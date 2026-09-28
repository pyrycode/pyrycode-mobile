# Question batch modal

Renders the clarification batch a `AskUserQuestion` tool call holds open for the active conversation
(#661), and wires Continue / Cancel through `ThreadViewModel`. The data layer that holds and sends batches
predates this: #822 holds each host's outstanding batches and exposes
`RelayRepositoryCoordinator.observeQuestionBatch(conversationId)`; #825 adds the sends,
`answerQuestionBatch` / `refuseQuestionBatch`. This ticket is the first (and so far only) consumer of both.
The live end-to-end proof — a real `AskUserQuestion` batch rendered, answered and refused against the
daemon, closed by its `question_dismissed` — is left to #679; everything below is unit- and Compose-tested
against fakes.

## Where it lives

- `ui/conversations/thread/QuestionModalState.kt` — `QuestionSelection`, `QuestionSendPhase`,
  `QuestionModalState`, `QuestionModalEvent`; `ThreadViewModel.kt` — the `questionModal: StateFlow<QuestionModalState?>` and
  `onQuestionEvent`.
- `ui/conversations/thread/QuestionBatchModal.kt` (new file) — the stateless composable and its private
  `QuestionBlock` / `ChoiceRow`.
- `ui/components/MobileModal.kt` — `MobileGateModal` gained the submit/sending/error extension this modal
  is the first to use; see [Shared mobile modal § The hardened gate](mobile-modal.md#the-hardened-gate-mobilegatemodal).
- `MainActivity.kt` — collects `vm.questionModal` and draws `QuestionBatchModal` **beside** `ThreadScreen`,
  not inside it (see § Placement below).
- `di/AppModule.kt` — binds the coordinator's `observeQuestionBatch` / `answerQuestionBatch` /
  `refuseQuestionBatch` into the `ThreadViewModel` constructor, the same passthrough shape
  [Modal answer flow](modal-answer-flow.md) uses for `answerModal` / `cancelModal`.

## State shape

`QuestionSelection(optionIndices: Set<Int>, otherTicked: Boolean, otherText: String)` holds one question's
picks. An option is identified by its **index**, never its claude-authored label — the same
never-a-key rule `QuestionBatch.kt`'s KDoc states for every claude-authored string in this batch.
`otherText` is held independently of `otherTicked` (mirrored from desktop's `questionPicksStore`):
unticking Other keeps whatever was typed, so re-ticking it does not lose the draft. Single choice and
multiple choice differ only in `withOption` / `withOtherTicked`'s clearing rule — picking an option on a
single-choice question clears any Other tick, and ticking Other clears any option pick; both directions are
independent on a multiple-choice question. Typing into the Other field ticks it (with the same
single-choice clear), so a typed answer cannot sit un-ticked and silently excluded.

`QuestionModalState(batch, selections, phase: QuestionSendPhase, agent: ConversationAgent =
ConversationAgent.Claude)` is one send's worth of state, plus the conversation's agent for the title
(#1116, § Title below):
`locked` is true while `phase` is `Sending` or `Sent` — **`Sent`, not just `Sending`**, because the daemon
sends no reply to `question_answer` / `question_refused` and the batch is only truly resolved by the later
`question_dismissed`. A modal that unlocked itself after `Sent` would let a second Continue race the first
send. `answers()` returns one `QuestionAnswer` per question — option labels in **option order**, then the
Other text **verbatim** when ticked and non-blank — or `null` if any question has no value; `canContinue`
composes that with `!locked`.

## Batch ownership: one modal, one batch, no stale sends

`ThreadViewModel` holds a single `viewModelScope` collector of the injected `questionBatch(conversationId)`
flow, filtered to `batch.conversationId == conversationId` as a belt over the coordinator's own
`batchFor` filtering (a batch held for a different conversation must never surface in this thread, let alone
receive this thread's answers). The reconcile is a plain data-equality check: the *same* batch (by value)
keeps whatever `phase` and `selections` the modal already has; *any other* batch — a genuine replacement, or
the same `questionBatchId` re-sent after a reconnect's empty-then-rebuild — starts fresh at `Idle` with
empty selections; `null` closes the modal and discards everything in it. Selections live only in this
`MutableStateFlow`, never `SavedStateHandle` or `rememberSaveable`, so process death also discards them —
the daemon's reconnect re-sends the batch regardless.

Both sends (`Continue` → `answerQuestionBatch`, `Cancel` → `refuseQuestionBatch`) go through one
`sendQuestion(questionBatchId, kind, send)` helper: it captures `questionBatchId` from the state held **at
the moment of the tap**, sets `Sending` synchronously (so a double tap before recomposition still sends
once), then applies the outcome (`Sent` or `Failed`) only if the *currently held* batch's id still matches
— a completion for a batch the modal has since moved past (dismissed, replaced) is silently dropped rather
than reviving stale UI. `CancellationException` is rethrown before the typed `RelayErrorException` /
`IllegalStateException` / `IllegalArgumentException` catches, matching every other guarded send in this
view model (`sendMessage`, `launchHistoryAsk`, [modal answer flow](modal-answer-flow.md)'s
`sendAnswer`/`sendCancel`). A `Failed` outcome keeps every selection so the user can retry without re-picking
anything, and re-enables both footer buttons.

**Verbatim, not trimmed.** The AC requires Other text sent "verbatim". Desktop's `resolveQuestionAnswers`
trims it before sending; mobile does not — `values()` uses `otherText.isNotBlank()` only to decide whether
Other counts as answered, and sends the exact typed string. A caller porting behaviour from the desktop
`questionResolution.ts` reference should check each such transform against the ticket's own AC rather than
assuming parity.

**The question path never touches `answerModal` / `cancelModal`.** It holds its own
`answerQuestionBatch` / `refuseQuestionBatch` lambdas, defaulted inert like every other outbound send this
VM owns. `ThreadViewModelQuestionTest` asserts this directly — a passing answer/refuse flow that also
records a call to the permission-modal's `answerModal` would be a real cross-wiring bug, not just an
untested path.

## Title names the conversation's agent (#1116)

`QuestionBatchModal`'s title picks `question_modal_title` ("Claude has questions") or
`question_modal_title_codex` ("Codex has questions") from `state.agent`. `QuestionBatch` itself carries no
agent field, so `ThreadViewModel` derives it separately: while a batch for this conversation is held, a
`flatMapLatest` subscribes to the conversation list and maps this conversation's row to its `agent` (Claude
when the row is absent), seeded with Claude through `onStart` so a cold list never holds the modal back, and
`distinctUntilChanged` so an unrelated list update doesn't re-emit the same agent. The batch-held fold keeps
this agent alongside the batch's own reconcile rule (§ Batch ownership above): the same batch keeps its
picks and gets `copy(agent = …)`; any other batch starts fresh. No held batch means no list subscription —
a thread with no questions issues no extra `list_conversations`.

**Left as a known duplicate subscription, non-blocking.** This collector calls
`repository.observeConversations(ConversationFilter.All)` directly rather than reusing the shared lookup.
\#1110 (merged after this ticket's plan was written) added a private `conversationAgent: Flow<ConversationAgent>`
to `ThreadViewModel` for exactly this conversation-agent lookup, built over a `conversations` flow shared
with `state`'s own combine so every subscriber rides one upstream `list_conversations` request — see
[Thread screen § The model-menu agent filter (#1110)](thread-screen-how-it-works-state.md#the-model-menu-agent-filter-1110).
Because this ticket's plan predates that merge, the shipped collector opens a second, independent
subscription instead. `RemoteConversationRepository.observeConversations`'s `StateFlow` conflation absorbs
the duplicate, so the verifier passed it as a non-blocking SHOULD FIX rather than a blocker. A future change
touching this collector should fold it onto `conversationAgent.onStart { emit(ConversationAgent.Claude)
}.distinctUntilChanged()` instead of subscribing to `observeConversations` a second time.

## Placement: `MainActivity`, not `ThreadScreen`

Unlike `PermissionModalOverlay`, which `ThreadScreen` draws inline from `ThreadPermissionModal.kt`, the
route host in `MainActivity` draws `QuestionBatchModal` directly, as a sibling of the `ThreadScreen` call
rather than a parameter threaded into it. `MobileGateModal` opens its own `Dialog` window, so where in the
composition tree it is invoked does not change what it visually sits over — and drawing it from the route
host avoids adding a sixth production file (`ThreadScreen.kt` plus the `MainActivity` call site that would
have to forward a new parameter into it) for what the estimate treated as one file. Keep this precedent in
mind before adding a parameter to `ThreadScreen` for a gate-shaped surface: if the gate's own window makes
placement irrelevant, drawing it beside the screen instead of through it can be the smaller change.

## Rendering

`QuestionBatchModal` is stateless — `(state: QuestionModalState, onEvent: (QuestionModalEvent) -> Unit,
modifier: Modifier = Modifier)` — and keeps no selection state of its own; every edit round-trips through
`onEvent` back into the VM. Each question renders as a tertiary `labelMedium` header line above a
`background`-filled, `primaryContainer`-bordered card (Figma `347:6913`): the question text, then its
options, then an Other row and its `OutlinedTextField` (test tag `question_other_<index>`, built from the
index only — never from claude-authored text). A single-choice question's rows sit in one
`selectableGroup()` with `Role.RadioButton`; a multiple-choice question's rows use `Role.Checkbox`
independently. All claude-authored text (header, question, option label, option description) renders
through plain `Text` with no `maxLines` — the shell's scrolling column already handles overflow by height,
so wrapping is free and no clamp is needed the way [`DebugBundleModal`](mobile-modal-callers.md#callers) needed one
for a fixed-height row.

Known deviation from Figma `347:6913` (verifier finding, non-blocking, unowned): the reference draws a
small tertiary glyph before each question's header line; the shipped `QuestionBlock` renders the header
text alone. No ticket currently owns adding it.

## Testing

- `ThreadViewModelQuestionTest` (unit): validation (Continue disabled until every question is answered,
  including a ticked-but-blank Other), single-choice Other clearing the picked option, one answer covering
  every question with verbatim values, a double Continue sending once, Cancel ignored while sending or
  sent, a failed send keeping selections and allowing a retry, dismissal-to-null and replacement both
  discarding selections, a late send completion not touching a newer batch, another conversation's batch
  being ignored, refusal following the same single-send/failure rules, and `answerModal` never being
  invoked.
- `QuestionBatchModalTest` (androidTest, a stateful host wired to a real `ThreadViewModel` over a fake
  batch flow and recording send lambdas — not a hand-rolled reducer stand-in): radio semantics and Other
  clearing on single choice, checkbox multi-pick plus Other text with the correct sent payload, a failed
  refusal's error message and kept selections, and reachability at 320×640 dp with the keyboard shown.
  The IME case finds the gate's own window with `WindowInspector.getGlobalWindowViews()` rather than
  capturing `LocalView` from inside `content` — the composable exposes no content-side hook to do that, and
  `MobileModalTest` established the same seam for the same reason (see the plan's Revisions and
  [Shared mobile modal § Focus and verification](mobile-modal.md#focus-and-verification)). The test also
  passes `modifier` through to `QuestionBatchModal`, forwarded into the gate, so
  `DeviceConfigurationOverride.ForcedSize` — which does not by itself constrain a `Dialog`'s own window —
  can size it. Before touching the IME, the test waits for that dialog window itself to gain focus,
  sends `CLOSE_SYSTEM_DIALOGS` when focus is absent, and checks focus again before using the cached
  view for inset measurements. A timeout reports the process windows' focus states. The managed API 33
  full UI run for #1235 executed all 78 tests, including this case, with no failures or skips; the
  intermittent external-dialog recovery path did not occur in that run.

## Related

- [Shared mobile modal](mobile-modal.md) — the `MobileGateModal` shell this modal is drawn in, and its
  submit/sending/error extension.
- [Permission-modal overlay](permission-modal-overlay.md) — the sibling gate consumer this ticket's
  ViewModel/render split and lock/send idiom mirrors.
- [Modal answer flow](modal-answer-flow.md) — the `answerModal`/`cancelModal` precedent this ticket's
  `answerQuestionBatch`/`refuseQuestionBatch` passthrough follows, and the VM this modal never calls into.
- [Thread screen](thread-screen.md) — the screen this modal is drawn beside, not inside; see § Placement.
