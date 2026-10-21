package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.FixtureThread
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinishedSelectionReplyTest {
    private val oldSink = RelayLog.sink

    @Before fun quietAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreAndroidLogs() {
        RelayLog.sink = oldSink
    }

    private class Source : ConversationRepository by FakeConversationRepository() {
        override val negotiatedThread = FixtureThread("selection-host", "chat")
        val rows get() = negotiatedThread.facts

        override fun observeMessages(conversationId: String): kotlinx.coroutines.flow.Flow<List<ThreadItem>> =
            error("legacy content must not satisfy selection")
    }

    private fun reply(
        text: String,
        role: Role = Role.Assistant,
        streaming: Boolean = false,
        parent: String = "",
        id: Long = 5,
    ) = authoritativeItem(
        id,
        kind =
            if (role ==
                Role.User
            ) {
                "user_message"
            } else {
                "assistant_message"
            },
        session = "session",
        turn = "turn",
        parent = if (parent.isEmpty()) null else 99,
        active = streaming,
        status =
            if (streaming) {
                "running"
            } else if (role ==
                Role.User
            ) {
                "delivered"
            } else {
                "done"
            },
        content = buildJsonObject { put("text", text) }.toString(),
    )

    @Test fun followsReplacementConnectionWithoutResendingTheRequest() =
        runTest {
            val old = Source()
            val current = Source()
            val live = MutableStateFlow<ConversationRepository?>(old)
            val result = async { withTimeout(1000) { live.awaitFinishedSelectionReply("chat") } }
            runCurrent()
            live.value = null
            old.rows.value = listOf(reply("amber cobalt jade"))
            runCurrent()
            assertFalse("a disconnected repository cannot provide the result", result.isCompleted)
            val completed = reply("amber cobalt jade")
            current.rows.value = listOf(completed)
            live.value = current
            runCurrent()
            val actual = result.await()
            assertEquals("amber cobalt jade", actual.content)
            assertEquals("session", actual.sessionId)
            assertEquals(Role.Assistant, actual.role)
            assertEquals(false, actual.isStreaming)
            assertEquals(
                authoritativeThreadEvidence(
                    "selection-host",
                    "chat",
                    current.negotiatedThread,
                    current.negotiatedThread.observe("chat").first(),
                ).records.single().message.id,
                actual.id,
            )
        }

    @Test fun acceptsActualReplyButRejectsUserChildBlankAndStreamingText() =
        runTest {
            val source = Source()
            val live = MutableStateFlow<ConversationRepository?>(source)
            val result = async { withTimeout(1000) { live.awaitFinishedSelectionReply("chat") } }
            source.rows.value =
                listOf(
                    reply("Use any ordinary words.", role = Role.User, id = 1),
                    reply("Child text", parent = "agent", id = 2),
                    reply(" ", id = 3),
                    reply("Still arriving", streaming = true, id = 4),
                )
            runCurrent()
            assertFalse(result.isCompleted)
            val completed = reply("**Morning light** fills the garden.\n\nA quiet start.")
            source.rows.value = source.rows.value + completed
            runCurrent()
            val actual = result.await()
            assertEquals("**Morning light** fills the garden.\n\nA quiet start.", actual.content)
            assertEquals(Role.Assistant, actual.role)
            assertEquals(false, actual.isStreaming)
        }
}
