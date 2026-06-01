package de.pyryco.mobile.ui.onboarding

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes a single camera frame to a QR raw value, or null if no QR is present. Synchronous. */
fun interface FrameDecoder {
    fun decode(image: ImageProxy): String?
}

/**
 * Production [FrameDecoder]: decodes QR codes via a bundled ML Kit barcode scanner.
 *
 * Not unit-tested — it needs a device-decoded frame; tests inject a fake [FrameDecoder] instead.
 * [decode] blocks on the detection `Task`, so it MUST run off the main thread — #334 binds this
 * analyzer to a background executor via `ImageAnalysis.setAnalyzer`. A failed or empty decode is the
 * normal "no QR in this frame" case: it returns `null` and logs nothing (AC5 — the payload is never
 * logged).
 */
class MlKitQrFrameDecoder : FrameDecoder {
    private val scanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )

    @ExperimentalGetImage
    override fun decode(image: ImageProxy): String? {
        val mediaImage = image.image ?: return null
        return try {
            val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
            Tasks
                .await(scanner.process(input))
                .firstOrNull { it.rawValue != null }
                ?.rawValue
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * CameraX [ImageAnalysis.Analyzer] that surfaces a decoded QR payload **exactly once per scan**.
 *
 * The seam that makes this JVM-testable is the injected [decoder]: production decodes via ML Kit;
 * tests pass a fake returning canned payloads, so `analyze` runs on a plain test thread with no
 * physical camera. The once-per-scan debounce (AC2) is the lock-free [hasSurfaced] latch — the first
 * successful decode flips it and fires [onQrDecoded]; every later frame (same or different payload) is
 * ignored for this instance's lifetime. Re-scan after a failed pair is a fresh analyzer per scanner
 * entry, owned by the live-camera wiring slice (#334).
 *
 * [onQrDecoded] feeds the ViewModel (`onEvent(QrDecoded(raw))`) in #334; the analyzer is not
 * instantiated in production until then — like #326's dormant `CameraError` seam.
 */
class QrCodeAnalyzer(
    private val onQrDecoded: (String) -> Unit,
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
            // CameraX will not deliver the next frame until the ImageProxy is closed.
            image.close()
        }
    }
}
