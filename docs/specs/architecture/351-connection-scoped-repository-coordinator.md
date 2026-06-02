# Spec #351 — Connection-scoped coordinator: a Noise pump + remote repository per live relay connection

**Ticket:** [#351](https://github.com/pyrycode/pyrycode-mobile/issues/351) · split from #349 · size **S** · labels `security-sensitive`
**Siblings:** #352 (stable `ConversationRepository` facade — consumes this slice's exposed observable) · #350 (flag-gated Fake↔Remote binding swap)

---

## Files to read first

| Path · lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:1-30` | The consumer contract (`inbound: Flow<Envelope>` + `send(Envelope): Boolean`). This slice adds `ManagedSessionPump : SessionPump` here. KDoc already says "the DI / connection-coordinator slice makes [NoiseSessionPump] `: SessionPump`". |
| `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:49-121` | Constructor `(transport, sessionFactory, …)`, `start()` (single-use), `send()`, `close()` (idempotent). These are the four members the coordinator drives; `inbound`/`send` already match `SessionPump` structurally. |
| `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:255-300` | `teardown(cause)` — closes the inbound channel, `session.close()` (**wipes keys**), `transport.close()`, cancels the pump's own scope. `PumpState` sealed type. This is why `close()` is the load-bearing security call on teardown. |
| `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:77-104` | `currentConnection: StateFlow<RelayTransport?>` — set to the live transport on `Up` (`runLoop`, line 135), cleared to `null` on `Down`/`close` (lines 102, 154). This is the coordinator's **input**. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:52-92` | Constructor `(pump: SessionPump, scope: CoroutineScope)`; `init` launches the **single** inbound collector on `scope`, documented as "Cancelled by its owner (#279/#302) when the connection ends." That owner is this coordinator. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:189-258` | `observeConversations` (sends `list_conversations` on subscribe) and `observeMessages` (sends `backfill_since`) — the read paths AC #4 exercises through the coordinator-built repo. |
| `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt:24-59` | Single-use, single-consumer-`inbound` transport contract. Confirms: a fresh connection ⇒ a fresh transport ⇒ must be a fresh pump (Noise ephemerals are per-handshake). |
| `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt:19-53` | The pump's session dependency — already a DI `single`. The DI `createPump` lambda closes over it. |
| `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt:27-52` | The DI precedent: a `single(createdAtStart = true) { … .also { it.start() } }` that observes a process-lifetime signal. Mirror this registration shape for the coordinator. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:44-65` | Wiring site. **Line 54** (`FakeConversationRepository … bind ConversationRepository::class`) must stay untouched (AC #5). Lines 56 + 60-65 show the supervisor binding and the `createdAtStart` precedent. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:660-701` | `FakeSessionPump` (UNLIMITED-channel-backed `inbound` + recorded `sent`) and the `conversationsEnvelope`/`messageEnvelope`/`messageChunkEnvelope` helpers. The coordinator test extends this fake to a `ManagedSessionPump` (adds `start`/`close` flags). |
| `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:308-382` | The exact test idiom for a class that owns its own scope: `StandardTestDispatcher(testScheduler)`, `runCurrent()`, **`close()` at the end of every test** so `runTest` doesn't hang on the perpetual collector, and the `FakeRelayTransport`/`FakeRelayTransportFactory` shapes. |

---

## Context

Phase 4 backend integration. Every prior slice is merged but nothing stands up the per-connection chain that turns a live socket into a working remote repository. Two gaps remain, both this slice's job:

1. **`NoiseSessionPump` does not yet declare the `SessionPump` contract** the repository consumes — though `inbound` and `send` already match structurally.
2. **No code observes `currentConnection`** to build the pump + repository chain and tear it down with the connection.

The seam shape is fixed by the merged code: `RelayConnectionSupervisor.currentConnection: StateFlow<RelayTransport?>` (input) → a coordinator → an exposed `StateFlow<ConversationRepository?>` (output, consumed by the #352 facade). The coordinator owns the per-connection lifecycle the supervisor's KDoc defers to it ("a layer-up coordinator … hands this connection's `inbound` to a fresh pump per connection").

---

## Design

### Package & new types

Everything lands in `data/`, no new package.

| Type | File | Kind |
|---|---|---|
| `ManagedSessionPump : SessionPump` | `data/repository/SessionPump.kt` (append) | new interface — adds `start()` + `close()` to the `SessionPump` data view |
| `RelayRepositoryCoordinator` | `data/repository/RelayRepositoryCoordinator.kt` | new class — the coordinator |
| `NoiseSessionPump` | `data/network/NoiseSessionPump.kt` (modify) | now `: ManagedSessionPump` — `override` on the four members; **no behavioural change** |

Two new exported types (`ManagedSessionPump`, `RelayRepositoryCoordinator`); 1 new file; 4 production files touched total (the two above + `NoiseSessionPump.kt` + `AppModule.kt`). Within size `S`.

### `ManagedSessionPump` — the coordinator's lifecycle view of the pump (AC #1)

`SessionPump` is the *data* view (the repository reads `inbound`, calls `send`). The coordinator additionally *owns the lifecycle* (start the session drive; tear it down). Interface Segregation: the coordinator gets a `ManagedSessionPump`; it hands the same instance, upcast to `SessionPump`, to the repository.

```kotlin
// data/repository/SessionPump.kt — appended below the existing SessionPump interface
interface ManagedSessionPump : SessionPump {
    fun start()   // single-use; launches the handshake + dispatch drive (NoiseSessionPump.start)
    fun close()   // idempotent; wipes keys + tears the session/scope down (NoiseSessionPump.close)
}
```

`NoiseSessionPump` changes to `class NoiseSessionPump(…) : ManagedSessionPump`, adding `override` to `inbound`, `send`, `start`, `close`. Because `ManagedSessionPump : SessionPump`, this **satisfies AC #1** (`NoiseSessionPump` is-a `SessionPump`); the repository's `pump: SessionPump` parameter binds unchanged. No method body changes.

> Why an interface and not the concrete pump: it keeps the coordinator's lifecycle logic unit-testable with a trivial channel-backed fake (see Testing), instead of forcing every coordinator test through a full real `Noise_IK` handshake against a `TestResponder`. The fake-pump cost is ~15 lines; the real-handshake cost is the whole `NoiseSessionPumpTest` fixture.

### `RelayRepositoryCoordinator` — the connection→repository state machine (AC #2, #3)

**Constructor contract** (mirrors the supervisor/pump idiom: own scope from an injected dispatcher; factory injected for testability):

```kotlin
class RelayRepositoryCoordinator(
    private val connections: StateFlow<RelayTransport?>,           // = supervisor.currentConnection
    private val createPump: (RelayTransport) -> ManagedSessionPump, // prod: NoiseSessionPump(t, sessionFactory)
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val currentRepository: StateFlow<ConversationRepository?>       // null between connections
    fun start()   // idempotent — launches the connections collector on the coordinator scope
    fun close()   // tears down the active connection + cancels the coordinator scope
}
```

**Behaviour** (the collector body is the whole design; keep it to one synchronous `onConnection(transport: RelayTransport?)` per emission — no `suspend`, so each emission is handled atomically w.r.t. the single collector coroutine):

- **`start()`** launches `connections.collect { onConnection(it) }` on the coordinator's own `CoroutineScope(SupervisorJob() + dispatcher)`. Idempotent (guard like the supervisor's `connect()`), so a double-registration can't spawn two collectors.
- **`onConnection(transport)`**:
  1. Tear down the currently-held connection if any (see teardown below); set `currentRepository.value = null`.
  2. If `transport == null`, return (the between-connections state).
  3. Else build a fresh connection:
     - `childScope = CoroutineScope(SupervisorJob(parent = coordinator-job) + dispatcher)` — the repository's collector scope, independently cancellable, also cancelled when the coordinator closes.
     - `pump = createPump(transport).also { it.start() }`
     - `repo = RemoteConversationRepository(pump, childScope)`
     - retain `(pump, childScope)` as the active connection; `currentRepository.value = repo`.
- **Teardown of an active connection** (on the next emission, on `close()`):
  1. `childScope.cancel()` — stops the repository's single inbound collector (no leaked collector).
  2. `pump.close()` — **independently required**: the pump owns its *own* `SupervisorJob` scope (`NoiseSessionPump.kt:56`), which `childScope.cancel()` does **not** reach; `close()` is what wipes the session keys and tears the pump scope down. Order: cancel scope, then close pump (matches the ticket's teardown note).
- **`close()`** tears down the active connection (as above), sets `currentRepository.value = null`, then cancels the coordinator scope (ending the collector).

**Key-wipe invariant (security-critical — see Security review).** *Every transition that drops a pump reference must first call `pump.close()`.* There are exactly three such transitions and all three close the pump: (a) a new connection replacing an old one, (b) a `null` emission, (c) `close()`. `pump.close()` → `session.close()` wipes the transport ciphers and the device-static copy (`NoiseSessionPump.kt:265`); dropping the reference without `close()` would leave key material resident in memory until GC (which does not zero byte arrays). The AC #3 test asserts `pump.closed == true` after every teardown — a deterministic safety net under the stochastic "remember to close" rule.

**`onConnection` must stay non-suspending.** Coroutine cancellation is cooperative — it only takes effect at a suspension point. Keeping `onConnection` free of `suspend` calls (`createPump`, `start()`, scope construction, the repository constructor, and the `StateFlow` write are all non-suspending) means a single emission is handled *atomically* w.r.t. cancellation: there is no point at which a `close()` racing on another thread could cancel the collector between `pump.start()` and retaining the reference, which would otherwise leak a started-but-unclosed pump. Do not introduce a suspension inside the build/teardown body.

**The coordinator never consumes the transport's streams.** It passes the `RelayTransport` reference to `createPump` and nothing else — it must not collect `transport.inbound` or `transport.events`. Those are single-consumer (`RelayTransport.kt:26-37`); the pump is the sole `inbound` consumer and the supervisor the sole `events` consumer. A stray collection here would steal frames from the handshake and corrupt the session.

**Why `currentConnection` alone is sufficient as the teardown trigger** (no need to also observe `pump.state`): the pump's `teardown()` always calls `transport.close()` (`NoiseSessionPump.kt:266`), which makes the supervisor observe `Down` and clear `currentConnection` to `null` — so a pump that dies on its own (handshake timeout, crypto fault) funnels back through the supervisor and reaches the coordinator as a `null` emission. The transport is the single source of connection liveness.

**No state carryover on reconnect (AC #3) — and why it is a crypto invariant, not just hygiene:** each connection gets a brand-new `pump`, `childScope`, and `RemoteConversationRepository`. The old `currentRepository` value is replaced by `null` then by the new instance; nothing (projection `StateFlow`s, `lastMessages`/`messagesByConversation` maps, the `requestId` counter, in-flight `pump.inbound` frames) is shared between the old and new repository — they are distinct objects with distinct scopes. The single-use pump guarantee (`start()` asserts `started.compareAndSet`) means a spent pump *cannot* be restarted. This is load-bearing for confidentiality: a `Noise_IK` session's ephemeral keys and its AEAD nonce counter are **per-handshake**; reusing a session (or its pump) across two transports would reuse nonces under the same key, which breaks AEAD confidentiality. The coordinator therefore must build a fresh pump per live transport and must never carry a pump across a `currentConnection` transition.

```
supervisor.currentConnection : StateFlow<RelayTransport?>
        │  null → T1 → null → T2 → …
        ▼
RelayRepositoryCoordinator.onConnection (single collector, sequential)
   T1 ─▶ pump1=create(T1).start();  repo1=Remote(pump1, scope1);  currentRepository=repo1
 null ─▶ scope1.cancel(); pump1.close()  (keys wiped);            currentRepository=null
   T2 ─▶ pump2=create(T2).start();  repo2=Remote(pump2, scope2);  currentRepository=repo2
        ▼
currentRepository : StateFlow<ConversationRepository?>  ──▶  #352 stable facade (out of scope here)
```

### DI registration (AC #5)

In `AppModule.kt`, register the coordinator as a process-lifetime singleton, **eagerly started**, mirroring the `LifecycleConnectionDriver` precedent (lines 60-65). **Do not touch line 54** — `FakeConversationRepository bind ConversationRepository::class` stays the bound default; ViewModels keep resolving the fake. The coordinator publishes `currentRepository` for #352 to consume; it does not register itself as a `ConversationRepository`.

```kotlin
single(createdAtStart = true) {
    val sessionFactory = get<NoiseSessionFactory>()
    RelayRepositoryCoordinator(
        connections = get<RelayConnectionSupervisor>().currentConnection,
        createPump = { transport -> NoiseSessionPump(transport, sessionFactory) },
    ).also { it.start() }
}
```

> `get<RelayConnectionSupervisor>()` resolves the same singleton already bound to `ConnectionStateSource::class` at line 56 (the concrete type is registered; `LifecycleConnectionDriver` already resolves it this way at line 61). `currentConnection` is a stable `StateFlow` instance for the supervisor's life.

**Behavioural consequence to be aware of (intended, in-scope):** with the coordinator `createdAtStart` and the supervisor already dialing in a paired+foregrounded app (via `LifecycleConnectionDriver`), this slice causes a real `Noise_IK` handshake to run on each live connection. That is the intended Phase 4 step and is bounded: no `list_conversations`/`backfill_since` is sent until a subscriber calls the read paths (which only #352 + #350 wire up), so a live-but-unconsumed pump simply completes the handshake and idles. The `#350` flag gates what the **UI reads**, not whether the encrypted channel is established — consistent with the repo's "no central Phase-4 flag; gate consumed-live impls on paired-state" rule (paired-state gating is automatic: the supervisor only publishes a transport when paired).

---

## State + concurrency model

- **Single source of state:** `currentRepository: StateFlow<ConversationRepository?>` (backed by a private `MutableStateFlow`, `null` initial). No parallel mutable connection state outside the single `onConnection` collector.
- **Single collector, sequential:** `connections.collect` runs on one coroutine on the coordinator scope; `onConnection` is non-suspending, so build/teardown of the `(pump, childScope)` field never races. No lock needed.
- **Scopes:**
  - Coordinator scope: `CoroutineScope(SupervisorJob() + dispatcher)` — owns the collector. `dispatcher` injected (`Dispatchers.Default` in prod; `StandardTestDispatcher(testScheduler)` in tests).
  - Per-connection `childScope`: child of the coordinator job, `+ dispatcher`. Owns the repository's inbound collector. Cancelled on teardown; also cancelled transitively when the coordinator scope is cancelled.
  - Pump scope: owned by `NoiseSessionPump` itself, **not** a child of `childScope`; reached only via `pump.close()`.
- **StateFlow distinct-emission:** `currentConnection` re-emits only on a changed value (`RelayTransport` has identity `equals`, and the supervisor uses a fresh instance per dial with `null` between), so `onConnection` is invoked once per real transition. The teardown-old-before-build-new logic is still defensive against any back-to-back non-null delivery.
- **Hot vs cold:** the coordinator transforms one hot `StateFlow` into another hot `StateFlow`; the repository's read flows stay cold (per-subscription request). No new cold/hot decisions here.

---

## Error handling

| Failure mode | Behaviour |
|---|---|
| Pump handshake fails (timeout / crypto fault / `NoiseSessionException`) | Pump self-tears-down → `transport.close()` → supervisor `Down` → `currentConnection` → `null` → coordinator tears down the connection. `currentRepository` returns to `null`. Supervisor's backoff governs the re-dial cadence; the coordinator adds no retry of its own. |
| `createPump` / repository construction throws | Should not happen for valid wiring (constructors are pure). If it did, the exception would propagate on the collector coroutine; the coordinator scope is a `SupervisorJob`, but the collector is a single child, so a throw would cancel the collector. **Mitigation:** `createPump` and the `RemoteConversationRepository` constructor must not throw (they don't today — construction only stores fields and launches a collector). No try/catch is added; do not invent a defense for an unobserved failure. |
| Connection drops mid-read | `childScope.cancel()` stops the repository collector; any in-flight cold `observeX` flow on a consumer's side completes/cancels naturally when its upstream scope dies. Consumers re-subscribe against the next connection's repository (the #352 facade's job). |
| Coordinator emits **no logs** | Consistent with the pump (`NoiseSessionPump.kt:47`) and supervisor (`RelayConnectionSupervisor.kt:60-62`) "no logs" posture: never log the transport, `PairedServer`, tokens, frame bytes, or the repository contents. The only outward signal is `currentRepository`. |

---

## Testing strategy

Unit only — JVM `./gradlew test`, JUnit4 + `runTest`, hand fakes (no MockK), matching `RemoteConversationRepositoryTest` / `RelayConnectionSupervisorTest`. New file `RelayRepositoryCoordinatorTest.kt` under `app/src/test/java/de/pyryco/mobile/data/repository/`. **Instrumented tests: none** (no Android APIs; the coordinator is pure data-layer).

**Test fakes** (lift from the existing tests):
- `FakeManagedPump : ManagedSessionPump` — the `FakeSessionPump` from `RemoteConversationRepositoryTest:672` (UNLIMITED-channel `inbound`, recorded `sent`, returns `true`) plus `var started`/`var closed` flags set by `start()`/`close()` and a `push(envelope)` helper.
- `FakeRelayTransport` — a trivial non-null token; the `createPump` lambda ignores it (the fake pump needs no real transport). Reuse the shape from `RelayConnectionSupervisorTest:342` or a one-liner stub.
- The coordinator's `createPump` in tests returns a *new* `FakeManagedPump` per call and records each, so reconnect tests can assert distinct instances.
- Real `RemoteConversationRepository` (not faked) so AC #4 exercises the genuine read paths over the fake pump.
- `StandardTestDispatcher(testScheduler)` passed as `dispatcher`; drive with `runCurrent()`; **call `coordinator.close()` at the end of every test** (the perpetual `connections.collect` otherwise hangs `runTest`).

**Scenarios** (each a `@Test`):

- **AC #1 — contract:** a constructed `NoiseSessionPump` (or the fake) `is SessionPump` and `is ManagedSessionPump`. (A one-line `assertTrue` — can also live in `NoiseSessionPumpTest`.)
- **AC #2 — build on live connection:** push `T1` into the `connections` `MutableStateFlow`; after `runCurrent()`, `currentRepository.value != null` and the first `FakeManagedPump.started == true`.
- **AC #2 — between connections is null:** initial `currentRepository.value == null`; after a `null` emission following a connection, back to `null`.
- **AC #4 — list path over the live pump:** with `T1` live, `backgroundScope.launch { repo.observeConversations(All).collect{…} }`; assert the fake pump recorded a `list_conversations` send; `pump.push(conversationsEnvelope(…))`; assert the projected, sorted list surfaces. (Mirrors `RemoteConversationRepositoryTest`, but the repo is the *coordinator-built* one.)
- **AC #4 — thread path over the live pump:** `observeMessages("c1")` ⇒ `backfill_since` recorded; `pump.push(messageChunkEnvelope(…))` then a live `messageEnvelope(…)` ⇒ ordered `ThreadItem.MessageItem` thread.
- **AC #3 — teardown on drop:** with `T1` live and a repo published, push `null`; assert `currentRepository.value == null`, the pump's `closed == true`, and the child scope is cancelled — verified by: a frame pushed to the *old* pump's `inbound` after teardown surfaces nowhere (the collector is gone) and no coroutine leaks (`runTest` completes after `close()`).
- **AC #3 — reconnect builds fresh, no carryover:** `T1` live → push a `conversations` snapshot → observe list1 (e.g. `[c1]`); push `null`; push `T2` → the new `FakeManagedPump` is a *distinct instance* and `started`, the prior pump is `closed`; the new `currentRepository` is a *different* object; observing the new repo's `observeConversations` before any push yields an empty/loading projection (no `c1` leaked from connection 1).
- **AC #5 — fake binding untouched:** assert (Koin or by inspection) that resolving `ConversationRepository` still yields `FakeConversationRepository`. *If* a lightweight Koin graph check is awkward, this AC is satisfied by the unchanged `AppModule.kt:54` line plus a code-review check; do not over-build a DI harness for it.

---

## Open questions

- **Coordinator class name.** `RelayRepositoryCoordinator` is the proposed name (parallels `RelayConnectionSupervisor`). The developer may pick a clearer name (e.g. `ConnectionScopedRepositoryProvider`) if it reads better at the #352 consumption site — keep `currentRepository` as the exposed member name regardless.
- **`ManagedSessionPump` file placement.** Spec appends it to `SessionPump.kt` (keeps production-file count at 4 and ktlint happy — `SessionPump` matches the filename, the extra interface is permitted, as `NoiseSessionPump.kt` already pairs a class with `PumpState`). A separate `ManagedSessionPump.kt` is also fine but would make 5 production files — avoid unless ktlint objects.
- **AC #5 Koin assertion depth.** Left to the developer: a full `koinApplication { modules(appModule) }` check vs. relying on the untouched binding line. No existing Koin-module unit test in the repo to mirror — don't invent a heavy one.

---

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The coordinator sits *above* the authenticated Noise channel — it only moves object references (`RelayTransport` → `ManagedSessionPump` → `RemoteConversationRepository`) and never touches wire bytes. The trust boundaries are pre-existing and unchanged: the pump emits on `inbound` only after a successful decrypt+parse (`NoiseSessionPump.kt:184-186`), and the repository decodes each payload in a `try/catch` that drops malformed input (`RemoteConversationRepository.kt:103-108`). The design explicitly forbids the coordinator from collecting `transport.inbound`/`events` (single-consumer streams owned by the pump/supervisor) — see Design.
- **[Tokens / secrets / keys]** No MUST FIX. The coordinator holds no key material; all keys live inside the pump/`NoiseIkSession`. The load-bearing property — *every pump-reference drop calls `pump.close()`* to wipe the session ciphers + device-static copy — is stated as a three-transition invariant in Design and enforced by a deterministic AC #3 test (`pump.closed == true` after teardown). Stochastic rule + deterministic safety net, different fabric.
- **[File / storage]** N/A — the coordinator performs no filesystem or storage I/O. Keystore-wrapped device-key and paired-server reads remain owned by the existing `DeviceStaticKeyStore` / `KeystorePairedServerStore`.
- **[Inter-process / Android surface]** N/A — no Activity/Service/Receiver/ContentProvider/deep-link/PendingIntent/WebView added. The coordinator is a pure data-layer singleton; the `createdAtStart` registration runs on the main thread at `Application.onCreate`, mirroring the existing `LifecycleConnectionDriver`. No exported component.
- **[Cryptographic primitives]** No MUST FIX. No crypto in the coordinator. The relevant invariant — **a fresh single-use pump per live connection** — prevents Noise ephemeral / AEAD-nonce reuse across connections (a confidentiality break), and is guaranteed by `createPump` per non-null emission plus the pump's `start()` single-use assertion. Documented as a crypto invariant in Design (not merely "no state carryover").
- **[Network & I/O]** No findings. The coordinator opens no sockets and sets no timeouts/TLS — all owned by #306, unchanged. Reconnect cadence (build pump → handshake → on failure, transport closes → supervisor `Down` → re-dial) is rate-limited by the supervisor's existing capped-exponential backoff (1/2/4/8/16/30s); the coordinator adds **no** retry of its own, so a failing relay cannot drive a tight pump-rebuild loop.
- **[Error messages / logs / telemetry]** No findings. The coordinator emits no logs and no telemetry, consistent with the pump (`NoiseSessionPump.kt:47`) and supervisor (`RelayConnectionSupervisor.kt:60-62`) "no logs" posture. Its only outward signal is the `currentRepository` reference; it never logs the transport, `PairedServer`, tokens, frame bytes, or repository contents.
- **[Concurrency]** No MUST FIX. Scope ownership is explicit (coordinator scope / per-connection child scope / pump-owned scope) with a documented cancellation path for each. Shared state (`currentRepository`, the active-connection field) is written only by the single non-suspending `onConnection` collector — no check-then-mutate race, no lock needed. The one theoretical hazard (a `close()` racing the collector mid-build, leaking a started-but-unclosed pump) is closed by the **`onConnection`-must-stay-non-suspending** invariant: cooperative cancellation cannot interrupt a non-suspending body, so each emission is handled atomically.
- **[Threat-model alignment]** No findings. The three mobile-relevant `protocol-mobile.md` § Security-model threats this slice can touch are addressed: session-key hygiene (wipe on every teardown), no ephemeral/nonce reuse (fresh pump per connection), and staying above the content-blind relay's authenticated boundary (no ciphertext handling). Cross-connection data leakage is precluded by distinct per-connection repositories with no shared projection state. UI-surface mobile threats (screenshot leakage, accessibility eavesdropping, overlay, deep-link, keyboard logging) do not apply — the coordinator has no UI or input. **Out of scope, named:** live end-to-end verification (server-side gaps), the no-connection UI rendering (#352), the flag-gated Fake↔Remote binding (#350), and the v2 write path (#346/#347/#348).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-02
