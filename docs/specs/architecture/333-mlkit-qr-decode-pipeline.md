# Spec: ML Kit QR decode pipeline — once-per-scan debounce + decoded ScannerUiState (#333)

Phase 4 / pairing. The stateful `ScannerScreen` + camera-permission state machine shipped in #326:
a pure synchronous `ScannerViewModel` with `ScannerUiState` (`PermissionRequesting` / `ReadyToScan` /
`Denied` / `Error`) and a `ScannerEvent.CameraError` *failure* seam that has no live producer yet.

This slice adds the **decode core**: an ML Kit barcode `ImageAnalysis.Analyzer` that processes camera
frames, decodes a QR code to its string payload, and surfaces it **exactly once per scan** (debounced —
repeated detections of the same in-flight scan do not re-fire), feeding the existing VM `onEvent` seam
so the state transitions to a new **decoded state** carrying the string.

It is the **success counterpart** to #326's `CameraError` seam, and it is deliberately
**camera-independent**: exercised through the analyzer→ViewModel seam with fake frames, no physical
camera. Binding the analyzer to a live CameraX preview is the follow-up slice (#334, already
blocked-by this). Parsing the decoded string into a `PairedServer` is #320; the fingerprint /
safety-number confirm gate is #321.

The decoded QR string is **untrusted external input**. This slice surfaces it but does **not** parse,
validate, persist, or log its content.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` (whole, 53 lines) — the
  sealed `ScannerUiState` / `ScannerEvent` + the pure synchronous `onEvent` mapper. This slice adds
  exactly one case to each sealed hierarchy and one `when` arm. Mirror the existing `CameraError →
  Error(message)` shape for the success path.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:61-78` — the exhaustive
  `when (state)` dispatch. The new `Decoded` branch joins the existing
  `PermissionRequesting, ReadyToScan -> ScannerViewport(...)` arm; **the viewport stays unchanged**
  (no new visible surface — scope guard).
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/ScannerViewModelTest.kt` (whole, 33 lines) — the
  plain-JUnit, synchronous-VM test idiom (no `runTest`). The new `QrDecoded` transition test follows
  `cameraError_movesToErrorCarryingMessage`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` — `ComposeTestRule`
  idiom for the screen. The `Decoded`-renders-the-viewport test follows the existing `ReadyToScan`
  viewport assertions ("Pair with pyrycode", "pyry pair").
- `docs/specs/architecture/326-stateful-scanner-permission-flow.md` — the immediately-prior slice.
  The "define a dormant seam now, no live producer this slice, unit-test the transition" pattern
  (its `CameraError`/`Error` reasoning) is the template this slice follows for the success seam.
