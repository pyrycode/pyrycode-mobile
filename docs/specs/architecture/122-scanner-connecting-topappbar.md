# Spec: Scanner Connecting — TopAppBar + spinner position (Figma 32:20)

Ticket: pyrycode-mobile #122
Size: S

## Context

`ScannerConnectingScreen` currently renders the spinner and captions vertically centred in a plain `Surface`, with no app bar. Figma 32:20 (the authoritative frame; text-style bindings were verified 2026-05-15) shows:

- The same `Pair with pyrycode` TopAppBar that `ScannerScreen` already renders (back arrow + title in `titleLarge`).
- The spinner sitting above the vertical centre at y≈384 within an 892-tall frame.
- The "Connecting…" caption on `onSurfaceVariant` (the current code uses `onSurface` — a color-role bug to fix).

This ticket aligns the screen with the pairing-flow chrome and Figma proportions. It does NOT wire the screen into the NavHost — see [Out of scope](#out-of-scope) below.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-20

Single column on `Schemes/Surface`: a transparent-container `TopAppBar` (height 64dp, back-arrow `IconButton` + `Pair with pyrycode` title in `titleLarge` on `onSurface`), then a body that places a 48dp `CircularProgressIndicator` at y=384 within the 892-tall frame (top=384, left=182 → horizontally centred), followed by the `bodyLarge` "Connecting to your pyrycode server…" caption at y=460 and the `bodyMedium` Roboto-Mono server-address caption at y=500 — both on `onSurfaceVariant`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreen.kt:25-83` — current implementation; this is the file you'll rewrite.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:50-96` — the canonical pairing-flow chrome (Surface → Column(systemBarsPadding) → TopAppBar(`Pair with pyrycode`, back arrow, transparent container) → weighted body). Mirror this exactly for the bar; the body diverges.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreenTest.kt` — existing instrumented tests; both `setContent` blocks need the new `onBack` parameter, and one new test is added (see [Testing](#testing)).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:99-233` — confirm what we already see: there is no `Routes.SCANNER_CONNECTING` entry. The screen is not currently navigated to. Do not add a route here; see [Out of scope](#out-of-scope).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography = Typography()`, so `titleLarge`, `bodyLarge`, `bodyMedium` resolve to standard M3 values matching Figma's `Static/Title Large`, `Static/Body Large`, `Static/Body Medium` tokens. No theme changes needed.

## Design

### Public signature

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerConnectingScreen(
    serverAddress: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The new `onBack: () -> Unit` parameter is required (no default) because the back affordance is now a mandatory part of the screen — callers must be explicit about back-navigation behavior. Match `SettingsScreen` / `LicenseScreen` / `ArchivedDiscussionsScreen` precedent (`onBack` is a required `() -> Unit` everywhere in this codebase).

### Layout structure

```
Surface(color = colorScheme.surface, fillMaxSize)
└─ Column(fillMaxSize, systemBarsPadding)
   ├─ TopAppBar  (mirrors ScannerScreen.kt:75-96)
   └─ Column(weight = 1f, fillMaxWidth, horizontalAlignment = CenterHorizontally)
      ├─ Spacer(weight = 316f)            ← (384 − 68) Figma gap above spinner
      ├─ CircularProgressIndicator(size = 48.dp)
      ├─ Spacer(height = 28.dp)           ← (460 − 432) Figma gap, spinner→caption
      ├─ Text("Connecting to your pyrycode server…")
      ├─ Spacer(height = 16.dp)           ← (500 − 484) Figma gap, caption→address
      ├─ Text(serverAddress)
      └─ Spacer(weight = 372f)            ← (892 − 520) remaining space below
```

**Why weighted spacers, not fixed dp.** AC2 requires a *proportional* offset so the position survives different device heights. Weighted spacers above/below the cluster split the available content area in the 316:372 ratio that Figma specifies, regardless of viewport height. Fixed gaps (28dp / 16dp) are kept inside the cluster because Figma specifies pixel-accurate spacing between spinner, caption, and address — those are not proportional.

**TopAppBar parameters** (lifted verbatim from `ScannerScreen.kt:75-96`):

- `title = { Text("Pair with pyrycode", style = MaterialTheme.typography.titleLarge) }`
- `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }`
- `colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colorScheme.onSurface, navigationIconContentColor = colorScheme.onSurface)`

The transparent container is intentional — it lets the parent `Surface(color = colorScheme.surface)` show through and matches `ScannerScreen`'s pattern. `@OptIn(ExperimentalMaterial3Api::class)` annotation is required on the function (same as `ScannerScreen`).

**Caption color-role correction.** The current `bodyLarge` "Connecting…" caption uses `MaterialTheme.colorScheme.onSurface`. Figma 32:20 specifies `onSurfaceVariant` (and AC3 requires it). Change the color role on this line; do not change the typography slot.

**Server-address caption.** Already correct in current code (`bodyMedium` + `FontFamily.Monospace` + `onSurfaceVariant`); no behavioural change required, but it will move because the surrounding layout changes.

### State + concurrency model

Pure stateless composable. No `viewModelScope`, no flows, no side effects. The screen reflects an "in-flight pairing" condition driven entirely by the caller (whoever eventually navigates to it). `onBack` is invoked synchronously from the IconButton click; cancellation of any pending pairing work is the caller's responsibility, not this screen's.

### Error handling

N/A — this screen has no failure modes. Real connection failures will surface on a separate "scanner failed" or "scanner denied" screen, not here. The "Connecting…" label is informational only.

## Testing

Update `ScannerConnectingScreenTest.kt`:

- Both existing `setContent` blocks: add `onBack = {}` to the `ScannerConnectingScreen(...)` call. The two existing assertions (`connectingMessage_renders`, `serverAddress_rendersVerbatim`) are unchanged.
- Add one new test, `backButton_invokesOnBack`: render with a captured `var backInvoked = false` lambda, find the node with `hasContentDescription("Back")`, `performClick()`, assert `backInvoked` flipped to `true`. This is the minimum-viable assertion that the new `onBack` wiring is connected to the IconButton.
- Add one new test, `topBarTitle_renders`: find a node with text `"Pair with pyrycode"` and assert it exists. Cheap regression guard against accidental title-text drift.

No assertions on spinner position (proportional layout is verified via `@Preview` visual review against Figma 32:20, not by instrumented tests — pixel-position asserts are brittle).

Both Light and Dark `@Preview`s at 412×892 must continue to compile and visually match Figma 32:20 (AC5). Update both preview bodies to pass `onBack = {}`.

## Out of scope

- **Nav-graph wiring.** `MainActivity.kt`'s `PyryNavHost` has no `Routes.SCANNER_CONNECTING` entry today, and the screen is not reached from any current navigation path. The ticket's Technical Notes section mentions "the navigation-graph call site needs to wire it to `navController.navigateUp()`" — but no such call site exists yet. Do **not** add a route, do **not** modify `MainActivity.kt`. When this screen is eventually wired into the post-scan flow (a future ticket), the caller will pass `onBack = { navController.popBackStack() }` matching the existing pattern at `MainActivity.kt:209` / `MainActivity.kt:215` / `MainActivity.kt:225`. The required parameter on the composable is the forward-compatibility hook; the wiring is a separate concern.
- Any change to `ScannerScreen` → `ChannelList` flow.
- Animations on the spinner (M3 `CircularProgressIndicator` default animation is sufficient).
- Cancellation / timeout behavior of pairing — this screen is presentation-only.

## Open questions

None blocking. The "y≈384/892" proportional ratio in AC2 is interpreted as "Figma's body-area split", implemented with weighted spacers above and below the spinner+caption cluster (316:372). If a future visual review flags a small offset vs Figma at non-892dp viewport heights, the ratio is the place to adjust; the cluster's internal 28dp/16dp gaps are pixel-accurate per Figma and should not be the first tuning knob.
