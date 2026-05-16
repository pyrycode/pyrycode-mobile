# Spec — M3 elevation on Welcome logo container per current Figma 6:32 (#168)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

The Welcome screen now applies the **`M3/Elevation Light/3`** drop-shadow effect to its top content. The effect is bound at Figma node **`6:33` (Hero group)** — the group that contains the logo, the title pair, and the body paragraph — *not* on the inner logo node `6:34` itself; the ticket body's "logo container" phrasing simplifies that to the logo because, on the dark surface (`#101418`), the only element with enough luminance contrast to actually cast a perceptible shadow is the bright tinted logo glyph (text glyphs are `#e0e2e8` / `#c2c7cf` on near-black — visually negligible shadow). The visible result is a soft dark glow under the snowflake.

`M3/Elevation Light/3` from `get_variable_defs` on node `6:32`:

| Layer | Type | Color | Offset | Blur radius | Spread |
|---|---|---|---|---|---|
| Key | `DROP_SHADOW` | `#0000004D` (black, α≈0.30) | (0, 1) | 3 | 0 |
| Ambient | `DROP_SHADOW` | `#00000026` (black, α≈0.15) | (0, 4) | 8 | 3 |

These two layers are the two-tier M3 elevation shadow that Compose's `Modifier.shadow(elevation = 6.dp, …)` renders by default (M3 Elevation Level 3 == 6.dp shadow elevation; the framework synthesises both key + ambient layers from a single dp value, using `DefaultShadowColor` ≈ `Color.Black` for both — same colour family as Figma's `#000000`-with-alpha layers).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:71-76` — the existing `Icon(painter = painterResource(R.drawable.ic_pyry_logo), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(width = 92.dp, height = 104.dp))` call. This is the **only call site** modified by this ticket. The `modifier` argument gets one new `.shadow(…)` chained onto the existing `.size(…)` — see `## Design` → "Edit shape".
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:1-32` — current import block. Confirms `androidx.compose.ui.draw.drawBehind` is already imported; the new edit adds **one** import (`androidx.compose.ui.draw.shadow`) from the same `androidx.compose.ui.draw` package, sorted into the existing alphabetised block right after `drawBehind`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:1-40` — confirms there is **no** `shadow` / `outline` token wired into the M3 colour scheme for shadow tinting. The Compose `Modifier.shadow(…)` API's `ambientColor` / `spotColor` parameters default to `DefaultShadowColor` (== `Color.Black`), which matches Figma's `#000000` shadow base. No new colour token is introduced — see `## Design` → "Colour binding" for the AC3 rationale.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` (entire file, 88 lines) — all four test cases match by visible text strings ("I already have pyrycode", "Set up pyrycode first"). The logo's `contentDescription = null` and is not matched by any assertion; no test edits needed. AC4 passes by construction.
- `docs/specs/architecture/167-welcome-logo-vector-refresh.md` — the immediately-prior Welcome-logo spec. Read for the established pattern around scope discipline ("Out-of-scope second caller" — `ChannelListScreen.kt` also consumes `R.drawable.ic_pyry_logo` but is deliberately not touched). The same discipline carries forward to this ticket: shadow is added on the Welcome `Icon` only; the ChannelListScreen nav-bar icon is **not** elevated.
- `docs/specs/architecture/150-welcome-logo-visual-fidelity.md` — `## Context` and "Colour binding" sections. Establishes the codebase idiom of preserving the `Icon(tint = MaterialTheme.colorScheme.primary)` pathway and not refactoring to `Image` + `ColorFilter` for the logo. This ticket continues that idiom — the shadow is added *around* the existing Icon, the Icon itself is untouched apart from one modifier chain.

## Context

