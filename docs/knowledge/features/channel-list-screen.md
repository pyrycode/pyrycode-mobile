# ChannelListScreen

Stateless `(state, onEvent)` composable that renders a Material 3 `Scaffold` with a `TopAppBar` (app-name title + trailing settings gear) above a `LazyColumn` of `ConversationRow`s for the persistent-channel slice, plus a trailing `FloatingActionButton` (#22) that creates a new discussion. Dispatches `ChannelListEvent.RowTapped` on row clicks, `SettingsTapped` on gear taps, and `CreateDiscussionTapped` on FAB taps. First stateless UI consumer of a ViewModel in the project; introduces the sealed `Event` shape every screen will follow.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is a Material 3 `TopAppBar` (rendered in **every** state) and whose `floatingActionButton` slot hosts a `FloatingActionButton` (rendered only when `state is Loaded || state is Empty`, #22). Renders the four `ChannelListUiState` variants from the VM (#45) inside the Scaffold body:

- **`Loading`** — centred `Text("Loading…")` placeholder. No FAB.
- **`Empty`** — centred `Text("Tap + to start a conversation")` (string resource `R.string.channel_list_empty`, #23). FAB rendered — the "+" in the copy refers to it.
- **`Error(message)`** — centred `Text("Couldn't load channels: $message")` placeholder. No FAB.
- **`Loaded(channels)`** — `LazyColumn` of `ConversationRow`s, one per channel, keyed by `Conversation.id`. FAB rendered.

The `TopAppBar`'s title is `stringResource(R.string.app_name)` ("Pyrycode Mobile"); its single trailing `actions` slot is an `IconButton` wrapping `Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_open_settings))`. The FAB wraps `Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_discussion))` at default `FabPosition.End`. Each row's `onClick` emits `ChannelListEvent.RowTapped(channel.id)`, the gear's `onClick` emits `SettingsTapped`, and the FAB's `onClick` emits `CreateDiscussionTapped` through the screen's `onEvent` lambda. The NavHost destination is the only place that lambda resolves to concrete actions (row/gear dispatched directly to `navController.navigate(...)`; `CreateDiscussionTapped` forwarded into `vm.onEvent(event)` so the suspend-shaped create can run and emit a `ChannelListNavigation.ToThread` event); the screen itself is `NavController`-free.

## Shape

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
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
        floatingActionButton = {
            if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty) {
                FloatingActionButton(
                    onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_new_discussion),
                    )
                }
            }
        },
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        when (state) {
            ChannelListUiState.Loading -> CenteredText("Loading…", bodyModifier)
            ChannelListUiState.Empty -> CenteredText(
                stringResource(R.string.channel_list_empty),
                bodyModifier,
            )
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

`ChannelListEvent` lives in `ChannelListScreen.kt`, not `ChannelListViewModel.kt`. The screen — `ConversationRow.onClick`, the gear `IconButton.onClick`, and the FAB's `onClick` — is the producer for all three variants. A VM-side reducer now exists for `CreateDiscussionTapped` (#22), but the sealed type stayed in the screen file: most variants still have no VM-side consumer, and routing decisions live at the destination's `when (event)`. Reconsider moving the type when *most* variants land in `vm.onEvent`.

## How it works

### Stateless `(state, onEvent)` contract

The screen takes a value-typed `state` and an `onEvent` lambda. No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. This is the canonical CLAUDE.md shape: hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`. Every subsequent screen in the project will follow the same shape.

### Exhaustive `when (state)`

Kotlin's sealed-interface exhaustiveness check enforces full variant coverage at compile time. No `else ->` branch — when Phase 4 adds a fifth `ChannelListUiState` variant (e.g. `Stale`), the compiler points at this `when`. That's the feature. The same shape governs the destination-level `when (event)` in `MainActivity`: appending `SettingsTapped` made the navigation `when` exhaustive on the sealed interface without requiring a defensive `else`.

### `Scaffold` + `TopAppBar` chrome (#21) + `FloatingActionButton` (#22)

The screen owns its own chrome rather than relying on a shared `TopAppBar` slot threaded through the NavHost. The outer `Scaffold` in `MainActivity` carries system-bar insets only; this inner `Scaffold` carries the screen's `TopAppBar` and FAB. The `Scaffold` body lambda's `PaddingValues` (named `inner`) is captured into a local `bodyModifier = Modifier.padding(inner)` and applied to each `when` branch — inline form, no `ChannelListBody` extraction; the body is short enough to stay readable. The `TopAppBar` is the small/leading-aligned Material 3 default (not `CenterAlignedTopAppBar`), with no scroll behaviour or navigation icon — this is a start destination with no back target. `@OptIn(ExperimentalMaterial3Api::class)` annotates the composable boundary; `TopAppBar` is perma-experimental in Compose Material 3.

The FAB sits at default `FabPosition.End`. The conditional `if (state is Loaded || state is Empty)` lives *inside* the slot's composable lambda — `Scaffold.floatingActionButton` is a non-nullable `@Composable () -> Unit`, so an `if/else` at the slot's assignment site does not type-check. Loading and Error states render no FAB (the slot's lambda body returns `Unit`). Snapshot reads of `state` inside the slot cause the slot to recompose on the Loading→Loaded transition without a `derivedStateOf`.

### `LazyColumn` with stable keys

`items(items = state.channels, key = { it.id })`. The `key` parameter is non-optional: without it, recomposition diffs list items by position rather than identity, and reorders (e.g. when a channel's `lastUsedAt` bumps it up the list) recompose every row below the moved item. `Conversation.id` is the stable identity per the data-model contract.

### `Loading` / `Empty` / `Error` placeholders

Each is a single `Text` centred inside `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)`. The private `CenteredText(text, modifier)` helper is the shared shape. AC permits a single `Text(...)`; centring is a basic layout decision, not a spinner or illustration. The `Empty` copy was upgraded in #23 from the placeholder `"No channels yet"` to the call-to-action `"Tap + to start a conversation"` (resource `R.string.channel_list_empty`) — the "+" refers to the FAB rendered in the same Scaffold. Plan.md's optional illustration above the copy is unrealised; text-only is correct for Phase 1.

### `ConversationRow.lastMessage = null`

`ChannelListUiState.Loaded` carries `List<Conversation>` only — no per-channel `Message?` projection. Passing `lastMessage = null` for every row is safe: `ConversationRow`'s supporting-content branch is `lastMessage?.let { ... }`, so the row collapses to its title + trailing relative-time stamp. A future ticket may extend `Loaded` to carry a `Map<String, Message?>` or a `data class ChannelRow(val conversation, val lastMessage)`.

## Wiring

At the `composable(Routes.ChannelList) { ... }` block in `MainActivity.PyryNavHost`:

```kotlin
composable(Routes.ChannelList) {
    val vm = koinViewModel<ChannelListViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.navigationEvents.collect { event ->
            when (event) {
                is ChannelListNavigation.ToThread ->
                    navController.navigate("conversation_thread/${event.conversationId}")
            }
        }
    }
    ChannelListScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is ChannelListEvent.RowTapped ->
                    navController.navigate("conversation_thread/${event.conversationId}")
                ChannelListEvent.SettingsTapped ->
                    navController.navigate(Routes.Settings)
                ChannelListEvent.CreateDiscussionTapped ->
                    vm.onEvent(event)
            }
        },
    )
}
```

Key points:

- **`koinViewModel<ChannelListViewModel>()` resolves against the existing Koin binding** (`viewModel { ChannelListViewModel(get()) }` from #45's `AppModule.kt`). Every `composable { ... }` block is a `NavBackStackEntry`-keyed scope; Compose Navigation 2.9+ auto-wires `LocalViewModelStoreOwner` to the current entry, so the bare call resolves to the entry-scoped store — the VM is created on first entry, retained across configuration changes, cleared on pop. No `viewModelStoreOwner = backStackEntry` argument needed.
- **`collectAsStateWithLifecycle()`, not `collectAsState()`.** The lifecycle-aware variant pauses upstream collection when the lifecycle drops below `STARTED`, which is what makes the VM's `WhileSubscribed(5_000)` actually save work when the screen is backgrounded.
- **Inline `when (event)` dispatch with mixed routing.** Three variants today: `RowTapped` and `SettingsTapped` resolve to `navController.navigate(...)` directly; `CreateDiscussionTapped` forwards into `vm.onEvent(event)` because its target route depends on the id returned by the suspend-shaped `createDiscussion()` call. The rule: events with no VM-side side effect stay routed at the destination; events that need a suspend or VM state mutation forward into `onEvent`.
- **`LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` for one-shot nav events.** Sits alongside the `state` read inside `composable(Routes.ChannelList)`. The `vm` key restarts the collector exactly when the VM identity changes (per `NavBackStackEntry` scope), which is the correct boundary for a one-shot channel. See [`channel-list-viewmodel.md`](./channel-list-viewmodel.md) for the `Channel<ChannelListNavigation>` + `receiveAsFlow()` shape on the emitter side.
- **Concrete navigation target built inline:** `"conversation_thread/${event.conversationId}"`. No `Routes.conversationThread(id)` helper — matches the navigation feature doc's "build inline until a second caller appears" rule.
- **No `popUpTo` / `launchSingleTop`.** Default `navigate(route)` semantics are correct: tapping a channel pushes the thread onto the back stack; back-press returns to the channel list. The only exceptional case in the graph is the `scanner` → `channel_list` transition.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-runtime-compose` (catalog: `androidx-lifecycle-runtime-compose`, reuses the `lifecycleRuntimeKtx = "2.6.1"` version-ref). Required for `collectAsStateWithLifecycle` — not pulled transitively by `lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx`, or the `koin-androidx-compose` chain. Three lifecycle artifacts now on the classpath (`-ktx`, `-viewmodel-ktx`, `-runtime-compose`) — they share `LifecycleOwner` ABIs and must stay on the same version-ref.
- **Koin compose:** `org.koin.androidx.compose.koinViewModel` (already on the classpath since #32 via `koin-androidx-compose`). Distinct from `org.koin.compose.koinInject` (the `scanner` destination's `AppPreferences` resolver) — `koinViewModel` is the right call for `ViewModel`-typed bindings because it routes through `LocalViewModelStoreOwner`.
- **Icons:** `androidx.compose.material:material-icons-core` (catalog: `androidx-compose-material-icons-core`, BOM-managed — no `version.ref`). The always-on icon set; supplies `Icons.Default.Settings` for the TopAppBar gear (#21). Don't reach for `material-icons-extended` (multi-MB megapack) for single-glyph needs — try `-core` first.
- **Strings:** `R.string.app_name` for the TopAppBar title (reused, defined since project init); `R.string.cd_open_settings` ("Open settings") for the gear's TalkBack `contentDescription`; `R.string.cd_new_discussion` ("New discussion") for the FAB's TalkBack description (#22); `R.string.channel_list_empty` ("Tap + to start a conversation") for the Empty body (#23). A-11y content-description strings keep the `cd_` prefix; user-facing copy uses a `<screen>_<role>` shape.

## Preview

Two `@Preview` composables, both light-theme via `PyrycodeMobileTheme(darkTheme = false)`, both `widthDp = 412` (matches the `ConversationRow.kt` previews from #17 for consistent device shape):

- `ChannelListScreenLoadedPreview` (`@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)`) — three inline `Conversation` instances varying `name`, `cwd` (one `DefaultScratchCwd` to verify the workspace-label-omission path through `ConversationRow`, two real workspaces), and `lastUsedAt` (12 minutes, 4 hours, 3 days ago).
- `ChannelListScreenEmptyPreview` (`@Preview(name = "Empty — Light", showBackground = true, widthDp = 412)`, #23) — passes `state = ChannelListUiState.Empty, onEvent = {}`. No sample data needed (`Empty` is a `data object`). Renders the TopAppBar above the centred empty-state copy and the FAB at `FabPosition.End`.

`Loading` and `Error` are still not previewed — their copy is a transient placeholder until designed visuals land.

## Edge cases / limitations

- **FAB has no long-press affordance.** Long-press → workspace picker is deferred to Phase 2 (part of the `ConversationThread` design).
- **FAB hidden in Loading and Error.** Conditional `state is Loaded || state is Empty`. AC specifically required Loaded and Empty; rendering in Loading risks a "tap before initial repository emission" race, and Error has no useful create target until recovery. Revisit if the design adds a "create from error" affordance.
- **No error/loading affordance on the create call itself.** `repository.createDiscussion()` is `suspend` but the fake never throws and resolves synchronously enough that no spinner is needed. Phase 4's `RemoteConversationRepository` adds both the error UI and the loading indicator together.
- **No `rememberLazyListState` / scroll-position persistence.** `LazyColumn` auto-saves scroll position within a single composition; `rememberSaveable(saver = LazyListState.Saver)` is the next step when a real bug surfaces (e.g. scroll resets after returning from thread). Defer until observed.
- **No retry affordance on `Error`.** `ChannelListEvent` has no `RetryClicked` variant. Per #45's spec, recovery requires a fresh subscription (leave the screen, wait 5s for `WhileSubscribed` to expire, re-enter). A retry button lands with the ticket that commits to designed error UI.
- **No instrumented test.** AC requires only `./gradlew assembleDebug`. The natural unit test ("tap a row → `onEvent(RowTapped(channelId))` fires") needs `androidx-compose-ui-test-junit4` wired to `androidTestImplementation` and an `androidTest/` source set entry; deferred until the first ticket with multi-event logic to verify.
- **No `flowOn(Dispatchers.IO)` anywhere in the chain.** `collectAsStateWithLifecycle` collects on Main (via the destination's `LifecycleOwner`); the VM is Main-dispatched; the fake repo's projection is pure CPU map manipulation. Phase 4's remote impl decides its own dispatcher internally.
- **No empty-state distinction between "never had a channel" and "had channels, all deleted".** Both render `Empty` → `"Tap + to start a conversation"`. The first ticket that needs the distinction extends `ChannelListUiState` (e.g. `Empty(reason: EmptyReason)`) or splits into a new variant.
- **Empty-state trigger is "no channels", not Plan.md's "no channels AND no discussions".** `ChannelListViewModel` observes only `ConversationFilter.Channels`, so a user with zero channels but non-zero discussions still sees the empty copy. Widening was considered and deferred in #23 — it would require a new `ChannelListUiState` variant (a discussion-aware CTA like "You have N discussions — promote one to a channel?"), which is a product call, not Phase 1 plumbing. The current copy reads correctly in either case because the FAB does create a conversation.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md) (LazyColumn + tap nav), [`../codebase/21.md`](../codebase/21.md) (TopAppBar + settings-gear wiring), [`../codebase/22.md`](../codebase/22.md) (FAB + one-shot nav channel), [`../codebase/23.md`](../codebase/23.md) (Empty-state copy + preview)
- Specs: `docs/specs/architecture/46-channellistscreen-lazycolumn-tap-nav.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/23-channel-list-empty-state.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (state producer + `onEvent` reducer + `navigationEvents`), [ConversationRow](./conversation-row.md) (per-row primitive), [Navigation](./navigation.md) (host graph, route constants, destination wiring), [Dependency injection](./dependency-injection.md) (Koin VM binding)
- Downstream: #19 / #20 (per-row decorations on the `ConversationRow` instances rendered here — workspace label, sleeping indicator), #26 (recent-discussions pill above the `LazyColumn`), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + VM-side reducer), Phase 2 long-press → workspace picker on the FAB, Phase 3 Settings (replaces `SettingsPlaceholder` body — gear wiring already in place since #21), Phase 4 (real backend behind the same `ConversationRepository` bind — zero screen change; adds error + loading UI for the create call), follow-up empty-state widening (`combine` channels + discussions flows, introduce a discussion-aware CTA variant — deferred from #23)
