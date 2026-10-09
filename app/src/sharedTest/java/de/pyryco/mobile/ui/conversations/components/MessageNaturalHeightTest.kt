package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MessageNaturalHeightTest {
    @get:Rule val rule = createComposeRule()

    private fun verify(
        width: Int,
        content: String,
        streaming: Boolean = false,
        attachment: Boolean = false,
    ) {
        var visible by mutableStateOf(false)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    Surface {
                        Column {
                            listOf(Role.User, Role.Assistant).forEach { role ->
                                MessageBubble(
                                    Message(
                                        role.name,
                                        "s",
                                        role,
                                        content,
                                        Instant.parse("2026-10-07T00:00:00Z"),
                                        isStreaming = streaming,
                                        attachments =
                                            if (attachment) {
                                                listOf(
                                                    MessageAttachment("a", "notes.txt", "text/plain"),
                                                )
                                            } else {
                                                emptyList()
                                            },
                                    ),
                                    metaRowVisible = visible,
                                    threadOpenedAt = Instant.parse("2026-10-08T00:00:00Z"),
                                )
                            }
                        }
                    }
                }
            }
        }

        fun assertNaturalHeight() {
            repeat(2) { index ->
                val bubble = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index].getUnclippedBoundsInRoot()
                val pixelBubble = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index].fetchSemanticsNode().boundsInRoot
                val text =
                    rule
                        .onAllNodes(
                            hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes()
                        .map {
                            // Empty Text has zero width, but its natural line height still counts.
                            val coordinates = it.layoutInfo.coordinates
                            Rect(coordinates.positionInRoot(), coordinates.size.toSize())
                        }.filter { bounds ->
                            // Convert through the test's configured density using the bubble's pixel bounds.
                            bounds.top >= pixelBubble.top && bounds.bottom <= pixelBubble.bottom
                        }
                val scale = pixelBubble.height / bubble.height.value
                if (!attachment) {
                    val bodyHeight = if (text.isEmpty()) 0f else (text.maxOf { it.bottom } - text.minOf { it.top }) / scale
                    // The empty finalized markdown wrapper contributes only the existing gap when
                    // the timestamp is visible; it supplies no body line or action-driven floor.
                    val emptyBodyGap = if (index == 1 && content.isBlank() && visible) 12f else 0f
                    if (!visible && content.startsWith("Alpha")) assertTrue("fixture renders two lines", bodyHeight in 24f..45f)
                    assertEquals("content plus existing padding", bodyHeight + emptyBodyGap + 32f, bubble.height.value, 1.5f)
                } else {
                    // The existing file row is 60dp; attachment-only must not acquire a 96dp floor.
                    val metaHeight = if (visible) text.last().height / scale + 12f else 0f
                    assertEquals(92f + metaHeight, bubble.height.value, 1.5f)
                }
                val row = rule.onAllNodesWithTag("message-row")[index].getUnclippedBoundsInRoot()
                assertEquals("actions add no row height", bubble.height.value, row.height.value, 1f)
            }
            val bubbles = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)
            assertEquals(16f, (bubbles[1].getUnclippedBoundsInRoot().top - bubbles[0].getUnclippedBoundsInRoot().bottom).value, 1f)
            val actionCount = if (content.isBlank() && !attachment && !streaming && !visible) 1 else 2
            assertEquals(
                "only surfaces that fit 48dp targets expose copy",
                actionCount,
                rule.onAllNodesWithContentDescription("Copy this message").fetchSemanticsNodes().size,
            )
            assertEquals(
                "only surfaces that fit 48dp targets expose reply",
                actionCount,
                rule.onAllNodesWithContentDescription("Reply to this message").fetchSemanticsNodes().size,
            )
        }
        assertNaturalHeight()
        if (!streaming) {
            rule.runOnIdle { visible = true }
            assertNaturalHeight()
            rule.runOnIdle { visible = false }
            assertNaturalHeight()
        }
    }

    @Test fun oneLineAt320dp() = verify(320, "Hi")

    @Test fun oneLineAt412dp() = verify(412, "Hi")

    @Test fun twoLinesAt320dp() = verify(320, "Alpha beta gamma")

    @Test fun twoLinesAt412dp() = verify(412, "Alpha beta gamma delta epsilon")

    @Test fun streamingAt320dp() = verify(320, "Hi", streaming = true)

    @Test fun streamingAt412dp() = verify(412, "Hi", streaming = true)

    @Test fun attachmentOnlyAt320dp() = verify(320, "", attachment = true)

    @Test fun attachmentOnlyAt412dp() = verify(412, "", attachment = true)

    @Test fun emptyBodyAt320dp() = verify(320, "")

    @Test fun emptyBodyAt412dp() = verify(412, "")

    @Test fun whitespaceBodyAt320dp() = verify(320, " \t ")

    @Test fun whitespaceBodyAt412dp() = verify(412, " \t ")
}
