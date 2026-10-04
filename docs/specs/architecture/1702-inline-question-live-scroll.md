# #1702: reach inline question actions before waiting for Continue

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_questionAnswer_reachesTheAskingConversation`, `awaitInlineQuestion`, and `awaitNoInlineQuestion` establish the existing round-trip and lazy-row selectors.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInlineQuestionTest.kt`: selection and offscreen action coverage provide the regression fixture.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the question's actions, block and title are separate reversed lazy-list items.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt`: `QuestionBatchActions` tags the action container independently of Continue's enabled state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt`: `canContinue` requires a selected value and an unlocked phase.
- `docs/knowledge/features/question-batch-modal.md`: Placement and Rendering explain why absence from semantics does not mean the batch is dismissed.
- `docs/e2e-interactive-stream.md`: the question-answer scenario requires both phone and peer round-trips.
- `docs/knowledge/features/development-verification-gates.md`: shared screen tests run on Robolectric and the device; real host/relay scenarios remain device-only.

## Change

After selecting the option, scroll to the stable `question-batch-actions` container before waiting for enabled Continue. Keep the existing timeout, enabled matcher, click, remote/answered dismissal, chosen-label inclusion and other-label exclusion, and peer-driven second-turn dismissal and completion. The latest #1691 branch/base stderr reports identify the Continue Compose wait; the original #1637 branch coroutine timeout is a different failure and is not evidence for this repair. A short-viewport regression will distinguish valid selection state from an uncomposed action row. Production code and visual geometry stay unchanged.

In-flight overlaps: #1631, #1642, #1646, #1689, #1690, #1691, #1693, #1694 and #1695 touch these test files; their edits concern other methods or focus coverage, so this local additive change has no dependency.

Sizing: one interaction repair with supporting regression, approximately 120 written lines, no exported types, no signature changes or consumer migrations, three acceptance criteria, and no new failure branches. This fits the builder boundaries.

## Testing strategy

Add a shared screen regression beside the existing offscreen-action coverage: a short viewport and tall single-question block, controlled selection state, actions absent from semantics after selection, then action-container reveal, enabled Continue and a generation-scoped Continue event. First run the old wait-before-scroll ordering and confirm a Compose timeout despite `canContinue`; then use the repaired ordering and run `ThreadInlineQuestionTest` on Robolectric plus the focused new method on the managed device. Compile androidTest, run lint, assembleDebug, format and force spotlessCheck.

The existing live scenario needs a real daemon, relay and Claude and therefore stays device-only. Builder does not run the credentialed live suite. Request `all` under the PR's Live tests so the dispatcher runs the full suite, as required by the ticket. Acceptance remains pending until fresh XML confirms the named method passed and records executed, failed and skipped counts; a focused result or exit code alone is insufficient.
