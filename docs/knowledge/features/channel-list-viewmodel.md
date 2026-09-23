# ChannelListViewModel

Exposes ordered host-qualified channel/chat state and explicit host actions through
`HostConversationSource`, plus the fold and selection state
[ChannelListScreen](channel-list-screen.md)'s assembled conversation tree needs (#731).
Every action the screen can trigger — row taps, folds, (since #738) both add
controls, (since #744) the host row's edit control and (since #827) a Chats
row's own edit control — is host-qualified and resolved from this view model's
own methods; `ChannelListScreen` carries no other state model. The flat compatibility
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
channels/chats grouped by workspace, and exposes the pending workspace picker's exact
`serverId`. Since #731 it also carries the assembled tree's **collapsed** fold keys and
the **last-opened** selection target — both mutated only by the screen's fold and row
taps, never reconciled against an incoming snapshot (see
[state projection](channel-list-viewmodel-projection.md)). Host row activation and
successful creation emit `HostConversationTarget(serverId, conversationId)` on
`hostNavigationEvents` and record that target as `selected`. Creation retains its
explicit host across preference reads and picker interaction. Since #738, creation and
the picker are reached only through this host-qualified path: `createHostDiscussion(serverId)`
and `openHostWorkspacePicker(serverId)` are called directly from the host row's own add
control, never via a captured "selected" host.

## Shape

```kotlin
data class HostChannelListEntry(
    val host: HostConversationSnapshot,
    val recentChats: List<Conversation>,
    val chatCount: Int,
    val recentChatLastMessages: Map<String, Message> = emptyMap(),
    val channelGroups: List<HostWorkspaceGroup> = emptyList(),  // #729
    val chatGroups: List<HostWorkspaceGroup> = emptyList(),     // #729
)

data class HostChannelListState(
    val hosts: List<HostChannelListEntry> = emptyList(),
    val workspacePickerServerId: String? = null,
    val collapsed: Set<TreeFoldKey> = emptySet(),                    // #731 — collapsed, not expanded
    val selected: HostConversationTarget? = null,                    // #731 — last opened from this list
    val hostEditor: HostEditorState? = null,                         // #744 — open Edit host modal's target
    val chatEditor: ChatEditorState? = null,                          // #827 — open Edit chat modal's target
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

enum class ConversationTreeSection { Channels, Chats }               // #731

/** A foldable node: a host row when [cwd] is null, that host's workspace row otherwise. */
data class TreeFoldKey(                                              // #731
    val section: ConversationTreeSection,
    val serverId: String,
    val cwd: String? = null,
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
rule — `ChannelListEvent.TreeHostAddTapped(serverId)` / `TreeHostAddLongPressed(serverId)` map
to `vm.createHostDiscussion(serverId)` / `vm.openHostWorkspacePicker(serverId)`, the control's
own row, never `destinations.selectedServerId()`. Since #744 the edit control follows the same
rule a third time: `TreeHostEditTapped(serverId) -> vm.openHostEditor(serverId)`,
`HostEditNameSubmitted(name) -> vm.submitHostName(name)` and `HostEditDismissed -> vm.dismissHostEditor()`.
\#745 adds three more events off the open modal's `Unpair host` action, none carrying a `serverId` — the
target is already the open editor's: `HostUnpairRequested -> vm.requestHostUnpair()`,
`HostUnpairConfirmed -> vm.confirmHostUnpair()` and `HostUnpairDeclined -> vm.declineHostUnpair()`.
\#840 adds a fourth control on the same rule: `TreeHostReconnectTapped(serverId) -> vm.reconnectHost(serverId)`.
`reconnectHost` is a one-line forward to the shared `HostConversationSource.retryHost(serverId)` — see
[Dependency injection § Exact-host Retry and lifecycle](dependency-injection.md#exact-host-retry-and-lifecycle)
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

**The five-flow limit.** `hostState`'s `combine` was already at `combine`'s five-argument typed overload
before this ticket. `chatEditor` makes six, so `hostEditor.state` and `chatEditor` are paired first through
an inner `combine(hostEditor.state, chatEditor, ::Pair)`, and the outer `combine`'s lambda destructures the
pair back into `editor` and `chat`. Reach for this shape again if a seventh arm is ever needed, rather than
the vararg `combine` overload, which loses per-argument typing.

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

Picker visibility comes straight from
`hostState.workspacePickerServerId`; pick/dismiss call the host methods, and the route provides
that captured owner's reconnecting repository to the nested `WorkspacePicker`. Only
`hostNavigationEvents` opens threads, preserving the host through asynchronous creation. See
[flat-list compatibility](navigation.md#temporary-flat-list-compatibility) for the one route
(`DiscussionListScreen`, unreachable) that still goes through the selected-host adapter — this
screen's own last use of it retired with the button.

`koinViewModel<…>()` (from `org.koin.androidx.compose`) routes through `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ auto-wires to the current `NavBackStackEntry` — so the VM is scoped to the back-stack entry, surviving configuration changes and tearing down on pop. `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose` (added to the catalog in #46), distinct from the `-ktx` artifacts already on the classpath.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-viewmodel-ktx` (catalog: `androidx-lifecycle-viewmodel-ktx`, same `lifecycleRuntimeKtx` version-ref as `lifecycle-runtime-ktx`). Required for `viewModelScope` at lifecycle 2.6.1 — `koin-androidx-compose` brings `lifecycle-viewmodel-compose` transitively, but that artifact depends on the non-`-ktx` base which lacks `viewModelScope`.
- **Test dependencies:** `org.jetbrains.kotlinx:kotlinx-coroutines-test` on `testImplementation` (catalog: `kotlinx-coroutines-test`, reuses `kotlinxCoroutines` version-ref). Required for `Dispatchers.setMain(...)` — `stateIn(viewModelScope, ...)` dispatches on `Dispatchers.Main.immediate` which is uninitialised in plain JVM unit tests.

## Testing

`HostChannelListViewModelTest` resolves the production `appModule` ViewModel binding
with controlled source and repository fixtures. Use colliding ids and unchanged
paths on case-distinct hosts, and assert each host's own preview: globally unique
fixture ids can conceal a cross-host merge. A silent host and silent preview must
coexist with visible rows from another host; disconnect must preserve cached rows
while removing previews.

For creation, suspend the preference flow, change compatibility selection and
replace the original host's repository before releasing the preference value.
Give case-distinct hosts different defaults and seed a conflicting global value;
assert both hosts' workspace arguments and qualified navigation, then scratch for
an unset default. A shared default or settled happy path can conceal use of the
legacy property, an early repository capture or a send redirected to selection.
Picker coverage uses a preference flow that throws if read, changes selection
while open, and holds creation suspended while checking immediate target clearing
and duplicate completion suppression.

Proving a projection subscribes to nothing (#729's workspace groups) needs the
fixture's `Host.available` flag, not `Host.live.value = null`: `available` gates
only the lookup lambda, so `repositoryFor` returns null and `observeHostEntry`
short-circuits before any preview subscription while `HostConversationSource`
keeps collecting rows — nulling `live` instead stops that row collector too, so
the test would pass against a projection producing nothing.

Fold and selection coverage (#731, same `fixture()`): every host and workspace key
starts expanded (empty `collapsed` set); toggling a host key collapses only that
host's key, leaving the same host's key in the *other* section expanded — proving
`TreeFoldKey.section` is load-bearing, not decorative. A relabel (`displayName` /
workspace label change only) and an incoming list update both leave the collapsed set
and the rendered groups untouched — the key is `(section, serverId, cwd)`, never a
display name. `onHostRowTapped` records the target as `selected`; a later snapshot
emission does not clear it, and a second tap replaces it — pinning "last opened from
this list", not "currently open."

**Editor coverage (#744, same `fixture()`).** `Fixture` gains an in-memory `PairedServerCollectionStore`
fake bound in the override module — the Koin-built view model would otherwise resolve the Keystore-backed
store on the JVM. `editorOpensOnTheRowsOwnStoredRecordAndSurvivesAnIncomingSnapshot` covers a named, an
unnamed and a blank-named host each opening with the right identity, relay address and name (blank for the
latter two), and a later snapshot leaving the published editor untouched.
`editorOpenIgnoresAnUnknownIdAndAnOpenSupersededByALaterTap` covers an unknown id publishing no editor and
proves the `editorOpenJob` cancellation: a second open while the first host's read is still in flight
publishes the **second** host's editor and never the first's, gated by holding the fake store's read open.
`submitSavesTheTrimmedClampedNameOrClearsItAndClosesTheEditor` covers OK writing the trimmed name and
closing, a blank or whitespace-only name writing `null`, and a name past `MAX_WORKSPACE_LABEL_CHARS`
written clamped. `failedSaveKeepsTheEditorOpenAndActionableWhileADismissedOneStaysClosed` covers a throwing
`setDisplayName` leaving the editor open with `saving = false, failed = true` and the stored name
unchanged, and a dismissal during an in-flight save not being undone by its later completion.
`dismissClosesTheEditorWithoutWriting` covers the last case.

**Removal coverage (#745, same `fixture()`).** The fake `Store` gains a working `remove` (a `removals`
list, a `failRemove` switch and a `removeGate` for observing mid-write state), and the stub `DataStore`
gains a real `updateData` so `AppPreferences.removeDefaultWorkspace` can be asserted rather than stubbed
out. `unpairIsGatedOnAConfirmationAndDecliningRemovesNothing` covers request setting `confirmingUnpair`
without writing anything and decline clearing it while leaving the store, the other host and both
workspace preferences untouched — plus a stray request with no open editor arming nothing.
`confirmingRemovesThePairingThenItsWorkspaceAndClosesTheEditor` proves the id-exact removal, the exact
workspace key cleared, the other host's entry and workspace left intact, and the editor closed — and, with
`removeGate` held, that the workspace key is still present until the pairing removal completes, which is
the assertion that actually proves the ordering rather than trusting it. `aFailedUnpairStaysOnTheConfirmationAndChangesNothing`
covers a throwing `remove` leaving `unpairFailed` set, `saving` cleared, the confirmation still up, the
pairing present and the workspace uncleared, the captured log lines carrying neither the id nor the name,
and a retry succeeding. The same test then proves the `saving` guard itself against a second, gated
removal: a decline and a second unpair request arriving mid-write are both ignored, so the write's own
`compareAndSet` still closes the modal rather than stranding it on a step the store never took — and,
separately, that a failure landing after a `dismissHostEditor()` call does not resurrect the modal either.

**Chat editor coverage (#827, same `fixture()`).** Both fixture hosts, `"Host"` and `"host"`, get a chat
sharing the same id, `"same"`, under different names — deliberately colliding rather than globally unique,
because a conversation id is host-local and a rename sent to the wrong host would still pass a suite that
gave every fixture chat its own id. Opening `("Host","same")` pre-fills Host's own name, opening
`("host","same")` pre-fills host's, a nameless chat pre-fills `""`, an unknown id opens nothing, and
opening changes neither `selected` nor the navigation channel. Submitting `"  New  "` renames only on
`"Host"`'s repo with `"New"` and closes the editor; `"host"`'s same-id chat is asserted unrenamed, and the
projected row picks up `"New"` once the repo's own stream re-emits. `isHostConnected` flips false when the
target host's status goes disconnected — with the editor left open and the typed text intact — and true
again on reconnect; submitting while `repositoryFor` returns null sets `failed` and sends nothing. A
throwing `rename` leaves the editor open with `failed = true, saving = false`, the stored name unchanged,
and asserts no captured log line carries the name, either id or the exception's message; a retry succeeds
and closes. A gated write completing after `dismissChatEditor()` does not reopen the editor — the same
`compareAndSet`-survives-a-dismissal proof the host editor's suite already established. Dismiss sends
nothing.

Unavailable-target coverage denies lookup even with cached rows and connected
indicators. Failure tests inspect the action job's cancellation state as well as
missing navigation: absence of navigation alone cannot prove cancellation was
re-thrown. Demo coverage uses the production repository selector and a paired host
owning a migrated legacy default, then checks scratch and an explicit `demo`
default on the existing fake singleton. These are deterministic contract tests.
The [live regression gate](../../e2e-interactive-stream.md#pre-ship-gate) does not
prove different defaults on two live hosts; that daemon-confirmed workspace
scenario remains [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676).

`rowTapsAndCreationTargetTheirNamedHostRegardlessOfTheSelectedAdapter` (#738,
reshaped from the pre-existing `rowTargetsAndLegacySelectedProjectionAndActionsUseSeparateNavigationStreams`)
is the test that proves the retirement rather than merely asserting it: it moves
the sibling [selected-host compatibility adapter](navigation.md#temporary-flat-list-compatibility)
to a second host (`f.selected.value = f.b.repo`) and asserts a row tap and a
`createHostDiscussion` call still land on their own named hosts, never following
the adapter. Reshaping the existing fixture this way — rather than deleting the
test — is positive proof the dependency was actually cut, where a deletion would
have proven nothing.

`ChannelListViewModelTest` — the flat-screen compatibility test class this
document once described here (20 cases: `initialState_isLoading` through
`longPressPicker_overridesDefaultWorkspace`) — retired whole in #738 (683 lines)
along with the `state` / `onEvent` / `navigationEvents` contract it existed to
prove. `HostChannelListViewModelTest` is now the only test class for this view
model, living at `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt`
and using JUnit 4. Its class-level `private val dispatcher = UnconfinedTestDispatcher()`
field (bound to `Dispatchers.setMain(dispatcher)` in `@Before`, passed to
`runTest(dispatcher) { … }` in every test body, `resetMain()` in `@After`) is this
class's own convention, not inherited from the retired sibling.

## Edge cases / limitations

- **No `init { }` block, no `refresh()`, no `retry()` method.** Cold-flow re-collection on resubscription is the existing retry surface. Explicit retry lands with the UI control that needs it.
- **`pickHostWorkspace` reads-then-clears `pendingHostWorkspacePicker.value` non-atomically** — see
  [state projection](channel-list-viewmodel-projection.md#one-shot-navigation-via-channelbuffered-22)
  for the concurrency note; pre-existing, out of #738's scope, no observed failure.
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Two rapid taps on the same host row's add control create two discussions.** No debounce / single-flight on `createHostDiscussion`. AC reads "single tap creates exactly one new discussion" — per-tap, not "duplicate-prevent". The fake's `createDiscussion` is fast; if real-world races appear they get their own ticket. Same shape the retired button's tap path had.
- **No `flowOn(Dispatchers.IO)`.** Upstream host projection inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **Content-free host logging.** Host projection logs a host count; picker and creation paths log static lifecycle/failure events through `RelayLog`. Preview failures never log exception text or decrypted content.

## Related

- Host contract: [#705 design](../../specs/architecture/705-host-channel-list.md), [host source identity](dependency-injection.md#host-identity-and-snapshots) and [exact-host repository access](dependency-injection.md#exact-host-repository-access).
- Ticket notes: [`../codebase/45.md`](../codebase/45.md), [`../codebase/22.md`](../codebase/22.md) (FAB → `onEvent` reducer + one-shot nav channel), [`../codebase/26.md`](../codebase/26.md) (`combine` of Channels + Discussions flows, widened `Loaded` / `Empty` to carry `recentDiscussionsCount`, `RecentDiscussionsTapped` event, `stubRepo` helper reshape), [`../codebase/69.md`](../codebase/69.md) (widened `Loaded` / `Empty` with `recentDiscussions: List<Conversation>`; collapsed the `.map { it.size }` projection into a single `combine` emission; two new tests pin `.take(3)` slicing and upstream-ordering contract), [`../codebase/161.md`](../codebase/161.md) (third combined input `lastMessagesFlow` derived via `flatMapLatest(distinctUntilChanged(recentIdsFlow))` + per-row `observeLastMessage` `combine`; `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default-arg affordance lets every existing construction site stay untouched), [`../codebase/221.md`](../codebase/221.md) (fourth combined input `pendingWorkspacePicker: MutableStateFlow<Boolean>` projects onto `workspacePickerVisible: Boolean = false` on `Loaded`/`Empty`; three new `onEvent` arms for `LongPressFab` / `WorkspacePicked(workspace)` / `WorkspacePickerDismissed`; `WorkspacePicked` clears the flag *synchronously before* the suspend launches — same shape as #78's `confirmPromotion`), [`../codebase/239.md`](../codebase/239.md) (pure test-infra refactor: lifts the `SettingsViewModelTest` `TemporaryFolder` + class-level `dispatcher` + `TestScope.newDataStore()` rig into `ChannelListViewModelTest` and introduces a `TestScope.makeVm(repository, prefs = AppPreferences(newDataStore()))` helper that routes all 18 VM construction sites; production code unchanged — the `prefs` default is the seam the next ticket changes one line of when it wires `AppPreferences.defaultWorkspace` into the FAB short-press), [`../codebase/240.md`](../codebase/240.md) (spends the #239 seam: VM gains `AppPreferences` as a second constructor parameter, `CreateDiscussionTapped` reads `appPreferences.defaultWorkspace.first()` inside the existing `viewModelScope.launch { … }` and passes it to `repository.createDiscussion(workspace = …)`; `WorkspacePicked` long-press path unchanged — explicit user pick still overrides the default; two new tests pin both behaviours; first consumer of [`AppPreferences.defaultWorkspace`](./app-preferences.md) since the #231 schema landed)
- Specs: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/26-recent-discussions-pill.md`, `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/161-recent-discussion-last-message-uistate.md`, `docs/specs/architecture/221-channel-list-fab-long-press-workspace-picker.md`, `docs/specs/architecture/729-group-conversations-by-host-and-workspace.md` (`HostWorkspaceGroup.kt`'s `HostConversationRow` / `HostWorkspaceGroup` / `groupConversationsByWorkspace`, consumed by [ChannelListScreen](channel-list-screen.md)'s tree since #731), `docs/specs/architecture/731-assemble-conversation-tree.md`, `docs/specs/architecture/738-list-add-controls-retire-fab.md` (retires the flat compatibility contract this document describes above), `docs/specs/architecture/744-host-row-edit-and-rename.md` (the editor's target, its three methods and the `editorOpenJob` concurrency guard), `docs/specs/architecture/745-unpair-host-from-edit-modal.md` (the removal's three methods, its ordering and the `saving` guard against sibling transitions), `docs/specs/architecture/751-settings-host-edit-and-unpair.md` (extracts both into [`HostEditorController`](host-editor.md), Settings' second caller), `docs/specs/architecture/827-rename-chat-from-tree-row.md` (`openChatEditor`/`submitChatName`/`dismissChatEditor`, resolved from the target's own `serverId` at the press rather than the selected host, and the colliding-id test fixture that proves it)
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam — since #240 `createDiscussion(workspace = <appPreferences.defaultWorkspace.first()>)` is the call the `CreateDiscussionTapped` arm makes — never the no-arg form anymore; `createDiscussion(workspace = event.workspace)` is the #221 call from the `WorkspacePicked` arm; `observeConversations(Discussions)` is the second subscription added in #26 and the same emission #69 re-uses for both `recent` and `count`; `observeLastMessage(id)` from #161 is the per-row subscription the `flatMapLatest` derivation rides), [`AppPreferences`](./app-preferences.md) (since #240; the `defaultWorkspace: Flow<String>` schema landed in #231 and the FAB short-press is its first consumer — the read is `.first()`-shaped, one-shot per event), [Paired server store](./paired-server-store.md) (since #744 — `PairedServerCollectionStore.loadById` / `setDisplayName`, read and written only by the editor's three methods; since #745 — `remove`, called only by `confirmHostUnpair`), [data model](./data-model.md) (`Conversation` payload, `Message` payload for `recentDiscussionLastMessages`), [dependency injection](./dependency-injection.md) (Koin wiring)
- Sibling combine-arm pattern: [DiscussionListViewModel](./discussion-list-viewmodel.md) `pendingPromotion` (#78) — the first instance of `combine(upstream, MutableStateFlow<…>)` visibility arm; the retired flat `pendingWorkspacePicker` (#221) was the second, retired with it in #738; `hostState`'s own combine over `pendingHostWorkspacePicker: MutableStateFlow<String?>` (#221, unaffected by #738) is the arm that survives, joined by `hostEditor.state` — a [`HostEditorController`](host-editor.md)'s own `StateFlow<HostEditorState?>` (#744; the `MutableStateFlow` itself moved inside that controller in #751, so this VM's combine input is now a read-only `StateFlow`, not a field it owns). The `SaveAsChannelDialog` visibility arm (#142) is a sibling instance in the codebase.
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent` and `collectAsStateWithLifecycle()`; #22 added the FAB; #26 added the pill; #69 replaced the pill with the inline section — itself replaced by #731's assembled tree, which consumes `hostState.collapsed` / `hostState.selected` and dispatches `onHostRowTapped` / `onFoldToggled` directly, dropping the `selectedServerId()` adapter for row taps; #161 added the compatibility `recentDiscussionLastMessages` field, consumed by sibling #162; #221 added the FAB long-press → picker path; #738 retired the FAB, the flat `ChannelListUiState`/`ChannelListNavigation`/`onEvent`/`navigationEvents` contract #46/#22/#26/#69/#161/#221 built, and the `repository` constructor parameter that fed it, replacing the FAB's two paths with the section-header and host-row add controls both routed through this VM's existing host-qualified methods; #744 drives [`HostEditorModal`](host-editor.md) off `hostState.hostEditor` — an inline `EditHostModal` call until #751 replaced it with the shared binding, so Settings' second caller resolves the same presence rule and failure strings), [WorkspacePicker](./workspace-picker.md) (the host `hostState.workspacePickerServerId` drives, unaffected by #738), follow-up Retry ticket (an explicit retry affordance — no longer has an `Error` state to retry from since #738; would need its own design), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class`; #490 already added the **crash-guard** around the host-qualified `createDiscussion` launches via [`launchGuardedRepoCall`](guarded-repo-launch.md), so what Phase 4 still owes is the **user-facing error surface**, not the try/catch), [Guarded repo launch](guarded-repo-launch.md) (the #490 one-shot-call guard the two create-discussion launches route through), #745 (done — wires `EditHostModal`'s `Unpair host` action behind a confirmation, described above under [Wiring](#wiring)), #827 (done — drives [`EditChatModal`](mobile-modal.md#callers) off `hostState.chatEditor`, a Chats row's own pencil, described above under [Wiring](#wiring); #828 wires that same modal's Archive action), #676 (the live emulator scenario for #744's open/save/failure flow and #745's removal, blocked by both).
