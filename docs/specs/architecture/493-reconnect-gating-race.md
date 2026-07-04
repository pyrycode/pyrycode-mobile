# #493 — Fix the reconnect gating race that can re-introduce #421 (list never loads)

**Size:** S (single production file + its test; internal restructuring, no new public types, no external edit fan-out). **Security-sensitive.**

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:104-215` — the four connection-state holders (`mutableRepository` :104, `activePumpFlow` :110, `activeRemoteRepo` :151, the plain `active: Connection?` var :221) and every flow derived off them (`pyrycodeStatus` :116, **`currentRepository` combine gate :138-144** — the bug, `liveSessionEvents` :163, `modalEvents` :175, `currentModal` :205, `connectionStatus` :213). This is the surface you consolidate.
- `RelayRepositoryCoordinator.kt:121-137` — the `#421` KDoc rationale on the Open-gate; your revision must preserve this reasoning (the gate stays, its derivation changes).
- `RelayRepositoryCoordinator.kt:239-265` — `onConnection`: the non-suspending build path that writes the holders in lock-step. The **:233 KDoc cancellation-atomicity invariant** ("Do not introduce a suspension here") is load-bearing.
- `RelayRepositoryCoordinator.kt:299-307` — `teardownActive`: nulls the holders then cancels scope + closes pump (the key-wipe, AC #4).
- `RelayRepositoryCoordinator.kt:329-359` — `answerModal`/`cancelModal`/`interrupt` (read `activeRemoteRepo.value`) and the private `Connection(pump, scope)` class you extend.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:46-63` — the #352 facade: `switchToLive` does `currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }`. The **first non-null `currentRepository` emission** makes it subscribe to `repo.observeConversations`, which fires the one-shot `list_conversations`. This is the victim of the transient exposure. No edit here — read it to understand why a transient matters.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:113-130` — the live wiring: `StableConversationRepository(coordinator.currentRepository)`, plus `connectionStatus`/`liveSessionEvents`/`currentModal` consumers. Confirms the public surface you must keep signature-stable. No edit here.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:833-851` — `newEnv`/`Env`: `connections: MutableStateFlow<RelayTransport?>`, `pumps: MutableList<FakeManagedPump>`, `StandardTestDispatcher`, `coordinator.start()`.
- `RelayRepositoryCoordinatorTest.kt:955-1007` — `FakeManagedPump`: starts at `PumpState.Handshaking`, `open()` drives to `Open`, `close()` sets the `closed` flag **but leaves `state` at its last value** (a closed pump's `state` stays `Open` — this is exactly the stale value the bug latches onto).
- `RelayRepositoryCoordinatorTest.kt:55-73` — the existing AC #2 test (`liveConnection_startsPumpAndPublishesRepository`): the shape your regression test mirrors, plus the Open-gate assertion pattern.
- `RelayRepositoryCoordinatorTest.kt:210-259` — the existing `reconnect_...` test: drives A→null→B **with a `runCurrent()` between each and opens pumpB**. Your regression test differs: it reconnects A→B and leaves pumpB at `Handshaking`.
- `CLAUDE.md` § Conventions — "Test-first. Red → green → refactor. Failing test first, implementation after." The regression test (AC #1) must be confirmed **red against current `main`** before the fix lands.

## Context

`currentRepository` (`:138-144`) gates repo exposure on the pump reaching `Open` — the #421 fix that stopped the conversation list from spinning forever. But it derives the gate by `combine`-ing **two independently-mutated `StateFlow`s**:

```kotlin
combine(
    mutableRepository,                                          // direct StateFlow
    activePumpFlow.flatMapLatest { pump -> pump?.state ?: flowOf(null) },  // one extra hop
) { repo, pumpState -> if (pumpState is PumpState.Open) repo else null }
```

On a **direct A→B reconnect** (`connections` emits transport B while A is live), `onConnection` runs synchronously: it nulls the holders (teardown), then sets `activePumpFlow = pumpB` (Handshaking) and `mutableRepository = repoB`. `combine` collects its two inputs on separate coroutines. Input 0 (`mutableRepository`, direct) delivers `repoB` in one hop. Input 1 (`activePumpFlow.flatMapLatest`) must cancel pumpA's `state` collector and launch pumpB's — an extra coroutine hop — during which `combine`'s cached input-1 value is still **pumpA's `Open`** (and `FakeManagedPump.close()` leaves `pumpA.state` at `Open`, so nothing else corrects it). In that window `combine` emits `(repoB, Open) → repoB`: the new repo is exposed while **its own** pump is still `Handshaking`.

The facade (`StableConversationRepository.switchToLive`, `:59`) subscribes on the first non-null repo and fires a one-shot `list_conversations` via `pump.send`, which returns `false` pre-`Open` and is **dropped** — never re-issued. That is #421's "list never loads" failure, re-introduced through the two-source race. Manifests only with `USE_RELAY_REPOSITORY` on.

`#492` (modal-hoist) has shipped and merged into `main` — the file already carries the `currentModal` fold; there is no remaining same-file blocker. Anchors above are verified against current `main`, post-#492.

