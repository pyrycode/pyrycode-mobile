# Spec: Compose tests for Scanner state screens (#101)

## Context

Phase 1's scanner flow has three state surfaces — default, connecting, denied — each its own composable in `app/src/main/java/de/pyryco/mobile/ui/onboarding/`. They're stable; no design churn pending. Ticket #100 just landed Compose tests for `WelcomeScreen` following the `SettingsScreenTest` pattern; this ticket extends the same pattern to the scanner trio so regressions in their visible structure get caught structurally instead of by eyeballing previews.

Scope: three new instrumented test classes, one per screen, asserting that the expected visible elements render and that obvious click targets carry a click action. No production-code changes.

## Design source

N/A — testing infrastructure per ticket body; no Figma anchor required.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt:1-48` — canonical precedent. Same imports, same `@RunWith(AndroidJUnit4::class)` + `createComposeRule()` + `PyrycodeMobileTheme { ... }` wrapping; copy the shape verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:49-154` — public composable signature `fun ScannerScreen(onTap: () -> Unit)`. Visible strings: `"Pair with pyrycode"` (TopAppBar title), `"Back"` (back icon `contentDescription`), `"Trouble scanning? Paste the pairing code instead"` (TextButton). HintCard body is `buildAnnotatedString` — the literal segments are `"Run "`, `"pyry pair"`, `" on your pyrycode server to generate a QR code."`; assert via substring match on `"pyry pair"`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreen.kt:25-60` — signature `fun ScannerConnectingScreen(serverAddress: String, modifier: Modifier = Modifier)`. Visible strings: `"Connecting to your pyrycode server…"` (note the U+2026 ellipsis) and the `serverAddress` arg rendered verbatim in a monospace Text.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt:33-84` — signature `fun ScannerDeniedScreen(onOpenSettings: () -> Unit, onPasteCode: () -> Unit, modifier: Modifier = Modifier)`. Visible strings: `"Camera permission required"` (headline), body copy starting `"Pyrycode needs the camera to read the QR code from your server."`, button `"Open settings"`, TextButton `"Paste code instead"`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/welcome/WelcomeScreenTest.kt` (just-landed #100) — second example of the pattern in this repo if the developer wants a recent reference alongside `SettingsScreenTest`.

## Design

Three new files under `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/`:

- `ScannerScreenTest.kt` — class `ScannerScreenTest`
- `ScannerConnectingScreenTest.kt` — class `ScannerConnectingScreenTest`
- `ScannerDeniedScreenTest.kt` — class `ScannerDeniedScreenTest`

Each file mirrors `SettingsScreenTest` exactly:

- `@RunWith(AndroidJUnit4::class)` on the class.
- `@get:Rule val composeTestRule = createComposeRule()`.
- Per test: `composeTestRule.setContent { PyrycodeMobileTheme { <ScreenUnderTest>(...) } }` then one assertion. No fakes, no MockK, no DI — these screens are stateless and take only lambdas / a plain String.
- Lambda args passed as `{}`; `serverAddress` passed as a stable literal (use `"home.lan:7117"` to match the preview).
- Use `hasText(..., substring = true)` for matches against `buildAnnotatedString` content and for any text that's part of a longer paragraph; exact match is fine for short standalone labels.
- Use `hasClickAction()` (asserted via `.assert(hasClickAction())`) for button/TextButton click-target checks; do **not** invoke `performClick()` — this ticket asserts structure, not callback wiring.
- No `performScrollTo()` needed unless the developer finds an assertion fails on a small emulator; the scanner screens fit one viewport.

State / concurrency model: none. The screens under test hold no `StateFlow` and no `viewModelScope` jobs; they're pure stateless composables.

Error handling: none — these are render assertions.

## Test cases (write these — bullet form, not full bodies)

**`ScannerScreenTest`:**

- `topAppBar_rendersPairWithPyrycodeTitle` — renders `ScannerScreen(onTap = {})`, asserts a node with exact text `"Pair with pyrycode"` exists.
- `hintCard_rendersPyryPairInstruction` — renders `ScannerScreen(onTap = {})`, asserts a node matching `hasText("pyry pair", substring = true)` exists (covers the `buildAnnotatedString` body).
- `pasteCodeFallback_hasClickAction` — renders `ScannerScreen(onTap = {})`, asserts the node matching `hasText("Trouble scanning?", substring = true)` carries a click action.

**`ScannerConnectingScreenTest`:**

- `connectingMessage_renders` — renders `ScannerConnectingScreen(serverAddress = "home.lan:7117")`, asserts a node matching `hasText("Connecting to your pyrycode server", substring = true)` exists. (Substring match avoids fragility around the `…` glyph.)
- `serverAddress_rendersVerbatim` — renders `ScannerConnectingScreen(serverAddress = "home.lan:7117")`, asserts a node with text `"home.lan:7117"` exists.

**`ScannerDeniedScreenTest`:**

- `heading_rendersCameraPermissionRequired` — renders `ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})`, asserts a node with text `"Camera permission required"` exists.
- `openSettingsButton_hasClickAction` — same render, asserts the `"Open settings"` node carries a click action.
- `pasteCodeButton_hasClickAction` — same render, asserts the `"Paste code instead"` node carries a click action.

The developer writes the Kotlin bodies in the project's testing idiom (matching `SettingsScreenTest` line by line). Use the same import set; do not pull in `performClick`, `hasTestTag`, or `onNodeWithContentDescription` unless a specific assertion above warrants it (none does).

## Testing strategy

- These ARE the tests. Run via `./gradlew connectedDebugAndroidTest` on a connected device/emulator — same channel as `SettingsScreenTest` and the #100 `WelcomeScreenTest`. AC requires all three test classes pass.
- No unit-test (`./gradlew test`) layer added; the Compose runtime needs the instrumented harness.

## Open questions

None. Pattern is established and the screens under test are stable.
