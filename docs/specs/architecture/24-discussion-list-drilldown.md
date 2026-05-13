# Spec — Discussion list drilldown screen (#24)

## Context

The Channel list (the app's main screen, #46) shows promoted conversations only. Unpromoted "discussions" — auto-named throwaway conversations — need their own drilldown screen so users can find the recent ones. Per `Plan.md`, the Channel list surfaces a "Recent discussions (N) →" pill that links here (the pill itself lands in #26; out of scope here). This ticket delivers the destination screen and its route so #26 can wire to it.

The screen is structurally a sibling of `ChannelListScreen`:
- Same data source (`ConversationRepository.observeConversations`) but with `ConversationFilter.Discussions`.
- Same row component (`ConversationRow`), rendered with visibly lower visual weight to signal the secondary tier.
- Same MVI shape (sealed `UiState`, sealed `Event`, `navigationEvents` channel).
- Difference: no FAB, no settings action — drilldown screen with a back button only.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — the entire file. Clone the shape verbatim: `sealed interface ChannelListUiState`, `sealed interface ChannelListNavigation`, the `state: StateFlow<…>` builder with `.map / .catch / .stateIn`, the `Channel<Navigation>` + `receiveAsFlow()` pattern, the `STOP_TIMEOUT_MILLIS` companion. The discussions VM differs only in (a) filter argument, (b) names, (c) no `CreateDiscussionTapped` branch — see Design below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — the entire file. The discussions screen mirrors the `Scaffold + LazyColumn + when(state)` skeleton; drop the FAB and the settings action; replace the top-app-bar title and add a back-navigation icon.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` — the entire file. The discussions VM test reuses the `stubRepo` / `erroringRepo` helpers' pattern. Crucially, this file already exercises the empty-vs-loaded transition the AC asks for; copy the pattern. The createDiscussion-specific tests do not apply.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:36-94` — the `ConversationRow` composable signature and how `condenseWorkspace` returns `null` for `DefaultScratchCwd`. The "workspace label is absent on default-scratch-cwd discussions" AC is already satisfied by this code; the developer must verify (preview screenshot inspection) but must not modify `ConversationRow`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-44` — the repository interface and the `ConversationFilter` enum.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:73-148` — the `PyryNavHost` composable and the private `Routes` object. The discussions route lands here.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — the Koin module. A new `viewModel { DiscussionListViewModel(get()) }` entry lands here.
- `app/src/main/res/values/strings.xml` — pattern for screen-scoped strings (`channel_list_empty`, `cd_*`). Add the two new strings listed under Design.
- `CLAUDE.md` (project root) — "Stateless composables", "Sealed types for `UiState` and `Event`", "Test-first".

## Design

### New package contents

Both files live in `app/src/main/java/de/pyryco/mobile/ui/conversations/list/` next to their channel-list counterparts.

#### `DiscussionListViewModel.kt`

Defines three top-level types and the VM:

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
    val state: StateFlow<DiscussionListUiState> = …    // see "State + concurrency"
    val navigationEvents: Flow<DiscussionListNavigation> = …
    fun onEvent(event: DiscussionListEvent)
}
```

The `state` builder is structurally identical to `ChannelListViewModel.state` (`observeConversations(ConversationFilter.Discussions).map { … }.catch { … }.stateIn(…)`). The error fallback message is `"Failed to load discussions."` when `e.message` is null/blank; otherwise pass through `e.message`.

The companion's `STOP_TIMEOUT_MILLIS = 5_000L` is duplicated (don't extract — see Constraints).

#### `DiscussionListScreen.kt`

Defines one sealed Event interface and the composable:

```kotlin
sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data object BackTapped : DiscussionListEvent
}

