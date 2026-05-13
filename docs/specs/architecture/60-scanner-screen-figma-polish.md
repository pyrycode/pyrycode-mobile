# Spec — Scanner Screen Figma polish (#60)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

A dark-surface 412×892 pairing screen. A M3 top app bar reads "Pair with pyrycode" with a leading back arrow; below it, a full-height rounded camera-viewport panel renders dual radial gradients (cool-blue stop near the upper-left, soft-coral stop near the lower-right), a 105-line atmospheric horizontal-stripe overlay, a 248dp four-corner reticle with a glowing horizontal scan line through its middle, and a translucent hint card pinned to the viewport's bottom that reads "Run `pyry pair` on your pyrycode server to generate a QR code." (the `pyry pair` token in coral Roboto Mono). A `Trouble scanning? Paste the pairing code instead` text-button sits below the viewport.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` (current 96-line stub from #12) — the file being rewritten. Preserve the `ScannerScreen(onTap: () -> Unit)` signature and the two `@Preview` composables (light + dark, 412×892) exactly.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — confirm the only call site reads `ScannerScreen(onTap = { ... })` and is left untouched (AC requirement). The scope-launched `setPairedServerExists(true) → navigate(ChannelList)` body of `onTap` is the wiring AC6 forbids changing.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:112,120,130` — the dark scheme token values that map cleanly to Figma's gradient stops and tokens: `primaryDark = #9DCBFC` (reticle, blue radial, scan line), `tertiaryDark = #FFB59F` (`pyry pair` mono accent, coral radial), `surfaceDark = #101418` (matches `--schemes/surface`). The light scheme is auto-derived; no light-mode mockup exists, the preview verifies it composes without crashing.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography = Typography()` is the M3 default; there is no project-level Roboto Mono FontFamily. Use Compose's built-in `FontFamily.Monospace` for the `pyry pair` token — it is a `FontFamily` constant, not a typography literal, and the AC's typography ban targets `TextStyle(...)` constructors and raw `sp` sizes.
- `docs/specs/architecture/57-welcome-screen-figma-polish.md` — the precedent Phase 1.5 Figma-polish spec. Mirror its conventions: zero `Color(0x…)` literals outside the gradient-stops carve-out, zero `TextStyle(...)` constructors, theme tokens everywhere else, two previews at 412×892 light + dark. The "Drawable assets" workflow and the "no `ButtonDefaults` override" rule both transfer 1:1.
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — confirm `androidx-compose-material3`, `androidx-compose-material-icons-core` (for `Icons.AutoMirrored.Filled.ArrowBack`), and `androidx-compose-ui` are on the classpath. No new dependencies; no catalog edits.

## Context

Phase 1's #12 shipped a stub Scanner — a `surfaceVariant` rounded box with a single 2dp horizontal line and a "Tap to pair" label. The Figma node `13:2` upgrades that placeholder to the pairing-moment "premium developer tool" visual that the marketing flow leans on: layered atmospherics behind a real reticle, with the `pyry pair` CLI command surfaced in mono+coral as the explicit pairing affordance.

This is the **second Phase 1.5 Figma-polish ticket** through the pipeline, following #57 (Welcome). The pattern is the same: the public composable signature and call-site wiring are frozen; the body is rewritten to match the locked Figma node; every visual property routes through `MaterialTheme.colorScheme.*` or `MaterialTheme.typography.*` except for the explicitly carved-out gradient-stop alpha overlays.

**Interaction caveat — tap-anywhere preserved on top of Material affordances.** AC6 mandates the fake-pair tap-anywhere behaviour. The Figma renders a back-arrow `IconButton` and a `TextButton`. Both are kept as visual elements but both are wired to `onTap` (same callback as the outer tap-anywhere gesture detector). That's not visually correct semantics — in Phase 4 the back-arrow will pop the back stack and the text-button will open a paste-pairing-code flow — but it is what AC6 demands: any tap, anywhere on this screen, fires `onTap` and triggers the fake-pair side effect at the call site. Document this contradiction in the composable as a one-line `// Phase 1.5: every interactive element fires onTap` comment; it's the kind of WHY-comment the project conventions explicitly permit, and the developer agent must not chase the contradiction by introducing a `onBack` or `onPasteCode` callback (that would change MainActivity wiring, which AC6 forbids).

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt`

No new files. No drawables — the back-arrow uses `Icons.AutoMirrored.Filled.ArrowBack` from `material-icons-core` (already on classpath; same package #57 explicitly banned new icons from, this one was already present). No changes to `MainActivity.kt`, `Theme.kt`, `Color.kt`, `Type.kt`, build files, or any other file.

### Public surface (unchanged)

```kotlin
@Composable
fun ScannerScreen(
    onTap: () -> Unit,
)
```

No `modifier` parameter. No new callbacks. Two `@Preview` composables at 412×892 (light + dark) stay verbatim by signature; only the rendered body differs.

### Layout tree

```
Surface(color = colorScheme.surface, Modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })   ← outer tap-anywhere
└── Column(Modifier.fillMaxSize().systemBarsPadding())
    ├── TopAppBar(...)                                                     ← "Pair with pyrycode"
    │   navigationIcon = IconButton(onClick = onTap) {
    │       Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    │   }
    │   colors = TopAppBarDefaults.topAppBarColors(
    │       containerColor = Color.Transparent,
    │       titleContentColor = colorScheme.onSurface,
    │       navigationIconContentColor = colorScheme.onSurface,
    │   )
    ├── Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
    │             .clip(RoundedCornerShape(24.dp)))                         ← Viewport
    │   ├── Box(Modifier.matchParentSize()
    │   │           .background(brush = blueRadial))                        ← Radial #1 (cool-blue, 30%/40%)
    │   ├── Box(Modifier.matchParentSize()
    │   │           .background(brush = coralRadial))                       ← Radial #2 (soft-coral, 70%/70%)
    │   ├── Canvas(Modifier.matchParentSize()) { ... }                      ← Atmospheric stripes (105 lines)
    │   ├── Box(Modifier.size(248.dp).align(Alignment.Center)) { ... }      ← Reticle (4 corners + scan line)
    │   └── Box(Modifier.align(Alignment.BottomCenter)
    │             .padding(16.dp).fillMaxWidth()
    │             .clip(RoundedCornerShape(12.dp))
    │             .background(colorScheme.scrim.copy(alpha = 0.72f))         ← Hint card
    │             .padding(horizontal = 16.dp, vertical = 12.dp))
    │       └── Text(buildAnnotatedString { ... "Run "; mono+coral "pyry pair"; " on your..." })
    └── Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Center)
        └── TextButton(onClick = onTap) {
            Text("Trouble scanning? Paste the pairing code instead",
                 style = typography.labelLarge,
                 color = colorScheme.primary)
        }
