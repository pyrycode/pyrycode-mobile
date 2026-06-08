# #391 — Relay-leg connection status: branch the 4404 close into a distinct `DaemonAbsent` state

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/391
**Size:** S · **Labels:** `security-sensitive`
**Split from #389.** Sibling **#392** (pyrycode-leg readiness + combined `{relay, pyrycode}` model, `blockedBy #391`) consumes the relay-leg signal this slice produces.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:64-204` — the class you modify. Key spots: the no-log contract in the KDoc (`:60-62`), the `runLoop` state machine (`:114-161`), the **`#308 seam`** Down arm (`:143-146`, where 4404 branches), `backoff()` (`:163-180`), and the `currentConnection` exposure pattern (`:79-81`) you mirror for `relayStatus`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt:61-81` — `TransportEvent.Down.code` semantics: WS close code (e.g. `4404`) or HTTP status on a rejected upgrade; **`null` on dial failure / malformed stored data**. This null-vs-4404 distinction is load-bearing for AC4.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt:1-13` — the legacy 4-case sealed class. The new `RelayLinkStatus` mirrors it + `DaemonAbsent`; the new file lives next to this one.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` — the test harness you extend. Note `FakeRelayTransport.emitDown(code, reason, cause)` (`:368-375`, already supports an explicit `code`), the `runCurrent()` vs `advanceUntilIdle()` discipline (`:26-35`), `intervalsFor(...)` replay (`:322-327`), and the `observe().first()` state helper (`:308`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt:32-52` — the **exhaustive `when (ConnectionState)` with no `else`**. This is *why* `DaemonAbsent` must NOT be added to `ConnectionState`: doing so breaks this `when` at compile time and forces premature UI work. Do not touch this file.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:64,81` — supervisor bound as `ConnectionStateSource`; `currentConnection` consumed via `get<RelayConnectionSupervisor>()` (the concrete type). #392 fetches `relayStatus` the same way → **no DI change in this ticket**.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:98,192-198` — consumes `ConnectionStateSource.observe()` then `.stateIn(...)`. Confirms a *derived cold* `observe()` flow (post-change) still satisfies every existing consumer.
- Memory: the ktlint single-public-class filename rule — a `.kt` file whose only public top-level type must be named after it; the new sealed type goes in its own file.

## Context

The relay path has two independently-failing legs: **phone → relay** and **relay → pyrycode daemon**. Today `RelayConnectionSupervisor` exposes one socket-level `ConnectionState` and retries *every* `Down` uniformly (`#308 seam`, `:143-146`). The transport already delivers the close code on `TransportEvent.Down.code` (`:76`), so the relay's `4404 "no server"` close (relay reachable, no daemon registered) is currently indistinguishable from a network/connect failure — both just retry.

This slice adds the **relay-leg** half of an honest two-part status: a relay-transport status that branches `4404` into a distinct `DaemonAbsent`. The Noise-session-readiness half (pyrycode leg) and the combined model are #392, which consumes this leg.

**No server/relay/daemon change** — the `4404` close is already delivered. **No heartbeat, no status endpoint.**

## Design

### New type — `data/model/RelayLinkStatus.kt`

A sealed class mirroring `ConnectionState`'s four cases plus `DaemonAbsent`. This is the relay-leg signal #392 zips with the pyrycode leg. Portable (no `android.*`), lives beside `ConnectionState.kt`. Its own file per the ktlint filename rule.

Contract (cases only — not an implementation):

```kotlin
sealed class RelayLinkStatus {
    data object Connected : RelayLinkStatus()              // relay reachable + socket up
    data object Connecting : RelayLinkStatus()             // dial in progress
    data class Reconnecting(val secondsRemaining: Int) : RelayLinkStatus()
    data object DaemonAbsent : RelayLinkStatus()           // relay reachable, no daemon (4404)
    data object Offline : RelayLinkStatus()                // unreachable / sustained unavailability
}
```

### Why a new type rather than a 5th `ConnectionState` case

