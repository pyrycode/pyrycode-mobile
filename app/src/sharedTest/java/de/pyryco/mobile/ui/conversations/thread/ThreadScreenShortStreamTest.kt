package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1509: a stream shorter than the viewport starts at the top of the message region, as Figma `640:2646` and
 * `639:2242` draw it, with the empty space below it rather than above.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenShortStreamTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_one_message_thread_starts_at_the_top_of_the_message_region() {
        setScreen(
            ThreadUiState(
                conversationId = "c1",
                displayName = "Release notes",
                isPromoted = true,
                hasMessages = true,
                items =
                    listOf(
                        ThreadItem.MessageItem(
                            Message("m1", "s1", Role.Assistant, MESSAGE, Instant.parse("2026-10-02T10:00:00Z"), isStreaming = false),
                        ),
                    ),
            ),
            ModalUiState.Hidden,
        )

        assertTopAnchored(composeRule.onNodeWithText(MESSAGE))
    }

    @Test
    fun a_thread_holding_only_a_pending_permission_request_starts_at_the_top_of_the_message_region() {
        setScreen(
            ThreadUiState(conversationId = "c1", displayName = "Release notes", isPromoted = true, hasMessages = false),
            ModalUiState.Open(
                modalId = "m1",
                modalClass = "permission",
                title = "Permission required",
                prompt = "claude wants to run ls",
                options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
                defaultOptionId = "reject_once",
            ),
        )

        assertTopAnchored(composeRule.onNodeWithTag("permission-request-card"))
    }

    private fun setScreen(
        state: ThreadUiState,
        modalState: ModalUiState,
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = modalState,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertTopAnchored(node: SemanticsNodeInteraction) {
        val region = composeRule.onNodeWithTag("thread-message-region").bounds()
        val content = node.bounds()
        val minTopGap = with(composeRule.density) { MESSAGE_AREA_TOP_INSET.toPx() }
        val maxTopGap = with(composeRule.density) { MAX_TOP_GAP.toPx() }
        val topGap = content.top - region.top
        val bottomGap = region.bottom - content.bottom
        assertTrue("content starts $topGap px below the region top, outside $minTopGap..$maxTopGap", topGap in minTopGap..maxTopGap)
        assertTrue("the empty space ($bottomGap px) lies below the content, not above ($topGap px)", bottomGap > topGap)
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private companion object {
        const val MESSAGE = "Let's write the release notes."

        // The list's top inset (#1562): the stream starts below it, where it sat before the region grew up to
        // the header's rule.
        val MESSAGE_AREA_TOP_INSET = 69.dp + 28.dp

        // The inset plus the row's own gutter and bubble padding; a bottom-anchored short stream sits hundreds
        // of dp lower.
        val MAX_TOP_GAP = MESSAGE_AREA_TOP_INSET + 32.dp
    }
}
