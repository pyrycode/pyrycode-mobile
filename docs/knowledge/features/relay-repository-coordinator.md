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

The coordinator derives [two-part connection status](#two-part-connection-status-392),
switches [live-session events](#live-session-event-seam-406) across reconnects, and
owns [FCM push-token re-registration](#connect-time-fcm-push-token-re-registration-365)
once per connection. Explicit [diagnostic archive requests](relay-debug-bundle-transfer.md)
also enter through this owner so admission and teardown share a connection lifetime.

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
    suspend fun answerModal(modalId: String, optionId: String)  // (#451) outbound modal_answer passthrough — the inbound-modal mirror, but a call not a flow
    suspend fun cancelModal(modalId: String)                    // (#451) outbound modal_cancel passthrough
    suspend fun interrupt(conversationId: String)                // explicit conversation target; fire-and-forget
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
See [host access and its reconnect regression](dependency-injection.md#exact-host-repository-access).

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

Per the daemon's contract (`docs/protocol-mobile.md` § Phone background behaviour) the phone re-registers
its FCM push token on **every** WS connect, so the daemon's wake target self-heals across app restarts and
connection drops; the server de-duplicates the `(platform, token, device_name)` triple, so a repeat is a
cheap (~100 B) no-op. [#365](../codebase/365.md) adds that connect-time orchestration here — the only
Phase 4 FCM slice that touches the connection lifecycle — reusing [#359](../codebase/359.md)'s
`RemoteConversationRepository.registerPushToken` sender unchanged.

After publishing the repo, `onConnection` launches `reregisterPushTokenOnOpen(pump, repo)` on the
per-connection `childScope`. The hook:

1. **Awaits the first transition out of `Handshaking`** — `pump.state.first { it is Open || it is Closed }`.
   `StateFlow.first {}` checks the current value first, so an already-`Open` pump fires with no missed-edge
   race.
2. **Aborts on a pre-Open `Closed`** (handshake fault / transport down) — `return`, nothing to register.
3. **Reads the token** — `val token = pushToken() ?: return`. A `null` token is a **no-op**: the capability
   is **dormant** until a token is stored (Firebase #361 via [#364](../codebase/364.md)).
4. **Sends once** — `repo.registerPushToken(token)`, swallowing failure.

It fires **exactly once per connection**, guaranteed *structurally*: each connection builds a fresh pump +
child scope + hook, and the `PumpState` machine never revisits `Handshaking` (re-key stays `Open`). No
client-side dedup — the server dedupes the triple.

Three load-bearing constraints shape it:

- **It uses the *concrete* `repo` handle, not `currentRepository`.** `registerPushToken` is **not** on the
  `ConversationRepository` interface (#359 — it is a device/connection concern), and `currentRepository` is
  interface-typed, so the hook calls it through the concrete `RemoteConversationRepository` captured at
  construction. This is the **first live caller** of the method #359 shipped dormant. It does **not** add a
  second `currentRepository` observer or a second connection-state subscription — it reuses the one
  `onConnection` collector + the pump's existing `state`.
- **`onConnection` stays non-suspending.** `launch` schedules and returns; all suspending work runs on the
  child scope, off the critical path — preserving the cancellation-atomicity / key-wipe invariant (below).
  The hook is **never** awaited inline.
- **Swallow, but propagate cancellation.** A narrow `catch` re-throws `CancellationException` (a drop
  cancels `childScope` mid-call — absorbing it would break structured-concurrency teardown) and swallows any
  other `Exception` **without logging** (the token is never logged; the daemon re-registers on the next
  connect by contract). A server `error` (`RelayErrorException`) or not-Open `IllegalStateException` is
  swallowed.

### Closing #359's `device_name: ""` defer

\#359 left `RemoteConversationRepository`'s `deviceName` ctor param defaulted to `""` and flagged that
whichever slice adds the live caller must thread the real name. #365 is that slice: it adds the
`deviceName: String = ""` coordinator param, threads it into the repo (`RemoteConversationRepository(pump,
childScope, deviceName)`), and `AppModule` supplies the live `NoiseClientInfo.deviceName` (`Build.MODEL`).
This matters because pyrycode's handler (#319) acks-with-**no-registry-touch** only when
`(Platform, Token, DeviceName)` matches the stored device — an empty `device_name` would *fork* the
server's dedup triple into a duplicate registry entry. See [[post-352-connection-scoped-repo-behind-facade]].

## Two-part connection status (#392)

The coordinator publishes [`connectionStatus`](connection-status.md), the combined
`ConnectionStatus { relay, pyrycode }` model the Settings status line consumes — surfaced onto
[`SettingsViewModel`](settings-viewmodel.md) and rendered as the
[`ConnectionStatusLine`](connection-status-line.md) under the Server row in **[#398](../codebase/398.md)**
(the live wiring of parent epic #390, `blockedBy #397`). It owns the connection-scoped pump, so it is where the **pyrycode-leg readiness** (the
honest `relay → daemon` end-to-end signal) is derived — the relay leg's `Connected` only means
*socket-open*, not Noise-session-open. The leg reaches `PyrycodeLinkStatus.Connected` **only** once the
pump reaches `Open` (handshake complete) — never on bare socket-up, never between connections —
closing the false green that bit live testing on 2026-06-08. See
[connection status](connection-status.md) for the model and the leg semantics.

It is **pure derivation** — no new mutable status state:

- The live pump is reached through the single [`activeConnection`](#the-single-connection-source-and-the-open-gated-currentrepository-421--493)
  source (`conn?.pump`), written on the non-suspending `onConnection`/`teardownActive` critical section (the
  fresh `Connection` on connect, `null` on teardown). The pump reference stays **inside** `activeConnection` —
  only the *derived* readiness is exposed, never the pump itself (the pump is single-owner).
- A private `pyrycodeStatus: Flow<PyrycodeLinkStatus>` = `activeConnection.flatMapLatest { conn ->
  conn?.pump?.state ?: flowOf(null) }.map { it.toPyrycodeLinkStatus() }` — the same
  `flatMapLatest`-over-a-live-child idiom [`StableConversationRepository`](stable-conversation-repository.md)
  uses for `currentRepository`. It tracks the **current** pump across reconnects with no carryover
  (`flatMapLatest` cancels the prior pump's `state` collection); "no connection" maps through `null` to the
  `Down` floor.
- The public `connectionStatus: StateFlow<ConnectionStatus>` = `combine(relayStatus, pyrycodeStatus) {
  relay, pyrycode -> ConnectionStatus(relay, pyrycode) }.stateIn(scope, SharingStarted.Eagerly, …)` on
  the coordinator's **existing** `scope` (cancelled by `close()`, so it doesn't hang `runTest`).

The mapping `internal fun PumpState?.toPyrycodeLinkStatus()` (bottom of the file, sibling to #391's
`RelayLinkStatus.toConnectionState()`) is total over `PumpState` + `null`: `null`/`Closed → Down`,
`Handshaking → Handshaking`, `Open → Connected`. It **discards `Open.connId` and `Closed.cause`** —
the no-log / no-leak contract is structurally enforced (no relay/crypto-derived string reaches the
status surface). `relayStatus` and `connections` come directly from the owning bundle's supervisor;
neither needs a separate Koin binding. `AppModule` passes the registry
compatibility projection, `get<RelayConnectionRegistry>().connectionStatus`, into
`SettingsViewModel`, keeping both legs on the selected host.

> **Init-order gotcha.** `stateIn(scope, Eagerly, …)` runs at *property initialization*, so
> `connectionStatus`/`pyrycodeStatus` (and `currentRepository`/`currentModal`) must be declared **after**
> `scope` and `activeConnection` in the class body — referencing an earlier-declared field is a
> construction-time NPE (not a compile error). `activeConnection` sits just below `scope` (where the old
> holders were), so every deriver below it satisfies this.

## Live-session event seam (#406)

The decoded [`LiveSessionEvent`](live-session-events.md) stream ([#385](../codebase/385.md)) lives on
the **concrete** `RemoteConversationRepository.liveSessionEvents` — connection-scoped and **not** on the
`ConversationRepository` interface — so a UI ViewModel cannot reach it. The coordinator owns the
connection-scoped repository, so it threads that non-interface surface up exactly as `pyrycodeStatus`
reaches the concrete pump through `activeConnection` (and as `registerPushToken` reaches the concrete repo
through the construction-time handle):

```kotlin
val liveSessionEvents: Flow<LiveSessionEvent> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.liveSessionEvents ?: emptyFlow() }
```

- The concrete `repo` is reached through the single `activeConnection` source (`conn?.repo`) — its
  `Connection.repo` field is set as part of `activeConnection.value = Connection(pump, scope, repo, transport)` in
  `onConnection` and cleared to `null` in `teardownActive`, both **non-suspending** writes inside the same
  critical section, so the cancellation-atomicity invariant is preserved. The repo reference stays **inside**
  `activeConnection`: only the *derived* event flow is exposed, never the concrete repo reference. (Before
  [#493](../codebase/493.md) this was a separate private `activeRemoteRepo` mirror; the consolidation folded
  it into the one source.)
- **Cold, not `stateIn`'d.** Unlike `connectionStatus` (current-value state), these are *events* with no
  "current value", so `liveSessionEvents` is a cold `Flow` with no scope of its own. Each consumer's
  collection independently observes `activeConnection` (a `StateFlow`) and subscribes to the current
  repo's `SharedFlow` (both multi-subscriber-safe) — no `shareIn`. (A code-review NIT flagged that
  per-subscriber `flatMapLatest` re-derivation is fine at today's consumer count; revisit only if the
  count grows.)
- **Reconnection-surviving.** `flatMapLatest` cancels the prior connection's collection and switches to
  the fresh repo's `liveSessionEvents` on each new connection; `emptyFlow()` between connections. A push
  on a now-dead pump surfaces nowhere — a coordinator test pins this.
- **Generic, not turn-state-specific.** The seam carries the **full** `LiveSessionEvent` stream, not an
  `isThinking`/turn-state projection — reducing to "latest phase" is a consumer concern. The first
  consumer is [`ThreadViewModel.isThinking`](turn-state-thinking-flag.md) (#406, the thinking-indicator
  data half); #387 (tool timeline) and #337 (live assistant text) reuse the same flow without
  re-plumbing this layer. `AppModule` supplies the registry projection of the
  selected coordinator's flow, exactly like `connectionStatus`.

## Modal event seam (#445) and the hoisted currentModal fold (#492)

The decoded [`ModalEvent`](modal-events.md) stream ([#437](../codebase/437.md)) lives on the **concrete**
`RemoteConversationRepository.modalEvents` (`replay = 0`, connection-scoped, **not** on the interface) —
the same posture as `liveSessionEvents`, so a UI ViewModel cannot reach it directly. The coordinator
threads it up as a **byte-for-byte mirror** of the live-session seam — switching off the same single
`activeConnection` source (`conn?.repo`) — and, as of [#492](../codebase/492.md), **folds it here** into
one "which modal is open" projection per retained host bundle:

```kotlin
// #492: PRIVATE — its sole consumer is currentModal below.
@OptIn(ExperimentalCoroutinesApi::class)
private val modalEvents: Flow<ModalEvent> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.modalEvents ?: emptyFlow() }

// #492: the hoisted projection, folded once per coordinator.
val currentModal: StateFlow<ModalUiState> =
    modalEvents
        .scan<ModalEvent, ModalUiState>(ModalUiState.Hidden) { state, event -> state.reduce(event) }
        .stateIn(scope, SharingStarted.Eagerly, ModalUiState.Hidden)
```

- **`modalEvents` is cold and now `private`** — events, no current value. The fold that holds "which modal
  is currently open" moved here in #492 from [`ThreadViewModel`](current-modal-state.md): folding it at a
  screen-scoped VM dropped any `modal_shown` fired before a thread screen subscribed (the source is
  `replay = 0`), so an outstanding prompt stayed stuck daemon-side while the phone rendered nothing. After
  the hoist nothing outside the coordinator reads the raw event stream, so it was demoted to `private`.
- **`currentModal` mirrors `currentRepository` / `connectionStatus`** — accumulate a `replay = 0`-derived
  stream `Eagerly` on the coordinator `scope` so `.value` is always the true current projection. Started
  `Eagerly` (not `WhileSubscribed`) is load-bearing: `scan` re-emits its seed on every fresh collection, so
  a resubscribe past a stop window would overwrite a retained `Open` with `Hidden`, and the `replay = 0`
  source won't replay to rebuild it (full rationale in [Current-modal state](current-modal-state.md#why-eagerly-not-whilesubscribed)).
  The pure `ModalUiState.reduce` lives in `data/model` (moved there in #492 so this `data`-layer coordinator
  can see it) and emits **no log** (modal fields may name a sensitive command/path).
- **Reconnection-surviving; retains across teardown.** `flatMapLatest` switches to the fresh repo's
  `modalEvents` on each new connection and cancels the prior; `emptyFlow()` between connections. The `.scan`
  sits **downstream** of `flatMapLatest`, so a connection drop does **not** restart it — a still-`Open`
  modal is **retained**, not reset to `Hidden` (the #492 teardown decision: the answer path is guarded by
  the deterministic `answerModal`/`cancelModal` null-guard, never by this UI projection, so retaining a
  stale `Open` can't send an answer on a dead connection).
- `AppModule` passes the registry's selected-host `currentModal` projection into
  `ThreadViewModel`. Every retained coordinator keeps folding its own modals even
  while another host is selected; overlapping modal ids never share an accumulator.

## Outbound modal-send passthrough (#451)

The **outbound mirror** of the inbound `modalEvents` seam: where `modalEvents` surfaces decoded modals *up*
to the ViewModel, the answer/cancel passthrough sends the user's decision *down* to the connection-scoped
concrete [`RemoteConversationRepository.answerModal` / `cancelModal`](remote-conversation-repository.md)
([#438](../codebase/438.md)). The asymmetry is correct: inbound is a stream (`Flow`); an answer/cancel is a
request/reply control **call**, so these are **suspend methods, not flows**. Both read the concrete repo off
the single `activeConnection` source (`activeConnection.value?.repo`):

```kotlin
suspend fun answerModal(modalId: String, optionId: String) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.answerModal(modalId, optionId)
}
// cancelModal(modalId) is identical, minus the optionId.
```

- **Null-guard only — both not-connected paths funnel to `IllegalStateException`.** When
  `activeConnection.value == null` (between connections) the guard throws. When a connection exists but the
  pump is still pre-`Open` (Handshaking), `repo.answerModal` → `sendAndAwaitReply` → `pump.send` returns
  false → `IllegalStateException` (the #438 precedent). So the passthrough needs **only** the null-guard — a
  redundant `Open` gate (like `currentRepository`'s, which exists for a different reason: facade
  publication) would be needless complexity, since the concrete send already fails fast.
- A server `error` propagates from the concrete repo as `RelayErrorException` **unchanged** — the
  passthrough neither catches nor maps it (the consuming [`ThreadViewModel`](modal-answer-flow.md) catches
  both exceptions and surfaces a one-shot error signal).
- **No log** — the `modalId`/`optionId` may name a sensitive command/path (never-log contract); the
  passthrough adds no `android.*` (data/ stays portable).
- The consumer is [`ThreadViewModel.sendAnswer` / `sendCancel`](modal-answer-flow.md), bound at the
  `AppModule` `ThreadViewModel` factory as suspend **method references**
  (`answerModal = registry::answerModal`, likewise cancel), which resolve the
  selected coordinator at call entry, matching the inbound compatibility seams.

## Outbound interrupt passthrough (#458)

The third outbound control passthrough forwards the open thread's conversation id
unchanged (#626). It reads the concrete repository from `activeConnection` to
select the transport; the argument selects the conversation to stop. See
[Interrupt send path](interrupt-send-path.md).

```kotlin
suspend fun interrupt(conversationId: String) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.interrupt(conversationId)
}
```

- **Null-guard only**, identical to `cancelModal`: between connections the guard throws; a connection that
  exists but whose pump is pre-`Open` surfaces as the concrete
  [`RemoteConversationRepository.interrupt`](remote-conversation-repository.md)'s `check(pump.send(...))` →
  `IllegalStateException`. No redundant `Open` gate. Never logs.
- **Explicit target:** the repository encodes the supplied id as
  `interrupt.payload.conversation_id`. No shared active-conversation cursor is read,
  so prior activity in A cannot choose the target of a call naming B.
- **Fire-and-forget:** the passthrough awaits no acknowledgment and changes no
  local turn state. The open conversation's inbound turn events remain authoritative.
- Bound at the `AppModule` `ThreadViewModel` factory as a suspend **method reference** (`interrupt =
  registry::interrupt`) into the VM's defaulted `suspend (String) -> Unit` lambda.
  The registry selects the host at call entry; the supplied id selects that host's
  conversation. The consumer is [`ThreadViewModel.onInterrupt` / `sendInterrupt`](interrupt-send-path.md);
  unlike `sendCancel` its failure catches are **empty** (no error channel or log).
  `CancellationException` is rethrown before the failure catches.

## Reconnect-spanning replay cursor (#412)

Each bundle's coordinator owns one in-memory [`ReplayCursor`](replay-cursor.md):
the latest interactive structured-stream [`Envelope.eventId`](mobile-protocol-v2-wire-layer.md)
observed across that bundle's reconnects. Explicit-record bundles for A and B
have separate coordinators and cursors even if their relay URL is the same.
The cursor cannot live on the per-connection
[`RemoteConversationRepository`](remote-conversation-repository.md), which is
rebuilt each reconnect: the next `hello` needs the old position before its new
inbound path exists. It is not persisted across bundle replacement or process
restart.

```kotlin
internal val replayCursor: ReplayCursor = ReplayCursor()   // one per coordinator, survives reconnects
```

- Each per-connection repository receives its coordinator's cursor through
  `replayCursor = replayCursor` in non-suspending `onConnection`. Inbound recording
  or a `resync` reset in A changes only A's cursor; B retains its position.
- `teardownActive` never clears the cursor. Drops, retry and background supervisor
  close preserve it while replacing the transport, pump, Noise session and
  repository. Bundle disposal ends this owner's usable lifetime; a new bundle
  starts empty.
- The [session factory supplier](noise-ik-session.md#factory-wiring) closes over
  the owning coordinator's `internal val replayCursor`, with no Koin lookup.
  It reads `.latest` at `hello`-build, advertising that position as
  `hello.last_event_id`; `null` omits the field. Reading an app-wide coordinator
  would mix hosts, and capturing a value at construction would miss later events.

The registry selects among retained explicit-record bundles; it never moves a
cursor between them. Selection changes leave all host cursors untouched. The
older `createCompatibility(store)` helper can reread a different host on redial
while retaining one cursor, so it is not used by app DI for collection ownership.

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
or disposed. Threads use [exact-host dependencies and Retry](dependency-injection.md#destination-ownership);
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
In relay mode, the [host source](dependency-injection.md#snapshot-lifetime), once resolved,
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
  ("list never loads"). Every seam above now switches off that one source.
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
