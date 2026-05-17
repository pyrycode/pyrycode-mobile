# Spec — #201: wire `ConnectionBanner` into `ThreadScreen`

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:14-48` — current `Scaffold(topBar, bottomBar) { inner -> LazyColumn(padding(inner).fillMaxSize(), reverseLayout=true) { … } }` shape. The edit replaces the single `LazyColumn` content slot with a `Column` whose first child is `ConnectionBanner` and second child is the existing `LazyColumn`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:20-52` — current constructor `(SavedStateHandle, ConversationRepository)`, the `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue=…)` shape over `repository.observeConversations(…)`, and the `sendMessage` launcher. This is the shape the new `ConnectionStateSource` plumbing mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt:22-60` — public signature `ConnectionBanner(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier)` and the `Connected → return` zero-height short-circuit. The wiring slice consumes this surface unmodified.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt:13-28` — interface contract (`observe(): Flow<ConnectionState>` + `suspend fun retry()`). `retry()` is a Phase-2 no-op in the fake; the VM still routes the call through `viewModelScope.launch` so the Phase-4 swap is binding-only.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt:1-26` — the test/preview seam `emit(state)` updates the backing `MutableStateFlow` and is the lever the new VM tests use to drive `Offline`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:30-36` — existing `single { FakeConnectionStateSource() } bind ConnectionStateSource::class` (line 31) and the `viewModel { ThreadViewModel(get(), get()) }` factory (line 36) that grows one `get()` argument in this slice.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-208` — destination block for `Routes.CONVERSATION_THREAD`; the edit adds one more `collectAsStateWithLifecycle()` call and threads two new parameters (`connectionState`, `onRetry`) into `ThreadScreen(...)`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:40-171` — existing test idiom: `Dispatchers.setMain(UnconfinedTestDispatcher())`, `SavedStateHandle(initialState = …)`, `runTest { launch { vm.state.collect {} }; advanceUntilIdle(); … }`. New tests follow the same shape; every existing `ThreadViewModel(handle, repository)` call site grows one argument.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame (`16:8`) shows the Top App Bar (`16:9`) immediately above the Message list (`16:21`) — no banner node exists in the design today. The ConnectionBanner inserts between those two children, pushing the LazyColumn down by the banner's intrinsic height under disconnected states and collapsing to zero height under `Connected` (the steady-state look matches the Figma exactly). The banner's own visual treatment is owned by `#200` and uses M3 `surfaceContainerHigh` / `errorContainer` slots — not re-specified here.

## Context

This slice finishes the connection-banner triad started by `#196` (data-layer source) and `#200` (composable). The composable was shipped behind a stateless `(state, onRetry)` contract; this ticket lifts state via `collectAsStateWithLifecycle` and pipes the lambda through `ThreadViewModel.retry()` so the production `ThreadScreen` actually renders the banner. Under Phase 2 the `FakeConnectionStateSource` always reports `Connected`, so the banner stays hidden under normal use; the integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake.

`ConnectionStateSource` is already singleton-bound in `AppModule` (`#196`). The change is mechanical: one new constructor arg on `ThreadViewModel`, one new `get()` in the Koin factory, one new collected state in the destination block, one new `Column` wrapper in `ThreadScreen`.

The deliberate scope contract:

- **Banner placement is structural, not overlay.** It pushes the message `LazyColumn` down — content is not occluded.
- **Connection state is independent of `ThreadUiState`.** Exposed as a separate `StateFlow<ConnectionState>`; see § Design / "Why a separate `StateFlow`" below.
- **`retry()` on the VM is non-suspend.** It launches a `viewModelScope` coroutine that calls the source's `suspend fun retry()`. UI callers don't need a `CoroutineScope`.
- **No new composables, no new sealed types, no new model fields.** All four AC bullets resolve in modifications to existing files.

## Design

### Files

Four edits, zero new production files. One new test fixture is added inline to the existing test file (no new test file).

