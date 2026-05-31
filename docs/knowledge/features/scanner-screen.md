# Scanner screen

QR-pairing screen — visually a "premium developer-tool" scanning moment (Figma node `13:2`, locked since #60). **Stateful since #326**: a `ScannerViewModel` + sealed `ScannerUiState` drive a runtime camera-permission flow, but there is **still no live camera** — the granted/`ReadyToScan` state renders the locked viewport unchanged and any tap fake-pairs. Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination. The CameraX preview + ML Kit decode that fills `ReadyToScan` is the immediately-following sibling slice and consumes this state machine.

## What it does

- Requests the `CAMERA` runtime permission **on entry** (#326). Granted → the locked viewport; denied → the existing [Scanner Denied screen](scanner-denied-screen.md) (#61), rendered in-route (no new route). A `ScannerErrorContent` recovery surface exists for an `Error` state whose live producer arrives with the camera slice.
- In the granted (`ReadyToScan`) and `PermissionRequesting` states, renders a 412×892 dark-surface pairing screen with a M3 top app bar (`"Pair with pyrycode"` + back-arrow), a full-height rounded camera-viewport panel, and a `"Trouble scanning? Paste the pairing code instead"` `TextButton` below.
- The viewport stacks (back-to-front): a `surfaceContainerLowest` base, dual radial gradients (cool-blue at 30% w / 40% h, soft-coral at 70% w / 70% h) painted via a single `Modifier.drawBehind`, a 1-px-every-7-dp horizontal atmospheric stripe overlay drawn in a single `Canvas`, a 248dp four-corner reticle with a glowing horizontal scan line through its middle, and a translucent hint card pinned to the viewport's bottom that reads `Run pyry pair on your pyrycode server to generate a QR code.` (the `pyry pair` token in `FontFamily.Monospace` + `colorScheme.tertiary` coral).
- Tap **anywhere** on the viewport (or "Paste the pairing code instead") → persists a stub `PairedServer` via `PairedServerStore.save(...)` (#295) → navigates to `channel_list` with the scanner popped from the back stack. The visible back-arrow `IconButton` and `TextButton` both fire the same `onTap` (Phase 1.5 contradiction; see below).
- Permission is now requested, but there is still no camera preview and no ML Kit. In the granted state, any tap is success.

## How it works

`ScannerScreen` is now a **stateless `when(state)` renderer** over `ScannerUiState` (#326) — it holds no state itself; the route owns the `ScannerViewModel` and the Android permission API and feeds resolved state in. The composable stays keyed purely on `ScannerUiState` so the Compose tests can drive each state directly without touching real runtime permissions.

```kotlin
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onTap: () -> Unit,            // viewport tap + "paste" → stub-pair + navigate (#295)
    onOpenSettings: () -> Unit,   // denied screen → app settings
    onPasteCode: () -> Unit,      // denied/error "paste code" → stub-pair + navigate
    modifier: Modifier = Modifier,
)
```

Dispatch (`ScannerScreen.kt:61`):

- `PermissionRequesting`, `ReadyToScan` → `private fun ScannerViewport(onTap, modifier)` — the **existing** locked viewport body (extracted verbatim in #326; a pure refactor, no visual change). Both states render identically now; the camera slice diverges them (live preview in `ReadyToScan`, shell behind the system permission dialog in `PermissionRequesting`).
- `Denied` → `ScannerDeniedScreen(onOpenSettings, onPasteCode, modifier)` — the existing #61 screen, reused as-is.
- `Error(message)` → `private fun ScannerErrorContent(message, onPasteCode, modifier)` — a minimal centered `Surface`: the message in `onSurfaceVariant` (`bodyLarge`, centered) over a "Paste the pairing code instead" `TextButton` so onboarding stays completable. No Figma exists for this state; the camera-engine slice is its live producer.

### State model — `ScannerViewModel`

A pure synchronous state machine: a single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` exposed via `asStateFlow()`, with `fun onEvent(ScannerEvent)` mapping `PermissionGranted → ReadyToScan`, `PermissionDenied → Denied`, `CameraError(m) → Error(m)`. No `viewModelScope`, no flows beyond the single holder, no Android types — that Android-freeness is what makes the transitions unit-testable as plain JUnit. Mirrors the `ChannelListViewModel.pendingWorkspacePicker` `MutableStateFlow` + `asStateFlow()` idiom. The only async edge — the runtime permission callback — lives in the composable (`MainActivity`) and feeds the VM via `onEvent`. The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation. See [`codebase/326.md`](../codebase/326.md) for the full state/event table.

### `ScannerViewport` — the locked viewport body

Composition shape: outer `Surface(color = colorScheme.surface, modifier = modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } })` over an inner `Column(systemBarsPadding)` containing the `TopAppBar`, a `weight(1f)` viewport `Box`, and the `"Trouble scanning?"` `TextButton`. All colors come from `MaterialTheme.colorScheme.*`, all type from `MaterialTheme.typography.*`. `Color.Transparent` is the only non-token `Color` used (top-app-bar container + radial-gradient terminal stops). This whole body was `ScannerScreen`'s body before #326; the `modifier: Modifier = Modifier` parameter still satisfies compose-lints' `ComposeModifierMissing` rule (added in #84).

The viewport `Box` is the visual centrepiece:

- **Background**: `colorScheme.surfaceContainerLowest` (darker than `surface`, M3 dark-scheme convention) clipped to `RoundedCornerShape(24.dp)`.
- **Radial gradients**: both painted inside a single `Modifier.drawBehind` lambda — two `drawRect(brush = Brush.radialGradient(...))` calls back-to-back. Centers and radius are derived from the lambda's `size` (`size.width * 0.30f`, `size.height * 0.70f`, `radius = maxOf(size.width, size.height) * 0.7f`), so the gradients adapt to any viewport dimension. Each gradient is a 3-stop: token-derived inner stop (`primary.copy(alpha = 0.12f)` / `tertiary.copy(alpha = 0.06f)`) → same color at `alpha = 0f` at offset 0.6 → `Color.Transparent` at offset 1.
- **Atmospheric stripes**: a single `Canvas(Modifier.matchParentSize())` runs a `while (y <= size.height) { drawRect(...); y += 7.dp.toPx() }` loop with stripe color hoisted to a `val` at the call site (`colorScheme.onSurface.copy(alpha = 0.04f)` — `MaterialTheme.colorScheme` is not addressable from the `DrawScope` receiver). Stripe count self-terminates against the measured height; the Figma "exactly 105 stripes" figure is a function of the 736dp panel height in the locked design.
- **Reticle (`Reticle`)**: private `Box(size = 248.dp)` parents four `Corner(...)` composables aligned to the four corners plus a single horizontally-padded scan-line `Canvas` (since #121 — replaces the prior paired glow-`Box` + crisp-line-`Box` shape). The Canvas is centred (`Modifier.align(Alignment.Center).padding(horizontal = 8.dp).fillMaxWidth().height(26.dp)`) and draws two layers into one surface: a shadow rect through a framework `Paint` carrying `BlurMaskFilter(12.dp.toPx(), BlurMaskFilter.Blur.NORMAL)` (the CSS-`box-shadow: 0 0 12px 0` analog Compose has no built-in primitive for) via `drawIntoCanvas { it.nativeCanvas.drawRect(...) }`, then the crisp 2dp `primary` line on top via `DrawScope.drawRect`. The 26dp Canvas height = 12dp shadow falloff + 2dp line + 12dp shadow falloff — exactly the envelope `BlurMaskFilter` extends past the source rect. Theme-derived colors (`primary`, `primary.copy(alpha = 0.6f).toArgb()` for the shadow ARGB int) are hoisted to `val`s at the composable's top scope so the `DrawScope` lambda can close over them.
- **`Corner(alignment, color, modifier = Modifier)`**: 28dp-square box with two `Modifier.align(alignment)` rectangles — a `28×4.dp` horizontal stub and a `4×28.dp` vertical stub, each `RoundedCornerShape(2.dp)`, both painted `colorScheme.primary`. The `alignment` parameter anchors both stubs to the same corner of the 28dp box; positioning of the corner inside the 248dp reticle is via the outer `Modifier.align(...)` passed in.
- **Hint card (`HintCard`)**: `Box(align(BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colorScheme.scrim.copy(alpha = 0.72f)).padding(horizontal = 16.dp, vertical = 12.dp))` wrapping a single `Text` whose content is `buildAnnotatedString { append("Run "); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colorScheme.tertiary)) { append("pyry pair") }; append(" on your pyrycode server to generate a QR code.") }`. Outer text uses `typography.bodyMedium` + `onSurface.copy(alpha = 0.92f)`. Non-interactive — taps bubble up to the outer `Surface`'s gesture detector.

Recomposition seam: trivial. The whole screen recomposes when the M3 theme flips light/dark; nothing else mutates. The radial brushes, the `AnnotatedString`, the stripe color, the corner composables, and the scan-line `Canvas`'s `BlurMaskFilter`-backed `Paint` are all reallocated on every recomposition — all cheap, all intentional (no `remember` blocks). The scan-line shadow uses `android.graphics.BlurMaskFilter`, which renders correctly with hardware acceleration on API 28+; min SDK 33 is comfortably inside the supported envelope (#121 swapped from `Modifier.blur` to `BlurMaskFilter` for a true CSS-equivalent drop shadow, not just a coincidental SDK-floor improvement).

Three deliberate design points worth knowing:

- **`pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }` over `Modifier.clickable`.** `clickable` applies a Material ripple to the entire screen that fights the camera-viewport metaphor. `detectTapGestures` is the idiomatic shape for "raw tap, no decoration." Not the `clickable(indication = null, interactionSource = ...)` variant either — that requires two extra imports and reads like "I want clickable, minus what makes clickable useful."
- **Tap detection lives on the outer `Surface`, not the inner `Column`.** `enableEdgeToEdge()` makes content draw under system bars; if the gesture detector were on the inner column the inset region wouldn't catch taps. The same reasoning drives `systemBarsPadding()` being on the inner `Column` (visual inset) rather than the `Surface` (which fills edge-to-edge so the tap window is the full screen).
- **The back `IconButton` and `Trouble scanning?` `TextButton` are both wired to `onTap`.** AC6 of #60 mandates "tap anywhere fires `onTap`"; the Figma renders a back arrow and a paste-pairing-code text button as visible affordances. Both are wired to the same `onTap` callback. Phase 4 (real CameraX + a real back-stack + a real paste flow) rewires them; introducing an `onBack` / `onPasteCode` callback now would require touching `MainActivity.kt`'s wiring (banned by AC6). A `// Phase 1.5: every interactive element fires onTap` comment marks the contradiction in the source.

