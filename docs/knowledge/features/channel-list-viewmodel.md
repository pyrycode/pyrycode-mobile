# ChannelListViewModel

Exposes ordered host-qualified channel/chat state and explicit host actions through
`HostConversationSource`. The existing flat-screen state, reducer and bare-id
navigation remain bound to the selected-host repository or demo fake until the
[screen](channel-list-screen.md) and [navigation](navigation.md) consumers migrate.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListViewModel.kt`.

## What it does

`hostState` preserves each source host's metadata and active rows, adds a recent-three
chat slice, full chat count and host-local optional previews, and exposes the pending
workspace picker's exact `serverId`. Host row activation and successful creation
emit `HostConversationTarget(serverId, conversationId)` on `hostNavigationEvents`.
Creation retains its explicit host across preference reads and picker interaction.

The compatibility `state: StateFlow<ChannelListUiState>` still combines the injected
repository's Channels and Discussions flows, recent-message previews and picker
visibility. It starts at `Loading`, then emits `Empty` or `Loaded` with recent-three
discussions and the full count, or `Error` when an upstream fails. `onEvent` handles
creation and picker events; successful legacy creation emits `ToThread(id)` on
`navigationEvents`. Legacy row/settings/discussion-list navigation remains in the
destination. These two contracts have separate picker state and navigation channels.

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
)

data class HostChannelListState(
    val hosts: List<HostChannelListEntry> = emptyList(),
    val workspacePickerServerId: String? = null,
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

### Cold-to-hot via `stateIn`

For the compatibility `state`, `observeConversations` is a cold `Flow`; `stateIn` shares one upstream collection across all subscribers and exposes a `StateFlow` with a current `.value`. The configuration:

- **`scope = viewModelScope`** — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by the VM.
- **`started = SharingStarted.WhileSubscribed(5_000)`** — the upstream starts collecting when `state` gets its first subscriber and stops 5s after the last subscriber unsubscribes. Configuration changes (rotation) re-subscribe within ms, so the 5s grace prevents collector churn; navigating away for >5s truly disposes the upstream.
- **`initialValue = Loading`** — guarantees the AC's "Initial emitted state is `Loading` before the repository flow produces a value". `stateIn` returns synchronously and the first real upstream value replaces `Loading` on the next dispatch turn.

### State projection

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

### Per-row `observeLastMessage` via `flatMapLatest(distinctUntilChanged(idsFlow))` (#161)

The compatibility `lastMessagesFlow` is the project's first bounded-fan-out subscription pattern — per-element side data sourced from a bounded parent slice via dynamic `combine` of per-row cold flows. Three load-bearing operators:

- **`recentIdsFlow = discussionsFlow.map { it.take(3).map(Conversation::id) }.distinctUntilChanged()`** — the `distinctUntilChanged` is non-optional. Without it, every discussions re-emission (even ones that don't change the recent-3) would tear down and rebuild the inner `combine`, churning per-row subscriptions. The id-list-equality check is what makes the steady-state subscription stable.
- **`flatMapLatest { ids -> … }`** — the `flatMapLatest` (vs `flatMapConcat` or `flatMapMerge`) is non-optional. When the recent slice shifts (e.g. a new discussion takes the top-3 spot, pushing another out), the obsolete per-row subscriptions must be cancelled before the new ones start — otherwise stale entries leak into the next emission. `flatMapLatest`'s "cancel previous inner flow on new outer emission" semantics are exactly this guarantee.
- **Inner `combine(ids.map { id -> observeLastMessage(id).map { msg -> id to msg } }) { pairs -> pairs.mapNotNull { (id, msg) -> msg?.let { id to it } }.toMap() }`** — dynamic-arity `combine` over the per-row cold flows. `mapNotNull` filters `null` last-messages so the resulting `Map<String, Message>` has no nullable values (the "absent key = null last message" contract); call sites that read `map[id]` get unambiguous `Message?` semantics. The empty-ids branch short-circuits to `flowOf(emptyMap())` because `combine(emptyList())` is not well-defined.

`flatMapLatest` carries `@OptIn(ExperimentalCoroutinesApi::class)` in `kotlinx-coroutines 1.10.2`; the opt-in is scoped to the `state` property (not file-level) so the experimental dependency stays visible at the use site. The recent slice is capped at `RECENT_DISCUSSIONS_LIMIT = 3`, so the inner `combine` arity is bounded — a batched `observeLastMessages(ids: Set<String>)` repository method was considered and rejected as Phase 1 over-engineering. If the bound grows or per-row data becomes a network call, revisit the batched API at that point.

### Default-value affordance on the new field

The `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default on both `Empty` and `Loaded` (#161) is what kept the ticket data-layer-only. Without it, every existing construction site would have needed an explicit `recentDiscussionLastMessages = emptyMap()` argument: 6 in `ChannelListScreen.kt` previews, 6 in `ChannelListViewModelTest`, 2 in `ChannelListScreenTest`. With it, none of those 14 sites change — the VM emits with `emptyMap()` (because the test `stubRepo.observeLastMessage` returns `flowOf(null)` and the projection's `mapNotNull` filters it out) and data-class equality matches expected literals that omit the new arg. Generalised rule: when widening a UiState data class with a field that has a sensible "no data" identity (`emptyMap()`, `emptyList()`, `null` for nullable references), provide a default — the only reason to omit it is when forcing every site to acknowledge the new field is the actual goal.

### `.take(3)` against an upstream-sorted flow — no VM-side resort

`FakeConversationRepository.observeConversations(Discussions)` already emits `.sortedByDescending { it.lastUsedAt }`. The VM does `discussions.take(RECENT_DISCUSSIONS_LIMIT)` with no `.sortedByDescending` of its own — re-sorting would have duplicated the contract and risked the two sorts disagreeing if the upstream definition drifts (e.g. Phase 4's `RemoteConversationRepository` returning server-ordered records). The `recentDiscussions_orderingFollowsUpstream` test pins this: the VM slices but does not re-sort. If the repository contract changes its sort key, that test is the first to fail.

### Error semantics

For the compatibility `state`, `Error(message)` is terminal in the underlying flow: once `catch` consumes the exception and emits an `Error`, the upstream has already errored and produces no further values. Recovery requires a fresh subscription — currently only achievable by leaving and re-entering the screen (subscribers drop to zero, `WhileSubscribed(5_000)` expires, a new subscription starts a fresh collection). A `retry()` event-driven path lands with the screen-side "Retry" button.

Message extraction: `e.message` verbatim when non-null and non-blank, else the literal fallback `"Failed to load channels."`. Never `e.toString()` or `e::class.simpleName` — exception class names are implementation detail and read badly in user-facing UI.

### One-shot navigation via `Channel.BUFFERED` (#22)

Both navigation flows use separate `Channel.BUFFERED` channels exposed through
`receiveAsFlow()`. Buffering keeps an event until a collector consumes it without
replaying it to a later collector. `hostNavigationEvents` carries the full
`HostConversationTarget`; `onHostRowTapped(target)` emits that exact pair without
creating a conversation or checking live repository availability.

Host creation uses explicit targets:

- `createHostDiscussion(serverId)` captures its argument before reading
  `appPreferences.defaultWorkspace.first()`. It passes the value verbatim, including
  the fresh-preferences `DEFAULT_SCRATCH_CWD` (`~/.pyrycode/scratch`) fallback.
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

The compatibility `navigationEvents` still carries `ChannelListNavigation.ToThread(id)`.
`MainActivity` collects it in the channel-list destination's `LaunchedEffect(vm)` and
navigates to `conversation_thread/${event.conversationId}`. Host actions never feed
this bare-id channel; adapting a host target by dropping its host would route through
the wrong compatibility selection. The host-aware route consumer remains #636.
Action coroutines and preview collection are cancelled when the ViewModel is cleared.

### `onEvent` reducer (#22, widened in #221)

Four compatibility `ChannelListEvent` arms are dispatched today; host-aware callers use the explicit methods above:

- **`CreateDiscussionTapped -> launchGuardedRepoCall { … }`** (#22, widened in #240, **guarded in #490**). Two-suspend sequence: `val workspace = appPreferences.defaultWorkspace.first()` (one-shot read of the persisted default — `DEFAULT_SCRATCH_CWD` on a fresh DataStore, or whatever Settings has set), then `val conversation = repository.createDiscussion(workspace = workspace)`, then `navigationChannel.send(ToThread(conversation.id))`. The `.first()` inherits `viewModelScope`'s dispatcher (`Dispatchers.Main.immediate`); DataStore switches internally to its own IO dispatcher and back. **Since #490 the launch routes through [`launchGuardedRepoCall`](guarded-repo-launch.md)** — under the relay repository a `RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException` from `createDiscussion` is inertly swallowed instead of reaching the default uncaught-exception handler and killing the process, and the follow-on `ToThread` send (kept **inside** the guard) is skipped on a throw. This is a **fail-quietly** guard, not error surfacing — no user-facing error UI yet; that Phase-4 affordance still lands separately. The `defaultWorkspace` projection's `?: DEFAULT_SCRATCH_CWD` fallback still guarantees a non-null `String` on the happy path. Pre-#240 the call was bare `repository.createDiscussion()` — the no-arg path hit `FakeConversationRepository.kt:108`'s `workspace ?: ""` and produced `cwd = ""`; post-#240 an unconfigured user gets `cwd = "~/.pyrycode/scratch"` instead (both forms hit `bumpWorkspace`'s `cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD` early-return, so no recent-workspaces pollution). Don't add a normalization step that maps `DEFAULT_SCRATCH_CWD` → `""` to preserve the old shape — that's a defense for a problem that hasn't been observed.
- **`LongPressFab -> pendingWorkspacePicker.value = true`** (#221). One line; flips the visibility flag, the combine projects, the screen re-renders with `workspacePickerVisible = true`, the host opens its sheet.
- **`is WorkspacePicked -> { pendingWorkspacePicker.value = false; launchGuardedRepoCall { … } }`** (#221, **guarded in #490**). Same shape as `CreateDiscussionTapped` (same [`launchGuardedRepoCall`](guarded-repo-launch.md) guard) but with `repository.createDiscussion(workspace = event.workspace)` instead of the no-arg default, and with the **synchronous flag-clear before the suspend**. Clearing first starts the sheet's exit immediately instead of waiting for `createDiscussion`. This compatibility arm does not check a pending target; duplicate-completion suppression belongs to `pickHostWorkspace`, whose captured host is cleared before launching.
- **`WorkspacePickerDismissed -> pendingWorkspacePicker.value = false`** (#221). One line; the host's `onDismiss` callback fires when the user dismisses the sheet (close icon, scrim tap, drag-down, back-press), the screen forwards to this arm, the flag flips, the projection emits, the host re-composes with `visible = false`, the `ModalBottomSheet` runs its exit animation as it leaves composition.

The `is RowTapped, SettingsTapped, RecentDiscussionsTapped -> Unit` arm exists for compiler exhaustiveness; `MainActivity` never forwards those into `onEvent`, but if the dispatch convention shifts later the VM tolerates them defensively (no-op). `RecentDiscussionsTapped` joined the arm in #26 — same rationale (pure navigation, no VM-side side effect).

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
never enter the demo source. `AppPreferences` is shared with Settings.

This is a temporary compatibility boundary: `state`, `onEvent(ChannelListEvent)`
and `navigationEvents` continue to serve the flat screen through the injected
repository. They receive neither a flattened multi-host list nor host navigation
with its host discarded. The source dependency adds `hostState`, explicit host
actions and `hostNavigationEvents`; screen layout/event producers remain #641 and
host-qualified route consumption remains #636. Existing screen consumers still use:

```kotlin
val vm = koinViewModel<ChannelListViewModel>()
val state by vm.state.collectAsStateWithLifecycle()
```

`koinViewModel<…>()` (from `org.koin.androidx.compose`) routes through `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ auto-wires to the current `NavBackStackEntry` — so the VM is scoped to the back-stack entry, surviving configuration changes and tearing down on pop. `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose` (added to the catalog in #46), distinct from the `-ktx` artifacts already on the classpath.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-viewmodel-ktx` (catalog: `androidx-lifecycle-viewmodel-ktx`, same `lifecycleRuntimeKtx` version-ref as `lifecycle-runtime-ktx`). Required for `viewModelScope` at lifecycle 2.6.1 — `koin-androidx-compose` brings `lifecycle-viewmodel-compose` transitively, but that artifact depends on the non-`-ktx` base which lacks `viewModelScope`.
- **Test dependencies:** `org.jetbrains.kotlinx:kotlinx-coroutines-test` on `testImplementation` (catalog: `kotlinx-coroutines-test`, reuses `kotlinxCoroutines` version-ref). Required for `Dispatchers.setMain(...)` — `stateIn(viewModelScope, ...)` dispatches on `Dispatchers.Main.immediate` which is uninitialised in plain JVM unit tests.

## Testing

`HostChannelListViewModelTest` covers the host contract with controlled source and
repository fixtures. Use colliding ids and unchanged paths on case-distinct hosts,
and assert each host's own preview: globally unique fixture ids can conceal a
cross-host merge. A silent host and silent preview must coexist with visible rows
from another host; disconnect must preserve cached rows while removing previews.

For creation, suspend the preference flow, change compatibility selection and
replace the original host's repository before releasing the preference value.
Checking only a settled happy path would miss an early repository capture or a
send redirected to the selected host. Picker coverage uses a preference flow that
throws if read, changes selection while open, and holds creation suspended while
checking immediate target clearing and duplicate completion suppression.

Unavailable-target coverage denies lookup even with cached rows and connected
indicators. Failure tests inspect the action job's cancellation state as well as
missing navigation: absence of navigation alone cannot prove cancellation was
re-thrown. Collect both navigation streams to prove host and legacy actions remain
isolated. The DI test resolves the actual `appModule` definition and proves demo
creation reaches the existing fake singleton. These are deterministic contract
tests; screen/route live coverage remains #676/#673.

`ChannelListViewModelTest` retains the flat-screen compatibility checks. Both test
classes live under `app/src/test/java/de/pyryco/mobile/ui/conversations/list/` and
use JUnit 4. Compatibility coverage includes:

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
13. `recentDiscussionsTapped_isNoOp` (#26) — `vm.onEvent(RecentDiscussionsTapped)` does not crash, does not emit on `navigationEvents`, does not mutate `state`. Mirrors the existing implicit coverage for `SettingsTapped`.
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
- **`onEvent` is opt-in per variant.** Four variants the VM consumes today: `CreateDiscussionTapped` (#22), `LongPressFab` / `WorkspacePicked` / `WorkspacePickerDismissed` (#221). `RowTapped` / `SettingsTapped` / `RecentDiscussionsTapped` route at the destination because they have no VM-side side effect. The decision rule: events with no VM-side side effect stay routed at the destination; events that need a suspend or VM state mutation forward into `onEvent`. Don't preemptively funnel every event through the VM "for consistency".
- **`pendingWorkspacePicker.value` survives across an `Error` transition** (#221). If a flow throws while the picker is open, `combine` collapses to `Error` (no `workspacePickerVisible` field on that variant); the VM's internal `pendingWorkspacePicker.value` retains `true` but is unobservable. When upstream recovers (`Loaded` emits again), the projection reads the retained value and the picker reappears. Acceptable Phase 0 behaviour. If `Error` becomes a routine transient state and auto-reappearance reads as surprising, fix with a `LaunchedEffect(state is Error) { pendingWorkspacePicker.value = false }` — not a projection-shape redesign.
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Two rapid FAB taps create two discussions.** No debounce / single-flight on `CreateDiscussionTapped`. AC reads "single tap creates exactly one new discussion" — per-tap, not "duplicate-prevent". The fake's `createDiscussion` is fast; if real-world races appear they get their own ticket.
- **No `flowOn(Dispatchers.IO)`.** Upstream `observeConversations` inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **Content-free host logging.** Host projection logs a host count; picker and creation paths log static lifecycle/failure events through `RelayLog`. Preview failures never log exception text or decrypted content. The compatibility projection still exposes `Error` without logging it.

## Related

- Host contract: [#705 design](../../specs/architecture/705-host-channel-list.md), [host source identity](dependency-injection.md#host-identity-and-snapshots) and [exact-host repository access](dependency-injection.md#exact-host-repository-access).
- Ticket notes: [`../codebase/45.md`](../codebase/45.md), [`../codebase/22.md`](../codebase/22.md) (FAB → `onEvent` reducer + one-shot nav channel), [`../codebase/26.md`](../codebase/26.md) (`combine` of Channels + Discussions flows, widened `Loaded` / `Empty` to carry `recentDiscussionsCount`, `RecentDiscussionsTapped` event, `stubRepo` helper reshape), [`../codebase/69.md`](../codebase/69.md) (widened `Loaded` / `Empty` with `recentDiscussions: List<Conversation>`; collapsed the `.map { it.size }` projection into a single `combine` emission; two new tests pin `.take(3)` slicing and upstream-ordering contract), [`../codebase/161.md`](../codebase/161.md) (third combined input `lastMessagesFlow` derived via `flatMapLatest(distinctUntilChanged(recentIdsFlow))` + per-row `observeLastMessage` `combine`; `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default-arg affordance lets every existing construction site stay untouched), [`../codebase/221.md`](../codebase/221.md) (fourth combined input `pendingWorkspacePicker: MutableStateFlow<Boolean>` projects onto `workspacePickerVisible: Boolean = false` on `Loaded`/`Empty`; three new `onEvent` arms for `LongPressFab` / `WorkspacePicked(workspace)` / `WorkspacePickerDismissed`; `WorkspacePicked` clears the flag *synchronously before* the suspend launches — same shape as #78's `confirmPromotion`), [`../codebase/239.md`](../codebase/239.md) (pure test-infra refactor: lifts the `SettingsViewModelTest` `TemporaryFolder` + class-level `dispatcher` + `TestScope.newDataStore()` rig into `ChannelListViewModelTest` and introduces a `TestScope.makeVm(repository, prefs = AppPreferences(newDataStore()))` helper that routes all 18 VM construction sites; production code unchanged — the `prefs` default is the seam the next ticket changes one line of when it wires `AppPreferences.defaultWorkspace` into the FAB short-press), [`../codebase/240.md`](../codebase/240.md) (spends the #239 seam: VM gains `AppPreferences` as a second constructor parameter, `CreateDiscussionTapped` reads `appPreferences.defaultWorkspace.first()` inside the existing `viewModelScope.launch { … }` and passes it to `repository.createDiscussion(workspace = …)`; `WorkspacePicked` long-press path unchanged — explicit user pick still overrides the default; two new tests pin both behaviours; first consumer of [`AppPreferences.defaultWorkspace`](./app-preferences.md) since the #231 schema landed)
- Specs: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/26-recent-discussions-pill.md`, `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/161-recent-discussion-last-message-uistate.md`, `docs/specs/architecture/221-channel-list-fab-long-press-workspace-picker.md`
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam — since #240 `createDiscussion(workspace = <appPreferences.defaultWorkspace.first()>)` is the call the `CreateDiscussionTapped` arm makes — never the no-arg form anymore; `createDiscussion(workspace = event.workspace)` is the #221 call from the `WorkspacePicked` arm; `observeConversations(Discussions)` is the second subscription added in #26 and the same emission #69 re-uses for both `recent` and `count`; `observeLastMessage(id)` from #161 is the per-row subscription the `flatMapLatest` derivation rides), [`AppPreferences`](./app-preferences.md) (since #240; the `defaultWorkspace: Flow<String>` schema landed in #231 and the FAB short-press is its first consumer — the read is `.first()`-shaped, one-shot per event), [data model](./data-model.md) (`Conversation` payload, `Message` payload for `recentDiscussionLastMessages`), [dependency injection](./dependency-injection.md) (Koin wiring)
- Sibling combine-arm pattern: [DiscussionListViewModel](./discussion-list-viewmodel.md) `pendingPromotion` (#78) — the first instance of `combine(upstream, MutableStateFlow<…>)` visibility arm; this VM's `pendingWorkspacePicker` (#221) is the second. The `SaveAsChannelDialog` visibility arm (#142) is the third in the codebase.
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent`, `collectAsStateWithLifecycle()`, and the screen-level loading/empty/error/loaded composables; #22 added the FAB and `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` at the destination; #26 added the pill and consumed `recentDiscussionsCount` off `UiState`; #69 replaced the pill with the inline section and now consumes both `recentDiscussions` and `recentDiscussionsCount`; #161 added the third UiState field but no UI consumer — sibling #162 is the consumer slice that reads `state.recentDiscussionLastMessages[conversation.id]` inside `RecentDiscussionsSection`; #221 consumes the new `workspacePickerVisible` field via a `WorkspacePicker` host composed as a Scaffold sibling), [WorkspacePicker](./workspace-picker.md) (the host the VM's `workspacePickerVisible` field drives), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + reducer arm), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class`; #490 already added the **crash-guard** around both `createDiscussion` launches via [`launchGuardedRepoCall`](guarded-repo-launch.md), so what Phase 4 still owes is the **user-facing error surface**, not the try/catch, + the loading affordance deferred in #22 / #221), [Guarded repo launch](guarded-repo-launch.md) (the #490 one-shot-call guard the two create-discussion launches route through).