1. **Modify** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — constructor grows a `ConnectionStateSource` param; add `connectionState: StateFlow<ConnectionState>` and `fun retry()`.
2. **Modify** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — replace single `LazyColumn` content slot with `Column { ConnectionBanner(...); LazyColumn(weight=1f) { … } }`; signature grows two params.
3. **Modify** `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — `viewModel { ThreadViewModel(get(), get(), get()) }` (one extra `get()`).
4. **Modify** `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — destination block adds one `collectAsStateWithLifecycle()` and threads `connectionState` + `onRetry = vm::retry` into `ThreadScreen(...)`.
5. **Modify** `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — every `ThreadViewModel(handle, repository)` call grows a third argument; three new tests; one recording test double.

### `ThreadViewModel` changes

Constructor:

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
) : ViewModel()
```

New exposed surface (added after the existing `state: StateFlow<ThreadUiState>`):

```kotlin
val connectionState: StateFlow<ConnectionState>
    = connectionStateSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Connected)

fun retry() {
    viewModelScope.launch { connectionStateSource.retry() }
}
```

Design points:

- **Why a separate `StateFlow<ConnectionState>` instead of folding into `ThreadUiState`.** The AC explicitly allows either. Separate keeps the existing `ThreadUiState`'s derivation (`repository.observeConversations(…).map { … }.stateIn(…)`) untouched — no `combine(...)` ceremony, no churn to the existing seven tests that pattern-match `ThreadUiState`. Connection state is global (every screen would consume the same source under future work); conversation state is per-screen. Reflecting that orthogonality at the type level is cleaner than artificial fusion. The destination block consumes two `collectAsStateWithLifecycle()` calls — one of the most common shapes already in the codebase (see `MainActivity.kt:209-214` where `SettingsViewModel` exposes four separate flows).
- **`SharingStarted.WhileSubscribed(5_000)`** matches the existing `state` flow's lifetime policy. Both flows share the same `viewModelScope` and the same subscription window; no asymmetry to reason about.
- **`initialValue = ConnectionState.Connected`** matches the fake's seeded value and gives the screen a non-null first frame without a `Loading` shim. The banner short-circuits to zero height on `Connected`, so the first paint shows no banner — correct steady-state behaviour.
- **`fun retry()` is non-suspend.** The VM owns the `viewModelScope` launch; UI callers can bind `vm::retry` directly to the `onRetry: () -> Unit` slot on `ConnectionBanner` without wrapping in a `rememberCoroutineScope { … }.launch { … }` block. Same pattern as the existing `fun sendMessage(text: String)` at `ThreadViewModel.kt:46-51`.
- **No `LaunchedEffect`, no `init {}` block.** Both `state` and `connectionState` are constructed declaratively; the `WhileSubscribed` flows start collecting when the screen subscribes and stop ~5s after it leaves.

New imports:

```kotlin
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConnectionStateSource
```

### `ThreadScreen` changes

Signature grows two params (placed after `onSendMessage` to keep the existing call-order intact for the `onBack` / `onSendMessage` / `onTitleClick` / `onOverflowClick` cluster):

```kotlin
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    connectionState: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
)
```

Content-slot edit. Replace the current `LazyColumn` body of `Scaffold` with:

```kotlin
Column(
    modifier = Modifier
        .padding(inner)
        .fillMaxSize(),
) {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        reverseLayout = true,
    ) {
        items(items = emptyList<Unit>()) { }
    }
}
```

Design points:

- **`Column` is the cheapest shape.** Ticket Technical Notes explicitly prescribe wrapping the existing list in a `Column` whose first child is the banner. Alternatives (`Box` with manual offset, `Scaffold` content overlay, `topBar = { Column { TopAppBar; ConnectionBanner } }`) all either change the overlay semantics (the AC forbids overlay) or force a refactor of `ThreadTopAppBar`. The `Column` wrapper is structurally minimal.
- **`weight(1f)` on the `LazyColumn`.** With the `Column` parent, the LazyColumn must explicitly claim remaining vertical space; otherwise it collapses to its intrinsic content height. Use `.fillMaxWidth().weight(1f)` (not `.fillMaxSize()`) — `fillMaxSize` ignores weight semantics inside a `Column` and would over-claim.
- **`reverseLayout = true` is preserved.** No semantic flip; the banner sits at the top of the visual column, the message list scrolls upward from the bottom — unchanged from #126's scaffolding decision.
- **`Modifier.padding(inner)` moves from the LazyColumn to the outer `Column`.** The inner-padding contract is "apply the scaffold's content-inset insets to the entire content slot"; with the slot now containing both the banner and the list, the padding wraps both. Applying `padding(inner)` to only the LazyColumn (leaving the banner outside the inset) would let the banner draw under the AppBar's status-bar inset on edge-to-edge devices.
- **No `Modifier.systemBarsPadding()` anywhere.** Handled by upstream `Scaffold` in `MainActivity`.

