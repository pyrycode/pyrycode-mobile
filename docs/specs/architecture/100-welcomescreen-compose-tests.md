# Spec — Compose tests for WelcomeScreen (#100)

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — the canonical pattern: `createComposeRule()`, `setContent { PyrycodeMobileTheme { ... } }`, `@RunWith(AndroidJUnit4::class)`, `onNodeWithText` / `onNode(hasText(...))` assertions. Mirror its imports and structure.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:34-136` — screen under test. Public surface: `WelcomeScreen(onPaired: () -> Unit, onSetup: () -> Unit)`. Primary `Button` text "I already have pyrycode" wires to `onPaired`; secondary `TextButton` text "Set up pyrycode first" wires to `onSetup`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt` — second exemplar of the same pattern; useful if the developer wants to cross-check naming and imports.

## Design source

N/A — testing infrastructure per ticket body; no visual fidelity work.

## Context

WelcomeScreen is the onboarding entry point. ViewModel-free, two callbacks. Phase 1 stabilised the screen but added no Compose-level test. This spec adds a structural test so regressions in button labels, click wiring, or removal of either CTA fail loudly in CI rather than silently in manual inspection.

Split from #82 (broader onboarding test sweep); this child covers WelcomeScreen only.

## Design

One new file:

```
app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt
```

Class `WelcomeScreenTest`, annotated `@RunWith(AndroidJUnit4::class)`, with a `@get:Rule val composeTestRule = createComposeRule()`.

Helper inside the class (not a parameter — keep each test self-contained per the SettingsScreenTest idiom):

- A small `setContent` block per test that hosts `PyrycodeMobileTheme { WelcomeScreen(onPaired = ..., onSetup = ...) }`. Callbacks are instantiated as local counters (`var pairedCount = 0` etc.) inside the click-tests so each test owns its own state. No shared mutable test state across `@Test` methods.

### Test cases

Four `@Test` methods, one assertion focus per test (matches SettingsScreenTest's "one behaviour per test" style):

1. **`pairedCta_isDisplayed`** — sets content with no-op callbacks, asserts `onNodeWithText("I already have pyrycode").assertIsDisplayed()`.
2. **`setupCta_isDisplayed`** — sets content with no-op callbacks, asserts `onNodeWithText("Set up pyrycode first").assertIsDisplayed()`.
3. **`tappingPairedCta_invokesOnPairedOnly`** — local `var pairedCount = 0; var setupCount = 0`, performs `onNodeWithText("I already have pyrycode").performClick()`, asserts `pairedCount == 1 && setupCount == 0`.
4. **`tappingSetupCta_invokesOnSetupOnly`** — symmetric: tap "Set up pyrycode first", assert `setupCount == 1 && pairedCount == 0`.

### Matcher choice

- Use `onNodeWithText(label)` (exact match) — both labels are short and exact in the source. Substring matching is unnecessary and adds false-positive risk.
- Use `assertIsDisplayed()` for visibility, `performClick()` for tap. Both are in `androidx.compose.ui.test`.
- No `performScrollTo()` needed — WelcomeScreen is not a scrolling surface; both CTAs render in the visible viewport at all test densities.

## State + concurrency model

No ViewModel, no flow, no coroutines. Plain Compose UI test using `ComposeContentTestRule`. `createComposeRule()` (not `createAndroidComposeRule<Activity>()`) is sufficient because the screen has no Activity dependency.

## Error handling

Test-only file. Any failure surfaces as a JUnit assertion failure; no production error path involved.

## Testing strategy

- Runs via `./gradlew connectedDebugAndroidTest` (requires connected device or emulator). Same harness as SettingsScreenTest.
- Not runnable via `./gradlew test` (that's unit-test only; Compose UI tests need the instrumented runner).
- Developer should verify all four tests pass before marking the ticket green. AC explicitly demands `connectedDebugAndroidTest`.

## Open questions

None. The screen surface is small and the pattern is established.
