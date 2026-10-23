package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.e2e.LiveSessionCleanupRule
import de.pyryco.mobile.e2e.authoritativeThreadEvidence
import de.pyryco.mobile.e2e.certifiedNextSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.runner.Description
import org.junit.runners.model.Statement

@OptIn(ExperimentalCoroutinesApi::class)
class SuiteCloseConsumptionTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun rotationReopenWaitsForCertificationBeforeAcquiringItsQuery() =
        runTest {
            val diagnosticLogs = mutableListOf<String>()
            RelayLog.sink = { _, _, message -> diagnosticLogs += message }
            val owner = "phone/host/0"
            val divider =
                """{"id":78,"kind":"session_divider","order":78,"rev":78,"session":"session-a","status":"done","active":false,"shown":true,"summary":"","content":{"cause":"operator_reset","previous_session_id":"session-a","new_session_id":"session-b"}}"""
            val rotationBatch =
                frame(175, "thread_items", """{"conversation_id":"chat","epoch":"epoch","version":81,"items":[$divider],"final":false}""", 30)
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner, capabilities = capabilities),
                        SuiteStep("client", owner, query(1, 0)),
                        SuiteStep("daemon", owner, batch(2, 1, version = 77)),
                        SuiteStep("daemon", owner, terminal(3, 1, version = 77)),
                        SuiteStep("client", owner, query(30, 77)),
                        SuiteStep("daemon", owner, rotationBatch),
                        SuiteStep("daemon", owner, terminal(176, 30, from = 77, version = 81)),
                        SuiteStep("client", owner, query(35, 81)),
                        // Advance the boundary: terminal-tail normalization cannot replace this query.
                        SuiteStep("daemon", owner, terminal(181, 35, from = 81, version = 84)),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val releaseTerminal = CompletableDeferred<Unit>()
                val enteredTerminal = CompletableDeferred<Unit>()
                val requests = mutableListOf<Envelope>()
                val observed =
                    object : SessionPump by pump {
                        override val inbound =
                            pump.inbound.onEach {
                                if (it.id == 176L) {
                                    enteredTerminal.complete(Unit)
                                    releaseTerminal.await()
                                }
                            }

                        override fun send(envelope: Envelope): Boolean {
                            requests += envelope
                            return pump.send(envelope)
                        }
                    }
                val store = AuthoritativeThreadStore()
                store.reset("host", "chat", "epoch")
                val repository = repository(observed, connectionScope, store)
                assertTrue(repository.openAuthoritativeThread("chat"))
                runCurrent()
                assertEquals("${tape.progress()} logs=$diagnosticLogs", 77L, store.current("host", "chat").checkpoint)
                assertTrue(repository.openAuthoritativeThread("chat"))
                runCurrent()
                assertTrue(enteredTerminal.isCompleted)
                val partial = store.current("host", "chat")
                assertEquals(81L, partial.completedVersion)
                assertEquals(77L, partial.checkpoint)
                assertTrue("the actual divider is already admitted", authoritativeThreadEvidence("host", "chat", repository, partial).admitted)
                val reopening =
                    async {
                        store.observe("host", "chat").first {
                            authoritativeThreadEvidence("host", "chat", repository, it).certifiedNextSession("session-a") == "session-b"
                        }
                        requireNotNull(repository.acquireAuthoritativeThread("chat"))
                    }
                runCurrent()
                releaseTerminal.complete(Unit)
                runCurrent()
                val destination = reopening.await()
                runCurrent()
                assertEquals(
                    "reopen must retain the required post-rotation query instead of coalescing it with the active query",
                    listOf(query(1, 0).payload, query(30, 77).payload, query(35, 81).payload),
                    requests.map { it.payload },
                )
                assertEquals(84L, store.current("host", "chat").checkpoint)
                hub.beforeClose(owner)
                destination.close()
                transport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                connectionScope.cancel()
                hub.close()
            }
        }

    @Test fun replacementActivitySurvivesResetQueryAndOrderedSessionCleanup() =
        runTest {
            val owner = "phone/host/2"
            val fixtureCleanup = frame(1, "test-control:checkpoint", """{"body":"fixture-cleanup"}""")
            val testEnd = frame(2, "test-control:test-end", """{"body":""}""")
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner, capabilities = capabilities),
                        SuiteStep("client", owner, query(1, 0)),
                        SuiteStep("daemon", owner, batch(2, 1)),
                        SuiteStep("daemon", owner, terminal(3, 1)),
                        SuiteStep("client", owner, query(13, 0)),
                        SuiteStep("daemon", owner, batch(14, 13, version = 24)),
                        SuiteStep("daemon", owner, terminal(15, 13, version = 24)),
                        SuiteStep("client", "control", fixtureCleanup),
                        SuiteStep("client", "control", testEnd),
                        SuiteStep("close", owner),
                        SuiteStep("inbound_end", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val hub = SuiteConnections.replaying(CoroutineScope(backgroundScope.coroutineContext + dispatcher), tape)
            val activityScope = CoroutineScope(coroutineContext + SupervisorJob() + dispatcher)
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                val releaseQuery = CompletableDeferred<Unit>()
                var heldQuery: Envelope? = null
                var hold = false
                val delayedPump =
                    object : SessionPump by pump {
                        override fun send(envelope: Envelope): Boolean {
                            if (!hold || envelope.type != "thread_catch_up") return pump.send(envelope)
                            check(heldQuery == null)
                            heldQuery = envelope
                            activityScope.launch {
                                releaseQuery.await()
                                check(pump.send(envelope))
                            }
                            return true
                        }
                    }
                val store = AuthoritativeThreadStore()
                store.reset("host", "chat", "epoch")
                val repository = repository(delayedPump, activityScope, store)
                assertTrue(repository.openAuthoritativeThread("chat"))
                runCurrent()
                assertEquals(12L, store.current("host", "chat").checkpoint)
                val generation = store.current("host", "chat").generation
                var closeCount = 0
                val activity =
                    AutoCloseable {
                        closeCount++
                        try {
                            transport.close()
                            runBlocking { hub.awaitIdle() }
                        } finally {
                            activityScope.cancel()
                        }
                    }
                val cleanup =
                    LiveSessionCleanupRule(begin = {}, end = {
                        assertEquals("replacement is alive through fixture cleanup", 0, closeCount)
                        releaseQuery.complete(Unit)
                        runCurrent()
                        assertEquals("same-epoch reset gets a new generation", generation + 1, store.current("host", "chat").generation)
                        assertEquals("real reset reply certifies the checkpoint", 24L, store.current("host", "chat").checkpoint)
                        assertEquals(
                            "real reset query retains the exact checkpoint",
                            query(13, 0).payload,
                            requireNotNull(heldQuery).payload,
                        )
                        hub.control("checkpoint", "fixture-cleanup") { error("Live control started") }
                        hub.control("test-end", "") { error("Live control started") }
                        assertEquals("test-end precedes replacement destruction", 0, closeCount)
                    })
                cleanup
                    .apply(
                        object : Statement() {
                            override fun evaluate() {
                                cleanup.withRestartedActivity(activity) {
                                    hold = true
                                    assertTrue(repository.resetAuthoritativeThread("chat", "epoch"))
                                    assertEquals(0L, store.current("host", "chat").checkpoint)
                                    assertTrue("the real query is held across proof return", heldQuery != null && !releaseQuery.isCompleted)
                                }
                            }
                        },
                        Description.EMPTY,
                    ).evaluate()
                runCurrent()
                assertEquals("replacement closes exactly once", 1, closeCount)
                hub.assertComplete()
            } finally {
                activityScope.cancel()
                hub.close()
            }
        }

    @Test fun closeWaitsForTerminalConsumptionBeforeReplacementResumes() =
        runTest {
            val old = "phone/host/2"
            val next = "phone/host/3"
            val peer = "peer/host/1"
            val first = query(1, 0)
            val resumed = query(1, 12)
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", peer),
                        SuiteStep("open", peer),
                        SuiteStep("daemon", peer, frame(1, "turn_state")),
                        SuiteStep("connect", old),
                        SuiteStep("open", old, capabilities = capabilities),
                        SuiteStep("client", old, first),
                        SuiteStep("daemon", old, batch(2, 1)),
                        SuiteStep("daemon", old, terminal(3, 1)),
                        SuiteStep("close", old),
                        SuiteStep("inbound_end", old),
                        SuiteStep("connect", next),
                        SuiteStep("open", next, capabilities = capabilities),
                        SuiteStep("client", next, resumed),
                        SuiteStep("daemon", next, terminal(2, 1, from = 12)),
                        SuiteStep("close", next),
                        SuiteStep("inbound_end", next),
                        SuiteStep("close", peer),
                        SuiteStep("inbound_end", peer),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            val store = AuthoritativeThreadStore()
            store.reset("host", "chat", "epoch")
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
            val replacementScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
            try {
                val peerTransport = hub.transport(peer) { error("Live transport started") }
                hub.pump(peerTransport) { error("Live pump started") }.start()
                peerTransport.connect()
                val transport = hub.transport(old) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val heldTerminal = CompletableDeferred<Unit>()
                val terminalEntered = CompletableDeferred<Unit>()
                val gated =
                    object : SessionPump by pump {
                        override val inbound =
                            pump.inbound.onEach {
                                if (it.id == 3L) {
                                    terminalEntered.complete(Unit)
                                    heldTerminal.await()
                                }
                            }
                    }
                val repository = repository(gated, connectionScope, store)
                assertTrue(repository.openAuthoritativeThread("chat"))
                runCurrent()
                assertTrue(terminalEntered.isCompleted)
                assertEquals(1, store.current("host", "chat").items.size)
                assertEquals(0L, store.current("host", "chat").checkpoint)
                val closing =
                    async {
                        hub.beforeClose(old)
                        connectionScope.cancel()
                        transport.close()
                    }
                runCurrent()
                assertFalse("queued terminal is not a consumed checkpoint; ${tape.progress()}", closing.isCompleted)
                heldTerminal.complete(Unit)
                runCurrent()
                advanceTimeBy(20)
                runCurrent()
                closing.await()
                assertEquals(12L, store.current("host", "chat").checkpoint)

                // An unread peer frame cannot block this phone or provide its consumption receipt.
                val replacement = hub.transport(next) { error("Live transport started") }
                val replacementPump = hub.pump(replacement) { error("Live pump started") }
                replacementPump.start()
                replacement.connect()
                runCurrent()
                val actualRequests = mutableListOf<Envelope>()
                val observed =
                    object : SessionPump by replacementPump {
                        override fun send(envelope: Envelope): Boolean {
                            actualRequests += envelope
                            return replacementPump.send(envelope)
                        }
                    }
                assertTrue(repository(observed, replacementScope, store).openAuthoritativeThread("chat"))
                runCurrent()
                assertEquals(resumed.payload, actualRequests.single().payload)
                hub.beforeClose(next)
                replacementScope.cancel()
                replacement.close()
                peerTransport.close()
                runCurrent()
                hub.assertComplete()
            } finally {
                connectionScope.cancel()
                replacementScope.cancel()
                hub.close()
            }
        }

    @Test fun cancelledCollectorCannotAcknowledgeTheClosePrefix() =
        runTest {
            val owner = "phone/host/2"
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", owner),
                        SuiteStep("open", owner, capabilities = capabilities),
                        SuiteStep("client", owner, query(1, 0)),
                        SuiteStep("daemon", owner, batch(2, 1)),
                        SuiteStep("daemon", owner, terminal(3, 1)),
                        SuiteStep("close", owner),
                    ),
                    literalProducerIdentities = true,
                )
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
            try {
                val transport = hub.transport(owner) { error("Live transport started") }
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                transport.connect()
                runCurrent()
                val gate = CompletableDeferred<Unit>()
                val gated =
                    object : SessionPump by pump {
                        override val inbound = pump.inbound.onEach { if (it.id == 3L) gate.await() }
                    }
                val store = AuthoritativeThreadStore()
                store.reset("host", "chat", "epoch")
                assertTrue(repository(gated, connectionScope, store).openAuthoritativeThread("chat"))
                runCurrent()
                val closing = async { runCatching { hub.beforeClose(owner) } }
                runCurrent()
                connectionScope.cancel()
                gate.complete(Unit)
                advanceTimeBy(30001)
                runCurrent()
                assertTrue(closing.await().exceptionOrNull() is TimeoutCancellationException)
                assertEquals(0L, store.current("host", "chat").checkpoint)
                assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            } finally {
                connectionScope.cancel()
                hub.close()
            }
        }

    private fun repository(
        pump: SessionPump,
        scope: CoroutineScope,
        store: AuthoritativeThreadStore,
    ) = RemoteConversationRepository(
        pump,
        scope,
        negotiatedCapabilities = { capabilities },
        authoritativeHostId = "host",
        authoritativeThreads = store,
    )

    private fun query(
        id: Long,
        version: Int,
    ) = frame(id, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":$version}""")

    private fun batch(
        id: Long,
        reply: Long,
        version: Int = 12,
    ) = frame(
        id,
        "thread_items",
        """{"conversation_id":"chat","epoch":"epoch","version":$version,"items":[${item(1, version.toLong(), 1).raw}],"final":false}""",
        reply,
    )

    private fun terminal(
        id: Long,
        reply: Long,
        from: Int? = null,
        version: Int = 12,
    ) = frame(
        id,
        "thread_items",
        if (from == null) {
            """{"conversation_id":"chat","epoch":"epoch","version":$version,"items":[],"final":true,"lower_order":1,"upper_order":2,"continuation":1,"older_exists":false}"""
        } else {
            """{"conversation_id":"chat","epoch":"epoch","version":$version,"items":[],"final":true,"from_version":$from}"""
        },
        reply,
    )

    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-21T00:00:00Z", MobileJson.parseToJsonElement(payload), reply)

    private val capabilities = setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE)
}
