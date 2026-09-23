# Relay repository coordinator — a Noise pump + remote repository per live connection

The **Phase 4 connection-scoping layer**: the loop that sits **on top of** the
[reconnect supervisor](relay-reconnect-supervisor.md) and the [Noise session pump](noise-session-pump.md)
and turns a *live relay socket* into a *working remote conversation repository* — for exactly as long as
that socket lives. It observes the supervisor's `currentConnection`, and for each live transport it
starts a fresh Noise pump over it and constructs a [`RemoteConversationRepository`](remote-conversation-repository.md)
against that pump on a connection-scoped child scope. The live repository — or `null` between
connections — is published on `currentRepository`. The app registry projects its selected
host to the [stable facade](stable-conversation-repository.md), so ViewModels keep one
reference across connection churn and compatibility selection changes.

The coordinator derives [two-part connection status](relay-repository-coordinator-seams-and-passthroughs.md#two-part-connection-status-392),
switches [live-session events](relay-repository-coordinator-seams-and-passthroughs.md#live-session-event-seam-406) across reconnects, and
owns [FCM push-token re-registration](#connect-time-fcm-push-token-re-registration-365)
once per connection. Explicit [diagnostic archive requests](relay-debug-bundle-transfer.md)
also enter through this owner so admission and teardown share a connection lifetime. It also
switches the active connection's [held clarification-question batches](relay-repository-coordinator-seams-and-passthroughs.md#question-batch-projection-822)
— unlike `currentModal`, that state resets on every reconnect instead of surviving it. It holds the
[background-task roster](relay-repository-coordinator-seams-and-passthroughs.md#background-task-roster-677)
the same reset way, except for which task ids have finished, which it retains across every reconnect so a
completed task cannot come back as live when the daemon re-sends its retained roster.

Package: `de.pyryco.mobile.data.repository` (`RelayRepositoryCoordinator` + the `ManagedSessionPump`
interface it drives, the latter appended to `SessionPump.kt`), co-located with the
[repository](remote-conversation-repository.md) it builds. Landed in [#351](../codebase/351.md) (split
from #349). Portable, `android.*`-free; diagnostic logging is confined to static
transfer categories and accepted chunk counts.

## Where it sits in the Phase 4 stack

```
StableConversationRepository facade     HostConversationSource snapshots
        ▲                                      ▲
RelayConnectionRegistry ── selected compatibility view + all-host descriptors
        ▲  one retained bundle/coordinator per exact serverId
RelayRepositoryCoordinator (#351) ─ per-connection pump + repository lifecycle   ◀── this doc
        │  observes currentConnection ; publishes currentRepository
        ├──────────────▶ RemoteConversationRepository (#312/#313/#329/#346)   ◀── one per connection
        │                       ▲  pump.inbound / pump.send
        └──────────────▶ NoiseSessionPump (#309)   ◀── one per connection; : ManagedSessionPump
                                ▲  collects inbound (decrypt) ; sends noise_msg (encrypt)
RelayConnectionSupervisor (#307) ─ currentConnection: StateFlow<RelayTransport?>
```

`RelayConnectionFactory` constructs the supervisor, session factory and coordinator
inside one `RelayConnectionBundle` (see [Configuration](#configuration)). The
supervisor owns the socket, the pump owns the Noise session, and the repository
owns the conversation projections. The coordinator scopes the latter two to each
socket's lifetime; the bundle and coordinator survive reconnects.

## Exported types

```kotlin
// data/repository/SessionPump.kt — the coordinator's lifecycle view of the pump
interface ManagedSessionPump : SessionPump {   // SessionPump = the repository's data view (inbound/send)
    val state: StateFlow<PumpState>   // (#365) lifecycle state; the coordinator awaits Open before re-registering
    fun start()   // single-use; launches the handshake + open-state dispatch drive
    fun close()   // idempotent; wipes session keys + tears the pump's session/scope down
}

// data/repository/RelayRepositoryCoordinator.kt
class RelayRepositoryCoordinator(
    connections: StateFlow<RelayTransport?>,                 // = supervisor.currentConnection (the input)
    relayStatus: StateFlow<RelayLinkStatus>,                 // (#392) = supervisor.relayStatus (the relay leg)
    createPump: (RelayTransport) -> ManagedSessionPump,      // prod: { NoiseSessionPump(it, sessionFactory) }
    dispatcher: CoroutineDispatcher = Dispatchers.Default,   // injection seam (test clock); stored as a val
    deviceName: String = "",                                 // (#365) supplied through the bundle's NoiseClientInfo
    pushToken: suspend () -> String? = { null },             // (#365) one-shot token read; null ⇒ no registration
) {
    val currentRepository: StateFlow<ConversationRepository?>  // live repo, or null between connections
    val connectionStatus: StateFlow<ConnectionStatus>         // (#392) combined {relay, pyrycode} two-part status
    val liveSessionEvents: Flow<LiveSessionEvent>             // (#406) reconnection-surviving #385 live-event seam
    val currentModal: StateFlow<ModalUiState>                // (#492) the bundle-scoped "which modal is open" projection, folded here (Eagerly) off a now-PRIVATE #437 modal-event seam
    val questionBatches: StateFlow<List<QuestionBatch>>       // (#822) every clarification batch outstanding on this host, reset on reconnect
    fun observeQuestionBatch(conversationId: String): Flow<QuestionBatch?>  // (#822) the batch outstanding for one conversation, or null
    val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>>  // (#677) every conversation's background-task roster on this host, reset on reconnect except for which task ids finished
    fun observeBackgroundTasks(conversationId: String): Flow<BackgroundTaskRoster?>  // (#677) one conversation's roster, or null when nothing reported
    fun observeLiveBackgroundTaskCount(conversationId: String): Flow<Int>  // (#677) BackgroundTaskRoster.liveCount for one conversation, or 0
    suspend fun answerModal(modalId: String, optionId: String)  // (#451) outbound modal_answer passthrough — the inbound-modal mirror, but a call not a flow
    suspend fun cancelModal(modalId: String)                    // (#451) outbound modal_cancel passthrough
    suspend fun interrupt(conversationId: String)                // explicit conversation target; fire-and-forget
    suspend fun answerQuestionBatch(questionBatchId: String, answers: List<QuestionAnswer>)  // (#825) outbound question_answer passthrough; validated + fire-and-forget
    suspend fun refuseQuestionBatch(questionBatchId: String)      // (#825) outbound question_refused passthrough
    fun requestDebugBundle(): DebugBundleTransfer               // one attempt on this host's current Open connection
    fun start()   // idempotent — launches the single connections collector on the coordinator scope
    fun close()   // tears down the active connection (wiping pump keys) + cancels the coordinator scope
}
```

`ManagedSessionPump` exists because the pump has **two consumers with different needs** (Interface
Segregation): the repository reads `inbound` and calls `send` (the `SessionPump` *data* view), while the
coordinator additionally `start()`s and `close()`s it (the *lifecycle* view). The coordinator depends on
the richer contract and hands the **same instance, upcast to `SessionPump`**, to the repository.
[`NoiseSessionPump`](noise-session-pump.md) declares `: ManagedSessionPump` — its members already
matched structurally, so this was purely additive (no behaviour change). [#365](../codebase/365.md) added
`val state: StateFlow<PumpState>` to the **lifecycle** view (not the `SessionPump` data view, so the
repository is untouched) — `NoiseSessionPump`'s already-public `state` gained only an `override`.

## How it works — the connection→repository state machine

A single, synchronized, **non-suspending** `onConnection(transport)` handles each
`currentConnection` emission on the collector launched by `start()`:

1. Clear `activeConnection`, synchronously call the old repository's
   `endDebugBundle()`, cancel its child scope, then close its pump. Settling the
   transfer before cancellation prevents an incomplete archive from outliving
   teardown; closing the pump separately wipes keys and stops its own scope.
2. Return if the new transport is `null`.
3. Create a fresh child scope, start a fresh pump, and construct the remote
   repository. Publish `Connection(pump, childScope, repo, transport)` as one value,
   then launch push-token re-registration on the child scope. The suspending hook
   runs outside this critical section.

The repository receives the bundle's replay cursor and a lazy capability supplier:
`{ (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() }`. Structured
event handlers read the negotiated set per envelope; handshaking yields an empty
set. Diagnostic transfers require `Open` but do not require `interactive`.

### The single connection source and the Open-gated `currentRepository` (#421 / #493)

`activeConnection: MutableStateFlow<Connection?>` holds the pump, child scope,
concrete repository and transport identity, or `null` between connections. Status,
repository, live-event and modal projections all derive from it; outbound calls
read its `.value`. Only `onConnection`/`teardownActive` write it. The pump remains
private, and teardown, close and diagnostic admission share the coordinator lock.

`currentRepository` exposes a repository only when **its own pump** is `Open`.
Publishing during handshaking previously lost the facade's one-shot
`list_conversations` send: `pump.send` returned false, and reaching Open did not
resubscribe. The list then waited forever for a snapshot (#421).

```kotlin
// #493: repo AND pump-state derive from the SAME switched value — one flatMapLatest over the one source.
val currentRepository: StateFlow<ConversationRepository?> =
    activeConnection
        .flatMapLatest { conn ->
            conn?.pump?.state?.map { if (it is PumpState.Open) conn.repo else null } ?: flowOf(null)
        }.stateIn(scope, SharingStarted.Eagerly, null)
```

Combining separate repository and pump-state flows reintroduced that failure on a
direct A→B reconnect (#493). B's repository could arrive before the switched pump
collector, pairing it briefly with A's cached `Open` even while B was handshaking.
One `flatMapLatest` closes over the same `Connection` for both values. Test this by
collecting **every emission**: a settled `.value` after draining the scheduler is
`null` with either implementation and misses the transient.

Internal `liveRepository()` and diagnostic admission read one `activeConnection`
under the teardown lock, requiring an active owner, transport identity with
`connections.value`, and that connection's actual `Open` pump. The asynchronous
`currentRepository` cache can still hold an old repository when a replacement
transport arrives, even if the old pump says `Open`. Exact-host lookup therefore
uses `liveRepository()`; compatibility streams retain their existing behavior.
See [host access and its reconnect regression](dependency-injection-host-conversation-source.md#exact-host-repository-access).

### Scope ownership (three distinct scopes)

- **Coordinator scope** — `CoroutineScope(SupervisorJob() + dispatcher)`; owns the `connections`
  collector and eager projections for one bundle. Cancelled by `close()`.
- **Per-connection `childScope`** — `SupervisorJob(coordinatorJob) + dispatcher`; owns the repository's
  single inbound collector. Cancelled on each teardown, and transitively when the coordinator scope dies.
- **Pump scope** — owned **by `NoiseSessionPump` itself**, *not* a child of `childScope`. Reached **only**
  by `pump.close()`. This is why teardown must close the pump explicitly: cancelling the child scope does
  not reach the pump's own `SupervisorJob`.

The factory adds no scope. Bundle disposal closes both the supervisor and the
coordinator; a temporary supervisor close leaves the coordinator alive for the
next connection. The app registry retains each explicit-record bundle until its
pairing is removed, its credentials change or the registry is disposed. Its own
scope observes collection revisions (see [Configuration](#configuration)).

### Why `currentConnection` alone is a sufficient teardown trigger

The coordinator does **not** also observe `pump.state`. A pump that dies on its own (handshake timeout,
crypto fault) always calls `transport.close()` in its teardown, which makes the supervisor observe `Down`
and clear `currentConnection` to `null` — so the death funnels back through the supervisor and reaches
the coordinator as a `null` emission. The transport is the single source of connection liveness.

## Connect-time FCM push-token re-registration (#365)

Split into [Relay repository coordinator — a Noise pump + remote repository per live connection — seams and passthroughs](relay-repository-coordinator-seams-and-passthroughs.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Connect-time FCM push-token re-registration (#365), Two-part connection status (#392), Live-session event seam (#406), Modal event seam (#445) and the hoisted currentModal fold (#492), Outbound modal-send passthrough (#451), Outbound interrupt passthrough (#458) and Reconnect-spanning replay cursor (#412) — moved there verbatim, headings and anchors intact.

## Security invariants

The coordinator sits *above* the authenticated Noise channel — it only moves object references and never
touches wire bytes. Two invariants are load-bearing (each backed by a deterministic test, different
fabric from the stochastic rule):

- **Key-wipe on every pump-reference drop.** Every transition that discards a pump first calls
  `ManagedSessionPump.close()` → `session.close()`, wiping the transport ciphers + device-static copy.
  There are exactly **three** such transitions and all three close the pump: a new connection replacing
  an old one, a `null` emission, and `close()`.
- **No nonce/ephemeral reuse — a fresh single-use pump per live connection.** A `Noise_IK` session's
  ephemerals and AEAD nonce counter are *per-handshake*; reusing a pump across transports would reuse
  nonces under one key (a confidentiality break). `createPump` builds a brand-new pump per non-null
  emission; the pump's `start()` single-use assertion makes a spent pump impossible to restart. Hence
  fresh cryptographic state on reconnect is an invariant: the pump, session keys,
  in-flight requests and repository projections are replaced. The coordinator's
  replay cursor and modal accumulator intentionally survive that churn.
- **Never collects the transport's single-consumer streams** (`inbound`/`events`) — it only hands the
  `RelayTransport` reference to `createPump`. A stray collection would steal frames from the handshake
  (the pump owns `inbound`) or from the supervisor (owns `events`).

`onConnection`, teardown, close and diagnostic admission share a monitor and never
suspend while holding it. This serializes those operations and prevents cooperative
cancellation between starting a pump and retaining its reference.

## Configuration

`RelayConnectionFactory.create(record)` constructs a `RelayConnectionBundle`
containing one supervisor, Noise session factory and coordinator. Its immutable
record supplies every dial, handshake and device-key reload for that host.
Construction starts coordinator collectors; `supervisor.connect()` starts dialing.
The factory supplies `NoiseClientInfo.deviceName`, the one-shot `pushToken.first()`
read, worker dispatchers and a separate key-store IO dispatcher. It owns no scope.

`AppModule.kt` eagerly owns `RelayConnectionRegistry` and calls `dispose()` on
Koin close. The [observable pairing store](paired-server-store.md#wiring--usage)
provides initial and successful-mutation revisions. Serial snapshot reads reconcile
one bundle per exact, case-sensitive `serverId`, even for hosts sharing a relay.
Identical records and name-only changes retain owners; removed or changed records
close their bundle before any replacement is created or dials. The
[lifecycle driver](lifecycle-connection-driver.md) controls all retained supervisors:
background close preserves coordinators, modal accumulators and cursors; resume
creates fresh transports, pumps, repositories and Noise sessions for each host.

`connectionFor(serverId)` returns exactly that retained bundle, or `null` for an
unknown/removed id. Its coordinator exposes `currentRepository`, `liveSessionEvents`,
`currentModal` and `connectionStatus` with separate relay/pyrycode legs. An id never
falls back to the compatibility host. Failures, overlapping conversation/modal ids,
pending requests and replay positions remain within their host's bundle.

The registry's `selected` flow follows the last saved surviving entry, matching
`PairedServerStore.load()`. Removing it selects the latest survivor; removing the
last leaves no owner. Selection switches compatibility repository/status/event/control
projections together without redialing unaffected bundles.
An empty selection yields no repository/events, hidden modal, relay `Idle` /
pyrycode `Down` and a hidden legacy banner. Modal/interrupt calls without a
selection throw `IllegalStateException`; retry is a no-op while empty, backgrounded
or disposed. Threads use [exact-host dependencies and Retry](dependency-injection-host-conversation-source.md#destination-ownership);
Settings/archive migration remains #637.

Compatibility state must read through the current selection. An independently
`stateIn`-cached switch briefly exposed the previous host's repository after
selection changed, while modal actions already addressed the new host. The
registry's repository/modal/status `.value` and `replayCache` therefore read the
selected coordinator directly; collection switches its flows without another
cache. Events and banner observation are cold switched flows. These projections
own no jobs; consumers own their collection lifetimes. The registry scope owns the
revision collector, while each bundle owns its coordinator projections.

Concrete Koin bundle/supervisor/session-factory/coordinator aliases resolve the
selected retained bundle for tests and diagnostics and refuse without a selection.
Compatibility consumers use registry projections; thread routes capture exact bundles.
`createCompatibility(store)` remains a helper, not an app-owned connection. See
[DI wiring](dependency-injection.md#how-it-works).

`bundle.close()` is permanent and idempotent: stop the supervisor's socket/retry
loop, cancel the coordinator and repository collectors, and explicitly close the
pump to wipe session keys. Registry disposal clears selection/map, closes every
bundle and cancels revision observation. Never reuse a disposed bundle.

The factory does not bind `ConversationRepository`: the existing selector chooses
the stable facade by default or the [fake](conversation-repository.md) with
`-PuseRelayRepository=false`. Both modes retain registry connection ownership.
In relay mode, the [host source](dependency-injection-host-conversation-source.md#snapshot-lifetime), once resolved,
owns per-host list subscriptions without screen subscribers. Backfill still waits
for thread subscribers.

### Host diagnostic archive transfer

`RelayConnectionRegistry.requestDebugBundle(serverId): DebugBundleTransfer` is the
Log data pull's entry point, admitted and torn down under this Configuration
section's connection ownership. See [Host diagnostic archive
transfer](relay-debug-bundle-transfer.md) for the transfer's admission/retry
contract, its 32 MiB accumulation bound and its terminal-state guarantees.

## Edge cases / limitations

- **`currentRepository` is `null` between connections** — by design. The #352 facade renders the
  no-connection state; consumers re-subscribe against the next connection's repository.
- **Connection loss cancels the child scope.** This stops the repository's inbound collector;
  its `finally` fails registered pending requests with `IllegalStateException` (#488).
  The facade switches cold reads to their empty fallback while no live repository is published.
  See [repository teardown handling](remote-conversation-repository-state-errors-and-handoff.md#hand-off--the-live-binding).
- **No retry of its own.** Reconnect cadence is governed entirely by the supervisor's capped-exponential
  backoff (1/2/4/8/16/30 s); a failing relay cannot drive a tight pump-rebuild loop.
- **Availability can change after lookup.** `liveRepository()` does not keep the
  connection alive for a later operation.

## Testing

Bundle transfer and bundle registry test coverage, including the \#764 accumulation-cap
cases, is described in [Host diagnostic archive transfer §
Testing](relay-debug-bundle-transfer.md#testing).

`di/RelayConnectionFactoryTest.kt` covers bundles and registry ownership with real
Noise peers over channel-backed transports. It verifies independent credentials,
cursors, status, repositories and modals even with overlapping conversation/modal
ids. B's pending modal reply completes while A fails, reconnects and is removed;
B continues receiving events. Transport collector counts must reach zero after
teardown: socket closure alone cannot prove the separately scoped pump stopped.

The selection-edge assertion uses an unconfined collector of `registry.selected`
to read `currentRepository.value` before switched collectors catch up. A settled
assertion after `runCurrent()` would pass with the stale `stateIn` cache. Registry
and DI cases also cover empty startup, first pairing, identical/name-only retention,
credential replacement ordering, latest-survivor selection, both repository
selectors and resumable background close; see [lifecycle tests](lifecycle-connection-driver.md#testing).

`app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` — JVM unit tests
(JUnit4 + `runTest`, hand fakes, no MockK), mirroring `RelayConnectionSupervisorTest` /
`RemoteConversationRepositoryTest`. A `StandardTestDispatcher` is injected and driven with `runCurrent()`
(not `advanceUntilIdle()`); **every test ends with `coordinator.close()`** so the perpetual
`connections.collect` does not hang `runTest`. A ~15-line channel-backed `FakeManagedPump` (UNLIMITED
inbound, `started`/`closed` flags, a `push` helper) stands in for the pump; the
`RemoteConversationRepository` is the **real** one, so the list (`list_conversations`) and thread
(`backfill_since`) read paths are exercised end-to-end over the fake pump. The reconnect test asserts
distinct pump + repository instances per connection with no projection carryover. AC #1 is a runtime
contract check in `NoiseSessionPumpTest` (`pump is SessionPump` / `is ManagedSessionPump`, typed as
`Any`). No instrumented test — pure data-layer.

Interrupt coverage uses the real repository over the fake pump: send for A, then
assert the call naming B adds exactly one `interrupt` frame whose sole payload
field is `conversation_id: B`, without supplying a reply. The no-active-connection
case still throws `IllegalStateException` for the ViewModel to swallow. These
target assertions do not prove the [pending cross-device live outcome](interrupt-send-path.md#testing).

[#365](../codebase/365.md) added a drivable `state` to `FakeManagedPump` (a
`MutableStateFlow(PumpState.Handshaking)` + `open()` / `closeState()` helpers — defaulting to
`Handshaking` keeps the pre-existing tests' hook dormant) and five connect-time tests: a stored token
registers once with the live `device_name` (AC #1, exact-payload assertion), a null token is a no-op
(AC #2), a reconnect re-registers once per connection (AC #3), a server `error` neither crashes nor wedges
the connection (AC #4), and a pre-`Open` `Closed` registers nothing (boundary). The `ack`/`error`
correlation mirrors `RemoteConversationRepositoryTest`'s #359 shape.

[#822](https://github.com/pyrycode/pyrycode-mobile/issues/822) added four question-batch cases to the same file: a batch held with no
subscriber (the eager-fold precedent `currentModal` set in #492); `observeQuestionBatch` returning only
its own conversation's batch even with an equal id held for another conversation; replacing the
connection dropping the old batches before a re-sent one is held once under its original id; and two
coordinators (hosts A and B) with equal conversation and batch ids showing only the host that actually
received the frame. All four reuse the existing `FakeManagedPump` harness — no new fake.

[#825](https://github.com/pyrycode/pyrycode-mobile/issues/825) added three coordinator cases to the same
file: with equal batch ids held on hosts A and B, answering and refusing on A puts frames on A's pump
only; a batch held on a prior connection fails `IllegalStateException` after a reconnect with nothing
sent; and calling either method with no active connection throws the same exception. All three reuse
`FakeManagedPump` — no new fake. The reconnect case does not itself distinguish the fresh repository's
empty hold from the no-active-connection guard (a non-blocking verifier NIT); the reset-on-reconnect path
it relies on is separately covered by the #822 reconnect test above.

[#493](../codebase/493.md) added three reconnect-gating tests that **collect every `currentRepository`
emission** across a **direct A→B** reconnect (no interposed `null`): with connection A `Open` and B's fresh
pump left at `Handshaking`, B's repo must never appear (`reconnect_directAtoB_neverExposesRepoWhileNewPumpHandshaking`
— confirmed RED against the pre-fix two-`StateFlow` gate, since the transient is caught by *collecting*, not
by the settled `.value` which is `null` on both); B's repo appears only once `pumps[1].open()`
(`…exposesRepoOnlyOnceNewPumpReachesOpen`); and post-reconnect the facade's one-shot `list_conversations`
reaches `pumps[1].sent` and the list loads (`reconnect_afterOpen_listConversationsSucceedsAndListLoads`).
The existing key-wipe / single-use-pump / no-carryover tests pass **unmodified**.

## Related

- Tickets: [#351](../codebase/351.md) — the coordinator + `ManagedSessionPump` (files, line refs,
  patterns, lessons) · [#365](../codebase/365.md) — the connect-time FCM push-token re-registration hook,
  the `ManagedSessionPump.state` addition, and closing #359's `device_name: ""` defer ·
  [#392](../codebase/392.md) — the derived pyrycode-leg readiness + the combined `connectionStatus` ·
  [#406](../codebase/406.md) — the reconnection-surviving [`liveSessionEvents`](live-session-events.md)
  seam (first consumer: [`ThreadViewModel.isThinking`](turn-state-thinking-flag.md)) ·
  [#445](../codebase/445.md) — the reconnection-surviving [`modalEvents`](modal-events.md) seam (mirror of
  `liveSessionEvents`) · [#492](../codebase/492.md) — **hoists** the
  [`currentModal`](current-modal-state.md) fold to this bundle-scoped layer (`modalEvents` demoted to
  `private`; the ViewModel now re-exposes `currentModal`), so a `modal_shown` fired before any thread
  screen subscribes is no longer dropped ·
  [#451](../codebase/451.md) — the **outbound** `answerModal` / `cancelModal` passthrough (the modalEvents
  mirror, but a suspend call; consumer: [`ThreadViewModel` modal answer flow](modal-answer-flow.md)) ·
  [#458](../codebase/458.md) — the **outbound** `interrupt` passthrough (the `cancelModal` mirror,
  fire-and-forget; consumer: [`ThreadViewModel.onInterrupt`](interrupt-send-path.md)) ·
  [#493](../codebase/493.md) — **consolidates** the four connection-state holders (`mutableRepository`,
  `activePumpFlow`, `activeRemoteRepo`, the plain `active` var) into the single `activeConnection`, closing
  the direct-A→B-reconnect race that re-exposed a pre-`Open` repo and
  [re-introduced #421](https://github.com/pyrycode/pyrycode-mobile/issues/421)
  ("list never loads"). Every seam above now switches off that one source ·
  [#822](https://github.com/pyrycode/pyrycode-mobile/issues/822) — the
  [clarification-batch projection](relay-repository-coordinator-seams-and-passthroughs.md#question-batch-projection-822):
  switches the concrete repository's [`questionBatches`](remote-conversation-repository-live-stream-and-modals.md#questionbatches--the-v2-clarification-batch-decodefold-seam-822)
  the same way as `modalEvents`, but — unlike `currentModal` (#492) — resets to empty on every reconnect
  instead of retaining across one, because the daemon's connect-time reconcile rebuilds it ·
  [#825](https://github.com/pyrycode/pyrycode-mobile/issues/825) — the **outbound**
  [`answerQuestionBatch` / `refuseQuestionBatch`](relay-repository-coordinator-seams-and-passthroughs.md#outbound-question-answer--refuse-passthrough-825)
  passthrough (the `answerModal`/`interrupt` mirror): validates against the connection's held batches
  before sending, gets no ack, and clears nothing — only `question_dismissed` or a reconnect retires a
  batch ·
  [#677](https://github.com/pyrycode/pyrycode-mobile/issues/677) — the
  [background-task roster](relay-repository-coordinator-seams-and-passthroughs.md#background-task-roster-677):
  switches the concrete repository's
  [`backgroundTasks`](remote-conversation-repository-live-stream-and-modals.md#backgroundtasks--the-v2-background-task-decodefold-seam-677)
  the `questionBatches` way, but adds one host-lifetime `FinishedBackgroundTasks` instance (the
  `replayCursor` shape) that the reset does not touch, so a task the daemon's reconnect roster re-lists
  after it finished does not read as live again.
- Two-part status: [Connection status](connection-status.md) (`ConnectionStatus` + `PyrycodeLinkStatus`,
  [#392](../codebase/392.md)) — derived/published here; relay leg from
  [`relayStatus`](relay-link-status.md) ([#391](../codebase/391.md)); consumed by the Settings status
  line (**#390**, `blockedBy #392`).
- Specs: `docs/specs/architecture/351-connection-scoped-repository-coordinator.md` ·
  `docs/specs/architecture/365-reregister-push-token-on-reconnect.md`.
- Push stack: [`RemoteConversationRepository.registerPushToken`](remote-conversation-repository.md)
  ([#359](../codebase/359.md), the reused sender) · [`AppPreferences.pushToken`](app-preferences.md)
  ([#364](../codebase/364.md), the persisted token this hook reads) · Firebase #361 (the token origin via
  `onNewToken`).
- Input: [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md)) —
  publishes `currentConnection`.
- Built per connection: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md), now
  `: ManagedSessionPump`) + [Remote conversation repository](remote-conversation-repository.md)
  ([#312](../codebase/312.md)/[#313](../codebase/313.md)/[#329](../codebase/329.md)/[#346](../codebase/346.md)).
- Consumed by: the [stable conversation repository](stable-conversation-repository.md) facade
  (**#352**, landed — delegates over `currentRepository` so ViewModels hold one stable reference) and
  **[#350](../codebase/350.md)** (landed — the flag-gated `conversationRepositoryModule` selector binds
  that facade as `ConversationRepository` when `USE_RELAY_REPOSITORY` is on) — both were out of scope for
  *this* slice.
- DI: [Dependency injection](dependency-injection.md) · the [lifecycle connection driver](lifecycle-connection-driver.md)
  ([#302](../codebase/302.md)) is the `createdAtStart` precedent it mirrors.
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md),
  [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md).
</content>
