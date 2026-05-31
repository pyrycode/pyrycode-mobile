# Connection state — model + source contract

The data-layer seam for "is the client connected to the pyrycode server?". Backs the [`ConnectionBanner`](./connection-banner.md) UI shipped in #200 (`Connecting…` / `Reconnecting in Ns` / `Offline — tap to retry` / hidden when `Connected`); the follow-up wiring slice (split from #197) places the banner inside `ThreadScreen` and connects it to `ConnectionStateSource.observe()` / `retry()` via `ThreadViewModel`.

Phase 2 ships a fake that always reports `Connected` and exposes a test/preview seam for driving the other three states; Phase 4 swapped the binding to the OkHttp-WS-backed [`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) ([#307](../codebase/307.md)) behind the same interface.

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

- **`Connected`** — banner hidden (the [`ConnectionBanner`](./connection-banner.md) returns early with zero composition).
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

The load-bearing contract for the wiring slice — `ThreadViewModel` will inject this and call `observe()` to feed `ThreadUiState` plus `retry()` from the [`ConnectionBanner`](./connection-banner.md)'s retry tap (the banner composable itself shipped in #200, no source dependency). Phase 4's walk-back changes the Koin binding only; the interface does not change.

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
- **`retry()` body is empty.** No `delay`, no dispatcher juggling, no jobs to launch. Acceptable because the [`ConnectionBanner`](./connection-banner.md) is hidden under `Connected` anyway — the absence of a state change in Phase 2 is the visible behaviour.
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
- **No removal of `retry()` even though the Phase 2 fake's body is empty.** Removing it now would force a churn rewrite when the wiring slice lands. The ticket Technical Notes explicitly pin the interface as stable.
- **No `StateFlow<ConnectionState>` in the interface return type.** Keeping the interface at `Flow<ConnectionState>` lets Phase 4 pick any flow shape internally (cold `flow { … }` over WebSocket events, etc.) without breaking callers.
- **No validation, no error handling, no logging.** Pure in-memory state in Phase 2; `retry()` does not throw.

## Phase 4 walk-back (landed in [#307](../codebase/307.md))

The real source is [`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) under `data/network/` — **OkHttp-WS-backed** (over the [#306](relay-ws-transport.md) transport), **not Ktor** as this plan originally guessed. What actually changed:

1. `RelayConnectionSupervisor : ConnectionStateSource` was added in `data/network/` (co-located with the transport it supervises), owning the reconnect loop over the single-use #306 transport and mapping its `Up`/`Down` events to `ConnectionState` with capped-exponential backoff.
2. The Koin binding in `AppModule.kt` was swapped: `single { RelayConnectionSupervisor(get(), get()) } bind ConnectionStateSource::class` replaced the `FakeConnectionStateSource` binding. The supervisor is bound as its concrete type too, so the [lifecycle connection driver](lifecycle-connection-driver.md) ([#302](../codebase/302.md), landed) can resolve it and drive `connect()`/`close()` across foreground/background edges. It is **app-wired**, sitting dormant at `Connected` (banner hidden) until the driver's first foreground `connect()`.
3. **`FakeConnectionStateSource` was kept** (only its binding was removed) — the `emit()` seam is still used by `ThreadViewModelTest` / `FakeConnectionStateSourceTest`.

No other call site changed; `ThreadViewModel` consumes the unchanged interface. The interface itself did **not** change (no `StateFlow` leak, no new methods) — exactly the stability the Phase-2 doc promised.

## Related

- Ticket notes: [`../codebase/196.md`](../codebase/196.md) (model + source), [`../codebase/200.md`](../codebase/200.md) (banner UI consumer)
- Specs: `docs/specs/architecture/196-connectionstate-model-stub-source.md`, `docs/specs/architecture/200-connectionbanner-composable.md`
- Parent: #134 → #197 (the UI slice, split into #200 + the follow-up wiring slice).
- Downstream:
  - [`ConnectionBanner`](./connection-banner.md) (#200, landed) — pure-UI consumer that renders this model with the four product copy strings.
  - Follow-up wiring slice (split from #197, landed) — places the banner inside `ThreadScreen`, injects `ConnectionStateSource` into `ThreadViewModel`, and routes `observe()` / `retry()` through.
  - [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md), landed) — the **real** `ConnectionStateSource` over the [#306](relay-ws-transport.md) WS transport; swapped the Koin binding away from the fake and drives the four states from live socket events + backoff.
- Sibling pattern: [`FakeConversationRepository`](conversation-repository.md) (`MutableStateFlow` + `state.map { … }` exposure shape, scaled down to one flow).
- DI: [Dependency injection](dependency-injection.md).
