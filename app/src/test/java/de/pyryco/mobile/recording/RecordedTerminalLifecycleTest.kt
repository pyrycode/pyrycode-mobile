package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.e2e.LiveSessionCleanupRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@OptIn(ExperimentalCoroutinesApi::class)
class RecordedTerminalLifecycleTest {
    @Test fun successfulReturnKeepsRepeatedPostEndQueriesAndRepliesBeforeCompleteAndProcessStop() = terminal(closeBeforeComplete = false)

    @Test fun aRecordedCloseBeforeCompleteStillPrecedesTheObservation() = terminal(closeBeforeComplete = true)

    @Test fun missingTerminalWorkTimesOutAndEarlyCloseStillFails() =
        runTest {
            val owner = "phone/host/0"
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", owner, frame(1, "list_conversations")),
                        SuiteStep("daemon", owner, frame(2, "conversations", reply = 1)),
                        SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                    ),
                )
            val hub =
                SuiteConnections.replaying(
                    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                    tape,
                )
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                hub.pump(transport) { error("Live pump started") }.start()
                transport.connect()
                runCurrent()
                val preparing = async { runCatching { hub.completeBeforePhoneTeardown() }.exceptionOrNull() }
                runCurrent()
                assertFalse("missing work cannot make terminal preparation succeed", preparing.isCompleted)
                advanceTimeBy(30001)
                runCurrent()
                assertTrue(preparing.await() is TimeoutCancellationException)
                assertFalse(tape.checkpointReady("scenario-complete"))
                transport.close()
                val early = runCatching { hub.awaitIdle() }.exceptionOrNull()
                assertTrue(
                    early
                        ?.cause
                        ?.message
                        .orEmpty()
                        .contains("lifecycle mismatch"),
                )
            } finally {
                hub.close()
            }
        }

    @Test fun terminalQueriesCannotBorrowAnotherHostOrGenerationOrCrossTheObservation() {
        for (other in listOf("phone/other-host/0", "phone/host/1", "observation")) {
            val owner = "phone/host/0"
            val request = frame(1, "list_conversations")
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner),
                        SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                        SuiteStep("client", owner, request),
                        SuiteStep("daemon", owner, frame(2, "conversations", reply = 1)),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                    ),
                )
            tape.lifecycle("connect", owner)
            assertTrue(tape.checkpointReady("scenario-complete"))
            if (other != "observation") {
                tape.send("control", frame(90, "test-control:checkpoint", """{"body":"scenario-complete"}"""))
            }
            assertThrows(IllegalStateException::class.java) {
                tape.send(
                    if (other ==
                        "observation"
                    ) {
                        owner
                    } else {
                        other
                    },
                    request.copy(id = 91),
                )
            }
        }
    }

    private fun terminal(closeBeforeComplete: Boolean) {
        val owner = "phone/host/0"
        val query = frame(1, "list_conversations")
        val response = frame(2, "conversations", """{"conversations":[]}""", 1)
        val complete = SuiteStep("client", "control", frame(2, "test-control:checkpoint", """{"body":"scenario-complete"}"""))
        val close = listOf(SuiteStep("close", owner), SuiteStep("inbound_end", owner))
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner),
                    SuiteStep("client", "control", frame(1, "test-control:test-end", """{"body":""}""")),
                    SuiteStep("daemon", owner, frame(1, "turn_state"), delayMs = 400),
                    SuiteStep("client", owner, query),
                    SuiteStep("daemon", owner, response, delayMs = 400),
                    SuiteStep("client", owner, query.copy(id = 3)),
                    SuiteStep("daemon", owner, response.copy(id = 4, inReplyTo = 3), delayMs = 400),
                ) + if (closeBeforeComplete) close + complete else listOf(complete) + close,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = SuiteConnections.replaying(scope, tape)
        try {
            val transport = hub.transport(owner) { error("Live transport started") }
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            val received = mutableListOf<Long?>()
            val repliesConsumed = CompletableDeferred<Unit>()
            activityScope.launch {
                pump.inbound.collect {
                    when (it.type) {
                        "turn_state" -> assertTrue(pump.send(query.copy(id = 90)))
                        "conversations" -> {
                            received += it.inReplyTo
                            if (received.size == 1) {
                                assertTrue(pump.send(query.copy(id = 91)))
                            } else {
                                repliesConsumed.complete(Unit)
                            }
                        }
                    }
                }
            }
            transport.connect()
            runBlocking { withTimeout(1000) { pump.state.first { it is PumpState.Open } } }
            var destroyed = false
            var stops = 0
            var ends = 0
            var prepared = false
            val cleanup =
                LiveSessionCleanupRule(begin = {}, end = {
                    ends++
                    assertFalse("test-end must retain its owning Activity", destroyed)
                    hub.control("test-end", "") { error("Live control started") }
                })
            val activity =
                TestRule { base, _ ->
                    object : Statement() {
                        override fun evaluate() {
                            try {
                                base.evaluate()
                            } finally {
                                destroyed = true
                                activityScope.cancel()
                                // Model delayed ProcessLifecycleOwner stop after real Activity destruction.
                                runBlocking {
                                    delay(20)
                                    stops++
                                    transport.close()
                                    hub.awaitIdle()
                                }
                            }
                        }
                    }
                }
            val recorded =
                RecordedActivityRule(activity) {
                    runBlocking { hub.beforePhoneTeardown() }
                    cleanup.endWhileActivityAlive()
                    prepared = runBlocking { hub.completeBeforePhoneTeardown() }
                    assertTrue("both separately correlated replies must be consumed before destruction", repliesConsumed.isCompleted)
                }
            cleanup
                .apply(
                    recorded.apply(
                        object : Statement() {
                            override fun evaluate() {
                                assertFalse("terminal queries must still be pending at scenario return", repliesConsumed.isCompleted)
                            }
                        },
                        Description.EMPTY,
                    ),
                    Description.EMPTY,
                ).evaluate()
            assertEquals(!closeBeforeComplete, prepared)
            if (!prepared) runBlocking { hub.checkpoint("scenario-complete") }
            assertEquals(listOf(90L, 91L), received)
            assertEquals(1, ends)
            assertEquals(1, stops)
            hub.assertComplete()
        } finally {
            activityScope.cancel()
            hub.close()
            scope.cancel()
        }
    }

    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-23T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = reply)
}
