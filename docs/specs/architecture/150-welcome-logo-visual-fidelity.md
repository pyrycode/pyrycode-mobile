# Spec — Welcome logo visual fidelity vs Figma LogoCenterCursor (#150)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

The relevant subtree is **node `9:2` — "Pyry Logo · snowflake + center cursor"**, a 104×104 mark composed of (a) six identical arm assets under `Group` `9:3`, each placed in a 14%-wide × 40%-tall container and rotated at 60° intervals so each arm radiates from the canvas centre to ~10 % from the corresponding edge; and (b) a single `Vector` `9:40` (the centre cursor) — a small 10 % × 14 % filled rectangle at canvas centre. The entire mark uses a single bound colour — `Schemes/Primary` (`#9DCBFC` in dark mode, `#32628D` in light) — applied uniformly to every arm stroke and the cursor fill.

## Files to read first

- `app/src/main/res/drawable/ic_pyry_logo.xml` (entire file, 95 lines) — the **only file modified by this ticket**. Current structure: one centre-rectangle `<path>` followed by six `<group rotation="…">` arm groups. Each arm group uses identical `pivotX="22.464"` `pivotY="8.944"` and identical `translateX="29.536"` `translateY="43.056"` (which place the pivot at canvas (52, 52) — the centre); rotations are `−90`, `−30`, `30`, `90`, `150`, `210`. The arm `pathData` is buggy (see `## Context`) and must be revised; group attributes, viewport dimensions, stroke width, and the centre-rectangle path are correct and must be preserved verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:71-76` — the `Icon(painter = painterResource(R.drawable.ic_pyry_logo), …, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(104.dp))` call. **Read but DO NOT modify.** Confirms the rendering pathway: Icon applies a single tint to the drawable, which is exactly what Figma specifies (one bound colour). The ticket body's framing of "Icon collapses every path to a single `primary` color … rather than the differentiated stroke/fill treatment" is partially misleading — see `## Context` for resolution.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:115` (`primaryDark = Color(0xFF9DCBFC)`) and `:4` (`primaryLight = Color(0xFF32628D)`) — the theme tokens that the Icon tint resolves to in each scheme. Confirm both produce contrast against `surfaceDark` (`#101418`) and `surfaceLight` (`#F8F9FF`) respectively, so AC2 (visible in both `@Preview` variants) is satisfied automatically by keeping the existing Icon+tint pathway.
- `docs/specs/architecture/57-welcome-screen-figma-polish.md` (the section starting `**ic_pyry_logo.xml**` at line 110) — the original drawable spec. The intent it states ("composed snowflake + center cursor", "single tintable path color so `Icon(tint = colorScheme.primary)` recolors it for both light and dark themes") is still correct; only the geometry encoding in the shipped XML went wrong.
- `docs/specs/architecture/149-welcome-pill-cta-and-padding.md` — the sibling split from #120 that this ticket co-exists with. Confirms the project's idiom of keeping `#FFFFFF` in vector XML as a "tinting bed" rather than wiring `?attr/colorPrimary`; the same idiom applies here.

## Context

