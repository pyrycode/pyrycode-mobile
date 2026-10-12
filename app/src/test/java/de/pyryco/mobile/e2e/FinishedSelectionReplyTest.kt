package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinishedSelectionReplyTest {
    private class Source : ConversationRepository by FakeConversationRepository() {
        val rows = MutableStateFlow<List<ThreadItem>>(emptyList())

        override fun observeMessages(conversationId: String) = rows
    }

    private fun reply(
        text: String,
        role: Role = Role.Assistant,
        streaming: Boolean = false,
        parent: String = "",
    ) = Message("reply", "session", role, text, Instant.fromEpochMilliseconds(0), streaming, parentToolUseId = parent)

    @Test fun followsReplacementConnectionWithoutResendingTheRequest() =
        runTest {
            val old = Source()
            val current = Source()
            val live = MutableStateFlow<ConversationRepository?>(old)
            val result = async { withTimeout(1000) { live.awaitFinishedSelectionReply("chat") } }
            runCurrent()
            live.value = null
            old.rows.value = listOf(ThreadItem.MessageItem(reply("amber cobalt jade")))
            runCurrent()
            assertFalse("a disconnected repository cannot provide the result", result.isCompleted)
            val completed = reply("amber cobalt jade")
            current.rows.value = listOf(ThreadItem.MessageItem(completed))
            live.value = current
            runCurrent()
            assertSame(completed, result.await())
        }

    @Test fun acceptsActualReplyButRejectsUserChildBlankAndStreamingText() =
        runTest {
            val source = Source()
            val live = MutableStateFlow<ConversationRepository?>(source)
            val result = async { withTimeout(1000) { live.awaitFinishedSelectionReply("chat") } }
            source.rows.value =
                listOf(
                    reply("Use any ordinary words.", role = Role.User),
                    reply("Child text", parent = "agent"),
                    reply(" "),
                    reply("Still arriving", streaming = true),
                ).map { ThreadItem.MessageItem(it) }
            runCurrent()
            assertFalse(result.isCompleted)
            val completed = reply("**Morning light** fills the garden.\n\nA quiet start.")
            source.rows.value = source.rows.value + ThreadItem.MessageItem(completed)
            runCurrent()
            assertSame(completed, result.await())
        }
}
