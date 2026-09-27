# Success color slot

A green `success` ("up") semantic color slot exposed on `MaterialTheme.colorScheme` for positive
connection/health and attention states: the green dot on the [connection-status
line](connection-status-line.md)'s "up" legs, the background task panel's Completed tag, and,
since #878, a conversation tree row's `Unread` [attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878) fill.
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

- `ui/theme/Color.kt` — `successLight = Color(0xFF316B2B)`, `successDark =
  Color(0xFF2FC038)` and `successDarkDynamic = Color(0xFFA6D388)` beside the `warning*` values.
- `ui/theme/Theme.kt` — `PyrycodeMobileTheme` selects static dark, dynamic dark or light
  `SuccessColors` before providing `LocalSuccessColors` alongside `LocalWarningColors`.
  Its public signature is unchanged.

## Authoritative values

The static dark value follows the live Figma `Schemes/Success` binding (`#2FC038`) on
sidebar node [`132:3902`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-3902),
inspected on 2026-09-28. Light and dynamic dark retain their earlier values; the
sidebar binding does not redefine those palette paths.

| Mode | `val` name | Hex | Rationale |
|---|---|---|---|
| Light | `successLight` | `#316B2B` | Existing light value. |
| Static dark | `successDark` | `#2FC038` | Live sidebar `Schemes/Success` binding. |
| Dynamic dark | `successDarkDynamic` | `#A6D388` | Existing wallpaper-colour-path value. |

No medium/high-contrast variants — `PyrycodeMobileTheme` only selects light/dark for `WarningColors`
(the contrast holders there are defined-but-dead), so `success` matches that scope.
`SharedDarkColourRolesTest` checks all three success paths, preventing a static-dark
design update from silently changing wallpaper colours.

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

The first consumer was the [connection-status line](connection-status-line.md)'s
`ConnectionLegCategory.color()` resolver ([#397](../codebase/397.md)) — `Up → success`. The [background
task panel](mobile-modal.md#callers)'s `TaskStatusTag` ([#1041](https://github.com/pyrycode/pyrycode-mobile/issues/1041))
is the second: its Completed style reads `colorScheme.success` as the dot/label colour on a 16% tint of
itself as the pill fill — no new field, since a flat `success` colour is enough to derive both.
Since #878, `ConversationStatusDot`'s `Unread` fill reads it too (see
[Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878)).

## Related

- Precedent (mirrored one-for-one): [Warning color](warning-color.md) ([#119](../codebase/119.md)) —
  the first custom color slot and the pattern for all future ones.
- Consumers: [Connection status line](connection-status-line.md) ([#397](../codebase/397.md)), the
  [background task panel](mobile-modal.md#callers)'s `TaskStatusTag` ([#1041](https://github.com/pyrycode/pyrycode-mobile/issues/1041))
  and the tree row's [Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878) (#878).
- Implementation notes: [`codebase/397.md`](../codebase/397.md).
- Theme primitive: `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme`,
  the three `SuccessColors` instances. Palette: `…/ui/theme/Color.kt` — `successLight` /
  `successDark` / `successDarkDynamic`.
