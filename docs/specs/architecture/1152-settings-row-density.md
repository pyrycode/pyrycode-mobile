# Settings row density (#1152)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` — `SettingsRow`, `SettingsScreen`, `AddPill`: shared ordinary rows and their controls.
- `app/src/main/java/de/pyryco/mobile/ui/settings/AboutScreen.kt` — `AboutScreen`: extracted content and shared row consumer.
- `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt` — `HostIdentityRow`, `BoundedLine`: separate layout and bounded identity to preserve.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` and `Theme.kt` — `AppTypography`, `PyrycodeMobileTheme`: M3 bodyLarge/bodySmall and surface roles already exist.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` and `AboutScreenTest.kt` — existing callback, inert-row, host and text assertions.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — display setup, restoration and retained nonblank device screenshots.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — shared tests, full-image managed device and existing Compose dependencies.
- `docs/knowledge/features/settings-screen.md`, `settings-screen-how-it-works.md`, `settings-screen-previews-and-edge-cases.md`, `about-screen.md` — extracted About content, nullable archive/log callbacks, independently clickable host rows.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes / Compose evidence — native text measurement; ATD framebuffer can be black, so full-image captures prove pixels.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

Read the Settings frame and its screenshot, plus Theme `17:25` and wallpaper `17:31` context. Rows are full-width horizontal layouts with 16 dp side padding and trailing gap; text uses M3 bodyLarge over bodySmall with a 2 dp gap and 10 dp vertical padding. Surface/onSurface/onSurfaceVariant tokens and existing M3 switches/chevrons reproduce the reference; the ticket deliberately raises tappable single-line rows from 44 to 48 dp.

## Change

Replace only `SettingsRow`'s minimum-height-enforcing `ListItem` with a content-sized Row. Keep its signature and all call sites, callbacks, control semantics and resources. Use a weighted text column with 10 dp vertical padding, a 2 dp interline gap, explicit bodyLarge/bodySmall typography and existing theme colors. Keep trailing content centered, independently measured, separated by 16 dp and padded vertically by 2 dp: a native 48 dp switch target then yields a 52 dp row, while two-line text yields 62 dp even beside a switch. A row click imposes a 48 dp minimum; inert text stays 44 dp. Heights are minima/content measurements, never fixed, so wrapped text grows. Merge ordinary text semantics while preserving the independently interactive switch node, as ListItem does.

HostIdentityRow, bounded identity text, owner badge, status legs, About contents, Log data, scroll containers and system insets retain their current treatment. No new state, jobs, errors, dependencies or logging events: this is a stateless layout retune, with existing feature lifecycle logging unchanged.

Sizing: one deliverable, one production file, approximately 550 written lines including tests/captures and this plan, no exported declarations, no consumer signature updates, three ACs and no new reject branches. This exceeds the refiner's 350-line estimate because real screenshot capture needs a device fixture, but remains within all six one-ticket limits. Refreshed all remote feature branches: no overlaps on the existing files this design touches.

## Testing strategy

- RED then GREEN in a new shared `SettingsRowLayoutTest`: at 412 dp, assert 62/52/48/44 dp row bounds, bodySmall's 12 sp / 16 sp layout, switch target/role/state/callback and inert-row semantics. Long headlines/subtitles at font scales 1 and 2 must wrap, grow, remain within their row and stay clear of trailing controls.
- Run existing `SettingsScreenTest` and `AboutScreenTest` for preserved consumers. Extend focused consumer coverage if needed for scrolling at large font and host/status preservation.
- Add `SettingsDensityDeviceTest` under androidTest because AC3 requires real saved pixels. Render the production Settings and About composables with a synthetic non-secret host, dark theme and wallpaper colors off; capture top/lower/About and large-font views, recording width, density, font scale and row bounds. Use the full `pixel8Api35` image for nonblank screenshots and retain PNGs/measurements under `app/src/androidTest/assets/settings-density-1152/`. Run this one class and inspect fresh XML counts; ATD runs may prove geometry but cannot substitute blank screenshots.
- Run scoped unit tests, Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. No new daemon flow is introduced, so no new real-Claude scenario applies; dispatcher owns full regression gates.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/settings-screen-how-it-works.md` § Same-file composables (`SettingsRow`) and `docs/knowledge/features/about-screen.md` § Shared / moved helpers to describe content-sized rows and bodySmall subtitles. No documentation-only AC was supplied.
