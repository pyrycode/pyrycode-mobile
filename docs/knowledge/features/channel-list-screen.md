# ChannelListScreen

Stateless `(state, onEvent)` composable that renders a Material 3 `Scaffold` with a `TopAppBar` (app-name title + trailing settings gear) above a `LazyColumn` of `ConversationRow`s for the persistent-channel slice. Dispatches `ChannelListEvent.RowTapped` on row clicks and `ChannelListEvent.SettingsTapped` on gear taps. First stateless UI consumer of a ViewModel in the project; introduces the sealed `Event` shape every screen will follow.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is a Material 3 `TopAppBar` (rendered in **every** state). Renders the four `ChannelListUiState` variants from the VM (#45) inside the Scaffold body:

- **`Loading`** — centred `Text("Loading…")` placeholder.
- **`Empty`** — centred `Text("No channels yet")` placeholder.
- **`Error(message)`** — centred `Text("Couldn't load channels: $message")` placeholder.
- **`Loaded(channels)`** — `LazyColumn` of `ConversationRow`s, one per channel, keyed by `Conversation.id`.

The `TopAppBar`'s title is `stringResource(R.string.app_name)` ("Pyrycode Mobile"); its single trailing `actions` slot is an `IconButton` wrapping `Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_open_settings))`. Each row's `onClick` emits `ChannelListEvent.RowTapped(channel.id)` and the gear `onClick` emits `ChannelListEvent.SettingsTapped` through the screen's `onEvent` lambda. The NavHost destination is the only place that lambda resolves to concrete actions (`navController.navigate("conversation_thread/$id")` and `navController.navigate(Routes.Settings)`); the screen itself is `NavController`-free.

## Shape

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onEvent(ChannelListEvent.SettingsTapped) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        when (state) {
            ChannelListUiState.Loading -> CenteredText("Loading…", bodyModifier)
            ChannelListUiState.Empty -> CenteredText("No channels yet", bodyModifier)
            is ChannelListUiState.Error -> CenteredText(
                "Couldn't load channels: ${state.message}",
                bodyModifier,
            )
            is ChannelListUiState.Loaded -> LazyColumn(modifier = bodyModifier.fillMaxSize()) {
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
}
```

`ChannelListEvent` lives in `ChannelListScreen.kt`, not `ChannelListViewModel.kt`, because the screen — specifically `ConversationRow.onClick` and the gear `IconButton.onClick` — is the only producer for these variants. When a future ticket adds a VM-side reducer (`fun onEvent(event: ChannelListEvent)`), the type moves into the VM file to join its consumer.

## How it works

### Stateless `(state, onEvent)` contract

The screen takes a value-typed `state` and an `onEvent` lambda. No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. This is the canonical CLAUDE.md shape: hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`. Every subsequent screen in the project will follow the same shape.

### Exhaustive `when (state)`

Kotlin's sealed-interface exhaustiveness check enforces full variant coverage at compile time. No `else ->` branch — when Phase 4 adds a fifth `ChannelListUiState` variant (e.g. `Stale`), the compiler points at this `when`. That's the feature. The same shape governs the destination-level `when (event)` in `MainActivity`: appending `SettingsTapped` made the navigation `when` exhaustive on the sealed interface without requiring a defensive `else`.

### `Scaffold` + `TopAppBar` chrome (#21)

The screen owns its own chrome rather than relying on a shared `TopAppBar` slot threaded through the NavHost. The outer `Scaffold` in `MainActivity` carries system-bar insets only; this inner `Scaffold` carries the screen's `TopAppBar`. The `Scaffold` body lambda's `PaddingValues` (named `inner`) is captured into a local `bodyModifier = Modifier.padding(inner)` and applied to each `when` branch — inline form, no `ChannelListBody` extraction; the body is short enough to stay readable. The `TopAppBar` is the small/leading-aligned Material 3 default (not `CenterAlignedTopAppBar`), with no scroll behaviour or navigation icon — this is a start destination with no back target. `@OptIn(ExperimentalMaterial3Api::class)` annotates the composable boundary; `TopAppBar` is perma-experimental in Compose Material 3.

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
                ChannelListEvent.SettingsTapped ->
                    navController.navigate(Routes.Settings)
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
- **Icons:** `androidx.compose.material:material-icons-core` (catalog: `androidx-compose-material-icons-core`, BOM-managed — no `version.ref`). The always-on icon set; supplies `Icons.Default.Settings` for the TopAppBar gear (#21). Don't reach for `material-icons-extended` (multi-MB megapack) for single-glyph needs — try `-core` first.
- **Strings:** `R.string.app_name` for the TopAppBar title (reused, defined since project init); `R.string.cd_open_settings` ("Open settings") for the gear's TalkBack `contentDescription`. New a-11y strings use the `cd_` prefix.

## Preview

Single `@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)` named `ChannelListScreenLoadedPreview`. Light theme, three inline `Conversation` instances varying `name`, `cwd` (one `DefaultScratchCwd` to verify the workspace-label-omission path through `ConversationRow`, two real workspaces), and `lastUsedAt` (12 minutes, 4 hours, 3 days ago). `widthDp = 412` matches the `ConversationRow.kt` previews from #17 for consistent device shape. `Loading` / `Empty` / `Error` are not previewed — throwaway placeholders being replaced in #23.

## Edge cases / limitations

- **No FAB yet.** The screen now has chrome (`Scaffold` + `TopAppBar` since #21) but no floating action button; FAB lands in #22 — wired into the same inner `Scaffold`'s `floatingActionButton` slot.
- **No `rememberLazyListState` / scroll-position persistence.** `LazyColumn` auto-saves scroll position within a single composition; `rememberSaveable(saver = LazyListState.Saver)` is the next step when a real bug surfaces (e.g. scroll resets after returning from thread). Defer until observed.
- **No retry affordance on `Error`.** `ChannelListEvent` has no `RetryClicked` variant. Per #45's spec, recovery requires a fresh subscription (leave the screen, wait 5s for `WhileSubscribed` to expire, re-enter). A retry button lands with the ticket that commits to designed error UI.
- **No instrumented test.** AC requires only `./gradlew assembleDebug`. The natural unit test ("tap a row → `onEvent(RowTapped(channelId))` fires") needs `androidx-compose-ui-test-junit4` wired to `androidTestImplementation` and an `androidTest/` source set entry; deferred until the first ticket with multi-event logic to verify.
- **No `flowOn(Dispatchers.IO)` anywhere in the chain.** `collectAsStateWithLifecycle` collects on Main (via the destination's `LifecycleOwner`); the VM is Main-dispatched; the fake repo's projection is pure CPU map manipulation. Phase 4's remote impl decides its own dispatcher internally.
- **No empty-state distinction between "never had a channel" and "had channels, all deleted".** Both render `Empty` → `"No channels yet"`. The first ticket that needs the distinction extends `ChannelListUiState` (e.g. `Empty(reason: EmptyReason)`) or splits into a new variant.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md) (LazyColumn + tap nav), [`../codebase/21.md`](../codebase/21.md) (TopAppBar + settings-gear wiring)
- Specs: `docs/specs/architecture/46-channellistscreen-lazycolumn-tap-nav.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (state producer), [ConversationRow](./conversation-row.md) (per-row primitive), [Navigation](./navigation.md) (host graph, route constants, destination wiring), [Dependency injection](./dependency-injection.md) (Koin VM binding)
- Downstream: #22 (FAB), #23 (real Loading / Empty / Error visuals replace the centred `Text` placeholders), #19 / #20 (per-row decorations on the `ConversationRow` instances rendered here — workspace label, sleeping indicator), #26 (recent-discussions pill above the `LazyColumn`), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + VM-side reducer), Phase 3 Settings (replaces `SettingsPlaceholder` body — gear wiring already in place since #21), Phase 4 (real backend behind the same `ConversationRepository` bind — zero screen change)
