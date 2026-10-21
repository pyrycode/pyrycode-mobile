package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class RecordedPhoneTeardownTest {
    private var savedLogSink = RelayLog.sink

    @Before fun muteDecoderLogs() {
        savedLogSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreDecoderLogs() {
        RelayLog.sink = savedLogSink
    }

    private val owner = "phone/host/1"
    private val query = frame(13, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":28}""")
    private val reply =
        frame(
            48,
            "thread_items",
            """{"conversation_id":"chat","epoch":"epoch","version":28,"items":[],"final":true,"from_version":28}""",
            13,
        )
    private val end = SuiteStep("client", "control", frame(4, "test-control:test-end", """{"body":""}"""))

    @Test fun foregroundReadinessCannotPassTapeReleaseBeforeHubEnqueue() =
        runTest {
            val released = CountDownLatch(1)
            val enqueue = CountDownLatch(1)
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, query),
                        SuiteStep("daemon", owner, reply),
                        end,
                        SuiteStep("close", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val hub =
                SuiteConnections.replaying(backgroundScope, tape, beforeRequestEnqueue = {
                    released.countDown()
                    check(enqueue.await(5, TimeUnit.SECONDS))
                })
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val sending = async(Dispatchers.IO) { pump.send(query.copy(id = 90)) }
                check(released.await(5, TimeUnit.SECONDS))
                assertTrue("Tape release precedes the held hub enqueue", tape.phoneWorkBeforeTestEndReady())
                val checking = CountDownLatch(1)
                val worker = AtomicReference<Thread>()
                val foreground =
                    async(Dispatchers.IO) {
                        worker.set(Thread.currentThread())
                        checking.countDown()
                        hub.beforePhoneTeardown()
                    }
                check(checking.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!foreground.isCompleted && worker.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    runCurrent()
                    Thread.yield()
                }
                assertFalse("Foreground fence passed a request paused before enqueue", foreground.isCompleted)
                assertEquals("Readiness must acquire the request/enqueue monitor", Thread.State.BLOCKED, worker.get().state)
                enqueue.countDown()
                assertTrue(sending.await())
                runCurrent()
                foreground.await()
                assertEquals(reply.copy(inReplyTo = 90), pump.inbound.first())
                assertTrue(tape.send("control", checkNotNull(end.message)).accepted)
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                enqueue.countDown()
                hub.close()
            }
        }

    @Test fun foregroundTeardownPermitsOnlyADeliveredDuplicateTail() =
        runTest {
            val initial = query.copy(payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","epoch":"epoch","version":0}"""))
            val window =
                reply.copy(
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"chat","epoch":"epoch","version":28,"items":[],"final":true,"lower_order":1,"upper_order":1,"continuation":1,"older_exists":false}""",
                        ),
                )
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, initial),
                        SuiteStep("daemon", owner, window),
                        SuiteStep("client", owner, query.copy(id = 14)),
                        SuiteStep("daemon", owner, reply.copy(id = 49, inReplyTo = 14)),
                        end,
                        SuiteStep("close", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                assertTrue(pump.send(initial.copy(id = 90)))
                runCurrent()
                assertEquals(window.copy(inReplyTo = 90), pump.inbound.first())
                val foreground = async { hub.beforePhoneTeardown() }
                advanceTimeBy(20)
                runCurrent()
                assertTrue("An already delivered duplicate tail must not prevent activity teardown", foreground.isCompleted)
                foreground.await()
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
                assertTrue(tape.send("control", checkNotNull(end.message)).accepted)
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun foregroundTeardownCannotBorrowIncompleteOrForeignCoverage() =
        runTest {
            for (condition in listOf("incomplete", "conversation", "epoch", "checkpoint")) {
                val conversation = if (condition == "conversation") "other" else "chat"
                val epoch = if (condition == "epoch") "other" else "epoch"
                val version = if (condition == "checkpoint") 27 else 28
                val initial =
                    query.copy(
                        payload =
                            MobileJson.parseToJsonElement(
                                """{"conversation_id":"$conversation","epoch":"$epoch","version":0}""",
                            ),
                    )
                val window =
                    reply.copy(
                        payload =
                            MobileJson.parseToJsonElement(
                                """{"conversation_id":"$conversation","epoch":"$epoch","version":$version,"items":[],"final":${condition != "incomplete"},"lower_order":1,"upper_order":1,"continuation":1,"older_exists":false}""",
                            ),
                    )
                val tape =
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("client", owner, initial),
                            SuiteStep("daemon", owner, window),
                            SuiteStep("client", owner, query.copy(id = 14)),
                            SuiteStep("daemon", owner, reply.copy(id = 49, inReplyTo = 14)),
                            end,
                            SuiteStep("close", owner),
                        ),
                        literalProducerIdentities = true,
                    )
                val hub = SuiteConnections.replaying(backgroundScope, tape)
                try {
                    val transport = hub.transport(owner) { error("Live transport started") }
                    val pump = hub.pump(transport) { error("Live pump started") }
                    pump.start()
                    transport.connect()
                    runCurrent()
                    assertTrue(pump.send(initial.copy(id = 90)))
                    runCurrent()
                    assertEquals(window.copy(inReplyTo = 90), pump.inbound.first())
                    val foreground = async { runCatching { hub.beforePhoneTeardown() } }
                    advanceTimeBy(30001)
                    runCurrent()
                    assertTrue("$condition coverage must keep the required tail pending", foreground.await().isFailure)
                    assertFalse(tape.phoneWorkBeforeTestEndReady())
                    assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
                } finally {
                    hub.close()
                }
            }
        }

    @Test fun foregroundPreparationCannotCrossAnObservationCheckpoint() {
        val observation = SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"reply-observed"}"""))
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("client", owner, query),
                    SuiteStep("daemon", owner, reply),
                    observation,
                    SuiteStep("client", owner, query.copy(id = 14)),
                    SuiteStep("daemon", owner, reply.copy(id = 49, inReplyTo = 14)),
                    end,
                ),
                literalProducerIdentities = true,
            )
        val delivered = tape.send(owner, query.copy(id = 90)).deliveries.single()
        tape.delivered(owner, checkNotNull(delivered.message))
        assertTrue(tape.preparePhoneTeardown().isEmpty())
        assertTrue(tape.phoneWorkBeforeTestEndReady())
        tape.send("control", checkNotNull(observation.message))
        assertFalse("The unprepared duplicate after the observation remains pending", tape.phoneWorkBeforeTestEndReady())
        assertTrue(tape.preparePhoneTeardown().isEmpty())
        assertFalse("An intervening observation prevents borrowed coverage", tape.phoneWorkBeforeTestEndReady())
    }

    @Test fun missingWorkAndAnEarlyCloseStillFail() {
        val tape = tape()
        assertFalse(tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        assertThrows(IllegalStateException::class.java) { tape.lifecycle("close", owner) }
        assertThrows(IllegalStateException::class.java) { tape.phoneWorkBeforeTestEndReady() }
    }

    @Test fun foreignWorkCannotReleaseTheRequiredQuery() {
        val variants =
            listOf(
                "phone/other-host/1" to query,
                "phone/host/0" to query,
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"other","epoch":"epoch","version":28}"""),
                    ),
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","epoch":"other","version":28}"""),
                    ),
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","epoch":"epoch","version":27}"""),
                    ),
            )
        for ((actualOwner, actual) in variants) {
            val tape = tape()
            assertFalse(tape.phoneWorkBeforeTestEndReady())
            assertThrows(IllegalStateException::class.java) { tape.send(actualOwner, actual.copy(id = 90)) }
            assertThrows(IllegalStateException::class.java) { tape.phoneWorkBeforeTestEndReady() }
        }
    }

    @Test fun aRequiredRepeatedQueryCannotBeOmittedOrSatisfiedByADuplicateRequestId() {
        val tape =
            tape(
                listOf(
                    SuiteStep("client", owner, query.copy(id = 14)),
                    SuiteStep("daemon", owner, reply.copy(id = 49, inReplyTo = 14)),
                ),
            )
        tape.send(owner, query.copy(id = 90))
        assertFalse("An identical later captured query remains required", tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.send(owner, query.copy(id = 90)) }
    }

    @Test fun readinessDoesNotConsumeTestEnd() {
        val tape = tape()
        val response = tape.send(owner, query.copy(id = 90)).deliveries.single()
        assertEquals(reply.copy(inReplyTo = 90), response.message)
        assertTrue(tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        tape.send("control", checkNotNull(end.message))
        tape.lifecycle("close", owner)
        tape.assertComplete()
    }

    @Test fun anObservationCheckpointBoundsTheForegroundWait() {
        val observation = SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"reply-observed"}"""))
        val tape = RecordedSuiteTape(listOf(observation, SuiteStep("client", owner, query), end), literalProducerIdentities = true)
        assertTrue(tape.phoneWorkBeforeTestEndReady())
        tape.send("control", checkNotNull(observation.message))
        assertFalse(tape.phoneWorkBeforeTestEndReady())
    }

    private fun tape(extra: List<SuiteStep> = emptyList()): RecordedSuiteTape =
        RecordedSuiteTape(
            listOf(SuiteStep("client", owner, query), SuiteStep("daemon", owner, reply)) + extra + end + SuiteStep("close", owner),
            literalProducerIdentities = true,
        )

    private fun frame(
        id: Long,
        type: String,
        body: String,
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-21T00:00:00Z", MobileJson.parseToJsonElement(body), inReplyTo = reply)
}
