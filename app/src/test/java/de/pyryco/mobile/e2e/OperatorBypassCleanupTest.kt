package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.recording.RecordedSuiteDocument
import de.pyryco.mobile.recording.RecordedSuiteTape
import de.pyryco.mobile.recording.SuiteConnections
import de.pyryco.mobile.recording.SuiteStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OperatorBypassCleanupTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun finalCapturedCatchUpFinishesBeforeHostRemoval() = exerciseCleanup(null)

    @Test fun scenarioFailureStillDrainsAndRemovesHost() = exerciseCleanup(AssertionError("Scenario failed"))

    private fun exerciseCleanup(scenarioFailure: Throwable?) =
        runTest {
            val document =
                RecordedSuiteDocument.parse(
                    File(
                        "src/androidTest/assets/recorded/suite/" +
                            "interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.json",
                    ).readText(),
                )
            val owner = "phone/host-3/0"
            // Reuse the unmodified captured request 82/checkpoint 33, reply and phone close boundary.
            val tail =
                document.steps
                    .dropWhile { it.owner != owner || it.direction != "client" || it.message?.id != 82L }
                    .filter { it.owner == owner }
                    .take(4)
            assertEquals(listOf("client", "daemon", "close", "inbound_end"), tail.map { it.direction })
            val request = requireNotNull(tail[0].message)
            val expectedReply = requireNotNull(tail[1].message)
            assertEquals("thread_catch_up", request.type)
            assertEquals(82L, expectedReply.inReplyTo)
            val tape =
                RecordedSuiteTape(
                    listOf(SuiteStep("connect", owner), SuiteStep("open", owner)) + tail,
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape, originalSpeed = true)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                val releaseRequest = CompletableDeferred<Unit>()
                val consumeReply = CompletableDeferred<Unit>()
                val enteredReply = CompletableDeferred<Envelope>()
                val consumedReply = CompletableDeferred<Envelope>()
                val collector =
                    backgroundScope.launch {
                        pump.inbound.collect {
                            enteredReply.complete(it)
                            consumeReply.await()
                            consumedReply.complete(it)
                        }
                    }
                val producer =
                    backgroundScope.launch {
                        releaseRequest.await()
                        assertTrue(pump.send(request.copy(id = 900)))
                    }
                runCurrent()
                var removed = false
                val actions = mutableListOf<String>()
                val cleanup =
                    async {
                        runCatching {
                            withOperatorBypassCleanup(
                                closePeer = { actions += "peer" },
                                beforeHostRemoval = {
                                    actions += "fence"
                                    hub.beforeClose(owner)
                                },
                                removeHost = {
                                    removed = true
                                    transport.close()
                                    producer.cancel()
                                    collector.cancel()
                                    actions += "host"
                                },
                            ) { scenarioFailure?.let { throw it } }
                        }
                    }
                runCurrent()
                assertFalse("Host removal raced the final captured catch-up", removed)
                assertTrue(producer.isActive && collector.isActive)
                releaseRequest.complete(Unit)
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertTrue("The correlated terminal reply must reach its collector", enteredReply.isCompleted)
                assertFalse("Delivery alone cannot authorize host removal", removed)
                assertFalse(cleanup.isCompleted)
                assertEquals(expectedReply.payload, enteredReply.await().payload)
                assertEquals(900L, enteredReply.await().inReplyTo)
                consumeReply.complete(Unit)
                advanceTimeBy(20)
                runCurrent()
                val outcome = cleanup.await()
                if (scenarioFailure == null) outcome.getOrThrow() else assertSame(scenarioFailure, outcome.exceptionOrNull())
                assertTrue(consumedReply.isCompleted)
                assertTrue(removed)
                assertEquals(listOf("peer", "fence", "host"), actions)
                hub.awaitIdle()
                hub.assertComplete()
            } finally {
                hub.close()
            }
        }
}
