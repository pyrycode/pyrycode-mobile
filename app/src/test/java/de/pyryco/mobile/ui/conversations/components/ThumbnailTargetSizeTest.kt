package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #984: the decode size an attacker-shaped image header is allowed to ask for. */
class ThumbnailTargetSizeTest {
    @Test
    fun largePhoto_scalesSoItsShortSideCoversTheSlot() {
        assertEquals(IntSize(640, 480), thumbnailTargetSize(4000, 3000, 480))
        assertEquals(IntSize(480, 640), thumbnailTargetSize(3000, 4000, 480))
    }

    @Test
    fun smallImage_isNeverUpscaled() {
        assertEquals(IntSize(100, 80), thumbnailTargetSize(100, 80, 480))
    }

    @Test
    fun extremeAspectRatio_capsTheLongSide_soTheDecodeStaysSmall() {
        // Scaling by the short side alone would keep this at full width: 10^6 × 160 × 4 bytes.
        val size = thumbnailTargetSize(1_000_000, 160, 480)
        assertEquals(IntSize(1920, 1), size)
    }

    @Test
    fun nonPositiveDimensions_areRefused() {
        assertNull(thumbnailTargetSize(0, 10, 480))
        assertNull(thumbnailTargetSize(10, -1, 480))
        assertNull(thumbnailTargetSize(10, 10, 0))
    }
}
