# #121 — Scanner reticle: scan-line drop-shadow glow

XS refinement to the private `Reticle()` composable in `ScannerScreen.kt`. Replace the paired "blurred glow Box + solid line Box" the #60 polish landed with a single scan-line element that carries a CSS-equivalent drop-shadow `0px 0px 12px 0px rgba(122,184,232,0.6)` matching Figma node `13:2`'s `28:108` "Scan line" layer.

No data-layer, navigation, DI, or theme changes. No public-API changes. The rest of `ScannerScreen.kt` (corner brackets, atmosphere stripes, radial gradients, `pyry pair` hint card, previews, the `ScannerScreen(onTap)` signature) is **out of scope** — #60 shipped those correctly and this ticket explicitly excludes touching them.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:167-196` — the current `Reticle()` private composable; the two scan-line `Box`es (lines 176-194) are the surgical target.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:1-46` — the existing import block; you will add a few graphics imports and drop two `androidx.compose.ui.draw.blur*` imports here.
- `docs/knowledge/codebase/60.md` — the #60 polish that landed the two-box pattern this ticket replaces; the "Lessons learned" entry about `MaterialTheme.colorScheme` not being addressable from `DrawScope` lambdas is load-bearing for the implementation below.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — confirm `colorScheme.primary` is the glacier-blue token (`#9dcbfc` in dark, mapped per scheme in light); the AC explicitly binds the scan-line and its shadow to `MaterialTheme.colorScheme.primary` at the Figma-specified alpha.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

The scan-line in the locked Figma node (`28:108` "Scan line" inside the `13:11` Reticle frame) is a single 232×2px rectangle positioned `left=8px, top=123px` inside the 248×248 reticle, filled with `Schemes/primary` (glacier-blue `#9dcbfc` in dark) and decorated with one drop shadow: `box-shadow: 0px 0px 12px 0px rgba(122,184,232,0.6)` (symmetric, no offset, 12px blur, no spread). One line, one shadow — the current code's stacked blurred-box-behind-solid-line is a stand-in that this ticket retires.

## Context

#60 (`docs/knowledge/codebase/60.md`) shipped the Scanner screen against Figma `13:2` and adopted `Modifier.blur(12.dp, BlurredEdgeTreatment.Unbounded)` on a 6dp-tall glow `Box` stacked behind a 2dp-tall solid line `Box` as the halo pattern. That approach reads visually similar at preview-scale but is structurally a two-element halo, not a one-element drop-shadow:

- The glow `Box` is 6dp tall — chunkier than Figma's 2dp source rect.
- Both `Box`es bind to `colorScheme.primary` directly. The Figma shadow color is `rgba(122,184,232,0.6)` — close to dark-theme `primary` but not identical to its light-theme counterpart, so the at-alpha binding is "primary @ α=0.6" by design, per AC4.
- The polish ticket calls out *"one scan-line element in the reticle, not two"* (AC3) and *"a true drop-shadow on the line, not as a separate blurred Box behind it"* (AC2). The fix is structural, not just visual.

Compose has no built-in modifier that renders a CSS-equivalent `box-shadow` with zero offset, arbitrary blur radius, and arbitrary color. `Modifier.shadow(elevation, spotColor, ambientColor)` uses the platform elevation shadow system — directional, not symmetric, and the `elevation`-to-blur-radius mapping is approximate and API-level-dependent. The faithful primitive is `android.graphics.BlurMaskFilter` applied to a `Paint`, drawn into the native canvas under the solid rect — the standard Android route for CSS-style blurred shadows. This spec specifies that path.

## Design

### Reticle scan-line: replace lines 176-194 with a single `Canvas`

The current `Reticle()` composable (file lines 167-196) draws four `Corner` children plus two scan-line `Box`es. Keep the corners untouched. Replace the two scan-line `Box`es with one `Canvas` composable, centered in the 248dp reticle box, that draws the shadow and the solid line into a single drawing surface.

**Composable shape** (signature sketch, ~25 lines):

```kotlin
val lineColor = MaterialTheme.colorScheme.primary
val shadowArgb = lineColor.copy(alpha = 0.6f).toArgb()
// ... corners unchanged ...
Canvas(
    modifier = Modifier
        .align(Alignment.Center)
        .padding(horizontal = 8.dp)
        .fillMaxWidth()
        .height(<canvas height>),
) {
    // 1. Construct a Paint with BlurMaskFilter(<blur radius>, NORMAL) at shadowArgb.
    // 2. drawIntoCanvas { canvas.nativeCanvas.drawRect(...) } — paint the 2dp-tall
    //    line rect through the blur-masked paint, centered vertically in the Canvas.
    // 3. drawRect(color = lineColor, topLeft = ..., size = ...) — paint the crisp
    //    2dp line at the same y, on top of the blurred shadow layer.
}
```

The Canvas is one Compose element. It draws two rasterized layers (shadow under crisp line) into the same drawing surface — that is the "single scan-line element with a drop shadow" the AC requires, structurally identical to how Figma renders the source layer. There is no second `Box` and no `Modifier.blur` after this change.

### Canvas dimensions

The Canvas must be tall enough to show the full blur falloff without clipping the halo. `BlurMaskFilter` extends the rendered pixels beyond the source-rect bounds by roughly the blur radius on every side.

- Source line height: `2.dp`
- Blur extends ~`12.dp` above and ~`12.dp` below the line.
- Canvas height: `26.dp` (12 + 2 + 12) — round up if needed for safety.

