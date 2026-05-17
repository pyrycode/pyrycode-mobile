# Spec: `ConnectionState` model + stub source (#196)

## Context

Phase 2's thread screen needs a stable seam for "is the client connected?". The real source lands in Phase 4 once pyrycode's mobile API ships; this slice introduces the model and a Phase 2 stub that always reports `Connected`, with a test seam for pushing any of the four states.

This is a pure data-layer slice. No Compose, no ViewModel, no UI integration. The banner that consumes this lands in #197 (which is `blocked-by` this ticket).

The interface signature is the load-bearing contract for #197 — `ThreadViewModel` will inject `ConnectionStateSource`, call `observe()` to feed `ThreadUiState`, and call `retry()` from its retry event handler. The Phase 4 walk-back is "swap the Koin binding"; the interface does not change.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:1-67` — interface-file convention: the contract, plus closely-related supporting types, live in one file. `ConnectionStateSource.kt` mirrors this shape (interface lives next to its consumers, not in a generic bucket).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:1-60` — `MutableStateFlow`-backed fake pattern. The `state` private field + `state.map { … }` exposure shape is what `FakeConnectionStateSource` should mirror, downsized to a single `MutableStateFlow<ConnectionState>`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — data-model file style: terse `data class` definitions, KDoc only where non-obvious. `ConnectionState.kt` matches this terseness.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:20-34` — exact spot to add the binding. Line 28 (`single { FakeConversationRepository() } bind ConversationRepository::class`) is the pattern to copy verbatim.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:1-42` — test idiom: `runBlocking` + `Flow.first()`, JUnit4 `assertEquals` / `assertTrue`. New tests should follow this shape (do not introduce Turbine, MockK, or `runTest` — the existing data-layer tests use plain `runBlocking`).
- `app/CLAUDE.md` is absent; root `CLAUDE.md` is the source of truth for stack conventions (MVI, sealed types, Koin, single Gradle module).

## Design

### Package layout

Three new files, one modified file:

```
app/src/main/java/de/pyryco/mobile/
├── data/
│   ├── model/
│   │   └── ConnectionState.kt          (new)
│   └── repository/
│       ├── ConnectionStateSource.kt    (new)
│       └── FakeConnectionStateSource.kt(new)
└── di/
    └── AppModule.kt                     (modify — add 1 binding)
```

Rationale for placing `ConnectionStateSource` under `data/repository/` rather than a new `data/connection/` package: the existing convention is "repository" = any cold-flow + suspend-mutator data source (`ConversationRepository` is doing exactly this, including non-conversation concerns like message streams). Don't introduce a new package tier for a single source — keep the slice minimal.

### `data/model/ConnectionState.kt`

Sealed class with four cases. Three are `object` (no payload); `Reconnecting` carries the countdown seconds.

```kotlin
sealed class ConnectionState {
    data object Connected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Reconnecting(val secondsRemaining: Int) : ConnectionState()
    data object Offline : ConnectionState()
}
```

Use `data object` (Kotlin 1.9+) for parity with how `data class` gives sane `equals`/`hashCode`/`toString` — consistent with the rest of the data model (see `Message.kt`, `Session.kt`). `secondsRemaining` is `Int` (not `Duration`) because the UI in #197 interpolates it into the literal string `"Reconnecting in Ns"`; an `Int` keeps the consumer trivial.

No validation of `secondsRemaining` (no `require(secondsRemaining >= 0)`). The fake is the only producer in Phase 2, and the Phase 4 real source will validate at its own boundary; data-class invariants here would be premature defense per pipeline principle.

### `data/repository/ConnectionStateSource.kt`

Interface only. Two members.

```kotlin
interface ConnectionStateSource {
    fun observe(): Flow<ConnectionState>
    suspend fun retry()
}
```

Contract notes (KDoc on the interface):

- `observe()` is a cold `Flow` — collectors receive the current value on subscription and every subsequent change. Same shape as `ConversationRepository.observeMessages` etc.
- `retry()` is a `suspend` no-op in the Phase 2 fake. In Phase 4 the real implementation will trigger an immediate reconnect attempt and transition through `Connecting` → `Connected` or `Offline`. The Phase 2 fake's no-op contract is intentional: #197's ViewModel calls `retry()` from a button tap, and the absence of a state change in Phase 2 is acceptable because the banner is hidden under `Connected` anyway.

### `data/repository/FakeConnectionStateSource.kt`

Concrete class implementing `ConnectionStateSource`. Single private `MutableStateFlow<ConnectionState>` initialized to `Connected`. `observe()` exposes the flow (use `.asStateFlow()` to widen to read-only `StateFlow<ConnectionState>` which up-casts to `Flow<ConnectionState>` for the interface return). `retry()` body is empty.

Test/preview seam: a public `fun emit(state: ConnectionState)` on the fake (not on the interface) that updates the internal `MutableStateFlow.value`. The naming matches the verb — "push a value into the observed flow". KDoc the method as "test-only seam; do not call from production code." Production consumers depend on the `ConnectionStateSource` interface, not the concrete fake, so they can't reach `emit()` by accident.

