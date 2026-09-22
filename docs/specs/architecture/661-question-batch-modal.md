# #661 — Render mobile clarification questions and answers

## Files read

- `data/model/QuestionBatch.kt` → `QuestionBatch`, `Question`, `QuestionOption`, `QuestionAnswer`, `batchFor` — the held-batch shape and the never-log / never-key rule for claude-authored strings.
- `data/repository/RelayRepositoryCoordinator.kt` → `observeQuestionBatch`, `answerQuestionBatch`, `refuseQuestionBatch`, `questionBatches` — the read seam (empties on reconnect via `teardownActive`) and the fire-and-forget sends (throw `IllegalStateException` / `IllegalArgumentException`, no reply).
- `data/repository/RemoteConversationRepository.kt` → `answerQuestionBatch` — requires every question index exactly once; values sent verbatim.
- `ui/conversations/thread/ThreadViewModel.kt` → constructor lambdas `answerModal` / `cancelModal`, `sendAnswer` / `sendCancel`, `modalSendErrors` — the precedent this ticket mirrors (suspend lambdas injected, catch only the documented throws, CancellationException rethrown first).
- `di/AppModule.kt` → `thread` — where coordinator seams are bound into `ThreadViewModel`.
- `MainActivity.kt` → the `Routes.CONVERSATION_THREAD` destination — the route host collecting VM flows.
- `ui/components/MobileModal.kt` → `MobileGateModal`, `MobileModalShell`, `ModalCancelButton` — the hardened gate (no Back/outside dismissal, `FLAG_SECURE`, obscured-touch filter) that currently offers Cancel only and no error.
- `ui/conversations/thread/ThreadScreen.kt` → `PermissionModalOverlay` — gate caller precedent; not modified (see Context).
- `androidTest/.../ui/components/MobileModalTest.kt` → `withTestIme`, `ime_keeps_focused_field_final_item_and_actions_reachable` — the IME harness the reachability test copies.
- `docs/knowledge/features/mobile-modal.md`, `permission-modal-overlay.md` — gate contract, and the lesson that a `hasText` assertion does not prove unclipped text.
- pyrycode-desktop `questionResolution.ts` → `resolveQuestionAnswers`; `questionPicksStore.ts` — option-order values, Other counted only when ticked and non-blank, Other text held independently of its tick, picks reset on reconnect.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (mobile modal shell) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6913 (Questionnaire: option row `347:6228`, Other row `347:6386` / `347:6709`, actions `347:6657`).

The existing `MobileGateModal` shell (primaryContainer surface, titleLarge header, divider, fixed footer) hosts one scrolling column. Each question is a tertiary `labelMedium` header line above a `background`-filled card with a 1 dp `primaryContainer` border and `shapes.small` corners, 16 dp padding: the question text in `bodyMedium` (medium weight), then option rows (tertiary radio / checkbox, 12 dp gap, label in `labelMedium` semibold over its description in `labelMedium`), then the Other row with a text field. The footer is outlined Cancel + filled Continue. Deviations: no desktop tab strip / Previous-Next; the header is not uppercased (claude text rendered as given); action targets stay ≥ 48 dp like the rest of the shell.

## Context

`#822` holds batches, `#825` sends answers/refusals; nothing renders them. This ticket adds the modal and the view-model half. File count: the estimate named `ThreadScreen.kt`, but a new parameter there must also be forwarded from `MainActivity`, making six production files. Instead the route host draws the question modal directly beside `ThreadScreen` (it is its own `Dialog` window, so placement in the tree does not matter). Production files: `ThreadViewModel.kt`, `AppModule.kt`, `MainActivity.kt`, `MobileModal.kt`, new `QuestionBatchModal.kt` (+ `strings.xml`).

## Design

### State types (in `ThreadViewModel.kt`, beside `ThreadUiState`)

- `data class QuestionSelection(optionIndices: Set<Int> = emptySet(), otherTicked: Boolean = false, otherText: String = "")` — per-question picks; option identity is its index, never its label.
- `enum class QuestionSendPhase { Idle, Sending, Sent, Failed }`.
- `data class QuestionModalState(batch: QuestionBatch, selections: List<QuestionSelection>, phase: QuestionSendPhase)` with
  - `locked: Boolean` = phase is `Sending` or `Sent`;
  - `answers(): List<QuestionAnswer>?` — per question, values = picked option labels in option order, then `otherText` verbatim when ticked and not blank; null if any question has no value;
  - `canContinue` = `!locked && answers() != null`.
