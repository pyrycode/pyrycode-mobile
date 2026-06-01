# Spec: CameraX live preview in scanner viewport + bind QR analyzer to stub-pair persist (#334)

Phase 4 / pairing. The stateful `ScannerScreen` + permission flow shipped in #326 (merged); the ML Kit
QR decode pipeline — a `QrCodeAnalyzer` that surfaces a decoded string **exactly once per scan** and a
`ScannerUiState.Decoded(payload)` state — shipped in #333 (this ticket's blocker, merged). Both the
`QrDecoded` success seam and the `CameraError` failure seam already exist on `ScannerViewModel` with no
live producer.

This slice makes the camera **visible and live**: a CameraX `Preview` use case rendered inside the
existing locked viewport (Figma `13:2`, behind the reticle/gradients/scan-line/hint card), bound to the
composable's lifecycle, with the existing `QrCodeAnalyzer` bound to a parallel `ImageAnalysis` use case
so a real scan produces a decode. On decode, the surfaced string drives the **existing stub-pair persist
(#295)** + navigation, replacing the temporary tap affordance from #326. On camera-bind failure, the
existing `CameraError` → `Error` state surfaces instead of a blank viewport.

**Scope guard:** the decoded QR string is untrusted external input. This slice routes the decode *event*
into the existing stub persist (which writes the hardcoded `STUB_PAIRED_SERVER` — the payload content is
**not** parsed into the persisted record) and navigates. No payload parsing (#320), no `PairedServer`
construction from the scan, no fingerprint gate (#321), no handshake.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:81-199` — `ScannerViewport`
  (private). The inner viewport `Box` (`:128-181`) is where the camera + atmosphere restructure happens:
  today it draws two radial gradients via the Box's `.drawBehind {}` (`:136-158`) then children
  (stripes `Canvas`, `Reticle`, `HintCard`). The camera must land **behind** the gradients — see Design.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:53-79` — `ScannerScreen` signature
  + `when (state)` dispatch. `onTap` is replaced by `onNavigateBack`; a `cameraPreview` slot is added.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/QrCodeAnalyzer.kt` (whole, 81 lines) — the analyzer
  to instantiate and bind. Constructor `QrCodeAnalyzer(onQrDecoded: (String) -> Unit, decoder = MlKit…)`;
  it is an `ImageAnalysis.Analyzer`. **Threading contract (already documented there):** `MlKitQrFrameDecoder`
  blocks on `Tasks.await`, so `setAnalyzer` MUST get a **background** executor, never the main one.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` (whole, 74 lines) — the
  `ScannerEvent.QrDecoded(payload)` / `ScannerEvent.CameraError(message)` events to fire and the
  `Decoded` / `Error` states they map to. **No change to this file** — both seams already exist.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:139-201` — the `composable(Routes.SCANNER)` route.
  This is where the camera preview slot is wired, `onTap`→`onNavigateBack` is renamed, and the
  `Decoded`-drives-stub-pair `LaunchedEffect` is added. `stubPairAndNavigate` (`:150-162`) is the
  **unchanged** persist+navigate target.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:344-345` — `AboutScreen(onBack = { navController
  .popBackStack() })`: the house back-navigation pattern `onNavigateBack` follows.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:354-366` — `STUB_PAIRED_SERVER` + the comment
  establishing it is a throwaway, non-load-bearing record (relay is `.invalid`). The decode does **not**
  feed its content into this record this slice.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` (whole, 153 lines) —
  7 `ScannerScreen(state = …, onTap = {}, …)` call sites. All update `onTap = {}` → `onNavigateBack = {}`
  (single `replace_all`); the `cameraPreview` slot defaults to `{}` so tests need not pass it.
- `gradle/libs.versions.toml:9-11,38-40` — `cameraCore = "1.4.2"` version + the `camera-core` / ML Kit
  library entries. The three new CameraX libraries reuse the existing `cameraCore` version ref.
- `app/build.gradle.kts:79-106` — `dependencies { }`. Add three `implementation(...)` lines.
- `app/src/main/AndroidManifest.xml:5-8` — `CAMERA` permission + optional camera feature are **already
  declared** (#326). **No manifest change this slice** (read only to confirm).
- `docs/specs/architecture/326-stateful-scanner-permission-flow.md`,
  `docs/specs/architecture/333-mlkit-qr-decode-pipeline.md` — the two prior slices; the "fresh analyzer
  per scanner entry" reset note (#333 Open questions) is owned here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

The locked dark scanner viewport (already built by #60/#121, wrapped stateful by #326): a transparent
`TopAppBar` ("← Pair with pyrycode") over a rounded-24dp `Box` holding a centered 248dp cyan
corner-reticle with a glowing scan line, faint atmospheric stripes + two radial gradients, a bottom
`scrim` hint card ("Run `pyry pair`…"), and a "Trouble scanning? Paste the pairing code instead" link.
**This slice changes no pixels of that overlay** — it only fills the dark scan region *behind* the
reticle/gradients/stripes/scan-line/hint card with the live CameraX preview. Match the locked visual;
do not redesign.

## Context

After #326 + #333 the scanner is a stateful screen whose `ReadyToScan` state renders a locked viewport
with **no camera**, whose `Decoded(payload)` state renders the same viewport unchanged, and whose
`Error` / `CameraError` seam has no live producer. Pairing is currently driven by a stub: every
interactive element fires `onTap` → `stubPairAndNavigate` (persist `STUB_PAIRED_SERVER` → `CHANNEL_LIST`).

This slice lights up the camera and rewires the pairing trigger from *tap* to *decode*: the analyzer
(built and unit-tested in #333) binds to the live camera, its once-per-scan callback feeds the existing
`QrDecoded` event, and the route reacts to the resulting `Decoded` state by running the **unchanged**
`stubPairAndNavigate`. The architectural lever keeping `ScannerScreen` testable is a **camera-preview
slot**: the screen stays a stateless `when(state)` renderer driven purely by `ScannerUiState` (so the
Compose tests drive every state with no physical camera), and the route injects the real CameraX
composable through the slot.

## Design

### Package / files

Camera is an Android UI concern — all new code is UI-layer, kept out of `data/` (portable per CLAUDE.md).
No `data/` change, no DI change, no manifest change.

| File | Change |
|------|--------|
| `ui/onboarding/CameraPreview.kt` | **New** — a `CameraPreview` composable: `PreviewView` via `AndroidView`, binds `Preview` + `ImageAnalysis` use cases (the latter feeding `QrCodeAnalyzer`) to the lifecycle, surfaces bind failure. |
| `ui/onboarding/ScannerScreen.kt` | **Modify** — replace `onTap` param with `onNavigateBack`; add a `cameraPreview: @Composable () -> Unit = {}` slot; remove the whole-surface tap-to-pair; render the preview behind the atmosphere overlay. |
| `MainActivity.kt` | **Modify** — `composable(Routes.SCANNER)`: inject the `cameraPreview` slot (only when `ReadyToScan`), rename `onTap`→`onNavigateBack` (`popBackStack`), add a `Decoded`→`stubPairAndNavigate` `LaunchedEffect`. |
| `app/build.gradle.kts` | **Modify** — three `implementation(...)` lines. |
| `gradle/libs.versions.toml` | **Modify** — three library entries (no new version — reuse `cameraCore`). |

Production source files (`.kt`/`.kts`, excl. tests + TOML): **3** (`CameraPreview.kt`, `ScannerScreen.kt`,
`MainActivity.kt`) + `build.gradle.kts` = **4**. Under the 5-file `s` ceiling.

### Dependencies — `libs.versions.toml` + `build.gradle.kts`

Add three CameraX libraries at the existing `cameraCore = "1.4.2"` ref (`camera-core` already present):

```toml
androidx-camera-camera2   = { group = "androidx.camera", name = "camera-camera2",   version.ref = "cameraCore" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraCore" }
androidx-camera-view      = { group = "androidx.camera", name = "camera-view",      version.ref = "cameraCore" }
```

`build.gradle.kts` `dependencies { }`: `implementation(libs.androidx.camera.camera2)` +
`implementation(libs.androidx.camera.lifecycle)` + `implementation(libs.androidx.camera.view)`.

- **`camera-view`** → `PreviewView`. **`camera-lifecycle`** → `ProcessCameraProvider.bindToLifecycle`.
  **`camera-camera2`** is the runtime Camera2 backend — it exposes no API used directly but MUST be on
  the classpath or `ProcessCameraProvider.getInstance` finds no camera implementation. All three are
  `implementation` (not `api`).
- No new version is introduced; all three pin to `1.4.2` to match `camera-core` (mixed CameraX
  artifact versions are unsupported).

### `CameraPreview.kt` — the live-camera composable

Public composable; contract (not body):

```kotlin
@Composable
fun CameraPreview(
    onQrDecoded: (String) -> Unit,   // → vm.onEvent(QrDecoded(it)) — fires at most once (analyzer debounce)
    onCameraError: (String) -> Unit, // → vm.onEvent(CameraError(it)) on bind failure (AC4)
    modifier: Modifier = Modifier,
)
```

Internals — each is a one-line contract; **no full body in this spec** (the developer writes the standard
CameraX-in-Compose idiom):

- **`PreviewView` via `AndroidView`** filling `Modifier.fillMaxSize()`. **Set
  `implementationMode = PreviewView.ImplementationMode.COMPATIBLE`** — this is load-bearing for AC1. The
  default `PERFORMANCE` mode backs the preview with a `SurfaceView` in a separate window that does **not**
  respect the parent Compose `clip(RoundedCornerShape(24.dp))` and can punch through the over-drawn
  reticle/gradient overlay. `COMPATIBLE` uses a `TextureView`, which composites in-hierarchy so the
  rounded-corner clip and the atmosphere/reticle drawn on top render correctly.
- **Background analysis executor:** `remember { Executors.newSingleThreadExecutor() }`. The analyzer runs
  here (off-main — required by #333's `Tasks.await` contract). Shut down in `onDispose`.
- **Analyzer:** `remember { QrCodeAnalyzer(onQrDecoded = { latestOnQrDecoded(it) }) }` — constructed once
  per composition entry, so it is a **fresh analyzer per scanner entry** (this is the re-scan/latch reset
  #333 deferred here). Wrap `onQrDecoded` / `onCameraError` in `rememberUpdatedState` so the long-lived
  analyzer / bind callback always call the current lambda, never a stale capture.
- **Binding:** obtain `ProcessCameraProvider.getInstance(context)` (a `ListenableFuture`); on its listener
  (run on `ContextCompat.getMainExecutor(context)`) build a `Preview` (`setSurfaceProvider(previewView
  .surfaceProvider)`) and an `ImageAnalysis` (`setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)`,
  `setAnalyzer(analysisExecutor, analyzer)`), then `provider.unbindAll()` and
  `provider.bindToLifecycle(LocalLifecycleOwner.current, CameraSelector.DEFAULT_BACK_CAMERA, preview,
  imageAnalysis)`. `bindToLifecycle` ties camera start/stop to the lifecycle (AC2). Wrap the bind in
  `try { … } catch (e: Exception) { onCameraError(CAMERA_BIND_ERROR_MESSAGE) }` (AC4).
- **Cleanup:** `DisposableEffect(Unit) { onDispose { runCatching { provider.unbindAll() }; analysisExecutor
  .shutdown() } }` — releases the camera when the composable leaves composition (AC2). (Lifecycle stop is
  already handled by `bindToLifecycle`; `unbindAll` covers the composition-exit case, e.g. on `Decoded`.)
- **`LocalLifecycleOwner`:** import from `androidx.lifecycle.compose.LocalLifecycleOwner` (the
  `androidx.compose.ui.platform` one is deprecated and trips `lint { abortOnError = true }`).
- **`CAMERA_BIND_ERROR_MESSAGE`:** a fixed, user-facing private const (e.g. *"Couldn't start the camera.
  Paste the pairing code instead."*). **Never** interpolate the exception / device details into it
  (Security: error-message leakage).

### `ScannerScreen.kt` — slot + callback restructure

The screen stays a stateless `when(state)` renderer. Two changes:

1. **Replace `onTap` with `onNavigateBack`; add the `cameraPreview` slot.** New signature:

```kotlin
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onNavigateBack: () -> Unit,             // TopAppBar back arrow → pop to Welcome
    onOpenSettings: () -> Unit,             // denied screen → app settings (unchanged)
    onPasteCode: () -> Unit,                // "Trouble scanning?" + denied/error paste → stub-pair (manual fallback)
    cameraPreview: @Composable () -> Unit = {},  // live camera (route injects; tests default to {})
    modifier: Modifier = Modifier,
)
```

The whole-surface `Modifier.pointerInput { detectTapGestures { onTap() } }` on the viewport `Surface`
(`:96-98`) is **removed** — it was the temporary tap-to-pair affordance the decode now replaces (AC3).
The back-arrow `IconButton` switches from `onClick = onTap` to `onClick = onNavigateBack`; the bottom
"Trouble scanning?…" `TextButton` switches from `onClick = onTap` to `onClick = onPasteCode`. The
`pointerInput` / `detectTapGestures` imports drop out. The `when(state)` arms are unchanged: the camera
viewport arm still covers `PermissionRequesting, ReadyToScan, is Decoded`; `Denied`/`Error` unchanged.

2. **Render the preview behind the atmosphere overlay** in the inner viewport `Box` (`:128-181`). The
   camera must sit **below** the gradients/stripes/reticle/hint card. Today the two radial gradients are
   drawn by the Box's own `.drawBehind {}`, which paints behind *all* children — so a child preview would
   cover them. Restructure (AC1):
   - Drop `.drawBehind { … gradients … }` from the Box modifier chain. **Keep** `.background(
     surfaceContainerLowest)` (the pre-camera fallback fill) and `.clip(RoundedCornerShape(24.dp))`.
   - First child: `cameraPreview()` — the back-most layer (renders nothing in `PermissionRequesting`;
     see route wiring).
   - Second child: an atmosphere overlay — `Box(Modifier.matchParentSize().drawBehind { … the two
     existing radial gradients, verbatim … })`. Moving the gradient draw into a `matchParentSize` child
     composites it **over** the camera. `blueStop`/`coralStop` vals (`:88-89`) are unchanged.
   - Remaining children unchanged and on top: stripes `Canvas` (`:160-172`), `Reticle`, `HintCard`.

   `ScannerViewport`'s private signature becomes `(onNavigateBack, onPasteCode, cameraPreview, modifier)`.
   The two `@Preview`s keep `state = ReadyToScan` and pass no `cameraPreview` (defaults to `{}`).

### `MainActivity.kt` — `composable(Routes.SCANNER)`

Three edits inside the existing route block; `stubPairAndNavigate`, the permission launcher, and the
entry-`LaunchedEffect` are otherwise unchanged.

1. **Inject the camera slot**, gated on permission-granted so the camera only binds in `ReadyToScan`:

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

2. **Decode drives the stub persist** (AC3) — react to the `Decoded` terminal state:

```kotlin
LaunchedEffect(state) {
    if (state is ScannerUiState.Decoded) stubPairAndNavigate()
}
```

   `stubPairAndNavigate` navigates with `popUpTo(SCANNER) { inclusive = true }`, removing this
   destination, so the effect cannot re-fire. The decoded `payload` is **not read** here — the existing
   stub persist writes the hardcoded `STUB_PAIRED_SERVER` (payload parsing is #320).

3. **`onTap`→`onNavigateBack`:** `ScannerScreen(state = state, onNavigateBack = { navController
   .popBackStack() }, onOpenSettings = openAppSettings, onPasteCode = stubPairAndNavigate, cameraPreview
   = …)`. `popBackStack` returns to `WELCOME` (the only entry to `SCANNER`), matching the `AboutScreen`
   back pattern. `onPasteCode` stays `stubPairAndNavigate` (the manual fallback until #320 makes it a real
   paste flow).

## State + concurrency model

- **Single source of state** unchanged: `ScannerViewModel.state: StateFlow<ScannerUiState>`. The camera
  feeds it only through `onEvent(QrDecoded | CameraError)` — the same indirection as #326's permission
  callback. No parallel mutable state; the camera holds no app state.
- **No new `viewModelScope` job.** The VM stays a pure synchronous mapper. The decode work runs on the
  `CameraPreview`-owned single-thread analysis `Executor` (off-main, per #333). The provider-future
  listener runs on the **main** executor (`getMainExecutor`) — it only builds use cases and binds; it must
  not call `Tasks.await` (the analyzer does, on the background executor).
- **Lifecycle / shutdown:** `bindToLifecycle(LocalLifecycleOwner)` starts/stops the camera with the
  composable's lifecycle (AC2: backgrounding stops the camera). `DisposableEffect.onDispose` calls
  `unbindAll()` + `executor.shutdown()` when the composable leaves composition (navigation to
  `CHANNEL_LIST`/`Error`, or back). The fresh-`QrCodeAnalyzer`-per-composition gives a clean once-per-scan
  latch each scanner entry.
- **Debounce TOCTOU:** none new — the analyzer's `AtomicBoolean.compareAndSet` (built in #333) guarantees
  a single `onQrDecoded`; the downstream `MutableStateFlow.value =` is thread-safe; Compose collects on
  main via `collectAsStateWithLifecycle`.
- **Camera preview is a `TextureView`** (`COMPATIBLE` mode) — it composites in the Compose hierarchy, so
  z-order (camera below, overlay above) and the rounded-corner clip are honored.

## Error handling

| Failure mode | Where | Surface |
|--------------|-------|---------|
| Camera fails to bind (no camera, in-use, `CameraSelector` resolves nothing, provider init throws) | `CameraPreview` bind `try/catch` | `onCameraError(CAMERA_BIND_ERROR_MESSAGE)` → `vm.onEvent(CameraError)` → `Error` state → `ScannerErrorContent` (message + "Paste the pairing code instead"). **No blank viewport** (AC4). The fixed message carries no exception detail. |
| No QR in frame / ML Kit decode fails / `image == null` | `MlKitQrFrameDecoder.decode` returns `null` (in #333) | Silent no-op; the proxy is closed, next frame awaited. Nothing logged. |
| Successful decode | analyzer latch → `onQrDecoded(raw)` → `QrDecoded` → `Decoded` → route `LaunchedEffect` | `stubPairAndNavigate`: persist `STUB_PAIRED_SERVER` → `CHANNEL_LIST`. Payload **not** validated/parsed here (#320/#321). |
| Stub-pair persist fails (`PairedServerStoreException`) | existing route `try/catch` (`:158-160`) | **Unchanged** — `Log.w(TAG, …javaClass.simpleName)`; stays on screen. Not routed through `Error`. |
| Permission denied | existing #326 flow | `Denied` → `ScannerDeniedScreen` (unchanged). Camera slot never invoked (gated on `ReadyToScan`). |

## Testing strategy

`CameraPreview` is **not unit-tested** — like `MlKitQrFrameDecoder`, it needs a physical camera /
`ProcessCameraProvider`; mocking the CameraX binding chain has negative value. Its collaborators are
already covered: the analyzer's decode/debounce by #333's `QrCodeAnalyzerTest` (JVM), the VM transitions
by `ScannerViewModelTest` (`QrDecoded → Decoded`, `CameraError → Error` already exist). This slice adds
no new VM behavior, so **no new unit test** is required.

**Instrumented — `ScannerScreenTest.kt`** (`./gradlew connectedAndroidTest`, `ComposeTestRule`):

- Update the 7 existing call sites `onTap = {}` → `onNavigateBack = {}` (single `replace_all`). Their
  assertions ("Pair with pyrycode", "pyry pair", "Trouble scanning?", "Camera permission required",
  the `Error`/`Decoded` renders) still hold — proves AC1 no-regression of the locked overlay, since
  `cameraPreview` defaults to `{}` (no camera needed in tests).
- The `pasteCodeFallback_hasClickAction` test still passes (the "Trouble scanning?" button keeps a click
  action — now `onPasteCode`).
- **Add one test:** the `cameraPreview` slot renders behind the overlay without disturbing it —
  `ScannerScreen(state = ReadyToScan, …, cameraPreview = { Box(Modifier.fillMaxSize().testTag("camera")) })`
  then assert both the slot node (`onNodeWithTag("camera").assertExists()`) **and** the overlay
  ("Pair with pyrycode" / "pyry pair") are present. This pins the slot wiring (AC1/AC2 surface) with a
  fake preview — no real camera. Describe as a scenario; the developer writes it in the file's idiom.

The route glue (permission launcher → event, `Decoded` → `stubPairAndNavigate`, camera binding) is thin
Android plumbing and not separately tested — consistent with #326's decision not to test the launcher
bridge. Manual smoke on a device (point at a `pyry pair` QR → `CHANNEL_LIST`; deny/airplane the camera →
`Error`) covers the live path.

## Open questions

- **On-resume permission re-check.** Deferred in #326 and still deferred: if the user opens app settings,
  grants, and returns, they stay in `Denied` until re-entering the scanner. Not in this slice's AC; the
  camera lifecycle work here doesn't change it. Leave for a later pairing-polish ticket.
- **First-scan model latency.** The bundled ML Kit model has a small first-call init cost; now measurable
  against a live camera. If the first scan feels slow on-device, a warm-up (`getClient` eagerly) is a
  cheap follow-up — out of scope here.
- **Re-scan after a failed pair.** A fresh `QrCodeAnalyzer` per `CameraPreview` composition already resets
  the once-per-scan latch on each scanner entry. The *failed-pair → return to scanner* loop (re-entry
  after #321's fingerprint reject) lands with #321; this slice provides the per-entry reset it will use.

## Security review

**Verdict:** PASS

This ticket carries `security-sensitive`. Adversarial re-read of the spec above. The load-bearing facts:
the decoded QR is **untrusted external input**, and a **live camera** is now bound. Walked every category:

**Findings:**

- **[Trust boundaries]** The untrusted boundary is unchanged from #333 and remains explicit and narrow:
  `QrCodeAnalyzer.onQrDecoded: (String) -> Unit` → `ScannerEvent.QrDecoded` → `ScannerUiState.Decoded`.
  **New this slice:** the `Decoded` state triggers `stubPairAndNavigate` via the route `LaunchedEffect` —
  but the handler **does not read the payload**; it persists the hardcoded `STUB_PAIRED_SERVER` and
  navigates to a fixed route. So a hostile QR can trigger *only* the same stub-pair-and-navigate a tap
  could before — it gains no new capability, controls neither the persisted bytes nor the nav target.
  Payload-content parsing (→ real `PairedServer`) is #320; identity/MITM confirmation is #321. Both named
  and out of scope. No finding requiring change.
- **[Tokens, secrets, credentials]** No finding — no token is generated, read, or compared this slice.
  `STUB_PAIRED_SERVER` is a public, non-routable (`.invalid`) throwaway documented as granting no access;
  storage choice is `PairedServerStore`'s (Keystore-wrapped, established upstream), untouched here.
- **[File / storage]** No finding — no filesystem op in this slice. The untrusted payload reaches no
  path construction, `File`, or DataStore (the scope guard forbids persisting *parsed* payload). The one
  persist (`stubPairAndNavigate`) writes a constant, payload-independent record. Path-traversal/TOCTOU are
  structurally impossible. `allowBackup`/storage scope are `PairedServerStore`'s concern, not changed.
- **[Inter-process / Android attack surface]** No finding — no new exported `Activity`/`Service`/
  `Receiver`, `intent-filter`, deep link, content provider, or WebView. The `CAMERA` permission +
  `camera.any` feature were declared in #326; this slice adds **no** manifest change. The two `Intent`s
  in the route (open-app-settings, the existing welcome `ACTION_VIEW`) are unchanged and target system
  handlers with no attacker-controlled extras.
- **[Cryptographic primitives]** No finding — no crypto, RNG, or keys introduced. The analyzer's
  `AtomicBoolean` debounce is a correctness primitive, not a security one; no value is compared to a secret.
- **[Network & I/O]** No finding — no network this slice. CameraX talks only to the local camera HAL; the
  **bundled** ML Kit model (chosen in #333) means no runtime model download, so no model-fetch MITM
  surface. No OkHttp/WebSocket work here.
- **[Error messages, logs, telemetry]** **Primary finding, addressed deterministically (AC5 continuity).**
  (1) The bind-failure message is a **fixed private const** — the exception is caught and dropped, never
  interpolated, so device/path/stack detail cannot leak into the `Error` UI. The spec forbids
  `onCameraError("$e")`. (2) The untrusted payload MUST NOT be logged: `CameraPreview` logs nothing about
  the scan; the redacting `toString()` on `Decoded`/`QrDecoded` (built in #333) means even an accidental
  `Log.d("$state")` cannot leak it. (3) The existing persist-failure log already logs only
  `e.javaClass.simpleName`, not the payload — unchanged. No Crashlytics/Sentry dependency exists.
  Deterministic code-review check: `grep -rn 'Log\.\|Timber' app/src/main/java/de/pyryco/mobile/ui/onboarding/`
  must show no reference to `payload`/`rawValue`/`"$e"`/`$state` in the camera/decode path.
- **[Concurrency]** No finding — the once-per-scan latch (`compareAndSet`) is unchanged and lock-free; no
  new coroutine/scope (the VM stays synchronous). The analysis `Executor` is owned by `CameraPreview` and
  `shutdown()` in `onDispose`; the camera is released via `unbindAll()` + lifecycle binding, so no leak
  outlives the composable. The blocking `Tasks.await` is pinned to the background executor (off-main) —
  a documented correctness constraint, not an exploit.
- **[Threat model alignment]** A live camera preview is now on screen. Mobile-specific threats considered:
  *screenshot / screen-recording capture of the viewfinder* — the viewfinder shows only what the camera
  points at (a QR the user is deliberately scanning); the decoded payload is never rendered (the `Decoded`
  state shows the unchanged viewport), so no secret is on-screen to capture. *Overlay / tap-jacking* — the
  pairing trigger moved from tap to decode, so a transparent-overlay tap can no longer force a pair;
  decode requires a real QR in frame. *Accessibility eavesdropping* — nothing sensitive is rendered as
  text. OUT OF SCOPE, named: hostile/malformed QR **content** validation → #320; server identity / MITM
  (safety-number) confirmation → #321; cross-session replay of a captured QR → #320/#321 (the in-session
  latch debounces one scan only).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01
