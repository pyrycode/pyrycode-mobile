# DiscussionListViewModel

Sibling of [`ChannelListViewModel`](channel-list-viewmodel.md) for the unpromoted (discussion) tier. Exposes `observeConversations(ConversationFilter.Discussions)` as a hot `StateFlow<DiscussionListUiState>` consumed by [`DiscussionListScreen`](discussion-list-screen.md) (#24), a stateless `(state, onEvent)` composable mounted at the `discussions` route.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `DiscussionListViewModel.kt`.

## What it does

Observes the discussion slice of the conversation repository, projects each emission into a `DiscussionListUiState` variant (`Loading` initially, then `Empty` or `Loaded(discussions)` depending on the emitted list, `Error(message)` if the upstream throws), and exposes the result as a `StateFlow` via the same `WhileSubscribed(5_000)` cold-to-hot pattern as the channel VM.

`fun onEvent(event: DiscussionListEvent)` handles two arms: `RowTapped(id) → viewModelScope.launch { navigationChannel.send(ToThread(id)) }` (so the unit test can assert the nav target — see "Dual nav wiring" below), and `BackTapped → Unit` (the destination handles back at the composable side via `navController.popBackStack()`; the no-op arm keeps the `when` exhaustive and documents intent).

## Shape

```kotlin
sealed interface DiscussionListUiState {
    data object Loading : DiscussionListUiState
    data object Empty : DiscussionListUiState
    data class Loaded(val discussions: List<Conversation>) : DiscussionListUiState
    data class Error(val message: String) : DiscussionListUiState
}

sealed interface DiscussionListNavigation {
    data class ToThread(val conversationId: String) : DiscussionListNavigation
}

class DiscussionListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<DiscussionListUiState> =
        repository.observeConversations(ConversationFilter.Discussions)
            .map<List<Conversation>, DiscussionListUiState> { discussions ->
                if (discussions.isEmpty()) DiscussionListUiState.Empty
                else DiscussionListUiState.Loaded(discussions)
            }
            .catch { e ->
                val raw = e.message
                emit(DiscussionListUiState.Error(
                    if (raw.isNullOrBlank()) "Failed to load discussions." else raw
                ))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = DiscussionListUiState.Loading,
            )

    private val navigationChannel = Channel<DiscussionListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<DiscussionListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: DiscussionListEvent) {
        when (event) {
            is DiscussionListEvent.RowTapped -> viewModelScope.launch {
                navigationChannel.send(DiscussionListNavigation.ToThread(event.conversationId))
            }
            DiscussionListEvent.BackTapped -> Unit
        }
    }

    private companion object { const val STOP_TIMEOUT_MILLIS = 5_000L }
}
```

`DiscussionListUiState`, `DiscussionListNavigation`, and `DiscussionListEvent` (defined in `DiscussionListScreen.kt`) are all top-level — call sites import the variants directly. The screen-name prefix carries enough disambiguation, matching the channel-list convention.

`STOP_TIMEOUT_MILLIS = 5_000L` is duplicated from the channel VM by design — not extracted to a shared constant. The duplication is cheap and the abstraction is premature; the project CLAUDE.md's "don't refactor adjacent code while you're there" rule applies here.

## How it works

### Cold-to-hot projection

Identical lifecycle to [`ChannelListViewModel`](channel-list-viewmodel.md): `observeConversations` is a cold flow; `stateIn(viewModelScope, WhileSubscribed(5_000), Loading)` shares one upstream collection across subscribers, starts on first subscriber, stops 5s after the last unsubscribes. Configuration changes (rotation) re-subscribe within milliseconds; the 5s grace prevents churn. See the channel VM doc for the full discussion of `.map → .catch → .stateIn` ordering and the rationale for the fallback string.

The error fallback is `"Failed to load discussions."` (not `"Failed to load channels."`). Otherwise the error path is structurally identical.

### Dual nav wiring (#24)

`RowTapped` emits on the VM-side `navigationChannel` *and* is also handled at the destination block via `navController.navigate("conversation_thread/${event.conversationId}")`. Both wires fire on every tap. The production path is the composable-side `navController.navigate(...)` — that's what the user sees. The VM-side path exists because AC #4 demands a unit-testable navigation event:

> Tapping a row emits a navigation event whose target is `conversation_thread/{id}` for the tapped discussion's id (asserted via the ViewModel's navigation flow in a unit test)

