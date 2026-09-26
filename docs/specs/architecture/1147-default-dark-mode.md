# #1147 — Default to dark mode

## Files read

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` — `AppPreferences.themeMode` reads the saved enum name with a fallback.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `MainActivity.onCreate` collects the preference and resolves System/Light/Dark for the root theme.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — `SettingsViewModel.themeMode` supplies the initial and collected Settings selection.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt` — `ThemePickerDialog` preserves all three choices and reflects its selected input.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme` already provides the dark palette.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` — default, unknown-value and round-trip assertions.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — initial selection and preference propagation assertions.
- `docs/knowledge/features/app-preferences.md`, `settings-screen.md`, `settings-viewmodel.md` — storage schema and the two collectors sharing it; keep the stored key and enum names stable.
- `docs/knowledge/features/development-verification.md` — scoped JVM checks and shared screen-test conventions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

Read the design context and screenshot for node 17:2: a dark vertical Settings list with M3 surface/on-surface colors, primary section labels, titleLarge heading, bodyLarge row titles and bodySmall subtitles. Preserve the existing row layout and palette; the ticket intentionally changes the unsaved Theme subtitle from the reference's sample System to Dark.

## Change

Change three `ThemeMode.SYSTEM` defaults to `ThemeMode.DARK`: the preference reader fallback (missing or unknown stored name), the root lifecycle collector's initial value, and the Settings StateFlow initial value. Saved SYSTEM, LIGHT and DARK names retain their meaning, key and write path, so upgrades need no migration. No new state, jobs, errors, logging events, types or theme tokens are introduced.

Size check: one deliverable, three production lines across three source files, about 100 total written lines including this plan and tests, zero exported declarations or simultaneous consumer updates, two acceptance criteria, zero state-machine reject branches. This agrees with the XS estimate. Refreshed remote feature branches: no overlap with the intended production or test files; no dependency.

## Testing strategy

Update existing missing/unknown preference and Settings initial-value assertions to Dark and observe RED before changing production. Assert the Settings initial value before collection as well as after loading. Add a disk-reopen test seeded with each existing serialized name, proving upgrade-compatible reads and restart persistence; retain the all-values setter round trip. Check the unchanged root mapping (DARK always true; SYSTEM follows the phone), Settings label and picker selection wiring by inspection. This is a default retune of an existing local preference, with no new operator/daemon flow or live-Claude scenario.

Run the two touched unit-test classes, Spotless, lint and assembleDebug. No device-only or shared-test source changes are planned. The dispatcher owns the full regression/device gates.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/app-preferences.md` sections “What it does” and “How it works” to describe Dark for missing/unknown stored theme values and both initial collectors, preserving saved System/Light/Dark choices. The ticket has no separate documentation acceptance criterion.
