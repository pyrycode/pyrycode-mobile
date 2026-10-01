# #1349 — Send the Other answer trimmed, as desktop does

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt` → `QuestionSelection.values` — the one place the Other text becomes an answer value.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelQuestionTest.kt` → `continue_is_enabled_only_when_every_question_has_an_answer` — currently asserts `" Web "` is sent untrimmed (#1305's "verbatim Other"); its expectation flips to `"Web"`.
- `docs/specs/architecture/1305-inline-questions.md` — the "verbatim Other" contract this ticket refines to "trimmed, as desktop does".

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=636-3279 — inline question. Visuals do not change; the fix is in the answer value only.

## Change

`QuestionSelection.values` adds `otherText.trim()` instead of `otherText`, still only when Other is ticked and the trimmed text is not empty — desktop's `resolveQuestionAnswers` in `questionResolution.ts`. The held draft (`otherText`) stays untrimmed, so the text field and the draft store keep what the operator typed; only the sent value is trimmed. The KDoc on `values` changes from "verbatim" to "trimmed". Nothing else moves.

## Testing strategy

- New `QuestionModalStateTest` (unit): Other ticked with `"  yes  "` → `values` is `["yes"]`; whitespace-only Other text → no value and `answers()` is null.
- Update the expectation in `ThreadViewModelQuestionTest.continue_is_enabled_only_when_every_question_has_an_answer` from `" Web "` to `"Web"`.
- `QuestionDraftStoreTest` and `ThreadInlineQuestionTest` assert the untrimmed draft and stay green unchanged.

## Documentation handoff

Pending for the documentation stage: the questions feature overview's "verbatim Other" wording, if any, becomes "trimmed, as desktop does".
