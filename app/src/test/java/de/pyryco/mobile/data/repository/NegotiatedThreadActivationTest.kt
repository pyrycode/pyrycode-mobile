package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2175: production negotiation, verdict ownership and request suppression through real coordinators. */
@OptIn(ExperimentalCoroutinesApi::class)
class NegotiatedThreadActivationTest {
    @Test fun helloAdvertisesThreadAndOmitsTheReplayCursor() {
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t")
        assertTrue(CAPABILITY_THREAD in hello.capabilities)
        assertTrue(CAPABILITY_INTERACTIVE in hello.capabilities)
        assertFalse("last_event_id" in MobileJson.encodeToString(hello))
    }

    @Test fun reconnectReevaluatesVerdictAndReopensOnlyOnTheNewConnection() =
        runTest {
            val host = host("h")
            try {
                val route = requireNotNull(host.coordinator.negotiatedThread)
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val first = host.connect()
                assertSame(ThreadSupport.Unverified, route.support.value)
                assertFalse(route.open("c"))
                first.open(CAPABLE)
                runCurrent()
                val firstVerdict = route.support.value as ThreadSupport.Supported
                assertTrue(route.open("c"))
                assertEquals(1, first.queries().size)

                host.connections.value = null
                runCurrent()
                assertSame(ThreadSupport.Unverified, route.support.value)
                assertFalse(route.open("c"))
                val second = host.connect()
                assertFalse(route.open("c"))
                second.open(CAPABLE)
                runCurrent()
                val secondVerdict = route.support.value as ThreadSupport.Supported
                assertNotSame(firstVerdict.connection, secondVerdict.connection)
                assertTrue(route.open("c"))
                assertEquals(1, first.queries().size)
                assertEquals(1, second.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun missingVerdictSurvivesDisconnectUntilACapableAckReplacesIt() =
        runTest {
            val host = host("h")
            try {
                val route = requireNotNull(host.coordinator.negotiatedThread)
                host.connect().open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                assertTrue(route.support.value is ThreadSupport.Missing)
                assertTrue(host.coordinator.threadMissing.value)
                host.connections.value = null
                runCurrent()
                assertTrue(route.support.value is ThreadSupport.Missing)
                val next = host.connect()
                runCurrent()
                assertTrue(host.coordinator.threadMissing.value)
                next.open(CAPABLE)
                runCurrent()
                assertTrue(route.support.value is ThreadSupport.Supported)
                assertFalse(host.coordinator.threadMissing.value)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun retiredConnectionCannotRestoreItsVerdict() =
        runTest {
            val host = host("h")
            try {
                val route = requireNotNull(host.coordinator.negotiatedThread)
                val retired = host.connect()
                retired.open(CAPABLE)
                runCurrent()
                val replacement = host.connect()
                runCurrent()
                assertSame(ThreadSupport.Unverified, route.support.value)
                retired.state.value = PumpState.Open("late", setOf(CAPABILITY_INTERACTIVE))
                retired.state.value = PumpState.Open("late", CAPABLE)
                runCurrent()
                assertSame(ThreadSupport.Unverified, route.support.value)
                assertFalse(route.open("c"))
                replacement.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                retired.state.value = PumpState.Open("later", CAPABLE)
                runCurrent()
                assertTrue(route.support.value is ThreadSupport.Missing)
                assertTrue(retired.queries().isEmpty() && replacement.queries().isEmpty())
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun missingThreadHostSendsNoContentRequests() =
        runTest {
            val host = host("h")
            try {
                val facade = StableConversationRepository(host.coordinator.currentRepository, negotiatedThread = host.coordinator.negotiatedThread)
                val pump = host.connect()
                backgroundScope.launch { facade.observeThreadSnapshot("c").collect {} }
                runCurrent()
                pump.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val route = requireNotNull(facade.negotiatedThread)
                assertFalse(route.open("c"))
                assertFalse(route.older("c"))
                assertTrue(runCatching { facade.requestHistory("c", "", 20) }.isFailure)
                runCurrent()
                assertEquals(emptyList<String>(), pump.sent.map { it.type }.filter { it in CONTENT_REQUESTS })
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun capableHostReplacesLegacyBackfillWithAThreadQuery() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val facade = StableConversationRepository(host.coordinator.currentRepository, negotiatedThread = host.coordinator.negotiatedThread)
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                backgroundScope.launch { facade.observeThreadSnapshot("c").collect {} }
                runCurrent()
                assertTrue(requireNotNull(facade.negotiatedThread).open("c"))
                runCurrent()
                assertEquals(listOf("thread_catch_up"), pump.sent.map { it.type }.filter { it in CONTENT_REQUESTS })
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun collidingHostsKeepSeparateStoresAndVerdicts() =
        runTest {
            val a = host("a")
            val b = host("b")
            try {
                a.coordinator.authoritativeThreads.reset("a", "c", "e")
                b.coordinator.authoritativeThreads.reset("b", "c", "e")
                val pumpA = a.connect()
                val pumpB = b.connect()
                pumpA.open(CAPABLE)
                pumpB.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val routeA = requireNotNull(a.coordinator.negotiatedThread)
                val routeB = requireNotNull(b.coordinator.negotiatedThread)
                assertTrue(routeA.open("c"))
                assertFalse(routeB.open("c"))
                val request = pumpA.queries().single()
                pumpA.push(batch(request, 10, listOf(item(1, 2, 1))))
                pumpA.push(window(request, 10, 1, 2, false))
                runCurrent()
                assertEquals(listOf(1L), routeA.observe("c").first().items.map { it.id })
                assertTrue(routeB.observe("c").first().items.isEmpty())
                assertFalse(a.coordinator.threadMissing.value)
                assertTrue(b.coordinator.threadMissing.value)
                assertTrue(pumpB.queries().isEmpty())
            } finally {
                a.coordinator.close()
                b.coordinator.close()
            }
        }

    @Test fun interruptedCatchUpAcrossReconnectKeepsNewerRevisionsAndPriorCheckpoint() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val route = requireNotNull(host.coordinator.negotiatedThread)
                val first = host.connect()
                first.open(CAPABLE)
                runCurrent()
                route.open("c")
                val seed = first.queries().single()
                first.push(batch(seed, 10, listOf(item(1, 5, 1, text = "newer"))))
                first.push(window(seed, 10, 1, 2, false))
                runCurrent()
                val certified = route.observe("c").first().checkpoint

                val second = host.connect()
                second.open(CAPABLE)
                runCurrent()
                route.open("c")
                val catchUp = second.queries().single()
                assertEquals(certified.toString(), field(catchUp, "version"))
                second.push(batch(catchUp, 20, listOf(item(1, 3, 1, text = "older"), item(2, 1, 2))))
                runCurrent()
                host.connections.value = null
                runCurrent()

                val held = route.observe("c").first()
                assertEquals(5L, held.items.single { it.id == 1L }.rev)
                assertEquals(listOf(1L, 2L), held.items.map { it.id }.sorted())
                assertEquals(certified, held.checkpoint)
                val third = host.connect()
                third.open(CAPABLE)
                runCurrent()
                route.open("c")
                assertEquals(certified.toString(), field(third.queries().single(), "version"))
            } finally {
                host.coordinator.close()
            }
        }

    private fun TestScope.host(id: String): Host {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<Pump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections = connections,
                relayStatus = MutableStateFlow(RelayLinkStatus.Connected),
                createPump = { Pump().also { pumps += it } },
                dispatcher = StandardTestDispatcher(testScheduler),
                authoritativeHostId = id,
            )
        coordinator.start()
        return Host(connections, pumps, coordinator, this)
    }

    private class Host(
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: MutableList<Pump>,
        val coordinator: RelayRepositoryCoordinator,
        val test: TestScope,
    ) {
        fun connect(): Pump {
            connections.value = Transport()
            test.runCurrent()
            return pumps.last()
        }
    }

    private class Pump : ManagedSessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val sent = mutableListOf<Envelope>()

        override fun start() = Unit

        override fun send(envelope: Envelope): Boolean {
            if (state.value !is PumpState.Open) return false
            sent += envelope
            return true
        }

        override fun close() {
            channel.close()
        }

        fun open(capabilities: Set<String>) {
            state.value = PumpState.Open("conn", capabilities)
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun queries() = sent.filter { it.type == "thread_catch_up" || it.type == "thread_page" }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2): Boolean = true

        override fun close() = Unit
    }

    private fun field(
        frame: Envelope,
        name: String,
    ) = ((frame.payload as JsonObject)[name] as JsonPrimitive).content

    private fun reply(
        request: Envelope,
        raw: JsonObject,
    ) = Envelope(1000, "thread_items", "2026-10-17T10:00:00Z", raw, inReplyTo = request.id)

    private fun batch(
        request: Envelope,
        version: Long,
        items: List<ThreadItemDto>,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(items.map { it.raw }))
            put("final", false)
        },
    )

    private fun window(
        request: Envelope,
        version: Long,
        lower: Long,
        upper: Long,
        older: Boolean,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", lower)
            put("upper_order", upper)
            put("continuation", lower)
            put("older_exists", older)
        },
    )

    private companion object {
        val CAPABLE = setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD)
        val CONTENT_REQUESTS = setOf("request_history", "backfill_since", "thread_catch_up", "thread_page")
    }
}
