# Spec — ThreadScreen skeleton + nav route + ViewModel + LazyColumn placeholder (#126)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:192-198` — the existing `composable(Routes.CONVERSATION_THREAD)` block whose body (`Text("Conversation thread placeholder: $conversationId")`) this ticket replaces. **The route constant string and the `navArgument("conversationId")` declaration stay unchanged** — only the body of the lambda is rewritten.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:136-190` — the two destination blocks (`Routes.CHANNEL_LIST`, `Routes.DISCUSSION_LIST`) that already navigate to `conversation_thread/${event.conversationId}`. **Read once to confirm the call sites already exist; do not add new navigation edges.**
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:241` — `Routes.CONVERSATION_THREAD = "conversation_thread/{conversationId}"`; the constant the new destination keeps consuming.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:1-32` — current Koin module. The new `viewModel { ThreadViewModel(get()) }` line lands after the existing `viewModel { ArchivedDiscussionsViewModel(get()) }` (one-line edit).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:64-86` — the closest existing reference for the `Scaffold { TopAppBar(title, navigationIcon = back-arrow-IconButton) }` shape, including the imports for `Icons.AutoMirrored.Filled.ArrowBack` and `R.string.cd_back`. Mirror this shape; do **not** copy the `actions = { ... }` overflow menu — overflow is #139's scope.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt:45-115` — the canonical "VM with `StateFlow<UiState>` + sealed event arms; `BackTapped → Unit` no-op" pattern. **Departure point for this ticket:** ours has no event-driven side effect yet, so the screen signature uses a direct `onBack: () -> Unit` callback instead of `onEvent(BackTapped)` — see § Design / "Why no `ThreadEvent` sealed type yet" below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:40-71` — the `stateIn(viewModelScope, started = WhileSubscribed(5_000), initialValue = Loading)` shape. **Not directly used here** (this ticket has no upstream `Flow` to share) — referenced so the developer understands why `ThreadViewModel` looks dramatically simpler and what shape it grows into in #128/#145.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — `Conversation.id: String` is the type that flows through `SavedStateHandle["conversationId"]`. Read to confirm the type-system match.
- `app/src/main/res/values/strings.xml` — already contains `cd_back` (line 7); no new strings are needed in this ticket.
- `docs/specs/architecture/15-conversation-thread-placeholder-route.md` — the original placeholder spec. Its "Why not type-safe routes" section (lines on the route-constant policy) still applies; do **not** migrate to `@Serializable` routes inside this ticket.
- `docs/knowledge/features/navigation.md:13` — current vault note describing the route as "placeholder `Text(…)` … Phase 2 replaces the body with the real thread UI." This ticket is the first slice of that replacement; the documentation phase will update that line.
- `docs/knowledge/features/channel-list-viewmodel.md:36-71` — the `ChannelListViewModel` shape used to introduce the canonical Koin `viewModel { ChannelListViewModel(get()) }` binding. **The interesting departure:** `ThreadViewModel`'s sole constructor arg is `SavedStateHandle`, not a `ConversationRepository`. See § Design / Koin wiring for why `get()` resolves to `SavedStateHandle` automatically inside a `viewModel { }` block consumed via `koinViewModel<…>()` from a `NavBackStackEntry`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Figma frame `16:8` is the **complete future state** of the thread screen — TopAppBar with back arrow + title + overflow menu, scrolling message list (user/assistant bubbles, tool-call card, code block, session delimiter), status row, and composer. **This ticket delivers only the outer shell:** TopAppBar (back arrow + title only — no overflow `more_vert` icon), an empty reverse-layout `LazyColumn` for the body, and no status row or composer. The remaining surfaces land in #128–#140 and #145; visual polish + overflow menu in #139.

Two design tokens load-bearing for this ticket:

- **Title typography** (Figma node `16:14`): M3 `titleLarge` — the default `TopAppBar` slot is `titleLarge` already, so no explicit `style = MaterialTheme.typography.titleLarge` override is needed.
- **TopAppBar background**: `Schemes/surface` (`#101418` dark). The default `TopAppBar` is `colorScheme.surface`, so no `colors = TopAppBarDefaults.topAppBarColors(...)` override is needed for this ticket. (Figma uses a slight variant for the surface container; #139 will reconcile.)

The reverse-layout body, status row, and composer in the Figma frame are out of scope here; they are deliberately not part of the design tokens this ticket consumes.

## Context

