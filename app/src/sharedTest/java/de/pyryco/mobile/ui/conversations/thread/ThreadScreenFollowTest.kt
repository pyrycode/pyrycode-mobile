package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1314: following is derived from the list's position, holds while the reader is at the newest end, and
 * comes back on every accepted send. Every test seeds thirty rows so the list overflows the viewport (#777):
 * with a short list the measure pulls index 0 back in and a missed pin cannot show.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenFollowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun an_overscroll_at_the_newest_end_leaves_following_on_for_a_new_row_and_a_streamed_delta() {
        var state by mutableStateOf(threadState(rows(30)))
        setScreen { state }
        list().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", "Reply begins.", isStreaming = true)) }
        awaitStreamedText("Reply begins.")

        composeRule.runOnIdle { state = state.copy(items = rows(30) + message("reply", LONG_REPLY, isStreaming = true)) }
        composeRule.waitForIdle()
        awaitStreamedText("Reply ends.")
    }

    @Test
    fun while_following_a_tool_result_and_a_queued_row_stay_in_view() {
        var state by mutableStateOf(threadState(rows(30)))
        setScreen { state }

        composeRule.runOnIdle { state = state.copy(items = state.items + toolRow(ToolCallStatus.Running)) }
        composeRule.onNodeWithText(TOOL, useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(items = rows(30) + toolRow(ToolCallStatus.Done)) }
        composeRule.onNodeWithText(TOOL, useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(queuedMessages = listOf(queued())) }
        composeRule.onNodeWithText(QUEUED).assertIsDisplayed()
    }

    @Test
    fun while_not_following_no_row_tool_result_or_queued_row_moves_the_reader() {
        var state by mutableStateOf(threadState(rows(30)))
        setScreen { state }
        scrollAway()

        composeRule.runOnIdle { state = state.copy(items = state.items + toolRow(ToolCallStatus.Running)) }
        composeRule.runOnIdle { state = state.copy(items = rows(30) + toolRow(ToolCallStatus.Done)) }
        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", "Reply begins.", isStreaming = true)) }
        composeRule.runOnIdle { state = state.copy(queuedMessages = listOf(queued())) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        composeRule.onNodeWithText(TOOL, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(QUEUED).assertDoesNotExist()
    }

    /**
     * A finger resting at the newest end refuses the pin for a tool row that arrives mid-stream. The refusal
     * costs that one scroll: the next streamed delta, after the finger lifts, still brings the tool row in.
     */
    @Test
    fun a_scroll_refused_mid_stream_costs_one_scroll_and_later_growth_is_followed() {
        var state by mutableStateOf(threadState(rows(30) + message("reply", "Reply begins.", isStreaming = true)))
        setScreen { state }
        val list = list()
        list.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 150f)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reply begins.", substring = true, useUnmergedTree = true).assertDoesNotExist()
        list.performTouchInput {
            repeat(9) { moveBy(Offset(0f, -150f)) }
            moveBy(Offset(0f, -600f))
        }
        composeRule.waitForIdle()
        awaitStreamedText("Reply begins.")

        composeRule.runOnIdle { state = state.copy(items = state.items + toolRow(ToolCallStatus.Running)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(TOOL, useUnmergedTree = true).assertDoesNotExist()
        list.performTouchInput {
            advanceEventTime(1_000)
            up()
        }
        composeRule.runOnIdle {
            state = state.copy(items = rows(30) + message("reply", LONG_REPLY, isStreaming = true) + toolRow(ToolCallStatus.Running))
        }

        awaitStreamedText("Reply ends.")
        composeRule.onNodeWithText(TOOL, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun an_accepted_text_send_after_scrolling_up_brings_the_newest_row_and_the_reply_into_view() {
        val repository = SendRepository()
        val vm = threadViewModel(repository)
        var state by mutableStateOf(threadState(rows(30)))
        setScreen(onSend = vm::sendMessage, sentMessages = vm.sentMessages) { state }
        scrollAway()

        composeRule.onNodeWithContentDescription(SEND).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, repository.sends) }
        composeRule.onNodeWithText("Row 30.").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("echo", "hello", isStreaming = false, role = Role.User)) }
        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", "Reply begins.", isStreaming = true)) }
        awaitStreamedText("Reply begins.")
    }

    @Test
    fun an_accepted_attachment_send_after_scrolling_up_brings_the_newest_row_into_view() {
        val repository = SendRepository()
        val vm = threadViewModel(repository)
        vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)
        var state by mutableStateOf(threadState(rows(30)))
        setScreen(onSend = vm::sendMessage, sentMessages = vm.sentMessages) { state }
        scrollAway()

        composeRule.onNodeWithContentDescription(SEND).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf(1), repository.attachmentCounts) }

        composeRule.onNodeWithText("Row 30.").assertIsDisplayed()
    }

    @Test
    fun a_refused_send_does_not_move_the_list() {
        val repository = SendRepository(failure = RelayErrorException(code = "server.error", retryable = false, message = "no"))
        val vm = threadViewModel(repository)
        var state by mutableStateOf(threadState(rows(30)))
        setScreen(onSend = vm::sendMessage, sentMessages = vm.sentMessages) { state }
        scrollAway()

        composeRule.onNodeWithContentDescription(SEND).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, repository.sends) }
        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = false)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        composeRule.onNodeWithText(REPLY).assertDoesNotExist()
    }

    @Test
    fun a_position_restored_away_from_the_end_is_not_following_and_one_at_the_end_is() {
        val restoration = StateRestorationTester(composeRule)
        var state by mutableStateOf(threadState(rows(30)))
        restoration.setContent { Screen(state) }
        scrollAway()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = false)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        composeRule.onNodeWithText(REPLY).assertDoesNotExist()

        // Back to the newest end, recreate again: the restored position is at the end, so it follows.
        list().performTouchInput { repeat(4) { swipeUp() } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(REPLY).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { state = state.copy(items = state.items + message("second", SECOND, isStreaming = false)) }
        composeRule.onNodeWithText(SECOND).assertIsDisplayed()
    }

    private fun list() = composeRule.onNode(hasScrollToIndexAction())

    /** A streaming body reveals its text a few characters per frame, so wait for it, then check it shows. */
    private fun awaitStreamedText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    /** A real drag: under reverseLayout older rows sit above, so the finger moves down to reach them. */
    private fun scrollAway() {
        list().performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
    }

    /** Overscroll is off, as in `ThreadScreenNewestRowTest`: on a device its stretch keeps drawing frames. */
    private fun setScreen(
        onSend: (String) -> Unit = {},
        sentMessages: Flow<Unit> = emptyFlow(),
        state: () -> ThreadUiState,
    ) {
        composeRule.setContent { Screen(state(), onSend, sentMessages) }
    }

    @Composable
    private fun Screen(
        state: ThreadUiState,
        onSend: (String) -> Unit = {},
        sentMessages: Flow<Unit> = emptyFlow(),
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = onSend,
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    draft = "hello",
                    sentMessages = sentMessages,
                )
            }
        }
    }

    /** Sends return at once, or throw [failure]; uploads are stored. */
    private class SendRepository(
        private val failure: Throwable? = null,
    ) : ConversationRepository by FakeConversationRepository() {
        var sends = 0
        val attachmentCounts = mutableListOf<Int>()

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = send(text)

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            attachmentCounts += attachments.size
            return send(text)
        }

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult = AttachmentUploadResult.Stored("att-1")

        private fun send(text: String): Message {
            sends++
            failure?.let { throw it }
            return Message("sent-$sends", "s1", Role.User, text, TIMESTAMP, isStreaming = false)
        }
    }

    private fun threadViewModel(repository: ConversationRepository) =
        ThreadViewModel(
            SavedStateHandle(mapOf("serverId" to "pyrybox", "conversationId" to "conversation")),
            repository,
            FakeConnectionStateSource(),
            ComposerDraftStore(),
            attachmentReader = AttachmentReader { AttachmentRead.Bytes("bytes".toByteArray()) },
        )

    private fun threadState(items: List<ThreadItem>) =
        ThreadUiState(
            conversationId = "conversation",
            displayName = "Follow",
            isPromoted = true,
            hasMessages = true,
            items = items,
        )

    private fun rows(count: Int): List<ThreadItem> = (1..count).map { message("Row-$it", "Row $it.", isStreaming = false) }

    private fun message(
        id: String,
        content: String,
        isStreaming: Boolean,
        role: Role = Role.Assistant,
    ): ThreadItem = ThreadItem.MessageItem(Message(id, "s1", role, content, TIMESTAMP, isStreaming))

    private fun toolRow(status: ToolCallStatus): ThreadItem =
        ThreadItem.MessageItem(
            Message(
                id = "tool-1",
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = TIMESTAMP,
                isStreaming = false,
                toolCall =
                    ToolCall(
                        toolName = TOOL,
                        input = "",
                        output =
                            if (status ==
                                ToolCallStatus.Done
                            ) {
                                "found"
                            } else {
                                ""
                            },
                        status = status,
                    ),
            ),
        )

    private fun queued() = QueuedMessage(id = 1L, text = QUEUED, timestamp = TIMESTAMP)

    private companion object {
        val TIMESTAMP: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val SEND = "Send message"
        const val TOOL = "Grep"
        const val QUEUED = "Queued follow-up."
        const val REPLY = "The file's witness token."
        const val SECOND = "A second reply."
        val LONG_REPLY = "Reply begins.\n\n" + (1..12).joinToString("\n\n") { "Reply line $it." } + "\n\nReply ends."
    }
}
