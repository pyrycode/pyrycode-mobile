# #499 — Idle relay status: stop reporting "Connected" while unpaired/idle

**Size:** XS (confirmed — 1 new sealed case, 2 compiler-forced `when` branches, 3 one-line reassignments; 3 production files + 2 mirror test files). Not security-sensitive.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt:13-30` — the sealed hierarchy you extend. Note every case except `Reconnecting` is a `data object` carrying no relay text (no-log contract). The new case follows the `DaemonAbsent`/`Offline` shape exactly.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:77,110-116,126-137` — the three idle-assignment sites (`state` seed line 77; `close()` line 115; unpaired branch line 135). These flip `Connected` → the new case.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:239-246` — `toConnectionState()`, the **banner** mapper. Exhaustive `when`; the compiler forces a new branch. New case → `ConnectionState.Connected` (banner stays hidden — AC#3).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt:36-67` — `ConnectionLegCategory` enum (only `Up`/`InProgress`/`Down`), `ConnectionLegVisual`, and `toLegVisual()`, the **Settings-line** mapper. Exhaustive `when`; the compiler forces a new branch. Study how `DaemonAbsent` (line 63-64) shares a category with `Connected` yet diverges by label — the exact precedent for idle sharing `Down`'s category with `Offline` yet diverging by label.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:39,57-58,107-116,126-137` — the KDoc/comments that literally say "idle … Connected" / "back to `ConnectionState.Connected`". Update the prose to name the new case so the doc stays honest; the derived-banner claim (`ConnectionState.Connected`, banner hidden) remains true and stays.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:305-330,363-416,617-626` — the three idle behaviour tests (`benignUnpaired…`, `reloadPerDial_laterNullRead…`, `close_tearsDownTransportAndStopsLoop`) and the `toConnectionState_…` mapper test. They assert the **derived** `ConnectionState` via `supervisor.state()`, which stays `Connected` under this design, so they keep passing; strengthen them to also pin the new relay-leg value (see Testing).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLineTest.kt:13-55` — the per-case `toLegVisual` triple assertions; add one for the new case.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:217-222` — **read-only confirmation**: `connectionStatus` just `combine`s the two legs into `ConnectionStatus`; no `when` over `RelayLinkStatus`, so there is **no third mapper** to update. Nothing to change here.

## Context

`RelayConnectionSupervisor` overloads one `RelayLinkStatus.Connected` value for two unrelated meanings: a **live socket** (transport `Up`, line 151) and three genuinely **idle** situations where nothing is dialing — the initial pre-`connect()` seed (line 77), the unpaired branch (paired store returns `null` → idle, end the loop, line 135), and `close()` (intentional background disconnect, line 115). The overload exists so the derived legacy banner stays hidden while idle (the banner shows nothing precisely when the derived `ConnectionState` is `Connected`).

The side effect: the Settings two-part status line's **relay** leg maps `Connected` → the green label "Connected". So while unpaired and dialing nothing, Settings reads "Connected" — a false positive during the exact pairing/connection-diagnosis workflow the line exists to support.

