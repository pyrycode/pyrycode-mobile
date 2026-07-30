# #597 — Render "Compacting conversation" in the thread status slot

Renders the data path #596 shipped: hoist `ConversationRepository.observeCompacting` into
`ThreadViewModel`, and extend the foot-of-list status slot from a two-way to a three-way branch so a
compacting conversation says so instead of showing an indefinite generic spinner.

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:326-361` | The two hoist precedents back to back — `isStalled` (`:334`, the `Boolean` shape this clones) and `apiRetry` (`:354`). Copy `isStalled`'s `stateIn` posture verbatim; read `apiRetry`'s KDoc only to see the "no dedup may be added" caveat you must **not** carry over. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:94-120` | The parameter block. `isThinking` / `isStalled` / `apiRetry` all sit in the defaulted group **after** `modifier` — the new param goes there too (see § Design 3 on the lint rule). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:304-328` | The status slot as it stands: `QueuedBacklog` → the #594 two-way `if` (`:319-322`) with its precedence comment (`:313-318`) → `InterruptAffordance` (`:327`). This is the only production site that changes behaviour. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:203` | `StallPromotionBanner` — a **separate** affordance above the list, outside this slot. Nothing here touches it (§ Security review, finding 9). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ApiRetryIndicator.kt:39-103` | The clone target's KDoc + composable body. Take the layout constants, the `semantics(mergeDescendants = true) { contentDescription = … }` idiom, the early-return posture, and the two `@Preview`s. **Do not** take `isRenderableCounter` / `MAX_PLAUSIBLE_ATTEMPTS` (`:105-126`) — there is no counter to gate. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt:30-68` | The `Boolean`-driven sibling — the closer shape of the two. Your composable is this file with a different label pair. |
| `app/src/main/res/values/strings.xml:54-59` | The label + `cd_*` naming convention for the two existing status affordances; append beside them. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt:359-380` | The three-line wiring pattern: `collectAsStateWithLifecycle()` beside `isStalled` / `apiRetry`, then pass through. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1277-1291` | `observeCompacting`'s KDoc + body. Confirms `distinctUntilChanged` is already applied and that membership-over-empty-set means no default is needed. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:84` | The `flowOf(false)` interface default — why a plain `FakeConversationRepository` stays inert with no override. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1204-1246` | The three `isStalled` JVM tests — the exact template for AC #4's first level. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:2899-2914` | `StallControllableRepo` — the `ConversationRepository by delegate` double, ~11 lines, that your new double clones. (Note: named `StallControllableRepo`, not the `StallingFakeRepository` some notes call it.) |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:68-117` | Repo/VM construction (capability gate already open) and the `start()` composition — the one line you add there. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:132-144, 305-319` | `pushApiRetry` + `apiRetryEnvelope` — the scripting-method and envelope-builder pair to clone, including the "retained `MutableStateFlow`, no subscribe-before-push hazard" note that applies verbatim. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedApiRetryTest.kt` | The whole file: scenario-class shape, `awaitDisplayed` / `awaitGone` / `assertNoTextContaining` helpers, and the `assertDoesNotExist`-on-the-other-status discipline. |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:210-213` | `CompactingPayloadDto` — the exact wire field names (`conversation_id`, `active`) your envelope builder must emit. |
| `docs/e2e-interactive-stream.md:893-905` | The API-retry deferred-coverage entry — the template AC #5 clones. It is a clause inside one long `- **Coverage:**` bullet, not its own bullet. |
| `docs/knowledge/features/compacting-state.md` | #596's data path in prose: why the observable is a bare `Boolean` with no progress field. |
| `docs/knowledge/features/api-retry-indicator.md`, `docs/specs/architecture/594-api-retry-status-render.md` | The immediately-preceding render slice, spec and knowledge doc. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:3297, 3552` | The two **decoy** `turn_state` cases pushing the phase string `"compacting"` and asserting it is dropped. Correct as they stand — do not touch, and do not read compaction from a phase mapping. |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `16-8` ("Conversation Thread Screen") draws a `Schemes/surface` column: top app bar, the message
list (M3 `body-medium` bubbles on `Schemes/primary-container` / `Schemes/surface-container-high`, a
mono tool chip, a session delimiter), then the bordered "Status row" (`Opus 4.7 · high · 73% used`)
and the composer. Confirmed by reading both the design context and the rendered screenshot: **no
status-affordance row is drawn** between the message list and the status row — the same design-owed
gap `ThinkingIndicator`, `ApiRetryIndicator` and `StallPromotionBanner` already ship against. This
slice therefore follows the app's existing M3 progress idiom (16dp indeterminate
`CircularProgressIndicator` + `bodySmall` / `onSurfaceVariant` label at 16dp × 8dp padding), pixel-
identical to its two siblings, with no contract change when the frame lands.

