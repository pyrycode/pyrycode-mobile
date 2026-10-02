package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1562: the message area starts at the header's rule (Figma `16:8`, `685:4337`). Scrolled rows draw up to
 * the rule, while a short or empty thread, the top pills and the input area keep their earlier positions.
 * The expected y values are the 412 × 892 reference frame's: [RULE_BOTTOM] is the Figma anchor the region now
 * starts at, and the others were measured on the layout before the change.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ThreadMessageAreaTopTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun scrolled_rows_are_drawn_up_to_the_header_rule() {
        val items =
            (1..12).map { n ->
                ThreadItem.MessageItem(
                    Message("m$n", "s1", Role.Assistant, longText(n), Instant.parse("2026-10-02T10:00:00Z"), isStreaming = false),
                )
            }
        setScreen(state(hasMessages = true, items = items))

        val region = rule.onNodeWithTag(MESSAGE_REGION)
        assertEquals("the message area starts at the rule's bottom edge", RULE_BOTTOM, region.getUnclippedBoundsInRoot().top.value, 1f)
        // Clipped bounds, in the forced frame's pixels: the list clips what scrolls past its top edge, so the
        // highest drawn message text shows where rows stop. Only text nodes count, so the list's own
        // full-region node cannot stand in for a row. Top content padding must not hold them below it.
        val highestDrawn =
            rule
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(MESSAGE_REGION)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .filter { it.height > 0f }
                .minOfOrNull { it.top } ?: error("no message rows drawn")
        assertEquals("rows are drawn up to the rule", region.fetchSemanticsNode().boundsInRoot.top, highestDrawn, 1f)
    }

    @Test
    fun an_empty_thread_and_the_input_area_keep_their_positions() {
        setScreen(state(hasMessages = false))

        val empty = rule.onNodeWithText(string(R.string.thread_empty_state)).getUnclippedBoundsInRoot()
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(INPUT_FIELD_TOP, field.top.value, 1f)
        assertEquals(EMPTY_TEXT_CENTRE, ((empty.top + empty.bottom) / 2).value, ONE_FRAME_PIXEL)
    }

    @Test
    fun the_top_pill_keeps_its_position() {
        setScreen(state(hasMessages = false), connectionState = ConnectionState.Offline)

        val pill = rule.onNodeWithTag("offline_retry_target").getUnclippedBoundsInRoot()
        assertEquals(TOP_PILL_TOP, pill.top.value, ONE_FRAME_PIXEL)
    }

    private fun setScreen(
        state: ThreadUiState,
        connectionState: ConnectionState = ConnectionState.Connected,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = connectionState,
                        onRetry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun state(
        hasMessages: Boolean,
        items: List<ThreadItem> = emptyList(),
    ): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = "pyrycode discord integration",
            isPromoted = true,
            hasMessages = hasMessages,
            items = items,
        )

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun longText(n: Int): String = (1..24).joinToString(" ") { "Message $n line $it of a reply long enough to wrap." }

    private companion object {
        const val MESSAGE_REGION = "thread-message-region"

        // Figma 533:1948's rule closes the bar at y 68–69; 621:3571, the message area, starts at y 69.
        const val RULE_BOTTOM = 69f

        // Pre-change positions in the reference frame. The pill's dp-exact top is 97, one frame pixel lower.
        const val EMPTY_TEXT_CENTRE = 423.9f
        const val INPUT_FIELD_TOP = 814.2f
        const val TOP_PILL_TOP = 95.5f

        // The forced frame scales density to about 0.774, where the old 16dp and 12dp gaps round to one pixel
        // fewer than the single 28dp inset. One such pixel is 1.29dp; at real densities the two agree.
        const val ONE_FRAME_PIXEL = 1.5f
    }
}