PR #170 merged 2026-05-16 (spec at `docs/specs/architecture/167-welcome-logo-vector-refresh.md`) and shipped the current Welcome logo as a `92 × 104 dp` `Icon` rendered flat — no shadow, no tonal lift. Post-merge, the Figma source-of-truth for node `6:32` was edited to bind `M3/Elevation Light/3` to the Hero group (node `6:33`), giving the logo a subtle soft drop-shadow on the dark surface.

The ticket body proposes three candidate approaches and asks the architect to pick:

1. **`Modifier.shadow(elevation, shape)` on the Icon (or a wrapper)** — direct drop-shadow.
2. **Wrapping the Icon in `Surface(tonalElevation = …, shape = …)`** — tonal-elevation surface.
3. A custom elevation effect.

**Decision: option 1 (`Modifier.shadow`).** Rationale:

- **The Figma effect is purely a drop-shadow** (`DROP_SHADOW` type in both `M3/Elevation Light/3` layers), with no tonal change to the underlying surface. M3's `tonalElevation` mechanism lifts the surface *colour* via `surfaceTint` overlay — it does not draw a drop-shadow. Using `Surface(tonalElevation = 6.dp)` would tint a (transparent) surface invisibly and produce no shadow; the correct M3 token for shadow rendering is `Surface(shadowElevation = …)` or, equivalently, `Modifier.shadow(elevation = …)`. Of the two, `Modifier.shadow` is the smaller edit: it does not introduce a new Composable layer, does not require a `color = Color.Transparent` workaround to suppress the default Surface background, and does not add a click/semantic surface where none is wanted.
- **Option 3 is unwarranted complexity.** The two-layer (key + ambient) drop-shadow recipe in `M3/Elevation Light/3` is exactly what `Modifier.shadow(elevation = 6.dp)` synthesises out of the box. Hand-rolling a custom `drawBehind { … }` block with `BlurMaskFilter`s would re-derive what the framework already gives us.

The implementation is a single chained modifier with one new import. **XS** (one file, < 10 LOC changed).

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` (one import added, the `Icon`'s `modifier` chain extended by one `.shadow(…)` call).
- **Do not modify:** `WelcomeScreenTest.kt`, `Color.kt`, `Theme.kt`, `MainActivity.kt`, any drawable, `ChannelListScreen.kt`. The `WelcomeScreen(onPaired, onSetup, modifier)` signature is preserved per the ticket body's "Preserve the existing `WelcomeScreen(onPaired, onSetup)` signature" requirement.

### Edit shape

One new import (sorted alphabetically into the existing `androidx.compose.ui.draw.*` block right after `drawBehind`):

```kotlin
import androidx.compose.ui.draw.shadow
```

One change to the existing `Icon(…)` call at `WelcomeScreen.kt:71-76`. The `modifier` argument's chain extends from:

```kotlin
modifier = Modifier.size(width = 92.dp, height = 104.dp),
```

to:

```kotlin
modifier = Modifier
    .shadow(
        elevation = 6.dp,
        shape = RectangleShape,
        clip = false,
    )
    .size(width = 92.dp, height = 104.dp),