- `gradle/libs.versions.toml` (whole, 65 lines) + `app/build.gradle.kts:79-114` — the version catalog
  and the `dependencies { }` block. The two new deps land here. **Confirm**: no `camera`/`mlkit`
  entry exists yet (none does as of this writing).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:139-201` — the `composable(Routes.SCANNER)`
  route. **Read-only context**: this slice does NOT touch it (no live-camera wiring — that's #334).
  Read it only to confirm the analyzer is not yet instantiated in production this slice.

## Design source

N/A — this slice adds **no new visible surface**. The `Decoded` state renders the existing locked
viewport unchanged (scope guard); the visible decoded hand-off and its Figma anchor (Scanner, node
`13-2`) belong to the follow-up live-preview slice #334. The visual-fidelity check is intentionally
skipped here.

## Context

After #326, the scanner is a stateful screen whose `ReadyToScan` state renders a locked viewport with
no live camera, and whose `Error` state is driven by a defined-but-dormant `CameraError` event. The
ticket chain splits the camera engine into testable slices: **this slice** builds and unit-tests the
decode→surface→debounce core behind an analyzer seam; **#334** renders the CameraX preview and binds
this analyzer to it unchanged; **#320** parses the surfaced (untrusted) string; **#321** adds the
fingerprint confirm gate.

The decode core must be exercisable without a device so its correctness (debounce, decode→transition)
is covered by `./gradlew test`. The architectural lever that makes this possible is a **synchronous
frame-decode seam** injected into the analyzer (production = ML Kit; test = a fake returning canned
payloads), so the analyzer's `analyze()` path is driven on the JVM with fake frames.

## Design

### Package / files

All new code is UI-layer (camera/decode is an Android UI concern — kept out of `data/` per CLAUDE.md
and the ticket). **No `data/` changes. No DI change. No `MainActivity` change** (the analyzer is not
instantiated in production until #334 wires it — like #326's dormant `CameraError`).

| File | Change |
|------|--------|
| `ui/onboarding/QrCodeAnalyzer.kt` | **New** — `FrameDecoder` seam + `MlKitQrFrameDecoder` (production) + `QrCodeAnalyzer : ImageAnalysis.Analyzer` (debounce + dispatch). |
| `ui/onboarding/ScannerViewModel.kt` | **Modify** — add `ScannerEvent.QrDecoded(payload)`, `ScannerUiState.Decoded(payload)`, one `onEvent` `when` arm. |
| `ui/onboarding/ScannerScreen.kt` | **Modify** — add `Decoded` to the `ScannerViewport` arm of the `when (state)` (viewport unchanged). |
| `gradle/libs.versions.toml` | **Modify** — add ML Kit barcode-scanning + CameraX `camera-core` versions + library entries. |
| `app/build.gradle.kts` | **Modify** — add the two `implementation(...)` lines. |

Production source files (`.kt`/`.kts`, excluding tests/TOML): **4** — `QrCodeAnalyzer.kt`,
`ScannerViewModel.kt`, `ScannerScreen.kt`, `build.gradle.kts`. Under the 5-file `s` ceiling.

### Dependencies — `libs.versions.toml` + `build.gradle.kts`

Add (concrete versions below are known-good; the developer may bump to the latest stable patch — the
API surface used here (`ImageAnalysis.Analyzer`, `ImageProxy`, `InputImage.fromMediaImage`,
`BarcodeScanning.getClient`, `Barcode.rawValue`) is stable across CameraX 1.4.x/1.5.x and ML Kit 17.x):

```toml
# [versions]
cameraCore = "1.4.2"
mlkitBarcode = "17.3.0"

# [libraries]
androidx-camera-core   = { group = "androidx.camera", name = "camera-core", version.ref = "cameraCore" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }
```

`build.gradle.kts` `dependencies { }`: `implementation(libs.androidx.camera.core)` +
`implementation(libs.mlkit.barcode.scanning)`.

- **Bundled ML Kit** (`com.google.mlkit:barcode-scanning`), not the Play-Services-download variant
  (`com.google.android.gms:play-services-mlkit-barcode-scanning`): the model ships in the APK, so the
  pairing scan works offline on first run with no runtime model fetch (and no MITM surface on a model
  download). `barcode-scanning` pulls `vision-common` (`InputImage`) and `play-services-tasks`
  (`Tasks`) transitively — no extra entries needed.
- **`camera-core` only** this slice — it provides `ImageAnalysis.Analyzer` + `ImageProxy`. `camera2`,
  `camera-lifecycle`, `camera-view` (the preview/binding deps) are #334's, not this slice's.

### Analyzer — `QrCodeAnalyzer.kt`

Three small declarations. The **seam** that makes the analyzer JVM-testable is a synchronous
`FrameDecoder`: production decodes via ML Kit (blocking on the detection `Task`), tests inject a fake
that returns a canned payload and ignores the frame.

```kotlin
/** Decodes a single camera frame to a QR raw value, or null if no QR is present. Synchronous. */
fun interface FrameDecoder {
    fun decode(image: ImageProxy): String?
}

