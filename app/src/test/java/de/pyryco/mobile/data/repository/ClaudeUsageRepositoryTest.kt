package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeUsageRepositoryTest {
    @Test
    fun arrivalOrderReplacesWholeReadingsAndDuplicatesDoNotAccumulate() =
        runTest {
            val host = host()
            try {
                val pump = open(host)
                val values = collect(host.facade.observeClaudeUsage())
                assertNull(values.last())
                val first = observed(""""five_hour":{"utilization":0,"resets_at":1000},"seven_day":{"utilization":0.5}""")
                pump.push(first.copy(inReplyTo = pump.asks().single().id))
                runCurrent()
                val reading = values.last() as ClaudeUsageReading.Observed
                assertEquals(0.0, reading.fiveHour?.utilization)
                assertEquals(0.5, reading.sevenDay?.utilization)
                repeat(3) { pump.push(first) }
                runCurrent()
                assertEquals(2, values.size)
                assertEquals(1, pump.asks().size)

                // The older observation instant arrives later and replaces the whole reading.
                pump.push(observed(""""seven_day":{}""", "2020-01-01T00:00:00Z"))
                runCurrent()
                assertEquals(
                    ClaudeUsageReading.Observed(Instant.parse("2020-01-01T00:00:00Z"), sevenDay = ClaudeUsageReading.Window()),
                    values.last(),
                )
                pump.push(observed())
                runCurrent()
                assertEquals(ClaudeUsageReading.Observed(Instant.parse(TS)), values.last())
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun unavailableStatesReplaceAndMalformedFramesPreserve() =
        runTest {
            val host = host()
            try {
                val pump = open(host)
                val values = collect(host.facade.observeClaudeUsage())
                pump.push(frame("""{"state":"observed","observed_at":false}"""))
                runCurrent()
                assertNull(values.last())
                for (state in listOf("none", "future-untrusted-state")) {
                    pump.push(observed(""""five_hour":{"utilization":0.6}"""))
                    runCurrent()
                    pump.push(frame("""{"state":"$state"}"""))
                    runCurrent()
                    val expected = if (state == "none") ClaudeUsageReading.None else ClaudeUsageReading.Unavailable
                    assertEquals(expected, values.last())
                    pump.push(frame("""{"state":"observed","observed_at":"bad"}"""))
                    runCurrent()
                    assertEquals(expected, values.last())
                }
                pump.push(observed(""""five_hour":{"utilization":0}"""))
                runCurrent()
                val last = values.last()
                pump.push(frame("""{"state":"none","five_hour":false}"""))
                runCurrent()
                assertEquals(last, values.last())
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun unauthenticatedAndClosedPumpsNeverRequestOrAcceptAllowance() =
        runTest {
            val host = host()
            try {
                val values = collect(host.facade.observeClaudeUsage())
                host.connections.value = Transport()
                runCurrent()
                val pump = host.pumps.single()
                pump.push(observed())
                runCurrent()
                assertNull(values.last())
                assertTrue(pump.sent.isEmpty())
                pump.state.value = PumpState.Closed(null)
                runCurrent()
                assertTrue(pump.sent.isEmpty())
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun refreshIsUngatedAndRefusalsDoNotStopObservation() =
        runTest {
            val host = host()
            try {
                host.facade.requestClaudeUsage() // offline is a silent no-op
                val pump = open(host) // no capabilities and no conversation lookup
                val values = collect(host.facade.observeClaudeUsage())
                collect(host.facade.observeClaudeUsage())
                host.facade.requestClaudeUsage()
                runCurrent()
                assertEquals(2, pump.asks().size)
                assertEquals(
                    2,
                    pump
                        .asks()
                        .map { it.id }
                        .distinct()
                        .size,
                )
                assertTrue(pump.sent.all { it.type == "request_claude_usage" })
                pump.asks().forEach {
                    assertEquals(JsonObject(emptyMap()), it.payload)
                    assertNull(it.inReplyTo)
                }
                pump.push(observed())
                runCurrent()
                val held = values.last()
                pump.accept = false
                host.facade.requestClaudeUsage()
                pump.throwOnSend = true
                host.facade.requestClaudeUsage()
                runCurrent()
                assertEquals(held, values.last())
                pump.push(frame("""{"state":"none"}"""))
                runCurrent()
                assertEquals(ClaudeUsageReading.None, values.last())
                host.connections.value = null
                runCurrent()
                val sent = pump.sent.size
                host.facade.requestClaudeUsage()
                assertEquals(sent, pump.sent.size)
                assertEquals(ClaudeUsageReading.None, values.last())
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun reconnectRefreshesWithoutBlankingAndRetiredSourcesCannotReplace() =
        runTest {
            val host = host()
            try {
                val first = open(host)
                val values = collect(host.facade.observeClaudeUsage())
                first.push(observed(""""five_hour":{"utilization":0.4}"""))
                runCurrent()
                val held = values.last()
                val oldRepo = host.coordinator.currentRepository.value!!
                host.connections.value = null
                runCurrent()
                assertEquals(held, values.last())
                host.connections.value = Transport()
                runCurrent()
                val second = host.pumps.last()
                assertTrue(second.sent.isEmpty())
                assertEquals(held, values.last())
                second.open()
                runCurrent()
                assertEquals(1, second.asks().size)
                second.open("rekey")
                runCurrent()
                assertEquals(1, second.asks().size)
                second.push(frame("""{"state":"observed","observed_at":"invalid"}"""))
                first.push(frame("""{"state":"none"}"""))
                oldRepo.requestClaudeUsage()
                runCurrent()
                assertEquals(held, values.last())
                assertEquals(1, first.asks().size)

                // Direct replacement before the coordinator collector has run: old frames/sends are retired.
                val secondRepo = host.coordinator.currentRepository.value!!
                host.connections.value = Transport()
                second.push(frame("""{"state":"none"}"""))
                secondRepo.requestClaudeUsage()
                assertEquals(1, second.asks().size)
                runCurrent()
                assertEquals(held, values.last())
                val third = host.pumps.last()
                third.open()
                runCurrent()
                third.push(frame("""{"state":"none"}""").copy(inReplyTo = third.asks().single().id))
                runCurrent()
                assertEquals(ClaudeUsageReading.None, values.last())
                assertEquals(1, values.count { it == null })
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun sameHostFacadesShareAndOtherHostsStayIsolated() =
        runTest {
            val a = host()
            val b = host()
            try {
                val pumpA = open(a)
                val pumpB = open(b)
                // Two thread destinations have independent facade objects for the same host holder.
                val anotherThread = StableConversationRepository(a.coordinator.currentRepository, a.coordinator.hostReadings)
                val first = collect(a.facade.observeClaudeUsage())
                val second = collect(anotherThread.observeClaudeUsage())
                val other = collect(b.facade.observeClaudeUsage())
                pumpA.push(observed(""""five_hour":{"utilization":0.1},"conversation_id":"same","session_id":"same""""))
                runCurrent()
                assertEquals(first.last(), second.last())
                assertNull(other.last())
                pumpB.push(frame("""{"state":"none","conversation_id":"same","session_id":"same"}"""))
                runCurrent()
                assertEquals(ClaudeUsageReading.None, other.last())
                assertTrue(first.last() is ClaudeUsageReading.Observed)
                anotherThread.requestClaudeUsage()
                assertEquals(2, pumpA.asks().size)
                assertEquals(1, pumpB.asks().size)
            } finally {
                a.coordinator.close()
                b.coordinator.close()
            }
        }

    @Test
    fun pairingDisposalClearsAndRePairStartsEmpty() =
        runTest {
            val old = host()
            val pump = open(old)
            val values = collect(old.facade.observeClaudeUsage())
            pump.push(observed())
            runCurrent()
            old.coordinator.close()
            runCurrent()
            assertNull(values.last())
            val fresh = host() // same server/connection IDs with a new pairing owner
            try {
                val newPump = open(fresh)
                val freshValues = collect(fresh.facade.observeClaudeUsage())
                pump.push(observed())
                runCurrent()
                assertNull(values.last())
                assertNull(freshValues.last())
                newPump.push(frame("""{"state":"none"}"""))
                runCurrent()
                assertEquals(ClaudeUsageReading.None, freshValues.last())
            } finally {
                fresh.coordinator.close()
            }
        }

    @Test
    fun allowanceNeverChangesRowsReplayOrRateLimitedState() =
        runTest {
            val host = host()
            try {
                val pump = open(host, setOf(CAPABILITY_INTERACTIVE))
                val repo = host.coordinator.currentRepository.value!!
                val rows = collect(repo.observeMessages("same"))
                pump.push(HostReadingFrames.rateLimited("same"))
                runCurrent()
                val warning = repo.observeUsageLimit("same").first()
                assertFalse(warning == null)
                val cursor = host.coordinator.replayCursor.latest
                val beforeRows = rows.toList()
                pump.push(observed().copy(eventId = 9999))
                runCurrent()
                assertEquals(beforeRows, rows)
                assertEquals(cursor, host.coordinator.replayCursor.latest)
                assertEquals(warning, repo.observeUsageLimit("same").first())
            } finally {
                host.coordinator.close()
            }
        }

    @Test
    fun compatibilityFacadeSwitchesAndFakeDefaultsRemainUsable() =
        runTest {
            val host = host()
            try {
                val facade = StableConversationRepository(host.coordinator.currentRepository)
                val values = collect(facade.observeClaudeUsage())
                val pump = open(host)
                pump.push(observed())
                runCurrent()
                assertTrue(values.last() is ClaudeUsageReading.Observed)
                facade.requestClaudeUsage()
                assertEquals(2, pump.asks().size)
                host.connections.value = null
                runCurrent()
                assertNull(values.last())
                val fake = FakeConversationRepository()
                assertNull(fake.observeClaudeUsage().first())
                fake.requestClaudeUsage()
            } finally {
                host.coordinator.close()
            }
        }

    private data class Host(
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: MutableList<Pump>,
        val coordinator: RelayRepositoryCoordinator,
        val facade: ConversationRepository,
    )

    private fun TestScope.host(): Host {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<Pump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections,
                MutableStateFlow(RelayLinkStatus.Connected),
                { Pump().also { pumps += it } },
                StandardTestDispatcher(testScheduler),
            )
        coordinator.start()
        return Host(connections, pumps, coordinator, StableConversationRepository(coordinator.currentRepository, coordinator.hostReadings))
    }

    private fun TestScope.open(
        host: Host,
        capabilities: Set<String> = emptySet(),
    ): Pump {
        host.connections.value = Transport()
        runCurrent()
        return host.pumps.last().also {
            it.open(capabilities = capabilities)
            runCurrent()
        }
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> =
        mutableListOf<T>().also { values ->
            backgroundScope.launch { flow.collect { values += it } }
            runCurrent()
        }

    private class Pump : ManagedSessionPump {
        private val incoming = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = incoming.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val sent = mutableListOf<Envelope>()
        var accept = true
        var throwOnSend = false

        override fun start() = Unit

        override fun close() = Unit // retained hostile source can still enqueue late frames

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            if (throwOnSend) error("send refused")
            return accept
        }

        fun open(
            id: String = "same",
            capabilities: Set<String> = emptySet(),
        ) {
            state.value = PumpState.Open(id, capabilities)
        }

        fun push(envelope: Envelope) {
            incoming.trySend(envelope)
        }

        fun asks() = sent.filter { it.type == "request_claude_usage" }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }

    private fun observed(
        fields: String = "",
        timestamp: String = TS,
    ) = frame("""{"state":"observed","observed_at":"$timestamp"${if (fields.isEmpty()) "" else ",$fields"}}""")

    private fun frame(raw: String) = Envelope(100, "claude_usage", TS, MobileJson.parseToJsonElement(raw))

    private companion object {
        const val TS = "2026-10-17T12:00:00Z"
    }
}
