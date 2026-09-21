# ChannelListViewModel

Exposes ordered host-qualified channel/chat state and explicit host actions through
`HostConversationSource`, plus the fold and selection state
[ChannelListScreen](channel-list-screen.md)'s assembled conversation tree needs (#731).
The compatibility `state` still reads the selected-host repository or demo fake and
drives only the placeholder branch (loading/error/no-hosts-empty) and the FAB paths;
production [navigation](navigation.md#temporary-flat-list-compatibility) captures
host-qualified targets for row taps, folds and actions. Legacy reducer and bare-id
navigation APIs remain available but are not consumed by the production graph.

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
explicit host across preference reads and picker interaction.

The compatibility `state: StateFlow<ChannelListUiState>` still combines the injected
repository's Channels and Discussions flows, recent-message previews and picker
visibility. It starts at `Loading`, then emits `Empty` or `Loaded` with recent-three
discussions and the full count, or `Error` when an upstream fails. `onEvent` handles
creation and picker events; successful legacy creation emits `ToThread(id)` on
`navigationEvents`. These two contracts have separate picker state and navigation
channels; the production destination adapts flat UI events to the host contract.

## Shape

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Empty(
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,           // #221
    ) : ChannelListUiState
    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,           // #221
    ) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

sealed interface ChannelListNavigation {
    data class ToThread(val conversationId: String) : ChannelListNavigation
}

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
    private val repository: ConversationRepository,
    private val appPreferences: AppPreferences,
    private val hostSource: HostConversationSource,
) : ViewModel() {
    // State projection and action behavior are described below.
}
```

`recentDiscussions` is bounded at `RECENT_DISCUSSIONS_LIMIT = 3` (the count of preview rows the section can host). `recentDiscussionsCount` is the full (uncapped) `discussions.size` — what the See-all label renders. Behavioural invariant: `recentDiscussions.size <= 3`, `recentDiscussions.size <= recentDiscussionsCount`, and when `recentDiscussionsCount == 0` both fields are empty/zero (so the section's "render only when non-empty" predicate is `recentDiscussions.isEmpty()`).

`recentDiscussionLastMessages` (#161) is a parallel map keyed by the conversation id of an entry in `recentDiscussions`. **Absent key = no last message for that conversation** — `null` is filtered out via `mapNotNull` in the projection, so call sites that read `state.recentDiscussionLastMessages[conversation.id]` get unambiguous `Message?` semantics by construction (no `null`-vs-missing ambiguity). The default value `= emptyMap()` on both `Empty` and `Loaded` is load-bearing: it lets every existing `Empty(...)` / `Loaded(...)` construction site (previews, screen tests, VM tests, future callers) omit the new argument without a 14-site mechanical cascade. Architect picked the parallel-map shape over a composite `RecentDiscussion(conversation, lastMessage)` because the AC explicitly forbids consumer-signature changes — `RecentDiscussionsSection(discussions: List<Conversation>, …)` stays as-is; sibling #162 reads per-row from the map without touching `recentDiscussions`. Map invariant: `recentDiscussionLastMessages.keys ⊆ recentDiscussions.map { it.id }` (the `flatMapLatest` over `recentIdsFlow` guarantees no stale entries leak through when the recent slice shifts).

The compatibility `workspacePickerVisible: Boolean = false` (#221) is the [`WorkspacePicker`](./workspace-picker.md) host's hoisted `visible` prop, projected from the private `pendingWorkspacePicker: MutableStateFlow<Boolean>`. Bare `Boolean`, not a nullable-payload wrapper like #78's `pendingPromotion: PendingPromotion?` — the picker is a one-off "create a discussion" trigger from the FAB with no per-target payload (no "which discussion are we promoting" equivalent). Default `= false` on both `Empty` and `Loaded` preserves the existing 26 construction sites (previews, screen tests, VM tests, the VM's own combine body); same affordance pattern as #161's `recentDiscussionLastMessages` widening. `Error` and `Loading` do not carry the field — the FAB renders only in `Loaded`/`Empty` (see [`ChannelListScreen.kt:100`](./channel-list-screen.md)), so the picker can only be reached from those states; collapsing to `Error` mid-pick removes the field entirely from the state stream, but the VM's internal `pendingWorkspacePicker.value` retains its prior `true` and the projection re-applies it whenever a legitimate `Loaded`/`Empty` emission lands again.

`ChannelListUiState` and `ChannelListNavigation` are top-level (siblings of the VM), not nested inside the VM — call sites import `ChannelListUiState.Loaded` / `ChannelListNavigation.ToThread` directly rather than `ChannelListViewModel.UiState.Loaded`. The screen-name prefix carries enough disambiguation.

## How it works

See [state projection and event handling](channel-list-viewmodel-projection.md)
for the `stateIn` sharing configuration, the state projection (including #729's
workspace groups), per-row last-message fan-out, the default-value affordance
convention, the ordering contract, error semantics, one-shot navigation and the
`onEvent` reducer.

## Wiring

Koin supplies three required constructor dependencies from
[`appModule`](dependency-injection.md):

```kotlin
viewModel { ChannelListViewModel(get(), get(), get()) }
```

They are `ConversationRepository`, `AppPreferences` and the shared
`HostConversationSource`. The repository selector supplies the selected-host
`StableConversationRepository` for normal builds, or the existing fake singleton
for `useRelay = false`. Its included `hostConversationModule` selects the matching
host source. Demo mode exposes only `HostConversationSource.DEMO_SERVER_ID`
(`demo`, display name `Demo`)
and resolves that same fake singleton for host reads and creation. Saved relay hosts
never enter the demo source. Demo creation reads `defaultWorkspace("demo")`: its
explicit value or scratch, even when a paired host owns the migrated legacy path.
`AppPreferences` is shared with Settings, whose workspace access still uses the
[transitional legacy API](app-preferences.md#what-it-does).

`state` still renders only the compatibility placeholders (loading/error/no-hosts
empty copy) and gates `workspacePickerVisible`; it never flattens multi-host
snapshots — the row content itself has come from `hostState` since #731. The FAB
paths are the only ones still going through the `MainActivity` adapter:
`destinations.selectedServerId()` (or `demo`) is captured at create/long-press/picker
entry and calls `createHostDiscussion` or `openHostWorkspacePicker`. Row taps and fold
toggles no longer touch that adapter — `ChannelListEvent.TreeRowTapped` /
`TreeFoldToggled` map straight to `vm.onHostRowTapped(event.target)` /
`vm.onFoldToggled(event.key)`, since the row already carries its own host. Picker
visibility comes from `hostState.workspacePickerServerId`; pick/dismiss call the host
methods. The route also provides that captured owner's reconnecting repository to the
nested `WorkspacePicker`. Only `hostNavigationEvents` opens threads, preserving the
host through asynchronous creation. See
[flat-list navigation](navigation.md#temporary-flat-list-compatibility).

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

Unavailable-target coverage denies lookup even with cached rows and connected
indicators. Failure tests inspect the action job's cancellation state as well as
missing navigation: absence of navigation alone cannot prove cancellation was
re-thrown. Collect both navigation streams to prove host and legacy actions remain
isolated. Demo coverage uses the production repository selector and a paired host
owning a migrated legacy default, then checks scratch and an explicit `demo`
default on the existing fake singleton. These are deterministic contract tests.
The [live regression gate](../../e2e-interactive-stream.md#pre-ship-gate) does not
prove different defaults on two live hosts; that daemon-confirmed workspace
scenario remains [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676).

`ChannelListViewModelTest` retains the flat-screen compatibility checks; its
legacy reducer uses the unqualified property and cannot prove production host
creation. Both test classes live under
`app/src/test/java/de/pyryco/mobile/ui/conversations/list/` and use JUnit 4.
Compatibility coverage includes:

1. `initialState_isLoading` — reads `vm.state.value` before any subscriber attaches; relies on `stateIn`'s `initialValue` being immediately visible without a hot collector.
2. `loaded_whenSourceEmitsNonEmpty` — launched collector, channels source emits `listOf(sampleChannel)`, asserts `Loaded(listOf(sampleChannel), recentDiscussions = emptyList(), recentDiscussionsCount = 0)` (#69 widened the assertion).
3. `empty_whenSourceEmitsEmptyList` — launched collector, channels emits `emptyList()`, asserts `Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0)` (#69 widened).
4. `loaded_carriesDiscussionsCount` (#26; updated #69) — channels emits one channel; discussions emits 3 items. Asserts `Loaded(channels = [one], recentDiscussions = listOf(d1, d2, d3), recentDiscussionsCount = 3)`. The `d1`/`d2`/`d3` instances have distinct `lastUsedAt` values to keep the assertion stable.
5. `empty_carriesDiscussionsCount` (#26; updated #69) — channels emits empty; discussions emits 2. Asserts `Empty(recentDiscussions = listOf(d1, d2), recentDiscussionsCount = 2)`.
6. `discussionsCount_updatesReactively` (#26; updated #69) — channels emits one channel; discussions emits 1, then 5. Latest `state` is `Loaded(..., recentDiscussions = listOf(first 3 of 5), recentDiscussionsCount = 5)`.
7. `loadingPersists_untilBothFlowsEmit` (#26; updated #69) — pin `combine`'s wait-for-both semantics: with neither source having emitted, `state.value == Loading`; after only the channels flow emits, still `Loading`; only after discussions emits does `state` transition to `Loaded(..., recentDiscussions = emptyList(), ...)`.
8. `recentDiscussions_isCappedAtThree` (#69) — emit 4 discussions with distinct, descending `lastUsedAt`; assert `recentDiscussions.size == 3` and the IDs match the top-3 by `lastUsedAt` desc. Pins the `.take(3)` slicing.
9. `recentDiscussions_orderingFollowsUpstream` (#69) — emit 3 discussions in a specific order via `MutableSharedFlow`; assert `recentDiscussions` is identity-equal to the emitted list. Pins "the VM does not re-sort, only slices" — fails first if the repository contract drifts.
10. `error_whenChannelsFlowThrows` (renamed from `error_whenSourceFlowThrows` in #26) — channels flow throws `RuntimeException("network down")`; asserts `Error("network down")`.
11. `error_whenDiscussionsFlowThrows` (#26) — discussions flow throws; same `Error` collapse. Pins "throw on either side ⇒ Error".
12. `error_messageIsNonBlank_whenExceptionMessageIsNull` — flow that throws `RuntimeException(null)`; asserts the fallback string path is non-blank.
13. `navigationHandledEvent_isNoOpForTheCompatibilityState` (#26; renamed and repointed at `SettingsTapped` in #731 when `RecentDiscussionsTapped` was retired) — `vm.onEvent(SettingsTapped)` does not crash, does not emit on `navigationEvents`, does not mutate `state`. Proves the reducer leaves `state` alone for an event the navigation host routes itself.
14. `createDiscussionTapped_createsOneUnpromotedConversation` (#22) — uses `FakeConversationRepository()` directly; snapshots `observeConversations(Discussions).first()` before and after `vm.onEvent(CreateDiscussionTapped)`; asserts the new list size increased by one and the new element has `isPromoted == false`.
15. `createDiscussionTapped_emitsToThreadNavigationWithCreatedId` (#22) — launches an `async { vm.navigationEvents.first() }` *before* the triggering `onEvent` call so the collector is attached when the channel sends; `advanceUntilIdle()`; asserts the captured event is `ChannelListNavigation.ToThread` whose `conversationId` equals the id of the newly-created discussion (looked up via the diff between pre- and post-snapshots).
16. `recentDiscussionLastMessages_populatedFromFake_endToEnd` (#161) — drives the real `FakeConversationRepository` through the real VM (no stub). Constructs `makeVm(FakeConversationRepository())` (#239), launches a collector, `advanceUntilIdle()`, asserts the resulting state is `Loaded`, then asserts `loaded.recentDiscussionLastMessages["seed-discussion-a"]` is non-null with `timestamp == Instant.parse("2026-05-11T14:00:00Z")` (the AC's "last message present" case) and `"seed-discussion-b" !in loaded.recentDiscussionLastMessages` (the "no messages → absent from the map" case, not "present with null value"). The single test covers both AC clauses through the real fake — the data-shape edits (`observeLastMessage` projection + `seed-discussion-a` history + `recentDiscussionLastMessages` field + `flatMapLatest` derivation) are all exercised on the integration path, not just at unit boundaries.
17. `longPressFab_setsWorkspacePickerVisibleToTrue` (#221) — `stubRepo(channels, discussions)` with `MutableSharedFlow`s drives a `Loaded` projection (one channel, zero discussions). Launches `vm.state.collect { }` to keep `WhileSubscribed` hot, calls `vm.onEvent(LongPressFab)`, `advanceUntilIdle()`, asserts `(state.value as Loaded).workspacePickerVisible == true`. Stub-shaped (not the real fake) because `LongPressFab` does no repository work — the stub keeps the test focused on the flag-flip + combine-arm projection.
18. `workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility` (#221) — `FakeConversationRepository()` (production fake; we need `createDiscussion` to actually run). Snapshots `observeConversations(Discussions).first()` as `before`. Launches `vm.state.collect { }`, pre-arranges `vm.onEvent(LongPressFab)` so the clear-visibility assertion is meaningful, then `val deferredEvent = async { vm.navigationEvents.first() }`; `vm.onEvent(WorkspacePicked("pyry-workspace/my-folder"))`; `advanceUntilIdle()`. Asserts: (a) the new discussion (computed as `(after - before)` and looked up by id) has `cwd == "pyry-workspace/my-folder"` (the fake sets `cwd = workspace ?: ""` at `FakeConversationRepository.kt:108`); (b) `deferredEvent.await()` is `ToThread(created.id)`; (c) `(state.value as Loaded).workspacePickerVisible == false`. End-to-end coverage of the four invariants the `WorkspacePicked` arm has to maintain.
19. `shortPressFab_usesPersistedDefaultWorkspace` (#240) — `FakeConversationRepository()` end-to-end. `prefs = AppPreferences(newDataStore())` + `prefs.setDefaultWorkspace("~/projects/my-thing")` + `advanceUntilIdle()` *before* `makeVm(repo, prefs = prefs)` so the read sees the persisted value. Snapshots `observeConversations(Discussions).first()` as `before`, dispatches `vm.onEvent(CreateDiscussionTapped)`, `advanceUntilIdle()`, isolates the new conversation via `(after - before.toSet()).single { it.id !in beforeIds }`, asserts `created.cwd == "~/projects/my-thing"`. Pins "the short-press path reads the persisted default and passes it to `createDiscussion`".
20. `longPressPicker_overridesDefaultWorkspace` (#240) — same setup with `prefs.setDefaultWorkspace("~/projects/default-path")`, then dispatches `vm.onEvent(LongPressFab)` followed by `vm.onEvent(WorkspacePicked("~/projects/user-pick"))`, `advanceUntilIdle()`, isolates the new conversation and asserts `created.cwd == "~/projects/user-pick"` — the user pick wins over the persisted default. Pins "the long-press path bypasses the `appPreferences` read; an explicit user pick always overrides the default".

Test infrastructure conventions established here (carry forward to future ViewModel tests):

- Class-level `private val dispatcher = UnconfinedTestDispatcher()` field (#239) — bound to `Dispatchers.setMain(dispatcher)` in `@Before`, passed to `runTest(dispatcher) { … }` in every test body. `resetMain()` in `@After`. Class-level `@OptIn(ExperimentalCoroutinesApi::class)`. (Pre-#239 the dispatcher was constructed inline each `@Before` and tests used bare `runTest { … }`; the lift consolidates both seams onto one field.)
- `makeVm(repository, prefs = AppPreferences(newDataStore()))` owns the compatibility
  fixture's `TemporaryFolder` / `PreferenceDataStoreFactory` rig. It also supplies
  an empty `HostConversationSource` with null lookup and the test dispatcher, then
  disposes that source in teardown. Seed custom preferences with
  `prefs.setDefaultWorkspace(path); advanceUntilIdle()` before constructing the VM.
  The host tests use controlled preference flows to expose suspension boundaries.
  Disable or inject the `RelayLog` sink in JVM tests and restore it after each test;
  see [JVM logging](development-verification.md#jvm-logging-and-formatting).
- **State-derivation tests** use hand-rolled `object : ConversationRepository { ... TODO("not used") }` stubs. The `stubRepo` helper now takes two `Flow<List<Conversation>>` parameters keyed by filter (`stubRepo(channels, discussions)`) — the VM subscribes twice with different filters; one shared flow no longer reflects the production behaviour. Old single-source call sites become `stubRepo(source, emptyFlow())` (or vice-versa) so the test explicitly states which filter it's exercising. `MutableSharedFlow<List<Conversation>>(replay = 0)` for each source — gives the test control over emission timing. `MutableStateFlow` would force an initial value at construction and defeat the Loading-observation window. Since #161, `stubRepo`'s `observeLastMessage` override returns `flowOf(null)` (not `TODO`) — the `mapNotNull` in the VM projection filters the `null` out, so every stub-backed test produces `recentDiscussionLastMessages = emptyMap()` and existing `Empty/Loaded(...)` literals (which omit the field) match by default. `erroringRepo`'s override stays `TODO("not used")` because those tests terminate at the `Error` state before any per-row subscription kicks in. Two-tier rule: stubs whose VMs *can* exercise the new method get a real default return; stubs whose VMs cannot reach it stay `TODO`.
- **Side-effect tests** (suspend-shaped `onEvent` arms) use `FakeConversationRepository()` directly — the production fake gives a faithful integration of `observeConversations` + `createDiscussion`. Don't extend the `stubRepo` helper for these; its `createDiscussion` returns `TODO("not used")`.
- Every non-initial-state test launches `launch { vm.state.collect { } }` to keep `WhileSubscribed` hot before emitting; `advanceUntilIdle()` between launching the collector + emitting, and between emitting + asserting, to drain the in-flight queue.
- One-shot-channel tests: `async { vm.navigationEvents.first() }` launched *before* the trigger, `advanceUntilIdle()` between trigger and `.await()`. `runTest`-friendly capture pattern.

## Edge cases / limitations

- **`Loading` is observable only because the test uses `replay = 0`.** The actual `FakeConversationRepository` projects synchronously from a populated `MutableStateFlow`, so production runtime never observes a real `Loading` frame — the seed records arrive in the same dispatch turn that `stateIn` emits the `initialValue`. The screen will still see `Loading` initially because `collectAsStateWithLifecycle()` snapshots `state.value` at composition time before the next emission lands; the architectural commitment to a `Loading` variant remains correct for the Phase 4 remote impl, where the round-trip is observably non-zero.
- **No `init { }` block, no `refresh()`, no `retry()` method.** Cold-flow re-collection on resubscription is the existing retry surface. Explicit retry lands with the UI control that needs it.
- **`onEvent` is opt-in per variant.** Four variants the VM consumes today: `CreateDiscussionTapped` (#22), `LongPressFab` / `WorkspacePicked` / `WorkspacePickerDismissed` (#221). `TreeRowTapped` / `TreeFoldToggled` / `SettingsTapped` route at the destination instead — the first two call `onHostRowTapped` / `onFoldToggled` directly rather than through `onEvent` (#731), and `SettingsTapped` is pure navigation. The decision rule: events with no VM-side side effect, or whose VM method the destination can call directly, stay routed at the destination; events that need a suspend or VM state mutation funneled through shared reducer logic forward into `onEvent`. Don't preemptively funnel every event through the VM "for consistency".
- **`pendingWorkspacePicker.value` survives across an `Error` transition** (#221). If a flow throws while the picker is open, `combine` collapses to `Error` (no `workspacePickerVisible` field on that variant); the VM's internal `pendingWorkspacePicker.value` retains `true` but is unobservable. When upstream recovers (`Loaded` emits again), the projection reads the retained value and the picker reappears. Acceptable Phase 0 behaviour. If `Error` becomes a routine transient state and auto-reappearance reads as surprising, fix with a `LaunchedEffect(state is Error) { pendingWorkspacePicker.value = false }` — not a projection-shape redesign.
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Two rapid FAB taps create two discussions.** No debounce / single-flight on `CreateDiscussionTapped`. AC reads "single tap creates exactly one new discussion" — per-tap, not "duplicate-prevent". The fake's `createDiscussion` is fast; if real-world races appear they get their own ticket.
- **No `flowOn(Dispatchers.IO)`.** Upstream `observeConversations` inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **Content-free host logging.** Host projection logs a host count; picker and creation paths log static lifecycle/failure events through `RelayLog`. Preview failures never log exception text or decrypted content. The compatibility projection still exposes `Error` without logging it.

## Related

- Host contract: [#705 design](../../specs/architecture/705-host-channel-list.md), [host source identity](dependency-injection.md#host-identity-and-snapshots) and [exact-host repository access](dependency-injection.md#exact-host-repository-access).
- Ticket notes: [`../codebase/45.md`](../codebase/45.md), [`../codebase/22.md`](../codebase/22.md) (FAB → `onEvent` reducer + one-shot nav channel), [`../codebase/26.md`](../codebase/26.md) (`combine` of Channels + Discussions flows, widened `Loaded` / `Empty` to carry `recentDiscussionsCount`, `RecentDiscussionsTapped` event, `stubRepo` helper reshape), [`../codebase/69.md`](../codebase/69.md) (widened `Loaded` / `Empty` with `recentDiscussions: List<Conversation>`; collapsed the `.map { it.size }` projection into a single `combine` emission; two new tests pin `.take(3)` slicing and upstream-ordering contract), [`../codebase/161.md`](../codebase/161.md) (third combined input `lastMessagesFlow` derived via `flatMapLatest(distinctUntilChanged(recentIdsFlow))` + per-row `observeLastMessage` `combine`; `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default-arg affordance lets every existing construction site stay untouched), [`../codebase/221.md`](../codebase/221.md) (fourth combined input `pendingWorkspacePicker: MutableStateFlow<Boolean>` projects onto `workspacePickerVisible: Boolean = false` on `Loaded`/`Empty`; three new `onEvent` arms for `LongPressFab` / `WorkspacePicked(workspace)` / `WorkspacePickerDismissed`; `WorkspacePicked` clears the flag *synchronously before* the suspend launches — same shape as #78's `confirmPromotion`), [`../codebase/239.md`](../codebase/239.md) (pure test-infra refactor: lifts the `SettingsViewModelTest` `TemporaryFolder` + class-level `dispatcher` + `TestScope.newDataStore()` rig into `ChannelListViewModelTest` and introduces a `TestScope.makeVm(repository, prefs = AppPreferences(newDataStore()))` helper that routes all 18 VM construction sites; production code unchanged — the `prefs` default is the seam the next ticket changes one line of when it wires `AppPreferences.defaultWorkspace` into the FAB short-press), [`../codebase/240.md`](../codebase/240.md) (spends the #239 seam: VM gains `AppPreferences` as a second constructor parameter, `CreateDiscussionTapped` reads `appPreferences.defaultWorkspace.first()` inside the existing `viewModelScope.launch { … }` and passes it to `repository.createDiscussion(workspace = …)`; `WorkspacePicked` long-press path unchanged — explicit user pick still overrides the default; two new tests pin both behaviours; first consumer of [`AppPreferences.defaultWorkspace`](./app-preferences.md) since the #231 schema landed)
- Specs: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/26-recent-discussions-pill.md`, `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/161-recent-discussion-last-message-uistate.md`, `docs/specs/architecture/221-channel-list-fab-long-press-workspace-picker.md`, `docs/specs/architecture/729-group-conversations-by-host-and-workspace.md` (`HostWorkspaceGroup.kt`'s `HostConversationRow` / `HostWorkspaceGroup` / `groupConversationsByWorkspace`, consumed by [ChannelListScreen](channel-list-screen.md)'s tree since #731), `docs/specs/architecture/731-assemble-conversation-tree.md`
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam — since #240 `createDiscussion(workspace = <appPreferences.defaultWorkspace.first()>)` is the call the `CreateDiscussionTapped` arm makes — never the no-arg form anymore; `createDiscussion(workspace = event.workspace)` is the #221 call from the `WorkspacePicked` arm; `observeConversations(Discussions)` is the second subscription added in #26 and the same emission #69 re-uses for both `recent` and `count`; `observeLastMessage(id)` from #161 is the per-row subscription the `flatMapLatest` derivation rides), [`AppPreferences`](./app-preferences.md) (since #240; the `defaultWorkspace: Flow<String>` schema landed in #231 and the FAB short-press is its first consumer — the read is `.first()`-shaped, one-shot per event), [data model](./data-model.md) (`Conversation` payload, `Message` payload for `recentDiscussionLastMessages`), [dependency injection](./dependency-injection.md) (Koin wiring)
- Sibling combine-arm pattern: [DiscussionListViewModel](./discussion-list-viewmodel.md) `pendingPromotion` (#78) — the first instance of `combine(upstream, MutableStateFlow<…>)` visibility arm; this VM's `pendingWorkspacePicker` (#221) is the second. The `SaveAsChannelDialog` visibility arm (#142) is the third in the codebase.
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent`, `collectAsStateWithLifecycle()`, and the screen-level loading/empty/error/loaded composables; #22 added the FAB and `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` at the destination; #26 added the pill and consumed `recentDiscussionsCount` off `UiState`; #69 replaced the pill with the inline section — itself replaced by #731's assembled tree, which consumes `hostState.collapsed` / `hostState.selected` and dispatches `onHostRowTapped` / `onFoldToggled` directly, dropping the `selectedServerId()` adapter for row taps; #161 added the third UiState field but no UI consumer — sibling #162 is the consumer slice; #221 consumes `workspacePickerVisible` via a `WorkspacePicker` host composed as a Scaffold sibling), [WorkspacePicker](./workspace-picker.md) (the host the VM's `workspacePickerVisible` field drives), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + reducer arm), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class`; #490 already added the **crash-guard** around both `createDiscussion` launches via [`launchGuardedRepoCall`](guarded-repo-launch.md), so what Phase 4 still owes is the **user-facing error surface**, not the try/catch, + the loading affordance deferred in #22 / #221), [Guarded repo launch](guarded-repo-launch.md) (the #490 one-shot-call guard the two create-discussion launches route through).
