# Settings row density evidence

Captured on 2026-09-27 from the recovery change on top of main `28f056bb`.
The build label in the screenshots identifies that base commit. The tested
working tree includes this pull request's SettingsRow change.

## Source and display

The current [Figma Settings frame](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2)
and its rendered screenshot were read through the desktop Figma connector.
The ordinary rows reuse the existing theme, chevrons, external-link vector,
buttons and switches. No downloaded design asset is used in production.

The full Android 15 image ran on the dedicated emulator-5580. Display override
1080 × 2340 pixels at 420 dpi is 411.43 × 891.43 dp, the original audit's nominal
412 dp configuration. Font scales are 1.0 and 2.0. Theme is dark and wallpaper
colours are off. Each image is an actual device framebuffer capture.

The fixture renders production SettingsScreen and AboutScreen with a synthetic
host. It matches MainActivity's edge-to-edge window, resize policy and consumed
outer system insets. It does not exercise network transport or pairing.

## Measurements and visual inspection

At font scale 1.0, Theme, Default YOLO and About Version measure 162 pixels,
within one pixel of 62 dp. The wallpaper switch row is 136 pixels, within one
pixel of 52 dp. Pair another server and Privacy policy are 126 pixels, exactly
48 dp. Inert License is 116 pixels, within one pixel of 44 dp.
See the per-scale text files for full row bounds.

Normal top, defaults, lower Settings and About captures were inspected against
Figma. Supporting text is visibly subordinate. Ordinary rows have the intended
spacing. Tappable plain rows retain the ticket's 48 dp accessibility adjustment
to the design's 44 dp. Longer notification and About link text wraps naturally.

At font scale 2.0, ordinary rows grow without text/control overlap. Lower Settings
and the final About License row remain reachable. The unchanged host row still
ellipsizes its bounded identity and keeps its owner badge and both status dots.
Its separate Pyrycode Connected status text wraps awkwardly at this scale.
HostIdentityRow and ConnectionStatusLine have no source changes in this PR;
that visible limitation is outside the shared ordinary-row adjustment.

## Verification

- The corrected 412 dp JVM fixture failed against the old implementation:
  subtitle row expected 62 dp, actual 72 dp. The baseline XML is retained here.
- 30 focused JVM tests pass across SettingsRowLayoutTest, SettingsScreenTest and
  AboutScreenTest. Checks include 12 sp / 16 sp supporting typography, row
  geometry, wrapping at both scales, callbacks, inert rows and switch semantics.
  Touch area checks use touch bounds, not the switch's smaller painted track.
- The retained device XML records four passing tests across the new geometry
  and screenshot classes. Both run on the real Android image.
- Formatting, debug lint, debug APK and Android test APK builds passed.

Device reproduction after booting only the dedicated audit emulator:

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.settings.SettingsDensityDeviceTest,de.pyryco.mobile.ui.settings.SettingsRowLayoutTest \
  -Pandroid.testInstrumentationRunnerArguments.captureSettingsPixels=true \
  --no-daemon --max-workers=2
```

The tests restore display size, density and font scale after each run. This is
focused row and screen coverage. It is not a full accessibility, transport,
landscape or all-theme regression audit.
