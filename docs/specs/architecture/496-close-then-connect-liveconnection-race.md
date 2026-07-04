# #496 — Close-then-connect race can clear the new loop's live connection

**Size:** XS (one production line changed in a single file; the bulk of the diff is one deterministic interleaving test + its fake-transport scaffolding). **Security-sensitive** (label-gated self-review below). No new public types. No external edit fan-out.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:143-179` — `runLoop`'s per-dial `try/finally`. **The fix site is the `finally` at `:168-174`**: `:172 liveConnection.value = null` is the unconditional clear to guard; `:173 transport.close()` stays unconditional. Read `:147-156` (the `Up` handler that publishes the transport at `:149`) and `:81-90` (`liveConnection`/`currentConnection`) for the field being raced.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:100-115` — `connect()`/`close()`. Both `@Synchronized` (cannot interleave with each other); the race is the loop's async `finally` running **outside** that lock. `close()`'s own synchronous clear at `:112-113` is correct as-is and must not be scoped.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:28-38, 525-638` — the test posture (`runTest` virtual clock, `StandardTestDispatcher(testScheduler)`, `runCurrent()` to settle event-driven transitions without advancing time) and the existing fakes: `FakeRelayTransport` (Channel-backed `events`, `closeCalls`), `FakeRelayTransportFactory` (records `created`), `StubPairedServerStore`. Your new test reuses this scaffolding and adds a **gated** transport for the old loop (see Testing).
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:342-365` — the existing `close_tearsDownTransportAndStopsLoop` (AC #4 coverage) and `:42-85 singleDrop_…` (normal-Down clear). Both must stay green after the fix — read them to confirm the guard preserves their `assertNull(currentConnection.value)` expectations.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:35-59, 81-88, 242-268` — the sole live consumer of `currentConnection` (wired in `AppModule.kt:94-101`). Read-only here; read it to understand the downstream impact (a wrongly-null `currentConnection` denies the coordinator the transport, so no fresh pump is built — the connection is silently dead). No edit.
- `docs/specs/architecture/493-reconnect-gating-race.md` — the sibling "async clear that outlives the state it was scoped to" fix; same race *class*, different layer. Read its Security review section for the format this spec mirrors.

## Context

`RelayConnectionSupervisor.close()` is `@Synchronized`: it cancels the supervision loop, then **synchronously** clears `liveConnection` (`:112-113`). Coroutine cancellation is cooperative, so the cancelled loop's `finally` — which *also* nulls `liveConnection` (`:172`) — runs later, at the loop's next cancellation checkpoint, and **outside** the `@Synchronized` lock.

If a new `connect()` has already started a fresh loop that reached `Up` and published its transport to `liveConnection` (`:149`) before the old loop's `finally` runs, that stale `finally` unconditionally nulls `liveConnection`, wiping the **new** loop's live connection. `currentConnection` then reads `null` even though the new socket is `Up`, so `RelayRepositoryCoordinator` never sees the transport and never builds a pump for it — the connection is silently dead until the next reconnect. Manifests only with `USE_RELAY_REPOSITORY` on; filed from the Cross-Repo Code Review 2026-07-03.

## Design

Single change: make the `finally`'s clear a **compare-and-clear on identity** — it may null `liveConnection` only if it still holds *this loop's own* transport. Everything else in the `finally` is unchanged; `transport.close()` (`:173`) stays unconditional so every loop always releases its own socket.

**Contract of the fixed `finally` (`:168-174`):**

- `stabilityTimer?.cancel()` — unchanged.
- **compare-and-clear:** clear `liveConnection` iff its current value is *referentially* this loop's `transport`; otherwise leave it untouched. Recommended spelling — atomic, and a literal reading of AC #2 ("compare-and-clear on identity"):

  ```kotlin
  liveConnection.update { current -> if (current === transport) null else current }
  ```
  (`import kotlinx.coroutines.flow.update`.) The production scope runs on `Dispatchers.Default` (multi-threaded), so express the check-then-act atomically rather than as a non-atomic `if (liveConnection.value === transport) liveConnection.value = null`. The plain `if`-guard is *functionally* safe here too — `close()` pre-nulls `liveConnection` before the new loop starts, and a cancelled loop's transport is never re-published, so no concurrent writer can restore `transport` after the read — but `update {}` is the same one-liner without the reviewer having to reconstruct that argument.
- `transport.close()` — unchanged, unconditional (AC #3).

**Why this is behavior-preserving on every other path** (no new test needed for these; existing tests cover them):

- *Normal socket `Down` → redial* (`singleDrop_…`): the loop's own `transport` is still what `liveConnection` holds (nobody else wrote it), so `=== transport` is true → cleared to `null`, exactly as today. `currentConnection` still drops to `null` before the redial.
- *Normal `close()`* (AC #4, `close_tearsDownTransportAndStopsLoop`): `close()` synchronously nulls `liveConnection` first; the loop's `finally` then reads `null` (not its transport) → skips → stays `null`, and `transport.close()` still releases the socket. Net result identical to today.
- *Mid-dial cancel before `Up`*: `liveConnection` was never set to this transport (it's `null` or another loop's), so `=== transport` is false → skip. Setting `null = null` was a no-op before, so no change.

No new types, no signature changes, no consumer edits. `liveConnection` is `private`; `currentConnection` is unchanged.

## State + concurrency model

- No new coroutine, `StateFlow`, or dispatcher. The change is one write inside an existing `finally` on the existing supervision-loop coroutine (`scope = SupervisorJob() + Dispatchers.Default`).
- Writers to `liveConnection`: (a) `close()` under `@Synchronized`; (b) a loop's `Up` handler; (c) a loop's `finally`. The race the fix closes is (c-of-old-loop) vs (b-of-new-loop). After the fix, (c) is a compare-and-clear keyed on the loop's own `transport`, so it can only ever clear the value it itself published — never a value published by a different loop.
- `close()`'s synchronous clear (`:112-113`) is intentionally left unscoped: a second `close()` legitimately clears whatever is current.

## Error handling

No error-handling surface changes. The `finally` still runs on both the normal-`Down` completion and on cancellation, and still unconditionally closes the socket — socket release is never scoped away (AC #3). The no-log contract is preserved: the guard reads/writes an in-memory reference and logs nothing.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.RelayConnectionSupervisorTest"`); no device. Add **one** regression test for AC #5, plus a small gated-transport fake. Follow `CLAUDE.md` § Conventions — write it **red against current `main` first** (it must fail on the un-guarded `finally`), then apply the fix to green.

**Critical — avoid the false-green trap.** A naive interleaving (`close()`, then `connect()`, then a single `runCurrent()`, then drive the new loop `Up`) does **not** reproduce the bug: `StandardTestDispatcher` runs queued tasks FIFO, and `close()` enqueues the old loop's cancellation-resume *before* `connect()` enqueues the new loop — so the old loop's `finally` runs while `liveConnection` is still `null` (a harmless `null → null` no-op) *before* the new loop ever publishes. Such a test passes on the **buggy** code too. The test must genuinely **hold the old loop's `finally` until after the new loop has published**, then release it.

**Technique — a transport whose cancellation is held in a `NonCancellable` cleanup gate.** Give the *old* loop's dial a transport whose `events` flow, on cancellation, suspends in its own `finally` until a test-controlled gate releases. Because the supervisor's `transport.events.collect { … }` cannot return until that flow completes, the supervisor's `finally` (the fix site) is deferred until the test opens the gate — letting the new loop publish in between. Minimal fake (nested private class in the test file; ≤ ~12 lines):

```kotlin
private class GatedRelayTransport(private val release: CompletableDeferred<Unit>) : RelayTransport {
    override val inbound = emptyFlow<InnerFrameV2>()
    override val events: Flow<TransportEvent> = flow {
        emit(TransportEvent.Up)                       // publishes to liveConnection
        try { awaitCancellation() }                   // park until close() cancels the loop
        finally { withContext(NonCancellable) { release.await() } } // hold the supervisor's finally
    }
    var closeCalls = 0; private set
    override fun connect() {}
    override fun send(frame: InnerFrameV2) = true
    override fun close() { closeCalls++ }
}
```

Provide a factory that serves the gated transport for dial 0 and a plain `FakeRelayTransport` for dial 1 (either a tiny `ScriptedRelayTransportFactory(vararg transports: RelayTransport)` returning them in order, or extend the existing `FakeRelayTransportFactory` with a seeded list). Seed the supervisor with `StubPairedServerStore(PAIRED)`, `Random(SEED)`, `StandardTestDispatcher(testScheduler)`, as the existing helpers do.

**Test scenario (AC #1, #2, #5) — deterministic interleaving:**

- `connect(); runCurrent()` → old loop dials the gated transport, processes `Up`, publishes it. Assert `currentConnection.value === gated` and `ConnectionState.Connected`.
- `close()` → cancels the old loop (enqueues its cancellation-resume) and synchronously nulls `liveConnection`. `connect()` → starts the new loop.
- `runCurrent()` → the old loop's cancellation-resume runs and **parks in the gate's `NonCancellable` cleanup** (supervisor `finally` not yet reached); the new loop dials `created[1]` and awaits events. Assert `currentConnection.value == null` at this point (optional intermediate check).
- `created[1].emitUp(); runCurrent()` → new loop publishes. Assert `currentConnection.value === created[1]`.
- `release.complete(Unit); runCurrent()` → **now** the old loop's held `finally` finally runs. **Key assertion:** `assertSame(created[1], supervisor.currentConnection.value)` — the new transport survives. On the un-guarded `finally` this is `null` (test red); with the guard it stays `created[1]` (test green).
- Also assert `gated.closeCalls >= 1` (AC #3: the old loop still released its own socket in `finally`). `supervisor.close()` to clean up.

**Regression safety (AC #4 and normal-drop):** do **not** add new tests — assert by re-running the suite that `close_tearsDownTransportAndStopsLoop` (`:342`) and `singleDrop_countsDownPerSecondAndRecoversOnAFreshTransport` (`:42`) stay green. Both already assert `assertNull(currentConnection.value)` on their respective paths; the guard preserves them (analysis in Design).

## Open questions

- **Atomic `update {}` vs plain `if`-guard.** Spec recommends `update {}` (atomic compare-and-clear, matches AC #2 wording, robust under the multi-threaded default dispatcher). The plain `if`-guard the ticket sketches is functionally equivalent given `close()` pre-nulls — either is acceptable; the developer may choose the plain form if they prefer to match the ticket verbatim. No behavior difference in any realizable interleaving.
- None blocking.

## Security review

**Verdict:** PASS

**Findings:**

- **[Concurrency] No findings — this is the category the ticket lives in; the change strictly *narrows* an existing race.** It converts an unconditional cross-loop clear into an identity-keyed compare-and-clear, so a loop's `finally` can no longer wipe a transport published by a *different* loop. Recommended as an atomic `MutableStateFlow.update {}` (single compare-and-clear) so the check-then-act cannot itself race under `Dispatchers.Default`. No new coroutine, scope, mutex, or suspension point; the loop's cancellation-atomicity is unchanged. `close()`'s synchronous clear stays under `@Synchronized`.
- **[Cryptographic primitives / key material] No findings — no crypto touched, and key-wipe is *reinforced*.** The supervisor observes only the transport's `events` (`Up`/`Down`), never its `inbound` frames or any Noise key material; no cipher, RNG, nonce, or handshake code is altered. Downstream, a wrongly-null `currentConnection` is what *denies* `RelayRepositoryCoordinator` the transport — the fix removes a spurious drop, and dropping is always fail-safe there (`teardownActive → pump.close()` wipes keys). `transport.close()` remains unconditional in the `finally`, so no socket is leaked open.
- **[Trust boundaries] No findings — no boundary added or moved.** The change reshapes one internal reference-write; it parses no wire bytes. `liveConnection`/`currentConnection` carry an already-trusted, locally-created `RelayTransport` reference. Untrusted-wire parsing lives downstream in `RemoteConversationRepository`/`MobileWireCodec`, untouched.
- **[Availability / DoS] No findings — the fix *removes* a silent denial-of-connection.** The pre-fix behavior is itself the availability defect: a benign background→foreground (or push-wake) timing wipes a live connection, stranding the user offline until the next reconnect backoff elapses. The guard eliminates that window; it introduces no new unbounded wait, retry storm, or resource that an attacker could pin (the transport lifecycle and backoff schedule are unchanged).
- **[Error messages, logs, telemetry] No findings — no-log contract preserved.** The supervisor emits no logs (`:62-66`); the guard adds none. No `PairedServer`, relay URL, transport reference, or `Down` code/reason/cause reaches any sink.
- **[Tokens / secrets / credentials], [File / storage], [Inter-process / Android], [Network & I/O config]** — Not applicable. No token/credential handling, no filesystem path, no exported component/intent/deep-link, and no change to WebSocket/OkHttp/TLS configuration (frame-size caps, timeouts, pinning live in `data/network`'s transport, out of scope). No new wire-facing surface.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
