# 1238 — Always use the static dark palette

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `MainActivity.onCreate` — the sole application-root theme selection and startup preference injection.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme` — `darkTheme = true` and `dynamicColor = false` select the existing static dark scheme and derived colors.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `themeMode`, `useWallpaperColors` — saved values are independent flows and remain available to Settings.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` → `prepare`, `launch`, `capture` — real-activity, 412 × 892 device setup and screenshot pattern.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SharedDarkColourCaptureTest.kt` → `sidebarAt412By892` — existing static-dark Figma fixture and comparison baseline.
- `docs/knowledge/features/app-preferences.md` § What it does — currently describes saved appearance values as runtime theme selectors; documentation stage updates this contract.
- `docs/knowledge/features/development-verification.md` § Device gate — full emulator pixels are needed for a screenshot, since the ATD may yield a black framebuffer.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Node `15:8`, inspected 2026-09-28: a 412 × 892 dark channel list with a near-black canvas, blue atmospheric glow, dark sidebar scrim, pale text and blue selected row. The root must keep the static dark `Schemes/*` roles already supplied by `PyrycodeMobileTheme`; this ticket does not redesign the channel list.

## Change

In `MainActivity.onCreate`, call `PyrycodeMobileTheme` with `darkTheme = true` and `dynamicColor = false`. Remove only the root's theme/wallpaper collectors and system-mode decision. Keep `AppPreferences`, its stored values and Settings behavior intact; the same injected preferences instance is still used for workspace migration. This uses the existing static palette for all root destinations on every launch and after a saved preference changes.

## Testing strategy

- Extend the real-activity device test with a focused 412 × 892 case that seeds legacy Light, System and wallpaper choices, toggles Android night mode, checks the dark pixel and unchanged stored values, and captures the rendered view. Run it red before the production edit, then green on the managed full emulator.
- Preserve a current render of Figma node `15:8` and create a labelled emulator/Figma/difference comparison under `app/src/androidTest/assets/` with the device result and capture context. Review in-scope palette samples against the existing static dark scheme; record the inspected node and date in the PR.
- Run focused device execution, lint, `assembleDebug`, `compileDebugAndroidTestKotlin`, and `spotlessApply`. The dispatcher runs full suites later.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/app-preferences.md` under “What it does” to state that saved appearance values remain stored but no longer select the runtime palette.
