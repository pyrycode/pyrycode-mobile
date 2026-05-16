# Warning color slot

A `warning` semantic color slot exposed on `MaterialTheme.colorScheme` for non-error attention states (token-budget percentages approaching limit, progress-bar fills approaching threshold). Material 3's `ColorScheme` is a `final` class with fixed slots — `warning` is not among them — so the slot is grafted on via the idiomatic Compose extension pattern rather than by extending `ColorScheme` itself.

## What ships

- A `WarningColors` data class (currently a single `val warning: Color` — extend with `onWarning` / `warningContainer` / `onWarningContainer` only when a consumer needs them).
- An `internal` `LocalWarningColors: ProvidableCompositionLocal<WarningColors>` created via `staticCompositionLocalOf` whose default value throws `error("WarningColors not provided. Wrap content in PyrycodeMobileTheme.")` — fail-fast on missing provider, never a silent `Color.Unspecified`.
- An extension property `val ColorScheme.warning: Color @Composable @ReadOnlyComposable get() = LocalWarningColors.current.warning` so call sites read `MaterialTheme.colorScheme.warning` — same surface as a real slot.
- Six per-mode `WarningColors(...)` instances in `Theme.kt` (`lightWarningColors`, `darkWarningColors`, plus the four medium/high-contrast variants — defined for parity with the existing `*ColorScheme` instances, even though the current `PyrycodeMobileTheme` `when` block only selects `lightWarningColors` / `darkWarningColors` and the medium/high-contrast schemes are likewise defined-but-not-routed).
- A `CompositionLocalProvider(LocalWarningColors provides warningColors)` wrapping the existing `MaterialTheme(...)` call inside `PyrycodeMobileTheme`, fed by a destructured `val (colorScheme, warningColors) = when { … }` so every composition path (light, dark, dynamic-light, dynamic-dark) supplies a non-null `WarningColors`.

The dynamic-color (wallpaper) branch falls back to the standard `darkWarningColors` / `lightWarningColors` — the wallpaper-derived `ColorScheme` does not contain a warning hint.

## Authoritative values

From the Figma `Schemes/Warning` variable in the `material-theme` collection (`fileKey=g2HIq2UyPhslEoHRokQmHG`, `VariableID:72:2`, authored 2026-05-15). The local `val` names mirror the existing `errorLight` / `errorDark` convention in `Color.kt`:

| Mode | `val` name | Hex |
|---|---|---|
| Light | `warningLight` | `#5C4C1E` |
| Light Medium Contrast | `warningLightMediumContrast` | `#403309` |
| Light High Contrast | `warningLightHighContrast` | `#251C00` |
| Dark | `warningDark` | `#D8B85A` |
| Dark Medium Contrast | `warningDarkMediumContrast` | `#E5CB80` |
| Dark High Contrast | `warningDarkHighContrast` | `#F3DDA8` |

The AC's `Warning80` / `WarningMC80` / `Warning30` naming (M3 tone numbers) was not adopted — the existing `Color.kt` uses mode-suffix names (`errorLight`, `errorDark`, etc.), and the Warning vals follow the same convention so they sort and group with their sibling `error*` blocks. The medium/high-contrast vals exist for the day `PyrycodeMobileTheme` routes accessibility settings through (not wired today; HC/MC `ColorScheme` instances are also defined-but-unselected).

## Usage

```kotlin
@Composable
fun TokenBudgetChip(percent: Int) {
    val tint = if (percent >= 80) MaterialTheme.colorScheme.warning
               else MaterialTheme.colorScheme.onSurfaceVariant
    // …
}
```

No call sites in production today — this slice is foundational. Downstream consumer tickets (#145 Status Sheet, #146 Conversation Thread token-budget chip per the ticket body) own the migration of the existing raw `#D8B85A` literals.

## Limits and how to extend

- **One field today.** If a consumer needs paired `onWarning` / `warningContainer` / `onWarningContainer` slots, extend the `WarningColors` data class with those fields, add the matching Figma-sourced color vals to `Color.kt` per-mode block, populate the six `WarningColors(...)` instances, and add the parallel `ColorScheme.onWarning` / `.warningContainer` / `.onWarningContainer` extension properties. The architect's spec drafted this 4-field shape; the developer dropped to one field per the literal AC. The seam is shaped to accept the extension without API churn at the call sites that already use `.warning`.
- **HC/MC not selected at runtime.** The four medium/high-contrast `WarningColors` instances are defined-but-not-routed. Whichever ticket wires the accessibility-contrast level through `PyrycodeMobileTheme` will be the one to extend the `when` block to pick them.
- **Missing provider is a crash, not a silent default.** Any composable that reads `MaterialTheme.colorScheme.warning` outside a `PyrycodeMobileTheme { ... }` wrap throws at first read. This is intentional — a silent `Color.Unspecified` would ship invisible UI to production. The error message names the fix.
- **`staticCompositionLocalOf`, not `compositionLocalOf`.** The value only changes when the theme changes (process-wide rare), so reads should not be tracked for recomposition. Same choice Material 3 makes for its own theme locals (`LocalAbsoluteTonalElevation`, etc.).

## Pattern for future custom color slots

This is the project's first non-Material custom color slot, and the chosen mechanism (extension property + private `CompositionLocal` + provider inside `PyrycodeMobileTheme`) is the precedent for any future ones (e.g. brand-specific `success`, `info`). The pattern preserves `MaterialTheme.colorScheme.<token>` as the call-site surface and does not interfere with `ColorScheme` equality, copy, or future Material additions.

## Related

- Implementation notes: [`codebase/119.md`](../codebase/119.md)
- Theme primitive: `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme`, plus the six per-mode `WarningColors` instances.
- Color tokens: `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` — six `warning*` vals next to the matching `error*` blocks.
- Sibling theming work: [App preferences](app-preferences.md) (`themeMode`, `useWallpaperColors`), [Settings screen](settings-screen.md) (Appearance section), [Settings ViewModel](settings-viewmodel.md).
