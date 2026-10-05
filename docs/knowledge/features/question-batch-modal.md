# Question batch modal

Renders the clarification batch a `AskUserQuestion` tool call holds open for a conversation (#661), and
wires Continue / Cancel through `ThreadViewModel`. The data layer that holds and sends batches predates
this: #822 holds each host's outstanding batches and exposes
`RelayRepositoryCoordinator.observeQuestionBatch(conversationId)`; #825 adds the sends,
`answerQuestionBatch` / `refuseQuestionBatch`.

**#1305 moved the batch out of its own `Dialog` window and into `ThreadScreen`'s scrollable message
stream**, reusing the same controls and wire contract. The file name `QuestionBatchModal.kt` and its test
names (`QuestionBatchModalTest`, `QuestionBatchModalCaptureTest`) are a known naming leftover from the
dialog era — see § Placement and § Rendering below for the current shape. The live end-to-end proof — a
real `AskUserQuestion` batch rendered, answered and refused against the daemon, closed by its
`question_dismissed` — landed with #966's `interactiveTurn_questionAnswer_reachesTheAskingConversation`;
\#1305 adapted its selectors to the inline surface (see
[Real-claude e2e coverage](../../e2e-interactive-stream.md)) without changing what it proves. Everything
else below is unit- and Compose-tested against fakes.

## Where it lives

- `ui/conversations/thread/QuestionModalState.kt` — `QuestionSelection`, `QuestionSendPhase`,
  `QuestionModalState` (now carrying a `generation: Long`, § State shape), `QuestionModalEvent`;
  `ThreadViewModel.kt` — the `questionModal: StateFlow<QuestionModalState?>` and `onQuestionEvent(event,
  generation)`.
