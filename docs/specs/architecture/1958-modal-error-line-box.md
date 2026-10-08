# Preserve the modal error line box (#1958)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt`: `MobileModalShell` appends the error to its centered content column.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`: `AppTypography.bodyMedium` supplies 14 sp / 20 sp / 400 / 0.25 sp without a line-height override.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt`: existing shell geometry, palette, footer and compact-layout coverage.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt`: existing error recomposition and focus coverage.
- `app/src/androidTest/assets/design-1220/list/1737-evidence.txt`: both prompt-failure errors measured 36 dp rather than 40 dp; the independent prompt-well mismatch belongs to #1957.
- `docs/knowledge/features/mobile-modal.md`: preserve the shared shell's composition tree, centered content slot and footer spacing.
- `docs/knowledge/features/shared-typography.md`: local `LineHeightStyle(Center, Trim.None)` preserves reference line boxes without changing global typography.
- `docs/knowledge/features/development-verification-gates.md`: exact dialog geometry needs native graphics, a window qualifier and a measured-width assertion; wider editable fixtures can hang in Robolectric.

## Design source

**Figma:** [Create channel / Prompt failed](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=784-7095), [Save as channel / Prompt failed](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=784-7134).

Read both contexts and screenshots on 2026-10-08. Each dark modal centers the name, prompt and final error group between its header and Cancel/OK footer. The final error spans two untrimmed 20 dp lines with M3 body/medium metrics and Schemes/Error; the existing close asset and other geometry stay in their current slots.

## Change

Copy `MaterialTheme.typography.bodyMedium` only at `MobileModalShell`'s final error `Text`, setting centered line-height alignment and no outer trimming. This restores the reference line box naturally, retaining font scaling, the theme's metrics and error colour, exact error semantics, polite announcement and final-item placement. Keep the centered column arrangement. Global typography and the prompt well remain outside this ticket. No overlapping in-flight branch touches the planned files. Forecast: about 150 written lines including this plan and shared tests, one production file, no new exported API, no consumer updates, two acceptance criteria and no new error branches; within all sizing boundaries.

## Testing strategy

Add `MobileModalErrorLayoutTest` under `app/src/sharedTest` before production changes. With native graphics and a measured 412×892 dp modal at font scale 1.0, use noneditable content probes to avoid Robolectric's wider-field idle bug. Test both exact string-resource errors for two lines, 40 dp semantics height within 2 dp, bodyMedium metrics, error colour, exact error semantics, polite live region, final placement with a 12 dp preceding gap, and group centering within the actual shell slot. Assert the local no-trim style as well, since Robolectric and device fonts can differ. Watch the new tests fail before applying the style override.

Run the new tests, existing `MobileModalFillTest`, `ModalFieldPaletteTest`, `CreateChannelDialogTest` and `SaveAsChannelDialogTest`, plus the existing device method `MobileModalTest#error_and_loading_recomposition_preserve_entered_value_and_focus`. This changes an existing text presentation, with no new operator action or backend flow; no new real-Claude scenario is needed. Run lint, debug assembly, shared-test instrumentation compilation and Spotless, then merge main, push and run the full unit/shared suite, assembly and `scripts/pre-verify.py --gradle` before opening the PR. The dispatcher owns the full device and scripted suites.
