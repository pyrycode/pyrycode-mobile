# 1233 — Shared channel form fields

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `ChannelFormFields`, `LabelledField`, `wellColors`: shared field geometry and existing focus, validation and editing behavior.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`: the scroll and IME container in which all three forms draw.
- `app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt` → `CreateChannelModal`: empty name/prompt and submit gate.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` → `EditChannelModal`: existing prompt, note and disabled state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt` → `SaveAsChannelDialog`: reused fields and verbatim prompt contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/CreateChannelModalTest.kt` → `CreateChannelModalTest`: existing focus, text and submit assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/ModalFieldPaletteTest.kt` → `ModalFieldPaletteTest`: existing well and text palette proof.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SharedDarkColourCaptureTest.kt` → `sidebarAt412By892`: real emulator capture and output convention.
- `docs/knowledge/features/shared-typography.md` § Roles: `bodyMedium` and emphasized `labelLarge` metrics already match Figma.
- `docs/knowledge/features/save-as-channel-dialog.md` § `ChannelFormFields`: preserve focus inside the dialog, prompt byte limit and verbatim edits.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes and Compose evidence: shared geometry tests run under Robolectric; real pixels require device instrumentation.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6446; form instances `487:2435` (Create), `500:2120` (Edit), `487:2355` (Save as). Inspected 2026-09-28.

All three instances show the same two-column-width field stack: emphasized `labelLarge` labels above rounded 6dp filled wells, `bodyMedium` input text, 8dp label-to-well and 12dp between fields. The wells have a 12dp outer vertical and 4dp inner vertical inset (16dp total), 16dp left inset, a 56dp right reserve for the single-line name, and 16dp right inset for the paragraph prompt; they show no border or indicator. The component `347:6446` shows an optional trailing action, but the three form instances omit it, so this slice does not add one.

## Context

`ChannelFormFields` already shares copy, colors and behavior across three modal callers. Its Material `TextField` applies default field height and content insets that visibly change the Figma well size. The color and typography roles landed in #1225 and #1226, so this ticket can localize the remaining field geometry to the shared form. No protocol or repository change is involved.

## Design

- Keep the `ChannelFormFields` signature and the three callers. Replace the two Material `TextField` renderers with `BasicTextField` inside a shared, explicitly padded rounded well. Maintain the external `TextFieldValue` for name selection and `String` for the prompt. Let the prompt expand beyond its four-line initial height inside `MobileModalShell`'s scroll area.
- Draw both labels through `LabelledField` with `labelLarge` SemiBold and the modal's `onPrimaryContainer` foreground. Give the editable nodes the existing test tags and accessible field names. Use `bodyMedium` and the established modal field container/text tokens. A disabled field remains readable and loses edit actions.
- The name stays single-line, requests focus in the dialog composition, and advertises IME Next. The prompt preserves newlines, spaces and byte-limit feedback. Its static optional note appears in the same slot as today. When over limit, expose an error semantic and an error-color message without changing the well's no-border geometry.
- Explicit well padding and a minimum paragraph height allow font scaling without clipping; the shell handles the keyboard and compact viewport. The field blocks retain 8dp internal and 12dp inter-field spacing.

## State and concurrency model

The form remains stateless. The caller owns values, enabled state and callbacks; `LaunchedEffect(Unit)` requests initial name focus exactly once per composition. No new jobs, flows or dispatchers are added. The modal owns scrolling and closes the composition and focus request on dismissal.

## Error handling

`SystemPromptLimit.fits` continues to classify UTF-8 overflow locally. The form shows the existing static message and error semantics; callers continue to disable submission. There is no I/O here, and caller errors remain in `MobileModal`.

## Testing strategy

- Add a shared Compose test beside `CreateChannelModalTest` for exact field/label geometry, accessible names, focus and Next, prompt verbatim editing, disabled state, over-limit message and note, at ordinary and enlarged text scales plus compact width. Run the new class under Robolectric and on the managed device if a measurement differs.
- Keep existing Create, Edit and Save as modal tests and `ModalFieldPaletteTest` green; run touched classes. Run lint, `assembleDebug` and Android-test compilation.
- Add a focused device-only capture test because genuine pixels and the actual viewport require an emulator. Capture the form at 412 × 892 dp in static dark, retain the PNG and test XML under `app/src/androidTest/assets/channel-fields-1233/`, and create a labelled side-by-side or difference image against the inspected Figma form. Inspect the output for field mismatches before the PR.
- This is a visual adjustment to an existing operator flow; existing rung-3 scenarios cover the Create/Save as actions. No new wire action or happy path is introduced.

## Open questions

- Figma shows default filled wells only. Focus, disabled and over-limit visuals are not specified there; preserve clear accessible behavior with the current theme roles and record these missing states in the PR.
- The supplied Figma form instance is 676 × 441, while the required emulator viewport is 412 × 892. Compare the shared fields at their native dp measurements and label the differing outer shell geometry in the evidence rather than scale the text or well dimensions.

## Documentation handoff

No documentation path or section is requested in this ticket. The documentation stage should update the owning shared modal/channel form overview with the final field geometry and the evidence location; pending that stage.
