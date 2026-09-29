# Rename dialog

[`RenameDialog`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt) is the thread overflow menu's Rename form for either conversation tier. [`ThreadScreen`](thread-screen.md) opens it with `state.displayName`; `ThreadViewModel` owns visibility and forwards a submitted name to `ConversationRepository.rename`.

## Presentation

The dialog uses [`MobileModal`](mobile-modal.md) for the dark shell, title and close row, Cancel/Save footer, scrolling, and keyboard avoidance. It keeps the copy **Rename**, **Name**, **Cancel**, and **Save**. Its one `BasicTextField` has the shared Input large treatment: an 8 dp label gap, a minimum 52 dp filled well, 16 dp leading and 56 dp trailing insets, modal field color roles, and the shared `modalControl` shape. The form mirrors the geometry locally because `LabelledField` and `FieldWell` are private to `ChannelFormFields`; it does not widen that component API.

The design comparison uses Figma Modal `489:1942` and Input large `347:6446`, inspected 2026-09-29. The Mobile page has no dedicated Rename composition, so these are component references rather than a whole-dialog reference. See the [412 × 892 emulator capture and labelled comparison](../../../app/src/androidTest/assets/rename-1278/labelled-component-comparison.png).

## Editing contract

The public composable takes `initialName`, `onSubmit`, and `onDismiss`. A local `TextFieldValue` selects the entire initial name on open. The focus effect lives inside the dialog content beside its field: requesting focus in the parent composition can run before the dialog window's field exists, leaving no focused node even though `requestFocus()` returns cleanly.

Save is enabled only when the trimmed text is nonblank and differs from `initialName`. Save and IME Done share the same guarded submit function, which passes the trimmed name to `onSubmit`; invalid Done is inert. Cancel, Close, and Back route to `onDismiss` without submitting. The caller removes the dialog after either callback. Edits are composition-local and are lost when the dialog is removed; repository errors remain the thread caller's responsibility.

## Verification

[`RenameDialogTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogTest.kt) covers selection, copy, validation, trimmed Save and Done, dismissal, compact 320 × 640 dp width, and enlarged text. [`MobileModalTest`](../../../app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt) checks the Rename field and footer with a visibly open IME at that compact size, including pointer submission. [`RenameDialogCaptureTest`](../../../app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogCaptureTest.kt) produced the [Pixel 8 capture](../../../app/src/androidTest/assets/rename-1278/emulator-rename-412x892.png); its [focused XML](../../../app/src/androidTest/assets/rename-1278/device-results.xml) records one executed, zero failed, zero skipped. The API 33 ATD image returned a blank framebuffer for capture despite rendering semantics, so use the Pixel 8 image for pixel comparisons. The thread's existing `InteractiveStreamE2ETest.interactiveTurn_renameConversation_relabelsTopBarAndListRow` covers the live rename path.

The field starts prefilled, so tests use `performTextReplacement` when asserting replacement. `performTextInput` can append to the existing value and make a changed-name assertion pass for the wrong reason.