## Design

**One consolidated connection source.** Replace the four separate holders — `mutableRepository`, `activePumpFlow`, `activeRemoteRepo`, and the plain `active: Connection?` var — with a **single** `private val activeConnection = MutableStateFlow<Connection?>(null)`. Extend the private `Connection` class to carry the concrete repo alongside the pump and scope:

```kotlin
private class Connection(
    val pump: ManagedSessionPump,
    val scope: CoroutineScope,
    val repo: RemoteConversationRepository,   // NEW — concrete type; liveSessionEvents/modalEvents/answerModal need it
)
```

Now repo and pump-state are two projections of the **same** switched value. Derive `currentRepository` with a single `flatMapLatest` over `activeConnection`, mapping the connection's own `pump.state` to the gated repo — closing the cross-`StateFlow` window structurally:

```kotlin
val currentRepository: StateFlow<ConversationRepository?> =
    activeConnection
        .flatMapLatest { conn ->
            conn?.pump?.state?.map { if (it is PumpState.Open) conn.repo else null } ?: flowOf(null)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)
```

When `flatMapLatest` switches to connection B, the inner flow is `connB.pump.state.map { … }`, whose **first** emission is `connB.pump.state.value` (`Handshaking`) → `null`. There is no code path that pairs `connB.repo` with anything but `connB`'s own pump state. (Kotlin typing note: the inner `conn.repo` is `RemoteConversationRepository`; `if (Open) conn.repo else null` widens to `ConversationRepository?` via `StateFlow`/`Flow` covariance — declared type stays `StateFlow<ConversationRepository?>`.)

**The other derived seams switch off the same source** — no parallel holders reintroduced:

| Member | Derivation (unchanged signature) |
|---|---|
| `pyrycodeStatus` (private) | `activeConnection.flatMapLatest { it?.pump?.state ?: flowOf(null) }.map { it.toPyrycodeLinkStatus() }` |
| `liveSessionEvents` | `activeConnection.flatMapLatest { it?.repo?.liveSessionEvents ?: emptyFlow() }` |
| `modalEvents` (private) | `activeConnection.flatMapLatest { it?.repo?.modalEvents ?: emptyFlow() }` |
| `currentModal` | **unchanged** — still `modalEvents.scan(…).stateIn(Eagerly)` |
| `connectionStatus` | **unchanged** — still `combine(relayStatus, pyrycodeStatus){…}.stateIn(Eagerly)` |

**Outbound passthroughs** read the consolidated source:

```kotlin
val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
```

for `answerModal`/`cancelModal`/`interrupt` — same null-guard-only posture, same never-log contract (the concrete `sendAndAwaitReply` → `pump.send` still throws `IllegalStateException` pre-`Open`, so no redundant Open-gate is needed here).

