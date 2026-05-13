# Spec — ChannelListScreen (LazyColumn + tap nav) (#46)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — the entire file. Confirms (a) `ChannelListUiState` is a top-level sibling of the VM (4 variants: `Loading`, `Empty`, `Loaded(channels)`, `Error(message)`) and (b) the VM exposes a single `val state: StateFlow<ChannelListUiState>` — no `onEvent` surface yet. **`ChannelListEvent` does not exist in this file and is intentionally not added here**: it lives next to the screen, since the VM doesn't consume events this ticket. Match the same in-file layout style for `ChannelListScreen.kt` (top-level sealed interface above the `@Composable`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:32-80` — `ConversationRow(conversation, lastMessage, onClick, modifier)`. Note `lastMessage: Message?` is nullable — when null, the row simply omits the supporting subtitle. The screen passes `lastMessage = null` for every row; per-row last-message data is not part of `ChannelListUiState.Loaded` and lives behind a follow-up ticket.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:66-119` — the existing `PyryNavHost` definition. The placeholder line `composable(Routes.ChannelList) { Text("Channel list placeholder") }` (line 105-107) is the exact block being replaced. The `Routes.ConversationThread` constant (`"conversation_thread/{conversationId}"`, line 137) is the navigation target; concrete targets are built inline at the call site as `"conversation_thread/$id"` per the navigation feature doc — there is no `Routes.conversationThread(id)` helper yet, and this ticket should not introduce one (single caller).
- `docs/knowledge/features/navigation.md:90-100` (Adding a route section) — confirms (a) screens take navigation as `() -> Unit` callbacks, not a `NavController`, and (b) concrete navigation targets for parameterized routes are built inline at the call site until a second caller appears. The screen-side `onEvent` lambda lives at the `composable(...)` destination; the screen Composable stays `NavController`-free.
- `docs/knowledge/features/channel-list-viewmodel.md:78-92` — the planned screen-side resolution pattern: `koinViewModel<ChannelListViewModel>()` + `vm.state.collectAsStateWithLifecycle()`. This ticket implements it. The Koin VM binding (`viewModel { ChannelListViewModel(get()) }`) already exists in `AppModule.kt:24`; no DI changes here.
- `gradle/libs.versions.toml` — verify `androidx-lifecycle-runtime-compose` is **not** in `[libraries]` today. It isn't: `koin-androidx-compose:4.0.4` brings `lifecycle-viewmodel-compose` transitively (sufficient for `koinViewModel<…>()`) but not `lifecycle-runtime-compose` (where `collectAsStateWithLifecycle` lives). This ticket adds one catalog entry + one `implementation(...)` line.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — payload type carried by `Loaded(channels)`. `Conversation.id` is the stable key for both `LazyColumn` `items(key = …)` and the navigation argument.
- `CLAUDE.md` (Conventions section) — "Stateless composables. Hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`." + "Sealed types for `UiState` and `Event`." — `ChannelListScreen` must take `(state, onEvent)` only; no `viewModel()` calls in the screen body.

## Context

`#45` shipped the read-only data path (`ChannelListUiState` + `ChannelListViewModel` exposing `StateFlow<ChannelListUiState>`, Koin-wired). The placeholder `Text("Channel list placeholder")` at the `channel_list` NavHost destination has been sitting since `#8`, blocking real navigation between the persistent-channels list and the conversation thread route.

This ticket is the first stateless UI consumer of a ViewModel in the codebase, and the first place a sealed `Event` type lands. Three coupling decisions land alongside the screen itself:

1. **`ChannelListEvent` sealed interface introduction.** Per `CLAUDE.md` ("Sealed types for `UiState` and `Event`"), every screen that handles user input through the `(state, onEvent)` shape needs a sibling `Event` sealed type. `#45` deliberately deferred this — there was no event to model. This ticket has exactly one event (row tap) and is therefore the right place.
2. **First `koinViewModel<…>()` + `collectAsStateWithLifecycle()` call site.** The feature doc for `ChannelListViewModel` already plans this shape; the missing piece is the `lifecycle-runtime-compose` artifact, which is not transitively pulled in by `koin-androidx-compose`. One catalog entry + one `implementation(...)` line.
3. **Choice not to introduce a ViewModel-side event reducer.** The PO body and technical notes are explicit: the NavHost destination translates `ChannelListEvent.RowTapped` directly into `navController.navigate(...)`. A `fun onEvent(event: ChannelListEvent)` on the VM would add a no-op pass-through with no behavioural effect. Wire it when there's a non-navigation event (retry, undo) that needs VM state to mutate.

