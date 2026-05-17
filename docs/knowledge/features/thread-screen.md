# Thread screen

Outer shell for the conversation thread at the `conversation_thread/{conversationId}` route. Skeleton landed in [#126](../codebase/126.md) (route + VM + empty `LazyColumn`); the TopAppBar slot was promoted from placeholder to the real Figma `16:8` chrome in [#139](../codebase/139.md); the `bottomBar` slot was filled with the composer in [#188](../codebase/188.md) — see [Thread input bar](thread-input-bar.md); the [`ConnectionBanner`](connection-banner.md) was wired between the TopAppBar and the message list in [#201](../codebase/201.md) (split from #197, originally #134). Remaining downstream `feat(ui/thread):` work (#128 message bubble, #135 session-boundary delimiter, #137/#138 empty states, #140 overflow menu, #141 rename dialog, #145 status row) lands additively without rewriting the skeleton.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/`). Files: `ThreadScreen.kt`, `ThreadTopAppBar.kt`, `ThreadInputBar.kt`, `ThreadViewModel.kt`. The [`ConnectionBanner`](connection-banner.md) it consumes lives one package over at `ui/conversations/components/ConnectionBanner.kt`. Figma reference frame: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — the TopAppBar region (back arrow + title + `more_vert` overflow), the empty reverse-layout `LazyColumn` shell, the composer (subframe `16:61`), and the banner slot between them are in scope here; the message list, status row, and other body decorations in the same frame are deferred to the downstream tickets above.

## What it does

Renders the chrome of the thread surface — a `Scaffold` with a real Figma-matched `TopAppBar` (back icon + tappable title + overflow icon) over a `Column { ConnectionBanner; LazyColumn(reverseLayout = true) }` body and a `ThreadInputBar` composer in the `bottomBar` slot — and resolves the conversation's `displayName` + the global `ConnectionState` from the repository / source via `ThreadViewModel`. No message rendering yet; the `LazyColumn` body is `items(emptyList<Unit>()) { }` so the screen renders as a titled empty surface with the banner above and a working composer below. Tapping the back arrow pops the nav back stack via an `onBack: () -> Unit` callback. Typing into the composer and tapping send (or the IME `Send` action) routes through `onSendMessage: (String) -> Unit` into `ThreadViewModel.sendMessage` and onward to `ConversationRepository.sendMessage`. Tapping the banner in the `Offline` state invokes `onRetry: () -> Unit`, bound to `ThreadViewModel.retry()` which launches `ConnectionStateSource.retry()` on `viewModelScope` (Phase-2 no-op; Phase-4 swaps the binding without touching the VM or the screen). Under `Connected` (the steady state the fake always reports) the banner short-circuits to zero height so the screen looks pre-#201 byte-identical. Tapping the title and tapping the overflow each invoke a separate lambda (`onTitleClick`, `onOverflowClick`) — both default to `{}` no-ops; #141 (rename dialog) and #140 (overflow menu) replace the defaults at the destination block when they land.

## Shape

```kotlin
// ThreadViewModel.kt
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,         // private val since #188
    private val connectionStateSource: ConnectionStateSource, // new in #201
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    val state: StateFlow<ThreadUiState> =
        repository
            .observeConversations(ConversationFilter.All)
            .map { list ->
                val conv = list.firstOrNull { it.id == conversationId }
                ThreadUiState(
                    conversationId = conversationId,
                    displayName = conv?.displayName() ?: conversationId,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    ThreadUiState(
                        conversationId = conversationId,
                        displayName = conversationId,
                    ),
            )

    val connectionState: StateFlow<ConnectionState> =     // new in #201
        connectionStateSource
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionState.Connected,
            )

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(state.value.conversationId, text)
        }
    }

    fun retry() {                                          // new in #201
        viewModelScope.launch { connectionStateSource.retry() }
    }
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

// ThreadScreen.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,         // new in #188, no default
    connectionState: ConnectionState,        // new in #201, no default
    onRetry: () -> Unit,                     // new in #201, no default
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            ThreadTopAppBar(
                title = state.displayName,
                onBack = onBack,
                onTitleClick = onTitleClick,
                onOverflowClick = onOverflowClick,
            )
        },
        bottomBar = {
            ThreadInputBar(onSend = onSendMessage)
        },
    ) { inner ->
        Column(                              // wrapper introduced in #201
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            ConnectionBanner(state = connectionState, onRetry = onRetry)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                reverseLayout = true,
            ) {
                items(items = emptyList<Unit>()) { }
            }
        }
    }
}

// ThreadTopAppBar.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`ThreadUiState` is still a **`data class`, not a `sealed interface`.** The canonical pattern in this codebase (`ChannelListUiState`, `DiscussionListUiState`, `ArchivedDiscussionsUiState`) is sealed `Loading | Empty | Loaded | Error` precisely because each represents a real async data-load stage; this screen still has zero `Loading` / `Error` distinction worth modelling (the `displayName` lookup falls back to the raw id rather than failing, so there is no error state to project). The first downstream ticket that introduces an error path or a "load-bearing waiting" frame (likely #128 once it subscribes to `observeMessages`) is the one that widens to a sealed envelope; the current `data class` becomes the `Loaded` variant via grep-replace.

`ThreadScreen`'s signature is **`(state, onBack, onSendMessage, connectionState, onRetry, modifier, onTitleClick, onOverflowClick)` — still no `onEvent` lambda even after #188 + #201 each landed VM-owned actions.** Every other VM-backed screen in the codebase uses `(state: UiState, onEvent: (Event) -> Unit)` once they have at least one VM-owned event. #188 added `onSendMessage: (String) -> Unit` as a flat callback rather than folding into a sealed `ThreadEvent { SendMessage(text), Retry, TitleTapped, OverflowTapped, … }`; #201 added `onRetry: () -> Unit` and `connectionState: ConnectionState` (the latter is *state*, not an event — see [Connection-banner wiring](#connection-banner-wiring) for why it's a separate `StateFlow` rather than a field on `ThreadUiState`). Flat callbacks for two event arms is still cheaper than scaffolding a sealed envelope. The fold to `ThreadEvent` is deferred to whichever ticket lands the *third* VM-owned event (#140 overflow or #141 rename, depending on whose handler routes through the VM rather than directly to nav). At that point the existing lambdas (`onSendMessage`, `onRetry`, `onTitleClick`, `onOverflowClick`, plus whichever new one arrives) collapse into one `onEvent: (ThreadEvent) -> Unit` and `onBack` stays separate (pure navigation). `connectionState` stays a flat `State` parameter — it's state, not an event.

## How it works

### `SavedStateHandle.get<String>("conversationId").orEmpty()` (lifted to a `private val`)

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes. Post-#139 the `conversationId` is lifted to a `private val` field on the VM so the `map { … }` body and the `stateIn(initialValue = …)` both reference one source of truth; before #139 the inline `savedStateHandle.get<String>("conversationId").orEmpty()` lived directly in the `MutableStateFlow(...)` constructor. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the title `Text` is non-null at every callsite.

### `observeConversations(All).map { firstOrNull }.stateIn(WhileSubscribed)` — option (c)

#139 picked option (c) of three plumbing options for surfacing `displayName` in `ThreadUiState` — filter the existing `observeConversations(All)` flow inside the VM rather than adding a route nav arg (option a) or a new `observeConversation(id)` repo method (option b). Tradeoffs:

| Option | Files | Tradeoff |
| --- | --- | --- |
| (a) Second nav arg `displayName` | Route + 4 call sites + VM + state | URL-encoding for unicode at every call site, scatters fallback logic, no live-rename re-emission |
| (b) New `observeConversation(id)` on repo | Interface + Fake + VM + state + screen + new top-bar file = ≥5 `.kt` | Cleanest contract long-term; busts the S file-count red line |
| **(c) Filter `All` in VM** | VM + state + screen + new top-bar file + AppModule = 4 `.kt` | One-call-line scan, free rename re-emission, no repo-surface change — **picked** |

The `firstOrNull { it.id == conversationId }` scan is `O(n)` per upstream emission — acceptable at Phase 0 fake-cardinality (~5 records). When the Phase 4 Ktor-backed impl lands and the per-emission scan has a real cost, a follow-up introduces `observeConversation(id): Flow<Conversation?>` (option b's endpoint). `ConversationFilter.All` is the right filter (not `Channels` / `Discussions`) because the thread screen is reachable from the archive screen and future deep links; restricting to non-archived would silently break those paths.

### `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`

Same lifetime policy as `ChannelListViewModel` / `DiscussionListViewModel`: the upstream subscription re-uses across configuration changes (rotation) without leaking when the screen leaves the back stack for >5s. The `initialValue` falls back to `ThreadUiState(conversationId, displayName = conversationId)` — before the upstream's first emission lands, the AppBar renders the path id, matching the post-emission fallback when the id is missing. The fake's `MutableStateFlow.map` chain emits synchronously, so in practice the placeholder is invisible; it exists for type safety and process-death restoration.

The pre-#139 VM was `MutableStateFlow(initial).asStateFlow()` (synchronous, hot from line 1, no `viewModelScope.launch`). The shape upgrade to `stateIn(WhileSubscribed)` is **byte-identical at the destination**: the `val state: StateFlow<ThreadUiState>` surface and the `val state by vm.state.collectAsStateWithLifecycle()` consumer pattern do not change. The interchange is deliberate — picking `MutableStateFlow` in #126 cost zero at the destination so that #139's widening to a cold-flow upstream was a drop-in replacement.

### `private fun Conversation.displayName()` — re-declared, not extracted

```kotlin
private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
```

The same extension also lives **privately** in `ArchivedDiscussionsScreen.kt:164-166`. #139 deliberately did **not** extract to a shared helper — promotion to `internal fun` in a new file would have pushed the spec to a 5-`.kt` diff (busting the S-budget). Two occurrences is below the canonical extract-on-third-use threshold; the third caller (likely #140's overflow menu's "Rename …" label) is the ticket that extracts to `internal fun Conversation.displayName()` under `ui/conversations/` and deletes both private copies.

The fallback strings are **Kotlin literals**, not `stringResource(R.string.untitled_discussion)`. ViewModels have no `Context`, and the existing private extension in `ArchivedDiscussionsScreen.kt` has been shipping with literals since #176; maintaining symmetry with the existing pattern is more important than tightening string-resource layering in #139. Revisit when the app needs localization beyond English — at that point the fallback moves into the UI layer (the composable that consumes `displayName`) rather than the VM.

### Display-name fallback chain

`conv?.displayName() ?: conversationId` collapses through three layers:

1. `name?.takeIf { it.isNotBlank() }` — real name if set and non-blank
2. `if (isPromoted) "Untitled channel" else "Untitled discussion"` — kind-aware fallback for `name = null` or `name = ""`
3. `conversationId` — when `firstOrNull { it.id == conversationId }` returns `null` (path not user-reachable in production; defensive against deep links and process-death races)

`displayName` is always a non-null `String`, so the title `Text` never needs a null check.

### Free rename re-emission

When #141's rename success path eventually calls `repository.rename(conversationId, newName)`, the fake's `MutableStateFlow<State>` updates; `observeConversations(All)` re-emits a list with the renamed conversation; the `.map { … }` recomputes `displayName`; `stateIn` publishes the new `ThreadUiState`; `collectAsStateWithLifecycle()` triggers recomposition. The `state_displayName_reemitsOnRename` test pins this — #141's only TopAppBar-related work is wiring the dialog's success path to `repository.rename(...)`; the live-title-update is already wired. Out-of-scope item from #139's ticket body ("plumbing a live rename back into the title without re-navigating — covered when #141 lands") is actually **already free** post-#139.

### `LazyColumn(reverseLayout = true)` — established in #126, still empty, now nested in a `Column`

The body shape is unchanged from #126: `items(emptyList<Unit>()) { }` inside a `LazyColumn` with `reverseLayout = true`. The reverse layout is invisible until messages render; baking it in now means #128's `items(state.messages) { … }` lands with no axis-flip work. No `verticalArrangement = Arrangement.Bottom` override — `reverseLayout = true` already pins the first item to the bottom edge.

Post-#201 the `LazyColumn` is nested inside a `Column` wrapper alongside the `ConnectionBanner` (see [Connection-banner wiring](#connection-banner-wiring) below). The list now carries `Modifier.fillMaxWidth().weight(1f)` rather than `.fillMaxSize()` — inside a `Column`, `fillMaxSize` ignores `weight` semantics and over-claims vertical space, fighting with siblings. The `reverseLayout = true` semantics are unchanged: the list still scrolls upward from the bottom of its weight-allocated region, with the banner pinned above it.

### Connection-banner wiring

The Scaffold content slot wraps the [`ConnectionBanner`](connection-banner.md) above the `LazyColumn` in a `Column`:

```kotlin
Column(modifier = Modifier.padding(inner).fillMaxSize()) {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), reverseLayout = true) { … }
}
```

Three design points pinned in #201:

- **Structural, not overlay.** Per the AC, the banner pushes the message list down by its intrinsic height; under `Connected` the banner's early `return` collapses to zero height so the steady-state look is byte-identical to pre-#201. Alternatives (`Box` with manual offset, `Scaffold` content overlay, `topBar = { Column { TopAppBar; ConnectionBanner } }`) all either change the overlay semantics (the AC forbids overlay) or force a refactor of `ThreadTopAppBar`. The `Column` wrapper is structurally minimal.
- **`Modifier.padding(inner)` lives on the outer `Column`, not the `LazyColumn`.** The Scaffold's content-inset wraps the banner *and* the list — applying `padding(inner)` only to the `LazyColumn` (leaving the banner outside the inset) would let the banner draw under the AppBar's status-bar inset on edge-to-edge devices.
- **`Connected` short-circuit is the steady state.** The fake's `FakeConnectionStateSource` always emits `Connected`; the banner's public-entry `when` returns early on `Connected` with no composition. Under normal use the banner is invisible; the integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake (see [Testing](#testing) below).

`connectionState: ConnectionState` is a flat parameter rather than a field on `ThreadUiState` — see [`#201`'s ticket notes](../codebase/201.md) for the full rationale. Short version: the existing `state` derivation stays untouched (no `combine(...)` ceremony, no churn to the seven existing tests that pattern-match `ThreadUiState`); connection state is global (every screen would consume the same source under future work) while conversation state is per-screen; reflecting that orthogonality at the type level is cleaner than artificial fusion. The destination block consumes two `collectAsStateWithLifecycle()` calls — the same shape `SettingsViewModel` already uses for its four separate flows.

