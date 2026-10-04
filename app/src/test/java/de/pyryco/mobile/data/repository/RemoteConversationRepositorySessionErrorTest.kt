package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the session-error contract through the real inbound collector and connection facade. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositorySessionErrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun defaultImplementationsAndFreshRemoteReportNoError() =
        runTest {
            val (_, remote) = repo()
            assertNull(remote.observeSessionError("c1").first())
            assertNull(FakeConversationRepository().observeSessionError("c1").first())
            val testDouble = object : ConversationRepository by FakeConversationRepository() {}
            assertNull(testDouble.observeSessionError("c1").first())
        }

    @Test
    fun knownAndUnknownCodesReplaceOnlyTheirConversationAndReachLateSubscribers() =
        runTest {
            val (pump, remote) = repo()
            val values = collectErrors(remote, "c1")
            for (code in listOf("session.child_crashing", "session.blocked", "future.session_problem", "")) {
                pump.push(error("c1", code))
                runCurrent()
                assertEquals(code, remote.observeSessionError("c1").first())
                pump.push(error("c1", code))
                pump.push(error("c2", "another.problem"))
                runCurrent()
                assertEquals(code, remote.observeSessionError("c1").first())
                assertEquals("another.problem", remote.observeSessionError("c2").first())
            }
            assertEquals(listOf(null, "session.child_crashing", "session.blocked", "future.session_problem", ""), values)
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun withoutInteractiveEvenValidErrorsDoNothing() =
        runTest {
            val pump = FakeSessionPump()
            val remote = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            for (code in listOf("session.blocked", "session.child_crashing", "future.problem")) pump.push(error("c1", code))
            runCurrent()
            assertNull(remote.observeSessionError("c1").first())
            assertTrue(logs.isEmpty())
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun everyMissingOrWrongTypedRequiredFieldPreservesHeldErrorAndCollectorSurvives() =
        runTest {
            val (pump, remote) = repo()
            val values = collectErrors(remote, "c1")
            pump.push(error("c1", "session.blocked"))
            runCurrent()
            val valid = error("c1", "replacement").payload as JsonObject
            val badValues = listOf("null", "123", "true", "[]", "{}")
            for (field in listOf("conversation_id", "code", "message")) {
                pump.push(frame("session_error", JsonObject(valid - field).toString()))
                runCurrent()
                assertEquals("missing $field", "session.blocked", remote.observeSessionError("c1").first())
                for (bad in badValues) {
                    val payload = JsonObject(valid + (field to MobileJson.parseToJsonElement(bad)))
                    pump.push(frame("session_error", payload.toString()))
                    runCurrent()
                    assertEquals("$field=$bad", "session.blocked", remote.observeSessionError("c1").first())
                }
            }
            for (bad in listOf("null", "[]", "true", "123", "\"payload\"")) pump.push(frame("session_error", bad))
            runCurrent()
            assertEquals(listOf(null, "session.blocked"), values)
            assertNull(remote.observeSessionError("123").first())
            assertNull(remote.observeSessionError("true").first())
            pump.push(error("c1", "session.child_crashing"))
            runCurrent()
            assertEquals(listOf(null, "session.blocked", "session.child_crashing"), values)
        }

    @Test
    fun daemonProseAndUnknownCodeNeverEnterLogsOrThreadRows() =
        runTest {
            val (pump, remote) = repo()
            val prose = "sensitive queued content token=secret https://host/private"
            val code = "unknown-sensitive-code"
            val payload =
                buildJsonObject {
                    put("conversation_id", "c1")
                    put("code", code)
                    put("message", prose)
                    put("future_field", "ignored")
                }
            pump.push(frame("session_error", payload.toString()))
            runCurrent()
            assertEquals(code, remote.observeSessionError("c1").first())
            assertEquals(emptyList<ThreadItem>(), remote.observeMessages("c1").first())
            assertNull(remote.observeLastMessage("c1").first())
            assertTrue(logs.none { prose in it || code in it || "secret" in it || "c1" in it })
            assertTrue(logs.any { "event=session_error" in it })
        }

    @Test
    fun eachRecognizedNonIdleTurnStateClearsOnlyItsConversation() =
        runTest {
            val (pump, remote) = repo()
            for (state in listOf("thinking", "responding")) {
                pump.push(error("c1", "session.blocked"))
                pump.push(error("c2", "session.child_crashing"))
                runCurrent()
                pump.push(turn("c2", state))
                runCurrent()
                assertEquals("session.blocked", remote.observeSessionError("c1").first())
                assertNull(remote.observeSessionError("c2").first())
                pump.push(turn("c1", state))
                runCurrent()
                assertNull(remote.observeSessionError("c1").first())
            }
        }

    @Test
    fun idleUnknownAndMalformedTurnStatesLeaveHeldErrorIntact() =
        runTest {
            val (pump, remote) = repo()
            pump.push(error("c1", "session.blocked"))
            pump.push(error("123", "session.blocked"))
            pump.push(error("true", "session.blocked"))
            runCurrent()
            val invalid =
                listOf(
                    """{"conversation_id":"c1","state":"idle"}""",
                    """{"conversation_id":"c1","state":"future_phase"}""",
                    """{"conversation_id":"c1"}""",
                    """{"state":"thinking"}""",
                    """{"conversation_id":"c1","state":null}""",
                    """{"conversation_id":"c1","state":123}""",
                    """{"conversation_id":123,"state":"thinking"}""",
                    """{"conversation_id":true,"state":"responding"}""",
                    """{"conversation_id":{},"state":"responding"}""",
                    "null",
                )
            for (payload in invalid) {
                pump.push(frame("turn_state", payload))
                runCurrent()
                for (id in listOf("c1", "123", "true")) assertEquals(payload, "session.blocked", remote.observeSessionError(id).first())
            }
            pump.push(turn("c1", "responding"))
            runCurrent()
            assertNull(remote.observeSessionError("c1").first())
        }

    @Test
    fun otherLiveFramesNeverClearTheSessionError() =
        runTest {
            val (pump, remote) = repo()
            pump.push(error("c1", "session.child_crashing"))
            runCurrent()
            val frames =
                listOf(
                    frame("assistant_delta", """{"conversation_id":"c1","turn_id":"t1","seq":0,"text":"reply"}"""),
                    frame("tool_use", """{"conversation_id":"c1","turn_id":"t1","tool_use_id":"tu1","name":"Bash","input_summary":"ls"}"""),
                    frame(
                        "tool_result",
                        buildJsonObject {
                            put("conversation_id", "c1")
                            put("turn_id", "t1")
                            put("tool_use_id", "tu1")
                            put("is_error", false)
                            put("result_summary", "ok")
                        }.toString(),
                    ),
                    frame("turn_end", """{"conversation_id":"c1","turn_id":"t1","stop_reason":"end_turn"}"""),
                    frame("queue_state", """{"conversation_id":"c1","queued":[]}"""),
                    frame("stall", """{"conversation_id":"c1"}"""),
                    frame(
                        "session_transition",
                        buildJsonObject {
                            put("conversation_id", "c1")
                            put("previous_session_id", "s1")
                            put("new_session_id", "s2")
                            put("reason", "clear")
                            put("occurred_at", "2026-10-03T10:00:00Z")
                        }.toString(),
                    ),
                )
            for (frame in frames) {
                pump.push(frame)
                runCurrent()
                assertEquals(frame.type, "session.child_crashing", remote.observeSessionError("c1").first())
            }
        }

    @Test
    fun plainSendClearsBeforeAckAndPreservesOtherConversations() = runTest { assertSendClears(attachments = false) }

    @Test
    fun attachmentSendClearsBeforeAckAndPreservesOtherConversations() = runTest { assertSendClears(attachments = true) }

    private suspend fun TestScope.assertSendClears(attachments: Boolean) {
        val (pump, remote) = repo()
        pump.push(error("c1", "session.blocked"))
        pump.push(error("c2", "session.child_crashing"))
        runCurrent()
        val send =
            async {
                if (attachments) {
                    remote.sendMessage("c1", "", listOf(MessageAttachment("attachment-id", "file.txt", "text/plain")))
                } else {
                    remote.sendMessage("c1", "try again")
                }
            }
        runCurrent()
        assertFalse(send.isCompleted)
        assertNull(remote.observeSessionError("c1").first())
        assertEquals("session.child_crashing", remote.observeSessionError("c2").first())
        assertEquals(1, pump.sent.count { it.type == "send_message" })
        pump.ack()
        runCurrent()
        send.await()
        assertNull(remote.observeSessionError("c1").first())
    }

    @Test
    fun failedSendsDoNotRestoreTheErrorInEitherOverload() =
        runTest {
            for (attachments in listOf(false, true)) {
                val (pump, remote) = repo()
                pump.push(error("c1", "session.blocked"))
                runCurrent()
                pump.acceptSends = false
                val result =
                    runCatching {
                        if (attachments) {
                            remote.sendMessage("c1", "", listOf(MessageAttachment("id", "file.txt", "text/plain")))
                        } else {
                            remote.sendMessage("c1", "retry")
                        }
                    }
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertNull(remote.observeSessionError("c1").first())
            }
        }

    @Test
    fun anErrorReceivedDuringSendSurvivesItsAck() =
        runTest {
            val (pump, remote) = repo()
            pump.push(error("c1", "session.blocked"))
            runCurrent()
            val send = async { remote.sendMessage("c1", "retry") }
            runCurrent()
            assertNull(remote.observeSessionError("c1").first())
            pump.push(error("c1", "session.child_crashing"))
            runCurrent()
            pump.ack()
            runCurrent()
            send.await()
            assertEquals("session.child_crashing", remote.observeSessionError("c1").first())
        }

    @Test
    fun inboundCompletionClearsTheOldConnectionProjection() =
        runTest {
            val (pump, remote) = repo()
            val values = collectErrors(remote, "c1")
            pump.push(error("c1", "session.blocked"))
            runCurrent()
            pump.close()
            runCurrent()
            assertEquals(listOf(null, "session.blocked", null), values)
        }

    @Test
    fun connectionScopeCancellationClearsHeldErrors() =
        runTest {
            val pump = FakeSessionPump()
            val connectionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val remote = RemoteConversationRepository(pump, connectionScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
            try {
                pump.push(error("c1", "session.blocked"))
                runCurrent()
                assertEquals("session.blocked", remote.observeSessionError("c1").first())
            } finally {
                connectionScope.cancel()
                runCurrent()
            }
            assertNull(remote.observeSessionError("c1").first())
        }

    @Test
    fun facadeWithoutHeldReadingsAlsoForwardsTheCurrentError() =
        runTest {
            val (pump, remote) = repo()
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(remote))
            val values = collectErrors(facade, "c1")
            pump.push(error("c1", "session.blocked"))
            runCurrent()
            pump.push(error("c1", "session.child_crashing"))
            runCurrent()
            assertEquals(listOf(null, "session.blocked", "session.child_crashing"), values)
            assertEquals("session.child_crashing", facade.observeSessionError("c1").first())
        }

    @Test
    fun facadeForwardsLiveStateBlanksTheGapAndStartsReconnectEmptyWithoutSending() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current, heldReadings = HostReadings())
            val values = collectErrors(facade, "c1")
            val (oldPump, oldRemote) = repo()
            oldPump.push(error("c1", "session.blocked"))
            runCurrent()
            current.value = oldRemote
            runCurrent()
            assertEquals(listOf(null, "session.blocked"), values)
            current.value = null
            runCurrent()
            assertNull(facade.observeSessionError("c1").first())
            oldPump.push(error("c1", "old-detached-code"))
            runCurrent()
            assertEquals(listOf(null, "session.blocked", null), values)
            val (newPump, newRemote) = repo()
            current.value = newRemote
            runCurrent()
            assertNull(facade.observeSessionError("c1").first())
            newPump.push(error("c2", "session.blocked"))
            runCurrent()
            assertNull(facade.observeSessionError("c1").first())
            newPump.push(error("c1", "session.child_crashing"))
            runCurrent()
            assertEquals("session.child_crashing", facade.observeSessionError("c1").first())
            assertEquals(listOf(null, "session.blocked", null, null, "session.child_crashing"), values)
            assertTrue(oldPump.sent.isEmpty())
            assertTrue(newPump.sent.isEmpty())
        }

    @Test
    fun cachingDecoratorForwardsCurrentAndChangingSessionErrors() =
        runTest {
            val (pump, remote) = repo()
            val caching = CachingConversationRepository(remote, FileConversationCache(tmp.root), "host")
            pump.push(error("c1", "session.blocked"))
            runCurrent()
            val values = collectErrors(caching, "c1")
            pump.push(error("c1", "session.child_crashing"))
            runCurrent()
            assertEquals(listOf("session.blocked", "session.child_crashing"), values)
            assertTrue(
                tmp.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun TestScope.collectErrors(
        repo: ConversationRepository,
        id: String,
    ): MutableList<String?> {
        val values = mutableListOf<String?>()
        backgroundScope.launch { repo.observeSessionError(id).toList(values) }
        runCurrent()
        return values
    }

    private fun error(
        id: String,
        code: String,
    ): Envelope =
        frame(
            "session_error",
            buildJsonObject {
                put("conversation_id", id)
                put("code", code)
                put("message", "discard this daemon prose")
            }.toString(),
        )

    private fun turn(
        id: String,
        state: String,
    ): Envelope =
        frame(
            "turn_state",
            buildJsonObject {
                put("conversation_id", id)
                put("state", state)
            }.toString(),
        )

    private fun frame(
        type: String,
        payload: String,
    ): Envelope = Envelope(1, type, "2026-10-03T10:00:00Z", MobileJson.parseToJsonElement(payload))

    private class FakeSessionPump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var acceptSends = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return acceptSends
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun close() {
            channel.close()
        }

        fun ack() {
            push(Envelope(2, "ack", "2026-10-03T10:00:00Z", JsonObject(emptyMap()), inReplyTo = sent.last().id))
        }
    }
}
