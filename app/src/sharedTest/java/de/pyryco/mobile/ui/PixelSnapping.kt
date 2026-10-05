package de.pyryco.mobile.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/**
 * Compose places every edge on a whole device pixel. Robolectric runs these tests at density 1, where a whole
 * dp is a whole pixel, so a Figma dp value comes back exactly. The emulator is a Pixel 2 at density 2.625,
 * where 28 dp is 73.5 px and lays out as 74 px, read back as 28.19 dp. Each laid-out edge can land up to
 * half a pixel off its dp position, so a size or a gap between two edges can be off by up to one pixel.
 */
private val displayDensity: Float
    get() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.resources.displayMetrics.density

/**
 * One device pixel in dp, the most a size or gap can drift. Zero at a whole density, where nothing rounds,
 * so the Robolectric run keeps its exact checks.
 */
internal fun pixelDp(): Float = displayDensity.let { if (it % 1f == 0f) 0f else 1f / it }

/** One device pixel in px under the same rule as [pixelDp]. */
internal fun pixelPx(): Float = if (pixelDp() == 0f) 0f else 1f

/**
 * [actual] is [expected] up to the pixel rounding described in this file. A position that adds up several
 * rounded sizes, such as a glyph centred in a target inset from the screen edge, passes the count as [pixels].
 */
internal fun assertDpEquals(
    expected: Dp,
    actual: Dp,
    message: String? = null,
    pixels: Int = 1,
) {
    assertEquals(message, expected.value, actual.value, pixels * pixelDp())
}

/** Each edge of [actual], in px, is within one device pixel of [expected]'s. */
internal fun assertRectEqualsWithinPixel(
    expected: Rect,
    actual: Rect,
) {
    val tolerance = pixelPx()
    val close =
        listOf(
            expected.left to actual.left,
            expected.top to actual.top,
            expected.right to actual.right,
            expected.bottom to actual.bottom,
        ).all { (a, b) -> kotlin.math.abs(a - b) <= tolerance }
    if (!close) throw AssertionError("expected:<$expected> but was:<$actual>, beyond one device pixel")
}
