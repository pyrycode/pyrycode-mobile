package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadQueries
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.recording.RecordedSuiteTape
import de.pyryco.mobile.recording.SuiteStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class InitialAttentionQueryTest {
    private val savedSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = savedSink
    }

    @Test fun destinationGateAllowsMetadataBeforeInitialQueryAndDefersOnlyAcquisition() =
        runTest {
            val replay = Replay()
            ThreadAcquisitionGate.arm(replay.thread, "b", 30_000).use { gate ->
                val source =
                    object : ConversationRepository by FakeConversationRepository() {
                        override val negotiatedThread = replay.thread
                    }
                val destination = checkNotNull(TappingConversationRepository(source, tapNegotiated = true).negotiatedThread)
                val acquired = async { gate.awaitAcquired() }
                var metadataStarted = false
                val navigation =
                    async {
                        metadataStarted = true
                        destination.acquire("b", replay.connection)
                    }
                runCurrent()
                assertTrue("destination metadata must run before the initial query exists", metadataStarted)
                assertFalse("only acquisition waits", navigation.isCompleted)
                assertFalse(acquired.isCompleted)
                assertTrue("the gate must not manufacture a query", replay.sent.isEmpty())
                replay.queries.open("b")
                replay.deliverBatch()
                runCurrent()
                assertFalse(navigation.isCompleted)
                replay.deliverTerminal()
                runCurrent()
                replay.assertExactQueriesAndComplete()
                acquired.await()
                checkNotNull(navigation.await()).close()
            }
        }

    @Test fun unarmedOrDifferentTargetAcquisitionKeepsProviderAdmission() =
        runTest {
            val replay = Replay()
            ThreadAcquisitionGate.arm(replay.thread, "other", 30_000).use {
                val destination = ThreadAcquisitionGate.negotiated(replay.thread)
                val lease = checkNotNull(destination.acquire("b", replay.connection))
                assertEquals(1, replay.sent.size)
                replay.deliverBatch()
                replay.deliverTerminal()
                lease.close()
            }
            val after = Replay()
            val lease = checkNotNull(ThreadAcquisitionGate.negotiated(after.thread).acquire("b", after.connection))
            assertEquals(1, after.sent.size)
            after.deliverBatch()
            after.deliverTerminal()
            lease.close()
        }

    @Test fun acquisitionGateRejectsRetiredConnectionAndClosedScope() =
        runTest {
            for (condition in listOf("connection", "scope")) {
                val replay = Replay()
                val gate = ThreadAcquisitionGate.arm(replay.thread, "b", 30_000)
                try {
                    replay.queries.open("b")
                    val destination = ThreadAcquisitionGate.negotiated(replay.thread)
                    val navigation = async { destination.acquire("b", replay.connection) }
                    runCurrent()
                    assertFalse(navigation.isCompleted)
                    if (condition == "connection") replay.support.value = ThreadSupport.Supported(Any()) else gate.close()
                    replay.deliverBatch()
                    replay.deliverTerminal()
                    runCurrent()
                    assertEquals(null, navigation.await())
                    assertEquals(1, replay.sent.size)
                    assertFalse(replay.phoneDismissed)
                } finally {
                    gate.close()
                }
            }
        }

    @Test fun acquisitionGateCannotSpendAReplacementGenerationsCertificate() =
        runTest {
            val replay = Replay()
            ThreadAcquisitionGate.arm(replay.thread, "b", 30_000).use {
                val destination = ThreadAcquisitionGate.negotiated(replay.thread)
                val navigation = async { destination.acquire("b", replay.connection) }
                runCurrent()
                replay.store.reset("host", "b", "e")
                var id = 0L
                val replacement =
                    AuthoritativeThreadQueries(
                        "host",
                        replay.store,
                        AuthoritativeThreadRoute("host", replay.store),
                        { true },
                        { ++id },
                        { true },
                        { 0L },
                    )
                replacement.open("b")
                replacement.accept(frame(2, "thread_items", WINDOW, 1))
                runCurrent()
                assertEquals(13L, replay.store.current("host", "b").checkpoint)
                assertFalse("a new generation's real certificate cannot release the old gate", navigation.isCompleted)
                assertTrue(replay.sent.isEmpty())
                navigation.cancelAndJoin()
                replacement.abandon()
            }
        }

    @Test fun activeInitialQueryDefersAcquisitionUntilCertified() =
        runTest {
            val replay = Replay()
            replay.queries.open("b")
            val navigation =
                async {
                    awaitInitialAttentionQuery(replay.store.observe("host", "b"))
                    checkNotNull(replay.queries.acquire("b"))
                }
            runCurrent()
            assertFalse("navigation must wait for the active query", navigation.isCompleted)
            replay.deliverBatch()
            runCurrent()
            assertFalse("a batch is not certification", navigation.isCompleted)
            assertEquals(1, replay.sent.size)
            assertTrue("peer may progress independently", replay.peerDismissed)
            assertFalse("peer progress cannot deliver the phone dismissal", replay.phoneDismissed)
            replay.deliverTerminal()
            runCurrent()
            assertTrue(navigation.isCompleted)
            replay.assertExactQueriesAndComplete()
            navigation.await().close()
        }

    @Test fun completedInitialQueryAcquiresWithoutAnotherWait() =
        runTest {
            val replay = Replay()
            replay.queries.open("b")
            replay.deliverBatch()
            replay.deliverTerminal()
            awaitInitialAttentionQuery(replay.store.observe("host", "b"))
            val lease =
                ThreadAcquisitionGate.arm(replay.thread, "b", 30_000).use {
                    checkNotNull(ThreadAcquisitionGate.negotiated(replay.thread).acquire("b", replay.connection))
                }
            replay.assertExactQueriesAndComplete()
            lease.close()
        }

    @Test fun unfencedAcquisitionLeavesRequiredQueryAndPhoneCompletionPending() {
        val replay = Replay()
        replay.queries.open("b")
        val lease = checkNotNull(replay.queries.acquire("b"))
        replay.deliverBatch()
        replay.deliverTerminal()
        assertEquals("active acquisition was coalesced", 1, replay.sent.size)
        assertTrue(replay.peerDismissed)
        assertFalse(replay.phoneDismissed)
        assertThrows(IllegalStateException::class.java) { replay.tape.assertComplete() }
        lease.close()
    }

    @Test fun cancellationCannotAcquireOrCompleteMissingQuery() =
        runTest {
            val replay = Replay()
            replay.queries.open("b")
            var acquired = false
            val navigation =
                async {
                    awaitInitialAttentionQuery(replay.store.observe("host", "b"))
                    acquired = true
                    replay.queries.acquire("b")
                }
            runCurrent()
            navigation.cancelAndJoin()
            replay.deliverBatch()
            replay.deliverTerminal()
            assertFalse(acquired)
            assertEquals(1, replay.sent.size)
            assertFalse(replay.phoneDismissed)
            assertThrows(IllegalStateException::class.java) { replay.tape.assertComplete() }
        }

    @Test fun otherConversationAndRetiredGenerationCannotCertifyInitialQuery() =
        runTest {
            for (condition in listOf("conversation", "generation")) {
                val store = AuthoritativeThreadStore()
                var id = 0L
                val queries =
                    AuthoritativeThreadQueries(
                        "host",
                        store,
                        AuthoritativeThreadRoute("host", store),
                        { true },
                        { ++id },
                        { true },
                        { 0L },
                    )
                queries.open("b")
                val navigation = async { awaitInitialAttentionQuery(store.observe("host", "b")) }
                runCurrent()
                val payload = if (condition == "conversation") WINDOW.replace("\"b\"", "\"other\"") else WINDOW
                if (condition == "generation") store.reset("host", "b", "e")
                queries.accept(frame(2, "thread_items", payload, 1))
                runCurrent()
                assertFalse("$condition cannot certify B's initial query", navigation.isCompleted)
                assertEquals(0L, store.current("host", "b").checkpoint)
                navigation.cancelAndJoin()
                queries.abandon()
            }
        }

    @Test fun fullQueryPayloadOwnerAndCountRemainStrict() {
        for (condition in listOf("owner", "generation", "conversation", "epoch", "checkpoint", "extra", "duplicate")) {
            val replay = Replay()
            replay.queries.open("b")
            replay.deliverBatch()
            replay.deliverTerminal()
            var request = query(102, "e", 13)
            val owner =
                when (condition) {
                    "owner" -> "peer/host/0"
                    "generation" -> "phone/host/1"
                    else -> PHONE
                }
            val raw = request.payload as JsonObject
            request =
                when (condition) {
                    "conversation" -> request.copy(payload = JsonObject(raw + ("conversation_id" to JsonPrimitive("other"))))
                    "epoch" -> query(102, "wrong", 13)
                    "checkpoint" -> query(102, "e", 12)
                    "extra" -> request.copy(payload = JsonObject(raw + ("limit" to JsonPrimitive(50))))
                    else -> request
                }
            if (condition == "duplicate") {
                replay.tape.request(owner, request)
                request = request.copy(id = 103)
            }
            assertThrows(condition, IllegalStateException::class.java) { replay.tape.request(owner, request) }
            assertThrows(condition, IllegalStateException::class.java) { replay.tape.assertComplete() }
        }
    }

    @Test fun observationCheckpointCannotReleaseTheSecondReplyEarly() {
        val checkpoint = frame(30, "test-control:checkpoint", """{"body":"observed"}""")
        val rows =
            listOf(
                SuiteStep("client", PHONE, query(1, "", 0)),
                SuiteStep("daemon", PHONE, frame(2, "thread_items", WINDOW, 1)),
                SuiteStep("client", "control", checkpoint),
                SuiteStep("client", PHONE, query(3, "e", 13)),
                SuiteStep("daemon", PHONE, frame(4, "thread_items", TAIL, 3)),
            )
        val tape = RecordedSuiteTape(rows, literalProducerIdentities = true)
        tape.request(PHONE, query(101, "", 0))
        assertTrue(tape.request(PHONE, query(102, "e", 13)).isEmpty())
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val released = tape.request("control", checkpoint)
        assertEquals(listOf(102L), released.map { it.message?.inReplyTo })
        tape.assertComplete()
    }

    private class Replay {
        val store = AuthoritativeThreadStore()
        val connection = Any()
        val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(connection))
        val progress = ThreadQueryReadiness.register("host") { (support.value as? ThreadSupport.Supported)?.connection === connection }
        val sent = mutableListOf<Envelope>()
        private val pending = mutableListOf<Envelope>()
        var peerDismissed = false
        var phoneDismissed = false
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("client", PHONE, query(1, "", 0)),
                    SuiteStep("daemon", PHONE, frame(2, "thread_items", BATCH, 1)),
                    SuiteStep("daemon", PHONE, frame(3, "thread_items", WINDOW, 1)),
                    SuiteStep("client", PHONE, query(4, "e", 13)),
                    SuiteStep("daemon", "peer/host/0", frame(5, "modal_dismissed")),
                    SuiteStep("daemon", PHONE, frame(6, "thread_items", TAIL, 4)),
                    SuiteStep("daemon", PHONE, frame(7, "modal_dismissed")),
                ),
                literalProducerIdentities = true,
            )
        private var nextId = 100L
        val queries =
            AuthoritativeThreadQueries(
                "host",
                store,
                AuthoritativeThreadRoute("host", store),
                { true },
                { ++nextId },
                { request ->
                    progress.sending(request)
                    sent += request
                    for (step in tape.request(PHONE, request)) {
                        if (step.owner == PHONE) {
                            val message = checkNotNull(step.message)
                            if (message.type == "modal_dismissed") phoneDismissed = true else pending += message
                        } else {
                            peerDismissed = true
                        }
                    }
                    progress.sent(request.id, true)
                    true
                },
                { 0L },
            )

        val thread =
            object : NegotiatedThread() {
                override val host = "host"
                override val store = this@Replay.store
                override val support = this@Replay.support

                override fun observe(conversationId: String) = store.observe(host, conversationId)

                override suspend fun open(conversationId: String) = queries.open(conversationId)

                override suspend fun acquire(
                    conversationId: String,
                    connection: Any,
                ): Closeable? =
                    if ((support.value as? ThreadSupport.Supported)?.connection === connection) queries.acquire(conversationId) else null

                override fun older(conversationId: String) = queries.older(conversationId)

                override fun cancel(conversationId: String) = queries.cancel(conversationId)
            }

        fun deliverBatch() {
            val reply = pending.removeAt(0)
            queries.accept(reply)
            progress.consumed(reply)
        }

        fun deliverTerminal() {
            val reply = pending.removeAt(0)
            queries.accept(reply)
            progress.consumed(reply)
        }

        fun assertExactQueriesAndComplete() {
            assertEquals(listOf(query(101, "", 0), query(102, "e", 13)).map { it.payload }, sent.map { it.payload })
            assertEquals(listOf("thread_catch_up", "thread_catch_up"), sent.map { it.type })
            assertTrue(phoneDismissed)
            deliverTerminal()
            assertEquals(13L, store.current("host", "b").checkpoint)
            tape.assertComplete()
        }
    }

    private companion object {
        const val PHONE = "phone/host/0"
        const val BATCH = """{"conversation_id":"b","epoch":"e","version":13,"items":[],"final":false}"""
        const val WINDOW = """{"conversation_id":"b","epoch":"e","version":13,"items":[],"final":true,
            "lower_order":0,"upper_order":14,"continuation":0,"older_exists":false}"""
        const val TAIL = """{"conversation_id":"b","epoch":"e","version":13,"items":[],"final":true,"from_version":13}"""

        fun query(
            id: Long,
            epoch: String,
            version: Int,
        ) = frame(id, "thread_catch_up", """{"conversation_id":"b","epoch":"$epoch","version":$version}""")

        fun frame(
            id: Long,
            type: String,
            payload: String = "{}",
            reply: Long? = null,
        ) = Envelope(id, type, "2026-10-21T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = reply)
    }
}
