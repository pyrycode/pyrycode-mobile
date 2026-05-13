# Spec — Welcome Screen Figma polish (#57)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

A dark-surface 412×892 onboarding screen with a top-anchored hero block (snowflake+cursor logo, large headline "Pyrycode Mobile", title-medium tagline "Control Claude sessions on your phone.", and a body paragraph) and a bottom-anchored CTA stack (filled primary button with a QR-scanner leading icon, text-style secondary button, and an alpha-reduced open-source footer). The defining visual decoration is a low-saturation deep-navy **radial glow** painted behind the hero, centered roughly at (48%, 30%) of the canvas — the wallpaper-tinted atmospheric overlay the AC names.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` (current 124-line scaffold from #7) — the file being rewritten. Preserve the `WelcomeScreen(onPaired, onSetup)` signature and the two `@Preview` composables (light + dark, 412×892) exactly.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:88-100` — the only call site. Confirms `onPaired` → scanner nav, `onSetup` → external `Intent.ACTION_VIEW` for `https://pyryco.de/setup`. The composable stays callback-shaped; do **not** add `Context` / `Intent` / `NavController` usage inside `WelcomeScreen.kt`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:17-91` — `darkScheme` / `lightScheme` definitions. Both schemes wire the full M3 token set (primary, primaryContainer, surface, onSurface, onSurfaceVariant, etc.) so every color the Figma uses maps to a token.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — confirm M3 typography slots (`headlineLarge`, `titleMedium`, `bodyLarge`, `labelLarge`, `labelSmall`) resolve through `AppTypography`. The Figma maps onto these slots 1:1; no custom `TextStyle()` required.
- `app/src/main/res/drawable/` — only `ic_launcher_*` exists today. New SVG-derived vector drawables land here.
- `gradle/libs.versions.toml` — Compose BOM, `androidx-compose-material3`, `androidx-compose-material-icons-core` already wired. No new dependencies (ticket Technical Notes: "Don't introduce new icon packages").
- Spec for #7 at `docs/specs/architecture/7-welcome-screen-scaffold.md` — the original architecture this ticket revises; useful to understand what's intentionally kept (signature, previews, stateless purity).

## Context

#7 shipped a stateless M3-default Welcome screen with `WelcomeScreen(onPaired, onSetup)`. #57 keeps that public contract and the stateless purity, but rewrites the body to match the locked Figma node 6:32: real logo, title + subtitle + body copy ordering, primary CTA with leading QR-frame icon, text-style secondary CTA, footer line, and a radial glow overlay.

This is the **first Phase 1.5 smoke-test ticket** through the Figma-anchored pipeline. The spec deliberately drives every visual property to either a `MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*` slot or to a vector drawable — code-review will check that no `Color(0xFF…)` literal and no `TextStyle(...)` default survives.

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt`
- **Add:** `app/src/main/res/drawable/ic_pyry_logo.xml` (snowflake + center cursor)
- **Add:** `app/src/main/res/drawable/ic_qr_scan_frame.xml` (QR scanner leading icon)

No other files change. `MainActivity.kt` already calls `WelcomeScreen(onPaired = ..., onSetup = ...)` and that call site is untouched.

### Public surface (unchanged)

```kotlin
@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
)
```

No `modifier` parameter. No new callbacks. The two `@Preview` composables remain (`WelcomeScreenLightPreview`, `WelcomeScreenDarkPreview` at 412×892).

### Layout tree

The current `Column(Arrangement.SpaceBetween)` is replaced by a layered `Box` so the radial glow can sit behind the content as an atmospheric overlay rather than as a sibling that takes layout space.

