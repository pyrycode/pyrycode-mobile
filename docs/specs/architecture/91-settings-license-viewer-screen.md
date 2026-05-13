# Settings — In-app License viewer screen behind About License row (#91)

## Context

`SettingsScreen`'s About section currently renders the License row as a static, non-clickable text label (`SettingsScreen.kt:192` — `SettingsRow(headline = "License: MIT")`). This ticket adds a secondary screen reachable from that row, displaying the MIT license text bundled into the APK at `assets/LICENSE`, so users can read the licence offline without a browser round-trip.

The repository-root `LICENSE` (21 lines, MIT) is the canonical source; the build copies it into `app/src/main/assets/LICENSE` so the rendered content cannot drift from what shipped. The viewer is a generic M3 secondary screen — Figma has no dedicated mock for it; it follows the back-arrow `TopAppBar` + scrollable body pattern already established by `DiscussionListScreen` and `SettingsScreen`.

## Design source

N/A — the license sub-screen has no Figma counterpart per the ticket body. Visual shape follows the existing generic M3 secondary-screen pattern (Scaffold + back-arrow `TopAppBar` + vertically-scrollable body) already used by `DiscussionListScreen.kt:59-73` and `SettingsScreen.kt:51-65`. The originating row lives at Figma node `17:2` (Settings About section, already implemented in #64 / #90).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:45-65` — existing `SettingsScreen` signature and `Scaffold` + `TopAppBar` + back-arrow shape; `LicenseScreen` mirrors it. Line 192 is the License row to wire.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:59-73` — alternate reference for the back-arrow `TopAppBar` shape; confirms `cd_back` is the established content-description string.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:90-187` — `PyryNavHost` block + `private object Routes` (lines 184-191). License route goes alongside `Settings`; the `composable(Routes.Settings)` call (line 176) is the call site that needs an `onOpenLicense` lambda added.
- `app/src/main/res/values/strings.xml` — existing strings; `cd_back` already exists (line 7). Only `license_title` is new.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — pattern for an androidTest using `createComposeRule()` + `PyrycodeMobileTheme` wrapper; new test mirrors this structure.
- `LICENSE` (repo root, 21 lines) — content to copy verbatim into `app/src/main/assets/LICENSE`. Begins with `MIT License`; contains `Permission is hereby granted` — both are valid fragments for the androidTest substring assertion.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — confirms `PyrycodeMobileTheme` is the wrapper for Compose tests / Previews.

## Design

### Package layout

One new screen file under the existing `ui/settings/` package:

```
app/src/main/java/de/pyryco/mobile/ui/settings/
├── SettingsScreen.kt   (modified — adds onOpenLicense param, wires License row)
└── LicenseScreen.kt    (new)
```

The viewer has **no ViewModel**. Asset I/O is synchronous against `AssetManager`; the file is ~1 KB. Doing this inside `remember { ... }` is the project-idiomatic pattern for one-time pure reads from composition (the alternative — a ViewModel — would add boilerplate for a stateless, cache-friendly value).

### `LicenseScreen` composable

Signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Body contract:

- `Scaffold(modifier)` with a `TopAppBar`:
  - `title = { Text(stringResource(R.string.license_title)) }` (resolves to `"License"`).
  - `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } }` — byte-equivalent to `SettingsScreen.kt:56-63`.
- Inside the scaffold's content lambda:
  - Capture `val context = LocalContext.current`.
  - Read the asset exactly once, cached across recompositions, keyed on the asset name so future asset renames remain stable:
    ```kotlin
    val licenseText = remember(LICENSE_ASSET_NAME) {
        context.assets.open(LICENSE_ASSET_NAME).bufferedReader().use { it.readText() }
    }
    ```
    `LICENSE_ASSET_NAME` is a file-private `const val = "LICENSE"`.
  - Render the text in a single scrollable `Text`:
    ```kotlin
    Text(
        text = licenseText,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(inner)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    )
    ```

**Typography pick.** Use `MaterialTheme.typography.bodyMedium`. The MIT licence is dense legal prose at ~20 short lines; `bodyMedium` (default 14sp) matches the body-text idiom already used by `SettingsRow` headlines and gives a comfortable reading rhythm without forcing horizontal scroll on small viewports. **Do not** use `bodyLarge` (visually heavy for legal text) or any monospace family (the asset is plain prose, not code).

**Why `remember(LICENSE_ASSET_NAME)`.** A bare `remember { ... }` would also work, but keying on the asset name makes the cache invariant explicit ("re-read iff the asset key changes") and matches the AC's "do not re-read on every recomposition" wording. The key is a compile-time constant so the recomputation never triggers in practice — it's documentation, not behaviour.

**No try/catch.** The asset is bundled at build time from a path the AC pins; failure to open it would be a build/packaging bug, not a runtime user-recoverable condition. Throwing through is correct — fail loudly during development. (Boundary-validation rule: only validate at system boundaries; build-packaged assets are not a boundary.)

Add a `@Preview(name = "License — Light", showBackground = true, widthDp = 412)` and a Dark variant, each wrapping `LicenseScreen(onBack = {})` in `PyrycodeMobileTheme(...)`, matching the `SettingsScreen` preview pair at `SettingsScreen.kt:260-274`. The preview's `LocalContext` is the IDE's preview context; its `AssetManager` correctly resolves `assets/LICENSE`, so previews render real licence text.

### Asset

Copy the repo-root `LICENSE` byte-identically to `app/src/main/assets/LICENSE`. The `app/src/main/assets/` directory does not currently exist; creating it as part of this ticket is fine (no Gradle config change needed — AGP picks up `src/main/assets/` automatically). Use plain `cp LICENSE app/src/main/assets/LICENSE` in the developer's workflow; do not edit, reformat, or add a trailing-newline normalization step. The androidTest's substring check is the regression guard against accidental edits.

### Navigation wiring

In `MainActivity.kt`:

1. Add a `License` constant to the `private object Routes`:
   ```kotlin
   const val License = "license"
   ```

2. Add a new `composable(Routes.License) { ... }` block alongside `composable(Routes.Settings)`:
   ```kotlin
   composable(Routes.License) {
       LicenseScreen(onBack = { navController.popBackStack() })
   }
   ```

3. Pass `onOpenLicense` into `SettingsScreen` from the existing `composable(Routes.Settings)` block:
   ```kotlin
   composable(Routes.Settings) {
       SettingsScreen(
           onBack = { navController.popBackStack() },
           onOpenLicense = { navController.navigate(Routes.License) },
       )
   }
   ```

   The `navController` instance stays in `MainActivity.kt`; `SettingsScreen` continues to receive plain lambdas. This matches the pattern used everywhere else in `PyryNavHost` (compare `composable(Routes.ChannelList)` at `MainActivity.kt:115-141`, which builds three call-site lambdas and passes them to the screen).

4. Add the `LicenseScreen` import next to the existing `SettingsScreen` import (`MainActivity.kt:42`).

### `SettingsScreen` change

Add an `onOpenLicense: () -> Unit` parameter to the `SettingsScreen` signature:

```kotlin
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Wire the License row at `SettingsScreen.kt:192`:

- Before: `SettingsRow(headline = "License: MIT")`
- After: `SettingsRow(headline = "License: MIT", trailing = { ChevronIcon() }, onClick = onOpenLicense)`

The `ChevronIcon` trailing matches the visual treatment of every other clickable secondary-navigation row in the About / Defaults / Storage sections of the same screen — without it, the row would look non-interactive despite being clickable. This is consistent with the existing in-screen idiom (`SettingsScreen.kt:107-110`, `127-130`, `162-167`).

**Compatibility.** `SettingsScreen` has exactly one production caller (`MainActivity.kt:177`) and two `@Preview` callers (`SettingsScreen.kt:264, 272`) plus two test callers (`SettingsScreenTest.kt:24, 38`). All five sites must pass `onOpenLicense = {}` (or `= { navController.navigate(Routes.License) }` for `MainActivity`). Five sites is well under the 10-call-site fan-out red line; this is a normal additive parameter, not a refactor cascade.

No default value (`onOpenLicense: () -> Unit = {}`) on the parameter — this keeps every call site honest about whether it's wired to navigation. Two `@Preview` and two test sites pass `{}` explicitly. The single production site at `MainActivity.kt:177` passes the real navigation lambda.

### String resources

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="license_title">License</string>
```

`cd_back` already exists at line 7 — do not duplicate.

## State + concurrency model

Stateless screen. No `ViewModel`, no `StateFlow`, no coroutines.

- The asset read runs once per composition entry inside `remember(LICENSE_ASSET_NAME) { ... }` — `AssetManager.open()` and `bufferedReader().use { readText() }` are blocking, synchronous calls. For a ~1 KB asset on the main thread the wall-clock cost is well below a frame budget (<<16 ms) and the cached `String` survives recomposition.
- No dispatchers, no cancellation surface — composition cancellation is not relevant for a sub-millisecond synchronous read.
- Returning from the screen via the back arrow calls `onBack()`, which routes through `MainActivity`'s `navController.popBackStack()`; no cleanup needed.

## Error handling

- **Asset missing or unreadable.** This is a build-packaging defect, not a runtime user condition. Let `IOException` propagate (no try/catch). The crash will be obvious in instrumented testing and is the right signal — Phase 0 / Phase 1 builds always ship the asset.
- **Empty asset.** Theoretical only (the asset is checked into source control via the AC's byte-identical copy). Would render an empty scroll surface; no UI special-casing. The androidTest's substring assertion (`"MIT License"` or `"Permission is hereby granted"`) catches the regression.
- **Navigation back-stack underflow.** `navController.popBackStack()` is safe on the License → Settings → ChannelList stack; no edge case.

## Testing strategy

### Unit tests

None. There is no ViewModel, no repository, no pure-Kotlin logic to assert against; everything is composable rendering that requires `ComposeTestRule`.

### Instrumented test (Compose)

New file: `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt`. Mirror the shape of the existing `SettingsScreenTest.kt`: `@RunWith(AndroidJUnit4::class)`, `@get:Rule createComposeRule()`, `setContent { PyrycodeMobileTheme { LicenseScreen(onBack = {}) } }`.

Two test scenarios:

- **`backArrow_isPresent`** — assert a node with content-description equal to the resolved string for `R.string.cd_back` exists. Use `hasContentDescription(...)` with the resolved string (look it up via `composeTestRule.activity.getString(R.string.cd_back)` or `InstrumentationRegistry.getInstrumentation().targetContext.getString(...)`). Verifies the back-arrow `IconButton` is wired.
- **`licenseBody_rendersKnownFragment`** — assert a `Text` node containing the substring `"Permission is hereby granted"` exists. The substring is stable across the AC's "byte-identical copy" guarantee and survives `wordwrap` SemanticsNode segmentation better than `"MIT License"` (which is only the first line and may be hit by other test fixtures if added later). Use `onNode(hasText("Permission is hereby granted", substring = true)).assertExists()`.

Both tests run under `./gradlew connectedAndroidTest` and require a connected device or emulator (`assetManager` only works on a real Android runtime; Robolectric is not configured for this project).

A `SettingsScreenTest.kt` update is **not** required by this ticket — adding `onOpenLicense = {}` to the two existing `setContent` blocks is the only change needed to keep those tests compiling, and it's a trivial diff. The developer should make that compile-fix as part of the same change; no new assertion is added.

## Open questions

- **Top-of-screen scroll affordance.** With <30 lines of text on most devices the body fits without scroll; on small landscape orientations a scroll indicator would be nice but Compose's `verticalScroll` doesn't expose one natively. Not in scope — defer until UX testing flags it.
- **Selectable text.** `Text` content is not selectable by default; a future enhancement could wrap in `SelectionContainer { ... }` to let users copy the license. Out of scope for this ticket — file as a follow-up if requested. The AC does not require selection.
- **Asset normalization (line endings).** Repo `LICENSE` is LF-terminated on macOS/Linux checkouts; if the developer happens to be on a Windows checkout with `core.autocrlf=true`, `cp` would copy CRLF bytes into the asset. The androidTest substring check is independent of line-ending choice, so this won't fail tests. Not worth a normalization step in this ticket.
