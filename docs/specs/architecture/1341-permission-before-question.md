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

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. No new inbound data path: the daemon-authored question and permission text reach Compose through the existing inline rows from #1305 and #1306, rendered as text. This ticket only chooses which of two already-decoded `ModalUiState.Open` / `QuestionModalState` values is composed, through `shownQuestion` in `ThreadScreen`.
- [Android attack surface] No findings, by design decision. `QuestionPromptProtection` (secure window plus the obscured-touch filter on the decor view and the compose view) keeps reading `questionState != null || openRequest != null` at its single call site, not `shownQuestion`, so the protection stays mounted without a gap while the question is withheld and across the permission → question hand-over. Moving it to `shownQuestion` would have dropped protection for the frame between the request resolving and the question returning.
- [UI-side leakage] No findings. While withheld, the question's Other text field leaves composition, so a third-party keyboard loses its input connection to that field; the text stays in the hoisted `QuestionSelection`, which only the app holds.
- [Concurrency] No findings. No new coroutine or flow. A withheld question composes no `dispatch` lambda, so no `QuestionModalEvent` carrying its generation can be sent while the permission request is open; `onQuestionEvent` still guards by generation when it returns.
- [Threat model: hostile daemon] OUT OF SCOPE (accepted). A daemon that holds a permission request open indefinitely withholds the question indefinitely. The daemon already decides when either prompt exists, so this grants it nothing new; it matches desktop's `ComposerSlot`.
- [Tokens, files and storage, cryptography, network and I/O, logs] Not applicable: the change touches no secret, file, crypto primitive, socket or log call; it is a composition choice inside `ThreadScreen`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

- 2026-10-01: Appended `## Security review` for the `security-sensitive` label, which the first build left out. The design and code do not change; the review confirms that `QuestionPromptProtection` keeps reading `questionState` on purpose.
