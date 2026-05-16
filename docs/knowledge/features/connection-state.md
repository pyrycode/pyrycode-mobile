# Connection state — model + source contract

The data-layer seam for "is the client connected to the pyrycode server?". Backs the connection banner UI in #197 (`Connecting…` / `Reconnecting in Ns` / `Offline — tap to retry` / hidden when `Connected`).

Phase 2 ships a fake that always reports `Connected` and exposes a test/preview seam for driving the other three states; Phase 4 swaps the binding to a Ktor/WebSocket-backed implementation behind the same interface.

Packages: `de.pyryco.mobile.data.model` (model) and `de.pyryco.mobile.data.repository` (interface + fake).

## Types

### `ConnectionState`

```kotlin
sealed class ConnectionState {
    data object Connected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Reconnecting(val secondsRemaining: Int) : ConnectionState()
    data object Offline : ConnectionState()
}
```

Four cases, fixed by product copy:

- **`Connected`** — banner hidden.
- **`Connecting`** — initial connect attempt; banner reads `"Connecting…"`.
- **`Reconnecting(secondsRemaining)`** — countdown during reconnect backoff; banner reads `"Reconnecting in Ns"` with the consumer interpolating `secondsRemaining`.
- **`Offline`** — terminal/manual-retry state; banner reads `"Offline — tap to retry"`.

`secondsRemaining` is `Int`, not `kotlin.time.Duration` — the UI interpolates it directly into the literal `"Reconnecting in Ns"` template, so an `Int` keeps the consumer trivial. Promote to `Duration` only if a later revision needs richer copy (e.g. `"Reconnecting in 1m 30s"`).

No `require(secondsRemaining >= 0)` and no `init { }` guard — the fake is the only producer in Phase 2 and Phase 4's real source will validate at its own boundary; constructor invariants here would defend an unobserved failure mode. Same posture as the rest of `data/model/` (see [Data model](data-model.md)).

Lives in its own file `data/model/ConnectionState.kt` — it's a freestanding concern, not a `Conversation`/`Session`/`Message` extension, so it doesn't belong in `Conversation.kt`.

### `ConnectionStateSource`

```kotlin
interface ConnectionStateSource {
    fun observe(): Flow<ConnectionState>
    suspend fun retry()
}
```

The load-bearing contract for #197 — `ThreadViewModel` injects this and calls `observe()` to feed `ThreadUiState` plus `retry()` from the banner's retry tap. Phase 4's walk-back changes the Koin binding only; the interface does not change.

- **`observe()`** — cold `Flow` of the current connection state. Collectors receive the current value on subscription and every subsequent change. Same shape as [`ConversationRepository`](conversation-repository.md)'s `observe*` methods.
- **`retry()`** — `suspend` to leave room for Phase 4's blocking I/O (open WebSocket, await handshake). Phase 4 is expected to transition the observed flow through `Connecting` → `Connected`/`Offline`; **network failures surface as state transitions, not by throwing from this method**. The interface KDoc records this expectation, but no code in Phase 2 enforces it (the fake's body is empty).

Lives under `data/repository/` rather than a new `data/connection/` package because the existing convention is "repository" = any cold-flow + suspend-mutator data source (`ConversationRepository` already does both). A new package tier for a single source would be premature.

## Phase 1 implementation — `FakeConnectionStateSource`

```kotlin
class FakeConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

    override fun observe(): Flow<ConnectionState> = state.asStateFlow()

    override suspend fun retry() { /* Phase 2: no-op */ }

    /** Test/preview seam: push a state into the observed flow. Not part of [ConnectionStateSource]. */
    fun emit(state: ConnectionState) { this.state.value = state }
}
```

- **Single `MutableStateFlow<ConnectionState>`** initialised to `Connected`. Hot stream — new subscribers receive the current value immediately, which is what satisfies the AC "a fresh `FakeConnectionStateSource` emits `Connected`". `observe()` returns `state.asStateFlow()` widened to `Flow<ConnectionState>` so the interface signature doesn't leak the `StateFlow` shape — Phase 4 may pick a different flow internally.
- **`retry()` body is empty.** No `delay`, no dispatcher juggling, no jobs to launch. Acceptable because the banner in #197 is hidden under `Connected` anyway — the absence of a state change in Phase 2 is the visible behaviour.
- **`emit(state)` is the test/preview seam.** Public on the concrete class, **not** on the `ConnectionStateSource` interface — production consumers depend on the interface (resolved via Koin) so they can't reach `emit()` by accident. KDoc explicitly names it "test/preview seam". The same shape as [`FakeConversationRepository`](conversation-repository.md)'s seed pipeline downsized to one mutator.
- **No coroutine scope ownership in the fake** — it has no jobs to launch. Phase 4 may need to take an injected `CoroutineScope` for the connection lifecycle; out of scope for Phase 2.

## Koin binding

```kotlin
// di/AppModule.kt
single { FakeConnectionStateSource() } bind ConnectionStateSource::class
```

Singleton scope (Phase 4 will hold a long-lived WebSocket; binding shape stays the same — only the bound class changes). See [Dependency injection](dependency-injection.md).

## What's deliberately absent

- **No `initialState` constructor parameter on `FakeConnectionStateSource`** (cf. `FakeConversationRepository(initialMessages = …)`). The AC's "test/preview seam" is fully served by `emit()` — previews call `emit()` after construction. Add the constructor param then if a one-line preview-fixture form is wanted later.
- **No removal of `retry()` even though the Phase 2 fake's body is empty.** Removing it now would force a churn rewrite when #197 lands. The ticket Technical Notes explicitly pin the interface as stable.
- **No `StateFlow<ConnectionState>` in the interface return type.** Keeping the interface at `Flow<ConnectionState>` lets Phase 4 pick any flow shape internally (cold `flow { … }` over WebSocket events, etc.) without breaking callers.
- **No validation, no error handling, no logging.** Pure in-memory state in Phase 2; `retry()` does not throw.

## Phase 4 walk-back

1. Add `data/network/` (or similar) with a Ktor-backed `RemoteConnectionStateSource : ConnectionStateSource` that owns the WebSocket and maps connection events to `ConnectionState`.
2. Swap the Koin binding in `AppModule.kt`: `single { RemoteConnectionStateSource(get()) } bind ConnectionStateSource::class`.
3. Delete `FakeConnectionStateSource.kt` (or keep it as a test fixture under `app/src/test/java/...` — the `emit()` seam is genuinely useful for VM tests).

No other call site changes; #197's `ThreadViewModel` consumes the interface.

## Related

- Ticket notes: [`../codebase/196.md`](../codebase/196.md)
- Spec: `docs/specs/architecture/196-connectionstate-model-stub-source.md`
- Parent: split from #134.
- Downstream: [#197](https://github.com/pyrycode/pyrycode-mobile/issues/197) — connection banner that consumes `observe()` and `retry()` from `ThreadViewModel`.
- Sibling pattern: [`FakeConversationRepository`](conversation-repository.md) (`MutableStateFlow` + `state.map { … }` exposure shape, scaled down to one flow).
- DI: [Dependency injection](dependency-injection.md).
