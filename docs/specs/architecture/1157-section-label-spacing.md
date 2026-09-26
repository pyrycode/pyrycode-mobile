# #1157 — Conversation-list section-label spacing

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — `ChannelListTopBar`, `ConversationTree`: the two rule-bottom gaps to adjust.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` — `TreeSectionHeader`: centres `labelLarge` in a minimum 48dp row, with a 48dp add control.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — `sectionHeaders_eachCarryTheirOwnPairingControl`, `listBar_drawsBothEntriesAndNoneOfTheRetiredChrome_onEveryDraw`: existing screen coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt` — `sectionHeader_rendersTitleAsHeading`, `everyTappableRow_meetsTheMinimumTouchTargetHeight`: existing component coverage.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — `activityAt412By892`: existing real-device list capture.
- `docs/knowledge/features/channel-list-screen.md`, `channel-list-screen-how-it-works.md` § “The list's own top bar”, and `channel-list-screen-tree-and-controls.md` § “Add controls” — retain the touch adaptation; the historical 12dp header-slack arithmetic is stale.
- `docs/knowledge/features/development-verification.md` § “Where a screen test goes” and “Compose evidence” — focused shared tests and full-image visual capture.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The design context and screenshot show a vertical sidebar, with thin horizontal rules and 28dp to the next section's 20dp label box. Preserve the existing M3 `HorizontalDivider`, `labelLarge` text and themed colours, plus the phone's 48dp touch targets and persistent add controls.

## Change

Set `BarBottomGap` from 28dp to 14dp and `TreeSectionRuleBottomGap` from 16dp to 14dp. Each combines with the current header's `(48 - 20) / 2 = 14dp` inner slack to place the label 28dp below its preceding rule at normal font size. Keep `TreeSectionHeader`'s minimum height, text sizing and larger-font growth intact. This is a literal spacing correction, with no new state, types, failure modes, logging events or daemon flow. System-bar work remains owned by #1149. No in-flight feature branch overlaps the affected source file after fetching origin.

Size check: one deliverable, one production file, approximately four production lines and 40 total written lines including this plan, zero exported types, zero consumer changes, one acceptance criterion and zero error branches; within all six limits.

## Testing strategy

No new logic or new test is needed. Run existing `ChannelListScreenTest` and `ConversationTreeRowsTest`, then Spotless, lint and `assembleDebug`. Compare the existing `MainActivityInsetsDeviceTest.activityAt412By892` list capture on `pixel8Api35` against Figma; its full image provides real pixels. No new live-Claude scenario is needed for a spacing-only correction. The dispatcher owns the full regression gates.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/channel-list-screen-how-it-works.md` § “The list's own top bar” / “Known spacing gap” to replace the stale 12dp-header-slack explanation with the current 14dp centred-header slack and mark the spacing gap resolved by #1157. The ticket has no separate documentation acceptance criterion.
