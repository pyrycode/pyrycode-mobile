package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MessageBubbleSelectionTest
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ThreadChromeTest {
    @get:Rule val rule = createComposeRule()

    private var draft by mutableStateOf("")
    private var attachments by mutableStateOf(emptyList<PendingAttachment>())
    private var extraItems by mutableStateOf(emptyList<ThreadItem>())

    @Test fun invisible_info_banners_preserve_the_newest_text_rest_gap() {
        screen()
        assertRestGap()
        rule.runOnIdle {
            extraItems = listOf(ThreadItem.Banner(BannerLevel.Info, "Invisible notice", false, Instant.parse("2026-10-04T10:01:00Z")))
        }
        assertRestGap()
        rule.runOnIdle { extraItems = emptyList() }
        assertRestGap()
    }

    @Test fun newest_text_surface_rests_12dp_above_status_after_each_composer_resize() {
        screen()
        assertRestGap()
        val initial = composerTop()
        rule.runOnIdle {
            attachments = listOf(PendingAttachment(1, "content://test/file", "file.pdf", "application/pdf", 10))
        }
        assertRestGap()
        assertTrue("pending files raise the chrome", composerTop() < initial)
        val attachmentTop = composerTop()
        rule.runOnIdle { draft = (1..5).joinToString("\n") { "Draft line $it" } }
        assertRestGap()
        assertTrue("multiline draft raises the chrome", composerTop() < attachmentTop)
        rule.runOnIdle {
            attachments = emptyList()
            draft = ""
        }
        assertRestGap()
        assertEquals(initial, composerTop(), 1.5f)
    }

    @Test fun chrome_background_taps_do_not_open_message_details() {
        screen()
        // Bounds, scrolls and taps are in px; the offsets below are dp, so they land alike at any density.
        val px = rule.density.density
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        rule.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 48f * px) }
        rule.waitForIdle()
        val band = rule.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
        val header = rule.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
        val bubbleBounds =
            rule
                .onAllNodes(
                    hasTestTag("message-bubble"),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .map { it.boundsInRoot }
        val touch = Offset(40f * px, band.top + 8f * px)
        assertTrue("a row must actually underlap the composer", bubbleBounds.any { it.contains(touch) })
        rule.onNodeWithTag("thread-message-region").performTouchInput {
            click(touch)
            click(Offset(40f * px, header.bottom - 2f * px))
        }
        rule.onAllNodesWithContentDescription("Copy this message", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun short_thread_starts_28dp_below_the_measured_rule() {
        screen(count = 1)
        val bar = rule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        val bubble = bubble(1).getUnclippedBoundsInRoot()
        assertEquals(69f, (bar.bottom - bar.top).value, 1.5f)
        assertEquals(28f, (bubble.top - bar.bottom).value, 1.5f)
    }

    @Test fun pending_attachment_strip_accepts_a_gradual_pointer_swipe() {
        attachments = (1..20).map { PendingAttachment(it.toLong(), "content://test/file$it", "file$it.pdf", "application/pdf", 10) }
        screen()
        val strip = rule.onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG)
        val range = strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("the attachment strip must overflow", range.maxValue() > 0f)
        val before = range.value()
        strip.performTouchInput {
            val start = Offset(width * 0.75f, height * 0.7f)
            down(start)
            moveTo(start - Offset(2f, 0f), delayMillis = 16)
            moveTo(start - Offset(4f, 0f), delayMillis = 16)
            repeat(8) { step -> moveTo(start - Offset(4f + (step + 1) * 25f, 0f), delayMillis = 32) }
            up()
        }
        rule.waitForIdle()
        val after = strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("a real pointer drag must reach later attachments", after > before + 40f)
    }

    // Reuse the selection suite's no-op magnifier: Robolectric's popup has no native Surface.
    // This only replaces the visual loupe, leaving the real text selection recognizer active.
    @Config(shadows = [MessageBubbleSelectionTest.NoOpMagnifier::class])
    @Test
    fun composer_accepts_long_press_text_selection_and_drag() {
        draft = "alpha beta gamma delta"
        screen()
        val field = rule.onNode(hasSetTextAction())
        field.performTouchInput {
            val start = Offset(15f, centerY)
            down(start)
            advanceEventTime(600)
            moveTo(start + Offset(2f, 0f), delayMillis = 16)
            moveTo(start + Offset(4f, 0f), delayMillis = 16)
            moveTo(Offset(width - 15f, centerY), delayMillis = 200)
            up()
        }
        rule.waitForIdle()
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue("long press and drag must select more than the first word", selection.length > "alpha".length)
        assertEquals("selection leaves the draft intact", "alpha beta gamma delta", draft)
    }

    private fun assertRestGap() {
        rule.waitForIdle()
        val band = rule.onNodeWithTag("thread-status-band").getUnclippedBoundsInRoot()
        val bubble = bubble(30).getUnclippedBoundsInRoot()
        assertEquals("visible message to status band", 12f, (band.top - bubble.bottom).value, 1.5f)
    }

    private fun composerTop() =
        rule
            .onNodeWithTag("thread-composer")
            .getUnclippedBoundsInRoot()
            .top.value

    private fun bubble(n: Int) = rule.onNode(hasTestTag("message-bubble") and hasAnyDescendant(hasText(text(n))), useUnmergedTree = true)

    private fun screen(count: Int = 30) {
        val state =
            ThreadUiState(
                conversationId = "c",
                displayName = "Thread",
                hasMessages = true,
                items =
                    (1..count).map { n ->
                        ThreadItem.MessageItem(
                            Message("m$n", "s", Role.Assistant, text(n), Instant.parse("2026-10-04T10:00:00Z"), isStreaming = false),
                        )
                    },
            )
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state.copy(items = state.items + extraItems),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        draft = draft,
                        onDraftChange = { draft = it },
                        attachments = attachments,
                    )
                }
            }
        }
    }

    private fun text(n: Int) = "Message $n: " + (1..8).joinToString(" ") { "a fixed reply with enough text to wrap" }
}
