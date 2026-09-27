# Dark sidebar panel and rules

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — `ChannelListScreen`, `ChannelListTopBar`, `ConversationTree`: one Scaffold paints behind both transparent children; two rules own their colours.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` and `Color.kt` — `PyrycodeMobileTheme`, `surfaceDark`, `scrimDark`, `inversePrimaryDark`: existing role tokens supply every colour.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — selection, navigation, controls and connection-indicator regression coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt` — native Robolectric view drawing pattern for colour assertions.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-how-it-works.md` — the list owns its bar; the outer activity owns system-bar insets; retain existing touch-target and icon adaptations.
- `docs/knowledge/features/development-verification.md` — shared screen tests and native pixel assertions; scoped Gradle verification.
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — existing Compose and Robolectric dependencies suffice.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8 — sidebar `133:259`, panel `I133:259;103:2959`.

The fetched sidebar context and screenshot show a dark column with settings/archive controls sharing the panel fill, two thin blue-grey rules, and distinguishable blue selected rows. The panel overlays black at 30% on `Schemes/Surface`; both rules use `Schemes/Inverse Primary` at 60%. Existing typography, icons and spacing remain outside this colour correction.

## Change

Set only the list Scaffold's container to the current scheme's scrim at 30% composited over its surface when that surface is dark. Determine darkness from the active surface's luminance, so explicitly selected app themes and previews work independently of the system setting. Keep the light surface unchanged. Share a private composable colour helper between both dividers, choosing inversePrimary for dark surfaces and the existing outlineVariant for light, retaining 60% alpha. The transparent top bar inherits the same Scaffold background. No theme, system-bar, row, modal, state, concurrency, failure or logging contracts change.

Sizing: one deliverable, one production file, approximately 25 production lines plus 170 test lines and this plan (under 250 total); no exported types, no signature migration, two acceptance criteria, zero error branches. The ticket's 15-line/two-file forecast is the same XS colour-only scope. Remote feature branches were refreshed; none overlap the production file. Codegraph mapped the three entry points but returned no callees, so direct source reading confirmed their structure.

## Testing strategy

Add a shared native-rendering `ChannelListColoursTest`: assert dark panel and top-bar fill against the reference, both rules against the expected blue-grey, selected-row contrast, and unchanged light fill/rules. Include an empty-list dark case. Run it RED before implementation and GREEN afterwards, then existing `ChannelListScreenTest` for controls/navigation/selection/connection behaviour. Inspect the rendered dark/light fixtures against the Figma screenshot. Run Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. This adds no operator flow or daemon interaction, so no new live-Claude scenario is required; full regression/device gates remain dispatcher-owned.

## Documentation handoff

No documentation requirement is named by the ticket. Pending for the documentation stage: update `docs/knowledge/features/channel-list-screen-how-it-works.md`, section “The list's own top bar (#737)”, to describe the local dark panel and inversePrimary rules, retaining the light treatment.
