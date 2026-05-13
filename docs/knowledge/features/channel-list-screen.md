# ChannelListScreen

Stateless `(state, onEvent)` composable that renders a `LazyColumn` of `ConversationRow`s for the persistent-channel slice, dispatching `ChannelListEvent.RowTapped` on row clicks. First stateless UI consumer of a ViewModel in the project; introduces the sealed `Event` shape every screen will follow.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Renders the four `ChannelListUiState` variants from the VM (#45):

- **`Loading`** — centred `Text("Loading…")` placeholder.
- **`Empty`** — centred `Text("No channels yet")` placeholder.
- **`Error(message)`** — centred `Text("Couldn't load channels: $message")` placeholder.
- **`Loaded(channels)`** — `LazyColumn` of `ConversationRow`s, one per channel, keyed by `Conversation.id`.

Each row's `onClick` emits `ChannelListEvent.RowTapped(channel.id)` through the screen's `onEvent` lambda. The NavHost destination is the only place that lambda resolves to a concrete action (`navController.navigate("conversation_thread/$id")`); the screen itself is `NavController`-free.

## Shape

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
}

@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ChannelListUiState.Loading -> CenteredText("Loading…", modifier)
        ChannelListUiState.Empty -> CenteredText("No channels yet", modifier)
        is ChannelListUiState.Error -> CenteredText("Couldn't load channels: ${state.message}", modifier)
        is ChannelListUiState.Loaded -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(items = state.channels, key = { it.id }) { channel ->
                ConversationRow(
                    conversation = channel,
                    lastMessage = null,
                    onClick = { onEvent(ChannelListEvent.RowTapped(channel.id)) },
                )
            }
        }
    }
}
```

`ChannelListEvent` lives in `ChannelListScreen.kt`, not `ChannelListViewModel.kt`, because the screen — specifically `ConversationRow.onClick` — is the only producer this ticket. When a future ticket adds a VM-side reducer (`fun onEvent(event: ChannelListEvent)`), the type moves into the VM file to join its consumer.

## How it works

### Stateless `(state, onEvent)` contract

The screen takes a value-typed `state` and an `onEvent` lambda. No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. This is the canonical CLAUDE.md shape: hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`. Every subsequent screen in the project will follow the same shape.

### Exhaustive `when (state)`

Kotlin's sealed-interface exhaustiveness check enforces full variant coverage at compile time. No `else ->` branch — when Phase 4 adds a fifth `ChannelListUiState` variant (e.g. `Stale`), the compiler points at this `when`. That's the feature.

### `LazyColumn` with stable keys

