# QR code analyzer

The **decode core** of the pairing scanner (#333): a CameraX `ImageAnalysis.Analyzer` that decodes a
camera frame to a QR string via ML Kit and surfaces it **exactly once per scan**, feeding the
[Scanner screen](scanner-screen.md)'s `ScannerViewModel` through a new `QrDecoded` event → `Decoded`
state. It is the success counterpart to #326's `CameraError`/`Error` failure seam.

**Live since #334.** Built and unit-tested in #333 (before any device was involved, so the decode/
debounce logic is covered on the JVM via `./gradlew test`), the analyzer is now **instantiated in
production** by the [Camera preview](camera-preview.md) composable (#334), which constructs it once per
scanner entry, binds it to a CameraX `ImageAnalysis` use case on a **background** executor, and owns
its lifecycle.

## What it does

- Decodes a single camera frame (`ImageProxy`) to a QR code's raw string payload, or `null` if no QR
  is present (the common per-frame case).
- Surfaces the decoded string **exactly once per scan**: repeated detections of the same in-flight
  scan — same payload or a different one — do not re-fire (debounced). One `QrCodeAnalyzer` instance
  surfaces one payload for its lifetime.
- On the first successful decode, invokes its `onQrDecoded: (String) -> Unit` callback, which (since
  #334) feeds `ScannerViewModel.onEvent(QrDecoded(raw))`, transitioning the scanner to
  `ScannerUiState.Decoded(payload)` — which in turn drives the stub-pair persist + navigation.
- Surfaces the decoded string but does **not** parse, validate, persist, display, or log it — it is
  **untrusted external input** (see *Security boundary*).

## How it works

Three declarations in `ui/onboarding/QrCodeAnalyzer.kt`. The seam that makes the analyzer
JVM-testable is the synchronous `FrameDecoder`: production decodes via ML Kit; tests inject a fake
returning canned payloads, so `analyze()` runs on a plain test thread with no physical camera.

```kotlin
/** Decodes a single camera frame to a QR raw value, or null if no QR is present. Synchronous. */
fun interface FrameDecoder {
    fun decode(image: ImageProxy): String?
}

class QrCodeAnalyzer(
    private val onQrDecoded: (String) -> Unit,          // seam to the ViewModel (wired in #334)
    private val decoder: FrameDecoder = MlKitQrFrameDecoder(),
) : ImageAnalysis.Analyzer {
    private val hasSurfaced = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            val raw = decoder.decode(image)
            if (raw != null && hasSurfaced.compareAndSet(false, true)) {
                onQrDecoded(raw)
            }
        } finally {
            image.close()   // CameraX won't deliver the next frame until the ImageProxy is closed.
        }
    }
}
```

- **The `finally { image.close() }` is mandatory.** CameraX will not deliver the next frame until the
  `ImageProxy` is closed — closing in `finally` keeps the frame pump alive whether or not a QR was
  found and even if the decoder throws.
- **The once-per-scan debounce is the lock-free `AtomicBoolean.compareAndSet(false, true)` latch.**
  The first non-null decode flips it and fires `onQrDecoded`; every later frame is ignored. The
  `compareAndSet` *is* the atomic guard — no check-then-act TOCTOU even though `analyze` may run on a
  background thread. A `null` decode does **not** consume the latch, so a stretch of no-QR frames
  before the real scan is harmless.

### `MlKitQrFrameDecoder` — the production decoder (not unit-tested)

Owns a single QR-only `BarcodeScanner`
(`BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())`
— QR-only is faster and ignores other symbologies). `decode`:

```kotlin
@ExperimentalGetImage
override fun decode(image: ImageProxy): String? {
    val mediaImage = image.image ?: return null
    return try {
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        Tasks.await(scanner.process(input)).firstOrNull { it.rawValue != null }?.rawValue
    } catch (_: Exception) {
        null    // a failed/empty decode is the normal "no QR in this frame" case — log nothing
    }
}
```

Not unit-tested (it needs a device-decoded frame); tests inject a fake `FrameDecoder` instead.

### State seam — `ScannerViewModel`

The analyzer is Android/CameraX-bound; the `ScannerViewModel` it feeds stays a pure synchronous
Android-free mapper (so its transitions are plain-JUnit testable). The decoded string crosses the
boundary as a bare `String`:

```kotlin
// ScannerEvent: success counterpart to CameraError.
data class QrDecoded(val payload: String) : ScannerEvent {
    override fun toString(): String = "QrDecoded(payload=<redacted ${payload.length} chars>)"
}
// ScannerUiState:
data class Decoded(val payload: String) : ScannerUiState {
    override fun toString(): String = "Decoded(payload=<redacted ${payload.length} chars>)"
}
// onEvent arm:
is ScannerEvent.QrDecoded -> ScannerUiState.Decoded(event.payload)
```

The `Decoded` state renders the **existing locked viewport unchanged** — no new visible surface this
slice (scope guard). See [Scanner screen](scanner-screen.md) for the `when(state)` dispatch.

## Configuration / usage

- **Dependencies** (added via `gradle/libs.versions.toml` + `app/build.gradle.kts`):
  `androidx.camera:camera-core:1.4.2` (`ImageAnalysis.Analyzer` + `ImageProxy`) and the **bundled**
  `com.google.mlkit:barcode-scanning:17.3.0`. `barcode-scanning` pulls `vision-common` (`InputImage`)
  and `play-services-tasks` (`Tasks`) transitively. `camera-core` only this slice —
  `camera2`/`camera-lifecycle`/`camera-view` (preview/binding) arrive with #334.
- **Bundled, not Play-Services-download** (`com.google.mlkit:barcode-scanning`, not
  `com.google.android.gms:play-services-mlkit-barcode-scanning`): the ~2.2 MB model ships in the APK,
  so the pairing scan works **offline on first run** with no runtime model fetch — and no
  model-download MITM surface. The tradeoff is APK size + a small first-call init cost (acceptable for
  offline-first pairing; revisit in #334 if the first scan feels slow).
- **Wiring (live in #334):** [`CameraPreview`](camera-preview.md) constructs
  `remember { QrCodeAnalyzer(onQrDecoded = { currentOnQrDecoded(it) }) }` and binds it via
  `ImageAnalysis.Builder().setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST).build().apply { setAnalyzer(analysisExecutor, analyzer) }`,
  where `analysisExecutor = remember { Executors.newSingleThreadExecutor() }`. **The executor is a
  background executor** — `Tasks.await` in `MlKitQrFrameDecoder.decode` blocks and throws on the main
  thread; the provider-future listener that builds/binds runs on `getMainExecutor` but never calls
  `Tasks.await`. The analyzer is constructed **once per `CameraPreview` composition**, so re-scan after
  a failed pair gets a **fresh analyzer per scanner entry** (the latch resets per entry). `QrCodeAnalyzer`
  does not expose `BarcodeScanner.close()`; the scanner instance lives for the analyzer's lifetime and
  is reclaimed with it.

## Security boundary

This analyzer is the **single, explicit point where untrusted external data enters the app** — a
camera-decoded QR string. The boundary is named and narrow:
`QrCodeAnalyzer.onQrDecoded: (String) -> Unit` → `ScannerEvent.QrDecoded(payload)` →
`ScannerUiState.Decoded(payload)`.

- **Bare `String`, deliberately not a "validated" wrapper** — so downstream code (#320) cannot mistake
  the payload for trusted input.
- **Never logged (AC5), enforced with two layers of different fabric:** (1) the stochastic rule — no
  `Log.*`/`Timber.*` call references the payload; failed/empty decodes log nothing. (2) the
  deterministic net — the redacting `toString()` on `Decoded`/`QrDecoded` means even an accidental
  `Log.d(TAG, "$state")` cannot leak the payload. The redaction does not affect the data-class
  `equals`/`hashCode`. Code-review check:
  `grep -rn 'Log\.\|Timber' app/src/main/java/de/pyryco/mobile/ui/onboarding/ | grep -i 'payload\|rawValue'`
  must be empty (this grep does **not** distinguish comments from code — keep `Log.`/`Timber` tokens
  out of comments that name the redacted field; see [`codebase/333.md`](../codebase/333.md)).
- **No parse / persist / display this slice** — path-traversal/TOCTOU are structurally impossible (no
  filesystem op exists). Content validation of a hostile/malformed QR → #320; identity / MITM
  confirmation of the scanned server → #321; cross-session replay of a captured QR → #320/#321 (the
  in-session latch only debounces one scan).

## Edge cases / limitations

- **The latch never resets within an analyzer's lifetime.** One `QrCodeAnalyzer` surfaces one payload
  for its lifetime — correct for a single scan. Re-scanning (e.g. after a failed pair in #321) relies on
  a **fresh analyzer per scanner entry**, which [`CameraPreview`](camera-preview.md) provides (#334)
  via `remember { QrCodeAnalyzer(...) }` keyed to the composition.
- **`MlKitQrFrameDecoder` is not unit-tested** — it needs a device-decoded frame. The decode/debounce
  *logic* is fully JVM-tested through the injected `FrameDecoder` seam; only the ML Kit bridge is
  device-only.
- **First-frame decode latency** with the bundled model is now measurable against the live camera
  (#334). If the first scan feels slow on-device, an eager `getClient` warm-up is a cheap follow-up —
  not done this far.

## Testing

- **`test/.../QrCodeAnalyzerTest.kt`** (plain JVM, `./gradlew test`) — drives `analyze()` with a
  `FakeImageProxy` (only `close()` real; other members throw) and an injected `FrameDecoder`, wiring
  `onQrDecoded` into a real `ScannerViewModel` to exercise the full seam (AC4). Covers: decode→surface
  +transition, repeated-same-payload-once, different-payload-ignored (debounce is per-scan not
  per-payload), null-frame-doesn't-consume-latch, and `analyze` always closes the frame.
- **`test/.../ScannerViewModelTest.kt`** — `qrDecoded_movesToDecodedCarryingPayload`.
- **`androidTest/.../ScannerScreenTest.kt`** — `decoded_rendersViewportUnchanged` proves the `Decoded`
  branch is a no-visible-change viewport render (scope guard).

## Related

- Issue: https://github.com/pyrycode/pyrycode-mobile/issues/333 · PR:
  https://github.com/pyrycode/pyrycode-mobile/pull/339 · Spec:
  `docs/specs/architecture/333-mlkit-qr-decode-pipeline.md`
- Ticket notes: [`codebase/333.md`](../codebase/333.md)
- Feature: [Scanner screen](scanner-screen.md) — the screen whose `when(state)` the `Decoded` state
  joins; the `ScannerViewModel`/`CameraError` foundation this decode core extends
- Feature: [Camera preview](camera-preview.md) — the #334 composable that instantiates and binds this
  analyzer to the live camera (background executor, fresh-per-entry, lifecycle)
- Ticket notes: [`codebase/334.md`](../codebase/334.md) — the live-camera wiring slice
- Upstream: #326 (stateful scanner + the `CameraError`/`Error` dormant-seam template mirrored here)
- Downstream: **#334** ✅ shipped (live CameraX preview, binds this analyzer unchanged, owns executor +
  lifecycle + re-scan reset), **#320** (parse the untrusted payload → `PairedServer`), **#321**
  (fingerprint / safety-number confirm gate). Server-side QR payload `Encode`/`Decode` analog: pyrycode
  #211/#212.
