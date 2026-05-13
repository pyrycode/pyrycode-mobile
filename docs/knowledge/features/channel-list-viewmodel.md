# ChannelListViewModel

First `ViewModel` in the codebase. Combines two `observeConversations` flows (`Channels` for the list, `Discussions` for the count) into one hot `StateFlow<ChannelListUiState>` consumed by [`ChannelListScreen`](channel-list-screen.md) (#46), a stateless `(state, onEvent)` composable.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListViewModel.kt`.

## What it does

Observes the persistent-channel slice + the unpromoted-discussions count from the conversation repository, folds them together via `combine`, and exposes the result as a `StateFlow<ChannelListUiState>`. Emits `Loading` initially (before *either* upstream produces a value — `combine` gates first emit on both), then `Empty(recentDiscussionsCount)` or `Loaded(channels, recentDiscussionsCount)` depending on the channels list, and `Error(message)` if either upstream flow throws. The Discussions count surface lands the data the "Recent discussions (N) →" pill (#26) renders.

Also exposes a `fun onEvent(event: ChannelListEvent)` reducer (#22) for events whose side effect requires the VM (currently only `CreateDiscussionTapped` — calls `repository.createDiscussion()` and emits a one-shot `ChannelListNavigation.ToThread` event via a `Channel<ChannelListNavigation>(BUFFERED)` surfaced as `val navigationEvents: Flow<ChannelListNavigation>`). The other `ChannelListEvent` variants (`RowTapped`, `SettingsTapped`, `RecentDiscussionsTapped`) are routed to `navController.navigate(...)` directly at the destination block and never enter `onEvent`; the VM's `when` includes a `Unit` arm for them purely for compiler exhaustiveness.

## Shape

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Empty(val recentDiscussionsCount: Int) : ChannelListUiState
    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussionsCount: Int,
    ) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

sealed interface ChannelListNavigation {
    data class ToThread(val conversationId: String) : ChannelListNavigation
}

class ChannelListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<ChannelListUiState> =
        combine(
            repository.observeConversations(ConversationFilter.Channels),
            repository.observeConversations(ConversationFilter.Discussions).map { it.size },
        ) { channels, discussionsCount ->
            if (channels.isEmpty()) ChannelListUiState.Empty(discussionsCount)
            else ChannelListUiState.Loaded(channels, discussionsCount)
        }
            .catch { e ->
                val raw = e.message
                emit(ChannelListUiState.Error(
                    if (raw.isNullOrBlank()) "Failed to load channels." else raw
                ))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ChannelListUiState.Loading,
            )

    private val navigationChannel = Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ChannelListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: ChannelListEvent) {
        when (event) {
            ChannelListEvent.CreateDiscussionTapped -> viewModelScope.launch {
                val conversation = repository.createDiscussion()
                navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))
            }
            is ChannelListEvent.RowTapped,
            ChannelListEvent.SettingsTapped,
            ChannelListEvent.RecentDiscussionsTapped,
            -> Unit
        }
    }

    private companion object { const val STOP_TIMEOUT_MILLIS = 5_000L }
}
```

`ChannelListUiState` and `ChannelListNavigation` are top-level (siblings of the VM), not nested inside the VM — call sites import `ChannelListUiState.Loaded` / `ChannelListNavigation.ToThread` directly rather than `ChannelListViewModel.UiState.Loaded`. The screen-name prefix carries enough disambiguation.

## How it works

### Cold-to-hot via `stateIn`

`observeConversations` is a cold `Flow`; `stateIn` shares one upstream collection across all subscribers and exposes a `StateFlow` with a current `.value`. The configuration:

- **`scope = viewModelScope`** — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by the VM.
- **`started = SharingStarted.WhileSubscribed(5_000)`** — the upstream starts collecting when `state` gets its first subscriber and stops 5s after the last subscriber unsubscribes. Configuration changes (rotation) re-subscribe within ms, so the 5s grace prevents collector churn; navigating away for >5s truly disposes the upstream.
- **`initialValue = Loading`** — guarantees the AC's "Initial emitted state is `Loading` before the repository flow produces a value". `stateIn` returns synchronously and the first real upstream value replaces `Loading` on the next dispatch turn.

### State projection

```
channelsFlow ──┐
               ├─ combine ──► Empty(count)         (when channels list is empty)
