# Connection status — the combined two-part `{relay, pyrycode}` model

The **honest, two-part** connection status the Settings status line (#390) renders. The relay path
has two independently-failing legs, and showing one collapsed dot lies whenever they disagree:

- **relay leg** (`phone → relay`) — socket-level, [`RelayLinkStatus`](relay-link-status.md)
  ([#391](../codebase/391.md)).
- **pyrycode leg** (`relay → daemon`) — end-to-end **session readiness**, `PyrycodeLinkStatus`
  ([#392](../codebase/392.md)).

`ConnectionStatus` holds **both legs verbatim** so each is shown independently — a green relay dot
paired with a handshaking pyrycode dot is an expressible, honest state, no longer collapsed into a
single false "connected." Introduced in [#392](../codebase/392.md) (split from #389), which adds the
pyrycode leg and the combined model on top of #391's relay leg.

Package: `de.pyryco.mobile.data.model` (`ConnectionStatus.kt`, `PyrycodeLinkStatus.kt`), beside
[`ConnectionState`](connection-state.md) / [`RelayLinkStatus`](relay-link-status.md). Portable
(**no `android.*`**) — `data/` models, kept Compose-Multiplatform-safe; each its own file per the
ktlint single-public-class filename rule.

## The types

```kotlin
// the combined model #390 consumes
data class ConnectionStatus(
    val relay: RelayLinkStatus,        // phone → relay (socket-level, #391)
    val pyrycode: PyrycodeLinkStatus,  // relay → daemon (session readiness, #392)
)

// the pyrycode-leg readiness
sealed class PyrycodeLinkStatus {
    data object Handshaking : PyrycodeLinkStatus()  // noise_init sent, awaiting noise_resp
    data object Connected : PyrycodeLinkStatus()    // Noise handshake completed; session live
    data object Down : PyrycodeLinkStatus()         // session ended, or no live session yet
}
```

`Connected` is a **`data object`** — it deliberately does **not** carry the handshake's `connId`. The
id has no UI role here, and dropping it keeps a relay/crypto-derived string off the status surface
(see [Security](#security)).

## Why the pyrycode leg is the *honest* signal

[`RelayConnectionSupervisor`](relay-reconnect-supervisor.md)'s `Connected` (the relay leg) means
**socket-open**, not Noise-session-open — its own KDoc flags this. A status dot driven straight off
the socket goes green whenever the relay socket is up, **even when the handshake failed or no daemon
is behind the relay**. That false green is exactly what bit live testing on **2026-06-08**.

The honest signal already exists on [`NoiseSessionPump.state`](noise-session-pump.md): `PumpState`
reaches `Open(connId)` **only** after `readResp` authenticates `noise_resp`. So `PyrycodeLinkStatus`
reaches `Connected` only post-handshake — never on bare socket-up, and not at all between connections
(no live pump). A socket-up-but-no-valid-handshake relay yields `Handshaking`/`Down`, never
`Connected`.

**No server polling, no heartbeat, no status endpoint** — readiness is purely the handshake
completion the pump already surfaces (explicit ticket constraint).

## Where it's produced — derived in the coordinator

The [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) owns the connection-scoped pump,
so it is where the pyrycode leg is derived and the combined model published (not in the pump itself):

```
                                 RelayRepositoryCoordinator
RelayConnectionSupervisor          activeConnection (single source: pump + scope + repo, or null)
  ├─ currentConnection ──▶ onConnection ──▶ createPump ──┘  │
  │                                                          ▼
  │                          flatMapLatest{ conn?.pump?.state ?: null } ──▶ toPyrycodeLinkStatus()
  │                                                          │
  │                                          pyrycodeStatus (private: Handshaking/Connected/Down)
  │                                                          │
  └─ relayStatus ───────────────────── combine ─────────────┘
                                          │
                              connectionStatus: StateFlow<ConnectionStatus>  ──▶ #390 Settings line
```

- **`PumpState? → PyrycodeLinkStatus`** is a total `internal` mapping fun
  (`toPyrycodeLinkStatus()`, bottom of `RelayRepositoryCoordinator.kt`): `null`/`Closed → Down`,
  `Handshaking → Handshaking`, `Open → Connected`. `Open.connId` and `Closed.cause` are **discarded**.
- **The live pump** is reached through the coordinator's single `activeConnection` source (`conn?.pump`),
  written on its non-suspending critical section — [#493](../codebase/493.md) consolidated the former
  separate `activePumpFlow`/`activeRemoteRepo` mirrors into this one source. `flatMapLatest` over it tracks
  the **current** pump across reconnects with no carryover (`Connected` → drop `Down` → fresh pump
  `Handshaking` → `Connected`).
- **`connectionStatus`** = `combine(relayStatus, pyrycodeStatus)` lifted to a hot `StateFlow` via
  `stateIn(scope, SharingStarted.Eagerly, …)`. The combined model is **pure derivation** — no
  hand-maintained joined state. The pyrycode leg stays **private**; only the combined model is public.

## Consumer

#390 (the Settings connection-status line) **split into two slices** at the architect:

- **[#397](../codebase/397.md) (the rendering component — shipped)** — the stateless
  [`ConnectionStatusLine`](connection-status-line.md) composable that renders *this* model as the
  two-part `● Relay   ● Pyrycode` line (each leg's dot coloured from a semantic token via a pure,
  unit-tested mapper). It consumes `ConnectionStatus` **verbatim** but does **not** touch the live
  data path.
- **[#398](../codebase/398.md) (the live wiring — `blockedBy #397`, shipped)** — fetches
  `get<RelayRepositoryCoordinator>().connectionStatus` off the concrete coordinator singleton in
  `AppModule` (the same pattern `currentRepository`/`relayStatus` use — **no new Koin binding**, no
  thin `single { … }`: the `StateFlow` is passed straight into the `SettingsViewModel` constructor),
  forwards it **verbatim** as a `val` on [`SettingsViewModel`](settings-viewmodel.md) (no `stateIn`
  re-wrap — the upstream is already hot/`Eagerly`), collects it lifecycle-aware at the Settings host,
  and drops the [`ConnectionStatusLine`](connection-status-line.md) component under the Server row.

This is the model's **first and only live consumer**; it shipped ahead of its UI by design (#391/#392
landed the data, #397 the component, #398 the wiring).

## Security

`security-sensitive`. The pump's lifecycle is driven by an untrusted relay; it crosses into trusted
status at **exactly one point** — `toPyrycodeLinkStatus()`, which discriminates on the sealed
`PumpState` subtype only. The two relay/crypto-derived strings reachable from `PumpState` —
`Open.connId` and `Closed.cause` — are **both discarded** (`Connected`/`Down` are static
`data object`s), so no relay-supplied text flows into the status surface; `ConnectionStatus`
downstream holds only two typed leg enums. The **no-log contract** holds — the mapping never reads
`cause`, and no `Log.*`/`Timber`/`println` is added (the coordinator's "emits no logs" posture is
preserved). A hostile relay **cannot inflate** the signal: `Connected` is reachable only via
`PumpState.Open`, which the pump sets only after `readResp` authenticates `noise_resp` — closing the
false green this model exists to fix. The relay leg ([`RelayLinkStatus`](relay-link-status.md)) was
vetted in #391 and is passed through verbatim. Mirror trust-boundary shape to #391's "single integer
`== 4404` comparison."

## Related

- Ticket notes: [`../codebase/392.md`](../codebase/392.md) — files/line refs, patterns, lessons.
- Spec: `docs/specs/architecture/392-pyrycode-leg-readiness-combined-status.md` (§ Design,
  § Security review — Verdict PASS).
- Relay leg (held verbatim): [Relay link status](relay-link-status.md) (`RelayLinkStatus`,
  [#391](../codebase/391.md)).
- Producer / wiring: [Relay repository coordinator](relay-repository-coordinator.md)
  ([#392](../codebase/392.md) added `connectionStatus`) — owns the connection-scoped pump it derives
  from.
- Readiness source: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) —
  `PumpState.Open` is the handshake-completion moment.
- Legacy single-signal sibling: [Connection state](connection-state.md) (`ConnectionState`, #196) —
  what a one-dot status used to collapse to.
- Renderer (shipped): [Connection status line](connection-status-line.md)
  ([#397](../codebase/397.md)) — the two-part `● Relay   ● Pyrycode` component; live wiring shipped in
  [#398](../codebase/398.md).
</content>
