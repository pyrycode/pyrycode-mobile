# Relay reconnect supervisor — auto-reconnect + the live `ConnectionStateSource`

The **Phase 4 reconnect-policy layer**: the loop that sits **on top of** the single-connection
[relay WS transport](relay-ws-transport.md) and turns a series of single-use sockets into a continuously
available connection. It re-dials on drop with capped-exponential backoff and is **the real
`ConnectionStateSource`** that the [`ConnectionBanner`](connection-banner.md) reflects — replacing the
Phase-2 `FakeConnectionStateSource` in the Koin graph.

> **Since [#391](../codebase/391.md): the source of truth is the relay-leg model, not
> `ConnectionState`.** The supervisor's single hot state is now a `MutableStateFlow<RelayLinkStatus>`
> (the [relay link status](relay-link-status.md) — the four `ConnectionState` cases **plus**
> `DaemonAbsent` (#391), `Idle` (#499), `PairingRejected` (#841) and `UpdateRequired` (#1008)), exposed
> read-only as `val relayStatus: StateFlow<RelayLinkStatus>`. The legacy 4-case
> [`ConnectionState`](connection-state.md) surface is **derived per-collector** from it, so every
> existing consumer is untouched. The `#308 seam` Down arm branches the relay's `4404 "no server"`
> close into `DaemonAbsent` (relay reachable, no daemon registered), a `4401`/`4426` close into
> `PairingRejected` (a rejected pairing — the redial halts), a `4412` close into `UpdateRequired`
> (the app build is too old — the redial halts), and a `4421` close straight into `Offline` (a protocol
> mismatch — the redial halts too, #1324), while every other code retries as before.
> This doc describes the post-#1324 state.

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
> supervisor sits dormant at `Idle` (#499), which derives to `Connected` (banner hidden).

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
    val relayStatus: StateFlow<RelayLinkStatus>    // relay-leg source of truth (6 cases incl. DaemonAbsent, PairingRejected); #392 zips it
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

A single `MutableStateFlow<RelayLinkStatus>` (initial value **`Idle`** since [#499](../codebase/499.md);
was `Connected`, and `ConnectionState` before [#391](../codebase/391.md)) is the only state source.
`relayStatus` exposes it via
`asStateFlow()`; the legacy `observe()` derives the 4-case `ConnectionState` from it per-collector. The
supervision loop runs as one child `loopJob` of an app-singleton
`CoroutineScope(SupervisorJob() + dispatcher)`.

```
attempt = 0                                   // consecutive failures since the last ≥60 s-stable connection
while active:
  load PairedServer                           // re-read EVERY dial (#489) — a re-pair to B is picked up on the next dial
    └─ null → state = Idle; return            // unpaired / un-paired mid-loop / undecryptable: no dial, Idle→banner hidden, loop ends (#499)
  state = Connecting                          // a dial is in flight
  transport = factory.create(paired); transport.connect()
  collect transport.events until it completes (terminal Down):
      Up   → state = Connected; currentConnection = transport
             launch stability timer (delay 60 s → stableReached = true)
      Down → daemonAbsent = (event.code == 4404)          // #308 seam: read-only, never logged; else false
             pairingRejected = (event.code == 4401 || event.code == 4426)   // #841: same read-only comparison
             updateRequired = (event.code == 4412)        // #1008: same read-only comparison
             protocolMismatch = (event.code == 4421)      // #1324: same read-only comparison
  finally: cancel stability timer (before reading the flag); currentConnection = null IFF still this loop's own transport (#496 identity compare-and-clear); transport.close() (always)
  if (sawUp && stableReached) attempt = 0     // ≥60 s stable → reset escalation
  attempt += 1
  if (pairingRejected) haltUntilRetry { PairingRejected }             // #841: suspend with no timeout until retry()/connect() wakes it
  else if (updateRequired) haltUntilRetry { UpdateRequired(dialMinimum) }  // #1008: same halt, carries this dial's latched minimum
  else if (protocolMismatch) haltUntilRetry { Offline }    // #1324: same halt, no new status — straight to the existing Offline leg
  else backoff(attempt, daemonAbsent)         // drains a stale pre-drop retry signal FIRST (#498), then: DaemonAbsent | Reconnecting countdown | Offline; each wait collapsible by retry()
```

`daemonAbsent`, `pairingRejected`, `updateRequired` and `protocolMismatch` are per-dial-iteration
`var`s (same closure-capture pattern as `sawUp`), set from the relay's WS close code in the `#308 seam`
Down arm. `daemonAbsent` is threaded into `backoff()`; the other three instead route to
`haltUntilRetry()`, so a rejected, too-old or mismatched dial still increments `attempt` (the anti-storm
property holds if the redial then fails on the network) but never enters the jittered-backoff wait.

> **Since [#489](../codebase/489.md): the `load()` is per-dial, not once at loop start.** The
> `pairedServerStore.load()` + `null → Idle; return` guard (`Idle` since #499) moved from **before** `while (isActive)`
> to the **top of the loop body**, so every dial re-reads the paired record. A loop started against
> server A therefore picks up a re-pair to B on its **next** dial (when A's socket drops), instead of
> dialing the A record captured once at loop start. `var attempt` stays before the loop (escalation spans
> dials). The `null` branch still `return`s (ends the job) — **not** `continue`, which would busy-spin;
> a later `connect()`/`retry()` starts a fresh loop that re-reads the store. The old benign-unpaired
> behaviour is unchanged — it's now just the first-iteration case of a guard that applies every dial.
> Re-pair-**while-connected** does not converge until A's socket drops (`connect()` is idempotent, so a
> second `connect()` no-ops); an *immediate* teardown-and-reconnect on re-pair is out of scope (a PO
> follow-up). See [`codebase/489.md`](../codebase/489.md).

**Relay-leg → banner mapping** (the leg has 7 cases — a `4421` halt reuses `Offline` rather than adding
an 8th; `observe()` derives the legacy `ConnectionState`):

| `RelayLinkStatus` | When | Derived `ConnectionState` | Banner |
|---|---|---|---|
| `Idle` | **deliberately not dialing** (#499): initial seed, unpaired branch, `close()` | `Connected` | hidden |
| `Connecting` | a dial is in flight | `Connecting` | `"Connecting…"` |
| `Connected` | transport `Up` (**socket-open** — see § Cross-sibling seams A) | `Connected` | hidden |
| `Reconnecting(secondsRemaining)` | counting down a **sub-cap** backoff interval (per-second) | `Reconnecting(secondsRemaining)` | `"Reconnecting in Ns"` |
| `DaemonAbsent` | a `4404` close: **relay reachable, no daemon registered** (steady, no countdown) | `Offline` | `"Offline — tap to retry"` (until #392's combined banner) |
| `PairingRejected` | a `4401`/`4426` close: **host refused the credential** — redial halted (#841) | `Offline` | `"Offline — tap to retry"` (Settings line, host row and — since #843 — the open thread's composer status area carry the distinct label/action; see below) |
| `UpdateRequired(minClientVersion)` | a `4412` close: **host refused this app build as too old** — redial halted (#1008) | `Offline` | `"Offline — tap to retry"` (Settings line reads "Update required"; the host row keeps the disconnected treatment and its plug control still dispatches a retry — the follow-up UI ticket changes that) |
| `Offline` | backoff escalated to the **30 s cap** (sustained unavailability); **or** a `4421` protocol-mismatch close — redial halted straight to this state, no dedicated leg (#1324) | `Offline` | `"Offline — tap to retry"` |

`Idle` derives to `Connected` **for the banner only** (idle is not an error, so it stays hidden) — but
the Settings status line's `toLegVisual()` maps the *same* `Idle` to a non-green "Not connected"
(`Down`), the whole point of [#499](../codebase/499.md): the two exhaustive mappers **diverge** on
`Idle` so the banner stays hidden while the Settings relay leg reads honestly, instead of a false green
"Connected" while unpaired/idle. `DaemonAbsent` derives to `Offline` (nearest legacy meaning — the relay
is up but unusable end-to-end); #392's combined banner gives it its own copy. The AC-observed transition on an
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

## Halt on a rejected pairing (#841), an app-too-old rejection (#1008), or a protocol mismatch (#1324)

A `4401` (invalid/expired/revoked token) or `4426` (stale saved server key) close cannot recover by
redialling — the host will refuse the same credential again. Neither can a `4412` close (the app build
is below the host's configured minimum, pyrycode#2576's `client.update_required` rejection) — only an
app update recovers. Nor can a `4421` close (unknown `type`, bad `v`, a malformed envelope, or a missing
first `noise_init` within the daemon's 10 s window — `protocol-mobile.md` § Error codes): the daemon and
client disagree at the framing level, and redialling the same build against the same host reproduces the
same mismatch. Desktop treats `4421` as one of its `DEFAULT_FATAL_CLOSE_CODES` and ends supervision,
offering Reconnect; mobile's equivalent is this halt, publishing the existing `Offline` leg rather than a
new status (there is nothing host-build-specific to surface, unlike `UpdateRequired`'s minimum). Instead
of `backoff()`, the loop calls a sibling function that halts indefinitely, parameterised by which terminal
state to publish:

```kotlin
private suspend fun haltUntilRetry(halted: () -> RelayLinkStatus) {
    drainStaleRetrySignals()             // same #498 drain backoff() uses, extracted to a shared helper
    synchronized(dialLock) { state.value = halted() }   // dialLock: see the minimum latch below
    retrySignal.receive()                // no timeout — suspends until retry() or a fresh loop wakes it
}
```

It consumes no `Random`, so the jitter sequence for later, unrelated backoffs is unaffected. `attempt`
was already incremented before the branch, so a rejected, too-old or mismatched drop still counts toward
escalation — if the resumed dial fails on the network instead of closing the same way again, `backoff()`
picks up from the already-escalated `attempt` rather than restarting at 1 s.

**Resume paths:**

- **Explicit retry** — `retry()` calls `connect()` (a no-op; the loop is still active) and
  `trySend(Unit)`; the halted `receive()` returns and the loop dials exactly once. A second rejection
  halts again.
- **Next foreground** — [`LifecycleConnectionDriver`](lifecycle-connection-driver.md) calls `close()` on
  background, which cancels the halted loop (the suspended `receive()` is cancellable) and sets `Idle`;
  the next foreground `connect()` starts a fresh loop that dials once. A push-wake `connect()` while
  already foreground is the existing idempotent no-op and does not dial.
- **Re-pair** — a new saved record makes `RelayConnectionRegistry.reconcile` close this host's bundle
  and build a fresh one, which starts from `Idle`; untouched by this ticket.
- Each host owns its own supervisor instance and state, so one host's halt never affects another's.

The halt holds no socket: the `finally` block has already released the transport and
compare-and-cleared `liveConnection` (#496) before `haltUntilRetry()` runs, so a halted host's `finally`
semantics are identical to any other drop's.

### The per-dial minimum latch (#1008)

The `4412` close and the sealed `client.update_required` error that precedes it travel over **different**
paths that race each other: the close reaches `runLoop` as `TransportEvent.Down`, while the error is
decrypted by [`NoiseSessionPump`](noise-session-pump.md#capturing-the-update-required-minimum-1008) as it
consumes `inbound` on its own coroutine. Either can arrive first. The supervisor latches the value per
dial instead of reading it inline:

```kotlin
private val dialLock = Any()
private var dialTransport: RelayTransport? = null   // this dial's transport, set right after transportFactory.create
private var dialMinimum: String? = null              // this dial's latched, already-validated minimum

internal fun recordClientMinimum(transport: RelayTransport, minClientVersion: String) {
    val valid = validMinClientVersion(minClientVersion) ?: return
    synchronized(dialLock) {
        if (transport !== dialTransport) return              // a stale dial's late error can't write here
        dialMinimum = valid
        if (state.value is RelayLinkStatus.UpdateRequired) state.value = RelayLinkStatus.UpdateRequired(valid)
    }
}
```

`dialTransport`/`dialMinimum` reset to `(the fresh transport, null)` at the top of every dial, so a
minimum from a previous dial's error can never leak into this one — enforced by an identity check, the
same `===`-not-`==` discipline as `currentConnection`'s #496 compare-and-clear. `NoiseSessionPump` never
validates; `recordClientMinimum` is the **only** production call that builds `UpdateRequired` with a
non-null minimum, so `validMinClientVersion` runs in exactly one place. Both orders resolve correctly:
if the error decrypts **before** the `4412` `Down`, the halt reads the already-latched `dialMinimum`; if
it decrypts **after**, `recordClientMinimum` finds `state.value is UpdateRequired` and upgrades it in
place. The halt's own state write and `recordClientMinimum`'s upgrade both run under `dialLock`, so the
two can never interleave into a torn read. A minimum that never arrives (the daemon's seal failed, or
the pump never reached `Open` — see [`RelayLinkStatus` § `UpdateRequired`](relay-link-status.md#updaterequired--an-app-too-old-rejection-halts-redial-1008))
leaves `UpdateRequired(null)`: correct, just less informative — best-effort by contract, not a bug to
chase.

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
>
> **Since [#841](#halt-on-a-rejected-pairing-841-an-app-too-old-rejection-1008-or-a-protocol-mismatch-1324): the drain is a shared `drainStaleRetrySignals()`
> helper, called at the top of both `backoff()` and `haltUntilRetry()`.** The halt needs the same
> protection `backoff()` does — a `retry()` tapped while `Connected` leaves a `Unit` sitting in the
> CONFLATED channel, and an undrained `haltUntilRetry()` would consume it immediately, turning one
> rejection into an instant second dial instead of an actual halt. Same placement rule: once per drop,
> before the first wait.

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
- **`close()`** sets `state = Idle` (#499 — intentional disconnect, not an error; `Idle` still derives
  to `Connected` so the banner stays hidden, while the Settings relay leg reads "Not connected");
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
  `DaemonAbsent`/`PairingRejected` are static `data object`s carrying **no** relay-supplied text.
  `PairedServer`, `relayUrl`, the transport, and `Down`'s `code`/`reason`/`cause` are **never** logged;
  the #391 `4404` branch, the #841 `4401`/`4426` branch, the #1008 `4412` branch and the #1324 `4421`
  branch each read `Down.code` **only to compare it** against a constant, never to log it, and no branch reads the close
  **reason** (mirrors #306's posture; code-review confirmed zero `Log`/`Timber`/`println`). `UpdateRequired`'s
  `minClientVersion` is the one non-static field this leg ever carries — it is never logged either, and
  `recordClientMinimum` is the sole production path that can populate it (see § The per-dial minimum
  latch above).
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
  integer comparison; since [#841](#halt-on-a-rejected-pairing-841-an-app-too-old-rejection-1008-or-a-protocol-mismatch-1324) it
  also branches `4401` (invalid/expired/revoked token) and `4426` (stale saved server key) into
  `PairingRejected`, since [#1008](#halt-on-a-rejected-pairing-841-an-app-too-old-rejection-1008-or-a-protocol-mismatch-1324)
  it branches `4412` (app build too old) into `UpdateRequired`, and since
  [#1324](#halt-on-a-rejected-pairing-841-an-app-too-old-rejection-1008-or-a-protocol-mismatch-1324) it
  branches `4421` (protocol mismatch) straight to `Offline` — all three halt redial instead of backing
  off. Five integer comparisons total — the only points where the untrusted relay-controlled code crosses
  into trusted state; `null` (dial failure / malformed data) and every other code cannot masquerade as
  any of them. Every non-`4404`/`4401`/`4426`/`4412`/`4421` code still retries uniformly. A hostile relay
  forging `4401`/`4426`/`4412`/`4421` can only halt redial for the affected host until the next explicit
  retry or foreground — it can already deny service by refusing connections outright, and the design never
  mutates or deletes the saved pairing on rejection, so a forged code cannot destroy a credential
  (accepted risk; see the #841 and #1008 plans' security reviews, both verdict PASS; #1324 carries no new
  security surface — it reuses the existing `Offline` halt path). A hostile relay
  **cannot** forge `UpdateRequired`'s minimum: it only arrives inside the AEAD-authenticated Noise
  session, so forging the close alone yields `UpdateRequired(null)`, never a fabricated version string.

## Edge cases & limitations

- **Benign-unpaired** — no stored `PairedServer` → no dial, goes `Idle` (#499 — derives to `Connected`,
  so the banner stays hidden; the Settings relay leg reads "Not connected"), and a tap-to-retry
  re-checks and stays idle. Never regresses into a spurious `Offline`/error. Since
  [#489](../codebase/489.md) this guard is evaluated **every dial**, so an un-pair (or an undecryptable
  read) on a *later* iteration goes `Idle` and ends the loop the same way — not only on the
  first iteration.
- **`Offline` is not terminal** — the loop keeps redialing every ~30 s at the cap.
- **`DaemonAbsent` is not terminal either** (#391) — the relay is reachable but no daemon is registered;
  the loop keeps redialling on the same schedule, so the leg flips off the moment a daemon registers.
  It derives to the legacy `Offline` banner until #392's combined banner gives it its own copy.
- **`PairingRejected` *is* effectively terminal for that host's automatic redial** (#841) — unlike every
  other state above, the loop does not keep retrying on its own; it halts until an explicit `retry()` or
  the next foreground `connect()`. This is deliberate: a refused credential cannot succeed on a redial,
  so redialling forever would only re-present it and hide the real cause behind "Offline".
- **`UpdateRequired` is terminal for the same reason** (#1008) — an app build the host has already
  refused cannot succeed on a redial either; the halt and resume paths are identical to
  `PairingRejected`'s. Unlike `PairingRejected`, the minimum it carries can arrive **after** the halt
  already published (see § The per-dial minimum latch) — the state stays `UpdateRequired`, only the
  `minClientVersion` field upgrades from `null`.
- **A `4421` protocol-mismatch close is terminal too, for the same reason** (#1324) — the halt and resume
  paths are identical to `PairingRejected`'s, but the published state is the existing `Offline`, not a new
  `RelayLinkStatus` case: there is no per-dial payload to latch, so plain `Offline` is desktop's Reconnect
  equivalent. The same close also ends the 10 s missing-first-`noise_init` window, so a slow network can
  now halt instead of retrying — accepted, since the user's Retry recovers either way.
- **Process death mid-loop** leaves nothing partial — no durable state (the #306 non-resumable contract);
  on relaunch, #302 drives a fresh `connect()`. A halted loop dies with the process like any other; the
  fresh `connect()` dials once and can be rejected again.
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
**unchanged** base-2 jitter band; non-`4404` (`1006`/`1000`) → `Reconnecting`, never `DaemonAbsent`;
`null` dial failure → `Reconnecting`→`Offline`, never `DaemonAbsent`; a direct `toConnectionState()` map
test. The six pre-existing tests (reading the derived `observe()`) are the legacy-derivation regression
guard. **`nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` originally listed `4401`
among its reconnecting codes; #841 dropped it there** (see below) since `4401` now halts instead. **[#496](../codebase/496.md) added** the deterministic close-then-connect
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
**[#499](../codebase/499.md) added** `initialState_beforeConnect_relayLegIsIdle` and **strengthened** the
three idle-behaviour tests (`benignUnpaired…`, `reloadPerDial_laterNullRead…`,
`close_tearsDownTransportAndStopsLoop`) to pin `relayStatus.value == RelayLinkStatus.Idle` **alongside**
their existing `ConnectionState.Connected` (banner-unchanged) assertions, plus
`toConnectionState_…` extended with `Idle → Connected`. The live-socket `emitUp() → Connected`
assertion is the AC#4 regression guard, untouched. (The Settings-line half — `Idle → Down`/"Not
connected" — is tested in `ConnectionStatusLineTest.relayIdle_mapsToDown_notConnected`.)

**#841 added** (spec: `docs/specs/architecture/841-rejected-pairing-relay-state.md`): `4401` and `4426` each →
`relayStatus == PairingRejected` and the legacy `state() == Offline`; after advancing well past the 30 s
cap, no further transport is created (proves the halt, not just a long wait). A `retry()` issued while
`Connected`, before the rejecting close, does not pre-collapse the halt (the shared drain, exercised
first by the builder's own RED-before-GREEN check per the PR's Lessons learned). `retry()` while halted
→ exactly one new dial (`Connecting`); a second rejection on it halts again with no further dial.
`close()` then `connect()` while halted → exactly one new dial. Two supervisors, one rejected-and-halted
and the other on `1006`, are asserted independently — the second keeps its normal reconnect countdown,
proving the halt is per-host. `nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` drops
`4401` from its reconnecting-codes list and gains the neighbouring codes `4400` and `4427`, which still
reconnect (guards the boundary of the new comparison). A direct `toConnectionState()` test covers
`PairingRejected → Offline`.

**#1008 added** (spec: `docs/specs/architecture/1008-update-required-halt.md`): a `4412` close →
`relayStatus == UpdateRequired(null)`, legacy `state() == Offline`, no further transport created past
10 minutes (proves the halt). `recordClientMinimum` called for the live dial's transport **before** the
`4412` `Down` → the halt reads the latched value, publishing `UpdateRequired("1.4.0")` directly; called
**after** the halt already published `UpdateRequired(null)` → the state upgrades in place to carry the
minimum. Called for a **previous** dial's transport (by identity, not equality) → ignored, proving the
per-dial latch can't leak across reconnects. Called with a non-`4412` close in play → the loop still
reconnects normally (the error alone never halts — only the close does). Called with an invalid value
(`"1.4"`, wrong part count) → `UpdateRequired(null)`, the validator rejection proven at the supervisor
boundary. `retry()` while halted → exactly one new dial with the latch cleared (no carry-over minimum
from the previous rejection); a repeat `4412` halts again. `close()` then `connect()` while halted →
exactly one new dial. Two supervisors, one `UpdateRequired`-halted and the other on `1006`, are asserted
independently — the second keeps its normal reconnect countdown, proving the halt is per-host, the same
shape as #841's equivalent test. A direct `toConnectionState()` test covers `UpdateRequired → Offline`.
`RelayConnectionFactoryTest.appTooOldRejectionHaltsOnlyItsOwnHostAndCarriesTheSealedMinimum` proves the
full wiring (pump → `onClientMinimum` → `recordClientMinimum`) through two real-Noise-session bundles
rather than "by composition": host A's sealed error then `4412` → `UpdateRequired("1.4.0")`, host B
unaffected and still dialling, an explicit retry on A dials once, and a bare `4412` with no error halts
with no minimum (see the plan's Revisions).

**#1318 added** (spec: `docs/specs/architecture/1318-thread-connected-after-handshake.md`): a second,
two-leg mapping, `internal fun ConnectionStatus.toConnectionState(): ConnectionState`, beside the
relay-only one above — for the thread, which needs `Connected` to mean the pyrycode leg's Noise
handshake finished, not just the relay socket opening. Relay `Connected` maps to `Connected` only when
the pyrycode leg is also `Connected`; to `Connecting` when the pyrycode leg is `Handshaking`/`Down`;
every other relay value falls back to `relay.toConnectionState()` unchanged. The single new test,
`connectionStatusToConnectionState_isConnectedOnlyWhenBothLegsAreUp`, drives every `RelayLinkStatus` ×
`PyrycodeLinkStatus` pair, plus explicit assertions that `Idle` stays `Connected` and `Reconnecting(n)`
keeps its countdown regardless of the pyrycode leg. This function does not change the supervisor's own
`observe()` or the relay-only mapping — see [Connection state § #1318](connection-state.md) and
[Connection status § #1318](connection-status.md) for the consumer wiring.

**#1324 added** (spec: `docs/specs/architecture/1324-halt-on-protocol-mismatch.md`): a `4421` close →
`relayStatus == Offline` (no new `RelayLinkStatus` case) and no further transport across ten minutes of
virtual time, proving the halt rather than a long wait; `retry()` after the halt dials exactly once, and
a second `4421` halts again with no further dial. The existing `nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent`
and the 4401/4426/4412 tests are the unchanged-codes regression guard.

## Related

- Ticket notes: [`../codebase/307.md`](../codebase/307.md) (original supervisor) ·
  [`../codebase/391.md`](../codebase/391.md) (relay-leg `RelayLinkStatus` + the `4404` → `DaemonAbsent`
  branch) · [`../codebase/489.md`](../codebase/489.md) (per-dial `load()` reload + connect-on-pairing via
  the Scanner) · [`../codebase/496.md`](../codebase/496.md) (the `finally`'s identity compare-and-clear of
  `currentConnection` — closes the close-then-connect race) · [`../codebase/498.md`](../codebase/498.md)
  (drains a stale `retrySignal` at the top of `backoff()` — a retry while healthy no longer pre-collapses the
  next drop's first wait) · [`../codebase/499.md`](../codebase/499.md) (the `Idle` case — the three idle
  sites stop overloading `Connected`, so the Settings relay leg no longer reads a false green while
  unpaired/idle) — files/line refs, patterns, lessons. #841 (`PairingRejected` + the halt), #1008
  (`UpdateRequired` + the per-dial minimum latch) and #1324 (`4421` → `Offline` + the halt) landed after
  the per-ticket archive was frozen (2026-09-05); their specs are below instead.
- Relay-leg model: [Relay link status](relay-link-status.md) ([#391](../codebase/391.md), extended by
  #841 and #1008) — the `RelayLinkStatus` source of truth this supervisor produces (`relayStatus`) and
  derives `ConnectionState` from. #1324 reuses `Offline` rather than extending this type.
- Specs: `docs/specs/architecture/307-relay-reconnect-supervisor-connectionstatesource.md` (§ Design,
  § State + concurrency model, § Cross-sibling decisions A/B, § Security review — Verdict PASS) ·
  `docs/specs/architecture/841-rejected-pairing-relay-state.md` (the halt/resume design, § Security
  review — Verdict PASS) · `docs/specs/architecture/1008-update-required-halt.md` (the sealed-error
  capture + per-dial minimum latch, § Security review — Verdict PASS) ·
  `docs/specs/architecture/1324-halt-on-protocol-mismatch.md` (the `4421` halt, copying desktop's
  `DEFAULT_FATAL_CLOSE_CODES` treatment).
- Sits on: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md)) — drives
  `connect()`/`close()`, collects `events`; never `inbound`. Implements [Connection state](connection-state.md)
  ([#196](../codebase/196.md)) `ConnectionStateSource`; swaps its `FakeConnectionStateSource` binding.
  Reads [`PairedServer`](paired-server-store.md) ([#294](../codebase/294.md)) — presence gates the dial.
- Consumer (UI): [`ConnectionBanner`](connection-banner.md) (#200) via `ThreadViewModel` (#201) —
  unchanged; only the bound `ConnectionStateSource` impl changed. Second consumer since
  [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843): the thread's Re-pair action reads
  `RelayLinkStatus` directly off `HostConversationConnection.status` (via `RelayConnectionRegistry.hostConnections`,
  not this class), the first UI surface to bypass the legacy derived `ConnectionState` and distinguish
  `PairingRejected` from every other `Offline`-deriving case. See
  [Thread screen § Sourced by `serverId` through the registry](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643).
- Siblings: **[#309](noise-session-pump.md)** (Noise session pump — collects the same connection's
  `inbound` via `currentConnection`; no blocker, but since #1008 `di/RelayConnectionFactory.kt`'s
  `RelayConnectionBundle` wires each pump's `onClientMinimum` callback to close over that dial's
  transport and call back into this supervisor's `recordClientMinimum`, see
  [NoiseSessionPump § Capturing the update-required minimum](noise-session-pump.md#capturing-the-update-required-minimum-1008)),
  **[#351](../codebase/351.md)** ([`RelayRepositoryCoordinator`](relay-repository-coordinator.md),
  **landed** — the consumer of `currentConnection`: starts a #309 pump + builds a remote repository per
  live connection), **[#302](../codebase/302.md)** ([lifecycle connection driver](lifecycle-connection-driver.md),
  **landed** — drives `connect()`/`close()` across foreground/background edges via the new
  `RelayConnectionController` seam), **#308** (relay auth-gate — the `#308 seam`'s namesake ticket;
  #391 branched `4404`, #841 branched `4401`/`4426`, #1008 branched `4412`, #1324 branched `4421`, all
  **landed**),
  **[#391](../codebase/391.md)** (relay-leg `RelayLinkStatus` + the `4404` → `DaemonAbsent` branch —
  **landed**), **#841** (split from #675 — `4401`/`4426` → `PairingRejected` + the redial halt —
  **landed**, see § Halt on a rejected pairing above), **#1008** (split from #1004 — `4412` →
  `UpdateRequired` + the sealed-error minimum + the redial halt — **landed**, see § Halt on a rejected
  pairing above), **#1324** (`4421` → `Offline` + the redial halt, mirroring desktop's
  `DEFAULT_FATAL_CLOSE_CODES` — **landed**, see § Halt on a rejected pairing above), **#392**
  (pyrycode-leg readiness +
  the combined `{relay, pyrycode}` model that zips `relayStatus`, `blockedBy #391`), **#278**
  (`RemoteConversationRepository`).
- Engine: [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md). Aligns with
  pyrycode-side ADR 024 (relay untrusted; E2E auth is Noise).
</content>
