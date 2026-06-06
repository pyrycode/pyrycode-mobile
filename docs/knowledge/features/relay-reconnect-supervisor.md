# Relay reconnect supervisor — auto-reconnect + the live `ConnectionStateSource`

The **Phase 4 reconnect-policy layer**: the loop that sits **on top of** the single-connection
[relay WS transport](relay-ws-transport.md) and turns a series of single-use sockets into a continuously
available connection. It re-dials on drop with capped-exponential backoff, maps live socket state onto
the existing [`ConnectionState`](connection-state.md) surface, and is **the real
`ConnectionStateSource`** that the [`ConnectionBanner`](connection-banner.md) reflects — replacing the
Phase-2 `FakeConnectionStateSource` in the Koin graph.

Package: `de.pyryco.mobile.data.network` (`RelayConnectionSupervisor` + `RelayTransportFactory`),
co-located with the [transport](relay-ws-transport.md) it supervises; it implements the
`data/repository` [`ConnectionStateSource`](connection-state.md) interface. Landed in
[#307](../codebase/307.md) (split from #301), on the OkHttp engine ([ADR 0005](../decisions/0005-okhttp-websocket-engine.md))
via #306.

> **No UI change.** The [`ConnectionBanner`](connection-banner.md) (#200) and its `ThreadViewModel`
> wiring (#201) already consume `ConnectionStateSource.observe()` / `retry()`. This layer only changes
> **which implementation** feeds them. The binding is **app-wired**; the
> [lifecycle connection driver](lifecycle-connection-driver.md) ([#302](../codebase/302.md), landed) calls
> `connect()` on foreground / push-wake and `close()` on background — until the first foreground the
> supervisor sits dormant at `Connected` (banner hidden).

## Where it sits in the Phase 4 stack

```
ConnectionBanner (#200) / ThreadViewModel (#201)   ◀── observe() / retry()
        ▲
RelayConnectionSupervisor (#307) ─ reconnect loop, backoff, ConnectionState   ◀── this doc
        │  drives connect()/close(), collects events (Up/Down)
        ▼
RelayTransport (#306) ─ one single-use socket, InnerFrameV2 ⇄ JSON text
        │
OkHttp WS (ADR 0005) ─ TLS / TCP
```

The supervisor owns the **socket lifecycle** (dial / reconnect / backoff). The sibling **Noise session
pump** ([#309](https://github.com/pyrycode/pyrycode-mobile/issues/309)) owns the **Noise session** over the *same* live connection. They consume
**different** #306 streams — `events` here, `inbound` there — so they don't contend on #306's
single-consumer streams, and there is **no blocker between them** (code-independent). See § Cross-sibling
seams.

## Exported types

```kotlin
/** Builds a fresh single-use RelayTransport per dial. The supervisor discards a spent instance after
 *  its terminal Down and asks for a new one to reconnect (#306 sockets are single-use). */
fun interface RelayTransportFactory {
    fun create(pairedServer: PairedServer): RelayTransport
}

class RelayConnectionSupervisor(
    transportFactory: RelayTransportFactory,
    pairedServerStore: PairedServerStore,                       // presence gates whether we dial
    dispatcher: CoroutineDispatcher = Dispatchers.Default,      // injection seam (test clock)
    random: Random = Random.Default,                            // jitter source; seed in tests
) : ConnectionStateSource {
    fun connect()                                  // start the supervision loop (idempotent); driven by #302
    fun close()                                    // stop the loop, tear down the socket, go idle
    override fun observe(): Flow<ConnectionState>  // the live state (a StateFlow under the hood)
    override suspend fun retry()                   // force an immediate reconnect; never throws
    val currentConnection: StateFlow<RelayTransport?>  // live instance (Up) or null — consumed by the #351 coordinator
}
```

`RelayTransportFactory` is a `fun interface`: the supervisor reconnects by **constructing a fresh
transport** (the #306 single-use contract), so it needs a per-dial builder it can fake. Production binds
a lambda closing over the shared `WebSocket.Factory` ([`defaultClient()`](relay-ws-transport.md)) +
`NoiseClientInfo`.

## The state machine

A single `MutableStateFlow<ConnectionState>` (initial value **`Connected`**) is the only state source;
`observe()` exposes it via `asStateFlow()`. The supervision loop runs as one child `loopJob` of an
app-singleton `CoroutineScope(SupervisorJob() + dispatcher)`.

```
load PairedServer
  └─ null → state = Connected; return        // benign-unpaired: no dial, banner hidden
  └─ present → run the loop:

attempt = 0                                   // consecutive failures since the last ≥60 s-stable connection
while active:
  state = Connecting                          // a dial is in flight
  transport = factory.create(paired); transport.connect()
  collect transport.events until it completes (terminal Down):
      Up   → state = Connected; currentConnection = transport
             launch stability timer (delay 60 s → stableReached = true)
      Down → no-op (recorded; collect completes next — single terminal Down)
  finally: cancel stability timer (before reading the flag); currentConnection = null; transport.close()
  if (sawUp && stableReached) attempt = 0     // ≥60 s stable → reset escalation
  attempt += 1
  backoff(attempt)                            // Reconnecting countdown OR Offline; collapsible by retry()
```

**`ConnectionState` mapping** (the 4 cases, no schema change):

| State | When | Banner |
|---|---|---|
| `Connecting` | a dial is in flight | `"Connecting…"` |
| `Connected` | transport `Up` (**socket-open** — see § Cross-sibling seams A) | hidden |
| `Reconnecting(secondsRemaining)` | counting down a **sub-cap** backoff interval (per-second) | `"Reconnecting in Ns"` |
| `Offline` | backoff escalated to the **30 s cap** (sustained unavailability) | `"Offline — tap to retry"` |

The AC-observed transition on an unexpected drop is
**`Connected → Reconnecting(secondsRemaining) → Connecting → Connected`** (`backoff()` emits
`Reconnecting` before the loop re-sets `Connecting`, with no spurious intermediate state).

## Backoff cadence

The wire-spec cadence (`protocol-mobile.md` § Reconnect, mirrored from the Go sibling pyrycode #247):

- **Base seconds** `backoffBaseSeconds(attempt)` = `1, 2, 4, 8, 16` for attempts 1–5, then `30` (cap) for
  attempt ≥ 6 (`if (attempt >= 6) 30 else (1 shl (attempt - 1))`).
- **±20 % jitter** `jitteredBackoffMs(attempt, random)` = `base * 1000 * (0.8 + random.nextDouble() * 0.4)`
  → `[0.8, 1.2)`. **Invariant: exactly one `random.nextDouble()` per interval**, so a seeded `Random`
  replays deterministically under the test clock (the test asserts *exact* ms, not a tolerance band).
- **Sub-cap (`base < 30`):** a **per-second** `Reconnecting(ceil(remainingMs / 1000.0))` countdown, each
  second a collapsible wait; then loop back to `Connecting`.
- **At the cap (`base == 30`):** emit `Offline` (no countdown), one collapsible wait, then **re-dial
  anyway**. `Offline` is "sustained unavailability → tap to retry", **not** a terminal stop — the loop
  keeps retrying every ~30 s while showing `Offline`.
- **Stability reset:** `attempt` resets to 0 only after a connection has been **stable ≥ 60 s** (a child
  `stabilityTimer` flips an `AtomicBoolean`). A connection that flaps in < 60 s keeps escalating.

## `retry()` — collapse the pending backoff

Each collapsible wait is `withTimeoutOrNull(ms) { retrySignal.receive() }` — a clean **stable-API** race
of the (virtual-clock) timeout against a `Channel<Unit>(CONFLATED)` retry signal: non-null ⇒ a retry
arrived (reconnect now), null ⇒ the wait fully elapsed. `retry()`:

1. **ensures the loop is running** (calls the same start path as `connect()` — so a "tap to retry"
   arriving while idle/unpaired re-checks paired state and **stays idle if still unpaired**, no spurious
   `Offline`), then
2. `retrySignal.trySend(Unit)` (CONFLATED → a burst of taps collapses to one wakeup).

It is **non-blocking and never throws** — failures surface only as `ConnectionState` transitions.
**Decision:** `retry()` does **not** reset `attempt` — escalation tracks sustained unavailability, and a
manual retry that immediately re-fails must not let the user hammer the relay from 1 s (an anti-storm
property, see § Security posture).

## Cross-sibling seams (the two "architect's call" decisions)

**A. `Connected` = socket-open (transport `Up`), not Noise-session-open.** The in-scope default. Gating
`Connected` on #309's Noise-handshake-completion signal would couple this layer to #309 (which has *no*
blocker relationship) and is out of scope. A future ticket may refine `Connected` to mean end-to-end
(Noise) readiness once #309's signal exists, if product wants the banner to reflect E2E readiness.
**Documented, not defaulted by omission.**

**B. Transport-instance ownership — `currentConnection: StateFlow<RelayTransport?>` is the
non-precluding seam.** The supervisor constructs each instance via `RelayTransportFactory`, owns its
lifecycle, and collects **only `events`** — it **never collects `inbound`** (that single-consumer stream
is #309's). It publishes the live instance on `currentConnection` (set on `Up`, cleared on
`Down`/`close()`), so the layer-up [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
([#351](../codebase/351.md), **landed**) hands the same connection to a **fresh #309 pump per connection**.
Coordinating the two over one instance lives a layer up, not in either sibling — this seam exists solely
so the supervisor's internal construction doesn't preclude it.

## State & concurrency model

- **One app-singleton scope** (`SupervisorJob() + dispatcher`, `Dispatchers.Default` in production — the
  loop is orchestration + `delay` + `StateFlow` writes; OkHttp owns the socket threads). The scope is
  never torn down; `connect()` launches `loopJob`, `close()` cancels it.
- **Single state source** — one `MutableStateFlow`, written **only** on the loop coroutine.
- **`connect()` idempotency** — `if (loopJob?.isActive == true) return`, so a repeated/over-eager
  `connect()` (a buggy #302) can't spawn a second loop = two concurrent dials on one transport surface.
  `connect()`/`close()` are `@Synchronized`.
- **`try/finally` releases the socket** on **both** a normal `Down` and loop cancellation — including a
  transport cancelled mid-dial **before** `Up` (which `currentConnection.close()` alone would miss, since
  `currentConnection` is only set on `Up`).
- **Stability flag is an `AtomicBoolean`** — written by the timer child, read by the loop **after**
  `stabilityTimer.cancel()`. Under a multi-threaded dispatcher, `cancel()` is not a memory barrier, so a
  plain `var` read could be stale; the atomic guarantees the loop sees the write. The boundary case
  (write lands at ~exactly 60 s as we cancel) is benign — either answer to "was it ≥60 s stable?" is
  acceptable.
- **`close()`** sets `state = Connected` (intentional disconnect, not an error → banner hidden);
  cancellation skips the post-`finally` code, so the loop can't overwrite it.

## Security posture

The spec's § Security review verdict is **PASS** (one MUST-FIX — the `AtomicBoolean` above — found and
addressed in self-review). The supervisor reacts to **transport events only** and never crosses the
untrusted-relay boundary:

- **No `inbound`, no frame decode.** It collects only `events` (`Up`/`Down`); #306 owns the
  untrusted-content boundary and surfaces only typed `TransportEvent`s. `PairedServer` is passed
  **opaquely** to the factory — not inspected, parsed, or persisted here.
- **No logging.** The only outward signal is the 4-case `ConnectionState` (no strings beyond
  `secondsRemaining: Int`). `PairedServer`, `relayUrl`, the transport, and `Down`'s `code`/`reason`/
  `cause` are **never** logged (mirrors #306's posture; code-review confirmed zero `Log`/`Timber`/
  `println`).
- **Anti-reconnect-storm (security-positive).** Capped-exponential backoff + `retry()` **not** resetting
  `attempt` + escalation-only-after-genuine-stability (the ≥60 s reset) + a CONFLATED retry channel
  (`retry()`-spam collapses to one wakeup) together prevent a client-side reconnect storm /
  token-exhaustion loop against a hostile or flapping relay.
- **TLS/timeouts/frame-cap inherited unchanged** from #306's `defaultClient()`; the factory passes the
  **one** shared client as-is (no `HttpLoggingInterceptor`, no TLS reconfig — a fresh client per reconnect
  would leak thread pools). The jitter RNG is `kotlin.random.Random` — explicitly a **non-security**
  (backoff-timing) use.
- **`4401` auth-reject handling is out of scope** — named to [#308](https://github.com/pyrycode/pyrycode-mobile/issues/308). This layer treats every
  `Down` uniformly as "backoff + redial"; a code comment marks the `Down.code` seam.

## Edge cases & limitations

- **Benign-unpaired** — no stored `PairedServer` → no dial, stays `Connected` (banner hidden), and a
  tap-to-retry re-checks and stays idle. Never regresses into a spurious `Offline`/error.
- **`Offline` is not terminal** — the loop keeps redialing every ~30 s at the cap.
- **Process death mid-loop** leaves nothing partial — no durable state (the #306 non-resumable contract);
  on relaunch, #302 drives a fresh `connect()`.
- **`Connected` is socket-level, not E2E** — until a future ticket gates it on #309 (decision A).

## Testing

JVM-only (`app/src/test/.../RelayConnectionSupervisorTest.kt`, `./gradlew test`), `runTest` virtual clock
— the supervisor is pure orchestration + `delay`, so virtual time advances it exactly (contrast the #306
transport, fed by *real* OkHttp threads, which uses `runBlocking { withTimeout }`). Test doubles live
in-file (no production fake): a `FakeRelayTransport` driving `events` via `emitUp()`/`emitDown()`, a
`FakeRelayTransportFactory` recording each `created` instance, a `StubPairedServerStore`. Jitter is
deterministic via a **seeded `Random(SEED)`**; `intervalsFor(vararg attempts)` replays
`jitteredBackoffMs` through a fresh `Random(SEED)` to compute exact expected ms.

> **Test-harness note (reusable):** settle event-driven transitions with **`runCurrent()`** (no virtual
> time elapses, so the 60 s stability timer can't fire by accident) and reserve
> `advanceTimeBy`/`advanceUntilIdle()` for the backoff waits. Mixing them silently fires the stability
> timer mid-test and resets escalation. See [`codebase/307.md`](../codebase/307.md) § Lessons learned.

Coverage: single-drop per-second countdown + recover on a fresh transport; backoff bases 1/2/4/8/16
(jitter band); `Offline` at the cap + continued retry; `retry()` collapse + no-throw; ≥60 s stability
reset vs <60 s escalation; benign-unpaired (no dial, tap-to-retry stays idle); `close()` teardown.

## Related

- Ticket notes: [`../codebase/307.md`](../codebase/307.md) — files/line refs, patterns, lessons.
- Spec: `docs/specs/architecture/307-relay-reconnect-supervisor-connectionstatesource.md` (§ Design,
  § State + concurrency model, § Cross-sibling decisions A/B, § Security review — Verdict PASS).
- Sits on: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md)) — drives
  `connect()`/`close()`, collects `events`; never `inbound`. Implements [Connection state](connection-state.md)
  ([#196](../codebase/196.md)) `ConnectionStateSource`; swaps its `FakeConnectionStateSource` binding.
  Reads [`PairedServer`](paired-server-store.md) ([#294](../codebase/294.md)) — presence gates the dial.
- Consumer (UI): [`ConnectionBanner`](connection-banner.md) (#200) via `ThreadViewModel` (#201) —
  unchanged; only the bound `ConnectionStateSource` impl changed.
- Siblings: **#309** (Noise session pump — collects the same connection's `inbound` via `currentConnection`;
  no blocker), **[#351](../codebase/351.md)** ([`RelayRepositoryCoordinator`](relay-repository-coordinator.md),
  **landed** — the consumer of `currentConnection`: starts a #309 pump + builds a remote repository per
  live connection), **[#302](../codebase/302.md)** ([lifecycle connection driver](lifecycle-connection-driver.md),
  **landed** — drives `connect()`/`close()` across foreground/background edges via the new
  `RelayConnectionController` seam), **#308** (relay auth-gate — will consume `Down.code == 4401`), **#278**
  (`RemoteConversationRepository`).
- Engine: [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md). Aligns with
  pyrycode-side ADR 024 (relay untrusted; E2E auth is Noise).
</content>