`ic_pyry_logo.xml` (shipped via #57) is structurally close to Figma node `9:2` — same viewport, correct centre cursor, six rotated arm groups at the right angles — but the **arm `pathData` is miscalibrated** in two ways that together collapse the mark visually:

1. **Arm span is centred on the canvas centre rather than radiating from it.** Each arm's main shaft is `M1.664,8.944 H43.264` (length 41.6) with the group's `pivotX="22.464"` sitting at the *midpoint* of that shaft. After the `translateX="29.536" translateY="43.056"` translation, the pivot lands at canvas (52, 52). After the per-group rotation (e.g. `−90°`), the arm is a vertical line spanning canvas y ∈ [31.2, 72.8] — i.e. it occupies only the middle 40 % of the canvas and **passes through** the centre. Figma's arm container spans `top-[10%]` to `bottom-1/2` (`y ∈ [10.4, 52]`), so each arm radiates *outward only* from centre to ~10 % from the canvas edge. Opposite arms (rot=−90 vs rot=+90, etc.) overlap entirely on top of each other in the current XML instead of forming distinct radial spikes.
2. **Inner branches sit at the canvas centre** — `M22.464,8.944 L29.744,1.664 …` puts the inner branch fork *at the pivot*, which translates to canvas (52, 52) — exactly where the centre cursor rectangle is. The intended spike-pair-near-the-tip pattern shows up as a tangled overlay on the cursor instead.

The result reads, on device, as a dense knot in the middle of the canvas with no arms reaching out — the opposite of a snowflake. The ticket body's "flat tinted glyph" perception is a downstream consequence; the root cause is geometric.

**Important clarification re. the ticket's "differentiated stroke/fill treatment" framing:** Figma node `9:2`'s `get_variable_defs` returns *only* `Schemes/Primary` (verified during architect run). The visual differentiation the ticket points at is between **stroke geometry (the six arm spikes)** and **fill geometry (the centre cursor rectangle)** — i.e. shape-primitive differentiation, not colour differentiation. Both render in the same `Schemes/Primary` blue. This means:

- The current `Icon(tint = MaterialTheme.colorScheme.primary)` rendering pathway is **structurally correct** and must not change. Switching to `Image` + `ColorFilter` or to a `Canvas` block is unnecessary and would diverge from #57's idiom.
- The `#FFFFFF` placeholder fills in the drawable XML are intentional — they're the "tinting bed" that `Icon`'s `BlendMode.SrcIn` filter replaces with the theme primary at composition time. This is what #57 documented as "Single tintable path color so `Icon(tint = colorScheme.primary)` recolors it for both light and dark themes."

The fix is therefore **one file, geometry only**: rewrite arm `pathData` in `ic_pyry_logo.xml`. No Kotlin changes.

## Design

### File scope

- **Modify:** `app/src/main/res/drawable/ic_pyry_logo.xml`
- **Add new file:** none
- **Do not modify:** `WelcomeScreen.kt`, `Color.kt`, `Theme.kt`, any test, any other drawable, `MainActivity.kt`. The signature `WelcomeScreen(onPaired, onSetup, modifier)` and the call site in `MainActivity` are untouched.

### What stays exactly as it is

The following must be preserved byte-for-byte from the current `ic_pyry_logo.xml`:

- `<vector>` root attributes: `android:width="104dp"`, `android:height="104dp"`, `android:viewportWidth="104"`, `android:viewportHeight="104"`.
- The centre-cursor `<path>`: `android:fillColor="#FFFFFF"` `android:pathData="M46.8,44.72 H57.2 V59.28 H46.8 Z"`. This is a 10.4 × 14.56 rectangle at canvas (46.8, 44.72) → (57.2, 59.28), which is 45 % from left × 43 % from top with 10 % × 14 % dimensions — exact match for Figma node `9:40`'s `inset-[43%_45%]` placement.
- The six `<group>` wrappers' geometry attributes: identical `pivotX="22.464"`, `pivotY="8.944"`, `translateX="29.536"`, `translateY="43.056"` for every arm; rotations `−90`, `−30`, `30`, `90`, `150`, `210` (one per arm, in that order).
- Inside each group, the `<path>` element's non-geometry attributes: `android:strokeColor="#FFFFFF"`, `android:strokeWidth="3.328"`, `android:strokeLineCap="round"`, `android:strokeLineJoin="round"`.

The only thing that changes is the value of `android:pathData` inside each arm `<path>`. The same new `pathData` string is used in all six arm groups (geometry is identical; rotation differentiates them).

### Arm path contract — geometric intent

Local arm coordinates are defined so that the group's pivot `(22.464, 8.944)` sits at the centre end of the shaft (the end that lands at canvas centre after translation). The arm extends along local +x. After `translate(29.536, 43.056)` and the group's rotation, the pivot lands at canvas (52, 52) and the shaft tip lands at the canvas position 41.6 units away in the rotated +x direction — i.e. for the rot=−90 group, the tip is at canvas (52, 10.4), which is exactly the `top-[10%]` Figma container edge for arm `9:4`.

Each arm consists of:

- **Main shaft** — a single straight stroke of length 41.6 units, starting *at the pivot* and extending outward (in local +x).
- **Inner branch pair** — two short strokes at ~60 % along the shaft from pivot (local x ≈ 47.424), one going up-and-outward and one going down-and-outward at 45° to the shaft. Each branch is the same length as the existing "inner" branch in the buggy XML (delta ≈ 7.28 units in each axis).
- **Outer branch pair** — two shorter strokes at ~87.5 % along the shaft from pivot (local x ≈ 58.864), one going up-and-outward and one going down-and-outward at 45°. Each branch matches the existing "outer" branch length (delta ≈ 5.2 units in each axis).

Both branch pairs sit in the outer half of the arm, so they form a "spike" near the arm tip when rendered, mirroring Figma node `9:4`'s visible Y-spike-at-tip silhouette.

### Arm path contract — concrete pathData string

Each arm `<path>` uses this exact `pathData` (single line in the XML; spaces between subpaths are legal Android `pathData` separators):

```
M22.464,8.944 H64.064 M47.424,8.944 L54.704,1.664 M47.424,8.944 L54.704,16.224 M58.864,8.944 L64.064,3.744 M58.864,8.944 L64.064,14.144
```

Breakdown of each subpath, for review:

| Subpath | Geometry | Local coords | After translate + rot=−90 (canvas coords) |
|---|---|---|---|
| `M22.464,8.944 H64.064` | Main shaft, pivot → outer tip, length 41.6 | (22.464, 8.944) → (64.064, 8.944) | (52, 52) → (52, 10.4) |
| `M47.424,8.944 L54.704,1.664` | Inner branch, upper, 45°, length ≈ 10.3 | (47.424, 8.944) → (54.704, 1.664) | (52, 27.04) → (44.72, 19.76) |
| `M47.424,8.944 L54.704,16.224` | Inner branch, lower, 45°, length ≈ 10.3 | (47.424, 8.944) → (54.704, 16.224) | (52, 27.04) → (59.28, 19.76) |
| `M58.864,8.944 L64.064,3.744` | Outer branch, upper, 45°, length ≈ 7.35 | (58.864, 8.944) → (64.064, 3.744) | (52, 15.6) → (46.80, 10.40) |
| `M58.864,8.944 L64.064,14.144` | Outer branch, lower, 45°, length ≈ 7.35 | (58.864, 8.944) → (64.064, 14.144) | (52, 15.6) → (57.20, 10.40) |

This pathData replaces the existing buggy one — apply the same string to *all six* arm `<path>` elements (the six groups already encode the 60°-interval rotations). The substitution can be a single `replace_all` of the old pathData string with the new one inside `ic_pyry_logo.xml`; no other XML changes are needed.

### Why this geometry derivation is safe

- **Shaft length 41.6 reuses the existing length verbatim.** The buggy XML's shaft also had length 41.6 (from x=1.664 to x=43.264); we just shift it entirely to one side of the pivot so it radiates outward instead of straddling.
- **Branch positions reuse the existing 60 % / 87.5 % proportional placements** from the buggy XML (existing inner branches were at 0 % from pivot along the old centred shaft, and outer branches at 60 % along the half-shaft toward the outer end → equivalent to 50 % and 80 % of the new full shaft; we use 60 % / 87.5 % to place both pairs solidly within the outer half where Figma node `9:4` shows them visually).
- **Branch deltas (±7.28, ±5.2)** are the same axis-aligned deltas as the existing branches — so stroke weight harmony with the shaft is preserved.
- **Translation, pivot, rotation, stroke width, stroke caps** are all untouched, so the developer cannot accidentally break the 60°-symmetry or the stroke join behaviour.

### Colour binding — explicit decision against `?attr/colorPrimary`

AC3 reads: *"Zero hardcoded `Color(0xFF…)` literals introduced in Kotlin (drawable XML fills are spec-permitted; if non-tokenized colors are unavoidable in the XML, document the rationale in the spec)."* The drawable retains its `#FFFFFF` placeholder strokes/fill. Rationale:

- The colour the user sees comes from `Icon(tint = MaterialTheme.colorScheme.primary)`, which uses `BlendMode.SrcIn` to replace the drawable's per-pixel colour at composition time. The XML colour is a *placeholder* that the tint overrides — not a colour the user ever sees.
- Switching to `android:strokeColor="?attr/colorPrimary"` (a theme attribute reference) would not work in combination with `Icon(tint = …)` — the tint would re-colour over the theme-bound colour, producing a double-application that mostly works (both colours are `colorPrimary`) but conceptually muddles the contract and breaks if a caller ever instantiates the drawable without a tint.
- The alternative — switching the Compose side from `Icon(tint = …)` to `Image(painter = …, colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))` together with `?attr/colorPrimary` in the XML — is two-file churn for zero visual change, and would diverge from the codebase's existing idiom for tintable drawables (`ic_qr_scan_frame.xml` also ships as `#FFFFFF` placeholders for `Icon` tinting; see #57).
- Keeping `#FFFFFF` as the tinting bed is therefore the documented, intentional pattern. No Kotlin colour literal is introduced. AC3 is satisfied as-is.

### Out of scope (do not implement)

- Any change to `WelcomeScreen.kt`, including the Icon/Image swap discussed above. The `Icon(tint = MaterialTheme.colorScheme.primary)` line stays exactly as written.
- Any change to the centre-cursor `<path>` — it is already correct against Figma node `9:40`.
- Any change to the six `<group>` wrappers' attributes — pivot, translation, and rotations are correct.
- Any change to the viewport, stroke width, or stroke cap/join properties.
- Introducing a dedicated `PyryLogo` composable, theming the drawable via `?attr/`, or extracting the snowflake into a `ImageVector` declared in Kotlin — all out of scope; the ticket is a geometry repair, not a refactor.
- Adding an instrumented or unit test for visual fidelity — pixel-grade fidelity is checked at code-review via the AC4 Figma screenshot comparison; the existing `WelcomeScreenTest` cases pass through unchanged because they match by text, not by drawable rendering.

## State + concurrency model

None. The drawable is static XML and `WelcomeScreen` remains a pure stateless composable. No `remember`, `LaunchedEffect`, or `ViewModel`. The Icon composable handles rasterisation + tinting on the Compose recomposition path with no caller-visible state.

## Error handling

N/A — no I/O, no fallible computation. A malformed `pathData` string would surface as a build-time `aapt2` error or a render-time `IllegalArgumentException` in the Compose preview; either is caught before merge.

## Testing strategy

- **Existing instrumented tests stay passing without edits.** `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` matches the four screen elements by visible text ("I already have pyrycode", "Set up pyrycode first"). The logo has `contentDescription = null` and is not addressed by any test matcher; changing only its rendered geometry leaves the test surface untouched. The developer should run `./gradlew connectedAndroidTest` (device required) to confirm.
- **Build + lint stays clean.** `./gradlew assembleDebug` and `./gradlew lint` must remain green. No new lint warnings are expected — `pathData` formatting follows the same conventions as the existing arm strings.
- **Preview verification (AC2).** Both `@Preview` composables in `WelcomeScreen.kt` (`WelcomeScreenLightPreview`, `WelcomeScreenDarkPreview`, both at 412 × 892) must render. The light preview shows the logo in `primaryLight` (`#32628D`) on `surfaceLight` (`#F8F9FF`) — dark blue on near-white. The dark preview shows the logo in `primaryDark` (`#9DCBFC`) on `surfaceDark` (`#101418`) — light blue on near-black, matching Figma. Neither combination produces dark-on-dark or light-on-light invisibility (verified via the colour values in `Color.kt`).
- **Visual fidelity verification (AC1 + AC4).** The dark preview is the canonical comparison surface against Figma node `9:2`. The developer should fetch a fresh screenshot via `mcp__plugin_figma_figma__get_screenshot(fileKey: "g2HIq2UyPhslEoHRokQmHG", nodeId: "9:2", maxDimension: 1024)` and side-by-side compare the rendered preview output. Acceptance: six distinct arms radiate from canvas centre at 60° intervals, each reaching ~10 % from the canvas edge; each arm shows a Y-spike pattern at its tip (two pairs of branches in the outer half); the centre cursor sits cleanly at canvas centre with no overlapping branch geometry. If the branch positions need fine-tuning for tighter pixel match, the developer may adjust the inner-branch x-coord (currently 47.424) and outer-branch x-coord (currently 58.864) within ±2 units while keeping the branch deltas (±7.28, ±5.2) intact.
- **No new test cases.** Vector-drawable path data has no meaningful unit-test surface in this codebase; the previews + Figma-comparison at code-review are the verification mechanism, as established by #57 and #149.

## Open questions

- **None substantive.** The four AC items map cleanly to a single-file pathData rewrite. The "switch Icon to Image" optionality the ticket body raises is resolved against in `## Design` → "Colour binding". The "differentiated stroke/fill treatment" framing is resolved in `## Context` (shape-primitive differentiation, not colour differentiation — Figma binds only `Schemes/Primary`).
- If, after the rewrite, the dark preview's spike geometry still reads visibly wrong compared to the Figma screenshot, the developer may pull the single-arm asset via `mcp__plugin_figma_figma__get_design_context(fileKey: "g2HIq2UyPhslEoHRokQmHG", nodeId: "9:4")` (returns one arm's SVG asset URL) and reconcile branch positions against the source SVG. This is a fallback; the contract in `## Design` should match well enough on first attempt.
