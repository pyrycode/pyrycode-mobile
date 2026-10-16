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
}
