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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
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

@OptIn(ExperimentalCoroutinesApi::class)
class SuiteCloseConsumptionTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
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
    ) = frame(
        id,
        "thread_items",
        """{"conversation_id":"chat","epoch":"epoch","version":12,"items":[${item(1, 12, 1).raw}],"final":false}""",
        reply,
    )

    private fun terminal(
        id: Long,
        reply: Long,
        from: Int? = null,
    ) = frame(
        id,
        "thread_items",
        if (from == null) {
            """{"conversation_id":"chat","epoch":"epoch","version":12,"items":[],"final":true,"lower_order":1,"upper_order":2,"continuation":1,"older_exists":false}"""
        } else {
            """{"conversation_id":"chat","epoch":"epoch","version":12,"items":[],"final":true,"from_version":$from}"""
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
