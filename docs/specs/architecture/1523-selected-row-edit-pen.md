# #1523 — Edit pen only on the selected conversation row; selected and pressed fills per 15:8

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` — `TreeConversationRow`: the `fill` selection and the `onEditTapped` pen.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — the conversation `itemsIndexed` block in the host tree, which builds `target`, compares it with `hostState.selected` and binds `onEditTapped`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — `selected` is `lastOpenedTarget`, so opening a row and pressing Back selects it.
- `app/src/sharedTest/.../list/ChannelListScreenTest.kt`, `app/src/sharedTest/.../components/ConversationTreeRowsTest.kt`, `app/src/androidTest/.../list/SidebarTreeCaptureTest.kt`, `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` (`openChannelEditor`, `setMuteInEditChannel`, `openRow`, `leaveThread`) — every pen tap on a row.

Overlapping in-flight branches, additive only: #1521 and #1522 (`ChannelListScreen.kt` constants and canvas), #1496 (`InteractiveStreamE2ETest.kt`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The Pyry Channels list draws one row in the `Channel` component's `Hover` variant (node `398:7258`): a rounded `Schemes/Primary Container` (`#134a74`) fill with the edit pen at the trailing edge. A second row, `pyrycode discord integration`, draws the darker `Schemes/On Primary` (`#003355`) fill and no pen. Every other row is unfilled and has no pen. Mobile reads Hover as *selected*, because a phone has no hover, and the darker row as *pressed*.

## Change

In `ChannelListScreen`'s conversation item, `onEditTapped` becomes null unless the host is connected **and** `target == hostState.selected`. The row is unchanged in that respect: it still draws a pen whenever it is given one, and the caller decides which row gets one, as its KDoc already says (the KDoc is updated to name the selected-only rule). In `TreeConversationRow`'s `fill`, the static dark palette swaps its two fills: selected draws `primaryContainer` and pressed draws `onPrimary`. The non-static branch keeps `primaryContainer.copy(alpha = SELECTED_FILL_ALPHA)` for selected, and its pressed fill stays `primaryContainer`. #1336's disconnected-host suppression stays as it is, and host rows are not touched.

Edit channel and Edit chat stay reachable by opening the row, pressing Back and tapping the pen.

## Testing strategy

- `ChannelListScreenTest` (shared, Robolectric):
  - New: with two connected rows and one selected, only the selected row has a pen; tapping it sends `TreeChannelEditTapped`/`TreeChatEditTapped` for that row's target. Changing `selected` moves the pen.
  - Existing pen tests are seeded with `selected` on the row whose pen they tap, or they assert one pen per selected row: `assertTrailingControlsFormOneColumn` checks one row per render; the disconnected-host test counts pens on the selected rows; the remaining tap tests select their target row.
- `ConversationTreeRowsTest` (shared): a pixel test under `PyrycodeMobileTheme(darkTheme = true, dynamicColor = false)`. A selected row paints `primaryContainer` and not `onPrimary`. An unselected row held pressed (pointer down, not up) paints `onPrimary`.
- `SidebarTreeCaptureTest` (device-only, existing): its fixture already selects `c3`, whose pen it taps, so it is unchanged and supplies the 412 × 892 capture for the PR.
- `InteractiveStreamE2ETest` (rung 3): `openChannelEditor` and `setMuteInEditChannel` reach the pen by opening the row and pressing Back first. Listed under `## Live tests` for `interactiveTurn_createEditArchiveChannel_readsPromptBack` and `interactiveTurn_muteChannel_roundTripsThroughTheHost`. Opening a thread starts no turn, so the mute test stays at zero Claude turns.
