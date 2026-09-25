# #1141 — Pair with code: respect system-bar insets

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen` — the main `Column` applies only `imePadding()`; the `Confirming` branch returns `ScannerScreen` before that `Column` is composed. The one file that changes.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` → `ScannerViewport` — the reference: `Surface` fills the window, its inner `Column` applies `systemBarsPadding()`, header `Row` uses the same 18 dp top padding as pair-with-code.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `enableEdgeToEdge()` in `onCreate`; the `PAIR_CODE_ROUTE` destination calls `PairCodeScreen(state, vm::onEvent)` with no inset padding from the outer `Scaffold`, same as the scanner destination.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` → `softwareKeyboardKeepsBothFieldsClearControlsAndActionsReachable` — the IME contract that must stay green (AC 2).
- `docs/knowledge/features/scanner-screen.md` — scanner layout; its viewport applies `systemBarsPadding()`.
- `docs/knowledge/features/development-verification.md` § "Where a screen test goes" — the new test goes in `app/src/sharedTest` under Robolectric.

No in-flight branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147 (scanner reference: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2)

Both 412 × 892 frames put the Back arrow and "Pairing" `titleLarge` title in the same place, above an `inversePrimary` divider. Pair-with-code keeps its `surface` ground and radial `primaryContainer` atmosphere under a column of two filled text fields, then Pair, Cancel and the open-source footer. The frames have no system bars, so on a device both screens place their chrome inside the system-bar insets.

## Change

In `PairCodeScreen`, the main `Column` becomes `fillMaxSize().background(surface).systemBarsPadding().imePadding()`. The background stays before the padding, so the surface still fills the window edge to edge the way the scanner's `Surface` does. `systemBarsPadding()` moves the header below the status bar and keeps Cancel and the footer above the navigation bar. `imePadding()` comes after it, so it adds only the keyboard height beyond the navigation bar that `systemBarsPadding()` already consumed. The `Confirming` branch returns before this `Column` and renders `ScannerScreen` unchanged, so it does not get a second inset. Nothing else moves.

## Testing strategy

- **New shared screen test** `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenInsetsTest.kt` (Robolectric, `@RunWith(AndroidJUnit4::class)`). The host activity is put edge to edge, and fixed nonzero status-bar and navigation-bar insets are applied to its window, so the test does not depend on what Robolectric reports. It renders `ScannerScreen(ReadyToScan)`, records the top of the Back button, and switches the same content to `PairCodeScreen`. It then asserts:
  - pair-with-code's Back top equals the scanner's Back top, within 1 px;
  - the Back top is at or below the status-bar inset;
  - the bottoms of Cancel and the open-source footer are at or above the root height minus the navigation-bar inset.
  RED on the current `main`: Back sits at the 18 dp padding, above the status-bar inset.
- **AC 2:** focused device run of `PairCodeScreenTest#softwareKeyboardKeepsBothFieldsClearControlsAndActionsReachable` on `pixel2Api33Atd`, with that test unchanged.

## Documentation handoff

Pending for the documentation stage: the pair-with-code topic, or `scanner-screen.md` if it covers the paste path, should state that pair-with-code applies `systemBarsPadding()` before `imePadding()` and matches the scanner's header position. The ticket names no specific document.
