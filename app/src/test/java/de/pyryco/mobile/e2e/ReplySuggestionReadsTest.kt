package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ReplySuggestion
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplySuggestionReadsTest {
    private val oldSink = RelayLog.sink

    @Before fun quietAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreAndroidLogs() {
        RelayLog.sink = oldSink
    }

    @Test
    fun offerArrivingOnlyOnReplacementDoesNotLeaveReadOnRetiredConnection() =
        runTest {
            val live = MutableStateFlow<ConversationRepository?>(Source())
            val read = async { live.awaitReplySuggestion(CONVERSATION) }
            runCurrent()
            val replacement = Source()
            live.value = replacement
            runCurrent()
            replacement.offer.value = suggestion(1u, "reconciled offer")
            runCurrent()
            assertTrue("offer observation stayed on the retired connection", read.isCompleted)
            assertSame(replacement.offer.value, read.await())
        }

    @Test
    fun offerFollowsReplacementAndRejectsRetiredUpdates() =
        runTest {
            val old = Source()
            val replacement = Source()
            val live = MutableStateFlow<ConversationRepository?>(old)
            val read = async { live.awaitReplySuggestion(CONVERSATION) }
            runCurrent()
            live.value = null
            runCurrent()
            old.offer.value = suggestion(4u, "retired")
            runCurrent()
            assertFalse("retired connection supplied the offer", read.isCompleted)
            live.value = replacement
            runCurrent()
            assertFalse("replacement must publish its own offer", read.isCompleted)
            val current = suggestion(1u, "current")
            replacement.offer.value = current
            runCurrent()
            assertTrue("offer observation stayed on the retired connection", read.isCompleted)
            assertSame(current, read.await())
        }

    @Test
    fun offerFollowsSessionSelection() =
        runTest {
            val source = Source()
            val live = MutableStateFlow<ConversationRepository?>(source)
            val read = async { live.awaitReplySuggestion(CONVERSATION) }
            runCurrent()
            source.offer.value = suggestion(1u, "another session", session = "next")
            runCurrent()
            assertFalse(read.isCompleted)
            source.conversations.value = listOf(conversation.copy(currentSessionId = "next"))
            runCurrent()
            assertTrue(read.isCompleted)
            assertSame(source.offer.value, read.await())
        }

    @Test
    fun echoAndClearFollowReplacement() =
        runTest {
            val old = Source()
            val replacement = Source()
            val live = MutableStateFlow<ConversationRepository?>(old)
            val offered = suggestion(2u, "exact offer")
            val echo = async { live.awaitSuggestionEcho(CONVERSATION, requireNotNull(offered.suggestedReply)) }
            val clear = async { live.awaitSuggestionClear(offered) }
            runCurrent()
            live.value = replacement
            runCurrent()
            old.items.value = listOf(user("exact offer"))
            old.offer.value = suggestion(3u, null)
            runCurrent()
            assertFalse("retired echo was accepted", echo.isCompleted)
            assertFalse("retired clear was accepted", clear.isCompleted)

            replacement.items.value = listOf(user("another text"))
            replacement.offer.value = suggestion(2u, null)
            runCurrent()
            assertFalse("inexact echo was accepted", echo.isCompleted)
            assertFalse("non-newer clear was accepted", clear.isCompleted)
            replacement.items.value = listOf(user("exact offer", revision = 2), user("exact offer", id = 2, revision = 2))
            replacement.offer.value = suggestion(3u, "still offered")
            runCurrent()
            assertFalse("duplicate echo was accepted", echo.isCompleted)
            assertFalse("a set was accepted as a clear", clear.isCompleted)

            replacement.items.value = listOf(user("exact offer", revision = 3))
            replacement.offer.value = suggestion(4u, null, session = "other session")
            runCurrent()
            assertTrue("echo observation stayed on the retired connection", echo.isCompleted)
            assertFalse("another session's clear was accepted", clear.isCompleted)
            replacement.offer.value = suggestion(4u, null)
            runCurrent()
            assertTrue("clear observation stayed on the retired connection", clear.isCompleted)
            assertSame(replacement.offer.value, clear.await())
            assertTrue(echo.await().owner.connection === replacement)
        }

    private class Source : ConversationRepository by FakeConversationRepository(), SyntheticThreadEvidenceProducer {
        val conversations = MutableStateFlow(listOf(conversation))
        val offer = MutableStateFlow<ReplySuggestion?>(null)
        val items = MutableStateFlow<List<ThreadItemDto>>(emptyList())
        override val negotiatedThread =
            object : NegotiatedThread() {
                override val host = "suggestion-host"
                override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(this@Source))
                override val store = AuthoritativeThreadStore()

                override fun observe(conversationId: String) =
                    items.map { facts ->
                        require(conversationId == CONVERSATION)
                        val version = facts.maxOfOrNull { it.rev } ?: 0
                        AuthoritativeThreadSnapshot(epoch = "fixture", items = facts, completedVersion = version, checkpoint = version)
                    }

                override suspend fun open(conversationId: String) = true

                override fun older(conversationId: String) = false

                override fun cancel(conversationId: String) = Unit
            }

        override fun evidenceSnapshots(conversationId: String) = negotiatedThread.observe(conversationId)

        override fun observeConversations(filter: ConversationFilter) = conversations

        override fun observeReplySuggestion(
            conversationId: String,
            sessionId: String,
        ) = offer.map { it?.takeIf { reading -> reading.conversationId == conversationId && reading.sessionId == sessionId } }
    }

    private companion object {
        const val CONVERSATION = "conversation"
        const val SESSION = "session"
        val conversation = Conversation(CONVERSATION, "channel", "/project", SESSION, emptyList(), true, Instant.fromEpochSeconds(0))

        fun suggestion(
            revision: ULong,
            text: String?,
            session: String = SESSION,
        ) = ReplySuggestion(CONVERSATION, session, revision, text)

        fun user(
            text: String,
            id: Long = 1,
            revision: Long = id,
        ) = authoritativeItem(
            id,
            kind = "user_message",
            session = SESSION,
            status = "delivered",
            rev = revision,
            content = buildJsonObject { put("text", text) }.toString(),
        )
    }
}