`#15` landed the `conversation_thread/{conversationId}` route in November 2025 with a placeholder `Text(...)` body. Since then, `ChannelListEvent.RowTapped` (`#46`) and `DiscussionListEvent.RowTapped` (`#24`) plus the `CreateDiscussionTapped` one-shot navigation (`#22`) all wire to that route. Four real call sites are already live in `MainActivity.kt:142-143, 151-152, 169-170, 178-179` — every channel-list and discussion-list row tap already lands on the placeholder.

This ticket is the **first replacement slice**: it stands up the `ThreadScreen` composable, the `ThreadViewModel`, and the Koin binding so that downstream `feat(ui/thread):` work (#128 message bubble, #133 input bar, #134 connection banner, #135 session delimiter, #137/#138 empty states, #139 TopAppBar overflow + polish, #145 status row) has a stable host to plug into. The body stays empty (`items(emptyList())`) so all of those downstream tickets land additively without rewriting this skeleton.

The ticket's deliberate scope contract:

- **Route definition is frozen.** `Routes.CONVERSATION_THREAD = "conversation_thread/{conversationId}"` and its `navArgument("conversationId") { type = NavType.StringType }` are unchanged.
- **Call sites are frozen.** All four existing `navController.navigate("conversation_thread/${event.conversationId}")` sites in `MainActivity.kt` keep their literals (no helper extracted yet — that's a follow-up when a fifth caller appears).
- **No data loading.** No `ConversationRepository` call, no `observeMessages(...)`, no `observeConversation(...)`. The ViewModel only reads `conversationId` from `SavedStateHandle`.
- **No `onEvent` reducer yet.** Single direct `onBack: () -> Unit` callback. The `(state, onEvent)` shape arrives with #133's input bar (the first event that the VM owns).

## Design

### Files

Two new files, two edits.

**New:** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`
**New:** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`
**Edit:** `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — replace the body of `composable(Routes.CONVERSATION_THREAD) { backStackEntry -> ... }`.
**Edit:** `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — append one `viewModel { ThreadViewModel(get()) }` line.

Package `de.pyryco.mobile.ui.conversations.thread` matches the planned layout in `CLAUDE.md` (`ui/conversations/thread/`). It is **new** in this ticket — create the directory.

### `ThreadUiState` — `data class`, not `sealed interface`

Single variant, single field:

```kotlin
data class ThreadUiState(val conversationId: String)
```

**Why a `data class`, not a `sealed interface` with one variant.** The canonical ViewModel pattern in this codebase (`ChannelListUiState`, `DiscussionListUiState`, `ArchivedDiscussionsUiState`) is `sealed interface { Loading, Empty, Loaded, Error }` because each represents a real async data-load stage. This ticket has zero async surface: there's no repository call, no `combine`, no `stateIn(..., initialValue = Loading)`. The state is fully resolved synchronously off `SavedStateHandle` at construction time. A `sealed interface` with one variant would prescribe more shape than the behaviour supports and would force every downstream test to pattern-match a single arm.

**When does this widen?** The first downstream ticket that introduces a `Loading` / `Error` distinction (likely #128 once it subscribes to `observeMessages`) widens `ThreadUiState` to `sealed interface { Loading, Loaded(conversationId, messages, …) }`. At that point the `data class` becomes the `Loaded` variant. The migration is a single grep-replace at the (then) two call sites (preview, destination); pre-paying that cost here is over-engineering by the project's "Don't design for hypothetical future requirements" rule.

### `ThreadViewModel` — `SavedStateHandle`-only constructor

```kotlin
class ThreadViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    val state: StateFlow<ThreadUiState> = MutableStateFlow(
        ThreadUiState(conversationId = savedStateHandle.get<String>("conversationId").orEmpty())
    ).asStateFlow()
}
```

Signature notes:

- **Constructor takes `SavedStateHandle` only.** No `ConversationRepository`, no other dependencies — this ticket loads no data. Adding the repository now (in anticipation of #128) violates the "Don't design for hypothetical future requirements" principle; the next ticket adds it in one line.
- **`SavedStateHandle.get<String>("conversationId").orEmpty()`** matches the existing extraction shape at `MainActivity.kt:196` (`backStackEntry.arguments?.getString("conversationId").orEmpty()`). Compose Navigation guarantees the argument is present before the destination composes; the `.orEmpty()` is for the type system (returns `String`, not `String?`) so the title `Text(...)` and the `data class` field read cleanly. **Do not** add a `requireNotNull(...)` or `error("missing conversationId")` — the framework contract is the source of truth, and a defensive throw would crash exactly the path the framework guarantees can't fail.
- **`MutableStateFlow(...).asStateFlow()`** — not `stateIn(...)`, not `flow { … }.stateIn(...)`. There is no upstream cold flow to share, no async timing, no `WhileSubscribed` lifetime to negotiate. The state is a single immutable record set at construction. Reuse of the `StateFlow<UiState>` interface keeps the destination-block consumer (`val state by vm.state.collectAsStateWithLifecycle()`) identical to every other VM in the codebase, so the shape that lands here remains a drop-in replacement when #128 widens to `stateIn(...)`.
- **No `viewModelScope.launch`, no `Channel`, no `navigationEvents`.** Pure synchronous state. The first VM-owned side effect arrives in #133 (composer send) and the first one-shot nav event in a later ticket — neither in scope here.

**Why no `ThreadEvent` sealed type yet.** All VM-backed screens in the codebase use `(state: UiState, onEvent: (Event) -> Unit)` — but every one of them has at least one event the VM owns (`CreateDiscussionTapped`, `SaveAsChannelRequested`, `RestoreRequested`, `TabSelected`). This ticket has only a back-arrow press, and the back press has no VM-side side effect (it's pure `navController.popBackStack()`, same as `DiscussionListEvent.BackTapped -> Unit` and `ArchivedDiscussionsEvent.BackTapped -> Unit`). Introducing a sealed `ThreadEvent { BackTapped }` to hold one no-op arm would be ceremony; the AC explicitly prescribes `onBack: () -> Unit` instead. When the first VM-owned event lands (#133's `ComposerChanged` / `SendTapped` is the most likely first), the screen signature widens to `(state, onBack, onEvent)` *or* `BackTapped` folds into a then-introduced `ThreadEvent` sealed type — that's a design decision for the ticket that introduces the second callback, not this one.

### `ThreadScreen` — Scaffold + minimal TopAppBar + reverse `LazyColumn`

Signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Composition:

- `Scaffold(modifier = modifier, topBar = { ThreadTopBar(state.conversationId, onBack) }) { inner -> Body(inner) }`
- `ThreadTopBar` is a `private @Composable` (file-local, not exported) wrapping `TopAppBar(title = { Text(conversationId) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } })`. **No `actions = { ... }`** — overflow menu is #139.
- Body: `LazyColumn(modifier = Modifier.padding(inner).fillMaxSize(), reverseLayout = true) { items(emptyList<Unit>()) { _ -> } }`. The `<Unit>` type parameter is for compiler exhaustiveness; the body never runs.

Design points:

- **TopAppBar `title` shows the raw `conversationId`.** The ticket explicitly allows this fallback (AC3: "the conversation name (or the raw conversationId if no name is plumbed yet)") — since this ticket plumbs no data, the conversation name isn't available yet. When a later ticket adds the conversation lookup, the `ThreadUiState` widens with `val title: String` (or the VM resolves `name ?: id` and the `conversationId` field is renamed to `title`). For now, render `state.conversationId` directly. The title field type stays `String` (not `String?`) — the empty-string fallback from `SavedStateHandle.orEmpty()` keeps the AppBar non-crashing under any path.
- **`reverseLayout = true` is the load-bearing scaffolding decision.** New messages will land at index 0 and the user's natural reading flow is bottom-up. Establishing the reverse layout now (even with an empty `items` list) means #128's message bubble lands at `items(state.messages) { … }` with no further axis-flip work and no scroll-state migration. The screen will appear visually empty in this ticket regardless of layout direction; the reverse-layout choice is invisible until #128, but baking it in now avoids the worst kind of refactor (semantics flip mid-stream when consumers have started rendering).
- **No `verticalArrangement = Arrangement.Bottom`** override. The default is fine; `reverseLayout = true` already pins the first item to the bottom edge.
- **Modifier ordering inside the body:** `Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. **Do not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying inset).
- **No `Modifier.systemBarsPadding()`.** The outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`; the per-screen `Scaffold` adds its own `inner` for the AppBar. Both are applied; status-bar inset is already handled upstream.

### Previews

Two `@Preview` composables at the bottom of `ThreadScreen.kt`, mirroring the `DiscussionListScreen` shape:

- `ThreadScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { ThreadScreen(state = ThreadUiState(conversationId = "kitchenclaw refactor"), onBack = {}) }`. The seed value reproduces the Figma title visually.
- `ThreadScreenDarkPreview` — same shape with `darkTheme = true`. Verifies the AppBar `Schemes/surface` token used in the Figma design (the `Schemes/surface` value `#101418` is the dark-mode surface; the AppBar should render against it without explicit color overrides).

No preview for an empty `conversationId` — that path is not user-reachable (Compose Navigation guarantees presence) and the title row would collapse to a one-line empty `Text("")` which adds no visual coverage.

### Koin wiring

Two edits.

`app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — append after `viewModel { ArchivedDiscussionsViewModel(get()) }`:

```kotlin
viewModel { ThreadViewModel(get()) }
```

And add the import: `import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel`.

**Why `get()` resolves to `SavedStateHandle`.** In Koin 4.x (the codebase is on 4.0.4 per `gradle/libs.versions.toml:17`), `viewModel { ... }` blocks invoked via `koinViewModel<T>()` from `org.koin.androidx.compose` (already on classpath via `libs.koin.androidx.compose`) auto-provide the current `LocalViewModelStoreOwner`'s `SavedStateHandle`. Inside the destination block in `MainActivity`, `LocalViewModelStoreOwner` is the `NavBackStackEntry` (Compose Navigation 2.9+ wires this automatically), which is both a `ViewModelStoreOwner` and a `SavedStateRegistryOwner`. So `get<SavedStateHandle>()` inside the `viewModel { }` block resolves to the back-stack entry's `SavedStateHandle`, which is pre-populated with the `navArgument("conversationId")` value. No `koinNavViewModel<T>()` import is needed — that's the KMP-compose artifact (`koin-compose-viewmodel-navigation`), which is **not** on classpath. The existing `koinViewModel<T>()` from `koin-androidx-compose` is the right call.

No new dependency. No new entry in `gradle/libs.versions.toml`. No catalog edits.

### Destination block edit

`app/src/main/java/de/pyryco/mobile/MainActivity.kt:192-198` currently reads:

```kotlin
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) { backStackEntry ->
    val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
    Text("Conversation thread placeholder: $conversationId")
}
```

Replace with:

```kotlin
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

Design points:

- **The `backStackEntry ->` lambda parameter goes away.** The `SavedStateHandle` path comes through the Koin `viewModel { }` block, not through `backStackEntry.arguments`. Eliminating the inline `backStackEntry.arguments?.getString(...)` is the actual seam this ticket establishes — the destination block no longer parses path arguments by hand; the VM does.
- **`route = Routes.CONVERSATION_THREAD` and `arguments = listOf(navArgument(...))` are unchanged.** Both AC2 and the carry-over invariant from #15. The route literal `"conversation_thread/{conversationId}"` is the same string; the four call sites that build `"conversation_thread/$id"` keep working with zero changes.
- **Add the imports** to `MainActivity.kt`:
  - `import de.pyryco.mobile.ui.conversations.thread.ThreadScreen`
  - `import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel`
  - `androidx.compose.material3.Text` may become unused after this edit — check and remove if so. Same for `androidx.navigation.compose.NavType` and `androidx.navigation.navArgument` (used) and any other previously-needed-only-by-the-placeholder symbol. The `Text` import is the prime candidate for removal; verify ktlint/Android Studio doesn't flag a leftover.

## State + concurrency model

- **No `viewModelScope.launch`.** `ThreadViewModel.state` is constructed synchronously off `SavedStateHandle`; no coroutine is launched, no dispatcher is touched.
- **No `WhileSubscribed(5_000)` configuration.** There is no shared cold-flow upstream; `MutableStateFlow(...).asStateFlow()` is hot from construction.
- **`StateFlow<ThreadUiState>` exposes `.value` immediately at composition.** `collectAsStateWithLifecycle()` at the destination snapshots the value synchronously on first composition — no `Loading` frame.
- **`SavedStateHandle` survives process death.** The `conversationId` argument is re-injected into the back-stack entry's `SavedStateHandle` automatically by Compose Navigation on process recreation. The VM constructor reads it the same way on both fresh creation and restoration paths; no `init { }` block needed.
- **Configuration changes** (rotation) re-use the existing `ViewModel` because `koinViewModel<…>()` routes through `LocalViewModelStoreOwner`, which is the `NavBackStackEntry`. The `StateFlow` value survives rotation because the VM survives rotation.
- **`viewModelScope` is unused in this ticket**, but the VM extends `ViewModel()` so future tickets can launch into it without re-architecting.

## Error handling

N/A for this ticket.

- **No network, no I/O, no parsing.** `SavedStateHandle.get<String>(...)` returns `null` if the key is absent; `.orEmpty()` collapses that to `""`. No exception path.
- **No `try/catch`, no `.catch { }`.** There is no upstream `Flow` to wrap.
- **Defensive throws are forbidden.** Compose Navigation guarantees the `conversationId` path argument is present before the destination composes — `requireNotNull(...)` or `error(...)` would assert against the framework's own contract.

When #128 lands and `ThreadViewModel` starts subscribing to `repository.observeMessages(conversationId)`, the `Error(message)` arm of a then-widened `sealed interface ThreadUiState` arrives along with that subscription. Not in scope here.

## Testing strategy

### `ThreadViewModelTest` — JVM unit test

New file: `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`.

Three tests, written in the same shape as `DiscussionListViewModelTest`:

1. **`state_carriesConversationIdFromSavedStateHandle`** — construct `SavedStateHandle(initialState = mapOf("conversationId" to "abc-123"))`, build `ThreadViewModel(handle)`, assert `vm.state.value == ThreadUiState(conversationId = "abc-123")`. Pins the load-bearing AC ("`ThreadUiState` carries `conversationId: String` read from `SavedStateHandle`").
2. **`state_isImmediatelyAvailableWithoutSubscriber`** — same setup; assert `vm.state.value` is readable synchronously *without* a launched `collect { }`. Pins the "no Loading frame" property (distinguishes this VM's hot `MutableStateFlow` from the cold-flow `stateIn(...)` shape used elsewhere).
3. **`state_collapsesAbsentConversationIdToEmptyString`** — construct `SavedStateHandle(initialState = emptyMap())` (no `conversationId` key), build the VM, assert `vm.state.value == ThreadUiState(conversationId = "")`. Pins the `.orEmpty()` fallback. Path not user-reachable in production but the property is observable and matches the destination-side extraction's defensive shape.

No `Dispatchers.setMain(...)` / `runTest` ceremony is strictly required — the VM does not launch any coroutine, so the test reads `vm.state.value` synchronously. Use plain JUnit `@Test` bodies (no `runTest { }`). Class-level annotations match the existing test files' style (no `@OptIn(ExperimentalCoroutinesApi::class)` — not needed).

`SavedStateHandle` lives in `androidx.lifecycle:lifecycle-viewmodel-savedstate`. Verify the artifact is present on `testImplementation` (it's transitively pulled in via `lifecycle-viewmodel-ktx` and `lifecycle-viewmodel-compose`); if a test-only artifact is missing, add it to `gradle/libs.versions.toml` and surface a one-line note in the PR description rather than papering over with a fake-handle subclass.

### Compose UI test for `ThreadScreen` — out of scope

The codebase has no Compose UI test infrastructure today (no `androidTest` directory for the existing list/detail screens, no `ComposeTestRule` setup, no Robolectric on `testImplementation`). Introducing it for a placeholder skeleton is over-scope — and would burn turns on tooling, not on the feature.

Visual verification of the AC8 smoke path ("tapping any channel or discussion row lands on `ThreadScreen` without a crash; the placeholder TopAppBar is visible with the conversation identifier; the body is empty") is by:

1. `./gradlew assembleDebug` + `./gradlew installDebug` on a connected device/emulator.
2. Manual tap: channel-list row → land on `ThreadScreen`; AppBar shows the conversation id; back arrow returns to the channel list.
3. Manual tap: discussion-list row → same.
4. Manual tap: FAB → create discussion → land on `ThreadScreen` via the `Channel`-backed nav event; AppBar shows the new discussion's id.

The developer reports the smoke check in the PR description; code-review treats it as the AC8 acceptance signal.

### Build + lint

`./gradlew assembleDebug` must pass. `./gradlew lint` must pass with no new warnings — the empty `items(emptyList<Unit>()) { _ -> }` body should not trigger lint; if it does (e.g. an "unused lambda parameter" warning), drop the trailing `{ _ -> }` and use the no-arg `items(emptyList<Unit>())` form whose signature requires a lambda — confirm at developer time which compiles cleanly.

`./gradlew test` runs the new `ThreadViewModelTest` alongside existing tests; all green.

## Open questions

- **Title text wording.** AC3 says "the conversation name (or the raw conversationId if no name is plumbed yet)". Since no name is plumbed, the AppBar renders `Text(state.conversationId)` — the raw id string with no prefix like "Conversation:" or "Thread:". Confirm this matches code-review's reading; if reviewers want a prefix, a one-character edit moves it to `Text("Thread · ${state.conversationId}")`. Default to bare id (matches the Figma `16:14` content, which is just `kitchenclaw refactor` with no chrome).
- **Import cleanup after edit.** After removing `Text("Conversation thread placeholder: …")` from `MainActivity.kt`, the `import androidx.compose.material3.Text` line may become unused — Android Studio / ktlint should auto-flag. Verify and delete; do not leave a dead import. No other imports should rot.
