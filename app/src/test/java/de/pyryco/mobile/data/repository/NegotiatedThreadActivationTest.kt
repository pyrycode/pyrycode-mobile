package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Opt-in negotiation probes drive real connection owners, without the future screen/provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class NegotiatedThreadActivationTest {
    private val oldSink = RelayLog.sink

    @Before fun quietLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
    }

    @Test fun authoritativeSnapshotNeverBackfillsLegacyContent() =
        runTest {
            val host = host("h")
            try {
                val pump = host.connect()
                pump.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val repo = requireNotNull(host.coordinator.currentRepository.value) as RemoteConversationRepository
                backgroundScope.launch { repo.observeThreadSnapshot("c").collect {} }
                runCurrent()
                assertEquals(listOf("request_claude_usage"), pump.sent.map { it.type })
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun replacedTransportRejectsRetainedQueryCallbackBeforeCollectorsRun() =
        runTest {
            val host = host("h")
            try {
                val pump = host.connect()
                pump.open(setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD))
                runCurrent()
                val repo = requireNotNull(host.coordinator.currentRepository.value) as RemoteConversationRepository
                assertTrue(repo.openAuthoritativeThread("c"))
                host.connections.value = Transport()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(repo.openAuthoritativeThread("c"))
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun onlyBothAuthenticatedCapabilitiesGrantSupport() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf(CAPABILITY_THREAD), setOf(CAPABILITY_INTERACTIVE), CAPABLE)) {
                val host = host("h")
                try {
                    // Held facts cannot stand in for an authenticated acknowledgement.
                    host.coordinator.authoritativeThreads.applyBatch("h", "c", "e", 10, listOf(item()), 0)
                    val pump = host.connect()
                    assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                    pump.open(capabilities)
                    runCurrent()
                    assertEquals(capabilities == CAPABLE, host.coordinator.threadSupport.value is ThreadSupport.Supported)
                    assertEquals(capabilities != CAPABLE, host.coordinator.threadMissing.value)
                    val repo = host.repo()
                    assertEquals(capabilities == CAPABLE, repo.openAuthoritativeThread("c"))
                } finally {
                    host.coordinator.close()
                }
            }
        }

    @Test fun reconnectReevaluatesVerdictAndReopensOnlyOnTheNewConnection() =
        runTest {
            val host = host("h")
            try {
                val first = host.connect()
                first.open(CAPABLE)
                runCurrent()
                val old = host.repo()
                val verdict = host.coordinator.threadSupport.value as ThreadSupport.Supported
                assertTrue(old.openAuthoritativeThread("c"))
                first.state.value = PumpState.Handshaking
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(old.openAuthoritativeThread("c"))
                host.connections.value = null
                runCurrent()
                val second = host.connect()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                second.open(CAPABLE)
                runCurrent()
                val next = host.coordinator.threadSupport.value as ThreadSupport.Supported
                assertNotSame(verdict.connection, next.connection)
                assertSame(host.repo(), next.connection)
                assertFalse(old.openAuthoritativeThread("c"))
                assertTrue(host.repo().openAuthoritativeThread("c"))
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
                val first = host.connect()
                first.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val missing = host.coordinator.threadSupport.value
                assertTrue(missing is ThreadSupport.Missing)
                first.state.value = PumpState.Handshaking
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                host.connections.value = null
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                val next = host.connect()
                assertSame(missing, host.coordinator.threadSupport.value)
                next.open(CAPABLE)
                runCurrent()
                assertTrue(host.coordinator.threadSupport.value is ThreadSupport.Supported)
                assertFalse(host.coordinator.threadMissing.value)
                host.coordinator.close()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun retiredConnectionCannotRestoreItsVerdictOrSendQueries() =
        runTest {
            val host = host("h")
            try {
                val retired = host.connect()
                retired.open(CAPABLE)
                runCurrent()
                val old = host.repo()
                assertTrue(old.openAuthoritativeThread("c"))
                val query = retired.queries().single()
                val replacement = host.connect()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                retired.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                retired.open(CAPABLE)
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(old.openAuthoritativeThread("c"))
                assertFalse(old.requestOlderAuthoritativeThread("c"))
                replacement.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val missing = host.coordinator.threadSupport.value
                retired.push(batch(query, item(text = "late")))
                retired.open(CAPABLE)
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                assertTrue(
                    host.coordinator.authoritativeThreads
                        .current("h", "c")
                        .items
                        .isEmpty(),
                )
                assertTrue(replacement.queries().isEmpty())
                host.coordinator.close()
                retired.open(emptySet())
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun missingThreadHostSendsNoContentRequests() =
        runTest {
            val missing = host("missing")
            val capable = host("capable")
            try {
                val absent = missing.connect()
                val present = capable.connect()
                // Obtain the repository synchronously before the asynchronous verdict collector runs.
                absent.open(CAPABLE)
                val early = missing.repo()
                assertSame(ThreadSupport.Unverified, missing.coordinator.threadSupport.value)
                assertLegacyRefused(early)
                absent.open(setOf(CAPABILITY_INTERACTIVE))
                present.open(CAPABLE)
                runCurrent()
                assertLegacyRefused(early)
                assertFalse(early.openAuthoritativeThread("c"))
                assertFalse(early.requestOlderAuthoritativeThread("c"))
                assertTrue(capable.repo().openAuthoritativeThread("c"))
                assertLegacyRefused(capable.repo())
                backgroundScope.launch { early.observeThreadSnapshot("c").collect {} }
                backgroundScope.launch { capable.repo().observeThreadSnapshot("c").collect {} }
                runCurrent()
                assertEquals(emptyList<String>(), absent.content())
                assertEquals(listOf("thread_catch_up"), present.content())
            } finally {
                missing.coordinator.close()
                capable.coordinator.close()
            }
        }

    @Test fun collidingHostsKeepSeparateStoresAndVerdicts() =
        runTest {
            val a = host("a")
            val b = host("b")
            try {
                val pumpA = a.connect()
                val pumpB = b.connect()
                pumpA.open(CAPABLE)
                pumpB.open(CAPABLE)
                runCurrent()
                for ((host, pump, text) in listOf(Triple(a, pumpA, "a"), Triple(b, pumpB, "b"))) {
                    host.coordinator.authoritativeThreads.reset(text, "c", "e")
                    assertTrue(host.repo().openAuthoritativeThread("c"))
                    val request = pump.queries().single()
                    pump.push(batch(request, item(text = text)))
                    pump.push(window(request))
                }
                runCurrent()
                assertEquals(
                    "a",
                    text(
                        a.coordinator.authoritativeThreads
                            .current("a", "c")
                            .items
                            .single(),
                    ),
                )
                assertEquals(
                    "b",
                    text(
                        b.coordinator.authoritativeThreads
                            .current("b", "c")
                            .items
                            .single(),
                    ),
                )
                assertTrue(
                    a.coordinator.authoritativeThreads
                        .current("b", "c")
                        .items
                        .isEmpty(),
                )
                val aVerdict = a.coordinator.threadSupport.value
                val replacement = b.connect()
                replacement.open(emptySet())
                runCurrent()
                pumpB.push(batch(pumpB.queries().single(), item(1, 9, 1, text = "stale")))
                runCurrent()
                assertSame(aVerdict, a.coordinator.threadSupport.value)
                assertTrue(b.coordinator.threadMissing.value)
                assertEquals(
                    "b",
                    text(
                        b.coordinator.authoritativeThreads
                            .current("b", "c")
                            .items
                            .single(),
                    ),
                )
            } finally {
                a.coordinator.close()
                b.coordinator.close()
            }
        }

    @Test fun pagingRequiresTheCurrentSupportedConnection() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                val repo = host.repo()
                assertTrue(repo.openAuthoritativeThread("c"))
                val query = pump.queries().single()
                pump.push(batch(query, item()))
                pump.push(window(query, older = true))
                runCurrent()
                assertTrue(repo.requestOlderAuthoritativeThread("c"))
                assertEquals(listOf("thread_catch_up", "thread_page"), pump.content())
                pump.state.value = PumpState.Handshaking
                runCurrent()
                assertFalse(repo.requestOlderAuthoritativeThread("c"))
                pump.open(setOf(CAPABILITY_THREAD))
                runCurrent()
                assertFalse(repo.requestOlderAuthoritativeThread("c"))
                assertEquals(2, pump.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun productionConfigurationRemainsLegacy() =
        runTest {
            val host = host("")
            try {
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(host.coordinator.threadMissing.value)
                assertFalse(
                    CAPABILITY_THREAD in HelloClientPayload(deviceName = "device", clientVersion = "version", token = "token").capabilities,
                )
                assertNull(host.repo().authoritativeStoreFor("h"))
                backgroundScope.launch { host.repo().observeThreadSnapshot("c").collect {} }
                runCurrent()
                assertEquals(listOf("backfill_since"), pump.content())
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun negotiationAndRefusalLogsHaveOnlyStaticCodes() =
        runTest {
            val messages = mutableListOf<String>()
            val enabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> messages += message }
            val host = host("sensitive-host")
            try {
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                assertLegacyRefused(host.repo())
                pump.state.value = PumpState.Handshaking
                runCurrent()
                pump.open(emptySet())
                runCurrent()
                val events =
                    messages.filter {
                        it.startsWith("event=thread_negotiation") ||
                            it.startsWith("event=legacy_content_request_refused")
                    }
                assertEquals(
                    setOf(
                        "event=thread_negotiation verdict=supported",
                        "event=thread_negotiation verdict=unverified",
                        "event=thread_negotiation verdict=missing",
                        "event=legacy_content_request_refused",
                    ),
                    events.toSet(),
                )
            } finally {
                host.coordinator.close()
                RelayLog.enabled = enabled
            }
        }

    private suspend fun assertLegacyRefused(repo: RemoteConversationRepository) {
        for (failure in listOf(
            runCatching { repo.requestHistory("c", "", 20) }.exceptionOrNull(),
            runCatching { repo.requestAttentionHistory("c") }.exceptionOrNull(),
        )) {
            assertTrue(failure is IllegalStateException)
            assertEquals("legacy_content_request_refused", failure?.message)
        }
    }

    private fun Host.repo() = requireNotNull(coordinator.currentRepository.value) as RemoteConversationRepository

    private fun Pump.queries() = sent.filter { it.type in setOf("thread_catch_up", "thread_page") }

    private fun Pump.content() =
        sent.map { it.type }.filter {
            it in
                setOf("request_history", "backfill_since", "thread_catch_up", "thread_page")
        }

    private fun field(
        frame: Envelope,
        name: String,
    ) = (frame.payload as JsonObject).getValue(name)

    private fun batch(
        request: Envelope,
        item: ThreadItemDto,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", 10)
            put("items", JsonArray(listOf(item.raw)))
            put("final", false)
        },
    )

    private fun window(
        request: Envelope,
        older: Boolean = false,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", 10)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", 1)
            put("upper_order", 2)
            put("continuation", 1)
            put("older_exists", older)
        },
    )

    private fun reply(
        request: Envelope,
        raw: JsonObject,
    ) = Envelope(1000, "thread_items", "2026-10-17T10:00:00Z", raw, inReplyTo = request.id)

    private companion object {
        val CAPABLE = setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD)
    }

    private fun TestScope.host(id: String): Host {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<Pump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections,
                MutableStateFlow(RelayLinkStatus.Connected),
                { Pump().also { pumps += it } },
                StandardTestDispatcher(testScheduler),
                authoritativeHostId = id,
            )
        coordinator.start()
        return Host(this, connections, pumps, coordinator)
    }

    private inner class Host(
        val testScope: TestScope,
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: List<Pump>,
        val coordinator: RelayRepositoryCoordinator,
    ) {
        fun connect(): Pump {
            connections.value = Transport()
            testScope.runCurrent()
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

        // Deliberately keep accepting late input and state: coordinator retirement must fence both.
        override fun close() = Unit

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun open(capabilities: Set<String>) {
            state.value = PumpState.Open("conn", capabilities)
        }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2): Boolean = true

        override fun close() = Unit
    }
}