```

Paddings (`16.dp` viewport horizontal, `8.dp` text-button vertical, `12.dp` hint card vertical, `24.dp` viewport corner radius, `12.dp` hint corner radius, `248.dp` reticle, `16.dp` hint inset) come from the Figma payload — layout dp, not theme tokens, not banned by AC5. Tune ±4dp if the preview reads off.

### Color → theme token bindings

| Figma value                  | Source                                             | Used by                          |
|------------------------------|----------------------------------------------------|----------------------------------|
| `--schemes/surface` (#101418)| `colorScheme.surface`                              | Outer Surface base               |
| `--schemes/on-surface` (#e0e2e8) | `colorScheme.onSurface`                       | Top-app-bar title + nav icon     |
| Viewport base `rgb(5,7,11)`  | `colorScheme.surfaceContainerLowest`               | Viewport panel base (darker than surface, M3 dark-scheme convention) |
| Blue radial stop `rgba(122,184,232,…)` | `colorScheme.primary.copy(alpha = 0.12f)`| Radial #1 inner stop             |
| Coral radial stop `rgba(255,168,142,…)` | `colorScheme.tertiary.copy(alpha = 0.06f)` | Radial #2 inner stop             |
| Reticle bars + scan line `--schemes/primary` (#9dcbfc) | `colorScheme.primary`     | Reticle 4 corners + scan line core |
| Scan-line glow `rgba(122,184,232,0.6)` | `colorScheme.primary.copy(alpha = 0.6f)` | Blurred glow under scan line     |
| Atmospheric stripe `rgba(255,255,255,0.04)` | `colorScheme.onSurface.copy(alpha = 0.04f)` | Canvas stripe paint        |
| Hint card bg `rgba(16,19,26,0.72)` | `colorScheme.scrim.copy(alpha = 0.72f)`      | Hint card overlay                |
| Hint card text `rgba(255,255,255,0.92)` | `colorScheme.onSurface.copy(alpha = 0.92f)` | Hint card prose           |
| `pyry pair` coral `#ffb59f`  | `colorScheme.tertiary`                             | Mono accent inside hint          |
| `Trouble scanning?` `--schemes/primary` (#9dcbfc) | `colorScheme.primary`              | TextButton label                 |
| Gradient terminal stop       | `Color.Transparent`                                | Both radials' outer stop         |

Per AC5's explicit carve-out — "gradient stops are RGBA-frozen at creation per Figma workflow lessons; that constraint is acceptable" — the developer **may** fall back to `Color(0xFF7AB8E8).copy(alpha = 0.12f)` / `Color(0xFFFFA88E).copy(alpha = 0.06f)` for the radials if matching `colorScheme.primary/tertiary` from the dark scheme drifts visually. The token-first mappings above are the preferred path; the literal fallback is the carve-out, not the default.

### Radial gradients

Two `Brush.radialGradient` instances applied via `Modifier.background(brush = ..., shape = RectangleShape)` on two stacked `Box`es inside the viewport. Both gradients use the viewport's natural coordinate space (`gradientUnits = userSpaceOnUse`), so the centers should be expressed as fractions of viewport width/height via `BoxWithConstraints` or `Modifier.drawBehind { ... }` reading `size.width / size.height`. A `BoxWithConstraints` wrapper around the viewport is the simplest reading; alternatively, encode the centers as `Offset(maxWidth.toPx() * 0.30f, maxHeight.toPx() * 0.40f)` etc.

| Brush      | Center (viewport-relative)  | Radius (viewport-relative) | Inner stop                                  | Mid stop (offset 0.6) | Outer stop |
|------------|------------------------------|----------------------------|---------------------------------------------|-----------------------|------------|
| Blue radial | `(30% w, 40% h)`            | ~`70% h` (≈max(w, h))      | `colorScheme.primary.copy(alpha = 0.12f)`   | `…copy(alpha = 0f)`   | `Color.Transparent` |
| Coral radial | `(70% w, 70% h)`           | ~`70% h`                   | `colorScheme.tertiary.copy(alpha = 0.06f)`  | `…copy(alpha = 0f)`   | `Color.Transparent` |

Figma's SVG payload uses a `gradientTransform` matrix (`matrix(26.6 0 0 51.52 ...)`) that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires `Modifier.scale(scaleX = a/b, scaleY = 1f)` on a wrapping Box — **do not** implement that. A circular approximation reads identically as atmospheric haze; the architectural call (per #57 precedent) is parity-of-intent, not pixel-identity of the SVG matrix.

Stacking order: blue radial *under* coral radial (the Figma SVG paints coral first then blue, but with both at low alpha and non-overlapping centers, the order is functionally invisible). Both sit below the stripes layer and below the reticle.

### Atmospheric stripes — single Canvas

AC3 explicitly requires "wrap as a single `Canvas` to avoid 105 nested composables." Implementation contract:

```kotlin
Canvas(Modifier.matchParentSize()) {
    val stripeColor = /* colorScheme.onSurface.copy(alpha = 0.04f), hoisted before the Canvas */
    val stripeSpacingPx = 7.dp.toPx()
    var y = 0f
    while (y <= size.height) {
        drawRect(
            color = stripeColor,
            topLeft = Offset(0f, y),
            size = Size(size.width, 1.dp.toPx()),
        )
        y += stripeSpacingPx
    }
}
```

The color must be hoisted **outside** the `Canvas` lambda — `MaterialTheme.colorScheme` is not addressable from the DrawScope receiver. Read it at the call site (`val stripeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)`) then close over it.

Do **not** compute the stripe count from `(size.height / 7.dp.toPx()).roundToInt()` and pre-allocate a list; the `while`-loop pattern above is simpler and the cost difference is invisible. Figma's "exactly 105 stripes" count is a function of the 736dp panel height; the panel height in our layout depends on the device — let the loop self-terminate at `size.height` and the count comes out right by construction.

### Reticle

A `Box(Modifier.size(248.dp).align(Alignment.Center))` parents 8 corner rectangles + 1 scan line, all using absolute positioning via `Modifier.offset(x = …, y = …)` (or equivalently, child `Modifier.align(Alignment.TopStart) + Modifier.offset(...)`). Four corners, each composed of a horizontal stub (`28×4.dp`, rounded `2.dp`) and a vertical stub (`4×28.dp`, rounded `2.dp`), positioned at TL / TR / BL / BR corners of the 248dp box. Coordinates direct from the Figma payload (offsets in dp inside the 248dp frame).

The scan line is a single `Box(Modifier.fillMaxWidth(...).height(2.dp).align(Alignment.Center))` painted `colorScheme.primary`, sized `232×2.dp`, positioned horizontally centered with a small inset from the reticle corners — Figma `left=8 top=123 width=232`. Stretching across "between the corners" — `Modifier.padding(horizontal = 8.dp).height(2.dp).fillMaxWidth()` inside the reticle box, vertically centered.

**Scan-line glow.** Figma `shadow-[0px_0px_12px_0px_rgba(122,184,232,0.6)]` — a 12dp Gaussian blur around the line. Compose's `Modifier.blur(12.dp)` (API 31+; this app's min SDK is 33, so always available) applied to a wider/taller backing `Box` painted `colorScheme.primary.copy(alpha = 0.6f)` produces the glow. Implementation contract: render the glow as a separate `Box` *behind* the crisp scan line:

