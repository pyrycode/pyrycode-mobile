# #473 — Layer 1b: session-boundary divider component test

**Size:** S · **Security-sensitive:** no · **Split from:** #435

A pure test change. **No production code** — both touched files are under `app/src/androidTest/`. Extends the #432 `ScriptedThreadHarness` with one session-transition scripting method and adds one component test asserting a session-boundary delimiter draws between two messages, driven through the **real** `RemoteConversationRepository` fold (#336).

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:121-164` — the existing `push*` scripting methods (each a thin `pump.push(<builder>Envelope(...))`). The new `pushSessionTransition` slots in here in the same shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:239-333` — the private envelope builders (`assistantDeltaEnvelope`, `toolUseEnvelope`, …) + the file-level `TS` constant (line 220). The new `sessionTransitionEnvelope` slots in beside them.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:3981-4000` — the unit-test `sessionTransitionEnvelope(conversationId, previousSessionId, newSessionId, reason, occurredAt, workspaceCwd, id)` builder. **Port it verbatim** into the harness file (it depends only on `main/` types: `Envelope`, `MobileJson`); the wire field names live here (`conversation_id`, `previous_session_id`, `new_session_id`, `reason`, `occurred_at`, `workspace_cwd`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt:1-137` — **the test idiom to mirror.** `@get:Rule createComposeRule()`, `@Before harness.start()`, `@After harness.close()`, the `pushAssistantDelta(...) + pushTurnEnd(...)` → finalized-message pattern (lines 62-77), the `waitUntil { onAllNodes…fetchSemanticsNodes().isNotEmpty() }` gate. This is the closest sibling — your new file is a fourth test alongside it.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt:112-124` — **the in-repo positional-assertion precedent.** `onNodeWithText(...).getUnclippedBoundsInRoot().top` for two rows + `assertTrue("…", firstTop < secondTop)`. Reuse this exact shape for the "delimiter is between m1 and m2" assertion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:91-118` — **the exact rendered strings to assert on.** The reason-independent explanation `Text` (line 92: `"Claude doesn't remember messages above this line. Install a memory plugin to preserve context. "`) and the per-reason `boundaryLabel` (lines 113-117: `Clear → "New session — <time>"`). No test tag exists; assert these strings (the ticket's chosen cheapest path).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:243-260` — confirms the render path: the `LazyColumn` keys `ThreadItem.SessionBoundary` rows and renders each via `SessionBoundaryDelimiter(boundary = item)`. Read-only confirmation; not changed.
- `docs/specs/architecture/336-session-boundary-fold-remote-thread.md` — the production fold this test exercises; its **Testing strategy** (the unit suite at `RemoteConversationRepositoryTest.kt:2850-3050`) already exhaustively covers fold ordering, reason-mapping, cross-routing, and drop-on-malformed at the **data** layer. This component test adds only the missing rung: a folded boundary actually **draws** as a delimiter on screen.
- `docs/e2e-interactive-stream.md` — the ladder-doc rule: assertions are **tolerant** (substring / presence / coordinate-ordering, generous `waitUntil` timeouts), never on delta counts or timing.

## Context

Part of the mobile e2e test ladder, Layer 1b (component level, no network/daemon/emulator). The thread renders a session-boundary delimiter from a synthetic `ThreadItem.SessionBoundary`. As of #336 (PR #485), the production `RemoteConversationRepository` folds the capability-gated `session_transition` wire event into a `SessionBoundary` interleaved with messages in arrival order — so this test exercises the **real production fold**, not the boundaries `FakeConversationRepository` synthesizes from in-memory history (the two-folds gotcha, #337: drive the real repo, not the fake and not the dormant VM path).

The `ScriptedThreadHarness` already injects the real graph (`FakeSessionPump → RemoteConversationRepository → ThreadViewModel → ThreadScreen`). The only gap is a scripting method to inject a `session_transition` envelope; everything downstream (decode, fold, render) already ships and is untouched.

This ticket was `blockedBy #336`; that blocker has shipped, so the test can now land.

## Design source

N/A — pure test change. The exercised UI (`SessionBoundaryDelimiter`, #135/#192) shipped and was design-reviewed; this ticket adds no UI and reproduces no design. The visual-fidelity check is intentionally not re-run here.

## Design

Two additive changes, both under `app/src/androidTest/`. **Zero production-source files.**

### 1. Extend `ScriptedThreadHarness` (1 method + 1 builder)

A new public scripting method, mirroring the existing `push*` methods (thin `pump.push` over a builder):

```kotlin
/** Script one `session_transition` — folds a SessionBoundary into the thread in arrival order (#336). */
fun pushSessionTransition(
    previousSessionId: String,
    newSessionId: String,
    reason: String,
    workspaceCwd: String? = null,
) = pump.push(sessionTransitionEnvelope(conversationId, previousSessionId, newSessionId, reason, workspaceCwd))
```

- Routes on the harness's own `conversationId` (the seeded `"c1"`), exactly like `pushAssistantDelta` / `pushToolUse` — so the folded boundary lands in the thread the screen observes. No `conversationId` parameter is exposed (the harness is single-conversation; cross-routing is a data-layer concern already unit-tested in #336).
- `reason` stays a plain `String` (matching the wire and the unit-test builder); the harness does not constrain it to the recognized set.

A new private builder beside the others (lines 239-333), **ported verbatim** from `RemoteConversationRepositoryTest.kt:3981-4000`, using the file-level `TS` constant for both `ts` and the default `occurred_at`:

```kotlin
private fun sessionTransitionEnvelope(
    conversationId: String,
    previousSessionId: String,
    newSessionId: String,
    reason: String,
    workspaceCwd: String? = null,
    occurredAt: String = TS,
    id: Long = 1L,
): Envelope  // raw-JSON payload; emits "workspace_cwd":null when null, quoted string otherwise
```

Keep the public scripting surface minimal: `pushSessionTransition` is the only new public method (the KDoc on the class already states this extension contract).

### 2. New test file `ScriptedSessionBoundaryTest.kt`

A new file in the same package (`de.pyryco.mobile.ui.conversations.thread`), structured exactly like `ScriptedThreadRenderTest` — `@get:Rule val composeRule = createComposeRule()`, `@Before { harness = ScriptedThreadHarness(composeRule); harness.start() }`, `@After { harness.close() }`, `private companion object { const val TIMEOUT_MS = 5_000L }`.

**ktlint filename rule:** the file's single public class must be `ScriptedSessionBoundaryTest` to match the filename (single-class-per-file rule; internal/public both count). Any per-reason label constant goes in the `companion object`, not a second top-level declaration.

**The scenario (one `@Test`):** script a stream that crosses a session boundary —

1. `pushAssistantDelta("t1", 0, "<m1>")` then `pushTurnEnd("t1")` — message 1 (first session), finalized (no caret).
2. `pushSessionTransition(previousSessionId = "s1", newSessionId = "s2", reason = "clear")` — the boundary.
3. `pushAssistantDelta("t2", 0, "<m2>")` then `pushTurnEnd("t2")` — message 2 (second session), finalized.

The two assistant turns (distinct `turn_id`s) are the two "messages in two consecutive sessions"; the harness exposes no `message`-envelope scripting and needs none — the already-wired `assistant_delta → turn_end` path produces rendered message rows (see `ScriptedThreadRenderTest.text_…`). Pick distinctive, plain-alphanumeric message texts that collide with neither each other nor the delimiter strings (e.g. `"alpha line"` / `"omega line"`); MarkdownText renders plain text 1:1 so substring matching is reliable. `reason = "clear"` is the chosen scenario reason (workspace-null, label `"New session — <time>"`); any recognized reason works, `clear` is the simplest.

See **Testing strategy** for the assertions.

## State + concurrency model

- **Why push-after-`start()` is safe.** The thread rows (messages + boundary) fold into the repository's retained `threadByConversation` `StateFlow`, projected cold-but-state-backed through `observeMessages` → `ThreadViewModel.state`. Unlike the `replay = 0` `liveSessionEvents` stream (isThinking / tool timeline), a row folded after the collector subscribes is retained and re-projected. `harness.start()`'s `awaitReady()` proves `state` has subscribed (seeded name in the top bar), so every `push*` after it surfaces. No extra readiness gate needed for the boundary.
- **Arrival order is deterministic.** All envelopes traverse one `Channel(UNLIMITED)` → the single inbound collector → serial folds. `trySend` preserves enqueue order, so the store converges to `[MessageItem(t1), SessionBoundary, MessageItem(t2)]`. The test asserts only after the last message renders, by which point the boundary and m1 are already folded into the same projected list.
- **No new coroutine / scope / dispatcher.** The harness owns its `scope`; `close()` (from `@After`) cancels it. The test introduces none of its own.

## Error handling

Not applicable to the test. The fold's failure modes (capability gate closed, malformed payload, unknown `reason`, cross-routing) are **not re-tested here** — they are exhaustively covered at the data layer by `RemoteConversationRepositoryTest` (#336, `:2850-3050`). Re-asserting them through the slow androidTest surface would duplicate coverage and add flake surface for no new signal. This component test's single new rung is **render**: a folded boundary draws a delimiter on screen, positioned between its neighbours.

## Testing strategy

Instrumented only — `./gradlew connectedAndroidTest` (device/emulator required), alongside `ScriptedThreadRenderTest` / `ScriptedToolRowTest`. No unit tests. Confirm the file compiles with `./gradlew compileDebugAndroidTestKotlin` (the mandatory unit-test/lint/assemble gates do **not** compile `androidTest`; see the project-memory note on that gap).

One `@Test`, asserting the four ACs against the scripted stream above. Assertions are tolerant per the ladder-doc rule; gate every text/geometry read behind a `waitUntil` on the slowest-arriving signal (the second message text), so the full sequence has folded before any bounds are read.

- **AC #4 — rides the extended harness.** Satisfied structurally by the test compiling and running on the new `pushSessionTransition` method; no separate assertion.
- **AC #3 — drives the real fold.** Satisfied structurally — `ScriptedThreadHarness` constructs the real `RemoteConversationRepository` (not the fake); document it in the test KDoc. No separate assertion.
- **AC #2 — explanatory label renders.** Assert the reason-independent explanation `Text` is displayed (substring `"Claude doesn't remember messages above this line"`) **and** the per-reason label is displayed (substring `"New session"` — the hardcoded `Clear` prefix; assert on the prefix, not the locale/timezone-formatted `<time>` tail, to stay locale-robust).
- **AC #1 — exactly one delimiter, between the two messages.**
  - *Exactly one:* `onAllNodesWithText("Claude doesn't remember messages above this line", substring = true).fetchSemanticsNodes().size` equals `1`. This is a structural one-to-one (one `session_transition` → one folded boundary → one rendered delimiter), deterministic and timing-independent — not the kind of delta-count the ladder-doc forbids.
  - *Between:* mirror `QueuedBacklogTest.kt:112-124` — read `getUnclippedBoundsInRoot().top` for the m1 text node, the explanation `Text` node, and the m2 text node; `assertTrue(m1Top < delimiterTop && delimiterTop < m2Top)`. All three rows fit on screen (two short messages + one delimiter), so all are composed and laid out — `getUnclippedBoundsInRoot` is safe and deterministic after `waitForIdle`. Use `useUnmergedTree = true` on the text reads (as `QueuedBacklogTest` does) so the message text inside `MessageBubble` resolves to its own bounded node.

Optional (developer's call, not required): a second `@Test` scripting `reason = "workspace_change"` with a non-null `workspaceCwd` to render the `"Workspace changed to <cwd>"` label (the `workspaceCwd!!` branch in `boundaryLabel`). Marginal — that branch already renders in the `SessionBoundaryDelimiter` previews; the primary test fully covers the ACs. Skip unless trivially cheap.

## Open questions

- **Positional assertion vs pure presence.** AC #1 says "positioned between," so the spec prescribes the geometry check (`getUnclippedBoundsInRoot().top` ordering) over a weaker presence-only assertion — it directly honors the AC, is deterministic (no timing), and has an in-repo precedent (`QueuedBacklogTest`). If the geometry read proves flaky on the target emulator (it should not — all rows are on-screen), fall back to asserting all three nodes are displayed and lean on #336's unit-tested fold order; flag it to code-review rather than silently weakening the assertion.
- **`useUnmergedTree` for the message text.** `QueuedBacklogTest` uses `useUnmergedTree = true` for its row-text bounds; `ScriptedThreadRenderTest` does not (it only checks presence). Use the unmerged tree for the bounds reads to get the tight text-node rect; presence/count reads can use either. The developer confirms against the actual `MessageBubble` semantics at write time.