## Configuration / usage

Mounted at the `scanner` route in `PyryNavHost` (see `MainActivity.kt:139`). Since #326 the route owns the VM + Android permission API and feeds the stateless `ScannerScreen` its state:

```kotlin
composable(Routes.SCANNER) {
    val context = LocalContext.current
    val pairedServerStore = koinInject<PairedServerStore>()
    val scope = rememberCoroutineScope()
    val vm = koinViewModel<ScannerViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()

    // #295 stub-pair-and-navigate, factored unchanged into one lambda (note the `: () -> Unit`).
    val stubPairAndNavigate: () -> Unit = {
        scope.launch {
            try {
                pairedServerStore.save(STUB_PAIRED_SERVER)
                navController.navigate(Routes.CHANNEL_LIST) {
                    popUpTo(Routes.SCANNER) { inclusive = true }
                    launchSingleTop = true
                }
            } catch (e: PairedServerStoreException) {
                Log.w(TAG, "stub paired-server save failed: ${e.javaClass.simpleName}")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.onEvent(if (granted) ScannerEvent.PermissionGranted else ScannerEvent.PermissionDenied)
    }
    var requested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        when {
            alreadyGranted -> vm.onEvent(ScannerEvent.PermissionGranted)
            !requested -> { requested = true; permissionLauncher.launch(Manifest.permission.CAMERA) }
        }
    }

    ScannerScreen(
        state = state,
        onTap = stubPairAndNavigate,
        onOpenSettings = { /* ACTION_APPLICATION_DETAILS_SETTINGS intent */ },
        onPasteCode = stubPairAndNavigate,
    )
}
```

