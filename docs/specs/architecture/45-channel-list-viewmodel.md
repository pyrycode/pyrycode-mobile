# Spec — ChannelListViewModel + UiState (#45)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-44` — `ConversationRepository` interface + `ConversationFilter.Channels`. The VM binds to this interface; the only call is `observeConversations(ConversationFilter.Channels)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:21-43` — confirms the channels stream is a cold `Flow<List<Conversation>>` derived from a `MutableStateFlow`. Emits the current value on subscription, never throws under the fake.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — payload type carried by `Loaded(channels)`. `Conversation` is a value-only data class; no Android types.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — current Koin module. This ticket appends two bindings and deletes the three-line `// Bindings added by downstream tickets:` TODO block (its job is done — both pending consumers, the repository binding from #4 and the first VM binding, land here).
- `docs/knowledge/features/dependency-injection.md` — Koin conventions (single `appModule`, `single { ... } bind ...::class` for interfaces, `viewModel { ... }` for VMs, "no `try/catch` around `startKoin`", "no `androidLogger` until a real misconfiguration surfaces"). Match these.
- `docs/knowledge/features/conversation-repository.md` — explicitly states "**No DI wiring yet.** No Koin module exists; the binding lands with the first ViewModel that consumes the fake." That's this ticket.
- `docs/specs/architecture/4-fake-conversation-repository.md:441` — flagged "Adding `kotlinx-coroutines-test` to the catalog (not needed at this stage; revisit when virtual-time tests are required)." This ticket is the revisit: `stateIn(viewModelScope, ...)` requires `Dispatchers.setMain` in unit tests.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:14-22` — existing test style (JUnit 4, `runBlocking`, `Flow.first()`). The new ChannelListViewModel test diverges (needs `runTest` + Main dispatcher); document the divergence inline.
- `app/build.gradle.kts:42-64` — `dependencies { }` block. Two additions: `implementation(libs.androidx.lifecycle.viewmodel.ktx)` (for `viewModelScope`) and `testImplementation(libs.kotlinx.coroutines.test)`.
- `gradle/libs.versions.toml` — version catalog. Two new `[libraries]` aliases (no new `[versions]` entry: both reuse existing `lifecycleRuntimeKtx` and `kotlinxCoroutines` version refs).
- `CLAUDE.md` (Stack, Conventions sections) — confirms MVI + ViewModel + StateFlow + sealed `UiState`/`Event`, stateless composables, single Gradle module.

## Context

The conversations data layer (`ConversationRepository` + `FakeConversationRepository`) shipped in #3/#4/#5/#6/#9/#10. The channel list screen (#46) is the first UI consumer; before the screen lands, this ticket establishes a tested, DI-wired data path so #46 can be a stateless composable receiving `(state, onEvent)`.

This is also the first `ViewModel` in the codebase. Two coupling decisions land alongside the VM itself:

1. **The pending `FakeConversationRepository` Koin binding.** Per `docs/knowledge/features/conversation-repository.md` and spec #4, the binding was deliberately deferred to "the first ViewModel that consumes the fake" — this one. Wiring it in a standalone ticket would have been a binding with no consumer; bundling here is correct.
2. **First `viewModelScope`-based unit test.** Prior tests used `runBlocking` + `Flow.first()` and explicitly avoided `kotlinx-coroutines-test`. `stateIn(viewModelScope, ...)` calls `Dispatchers.Main.immediate` under the hood, which is uninitialised in plain JVM tests — `IllegalStateException: Module with the Main dispatcher had failed to initialize` without a `Dispatchers.setMain(...)` shim. This ticket brings `kotlinx-coroutines-test` onto the test classpath.

Both items are scoped tightly: the catalog additions reuse existing `[versions]` pins, the AppModule edit is two `+` lines and three `-` comment lines.

## Design

### `ChannelListUiState` + `ChannelListViewModel` (new file)

Path: `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`.

Single file, two top-level declarations.

```kotlin
package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data object Empty : ChannelListUiState
    data class Loaded(val channels: List<Conversation>) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