@Composable
fun DiscussionListScreen(
    state: DiscussionListUiState,
    onEvent: (DiscussionListEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

`DiscussionListScreen` body:
- `Scaffold` with a `TopAppBar(title = { Text(stringResource(R.string.discussion_list_title)) }, navigationIcon = { IconButton onClick = onEvent(BackTapped) -> Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) })`.
- No FAB.
- `when (state)`: `Loading` → centered "Loading…"; `Empty` → centered string `R.string.discussion_list_empty`; `Error` → centered `"Couldn't load discussions: ${state.message}"`; `Loaded` → `LazyColumn` of `ConversationRow` items keyed by `conversation.id`.
- The `Loaded` branch passes `modifier = Modifier.alpha(0.65f)` to each `ConversationRow`. See "Visual de-emphasis" below.

`CenteredText` private helper: inline a copy (~5 lines) — do not extract a shared helper. Same approach as `ChannelListScreen`.

#### Visual de-emphasis (the AC mechanism)

Mechanism: **outer `Modifier.alpha(0.65f)` applied to each `ConversationRow` in the `LazyColumn` items lambda.** Single-line addition at the call site:

```kotlin
ConversationRow(
    conversation = discussion,
    lastMessage = null,
    onClick = { onEvent(DiscussionListEvent.RowTapped(discussion.id)) },
    modifier = Modifier.alpha(0.65f),
)
```

Rationale for choosing alpha over a `deEmphasized` parameter on `ConversationRow` or a surface tint:
- **No edit fan-out into `ConversationRow`.** A `deEmphasized` parameter would force every existing call site (`ChannelListScreen`, two existing `@Preview` composables in `ConversationRow.kt`) to pass `deEmphasized = false`. Alpha avoids that.
- **Survives ripple correctly.** Material 3 ripples render on top of the un-alpha'd surface inside `ListItem`; an outer alpha dims the content uniformly without breaking touch feedback. A surface-variant tint would require composing `ConversationRow` inside a `Surface(tonalElevation=…)` wrapper — more code.
- **Matches "secondary tier" reading.** Discussions are not disabled; they are de-emphasized. 0.65 reads as secondary, not greyed-out. Do not use values < 0.5 (looks disabled).

The side-by-side comparison `@Preview` (AC) is the visual contract. Build the preview with one `Column { … }` containing a channel `ConversationRow` (no alpha) and a discussion `ConversationRow` (with `Modifier.alpha(0.65f)`), each given the same fixture `lastMessage = null` for a fair comparison.

#### New strings (in `app/src/main/res/values/strings.xml`)

- `discussion_list_title` → `"Recent discussions"`
- `discussion_list_empty` → `"No discussions yet"`
- `cd_back` → `"Back"`

If `cd_back` already exists, reuse it.

### Navigation wiring (`MainActivity.kt`)

In the private `Routes` object, add `const val DiscussionList = "discussions"`. The ticket fixes the route string as `"discussions"`; do not change it.

In `PyryNavHost`, add a new `composable(Routes.DiscussionList)` block adjacent to the `Routes.ChannelList` block. Pattern mirrors the channel-list block:

- `val vm = koinViewModel<DiscussionListViewModel>()`
- `val state by vm.state.collectAsStateWithLifecycle()`
- `LaunchedEffect(vm) { vm.navigationEvents.collect { event -> when (event) { is DiscussionListNavigation.ToThread -> navController.navigate("conversation_thread/${event.conversationId}") } } }`
- `DiscussionListScreen(state = state, onEvent = { event -> when (event) { is DiscussionListEvent.RowTapped -> navController.navigate("conversation_thread/${event.conversationId}"); DiscussionListEvent.BackTapped -> navController.popBackStack() } })`

Important: `RowTapped` navigates **directly via `navController`** in `MainActivity` (same pattern as `ChannelList.RowTapped`); the VM does **not** emit a navigation event for taps. The VM's `navigationChannel` exists for the AC test only — see "Why a navigation channel if MainActivity handles taps directly?" in Open questions.

The discussions route is not yet linked from anywhere in the live app graph. That is intentional and explicitly stated in the ticket — #26 will add the pill on the channel list that calls `navController.navigate(Routes.DiscussionList)`. Verifying the route renders without crashing requires running the app and using `navController.navigate("discussions")` from a temporary trigger (or just trusting the `@Preview` + tests). The AC does not require a wired entry point.

### Koin (`AppModule.kt`)

Add one line:

```kotlin
viewModel { DiscussionListViewModel(get()) }
```

below the existing `ChannelListViewModel` registration.

## State + concurrency model

- Single `StateFlow<DiscussionListUiState>` built with `SharingStarted.WhileSubscribed(5_000)` — identical lifecycle to `ChannelListViewModel`. The repository flow is cold; `stateIn` makes it hot for the lifetime of one collector + 5s grace.
- `Channel<DiscussionListNavigation>(capacity = Channel.BUFFERED)` for navigation events. Required by the AC ("asserted via the ViewModel's navigation flow in a unit test") — see Testing.
- Dispatcher: default — `viewModelScope` runs on `Dispatchers.Main.immediate`; the cold `Flow` from the fake repo has no IO and does not need an explicit dispatcher switch.
- Shutdown: standard `viewModelScope` cancellation on `onCleared()`. The `WhileSubscribed(5_000)` lets the upstream cancel cleanly when the screen leaves composition.

The `onEvent` function:

```kotlin
fun onEvent(event: DiscussionListEvent) {
    when (event) {
        is DiscussionListEvent.RowTapped ->
            viewModelScope.launch {
                navigationChannel.send(DiscussionListNavigation.ToThread(event.conversationId))
            }
        DiscussionListEvent.BackTapped -> Unit
    }
}
```

`RowTapped` emits to the navigation channel so the unit test can assert the navigation target (AC: "Tapping a row emits a navigation event whose target is `conversation_thread/{id}`"). `MainActivity` also handles `RowTapped` directly via `navController.navigate(...)` — both paths are wired so the production path doesn't depend on the VM-side channel, but the VM-side channel still emits so the test can observe it.

## Error handling

Failure modes:
- **Repository flow throws** — the `.catch { e -> emit(DiscussionListUiState.Error(…)) }` branch fires. Message: `e.message` if non-blank, else `"Failed to load discussions."`. The UI shows a centered `"Couldn't load discussions: <message>"` string. Same shape as channel list.
- **Empty list** — `DiscussionListUiState.Empty` (not `Loaded(emptyList())`); the UI shows the centered empty-state string. This is an AC.
- **Conversion / parse errors** — n/a in Phase 0; the fake repo emits structured data. Phase 4 will revisit.

No retry, no banner, no dialog. The drilldown is read-only in Phase 0.

## Testing strategy

One new test file: `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt`.

Pattern: copy the structure of `ChannelListViewModelTest.kt` verbatim — same `@Before`/`@After` dispatcher setup, same `stubRepo` / `erroringRepo` helper shape (but parameterized differently — see below).

Test scenarios (each is one `@Test`):

1. **`initialState_isLoading`** — VM exposes `DiscussionListUiState.Loading` before the source emits.
2. **`loaded_containsOnlyUnpromoted_inSourceOrder`** (AC #2) — stub the repo so `observeConversations(ConversationFilter.Discussions)` returns a flow that emits `listOf(discussion1, discussion2, discussion3)` (all `isPromoted = false`); assert `vm.state.value == DiscussionListUiState.Loaded(listOf(discussion1, discussion2, discussion3))`. Note: the `ConversationFilter.Discussions` filter contract is the repo's responsibility — the VM trusts what it receives. To honor the AC wording ("given a fake repo emitting a mixed list of channels and discussions, the `Loaded` state contains only conversations with `isPromoted = false`"), use a `stubRepo` whose `observeConversations(filter)` returns the discussions-only sublist when `filter == ConversationFilter.Discussions`, and assert the VM passes through verbatim. **Do not test `FakeConversationRepository`'s filtering here** — that's already covered by the fake's own contract tests; this is a VM unit test, not a repo test.
3. **`empty_whenSourceEmitsEmptyList`** (AC #3) — emits `emptyList()`, asserts `DiscussionListUiState.Empty`.
4. **`rowTapped_emitsToThreadNavigation`** (AC #4) — VM receives `DiscussionListEvent.RowTapped("disc-7")`; collect the navigation channel via `async { vm.navigationEvents.first() }`; assert the event is `DiscussionListNavigation.ToThread("disc-7")`. Pattern: see the `createDiscussionTapped_emitsToThreadNavigationWithCreatedId` test for the `async` + `vm.navigationEvents.first()` pattern.
5. **`error_whenSourceFlowThrows`** — stub throws `RuntimeException("network down")`, assert state is `Error("network down")`. (Not in AC, but parity with channel list — write it.)
6. **`error_messageIsNonBlank_whenExceptionMessageIsNull`** — same as channel list. (Not in AC, but parity.)

Each `stubRepo` impl returns an in-memory `Flow` via `MutableSharedFlow` or `flowOf` and uses `TODO("not used")` for the remaining `ConversationRepository` methods (same shape as the existing test).

No instrumented (`androidTest`) coverage required by the AC. The `@Preview` composables provide visual-regression context without an instrumented test.

`@Preview` composables in `DiscussionListScreen.kt`:
- **`DiscussionListScreenLoadedPreview`** (AC #6) — Loaded state with at least two unpromoted `Conversation` fixtures with `cwd = DefaultScratchCwd` (so workspace label is absent — AC #7). Use a third item with `isSleeping = true` for parity with `ChannelListScreen`'s Loaded preview if helpful, but two minimum is required.
- **`DiscussionRowVsChannelRowPreview`** (AC #5) — a `Column` with a channel `ConversationRow` (no alpha, `isPromoted = true`, `name = "pyrycode-mobile"`, `cwd = "~/Workspace/Projects/pyrycode-mobile"`) above a discussion `ConversationRow` (`Modifier.alpha(0.65f)`, `isPromoted = false`, `name = null`, `cwd = DefaultScratchCwd`). Both wrapped in `PyrycodeMobileTheme`. This is the visual contract for the de-emphasis AC.
- Optional: `DiscussionListScreenEmptyPreview` for parity, not required.

## Constraints / non-goals

- **Do not modify `ConversationRow.kt`.** The de-emphasis mechanism lives at the call site. If the developer feels the urge to add a `deEmphasized: Boolean = false` parameter to `ConversationRow`, stop — that's the rejected path (see Visual de-emphasis rationale).
- **Do not extract a shared `CenteredText` helper** across `ChannelListScreen` and `DiscussionListScreen`. Both files keep their own ~5-line `private fun CenteredText` — refactoring adjacent code is explicitly out of scope per the project CLAUDE.md.
- **Do not extract `STOP_TIMEOUT_MILLIS` to a shared constant.** Same reason — the duplication is cheap and the abstraction is premature.
- **Do not wire the channel-list pill that links here.** That's #26.
- **Do not implement promotion, archive, or "Save as channel…" actions.** Out of scope per the ticket.
- **Single source of state** — only the VM holds `UiState`. The screen receives `(state, onEvent)`. No `remember { mutableStateOf… }` for list data inside the composable.

## Open questions

- **Why a navigation channel for `RowTapped` if `MainActivity` handles taps directly?** The VM emits because AC #4 explicitly demands a unit-testable navigation event. The production navigation path (composable-side `navController.navigate(...)`) is the simpler one and is what the user actually sees. The redundancy is intentional and small — both wires emit, the test observes the VM-side one. If a future ticket consolidates navigation through the VM, the composable-side branch comes out then. Do not optimize this away now.
- **Should `BackTapped` go through the VM at all?** It currently dispatches to `Unit` in the VM and is handled at the composable side via `navController.popBackStack()`. Keeping the event in the sealed type documents the intent for future tests/analytics; the VM branch is a no-op. Acceptable; do not flag.
