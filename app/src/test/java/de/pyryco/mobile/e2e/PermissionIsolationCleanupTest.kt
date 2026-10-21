package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.recording.RecordedSuiteTape
import de.pyryco.mobile.recording.SuiteConnections
import de.pyryco.mobile.recording.SuiteStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionIsolationCleanupTest {
    private var savedLogSink = RelayLog.sink

    @Before fun muteDecoderLogs() {
        savedLogSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreDecoderLogs() {
        RelayLog.sink = savedLogSink
    }

    @Test fun hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion() =
        runTest {
            val phone = "phone/host/0"
            val peer = "peer/host/0"
            val request = frame(1, "thread_catch_up", """{"conversation_id":"B","epoch":"epoch-b","version":13}""")
            val reply =
                frame(
                    2,
                    "thread_items",
                    """{"conversation_id":"B","epoch":"epoch-b","version":13,"from_version":13,"items":[],"final":true}""",
                    1,
                )
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", phone),
                        SuiteStep("open", phone),
                        SuiteStep("connect", peer),
                        SuiteStep("open", peer),
                        SuiteStep("daemon", peer, frame(1, "turn_end", """{"conversation_id":"B"}""")),
                        SuiteStep("client", phone, request),
                        SuiteStep("daemon", phone, reply, delayMs = 1000),
                        SuiteStep("close", peer),
                        SuiteStep("inbound_end", peer),
                        SuiteStep("close", phone),
                        SuiteStep("inbound_end", phone),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape, originalSpeed = true)
            try {
                val phoneTransport = hub.transport(phone) { error("No live transport") }
                val phonePump = hub.pump(phoneTransport) { error("No live pump") }
                phonePump.start()
                phoneTransport.connect()
                val peerTransport = hub.transport(peer) { error("No live transport") }
                val peerPump = hub.pump(peerTransport) { error("No live pump") }
                peerPump.start()
                peerTransport.connect()
                val peerEnd = async { peerPump.inbound.first { it.type == "turn_end" } }
                val phoneReply = async { phonePump.inbound.first { it.type == "thread_items" } }
                runCurrent()
                assertEquals("turn_end", peerEnd.await().type)

                var removed = false
                val cleanup =
                    async {
                        withPermissionIsolationCleanup(
                            closePeer = peerTransport::close,
                            beforeHostRemoval = { hub.beforeClose(phone) },
                            removeHost = {
                                removed = true
                                phoneTransport.close()
                            },
                        ) { Unit }
                    }
                runCurrent()
                assertFalse("Peer completion cannot authorize phone removal", removed)
                assertFalse(cleanup.isCompleted)
                // Release the actual required request while the phone connection remains usable.
                assertTrue(phonePump.send(request.copy(id = 90)))
                runCurrent()
                advanceTimeBy(999)
                runCurrent()
                assertFalse("An accepted query still has a scheduled delivery", removed)
                advanceTimeBy(21)
                runCurrent()
                cleanup.await()
                assertTrue(removed)
                assertEquals(90L, phoneReply.await().inReplyTo)
                assertEquals(reply.payload, phoneReply.await().payload)
                hub.awaitIdle()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }

    @Test fun missingPhoneWorkFailsAtTheExistingDeadlineAndStillRemovesHost() =
        runTest {
            val owner = "phone/host/0"
            val hub =
                SuiteConnections.replaying(
                    backgroundScope,
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep(
                                "client",
                                owner,
                                frame(1, "thread_catch_up", """{"conversation_id":"B","epoch":"epoch-b","version":13}"""),
                            ),
                            SuiteStep("close", owner),
                            SuiteStep("inbound_end", owner),
                        ),
                    ),
                )
            try {
                val transport = hub.transport(owner) { error("No live transport") }
                val pump = hub.pump(transport) { error("No live pump") }
                pump.start()
                transport.connect()
                runCurrent()
                var removed = false
                val cleanup =
                    async {
                        runCatching {
                            withPermissionIsolationCleanup(
                                closePeer = {},
                                beforeHostRemoval = { hub.beforeClose(owner) },
                                removeHost = {
                                    removed = true
                                    transport.close()
                                },
                            ) { Unit }
                        }.exceptionOrNull()
                    }
                runCurrent()
                advanceTimeBy(29999)
                runCurrent()
                assertFalse(removed)
                assertFalse(cleanup.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(cleanup.await() is TimeoutCancellationException)
                assertEquals(30000L, testScheduler.currentTime)
                assertTrue(removed)
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            } finally {
                hub.close()
            }
        }

    @Test fun aReplayMismatchRemainsFirstThroughCleanup() =
        runTest {
            // Probe owner, conversation, epoch and checkpoint independently. None can borrow B's query.
            val owner = "phone/host/0"
            val request = frame(1, "thread_catch_up", """{"conversation_id":"B","epoch":"epoch-b","version":13}""")
            val wrongRequests =
                listOf(
                    "peer/host/0" to request,
                    owner to frame(90, "thread_catch_up", """{"conversation_id":"A","epoch":"epoch-b","version":13}"""),
                    owner to frame(90, "thread_catch_up", """{"conversation_id":"B","epoch":"epoch-a","version":13}"""),
                    owner to frame(90, "thread_catch_up", """{"conversation_id":"B","epoch":"epoch-b","version":12}"""),
                )
            for ((sender, wrong) in wrongRequests) {
                val tape =
                    RecordedSuiteTape(
                        listOf(
                            SuiteStep("connect", owner),
                            SuiteStep("open", owner),
                            SuiteStep("client", owner, request),
                            SuiteStep("close", owner),
                            SuiteStep("inbound_end", owner),
                        ),
                        literalProducerIdentities = true,
                    )
                val hub = SuiteConnections.replaying(backgroundScope, tape)
                try {
                    val transport = hub.transport(owner) { error("No live transport") }
                    val pump = hub.pump(transport) { error("No live pump") }
                    pump.start()
                    transport.connect()
                    runCurrent()
                    val first = assertThrows(IllegalStateException::class.java) { tape.send(sender, wrong) }
                    val original = AssertionError("Scenario assertion")
                    val failure =
                        runCatching {
                            withPermissionIsolationCleanup(
                                closePeer = {},
                                beforeHostRemoval = { hub.beforeClose(owner) },
                                removeHost = transport::close,
                            ) { throw original }
                        }.exceptionOrNull()
                    assertSame(original, failure)
                    assertEquals(first.message, original.suppressed.single().message)
                    val stored = assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
                    assertEquals(first.message, stored.cause?.message)
                } finally {
                    hub.close()
                }
            }
        }

    @Test fun scenarioFailureKeepsCleanupFailuresSuppressedInOrder() {
        val scenario = AssertionError("Scenario")
        val peer = IllegalStateException("Peer cleanup")
        val fence = IllegalStateException("Replay fence")
        val host = IllegalStateException("Host cleanup")
        val failure =
            assertThrows(AssertionError::class.java) {
                withPermissionIsolationCleanup(
                    closePeer = { throw peer },
                    beforeHostRemoval = { throw fence },
                    removeHost = { throw host },
                ) { throw scenario }
            }
        assertSame(scenario, failure)
        assertEquals(listOf(peer, fence, host), failure.suppressed.toList())
    }

    @Test fun firstCleanupFailureRemainsPrimaryAndHostCleanupStillRuns() {
        val first = IllegalStateException("Replay fence")
        val later = IllegalArgumentException("Host cleanup")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                withPermissionIsolationCleanup(
                    closePeer = {},
                    beforeHostRemoval = { throw first },
                    removeHost = { throw later },
                ) { Unit }
            }
        assertSame(first, failure)
        assertSame(later, failure.suppressed.single())
    }

    @Test fun ordinaryCleanupKeepsPeerThenHostOrder() {
        val actions = mutableListOf<String>()
        val result =
            withPermissionIsolationCleanup(
                closePeer = { actions += "peer" },
                beforeHostRemoval = {}, // The native fence is a no-op outside replay.
                removeHost = { actions += "host" },
            ) {
                actions += "scenario"
                "result"
            }
        assertEquals("result", result)
        assertEquals(listOf("scenario", "peer", "host"), actions)
    }

    @Test fun rethrowingTheSameFailureDuringCleanupCannotCauseSelfSuppression() {
        val original = AssertionError("Original failure")
        val failure =
            assertThrows(AssertionError::class.java) {
                withPermissionIsolationCleanup(
                    closePeer = { throw original },
                    beforeHostRemoval = { throw original },
                    removeHost = { throw original },
                ) { throw original }
            }
        assertSame(original, failure)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun frame(
        id: Long,
        type: String,
        body: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-21T00:00:00Z", MobileJson.parseToJsonElement(body), inReplyTo = reply)
}