`items(items = state.channels, key = { it.id })`. The `key` parameter is non-optional: without it, recomposition diffs list items by position rather than identity, and reorders (e.g. when a channel's `lastUsedAt` bumps it up the list) recompose every row below the moved item. `Conversation.id` is the stable identity per the data-model contract.

### `Loading` / `Empty` / `Error` placeholders

Each is a single `Text` centred inside `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)`. The private `CenteredText(text, modifier)` helper is the shared shape. AC permits a single `Text(...)`; centring is a basic layout decision, not a spinner or illustration. Real visuals land in #23.

### `ConversationRow.lastMessage = null`

`ChannelListUiState.Loaded` carries `List<Conversation>` only — no per-channel `Message?` projection. Passing `lastMessage = null` for every row is safe: `ConversationRow`'s supporting-content branch is `lastMessage?.let { ... }`, so the row collapses to its title + trailing relative-time stamp. A future ticket may extend `Loaded` to carry a `Map<String, Message?>` or a `data class ChannelRow(val conversation, val lastMessage)`.

## Wiring

At the `composable(Routes.ChannelList) { ... }` block in `MainActivity.PyryNavHost`:

```kotlin
composable(Routes.ChannelList) {
    val vm = koinViewModel<ChannelListViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ChannelListScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is ChannelListEvent.RowTapped ->
                    navController.navigate("conversation_thread/${event.conversationId}")
            }
        },
    )
}
```

Key points:

- **`koinViewModel<ChannelListViewModel>()` resolves against the existing Koin binding** (`viewModel { ChannelListViewModel(get()) }` from #45's `AppModule.kt`). Every `composable { ... }` block is a `NavBackStackEntry`-keyed scope; Compose Navigation 2.9+ auto-wires `LocalViewModelStoreOwner` to the current entry, so the bare call resolves to the entry-scoped store — the VM is created on first entry, retained across configuration changes, cleared on pop. No `viewModelStoreOwner = backStackEntry` argument needed.
- **`collectAsStateWithLifecycle()`, not `collectAsState()`.** The lifecycle-aware variant pauses upstream collection when the lifecycle drops below `STARTED`, which is what makes the VM's `WhileSubscribed(5_000)` actually save work when the screen is backgrounded.
- **Inline `when (event)` dispatch.** Single-variant today; the inline form keeps the navigation target visible at the destination. No `private fun onChannelListEvent(...)` extraction until event-translation grows non-trivial.
- **Concrete navigation target built inline:** `"conversation_thread/${event.conversationId}"`. No `Routes.conversationThread(id)` helper — matches the navigation feature doc's "build inline until a second caller appears" rule.
- **No `popUpTo` / `launchSingleTop`.** Default `navigate(route)` semantics are correct: tapping a channel pushes the thread onto the back stack; back-press returns to the channel list. The only exceptional case in the graph is the `scanner` → `channel_list` transition.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-runtime-compose` (catalog: `androidx-lifecycle-runtime-compose`, reuses the `lifecycleRuntimeKtx = "2.6.1"` version-ref). Required for `collectAsStateWithLifecycle` — not pulled transitively by `lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx`, or the `koin-androidx-compose` chain. Three lifecycle artifacts now on the classpath (`-ktx`, `-viewmodel-ktx`, `-runtime-compose`) — they share `LifecycleOwner` ABIs and must stay on the same version-ref.
- **Koin compose:** `org.koin.androidx.compose.koinViewModel` (already on the classpath since #32 via `koin-androidx-compose`). Distinct from `org.koin.compose.koinInject` (the `scanner` destination's `AppPreferences` resolver) — `koinViewModel` is the right call for `ViewModel`-typed bindings because it routes through `LocalViewModelStoreOwner`.

## Preview

Single `@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)` named `ChannelListScreenLoadedPreview`. Light theme, three inline `Conversation` instances varying `name`, `cwd` (one `DefaultScratchCwd` to verify the workspace-label-omission path through `ConversationRow`, two real workspaces), and `lastUsedAt` (12 minutes, 4 hours, 3 days ago). `widthDp = 412` matches the `ConversationRow.kt` previews from #17 for consistent device shape. `Loading` / `Empty` / `Error` are not previewed — throwaway placeholders being replaced in #23.

## Edge cases / limitations

- **No `Scaffold` / `TopAppBar` / `FAB`.** The screen is bare content — the NavHost destination renders it directly inside the outer `Scaffold` from `MainActivity`. TopAppBar lands in #21, FAB in #22.
- **No `rememberLazyListState` / scroll-position persistence.** `LazyColumn` auto-saves scroll position within a single composition; `rememberSaveable(saver = LazyListState.Saver)` is the next step when a real bug surfaces (e.g. scroll resets after returning from thread). Defer until observed.
- **No retry affordance on `Error`.** `ChannelListEvent` has no `RetryClicked` variant. Per #45's spec, recovery requires a fresh subscription (leave the screen, wait 5s for `WhileSubscribed` to expire, re-enter). A retry button lands with the ticket that commits to designed error UI.
- **No instrumented test.** AC requires only `./gradlew assembleDebug`. The natural unit test ("tap a row → `onEvent(RowTapped(channelId))` fires") needs `androidx-compose-ui-test-junit4` wired to `androidTestImplementation` and an `androidTest/` source set entry; deferred until the first ticket with multi-event logic to verify.
- **No `flowOn(Dispatchers.IO)` anywhere in the chain.** `collectAsStateWithLifecycle` collects on Main (via the destination's `LifecycleOwner`); the VM is Main-dispatched; the fake repo's projection is pure CPU map manipulation. Phase 4's remote impl decides its own dispatcher internally.
- **No empty-state distinction between "never had a channel" and "had channels, all deleted".** Both render `Empty` → `"No channels yet"`. The first ticket that needs the distinction extends `ChannelListUiState` (e.g. `Empty(reason: EmptyReason)`) or splits into a new variant.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md)
- Spec: `docs/specs/architecture/46-channellistscreen-lazycolumn-tap-nav.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (state producer), [ConversationRow](./conversation-row.md) (per-row primitive), [Navigation](./navigation.md) (host graph, route constants, destination wiring), [Dependency injection](./dependency-injection.md) (Koin VM binding)
- Downstream: #21 (TopAppBar wraps this screen in a `Scaffold`), #22 (FAB), #23 (real Loading / Empty / Error visuals replace the centred `Text` placeholders), #19 / #20 (per-row decorations on the `ConversationRow` instances rendered here — workspace label, sleeping indicator), #26 (recent-discussions pill above the `LazyColumn`), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + VM-side reducer), Phase 4 (real backend behind the same `ConversationRepository` bind — zero screen change)
