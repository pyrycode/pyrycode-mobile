# #816 — Scope permission prompts to the conversation that raised them

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ModalShownPayloadDto`, `ModalShownPayloadDto.toEvent`, `ModalDismissedPayloadDto.toEvent`: the decode seam. The KDoc block above `ModalOptionDto` claims modal payloads carry no `conversation_id` and is stale.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt` → `ModalEvent.Shown`: the portable event. Its KDoc says there is no `conversation_id`, which is stale.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState.Open`, `ModalUiState.Dismissed`, `reduce`: the single-modal fold, which the coordinator runs once per host. Its KDoc says the state is "not scoped per conversation", which is stale.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the `currentModal` constructor property, `armedOptionId`, `onModalOption`, `onModalCancel`, and the `questionBatch(conversationId).collect` block in `init`, which is the scoping pattern to mirror.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread`: binds the host's `coordinator.currentModal` / `answerModal` / `cancelModal` (#636). Only the argument name changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the modal-overlay `when (modalState)` block. Its comment "so this is not scoped per thread" is stale. `LaunchedEffect(modalState.modalId)` shows the Dismissed snackbar once.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentModal`: `scan(reduce).stateIn(Eagerly)` per host. Unchanged.
- `app/src/test/.../data/model/ModalUiStateTest.kt`, `app/src/test/.../ui/conversations/thread/ThreadViewModelTest.kt` (`makeVm`, `vmWithModal`, `vmWithModalSendPath`, `openModal`, `ACTIVE_CONV`), `app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt` (`modalShown_decodesAllFieldsPreservingOptionOrder`, `modalShownEnvelope`): where the regression coverage goes.
- `docs/knowledge/features/current-modal-state.md`: the fold is hoisted to the process-scoped coordinator (#492). The VM re-exposes it, and this ticket filters it there without moving it.
- `pyrycode/docs/protocol-mobile.md` § Modal (v2), the `modal_shown` `conversation_id` row and the note "`modal_shown`'s `conversation_id` is outbound-only (#1065)": `conversation_id` is a daemon-asserted scoping stamp. Answers stay keyed on `modal_id` alone, and a reconnect re-send carries the same `conversation_id`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

This is the generic mobile modal container that #815 moved the prompt into. It is a full-height dark `Surface` with a title row and close icon, a content column, and Cancel (outlined) / OK (filled) actions at the foot. The node's sample content is "Edit host". This ticket changes no visuals. It decides *whether* the prompt renders in a given thread, so the visual-fidelity check has nothing new to compare.

## Context

Since daemon #1065, `modal_shown` has carried `conversation_id`. Mobile drops it, so with two chats open on one host, a prompt raised by chat A renders in chat B and can be answered from B. #636 already routes answers to the owning host. This ticket adds the conversation half, and only for display and input. The answer frame gains no field, because the daemon never trusts a client-sent conversation.

## Design

**Wire → event.**
- `ModalShownPayloadDto` gains `@SerialName("conversation_id") val conversationId: String = ""`. It is the only defaulted field. Every other field stays required, and the fail-closed decode posture is unchanged. With the default, a frame without the key still decodes (AC 2). An explicit `null` still fails the decode, as for every other field; the contract says the field is a string.
- `ModalShownPayloadDto.toEvent` copies it.
- `ModalEvent.Shown` gains `val conversationId: String = ""` as the **last** parameter, so the positional construction sites don't cascade. `ModalEvent.Dismissed` is unchanged, since `modal_dismissed` carries no conversation on the wire.

**Fold.**
- `ModalUiState.Open` and `ModalUiState.Dismissed` each gain a trailing `val conversationId: String = ""`.
- `reduce`: `Shown → Open` copies `event.conversationId`. A matching `Dismissed` copies the **open modal's** `conversationId` into `ModalUiState.Dismissed`, because the fold is the only place that knows it (AC 3). Last-shown-wins is unchanged, so a reconnect re-send with the same `modal_id` replaces the prompt in place (AC 4).
- A new pure function sits beside `reduce`: `fun ModalUiState.scopedTo(conversationId: String): ModalUiState`. It returns the receiver when it is `Open` or `Dismissed` and its `conversationId` is non-blank and equal to the argument. Otherwise it returns `Hidden`. Blank on either side means no thread, which is the "unscoped renders nowhere" rule of AC 2. The function is public because `ui/` consumes it.

**Thread VM.**
- The constructor property `val currentModal: StateFlow<ModalUiState>` becomes `private val hostModal: StateFlow<ModalUiState>` with the same default. `AppModule` passes it by the new name. The tests pass it positionally or by `makeVm`'s own parameter name, so they are unaffected.
- The public `val currentModal: StateFlow<ModalUiState>` is now `hostModal.map { it.scopedTo(conversationId) }.stateIn(viewModelScope, Eagerly, hostModal.value.scopedTo(conversationId))`. It is declared after `conversationId`. `ThreadScreen` keeps collecting `vm.currentModal`, and its signature doesn't change.
- `onModalOption` and `onModalCancel` read `hostModal.value.scopedTo(conversationId)` synchronously, through a private `scopedModal()` helper, rather than `currentModal.value`. The input guard then never lags the host flow by a dispatch: a tap in thread B cannot answer A's prompt even in the instant before the `stateIn` collector runs (AC 1).
- `armedOptionId` combines the scoped `currentModal` with the arm, so an arm never surfaces for a foreign prompt.

**Stale comments.** Correct the `conversation_id` claims in the KDoc of the modal DTO block, `ModalEvent`, `ModalUiState`, and the `ThreadScreen` overlay comment.

## State + concurrency model

There is no new job. The one new `stateIn` runs on `viewModelScope`, `Eagerly`, and is cancelled with the VM. Its initial value is computed from `hostModal.value`, so `.value` is right at construction. The host fold, the coordinator's `Eagerly` `stateIn`, is untouched and still survives reconnects.

## Error handling

The only new failure mode is a missing or empty `conversation_id`. It decodes to `""`, and `scopedTo` hides it from every thread. It is never treated as belonging to all threads. There is no logging: the modal fields stay inert data, and the no-log contract of `reduce` extends to `scopedTo`.

## Testing strategy

Unit tests only. The screen composable is unchanged.
- `ModalUiStateTest`:
  - `Shown → Open` carries `conversationId`.
  - A matching dismiss carries the open modal's `conversationId` into `Dismissed`.
  - A repeat `Shown` with the same `modal_id` replaces `Open` in place (reconnect re-send).
  - `scopedTo`: Open/Dismissed match → itself; other conversation → Hidden; blank modal conversation → Hidden; blank thread conversation → Hidden; Hidden → Hidden.
  - Update the existing `Dismissed` assertion in `matchingDismiss_clearsOpenWithVerbatimOutcomeAndSource` for the carried id.
- `ThreadViewModelTest`:
  - `openModal` gains `conversationId: String = ACTIVE_CONV`, so the existing arm/answer/cancel cases keep exercising an owned prompt.
  - New: a prompt for another conversation → `currentModal` is Hidden, and `onModalOption` (default and non-default) and `onModalCancel` send nothing.
  - New: a prompt with an empty `conversationId` → Hidden and inert.
  - New: `Dismissed` for this conversation surfaces, and `Dismissed` for another stays Hidden.
- `RemoteConversationRepositoryTest`:
  - `modalShown_decodesAllFieldsPreservingOptionOrder` adds `"conversation_id":"c1"` and asserts `conversationId = "c1"` in the expected event, so a missing mapper copy fails the test instead of the default hiding it.
  - New: a `modal_shown` without `conversation_id` still decodes, with `conversationId == ""`.
- No androidTest changes are needed. `ThreadScreenModalTest` constructs `Open`/`Dismissed` without the trailing field, and the screen renders what it is handed.
- No rung-3 scenario here. Live behaviour is #679 (`needs-real-claude`, blocked by this ticket), per AC 4.

## Documentation handoff

Pending for the documentation stage. The `conversation_id` scoping belongs in `docs/knowledge/features/current-modal-state.md` (the fold now carries the conversation into `Open`/`Dismissed`; `scopedTo`), `features/modal-events.md` (the decode seam's defaulted `conversation_id`) and `features/thread-screen.md` (the thread filters the host modal by its own conversation).

## Open questions

- None blocking. The single-modal-per-host fold is kept deliberately (ticket Technical Notes). If prompts from two conversations were outstanding at once, the later one would supersede the earlier in the fold, as it does today.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. `conversation_id` enters at the single decode seam, `ModalShownPayloadDto`, as an inert string and is only ever compared for equality against the thread's own route id, in `scopedTo`. It never selects a host, forms a URL, path or key, or reaches a log. The answer path still sends only the VM's own `modal_id` (`onModalOption` / `onModalCancel`). No phone-asserted conversation reaches the daemon, which matches the contract's outbound-only rule. A missing or blank id fails closed to "no thread", never to "all threads".
- [Trust boundaries] No findings. The same daemon-authored free text (`title`, `prompt`, option labels) still renders as `Text` through the existing overlay. This ticket adds no new text sink.
- [Tokens / crypto / storage / IPC] Not applicable. There is no token, key, file, intent or crypto change. The field lives in memory only.
- [Network & I/O] No findings. One optional string is added to an existing frame, and it is bounded by the existing relay frame cap. There is no new verb.
- [Logs] No findings. `scopedTo` and `reduce` do not log, and the id isn't logged anywhere.
- [Concurrency] No findings. The input guard reads the host `StateFlow.value` synchronously (`scopedModal()`), so there is no check-then-act window in which a prompt from another conversation is answerable through a lagging derived flow. The new `stateIn` is owned by `viewModelScope`.
- [Threat model] OUT OF SCOPE. A compromised daemon can stamp any `conversation_id`, but it could equally raise the prompt in any thread; the scoping is a display aid, and the daemon remains the authority on resolution. Several outstanding prompts per host (a per-conversation map) are deferred, and the ticket explicitly keeps the single-modal fold.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
