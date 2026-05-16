# ChannelListViewModel

First `ViewModel` in the codebase. Combines two `observeConversations` flows (`Channels` for the list, `Discussions` for the top-3 list *and* total count, #69) into one hot `StateFlow<ChannelListUiState>` consumed by [`ChannelListScreen`](channel-list-screen.md) (#46), a stateless `(state, onEvent)` composable.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListViewModel.kt`.

## What it does

Observes the persistent-channel slice + the unpromoted-discussions list from the conversation repository, folds them together via `combine`, and exposes the result as a `StateFlow<ChannelListUiState>`. Emits `Loading` initially (before *any* upstream produces a value — `combine` gates first emit on all three sources), then `Empty(recentDiscussions, recentDiscussionsCount, recentDiscussionLastMessages)` or `Loaded(channels, recentDiscussions, recentDiscussionsCount, recentDiscussionLastMessages)` depending on the channels list, and `Error(message)` if any upstream flow throws. Since #69 the Discussions upstream lands as both `recentDiscussions = discussions.take(3)` (the top-3 surface consumed by the inline section's preview rows) and `recentDiscussionsCount = discussions.size` (the total used by the "See all discussions (N) →" link); both values come from a single `combine` emission. Since #161 a third combined input carries `recentDiscussionLastMessages: Map<String, Message>` keyed by recent-discussion id (default `emptyMap()`) — sourced via a per-row `observeLastMessage` subscription derived from the recent slice through `flatMapLatest`.

Also exposes a `fun onEvent(event: ChannelListEvent)` reducer (#22) for events whose side effect requires the VM (currently only `CreateDiscussionTapped` — calls `repository.createDiscussion()` and emits a one-shot `ChannelListNavigation.ToThread` event via a `Channel<ChannelListNavigation>(BUFFERED)` surfaced as `val navigationEvents: Flow<ChannelListNavigation>`). The other `ChannelListEvent` variants (`RowTapped`, `SettingsTapped`, `RecentDiscussionsTapped`) are routed to `navController.navigate(...)` directly at the destination block and never enter `onEvent`; the VM's `when` includes a `Unit` arm for them purely for compiler exhaustiveness.

## Shape

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Empty(
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
    ) : ChannelListUiState
    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
    ) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

sealed interface ChannelListNavigation {
    data class ToThread(val conversationId: String) : ChannelListNavigation
}

class ChannelListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChannelListUiState> = run {
        val channelsFlow = repository.observeConversations(ConversationFilter.Channels)
        val discussionsFlow = repository.observeConversations(ConversationFilter.Discussions)
        val recentIdsFlow = discussionsFlow
            .map { it.take(RECENT_DISCUSSIONS_LIMIT).map(Conversation::id) }
            .distinctUntilChanged()
        val lastMessagesFlow: Flow<Map<String, Message>> = recentIdsFlow.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id ->
                repository.observeLastMessage(id).map { msg -> id to msg }
            }) { pairs ->
                pairs.mapNotNull { (id, msg) -> msg?.let { id to it } }.toMap()
            }
        }
        combine(channelsFlow, discussionsFlow, lastMessagesFlow) { channels, discussions, lastMessages ->
            val recent = discussions.take(RECENT_DISCUSSIONS_LIMIT)
            val count = discussions.size
            if (channels.isEmpty()) ChannelListUiState.Empty(recent, count, lastMessages)
            else ChannelListUiState.Loaded(channels, recent, count, lastMessages)
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
    }

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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val RECENT_DISCUSSIONS_LIMIT = 3
    }
}
```

`recentDiscussions` is bounded at `RECENT_DISCUSSIONS_LIMIT = 3` (the count of preview rows the section can host). `recentDiscussionsCount` is the full (uncapped) `discussions.size` — what the See-all label renders. Behavioural invariant: `recentDiscussions.size <= 3`, `recentDiscussions.size <= recentDiscussionsCount`, and when `recentDiscussionsCount == 0` both fields are empty/zero (so the section's "render only when non-empty" predicate is `recentDiscussions.isEmpty()`).

`recentDiscussionLastMessages` (#161) is a parallel map keyed by the conversation id of an entry in `recentDiscussions`. **Absent key = no last message for that conversation** — `null` is filtered out via `mapNotNull` in the projection, so call sites that read `state.recentDiscussionLastMessages[conversation.id]` get unambiguous `Message?` semantics by construction (no `null`-vs-missing ambiguity). The default value `= emptyMap()` on both `Empty` and `Loaded` is load-bearing: it lets every existing `Empty(...)` / `Loaded(...)` construction site (previews, screen tests, VM tests, future callers) omit the new argument without a 14-site mechanical cascade. Architect picked the parallel-map shape over a composite `RecentDiscussion(conversation, lastMessage)` because the AC explicitly forbids consumer-signature changes — `RecentDiscussionsSection(discussions: List<Conversation>, …)` stays as-is; sibling #162 reads per-row from the map without touching `recentDiscussions`. Map invariant: `recentDiscussionLastMessages.keys ⊆ recentDiscussions.map { it.id }` (the `flatMapLatest` over `recentIdsFlow` guarantees no stale entries leak through when the recent slice shifts).

`ChannelListUiState` and `ChannelListNavigation` are top-level (siblings of the VM), not nested inside the VM — call sites import `ChannelListUiState.Loaded` / `ChannelListNavigation.ToThread` directly rather than `ChannelListViewModel.UiState.Loaded`. The screen-name prefix carries enough disambiguation.

## How it works

### Cold-to-hot via `stateIn`

`observeConversations` is a cold `Flow`; `stateIn` shares one upstream collection across all subscribers and exposes a `StateFlow` with a current `.value`. The configuration:

- **`scope = viewModelScope`** — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by the VM.
- **`started = SharingStarted.WhileSubscribed(5_000)`** — the upstream starts collecting when `state` gets its first subscriber and stops 5s after the last subscriber unsubscribes. Configuration changes (rotation) re-subscribe within ms, so the 5s grace prevents collector churn; navigating away for >5s truly disposes the upstream.
- **`initialValue = Loading`** — guarantees the AC's "Initial emitted state is `Loading` before the repository flow produces a value". `stateIn` returns synchronously and the first real upstream value replaces `Loading` on the next dispatch turn.

### State projection

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
   combine(channels, discussions, lastMessages) ──► derive ─┬─ recent = discussions.take(3)
                                                            ├─ count  = discussions.size
                                                            └─ lastMessages (as-is)
                                                  ──► Empty(recent, count, lastMessages)       (channels.isEmpty())
                                                  └─► Loaded(ch, recent, count, lastMessages)  (otherwise)
                 ─── catch ─► Error(message)
                 ─── stateIn ─► initial = Loading
```

