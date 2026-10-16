package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuiteConnectionsTest {
    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload), reply)

    @Test fun replayRoutesPeerResultsToTheRealPhoneConnectionWithoutCreatingLiveObjects() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone", capabilities = setOf("interactive")),
                    SuiteStep("connect", "peer"),
                    SuiteStep("open", "peer", capabilities = setOf("interactive")),
                    SuiteStep("client", "peer", frame(1, "send_message")),
                    SuiteStep("daemon", "phone", frame(2, "message", """{"text":"recorded reply"}""")),
                    SuiteStep("daemon", "peer", frame(2, "ack", reply = 1)),
                    SuiteStep("close", "peer"),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val phoneTransport = hub.transport("phone") { error("Live transport started") }
            phoneTransport.connect()
            assertEquals(TransportEvent.Up, phoneTransport.events.first())
            val phone = hub.pump(phoneTransport) { error("Live pump started") }
            phone.start()
            runCurrent()
            assertEquals(setOf("interactive"), (phone.state.value as PumpState.Open).capabilities)
            val peerTransport = hub.transport("peer") { error("Live transport started") }
            peerTransport.connect()
            val peer = hub.pump(peerTransport) { error("Live pump started") }
            peer.start()
            runCurrent()
            val received = async { phone.inbound.first() }
            assertEquals(true, peer.send(frame(90, "send_message")))
            runCurrent()
            assertEquals("message", received.await().type)
            assertEquals(90L, peer.inbound.first().inReplyTo)
            peer.close()
            phone.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun aClosedPumpRefusesFurtherChunksWithoutSendingThemToTheRecordedDaemon() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.close()
            assertFalse(pump.send(frame(1, "attachment_chunk", """{"index":2}""")))
            hub.assertComplete()
            hub.close()
        }

    @Test fun aCapturedRejectedRequestAfterCloseStillRequiresItsCompletePayload() =
        runTest {
            for (changed in listOf(false, true)) {
                val expected = frame(1, "request_attachment", """{"attachment_id":"file"}""")
                val entries =
                    listOf(
                        SuiteStep("connect", "phone"),
                        SuiteStep("open", "phone"),
                        SuiteStep("close", "phone"),
                        SuiteStep("client", "phone", expected, accepted = false),
                    )
                val tape = RecordedSuiteTape(entries)
                val hub = SuiteConnections.replaying(backgroundScope, tape)
                val transport = hub.transport("phone") { error("Live transport started") }
                transport.connect()
                val pump = hub.pump(transport) { error("Live pump started") }
                pump.start()
                runCurrent()
                pump.close()
                if (changed) {
                    assertThrows(
                        IllegalStateException::class.java,
                    ) { pump.send(frame(80, "request_attachment", """{"attachment_id":"other"}""")) }
                    assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
                } else {
                    assertFalse(pump.send(expected.copy(id = 80, payload = tape.arguments(expected.payload.jsonObject))))
                    hub.assertComplete()
                }
                hub.close()
            }
        }

    @Test fun recordedCadencePreservesIntermediateObservations() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone"),
                    SuiteStep("client", "phone", frame(1, "send_message")),
                    SuiteStep("daemon", "phone", frame(2, "turn_state")),
                    SuiteStep("daemon", "phone", frame(3, "turn_end"), delayMs = 500),
                    SuiteStep("close", "phone"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val transport = hub.transport("phone") { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.send(frame(20, "send_message"))
            runCurrent()
            assertEquals("turn_state", pump.inbound.first().type)
            val finished = async { pump.inbound.first() }
            advanceTimeBy(499)
            runCurrent()
            assertFalse(finished.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("turn_end", finished.await().type)
            pump.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun anUnexpectedControlActionFailsTheWholeReplay() =
        runTest {
            val tape = RecordedSuiteTape(listOf(SuiteStep("client", "control", frame(1, "test-control:test-begin"))))
            val hub = SuiteConnections.replaying(backgroundScope, tape)
            assertThrows(IllegalStateException::class.java) { hub.control("stop", "") { error("Real control invoked") } }
            assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            hub.close()
        }

    @Test fun controlResponsesReplayWithoutCallingTheHostAndStillRequireTheExactRequest() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val body = kotlinx.serialization.json.JsonObject(emptyMap())
        val response = MobileJson.parseToJsonElement("""{"state":"held"}""").jsonObject
        val recording = SuiteConnections.recording(scope)
        assertEquals(response, recording.exchangeControl("task/status", body) { response })
        val steps = recording.completedCapture(true)
        recording.close()
        val replay = SuiteConnections.replaying(scope, RecordedSuiteTape(steps))
        assertEquals(response, replay.exchangeControl("task/status", body) { error("Real host invoked during replay") })
        kotlinx.coroutines.runBlocking { replay.awaitIdle() }
        replay.assertComplete()
        replay.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun observingALivePumpCannotDelayItsPublishedConnectionState() =
        runTest {
            val published = kotlinx.coroutines.flow.MutableStateFlow<PumpState>(PumpState.Handshaking)
            val delegate =
                object : de.pyryco.mobile.data.repository.ManagedSessionPump {
                    override val state = published
                    override val inbound = kotlinx.coroutines.flow.emptyFlow<Envelope>()

                    override fun start() { }

                    override fun send(envelope: Envelope) = true

                    override fun close() {
                        published.value = PumpState.Closed(null)
                    }
                }
            val transport =
                object : de.pyryco.mobile.data.network.RelayTransport {
                    override val inbound = kotlinx.coroutines.flow.emptyFlow<de.pyryco.mobile.data.network.InnerFrameV2>()
                    override val events = kotlinx.coroutines.flow.emptyFlow<de.pyryco.mobile.data.network.TransportEvent>()

                    override fun connect() { }

                    override fun close() { }

                    override fun send(frame: de.pyryco.mobile.data.network.InnerFrameV2) = true
                }
            val hub = SuiteConnections.recording(backgroundScope)
            val observed = hub.pump(hub.transport("phone") { transport }) { delegate }
            val opened = PumpState.Open("connection", setOf("interactive"))
            published.value = opened
            assertEquals(opened, observed.state.value) // No scheduler turn may be required by the observation tap.
            observed.close()
            hub.close()
        }

    @Test fun aRemotePumpEndClosesItsTransportBeforeThePeerRedials() =
        runTest {
            val entries =
                listOf(
                    SuiteStep("connect", "peer/host/0"),
                    SuiteStep("open", "peer/host/0"),
                    SuiteStep("client", "control", frame(1, "test-control:stop", """{"body":""}""")),
                    SuiteStep("down", "peer/host/0"),
                    SuiteStep("close", "peer/host/0"),
                    SuiteStep("inbound_end", "peer/host/0"),
                    SuiteStep("connect", "peer/host/1"),
                    SuiteStep("open", "peer/host/1"),
                    SuiteStep("close", "peer/host/1"),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(entries))
            val first = hub.transport("peer/host/0") { error("Live transport started") }
            first.connect()
            val pump = hub.pump(first) { error("Live pump started") }
            pump.start()
            runCurrent()
            hub.control("stop", "") { error("Live control started") }
            runCurrent()
            assertEquals(PumpState.Closed(null), pump.state.value)
            val second = hub.transport("peer/host/1") { error("Live transport started") }
            second.connect()
            val next = hub.pump(second) { error("Live pump started") }
            next.start()
            runCurrent()
            assertEquals(true, next.state.value is PumpState.Open)
            next.close()
            hub.assertComplete()
            hub.close()
        }

    @Test fun aRejectedCloseStillClosesThePumpAndCannotMaskTheScenarioFailure() =
        runTest {
            val owner = "phone/host/0"
            val rows =
                listOf(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner),
                    SuiteStep("client", owner, frame(1, "send_message")),
                    SuiteStep("close", owner),
                )
            val hub = SuiteConnections.replaying(backgroundScope, RecordedSuiteTape(rows))
            val transport = hub.transport(owner) { error("Live transport started") }
            transport.connect()
            val pump = hub.pump(transport) { error("Live pump started") }
            pump.start()
            runCurrent()
            pump.close()
            assertEquals(PumpState.Closed(null), pump.state.value)
            assertThrows(IllegalStateException::class.java) { hub.assertComplete() }
            hub.close()
        }
}
