package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the #351 connection-scoped coordinator: per live `currentConnection` transport
 * it starts a fresh Noise pump and constructs a [RemoteConversationRepository] against it, publishing
 * the live repository on [RelayRepositoryCoordinator.currentRepository]; on drop it cancels the
 * connection scope and closes the pump (wiping keys). JUnit4 + `runTest`, a `StandardTestDispatcher`
 * driven with `runCurrent()`, hand fakes (no MockK) — mirroring `RelayConnectionSupervisorTest` /
 * `RemoteConversationRepositoryTest`. The repository is the **real** one so AC #4 exercises the genuine
 * read paths over the fake pump. Every test ends with `coordinator.close()` so the perpetual
 * `connections.collect` does not hang `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRepositoryCoordinatorTest {
    // ---- AC #2: a live connection starts a pump and publishes a repository ----------------------

    @Test
    fun liveConnection_startsPumpAndPublishesRepository() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()

            assertNotNull(env.coordinator.currentRepository.value)
            assertEquals(1, env.pumps.size)
            assertTrue("the per-connection pump is started", env.pumps.single().started)

            env.coordinator.close()
        }

    // ---- AC #2/#3: between connections the published repository is null --------------------------

    @Test
    fun betweenConnections_repositoryIsNull() =
        runTest {
            val env = newEnv()
            assertNull("no connection yet", env.coordinator.currentRepository.value)

            env.connections.value = StubRelayTransport()
            runCurrent()
            assertNotNull(env.coordinator.currentRepository.value)

            env.connections.value = null
            runCurrent()
            assertNull("the connection cleared", env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // ---- AC #4: the conversation-list read path functions end-to-end over the live pump ----------

    @Test
    fun listPath_overLivePump_yieldsProjectedConversationList() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val repo = requireNotNull(env.coordinator.currentRepository.value)
            val pump = env.pumps.single()

            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()

            // The subscription drove a list_conversations request over the coordinator-built pump.
            assertEquals("list_conversations", pump.sent.single().type)

            // Wire order is ascending by last_used_at; the projection sorts most-recent-first.
            pump.push(
                conversationsEnvelope(
                    """
                    {"conversations":[
                      {"id":"older","name":"Older","is_promoted":true,"cwd":"/p/older","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"},
                      {"id":"newer","name":"Newer","is_promoted":true,"cwd":"/p/newer","last_message_ts":"2026-05-08T11:00:00Z","last_used_at":"2026-05-08T11:00:00Z"}
                    ]}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(listOf("newer", "older"), emissions.last().map { it.id })

            env.coordinator.close()
        }

    // ---- AC #4: the thread read path functions end-to-end over the live pump --------------------

    @Test
    fun threadPath_overLivePump_yieldsOrderedThread() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val repo = requireNotNull(env.coordinator.currentRepository.value)
            val pump = env.pumps.single()

            val emissions = mutableListOf<List<ThreadItem>>()
            backgroundScope.launch { repo.observeMessages("c1").collect { emissions += it } }
            runCurrent()

            // The subscription drove a backfill_since request over the coordinator-built pump.
            assertEquals("backfill_since", pump.sent.single().type)

            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "m1", "user", "first"),
                        chunkRow("c1", "m2", "assistant", "second"),
                    ),
                ),
            )
            runCurrent()
            pump.push(messageEnvelope("c1", "m3", "user", "live", "2026-05-31T12:00:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "m2", "m3"), messageIds(emissions.last()))

            env.coordinator.close()
        }

    // ---- AC #3: a dropped connection closes the pump and stops the repository collector ----------

    @Test
    fun connectionDrop_closesPumpAndStopsCollector() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val repo1 = requireNotNull(env.coordinator.currentRepository.value)
            val pump1 = env.pumps.single()

            env.connections.value = null
            runCurrent()

            // Key-wipe invariant: dropping the pump reference closed it (wiping session keys); no repo.
            assertTrue("the pump is closed on teardown (keys wiped)", pump1.closed)
            assertNull(env.coordinator.currentRepository.value)

            // The repository's single inbound collector is cancelled with the child scope: a frame
            // pushed to the old pump after teardown surfaces nowhere — observing the dead repo's list
            // never projects (its projection writer is gone).
            val leaked = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo1.observeConversations(ConversationFilter.All).collect { leaked += it } }
            runCurrent()
            pump1.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"ghost","name":"Ghost","is_promoted":true,"cwd":"/g","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(emptyList<List<Conversation>>(), leaked)

            env.coordinator.close()
        }

    // ---- AC #3: reconnect builds a fresh pump + repository with no state carried over ------------

    @Test
    fun reconnect_buildsFreshPumpAndRepository_noCarryover() =
        runTest {
            val env = newEnv()

            // Connection 1 loads a snapshot containing c1.
            env.connections.value = StubRelayTransport()
            runCurrent()
            val repo1 = requireNotNull(env.coordinator.currentRepository.value)
            val pump1 = env.pumps[0]
            val list1 = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo1.observeConversations(ConversationFilter.All).collect { list1 += it } }
            runCurrent()
            pump1.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"One","is_promoted":true,"cwd":"/c1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("c1"), list1.last().map { it.id })

            // Drop, then reconnect over a fresh transport.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()

            val repo2 = requireNotNull(env.coordinator.currentRepository.value)
            val pump2 = env.pumps[1]

            assertEquals(2, env.pumps.size)
            assertTrue("the previous pump was closed", pump1.closed)
            assertTrue("the fresh pump is started", pump2.started)
            assertFalse("the fresh pump is live", pump2.closed)
            assertNotSame("a distinct pump per connection", pump1, pump2)
            assertNotSame("a distinct repository per connection", repo1, repo2)

            // No projection state carried over: before any snapshot on connection 2, the new
            // repository's list yields nothing — c1 did not leak across the connection boundary.
            val list2 = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo2.observeConversations(ConversationFilter.All).collect { list2 += it } }
            runCurrent()
            assertEquals(emptyList<List<Conversation>>(), list2)

            env.coordinator.close()
        }

    // ---- AC #3: closing the coordinator tears down the active connection -------------------------

    @Test
    fun close_tearsDownActiveConnection() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            env.coordinator.close()
            runCurrent()

            assertTrue("close() wipes the active pump", pump.closed)
            assertNull(env.coordinator.currentRepository.value)
        }

    // ---- #365: connect-time push-token re-registration ------------------------------------------

    // AC #1: a fresh session reaching Open with a stored token sends exactly one register_push_token
    // carrying the live device name — never an empty device_name.
    @Test
    fun onOpen_withStoredToken_registersOnceWithLiveDeviceName() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.open()
            runCurrent()

            val sent = pump.sent.single { it.type == "register_push_token" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"platform":"fcm","token":"fcm-tok","device_name":"Pixel-8"}"""),
                sent.payload,
            )

            // Resolve the awaiting coroutine cleanly before teardown.
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            env.coordinator.close()
        }

    // AC #2: no stored token → the connect-time hook is a no-op (sends nothing, does not error).
    @Test
    fun onOpen_withNoStoredToken_sendsNothing() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { null })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.open()
            runCurrent()

            assertTrue(
                "no register_push_token when no token is stored",
                pump.sent.none { it.type == "register_push_token" },
            )
            assertNotNull("the repository is still published", env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // AC #3: a reconnect re-registers — exactly once per connection (fresh hook per connection, not a
    // leaked single-fire).
    @Test
    fun reconnect_reRegistersOncePerConnection() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })

            // Connection 1 reaches Open → one register frame on pump 1.
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump1 = env.pumps[0]
            pump1.open()
            runCurrent()
            val sent1 = pump1.sent.single { it.type == "register_push_token" }
            pump1.push(ackEnvelope(sent1.id))
            runCurrent()

            // Drop, then reconnect over a fresh transport → one register frame on pump 2.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump2 = env.pumps[1]
            pump2.open()
            runCurrent()
            val sent2 = pump2.sent.single { it.type == "register_push_token" }
            pump2.push(ackEnvelope(sent2.id))
            runCurrent()

            assertEquals(1, pump1.sent.count { it.type == "register_push_token" })
            assertEquals(1, pump2.sent.count { it.type == "register_push_token" })

            env.coordinator.close()
        }

    // AC #4: a registration failure on connect does not crash or wedge the connection.
    @Test
    fun registrationFailure_doesNotCrashOrWedgeTheConnection() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })

            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump1 = env.pumps.single()
            pump1.open()
            runCurrent()

            val sent = pump1.sent.single { it.type == "register_push_token" }
            pump1.push(errorEnvelope(sent.id, code = "server.binary_busy", retryable = true))
            runCurrent()

            // The failure was swallowed: the repository is still live.
            assertNotNull(env.coordinator.currentRepository.value)

            // A subsequent drop/reconnect still works (the connection is not wedged).
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            assertNotNull(env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // AC #1 (boundary): a session that closes before ever reaching Open registers nothing.
    @Test
    fun preOpenClosed_abortsWithoutRegistering() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.closeState(null)
            runCurrent()

            assertTrue(
                "no register_push_token when the session closed before Open",
                pump.sent.none { it.type == "register_push_token" },
            )

            env.coordinator.close()
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun TestScope.newEnv(
        deviceName: String = "",
        pushToken: suspend () -> String? = { null },
    ): Env {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<FakeManagedPump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections = connections,
                createPump = { FakeManagedPump().also { pumps += it } },
                dispatcher = StandardTestDispatcher(testScheduler),
                deviceName = deviceName,
                pushToken = pushToken,
            )
        coordinator.start()
        return Env(connections, pumps, coordinator)
    }

    private class Env(
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: MutableList<FakeManagedPump>,
        val coordinator: RelayRepositoryCoordinator,
    )

    private fun messageIds(thread: List<ThreadItem>): List<String> = thread.map { (it as ThreadItem.MessageItem).message.id }

    private fun chunkRow(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
    ): String = """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}"""

    private fun conversationsEnvelope(rawConversationsPayload: String): Envelope =
        Envelope(
            id = 1L,
            type = "conversations",
            ts = TS,
            payload = MobileJson.parseToJsonElement(rawConversationsPayload),
        )

    private fun messageChunkEnvelope(rows: List<String>): Envelope =
        Envelope(
            id = 1L,
            type = "message_chunk",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"messages":[${rows.joinToString(",")}]}"""),
        )

    private fun messageEnvelope(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
        ts: String,
    ): Envelope =
        Envelope(
            id = 1L,
            type = "message",
            ts = ts,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}""",
                ),
        )

    /** Empty-`ack` reply correlated to [inReplyTo] — the register_push_token success signal. */
    private fun ackEnvelope(inReplyTo: Long): Envelope =
        Envelope(id = 99L, type = "ack", ts = TS, payload = MobileJson.parseToJsonElement("{}"), inReplyTo = inReplyTo)

    /** A server `error` reply correlated to [inReplyTo] — exercises the swallow path. */
    private fun errorEnvelope(
        inReplyTo: Long,
        code: String,
        retryable: Boolean = true,
    ): Envelope =
        Envelope(
            id = 99L,
            type = "error",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"boom","retryable":$retryable}"""),
            inReplyTo = inReplyTo,
        )

    /** Channel-backed [ManagedSessionPump] fake: unlimited inbound buffer, a drivable lifecycle [state],
     *  and start/close lifecycle flags. Defaults to [PumpState.Handshaking] so tests that never drive it
     *  to Open keep the connect-time hook dormant (the register frame never appears). */
    private class FakeManagedPump : ManagedSessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        private val mutableState = MutableStateFlow<PumpState>(PumpState.Handshaking)

        override val state: StateFlow<PumpState> = mutableState.asStateFlow()

        val sent = mutableListOf<Envelope>()

        var started = false
            private set

        var closed = false
            private set

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
            inboundChannel.close()
        }

        /** Drive the handshake to completion: the connect-time hook awaits this transition. */
        fun open(connId: String = "c1") {
            mutableState.value = PumpState.Open(connId)
        }

        /** Drive a terminal close without ever reaching Open (pre-Open fault). */
        fun closeState(cause: Throwable? = null) {
            mutableState.value = PumpState.Closed(cause)
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    /** A non-null transport token; the [RelayRepositoryCoordinator] only hands the reference to
     *  `createPump` and never collects its streams, so the fake pump ignores it entirely. */
    private class StubRelayTransport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2): Boolean = true

        override fun close() = Unit
    }

    private companion object {
        const val TS = "2026-05-31T00:00:00Z"
    }
}
