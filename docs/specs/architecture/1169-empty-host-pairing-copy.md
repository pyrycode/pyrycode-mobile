# Empty host pairing guidance (#1169)

## Files read

- `app/src/main/res/values/strings.xml` — `channel_list_empty` is the only production edit.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — `ChannelListScreen`, `ChannelListTopBar` and `CenteredText` show the placeholder below the always-present Settings gear.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost` and `Routes.settings` keep Settings reachable with no selected host.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` — `SettingsScreen` exposes Pair another server independently of host rows.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — `emptyState_rendersPlaceholder_whenThereAreNoHosts` covers the empty draw; the bar and Settings event already have regression tests.
- `docs/knowledge/features/channel-list-screen.md` — What it does: zero hosts is the only blank tree, including while awaiting snapshots.
- `docs/knowledge/features/settings-screen.md` — What it does: Pair another server opens the existing scanner.
- `docs/knowledge/features/development-verification.md` — Where a screen test goes: the existing shared test runs under Robolectric.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The design context and screenshot show a vertical list on a dark Schemes/Surface background, with Settings and archive icons above a thin divider and nested host/workspace rows. There is no dedicated empty variant; preserve the existing Compose scaffold and Settings entry, changing only the placeholder text.

## Change

Replace “Tap + to start a conversation” with “To pair a host, open Settings and choose Pair another server.” This names the available route without asserting that no host is paired during an initial snapshot wait. No layout, navigation, state, concurrency, error handling or logging changes are needed for this resource correction.

Sizing: one deliverable and one acceptance criterion; one resource line, zero production Kotlin files, approximately 45 total written lines including this plan and the test adjustment, zero new exported declarations, zero changed consumer signatures, zero error branches. This agrees with the refiner's XS production estimate. Refreshed all remote feature branches; none overlaps the resource or test file.

## Testing strategy

Strengthen the existing empty-state test with the literal guidance, absence of the retired instruction, and a Settings tap emitting `SettingsTapped`. Run that method RED before the resource edit, then the affected `ChannelListScreenTest` class GREEN. Run Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. Existing layout and assets stay intact; compare the preserved top bar to the fetched frame. This corrects copy on an existing flow and introduces no operator interaction or daemon behavior requiring a new live scenario.

## Documentation handoff

The ticket specifies no documentation requirement. Pending for the documentation stage: update the quoted `channel_list_empty` wording in `docs/knowledge/features/channel-list-screen.md`, section “What it does”.
