# Settings — About Version row + Open-source row wiring (#90)

## Context

`SettingsScreen` (delivered visually in #64) renders the About section as a static skeleton: the Version row hardcodes `"Version 0.1.0"` / `"build a8f3c2d"`, and the Open-source row's `onClick = {}` is a no-op. This ticket wires those two rows so the Version row reflects the actually-installed build and the Open-source row launches the platform browser at the repo URL. License is intentionally out of scope (sibling ticket).

The locked Figma row treatment (single-line headline with version inline, separate `supporting` line for build code, `ExternalLinkIcon` trailing) must be preserved — substitute dynamic data into the existing `headline` / `supporting` slots; do not refactor the row shape.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The About section is a stack of M3 `ListItem`-style rows under a `labelLarge`/`primary` "About" header. The Version row is a non-clickable two-line row (body-large headline `Version <name>`, body-small supporting `build <code>`); the Open-source row is a clickable single-line row with the `ic_open_in_new` painter as trailing content (`Schemes/on-surface`, 18dp). Tokens already wired via `MaterialTheme.colorScheme`/`typography` and `R.drawable.ic_open_in_new` — no new visual assets.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:170-185` — current About section, the two `SettingsRow(...)` calls being modified. Lines 200-215 show `SettingsRow` already accepts `onClick: (() -> Unit)? = null` and applies `Modifier.clickable` when non-null — no signature change required.
- `app/build.gradle.kts:14-40` — `defaultConfig` (already has `versionCode = 1`, `versionName = "1.0"`) and `buildFeatures { compose = true }` (the block where `buildConfig = true` is added).
- `app/src/androidTest/java/de/pyryco/mobile/ExampleInstrumentedTest.kt` — only existing androidTest file. Confirms `androidx.test.ext.junit.runners.AndroidJUnit4` is available; this ticket introduces the project's first `createComposeRule()` test.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` (any path under `ui/theme/`) — `PyrycodeMobileTheme` wrapper to use in the Compose test's `setContent`.

## Design

### Gradle change

Add `buildConfig = true` to the `buildFeatures { }` block in `app/build.gradle.kts`. Without this, AGP 8.x does not generate `BuildConfig` and `de.pyryco.mobile.BuildConfig.VERSION_NAME` won't resolve.

```kotlin
buildFeatures {
    compose = true
    buildConfig = true
}
```

`versionCode = 1` and `versionName = "1.0"` already exist in `defaultConfig` — no values to add there.

### SettingsScreen changes

Two edits inside the existing About section (`SettingsScreen.kt:170-185`):

**Version row.** Substitute `BuildConfig` values into the headline/supporting strings. Read them inline at composition time:

- `headline = "Version ${BuildConfig.VERSION_NAME}"`
- `supporting = "build ${BuildConfig.VERSION_CODE}"`

The row remains non-clickable (no `onClick`). `BuildConfig.VERSION_NAME` is a `String`; `VERSION_CODE` is an `Int` — interpolation handles both.

**Open-source row.** Replace `onClick = {}` with a lambda that dispatches `Intent.ACTION_VIEW` for the GitHub URL using the `LocalContext`-captured `Context`.

- Capture once at the top of `SettingsScreen`'s composable body: `val context = LocalContext.current`
- In the row's `onClick`: build an `Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pyrycode/pyrycode-mobile"))` and call `context.startActivity(intent)`
- Define the URL as a private top-level `const val SOURCE_REPO_URL = "https://github.com/pyrycode/pyrycode-mobile"` at the bottom of the file (single use, but extracting it makes the test ignore the literal and prevents drift if it moves)

Imports to add:
- `android.content.Intent`
- `android.net.Uri`
- `androidx.compose.ui.platform.LocalContext`
- `de.pyryco.mobile.BuildConfig`

Per ticket: do NOT thread an `ActivityResultLauncher` through composable parameters — `LocalContext` lambda capture is the established pattern for one-shot external URL launches in this codebase.

The Privacy policy row (line 181-184) keeps its `onClick = {}` — out of scope. License row (line 185) keeps its current shape.

### State + concurrency model

None. `BuildConfig.*` are compile-time `const val` reads — stable, no recomposition triggers. The `onClick` lambda captures the (stable) `Context`; no `remember` required for the lambda since it doesn't allocate hot state and the row's recomposition cost is negligible.

### Error handling

`context.startActivity(Intent.ACTION_VIEW)` for `https://` URIs is resolved by the platform's default browser. On a stock Android emulator / device with Chrome (or any browser) installed it succeeds. There is no realistic failure mode worth defending against on Phase-0 hardware (a device with zero browsers can theoretically throw `ActivityNotFoundException`, but Pyrycode mobile targets API 33+ where a browser is universal). **Do not** wrap the `startActivity` call in `try`/`catch` — keep it unguarded. If a follow-up ever needs a fallback (custom-tab, in-app web view), it lands in its own ticket.

### Testing strategy

One new instrumented test file: `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`.

- Use `androidx.compose.ui.test.junit4.createComposeRule()` (no Activity needed — we're not launching an Intent in the test).
- `composeTestRule.setContent { PyrycodeMobileTheme { SettingsScreen(onBack = {}) } }`.
- The About rows live at the bottom of a `verticalScroll` Column — assertions must `performScrollTo()` before asserting display, otherwise the row may be off-screen and matchers can mis-report.

Scenarios (write as discrete `@Test` methods):

- **`versionRow_rendersBuildConfigVersionName`** — find a node with text containing `BuildConfig.VERSION_NAME` (substring match), scroll to it, assert it is displayed. The matcher reads `BuildConfig.VERSION_NAME` directly so the test stays correct when `versionName` is bumped.
- **`openSourceRow_hasClickAction`** — find a node with text containing `"Open source"` (substring match), scroll to it, assert `hasClickAction()`. Per AC the test does not need to verify that the external `Intent` actually launches — `ListItem`'s merged-descendant semantics expose the row's `clickable` `OnClick` action on the same node that carries the headline text, so a single `onNode(hasText("Open source", substring = true))` resolves cleanly.

No unit (`./gradlew test`) coverage required — the change is UI-rendering + intent-dispatch wiring, both of which the Compose test covers.

### Manual verification

Per the third acceptance criterion: install on emulator (`./gradlew installDebug`), open Settings, scroll to About, tap the Open-source row, confirm an external browser activity loads `https://github.com/pyrycode/pyrycode-mobile`. Note in the PR description that this was checked.

## Open questions

None.
