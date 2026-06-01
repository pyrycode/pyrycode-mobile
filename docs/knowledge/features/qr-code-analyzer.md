# QR code analyzer

The **decode core** of the pairing scanner (#333): a CameraX `ImageAnalysis.Analyzer` that decodes a
camera frame to a QR string via ML Kit and surfaces it **exactly once per scan**, feeding the
[Scanner screen](scanner-screen.md)'s `ScannerViewModel` through a new `QrDecoded` event → `Decoded`
state. It is the success counterpart to #326's `CameraError`/`Error` failure seam.

**Dormant until #334.** Like #326's `CameraError` seam, the analyzer is built and unit-tested but
**not instantiated in production** this slice — the live-camera wiring slice (#334) constructs it,
binds it to a CameraX preview, and owns its lifecycle. It exists now so the decode/debounce logic is
covered on the JVM (`./gradlew test`) before any device is involved.

## What it does

- Decodes a single camera frame (`ImageProxy`) to a QR code's raw string payload, or `null` if no QR
  is present (the common per-frame case).
- Surfaces the decoded string **exactly once per scan**: repeated detections of the same in-flight
  scan — same payload or a different one — do not re-fire (debounced). One `QrCodeAnalyzer` instance
  surfaces one payload for its lifetime.
- On the first successful decode, invokes its `onQrDecoded: (String) -> Unit` callback, which (in
  #334) feeds `ScannerViewModel.onEvent(QrDecoded(raw))`, transitioning the scanner to
  `ScannerUiState.Decoded(payload)`.
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
- **Wiring (in #334, not this slice):** construct `QrCodeAnalyzer(onQrDecoded = { vm.onEvent(QrDecoded(it)) })`
  and bind it via `ImageAnalysis.setAnalyzer(backgroundExecutor, analyzer)`. **The executor MUST be a
  background executor** — `Tasks.await` in `MlKitQrFrameDecoder.decode` blocks and throws on the main
  thread. Re-scan after a failed pair is a **fresh analyzer per scanner entry** (the latch never
  resets); analyzer construction + CameraX lifecycle (incl. `BarcodeScanner.close()`) are #334's.

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

- **The latch never resets.** One `QrCodeAnalyzer` surfaces one payload for its lifetime — correct for
  a single scan. Re-scanning (e.g. after a failed pair in #321) means a fresh analyzer per scanner
  entry, owned by #334.
- **`MlKitQrFrameDecoder` is not unit-tested** — it needs a device-decoded frame. The decode/debounce
  *logic* is fully JVM-tested through the injected `FrameDecoder` seam; only the ML Kit bridge is
  device-only.
- **No live camera, preview, or production instantiation** this slice — exercised through the
  analyzer→ViewModel seam with fake frames only. First-frame decode latency with the bundled model is
  unmeasured (no live camera to measure against); revisit in #334.

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
- Upstream: #326 (stateful scanner + the `CameraError`/`Error` dormant-seam template mirrored here)
- Downstream: **#334** (live CameraX preview, binds this analyzer unchanged, owns executor + lifecycle
  + re-scan reset), **#320** (parse the untrusted payload → `PairedServer`), **#321** (fingerprint /
  safety-number confirm gate). Server-side QR payload `Encode`/`Decode` analog: pyrycode #211/#212.
