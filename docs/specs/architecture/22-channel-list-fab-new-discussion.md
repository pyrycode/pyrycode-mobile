# Spec — Channel list FAB: new discussion (#22)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` (full, 129 lines) — primary edit target. Existing Scaffold has `topBar` only; we add the `floatingActionButton` slot and a third `ChannelListEvent` variant. Note line 32-35 (current sealed interface) and lines 44-78 (Scaffold body + `when (state)` dispatch).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` (full, 48 lines) — secondary edit target. Constructor's `repository` is currently a non-property parameter consumed only in the `state` initializer; this ticket promotes it to `private val` so a new `onEvent` method can call `repository.createDiscussion(...)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:24` — confirms `suspend fun createDiscussion(workspace: String? = null): Conversation`. Default-null param; the FAB call site passes nothing.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:76-100` — the fake's `createDiscussion` implementation: mints a UUID, sets `isPromoted = false`, returns the new `Conversation`. The unit test will exercise this fake directly (it lives in `main`, not `test`, so it's reachable from `app/src/test/`).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:110-124` — `composable(Routes.ChannelList)` block. The `when (event)` lambda gets a new arm for `CreateDiscussionTapped` (delegates to the VM), and a `LaunchedEffect` collecting `vm.navigationEvents` is added alongside `vm.state`.
- `app/src/main/res/values/strings.xml` — only contains `app_name` and `cd_open_settings`. Add `cd_new_discussion` here; do not introduce a new resource file.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` (full, 134 lines) — pattern reference. The existing helper `stubRepo` returns `TODO("not used")` for `createDiscussion`; new tests should NOT extend that stub. Use `FakeConversationRepository()` directly — it's the production fake and gives a faithful integration of `observeConversations` + `createDiscussion`.
- `docs/specs/architecture/21-channel-list-top-app-bar.md` — sibling spec; the Scaffold + sealed-events pattern established there extends here.

## Context

`ChannelListScreen` (#18, #21) renders a list of channels with an app bar. The list has no entry point for creating a new conversation. This ticket adds the standard Material 3 affordance — a floating action button — that creates an *unpromoted* `Conversation` (a "discussion" in the project's vocabulary) and navigates to its placeholder thread.

The interaction is the first case where the ViewModel performs a *suspend-shaped side effect* (calling `repository.createDiscussion`) and then emits a *one-shot navigation event* whose target depends on the suspend's return value. Existing events (`RowTapped`, `SettingsTapped`) navigate without VM involvement — `MainActivity` handles them directly. This ticket introduces a one-shot navigation-event channel from the VM to the screen-owner; it becomes the model for later flows (promotion, archive, workspace change).

## Design

### 1. `ChannelListEvent` — new variant

In `ChannelListScreen.kt`:

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
}
```

### 2. `ChannelListNavigation` — one-shot nav events from the VM

Define a small sealed interface alongside `ChannelListUiState` in `ChannelListViewModel.kt` (top-level, same file — the project does not have a separate `navigation/` subpackage and one-event-source-per-screen is fine to keep co-located):

```kotlin
sealed interface ChannelListNavigation {
    data class ToThread(val conversationId: String) : ChannelListNavigation
}
```

A sealed interface (not a bare `data class`) is used so future variants (e.g. `ToPromotionDialog`, `ToSettings`) extend without rippling. Cost is one extra line today.

### 3. `ChannelListViewModel` — promote `repository`, add `onEvent`, expose `navigationEvents`

Changes to `ChannelListViewModel`:

- Promote `repository` to a property: `class ChannelListViewModel(private val repository: ConversationRepository) : ViewModel()`.
- Add a private `Channel<ChannelListNavigation>` and expose it as a cold `Flow` via `receiveAsFlow()`:
  - Field: `private val navigationChannel = Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)`
  - Public: `val navigationEvents: Flow<ChannelListNavigation> = navigationChannel.receiveAsFlow()`
- Add `fun onEvent(event: ChannelListEvent)`. The VM only handles `CreateDiscussionTapped`; `RowTapped` and `SettingsTapped` continue to be routed by `MainActivity` (no behavior change for them). For `CreateDiscussionTapped`, launch in `viewModelScope`, call `repository.createDiscussion()` with no argument (workspace defaults to `null`), then `navigationChannel.send(ChannelListNavigation.ToThread(newConversation.id))`.

