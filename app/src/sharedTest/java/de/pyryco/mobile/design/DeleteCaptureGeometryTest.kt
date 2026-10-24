package de.pyryco.mobile.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteCaptureGeometryTest {
    private val screen = Rect(0f, 0f, 412f, 892f)

    private fun insets(
        top: Int,
        bottom: Int,
        cutout: Insets = Insets.NONE,
    ): WindowInsetsCompat =
        WindowInsetsCompat
            .Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
            .build()

    private fun surface(top: Float) = Rect(48f, top, 364f, top + 220f)

    @Test fun symmetricBarsRetainTheReferenceOffset() {
        val viewport = usableDialogViewport(screen, insets(24, 24))
        assertEquals(Rect(0f, 24f, 412f, 868f), viewport)
        assertDeleteCentered(surface(336f), viewport, 1f)
        assertEquals(312f, surface(336f).top - viewport.top, 0f)
    }

    @Test fun asymmetricBarsMoveTheScreenCenterWithoutKeepingTheOldOffset() {
        val viewport = usableDialogViewport(screen, insets(49, 24))
        assertEquals(Rect(0f, 49f, 412f, 868f), viewport)
        assertDeleteCentered(surface(348.5f), viewport, 1f)
        assertEquals(299.5f, surface(348.5f).top - viewport.top, 0f)
    }

    @Test fun misplacedSurfaceIsRejectedOnEitherSideOfTolerance() {
        val viewport = usableDialogViewport(screen, insets(49, 24))
        for (top in listOf(336f, 361f, 346.4f, 350.6f)) {
            assertThrows(AssertionError::class.java) { assertDeleteCentered(surface(top), viewport, 1f) }
        }
        assertDeleteCentered(surface(346.5f), viewport, 1f)
        assertDeleteCentered(surface(350.5f), viewport, 1f)
    }

    @Test fun cutoutOverlapUsesEachEdgeOnce() {
        val viewport = usableDialogViewport(screen, insets(49, 24, Insets.of(8, 49, 4, 30)))
        assertEquals(Rect(8f, 49f, 408f, 862f), viewport)
        assertDeleteCentered(surface(345.5f), viewport, 1f)
    }

    @Test fun cutoutLargerThanStatusBarDefinesTheTopEdge() {
        val viewport = usableDialogViewport(screen, insets(24, 24, Insets.of(0, 49, 0, 0)))
        assertEquals(Rect(0f, 49f, 412f, 868f), viewport)
        assertDeleteCentered(surface(348.5f), viewport, 1f)
    }

    @Test fun effectiveSyntheticBarsKeepGeometryWithoutClaimingPixels() {
        // Mirrors DesignCapture's effective ATD insets; no framebuffer or real-bar claim.
        val incoming = WindowInsetsCompat.Builder().build()
        val effective =
            WindowInsetsCompat
                .Builder(incoming)
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .build()
        assertEquals(Rect(0f, 24f, 412f, 868f), usableDialogViewport(screen, effective))
        assertDeleteCentered(surface(336f), usableDialogViewport(screen, effective), 1f)
    }

    @Test fun screenOriginAndDensityApplyToBothMeasurementsAndTolerance() {
        val translatedScreen = Rect(100f, 200f, 924f, 1984f)
        val viewport = usableDialogViewport(translatedScreen, insets(98, 48))
        assertEquals(Rect(100f, 298f, 924f, 1936f), viewport)
        val measured = Rect(196f, 897f, 828f, 1337f)
        assertDeleteCentered(measured.translate(Offset(0f, 4f)), viewport, 2f)
        assertThrows(AssertionError::class.java) {
            assertDeleteCentered(measured.translate(Offset(0f, 4.1f)), viewport, 2f)
        }
    }
}
