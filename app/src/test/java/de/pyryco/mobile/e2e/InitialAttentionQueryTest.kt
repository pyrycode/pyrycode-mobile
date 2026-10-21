package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadQueries
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.recording.RecordedSuiteTape
import de.pyryco.mobile.recording.SuiteStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

@OptIn(ExperimentalCoroutinesApi::class)
class InitialAttentionQueryTest {
    private val savedSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = savedSink
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
            val lease = checkNotNull(replay.queries.acquire("b"))
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
                    sent += request
                    for (step in tape.request(PHONE, request)) {
                        if (step.owner == PHONE) {
                            val message = checkNotNull(step.message)
                            if (message.type == "modal_dismissed") phoneDismissed = true else pending += message
                        } else {
                            peerDismissed = true
                        }
                    }
                    true
                },
                { 0L },
            )

        fun deliverBatch() {
            queries.accept(pending.removeAt(0))
        }

        fun deliverTerminal() {
            queries.accept(pending.removeAt(0))
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
