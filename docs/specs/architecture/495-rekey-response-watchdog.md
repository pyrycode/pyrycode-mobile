# #495 — Recover re-keying after a lost re-key `noise_resp`

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/495
**Size:** S · **Labels:** `security-sensitive`
**Scope:** one production file (`NoiseSessionPump.kt`) + one test file (`NoiseSessionPumpTest.kt`). No UI, no new public API, no new exported types.

---

## Files to read first

Read these before touching anything — they carry every contract this change rides on.

- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:231-254` — `initiateRekey`. The watchdog is **armed** here, immediately after the successful `transport.send(noise_init)`. Note the existing order: `writeRekeyInit(s)` → `rekeyInFlight = true` → `send` → `finally { s.fill(0) }`, all inside `rekeyMutex.withLock`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:198-211` — `onOpenFrame`'s `TYPE_NOISE_RESP` arm. The watchdog is **cancelled** here, right after the `if (!rekeyInFlight) throw …` guard confirms this resp belongs to an in-flight re-key. This is the *only* place today that clears `rekeyInFlight` + calls `rebaseRekeyTimer`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:213-221` — `rebaseRekeyTimer` + the **"drive-coroutine-only"** invariant on `rekeyTimerJob`. Do **not** re-arm the 1-hour timer from the watchdog (that would break the single-writer discipline). Route the timeout to `teardown` instead.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:256-269` — `teardown(cause)`, the single idempotent funnel. The watchdog fires straight into this. It wipes the session (`session.close()`), downs the transport, and cancels `scope` (which auto-cancels the watchdog on every *other* teardown path).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:50-56` — ctor params (`handshakeTimeoutMs`, `rekeyIntervalMs`). Add `rekeyRespTimeoutMs` here following the same injectable-timeout pattern.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:82-87` — `rekeyInFlight` + `rekeyTimerJob` field decls. Add the `rekeyRespTimeoutJob` field alongside.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:271-284` — companion consts (`HANDSHAKE_TIMEOUT_MS`, `REKEY_INTERVAL_MS`). Add `REKEY_RESP_TIMEOUT_MS`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:186-204` — `writeRekeyInit`: the `check(pendingRekey == null)` precondition (`:189`) is **why the recovery is a teardown, not a same-session retry** — an abandoned re-key leaves `pendingRekey` non-null and nothing but `close()` clears it.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:276-289` — `close()`: `pendingRekey?.destroy(); pendingRekey = null` wipes the re-key handshake's copy of `s`. This is what preserves the #298 bounded-RAM window **on the timeout path** (AC #3).
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt:328-542` — the entire `#304` re-key test block. **Several of these must be migrated off `advanceUntilIdle()`** — see § Testing strategy (this is the turn-eater; read it before writing any test code).
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt:558-616` — `Fixture` / `newPump` / `openSession` helpers. Thread the new `rekeyRespTimeoutMs` param through here.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt:770-819` — the companion (`REKEY_MS = 20L`); add a short response-timeout const for the regression test.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:103` — the *only* production construction site: `createPump = { transport -> NoiseSessionPump(transport, sessionFactory) }`. The new ctor param is **defaulted**, so this line does **not** change. (Grounds AC #1: the coordinator rebuilds a fresh pump per connection.)
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:43-70,155-170` — the reconnect-on-`Down` loop. Confirms the recovery chain: pump `teardown` → `transport.close()` → transport emits `Down` → supervisor reconnects → coordinator builds a fresh pump → fresh handshake → fresh keys → 1-hour timer re-armed.

---

## Context

`NoiseSessionPump.initiateRekey` sets `rekeyInFlight = true` **before** sending the re-key `noise_init`. The inbound `noise_resp` handler (`onOpenFrame`, `TYPE_NOISE_RESP`) is the **only** code that clears `rekeyInFlight` and re-arms the 1-hour cadence (`rebaseRekeyTimer`). The one-shot timer coroutine that fired *this* re-key already completed its `delay`, so if that `noise_resp` never arrives (a single dropped frame), nothing re-arms the cadence: `rekeyInFlight` wedges `true` forever, every future `initiateRekey` hits the `if (rekeyInFlight …) return@withLock` guard and silently skips, and the transport runs indefinitely on un-rotated keys. There is no response timeout today.

