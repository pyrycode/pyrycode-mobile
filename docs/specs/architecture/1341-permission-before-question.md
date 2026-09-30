# #1341 — Show a chat's permission prompt before its question

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — `openRequest` and `questionState` both feed the inline prompt rows, the prompt row count, the empty-state guard, the prompt reveal effect and the status band's `waitingForAnswers`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt` → `QuestionModalState`, `QuestionSelection` — picks and Other text live in the hoisted state (kept by #1305's `QuestionDraftStore`), not in composition, so leaving the question out of composition loses nothing.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInlineQuestionTest.kt` — the screen-test class the new case joins.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt` → `openModal` — the `ModalUiState.Open` fixture shape.

In-flight overlap: #1309 and #1359 also edit `ThreadScreen.kt`, in unrelated blocks (footer status click, banner row). Build through.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=636-3279

Visuals do not change: the inline permission request and the inline question render exactly as #1306 and #1305 drew them; this ticket only decides which of the two is composed.

## Change

In `ThreadScreen`, derive `shownQuestion = questionState.takeIf { openRequest == null }` beside `openRequest`, and read it wherever the screen draws or measures the inline question: the question's lazy items, `promptRowCount`, the empty-state guard, the `promptIdentity` reveal key, and the status band's `waitingForAnswers` (the "Waiting for answers" reading would otherwise point at a question that is not on screen). `QuestionPromptProtection` and `promptPresent` keep reading `questionState` — both already hold whenever either prompt exists, so nothing moves there. Mirrors desktop's `ComposerSlot`, which hides `QuestionPanelSlot` while `selectHasOutstandingFor` is true. When the prompt resolves, `shownQuestion` becomes `questionState` again with the hoisted selections, and the `promptIdentity` change reveals it to a reader at the newest end.

## Testing strategy

New case in `ThreadInlineQuestionTest`: compose with a question whose selection has a ticked option and Other text, plus an `Open` permission request; assert the request shows and the question title/row and "Waiting for answers" do not; flip the modal to `Hidden`; assert the question shows with its option selected and its Other text intact. Existing `ThreadInlineQuestionTest` and `ThreadScreenModalTest` cover each prompt alone.

## Documentation handoff

None pending — the ticket names no documentation requirement.
