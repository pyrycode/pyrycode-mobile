# Lifecycle connection driver — close on background, reconnect on foreground/push

`LifecycleConnectionDriver` forwards whole-app foreground/background edges to
`RelayConnectionRegistry`. The registry owns one retained connection bundle per
saved host; each bundle's [reconnect supervisor](relay-reconnect-supervisor.md)
decides how to dial and recover from drops. Connection lifetime follows the pairing
collection, independently of the host shown by the temporary compatibility UI. The
driver itself holds only a foreground flag and an optional open push-wake window
([#361](../codebase/361.md)) — which hosts exist and how each dials or recovers
stays entirely the registry's and its supervisors'.

Motivation is the mobile battery/privacy threat model: don't hold an authenticated relay socket open
while the app is idle in the background, and always return over a **fresh** session (the Noise layer
re-handshakes on each new socket — out of scope here).

## Where it sits in the Phase 4 stack

```text
ProcessLifecycleOwner ── ON_START / ON_STOP ──▶ LifecycleConnectionDriver
                                                       │ connect() / close()
ObservablePairedServerStore ── revision ──▶ RelayConnectionRegistry
                                              │ owns by exact serverId
                                              ├─▶ bundle A ─▶ supervisor A
                                              └─▶ bundle B ─▶ supervisor B
                                              │ selects latest saved survivor
                                              ▼
                                compatibility repository / status / events / actions
```

The driver calls the same controller methods from lifecycle edges and the existing
payload-free `onPushWake()` entry point. The registry projects the selected host's
unchanged [ConnectionState](connection-state.md) stream to the banner; it does
not combine hosts' connection states.

## Package: `de.pyryco.mobile.lifecycle`

The **single legitimate home** for `android.*` / `androidx.lifecycle.*` in the transport path (blessed by
the ticket's Technical Notes). The portable transport contract (`RelayTransport`,
`RelayConnectionSupervisor`) stays free of platform APIs — the `lifecycle` package depends **inward** on
`data/network`, never the reverse. (Mirrors the [`data/` android-import exception](../codebase/294.md)
posture: platform code lives behind a portable seam.)

## The two pieces

### 1. `RelayConnectionController` — the portable control seam

Declared in portable `data/network`, alongside
[RelayConnectionSupervisor](relay-reconnect-supervisor.md). Both the supervisor
and the app-owned registry implement it:

```kotlin
// data/network/RelayConnectionSupervisor.kt
interface RelayConnectionController {
    fun connect()   // idempotent start of the owned supervision loops
    fun close()     // resumable socket/retry teardown on background
}
```

A minimal, data-parameter-free surface: callers start or stop supervision without
supplying credentials or selecting a host through this seam. The registry delegates
each host's socket and retry behavior to its supervisor.

> **Why an interface, not the concrete supervisor.** It gives the lifecycle-edge test a trivial recording
> double — no real network, no real transport, no virtual clock — instead of standing up a full
> supervisor with the #307 fakes + the `runCurrent`/`advanceTimeBy` clock dance. Matches the project's
> fakes-over-MockK idiom.

The Scanner is a second controller consumer: [pairing confirmation](pairing-confirm-gate.md)
saves before calling `connect()`. Both it and the lifecycle driver resolve the
registry. Successful store mutations also notify the registry directly, so adding,
removing or replacing a host while foregrounded needs no further lifecycle edge.
A failed save neither notifies the registry nor proceeds to `connect()`.

### 2. `LifecycleConnectionDriver` — the driver

The only new `androidx.lifecycle.*` site. It forwards `onStart`/`onStop` to the controller exactly as
before; the only state it holds is whether the app is foregrounded and whether a push-wake window is
open ([#361](../codebase/361.md)) — no `StateFlow`, no connection state, nothing the registry doesn't
already own.

```kotlin
// lifecycle/LifecycleConnectionDriver.kt
class LifecycleConnectionDriver(
    private val controller: RelayConnectionController,
    private val lifecycle: Lifecycle,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wakeWindow: Duration = PUSH_WAKE_WINDOW,  // 30.seconds
) : DefaultLifecycleObserver {
    fun start()                                  // lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner)  // foreground → cancel any open window, controller.connect()
    override fun onStop(owner: LifecycleOwner)   // background → controller.close()
    fun onPushWake()                             // backgrounded & no window open → connect() + start window
    fun dispose()                                 // cancels the window-timer scope; Koin onClose
}
```

| Edge | Source | Action | Why |
|---|---|---|---|
| **Foreground** | `ON_START` (whole-app) | cancel any open window, `connect()` | start every retained host's supervisor independently; the foreground now owns the connections until the next `onStop` |
| **Background** | `ON_STOP` (whole-app) | `close()` | close every socket and retry loop; retain bundles for resume |
| **Push-wake (backgrounded, no window open)** | `onPushWake()` (FCM, [#361](../codebase/361.md)) | `connect()`, then `close()` after `wakeWindow` unless foregrounded first | wake every saved host for a bounded background window, not indefinitely |
| **Push-wake (foregrounded, or a window already open)** | `onPushWake()` | no-op | no duplicate connection, and a repeat wake never extends the window |

- `onStart`/`onStop` map to `ProcessLifecycleOwner`'s `ON_START` / `ON_STOP` — the **whole-app**
  foreground/background signal that (intentionally) does **not** fire on configuration changes. A
  process started by a push never reaches `ON_START`, so the driver begins in the background by
  construction — no separate "cold start from push" case exists.
- **`onPushWake()` opens a bounded background window, not an indefinite connection.** While
  backgrounded with no window open it calls `connect()` and starts a `wakeWindow` (30 s) timer; when
  the timer fires it calls `close()` **only if** this is still the current window and the app is
  still backgrounded (a job-identity check under the same lock a foreground clears). A wake while
  foregrounded, or while a window is already open, is a no-op: it neither opens a second connection
  nor extends the current window. `onPushWake()` stays **payload-free** by design — the in-process
  entry point the FCM service ([push messaging service](push-messaging-service.md)) calls, carrying
  no data from the received message.
- **All state changes are `@Synchronized`.** `onPushWake()` arrives on an FCM worker thread,
  `onStart`/`onStop` on main, and the window's expiry on `dispatcher` — three different threads
  writing `foreground`/`wakeJob`, serialized on the driver's own lock. The registry never calls back
  into the driver, so driver → controller is the only lock order; there is no reverse path for a
  deadlock to form on.

## Wiring — eager Koin singleton (no `PyryApp` change)

```kotlin
// di/AppModule.kt
single(createdAtStart = true) {
    RelayConnectionRegistry(get(), get())
} onClose { it?.dispose() }
single<RelayConnectionController> { get<RelayConnectionRegistry>() }
single<ConnectionStateSource> { get<RelayConnectionRegistry>() }
single(createdAtStart = true) {
    LifecycleConnectionDriver(
        controller = get<RelayConnectionController>(),
        lifecycle = ProcessLifecycleOwner.get().lifecycle,
    ).also { it.start() }
} onClose { it?.dispose() }
// #361: the FCM service resolves both directly, so the token write outlives the service instance.
single { PushTokenSink(get()) } onClose { it?.dispose() }
```

`appModule` eagerly constructs the registry and driver at application startup.
The registry observes the initial revision and successful mutations from the
shared [observable pairing store](paired-server-store.md#wiring--usage), reading
collection snapshots serially. It reconciles an exact, case-sensitive `serverId`
map through `RelayConnectionFactory.create(record)`. Two ids sharing a relay URL
still own separate bundles. Construction starts their coordinators; dialing waits
for the registry's foreground flag.

The latest saved surviving entry selects the compatibility view, matching
`PairedServerStore.load()`. Selection changes do not own connection lifetime or
create an extra connection. Stable consumers use the registry; concrete Koin
aliases resolve its retained selection for tests and diagnostics. See
[dependency injection](dependency-injection.md#how-it-works) and
[bundle configuration](relay-repository-coordinator.md#configuration).

## Guarantees (delegated, not re-implemented)

The driver forwards each foreground to `connect()` and each background to
`close()`, and owns only the push-wake window's own timing ([#361](../codebase/361.md)) — which host
to dial and how a dial or close behaves stays entirely delegated. The registry and per-host
supervisors enforce the remaining guarantees:

- **Reconciliation preserves unchanged owners.** Identical credential records and
  display-name-only edits retain bundles. Removed or credential-changed records
  permanently close their old bundle before any replacement is created or dials.
  Other hosts keep their repository, modal accumulator and replay cursor.
- **Foreground starts hosts independently.** An unavailable host cannot delay
  another host's loop. Newly reconciled hosts start while foregrounded; repeated
  lifecycle signals and unchanged snapshots create no duplicate connection.
- **Background close is resumable.** Close every supervisor's socket and retry
  loop, retaining surviving bundles, coordinators, modal accumulators and cursors.
  Resume builds fresh transports, pumps, repositories and Noise sessions using
  each host's retained replay position. Calling `bundle.close()` here would
  permanently discard the owner needed for resume.
- **A delayed read cannot reopen background sockets.** Reconciliation checks the
  current foreground/disposal flags under the same lock as lifecycle changes,
  after the suspending collection read. Registry retry likewise checks activity
  under that lock and targets only the selected host.
- **Unpaired stays empty and idle.** No bundle is created for an empty collection.
  Repository/events are empty, modal is hidden, status is relay `Idle` / pyrycode
  `Down`, and the legacy banner is hidden (`ConnectionState.Connected`). An
  intentional background close also leaves the relay idle rather than offline.
- **Disposal is permanent.** Koin close invokes `registry.dispose()`, clearing
  selection and exact-id access, closing every bundle and cancelling collection
  observation. Subsequent signals cannot create or dial owners. Process restart
  rebuilds from saved pairings with fresh in-memory cursors.

**Threading.** The registry serializes snapshot reads on its worker scope and
uses short, non-suspending `@Synchronized` sections for reconciliation, lifecycle
and disposal. Supervisor starts launch independently; storage IO and network
handshakes are never awaited under the registry lock. The driver's own state (the
foreground flag and the open wake window) is `@Synchronized` on itself; it still
receives process lifecycle callbacks on the main thread and `onPushWake()` on an
FCM worker thread.

## Edge cases & limitations

- **Push wakes every saved host, never a subset.** A push payload has no defined contract, so it
  cannot choose a host: `onPushWake()` stays payload-free and `connect()` dials the same all-host set
  a foreground would. Waking a single host from a payload needs a sender-defined contract that does
  not exist yet ([#361](../codebase/361.md)).
- **The background wake window is 30 s, fixed, and not extended by repeats.** A wake while
  backgrounded with no window open connects every host and starts one `wakeWindow` timer
  (`PUSH_WAKE_WINDOW`); at expiry it closes only if that timer is still current and the app is still
  backgrounded. A wake while a window is already open, or while foregrounded, is a no-op — it never
  opens a second connection and never pushes the close-time later. Foregrounding during an open
  window cancels it outright; the connections then stay open under the normal foreground rule until
  the next `onStop`.
- **`onPushWake()` → `connect()` vs `retry()`.** `connect()` (idempotent loop start) is correct for the
  in-scope case (push from background-idle). If a future requirement needs "force-immediate even when a
  backoff wait is pending" (a push during an `Offline` backoff while foregrounded), switch the body to
  `retry()`. For background-from-idle the two are equivalent.
- **There is no push sender yet.** The daemon stores tokens
  (`internal/relay/handlers/register_push_token.go`) but neither pyrycode nor pyrycode-relay has a
  path that sends FCM, so notifications do not work end to end; see
  [push messaging service](push-messaging-service.md).

## Security posture

Spec § Security review verdict: **PASS** (self-review, [#361](../codebase/361.md) revision). The driver
crosses no untrusted→trusted boundary: inputs are lifecycle events from the trusted Android framework and a
**payload-free** `onPushWake()` — the FCM service that calls it never reads the received message (see
[push messaging service](push-messaging-service.md)). `RelayConnectionController` exposes only
`connect()`/`close()` (no data parameters), so neither the driver nor any caller can inject relay- or
push-controlled data through this seam. Backgrounding — deliberate or a wake window's expiry — closes
every authenticated relay socket. The driver emits no logs. Whoever holds the project's FCM sender
credentials can trigger a wake repeatedly, but each window is bounded at 30 s and not extended, so a
flood costs at most one open window at a time, and connections only ever go to already-saved pairings
(accepted, `security-sensitive`, SHOULD-FIX noted and not required). Registry diagnostics use
debug-gated [RelayLog](relay-log.md) with static lifecycle event names and host counts only, never ids,
credentials, local names or payloads.

## Testing

JVM unit test (`test/.../lifecycle/LifecycleConnectionDriverTest.kt`, `./gradlew test`) — **no
instrumentation, no Robolectric, no real network/transport** (AC 5). Two in-file doubles (mirroring #307's
idiom): a recording `FakeRelayConnectionController` (records an ordered `connect()`/`close()` list) and the
lifecycle seam driven via `LifecycleRegistry.createUnsafe(owner)` + `handleLifecycleEvent(...)`
(`createUnsafe` is the test-only factory that skips the main-thread check, so a real `ProcessLifecycleOwner`
is unnecessary — **no new dependency**). Since [#361](../codebase/361.md) the wake window runs on a real
timer, so the suite drives `StandardTestDispatcher(testScheduler)` and virtual time (`advanceTimeBy`/
`runCurrent`) rather than asserting on synchronous dispatch alone.
Scenarios: foreground → `[connect]`; background → `[connect, close]`; background-then-foreground →
`[connect, close, connect]`; cold start → `[connect]`; background wake → `[connect]` then, after the 30 s
window with nothing else happening, `[connect, close]`; foreground wake → no extra call; repeated wakes
inside an open window → one `connect` and the close still lands 30 s after the *first* wake, not extended
by the repeats; foreground during an open window → no close at the window's end, the next `onStop` closes
instead; a wake after a window has already expired → a fresh `connect`/`close` cycle; rapid toggle ×3 →
strictly alternating `[connect, close]×3`; `start()` is what registers the observer.

The wake timer is launched `CoroutineStart.LAZY` and assigned to `wakeJob` before `start()` runs — launched
eagerly on an immediate test dispatcher it could expire before the assignment completed, and the job-identity
check in `expireWake` would then find no job to compare against and leave the window open forever. Getting
this test suite green first surfaced the ordering bug.

`di/RelayConnectionFactoryTest.kt` exercises registry lifetime with real Noise
peers. Its delayed-read case backgrounds the registry before releasing `list()`
and asserts no dial; checking only a completed startup read would miss that race.
It also verifies duplicate signals, saved hosts added while backgrounded, retained
cursors with fresh resume handshakes, and disposal during a pending read. Closed
transports must have zero active collectors, not just a closed-socket flag.

## Related

- Ticket notes: [`../codebase/302.md`](../codebase/302.md) — files/line refs, patterns, the push-wake `connect()`-vs-`retry()` call.
- Spec: `docs/specs/architecture/302-ws-transport-process-lifecycle.md` (§ Design, § State + concurrency model, § Open questions, § Security review — Verdict PASS).
- Ownership: [Active paired-host registry design (#634)](../../specs/architecture/634-active-host-registry.md); drives each [reconnect supervisor](relay-reconnect-supervisor.md) through the registry controller binding.
- State surface: [Connection state](connection-state.md) ([#196](../codebase/196.md)) — unchanged; `close()` → `Connected` is **why** a background close is not `Offline`.
- Consumer (UI): [`ConnectionBanner`](connection-banner.md) (#200) via `ThreadViewModel` (#201) — visuals unchanged; only *which* state is published across lifecycle edges changes.
- Siblings: **#309** (Noise session pump — re-handshakes on each fresh socket this driver reopens), **#308** (relay auth-gate), **[#489](../codebase/489.md)** (the Scanner as a second `RelayConnectionController` caller — `connect()` on a fresh pairing; also added the explicit interface bind).
- [Push messaging service](push-messaging-service.md) ([#361](../codebase/361.md)) — the FCM service that calls `onPushWake()`, and `PushTokenSink`, the collaborator that persists the token this driver's connections re-register (see [relay repository coordinator § Connect-time FCM push-token re-registration](relay-repository-coordinator-seams-and-passthroughs.md#connect-time-fcm-push-token-re-registration-365)).
- Spec: `docs/specs/architecture/361-fcm-push-wake.md` (§ Design § Background wake window, § Security review — Verdict PASS, § Revisions).