All three additions are minimal — the catalog + build additions reuse the existing `lifecycleRuntimeKtx` version-ref, and the event type has a single variant.

## Design

### Screen + Event (new file)

Path: `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`.

Single file, three top-level declarations (one sealed interface + one public composable + one private preview composable). Symmetric with `ChannelListViewModel.kt` from `#45`, which colocates `ChannelListUiState` as a top-level sibling of the VM in the same file.

```kotlin
package de.pyryco.mobile.ui.conversations.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Text
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.ui.conversations.components.ConversationRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

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

@Composable
private fun CenteredText(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
```

Design notes:

- **`ChannelListEvent` lives next to the screen, not the VM.** The VM doesn't consume events this ticket. Placing the sealed type in `ChannelListScreen.kt` keeps the event surface adjacent to its only producer (`ConversationRow.onClick`). When a future ticket adds a VM-side reducer, the type moves into `ChannelListViewModel.kt` (or stays where it is — either works, since it's a `ui/conversations/list/` package member either way). This avoids a stranded file in this ticket.
- **`RowTapped(conversationId: String)` — single variant.** The AC names one event. Anticipated future variants (retry, archive-swipe, long-press menu) land with the tickets that need them. No `LongPressed` / `ArchiveSwiped` placeholders.
- **`@Composable fun ChannelListScreen(state, onEvent, modifier)`** — exactly the canonical stateless shape per CLAUDE.md. No `koinInject`, no `viewModel()`, no `LocalContext.current`, no `NavController` parameter. The destination block in `MainActivity` is the only place those resolve.
- **`when (state)` branches map directly onto the four `ChannelListUiState` variants.** Kotlin's exhaustiveness check on sealed interfaces enforces coverage at compile time — when `Phase 4` adds a fifth variant (e.g. `Stale`), the compiler points at this `when`.
- **Per-AC: Loading/Empty/Error each render a single `Text(...)` placeholder.** `CenteredText` is a private helper that wraps the `Text` in a centred `Box(Modifier.fillMaxSize())`. The AC permits a single `Text(...)`; centring it in a `Box` is not a spinner or illustration — it's a basic layout decision that keeps the page from looking broken before `#23` ships the real empty state.
- **`LazyColumn(items = state.channels, key = { it.id })`** — `key` is essential. Without it, recomposition would diff list items by position rather than identity; if the seed order shifts (e.g. on a new message bumping a channel up by `lastUsedAt`), every row below the moved item would re-compose unnecessarily. `Conversation.id` is the stable identity per the data-model contract.
- **`ConversationRow.lastMessage = null` for every row.** `ChannelListUiState.Loaded` carries `List<Conversation>` only — no per-channel `Message?` projection. Adding per-channel `lastMessage` data would expand `ChannelListUiState` and the VM projection, out of scope for this ticket and unrelated to "see channels, tap to open". The row's `supportingContent` is `null`-safe (line 67 of `ConversationRow.kt` is `lastMessage?.let { ... }`), so `null` renders as no subtitle. A follow-up ticket may extend `Loaded` to carry `Map<String, Message?>` or a `data class ChannelRow(val conversation: Conversation, val lastMessage: Message?)` — defer.
- **`Modifier` parameter at the screen entry point.** Conventional Compose contract; the destination block passes `Modifier` if the host wants to inject padding/scroll-behaviour, otherwise the default is fine. The current NavHost passes a `Modifier` to the host but not down to individual `composable { ... }` blocks; leave it that way (no destination-level `Modifier` change).
- **`@Preview` with 3 channels.** Single `@Preview`, light theme, hardcoded preview data. Three concrete `Conversation` instances inline in a `private val previewChannels = listOf(...)` keeps the preview self-contained. `widthDp = 412` (matching `ConversationRow.kt`'s previews) for consistent device shape. Per AC, the preview wraps a `Loaded` state — no need to preview `Loading`/`Empty`/`Error` (those are throwaway placeholders being replaced in `#23`).
- **No `rememberLazyListState`.** Saving scroll position across navigation is `rememberSaveable`-territory; `LazyColumn` already auto-saves position within a single composition. Add `rememberSaveable(saver = LazyListState.Saver) { LazyListState() }` only when a real bug surfaces (e.g. scroll resets after returning from thread). Defer.
- **No `Scaffold` / `TopAppBar`.** Out of scope per the PO body (`#21` is the TopAppBar, `#22` is the FAB, `#23` is the polished empty state). The screen is bare content; the NavHost destination renders it directly inside the existing outer `Scaffold` from `MainActivity`.

### `ChannelListEvent.RowTapped` → navigation translation

The NavHost destination in `MainActivity` is the translation point. The lambda receives a `ChannelListEvent` and dispatches on its variant; for `RowTapped`, it calls `navController.navigate("conversation_thread/$id")`. The string-format is inline, matching the navigation feature doc's "concrete targets built inline at the call site" rule (only one caller exists — no helper).

### `MainActivity.kt` NavHost destination edits

Path: `app/src/main/java/de/pyryco/mobile/MainActivity.kt`. Replace the existing 3-line placeholder block:

```kotlin
composable(Routes.ChannelList) {
    Text("Channel list placeholder")
}
```

with:

```kotlin
composable(Routes.ChannelList) {
    val vm = koinViewModel<ChannelListViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ChannelListScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is ChannelListEvent.RowTapped -> navController.navigate("conversation_thread/${event.conversationId}")
            }
        },
    )
}
```

Required import additions (alphabetically merged into the existing block):

- `androidx.compose.runtime.getValue` — already present (used by `produceState` at line 17); no change.
- `androidx.lifecycle.compose.collectAsStateWithLifecycle` — new.
- `de.pyryco.mobile.ui.conversations.list.ChannelListEvent` — new.
- `de.pyryco.mobile.ui.conversations.list.ChannelListScreen` — new.
- `de.pyryco.mobile.ui.conversations.list.ChannelListViewModel` — new.
- `org.koin.androidx.compose.koinViewModel` — new (`koinInject` is the existing import at line 33; `koinViewModel` is a sibling top-level fun in the same package, used distinctly for `ViewModel`-typed Koin resolution).

Notes:

- **`androidx.lifecycle.compose.collectAsStateWithLifecycle`** comes from `androidx.lifecycle:lifecycle-runtime-compose` (added below). Not the older `androidx.compose.runtime.collectAsState` — the lifecycle-aware variant pauses upstream collection when the lifecycle drops below `STARTED`, which is what makes `WhileSubscribed(5_000)` actually save work when the screen is backgrounded.
- **`koinViewModel<T>()`** resolves the VM via the existing `viewModel { ChannelListViewModel(get()) }` binding from `#45`'s `AppModule.kt`. The destination scope is the right place because every `composable(...)` block is a `NavBackStackEntry`-keyed scope, so the VM is bound to the navigation entry and tears down when the user navigates back from the channel list — which is exactly the right lifecycle for a per-screen VM.
- **`when (event) { is ChannelListEvent.RowTapped -> ... }`** — exhaustive on the sealed interface. When a future ticket adds `ChannelListEvent.RetryClicked`, the compiler points here. Inline `when` is the right shape — extracting a `private fun onChannelListEvent(event: ChannelListEvent)` adds a layer with no behavioural benefit for a single variant.
- **No `popUpTo` / `launchSingleTop`** on this navigation call. Default `navigate(route)` semantics are correct: tapping a channel pushes the thread onto the back stack; back-press returns to the channel list. The only exceptional navigation behaviour in the graph today is the `scanner` → `channel_list` transition (per the navigation feature doc); the row-tap is the canonical default case.

### `gradle/libs.versions.toml` — one new library entry

Add to `[libraries]`, placed directly under the existing `androidx-lifecycle-viewmodel-ktx` entry (line 24):

```toml
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
```

No new `[versions]` entry: reuses the existing `lifecycleRuntimeKtx = "2.6.1"` pin. The three lifecycle artifacts (`runtime-ktx`, `viewmodel-ktx`, `runtime-compose`) must stay version-locked — they share `LifecycleOwner` ABIs.

### `app/build.gradle.kts` — one new dependency line

Add inside `dependencies { }`, immediately after `implementation(libs.androidx.lifecycle.viewmodel.ktx)` (current line 53):

```kotlin
implementation(libs.androidx.lifecycle.runtime.compose)
```

Why this is needed: `collectAsStateWithLifecycle` lives in `androidx.lifecycle:lifecycle-runtime-compose`, not in the base `lifecycle-runtime-ktx` (which only carries the `LifecycleOwner` infrastructure) nor in `lifecycle-viewmodel-compose` (the `koinViewModel`-supporting artifact that `koin-androidx-compose` already brings transitively). Without this dependency the import doesn't resolve at compile time.

## State + concurrency model

- **Scope at destination:** the `composable(Routes.ChannelList) { ... }` block runs in the `NavBackStackEntry`'s composition scope. `koinViewModel<ChannelListViewModel>()` ties the VM lifetime to that entry — created on first entry, retained across configuration changes (rotation), cleared when the entry is popped from the back stack.
- **State collection:** `vm.state.collectAsStateWithLifecycle()` collects on the destination's `LifecycleOwner`. When the screen leaves the foreground (e.g. back-pressed, or the app is backgrounded while the screen is visible), collection pauses; combined with the VM's `WhileSubscribed(5_000)` upstream grace, the underlying repository flow stays subscribed for 5 seconds before being cancelled — which avoids re-collection thrash if the user immediately returns.
- **Dispatcher:** `collectAsStateWithLifecycle` collects on the Main thread by default (it dispatches on the `lifecycleScope` of the current `LifecycleOwner`, which runs on Main). The VM is already Main-dispatched; the data layer's projection is pure CPU work. No `flowOn` needed.
- **Recomposition seams:** `state` is a `ChannelListUiState` (a value-only sealed type with stable nested `Conversation` data classes). Compose's stability inference treats stable types as stable parameters and skips recomposition when references don't change. The `LazyColumn`'s `items(key = { it.id })` keys each row to `Conversation.id`, so when the channel list re-emits (e.g. seed reorder by `lastUsedAt`), only the rows whose `Conversation` values changed re-compose.
- **`onEvent` lambda stability:** the inline `{ event -> when (event) { ... } }` lambda captures `navController`, which is a stable reference returned by `rememberNavController()`. Compose's stability inference treats the lambda as stable — `ChannelListScreen` won't be recomposed on every parent recomposition just because of the lambda parameter. If at some point recomposition profiling shows otherwise, wrap the lambda in `remember(navController) { ... }` — but don't preemptively.

## Error handling

- **Source of failures:** the VM's `state` is the entire surface — `Error(message)` is a state variant, not an exception thrown to the screen. The screen's `when` already handles it via the third branch.
- **Surface:** `Text("Couldn't load channels: ${state.message}")` centred in a `Box`. The AC explicitly forbids error iconography ("no error iconography — full visuals are #23 and later"); a `Text` is the minimum the user-facing surface needs to differentiate "I have no channels" from "something broke". The phrasing concatenates the VM's already-sanitised message (per `#45`'s spec: never raw `e.toString()`, never class names) — safe to render verbatim.
- **No retry affordance.** `ChannelListEvent` has no `RetryClicked` variant. As `#45`'s spec notes, recovery requires a fresh subscription (leave the screen, wait 5s for `WhileSubscribed` to expire, re-enter). A retry button lands with whichever ticket commits to designed error UI — not this one.
- **No error from the screen itself.** The screen is read-only; the only side effect is `onEvent(...)`, and the destination's `when` handles every variant exhaustively. There is no `try/catch` in the screen.