`ConnectionState` is the **legacy single-signal** model the banner consumes; `ConnectionBanner.kt:32-52` switches over it with an exhaustive `when` (no `else`). Adding `DaemonAbsent` there is a compile error that forces banner UI work *now* — but the banner is re-pointed to #392's *combined* model anyway (memory: consumer #390 → #392). The two-leg architecture wants the relay leg as its **own type**; #392 zips `{relay, pyrycode}`. So: introduce `RelayLinkStatus` as the leg type and **derive** the legacy `ConnectionState` from it (Strangler Fig — new alongside old; #392 eventually makes the combined model the real source).

### Supervisor changes — `RelayConnectionSupervisor.kt`

**Single source of state** stays one `MutableStateFlow`, retyped to the leg model; the legacy surface is derived (no parallel mutable state):

- Retype the backing field `state` from `MutableStateFlow<ConnectionState>` to `MutableStateFlow<RelayLinkStatus>`, initial `RelayLinkStatus.Connected`. Every internal `state.value = ConnectionState.X` write becomes the matching `RelayLinkStatus.X` (Connecting / Connected / Reconnecting / Offline; and `close()`'s idle `Connected`).
- Add the public leg signal, mirroring the `currentConnection` exposure (`:79-81`):
  - `val relayStatus: StateFlow<RelayLinkStatus> = state.asStateFlow()`
- Derive the legacy `ConnectionStateSource` surface (keeps `ConnectionState` 4-case, every consumer untouched):
  - `override fun observe(): Flow<ConnectionState> = state.map { it.toConnectionState() }`
- Add an `internal` top-level mapping fun (sibling to `backoffBaseSeconds` / `jitteredBackoffMs`, directly unit-testable):
  - `internal fun RelayLinkStatus.toConnectionState(): ConnectionState` — identity for the four shared cases; **`DaemonAbsent → ConnectionState.Offline`** (the closest legacy meaning until #392's combined banner; the relay is up but unusable end-to-end).

**The 4404 branch (the `#308 seam`, `:143-146`):**

- Declare a per-dial-iteration `var daemonAbsent = false` in `runLoop`'s `while` body (same closure-capture pattern as the existing `var sawUp`, `:126`).
- In the Down arm, set `daemonAbsent = (event.code == RELAY_NO_DAEMON_CLOSE)`. **Read only — no logging** (see Error handling). Do **not** read `event.reason` / `event.cause` into state.
- Add `const val RELAY_NO_DAEMON_CLOSE = 4404` to the companion (`:186-189`).
- Thread the flag into backoff: `backoff(attempt, daemonAbsent)`.

**`backoff(attempt: Int, daemonAbsent: Boolean)`:**

- When `daemonAbsent`: set `state.value = RelayLinkStatus.DaemonAbsent`, then `collapsibleWait(jitteredBackoffMs(attempt, random))` and `return`. **Same backoff schedule** (same attempt-based jittered interval, same escalation to the 30 s cap), **no per-second `Reconnecting` countdown** (DaemonAbsent is a steady, distinct state). `collapsibleWait` still lets `retry()` collapse the wait, so tap-to-retry works during `DaemonAbsent`.
- Otherwise: the existing sub-cap `Reconnecting` countdown / at-cap `Offline` logic, unchanged except the `state.value` writes now use `RelayLinkStatus`.

### Data flow

```
RelayTransport.events ──Down(code)──▶ runLoop Down arm
        code == 4404 ─▶ daemonAbsent=true ─▶ backoff ─▶ state = DaemonAbsent ─┐
        code != 4404 / null ─▶ backoff ─▶ Reconnecting(countdown) / Offline ──┤
                                                                              ▼
                              state: MutableStateFlow<RelayLinkStatus>  (single source)
                                 ├─ relayStatus (asStateFlow)  ──▶ #392 combined model
                                 └─ observe() = map{ toConnectionState() } ──▶ legacy banner
```

On redial during `DaemonAbsent`: the loop builds a fresh transport → `state = Connecting`; a daemon now registered → `Up` → `Connected` (leg flips off); still 4404 → `DaemonAbsent` again; a non-4404 Down → the existing reconnect path.

## State + concurrency model

- No new coroutines/scopes. The supervisor's own `CoroutineScope(SupervisorJob() + dispatcher)` and single `runLoop` job are unchanged.
- `state` is the single hot `MutableStateFlow` (conflated). `relayStatus` is its read-only `asStateFlow()` view (hot, shared). `observe()` returns a **per-collector cold** mapped flow — no cross-screen sharing leak; each consumer gets its own mapping.
- `var daemonAbsent` is loop-local, written only inside the single `events.collect` running on the loop coroutine (identical safety to `sawUp`). No TOCTOU: `DaemonAbsent` is set in `backoff` on the same loop coroutine; `close()`'s reset is already `@Synchronized` and cancels the loop first (pre-existing behaviour, untouched).
- Cancellation/shutdown unchanged: the `finally` (`:150-156`) still releases the socket on Down and on `close()`.

## Error handling

| Failure mode | `Down.code` | Relay-leg status | Legacy `ConnectionState` |
|---|---|---|---|
| Daemon not registered (relay up) | `4404` | `DaemonAbsent`, keep redialling on schedule | `Offline` |
| Server/graceful close (e.g. `1000`, future `4401`) | non-`4404` | existing `Reconnecting`→`Offline` path | identity |
| Abnormal drop | `1006` etc. | existing reconnect path | identity |
| **Clean dial failure** | `null` | existing reconnect path (`Reconnecting`→`Offline`) — **never `DaemonAbsent`** | identity |
| **Malformed stored data** (bad `relayUrl`) | `null` (per `RelayTransport` contract) | existing reconnect path — **never `DaemonAbsent`** | identity |

The integer equality `code == 4404` cleanly excludes `null` and every other code, so dial failures and malformed data cannot masquerade as `DaemonAbsent`. No throws to handle — the transport surfaces failures as `Down` and never throws (`RelayTransport.kt:42-44`); `retry()` never throws.

**No-log contract (MUST hold — `RelayConnectionSupervisor.kt:60-62`):** the new branch reads `event.code` to compare against the constant and adds **no** log statement; `DaemonAbsent` is a static object carrying **no** relay-supplied string. `code` / `reason` / `cause` stay unlogged.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), extending `RelayConnectionSupervisorTest.kt` — JVM, virtual clock, fake transport, seeded `Random`; no device. `FakeRelayTransport.emitDown(code = 4404)` already supports the explicit code. Add a `relayStatus()` read helper (`supervisor.relayStatus.value`) alongside the existing `state()` (`observe().first()`) helper. Scenarios (developer writes bodies in the existing idiom):

- **4404 → `DaemonAbsent`, distinct from Offline (AC2).** connect → `emitUp` → `emitDown(code = 4404)` → `runCurrent` ⇒ `relayStatus.value == DaemonAbsent` and `!is Reconnecting && != Offline`. Assert the legacy view: `observe().first() == ConnectionState.Offline`.
- **`DaemonAbsent` keeps redialling and flips off on register (AC2).** from `DaemonAbsent`, `advanceUntilIdle` ⇒ a fresh transport created (`factory.created.size` +1) and status `Connecting`; `emitUp` on the new transport ⇒ `Connected` (leg off).
- **Repeated 4404 stays `DaemonAbsent` on the unchanged schedule (AC2).** two consecutive `emitDown(code = 4404)` (no intervening 60 s stability) ⇒ `DaemonAbsent` both times; the second redial interval sits in the **base-2** ±20 % jitter band (`intervalsFor(1, 2)`), proving the existing backoff escalation/schedule is reused, not replaced.
- **Non-4404 Down → existing path, no `DaemonAbsent` (AC3).** `emitDown(code = 1006)` (and a `4401` case) ⇒ `relayStatus` enters `Reconnecting(countdown)`, never `DaemonAbsent`; legacy `observe()` unchanged.
- **Clean dial failure (`code = null`) → unreachable path (AC4).** `emitDown(code = null)` ⇒ `Reconnecting` sub-cap, escalating to `Offline` at the cap; never `DaemonAbsent`.
- **Legacy derivation regression guard.** the existing six tests (which read `observe().first()`) must still pass unchanged — they are the proof that `toConnectionState()` preserves the four legacy cases.
- **`toConnectionState()` direct map test (optional, cheap).** each `RelayLinkStatus` → expected `ConnectionState`, incl. `DaemonAbsent → Offline`.

`./gradlew check` green; test-first (red → green → refactor).

## Open questions

- **`DaemonAbsent` → `Offline` legacy mapping.** Chosen because the relay-up-but-no-daemon case is unusable end-to-end and `Offline` is the nearest legacy banner state. It's transitional — #392's combined banner gives `DaemonAbsent` its own copy. If #392's design wants a different interim banner string, that's #392's call; this slice keeps the legacy banner behaviour stable (no new banner state).
- **Backoff escalation during sustained `DaemonAbsent`** reaches the 30 s cap like any other drop (AC: "keeps redialling on its backoff schedule"). Worst-case ~30 s to notice a freshly-registered daemon. Matches existing offline behaviour; if a tighter daemon-register latency is wanted later, it's a separate refinement.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The WS close `code` is relay-controlled (untrusted). It crosses into trusted state at exactly one explicit point — the `event.code == RELAY_NO_DAEMON_CLOSE` integer comparison in `runLoop`'s Down arm. No relay-supplied string (`reason`) or `Throwable` (`cause`) flows into `RelayLinkStatus`; `DaemonAbsent` is a static `data object`. Downstream holds a typed enum case only, never relay text.
- **[Tokens, secrets, credentials]** N/A — this slice reads/stores no tokens or secrets. The close `code` is a public protocol value, not a secret; integer `==` (not a secret compare) is correct, so no constant-time comparison is needed.
- **[File / storage]** N/A — no filesystem or storage operations introduced.
- **[Inter-process / Android surface]** N/A — no Activity/Service/Receiver, Intent, deep link, PendingIntent, ContentProvider, or WebView added; pure `data/network` policy change.
- **[Cryptographic primitives]** No finding. The pre-existing `random: Random` (kotlin.random) drives backoff **jitter only** (non-security) and is unchanged; no security-relevant randomness, key, or primitive is introduced.
- **[Network & I/O]** No finding. No transport/timeout/TLS/frame-cap change — those belong to #306 and are untouched. A hostile relay spamming `4404` only forces `DaemonAbsent` plus continued redial on the **same** capped-exponential backoff (escalates to the 30 s cap) — no tighter retry loop, no token-exhaustion or DoS amplification versus today's uniform retry.
- **[Error messages, logs, telemetry]** No finding — design-enforced. The no-log contract (`RelayConnectionSupervisor.kt:60-62`) is preserved: the branch adds no log statement and `DaemonAbsent` embeds no relay string, so `code`/`reason`/`cause` remain unlogged. (Spec marks this MUST; code-review verifies no `Log.*`/`Timber.*` added.)
- **[Concurrency]** No finding. No new coroutine/scope; single hot `MutableStateFlow` source with a read-only `asStateFlow()` view and a per-collector **cold** `observe()` (no cross-screen sharing leak); `var daemonAbsent` is loop-local with the same safety as the existing `sawUp`; `retry()` collapses the `DaemonAbsent` wait without races.
- **[Threat model alignment]** Malformed stored data / dial failure (`code = null`) cannot masquerade as `DaemonAbsent` (integer-equality excludes `null`). UI-surface threats (screenshot/overlay/accessibility) are N/A — no UI in this data-layer slice (banner display is #390/#392). **OUT OF SCOPE:** halt-retry / re-pair branching for auth-reject close codes (e.g. `4401`) is the `#308 seam`'s eventual *other* branch — deferred; this ticket branches only `4404` and leaves all other codes on the existing uniform-retry path.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
