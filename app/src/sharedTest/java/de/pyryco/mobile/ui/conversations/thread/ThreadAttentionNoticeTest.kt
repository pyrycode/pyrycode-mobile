package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadAttentionNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = HostConversationTarget("other host", "same/id")
    private var attention by mutableStateOf<ThreadAttention?>(ThreadAttention(1, target, "Other"))
    private var connection by mutableStateOf<ConnectionState>(ConnectionState.Connected)
    private var clicked: HostConversationTarget? = null
    private var taps = 0
    private var dismissals = 0
    private var view: View? = null

    private fun screen(usage: Boolean = false) {
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                ThreadScreen(
                    state = ThreadUiState("same/id", "Current"),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connection,
                    onRetry = {},
                    usageLimit = if (usage) UsageLimitReading("allowed_warning", "seven_day", 0, .8, null) else null,
                    onDismissUsageLimit = { dismissals++ },
                    mcpFailure = if (usage) "github" else null,
                    sessionError = if (usage) "session.blocked" else null,
                    attentionPill =
                        attention?.let { reading ->
                            {
                                ThreadAttentionNotice(reading) {
                                    clicked = it
                                    taps++
                                }
                            }
                        },
                )
            }
        }
    }

    @Test fun singleAndFinishedLabels_useExactTarget_andCountUsesList_withoutX() {
        screen()
        val waiting = compose.onNodeWithText("Other needs your answer")
        waiting.assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(target, clicked)
            assertEquals(1, taps)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.thread_notice_dismiss)).assertDoesNotExist()
        compose.runOnIdle { attention = ThreadAttention(0, target, "Other") }
        compose.onNodeWithText("Other finished").assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(target, clicked)
            assertEquals(2, taps)
            attention = ThreadAttention(3)
        }
        compose.onNodeWithText("3 conversations need you").assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(null, clicked)
            assertEquals(3, taps)
        }
        compose.onAllNodesWithTag("thread_attention_pill").assertCountEquals(1)
    }

    @Test fun attentionTargetContainsItsLabelAndDescription_forEveryVariant() {
        screen()

        fun assertTarget(label: String) {
            compose
                .onNode(hasTestTag("thread_attention_pill") and hasText(label) and hasContentDescription(label) and hasClickAction())
                .assertIsDisplayed()
            compose.onAllNodes(hasText(label)).assertCountEquals(1)
            compose.onAllNodes(hasContentDescription(label)).assertCountEquals(1)
        }
        assertTarget("Other needs your answer")
        compose.runOnIdle { attention = ThreadAttention(0, target, "Other") }
        assertTarget("Other finished")
        compose.runOnIdle { attention = ThreadAttention(2) }
        assertTarget("2 conversations need you")
    }

    @Test fun attentionIsFirstAboveUsageMcpConnectionAndSessionError_withTwelveDpGaps() {
        screen(usage = true)
        val first = compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val usage = compose.onNodeWithText("Nearly at usage limit - 7-day window").getUnclippedBoundsInRoot()
        assertEquals(12f, (usage.top - first.bottom).value, .5f)
        val mcp = compose.onNodeWithText("MCP server github failed").getUnclippedBoundsInRoot()
        val error = compose.onNodeWithText(context.getString(R.string.thread_session_blocked)).getUnclippedBoundsInRoot()
        assertTrue(first.top < mcp.top && mcp.top < error.top)
        compose.runOnIdle { connection = ConnectionState.Offline }
        val retry = compose.onNodeWithTag("offline_retry_target").getUnclippedBoundsInRoot()
        assertTrue(first.top < retry.top)
        compose.onAllNodesWithTag("thread_attention_pill").assertCountEquals(1)
    }

    @Test fun attentionBottomEdge_andUsageDismissTopEdge_routeToSeparateActions() {
        screen(usage = true)
        compose.onNodeWithTag("thread_attention_pill").performTouchInput { click(Offset(width / 2f, height - 1f)) }
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, dismissals)
        }
        compose
            .onNodeWithContentDescription(context.getString(R.string.thread_notice_dismiss))
            .performTouchInput { click(Offset(width / 2f, 1f)) }
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, dismissals)
        }
    }

    @Test fun shortWaiting_hasA48dpTarget_withExpandedBoundaryTaps() {
        assertExpandedTarget(ThreadAttention(1, target, "B"))
    }

    @Test fun shortFinished_hasA48dpTarget_withExpandedBoundaryTaps() {
        assertExpandedTarget(ThreadAttention(0, target, "B"))
    }

    @Test fun shortCount_hasA48dpTarget_withExpandedBoundaryTaps() {
        assertExpandedTarget(ThreadAttention(2))
    }

    private fun assertExpandedTarget(reading: ThreadAttention) {
        attention = reading
        screen(usage = true)
        val pill = compose.onNodeWithTag("thread_attention_pill")
        val touch = pill.fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("attention touch bounds $touch must be at least 48dp high", touch.height >= minimum)
        assertTrue("attention touch bounds $touch must be at least 48dp wide", touch.width >= minimum)
        val dismiss = compose.onNodeWithContentDescription(context.getString(R.string.thread_notice_dismiss))
        val dismissTouch = dismiss.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("attention $touch must end before dismiss $dismissTouch", touch.bottom <= dismissTouch.top)

        val visible = compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val usage = compose.onNodeWithText("Nearly at usage limit - 7-day window").fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 12.dp.toPx() }
        assertEquals(gap, usage.top - visible.bottom, 1f)
        assertEquals(with(compose.density) { 24.dp.toPx() }, visible.height, 1f)
        assertTrue("target must extend above the drawn pill", touch.top < visible.top)
        val targetBounds = pill.fetchSemanticsNode().boundsInRoot
        pill.performTouchInput { click(Offset(touch.center.x - targetBounds.left, touch.top - targetBounds.top + 1f)) }
        pill.performTouchInput { click(Offset(touch.center.x - targetBounds.left, touch.bottom - targetBounds.top - 1f)) }
        compose.runOnIdle {
            assertEquals(reading.target, clicked)
            assertEquals(2, taps)
            assertEquals(0, dismissals)
        }

        val dismissBounds = dismiss.fetchSemanticsNode().boundsInRoot
        // Surface clips hit-testing outside the usage pill. Tap its top edge, above the X glyph,
        // to exercise the dismiss target's expansion inside that surface rather than its clip.
        dismiss.performTouchInput { click(Offset(dismissTouch.center.x - dismissBounds.left, usage.top - dismissBounds.top + 1f)) }
        compose.runOnIdle {
            assertEquals(2, taps)
            assertEquals(1, dismissals)
        }
    }

    @Test fun waitingAndFinishedMatchFigmaColors_bodySmallAndHugWidth() {
        screen()
        assertStyleAndContainer("Other needs your answer", Color(0xFFD8B85A), Color(0xFF3D3215))
        val waitingWidth = compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        compose.runOnIdle { attention = ThreadAttention(0, target, "Other") }
        assertStyleAndContainer("Other finished", Color(0xFF2FC038), Color(0xFF0F3313))
        assertTrue(
            compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).getUnclippedBoundsInRoot().width < waitingWidth,
        )
    }

    @Test fun hostileLongName_isPlainBoundedText_twoLinesWithEllipsis_insideThreadWidth() {
        attention = ThreadAttention(1, target, "\u0000\n<script>https://evil/\t" + "😀".repeat(100))
        screen()
        val expected = "<script>https://evil/" + "😀".repeat(59) + " needs your answer"
        val node = compose.onNodeWithText(expected, useUnmergedTree = true)
        node.assertIsDisplayed()
        val result = layout(expected)
        assertEquals(2, result.lineCount)
        assertTrue(result.isLineEllipsized(1))
        assertEquals(2, result.layoutInput.maxLines)
        val bounds = compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val thread = compose.onRoot().getUnclippedBoundsInRoot()
        // Both 20dp gutters must hold on Robolectric's 320dp viewport and wider device screens.
        assertTrue(bounds.left >= thread.left + 20.dp)
        assertTrue(bounds.right <= thread.right - 20.dp)
        node.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(target, clicked) }
    }

    @Test fun emptyAndUnavailableNames_fallBackToAppName() {
        attention = ThreadAttention(1, target, "\u0000\n \t")
        screen()
        val app = context.getString(R.string.app_name)
        compose.onNodeWithText("$app needs your answer").assertIsDisplayed()
        compose.runOnIdle { attention = ThreadAttention(0, target, null) }
        compose.onNodeWithText("$app finished").assertIsDisplayed()
    }

    private fun layout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertStyleAndContainer(
        label: String,
        text: Color,
        container: Color,
    ) {
        val style = layout(label).layoutInput.style
        assertEquals(text, style.color)
        assertEquals(12f, style.fontSize.value, .01f)
        assertEquals(TextAlign.End, style.textAlign)
        val bounds = compose.onNodeWithTag("thread_attention_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val inset = with(compose.density) { 2.dp.toPx() }
            val actual = Color(bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + inset).toInt()))
            assertEquals(container.red, actual.red, 1f / 255)
            assertEquals(container.green, actual.green, 1f / 255)
            assertEquals(container.blue, actual.blue, 1f / 255)
            System.getenv("THREAD_ATTENTION_CAPTURE_DIR")?.let { directory ->
                val output = File(directory, if (label.endsWith("finished")) "finished.png" else "waiting.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }
}
