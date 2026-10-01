# #1336 — Hide create and edit controls on a disconnected host

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `treeHost` — passes `onAddTapped` to both `TreeHostSectionRow`s and `onEditTapped` to every `TreeConversationRow` unconditionally; the modal bindings (`ChatEditorModal`, `CreateChannelModalBinding`, `ChannelEditorModal`) read `hostAvailable` from `isHostConnected`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostSectionRow` (non-null `onAddTapped`), `TreeConversationRow` (already nullable `onEditTapped`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `HostChannelListState.isHostConnected`, the private `ConnectionStatus.isLive`, `createChat`, `openCreateChannel`, `submitCreateChannel`, `openChatEditor`, `submitChatName`, `archiveChat`, `openChannelEditor`, `submitChannelEdit`, `archiveChannel` — every one resolves the host only through `repositoryFor`, which is non-null on a host whose snapshot is not live.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `snapshots` (`StateFlow<List<HostConversationSnapshot>>`, carries `connectionStatus`).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`/`Host` (per-host `status` flow and `available` lookup gate); `chatEditorFollowsItsOwnHostsConnectionWithoutClosing` and `channelPromptIsReadOnceItsHostConnectsAndAFailedOrOversizeReadIsUnavailable` encode the #1190 keep-open rule this ticket reverses.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `entry`, `setTree`; `multiHostTreeKeepsTargetsThroughFoldsEditingPromotionReconnectAndTheFinalRow` taps a pen on an offline host.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, `channel-list-viewmodel.md` — current section-plus and keep-open documentation (documentation stage updates these).

No in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

No new visuals (per the ticket): on a host that is not connected the Channels/Chats section plus and every conversation-row pen are simply not drawn; the rows keep their existing layout, and dialogs close through their existing close. The Figma MCP is unauthenticated in this session, so no fresh screenshot was taken; nothing new is drawn.

## Context

Mobile keeps every create/edit control on a down host and keeps their modals open with OK disabled (#1190). The owner now wants desktop's rule (`ChannelList.tsx`): controls are rendered only while the host is connected, the container re-checks on press, and open create/edit dialogs for a host close when it stops being connected. This reverses #1190's keep-open-on-disconnect rule; the documentation stage should record that reversal.

## Design

### Screen

- `TreeHostSectionRow.onAddTapped: (() -> Unit)?` — when null the trailing `TreeRowControl` is not emitted. The fold area and row height are unchanged.
- `treeHost` computes `val connected = hostState.isHostConnected(host.serverId)` once per host and passes `null` for the section plus and the row pen when it is false. The host row (`TreeHostRow`: Edit host pen, reconnect / re-pair / update control), folding and row taps are untouched.
- The modal bindings keep passing `hostAvailable` (other callers use the parameters); the closing is the view model's job.

### View model

- A private `isHostLive(serverId)` reads `hostSource.snapshots.value` with the same `isLive` rule `isHostConnected` uses.
- **Refusals at open:** `createChat`, `openCreateChannel`, `openChatEditor`, `openChannelEditor` return without state change when `!isHostLive(serverId)` (log `code=disconnected`).
- **Re-check before any repository call:** `submitCreateChannel`, `submitChatName`, `submitChannelEdit`, `archiveChat`, `archiveChannel` check `isHostLive(state.serverId)` after the `saving` guard and before `repositoryFor`; on a not-live host they close their own modal (`compareAndSet(state, null)`, cancelling the prompt read for the channel editor) and return. Closing rather than flagging matches the watcher, so the outcome does not depend on which of the two sees the disconnect first.
- **Snapshot watcher:** `init { viewModelScope.launch { hostSource.snapshots.collect { … } } }` computes the live server-id set and, for each of `createChannel`, `chatEditor`, `channelEditor`, `createChat`, atomically `update`s a state whose `serverId` is not in it to `null` (channel editor also cancels `channelPromptRead`). Logged once per cleared modal, content-free.
- Out of scope, untouched: `hostEditor`, `addWorkspace`, `workspaceEditor`.

## State + concurrency model

- The watcher runs in `viewModelScope` for the view model's lifetime; it is cancelled in `onCleared` with the scope. `snapshots` is a hot `StateFlow`, so the first collected value is the current one and conflation is harmless (only the latest liveness matters).
- Clearing uses `MutableStateFlow.update`, so it is atomic against concurrent writers. A write already in flight may finish; its terminal `compareAndSet(pending, …)` against a cleared (null) state is a no-op, so a cleared modal is never resurrected. `createChat`'s success path still navigates (the chat exists); this matches the ticket's "a write in flight may finish".
- Open/submit checks read `snapshots.value` synchronously on the caller's (main) thread before any launch, so a call after the disconnect snapshot has been published never reaches the repository.

## Error handling

No new failure surface. A refused open does nothing visible (the control is not drawn anyway, so only a race reaches it). A refused submit closes the modal, which is the same state the watcher produces. Existing `repositoryFor == null` failure flags remain for a live snapshot without a repository.

## Testing strategy

Unit (`HostChannelListViewModelTest`, `runTest` on the fixture's dispatcher, two hosts `Host`/`host`):

- Watcher: open Create channel, Edit chat and Edit channel on `Host` and a modal on `host`; flip `Host` offline → `Host`'s modals are null, `host`'s stays. A failed `createChat` state on `Host` clears on disconnect.
- Opens refuse: with `Host` offline, `createChat`, `openCreateChannel`, `openChatEditor`, `openChannelEditor` publish nothing and create nothing.
- Submit race: for each of `submitCreateChannel`, `submitChatName`, `submitChannelEdit`, `archiveChat`, `archiveChannel`: open while connected, flip the host offline just before the call, and assert no repository call (creates, renames, mute/prompt writes, archives all empty) and the modal closed.
- Rewrite the two #1190 tests that assert a modal survives a disconnect to the new rule.

Compose screen test (`ChannelListScreenTest`, Robolectric, sharedTest): two hosts, both with a channel and a chat; drive `first` offline via the hoisted state → no `treeHostChannelAddTestTag`/`treeHostChatAddTestTag` for `first`, no row pens for `first`'s rows, `second` keeps all; `first`'s Edit host pen and reconnect control exist, fold and row tap still emit events; flip back to connected → plus and pens return. Update `multiHostTreeKeepsTargets…` (it taps a pen on an offline host) to assert the pen is absent instead.

Not operator-facing in the daemon-flow sense (no new wire interaction), so no rung-3 scenario.

## Open questions

- Whether the submit-race test can observe the snapshot update before the watcher runs depends on `HostConversationSource`'s dispatcher; resolve during implementation (fallback: test the re-check by making the snapshot non-live while the watcher has not yet run, or by asserting no repository call regardless of which path closed the modal).

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`: a disconnected host shows no section plus and no row pen.
- `docs/knowledge/features/channel-list-viewmodel.md`: this reverses #1190's keep-open-on-disconnect rule; create/edit modals close when their host stops being connected.
