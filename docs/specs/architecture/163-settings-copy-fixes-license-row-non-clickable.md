# #163 — Settings copy fixes + License row non-clickable

Ticket: https://github.com/pyrycode/pyrycode-mobile/issues/163
Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2
Size: XS

## Context

Three copy/treatment mismatches between the current `SettingsScreen` and Figma 17:2:

1. Theme supporting text shows `"System default"` for `ThemeMode.SYSTEM`; Figma reads `"System"`.
2. Wallpaper-colors row headline reads `"Use wallpaper colors"`; Figma reads `"Use Material You dynamic color"`.
3. License row currently has a trailing chevron and `onClick` that navigates to a dedicated `LicenseScreen` route. Figma row 17:124–17:126 is text-only ("License: MIT"); no chevron, no row click.

Removing the License row's click action orphans `LicenseScreen.kt`, the `Routes.LICENSE` nav entry in `MainActivity.kt`, the `R.string.license_title` resource, the `app/src/main/assets/LICENSE` asset, and `LicenseScreenTest.kt` (verified — `LicenseScreen` has no other callers). Architect decision: **remove the orphaned code in this ticket.** The license-viewer screen has no remaining entry point in the app; leaving it as untriggerable code adds review/test cost and confuses future readers. Restoring from git history is trivial if a future entry point is ever wired.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Settings screen is a vertical M3 list grouped under `labelLarge` primary-coloured section headers. The relevant rows for this ticket (Appearance section + bottom of About section): a "Theme" two-line list item with supporting text "System" and a trailing chevron; a "Use Material You dynamic color" single-line row with a trailing Switch; and a final "License: MIT" single-line row that is text-only — no trailing icon, no ripple, no row-level click target.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:47-229` — composable signature, row composition, and the three rows that change (Theme @114, Use-wallpaper @120, License @222)
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:292-297` — `ThemeMode.label()` extension; this is where the `"System default"` → `"System"` change lives
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:241-256` — `SettingsRow` contract: passing `onClick = null` (the parameter default) makes the row non-clickable (no `Modifier.clickable`). Use the default, don't add a new "isClickable" flag.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:200-216` — Settings composable wiring + `Routes.LICENSE` block to delete; line 244 declares the constant
- `app/src/main/java/de/pyryco/mobile/ui/settings/LicenseScreen.kt` — entire file to delete
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — existing instrumented test pattern (`createComposeRule`, `performScrollTo`, `hasText`/`hasClickAction`); extend with new assertions
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt` — entire file to delete
- `app/src/main/res/values/strings.xml:17-29` — `settings_use_wallpaper_colors` (line 25) is the string to update; `license_title` (line 19) is the resource to delete
- `app/src/main/assets/LICENSE` — asset file to delete (only `LicenseScreen.kt` reads it)
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — existing unit-test conventions for the settings package (JUnit4, no MockK needed for a pure-Kotlin assertion)

## Design

Three independent surface-level edits plus deletion of one orphaned route+screen+asset+test. No new types, no new composables, no DI changes, no preference changes, no state-machine changes.

### Edit 1 — Theme supporting text

In `SettingsScreen.kt`, update the `ThemeMode.label()` extension (currently at lines 292–297):

```kotlin
internal fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "System"      // was "System default"
        ThemeMode.LIGHT  -> "Light"
        ThemeMode.DARK   -> "Dark"
    }
