# Relay reconnect supervisor — auto-reconnect + the live `ConnectionStateSource`

The **Phase 4 reconnect-policy layer**: the loop that sits **on top of** the single-connection
[relay WS transport](relay-ws-transport.md) and turns a series of single-use sockets into a continuously
available connection. It re-dials on drop with capped-exponential backoff and is **the real
`ConnectionStateSource`** that the [`ConnectionBanner`](connection-banner.md) reflects — replacing the
Phase-2 `FakeConnectionStateSource` in the Koin graph.

> **Since [#391](../codebase/391.md): the source of truth is the relay-leg model, not
> `ConnectionState`.** The supervisor's single hot state is now a `MutableStateFlow<RelayLinkStatus>`
> (the [relay link status](relay-link-status.md) — the four `ConnectionState` cases **plus**
> `DaemonAbsent`), exposed read-only as `val relayStatus: StateFlow<RelayLinkStatus>`. The legacy 4-case
> [`ConnectionState`](connection-state.md) surface is **derived per-collector** from it, so every
> existing consumer is untouched. The `#308 seam` Down arm now branches the relay's `4404 "no server"`
> close into `DaemonAbsent` (relay reachable, no daemon registered) while every other code retries as
> before. This doc describes the post-#391 state.

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
    fun connect()                                  // start the supervision loop (idempotent); driven by #302, and by the Scanner on a fresh pairing (#489)
    fun close()                                    // stop the loop, tear down the socket, go idle
    override fun observe(): Flow<ConnectionState>  // legacy surface, DERIVED per-collector from relayStatus (#391)
    override suspend fun retry()                   // force an immediate reconnect; never throws
    val currentConnection: StateFlow<RelayTransport?>  // live instance (Up) or null — consumed by the #351 coordinator
    val relayStatus: StateFlow<RelayLinkStatus>    // #391 relay-leg source of truth (5 cases incl. DaemonAbsent); #392 zips it
}
```

`relayStatus` and `currentConnection` are plain public `val`s on the **concrete** supervisor (not on
the `ConnectionStateSource` interface, no Koin change) — [#392](https://github.com/pyrycode/pyrycode-mobile/issues/392)
fetches `relayStatus` off the concrete type exactly like the coordinator fetches `currentConnection`.
`observe()` is the **legacy single-signal** surface, derived from `relayStatus` via
`state.map { it.toConnectionState() }` (`DaemonAbsent → Offline`); its return type is unchanged, so
the [`ConnectionBanner`](connection-banner.md)/`ThreadViewModel` consume it untouched. See
[relay link status](relay-link-status.md) for the leg model and the Strangler-Fig rationale.

`RelayTransportFactory` is a `fun interface`: the supervisor reconnects by **constructing a fresh
transport** (the #306 single-use contract), so it needs a per-dial builder it can fake. Production binds
a lambda closing over the shared `WebSocket.Factory` ([`defaultClient()`](relay-ws-transport.md)) +
`NoiseClientInfo`.

## The state machine

A single `MutableStateFlow<RelayLinkStatus>` (initial value **`Connected`**) is the only state source
(since [#391](../codebase/391.md); was `ConnectionState` before). `relayStatus` exposes it via
`asStateFlow()`; the legacy `observe()` derives the 4-case `ConnectionState` from it per-collector. The
supervision loop runs as one child `loopJob` of an app-singleton
`CoroutineScope(SupervisorJob() + dispatcher)`.

```
attempt = 0                                   // consecutive failures since the last ≥60 s-stable connection
while active:
  load PairedServer                           // re-read EVERY dial (#489) — a re-pair to B is picked up on the next dial
    └─ null → state = Connected; return       // unpaired / un-paired mid-loop / undecryptable: no dial, banner hidden, loop ends
  state = Connecting                          // a dial is in flight
  transport = factory.create(paired); transport.connect()
  collect transport.events until it completes (terminal Down):
      Up   → state = Connected; currentConnection = transport
             launch stability timer (delay 60 s → stableReached = true)
      Down → daemonAbsent = (event.code == 4404)   // #308 seam: read-only, never logged; else false
  finally: cancel stability timer (before reading the flag); currentConnection = null IFF still this loop's own transport (#496 identity compare-and-clear); transport.close() (always)
  if (sawUp && stableReached) attempt = 0     // ≥60 s stable → reset escalation
  attempt += 1
  backoff(attempt, daemonAbsent)              // drains a stale pre-drop retry signal FIRST (#498), then: DaemonAbsent | Reconnecting countdown | Offline; each wait collapsible by retry()
