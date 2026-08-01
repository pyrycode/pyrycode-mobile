# #586 — e2e: fail every live scenario on an unrecognized-message row (parser-gap sentinel)

**Size:** S (held from PO). **Production files touched: 0.** Test + harness + ladder-doc only.

## Design source

N/A — no UI is added or changed. The ticket ships a test guard and a doc note; the row it detects
(`UnrecognizedMessageRow`) already shipped in #608 with its own Figma-anchored spec. The visual-fidelity
check is intentionally skipped.

## Files to read first

| Path | What to extract |
| --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:56-69` | The whole shared surface today: **one** `@get:Rule composeTestRule` + one derived string field. No `@Before`/`@After` — this is the "there is no shared live path" fact the ticket is built on. Every `@Test` below it is self-contained. |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:1205-1212` | `awaitConnected()` — the existing precedent for reaching into the Koin graph from this class (`GlobalContext.get().get<ConnectionStateSource>()`). The sentinel needs no such reach, but this is the idiom if you think you do. |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt:57-61` | The relay-mode `startKoin { modules(appModule, conversationRepositoryModule(useRelay = true)) }` call. This one line is where the tap is installed. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:109-113` and `:156-161` | Why the tap is non-circular: `StableConversationRepository` is registered **as its own concrete type** in `appModule`, and `conversationRepositoryModule` is the *only* definition binding the `ConversationRepository` interface. A decorator can `get<StableConversationRepository>()` without a cycle. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1324-1339` | **The load-bearing constraint.** `observeMessages` is `flow { pump.send(backfillSinceRequest(id)); emitAll(threadProjection(id)) }` — every subscription fires a **full-history backfill request on the live wire**. This is why the guard must *tap the app's existing subscription* instead of opening its own per-conversation collectors. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:288-331` | `ThreadItem.UnrecognizedMessage` field-by-field + `UnrecognizedSite`'s closed four-value enum. Note which fields are client-owned (`id`, `site`, `occurredAt`) vs. untrusted daemon strings (`messageType`, `raw`). |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:395-443` | Wire shape `{conversation_id, site, message_type, raw, truncated}` and the four `site` tokens (`line_type` / `assistant_block` / `user_block` / `undecodable`). The rung-2 envelope builder must match this byte for byte. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UnrecognizedMessageRow.kt:55-76` | The **"no logging: nothing on this path logs any payload field"** contract this ticket deliberately carves an exception out of. Read it before writing the failure message. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:141-154` | `pushApiRetry` / `pushCompacting` — the exact shape `pushUnrecognizedMessage` clones (thin `pump.push` + a private envelope builder at the file's bottom). |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:68-89` | Where the harness builds `RemoteConversationRepository` and hands it to `ThreadViewModel(repository = repo, …)`. **One-line change:** wrap in the tap. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedApiRetryTest.kt` (whole, 198 lines) | The rung-2 test-class idiom: `@get:Rule composeRule`, `@Before start()`, `@After close()`, tolerant `waitUntil` asserts. `ScriptedUnrecognizedMessageTest` clones this. |
| `docs/e2e-interactive-stream.md:962-989` | The two sibling carve-outs (#594 api-retry, #597 compacting). The new note goes next to these, in the same `## Follow-ups to ticket` bullet, and must state the **inverted** shape. |
| `docs/e2e-interactive-stream.md:704-769` | § "Interactive runner selection" (#614) — `report_interactive_runner` logs `interactive runner: <runner> (<reason>)` before **every** daemon spawn, in every mode. This is the already-existing machinery the live-path caveat points at; do not add more. |

## Context

The daemon forwards claude message kinds its stream-json parser cannot map as their own
`unrecognized_message` frame instead of dropping them. #609 decodes that frame and folds it into the
thread; #608 renders it as a collapsed row. Both are shipped — so a live run can now *produce* something
observable when claude adds a message kind.

This ticket turns that into a gate: every curated `LIVE=1` scenario becomes a sentinel for free. Red does
not mean broken; it means the daemon's measured ignore-list needs re-taking.

Two facts shape the design, both verified against the code rather than assumed:

1. **`observeMessages` is not free to subscribe.** It sends a full-history `backfill_since` on every
   subscription (`RemoteConversationRepository.kt:1324-1327`). A guard that opens its own collector per
   conversation would fire one full-history request per conversation on the operator's **real `$HOME`**
   — where LIVE runs and state accumulates — repeated on every conversation-list change. That is
   measurable extra wire traffic during a timing-sensitive real-claude turn, which fails AC #5 ("adds no
   flakiness of its own"). **The guard must therefore observe the subscription the app already makes,
   not open new ones.**
2. **Rung 4 has no emitter.** The daemon emits `unrecognized_message` only from the stream-json runner
   (`internal/streamsup/parser.go`); rung 4 runs PTY. Hence the non-vacuity proof lives on rung 2. This
   is the #594/#597 carve-out inverted — there rung 2 was the only surface *with* a producer; here rung 3
   has the emitter and rung 4 is the one without.

## Design

Three pieces, all in `androidTest`. No production file changes.

### 1. The tap — a pass-through decorator on the repository

```kotlin
internal class TappingConversationRepository(
    private val delegate: ConversationRepository,
) : ConversationRepository by delegate {
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
    // = delegate.observeMessages(id).onEach(UnrecognizedRowRecorder::record)
    // Records; never throws, never filters, never alters the emission. conversationId is
    // deliberately NOT passed to the recorder (see Security review, Trust boundaries).
}
```

Kotlin interface delegation (`by delegate`) means one override and zero maintenance as
`ConversationRepository` grows. The tap is **inert**: it observes an emission the app was already
receiving, issues no request, and adds no subscription.

Consequences to preserve:

- `onEach` must not throw. A throw would propagate into the app's own thread flow and break the screen
  under test. The recorder does bounded, non-throwing work only; **all** assertion happens in the rule.
- Coverage boundary: the tap sees a row only for a conversation the app is currently subscribed to. In
  practice that is the conversation whose live claude turn could produce the frame, and the frame routes
  strictly by `conversation_id` (`RemoteConversationRepository.kt:573-575`). **Document this boundary in
  the class KDoc; do not engineer around it** — no observed failure motivates the cost, and the cost is
  exactly the backfill storm rejected above.

### 2. The recorder + the finding function

```kotlin
internal object UnrecognizedRowRecorder {
    fun reset()
    fun record(items: List<ThreadItem>)              // filters to UnrecognizedMessage, accumulates
    fun observed(): List<ThreadItem.UnrecognizedMessage>
}

/** Null when [rows] is empty; otherwise the bounded, sanitized operator-facing failure text. */
internal fun unrecognizedFinding(rows: List<ThreadItem.UnrecognizedMessage>): String?
```

- **Process-global by necessity.** The Koin repository singleton outlives any one test, so the recorder
  is an `object` whose lifecycle the rule owns (`reset()` at test start). The rung-2 test resets in
  `@Before`.
- **Accumulating, not snapshotting.** This is what satisfies AC #3: once a row is seen mid-scenario, it
  stays recorded even after the scenario navigates to a list surface and the thread's collector is
  cancelled. The end-of-scenario check reads accumulated history, never live screen state.
- **Dedup by structural equality**, not by `id`. `observeMessages` re-emits the whole list on every
  change, so the same row arrives many times. Use a `LinkedHashSet<ThreadItem.UnrecognizedMessage>`:
  `data class` equality includes `occurredAt`, so it dedups re-emissions while staying immune to the
  `unrecognized-<n>` counter restarting at 1 when a reconnect builds a fresh repository.
- **Thread-safe.** Emissions arrive on the app's collector dispatcher; `reset`/`observed` run on the
  test thread. An `AtomicReference` holding an immutable set (`updateAndGet`) or a `synchronized` block
  — either is fine; pick one and say which in the KDoc.
- `unrecognizedFinding` is **pure** — this is what lets rungs 2 and 3 share one definition of "the guard
  fired" and one definition of what may be printed.

**Failure-message contract** (the security-critical surface — see Security review):

- Names the row count, and per row: `site` (enum `.name`, client-owned, verbatim), `messageType`
  (untrusted — sanitized + capped, see below), `truncated` (decoded `Boolean`, no injection surface).
- **Never** references `raw`. Never references a conversation id.
- `messageType` is bounded to a small cap (≈64 chars, with an explicit elision marker) and sanitized by
  **allowlist**: any character outside a conservative printable-ASCII set is replaced. Quote it so an
  empty value (the `undecodable` case) is visible as `""` rather than a blank.
- Cap the number of rows enumerated (≈5) with a trailing `+N more` line, so a flood cannot produce
  megabytes of instrumentation output.
- Close with a one-line operator instruction: this means the daemon's measured ignore-list needs
  re-taking, not that the client is broken.

### 3. The rule — the shared seam AC #2 asks for

```kotlin
class UnrecognizedRowSentinel : TestRule
// apply(base, description) →
//   1. UnrecognizedRowRecorder.reset()
//   2. base.evaluate()  — on throw: attach the finding via Throwable.addSuppressed, rethrow unchanged
//   3. on green: unrecognizedFinding(observed())?.let { throw AssertionError(it) }
```

Wired into `InteractiveStreamE2ETest` as **one new field**:

```kotlin
@get:Rule
val unrecognizedRowSentinel = UnrecognizedRowSentinel()
```

That is the entire per-class cost, and a ninth scenario added tomorrow inherits it with no line to
remember — AC #2.

Two decisions worth stating:

- **Rule ordering vs. `composeTestRule` is immaterial**, so do **not** introduce a `RuleChain` or
  `@Rule(order = …)`. The sentinel resets before the test body and checks after it either way; the
  recorder holds data independently of whether the activity has been torn down. Keeping the existing
  `@get:Rule val composeTestRule` declaration untouched is the smaller, safer diff.
- **`addSuppressed` on a red body is not decoration.** The most likely real-world manifestation of a
  parser gap is the scenario's *own* assertion timing out because the reply never rendered. Without the
  suppressed finding the operator sees a bare `waitUntil timed out` with no clue. Two lines, and it
  serves the ticket's actual purpose.

### 4. Installing the tap in the live graph

`E2eTestApplication`'s relay branch swaps its repository module for one that binds the interface to the
tap wrapping the concrete facade:

```kotlin
// Mirrors conversationRepositoryModule(useRelay = true) — see AppModule.kt:156-161 — and wraps.
single<ConversationRepository> { TappingConversationRepository(get<StableConversationRepository>()) }
```

**Replace** the `conversationRepositoryModule(useRelay = true)` argument rather than adding a second
module that overrides it. Koin 4.0.4's override semantics are not something this test should depend on;
replacement is unambiguous. The KDoc must point at `AppModule.kt:156-161` and state the mirror
obligation, since the two definitions can now drift.

The no-relay branch (ordinary instrumented runs, fake repository) is **untouched** — the tap ships only
where a real daemon can produce the frame.

`DeterministicInteractiveStreamE2ETest` (rung 4) also runs relay-backed and will therefore *record*
through the tap, but installs no rule and asserts nothing. That is correct and harmless: rung 4 has no
emitter, so a rule there would be permanently vacuous. Do not add one.

### 5. Rung-2 non-vacuity (AC #4)

`ScriptedThreadHarness` gains, cloning `pushApiRetry`'s shape exactly:

```kotlin
/** Script one `unrecognized_message` (#609) — [site] is the raw wire token, not the enum. */
fun pushUnrecognizedMessage(
    site: String,
    messageType: String,
    raw: String,
    truncated: Boolean = false,
)
```

plus a private `unrecognizedMessageEnvelope(...)` builder at the file's bottom emitting
`{"conversation_id":…,"site":…,"message_type":…,"raw":…,"truncated":…}`.

**`site` stays a raw `String`**, matching `pushSessionTransition`'s documented choice for `reason`: the
harness must be able to script an *unrecognized* site token to exercise the mapper's drop path.

**`raw` must be JSON-escaped**, unlike the existing builders' simple interpolation — a realistic `raw`
value is itself JSON and contains `"`. Build the payload with `kotlinx.serialization` (`buildJsonObject`
or the DTO) rather than string interpolation, or the fixture silently produces a malformed envelope that
the fold drops, and the test goes false-green. Same trap family as the `#593`/`#460` quoted-primitive
lesson: a decoder that accepts the wrong thing makes a probe prove nothing.

The harness also wraps its repository for the VM:

```kotlin
ThreadViewModel(repository = TappingConversationRepository(repo), …)
// repo.liveSessionEvents stays direct — the tap only decorates the ConversationRepository surface.
```

This is what makes the proof cover **the chain**, not a pure function in isolation: scripted envelope →
real `RemoteConversationRepository` fold → tap → recorder → `unrecognizedFinding`. Existing rung-2 tests
route through the tap too and are unaffected (it records; nothing asserts).

## State + concurrency model

- **No new coroutine scopes.** The tap adds an `onEach` to a flow the app already collects, on the
  collector's existing dispatcher. The harness change adds nothing — `ScriptedThreadHarness.scope`
  already owns the collector and `close()` already cancels it.
- **Recorder mutation** is the one piece of shared state: writes from the app's collector dispatcher,
  reads from the JUnit test thread. Guarded by an atomic swap of an immutable set (or `synchronized`) —
  no check-then-mutate, per the `update {}` discipline used throughout `RemoteConversationRepository`.
- **Cancellation is a non-event.** `onEach` adds no suspension point and no cleanup block; when the
  thread screen is popped the collector is cancelled exactly as before.
- Cold/hot is unchanged: `observeMessages` stays cold and per-collector. The tap does **not** share,
  cache, or `stateIn` anything.

## Error handling

| Failure mode | Behaviour |
| --- | --- |
| Row produced during an otherwise-green scenario | `AssertionError` with the bounded finding → scenario red (AC #1). |
| Row produced during a scenario that fails for its own reason | Original throwable propagates unchanged, finding attached via `addSuppressed`. Primary cause is never masked. |
| Recorder read while a write is in flight | Atomic snapshot; a row arriving after the read is simply not in this scenario's finding. Acceptable — the row is produced during the turn, long before teardown. |
| Malformed scripted envelope at rung 2 | The real fold drops it (`decodeUnrecognizedMessage` returns null) and the guard never fires — a **false green**. Mitigated by building the fixture through `kotlinx.serialization`, and by the negative-shape scenario below. |
| App never subscribes to the affected conversation's thread | Row not observed. Documented boundary, not engineered around (see Design §1). |
| Live runner resolves to PTY | Sentinel is structurally incapable of firing. **Documented, not engineered** — `report_interactive_runner` (#614) already prints the resolved runner before every daemon spawn, so the condition is visible in the output of every run. |

## Testing strategy

All instrumented (`./gradlew connectedAndroidTest`); there is no unit-test surface here. Per the memory
lesson, `androidTest` is **not** compiled by `test` / `lint` / `assembleDebug` — the gate list for this
ticket must include `compileDebugAndroidTestKotlin`.

**Rung 2 — `ScriptedUnrecognizedMessageTest`** (new; clone `ScriptedApiRetryTest`'s class shape):

- *Guard fires on a scripted row.* Push `site = "assistant_block"`, a distinctive `messageType`, and a
  realistic quote-bearing `raw`. Wait until the recorder reports a row, then assert
  `unrecognizedFinding(...)` is non-null. This is the AC #4 proof.
- *The finding names site and message type.* Assert the returned text contains `AssistantBlock` (or the
  chosen site rendering) and the pushed `messageType`.
- *The finding never contains the payload body.* Push a `raw` carrying a unique sentinel token that
  appears nowhere else, and assert the finding does not contain it. This is the security AC, and it must
  be its own assertion, not a comment.
- *`messageType` is sanitized and bounded.* Push a `messageType` containing a newline, an ANSI escape,
  and >64 characters; assert the finding contains none of the raw control characters and is bounded.
- *Clean harness ⇒ no finding.* With no `unrecognized_message` pushed (drive an ordinary
  delta/`turn_end` pair instead), assert `unrecognizedFinding(observed())` is null — the rung-2 mirror
  of AC #5, and the fence proving the previous assertions are not matching everything.
- *An unrecognized `site` token drops.* Push `site = "not_a_site"`; assert no finding. Proves the probe
  is exercising the real mapper (`toRow()` returns null off the closed set), not a shortcut.

Reset the recorder in `@Before` (the harness is per-test, the recorder is not).

**Rung 3 — the live class:** no new test method. The sentinel rule is the deliverable; AC #5 is
discharged by the existing eight curated methods staying green on a clean `LIVE=1` run. Note in the PR
that the operator must run the gate once to confirm.

**Rung 4:** unchanged. Explicitly out of scope (#613).

## Documentation (`docs/e2e-interactive-stream.md`)

Two edits, both in existing sections — no new top-level section:

1. **§ "Layer 1 — component render harness (rung 2)"**, the piece-table after the #435 split: one row
   for `pushUnrecognizedMessage` + `ScriptedUnrecognizedMessageTest.kt` (#586), matching the
   `pushToolUse` / `pushConnectionState` / `pushSessionTransition` rows' phrasing.
2. **§ "Follow-ups to ticket"**, immediately after the #594/#597 carve-outs (`:962-989`): the
   parser-gap sentinel bullet. It must record, in the siblings' voice:
   - the guard's shape (a rule on the live class, tapping the app's own `observeMessages` subscription,
     accumulating across the scenario so a list-ending scenario is still covered);
   - **why the non-vacuity proof cannot live on the deterministic rung** — the daemon emits
     `unrecognized_message` only from the stream-json runner; rung 4 runs PTY, so a fixture line with an
     unknown `type` produces nothing; and rung 4 cannot be flipped (#613, blocked on an upstream
     `fakeclaude` capability with no ticket);
   - that this is the #594/#597 carve-out **inverted** — there rung 2 held the only producer; here rung 3
     holds the emitter and rung 4 is the one without;
   - the **live-path caveat**: on `LIVE=1` the runner comes from the operator's real `~/.pyry/config.json`
     and `INTERACTIVE_RUNNER` is refused in preflight, so the sentinel can only fire when that resolves
     to stream-json — visible in every run's output via #614's `interactive runner:` line. Recorded as a
     known condition; no machinery added for a failure nobody has hit.

## Open questions

1. **Site rendering in the finding** — enum `.name` (`AssistantBlock`) or the wire token
   (`assistant_block`)? The wire token is what an operator would grep the daemon for; the enum name is
   what the Kotlin reader sees. Developer's call — pick one, state it in the KDoc, and assert on it.
2. **The `messageType` allowlist** — printable ASCII minus quotes is the conservative default. Widening
   it to include non-ASCII would make a non-Latin message kind readable, but that is speculative; start
   narrow and note the tradeoff.
3. **Recorder growth in rung-4 runs** — the tap records with no rule to reset it. Bounded in practice
   (rung 4 has no emitter, so the set stays empty). If the developer prefers a belt, an explicit cap on
   the recorded set is deterministic code and cheap; not required.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings — the boundary is explicit and single. Untrusted daemon strings
  enter through exactly one production function (`decodeUnrecognizedMessage`,
  `RemoteConversationRepository.kt:797`) and reach this ticket already typed as
  `ThreadItem.UnrecognizedMessage`. Within the ticket the second boundary — untrusted value → operator
  output — is also single: `unrecognizedFinding` is the *only* function permitted to read
  `messageType`, and it is a pure function taking `List<ThreadItem.UnrecognizedMessage>` and returning
  `String?`. The tap and the recorder move rows without reading their fields. Reviewability is the point:
  one function to audit, and the tests above assert its two bounds (no `raw`, sanitized `messageType`).
- **[Error messages, logs, telemetry]** **The category this ticket exists inside; two findings, both
  addressed in the design above.**
  (a) *Deliberate carve-out from a stated contract.* `UnrecognizedMessageRow.kt:59-72` and
  `ConversationRepository.kt:295` both state that nothing on this path logs any payload field. This
  ticket prints `messageType` on test failure. The carve-out is justified — a sentinel that says "a
  parser gap occurred" without naming the kind is undiagnosable — and is narrowed three ways: it is
  `androidTest`-only (never in a shipped APK, never in Logcat on a user device), it is operator-local
  (instrumentation output on the operator's own machine, on a run they invoked), and it excludes `raw`
  entirely. **The spec must state, and the tests assert, that `raw` is never read** — done above.
  (b) *Log-forging / terminal-injection via `messageType`.* An untrusted string printed into a
  line-oriented report can contain `\n` to forge additional finding lines, or ANSI escapes to manipulate
  the operator's terminal. Addressed: allowlist sanitization plus a length cap plus a row-count cap,
  each with its own asserted test scenario. Rated SHOULD FIX rather than MUST FIX only because the
  design already carries the mitigation; code-review must verify the allowlist is an allowlist (replace
  everything outside a permitted set) and not a blocklist of a few known-bad characters.
  (c) No conversation id reaches the output. `unrecognizedFinding` takes only rows, and
  `ThreadItem.UnrecognizedMessage` carries no conversation id — so the cross-conversation correlation
  leak that production's own KDoc warns about (`RemoteConversationRepository.kt:585-587`) is prevented
  *structurally*, not by discipline. The tap does hold `conversationId` as a parameter; the spec
  requires it not be passed to the recorder.
- **[Concurrency]** No findings. No new scope, no new subscription, no cancellation-sensitive cleanup.
  The one shared mutable is the recorder set, specified as an atomic immutable-swap (no check-then-
  mutate). The `onEach` is required non-throwing, so a recorder defect degrades to a missed finding
  rather than to a corrupted app-under-test — fail-safe in the right direction for a *test* guard,
  since the sentinel is a safety net, not a correctness dependency of the app.
- **[Network & I/O]** No findings, and this is the category that shaped the design. Rejecting the
  independent-collector approach removed the ticket's only would-be network side effect: N full-history
  `backfill_since` requests per conversation-list change on the live wire
  (`RemoteConversationRepository.kt:1324-1327`). The chosen tap issues **zero** requests. No timeout,
  TLS, or framing surface is touched.
- **[Tokens, secrets, credentials]** Not applicable, and specifically so: the ticket adds no credential
  handling, and the one place it touches credential-adjacent code — `E2eTestApplication`'s relay branch
  — is edited only at the `modules(...)` argument. The `PairedServer` construction, the arg reads, and
  the `store.save` block are untouched. Note that `E2eTestApplication.kt:48` already logs the relay URL
  and server id; the tap adds nothing to that and this ticket does not widen it.
- **[File / storage operations]** Not applicable — no path is constructed, read, or written. The
  recorder is in-memory and process-scoped.
- **[Inter-process / Android attack surface]** Not applicable — no manifest change, no exported
  component, no intent, no deep link, no `PendingIntent`. `androidTest` sources are not present in a
  release APK.
- **[Cryptographic primitives]** Not applicable — no randomness, no hashing, no key material. Row
  identity uses `data class` structural equality, which is a correctness choice (see Design §2), not a
  security one.
- **[Threat model alignment]** The relevant mobile-side threat is a **hostile or buggy daemon**, which
  the production fold already treats as the adversary (capability gate, decode-or-drop, closed `site`
  set, no logging). This ticket must not weaken that posture, and does not: it reads only already-
  decoded values, and its one new exposure (`messageType` in operator-local test output) is bounded and
  sanitized. Out of scope and named: rung-4 injection of a synthetic frame is **#613**, blocked on an
  upstream `fakeclaude` capability with no ticket yet.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-08-01