```
Box(Modifier.fillMaxSize())
├── Surface(color = colorScheme.surface, Modifier.fillMaxSize())   ← solid base
├── Box(Modifier.fillMaxSize().drawBehind { radialGlow })           ← atmospheric overlay
└── Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp),
          verticalArrangement = Arrangement.SpaceBetween,
          horizontalAlignment = Alignment.Start)                     ← content
    ├── Column(spacedBy = 28.dp, Modifier.padding(top = 136.dp))     ← Hero
    │   ├── Icon(R.drawable.ic_pyry_logo, size = 104.dp, tint = colorScheme.primary)
    │   ├── Column(spacedBy = 8.dp)
    │   │   ├── Text("Pyrycode Mobile",                 style = headlineLarge,  color = onSurface)
    │   │   └── Text("Control Claude sessions on your phone.",
    │   │                                                style = titleMedium,    color = onSurfaceVariant)
    │   └── Text("Pyrycode runs Claude on your computer or home server. Channels and
    │             conversation history live on your machine, accessible from any device.",
    │                                                    style = bodyLarge,      color = onSurfaceVariant)
    └── Column(spacedBy = 12.dp, Modifier.fillMaxWidth().padding(bottom = 16.dp))  ← CTAs + footer
        ├── Button(onClick = onPaired, Modifier.fillMaxWidth().height(56.dp))
        │   ├── Icon(R.drawable.ic_qr_scan_frame, size = 20.dp)
        │   ├── Spacer(Modifier.width(8.dp))
        │   └── Text("I already have pyrycode")                ← labelLarge (Button default)
        ├── TextButton(onClick = onSetup, Modifier.fillMaxWidth().height(56.dp))
        │   └── Text("Set up pyrycode first")                  ← labelLarge (TextButton default)
        └── Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Center)
            └── Text("Open source · github.com/pyrycode/pyrycode-mobile",
                  style = labelSmall, color = onSurfaceVariant.copy(alpha = 0.55f))
```

Numerical paddings (`136.dp` top, `32.dp` horizontal, `28.dp` hero gap, `12.dp` CTA gap, `8.dp` icon spacing, `16.dp` bottom inset) are layout dp, not theme tokens — they are not banned by the "no hardcoded values" AC (which targets color and typography literals). Take the values from the Figma `data-node-id` payload above; tune ±4dp if the preview reads better.

### Color → theme token bindings (zero hardcoded literals)

Every color the Figma payload encodes maps to a Material 3 scheme token:

| Figma value         | M3 token                                     | Used by                          |
|---------------------|----------------------------------------------|----------------------------------|
| `rgb(16,20,24)`     | `colorScheme.surface`                        | Solid background                 |
| `rgba(0,51,85,…)`   | `colorScheme.primaryContainer.copy(alpha=…)` | Radial glow start stop           |
| `#e0e2e8`           | `colorScheme.onSurface`                      | Title text                       |
| `#c2c7cf`           | `colorScheme.onSurfaceVariant`               | Subtitle / body / footer text    |
| `#9dcbfc`           | `colorScheme.primary` (default `Button` bg)  | Primary CTA fill                 |
| `#035` (#003355)    | `colorScheme.onPrimary` (default `Button` fg)| Primary CTA label + icon tint    |

The default `Button` and `TextButton` color resolution from `MaterialTheme` already produces these mappings — no `ButtonDefaults.buttonColors(...)` override is required and none should be added. `Color.Transparent` is the only non-token color permitted (as the terminal stop of the radial gradient); it is a `Color` constant, not a hex literal.

### Radial glow

Implementation contract — a `Modifier.drawBehind` (or equivalent `Modifier.background(brush = …)`) that paints a radial gradient behind the content layer:

- Center: roughly (48% width, 30% height) of the canvas — the Figma SVG `cx/cy = (196, 265)` on a 412×892 frame translates to those proportions.
- Radius: ~53% of height (the Figma `gradientTransform` matrix produces an ellipse with semi-axes ~285×472; approximating as a circular radial of ~470dp is visually acceptable and avoids re-implementing the matrix transform).
- Colors: `listOf(colorScheme.primaryContainer.copy(alpha = 0.6f), Color.Transparent)`.

The developer may fine-tune center/radius/alpha by comparing the preview against the Figma screenshot — the goal is visual parity with the atmospheric overlay, not pixel-exact reproduction of the SVG matrix. **Do not** introduce a custom `Color(0x00335…)` to match the Figma stop literally; the token `primaryContainer` is the brand intent.

### Drawable assets

Both new drawables come from the Figma payload via the developer agent's inlined `figma-implement-design` workflow — `get_design_context` returns short-lived asset URLs (the `imgGroup*`, `imgVector`, `imgQrScannerIcon` constants in the payload). Download via the Figma MCP, convert SVG → Android Vector Drawable (`drawable-tools` / Android Studio's "Vector Asset" import, or hand-conversion for simple paths), commit under `app/src/main/res/drawable/` (NOT `assets/`).

- **`ic_pyry_logo.xml`** — composed snowflake + center cursor. Source: node `9:2` ("Pyry Logo · snowflake + center cursor"). 104dp viewport. Single tintable path color so `Icon(tint = colorScheme.primary)` recolors it for both light and dark themes. If the SVG comes back with hardcoded fills, hand-edit the resulting vector XML to use `android:fillColor="?attr/colorPrimary"` or `@android:color/white` (then rely on Compose `Icon` tint).
- **`ic_qr_scan_frame.xml`** — QR scanner four-corner frame. Source: node `9:41`. 20dp viewport. Same tinting rule.

