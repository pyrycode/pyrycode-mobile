package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicBoolean

class RecordedActivityRuleTest {
    @Test fun capturedBackgroundQueriesFinishBeforeTheActivityClosesItsConnection() {
        val owner = "phone/host/0"

        fun frame(
            id: Long,
            type: String,
            body: String = "{}",
            reply: Long? = null,
        ) = Envelope(id, type, "2026-10-18T00:00:00Z", MobileJson.parseToJsonElement(body), inReplyTo = reply)
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner),
                    SuiteStep("client", owner, frame(1, "list_conversations")),
                    SuiteStep("daemon", owner, frame(2, "conversations", """{"conversations":[]}""", 1)),
                    SuiteStep("close", owner),
                    SuiteStep("inbound_end", owner),
                    SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")),
                ),
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = SuiteConnections.replaying(scope, tape)
        try {
            val transport = hub.transport(owner) { error("Replay cannot open a real transport") }
            val pump = hub.pump(transport) { error("Replay cannot launch a real pump") }
            pump.start()
            val received = CompletableDeferred<Envelope>()
            scope.launch { pump.inbound.collect { received.complete(it) } }
            transport.connect()
            runBlocking { withTimeout(1000) { pump.state.first { it is PumpState.Open } } }
            val guardEntered = CompletableDeferred<Unit>()
            val sent = AtomicBoolean()
            val request = frame(90, "list_conversations")
            val query =
                scope.launch {
                    guardEntered.await()
                    assertTrue(pump.send(request))
                    sent.set(true)
                }
            val activity =
                TestRule { base, _ ->
                    object : Statement() {
                        override fun evaluate() {
                            try {
                                base.evaluate()
                            } finally {
                                transport.close()
                            }
                        }
                    }
                }
            val rule =
                RecordedActivityRule(activity) {
                    guardEntered.complete(Unit)
                    runBlocking {
                        hub.beforeClose(owner)
                        query.join()
                    }
                }
            rule
                .apply(
                    object : Statement() {
                        override fun evaluate() = Unit
                    },
                    Description.EMPTY,
                ).evaluate()
            assertTrue("The recorded query must run while the activity is still alive", sent.get())
            runBlocking {
                withTimeout(1000) {
                    query.join()
                    hub.awaitIdle()
                    val response = received.await()
                    assertEquals(request.id, response.inReplyTo)
                    assertEquals("conversations", response.type)
                }
            }
            hub.control("checkpoint", "scenario-complete") { error("Replay cannot call a producer") }
            runBlocking { withTimeout(1000) { hub.awaitIdle() } }
            hub.assertComplete()
        } finally {
            hub.close()
            scope.cancel()
        }
    }

    @Test fun anOriginalScenarioFailureIsPreservedWithoutWaitingForBackgroundQueries() {
        val original = AssertionError("Original scenario failed")
        var closed = false
        var waited = false
        val activity =
            TestRule { base, _ ->
                object : Statement() {
                    override fun evaluate() {
                        try {
                            base.evaluate()
                        } finally {
                            closed = true
                        }
                    }
                }
            }
        val rule = RecordedActivityRule(activity) { waited = true }
        val failure =
            assertThrows(AssertionError::class.java) {
                rule
                    .apply(
                        object : Statement() {
                            override fun evaluate(): Unit = throw original
                        },
                        Description.EMPTY,
                    ).evaluate()
            }
        assertSame(original, failure)
        assertTrue(closed)
        assertTrue(!waited)
    }
}