```

Important: `.shadow(…)` is chained **before** `.size(…)` so the shadow is drawn behind the size-constrained Icon box (Compose modifiers apply outside-in for layout; the shadow modifier wraps the size modifier, drawing the shadow in the area the size modifier reserves). Putting `.size(…)` first would size the Icon then attempt to draw the shadow over a wrapper of unspecified size; the order shown above is the correct Compose idiom.

`RectangleShape` is imported from `androidx.compose.ui.graphics.RectangleShape` — it is **not** currently imported in `WelcomeScreen.kt`, so the developer adds this second import as well, sorted into the existing `androidx.compose.ui.graphics.*` block (which currently imports `Brush` and `Color`).

So the full import-block additions are exactly:

- `import androidx.compose.ui.draw.shadow`
- `import androidx.compose.ui.graphics.RectangleShape`

### Why these specific parameters

- **`elevation = 6.dp`** — M3 Elevation Level 3 == 6.dp shadow elevation. Compose's shadow renderer maps this single dp value to a two-tier key+ambient shadow approximating the Material Design 3 elevation spec; the rendered shadow closely matches Figma's `M3/Elevation Light/3` two-layer drop-shadow recipe without needing per-layer parameters. The dp value comes from the M3 elevation token mapping (`m3.material.io/styles/elevation/tokens`), not from a literal in the Figma effect.
- **`shape = RectangleShape`** — the shadow's outline is a rectangle matching the Icon's bounding box (92 × 104 dp). See "Shape caveat" below for why this is the right call despite the snowflake's irregular silhouette.
- **`clip = false`** — `Modifier.shadow`'s default is `clip = (elevation > 0.dp)` == `true`, which would clip the Icon's pixel content to the shape's outline. Since shape == `RectangleShape` and the Icon's content already lives inside that rectangle, the clip is a no-op visually, *but* setting `clip = false` is the documented Compose convention for "shadow-only, no clipping intent" and avoids any future surprise if the Icon's pixel bounds ever exceed the size modifier (e.g. via a future `ContentScale` change).
- **`ambientColor` / `spotColor` — omitted (defaults)** — defaults to `DefaultShadowColor` (== `Color.Black`). Matches Figma's `#000000`-with-alpha shadow base. See "Colour binding" below for the AC3 rationale.

### Shape caveat — rectangle vs. snowflake silhouette

Compose's `Modifier.shadow(elevation, shape)` casts a shadow whose outline is the **`shape` parameter's geometry**, not the alpha channel of the modified element's pixel content. This is a fundamental difference from the CSS `drop-shadow(…)` filter that Figma uses, which casts a shadow tracing the rendered alpha (so Figma's preview shows a snowflake-shaped shadow).

The available Compose options for a true silhouette-tracing shadow are all disproportionate to the task:

- **Build a `GenericShape` from the vector `pathData`** — requires parsing the 6 KB SVG path string at composition time into Compose's `Path` API; bug-prone, expensive per recomposition, and creates a separate maintenance burden if the drawable ever updates.
- **Bake the shadow into the drawable itself** — adds a second `<path>` layer with blurred fill below the existing path. Android Vector Drawable lacks a built-in blur primitive; would require `RenderEffect` (API 31+, OK for `minSdk = 33`) but the blur is then static and not lifted from any M3 token.
- **Use `graphicsLayer { renderEffect = BlurEffect(…); shadowElevation = … }` with a custom clip path** — same `GenericShape` problem as the first option, with added complexity.

