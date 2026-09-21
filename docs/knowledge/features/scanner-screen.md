# Scanner screen

QR-pairing screen with a live camera and a theme-based Pairing frame (Figma node `13:2`, updated in #640). **Stateful since #326**: a `ScannerViewModel` + sealed `ScannerUiState` drive a runtime camera-permission flow. **Live since #334**: the granted/`ReadyToScan` state now renders a real CameraX preview behind the locked overlay, and **a QR scan — not a tap — drives pairing**. Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination. The **decode core** landed in #333 (an ML Kit [QR code analyzer](qr-code-analyzer.md) + a `QrDecoded → Decoded(payload)` transition, the success counterpart to #326's `CameraError → Error`); #334 instantiates that analyzer in production, binds it to the live camera via the new [Camera preview](camera-preview.md) composable, and rewires the pairing trigger from tap to decode. **Real pairing since #320**: the `Decoded` binding parses + validates the scanned payload into a real `PairedServer` (the [Pairing payload parser](pairing-payload-parser.md)), replacing the throwaway stub on this path. **Gated since #343**: on a successful parse it no longer persists — it derives the server's [static-key fingerprint](static-key-fingerprint.md) and parks in a new `AwaitingConfirm` state; the full-screen `PairingConfirmContent` shows the fingerprint and requires an explicit confirm before anything saves (the [Pairing confirm gate](pairing-confirm-gate.md), the QR-TOFU MITM checkpoint). Decline / system Back persist nothing and re-arm the scanner.

## What it does

- Requests the `CAMERA` runtime permission **on entry** (#326). Granted → the locked viewport with a live camera; denied → the existing [Scanner Denied screen](scanner-denied-screen.md) (#61), rendered in-route (no new route). On camera-bind failure the `ScannerErrorContent` recovery surface renders the `Error` state — whose live producer is now the [Camera preview](camera-preview.md) (#334).
- In `ReadyToScan`, shows the theme-based **Pairing** header with a 48 dp Back target and inset divider above the rounded live-camera window. The reference layout is 412×892 dp; at 360×640 dp the guide moves or shrinks to keep the helper and actions visible. The `Trouble scanning? Paste the pairing code instead` action sits below the window with a minimum 48 dp height.
- The viewport keeps the **live CameraX preview first**, beneath the blue/coral atmosphere, horizontal stripes and measured reticle/helper guides. The reticle is a centered 248 dp square when space permits, with a static glowing scan line. The bottom helper reads `Run pyry pair on your pyrycode server to generate a QR code.`; its theme-surface background and `onSurface`/`tertiary` text remain readable in either theme. The `surfaceContainerLowest` fill is only the camera fallback. See the [viewport layout](#scannerviewport--the-locked-viewport-body) for compact placement.
- A **decoded QR** (not a tap) drives pairing: the analyzer's once-per-scan callback → `QrDecoded` → `Decoded`, which the route reacts to (`LaunchedEffect(state)`) by running `parsePairingPayload(payload)` (#320 — the [Pairing payload parser](pairing-payload-parser.md)). **Since #343, on `Success` it does not persist** — it derives the fingerprint (`serverKeyFingerprint`, #342) and fires `ScannerEvent.PairingPrepared(fp, server)` → `AwaitingConfirm`, which renders the confirm surface. The **only** scan-path `PairedServerStore.save(...)` runs behind the Confirm button (`confirmPairAndNavigate`), which — since #489 — delegates to the Android-free `confirmPairingAndConnect` (`save → connect → navigate`), so a **successful** persist also starts the relay supervision loop immediately (no background→foreground cycle) before navigating to `channel_list` with the scanner popped from the back stack. A parse failure (or the structurally-unreachable derive-`null`) fires `PairingFailed(msg)` → `Error`; a `PairedServerStoreException` on the confirm save likewise → `Error`. Decline / system Back → `DeclinePairing` → `ReadyToScan` (nothing persisted). **The decoded payload is read**; only the confirmed record is saved.
- The back arrow pops to the caller. The viewport, denied and error paste actions all navigate to the [pair-with-code screen](paste-code-dialog.md). Cancel or Android Back from that form's editing state returns to the invoking scanner without saving; scanner Back then returns to its caller. Manual entry owns its draft and confirmation flow separately from the scanner state machine.

## How it works

`ScannerScreen` is now a **stateless `when(state)` renderer** over `ScannerUiState` (#326) — it holds no state itself; the route owns the `ScannerViewModel` and the Android permission API and feeds resolved state in. The composable stays keyed purely on `ScannerUiState` so the Compose tests can drive each state directly without touching real runtime permissions.

```kotlin
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onNavigateBack: () -> Unit,                 // header back arrow → pop to caller
    onOpenSettings: () -> Unit,                 // denied screen → app settings
    onPasteCode: () -> Unit,                    // viewport + denied/error paste → pair_code route
    modifier: Modifier = Modifier,
    onConfirmPairing: () -> Unit = {},          // #343 AwaitingConfirm → confirmPairAndNavigate(state.server)
    onDeclinePairing: () -> Unit = {},          // #343 Decline button → DeclinePairing → ReadyToScan
    cameraPreview: @Composable () -> Unit = {}, // live camera (route injects for ReadyToScan; tests default {})
)
```

All defaulted params stay **after** `modifier` — Compose lint (`ComposableParameterOrder`/`abortOnError`) requires `modifier` to precede a trailing `@Composable () -> Unit` slot **and** the default-valued callbacks. The #343 spec asked for the two confirm callbacks "after `onPasteCode`, before `modifier`", but the lint forces them after `modifier`; all callers use named args, so the order is safe (the same lint family bit the `cameraPreview` slot in #334 — see [`codebase/334.md`](../codebase/334.md) and [`codebase/343.md`](../codebase/343.md)).

Dispatch (`ScannerScreen.kt:61`):

- `PermissionRequesting`, `ReadyToScan`, `is Decoded` → `private fun ScannerViewport(onNavigateBack, onPasteCode, cameraPreview, modifier)` — the viewport body, refreshed in #640. The `cameraPreview` slot is the only per-state divergence: the route injects a live [Camera preview](camera-preview.md) **only for `ReadyToScan`** (an empty slot for `PermissionRequesting`, the shell behind the system permission dialog, and `Decoded`, the brief hand-off before navigation). `Decoded` still renders **no new visible surface** — the decoded payload is never displayed (scope guard); it triggers the route's `LaunchedEffect(state)` parse-and-derive step before fingerprint confirmation; persistence waits for Confirm.
- `Denied` → `ScannerDeniedScreen(onOpenSettings, onPasteCode, modifier)` — the existing #61 screen, reused as-is.
- `Error(message)` → `private fun ScannerErrorContent(message, onPasteCode, modifier)` — a minimal centered `Surface`: the message in `onSurfaceVariant` (`bodyLarge`, centered) over a "Paste the pairing code instead" `TextButton` so onboarding stays completable. No Figma exists for this state; the camera-engine slice is its live producer.
- `AwaitingConfirm(fingerprint, server)` → `private fun PairingConfirmContent(fingerprint, onConfirm, onDecline, modifier)` (#343) — the **security checkpoint**: a peer full-screen `Surface` (mirrors `ScannerErrorContent`) rendering the `fingerprint` verbatim in `FontFamily.Monospace` inside a `SelectionContainer` (selectable/copyable) with a `contentDescription`, compare copy ("…matches the `Static-key fp:` line that `pyry pair` shows on your other device…"), and Confirm (`Button`) / Decline (`OutlinedButton`) each `fillMaxWidth().heightIn(min = 48.dp)`. **Receives only the `fingerprint` string + two callbacks — never the token-bearing `server`.** Design-later, deliberately non-decorative (no Figma yet). See [Pairing confirm gate](pairing-confirm-gate.md).

### State model — `ScannerViewModel`

A pure synchronous state machine: a single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` exposed via `asStateFlow()`, with `fun onEvent(ScannerEvent)` mapping `PermissionGranted → ReadyToScan`, `PermissionDenied → Denied`, `CameraError(m) → Error(m)`, (since #333) `QrDecoded(p) → Decoded(p)`, (since #320) `PairingFailed(m) → Error(m)` (a parse/persist failure on the `Decoded` path; mirrors `CameraError`, carries a UI-owned constant, not a payload byte), and (since #343) `PairingPrepared(fp, server) → AwaitingConfirm(fp, server)` + `DeclinePairing → ReadyToScan` (the [confirm gate](pairing-confirm-gate.md) transitions). The `AwaitingConfirm` state carries the parsed `server` (so confirm saves exactly that record — no re-derive) and its derived `fingerprint` (so the surface renders without re-deriving); neither it nor `PairingPrepared` needs a custom `toString` — the only secret is `server.token`, already redacted by `PairedServer.toString()`, and the fingerprint is a public-key digest. **Confirm is deliberately not an event** — the `suspend save` + navigate is a route-scope callback (mirrors `onPasteCode`), keeping the VM Android-free. No `viewModelScope`, no flows beyond the single holder, no Android types — that Android-freeness is what makes the transitions unit-testable as plain JUnit. `Decoded`/`QrDecoded` carry the **raw untrusted** QR string as a bare `String` and override `toString()` to redact it (`"<redacted N chars>"`) — a deterministic no-log net (AC5 of #333) that doesn't touch the data-class `equals`/`hashCode`. The analyzer that produces `QrDecoded` is the [QR code analyzer](qr-code-analyzer.md), fed in via `onEvent` exactly like the permission callback (its live producer arrives with #334). Mirrors the `ChannelListViewModel.pendingWorkspacePicker` `MutableStateFlow` + `asStateFlow()` idiom. The only async edge — the runtime permission callback — lives in the composable (`MainActivity`) and feeds the VM via `onEvent`. The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation. See [`codebase/326.md`](../codebase/326.md) for the full state/event table.

### `ScannerViewport` — the locked viewport body

The outer `Surface(color = colorScheme.surface)` fills the screen; an inner `Column`
applies `systemBarsPadding()`. Its header is a `Row` with the shared Material
ArrowBack glyph (`contentDescription = "Back"`) in an explicitly sized 48 dp
`IconButton`, followed by **Pairing** in `titleLarge` / `onPrimaryContainer`.
Header padding is 8 dp start, 20 dp end and 18 dp top. The `HorizontalDivider`
uses `inversePrimary` at 60% opacity, 20 dp side insets and 6 dp top spacing;
24 dp separates it from the camera window. The weighted window has 16 dp side
insets. Below it, a 16 dp padded container holds the centered paste `TextButton`
(`labelLarge` / `primary`, minimum 48 dp height). Native touch targets and system
insets consume space absent from the frameless design canvas.

The viewport has no tap-to-pair gesture. Back and Paste have separate callbacks;
a QR decode starts camera pairing. Colors and typography come from theme roles;
`Color.Transparent` is used only for radial-gradient terminal stops.

The viewport `Box` stacks `cameraPreview()` first, then the radial-gradient
sibling, stripe `Canvas` and `ScannerGuides`. The private `ScannerGuides` layout
measures the helper **including its 16 dp outer padding** before constraining the
reticle. The reticle remains square, at most 248 dp and no larger than the window
width or the height left above the padded helper. It stays centered when it fits,
otherwise moves upward and shrinks only when needed. The helper stays bottom
aligned; its top padding provides the 16 dp reticle-to-card gap. Measurement is
synchronous and adds no state, coroutine or camera lifecycle behavior.

- **Background**: `colorScheme.surfaceContainerLowest` (darker than `surface`, M3 dark-scheme convention) clipped to `RoundedCornerShape(24.dp)` — now a **fallback fill** behind the camera feed (visible only before/without a bound camera). The 24dp clip also bounds the `TextureView` preview (only because the preview is `COMPATIBLE`/in-hierarchy; see [Camera preview](camera-preview.md)).
- **Radial gradients**: two `drawRect(brush = Brush.radialGradient(...))` calls in a single `Modifier.drawBehind`, **moved in #334 from the `Box`'s own modifier into a `Box(Modifier.matchParentSize().drawBehind { … })` child**. The relocation is the AC1 lever: the `Box`'s own `drawBehind` paints behind *all* children including the camera, so the camera would hide the gradients; a `matchParentSize` sibling placed *after* `cameraPreview()` layers the gradients **over** it. The draw is byte-for-byte identical (same stops/centres/`radius`). Centers and radius are derived from the lambda's `size` (`size.width * 0.30f`, `size.height * 0.70f`, `radius = maxOf(size.width, size.height) * 0.7f`), so the gradients adapt to any viewport dimension. Each gradient is a 3-stop: token-derived inner stop (`primary.copy(alpha = 0.12f)` / `tertiary.copy(alpha = 0.06f)`) → same color at `alpha = 0f` at offset 0.6 → `Color.Transparent` at offset 1.
- **Atmospheric stripes**: a single `Canvas(Modifier.matchParentSize())` runs a `while (y <= size.height) { drawRect(...); y += 7.dp.toPx() }` loop with stripe color hoisted to a `val` at the call site (`colorScheme.onSurface.copy(alpha = 0.04f)` — `MaterialTheme.colorScheme` is not addressable from the `DrawScope` receiver). Stripe count self-terminates against the measured height; the Figma "exactly 105 stripes" figure is a function of the 736dp panel height in the locked design.
- **Reticle (`Reticle`)**: private `Box(size = 248.dp)` uses that preferred size within `ScannerGuides`' measured square constraints and parents four `Corner(...)` composables aligned to the four corners plus a single horizontally-padded scan-line `Canvas` (since #121 — replaces the prior paired glow-`Box` + crisp-line-`Box` shape). The Canvas is centred (`Modifier.align(Alignment.Center).padding(horizontal = 8.dp).fillMaxWidth().height(26.dp)`) and draws two layers into one surface: a shadow rect through a framework `Paint` carrying `BlurMaskFilter(12.dp.toPx(), BlurMaskFilter.Blur.NORMAL)` (the CSS-`box-shadow: 0 0 12px 0` analog Compose has no built-in primitive for) via `drawIntoCanvas { it.nativeCanvas.drawRect(...) }`, then the crisp 2dp `primary` line on top via `DrawScope.drawRect`. The 26dp Canvas height = 12dp shadow falloff + 2dp line + 12dp shadow falloff — exactly the envelope `BlurMaskFilter` extends past the source rect. Theme-derived colors (`primary`, `primary.copy(alpha = 0.6f).toArgb()` for the shadow ARGB int) are hoisted to `val`s at the composable's top scope so the `DrawScope` lambda can close over them.
- **`Corner(alignment, color, modifier = Modifier)`**: 28dp-square box with two `Modifier.align(alignment)` rectangles — a `28×4.dp` horizontal stub and a `4×28.dp` vertical stub, each `RoundedCornerShape(2.dp)`, both painted `colorScheme.primary`. The `alignment` parameter anchors both stubs to the same corner of the 28dp box; positioning of the corner inside the 248dp reticle is via the outer `Modifier.align(...)` passed in.
- **Hint card (`HintCard`)**: 16 dp outer padding, 12 dp rounded corners and
  `colorScheme.surface.copy(alpha = 0.94f)` behind the text. The nearly opaque
  theme surface replaces the dark scrim so light-mode text is readable over a
  camera feed. Inner padding is 16 dp horizontal / 12 dp vertical. The instruction
  uses `bodyMedium` and `onSurface` at 92% opacity; only `pyry pair` uses
  `FontFamily.Monospace` and `tertiary`. The card is non-interactive.

Recomposition seam: trivial. The whole screen recomposes when the M3 theme flips light/dark; nothing else mutates. The radial brushes, the `AnnotatedString`, the stripe color, the corner composables, and the scan-line `Canvas`'s `BlurMaskFilter`-backed `Paint` are all reallocated on every recomposition — all cheap, all intentional (no `remember` blocks). The scan-line shadow uses `android.graphics.BlurMaskFilter`, which renders correctly with hardware acceleration on API 28+; min SDK 33 is comfortably inside the supported envelope (#121 swapped from `Modifier.blur` to `BlurMaskFilter` for a true CSS-equivalent drop shadow, not just a coincidental SDK-floor improvement).

Three deliberate design points worth knowing:

- **The camera is injected through a slot, not built into the screen.** `cameraPreview: @Composable () -> Unit = {}` keeps `ScannerScreen` a pure stateless `when(state)` renderer the Compose tests can drive with **no physical camera** (the default `{}`); the route owns the real CameraX composable and **gates it on `ReadyToScan`**. This is what lets the locked overlay stay instrumented-tested while the device-bound feed lives entirely in the route. See [Camera preview](camera-preview.md).
- **Per-affordance callbacks replaced the single `onTap` in #334.** The whole-surface `detectTapGestures` is gone; the back `IconButton` → `onNavigateBack` (`popBackStack` → Welcome) and the "Trouble scanning?" `TextButton` → `onPasteCode` (the full-screen manual pairing route). The `// Phase 1.5: every interactive element fires onTap` contradiction comment is removed. Camera pairing starts with a decode (`Decoded` → route `LaunchedEffect`); manual pairing starts from the separate form.
- **`systemBarsPadding()` stays on the inner `Column`, not the `Surface`.** The
  background fills edge-to-edge while the header, inset camera window, helper
  and paste target stay clear of system bars.

## Configuration / usage

Mounted at the `scanner` route in `PyryNavHost` (see `MainActivity.kt:139`). Since #326 the route owns the VM + Android permission API and feeds the stateless `ScannerScreen` its state:

```kotlin
composable(Routes.SCANNER) {
    val context = LocalContext.current
    val pairedServerStore = koinInject<PairedServerStore>()
    val scope = rememberCoroutineScope()
    val vm = koinViewModel<ScannerViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()

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
    // compared the fingerprint; reuses the lifecycle-scoped `scope`. #489: delegates to the Android-free
    // confirmPairingAndConnect free function, which orders save → connect → navigate — so a SUCCESSFUL
    // persist also starts the relay loop immediately (no background→foreground cycle). A store failure
    // routes to Error so nothing half-persists, and connect() never fires on a failed persist.
    val confirmPairAndNavigate: (PairedServer) -> Unit = { server ->
        scope.launch {
            confirmPairingAndConnect(
                server = server,
                store = pairedServerStore,
                controller = connectionController,           // koinInject<RelayConnectionController>() (#489)
                onPersisted = {
                    navController.navigate(Routes.CHANNEL_LIST) {
                        popUpTo(Routes.SCANNER) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onFailed = { e ->
                    Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
                    vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
                },
            )
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
        onPasteCode = { navController.navigate(Routes.PAIR_CODE) },
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
- **Decode drives pairing via `LaunchedEffect(state)`; the persist is gated (#343).** The terminal `Decoded` state runs `parsePairingPayload` (#320 — the [Pairing payload parser](pairing-payload-parser.md)) and, on `Success`, derives the fingerprint (`serverKeyFingerprint`) and fires `PairingPrepared → AwaitingConfirm` — **this effect never persists**. The save moved to `confirmPairAndNavigate`, the route-scope lambda wired to `onConfirmPairing` and gated by the Confirm button; since #489 it delegates to the Android-free `confirmPairingAndConnect` free function (`save → connect → navigate`), which starts the relay loop on a **successful** persist — `connect()` runs **before** the navigate callback so the loop (on the supervisor's own scope) is launched even as the navigate pops the Scanner and cancels this composable's scope. `popUpTo(SCANNER){inclusive=true}` on the navigate removes the destination so nothing re-fires. A parse/derive failure routes through `ScannerEvent.PairingFailed → Error` (state flips off `Decoded`, so the keyed effect re-runs as a no-op). The orchestration stays in the composable — the VM remains a pure synchronous state machine, so the parse+derive sit at the call site and the suspend save stays a callback rather than splitting into the VM. See the [Pairing confirm gate](pairing-confirm-gate.md). The manual form owns its separate draft and persistence lifecycle while sharing the confirmation surface.
- **Back arrow pops to Welcome.** `onNavigateBack = { navController.popBackStack() }` — Welcome is the only entry to `SCANNER`, matching the `AboutScreen` back pattern. (#334 replaced #326's back-arrow-fires-`onTap` stub.)
- **Manual entry is navigation only.** `onPasteCode` opens `pair_code`; validation, fingerprint binding and persistence belong to that destination. [Manual return rules](navigation.md#manual-pairing-entry-and-return) distinguish draft return, caller return and connection-ready success.
- **Save precedes connection start and navigation.** `confirmPairingAndConnect` awaits the credential write; a `PairedServerStoreException` reports failure through the scanner's Error state without starting a new connection.
- **`popUpTo(Routes.SCANNER) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.SCANNER)` is a no-op since Scanner is the top). `launchSingleTop` guards against double-tap stacking duplicate ChannelList entries during the in-flight coroutine.
- **`requested` is `rememberSaveable`** to guard against re-prompting the runtime permission after a config change while `Denied` (the VM, which survives rotation, already retains the resolved state).

## State + concurrency

- **State holder.** A single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` on `ScannerViewModel`, exposed via `asStateFlow()` and collected with `collectAsStateWithLifecycle()` in the route. No `viewModelScope`, no coroutines in the VM — the mapping is synchronous (`onEvent`). The only async edge is the Android permission callback, which lives in the composable and feeds the VM via `onEvent`.
- **Persist scope.** `rememberCoroutineScope()` inside the `composable(Routes.SCANNER)` block drives the `save()`/`navigate()` side-effects — the gated `confirmPairAndNavigate`. Manual pairing instead uses its destination ViewModel scope. Cancels if the destination leaves the back stack mid-write — acceptable for a millisecond-scale DataStore write. Dispatcher is `Dispatchers.Main.immediate`; `DataStore.edit` hops to IO internally then resumes on Main for `navigate(...)`.
- **Rotation.** The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied` is retained across rotation; the `rememberSaveable` `requested` flag prevents a re-prompt while `Denied`. (One process-death gap — see Edge cases.)

## Error handling

- **Permission denied** (incl. "don't ask again") → `onEvent(PermissionDenied)` → `Denied` → the #61 screen. "Open settings" handles permanent denial; "Paste code" completes onboarding. #326 deliberately does **not** distinguish transient vs permanent denial (would need `shouldShowRequestPermissionRationale` + the Activity — "Open settings" covers both).
- **Parse / validation failure on a scanned payload** (#320: bad outer base64url, non-JSON, trailing data, missing/empty field, malformed relay, wrong-length/non-base64 pubkey) → `parsePairingPayload` returns `Failure` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error` → `ScannerErrorContent`. **Nothing is persisted** on any reject path; the user-facing copy is a fixed generic string, never a field value (see [Pairing payload parser](pairing-payload-parser.md) § no-leak).
- **Fingerprint derive failure** (#343: stored key non-base64-std / ≠ 32 bytes — structurally unreachable for a `Success`, the parser already proved base64-std-of-32-bytes) → `serverKeyFingerprint` returns `null` → fixed-reason `Log.w("fingerprint derive failed: bad-stored-key")` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error`. Handled so `staticKeyFingerprint`'s `require` can't throw into the `LaunchedEffect` coroutine; **nothing persisted**.
- **Confirm-path persist failure** (`PairedServerStoreException`, e.g. Keystore/IO, on the gated confirm save) → narrow `catch` in `confirmPairAndNavigate` → `Log.w` (`javaClass.simpleName` only) → `onEvent(PairingFailed(SAVE_FAILED_MSG))` → `Error` (since #343 this is the **only** scan-path save; before #343 the save sat in the `Decoded` effect). Manual pairing reports its own inline failure and retained-pairing state; see [failure behavior](paste-code-dialog.md#failure-and-cancellation).
- **Decline / system Back while confirming** (#343) → `onEvent(DeclinePairing)` → `ReadyToScan` → re-mounts `CameraPreview`, ready to re-scan. **Nothing persisted.** The `BackHandler` is gated on `AwaitingConfirm`, so Back acts as Decline only while the gate is open.
- **Camera bind failure** (no camera, in-use, provider init throws) → [`CameraPreview`](camera-preview.md)'s bind `try/catch` → `onCameraError(CAMERA_BIND_ERROR_MESSAGE)` → `onEvent(CameraError(...))` → `Error` → `ScannerErrorContent` (message + "Paste the pairing code instead"), **not** a blank viewport (AC4). The message is a fixed const — the exception is never interpolated (no detail leak). Live since #334.
- **No QR in frame / decode fails** → the analyzer's `FrameDecoder` returns `null` → silent no-op, next frame awaited (logs nothing). A successful decode → `QrDecoded` → `Decoded` → the route's parse-and-derive `LaunchedEffect` (#320/#343 — parks in `AwaitingConfirm`, no longer persists here).
- **Unknown routes** can't happen at runtime — `Routes.CHANNEL_LIST` is registered in the same `NavHost` in the same file.

## Edge cases / limitations

- **Back and paste are separate actions.** The toolbar calls `popBackStack()`; paste opens `pair_code`. Neither action confirms a pairing.
- **Process-death drops the resolved `Denied`/`ReadyToScan` state.** `requested` is `rememberSaveable` (survives process death) but the resolved state lives only in the VM (does **not**). After process death on the scanner with permission previously denied, `LaunchedEffect(Unit)` sees not-granted + `requested == true` → no branch fires → the viewport shell renders instead of `Denied`. Benign — the paste fallback still completes onboarding. The **on-resume permission re-check is still deferred** (a later pairing-polish ticket): grant-in-settings-then-return stays in `Denied`/shell until re-entering the scanner. Paste remains usable after returning from Settings; leaving and re-entering with permission granted starts the preview. The #640 frame update preserves this behavior. (Non-blocking NIT carried from #326.)
- **Camera success retains Welcome underneath.** Only `Scanner` is popped, so Back from the channel list can return to Welcome. A cold start uses the [saved collection](navigation.md#how-it-works) to select its initial destination. Manual pairing instead clears prior graph entries after target readiness; see [return rules](navigation.md#manual-pairing-entry-and-return).
- **Static scan-line.** The glow marks the scan area without animation; no `rememberInfiniteTransition` runs.
- **Radial gradients are circular, not elliptical.** Figma's SVG payload uses a `gradientTransform` matrix that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires a wrapping `Modifier.scale(...)` Box. The circular approximation reads identically as atmospheric haze and is what shipped — parity-of-intent, not pixel-identity of the SVG matrix.
- **Light-theme appearance uses the same theme roles.** The dark frame is the
  supplied design target. Light/dark captures at both supported test sizes were
  reviewed for #640; the helper now uses a 94%-opaque theme surface instead of a
  dark scrim. Stripes remain subtle (`onSurface` at 4% opacity); there is no
  `isSystemInDarkTheme()` layout branch.
- **Live camera acceptance remains separate from fixture coverage.** A fake
  preview slot proves layout and callback wiring, not CameraX binding or QR
  capture. `InteractiveStreamE2ETest` begins with injected pairing credentials:
  its [eight-test live pass for #640](https://github.com/pyrycode/pyrycode-mobile/issues/640#issuecomment-5756259017)
  does not prove real camera/QR → fingerprint → confirmed pairing or scanner →
  manual pairing against real daemons/live relay. Those scenarios and second-host
  management belong to [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676).
  See [Camera preview](camera-preview.md) and the existing
  [live gate](../../e2e-interactive-stream.md#pre-ship-gate); #640 adds no rung-3
  scenario or rung-4 twin.
- **`ScannerScreen.kt` is foundational, not disposable.** The `ScannerViewModel` state machine, the `when(state)` renderer, and `ScannerViewport` were **consumed** by #334 (the live preview injected through the `cameraPreview` slot, `CameraError` produced on bind failure), not replaced. `Routes.SCANNER` stays a single destination.

### Focused verification

`ScannerScreenTest` covers the Pairing title, permission/decoded viewport shells,
denial/error fallbacks, fake camera slot and fingerprint display, accessibility,
confirmation callbacks and 48 dp actions. `ScannerFrameTest` exercises 412×892 and
360×640 dp in light and dark themes, asserting divider/camera presence, clickable
Back/Paste, square reticle bounds, the centered 248 dp reference reticle, and
separation between the reticle, helper card, camera window and paste action.

Measure **the helper card's bounds**, not only its text: a reticle-to-text check
can pass while the reticle overlaps the card background. The fixture combines
reported system insets with minimum 24 dp top/bottom bands so bar-clearance checks
still require space on an ATD image without system chrome. Half a dp of tolerance
accounts for density conversion when comparing reticle dimensions. Back is
explicitly sized to 48 dp because the stock M3 button's visual bounds can be
smaller than its expanded touch target.

`PairCodeScreenTest.scannerPasteReturnsFromEveryRecoveryStateWithoutSaving` uses
production navigation and injects ready, denied and camera-error scanner states.
For each, it opens manual entry and returns with both Cancel and Android Back,
compares stored pairing snapshots without emitting credentials, then checks that
scanner Back reaches Welcome. This does not exercise the Settings permission
lifecycle or real QR capture.

On 2026-09-21, all **20 device methods** across these three classes and **35 JVM
regressions** across `ScannerViewModelTest`, `QrCodeAnalyzerTest`,
`PairingConfirmationTest`, `PairCodeViewModelTest` and `PairingPayloadParserTest`
passed without failures, errors or skips. The
[verifier's saved-XML review](https://github.com/pyrycode/pyrycode-mobile/pull/719#issuecomment-5756242269)
confirmed their execution in the broader UI/unit gates. The
[PR evidence](https://github.com/pyrycode/pyrycode-mobile/pull/719) records the
focused command and reviewed `scanner-{412,360}-{dark,light}.png` captures under
`app/build/outputs/managed_device_android_test_additional_output/debug/pixel2Api33Atd/`.
The captures use a non-secret fake preview; they establish frame appearance, not
live camera rendering.

## Related

- [Updated pairing scanner plan and revisions](../../specs/architecture/640-updated-pairing-scanner.md)
- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/12 (stub), https://github.com/pyrycode/pyrycode-mobile/issues/60 (Figma polish), https://github.com/pyrycode/pyrycode-mobile/issues/326 (stateful + permission flow), https://github.com/pyrycode/pyrycode-mobile/issues/333 (decode core), https://github.com/pyrycode/pyrycode-mobile/issues/334 (live CameraX preview), https://github.com/pyrycode/pyrycode-mobile/issues/320 (parse → real `PairedServer` + persist), https://github.com/pyrycode/pyrycode-mobile/issues/343 (fingerprint confirm gate)
- Specs: `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/60-scanner-screen-figma-polish.md`, `docs/specs/architecture/326-stateful-scanner-permission-flow.md`, `docs/specs/architecture/333-mlkit-qr-decode-pipeline.md`, `docs/specs/architecture/334-camerax-live-preview-scanner.md`, `docs/specs/architecture/320-qr-payload-parse-persist.md`, `docs/specs/architecture/343-pairing-fingerprint-confirm-gate.md`
- Ticket notes: `../codebase/12.md`, `../codebase/60.md`, `../codebase/326.md`, `../codebase/333.md`, `../codebase/334.md`, `../codebase/320.md`, `../codebase/343.md`, [`../codebase/489.md`](../codebase/489.md) (connect-on-pairing via `confirmPairingAndConnect`)
- Figma node: `13:2` (no confirm-pairing surface drawn — `AwaitingConfirm` is design-later)
- Upstream: #8 (NavHost), #295 (historical pairing startup gate), #60/#121 (atmosphere and scan-line drawing retained in the #640 frame), #61 (denied screen reused as the `Denied` state), #326 (stateful + permission), #333 (decode core), #320 (parse the `Decoded` payload → real `PairedServer`; the `PairingFailed → Error` route), #342 (`staticKeyFingerprint`, derived in the rewired `Decoded` effect), #343 (the confirm gate — `AwaitingConfirm` state, `PairingConfirmContent`, the gated persist)
- Downstream: [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676) owns
  real camera/manual pairing and second-host management coverage. The
  [manual pair-with-code flow](paste-code-dialog.md) shares the fingerprint gate
  and returns here on cancellation.
- Sibling docs: [Pairing confirm gate](pairing-confirm-gate.md) (the #343 security checkpoint hosted as this screen's `AwaitingConfirm` branch), [Pairing payload parser](pairing-payload-parser.md) (the #320 parse/validate/map boundary the `Decoded` binding runs + the #343 `serverKeyFingerprint`), [Static-key fingerprint](static-key-fingerprint.md) (the #342 derivation the gate displays), [Camera preview](camera-preview.md) (the #334 live-camera composable injected through this screen's slot), [QR code analyzer](qr-code-analyzer.md) (the #333 decode core feeding this screen's VM), [Scanner Denied screen](scanner-denied-screen.md), [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)
