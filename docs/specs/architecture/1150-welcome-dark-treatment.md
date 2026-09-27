# Welcome dark treatment (#1150)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — `WelcomeScreen`, its two previews: local drawing, layout and callbacks.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` — display and callback isolation coverage.
- `app/src/main/res/drawable/ic_pyry_logo.xml` — existing single filled path and 92 × 104 dp viewport mapping; reuse unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` and `Theme.kt` — `primaryContainerDark`, `onPrimaryDark`, `PyrycodeMobileTheme`: existing gradient/text roles.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost` and activity Scaffold consume shared insets; preserve that ownership.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — real-activity capture and display restoration pattern.
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — existing Compose shadow APIs and full `pixel8Api35` image; no new dependency.
- `docs/knowledge/features/welcome-screen.md` — How it works and Edge cases / limitations contain the superseded rectangular-shadow decision.
- `docs/knowledge/features/development-verification.md` — Compose evidence requires real system bars and nonblank activity pixels.
- `docs/specs/architecture/168-welcome-logo-m3-elevation.md` — earlier logo-only scope and two Figma shadow layers.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Read current design context and screenshot: a left-aligned hero above a bottom CTA stack, on `Schemes/Surface`, with a narrow tilted blue ellipse behind the logo and introduction. Existing M3 headlineLarge/titleMedium/bodyLarge typography stays intact; the body has a 320 dp measure and the secondary action uses `Schemes/On Surface`. Hero `6:33` uses two mark-following shadows: black at 30%, offset (0,1), blur 3, spread 0; black at 15%, offset (0,4), blur 8, spread 3.

The gradient's radius-10 transform is `matrix(28.4 -2.6 4.3038 47.011 196 265)` in the 412 × 892 reference. Stops are opaque primaryContainer at 0 and transparent onPrimary at 0.7. Scale this reference geometry to the available drawing area, retaining rotation and independent axes.

## Change

Replace the circular gradient with a cached shader carrying the exact affine transform and stops, using existing theme roles. Replace the rectangular elevation with two Compose drop shadows whose shape is derived from the existing vector's single path, scaled to its viewport; retain the existing painter, primary tint and 92 × 104 dp slot. Use the theme scrim role for shadow color. Cap only the body at 320 dp using constraint-respecting width, keeping its left alignment. Set TextButton content color to onSurface. Insets, copy, typography, navigation and other screens stay as they are.

This is one visual correction, estimated at roughly 350 written lines including capture tests and plan, one production file, no exported types, no signature/caller changes, three acceptance criteria and no new error branches. No in-flight feature branch overlaps the planned files. Codegraph found Welcome entry points but no callees or existing dropShadow pattern; source reads fill that gap.

No state, jobs, I/O boundaries or classified lifecycle events are introduced in production, so no new logging or concurrency machinery is appropriate.

## Testing strategy

- RED then GREEN: shared Welcome tests assert a 320 dp body at 412 dp and available-width shrinkage at 360 dp, plus onSurface setup text. Preserve all four display/callback assertions.
- Add a focused device capture test for Welcome using real `MainActivity`, dark mode, physical system bars, 160 dpi, and 412 × 892 / 360 × 800 dp display overrides, restored afterward. Device-only because it uses UiAutomation screenshots and shell display configuration. Check body/action reachability, record density and insets, reject blank captures, and keep PNG/metadata under `app/src/androidTest/assets/welcome-1150/` after execution.
- Compare fresh captures with the current Figma render for ellipse extent/fade, silhouette shadow, text color/measure and small-width reachability. Record revision and findings in the PR. No daemon or real-Claude scenario is required for this visual correction.
- Run focused shared tests, focused full-image capture class, Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. The dispatcher owns the full regression suites.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/welcome-screen.md` § How it works and § Edge cases / limitations to describe the corrected glow, mark-following shadow and text measure; replace the claim that the rectangular shadow is tolerable.
