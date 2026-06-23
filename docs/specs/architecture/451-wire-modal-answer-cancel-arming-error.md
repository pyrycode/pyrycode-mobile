# #451 — Wire modal answer/cancel + arming/second-confirm + error to the live send path

**Ticket:** [#451](https://github.com/pyrycode/pyrycode-mobile/issues/451) · **Size:** S · **Labels:** `security-sensitive`, `size:s`
**Split from #444** (this is the **behavior** half — VM decision logic + outbound send wiring, no UI). The
sibling **render** slice **#452** (armed/second-confirm affordance + dismiss snackbar + route-host forward)
is `blockedBy` this one.
**Consumes (shipped):** #445 `ThreadViewModel.currentModal` / `ModalUiState.Open.defaultOptionId`; #438
concrete `RemoteConversationRepository.answerModal` / `cancelModal`; #437 decoded `modalEvents`.

## Design source

N/A — data/VM behavior, no UI surface (same posture as the #445 projection slice). The render of the
armed/second-confirm affordance and the snackbar lands in the follow-up render slice **#452**.

## Files to read first

Every addition mirrors an existing precedent in these exact files — copy the precedent, don't invent.
(Generated from `codegraph_context` + the reads done during this spec; off-topic hits pruned.)

| Path / lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:105-116` | VM ctor + the defaulted `liveSessionEvents`/`modalEvents = emptyFlow()` precedent (keeps fake graph + existing tests inert). Add the two **defaulted no-op send lambdas** the same way. |
| `…/ThreadViewModel.kt:130-131` | `navigationChannel = Channel(BUFFERED)` → `navigationEvents = receiveAsFlow()` — the **one-shot VM→UI event idiom** this VM already uses. The error signal mirrors it exactly. |
| `…/ThreadViewModel.kt:271-296` | `currentModal: StateFlow<ModalUiState>` (the #445 source you read at tap time) + its `Eagerly` rationale (so `.value` is always the true projection). |
| `…/ThreadViewModel.kt:317-337` | `sendMessage` / `onWorkspacePicked` — the `viewModelScope.launch { repository.… }` outbound-call precedent. The send methods follow this shape, **adding a try/catch**. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ModalUiState.kt:33-40` | `ModalUiState.Open(modalId, modalClass, title, prompt, options, defaultOptionId)` — the decision logic reads `defaultOptionId` and `modalId` off this. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:143-172` | `private val activeRemoteRepo: MutableStateFlow<RemoteConversationRepository?>` (:148) + the inbound `modalEvents` `flatMapLatest` seam (:170-172). Add the **outbound** passthrough as its mirror (a suspend call, not a flow). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1071-1110` | The concrete `answerModal` (:1071) / `cancelModal` (:1103) the passthrough delegates to. Note: each `throw`s on a server `error`/not-`Open` session (via `sendAndAwaitReply`); the passthrough adds only the null-repo guard. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:147-151` | `RelayErrorException(code, retryable, message)` — one of the two exceptions to catch. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:115-124` | `ThreadViewModel` Koin reg (currently 6 positional args). Resolve the coordinator once and bind the two send lambdas (method refs). |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1563-1599` | `makeVm` (:1563), `vmWithModalEvents` (:1581), `modalShown(...)` (:1588) helpers — add the two defaulted send-lambda params to `makeVm`; reuse `modalShown` to drive the decision tests. |
| `…/ThreadViewModelTest.kt:1180-1290` | The `navigationEvents` collect-into-list test pattern (`launch { vm.navigationEvents.collect { … } }`) — the template for asserting the one-shot error signal. |
| `…/ThreadViewModelTest.kt:247-340` | The #445 `currentModal` tests (`MutableSharedFlow<ModalEvent>` → emit → assert `.value`) — the template for setting up an `Open` modal before driving taps. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:280-384` | The `register_push_token` coordinator tests — the **exact precedent** for testing an outbound control message through the coordinator: drive a connection, invoke the method, assert `pump.sent.single { it.type == … }`, push an ack. Mirror for `modal_answer`/`modal_cancel` + the no-connection throw. |
| **Memory lessons** | `[[remote-repo-test-runcurrent-not-advanceuntilidle]]` (drive pump fan-out with `runCurrent()`, not `advanceUntilIdle()`) for the coordinator test; `[[gradle-single-test-class-task]]` (`testDebugUnitTest --tests`, not bare `test --tests`). |

## Context

pyrycode#597 Phase 3 (ADR 025) puts a permission/choice modal over the encrypted mobile wire. Three
halves already shipped:

- **#437** decodes `modal_shown`/`modal_dismissed` → `RemoteConversationRepository.modalEvents` (`replay = 0`).
- **#445** folds that stream into the hoisted **app-level** `ThreadViewModel.currentModal: StateFlow<ModalUiState>`
  (`Hidden` / `Open(…, defaultOptionId)` / `Dismissed`), and adds the **inbound** coordinator `modalEvents`
  passthrough.
- **#438** adds the **outbound** concrete send methods `answerModal(modalId, optionId)` / `cancelModal(modalId)`
  on `RemoteConversationRepository` (mints `answer_token` internally, awaits ack/error, throws on failure).
- **#446** renders the `Open` overlay and leaves the screen's `onModalOption` / `onModalCancel` hooks **inert**.

This slice is the **behavior glue**: it (1) exposes an **outbound** coordinator passthrough that reaches the
connection-scoped concrete repo's send methods — the mirror of #445's inbound `modalEvents` seam (verified:
these methods have zero callers and no existing seam); and (2) adds the `ThreadViewModel` decision logic that
turns option taps / cancel into `answerModal` / `cancelModal` calls, with the **fail-safe-deny single-tap /
second-confirm** UX belt and a non-crashing error signal.

**The fail-safe-deny belt (the one real behavior decision).** The producer (pyrycode#716) ships **no
per-option "destructive" marker**. Its safety design is the fail-safe-deny `default_option_id`: the
highlighted default is always the deny/safe option (`reject_once` for `permission`, `exit` for `trust`).
The phone never defines a destructive vocabulary and never inspects option-id semantics — it keys the
second-confirm purely off `Open.defaultOptionId` (carried verbatim through #445): **answering with any
option other than the default requires an explicit second confirm.** This over-captures `reject_always`
as needing a confirm, which is harmless (a deny variant). Authoritative deny-on-timeout / first-answer-wins
/ per-device-grant enforcement is server-side (pyrycode#702/#703/#717); this is the phone-side UX belt only.

**This slice leaves the screen hooks unforwarded** — exactly as #445 shipped `currentModal` before #446
rendered it. It touches **no** `ThreadScreen.kt` / `MainActivity.kt`. The route-host forward of
`onModalOption`/`onModalCancel` and the rendering of the armed affordance + error snackbar are **#452**.

## Design

Three production files, all additive. **No** interface change, **no** facade change, **no** Fake change,
**no** UI change.

### 1. Coordinator — outbound passthrough (mirror of the inbound `modalEvents` seam)

In `RelayRepositoryCoordinator`, add two **suspend** methods reaching the connection-scoped concrete repo
through the existing `private val activeRemoteRepo` (:148) — no new field. Contract sketch:

```kotlin
suspend fun answerModal(modalId: String, optionId: String) {
    val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection")
    repo.answerModal(modalId, optionId)
}
suspend fun cancelModal(modalId: String) {
    val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection")
    repo.cancelModal(modalId)
}
```

- **Asymmetry with the inbound seam is correct:** inbound `modalEvents` is a `Flow` (a stream); outbound
  answer/cancel are request/reply control **calls**, so they are suspend methods, not flows.
- **Both not-connected paths funnel to `IllegalStateException`.** When `activeRemoteRepo.value` is `null`
  (between connections) the null-guard throws. When it is non-null but the pump is still pre-`Open`
  (Handshaking), `repo.answerModal` → `sendAndAwaitReply` → `pump.send` returns false → `IllegalStateException`
  (the #438 precedent). So the passthrough needs **only** the null-guard; do **not** re-implement an `Open`
  gate here (`currentRepository` at :135-141 gates on `Open` for a different reason — facade publication; the
  concrete send already fails fast, so an extra gate would be redundant complexity).
- A server `error` propagates from the concrete repo as `RelayErrorException` unchanged. Add no log; the
  `modalId`/`optionId` may name a sensitive command/path (never-log contract).

### 2. ViewModel — defaulted send lambdas, arm state, decision logic, error signal

#### 2a. Two defaulted no-op send-lambda ctor params (mirror `liveSessionEvents`/`modalEvents`)

```kotlin
// #451: the outbound modal-send path → the coordinator's passthrough to the connection-scoped concrete
// repo. Defaulted no-ops so the fake-backed Koin graph + existing ThreadViewModel tests stay inert.
private val answerModal: suspend (modalId: String, optionId: String) -> Unit = { _, _ -> },
private val cancelModal: suspend (modalId: String) -> Unit = { _ -> },
```

Lambdas (not the coordinator instance) keep the VM dependency minimal and trivially testable — the VM never
holds the facade-bypassing concrete repo or the coordinator; `AppModule` binds the live path (§3). This is
the **outbound** analog of the #406/#445 defaulted-flow injection (a flow can model an inbound stream; an
outbound call needs a suspend lambda).

#### 2b. Transient, modalId-scoped arm state

```kotlin
private data class ArmedModalOption(val modalId: String, val optionId: String)   // private → not exported

// Which non-default option is "armed" awaiting a second confirm. Scoped to the modalId it belongs to so a
// stale arm can never pre-arm a fresh modal. NOT persisted (no rememberSaveable / SavedStateHandle).
private val armedModalOption = MutableStateFlow<ArmedModalOption?>(null)
```

Exposed as a **scoped** sibling `StateFlow` for the render slice #452 to draw the armed affordance (the
#445→#446 seam analog — the behavior slice owns the arm state, the render slice renders it):

```kotlin
/** The option of the *currently-open* modal that is armed (tapped once, awaiting a second confirm), or
 *  null. Scoped: null unless the arm's modalId matches the open modal, so a stale arm is invisible and a
 *  resolve nulls it automatically. Sibling StateFlow to currentModal; consumed by #452. */
val armedOptionId: StateFlow<String?> =
    combine(currentModal, armedModalOption) { modal, arm ->
        if (modal is ModalUiState.Open && arm?.modalId == modal.modalId) arm.optionId else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

`Eagerly` matches `currentModal` so `.value` is always correct and a resolve immediately nulls the affordance.

**No reactive arm-clear coroutine.** The modalId scoping makes a stale arm **structurally inert**: it can
neither be displayed (`armedOptionId` derives `null`) nor confirmed against a different modal (the equality
in 2c requires a modalId match). This is the deterministic guard — no extra clearing machinery (simplicity
first; the scoping *is* the "resets on resolve" property).

#### 2c. Decision methods (named to match the #446 screen hooks so #452 binds `vm::onModalOption` / `vm::onModalCancel`)

```kotlin
fun onModalOption(optionId: String)   // matches ThreadScreen onModalOption: (String) -> Unit
fun onModalCancel()                    // matches ThreadScreen onModalCancel: () -> Unit
```

Behaviour contract (developer writes the body; the AC#5 tests are the oracle). Read the open modal from the
VM's own `currentModal.value` — **never** from a caller-supplied id (the UI passes only the tapped
`optionId`; the `modalId` is the VM's server-supplied state):

- `onModalOption(optionId)` — `val open = currentModal.value as? ModalUiState.Open ?: return`, then:
  1. `optionId == open.defaultOptionId` → **single-tap answer** (`sendAnswer(open.modalId, optionId)`).
  2. else if `armedModalOption.value == ArmedModalOption(open.modalId, optionId)` → **second confirm** of the
     same armed option → `sendAnswer(open.modalId, optionId)`.
  3. else → **(re-)arm**: `armedModalOption.value = ArmedModalOption(open.modalId, optionId)` (covers first
     tap of a non-default option **and** re-tap of a *different* option = re-arm). No send.
- `onModalCancel()` — `val open = currentModal.value as? ModalUiState.Open ?: return`; clear the arm
  (`armedModalOption.value = null`); `sendCancel(open.modalId)`.

`sendAnswer` clears the arm (`armedModalOption.value = null`) **before** launching — the second-confirm
gesture is consumed on the attempt (success or failure); `currentModal` stays `Open` until the daemon
resolves it, so the user may answer again after a failure (the VM does **not** auto-retry — first-answer-wins
is server-side).

```kotlin
private fun sendAnswer(modalId: String, optionId: String) {
    armedModalOption.value = null
    viewModelScope.launch {
        try { answerModal(modalId, optionId) }
        catch (e: RelayErrorException) { modalSendErrorChannel.trySend(Unit) }
        catch (e: IllegalStateException) { modalSendErrorChannel.trySend(Unit) }
    }
}
// sendCancel(modalId) is identical minus the optionId.
```

**Catch only the two documented throws** (`RelayErrorException` from a server `error` incl. the
ungranted-device reject pyrycode#702; `IllegalStateException` from a not-connected session). Do **not** use
a broad `catch (e: Exception)` — it would swallow `CancellationException` and break structured cancellation.
Any other `Throwable` propagates normally. The catch surfaces the one-shot error event and **nothing else** —
no log, no `currentModal` mutation, no state change beyond the already-applied arm clear.

#### 2d. One-shot error signal (mirror `navigationChannel` / `navigationEvents`)

```kotlin
private val modalSendErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)
/** One-shot "a modal send (answer or cancel) failed" signal. Carries NO modal payload (so nothing
 *  sensitive can be logged through it). #452 shows a transient snackbar. */
val modalSendErrors: Flow<Unit> = modalSendErrorChannel.receiveAsFlow()
```

**Interpreting AC#4's "in UI state".** Surfaced as a VM-exposed observable signal — the established one-shot
event idiom this VM already uses for `navigationEvents` — **not** a `ThreadUiState` field. This is consistent
with how `currentModal` / `isThinking` / `isStalled` are VM-exposed signals rather than `ThreadUiState`
fields, and it is the natural fit for a transient snackbar: an event fires exactly once per failure with no
sticky flag to reset and no re-fire on recomposition. Payload is `Unit` — a send failed; the VM does not
distinguish failure types here (see Open Questions for the #440/#452 read-only extension point).

### 3. Koin wiring (`AppModule.kt:115-124`)

Resolve the coordinator once, bind the two send lambdas as suspend method references:

```kotlin
viewModel {
    val coordinator = get<RelayRepositoryCoordinator>()
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        coordinator.modalEvents,
        answerModal = coordinator::answerModal,
        cancelModal = coordinator::cancelModal,
    )
}
```

Hoisting `coordinator` (instead of resolving it three times) is an in-block tidy of the same registration,
not an adjacent-code refactor. If the suspend method-reference binding gives trouble, fall back to explicit
lambdas (`answerModal = { id, opt -> coordinator.answerModal(id, opt) }`). Capture the resolved `coordinator`
instance — do not call `get<…>()` *inside* the escaping lambda (the Koin scope receiver may be gone by then).

## State + concurrency model

- **Single source of state** per signal: `armedModalOption` (raw, private) → `armedOptionId` (derived,
  scoped, public). No parallel mirror. The error signal is a `Channel`, drained once per failure.
- `armedOptionId` and the error flow are **separate VM properties**, not `ThreadUiState` fields — exactly
  like `currentModal` / `isThinking` / `isStalled` / `navigationEvents`. App-level (no `conversationId`
  filter; the modal carries no `conversation_id`, inherited from #445).
- Sends run on `viewModelScope.launch` (the `sendMessage`/`onWorkspacePicked` precedent); cancelled with
  `viewModelScope` on VM clear. The repo-layer suspend call inherits its dispatcher from `sendAndAwaitReply`;
  no manual dispatcher switching.
- Coordinator passthrough: suspend, runs on the caller's coroutine (the VM's `viewModelScope`); reads the
  `activeRemoteRepo` StateFlow snapshot and delegates. No new scope, no new coroutine.

## Error handling

| Failure mode | Path | Surfaced as |
|---|---|---|
| No active connection (`activeRemoteRepo.value == null`) | coordinator null-guard | `IllegalStateException` → caught in VM → `modalSendErrors` emits once |
| Pump pre-`Open` / dropped (`pump.send` false) | concrete `sendAndAwaitReply` `check` | `IllegalStateException` → same |
| Server `error` (incl. ungranted-device reject #702) | concrete `sendAndAwaitReply` | `RelayErrorException` → caught in VM → `modalSendErrors` emits once |
| Caller cancellation while awaiting | structured cancellation | `CancellationException` **propagates** (not caught) — coroutine cancels cleanly |

AC#4 is satisfied by construction: every documented send failure is caught and emits the one-shot signal;
nothing is logged; `currentModal` and all other state are untouched (only the arm clear, which is the
intended transition of the confirm gesture, precedes the launch). The VM does not retry, interpret the
error code, or degrade to read-only — that is #440/#452.

## Security posture (`security-sensitive`)

See the full adversarial pass in **§ Security review** below. Summary of the load-bearing properties:

- **The VM answers the modal it knows is open, not a UI-claimed one.** `onModalOption`/`onModalCancel`
  derive `modalId` from the VM's own `currentModal.value`; the UI supplies only the tapped `optionId`. No
  caller-asserted modal routing.
- **Fail-safe-deny preserved.** The single-tap path is gated to `optionId == open.defaultOptionId` (the
  producer's deny/safe option); every other option requires an explicit second confirm of the *same* armed
  option. No auto-answer, no destructive-vocabulary interpretation.
- **No trust-bearing value minted here.** `answer_token` is minted by #438's concrete repo; this slice mints
  nothing. The daemon validates `modalId`/`optionId` (#703/#706); the VM does not.
- **Never-log.** The error event carries `Unit`; no `modalId`/`optionId`/modal text is logged in the VM or
  the coordinator passthrough.
- **Stale-arm safety.** The modalId-scoping makes a stale arm structurally unable to pre-arm or auto-confirm
  a fresh modal.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "…ThreadViewModelTest"` and `"…RelayRepositoryCoordinatorTest"`;
bare `test --tests` is rejected — `[[gradle-single-test-class-task]]`). No instrumented test (no UI).

### `ThreadViewModelTest.kt` (primary)

- Add **two defaulted send-lambda params** to `makeVm` (:1563), forwarding to the new ctor args; existing
  call sites stay inert (no-op defaults). Drive an `Open` modal via `vmWithModalEvents` + `modalShown(...)`
  (the #445 helpers), then call `onModalOption`/`onModalCancel`. Capture the send path with recording
  lambdas (`answers: MutableList<Pair<String,String>>`, `cancels: MutableList<String>`, a `failWith:
  Throwable?` switch). Assert `vm.armedOptionId.value`; collect `vm.modalSendErrors` into a list (the
  `navigationEvents` pattern).

Scenarios (AC#5 — bullets, not pre-written bodies):

- **default → immediate single-tap answer** — `Open(defaultOptionId="reject_once")`; `onModalOption("reject_once")`;
  assert exactly one answer `("m1","reject_once")`, `armedOptionId.value == null`, no error.
- **non-default → arm, no send** — `onModalOption("allow_once")`; assert **no** answer call;
  `armedOptionId.value == "allow_once"`.
- **second tap of armed option → send + clear** — arm `"allow_once"`, then `onModalOption("allow_once")`;
  assert exactly one answer `("m1","allow_once")`, `armedOptionId.value == null`.
- **re-tap a different option → re-arm, no send** — arm `"allow_once"`, then `onModalOption("allow_always")`;
  assert **no** answer call; `armedOptionId.value == "allow_always"`.
- **cancel → cancel call + clear** — arm `"allow_once"`, then `onModalCancel()`; assert exactly one cancel
  `"m1"`, `armedOptionId.value == null`.
- **send failure → error signal, nothing else mutated** — for `failWith ∈ { RelayErrorException(…),
  IllegalStateException(…) }`: `onModalOption("reject_once")`; assert `modalSendErrors` received exactly one
  `Unit`, `currentModal.value` is still `Open(m1)`. (Asserting "nothing logged" is by construction — the
  event carries `Unit`; code-review verifies no `Log.*`.)
- **stale arm cannot pre-arm a fresh modal (scoping)** — arm `"allow_once"` on `m1`; emit `modalShown(m2)`;
  assert `armedOptionId.value == null` (scoped to `m1`); then `onModalOption("allow_once")` on `m2` → arms
  (no send), proving the `m1` arm did not auto-confirm `m2`.
- **default-inert** — a VM built with the no-op send defaults and no modal source: `onModalOption("x")` /
  `onModalCancel()` are no-ops (the `as? Open ?: return` guard); no crash, no answer/cancel call. (Confirms
  existing tests stay inert.)

### `RelayRepositoryCoordinatorTest.kt` (passthrough)

Mirror the `register_push_token` coordinator tests (:280-384): drive a connection (`Open`), launch the
suspend call on `backgroundScope`, `runCurrent()` (`[[remote-repo-test-runcurrent-not-advanceuntilidle]]`),
assert the outbound envelope, push an ack to complete.

- **`answerModal` with an active connection delegates** — assert `pump.sent.single { it.type == "modal_answer" }`
  carries the `modalId`/`optionId`; push `ackEnvelope` → the suspend completes without throwing.
- **`cancelModal` with an active connection delegates** — assert `pump.sent.single { it.type == "modal_cancel" }`.
- **no active connection → `IllegalStateException`** — call `answerModal`/`cancelModal` before any connection
  (or after teardown, `currentRepository.value == null`); assert `IllegalStateException`. This is the
  load-bearing new branch (the not-connected path the VM catches). (#438 already covers the concrete method's
  full wire-shape + server-error + send-false matrix; do not duplicate it here.)

## Open questions

- **Error-event payload (#440/#452 read-only).** This slice surfaces `Flow<Unit>` — sufficient for #452's
  generic failure snackbar. If #440/#452's "reactive read-only on ungranted-device reject" needs to
  distinguish the #702 reject from other failures, extend the event payload to carry the non-sensitive
  `RelayErrorException.code`/`retryable` **then** (additive — the seam is here; this honors evidence-based
  fix selection: no distinguishing behavior is built until the consuming behavior exists).
- **Stale `Open` on disconnect** (deferred from #445/#446). A connection drop pushes no "clear" event, so a
  stale `Open` can persist across a reconnect. Answering it server-side is rejected (stale `modalId`) and
  surfaces via the AC#4 error signal — so a proactive stale-clear is a UX nicety, **not** a correctness
  requirement. **Not built here** (no observed failure; the daemon validation is the deterministic backstop).
- **Arm on a failed answer.** This spec clears the arm on the send *attempt* (the gesture is consumed). The
  alternative — keep it armed for a one-tap retry — is a #452 UX call; AC#5 only checks the error signal.

## Acceptance criteria → design mapping

1. default → single-tap answer → §2c branch 1 (`optionId == defaultOptionId` → `sendAnswer`).
2. non-default → arm; second confirm of same option → send; different option → re-arm; cancel clears →
   §2b (`armedModalOption`, modalId-scoped) + §2c branches 2/3 + `onModalCancel`.
3. cancel sends `modal_cancel` + clears arm → §2c `onModalCancel` → coordinator `cancelModal` (§1).
4. failed send (server `error` incl. #702 / not-connected) caught → non-crashing error signal; payload never
   logged; state not otherwise mutated → §2c try/catch + §2d `modalSendErrors` + § Error handling.
5. VM unit pass drives all transitions + error-signal-set-nothing-logged → § Testing `ThreadViewModelTest`.

## Scope (size self-check)

**Production source files modified** (excluding `*Test.kt`, `*.md`, this spec): `RelayRepositoryCoordinator.kt`,
`ThreadViewModel.kt`, `AppModule.kt` = **3** (= the S limit; below the ≥5 gate). **New files: 0.** **New
exported types: 0** (`ArmedModalOption` is `private`; `armedOptionId`/`modalSendErrors` are properties, not
new types). **New public methods: 4** (2 coordinator + 2 VM) + 2 public VM properties. **Consumer cascade: 0**
— the two new VM ctor params are defaulted (`makeVm` + `AppModule` only); the new VM methods have zero current
callers (#452 wires them later). **Decision branches: 3** (default / second-confirm / re-arm) + 2 error
catches — well under 10. **Total written LOC** (≈25 coordinator+test, ≈70 VM, ≈4 AppModule, ≈170 VM tests,
this spec) ≈ **~340 production+test**. Below the ~400 S line and the ~600 split line. **No red line tripped —
solidly S.**

## Security review

**Reviewer:** architect (self-review; `agents/architect/security-review.md` is not synced into this worktree —
performing the pass inline using the standard adversarial categories, per the #438/#446/#445 precedent).
**Date:** 2026-06-23
**Verdict:** PASS

Run adversarially against the spec above, assuming it has holes. This slice opens the **decision-to-send**
path for a permission answer — a high-consequence action (it injects "allow/deny this command" into the
supervised `claude`). The adversarial questions: can a stale/spoofed/raced modal cause an unintended *allow*;
is the fail-safe-deny belt bypassable; does the VM mint or trust anything it shouldn't; is anything sensitive
logged; does the catch mask failures.

**Findings:**

- **[Trust boundaries — who chooses the modal being answered].** No findings. `onModalOption`/`onModalCancel`
  read the `modalId` from the VM's own `currentModal.value` (server-supplied state held in the VM), **never**
  from a caller argument — the UI passes only the tapped `optionId`. A compromised/buggy UI therefore cannot
  redirect an answer to a *different* modal; it can at most pass an `optionId` for the modal that is actually
  open, which the daemon then validates against its own option list (#706). The `optionId` is echoed
  verbatim, never interpreted for semantics (correct — a client-side option-semantics check would be theatre
  that could diverge from the daemon). Trust boundary intact; authority stays server-side.
- **[Fail-safe-deny integrity — can the second-confirm be bypassed].** No findings. The single-tap path is
  gated by `optionId == open.defaultOptionId` (a plain string `==` against the producer's verbatim
  fail-safe-deny default — a UX gate, not a secret comparison, so `==` is correct). Every non-default option
  requires `armedModalOption.value == ArmedModalOption(open.modalId, optionId)` — i.e. the *same* option,
  *same* modal, tapped twice. There is no path from a single non-default tap to a send. A different-option
  tap re-arms (never sends the previously-armed option). The belt cannot be skipped by reordering taps.
- **[Stale / raced arm — can a leftover arm auto-allow a new modal].** No findings — this is the explicit
  design property. The arm carries its own `modalId`; the second-confirm equality requires it to match the
  *currently-open* modal's id, and `armedOptionId` derives `null` for any non-matching modal. So a modal
  resolving and a new one opening (last-shown-wins, #445) cannot turn a stale arm into an auto-confirm: the
  new modal starts unarmed, and the leftover `ArmedModalOption(m1,…)` can never equal `ArmedModalOption(m2,…)`.
  Tested explicitly (the "stale arm cannot pre-arm a fresh modal" scenario). The deterministic guard is the
  modalId scoping, not a stochastic clear.
- **[Mint / trust-bearing values].** No findings. This slice mints nothing. `answer_token` (the daemon's
  idempotency key) is minted inside #438's concrete `answerModal`; the VM and coordinator passthrough only
  forward `modalId`/`optionId`. No credential, key, nonce, or token is generated, stored, or compared here.
- **[Output redaction / logs / telemetry].** No findings — and the obligation is named. The `modalId`/
  `optionId` and the modal they answer may reference a sensitive command/path. The error signal carries
  **`Unit`** — structurally incapable of leaking the payload. No `Log.*`/`Timber` statement is added in the
  VM decision methods or the coordinator passthrough; `RelayErrorException` is caught and its message is
  **not** surfaced (only a `Unit` event is emitted). Mirrors #438/#445's never-log discipline.
- **[Fail-closed / availability — does the catch mask anything].** No findings. The catch is scoped to the
  two documented throws (`RelayErrorException`, `IllegalStateException`); `CancellationException` is **not**
  caught, so structured cancellation is preserved (a broad `catch (e: Exception)` would be a bug — flagged in
  the spec). A failed send mutates nothing beyond the already-applied arm clear and leaves `currentModal`
  `Open`, so the user can re-answer; the VM does **not** auto-retry (first-answer-wins is server-side, so a
  blind retry could race the daemon's dedup — correctly avoided). A not-connected send fails fast (no hang).
- **[Replay / idempotency].** No findings. Replay-safety lives in #438's `answer_token` + the daemon's
  `(modal_id, answer_token)` dedup; this slice neither weakens nor relies on it beyond forwarding. The
  no-auto-retry rule means the VM does not itself generate replayed answers.
- **[Android attack surface — tapjacking].** Named, owned by #452. An overlay-tap (tapjacking) attack over
  the fail-safe-deny default is partially mitigated *by design here*: the prominent single-tap option is the
  **deny/safe** default, and any *allow* requires a deliberate second confirm — so a single obscured tap can
  at worst trigger a deny. The touch-obscured filtering on the live buttons
  (`setFilterTouchesWhenObscured` / Compose touch-obscured handling) is a **render-time** mitigation owned by
  **#452** (the slice that makes the taps live and draws the buttons), inherited from #446's security note.
- **[Secrets / crypto / file ops / subprocess / network / DoS].** N/A — no secret/key handled, no crypto
  (AEAD sealing is the transport's), no I/O or path handling, no execution, no new socket (rides the existing
  authenticated v2 session via #438), no attacker-controlled unbounded allocation (two fixed-shape envelopes
  per user tap, user-rate-limited). `data/` stays portable (the coordinator passthrough adds no `android.*`).
- **[Threat model alignment].** Aligned with `protocol-mobile.md § Security model` and ADR 025. The new
  decision logic correctly pushes all authority to the daemon (unguessable `modal_id` validity #706 +
  per-device gate #702 + first-answer-wins #703), adds a client-side fail-safe-deny UX belt that can only
  make the action *harder* (never easier) than the wire allows, and introduces no new trust boundary, no
  minted secret, and no log of sensitive data.

**Producer dependencies (already owned upstream, not this slice's work):** unguessable `modal_id` minting +
`(modal_id, answer_token)` dedup + stale-id rejection (#703/#706); per-device answer gate (#702); AEAD
session confidentiality (#571). All merged.
