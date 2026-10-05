# ChannelListViewModel

Exposes ordered host-qualified channel/chat state and explicit host actions through
`HostConversationSource`, plus the fold and selection state
[ChannelListScreen](channel-list-screen.md)'s assembled conversation tree needs (#731).
Every action the screen can trigger — row taps, folds, (since #738) both add
controls and the host row's edit control — is
host-qualified and resolved from this view model's own methods; `ChannelListScreen`
carries no other state model. The flat compatibility
`ChannelListUiState` projection, its `ChannelListNavigation` one-shot channel and its
`onEvent` reducer retired with the floating action button that was their only
consumer (#738) — see [Configuration](#configuration) for what that removed from
the constructor. [DiscussionListScreen](discussion-list-screen.md) (unreachable
since #731) remains the sole consumer of the sibling
[selected-host compatibility adapter](navigation.md#temporary-flat-list-compatibility)
elsewhere in the codebase; this view model no longer touches it at all.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListViewModel.kt`.

## What it does

`hostState` preserves each source host's metadata and active rows, adds a recent-three
chat slice, full chat count, host-local optional previews and (#729) each host's
channels/chats grouped by workspace for other consumers, and (since #904) exposes the open Add workspace
modal's target and its own host's recent folders. Since #731 it also carries the assembled tree's **collapsed** fold keys and
the **last-opened** selection target — both mutated only by the screen's fold and row
taps, never reconciled against an incoming snapshot (see
[state projection](channel-list-viewmodel-projection.md)). Since #877 each entry also
carries that host's non-Idle `ConversationAttention` states, read per row through
`attentionFor` (see [state projection § Attention join](channel-list-viewmodel-projection.md#attention-join-877));
drawn on the row's leading dot by #878, see [ChannelListScreen — conversation tree and
controls § Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878). Host row activation and
successful creation emit `HostConversationTarget(serverId, conversationId)` on
`hostNavigationEvents` and record that target as `selected`. Creation retains its
explicit host through the Chats-section confirmation: `openCreateChat(serverId)` holds the clicked
host, and `submitCreateChat()` sends `createDiscussion(null)` through that host's repository. The
daemon selects the folder independently of app preferences. The retained Add workspace state is no
longer opened from the host row; the thread's picker remains available for folder changes.

Both chat creation paths read the persisted app-wide remembered raw model after creation, including
after an app restart. They apply it only when the created conversation's own host publishes an
untruncated exact-value row for its agent;
otherwise the new session keeps its inherited model. The relay's create reply has no session ID, so
the list reads that conversation's session settings for the authoritative ID, then awaits the model
write before opening the thread. An absent or default remembered value, a missing menu or settings
reply, or a failed write still lets the created chat open. A failed write can leave the server's final
choice unknown; the thread reads its saved setting when it opens. Opening or switching to an existing
chat or channel never uses the remembered model, even when its saved choice is intentionally empty. See
[Remembered model choice](thread-composer-footer.md#remembered-model-choice-1222) for how a successful
thread selection updates the preference.

## Shape

```kotlin
data class HostChannelListEntry(
    val host: HostConversationSnapshot,
    val recentChats: List<Conversation>,
    val chatCount: Int,
    val recentChatLastMessages: Map<String, Message> = emptyMap(),
    val channelGroups: List<HostWorkspaceGroup> = emptyList(),  // #729
    val chatGroups: List<HostWorkspaceGroup> = emptyList(),     // #729
    val attention: Map<String, ConversationAttention> = emptyMap(),  // #877 — non-Idle rows only
) {
    /** [host]'s one attention state for [conversationId]; every row has one, Idle by default (#877). */
    fun attentionFor(conversationId: String): ConversationAttention = attention[conversationId] ?: ConversationAttention.Idle
}

data class HostChannelListState(
    val hosts: List<HostChannelListEntry> = emptyList(),
    val addWorkspace: AddWorkspaceState? = null,                     // #904 — open Add workspace modal's target
    val addWorkspaceRecent: List<String> = emptyList(),              // #904 — that host's own recent folders
    val collapsed: Set<TreeFoldKey> = emptySet(),                    // #731 — collapsed, not expanded
    val selected: HostConversationTarget? = null,                    // #731 — last opened from this list
    val hostEditor: HostEditorState? = null,                         // #744 — open Edit host modal's target
    val workspaceEditor: WorkspaceEditorState? = null,                // #905 — open Edit workspace modal's target
    val createChannel: CreateChannelState? = null,                    // #958 — open Create channel modal's target
    val createChat: CreateChatState? = null,                          // immediate Chats-plus request
) {
    /** True when the host snapshot has both connection legs Connected. */
    fun isHostConnected(serverId: String): Boolean =
        hosts.any { it.host.serverId == serverId && it.host.connectionStatus.isLive() }
}

/** The Add workspace modal's target and flags (#904), shaped like [HostEditorState]. [selected] is the
 *  folder OK will start a chat in — a recent folder picked, or one just created on [serverId]; creating
 *  never sets it via a chat start. [busy] covers either write in flight, since the modal's one loading
 *  flag gates both a folder creation and a chat start; [createFailed] / [startFailed] are flags, never a
 *  daemon message, so the UI resolves each to a static string. */
data class AddWorkspaceState(
    val serverId: String,
    val selected: String? = null,
    val busy: Boolean = false,
    val createFailed: Boolean = false,
    val startFailed: Boolean = false,
)

enum class ConversationTreeSection { Host, Channels, Chats }         // #731/#1189

/** A foldable node identified by its host and kind, independent of names and folders. */
data class TreeFoldKey(                                              // #731/#1189
    val section: ConversationTreeSection,
    val serverId: String,
)

data class HostConversationTarget(val serverId: String, val conversationId: String)

class ChannelListViewModel(
    private val appPreferences: AppPreferences,
    private val hostSource: HostConversationSource,
    private val pairedServers: PairedServerCollectionStore,          // #744
) : ViewModel() {
    // State projection and action behavior are described below.
}
```

`ChannelListViewModel`'s constructor dropped `repository: ConversationRepository` in #738 — it was read only
by the retired compatibility `state` projection and the `onEvent` reducer it fed; `hostSource` already
supplies every host-qualified read and write this VM performs. The one call site,
[`AppModule`](dependency-injection.md), lost the matching `get()` argument, then gained a third — the
`PairedServerCollectionStore` `AppModule` already binds through `ObservablePairedServerStore` — when #744
added `pairedServers` for the Edit host modal's read and write. Since #751 `pairedServers` is handed
straight to a [`HostEditorController`](host-editor.md), the seam [Settings](settings-viewmodel.md) now
drives too; nothing in this class reads the store directly any more.

**`HostEditorState` and the machine that produces it moved to [`ui/host/HostEditor.kt`](host-editor.md)
in #751** — this view model held both from #744/#745 until then. See that document for the state's
shape and field-by-field rationale (display text and the target id only, never the `PairedServer`
record; unclamped identity/relay text; flags rather than messages) and for the controller's six
transitions, their `compareAndSet` terminal-transition discipline and their guard-against-sibling-write
concurrency rule. What stays true here: this class constructs its own `HostEditorController` instance
over its own `viewModelScope`, so its editor is never shared with Settings' — see [Wiring](#wiring).

`HostChannelListEntry`, `HostChannelListState`, `ConversationTreeSection`, `TreeFoldKey` and
`HostConversationTarget` are top-level (siblings of the VM), not nested inside it — call sites import them
directly rather than through `ChannelListViewModel.`-qualified paths. The screen-name prefix on
`HostChannelListEntry` / `HostChannelListState` carries enough disambiguation. `HostEditorState` (imported
from `ui.host`) is likewise unqualified at every call site.

## How it works

See [state projection and event handling](channel-list-viewmodel-projection.md)
for `hostState`'s `stateIn` sharing configuration, its projection (including #729's
workspace groups and #731's fold/selection state), the per-host preview fan-out and
one-shot navigation for the host-qualified creation and picker methods.

## Wiring

Koin supplies three required constructor dependencies from
[`appModule`](dependency-injection.md):

```kotlin
viewModel { ChannelListViewModel(get(), get(), get()) }
```

They are `AppPreferences`, the shared `HostConversationSource` and (since #744) the
`PairedServerCollectionStore` `appModule` already binds through
`ObservablePairedServerStore` (#738 dropped a prior third, `ConversationRepository`
— see [Shape](#shape) above). `HostConversationSource`'s
included `hostConversationModule` selects the matching host source. Demo mode exposes
only `HostConversationSource.DEMO_SERVER_ID`
(`demo`, display name `Demo`)
and resolves that same fake singleton for host reads and creation. Saved relay hosts
never enter the demo source. Demo creation reads `defaultWorkspace("demo")`: its
explicit value or scratch, even when a paired host owns the migrated legacy path.
`AppPreferences` is shared with Settings, whose workspace access still uses the
[transitional legacy API](app-preferences.md#what-it-does).

`PyryNavHost`'s `Routes.CHANNEL_LIST` destination collects only `hostState`. Row taps and
fold toggles map straight to `vm.onHostRowTapped(event.target)` / `vm.onFoldToggled(event.key)`,
since the row already carries its own host. The Chats-section plus maps
`TreeHostChatAddTapped(serverId)` to `vm.openCreateChat(serverId)`; Create and Cancel call
`vm.submitCreateChat()` and `vm.dismissCreateChat()`. The held dialog target, rather than
`destinations.selectedServerId()`, selects the repository. Since #744 the edit control follows the same
rule a third time: `TreeHostEditTapped(serverId) -> vm.openHostEditor(serverId)`,
`HostEditNameSubmitted(name) -> vm.submitHostName(name)` and `HostEditDismissed -> vm.dismissHostEditor()`.
\#745 adds three more events off the open modal's `Unpair host` action, none carrying a `serverId` — the
target is already the open editor's: `HostUnpairRequested -> vm.requestHostUnpair()`,
`HostUnpairConfirmed -> vm.confirmHostUnpair()` and `HostUnpairDeclined -> vm.declineHostUnpair()`.
\#840 adds a fourth control on the same rule: `TreeHostReconnectTapped(serverId) -> vm.reconnectHost(serverId)`.
`reconnectHost` is a one-line forward to the shared `HostConversationSource.retryHost(serverId)` — see
[Dependency injection § Exact-host Retry and lifecycle](dependency-injection-host-conversation-source.md#exact-host-retry-and-lifecycle)
— and touches no VM state: not `collapsedKeys`, not the snapshot, not the editor.

**Conversation editing belongs to the thread.** #1582 removed the list's chat editor
state and both conversation editors' flows, entry points and disconnect cleanup.
`HostChannelListState` has neither `chatEditor` nor `channelEditor`. The shared channel
controller and state types retain their current list package; the thread remains their
production owner. Their regression tests construct the controller directly.

**Create channel from a host section (#1189).** `openCreateChannel(serverId)` accepts a host in the
current snapshot even when it has no active channel. The section action leaves `CreateChannelState.cwd`
null, so `submitCreateChannel` passes null to the repository and the daemon chooses its default folder;
the app's saved per-host default is not consulted. A prompt-write failure retains the daemon-created id,
so retry writes only the prompt. The fold keys stay in a separate `StateFlow` from snapshots and the
selected target; folding never changes selection, and snapshot refresh or reconnect never resets folds.

**Former workspace-row pencil, host-resolved a sixth time (#905).** `TreeWorkspaceEditTapped(serverId,
cwd) -> vm.openWorkspaceEditor(serverId, cwd)`, `WorkspaceEditNameSubmitted(name) ->
vm.submitWorkspaceName(name)` and `WorkspaceEditDismissed -> vm.dismissWorkspaceEditor()`, plus three more
off the modal's own Archive step: `WorkspaceArchiveRequested -> vm.requestWorkspaceArchive()`,
`WorkspaceArchiveConfirmed -> vm.confirmWorkspaceArchive()`, `WorkspaceArchiveDeclined ->
vm.declineWorkspaceArchive()` — the same request/decline/confirm shape #745's unpair step established.
`openWorkspaceEditor` has no `HostWorkspaceGroup` to reopen from (that projection carries no label), so it
looks up the first channel or chat at that `serverId`/`cwd` in `hostSource.snapshots.value` and reads
`workspaceDisplayName(cwd, row.workspaceLabel)` — the row's own display rule, not a stored label read
directly; an unknown host or a `cwd` with no active row opens nothing. `submitWorkspaceName` applies the
label rule (`workspaceLabelFor`, in `ui/workspace/WorkspaceDisplayName.kt`) and rejects an over-bound
result before ever resolving a repository, so a refusal never reaches the daemon; otherwise it resolves
`hostSource.repositoryFor(target.serverId)` **at the press**, using the held editor target, and the
same `compareAndSet`-against-the-published-state discipline closes it. `requestWorkspaceArchive` /
`declineWorkspaceArchive` / `confirmWorkspaceArchive` mirror `HostEditorController`'s unpair three: request
and decline both refuse while `saving`, so a decline cannot race a confirm's own close, and a failed
confirm leaves `confirmingArchive` set so OK retries — `archiveWorkspace` leaves already-archived rows
archived, so a retry only touches the rows still active. Neither write patches a local copy of the row;
both rely on the host's own conversation stream re-emitting, the same discipline every other editor here
uses.

### `ChannelEditorController` (#667 / #1561)

`ChannelEditorController` (`ui/conversations/list/ChannelEditorController.kt`) is a plain class — shaped
like [`HostEditorController`](host-editor.md) (#751) — holding the Edit channel modal's machine that used
to live directly on `ChannelListViewModel`: a private `editor: MutableStateFlow<ChannelEditorState?>`
(target, saved name, saved mute flag `savedMuted` (#1021 — the host's stored `Conversation.muted` at open
time, then the value the daemon confirmed, the same pattern as `savedName`), and the
`saving`/`failed`/`archiveFailed` flags; its own `prompt` field stays at its `Reading` default and is never
read directly) and a private `reading: MutableStateFlow<Pair<HostConversationTarget,
ChannelPromptReading>?>` holding the latest stored-prompt reading tagged with the channel it belongs to.
`state: Flow<ChannelEditorState?> = combine(editor, reading) { editor, reading -> editor?.copy(prompt =
channelPromptFor(editor, reading)) }` publishes the editor's own reading only when the tag matches, else
`ChannelPromptReading.Reading` — the same tag-and-filter discipline #904's `addWorkspaceRecent` combine
established, applied here so a read landing mid-write can never break that write's own `compareAndSet`
(the write's terminal transitions target `editor`, which the read never touches).

Its constructor takes the owner's `viewModelScope` as `scope` (so clearing the owner cancels the prompt
read and any write chain), `isHostLive: (serverId) -> Boolean` (gates every open and press), `repositoryFor:
(serverId) -> ConversationRepository?` (resolved fresh at each press, since a reconnect replaces it),
`awaitRepository: suspend (serverId) -> ConversationRepository` (what the prompt read waits on, so a live host
whose repository is not resolved yet can fill the prompt when it becomes available) and `onArchived: suspend () -> Unit = {}` (runs after
a confirmed archive, with `leaveForList()` in the thread). `ThreadViewModel` constructs
one bound to its own repository and `hostAvailable`; the list no longer constructs one.
See [the thread instance](thread-overflow-menu-viewmodel-dispatcher.md#editchannel--channeleditorcontroller-1561).

`open(target, name, muted)` cancels any previous read job, resets `reading` to `target to Reading`,
publishes the new `ChannelEditorState` with `savedName` clamped through the same `boundedName` helper
`EditChatModal`'s seed uses (surrogate-safe `take(MAX_WORKSPACE_LABEL_CHARS)`) and `savedMuted` set from
the caller's own read of `channel.muted`, and launches the read job: it waits on `awaitRepository`, calls
`requestSystemPrompt` once, and publishes the tagged result — `Unavailable` for a thrown read or a reply
over `SystemPromptLimit.MAX_BYTES` (never rendered or written back), `Read(prompt, status)` otherwise.
`submit(name, systemPrompt, muted: Boolean? = null)` resolves `repositoryFor(state.serverId)` **at the
press**, the same held-target discipline `submitWorkspaceName` uses, and sends
only what changed, in the order **rename → mute → prompt** (#1021): a rename iff the trimmed name differs
from `savedName`, then a `setMuted` write iff `muted` is non-null and differs from `savedMuted` (`muted ==
null` means the caller reported no value and writes nothing — the default keeps every existing call site
compiling), then the prompt leg, which follows desktop's `promptWriteFor` (#1342): `draft =
systemPrompt?.takeIf { read != null }` — an unread or failed prompt can therefore never be overwritten,
even when the operator typed a name change and pressed OK — and `writesPrompt = draft != null && draft !=
read?.prompt.orEmpty()` gates the write on any difference from the last reading; what gets sent is
`promptToWrite = draft?.takeIf { it.isNotEmpty() }`, so an emptied box over a stored prompt sends `null`
(clearing it) rather than storing `""`, while an unchanged box still sends nothing and any other text still
goes verbatim. Every caller logs `prompt=$writesPrompt` on `channel_edited`, a boolean rather than the
nullable value, since the thing worth recording is "did a prompt write happen", not what it sent. A
confirmed rename updates `savedName`, and a confirmed mute write updates `savedMuted`, before the prompt
leg runs — the prompt is the only write whose confirmation is never recorded, so it stays last and a retry
after any failure sends only the writes the host has not yet confirmed. `archive()` needs no field condition or confirmation: `archive(conversationId)` on the
press-resolved repository — and on success runs `onArchived()` after its terminal `compareAndSet`, which is
how a thread leaves for the list on a confirmed archive from the modal, through the same `leaveForList`
its menu's own Archive uses. `dismiss()` nulls `editor` and cancels the read job unguarded. `closeUnless(keep)` closes a target rejected by the predicate and cancels its prompt read.
Direct controller tests exercise this lifecycle hook; stale press guards separately cover
submit/archive after liveness changes without a preceding close hook. See the thread's
[disconnect policy](thread-overflow-menu-viewmodel-dispatcher.md#editchannel--channeleditorcontroller-1561).
Every line removed from `ChannelListViewModel` reappeared here unchanged but for the renamed
fields and the injected lambdas — the #1561 verifier compared them line by line — so the guarded writes,
the rename → mute → prompt order, cancellation handling and log events are exactly as they were before the
move. See [System prompt editor](system-prompt-editor.md) for why this reads and writes the prompt itself
rather than constructing a `SystemPromptEditor`: that class binds one repository at construction, which a
background/foreground reconnect retires.

**A disconnected host closes its own creation state (#1336 / #1582).** The snapshot
watcher computes the live host set and atomically clears `createChannel` and `createChat`
for hosts outside it. It no longer owns conversation editor cleanup. Creation entry points
and submits re-check liveness at the press; test this with queued Main dispatch so the
watcher cannot hide a missing press guard. A late completion cannot restore a cleared modal.
Host, workspace and Add workspace editing retain their existing policies.

**The five-flow limit.** The outer typed `combine` groups creation state as
`combine(addWorkspace, addWorkspaceRecent, combine(createChannel, createChat, ::Pair), ::Triple)`.
The surviving editor group is simply `combine(hostEditor.state, workspaceEditor, ::Pair)`.
Target-tagged recents still filter out a previous host's emission before publishing it
against a new open target; removing editor flows does not make that guard optional.

**The editor's six methods (#744/#745, delegated to a shared controller since #751).** `openHostEditor`,
`submitHostName`, `requestHostUnpair`, `declineHostUnpair`, `confirmHostUnpair` and `dismissHostEditor`
are each a one-line delegation to this VM's own `HostEditorController` instance (constructed
`HostEditorController(viewModelScope, pairedServers, appPreferences)`) — kept as this class's own
methods, named exactly as before, so `ChannelListScreen`'s event dispatch and
`HostChannelListViewModelTest`'s existing proofs are untouched by the #751 extraction. See
[Host editor](host-editor.md) for what each method does: the `editorOpenJob` cancellation that stops a
slower read from publishing over a newer tap, the `compareAndSet` terminal-transition discipline, the
`saving` guard every transition that can publish a new state carries (including `requestHostUnpair` and
`declineHostUnpair`, whose unguarded first drafts had exactly the stranded-modal defect the #745
security review caught before merge), the removal ordering that clears the host's cached workspace only
after the pairing is actually gone, and the content-free `RelayLog.d` lines
(`host_editor_opened`/`_open_failed`/`_open_rejected`, `host_name_save_started`/`_failed`/`_saved`,
`host_unpair_requested`/`_declined`/`_started`/`_failed`/`_unpaired`, `host_editor_dismissed`) — never
the entered name, the identity or the relay address. The connection close on a removal needs no call
here either: `pairedServers` resolves to
[`ObservablePairedServerStore`](paired-server-store.md#wiring--usage), whose revision bump
`RelayConnectionRegistry` reconciles by closing exactly the removed id's bundle.

**Add workspace (#904), five methods on a private `MutableStateFlow<AddWorkspaceState?>`.**
`openAddWorkspace(serverId)` rejects an id absent from `hostSource.snapshots` (content-free log)
and otherwise publishes `AddWorkspaceState(serverId)` — replacing `openHostWorkspacePicker`, and
the row's **own** host exactly as that method was. `selectAddWorkspaceFolder(path)` and
`createAddWorkspaceFolder(name)` / `submitAddWorkspace()` use a host-resolved write shape: `hostSource.repositoryFor(state.serverId)` is resolved **at the press**, never from
the selected host, and both writes publish a `busy = true` pending state before launching, with
`compareAndSet(pending, …)` terminal transitions so a write finishing after a dismissal or a
retarget cannot touch a newer state. Creating a folder only ever sets `selected`; only
`submitAddWorkspace` starts the chat, via `createDiscussion(selected)`, and only records
`lastOpenedTarget` / sends on `hostNavigationChannel` if `compareAndSet(pending, null)` succeeds, or
if its newly published row was tapped while the model decision was pending. Otherwise a chat started
right before a Cancel lands in the list unopened. `dismissAddWorkspace()` sets the state to `null`
unguarded and sends nothing.
Failures publish `createFailed` / `startFailed` flags, never the exception's message.

**The tagged recents combine.** `addWorkspaceRecent: Flow<Pair<String?, List<String>>>` derives from
`addWorkspace.map { it?.serverId }.distinctUntilChanged().flatMapLatest { … }` — for a non-null id,
`hostSource.snapshots.map { hostSource.repositoryFor(id) }.distinctUntilChanged().filterNotNull()
.flatMapLatest { it.recentWorkspaces() }`, capped at `MAX_ADD_WORKSPACE_RECENTS = 50` since this
shell's content column is not lazy, `catch`-closed to empty and `onStart`-seeded empty. Keying on
the server id alone (not the selection or the flags) means a pick or a failure never re-fetches,
but closing and reopening does — the "recents re-fetch on every open" behaviour the rung-3 scenario
depends on. Each emission is tagged `serverId to list`, and the `hostState` combine publishes
`addWorkspaceRecent` only when that tag equals the open state's own `serverId`, else `emptyList()`
— **this is load-bearing, not decoration.** An untagged pairing can publish one `combine` emission
that still carries the *previous* target's list against the *new* target's state, because the
combine observes the state change before `flatMapLatest` switches collectors; on a host-scoped
folder list that is a cross-host leak the ticket's security review caught as a MUST FIX before
merge (a tap on that stale row would send the wrong host's path to `create_conversation`). Any
future `combine` that pairs a host-scoped open/close flag with a `flatMapLatest`-derived per-host
list needs the same tag-and-filter, not just a zip.

Only `hostNavigationEvents` opens threads, preserving the host through asynchronous creation. See
[flat-list compatibility](navigation.md#temporary-flat-list-compatibility) for the one route
(`DiscussionListScreen`, unreachable) that still goes through the selected-host adapter — this
screen's own last use of it retired with the button.

`koinViewModel<…>()` (from `org.koin.androidx.compose`) routes through `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ auto-wires to the current `NavBackStackEntry` — so the VM is scoped to the back-stack entry, surviving configuration changes and tearing down on pop. `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose` (added to the catalog in #46), distinct from the `-ktx` artifacts already on the classpath.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-viewmodel-ktx` (catalog: `androidx-lifecycle-viewmodel-ktx`, same `lifecycleRuntimeKtx` version-ref as `lifecycle-runtime-ktx`). Required for `viewModelScope` at lifecycle 2.6.1 — `koin-androidx-compose` brings `lifecycle-viewmodel-compose` transitively, but that artifact depends on the non-`-ktx` base which lacks `viewModelScope`.
- **Test dependencies:** `org.jetbrains.kotlinx:kotlinx-coroutines-test` on `testImplementation` (catalog: `kotlinx-coroutines-test`, reuses `kotlinxCoroutines` version-ref). Required for `Dispatchers.setMain(...)` — `stateIn(viewModelScope, ...)` dispatches on `Dispatchers.Main.immediate` which is uninitialised in plain JVM unit tests.

## Testing

Split out to [ChannelListViewModel — testing](channel-list-viewmodel-testing.md) on 2026-09-24 to keep
this document under the docs guard's size cap. That document covers `HostChannelListViewModelTest`'s full
coverage of everything under [Wiring](#wiring) above, host by host and editor by editor.

## Edge cases / limitations

- **Snapshot watcher and explicit reconnect.** The `init` watcher closes creation state on disconnect; `reconnectHost` forwards to the host source. Projection still re-collects on subscription.
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Create chat is single-flight per open dialog.** `saving` prevents a second send. A dialog identity
  survives state copies so a delayed reply from a dismissed dialog cannot close a reopened one for the
  same host; this needs a same-host completion-order test because different-host tests would pass with
  value-equal dialog states.
- **A new row can appear before its model write settles.** A tap on that host-qualified target waits for
  the model decision; ordinary existing rows still open immediately. A create-path-only navigation wait
  would let the first message race the pending write through the row instead.
- **No `flowOn(Dispatchers.IO)`.** Upstream host projection inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **Content-free host logging.** Host projection logs a host count; picker and creation paths log static lifecycle/failure events through `RelayLog`. Preview failures never log exception text or decrypted content.

## Related

Split out to [ChannelListViewModel — related documents and ticket history](channel-list-viewmodel-related.md)
on 2026-09-24 to keep this document under the docs guard's size cap. That document carries the ticket
notes, specs, upstream/downstream consumers and the sibling combine-arm pattern for every editor and
modal under [Wiring](#wiring) above, including [Edit channel (#667)](channel-list-viewmodel-related.md).
