# DiscussionListViewModel

Sibling of [`ChannelListViewModel`](channel-list-viewmodel.md) for the unpromoted (discussion) tier. Exposes `observeConversations(ConversationFilter.Discussions)` as a hot `StateFlow<DiscussionListUiState>` consumed by [`DiscussionListScreen`](discussion-list-screen.md) (#24), a stateless `(state, onEvent)` composable mounted at the `discussions` route.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `DiscussionListViewModel.kt`.

## What it does

Observes the discussion slice of the conversation repository **combined with** a private `MutableStateFlow<PendingPromotion?>` (#78), projects each emission into a `DiscussionListUiState` variant (`Loading` initially, then `Empty` or `Loaded(discussions, pendingPromotion)` depending on the emitted list, `Error(message)` if the upstream throws), and exposes the result as a `StateFlow` via the same `WhileSubscribed(5_000)` cold-to-hot pattern as the channel VM.

`fun onEvent(event: DiscussionListEvent)` handles five arms: `RowTapped(id) → viewModelScope.launch { navigationChannel.send(ToThread(id)) }` (so the unit test can assert the nav target — see "Dual nav wiring" below), `SaveAsChannelRequested(id) → openPromotionDialog(id)` (#78 — opens the confirmation dialog by setting `pendingPromotion`), `PromoteConfirmed → confirmPromotion()` (#78 — clears `pendingPromotion`, then `viewModelScope.launch { repository.promote(...) }`), `PromoteCancelled → pendingPromotion.value = null` (#78 — also fired by scrim taps and back-press via M3 `AlertDialog`'s default `onDismissRequest` routing), and `BackTapped → Unit` (the destination handles back at the composable side via `navController.popBackStack()`; the no-op arm keeps the `when` exhaustive and documents intent).

## Shape

```kotlin
sealed interface DiscussionListUiState {
    data object Loading : DiscussionListUiState
    data object Empty : DiscussionListUiState
    data class Loaded(
        val discussions: List<Conversation>,
        val pendingPromotion: PendingPromotion? = null,    // #78
    ) : DiscussionListUiState
    data class Error(val message: String) : DiscussionListUiState
}

data class PendingPromotion(                                // #78
    val conversationId: String,
    val sourceName: String?,
)

sealed interface DiscussionListNavigation {
    data class ToThread(val conversationId: String) : DiscussionListNavigation
}

class DiscussionListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    private val pendingPromotion = MutableStateFlow<PendingPromotion?>(null)

    val state: StateFlow<DiscussionListUiState> =
        combine(
            repository.observeConversations(ConversationFilter.Discussions),
            pendingPromotion,
        ) { discussions, pending ->
            if (discussions.isEmpty()) {
                // Drop stale pending state if the list collapsed while a dialog was open.
                if (pending != null) pendingPromotion.value = null
                DiscussionListUiState.Empty
            } else {
                DiscussionListUiState.Loaded(discussions, pending)
            }
        }.catch { e ->
            val raw = e.message
            emit(DiscussionListUiState.Error(
                if (raw.isNullOrBlank()) "Failed to load discussions." else raw
            ))
        }.stateIn(
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
            is DiscussionListEvent.SaveAsChannelRequested -> openPromotionDialog(event.conversationId)
            DiscussionListEvent.PromoteConfirmed -> confirmPromotion()
            DiscussionListEvent.PromoteCancelled -> pendingPromotion.value = null
            DiscussionListEvent.BackTapped -> Unit
        }
    }

    private fun openPromotionDialog(conversationId: String) {
        val current = state.value as? DiscussionListUiState.Loaded ?: return
        val match = current.discussions.firstOrNull { it.id == conversationId } ?: return
        pendingPromotion.value = PendingPromotion(conversationId, match.name)
    }

    private fun confirmPromotion() {
        val snapshot = pendingPromotion.value ?: return
        pendingPromotion.value = null                       // clear first → deterministic re-entry guard
        viewModelScope.launch {
            repository.promote(
                conversationId = snapshot.conversationId,
                name = derivedChannelName(snapshot.sourceName),
                workspace = null,
            )
        }
    }

    private companion object { const val STOP_TIMEOUT_MILLIS = 5_000L }
}

internal fun derivedChannelName(sourceName: String?): String =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled channel"

internal fun displayLabel(sourceName: String?): String =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled discussion"
```

`DiscussionListUiState`, `PendingPromotion`, `DiscussionListNavigation`, and `DiscussionListEvent` (defined in `DiscussionListScreen.kt`) are all top-level — call sites import the variants directly. The screen-name prefix carries enough disambiguation, matching the channel-list convention. `PendingPromotion` lives alongside `DiscussionListUiState` (not on `Loaded` as a nested type) so the `DiscussionListScreenTest` fixtures can construct it without naming the enclosing variant.

The two file-scope helpers (`derivedChannelName` / `displayLabel`) are `internal` so the test source set can unit-test them as plain functions if a future ticket adds direct coverage; today they're exercised transitively via the VM and the composable.

`STOP_TIMEOUT_MILLIS = 5_000L` is duplicated from the channel VM by design — not extracted to a shared constant. The duplication is cheap and the abstraction is premature; the project CLAUDE.md's "don't refactor adjacent code while you're there" rule applies here.

## How it works

### Cold-to-hot projection

Same lifecycle as [`ChannelListViewModel`](channel-list-viewmodel.md): `stateIn(viewModelScope, WhileSubscribed(5_000), Loading)` shares one upstream collection across subscribers, starts on first subscriber, stops 5s after the last unsubscribes. Configuration changes (rotation) re-subscribe within milliseconds; the 5s grace prevents churn. The `pendingPromotion` `MutableStateFlow` is preserved across that grace window, so a dialog open at rotation time re-appears after the rebuild.

Since #78 the upstream pipeline is `combine(observeConversations, pendingPromotion) → catch → stateIn` rather than the prior `.map → .catch → .stateIn`. `combine` does not emit until *both* upstreams have produced — `pendingPromotion` has the initial `null`, so the seed gap is purely the first repository emission; `stateIn(initialValue = Loading)` covers it. The error fallback is `"Failed to load discussions."` (not `"Failed to load channels."`). Otherwise the error path is structurally identical to the channel VM.

### Combine semantics and the empty-while-pending edge (#78)

The combine projection has four observable arms:

| upstream emits | pendingPromotion | resulting state |
| --- | --- | --- |
| `emptyList()` | any | `Empty` (`pendingPromotion` cleared if non-null) |
| non-empty | `null` | `Loaded(discussions, pendingPromotion = null)` |
| non-empty | non-null | `Loaded(discussions, pendingPromotion = pp)` |
| (initial) | (initial) | `Loading` (until first combine emission) |

If the upstream collapses to `emptyList()` while a dialog is open (extreme edge — the only discussion got archived externally), the dialog disappears and the `pendingPromotion` flow is *also* cleared inline from the combine body, so a later non-empty emission cannot revive a stale dialog. Side-effecting inside `combine` is awkward but correct here — the alternative `onEach { … }` would cost an extra coroutine and put the invalidation further from the projection.

### Dual nav wiring (#24)

`RowTapped` emits on the VM-side `navigationChannel` *and* is also handled at the destination block via `navController.navigate("conversation_thread/${event.conversationId}")`. Both wires fire on every tap. The production path is the composable-side `navController.navigate(...)` — that's what the user sees. The VM-side path exists because AC #4 demands a unit-testable navigation event:

> Tapping a row emits a navigation event whose target is `conversation_thread/{id}` for the tapped discussion's id (asserted via the ViewModel's navigation flow in a unit test)

A single-wire shape (composable-only) would require either a `TestNavHostController` setup (deferred since #8) or brittle `navController` interaction assertions to satisfy the AC. Two wires, both emit, the test observes the VM one. Don't optimize the redundancy away — if a future ticket consolidates navigation through the VM, the composable-side branch comes out then.

### `BackTapped` is intentionally a no-op in the VM

`navController.popBackStack()` runs at the destination block. The `BackTapped -> Unit` arm exists for `when` exhaustiveness and to document that the event is intentionally surfaced as part of the sealed `DiscussionListEvent` (future analytics or VM-side back-press logic plugs in here). Don't delete the arm.

### Promotion confirmation flow (#78)

The Phase 0 `Unit` stub on `SaveAsChannelRequested` (#25) was wired in #78 to a minimal Material 3 confirmation dialog. The dialog *is* the promotion flow — no separate name-entry or workspace-picker UI; the richer flow is a follow-up ticket when its Figma lands.

Three behaviour seams sit between the four event variants:

- **`openPromotionDialog(conversationId)`** — reads `state.value`. If it is `Loaded` and the id is present in `discussions`, snapshots that discussion's `name` into `pendingPromotion.value = PendingPromotion(conversationId, name)`. If the state isn't `Loaded` or the id is missing (stale long-press after the upstream dropped the row), silently no-ops. **Does not re-query the repository** — the ticket spec forbids re-fetching for the display label, and stale state is preferable to an inconsistent dialog.
- **`confirmPromotion()`** — snapshots `pendingPromotion.value` into a local; if non-null, sets `pendingPromotion.value = null` *first*, then `viewModelScope.launch { repository.promote(snapshot.conversationId, derivedChannelName(snapshot.sourceName), workspace = null) }`. Clearing before the launch makes the dismissal deterministic (no stale dialog during the suspend) and guards against a double-tap firing two promote calls (the second `PromoteConfirmed` arrives to a `null` snapshot and short-circuits).
- **`PromoteCancelled`** — one-line `pendingPromotion.value = null`. Also fired by scrim taps and back-press, because the composable wires `onDismissRequest = { onEvent(PromoteCancelled) }` on the M3 `AlertDialog` (which routes both gestures through the same slot by default).

The `repository.promote(conversationId, name, workspace)` call is fire-and-forget. No await, no `isLoading` UiState, no error surface — the ticket explicitly scopes to a confirmation dialog with no failure handling. The `FakeConversationRepository.promote` is in-memory and never fails for a valid id; stale-id failures would surface as uncaught coroutine exceptions, acceptable for Phase 0 (the `openPromotionDialog` guard already filters most of them at request time). The next ticket that needs error-surfacing introduces a project-wide pattern.

The promote completion is observed by the next `observeConversations` emission dropping the now-promoted row from the discussion list (it's now a channel). The dialog has already dismissed by then; no spinner is needed.

### Name derivation — two helpers, two consumers (#78)

```kotlin
internal fun derivedChannelName(sourceName: String?) =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled channel"

internal fun displayLabel(sourceName: String?) =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled discussion"
```

Two distinct fallbacks on purpose:

- **`displayLabel`** matches `ConversationRow.displayName`'s `"Untitled discussion"` fallback so the dialog body reads the same identifier the user just long-pressed on the row.
- **`derivedChannelName`** is what gets persisted as `Conversation.name` post-promote — `"Untitled channel"` because the conversation is now in the channels tier and `"Untitled discussion"` would be a misleading channel name.

For named discussions, both return the same `sourceName` verbatim. The discussion list passes `lastMessage = null` to `ConversationRow` (no message-preview surface in Phase 0), so `Conversation.name` is the only "preview" available — using it as both the dialog identifier and the post-promote name is the simplest faithful reading of the ticket body.

## Wiring

Koin binding (registered in [`AppModule`](dependency-injection.md)):

```kotlin
viewModel { DiscussionListViewModel(get()) }
```

Constructor parameter is the `ConversationRepository` interface; the singleton bound via `single { FakeConversationRepository() } bind ConversationRepository::class` resolves through `get()`. Phase 4's `RemoteConversationRepository` drops in behind the same bind line.

Composable resolution at the `composable(Routes.DISCUSSION_LIST) { ... }` destination:

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

`app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt`. JUnit 4. Two repo doubles share the file: the existing `stubRepo` (returns the source flow, `TODO("not used")` for the rest) for tests that don't need to observe `promote`, and a new `RecordingRepo` (#78) that captures each `promote` call into a `mutableListOf<PromoteCall>` so confirm-arm tests can assert the `(id, name, workspace)` triple.

Cases (#78 added the promotion-arm coverage on top of the original four state-derivation tests):

1. **`initialState_isLoading`** — reads `vm.state.value` before any subscriber attaches.
2. **`loaded_passesThroughRepositoryOrder`** — `source.emit(listOf(d1, d2, d3))`; asserts `Loaded(listOf(d1, d2, d3))` in source order. Compares positionally to `Loaded(discussions)` — the `pendingPromotion = null` default on the variant means this assertion still type-checks unchanged from before #78.
3. **`empty_whenSourceEmitsEmptyList`** — asserts `Empty`.
4. **`rowTapped_emitsToThreadNavigation`** — `async { vm.navigationEvents.first() }` launched *before* `vm.onEvent(RowTapped("disc-7"))`.
5. **`error_whenSourceFlowThrows`** / **`error_messageIsNonBlank_whenExceptionMessageIsNull`** — both throwing-flow paths.
6. **`saveAsChannelRequested_setsPendingPromotion_whenIdIsInLoadedDiscussions`** (#78) — emits two discussions, dispatches `SaveAsChannelRequested("d1")`, asserts `state.value.pendingPromotion == PendingPromotion("d1", d1.name)`.
7. **`saveAsChannelRequested_isNoOp_whenIdIsNotInDiscussions`** (#78) — `SaveAsChannelRequested("unknown")`; asserts `pendingPromotion == null`.
8. **`promoteCancelled_clearsPendingPromotion`** (#78) — sets up pending first, dispatches `PromoteCancelled`, asserts `pendingPromotion == null` *and* the `RecordingRepo` captured no `promote` call.
9. **`promoteConfirmed_callsRepositoryPromote_withDerivedName_andClearsPending`** (#78) — three sub-cases against the `RecordingRepo`: `sourceName = "foo"` → `promote("d1", "foo", null)`; `sourceName = null` → `promote("d1", "Untitled channel", null)`; `sourceName = "   "` → `promote("d1", "Untitled channel", null)`. Each sub-case is its own `@Test` method.
10. **`promoteConfirmed_isNoOp_whenNoPendingPromotion`** (#78) — fresh VM, dispatch `PromoteConfirmed` with no prior `SaveAsChannelRequested`; assert `RecordingRepo.promoteCalls` is empty.
11. **`pendingPromotion_cleared_whenUpstreamEmitsEmpty`** (#78) — set up pending, then `source.emit(emptyList())`; assert state is `Empty`, then `source.emit(listOf(d2))` and assert the resulting `Loaded` has `pendingPromotion = null`. Pins the inline reset inside the combine body.

Test infrastructure conventions are unchanged from the channel VM tests — `Dispatchers.setMain(UnconfinedTestDispatcher())`, hand-rolled repository doubles with `MutableSharedFlow<List<Conversation>>(replay = 0)`, `launch { vm.state.collect { } }` kept alive across emissions to hold `WhileSubscribed` hot, `advanceUntilIdle()` between trigger and assertion.

`androidTest` coverage since #78 — `DiscussionListScreenTest.kt` covers the dialog overlay (appears / does-not-appear, confirm and cancel button → event emission, body string interpolation with `sourceName` present and null). See [`discussion-list-screen.md`](discussion-list-screen.md) for the test conventions; the screen-side tests cover the composable contract, the VM-side tests cover the state machine, and AC #6(d) (scrim/back dismiss without promoting) falls out by construction from the M3 `AlertDialog`'s `onDismissRequest` default.

## Edge cases / limitations

- **No `CreateDiscussionTapped` arm.** Discussions are created from the channel-list FAB (#22) and surfaced into this screen via the repository's filtered flow on the next emission.
- **`SaveAsChannelRequested` opens a confirmation dialog** (#78). The richer promotion flow (user-entered name + workspace radio group) is a follow-up ticket pinned to a future Figma node. Today the dialog supplies an auto-derived name (`derivedChannelName(Conversation.name)`) and `workspace = null` — the discussion's existing name becomes the channel's name verbatim, falling back to `"Untitled channel"` when blank.
- **`BackTapped` is a VM no-op.** Routed at the composable side; the VM arm is purely for compiler exhaustiveness. Don't add VM state mutation here speculatively.
- **No error handling around `repository.promote`** (#78). The fake never fails for a valid id; stale-id failures would surface as uncaught coroutine exceptions and are acceptable for Phase 0. Adding a try/catch + error UiState would expand scope and pre-build infrastructure the rest of the app lacks. The next ticket that needs error-surfacing introduces the project-wide pattern.
- **No `isLoading` UiState during promote** (#78). The dialog clears synchronously *before* the `viewModelScope.launch`; the promote completes in the background; the next `observeConversations` emission drops the now-promoted row. No spinner, no in-flight flag.
- **No `flowOn(Dispatchers.IO)`.** Same dispatcher policy as the channel VM — upstream inherits `Dispatchers.Main.immediate` from `viewModelScope`; the fake's projection is CPU map manipulation.
- **No retry / refresh method.** Cold-flow re-collection on resubscription is the existing retry surface. Phase 4 adds a designed `Error` screen with explicit retry; the current centered-text error is a placeholder.
- **No logging in `catch`.** Phase 4 introduces the logging strategy alongside the network layer.

## Related

- Ticket notes: [`../codebase/24.md`](../codebase/24.md), [`../codebase/25.md`](../codebase/25.md), [`../codebase/78.md`](../codebase/78.md)
- Specs: `docs/specs/architecture/24-discussion-list-drilldown-screen.md`, `docs/specs/architecture/25-save-as-channel-affordances.md`, `docs/specs/architecture/78-promote-discussion-confirmation-dialog.md`
- Sibling: [ChannelListViewModel](channel-list-viewmodel.md) — structural clone source; see it for the `.map / .catch / .stateIn` pattern, the `Channel<…>(BUFFERED) → receiveAsFlow()` one-shot navigation seam, and the test-infrastructure conventions
- Upstream: [Conversation repository](conversation-repository.md) (the `observeConversations(ConversationFilter.Discussions)` projection — `FakeConversationRepository` projection stamps `isSleeping` on every emission, so the discussion list inherits the sleeping-dot affordance without VM work), [data model](data-model.md) (`Conversation`), [dependency injection](dependency-injection.md) (Koin wiring)
- Downstream: [DiscussionListScreen](discussion-list-screen.md), richer promotion flow (name entry + workspace radios — pins to Figma when design lands; integration point is extending `PendingPromotion` with the additional fields and replacing `PromotionConfirmationDialog`'s body), Phase 4 (`RemoteConversationRepository` replaces the fake behind the same bind line; `repository.promote(...)` does its own dispatcher switching internally)