`onRetry` binds to `vm::retry` at the destination — method reference, not a fresh lambda, so the binding is stable across recompositions (the lambda allocation only happens once per VM lifecycle, not per recomposition).

### `fun retry()` — non-suspend, VM owns the launch

```kotlin
fun retry() {
    viewModelScope.launch { connectionStateSource.retry() }
}
```

The VM exposes `retry()` as a non-`suspend` method; the `viewModelScope.launch` body wraps the source's `suspend fun retry()`. UI callers bind `vm::retry` directly to `ConnectionBanner`'s `onRetry: () -> Unit` slot without `rememberCoroutineScope { ... }.launch { ... }`. Same shape as `fun sendMessage(text: String)` from #188 — the convention in this codebase is never to expose `suspend` on a VM. If the screen is destroyed mid-call the launch is cancelled, which is fine for the Phase-2 no-op body; in Phase 4 the real source's `suspend fun retry()` may do network I/O, and `viewModelScope` cancellation will propagate as expected.

No `try/catch` around `connectionStateSource.retry()`. Per the `ConnectionStateSource` interface KDoc (#196), failures surface as state transitions (`Offline`), not exceptions; the Phase-2 fake cannot throw. No `.catch { ... }` on the upstream `observe()` either — premature defense.

### `connectionState: StateFlow<ConnectionState>` — same lifetime as `state`

```kotlin
val connectionState: StateFlow<ConnectionState> =
    connectionStateSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Connected)
```

`SharingStarted.WhileSubscribed(5_000)` matches the `state` flow's lifetime policy — both share the same `viewModelScope` and subscription window. If the screen resumes within 5s of leaving the back stack, the existing collector is reused (no `Connected` flash from re-subscription). `initialValue = ConnectionState.Connected` matches the fake's seeded value so the screen's first frame paints with the banner already short-circuited.

### `ThreadTopAppBar` — Figma `16:8` chrome

`ThreadTopAppBar(title, onBack, onTitleClick, onOverflowClick, modifier)` is a **public** stateless composable in its own file (`ThreadTopAppBar.kt`). Three slots:

- `navigationIcon` — `IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, R.string.cd_back) }`. `R.string.cd_back` reused — same string that backs every other back-arrow in the app.
- `title` — `Text(text = title, modifier = Modifier.clickable(onClick = onTitleClick).semantics { role = Role.Button })`. The `clickable` modifier rides on `Text` (not on a wrapping `Row`) so the ripple aligns with the visible text bounds rather than the full title-slot column. `Role.Button` keeps TalkBack announcing the title as activatable. The default `TopAppBar` title slot already renders `titleLarge` against `colorScheme.onSurface`, matching Figma `16:14` — do **not** override `style` or `color`.
- `actions` — `IconButton(onClick = onOverflowClick) { Icon(Icons.Filled.MoreVert, R.string.cd_more_actions) }`. `MoreVert` lives at `androidx.compose.material.icons.filled.MoreVert` (non-automirrored — the icon is symmetric, no RTL flip). New string `cd_more_actions = "More actions"` introduced in #139; named generically so non-thread overflows can reuse it.

**`TopAppBar` defaults are correct for this ticket.** Figma `16:8` uses `Schemes/surface` for the bar background, which matches `TopAppBarDefaults.topAppBarColors().containerColor` (= `colorScheme.surface`). Do **not** add `colors = TopAppBarDefaults.topAppBarColors(containerColor = ...)`. No `scrollBehavior` (Figma does not specify collapse-on-scroll; explicitly out of scope per the ticket body). No `windowInsets` override (the outer `MainActivity` `Scaffold` doesn't consume the top inset, so the default already handles status-bar inset correctly).

The composable is **stateless** per the project convention — no `remember`, no `MutableState`, no `rememberSnackbarHostState`. All four callbacks are caller-owned.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied.

## Wiring

### Koin binding

```kotlin
// di/AppModule.kt:36
viewModel { ThreadViewModel(get(), get(), get()) }
```

Post-#201: three `get()`s. The first resolves to the back-stack entry's `SavedStateHandle` (auto-provided by Koin's `koin-androidx-compose` artifact via `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ wires to the `NavBackStackEntry`). The second resolves to the `ConversationRepository` singleton bound at `AppModule.kt:28` (`single { FakeConversationRepository() } bind ConversationRepository::class`). The third (added in #201) resolves to the `ConnectionStateSource` singleton bound at `AppModule.kt:31` since #196 (`single { FakeConnectionStateSource() } bind ConnectionStateSource::class`). No new imports — all three deps were already in scope by the time #201 landed; no new `gradle/libs.versions.toml` entries.

### Destination block

```kotlin
// MainActivity.kt:197-211
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()   // added in #201
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onSendMessage = vm::sendMessage,   // added in #188
        connectionState = connectionState, // added in #201
        onRetry = vm::retry,               // added in #201
    )
}
```

The `Routes.CONVERSATION_THREAD = "conversation_thread/{conversationId}"` constant, the `navArgument` declaration, and the four pre-existing call sites at `MainActivity.kt:142-143, 151-152, 169-170, 178-179` are **all unchanged** from #15 / #126. The `onTitleClick` / `onOverflowClick` lambdas still fall through to their `{}` defaults until #141 / #140 wire real handlers — #141 adds one named parameter binding `onTitleClick = { showRenameDialog = true }`; #140 adds the symmetric `onOverflowClick = …`. Method-reference syntax `vm::sendMessage` / `vm::retry` (rather than fresh lambdas) avoids new lambda allocations per recomposition of the destination block.

## Configuration

- **Dependencies:** no new entries. `ConversationRepository` was already on classpath; `kotlinx.coroutines.flow.stateIn` rides in via the existing `kotlinx-coroutines-core` (catalog: `libs.coroutines.core`). No `gradle/libs.versions.toml` edits.
- **Strings:** `R.string.cd_back` reused, `R.string.cd_more_actions` added in #139 (`<string name="cd_more_actions">More actions</string>`). Naming follows the project's `cd_*` content-description convention.
- **State survives process death.** The back-stack entry's `SavedStateHandle` is re-populated with the `navArgument` value on process recreation; the VM constructor reads `conversationId` identically on fresh creation and restoration. `stateIn` re-subscribes on first `collectAsStateWithLifecycle()` call after restoration.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — twelve JUnit 4 tests post-#201 (seven from #139, two from #188, three new in #201). The pre-#139 plain-JUnit shape (no `runTest`, no `Dispatchers.setMain`) no longer works because `stateIn(viewModelScope, …)` requires a `Main` test dispatcher to publish emissions in test scope. The file adopts the canonical scaffold from `ChannelListViewModelTest:1-60`:

```kotlin
@Before fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
@After  fun tearDownMainDispatcher() { Dispatchers.resetMain() }
```

Since #201 the file also carries a `private fun makeVm(handle, repository, source = FakeConnectionStateSource())` helper at the bottom. Every existing `ThreadViewModel(handle, repository)` call site was rewritten as `makeVm(handle, repository)` to absorb the new constructor arg without threading a fixture through nine call sites — the default arg keeps `state`-focused tests terse and the three connection-focused tests pass an explicit source. Same shape as `ChannelListViewModelTest.makeVm` from #239.

Tests:

1. **`state_initialValue_isConversationIdPlaceholderBeforeSubscription`** — synchronously reads `vm.state.value` *without* a launched collector; asserts the `stateIn` `initialValue` is `ThreadUiState(id, displayName = id)`. Pins the placeholder contract.
2. **`state_resolvedTitle_isChannelNameForSeededChannel`** — `runTest { launch collector; advanceUntilIdle(); assert displayName == "Personal" }` against `seed-channel-personal` and `FakeConversationRepository()`. Pins the happy-path channel-name resolution.
3. **`state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion`** — same shape against `seed-discussion-a` (seeded with `name = null, isPromoted = false`). Pins the discussion fallback.
4. **`state_resolvedTitle_isUntitledChannelForUnnamedChannel`** — uses the file-scope `fixedRepo(listOf(Conversation(name = null, isPromoted = true, …)))` helper; asserts `"Untitled channel"`. Pins the channel fallback.
5. **`state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing`** — `fixedRepo(emptyList())` with `conversationId = "ghost-id"`; asserts `displayName == "ghost-id"`. Pins the missing-conversation fallback.
6. **`state_displayName_reemitsOnRename`** — collect into a `mutableListOf<ThreadUiState>()`; call `repository.rename("seed-channel-personal", "Personal — renamed")` inside the same `runTest` block; assert the post-rename emission's `displayName` matches. **Pins the load-bearing contract** that the downstream rename dialog (#141) inherits "for free" from option (c).
7. **`state_collapsesAbsentConversationIdToEmptyString`** — `SavedStateHandle(initialState = emptyMap())`; assert `state.value.conversationId == ""` synchronously. Pins the `.orEmpty()` narrowing (path not user-reachable in production).
8. **`sendMessage_blankText_isNoOp`** (#188) — calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`; asserts observed messages unchanged. VM-side blank-rejection contract.
9. **`sendMessage_nonBlankText_appendsToConversation`** (#188) — calls `vm.sendMessage("Hello world")`; asserts the appended `ThreadItem.MessageItem` lands with `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"`.
10. **`connectionState_initialValue_isConnected`** (#201) — construct VM with default `FakeConnectionStateSource()`. Without any collector, assert `vm.connectionState.value == ConnectionState.Connected`. Pins the AC1 default + the `WhileSubscribed` initialValue contract. Synchronous; no `runTest { }` wrapper needed.
11. **`connectionState_reemitsOnSourceChange`** (#201) — construct a `FakeConnectionStateSource` explicitly, build the VM with it, launch a collector, call `source.emit(ConnectionState.Offline)`, `advanceUntilIdle()`, assert `vm.connectionState.value == ConnectionState.Offline`. Pins the AC1 "exposes current state" wiring (not just the initialValue).
12. **`retry_invokesSourceRetry`** (#201) — construct a `RecordingConnectionStateSource` (file-private test double; see below), call `vm.retry()`, `advanceUntilIdle()`, assert `source.retryCallCount == 1`. Pins AC2: the VM actually forwards to the source rather than swallowing the call.

The `fixedRepo(conversations)` helper is an anonymous `object : ConversationRepository { … }` with `TODO("not used")` overrides plus a `flowOf(conversations)`-backed `observeConversations`. It's kept local rather than extracted — each test's bespoke conversation shape would force a builder-shaped helper that doesn't pay for itself yet.

The `RecordingConnectionStateSource` test double introduced in #201 lives at the bottom of `ThreadViewModelTest.kt` alongside `fixedRepo`:

```kotlin
private class RecordingConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var retryCallCount: Int = 0
        private set
    override fun observe(): Flow<ConnectionState> = state.asStateFlow()
    override suspend fun retry() { retryCallCount++ }
}
```

It cannot reuse `FakeConnectionStateSource` because the fake's `retry()` is a no-op — an assertion-of-effect through the fake would have nothing to observe. The recording double is deliberate test-local fixture; pre-staging this as a public-ish helper in `data/repository/` would be premature (no other consumer needs it).

No instrumented Compose test for `ThreadScreen` or `ThreadTopAppBar`. The codebase has no `androidTest` infrastructure for thread/list screens beyond existing fixtures. Visual verification is by `./gradlew assembleDebug` + `./gradlew installDebug` + manual tap from channel-list / discussion-list / FAB-create-discussion paths (channel name renders, discussion fallback renders, banner stays hidden under steady-state `Connected`, back pops, title-tap and overflow-tap are no-op stubs with TalkBack announcing role + content description correctly).

## Previews

Two `@Preview`s at the bottom of `ThreadScreen.kt`:

- **`ThreadScreenLightPreview`** — `PyrycodeMobileTheme(darkTheme = false) { ThreadScreen(state = …, onBack = {}, onSendMessage = {}, connectionState = ConnectionState.Connected, onRetry = {}) }`. The `"kitchenclaw refactor"` seed reproduces the Figma `16:14` title literal.
- **`ThreadScreenDarkPreview`** — same seed with `darkTheme = true`. Verifies the AppBar `Schemes/surface` `#101418` token renders against the dark theme without explicit color overrides.

Both previews use the steady-state `ConnectionState.Connected`, so the banner is invisible. **No `Offline` / `Connecting` / `Reconnecting` preview variants here** — the four-state matrix already lives in [`ConnectionBannerPreviewMatrix`](connection-banner.md#preview) from #200; duplicating would add no fidelity beyond confirming the wiring, and the steady-state preview is the regression artifact for the structural `Column { Banner; LazyColumn }` shape itself. Neither preview passes `onTitleClick` / `onOverflowClick` — the defaults apply. No standalone preview for `ThreadTopAppBar` (would duplicate the screen-level preview's coverage; the spec called this out as optional).

## Edge cases / limitations

- **No data loading for messages yet.** `ThreadViewModel` subscribes to `observeConversations(All)` for the title, but not to `observeMessages(conversationId)` — that's #128's wiring. The `LazyColumn` body still renders nothing. Sending a message via the composer (#188) appends through the repository but the appended `Message` is invisible at the screen level until #128 lands — the only feedback that send fired is the cleared input field.
- **`displayName` falls back to the raw `conversationId` when the lookup misses.** Path is not user-reachable in production (every nav edge passes a real id) but the property is observable and pinned by a test. Deep links and process-death restoration go through the same path.
- **Banner is invisible under normal use.** The Phase-2 `FakeConnectionStateSource` always emits `Connected`; the banner short-circuits to zero height. The integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake — the user-facing disconnected affordance lands when Phase 4 swaps the binding to the real Ktor-backed source.
- **`retry()` is a Phase-2 no-op.** The fake's `retry()` does nothing; the VM still routes the call through `viewModelScope.launch` so the Phase-4 swap is binding-only. Tapping the `Offline` banner today fires the ripple, increments `RecordingConnectionStateSource.retryCallCount` in tests, and does nothing observable in the running app.
- **Title-tap ripple aligns with text bounds, not the full title-slot column.** Deliberate tradeoff for the rename entry — if #141's UX widens to "tap anywhere in the bar to rename", `ThreadTopAppBar` widens the clickable region to a wrapping `Row`.
- **`onTitleClick` / `onOverflowClick` are no-op stubs at the destination block.** #141 wires the rename dialog through `onTitleClick`; #140 wires the overflow menu through `onOverflowClick`. Until they land, both icons announce their content descriptions to TalkBack but do nothing visible on tap.
- **No scroll behaviour on the TopAppBar.** Figma `16:8` does not specify collapse-on-scroll or elevation overlay; `scrollBehavior` is left at the default. Out-of-scope per the ticket body.
- **No `Modifier.systemBarsPadding()` on the per-screen `Scaffold`.** Outer `MainActivity` `Scaffold` already passes `innerPadding`; status-bar inset is already handled upstream — don't add it again.
- **Per-emission `firstOrNull` scan.** `O(n)` over the full conversation list. Acceptable at Phase 0 fake-cardinality; a follow-up introduces `observeConversation(id): Flow<Conversation?>` when the Phase 4 Ktor-backed impl makes the cost real.
- **No `AnimatedVisibility` on banner state changes.** State changes swap the banner in/out instantly. Open follow-up — needs a designer-signed-off cross-fade duration.

## Related

- Ticket notes: [`../codebase/126.md`](../codebase/126.md) (skeleton), [`../codebase/139.md`](../codebase/139.md) (TopAppBar promotion), [`../codebase/188.md`](../codebase/188.md) (input bar + `sendMessage`), [`../codebase/201.md`](../codebase/201.md) (ConnectionBanner wiring), [`../codebase/15.md`](../codebase/15.md) (the placeholder route this slice replaces)
- Specs: `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/139-thread-topappbar-back-title-overflow.md`, `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/201-thread-screen-wire-connectionbanner.md`
- Upstream: [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination consumes), [Conversation repository](conversation-repository.md) (the `observeConversations(All)` source the VM consumes; `sendMessage` it forwards to since #188), [Connection state](connection-state.md) (the `ConnectionStateSource.observe()` / `retry()` contract the VM consumes since #201), [Dependency injection](dependency-injection.md) (Koin `viewModel { ThreadViewModel(get(), get(), get()) }` binding — third `get()` added in #201)
- Child components: [Thread input bar](thread-input-bar.md) (the composer in `bottomBar`, landed in #188), [ConnectionBanner](connection-banner.md) (the banner between TopAppBar and message list, wired in #201)
- Downstream remaining (each replaces a body slot here, not the chrome): #128 message bubble (widens `ThreadUiState` to sealed `Loading | Loaded(messages, displayName, …) | Error` and subscribes to `observeMessages`), #135 session-boundary delimiter, #137/#138 empty states, #140 overflow menu (wires `onOverflowClick`), #141 rename dialog (wires `onTitleClick`; live re-emission already free post-#139), #145 status row
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the complete future state; this slice ships the outer shell + the TopAppBar chrome + the composer in `bottomBar` + the connection banner slot between them)
