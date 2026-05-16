# Spec 119 — Warning color slot on MaterialTheme.colorScheme

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` — full file. The 24 existing `error*` vals (`errorLight`, `onErrorLight`, `errorContainerLight`, `onErrorContainerLight`, then `*LightMediumContrast`, `*LightHighContrast`, `*Dark`, `*DarkMediumContrast`, `*DarkHighContrast`) are the exact shape to mirror for `warning*`. Add new vals next to the matching `error*` set in each of the six mode blocks; keep the file's mode-grouped ordering.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:14-246` — the six `lightColorScheme(...)` / `darkColorScheme(...)` blocks (`lightScheme`, `darkScheme`, `mediumContrastLightColorScheme`, `highContrastLightColorScheme`, `mediumContrastDarkColorScheme`, `highContrastDarkColorScheme`). Each will get a sibling `WarningColors(...)` instance defined next to it (warning isn't a slot on Material 3's `ColorScheme` constructor, so it cannot go inside the existing `lightColorScheme(...)` call — it sits next to it).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:248-289` — the `ColorFamily` data class (precedent for an `@Immutable` color grouping in this package) and the `PyrycodeMobileTheme` composable. Note how it picks a `colorScheme` via the `when` block and passes it to `MaterialTheme(...)`; we will pick a parallel `WarningColors` in the same `when` and wrap `MaterialTheme(...)` in a `CompositionLocalProvider` that supplies it.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt` — confirm-only. The composition root wraps in `PyrycodeMobileTheme { ... }`; placing the provider inside the theme composable means PyryApp does not change.
- `app/build.gradle.kts` (test-deps section) — confirm `androidx.compose.ui.test.junit4` and `androidx.compose.ui.test.manifest` are already wired (they are, per existing scanner / channel-list compose tests). No new test dependency is required.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` (or any existing `androidTest` Compose test) — the project's `createAndroidComposeRule<...>` / `setContent { PyrycodeMobileTheme { ... } }` idiom is the template the new theme test should follow.
- Figma file `g2HIq2UyPhslEoHRokQmHG`, variable `Schemes/Warning` (`VariableID:72:2`) — read the six mode values directly via `mcp__plugin_figma_figma__get_variable_defs` before writing the colour literals. The ticket pre-seeds two (Dark = `#D8B85A`, Light = `#5C4C1E`); the other four (`LightMediumContrast`, `LightHighContrast`, `DarkMediumContrast`, `DarkHighContrast`) come from the same variable's mode bindings — do not invent them.

## Design source

N/A — this ticket introduces a theme token, not a screen-level UI change. The token's authoritative values live in the Figma `Schemes/Warning` variable (see Files to read first). Downstream consumer tickets carry their own Figma node references.

## Context

Material 3's `androidx.compose.material3.ColorScheme` is a final class whose slots are fixed (`primary`, `secondary`, `tertiary`, `error`, surfaces, outlines, etc.) — `warning` is not on the list, and the class cannot be subclassed or extended with new constructor params. Downstream tickets (Status Sheet, Conversation Thread token-budget chip) need a `warning` semantic to theme attention-without-error states. Today they would have to hard-code `#D8B85A`; this ticket creates the token they will consume.

The chosen mechanism is the idiomatic Compose extension pattern: a private `CompositionLocal` carrying a small `WarningColors` value, plus extension properties on `ColorScheme` so the call site reads `MaterialTheme.colorScheme.warning` — identical surface to a real slot. This is the same pattern Material 3 itself uses for `LocalAbsoluteTonalElevation`, and what e.g. `Surface`'s own extension surfaces use behind the scenes; it does not interfere with `ColorScheme` equality, copy, or any future Material additions.

## Design

### New file: `app/src/main/java/de/pyryco/mobile/ui/theme/WarningColors.kt`

A small file owning the data class, the CompositionLocal, and the four extension properties.

```kotlin
package de.pyryco.mobile.ui.theme

@Immutable
data class WarningColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LocalWarningColors: ProvidableCompositionLocal<WarningColors> =
    staticCompositionLocalOf { error("WarningColors not provided. Wrap content in PyrycodeMobileTheme.") }

val ColorScheme.warning: Color
    @Composable @ReadOnlyComposable get() = LocalWarningColors.current.warning

// + parallel ColorScheme.onWarning, ColorScheme.warningContainer, ColorScheme.onWarningContainer
```

Notes on choices:

- `@Immutable` + `data class` matches the existing `ColorFamily` precedent in `Theme.kt`.
- `staticCompositionLocalOf` (not `compositionLocalOf`) — the value changes only when the theme changes (process-wide), so we want reads not to trigger recomposition tracking. Same choice Material 3 makes for its own theme locals.
- Default value throws — there is no sensible app-wide fallback, and a silent default would hide "developer forgot to wrap in `PyrycodeMobileTheme`" bugs. The error message names the fix.
- `internal` on the local: outside callers go through the `ColorScheme.warning` extension, not through `LocalWarningColors.current` directly. Keeps the API surface to the four extension properties.
- `@ReadOnlyComposable` on each getter: tells the compiler the getter only reads composition state (does not contribute to the composition tree), which is the same annotation Material 3's `MaterialTheme.colorScheme` getter carries.

### Modified file: `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt`

