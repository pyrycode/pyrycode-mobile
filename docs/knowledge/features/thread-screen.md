# Thread screen

Outer shell for the conversation thread at the `conversation_thread/{conversationId}` route. First replacement slice for the #15 placeholder `Text(...)`: ships the `ThreadScreen` composable, the `ThreadViewModel`, and the Koin binding so downstream `feat(ui/thread):` work (#128 message bubble, #133 input bar, #134 connection banner, #135 session-boundary delimiter, #137/#138 empty states, #139 TopAppBar overflow + polish, #145 status row) can land additively without rewriting the skeleton.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/`). Files: `ThreadScreen.kt`, `ThreadViewModel.kt`. Figma reference frame: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — only the TopAppBar (back arrow + title; **no** `more_vert` overflow yet) and the empty reverse-layout `LazyColumn` shell are in scope here; the message list, status row, and composer in the same frame are deferred to the downstream tickets above.

## What it does

Renders the chrome of the thread surface — a `Scaffold` with a minimal back-arrow `TopAppBar` over an empty `LazyColumn(reverseLayout = true)` body — and reads the `conversationId` path argument off `SavedStateHandle` through `ThreadViewModel`. No data loading, no message rendering, no input bar yet; the body is `items(emptyList<Unit>()) { }` so the screen renders as a titled empty surface. Tapping the back arrow pops the nav back stack via an `onBack: () -> Unit` callback supplied by the destination block.

## Shape

```kotlin
// ThreadViewModel.kt
data class ThreadUiState(
    val conversationId: String,
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val state: StateFlow<ThreadUiState> =
        MutableStateFlow(
            ThreadUiState(
                conversationId = savedStateHandle.get<String>("conversationId").orEmpty(),
            ),
        ).asStateFlow()
}

// ThreadScreen.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { ThreadTopBar(title = state.conversationId, onBack = onBack) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            reverseLayout = true,
        ) {
            items(items = emptyList<Unit>()) { }
        }
    }
}
```

`ThreadUiState` is a **`data class`, not a `sealed interface` with one variant.** The canonical pattern in this codebase (`ChannelListUiState`, `DiscussionListUiState`, `ArchivedDiscussionsUiState`) is sealed `Loading | Empty | Loaded | Error` precisely because each represents a real async data-load stage; this screen has zero async surface today (no repository call, no `combine`, no `stateIn(..., initialValue = Loading)`). State is fully resolved synchronously off `SavedStateHandle` at construction time. The first downstream ticket that introduces `Loading` / `Error` distinctions (likely #128 once it subscribes to `observeMessages`) is the one that widens `ThreadUiState` to a sealed envelope; the current `data class` becomes the `Loaded` variant via grep-replace at the two call sites (preview + destination).

`ThreadScreen`'s signature is **`(state, onBack, modifier)` — no `onEvent` lambda yet.** Every other VM-backed screen in the codebase uses `(state: UiState, onEvent: (Event) -> Unit)` because each has at least one event the VM owns (`CreateDiscussionTapped`, `SaveAsChannelRequested`, `RestoreRequested`, `TabSelected`). This ticket has only the back-press, and the back-press has no VM-side side effect (pure `navController.popBackStack()` at the destination). Introducing a sealed `ThreadEvent { BackTapped }` arm to hold one no-op variant would be ceremony. The signature widens to `(state, onBack, onEvent)` — *or* `BackTapped` folds into a then-introduced `ThreadEvent` — when the first VM-owned event lands (#133's `ComposerChanged` / `SendTapped` is the most likely first); that's a design decision for the ticket that introduces the second callback.

## How it works

### `SavedStateHandle.get<String>("conversationId").orEmpty()`

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes, so the only realistic path through `SavedStateHandle.get<String>(...)` returns the live id. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the `Text(state.conversationId)` title is non-null at every callsite. Defensive throws (`requireNotNull(...)` / `error("missing conversationId")`) are explicitly forbidden — they would assert against the framework's own presence contract and crash exactly the path the framework guarantees can't fail. The pre-#126 destination block at `MainActivity.kt:196` extracted the id with the same shape (`backStackEntry.arguments?.getString("conversationId").orEmpty()`); #126 moves the extraction one layer into the VM, eliminating the inline arg-parse in the `composable { }` lambda.

### `MutableStateFlow(...).asStateFlow()` — hot from construction, no `stateIn`

The VM exposes a `StateFlow<ThreadUiState>` consumed by the destination via `val state by vm.state.collectAsStateWithLifecycle()`, identical to every other VM in the codebase. The construction shape is **`MutableStateFlow(...).asStateFlow()`**, not `stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`. There is no upstream cold flow to share, no `Loading` initial frame to negotiate, no async timing — the state is a single immutable record set at construction and is hot from line 1. `collectAsStateWithLifecycle()` snapshots `.value` synchronously on first composition; the screen never sees a `Loading` frame because there isn't one. Picking `MutableStateFlow` over `stateIn` here is deliberate; the surface is interchangeable so when #128 widens to `combine(observeMessages, …).stateIn(...)` the destination consumer pattern is byte-identical.