## Testing strategy

**No new unit or instrumented tests in this ticket.** The AC mandates only `./gradlew assembleDebug` passes. The screen is stateless, with no business logic to assert beyond visual rendering; the VM is already unit-tested at the `state`-emission boundary by `ChannelListViewModelTest` (`#45`).

What this ticket does cover:

- **Compose `@Preview` with `Loaded(3 channels)`** — per AC. Verifies the `Loaded` branch + `LazyColumn` + `ConversationRow` integration renders at preview time in Android Studio. The preview function should be `private`, named e.g. `ChannelListScreenLoadedPreview`, wrap `PyrycodeMobileTheme(darkTheme = false)`, and pass `onEvent = {}`. Three preview `Conversation` instances inline: vary `name`, `cwd` (one `DefaultScratchCwd` to verify the workspace-label-omission path through `ConversationRow`, two real workspaces), and `lastUsedAt` so the trailing relative-time stamps look distinct.
- **`./gradlew assembleDebug`** — verifies the catalog entry, dependency line, imports, and the `MainActivity` NavHost replacement all compile against the existing graph.

What this ticket explicitly defers:

- **Instrumented `ComposeTestRule` tap-emit test.** The "tap a row → `onEvent(RowTapped(channelId))` fires" assertion is the natural unit test for the screen. It needs `androidx-compose-ui-test-junit4` (already in the catalog at line 32 but only wired to `androidTestImplementation` at line 61) and an `androidTest/` source set entry. The current convention is to defer instrumented tests until the first ticket that has multi-event logic to verify; a one-line `onEvent(RowTapped(channel.id))` is straightforward enough to verify by eye. The first ticket that adds a second event or non-trivial event-translation logic should add the instrumented test then.
- **VM-side `onEvent` reducer test.** No reducer exists. When one lands (e.g. `RetryClicked` → resubscribe), it gets its own VM-test extension.
- **Per-row last-message integration.** `lastMessage = null` for every row; no last-message rendering to verify. When `Loaded` gets extended to carry `Map<String, Message?>` (or similar), the row supporting-content path becomes worth testing.

