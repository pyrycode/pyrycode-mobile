# ChannelListViewModel — state projection and event handling

Split from [ChannelListViewModel](channel-list-viewmodel.md) — read that first for
the state shapes, the package and the wiring. This document covers how `hostState`
is derived: `stateIn` sharing, the state projection (including #729's workspace
groups), fold and selection, per-host preview fan-out and one-shot navigation.

Until #738, this document also covered the compatibility `state`'s own `combine` /
`catch` / `stateIn` pipeline, its `onEvent` reducer and the `lastMessagesFlow`
per-row fan-out that fed it — `ChannelListUiState`, `ChannelListNavigation`,
`state`, `onEvent` and `navigationEvents` all retired with the floating action
button that was their last consumer, and that material retired with them rather
than staying as a description of dead code. See
[ChannelListViewModel — related documents and ticket history](channel-list-viewmodel-related.md#related) for where each
technique's ticket history lives.

## `hostState` via `stateIn`

`observeHostEntry` and the fold/selection `MutableStateFlow`s are combined into one
`hostState: StateFlow<HostChannelListState>`, shared the same way: `scope =
viewModelScope` (supervisor-Job-backed, cancelled on `ViewModel.onCleared()`),
`started = SharingStarted.WhileSubscribed(5_000)` (the upstream starts collecting on
the first subscriber and stops 5s after the last one unsubscribes — the grace
absorbs a rotation's brief unsubscribe/resubscribe without a full collector
teardown), `initialValue = HostChannelListState()` (empty hosts, empty collapsed
set, no selection, no pending picker) — the same shape `ConversationTree`'s render
already treats as "no host has produced a snapshot yet."

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
rows; the ViewModel adds no list cache. See [snapshot lifetime](dependency-injection-host-conversation-source.md#snapshot-lifetime).
The nullable `addWorkspace` and its `addWorkspaceRecent` list (#904, replacing the flat
`workspacePickerServerId`) are combined from VM-owned Add workspace state — see
[`hostState` via `stateIn`](#hoststate-via-statein) above for the sharing config and
[ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for the tagged-recents pairing.

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

`recentChats = host.chats.take(RECENT_DISCUSSIONS_LIMIT)` is a slice, not a resort — the VM trusts
the source's own ordering (`FakeConversationRepository.observeConversations` sorts by
`lastUsedAt` descending; a future `RemoteConversationRepository` would order server-side) rather
than re-sorting, which would risk the two orderings disagreeing if the upstream definition
drifts. `HostChannelListViewModelTest` pins "slice, don't resort" the same way the retired flat
suite's `recentDiscussions_orderingFollowsUpstream` once did.

### Attention join (#877)

`hostState`'s combine gained a fifth source: `hostSource.attention`, joined at the entry
level rather than inside the per-snapshot projection — `entries.map { it.copy(attention =
attention[it.host.serverId].orEmpty()) }` runs *outside* the `hostSource.snapshots.flatMapLatest { … }`
block that produces `entries`. A `turn_state`/`turn_end` event changes only `hostSource.attention`,
never `hostSource.snapshots`, so this join never re-subscribes `observeHostEntry`'s per-host
preview collectors — folding attention into the snapshot projection itself would have made every
live turn re-run every host's preview `flatMapLatest`, blanking recent-chat previews through their
`onStart { emit(null) }` on each one. See [dependency injection § Attention
state](dependency-injection-host-conversation-source.md#attention-state-877) for the fold that produces
`hostSource.attention` and for `HostChannelListEntry.attentionFor`'s Idle-default read.

`onHostRowTapped(target)` calls `hostSource.markOpened(target.serverId, target.conversationId)`
synchronously, alongside recording `lastOpenedTarget` — the list's own open path, distinct from a
thread's `ConversationViewing` handle, which stays open only as long as that thread's `ViewModel`
is alive. A tap clears that row's unread and failed marks on that host only; it does not hold the
row read against a turn that completes after the tap but before the thread screen resolves.

## One-shot navigation via `Channel.BUFFERED` (#22)

Both navigation flows use separate `Channel.BUFFERED` channels exposed through
`receiveAsFlow()`. Buffering keeps an event until a collector consumes it without
replaying it to a later collector. `hostNavigationEvents` carries the full
`HostConversationTarget`; `onHostRowTapped(target)` emits that exact pair without
creating a conversation or checking live repository availability.

The production graph exposes host creation only after
[startup migration](navigation.md#how-it-works) succeeds using the full saved-host
snapshot. Creation uses explicit targets:

- `openCreateChat(serverId)` validates and holds the clicked host. `submitCreateChat()` resolves
  that host's repository and sends `createDiscussion(null)`, so the daemon chooses its default
  folder without reading the app's saved per-host default. The dialog state persists across
  connection changes; a distinct dialog id fences late replies after dismissal and reopening.
- `openAddWorkspace(serverId)` (#904, replacing `openHostWorkspacePicker`) stores the chosen
  host, exposed as `hostState.addWorkspace`. `submitAddWorkspace()` sends the modal's own
  `selected` path — a recent folder picked via `selectAddWorkspaceFolder`, or one just made via
  `createAddWorkspaceFolder` — through `createDiscussion(selected)`, and only records the
  navigation target if `compareAndSet(pending, null)` still finds the state it published, so a
  send that completes after `dismissAddWorkspace()` neither navigates nor reopens the modal. The
  explicit path remains separate from the Chats confirmation. See
  [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for all five methods and the
  `compareAndSet` discipline shared with `submitChatName`.

Both creation paths resolve `hostSource.repositoryFor(capturedServerId)`
before sending. Compatibility selection changes cannot redirect them, and a
replacement repository for that same host is used. Cached rows or connected
indicators cannot authorize a send: unknown, removed, disconnected and handshaking
targets return no repository, create nothing and emit no success navigation. See
[exact-host access](dependency-injection-host-conversation-source.md#exact-host-repository-access).

A successful create emits exactly one target with the captured host and returned
conversation id on `hostNavigationEvents`. A failed Chats create leaves its dialog open with a
static retryable error; dismissal creates nothing. Cancellation propagates. Debug events contain
static action/failure codes, never exception text,
host ids, paths or message content. A disconnect after lookup can still fail the
repository call; lookup is not a reservation.

`MainActivity` collects `hostNavigationEvents` and calls the shared host-qualified
route builder directly from the destination's `when (event)` — since #738 retired
`ChannelListViewModel.onEvent` along with the compatibility `navigationEvents` it
fed, there is no reducer left for the host-qualified events to be distinguished
from; every `ChannelListEvent` variant the VM needs to act on now maps to one of
its explicit methods (`onHostRowTapped`, `onFoldToggled`, `openCreateChat`, `submitCreateChat`, `dismissCreateChat`,
`openAddWorkspace`, `selectAddWorkspaceFolder`, `createAddWorkspaceFolder`,
`submitAddWorkspace`, `dismissAddWorkspace`) straight from `PyryNavHost`. Action
coroutines and preview collection are cancelled when the ViewModel is cleared.

**The non-atomic read-then-clear this section used to describe is gone with `pickHostWorkspace`
itself.** That method read `pendingHostWorkspacePicker.value` and cleared it without
`getAndUpdate`, so two picks landing in the same frame could both resolve the same host and
create two chats — flagged out of scope by #738's own security review and left unfixed through
several tickets. #904 replaced the whole method with `submitAddWorkspace`'s `compareAndSet`
terminal transition against a `busy`-guarded pending state (see
[ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring)), which closes this gap as a
side effect of the host-resolved-write shape rather than as a targeted fix — every entry point
into the Add workspace machine refuses while `busy`, so two submits cannot both resolve the same
pending state.
