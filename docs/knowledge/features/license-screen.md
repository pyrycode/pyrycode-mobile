# License screen

Generic M3 secondary screen at the `license` route that renders the MIT license text bundled into the APK at `assets/LICENSE`. Reached from `SettingsScreen`'s About-section "License: MIT" row (#91). No Figma counterpart — visual shape follows the project's secondary-screen idiom (back-arrow `TopAppBar` + scrollable body) established by `DiscussionListScreen` and `SettingsScreen`.

## What it does

Lets users read the licence Pyrycode Mobile ships under without leaving the app or hitting the network. The text is a byte-identical copy of the repo-root `LICENSE` packaged at `app/src/main/assets/LICENSE` — the displayed content cannot drift from what the build was actually packaged with.

## How it works

Pure stateless composable, no `ViewModel`, no DI, no repository, no coroutines.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Skeleton:

- `Scaffold(modifier)` with an M3 `TopAppBar` — `title = stringResource(R.string.license_title)` ("License"), `navigationIcon` is the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + `R.string.cd_back`) wired to the `onBack` callback. Byte-equivalent to `SettingsScreen` / `DiscussionListScreen`.
- Inside the content lambda: `val context = LocalContext.current`, then a single keyed `remember`:
  ```kotlin
  val licenseText = remember(LICENSE_ASSET_NAME) {
      context.assets.open(LICENSE_ASSET_NAME).bufferedReader().use { it.readText() }
  }
  ```
  `LICENSE_ASSET_NAME` is a file-private `const val = "LICENSE"`. The read runs once per composition entry; the cached `String` survives recomposition.
- The text renders as a single `Text` styled `MaterialTheme.typography.bodyMedium` (default 14sp — matches `SettingsRow` headlines and gives comfortable reading rhythm on dense legal prose without forcing horizontal scroll on small viewports). Modifier chain: `.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp)`.

### Asset

- Path: `app/src/main/assets/LICENSE` (21 lines, MIT).
- Source: byte-identical copy of the repo-root `LICENSE`. Do not edit, reformat, or normalize line endings — the androidTest substring matcher is the regression guard.
- AGP picks up `src/main/assets/` automatically; no Gradle config touched.

### Strings

One new id in `strings.xml`: `license_title = "License"`. The pre-existing `cd_back` is reused for the back-arrow content-description.

## Configuration / usage

Mounted at the `license` route in `PyryNavHost` (`MainActivity.kt`):

```kotlin
composable(Routes.License) {
    LicenseScreen(onBack = { navController.popBackStack() })
}
```

Entry point is `SettingsScreen`'s About-section "License: MIT" row (`SettingsScreen.kt:190-194`), wired via an `onOpenLicense: () -> Unit` parameter passed from the destination block:

```kotlin
composable(Routes.Settings) {
    SettingsScreen(
        onBack = { navController.popBackStack() },
        onOpenLicense = { navController.navigate(Routes.License) },
    )
}
```

The `SettingsScreen` row carries `trailing = { ChevronIcon() }` to match the visual idiom of the other clickable secondary-navigation rows in the About / Defaults / Storage sections.

## Edge cases / limitations

- **Asset missing.** Treated as a build-packaging defect, not a runtime user condition. `assets.open(...)` propagates `IOException`; no try/catch. The crash is the right signal in CI / instrumented testing — the asset is checked into source control via the AC's byte-identical copy.
- **Empty asset.** Theoretical only. Would render an empty scroll surface; no UI special-casing. The androidTest substring assertion (`"Permission is hereby granted"`) is the regression guard against accidental edits.
- **Text is not selectable.** A future enhancement could wrap the `Text` in `SelectionContainer { ... }` to let users copy the licence. Out of scope here; file as a follow-up if needed.
- **Main-thread read.** `AssetManager.open` + `bufferedReader().use { readText() }` block the main thread for the ~1 KB asset — sub-millisecond, well below a frame budget. Don't add a coroutine / dispatcher; the boilerplate-to-value ratio is wrong for a synchronous pure read.

## Previews

Two `@Preview`s, both `private`, both at `widthDp = 412`:

- `LicenseScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { LicenseScreen(onBack = {}) }`.
- `LicenseScreenDarkPreview` — same with `darkTheme = true`.

The preview's `LocalContext.current.assets.open("LICENSE")` resolves the real bundled asset, so previews render the actual licence text.

## Testing

Instrumented (`./gradlew connectedAndroidTest`). New `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt`:

- `backArrow_isPresent` — resolves `R.string.cd_back` via `InstrumentationRegistry.getInstrumentation().targetContext.getString(...)` then `onNode(hasContentDescription(cdBack)).assertIsDisplayed()`.
- `licenseBody_rendersKnownFragment` — `onNode(hasText("Permission is hereby granted", substring = true)).assertExists()`. The substring is a mid-document fragment (rather than `"MIT License"`) so it can't be hit by later fixtures whose headers might also say "MIT License".

No unit tests — there's no `ViewModel`, no state machine, no business logic.

## Related

- Spec: `docs/specs/architecture/91-settings-in-app-license-viewer.md`
- Ticket notes: [`../codebase/91.md`](../codebase/91.md)
- Entry point: [Settings screen](settings-screen.md) — About-section License row
- Sibling back-nav screen patterns: [Settings screen](settings-screen.md), [Discussion list screen](discussion-list-screen.md) — same `Scaffold` + back-arrow `TopAppBar` + scrollable body shape
- Navigation: [Navigation](navigation.md) — `license` route in `PyryNavHost`