Notes:

- **Route owns the VM + the permission API; the screen is stateless.** `koinViewModel<ScannerViewModel>()` + `collectAsStateWithLifecycle()`, consistent with every other destination. The runtime permission launcher and `checkSelfPermission` live here — the VM stays Android-free (and unit-testable). The #295-era "destination-block-scoped Koin + `rememberCoroutineScope`" stub pattern is retained *for the persist side-effect only*; the screen's state now comes from the VM.
- **`stubPairAndNavigate` is annotated `: () -> Unit`.** A `val` lambda whose last expression is `scope.launch { … }` infers `() -> Job` and won't satisfy a `() -> Unit` parameter — the explicit annotation coerces it. (Lesson from #326; see [`codebase/326.md`](../codebase/326.md).)
- **`scope.launch { save(record); navigate(...) }` is sequential.** Awaiting the DataStore write before navigating matters because the start-destination gate reads it (`PairedServerStore.load()`, #295) — fire-and-forget would race the next composition. The `PairedServerStoreException` catch (added in #295) is preserved; the failure stays a `Log.w` + stay-put, **not** routed through the VM `Error` state.
- **`popUpTo(Routes.SCANNER) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.SCANNER)` is a no-op since Scanner is the top). `launchSingleTop` guards against double-tap stacking duplicate ChannelList entries during the in-flight coroutine.
- **`requested` is `rememberSaveable`** to guard against re-prompting the runtime permission after a config change while `Denied` (the VM, which survives rotation, already retains the resolved state).