- `sealed interface QuestionModalEvent`: `OptionToggled(questionIndex, optionIndex)`, `OtherToggled(questionIndex)`, `OtherTextChanged(questionIndex, text)`, `Continue`, `Cancel`.

Selection rules (pure `QuestionSelection` transforms): single choice — an option pick replaces the pick and unticks Other; ticking Other clears option picks. Multiple choice — options and Other toggle independently. Typing in Other ticks it (with the single-choice clear). Unticking Other keeps its text (desktop).

### ViewModel

New constructor params, defaulted inert like `answerModal`:
- `questionBatch: (conversationId: String) -> Flow<QuestionBatch?> = { flowOf(null) }`
- `answerQuestionBatch: suspend (questionBatchId: String, answers: List<QuestionAnswer>) -> Unit`
- `refuseQuestionBatch: suspend (questionBatchId: String) -> Unit`

`val questionModal: StateFlow<QuestionModalState?>` backed by a private `MutableStateFlow`. An init-time `viewModelScope` collector of `questionBatch(conversationId)` (filtered to `batch.conversationId == conversationId`, a belt over `batchFor`) reconciles: the same batch (data equality) keeps the current state; any other batch starts fresh (`Idle`, empty selections); null closes and discards. A reconnect empties the coordinator's list before the reconcile re-sends, so the thread sees null then the batch and starts fresh.

`fun onQuestionEvent(event: QuestionModalEvent)`: selection events are ignored while `locked`. `Continue` requires `canContinue`; `Cancel` requires `!locked`. Both set `Sending`, capture the batch id from the state they hold, launch the send, then set `Sent` or `Failed` — applied only if the held batch id still matches, so a late completion never touches a newer batch. Catch `IllegalStateException`, `IllegalArgumentException` and `RelayErrorException` after rethrowing `CancellationException`. `Failed` keeps selections and re-enables actions. The question path never calls `answerModal`.

Logging: content-free `RelayLog.d { "event=question_send kind=answer|refuse outcome=sent|failed" }`. No ids, labels or values.

### `MobileGateModal` extension

