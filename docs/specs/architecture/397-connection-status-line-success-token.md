# Spec #397 — green "up" status token + two-part `ConnectionStatusLine` component

**Ticket:** [#397](https://github.com/pyrycode/pyrycode-mobile/issues/397) — `feat(ui/theme+components)` · size:s · split from #390 (slice A; #398 wires it into Settings, blockedBy #397)

## Design source

N/A — new live-testing diagnostic affordance conceived 2026-06-08, after the design lock; not yet in the locked Figma. The Settings frame [`17-2`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2) has a Connection section with a server row (`17:13`) but no Relay·Pyrycode status line is drawn. **Design owed:** the line lands under the server row in `17-2` and the visuals reconcile then. Build now against the inline layout + token-category contract below (same design-later resolution as #343). The visual-fidelity check is intentionally skipped; the green shades and micro-spacing here are placeholders the design will pin later — the *behaviour* (which leg → which colour-category + label) is the load-bearing part.

## Context

The app has no positive connection indicator — `Connected` is conveyed only by the **absence** of `ConnectionBanner`, so a user cannot glance and confirm "I'm connected." Live phone↔daemon testing on 2026-06-08 surfaced a daemon-not-on-relay bug (daemon 4404-looping behind a base relay URL) that presented only as a generic "Offline" with no hint of *which* leg was broken. The path is **phone → relay → pyrycode daemon** — two legs that fail independently.

This ticket builds the **reusable, tested, previewable** `● Relay   ● Pyrycode` component plus the one new theme colour it needs (a green "up" token — the palette today is blue/orange/red + custom amber `warning`, no green). It does **not** wire the live data path or touch the Settings screen — that is #398.

The combined model it renders is already live on `main` (from #391/#392): `ConnectionStatus(relay: RelayLinkStatus, pyrycode: PyrycodeLinkStatus)`. Consume it verbatim; do **not** redefine it.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/theme/WarningColors.kt` (whole file, 25 lines) — **the precedent to mirror**: `@Immutable` holder + `internal val Local…` `staticCompositionLocalOf` + `val ColorScheme.<token>` `@Composable @ReadOnlyComposable` extension. `SuccessColors.kt` is a line-for-line analog.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:249-303` — how `WarningColors` instances are built (`lightWarningColors`/`darkWarningColors`), selected in the `when` of `PyrycodeMobileTheme`, and provided via `CompositionLocalProvider`. The success token threads through the **same three sites**.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:20,131` — where palette literals live (`warningLight`/`warningDark` are the pattern); add `successLight`/`successDark` next to them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` (whole file) — **the component idiom**: a stateless composable that branches on a connection sealed type, resolves colour from `MaterialTheme.colorScheme.*`, splits a private content composable, and ends with a private preview-matrix + Light/Dark `@Preview` pair. Note it uses **inline string literals** for status text ("Connecting…", "Offline — tap to retry") — *not* `stringResource`. Mirror that; the mapper returns plain `String`s so it stays JVM-unit-testable.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt:80-88` — `tokenPercentColor`: the idiom for a small `@Composable` that resolves one of {`onSurfaceVariant`, `warning`, `error`} from a category. The category→token resolver mirrors this; here `success`/`warning`/`error`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenMapperTest.kt:1-12` — **the pure-mapper test idiom**: plain `org.junit` + `assertEquals`, no Robolectric/device. The mapper tests mirror this exactly.
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` + `PyrycodeLinkStatus.kt` + `ConnectionStatus.kt` (whole files) — the input types. **Read-only; do not modify.** `RelayLinkStatus.DaemonAbsent` = "relay reachable, no daemon registered behind it" — an **UP** state on the Relay dot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt:42` — confirms the `import de.pyryco.mobile.ui.theme.warning` consumption shape you'll mirror with `import …theme.success`.

## Design

Four production files (3 are the irreducible theme-token plumbing the AC mandates; the 4th co-locates the small presentation mapper with its sole consumer, the component). One test file.

### A. The green "up" token — new `SuccessColors.kt` sibling

**Decision:** a **new `SuccessColors` holder**, not an additive field on `WarningColors`. Rationale: extending `WarningColors` to carry a green `success` field would leave a holder literally named `WarningColors` owning an unrelated colour, and consumers would read `colorScheme.warning` and a green value from the same `WarningColors.current` — semantically muddled. A sibling keeps `import …theme.warning` / `import …theme.success` cleanly separate and mirrors the precedent one-to-one.

**`ui/theme/SuccessColors.kt`** (new) — line-for-line analog of `WarningColors.kt`:

```kotlin
@Immutable data class SuccessColors(val success: Color)
internal val LocalSuccessColors: ProvidableCompositionLocal<SuccessColors> =
    staticCompositionLocalOf { error("SuccessColors not provided. Wrap content in PyrycodeMobileTheme.") }
val ColorScheme.success: Color @Composable @ReadOnlyComposable get() = LocalSuccessColors.current.success
```

**`ui/theme/Color.kt`** (modified) — add two literals beside the `warning*` ones (placeholder M3 green tonal values; design-owed):

| literal | value | rationale |
|---|---|---|
| `successLight` | `Color(0xFF316B2B)` | medium-dark green (≈green tone 40); contrast on `surfaceLight` `#F8F9FF`. Mirrors `warningLight` being a *dark* amber for the light scheme. |
| `successDark` | `Color(0xFFA6D388)` | light green (≈green tone 80); contrast on `surfaceDark` `#101418`. Mirrors `warningDark` being a *light* amber for the dark scheme. |

(No medium/high-contrast variants — `PyrycodeMobileTheme` only selects light/dark for `WarningColors`, so success matches that scope per AC: "for both light and dark schemes.")

**`ui/theme/Theme.kt`** (modified) — three additive touches mirroring `WarningColors`:
1. `private val lightSuccessColors = SuccessColors(successLight)` / `private val darkSuccessColors = SuccessColors(successDark)` (beside `lightWarningColors`/`darkWarningColors`, line ~249).
2. In `PyrycodeMobileTheme`'s `when`, select `successColors` alongside `warningColors` in each branch (dynamic light/dark + plain light/dark), so the destructuring becomes a triple (or add a parallel `val successColors = if (darkTheme) darkSuccessColors else lightSuccessColors` after the `when` — developer's call; the `when` already keys on the same `darkTheme`/dynamic conditions).
3. Add `LocalSuccessColors provides successColors` to the existing `CompositionLocalProvider(LocalWarningColors provides warningColors)` (it takes vararg providers).

### B. Presentation mapper + component — `ConnectionStatusLine.kt` (new)

One file, `ui/conversations/components/ConnectionStatusLine.kt`, holding the pure mapper, its tiny value types, the category→colour resolver, the component, and previews. (ktlint's single-top-level-declaration filename rule does not fire — the file has multiple top-level declarations.)

**Pure mapper (no Compose import; the unit-tested core):**

```kotlin
internal enum class ConnectionLegCategory { Up, InProgress, Down }
internal data class ConnectionLegVisual(
    val category: ConnectionLegCategory,
    val label: String,            // visible state word, e.g. "Connected"
    val contentDescription: String, // leg + state for TalkBack, e.g. "Relay: connected"
)
internal fun RelayLinkStatus.toLegVisual(): ConnectionLegVisual
internal fun PyrycodeLinkStatus.toLegVisual(): ConnectionLegVisual
```

**Mapping contract** (recommended copy — exact wording is design-owed and may shift when Figma lands; the **category** column is the load-bearing product behaviour, the label/cd move with the tests):

Relay leg (`RelayLinkStatus`):

| case | category | label | contentDescription |
|---|---|---|---|
| `Connected` | `Up` | `"Connected"` | `"Relay: connected"` |
| `Connecting` | `InProgress` | `"Connecting…"` | `"Relay: connecting"` |
| `Reconnecting(s)` | `InProgress` | `"Reconnecting"` | `"Relay: reconnecting"` |
| `DaemonAbsent` | **`Up`** | `"Reachable"` | `"Relay: reachable, no daemon"` |
| `Offline` | `Down` | `"Offline"` | `"Relay: offline"` |

Pyrycode leg (`PyrycodeLinkStatus`):

| case | category | label | contentDescription |
|---|---|---|---|
| `Handshaking` | `InProgress` | `"Handshaking…"` | `"Pyrycode: handshaking"` |
| `Connected` | `Up` | `"Connected"` | `"Pyrycode: connected"` |
| `Down` | `Down` | `"Down"` | `"Pyrycode: down"` |

`DaemonAbsent → Up` is **AC#2** — the relay is up; the absence is the pyrycode leg's story. Distinct label "Reachable" (vs "Connected") gives the text/a11y channel an honest description while staying green. `Reconnecting(secondsRemaining)` may optionally surface the countdown in its label/cd (`"Reconnecting ${s}s"`) — not required; if added, fold `s` into both label and cd so the per-case test stays deterministic.

**Category → semantic-colour-token resolver** (the only colour-resolution site; mirrors `tokenPercentColor`):

```kotlin
@Composable @ReadOnlyComposable
internal fun ConnectionLegCategory.color(): Color  // Up→colorScheme.success, InProgress→.warning, Down→.error
```

No hardcoded hex anywhere in the component — every dot colour comes from an M3 semantic token (AC#3).

**Component:**

```kotlin
@Composable
fun ConnectionStatusLine(status: ConnectionStatus, modifier: Modifier = Modifier)
```

- Stateless; takes a `ConnectionStatus`, renders a single `Row` (`verticalAlignment = CenterVertically`) with two leg children — `"Relay"` from `status.relay.toLegVisual()`, `"Pyrycode"` from `status.pyrycode.toLegVisual()` — separated by horizontal spacing.
- Private `StatusLeg(name: String, visual: ConnectionLegVisual, modifier)`: a `Row` of **[coloured dot] [name] [state label]**, where
  - the **dot** is a `Box(Modifier.size(8.dp).background(visual.category.color(), CircleShape))` — colour is the only colour signal, redundant with the text;
  - **name** (`Text(name)`, `labelMedium`/`onSurface`) and **state label** (`Text(visual.label)`, `labelSmall`/`onSurfaceVariant`) make status legible **without colour perception** (AC: not colour-only);
  - the leg `Row` carries `Modifier.clearAndSetSemantics { contentDescription = visual.contentDescription }` so TalkBack announces one clean phrase per leg ("Relay: connected") instead of fragmented child texts.
- Exact spacing/typography is a small developer choice to reconcile with the design-owed Figma; keep it compact enough for both legs to share one line at ~412dp.

**Previews** (mirror `ConnectionBanner`'s private-matrix + Light/Dark `@Preview` pair) — a private `ConnectionStatusLinePreviewMatrix` rendering the four AC-required combinations in a `Column`, wrapped by two `@Preview` functions in `PyrycodeMobileTheme(darkTheme = false)` and `… = true)`:

1. both up — `ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)`
2. **relay up / pyrycode down (the motivating diagnostic)** — `ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)` (green relay + red pyrycode — visually proves AC#2)
3. both down — `ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)`
4. reconnecting/connecting — `ConnectionStatus(RelayLinkStatus.Reconnecting(secondsRemaining = 12), PyrycodeLinkStatus.Handshaking)`

## State + concurrency model

None. Pure presentation: a stateless composable over an immutable input + a pure mapper. No `ViewModel`, `StateFlow`, `viewModelScope`, coroutine, or side effect. The live `ConnectionStatus` flow is sourced and collected by the consumer slice (#398), not here.

## Error handling

None at this layer — the mapper is total over both sealed hierarchies (exhaustive `when`, no `else`), so adding a future `RelayLinkStatus`/`PyrycodeLinkStatus` case is a compile error that forces a mapping decision (the desired failure mode). No network/IO/parse/permission surface.

## Testing strategy

**Unit only** (`./gradlew test` — JVM, no device), one file `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLineTest.kt`, mirroring `ThreadScreenMapperTest` (plain `org.junit` + `assertEquals`). **Test-first: red → green.** Scenarios:

- `RelayLinkStatus.Connected.toLegVisual()` → `(Up, "Connected", "Relay: connected")`.
- `RelayLinkStatus.Connecting.toLegVisual()` → `category == InProgress`.
- `RelayLinkStatus.Reconnecting(12).toLegVisual()` → `category == InProgress` (and, if countdown adopted, label/cd contain `12`).
- **`RelayLinkStatus.DaemonAbsent.toLegVisual()` → `category == Up`** — the AC#2 invariant, asserted as its own named test (green, never `Down`/`error`).
- `RelayLinkStatus.Offline.toLegVisual()` → `category == Down`.
- `PyrycodeLinkStatus.Handshaking.toLegVisual()` → `category == InProgress`.
- `PyrycodeLinkStatus.Connected.toLegVisual()` → `category == Up`.
- `PyrycodeLinkStatus.Down.toLegVisual()` → `category == Down`.
- Every case asserts the full triple (category + label + contentDescription) so a copy change is caught and moved deliberately.

**No instrumented/Compose-UI test** — the category→token resolver and layout are preview-verified, matching `ConnectionBanner` (which has no unit test, only previews). The pure mapper carries the test weight; `./gradlew check` (unit tests + lint + spotless) must be green (final AC).

## Open questions

- **Countdown in `Reconnecting` label** — left optional. Recommendation: omit for the first cut (the design-owed Figma may dictate placement); the test asserts only `category == InProgress` unless the developer opts in, in which case fold `secondsRemaining` into both label and cd.
- **Green shades** — `0xFF316B2B` / `0xFFA6D388` are placeholders chosen for adequate light/dark contrast; the design-owed Figma reconciliation may replace them. They are isolated in `Color.kt`, so a later change is a two-literal edit.

## Scope note (self-check)

Production source files prescribed: `SuccessColors.kt` (new), `Color.kt` (modified), `Theme.kt` (modified), `ConnectionStatusLine.kt` (new) = **4** — under the 5-file gate. The three theme files are the irreducible token-plumbing the AC mandates (`WarningColors` precedent: literal → holder → provider); the mapper is co-located with its sole consumer rather than given a 5th file, which is its honest size, not a split-dodge. No consumer call sites change (this is the build slice; #398 is the wiring slice). No new state machine, repository, or wire type.
