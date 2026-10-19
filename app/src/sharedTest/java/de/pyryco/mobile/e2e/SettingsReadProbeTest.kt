package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.HostReadings
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReadProbeTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun earlierSnapshotsCannotCompleteCurrentRead() =
        runTest {
            val fixture = Fixture(this)
            fixture.deliver(fixture.reply())
            runCurrent()
            val result = async { fixture.read() }
            runCurrent()
            assertFalse("earlier minimal snapshot is not this read", result.isCompleted)
            val request = fixture.request()
            fixture.deliver(fixture.reply(request.id - 1))
            fixture.deliver(fixture.reply(request.id).copy(conversationId = JsonPrimitive("other")))
            fixture.deliver(fixture.reply(request.id).copy(sessionStateCleared = JsonPrimitive(true), payload = JsonObject(emptyMap())))
            runCurrent()
            assertFalse("unrelated and cleared observations cannot complete", result.isCompleted)
            fixture.deliver(fixture.reply(request.id, "high"))
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
        }

    @Test fun immediateReplyIsRetained() =
        runTest {
            val fixture = Fixture(this)
            fixture.pump.onSend = { fixture.deliver(fixture.reply(it.id, "high")) }
            val result = async { fixture.read() }
            runCurrent()
            assertTrue("a read was sent", fixture.pump.sent.any { it.type == "request_session_settings" })
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
            assertTrue("the repository consumed the immediate reply before send returned", fixture.pump.deliveredDuringSend)
        }

    @Test fun equalValuedReplyCompletes() =
        runTest {
            val fixture = Fixture(this)
            fixture.deliver(fixture.reply(effective = "high"))
            runCurrent()
            val previous = fixture.repo.observeSessionSettings("c").first()
            val result = async { fixture.read() }
            runCurrent()
            assertFalse("value equality is not freshness", result.isCompleted)
            fixture.deliver(fixture.reply(fixture.request().id, "high"))
            assertEquals(previous, result.await())
        }

    @Test fun missingAppliedFieldIsReturned() =
        runTest {
            val fixture = Fixture(this)
            fixture.deliver(fixture.reply(effective = "high"))
            val result = async { fixture.read() }
            runCurrent()
            fixture.deliver(fixture.reply(fixture.request().id))
            val reading = result.await()
            assertEquals("saved effort stays separate", "high", reading.effort)
            assertEquals(EffectiveEffort.Unavailable, reading.effectiveEffort)
        }

    @Test fun laterSnapshotDoesNotReplaceTheCorrelatedReply() =
        runTest {
            val fixture = Fixture(this)
            val result = async { fixture.read() }
            runCurrent()
            fixture.deliver(fixture.reply(fixture.request().id, "high"))
            fixture.deliver(fixture.reply())
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
        }

    @Test fun legacyHeldSnapshotCannotCompleteTheRead() =
        runTest {
            val fixture = Fixture(this, authoritative = false)
            fixture.readings.holdSessionSettings("c", fixture.reply().payload.toSessionSettings())
            val result = async { fixture.read() }
            runCurrent()
            assertFalse(result.isCompleted)
            val reply = fixture.reply(fixture.request().id, "high")
            fixture.deliver(reply.copy(conversationId = null, sessionProvenance = SessionProvenance()))
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
        }

    @Test fun replacementCannotReturnRetiredReply() =
        runTest {
            val fixture = Fixture(this)
            val result = async { runCatching { fixture.read() } }
            runCurrent()
            val request = fixture.request()
            fixture.current = false
            fixture.deliver(fixture.reply(request.id, "high"))
            assertTrue("replacement fails explicitly", result.await().isFailure)
        }

    @Test fun wrongHostAndSessionCannotCompleteAnEqualReading() =
        runTest {
            val fixture = Fixture(this)
            val otherHost = Fixture(this)
            fixture.deliver(fixture.reply(effective = "high"))
            val result = async { fixture.read() }
            runCurrent()
            val request = fixture.request()
            otherHost.deliver(otherHost.reply(request.id, "high"))
            fixture.deliver(fixture.reply(request.id, "high").copy(sessionProvenance = SessionProvenance(JsonPrimitive("wrong"))))
            fixture.deliver(fixture.reply(request.id, "high").copy(eventId = 1))
            runCurrent()
            assertFalse("neither matching values nor foreign ownership proves a reply", result.isCompleted)
            fixture.deliver(fixture.reply(request.id, "high"))
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
        }

    @Test fun cancelledReadCannotSupplyTheNextCallsReply() =
        runTest {
            val fixture = Fixture(this)
            val first = async { fixture.read() }
            runCurrent()
            val retired = fixture.request()
            first.cancel()
            runCurrent()
            val next = async { fixture.read() }
            runCurrent()
            val current = fixture.request()
            assertTrue("each call establishes its own read", retired.id != current.id)
            fixture.deliver(fixture.reply(retired.id, "high"))
            runCurrent()
            assertFalse(next.isCompleted)
            fixture.deliver(fixture.reply(current.id, "high"))
            assertEquals(EffectiveEffort.Applied("high"), next.await().effectiveEffort)
        }

    @Test fun closedPumpFailsTheOutstandingRead() =
        runTest {
            val fixture = Fixture(this)
            val result = async { runCatching { fixture.read() } }
            runCurrent()
            fixture.pump.close()
            assertTrue(result.await().isFailure)
        }

    @Test fun sessionReplacementAbandonsRead() =
        runTest {
            val fixture = Fixture(this)
            val result = async { runCatching { fixture.read() } }
            runCurrent()
            val request = fixture.request()
            fixture.deliver(fixture.clear("next"))
            // Even reuse of the original identity cannot revive the outstanding read.
            fixture.deliver(fixture.clear("s"))
            fixture.deliver(fixture.reply(request.id, "high"))
            assertTrue(result.await().isFailure)
        }

    @Test fun legacySubscriptionCorrelatesItsRead() =
        runTest {
            val fixture = Fixture(this, authoritative = false)
            val result = async { fixture.read() }
            runCurrent()
            val reply = fixture.reply(fixture.request().id, "high")
            fixture.deliver(reply.copy(conversationId = null, sessionProvenance = SessionProvenance()))
            assertEquals(EffectiveEffort.Applied("high"), result.await().effectiveEffort)
        }

    @Test fun refusedSendFailsExplicitly() =
        runTest {
            val fixture = Fixture(this)
            fixture.pump.accepted = false
            val result = async { runCatching { fixture.read() } }
            runCurrent()
            assertTrue(result.await().isFailure)
        }

    private class Fixture(
        scope: TestScope,
        val authoritative: Boolean = true,
    ) {
        val pump = Pump()
        val readings = HostReadings()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val repositoryScope = CoroutineScope(scope.backgroundScope.coroutineContext + dispatcher)
        val repo =
            RemoteConversationRepository(
                SettingsReadProbe.wrap(pump),
                repositoryScope,
                negotiatedCapabilities = { if (authoritative) setOf("thread", "interactive") else setOf("interactive") },
                authoritativeHostId = if (authoritative) "host" else "",
                hostReadings = readings,
            )
        var current = true

        init {
            if (authoritative) deliver(clear("s"))
        }

        suspend fun read() = SettingsReadProbe.read(repo, "c", { current }, { readings.liveSessionBindings["c"] })

        fun request() = pump.sent.last { it.type == "request_session_settings" }

        fun deliver(frame: Envelope) {
            check(pump.frames.trySend(frame).isSuccess)
        }

        fun clear(session: String) =
            Envelope(
                9,
                "session_settings",
                "2026-10-19T00:00:00Z",
                JsonObject(emptyMap()),
                conversationId = JsonPrimitive("c"),
                sessionProvenance = SessionProvenance(JsonPrimitive(session)),
                sessionStateCleared = JsonPrimitive(true),
            )

        fun reply(
            correlation: Long? = null,
            effective: String? = null,
        ) = Envelope(
            10,
            "session_settings",
            "2026-10-19T00:00:00Z",
            buildJsonObject {
                put("conversation_id", "c")
                put("session_id", "s")
                put("model", "")
                put("effort", "high")
                put("yolo", false)
                put("permission_mode", "default")
                put("used_tokens", 0)
                put("window_tokens", 0)
                effective?.let { put("effective_effort", it) }
            },
            inReplyTo = correlation,
            conversationId = JsonPrimitive("c"),
            sessionProvenance = SessionProvenance(JsonPrimitive("s")),
        )
    }

    private class Pump : ManagedSessionPump {
        val frames = Channel<Envelope>(Channel.UNLIMITED)
        val sent = mutableListOf<Envelope>()
        var onSend: (Envelope) -> Unit = {}
        var accepted = true
        private var sending = false
        var deliveredDuringSend = false
        override val inbound = frames.receiveAsFlow().onEach { if (sending) deliveredDuringSend = true }
        override val state = MutableStateFlow<PumpState>(PumpState.Open("connection", setOf("thread", "interactive")))

        override fun start() = Unit

        override fun close() {
            frames.close()
        }

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            sending = true
            try {
                onSend(envelope)
            } finally {
                sending = false
            }
            return accepted
        }
    }
}
