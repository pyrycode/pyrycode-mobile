# Scanner screen

QR-pairing screen — visually a "premium developer-tool" scanning moment (Figma node `13:2`, locked since #60); functionally still a Phase 0 stub that fake-pairs on any tap. Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination.

## What it does

- Renders a 412×892 dark-surface pairing screen with a M3 top app bar (`"Pair with pyrycode"` + back-arrow), a full-height rounded camera-viewport panel, and a `"Trouble scanning? Paste the pairing code instead"` `TextButton` below.
- The viewport stacks (back-to-front): a `surfaceContainerLowest` base, dual radial gradients (cool-blue at 30% w / 40% h, soft-coral at 70% w / 70% h) painted via a single `Modifier.drawBehind`, a 1-px-every-7-dp horizontal atmospheric stripe overlay drawn in a single `Canvas`, a 248dp four-corner reticle with a glowing horizontal scan line through its middle, and a translucent hint card pinned to the viewport's bottom that reads `Run pyry pair on your pyrycode server to generate a QR code.` (the `pyry pair` token in `FontFamily.Monospace` + `colorScheme.tertiary` coral).
- Tap **anywhere** on the screen → flips `AppPreferences.setPairedServerExists(true)` → navigates to `channel_list` with the scanner popped from the back stack. The visible back-arrow `IconButton` and `TextButton` both fire the same `onTap` (Phase 1.5 contradiction; see below).
- No camera, no permissions, no ML Kit. Any tap is success.

## How it works

Pure stateless Composable. No `ViewModel`, no `UiState` / `Event` sealed types, no `Modifier` parameter — a fire-and-forget tap is the entire interaction model.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onTap: () -> Unit)
```

Composition shape: outer `Surface(color = colorScheme.surface, fillMaxSize, pointerInput { detectTapGestures { onTap() } })` over an inner `Column(systemBarsPadding)` containing the `TopAppBar`, a `weight(1f)` viewport `Box`, and the `"Trouble scanning?"` `TextButton`. All colors come from `MaterialTheme.colorScheme.*`, all type from `MaterialTheme.typography.*`. `Color.Transparent` is the only non-token `Color` used (top-app-bar container + radial-gradient terminal stops).

The viewport `Box` is the visual centrepiece:

- **Background**: `colorScheme.surfaceContainerLowest` (darker than `surface`, M3 dark-scheme convention) clipped to `RoundedCornerShape(24.dp)`.
- **Radial gradients**: both painted inside a single `Modifier.drawBehind` lambda — two `drawRect(brush = Brush.radialGradient(...))` calls back-to-back. Centers and radius are derived from the lambda's `size` (`size.width * 0.30f`, `size.height * 0.70f`, `radius = maxOf(size.width, size.height) * 0.7f`), so the gradients adapt to any viewport dimension. Each gradient is a 3-stop: token-derived inner stop (`primary.copy(alpha = 0.12f)` / `tertiary.copy(alpha = 0.06f)`) → same color at `alpha = 0f` at offset 0.6 → `Color.Transparent` at offset 1.
- **Atmospheric stripes**: a single `Canvas(Modifier.matchParentSize())` runs a `while (y <= size.height) { drawRect(...); y += 7.dp.toPx() }` loop with stripe color hoisted to a `val` at the call site (`colorScheme.onSurface.copy(alpha = 0.04f)` — `MaterialTheme.colorScheme` is not addressable from the `DrawScope` receiver). Stripe count self-terminates against the measured height; the Figma "exactly 105 stripes" figure is a function of the 736dp panel height in the locked design.
- **Reticle (`Reticle`)**: private `Box(size = 248.dp)` parents four `Corner(...)` composables aligned to the four corners and two horizontally-padded scan-line layers — a 6dp-tall `Box(blur(12.dp, BlurredEdgeTreatment.Unbounded), background = primary.copy(alpha = 0.6f))` glow under a 2dp-tall `Box(background = primary)` crisp line. Both centered via `Modifier.align(Alignment.Center)`.
- **`Corner(alignment, color)`**: 28dp-square box with two `Modifier.align(alignment)` rectangles — a `28×4.dp` horizontal stub and a `4×28.dp` vertical stub, each `RoundedCornerShape(2.dp)`, both painted `colorScheme.primary`. The `alignment` parameter anchors both stubs to the same corner of the 28dp box; positioning of the corner inside the 248dp reticle is via the outer `Modifier.align(...)` passed in.
- **Hint card (`HintCard`)**: `Box(align(BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colorScheme.scrim.copy(alpha = 0.72f)).padding(horizontal = 16.dp, vertical = 12.dp))` wrapping a single `Text` whose content is `buildAnnotatedString { append("Run "); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colorScheme.tertiary)) { append("pyry pair") }; append(" on your pyrycode server to generate a QR code.") }`. Outer text uses `typography.bodyMedium` + `onSurface.copy(alpha = 0.92f)`. Non-interactive — taps bubble up to the outer `Surface`'s gesture detector.

Recomposition seam: trivial. The whole screen recomposes when the M3 theme flips light/dark; nothing else mutates. The radial brushes, the `AnnotatedString`, the stripe color, and the corner composables are all reallocated on every recomposition — all cheap, all intentional (no `remember` blocks). The scan-line glow's `Modifier.blur(12.dp, BlurredEdgeTreatment.Unbounded)` requires API 31+; min SDK is 33, so production is safe — preview environments < API 31 render the line without halo.

Three deliberate design points worth knowing:

- **`pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }` over `Modifier.clickable`.** `clickable` applies a Material ripple to the entire screen that fights the camera-viewport metaphor. `detectTapGestures` is the idiomatic shape for "raw tap, no decoration." Not the `clickable(indication = null, interactionSource = ...)` variant either — that requires two extra imports and reads like "I want clickable, minus what makes clickable useful."
- **Tap detection lives on the outer `Surface`, not the inner `Column`.** `enableEdgeToEdge()` makes content draw under system bars; if the gesture detector were on the inner column the inset region wouldn't catch taps. The same reasoning drives `systemBarsPadding()` being on the inner `Column` (visual inset) rather than the `Surface` (which fills edge-to-edge so the tap window is the full screen).
- **The back `IconButton` and `Trouble scanning?` `TextButton` are both wired to `onTap`.** AC6 of #60 mandates "tap anywhere fires `onTap`"; the Figma renders a back arrow and a paste-pairing-code text button as visible affordances. Both are wired to the same `onTap` callback. Phase 4 (real CameraX + a real back-stack + a real paste flow) rewires them; introducing an `onBack` / `onPasteCode` callback now would require touching `MainActivity.kt`'s wiring (banned by AC6). A `// Phase 1.5: every interactive element fires onTap` comment marks the contradiction in the source.

