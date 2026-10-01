# #1333 — Remove the connection dots from host rows

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `ConnectionLegPair`, `IdleLegDot`, `LegDot`, `TreeLegDotGap`, `FoldableTreeRow` KDoc — the call and the helpers this ticket removes.
- `app/src/main/res/values/strings.xml` → `cd_tree_host_leg_idle` — only used by `IdleLegDot`; removed with it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `toLegVisual`, `ConnectionLegVisual`, `ConnectionLegCategory.color` — stay; `ConnectionStatusLine` still uses them.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt` → the three host-row tests that assert on the dots.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

Sidebar frame: host row I133:259;405:7839 now hides its "Relay connection status dot" and "Host connection status dot" layers, so the row is server glyph, name, fold chevron, then the trailing controls (reconnect or update when applicable, then the Edit host pen). The Figma MCP was unauthenticated in this run, so this summary is taken from the ticket's description of the layer change; the change is a pure removal.

## Change

`TreeHostRow` stops calling `ConnectionLegPair`. `ConnectionLegPair`, `IdleLegDot`, `LegDot`, `TreeLegDotGap` and the string `cd_tree_host_leg_idle` become unused and are deleted, along with the now-unused `Arrangement` import. The disconnected accent, the reconnect/update controls, the update caption and the Edit pen are untouched. `TreeHostRow`'s and `FoldableTreeRow`'s KDoc drop their mentions of the dots. `ConnectionStatusLine` and its `toLegVisual` mapping stay; they still draw both legs in the thread. `TreeHostRow` keeps its `connectionStatus` parameter because the relay leg still drives the disconnected and update-required treatment.

In-flight overlap: `feature/1305` edits `strings.xml`; this ticket only deletes one unrelated line there.

## Testing strategy

In `ConversationTreeRowsTest` (Robolectric, `sharedTest`):

- Replace `hostRow_showsEachConnectionLegWithItsOwnDescription` and `hostRow_updateRequired_drawsTheHostDotIdle_…` with one test that drives a host row through connected, connecting, offline, pairing rejected and update required, and in each state asserts: no node carries any relay or pyrycode leg description (every `toLegVisual().contentDescription` value, plus "Pyrycode: idle"); the name and the fold chevron's description are present; the Edit control keeps its name; the reconnect control appears (named) exactly for offline and pairing rejected, the update control exactly for update required.
- `hostRow_longName_…IndicatorPair…` anchors on the Edit control's right edge instead of the relay dot.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/channel-list-screen-tree-and-controls.md` describes `ConnectionLegPair`, `LegDot`, `IdleLegDot` and `cd_tree_host_leg_idle` on host rows (the #744 pencil, #1009 update-required and #840 reconnect passages); they should say host rows carry no connection dots since #1333.
