# 302 — WS transport process-lifecycle: close on background, reconnect on foreground/push

> **Size:** S. A thin, **stateless** lifecycle driver wiring the whole-app foreground/background
> signal (`ProcessLifecycleOwner`) to the **already-shipped** `RelayConnectionSupervisor` (#307)
> `connect()`/`close()` seam. No new state type, no transport, no network. The supervisor already owns
> the hard parts (idempotent `connect()`, full-teardown `close()`, unpaired-gate, anti-storm backoff);
> this ticket only decides **when** to call them.

## Design source

N/A — non-UI transport lifecycle driver. No `## Figma` section in the ticket and no visible surface:
the `ConnectionBanner` (#200) visuals are unchanged; this ticket only changes *which* `ConnectionState`
is published across lifecycle edges, via the existing supervisor. The visual-fidelity check is
intentionally skipped.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:48-95` — **the seam.**
  `connect()` (idempotent loop start, 72-76), `close()` (full teardown → idle `Connected`, 80-87),
  `retry()` (92-95). This ticket adds the `RelayConnectionController` interface **here** and drives
  `connect()`/`close()`. Note `connect()`/`close()` are non-throwing and non-blocking (launch/cancel only).
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt:1-13` — the 4-case sealed state.
  `close()` sets `Connected` (banner hidden) — this is **why a deliberate background close is not
  `Offline`/`Reconnecting`** (AC 1). No change to this type.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:46-53` — current supervisor binding
  (`single { RelayConnectionSupervisor(get(), get()) } bind ConnectionStateSource::class`). The single is
  **also resolvable by its concrete type** `RelayConnectionSupervisor` (Koin registers the lambda's return
  type plus each `bind`). This is where the new eager driver `single` goes.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt:8-16` — composition root / `startKoin`. **No change
  required** — the driver self-registers via eager Koin init (see § Design). Read to confirm.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:1-70` — the in-file
  fake idiom (`FakeRelayTransport`, `StubPairedServerStore`; no MockK) and the JVM `runTest`/`runCurrent`
  conventions. The new driver test follows the same in-file-fake shape but **simpler** (no virtual clock —
  the driver launches no coroutines).
- `gradle/libs.versions.toml:8,32-34` — `lifecycleRuntimeKtx = "2.6.1"` and the three existing
  `androidx-lifecycle-*` catalog entries. Add `lifecycle-process` at the **same** version ref.
- `app/build.gradle.kts:92-94,104-106` — where the lifecycle `implementation`s and the `testImplementation`s
  live. Add the `lifecycle-process` `implementation`.
- `docs/knowledge/features/relay-reconnect-supervisor.md` — the supervisor contract: idempotent
  `connect()` (`if (loopJob?.isActive) return`), full-teardown `close()` (cancel loop, close socket, null
  `currentConnection`, state → `Connected`), the benign-unpaired gate, and the anti-storm backoff. **The
  driver relies on all of these and re-implements none of them** (AC 4).

## Context

Phase 4 transport lifecycle. The reconnect supervisor (#307) decides **how** to dial / reconnect-on-drop;
this ticket decides **when** the connection should be alive. The supervisor was shipped **bound but
dormant** — it sits at `Connected` (banner hidden) until something calls `connect()`. This ticket is that
something:

- **Background → `close()`.** Tear down the live socket and stop the supervision loop so **no on-drop
  backoff fires while backgrounded**. A deliberate background close is **not a failure** — `close()`
  sets `Connected`, so it never surfaces as `Offline`/`Reconnecting`.
- **Foreground / push-wake → `connect()`.** Restart the loop; a brand-new socket is dialed (the Noise
  layer re-handshakes on top — out of scope here).

There is **no Noise, no transport, no new state type** in this ticket — only a driver that calls two
existing methods on two existing lifecycle edges plus a push-wake entry point.

## Design

### New package: `de.pyryco.mobile.lifecycle`

The **single** legitimate home for `android.*` / `androidx.lifecycle.*` (the ticket's Technical Notes
bless this entry point). The portable transport contract (`RelayTransport`, `RelayConnectionSupervisor`)
stays free of platform APIs.

### 1. Portable control seam — `RelayConnectionController` (co-located in `RelayConnectionSupervisor.kt`)

A two-method interface that narrows the supervisor to exactly what the driver needs. Declared in the
**portable** `data/network` package (alongside its canonical implementor) so the platform `lifecycle`
package depends *inward* on `data/network`, never the reverse. Pure — no `android.*`.

```kotlin
// data/network/RelayConnectionSupervisor.kt (top of file)
interface RelayConnectionController {
    fun connect()   // existing semantics: idempotent supervision-loop start
    fun close()     // existing semantics: full teardown → idle ConnectionState.Connected
}
```

`RelayConnectionSupervisor` gains the interface in its header and `override` on the two methods
(3-line edit — the bodies are unchanged):

```kotlin
class RelayConnectionSupervisor(...) : ConnectionStateSource, RelayConnectionController {
    @Synchronized override fun connect() { /* unchanged */ }
    @Synchronized override fun close()   { /* unchanged */ }
}
```

> **Why an interface, not the concrete type:** it gives the AC-5 test a trivial recording double (no
> real network, no real transport, no virtual clock) instead of standing up a full supervisor with the
> #307 fakes + the `runCurrent`/`advanceTimeBy` clock dance. Matches the project's fakes-over-MockK idiom.

### 2. The driver — `LifecycleConnectionDriver`

The only new `androidx.lifecycle.*` site. Stateless: it forwards lifecycle edges to the controller and
holds no connection state of its own.

```kotlin
// lifecycle/LifecycleConnectionDriver.kt
class LifecycleConnectionDriver(
    private val controller: RelayConnectionController,
    private val lifecycle: Lifecycle,
) : DefaultLifecycleObserver {
    fun start()                                    // lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner)    // → controller.connect()   (foreground)
    override fun onStop(owner: LifecycleOwner)     // → controller.close()     (background)
    fun onPushWake()                               // → controller.connect()   (push reconnect entry)
}
```

- `onStart`/`onStop` map to `Lifecycle.Event.ON_START` / `ON_STOP` from `ProcessLifecycleOwner` — the
  whole-app foreground/background signal that (intentionally) does **not** fire on configuration changes.
- `onPushWake()` is the stable in-process entry point the **future FCM service** calls. It is identical to
  the foreground path (`connect()`) — satisfying AC 3's "same reconnect path as foregrounding." Because
  `connect()` is idempotent, `onPushWake()` is correct whether the app is foreground (loop already running
  → no-op) or background (loop idle → starts). The driver needs no foreground/background bookkeeping.

### 3. Wiring — eager Koin singleton (no `PyryApp` change)

```kotlin
// di/AppModule.kt — added single
single(createdAtStart = true) {
    LifecycleConnectionDriver(
        controller = get<RelayConnectionSupervisor>(),     // concrete; upcasts to RelayConnectionController
        lifecycle = ProcessLifecycleOwner.get().lifecycle, // the android.* entry point
    ).also { it.start() }
}
```

`createdAtStart = true` constructs the driver during `startKoin { modules(appModule) }` (which runs on the
main thread in `Application.onCreate`) and registers the observer immediately — so no `PyryApp` edit is
needed, and the driver is a resolvable app-singleton the future FCM service can `get()` for `onPushWake()`.
`get<RelayConnectionSupervisor>()` resolves the existing dormant singleton (same instance as the
`ConnectionStateSource` binding) and upcasts to the new interface — **no `binds` change required**.

> Alternative considered: resolve + `start()` explicitly in `PyryApp.onCreate`. Rejected to avoid touching
> the composition root for a self-registering singleton; the eager-Koin pattern is idiomatic and keeps the
> wiring in one place. If the team prefers explicit composition-root wiring, move `.also { it.start() }`
> into `PyryApp` after `startKoin` (one extra file).

### Data flow

```
ProcessLifecycleOwner (whole-app)
        │  ON_START / ON_STOP  (main thread)
        ▼
LifecycleConnectionDriver  ──onStart──▶ connect()  ┐
                           ──onStop───▶ close()    ├─▶ RelayConnectionSupervisor (#307)
(future) FCMService ──onPushWake()──▶ connect()    ┘        │  unchanged ConnectionState stream
                                                            ▼
                                              ConnectionBanner (#200) — visuals unchanged
```

### Dependency

`androidx.lifecycle:lifecycle-process` provides `ProcessLifecycleOwner` (NOT pulled in transitively by
`lifecycle-runtime-ktx`). Add at the existing `lifecycleRuntimeKtx = "2.6.1"` ref:

- `gradle/libs.versions.toml`: `androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycleRuntimeKtx" }`
- `app/build.gradle.kts`: `implementation(libs.androidx.lifecycle.process)`

## State + concurrency model

- **Stateless driver — single source of state preserved.** The driver holds no `StateFlow` and no mutable
  fields beyond its two injected deps. The only source of connection state remains the supervisor's
  `MutableStateFlow<ConnectionState>` (#307).
- **No coroutines launched by the driver.** It calls `connect()`/`close()` synchronously; the supervisor
  owns its app-singleton `CoroutineScope` and all cancellation.
- **Threading.** `ProcessLifecycleOwner` dispatches callbacks on the **main thread**; `connect()`/`close()`
  are `@Synchronized` and non-blocking (launch/cancel only) → no main-thread jank, no race. `onPushWake()`
  invoked from a future FCM thread is safe via the supervisor's `@Synchronized` + idempotency.
- **No overlapping sockets/loops (AC 4).** The driver's contract is simply: **each background pairs with
  `close()`, each foreground with `connect()`.** Non-overlap is guaranteed downstream by the supervisor —
  `connect()` idempotency (`if (loopJob?.isActive) return` ⇒ no second loop/dial) and `close()` full
  teardown (cancel loop → close socket → null `currentConnection`). `ProcessLifecycleOwner` additionally
  debounces rapid toggles (the brief ON_STOP delay coalesces config changes / quick app-switches) — a
  secondary safety, not the primary guarantee. The driver re-implements none of this.
- **Cold start.** `addObserver` runs at `startKoin` (Application.onCreate); the process lifecycle then
  advances INITIALIZED → … → STARTED as `MainActivity` starts, delivering `onStart` → `connect()`. The
  cold-start "app opens in foreground → connect" case falls out naturally, no special-casing.
- **Shutdown / process death.** The supervisor holds no durable state (#306/#307 non-resumable contract);
  on relaunch, the driver's `onStart` drives a fresh `connect()`.

## Error handling

- **The driver has no failure modes.** `connect()`/`close()` are documented non-throwing; the driver only
  forwards. No try/catch.
- **Background close is not an error.** `close()` sets `ConnectionState.Connected` (banner hidden), so the
  driver calling `close()` on `onStop` **cannot** produce `Offline`/`Reconnecting` (AC 1) — a structural
  guarantee, no driver logic required.
- **Unpaired stays idle (AC 4).** Foregrounding calls `connect()`; the supervisor's loop loads
  `PairedServer`, finds `null`, stays `Connected`, dials nothing. The driver does **not** check paired
  state.
- **Transport failures** (network / IO / handshake) surface through the **existing** `ConnectionState`
  stream — unchanged by this ticket.
- **Zero logging (design rule).** The driver emits no `Log`/`Timber`/`println` — no lifecycle events, no
  paired state, no connection details — mirroring the #306/#307 zero-log posture. Code-review enforces.

## Testing strategy

JVM unit test — `app/src/test/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriverTest.kt`
(`./gradlew test`). No instrumentation, no Robolectric, no real network/transport (AC 5).

**Doubles (both in-file, mirroring #307's idiom):**

- `FakeRelayConnectionController : RelayConnectionController` — records an ordered list of
  `connect()`/`close()` calls (the supervisor double; ~6 lines, no transport at all).
- Lifecycle seam — `LifecycleRegistry.createUnsafe(owner)` driven via `handleLifecycleEvent(...)`. `createUnsafe`
  is the test-only factory that skips the main-thread check, so it works in a plain JVM test with **no new
  dependency**. (Optional alternative: add `androidx.lifecycle:lifecycle-runtime-testing` as a
  `testImplementation` and use `TestLifecycleOwner`, which removes the owner-holder boilerplate. Developer's
  call; `createUnsafe` is the zero-dep default.)

**Scenarios (inputs → expected):**

- Foreground (drive registry to `STARTED`) → exactly one `connect()`, no `close()`.
- Background (`STARTED` → `CREATED`, i.e. `ON_STOP`) → exactly one `close()`.
- Background then foreground → ordered `[close, connect]`.
- Cold start (`INITIALIZED` → `CREATED` → `STARTED`) → exactly one `connect()`, no `close()`.
- Push-wake while backgrounded (drive to `CREATED`, then `onPushWake()`) → `connect()` called; assert it
  does **not** also `close()` (same path as foreground).
- Rapid toggle (`ON_START`/`ON_STOP` × N) → exact ordered `[connect, close, connect, close, …]`; never two
  consecutive `connect()`s without an intervening `close()` (the driver-pairing invariant).
- `start()` registers the observer (a real event routed through the registry reaches `onStart`/`onStop`).

**Delegated, not re-tested here** (covered by #307): that a background `close()` yields `Connected` (not
`Offline`), and that an unpaired `connect()` stays idle. The driver test asserts only that the driver calls
`close()`/`connect()` at the right edges; the supervisor's state mapping and unpaired-gate are its own.

## Open questions

- **Re-closing after a background push-wake.** A push received while backgrounded opens a socket that
  stays open until the next lifecycle edge (the app foregrounds-then-backgrounds). Deciding *when* to
  re-close a push-opened background connection — after the pushed work is serviced — needs the push
  payload/intent, which is the **future FCM-registration ticket's** responsibility. This ticket wires only
  the wake edge (`onPushWake()` → `connect()`). **Out of scope, named.**
- **`onPushWake()` → `connect()` vs `retry()`.** `connect()` (idempotent loop start) is correct for the
  in-scope case (push from background-idle). If a future requirement needs "force-immediate even when a
  backoff wait is pending" (e.g. a push during an `Offline` backoff while foregrounded), switch the body to
  `retry()` (which also ensures the loop runs, then collapses the pending wait). For background-from-idle
  the two are equivalent. Flagged for the FCM ticket.

## File inventory (scope self-check)

Implementation source files (new or modified `.kt`/`.kts`, excluding tests/`.md`/spec): **4** — under the
≥5 split threshold.

1. `data/network/RelayConnectionSupervisor.kt` — modified: add `RelayConnectionController` interface +
   implement it (`override` ×2, header). Bodies unchanged.
2. `lifecycle/LifecycleConnectionDriver.kt` — new: the driver (~30 lines).
3. `di/AppModule.kt` — modified: one eager driver `single`.
4. `app/build.gradle.kts` — modified: one `implementation(...)` line.

Plus build-config `gradle/libs.versions.toml` (one catalog entry, `.toml`) and one new test file. Total
written work ≈ 220 LOC — a clean S.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The driver crosses no untrusted→trusted boundary. Inputs are (a)
  lifecycle events from the trusted Android framework (`ProcessLifecycleOwner`) and (b) `onPushWake()` — a
  **payload-free** method call. The untrusted-relay boundary stays entirely in #306 (frame decode) / #307
  (`events` only). `RelayConnectionController` exposes only `connect()`/`close()` (no data parameters), so
  neither the driver nor any future caller can inject relay- or push-controlled data through this seam.
- **[Tokens, secrets, credentials]** No finding. The driver handles none. `PairedServer` is loaded
  opaquely by the supervisor (#307), never by the driver; `connect()`/`close()` carry no credentials.
- **[File / storage]** No finding. The driver performs no I/O, path handling, or persistence.
- **[Inter-process / Android attack surface]** No finding for this ticket. It adds **no** exported
  `Activity`/`Service`/`BroadcastReceiver`, no `<intent-filter>`, no `PendingIntent`, no manifest
  component. `onPushWake()` is an in-process Koin-resolved method, not an IPC entry point. *Security
  property to carry forward (SHOULD-NOTE for the future FCM ticket):* `onPushWake()` takes no arguments, so
  a spoofed/replayed push can at most trigger one `connect()` — idempotent (no second loop), gated on
  `PairedServer` presence (no dial if unpaired), and rate-limited by the supervisor's capped-exponential
  backoff (#307 anti-storm). No amplification, no data injection. Keep the FCM→`onPushWake()` hop
  payload-free; do not thread push body into the connection path. Not a MUST-FIX here (no payload is
  threaded).
- **[Cryptographic primitives]** No finding. None in the driver. The Noise re-handshake on each fresh
  socket is #275/#306's concern, unchanged. The supervisor's `kotlin.random.Random` backoff jitter is a
  documented non-security use (#307).
- **[Network & I/O]** No finding. The driver opens no sockets; it triggers the supervisor, which inherits
  #306's timeouts / `MODERN_TLS` / frame-cap unchanged. The background-close ↔ foreground/push-connect
  cycle introduces **no new reconnect-storm vector**: `connect()` idempotency collapses a push flood to
  no-ops while the loop runs, `close()` fully tears down, the supervisor's capped backoff +
  `retry()`-doesn't-reset-`attempt` anti-storm bounds re-dials, and `ProcessLifecycleOwner` debounces rapid
  foreground/background toggles.
- **[Error messages, logs, telemetry]** No finding. The driver emits zero logs (design rule in § Error
  handling); no telemetry added. Code-review enforces the zero-log posture.
- **[Concurrency]** No finding. The driver is stateless (no `StateFlow`, no mutable fields). Callbacks
  arrive on the main thread; `connect()`/`close()` are `@Synchronized` + non-blocking → no jank, no race.
  `onPushWake()` from a future FCM thread is safe via `@Synchronized` + idempotency. The driver launches no
  coroutine; the supervisor owns its scope and cancellation. Process death mid-loop leaves no partial state
  (#306/#307 non-resumable); relaunch's `onStart` drives a fresh `connect()`.
- **[Threat model alignment]** No finding. This ticket is **net-positive** for the mobile privacy/battery
  threat model — it closes the authenticated relay socket whenever the app is backgrounded, eliminating an
  idle authenticated connection (the user-story motivation). Mobile-specific surfaces (screenshot leakage,
  accessibility eavesdropping, screen-overlay, malicious deep links, keyboard logging) do not apply: no UI,
  no new components, no user input. The one deferred item — re-closing a push-opened background connection
  — is named and routed to the future FCM ticket (§ Open questions).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