**Publish and teardown stay on the single non-suspending path.** `onConnection` builds pump → repo, then publishes **one** object; `teardownActive` reads-then-nulls then tears down. Contract sketches (bodies stay non-suspending — only `MutableStateFlow.value` writes, object construction, `scope.cancel()`, `pump.close()`; **do not introduce a suspension**, per the :233 invariant):

- `onConnection(transport)`: `teardownActive()`; return if `null`; build `childScope`, `pump` (`.start()`), `repo` (`RemoteConversationRepository(pump, childScope, deviceName, negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() }, replayCursor)`); `activeConnection.value = Connection(pump, childScope, repo)`; `childScope.launch { reregisterPushTokenOnOpen(pump, repo) }`.
- `teardownActive()`: `val current = activeConnection.value ?: return`; `activeConnection.value = null`; `current.scope.cancel()`; `current.pump.close()`. (Order preserved: null the source first so `currentRepository` re-derives `null`, then cancel scope, then close pump — the key-wipe.)

**Property declaration order.** `activeConnection` must be declared **above** every flow that derives from it (`currentRepository`, `pyrycodeStatus`, `liveSessionEvents`, `modalEvents`, `currentModal`, `connectionStatus`), because those are property initializers and Kotlin initializes top-to-bottom. Put `activeConnection` where `mutableRepository` is today (`:104`). Delete `activePumpFlow`, `activeRemoteRepo`, and the `active` var.

**KDoc.** Rewrite the `:121-137` `#421` block to describe the single-source derivation (the gate is preserved; only its plumbing changes — call out that repo and pump-state now come from one switched source, closing the two-`StateFlow` window). Preserve the class-level key-wipe and single-use-pump KDocs (`:35-79`); the three drop-transitions are unchanged.

No import changes are required (`flatMapLatest`, `map`, `flowOf`, `emptyFlow`, `combine`, `scan`, `stateIn`, `MutableStateFlow` are all already imported).

## State + concurrency model

- **Single source of truth:** `activeConnection: MutableStateFlow<Connection?>` is the only connection-state holder; every consumer derives from it. This is a strict reduction from four holders to one — the whole point (no parallel mutable state).
- **Ownership / lifecycle:** `activeConnection` is written **only** on the single non-suspending `onConnection`/`teardownActive` path (plus idempotent `close`), preserving the existing "written only by the single collector" invariant (`:219-221`). Derived flows are `stateIn(scope, Eagerly)` (for `currentRepository`; `connectionStatus`, `currentModal` keep `Eagerly`) or cold (`liveSessionEvents`, `modalEvents`), collected on the process-scoped `scope`. `close()` cancels `scope`, ending all of them.
- **Dispatcher:** `Dispatchers.Default` (unchanged); tests inject `StandardTestDispatcher`.
- **Cancellation atomicity (the invariant this ticket must not break):** `onConnection` remains free of suspension points, so a racing `close()` cannot cancel the collector mid-build and leak a started-but-unclosed pump. `MutableStateFlow.value` reads/writes are non-suspending; the consolidation adds none.
- **Race closed:** because `currentRepository` now derives repo and pump-state from one `flatMapLatest` over `activeConnection`, there is no interleaving in which the new connection's repo is paired with the previous connection's pump-state. `flatMapLatest`'s cancel-old-inner / subscribe-new-inner is atomic w.r.t. which connection's `repo` is in scope: the inner lambda closes over `conn`, so the mapped `conn.repo` and `conn.pump.state` are always the same connection's.

## Error handling

No new failure modes; the change is a derivation restructuring.

- **Between connections / pre-`Open`:** `currentRepository` → `null` (unchanged contract). Facade reads yield the empty projection; mutations throw `IllegalStateException(NOT_CONNECTED)`.
- **Pump drops to `Closed` while still the active connection:** the inner `map` yields `null` (`Closed !is Open`), same as the old `combine`. Facade drops the projection.
- **`answerModal`/`cancelModal`/`interrupt` with no active connection:** `IllegalStateException("no active connection")` (unchanged). Pre-`Open` with a connection: the concrete repo's `sendAndAwaitReply` throws (the #438 precedent), unchanged.
- **No-log contract preserved:** the coordinator still emits **no** logs on any path (modal fields, capabilities, connIds may name sensitive commands/paths). The fix adds none.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RelayRepositoryCoordinatorTest"`; the aggregate `test` task is fine too). Hand fakes, no MockK, `StandardTestDispatcher` driven with `runCurrent()` — mirror the existing file. No instrumented/Compose tests (pure coordinator logic).

