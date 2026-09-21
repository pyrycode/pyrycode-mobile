# ChannelListViewModel — state projection and event handling

Split from [ChannelListViewModel](channel-list-viewmodel.md) — read that first for
the state shapes, the package and the wiring. This document covers how `state` and
`hostState` are derived: `stateIn` sharing, the state projection (including #729's
workspace groups), per-row last-message fan-out, the default-value affordance
convention, the ordering contract, error semantics, one-shot navigation and the
`onEvent` reducer.

## Cold-to-hot via `stateIn`

For the compatibility `state`, `observeConversations` is a cold `Flow`; `stateIn` shares one upstream collection across all subscribers and exposes a `StateFlow` with a current `.value`. The configuration:

- **`scope = viewModelScope`** — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by the VM.
- **`started = SharingStarted.WhileSubscribed(5_000)`** — the upstream starts collecting when `state` gets its first subscriber and stops 5s after the last subscriber unsubscribes. Configuration changes (rotation) re-subscribe within ms, so the 5s grace prevents collector churn; navigating away for >5s truly disposes the upstream.
- **`initialValue = Loading`** — guarantees the AC's "Initial emitted state is `Loading` before the repository flow produces a value". `stateIn` returns synchronously and the first real upstream value replaces `Loading` on the next dispatch turn.

## State projection

`hostState: StateFlow<HostChannelListState>` maps `HostConversationSource.snapshots`
in source order. Each `HostChannelListEntry.host` is the unchanged snapshot: exact,
case-sensitive `serverId`, nullable local `displayName`, separate relay and pyrycode
legs in `connectionStatus`, active `channels` and complete active `chats`. Records,
daemon ids and workspace paths are preserved verbatim. `recentChats = host.chats.take(3)`
and `chatCount = host.chats.size`; neither hosts nor rows are re-sorted.

`recentChatLastMessages` belongs to its enclosing host entry. The pair
`(entry.host.serverId, conversation.id)` identifies a row or preview; equal ids or
paths on two hosts must never share a cross-host map. Host identity stays outside
`Conversation`, `Message` and wire serialization.