```

The `"Light"` and `"Dark"` arms are unchanged. The Figma source-of-truth only specifies the SYSTEM case; do not touch the others.

### Edit 2 — Wallpaper-colors row headline

In `app/src/main/res/values/strings.xml`, change the value of `settings_use_wallpaper_colors`:

```xml
<string name="settings_use_wallpaper_colors">Use Material You dynamic color</string>
```

The supporting-text resource `settings_use_wallpaper_colors_unsupported` ("Requires Android 12 or newer") stays as-is — it's only shown when `Build.VERSION.SDK_INT < S` and is not part of the locked Figma. The composable already reads from `R.string.settings_use_wallpaper_colors` at `SettingsScreen.kt:121`; no Kotlin change.

### Edit 3 — License row non-clickable, no chevron

In `SettingsScreen.kt` at line 222, replace the existing block:

```kotlin
SettingsRow(
    headline = "License: MIT",
    trailing = { ChevronIcon() },
    onClick = onOpenLicense,
)
```

with:

```kotlin
SettingsRow(
    headline = "License: MIT",
)
```

This relies on `SettingsRow`'s existing defaults (`supporting = null`, `trailing = null`, `onClick = null`) — verified at line 248: a `null` `onClick` produces a plain `Modifier`, no `clickable`. **Do not** introduce a new parameter or modify `SettingsRow`.

Then drop `onOpenLicense: () -> Unit` from the `SettingsScreen` parameter list (line 55) and update both `@Preview` callsites (lines 311 and 327) to remove the argument.

### Edit 4 — Delete the orphaned License route + screen + asset + test

After Edit 3, `onOpenLicense` is the only consumer of `Routes.LICENSE`, which is the only consumer of `LicenseScreen`, which is the only consumer of `app/src/main/assets/LICENSE` and `R.string.license_title`. All become dead. Delete in this order (any order works; this minimises transient compile errors):

1. `app/src/main/java/de/pyryco/mobile/MainActivity.kt`:
   - Remove the `import de.pyryco.mobile.ui.settings.LicenseScreen` line (44)
   - Remove the `onOpenLicense = ...` argument from the `SettingsScreen(...)` call (line 210)
   - Remove the `composable(Routes.LICENSE) { ... }` block (lines 214–216)
   - Remove the `const val LICENSE = "license"` line (244) from the `Routes` object
2. Delete the entire file `app/src/main/java/de/pyryco/mobile/ui/settings/LicenseScreen.kt`
3. Delete the entire file `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt`
4. Delete the asset file `app/src/main/assets/LICENSE`
5. In `app/src/main/res/values/strings.xml`, remove the `license_title` entry (line 19)
6. In `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`, remove the `onOpenLicense = {}` argument from both `SettingsScreen(...)` invocations (lines 31 and 53)

If the `app/src/main/assets/` directory is empty after step 4, leave the directory in place — `removeAll` of the directory is out-of-scope churn.

## State + concurrency model

No change. `SettingsScreen` remains a stateless `Composable` driven by `SettingsViewModel`'s `themeMode`/`useWallpaperColors` `StateFlow`s. No new `LaunchedEffect`, no new `remember`s, no new coroutines.

## Error handling

No new code paths; nothing to handle. The deleted `LicenseScreen` previously read `LICENSE` from assets at composition time using `context.assets.open(...).use { ... }` — that asset-read goes away with the screen. No other code reads the asset (verified by grep — only `LicenseScreen.kt` references `LICENSE_ASSET_NAME`).

## Testing strategy

### Unit test (new file)

Add `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsLabelsTest.kt` covering the `ThemeMode.label()` extension. Pure Kotlin, no Android dependencies, runs under `./gradlew test`.

Scenarios:

- `ThemeMode.SYSTEM.label()` returns the string `"System"` (covers AC 1 and guards against accidental regression to `"System default"`)
- `ThemeMode.LIGHT.label()` returns `"Light"` (regression guard, not part of the AC but cheap)
- `ThemeMode.DARK.label()` returns `"Dark"` (same)

The developer should put this test in the existing `ui/settings` test package; the `label()` extension is declared `internal` so the test must be in the same module (it is — single-module project).

### Instrumented tests (extend existing file)

Add two new `@Test` methods to `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`. Follow the existing pattern in `openSourceRow_hasClickAction` exactly — set content with `PyrycodeMobileTheme { SettingsScreen(...) }`, scroll to the node, assert.

Scenarios:

- `wallpaperColorsRow_rendersMaterialYouLabel` — find a node matching `hasText("Use Material You dynamic color")`, `performScrollTo()`, `assertExists()`. Covers AC 2.
- `licenseRow_hasNoClickActionAndNoTrailingIcon` — find the node matching `hasText("License: MIT")`, `performScrollTo()`, then assert `hasClickAction().not()` (or `assert(!hasClickAction())` via Compose's matcher composition). For the trailing-icon absence: the row's `trailingContent` slot is `null` by composable default; assertion can be `onNodeWithText("License: MIT").onParent().onChildren().assertCountEquals(1)` — the `ListItem` will then contain only the headline slot. **Prefer the click-action assertion as the primary check** (it's the load-bearing behaviour the AC names); the trailing-icon assertion is a secondary regression guard. If `onChildren().assertCountEquals(1)` proves brittle against `ListItem`'s internal structure during implementation, narrow it to a simpler "no chevron `KeyboardArrowRight` icon under this row" check or drop it — the click-action assertion alone satisfies AC 3.

The two existing tests (`versionRow_rendersBuildConfigVersionName`, `openSourceRow_hasClickAction`) must continue to pass with the `onOpenLicense` argument removed.

### Removed tests

`LicenseScreenTest.kt` is deleted with the screen. No replacement needed.

### Manual / preview check

The two `@Preview` composables in `SettingsScreen.kt` (Light + Dark) must continue to render after the parameter removal. Run them in Android Studio preview pane to confirm AC 5 — the three changed rows show the new copy and the License row has no chevron. Mention in the PR description whether this was checked.

## Open questions

None. The Figma node, the existing component contracts, and the existing test patterns are sufficient.
