# Scanner screen

QR-pairing screen — visually a "premium developer-tool" scanning moment (Figma node `13:2`, locked since #60). **Stateful since #326**: a `ScannerViewModel` + sealed `ScannerUiState` drive a runtime camera-permission flow. **Live since #334**: the granted/`ReadyToScan` state now renders a real CameraX preview behind the locked overlay, and **a QR scan — not a tap — drives pairing**. Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination. The **decode core** landed in #333 (an ML Kit [QR code analyzer](qr-code-analyzer.md) + a `QrDecoded → Decoded(payload)` transition, the success counterpart to #326's `CameraError → Error`); #334 instantiates that analyzer in production, binds it to the live camera via the new [Camera preview](camera-preview.md) composable, and rewires the pairing trigger from tap to decode. **Real pairing since #320**: the `Decoded` binding parses + validates the scanned payload into a real `PairedServer` (the [Pairing payload parser](pairing-payload-parser.md)), replacing the throwaway stub on this path. **Gated since #343**: on a successful parse it no longer persists — it derives the server's [static-key fingerprint](static-key-fingerprint.md) and parks in a new `AwaitingConfirm` state; the full-screen `PairingConfirmContent` shows the fingerprint and requires an explicit confirm before anything saves (the [Pairing confirm gate](pairing-confirm-gate.md), the QR-TOFU MITM checkpoint). Decline / system Back persist nothing and re-arm the scanner.

## What it does

- Requests the `CAMERA` runtime permission **on entry** (#326). Granted → the locked viewport with a live camera; denied → the existing [Scanner Denied screen](scanner-denied-screen.md) (#61), rendered in-route (no new route). On camera-bind failure the `ScannerErrorContent` recovery surface renders the `Error` state — whose live producer is now the [Camera preview](camera-preview.md) (#334).
- In the granted (`ReadyToScan`) state, renders a 412×892 dark-surface pairing screen with a M3 top app bar (`"Pair with pyrycode"` + back-arrow), a full-height rounded camera-viewport panel showing the **live back-camera feed**, and a `"Trouble scanning? Paste the pairing code instead"` `TextButton` below.
- The viewport stacks (back-to-front): the **live CameraX preview** (`ReadyToScan` only; the [Camera preview](camera-preview.md) composable injected through a slot), then dual radial gradients (cool-blue at 30% w / 40% h, soft-coral at 70% w / 70% h) painted in a `matchParentSize` overlay child, a 1-px-every-7-dp horizontal atmospheric stripe overlay drawn in a single `Canvas`, a 248dp four-corner reticle with a glowing horizontal scan line through its middle, and a translucent hint card pinned to the viewport's bottom that reads `Run pyry pair on your pyrycode server to generate a QR code.` (the `pyry pair` token in `FontFamily.Monospace` + `colorScheme.tertiary` coral). The `Box`'s `surfaceContainerLowest` background is now a fallback fill behind the camera. **The atmosphere/reticle/hint overlay is pixel-identical to the locked #60/#121 design — #334 changed no overlay pixels, only what fills the dark region behind it.**
- A **decoded QR** (not a tap) drives pairing: the analyzer's once-per-scan callback → `QrDecoded` → `Decoded`, which the route reacts to (`LaunchedEffect(state)`) by running `parsePairingPayload(payload)` (#320 — the [Pairing payload parser](pairing-payload-parser.md)). **Since #343, on `Success` it does not persist** — it derives the fingerprint (`serverKeyFingerprint`, #342) and fires `ScannerEvent.PairingPrepared(fp, server)` → `AwaitingConfirm`, which renders the confirm surface. The **only** scan-path `PairedServerStore.save(...)` runs behind the Confirm button (`confirmPairAndNavigate`), then navigates to `channel_list` with the scanner popped from the back stack. A parse failure (or the structurally-unreachable derive-`null`) fires `PairingFailed(msg)` → `Error`; a `PairedServerStoreException` on the confirm save likewise → `Error`. Decline / system Back → `DeclinePairing` → `ReadyToScan` (nothing persisted). **The decoded payload IS read** (the stub write moved off this path — `STUB_PAIRED_SERVER` now only backs the out-of-scope paste fallback).
- The back-arrow `IconButton` now **pops** (`popBackStack` → Welcome); the "Trouble scanning?" `TextButton` runs the **stub-pair** as a manual paste fallback (a real paste-input flow is a separate ticket; only the scanned `Decoded` path parses). The whole-surface tap-to-pair from #326 is **removed** (AC3) — pairing requires a real QR in frame.

## How it works

`ScannerScreen` is now a **stateless `when(state)` renderer** over `ScannerUiState` (#326) — it holds no state itself; the route owns the `ScannerViewModel` and the Android permission API and feeds resolved state in. The composable stays keyed purely on `ScannerUiState` so the Compose tests can drive each state directly without touching real runtime permissions.

```kotlin
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onNavigateBack: () -> Unit,                 // TopAppBar back arrow → pop to Welcome
    onOpenSettings: () -> Unit,                 // denied screen → app settings
    onPasteCode: () -> Unit,                    // "Trouble scanning?" + denied/error paste → stub-pair
    modifier: Modifier = Modifier,
    onConfirmPairing: () -> Unit = {},          // #343 AwaitingConfirm → confirmPairAndNavigate(state.server)
    onDeclinePairing: () -> Unit = {},          // #343 Decline button → DeclinePairing → ReadyToScan
    cameraPreview: @Composable () -> Unit = {}, // live camera (route injects for ReadyToScan; tests default {})
)
```

All defaulted params stay **after** `modifier` — Compose lint (`ComposableParameterOrder`/`abortOnError`) requires `modifier` to precede a trailing `@Composable () -> Unit` slot **and** the default-valued callbacks. The #343 spec asked for the two confirm callbacks "after `onPasteCode`, before `modifier`", but the lint forces them after `modifier`; all callers use named args, so the order is safe (the same lint family bit the `cameraPreview` slot in #334 — see [`codebase/334.md`](../codebase/334.md) and [`codebase/343.md`](../codebase/343.md)).

Dispatch (`ScannerScreen.kt:61`):

- `PermissionRequesting`, `ReadyToScan`, `is Decoded` → `private fun ScannerViewport(onNavigateBack, onPasteCode, cameraPreview, modifier)` — the locked viewport body (extracted verbatim in #326). The `cameraPreview` slot is the only per-state divergence: the route injects a live [Camera preview](camera-preview.md) **only for `ReadyToScan`** (an empty slot for `PermissionRequesting`, the shell behind the system permission dialog, and `Decoded`, the brief hand-off before navigation). `Decoded` still renders **no new visible surface** — the decoded payload is never displayed (scope guard); it exists to trigger the route's `LaunchedEffect(state)` parse-and-persist (#320; stub-pair before that).
- `Denied` → `ScannerDeniedScreen(onOpenSettings, onPasteCode, modifier)` — the existing #61 screen, reused as-is.
- `Error(message)` → `private fun ScannerErrorContent(message, onPasteCode, modifier)` — a minimal centered `Surface`: the message in `onSurfaceVariant` (`bodyLarge`, centered) over a "Paste the pairing code instead" `TextButton` so onboarding stays completable. No Figma exists for this state; the camera-engine slice is its live producer.
- `AwaitingConfirm(fingerprint, server)` → `private fun PairingConfirmContent(fingerprint, onConfirm, onDecline, modifier)` (#343) — the **security checkpoint**: a peer full-screen `Surface` (mirrors `ScannerErrorContent`) rendering the `fingerprint` verbatim in `FontFamily.Monospace` inside a `SelectionContainer` (selectable/copyable) with a `contentDescription`, compare copy ("…matches the `Static-key fp:` line that `pyry pair` shows on your other device…"), and Confirm (`Button`) / Decline (`OutlinedButton`) each `fillMaxWidth().heightIn(min = 48.dp)`. **Receives only the `fingerprint` string + two callbacks — never the token-bearing `server`.** Design-later, deliberately non-decorative (no Figma yet). See [Pairing confirm gate](pairing-confirm-gate.md).

### State model — `ScannerViewModel`

A pure synchronous state machine: a single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` exposed via `asStateFlow()`, with `fun onEvent(ScannerEvent)` mapping `PermissionGranted → ReadyToScan`, `PermissionDenied → Denied`, `CameraError(m) → Error(m)`, (since #333) `QrDecoded(p) → Decoded(p)`, (since #320) `PairingFailed(m) → Error(m)` (a parse/persist failure on the `Decoded` path; mirrors `CameraError`, carries a UI-owned constant, not a payload byte), and (since #343) `PairingPrepared(fp, server) → AwaitingConfirm(fp, server)` + `DeclinePairing → ReadyToScan` (the [confirm gate](pairing-confirm-gate.md) transitions). The `AwaitingConfirm` state carries the parsed `server` (so confirm saves exactly that record — no re-derive) and its derived `fingerprint` (so the surface renders without re-deriving); neither it nor `PairingPrepared` needs a custom `toString` — the only secret is `server.token`, already redacted by `PairedServer.toString()`, and the fingerprint is a public-key digest. **Confirm is deliberately not an event** — the `suspend save` + navigate is a route-scope callback (mirrors `onPasteCode`), keeping the VM Android-free. No `viewModelScope`, no flows beyond the single holder, no Android types — that Android-freeness is what makes the transitions unit-testable as plain JUnit. `Decoded`/`QrDecoded` carry the **raw untrusted** QR string as a bare `String` and override `toString()` to redact it (`"<redacted N chars>"`) — a deterministic no-log net (AC5 of #333) that doesn't touch the data-class `equals`/`hashCode`. The analyzer that produces `QrDecoded` is the [QR code analyzer](qr-code-analyzer.md), fed in via `onEvent` exactly like the permission callback (its live producer arrives with #334). Mirrors the `ChannelListViewModel.pendingWorkspacePicker` `MutableStateFlow` + `asStateFlow()` idiom. The only async edge — the runtime permission callback — lives in the composable (`MainActivity`) and feeds the VM via `onEvent`. The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation. See [`codebase/326.md`](../codebase/326.md) for the full state/event table.

### `ScannerViewport` — the locked viewport body

Composition shape: outer `Surface(color = colorScheme.surface, modifier = modifier.fillMaxSize())` over an inner `Column(systemBarsPadding)` containing the `TopAppBar` (back arrow → `onNavigateBack`), a `weight(1f)` viewport `Box`, and the `"Trouble scanning?"` `TextButton` (→ `onPasteCode`). The whole-surface `pointerInput { detectTapGestures { onTap() } }` from #326 was **removed in #334** (AC3 — tap-to-pair is replaced by decode-drives-pairing). All colors come from `MaterialTheme.colorScheme.*`, all type from `MaterialTheme.typography.*`. `Color.Transparent` is the only non-token `Color` used (top-app-bar container + radial-gradient terminal stops). The `modifier: Modifier = Modifier` parameter satisfies compose-lints' `ComposeModifierMissing` rule (added in #84).

The viewport `Box` is the visual centrepiece. Its child stack since #334 is, back-to-front: **`cameraPreview()`** (the injected live feed — see [Camera preview](camera-preview.md)), the radial-gradient overlay child, the stripe `Canvas`, the `Reticle`, and the `HintCard`.

- **Background**: `colorScheme.surfaceContainerLowest` (darker than `surface`, M3 dark-scheme convention) clipped to `RoundedCornerShape(24.dp)` — now a **fallback fill** behind the camera feed (visible only before/without a bound camera). The 24dp clip also bounds the `TextureView` preview (only because the preview is `COMPATIBLE`/in-hierarchy; see [Camera preview](camera-preview.md)).
- **Radial gradients**: two `drawRect(brush = Brush.radialGradient(...))` calls in a single `Modifier.drawBehind`, **moved in #334 from the `Box`'s own modifier into a `Box(Modifier.matchParentSize().drawBehind { … })` child**. The relocation is the AC1 lever: the `Box`'s own `drawBehind` paints behind *all* children including the camera, so it would hide the feed; a `matchParentSize` sibling placed *after* `cameraPreview()` layers the gradients **over** it. The draw is byte-for-byte identical (same stops/centres/`radius`). Centers and radius are derived from the lambda's `size` (`size.width * 0.30f`, `size.height * 0.70f`, `radius = maxOf(size.width, size.height) * 0.7f`), so the gradients adapt to any viewport dimension. Each gradient is a 3-stop: token-derived inner stop (`primary.copy(alpha = 0.12f)` / `tertiary.copy(alpha = 0.06f)`) → same color at `alpha = 0f` at offset 0.6 → `Color.Transparent` at offset 1.
- **Atmospheric stripes**: a single `Canvas(Modifier.matchParentSize())` runs a `while (y <= size.height) { drawRect(...); y += 7.dp.toPx() }` loop with stripe color hoisted to a `val` at the call site (`colorScheme.onSurface.copy(alpha = 0.04f)` — `MaterialTheme.colorScheme` is not addressable from the `DrawScope` receiver). Stripe count self-terminates against the measured height; the Figma "exactly 105 stripes" figure is a function of the 736dp panel height in the locked design.
- **Reticle (`Reticle`)**: private `Box(size = 248.dp)` parents four `Corner(...)` composables aligned to the four corners plus a single horizontally-padded scan-line `Canvas` (since #121 — replaces the prior paired glow-`Box` + crisp-line-`Box` shape). The Canvas is centred (`Modifier.align(Alignment.Center).padding(horizontal = 8.dp).fillMaxWidth().height(26.dp)`) and draws two layers into one surface: a shadow rect through a framework `Paint` carrying `BlurMaskFilter(12.dp.toPx(), BlurMaskFilter.Blur.NORMAL)` (the CSS-`box-shadow: 0 0 12px 0` analog Compose has no built-in primitive for) via `drawIntoCanvas { it.nativeCanvas.drawRect(...) }`, then the crisp 2dp `primary` line on top via `DrawScope.drawRect`. The 26dp Canvas height = 12dp shadow falloff + 2dp line + 12dp shadow falloff — exactly the envelope `BlurMaskFilter` extends past the source rect. Theme-derived colors (`primary`, `primary.copy(alpha = 0.6f).toArgb()` for the shadow ARGB int) are hoisted to `val`s at the composable's top scope so the `DrawScope` lambda can close over them.
- **`Corner(alignment, color, modifier = Modifier)`**: 28dp-square box with two `Modifier.align(alignment)` rectangles — a `28×4.dp` horizontal stub and a `4×28.dp` vertical stub, each `RoundedCornerShape(2.dp)`, both painted `colorScheme.primary`. The `alignment` parameter anchors both stubs to the same corner of the 28dp box; positioning of the corner inside the 248dp reticle is via the outer `Modifier.align(...)` passed in.
- **Hint card (`HintCard`)**: `Box(align(BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colorScheme.scrim.copy(alpha = 0.72f)).padding(horizontal = 16.dp, vertical = 12.dp))` wrapping a single `Text` whose content is `buildAnnotatedString { append("Run "); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colorScheme.tertiary)) { append("pyry pair") }; append(" on your pyrycode server to generate a QR code.") }`. Outer text uses `typography.bodyMedium` + `onSurface.copy(alpha = 0.92f)`. Non-interactive — taps bubble up to the outer `Surface`'s gesture detector.

Recomposition seam: trivial. The whole screen recomposes when the M3 theme flips light/dark; nothing else mutates. The radial brushes, the `AnnotatedString`, the stripe color, the corner composables, and the scan-line `Canvas`'s `BlurMaskFilter`-backed `Paint` are all reallocated on every recomposition — all cheap, all intentional (no `remember` blocks). The scan-line shadow uses `android.graphics.BlurMaskFilter`, which renders correctly with hardware acceleration on API 28+; min SDK 33 is comfortably inside the supported envelope (#121 swapped from `Modifier.blur` to `BlurMaskFilter` for a true CSS-equivalent drop shadow, not just a coincidental SDK-floor improvement).

Three deliberate design points worth knowing:

- **The camera is injected through a slot, not built into the screen.** `cameraPreview: @Composable () -> Unit = {}` keeps `ScannerScreen` a pure stateless `when(state)` renderer the Compose tests can drive with **no physical camera** (the default `{}`); the route owns the real CameraX composable and **gates it on `ReadyToScan`**. This is what lets the locked overlay stay instrumented-tested while the device-bound feed lives entirely in the route. See [Camera preview](camera-preview.md).
- **Per-affordance callbacks replaced the single `onTap` in #334.** The whole-surface `detectTapGestures` is gone; the back `IconButton` → `onNavigateBack` (`popBackStack` → Welcome) and the "Trouble scanning?" `TextButton` → `onPasteCode` (the stub-pair manual fallback). The `// Phase 1.5: every interactive element fires onTap` contradiction comment is removed. Pairing is now triggered only by a decode (`Decoded` → route `LaunchedEffect`), not by any tap.
- **`systemBarsPadding()` stays on the inner `Column`, not the `Surface`.** `enableEdgeToEdge()` makes content draw under system bars; the `Surface` fills edge-to-edge so the camera feed and atmosphere reach the screen edges, while the `Column`'s inset keeps the top app bar and hint card clear of the bars.

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

    // #343: the ONLY scan-path persist — runs solely behind the Confirm button, after the user has
    // compared the fingerprint. Mirrors stubPairAndNavigate's try/save/navigate/catch shape; reuses
    // the lifecycle-scoped `scope`. A store failure routes to Error so nothing half-persists.
    val confirmPairAndNavigate: (PairedServer) -> Unit = { server ->
        scope.launch {
            try {
                pairedServerStore.save(server)
                navController.navigate(Routes.CHANNEL_LIST) {
                    popUpTo(Routes.SCANNER) { inclusive = true }
                    launchSingleTop = true
                }
            } catch (e: PairedServerStoreException) {
                Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
                vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
            }
        }
    }

    // #343 (rewired from #320): a successful decode parses into a real PairedServer and derives its
    // static-key fingerprint, then parks in AwaitingConfirm — it does NOT persist. A parse failure, or
    // a derive that returns null (structurally unreachable for a Success, but handled so
    // staticKeyFingerprint's require can't throw into this coroutine), routes to Error. On the
    // AwaitingConfirm transition the state is no longer Decoded, so this effect re-runs as a no-op.
    LaunchedEffect(state) {
        val decoded = state as? ScannerUiState.Decoded ?: return@LaunchedEffect
        when (val result = parsePairingPayload(decoded.payload)) {
            is PairingParseResult.Success -> {
                val fingerprint = serverKeyFingerprint(result.server.serverStaticPublicKey)
                if (fingerprint == null) {
                    Log.w(TAG, "fingerprint derive failed: bad-stored-key")
                    vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
                } else {
                    vm.onEvent(ScannerEvent.PairingPrepared(fingerprint, result.server))
                }
            }
            is PairingParseResult.Failure -> {
                Log.w(TAG, "pairing parse failed: ${result.reason}")
                vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
            }
        }
    }

    // While confirming, system Back behaves as Decline (return to scanner, persist nothing) rather than
    // popping the whole scanner route to Welcome. Disabled otherwise, so Back pops normally.
    BackHandler(enabled = state is ScannerUiState.AwaitingConfirm) {
        vm.onEvent(ScannerEvent.DeclinePairing)
    }

    ScannerScreen(
        state = state,
        onNavigateBack = { navController.popBackStack() },
        onOpenSettings = { /* ACTION_APPLICATION_DETAILS_SETTINGS intent */ },
        onPasteCode = stubPairAndNavigate,
        // Confirm reads the CURRENT collected state: if back/decline already moved it off
        // AwaitingConfirm, the cast is null and confirm is a no-op — a save cannot fire after the gate
        // closed. Persists exactly the parsed record the displayed fingerprint was derived from.
        onConfirmPairing = {
            (state as? ScannerUiState.AwaitingConfirm)?.let { confirmPairAndNavigate(it.server) }
        },
        onDeclinePairing = { vm.onEvent(ScannerEvent.DeclinePairing) },
        cameraPreview = {
            if (state is ScannerUiState.ReadyToScan) {
                CameraPreview(
                    onQrDecoded = { vm.onEvent(ScannerEvent.QrDecoded(it)) },
                    onCameraError = { vm.onEvent(ScannerEvent.CameraError(it)) },
                )
            }
        },
    )
}
```

Notes:

- **Route owns the VM + the permission API + the camera; the screen is stateless.** `koinViewModel<ScannerViewModel>()` + `collectAsStateWithLifecycle()`, consistent with every other destination. The runtime permission launcher, `checkSelfPermission`, and the live [Camera preview](camera-preview.md) all live here — the VM stays Android-free (and unit-testable), and the camera feeds it only through `onEvent(QrDecoded | CameraError)`. The #295-era "destination-block-scoped Koin + `rememberCoroutineScope`" stub pattern is retained *for the persist side-effect only*; the screen's state now comes from the VM.
- **The camera slot is gated on `ReadyToScan`.** Binding only in that state means `PermissionRequesting` (behind the system dialog) and `Decoded` (handing off) render the viewport with an empty slot — no camera bound when there shouldn't be one.
- **Decode drives pairing via `LaunchedEffect(state)`; the persist is gated (#343).** The terminal `Decoded` state runs `parsePairingPayload` (#320 — the [Pairing payload parser](pairing-payload-parser.md)) and, on `Success`, derives the fingerprint (`serverKeyFingerprint`) and fires `PairingPrepared → AwaitingConfirm` — **this effect never persists**. The save moved to `confirmPairAndNavigate`, the route-scope lambda wired to `onConfirmPairing` and gated by the Confirm button; `popUpTo(SCANNER){inclusive=true}` on the navigate removes the destination so nothing re-fires. A parse/derive failure routes through `ScannerEvent.PairingFailed → Error` (state flips off `Decoded`, so the keyed effect re-runs as a no-op). The orchestration stays in the composable — the VM remains a pure synchronous state machine, so the parse+derive sit at the call site and the suspend save stays a callback rather than splitting into the VM. See the [Pairing confirm gate](pairing-confirm-gate.md). (`stubPairAndNavigate` survives only for the out-of-scope `onPasteCode` fallback — still ungated.)
- **Back arrow pops to Welcome.** `onNavigateBack = { navController.popBackStack() }` — Welcome is the only entry to `SCANNER`, matching the `AboutScreen` back pattern. (#334 replaced #326's back-arrow-fires-`onTap` stub.)
- **`stubPairAndNavigate` is annotated `: () -> Unit`.** A `val` lambda whose last expression is `scope.launch { … }` infers `() -> Job` and won't satisfy a `() -> Unit` parameter — the explicit annotation coerces it. (Lesson from #326; see [`codebase/326.md`](../codebase/326.md).)
- **`scope.launch { save(record); navigate(...) }` is sequential.** Awaiting the DataStore write before navigating matters because the start-destination gate reads it (`PairedServerStore.load()`, #295) — fire-and-forget would race the next composition. The `PairedServerStoreException` catch (added in #295) is preserved; the failure stays a `Log.w` + stay-put, **not** routed through the VM `Error` state.
- **`popUpTo(Routes.SCANNER) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.SCANNER)` is a no-op since Scanner is the top). `launchSingleTop` guards against double-tap stacking duplicate ChannelList entries during the in-flight coroutine.
- **`requested` is `rememberSaveable`** to guard against re-prompting the runtime permission after a config change while `Denied` (the VM, which survives rotation, already retains the resolved state).

## State + concurrency

- **State holder.** A single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` on `ScannerViewModel`, exposed via `asStateFlow()` and collected with `collectAsStateWithLifecycle()` in the route. No `viewModelScope`, no coroutines in the VM — the mapping is synchronous (`onEvent`). The only async edge is the Android permission callback, which lives in the composable and feeds the VM via `onEvent`.
- **Persist scope.** `rememberCoroutineScope()` inside the `composable(Routes.SCANNER)` block drives the `save()`/`navigate()` side-effects — the stub-pair (#295) **and**, since #343, the gated `confirmPairAndNavigate`. Cancels if the destination leaves the back stack mid-write — acceptable for a millisecond-scale DataStore write. Dispatcher is `Dispatchers.Main.immediate`; `DataStore.edit` hops to IO internally then resumes on Main for `navigate(...)`.
- **Rotation.** The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation; the `rememberSaveable` `requested` flag prevents a re-prompt while `Denied`. (One process-death gap — see Edge cases.)

## Error handling

- **Permission denied** (incl. "don't ask again") → `onEvent(PermissionDenied)` → `Denied` → the #61 screen. "Open settings" handles permanent denial; "Paste code" completes onboarding. #326 deliberately does **not** distinguish transient vs permanent denial (would need `shouldShowRequestPermissionRationale` + the Activity — "Open settings" covers both).
- **Parse / validation failure on a scanned payload** (#320: bad outer base64url, non-JSON, trailing data, missing/empty field, malformed relay, wrong-length/non-base64 pubkey) → `parsePairingPayload` returns `Failure` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error` → `ScannerErrorContent`. **Nothing is persisted** on any reject path; the user-facing copy is a fixed generic string, never a field value (see [Pairing payload parser](pairing-payload-parser.md) § no-leak).
- **Fingerprint derive failure** (#343: stored key non-base64-std / ≠ 32 bytes — structurally unreachable for a `Success`, the parser already proved base64-std-of-32-bytes) → `serverKeyFingerprint` returns `null` → fixed-reason `Log.w("fingerprint derive failed: bad-stored-key")` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error`. Handled so `staticKeyFingerprint`'s `require` can't throw into the `LaunchedEffect` coroutine; **nothing persisted**.
- **Confirm-path persist failure** (`PairedServerStoreException`, e.g. Keystore/IO, on the gated confirm save) → narrow `catch` in `confirmPairAndNavigate` → `Log.w` (`javaClass.simpleName` only) → `onEvent(PairingFailed(SAVE_FAILED_MSG))` → `Error` (since #343 this is the **only** scan-path save; before #343 the save sat in the `Decoded` effect). Distinct from the **paste-fallback stub persist** failure, which keeps the #295 contract: narrow `catch` → `Log.w` + stay-put, **not** routed through `Error`.
- **Decline / system Back while confirming** (#343) → `onEvent(DeclinePairing)` → `ReadyToScan` → re-mounts `CameraPreview`, ready to re-scan. **Nothing persisted.** The `BackHandler` is gated on `AwaitingConfirm`, so Back acts as Decline only while the gate is open.
- **Camera bind failure** (no camera, in-use, provider init throws) → [`CameraPreview`](camera-preview.md)'s bind `try/catch` → `onCameraError(CAMERA_BIND_ERROR_MESSAGE)` → `onEvent(CameraError(...))` → `Error` → `ScannerErrorContent` (message + "Paste the pairing code instead"), **not** a blank viewport (AC4). The message is a fixed const — the exception is never interpolated (no detail leak). Live since #334.
- **No QR in frame / decode fails** → the analyzer's `FrameDecoder` returns `null` → silent no-op, next frame awaited (logs nothing). A successful decode → `QrDecoded` → `Decoded` → the route's parse-and-derive `LaunchedEffect` (#320/#343 — parks in `AwaitingConfirm`, no longer persists here).
- **Unknown routes** can't happen at runtime — `Routes.CHANNEL_LIST` is registered in the same `NavHost` in the same file.

## Edge cases / limitations

- **Back arrow pops to Welcome (since #334).** The `TopAppBar` back `IconButton` is now wired to `onNavigateBack` (`navController.popBackStack()`), and the `"Trouble scanning?"` `TextButton` to `onPasteCode` (stub-pair) — the #326 "every affordance fires `onTap`" stub is resolved. Pairing is triggered only by a decode, never by a tap.
- **Process-death drops the resolved `Denied`/`ReadyToScan` state.** `requested` is `rememberSaveable` (survives process death) but the resolved state lives only in the VM (does **not**). After process death on the scanner with permission previously denied, `LaunchedEffect(Unit)` sees not-granted + `requested == true` → no branch fires → the viewport shell renders instead of `Denied`. Benign — the paste fallback still completes onboarding. The **on-resume permission re-check is still deferred** (a later pairing-polish ticket): grant-in-settings-then-return stays in `Denied`/shell until re-entering the scanner; #334's camera-lifecycle work did not change it. (Non-blocking NIT carried from #326.)
- **No Welcome-pop on success.** Only `Scanner` is popped, not `Welcome` — handled by the conditional start destination (`PairedServerStore.load()`, #295). A back-press from ChannelList returns to Welcome; tapping "I already have pyrycode" again re-routes through Scanner (the stub ignores already-paired state). Documented intermediate state, not a bug.
- **Static scan-line.** No `rememberInfiniteTransition` animation. Animation polish is deferred. A motionless line reads as "scan area indicator" well enough for the stub.
- **Radial gradients are circular, not elliptical.** Figma's SVG payload uses a `gradientTransform` matrix that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires a wrapping `Modifier.scale(...)` Box. The circular approximation reads identically as atmospheric haze and is what shipped — parity-of-intent, not pixel-identity of the SVG matrix.
- **Light-theme appearance is auto-derived.** No Figma light mockup exists for this screen. The dark scheme is the design target; the light scheme is derived from theme tokens and the preview verifies it composes. Stripe alpha (`onSurface.copy(alpha = 0.04f)`) reads washed-out on a light surface — acceptable; do not branch on `isSystemInDarkTheme()`.
- **`CameraPreview` is not unit-tested** (since #334) — it needs a physical camera / `ProcessCameraProvider`; mocking the bind chain has negative value. The route glue (slot injection, `Decoded` → `parsePairingPayload` → `serverKeyFingerprint` → `AwaitingConfirm` → gated save/navigate, the `BackHandler`, the permission launcher) is thin Android plumbing, not separately tested — the parser/derive are covered by `PairingPayloadParserTest`, the VM transitions by `ScannerViewModelTest`, and the confirm surface by `ScannerScreenTest` (see [Pairing confirm gate](pairing-confirm-gate.md) and [Pairing payload parser](pairing-payload-parser.md)). Live-path coverage is manual device smoke (point at a `pyry pair` QR → confirm screen → compare fp → Confirm → `channel_list`; Decline/Back → scanner re-armed, nothing persisted; deny/airplane the camera → `Error`). See [Camera preview](camera-preview.md).
- **`ScannerScreen.kt` is foundational, not disposable.** The `ScannerViewModel` state machine, the `when(state)` renderer, and `ScannerViewport` were **consumed** by #334 (the live preview injected through the `cameraPreview` slot, `CameraError` produced on bind failure), not replaced. `Routes.SCANNER` stays a single destination.
- **Instrumented test class — 13 methods since #343** (`app/src/androidTest/.../onboarding/ScannerScreenTest.kt`). All call sites migrated `onTap = {}` → `onNavigateBack = {}`. The three original tests (`topAppBar_rendersPairWithPyrycodeTitle` exact `"Pair with pyrycode"`, `hintCard_rendersPyryPairInstruction` substring `"pyry pair"`, `pasteCodeFallback_hasClickAction` substring `"Trouble scanning?"`) prove AC1/AC3 no-regression of the locked overlay (`cameraPreview` defaults to `{}`, so no device needed). Added across #326/#333/#334: `permissionRequesting_rendersViewportShell`, `denied_rendersScannerDeniedScreen` (`"Camera permission required"` → the #61 route), `error_rendersMessageAndClickablePasteFallback` (message + clickable "Paste the pairing code instead"), `decoded_rendersViewportUnchanged` (`Decoded("ignored-payload")` still renders the viewport — payload never displayed), and (#334) `cameraPreviewSlot_rendersBehindLockedOverlay` (injects a fake `testTag("camera")` slot and asserts **both** the slot node **and** the locked overlay render — pins AC1 slot wiring with no real camera). Added in #343 (the [confirm gate](pairing-confirm-gate.md), all driving `AwaitingConfirm(fingerprint, server)`): `awaitingConfirm_rendersFingerprintVerbatimAndCompareCopy`, `awaitingConfirm_fingerprint_exposesContentDescription` (`"Server fingerprint $FINGERPRINT"`), `awaitingConfirm_confirmButton_invokesOnConfirm`, `awaitingConfirm_declineButton_invokesOnDecline`, `awaitingConfirm_confirmAndDecline_meetMinimumTouchTargetHeight` (`assertHeightIsAtLeast(48.dp)`). VM transitions are covered separately by `test/.../ScannerViewModelTest.kt` (plain JUnit, 7 cases incl. `qrDecoded_movesToDecodedCarryingPayload` and #343's `pairingPrepared_…`/`declinePairing_…`); the decode→surface→debounce core has its own `test/.../QrCodeAnalyzerTest.kt` (see [QR code analyzer](qr-code-analyzer.md)). (Instrumented confirm-surface cases compile but were not run — no device; same posture as #320/#334.)

## Related

- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/12 (stub), https://github.com/pyrycode/pyrycode-mobile/issues/60 (Figma polish), https://github.com/pyrycode/pyrycode-mobile/issues/326 (stateful + permission flow), https://github.com/pyrycode/pyrycode-mobile/issues/333 (decode core), https://github.com/pyrycode/pyrycode-mobile/issues/334 (live CameraX preview), https://github.com/pyrycode/pyrycode-mobile/issues/320 (parse → real `PairedServer` + persist), https://github.com/pyrycode/pyrycode-mobile/issues/343 (fingerprint confirm gate)
- Specs: `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/60-scanner-screen-figma-polish.md`, `docs/specs/architecture/326-stateful-scanner-permission-flow.md`, `docs/specs/architecture/333-mlkit-qr-decode-pipeline.md`, `docs/specs/architecture/334-camerax-live-preview-scanner.md`, `docs/specs/architecture/320-qr-payload-parse-persist.md`, `docs/specs/architecture/343-pairing-fingerprint-confirm-gate.md`
- Ticket notes: `../codebase/12.md`, `../codebase/60.md`, `../codebase/326.md`, `../codebase/333.md`, `../codebase/334.md`, `../codebase/320.md`, `../codebase/343.md`
- Figma node: `13:2` (no confirm-pairing surface drawn — `AwaitingConfirm` is design-later)
- Upstream: #8 (NavHost), #295 (stub-pair persist + start-destination gate this screen preserves — the stub now backs only the paste fallback), #60/#121 (locked viewport visual reused verbatim), #61 (denied screen reused as the `Denied` state), #326 (stateful + permission), #333 (decode core), #320 (parse the `Decoded` payload → real `PairedServer`; the `PairingFailed → Error` route), #342 (`staticKeyFingerprint`, derived in the rewired `Decoded` effect), #343 (the confirm gate — `AwaitingConfirm` state, `PairingConfirmContent`, the gated persist)
- Downstream: the failed-pair → re-scan loop is **landed** in #343 (Decline / Back → `ReadyToScan`); the still-ungated paste path is the only remaining pairing-flow gap (a future real-paste ticket routes paste through the same confirm gate)
- Sibling docs: [Pairing confirm gate](pairing-confirm-gate.md) (the #343 security checkpoint hosted as this screen's `AwaitingConfirm` branch), [Pairing payload parser](pairing-payload-parser.md) (the #320 parse/validate/map boundary the `Decoded` binding runs + the #343 `serverKeyFingerprint`), [Static-key fingerprint](static-key-fingerprint.md) (the #342 derivation the gate displays), [Camera preview](camera-preview.md) (the #334 live-camera composable injected through this screen's slot), [QR code analyzer](qr-code-analyzer.md) (the #333 decode core feeding this screen's VM), [Scanner Denied screen](scanner-denied-screen.md), [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)
