# Thread Rename on the shared mobile modal (#1278)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt` → `RenameDialogInternal` owns the prefilled selection, focus, validation, and callback contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogTest.kt` → current Compose coverage for copy, validation, focus, and dismissal.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` supplies the dark editing shell, Close/Cancel/Back routing, footer actions, scrolling, and IME avoidance.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `LabelledField` and `FieldWell` establish the filled Input large geometry and theme roles used by current forms.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `HostNameField` is another labelled field implementation and demonstrates the theme roles.
- `docs/knowledge/features/mobile-modal.md` and `docs/knowledge/features/mobile-modal-callers.md` → shell contract, compact layout, focus placement, and field color lessons.
- `docs/knowledge/features/development-verification.md` → device capture and 412 × 892 viewport evidence procedure.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=489-1942 and Input large https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6446 (inspected 2026-09-29).

`489:1942` shows a navy modal with a title/close row, thin divider, centered outlined and filled actions, and a labelled filled field in the content. `347:6446` specifies an 8 dp label gap and a rounded 52 dp well with 16 dp leading and 56 dp trailing text insets; its optional send icon does not apply to Rename. The Mobile page has no dedicated Rename composition, so the shared components guide the presentation without claiming a whole-dialog match.

## Context

The thread's reachable Rename action still opens `AlertDialog`. The shared `MobileModal` already solves the requested shell, actions, compact height, and keyboard behavior. Keep Rename's existing copy and callback contract.

## Design

`RenameDialogInternal` will render `MobileModal` with `Rename`, `Cancel`, and `Save`. Its content will contain one labelled `BasicTextField` with the shared Input large geometry and modal field color roles. The prefilled `TextFieldValue` selects its text on open; focus is requested inside the dialog composition. Both Save and IME Done call the same guarded submit function, which sends only a changed, nonblank trimmed name. Close, Cancel, and Back use `onDismiss`. The caller still removes the dialog after a callback; no ViewModel or repository contract changes.

## State and concurrency model

The edit buffer remains local `remember` state in `RenameDialogInternal`. No jobs or flows are added. The focus effect is composition-bound and ends with the dialog. `MobileModal` owns window insets and scrolling.

## Error handling

Blank or unchanged input disables Save and makes IME Done inert. Rename write failures remain the thread caller's existing responsibility; this presentation component performs no I/O.

## Testing strategy

- Extend `RenameDialogTest` under `sharedTest`: selection, IME Done guard and trimmed submit, Close and Back dismissal, compact width, enlarged text, and keyboard accessibility. Run its focused Robolectric class, with a focused managed-device tiebreaker if Robolectric disagrees on IME behavior.
- Add a focused `androidTest` capture at a 412 × 892 logical viewport for actual emulator pixels. Commit the capture and current Figma component renders with a labelled comparison image under `app/src/androidTest/assets/rename-1278/`; run the capture method and inspect executed XML.
- Run focused existing modal and thread coverage, lint, debug assembly, and androidTest compilation. This local form presentation change does not add a new operator-to-daemon flow, so existing rename e2e coverage remains the live path.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/mobile-modal-callers.md` under **Callers** to describe thread Rename's shared shell and labelled field. No shared knowledge document is edited in this builder stage.

## Open questions

- Check whether the shared field well can be reused directly; if its helpers remain private, mirror its geometry locally without widening the shared component API.
- Confirm actual emulator capture can be produced in the managed device test run and retain its pixel file as an allowed test asset.

## Revisions

- `LabelledField` and `FieldWell` are private to `ChannelFormFields`, so `RenameDialogInternal` mirrors their single-field geometry locally. This keeps the shared form API unchanged.
- The managed API 33 ATD image rendered the dialog but returned a blank framebuffer. `RenameDialogCaptureTest` skips that capture-only check on a blank framebuffer; the full Pixel 8 API 35 run produced the actual 412 × 892 PNG and a passing, unskipped XML report under `app/src/androidTest/assets/rename-1278/`.