```kotlin
class FakeConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

    override fun observe(): Flow<ConnectionState> = state.asStateFlow()

    override suspend fun retry() { /* Phase 2: no-op stub */ }

    /** Test/preview seam: push a state into the observed flow. Not part of [ConnectionStateSource]. */
    fun emit(state: ConnectionState) { this.state.value = state }
}
```

Total: ~10 lines of body.

### `di/AppModule.kt` modification

Add one line in the `module { … }` block, immediately after the existing `FakeConversationRepository` binding (line 28). Add the two imports.

```kotlin
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
// …
single { FakeConnectionStateSource() } bind ConnectionStateSource::class
```

Singleton scope (Phase 4 will hold a long-lived WebSocket; binding shape stays the same).

## State + concurrency model

- `MutableStateFlow<ConnectionState>` is the single source of state in the fake. It's a hot stream; new subscribers receive the current value immediately, which satisfies the AC "a fresh `FakeConnectionStateSource` emits `Connected`".
- `observe()` returns the flow up-cast to `Flow<ConnectionState>` so consumers (the Phase 4 real source, the Phase 2 fake) can choose any flow shape internally without leaking it.
- `retry()` is `suspend` to leave room for the Phase 4 implementation to do blocking I/O (open WebSocket, await connection). Phase 2 body is empty; no `delay`, no dispatcher juggling.
- No coroutine scope ownership inside the fake — it has no jobs to launch. Phase 4 may need to take an injected `CoroutineScope` for the connection lifecycle; that's not in scope for this slice.

## Error handling

None. Pure in-memory state in Phase 2. `retry()` does not throw. The interface KDoc may note that the Phase 4 implementation is expected to surface network failures by transitioning the observed `Flow` to `Offline` (rather than throwing from `retry()`), but no code in this slice enforces that.

## Testing strategy

Unit tests only (`./gradlew test`). No instrumented tests. New file: `app/src/test/java/de/pyryco/mobile/data/repository/FakeConnectionStateSourceTest.kt`.

Use the same idiom as `FakeConversationRepositoryTest`: JUnit4, `runBlocking`, `Flow.first()` for one-shot reads, plain `assertEquals`. **Do not** add Turbine or MockK as dependencies — the existing data-layer tests work fine without them, and adding them in this slice would be scope creep.

Test scenarios (the developer writes the function bodies in the project idiom):

1. **`observe_emits_Connected_by_default`** — construct a fresh `FakeConnectionStateSource`, call `observe().first()`, assert it equals `ConnectionState.Connected`.
2. **`emit_drivesAllFourStates_inOrder`** — construct the fake. Collect `observe()` into a list while sequentially calling `emit(Connecting)`, `emit(Reconnecting(5))`, `emit(Offline)`, `emit(Connected)`. Assert the collected list equals `[Connected, Connecting, Reconnecting(5), Offline, Connected]` (initial value + four pushes). Implementation hint for the developer: `kotlinx.coroutines.flow.take(5).toList()` inside `runBlocking`, with the four `emit` calls launched in a separate coroutine that the test joins; or — simpler — push the four values eagerly into the `MutableStateFlow` before subscribing isn't valid here because `MutableStateFlow` only retains the latest value. So the test must subscribe first, push concurrently, then await collection. Reference `kotlinx.coroutines.flow.take` + `kotlinx.coroutines.async`.
3. **`retry_is_callable_without_throwing`** — call `runBlocking { source.retry() }` and assert no exception. (A one-liner.)

The "drives all four states in order" test is the only one with mild coroutine choreography. If the developer finds the `MutableStateFlow` conflation drops intermediate emissions in practice, fall back to asserting that each `emit` individually surfaces via a fresh `.first()` (loop the four states, emit, assert) — the AC is "surfaces each in order via `observe()`", which a per-state `first()` satisfies. Document that fallback inline.

No Koin/DI test in this slice. `AppModule.kt` is already covered by app-level smoke (the existing modules don't have per-module unit tests either).

## Open questions

- **Should `retry()` be on the interface at all in Phase 2 if it's a no-op?** Yes — the ticket's Technical Notes explicitly say the interface should be stable for #197. Removing `retry()` now would force a churn rewrite when #197 lands. Keep it.
- **Should `FakeConnectionStateSource` accept an `initialState` constructor parameter** (mirroring `FakeConversationRepository(initialMessages = …)`)? Not in this slice — the AC's "test-only seam" is satisfied by `emit()`, and previews can call `emit()` after construction. If a future preview needs a one-line constructor form, add the parameter then. Avoid speculative API surface.
- **`ConnectionState.Reconnecting` payload type**: kept as `Int secondsRemaining` per ticket Context. If a later revision wants `Duration` for richer UI (e.g. "Reconnecting in 1m 30s"), that's a model change, not in scope here.
