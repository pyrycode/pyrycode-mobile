# Relay repository coordinator — a Noise pump + remote repository per live connection

The **Phase 4 connection-scoping layer**: the loop that sits **on top of** the
[reconnect supervisor](relay-reconnect-supervisor.md) and the [Noise session pump](noise-session-pump.md)
and turns a *live relay socket* into a *working remote conversation repository* — for exactly as long as
that socket lives. It observes the supervisor's `currentConnection`, and for each live transport it
starts a fresh Noise pump over it and constructs a [`RemoteConversationRepository`](remote-conversation-repository.md)
against that pump on a connection-scoped child scope. The live repository — or `null` between
connections — is published on `currentRepository`, the seam the **stable-reference facade** (#352)
consumes so ViewModels never re-resolve across connection churn.

This is the slice every prior Phase 4 read/mutation slice deferred to as **"the owner (#279/#302)"** of
the connection scope and the `SessionPump` binding. It is largely a **wiring layer**: it adds no wire
types and no payloads. Beyond owning the pump + repository lifecycle, it owns exactly one connect-time
side effect — the **FCM push-token re-registration** ([#365](../codebase/365.md)), which reuses
[#359](../codebase/359.md)'s sender unchanged (see [§ Connect-time push-token re-registration](#connect-time-fcm-push-token-re-registration-365)).
Because it owns the connection-scoped pump, it is also where the **pyrycode-leg session readiness** is
derived and the combined [`ConnectionStatus`](connection-status.md) `{relay, pyrycode}` status
published ([#392](../codebase/392.md)) — see [§ Two-part connection status](#two-part-connection-status-392).
For the same reason — it owns the connection-scoped *repository* — it surfaces the
reconnection-surviving [`liveSessionEvents`](live-session-events.md) seam ([#406](../codebase/406.md))
that brings #385's decoded turn-state/tool/assistant events to UI ViewModels — see
[§ Live-session event seam](#live-session-event-seam-406).

Package: `de.pyryco.mobile.data.repository` (`RelayRepositoryCoordinator` + the `ManagedSessionPump`
interface it drives, the latter appended to `SessionPump.kt`), co-located with the
[repository](remote-conversation-repository.md) it builds. Landed in [#351](../codebase/351.md) (split
from #349). Portable, `android.*`-free, emits **no logs**.

## Where it sits in the Phase 4 stack

```
\#352 stable ConversationRepository facade   ◀── delegates to currentRepository (out of scope here)
        ▲
RelayRepositoryCoordinator (#351) ─ per-connection pump + repository lifecycle   ◀── this doc
        │  observes currentConnection ; publishes currentRepository
        ├──────────────▶ RemoteConversationRepository (#312/#313/#329/#346)   ◀── one per connection
        │                       ▲  pump.inbound / pump.send
        └──────────────▶ NoiseSessionPump (#309)   ◀── one per connection; : ManagedSessionPump
                                ▲  collects inbound (decrypt) ; sends noise_msg (encrypt)
RelayConnectionSupervisor (#307) ─ currentConnection: StateFlow<RelayTransport?>
```

The coordinator is the **only place** the three Phase-4 siblings are joined: the supervisor owns the
socket, the pump owns the Noise session, the repository owns the conversation projections — each
code-independent — and this layer scopes the latter two to the former's lifetime.

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
    deviceName: String = "",                                 // (#365) live Build.MODEL; "" until AppModule wires it
    pushToken: suspend () -> String? = { null },             // (#365) one-shot token read; null ⇒ no registration
) {
    val currentRepository: StateFlow<ConversationRepository?>  // live repo, or null between connections
    val connectionStatus: StateFlow<ConnectionStatus>         // (#392) combined {relay, pyrycode} two-part status
    val liveSessionEvents: Flow<LiveSessionEvent>             // (#406) reconnection-surviving #385 live-event seam
    val currentModal: StateFlow<ModalUiState>                // (#492) the process-scoped "which modal is open" projection, folded here (Eagerly) off a now-PRIVATE #437 modal-event seam
    suspend fun answerModal(modalId: String, optionId: String)  // (#451) outbound modal_answer passthrough — the inbound-modal mirror, but a call not a flow
    suspend fun cancelModal(modalId: String)                    // (#451) outbound modal_cancel passthrough
    suspend fun interrupt()                                     // (#458) outbound bare interrupt passthrough — the cancelModal mirror, fire-and-forget
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

A single, **non-suspending** `onConnection(transport: RelayTransport?)` handles each `currentConnection`
emission, run by one collector launched in `start()`:

1. **Tear down the active connection** (always, first): `currentRepository = null`, then **cancel the
   child scope, then close the pump** — order matters (see below).
2. **If `transport == null`**, return — this is the between-connections state.
3. **Else build a fresh connection**: a per-connection `childScope` (child of the coordinator job),
   `pump = createPump(transport).also { it.start() }`, and
   `repo = RemoteConversationRepository(pump, childScope, deviceName, negotiatedCapabilities = { … })`,
   then publish the whole connection as **one object** — `activeConnection.value = Connection(pump,
   childScope, repo)`, the single source every derived seam (including the Open-gated `currentRepository`)
   projects from — and `childScope.launch { … }` the connect-time push-token re-registration hook (#365,
   below). `launch` returns immediately, so `onConnection` stays non-suspending.

   The fourth argument is the **capability supplier** [#385](../codebase/385.md) wired to gate the
   repo's [`liveSessionEvents`](remote-conversation-repository.md) decode seam on the negotiated
   `interactive` set: `negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() }`.
   A **supplier**, not a value, read lazily per structured envelope (the set is empty while the pump
   is `Handshaking`; structured envelopes only arrive post-`Open`). `pump.state.value` is a
   **non-suspending** read, so this adds no suspension point to the non-suspending critical section.
   No interface change — the `SessionPump` the repo consumes is untouched (this reads the live
   `ManagedSessionPump.state` the coordinator already owns).

```
currentConnection :  null → T1 → null → T2 → …
        ▼
onConnection (single collector, sequential, non-suspending)
   T1 ─▶ pump1 = create(T1).start();  repo1 = Remote(pump1, scope1, name);  activeConnection = Connection(pump1, scope1, repo1)
         scope1.launch { reregisterPushTokenOnOpen(pump1, repo1) }   (#365, off the critical path)
 null ─▶ activeConnection = null;  scope1.cancel(); pump1.close()  (keys wiped)
   T2 ─▶ pump2 = create(T2).start();  repo2 = Remote(pump2, scope2, name);  activeConnection = Connection(pump2, scope2, repo2)
         scope2.launch { reregisterPushTokenOnOpen(pump2, repo2) }
```

### The single connection source and the Open-gated `currentRepository` (#421 / #493)

There is **one** connection-state holder — `private val activeConnection = MutableStateFlow<Connection?>(null)`
— carrying the pump + child scope + concrete repo of the connection currently being served, or `null` between
connections. **Every** connection-derived seam is a projection of this one `StateFlow`: `currentRepository`,
`pyrycodeStatus`, `liveSessionEvents`, `modalEvents`/`currentModal`, `connectionStatus`, and the outbound
`answerModal`/`cancelModal`/`interrupt` passthroughs (which read its `.value`). It is written **only** on the
non-suspending `onConnection`/`teardownActive` path (plus the idempotent `close()`); the pump/repo references
stay *inside* it (single-owner) — only *derived* signals are ever exposed, never the pump reference itself.

`currentRepository` is **Open-gated**: it exposes the live repo only once the connection's Noise pump reaches
`PumpState.Open`, and `null` again between connections. This gate is the **#421 fix**. Exposing the repo at
bare socket-up (repo built but the pump still `Handshaking`) made the conversation list never load: the #352
facade subscribes to `RemoteConversationRepository.observeConversations` the moment a non-null repo appears,
and that subscription fires a **one-shot** `list_conversations` via `pump.send` — which returns `false` and is
**dropped** pre-`Open`, never re-issued after the handshake. The daemon never receives the request, never
replies with a `conversations` snapshot, and the list spins forever (the "New discussion" FAB, gated on the
first snapshot, never appears). Gating behind `Open` — mirroring `pyrycodeStatus` and the connect-time
push-token hook, which already await `Open` — means the facade subscribes, and the list send fires, only once
`pump.send` will succeed.

```kotlin
// #493: repo AND pump-state derive from the SAME switched value — one flatMapLatest over the one source.
val currentRepository: StateFlow<ConversationRepository?> =
    activeConnection
        .flatMapLatest { conn ->
            conn?.pump?.state?.map { if (it is PumpState.Open) conn.repo else null } ?: flowOf(null)
        }.stateIn(scope, SharingStarted.Eagerly, null)
```

**Why one source, not two (#493).** The gate previously `combine`d **two independently-mutated `StateFlow`s** —
a repo holder (`mutableRepository`) and a `flatMapLatest` over a *separate* pump holder (`activePumpFlow`). On a
**direct A→B reconnect** (the supervisor emits transport B while A is still live, with **no** interposed `null`),
`combine` collects its inputs on separate coroutines: the direct repo emission (`repoB`) arrives in one hop,
while the pump-state input must cancel pumpA's `state` collector and subscribe to pumpB's — one *extra* hop.
In that window `combine`'s cached pump-state is still **pumpA's `Open`** (and `ManagedSessionPump.close()`
leaves `state` at its last value, so nothing corrects it), so it transiently emits `(repoB, Open) → repoB`:
**the new connection's repo is exposed while its own pump is still `Handshaking`** — the facade's one-shot
`list_conversations` fires pre-`Open` and is dropped, **re-introducing #421**. Deriving repo *and* pump-state
from one switched `Connection` closes that window **structurally**: the inner lambda **closes over `conn`**, so
the mapped `conn.repo` and `conn.pump.state` are always the *same* connection's. Switching to connection B
yields `connB.pump.state.value` (`Handshaking`) → `null` first, and there is no path that pairs `connB.repo`
with any pump but B's own. (The `if (Open) conn.repo else null` widens `RemoteConversationRepository` to
`ConversationRepository?` via `Flow`/`StateFlow` covariance; the declared type stays
`StateFlow<ConversationRepository?>`.) The settled `.value` on a full drain is `null` on both the old and new
code — the bug is a **transient emission**, observable only by *collecting* `currentRepository`, not by
reading `.value` (the shape of the #493 regression test).

> **History.** #421 added the Open-gate; #493 kept the gate but replaced its plumbing — collapsing the four
> holders (`mutableRepository`, `activePumpFlow`, `activeRemoteRepo`, and a plain `active: Connection?` var)
> into the single `activeConnection` and deriving the gate from it. The consolidation is a strict reduction of
> mutable state (4 → 1), not added machinery. See [#493](../codebase/493.md) /
> [#421](https://github.com/pyrycode/pyrycode-mobile/issues/421).

### Scope ownership (three distinct scopes)

- **Coordinator scope** — `CoroutineScope(SupervisorJob() + dispatcher)`; owns the `connections`
  collector. Cancelled by `close()`.
- **Per-connection `childScope`** — `SupervisorJob(coordinatorJob) + dispatcher`; owns the repository's
  single inbound collector. Cancelled on each teardown, and transitively when the coordinator scope dies.
- **Pump scope** — owned **by `NoiseSessionPump` itself**, *not* a child of `childScope`. Reached **only**
  by `pump.close()`. This is why teardown must close the pump explicitly: cancelling the child scope does
  not reach the pump's own `SupervisorJob`.

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
status surface). `relayStatus` is fetched off the concrete supervisor (`get<RelayConnectionSupervisor>().relayStatus`)
exactly like `connections`, with **no new Koin binding** — and the consumer (#398) likewise obtains
the combined model with **no new binding**, passing `get<RelayRepositoryCoordinator>().connectionStatus`
straight into the `SettingsViewModel` constructor at the `AppModule` factory.

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
  `Connection.repo` field is set as part of `activeConnection.value = Connection(pump, scope, repo)` in
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
  re-plumbing this layer. Each fetches it off the concrete coordinator singleton at the `AppModule`
  factory — **no new Koin binding** — exactly like `connectionStatus`.

## Modal event seam (#445) and the hoisted currentModal fold (#492)

The decoded [`ModalEvent`](modal-events.md) stream ([#437](../codebase/437.md)) lives on the **concrete**
`RemoteConversationRepository.modalEvents` (`replay = 0`, connection-scoped, **not** on the interface) —
the same posture as `liveSessionEvents`, so a UI ViewModel cannot reach it directly. The coordinator
threads it up as a **byte-for-byte mirror** of the live-session seam — switching off the same single
`activeConnection` source (`conn?.repo`) — and, as of [#492](../codebase/492.md), **folds it here** into
the single process-scoped "which modal is open" projection:

```kotlin
// #492: PRIVATE — its sole consumer is currentModal below.
@OptIn(ExperimentalCoroutinesApi::class)
private val modalEvents: Flow<ModalEvent> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.modalEvents ?: emptyFlow() }

// #492: the single hoisted projection, folded once at this process-scoped layer.
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
- The sole consumer is `ThreadViewModel`, which re-exposes `currentModal` verbatim (fetched off the
  concrete coordinator singleton at the `AppModule` `ThreadViewModel` factory — **no new Koin binding**,
  exactly like `liveSessionEvents`).

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
  (`answerModal = coordinator::answerModal`) — **no new Koin binding**, exactly like the inbound seams.

## Outbound interrupt passthrough (#458)

The third outbound control passthrough, the exact `cancelModal` mirror for the bare `interrupt` frame (the
remote-Esc wire half — see [Interrupt send path](interrupt-send-path.md), [#458](../codebase/458.md)). It
reads the concrete repo off the same single `activeConnection` source:

```kotlin
suspend fun interrupt() {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.interrupt()
}
```

- **Null-guard only**, identical to `cancelModal`: between connections the guard throws; a connection that
  exists but whose pump is pre-`Open` surfaces as the concrete
  [`RemoteConversationRepository.interrupt`](remote-conversation-repository.md)'s `check(pump.send(...))` →
  `IllegalStateException`. No redundant `Open` gate. Never logs.
- **No `optionId`/`modalId` — interrupt is connection-level and bare.** Unlike the modal passthroughs it
  takes no arguments; the frame carries no `conversation_id` ("the one running turn").
- Bound at the `AppModule` `ThreadViewModel` factory as a suspend **method reference** (`interrupt =
  coordinator::interrupt`) into the VM's defaulted `interrupt` lambda — **no new Koin binding**, exactly like
  the modal seams. The consumer is [`ThreadViewModel.onInterrupt` / `sendInterrupt`](interrupt-send-path.md);
  unlike `sendCancel` its catches are **empty** (interrupt is inert on failure — no error channel, no log).

## Reconnect-spanning replay cursor (#412)

The coordinator owns the [`ReplayCursor`](replay-cursor.md) ([#412](../codebase/412.md)) — the durable
high-water mark of the latest interactive structured-stream
[`Envelope.eventId`](mobile-protocol-v2-wire-layer.md) observed across all connections — for the **same
reason** it owns `liveSessionEvents` and the pyrycode-leg derivation: it is the single process-lifetime
layer that holds both the per-connection repo (the recorder) and the per-connection pump (the future
hello producer). The cursor **cannot** live on the per-connection
[`RemoteConversationRepository`](remote-conversation-repository.md) (rebuilt each reconnect), because it
must outlive connection churn and be readable at the *next* connection's `hello`-build moment — **before**
that connection's inbound path exists.

```kotlin
internal val replayCursor: ReplayCursor = ReplayCursor()   // survives connection churn; read by #413 at hello-build
```

- Threaded into each per-connection repo in `onConnection` as one extra **defaulted** named arg
  (`replayCursor = replayCursor`) — keeping `onConnection` non-suspending (no new suspension point) and
  every existing construction/test compiling unchanged (the same defaulted-param discipline as
  `deviceName`/`negotiatedCapabilities`).
- **Survives reconnects** because `teardownActive` (the per-connection churn path) never touches it; only
  a full `close()` ends the process-scoped object. A coordinator test pins `replayCursor.latest`
  persisting across an `onConnection` churn.
- **`internal`, read-only seam** (mirroring `toPyrycodeLinkStatus`'s visibility) so unit tests and the
  consuming slice [#413](https://github.com/pyrycode/pyrycode-mobile/issues/413) read it **without** a
  public API surface or a new Koin binding. This slice **records** the cursor; #413 reads
  `replayCursor.latest` at `buildHello` to advertise `last_event_id` and resume the missed event tail.
  The cursor is the third non-interface surface threaded off the concrete repo/pump, after
  `liveSessionEvents` (#406) and the `pyrycodeStatus` derivation (#392).

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
  "no state carryover on reconnect" is a **crypto invariant**, not just hygiene — nothing (projection
  state, in-flight requests, the pump) is shared between an old and new connection.
- **Never collects the transport's single-consumer streams** (`inbound`/`events`) — it only hands the
  `RelayTransport` reference to `createPump`. A stray collection would steal frames from the handshake
  (the pump owns `inbound`) or from the supervisor (owns `events`).

The **non-suspending `onConnection`** is itself a concurrency safeguard: cooperative cancellation only
acts at a suspension point, so a suspension-free build/teardown body is handled *atomically* w.r.t. a
racing `close()` — closing the one window where a cancellation between `pump.start()` and retaining the
reference would leak a started-but-unclosed pump.

## Configuration

DI registration in `AppModule.kt`, mirroring the [`LifecycleConnectionDriver`](lifecycle-connection-driver.md)
eager-singleton precedent:

```kotlin
single(createdAtStart = true) {
    val sessionFactory = get<NoiseSessionFactory>()
    RelayRepositoryCoordinator(
        connections = get<RelayConnectionSupervisor>().currentConnection,
        relayStatus = get<RelayConnectionSupervisor>().relayStatus,     // #392: the relay leg of connectionStatus
        createPump = { transport -> NoiseSessionPump(transport, sessionFactory) },
        // #365: close #359's device_name: "" defer + supply the connect-time token read.
        deviceName = get<NoiseClientInfo>().deviceName,                 // Build.MODEL
        pushToken = { get<AppPreferences>().pushToken.first() },        // one-shot read of the persisted token
    ).also { it.start() }
}
```

The two `#365` params are **defaulted** (`""` / `{ null }`), so the connect-time re-registration is
dormant until `AppModule` wires these live values; `AppPreferences` and `NoiseClientInfo` were already
resolvable singletons. `pushToken.first()` is the correct one-shot read of the non-completing DataStore
flow.

`createdAtStart` so it observes `currentConnection` for the process lifetime. It does **not** bind
`ConversationRepository` — that binding lives in #350's flag-gated `conversationRepositoryModule` selector
(the [Fake](conversation-repository.md) is the default-OFF binding; the facade is selected when
`USE_RELAY_REPOSITORY` is on). With the coordinator eager and the supervisor already dialing in a paired+foregrounded
app, a real `Noise_IK` handshake runs on each live connection; this is bounded — no
`list_conversations`/`backfill_since` is sent until a subscriber calls a read path (only #352/#350 wire
that up), so a live-but-unconsumed pump just completes the handshake and idles. #350's flag gates what
the **UI reads**, not whether the encrypted channel is established.

## Edge cases / limitations

- **`currentRepository` is `null` between connections** — by design. The #352 facade renders the
  no-connection state; consumers re-subscribe against the next connection's repository.
- **In-flight reads are dropped on connection loss.** `childScope.cancel()` stops the repository's
  collector; any cold `observeX` flow on a consumer completes/cancels naturally when its upstream scope
  dies. The connection-drop-mid-send leak (an awaiting `sendMessage` deferred that never completes) is a
  known open item carried by [#346](../codebase/346.md) — the coordinator adds no per-request timeout.
- **No retry of its own.** Reconnect cadence is governed entirely by the supervisor's capped-exponential
  backoff (1/2/4/8/16/30 s); a failing relay cannot drive a tight pump-rebuild loop.
- **The exposed observable is the only contract** — this slice does **not** implement the stable
  `ConversationRepository` facade (#352) and does **not** touch ViewModels.

## Testing

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
  [`currentModal`](current-modal-state.md) fold to this process-scoped layer (`modalEvents` demoted to
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
