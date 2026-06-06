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
the connection scope and the `SessionPump` binding. It is a **wiring layer**: it adds no wire types, no
payloads, and no behaviour to the pump or repository — it only owns their lifecycle.

Package: `de.pyryco.mobile.data.repository` (`RelayRepositoryCoordinator` + the `ManagedSessionPump`
interface it drives, the latter appended to `SessionPump.kt`), co-located with the
[repository](remote-conversation-repository.md) it builds. Landed in [#351](../codebase/351.md) (split
from #349). Portable, `android.*`-free, emits **no logs**.

## Where it sits in the Phase 4 stack

```
#352 stable ConversationRepository facade   ◀── delegates to currentRepository (out of scope here)
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
    fun start()   // single-use; launches the handshake + open-state dispatch drive
    fun close()   // idempotent; wipes session keys + tears the pump's session/scope down
}

// data/repository/RelayRepositoryCoordinator.kt
class RelayRepositoryCoordinator(
    connections: StateFlow<RelayTransport?>,                 // = supervisor.currentConnection (the input)
    createPump: (RelayTransport) -> ManagedSessionPump,      // prod: { NoiseSessionPump(it, sessionFactory) }
    dispatcher: CoroutineDispatcher = Dispatchers.Default,   // injection seam (test clock); stored as a val
) {
    val currentRepository: StateFlow<ConversationRepository?>  // live repo, or null between connections
    fun start()   // idempotent — launches the single connections collector on the coordinator scope
    fun close()   // tears down the active connection (wiping pump keys) + cancels the coordinator scope
}
```

`ManagedSessionPump` exists because the pump has **two consumers with different needs** (Interface
Segregation): the repository reads `inbound` and calls `send` (the `SessionPump` *data* view), while the
coordinator additionally `start()`s and `close()`s it (the *lifecycle* view). The coordinator depends on
the richer contract and hands the **same instance, upcast to `SessionPump`**, to the repository.
[`NoiseSessionPump`](noise-session-pump.md) declares `: ManagedSessionPump` — its four members already
matched structurally, so this was purely additive (no behaviour change).

## How it works — the connection→repository state machine

A single, **non-suspending** `onConnection(transport: RelayTransport?)` handles each `currentConnection`
emission, run by one collector launched in `start()`:

1. **Tear down the active connection** (always, first): `currentRepository = null`, then **cancel the
   child scope, then close the pump** — order matters (see below).
2. **If `transport == null`**, return — this is the between-connections state.
3. **Else build a fresh connection**: a per-connection `childScope` (child of the coordinator job),
   `pump = createPump(transport).also { it.start() }`, retain `(pump, childScope)`, and publish
   `RemoteConversationRepository(pump, childScope)` on `currentRepository`.

```
currentConnection :  null → T1 → null → T2 → …
        ▼
onConnection (single collector, sequential, non-suspending)
   T1 ─▶ pump1 = create(T1).start();  repo1 = Remote(pump1, scope1);  currentRepository = repo1
 null ─▶ scope1.cancel(); pump1.close()  (keys wiped);               currentRepository = null
   T2 ─▶ pump2 = create(T2).start();  repo2 = Remote(pump2, scope2);  currentRepository = repo2
```

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
        createPump = { transport -> NoiseSessionPump(transport, sessionFactory) },
    ).also { it.start() }
}
```

`createdAtStart` so it observes `currentConnection` for the process lifetime. It does **not** bind
`ConversationRepository` — the [Fake](conversation-repository.md) stays the default until the #350
flag-gated swap. With the coordinator eager and the supervisor already dialing in a paired+foregrounded
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

## Related

- Ticket: [#351](../codebase/351.md) — implementation record (files, line refs, patterns, lessons).
- Spec: `docs/specs/architecture/351-connection-scoped-repository-coordinator.md`.
- Input: [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md)) —
  publishes `currentConnection`.
- Built per connection: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md), now
  `: ManagedSessionPump`) + [Remote conversation repository](remote-conversation-repository.md)
  ([#312](../codebase/312.md)/[#313](../codebase/313.md)/[#329](../codebase/329.md)/[#346](../codebase/346.md)).
- Consumed by: the [stable conversation repository](stable-conversation-repository.md) facade
  (**#352**, landed — delegates over `currentRepository` so ViewModels hold one stable reference) and
  **#350** (the flag-gated Fake↔Remote binding swap) — both out of scope for *this* slice.
- DI: [Dependency injection](dependency-injection.md) · the [lifecycle connection driver](lifecycle-connection-driver.md)
  ([#302](../codebase/302.md)) is the `createdAtStart` precedent it mirrors.
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md),
  [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md).
</content>
