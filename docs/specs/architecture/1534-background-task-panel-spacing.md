# #1534 — Background-task panel spacing matches 568:877 and 568:932

## Files read

- `ui/conversations/thread/BackgroundTaskPanel.kt`: `PartialNotice`, `GroupLabel`, `TaskRow`, `TaskProgress`, `LatestUpdate`, `CutMarker`, `TaskField` and the `TaskRowGap` / `ProgressGap` constants.
- `ui/conversations/thread/TaskStatusTag.kt`: `TaskStatusTag`, the 20 px pill that sets the card's type-row height.
- `app/src/sharedTest/.../thread/BackgroundTaskPanelLayoutTest.kt`: the existing layout coverage. #1496 also edits it, so the new assertions go in their own file.
- Overlap: #1496 edits the `MobileReadOnlyModal` call and a comment in `BackgroundTaskPanel.kt`. My edits are in other declarations.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-876 (Populated `568:877`, Capped `568:932`)

The panel content is a column with 10 px gaps: the "Partial list" banner (12/10 padding, 19 px line, so 39 tall), the group headings (19), then the task cards (14/12 padding, 8 px gaps). Inside a card, the type row is 20 (the tag is 2 px padding plus a 16 px line). The description uses 20 px lines. Progress is activity 19, a 2 px gap, then meta 17. The summary is 19. Latest update is label 17, a 4 px gap, then a patch block of 8 + n×17 + 8. The cut marker is 20: a 1 px border, 1 px padding and a 16 px line. Colours, type and radii are unchanged.

## Change

Every measurement in the frames is a CSS line box, `line-height × lines`. Compose's default `LineHeightStyle` trims the half-leading above the first line and below the last, so each panel text is a few px shorter than its frame box. #1041 offset that with stretched gaps (`TaskRowGap` 12 against the frame's 8, `ProgressGap` 6 against 2), but the banner (4 px short), the headings (3 px short each) and some cards (1 px short) kept the loss, and the drift adds up down the panel. The fix gives every task-list text style in `BackgroundTaskPanel.kt` and `TaskStatusTag` an untrimmed, centred line box through one private extension, `TextStyle.untrimmedLineBox()`. That makes each text box exactly `lineHeight × lines`, as in Figma. It then restores the frames' gaps: `TaskRowGap` 8, `ProgressGap` 2, and `CutMarker` 2 px vertical padding, which accounts for the frame's border-box border. `EmptyReading` already matches its frames and is untouched. Nothing else moves because only line-box heights and those three spacings change.

## Testing strategy

New Robolectric screen test `BackgroundTaskPanelSpacingTest` (sharedTest, `@GraphicsMode(NATIVE)`, 412×892). It renders a Populated roster and a Capped roster with single-line fields and asserts these offsets from the scroll viewport's top, within 1 px:

- Capped: banner text at 10 (19 tall), heading at 49, first card at 78.
- Populated: heading at 0, first card at 29.
- The frame heights of the single-line card shapes: 118 for running with progress, 99 for finished with a summary, 136 for running with a "no change" update, and 100 for a description with a cut marker.

Card bounds come from the merged card node, whose semantics sit inside the card's padding, so its bounds are the card minus 12 px top and bottom. The existing `BackgroundTaskPanelLayoutTest` and `BackgroundTaskPanelTest` are rerun as well.