```
Box(fillMaxWidth-padded-8dp, height = 6dp, blur(12.dp), background = primary.copy(0.6f))  ← glow
Box(fillMaxWidth-padded-8dp, height = 2.dp,             background = primary)             ← line
```

Both centered vertically at the reticle's 50% line via a shared parent `Box` with `contentAlignment = Center`, or via matched-positioning siblings. The blur radius and glow height are loosely tunable — 4–8dp glow box × 10–14dp blur radius all read identically. Do not introduce `Modifier.shadow(...)` here; `shadow` is shape-bound and produces a clipped drop shadow rather than a halo.

### Hint card

`Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colorScheme.scrim.copy(alpha = 0.72f)).padding(horizontal = 16.dp, vertical = 12.dp))` wrapping a `Text` whose content is an `AnnotatedString` built from `buildAnnotatedString`:

- `append("Run ")` — styled with default text style (`typography.bodyMedium`, color `colorScheme.onSurface.copy(alpha = 0.92f)`).
- `withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colorScheme.tertiary)) { append("pyry pair") }`.
- `append(" on your pyrycode server to generate a QR code.")`.

The outer `Text` uses `style = MaterialTheme.typography.bodyMedium` and `color = colorScheme.onSurface.copy(alpha = 0.92f)`. **Do not** wrap the `pyry pair` token in a separate `Text(...)` inline — that fragments the line at wrap points and breaks the flow. `AnnotatedString` is the canonical solution for mixed-style runs inside a single paragraph.

