package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadActivityIndicatorVisualTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun shortReadingsUseTheInputStatusBandHeight() {
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.width(372.dp)) {
                        Box(Modifier.testTag("thinking")) { ThinkingIndicator(isThinking = true) }
                        Box(Modifier.testTag("retry")) { ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown) }
                        Box(Modifier.testTag("compacting")) { CompactingIndicator(isCompacting = true) }
                        Box(Modifier.testTag("reset")) {
                            ResettingIndicator(status = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Pending))
                        }
                        Box(Modifier.testTag("outcome")) {
                            TurnOutcomeIndicator(
                                report = TurnOutcomeReport(TurnOutcomeReport.Kind.Interrupted, emptyList(), null),
                                agent = ConversationAgent.Claude,
                            )
                        }
                    }
                }
            }
        }

        listOf("thinking", "retry", "compacting", "reset", "outcome").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertHeightIsEqualTo(24.dp)
        }
    }

    @Test
    fun thinkingTextStartsAfterTheFigmaGlyphAndGap() {
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Surface {
                    ThinkingIndicator(isThinking = true, modifier = Modifier.width(372.dp))
                }
            }
        }

        composeRule.onNodeWithText("Thinking…", useUnmergedTree = true).assertLeftPositionInRootIsEqualTo(38.dp)
    }

    @Test
    fun pulsingGlyphRetainsItsVisibleSlotAcrossFrames() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThinkingIndicator(isThinking = true, modifier = Modifier.width(372.dp))
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val glyph = composeRule.onNodeWithTag("thinking_glyph", useUnmergedTree = true)
        val first = glyph.getUnclippedBoundsInRoot()
        assertEquals(14.dp, first.right - first.left)
        assertEquals(16.dp, first.bottom - first.top)

        composeRule.mainClock.advanceTimeBy(450)
        assertEquals(first, glyph.getUnclippedBoundsInRoot())
        composeRule.mainClock.advanceTimeBy(450)
        assertEquals(first, glyph.getUnclippedBoundsInRoot())
    }

    @Test
    fun retryProgressKeepsAVisibleArcAcrossAnimationFrames() {
        composeRule.mainClock.autoAdvance = false
        var view: View? = null
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Surface {
                    view = LocalView.current
                    Box(Modifier.testTag("retry_frame")) {
                        ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown, modifier = Modifier.width(372.dp))
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val visiblePixels = mutableListOf<Int>()
        repeat(8) {
            val bounds = composeRule.onNodeWithTag("retry_frame").fetchSemanticsNode().boundsInRoot
            val bitmap =
                composeRule.runOnIdle {
                    val root = checkNotNull(view)
                    Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
                }
            val visible =
                (4 until 20).sumOf { y ->
                    (16 until 32).count { x ->
                        val pixel = bitmap.getPixel(bounds.left.toInt() + x, bounds.top.toInt() + y)
                        Color.blue(pixel) - Color.red(pixel) > 40
                    }
                }
            bitmap.recycle()
            visiblePixels += visible
            composeRule.mainClock.advanceTimeBy(125)
        }
        assertTrue("retry spinner arc changes visible size: $visiblePixels", visiblePixels.max() - visiblePixels.min() <= 8)
    }
}
