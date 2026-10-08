package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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
        compact: Boolean = true,
        atBoundary: Boolean = false,
        rewrap: Boolean = false,
    ) {
        val messages =
            listOf(Role.User, Role.User, Role.Assistant, Role.Assistant).mapIndexed { index, role ->
                Message("message-$index", "s", role, "$content $index", Instant.parse("2026-10-07T00:00:00Z"), isStreaming = streaming)
            }
        var boundaryHeightPx = 0
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    val bodyStyle = MaterialTheme.typography.bodyMedium
                    val testBody =
                        if (atBoundary) {
                            val exactLineHeight =
                                with(LocalDensity.current) {
                                    // A single line gives an exact pixel height even at a fractional viewport density.
                                    // Stay below the integer to avoid floating-point round trips adding a pixel.
                                    boundaryHeightPx = 96.dp.roundToPx()
                                    (boundaryHeightPx - 16.dp.roundToPx() * 2 - 0.01f).toSp()
                                }
                            bodyStyle.copy(
                                lineHeight = exactLineHeight,
                                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            )
                        } else {
                            bodyStyle
                        }
                    MaterialTheme(typography = MaterialTheme.typography.copy(bodyMedium = testBody)) {
                        CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                            Surface(Modifier.width(width.dp).testTag("target-fixture")) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 30.dp)) {
                                    messages.forEach { message ->
                                        MessageBubble(
                                            message,
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
        }
        val fixture = rule.onNodeWithTag("target-fixture").getUnclippedBoundsInRoot()
        assertEquals("configured fixture width", width.toFloat(), fixture.width.value, 1f)
        repeat(messages.size) { index ->
            rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index].performScrollTo()
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
            if (compact) {
                assertEquals(c.right.value, r.left.value, 1f)
                assertEquals(c.top.value, r.top.value, 1f)
                assertEquals((c.left.value + c.right.value) / 2, (cg.left.value + cg.right.value) / 2, 1f)
                assertEquals((r.left.value + r.right.value) / 2, (rg.left.value + rg.right.value) / 2, 1f)
            } else {
                assertEquals(c.bottom.value, r.top.value, 1f)
                assertEquals(c.left.value, r.left.value, 1f)
            }
            listOf(c, r).forEach { target ->
                assertTrue("target inside screen", target.left >= 0.dp && target.right <= width.dp)
                assertTrue("target stays vertically inside screen", target.top >= fixture.top && target.bottom <= fixture.bottom)
                assertTrue("target outside bubble content", target.right <= bubble.left || target.left >= bubble.right)
            }
            if (rewrap) assertTrue("compact rewrap stays compact above 96dp", bubble.height > 96.dp)
            if (atBoundary) {
                assertEquals(96f, bubble.height.value, 1f)
                assertEquals(
                    "exact threshold in pixels",
                    boundaryHeightPx.toFloat(),
                    rule
                        .onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index]
                        .fetchSemanticsNode()
                        .boundsInRoot.height,
                    0.01f,
                )
            }
            val row = rule.onAllNodesWithTag("message-row")[index].getUnclippedBoundsInRoot()
            assertTrue("copy target stays inside its message row", c.top >= row.top)
            assertTrue("reply target stays inside its message row", r.bottom <= row.bottom)
            assertTrue("action targets fit the visible surface height", c.top >= bubble.top && r.bottom <= bubble.bottom)
            assertEquals(bubble.height.value, row.height.value, 1f)
            if (index > 0) {
                val precedingReply = rule.onAllNodesWithContentDescription("Reply to this message")[index - 1].getUnclippedBoundsInRoot()
                assertTrue("adjacent rows have separate action targets", c.top >= precedingReply.bottom)
                val precedingBubble = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[index - 1].getUnclippedBoundsInRoot()
                assertEquals(16f, (bubble.top - precedingBubble.bottom).value, 1f)
            }
            val cy = (cg.top.value + cg.bottom.value) / 2
            val ry = (rg.top.value + rg.bottom.value) / 2
            assertEquals(if (compact) 0f else 25f, ry - cy, 1f)
            assertEquals((bubble.top.value + bubble.bottom.value) / 2, (cy + ry) / 2, 1f)
            if (!compact) {
                assertEquals(12.5f, c.bottom.value - cy, 1f)
                assertEquals(12.5f, ry - r.top.value, 1f)
            }
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
        assertEquals(messages.flatMap { message -> List(4) { message.content } }, copies)
        assertEquals(messages.flatMap { message -> List(4) { message } }, replies)
        assertEquals(0, toggles)
    }

    @Test fun shortBubbleTargetsAt320dp() = verify(320, "Hi")

    @Test fun shortBubbleTargetsAt412dp() = verify(412, "Hi")

    @Test fun longBubbleTargetsAt320dp() = verify(320, "long content ".repeat(16), compact = false)

    @Test fun longBubbleTargetsAt412dp() = verify(412, "long content ".repeat(16), compact = false)

    @Test fun twoLineTargetsAt320dp() = verify(320, "Alpha beta gamma")

    @Test fun twoLineTargetsAt412dp() = verify(412, "Alpha beta gamma delta epsilon")

    @Test fun exactly96dpUsesStackedAt320dp() = verify(320, "Hi", compact = false, atBoundary = true)

    @Test fun exactly96dpUsesStackedAt412dp() = verify(412, "Hi", compact = false, atBoundary = true)

    @Test fun compactRewrapDoesNotReselectAt320dp() = verify(320, "word ".repeat(9), rewrap = true)

    @Test fun compactRewrapDoesNotReselectAt412dp() = verify(412, "word ".repeat(20), rewrap = true)

    @Test fun streamingTargetsAt412dp() = verify(412, "arrived", streaming = true)

    @Test fun streamingTargetsRemainAvailable() = verify(320, "arrived", streaming = true)
}