## Configuration / usage

Mounted at the `scanner` route in `PyryNavHost` (see `MainActivity.kt`):

```kotlin
composable(Routes.SCANNER) {
    val appPreferences = koinInject<AppPreferences>()
    val scope = rememberCoroutineScope()
    ScannerScreen(
        onTap = {
            scope.launch {
                appPreferences.setPairedServerExists(true)
                navController.navigate(Routes.CHANNEL_LIST) {
                    popUpTo(Routes.SCANNER) { inclusive = true }
                    launchSingleTop = true
                }
            }
        },
    )
}
```

Notes:

- **`koinInject<AppPreferences>()` from `org.koin.compose`**, not a `ScannerViewModel`. Phase 4 will replace this whole `composable(...)` block wholesale; a ViewModel here is over-engineering for a stub that exists to be deleted. The pattern is "destination-block-scoped Koin + `rememberCoroutineScope`" for stub destinations that need a one-shot suspend side-effect.
- **`scope.launch { setPairedServerExists(true); navigate(...) }` is sequential.** Awaiting the DataStore write before navigating matters once #13 lands its `pairedServerExists` collector as the start-destination predicate — fire-and-forget would race the next composition.
- **`popUpTo(Routes.SCANNER) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.SCANNER)` is a no-op since Scanner is the top). `launchSingleTop` guards against double-tap stacking duplicate ChannelList entries during the in-flight coroutine.

## Why no ViewModel

Stub destinations with no observable state, no `UiState` to expose, and no lifecycle past a single `suspend` write don't need a ViewModel. The MVI conventions in `CLAUDE.md` apply to screens that *have* state to manage — they aren't a mandate to wrap every destination in scaffolding. Phase 4 (real CameraX preview, scanning state machine, error UI) will introduce a `ScannerViewModel` when there is actual state to manage.

