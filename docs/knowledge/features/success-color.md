# Success color slot

A green `success` ("up") semantic color slot exposed on `MaterialTheme.colorScheme` for positive
connection/health and attention states: the green dot on the [connection-status
line](connection-status-line.md)'s "up" legs, and, since #878, a conversation tree row's
`Unread` [attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878) fill.
Like [`warning`](warning-color.md), `success` is not
one of Material 3's fixed `ColorScheme` slots, so it is grafted on via the idiomatic Compose
extension pattern rather than by extending the `final` `ColorScheme`.

This is the **second** custom color slot in the project and the first realization of the "future
ones (e.g. brand-specific `success`, `info`)" the [`warning` slot](warning-color.md) anticipated. It
mirrors `WarningColors` **one-for-one** — a deliberate sibling, not an additive field on
`WarningColors` (see [Why a sibling](#why-a-sibling-not-a-warningcolors-field)). Introduced in
[#397](../codebase/397.md).

## What ships

`ui/theme/SuccessColors.kt` (its own file per the ktlint single-public-class rule):

- A `@Immutable data class SuccessColors(val success: Color)` — one field today; extend with
  `onSuccess` / `successContainer` / `onSuccessContainer` only when a consumer needs them (the same
  one-field-until-needed posture as `WarningColors`).
- An `internal val LocalSuccessColors: ProvidableCompositionLocal<SuccessColors>` via
  `staticCompositionLocalOf` whose default **throws** `error("SuccessColors not provided. Wrap
  content in PyrycodeMobileTheme.")` — fail-fast on a missing provider, never a silent
  `Color.Unspecified` that would ship an invisible dot.
- An extension property `val ColorScheme.success: Color @Composable @ReadOnlyComposable get() =
  LocalSuccessColors.current.success` so call sites read `MaterialTheme.colorScheme.success` — the
  same surface as a real M3 slot.

Wired through the same three sites as `WarningColors`:

- `ui/theme/Color.kt` — two palette literals beside the `warning*` ones: `successLight =
  Color(0xFF316B2B)` and `successDark = Color(0xFFA6D388)`.
- `ui/theme/Theme.kt` — `private val lightSuccessColors = SuccessColors(successLight)` /
  `darkSuccessColors`, a `val successColors = if (darkTheme) darkSuccessColors else
  lightSuccessColors` selected after the existing `when`, and `LocalSuccessColors provides
  successColors` added to the existing `CompositionLocalProvider` (vararg) alongside
  `LocalWarningColors`. `PyrycodeMobileTheme`'s signature is unchanged.

## Authoritative values

**Placeholders, design-owed.** Unlike [`warning`](warning-color.md) (sourced from a locked Figma
`Schemes/Warning` variable), `success` was conceived as a live-testing diagnostic affordance
*after* the design lock — no Figma `Schemes/Success` variable exists yet. The two values are chosen
for adequate light/dark contrast and will be reconciled when the design draws the connection-status
line under Settings frame `17-2` (same design-later resolution as [#343](../codebase/343.md)).

| Mode | `val` name | Hex | Rationale |
|---|---|---|---|
| Light | `successLight` | `#316B2B` | medium-dark green (≈green tone 40); contrast on `surfaceLight` `#F8F9FF`. Mirrors `warningLight` being a *dark* amber for the light scheme. |
| Dark | `successDark` | `#A6D388` | light green (≈green tone 80); contrast on `surfaceDark` `#101418`. Mirrors `warningDark` being a *light* amber for the dark scheme. |

No medium/high-contrast variants — `PyrycodeMobileTheme` only selects light/dark for `WarningColors`
(the contrast holders there are defined-but-dead), so `success` matches that scope. The two literals
are isolated in `Color.kt`, so a design reconciliation is a two-line edit.

## Why a sibling, not a `WarningColors` field

Extending `WarningColors` to carry a green `success` field would leave a holder literally named
`WarningColors` owning an unrelated colour, and consumers would read `colorScheme.warning` and a
green value from the same `WarningColors.current` — semantically muddled. A separate `SuccessColors`
keeps `import …theme.warning` / `import …theme.success` cleanly separate and mirrors the precedent
exactly. **The pattern for the next slot (`info`, etc.): a new sibling holder, never a wider
existing one.**

## Usage

```kotlin
val dot = when (category) {
    Up         -> MaterialTheme.colorScheme.success   // green
    InProgress -> MaterialTheme.colorScheme.warning   // amber
    Down       -> MaterialTheme.colorScheme.error     // red
}
```

Consumers: the [connection-status line](connection-status-line.md)'s `ConnectionLegCategory.color()`
resolver ([#397](../codebase/397.md)) — `Up → success`; and, since #878, `ConversationStatusDot`'s
`Unread` fill (see [Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878)).

## Related

- Precedent (mirrored one-for-one): [Warning color](warning-color.md) ([#119](../codebase/119.md)) —
  the first custom color slot and the pattern for all future ones.
- Consumers: [Connection status line](connection-status-line.md) ([#397](../codebase/397.md));
  [Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878) (#878).
- Implementation notes: [`codebase/397.md`](../codebase/397.md).
- Theme primitive: `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme`,
  the two `SuccessColors` instances. Palette: `…/ui/theme/Color.kt` — `successLight` / `successDark`.
