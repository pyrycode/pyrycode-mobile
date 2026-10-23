package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadQueries
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadQueryReadinessTest {
    private val savedLogSink = RelayLog.sink

    @Before fun muteLogs() { RelayLog.sink = { _, _, _ -> } }
    @After fun restoreLogs() { RelayLog.sink = savedLogSink }

    @Test fun cachedCertificationCannotReleaseAcquisitionDuringTheCurrentQuery() = runTest {
        val store = AuthoritativeThreadStore()
        val connection = Any()
        val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(connection))
        val progress = ThreadQueryReadiness.register(HOST) { true }
        val sent = mutableListOf<Envelope>()
        var next = 0L
        val queries = AuthoritativeThreadQueries(HOST, store, AuthoritativeThreadRoute(HOST, store), { true }, { ++next }, {
            progress.sending(it)
            sent += it
            progress.sent(it.id, true)
            true
        }, { 0L })
        val thread = object : NegotiatedThread() {
            override val host = HOST
            override val store = store
            override val support = support
            override fun observe(conversationId: String) = store.observe(host, conversationId)
            override suspend fun open(conversationId: String) = queries.open(conversationId)
            override suspend fun acquire(conversationId: String, connection: Any): Closeable? =
                if ((support.value as? ThreadSupport.Supported)?.connection === connection) queries.acquire(conversationId) else null
            override fun older(conversationId: String) = queries.older(conversationId)
            override fun cancel(conversationId: String) = queries.cancel(conversationId)
        }
        fun complete(request: Long, initial: Boolean = false) {
            val reply = terminal(request, initial)
            queries.accept(reply)
            progress.consumed(reply)
        }
        try {
            queries.open(CHAT)
            complete(1, initial = true)
            assertEquals(13L, store.current(HOST, CHAT).checkpoint)
            queries.open(CHAT)
            assertEquals(2, sent.size)
            ThreadAcquisitionGate.arm(thread, CHAT, 30000).use { gate ->
                val acquisition = async { ThreadAcquisitionGate.negotiated(thread).acquire(CHAT, connection) }
                runCurrent()
                assertFalse("old certified content must not release a newer active query", acquisition.isCompleted)
                assertFalse(gate.finished)
                complete(2)
                runCurrent()
                assertEquals("acquisition must send its own query after the current terminal is consumed", 3, sent.size)
                assertTrue(gate.finished)
                complete(3)
                checkNotNull(acquisition.await()).close()
            }
        } finally {
            queries.abandon()
            progress.close()
        }
    }

    @Test fun replacementCannotSpendThePreviousConnectionsReceipt() = runTest {
        val first = ThreadQueryReadiness.register(HOST) { true }
        send(first, 1)
        first.consumed(terminal(1))
        val previous = ThreadQueryReadiness.awaitCurrent(HOST, CHAT)
        assertTrue(previous.isCurrent())
        val second = ThreadQueryReadiness.register(HOST) { true }
        val wait = async { ThreadQueryReadiness.awaitCurrent(HOST, CHAT) }
        runCurrent()
        assertFalse(previous.isCurrent())
        assertFalse(wait.isCompleted)
        send(second, 2)
        // An old connection completing again cannot satisfy the current host's observation.
        first.consumed(terminal(1))
        runCurrent()
        assertFalse(wait.isCompleted)
        second.consumed(terminal(2))
        runCurrent()
        assertTrue(wait.await().isCurrent())
        first.close()
        second.close()
    }

    @Test fun anotherConversationAndPartialReplyCannotCompleteTheCurrentRead() = runTest {
        val source = ThreadQueryReadiness.register(HOST) { true }
        send(source, 1)
        val wait = async { ThreadQueryReadiness.awaitCurrent(HOST, CHAT) }
        runCurrent()
        source.consumed(frame(5, "thread_items", """{"conversation_id":"chat","epoch":"e","version":13,"items":[],"final":false}""", 1))
        runCurrent()
        assertFalse(wait.isCompleted)
        send(source, 2, conversation = "other")
        source.consumed(terminal(2, conversation = "other"))
        runCurrent()
        assertFalse(wait.isCompleted)
        source.consumed(terminal(1))
        runCurrent()
        assertTrue(wait.await().isCurrent())
        source.close()
    }

    @Test fun refusedMalformedAndCancelledQueriesCannotReuseAnOldSuccess() = runTest {
        for (failure in listOf("refused", "malformed", "closed", "cancelled")) {
            val source = ThreadQueryReadiness.register(HOST) { true }
            send(source, 1)
            source.consumed(terminal(1))
            val old = ThreadQueryReadiness.awaitCurrent(HOST, CHAT)
            source.sending(query(2))
            source.sent(2, failure != "refused")
            when (failure) {
                "malformed" -> source.consumed(terminal(2).copy(payload = MobileJson.parseToJsonElement("{}")))
                "closed" -> source.close()
            }
            val wait = async { ThreadQueryReadiness.awaitCurrent(HOST, CHAT) }
            runCurrent()
            assertFalse(failure, old.isCurrent())
            assertFalse(failure, wait.isCompleted)
            wait.cancelAndJoin()
            assertFalse(failure, source.ready(CHAT) != null)
            source.close()
        }
    }

    @Test fun aSynchronousConsumedReplyStillNeedsItsAcceptedSend() = runTest {
        val source = ThreadQueryReadiness.register(HOST) { true }
        source.sending(query(1))
        source.consumed(terminal(1))
        assertEquals(null, source.ready(CHAT))
        source.sent(1, true)
        assertTrue(ThreadQueryReadiness.awaitCurrent(HOST, CHAT).isCurrent())
        source.close()
    }

    @Test fun aQueuedTerminalNeedsSuccessfulDownstreamConsumption() = runTest {
        for (cancelled in listOf(false, true)) {
            val raw = Pump()
            val source = ThreadQueryReadiness.wrap(HOST, raw)
            val received = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val collector = backgroundScope.launch {
                source.inbound.collect {
                    received.complete(Unit)
                    release.await()
                }
            }
            val reading = async { ThreadQueryReadiness.awaitCurrent(HOST, CHAT) }
            runCurrent()
            assertTrue(source.send(query(1)))
            raw.frames.send(terminal(1))
            runCurrent()
            assertTrue(received.isCompleted)
            assertFalse("queued terminal must not certify an unfinished collector", reading.isCompleted)
            if (cancelled) {
                collector.cancelAndJoin()
                runCurrent()
                assertFalse("cancelled downstream work cannot certify readiness", reading.isCompleted)
                reading.cancelAndJoin()
            } else {
                release.complete(Unit)
                runCurrent()
                assertTrue(reading.await().isCurrent())
                collector.cancelAndJoin()
            }
            source.close()
        }
    }

    private class Pump : ManagedSessionPump {
        val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = frames.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Open("phone", setOf("thread", "interactive")))
        override fun send(envelope: Envelope) = true
        override fun start() = Unit
        override fun close() { frames.close() }
    }

    private fun send(source: ThreadQueryProgress, id: Long, conversation: String = CHAT) {
        source.sending(query(id, conversation))
        source.sent(id, true)
    }

    private fun query(id: Long, conversation: String = CHAT) =
        frame(id, "thread_catch_up", """{"conversation_id":"$conversation","epoch":"e","version":13}""")

    private fun terminal(id: Long, initial: Boolean = false, conversation: String = CHAT): Envelope {
        val coverage =
            if (initial) {
                "\"lower_order\":0,\"upper_order\":14,\"continuation\":0,\"older_exists\":false"
            } else {
                "\"from_version\":13"
            }
        return frame(
            id + 10,
            "thread_items",
            """{"conversation_id":"$conversation","epoch":"e","version":13,"items":[],"final":true,$coverage}""",
            id,
        )
    }

    private fun frame(id: Long, type: String, payload: String, reply: Long? = null) =
        Envelope(id, type, "2026-10-23T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = reply)

    private companion object {
        const val HOST = "query-readiness-host"
        const val CHAT = "chat"
    }
}