### No `viewModelScope.launch`, no `Channel`, no `navigationEvents`

Pure synchronous state. The constructor reads `SavedStateHandle`, builds one `ThreadUiState`, wraps it in a `MutableStateFlow`, and returns. `viewModelScope` is unused in this ticket; the VM extends `ViewModel()` so future tickets can `launch` into it without re-architecting. The first VM-owned side effect arrives in #133 (composer send); the first one-shot nav event in a later ticket — neither in scope here.

### `LazyColumn(reverseLayout = true)` — established now, not when #128 lands

The body shape is `items(emptyList<Unit>()) { }` inside a `LazyColumn` with `reverseLayout = true`. The reverse layout is invisible until messages render — `reverseLayout` only affects the visual axis once there's content to flip — but baking it in now means #128's `items(state.messages) { … }` lands with no further axis-flip work and no scroll-state migration. Establishing the layout direction with consumers already rendering is the worst kind of refactor (semantics flip mid-stream); paying the no-op cost here is cheap. The `<Unit>` type parameter is for compiler exhaustiveness; the lambda body never runs. No `verticalArrangement = Arrangement.Bottom` override — `reverseLayout = true` already pins the first item to the bottom edge.

### TopAppBar — Schemes/surface defaults, no overrides

`ThreadTopBar` is a `private @Composable` (file-local; not exported) wrapping `TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, R.string.cd_back) } })`. **No `actions = { ... }`** — overflow menu is #139's scope. The Figma frame (`16:14`) specifies `titleLarge` typography and `Schemes/surface` (`#101418` dark) for the AppBar background; both are M3 defaults for `TopAppBar`, so no explicit `style = MaterialTheme.typography.titleLarge` and no `colors = TopAppBarDefaults.topAppBarColors(...)` override is needed. #139 reconciles the surface-container variant.

The `title` text renders the **raw `conversationId`** until a later ticket plumbs the conversation lookup — AC §3 explicitly allows this fallback ("the conversation name (or the raw conversationId if no name is plumbed yet)"). When a later ticket adds the conversation projection, `ThreadUiState` widens with a `title: String` field (or the VM resolves `name ?: id` and the existing field is renamed); for now, the AppBar title is the bare id string with no prefix like "Conversation:" or "Thread:". The empty-string fallback from `SavedStateHandle.orEmpty()` keeps the AppBar non-crashing under any path.

`R.string.cd_back` is reused — the same string that backs the `DiscussionListScreen` / `ArchivedDiscussionsScreen` / `SettingsScreen` back-arrows. No new strings.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied, so the status-bar inset is already handled upstream.

## Wiring

### Koin binding

```kotlin
// di/AppModule.kt
viewModel { ThreadViewModel(get()) }
```

`get()` resolves to `SavedStateHandle` automatically when the `viewModel { }` block is consumed via `koinViewModel<…>()` from `org.koin.androidx.compose` inside a `NavBackStackEntry` scope. Compose Navigation 2.9+ wires `LocalViewModelStoreOwner` to the back-stack entry, which is both a `ViewModelStoreOwner` and a `SavedStateRegistryOwner`; Koin 4.0.4 (the version pinned in `gradle/libs.versions.toml:17`) auto-provides the entry's `SavedStateHandle` into the `viewModel { }` factory. The handle is pre-populated with the `navArgument("conversationId")` value because the back-stack entry's `arguments` `Bundle` is the source `SavedStateHandle` reads from on first construction.

No new dependency. The existing `koinViewModel<T>()` from `koin-androidx-compose` (already on classpath via `libs.koin.androidx.compose`) is the right call site — **not** `koinNavViewModel<T>()`, which lives in the KMP-compose artifact `koin-compose-viewmodel-navigation` (not on classpath, and not needed here).

### Destination block

```kotlin
// MainActivity.kt:193-203
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
    )
}
```

The `Routes.CONVERSATION_THREAD = "conversation_thread/{conversationId}"` constant and the `navArgument("conversationId") { type = NavType.StringType }` declaration are **unchanged from #15**. The four existing call sites that build `"conversation_thread/$id"` and call `navController.navigate(...)` (`MainActivity.kt:142-143, 151-152, 169-170, 178-179`) keep working with zero changes — every channel-list and discussion-list row tap, plus the post-`createDiscussion` one-shot nav event, lands on the new screen with no nav-graph edits.

The `backStackEntry ->` lambda parameter is **gone** post-#126. The path-argument extraction shape `backStackEntry.arguments?.getString("conversationId").orEmpty()` no longer appears at the destination; the `SavedStateHandle` route through Koin's `viewModel { }` block now owns it. Eliminating the inline `arguments?.getString(...)` parse is the actual seam #126 establishes — the destination block consumes the VM's already-decoded state instead of parsing path arguments by hand.

## Configuration

