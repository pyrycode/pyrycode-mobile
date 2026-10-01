# #1340 — Close a prompt on answer and show a refused answer in its chat

## Files read

- `data/model/ModalUiState.kt` — `HostModalState`, `HostModalState.reduce(ModalEvent)`, `HostModalState.scopedTo`, `ModalUiState.Dismissed`. The fold this ticket extends.
- `data/repository/RelayRepositoryCoordinator.kt` — `modalEvents` (the `null` reconnect marker), `hostModals` (the `scan` that resets on `null`), `answerModal` / `cancelModal` (the throws: `RelayErrorException`, `IllegalStateException`).
- `di/AppModule.kt` — `ThreadDestinationFactory.thread`, which hands the coordinator's `hostModals` and send lambdas to `ThreadViewModel`; the new fold entry point travels the same way.
- `ui/conversations/thread/ThreadViewModel.kt` — `onModalOption`, `onModalCancel`, `sendAnswer`, `sendCancel`, `modalSendErrorChannel` / `modalSendErrors`, `scopedModal`, `currentModal`.
- `ui/conversations/thread/ThreadScreen.kt` — the `modalSendErrors` snackbar `LaunchedEffect`, the `permissionRequestItems` slot at the head of the reversed `LazyColumn`, `promptRowCount` (the older-history predicate counts prompt rows), and the `ModalUiState.Dismissed` snackbar branch at the end of `ThreadScreen`.
- `ui/conversations/components/NoticePill.kt` — `NoticePill`, the Default variant with its X (`onDismiss`).
- `ui/conversations/thread/PermissionDraftStore.kt` — `keeps` retires a draft once its `modalId` is in `resolved`, so a local answer retires the grant draft as a daemon dismissal would.
- `MainActivity.kt` — the thread destination's `ThreadScreen` call, which passes `vm.modalSendErrors`.
- `docs/knowledge/features/modal-answer-flow.md` — catch order is load-bearing (`CancellationException` first, since it extends `IllegalStateException`); arm clears on the attempt.
- `docs/knowledge/features/current-modal-state.md` — the reconnect marker empties the fold so only the daemon's re-sends bring a prompt back.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, step 4 waits for the daemon's dismissal before `awaitNoPromptDialog`.
- Desktop (`pyrycode-desktop`): `answerPrompt` / `cancelPrompt` in `modalResolution.ts`, `reduceModal`'s `rejectionOwners` in `modalPrompts.ts` — the model, per the ticket.

Overlaps: #1338 deletes `latestOutstanding` / the coordinator's `currentModal` next to `hostModals`; #1344, #1346, #1360 and others touch `ThreadViewModel.kt`, `ThreadScreen.kt`, `MainActivity.kt`, `strings.xml` and the e2e class in other blocks. My edits there stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242 (the inline permission card) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6617 (Default `NoticePill`).

No frame draws the rejection. It reuses the existing `NoticePill` (Default: `primaryContainer` / `onPrimaryContainer`, `bodySmall`, 6dp radius, trailing 8dp X) unchanged, laid in the page with no shadow (`shadowElevation = 0.dp`), start-aligned inside the card's own gutter (`ComposerGutter` horizontal, 4dp vertical) in the message-list slot the card occupied.

## Context

Today a tap sends the answer and leaves the card until the daemon's `modal_dismissed`, which names the phone's own answer `source: remote` and so raises "Resolved on another device" for the user's own tap. A failed send raises a "Couldn't send your answer" snackbar and leaves the card. Desktop closes the prompt at once, swallows a send failure, and keeps a refused answer visible in the owning chat until dismissed. This ticket brings mobile to that behaviour. No decision record needed; it follows desktop.

## Design

### Fold (`ModalUiState.kt`)

- `ModalUiState.Dismissed` gains `answeredHere: Boolean = false` — this phone recorded the resolution itself.
- `HostModalState` gains `rejectedConversations: Set<String> = emptySet()` — the chats showing "Your answer was rejected." (desktop `rejectionOwners`).
- New exported `sealed interface ModalAction` — what this phone did to a prompt, folded beside the wire's `ModalEvent`s:
  - `AnsweredHere(modalId)` — an answer or cancel left the phone's hands.
  - `Rejected(conversationId)` — the daemon refused that chat's answer.
  - `RejectionDismissed(conversationId)` — the user tapped the notice's X.
- `internal fun HostModalState.reduce(action: ModalAction): HostModalState`:
  - `AnsweredHere` for a held id → removed from `outstanding`, appended to `resolved` as `Dismissed(modalId, outcome = "", source = "", conversationId = held.conversationId, answeredHere = true)`. An id the host does not hold → unchanged. The existing `Shown` guard (`resolved.any { modalId }`) then keeps a repeated `modal_shown` from bringing it back on this connection, and the daemon's later `modal_dismissed` names an id no longer held, so the existing branch ignores it.
  - `Rejected` with a non-blank conversation → added to `rejectedConversations`; blank → unchanged.
  - `RejectionDismissed` → removed.
