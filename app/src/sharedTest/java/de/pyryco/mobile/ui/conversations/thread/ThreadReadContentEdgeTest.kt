package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.ui.conversations.components.BubbleVerticalPadding
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadReadContentEdgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun messageEdge_isInsideBubblePaddingWithAndWithoutMetadata() {
        val metadata = mutableStateOf(false)
        var bubble: Rect? = null
        var edge: Float? = null
        var padding = 0f
        compose.setContent {
            padding = with(LocalDensity.current) { BubbleVerticalPadding.toPx() }
            PyrycodeMobileTheme {
                MessageBubble(
                    message = message(Role.Assistant),
                    metaRowVisible = metadata.value,
                    onContentTrailingEdge = { _, bottom -> edge = bottom },
                )
            }
        }

        fun assertEdge() {
            val bounds = compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            bubble = bounds
            compose.runOnIdle { assertEquals(bounds.bottom - padding, requireNotNull(edge), 1f) }
        }
        compose.waitForIdle()
        assertEdge()
        val initial = requireNotNull(edge)
        compose.runOnIdle { metadata.value = true }
        compose.waitForIdle()
        assertEdge()
        compose.runOnIdle {
            assertTrue(requireNotNull(edge) > initial)
            assertTrue(requireNotNull(edge) < requireNotNull(bubble).bottom)
        }
    }

    @Test fun toolEdge_usesToolSurfaceIncludingExpandedContent() {
        var edge: Float? = null
        var row: Rect? = null
        compose.setContent {
            PyrycodeMobileTheme {
                Box(Modifier.onGloballyPositioned { row = it.boundsInWindow() }) {
                    MessageBubble(
                        message =
                            message(Role.Tool).copy(
                                toolCall =
                                    ToolCall(
                                        "Bash",
                                        "echo hello",
                                        "hello",
                                        inputFields =
                                            mapOf(
                                                "description" to "Run command",
                                            ),
                                    ),
                            ),
                        onContentTrailingEdge = { _, bottom -> edge = bottom },
                    )
                }
            }
        }

        fun assertEdge() {
            val tool = compose.onNodeWithTag(TOOL_ROW_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                assertEquals(tool.bottom, requireNotNull(edge), 1f)
                assertTrue("whole lazy row includes trailing space", requireNotNull(row).bottom > requireNotNull(edge))
            }
        }
        compose.waitForIdle()
        assertEdge()
        val collapsed = requireNotNull(edge)
        compose.onNodeWithTag(TOOL_ROW_TAG).performClick()
        compose.waitForIdle()
        assertEdge()
        compose.runOnIdle { assertTrue(requireNotNull(edge) > collapsed) }
    }

    private fun message(role: Role) = Message("m", "", role, "Readable content", Instant.parse("2026-10-07T00:00:00Z"), isStreaming = false)
}