## Context

Auto-compaction makes a remote head look dead: claude goes silent on the content channel for tens of
seconds while the generic spinner keeps turning. #596 (PR #600, `da4c3f9`) reduced the `compacting`
wire envelope to `observeCompacting(conversationId): Flow<Boolean>`, defaulted on the interface and
plumbed through the facade. Nothing renders it yet. This is the visible half, and the same
data-slice → render-slice pair as #395 → #396 (stall) and #593 → #594 (api-retry).

Three properties of the state drive the whole design:

- **On/off only.** No counter, percent, or ETA exists on the wire, so an indeterminate affordance is
  the honest rendering. No progress bar.
- **Conversation-level, not turn-scoped.** It neither opens nor closes a turn, so it decorates the
  existing status slot rather than altering the turn lifecycle — it must show even when `turn_state`
  says `idle`.
- **Purely additive.** Production's stream-json runner has no emitter for the frame, so a live daemon
  today never sends it. Every existing thread must render byte-identically.

## Design

Four production files, one new. No constructor, DI, Koin-module, interface, or data-model change: the
repository method already exists on the interface `ThreadViewModel` already holds.

### 1. `ThreadViewModel.isCompacting` — the hoist

New public `StateFlow<Boolean>` immediately after `apiRetry` (`ThreadViewModel.kt:361`), a verbatim
clone of `isStalled`'s shape:

```kotlin
val isCompacting: StateFlow<Boolean> =
    repository
        .observeCompacting(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
```

A sibling flow beside `connectionState` / `isThinking` / `isStalled`, **not** a `ThreadUiState` field
— like them it is a transient, connection-scoped cross-cutting signal the stateless screen takes as a
separate parameter. `false` covers "no live connection", "not compacting", and "no frame ever
received".

**No extra flow operator.** `observeCompacting` already applies `distinctUntilChanged` in the remote
impl and defaults to `flowOf(false)` on the interface and facade. Do **not** clone `apiRetry`'s "and
none may be added" KDoc caveat: that warning exists because a climbed counter must survive as a fresh
emission, and it inverts here — a `Boolean` has no intermediate values to collapse, so dedup is
correct and already applied. Say so in the KDoc, so a future reader does not import the inverted rule
by pattern-match.

### 2. `CompactingIndicator` — new composable