- `internal fun HostModalState.reconnected(): HostModalState` — empty except `rejectedConversations`, which outlives the reset.
- `HostModalState.scopedTo`: when the chat holds no outstanding prompt, its **latest** resolved entry is returned only if it is not `answeredHere`; otherwise `Hidden`. So the user's own tap produces no snackbar, and an older remote dismissal of the same chat does not resurface either (its `LaunchedEffect(modalId)` would re-fire on the `Open → Dismissed(older)` change).

### Coordinator (`RelayRepositoryCoordinator.kt`)

`hostModals` becomes a private `MutableStateFlow<HostModalState>` exposed read-only. One collector launched in `scope` (as `stateIn(Eagerly)` did) folds `modalEvents`: `null` → `reconnected()`, event → `reduce(event)`, through `update {}`. New `fun recordModalAction(action: ModalAction)` folds an action synchronously with `update {}`, so the thread's synchronous `hostModal.value` read sees it before the send launches. No log: actions carry modal ids.

### Wiring (`AppModule.kt`)

`ThreadDestinationFactory.thread` passes `recordModalAction = { action -> bundle?.coordinator?.recordModalAction(action) }`. The demo host keeps the inert default.

### ViewModel (`ThreadViewModel.kt`)

- New constructor param `recordModalAction: (ModalAction) -> Unit = {}` beside `cancelModal`.
- `sendAnswer(open, optionId, alwaysAllow)`: clear the arm, `recordModalAction(AnsweredHere(modalId))`, then launch the send. Catch order unchanged (`CancellationException` rethrown first). `RelayErrorException` → `recordModalAction(Rejected(conversationId))`. `IllegalStateException` → nothing (the daemon re-sends an unanswered prompt on the next connection).
- `sendCancel`: `AnsweredHere` first, then send; both caught failures show nothing.
- Removed: `modalSendErrorChannel`, `modalSendErrors`.
- New `val answerRejected: StateFlow<Boolean>` — `conversationId in hostModal.rejectedConversations`, `Eagerly`, seeded from `hostModal.value`.
- New `fun onAnswerRejectionDismissed()` → `recordModalAction(RejectionDismissed(conversationId))`.
- Content-free debug logs: `event=permission_answer outcome=rejected|unsent`, `event=permission_cancel outcome=refused|unsent`.

### Screen (`ThreadScreen.kt`, `MainActivity.kt`, `strings.xml`)

