# Spec — Welcome Screen Scaffold (#7)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — existing entry point + `@Preview` shape; the new file mirrors its preview pattern (`@Preview(showBackground = true)` wrapped in `PyrycodeMobileTheme`).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:257-279` — `PyrycodeMobileTheme` signature; both previews and any embedding wrap here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography` is currently default `Typography()`, so all `MaterialTheme.typography.*` slots resolve to Material 3 defaults. The AC bans hardcoded font sizes/weights/families; rely entirely on typography slots and let the theme decide.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:1-30` — the imported brand palette. **Do not** import these tokens directly; only consume via `MaterialTheme.colorScheme.*` (AC bar on `Color(0x...)` literals applies to using the palette directly too).
- `gradle/libs.versions.toml` — Compose BOM `2026.02.01`, Material 3 (`androidx-compose-material3`) and `androidx-compose-ui-tooling-preview` already wired; no dependency changes needed.

## Context

First onboarding ticket. The screen is a pure stateless Composable taking two navigation callbacks. NavHost wiring lands in #8; real CTA destinations (QR scanner, browser intent) land in #14. Keeping this ticket free of `NavController` / `Intent` code is the explicit boundary that lets #8 and #14 proceed in parallel.

Figma reference: node `6:32` at the 412×892 baseline. The AC text is authoritative — adapt the Figma export to Compose + Material 3 idioms rather than transliterating React/Tailwind.

## Design

### File

Create one file: `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt`. The `ui/onboarding/` directory does not exist yet — let `kotlinc` create it via the package declaration; the build will pick it up under the existing `app/src/main/java/` source root.

### Public surface

```kotlin
@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
)
```

The signature is fixed by AC1 (consumed verbatim by #8 and #14). Do not add a `modifier: Modifier = Modifier` parameter — call sites in #8/#14 won't pass one, and adding it now is scope creep beyond the AC.

### Layout tree

Top-level `Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize())` containing a single `Column` with:

- `Modifier.fillMaxSize()`
- `.systemBarsPadding()` (edge-to-edge is enabled in `MainActivity`; honor insets)
- `.padding(horizontal = 24.dp)` and `.padding(vertical = 32.dp)` (spacing dp values are layout, not theme tokens — they are not banned by the "no hardcoded font sizes/weights/families" AC, which targets typography only)
- `verticalArrangement = Arrangement.SpaceBetween`
- `horizontalAlignment = Alignment.CenterHorizontally`

Three children, top to bottom:

1. **Hero section** — `Column(horizontalAlignment = CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f))` with `verticalArrangement` reset by an inner `Spacer(Modifier.weight(1f))` above and below to vertically center the hero block within its allocated weight. Inside, top to bottom:
   - **Logo placeholder** — `Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))` with a centered `Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))`. `Icons.Filled.Terminal` ships with `material-icons-extended`; if that artifact is absent (it is — check `libs.versions.toml`: no `material-icons-extended` line), substitute `Icons.Filled.Star` from the core icon set or fall back to a plain `Box` with no inner icon. **Preferred:** plain `Box` with no inner icon — keeps the file dependency-free and the ticket truly out-of-scope for branding.
   - **Title** — `Text("Pyrycode Mobile", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)`.
   - **Subtitle** — `Text("Your pyrycode CLI, in your pocket.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)`. Subtitle copy is unconstrained by the AC; this is a reasonable default — the developer may pick equivalent copy if they prefer.

2. **CTA stack** — `Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp))`:
   - **Primary CTA** — `Button(onClick = onPaired, modifier = Modifier.fillMaxWidth())` with `Text("I already have pyrycode")`. Default `Button` resolves to filled / primary container styling from Material 3 — no `colors = ButtonDefaults.buttonColors(...)` override needed (and the AC bans direct color literals anyway).
   - **Secondary CTA** — `OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth())` with `Text("Set up pyrycode first")`. `OutlinedButton` is the canonical Material 3 secondary affordance below a filled primary.

3. **Footer line** — `Text("Pyrycode is open source.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())`. Footer copy is also unconstrained by the AC; use this default unless the developer has a clearly better short phrasing.

### Imports — what's allowed

Only Material 3 (`androidx.compose.material3.*`), foundation (`androidx.compose.foundation.layout.*`, `androidx.compose.foundation.shape.CircleShape`), runtime (`androidx.compose.runtime.Composable`), ui (`androidx.compose.ui.Modifier`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.text.style.TextAlign`, `androidx.compose.ui.tooling.preview.Preview`), and the project's `PyrycodeMobileTheme`. **No** `androidx.navigation.*`, **no** `androidx.activity.*`, **no** `android.content.Intent`. Verify with `grep -E 'NavController|androidx\.navigation' app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` returning empty after writing the file (AC4).

## State + concurrency model

None. No `remember`, no `LaunchedEffect`, no `ViewModel`. Pure function from `(onPaired, onSetup)` callbacks to composition. Recomposition correctness: both callbacks are passed straight to `onClick` without capture — they are stable references at the call site (#8 will pass top-level lambdas or method references).

## Error handling

N/A — no I/O, no state.

## Testing strategy

The AC does not require unit or instrumented tests for this ticket — coverage is the two `@Preview` composables and the `./gradlew assembleDebug` gate. Do **not** add `ComposeTestRule` interaction tests; that's scope creep beyond AC and would introduce a new test dependency (`androidx-compose-ui-test-junit4` is in `libs.versions.toml` but unused so far).

### Required previews (AC5)

Both live in the same file, below `WelcomeScreen`:

```kotlin
@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun WelcomeScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        WelcomeScreen(onPaired = {}, onSetup = {})
    }
}

@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WelcomeScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        WelcomeScreen(onPaired = {}, onSetup = {})
    }
}
```

`Configuration` import: `import android.content.res.Configuration`. The 412×892 dimensions match the Figma baseline so the preview renders at the design's intended size. Pass `darkTheme = true/false` explicitly to the theme so the preview is deterministic regardless of the IDE's preview-runner system setting.

Mark both previews `private` — they're not part of the public surface.

## Open questions

- **Logo placeholder choice.** Spec defaults to a plain colored `Box` (no icon) to keep the file dependency-free. If the developer prefers an icon, they may use any core `Icons.Filled.*` symbol — the asset is throwaway and will be replaced when the real branding lands.
- **Subtitle and footer copy.** Spec defaults are reasonable but not binding; developer may substitute equivalent short phrasings if they read better in context. The AC does not pin the strings.

## Out of scope (do not implement)

- `NavController`, `NavHost`, route strings — #8.
- `Intent.ACTION_VIEW` / browser launch / QR scanner — #14.
- Real logo asset, custom typography weights — branding ticket, not yet filed.
- `WelcomeViewModel` or any `remember`-based state — pure stateless per AC and Technical Notes.
- Conditional start destination based on pairing state — #13.