Add 24 new colour vals — for each of the six mode suffixes (`Light`, `LightMediumContrast`, `LightHighContrast`, `Dark`, `DarkMediumContrast`, `DarkHighContrast`) define `warning<Suffix>`, `onWarning<Suffix>`, `warningContainer<Suffix>`, `onWarningContainer<Suffix>`. Place each block of four next to the matching `error*` block in the same mode section, so the file's mode-grouped ordering is preserved.

All hex values come from the Figma `Schemes/Warning` variable's six mode bindings (see Files to read first).

### Modified file: `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`

Two additions:

1. **Six `WarningColors(...)` constants**, one per mode, declared next to (not inside) the matching `*ColorScheme` block they pair with:

   ```kotlin
   private val lightWarningColors = WarningColors(warningLight, onWarningLight, warningContainerLight, onWarningContainerLight)
   private val darkWarningColors  = WarningColors(warningDark,  onWarningDark,  warningContainerDark,  onWarningContainerDark)
   // + mediumContrastLightWarningColors, highContrastLightWarningColors,
   //   mediumContrastDarkWarningColors,  highContrastDarkWarningColors
   ```

   All six are defined per AC #3, even though only `lightWarningColors` / `darkWarningColors` are consumed today (the medium/high-contrast schemes are likewise defined-but-not-routed in the current `PyrycodeMobileTheme`).

2. **Provider in `PyrycodeMobileTheme`** — extend the existing `when` block to pick a parallel `warningColors` alongside `colorScheme`, then wrap the existing `MaterialTheme(...)` call in `CompositionLocalProvider(LocalWarningColors provides warningColors)`:

   ```kotlin
   val (colorScheme, warningColors) = when {
       dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
           val context = LocalContext.current
           if (darkTheme) dynamicDarkColorScheme(context) to darkWarningColors
           else            dynamicLightColorScheme(context) to lightWarningColors
       }
       darkTheme -> darkScheme to darkWarningColors
       else      -> lightScheme to lightWarningColors
   }

   CompositionLocalProvider(LocalWarningColors provides warningColors) {
       MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
   }
   ```

   Dynamic-color branch: the wallpaper-derived `ColorScheme` does not contain a warning hint, so the dynamic path falls back to the standard light/dark warning. This keeps `MaterialTheme.colorScheme.warning` non-null in every composition path (AC #2).

## State + concurrency model

N/A — this is a pure theme/token change. No `ViewModel`, no `Flow`, no coroutines. `CompositionLocalProvider` is composition-tree state managed by Compose itself.

## Error handling

Single failure mode: a composable reads `MaterialTheme.colorScheme.warning` outside a `PyrycodeMobileTheme { ... }` wrap. `staticCompositionLocalOf { error(...) }` surfaces this at first read with a message naming the fix — a fail-fast crash in dev/test, not a silent `Color.Unspecified` that ships and produces invisible UI in production. This matches how Material 3 handles missing-theme misuse.

No network, no IO, no permission, no parse paths to consider.

## Testing strategy

The AC's word "unit test" is loose: resolving `MaterialTheme.colorScheme.warning` requires a live Compose composition, which on this project means **`app/src/androidTest/`** (the project has no Robolectric; existing Compose tests like `WelcomeScreenTest`, `ScannerScreenTest`, `ChannelListScreenTest` all live under `androidTest`). The test runs via `./gradlew connectedAndroidTest`. Call this out in the PR description so reviewers know the AC's "unit test" wording maps to an instrumented Compose test in this codebase.

New file: `app/src/androidTest/java/de/pyryco/mobile/ui/theme/WarningColorSlotTest.kt`

Use `createComposeRule()`. Scenarios (bullet-pointed; the developer writes them in the project's existing Compose-test idiom):

- **Light theme resolves warning to the Figma `Schemes/Warning` Light value.** Set content to `PyrycodeMobileTheme(darkTheme = false, dynamicColor = false) { CaptureWarning() }` where `CaptureWarning` is a private test composable that writes `MaterialTheme.colorScheme.warning` into a side-channel `var` (e.g. captured via a `remember` + outer reference, or via a `SideEffect`). Assert the captured `Color` equals `warningLight`.
- **Dark theme resolves warning to the Figma `Schemes/Warning` Dark value.** Same shape with `darkTheme = true`; assert equals `warningDark`.
- The two scenarios above cover the two values the AC names. Asserting against the `warningLight` / `warningDark` vals (not raw hex literals) keeps the test green if the Figma palette is later re-pulled — the contract under test is "the slot is wired through and reachable", not "this specific hex never changes".
- Compare `Color` instances directly with `assertEquals`; `androidx.compose.ui.graphics.Color` is a value class with structural equality.

Not in scope for this ticket:

- Tests for `onWarning` / `warningContainer` / `onWarningContainer` — same wiring path; covered transitively by the two assertions above. Adding four more per-mode would be noise.
- Tests for the four medium/high-contrast `WarningColors` instances — they exist for future routing (AC #3) but are not selected by the current `when` block, so there is no live composition path to assert against. Their existence is verified by `Theme.kt` compiling.
- Migrating the `#D8B85A` literal — explicitly out of scope per Technical Notes; downstream tickets own that.

## Open questions

None. The mechanism (`CompositionLocal` + extension property), the file layout (one new file, two modified), the Figma source (`Schemes/Warning` variable, six modes), and the test approach (instrumented Compose test, light + dark assertions) are all pinned. The four un-routed `WarningColors` instances are intentional per AC #3 and parallel the existing un-routed medium/high-contrast `ColorScheme` instances.
