# Dependency injection — host conversation source and destination ownership

Split from [Dependency injection](dependency-injection.md) — read that first for Koin
wiring, `appModule`, the flag-gated selector pattern, testing and configuration. This
document covers how `HostConversationSource` aggregates per-host state and how host-qualified
destinations (thread, settings, archive) get their exact-host dependencies: host
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
design (see [conversation cache § Failure model](conversation-cache-failure-model.md#failure-model--graceful-reads-reporting-mutations))
— so only a non-empty read publishes anything, and only a live list may ever
empty a host.

### Attention state (#877)

Every conversation row on every host carries exactly one `ConversationAttention`
(`di/ConversationAttention.kt`): `WaitingForAnswer`, `Running`, `Unread`,
`Idle`, declared in precedence order and resolved by the total function
`resolveAttention(waiting, running, unread)` — desktop's
`resolveConversationStatus` order (`../pyrycode-desktop/src/renderer/src/store/conversationStatus.ts`)
exactly, with no mobile-only state. An earlier mobile-only `Failed` state sat after `Running`
and before `Unread`; #1451 removed it to match desktop, which has no failed state — a turn
that ends Failed or StoppedEarly while not viewed now folds as an ordinary completed turn and
uses the applicable shared or local unread rule below.
`TreeConversationRow` preserves the green Unread dot and existing accessibility
descriptions; other attention consumers use the same precedence.

`HostConversationSource.attention: StateFlow<Map<String, Map<String, ConversationAttention>>>`
is keyed by `serverId` then conversation id and holds **non-Idle entries only** — a
missing id is Idle. It is a sibling of `snapshots`, published from the same `Held`
entry but folded and combined independently, so an attention change never re-runs
`observeHostEntry`'s preview `flatMapLatest` and a preview change never touches
attention. `ChannelListViewModel.hostState` joins the two at the entry level (see
[state projection § Attention join](channel-list-viewmodel-projection.md#attention-join-877)).

The fold itself is the internal, pure `HostAttentionState` data class — no clock, no
I/O or logging. Turn ids and local row tokens are equality keys; durable entry ids
are compared as unsigned values.

**Shared unread (#1883).** For each conversation with a present confirmed `readUpTo` (wire `read_up_to`),
unread is exactly `latestEntryId > readUpTo` (wire latest is `latest_entry_id`). An unknown latest id asserts no durable
unread; zero is a valid mark. `observeHostReadMarks()` projects the repository's existing
`ConversationReadMarks` map, with no new request, inbound consumer or checkpoint fold.
List refreshes, confirmed replies/peer pushes and received history/live/replay durable
identity recompute attention immediately. `conversation_updated` supplies a mark,
not a new latest id. The mark is shared across paired clients on that daemon host,
never across hosts with equal conversation ids. See the
[repository read facts](remote-conversation-repository.md) and
[phone read proof](../../e2e-interactive-stream.md#phone-read-mark-proof-1912).

Opening or viewing a supported conversation, adding local rows and completing a turn
do not change its local positions or override the shared comparison. Completion still
updates bounded counted-turn bookkeeping for once-per-turn alert candidates. A modern
thread remains unread until the daemon confirms a mark covering its newest known entry.
Local UUIDs/turn ids never become durable ids; notification cancellation is a separate
consumer concern.

**Older-daemon fallback.** When `readUpTo` is absent, only that conversation uses
the existing host-keyed, persisted `ReadPosition` rules below. Cached read-field
presence cannot negotiate support for a connected daemon.

**`busy` (#1452).** A conversation blinks `Running` not only while a turn runs, but also while it
is stalled, retrying the API, compacting or resetting — desktop's `isWorking`
(`../pyrycode-desktop/src/renderer/src/store/conversationStatus.ts`). `HostAttentionState.busy` is
a sibling of `running`, folded by `withBusy(ids: Set<String>)` (full-set replace, since the
repository already holds the edges) rather than by `onEvent`, which stays `running`-only. `resolve`
treats `running = id in running || id in busy`, and `disconnected()` clears `busy` alongside
`running`. The ids come from `ConversationRepository.observeBusyConversations()` (default
`flowOf(emptySet())`), implemented in `RemoteConversationRepository` as the union of
`StallProjection`, `ApiRetryProjection`, `CompactingProjection` and `ResettingProjection`'s own
host-wide `observeIds()` reads — see [Stall state](stall-state.md#how-it-surfaces-in-the-repository),
[API-retry status](api-retry-status.md#how-it-surfaces-in-the-repository),
[Compacting state](compacting-state.md#how-it-surfaces-in-the-repository) and
[Resetting state](resetting-state.md#how-it-surfaces-in-the-repository). The four edges are reused
**unchanged**, because the thread's own indicators read the same projections, which keeps two
deliberate differences from desktop's `isWorking`: a stall's blink clears on any decoded live
event rather than only `turn_state`, and a reset's blink also clears on `session_transition`,
since desktop has no such edge. No new visuals: a busy conversation resolves to the existing
`ConversationAttention.Running`, which already draws the blink.

- `onEvent(event, viewing)` folds one `LiveSessionEvent`. `TurnState` Thinking/Responding
  adds the conversation to `running`; `TurnState` Idle clears `running`. `TurnEnd`
  clears `running` and, unless its `turnId` is blank, over `MAX_TURN_ID_CHARS` (256), or
  already **counted** — in the bounded per-conversation `counted` list (newest last, capped
  at `MAX_COUNTED_TURNS_PER_CONVERSATION` = 16) or equal to the stored position's
  `completedTurnId`/`readTurnId` — records the turn as counted. Only for fallback
  conversations, it also sets a `ReadPosition`
  (read immediately if `viewing`, else `completedTurnId` only, keeping the prior
  `readTurnId`). `completed` draws no distinction by how the turn ended — a failed, interrupted
  or early-stopped turn sets that same `ReadPosition` like any other completed turn (#1451
  removed the separate `failed` fold this used to feed; [#1357](turn-outcome-indicator.md)
  later removed the outcome classifier itself, which this fold never called), so it resolves
  Unread when not viewed and Idle once opened. Every other event is a no-op.
- For fallback conversations, `rowsAdded(conversationId, viewing, token)` (#1361) is the other way a conversation turns
  Unread: a row — a text delta's bubble, a tool call, a banner, a session boundary, a
  compaction divider, a refusal, an attachment offer, a `TurnEnd` — appended to the thread,
  rather than only a turn ending. A compaction divider now counts from two triggers (#1358):
  the `compacting` falling edge itself appends one as soon as the compaction ends, before any
  `compaction_boundary` frame arrives and replaces it in place, so ending a background
  conversation's compaction marks it Unread on that edge alone — `HostConversationSourceAttentionTest`
  opens the chat before asserting the edge's own clear, the same exception it already held for a
  `session_transition`. Desktop's `isConversationUnread` counts rows the same way;
  this fold is mobile's trigger change onto mobile's own storage and bounds. A viewed
  conversation is unchanged (opening is what reads it, below), and a conversation already
  Unread keeps its stored position rather than overwriting it — a fresh token there would
  drop the turn id `isCounted` needs to recognise a `TurnEnd` re-delivered to a cold process.
  Otherwise the conversation's position becomes `ReadPosition(token, previous readTurnId)`,
  moved to the newest end of the bounded map exactly as `onEvent`'s `TurnEnd` branch does.
  `token` is a value **this phone mints** per call — a random UUID, not a daemon id — so it
  can never collide with a stored read token or a turn id; `ReadPosition`'s two fields are
  therefore no longer both daemon-authored (contrast the KDoc update in
  `ConversationCache.kt`). A `TurnEnd` that follows rows already counted by `rowsAdded` still
  runs its own counted/alert bookkeeping — the two folds are independent, so a turn with no
  rows still marks Unread as before, and a turn after rows still alerts once.
- `opened(conversationId)` is a no-op with shared marks; otherwise it sets `readTurnId = completedTurnId`.
- `disconnected()` clears `running` and `busy` — read facts and positions survive a lost
  connection in memory, matching the lifecycle driver closing a supervisor and the
  collector below seeing a null repository.
- `restored(stored)` merges positions read from the cache under the live ones — a live
  completion always wins over a stale restore.
- `resolve(prompts, batches)` returns the non-Idle map: `WaitingForAnswer` comes from **any**
  `ModalUiState.Open` in `prompts` whose `conversationId` matches and is non-blank (a blank-id prompt
  belongs to no row, #816) or from `batches.batchFor(id) != null`. Before [#1338](current-modal-state.md#related)
  `resolve` took one `ModalUiState` — the host's single most-recently-shown prompt — so a second chat's
  prompt silently evicted the first's waiting state; `resolve` now takes the whole
  `List<ModalUiState.Open>`, matching desktop's `selectHasOutstandingFor`
  (`src/renderer/src/store/modalPrompts.ts`), and a chat waits while *any* outstanding prompt names it.
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
`ConversationViewing` handle of its own, so opening it advances the fallback read
position via `opened`, but does not hold the fallback conversation read past that one call the way a thread's view does. Neither signal
is read proof for a modern daemon.

`HostConversationSource.launchAttention(entry)` runs independent collectors under the same
`entry.job` `reconcile` already cancels on bundle replacement or removal: the live-event
fold (reading `viewing` under the class monitor via `updateAttention`), a `repositories`
null emission → `disconnected()`, a second, independent `connection.repositories.collectLatest`
that folds each repository's `observeBusyConversations()` into `busy` via `withBusy(ids)`
(#1452, a null repository observes nothing), a third `connection.repositories.collectLatest`
(#1361) that folds each repository's `observeThreadRowCounts(): Flow<Map<String, Int>>` (a
defaulted empty `ConversationRepository` member; `RemoteConversationRepository` returns
`ThreadProjection.observeRowCounts()`, the per-conversation thread list's size) against a
local `seen` map that **starts empty on every repository**, not on whatever counts the
collector happens to observe first — a connection's `ThreadProjection` itself starts empty, so
rows a replay (`last_event_id`) delivered before this collector's first read are genuinely new
against that zero baseline, not an in-place jump to be ignored. Each emission's conversations
whose count exceeds `seen[id] ?: 0` fold through `rowsAdded(id, viewing.isViewing(serverId,
id), UUID.randomUUID().toString())` in one `updateAttention` call, then `seen` becomes that
emission; `collectLatest` cancels the previous repository's `seen` along with its collection on
replacement, so the next repository restarts at zero rather than carrying over the old one's
counts. The combined `modals`/`questionBatches` → `resolve`, and, only when a `cache` is
bound, a one-shot restore followed by a collector over each distinct positions map, written
through `ConversationCache.writeReadPositions`, complete the collection paths. All of them
route through one synchronized `updateAttention` mutation, which rejects retired bundles.
The host-read-facts collector uses `collectLatest`, and row/read-fact callbacks also
check exact current repository identity to reject superseded callbacks. A disconnect
retains last-known shared attention; each non-null replacement clears prior read facts
exactly once, before any consumer mutation. Restoration supplies only local positions.

**Replacement ordering matters (#1883).** Cancelling separate collectors does not
order the read-fact reset before replay rows or completion events. `Held.attentionRepository`
is established inside the same monitor as every attention mutation, so replacement
rows/completions cannot consume a previous daemon's support and lose fallback bookkeeping.
Consumer-first dispatcher regressions force both orderings. No suspension occurs under
that monitor; entry removal, bundle replacement and disposal cancel owned jobs.

For fallback tracking, rows the phone already had re-enter a repository's `ThreadProjection` only through
`observeMessages`'s `backfill_since` and `requestHistory` pages, and only `ThreadViewModel`
asks for those, whose lifetime is the `ConversationViewing` view — so a conversation's own
backfill, including the one a reconnect repeats against the new repository, lands while it is
viewed and `rowsAdded` leaves it alone. An attachment offer's first-seen row already comes
from `AttachmentOfferProjection.apply` (#983) appending into the same thread store, so it
needs no separate counting path.

**Known gap: a disconnect can race a stale busy write.** The null-repository collector clears
`busy` with `disconnected()`, and the separate `observeBusyConversations()` collector writes
it with `withBusy(ids)` — two different coroutines, with no ordering between them. In the gap
`RelayRepositoryCoordinator.teardownActive` leaves between nulling `activeConnection` and
cancelling the repository scope, a busy emission already in flight (or a rising `stall`/
`compacting` frame applied right there) can take the monitor after `disconnected()` runs and
write the retired repository's ids back; `isCurrent(entry)` checks only the generation, not
repository identity, so it does not catch this. Flagged as a verifier SHOULD FIX on the #1452
PR and left open: the existing `running` path has the identical shape (the same gap can replay
a stale `turn_state`), the window is narrow, and the next connection's first — empty — emission
heals it. A fix needs to close both paths together, either by folding the clear into the same
`collectLatest` block as the busy write (so cancellation serializes them) or by guarding the
write with `connection.repositories.value === repository`, the way the snapshot path's
`update(entry, repository)` already does.

**Fallback-only known gap: a restore landing after a view opens does not re-mark it read.** The restore
collector folds `attention.restored(written)` directly, without re-applying `opened` for
this host's currently-viewed conversations — unlike the `viewing.viewed` collector, which
does re-open on every change. Concretely: process death while the operator is on a thread,
Android restores straight into that thread (registering the view against empty positions),
and the stored `ReadPosition(T, null)` that arrives afterward reads Unread while the thread
is open, and stays Unread after backing out. Flagged as a verifier SHOULD FIX on the PR;
this historical finding remains scoped to local fallback positions. The fix belongs
with whichever ticket next touches `launchAttention`'s restore branch (candidate: fold
`opened` for this host's `viewing.viewed` entries right after `restored`).

### Attention alerts (#685)

`HostConversationSource.alerts: SharedFlow<AttentionAlert>` is `attention`'s sibling output, for a
consumer that needs identities rather than states — a state map alone cannot tell a new turn from a
still-running one, or a re-shown prompt from a fresh one. `AttentionAlert(serverId, conversationId, kind, key)`
names one thing that may deserve a notification: `Kind.TurnCompleted` with `key = turnId`, or
`Kind.Prompt` with `key = "modal:$modalId"` / `"batch:$questionBatchId"`. Every field but `serverId` is
daemon-authored and used only as an equality key, exactly like the ids [§ Attention state](#attention-state-877)'s
own fold treats the same way.

- **Turn:** emitted inside `updateAttention`'s live-event collector, immediately after the fold's
  `onEvent` call, by comparing `attention.counted[conversationId]` before and after. It fires only when
  the fold counts that `TurnEnd` for the first time — reusing [§ Attention state](#attention-state-877)'s
  once-per-turn rule verbatim (a blank or oversized turn id never counts; a re-delivered turn is a no-op;
  a restored `positions` entry recognises the latest turn after process death). Emitted whether or not
  the conversation is viewed — a backgrounded thread composition can stay alive.
- **Prompt:** emitted inside the modal/batches collector by diffing the current prompt-key set
  (the private `promptKeys(modals, batches)`) against `Held.prompts`, the previous set for that
  generation; only keys new since the last emission alert. A `StateFlow` re-publish of the same modals or
  batches therefore emits nothing. Whether a reconnect re-emits depends on whether the key actually left
  `Held.prompts` in between: a question batch that a reconnect drops and then shows again *does* re-emit,
  because its id left the held set while it was gone — suppressing that repeat is the consumer's job (see
  [Push messaging service § Attention alerts and the tap route](push-messaging-service.md#attention-alerts-and-the-tap-route-685)),
  not this flow's. **Before [#1337](current-modal-state.md#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure),
  a retained permission modal did not re-emit across a reconnect:** the single `ModalUiState` this source
  read carried the same open modal straight across the gap, so `modal:$modalId` never left `Held.prompts`,
  and a re-shown copy with the same id (even with different prompt text) was not a new key. **Since #1337, a
  *new* connection clears `coordinator.hostModals`** (a plain teardown still does not), so the prompt this
  source reads goes empty → populated across that reconnect when the daemon re-sends the same prompt —
  `modal:$modalId` now **does** leave `Held.prompts` while the connection is re-established, and this flow
  re-emits the alert exactly like a dropped-and-reshown question batch. Suppressing that repeat is
  still the consumer's job: `AttentionNotifier`'s `AlertLedger` (see [Push messaging service § Attention
  alerts and the tap route](push-messaging-service.md#attention-alerts-and-the-tap-route-685)) already
  dedupes on the alert's digest regardless of how many times this flow re-emits it, so the push path still
  posts exactly one notification across the reconnect —
  `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` is the live proof.
  `HostConversationSourceAttentionTest.aPromptAlertsOncePerModalOrBatchAndABlankConversationPromptAlertsNothing`
  still pins the no-second-alert contract for a same-value re-publish that never passes through empty —
  the case this flow's own diff handles identically before and after #1337. `promptKeys` drops a
  blank-`conversationId` modal or batch, the same way `resolve` above does — its tap could never route to
  anything.
  **Since [#1338](current-modal-state.md#related), `promptKeys` keys every outstanding prompt**, not only
  the host's most-recently-shown one: `HostConversationConnection.modal: StateFlow<ModalUiState>` became
  `modals: StateFlow<HostModalState>`, wired from `coordinator.hostModals`, and `promptKeys` maps
  `modals.outstanding` to one `"modal:$modalId"` key per prompt. A second chat's prompt arriving no longer
  evicts the first's key, so each prompt still alerts exactly once —
  `HostConversationSourceAttentionTest.everyChatHoldingAPromptWaitsAndAlertsOnce_andAnsweringOneLeavesTheOther`
  pins two prompts held at once alerting once each, a re-emit of the same list alerting nothing, and
  answering one leaving the other's waiting state and key untouched.
- **Hot, not replayed, bounded:** `MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)`,
  emitted with `tryEmit` under the same class monitor `updateAttention` already holds — no new lock and
  no suspension inside the fold. A late subscriber sees nothing emitted before it subscribed; the one
  production consumer, `AttentionNotifier`, is bound `createdAtStart` so it is always already subscribed
  before any host can connect.

### Exact-host repository access

`repositoryFor(serverId)` resolves `RelayConnectionRegistry.connectionFor` by exact
id, without consulting compatibility selection. Unknown, removed, disconnected or
handshaking hosts return `null`, even if their snapshots still contain rows.
The coordinator's internal `liveRepository()` checks one active connection under
its teardown lock: the owner must be active, its transport must be identical to
the supervisor's current transport, and that connection's actual pump must be
`Open`.

Collection of `currentRepository` remains asynchronous and can lag replacement;
since #1884 its synchronous `.value` and replay cache select through `liveRepository()`.
Cached rows or collected status therefore do not establish current availability.
`currentReadMarks(serverId, conversationId)` uses that authoritative selection to
read the remote repository's atomic facts, retaining accepted facts while offline
but rejecting old support for a replacement. The host-first `readMarks` flow triggers
[notification reconciliation](push-messaging-service.md#confirmed-daemon-read-cancellation-1884);
the synchronous lookup prevents a queued covered trigger from cancelling a new
unread completion, or spending it against a retired repository's mark. Delaying
real coordinator publication, rather than substituting a repository flow, is needed
to test this ordering. This lookup returns availability at the time of the check;
a later disconnect can still make an operation fail. See the
[coordinator's availability checks](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493).

### Destination ownership

`ThreadDestinationFactory` is a scope-free singleton in `hostConversationModule`.
The Koin `viewModel` definition calls `thread(handle, preferences)`
(`literal(handle)`, the matching method for the now-retired literal-screen
destination, was removed by [#883](../../specs/architecture/883-retire-literal-screen.md)); the factory reads `serverId` from the destination's
`SavedStateHandle`, while each ViewModel reads its unchanged host-local
`conversationId`. [Navigation](navigation.md#host-qualified-destinations) supplies
both arguments and scopes ViewModels to individual back-stack entries.

`ThreadDestinationFactory.settings(handle, preferences)` (#749; dropped its third `repository`
parameter in #715) is the third destination method, in the same shape but with two deliberate
differences from `thread`: the owner it reads from the `SavedStateHandle` is **optional**
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
use the owner facade. Compatibility selection cannot change an open prompt's display or
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
under one lifecycle boundary.

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

Demo thread and picker repositories also resolve that same singleton.
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
- [Current-modal state](current-modal-state.md) — the coordinator-side `HostModalState` fold that
  `HostConversationConnection.modals` is wired from, and [#1338](current-modal-state.md#related)'s removal
  of the single-value `currentModal` projection this source and `resolve` used to read.
