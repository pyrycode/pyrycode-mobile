package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The message trail's hooks in the send path and the thread projection (#1564): each state a sent message
 * reaches is one line, keyed by its `message_id`, and nothing the operator typed reaches a line or the file.
 */
class RemoteConversationRepositoryMessageTrailTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val relayLogSink = RelayLog.sink

    /** The attachment send writes a debug [RelayLog] line, and `android.util.Log` is not mocked on the JVM. */
    @Before
    fun silenceRelayLog() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun restoreRelayLog() {
        RelayLog.sink = relayLogSink
    }

    @Test
    fun anAcknowledgedSend_logsSentWithTheConnectionToken_thenAcknowledged() =
        runTest {
            val env = newEnv()
            val send = startSend(env, "hi")
            val sent = env.pump.sentMessage()
            env.pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertTrue(send().isSuccess)
            val id = sent.messageId()
            assertEquals(listOf("state=sent conn=$TOKEN", "state=acknowledged"), env.trailOf(id))
            env.trail.dispose()
        }

    @Test
    fun aSendWhileNotConnected_logsOnlyTheFailure() =
        runTest {
            val env = newEnv()
            env.pump.sendResult = false
            val send = startSend(env, "hi")

            assertTrue(send().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("state=failed reason=not_connected"), env.trailOf(env.pump.sentMessage().messageId()))
            env.trail.dispose()
        }

    @Test
    fun aDaemonError_logsItsCode_neverItsMessage() =
        runTest {
            val env = newEnv()
            val send = startSend(env, "hi")
            val sent = env.pump.sentMessage()
            env.pump.push(errorEnvelope(sent.id, code = "message.too_long", message = "daemon says SECRET-DAEMON-WORDS"))
            runCurrent()

            assertTrue(send().isFailure)
            assertEquals(
                listOf("state=sent conn=$TOKEN", "state=failed reason=daemon_error code=message.too_long"),
                env.trailOf(sent.messageId()),
            )
            assertFalse(env.logcat.any { "SECRET-DAEMON-WORDS" in it })
            env.trail.dispose()
        }

    @Test
    fun anUnknownConversation_logsTheNotFoundCode() =
        runTest {
            val env = newEnv()
            val send = startSend(env, "hi")
            val sent = env.pump.sentMessage()
            env.pump.push(errorEnvelope(sent.id, code = "conversation.not_found", message = "c1"))
            runCurrent()

            assertTrue(send().exceptionOrNull() is IllegalArgumentException)
            assertEquals(
                "state=failed reason=daemon_error code=conversation.not_found",
                env.trailOf(sent.messageId()).last(),
            )
            env.trail.dispose()
        }

    @Test
    fun aConnectionTornDownBeforeTheAck_endsTheTrailWithTheTornDownFailure() =
        runTest {
            val env = newEnv()
            val send = startSend(env, "hi")
            val sent = env.pump.sentMessage()
            env.pump.close()
            runCurrent()

            assertTrue(send().exceptionOrNull() is IllegalStateException)
            assertEquals(
                listOf("state=sent conn=$TOKEN", "state=failed reason=torn_down"),
                env.trailOf(sent.messageId()),
            )
            val fileLines = env.file.readLines().filter { sent.messageId() in it }
            assertTrue(fileLines.last().endsWith("state=failed reason=torn_down"))
            env.trail.dispose()
        }

    @Test
    fun aQueuedSend_logsQueuedOnce_andDeliveredOnceOnTheDrain() =
        runTest {
            val env = newEnv()
            val send = startSend(env, "two")
            val sent = env.pump.sentMessage()
            val id = sent.messageId()
            env.pump.push(queueStateEnvelope(listOf(42L to id)))
            env.pump.push(queueStateEnvelope(listOf(42L to id)))
            env.pump.push(ackEnvelope(sent.id))
            env.pump.push(queueStateEnvelope(emptyList()))
            env.pump.push(userMessageEnvelope(id))
            runCurrent()

            assertTrue(send().isSuccess)
            assertEquals(
                listOf("state=sent conn=$TOKEN", "state=queued", "state=acknowledged", "state=delivered"),
                env.trailOf(id),
            )
            env.trail.dispose()
        }

    @Test
    fun aSendDeliveredWithoutQueueing_logsDeliveredOnThePushedMessage() =
        runTest {
            val env = newEnv()
            startSend(env, "hi")
            val sent = env.pump.sentMessage()
            env.pump.push(ackEnvelope(sent.id))
            env.pump.push(userMessageEnvelope(sent.messageId()))
            runCurrent()

            assertEquals(
                listOf("state=sent conn=$TOKEN", "state=acknowledged", "state=delivered"),
                env.trailOf(sent.messageId()),
            )
            env.trail.dispose()
        }

    @Test
    fun aConfirmedDrop_logsDropped_andNoDelivery() =
        runTest {
            val env = newEnv()
            startSend(env, "two")
            val sent = env.pump.sentMessage()
            val id = sent.messageId()
            env.pump.push(queueStateEnvelope(listOf(42L to id)))
            env.pump.push(ackEnvelope(sent.id))
            runCurrent()
            backgroundScope.launch { env.repo.dropQueuedMessage(CONV, 42L) }
            runCurrent()
            env.pump.push(queueStateEnvelope(emptyList()))
            runCurrent()

            assertEquals(
                listOf("state=sent conn=$TOKEN", "state=queued", "state=acknowledged", "state=dropped reason=user_dropped"),
                env.trailOf(id),
            )
            env.trail.dispose()
        }

    @Test
    fun anotherDevicesQueuedOrDeliveredMessage_logsNothing() =
        runTest {
            val env = newEnv()
            env.pump.push(queueStateEnvelope(listOf(7L to FOREIGN_ID)))
            env.pump.push(queueStateEnvelope(emptyList()))
            env.pump.push(userMessageEnvelope(FOREIGN_ID))
            runCurrent()

            assertEquals(emptyList<String>(), env.logcat)
            env.trail.dispose()
        }

    @Test
    fun theMessageTextAndAttachmentNames_reachNoLineAndNotTheFile() =
        runTest {
            val env = newEnv()
            val text = "ZEBRA-QUOKKA-7731 the secret plan"
            var outcome: Result<Message>? = null
            backgroundScope.launch {
                outcome =
                    runCatching {
                        env.repo.sendMessage(
                            CONV,
                            text,
                            listOf(MessageAttachment(ATTACHMENT_ID, "PANGOLIN-REPORT.pdf", "application/pdf")),
                        )
                    }
            }
            runCurrent()
            val sent = env.pump.sentMessage()
            env.pump.push(queueStateEnvelope(listOf(42L to sent.messageId()), text = text))
            env.pump.push(ackEnvelope(sent.id))
            env.pump.push(queueStateEnvelope(emptyList()))
            env.pump.push(userMessageEnvelope(sent.messageId(), text = text))
            runCurrent()

            assertTrue(requireNotNull(outcome).isSuccess)
            assertEquals(4, env.logcat.size)
            val fileText = env.file.readText()
            for (secret in listOf("ZEBRA-QUOKKA-7731", "secret plan", "PANGOLIN-REPORT", ATTACHMENT_ID)) {
                assertFalse("logcat carries $secret", env.logcat.any { secret in it })
                assertFalse("file carries $secret", secret in fileText)
            }
            env.trail.dispose()
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private class Env(
        val pump: FakeSessionPump,
        val repo: RemoteConversationRepository,
        val trail: MessageTrail,
        val logcat: MutableList<String>,
        val file: File,
    ) {
        /** The trail's lines for [messageId], without the timestamp and id. */
        fun trailOf(messageId: String): List<String> =
            logcat.filter { " id=$messageId " in it }.map { it.substringAfter(" id=$messageId ") }
    }

    private fun TestScope.newEnv(): Env {
        val logcat = mutableListOf<String>()
        val file = File(tmp.root, MessageTrail.FILE_NAME)
        val trail =
            MessageTrail(
                file = { file },
                writerDispatcher = StandardTestDispatcher(testScheduler),
                now = { Instant.parse(TS) },
                logcat = { logcat += it },
            )
        val pump = FakeSessionPump()
        val repo =
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                messageTrail = trail,
                connToken = { TOKEN },
            )
        runCurrent()
        return Env(pump, repo, trail, logcat, file)
    }

    /** Launches [RemoteConversationRepository.sendMessage] (it suspends on the reply) and returns its eventual result. */
    private fun TestScope.startSend(
        env: Env,
        text: String,
    ): () -> Result<Message> {
        var outcome: Result<Message>? = null
        backgroundScope.launch { outcome = runCatching { env.repo.sendMessage(CONV, text) } }
        runCurrent()
        return { requireNotNull(outcome) { "sendMessage has not completed" } }
    }

    private fun FakeSessionPump.sentMessage(): Envelope = sent.single { it.type == "send_message" }

    private fun Envelope.messageId(): String =
        payload.jsonObject
            .getValue("message_id")
            .jsonPrimitive.content

    private fun ackEnvelope(inReplyTo: Long): Envelope =
        Envelope(id = 99L, type = "ack", ts = TS, payload = JsonObject(emptyMap()), inReplyTo = inReplyTo)

    private fun errorEnvelope(
        inReplyTo: Long,
        code: String,
        message: String,
    ): Envelope =
        Envelope(
            id = 99L,
            type = "error",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"$message","retryable":false}"""),
            inReplyTo = inReplyTo,
        )

    private fun queueStateEnvelope(
        items: List<Pair<Long, String>>,
        text: String = "queued text",
    ): Envelope {
        val queued =
            items.joinToString(",") { (queuedMsgId, messageId) ->
                """{"queued_msg_id":$queuedMsgId,"message_id":"$messageId","text":"$text","ts":"$TS"}"""
            }
        return Envelope(
            id = 1L,
            type = "queue_state",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$CONV","queued":[$queued]}"""),
        )
    }

    private fun userMessageEnvelope(
        messageId: String,
        text: String = "delivered text",
    ): Envelope =
        Envelope(
            id = 2L,
            type = "message",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$CONV","message_id":"$messageId","role":"user","text":"$text"}""",
                ),
        )

    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        var sendResult = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return sendResult
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }

        /** Ends the inbound stream, so the repository's collector runs its teardown sweep. */
        fun close() {
            inboundChannel.close()
        }
    }

    private companion object {
        const val TS = "2026-10-03T10:00:00Z"
        const val CONV = "c-1"
        const val TOKEN = "1a2b3c4d"
        const val FOREIGN_ID = "9f8e7d6c-5b4a-4321-8fed-cba987654321"
        const val ATTACHMENT_ID = "3f2b8c1e-5d4a-4b6f-9a2e-7c1d0e9f8a6b"
    }
}
