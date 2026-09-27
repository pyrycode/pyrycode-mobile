# ChannelListViewModel

Exposes ordered host-qualified channel/chat state and explicit host actions through
`HostConversationSource`, plus the fold and selection state
[ChannelListScreen](channel-list-screen.md)'s assembled conversation tree needs (#731).
Every action the screen can trigger — row taps, folds, (since #738) both add
controls, (since #744) the host row's edit control, (since #827) a Chats
row's own edit control and (since #667) a Channels row's own edit control — is
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
explicit host across preference reads and Add workspace interaction. Since #738, creation is reached only through this host-qualified path: `createHostDiscussion(serverId)`
is called directly from the host row's own add control, never via a captured "selected" host;
since #904 the same control's long-press calls `openAddWorkspace(serverId)` — the shared
[`MobileModal`](mobile-modal.md) shell in place of the retired bottom-sheet picker.

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
    val chatEditor: ChatEditorState? = null,                          // #827 — open Edit chat modal's target
    val workspaceEditor: WorkspaceEditorState? = null,                // #905 — open Edit workspace modal's target
    val createChannel: CreateChannelState? = null,                    // #958 — open Create channel modal's target
    val channelEditor: ChannelEditorState? = null,                    // #667 — open Edit channel modal's target
) {
    /** True exactly when [serverId] has a snapshot and both connection legs are `Connected` (#827).
     *  Derived from the same snapshot flow the rows draw from, so a disconnect or reconnect flips this
     *  without publishing a new [chatEditor] — the modal and its typed name stay put. Both legs are
     *  compared with `==`, not an exhaustive `when`, so a relay state added later reads as not
     *  connected rather than needing a classification here. */
    fun isHostConnected(serverId: String): Boolean =
        hosts.any { it.host.serverId == serverId && it.host.connectionStatus.isLive() }
}

/** The Edit chat modal's target and flags (#827), shaped like [HostEditorState]: ids and display text
 *  only. [initialName] is unclamped — `EditChatModal` clamps it at its own boundary, surrogate-safe. */
data class ChatEditorState(
    val serverId: String,
    val conversationId: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

/** The Add workspace modal's target and flags (#904), shaped like [ChatEditorState]. [selected] is the
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
since the row already carries its own host; since #738 the two add controls follow the same
rule — `ChannelListEvent.TreeHostAddTapped(serverId)` maps to `vm.createHostDiscussion(serverId)`,
the control's own row, never `destinations.selectedServerId()`; since #904
`TreeHostAddLongPressed(serverId)` maps to `vm.openAddWorkspace(serverId)`, opening the shared
`MobileModal` shell in place of the retired bottom-sheet picker (see below). Since #744 the edit control follows the same
rule a third time: `TreeHostEditTapped(serverId) -> vm.openHostEditor(serverId)`,
`HostEditNameSubmitted(name) -> vm.submitHostName(name)` and `HostEditDismissed -> vm.dismissHostEditor()`.
\#745 adds three more events off the open modal's `Unpair host` action, none carrying a `serverId` — the
target is already the open editor's: `HostUnpairRequested -> vm.requestHostUnpair()`,
`HostUnpairConfirmed -> vm.confirmHostUnpair()` and `HostUnpairDeclined -> vm.declineHostUnpair()`.
\#840 adds a fourth control on the same rule: `TreeHostReconnectTapped(serverId) -> vm.reconnectHost(serverId)`.
`reconnectHost` is a one-line forward to the shared `HostConversationSource.retryHost(serverId)` — see
[Dependency injection § Exact-host Retry and lifecycle](dependency-injection-host-conversation-source.md#exact-host-retry-and-lifecycle)
— and touches no VM state: not `collapsedKeys`, not the snapshot, not the editor.

**A Chats row's own edit control, host-resolved a fifth time (#827).** `TreeChatEditTapped(target) ->
vm.openChatEditor(target)`, `ChatEditNameSubmitted(name) -> vm.submitChatName(name)` and
`ChatEditDismissed -> vm.dismissChatEditor()`. `openChatEditor` looks the conversation up synchronously
in `hostSource.snapshots.value`, matched by the target's own `serverId` then its own conversation id among
that host's `chats`; an unknown id logs a content-free reject and publishes nothing, and the lookup never
touches `lastOpenedTarget` or the navigation channel — the pencil edits the row, it does not open it.
`submitChatName` resolves `hostSource.repositoryFor(target.serverId)` **at the press**, never from the
selected host or `ThreadDestinationFactory`; with no live repository it publishes `failed = true` and
sends nothing. Otherwise it publishes a `saving = true` state and launches one `viewModelScope.launch`
that calls `rename(conversationId, trimmed)`; success does `chatEditor.compareAndSet(pending, null)`, and
an `Exception` other than `CancellationException` does
`chatEditor.compareAndSet(pending, pending.copy(saving = false, failed = true))` — the same
`compareAndSet`-against-the-state-published-before-the-write discipline
[`HostEditorController`](host-editor.md) established, so a write finishing after a dismissal can neither
resurrect the modal nor overwrite a newer one. The renamed row's new name reaches the list through the
repository's own conversation stream re-emitting; this method writes no local copy of it. `dismissChatEditor`
publishes `null` unguarded, as `HostEditorController.dismiss` does. Unlike the host editor, this state is a
private `MutableStateFlow<ChatEditorState?>` on the view model itself, not a shared controller —
`ChannelListViewModel` is `chatEditor`'s only owner, so #744/#751's extraction has no counterpart here.

**The editor's Archive action (#828).** `ChatArchiveRequested -> vm.archiveChat()`, no target id: the
target is whichever chat the editor already has open. `archiveChat` needs an open, non-`saving` editor,
then resolves `hostSource.repositoryFor(target.serverId)` **at the press**, exactly as `submitChatName`
does — the same host, never the selected one, which matters because conversation ids are host-local and
two hosts can hold a chat with the same id. With no live repository it publishes `archiveFailed = true`
and sends nothing; otherwise it publishes `saving = true` (clearing both `failed` and `archiveFailed`,
since one in-flight flag now gates both the rename and the archive actions the modal draws) and launches
`live.archive(conversationId)`, `compareAndSet`-terminal the same way: success clears the editor, a
non-cancellation `Exception` restores `saving = false` with `archiveFailed = true`. It never calls
`rename` or `delete`. It reads no field state — the name typed into the buffer is irrelevant to
archiving and survives a failed archive untouched, since a failure keeps the same editor instance.
`submitChatName` clears `archiveFailed` too, in both its pending and its unavailable-host branches, so a
failed archive followed by a successful or failed rename never shows a stale archive error. The archived
row leaves the Chats section the same way a rename's new name arrives — the host's own conversation
stream re-emits it with `archived = true`; nothing here patches the snapshot.

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
`hostSource.repositoryFor(target.serverId)` **at the press**, exactly as `submitChatName` does, and the
same `compareAndSet`-against-the-published-state discipline closes it. `requestWorkspaceArchive` /
`declineWorkspaceArchive` / `confirmWorkspaceArchive` mirror `HostEditorController`'s unpair three: request
and decline both refuse while `saving`, so a decline cannot race a confirm's own close, and a failed
confirm leaves `confirmingArchive` set so OK retries — `archiveWorkspace` leaves already-archived rows
archived, so a retry only touches the rows still active. Neither write patches a local copy of the row;
both rely on the host's own conversation stream re-emitting, the same discipline every other editor here
uses.

**A Channels row's own pencil, host-resolved an eighth time (#667).** `TreeChannelEditTapped(target) ->
vm.openChannelEditor(target)`, `ChannelEditSubmitted(name, systemPrompt, muted) -> vm.submitChannelEdit(name,
systemPrompt, muted)`, `ChannelArchiveRequested -> vm.archiveChannel()` and `ChannelEditDismissed ->
vm.dismissChannelEditor()`. Unlike every editor above, this one's published state is not one
`MutableStateFlow` but two, combined internally before either reaches `hostState`: a private
`channelEditor: MutableStateFlow<ChannelEditorState?>` holding the target, the saved name, the saved mute
flag (`savedMuted`, #1021 — the host's stored `Conversation.muted` at open time, then the value the
daemon confirmed, the same pattern as `savedName`) and the
`saving`/`failed`/`archiveFailed` flags (its own `prompt` field stays at its `Reading` default and is
never read), and a private `channelPrompt: MutableStateFlow<Pair<HostConversationTarget,
ChannelPromptReading>?>` holding the latest stored-prompt reading tagged with the channel it belongs to.
`publishedChannelEditor = combine(channelEditor, channelPrompt) { editor, reading -> editor?.copy(prompt =
channelPromptFor(editor, reading)) }` publishes the editor's own reading only when the tag matches, else
`ChannelPromptReading.Reading` — the same tag-and-filter discipline #904's `addWorkspaceRecent` combine
established, applied here so a read landing mid-write can never break that write's own `compareAndSet`
(the write's terminal transitions target `channelEditor`, which the read never touches).

`openChannelEditor` looks the channel up synchronously in `hostSource.snapshots.value`, matched by the
target's own `serverId` then its own conversation id among that host's **`channels`** — the sibling
lookup to `openChatEditor`'s `chats` walk above, now that `openChatEditor`'s own KDoc names this method
rather than pointing at this ticket as future work. An unknown id logs a content-free reject and touches
neither `selected` nor the navigation channel, exactly as the chat and workspace editors' opens do; only
a chat qualifies for `openChatEditor`'s own lookup, a channel only for this one's. It then cancels any
previous read job (a `Job?` field,
`channelPromptRead`), resets `channelPrompt` to `target to Reading`, publishes the new `ChannelEditorState`
with `savedName` clamped through the same `boundedName` helper `EditChatModal`'s seed uses (surrogate-safe
`take(MAX_WORKSPACE_LABEL_CHARS)`) and `savedMuted` read straight from that same host-snapshot lookup's
`channel.muted` (#1021), and launches the read job: it waits for `hostSource.repositoryFor
(target.serverId)` to become non-null on the snapshots flow, calls `requestSystemPrompt` once, and
publishes the tagged result — `Unavailable` for a thrown read or a reply over `SystemPromptLimit.MAX_BYTES`
(never rendered or written back), `Read(prompt, status)` otherwise. `submitChannelEdit(name, systemPrompt,
muted: Boolean? = null)` resolves
`hostSource.repositoryFor(state.serverId)` **at the press**, the same discipline `submitChatName` and
`submitWorkspaceName` use, and sends only what changed, in the order **rename → mute → prompt** (#1021): a
rename iff the trimmed name differs from `savedName`, then a `setMuted` write iff `muted` is non-null and
differs from `savedMuted` (`muted == null` means the caller reported no value and writes nothing — the
default keeps every existing call site compiling), then the prompt verbatim iff it differs from the
reading's own `prompt.orEmpty()` **and** the
caller ever showed the field (`systemPrompt != null`) — an unread or failed prompt can therefore never be
overwritten, even when the operator typed a name change and pressed OK. A confirmed rename updates
`savedName`, and a confirmed mute write updates `savedMuted`, before the prompt leg runs — the prompt is
the only write whose confirmation is never recorded, so it stays last and a retry after any failure sends
only the writes the host has not yet confirmed. `archiveChannel` mirrors `archiveChat`'s shape exactly — no field condition, no
confirmation, `archive(conversationId)` on the press-resolved repository — and `dismissChannelEditor`
nulls `channelEditor` and cancels `channelPromptRead` unguarded. See
[System prompt editor](system-prompt-editor.md) for why this reads and writes the prompt itself rather
than constructing a `SystemPromptEditor`: that class binds one repository at construction, which a
background/foreground reconnect retires.

**The five-flow limit.** `hostState`'s outer `combine` was already at `combine`'s five-argument typed
overload before #744, so every editor and modal added since has had to arrive as one of those five
arguments rather than a sixth. Two of the five are themselves a nested `combine` that packs several
targets into one value:

- The Add-workspace group (`combine(addWorkspace, addWorkspaceRecent, createChannel, ::Triple)`, #958)
  packs the open Add workspace target, its own tagged recents list (#904) and the open Create channel
  target into one three-argument typed `combine`, destructured back into `adding`, `recent` and `creating`
  in the outer lambda.
- The editor group started as `combine(hostEditor.state, chatEditor, workspaceEditor, ::Triple)` (#905:
  `chatEditor` made it six arms, `workspaceEditor` a seventh, so this three-argument `combine` absorbed
  both). #667's `channelEditor` made it an eighth arm, one past what a single `combine` call can hold at
  five outer plus three inner, so the editor group is now nested two deep:
  `combine(combine(hostEditor.state, chatEditor, ::Pair), workspaceEditor, publishedChannelEditor, ::Triple)`.
  The outer lambda destructures the `Triple` into `editorAndChat`, `workspace` and `channel`, then the
  `Pair` inside `editorAndChat` into `editor` and `chat` — two destructuring steps for what reads as three
  named editors. `publishedChannelEditor` is itself `channelEditor` and `channelPrompt`'s own inner
  `combine` (described above), not the raw `MutableStateFlow` the other four editors publish directly —
  the state that lands in this outer combine is already the fully-projected `ChannelEditorState?`.

Reach for one more nesting level, the same way, if a ninth arm is ever needed; the vararg `combine`
overload is available but loses per-argument typing, so prefer nesting until that cost is worth paying.
\#904 established the pairing trick both this group's `channelPrompt` (above) and the Add-workspace group's
`addWorkspaceRecent` (below) reuse: tag every emission with the target it was produced for, and publish it
only when that tag still matches the currently open target — the guard against a stale target's value
landing on a fresher one's state.

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
`createAddWorkspaceFolder(name)` / `submitAddWorkspace()` follow `submitChatName`'s host-resolved-
write shape: `hostSource.repositoryFor(state.serverId)` is resolved **at the press**, never from
the selected host, and both writes publish a `busy = true` pending state before launching, with
`compareAndSet(pending, …)` terminal transitions so a write finishing after a dismissal or a
retarget cannot touch a newer state. Creating a folder only ever sets `selected`; only
`submitAddWorkspace` starts the chat, via `createDiscussion(selected)`, and only records
`lastOpenedTarget` / sends on `hostNavigationChannel` if `compareAndSet(pending, null)` succeeds —
so a chat started right before a Cancel lands in the list unopened rather than reopening the modal
or navigating. `dismissAddWorkspace()` sets the state to `null` unguarded and sends nothing.
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

- **No `init { }` block, no `refresh()`, no `retry()` method.** Cold-flow re-collection on resubscription is the existing retry surface. Explicit retry lands with the UI control that needs it.
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Two rapid taps on the same host row's add control create two discussions.** No debounce / single-flight on `createHostDiscussion`. AC reads "single tap creates exactly one new discussion" — per-tap, not "duplicate-prevent". The fake's `createDiscussion` is fast; if real-world races appear they get their own ticket. Same shape the retired button's tap path had.
- **No `flowOn(Dispatchers.IO)`.** Upstream host projection inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **Content-free host logging.** Host projection logs a host count; picker and creation paths log static lifecycle/failure events through `RelayLog`. Preview failures never log exception text or decrypted content.

## Related

Split out to [ChannelListViewModel — related documents and ticket history](channel-list-viewmodel-related.md)
on 2026-09-24 to keep this document under the docs guard's size cap. That document carries the ticket
notes, specs, upstream/downstream consumers and the sibling combine-arm pattern for every editor and
modal under [Wiring](#wiring) above, including [Edit channel (#667)](channel-list-viewmodel-related.md).
