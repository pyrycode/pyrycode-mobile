# ChannelListScreen

Stateless `(state, onEvent)` composable that renders a Material 3 `Scaffold` with a `TopAppBar` (leading Pyrycode logo + app-name title + trailing settings gear, #68) above a `LazyColumn` of `ConversationRow`s — each carrying a 40dp leading [`ConversationAvatar`](./conversation-avatar.md) (#68) — for the persistent-channel slice, preceded by a "Channels" section header (#68, `Loaded` branch only) and trailed by a manually-composed `Surface`-based FAB (#22 wired the M3 `FloatingActionButton`; #221 replaced it with a non-clickable `Surface` carrying a `Modifier.combinedClickable` — tap creates a scratch discussion, long-press opens the [`WorkspacePicker`](./workspace-picker.md)) that creates a new discussion. Since #69 an inline "Recent discussions" section ([`DiscussionPreviewRow`](./discussion-preview-row.md) × up-to-3 + "See all discussions (N) →" link) renders as a trailing `LazyColumn` item in `Loaded` (above the centred empty-state copy in `Empty`); the previous `RecentDiscussionsPill` is gone. Since #221 the [`WorkspacePicker`](./workspace-picker.md) host composable is mounted as a sibling of the `Scaffold` and gated on the new `state.workspacePickerVisible: Boolean` field (on both `Loaded` and `Empty`). Dispatches `ChannelListEvent.RowTapped` on row clicks (including discussion preview rows), `SettingsTapped` on gear taps, `CreateDiscussionTapped` on FAB taps, `RecentDiscussionsTapped` on the See-all row, and (#221) `LongPressFab` / `WorkspacePicked(workspace)` / `WorkspacePickerDismissed` on the FAB long-press → picker flow. First stateless UI consumer of a ViewModel in the project; introduces the sealed `Event` shape every screen will follow.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is a Material 3 `TopAppBar` (rendered in **every** state) and whose `floatingActionButton` slot hosts the file-private `ChannelListFab` (rendered only when `state is Loaded || state is Empty`, #22; the M3 `FloatingActionButton` widget was replaced in #221 by a manually-composed `Surface` so an outer `combinedClickable` can own the tap + long-press gestures without being shadowed by an inner `Surface(onClick = ...)`). Renders the four `ChannelListUiState` variants from the VM (#45) inside the Scaffold body:

- **`Loading`** — centred `Text("Loading…")` placeholder. No FAB, no recent-discussions section.
- **`Empty(recentDiscussions, recentDiscussionsCount, recentDiscussionLastMessages)`** — `Column(bodyModifier.fillMaxSize())` containing `RecentDiscussionsSection(...)` (renders nothing when `recentDiscussions.isEmpty()`, otherwise emits divider + header + up-to-3 `DiscussionPreviewRow`s + See-all link, #69; since #162 each row receives its last message via `lastMessages[conversation.id]`) above a centred `Text("Tap + to start a conversation")` (resource `R.string.channel_list_empty`, #23) inside `Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center)`. FAB rendered — the "+" in the copy refers to it.
- **`Error(message)`** — centred `Text("Couldn't load channels: $message")` placeholder. No FAB, no section.
- **`Loaded(channels, recentDiscussions, recentDiscussionsCount, recentDiscussionLastMessages)`** — `Column(bodyModifier.fillMaxSize())` containing a private `ChannelsSectionHeader()` (#68 — `labelLarge` "Channels" on `onSurfaceVariant @ 0.85f`, padding `(start=16, end=16, top=12, bottom=4)`), then `LazyColumn(Modifier.weight(1f))` of `ConversationRow`s (one per channel, keyed by `Conversation.id`) followed by a trailing `item(key = "recent-discussions-section")` hosting `RecentDiscussionsSection` (#69 — placement moved *inside* the `LazyColumn` so the section scrolls with the channels, matching the Figma `15:8` single-scroll layout; since #162 forwards `state.recentDiscussionLastMessages` so each row reads its own most-recent `Message`). FAB rendered.

The `TopAppBar`'s `navigationIcon` slot (#68) is a 28dp `Icon(painter = painterResource(R.drawable.ic_pyry_logo), tint = MaterialTheme.colorScheme.primary)` centred inside a 40dp `Box`; the drawable's own `#FFFFFF` fills are overridden by Compose's `SrcIn` `ColorFilter` (`Icon(painter, tint = …)` applies it across the painter, so the logo always renders in `primary` regardless of the asset's internal fills). Its title is `stringResource(R.string.app_name)` ("Pyrycode Mobile"); its single trailing `actions` slot is an `IconButton` wrapping `Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_open_settings))`. The FAB wraps `Icon(Icons.Default.Add, contentDescription = null)` (since #221 the `contentDescription` lives on the outer `combinedClickable`'s `onClickLabel` / `onLongClickLabel`, not the inner `Icon`) at default `FabPosition.End`. Each row's `onClick` emits `ChannelListEvent.RowTapped(channel.id)` (channels) or `ChannelListEvent.RowTapped(discussion.id)` (preview rows inside the section), the gear's `onClick` emits `SettingsTapped`, the FAB's `onClick` emits `CreateDiscussionTapped`, the FAB's `onLongClick` (#221) emits `LongPressFab`, the `WorkspacePicker` host's `onPicked(path)` emits `WorkspacePicked(path)` and its `onDismiss` emits `WorkspacePickerDismissed`, and the See-all row's `onClick` emits `RecentDiscussionsTapped` through the screen's `onEvent` lambda. The NavHost destination is the only place that lambda resolves to concrete actions (row/gear/See-all dispatched directly to `navController.navigate(...)`; `CreateDiscussionTapped` and the three #221 variants forwarded into `vm.onEvent(event)` so the suspend-shaped create can run and emit a `ChannelListNavigation.ToThread` event); the screen itself is `NavController`-free.

The section's "render only when non-empty" guard lives inside `RecentDiscussionsSection` itself (`if (discussions.isEmpty()) return`) — call sites in both `Empty` and `Loaded` branches invoke it unconditionally. This is the "no orphan header" AC: when there are no recent discussions, the entire block (divider + header + rows + See-all link) collapses to nothing.

## Shape

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
    data object RecentDiscussionsTapped : ChannelListEvent
    data object LongPressFab : ChannelListEvent                          // #221
    data class WorkspacePicked(val workspace: String) : ChannelListEvent // #221
    data object WorkspacePickerDismissed : ChannelListEvent              // #221
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newDiscussionLabel = stringResource(R.string.cd_new_discussion)
    val longPressLabel = stringResource(R.string.cd_long_press_fab_pick_workspace)
    Scaffold(
        modifier = modifier,
        topBar = { /* … TopAppBar with settings gear … */ },
        floatingActionButton = {
            if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty) {
                ChannelListFab(
                    onTap = { onEvent(ChannelListEvent.CreateDiscussionTapped) },
                    onLongPress = { onEvent(ChannelListEvent.LongPressFab) },
                    onTapLabel = newDiscussionLabel,
                    onLongPressLabel = longPressLabel,
                )
            }
        },
    ) { inner ->
        /* … same body as before — when (state) → Loading/Empty/Error/Loaded … */
    }
    val pickerVisible = when (state) {
        is ChannelListUiState.Loaded -> state.workspacePickerVisible
        is ChannelListUiState.Empty -> state.workspacePickerVisible
        ChannelListUiState.Loading, is ChannelListUiState.Error -> false
    }
    WorkspacePicker(
        visible = pickerVisible,
        onPicked = { path -> onEvent(ChannelListEvent.WorkspacePicked(path)) },
        onDismiss = { onEvent(ChannelListEvent.WorkspacePickerDismissed) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListFab(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onTapLabel: String,
    onLongPressLabel: String,
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                onClickLabel = onTapLabel,
                onLongClickLabel = onLongPressLabel,
                role = Role.Button,
            ),
        shape = FloatingActionButtonDefaults.shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
        }
    }
}
```

`ChannelListEvent` lives in `ChannelListScreen.kt`, not `ChannelListViewModel.kt`. The screen — `ConversationRow.onClick`, `DiscussionPreviewRow.onClick` (inside the section), the gear `IconButton.onClick`, the FAB's `onClick` / `onLongClick`, the `WorkspacePicker`'s `onPicked` / `onDismiss`, and the See-all row's `onClick` — is the producer for all seven variants. The VM-side reducer (#22 / #221) now handles `CreateDiscussionTapped`, `LongPressFab`, `WorkspacePicked`, and `WorkspacePickerDismissed`; routing decisions for the other three live at the destination's `when (event)`. The sealed type stayed in the screen file: nav-only variants outnumber VM-consumed variants only marginally now (3 vs. 4), and the producer set is uniformly the screen. Reconsider moving the type only if the producer ever ceases to be the screen.

## Recent-discussions section (#69)

Two file-local private composables inside `ChannelListScreen.kt`: `RecentDiscussionsSection` (the whole block) and `SeeAllDiscussionsRow` (the trailing affordance).

`RecentDiscussionsSection(discussions, lastMessages, totalCount, onSeeAllClick, onRowClick)` — `lastMessages` param added in #162:

```kotlin
@Composable
private fun RecentDiscussionsSection(
    discussions: List<Conversation>,
    lastMessages: Map<String, Message>,
    totalCount: Int,
    onSeeAllClick: () -> Unit,
    onRowClick: (String) -> Unit,
) {
    if (discussions.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
        )
        Text(
            text = stringResource(R.string.recent_discussions_section_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        discussions.forEach { conversation ->
            DiscussionPreviewRow(
                conversation = conversation,
                lastMessage = lastMessages[conversation.id],
                onClick = { onRowClick(conversation.id) },
            )
        }
        SeeAllDiscussionsRow(totalCount = totalCount, onClick = onSeeAllClick)
    }
}
```

Block emission order matches Figma `15:8`: 1dp `HorizontalDivider` (`outlineVariant @ 0.60f`, Figma `15:85`) → label-large header on `onSurfaceVariant @ 0.85f` (Figma `15:87`, padding mirrors `ChannelsSectionHeader`) → up-to-3 `DiscussionPreviewRow`s in a plain `forEach` (no `LazyColumn` — the section is itself inside the outer `LazyColumn` as a single `item`, and 3 rows isn't worth a nested lazy region) → `SeeAllDiscussionsRow`.

`lastMessages` is plumbed top-down from `ChannelListUiState.recentDiscussionLastMessages` (added in #161) — both `Empty` and `Loaded` branches pass `state.recentDiscussionLastMessages` through verbatim. The per-row `lastMessages[conversation.id]` lookup returns `Message?` (the map only holds non-null entries by construction, so an absent key collapses to the row's null-body shape — title above meta, no body slot, single 2 dp gap). Stable map type means no `remember` / `derivedStateOf` needed: as long as the `UiState` instance is stable (a `data class` of stable fields, true today), recomposition is shape-driven.

`SeeAllDiscussionsRow(totalCount, onClick)`:

```kotlin
@Composable
private fun SeeAllDiscussionsRow(totalCount: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.cd_see_all_discussions, totalCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.see_all_discussions_label, totalCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}
```

Modifier order is load-bearing: `.clickable` *then* `.semantics(mergeDescendants = true) { contentDescription = … ; role = Role.Button }`. The merged-descendants semantics carry the row's `contentDescription` and `role`; the inner `Text` and `Icon` (the icon's `contentDescription = null` is correct) collapse into the merged node so TalkBack reads "See all N recent discussions, button" once, not twice. The arrow uses `Icons.AutoMirrored.Filled.ArrowForward` from `material-icons-core` — `AutoMirrored` is a sub-package, not a separate artifact (do not reach for `material-icons-extended`).

Strings (#69): `R.string.recent_discussions_section_header` ("Recent discussions"), `R.string.see_all_discussions_label` ("See all discussions (%d)", parametrised), `R.string.cd_see_all_discussions` ("See all %d recent discussions", parametrised). The previously-shipped `R.string.recent_discussions_pill_label` and `R.string.cd_recent_discussions_pill` were deleted alongside the pill file.

Per-row primitive: [`DiscussionPreviewRow`](./discussion-preview-row.md) — distinct from `ConversationRow` (no `ListItem` chrome, no avatar slot, no trailing time; instead title / body / `<workspace> · <time>` meta with a monospace span on the workspace fragment).

## How it works

### Stateless `(state, onEvent)` contract

The screen takes a value-typed `state` and an `onEvent` lambda. No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. This is the canonical CLAUDE.md shape: hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`. Every subsequent screen in the project will follow the same shape.

### Exhaustive `when (state)`

Kotlin's sealed-interface exhaustiveness check enforces full variant coverage at compile time. No `else ->` branch — when Phase 4 adds a fifth `ChannelListUiState` variant (e.g. `Stale`), the compiler points at this `when`. That's the feature. The same shape governs the destination-level `when (event)` in `MainActivity`: appending `SettingsTapped` made the navigation `when` exhaustive on the sealed interface without requiring a defensive `else`.

### `Scaffold` + `TopAppBar` chrome (#21) + `ChannelListFab` (#22 → #221)

The screen owns its own chrome rather than relying on a shared `TopAppBar` slot threaded through the NavHost. The outer `Scaffold` in `MainActivity` carries system-bar insets only; this inner `Scaffold` carries the screen's `TopAppBar` and FAB. The `Scaffold` body lambda's `PaddingValues` (named `inner`) is captured into a local `bodyModifier = Modifier.padding(inner)` and applied to each `when` branch — inline form, no `ChannelListBody` extraction; the body is short enough to stay readable. The `TopAppBar` is the small/leading-aligned Material 3 default (not `CenterAlignedTopAppBar`), with no scroll behaviour or navigation icon — this is a start destination with no back target. `@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)` annotates the composable boundary; `TopAppBar` is perma-experimental in Compose Material 3, and `combinedClickable` (used in the file-private `ChannelListFab`) requires the foundation opt-in.

The FAB sits at default `FabPosition.End`. The conditional `if (state is Loaded || state is Empty)` lives *inside* the slot's composable lambda — `Scaffold.floatingActionButton` is a non-nullable `@Composable () -> Unit`, so an `if/else` at the slot's assignment site does not type-check. Loading and Error states render no FAB (the slot's lambda body returns `Unit`). Snapshot reads of `state` inside the slot cause the slot to recompose on the Loading→Loaded transition without a `derivedStateOf`.

#### `ChannelListFab` — manually-composed `Surface`, not M3 `FloatingActionButton` (#221)

#22 originally used the M3 `FloatingActionButton` widget. #221 needed to add a long-press gesture; wrapping the M3 FAB in an outer `Box.combinedClickable { FloatingActionButton(onClick = {}) { … } }` does **not** work — the M3 widget composes its own inner `Surface(onClick = ...)`, which installs a `Modifier.clickable` on a leaf node that consumes pointer events before they can propagate up to the outer `combinedClickable`. The failure mode is silent: tap appears to work in cursory testing because the inner clickable also fires, but the outer `onLongClick` is never reached. Same failure shape as #25's "`Modifier.clickable` on the inner `ListItem` of `ConversationRow` shadows any outer pointer-input wrapper" (see [`../codebase/25.md`](../codebase/25.md) and [`../codebase/221.md`](../codebase/221.md) for the full reasoning).

The fix: replace the M3 widget with a manually-composed non-clickable `Surface` carrying the `combinedClickable` directly on its modifier. The file-private `ChannelListFab(onTap, onLongPress, onTapLabel, onLongPressLabel)` reproduces the M3 standard FAB visual using only public M3 surface API:

- `Modifier.size(56.dp)` — matches the internal `FabPrimaryTokens.ContainerWidth/Height`.
- `Modifier.combinedClickable(onClick, onLongClick, onClickLabel, onLongClickLabel, role = Role.Button)` — the sole pointer-input subscriber on the node. `role = Role.Button` + the two labels make TalkBack announce "New discussion, button, double-tap to activate, long-press to pick workspace." No outer `semantics { }` is needed; `combinedClickable` attaches the role automatically. The default indication (`LocalIndication.current`, resolves to M3 ripple inside the theme) preserves the ripple-on-press feedback.
- `shape = FloatingActionButtonDefaults.shape` — the public M3 shape token; matches the native FAB shape verbatim.
- `color = MaterialTheme.colorScheme.primaryContainer` / `contentColor = onPrimaryContainer` — the M3 standard FAB tonal palette.
- `tonalElevation = 6.dp` + `shadowElevation = 6.dp` — matches `FabPrimaryTokens.ContainerElevation`.
- Inner `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, contentDescription = null) }` — the M3 FAB widget centres its content automatically; the non-clickable `Surface { content }` overload does not, so the inner `Box` carries the centring explicitly. `contentDescription = null` on the `Icon` because the `combinedClickable`'s `onClickLabel` / `onLongClickLabel` already carry the a11y descriptions — duplicating would announce twice.

The tradeoff is that the M3 widget's "lower elevation while held" press animation is lost (we don't replicate the internal `InteractionSource` plumbing); the ripple from `combinedClickable` covers the user-facing feedback gap. Acceptable for Phase 0. If a future Compose Material 3 BOM introduces a native `onLongClick` parameter on `FloatingActionButton`, revert to the native widget and delete `ChannelListFab`.

#### `WorkspacePicker` host as Scaffold sibling (#221)

After the `Scaffold { ... }` block closes, the screen composes a `WorkspacePicker(visible, onPicked, onDismiss)` as a sibling of the Scaffold (not inside its content lambda). `visible` is derived from an exhaustive `when (state)` block that maps `Loaded`/`Empty` to their `workspacePickerVisible: Boolean` field and `Loading`/`Error` to `false`. `onPicked(path)` dispatches `WorkspacePicked(path)`; `onDismiss` dispatches `WorkspacePickerDismissed`.

Sibling placement (not inside Scaffold) matches #78's `SaveAsChannelDialog` rendering. [`WorkspacePicker`](./workspace-picker.md) wraps a `ModalBottomSheet` which manages its own `Popup`/`Window` and renders above the entire activity; placing it inside the Scaffold body would conflate the sheet's window-level scrim with the body's `LazyColumn` layout. The host costs nothing when `visible = false` (its public composable does `if (!visible) return` before any Koin lookup, `remember`, or coroutine scope) — mounting it permanently is the correct shape.

### `LazyColumn` with stable keys

`items(items = state.channels, key = { it.id })`. The `key` parameter is non-optional: without it, recomposition diffs list items by position rather than identity, and reorders (e.g. when a channel's `lastUsedAt` bumps it up the list) recompose every row below the moved item. `Conversation.id` is the stable identity per the data-model contract.

### `Loading` / `Empty` / `Error` placeholders

Each is a single `Text` centred inside `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)`. The private `CenteredText(text, modifier)` helper is the shared shape. AC permits a single `Text(...)`; centring is a basic layout decision, not a spinner or illustration. The `Empty` copy was upgraded in #23 from the placeholder `"No channels yet"` to the call-to-action `"Tap + to start a conversation"` (resource `R.string.channel_list_empty`) — the "+" refers to the FAB rendered in the same Scaffold. Plan.md's optional illustration above the copy is unrealised; text-only is correct for Phase 1.

## Wiring

At the `composable(Routes.CHANNEL_LIST) { ... }` block in `MainActivity.PyryNavHost`:

```kotlin
composable(Routes.CHANNEL_LIST) {
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
                    navController.navigate(Routes.SETTINGS)
                ChannelListEvent.RecentDiscussionsTapped ->
                    navController.navigate(Routes.DISCUSSION_LIST)
                ChannelListEvent.CreateDiscussionTapped,
                ChannelListEvent.LongPressFab,
                is ChannelListEvent.WorkspacePicked,
                ChannelListEvent.WorkspacePickerDismissed,
                ->
                    vm.onEvent(event)
            }
        },
    )
}
```

Key points:

- **`koinViewModel<ChannelListViewModel>()` resolves against the existing Koin binding** (`viewModel { ChannelListViewModel(get()) }` from #45's `AppModule.kt`). Every `composable { ... }` block is a `NavBackStackEntry`-keyed scope; Compose Navigation 2.9+ auto-wires `LocalViewModelStoreOwner` to the current entry, so the bare call resolves to the entry-scoped store — the VM is created on first entry, retained across configuration changes, cleared on pop. No `viewModelStoreOwner = backStackEntry` argument needed.
- **`collectAsStateWithLifecycle()`, not `collectAsState()`.** The lifecycle-aware variant pauses upstream collection when the lifecycle drops below `STARTED`, which is what makes the VM's `WhileSubscribed(5_000)` actually save work when the screen is backgrounded.
- **Inline `when (event)` dispatch with mixed routing.** Seven variants today: `RowTapped`, `SettingsTapped`, and `RecentDiscussionsTapped` (#26) resolve to `navController.navigate(...)` directly; `CreateDiscussionTapped`, `LongPressFab`, `WorkspacePicked`, and `WorkspacePickerDismissed` (#22 / #221) forward into `vm.onEvent(event)` via a comma-grouped arm because each needs VM state mutation or a suspend-shaped side effect. The rule: events with no VM-side side effect stay routed at the destination; events that need a suspend or VM state mutation forward into `onEvent`. Comma-grouping the four VM-forwarded variants keeps the arm to one body and makes the nav-vs-VM split visually obvious.
- **`LaunchedEffect(vm) { vm.navigationEvents.collect { … } }` for one-shot nav events.** Sits alongside the `state` read inside `composable(Routes.CHANNEL_LIST)`. The `vm` key restarts the collector exactly when the VM identity changes (per `NavBackStackEntry` scope), which is the correct boundary for a one-shot channel. See [`channel-list-viewmodel.md`](./channel-list-viewmodel.md) for the `Channel<ChannelListNavigation>` + `receiveAsFlow()` shape on the emitter side.
- **Concrete navigation target built inline:** `"conversation_thread/${event.conversationId}"`. No `Routes.conversationThread(id)` helper — matches the navigation feature doc's "build inline until a second caller appears" rule.
- **No `popUpTo` / `launchSingleTop`.** Default `navigate(route)` semantics are correct: tapping a channel pushes the thread onto the back stack; back-press returns to the channel list. The only exceptional case in the graph is the `scanner` → `channel_list` transition.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-runtime-compose` (catalog: `androidx-lifecycle-runtime-compose`, reuses the `lifecycleRuntimeKtx = "2.6.1"` version-ref). Required for `collectAsStateWithLifecycle` — not pulled transitively by `lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx`, or the `koin-androidx-compose` chain. Three lifecycle artifacts now on the classpath (`-ktx`, `-viewmodel-ktx`, `-runtime-compose`) — they share `LifecycleOwner` ABIs and must stay on the same version-ref.
- **Koin compose:** `org.koin.androidx.compose.koinViewModel` (already on the classpath since #32 via `koin-androidx-compose`). Distinct from `org.koin.compose.koinInject` (the `scanner` destination's `AppPreferences` resolver) — `koinViewModel` is the right call for `ViewModel`-typed bindings because it routes through `LocalViewModelStoreOwner`.
- **Icons:** `androidx.compose.material:material-icons-core` (catalog: `androidx-compose-material-icons-core`, BOM-managed — no `version.ref`). The always-on icon set; supplies `Icons.Default.Settings` for the TopAppBar gear (#21). Don't reach for `material-icons-extended` (multi-MB megapack) for single-glyph needs — try `-core` first.
- **Strings:** `R.string.app_name` for the TopAppBar title (reused, defined since project init); `R.string.cd_open_settings` ("Open settings") for the gear's TalkBack `contentDescription`; `R.string.cd_new_discussion` ("New discussion") for the FAB's `combinedClickable.onClickLabel` (#22; reused as the tap-label by #221); `R.string.cd_long_press_fab_pick_workspace` ("Pick a workspace for the new discussion") for the FAB's `combinedClickable.onLongClickLabel` (#221); `R.string.channel_list_empty` ("Tap + to start a conversation") for the Empty body (#23); `R.string.recent_discussions_section_header` ("Recent discussions"), `R.string.see_all_discussions_label` ("See all discussions (%d)") and `R.string.cd_see_all_discussions` ("See all %d recent discussions") for the section (#69 — replaced the pill's two strings); `R.string.cd_pyrycode_logo` ("Pyrycode logo") for the TopAppBar `navigationIcon` (#68); `R.string.channels_section_header` ("Channels") for the section header (#68). A-11y content-description strings keep the `cd_` prefix; user-facing copy uses a `<screen>_<role>` shape.
- **Drawables:** `R.drawable.ic_pyry_logo` (since #68 — already in tree; the same vector asset Welcome's hero uses).

## Preview

Six `@Preview` composables, all `widthDp = 412` (matches the `ConversationRow.kt` previews from #17 for consistent device shape). All six reshaped in #69 — the `+ pill` naming and seed shape are gone; `Loaded/Empty + discussions` cover the populated-section case:

- `ChannelListScreenLoadedPreview` (`@Preview(name = "Loaded — Light", …)`) — three inline channels via the file-private `previewChannels(now)` helper varying `name`, `cwd` (one `DEFAULT_SCRATCH_CWD`, two real workspaces, one with `isSleeping = true`), and `lastUsedAt` (12m / 4h / 3d ago). `recentDiscussions = emptyList()`, `recentDiscussionsCount = 0` — section absent; baseline channel-list look (no orphan header).
- `ChannelListScreenLoadedWithDiscussionsPreview` (`@Preview(name = "Loaded + discussions — Light", …)`, #69) — same three channels + three sample discussions via `previewDiscussions(now)` (12m / 2h / 26h ago, varied names to exercise titleMedium truncation), `recentDiscussionsCount = 8`. Renders the full section as a trailing `LazyColumn` item: divider → header → 3 preview rows → See-all link `(8) →`. Canonical "matches Figma 15:8" preview for the populated-loaded case. Since #162 each preview row collapses to title-above-meta (no body) because `recentDiscussionLastMessages` defaults to `emptyMap()` here — the populated-body case is covered by `DiscussionPreviewRow`'s own previews, so this screen-level preview deliberately exercises the section's degrade-gracefully shape.
- `ChannelListScreenEmptyPreview` (`@Preview(name = "Empty — Light", …)`, #23) — `state = ChannelListUiState.Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0), onEvent = {}`. No sample data needed. Renders the TopAppBar above the centred empty-state copy and the FAB at `FabPosition.End`; section absent.
- `ChannelListScreenEmptyWithDiscussionsPreview` (`@Preview(name = "Empty + discussions — Light", …)`, #69) — `Empty(recentDiscussions = previewDiscussions(now), recentDiscussionsCount = 5)`. Section renders at the top of the body (above the centred empty-state copy); proves the section's "no orphan header" guard inverts correctly when discussions exist but channels don't.
- `ChannelListScreenLoadedDarkPreview` (`@Preview(name = "Loaded — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, …)`, #68; reshaped #69) — same data as the light loaded-with-discussions preview but in dark theme. Exercises the dark-scheme logo tint (`primary`), section header colour, the three avatar `*-container` palettes, and the discussion-preview-row meta line's `onSurfaceVariant @ 0.70f` contrast.
- `ChannelListScreenEmptyDarkPreview` (`@Preview(name = "Empty — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, …)`, #68) — dark counterpart of the bare-empty preview; `recentDiscussions = emptyList()`.

`Loading` and `Error` are still not previewed — their copy is a transient placeholder until designed visuals land.

## Edge cases / limitations

- **FAB long-press → `WorkspacePicker` (#221).** Long-pressing the FAB now opens the [`WorkspacePicker`](./workspace-picker.md) host sheet; on pick, `repository.createDiscussion(workspace = path)` runs and `ChannelListNavigation.ToThread(id)` fires on the same channel as the tap path. Tap behaviour from #22 (create-scratch-discussion → navigate) is unchanged. The previously-deferred "Phase 2 — Workspace picker is part of `ConversationThread` design" decision was walked back: the picker host (#220) is reusable across screens, and the FAB's gesture surface was the natural entry point. The Conversation Thread's own picker access points (empty-state chip from #137, overflow item from #208) consume the same host independently.
- **Press-elevation animation is lost** on the new manual-`Surface` FAB (#221). M3's `FloatingActionButton` lowers its elevation while held; the manual composition doesn't replicate the `InteractionSource` plumbing. The `combinedClickable` default ripple covers the user-facing feedback gap. Accepted Phase 0 tradeoff for unblocking long-press; revert to the native widget if a future BOM ships `onLongClick` natively.
- **FAB hidden in Loading and Error.** Conditional `state is Loaded || state is Empty`. AC specifically required Loaded and Empty; rendering in Loading risks a "tap before initial repository emission" race, and Error has no useful create target until recovery. Revisit if the design adds a "create from error" affordance.
- **No error/loading affordance on the create call itself.** `repository.createDiscussion()` is `suspend` but the fake never throws and resolves synchronously enough that no spinner is needed. Phase 4's `RemoteConversationRepository` adds both the error UI and the loading indicator together.
- **No `rememberLazyListState` / scroll-position persistence.** `LazyColumn` auto-saves scroll position within a single composition; `rememberSaveable(saver = LazyListState.Saver)` is the next step when a real bug surfaces (e.g. scroll resets after returning from thread). Defer until observed.
- **No retry affordance on `Error`.** `ChannelListEvent` has no `RetryClicked` variant. Per #45's spec, recovery requires a fresh subscription (leave the screen, wait 5s for `WhileSubscribed` to expire, re-enter). A retry button lands with the ticket that commits to designed error UI.
- **Instrumented test coverage since #99.** `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — six `@Test` methods covering the `TopAppBar` title + settings gear, each channel name in `Loaded`, all three event dispatches (`RowTapped(id)`, `SettingsTapped`, `CreateDiscussionTapped`), and the `Empty` placeholder. Constructs `ChannelListUiState` instances directly with a local `channel(id, name)` fixture builder and captures emitted events into a `mutableListOf<ChannelListEvent>` — no Koin, no `ChannelListViewModel`, no `FakeConversationRepository`. `Loading` / `Error` branches and the recent-discussions section / See-all link / `RecentDiscussionsTapped` event are intentionally **not** covered — evidence-based: no regressions observed there. Add a `@Test` when one does. #221 did NOT add an androidTest for the FAB long-press gesture or the `WorkspacePicker` integration — same posture as #22's decision; the `WorkspacePicker`'s own androidTest (#220) covers the host, and the screen-level wiring is a hoisted `visible` prop + `onPicked` / `onDismiss` pass-through.
- **No `flowOn(Dispatchers.IO)` anywhere in the chain.** `collectAsStateWithLifecycle` collects on Main (via the destination's `LifecycleOwner`); the VM is Main-dispatched; the fake repo's projection is pure CPU map manipulation. Phase 4's remote impl decides its own dispatcher internally.
- **No empty-state distinction between "never had a channel" and "had channels, all deleted".** Both render `Empty` → `"Tap + to start a conversation"`. The first ticket that needs the distinction extends `ChannelListUiState` (e.g. `Empty(reason: EmptyReason)`) or splits into a new variant.
- **Empty-state trigger is "no channels", not Plan.md's "no channels AND no discussions".** `ChannelListViewModel` observes both filters now (#26, widened in #69 to also carry the top-3 discussions), but the `Empty` variant still gates on `channels.isEmpty()` alone. A user with zero channels but non-zero discussions sees the empty copy *and* the inline recent-discussions section above it — the section is the secondary affordance, not a discussion-aware CTA. The product-level "promote one of your N discussions to a channel?" widening (deferred from #23) would still need a new `ChannelListUiState` variant.
- **Section placement inside vs. outside `LazyColumn` is intentional.** In `Loaded`, the section is a trailing `item(key = "recent-discussions-section")` so it scrolls with the channels (Figma `15:8` is a single scroll region). In `Empty`, no `LazyColumn` exists, so the section is a direct child of the outer `Column` above the centred empty-state `Box(weight=1f)`. The two placements share the same `RecentDiscussionsSection` composable; the divergence is purely about which parent owns it.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md) (LazyColumn + tap nav), [`../codebase/21.md`](../codebase/21.md) (TopAppBar + settings-gear wiring), [`../codebase/22.md`](../codebase/22.md) (FAB + one-shot nav channel), [`../codebase/23.md`](../codebase/23.md) (Empty-state copy + preview), [`../codebase/26.md`](../codebase/26.md) (Recent-discussions pill + Loaded/Empty body restructure + `RecentDiscussionsTapped` event), [`../codebase/68.md`](../codebase/68.md) (Figma polish — TopAppBar logo, leading avatars, "Channels" section header, dark previews), [`../codebase/69.md`](../codebase/69.md) (inline Recent-discussions section replaces the pill; widens `Loaded` / `Empty` with `recentDiscussions: List<Conversation>`; previews reshaped), [`../codebase/99.md`](../codebase/99.md) (instrumented Compose test class — six `@Test` methods covering structure + event dispatch), [`../codebase/162.md`](../codebase/162.md) (`RecentDiscussionsSection` forwards `state.recentDiscussionLastMessages` to each `DiscussionPreviewRow`; placeholder body resource retired), [`../codebase/221.md`](../codebase/221.md) (FAB long-press → `WorkspacePicker`; M3 `FloatingActionButton` widget replaced by manually-composed `Surface` + `combinedClickable` to escape the inner-clickable shadowing failure mode from #25; three new `ChannelListEvent` variants + `WorkspacePicker` host as Scaffold sibling)
- Specs: `docs/specs/architecture/46-channellistscreen-lazycolumn-tap-nav.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`, `docs/specs/architecture/22-channel-list-fab-new-discussion.md`, `docs/specs/architecture/23-channel-list-empty-state.md`, `docs/specs/architecture/26-recent-discussions-pill.md`, `docs/specs/architecture/68-channel-list-figma-polish.md`, `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/99-channel-list-screen-compose-tests.md`, `docs/specs/architecture/162-channel-list-discussion-preview-row-last-message.md`, `docs/specs/architecture/221-channel-list-fab-long-press-workspace-picker.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (state producer + `onEvent` reducer + `navigationEvents`; #26 widened `Loaded` / `Empty` to carry `recentDiscussionsCount`; #69 added `recentDiscussions: List<Conversation>` alongside; #161 added `recentDiscussionLastMessages: Map<String, Message>`; #221 added `workspacePickerVisible: Boolean = false`), [ConversationRow](./conversation-row.md) (per-channel-row primitive), [DiscussionPreviewRow](./discussion-preview-row.md) (per-preview-row primitive, #69; signature gained `lastMessage: Message?` in #162), [ConversationAvatar](./conversation-avatar.md) (leading bubble, #68), [WorkspacePicker](./workspace-picker.md) (host composable wired in #221 as a Scaffold sibling; first consumer of #220's host), [Navigation](./navigation.md) (host graph, route constants, destination wiring), [Dependency injection](./dependency-injection.md) (Koin VM binding)
- Downstream: #154 (walks `ConversationRow` back to the Figma 15:8 single-line shape — the workspace label from #19 and the leading sleep dot from #20 both removed; the sleeping dot reappears *after* the name, between the name and timestamp), follow-up Retry ticket (adds `ChannelListEvent.RetryClicked` + VM-side reducer), Phase 3 Settings (replaces `SettingsPlaceholder` body — gear wiring already in place since #21), Phase 4 (real backend behind the same `ConversationRepository` bind — zero screen change; adds error + loading UI for the create call), follow-up discussion-aware empty CTA (deferred from #23)