`Icon(painter = painterResource(R.drawable.ic_pyry_logo), contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(104.dp))` is the canonical render shape. `contentDescription = null` is correct — the logo is decorative, the title text below carries the screen identity.

### Imports (allow-list)

Material 3 (`androidx.compose.material3.*`), foundation (`androidx.compose.foundation.layout.*`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.*`), runtime (`androidx.compose.runtime.Composable`), ui (`androidx.compose.ui.Modifier`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.draw.drawBehind`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.graphics.Brush`, `androidx.compose.ui.graphics.Color` — for `Color.Transparent` only, `androidx.compose.ui.res.painterResource`, `androidx.compose.ui.text.style.TextAlign`, `androidx.compose.ui.tooling.preview.Preview`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.unit.sp` — **not used**), `android.content.res.Configuration`, `de.pyryco.mobile.R`, `de.pyryco.mobile.ui.theme.PyrycodeMobileTheme`.

**Banned**: `androidx.navigation.*`, `androidx.activity.*`, `android.content.Intent`, `androidx.compose.material.icons.*` (per ticket: "Don't introduce new icon packages — assets come from the Figma payload only"), any custom `TextStyle(...)` constructor, any `Color(0x…)` literal.

## State + concurrency model

None. Pure stateless composable. No `remember`, no `LaunchedEffect`, no `ViewModel`. Both callbacks (`onPaired`, `onSetup`) are passed straight to `onClick` without capture — they remain stable references at the `MainActivity.kt` call site.

## Error handling

N/A — no I/O, no state. The radial glow brush is constructed per-recomposition; this is fine because the colors are theme-derived `Color` values and `Brush.radialGradient` is cheap. No `remember` wrapping required for performance at this scale.

## Testing strategy

The two `@Preview` composables (light + dark, 412×892) plus `./gradlew assembleDebug` plus `./gradlew lint` are the verification surface, same as #7. Code-review will additionally fetch the Figma screenshot via `mcp__plugin_figma_figma__get_screenshot` and visually compare it against the dark preview to confirm fidelity.

- No new unit tests. A stateless layout composable doesn't have a meaningful unit-test surface; previews are the test artifact.
- No new `ComposeTestRule` interaction tests. Click behavior is unchanged from #7 (callback shape preserved); no regression risk worth a UI test of its own.
- Preview must compile and render. The two existing preview functions stay verbatim (no signature change to `WelcomeScreen` means no preview signature change).

## Open questions

- **Headline copy: ticket vs Figma mismatch.** Ticket AC2 quotes the headline as `"Your Claude sessions, on your phone."`, but Figma node 6:32 renders "Pyrycode Mobile" (headlineLarge) as the headline and "Control Claude sessions on your phone." (titleMedium) as the tagline. The ticket title is "match Welcome screen to **Figma** node 6:32" — Figma is the locked source. This spec follows Figma. If PO intended the AC2 copy literally, the rework should adjust the Figma file rather than the implementation; flag in code-review if uncertain.
- **Body paragraph copy.** AC2 says "supporting paragraph (existing copy preserved)" but the existing copy is `"Your pyrycode CLI, in your pocket."`, while Figma has a substantially longer two-sentence paragraph (`"Pyrycode runs Claude on your computer or home server. Channels and conversation history live on your machine, accessible from any device."`). Following Figma. Same flag-to-PO disposition if the literal AC was intended.
- **Glow alpha.** `primaryContainer.copy(alpha = 0.6f)` is a starting point; the developer should iterate against the dark preview until it visually matches the Figma screenshot's atmospheric intensity. Acceptable range: 0.4–0.8.

## Out of scope (do not implement)

- Adopting `material-icons-extended` for a built-in QR icon — ticket explicitly bans new icon packages.
- Adding `ButtonDefaults.buttonColors(...)` overrides — defaults already produce the Figma colors via theme tokens.
- Light-theme color literal hardcoding to match Figma's dark-mode-only mockup — the same theme bindings produce a sensible light variant; preview verifies it renders. Do not gate on Figma having a light mockup.
- Logo asset refinement (multi-color, gradient fills, animation) — single tintable path is the contract for this ticket.
- `MainActivity.kt` changes — call site is correct.
- Any data-layer, navigation, or DI changes.
