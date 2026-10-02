# #1545 — Launch splash shows the snowflake on the dark background

## Files read

- `app/src/main/res/values/themes.xml` — `Theme.PyrycodeMobile.SplashScreen`, whose `windowSplashScreenBackground` points at `@color/ic_launcher_background`.
- `app/src/main/res/values/colors.xml` — `ic_launcher_background` (#FF32628D), which the launcher icon keeps; a new `splash_background` goes beside it.
- `app/src/main/res/drawable/ic_splash_logo.xml` — the placeholder white circle on the 288 dp canvas, redrawn here.
- `app/src/main/res/drawable/ic_pyry_logo.xml` — source of the snowflake path (viewport 91.002 × 103.812).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` — `backgroundDark` (#101418), the value the splash background copies.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=701-5001 (icon spec `701:5005`)

A 412 × 892 frame filled `Schemes/Background` (#101418) with the 92 × 104 "pyrycode mark" frame at (160, 394), exactly centred. Inside it the vector group is `ic_pyry_logo`'s path at native size (91.002 × 103.812), inset 1.08 % from the left and 0 from the top, filled #7AB8E8 (the brand glacier blue the launcher icon #1546 uses, an unbound literal rather than a scheme token), and drawn flipped vertically (`-rotate-180 -scale-x-100`).

## Change

`colors.xml` gains `splash_background` = #FF101418, and the splash theme's `windowSplashScreenBackground` points at it instead of `ic_launcher_background`. `ic_splash_logo.xml` keeps its 288 dp canvas and replaces the circle with `ic_pyry_logo`'s path inside one `<group>` that places the mark's 92 × 104 frame centred on (144, 144): frame origin (98, 92), plus Figma's 0.998 dp left inset, so `translateX` 98.998 and `translateY` 92, with `scaleY` −1 about `pivotY` 51.906 to reproduce Figma's flip. Fill is a new `splash_mark` colour (#FF7AB8E8) beside `splash_background`, not a theme attribute, because splash drawables resolve outside the app theme. The 104 dp mark sits inside the 192 dp safe area. `ic_launcher_background`, the launcher foreground and the mipmaps are not touched, which is the "launcher icon unchanged" criterion.

## Testing strategy

A Robolectric screen-layer test, `SplashResourcesTest` under `app/src/sharedTest/java/de/pyryco/mobile/`, pins the contract: `R.color.splash_background` equals `backgroundDark`, `windowSplashScreenBackground` in `Theme.PyrycodeMobile.SplashScreen` resolves to that colour, `R.color.splash_mark` is #FF7AB8E8, and `R.color.ic_launcher_background` stays #FF32628D. The visual criterion (cold launch on the managed emulator matching `701:5001`) is checked by capture against the Figma screenshot where an emulator is available and recorded in the PR; no device-only test is added, because the splash window is system-drawn and a test would only re-assert the resources above.

## Documentation handoff

- `docs/knowledge/features/splash-screen.md`, sections "What it does", "Theme" and "Drawable": pending for the documentation stage. Describe `splash_background` (#101418) as the splash background, `ic_splash_logo` as the flipped `ic_pyry_logo` mark at 92 × 104 dp filled #7AB8E8 centred on the 288 dp canvas, and drop the "placeholder" and "`ic_pyry_logo` is not reused" wording.

## Revisions

### 2026-10-02 — mark fill is #7AB8E8, not #9DCBFC

The first plan read the mark's fill in Figma `701:5001` as `Schemes/Primary` (dark), #9DCBFC, and departed from the issue without saying so. The verifier's review on PR #1547 sampled the Figma render: the mark is #7AB8E8, an unbound literal, which is what the issue specifies. The new contract: `colors.xml` names the mark colour `splash_mark` = #FF7AB8E8 beside `splash_background`, `ic_splash_logo` fills from `@color/splash_mark`, and `SplashResourcesTest` asserts its value. Geometry, flip, background and the untouched launcher icon are unchanged.