Center-aligning a 26dp-tall Canvas inside the 248dp reticle puts the Canvas at y=111..137. Drawing the 2dp line at the Canvas's vertical center (y=12dp..14dp local) lands the line at y=123..125 in the reticle — Figma's specified y=123 placement. ✓

The Canvas inherits `Modifier.padding(horizontal = 8.dp).fillMaxWidth()` from the existing two-box approach, so the resulting line width is `248dp − 16dp = 232dp` — Figma's specified `w=232px`. ✓

### Imports

- **Add:**
  - `android.graphics.BlurMaskFilter`
  - `androidx.compose.ui.graphics.Paint`
  - `androidx.compose.ui.graphics.drawscope.drawIntoCanvas`
  - `androidx.compose.ui.graphics.nativeCanvas`
  - `androidx.compose.ui.graphics.toArgb`
- **Remove:**
  - `androidx.compose.ui.draw.BlurredEdgeTreatment`
  - `androidx.compose.ui.draw.blur`
- Keep all other imports as-is. `androidx.compose.foundation.Canvas` is already imported (line 4) for the atmosphere-stripes Canvas.

### Color binding — the AC4 contract

AC4: *"Zero hardcoded color literals; the glow color binds to `MaterialTheme.colorScheme.primary` (glacier-blue) at the alpha Figma specifies."*

- The crisp line color = `MaterialTheme.colorScheme.primary` (no alpha).
- The shadow color = `MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)`, converted to ARGB via `.toArgb()` for `Paint`'s `color` field.
- Do **not** introduce `Color(0xFF7AB8E8)` literally to "match Figma's `rgba(122,184,232,0.6)` exactly." The Figma shadow rgb happens to match dark-scheme primary; the binding is by token+alpha, not by raw hex. (Same precedent #60 set with the radial gradient stops — token + alpha, not literal.)

Per #60's "MaterialTheme.colorScheme is not addressable from DrawScope" lesson: hoist `lineColor` and `shadowArgb` to `val`s at the top of `Reticle()`, before the `Box(modifier = modifier.size(248.dp))` call. The `Canvas` lambda runs as a `DrawScope` extension; closing over hoisted `val`s is the only way to use theme colors inside it.

### Drop-shadow rendering details

- `BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)` produces a symmetric Gaussian-like blur around the painted shape — the right mode for a CSS `box-shadow: 0 0 12px 0` analog (`SOLID`/`OUTER`/`INNER` modes do not match CSS semantics).
- `radius = 12.dp.toPx()` — convert dp to pixels inside the `DrawScope` lambda, since `BlurMaskFilter` takes pixel units.
- The `Paint` constructed via `androidx.compose.ui.graphics.Paint()` exposes the underlying framework Paint via `.asFrameworkPaint()`; `maskFilter` and `color` (as ARGB int) live on the framework paint. Set `isAntiAlias = true`.
- Draw order: shadow rect first (through the blur-masked Paint), then the crisp line rect on top. The crisp line uses `DrawScope.drawRect` directly — no need for the native canvas.

### What does not change

- The four `Corner` composables and their layout — unchanged.
- The outer `Box(modifier = modifier.size(248.dp))` of `Reticle()` — unchanged.
- `ScannerScreen`, `HintCard`, atmosphere stripes, radial gradients, top app bar, "Trouble scanning?" `TextButton`, `Surface`, `pointerInput` — unchanged.
- The two `@Preview` composables (light, dark) — unchanged. They will pick up the new rendering automatically and (per AC5) need a visual check that the result reads as Figma.

## State + concurrency model

N/A. `Reticle()` is a stateless private composable. No `ViewModel`, no `StateFlow`, no `LaunchedEffect`, no coroutines. This ticket touches a pure-presentation Canvas and adds no state.

## Error handling

N/A. No I/O, no parsing, no platform calls that can fail. `BlurMaskFilter` is part of `android.graphics` and has been available since API 1; min SDK is 33. Hardware acceleration is on for all `WindowManager.LayoutParams.TYPE_APPLICATION` windows by default — `BlurMaskFilter` renders correctly with HW accel on API 28+, so production is in the supported envelope.

## Testing strategy

- **No unit-test changes required.** `Reticle()` is a private composable with no observable state.
- **No instrumented-test changes required.** The existing Scanner compose tests (per #101, `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` if present, or `app/src/test/...` for any Robolectric-shaped variant) assert structural elements — the top app bar title, the "Trouble scanning?" `TextButton`, `onTap` invocation — none of which target the scan-line's rendering. Run the existing suite as a no-regression sanity check; do not add new assertions for shadow pixels.
- **Manual visual check.** After implementing, run `./gradlew assembleDebug` then verify the two `@Preview` composables (light + dark) in the IDE preview pane render a thin (2dp) line with a soft glacier-blue glow extending ~12dp above and below, with no visible "stacked box" seam. Compare to the Figma `13:2` screenshot. Acceptance criterion AC5 (previews match Figma) is satisfied when there is one continuous line with a symmetric soft halo, not a chunky 6dp band under a 2dp line.
- **Build verification.** `./gradlew assembleDebug` + `./gradlew lint` should pass with zero new warnings. `./gradlew test` and `./gradlew connectedAndroidTest` (if a device is attached) should pass.

## Open questions

- **None blocking.** One non-blocking implementation detail: if `BlurMaskFilter.Blur.NORMAL` reads too soft compared to Figma at the developer's preview, `BlurMaskFilter.Blur.OUTER` is a viable second choice (renders only outside the source rect, leaving the inner 2dp area to the crisp line on top — same visual result, slightly different pixel composition). Stay with `NORMAL` unless the preview clearly reads off; do not litigate this in PR review.