`FontFamily.Monospace` is a Compose-provided `FontFamily` constant (`androidx.compose.ui.text.font.FontFamily.Monospace`); on Android it resolves to the device's monospace font (Roboto Mono on stock Android). This is the correct token-shaped answer — not a custom font file, not a `Font(R.font.roboto_mono)` import.

The hint card sits visually inside the viewport — at the BottomCenter alignment of the viewport `Box`, with 16dp inset on all four sides. It is non-interactive (no `clickable`, no `pointerInput`). Taps on it propagate up to the outer `Surface`'s `detectTapGestures` and fire `onTap`. (Compose pointer events bubble unless a child handles them; the hint card does not handle.)

### "Trouble scanning?" TextButton

Below the viewport, inside the outer `Column`. `TextButton(onClick = onTap, modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally))` containing `Text("Trouble scanning? Paste the pairing code instead", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)`. No `ButtonDefaults.textButtonColors(...)` override; the default `TextButton` content color matches `primary` already, the explicit `color =` on the inner `Text` is belt-and-suspenders against future theme drift.

`onClick = onTap` is the AC6 reconciliation: visually a paste-pairing-code affordance, functionally a fake-pair tap. Same with the top-app-bar back IconButton.

### Top app bar

`TopAppBar(title = { Text("Pair with pyrycode", style = MaterialTheme.typography.titleLarge) }, navigationIcon = { IconButton(onClick = onTap) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface))`.

