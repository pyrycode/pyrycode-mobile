# #1563 — Channel and chat rows draw no edit pen

## Files read

- `ui/conversations/list/ChannelListScreen.kt` — `treeHost`, the `itemsIndexed` block that hands `TreeConversationRow` its `onEditTapped` and `editDescription` for the selected row (#1523). The only production change.
- `ui/conversations/components/ConversationTreeRows.kt` — `TreeConversationRow`: a null `onEditTapped` draws no `TreeRowControl`, and the selectable area takes the freed width through `weight(1f)`. Unchanged.
- `ui/conversations/thread/ThreadOverflowMenu.kt` — `ThreadOverflowMenu`: a channel's Edit item (#1561) and a chat's Rename, the editing paths that remain.
- `MainActivity.kt` — the `ChannelListEvent.TreeChannelEditTapped` and `TreeChatEditTapped` routes into `ChannelListViewModel.openChannelEditor` and `openChatEditor`. Unchanged.
- `sharedTest/.../list/ChannelListScreenTest.kt`, `androidTest/.../list/SidebarTreeCaptureTest.kt`, `androidTest/.../e2e/InteractiveStreamE2ETest.kt` — every test that finds or taps a conversation row's pen by `cd_tree_channel_edit` or `cd_tree_chat_edit`.

Overlapping in-flight branch, built through: #1497 touches `InteractiveStreamE2ETest.kt` in the effort-label helpers, not the channel-editor helpers this ticket edits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The tree's conversation rows are a status dot and a `bodySmall` name, with the selected row on its `primary-container` fill and no trailing pen on any row. Host and section rows keep their trailing controls; the app keeps the host row's pen and the workspace row's pen as the ticket says.

## Change

In `treeHost`, `TreeConversationRow` no longer receives `onEditTapped` or `editDescription`, so no Channels or Chats row draws a pen, selected or not, connected or not. The row's `onClick` (`TreeRowTapped`) is untouched, so a tap anywhere on the row, including where the pen was, opens it. `TreeHostRow` and `TreeWorkspaceRow` keep their pens. Channels are edited from the thread's Edit item (#1561) and chats renamed from the thread's Rename.

`TreeChannelEditTapped`, `TreeChatEditTapped`, their `MainActivity` routes and the list's Edit channel and Edit chat modals stay: removing the list's now-unreached editors is a separate cleanup with its own call-site count, filed as #1582 rather than folded into this one-file change. `TreeConversationRow` keeps its optional pen parameter for the same reason.

## Testing strategy

- `ChannelListScreenTest`: `onlyTheSelectedConversationRowDrawsAPen_andItEditsThatRow` becomes `noConversationRowDrawsAPen_andRowsStillOpen`: with a channel, then a chat, then nothing selected, no `cd_tree_channel_edit` or `cd_tree_chat_edit` node exists while the host pen does, and tapping each row sends its `TreeRowTapped`. Tests whose subject was the row pen (`chatRowPencil_…`, `channelRowPen_…`) are removed; tests that only measured or tapped it on the way to another subject keep that subject: the one-column check keeps the section-plus edges and row insets, the disconnect test keeps the section-plus suppression, the re-sort test keeps the moved selection and checks the moved row's tap target, and the section-plus test drops its pen-bounds assertion.
- `SidebarTreeCaptureTest` (device-only: real pointer taps on the emulator's real layout, #1203): the selected row's text must end inside the viewport, and a tap in the host pen's column on the selected row now opens that row.
- `InteractiveStreamE2ETest` (rung 3): `openChannelEditor` and `setMuteInEditChannel` reach Edit channel the operator's new way, opening the row and choosing Edit from the thread's More actions menu, and leave the thread once the editor closes; Archive from the editor already returns to the list (#1561). `interactiveTurn_createEditArchiveChannel_readsPromptBack` and `interactiveTurn_muteChannel_roundTripsThroughTheHost` go under `## Live tests`.

## Revisions

### 2026-10-03: the design capture walk also tapped the row pen

The verifier's UI gate failed `ListDesignCaptureTest`'s two `listFramesAt*` methods: `walk` selected the first channel and waited for its Edit channel pen, which this change removes. Files read missed `androidTest/.../design/ListDesignCaptureTest.kt` because the search covered list and e2e tests, not the design captures. The walk now opens the first channel and reaches Edit channel through the thread's More actions, Edit (#1561), in the same thread visit that captures `thread-menu`; the Archive channel reachability assertion stays. Its capture index, `androidTest/assets/design-1220/list/index.md`, now names that path for Edit channel and marks the list's Edit chat modal unreachable. The stale KDoc on `TreeConversationRow` and `ChannelListViewModel.isHostConnected`, and the disconnect test's leftover selection, are corrected in the same commit. Production behaviour is unchanged.