**Decision: accept the rectangular shadow.** On the dark theme surface (`surfaceDark = #101418`), the rectangular shadow under a 92 × 104 dp bounding box renders as a barely-perceptible soft dark gradient under the logo region — the rectangle outline is **not** visually distinguishable from a silhouette shadow at the M3 Elevation 3 contrast level on a near-black background. On the light theme (`surfaceLight = #F8F9FF`), the shadow has more contrast and the rectangle-vs-silhouette difference *is* technically visible to a discerning eye, but the visible shadow is still confined to within ~4 dp around the bounding box (per the Figma `radius: 8, spread: 3` ambient layer's reach) and reads as a generic depth treatment, not as a "wrong" shape. The visual fidelity loss is judged acceptable given the dark theme is the canonical comparison surface for AC1/AC5.

Flagged under `## Open questions` so code-review can object if they disagree.

### Colour binding — AC3 rationale

AC3 reads: *"No hardcoded `Color(0xFF…)` for the shadow tint — uses theme tokens."*

The spec does **not** override `ambientColor` or `spotColor` on `Modifier.shadow(…)`; the framework default `DefaultShadowColor` (Compose constant, evaluates to `Color.Black`) is used. The interpretation:

- AC3 forbids writing `ambientColor = Color(0xFF000000)` or `spotColor = Color(0xFF...)` literals in the call site — i.e. no per-call `Color(0xFF…)` hex literal is introduced.
- `Color.Black` (Compose framework constant) and `DefaultShadowColor` (framework default fallback) are **not** literal hex values written by this ticket; they are framework constants, the same way `Color.Transparent` is.
- M3's `ColorScheme` defines no `shadow` token in any current Material 3 spec (verified by reading `androidx.compose.material3.ColorScheme` field list — there is `outline`, `outlineVariant`, `scrim`, but no `shadow`). There is no theme token *to* use; the framework default is the canonical answer.

AC3 is satisfied as-is: no new `Color(0xFF...)` literal is introduced, and the default colour matches Figma's `#000000`-with-alpha shadow specification.

### Scope: logo only, not Hero group

The Figma effect is bound at node `6:33` (Hero), which contains the logo Icon (`6:34`), the title group (`6:36` — "Pyrycode Mobile" + "Control Claude sessions on your phone."), and the body paragraph (`6:39` — "Pyrycode runs Claude…"). The ticket body scopes the change to the logo container only, and this spec honours that scope.

Visual justification (so the developer + reviewer understand the trade-off):

- Text glyphs in the Hero block are `MaterialTheme.colorScheme.onSurface` (`#E0E2E8` in dark mode, `#191C20` in light) and `onSurfaceVariant` (`#C2C7CF` / `#42474E`). On dark theme, an `M3/Elevation Light/3` shadow under those glyphs would be approximately invisible (the shadow is black-with-alpha on near-black surface; text is light-grey on near-black surface — the shadow has no contrasting background to be seen against). On light theme, the shadow under glyph rectangles would be slightly visible as a soft grey haze, but applying `Modifier.shadow(…)` to the entire Hero `Column` would produce a single rectangular shadow under the whole 280-tall block — visually heavy and not what Figma's per-glyph CSS `drop-shadow` filter produces.
- Faithfully reproducing Figma's per-glyph drop-shadow under the text would require `TextStyle(shadow = Shadow(…))` on each of the three `Text` composables (title, subtitle, body), which is **out of scope** for this ticket (would touch four lines, all on text composables the ticket body doesn't mention).
- The logo Icon is the only element with sufficient luminance and shape solidity for the elevation effect to read as intentional depth. Scoping shadow to the Icon therefore achieves the visible intent of the Figma edit (the logo gains depth) without making three text glyphs' text-shadow API choices in passing.

This scoping decision is flagged under `## Open questions` so PO + code-review can file a follow-up if they want the Hero-level treatment after seeing the merged result.

### Out of scope (do not implement)

- Any change to `ChannelListScreen.kt` — the second consumer of `R.drawable.ic_pyry_logo` (28 dp nav-bar icon) remains flat. The Figma source for the nav-bar logo location (top app bar, channel list) has no elevation binding; applying one here would invent design intent.
- Any change to `WelcomeScreenTest.kt` — tests match by visible text, not by drawable rendering. AC4 passes through unchanged.
- Any change to text composables (`Text("Pyrycode Mobile", …)`, `Text("Control Claude sessions on your phone.", …)`, `Text("Pyrycode runs Claude…")`). No `TextStyle(shadow = Shadow(…))` added.
- Any extraction of a `PyryLogo` composable wrapper, refactor to `Surface(shadowElevation = …, color = Color.Transparent)`, or change to the `Icon(tint = MaterialTheme.colorScheme.primary)` colour pathway. Single-modifier addition only.
- Any change to `Color.kt`, `Theme.kt`, or addition of a custom `MaterialTheme` `shadow` token.
- Any change to the `.size(width = 92.dp, height = 104.dp)` size modifier, the painter, the contentDescription, the tint, the surrounding Column / Box layout, or the radial-gradient `drawBehind` background.

## State + concurrency model

None. `Modifier.shadow(…)` is a draw-pass modifier with no recomposition cost beyond the existing Icon render — Compose computes the shadow on the GPU draw layer once per recomposition of the Icon. `WelcomeScreen` remains a pure stateless composable. No `remember`, no `LaunchedEffect`, no new state surface.

