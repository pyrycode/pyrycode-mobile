# #307 — relay reconnect supervisor + `ConnectionStateSource` over the WS transport

The Phase 4 reconnect-policy layer that sits **on top of** the single-connection relay WS transport
(#306). #306 is a single dial: `connect()` / `close()` / `send(frame)`, an `inbound` frame stream
(single-consumer — **not ours**, it's #309's), and an `events` stream of raw `Up` / `Down(code, reason,
cause)` for one socket. This ticket owns the **loop** that re-dials on drop with capped-exponential
backoff and maps live socket state onto the existing `ConnectionStateSource` surface, then **swaps the
Koin binding** `FakeConnectionStateSource → RelayConnectionSupervisor` so the connection banner reflects
real connectivity.

No UI change. The `ConnectionBanner` (#200) and its `ThreadViewModel` wiring (#201) already consume
`ConnectionStateSource.observe()` / `retry()`; this ticket only changes which implementation feeds them.
No `## Figma` section in the ticket body, and that is correct — this is a data/transport-layer ticket
with zero new or changed composables.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt` (full, 1–82) — **the #306 surface we
  consume.** We drive `connect()`/`close()` and collect `events` (`TransportEvent.Up` / `Down(code,
  reason, cause)`). Single-use lifecycle: `NEW → connect() → DIALING → Up → UP → Down → terminal`; after
  the single terminal `Down`, `events` **completes** and the instance is spent. We never touch `inbound`.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt:43-47` (ctor) and `:215-250`
  (`defaultClient(): OkHttpClient` companion) — how the production factory builds an instance:
  `OkHttpRelayTransport(pairedServer, clientInfo, webSocketFactory)`; `defaultClient()` is the
  securely-configured shared client (timeouts, `pingInterval`, `MODERN_TLS+CLEARTEXT`, no logging
  interceptor). Don't read the whole impl — only the ctor + companion.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt` (full) — the 4 states. **No schema
  change.** `Reconnecting(secondsRemaining: Int)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt` (full) — the interface we
  implement: `fun observe(): Flow<ConnectionState>`, `suspend fun retry()`. KDoc already states the Phase-4
  expectation (transitions, never throw from `retry()`).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt` (full) — the binding we
  swap away from. **Keep it** (tests/previews still use it). Note its default state is `Connected`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` (full) — `suspend fun load():
  PairedServer?` gates whether we dial. **Returns `null` on absent OR corrupt; never throws.** `PairedServer`
  fields (relayUrl, token, serverId, serverStaticPublicKey).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt` (full) — the established
  **factory + injected-dispatcher** idiom in this codebase. Mirror its shape for `RelayTransportFactory` and
  for dispatcher injection (`ioDispatcher: CoroutineDispatcher = Dispatchers.IO`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` (full) — the binding to swap (line 43) and where to
  add the factory + client singletons. `NoiseClientInfo` is already bound (line 40).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt:33` —
  `ConnectionState.Connected -> return` (banner renders nothing). **This is the AC-3 anchor:** idle/unpaired
  must map to `Connected` to keep the banner hidden.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:192-210` — the consumer:
  `connectionState = source.observe().stateIn(..., initialValue = Connected)` and `retry()` →
  `source.retry()`. The swap must not regress this when nothing is paired.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConnectionStateSourceTest.kt` (full) — documents
  the `MutableStateFlow` conflation caveat. Informs how to assert an ordered state sequence (see Testing).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1-70` — the
  `runTest` + `backgroundScope` + `Dispatchers.setMain(UnconfinedTestDispatcher())` test idiom to mirror.
- `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt` — fake/driver patterns and
  `InnerFrameV2` usage; reference when building `FakeRelayTransport`.
- `gradle/libs.versions.toml:48-49` — `kotlinx-coroutines-test` is **already** in the catalog and wired as
  `testImplementation` (build.gradle.kts:105). **No new dependency.**
- `docs/knowledge/features/relay-ws-transport.md` and `docs/knowledge/codebase/306.md` — the transport's
  established contracts (single-use = the reconnect-state-machine boundary; `Down` enrichment with
  code/reason; `defaultClient()` config). Read-only.

## Context

Phase 4 transport. #306 deliberately kept reconnect/backoff and the `ConnectionStateSource` swap **out** —
it surfaces raw `Up`/`Down` for a supervisor to drive. This ticket is that supervisor. The backoff cadence
is the wire-spec cadence (`protocol-mobile.md` § Reconnect, mirrored from Go sibling pyrycode #247): capped
exponential **1 / 2 / 4 / 8 / 16 / 30 s**, **±20 % jitter**, reset to attempt 1 after the connection has
been stable **≥ 60 s**.

Sibling layer #309 (Noise session pump) drives the Noise handshake + open-state `noise_msg` loop over the
**same** #306 connection by collecting its `inbound` stream. We collect `events`; it collects `inbound` —
different single-consumer streams, so no contention. **No blocker between #307 and #309** (code-independent).
See the two cross-sibling decisions below.

## Design

Two new production files + one Koin edit.

### New types (2 exported)

**1. `RelayTransportFactory`** — `data/network/RelayTransportFactory.kt`

```kotlin
/** Builds a fresh single-use [RelayTransport] per dial. The supervisor discards a spent
 *  instance and asks for a new one to reconnect (#306 sockets are single-use). */
fun interface RelayTransportFactory {
    fun create(pairedServer: PairedServer): RelayTransport
}
```

Why a factory: the supervisor reconnects by **constructing a fresh transport** (the #306 single-use
contract), and must stay portable + fakeable. Production binds a lambda that closes over the shared
`OkHttpClient` (`defaultClient()`) + `NoiseClientInfo`; tests inject a fake returning fake transports.

**2. `RelayConnectionSupervisor`** — `data/network/RelayConnectionSupervisor.kt` (co-located with the
transport it supervises; implements the `data/repository` `ConnectionStateSource` interface).

```kotlin
class RelayConnectionSupervisor(
    private val transportFactory: RelayTransportFactory,
    private val pairedServerStore: PairedServerStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,        // jitter source; seed in tests
) : ConnectionStateSource {

    fun connect()           // start the supervision loop (idempotent: `if (loopJob?.isActive == true) return`); driven by #302
    fun close()             // stop the loop, tear down the live socket, go idle
    override fun observe(): Flow<ConnectionState>
    override suspend fun retry()
    val currentConnection: StateFlow<RelayTransport?>   // live instance (Up) or null — #309/#302 seam
}
```

- Single source of state: a private `MutableStateFlow<ConnectionState>(Connected)` exposed via
  `observe()` (`.asStateFlow()`). **Initial value `Connected`** — matches the Fake and `ThreadViewModel`'s
  `initialValue`, so an unpaired/idle supervisor keeps the banner hidden (AC 3).
- Owns `private val scope = CoroutineScope(SupervisorJob() + dispatcher)` (app-singleton lifetime). The
  supervision loop runs as a single child `loopJob` of this scope; `connect()` launches it, `close()`
  cancels it. The scope itself is not torn down (singleton lives app-lifetime).

### State machine — the supervision loop

Run inside `loopJob`. On start, load paired state once; if absent, stay idle.

```
load PairedServer
  └─ null → state = Connected; return        // benign-unpaired: no dial, banner hidden (AC 3)
  └─ present → run the loop below with `paired`

attempt = 0                                   // consecutive failures since the last "stable" connection
loop while active:
  state = Connecting                          // a dial is in flight
  transport = transportFactory.create(paired) ; transport.connect()
  sawUp = false ; stableReached = AtomicBoolean(false)
  collect transport.events until it completes (terminal Down):
      Up    → state = Connected ; currentConnection = transport ; sawUp = true
              stabilityTimer = scope.launch { delay(60_000) ; stableReached.set(true) }   // child
      Down  → (recorded; collection completes next)
  stabilityTimer?.cancel()                    // cancel BEFORE reading the flag
  currentConnection = null ; transport.close()   // idempotent
  if (sawUp && stableReached.get()) attempt = 0   // ≥60 s stable → reset escalation
  attempt += 1
  backoff(attempt)                            // Reconnecting countdown OR Offline; collapsible by retry()
```

**`backoff(attempt)`** computes the base interval, jitters it, and waits — surfacing state per AC 1:

- `base = backoffBaseSeconds(attempt)` = `1, 2, 4, 8, 16` for attempts 1–5, then `30` (cap) for attempt ≥ 6.
  (`if (attempt >= 6) 30 else (1 shl (attempt - 1))`.)
- `intervalMs = jitteredBackoffMs(attempt, random)` (formula below).
- **Sub-cap (`base < 30`):** per-second countdown — emit `Reconnecting(secondsRemaining)`, wait one
  collapsible second, decrement, until the interval is exhausted; then loop back to `Connecting`.
- **At cap (`base == 30`):** emit `Offline` (no countdown), one collapsible wait of `intervalMs`, then
  loop back to dial. Offline is "sustained unavailability → tap to retry"; the loop **keeps retrying** every
  ~30 s while showing `Offline`.

Resulting transitions on an unexpected drop (AC 1): `Connected → Reconnecting(secondsRemaining) →
Connecting → Connected`. ✓

**Jitter formula (pin exactly — production and test share it).** Expose a pure helper so the test can
replay it deterministically:

```
internal fun jitteredBackoffMs(attempt: Int, random: Random): Long {
    val base = backoffBaseSeconds(attempt)          // 1,2,4,8,16,30
    val factor = 0.8 + random.nextDouble() * 0.4    // [0.8, 1.2)  → ±20%
    return (base * 1000L * factor).toLong()
}
```

Invariant the test depends on: **exactly one `random.nextDouble()` per backoff interval**, consumed at
interval computation. `secondsRemaining` displayed = `ceil(remainingMs / 1000.0).toInt()`.

**`retry()` — collapse pending backoff (AC 2).** A `Channel<Unit>(CONFLATED)` `retrySignal`. Each
collapsible wait races `delay(ms)` against `retrySignal.receiveCatching()` via `select {}`; a retry returns
the wait early → the loop proceeds immediately to `Connecting`. `retry()` body: ensure the loop is running
(call the same start path as `connect()` — covers "tap to retry" arriving while idle/unpaired, which
re-checks paired state and stays idle if still unpaired), then `retrySignal.trySend(Unit)`. **Decision:**
`retry()` does **not** reset `attempt` — escalation tracks sustained unavailability; a manual retry that
immediately re-fails must not let the user hammer the relay from 1 s. `retry()` is non-blocking and
**never throws** (only `trySend` + a launch) — failures surface only as state transitions.

**`close()`** cancels `loopJob`, cancels the stability timer, `currentConnection.value?.close()`, sets
`currentConnection = null`, and resets `state = Connected` (intentional disconnect, not an error → banner
hidden). `connect()` after `close()` re-launches the loop from a fresh paired-load.

### Cross-sibling decisions (called out per the ticket's "architect's call")

**A. `Connected` = socket-open (transport `Up`), not Noise-session-open.** In-scope default. Gating on
#309's handshake-completion signal would couple this ticket to #309 (which has *no* blocker relationship)
and is out of scope. A future ticket may refine `Connected` to mean end-to-end (Noise) readiness once
#309's signal exists, if product wants the banner to reflect E2E readiness. Documented, not defaulted by
omission.

**B. Transport-instance ownership — `currentConnection: StateFlow<RelayTransport?>` is the non-precluding
seam.** The supervisor constructs each instance via `RelayTransportFactory`, owns its lifecycle, and
collects only `events`. It **never collects `inbound`** (that single-consumer stream is #309's). It
publishes the live instance on `currentConnection` (set on `Up`, cleared on `Down`/`close()`), so the
layer-up coordinator (#302 / future wiring) can hand the same connection's `inbound` to a fresh #309 pump
per connection. Coordination of the two over one instance lives a layer up, not in either sibling — this
seam exists solely so the supervisor's internal construction does **not** preclude it.

### Koin wiring (AppModule.kt — the swap, AC 4)

Add the factory + shared client; replace the `FakeConnectionStateSource` binding. Bind the supervisor as
both `ConnectionStateSource` (for the banner consumers) and its concrete type (for #302 to drive
`connect()`/`close()`):

```kotlin
single<WebSocket.Factory> { OkHttpRelayTransport.defaultClient() }
single<RelayTransportFactory> {
    val client = get<WebSocket.Factory>(); val info = get<NoiseClientInfo>()
    RelayTransportFactory { paired -> OkHttpRelayTransport(paired, info, client) }
}
single { RelayConnectionSupervisor(get(), get()) } bind ConnectionStateSource::class
// remove: single { FakeConnectionStateSource() } bind ConnectionStateSource::class
```

`RelayConnectionSupervisor(get(), get())` wires `transportFactory` + `pairedServerStore`; `dispatcher` +
`random` use constructor defaults. `#302` resolves `get<RelayConnectionSupervisor>()` for the lifecycle
methods. **Note:** the supervisor is now app-wired but **dormant until `connect()` is called** — nothing in
this ticket calls it; #302 drives the first `connect()`. Until then the bound instance sits at `Connected`
(idle), exactly preserving today's hidden-banner behavior. (Whether to extract a narrow `ConnectionLifecycle`
interface for #302 is deferred — see Open questions.)

## State + concurrency model

- **Scope:** one app-singleton `CoroutineScope(SupervisorJob() + dispatcher)` owned by the supervisor;
  `dispatcher = Dispatchers.Default` in production (the loop is orchestration + `delay` + `StateFlow`
  writes; OkHttp owns the socket threads). No blocking I/O on the loop thread. `pairedServerStore.load()`
  switches to IO internally.
- **Single state source:** the private `MutableStateFlow<ConnectionState>`; nothing else holds connection
  state. Writes happen only on the loop coroutine (+ the stability timer flips a local `var`, read back on
  the loop thread after cancel — no cross-thread `StateFlow` race).
- **Stability timer:** a child coroutine launched on `Up` (`delay(60_000)` → `stableReached.set(true)`),
  cancelled on `Down` *before* the loop reads the flag. The flag is an **`AtomicBoolean`** (not a plain
  `var`): the timer and the loop may run on different threads under `Dispatchers.Default`, and `cancel()`
  alone establishes no happens-before for a plain field read. The atomic guarantees the loop sees the
  timer's write; the only residual boundary case (write lands at ~exactly 60 s as we cancel) is benign —
  either answer to "was it ≥60 s stable?" is acceptable at the boundary. Uses only `delay`, so it tracks
  virtual time under the test clock. Cancelled by `close()` too.
- **`connect()` idempotency:** guard with `if (loopJob?.isActive == true) return` so a repeated `connect()`
  (a buggy/over-eager #302) cannot spawn a second loop — two loops would mean two concurrent dials / two
  sockets contending for the same single-consumer streams.
- **Cancellation:** `close()` cancels `loopJob` (cooperative — the loop suspends at `events.collect`,
  `delay`, and the `select` wait); the spent transport is closed idempotently. App-singleton scope is never
  cancelled (no leak — bounded, one loop).
- **Cold vs hot:** `observe()` returns the hot `StateFlow` (current value + changes), as the interface and
  `ThreadViewModel`'s `stateIn` expect.

## Error handling

- **All transport failures arrive as `Down` on `events`** (#306 contract: malformed `relayUrl`,
  TLS/dial/JSON/size failures, `4401` reject — all funnel to `Down`; only `connect()`-twice throws, which
  the supervisor never does). So `events.collect` never throws from the transport. We rely on this
  invariant rather than wrapping the dial in defensive try/catch (evidence-based — no observed throw path).
- **`pairedServerStore.load()`** never throws and returns `null` on corrupt → treated as unpaired (idle).
- **`Down.code`** is available for future policy (e.g. `4401` → stop retrying / re-pair, per #308). **This
  ticket treats every `Down` uniformly as "retry with backoff"** — code-specific handling (4401 → halt) is
  #308's, out of scope here. Note it in code so #308 has the seam; do not branch on it now.
- **`retry()` / `connect()` / `close()`** never throw; the only surfaced failure channel is the state flow.
- **No logging of failure detail or credentials.** The supervisor MUST NOT `Log`/`Timber`/`println` the
  `PairedServer`, the `relayUrl`, the live transport, or `Down`'s `code`/`reason`/`cause`. The only outward
  signal is the 4-case `ConnectionState` (no strings beyond `secondsRemaining: Int`). This mirrors #306's
  posture (no `HttpLoggingInterceptor`; `Down.reason` is category-only) and the token-redaction discipline
  in `PairedServer.toString` / `HelloClientPayload.toString`. Code-review must confirm no log statements.

## Testing strategy

Unit only (`./gradlew test`), JVM, `runTest` + virtual clock. **No instrumented tests** (data-layer, no
device — same posture as the #306 transport tests). New test file
`app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt`.

Test doubles (keep inside the test file / test source set — do not add a production fake):

- **`FakeRelayTransport : RelayTransport`** — `events` backed by a `Channel`/`MutableSharedFlow` the test
  drives via `emitUp()` / `emitDown(code, reason, cause)` (the latter also completes the stream, per the
  single-terminal-`Down` contract); `inbound` can be empty; records `connect()`/`close()` calls. ~40 lines.
- **`FakeRelayTransportFactory : RelayTransportFactory`** — `create()` returns a fresh `FakeRelayTransport`
  each call, recording the sequence so the test drives each reconnect's instance. ~10 lines.
- **stub `PairedServerStore`** — returns a fixed `PairedServer` (or `null` for the unpaired test).
- Seeded `Random(SEED)`; supervisor scope = the `runTest` `backgroundScope`-style scope with
  `StandardTestDispatcher(testScheduler)`; advance with `advanceTimeBy` / `advanceUntilIdle`.

Asserting ordered state sequences (the conflation caveat from `FakeConnectionStateSourceTest`): launch a
background collector of `observe()` into a list, drive the fake + advance virtual time, then assert the
collected sequence. The per-second `Reconnecting(...)` emissions are separated by `delay(1000)` suspension
points, so they are not conflated away when collected across advances.

Scenarios (bullet form — developer writes bodies in the project idiom):

- **Happy connect:** paired; `connect()` → `Connecting`; drive `Up` → `Connected`; `currentConnection`
  non-null. (AC 1)
- **Single drop recovers (AC-1 observed transition):** `Up` → `Connected`; drive `Down`; assert sequence
  advances `Connected → Reconnecting(1) … → Connecting` after advancing the seeded ~1 s interval; drive
  `Up` → `Connected`; assert a **fresh** transport instance was created for the redial.
- **Backoff progression:** repeated `Down`-before-`Up`; assert successive intervals correspond to bases
  `1, 2, 4, 8, 16` using the **same seed replayed** through `jitteredBackoffMs` to get exact ms for each
  `advanceTimeBy`; assert per-second `Reconnecting(k…1)` countdown. (AC 1, AC 5)
- **Offline at cap:** escalate to attempt 6; assert state is `Offline` (not `Reconnecting`); advance ~30 s
  and assert it re-dials (`Connecting`); a further `Down` keeps it `Offline`. (AC 1, AC 5)
- **`retry()` collapse:** during a `Reconnecting`/`Offline` wait, call `retry()`; assert it dials
  immediately (`Connecting`) *without* advancing the full remaining interval; assert `retry()` returns
  without throwing. (AC 2, AC 5)
- **≥60 s stability reset:** `Up` → `Connected`; `advanceTimeBy(60_000)`; drive `Down`; assert the next
  backoff base is 1 s (reset). Contrast: `Up` → `Connected`; advance < 60 s; `Down`; assert the backoff
  escalates (no reset). (AC 5)
- **Benign-unpaired (AC 3):** store returns `null`; `connect()`; assert `factory.create` is **never**
  called (no dial) and state stays `Connected` (never `Offline`/error). (AC 3, AC 5)
- **`close()`:** while `Connected`, `close()` → `currentConnection` cleared, the live transport `close()`d,
  state back to `Connected`, and a subsequent (driven) event causes no further dial (loop stopped). (AC 4)

## Open questions

- **`ConnectionLifecycle` interface for #302?** This spec exposes `connect()`/`close()` on the concrete
  `RelayConnectionSupervisor` and has #302 inject the concrete type. If #302 (or another lifecycle driver)
  wants to fake the lifecycle independently, extract a 2-method interface then — deferred as speculative
  now (evidence-based; #302 isn't built).
- **`4401` auth-reject handling** (`Down.code == 4401` → stop retrying / trigger re-pair) is **#308's**.
  This ticket retries uniformly; leave a code comment marking the seam.
- **Process-lifecycle reconnect** (close on background / reconnect on foreground/push) is **#302**, which
  drives this supervisor's `connect()`/`close()` — out of scope here.

## Security review

**Verdict:** PASS (one MUST FIX found and addressed inline before this section was written — see Concurrency).

**Findings:**

- **[Trust boundaries]** No findings. The supervisor never crosses the untrusted-relay boundary: it
  collects only `events` (`Up`/`Down`), never `inbound`, and never decodes a frame's `data`. #306 already
  owns the untrusted-content boundary (`onMessage` size cap + JSON decode) and surfaces only typed
  `TransportEvent`s. `PairedServer` crosses from encrypted storage (#294) into memory only and is passed
  opaquely to the factory — not inspected, parsed, or persisted here.
- **[Tokens, secrets, credentials]** No findings. This ticket does not generate, store, rotate, or revoke
  tokens (that is #294 + the pairing flow). The `PairedServer.token` is passed to `factory.create(paired)`
  and never enters `ConnectionState`, `currentConnection`, logs, or error text. Lifecycle/revocation is
  out of scope (named: #294 / #308 for `4401`-driven re-pair).
- **[File / storage]** N/A — the supervisor performs no file or storage I/O. `pairedServerStore.load()` is
  #294's encrypted Keystore-backed read (never throws, `null` on corrupt). No path construction, no
  TOCTOU, no new at-rest data, no backup surface.
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`BroadcastReceiver`,
  intent filter, deep link, `PendingIntent`, `ContentProvider`, or `WebView`. `connect()`/`close()`/
  `retry()` are in-process methods called only by #302 / the banner consumer.
- **[Cryptographic primitives]** No findings. No crypto in this ticket (Noise is #298/#309; we never decode
  `data`). The jitter RNG is `kotlin.random.Random` — explicitly a **non-security** use (backoff timing),
  permitted by the checklist. TLS is inherited unchanged from #306's `defaultClient()` (`MODERN_TLS +
  CLEARTEXT`); the factory must pass it **as-is** — do not reconfigure TLS or add an
  `HttpLoggingInterceptor`.
- **[Network & I/O]** No findings. Inbound frame-size cap and timeouts live in #306 and are reused
  unchanged. A hung dial or a silent socket is bounded into a `Down` by `defaultClient()`'s
  `connectTimeout = 15 s` and `pingInterval = 20 s` — the supervisor relies on this rather than adding a
  redundant watchdog (deliberate). **Anti-DoS property (security-positive):** capped-exponential backoff +
  `retry()` *not* resetting `attempt` + escalation-on-flap (the ≥60 s-stable reset fires only after genuine
  stability) prevents a client-side reconnect storm / token-exhaustion loop against a hostile or flapping
  relay. The retry signal is a `CONFLATED` channel, so `retry()`-spam collapses to one pending wakeup
  (bounded).
- **[Error messages, logs, telemetry]** SHOULD FIX, stated in the spec (Error handling): the supervisor
  MUST NOT log `PairedServer` / `relayUrl` / the transport / `Down.code|reason|cause`. The only outward
  signal is the 4-case `ConnectionState` (no strings beyond `secondsRemaining: Int`). Code-review confirms
  zero log statements. Not exploitable as designed (the spec adds no logging) — recorded as a guardrail.
- **[Concurrency]** **MUST FIX — addressed inline.** The ≥60 s stability flag is written by the timer child
  and read by the loop after `cancel()`; under `Dispatchers.Default` (multi-threaded) a plain `var` read
  has no happens-before guarantee → stale-read bug. Revised to an **`AtomicBoolean`** read after
  `stabilityTimer.cancel()` (see State machine + State/concurrency model). Also pinned: `connect()`
  idempotency (`if (loopJob?.isActive == true) return`) so a repeated `connect()` cannot spawn a second
  loop / second socket. Single state source (one `MutableStateFlow`, written only on the loop coroutine);
  app-singleton scope torn down only via `close()` cancelling `loopJob`; no durable state, so process death
  mid-loop leaves nothing partial (#306's non-resumable contract).
- **[Threat model alignment]** No findings. Relay-is-untrusted (pyrycode ADR 024) is honored — the
  supervisor reacts to transport events only and treats every `Down` (including hostile-relay garbage that
  #306 tore down) uniformly as "backoff + redial". `4401` auth-reject → re-pair/halt is **out of scope**,
  named to **#308**. Mobile-specific threats (screenshot/accessibility/overlay/deep-link/keyboard) do not
  apply — no UI, no token entry, no new surface.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
</content>
</invoke>