**AC #1 — regression test, must be RED against current `main` first.** New test, e.g. `reconnect_directAtoB_neverExposesRepoWhileNewPumpHandshaking`:

- Attach a collector that records **every** emission before the reconnect: `backgroundScope.launch { coordinator.currentRepository.collect { emissions += it } }`.
- Drive connection A to `Open` (`connections.value = StubRelayTransport(); runCurrent(); pumps[0].open(); runCurrent()`); confirm `emissions.last()` is `repoA` (non-null).
- Reconnect **directly** A→B: `connections.value = StubRelayTransport(); runCurrent()`. **Do not** interpose a `null` emission with its own `runCurrent()`, and **do not** open `pumps[1]` — leave it at `Handshaking`.
- Assert: the only non-null repo ever observed is A's; B's repo (`pumps[1]`'s connection) is never emitted while `pumps[1].state` is `Handshaking`. Concretely — every non-null entry in `emissions` is identity-equal to `repoA`, and the settled `currentRepository.value` is `null`.
- **Test-first discipline:** run this against current `main` and confirm it is red before touching production code; run against the fix and confirm green.

**Determinism note (developer, read before writing AC #1).** The distinguishing behavior is a *transient* emission of B's repo, not the settled `.value` (which is `null` on both current and fixed code, since a full `runCurrent()` drains to `(repoB, Handshaking) → null`). The transient is caught by **collecting** `currentRepository`, not by reading `.value`. Under `StandardTestDispatcher` the schedule is deterministic; `combine` collects input 0 (`mutableRepository`) before input 1 (`flatMapLatest`), so the direct A→B path is expected to surface `repoB` before the correcting `null`. **If** the transient proves non-reproducible under the test dispatcher (collector green on current `main`) — do **not** weaken the assertion to make it pass; that would ship an assertion that proves nothing. Escalate to the operator with the empirical finding (see Open Questions). The fix's correctness (AC #2/#3/#4) does not depend on AC #1 being red, but AC #1 is the contracted proof and must not be faked green. Reference lesson [[remote-repo-test-runcurrent-not-advanceuntilidle]] — use `runCurrent()`, never `advanceUntilIdle()`, on this harness.

**AC #2 — new repo appears only once its own pump reaches `Open`.** Extend the AC #1 scenario: after asserting B's repo is not exposed while `pumps[1]` is `Handshaking`, call `pumps[1].open(); runCurrent()` and assert `currentRepository.value` is now B's repo (identity-distinct from `repoA`), and that B's repo appears in `emissions` only from this point on.

**AC #3 — reconnect → list loads (facade one-shot succeeds).** Mirror the existing `reconnect_buildsFreshPumpAndRepository_noCarryover` shape (A→null→B, `open()` pumpB), then drive the facade's read path over connection B: subscribe `repo2.observeConversations(ConversationFilter.All)`, `runCurrent()`, assert a single `list_conversations` frame reached `pumps[1].sent` (the send succeeded post-`Open`, not dropped), then `push` a `conversationsEnvelope` and assert the projected list. Reuse the `conversationsEnvelope`/assertion helpers already in the file.

**AC #4 — key-wipe / single-use-pump invariant unbroken.** The existing coordinator tests already cover this (`reconnect_buildsFreshPumpAndRepository_noCarryover` asserts `pump1.closed`, `pump2.started`, distinct pumps/repos; `close_tearsDownActiveConnection` asserts `pump.closed`; the push-token tests assert once-per-connection). **These must continue to pass unmodified** — they exercise the three drop-transitions through `teardownActive`. Do not rewrite them; if any breaks, the consolidation changed teardown semantics and is wrong. The rest of the file's tests (`liveSessionEvents`, `currentModal`, `connectionStatus`, `answerModal`/`cancelModal`/`interrupt`) likewise assert unchanged public contracts and must stay green.

## Open questions

- **AC #1 transient observability under `StandardTestDispatcher`.** The regression assertion depends on the test collector observing the buggy transient (`repoB` before the correcting `null`). This is expected to reproduce (the cross-repo review that filed this verified the harness supports the repro, and it is a real observed failure), but StateFlow conflation is schedule-sensitive. If the developer cannot get a deterministic red on current `main`, the resolution is **not** to relax the assertion — it is to escalate to the operator with the finding (the fix still stands on AC #2/#3/#4). Flagged here so the developer treats a green-on-current AC #1 as a stop-and-escalate signal, not a pass.
- **`Connection` field naming.** Spec names the new field `repo: RemoteConversationRepository`. If a clearer name fits the file's idiom, the developer may rename — no external contract depends on it (`Connection` is private).

## Security review

**Verdict:** PASS

**Findings:**

- **[Concurrency] No findings — this is the category the ticket lives in.** The consolidation *strengthens* the concurrency posture: it collapses four independently-mutated holders (`mutableRepository`, `activePumpFlow`, `activeRemoteRepo`, `active`) into one `StateFlow<Connection?>`, eliminating the cross-`StateFlow` `combine` window that let the new repo pair with the old pump's cached `Open`. The non-suspending `onConnection`/`teardownActive` critical section (the cancellation-atomicity invariant, `:233`) is explicitly preserved — the design adds only non-suspending `MutableStateFlow.value` writes and object construction, so no racing `close()` can cancel mid-build and leak a started-but-unclosed pump. Hot/cold flow choices are unchanged (`currentRepository`/`connectionStatus`/`currentModal` stay `stateIn(Eagerly)`; `liveSessionEvents`/`modalEvents` stay cold). No new TOCTOU: `activeConnection` is single-writer on the collector path.
- **[Cryptographic primitives / key-wipe] No findings — the AC #4 invariant is preserved deterministically.** Every pump-drop transition (new-replaces-old, `null` emission, `close`) still routes through `teardownActive → current.pump.close()`, the load-bearing key-wipe (wipes the session transport ciphers + device-static copy). The single-use-pump-per-connection rule (fresh pump/scope/repo per transport; nothing shared across connections — required because `Noise_IK` ephemeral keys + AEAD nonce counters are per-handshake) is unchanged: `onConnection` still builds a brand-new `Connection`. The regression scenario cannot cause a pump to be reused or a key-wipe to be skipped — `teardownActive` reads-then-nulls-then-closes on the same non-suspending path.
- **[Trust boundaries] No findings — no boundary added or moved.** The change reshapes internal derivation only; it introduces no new parse of untrusted data. `pump.state`/`repo` are internal, already-trusted projections. Untrusted-wire parsing lives downstream in `RemoteConversationRepository`/`MobileWireCodec`, untouched here.
- **[Error messages, logs, telemetry] No findings — no-log contract preserved.** The coordinator emits no logs on any path; the fix adds none. Modal fields, capability sets, `connId`s (which may name sensitive commands/paths) still never reach a log sink. `answerModal`/`cancelModal`/`interrupt` retain the never-log contract.
- **[Tokens, secrets, credentials] No findings — push-token path untouched.** `reregisterPushTokenOnOpen` (`:276-291`) is unchanged: still fires once per connection off the child scope after `Open`, still swallows non-cancellation exceptions without logging the token, still propagates `CancellationException`. The `Connection` gaining a `repo` field does not change token handling.
- **[File/storage], [Inter-process/Android], [Network & I/O], [Threat model]** — Not applicable. This ticket adds no filesystem path, no exported component/intent/deep-link, no WebSocket/OkHttp configuration, and no new wire-facing surface. The WebSocket frame-size / timeout / TLS posture is owned by `data/network/` and is out of scope; no threat in the mobile wire-protocol security model is newly touched.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
