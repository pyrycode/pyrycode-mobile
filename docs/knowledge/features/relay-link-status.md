# Relay link status — the relay-leg connection signal

The **relay-leg half** of the two-part connection status. The relay path has two
independently-failing legs — **phone → relay** and **relay → pyrycode daemon** — and
`RelayLinkStatus` models the *first* of them: a socket-level signal that additionally branches the
relay's `4404 "no server"` close (relay reachable, but no daemon registered behind it) into a
distinct `DaemonAbsent`, a state the legacy single-signal [`ConnectionState`](connection-state.md)
cannot express. Introduced in [#391](../codebase/391.md) (split from #389).

Package: `de.pyryco.mobile.data.model` (`RelayLinkStatus.kt`), beside
[`ConnectionState`](connection-state.md). Portable (**no `android.*`**) — it's a `data/` model, kept
Compose-Multiplatform-safe.

## The type

```kotlin
sealed class RelayLinkStatus {
    data object Connected : RelayLinkStatus()              // relay reachable + socket up
    data object Idle : RelayLinkStatus()                   // deliberately not dialing (#499)
    data object Connecting : RelayLinkStatus()             // a dial is in progress
    data class Reconnecting(val secondsRemaining: Int) : RelayLinkStatus()
    data object DaemonAbsent : RelayLinkStatus()           // relay reachable, no daemon (the 4404 close)
    data object PairingRejected : RelayLinkStatus()        // host refused the credential (4401 / 4426)
    data object Offline : RelayLinkStatus()                // unreachable / sustained unavailability
}
```

It mirrors `ConnectionState`'s four cases and adds `DaemonAbsent` (#391), `Idle` (#499) and
`PairingRejected` (#841). All three are static `data object`s carrying **no relay-supplied text**: for
`DaemonAbsent`/`PairingRejected` the relay's `reason` string / `cause` never flow into it — the only
relay datum that crosses into this model is the integer close `code`, compared against `4404`, `4401`
and `4426` (see [Security](#security)); `Idle` carries no data at all.

## `PairingRejected` — a rejected credential halts redial (#841)

A `4401` (invalid, expired or revoked device token) or `4426` (handshake failed: the saved server
static key is stale) close cannot recover by redialling — the host will refuse the same credential
again. The [supervisor](relay-reconnect-supervisor.md) branches both codes to `PairingRejected` and
stops its automatic redial for that host (`haltUntilRetry()` — see the supervisor doc's state machine
and § Halt and resume paths); an explicit retry or the next foreground `connect()` dials exactly once
more. Every other close code, `4404` included, and a `null` dial failure keep the uniform retry path.

**Phone-side detection is out of scope.** A `noise_resp` verify failure on the phone tears down through
`NoiseSessionPump` with a category-only `NoiseSessionException` shared with a handshake timeout, a wrong
first frame, the re-key watchdog and a bad open-state frame; the transport then closes locally with the
same `Down(code = 1000)` as any other local close, so the supervisor cannot distinguish it from this
event alone. Making it distinguishable — a typed pump cause, or the phone closing with `4426` as the
protocol contract describes — is a follow-up ticket.

`RelayConnectionRegistry.pairingStatus` deliberately leaves `PairingRejected` **out of** its
retry-once set (the set that re-presents a bundle's stale first-seen `Offline`/`DaemonAbsent`): the
bundle is keyed to the exact saved record, so an initial `PairingRejected` on it means this exact
token/server-key pair was refused, and an automatic retry cannot succeed with a rejected credential. A
credential change replaces the record and `reconcile` builds a fresh bundle starting from `Idle`, so a
stale rejection from an older credential can never reach the flow.

## `Idle` — deliberately not dialing (#499)

`Idle` is the state where the supervisor is **not dialing anything on purpose**, distinct from a live
socket. It covers three situations that all previously overloaded `Connected`: the **initial**
pre-`connect()` seed, the **unpaired** branch (paired store returns `null` → idle, end the loop), and
**`close()`** (intentional background disconnect). It is named for the *situation*, not a cause —
`close()` was paired and the initial seed predates any pairing check, so a pairing-specific name would be
wrong for two of the three.

The point of the separate case is that the two derived surfaces **diverge** on it (both mappers are
exhaustive `when`s, so the compiler forces each to decide):

- **banner** ([`toConnectionState()`](relay-reconnect-supervisor.md)): `Idle → ConnectionState.Connected`
  — the banner stays hidden (idle is not an error), exactly as when it overloaded `Connected`.
- **Settings line** ([`toLegVisual()`](connection-status-line.md)): `Idle → Down`/"Not connected" — a
  non-green, honest label, so the Settings relay leg no longer reads a false "Connected" while unpaired.

Before #499 both idle and live-socket reused `Connected`, which was harmless while the banner was the
only consumer but leaked a false green once the Settings line began reading a *positive* claim off the
same value. See [`../codebase/499.md`](../codebase/499.md).

## Why a new type instead of a fifth `ConnectionState` case

`ConnectionState` is the **legacy single-signal** model the [`ConnectionBanner`](connection-banner.md)
consumes with an **exhaustive `when` (no `else`)**. Adding `DaemonAbsent` there is a compile error
that would force banner UI work *now* — but the banner is re-pointed to #392's *combined* model
anyway. The two-leg architecture wants the relay leg as its **own type**, so #391 follows a
**Strangler Fig**: introduce `RelayLinkStatus` as the leg type, make it the supervisor's single source
of truth, and **derive** the legacy `ConnectionState` from it. #392 eventually makes the combined
`{relay, pyrycode}` model the real source.

## Where it's produced and derived

The [`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) ([#391](../codebase/391.md)) is the
sole producer:

- **Single source of truth** — the supervisor's backing `MutableStateFlow` is typed `RelayLinkStatus`
  (initial `Idle` since #499), exposed read-only as `val relayStatus: StateFlow<RelayLinkStatus>` (mirroring
  `currentConnection` — a plain public property on the concrete supervisor, **no interface, no DI
  change**).
- **Legacy `ConnectionState` is derived per-collector** —
  `override fun observe(): Flow<ConnectionState> = state.map { it.toConnectionState() }`, so every
  existing consumer is untouched. `toConnectionState()` is an `internal` top-level mapping fun:
  identity for the four shared cases, **`Idle → ConnectionState.Connected`** (#499 — deliberately idle,
  banner stays hidden), **`DaemonAbsent → ConnectionState.Offline`** (the relay is up but unusable
  end-to-end — the nearest legacy banner meaning until #392 gives `DaemonAbsent` its own copy), and
  **`PairingRejected → ConnectionState.Offline`** (#841 — same nearest-legacy-meaning rationale; the
  Settings status line and the host row give it its own label/treatment instead).

```
            ┌─ relayStatus (asStateFlow) ─────────▶ #392 combined {relay, pyrycode}
state: MutableStateFlow<RelayLinkStatus>
            └─ observe() = map { toConnectionState() } ─▶ legacy ConnectionBanner
```

The `4404` branch: in the supervisor's `#308 seam` Down arm, a loop-local `daemonAbsent =
(event.code == 4404)` is threaded into `backoff()`, which emits a **steady** `DaemonAbsent` (no
per-second countdown) on the **same** jittered-backoff schedule as any other drop — so the leg flips
off the instant a daemon registers and the next dial succeeds. The same Down arm also sets a loop-local
`pairingRejected = (event.code == 4401 || event.code == 4426)` (#841); when set, the loop calls
`haltUntilRetry()` instead of `backoff()`, suspending with no timeout until `retry()` or a fresh
`connect()` wakes it. Every other close code (`1006`, `1000`) and a `null` dial failure stay on the
existing `Reconnecting`/`Offline` path. See the [supervisor doc](relay-reconnect-supervisor.md) for the
full state machine and backoff cadence.

## Consumer

- **[#392](../codebase/392.md) (landed)** zips this relay leg with the **pyrycode-session-readiness
  leg** (derived from `NoiseSessionPump.state`) into the combined
  [`ConnectionStatus { relay, pyrycode }`](connection-status.md) model, published off the
  [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) as `connectionStatus`. It fetches
  `relayStatus` off the concrete supervisor exactly like `currentConnection` — **no interface or DI
  change** (it becomes a coordinator ctor param). The relay leg is passed through **verbatim**.
- The combined model's consumer is the Settings connection-status line (**#390**, `blockedBy #392`,
  next).
- The legacy *derived* `observe()` is still consumed unchanged (by `ThreadViewModel`); `relayStatus`
  itself now has its first live consumer in the coordinator.

## Security

`security-sensitive`. The relay-controlled WS close `code` is **untrusted** and crosses into trusted
state at exactly three integer comparisons — `event.code == RELAY_NO_DAEMON_CLOSE` (`4404`),
`== RELAY_TOKEN_REJECTED_CLOSE` (`4401`) and `== HANDSHAKE_FAILED_CLOSE` (`4426`, #841). Integer `==`
cleanly excludes `null` (a clean dial failure / malformed stored data) and every other code, so those
**cannot masquerade as `DaemonAbsent`/`PairingRejected`**. No relay-supplied string or `Throwable` flows
into the model; both are static objects. The supervisor's **no-log contract** holds — each branch reads
`code` only to compare it, never to log it. A hostile relay spamming `4404` only forces `DaemonAbsent`
plus continued redial on the **same** capped-exponential backoff (no tighter loop, no amplification).

A hostile relay forging `4401`/`4426` can halt redial for that host (denial of service), but it could
already deny service by dropping or refusing connections — the halt is bounded by the next explicit
retry or foreground, and the design deliberately never mutates or deletes the saved pairing on
rejection, so a forged code can never destroy a credential (accepted risk, see the #841 plan's security
review, verdict PASS). Halting also *reduces* how often the refused token is re-presented to the relay —
from every ≤30 s to once per explicit user action or foreground.

## Related

- Ticket notes: [`../codebase/391.md`](../codebase/391.md) (the type + `DaemonAbsent`) ·
  [`../codebase/499.md`](../codebase/499.md) (the `Idle` sixth case + the divergent banner/Settings
  mapping) — files/line refs, patterns, lessons. #841 (`PairingRejected`) landed after the
  per-ticket archive was frozen (2026-09-05); see its spec below instead.
- Specs: `docs/specs/architecture/391-relay-leg-daemon-absent-status.md` (§ Design, § Security review —
  Verdict PASS) · `docs/specs/architecture/841-rejected-pairing-relay-state.md` (`PairingRejected`,
  the halt/resume design, § Security review — Verdict PASS).
- Producer: [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#391](../codebase/391.md)) —
  the single source of truth (`relayStatus`) and the `toConnectionState()` derivation.
- Legacy sibling it's derived to: [Connection state](connection-state.md) (`ConnectionState`, #196).
- Delivers the `4404`: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md))
  — `TransportEvent.Down.code`.
- Consumer: **[#392](../codebase/392.md)** (landed) — the combined
  [`{relay, pyrycode}` status](connection-status.md), published off the
  [coordinator](relay-repository-coordinator.md) as `connectionStatus`; its consumer is the Settings
  status line (#390, next).