This is the mobile-initiator mirror of a case the Go binary already handles: its scheduled re-key arms a bounded reply timeout and tears the conn down (WS 4426) when the peer doesn't reply within ~30 s (pyrycode #450). Per the pipeline "different fabric" rule, the safety net for a stochastic dropped frame is **code-level teardown**, not a second best-effort retry.

Manifests with `USE_RELAY_REPOSITORY` on. Filed from the Cross-Repo Code Review 2026-07-03.

---

## Design

One production change: a bounded **response watchdog**, armed with `rekeyInFlight`, cancelled on the completing `noise_resp`, funnelling to `teardown` on timeout.

### Why teardown, not same-session retry

`NoiseIkSession.writeRekeyInit` requires `pendingRekey == null` (`NoiseIkSession.kt:189`). An abandoned re-key leaves `pendingRekey` non-null, and only `session.close()` clears it (`:281-282`). So "just clear `rekeyInFlight` and retry" silently dead-ends at `writeRekeyInit` unless we add a new session-level abandon API — extra surface for no gain. Funnelling the timeout through the existing `teardown(cause)` is the deterministic recovery: it wipes keys, downs the transport, and the reconnect supervisor (#306/#307) rebuilds a fresh pump → fresh handshake → fresh keys → timer re-armed. Given the single-re-key-in-flight invariant, teardown-then-fresh-handshake **is** the recovery; there is no in-session state worth salvaging once a re-key is abandoned.

### The three edits

**1. New injectable timeout (ctor param + companion const).**

- Add ctor param `private val rekeyRespTimeoutMs: Long = REKEY_RESP_TIMEOUT_MS` alongside `rekeyIntervalMs` (`:55`).
- Add companion const `const val REKEY_RESP_TIMEOUT_MS = 30_000L` — mirrors the Go initiator's WS-4426 reply deadline. KDoc it as the server mirror.

**2. New watchdog field.**

- `@Volatile private var rekeyRespTimeoutJob: Job? = null`, declared next to `rekeyTimerJob` (`:86-87`). `@Volatile` mirrors `rekeyInFlight`'s visibility posture — written under `rekeyMutex` in `initiateRekey`, read/cancelled on the drive coroutine in `onOpenFrame`.

**3. Arm in `initiateRekey`; cancel in `onOpenFrame`.**

Arm — inside the existing `try`, immediately after the `transport.send(noise_init)` line (still under `rekeyMutex`):

```kotlin
transport.send(InnerFrameV2(type = TYPE_NOISE_INIT, data = base64StdEncode(initBytes)))
rekeyRespTimeoutJob = scope.launch {
    delay(rekeyRespTimeoutMs)
    teardown(NoiseSessionException("re-key noise_resp not received before the deadline"))
}
```

Placement matters: it sits **after** `writeRekeyInit`/`send` so a racing-teardown `IllegalStateException` (caught below) never arms a watchdog, and **inside** the `try` so it is only armed when an init was actually put on the wire. `teardown` is safe to call from a `scope.launch` child — `drive()` already does exactly this — and idempotent via its `terminated` CAS.

Cancel — in `onOpenFrame`'s `TYPE_NOISE_RESP` arm, right after the in-flight guard and before `readRekeyResp`:

```kotlin
if (!rekeyInFlight) throw NoiseSessionException("unexpected noise_resp with no re-key in flight")
rekeyRespTimeoutJob?.cancel() // the swap is completing — disarm the watchdog
session.readRekeyResp(base64StdDecode(frame.data))
rekeyInFlight = false
rebaseRekeyTimer()
```

Cancelling **before** `readRekeyResp` is deliberate: if `readRekeyResp` throws (MAC failure → the drive-loop catch → `teardown`), the watchdog is already both cancelled here *and* redundantly cancelled by `scope.cancel()` in teardown. Either order is correct; before-the-throw keeps the disarm unconditional once a resp for an in-flight re-key is confirmed.

### Why job-cancellation is sufficient (no generation counter needed)

The single-re-key-in-flight invariant means a **new** re-key can only start after the resp handler has cleared `rekeyInFlight` — and that same handler cancels the prior watchdog first. So watchdog A is always cancelled by resp A before re-key B can arm watchdog B; a stale watchdog cannot fire during a later re-key. No epoch/generation token is required. The only unguarded window is the sub-instruction race "resp processed on the drive coroutine *between* `send` and the watchdog-field assignment on the initiateRekey coroutine" — not physically realizable (the resp is a network reply that cannot precede the init it answers) and consistent with the file's existing posture (the resp handler already clears `rekeyInFlight` off-mutex on the same assumption). Documented, not defended.

---

## State + concurrency model

- **Scope / cancellation.** The watchdog is a `scope.launch` child, exactly like `rekeyTimerJob`. Every non-watchdog teardown path calls `scope.cancel()`, which auto-cancels an armed watchdog — no explicit cancel needed in `teardown`, on `close()`, or on transport `Down`. The existing `closeMidRekey_…` leak check (no leaked collector after close) therefore still holds.
- **Dispatcher.** Inherits the pump's injected dispatcher (`Dispatchers.Default` in prod; `StandardTestDispatcher` under test) — no new dispatcher hop. The `delay(rekeyRespTimeoutMs)` is virtual-clock-drivable, which is what makes the regression test deterministic.
- **`rekeyTimerJob` invariant preserved.** The watchdog never touches `rekeyTimerJob` and never re-arms the 1-hour cadence. It only calls `teardown`. The "drive-coroutine-only" single-writer discipline on `rekeyTimerJob` is untouched.
- **Field-writer discipline.** `rekeyRespTimeoutJob` has two touch sites — armed in `initiateRekey` (under `rekeyMutex`), cancelled in `onOpenFrame` (drive coroutine). This is the same cross-coroutine shape as `rekeyInFlight` (`@Volatile`, set under mutex, cleared on the drive coroutine). Match that posture; do not add a second mutex.

---

## Error handling

- **Timeout → fault teardown.** The watchdog calls `teardown(NoiseSessionException("re-key noise_resp not received before the deadline"))` — a **non-null** cause, so `PumpState.Closed(cause)` reads as a fault (mirrors the handshake-timeout path at `:148`). The message is **category-only**, no key material / frame bytes / secret — carries the #291/#273 no-secrets-in-logs posture forward.
- **Recovery is transport-driven, not cause-driven.** `teardown` always calls `transport.close()`; the supervisor reconnects on the transport `Down` event regardless of the pump's `Closed` cause. So the fault cause is for observability, not for triggering the reconnect.
- **Key hygiene on the timeout path (AC #3).** The device-static local `s` in `initiateRekey` is already zeroed in its `finally` before the watchdog ever fires — the timeout does **not** extend `s`'s life. The session's copy inside `pendingRekey` is wiped by `session.close()` inside `teardown`. Both copies are gone promptly; the #298 bounded-RAM window is not widened.
- **Happy path unchanged (AC #2).** When the resp arrives first: watchdog cancelled, `rekeyInFlight` cleared, `rebaseRekeyTimer` re-arms the 1-hour cadence, no extra frame sent. Byte-for-byte identical outbound behavior to today.

---

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.NoiseSessionPumpTest"`; the bare `test` task is aggregate — use `testDebugUnitTest --tests`). No instrumented test — this is pure data-layer, driven by `runTest`'s virtual clock against the existing `FakeRelayTransport` + real `TestResponder`.

### ⚠️ The load-bearing gotcha: existing `#304` tests must migrate off `advanceUntilIdle()`

**This is the highest-risk part of the ticket. Read it fully before writing any test code — skipping it burns turns debugging phantom teardowns.**

Adding *any* finite watchdog changes what `advanceUntilIdle()` does. `advanceUntilIdle()` fast-forwards virtual time through **all** pending delays — including the newly-armed `delay(rekeyRespTimeoutMs)`. So every existing re-key test that (a) fires a re-key and then (b) calls `advanceUntilIdle()` **before** delivering the `noise_resp` will now fast-forward past the watchdog deadline and **tear the session down** — either failing an `assertEquals(PumpState.Open …)` outright, or silently preempting the teardown trigger the test meant to exercise.

Affected tests (all in the `#304` block, `:328-542`) and the fix — replace the `advanceUntilIdle()` that fires the re-key with a **bounded** advance that reaches only the timer/request, not the watchdog:

| Test | Current | Fix |
|------|---------|-----|
| `timer_firesRekeyInitOnlyAfterTheInterval` | `advanceUntilIdle()` fires timer, asserts `Open` | `advanceTimeBy(rekeyIntervalMs); runCurrent()` — fires the timer at its deadline, leaves the watchdog dormant |
| `timerRekey_completesAndTrafficContinuesOnNewKeys` | `advanceUntilIdle()` fires timer *before* resp | same bounded advance, then push resp via `runCurrent()` |
| `timer_isRebasedByEachCompletedRekey` | final `advanceUntilIdle()` fires re-key #2, no resp | bounded advance to the rebased timer |
| `rekeyComplete_sendsNoAckAndStaysOpen` | `advanceUntilIdle()` fires timer before resp | bounded advance, then resp via `runCurrent()` |
| `inboundRekeyRequest_triggersRekeyNotForwardedRoundTripsOnNewKeys` | `advanceUntilIdle()` runs the launched `initiateRekey` before resp | `runCurrent()` (the `rekey_request` path is `scope.launch{…}` at current time; no clock advance needed to run it) |
| `assertRekeyRequestTriggersRekey` helper (covers 3 tests) | `advanceUntilIdle()` | `runCurrent()` |
| `rekeyResp_fromDifferentStaticTearsDownWithoutHalfSwap` | `advanceUntilIdle()` fires timer before foreign resp | bounded advance, then foreign resp via `runCurrent()` — otherwise the watchdog preempts the MAC-fail teardown this test exists to prove |
| `closeMidRekey_cancelsTimerWipesSessionLeavesNoLeak` | `advanceUntilIdle()` fires timer before `close()` | bounded advance, then `close()` — otherwise the watchdog preempts the `close()` this test exercises |

Notes on the `advanceTimeBy` idiom: `advanceTimeBy(rekeyIntervalMs)` runs tasks scheduled *strictly before* the new virtual time, so a `delay(rekeyIntervalMs)` armed at the same origin fires only under the trailing `runCurrent()`. Keep the existing `REKEY_MS = 20L` interval; the default `rekeyRespTimeoutMs = 30_000L` sits far beyond any bounded advance these tests make, so leaving them on the default timeout keeps the watchdog dormant. Do **not** try to keep `advanceUntilIdle()` and "make the timeout large" — `advanceUntilIdle()` reaches *any* finite delay; the driving must change, not the deadline.

The three tests the AC names as "still pass" — `timer_isRebasedByEachCompletedRekey` (rebase-on-completion), `rekeyComplete_sendsNoAckAndStaysOpen` (no-`rekey_ack`), and the completes-and-stays-Open cases — all **complete** the re-key, so the watchdog is cancelled and their happy-path assertions are byte-for-byte preserved after the bounded-advance migration.

### Fixture threading

Thread `rekeyRespTimeoutMs` through `Fixture.newPump` and `openSession` as an optional param (default `null` → keep the pump's real 30 s default), mirroring how `rekeyIntervalMs` is already threaded (`:577-583`, `:594-599`). Only the new regression test injects a small value.

### New regression test (AC #4)

`timerRekey_respTimeoutTearsDownWhenRespNeverArrives` (or similar). Bulleted scenario — developer writes it in the file's idiom:

- Open a session with a small `rekeyIntervalMs` (e.g. `REKEY_MS`) **and** a small `rekeyRespTimeoutMs` (e.g. `50L`), with `rekeyRespTimeoutMs` chosen so the watchdog and the timer are unambiguously ordered.
- Bounded-advance to fire the re-key timer; assert the re-key `noise_init` went out (`sentFrames.size == 2`, last type `noise_init`) and the pump is still `Open`.
- Do **not** deliver a `noise_resp`.
- `advanceTimeBy(rekeyRespTimeoutMs); runCurrent()` (or `advanceUntilIdle()`) to reach the watchdog deadline.
- Assert recovery: `pump.state.value is PumpState.Closed`, its `cause is NoiseSessionException`, and `transport.closeCalls >= 1`. (The pump-level assertion of "recovers" is the teardown; the fresh-handshake half lives in the supervisor and is out of scope for a pump unit test.)
- Optionally assert no leaked collector (`collector.isCompleted` after `advanceUntilIdle()`), matching the existing lifecycle tests.

A second, cheaper assertion for the "does not remain permanently unable to re-key" wording: after the timeout tears the pump down, `pump.send(envelope())` returns `false` (session wiped) — the pump can no longer wedge because it is no longer Open at all.

---

## Open questions

- **`rekeyRespTimeoutMs` default value.** Spec'd at `30_000L` to mirror the Go WS-4426 deadline (pyrycode #450). If the server's actual re-key reply budget differs materially, adjust — but 30 s is the documented server mirror and the safe default. Not a blocker.
- **Watchdog cause string.** `"re-key noise_resp not received before the deadline"` is category-only per the no-secrets convention; wording is non-load-bearing (no test asserts the string, only `cause is NoiseSessionException`). Developer may match the exact phrasing of the sibling handshake-timeout message for consistency.

---

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No new boundary. The watchdog consumes no untrusted data — it is a pure time-based teardown trigger. The `noise_resp` that cancels it still crosses the *existing* boundary in `session.readRekeyResp` (MAC-verified before any state swap); this change adds no new parse of wire bytes. A hostile/absent resp can now only ever cause a teardown (fail-safe), never a wedge.
- **[Tokens, secrets, credentials]** No token/credential handling added. The re-key device-static `s` lifecycle is unchanged: zeroed in `initiateRekey`'s `finally` regardless of the watchdog; the session's copy wiped by `session.close()` in `teardown`. The timeout path does **not** widen the #298 bounded-RAM window (AC #3) — teardown wipes `pendingRekey` promptly, strictly *narrowing* the window versus today's indefinite-wedge, where an abandoned `pendingRekey` (and its copy of `s`) would live for the whole remaining session.
- **[File / storage operations]** N/A — no filesystem access; the pump is memory + socket only.
- **[Inter-process / Android attack surface]** N/A — no Intent / deep link / PendingIntent / provider / WebView surface; `data/network` layer, `android.*`-free.
- **[Cryptographic primitives]** No crypto primitive added or altered. No RNG use (the watchdog is a `delay`, not a nonce/jitter source). Key material handling (`writeRekeyInit`/`readRekeyResp`/`close`) is untouched. Constant-time compares N/A (no attacker-controlled value is compared to a secret in the new code).
- **[Network & I/O]** The change *adds* a missing bounded deadline — it aligns the mobile initiator with the server's own bounded re-key reply timeout (Go #450 WS 4426). It does **not** touch frame-size caps, TLS config, OkHttp timeouts, or certificate pinning (those live in the #306 transport, unchanged). Net effect: a slow/hostile relay that swallows a re-key `noise_resp` can no longer hold the session on un-rotated keys indefinitely — it now forces a fresh handshake within `rekeyRespTimeoutMs`. This is a **hardening**, not a new exposure.
- **[Error messages, logs, telemetry]** The pump emits **no logs** (per its class contract, `:48`). The only new surface is the `PumpState.Closed(cause)` message, which is category-only (`"re-key noise_resp not received before the deadline"`) — no key, frame bytes, `conn_id`, or `PairedServer` data. Matches the sibling handshake-timeout message.
- **[Concurrency]** The watchdog is a `scope.launch` child cancelled by `scope.cancel()` on every other teardown path — no leak (the existing `closeMidRekey_…` leak check still holds after migration). No new mutex; `rekeyRespTimeoutJob` matches `rekeyInFlight`'s `@Volatile` cross-coroutine posture (set under `rekeyMutex`, cancelled on the drive coroutine). The `rekeyTimerJob` "drive-coroutine-only" single-writer invariant is preserved — the watchdog never re-arms the 1-hour timer, it only funnels to `teardown`. The one unguarded sub-instruction arm/cancel window is not physically realizable (a resp cannot precede the init it answers) and is consistent with the file's existing off-mutex `rekeyInFlight = false`.
- **[Threat model alignment]** Directly closes a mobile-initiator gap the server already defends: a single dropped re-key `noise_resp` silently voiding the 1-hour key-rotation guarantee for the rest of the session (Cross-Repo Code Review 2026-07-03). Post-change, the guarantee is enforced by a bounded teardown-and-fresh-handshake, mirroring the Go binary's WS-4426 behavior. No mobile-specific threat (screenshot/overlay/keyboard) applies — no UI, no user input.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