`combine` (#26) replaced the single-flow `map` shape; #161 widened the combine arity from 2 to 3 by adding `lastMessagesFlow` (derived from `discussionsFlow`, not from `ConversationRepository` directly). The three upstreams are independent subscriptions on the same `ConversationRepository`; all are cold flows, all inherit the `WhileSubscribed(5_000)` shared lifetime. `combine` waits for *every* side to emit before the first downstream emission — this is what keeps the `Loading` initial frame observable until the data layer has fully answered (see "Edge cases / limitations"). `.catch { }` sits between `combine { }` and `.stateIn(...)` because `stateIn` is a terminal operator (returns `StateFlow`, not `Flow`) — `.catch` after it doesn't compile. One `catch` block covers all three upstream throwables; a throw on *any* side (channels, discussions, or any per-row `observeLastMessage`) collapses the whole pipeline to `Error`.

#69 collapsed the previous `.map { it.size }` projection on the discussions flow: both `recent` and `count` now derive from a single emission inside the `combine` body. Re-introducing a `.map` would force either a third upstream subscription (two collections of the same cold flow, two `WhileSubscribed` lifetimes) or a `combine`-of-`combine`. Single-emission `combine` body → multiple derived values is the right shape; reach for the un-mapped form whenever a second derived value lands.

### Per-row `observeLastMessage` via `flatMapLatest(distinctUntilChanged(idsFlow))` (#161)

`lastMessagesFlow` is the project's first bounded-fan-out subscription pattern — per-element side data sourced from a bounded parent slice via dynamic `combine` of per-row cold flows. Three load-bearing operators:

- **`recentIdsFlow = discussionsFlow.map { it.take(3).map(Conversation::id) }.distinctUntilChanged()`** — the `distinctUntilChanged` is non-optional. Without it, every discussions re-emission (even ones that don't change the recent-3) would tear down and rebuild the inner `combine`, churning per-row subscriptions. The id-list-equality check is what makes the steady-state subscription stable.
- **`flatMapLatest { ids -> … }`** — the `flatMapLatest` (vs `flatMapConcat` or `flatMapMerge`) is non-optional. When the recent slice shifts (e.g. a new discussion takes the top-3 spot, pushing another out), the obsolete per-row subscriptions must be cancelled before the new ones start — otherwise stale entries leak into the next emission. `flatMapLatest`'s "cancel previous inner flow on new outer emission" semantics are exactly this guarantee.
- **Inner `combine(ids.map { id -> observeLastMessage(id).map { msg -> id to msg } }) { pairs -> pairs.mapNotNull { (id, msg) -> msg?.let { id to it } }.toMap() }`** — dynamic-arity `combine` over the per-row cold flows. `mapNotNull` filters `null` last-messages so the resulting `Map<String, Message>` has no nullable values (the "absent key = null last message" contract); call sites that read `map[id]` get unambiguous `Message?` semantics. The empty-ids branch short-circuits to `flowOf(emptyMap())` because `combine(emptyList())` is not well-defined.

`flatMapLatest` carries `@OptIn(ExperimentalCoroutinesApi::class)` in `kotlinx-coroutines 1.10.2`; the opt-in is scoped to the `state` property (not file-level) so the experimental dependency stays visible at the use site. The recent slice is capped at `RECENT_DISCUSSIONS_LIMIT = 3`, so the inner `combine` arity is bounded — a batched `observeLastMessages(ids: Set<String>)` repository method was considered and rejected as Phase 1 over-engineering. If the bound grows or per-row data becomes a network call, revisit the batched API at that point.

### Default-value affordance on the new field

The `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default on both `Empty` and `Loaded` (#161) is what kept the ticket data-layer-only. Without it, every existing construction site would have needed an explicit `recentDiscussionLastMessages = emptyMap()` argument: 6 in `ChannelListScreen.kt` previews, 6 in `ChannelListViewModelTest`, 2 in `ChannelListScreenTest`. With it, none of those 14 sites change — the VM emits with `emptyMap()` (because the test `stubRepo.observeLastMessage` returns `flowOf(null)` and the projection's `mapNotNull` filters it out) and data-class equality matches expected literals that omit the new arg. Generalised rule: when widening a UiState data class with a field that has a sensible "no data" identity (`emptyMap()`, `emptyList()`, `null` for nullable references), provide a default — the only reason to omit it is when forcing every site to acknowledge the new field is the actual goal.

### `.take(3)` against an upstream-sorted flow — no VM-side resort

`FakeConversationRepository.observeConversations(Discussions)` already emits `.sortedByDescending { it.lastUsedAt }`. The VM does `discussions.take(RECENT_DISCUSSIONS_LIMIT)` with no `.sortedByDescending` of its own — re-sorting would have duplicated the contract and risked the two sorts disagreeing if the upstream definition drifts (e.g. Phase 4's `RemoteConversationRepository` returning server-ordered records). The `recentDiscussions_orderingFollowsUpstream` test pins this: the VM slices but does not re-sort. If the repository contract changes its sort key, that test is the first to fail.

### Error semantics

`Error(message)` is terminal in the underlying flow: once `catch` consumes the exception and emits an `Error`, the upstream has already errored and produces no further values. Recovery requires a fresh subscription — currently only achievable by leaving and re-entering the screen (subscribers drop to zero, `WhileSubscribed(5_000)` expires, a new subscription starts a fresh collection). A `retry()` event-driven path lands with the screen-side "Retry" button.

Message extraction: `e.message` verbatim when non-null and non-blank, else the literal fallback `"Failed to load channels."`. Never `e.toString()` or `e::class.simpleName` — exception class names are implementation detail and read badly in user-facing UI.

### One-shot navigation via `Channel.BUFFERED` (#22)

`navigationChannel` is a `Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)` exposed via `receiveAsFlow()`. `Channel` (not `SharedFlow`) because a nav event must fire exactly once even if the collector momentarily isn't attached — `MutableSharedFlow` would either replay (re-navigating on rotation) or drop (losing in-flight taps). `BUFFERED` because the burst is one event at a time; no back-pressure semantics are wanted.

Consumer is `MainActivity`'s `composable(Routes.CHANNEL_LIST)` block, which runs `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` to translate `ChannelListNavigation.ToThread` into `navController.navigate("conversation_thread/${event.conversationId}")`. The `vm` key restarts the collector exactly when the VM identity changes (per `NavBackStackEntry` scope). Cancellation is atomic with the launching coroutine: if the user navigates away mid-`createDiscussion`, `viewModelScope` cancels both the suspend and the pending `send`.

### `onEvent` reducer (#22)

Single dispatched arm: `CreateDiscussionTapped -> viewModelScope.launch { … }`. Calls `repository.createDiscussion()` with no argument (workspace defaults to `null`), then `navigationChannel.send(ToThread(conversation.id))`. No `try/catch` — the fake never throws; speculative defense forbidden by project principles. Phase 4's `RemoteConversationRepository` adds the catch and the error UI together.

The `is RowTapped, SettingsTapped, RecentDiscussionsTapped -> Unit` arm exists for compiler exhaustiveness; `MainActivity` never forwards those into `onEvent`, but if the dispatch convention shifts later the VM tolerates them defensively (no-op). `RecentDiscussionsTapped` joined the arm in #26 — same rationale (pure navigation, no VM-side side effect).

## Wiring

Koin binding (already registered in [`AppModule`](./dependency-injection.md)):

```kotlin
viewModel { ChannelListViewModel(get()) }
```

The constructor parameter type is `ConversationRepository` (the interface, not the `FakeConversationRepository` impl). Koin's `single { FakeConversationRepository() } bind ConversationRepository::class` makes `get()` resolve to the bound implementation; Phase 4's remote impl drops in behind the same bind line with no VM change.

Composable resolution at the `composable(Routes.CHANNEL_LIST) { ... }` destination (landed in #46):

```kotlin
val vm = koinViewModel<ChannelListViewModel>()
val state by vm.state.collectAsStateWithLifecycle()
```

`koinViewModel<…>()` (from `org.koin.androidx.compose`) routes through `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ auto-wires to the current `NavBackStackEntry` — so the VM is scoped to the back-stack entry, surviving configuration changes and tearing down on pop. `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose` (added to the catalog in #46), distinct from the `-ktx` artifacts already on the classpath.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-viewmodel-ktx` (catalog: `androidx-lifecycle-viewmodel-ktx`, same `lifecycleRuntimeKtx` version-ref as `lifecycle-runtime-ktx`). Required for `viewModelScope` at lifecycle 2.6.1 — `koin-androidx-compose` brings `lifecycle-viewmodel-compose` transitively, but that artifact depends on the non-`-ktx` base which lacks `viewModelScope`.
- **Test dependencies:** `org.jetbrains.kotlinx:kotlinx-coroutines-test` on `testImplementation` (catalog: `kotlinx-coroutines-test`, reuses `kotlinxCoroutines` version-ref). Required for `Dispatchers.setMain(...)` — `stateIn(viewModelScope, ...)` dispatches on `Dispatchers.Main.immediate` which is uninitialised in plain JVM unit tests.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`. JUnit 4, matching the existing test-class style. Seventeen tests (six existing reshaped + two added in #69 to pin the `recentDiscussions` surface + one added in #161 to pin the `recentDiscussionLastMessages` end-to-end path through the real fake):

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
16. `recentDiscussionLastMessages_populatedFromFake_endToEnd` (#161) — drives the real `FakeConversationRepository` through the real VM (no stub). Constructs `ChannelListViewModel(FakeConversationRepository())`, launches a collector, `advanceUntilIdle()`, asserts the resulting state is `Loaded`, then asserts `loaded.recentDiscussionLastMessages["seed-discussion-a"]` is non-null with `timestamp == Instant.parse("2026-05-11T14:00:00Z")` (the AC's "last message present" case) and `"seed-discussion-b" !in loaded.recentDiscussionLastMessages` (the "no messages → absent from the map" case, not "present with null value"). The single test covers both AC clauses through the real fake — the data-shape edits (`observeLastMessage` projection + `seed-discussion-a` history + `recentDiscussionLastMessages` field + `flatMapLatest` derivation) are all exercised on the integration path, not just at unit boundaries.

Test infrastructure conventions established here (carry forward to future ViewModel tests):

- `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain()` in `@After`. Class-level `@OptIn(ExperimentalCoroutinesApi::class)`.
- **State-derivation tests** use hand-rolled `object : ConversationRepository { ... TODO("not used") }` stubs. The `stubRepo` helper now takes two `Flow<List<Conversation>>` parameters keyed by filter (`stubRepo(channels, discussions)`) — the VM subscribes twice with different filters; one shared flow no longer reflects the production behaviour. Old single-source call sites become `stubRepo(source, emptyFlow())` (or vice-versa) so the test explicitly states which filter it's exercising. `MutableSharedFlow<List<Conversation>>(replay = 0)` for each source — gives the test control over emission timing. `MutableStateFlow` would force an initial value at construction and defeat the Loading-observation window. Since #161, `stubRepo`'s `observeLastMessage` override returns `flowOf(null)` (not `TODO`) — the `mapNotNull` in the VM projection filters the `null` out, so every stub-backed test produces `recentDiscussionLastMessages = emptyMap()` and existing `Empty/Loaded(...)` literals (which omit the field) match by default. `erroringRepo`'s override stays `TODO("not used")` because those tests terminate at the `Error` state before any per-row subscription kicks in. Two-tier rule: stubs whose VMs *can* exercise the new method get a real default return; stubs whose VMs cannot reach it stay `TODO`.
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

- Ticket notes: [`../codebase/45.md`](../codebase/45.md), [`../codebase/22.md`](../codebase/22.md) (FAB → `onEvent` reducer + one-shot nav channel), [`../codebase/26.md`](../codebase/26.md) (`combine` of Channels + Discussions flows, widened `Loaded` / `Empty` to carry `recentDiscussionsCount`, `RecentDiscussionsTapped` event, `stubRepo` helper reshape), [`../codebase/69.md`](../codebase/69.md) (widened `Loaded` / `Empty` with `recentDiscussions: List<Conversation>`; collapsed the `.map { it.size }` projection into a single `combine` emission; two new tests pin `.take(3)` slicing and upstream-ordering contract), [`../codebase/161.md`](../codebase/161.md) (third combined input `lastMessagesFlow` derived via `flatMapLatest(distinctUntilChanged(recentIdsFlow))` + per-row `observeLastMessage` `combine`; `recentDiscussionLastMessages: Map<String, Message> = emptyMap()` default-arg affordance lets every existing construction site stay untouched)
- Specs: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/26-recent-discussions-pill.md`, `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/161-recent-discussion-last-message-uistate.md`
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam — `createDiscussion(workspace = null)` is the call the `onEvent` reducer makes; `observeConversations(Discussions)` is the second subscription added in #26 and the same emission #69 re-uses for both `recent` and `count`; `observeLastMessage(id)` from #161 is the per-row subscription the `flatMapLatest` derivation rides), [data model](./data-model.md) (`Conversation` payload, `Message` payload for `recentDiscussionLastMessages`), [dependency injection](./dependency-injection.md) (Koin wiring)
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent`, `collectAsStateWithLifecycle()`, and the screen-level loading/empty/error/loaded composables; #22 added the FAB and `LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` at the destination; #26 added the pill and consumed `recentDiscussionsCount` off `UiState`; #69 replaced the pill with the inline section and now consumes both `recentDiscussions` and `recentDiscussionsCount`; #161 added the third UiState field but no UI consumer — sibling #162 is the consumer slice that reads `state.recentDiscussionLastMessages[conversation.id]` inside `RecentDiscussionsSection`), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + reducer arm), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class`; adds error handling around `createDiscussion()` and the loading affordance deferred in #22).
