package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which agent runs each conversation (#1108): the `conversations` row, the `conversation_created` reply and
 * `conversation_updated` set it when they carry `agent`, and a `conversation_updated` without the key keeps
 * the stored value. A sibling of [RemoteConversationRepositoryMuteTest] with the same small pump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryAgentTest {
    @Test
    fun snapshotRow_carryingCodexReadsCodex_andARowWithoutTheKeyReadsClaude() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(
                conversations(
                    """
                    {"id":"codex","name":null,"is_promoted":false,"cwd":"/p","agent":"codex","last_message_ts":"2026-05-08T10:00:00Z","last_used_at":"2026-05-08T10:00:00Z"},
                    {"id":"plain","name":null,"is_promoted":false,"cwd":"/p","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}
                    """,
                ),
            )
            runCurrent()

            val rows = repo.observeConversations(ConversationFilter.All).first()
            assertEquals(
                listOf("codex" to ConversationAgent.Codex, "plain" to ConversationAgent.Claude),
                rows.map { it.id to it.agent },
            )
        }

    @Test
    fun conversationCreated_carryingCodex_isStoredAndReturnedAsCodex() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            var created: Conversation? = null
            backgroundScope.launch { created = repo.createDiscussion(null) }
            runCurrent()

            val request = pump.sent.single { it.type == "create_conversation" }
            pump.push(record(type = "conversation_created", inReplyTo = request.id, name = null, agent = "codex"))
            runCurrent()

            assertEquals(ConversationAgent.Codex, requireNotNull(created).agent)
            assertEquals(ConversationAgent.Codex, single(repo).agent)
        }

    @Test
    fun conversationUpdated_carryingAgent_switchesTheStoredAgentBothWays() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Chan", agent = null))
            runCurrent()
            assertEquals(ConversationAgent.Claude, single(repo).agent)

            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Chan", agent = "codex"))
            runCurrent()
            assertEquals(ConversationAgent.Codex, single(repo).agent)

            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Chan", agent = "claude"))
            runCurrent()
            assertEquals(ConversationAgent.Claude, single(repo).agent)
        }

    @Test
    fun conversationUpdated_withoutAgent_keepsTheStoredAgentAndFoldsTheRest() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Chan", agent = "codex"))
            runCurrent()

            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Renamed", agent = null))
            runCurrent()

            val row = single(repo)
            assertEquals("Renamed", row.name)
            assertEquals(ConversationAgent.Codex, row.agent)
        }

    // The correlated reply also reaches the caller's own upsert after the inbound fold; that path must keep the
    // stored agent too, or it would undo the inbound arm's keep.
    @Test
    fun correlatedReply_withoutAgent_keepsTheStoredAgent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(record(type = "conversation_updated", inReplyTo = null, name = "Chan", agent = "codex"))
            runCurrent()

            var outcome: Result<Unit>? = null
            backgroundScope.launch { outcome = runCatching { repo.setMuted("conv-1", true) } }
            runCurrent()
            val request = pump.sent.last { it.type == "set_conversation_muted" }
            pump.push(record(type = "conversation_updated", inReplyTo = request.id, name = "Chan", agent = null, muted = true))
            runCurrent()

            requireNotNull(outcome).getOrThrow()
            val row = single(repo)
            assertTrue(row.muted)
            assertEquals(ConversationAgent.Codex, row.agent)
        }

    private fun TestScope.repo(pump: FakeSessionPump) =
        RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

    private suspend fun single(repo: RemoteConversationRepository): Conversation =
        repo.observeConversations(ConversationFilter.All).first().single()

    private fun conversations(rows: String) =
        Envelope(
            id = 97L,
            type = "conversations",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversations":[${rows.trim()}]}"""),
        )

    private fun record(
        type: String,
        inReplyTo: Long?,
        name: String?,
        agent: String?,
        muted: Boolean = false,
    ): Envelope {
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val agentJson = agent?.let { ""","agent":"$it"""" }.orEmpty()
        return Envelope(
            id = 98L,
            type = type,
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"id":"conv-1","name":$nameJson,"is_promoted":true,"is_muted":$muted,"cwd":"/p","last_used_at":"2026-05-08T10:00:00Z","workspace_label":null$agentJson}""",
                ),
            inReplyTo = inReplyTo,
        )
    }

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
        const val TS = "2026-09-25T00:00:00Z"
    }
}