The `containerColor = Color.Transparent` lets the outer `Surface`'s `surface` color show through; without it, `TopAppBar` defaults to a tonal-elevated surface variant that produces a visible seam against the viewport panel. `Color.Transparent` is a `Color` constant (not a hex literal), so it does not violate AC5.

Uses Material 3's experimental `TopAppBar`; the developer must add `@OptIn(ExperimentalMaterial3Api::class)` to the `ScannerScreen` function. Single opt-in annotation, not a file-level `@file:OptIn`.

### Imports (allow-list)

- Material 3 (`androidx.compose.material3.*` — `MaterialTheme`, `Surface`, `Text`, `TopAppBar`, `TopAppBarDefaults`, `IconButton`, `Icon`, `TextButton`, `ExperimentalMaterial3Api`).
- Material icons (`androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.automirrored.filled.ArrowBack`).
- Foundation (`androidx.compose.foundation.background`, `androidx.compose.foundation.Canvas`, `androidx.compose.foundation.gestures.detectTapGestures`, `androidx.compose.foundation.layout.*`, `androidx.compose.foundation.shape.RoundedCornerShape`).
- Runtime (`androidx.compose.runtime.Composable`).
- UI (`androidx.compose.ui.Alignment`, `androidx.compose.ui.Modifier`, `androidx.compose.ui.draw.blur`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.graphics.Brush`, `androidx.compose.ui.graphics.Color` — for `Color.Transparent` only, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.text.SpanStyle`, `androidx.compose.ui.text.buildAnnotatedString`, `androidx.compose.ui.text.font.FontFamily`, `androidx.compose.ui.text.withStyle`, `androidx.compose.ui.tooling.preview.Preview`, `androidx.compose.ui.unit.dp`).
- Android (`android.content.res.Configuration`).
- Project (`de.pyryco.mobile.ui.theme.PyrycodeMobileTheme`).

