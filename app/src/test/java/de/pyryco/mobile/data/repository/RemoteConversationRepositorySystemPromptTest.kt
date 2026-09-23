package de.pyryco.mobile.data.repository

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire round trip for the conversation system prompt (#823): `request_system_prompt` → `system_prompt`
 * and `set_system_prompt` → `conversation_updated` / `error`. The payload shapes themselves are owned by
 * `SystemPromptPayloadsTest`; this class proves what the repository sends, what it returns, and that
 * failures stay scoped to the one call. A sibling of [RemoteConversationRepositoryTest] with its own
 * small pump, because that class is already several thousand lines long.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositorySystemPromptTest {
    @Test
    fun read_sendsOneRequestNamingTheConversationAndReturnsTheReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val read = startRead(repo, "conv-1")
            runCurrent()
            val sent = pump.sent.single()
            assertEquals("request_system_prompt", sent.type)
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"conv-1"}"""), sent.payload)

            pump.push(reply(sent.id, """{"system_prompt":"Haiku","session_prompt_status":"differs"}"""))
            runCurrent()

            assertEquals(SystemPromptReading("Haiku", SessionPromptStatus.Differs), read().getOrThrow())
        }

    @Test
    fun read_keepsAbsentEmptyAndTextDistinct() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val results =
                listOf(
                    """{"session_prompt_status":"matches"}""",
                    """{"system_prompt":"","session_prompt_status":"matches"}""",
                    """{"system_prompt":"text","session_prompt_status":"matches"}""",
                ).map { payload ->
                    val read = startRead(repo, "conv-1")
                    runCurrent()
                    pump.push(reply(pump.sent.last().id, payload))
                    runCurrent()
                    read().getOrThrow().systemPrompt
                }

            assertEquals(listOf(null, "", "text"), results)
        }

    // A conversation with nothing running reads normally rather than failing.
    @Test
    fun read_noSession_isANormalReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val read = startRead(repo, "conv-1")
            runCurrent()
            pump.push(reply(pump.sent.single().id, """{"system_prompt":"stored","session_prompt_status":"no_session"}"""))
            runCurrent()

            assertEquals(SystemPromptReading("stored", SessionPromptStatus.NoSession), read().getOrThrow())
        }

    // The daemon never answers this verb on a non-interactive conn, so the read must not wait for it.
    @Test
    fun read_withoutInteractive_failsImmediatelyWithoutSending() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })

            val read = startRead(repo, "conv-1")
            runCurrent()

            assertTrue(read().exceptionOrNull() is IllegalStateException)
            assertTrue(pump.sent.isEmpty())
        }

    // A malformed reply fails that read and nothing else: the list is untouched and a later read works.
    @Test
    fun read_malformedReply_failsOnlyThatRead() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            pump.push(conversationUpdated(inReplyTo = null, id = "conv-1", name = "Before"))
            runCurrent()
            val before = repo.observeConversations(ConversationFilter.All).first()

            val bad = startRead(repo, "conv-1")
            runCurrent()
            pump.push(reply(pump.sent.last().id, """{"system_prompt":42,"session_prompt_status":"matches"}"""))
            runCurrent()
            val badStatus = startRead(repo, "conv-1")
            runCurrent()
            pump.push(reply(pump.sent.last().id, """{"system_prompt":"x","session_prompt_status":"stale"}"""))
            runCurrent()

            assertTrue(bad().exceptionOrNull() is IllegalArgumentException)
            assertTrue(badStatus().exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first())

            val good = startRead(repo, "conv-1")
            runCurrent()
            pump.push(reply(pump.sent.last().id, """{"session_prompt_status":"no_session"}"""))
            runCurrent()
            assertEquals(SystemPromptReading(null, SessionPromptStatus.NoSession), good().getOrThrow())
        }

    // The reply carries no conversation id: one that answers no pending request lands nowhere.
    @Test
    fun read_unmatchedReply_isIgnored() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val read = startRead(repo, "conv-1")
            runCurrent()
            val sentId = pump.sent.single().id
            pump.push(reply(sentId + 100, """{"system_prompt":"stray","session_prompt_status":"matches"}"""))
            pump.push(reply(null, """{"system_prompt":"stray","session_prompt_status":"matches"}"""))
            runCurrent()
            pump.push(reply(sentId, """{"system_prompt":"mine","session_prompt_status":"matches"}"""))
            runCurrent()

            assertEquals("mine", read().getOrThrow().systemPrompt)
        }

    @Test
    fun write_sendsNullEmptyAndTextAsDistinctValues() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            for (value in listOf(null, "", "Answer only in haiku. 🌸")) {
                val write = startWrite(repo, "conv-1", value)
                runCurrent()
                pump.push(conversationUpdated(inReplyTo = pump.sent.last().id, id = "conv-1", name = "Chan"))
                runCurrent()
                write().getOrThrow()
            }

            val sent = pump.sent.filter { it.type == "set_system_prompt" }
            assertEquals(3, sent.size)
            assertTrue(sent.all { it.payload.jsonObject["conversation_id"] == JsonPrimitive("conv-1") })
            assertEquals(
                listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("Answer only in haiku. 🌸")),
                sent.map { it.payload.jsonObject["system_prompt"] },
            )
        }

    // The ack is the conversation record; it folds into the list the way rename's does.
    @Test
    fun write_ackUpsertsTheReturnedRecord() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val write = startWrite(repo, "conv-1", "x")
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = pump.sent.single().id, id = "conv-1", name = "Server"))
            runCurrent()

            write().getOrThrow()
            assertEquals(
                "Server",
                repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun write_exactly8192BytesOfMultiByteText_isSent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val text = "€".repeat(2730) + "ab"

            val write = startWrite(repo, "conv-1", text)
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = pump.sent.single().id, id = "conv-1", name = "Chan"))
            runCurrent()

            write().getOrThrow()
            assertEquals(
                JsonPrimitive(text),
                pump.sent
                    .single()
                    .payload.jsonObject["system_prompt"],
            )
        }

    @Test
    fun write_over8192BytesOfMultiByteText_isRefusedBeforeAnyFrame() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val text = "€".repeat(2730) + "äa" // 8193 bytes, 2732 chars

            val write = startWrite(repo, "conv-1", text)
            runCurrent()

            val error = write().exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(pump.sent.isEmpty())
            assertTrue(!error?.message.orEmpty().contains("€"))
            assertTrue(!error?.message.orEmpty().contains("conv-1"))
        }

    @Test
    fun write_conversationNotFound_throwsIllegalArgument() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val write = startWrite(repo, "gone", "x")
            runCurrent()
            pump.push(error(pump.sent.single().id, "conversation.not_found"))
            runCurrent()

            assertTrue(write().exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun write_otherServerError_throwsRelayError() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)

            val write = startWrite(repo, "conv-1", "x")
            runCurrent()
            pump.push(error(pump.sent.single().id, "protocol.malformed"))
            runCurrent()

            val thrown = write().exceptionOrNull()
            assertTrue(thrown is RelayErrorException)
            assertEquals("protocol.malformed", (thrown as RelayErrorException).code)
        }

    // The write has no interactive gate: the daemon answers it on any conn.
    @Test
    fun write_withoutInteractive_isStillSent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })

            val write = startWrite(repo, "conv-1", null)
            runCurrent()
            pump.push(conversationUpdated(inReplyTo = pump.sent.single().id, id = "conv-1", name = "Chan"))
            runCurrent()

            assertNull(write().exceptionOrNull())
        }

    private fun TestScope.interactiveRepo(pump: FakeSessionPump) =
        RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

    private fun TestScope.startRead(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): () -> Result<SystemPromptReading> {
        var outcome: Result<SystemPromptReading>? = null
        backgroundScope.launch { outcome = runCatching { repo.requestSystemPrompt(conversationId) } }
        return { requireNotNull(outcome) { "read has not completed" } }
    }

    private fun TestScope.startWrite(
        repo: RemoteConversationRepository,
        conversationId: String,
        systemPrompt: String?,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.setSystemPrompt(conversationId, systemPrompt) } }
        return { requireNotNull(outcome) { "write has not completed" } }
    }

    private fun reply(
        inReplyTo: Long?,
        payload: String,
    ) = Envelope(id = 99L, type = "system_prompt", ts = TS, payload = MobileJson.parseToJsonElement(payload), inReplyTo = inReplyTo)

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
        name: String,
    ) = Envelope(
        id = 98L,
        type = "conversation_updated",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"id":"$id","name":"$name","is_promoted":true,"is_archived":false,"cwd":"/p","last_used_at":"2026-05-08T10:00:00Z","workspace_label":null}""",
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
        const val TS = "2026-09-22T00:00:00Z"
    }
}
