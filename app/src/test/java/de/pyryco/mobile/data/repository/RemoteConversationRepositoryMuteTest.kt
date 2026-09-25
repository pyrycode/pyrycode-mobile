package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire round trip for the conversation mute flag (#1000): `set_conversation_muted` → a correlated
 * `conversation_updated` / `error`, plus the uncorrelated push the daemon also sends the requester. A
 * sibling of [RemoteConversationRepositoryTest] with its own small pump, like
 * [RemoteConversationRepositorySystemPromptTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryMuteTest {
    // The daemon refuses a frame without `muted`, so `false` must be on the wire as well as `true`.
    @Test
    fun setMuted_sendsOneFramePerCallWithExactlyConversationIdAndMuted() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            for (muted in listOf(true, false)) {
                val write = startWrite(repo, "conv-1", muted)
                runCurrent()
                pump.push(conversationUpdated(inReplyTo = muteFrame(pump).id, id = "conv-1", muted = muted))
                runCurrent()
                write().getOrThrow()
            }

            val sent = pump.sent.filter { it.type == "set_conversation_muted" }
            assertEquals(2, sent.size)
            assertEquals(
                listOf(
                    MobileJson.parseToJsonElement("""{"conversation_id":"conv-1","muted":true}"""),
                    MobileJson.parseToJsonElement("""{"conversation_id":"conv-1","muted":false}"""),
                ),
                sent.map { it.payload },
            )
        }

    @Test
    fun setMuted_replyFoldsIntoTheListWithoutARelist() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(conversationUpdated(inReplyTo = null, id = "conv-1", muted = false))
            runCurrent()

            val mute = startWrite(repo, "conv-1", true)
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = muteFrame(pump).id, id = "conv-1", muted = true))
            runCurrent()
            mute().getOrThrow()
            assertTrue(single(repo).muted)
            val listsBefore = pump.sent.count { it.type == "list_conversations" }

            val unmute = startWrite(repo, "conv-1", false)
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = muteFrame(pump).id, id = "conv-1", muted = false))
            runCurrent()
            unmute().getOrThrow()
            // Counted before observing again: the write itself must not ask for a re-list.
            assertEquals(listsBefore, pump.sent.count { it.type == "list_conversations" })
            assertFalse(single(repo).muted)
        }

    // The requester gets both the correlated reply and the uncorrelated push; both upsert by id.
    @Test
    fun setMuted_replyAndPushTogether_leaveOneRowWithTheNewValue() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val write = startWrite(repo, "conv-1", true)
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = muteFrame(pump).id, id = "conv-1", muted = true))
            pump.push(conversationUpdated(inReplyTo = null, id = "conv-1", muted = true))
            runCurrent()

            write().getOrThrow()
            val rows = repo.observeConversations(ConversationFilter.All).first()
            assertEquals(listOf("conv-1"), rows.map { it.id })
            assertTrue(rows.single().muted)
        }

    @Test
    fun setMuted_serverRefusal_throwsRelayErrorAndKeepsThePreviousValue() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(conversationUpdated(inReplyTo = null, id = "conv-1", muted = false))
            runCurrent()
            val before = repo.observeConversations(ConversationFilter.All).first()

            val write = startWrite(repo, "conv-1", true)
            runCurrent()
            pump.push(error(muteFrame(pump).id, "protocol.malformed"))
            runCurrent()

            val thrown = write().exceptionOrNull()
            assertTrue(thrown is RelayErrorException)
            assertEquals("protocol.malformed", (thrown as RelayErrorException).code)
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first())
        }

    // `conversation.not_found` maps to IllegalArgumentException for every verb (RelayRequests.mapError).
    @Test
    fun setMuted_unknownConversation_throwsIllegalArgumentAndKeepsTheList() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(conversationUpdated(inReplyTo = null, id = "conv-1", muted = false))
            runCurrent()
            val before = repo.observeConversations(ConversationFilter.All).first()

            val write = startWrite(repo, "gone", true)
            runCurrent()
            pump.push(error(muteFrame(pump).id, "conversation.not_found"))
            runCurrent()

            assertTrue(write().exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first())
        }

    private fun TestScope.repo(pump: FakeSessionPump) =
        RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

    /** The latest mute frame; the repository also sends its own `list_conversations` requests. */
    private fun muteFrame(pump: FakeSessionPump): Envelope = pump.sent.last { it.type == "set_conversation_muted" }

    private suspend fun single(repo: RemoteConversationRepository): Conversation =
        repo.observeConversations(ConversationFilter.All).first().single()

    private fun TestScope.startWrite(
        repo: RemoteConversationRepository,
        conversationId: String,
        muted: Boolean,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.setMuted(conversationId, muted) } }
        return { requireNotNull(outcome) { "write has not completed" } }
    }

    private fun error(
        inReplyTo: Long,
        code: String,
    ) = Envelope(
        id = 99L,
        type = "error",
        ts = TS,
        payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"fixed","retryable":false}"""),
        inReplyTo = inReplyTo,
    )

    private fun conversationUpdated(
        inReplyTo: Long?,
        id: String,
        muted: Boolean,
    ) = Envelope(
        id = 98L,
        type = "conversation_updated",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"id":"$id","name":"Chan","is_promoted":true,"is_archived":false,"is_muted":$muted,"cwd":"/p","last_used_at":"2026-05-08T10:00:00Z","workspace_label":null}""",
            ),
        inReplyTo = inReplyTo,
    )

    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-24T00:00:00Z"
    }
}
