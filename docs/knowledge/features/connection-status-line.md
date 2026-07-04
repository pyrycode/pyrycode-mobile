# Connection status line — the two-part `● Relay   ● Pyrycode` component

A reusable, stateless composable that renders the combined
[`ConnectionStatus`](connection-status.md) `{relay, pyrycode}` model as a two-part status line —
two coloured dots, each labelled with its leg name and a textual state word:

```
●  Relay     ●  Pyrycode
```

It exists because the app had **no positive connection indicator** — `Connected` was conveyed only
by the *absence* of the [`ConnectionBanner`](connection-banner.md), so a user couldn't glance and
confirm "I'm connected." The path is **phone → relay → pyrycode daemon** — two independently-failing
legs — and a single collapsed dot lies whenever they disagree. The motivating case (live testing
**2026-06-08**): a daemon 4404-looping behind a base relay URL presented only as a generic "Offline"
with no hint of *which* leg was broken. This line makes both legs glanceable and self-diagnosing, so
**relay-reachable-but-no-daemon** reads as "Relay = up (green), Pyrycode = down (red)" — the compound
*is* the diagnostic.

Built in [#397](../codebase/397.md) (the **component/build slice** of #390). This ticket builds the
reusable, tested, previewable component + its green [`success` token](success-color.md); the **live
wiring** into Settings (sourcing + collecting the flow) is the sibling slice **#398**
(`blockedBy #397`). Package `de.pyryco.mobile.ui.conversations.components`, beside
[`ConnectionBanner`](connection-banner.md) whose idiom it follows.

## Design — category, then token

The component's load-bearing logic is a **pure mapper** that resolves each leg to a presentation
triple, kept deliberately free of Compose so it is JVM-unit-testable:

```kotlin
internal enum class ConnectionLegCategory { Up, InProgress, Down }

internal data class ConnectionLegVisual(
    val category: ConnectionLegCategory,
    val label: String,             // visible state word, e.g. "Connected"
    val contentDescription: String, // leg + state for TalkBack, e.g. "Relay: connected"
)

internal fun RelayLinkStatus.toLegVisual(): ConnectionLegVisual      // 6 cases
internal fun PyrycodeLinkStatus.toLegVisual(): ConnectionLegVisual   // 3 cases
```

The **category** is the product behaviour; the concrete M3 colour token is resolved at one separate
Compose site, so the mapping stays pure and total. Both mappers are an **exhaustive `when` with no
`else`** — a future `RelayLinkStatus`/`PyrycodeLinkStatus` case becomes a compile error that forces a
mapping decision.

### Mapping contract

Relay leg ([`RelayLinkStatus`](relay-link-status.md)):

| case | category | label | contentDescription |
|---|---|---|---|
| `Connected` | `Up` (green) | `"Connected"` | `"Relay: connected"` |
| **`Idle`** | **`Down` (red)** | `"Not connected"` | `"Relay: not connected"` |
| `Connecting` | `InProgress` (amber) | `"Connecting…"` | `"Relay: connecting"` |
| `Reconnecting(s)` | `InProgress` (amber) | `"Reconnecting"` | `"Relay: reconnecting"` |
| **`DaemonAbsent`** | **`Up` (green)** | `"Reachable"` | `"Relay: reachable, no daemon"` |
| `Offline` | `Down` (red) | `"Offline"` | `"Relay: offline"` |

Pyrycode leg (`PyrycodeLinkStatus`):

| case | category | label | contentDescription |
|---|---|---|---|
| `Handshaking` | `InProgress` (amber) | `"Handshaking…"` | `"Pyrycode: handshaking"` |
| `Connected` | `Up` (green) | `"Connected"` | `"Pyrycode: connected"` |
| `Down` | `Down` (red) | `"Down"` | `"Pyrycode: down"` |

**`DaemonAbsent → Up` is the load-bearing invariant** (the ticket's AC#2): the relay is reachable —
the missing daemon is the *pyrycode* leg's story, not a relay failure, so the Relay dot stays green.
A distinct `"Reachable"` label (vs `"Connected"`) keeps the text/a11y channel honest while staying
green.

**`Idle → Down` is the mirror invariant ([#499](../codebase/499.md)):** the deliberately-not-dialing
state (initial / unpaired / `close()`) reads a non-green "Not connected" here, **diverging** from the
banner mapper's `Idle → ConnectionState.Connected` (banner hidden). Same `Down` category as `Offline`
but a distinct label — the exact "share the category, diverge on the text" pattern `Connected` and
`DaemonAbsent` use to share `Up`. This is what stopped the Settings relay leg reading a false green
"Connected" while unpaired. See [Relay link status](relay-link-status.md) § `Idle`. The `Reconnecting(secondsRemaining)` countdown is **not** surfaced in the label (design-owed
placement); fold `s` into both label and cd together if it is ever adopted, so the per-case test
stays deterministic.

### Category → token (the one colour site)

```kotlin
@Composable @ReadOnlyComposable
internal fun ConnectionLegCategory.color(): Color = when (this) {
    Up         -> MaterialTheme.colorScheme.success   // the new green token (#397)
    InProgress -> MaterialTheme.colorScheme.warning   // existing amber
    Down       -> MaterialTheme.colorScheme.error     // M3 red
}
```

This is the **only** colour-resolution site — every dot colour comes from an M3 semantic token, no
hardcoded hex in the component (the green hex lives in [`Color.kt`](success-color.md), mirroring
`warningLight`/`warningDark`). The split (category in pure code, token at one Compose site) mirrors
[`ThreadStatusRow`](thread-status-row.md)'s `tokenPercentColor`.

## The component

```kotlin
@Composable
fun ConnectionStatusLine(status: ConnectionStatus, modifier: Modifier = Modifier)
```

- **Stateless** — takes an immutable `ConnectionStatus`, holds no state, runs no coroutine/side
  effect. The live flow is sourced and collected by the consumer (#398), never here.
- A `Row` (`Arrangement.spacedBy(24.dp)`, `CenterVertically`) of two private
  `StatusLeg(name, visual)` children — "Relay" from `status.relay.toLegVisual()`, "Pyrycode" from
  `status.pyrycode.toLegVisual()`.
- Each `StatusLeg` is a `Row` of **[8.dp coloured dot] [name] [state label]**:
  - the dot is `Box(Modifier.size(8.dp).background(visual.category.color(), CircleShape))` — colour
    is **redundant** with the text, never the only signal;
  - **name** (`Text`, `labelMedium`/`onSurface`) and **state label** (`Text`, `labelSmall`/
    `onSurfaceVariant`) make status legible **without colour perception**;
  - the leg `Row` carries `Modifier.clearAndSetSemantics { contentDescription =
    visual.contentDescription }` so TalkBack announces **one clean phrase per leg** ("Relay:
    connected") instead of fragmented child texts.
- **Skippable**: `ConnectionStatus` and both sealed hierarchies (incl. `Reconnecting(Int)`) are
  stable, and the component captures no unstable lambda — so recomposition is clean.

### Previews

A private `ConnectionStatusLinePreviewMatrix` renders the four meaningful combinations, wrapped by a
Light + Dark `@Preview` pair at `widthDp = 412` (the `ConnectionBanner` idiom):

1. both up — `ConnectionStatus(Connected, Connected)`
2. **relay up / pyrycode down** — `ConnectionStatus(DaemonAbsent, Down)` (green relay + red pyrycode;
   visually proves the AC#2 diagnostic)
3. both down — `ConnectionStatus(Offline, Down)`
4. reconnecting / connecting — `ConnectionStatus(Reconnecting(12), Handshaking)`

## Testing

Unit-only (`./gradlew test`, JVM, no device), `ConnectionStatusLineTest.kt` — plain `org.junit` +
`assertEquals`, the [`ThreadScreenMapperTest`](thread-screen.md) idiom. One `@Test` per sealed case
(all 9 — the 6 relay cases incl. #499's `Idle`, plus the 3 pyrycode cases), each asserting the **full
triple** so a copy change is caught and moved deliberately; the AC#2 invariant is its own named test
(`relayDaemonAbsent_mapsToUp_neverDown`), and #499's is `relayIdle_mapsToDown_notConnected`. The trivial
category→token resolver and the layout are **preview-verified**, not instrumented — matching
`ConnectionBanner` (no unit test there either). The pure mapper carries the test weight.

## Live wiring (#398)

Wired into Settings in [#398](../codebase/398.md) (slice B of #390): the
[`RelayRepositoryCoordinator.connectionStatus`](relay-repository-coordinator.md) flow is forwarded
verbatim onto [`SettingsViewModel`](settings-viewmodel.md), injected via Koin off the concrete
coordinator, collected lifecycle-aware at the `MainActivity` Settings host, and this component is
rendered under the **Server** row in the **Connection** section of the
[Settings screen](settings-screen.md). Both legs now update live. In the default debug build
(`USE_RELAY_REPOSITORY` OFF) the line honestly reads `Offline / Down` — there is no real relay link
in that build; against a live, paired daemon the dots reflect reality.

## Limits / not yet done

- **Design-owed visuals (residual).** `## Figma` was N/A in #397 (conceived after the design lock);
  the design landed 2026-06-08 (frame `17-2`, node `90-4`) and #398 placed the component under the
  Server row. The green shades and dot size are still placeholders to reconcile against the lock; the
  micro-spacing under the Server row drew a non-blocking code-review NIT on #398 (the `top/bottom`
  padding stacks on the `ListItem`'s own inset, so `17-2` couples it slightly tighter — left as a
  visual-polish follow-up). The *behaviour* (leg → category + label) is locked; the pixels are not.

## Related

- The model it renders (verbatim): [Connection status](connection-status.md)
  (`ConnectionStatus {relay, pyrycode}`, [#392](../codebase/392.md)).
- Legs: [Relay link status](relay-link-status.md) (`RelayLinkStatus`, [#391](../codebase/391.md)) +
  `PyrycodeLinkStatus` (in [Connection status](connection-status.md)).
- The green token: [Success color](success-color.md) ([#397](../codebase/397.md)).
- Component idioms followed: [Connection banner](connection-banner.md) (stateless-over-a-sealed-type
  + private preview-matrix), [Thread status row](thread-status-row.md) (category→token resolver).
- Implementation notes: [`codebase/397.md`](../codebase/397.md) (build slice),
  [`codebase/398.md`](../codebase/398.md) (live wiring),
  [`codebase/499.md`](../codebase/499.md) (the `Idle → Down`/"Not connected" mapping — closes the false
  green on the relay leg while unpaired/idle).
- Live consumer (shipped): [Settings ViewModel](settings-viewmodel.md) +
  [Settings screen](settings-screen.md) via [#398](../codebase/398.md) (`blockedBy #397`).