```

`daemonAbsent` is a per-dial-iteration `var` (same closure-capture pattern as `sawUp`), set from the
relay's WS close code in the `#308 seam` Down arm and threaded into `backoff()`.

> **Since [#489](../codebase/489.md): the `load()` is per-dial, not once at loop start.** The
> `pairedServerStore.load()` + `null → Connected; return` guard moved from **before** `while (isActive)`
> to the **top of the loop body**, so every dial re-reads the paired record. A loop started against
> server A therefore picks up a re-pair to B on its **next** dial (when A's socket drops), instead of
> dialing the A record captured once at loop start. `var attempt` stays before the loop (escalation spans
> dials). The `null` branch still `return`s (ends the job) — **not** `continue`, which would busy-spin;
> a later `connect()`/`retry()` starts a fresh loop that re-reads the store. The old benign-unpaired
> behaviour is unchanged — it's now just the first-iteration case of a guard that applies every dial.
> Re-pair-**while-connected** does not converge until A's socket drops (`connect()` is idempotent, so a
> second `connect()` no-ops); an *immediate* teardown-and-reconnect on re-pair is out of scope (a PO
> follow-up). See [`codebase/489.md`](../codebase/489.md).

**Relay-leg → banner mapping** (the leg has 5 cases; `observe()` derives the legacy `ConnectionState`):

| `RelayLinkStatus` | When | Derived `ConnectionState` | Banner |
|---|---|---|---|
| `Connecting` | a dial is in flight | `Connecting` | `"Connecting…"` |
| `Connected` | transport `Up` (**socket-open** — see § Cross-sibling seams A) | `Connected` | hidden |
| `Reconnecting(secondsRemaining)` | counting down a **sub-cap** backoff interval (per-second) | `Reconnecting(secondsRemaining)` | `"Reconnecting in Ns"` |
| `DaemonAbsent` | a `4404` close: **relay reachable, no daemon registered** (steady, no countdown) | `Offline` | `"Offline — tap to retry"` (until #392's combined banner) |
| `Offline` | backoff escalated to the **30 s cap** (sustained unavailability) | `Offline` | `"Offline — tap to retry"` |

`DaemonAbsent` derives to `Offline` (nearest legacy meaning — the relay is up but unusable
end-to-end); #392's combined banner gives it its own copy. The AC-observed transition on an
unexpected (non-4404) drop is
**`Connected → Reconnecting(secondsRemaining) → Connecting → Connected`** (`backoff()` emits
`Reconnecting` before the loop re-sets `Connecting`, with no spurious intermediate state).

## Backoff cadence

The wire-spec cadence (`protocol-mobile.md` § Reconnect, mirrored from the Go sibling pyrycode #247):

- **Base seconds** `backoffBaseSeconds(attempt)` = `1, 2, 4, 8, 16` for attempts 1–5, then `30` (cap) for
  attempt ≥ 6 (`if (attempt >= 6) 30 else (1 shl (attempt - 1))`).
- **±20 % jitter** `jitteredBackoffMs(attempt, random)` = `base * 1000 * (0.8 + random.nextDouble() * 0.4)`
  → `[0.8, 1.2)`. **Invariant: exactly one `random.nextDouble()` per interval**, so a seeded `Random`
  replays deterministically under the test clock (the test asserts *exact* ms, not a tolerance band).
  `intervalMs` is computed **before** the `daemonAbsent` branch (below), so the daemon-absent path
  consumes one `nextDouble()` like every other path — this alignment is what lets the test prove the
  schedule is **reused, not replaced** (#391).
- **Daemon-absent (`event.code == 4404`):** emit a **steady** `DaemonAbsent` (no per-second countdown —
  it's a distinct state, not a counting-down reconnect), one collapsible wait on the **same**
  attempt-based jittered interval, then re-dial — so the leg flips off the instant a daemon registers
  and the next dial succeeds. `attempt` still escalates to the 30 s cap under sustained daemon-absence
  (worst-case ~30 s to notice a freshly-registered daemon); `retry()` still collapses the wait, so
  tap-to-retry works during `DaemonAbsent`. (#391)
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

> **Since [#498](../codebase/498.md): `backoff()` drains a stale `retrySignal` before its first wait.** The
> CONFLATED channel *retains* an un-consumed `Unit`, so a `retry()` fired while the loop is inside
> `events.collect` (**`Connected`** — no receiver parked) or during the `Connecting` window leaves a stale
> signal buffered with nothing to collapse. Without a drain, the *next* drop's first `collapsibleWait`
> `receive()` consumed that stale `Unit` and re-dialled instantly — shortening a backoff that should have
> waited its full jittered interval. `backoff()` now runs `while (retrySignal.tryReceive().isSuccess) {}` at
> its **top**, before the `daemonAbsent`/sub-cap/cap branches, so it drops exactly the pre-drop signals and
> nothing else. Placement is load-bearing: the drain is **not** inside `collapsibleWait` (which runs
> per-second in the sub-cap countdown and would swallow a legitimate *in-progress* retry) — at the top of
> `backoff()` it runs once per drop, before the first `receive()`, so a `retry()` arriving *during* a wait
> still collapses it (it's `trySend`'d after the drain). LOW/self-correcting; `USE_RELAY_REPOSITORY`-only.
> A narrow multi-threaded-dispatcher window (a `trySend` racing into the gap between `events.collect`
> completing and the drain) is knowingly left undefended per Evidence-Based Fix Selection — the gentler,
> same-class edge, non-reproducing on the single-threaded test dispatcher. See
> [`codebase/498.md`](../codebase/498.md).

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
  `currentConnection` is only set on `Up`). **`transport.close()` is unconditional** — every loop always
  releases its own socket.
- **Since [#496](../codebase/496.md): the `finally`'s `currentConnection` clear is an identity
  compare-and-clear, not an unconditional null.** `connect()`/`close()` are `@Synchronized` and so cannot
  interleave with **each other**, but a cancelled loop's `finally` runs **outside** that lock (cancellation is
  cooperative — the `finally` fires at the loop's next checkpoint, which can be *after* a fresh `connect()` has
  already started a new loop that reached `Up` and published its transport). An unconditional
  `liveConnection.value = null` there would wipe the **new** loop's live connection, leaving
  `currentConnection` `null` over an open socket so the [coordinator](relay-repository-coordinator.md) never
  builds a pump — silently dead until the next reconnect. The `finally` now clears **only when
  `liveConnection` still referentially holds *this* loop's `transport`**
  (`liveConnection.update { if (it === transport) null else it }` — atomic, because the scope is
  multi-threaded `Dispatchers.Default`), so a loop can only ever retract the value it itself published.
  `close()`'s own synchronous clear stays **unscoped** (a second `close()` legitimately clears whatever is
  current). Same race *class* as [#493](../codebase/493.md) (an async clear outliving the state it was scoped
  to), one layer down.
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
- **No logging.** The only outward signal is the typed [`RelayLinkStatus`](relay-link-status.md) leg
  (and its derived 4-case `ConnectionState`) — no strings beyond `secondsRemaining: Int`, and
  `DaemonAbsent` is a static `data object` carrying **no** relay-supplied text. `PairedServer`,
  `relayUrl`, the transport, and `Down`'s `code`/`reason`/`cause` are **never** logged; the #391
  `4404` branch reads `Down.code` **only to compare it** against the constant, never to log it (mirrors
  #306's posture; code-review confirmed zero `Log`/`Timber`/`println`).
- **Anti-reconnect-storm (security-positive).** Capped-exponential backoff + `retry()` **not** resetting
  `attempt` + escalation-only-after-genuine-stability (the ≥60 s reset) + a CONFLATED retry channel
  (`retry()`-spam collapses to one wakeup) together prevent a client-side reconnect storm /
  token-exhaustion loop against a hostile or flapping relay.
- **TLS/timeouts/frame-cap inherited unchanged** from #306's `defaultClient()`; the factory passes the
  **one** shared client as-is (no `HttpLoggingInterceptor`, no TLS reconfig — a fresh client per reconnect
  would leak thread pools). The jitter RNG is `kotlin.random.Random` — explicitly a **non-security**
  (backoff-timing) use.
- **Close-code branching at the `#308 seam`.** Since [#391](../codebase/391.md) the `Down` arm branches
  the relay's `4404 "no server"` close into `DaemonAbsent` (relay reachable, no daemon) via a single
  integer comparison — the one point where the untrusted relay-controlled code crosses into trusted
  state; `null` (dial failure / malformed data) and every other code cannot masquerade as it. **`4401`
  auth-reject halt/re-pair remains out of scope** — the seam's eventual *other* branch, named to
  [#308](https://github.com/pyrycode/pyrycode-mobile/issues/308); every non-`4404` code still retries
  uniformly.

## Edge cases & limitations

- **Benign-unpaired** — no stored `PairedServer` → no dial, stays `Connected` (banner hidden), and a
  tap-to-retry re-checks and stays idle. Never regresses into a spurious `Offline`/error. Since
  [#489](../codebase/489.md) this guard is evaluated **every dial**, so an un-pair (or an undecryptable
  read) on a *later* iteration idles at `Connected` and ends the loop the same way — not only on the
  first iteration.
- **`Offline` is not terminal** — the loop keeps redialing every ~30 s at the cap.
- **`DaemonAbsent` is not terminal either** (#391) — the relay is reachable but no daemon is registered;
  the loop keeps redialling on the same schedule, so the leg flips off the moment a daemon registers.
  It derives to the legacy `Offline` banner until #392's combined banner gives it its own copy.
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
**#391 added** (`relayStatus.value` read helper alongside the `observe().first()` legacy helper):
`4404 → DaemonAbsent` distinct from `Offline` (and the legacy view → `Offline`); `DaemonAbsent` keeps
redialling + flips to `Connected` when a daemon registers; repeated `4404` stays `DaemonAbsent` on the
**unchanged** base-2 jitter band; non-`4404` (`1006`/`4401`/`1000`) → `Reconnecting`, never
`DaemonAbsent`; `null` dial failure → `Reconnecting`→`Offline`, never `DaemonAbsent`; a direct
`toConnectionState()` map test. The six pre-existing tests (reading the derived `observe()`) are the
legacy-derivation regression guard. **[#496](../codebase/496.md) added** the deterministic close-then-connect
interleaving test (`closeThenConnect_oldLoopFinally_doesNotClearNewLoopConnection`): a `GatedRelayTransport`
holds the old loop's cancellation in a `NonCancellable` cleanup gate **past** the point the new loop reaches
`Up` and publishes, then releases it, asserting `currentConnection` still holds the **new** transport (a naive
single-`runCurrent()` interleaving is false-green — see [`codebase/496.md`](../codebase/496.md) § Lessons
learned). **[#498](../codebase/498.md) added** `retryWhileConnected_doesNotShortenFirstBackoffAfterLaterDrop`:
a `retry()` issued *while Connected* buffers a stale signal, then a later `emitDown()` starts a fresh backoff
that must **not** be pre-collapsed — `advanceTimeBy(intervalsFor(1).first() - 1)` → still `Reconnecting`/one
dial, `advanceTimeBy(1)` → re-dialled (exact ms, since `retry()` draws no jitter `random`). RED without the
top-of-`backoff()` drain (would be `Connecting`/two dials). The AC#2 guard
`retry_collapsesPendingBackoffWithoutThrowing` stays green (a retry *during* the wait still collapses).

## Related

- Ticket notes: [`../codebase/307.md`](../codebase/307.md) (original supervisor) ·
  [`../codebase/391.md`](../codebase/391.md) (relay-leg `RelayLinkStatus` + the `4404` → `DaemonAbsent`
  branch) · [`../codebase/489.md`](../codebase/489.md) (per-dial `load()` reload + connect-on-pairing via
  the Scanner) · [`../codebase/496.md`](../codebase/496.md) (the `finally`'s identity compare-and-clear of
  `currentConnection` — closes the close-then-connect race) · [`../codebase/498.md`](../codebase/498.md)
  (drains a stale `retrySignal` at the top of `backoff()` — a retry while healthy no longer pre-collapses the
  next drop's first wait) — files/line refs, patterns, lessons.
- Relay-leg model: [Relay link status](relay-link-status.md) ([#391](../codebase/391.md)) — the
  `RelayLinkStatus` source of truth this supervisor produces (`relayStatus`) and derives
  `ConnectionState` from.
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
  `RelayConnectionController` seam), **#308** (relay auth-gate — the seam's *other* branch, will consume
  `Down.code == 4401`; #391 already branched `4404`), **[#391](../codebase/391.md)** (relay-leg
  `RelayLinkStatus` + the `4404` → `DaemonAbsent` branch — **landed**), **#392** (pyrycode-leg readiness +
  the combined `{relay, pyrycode}` model that zips `relayStatus`, `blockedBy #391`), **#278**
  (`RemoteConversationRepository`).
- Engine: [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md). Aligns with
  pyrycode-side ADR 024 (relay untrusted; E2E auth is Noise).
</content>