Preview updates (two-line edit per preview): both `ThreadScreenLightPreview` and `ThreadScreenDarkPreview` add `connectionState = ConnectionState.Connected` and `onRetry = {}`. Pre-staging `Offline` previews here would duplicate `#200`'s `ConnectionBannerPreviewMatrix` and add no fidelity beyond confirming the wiring — keep the previews on the steady-state baseline.

New imports:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
```

(Existing `import androidx.compose.foundation.layout.fillMaxSize` stays — the outer `Column` still uses `.fillMaxSize()`.)

### `AppModule` change

One-arg edit:

```kotlin
viewModel { ThreadViewModel(get(), get(), get()) }
```

Koin resolves the third `get()` to the existing singleton `FakeConnectionStateSource` bound to `ConnectionStateSource::class` at line 31. No new imports, no new bindings.

### `MainActivity` destination block change

Add one `collectAsStateWithLifecycle()` and thread two new params into `ThreadScreen`:

```kotlin
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onSendMessage = vm::sendMessage,
        connectionState = connectionState,
        onRetry = vm::retry,
    )
}
```

No new imports — `collectAsStateWithLifecycle` is already in scope from the prior call.

## State + concurrency model

- `connectionState: StateFlow<ConnectionState>` lives on `viewModelScope` via `stateIn(WhileSubscribed(5_000), …)` — same lifetime contract as `state`. When the screen leaves composition, the upstream `source.observe()` collector tears down after 5s; if the screen resumes within that window, the existing collector is reused (no `Connected` flash from re-subscription).
- `fun retry()` launches a one-shot coroutine on `viewModelScope`. If the screen is destroyed mid-call, the launch is cancelled — fine for the Phase-2 no-op body; in Phase 4 the real source's `suspend fun retry()` may do network I/O, and `viewModelScope` cancellation will propagate as expected.
- `onRetry` lambda capture at the destination uses `vm::retry` (method reference) — stable across recompositions because `vm` itself is stable. No `remember { … }` wrapping needed.
- No new dispatcher juggling. The `source.observe()` is consumed on `Dispatchers.Main.immediate` (default for `viewModelScope`); the Phase-4 real source is expected to do its own I/O dispatch upstream of `observe()`.

## Error handling

None at this layer.

- `connectionStateSource.retry()` cannot throw under Phase 2 (no-op body). Per the interface KDoc at `ConnectionStateSource.kt:20-26`, the Phase-4 contract is that failures surface as state transitions (`Offline`), not as exceptions thrown from `retry()`. So no `try/catch` around `viewModelScope.launch { source.retry() }`.
- `source.observe()` is a cold `Flow` that the fake never errors. The Phase-4 implementation likewise routes errors through state, not `Flow.catch`. No `.catch { … }` on the upstream — would be premature defense.
- The banner itself handles the `Offline` UX by rendering the retry call-to-action; no error-banner / dialog / snackbar fallback is in scope.

## Testing strategy

Unit tests only (`./gradlew test`). No Compose UI test. Rationale for skipping the UI test: the codebase has no existing `androidTest` infrastructure (per `#126`'s spec — no `ComposeTestRule`, no Robolectric on `testImplementation`). Visual placement of the banner is verifiable via the existing `ConnectionBannerPreviewMatrix` (`#200`) and the screen's two `@Preview` composables on the steady-state `Connected` path. The structural placement contract (banner above list, pushing not overlaying) is asserted by the `Column { ConnectionBanner; LazyColumn(weight=1f) }` shape itself — a UI test asserting "banner is above list" would be tautological against the composable structure.

### `ThreadViewModelTest` updates

Constructor-call site updates (mechanical): every `ThreadViewModel(handle, repository)` grows a third argument. There are seven such sites (every `@Test` in the file). To avoid threading a new fixture through every site, introduce one local helper at the bottom of the file alongside the existing `fixedRepo(…)`:

```kotlin
private fun makeVm(
    handle: SavedStateHandle,
    repository: ConversationRepository,
    source: ConnectionStateSource = FakeConnectionStateSource(),
): ThreadViewModel = ThreadViewModel(handle, repository, source)
```

Replace every direct `ThreadViewModel(handle, repository)` with `makeVm(handle, repository)`. The default arg lets tests that don't care about connection state stay terse; the three new tests pass a custom source explicitly. Same pattern as `ChannelListViewModelTest.makeVm` introduced in `#239` (spec `239-channellistviewmodeltest-makevm-helper.md`).

New tests (bullets describe inputs + expected behaviour; the developer writes the bodies in the project's existing `runTest { launch { … }; advanceUntilIdle(); … }` shape — see `state_resolvedTitle_isChannelNameForSeededChannel` at lines 51-61 for the canonical idiom):

1. **`connectionState_initialValue_isConnected`** — construct the VM with the default `FakeConnectionStateSource()`. Without any collector, assert `vm.connectionState.value == ConnectionState.Connected`. Pins the AC1 default + the `WhileSubscribed` initialValue contract. Synchronous; no `runTest { }` wrapper needed.
2. **`connectionState_reemitsOnSourceChange`** — construct a `FakeConnectionStateSource` explicitly, build the VM with it, launch a collector on `vm.connectionState`, call `source.emit(ConnectionState.Offline)`, `advanceUntilIdle()`, assert `vm.connectionState.value == ConnectionState.Offline`. Pins the AC1 "exposes current state" wiring (not just the initialValue).
3. **`retry_invokesSourceRetry`** — construct a `RecordingConnectionStateSource` (test double, defined at the bottom of the test file alongside `fixedRepo` — see fixture below), build the VM with it, call `vm.retry()`, `advanceUntilIdle()`, assert `source.retryCallCount == 1`. Pins AC2 — the VM actually forwards to the source rather than swallowing the call.

Test double (inline in `ThreadViewModelTest.kt`):

```kotlin
private class RecordingConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var retryCallCount: Int = 0
        private set
    override fun observe(): Flow<ConnectionState> = state.asStateFlow()
    override suspend fun retry() { retryCallCount++ }
}
```

This is a deliberate departure from "reuse `FakeConnectionStateSource`": the fake's `retry()` is a no-op so an assertion-of-effect through the fake would have nothing to observe. The recording double is ~10 lines of test-local fixture; pre-staging this as a public-ish helper in `data/repository/` would be premature (no other consumer needs it).

### Existing tests

All seven existing `@Test`s continue to pass without behavioural change after the `makeVm(...)` swap. Specifically:

- `state_initialValue_isConversationIdPlaceholderBeforeSubscription` (line 41) — unaffected; reads `vm.state.value`, not `vm.connectionState.value`.
- `state_resolvedTitle_…` (×3, lines 51-105) — unaffected.
- `state_displayName_reemitsOnRename` (line 107) — unaffected.
- `state_collapsesAbsentConversationIdToEmptyString` (line 122) — unaffected.
- `sendMessage_blankText_isNoOp` / `sendMessage_nonBlankText_appendsToConversation` (lines 130, 150) — unaffected.

### Build / lint

`./gradlew assembleDebug` and `./gradlew test` must pass. `./gradlew lint` must pass with no new warnings. Spotless/ktlint: the developer runs `./gradlew spotlessApply` before pushing; this is standard.

## Open questions

- **Should the destination block use `vm::retry` or `{ vm.retry() }`?** Both compile. `vm::retry` is one character shorter and Compose-stable. Pick `vm::retry`. (Documented to avoid a code-review nit.)
- **Should `connectionState` be passed positionally before or after `modifier`?** Keep `modifier` as the fourth-from-last parameter; `connectionState` + `onRetry` go just before `modifier` so the existing `(state, onBack, onSendMessage)` cluster reads chronologically: navigation, message-send, then orthogonal global state, then the catch-all `modifier`, then the two optional `onTitleClick` / `onOverflowClick` defaults. This matches the convention in the rest of the codebase (`modifier` lives between required params and optional defaulted ones).
- **`#200` previews vs. this slice's screen previews.** Steady-state `Connected` is the only path shown in `ThreadScreenLightPreview` / `ThreadScreenDarkPreview`; the four-state matrix already lives in `ConnectionBanner.kt:91-97`. No duplication needed.