- `ThreadScreen` drops `modalSendErrors` and its `LaunchedEffect`; gains `answerRejected: Boolean = false` and `onDismissAnswerRejection: () -> Unit = {}`.
- When `answerRejected`, one `item(key = "permission-rejection")` follows the permission items in the reversed list (so it sits above any newer card, at the card's slot when none): `NoticePill(text = permission_answer_rejected, isError = false, onDismiss = onDismissAnswerRejection, shadowElevation = 0.dp)`, test tag `thread-permission-rejection`. `promptRowCount` counts it.
- `MainActivity` collects `vm.answerRejected`, passes it and `vm::onAnswerRejectionDismissed`, and drops `modalSendErrors`.
- `strings.xml`: add `permission_answer_rejected` = "Your answer was rejected."; remove `modal_send_failed`.

## State and concurrency model

The fold is one `MutableStateFlow` in the coordinator's process scope, written by the wire collector and by `recordModalAction`, both through `update {}` so neither loses the other's write. `recordModalAction` runs on the caller's thread (main) and is non-suspending. Sends stay in `viewModelScope`; a VM cleared mid-send cancels the send and records nothing after the local dismissal. The rejection set lives as long as the coordinator, which lives as long as the host's pairing bundle; a reconnect keeps it.

## Error handling

| Failure | Result |
|---|---|
| Answer → `RelayErrorException` | Card already gone; chat shows the rejection notice until its X |
| Answer → `IllegalStateException` (not sent / torn down) | Nothing shown; the next connection re-sends the prompt |
| Cancel → either | Nothing shown |
| `CancellationException` | Rethrown; nothing recorded after the local dismissal |

The notice copy is a client string; no daemon text or error code reaches the UI.

## Testing strategy

- `ModalUiStateTest` (unit): `AnsweredHere` removes the held prompt and records it; a repeated `Shown` for that id is ignored; a later wire `Dismissed` is ignored; `scopedTo` is `Hidden` after an answered-here (also with an older remote dismissal of the same chat); unknown id unchanged; `Rejected` / `RejectionDismissed` add and remove; blank owner ignored; `reconnected()` keeps rejections, drops outstanding and resolved, and a re-sent `Shown` then returns.
- `RelayRepositoryCoordinatorTest` (unit): `recordModalAction` updates `hostModals.value` synchronously; a rejection survives a new connection while a re-sent prompt returns.
- `ThreadViewModelTest` (unit, `hostChat` with a `MutableStateFlow<HostModalState>` and a recorder lambda that folds into it): default tap, armed confirm and cancel each make `currentModal` `Hidden` before the send completes (send gated on a `CompletableDeferred`); `RelayErrorException` on answer sets `answerRejected` in the owning chat only and `onAnswerRejectionDismissed` clears it; `RelayErrorException` on cancel and `IllegalStateException` on either leave `answerRejected` false; cancellation mid-send records no rejection. Existing `modalSendErrors` cases are rewritten to these assertions.
- `ThreadScreenModalTest` (shared Robolectric): `answerRejected = true` shows the pill with the copy and an X whose click calls `onDismissAnswerRejection`; false shows nothing. The `modal_send_failed` snackbar case is removed.
- Rung 3: `InteractiveStreamE2ETest.interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` step 4 waits for the card to leave right after the allow tap, before awaiting the daemon's dismissal, and asserts no "Resolved on another device" snackbar after it. The live gate runs it and `interactiveTurn_permissionPrompts_heldPerConversation`, which also allows from the phone (`## Live tests`). The builder never runs the live suite; AC 4's counts come from the dispatcher's live gate. No rung-4 twin: the scripted fixture's prompts are not answered from the phone today.

## Open Questions

- Should a rejection notice and a new card in the same chat both show? Yes — desktop's `visibleRejections` is independent of the open prompt; the notice sits above the newer card.

## Documentation handoff

- `docs/knowledge/features/modal-answer-flow.md`: cover the local dismissal on tap and the in-chat rejection notice, including that the notice outlives a reconnect while the resolved record does not. Pending for the documentation stage.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only new inbound influence is *whether* `answerModal` threw `RelayErrorException`; its daemon-authored `message` and `code` are discarded in `sendAnswer`'s catch and never reach state, UI or logs. The notice is the fixed `permission_answer_rejected` resource. A `Rejected` owner is the VM's own `conversationId`, never a wire value.
- [Trust boundaries / spoofing] No findings. A hostile or replayed `modal_dismissed` still needs an exact held `modalId` (unchanged `reduce(ModalEvent)`); `AnsweredHere` only removes a prompt this thread's own `scopedModal()` returned, after the #1306 stale-id and #1321 connected gates.
- [Tokens] No findings. No token, key or credential is created, stored or logged; the `answer_token` stays inside `ConversationCommands`.
- [Files & storage] No findings. Rejection state is heap-only in the coordinator; nothing is persisted.
- [Android attack surface] No findings. No new component, intent, deep link or WebView.
- [Cryptography] No findings. No crypto touched.
- [Network & I/O] SHOULD FIX. Dropping the card before the reply means a silently unsent answer is invisible. The design relies on the daemon's connect-time re-send for `IllegalStateException`; Phase B must keep `IllegalStateException` mapped to "unsent" (no rejection, no resolved record surviving) and add the coordinator test showing a reconnect clears the answered-here record so the re-sent prompt returns.
- [Errors, logs] SHOULD FIX. New debug logs carry only static outcome codes (`rejected`, `unsent`, `refused`), never `modalId`, `optionId`, conversation id or the exception message; `recordModalAction` in the coordinator logs nothing. The verifier checks the log lines.
- [Concurrency] SHOULD FIX. The wire collector and `recordModalAction` both write the fold; both must use `MutableStateFlow.update {}` so a `modal_shown` arriving during a tap is not lost. Catch order in `sendAnswer` / `sendCancel` keeps `CancellationException` first so VM teardown records no rejection.
- [Threat model — malicious relay] No findings. A relay that drops the reply makes `sendAndAwaitReply` time out or the connection close (`IllegalStateException`) → nothing shown, prompt re-sent on reconnect; it cannot forge a rejection (it cannot produce an in-session `error`). A hostile daemon can at most show the fixed rejection copy in the chat that answered.
- [Threat model — UI leakage] No findings. The notice copy is fixed; no prompt text moves to a new surface. The removed snackbar was already fixed copy.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

- **2026-10-01 — constructor position and empty thread.** `recordModalAction` is the last `ThreadViewModel` constructor parameter rather than beside `cancelModal`, so no positional caller in the twenty-odd test files shifts; `AppModule` and `makeVm` pass it by name. `ThreadScreen`'s empty-thread branch now also yields to the list when `answerRejected` holds, as it already did for an open request; otherwise a refused answer in a chat with no messages had no slot to draw in (caught by `ThreadScreenModalTest`). The contracts above are otherwise unchanged.
