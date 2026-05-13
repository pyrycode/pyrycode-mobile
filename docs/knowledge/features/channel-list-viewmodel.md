# ChannelListViewModel

First `ViewModel` in the codebase. Exposes `observeConversations(ConversationFilter.Channels)` as a hot `StateFlow<ChannelListUiState>` consumed by [`ChannelListScreen`](channel-list-screen.md) (#46), a stateless `(state, onEvent)` composable.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListViewModel.kt`.

## What it does

Observes the persistent-channel slice of the conversation repository, projects each emission into a `ChannelListUiState` variant, and exposes the result as a `StateFlow`. Emits `Loading` initially (before the upstream produces a value), then `Empty` or `Loaded(channels)` depending on the emitted list, and `Error(message)` if the upstream flow throws.

Read-only at this layer — no VM-side reducer. `ChannelListEvent` (with the single `RowTapped(conversationId)` variant) lives next to the screen and is translated directly into `navController.navigate(...)` at the destination block (#46). A VM-side `fun onEvent(event: ChannelListEvent)` reducer will land with the first non-navigation event (retry, archive, etc.); when it does, the sealed type moves from `ChannelListScreen.kt` into this file to colocate with its consumer.

## Shape

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data object Empty : ChannelListUiState
    data class Loaded(val channels: List<Conversation>) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

class ChannelListViewModel(
    repository: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<ChannelListUiState> =
        repository.observeConversations(ConversationFilter.Channels)
            .map<List<Conversation>, ChannelListUiState> { channels ->
                if (channels.isEmpty()) ChannelListUiState.Empty
                else ChannelListUiState.Loaded(channels)
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

    private companion object { const val STOP_TIMEOUT_MILLIS = 5_000L }
}
```

`ChannelListUiState` is top-level (sibling of the VM), not nested inside the VM — call sites import `ChannelListUiState.Loaded` directly rather than `ChannelListViewModel.UiState.Loaded`. The screen-name prefix carries enough disambiguation.

## How it works

### Cold-to-hot via `stateIn`

`observeConversations` is a cold `Flow`; `stateIn` shares one upstream collection across all subscribers and exposes a `StateFlow` with a current `.value`. The configuration:

- **`scope = viewModelScope`** — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by the VM.
- **`started = SharingStarted.WhileSubscribed(5_000)`** — the upstream starts collecting when `state` gets its first subscriber and stops 5s after the last subscriber unsubscribes. Configuration changes (rotation) re-subscribe within ms, so the 5s grace prevents collector churn; navigating away for >5s truly disposes the upstream.
- **`initialValue = Loading`** — guarantees the AC's "Initial emitted state is `Loading` before the repository flow produces a value". `stateIn` returns synchronously and the first real upstream value replaces `Loading` on the next dispatch turn.

### State projection

```
List<Conversation> ─── map ──► Empty (when list is empty)
                            └► Loaded(channels)  (otherwise)
                ─── catch ─► Error(message)
                ─── stateIn ─► initial = Loading
```

`.catch { }` sits between `.map { }` and `.stateIn(...)` because `stateIn` is a terminal operator (returns `StateFlow`, not `Flow`) — `.catch` after it doesn't compile. One `catch` block covers both upstream throwables and (hypothetical) map exceptions.

### Error semantics

`Error(message)` is terminal in the underlying flow: once `catch` consumes the exception and emits an `Error`, the upstream has already errored and produces no further values. Recovery requires a fresh subscription — currently only achievable by leaving and re-entering the screen (subscribers drop to zero, `WhileSubscribed(5_000)` expires, a new subscription starts a fresh collection). A `retry()` event-driven path lands with the screen-side "Retry" button.

Message extraction: `e.message` verbatim when non-null and non-blank, else the literal fallback `"Failed to load channels."`. Never `e.toString()` or `e::class.simpleName` — exception class names are implementation detail and read badly in user-facing UI.

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

`app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`. JUnit 4, matching the existing test-class style. Five tests:

1. `initialState_isLoading` — reads `vm.state.value` before any subscriber attaches; relies on `stateIn`'s `initialValue` being immediately visible without a hot collector.
2. `loaded_whenSourceEmitsNonEmpty` — launched collector, `source.emit(listOf(sampleChannel))`, asserts `Loaded(listOf(sampleChannel))`.
3. `empty_whenSourceEmitsEmptyList` — launched collector, `source.emit(emptyList())`, asserts `Empty`.
4. `error_whenSourceFlowThrows` — flow that throws `RuntimeException("network down")`; asserts `Error("network down")`.
5. `error_messageIsNonBlank_whenExceptionMessageIsNull` — flow that throws `RuntimeException(null)`; asserts the fallback string path is non-blank.

Test infrastructure conventions established here (carry forward to future ViewModel tests):

- `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain()` in `@After`. Class-level `@OptIn(ExperimentalCoroutinesApi::class)`.
- Hand-rolled `object : ConversationRepository { ... TODO("not used") }` stubs inline at the bottom of the test file. No `mockk`, no shared `TestConversationRepository` base, no test-fixtures module.
- `MutableSharedFlow<List<Conversation>>(replay = 0)` for the source — gives the test control over emission timing. `MutableStateFlow` would force an initial value at construction and defeat the Loading-observation window.
- Every non-initial-state test launches `launch { vm.state.collect { } }` to keep `WhileSubscribed` hot before emitting; `advanceUntilIdle()` between launching the collector + emitting, and between emitting + asserting, to drain the in-flight queue.

## Edge cases / limitations

- **`Loading` is observable only because the test uses `replay = 0`.** The actual `FakeConversationRepository` projects synchronously from a populated `MutableStateFlow`, so production runtime never observes a real `Loading` frame — the seed records arrive in the same dispatch turn that `stateIn` emits the `initialValue`. The screen will still see `Loading` initially because `collectAsStateWithLifecycle()` snapshots `state.value` at composition time before the next emission lands; the architectural commitment to a `Loading` variant remains correct for the Phase 4 remote impl, where the round-trip is observably non-zero.
- **No `init { }` block, no `refresh()`, no `retry()` method.** Cold-flow re-collection on resubscription is the existing retry surface. Explicit retry lands with the UI control that needs it.
- **No VM-side `Event` / `onEvent` surface.** `ChannelListEvent` (sealed, single `RowTapped` variant) was introduced in #46 next to the screen, since tap-navigation translates directly into `navController.navigate(...)` at the destination block. A VM-side reducer lands when the first non-navigation event (retry, archive, etc.) arrives.
- **No `flowOn(Dispatchers.IO)`.** Upstream `observeConversations` inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation; Phase 4's remote impl decides its own dispatcher internally. The VM stays dispatcher-agnostic.
- **No logging.** No `Log.e(...)` in `catch`. Phase 4 introduces a logging strategy alongside the network layer; until then, the `Error` state is the diagnostic surface.

## Related

- Ticket notes: [`../codebase/45.md`](../codebase/45.md)
- Spec: `docs/specs/architecture/45-channel-list-viewmodel-uistate-data-path.md`
- Upstream: [Conversation repository](./conversation-repository.md) (data-layer seam), [data model](./data-model.md) (`Conversation` payload), [dependency injection](./dependency-injection.md) (Koin wiring)
- Downstream: [ChannelListScreen](channel-list-screen.md) (#46 — first UI consumer; introduced `ChannelListEvent`, `collectAsStateWithLifecycle()`, and the screen-level loading/empty/error/loaded composables), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` and moves the sealed type into this file alongside a VM-side reducer), Phase 4 (`ConversationRepositoryImpl` replaces `FakeConversationRepository` behind the same `bind ConversationRepository::class` — VM unchanged).