## State + concurrency

- **State holder.** A single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` on `ScannerViewModel`, exposed via `asStateFlow()` and collected with `collectAsStateWithLifecycle()` in the route. No `viewModelScope`, no coroutines in the VM — the mapping is synchronous (`onEvent`). The only async edge is the Android permission callback, which lives in the composable and feeds the VM via `onEvent`.
- **Persist scope.** `rememberCoroutineScope()` inside the `composable(Routes.SCANNER)` block drives the stub-pair `save()`/`navigate()` side-effect only (unchanged from #295). Cancels if the destination leaves the back stack mid-write — acceptable for a millisecond-scale DataStore write. Dispatcher is `Dispatchers.Main.immediate`; `DataStore.edit` hops to IO internally then resumes on Main for `navigate(...)`.
- **Rotation.** The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation; the `rememberSaveable` `requested` flag prevents a re-prompt while `Denied`. (One process-death gap — see Edge cases.)

## Error handling

- **Permission denied** (incl. "don't ask again") → `onEvent(PermissionDenied)` → `Denied` → the #61 screen. "Open settings" handles permanent denial; "Paste code" completes onboarding. #326 deliberately does **not** distinguish transient vs permanent denial (would need `shouldShowRequestPermissionRationale` + the Activity — "Open settings" covers both).
- **Stub-pair persist failure** (`PairedServerStoreException`, e.g. Keystore/IO) → narrow `catch` → `Log.w` + stay on screen so the user can re-tap (the #295 contract). **Not** routed through the VM `Error` state — `PairedServer` handling is out of scope for #326.
- **Camera bind / decode failure** → the `Error` state exists and `ScannerErrorContent` renders it, but there is **no live producer this slice**; the camera-engine slice drives `onEvent(CameraError(...))`.
- **Unknown routes** can't happen at runtime — `Routes.CHANNEL_LIST` is registered in the same `NavHost` in the same file.

## Edge cases / limitations

- **Back arrow pairs-and-advances, not pops.** The `TopAppBar` back `IconButton` and the `"Trouble scanning?"` `TextButton` are both wired to `onTap` (→ stub-pair-and-navigate) — pre-existing Phase-0 behaviour ("every interactive element fires `onTap`"), preserved verbatim in #326 per AC4. Not correct nav wiring; the real back affordance lands when per-affordance lambdas split out.
- **Process-death drops the resolved `Denied`/`ReadyToScan` state.** `requested` is `rememberSaveable` (survives process death) but the resolved state lives only in the VM (does **not**). After process death on the scanner with permission previously denied, `LaunchedEffect(Unit)` sees not-granted + `requested == true` → no branch fires → the viewport shell renders instead of `Denied`. Benign — the viewport is tappable, so onboarding still completes — and the camera-engine slice (which owns CameraX lifecycle + on-resume re-check) is the natural home for the fix. (Non-blocking NIT from #326's code review.)
- **No Welcome-pop on success.** Only `Scanner` is popped, not `Welcome` — handled by the conditional start destination (`PairedServerStore.load()`, #295). A back-press from ChannelList returns to Welcome; tapping "I already have pyrycode" again re-routes through Scanner (the stub ignores already-paired state). Documented intermediate state, not a bug.
- **Static scan-line.** No `rememberInfiniteTransition` animation. Animation polish is deferred. A motionless line reads as "scan area indicator" well enough for the stub.
- **Radial gradients are circular, not elliptical.** Figma's SVG payload uses a `gradientTransform` matrix that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires a wrapping `Modifier.scale(...)` Box. The circular approximation reads identically as atmospheric haze and is what shipped — parity-of-intent, not pixel-identity of the SVG matrix.
- **Light-theme appearance is auto-derived.** No Figma light mockup exists for this screen. The dark scheme is the design target; the light scheme is derived from theme tokens and the preview verifies it composes. Stripe alpha (`onSurface.copy(alpha = 0.04f)`) reads washed-out on a light surface — acceptable; do not branch on `isSystemInDarkTheme()`.
- **The file is no longer fully disposable.** Pre-#326 this doc warned the entire `ScannerScreen.kt` file would be discarded by the camera slice. That changed: the `ScannerViewModel` state machine, the `when(state)` renderer, and `ScannerViewport` are the foundation the camera-engine slice **consumes**, not replaces — it fills `ReadyToScan` with a live CameraX preview and wires `onEvent(CameraError(...))`. `Routes.SCANNER` stays a single destination.
- **Instrumented test class — six methods since #326** (`app/src/androidTest/.../onboarding/ScannerScreenTest.kt`). The three original tests (`topAppBar_rendersPairWithPyrycodeTitle` exact `"Pair with pyrycode"`, `hintCard_rendersPyryPairInstruction` substring `"pyry pair"`, `pasteCodeFallback_hasClickAction` substring `"Trouble scanning?"`) now pass `state = ScannerUiState.ReadyToScan` — their unchanged assertions prove AC3 no-regression. Three new: `permissionRequesting_rendersViewportShell` (`"Pair with pyrycode"` present), `denied_rendersScannerDeniedScreen` (`"Camera permission required"` → the #61 route, Compose-tested), `error_rendersMessageAndClickablePasteFallback` (message + clickable "Paste the pairing code instead"). VM transitions are covered separately by `test/.../ScannerViewModelTest.kt` (plain JUnit, 4 cases). The back-arrow / paste wires-to-`onTap` contradiction is still unasserted.

## Related

- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/12 (stub), https://github.com/pyrycode/pyrycode-mobile/issues/60 (Figma polish), https://github.com/pyrycode/pyrycode-mobile/issues/326 (stateful + permission flow)
- Specs: `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/60-scanner-screen-figma-polish.md`, `docs/specs/architecture/326-stateful-scanner-permission-flow.md`
- Ticket notes: `../codebase/12.md`, `../codebase/60.md`, `../codebase/326.md`
- Figma node: `13:2`
- Upstream: #8 (NavHost), #295 (stub-pair persist + start-destination gate this screen preserves), #60/#121 (locked viewport visual reused verbatim), #61 (denied screen reused as the `Denied` state)
- Downstream: the camera-engine slice (CameraX preview + ML Kit decoder fills `ReadyToScan` and drives `onEvent(CameraError(...))`), #320/#321 (payload parse, real `PairedServer`, fingerprint, handshake)
- Sibling docs: [Scanner Denied screen](scanner-denied-screen.md), [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)
