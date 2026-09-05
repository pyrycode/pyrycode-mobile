# Lifecycle connection driver — close on background, reconnect on foreground/push

The **Phase 4 transport-lifecycle layer**: the thin, stateless driver that decides **when** the relay
connection should be alive. It sits **on top of** the [reconnect supervisor](relay-reconnect-supervisor.md)
([#307](../codebase/307.md)) — the supervisor decides **how** to dial and reconnect-on-drop; this driver
decides **when**. Landed in [#302](../codebase/302.md) (split from #276).

Motivation is the mobile battery/privacy threat model: don't hold an authenticated relay socket open
while the app is idle in the background, and always return over a **fresh** session (the Noise layer
re-handshakes on each new socket — out of scope here).

## Where it sits in the Phase 4 stack

```
ProcessLifecycleOwner (whole-app foreground/background, main thread)
        │  ON_START / ON_STOP
        ▼
LifecycleConnectionDriver (#302) ── onStart ─▶ connect()  ┐   ◀── this doc ("when")
                                 ── onStop ──▶ close()     ├─▶ RelayConnectionSupervisor (#307, "how")
(future) FCMService ── onPushWake() ─────────▶ connect()  ┘        │  unchanged ConnectionState stream
                                                                   ▼
                                              ConnectionBanner (#200) — visuals unchanged
```

The driver **only** calls two existing methods on two lifecycle edges plus a push-wake entry point.
There is **no Noise, no transport, and no new state type** in this ticket — the
[`ConnectionState`](connection-state.md) surface is untouched; this layer changes only *which* state is
published across lifecycle edges, via the existing supervisor.

## Package: `de.pyryco.mobile.lifecycle`

The **single legitimate home** for `android.*` / `androidx.lifecycle.*` in the transport path (blessed by
the ticket's Technical Notes). The portable transport contract (`RelayTransport`,
`RelayConnectionSupervisor`) stays free of platform APIs — the `lifecycle` package depends **inward** on
`data/network`, never the reverse. (Mirrors the [`data/` android-import exception](../codebase/294.md)
posture: platform code lives behind a portable seam.)

## The two pieces

### 1. `RelayConnectionController` — the portable control seam

Declared in the **portable** `data/network` package, co-located with its only implementor
([`RelayConnectionSupervisor`](relay-reconnect-supervisor.md)):

```kotlin
// data/network/RelayConnectionSupervisor.kt
interface RelayConnectionController {
    fun connect()   // idempotent supervision-loop start (foreground / push-wake)
    fun close()     // full teardown → idle ConnectionState.Connected (background)
}
```

A deliberately **minimal, data-parameter-free** surface: the driver (and a future FCM caller) can only
**start or stop** the supervision loop, never inject relay- or push-controlled data through it. The
supervisor gains the interface in its header and `override` on its two existing methods — the bodies are
**unchanged** (`connect()` idempotency + `close()` full-teardown already shipped in #307).

> **Why an interface, not the concrete supervisor.** It gives the lifecycle-edge test a trivial recording
> double — no real network, no real transport, no virtual clock — instead of standing up a full
> supervisor with the #307 fakes + the `runCurrent`/`advanceTimeBy` clock dance. Matches the project's
> fakes-over-MockK idiom.

> **Since [#489](../codebase/489.md): the Scanner is a second live consumer of this seam.** The pairing
> confirm/paste flow injects `RelayConnectionController` and calls `connect()` right after a fresh pairing
> persists, so a first pairing comes up **immediately** instead of waiting for the next foreground
> `onStart`. Because `connect()` is idempotent, the two callers never conflict. This is also why #489
> added an **explicit** Koin bind for the interface (`AppModule.kt`,
> `single { … } binds arrayOf(ConnectionStateSource::class, RelayConnectionController::class)`) — the
> driver here still resolves the **concrete** supervisor and upcasts (unchanged), but the Scanner needs
> the interface resolvable directly. See [`codebase/489.md`](../codebase/489.md).

### 2. `LifecycleConnectionDriver` — the driver

The only new `androidx.lifecycle.*` site. **Stateless**: it forwards lifecycle edges to the controller
and holds no connection state of its own (no `StateFlow`, no mutable fields beyond its two injected deps).

```kotlin
// lifecycle/LifecycleConnectionDriver.kt
class LifecycleConnectionDriver(
    private val controller: RelayConnectionController,
    private val lifecycle: Lifecycle,
) : DefaultLifecycleObserver {
    fun start()                                  // lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner)  // foreground → controller.connect()
    override fun onStop(owner: LifecycleOwner)   // background → controller.close()
    fun onPushWake()                             // push-wake → controller.connect()
}
```

| Edge | Source | Action | Why |
|---|---|---|---|
| **Foreground** | `ON_START` (whole-app) | `connect()` | restart the loop; a fresh socket is dialed |
| **Background** | `ON_STOP` (whole-app) | `close()` | tear down the socket + stop the loop → **no on-drop backoff while backgrounded** |
| **Push-wake** | `onPushWake()` (future FCM) | `connect()` | same reconnect path as foregrounding |

- `onStart`/`onStop` map to `ProcessLifecycleOwner`'s `ON_START` / `ON_STOP` — the **whole-app**
  foreground/background signal that (intentionally) does **not** fire on configuration changes.
- **`onPushWake()` is identical to the foreground path (`connect()`).** Because `connect()` is idempotent,
  it is correct whether the app is foreground (loop already running → no-op) or background (loop idle →
  starts), so the driver needs **no foreground/background bookkeeping**. `onPushWake()` is **payload-free**
  by design — the stable in-process entry point the future FCM service calls.

## Wiring — eager Koin singleton (no `PyryApp` change)

```kotlin
// di/AppModule.kt
single(createdAtStart = true) {
    LifecycleConnectionDriver(
        controller = get<RelayConnectionSupervisor>(),     // concrete; upcasts to RelayConnectionController
        lifecycle = ProcessLifecycleOwner.get().lifecycle, // the android.* entry point
    ).also { it.start() }
}
```

`createdAtStart = true` constructs the driver during `startKoin { modules(appModule) }` (which runs on the
main thread in `Application.onCreate`) and registers the observer immediately — so **no composition-root
edit** is needed. `get<RelayConnectionSupervisor>()` resolves the **existing dormant** supervisor
singleton (the same instance bound as `ConnectionStateSource`) and upcasts to the new interface — **no
`bind` change required**. The driver is a resolvable app-singleton, so the future FCM service can `get()`
it to call `onPushWake()`.

## Guarantees (delegated, not re-implemented)

The driver's whole contract is: **each background pairs with `close()`, each foreground with
`connect()`.** Everything else is enforced **downstream by the supervisor** — the driver re-implements
none of it (AC 4):

- **No overlapping sockets/loops.** `connect()` idempotency (`if (loopJob?.isActive) return`) ⇒ no second
  loop/dial; `close()` full teardown ⇒ no leaked socket. `ProcessLifecycleOwner` additionally debounces
  rapid foreground/background toggles (a secondary safety, not the primary guarantee).
- **Background close is not an error.** `close()` sets [`ConnectionState.Connected`](connection-state.md)
  (banner hidden), so calling `close()` on `onStop` **structurally cannot** surface as `Offline` /
  `Reconnecting` (no "tap to retry" / countdown). No driver logic required (AC 1).
- **Unpaired stays idle.** Foregrounding calls `connect()`; the supervisor's loop loads `PairedServer`,
  finds `null`, stays `Connected`, dials nothing. The driver does **not** check paired state (AC 4).
- **Cold start falls out naturally.** `addObserver` runs at `startKoin`; the process lifecycle then
  advances `INITIALIZED → … → STARTED` as `MainActivity` starts, delivering `onStart` → `connect()`. No
  special-casing.
- **Process death** leaves nothing partial (#306/#307 non-resumable contract); relaunch's `onStart` drives
  a fresh `connect()`.

**Threading.** `ProcessLifecycleOwner` dispatches callbacks on the **main thread**; `connect()`/`close()`
are `@Synchronized` and non-blocking (launch/cancel only) → no main-thread jank, no race. `onPushWake()`
from a future FCM thread is safe via the same `@Synchronized` + idempotency.

## Edge cases & limitations

- **Re-closing a push-opened background connection is out of scope (named).** A push received while
  backgrounded opens a socket that stays open until the next lifecycle edge. Deciding *when* to re-close it
  (after the pushed work is serviced) needs the push payload/intent — the **future FCM-registration
  ticket's** responsibility. This ticket wires only the wake edge (`onPushWake()` → `connect()`).
- **`onPushWake()` → `connect()` vs `retry()`.** `connect()` (idempotent loop start) is correct for the
  in-scope case (push from background-idle). If a future requirement needs "force-immediate even when a
  backoff wait is pending" (a push during an `Offline` backoff while foregrounded), switch the body to
  `retry()`. For background-from-idle the two are equivalent. Flagged for the FCM ticket.
- **FCM registration / token plumbing is a separate ticket** — this driver only needs the foreground/push
  **wake** edge to trigger a reconnect.

## Security posture

Spec § Security review verdict: **PASS** (architect self-review). The driver crosses no
untrusted→trusted boundary: inputs are lifecycle events from the trusted Android framework and a
**payload-free** `onPushWake()`. `RelayConnectionController` exposes only `connect()`/`close()` (no data
parameters), so neither the driver nor any future caller can inject relay- or push-controlled data through
this seam. Net-positive for the mobile threat model — it **closes the authenticated relay socket whenever
the app is backgrounded**, eliminating an idle authenticated connection. **Zero logging** (mirrors the
\#306/#307 posture; code-review enforced). *Carry-forward for the FCM ticket:* keep the
FCM→`onPushWake()` hop payload-free — a spoofed/replayed push can then at most trigger one idempotent,
paired-gated, backoff-rate-limited `connect()` (no amplification, no data injection).

## Testing

JVM unit test (`test/.../lifecycle/LifecycleConnectionDriverTest.kt`, `./gradlew test`) — **no
instrumentation, no Robolectric, no real network/transport** (AC 5). Two in-file doubles (mirroring #307's
idiom): a recording `FakeRelayConnectionController` (records an ordered `connect()`/`close()` list) and the
lifecycle seam driven via `LifecycleRegistry.createUnsafe(owner)` + `handleLifecycleEvent(...)`
(`createUnsafe` is the test-only factory that skips the main-thread check, so a real `ProcessLifecycleOwner`
is unnecessary — **no new dependency**). The driver launches no coroutines, so there's no virtual clock:
each event dispatches synchronously and the recorded call list is the full ordered assertion target.
Scenarios: foreground → `[connect]`; background → `[connect, close]`; background-then-foreground →
`[connect, close, connect]`; cold start → `[connect]`; push-wake-while-backgrounded → one extra `connect`,
no extra `close`; rapid toggle ×3 → strictly alternating `[connect, close]×3`; `start()` is what registers
the observer.

## Related

- Ticket notes: [`../codebase/302.md`](../codebase/302.md) — files/line refs, patterns, the push-wake `connect()`-vs-`retry()` call.
- Spec: `docs/specs/architecture/302-ws-transport-process-lifecycle.md` (§ Design, § State + concurrency model, § Open questions, § Security review — Verdict PASS).
- Drives: [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md)) via the new `RelayConnectionController` seam — its `connect()`/`close()` (idempotent start / full teardown), unchanged.
- State surface: [Connection state](connection-state.md) ([#196](../codebase/196.md)) — unchanged; `close()` → `Connected` is **why** a background close is not `Offline`.
- Consumer (UI): [`ConnectionBanner`](connection-banner.md) (#200) via `ThreadViewModel` (#201) — visuals unchanged; only *which* state is published across lifecycle edges changes.
- Siblings: **#309** (Noise session pump — re-handshakes on each fresh socket this driver reopens), **#308** (relay auth-gate), **[#489](../codebase/489.md)** (the Scanner as a second `RelayConnectionController` caller — `connect()` on a fresh pairing; also added the explicit interface bind), **future FCM ticket** (push-token registration → calls `onPushWake()`; owns the push-opened-connection re-close decision).
</content>
</invoke>