New file `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CompactingIndicator.kt`
(ktlint's filename rule: one top-level declaration, file named after it).

```kotlin
@Composable
fun CompactingIndicator(isCompacting: Boolean, modifier: Modifier = Modifier)
```

Stateless, a pure function of `isCompacting`, early-returns when `false`. Structurally
`ThinkingIndicator` with a different label pair: `Row` → 16dp `CircularProgressIndicator` (2dp
stroke) + 8dp gap + `Text` in `bodySmall` / `onSurfaceVariant`, wrapped in
`.semantics(mergeDescendants = true) { contentDescription = … }` so it reads as **one** merged node
(AC #1). Reuse the sibling files' private layout constants by re-declaring them at file scope, as
`ApiRetryIndicator` does — do not extract a shared constants file (out of scope; "don't refactor
adjacent code").

The early return is redundant with the screen's branch and deliberately kept: it makes the composable
total and matches both siblings' defence-in-depth posture (`ApiRetryIndicator.kt:47-49` documents
exactly this).

Two `@Preview`s (light + dark, `widthDp = 412`), cloned from the siblings.

Strings — append beside `cd_thread_api_retry_unknown` in `strings.xml`:

- `thread_compacting_label` = `Compacting conversation`
- `cd_thread_compacting` = `Claude is compacting the conversation`

Neither takes a format argument: nothing daemon-supplied reaches either string (§ Security review,
finding 1). The `cd_*` string is also the androidTest handle — every rung-2 assertion resolves it and
queries `onNodeWithContentDescription`.

### 3. `ThreadScreen` — the slot becomes three-way

New defaulted parameter `isCompacting: Boolean = false`, placed in the defaulted group **after**
`modifier` (next to `apiRetry`). Placing a new defaulted `@Composable` param before `modifier` or
before trailing lambdas is an Android Lint `ComposeParameterOrder` **error** that compiles and passes
`test`/`assembleDebug` but fails `lint`, and `spotlessApply` does not fix it.

`ThreadScreen.kt:319-322` becomes a `when` with three arms, in this precedence:

1. `apiRetry != ApiRetryStatus.NotRetrying` → `ApiRetryIndicator`
2. `isCompacting` → `CompactingIndicator`
3. else → `ThinkingIndicator(isThinking = isThinking)`

Exactly one affordance renders; the arms never stack. Two things about this ordering:

- **Precedence stays single-sourced in the screen, not the ViewModel** — #594's deliberate decision,
  extended rather than revisited. `isThinking` keeps meaning "the `turn_state` phase" (other tests
  assert it directly), so suppressing it at its source would make the VM's contract lie. Extend the
  existing comment at `:313-318` rather than replacing it.
- **api-retry keeps the top arm.** The two have never been observed overlapping and this ticket
  spends no AC on it, but the tie-break needs a reason on record: api-retry is the "something is
  going wrong" signal and compaction is benign progress, so the benign affordance must never mask
  the alarming one. This is also the minimal delta to the shipped decision. Put that sentence in the
  comment — it is the render-side half of the security question (§ Security review, finding 9).

Untouched, and must stay so: `StallPromotionBanner` (`:203`, a separate affordance above the list —
compacting never suppresses the stall CTA) and `InterruptAffordance` (`:327`, below the slot — a
compacting conversation stays interruptible).

### 4. `MainActivity` — wiring

`val isCompacting by vm.isCompacting.collectAsStateWithLifecycle()` beside the `isStalled` / `apiRetry`
lines (`:359-361`), passed as `isCompacting = isCompacting` in the `ThreadScreen(` call (`:378-380`).
Two lines.

### 5. What does **not** change

`ThreadUiState`, `ThreadEvent`, `ConversationRepository`, `RemoteConversationRepository`,
`StableConversationRepository`, `FakeConversationRepository`, the Koin modules, `ThreadStatusRow`
(the model/effort/token row — despite the name, not this slot), and the ~27 other `ThreadScreen(`
call sites (the new param is defaulted to today's behaviour; the compiler proves no edit is needed —
see § Scope audit).

## State + concurrency model

- **One new flow, no new coroutine.** `stateIn(viewModelScope, WhileSubscribed(5_000), false)` —
  identical lifecycle to `isStalled` / `apiRetry`. `viewModelScope` cancels on screen exit; the
  5-second grace keeps the upstream alive across configuration changes.
- **Cold upstream, per-collector.** `observeCompacting` is a cold `map` over the repository's shared
  `MutableStateFlow<Set<String>>`, so a late subscriber immediately sees the current membership —
  there is **no** subscribe-before-push hazard of the kind the `replay = 0` `liveSessionEvents` path
  has. This is what lets the rung-2 scenarios push edges without a readiness gate beyond
  `harness.start()`.
- **Dispatcher:** none specified. Pure in-memory projection, no IO; the collector runs on the
  VM's default (Main) like its siblings.
- **Recomposition:** `Boolean` is a stable type, so the new `ThreadScreen` parameter adds no
  instability. The composable holds no `remember`ed or derived state — the label is a constant
  `stringResource`, so there is nothing to freeze.
- **No shared mutable state added.** The read-modify-write on the id `Set` lives entirely in #596's
  repository; this layer only observes.

## Error handling

There is no failure mode to surface at this layer, and that is a design statement rather than an
omission:

- **Malformed / unknown frames** never reach here — `decodeCompacting` drops them silently at the
  repository boundary (#596), so the flow simply does not emit.
- **No live connection** yields `false` via the facade's default; the connection problem is already
  surfaced by `ConnectionBanner`, and this slice adds no second, competing error affordance.
- **A stuck-active flag** (daemon sets `active` and never clears) renders an indefinite
  "Compacting conversation". Deliberately undefended here — see § Security review, finding 9: #596
  already declined a timeout at the layer that owns the state, on the grounds that it would invent
  policy the wire contract does not define, and no occurrence has been observed. The mitigation this
  slice does owe is structural, not temporal: the interrupt affordance and the stall CTA both stay
  visible, so the operator is never trapped by the indicator.
- **Nothing is logged and nothing is thrown.** No `RelayLog` call, no snackbar, no one-shot channel.

## Testing strategy

Two levels, per AC #4. The second level alone is not enough: `androidTest` is not compiled or run by
`./gradlew check`, so a hoist proven only there is unproven by the mandatory gates — the gap #594's
code review flagged. Run `./gradlew compileDebugAndroidTestKotlin` explicitly; `test` / `lint` /
`assembleDebug` will not catch a broken instrumented source set.

### Level 1 — plain-JVM ViewModel tests (`ThreadViewModelTest.kt`)

Add a `CompactingControllableRepo` double beside `StallControllableRepo` (`:2904`): delegate the whole
interface to a seeded `FakeConversationRepository`, override only `observeCompacting` with a
controllable `MutableStateFlow(false)`, recording each observed id. ~11 lines.

Three tests in a new `// ---- #597: isCompacting projection over repository.observeCompacting ----`
section, cloning `:1204-1246`:

- **Inert by default** — a plain `FakeConversationRepository` inherits the `flowOf(false)` interface
  default; `vm.isCompacting.value` is `false` with no collector. (AC #3.)
- **Onset then clear** — with a collector running and `advanceUntilIdle()` between steps: flipping the
  double to `true` makes `isCompacting.value` true; flipping it back to `false` makes it false again.
  Both edges, at the level the mandatory gates run. (AC #1, AC #2.)
- **Routing** — the recorded observed ids are non-empty and all equal the VM's own `conversationId`,
  so a compaction on another conversation cannot drive this screen.

### Level 2 — rung-2 scripted render (`ScriptedCompactingTest.kt`, new)

Harness additions first (`ScriptedThreadHarness.kt`):

- Wire `isCompacting = vm.isCompacting.collectAsState().value` into `start()`'s `ThreadScreen` call.
- `fun pushCompacting(active: Boolean)` beside `pushApiRetry` (`:140`), delegating to a private
  `compactingEnvelope(conversationId, active)` builder — `type = "compacting"`, payload
  `{"conversation_id": …, "active": …}` per `CompactingPayloadDto`. KDoc the same
  retained-`MutableStateFlow` / no-subscribe-before-push-hazard note `pushApiRetry` carries.

New scenario class cloning `ScriptedApiRetryTest`'s structure and helpers (`awaitDisplayed`,
`awaitGone`, `assertNoTextContaining`, tolerant `waitUntil` timeouts, never counts or timing). Every
assertion resolves a `cd_*` string and queries `onNodeWithContentDescription`; every mutual-exclusion
claim gets an `assertDoesNotExist` on the *other* status:

- **Rising edge replaces the thinking label** — push `turn_state: thinking`, await the thinking
  description, push `compacting active=true`; the compacting description is displayed and the
  thinking description does not exist. (AC #1.)
- **Shows while the turn state is idle** — push `turn_state: idle` then `compacting active=true`; the
  compacting description is displayed, proving the status is conversation-level, not turn-scoped.
- **Falling edge reverts to thinking** — thinking + compacting on, then `compacting active=false`; the
  thinking description is displayed again and the compacting description does not exist. (AC #2.)
- **Falling edge while idle leaves no status** — compacting on, `turn_state: idle`, compacting off;
  neither description exists. Nothing sticks. (AC #2.)
- **No frame at all is unchanged** — push only `turn_state: thinking`; the thinking description is
  displayed and the compacting description never existed. (AC #3.)

Negative text assertions, if any are added, must use `useUnmergedTree = true` — the merged-semantics
node masks the child `Text`, and a merged-tree query is a false green.

Rungs 3 and 4 are not coverable; see AC #5 below.

### Doc entry (AC #5)

Append one `;`-separated clause to the `- **Coverage:**` bullet in `docs/e2e-interactive-stream.md`
(the entry ends at `:905`), cloning the API-retry clause immediately before it: rung 2 shipped, what
the scenarios drive, and the producer-side carve-out — the daemon emits `compacting` only from the
PTY-runner detector family, production (rung 3) runs the stream-json interactive runner with no
emitter, and rung 4's `fakeclaude` swaps claude but keeps the real daemon, so injecting the frame
there would be a daemon change and is out of scope for a client-only ticket. Keep the existing
contrast with the `@Ignore`d #482 spinner (a transient-signal gap, not a producer-side one).

No `docs/knowledge/` file is a deliverable here — the documentation phase writes
`docs/knowledge/codebase/597.md` from this spec plus the merged diff.

## Scope audit

Red lines, counted raw:

| Red line | Count | Verdict |
|---|---|---|
| New files | 2 (`CompactingIndicator.kt`, `ScriptedCompactingTest.kt`) | ≤ 3 ✓ |
| Total written lines (prod + tests + doc) | ~310 | ≤ 600 ✓ |
| New exported types | 3 (one composable, one VM `StateFlow`, one screen param) | ≤ 5 ✓ |
| Call sites needing simultaneous update | 1 | ≤ 10 ✓ |
| Acceptance criteria | 5 | ≤ 5 ✓ |
| Error/reject branches | 0 | ≤ 10 ✓ |
| Production `.kt` files (commit gate) | 4 | < 5 ✓ |

The call-site number is the one worth showing the working for. `ThreadScreen(` has ~28 call sites
(`MainActivity`, 5 previews, ~21 across nine androidTest classes). **Exactly one** — the harness's
composition — is edited, and by intent rather than by cascade. The other ~27 need no edit at all
because the new parameter is defaulted to the value that reproduces today's behaviour; this is not
the "mechanical edits collapse" rationalization the red lines forbid (that was pyrycode#75's
*required* parameter, where every site had to change), it is the absence of edits, and the compiler
enforces it. The measured precedent is exact: #594 added a defaulted parameter to this same
composable with the same call-site count and shipped in 4 production files / 151+21+14+2 lines. This
slice is strictly smaller — a `Boolean` where that one needed a sealed type, a counter, and a display
sanity gate.

Estimated split: `CompactingIndicator.kt` ~75 (KDoc-heavy per repo convention), `ThreadViewModel` ~15,
`ThreadScreen` ~12, `MainActivity` 2, `strings.xml` 2, `ThreadViewModelTest` ~50, harness ~25,
`ScriptedCompactingTest` ~120, doc ~12.

## Open questions

- **`liveRegion` semantics.** None of the four status affordances declares one, so a TalkBack user is
  not announced when the status changes. Out of scope — a pre-existing gap across the whole family,
  already noted as a PO follow-up during #594's code review. Do not fix it here for one affordance
  only; that would leave the family inconsistent.
- **Precedence if compaction and api-retry ever do overlap.** Resolved by fiat above (api-retry wins),
  unproven by any AC because the combination has never been observed. If it is ever seen in the wild,
  the decision to revisit is a one-line change in one `when`.

## Security review

**Verdict:** PASS

**Findings:**

- **[1. Trust boundaries]** No findings. The untrusted → trusted boundary is upstream and unchanged:
  `decodeCompacting` (`RemoteConversationRepository.kt:692`), where the daemon payload is consumed and
  only a `Pair<String, Boolean>` of primitives escapes. This slice adds **no** boundary — it consumes
  a `Boolean` already derived from `Set` membership. Concretely: the wire payload is
  `{conversation_id, active}` (`InteractivePayloads.kt:210-213`), so no counter and no free text
  exists to render; both new strings are literals with **no** format argument, and the composable
  interpolates nothing. There is therefore no display-sanitisation surface of the kind #594 carried
  (its `isRenderableCounter` gate exists because two daemon-supplied ints reached the label) — the
  correct action is to *not* clone that gate, and the absence is deliberate, not overlooked.
- **[2. Tokens, secrets, credentials]** Not applicable, by design decision: the slice creates, stores,
  compares, rotates, and revokes nothing. No credential, key, or fingerprint is touched or displayed.
- **[3. File / storage]** Not applicable, by design decision: nothing is persisted. The flow is
  in-memory and connection-scoped, and the composable holds no `rememberSaveable` state, so the status
  cannot survive process death into a later session and no `allowBackup` / at-rest question arises.
- **[4. Inter-process / Android attack surface]** Not applicable, by design decision: no `Activity`,
  `Service`, `BroadcastReceiver`, `ContentProvider`, `PendingIntent`, `WebView`, deep link, or
  `intent-filter` is added or altered, and no new exported surface exists. The `MainActivity` change
  is two lines inside an existing composition, adding no intent handling.
- **[5. Cryptographic primitives]** Not applicable, by design decision: no RNG, no hash, no key, and
  no comparison of an attacker-controlled value against a secret. The only comparisons in the slice
  are `Boolean` and a sealed-object identity check in the `when`, neither security-relevant.
- **[6. Network & I/O]** No findings. The slice opens no connection, builds no client, sets no
  timeout or TLS config, lifts no frame cap, and adds no suspension point or I/O. It subscribes to an
  existing flow and cannot back-pressure the transport (the upstream is a `map` over a retained
  `StateFlow`; a slow collector conflates rather than blocks).
- **[7. Error messages, logs, telemetry]** No findings. Nothing is logged, no error message is
  constructed, and no telemetry is added. MUST-NOT-log for the developer, stated so it is not
  discovered by accident: neither the `conversation_id` nor the raw payload may be logged from the VM
  or the composable — a logged id is a cross-conversation correlation leak, per the sibling KDocs. The
  slice has no counter or text to leak in the first place, so its log surface is the smallest of the
  family. Screenshot leakage / accessibility-service eavesdropping / overlay attacks (the mobile
  threats #596 explicitly handed to this ticket as "the first three become relevant once #597
  renders"): the rendered surface is a fixed, non-secret English string plus a spinner, identical in
  information content to the already-shipped "Thinking…". It reveals that a compaction is running —
  which an observer already infers from the spinner — and nothing about conversation content, so no
  `FLAG_SECURE` or redaction requirement is created.
- **[8. Concurrency]** No findings. One flow, no new coroutine, no mutex, no shared mutable state, no
  check-then-mutate (the read-modify-write on the id `Set` lives in #596). Scope ownership is explicit
  and matches two shipped siblings: `viewModelScope` + `WhileSubscribed(5_000)`, cancelled on screen
  exit, so nothing outlives the `ViewModel`. The hot-vs-cold item is answered correctly by the
  upstream: `observeCompacting` is **cold** and per-collector over a shared `StateFlow`, so no data
  crosses screens and a `flatMapLatest` re-subscription through the facade sees current state rather
  than silence. No `catch` block is introduced, so the
  `CancellationException`-swallowed-by-`IllegalStateException` trap has no site here.
- **[9. Threat model alignment]** No findings, and this is the bounded question the label was applied
  for. The threat is a compromised or buggy *authenticated* daemon using a "the session is alive, keep
  waiting" affordance to keep the operator waiting on a head that is actually hung — the render-side
  consequence of the **stuck-active** case #596 deferred. That deferral is correct and stands (a
  client-side compaction timeout would invent policy the wire contract does not define, it fights the
  daemon as SSOT, a reconnect clears the state anyway, and no occurrence has been observed). What
  this slice owes is the confirmation that the *composition* cannot suppress a signal the operator
  would otherwise see, and it holds by construction on all three counts:
  1. **The stall CTA is untouched.** `StallPromotionBanner` (`ThreadScreen.kt:203`) is a separate
     affordance above the message list, outside this slot, driven by its own `isStalled` flow. A
     daemon that emits endless `compacting` frames cannot hide a stall — the banner and the compacting
     row render simultaneously and independently. This is the render-side mirror of the structural
     inertness #596 pinned at the data layer, and it is the single most important property of the
     design; § Design 3 requires it in prose and no test may be written that would pass with the
     banner suppressed.
  2. **The interrupt affordance is untouched.** `InterruptAffordance` (`:327`) sits below the slot and
     is driven by `isBusy`, so the operator's escape hatch from a wrongly-persistent "compacting"
     stays available. #596's review explicitly asked that this flag "must not block interaction on
     it" — the design blocks nothing: the composer, overflow menu, and interrupt all stay live.
  3. **What compacting *does* displace is strictly more informative.** It replaces the generic
     `Thinking…` spinner, which conveys less. The one arm ordered above it is api-retry, chosen
     precisely so a benign progress label can never mask the "something is going wrong" signal
     (§ Design 3). No arm of the `when` can reduce what the operator learns.
  No timeout, watchdog, or heartbeat is specified — confirm, do not build, per the ticket's scope and
  the evidence-based-fix rule. Remaining mobile-specific threats (malicious deep links, third-party
  keyboard logging) have no site: the slice adds no input field and no link.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30
