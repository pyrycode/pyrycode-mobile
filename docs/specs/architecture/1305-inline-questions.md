# Inline conversation questions (#1305)

## Files read

- `ui/conversations/thread/QuestionBatchModal.kt` → `QuestionBatchModal`, `QuestionBlock`, `ChoiceRow`: reuse inert text, indexed controls and touch floors.
- `ui/conversations/thread/QuestionModalState.kt` → `QuestionModalState`, `QuestionSelection`: preserve answer order, validation and verbatim Other.
- `ui/conversations/thread/ThreadViewModel.kt` → `onQuestionEvent`, `sendQuestion`, `heldQuestionBatch`: current destination-owned draft and guarded sends.
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: reverse layout, stable row keys, streaming pin and oldest-history demand.
- `ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore`: process-lifetime ownership precedent.
- `di/AppModule.kt` → `ThreadDestinationFactory.thread`: bind the owning host's coordinator and sends.
- `MainActivity.kt` → thread destination: remove sibling dialog and forward question state/events.
- `data/repository/RelayRepositoryCoordinator.kt` → `currentRepository`, `questionBatches`: eager host state and connection replacement.
- `ui/components/MobileModal.kt` → `MobileGateModal`: secure window and obscured-touch protection to preserve on the activity surface.
- `docs/knowledge/features/question-batch-modal.md` § Batch ownership: retain Sending/Sent lock, invalidate reconnects, avoid permission sends.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`: history indicator must never contribute to demand count.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes, Compose evidence: shared tests versus real IME/pixel tests.
- `../pyrycode/docs/protocol-mobile.md` → Question (v2): existing contract remains authoritative; no wire changes.

Production paths above are relative to `app/src/main/java/de/pyryco/mobile/`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=636-3279

Read context and screenshots for selected `636-3540`, keyboard `636-3803`, compact `636-4066`, navigation `640-2437`, and behavior `640-2838` as well. The stream contains a medium title, tertiary glyph/uppercase headers, background cards with primary-container borders and wrapping M3 question/choice text. Cancel and Continue follow all questions with a 20dp gap; compact large text stacks them. Existing chat gutters, atmospheric background, Back and composer remain available. Existing exported question glyph/check vectors match these assets.

## Context and scope

Questions currently block navigation in their own dialog and lose picks when their destination is popped. One deliverable moves questions into their owning chat with process-lifetime drafts. Permission presentation and #1220's application comparison remain separate.

Size: approximately 1050–1350 written lines, seven production files, one new exported store type, two production consumer updates, five AC, three send-error classifications. No mandatory signature cascade: added dependencies/parameters have inert defaults for existing test and preview callers. Codegraph returned incomplete composable callers; source inspection found MainActivity and previews. All six boundaries hold. Overlap with #1283 is limited to separate status-area edits in `ThreadScreen`; no real dependency.

## Design

`QuestionDraftStore` owns a map by server/conversation, each entry holding one batch, its selections/send phase and a monotonically allocated in-process generation. The generation changes on any replacement, null/dismissal or source reconnect, even if the same batch ID and payload return. It is never saved or persisted. An app-owned collector follows the bound host's repository identity and batch list, continuing after destination exit; reconnect clears before observing the new repository. Rebinding a replaced coordinator cancels its old collector and clears its host. Koin closes the store and its scope.

`ThreadViewModel` observes the store for its fixed route owner and derives the agent as before. Default standalone tests retain the injected batch flow through a local store. Selection updates and phases use compare-generation operations. The route passes events with the displayed generation; stale composition callbacks cannot edit or submit a replacement. Send work locks synchronously and checks generation again before invoking the handler and after completion. Destination cancellation restores retryability for a matching in-flight request without reviving invalidated state.

The inline composable reuses `QuestionBlock`/`ChoiceRow`. Split the batch into lazy items: title, each question in original order, then error/actions. With reverse layout emit actions first, questions reversed, title last, before delivered rows. This permits scrolling a tall batch and focused fields. Keys use generation plus index, never prompt text. The empty-thread branch yields when a question exists. Count prompt items as a fixed prefix in the oldest-history predicate, require actual history rows, and continue excluding the loading/retry indicator. Question arrival and edits are excluded from message auto-follow; disable the streaming bottom pin while questions are present so field sizing cannot drag a history reader. Existing message/queued-row behavior otherwise stays intact.

Actions use M3 buttons with theme shapes/colors and at least 48dp touch height, centered horizontally at normal size and stacked at narrow large-text width. Other stays editable independently of its selection toggle, with an explicit bring-into-view request on focus and IME height changes. Text remains plain and length bounded on this rendering surface; answers continue using the original label and Other values.

While a question batch is rendered, `ThreadScreen` installs FLAG_SECURE and obscured-touch filtering on its activity window/view and restores previous settings on disposal. This protection is active even for offscreen prompt rows and ends on navigation/dismissal. No change to permission window selectors or guards.

## State and concurrency

Store mutations are synchronized; its injected dispatcher and SupervisorJob own host collectors, cancelled by rebind/dispose. Repository replacement is a source epoch, not just equality of batches. ViewModel observation and outbound work are viewModelScope jobs on Main. No disk, SavedStateHandle or rememberSaveable holds picks. StateFlow emits immutable snapshots; no lock spans suspension. Background connection close invalidates drafts through repository loss.

## Error handling

Existing typed RelayErrorException/IllegalStateException/IllegalArgumentException handling maps sends to Failed, retains picks and enables retry; cancellation propagates. Static debug lifecycle/send logs contain no identifiers, text, labels, values or exception messages. No new I/O or protocol failure path.

## Testing strategy

- RED first: shared inline screen tests establish no dialog, empty-thread placement, active Back/composer, old-message anchoring across arrival/edit and oldest-history demand. Unit tests establish navigation retention, host/conversation isolation, replacement/dismissal while away, same-ID reconnect invalidation and stale generation callbacks/completions.
- Retain existing ThreadViewModelQuestionTest validation, multi-select, verbatim text, failure/retry and permission-handler isolation coverage.
- Adapt existing device question tests to the real ThreadScreen host, including pointer focus/selection boundaries, compact stacking and real IME open/close. Device-only reason: actual input method and WindowInsets. Run affected history/newest-row shared classes and focused question device class.
- Adapt static fixture capture to the activity view with FLAG_SECURE retained, normal 412×892, compact 320×700 at 150%, and real keyboard; save screenshots and focused result evidence under `app/src/androidTest/assets/question-1305/`. Capture uses static fixture text only. Attach evidence links to the ticket via PR handoff.
- Adapt the existing rung-3 `InteractiveStreamE2ETest.interactiveTurn_questionAnswer_reachesTheAskingConversation` selectors to scrollable inline controls, preserving phone-answer Claude proof and peer-dismissal proof. Dispatcher owns its fresh full live run; no focused real-Claude run. Report method execution plus executed/failed/skipped counts after that gate. No new protocol scenario or fixture producer is required.
- Focused unit/shared tests, lint, assembleDebug, androidTest compilation, formatting checks. Full UI/scripted/live gates remain dispatcher work.

## Open questions

None. The compact action breakpoint will be measured against the reference and pointer tests rather than fixed label widths.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/question-batch-modal.md` (Batch ownership, Placement, Rendering, Testing) and thread topics (`thread-screen-how-it-works-list-and-status-row.md`, `thread-screen.md`) to describe inline placement, navigation draft lifetime and invalidation, and record the named live result. Keep #1220 in Inbox for the full application comparison.

