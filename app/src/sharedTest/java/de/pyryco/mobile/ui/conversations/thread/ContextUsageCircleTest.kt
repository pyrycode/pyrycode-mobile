package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.warning
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Samples actual drawing, so a clockwise arc or recoloured track cannot pass on semantics alone. */
@Config(qualifiers = "w960dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ContextUsageCircleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun boundaries_andUpdates_drawCounterclockwiseArcOverConstantTrack() {
        var percent by mutableStateOf<Int?>(null)
        var view: View? = null
        var track = Color.Unspecified
        var primary = Color.Unspecified
        var warning = Color.Unspecified
        var error = Color.Unspecified
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                // Three pixels per dp makes the 2dp stroke measurable without edge antialiasing.
                CompositionLocalProvider(LocalDensity provides Density(3f)) {
                    view = LocalView.current
                    track = MaterialTheme.colorScheme.primaryContainer
                    primary = MaterialTheme.colorScheme.primary
                    warning = MaterialTheme.colorScheme.warning
                    error = MaterialTheme.colorScheme.error
                    ThreadComposerFooter(
                        runConfig = ThreadRunConfig(contextPercent = percent),
                        onOpen = {},
                        onStatusClick = {},
                        onAnchorChanged = { _, _ -> },
                    )
                }
            }
        }
        for (reading in listOf(null, 0, 69, 70, 84, 85, 100)) {
            composeRule.runOnIdle { percent = reading }
            val used =
                when (reading) {
                    70, 84 -> warning
                    85, 100 -> error
                    else -> primary
                }
            val description =
                when (reading) {
                    null -> "Context usage unavailable"
                    70, 84 -> "Context usage warning, $reading%"
                    85, 100 -> "Context usage high, $reading%"
                    else -> "Context usage $reading%"
                }
            val node = composeRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG)
            node.assertContentDescriptionEquals(description)
            val semantics = node.fetchSemanticsNode()
            assertTrue("the circle has no visible percentage text", semantics.config.getOrNull(SemanticsProperties.Text) == null)
            val bounds = semantics.boundsInRoot
            composeRule.runOnIdle {
                val root = checkNotNull(view)
                // captureToImage does not redraw reliably under Robolectric; draw the real view instead.
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val radius = bounds.width / 2f - 3f
                val sweep = (reading ?: 0) * 3.6f
                for (angle in 5 until 360 step 10) {
                    if (abs(angle - sweep) < 5f) continue
                    // Angles increase counterclockwise from twelve o'clock.
                    val radians = Math.toRadians((-90 - angle).toDouble())
                    val x = (bounds.center.x + radius * cos(radians)).roundToInt()
                    val y = (bounds.center.y + radius * sin(radians)).roundToInt()
                    val expected = if (angle < sweep) used else track
                    val actual = Color(bitmap.getPixel(x, y))
                    assertTrue("$reading% at $angle degrees: $actual expected $expected", actual.near(expected))
                }
                val center = Color(bitmap.getPixel(bounds.center.x.roundToInt(), bounds.center.y.roundToInt()))
                val insideStroke = Color(bitmap.getPixel(bounds.center.x.roundToInt(), (bounds.center.y - 4f * 3f).roundToInt()))
                assertTrue("ring must leave its centre empty", insideStroke.near(center))
                // At nine o'clock the 2dp stroke spans radii 5.5–7.5dp inside the 15dp box.
                for (distance in listOf(5, 6, 7, 8)) {
                    val pixel = Color(bitmap.getPixel((bounds.center.x - distance * 3f).roundToInt(), bounds.center.y.roundToInt()))
                    val expected =
                        if (distance == 6 || distance == 7) {
                            if ((reading ?: 0) > 25) used else track
                        } else {
                            center
                        }
                    assertTrue("$reading% stroke at radius $distance dp", pixel.near(expected))
                }
                bitmap.recycle()
            }
        }
    }

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) < 0.08f && abs(green - other.green) < 0.08f && abs(blue - other.blue) < 0.08f
}
