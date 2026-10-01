# #1334 — Line up the Edit host pen with the row pens

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `treeHost`, `TreeConversationInset` — the conversation row `Box` pads both sides by 12dp; the only production change.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `TreeConversationRow`, `FoldableTreeRow`, `TreeRowControl` — host and conversation rows both end in `TreeRowEndPadding` plus a `TreeControlWidth` control, so equal outer right edges give equal pen centres. `TreeHostSectionRow` pads its own end by 10dp and is not wrapped by the changed `Box`, so the section pluses do not move.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `setTree`, `siblingConversationRowsKeepTheFourDpFigmaGap` — where the new geometry test sits.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SidebarTreeCaptureTest.kt` → `capture` — existing device geometry/tap coverage of the same pens; its assertions (pen inside the viewport, text ends before its pen, taps route to their own control) still hold after the change.

No in-flight feature branch touches `ChannelListScreen.kt`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

Sidebar frame: host row I133:259;405:7839 and Channel list I133:259;103:2985. Conversation rows start 12dp in from the host row and end on the host row's right edge, so the row pens and the Edit host pen form one trailing column. (Figma MCP was not authenticated in this session; the summary is taken from the ticket's node annotations.)

## Change

In `treeHost`, the conversation row `Box` drops `end = TreeConversationInset` and keeps `start = TreeConversationInset` and its top gap. Each Channels and Chats row then ends at the tree gutter like `TreeHostRow` does, and because both rows end in the same `TreeRowEndPadding` and control width, the pens share one horizontal centre. Nothing else moves: section rows are separate items with their own end padding.

## Testing strategy

New `ChannelListScreenTest` case: one host with a channel and a chat, rendered under `DeviceConfigurationOverride.ForcedSize` at 412dp and at 320dp. Assert the Edit host pen's centre X equals each row pen's centre X within 0.5dp; each row's tagged selectable node starts 12dp right of the host row's fold node (the merged node named by the host's collapse label); and each section plus's right edge stays exactly 10dp left of the host pen's right edge, as it is today. RED before the change: pen centres differ by 12dp.

Existing coverage run: `ChannelListScreenTest`, `ConversationTreeRowsTest`; `SidebarTreeCaptureTest` on the managed device.

## Documentation handoff

None named by the ticket.
