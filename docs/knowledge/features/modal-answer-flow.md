# Modal answer flow — the fail-safe-deny answer/cancel behavior on the thread ViewModel

The **interaction/behavior half of the permission/choice-modal surface**: how an option tap or a cancel on
the open modal becomes an outbound `modal_answer` / `modal_cancel` over the live encrypted wire, with the
**fail-safe-deny single-tap / second-confirm** UX belt and a non-crashing error signal. Landed in
[#451](../codebase/451.md) (split from #444, the interaction half of #439), part of the Phase 3
permission-modal feature (epic pyrycode#597, ADR 025). This slice adds **no UI** — it drives the
`onModalOption` / `onModalCancel` screen hooks from the ViewModel and exposes the arm + error signals; the
**sibling render slice [#452](../codebase/452.md)** (`blockedBy` this) then **shipped** the render half that
draws the armed affordance off `armedOptionId`, surfaces `modalSendErrors` on the snackbar, and wires these
hooks into the route host so the taps go live.

It is the fourth and final half of the modal family:

| Half | Slice | Doc |
|---|---|---|
| decode (`modal_shown`/`modal_dismissed` → `ModalEvent`) | [#437](../codebase/437.md) | [Modal events](modal-events.md) |
| project (`ModalEvent` → hoisted `currentModal`) | [#445](../codebase/445.md) | [Current-modal state](current-modal-state.md) |
| render (`currentModal` → overlay + dismiss snackbar) | [#446](../codebase/446.md) | [Permission-modal overlay](permission-modal-overlay.md) |
| **answer (taps/cancel → outbound send + arm + error)** | **#451** | **this doc** |

The concrete outbound send methods themselves ship in [#438](../codebase/438.md) (see
[Remote conversation repository § `answerModal` / `cancelModal`](remote-conversation-repository.md)); this
slice is the **glue** from the screen hooks to those methods.

## The data path

```
ThreadScreen onModalOption(optionId) / onModalCancel()   ◀── route host wires them to the VM (#452)
        │  (UI passes only the tapped optionId — never a modalId)
        ▼
ThreadViewModel.onModalOption / onModalCancel            ◀── reads modalId from its OWN currentModal.value
        │  fail-safe-deny decision: default → answer ; non-default → arm → 2nd confirm → answer ; cancel
        ▼
sendAnswer / sendCancel  ──▶  answerModal / cancelModal  (defaulted suspend lambdas)
        │                          = coordinator::answerModal / ::cancelModal  (AppModule)
        ▼
RelayRepositoryCoordinator.answerModal / cancelModal     ◀── #451 outbound passthrough (null-guard only)
        │  reaches the connection-scoped concrete repo via activeConnection.value?.repo
        ▼
RemoteConversationRepository.answerModal / cancelModal   ◀── #438 (mints answer_token, awaits ack/error, throws)
```

Two seams, both the **outbound mirror** of the inbound modal path: the coordinator passthrough mirrors #445's
`modalEvents` seam (a suspend *call*, not a `Flow`, because answer/cancel are request/reply control
messages), and the VM's defaulted suspend-lambda injection mirrors #445's defaulted `modalEvents` flow param.

## The fail-safe-deny belt — the one real behavior decision

The producer (pyrycode#716) ships **no per-option "destructive" marker**. Its safety design is the
fail-safe-deny `default_option_id`: the highlighted default is always the deny/safe option (`reject_once` for
`permission`, `exit` for `trust`), so a careless confirm denies rather than grants. The phone **never defines
a destructive vocabulary and never inspects option-id semantics** — it keys the second-confirm purely off
`Open.defaultOptionId` (carried verbatim through #445):

```kotlin
fun onModalOption(optionId: String) {
    val open = currentModal.value as? ModalUiState.Open ?: return   // the modalId is the VM's own state
    when {
        optionId == open.defaultOptionId -> sendAnswer(open.modalId, optionId)          // single tap
        armedModalOption.value == ArmedModalOption(open.modalId, optionId) ->
            sendAnswer(open.modalId, optionId)                                          // 2nd confirm
        else -> armedModalOption.value = ArmedModalOption(open.modalId, optionId)       // (re-)arm, no send
    }
}

fun onModalCancel() {
    val open = currentModal.value as? ModalUiState.Open ?: return
    armedModalOption.value = null
    sendCancel(open.modalId)
}
```

| tap | result |
|---|---|
| the **default** option (`optionId == defaultOptionId`) | **single-tap** answer — sends immediately |
| a **non-default** option, first tap | **arms** it (`armedModalOption = (modalId, optionId)`); **no send** |
| the **same** armed option again | **second confirm** — sends |
| a **different** non-default option | **re-arms** to the new option; **no send** |
| cancel | clears the arm + sends `modal_cancel` |

The belt over-captures `reject_always` as needing a confirm, which is harmless (a deny variant). The
authoritative deny-on-timeout / first-answer-wins / per-device-grant enforcement is server-side
(pyrycode#702/#703/#717); this is the phone-side UX belt **only** — it can make the action *harder* (never
easier) than the wire allows.

## The arm state — transient, modalId-scoped, structurally stale-safe

The arm lives in a single private `MutableStateFlow<ArmedModalOption?>` (the private `data class
ArmedModalOption(modalId, optionId)`), exposed to #452 as a **scoped** derived sibling:

```kotlin
val armedOptionId: StateFlow<String?> =
    combine(currentModal, armedModalOption) { modal, arm ->
        if (modal is ModalUiState.Open && arm?.modalId == modal.modalId) arm.optionId else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

- **Transient** — never persisted (no `rememberSaveable` / `SavedStateHandle`); it resets on resolve /
  cancel / re-tap.
- **modalId-scoped, so a stale arm is structurally inert** — `armedOptionId` derives `null` for any
  non-matching modal (a stale arm can't be *displayed*), and the second-confirm equality in `onModalOption`
  requires a modalId match (a stale arm can't *auto-confirm* a fresh modal). **No reactive arm-clear
  coroutine** — the scoping *is* the "resets on resolve" property. This is the deterministic guard (a plain
  equality), not a stochastic clear.
- **`Eagerly` matches `currentModal`** so `.value` is always the true projection and a resolve immediately
  nulls the affordance.
- **The arm clears on the send *attempt*** (`sendAnswer` nulls it *before* launching), not on success — the
  second-confirm gesture is consumed whether the send succeeds or fails. `currentModal` stays `Open` until
  the daemon resolves it, so the user may answer again after a failure (no auto-retry — first-answer-wins is
  server-side).

## The error signal — one-shot, payload-free

A failed send (server `error`, including the ungranted-device reject pyrycode#702; or a not-connected
session) is caught and surfaced as the established one-shot VM→UI event idiom — the `navigationEvents` shape,
**not** a `ThreadUiState` field (consistent with `currentModal` / `isThinking` / `isStalled` being VM-exposed
signals):

```kotlin
private val modalSendErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)
val modalSendErrors: Flow<Unit> = modalSendErrorChannel.receiveAsFlow()   // #452 shows a transient snackbar
```

The payload is **`Unit`** — a send failed; the VM does not distinguish failure types here, and nothing
sensitive can leak through it (the `modalId`/`optionId`/modal text never reach the signal). If #440/#452's
reactive read-only mode later needs to distinguish the #702 reject, the event payload can be extended to
carry the non-sensitive `RelayErrorException.code` **then** (additive — the seam is here; evidence-based fix
selection means no distinguishing behavior is built until the consuming behavior exists).

### Catch order is load-bearing

```kotlin
private fun sendAnswer(modalId: String, optionId: String) {
    armedModalOption.value = null
    viewModelScope.launch {
        try {
            answerModal(modalId, optionId)
        } catch (e: CancellationException) {
            throw e // MUST be first: j.u.c.CancellationException extends IllegalStateException on the JVM
        } catch (e: RelayErrorException) {
            modalSendErrorChannel.trySend(Unit)
        } catch (e: IllegalStateException) {
            modalSendErrorChannel.trySend(Unit)
        }
    }
}
```

The `catch (CancellationException) { throw e }` **must precede** the typed catches. On the JVM/Android
`kotlinx.coroutines.CancellationException` is a `typealias` for `java.util.concurrent.CancellationException`,
which **`extends IllegalStateException`** — so without the leading rethrow, the `IllegalStateException` catch
would swallow coroutine cancellation (VM teardown mid-send → `deferred.await()` throws `CancellationException`),
break structured cancellation, and fire a spurious error signal. This was the [#451](../codebase/451.md)
rework defect; see [[catch-illegalstate-swallows-cancellation]]. A broad `catch (Exception)` /
`catch (Throwable)` is forbidden for the same reason. `sendCancel` is identical minus the `optionId`.

## The coordinator passthrough

[`RelayRepositoryCoordinator`](relay-repository-coordinator.md#outbound-modal-send-passthrough-451) gains two
**suspend** methods reaching the connection-scoped concrete repo through the coordinator's single
`activeConnection` source (`activeConnection.value?.repo`; [#493](../codebase/493.md) consolidated the former
`activeRemoteRepo` mirror into it) — the outbound mirror of the inbound `modalEvents` seam:

```kotlin
suspend fun answerModal(modalId: String, optionId: String) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.answerModal(modalId, optionId)
}
// cancelModal is the same, minus optionId.
```

It needs **only the null-guard** — both not-connected paths funnel to `IllegalStateException`: when
`activeConnection.value == null` (between connections) the guard throws; when a connection exists but the
pump is pre-`Open`, the concrete `answerModal` → `sendAndAwaitReply` → `pump.send` returns false →
`IllegalStateException` already (the #438 precedent). A redundant `Open` gate would be needless complexity. A
server `error` propagates as `RelayErrorException` unchanged. **No log** — the `modalId`/`optionId` may name
a sensitive command/path.

## Wiring

`AppModule` resolves the coordinator once and binds the two send lambdas as suspend method references at the
`ThreadViewModel` factory — **no new Koin binding** (mirrors the `liveSessionEvents` / `modalEvents` args):

```kotlin
viewModel {
    val coordinator = get<RelayRepositoryCoordinator>()
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        coordinator.currentModal, // #492: the hoisted projection (was coordinator.modalEvents)
        answerModal = coordinator::answerModal,
        cancelModal = coordinator::cancelModal,
    )
}
```

The two new VM ctor params are **defaulted no-ops** (`{ _, _ -> }` / `{ _ -> }`), so in the default debug
build (`USE_RELAY_REPOSITORY` OFF, fake repository) and every existing test the send path is inert — taps on
a VM with no modal source no-op via the `as? Open ?: return` guard.

## Edge cases / limitations

- **No modal open** — `onModalOption` / `onModalCancel` are no-ops (the `currentModal.value as? Open ?:
  return` guard). The fake graph + existing tests stay inert.
- **Send failure** — caught, emits one `modalSendErrors`; `currentModal` stays `Open` so the user can
  re-answer. No auto-retry, no error-code interpretation, no read-only degrade (that is #440/#452).
- **VM teardown mid-send** — cancellation propagates cleanly (the rethrow); no spurious error signal.
- **Stale `Open` across a reconnect** (deferred from #445/#446) — a connection drop pushes no "clear" event,
  so a stale `Open` can persist. Answering it is rejected server-side (stale `modalId`) and surfaces via the
  error signal, so a proactive stale-clear is a UX nicety, **not** a correctness requirement — **not built
  here** (no observed failure; the daemon validation is the deterministic backstop).
- **App-level, not per-conversation** — inherited from #445; the modal carries no `conversation_id`, so
  there is one outstanding answer flow across the app.

## Testing

Unit only (`./gradlew testDebugUnitTest --tests "…ThreadViewModelTest"` /
`"…RelayRepositoryCoordinatorTest"`; bare `test --tests` is rejected — [[gradle-single-test-class-task]]). No
instrumented test (no UI). `ThreadViewModelTest` drives an `Open` modal by setting the injected
`StateFlow<ModalUiState>`'s `.value` directly (via the `vmWithModal` / `openModal(...)` helpers — renamed in
[#492](../codebase/492.md) from the pre-hoist `vmWithModalEvents` / `modalShown` that emitted a raw
`ModalEvent.Shown`), captures the send path with recording lambdas (`vmWithModalSendPath`), and asserts
`armedOptionId.value` + collects `modalSendErrors` (the `navigationEvents` pattern): default→answer,
non-default→arm, second-tap→send+clear, re-tap→re-arm, cancel→cancel+clear, failure→error-signal, stale-arm
scoping, inert-with-no-modal, and the `modalSend_scopeCancellationMidSend_doesNotEmitErrorSignal` regression
(hosts the VM in a real `ViewModelStore`, suspends a send on a never-completing deferred, `store.clear()`s
the scope, asserts no error fires). `RelayRepositoryCoordinatorTest` mirrors the `register_push_token`
quartet for the passthrough (delegate-over-active-connection + no-connection-throws), driven with
`runCurrent()` ([[remote-repo-test-runcurrent-not-advanceuntilidle]]).

## Related

- [#451 implementation notes](../codebase/451.md) — files, line refs, the rework lesson.
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md)) — the hoisted `currentModal` /
  `Open.defaultOptionId` this reads at tap time; the projection half.
- [Permission-modal overlay](permission-modal-overlay.md) ([#446](../codebase/446.md) base + [#452](../codebase/452.md)
  live) — the render of the open overlay + dismiss snackbar; the render slice **#452** extended it with the
  armed affordance + Cancel button + send-error snackbar + tapjacking net and wired these VM hooks
  (`vm::onModalOption` / `vm::onModalCancel`) + `armedOptionId` / `modalSendErrors` into the route host.
- [Remote conversation repository § `answerModal` / `cancelModal`](remote-conversation-repository.md)
  ([#438](../codebase/438.md)) — the concrete outbound send methods the passthrough delegates to.
- [Relay repository coordinator § Outbound modal-send passthrough](relay-repository-coordinator.md#outbound-modal-send-passthrough-451)
  — hosts the passthrough; the outbound mirror of its [§ Modal event seam](relay-repository-coordinator.md#modal-event-seam-445-and-the-hoisted-currentmodal-fold-492).
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the upstream decode seam.
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `armedOptionId` / `modalSendErrors` join
  `currentModal` / `isThinking` / `isStalled` / `navigationEvents` as VM-exposed signals.
- Sibling slices: [**#452**](../codebase/452.md) the render of the armed/second-confirm affordance +
  snackbar + route-host forward (shipped) · **#440** read-only device mode (`blockedBy` #452).
- Producer SSOT: pyrycode#716 (fail-safe-deny `default_option_id`, no per-option destructive marker), #702
  (per-device answer gate) / #703 (first-answer-wins) / #706 (stale-id reject); ADR 025 § Phase 3 modals,
  EPIC pyrycode#597.
