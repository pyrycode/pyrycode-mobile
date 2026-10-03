# #1577 — Consecutive tool rows sit flush

## Files read

- `ui/conversations/components/ToolCallRow.kt` — `ToolCallRow` / `ToolCallRowContent`: the `Surface`'s `padding(bottom = MessageRowVerticalSpacing)` is the gap.
- `ui/conversations/components/MessageBubble.kt` — `MessageBubble`'s `Role.Tool` arm is the only caller of `ToolCallRow` in the thread.
- `ui/conversations/thread/ThreadScreen.kt` — the `itemsIndexed` over `reversedRows` knows each row's chronological neighbour.
- `sharedTest/.../thread/ToolRowNestingTest.kt` — the pattern for mounting the real `ThreadScreen` and measuring tool-row bounds.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1792

The "Consequent tool uses" group: consecutive tool rows keep their own 6 dp rounded `primaryContainer` outline and no gap; each following row moves up by the 1 dp outline width so the two outlines draw as one line. (The MCP screenshot returned empty on 2026-10-03; the ticket text is the reference for these values.)

## Change

`ToolCallRow` and `MessageBubble` gain `joinsNextToolRow: Boolean = false`. When true, the `Surface` drops its 12 dp bottom padding and a `layout` modifier reports its height one `ToolCallBorderWidth` short while drawing at full size, so the next row's top outline lands on this row's bottom outline. `ThreadScreen` sets the flag when the row and its next chronological row are both delivered tool messages with a `toolCall`. Every other neighbour (assistant, user, delimiter, notice, queued row) keeps today's 12 dp. The `Surface` gains a `TOOL_ROW_TAG` test tag, placed after the shrink so its bounds are the full drawn outline. Nothing else moves: expansion lives inside the `Surface`, so collapsed and expanded rows behave the same.

## Testing strategy

New `ConsecutiveToolRowSpacingTest` in `app/src/sharedTest/.../thread/`, beside `ToolRowNestingTest`, mounts `ThreadScreen` with tool, tool, assistant text:

- the second tool row's top equals the first row's bottom minus 1 dp, collapsed and after expanding the first row;
- the space between the last tool row's bottom and the assistant text is at least 12 dp.

Existing `ToolCallRowTest`, `MessageBubbleTest`, `ToolRowNestingTest` and `ScriptedToolRowTest` rerun unchanged.