Defaulted new params so `PermissionModalOverlay` is unchanged: `submitLabel: String? = null`, `onSubmit: () -> Unit = {}`, `submissionEnabled: Boolean = true`, `sending: Boolean = false`, `error: String? = null`. When `submitLabel` is set the footer adds a filled submit button (same styling as `MobileModal`'s OK, progress indicator while `sending`). `sending` disables both Cancel and submit: on a gate, Cancel is itself a decision send. `error` goes to the shell's existing error slot. `ModalCancelButton` gains `enabled`.

### `QuestionBatchModal.kt` (new, `ui/conversations/thread/`)

`@Composable internal fun QuestionBatchModal(state: QuestionModalState, onEvent: (QuestionModalEvent) -> Unit)`: a `MobileGateModal` with a fixed title, Cancel/Continue labels, `submissionEnabled = state.canContinue`, `sending = state.locked`, and `error` set to a fixed string when `Failed`. The content has one block per question in batch order. Single-choice rows use `Modifier.selectable(role = Radio)` inside `selectableGroup`, and multiple-choice rows use `toggleable(role = Checkbox)`. The Other row is a radio or checkbox labelled "Other" followed by an `OutlinedTextField` with test tag `question_other_<index>`, a tag built from the index only. Controls are disabled while locked. All claude text goes through plain `Text`, which wraps and has no `maxLines`. The composable keeps no selection state of its own.

`MainActivity` collects `vm.questionModal` and draws `QuestionBatchModal` when it is non-null.

## State + concurrency model

A single `viewModelScope` collector reads the batch flow, and send jobs run in `viewModelScope`. Both are cancelled with the ViewModel. Selections live only in the VM, not in `SavedStateHandle`, so process death discards them. The daemon's reconnect re-sends the batch. `Dispatchers.Main` is used throughout.

## Error handling

A throw from a send becomes `Failed`, which shows the fixed in-modal error "Couldn't send. Try again." The failure is also logged content-free. A missing connection is an `IllegalStateException` from the coordinator, so it takes the same path.

## Testing strategy

- `ThreadViewModelQuestionTest` (unit, `runTest` + `UnconfinedTestDispatcher`, fake batch `MutableStateFlow`, recording lambdas). It covers:
  - Continue is disabled until every question is answered, including when Other is blank.
  - Single-choice Other clears the picked option.
  - One answer covers every question with verbatim values.
  - A double Continue sends once, and Cancel is ignored while in flight or sent.
  - A failed send keeps selections and allows a retry.
  - Dismissal to null discards selections, and a replacement batch starts fresh.
  - A late send completion does not touch a newer batch.
  - Another conversation's batch is ignored.
  - Refusal follows the same single-send rule and failure path.
  - `answerModal` is never called.
- `QuestionBatchModalTest` (androidTest, stateful host around the stateless composable driven by the real reducer):
  - Radio semantics and Other clearing on single choice.
  - Checkbox multi-pick plus Other text.
  - Continue enabled state and click reporting.
  - At 320 × 640 with the `withTestIme` harness and the keyboard shown, the last question's Other field and its final option can be scrolled into view, and Cancel and Continue stay displayed.
- No rung-3 scenario here; the live proof is #679.

## Open questions

- Desktop trims Other text before sending. The AC says "sent verbatim", so mobile sends the typed text untrimmed and uses the trim only for the blank check.

## Documentation handoff

Pending for the documentation stage: the ticket names no documentation section. The shared-modal overview (`docs/knowledge/features/mobile-modal.md` § The hardened gate) should record `MobileGateModal`'s new submit, sending and error parameters, and `QuestionBatchModal` is its second caller.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Claude-authored headers, question text, labels and descriptions enter only through the `QuestionBatch` type from #822. They render only through plain `Text`, never markdown, a link, an attribute, a key or a log. Option and question identity is the list index. The single string built from them is the test tag `question_other_<index>`, which uses the index only. The data layer puts no per-string length bound on this text. The Noise transport's 65535-byte message cap bounds a whole batch. Wrapped text in the scrolling column costs height only, so this ticket adds no render clamp.
- [Trust boundaries] No findings. The cross-conversation belt in the ViewModel drops any batch whose `conversationId` is not the thread's, on top of the coordinator's `batchFor`. Sends take the batch id from the VM's own held state, never from the UI. This follows the permission prompt's `modalId` rule.
- [Tokens] No findings. The `answer_token` is minted in `RemoteConversationRepository`'s `questionToken`, and this ticket neither touches nor reads it.
- [File / storage] No findings. Selections and Other text live only in the VM's `MutableStateFlow`, with no `rememberSaveable` or `SavedStateHandle`. They are gone on process death and on dismissal.
- [Android surface] No findings. No intents, deep links or providers are involved. The modal is `MobileGateModal`, so its own window has `FLAG_SECURE` and `filterTouchesWhenObscured` set, which covers screenshot and tapjacking leakage of claude text and operator answers. Back and outside taps never send.
- [Crypto] No findings. No primitives are used.
- [Network & I/O] No findings. Sends go through the coordinator's existing passthroughs, and the frame shape and validation belong to #825.
- [Logs] No findings. The single new log line is `event=question_send kind=… outcome=…`, made of static codes only. Exceptions are caught without logging their messages. The in-modal error is a fixed string resource.
- [Concurrency] No findings. `onQuestionEvent` runs on Main and sets `Sending` synchronously before launching, so a double tap sends once. A completion updates the phase only when the held batch id still matches, so a late result never unlocks or marks a newer batch. Both jobs are bound to `viewModelScope`.
- [Threat model] OUT OF SCOPE: keyboard learning of Other text by a third-party IME. It has the same exposure as the thread composer, and no ticket tracks it. A question answer never grants a tool permission, because the question path holds only the question lambdas and the tests assert that `answerModal` is never invoked.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- 2026-09-23 (build): `QuestionBatchModal` also takes `modifier: Modifier = Modifier`, which is passed to the gate. The 320 × 640 test needs it to size the dialog's own window. `DeviceConfigurationOverride` alone does not constrain a `Dialog`, and `MobileModalTest` relies on the same seam. The IME test finds the gate's window with `WindowInspector.getGlobalWindowViews()` instead of a `LocalView` captured in content, because the composable exposes no content hook.