**Banned**: any `Color(0x…)` literal outside the AC5 gradient-stop carve-out (and even there, prefer token + alpha first), any `TextStyle(...)` constructor, any `Font(R.font.…)` import (no new font assets), any `androidx.navigation.*` / `androidx.activity.*` / Koin / DataStore import (this screen remains a pure stateless composable), any new icon package (use the already-present `material-icons-core`'s `Icons.AutoMirrored.Filled.ArrowBack`).

### Previews

Two `@Preview` composables at the bottom of the file, identical to #12's existing previews (light + dark, 412×892, `PyrycodeMobileTheme(darkTheme = …)`, pass-through empty `onTap = {}`). Do not change the preview signatures — the developer is rewriting the *body* of `ScannerScreen`, not the preview wiring. AC's preview-renders-without-crashing requirement is satisfied by `Brush.radialGradient` rendering correctly with `BoxWithConstraints`-derived sizes (which the preview canvas supplies) and the Canvas stripe loop self-terminating against the preview height.

## State + concurrency model

None. Pure stateless composable. No `remember`, no `rememberCoroutineScope`, no `LaunchedEffect`, no `ViewModel`. The `onTap` callback is passed straight to:

- The outer `Surface`'s `pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }`.
- `IconButton(onClick = onTap)` in the top app bar.
- `TextButton(onClick = onTap)` below the viewport.

All three references are stable across recompositions because `onTap` is a stable function reference at the `MainActivity.kt` call site (the `scope.launch { ... }` closure is captured once per destination composition).

The radial brushes and the hint `AnnotatedString` are recreated on every recomposition — both are cheap and there's no observable performance impact. Do **not** wrap them in `remember { ... }` blocks. The Canvas stripe loop runs once per draw pass, which is what we want (the loop terminates against the current measured height, not a remembered one).

## Error handling

N/A. No I/O, no state, no callbacks that can throw. `Modifier.blur(12.dp)` falls back to a no-op on API < 31; min SDK 33 means this path is never taken in production, but if a preview environment ever fails the blur, the line renders without halo — visually degraded, not crashed. Acceptable.

## Testing strategy

Same shape as #57 — preview-driven verification, no unit / Compose UI tests added for this ticket.

- **Unit (`./gradlew test`)**: none required. No business logic.
- **Instrumented (`./gradlew connectedAndroidTest`)**: none required. Interaction is unchanged from #12 (same `onTap` callback, same fake-pair behavior at the call site).
- **`./gradlew lint`**: must pass without new warnings.
- **`./gradlew assembleDebug`**: must pass.
- **Preview rendering**: both `ScannerScreenLightPreview` and `ScannerScreenDarkPreview` must render in Android Studio's Compose preview without exception.
- **Visual fidelity check** (code-review owns this, not developer): code-review will fetch `mcp__plugin_figma_figma__get_screenshot(fileKey = "g2HIq2UyPhslEoHRokQmHG", nodeId = "13:2")` and visually compare it against the dark preview. The scan-line glow, the dual radials, the 105-stripe overlay, the coral mono `pyry pair` accent, and the hint-card placement are the load-bearing visual elements.

## Open questions

- **Light-theme appearance.** No Figma light mockup exists. The dark scheme is Phase 1.5's target; the light scheme is auto-derived from theme tokens and the preview verifies it composes. If the dark-scheme stripe alpha (`onSurface.copy(alpha = 0.04f) = #e0e2e8 @ 4%` on a light surface) reads too washed-out in light mode, that's acceptable — Phase 1.5's mandate is dark-mode fidelity. Do not branch the stripe color on `isSystemInDarkTheme()` to "fix" light mode; the token routes through correctly.
- **Reticle absolute positioning.** Eight corner stubs at fixed offsets inside a 248dp box is the Figma-mirroring shape. A cleaner Compose-idiomatic refactor (e.g. a `for (corner in corners) { ... }` loop with computed offsets, or a `Canvas` painting the corner strokes directly) is tempting but adds risk to a Phase 1.5 visual ticket. Keep the eight `Box` children; the loop refactor can wait for Phase 4's re-implementation under CameraX. Sized for cost-of-change, not aesthetic.
- **Hint card alpha tuning.** `colorScheme.scrim.copy(alpha = 0.72f)` is a starting point; the Figma stop is `rgba(16,19,26,0.72)` which is `surfaceDark @ 72%` — `scrim` in M3 dark scheme is typically `#000000` so the result is slightly darker. If the preview reads too opaque, fall back to `colorScheme.surface.copy(alpha = 0.72f)` (literal token match). Either is correct token-wise; pick by preview readability.

## Out of scope (do not implement)

- Real CameraX preview integration — Phase 4 owns this; the viewport remains a painted decoration.
- Real QR scan detection — Phase 4.
- Real back-navigation (`onClick = { navController.popBackStack() }`) wired to the IconButton — would require either changing MainActivity.kt's wiring (banned by AC6) or threading a second callback through the composable signature (also banned). The IconButton fires `onTap` instead; Phase 4 rewires.
- Paste-pairing-code flow wired to the TextButton — same reasoning as above; fires `onTap` in Phase 1.5.
- Scan-line animation (`rememberInfiniteTransition` translating the scan line vertically across the reticle) — the Figma shows a static line; the AC doesn't ask for motion; Phase 4 owns interactive polish.
- Reticle pulse / corner-stroke animation — same out-of-scope category.
- Adding a `Roboto_Mono` font asset under `res/font/` and a custom `FontFamily` — `FontFamily.Monospace` is the contract for this ticket. A custom mono font is a project-wide typography decision that should land in `Type.kt`, not in this screen.
- `MainActivity.kt`, `Theme.kt`, `Color.kt`, `Type.kt`, build-file, or any drawable-resource changes.
