package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
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
class MessageReplyTargetsTest {
    @get:Rule val rule = createComposeRule()
    private val replies = mutableListOf<Message>()
    private val copies = mutableListOf<String>()
    private var toggles = 0
    private val clipboard =
        object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) {
                copies += annotatedString.text
            }

            override fun getText(): AnnotatedString? = null

            override fun hasText() = false
        }

    private fun verify(
        width: Int,
        content: String,
        streaming: Boolean = false,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        Surface {
                            Column(Modifier.padding(vertical = 30.dp)) {
                                listOf(Role.User, Role.Assistant).forEach { role ->
                                    MessageBubble(
                                        Message(
                                            role.name,
                                            "s",
                                            role,
                                            content,
                                            Instant.parse("2026-10-07T00:00:00Z"),
                                            isStreaming = streaming,
                                        ),
                                        metaRowVisible = false,
                                        onToggleMetaRow = { toggles++ },
                                        onReply = { replies += it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        repeat(2) { index ->
            val copy = rule.onAllNodesWithContentDescription("Copy this message")[index]
            val reply = rule.onAllNodesWithContentDescription("Reply to this message")[index]
            assertEquals(androidx.compose.ui.semantics.Role.Button, reply.fetchSemanticsNode().config[SemanticsProperties.Role])
            val c = copy.getUnclippedBoundsInRoot()
            val r = reply.getUnclippedBoundsInRoot()
            val cg = rule.onAllNodesWithTag("message-copy-glyph", useUnmergedTree = true)[index].getUnclippedBoundsInRoot()
            val rg = rule.onAllNodesWithTag("message-reply-glyph", useUnmergedTree = true)[index].getUnclippedBoundsInRoot()
            val bubble = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index].getUnclippedBoundsInRoot()
            assertEquals(48f, c.width.value, 1f)
            assertEquals(48f, c.height.value, 1f)
            assertEquals(48f, r.width.value, 1f)
            assertEquals(48f, r.height.value, 1f)
            assertEquals(c.bottom.value, r.top.value, 1f)
            val row = rule.onAllNodesWithTag("message-row")[index].getUnclippedBoundsInRoot()
            assertTrue("copy target stays inside its message row", c.top >= row.top)
            assertTrue("reply target stays inside its message row", r.bottom <= row.bottom)
            if (index > 0) {
                val precedingReply = rule.onAllNodesWithContentDescription("Reply to this message")[index - 1].getUnclippedBoundsInRoot()
                assertTrue("adjacent rows have separate action targets", c.top >= precedingReply.bottom)
            }
            val cy = (cg.top.value + cg.bottom.value) / 2
            val ry = (rg.top.value + rg.bottom.value) / 2
            assertEquals(25f, ry - cy, 1f)
            assertEquals((bubble.top.value + bubble.bottom.value) / 2, (cy + ry) / 2, 1f)
            assertEquals(12.5f, c.bottom.value - cy, 1f)
            assertEquals(12.5f, ry - r.top.value, 1f)
            assertEquals(13f, rg.width.value, 1f)
            // Actual pointer events on both sides of the shared edge, then each outer boundary.
            copy.performTouchInput {
                click(Offset(center.x, bottom - 1f))
                click(Offset(center.x, 1f))
                click(Offset(1f, center.y))
                click(
                    Offset(
                        right - 1f,
                        center.y,
                    ),
                )
            }
            reply.performTouchInput {
                click(Offset(center.x, 1f))
                click(Offset(center.x, bottom - 1f))
                click(Offset(1f, center.y))
                click(
                    Offset(
                        right - 1f,
                        center.y,
                    ),
                )
            }
            assertEquals((index + 1) * 4, copies.size)
            assertEquals((index + 1) * 4, replies.size)
        }
        assertEquals(List(8) { content }, copies)
        assertEquals(List(8) { content }, replies.map { it.content })
        assertEquals(List(4) { Role.User } + List(4) { Role.Assistant }, replies.map { it.role })
        assertEquals(0, toggles)
    }

    @Test fun shortBubbleTargetsAt320dp() = verify(320, "Hi")

    @Test fun shortBubbleTargetsAt412dp() = verify(412, "Hi")

    @Test fun longBubbleTargetsAt320dp() = verify(320, "long content ".repeat(16))

    @Test fun longBubbleTargetsAt412dp() = verify(412, "long content ".repeat(16))

    @Test fun streamingTargetsRemainAvailable() = verify(320, "arrived", streaming = true)
}