discCountFlow ─┘            └► Loaded(ch, count)   (otherwise)
                 ─── catch ─► Error(message)
                 ─── stateIn ─► initial = Loading
```

`combine` (#26) replaced the single-flow `map` shape. The two upstreams are independent subscriptions on the same `ConversationRepository`; both are cold flows, both inherit the `WhileSubscribed(5_000)` shared lifetime. `combine` waits for *both* sides to emit before the first downstream emission — this is what keeps the `Loading` initial frame observable until the data layer has fully answered (see "Edge cases / limitations"). `.catch { }` sits between `combine { }` and `.stateIn(...)` because `stateIn` is a terminal operator (returns `StateFlow`, not `Flow`) — `.catch` after it doesn't compile. One `catch` block covers both upstream throwables; a throw on *either* the channels flow or the discussions flow collapses the whole pipeline to `Error`.

### Error semantics

`Error(message)` is terminal in the underlying flow: once `catch` consumes the exception and emits an `Error`, the upstream has already errored and produces no further values. Recovery requires a fresh subscription — currently only achievable by leaving and re-entering the screen (subscribers drop to zero, `WhileSubscribed(5_000)` expires, a new subscription starts a fresh collection). A `retry()` event-driven path lands with the screen-side "Retry" button.

Message extraction: `e.message` verbatim when non-null and non-blank, else the literal fallback `"Failed to load channels."`. Never `e.toString()` or `e::class.simpleName` — exception class names are implementation detail and read badly in user-facing UI.

### One-shot navigation via `Channel.BUFFERED` (#22)

`navigationChannel` is a `Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)` exposed via `receiveAsFlow()`. `Channel` (not `SharedFlow`) because a nav event must fire exactly once even if the collector momentarily isn't attached — `MutableSharedFlow` would either replay (re-navigating on rotation) or drop (losing in-flight taps). `BUFFERED` because the burst is one event at a time; no back-pressure semantics are wanted.

Consumer is `MainActivity`'s `composable(Routes.ChannelList)` block, which runs `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` to translate `ChannelListNavigation.ToThread` into `navController.navigate("conversation_thread/${event.conversationId}")`. The `vm` key restarts the collector exactly when the VM identity changes (per `NavBackStackEntry` scope). Cancellation is atomic with the launching coroutine: if the user navigates away mid-`createDiscussion`, `viewModelScope` cancels both the suspend and the pending `send`.

### `onEvent` reducer (#22)

Single dispatched arm: `CreateDiscussionTapped -> viewModelScope.launch { … }`. Calls `repository.createDiscussion()` with no argument (workspace defaults to `null`), then `navigationChannel.send(ToThread(conversation.id))`. No `try/catch` — the fake never throws; speculative defense forbidden by project principles. Phase 4's `RemoteConversationRepository` adds the catch and the error UI together.

The `is RowTapped, SettingsTapped, RecentDiscussionsTapped -> Unit` arm exists for compiler exhaustiveness; `MainActivity` never forwards those into `onEvent`, but if the dispatch convention shifts later the VM tolerates them defensively (no-op). `RecentDiscussionsTapped` joined the arm in #26 — same rationale (pure navigation, no VM-side side effect).

## Wiring

Koin binding (already registered in [`AppModule`](./dependency-injection.md)):

```kotlin
viewModel { ChannelListViewModel(get()) }
```

The constructor parameter type is `ConversationRepository` (the interface, not the `FakeConversationRepository` impl). Koin's `single { FakeConversationRepository() } bind ConversationRepository::class` makes `get()` resolve to the bound implementation; Phase 4's remote impl drops in behind the same bind line with no VM change.

Composable resolution at the `composable(Routes.ChannelList) { ... }` destination (landed in #46):

```kotlin
val vm = koinViewModel<ChannelListViewModel>()
val state by vm.state.collectAsStateWithLifecycle()
```

`koinViewModel<…>()` (from `org.koin.androidx.compose`) routes through `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ auto-wires to the current `NavBackStackEntry` — so the VM is scoped to the back-stack entry, surviving configuration changes and tearing down on pop. `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose` (added to the catalog in #46), distinct from the `-ktx` artifacts already on the classpath.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-viewmodel-ktx` (catalog: `androidx-lifecycle-viewmodel-ktx`, same `lifecycleRuntimeKtx` version-ref as `lifecycle-runtime-ktx`). Required for `viewModelScope` at lifecycle 2.6.1 — `koin-androidx-compose` brings `lifecycle-viewmodel-compose` transitively, but that artifact depends on the non-`-ktx` base which lacks `viewModelScope`.
- **Test dependencies:** `org.jetbrains.kotlinx:kotlinx-coroutines-test` on `testImplementation` (catalog: `kotlinx-coroutines-test`, reuses `kotlinxCoroutines` version-ref). Required for `Dispatchers.setMain(...)` — `stateIn(viewModelScope, ...)` dispatches on `Dispatchers.Main.immediate` which is uninitialised in plain JVM unit tests.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`. JUnit 4, matching the existing test-class style. Fourteen tests (seven new in #26 for the `combine` + count surface):

1. `initialState_isLoading` — reads `vm.state.value` before any subscriber attaches; relies on `stateIn`'s `initialValue` being immediately visible without a hot collector.
2. `loaded_whenSourceEmitsNonEmpty` — launched collector, channels source emits `listOf(sampleChannel)`, asserts `Loaded(listOf(sampleChannel), recentDiscussionsCount = 0)`.
3. `empty_whenSourceEmitsEmptyList` — launched collector, channels emits `emptyList()`, asserts `Empty(0)`.
4. `loaded_carriesDiscussionsCount` (#26) — channels emits one channel; discussions emits 3 items. Asserts `Loaded(channels = [one], recentDiscussionsCount = 3)`.
5. `empty_carriesDiscussionsCount` (#26) — channels emits empty; discussions emits 2. Asserts `Empty(2)`.
6. `discussionsCount_updatesReactively` (#26) — channels emits one channel; discussions emits 1, then 5. Latest `state` is `Loaded(..., recentDiscussionsCount = 5)`.
7. `loadingPersists_untilBothFlowsEmit` (#26) — pin `combine`'s wait-for-both semantics: with neither source having emitted, `state.value == Loading`; after only the channels flow emits, still `Loading`; only after discussions emits does `state` transition.
8. `error_whenChannelsFlowThrows` (renamed from `error_whenSourceFlowThrows` in #26) — channels flow throws `RuntimeException("network down")`; asserts `Error("network down")`.
9. `error_whenDiscussionsFlowThrows` (#26) — discussions flow throws; same `Error` collapse. Pins "throw on either side ⇒ Error".
10. `error_messageIsNonBlank_whenExceptionMessageIsNull` — flow that throws `RuntimeException(null)`; asserts the fallback string path is non-blank.
11. `recentDiscussionsTapped_isNoOp` (#26) — `vm.onEvent(RecentDiscussionsTapped)` does not crash, does not emit on `navigationEvents`, does not mutate `state`. Mirrors the existing implicit coverage for `SettingsTapped`.
12. `createDiscussionTapped_createsOneUnpromotedConversation` (#22) — uses `FakeConversationRepository()` directly; snapshots `observeConversations(Discussions).first()` before and after `vm.onEvent(CreateDiscussionTapped)`; asserts the new list size increased by one and the new element has `isPromoted == false`.
13. `createDiscussionTapped_emitsToThreadNavigationWithCreatedId` (#22) — launches an `async { vm.navigationEvents.first() }` *before* the triggering `onEvent` call so the collector is attached when the channel sends; `advanceUntilIdle()`; asserts the captured event is `ChannelListNavigation.ToThread` whose `conversationId` equals the id of the newly-created discussion (looked up via the diff between pre- and post-snapshots).

Test infrastructure conventions established here (carry forward to future ViewModel tests):

- `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain()` in `@After`. Class-level `@OptIn(ExperimentalCoroutinesApi::class)`.
- **State-derivation tests** use hand-rolled `object : ConversationRepository { ... TODO("not used") }` stubs. The `stubRepo` helper now takes two `Flow<List<Conversation>>` parameters keyed by filter (`stubRepo(channels, discussions)`) — the VM subscribes twice with different filters; one shared flow no longer reflects the production behaviour. Old single-source call sites become `stubRepo(source, emptyFlow())` (or vice-versa) so the test explicitly states which filter it's exercising. `MutableSharedFlow<List<Conversation>>(replay = 0)` for each source — gives the test control over emission timing. `MutableStateFlow` would force an initial value at construction and defeat the Loading-observation window.
- **Side-effect tests** (suspend-shaped `onEvent` arms) use `FakeConversationRepository()` directly — the production fake gives a faithful integration of `observeConversations` + `createDiscussion`. Don't extend the `stubRepo` helper for these; its `createDiscussion` returns `TODO("not used")`.
- Every non-initial-state test launches `launch { vm.state.collect { } }` to keep `WhileSubscribed` hot before emitting; `advanceUntilIdle()` between launching the collector + emitting, and between emitting + asserting, to drain the in-flight queue.
- One-shot-channel tests: `async { vm.navigationEvents.first() }` launched *before* the trigger, `advanceUntilIdle()` between trigger and `.await()`. `runTest`-friendly capture pattern.

## Edge cases / limitations

- **`Loading` is observable only because the test uses `replay = 0`.** The actual `FakeConversationRepository` projects synchronously from a populated `MutableStateFlow`, so production runtime never observes a real `Loading` frame — the seed records arrive in the same dispatch turn that `stateIn` emits the `initialValue`. The screen will still see `Loading` initially because `collectAsStateWithLifecycle()` snapshots `state.value` at composition time before the next emission lands; the architectural commitment to a `Loading` variant remains correct for the Phase 4 remote impl, where the round-trip is observably non-zero.
- **No `init { }` block, no `refresh()`, no `retry()` method.** Cold-flow re-collection on resubscription is the existing retry surface. Explicit retry lands with the UI control that needs it.
- **`onEvent` is opt-in per variant.** `CreateDiscussionTapped` (#22) is the only variant the VM consumes today; `RowTapped` / `SettingsTapped` route at the destination because they have no VM-side side effect. The decision rule: events with no VM-side side effect stay routed at the destination; events that need a suspend or VM state mutation forward into `onEvent`. Don't preemptively funnel every event through the VM "for consistency".
- **One-shot navigation is `Channel`-backed, not `StateFlow<Navigation?>`.** `MutableSharedFlow` was considered and rejected: replay-1 would re-fire on rotation, replay-0 would drop in-flight taps. `Channel(BUFFERED)` + `receiveAsFlow()` is the right shape — survives the recomposition window between tap and consume, cancels atomically with `viewModelScope`.
- **Two rapid FAB taps create two discussions.** No debounce / single-flight on `CreateDiscussionTapped`. AC reads "single tap creates exactly one new discussion" — per-tap, not "duplicate-prevent". The fake's `createDiscussion` is fast; if real-world races appear they get their own ticket.
- **No `flowOn(Dispatchers.IO)`.** Upstream `observeConversations` inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **No logging.** No `Log.e(...)` in `catch`. Phase 4 introduces a logging strategy alongside the network layer; until then, the `Error` state is the diagnostic surface.

## Related

- Ticket notes: [`../codebase/45.md`](../codebase/45.md), [`../codebase/22.md`](../codebase/22.md) (FAB → `onEvent` reducer + one-shot nav channel), [`../codebase/26.md`](../codebase/26.md) (`combine` of Channels + Discussions flows, widened `Loaded` / `Empty` to carry `recentDiscussionsCount`, `RecentDiscussionsTapped` event, `stubRepo` helper reshape)
- Specs: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/26-recent-discussions-pill.md`
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam — `createDiscussion(workspace = null)` is the call the `onEvent` reducer makes; `observeConversations(Discussions)` is the second subscription added in #26), [data model](./data-model.md) (`Conversation` payload), [dependency injection](./dependency-injection.md) (Koin wiring)
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent`, `collectAsStateWithLifecycle()`, and the screen-level loading/empty/error/loaded composables; #22 added the FAB and `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` at the destination; #26 added the pill below/above the screen body and consumes `recentDiscussionsCount` off `UiState`), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + reducer arm), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class`; adds error handling around `createDiscussion()` and the loading affordance deferred in #22).
