# #1957 — Retain multiline prompt well height

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt`: `ChannelFormFields`, `FieldWell` and the full-line-box style own text measurement and well padding.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/ChannelFormFieldsTest.kt`: existing native-font geometry, editing, validation and enlarged-text coverage.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: `promptFailedFramesAt412By892` retains both failed forms with the keyboard closed; add prompt geometry assertions here.
- `app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt`: `CreateChannelModal` retains typed values across failures.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt`: `SaveAsChannelDialog` retains verbatim prompts and locks confirmed names.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`: `AppTypography.bodyMedium` supplies the 20sp text line box.
- `docs/knowledge/features/save-as-channel-dialog.md`: preserve focus inside the dialog, UTF-8 validation and verbatim editing.
- `docs/knowledge/features/channel-list-screen.md`: Create shares the modal/form contract.
- `docs/knowledge/features/development-verification-gates.md`: native fonts and forced content size avoid Robolectric's editable-dialog width limitation.
- `app/src/androidTest/assets/design-1220/list/1737-evidence.txt`: the measured 20dp prompt-height shortfall is independent of #1958's error line box.

## Design source

**Figma:** [Create channel / Prompt failed](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=784-7095), [Save as channel / Prompt failed](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=784-7134), inspected 2026-10-08 through design context and screenshots.

Both dark frames show the retained release-notes prompt on two lines inside a 132dp filled well. The existing modal field palette, bodyMedium text and rounded well remain; the design combines 16dp above the full text line box with 76dp below it (64dp inner plus 12dp outer padding).

## Change

Replace the prompt's four-line minimum with its actual drawn line box and explicit 76dp bottom padding in `FieldWell`; preserve the 112dp well minimum. Use untrimmed bodyMedium line boxes for the prompt, so two 20dp lines produce 132dp and each further drawn line grows the well by its text height, including wrapping and explicit/trailing newlines. Empty and one-line prompts remain 112dp. Keep name geometry, values, callbacks, focus, validation, callers and the separate error-line correction unchanged. The existing exported `PROMPT_MIN_LINES` and `PromptWellHeight` remain available to Channel info. No in-flight branch overlaps the planned files. Expected written work is about 150 lines across one production file, two test files and this plan; no new exported types, consumer updates or reject branches.

## Testing strategy

Add native-font shared tests at a forced 356dp form width (the 412dp viewport minus modal insets) for wrapping, newlines, trailing blank lines and shrinking to empty/one-line content; inspect actual `TextLayoutResult` and prove the 76dp bottom gap independently. Use pointer taps inside the grown blank well to check prompt focus. Add 132dp/two-line/bottom-gap assertions to the existing device-only capture of both failed forms; real dialog width, IME dismissal and hardware screenshots require that existing device test. Watch these assertions fail before implementation, then rerun them green. Run existing Create, Edit, Save as, palette and shared form tests, the focused prompt-failed capture on the full device image, lint, assembleDebug, Android-test compilation and forced Spotless. Compare fresh captures with the inspected Figma screenshots, leaving #1958's error geometry to its own ticket. Existing real-Claude Create/Save scenarios cover these unchanged actions; this correction adds no new operator flow. After the final main merge and push, run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/save-as-channel-dialog.md` § `ChannelFormFields` to describe actual prompt line boxes plus 76dp blank space instead of the form's four-line minimum. Channel info's separate four-line minimum remains unchanged.