## State + concurrency

- **Scope.** `rememberCoroutineScope()` inside the `composable(Routes.SCANNER)` block, tied to the destination's composition. Cancels if the destination leaves the back stack mid-write — acceptable for a millisecond-scale DataStore write.
- **Dispatcher.** `Dispatchers.Main.immediate` from `rememberCoroutineScope`; `DataStore.edit` internally hops to IO for the disk write, then resumes back on Main for `navController.navigate(...)`. No manual `withContext` needed.
- **Cancellation race.** If cancellation occurs after the write returns but before `navigate(...)` runs, the preference is set but no navigation happens — and cancellation only happens via the destination leaving the back stack (user already navigated away). Acceptable.

## Error handling

- **DataStore write failures** propagate as `IOException` to the (unregistered) `CoroutineExceptionHandler` → crash. For a Phase 0 stub on a fresh first-run install, a DataStore failure is a real bug that should be loud. No try/catch around the write; #13 / Phase 4 will revisit error UX once pairing has a real failure surface.
- **Unknown routes** can't happen at runtime — `Routes.CHANNEL_LIST` is registered in the same `NavHost` in the same file.

## Edge cases / limitations

- **Tap-handler keyed on `Unit`, not `onTap`.** The `onTap` callback is bound to a stable `scope.launch { ... }` closure for the destination's lifetime, so re-keying on every recomposition is unnecessary churn. If the surrounding NavHost block ever grows mutable state that the callback needs to read, re-key on `onTap` (or a more specific dependency).
- **No Welcome-pop on success.** Only `Scanner` is popped, not `Welcome` — that's #13's call (conditional start destination). Until #13 lands, a back-press from ChannelList returns to Welcome; tapping "I already have pyrycode" again re-routes through Scanner (the stub ignores already-paired state). Documented intermediate state, not a bug.
- **Static scan-line.** No `rememberInfiniteTransition` animation. Animation polish is explicitly Phase 4's job (see the ticket's "Out of scope"). A motionless line reads as "scan area indicator" well enough for the stub.
- **Radial gradients are circular, not elliptical.** Figma's SVG payload uses a `gradientTransform` matrix that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires a wrapping `Modifier.scale(...)` Box. The circular approximation reads identically as atmospheric haze and is what shipped — parity-of-intent, not pixel-identity of the SVG matrix.
- **Light-theme appearance is auto-derived.** No Figma light mockup exists for this screen. The dark scheme is Phase 1.5's target; the light scheme is derived from theme tokens and the preview verifies it composes. Stripe alpha (`onSurface.copy(alpha = 0.04f)`) reads washed-out on a light surface — acceptable; do not branch on `isSystemInDarkTheme()`.
- **Phase 4 walk-back.** The `Routes.SCANNER` route constant survives the Phase 4 rewrite. Everything inside `composable(Routes.SCANNER) { ... }` and the entire `ScannerScreen.kt` file do not. Don't add abstractions here anticipating the Phase 4 shape.
- **Three-method instrumented test class since #101.** `app/src/androidTest/.../onboarding/ScannerScreenTest.kt` covers `topAppBar_rendersPairWithPyrycodeTitle` (exact match on `"Pair with pyrycode"`), `hintCard_rendersPyryPairInstruction` (substring match on `"pyry pair"` — covers the `buildAnnotatedString` body without depending on the full sentence), and `pasteCodeFallback_hasClickAction` (substring `"Trouble scanning?"` carries a click action). Structure only — neither the back-`IconButton` nor the `TextButton` wires-to-`onTap` contradiction is exercised; click-callback coverage is deferred until the Phase 4 rewrite splits `onTap` into per-affordance lambdas.

## Related

- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/12 (stub), https://github.com/pyrycode/pyrycode-mobile/issues/60 (Figma polish)
- Specs: `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/60-scanner-screen-figma-polish.md`
- Ticket notes: `../codebase/12.md`, `../codebase/60.md`
- Figma node: `13:2`
- Upstream: #8 (NavHost), #11 (`AppPreferences.setPairedServerExists`)
- Downstream: #13 (conditional start destination based on `pairedServerExists`), Phase 4 (CameraX + ML Kit replaces this whole block)
- Sibling docs: [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)