A single-wire shape (composable-only) would require either a `TestNavHostController` setup (deferred since #8) or brittle `navController` interaction assertions to satisfy the AC. Two wires, both emit, the test observes the VM one. Don't optimize the redundancy away — if a future ticket consolidates navigation through the VM, the composable-side branch comes out then.

### `BackTapped` is intentionally a no-op in the VM

`navController.popBackStack()` runs at the destination block. The `BackTapped -> Unit` arm exists for `when` exhaustiveness and to document that the event is intentionally surfaced as part of the sealed `DiscussionListEvent` (future analytics or VM-side back-press logic plugs in here). Don't delete the arm.

## Wiring

Koin binding (registered in [`AppModule`](dependency-injection.md)):

```kotlin
viewModel { DiscussionListViewModel(get()) }
```

Constructor parameter is the `ConversationRepository` interface; the singleton bound via `single { FakeConversationRepository() } bind ConversationRepository::class` resolves through `get()`. Phase 4's `RemoteConversationRepository` drops in behind the same bind line.

Composable resolution at the `composable(Routes.DiscussionList) { ... }` destination:

```kotlin
val vm = koinViewModel<DiscussionListViewModel>()
val state by vm.state.collectAsStateWithLifecycle()
LaunchedEffect(vm) {
    vm.navigationEvents.collect { event ->
        when (event) {
            is DiscussionListNavigation.ToThread ->
                navController.navigate("conversation_thread/${event.conversationId}")
        }
    }
}
```

`koinViewModel<…>()` scopes the VM to the current `NavBackStackEntry` (Compose Navigation 2.9+ auto-wiring). `LaunchedEffect(vm)` re-keys on VM identity — exactly when the user enters this back-stack entry — so the collector restarts cleanly on each new instance and cancels on pop.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt`. JUnit 4, six tests, mirroring the channel-list test conventions verbatim:

1. **`initialState_isLoading`** — reads `vm.state.value` before any subscriber attaches.
2. **`loaded_passesThroughRepositoryOrder`** — `source.emit(listOf(d1, d2, d3))`; asserts `Loaded(listOf(d1, d2, d3))` in source order. The VM trusts what the repo emits; the `ConversationFilter.Discussions` filter contract is the repository's responsibility.
3. **`empty_whenSourceEmitsEmptyList`** — `source.emit(emptyList())`; asserts `Empty` (the variant, not `Loaded(emptyList())`).
4. **`rowTapped_emitsToThreadNavigation`** — `async { vm.navigationEvents.first() }` launched *before* `vm.onEvent(RowTapped("disc-7"))`; asserts the captured event is `ToThread("disc-7")`.
5. **`error_whenSourceFlowThrows`** — flow throws `RuntimeException("network down")`; asserts `Error("network down")`.
6. **`error_messageIsNonBlank_whenExceptionMessageIsNull`** — `RuntimeException(null)`; asserts the fallback path is non-blank.

Test infrastructure conventions are unchanged from the channel VM tests — `Dispatchers.setMain(UnconfinedTestDispatcher())`, hand-rolled `stubRepo` with `MutableSharedFlow<List<Conversation>>(replay = 0)` and `TODO("not used")` on the other repository methods, `launch { vm.state.collect { } }` kept alive across emissions to hold `WhileSubscribed` hot, `advanceUntilIdle()` between trigger and assertion.

No `androidTest` coverage — the AC's visual contract lives in the `@Preview` composables in `DiscussionListScreen.kt`.

## Edge cases / limitations

- **No `CreateDiscussionTapped` arm.** Discussions are created from the channel-list FAB (#22) and surfaced into this screen via the repository's filtered flow on the next emission. The discussion list is read-only in Phase 0; the long-press "Save as channel…" action (#1) and the promotion dialog (Phase 2) add the first write paths.
- **`BackTapped` is a VM no-op.** Routed at the composable side; the VM arm is purely for compiler exhaustiveness. Don't add VM state mutation here speculatively.
- **No `flowOn(Dispatchers.IO)`.** Same dispatcher policy as the channel VM — upstream inherits `Dispatchers.Main.immediate` from `viewModelScope`; the fake's projection is CPU map manipulation.
- **No retry / refresh method.** Cold-flow re-collection on resubscription is the existing retry surface. Phase 4 adds a designed `Error` screen with explicit retry; the current centered-text error is a placeholder.
- **No logging in `catch`.** Phase 4 introduces the logging strategy alongside the network layer.

## Related

- Ticket notes: [`../codebase/24.md`](../codebase/24.md)
- Spec: `docs/specs/architecture/24-discussion-list-drilldown-screen.md`
- Sibling: [ChannelListViewModel](channel-list-viewmodel.md) — structural clone source; see it for the `.map / .catch / .stateIn` pattern, the `Channel<…>(BUFFERED) → receiveAsFlow()` one-shot navigation seam, and the test-infrastructure conventions
- Upstream: [Conversation repository](conversation-repository.md) (the `observeConversations(ConversationFilter.Discussions)` projection — `FakeConversationRepository` projection stamps `isSleeping` on every emission, so the discussion list inherits the sleeping-dot affordance without VM work), [data model](data-model.md) (`Conversation`), [dependency injection](dependency-injection.md) (Koin wiring)
- Downstream: [DiscussionListScreen](discussion-list-screen.md), Phase 2 promotion dialog (adds `PromoteRequested(id)` event arm — converts a discussion into a channel via the repository), Phase 4 (`RemoteConversationRepository` replaces the fake behind the same bind line)
