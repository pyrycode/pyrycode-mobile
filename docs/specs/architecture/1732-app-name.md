# #1732 — Name the app Pyrycode

## Files read

- `app/src/main/res/values/strings.xml` — `app_name`, used by both manifest labels and the notification fallback.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — `WelcomeScreen` title.
- `app/src/main/AndroidManifest.xml` — application and launcher activity labels reference `app_name`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` — `referenceHeroStartsAtInsetAdjustedPosition` selects the title.
- `app/src/sharedTest/java/de/pyryco/mobile/MainActivityInsetsTest.kt` — `welcomeConsumesChangingSystemBarsExactlyOnce` exercises the real activity and title.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` — `aBackgroundedTurnPostsOneFixedCopyNotificationWhoseTapOpensThatThread` checks fallback naming.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — unpaired title selectors in `exercise` and `lightAndroidModeKeepsLightSystemBarIcons`; paired selectors refer to the demo channel.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignHarnessSmokeTest.kt` — `compactLargeTextLaunchCapturesRealFrame` selects Welcome.
- `app/src/androidTest/java/de/pyryco/mobile/design/OnboardingDesignCaptureTest.kt` — `scannerFramesAt412By892` selects Welcome; `rePairFramesAt412By892` selects the demo channel.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeAppearanceDeviceTest.kt` — `capture` selects the title and saves real framebuffer pixels at reference, compact and enlarged-text sizes.
- `docs/knowledge/features/welcome-screen.md` — preserve existing typography, glow, body wrapping and action placement; ATD captures do not prove real pixels.
- `app/src/androidTest/assets/welcome-1212/capture-context.txt` — existing real-device capture command and metadata conventions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Inspected 2026-10-04: a left-aligned logo and “Pyrycode” title above the tagline and body, with bottom filled and text-button CTAs over a dark blue glow. The title uses M3 `headlineLarge` / `onSurface`; the existing logo, glow, spacing and other text remain as rendered.

## Change

Replace “Pyrycode Mobile” with “Pyrycode” in `app_name` and the Welcome title. Update only test selectors referring to Welcome, and pin the resolved application/launcher labels and notification fallback to the literal product name. Keep the package id and demo channel name intact. Retain fresh reference/compact/enlarged-text captures under `app/src/androidTest/assets/welcome-1732/`; older ticket captures are historical evidence, not executable screenshot assertions. Overlap: #1642 adds an unrelated string at the end of `strings.xml`; this edit changes only `app_name`. Forecast: under 150 written text lines including plan and capture metadata, no new exported declarations, no signature changes, two acceptance criteria, no new failure branches.

## Testing strategy

Update existing Welcome/title assertions first and observe focused failures before changing production. Run `WelcomeScreenTest`, `MainActivityInsetsTest` (including resolved manifest label assertions), `AttentionNotifierTest` (literal fallback assertion), and `ChannelListScreenTest` (existing `app_name` absence coverage). Run existing `WelcomeAppearanceDeviceTest` on full `pixel8Api35` with `requireRealSystemBars=true` for its three sizes, inspect fresh XML counts and compare the reference capture to Figma; real framebuffer pixels require a device. Compile instrumentation tests to check all renamed selectors, then run lint, assembleDebug, spotlessApply and forced spotlessCheck. The rename adds no daemon-facing interaction or real-Claude scenario.
