# Dependency injection — host conversation source and destination ownership

Split from [Dependency injection](dependency-injection.md) — read that first for Koin
wiring, `appModule`, the flag-gated selector pattern, testing and configuration. This
document covers how `HostConversationSource` aggregates per-host state and how host-qualified
destinations (thread, literal, settings, archive) get their exact-host dependencies: host
identity and snapshots, snapshot lifetime, on-disk restore, attention state (#877), exact-host
repository access, destination ownership, exact-host retry and the demo binding.

### Host identity and snapshots

`HostConversationSource.snapshots` is a shared
`StateFlow<List<HostConversationSnapshot>>` in `di/`. Each relay snapshot carries
the exact, case-sensitive saved `serverId`, nullable local `displayName`, separate
relay/pyrycode legs in `connectionStatus`, and `channels` / `chats` lists. Host
identity belongs to this aggregation boundary: `Conversation` and wire payloads
remain host-local. Equal conversation ids or workspace paths on different hosts
stay distinct; display names and paths are never routing aliases. Snapshots expose
no pairing records, tokens or keys.

Registry reconciliation publishes every saved host, including hosts awaiting their
first reply with empty lists; no saved hosts produces an empty source. The source
collects `ConversationFilter.All` once per current repository and partitions active
rows into promoted Channels and unpromoted Chats (discussions), excluding archived
rows. It preserves each partition's repository order and the original records,
including ids and workspace paths verbatim. It adds no frame consumer.

### Snapshot lifetime

The Koin singleton starts collection on first resolution and owns its in-memory
cache until Koin close calls `dispose()`. Collection continues with zero screen
subscribers. Each host's status and list collectors run independently, so a silent
host cannot delay another host's rows or status.

A null repository, disconnect, background close or reconnect awaiting its first
list emission retains that host's last rows. Each actual emission atomically
replaces both lists for that host, including an empty result. Feed this cache from
the per-host coordinator streams: the selected-host
[stable facade's cold reads](stable-conversation-repository.md#cold-reads--flatmaplatest-switch-with-an-empty-fallback)
emit an empty fallback on disconnect and cannot distinguish it from a real empty
reply. The compatibility facade keeps that behavior.

The coordinator's repository-stream identity identifies a retained bundle
generation. A display-name-only edit updates metadata while preserving its bundle,
collectors and rows. Removal or credential-driven bundle replacement cancels the
old collectors and discards that host's rows; a replacement starts empty. Updates
check both the registered generation and current repository identity, because
cancellation alone cannot reject every late callback. Source disposal cancels its
collectors, clears the in-memory cache and disables lookup; registry disposal
publishes no hosts. The in-memory cache itself does not survive app restart — but
see below: as of #796, an accepted live list also lands in an on-disk
`ConversationCache`, and a newly created entry seeds itself from that document
before its first live emission arrives.

### Restore from the on-disk cache (#796)

`HostConversationSource.relay(...)` takes an optional trailing
`cache: ConversationCache? = null` (the [conversation cache](conversation-cache.md)'s
contract). `appModule` binds the one production `FileConversationCache` under
`Context.noBackupFilesDir` — never `filesDir`, since the manifest's
`allowBackup="true"` would otherwise carry cached conversation names and cwds into
cloud backup and device-to-device transfer while the Keystore-wrapped pairing
credentials that authorize reading them do not — and `hostConversationModule`'s
relay branch passes it as `cache = get()`; the demo branch passes none.

Each live list `update` accepts — one that clears the existing stale-entry and
superseded-repository guards — is written to the cache verbatim, archived rows
included, so the stored document mirrors exactly what the daemon reported and the
same `withRows` filter (the single promoted/archived split, used by both paths) is
applied identically on read. When `reconcile` creates a new entry and a cache is
present, it also launches a read of that host's cached document; a non-empty
result seeds the snapshot through `withRows`. The seed never writes
`connectionStatus` — a restored list must not read as a connected one, and
`TreeHostRow`'s existing status treatment stays the only disconnected affordance
(see [ChannelListScreen § Edge cases](channel-list-screen.md#edge-cases--limitations)).
A restore *seeds*; a live list still *replaces* wholesale, exactly as before #796
— so a conversation id already drawn cannot gain a second row and a conversation
the daemon stops reporting cannot survive a live replacement, with no merge or
dedup anywhere.

**The race a restore's read length creates.** A cache read has no bound relative
to the daemon's own list arriving, so a slow restore can complete *after* a live
list has already landed for the same entry — and the existing stale-entry /
superseded-repository guards both check *who is current*, not *what already won*,
so neither rejects that write. `Held` therefore carries its own `live: Boolean`,
set the moment a live-list write is accepted; a restoring write is rejected once
`live` is already set. `live` is read and written only inside the same
`@Synchronized update` this class already serializes every snapshot mutation
through, and the cache read suspends *outside* that monitor, so the check-and-set
is atomic against the live path.

A failed or empty cache read is never treated as authoritative:
`readConversations` returns `emptyList()` both for a document that was never
written and for one that failed to parse — the two are indistinguishable by
design (see [conversation cache § Failure model](conversation-cache.md#failure-model--graceful-reads-reporting-mutations))
— so only a non-empty read publishes anything, and only a live list may ever
empty a host.

### Attention state (#877)

Every conversation row on every host carries exactly one `ConversationAttention`
(`di/ConversationAttention.kt`): `WaitingForAnswer`, `Running`, `Failed`, `Unread`,
`Idle`, declared in precedence order and resolved by the total function
`resolveAttention(waiting, running, failed, unread)` — desktop's
`resolveConversationStatus` order (`../pyrycode-desktop/src/renderer/src/store/conversationStatus.ts`)
with mobile's extra `Failed`, which sits after `Running` and before `Unread`.
Drawing the state on `TreeConversationRow` is a separate, blocked ticket; this
slice only publishes it.

`HostConversationSource.attention: StateFlow<Map<String, Map<String, ConversationAttention>>>`
is keyed by `serverId` then conversation id and holds **non-Idle entries only** — a
missing id is Idle. It is a sibling of `snapshots`, published from the same `Held`
entry but folded and combined independently, so an attention change never re-runs
`observeHostEntry`'s preview `flatMapLatest` and a preview change never touches
attention. `ChannelListViewModel.hostState` joins the two at the entry level (see
[state projection § Attention join](channel-list-viewmodel-projection.md#attention-join-877)).

The fold itself is the internal, pure `HostAttentionState` data class — no clock, no
I/O, no logging, because every id it touches is daemon-authored and used only as an
equality key:

- `onEvent(event, viewing)` folds one `LiveSessionEvent`. `TurnState` Thinking/Responding
  adds the conversation to `running` and clears it from `failed` (a next turn starting
  retires the previous turn's failure); `TurnState` Idle clears `running`. `TurnEnd`
  clears `running` and, unless its `turnId` is blank, over `MAX_TURN_ID_CHARS` (256), or
  already **counted** — in the bounded per-conversation `counted` list (newest last, capped
  at `MAX_COUNTED_TURNS_PER_CONVERSATION` = 16) or equal to the stored position's
  `completedTurnId`/`readTurnId` — records the turn as counted, sets a `ReadPosition`
  (read immediately if `viewing`, else `completedTurnId` only, keeping the prior
  `readTurnId`), and marks the conversation failed only when not viewing and
  `turnOutcomeReport(event)?.kind` is `Failed` or `StoppedEarly` (`Interrupted` never
  counts). Every other event is a no-op.
- `opened(conversationId)` clears `failed` and sets `readTurnId = completedTurnId`.
- `disconnected()` clears `running` only — positions and `failed` survive a lost
  connection in memory, matching the lifecycle driver closing a supervisor and the
  collector below seeing a null repository.
- `restored(stored)` merges positions read from the cache under the live ones — a live
  completion always wins over a stale restore.
- `resolve(modal, batches)` returns the non-Idle map: `WaitingForAnswer` comes from a
  `ModalUiState.Open` whose `conversationId` matches and is non-blank (a blank-id prompt
  belongs to no row, #816) or from `batches.batchFor(id) != null`.
- `positions` is capped at `MAX_READ_POSITIONS` (1000) per host, oldest insertion order
  dropped first — see the [security review](../../specs/architecture/877-conversation-attention-state.md#security-review)
  for why these three bounds exist (an unbounded daemon could otherwise mint ids or huge
  turn ids to grow the stored document).

`ConversationViewing` (same file) is the thread's viewing signal, deliberately its own
small class rather than a `HostConversationSource` method: `view(serverId, conversationId): Closeable`
increments a counted `(serverId, conversationId)` multiset and returns an idempotent-close
handle; a blank id returns a no-op handle. `HostConversationSource` takes it as a
constructor parameter (`relay(...)` and `demo(...)` both default to a fresh instance) and
folds `viewing.viewed` per host, re-opening every currently-viewed conversation of that
host on each change. `ChannelListViewModel.onHostRowTapped` calls
`hostSource.markOpened(serverId, conversationId)` directly instead — the list tap has no
`ConversationViewing` handle of its own, so opening it also clears `failed`, but does not
hold the conversation read past that one call the way a thread's view does.

`HostConversationSource.launchAttention(entry)` runs four collectors under the same
`entry.job` `reconcile` already cancels on bundle replacement or removal: the live-event
fold (reading `viewing` under the class monitor via `updateAttention`), a `repositories`
null emission → `disconnected()`, the combined `modal`/`questionBatches` → `resolve`, and,
only when a `cache` is bound, a one-shot restore followed by a collector over each
distinct positions map, written through `ConversationCache.writeReadPositions`. All four
route through one `@Synchronized updateAttention(entry, change)`, which reuses `update`'s
staleness guard (factored out as `isCurrent(entry)`) so a retired bundle cannot publish or
persist.

**Known gap: a restore landing after a view opens does not re-mark it read.** The restore
collector folds `attention.restored(written)` directly, without re-applying `opened` for
this host's currently-viewed conversations — unlike the `viewing.viewed` collector, which
does re-open on every change. Concretely: process death while the operator is on a thread,
Android restores straight into that thread (registering the view against empty positions),
and the stored `ReadPosition(T, null)` that arrives afterward reads Unread while the thread
is open, and stays Unread after backing out. Flagged as a verifier SHOULD FIX on the PR;
low impact today because nothing draws attention yet, so it did not block. The fix belongs
with whichever ticket next touches `launchAttention`'s restore branch (candidate: fold
`opened` for this host's `viewing.viewed` entries right after `restored`).

### Exact-host repository access

`repositoryFor(serverId)` resolves `RelayConnectionRegistry.connectionFor` by exact
id, without consulting compatibility selection. Unknown, removed, disconnected or
handshaking hosts return `null`, even if their snapshots still contain rows.
The coordinator's internal `liveRepository()` checks one active connection under
its teardown lock: the owner must be active, its transport must be identical to
the supervisor's current transport, and that connection's actual pump must be
`Open`.

The asynchronous `currentRepository` cache can still hold a retired repository
when a replacement transport first arrives. Cached rows or status therefore do
not establish current availability. This lookup returns availability at the time
of the check; a later disconnect can still make an operation fail. See the
[coordinator's availability checks](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493).

### Destination ownership

`ThreadDestinationFactory` is a scope-free singleton in `hostConversationModule`.
The Koin `viewModel` definitions call `thread(handle, preferences)` and
`literal(handle)`; the factory reads `serverId` from the destination's
`SavedStateHandle`, while each ViewModel reads its unchanged host-local
`conversationId`. [Navigation](navigation.md#host-qualified-destinations) supplies
both arguments and scopes ViewModels to individual back-stack entries.

`ThreadDestinationFactory.settings(handle, preferences)` (#749; dropped its third `repository`
parameter in #715) is the third destination method, in the same shape but with two deliberate
differences from `thread`/`literal`: the owner it reads from the `SavedStateHandle` is **optional**
(`handle.get<String>("serverId").orEmpty()` — a blank owner is a valid destination state, not an
error), and it never resolves that id to a connection bundle — `SettingsViewModel` reads a saved
host's identity and status only, so a saved-but-disconnected owner is still its owner. `preferences`
is the same `AppPreferences` singleton every other `SettingsViewModel` dependency already used; #749
did not touch it, per its explicit deferral of `archivedDiscussionCount` and the default-workspace
picker to #715/#714. #714 closed the workspace half of that deferral: `preferences` is still the one
process-wide `AppPreferences` singleton, but `SettingsViewModel` now reads and writes
`defaultWorkspace` through it under the destination's own `ownerServerId` key rather than app-wide,
and the workspace picker's own repository is bound by the route (`HostWorkspaceRepository`, keyed by
`SettingsViewModel.workspacePickerServerId`), not by a repository argument here — see
[SettingsViewModel § Configuration/usage](settings-viewmodel.md#configuration--usage). #715 closed
the other half: `settings` now builds `SettingsViewModel(preferences, repository(serverId), serverId, hosts())`
— `archivedDiscussionCount` reads the **owner's** exact-host repository (below) rather than the
compatibility one, so the number on the Storage row matches the archive its own row opens. Under a
blank owner that facade is backed by a permanently-`null` repository, whose cold reads are
`emptyList()` — a count of zero, the honest answer for a destination owning no host.

`ThreadDestinationFactory.archive(handle)` (#715) is the fourth destination method, and the Archive
route's owner counterpart to `settings` above: it reads the **same** `serverId` key from the
`SavedStateHandle`, but — unlike `settings` — a blank owner here is never valid (`Routes.ARCHIVED_DISCUSSIONS`
is a required path segment, and [`HostDestination`](navigation.md#archive-a-required-owner-destination-two-doors-715)
rejects an unresolvable one before this method is reached). It builds
`ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))`, where `repository(serverId)`
is #636's exact-host seam verbatim — resolved **once**, at construction, so a selection change,
reconnect or unpair can move neither the rows nor a pending restore, and a host holding the same
conversation id under a different owner is unreachable by construction. The private `hostLabel(serverId): Flow<String>`
helper maps the existing `hosts()` projection (below) down to a single resolved display name
(`displayName` when non-blank, else `serverId`) — the same name-or-id fallback `SettingsHostRow.name`
uses, deliberately duplicated rather than shared (one line; sharing it would mean exporting a
resolution helper for a single caller, following #177's precedent for `Conversation.displayName()`).
Only that resolved `String` crosses into `ArchivedDiscussionsViewModel`; neither a `SettingsHost` nor
a stored `PairedServerEntry` reaches it, so no pairing token or server static key does either.

`settings` also builds the private `hosts(): Flow<List<SettingsHost>>` that becomes
`SettingsViewModel`'s fourth constructor argument — every saved host's identity plus its own live
status, joined from two sources because neither alone carries all four display fields:
`registry.hostConnections` (`RelayConnectionRegistry`, exposed here as the factory's own
`hostConnections` property) carries the per-host `status: StateFlow<ConnectionStatus>` and
`displayName`, and re-emits on every store revision so a rename, a pairing and an unpair all reach
the projection; the relay URL lives only in the stored record, so the `map` reads
`store.list()` once per `hostConnections` emission and joins by exact `serverId` — one decrypt for
the whole saved-host blob per revision, not one `PairedServerCollectionStore.loadById` per host
decrypting it N times for the same data. The three display fields (`serverId`, `displayName`,
`relayUrl`) are copied out explicitly into `SettingsHost`; the joined `PairedServerEntry` never
escapes the `map` block, because it carries the pairing token and server static key and
`SettingsHost` has no redacting `toString`. In demo mode (`useRelay = false`) `hosts()` instead
yields a single fixed `SettingsHost` for `HostConversationSource.DEMO_SERVER_ID`, mirroring the
`selectedServerId()` demo shape below. The returned `Flow` is deliberately cold — `SettingsViewModel`
lifts it with its own `stateIn`, so two Settings entries on the back stack (e.g. after Back and
reopen under a different capture) do not share one projection or its subscription lifetime.

Relay destinations capture the exact `connectionFor(serverId)` retained bundle.
Their repository is a `StableConversationRepository` over **that coordinator's**
`currentRepository` stream. Capturing `HostConversationSource.repositoryFor`'s
concrete live repository would strand the destination after reconnect; injecting
the global compatibility facade would redirect it on selection changes. The
destination facade adds no scope or jobs: cold reads switch with the owner stream,
and one-shot calls retain their existing arguments and errors. Disconnected or
handshaking owners yield empty/default reads and unavailable writes; B's healthy
connection cannot substitute for A's unavailable one.

The same bundle supplies the thread's supervisor state, live-session events,
current modal, modal answer/cancel and interrupt callbacks. Repository-backed
session/queue state, Send, Reset session, queue drop and existing thread actions
use the owner facade. Literal Request/Retry use a facade over that same host's
coordinator. Compatibility selection cannot change an open prompt's display or
answer target, even with colliding conversation/modal ids. App preferences remain
shared. The navigation guard waits for saved-host initialization and rejects
unknown/removed hosts before constructing their ViewModels; there is no fallback
to selection.

Ownership also covers descendant injection. `HostWorkspaceRepository` provides
`LocalWorkspacePickerRepository` around the thread and flat channel screen,
remembering a factory repository for the route host or captured picker host.
`WorkspacePicker` uses it for recents and folder creation, so its returned path
and the ViewModel's final action reach the same host across selection/reconnect.
Without that provider, a correctly bound ViewModel can still combine B's folders
with A's workspace change. The picker's nullable-local fallback remains the
compatibility Koin binding for Settings; #749 gave the Settings destination itself
exact-host ownership of its identity and connection status, but deliberately left
the default-workspace picker on this compatibility binding — that migration is #714.

### Exact-host Retry and lifecycle

The thread's `ConnectionStateSource.retry()` calls
`RelayConnectionRegistry.retryHost(serverId, expectedBundle)`. The registry holds
the same monitor used by reconciliation, removal, replacement, background close
and disposal while checking foreground state, disposal and exact bundle identity,
then running the supervisor's nonblocking Retry. A queued Retry cannot reopen a
retired or background owner, and it never retries another selected host.

Checking identity before calling the supervisor outside this monitor leaves a
check/use race: bundle teardown closes the supervisor but does not permanently
disable its `retry()`/`connect()` path. Keep validation and the nonblocking call
under one lifecycle boundary. Literal-screen Retry is a separate snapshot re-fetch;
it retains the destination repository and existing snapshot error mapping.

The tree's per-host reconnect control (#840) mirrors this same pairing rather
than adding a second one. `HostConversationSource.relay(...)` gained a trailing
`retry: (String) -> Unit` that resolves `{ id -> registry.connectionFor(id)?.let { registry.retryHost(id, it) } }`
— identical to the destination factory's above — and a public `retryHost(serverId)`
that reads `disposed` under its own monitor and calls `retry` **outside** it, so
the source never holds its lock while taking the registry's; no lock order is
introduced. `ChannelListViewModel.reconnectHost(serverId)` forwards to it
directly. `demo(...)` keeps the no-op default, since the demo host is always
connected. See [ChannelListScreen § Host row reconnect control](channel-list-screen-tree-and-controls.md#host-row-reconnect-control-840)
for the row side.

### Demo binding

With `useRelay = false`, the same source type exposes exactly one host:
`HostConversationSource.DEMO_SERVER_ID` (`demo`), local name `Demo`, with both link
states `Connected`. Lists and exact lookup use the existing
`FakeConversationRepository` singleton. Only the exact id `demo` resolves; saved
relay hosts never enter these snapshots or lookups, even though their connection
owners still exist.

Demo thread, literal and picker repositories also resolve that same singleton.
The thread gets `FakeConnectionStateSource` (`Connected`) and its inert default
live-event, hidden-modal and control dependencies. Saved real hosts never supply
demo content, permissions or controls. `selectedServerId()` returns `demo` in this
mode; in relay mode it captures the current exact selected host for temporary
flat-list entry points, and (since #749) for the channel list's settings gear —
`ChannelListEvent.SettingsTapped → navController.navigate(Routes.settings(destinations.selectedServerId()))`
captures the owner once, at tap time, into the Settings route; see
[Navigation § Settings](navigation.md#settings-an-optionally-owned-destination).

`ChannelListViewModel` receives this shared source as its third constructor
dependency and exposes [host-qualified state and actions](channel-list-viewmodel-projection.md#state-projection).
`DiscussionListViewModel` receives it as its second dependency for
[host-qualified navigation and captured promotion](discussion-list-viewmodel.md#wiring).
Both retain compatibility state/events and bare-id navigation APIs, but production
routes collect only `hostNavigationEvents`. The flat list still displays the
selected facade or fake; its adapters call host-aware row/create/picker/promotion
commands and project captured picker/promotion visibility into the existing screen
state. Asynchronous completion retains the captured host. See
[flat-list compatibility](navigation.md#temporary-flat-list-compatibility);
tree rendering remains #641. #749 moved the Settings destination itself (identity +
connection status) to exact-host ownership; #714 and #715 closed the two remaining
compatibility-bound pieces Settings still showed — the default-workspace picker and
the archived-discussion count, respectively — so nothing on this destination reads
compatibility selection any longer.

## Related

- [Dependency injection](dependency-injection.md) — the parent document: `appModule`,
  the flag-gated selector pattern, testing and configuration.