- **Dependencies:** no new entries. `androidx.lifecycle:lifecycle-viewmodel-savedstate` is transitively present via `lifecycle-viewmodel-ktx` + `lifecycle-viewmodel-compose`; `org.koin:koin-androidx-compose` (catalog: `libs.koin.androidx.compose`) is on classpath since #46; `androidx.navigation:navigation-compose 2.9+` wires `LocalViewModelStoreOwner` to the `NavBackStackEntry` automatically. No `gradle/libs.versions.toml` edits.
- **Strings:** none added. `R.string.cd_back` is reused.
- **State survives process death.** The back-stack entry's `SavedStateHandle` is re-populated with the `navArgument` value on process recreation; the VM constructor reads `conversationId` the same way on both fresh creation and restoration paths. No `init { }` block needed.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — three plain JUnit 4 tests, no `runTest`, no `Dispatchers.setMain`. The VM launches no coroutine, so `vm.state.value` reads synchronously off the underlying `MutableStateFlow`:

1. **`state_carriesConversationIdFromSavedStateHandle`** — construct `SavedStateHandle(initialState = mapOf("conversationId" to "abc-123"))`, build `ThreadViewModel(handle)`, assert `vm.state.value == ThreadUiState(conversationId = "abc-123")`. Pins the load-bearing AC ("`ThreadUiState` carries `conversationId: String` read from `SavedStateHandle`").
2. **`state_isImmediatelyAvailableWithoutSubscriber`** — same setup; assert `vm.state.value.conversationId == "xyz-9"` is readable synchronously *without* a launched `collect { }`. Pins the "no Loading frame" property that distinguishes this VM's hot `MutableStateFlow` from the cold-flow `stateIn(...)` shape every other VM uses.
3. **`state_collapsesAbsentConversationIdToEmptyString`** — construct `SavedStateHandle(initialState = emptyMap())` (no `conversationId` key); assert `vm.state.value == ThreadUiState(conversationId = "")`. Pins the `.orEmpty()` narrowing. Path not user-reachable in production (the framework contract guarantees presence), but the property is observable and matches the pre-#126 destination-side extraction's defensive shape.

No instrumented Compose test for `ThreadScreen`. The codebase has no `androidTest` infrastructure for thread/list screens beyond the existing fixtures, and adding `createComposeRule()` plumbing for an empty skeleton would be tooling-burn for zero behavioural coverage. Visual verification of AC8 (smoke path) is by `./gradlew assembleDebug` + `./gradlew installDebug` + manual tap from channel-list / discussion-list / FAB-create-discussion paths.

## Previews

Two `@Preview`s at the bottom of `ThreadScreen.kt`:

- **`ThreadScreenLightPreview`** — `PyrycodeMobileTheme(darkTheme = false) { ThreadScreen(state = ThreadUiState(conversationId = "kitchenclaw refactor"), onBack = {}) }`. The `"kitchenclaw refactor"` seed reproduces the Figma `16:14` title literal.
- **`ThreadScreenDarkPreview`** — same seed with `darkTheme = true`. Verifies the AppBar `Schemes/surface` `#101418` token used in the Figma design renders against the dark theme without explicit color overrides.

No preview for an empty `conversationId` — that path is not user-reachable and the title row would collapse to a one-line empty `Text("")` adding no visual coverage.

## Edge cases / limitations

- **No data loading.** No `ConversationRepository` call, no `observeMessages(...)`, no `observeConversation(...)`. The VM only reads `conversationId` from `SavedStateHandle`. #128 introduces the first data-layer wiring.
- **Title is the raw id string.** Until a conversation lookup lands (Phase 2), the AppBar shows the unformatted `conversationId` — UUIDs, slugs, or whatever the upstream emits. Acceptable per AC §3; the empty-string fallback from `.orEmpty()` keeps the AppBar non-crashing under any path.
- **Body is permanently empty.** `items(emptyList<Unit>()) { }` renders no rows. The reverse-layout flag is invisible until #128's message bubble lands.
- **No overflow menu, no input bar, no status row, no connection banner.** Each is its own ticket (#139 / #133 / #145 / #134). Deliberately additive.
- **No `Modifier.systemBarsPadding()` on the per-screen `Scaffold`.** Outer `MainActivity` `Scaffold` already passes `innerPadding`; per-screen `Scaffold` adds its own `inner` for the AppBar. Status-bar inset is already handled upstream — don't add it again.

## Related

- Ticket notes: [`../codebase/126.md`](../codebase/126.md), [`../codebase/15.md`](../codebase/15.md) (the placeholder route this slice replaces)
- Specs: `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`
- Upstream: [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination block consumes), [Dependency injection](dependency-injection.md) (Koin `viewModel { ThreadViewModel(get()) }` binding)
- Downstream (each replaces a body slot here, not the chrome): #128 message bubble (widens `ThreadUiState` to sealed `Loading | Loaded(messages, …) | Error`, subscribes to `repository.observeMessages(conversationId)`), #133 input bar (introduces the first `ThreadEvent` and the `onEvent` lambda), #134 connection banner, #135 session-boundary delimiter, #137/#138 empty states, #139 TopAppBar overflow + final visual polish, #145 status row
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the complete future state; this slice ships only the outer shell)
