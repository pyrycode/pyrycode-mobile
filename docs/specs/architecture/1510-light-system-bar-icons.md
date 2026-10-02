# #1510: keep status-bar icons light on a light-mode phone

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `MainActivity.onCreate`, which calls `enableEdgeToEdge()` with its default `SystemBarStyle.auto` for both bars, then draws `PyrycodeMobileTheme(darkTheme = true)`.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt`: `savedAppearanceAndAndroidModeCannotChangeStaticDarkPalette`, which already flips the device's night mode with `cmd uimode night` and restores it in `restore`; the new test reuses that setup, `launch` and the startup-store override.

No other code sets bar appearance (no `isAppearanceLight*`, `statusBarColor` or `windowLightStatusBar` under `app/src/`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The frames do not draw the status bar. The reference is the static dark canvas the thread sits on, which needs light status-bar and navigation-bar icons in every Android mode.

## Change

`MainActivity.onCreate` passes `SystemBarStyle.dark(Color.TRANSPARENT)` as both `statusBarStyle` and `navigationBarStyle` to `enableEdgeToEdge`. `SystemBarStyle.auto` picks icon colour from the phone's own night mode, so a light-mode phone gets dark icons on the app's always-dark canvas. `dark` never requests light bar appearance, and the transparent scrim keeps the edge-to-edge drawing the inset tests rely on. Nothing else moves: insets, theme and per-screen layout are unchanged.

## Testing strategy

New method `lightAndroidModeKeepsLightSystemBarIcons` in `MainActivityInsetsDeviceTest`. It sets `cmd uimode night no`, launches `MainActivity` unpaired (welcome) and paired (channel list), confirms the activity's configuration is night-no, and asserts `WindowInsetsControllerCompat.isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars` are both false. Before the change, `auto` sets both to true in light mode, so the test is red first.

Device-only reason: the ticket asks for an instrumented test, and changing the system's night mode needs the device shell (`cmd uimode night`), which this class already drives and restores.
