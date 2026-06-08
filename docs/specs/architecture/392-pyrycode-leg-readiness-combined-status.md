# #392 — pyrycode-leg Noise-session readiness + combined two-part connection status

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/392
**Size:** S · **Labels:** `security-sensitive`
**Split from #389. Depends on #391** (consumes its `RelayLinkStatus` relay-leg type, shipped on `main` — commit `a2dc738`). The combined `{relay, pyrycode}` model this slice produces is what the Settings connection-status line (**#390**, `blockedBy #392`) consumes.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:55-158` — **the class you modify.** It already owns the connection-scoped pump: `onConnection` (`:92-105`) builds a fresh pump per live transport and publishes the repo on `currentRepository` (`:65-68`); `teardownActive` (`:139-145`) clears it on drop. You add the relay-leg input, a private live-pump mirror, the derived pyrycode-leg flow, and the combined `connectionStatus` here. The `:85-105` non-suspending-critical-section invariant is load-bearing — keep your additions inside it.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:59-62,287-301` — `PumpState` (`Handshaking → Open(connId) → Closed(cause)`) is the readiness source. `state` starts `Handshaking`, flips to `Open` **only** after `readResp` succeeds (`:164`), and to `Closed` on teardown (`:264`). This is *why* `Connected` can only be reached post-handshake.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:44-54` — `ManagedSessionPump.state: StateFlow<PumpState>` is exactly the member you read off the live pump.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:87-96` — `relayStatus: StateFlow<RelayLinkStatus>` (#391) is the relay leg you zip in. It's fetched off the **concrete** supervisor, like `currentConnection` — no interface/DI change (mirror the `connections = get<RelayConnectionSupervisor>().currentConnection` wiring already in AppModule).
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` — the relay-leg type the combined model holds verbatim (5 cases incl. `DaemonAbsent`).
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt` — style mirror for the two new sealed/`data` types (terse sealed class, `data object` cases).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:48-66` — the **exact idiom to copy** for the pyrycode-leg derivation: `currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }` under `@OptIn(ExperimentalCoroutinesApi::class)`. Your `activePumpFlow.flatMapLatest { it?.state ?: flowOf(null) }` is the same shape over the live pump.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:78-92` — where the coordinator is constructed (add `relayStatus = get<RelayConnectionSupervisor>().relayStatus`) and how singletons hand StateFlows to consumers (`currentRepository` is the precedent #390 follows for `connectionStatus`).
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` — the harness you extend. `FakeManagedPump` (`:472-516`) already drives lifecycle via `open()`/`closeState()` and exposes `state`; `newEnv` (`:385-401`) builds the coordinator; the suite uses `StandardTestDispatcher` + `runCurrent()` and **every test ends with `coordinator.close()`** so the perpetual collectors don't hang `runTest` — your new `stateIn` collectors fall under the same `close()`.
- Memory: **ktlint single-public-class filename rule** — a `.kt` file's only public top-level *type* must match the filename (functions don't count). Each new sealed/`data` type gets its own file; the mapping `fun` rides at the bottom of `RelayRepositoryCoordinator.kt`.

## Context

The relay path has two independently-failing legs. #391 shipped the **relay leg** (`phone → relay`) as `RelayLinkStatus`, branching the `4404` close into `DaemonAbsent`. This slice adds the **pyrycode leg** (`relay → daemon`, end-to-end session readiness) and the **combined** `{relay, pyrycode}` model.

`RelayConnectionSupervisor.relayStatus.Connected` means *socket-open* — the supervisor's own KDoc (`:58-60`) flags that it deliberately does **not** mean Noise-session-open. A status dot driven straight off the socket goes green whenever the relay socket is up, even when the handshake failed or no daemon is behind the relay — the false green that bit live testing on 2026-06-08.

`NoiseSessionPump` already exposes the honest signal: `state` reaches `Open(connId)` only at handshake completion. The pump is **connection-scoped** — a fresh pump per connection, built and owned by `RelayRepositoryCoordinator` (the #302/#309 coordinator seam). So the readiness signal must follow the *current* pump across reconnects; no pump between connections = not ready. We wire it in the coordinator (which owns the pump), not in the pump itself.

**No server polling, no heartbeat, no status endpoint** — readiness is purely the handshake-completion the pump already surfaces.

## Design

### Two new portable types in `data/model/` (each its own file, ktlint rule)

**`PyrycodeLinkStatus.kt`** — the pyrycode-leg readiness, a 3-case sealed type mirroring `ConnectionState`'s terse style. Contract (cases only — not an implementation):

```kotlin
sealed class PyrycodeLinkStatus {
    data object Handshaking : PyrycodeLinkStatus()  // noise_init sent, awaiting noise_resp
    data object Connected : PyrycodeLinkStatus()    // Noise handshake completed; session live
    data object Down : PyrycodeLinkStatus()         // session ended, or no live session yet
}
```

`Connected` is a **`data object`** — it deliberately does **not** carry `connId`. The handshake-derived id has no UI role here, and dropping it keeps a relay/crypto-derived string out of the status surface (see Security review § Trust boundaries).

**`ConnectionStatus.kt`** — the combined two-part model #390 consumes. Holds both legs verbatim:

```kotlin
data class ConnectionStatus(
    val relay: RelayLinkStatus,
    val pyrycode: PyrycodeLinkStatus,
)
```

### Mapping function (top-level `internal`, bottom of `RelayRepositoryCoordinator.kt`)

Sibling to #391's `RelayLinkStatus.toConnectionState()` placement. A function, so it does not trip the filename rule. Total over `PumpState?` (including the no-pump `null`):

`internal fun PumpState?.toPyrycodeLinkStatus(): PyrycodeLinkStatus`

| `PumpState` | → `PyrycodeLinkStatus` | note |
|---|---|---|
| `null` (no live pump / between connections) | `Down` | not-ready floor |
| `PumpState.Handshaking` | `Handshaking` | |
| `PumpState.Open(connId)` | `Connected` | **only** post-handshake; `connId` discarded |
| `PumpState.Closed(cause)` | `Down` | **`cause` never read** (no-log contract) |

The invariant — *`Connected` ⟺ pump reached `Open`* — is asserted by the testing-strategy scenarios below.

### Coordinator changes — `RelayRepositoryCoordinator.kt`

**Single source of state preserved.** The pyrycode leg's single source is a private live-pump mirror; the combined model is pure derivation. No new mutable status state.

- **New constructor param** `relayStatus: StateFlow<RelayLinkStatus>`, placed right after `connections` (both are supervisor-published inputs). Both existing call sites (AppModule, test `newEnv`) use named args, so this is additive — no positional cascade.
- **Private live-pump mirror** `activePumpFlow: MutableStateFlow<ManagedSessionPump?>`, written in lock-step with the existing `mutableRepository`: set to the fresh `pump` in `onConnection` (`:96-97` region), cleared to `null` in `teardownActive` (`:140` region). It is the observable mirror of the lifecycle `active: Connection?` already tracks — exactly the precedent `mutableRepository` set. Stays **private**: the pump is single-owner (`:34-42`); only the *derived* readiness is exposed, never the pump reference.
- **Private derived leg** `pyrycodeStatus: Flow<PyrycodeLinkStatus>` — `flatMapLatest` over `activePumpFlow` onto the live pump's `state`, mapped through `toPyrycodeLinkStatus()`. Copy `StableConversationRepository.switchToLive`'s `@OptIn(ExperimentalCoroutinesApi::class)` + `?.… ?: flowOf(null)` shape. One contract line:
  - `activePumpFlow.flatMapLatest { it?.state ?: flowOf(null) }.map { it.toPyrycodeLinkStatus() }`
- **Public combined model** `connectionStatus: StateFlow<ConnectionStatus>` — `combine(relayStatus, pyrycodeStatus)` into `ConnectionStatus(relay, pyrycode)`, `stateIn`'d on the coordinator's existing `scope`:
  - `combine(relayStatus, pyrycodeStatus) { relay, pyrycode -> ConnectionStatus(relay, pyrycode) }.stateIn(scope, SharingStarted.Eagerly, ConnectionStatus(relayStatus.value, PyrycodeLinkStatus.Down))`

Only `connectionStatus` is public (1 new public member). AC1's "observable pyrycode-leg readiness signal" is satisfied through `connectionStatus.value.pyrycode` — the combined model exposes both legs (AC3). `pyrycodeStatus` stays private; no consumer wants the leg standalone (evidence-based — promote later if one appears).

**Init-order gotcha.** `stateIn(scope, Eagerly, …)` runs at *property initialization* (construction), so the derived-flow properties (`pyrycodeStatus`, `connectionStatus`) must be declared **after** `scope` (`:62-63`) and after `activePumpFlow` in the class body — Kotlin initializes top-to-bottom, and referencing `scope`/`activePumpFlow` from an earlier-declared property is a construction-time NPE. Placing the new fields beside `mutableRepository` (already below `scope`) satisfies this.

### DI — `AppModule.kt`

One added line in the existing `RelayRepositoryCoordinator { … }` block:
- `relayStatus = get<RelayConnectionSupervisor>().relayStatus,`

**No new Koin binding.** #390 obtains the combined model via `get<RelayRepositoryCoordinator>().connectionStatus`, the same concrete-singleton pattern `StableConversationRepository` uses for `currentRepository` (`:92`). Adding a dedicated `single { …connectionStatus }` is deferred to #390 (its consumer, its call) — building it now would be a binding with no consumer.

### Data flow

```
RelayConnectionSupervisor                RelayRepositoryCoordinator
  ├─ currentConnection ──(transport)──▶ onConnection ─▶ createPump ─▶ activePumpFlow
  │                                                          │ (live pump, or null between conns)
  │                                                          ▼
  │                                       flatMapLatest{ pump.state } ─▶ toPyrycodeLinkStatus()
  │                                                          │
  │                                                  pyrycodeStatus (Handshaking/Connected/Down)
  │                                                          │
  └─ relayStatus ───────────────────────── combine ─────────┘
                                              │
                                   connectionStatus: StateFlow<ConnectionStatus{relay,pyrycode}>
                                              │
                                              ▼  (#390 Settings status line)
```

Across a reconnect: `currentConnection` flips `null` → drop → fresh transport; the coordinator clears `activePumpFlow` to `null` (leg → `Down`), then sets it to the new pump (`Handshaking` → `Connected` on its own handshake). `flatMapLatest` cancels the prior pump's `state` collection and subscribes the new one — the leg tracks the live pump with no carryover.

## State + concurrency model

- **No new scope.** `pyrycodeStatus`/`connectionStatus` are `stateIn`'d on the coordinator's existing app-lifetime `scope` (`:62-63`). Both collectors are cancelled by the existing `close()` (`:149-152`, cancels `scope`) — and by `coordinator.close()` at the end of every test, so `runTest` doesn't hang.
- **`SharingStarted.Eagerly`** — the status is process-global and cheap (mapping `StateFlow` transitions, no IO), so keeping it always-live gives correct `.value` at any glance and simple `.value` reads in tests. `WhileSubscribed(5_000)` is a drop-in if lifecycle pressure is ever observed (StateFlow caches the last value) — deferred, no observed need.
- **`flatMapLatest`** cancels the previous pump's `state` collection on every `activePumpFlow` change — no collector leak across connections.
- **`activePumpFlow`** is written only inside the non-suspending `onConnection`/`teardownActive` critical section (the `:85-105` cancellation-atomicity invariant), in lock-step with `mutableRepository`. No suspension is introduced there. No check-then-mutate / TOCTOU: each write is an unconditional `.value =` on the same loop coroutine.
- **Hot, shared** `connectionStatus` is intentional (one process-global indicator, not per-screen). No cross-screen data-leak concern: the payload is two typed enums, no per-collector sensitive state.

## Error handling

| Failure mode | pump `state` | pyrycode leg | combined `ConnectionStatus` |
|---|---|---|---|
| No connection / between connections | (no pump, `null`) | `Down` | `{relay, Down}` — relay leg independent |
| Handshake in flight (socket up, pre-`noise_resp`) | `Handshaking` | `Handshaking` | never `Connected` on bare socket-up (AC2) |
| Handshake complete | `Open` | `Connected` | `{relay, Connected}` |
| Handshake timeout / MAC failure / bad frame | `Closed(cause)` | `Down` | honest "relay up, session not established" |
| Connection drop | `Closed` then `null` | `Down` (both map to `Down` — no flicker) | `{relay, Down}` |

- **No throws to handle.** Reading `StateFlow.value` / collecting never throws; `send`/`start`/`close` on the pump are non-throwing (`SessionPump.kt:31`, `NoiseSessionPump.kt:262-269`). The mapping is total over the sealed `PumpState` + `null`.
- **No-log contract (MUST hold).** `PumpState.Closed.cause` carries a category-only message and **must not be logged**; `toPyrycodeLinkStatus` discriminates on the `PumpState` subtype only and maps `Closed → Down` (a static `data object`) — `cause` is never read. `Open.connId` is likewise discarded. `ConnectionStatus` carries only the two typed leg enums (the sole string is #391's already-vetted `Reconnecting.secondsRemaining: Int`). The coordinator's existing "emits no logs" posture (`:42`) is preserved — no `Log.*`/`Timber.*` added.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), extending `RelayRepositoryCoordinatorTest.kt` — JVM, `StandardTestDispatcher` + `runCurrent()`, the existing `FakeManagedPump` (drives `state` via `open()`/`closeState()`), no device. Two harness tweaks:

- Add a `relayStatus: MutableStateFlow<RelayLinkStatus>` to `newEnv` (default `RelayLinkStatus.Connected`), pass it to the coordinator, and stash it on `Env` so a test can drive the relay leg **independently** of the pyrycode leg.
- Read assertions off `coordinator.connectionStatus.value` (`.relay` / `.pyrycode`) after `runCurrent()`; close with `coordinator.close()` like every existing test.

Scenarios (developer writes bodies in the existing idiom):

- **No pump → `Down` (AC1/AC2).** Fresh coordinator, no connection ⇒ `connectionStatus.value.pyrycode == Down`.
- **Bare socket-up is `Handshaking`, never `Connected` (AC2).** set `connections` ⇒ pump created (defaults to `Handshaking`) ⇒ `runCurrent` ⇒ `pyrycode == Handshaking` (asserts `!= Connected` — the false-green guard).
- **`Open` ⇒ `Connected`, only post-handshake (AC1/AC2).** from `Handshaking`, `pump.open()` ⇒ `runCurrent` ⇒ `pyrycode == Connected`.
- **`Closed` ⇒ `Down` (AC1).** from `Open`, `pump.closeState(null)` ⇒ `pyrycode == Down`. (A `closeState(SomeException("…"))` variant confirms `Down` regardless of `cause` — the no-log discrimination.)
- **Readiness tracks the live pump across reconnect (AC2).** open → `Connected`; `connections = null` ⇒ `Down`; fresh transport ⇒ new pump `Handshaking`; `pump2.open()` ⇒ `Connected` again — proving the leg follows the *current* pump with no carryover.
- **Combined model reflects each leg independently (AC3/AC4).** hold the pyrycode leg at `Connected` (`pump.open()`) and drive `env.relayStatus.value = DaemonAbsent` ⇒ `connectionStatus.value == ConnectionStatus(relay = DaemonAbsent, pyrycode = Connected)`; then `env.relayStatus.value = Connected` + `pump.closeState(null)` ⇒ `ConnectionStatus(relay = Connected, pyrycode = Down)`. Each leg moves on its own axis.

`./gradlew check` green; test-first (red → green → refactor).

## Open questions

- **DI exposure for #390.** This slice exposes `connectionStatus` off the concrete coordinator singleton (precedent: `currentRepository`, `relayStatus`). Whether #390 adds a thin `single { get<RelayRepositoryCoordinator>().connectionStatus }` binding or fetches it directly is #390's call — not pre-built here.
- **`Eagerly` vs `WhileSubscribed`.** Chose `Eagerly` for always-live status + `.value` test simplicity; `WhileSubscribed(5_000)` is a drop-in if lifecycle pressure is ever observed. Deferred (no observed need).
- **`Connected` carrying `connId`.** Dropped — no UI role, and it keeps a handshake-derived id off the status surface. If a future diagnostics view wants it, add a field then.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The pump's `state` crosses from the (untrusted-relay-driven) session machine into trusted status at exactly one explicit point — `toPyrycodeLinkStatus()`, which discriminates on the sealed `PumpState` subtype only. The two relay/crypto-derived strings reachable from `PumpState` — `Open.connId` and `Closed.cause` — are both **discarded** by the mapping (`Connected`/`Down` are static `data object`s). `ConnectionStatus` downstream holds only two typed leg enums; no relay-supplied text flows into the status surface. The relay leg (`RelayLinkStatus`) was vetted in #391 and is passed through verbatim.
- **[Tokens, secrets, credentials]** N/A — this slice reads/stores no tokens or secrets. It reads the pump's lifecycle *enum*, never session keys, the device static key, or the push token (the coordinator's token handling in `reregisterPushTokenOnOpen` is #365's, untouched).
- **[File / storage]** N/A — no filesystem or storage operation introduced; pure in-memory flow derivation.
- **[Inter-process / Android surface]** N/A — no Activity/Service/Receiver, Intent, deep link, PendingIntent, ContentProvider, or WebView; portable `data/` types + `data/repository` flow wiring, no `android.*`.
- **[Cryptographic primitives]** No finding. No RNG, key, nonce, or comparison introduced. The Noise handshake/AEAD/re-key all live in `NoiseSessionPump`/`NoiseIkSession` and are untouched; this slice only observes the *outcome* (`Open`) the handshake already reached.
- **[Network & I/O]** No finding — design-enforced. **No server polling, no heartbeat, no status endpoint** (explicit ticket constraint): readiness is the pump's already-surfaced handshake completion. No transport/timeout/TLS/frame-cap change. A hostile relay cannot inflate the signal — `Connected` is reachable *only* via `PumpState.Open`, which the pump sets *only* after `readResp` authenticates the `noise_resp` (`NoiseSessionPump.kt:155-164`); a socket-up-but-no-valid-handshake relay yields `Handshaking`/`Down`, never `Connected` (the exact false-green this ticket closes). No new retry/backoff loop — redial cadence stays the supervisor's #391 schedule.
- **[Error messages, logs, telemetry]** No finding — design-enforced. The no-log contract holds: `toPyrycodeLinkStatus` never reads `Closed.cause` (maps to a static `Down`), `connId` is discarded, and no `Log.*`/`Timber.*` is added — the coordinator's "emits no logs" posture (`RelayRepositoryCoordinator.kt:42`) is preserved. `ConnectionStatus` embeds no relay/crypto strings. (Spec marks MUST; code-review verifies no log call added.)
- **[Concurrency]** No finding. No new scope — both derived flows `stateIn` on the existing coordinator `scope`, cancelled by the existing `close()`. `flatMapLatest` cancels the prior pump's `state` collection on every pump change (no collector leak). `activePumpFlow` is written only in the non-suspending `onConnection`/`teardownActive` critical section, in lock-step with `mutableRepository`, preserving the `:85-105` cancellation-atomicity invariant — no TOCTOU (unconditional `.value =` on one coroutine). `connectionStatus` is intentionally hot/shared (process-global indicator) carrying only typed enums — no cross-screen sensitive-state leak.
- **[Threat model alignment]** The pyrycode leg is precisely a threat-model improvement: it removes the false "connected" when the relay socket is up but the E2E session is not live (handshake failed / no daemon), so the user is never told the session is secure before `Noise_IK` completes. UI-surface threats (screenshot/overlay/accessibility) are N/A — no UI in this data-layer slice (the status *line* is #390). **OUT OF SCOPE:** any richer per-failure diagnostics (surfacing `Closed.cause` category to the UI) is deferred and would itself need a no-log/no-leak review when filed; this slice surfaces a non-diagnostic `Down` only.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
