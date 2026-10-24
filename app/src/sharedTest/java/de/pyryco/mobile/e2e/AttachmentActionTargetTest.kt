package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
import de.pyryco.mobile.ui.conversations.components.MESSAGE_ATTACHMENT_FILE_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A composed, clickable restored offer can still sit behind the thread's pointer-intercepting header. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentActionTargetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun restoredDeferredOffer_behindHeader_receivesOneOpenRequest() = probe(AttachmentAction.OPEN)

    @Test
    fun restoredDeferredOffer_behindComposer_receivesOneSaveRequest() = probe(AttachmentAction.SAVE)

    private fun probe(action: AttachmentAction) {
        val attachment = MessageAttachment("offered-file", "offer.txt")
        val requested = mutableListOf<AttachmentAction>()
        val items =
            (0 until 30).map { index ->
                ThreadItem.MessageItem(
                    Message(
                        id = "message-$index",
                        sessionId = "session",
                        role = Role.Assistant,
                        content = "Message $index",
                        timestamp = Instant.parse("2026-10-24T00:00:00Z"),
                        isStreaming = false,
                        attachments = if (index == 15) listOf(attachment) else emptyList(),
                    ),
                )
            }
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState(conversationId = "restored", displayName = "Files", hasMessages = true, items = items),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onRequestAttachment = { _, action -> requested += action },
                )
            }
        }
        val matcher = hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG) and hasText("offer.txt") and hasClickAction()
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(matcher)
        val header = compose.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
        val before = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val controlCenter = if (action == AttachmentAction.OPEN) header.center.y else composer.top + 20f
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, controlCenter - before.center.y) }
        val obscured = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "control must begin behind chrome: $obscured / $header / $composer",
            obscured.center.y < header.bottom || obscured.center.y > composer.top,
        )
        assertTrue("the offer has not been fetched", requested.isEmpty())

        val target = compose.attachmentActionTarget(matcher)
        if (action == AttachmentAction.OPEN) target.performClick() else target.performTouchInput { longClick() }

        compose.runOnIdle { assertEquals("one physical gesture must request the file", listOf(action), requested) }
    }
}
