# #1521 — Channel list top bar sits 4 dp above `15:8`

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — `BarTopGap`, `ChannelListTopBar`, `TreeHostGap` and the collapsed early-exits in `treeHost`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` — `TreeHostSectionRow` (closed vs open folder glyph) and `FoldableTreeRow` (right vs down chevron, fold label).
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — `assertToolbarGeometry` pins the bar glyph tops and rule position.
- `app/src/androidTest/assets/design-1220/list/index.md` — the #1431 audit's measurements and its note that the frame's right chevron on the expanded "Pyry" host is a frame inconsistency the app does not follow.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Titleless bar of three 24 dp glyphs (Settings, Archive, add host) whose tops sit 32 dp below the frame top (body padding 4 + sidebar padding 24 + button row padding 4), then a rule and the host tree. Host containers stack 16 dp apart (host rows at 28 dp pitch plus 16). Collapsed hosts (`MB Second brain`, `Elli`) draw a right chevron and no children; collapsed sections under `MB Game dev` draw the closed-folder glyph, a right chevron and no rows.

## Change

`BarTopGap` in `ChannelListScreen.kt` goes from `28.dp - BarTouchSlack` to `32.dp - BarTouchSlack`, so the glyph tops sit at 32 dp, and the rule and the tree move down 4 dp with the bar, because the tree follows the bar in the `Scaffold`. The thread's and reader's `BarTopGap` in `ThreadTopAppBar.kt` are separate constants and do not move. The 16 dp `TreeHostGap` and the collapsed treatment already match the frame: `TreeHostSectionRow` swaps to `ic_tree_folder`, `FoldableTreeRow` swaps to `ic_tree_chevron_right`, and `treeHost` emits no child items for a collapsed key. So no other production code changes.

## Testing strategy

- `ChannelListScreenTest.assertToolbarGeometry`: glyph tops move from 28/30/28 to 32/34/32 dp and the rule top from 68 to 72 dp. The existing 24 dp rule-to-first-host assertion keeps the tree's position relative to the bar.
- New `ChannelListScreenTest` case with two hosts, the first host's Chats section collapsed and the second host collapsed. It asserts 16 dp between the first host's last row and the second host's row, an "Expand" fold label on both collapsed rows, and that neither collapsed row's children are drawn.
- Captures: a 412 × 892 density-1.0 render of that two-host state for the side-by-side comparison with `15:8`, recorded in the PR.