## Error handling

N/A. `Modifier.shadow(elevation = 6.dp, …)` cannot fail at runtime; invalid dp values are caught at compile time. The Compose `RectangleShape` constant is non-null. No I/O, no fallible computation.

## Testing strategy

- **`./gradlew assembleDebug`** must succeed.
- **`./gradlew lint`** must remain green. `Modifier.shadow(…)` is part of the standard Compose UI surface; no lint warnings expected.
- **`./gradlew connectedAndroidTest`** (device required) — re-runs `WelcomeScreenTest` (`pairedCta_isDisplayed`, `setupCta_isDisplayed`, `tappingPairedCta_invokesOnPairedOnly`, `tappingSetupCta_invokesOnSetupOnly`) unchanged. AC4 is satisfied iff all four tests pass. None of them assert on logo rendering, shadow presence, or elevation.
- **Preview verification (AC2)** — both `@Preview` composables (`WelcomeScreenLightPreview`, `WelcomeScreenDarkPreview`, both at 412 × 892) must render without throwing. The light preview shows the logo on `surfaceLight = #F8F9FF` with a visible soft grey shadow under the 92 × 104 dp bounding box (rectangle, not snowflake silhouette — see "Shape caveat"). The dark preview shows the logo on `surfaceDark = #101418` with a barely-perceptible dark soft glow under the bounding box.
- **Visual fidelity verification (AC1, AC5)** — code-reviewer fetches the current Figma `6:32` screenshot via `mcp__plugin_figma_figma__get_screenshot(fileKey: "g2HIq2UyPhslEoHRokQmHG", nodeId: "6:32")` and compares side-by-side against the rendered dark preview. Acceptance: the snowflake glyph reads with intentional depth (not flat); the rectangular-shadow approximation is judged within tolerance for AC5; if reviewer judges the rectangle-vs-silhouette difference visible enough to reject, the verdict goes via standard `needs-rework:code-review` flow rather than via spec edit.
- **No new unit tests, no new instrumented tests.** Compose `Modifier.shadow(…)` has no programmatic test surface in this codebase (no `composeTestRule` matcher exists for "asserts shadow elevation is N dp"). Visual verification is reviewer-side Figma comparison, as established by #150, #149, #167.

## Open questions

- **Scope: logo only vs. Hero block.** The Figma effect is bound on Hero (`6:33`), the ticket and this spec scope it to the logo (`6:34`). Rationale captured under "Scope: logo only, not Hero group" above. **Impact on AC5:** if reviewer compares Figma against the rendered dark preview and judges the title/body text *also* needs shadow, this is a follow-up ticket (would touch three `Text` composables with `TextStyle(shadow = Shadow(…))` and is out of scope here). Developer should mention this scope choice in the PR description so the reviewer can decide whether to file the follow-up.
- **Shape caveat: rectangle vs. snowflake silhouette.** The shadow's outline is the Icon's bounding-box rectangle, not the snowflake glyph's alpha-derived silhouette. This is a fundamental Compose API limitation (see "Shape caveat" above for the rejected alternatives). **Impact on AC5:** if reviewer judges the rectangular shadow visibly wrong vs. Figma's silhouette shadow, a follow-up ticket exploring `graphicsLayer { renderEffect = BlurEffect(…) }` or a baked-in drawable shadow can be filed. Both approaches are weeks-of-detail-work to do well; the current spec is the right starting point. Developer should mention this caveat in the PR description.
- **Shadow on the light theme is more visible than on the dark theme.** Both `@Preview` variants must render correctly (AC2), but the rectangle shadow shape will read more clearly on `surfaceLight = #F8F9FF` than on `surfaceDark = #101418`. If reviewer judges the light-preview shadow ugly (rectangular halo around a snowflake), the same follow-up options apply.