- `ui/conversations/thread/QuestionDraftStore.kt` (new in #1305) — the app-scoped, process-lifetime store
  that now owns the picks; see § Batch ownership below.
- `ui/conversations/thread/QuestionBatchModal.kt` — `QuestionBatchTitle`, `QuestionBatchActions`,
  `QuestionBlock` / `ChoiceRow` (the rendering pieces, now drawn directly as `ThreadScreen` `LazyColumn`
  items instead of inside a `MobileGateModal` window) and `QuestionPromptProtection` /
  `QuestionProtectionOwners` (the capture/touch-filter guard that replaces the dialog window's own
  protection; § Rendering).
- `ui/conversations/thread/ThreadScreen.kt` — mounts the prompt rows, the newest-end reveal effect and the
  composer-status "Waiting for answers" reading; see [Thread screen § list and status
  row](thread-screen-how-it-works-list-and-status-row.md#inline-question-rows-and-the-newest-end-reveal-1305).
- `MainActivity.kt` — passes `questionState = questionModal` and `onQuestionEvent = vm::onQuestionEvent`
  straight into the `ThreadScreen(...)` call (see § Placement below); no longer draws a sibling `Dialog`.
- `di/AppModule.kt` — `single { QuestionDraftStore() } onClose { it?.dispose() }` binds the app-scoped store;
  `ThreadDestinationFactory.thread` binds it to the destination's `RelayRepositoryCoordinator` and its
  `submitQuestionBatch` handler (§ Batch ownership).
- `data/repository/RelayRepositoryCoordinator.kt` — `submitQuestionBatch(source, batch, answers)`, the
  authoritative outbound boundary the store's submission validates against (§ Batch ownership).

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
ConversationAgent.Claude, generation: Long = 0)` is one send's worth of state, plus the conversation's agent
for the title (#1116, § Title below). **`generation` is new in #1305**: a monotonically allocated, per-store
counter (never saved or persisted) that changes on any replacement, dismissal or source reconnect, even when
the same batch id and payload return. Every `ThreadScreen` event and outbound send now carries the
generation it was rendered against, so a stale composition callback — a tap queued before a replacement, an
edit landing after the batch it edited is gone — cannot act on a newer request; see § Batch ownership below
for the full mechanism.
`locked` is true while `phase` is `Sending` or `Sent` — **`Sent`, not just `Sending`**, because the daemon
sends no reply to `question_answer` / `question_refused` and the batch is only truly resolved by the later
`question_dismissed`. A modal that unlocked itself after `Sent` would let a second Continue race the first
send. `answers()` returns one `QuestionAnswer` per question — option labels in **option order**, then the
Other text **trimmed** when ticked and non-blank — or `null` if any question has no value; `canContinue`
composes that with `!locked`.

## Batch ownership: process-lifetime drafts, source- and request-bound sends

**#1305 moved ownership off the destination `ThreadViewModel` and onto `QuestionDraftStore`, an app-scoped
singleton with the same process-lifetime precedent as [`ComposerDraftStore`](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership).**
The picks a user made before navigating away (Back to the list, opening another chat) used to live in the
destination's own `MutableStateFlow` and were lost when that destination was popped; the store now survives
the destination and is keyed by `(serverId, conversationId)`, so reopening the same conversation for the
life of the app process restores the same selections and Other text for the same outstanding batch. Drafts
are heap-only — never `SavedStateHandle`, `rememberSaveable` or disk — so process death still discards them,
same as before.

**One collector per host, bound once, outliving the destination.** `ThreadDestinationFactory.thread` calls
`questionDrafts.bind(serverId, bundle.coordinator, bundle.coordinator.currentRepository,
liveRepository = bundle.coordinator::liveRepository, submit = bundle.coordinator::submitQuestionBatch)`.
`bind` is a no-op if the same `owner` (the coordinator instance) is already bound for that `serverId`; a
different owner cancels the old collector via `clearHost` before starting a new one. The collector
`collectLatest`s the coordinator's `currentRepository` and, for each `RemoteConversationRepository` it
sees, its `questionBatches`. A reconnect that replaces the concrete repository first reconciles with an
empty list (clearing any stale drafts for that host) before the new repository's own batches arrive — the
same "empty-then-rebuild" shape the pre-#1305 destination-scoped flow already had, just owned one level
higher. `ThreadViewModel` itself keeps its old standalone-test path: when no `QuestionDraftStore` is
injected (the default), it builds a private one and folds the injected `questionBatch(conversationId)` flow
into it directly, so `ThreadViewModelQuestionTest`'s existing fakes need no store wiring.

**Generation, not batch equality, is the identity the send path trusts.** `reconcileHost` keeps an existing
draft's picks and generation only when `old?.batch == batch` (**structural** equality — the daemon can
re-send a `question_shown` with an unchanged payload after a `StateFlowImpl` conflation, and a data-class
equality check treats that as "still the same request"); any other batch — by id or content — allocates a
fresh `QuestionModalState` with the next `nextGeneration`. `current(serverId, conversationId)` and
`submit(...)`, by contrast, re-validate the **held instance** against the live source by identity (`!==`) —
a retired source (`binding.liveRepository() !== source`) or a request the source no longer reports at that
identity clears the draft and logs `event=question_draft_retired`. The verifier flagged the `==`/`!==`
split as a non-blocking SHOULD FIX (the two checks could in principle disagree during a same-id,
same-payload reconnect race); it was not shipped, because `QuestionBatchProjection` applies updates through
`MutableStateFlow.update`, and `StateFlowImpl` never re-stores a value that equals the current one — so an
unchanged batch keeps its original instance in practice, and the two checks cannot actually disagree. A
future change to either identity rule should re-examine that assumption rather than taking it on faith.

**Every event and send is generation-scoped.** `ThreadScreen`'s `onQuestionEvent(event, generation)` passes
the generation the row was rendered against (captured from `pending.generation` at the `LazyColumn` item,
not re-read at tap time). `ThreadViewModel.onQuestionEvent` re-fetches `questions.current(serverId,
conversationId)` and bails if its generation no longer matches — a stale composition callback (queued before
a replacement) is a no-op rather than a mutation of a request that no longer exists. `sendQuestion` captures
the generation at the moment of the tap, sets `Sending` synchronously (so a double tap before recomposition
still sends once), and checks the generation **twice more**: once before launching the send (in case the
draft was already replaced between the tap and the coroutine starting), and again via `QuestionDraftStore.submit`,
which re-validates against the live repository/request **at the outbound boundary**, immediately before the
actual `answerQuestionBatch` / `refuseQuestionBatch` call. `CancellationException` is rethrown before the
typed `RelayErrorException` / `IllegalStateException` / `IllegalArgumentException` catches, matching every
other guarded send in this view model — a cancellation additionally marks the generation `Failed` via
`invokeOnCompletion`, so an interrupted send (e.g. the destination cancelling on navigation) restores
retryability instead of leaving the draft stuck at `Sending`.

**The outbound boundary is the authoritative check, not the store's asynchronous projection.** This closes a
reconnect race the first rework pass found: a reconnect can replace the coordinator's active repository and
rebuild an equal outstanding batch (same id, same payload) while the store's `Dispatchers.Main.immediate`
collector is still queued behind that reconnect. Without a second check, a tap from the *old* surface could
pass the store's stale generation check and then have the injected `answerQuestionBatch` handler read the
coordinator's *new* active repository, redirecting old picks onto the rebuilt request. `submitQuestionBatch`
in `RelayRepositoryCoordinator` closes this: it captures the `source: RemoteConversationRepository` and
`batch: QuestionBatch` the store validated, and re-checks both by identity — `liveRepository() === source`
and the source's own held batch for that conversation `=== batch` — synchronously, immediately before
sending, and always sends through the **captured** source, never a freshly selected one. Locks are always
taken store → coordinator, so the two synchronized blocks cannot deadlock.

**Trimmed, as desktop does.** #1305 shipped Other text sent verbatim; #1349 refined the AC to match desktop's
`resolveQuestionAnswers`, which adds `otherText.trim()` only when Other is ticked and the trimmed text is
non-empty. `values()` now does the same — `otherText.trim().takeIf { otherTicked && it.isNotEmpty() }` — so
whitespace-only Other text still counts as no value. Only the sent value is trimmed; the held draft
(`otherText` in `QuestionDraftStore`) stays untrimmed, so the text field keeps exactly what the operator
typed. A caller porting behaviour from the desktop `questionResolution.ts` reference should still check each
such transform against the ticket's own AC rather than assuming parity by default.

**The question path never touches `answerModal` / `cancelModal`.** It holds its own
`answerQuestionBatch` / `refuseQuestionBatch` lambdas, defaulted inert like every other outbound send this
VM owns. `ThreadViewModelQuestionTest` asserts this directly — a passing answer/refuse flow that also
records a call to the permission-modal's `answerModal` would be a real cross-wiring bug, not just an
untested path.

**Koin closes the store.** `single { QuestionDraftStore() } onClose { it?.dispose() }` — `dispose()` cancels
the store's `SupervisorJob` scope, clears every binding and source, and empties the drafts map. Nothing else
calls `dispose()`; the store is meant to outlive every destination for the app process.

**Continue and refuse are gated on the host connection, not just `locked` (#1321).** `onQuestionEvent`'s
`Continue` and `Cancel` branches both add `promptSendAllowed(kind)` — the same tap-time helper
[modal-answer-flow.md](modal-answer-flow.md#not-connected-refuses-the-tap-before-the-arm-or-grant-change-1321)
describes for the permission surface — ahead of the existing `!held.locked` check and before `sendQuestion`,
which is what moves the batch to `Sending`. A refused tap therefore never locks the batch: picks and Other
text stay exactly as drafted, and the same Continue/Cancel tap sends once the host reconnects. Option toggles
and Other-text edits are not gated — only the two sends are.

## Title names the conversation's agent (#1116)

`QuestionBatchTitle`'s title picks `question_modal_title` ("Claude has questions") or
`question_modal_title_codex` ("Codex has questions") from `state.agent`. `QuestionBatch` itself carries no
agent field, so `ThreadViewModel` derives it separately. Through #1305's first rework pass this read its own
`flatMapLatest` over `repository.observeConversations(ConversationFilter.All)`, restarting on every
selection/send-phase/Other-keystroke edit and re-issuing `list_conversations` each time — flagged as a
SHOULD FIX and fixed in the same rework: the question-agent observation now `combine`s
`questions.observe(serverId, conversationId)` with the shared `conversationAgent` property
[#1110](thread-screen-how-it-works-state.md#the-model-menu-agent-filter-1110) already exposes for the
model-menu filter, seeded with Claude via `.onStart { emit(ConversationAgent.Claude) }` so a cold list still
doesn't hold the batch back. Editing a draft no longer restarts the list subscription or transiently renames
a known Codex conversation back to Claude. The held state keeps this agent alongside the store's own
reconcile rule (§ Batch ownership above): `held?.copy(agent = agent)`.

## Placement: inline in `ThreadScreen`, since #1305

**Superseded.** Through #1305's original plan, `QuestionBatchModal` opened its own `MobileGateModal` dialog
window and was drawn as a `MainActivity` sibling beside `ThreadScreen`, not a parameter threaded into it —
see § Rendering (old shape) below for why that no longer applies. The ticket's AC required the batch to
"appear only in its owning conversation's scrollable stream" so Back, channel switching and history scrolling
stay available without a blocking window, which a `Dialog` cannot do. `ThreadScreen` now takes
`questionState: QuestionModalState?` and `onQuestionEvent: (QuestionModalEvent, Long) -> Unit` parameters;
`MainActivity` passes `vm.questionModal` and `vm::onQuestionEvent` straight through instead of drawing a
sibling. The prompt is rendered as leading `LazyColumn` items (§ Rendering) rather than an overlay, so
placement is no longer "irrelevant because the gate owns its own window" — a future gate-shaped surface
should treat this as the shape questions themselves have moved to, not as a second precedent alongside the
old one.

## Rendering

**The batch is three independent `LazyColumn` items now, not one dialog composable.** `ThreadScreen`
emits, for a held `questionState`, an actions item (`QuestionBatchActions`, key
`"question-actions:${generation}"`), one item per question in original order (`QuestionBlock`, key
`"question:${generation}:$it"`, `testTag("thread-question-row")`), and a title item last
(`QuestionBatchTitle`, key `"question-title:${generation}"`) — under `reverseLayout = true` this source
order draws bottom-up as actions, then questions newest-to-oldest, then the title at the top, exactly
matching a top-down read. Each item is wrapped in the screen's existing `ComposerGutter` padding. Keys use
the generation plus index or role, never prompt text, so a replacement (a new generation) never reuses a
disposed row's identity. None of the three composables keep selection state of their own; every edit
round-trips through `onEvent(event, pending.generation)` back into the VM (§ Batch ownership). The question
content follows the dark Figma questionnaire `347:6697`, question labels `347:6861`, radio rows `347:6476`,
and checkbox rows `347:6771` (inspected 2026-09-30). A tertiary exported glyph sits in a 14 × 16 dp slot
beside the uppercase `labelSmall` header. The `background` card uses a 1 dp `primaryContainer` border and
the 6 dp `modalControl` shape. Question text uses `bodyMedium`; choice labels and descriptions use
`labelMedium`.

**Valid selection does not compose the separate actions row (#1702).** An option can be selected and
`state.canContinue` true while `question-batch-actions` is outside the viewport and absent from semantics.
An interaction test must reveal that stable container before waiting for enabled Continue; waiting first
can time out before ever reaching the scroll. A longer timeout cannot compose an offscreen lazy item.
See [the question-answer live scenario](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

**`QuestionBatchActions` takes a `connected: Boolean` (#1321), ANDed into Cancel's `!state.locked` and
Continue's `state.canContinue` enabled checks.** `ThreadScreen` derives it the same way as the #1319 footer
gate (`connectionState == ConnectionState.Connected`) and passes it alongside `pending` and `dispatch`.
`QuestionBlock`'s own `enabled = !pending.locked` is unchanged — option rows and the Other field stay
editable while the host is down, since only the two sends need the gate. See § Batch ownership above for the
VM-side `promptSendAllowed` check this mirrors.

**A disabled Continue draws as the enabled button, faded (#1484, Figma `636:3535`).** `ButtonDefaults.buttonColors`
sets `disabledContainerColor`/`disabledContentColor` to the same `primary`/`onPrimary` pair as the enabled
colours, and a separate `Modifier.alpha(0.38f)` applies only while disabled. Figma's 38 % is a layer opacity on
the whole button, not a per-colour alpha, so the label composites onto the fill first and the group fades
together; setting alpha on the colours instead would fade the label a second time against the already-dim fill.
The enabled button is unchanged. In the stacked layout (compact width or large text, Figma `636:4325`), the
`Column` holding Cancel and Continue drops `fillMaxWidth()`, so it wraps to the wider Continue and sits at the
`BoxWithConstraints`' start edge instead of spanning the card; `horizontalAlignment = CenterHorizontally` keeps
Cancel centred over Continue. The side-by-side `Row` used above that width threshold is unchanged.

Whole single-choice rows sit in one `selectableGroup()` with `Role.RadioButton`; multiple-choice
rows use `Role.Checkbox` independently. The visible tertiary selectors are 20 dp, with a dot for a
selected radio and the exported check vector for a selected checkbox. Other is part of its choice
row: its `BasicTextField` has the index-only `question_other_<index>` tag. Since
[#1501](../../specs/architecture/1501-prompt-measured-spacing.md), its 48 dp touch target no longer
comes from a `heightIn(min = 48.dp)` wrapper around the inset `modalFieldContainer` well — that
moved layout and left the card 6 dp taller than Figma `636:3279` — but from Compose's pointer
hit-test expansion to `ViewConfiguration.minimumTouchTargetSize`, which takes no layout space; the
field's own layout is the 32 dp well. The Other label and the question line both carry a local
`Trim.None` line-height style (`FrameLineBox` in `QuestionBatchModal.kt`) so each keeps its full
20 dp line box — `AppTypography`'s styles carry no `lineHeightStyle`, so Compose otherwise trims a
single line to its glyphs (about 16 dp for a 14/20 or 20/20 style), 4 dp short of the box Figma
measures from. The radio/checkbox sits top-aligned with the "Other" label (`ChoiceRow` lost its
`other` top-offset parameter); a row's selection target still does not enlarge the separately
focusable field.

**Only the last question's Other field can bring the actions into view (#1484, Figma `636:3803`).** On focus,
and again on every IME height change, `QuestionBlock`'s `reveal` waits one frame (`withFrameNanos {}` — the
effect can start while the lazy item is still being subcomposed inside the list's measure pass, where
`LazyListState.scrollToItem` crashes with "performMeasureAndLayout called during measure layout";
`BringIntoViewRequester.bringIntoView()` alone does not force a remeasure and does not crash there), then calls
`revealActions()`, then `requester.bringIntoView()` for the field itself. `ThreadScreen` passes a real
`revealActions` — which reads `listState.layoutInfo` and only scrolls to the actions item
(`scrollToItem(actionsIndex)`, `1` when the refused-answer notice precedes it, else `0`) when that item is not
already fully inside the viewport — to the **last** question's block only; every earlier question's `QuestionBlock`
gets `NoReveal`, a no-op. This is deliberate, not a simplification left for later: only the last field sits
directly above the actions, so only its reveal can snap them into place without first pushing the tapped field
itself out of view. Giving every field the same `revealActions` regressed exactly that — any earlier question's
Other field snapped the actions to the bottom before `bringIntoView()` animated the field back, so the field the
user had just tapped left the screen for the whole keyboard animation, repeating on every IME inset frame; a
Robolectric probe with the main clock paused showed it 14 frames out of `thread-message-region` before settling.
`QuestionBlock` reads the callback through `rememberUpdatedState`, so a run already in flight when the
refused-answer notice appears or is dismissed — which shifts `actionsIndex` — still targets the current index,
not the one it started with. The last run of the IME-keyed effect sees the settled inset, so the final scroll
position is the same on every run. Closing the keyboard leaves the draft untouched.

All daemon-authored text (header, question, option label, option description) renders through plain `Text`,
length-bounded by the file-private `MAX_QUESTION_TEXT = 8192` constant, with no `maxLines` or link
interpretation — the same "never a key, never interpreted" posture the old `MobileGateModal` shell gave
this content, now enforced directly by these three composables instead of inherited from the shell. The
questionnaire is a 699 dp component example with Previous, not a full-screen mobile question reference:
**no full-screen 412 × 892 question reference exists**. The normal, compact and keyboard captures attached
to #1305 (`app/src/androidTest/assets/question-1305/`) compare the inline layout's component geometry
against the linked Figma states rather than claim a frame match. Since
[#1501](../../specs/architecture/1501-prompt-measured-spacing.md) dropped the Other field's layout-height
touch floor (see above), each question card measures 240 dp against Figma `636:3279`'s 242 — within the
ticket's 2 dp tolerance, not the "slightly taller than Figma" gap #1299's touch-floor tradeoff used to leave.

**Accessibility semantics, restored after a rework.** `QuestionBatchTitle` carries `Modifier.semantics {
heading() }`; the `question-send-failed` failure text carries `liveRegion = LiveRegionMode.Polite` and
`error(...)`. Both were free inside the old `MobileGateModal` shell and had to be re-added explicitly once
the batch moved to plain `LazyColumn` items — a second rework pass found and fixed the gap; a change to
either composable should keep both.

**Known gap, non-blocking: `QuestionBatchActions`' Continue button has no sending indicator.**
`MobileGateModal`'s `ModalSubmitButton(loading = …)` used to show progress while `state.phase` was
`Sending`/`Sent`; the inline `Continue` is a plain `Button` that is only disabled during that window, with
no visual feedback that a send is in flight. The lock itself (`state.locked`, § Batch ownership) is intact —
a double tap still cannot double-send — this is a missing loading affordance only. Flagged SHOULD FIX on
the final verifier pass and left unaddressed as non-blocking; a future touch to this composable should add
the same loading content `Continue` showed inside the gate.

**`QuestionPromptProtection` replaces the dialog window's own `FLAG_SECURE` / obscured-touch guard.** While
`questionState != null`, `ThreadScreen` mounts `QuestionPromptProtection()`, which walks up from
`LocalContext.current` to the hosting `Activity` and acquires shared ownership of the window's
`FLAG_SECURE`, the window **decor view's** `filterTouchesWhenObscured` and the Compose `LocalView`'s own
`filterTouchesWhenObscured`, all through the file-private `QuestionProtectionOwners` singleton (a
reference-counted map keyed by `Window`/`View`, Main-thread only). This protects the surface even for
prompt rows currently scrolled offscreen, since the guard is keyed on "a batch is mounted somewhere", not on
a specific row's visibility. **Shared ownership, not independent per-composition snapshots, is required**
because `PyryNavHost`'s default ~700ms fade transitions can mount a second thread destination's prompt
before the first one disposes: the first acquisition for each key records that key's *original* policy, an
intermediate release (an overlapping owner exiting) leaves the shared policy untouched, and only the last
release restores the recorded original. An earlier design that captured and restored the policy per
composition failed exactly this overlap — found as a MUST FIX in the first verifier pass — because A's
disposal could clear protection while B still displayed a prompt, and B's own disposal could then leave
protection stuck on. The window **decor view**, not just `AndroidComposeView`, is filtered because
`AndroidComposeView` overrides touch dispatch and setting its own flag alone does not reject an obscured
`MotionEvent` — a second finding from the same pass. Protection ends when the last mounted batch disposes;
while active, it also rejects obscured taps on Back and the composer (an explicit Security-review choice,
not an oversight — a fully obscuring overlay should block every control on the surface, not just the
question rows).

## Testing

- `ThreadViewModelQuestionTest` (unit): validation (Continue disabled until every question is answered,
  including a ticked-but-blank Other), single-choice Other clearing the picked option, one answer covering
  every question with verbatim values, a double Continue sending once, Cancel ignored while sending or
  sent, a failed send keeping selections and allowing a retry, dismissal-to-null and replacement both
  discarding selections, a late send completion not touching a newer batch, another conversation's batch
  being ignored, refusal following the same single-send/failure rules, and `answerModal` never being
  invoked. #1305 extended it for generation-scoped events: a stale generation's Continue/Cancel/edit is a
  no-op against a replaced batch, and a cancelled in-flight send restores retryability.
- `QuestionDraftStoreTest` (unit, new in #1305): navigation keeps picks and owners stay isolated by
  `(serverId, conversationId)`; dismissal, replacement and a reconnect all invalidate stale callbacks even
  when the replacement re-sends the same batch id; host observation keeps invalidating a retired source
  without a screen attached, and a reconnect that rebuilds an equal batch needs no null tick in between to
  be treated as a new generation.
- `RelayRepositoryCoordinatorTest` (unit) gained `submitQuestionBatch` coverage: it rejects a retired source
  even when a newer source holds an equal request (closing the reconnect-redirect race, § Batch ownership),
  and a fresh submission through the live source still works.
- `RelayConnectionFactoryTest` (unit) extended
  `destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect` to the real Koin
  `QuestionDraftStore` binding: a separately paused draft-store scheduler still rejects old-generation
  Continue/Cancel after an equal request is rebuilt, and routes fresh answers/refusals to the exact owning
  host.
- `ThreadInlineQuestionTest` (shared Robolectric, new in #1305, `app/src/sharedTest/.../thread/`): an empty
  thread with a pending batch has no history-demand advance and keeps Back/composer active
  (`empty_thread_has_inline_questions_active_back_and_composer_without_history_demand`); a reader at the
  newest end of a populated thread is scrolled to see a newly arrived batch, while the existing
  history-reader anchor is unchanged (`arrival_reveals_the_batch_to_a_reader_at_the_newest_end` — added in
  the ticket's second rework after the verifier found the prompt landing off-screen below the anchored
  newest row); the title has `heading()` and the failure text has `liveRegion = Polite` + `error(...)`
  (`failure_feedback_is_announced_and_the_title_is_a_heading`); and a history-scrolled reader is preserved
  across batch arrival and edits, with prompt rows never advancing the oldest-end history demand
  (`arrival_and_edits_preserve_a_history_reader_and_prompt_rows_do_not_advance_history_demand`). See
  [Thread screen § list and status
  row](thread-screen-how-it-works-list-and-status-row.md#inline-question-rows-and-the-newest-end-reveal-1305)
  for the production side of each. #1484 added three more: `focusing_other_reveals_the_field_and_both_actions`
  (the last question's Other field, the field and both actions land inside `thread-message-region`),
  `focusing_an_earlier_other_keeps_the_field_in_view_on_every_frame` (main clock paused, `question_other_0` in a
  two-question batch, asserted inside the region on each of 30 stepped frames — the regression test for the
  everyone-reveals mistake above), and `stacked_actions_sit_at_the_start_with_cancel_centred_over_continue`
  (font scale 1.5 at 320 dp: Continue's left edge equals the actions column's left edge, Cancel's centre equals
  Continue's centre). #1702 adds
  `selected_option_can_reach_continue_when_the_actions_row_is_uncomposed`: a 320 × 500 dp viewport with a
  tall single-question block proves the option is selected and `canContinue` true while the actions tag
  is absent, then reveals the container, asserts displayed/enabled Continue and dispatches exactly one
  Continue event with the held generation. The old wait-before-scroll ordering reproduced a
  `ComposeTimeoutException`; this diagnoses the Continue semantics wait, distinct from the original
  #1637 `TimeoutCancellationException` in a coroutine. Historical timeout reports alone do not establish
  the viewport mechanism; the controlled regression does.
- `QuestionBatchModalTest` (androidTest — the file and class names predate #1305's move out of the dialog,
  § Where it lives) now mounts the real inline `ThreadScreen` host over a fake batch flow and recording send
  lambdas, not a standalone `QuestionBatchModal` call: radio semantics and Other clearing on single choice,
  checkbox multi-pick plus Other text with the correct sent payload, a failed refusal's error message and
  kept selections, and reachability at 320×640 dp with the keyboard shown, plus selector/glyph bounds, a
  near-edge tap in the Other field's own focus region, retained draft after choice changes, inert long text
  at 1.5× font scale, and last-field/footer reachability with a visible IME at compact width (from #1299).
  #1305's rework also adds overlapping-protection coverage releasing two `QuestionPromptProtection` owners
  in both orders, asserting secure capture and actual obscured-`MotionEvent` rejection stay active until the
  last release (with a positive ordinary-tap control proving an unhandled event isn't mistaken for
  rejection), and confirming the final release restores the original policy, including one starting from an
  already-protected window. The fresh focused device run recorded 11 executed tests, 0 failures/errors/skips
  (`app/src/androidTest/assets/question-1305/rework-api33-focused.xml`).
- **Earlier Other, real IME:**
  `QuestionBatchModalTest#ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen`
  requires initial host-window focus, an earlier Other field wholly obscured by the header,
  a focused retained draft, positive real IME inset, and field clearance between header and
  composer through keyboard open/dismiss/reopen. Its initial focus timeout reproduced before
  scroll/IME assertions on the #1615 merge base; that failure did not establish a rendering
  regression. With the shipped `E2eInstrumentationRunner.quietSystem` mitigation at `186c399b`,
  the unchanged method passed a separate focused `pixel2Api33Atd` run at `de584470`:
  **1 executed, 1 passed, 0 failed, 0 errors, 0 skipped**, exit 0, one shard and disabled animations
  ([XML](../../../app/src/androidTest/assets/focus-1797/focused-api33.xml),
  [command/revision record](../../../app/src/androidTest/assets/focus-1797/focused-run.json)).
  The required forced sweep subsequently ran with
  `UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
  at `b608f49bc1177ec64477c02428ca20337989ee5b`, exit 0: **183 executed, 183 passed,
  0 failed, 0 errors, 1 skipped**. Fresh device and dispatcher XML in
  `build/dispatcher-tests/ui-k8ql88ff/` confirm this method passed in 2.715 s with no
  failure/error/skipped child. This fulfills the builder plan/PR's pending sweep handoff;
  see the [operator record](https://github.com/pyrycode/pyrycode-mobile/pull/1800#issuecomment-5993306258).
  After newer main was merged, the sweep at `22d2bb2faf24c17e9cc3ff197f2617952d1f549d`
  corroborated it: **184 executed, 184 passed, 0 failed, 0 errors, 1 skipped**, exit 0;
  the method passed in 1.811 s. Both sweeps skipped only
  `RenameDialogCaptureTest#renameAtFigmaViewport`. The test and its harness were unchanged
  between these revisions. The original `b4875016` sweep and the current-head sweep each
  recorded a native emulator Bluetooth SIGABRT during this passing method, with no Pyrycode
  app crash, ANR or focus-failure markers found; the forced run had no such crash markers.
  Credit the mitigation for permitting these assertions to complete, without claiming
  Bluetooth crashes were eliminated or universal focus reliability. See
  [verification evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence)
  for the counted XML and retained command/revision records, and the
  [final verifier record](https://github.com/pyrycode/pyrycode-mobile/pull/1800#issuecomment-5994141618).
- **`QuestionBatchModalCaptureTest`** (androidTest): adapted from the dialog's own secure-window bitmap
  capture to the activity view with `FLAG_SECURE` still set — normal 412×892, compact 320×700 at 150% text,
  and a real keyboard open. Static fixture text only; captures at
  `app/src/androidTest/assets/question-1305/question-{normal,compact,keyboard}.png`, attached to #1305 for
  #1220's application-wide comparison.
- **`PromptsDesignCaptureTest`** (androidTest, the #1433 application-wide prompts audit,
  `app/src/androidTest/assets/design-1220/prompts/`): `questionFrames`, `questionKeyboardFrame` and
  `questionCompactFrame` cover the three `636:*` frames this ticket fixed. `questionKeyboardFrame` asserts,
  before any further scroll, that the focused Other field and both actions are displayed and lie inside
  `thread-message-region` — not under the composer — which is the device-only proof for the keyboard reveal
  above, since the colour and stacked-layout fixes are judged by pixel comparison instead. #1484's re-capture
  reported 6 executed, 0 failed, 0 skipped on `pixel8Api35`; the `636:3279`, `636:3803` and `636:4066` items in
  `index.md` are updated for these three aspects only.
- **Live, rung-3:** `InteractiveStreamE2ETest.interactiveTurn_questionAnswer_reachesTheAskingConversation`
  (#966) still proves the phone's answer reaches the asking claude and a peer answer removes the pending
  batch with no phone tap; #1305 adapted its selectors to the inline surface (scrolling to
  `question-batch-title` / `question-batch-actions` tags instead of matching a dialog's title text — see
  [Real-claude e2e coverage](../../e2e-interactive-stream.md)). The full rung-3 live suite at `7fba6c4b`
  merged with `origin/main` `207ff366` reported **44 executed, 43 passed, 1 failed, 0 skipped**, with this
  method executed and passed. The one failure,
  `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`, was an inherited, unrelated regression
  (also failed on `origin/main` alone, traced to daemon #2699) and was isolated in the same branch
  (`@Ignore`, removed from the LIVE list, `LIVE_MINIMUM` lowered to 43) with no production change. #1351
  fixed the underlying cause — the live `message` arm now keeps a held row's attachments instead of
  replacing them — and #1369 reverted the isolation; `android-test-gate.py`'s floor is counted from the
  curated list since 2026-10-01, so only the `@Ignore` and the LIVE-list entry needed restoring. The
  dispatcher's full live gate at `6e79da4f` merged with `origin/main` `b6c06182` reported **50 executed,
  50 passed, 0 failed, 0 skipped**, with the restored method passing. See the `LIVE_MINIMUM` history in
  [Real-claude e2e coverage](../../e2e-interactive-stream.md) for the full chain.

## Related

- [Shared mobile modal](mobile-modal.md) — the `MobileGateModal` shell this surface was drawn in before
  #1305, and the submit/sending/error extension #1305's `Continue` still approximates without its loading
  indicator (§ Rendering).
- [1501 architecture doc](../../specs/architecture/1501-prompt-measured-spacing.md) — the Other row's
  measured-spacing fix against `636:3279`, the shared `Trim.None` line-box cause, and the compact-frame
  platform-scaling branch.
- [Permission-modal overlay](permission-modal-overlay.md) — the sibling gate consumer this ticket's
  ViewModel/render split and lock/send idiom mirrors; still dialog-presented, not moved inline by #1305 —
  see that ticket's scope note and #1220 (kept in Inbox) for the application-wide comparison.
- [Modal answer flow](modal-answer-flow.md) — the `answerModal`/`cancelModal` precedent this ticket's
  `answerQuestionBatch`/`refuseQuestionBatch` passthrough follows, and the VM this modal never calls into.
- [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md) —
  `ComposerDraftStore`, the process-lifetime, app-scoped precedent `QuestionDraftStore` follows.
- [Thread screen](thread-screen.md) — the screen this batch now renders inside; see § Placement above and
  [Thread screen § Destination block](thread-screen.md#destination-block) for the current wiring.
- [Thread screen — list and status
  row](thread-screen-how-it-works-list-and-status-row.md#inline-question-rows-and-the-newest-end-reveal-1305)
  — the prompt-row prefix in the oldest-end history predicate, the newest-end reveal effect, and the
  composer-status "Waiting for answers" reading.
- [Real-claude e2e coverage](../../e2e-interactive-stream.md) — the adapted rung-3 scenario and the
  `LIVE_MINIMUM` / curated-list history, including #1305's temporary exclusion of an unrelated method.