## Security review

**Verdict:** PASS

- Trust boundaries: `QuestionBlock` renders daemon text only as length-bounded plain Text; indices/generation identify rows. Wire labels remain unchanged for answers.
- Tokens: no new credentials or token storage; generation is a process-local identity, not authentication.
- File/storage: drafts remain heap-only; static capture files contain fixture content, never live prompts.
- Android surface: move the dialog's FLAG_SECURE/filterTouchesWhenObscured protection to the activity while the batch is present; restore prior policy on exit. No new exported component or WebView.
- Crypto: inherits Noise transport without changes to keys, nonces or primitives.
- Network/I/O: existing coordinator and conversation-scoped sends; reconnect epoch invalidates rather than revives picks. No frame/URL/timeout changes.
- Errors/logs: static phase/error codes only; original generic failure feedback, no exception strings or prompt content.
- Concurrency: generation checks cover stale edit, pre-send and completion; synchronous lock prevents duplicate submits. Host collectors outlive destinations and are explicitly cancelled on rebind/dispose.
- Threat alignment: hostile prompt text remains inert; capture and overlay guards preserved. Existing transport defenses handle relay threats. Accessibility services/third-party IMEs retain the existing Android trust boundary; this ticket adds no secret-entry field or credential exposure.

**Reviewer:** builder self-review using `builder/security-review.md`. **Date:** 2026-09-30.