**Why `Channel` and not `SharedFlow`.** A nav event must fire exactly once even if the collector momentarily isn't attached (configuration change, transient recomposition). `Channel` with `receiveAsFlow()` gives at-most-once delivery with buffering; `MutableSharedFlow` would either replay (wrong — we'd renavigate on rotation) or drop (wrong — we'd lose an in-flight tap). `Channel.BUFFERED` avoids back-pressure for the burst-of-one case.

**Why dispatch only the new variant in the VM.** Keeping `RowTapped`/`SettingsTapped` routed through `MainActivity`'s `when` matches the existing pattern (#21) and avoids a wider rewrite. The VM intercepts only the event that needs to do work; the rest pass through untouched.

Behavior sketch (not the implementation — developer writes the body):

```kotlin
fun onEvent(event: ChannelListEvent) {
    when (event) {
        ChannelListEvent.CreateDiscussionTapped -> viewModelScope.launch {
            val conversation = repository.createDiscussion()
            navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))
        }
        is ChannelListEvent.RowTapped, ChannelListEvent.SettingsTapped -> Unit
    }
}
```

The `Unit` arm exists so the `when` is exhaustive and the compiler enforces handling future variants. `RowTapped`/`SettingsTapped` are still passed in by `ChannelListScreen`'s caller (MainActivity unwraps them before they reach the VM — see §5); the VM tolerates them defensively in case the dispatch convention shifts later.

### 4. `ChannelListScreen` — add the FAB to the Scaffold

In the existing `Scaffold(...)`:

- Add a new slot `floatingActionButton = { ... }` between `topBar` and the body lambda.
- Render the FAB only when the screen is "interactive" — i.e. `state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty`. In Loading and Error states, omit the FAB. This satisfies the AC literally ("at minimum the Loaded and Empty states") and avoids the "tap FAB while still spinning" race against the initial `observeConversations` emission.
- The FAB itself: Material 3 `FloatingActionButton` (compact — *not* `ExtendedFloatingActionButton`; the icon-only form matches the standard Android "new" affordance and doesn't require a label string).
  - `onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) }`
  - `Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_discussion))`
- `floatingActionButtonPosition` left at the default (`FabPosition.End`).

The `onEvent` lambda passed in from `MainActivity` already handles `CreateDiscussionTapped` (see §5) — no contract change to the composable's signature.

### 5. `MainActivity.kt` — collect navigation events, route the new variant

In the `composable(Routes.ChannelList)` block:

- Pull `val nav by rememberUpdatedState(navController)` is not needed — `navController` is already stable within the NavHost lambda. Just capture it.
- Add a `LaunchedEffect(vm)` that collects `vm.navigationEvents`:

```kotlin
LaunchedEffect(vm) {
    vm.navigationEvents.collect { event ->
        when (event) {
            is ChannelListNavigation.ToThread ->
                navController.navigate("conversation_thread/${event.conversationId}")
        }
    }
}
```

- Extend the existing `when (event)` in `onEvent` to forward `CreateDiscussionTapped` to the VM:

```kotlin
when (event) {
    is ChannelListEvent.RowTapped ->
        navController.navigate("conversation_thread/${event.conversationId}")
    ChannelListEvent.SettingsTapped ->
        navController.navigate(Routes.Settings)
    ChannelListEvent.CreateDiscussionTapped ->
        vm.onEvent(event)
}
```

The `LaunchedEffect` key is `vm` (stable across recomposition, fresh per ViewModelStoreOwner) so the collector restarts only if the VM identity changes. The composable function for the route is destroyed/recreated on navigation away/back; `LaunchedEffect` cancels on disposal, which is correct — events emitted while away would be lost, but the only emitter is `onEvent(CreateDiscussionTapped)` which can only fire when the screen is composed.

### 6. New string resource

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="cd_new_discussion">New discussion</string>
```

`cd_` prefix marks it as a content-description string (matches `cd_open_settings`).

### 7. Preview

No new preview is required. The existing `ChannelListScreenLoadedPreview` will pick up the FAB automatically because the FAB is rendered in the `Loaded` state. The developer should visually verify by running the preview pane.

## State + concurrency model

- **`state: StateFlow<ChannelListUiState>`** — unchanged. Still derived from `repository.observeConversations(ConversationFilter.Channels)` via `stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`.
- **`navigationEvents: Flow<ChannelListNavigation>`** — backed by `Channel<ChannelListNavigation>(Channel.BUFFERED)`. Cold from the consumer's perspective (one collector at a time — `MainActivity`'s `LaunchedEffect`). Buffered so a tap that fires while the screen is mid-recomposition isn't dropped.
- **Dispatcher** — `repository.createDiscussion()` is a `suspend` function; `viewModelScope` defaults to `Dispatchers.Main.immediate`. The fake's implementation is non-blocking (`MutableStateFlow.update`), so no dispatcher hop is needed. The Phase 4 remote implementation may need to inject `Dispatchers.IO` for the network call — that's a Phase 4 concern; do not pre-thread a dispatcher parameter through the VM today.
- **Cancellation** — if the user navigates away mid-`createDiscussion`, `viewModelScope` is cancelled (ViewModel is cleared when the route leaves the back stack). The Channel send is wrapped in the same coroutine, so cancellation aborts both atomically. No partial state — the repository's `createDiscussion` either completes (and the new conversation is observable) or doesn't.
- **Re-tap race** — two rapid taps create two discussions. The AC ("single tap creates exactly one new discussion") is per-tap; it does not require debounce. If duplicate-prevention becomes a real-world need (the fake is fast; humans are slow), it's a separate ticket.

## Error handling

Out of scope for this ticket per the issue body: "Error / loading affordance for the create call itself (the fake repo can't fail; revisit when the real repo lands in Phase 4)."

Implementation note: do NOT wrap the `repository.createDiscussion()` call in a `try/catch`. The fake never throws on this path. Adding a catch for a failure mode that cannot occur is exactly the kind of speculative defense the project's principles forbid. When Phase 4 introduces a real `RemoteConversationRepository` whose `createDiscussion` can fail, that ticket adds the error state and the catch together.

## Recomposition correctness

- `Icons.Default.Add` is a stable `ImageVector` singleton — safe to pass to `Icon` directly.
- The `onClick` lambda for `FloatingActionButton` captures `onEvent`, which is a stable `(ChannelListEvent) -> Unit` parameter — the lambda itself is stable, no `remember` needed.
- The conditional `floatingActionButton = if (state is Loaded || state is Empty) { ... } else { ... }` would not compile — the slot is a `@Composable () -> Unit`, not nullable. Pattern instead: always pass a composable lambda, and inside it do the `when (state)` to render either the `FloatingActionButton` or nothing (`Unit`):
  ```kotlin
  floatingActionButton = {
      if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty) {
          FloatingActionButton(onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) }) {
              Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_discussion))
          }
      }
  }
  ```
- `state` is read inside the FAB slot's composable lambda — recomposition driven by `collectAsStateWithLifecycle` will recompute the slot when state transitions Loading→Loaded, which is correct (FAB appears as soon as data is available).

## Testing strategy

### Unit tests (`./gradlew test`) — additions to `ChannelListViewModelTest.kt`

Use `FakeConversationRepository()` (the production fake, reachable from `main`) for the create-flow tests. The existing `stubRepo` helper is for state-derivation tests and should be left alone.

Test scenarios (bullet points — developer writes the function bodies):

- **`createDiscussionTapped_createsOneUnpromotedConversation`** — instantiate `FakeConversationRepository()`, snapshot the `Discussions` list (`repository.observeConversations(ConversationFilter.Discussions).first()`), call `vm.onEvent(ChannelListEvent.CreateDiscussionTapped)`, `advanceUntilIdle()`, re-snapshot, assert the new list size equals the original size + 1, and that the new element has `isPromoted == false`.
- **`createDiscussionTapped_emitsToThreadNavigationWithCreatedId`** — instantiate the VM with `FakeConversationRepository()`. Launch a collector on `vm.navigationEvents.toList(...)` (or use `vm.navigationEvents.first()` inside `async`/`runTest`), trigger `vm.onEvent(ChannelListEvent.CreateDiscussionTapped)`, `advanceUntilIdle()`, assert the collected event is a `ChannelListNavigation.ToThread` whose `conversationId` matches the id of the newly created discussion (lookup: the only `Discussions` row whose id is not in the pre-snapshot).
- **No test needed for `RowTapped`/`SettingsTapped` reaching `onEvent`** — those variants are not dispatched into the VM by `MainActivity`; the `Unit` arm exists purely for compiler exhaustiveness.

Existing tests (`initialState_isLoading`, `loaded_whenSourceEmitsNonEmpty`, `empty_whenSourceEmitsEmptyList`, `error_whenSourceFlowThrows`, `error_messageIsNonBlank_whenExceptionMessageIsNull`) must continue to pass. None of them touch `createDiscussion` or `navigationEvents`; the only change they may observe is that the constructor parameter is now a property — non-breaking.

### Instrumented tests (`./gradlew connectedAndroidTest`) — none

This ticket does not introduce a `ComposeTestRule`-based UI test. AC only requires unit-test coverage of the VM flow.

### Manual verification

The developer should run `./gradlew assembleDebug` and visually confirm in the preview pane (or on a running emulator) that:

- The FAB appears in the bottom-right of the channel list (Loaded state).
- The FAB also appears in the Empty state (can be exercised by temporarily seeding `FakeConversationRepository` with `Channels` filter returning an empty list, or by trusting the conditional logic — the architect-side verification of "Loaded + Empty branch covered" is sufficient).
- Tapping the FAB navigates to a `conversation_thread/<uuid>` placeholder route.

## Open questions

None. All decisions are settled above.

## Out of scope (do not touch)

- Long-press → workspace picker. The issue body defers this to "Phase 2 — Workspace picker is part of `ConversationThread` design".
- Error/loading affordance for the create call (deferred to Phase 4 per issue).
- Refactoring `RowTapped`/`SettingsTapped` to flow through `vm.onEvent`. Tempting for consistency, but it's scope creep — those events have no VM-side side effect today.
- Introducing a `navigation/` subpackage or a top-level `NavigationEvent` hierarchy. Keep `ChannelListNavigation` co-located with `ChannelListViewModel` until a second screen needs the same pattern.
- Adding a debounce / single-flight guard on `CreateDiscussionTapped`. No observed failure mode justifies it today.
