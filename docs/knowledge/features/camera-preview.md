# Camera preview

The live-camera composable of the pairing scanner (#334): a CameraX `Preview` + a parallel
`ImageAnalysis` (feeding the existing [QR code analyzer](qr-code-analyzer.md)) bound to the
composable's lifecycle, rendered **behind** the locked [Scanner screen](scanner-screen.md) viewport
overlay (Figma `13:2`). It is the live producer for both seams the scanner had defined-but-dormant: a
real decode feeds `QrDecoded → Decoded`, a bind failure feeds `CameraError → Error`.

## What it does

- Shows the live back-camera feed, filling its bounds, inside the rounded scanner viewport — behind the
  reticle / atmosphere gradients / scan-line / hint card (which all draw on top, unchanged).
- Binds the [`QrCodeAnalyzer`](qr-code-analyzer.md) to a parallel `ImageAnalysis` use case so a real QR
  in frame produces a decode, surfaced **once per scanner entry** (a fresh analyzer per composition
  resets the once-per-scan latch).
- Starts/stops the camera with the composable's lifecycle; releases the camera + its analysis executor
  when the composable leaves composition.
- On camera-bind failure, reports a **fixed, detail-free** message — never the exception — so the
  scanner's `Error` recovery surface appears instead of a blank viewport.

## How it works

`ui/onboarding/CameraPreview.kt`. Public, stateless-to-the-caller contract:

```kotlin
@Composable
fun CameraPreview(
    onQrDecoded: (String) -> Unit,    // → vm.onEvent(QrDecoded(it)); fires at most once (analyzer latch)
    onCameraError: (String) -> Unit,  // → vm.onEvent(CameraError(it)) on bind failure (AC4)
    modifier: Modifier = Modifier,
)
```

It owns no app state — it feeds the `ScannerViewModel` only through those two callbacks, the same
`onEvent` indirection #326 used for the permission callback. The camera holds no `UiState`.

- **`PreviewView` via `AndroidView`**, `Modifier.fillMaxSize()`, with
  **`implementationMode = PreviewView.ImplementationMode.COMPATIBLE`**. This is **load-bearing**: the
  default `PERFORMANCE` mode backs the preview with a `SurfaceView` in a separate window that ignores
  both the parent `clip(RoundedCornerShape(24.dp))` and any sibling drawn on top — it would **punch
  through** the over-drawn reticle/gradient overlay. `COMPATIBLE` uses a `TextureView`, which
  composites in the Compose hierarchy, so the rounded-corner clip is honoured and the atmosphere /
  reticle draw correctly over the feed.
- **Background analysis executor:** `remember { Executors.newSingleThreadExecutor() }`. The analyzer
  runs here — **off-main is required**: `MlKitQrFrameDecoder.decode` blocks on `Tasks.await`, which
  throws on the main thread (#333's threading contract). `shutdown()` in `onDispose`.
- **Fresh analyzer per composition entry:** `remember { QrCodeAnalyzer(onQrDecoded = { currentOnQrDecoded(it) }) }`
  — one analyzer per `CameraPreview` composition, so the once-per-scan latch resets on each scanner
  entry. This is the re-scan reset #333 deferred to #334; the failed-pair → re-scan loop (#321) inherits
  it.
- **`rememberUpdatedState`** wraps `onQrDecoded` / `onCameraError`. The route allocates fresh slot
  lambdas every recomposition, while the analyzer and the bind listener are long-lived (`remember` /
  `DisposableEffect(Unit)`) — the indirection lets them call the *current* lambda without re-binding the
  camera or capturing a stale one.
- **Binding** (in `DisposableEffect(Unit)`): get `ProcessCameraProvider.getInstance(context)` (a
  `ListenableFuture`); on its listener — run on `ContextCompat.getMainExecutor(context)` — build a
  `Preview` (`setSurfaceProvider(previewView.surfaceProvider)`) and an `ImageAnalysis`
  (`STRATEGY_KEEP_ONLY_LATEST`, `setAnalyzer(analysisExecutor, analyzer)`), then `provider.unbindAll()`
  and `provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)`.
  `bindToLifecycle` ties camera start/stop to the lifecycle. The listener runs on **main** but only
  builds/binds use cases — it never calls `Tasks.await` (the analyzer does, on the background executor).
- **`LocalLifecycleOwner`** is imported from `androidx.lifecycle.compose` — the
  `androidx.compose.ui.platform` one is deprecated and trips `lint { abortOnError = true }`.

```kotlin
DisposableEffect(Unit) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply { setAnalyzer(analysisExecutor, analyzer) }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis,
            )
        } catch (_: Exception) {
            currentOnCameraError(CAMERA_BIND_ERROR_MESSAGE)   // fixed message — never interpolate `$e`
        }
    }, ContextCompat.getMainExecutor(context))
    onDispose {
        runCatching { cameraProviderFuture.get().unbindAll() }
        analysisExecutor.shutdown()
    }
}
```

### Rendering position (behind the overlay)

The scanner viewport `Box` renders `cameraPreview()` as its **back-most** child. The atmosphere
gradients — previously drawn by the Box's own `.drawBehind {}` (which paints behind *all* children) —
were moved into a `Box(Modifier.matchParentSize().drawBehind { … })` sibling placed *after* the camera,
so they layer **over** the feed; stripes / reticle / hint card follow on top. The Box keeps its
`surfaceContainerLowest` background (a pre-camera fallback fill) and the 24dp rounded clip. See
[Scanner screen](scanner-screen.md) for the full layer stack.

## Configuration / usage

Injected by the `composable(Routes.SCANNER)` route through the [Scanner screen](scanner-screen.md)'s
`cameraPreview` slot, **gated on `ReadyToScan`** so the camera binds only in that state:

```kotlin
cameraPreview = {
    if (state is ScannerUiState.ReadyToScan) {
        CameraPreview(
            onQrDecoded = { vm.onEvent(ScannerEvent.QrDecoded(it)) },
            onCameraError = { vm.onEvent(ScannerEvent.CameraError(it)) },
        )
    }
}
```

`PermissionRequesting` (behind the system permission dialog) and `Decoded` (handing off to pairing)
render the same viewport with an **empty** slot — no camera bound. A successful decode transitions to
`Decoded`, which the route reacts to with `LaunchedEffect(state) { if (state is Decoded) stubPairAndNavigate() }`
(the unchanged #295 stub-pair persist + navigate, replacing #326's tap affordance).

**Dependencies** (added via `gradle/libs.versions.toml` + `app/build.gradle.kts`, all pinned to the
existing `cameraCore = 1.4.2` — mixed CameraX artifact versions are unsupported):

- `androidx.camera:camera-view` → `PreviewView`.
- `androidx.camera:camera-lifecycle` → `ProcessCameraProvider.bindToLifecycle`.
- `androidx.camera:camera-camera2` → the Camera2 runtime backend; **exposes no directly-used API** but
  must be on the classpath or `ProcessCameraProvider.getInstance` finds no camera implementation.

`camera-core` (`ImageAnalysis` / `ImageProxy`) + bundled ML Kit barcode-scanning landed with #333. The
`CAMERA` permission + non-required `camera.any` feature were declared in #326 — **no manifest change**
this slice.

## Security boundary

- **The decoded QR string is untrusted external input** but `CameraPreview` only forwards it via
  `onQrDecoded` — it never parses, persists, displays, or logs it. The pairing handler it ultimately
  drives (`stubPairAndNavigate`) writes the **constant** `STUB_PAIRED_SERVER`, not the payload (content
  parse → #320; identity/MITM confirm → #321).
- **Bind-failure message never leaks detail.** `CAMERA_BIND_ERROR_MESSAGE` is a fixed private const; the
  exception is caught with `catch (_: Exception)` and **never** interpolated, so no device/stack detail
  reaches the `Error` UI (AC5). The camera/decode path logs nothing
  (`grep -rn 'Log\.\|Timber' ui/onboarding/` is empty); the redacting `toString()` on `Decoded`/`QrDecoded`
  (#333) is the deterministic backstop.
- **Live-camera threat model:** the viewfinder shows only what the camera is pointed at (a QR the user
  is deliberately scanning); the decoded payload is never rendered. The pairing trigger moved from tap
  to decode, so a transparent-overlay tap can no longer force a pair.

## Edge cases / limitations

- **Not unit-tested by design** — it needs a physical camera / `ProcessCameraProvider`; mocking the
  CameraX bind chain has negative value. Its collaborators are covered: the analyzer's decode/debounce
  by #333's `QrCodeAnalyzerTest` (JVM), the VM transitions by `ScannerViewModelTest`. The scanner's
  instrumented `cameraPreviewSlot_rendersBehindLockedOverlay` test pins the slot wiring with a **fake**
  preview (no real camera).
- **`onDispose` re-fetches the provider via `cameraProviderFuture.get()` on the main thread** (NIT,
  accepted). If disposal races the very first camera-HAL init, `.get()` can briefly block the UI thread;
  `runCatching` guards exceptions, not the block. Canonical CameraX-in-Compose idiom, no leak/crash. If
  revisited: remember the resolved `provider` in the bind listener and null-check it in `onDispose`.
- **First-scan model latency.** The bundled ML Kit model (#333) has a small first-call init cost, now
  measurable against a live camera. If the first scan feels slow on-device, an eager `getClient` warm-up
  is a cheap follow-up — out of scope here.
- **On-resume permission re-check still deferred** (from #326): grant-in-settings-then-return leaves the
  user in `Denied` until re-entering the scanner. Not changed by the camera lifecycle work here; a later
  pairing-polish ticket.

## Related

- Issue: https://github.com/pyrycode/pyrycode-mobile/issues/334 · PR:
  https://github.com/pyrycode/pyrycode-mobile/pull/340 · Spec:
  `docs/specs/architecture/334-camerax-live-preview-scanner.md`
- Ticket notes: [`codebase/334.md`](../codebase/334.md)
- Feature: [Scanner screen](scanner-screen.md) — the screen that injects this through its `cameraPreview`
  slot and renders it behind the locked overlay
- Feature: [QR code analyzer](qr-code-analyzer.md) — the analyzer this binds to the `ImageAnalysis` use
  case (built + unit-tested in #333; instantiated in production here)
- Upstream: #326 (the `CameraError`/`Error` + permission flow this produces), #333 (the
  `QrCodeAnalyzer` + `QrDecoded`/`Decoded` seam), #60/#121 (the locked viewport drawn on top), #295 (the
  stub-pair persist the decode now drives)
- Downstream: #320 (parse the untrusted payload → real `PairedServer`), #321 (fingerprint /
  safety-number confirm gate; owns the failed-pair → re-scan loop that uses this slice's per-entry latch
  reset)