class QrCodeAnalyzer(
    private val onQrDecoded: (String) -> Unit,        // seam to the ViewModel (wired in #334)
    private val decoder: FrameDecoder = MlKitQrFrameDecoder(),
) : ImageAnalysis.Analyzer {
    private val hasSurfaced = AtomicBoolean(false)
    override fun analyze(image: ImageProxy) { /* contract below */ }
}
```

- **`analyze(image)` contract** (≤8 lines): in a `try { } finally { image.close() }`, call
  `decoder.decode(image)`; if it returns a non-null `raw` **and** `hasSurfaced.compareAndSet(false,
  true)`, invoke `onQrDecoded(raw)`. The `finally { image.close() }` is mandatory — CameraX will not
  deliver the next frame until the `ImageProxy` is closed. The latch is the once-per-scan debounce
  (AC2): the first successful decode flips it; every later frame (same or different payload) is
  ignored for this analyzer instance's lifetime. (Re-scan/reset = a fresh analyzer per scanner entry,
  owned by #334 — see Open questions.)
- **`MlKitQrFrameDecoder : FrameDecoder`** (production, **not unit-tested** — needs a device):
  owns a single `BarcodeScanner` from `BarcodeScanning.getClient(options)` where `options =
  BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()` (QR-only — faster,
  ignores other symbologies). `decode(image)`: `image.image ?: return null`, build `InputImage
  .fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)`, then `Tasks.await(scanner.process(
  input)).firstOrNull { it.rawValue != null }?.rawValue`, wrapped in `try/catch` that returns `null`
  on any failure (a failed/empty decode is the normal "no QR in this frame" case — **log nothing**).
- **`@ExperimentalGetImage` opt-in:** `ImageProxy.getImage()` is annotated `@ExperimentalGetImage`;
  using it without opting in trips the `UnsafeOptInUsageError` lint, and `lint { abortOnError = true }`
  fails the build. Annotate the `decode` function (and the test's fake `ImageProxy`, see Testing)
  with `@androidx.camera.core.ExperimentalGetImage`.
- **Threading contract for #334:** `Tasks.await` **blocks** and throws if called on the main thread.
  CameraX invokes `analyze` on the executor passed to `ImageAnalysis.setAnalyzer(executor, analyzer)`
  — #334 must pass a **background** executor (e.g. a single-thread executor), never the main one.
  Documented here so the wiring slice doesn't bind on main. This slice's tests call `analyze` on the
  test thread with the fake decoder (no `Tasks.await`), so the constraint doesn't bite here.

### State — `ScannerViewModel.kt`

Add the success counterpart to `CameraError`. The VM stays a pure synchronous mapper (no
`viewModelScope`, no Android types — AC4 testability).

```kotlin
// in ScannerUiState:
data class Decoded(val payload: String) : ScannerUiState {
    // AC5 deterministic net: never leak the untrusted payload via toString() (e.g. Log.d("$state")).
    override fun toString(): String = "Decoded(payload=<redacted ${payload.length} chars>)"
}