The fix is the divergence the ticket names: give idle its **own** `RelayLinkStatus` case. Because both mappers are exhaustive `when`s, the compiler forces both to handle it, and they can map it differently — idle → banner-hidden `ConnectionState` (preserves AC#3) **and** idle → a non-"Connected", non-green Settings label (satisfies AC#1). Manifests only with `USE_RELAY_REPOSITORY` on.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Settings "Connection" section (node `90:4 "Status"`) renders the two-part `● Relay   ● Pyrycode` line with M3 semantic dot colours — a green dot for an up leg, a red dot for a down leg (the mock shows Relay green, Pyrycode red). The new idle relay state reuses this established red/`error` = "down" language plus a distinct textual label; **no new pixel design or colour token** — the status-line component post-dates the design lock (#390/#397/#398) and the category enum already covers success/warning/error.

## Design

### 1. New sealed case — `RelayLinkStatus.kt`

Add one case alongside the existing five:

```kotlin
/** Deliberately not dialing: initial (pre-connect), unpaired, or intentionally closed. */
data object Idle : RelayLinkStatus()
```

- `data object` (no relay-supplied text) → satisfies the no-log contract, matching `DaemonAbsent`/`Offline`.
- Name `Idle` (not `Disconnected`/`NotPaired`): it covers all three situations without implying a cause. `close()` was paired; the initial seed predates any pairing check — so a pairing-specific name would be wrong for two of the three.

### 2. Three idle reassignments — `RelayConnectionSupervisor.kt`

Flip `RelayLinkStatus.Connected` → `RelayLinkStatus.Idle` at exactly these three sites; **leave the live-socket `Up` branch (line 151) as `Connected`**:

| Site | Line | Meaning |
|------|------|---------|
| `state` initial seed | 77 | pre-`connect()` |
| `close()` | 115 | intentional background disconnect |
| unpaired branch (`paired == null`) | 135 | dialing nothing, loop ends |

Update the adjacent KDoc/comments (lines 39, 57-58, 107-108, 130-132) that describe these as idling "at `Connected`" to name `Idle`; keep the parenthetical that the **derived banner** state is still `ConnectionState.Connected` (banner hidden) — that remains true.

### 3. Banner mapper — `toConnectionState()` (`RelayConnectionSupervisor.kt:239`)

Add the compiler-forced branch:

```kotlin
RelayLinkStatus.Idle -> ConnectionState.Connected
```

This is the crux: idle derives to the banner-hidden legacy state (AC#3), diverging from the Settings mapper below. Update the function KDoc (lines 235-237) to mention idle maps to `Connected` like the four shared cases.

### 4. Settings-line mapper — `toLegVisual()` (`ConnectionStatusLine.kt:55`)

Add the compiler-forced branch:

```kotlin
RelayLinkStatus.Idle ->
    ConnectionLegVisual(ConnectionLegCategory.Down, "Not connected", "Relay: not connected")
```

**Design decision — category `Down`, label "Not connected":**

- **Category `Down`, not a new neutral 4th value.** The enum is `Up`/`InProgress`/`Down` only, resolved to success/warning/error M3 tokens in `color()`. `Up` is forbidden by AC#1 (must not read green). `InProgress` (amber) would falsely imply an in-flight dial — nothing is dialing. A genuinely-neutral category would need a new enum value **and** a new M3 colour token in `theme/`, pushing this past XS and past the ticket's "reuses established M3 category language" framing. `Down` is the honest, in-scope choice: during diagnosis, "you are connected to nothing" is truthfully a not-up state, and the Figma mock already uses red for a not-connected leg.
- **Label "Not connected", distinct from `Offline`'s "Offline".** Two states share the `Down` category but diverge on the visible text/a11y channel — the exact pattern `Connected`/`DaemonAbsent` use to share `Up` ("Connected" vs "Reachable"). "Not connected" reads correctly for all three idle situations; "Not paired" would be wrong for `close()` and the initial seed.
- `contentDescription` = "Relay: not connected".

### Data flow (unchanged shape)

`state: MutableStateFlow<RelayLinkStatus>` → `relayStatus` (published off the supervisor) → `RelayRepositoryCoordinator.connectionStatus` (`combine` with the pyrycode leg, unchanged) → `ConnectionStatusLine.toLegVisual()` (Settings). Separately `state.map { it.toConnectionState() }` → legacy banner. The two consumers already derive independently; this ticket only adds a case each `when` must route.

## State + concurrency model

No change. `state` is the single hot `MutableStateFlow<RelayLinkStatus>` source of truth; `relayStatus`/`observe()` derive from it; no new flows, jobs, dispatchers, or cancellation behaviour. The three reassignments run on the same paths and threads as today.

## Error handling

No change. Idle is not an error path — it is the deliberate-not-dialing state. No new failure modes, result types, or UI surfacing. The no-log contract is preserved (new case carries no text).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest` — pure mappers + supervisor flow; no instrumented test needed). All scenarios below are `runTest`/pure-JVM.

**`ConnectionStatusLineTest.kt`** — add one mapper test:
- `relayIdle_mapsToDown_notConnected`: `RelayLinkStatus.Idle.toLegVisual()` equals `ConnectionLegVisual(ConnectionLegCategory.Down, "Not connected", "Relay: not connected")`. This is the executable AC#1 (Settings relay leg is not "Connected", not green).

**`RelayConnectionSupervisorTest.kt`:**
- Extend `toConnectionState_mapsLegacyCasesIdentityAndDaemonAbsentToOffline` (line 620) with `assertEquals(ConnectionState.Connected, RelayLinkStatus.Idle.toConnectionState())` — executable AC#3 (idle keeps the banner hidden).
- New tiny test: before any `connect()`, `supervisor.relayStatus.value == RelayLinkStatus.Idle` — AC#2 (initial state).
- Strengthen `benignUnpaired_doesNotDialAndStaysConnected` (line 305) and `reloadPerDial_laterNullRead_idlesAtConnectedWithoutDialing` (line 365): keep the existing `ConnectionState.Connected` assertions (banner unchanged) **and** add `assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)` — AC#1/#2 (unpaired + later-null-read relay leg is idle, not connected).
- Strengthen `close_tearsDownTransportAndStopsLoop` (line 393): after `close()`, add `assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)` alongside the existing `ConnectionState.Connected` assertion — AC#2 (post-close idle).
- **Unchanged (regression guard):** the live-socket assertion at line 481 (`emitUp()` → `RelayLinkStatus.Connected`) and every `Connecting`/`Reconnecting`/`DaemonAbsent`/`Offline` assertion stay exactly as-is — AC#4.

**Not needed:** `RelayRepositoryCoordinatorTest.kt:960` seeds its fake with `RelayLinkStatus.Connected` as a combine-input default; leave it — it exercises the coordinator's zip, not idle semantics. No change.

## Open questions

None blocking. One deferred design refinement, out of scope: if a future combined-status ticket introduces a neutral (grey) dot category, idle would be its natural first consumer — but that needs a new M3 token and is explicitly deferred by this ticket.
