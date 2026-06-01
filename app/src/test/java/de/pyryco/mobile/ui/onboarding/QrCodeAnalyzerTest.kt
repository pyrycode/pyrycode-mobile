package de.pyryco.mobile.ui.onboarding

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Exercises the decode→surface→debounce core through the analyzer→ViewModel seam with fake frames —
// no physical camera (AC4). onQrDecoded is wired to a real ScannerViewModel, the same indirection
// #334 uses against a live camera.
class QrCodeAnalyzerTest {
    // Minimal ImageProxy fake: only close() is exercised (recorded for the close-contract test). The
    // injected FrameDecoder ignores the frame, so every other member is unreachable here.
    private class FakeImageProxy : ImageProxy {
        var closed = false
            private set

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = throw UnsupportedOperationException("unused")

        override fun setCropRect(rect: Rect?): Unit = throw UnsupportedOperationException("unused")

        override fun getFormat(): Int = throw UnsupportedOperationException("unused")

        override fun getHeight(): Int = throw UnsupportedOperationException("unused")

        override fun getWidth(): Int = throw UnsupportedOperationException("unused")

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = throw UnsupportedOperationException("unused")

        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("unused")

        @ExperimentalGetImage
        override fun getImage(): Image? = throw UnsupportedOperationException("unused")
    }

    // Returns each scripted value in turn (then sticks on the last), so a multi-frame scan is canned.
    private class QueueFrameDecoder(
        vararg values: String?,
    ) : FrameDecoder {
        private val queue = ArrayDeque(values.toList())
        private var last: String? = null

        override fun decode(image: ImageProxy): String? {
            if (queue.isNotEmpty()) last = queue.removeFirst()
            return last
        }
    }

    @Test
    fun decode_surfacesPayloadOnceAndTransitionsToDecoded() {
        val vm = ScannerViewModel()
        var callbackCount = 0
        val analyzer =
            QrCodeAnalyzer(
                onQrDecoded = { payload ->
                    callbackCount++
                    vm.onEvent(ScannerEvent.QrDecoded(payload))
                },
                decoder = FrameDecoder { "qr-payload" },
            )

        analyzer.analyze(FakeImageProxy())

        assertEquals(ScannerUiState.Decoded("qr-payload"), vm.state.value)
        assertEquals(1, callbackCount)
    }

    @Test
    fun repeatedFramesSamePayload_surfaceExactlyOnce() {
        val vm = ScannerViewModel()
        var callbackCount = 0
        val analyzer =
            QrCodeAnalyzer(
                onQrDecoded = { payload ->
                    callbackCount++
                    vm.onEvent(ScannerEvent.QrDecoded(payload))
                },
                decoder = FrameDecoder { "p" },
            )

        analyzer.analyze(FakeImageProxy())
        analyzer.analyze(FakeImageProxy())
        analyzer.analyze(FakeImageProxy())

        assertEquals(1, callbackCount)
        assertEquals(ScannerUiState.Decoded("p"), vm.state.value)
    }

    @Test
    fun differentPayloadAfterFirstScan_isIgnored() {
        val vm = ScannerViewModel()
        var callbackCount = 0
        val analyzer =
            QrCodeAnalyzer(
                onQrDecoded = { payload ->
                    callbackCount++
                    vm.onEvent(ScannerEvent.QrDecoded(payload))
                },
                decoder = QueueFrameDecoder("first", "second"),
            )

        analyzer.analyze(FakeImageProxy())
        analyzer.analyze(FakeImageProxy())

        assertEquals(1, callbackCount)
        assertEquals(ScannerUiState.Decoded("first"), vm.state.value)
    }

    @Test
    fun nullFrameDoesNotConsumeLatch_thenLaterQrSurfaces() {
        val vm = ScannerViewModel()
        var callbackCount = 0
        val analyzer =
            QrCodeAnalyzer(
                onQrDecoded = { payload ->
                    callbackCount++
                    vm.onEvent(ScannerEvent.QrDecoded(payload))
                },
                decoder = QueueFrameDecoder(null, "late"),
            )

        analyzer.analyze(FakeImageProxy())
        assertEquals(0, callbackCount)
        assertEquals(ScannerUiState.PermissionRequesting, vm.state.value)

        analyzer.analyze(FakeImageProxy())
        assertEquals(1, callbackCount)
        assertEquals(ScannerUiState.Decoded("late"), vm.state.value)
    }

    @Test
    fun analyze_closesFrameEvenWhenNoQrDecoded() {
        val analyzer = QrCodeAnalyzer(onQrDecoded = {}, decoder = FrameDecoder { null })
        val frame = FakeImageProxy()

        analyzer.analyze(frame)

        assertTrue(frame.closed)
    }
}
