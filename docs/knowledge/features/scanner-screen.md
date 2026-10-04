# Scanner screen

QR-pairing screen with a live camera and a theme-based Pairing frame (Figma node `13:2`, updated in #640). **Stateful since #326**: a `ScannerViewModel` + sealed `ScannerUiState` drive a runtime camera-permission flow. **Live since #334**: the granted/`ReadyToScan` state now renders a real CameraX preview behind the locked overlay, and **a QR scan — not a tap — drives pairing**. Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination. The **decode core** landed in #333 (an ML Kit [QR code analyzer](qr-code-analyzer.md) + a `QrDecoded → Decoded(payload)` transition, the success counterpart to #326's `CameraError → Error`); #334 instantiates that analyzer in production, binds it to the live camera via the new [Camera preview](camera-preview.md) composable, and rewires the pairing trigger from tap to decode. **Real pairing since #320**: the `Decoded` binding parses + validates the scanned payload into a real `PairedServer` (the [Pairing payload parser](pairing-payload-parser.md)), replacing the throwaway stub on this path. **Gated since #343**: on a successful parse it no longer persists — it derives the server's [static-key fingerprint](static-key-fingerprint.md) and parks in a new `AwaitingConfirm` state; the full-screen `PairingConfirmContent` shows the fingerprint and requires an explicit confirm before anything saves (the [Pairing confirm gate](pairing-confirm-gate.md), the QR-TOFU MITM checkpoint). Decline / system Back persist nothing and re-arm the scanner. **Reports done only once the host answers, since #1386**: Confirm no longer navigates straight to the channel list — `ScannerViewModel` saves the record and then waits on the same shared [`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry) step the code path uses; the confirm modal stays up in a loading state through the wait, and a failure renders in its error slot with Retry (if retryable) and Cancel. The channel list opens only on success.

## What it does

- Requests the `CAMERA` runtime permission **on entry** (#326). Granted → the locked viewport with a live camera; denied → the existing [Scanner Denied screen](scanner-denied-screen.md) (#61), rendered in-route (no new route). On camera-bind failure the `ScannerErrorContent` recovery surface renders the `Error` state — whose live producer is now the [Camera preview](camera-preview.md) (#334).
- In `ReadyToScan`, shows the theme-based **Pairing** header with a 48 dp Back target and inset divider above the rounded live-camera window. The reference layout is 412×892 dp; at 360×640 dp the guide moves or shrinks to keep the helper and actions visible. The `Trouble scanning? Paste the pairing code instead` action sits below the window with a minimum 48 dp height.
- The viewport keeps the **live CameraX preview first**, beneath the blue/coral atmosphere, horizontal stripes and measured reticle/helper guides. The reticle is a centered 248 dp square when space permits, with a static glowing scan line. The bottom helper reads `Run pyry pair on your pyrycode server to generate a QR code.`; its theme-surface background and `onSurface`/`tertiary` text remain readable in either theme. The `surfaceContainerLowest` fill is only the camera fallback. See the [viewport layout](#scannerviewport--the-locked-viewport-body) for compact placement.
- A **decoded QR** (not a tap) drives pairing: the analyzer's once-per-scan callback → `QrDecoded` → `Decoded`, which the route reacts to (`LaunchedEffect(state)`) by running `parsePairingPayload(payload)` (#320 — the [Pairing payload parser](pairing-payload-parser.md)). **Since #343, on `Success` it does not persist** — it derives the fingerprint (`serverKeyFingerprint`, #342) and fires `ScannerEvent.PairingPrepared(fp, server)` → `AwaitingConfirm`, which renders the confirm surface. The **only** scan-path `PairedServerStore.save(...)` runs behind the Confirm button, inside `ScannerViewModel` itself (`ScannerEvent.ConfirmPairing`) — since #489 it delegates to the Android-free `confirmPairingAndConnect` (`save → connect`), so a **successful** persist also starts the relay supervision loop immediately (no background→foreground cycle). **Since #1386, a successful save does not yet mean paired**: the VM then waits on the shared [`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry) step for that saved record, and only reports `Paired` — which the route turns into the navigate to `channel_list` with the scanner popped from the back stack — once the step sees the host actually answer. A parse failure (or the structurally-unreachable derive-`null`) fires `PairingFailed(msg)` → `Error`; a `PairedServerStoreException` on the confirm save likewise → `Error`; a verification failure → `VerificationFailed`, rendered in the modal's error slot with Retry (if retryable) and Cancel. Decline (before saving) → `DeclinePairing` → `ReadyToScan`; Cancel (during the wait or after a failure) → `CancelVerification` → `Cancelled`, which the route turns into a pop — **the saved host is kept** in both the Decline and the Cancel case; only Decline additionally never saved anything. **The decoded payload is read**; only the confirmed record is saved, exactly once per scan regardless of Retry.
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
    onConfirmPairing: () -> Unit = {},          // #343/#1386 Confirm button → ScannerEvent.ConfirmPairing (VM saves, then waits)
    onDeclinePairing: () -> Unit = {},          // #343 Decline button → DeclinePairing → ReadyToScan
    onRetryPairing: () -> Unit = {},            // #1386 Retry button → RetryVerification (re-waits, no re-save)
    onCancelPairing: () -> Unit = {},           // #1386 Cancel button → CancelVerification → Cancelled (route pops)
    cameraPreview: @Composable () -> Unit = {}, // live camera (route injects for ReadyToScan; tests default {})
)
```

All defaulted params stay **after** `modifier` — Compose lint (`ComposableParameterOrder`/`abortOnError`) requires `modifier` to precede a trailing `@Composable () -> Unit` slot **and** the default-valued callbacks. The #343 spec asked for the two confirm callbacks "after `onPasteCode`, before `modifier`", but the lint forces them after `modifier`; all callers use named args, so the order is safe (the same lint family bit the `cameraPreview` slot in #334 — see [`codebase/334.md`](../codebase/334.md) and [`codebase/343.md`](../codebase/343.md)).

Dispatch (`ScannerScreen.kt:61`):

- `PermissionRequesting`, `ReadyToScan`, `is Decoded`, `Paired`, `Cancelled` → `private fun ScannerViewport(onNavigateBack, onPasteCode, cameraPreview, modifier)` — the viewport body, refreshed in #640. The `cameraPreview` slot is the only per-state divergence: the route injects a live [Camera preview](camera-preview.md) **only for `ReadyToScan`** (an empty slot for `PermissionRequesting`, the shell behind the system permission dialog, and `Decoded`, the brief hand-off before navigation). `Decoded` still renders **no new visible surface** — the decoded payload is never displayed (scope guard); it triggers the route's `LaunchedEffect(state)` parse-and-derive step before fingerprint confirmation; persistence waits for Confirm. **`Paired`/`Cancelled` (#1386) render this same bare viewport for the one frame before the route's own `LaunchedEffect(state)` navigates or pops** — the camera stays gated on `ReadyToScan`, so it never rebinds for either terminal state.
- `Denied` → `ScannerDeniedScreen(onNavigateBack, onOpenSettings, onPasteCode, modifier)` — the [denied surface](scanner-denied-screen.md), including its own “Pair with pyrycode” header, 48 dp Back target and exported reference illustration (#1151). All three callbacks come from the existing route; cancelling untouched manual entry returns to this denied state without saving.
- `Error(message)` → `private fun ScannerErrorContent(message, onPasteCode, modifier)` — a minimal centered `Surface`: the message in `onSurfaceVariant` (`bodyLarge`, centered) over a "Paste the pairing code instead" `TextButton` so onboarding stays completable. No Figma exists for this state; the camera-engine slice is its live producer.
- `AwaitingConfirm(fingerprint, server)`, `Verifying(fingerprint, server)`, `VerificationFailed(fingerprint, server, message, retryable)` → **one shared `when` branch** rendering `private fun PairingConfirmContent(fingerprint, onConfirm, onDecline, cancelLabel, submitLabel, loading, error, modifier)` (#343, extended #1386) — the **security checkpoint**: a peer full-screen `Surface` (mirrors `ScannerErrorContent`) inside the shared [mobile modal](mobile-modal.md), rendering the `fingerprint` verbatim in `FontFamily.Monospace` inside a `SelectionContainer` (selectable/copyable) with a `contentDescription`, compare copy ("…matches the `Static-key fp:` line that `pyry pair` shows on your other device…"). The branch derives the footer labels, `loading` and `error` from which of the three states it is, so the same `MobileModal` composition — and the same `Dialog` window — stays up across confirm, the wait and a failure; see [Pairing confirm gate § one modal window](pairing-confirm-gate.md#confirm-the-wait-and-a-failure-share-one-modal-window-1386). **Receives only the `fingerprint` string + callbacks — never the token-bearing `server`**, which stays in `ScannerViewModel` state. Design-later, deliberately non-decorative (no Figma yet) for `AwaitingConfirm`; the wait and failure states reuse the same shell with no new visuals. See [Pairing confirm gate](pairing-confirm-gate.md).

### State model — `ScannerViewModel`

A single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` exposed via `asStateFlow()`, with `fun onEvent(ScannerEvent)` mapping `PermissionGranted → ReadyToScan`, `PermissionDenied → Denied`, `CameraError(m) → Error(m)`, (since #333) `QrDecoded(p) → Decoded(p)`, (since #320) `PairingFailed(m) → Error(m)` (a parse/persist failure on the `Decoded` path; mirrors `CameraError`, carries a UI-owned constant, not a payload byte), and (since #343) `PairingPrepared(fp, server) → AwaitingConfirm(fp, server)` + `DeclinePairing → ReadyToScan` (the [confirm gate](pairing-confirm-gate.md) transitions). The `AwaitingConfirm` state carries the parsed `server` (so confirm saves exactly that record — no re-derive) and its derived `fingerprint` (so the surface renders without re-deriving); neither it nor `PairingPrepared` needs a custom `toString` — the only secret is `server.token`, already redacted by `PairedServer.toString()`, and the fingerprint is a public-key digest. `Decoded`/`QrDecoded` carry the **raw untrusted** QR string as a bare `String` and override `toString()` to redact it (`"<redacted N chars>"`) — a deterministic no-log net (AC5 of #333) that doesn't touch the data-class `equals`/`hashCode`. The analyzer that produces `QrDecoded` is the [QR code analyzer](qr-code-analyzer.md), fed in via `onEvent` exactly like the permission callback (its live producer arrives with #334). Mirrors the `ChannelListViewModel.pendingWorkspacePicker` `MutableStateFlow` + `asStateFlow()` idiom. See [`codebase/326.md`](../codebase/326.md) for the full pre-#1386 state/event table.

**Since #1386, the VM is no longer purely synchronous.** It takes `PairedServerStore`, `RelayConnectionController` and an `observe: (PairedServer) -> Flow<ConnectionStatus?>` constructor dependency (the DI module wires `observe` to `RelayConnectionRegistry::pairingStatus`) and gains `viewModelScope`. `ConfirmPairing` from `AwaitingConfirm` moves state to `Verifying(fingerprint, server)` **synchronously** — closing the earlier "camera double-confirm is not guarded" gap as a side effect, since a second tap while already `Verifying` falls through to the no-op branch — then launches a `Job` that saves via `confirmPairingAndConnect` and awaits the shared [`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry) step (the same one [#1385](../../specs/architecture/1385-pairing-verification-rule.md) extracted for `PairCodeViewModel`) on the saved record: `Connected` → `Paired`; a `Failure` → `VerificationFailed(fingerprint, server, message, retryable)`, with the step's `message`/`retryable` copied out because a public sealed state cannot hold the `internal PairingVerification.Failure` directly. `RetryVerification` re-launches the same wait on the same `server` (no re-save); `CancelVerification` cancels the `Job` and moves to `Cancelled`. The one held `Job` is cancelled by `CancelVerification` and by `onCleared`, and a wait's result is applied only while state is still `Verifying` for that exact `(fingerprint, server)` — a late resume after Cancel or a newer wait can never navigate. While `Verifying` or `VerificationFailed`, every event other than `RetryVerification`/`CancelVerification` is ignored (`Paired`/`Cancelled` ignore everything): a rotation re-sends `PermissionGranted` through the route's permission-check `LaunchedEffect(Unit)`, and under the pre-#1386 unconditional transition that would have replaced the modal with `ReadyToScan` while the wait kept running underneath. Putting the wait on `viewModelScope` rather than a route-held `rememberCoroutineScope` job is what lets it survive rotation at all. Debug-only `RelayLog` events (`scanner_pair_save_started`, `scanner_pair_connection_wait`, `scanner_pair_connected`, `scanner_pair_failed code=…`, `scanner_pair_retry`, `scanner_pair_cancel`) carry only static names and the failure's `code` — never the server, token, fingerprint or status payload. See [Pairing confirm gate § Confirm, Retry and Cancel are events](pairing-confirm-gate.md#confirm-retry-and-cancel-are-events-the-vm-owns-the-wait-1386) for the full design.

### `PairingHeader` — one header for Scanner, Denied and Pair Screen

Internal composable in `ui/onboarding/PairingHeader.kt` (#1463), shared by this
screen's `ScannerViewport`, [Scanner Denied](scanner-denied-screen.md) and
[Pair Screen](paste-code-dialog.md). Before #1463 each screen drew its own header
row with its own top padding and height, and the three landed at three different
heights on a real device (28, 24 and 6 px off Figma at 412×892 with real 24 dp
system bars) even though all three frames put the title's 28 px line box 24 px
below the status bar. The shared header owns geometry and chrome; callers retain their own content:
`PairingHeader(title, titleColor, onBack, backIcon, backdropSource, modifier, startPadding, backEnabled, divider)`
draws a `heightIn(min = 48.dp)` row padded 14 dp from its top — so the centered
28 dp title line box starts 24 dp below wherever the row is placed — and, when
`divider = true`, a `HorizontalDivider` 6 dp under the row (44 dp below the title
top). Each caller applies `systemBarsPadding()` first and places `PairingHeader`
at the inset edge; the body below it keeps each frame's own layout, so a header
move shifts the body with it (Scanner's card, Pair Screen's form) rather than
leaving a gap or an overlap.

Since #1648, the header Column uses `chromeBackdrop(backdropSource,
MaterialTheme.colorScheme.threadColors.headerBackdrop, top = true)` for the
shared theme-backed downward gradient and progressive background blur. Its
content Row uses `defaultChromeShadow()` for the Default alpha-following shadow
on the title and Back glyph; foreground controls draw sharp afterward. The
divider stays outside the shadow. These are the [thread's shared effects](thread-screen-how-it-works-overlays-and-app-bar.md),
not copied gradient or shadow constants.

Each caller remembers a screen-local `HazeState` and records only its full-size
background in a sibling `hazeSource` behind the inset-aware content. Recording
the content itself would sample foreground controls or feed the header effect
back into its own source. The decoration leaves the geometry below unchanged.

Two things are easy to miss when touching this composable:

- **The title's bounds, not its glyphs, carry the contract.** Compose's default
  `LineHeightStyle` trims a single-line `Text` to the font's own height, so the
  title's measured top would land 2 dp low. The header pins
  `MaterialTheme.typography.titleLarge.copy(lineHeightStyle = LineHeightStyle(Center, Trim.None))`,
  which keeps the box the full 28 dp with glyphs centered inside it — the glyphs
  land exactly where they did before, but the box (and the `pairing_header_title`
  test tag's bounds) now matches Figma's line box. Asserting glyph position
  instead of the tagged node's bounds would miss a future regression here.
- **Each frame keeps its own title, color, icon and divider setting, not just its
  copy.** Scanner and Pair Screen draw "Pairing" in `onPrimaryContainer` with a
  divider; Denied draws "Pair with pyrycode" in `onSurface` with none
  (`divider = false`, `startPadding = 4.dp` to match its narrower back-icon inset).
  A caller that drops one of these per-screen parameters silently reverts to
  Scanner's look.

**Verification lesson:** header geometry is asserted in both the sharedTest
`PairingHeaderGeometryTest` (renders all three screens at `w412dp-h892dp` with
24 dp bars applied, asserting the title top and divider offset) and in two
androidTest device classes that independently pinned the pre-#1463 Back-button
height — `MainActivityInsetsDeviceTest` (Scanner and Pair Screen) and
`ScannerDeniedRouteDeviceTest` (Denied). Both now assert the `pairing_header_title`
top at the status inset + 24 dp rather than a Back-button offset, so a future
header change does not need retuning in two places as this ticket did. A header
move must update all three: the sharedTest, and both device classes.

### `ScannerViewport` — the locked viewport body

The outer `Surface(color = colorScheme.surface)` fills the screen; an inner `Column`
applies `systemBarsPadding()`. Its header is the shared [`PairingHeader`](#pairingheader--one-header-for-scanner-denied-and-pair-screen)
(`title = "Pairing"`, `onPrimaryContainer`, divider on), which places the title's
28 dp line box 24 dp below the status inset and the divider 44 dp below the title
top. The weighted window has 16 dp side insets and starts 24 dp below the header
(93 dp from the inset, including the header). Below it, a 16 dp padded container
holds the centered paste `TextButton` (`labelLarge` / `primary`, minimum 48 dp
height). Native touch targets and system insets consume space absent from the
frameless design canvas.

The viewport has no tap-to-pair gesture. Back and Paste have separate callbacks;
a QR decode starts camera pairing. Colors and typography come from theme roles;
`Color.Transparent` is used only for radial-gradient terminal stops.

The viewport `Box` stacks `cameraPreview()` first, then a 60%-opaque
`colorScheme.scrim` layer, the radial-gradient sibling, stripe `Canvas`
and `ScannerGuides`. The scrim darkens real imagery without hiding it. The private `ScannerGuides` layout
measures the helper **including its 16 dp outer padding** before constraining the
reticle. The reticle remains square, at most 248 dp and no larger than the window
width or the height left above the padded helper. It stays centered when it fits,
otherwise moves upward and shrinks only when needed. The helper stays bottom
aligned; its top padding provides the 16 dp reticle-to-card gap. Measurement is
synchronous and adds no state, coroutine or camera lifecycle behavior.

- **Root atmosphere**: `scannerAtmosphere` draws a theme-derived blue radial center over the surface behind the header, window and actions. It fills a full-size sibling `hazeSource` behind the `systemBarsPadding()` column, so atmospheric color reaches the screen edges while content respects system bars. The header samples this atmosphere; camera, reticle and controls are outside the source. The same drawing helper serves the denied surface.
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

Recomposition seam: trivial. The whole screen recomposes when the M3 theme changes; nothing else mutates. The radial brushes, the `AnnotatedString`, the stripe color, the corner composables, and the scan-line `Canvas`'s `BlurMaskFilter`-backed `Paint` are all reallocated on every recomposition — all cheap, all intentional (no `remember` blocks). The scan-line shadow uses `android.graphics.BlurMaskFilter`, which renders correctly with hardware acceleration on API 28+; min SDK 33 is comfortably inside the supported envelope (#121 swapped from `Modifier.blur` to `BlurMaskFilter` for a true CSS-equivalent drop shadow, not just a coincidental SDK-floor improvement).

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

    // #1386: Confirm saves and then waits in the VM; the channel list opens only once the host
    // answered, and Cancel pops the scanner with the host still saved.
    LaunchedEffect(state) {
        when (state) {
            ScannerUiState.Paired ->
                navController.navigate(Routes.CHANNEL_LIST) {
                    popUpTo(Routes.SCANNER) { inclusive = true }
                    launchSingleTop = true
                }
            ScannerUiState.Cancelled -> navController.popBackStack()
            else -> Unit
        }
    }

    // #343 (rewired from #320): a successful decode parses into a real PairedServer and derives its
    // static-key fingerprint, then parks in AwaitingConfirm — it does NOT persist. The persist moves
    // behind the Confirm button (ScannerEvent.ConfirmPairing); this effect never touches the store. A
    // parse failure, or a derive that returns null (structurally unreachable for a Success, but handled
    // so staticKeyFingerprint's require can't throw into this coroutine), routes to Error. On the
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
    // #1386: Back during the wait or after a failure stops waiting and pops the scanner; the host
    // stays saved.
    BackHandler(enabled = state is ScannerUiState.Verifying || state is ScannerUiState.VerificationFailed) {
        vm.onEvent(ScannerEvent.CancelVerification)
    }

    ScannerScreen(
        state = state,
        onNavigateBack = { navController.popBackStack() },
        onOpenSettings = { /* ACTION_APPLICATION_DETAILS_SETTINGS intent */ },
        onPasteCode = { navController.navigate(Routes.PAIR_CODE) },
        // The VM confirms only from AwaitingConfirm, saving exactly the record whose fingerprint is
        // shown; once back/decline moved it off, a late tap is a no-op.
        onConfirmPairing = { vm.onEvent(ScannerEvent.ConfirmPairing) },
        onDeclinePairing = { vm.onEvent(ScannerEvent.DeclinePairing) },
        onRetryPairing = { vm.onEvent(ScannerEvent.RetryVerification) },
        onCancelPairing = { vm.onEvent(ScannerEvent.CancelVerification) },
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

DI (`AppModule.kt`): `viewModel { val registry = get<RelayConnectionRegistry>(); ScannerViewModel(get(), registry, registry::pairingStatus) }` — `get<PairedServerStore>()` is the store the route used to inject directly.

Notes:

- **Route owns the permission API + the camera; the VM owns persistence, the wait and navigation-worthy state; the screen is stateless.** `koinViewModel<ScannerViewModel>()` + `collectAsStateWithLifecycle()`, consistent with every other destination. The runtime permission launcher, `checkSelfPermission`, and the live [Camera preview](camera-preview.md) live in the route — the camera feeds the VM only through `onEvent(QrDecoded | CameraError)`. **Since #1386 the route no longer injects `PairedServerStore`/`RelayConnectionController` or holds a `rememberCoroutineScope` for the confirm side-effect** — the VM does, so the save-then-wait survives rotation; the route's job is reduced to a `LaunchedEffect(state)` that turns `Paired`/`Cancelled` into navigate/pop.
- **The camera slot is gated on `ReadyToScan`.** Binding only in that state means `PermissionRequesting` (behind the system dialog), `Decoded` (handing off), and now `Paired`/`Cancelled` (the one frame before navigate/pop) render the viewport with an empty slot — no camera bound when there shouldn't be one.
- **Decode drives pairing via `LaunchedEffect(state)`; the persist and the post-save wait are gated (#343, #1386).** The terminal `Decoded` state runs `parsePairingPayload` (#320 — the [Pairing payload parser](pairing-payload-parser.md)) and, on `Success`, derives the fingerprint (`serverKeyFingerprint`) and fires `PairingPrepared → AwaitingConfirm` — **this effect never persists**. The save and the wait both now live in the VM behind `ScannerEvent.ConfirmPairing`; the route's only remaining job after Confirm is to react to the VM's eventual `Paired`/`Cancelled` state. See [Pairing confirm gate](pairing-confirm-gate.md). The manual form owns its separate draft and persistence lifecycle while sharing the confirmation surface.
- **Back arrow pops to the caller.** `onNavigateBack = { navController.popBackStack() }` reaches both the viewport and denied header. From fresh onboarding it returns to Welcome without saving a pairing.
- **Manual entry is navigation only.** `onPasteCode` opens `pair_code`; validation, fingerprint binding and persistence belong to that destination. [Manual return rules](navigation.md#manual-pairing-entry-and-return) distinguish draft return, caller return and connection-ready success.
- **Save precedes the wait, which precedes navigation.** `confirmPairingAndConnect` awaits the credential write and starts the relay loop; a `PairedServerStoreException` reports failure through the scanner's `Error` state without starting a connection or a wait. Only a successful save reaches `verifySavedPairing`.
- **`popUpTo(Routes.SCANNER) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.SCANNER)` is a no-op since Scanner is the top). `launchSingleTop` guards against a `Paired` recomposition stacking duplicate ChannelList entries.
- **`requested` is `rememberSaveable`** to guard against re-prompting the runtime permission after a config change while `Denied` (the VM, which survives rotation, already retains the resolved state).

## State + concurrency

- **State holder.** A single `MutableStateFlow<ScannerUiState>(PermissionRequesting)` on `ScannerViewModel`, exposed via `asStateFlow()` and collected with `collectAsStateWithLifecycle()` in the route. The permission/decode transitions stay synchronous (`onEvent`); the only async edges are the Android permission callback (lives in the composable, feeds the VM via `onEvent`) and, since #1386, the VM's own save-then-wait.
- **Persist + wait scope (#1386).** One `operation: Job?` on `viewModelScope` (Main) covers the save and the wait, or a Retry's wait alone. `CancelVerification` and `onCleared` (route popped, including system Back with the dialog gone) cancel it. A result from `verify()` is applied only while state is still `Verifying` for that exact `(fingerprint, server)`, so a late resume can never navigate after Cancel or override a newer wait. Before #1386 this was a route-held `rememberCoroutineScope()` job driving `save()`/`navigate()` directly; moving it into the VM is what lets the wait survive rotation. Manual pairing uses its own destination ViewModel scope the same way.
- **Rotation.** The VM survives configuration changes, so a resolved `ReadyToScan`/`Denied`/`Verifying`/`VerificationFailed` is retained across rotation; the `rememberSaveable` `requested` flag prevents a re-prompt while `Denied`. While `Verifying`/`VerificationFailed`, the VM ignores a rotation-replayed `PermissionGranted` (and every other event but Retry/Cancel), so the confirm modal cannot be knocked away mid-wait. (One process-death gap — see Edge cases.)

## Error handling

- **Permission denied** (incl. "don't ask again") → `onEvent(PermissionDenied)` → `Denied` → the #61 screen. "Open settings" handles permanent denial; "Paste code" completes onboarding. #326 deliberately does **not** distinguish transient vs permanent denial (would need `shouldShowRequestPermissionRationale` + the Activity — "Open settings" covers both).
- **Parse / validation failure on a scanned payload** (#320: bad outer base64url, non-JSON, trailing data, missing/empty field, malformed relay, wrong-length/non-base64 pubkey) → `parsePairingPayload` returns `Failure` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error` → `ScannerErrorContent`. **Nothing is persisted** on any reject path; the user-facing copy is a fixed generic string, never a field value (see [Pairing payload parser](pairing-payload-parser.md) § no-leak).
- **Fingerprint derive failure** (#343: stored key non-base64-std / ≠ 32 bytes — structurally unreachable for a `Success`, the parser already proved base64-std-of-32-bytes) → `serverKeyFingerprint` returns `null` → fixed-reason `Log.w("fingerprint derive failed: bad-stored-key")` → `onEvent(PairingFailed(PARSE_FAILED_MSG))` → `Error`. Handled so `staticKeyFingerprint`'s `require` can't throw into the `LaunchedEffect` coroutine; **nothing persisted**.
- **Confirm-path persist failure** (`PairedServerStoreException`, e.g. Keystore/IO, on the gated confirm save) → `confirmPairingAndConnect`'s `onFailed` inside `ScannerViewModel.persist` → `RelayLog.w { "event=scanner_pair_failed code=save_failed" }` → `ScannerUiState.Error(SAVE_FAILED_MESSAGE)` (since #343 this is the **only** scan-path save; the #1386 wait is never reached on a failed save). Manual pairing reports its own inline failure and retained-pairing state; see [failure behavior](paste-code-dialog.md#failure-and-cancellation).
- **Post-save verification failure** (#1386: `DaemonAbsent`/deadline, `PairingRejected`, or `UpdateRequired` from the shared [`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry) step) → `VerificationFailed(fingerprint, server, message, retryable)` → the modal's error slot shows `message`, with Retry offered only when `retryable`. **The host stays saved** — a failed wait is not a failed save. Retry re-verifies the same `server` for a fresh wait; it never re-saves.
- **Decline (before saving) / Cancel (during the wait or after a failure) / system Back** (#343, extended #1386) → `DeclinePairing` → `ReadyToScan` (re-mounts `CameraPreview`, ready to re-scan, **nothing was ever saved**); or `CancelVerification` → `Cancelled` (route pops, **the already-saved host is kept**). One `BackHandler` is gated on `AwaitingConfirm` (Decline), a second on `Verifying`/`VerificationFailed` (Cancel) — Back acts as one or the other only while its gate is open.
- **Camera bind failure** (no camera, in-use, provider init throws) → [`CameraPreview`](camera-preview.md)'s bind `try/catch` → `onCameraError(CAMERA_BIND_ERROR_MESSAGE)` → `onEvent(CameraError(...))` → `Error` → `ScannerErrorContent` (message + "Paste the pairing code instead"), **not** a blank viewport (AC4). The message is a fixed const — the exception is never interpolated (no detail leak). Live since #334.
- **No QR in frame / decode fails** → the analyzer's `FrameDecoder` returns `null` → silent no-op, next frame awaited (logs nothing). A successful decode → `QrDecoded` → `Decoded` → the route's parse-and-derive `LaunchedEffect` (#320/#343 — parks in `AwaitingConfirm`, no longer persists here).
- **Unknown routes** can't happen at runtime — `Routes.CHANNEL_LIST` is registered in the same `NavHost` in the same file.

## Map

[Scanner screen — edge cases and testing](scanner-screen-edge-cases-and-testing.md)
holds `## Edge cases / limitations` and its `### Focused verification`
subsection, split out 2026-10-01 to keep this document under the 50000-byte cap
the docs guard enforces.

## Related

- [Updated pairing scanner plan and revisions](../../specs/architecture/640-updated-pairing-scanner.md)
- [One pairing header for Scanner, Denied and Pair Screen](../../specs/architecture/1463-shared-pairing-header.md) (#1463)
- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/12 (stub), https://github.com/pyrycode/pyrycode-mobile/issues/60 (Figma polish), https://github.com/pyrycode/pyrycode-mobile/issues/326 (stateful + permission flow), https://github.com/pyrycode/pyrycode-mobile/issues/333 (decode core), https://github.com/pyrycode/pyrycode-mobile/issues/334 (live CameraX preview), https://github.com/pyrycode/pyrycode-mobile/issues/320 (parse → real `PairedServer` + persist), https://github.com/pyrycode/pyrycode-mobile/issues/343 (fingerprint confirm gate)
- Specs: `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/60-scanner-screen-figma-polish.md`, `docs/specs/architecture/326-stateful-scanner-permission-flow.md`, `docs/specs/architecture/333-mlkit-qr-decode-pipeline.md`, `docs/specs/architecture/334-camerax-live-preview-scanner.md`, `docs/specs/architecture/320-qr-payload-parse-persist.md`, `docs/specs/architecture/343-pairing-fingerprint-confirm-gate.md`
- Ticket notes: `../codebase/12.md`, `../codebase/60.md`, `../codebase/326.md`, `../codebase/333.md`, `../codebase/334.md`, `../codebase/320.md`, `../codebase/343.md`, [`../codebase/489.md`](../codebase/489.md) (connect-on-pairing via `confirmPairingAndConnect`)
- Figma node: `13:2` (no confirm-pairing surface drawn — `AwaitingConfirm` is design-later)
- Upstream: #8 (NavHost), #295 (historical pairing startup gate), #60/#121 (atmosphere and scan-line drawing retained in the #640 frame), #61 (denied screen reused as the `Denied` state), #326 (stateful + permission), #333 (decode core), #320 (parse the `Decoded` payload → real `PairedServer`; the `PairingFailed → Error` route), #342 (`staticKeyFingerprint`, derived in the rewired `Decoded` effect), #343 (the confirm gate — `AwaitingConfirm` state, `PairingConfirmContent`, the gated persist)
- Downstream: [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676) owns
  real camera QR capture coverage. Manual pairing against real daemons/live relay
  is proven by #847, and second-host management (rename, unpair) by
  [#1085](https://github.com/pyrycode/pyrycode-mobile/issues/1085). The
  [manual pair-with-code flow](paste-code-dialog.md) shares the fingerprint gate
  and returns here on cancellation. [#1394](https://github.com/pyrycode/pyrycode-mobile/issues/1394)
  owns the #1386 save-then-wait flow's rung-3 live coverage.
- Sibling docs: [Pairing confirm gate](pairing-confirm-gate.md) (the #343 security checkpoint hosted as this screen's `AwaitingConfirm`/`Verifying`/`VerificationFailed` branch), [Pairing payload parser](pairing-payload-parser.md) (the #320 parse/validate/map boundary the `Decoded` binding runs + the #343 `serverKeyFingerprint`), [Static-key fingerprint](static-key-fingerprint.md) (the #342 derivation the gate displays), [Camera preview](camera-preview.md) (the #334 live-camera composable injected through this screen's slot), [QR code analyzer](qr-code-analyzer.md) (the #333 decode core feeding this screen's VM), [Pair-with-code § target readiness and retry](paste-code-dialog.md#target-readiness-and-retry) (the shared `verifySavedPairing` step this screen's `Verifying`/`VerificationFailed` states wait on, #1386), [Shared mobile modal](mobile-modal.md) (the nullable `submitLabel` a non-retryable failure uses), [Scanner Denied screen](scanner-denied-screen.md), [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)
- Ticket #1386, spec [`docs/specs/architecture/1386-scanner-pairing-verification.md`](../../specs/architecture/1386-scanner-pairing-verification.md) — routes the camera path through the shared verification step [#1385](../../specs/architecture/1385-pairing-verification-rule.md) extracted, split from #1322