Run order for the developer:

- `./gradlew assembleDebug` — must pass. Catches catalog/build/import wiring problems.
- `./gradlew test` — must still pass (the existing `ChannelListViewModelTest` and `FakeConversationRepositoryTest` remain green; this ticket touches neither).
- Open the new `@Preview` in Android Studio — visually confirms 3 rows render in a `LazyColumn`. Optional but cheap.

## Edit list (developer's checklist)

1. **`gradle/libs.versions.toml`** — add one `[libraries]` entry (`androidx-lifecycle-runtime-compose`) directly under `androidx-lifecycle-viewmodel-ktx`. No `[versions]` change.
2. **`app/build.gradle.kts`** — add `implementation(libs.androidx.lifecycle.runtime.compose)` immediately after the existing `androidx.lifecycle.viewmodel.ktx` line.
3. **`app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`** — new file. Top-level `sealed interface ChannelListEvent { data class RowTapped(val conversationId: String) }` + `@Composable fun ChannelListScreen(state, onEvent, modifier)` with the four-branch `when` + `private @Composable fun CenteredText(...)` helper + `private @Composable fun ChannelListScreenLoadedPreview()` + three inline preview `Conversation` instances.
4. **`app/src/main/java/de/pyryco/mobile/MainActivity.kt`** — add five imports (`androidx.lifecycle.compose.collectAsStateWithLifecycle`, `de.pyryco.mobile.ui.conversations.list.ChannelListEvent`, `de.pyryco.mobile.ui.conversations.list.ChannelListScreen`, `de.pyryco.mobile.ui.conversations.list.ChannelListViewModel`, `org.koin.androidx.compose.koinViewModel`), then replace the existing `composable(Routes.ChannelList) { Text("Channel list placeholder") }` block (lines 105-107) with the seven-line block in the **MainActivity.kt NavHost destination edits** section above.
5. **Run `./gradlew assembleDebug`** — must pass.
6. **Run `./gradlew test`** — must still pass (no new tests; existing tests unchanged).

## Open questions

None.

Two judgment calls worth flagging for review:

- **Centring the placeholder text vs left-aligning under a future TopAppBar.** Chose `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(...) }` for Loading/Empty/Error. `#23` will replace this with real visuals, so the lifetime of this code is short. Left-aligned (e.g. `Text(modifier = Modifier.padding(16.dp))`) would also satisfy the AC. Centring renders better in the bare-content state without a `TopAppBar`/`FAB`.
- **`koinViewModel<T>()` vs `koinViewModel<T>(viewModelStoreOwner = backStackEntry)`.** Compose Navigation 2.9+ auto-wires `LocalViewModelStoreOwner` to the current back-stack entry, so the bare `koinViewModel<ChannelListViewModel>()` call resolves to the correct entry-scoped store without the explicit `viewModelStoreOwner` argument. Confirmed against the `navigationCompose = "2.9.5"` pin in `libs.versions.toml`. If a future navigation refactor introduces nested graphs, the explicit form may be needed — defer.