`channelGroups` and `chatGroups` (#729) group `host.channels` and `host.chats` — the
full active lists, not the recent-three slice — via `groupConversationsByWorkspace`
in `HostWorkspaceGroup.kt` (same package). The group key is the `(serverId, cwd)`
pair, `cwd` compared exactly (no trim, normalise or case-fold); `displayName` is
the shared `workspaceDisplayName` rule (#722) read off the group's *first*
conversation and is text only, never an identity — a rename or clear changes only
that text, so no conversation moves groups and the same path on another host stays
a separate group. `conversations.groupBy { it.cwd }` returns a `LinkedHashMap`, so
group order is first-encounter and each group keeps source-order membership — no
sort key of its own, matching `recentDiscussions_orderingFollowsUpstream`.
`observeHostEntry` computes both lists once per snapshot emission, before the
preview `combine`; the existing `entry.copy(recentChatLastMessages = …)` carries
them through unchanged, so a relabel re-projects without resubscribing and a
preview emission never recomputes a group.

For each host, `repositoryFor(host.serverId)` supplies live preview access. Each
recent chat's `observeLastMessage` flow starts with `null`, so a silent preview
cannot hold up its row or any other host. Null or failed previews become absent
map entries; exceptions affect only that preview, while cancellation propagates.
A missing repository gives an empty preview map and retains the snapshot's cached
rows. Snapshot changes cancel obsolete preview collections through `flatMapLatest`.

A host awaiting its first list reply is present with empty rows, and no source
hosts produces `hosts = emptyList()`. Disconnected hosts keep source-owned cached
rows; the ViewModel adds no list cache. See [snapshot lifetime](dependency-injection.md#snapshot-lifetime).
`hostState` uses `stateIn(viewModelScope, WhileSubscribed(5_000), HostChannelListState())`.
The nullable `workspacePickerServerId` is combined from VM-owned picker state.

### Fold and selection (#731)

`hostState`'s combine grew from two sources to four: the snapshot-derived host list, the
pending-picker `serverId`, and two more private `MutableStateFlow`s the assembled tree needs —
`collapsedKeys: MutableStateFlow<Set<TreeFoldKey>>` and
`lastOpenedTarget: MutableStateFlow<HostConversationTarget?>`. Both are hot flows with an
initial value (`emptySet()` / `null`), so neither holds up the first `combine` emission the way
a cold repository-backed flow could.

`collapsedKeys` holds **collapsed** keys, not expanded ones — the empty initial set means
"every host and every workspace expanded," so a host or workspace that arrives in a later
snapshot needs no reconciliation to draw expanded. `onFoldToggled(key)` is a read-modify-write
(`collapsedKeys.getAndUpdate { if (key in it) it - key else it + key }`, not a read-then-assign)
and logs a content-free `RelayLog.d` line carrying only the event name and the resulting
expanded flag — never `serverId`, `cwd` or a display name. The set is **never pruned** against
an incoming snapshot: pruning would silently unfold a host that momentarily disappears during a
reconnect, which would contradict "fold state survives … an incoming list update."

`lastOpenedTarget` is set synchronously — in `onHostRowTapped(target)` before the channel send,
and in `sendHostDiscussion` before its own send — so the tapped or freshly created row is already
the highlighted one by the time navigation completes. It records *last opened from this list*,
not *currently open*: the list and the thread are separate destinations, so nothing is "open"
while the list is on screen. A later snapshot emission never clears it; only another
`onHostRowTapped` / `sendHostDiscussion` call replaces it (last-writer-wins is intentional here).

The compatibility `state` continues to project only the injected selected-host
repository or fake:

```
channelsFlow ──────┐
                   │
discussionsFlow ──┬─► recentIdsFlow = discussions.take(3).map(::id).distinctUntilChanged
                  │           │
                  │           ▼
                  │   flatMapLatest { ids ->
                  │       if empty → flowOf(emptyMap())
                  │       else combine(ids.map { observeLastMessage(it).map(it to _) }) { … }
                  │   } ──► lastMessagesFlow: Flow<Map<String, Message>>
                  │           │
                  └───────────┤
                              │
pendingWorkspacePicker ───────┤   (private MutableStateFlow<Boolean>, #221)
                              │
   combine(channels, discussions, lastMessages, pickerVisible)
                              ──► derive ─┬─ recent = discussions.take(3)
                                          ├─ count  = discussions.size
                                          ├─ lastMessages (as-is)
                                          └─ pickerVisible (as-is)
                              ──► Empty(recent, count, lastMessages, pickerVisible)   (channels.isEmpty())
                              └─► Loaded(ch, recent, count, lastMessages, pickerVisible)
                 ─── catch ─► Error(message)
                 ─── stateIn ─► initial = Loading
```

`combine` (#26) replaced the single-flow `map` shape; #161 widened the combine arity from 2 to 3 by adding `lastMessagesFlow` (derived from `discussionsFlow`, not from `ConversationRepository` directly); #221 widened it again from 3 to 4 by adding `pendingWorkspacePicker` (a private `MutableStateFlow<Boolean>` written only by the VM's `onEvent` handler). The four upstreams are independent subscriptions; the three repository-backed ones are cold flows inheriting the `WhileSubscribed(5_000)` shared lifetime, and `pendingWorkspacePicker` is a hot `MutableStateFlow` whose lifetime is the VM's. The 4-arity `combine` overload ships with kotlinx-coroutines (up to 5 args). `combine` waits for *every* side to emit before the first downstream emission — `pendingWorkspacePicker` has an initial value (`false`) so combine doesn't suspend on it; the `Loading` initial frame remains observable until the three data flows produce their first values (same loading semantics as #161). `.catch { }` sits between `combine { }` and `.stateIn(...)` because `stateIn` is a terminal operator (returns `StateFlow`, not `Flow`) — `.catch` after it doesn't compile. One `catch` block covers all three repository-backed throwable sources; a throw on *any* of them (channels, discussions, or any per-row `observeLastMessage`) collapses the whole pipeline to `Error`. `pendingWorkspacePicker` cannot throw (it's a `MutableStateFlow` with no upstream).

\#69 collapsed the previous `.map { it.size }` projection on the discussions flow: both `recent` and `count` now derive from a single emission inside the `combine` body. Re-introducing a `.map` would force either a third upstream subscription (two collections of the same cold flow, two `WhileSubscribed` lifetimes) or a `combine`-of-`combine`. Single-emission `combine` body → multiple derived values is the right shape; reach for the un-mapped form whenever a second derived value lands.

## Per-row `observeLastMessage` via `flatMapLatest(distinctUntilChanged(idsFlow))` (#161)

The compatibility `lastMessagesFlow` is the project's first bounded-fan-out subscription pattern — per-element side data sourced from a bounded parent slice via dynamic `combine` of per-row cold flows. Three load-bearing operators:

- **`recentIdsFlow = discussionsFlow.map { it.take(3).map(Conversation::id) }.distinctUntilChanged()`** — the `distinctUntilChanged` is non-optional. Without it, every discussions re-emission (even ones that don't change the recent-3) would tear down and rebuild the inner `combine`, churning per-row subscriptions. The id-list-equality check is what makes the steady-state subscription stable.
- **`flatMapLatest { ids -> … }`** — the `flatMapLatest` (vs `flatMapConcat` or `flatMapMerge`) is non-optional. When the recent slice shifts (e.g. a new discussion takes the top-3 spot, pushing another out), the obsolete per-row subscriptions must be cancelled before the new ones start — otherwise stale entries leak into the next emission. `flatMapLatest`'s "cancel previous inner flow on new outer emission" semantics are exactly this guarantee.
- **Inner `combine(ids.map { id -> observeLastMessage(id).map { msg -> id to msg } }) { pairs -> pairs.mapNotNull { (id, msg) -> msg?.let { id to it } }.toMap() }`** — dynamic-arity `combine` over the per-row cold flows. `mapNotNull` filters `null` last-messages so the resulting `Map<String, Message>` has no nullable values (the "absent key = null last message" contract); call sites that read `map[id]` get unambiguous `Message?` semantics. The empty-ids branch short-circuits to `flowOf(emptyMap())` because `combine(emptyList())` is not well-defined.

`flatMapLatest` carries `@OptIn(ExperimentalCoroutinesApi::class)` in `kotlinx-coroutines 1.10.2`; the opt-in is scoped to the `state` property (not file-level) so the experimental dependency stays visible at the use site. The recent slice is capped at `RECENT_DISCUSSIONS_LIMIT = 3`, so the inner `combine` arity is bounded — a batched `observeLastMessages(ids: Set<String>)` repository method was considered and rejected as Phase 1 over-engineering. If the bound grows or per-row data becomes a network call, revisit the batched API at that point.

## Default-value affordance on the new field

The `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default on both `Empty` and `Loaded` (#161) is what kept the ticket data-layer-only. Without it, every existing construction site would have needed an explicit `recentDiscussionLastMessages = emptyMap()` argument: 6 in `ChannelListScreen.kt` previews, 6 in `ChannelListViewModelTest`, 2 in `ChannelListScreenTest`. With it, none of those 14 sites change — the VM emits with `emptyMap()` (because the test `stubRepo.observeLastMessage` returns `flowOf(null)` and the projection's `mapNotNull` filters it out) and data-class equality matches expected literals that omit the new arg. Generalised rule: when widening a UiState data class with a field that has a sensible "no data" identity (`emptyMap()`, `emptyList()`, `null` for nullable references), provide a default — the only reason to omit it is when forcing every site to acknowledge the new field is the actual goal.

## `.take(3)` against an upstream-sorted flow — no VM-side resort

`FakeConversationRepository.observeConversations(Discussions)` already emits `.sortedByDescending { it.lastUsedAt }`. The VM does `discussions.take(RECENT_DISCUSSIONS_LIMIT)` with no `.sortedByDescending` of its own — re-sorting would have duplicated the contract and risked the two sorts disagreeing if the upstream definition drifts (e.g. Phase 4's `RemoteConversationRepository` returning server-ordered records). The `recentDiscussions_orderingFollowsUpstream` test pins this: the VM slices but does not re-sort. If the repository contract changes its sort key, that test is the first to fail.

## Error semantics

For the compatibility `state`, `Error(message)` is terminal in the underlying flow: once `catch` consumes the exception and emits an `Error`, the upstream has already errored and produces no further values. Recovery requires a fresh subscription — currently only achievable by leaving and re-entering the screen (subscribers drop to zero, `WhileSubscribed(5_000)` expires, a new subscription starts a fresh collection). A `retry()` event-driven path lands with the screen-side "Retry" button.

Message extraction: `e.message` verbatim when non-null and non-blank, else the literal fallback `"Failed to load channels."`. Never `e.toString()` or `e::class.simpleName` — exception class names are implementation detail and read badly in user-facing UI.

## One-shot navigation via `Channel.BUFFERED` (#22)

Both navigation flows use separate `Channel.BUFFERED` channels exposed through
`receiveAsFlow()`. Buffering keeps an event until a collector consumes it without
replaying it to a later collector. `hostNavigationEvents` carries the full
`HostConversationTarget`; `onHostRowTapped(target)` emits that exact pair without
creating a conversation or checking live repository availability.

The production graph exposes host creation only after
[startup migration](navigation.md#how-it-works) succeeds using the full saved-host
snapshot. Creation uses explicit targets:

- `createHostDiscussion(serverId)` captures its argument before reading
  `appPreferences.defaultWorkspace(serverId).first()`. This one-shot read uses the
  exact, case-sensitive id and passes the value verbatim. An unset host default
  yields `DEFAULT_SCRATCH_CWD` (`~/.pyrycode/scratch`); it never falls back to the
  legacy owner's path or the currently selected host's default.
- `openHostWorkspacePicker(serverId)` stores the chosen host, exposed as
  `hostState.workspacePickerServerId`. `pickHostWorkspace(workspace)` captures and
  synchronously clears that target before launching creation. The explicit path
  bypasses the preference read. A second completion without a pending target does
  nothing. `dismissHostWorkspacePicker()` clears the target and creates nothing.

Both creation paths resolve `hostSource.repositoryFor(capturedServerId)` immediately
before sending. Compatibility selection changes cannot redirect them, and a
replacement repository for that same host is used. Cached rows or connected
indicators cannot authorize a send: unknown, removed, disconnected and handshaking
targets return no repository, create nothing and emit no success navigation. See
[exact-host access](dependency-injection.md#exact-host-repository-access).

A successful create emits exactly one target with the captured host and returned
conversation id on `hostNavigationEvents`. Repository failures use the existing
[guard](guarded-repo-launch.md): `RelayErrorException`, `IllegalStateException` and
`UnsupportedOperationException` are quiet failures with no navigation. Cancellation
propagates. Debug events contain static action/failure codes, never exception text,
host ids, paths or message content. A disconnect after lookup can still fail the
repository call; lookup is not a reservation.

The compatibility `navigationEvents` still carries `ChannelListNavigation.ToThread(id)`,
but `MainActivity` collects only `hostNavigationEvents` and calls the shared
host-qualified route builder. Host actions never feed the bare-id channel;
dropping the host would reintroduce selection-dependent routing.
Action coroutines and preview collection are cancelled when the ViewModel is cleared.

## `onEvent` reducer (#22, widened in #221)

Four compatibility `ChannelListEvent` arms are dispatched today; host-aware callers use the explicit methods above:

- **`CreateDiscussionTapped -> launchGuardedRepoCall { … }`** (#22, widened in #240, **guarded in #490**). Two-suspend sequence: `val workspace = appPreferences.defaultWorkspace.first()` (one-shot read of the persisted default — `DEFAULT_SCRATCH_CWD` on a fresh DataStore, or whatever Settings has set), then `val conversation = repository.createDiscussion(workspace = workspace)`, then `navigationChannel.send(ToThread(conversation.id))`. The `.first()` inherits `viewModelScope`'s dispatcher (`Dispatchers.Main.immediate`); DataStore switches internally to its own IO dispatcher and back. **Since #490 the launch routes through [`launchGuardedRepoCall`](guarded-repo-launch.md)** — under the relay repository a `RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException` from `createDiscussion` is inertly swallowed instead of reaching the default uncaught-exception handler and killing the process, and the follow-on `ToThread` send (kept **inside** the guard) is skipped on a throw. This is a **fail-quietly** guard, not error surfacing — no user-facing error UI yet; that Phase-4 affordance still lands separately. The `defaultWorkspace` projection's `?: DEFAULT_SCRATCH_CWD` fallback still guarantees a non-null `String` on the happy path. Pre-#240 the call was bare `repository.createDiscussion()` — the no-arg path hit `FakeConversationRepository.kt:108`'s `workspace ?: ""` and produced `cwd = ""`; post-#240 an unconfigured user gets `cwd = "~/.pyrycode/scratch"` instead (both forms hit `bumpWorkspace`'s `cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD` early-return, so no recent-workspaces pollution). Don't add a normalization step that maps `DEFAULT_SCRATCH_CWD` → `""` to preserve the old shape — that's a defense for a problem that hasn't been observed.
- **`LongPressFab -> pendingWorkspacePicker.value = true`** (#221). One line; flips the visibility flag, the combine projects, the screen re-renders with `workspacePickerVisible = true`, the host opens its sheet.
- **`is WorkspacePicked -> { pendingWorkspacePicker.value = false; launchGuardedRepoCall { … } }`** (#221, **guarded in #490**). Same shape as `CreateDiscussionTapped` (same [`launchGuardedRepoCall`](guarded-repo-launch.md) guard) but with `repository.createDiscussion(workspace = event.workspace)` instead of the no-arg default, and with the **synchronous flag-clear before the suspend**. Clearing first starts the sheet's exit immediately instead of waiting for `createDiscussion`. This compatibility arm does not check a pending target; duplicate-completion suppression belongs to `pickHostWorkspace`, whose captured host is cleared before launching.
- **`WorkspacePickerDismissed -> pendingWorkspacePicker.value = false`** (#221). One line; the host's `onDismiss` callback fires when the user dismisses the sheet (close icon, scrim tap, drag-down, back-press), the screen forwards to this arm, the flag flips, the projection emits, the host re-composes with `visible = false`, the `ModalBottomSheet` runs its exit animation as it leaves composition.

The `is TreeRowTapped, is TreeFoldToggled, SettingsTapped -> Unit` arm exists for compiler
exhaustiveness; `MainActivity` maps `TreeRowTapped` / `TreeFoldToggled` to `onHostRowTapped` /
`onFoldToggled` directly and `SettingsTapped` to a route navigation, never forwarding any of the
three into `onEvent` — but if the dispatch convention shifts later the VM tolerates them
defensively (no-op). Pre-#731 this arm named `RowTapped` / `RecentDiscussionsTapped`, both retired
with the flat list and the recent-discussions section.