// in ScannerEvent:
data class QrDecoded(val payload: String) : ScannerEvent {
    override fun toString(): String = "QrDecoded(payload=<redacted ${payload.length} chars>)"
}
```

`onEvent` gains one exhaustive arm: `is ScannerEvent.QrDecoded -> ScannerUiState.Decoded(event.payload)`.
No logging, no side effects. `payload` is the **raw untrusted** string — a bare `String`, deliberately
not a "validated" type, so downstream callers (#320) know they hold unparsed input. The redacting
`toString()` overrides are the deterministic guarantee for AC5 (see Security review); they do not
affect the data-class `equals`/`hashCode`, so `assertEquals(Decoded("x"), …)` still works.

### Screen — `ScannerScreen.kt`

The exhaustive `when (state)` forces a `Decoded` branch. It joins the existing viewport arm — **no new
visible surface** (scope guard):

```kotlin
ScannerUiState.PermissionRequesting,
ScannerUiState.ReadyToScan,
is ScannerUiState.Decoded,
-> ScannerViewport(onTap = onTap, modifier = modifier)
```

(`is` is required for the `Decoded` data class; grouping it with the `data object` arms is valid.)
The previews stay on `ReadyToScan` — no change. `MainActivity` passes `state` straight through to
`ScannerScreen` and does not `when` on `ScannerUiState`, so it needs no edit.

## State + concurrency model

- **Single source of state** unchanged: `ScannerViewModel` exposes the one
  `StateFlow<ScannerUiState>`; the analyzer feeds it only through `onEvent` (the same indirection as
  #326's permission callback). No parallel mutable state.
- **No new coroutine / `viewModelScope` job.** The VM remains synchronous. The decode work runs on
  CameraX's analysis executor (#334-supplied), not in the VM.
- **Debounce = a lock-free `AtomicBoolean.compareAndSet(false, true)`** in the analyzer. `analyze`
  may run on a background thread; the latch is thread-safe and guarantees a single `onQrDecoded`
  call. The downstream `MutableStateFlow.value =` write is itself thread-safe; Compose collects via
  `collectAsStateWithLifecycle` on main (existing route pattern). No check-then-mutate TOCTOU — the
  `compareAndSet` *is* the atomic guard.
- **No shutdown/cancellation work** this slice (no camera resource held; analyzer not bound). CameraX
  lifecycle + `BarcodeScanner.close()` are #334's.

## Error handling

| Failure mode | Where | Surface |
|--------------|-------|---------|
| No QR in frame / ML Kit decode fails / `image == null` | `MlKitQrFrameDecoder.decode` returns `null` | Silent no-op; `analyze` closes the proxy and waits for the next frame. **Nothing logged** (a non-decoding frame is the common case, not an error). |
| Repeated detection of the in-flight scan | `hasSurfaced.compareAndSet` returns `false` | Ignored — surfaced exactly once (AC2). |
| Successful decode | latch flips → `onQrDecoded(raw)` → (in #334) `vm.onEvent(QrDecoded(raw))` | `Decoded(payload)` state; renders the unchanged viewport this slice. The payload is **not validated** here — malformed/hostile content is #320's parse step + #321's fingerprint gate. |

No new user-facing error surface; the `Error`/`Denied` states from #326 are untouched.

## Testing strategy

**Unit — `app/src/test/java/.../ui/onboarding/QrCodeAnalyzerTest.kt`** (new; `./gradlew test`, plain
JVM, no device). Drives `analyze()` with fake frames via an injected `FrameDecoder`, wiring
`onQrDecoded` to a real `ScannerViewModel` so the test exercises the full analyzer→ViewModel seam
(AC4). Needs a minimal `FakeImageProxy` implementing `ImageProxy` where **only `close()` is exercised**
(a no-op); all other members may `throw`/return defaults since the fake decoder ignores the frame
(annotate with `@androidx.camera.core.ExperimentalGetImage` for the `getImage()` override). Scenarios:

- **decode→surface (AC1, AC3):** fake decoder returns `"qr-payload"`; one `analyze(FakeImageProxy())`
  ⇒ VM state is `Decoded("qr-payload")` and the callback fired once.
- **once-per-scan, same payload (AC2):** decoder always returns `"p"`; two+ `analyze()` calls ⇒
  callback fired **exactly once** (assert via a counter), state is `Decoded("p")`.
- **once-per-scan, different payload (AC2):** decoder returns `"first"` then `"second"`; two
  `analyze()` calls ⇒ only `"first"` surfaces; state stays `Decoded("first")` (the latch ignores the
  second). Proves the debounce is per-scan, not per-payload.
- **no-QR then QR:** decoder returns `null` then `"late"`; first `analyze()` ⇒ state unchanged
  (still `PermissionRequesting`/`ReadyToScan`), callback not fired; second ⇒ `Decoded("late")`,
  fired once. Proves null frames don't consume the latch.

**Unit — `ScannerViewModelTest.kt`** (modify): add `qrDecoded_movesToDecodedCarryingPayload` —
`onEvent(QrDecoded("abc"))` ⇒ `state.value == Decoded("abc")`. (Mirrors the existing
`cameraError_…` test.)

**Instrumented — `ScannerScreenTest.kt`** (modify; `./gradlew connectedAndroidTest`,
`ComposeTestRule`): one test rendering `ScannerScreen(state = ScannerUiState.Decoded("x"), …)` and
asserting the viewport renders (e.g. "Pair with pyrycode" / "pyry pair" present) — proves the
`Decoded` branch is a no-visible-change viewport render (scope guard). Light; the AC's decode/debounce
coverage lives in the unit tests above.

No test asserts on the payload's *content* beyond identity (it's untrusted, unparsed here).

## Open questions

- **Re-scan / latch reset.** The latch is per-analyzer-instance and never resets, so one
  `QrCodeAnalyzer` surfaces one payload for its lifetime. That's correct for one scan. Re-scanning
  (e.g. after a failed pair in #321) means a **fresh analyzer per scanner entry** — #334 owns analyzer
  construction + CameraX lifecycle and is the natural home for reset. Flagged for #334; out of scope
  here (Simplicity First; not in the AC).
- **`QrDecoded` / `Decoded` naming.** Chosen to read as the success counterpart to `CameraError` /
  `Error`. The contract (a decoded event carrying a `String` → a decoded state carrying that `String`)
  is what matters; the developer may rename if a house convention emerges.
- **First-frame decode latency (bundled model).** The bundled ML Kit model adds ~2.2 MB to the APK
  and a small first-call init cost. Acceptable for offline-first pairing; not measured this slice
  (no live camera to measure against). Revisit in #334 if the first scan feels slow.

## Security review

**Verdict:** PASS

This ticket carries `security-sensitive`. Adversarial re-read of the spec above; the load-bearing
concern is that the **decoded QR string is untrusted external input** crossing into the app here.

**Findings:**

- **[Trust boundaries]** This analyzer is the single, explicit point where untrusted external data
  (a camera-decoded QR) enters the process, as a bare `String`. The boundary is named and narrow:
  `QrCodeAnalyzer.onQrDecoded: (String) -> Unit` → `ScannerEvent.QrDecoded(payload)` →
  `ScannerUiState.Decoded(payload)`. By design this slice does **not** parse, validate, persist, or
  branch on the payload — it only carries it. The type is deliberately a plain `String` (not a
  "validated" wrapper) so downstream code (#320) cannot mistake it for trusted. No finding requiring
  change; documented as the explicit boundary.
- **[File / storage]** No finding — **no filesystem operation exists** in this slice (the scope guard
  forbids persistence). The untrusted payload reaches no path-construction, no `File`, no DataStore;
  path-traversal/TOCTOU are structurally impossible here. Persistence of a *parsed* payload is #320/#321.
- **[Error messages, logs, telemetry]** **AC5 — primary finding, addressed deterministically.** The
  raw payload MUST NOT be logged anywhere (analyzer, `MlKitQrFrameDecoder`, VM, or any catch block).
  Two enforcement layers, different fabric: (1) the stochastic rule — no `Log.*`/`Timber.*` call
  references `payload`/`rawValue`; failed/empty decodes log nothing. (2) the deterministic net — the
  redacting `toString()` on `Decoded` and `QrDecoded` (in the Design above) means even an accidental
  `Log.d(TAG, "$state")` or `"$event"` cannot leak the payload, since the data-class `toString()` that
  would otherwise embed it is overridden. Code-review check (deterministic):
  `grep -rn 'Log\.\|Timber' app/src/main/java/de/pyryco/mobile/ui/onboarding/ | grep -i 'payload\|rawValue'`
  must be empty. There is no Crashlytics/Sentry dependency in the app, so no crash-reporter capture path.
- **[Inter-process / Android attack surface]** No finding — no new exported component, `intent-filter`,
  deep link, content provider, or WebView. The `CAMERA` permission was declared in #326; this slice
  adds none. The analyzer is internal and not instantiated in production until #334.
- **[Cryptographic primitives]** No finding — no crypto, RNG, or keys. The `AtomicBoolean` debounce is
  a correctness primitive, not a security one; payload identity is never compared against a secret.
- **[Network & I/O]** No finding — no network this slice. The **bundled** ML Kit model (not the
  Play-Services-download variant) means no runtime model fetch and therefore no model-download MITM
  surface. No WebSocket/OkHttp work here.
- **[Concurrency]** No finding — the once-per-scan latch is a lock-free `compareAndSet`, not a
  check-then-act on shared state; it guarantees a single dispatch even if `analyze` runs on a
  background thread. No new coroutine/scope. (Threading contract for the *blocking* `Tasks.await` —
  must run off-main — is documented for #334 in Design; it is a correctness constraint, not an
  exploit.)
- **[Threat model alignment]** On-screen leakage of the scanned payload is not possible this slice —
  the `Decoded` state renders the viewport unchanged and the payload is never displayed. OUT OF SCOPE,
  deferred and named: **content validation of a hostile/malformed QR → #320** (parse), **identity /
  MITM confirmation of the scanned server → #321** (fingerprint / safety-number gate),
  **cross-session replay of a captured QR → #320/#321** (the in-session latch only debounces one
  scan). No mobile-specific threat (overlay, screenshot, accessibility eavesdropping) applies to a
  state that renders no payload.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01