class ChannelListViewModel(
    repository: ConversationRepository,
) : ViewModel() {

    val state: StateFlow<ChannelListUiState> =
        repository.observeConversations(ConversationFilter.Channels)
            .map<List<Conversation>, ChannelListUiState> { channels ->
                if (channels.isEmpty()) ChannelListUiState.Empty
                else ChannelListUiState.Loaded(channels)
            }
            .catch { e ->
                val raw = e.message
                emit(ChannelListUiState.Error(if (raw.isNullOrBlank()) "Failed to load channels." else raw))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ChannelListUiState.Loading,
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

Design notes:

- **`ChannelListUiState` is top-level (not nested inside the VM).** The screen-side composable (#46) will import the variants directly (`ChannelListUiState.Loaded`); a nested `ChannelListViewModel.UiState` would force `import …ChannelListViewModel.UiState` at every call site for marginal namespacing benefit. Subsequent screens get their own `XxxUiState` peer types — the name carries the disambiguation.
- **`data object` for `Loading`/`Empty`.** Kotlin 2.0+ — gives free `toString`/`equals`. The two are singletons; `data class()` would be wrong (separate instances would compare unequal).
- **Constructor takes `ConversationRepository` (the interface), not `FakeConversationRepository`.** Koin's `bind ConversationRepository::class` makes `get()` resolve to the bound implementation. Phase 4's remote impl drops in behind the same interface with no VM change.
- **Cold-to-hot via `stateIn`.** `WhileSubscribed(5_000)` — standard 5-second grace: survives configuration changes (rotation) without re-collecting; truly disposes when navigation exits the screen for >5s. Matches the de-facto Android default. `Lazily` would leak the upstream after the screen exits; `Eagerly` would start collecting at VM construction (before the screen subscribes) and waste work if the user navigates away before subscribing.
- **`initialValue = Loading`.** Satisfies AC: "Initial emitted state is `Loading` (before the repository flow produces a value)." `stateIn` emits this synchronously; the first real `observeConversations` value replaces it on the next dispatch turn.
- **`.catch { }` before `.stateIn`, after `.map { }`.** This intercepts both repository-flow errors and map exceptions. Placing `catch` after `stateIn` would not work — `stateIn` is a terminal operator returning `StateFlow`, not a `Flow` operator chain. The `Error` payload uses the exception message verbatim when present, falling back to `"Failed to load channels."` when null/blank — guarantees the AC's "non-blank message" requirement.
- **No `Event` / `onEvent` surface in this ticket.** The PO body and AC are read-only: observe channels, emit state. The follow-up #46 introduces `tap navigation` — that's the right ticket to introduce `ChannelListEvent` sealed type and `fun onEvent(event: ChannelListEvent)`. Defer until there's an event to model. **No speculative `init { }` / `refresh()` / `retry()` methods.** Cold flow re-collection on resubscription is the existing retry surface; an explicit `retry()` lands when the UI surfaces a "Retry" button (not in this ticket).
- **No `init { }` block that pre-populates state.** `stateIn` handles initial-value emission. An `init { viewModelScope.launch { … } }` would duplicate the wiring `stateIn` already provides.
- **`STOP_TIMEOUT_MILLIS` is a `private companion object const`.** Single named constant; not exposed; expression-bodied so no `val` field on instances. If a future ticket adds a second timeout, fine — promote to a shared `UiDefaults` object then, not preemptively.

### Package + directory layout

New directory: `app/src/main/java/de/pyryco/mobile/ui/conversations/list/`. Matches the CLAUDE.md "planned package layout" (`ui/conversations/list/`). Test mirror: `app/src/test/java/de/pyryco/mobile/ui/conversations/list/`.

No other files land in `ui/conversations/list/` in this ticket. The screen (`ChannelListScreen.kt`), empty state, FAB, TopAppBar (#23, #22, #21) all land in their own tickets.

### `AppModule.kt` edits

Path: `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`. Diff-shaped change — two adds, one block delete.

Final file:

```kotlin
package de.pyryco.mobile.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
        )
    }
    single { AppPreferences(get()) }
    single { FakeConversationRepository() } bind ConversationRepository::class
    viewModel { ChannelListViewModel(get()) }
}
```

Edits to make (developer's checklist for this file):

1. **Add three imports** alphabetically in the existing block:
   - `de.pyryco.mobile.data.repository.ConversationRepository`
   - `de.pyryco.mobile.data.repository.FakeConversationRepository`
   - `de.pyryco.mobile.ui.conversations.list.ChannelListViewModel`
2. **Add two Koin imports**:
   - `org.koin.androidx.viewmodel.dsl.viewModel`
   - `org.koin.dsl.bind`
3. **Delete the three-line TODO block** (`// Bindings added by downstream tickets:` and the two `//   #...` lines below it). Its job is done — both pending consumers land in this ticket.
4. **Add two binding lines** at the end of the `module { ... }` block, in this order:
   - `single { FakeConversationRepository() } bind ConversationRepository::class`
   - `viewModel { ChannelListViewModel(get()) }`

Notes:

- **Order within `module { }` matters for readability, not for resolution.** Koin builds a resolution graph; declaration order doesn't matter. Keep singletons together (DataStore → AppPreferences → ConversationRepository), then ViewModels (`viewModel { ... }`) — same shape as canonical Koin examples.
- **`viewModel { ... }` DSL import.** Use `org.koin.androidx.viewmodel.dsl.viewModel` — the Android-flavored DSL that backs `koinViewModel<…>()` in composables. Koin 4.0 also exposes `org.koin.core.module.dsl.viewModel`; if AGP/Kotlin flags the `androidx` form as deprecated in this version, switch to the `core.module.dsl` import. Both work today; the `androidx` form is what every example in the wild uses.
- **No `androidLogger(...)` change.** Per existing convention.

### Dependency wiring

**`gradle/libs.versions.toml`** — two new `[libraries]` entries, no new `[versions]` entries. Both reuse existing version refs.

Add to `[libraries]`, placed adjacent to their version-ref siblings (catalog already groups by prefix, not strict alphabetic):

```toml
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycleRuntimeKtx" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

Placement notes:

- `androidx-lifecycle-viewmodel-ktx` — put next to `androidx-lifecycle-runtime-ktx` (line 23 today). Same version-ref (`lifecycleRuntimeKtx = "2.6.1"`) on purpose — the two `lifecycle-*` artifacts must move in lockstep. Renaming `lifecycleRuntimeKtx` → `lifecycle` is a separate refactor; do not do it here.
- `kotlinx-coroutines-test` — put directly under `kotlinx-coroutines-core` (line 34 today). Reuses `kotlinxCoroutines = "1.10.2"`.

**`app/build.gradle.kts`** — two new lines in `dependencies { }`.

Add:

```kotlin
implementation(libs.androidx.lifecycle.viewmodel.ktx)
testImplementation(libs.kotlinx.coroutines.test)
```

Placement:

- `implementation(libs.androidx.lifecycle.viewmodel.ktx)` — alphabetically immediately after `implementation(libs.androidx.lifecycle.runtime.ktx)` (current line 52).
- `testImplementation(libs.kotlinx.coroutines.test)` — adjacent to the existing `testImplementation(libs.junit)` (current line 57). Place it directly after `testImplementation(libs.junit)`.

Why `lifecycle-viewmodel-ktx` is needed: at lifecycle 2.6.1, `viewModelScope` lives in the `-ktx` artifact, not in `lifecycle-viewmodel` proper. `koin-androidx-compose` brings in `lifecycle-viewmodel-compose` transitively, which depends on `lifecycle-viewmodel` (no `-ktx`). Without the explicit dependency, `viewModelScope` won't resolve. If a future lifecycle bump (≥2.9) merges `viewModelScope` into the base artifact, this `-ktx` dependency can be dropped — but that's a lifecycle-upgrade ticket, not this one.

### Verification — first-binding smoke

No `koin-test` / `verify()` is added by this ticket — per the conventions in `docs/knowledge/features/dependency-injection.md` ("`koin-test` is deliberately not on the classpath yet"). The signal that the binding wires correctly is `./gradlew assembleDebug` (compile + manifest merge) plus the new VM unit test running successfully (`./gradlew test`).

If a later ticket pulls in `koin-test` for a binding-verification reason, that ticket can also asserter on this binding retroactively.

## State + concurrency model

- **Scope:** `viewModelScope` — supervisor-Job-backed; cancelled on `ViewModel.onCleared()`. No additional `CoroutineScope` is owned by this VM.
- **Dispatcher:** the upstream `observeConversations` flow inherits the collector's dispatcher (`Dispatchers.Main.immediate` from `viewModelScope`). The fake's projection is pure CPU map manipulation — no `flowOn(IO)` needed. Phase 4's remote impl decides its own dispatcher; the VM stays dispatcher-agnostic.
- **Hot vs cold:** upstream `Flow` is cold; `stateIn` converts it to a hot `StateFlow<ChannelListUiState>` shared across collectors. `WhileSubscribed(5_000)` cancels the upstream collection 5 seconds after the last subscriber unsubscribes (typically the screen leaving the composition).
- **Cancellation:** automatic via `viewModelScope` + `WhileSubscribed`. No manual `Job` handling. Configuration changes (rotation) keep the VM alive; the 5-second grace prevents collector restart churn.
- **Subscription semantics:** `state.collectAsStateWithLifecycle()` on the composable side (next ticket) — when the lifecycle drops below STARTED, the collector unsubscribes; the upstream stays alive for 5s in case of immediate re-entry.
- **No parallel mutable state.** A single `StateFlow<ChannelListUiState>` is the only state surface. No sibling `MutableStateFlow` for "isLoading" or "errors" — those are state variants, not orthogonal axes.

## Error handling

- **Source of failures:** any `Throwable` emitted from the upstream `observeConversations` flow. Today's fake never throws — but the interface contract permits it (per `docs/knowledge/features/conversation-repository.md`: "Stream-level errors terminate the flow and are caught at the UI's `catch { }`"), and Phase 4's remote impl will throw on network/IO failures.
- **Surface:** single `ChannelListUiState.Error(message: String)` terminal state. Error rendering (banner vs full-screen) is the screen ticket's call; the VM only commits to "non-blank message" per AC.
- **Message extraction:** `e.message` verbatim when non-null/non-blank, else the literal fallback `"Failed to load channels."`. No `e.toString()`, no `e::class.simpleName` — exception class names are implementation detail and would leak through to user-facing UI.
- **Error is terminal in the underlying flow.** `catch { emit(Error(...)) }` consumes the exception and emits one terminal state; the upstream flow has already errored out and will not produce further values. To recover, the user must re-enter the screen (subscriber drops to zero, `WhileSubscribed(5_000)` expires, then a new subscription starts a fresh collection). A `retry()` event-driven recovery path is deferred to whichever ticket adds a "Retry" button on the screen.
- **Programmer-error exceptions don't apply here.** The VM does not call any of the mutator suspend functions (`promote`, `archive`, `rename`, etc.) — those throw `IllegalArgumentException` for unknown ids, but this VM only `observe`s. Defer mutator-error handling to the tickets that introduce promotion/archive UI.
- **No logging.** No `Log.e(...)` in the VM. Phase 4 will introduce a logging strategy alongside the network layer; until then, the test suite is the diagnostic surface and an `Error` state is observable from the screen.

## Testing strategy

One new test file: `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`.

**Scope:** the three transitions AC names — `Loading → Loaded`, `Loading → Empty`, `Loading → Error` — exercised against a hand-rolled test stub repository, not `FakeConversationRepository`. Reason: the fake's projection emits synchronously from a populated `MutableStateFlow`, so `Loading` is unobservable in practice — by the time the test collects, the first non-Loading state has already arrived. A controllable stub backed by `MutableSharedFlow` (replay = 0, no initial value) gives deterministic Loading-then-X sequencing.

### Test-class shape

```kotlin
package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isLoading() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        assertEquals(ChannelListUiState.Loading, vm.state.value)
    }

    @Test
    fun loaded_whenSourceEmitsNonEmpty() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        val collector = launch { vm.state.collect { /* keep upstream hot */ } }
        source.emit(listOf(sampleChannel))
        assertEquals(ChannelListUiState.Loaded(listOf(sampleChannel)), vm.state.value)
        collector.cancel()
    }

    @Test
    fun empty_whenSourceEmitsEmptyList() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        val collector = launch { vm.state.collect { } }
        source.emit(emptyList())
        assertEquals(ChannelListUiState.Empty, vm.state.value)
        collector.cancel()
    }

    @Test
    fun error_whenSourceFlowThrows() = runTest {
        val erroring = object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                flow { throw RuntimeException("network down") }
            // mutators + observeMessages: throw NotImplementedError — see helper notes below
            override fun observeMessages(conversationId: String) = TODO("not used")
            override suspend fun createDiscussion(workspace: String?) = TODO("not used")
            override suspend fun promote(conversationId: String, name: String, workspace: String?) = TODO("not used")
            override suspend fun archive(conversationId: String) = TODO("not used")
            override suspend fun rename(conversationId: String, name: String) = TODO("not used")
            override suspend fun startNewSession(conversationId: String, workspace: String?) = TODO("not used")
            override suspend fun changeWorkspace(conversationId: String, workspace: String) = TODO("not used")
        }
        val vm = ChannelListViewModel(erroring)
        val collector = launch { vm.state.collect { } }
        val state = vm.state.value
        assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
        assertEquals("network down", (state as ChannelListUiState.Error).message)
        collector.cancel()
    }

    // --- helpers ---

    private fun stubRepo(source: MutableSharedFlow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source
            override fun observeMessages(conversationId: String) = TODO("not used")
            override suspend fun createDiscussion(workspace: String?) = TODO("not used")
            override suspend fun promote(conversationId: String, name: String, workspace: String?) = TODO("not used")
            override suspend fun archive(conversationId: String) = TODO("not used")
            override suspend fun rename(conversationId: String, name: String) = TODO("not used")
            override suspend fun startNewSession(conversationId: String, workspace: String?) = TODO("not used")
            override suspend fun changeWorkspace(conversationId: String, workspace: String) = TODO("not used")
        }

    private val sampleChannel = Conversation(
        id = "test-channel",
        name = "Test",
        cwd = "~/test",
        currentSessionId = "s1",
        sessionHistory = listOf("s1"),
        isPromoted = true,
        lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
    )
}
```

### Why this shape

- **`UnconfinedTestDispatcher` as Main.** Propagates emissions eagerly so the test body can read `vm.state.value` after `source.emit(...)` without explicit `runCurrent()` / `advanceUntilIdle()`. The `StandardTestDispatcher` alternative would force `runCurrent()` between every line; `UnconfinedTestDispatcher` matches the "set a value, read a value" mental model.
- **`launch { vm.state.collect { } }` to keep the upstream hot.** `WhileSubscribed(5_000)` only starts collecting upstream once `state` has a subscriber. Without a launched collector, `source.emit(...)` would have no listener and the test would observe `Loading` forever. The `initialState_isLoading` test deliberately *doesn't* subscribe — it relies on `stateIn`'s `initialValue` being immediately visible on `.value` regardless of subscription.
- **`MutableSharedFlow(replay = 0)`** — gives the test full control over emission timing. A `MutableStateFlow` would force an initial value at construction, defeating the "no value yet" semantics needed for the Loading transition.
- **Inline anonymous `ConversationRepository` stubs.** No shared base class, no test-only `TestConversationRepository` file. Three short stubs at the bottom of the test file — readable, no indirection. Each stub `TODO("not used")` for the unused methods; if a future test does call them, the helpful failure message points back to the same file.
- **`@OptIn(ExperimentalCoroutinesApi::class)` at the class level.** `Dispatchers.setMain` / `resetMain` and `UnconfinedTestDispatcher` are flagged experimental. Class-level opt-in is canonical for test classes; per-call would be noisy.
- **JUnit 4** to match existing test style (`FakeConversationRepositoryTest`, `AppPreferencesTest`). Not JUnit 5 — not on the classpath, and not worth bringing in.

### What the test suite does NOT cover

- **Koin resolvability.** The AC says "resolvable from the composition root" — verified by `./gradlew assembleDebug` (Koin doesn't crash at startup since `appModule` is loaded synchronously) plus the existing `koin-test`-free convention. Adding `koin-test` for one assertion is the "defense for an unobserved failure mode" anti-pattern called out in spec #32.
- **`WhileSubscribed` cancellation timing.** Asserting the exact 5-second grace would couple the test to virtual time and `WhileSubscribed`'s internals. Trust the kotlinx-coroutines library here; cover edge cases when a real bug surfaces.
- **The `FakeConversationRepository` ↔ VM integration.** Out of scope; the repository's own test suite already validates the projection.
- **`ChannelListEvent` / `onEvent`.** Doesn't exist yet. Defer to #46 / tap-nav ticket.

### Commands

- `./gradlew test` — passes, includes the four new test methods above.
- `./gradlew assembleDebug` — passes; proves the catalog additions resolve, the new VM compiles, the Koin module is parseable.
- No instrumented (`connectedAndroidTest`) coverage — the VM has no Android dependencies beyond `ViewModel`/`viewModelScope`, and those are exercised by the unit suite via `Dispatchers.setMain`.

## Edit list (developer's checklist)

1. **`gradle/libs.versions.toml`** — add two `[libraries]` entries (`androidx-lifecycle-viewmodel-ktx`, `kotlinx-coroutines-test`). No `[versions]` change.
2. **`app/build.gradle.kts`** — add `implementation(libs.androidx.lifecycle.viewmodel.ktx)` and `testImplementation(libs.kotlinx.coroutines.test)`.
3. **`app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`** — new file. Sealed `ChannelListUiState` (4 variants) + `ChannelListViewModel` class as shown above.
4. **`app/src/main/java/de/pyryco/mobile/di/AppModule.kt`** — add imports, add two binding lines, delete the three-line TODO comment block.
5. **`app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`** — new file. Four JUnit 4 tests covering the three transitions + initial Loading observation.
6. **Run `./gradlew test`** — must pass (4 new tests green; all existing tests still green).
7. **Run `./gradlew assembleDebug`** — must pass (catalog/build/manifest still valid; Koin module compiles with the new bindings).

## Open questions

None.

The only judgment call worth flagging for review: `viewModel { ... }` DSL import path. The `org.koin.androidx.viewmodel.dsl.viewModel` import is the canonical Koin 4.0.x form and resolves cleanly against the BOM (`4.0.4`) currently pinned. If a future Koin BOM bump deprecates it in favor of `org.koin.core.module.dsl.viewModel`, the swap is a single import line — no semantic change. Not a blocker.
