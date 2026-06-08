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
    data object Connecting : RelayLinkStatus()             // a dial is in progress
    data class Reconnecting(val secondsRemaining: Int) : RelayLinkStatus()
    data object DaemonAbsent : RelayLinkStatus()           // relay reachable, no daemon (the 4404 close)
    data object Offline : RelayLinkStatus()                // unreachable / sustained unavailability
}
```

It mirrors `ConnectionState`'s four cases and adds the fifth, `DaemonAbsent`. `DaemonAbsent` is a
static `data object` carrying **no relay-supplied text** — the relay's `reason` string / `cause` never
flow into it; the only relay datum that crosses into this model is the integer close `code`, compared
once against `4404` (see [Security](#security)).

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
  (initial `Connected`), exposed read-only as `val relayStatus: StateFlow<RelayLinkStatus>` (mirroring
  `currentConnection` — a plain public property on the concrete supervisor, **no interface, no DI
  change**).
- **Legacy `ConnectionState` is derived per-collector** —
  `override fun observe(): Flow<ConnectionState> = state.map { it.toConnectionState() }`, so every
  existing consumer is untouched. `toConnectionState()` is an `internal` top-level mapping fun:
  identity for the four shared cases, and **`DaemonAbsent → ConnectionState.Offline`** (the relay is up
  but unusable end-to-end — the nearest legacy banner meaning until #392 gives `DaemonAbsent` its own
  copy).

```
            ┌─ relayStatus (asStateFlow) ─────────▶ #392 combined {relay, pyrycode}
state: MutableStateFlow<RelayLinkStatus>
            └─ observe() = map { toConnectionState() } ─▶ legacy ConnectionBanner
```

The `4404` branch: in the supervisor's `#308 seam` Down arm, a loop-local `daemonAbsent =
(event.code == 4404)` is threaded into `backoff()`, which emits a **steady** `DaemonAbsent` (no
per-second countdown) on the **same** jittered-backoff schedule as any other drop — so the leg flips
off the instant a daemon registers and the next dial succeeds. Every other close code (`4401`, `1006`,
`1000`) and a `null` dial failure stay on the existing `Reconnecting`/`Offline` path. See the
[supervisor doc](relay-reconnect-supervisor.md) for the full state machine and backoff cadence.

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
state at exactly one point — the integer `event.code == RELAY_NO_DAEMON_CLOSE` (`4404`) comparison.
Integer `==` cleanly excludes `null` (a clean dial failure / malformed stored data) and every other
code, so those **cannot masquerade as `DaemonAbsent`**. No relay-supplied string or `Throwable` flows
into the model; `DaemonAbsent` is a static object. The supervisor's **no-log contract** holds — the
branch reads `code` only to compare it, never to log it. A hostile relay spamming `4404` only forces
`DaemonAbsent` plus continued redial on the **same** capped-exponential backoff (no tighter loop, no
amplification). The `4401` auth-reject halt/re-pair branch is the `#308 seam`'s eventual *other*
branch — out of scope here.

## Related

- Ticket notes: [`../codebase/391.md`](../codebase/391.md) — files/line refs, patterns, lessons.
- Spec: `docs/specs/architecture/391-relay-leg-daemon-absent-status.md` (§ Design, § Security review —
  Verdict PASS).
- Producer: [Relay reconnect supervisor](relay-reconnect-supervisor.md) ([#391](../codebase/391.md)) —
  the single source of truth (`relayStatus`) and the `toConnectionState()` derivation.
- Legacy sibling it's derived to: [Connection state](connection-state.md) (`ConnectionState`, #196).
- Delivers the `4404`: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md))
  — `TransportEvent.Down.code`.
- Consumer: **[#392](../codebase/392.md)** (landed) — the combined
  [`{relay, pyrycode}` status](connection-status.md), published off the
  [coordinator](relay-repository-coordinator.md) as `connectionStatus`; its consumer is the Settings
  status line (#390, next).
