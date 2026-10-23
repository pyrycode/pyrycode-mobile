package de.pyryco.mobile.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.HostReadings
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.e2e.SettingsReadProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The native scenario's post-turn settings ordering, without a daemon or a timing race. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InheritedEffortReplayTest {
    @Test fun observationWaitsForSettingsStillHeldBehindTheirCausalQuery() =
        runTest {
            Fixture(this, holdQuery = true).use { fixture ->
                fixture.ready()
                assertEquals(EffectiveEffort.Applied("high"), fixture.read().effectiveEffort)
                fixture.releaseLaterSettings()
                val observation = async { fixture.beforeObservation() }
                runCurrent()
                assertFalse("an empty queue cannot skip unreleased settings", observation.isCompleted)
                fixture.releaseQuery()
                observation.await()
                val reading = fixture.read()
                fixture.hub.awaitIdle()
                assertEquals(EffectiveEffort.Applied("high"), reading.effectiveEffort)
                assertEquals(reading, fixture.repo.observeSessionSettings("chat").first())
                fixture.finish()
            }
        }

    @Test fun finalReadCannotBeOverwrittenByReleasedMinimalSettings() =
        runTest {
            Fixture(this).use { fixture ->
                fixture.ready()
                val independent = fixture.read()
                assertEquals("no saved effort override", "", independent.effort)
                assertEquals(EffectiveEffort.Applied("high"), independent.effectiveEffort)
                fixture.releaseLaterSettings()

                fixture.beforeObservation()
                val observed = fixture.read()
                fixture.hub.awaitIdle()
                assertEquals(
                    "the independent correlated reply was complete",
                    EffectiveEffort.Applied("high"),
                    observed.effectiveEffort,
                )
                assertEquals(
                    "the sheet must still receive that applied value",
                    observed,
                    fixture.repo.observeSessionSettings("chat").first(),
                )
                assertEquals("default", observed.permissionMode)
                // Repeating the boundary creates no request and cannot reuse the earlier read's receipt.
                fixture.beforeObservation()
                assertEquals(observed, fixture.read())
                fixture.finish()
            }
        }

    @Test fun observationWaitsForDownstreamConsumptionAfterRouting() =
        runTest {
            Fixture(this, holdMinimal = true).use { fixture ->
                fixture.ready()
                assertEquals(EffectiveEffort.Applied("high"), fixture.read().effectiveEffort)
                fixture.releaseLaterSettings()
                val observation = async { fixture.beforeObservation() }
                advanceTimeBy(1_000)
                runCurrent()
                assertTrue("the minimal notification reached the held collector", fixture.minimalEntered.isCompleted)
                assertFalse("routing the snapshot cannot acknowledge app consumption", observation.isCompleted)
                fixture.allowMinimal.complete(Unit)
                observation.await()
                assertEquals(EffectiveEffort.Applied("high"), fixture.read().effectiveEffort)
                fixture.finish()
            }
        }

    @Test fun missingAppliedEffortRemainsUnavailableAfterTheFence() =
        runTest {
            Fixture(this, finalApplied = false).use { fixture ->
                fixture.ready()
                assertEquals(EffectiveEffort.Applied("high"), fixture.read().effectiveEffort)
                fixture.releaseLaterSettings()
                fixture.beforeObservation()
                val reading = fixture.read()
                assertEquals("", reading.effort)
                assertEquals(EffectiveEffort.Unavailable, reading.effectiveEffort)
                assertEquals(reading, fixture.repo.observeSessionSettings("chat").first())
                fixture.finish()
            }
        }

    @Test fun aClosedOwnerCannotCompleteTheObservation() =
        runTest {
            Fixture(this, holdMinimal = true).use { fixture ->
                fixture.ready()
                fixture.read()
                fixture.releaseLaterSettings()
                val observation = async { runCatching { fixture.beforeObservation() } }
                advanceTimeBy(1_000)
                runCurrent()
                assertTrue(fixture.minimalEntered.isCompleted)
                fixture.closePhone()
                fixture.allowMinimal.complete(Unit)
                assertTrue("a closed pump cannot certify this observation", observation.await().exceptionOrNull() is IllegalStateException)
            }
        }

    private class Fixture(
        scope: TestScope,
        private val holdMinimal: Boolean = false,
        finalApplied: Boolean = true,
        holdQuery: Boolean = false,
    ) : AutoCloseable {
        private val owner = "phone/host/0"
        private val release = frame(1, "test-control:checkpoint", """{"body":"later-settings"}""")
        private val minimal = settings(4, applied = false)
        private val query = frame(3, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":0}""")
        private val tape =
            RecordedSuiteTape(
                listOfNotNull(
                    SuiteStep("connect", owner),
                    SuiteStep("open", owner, capabilities = setOf("thread", "interactive")),
                    SuiteStep(
                        "daemon",
                        owner,
                        frame(1, "session_settings", "{}").copy(
                            conversationId = JsonPrimitive("chat"),
                            sessionProvenance = SessionProvenance(JsonPrimitive("session")),
                            sessionStateCleared = JsonPrimitive(true),
                        ),
                    ),
                    SuiteStep("client", owner, frame(1, "request_session_settings", """{"conversation_id":"chat"}""")),
                    SuiteStep("daemon", owner, settings(2, applied = true, reply = 1)),
                    SuiteStep("client", "control", release),
                    SuiteStep("client", owner, query).takeIf { holdQuery },
                    SuiteStep(
                        "daemon",
                        owner,
                        frame(
                            3,
                            "thread_items",
                            """{"conversation_id":"chat","epoch":"epoch","version":0,"items":[],"final":true,"lower_order":0,"upper_order":0,"continuation":0,"older_exists":false}""",
                            reply = 3,
                        ),
                    ).takeIf { holdQuery },
                    SuiteStep("daemon", owner, minimal, delayMs = 100),
                    SuiteStep("client", owner, frame(2, "request_session_settings", """{"conversation_id":"chat"}""")),
                    SuiteStep("daemon", owner, settings(5, applied = finalApplied, reply = 2), delayMs = 100),
                    SuiteStep("close", owner),
                    SuiteStep("inbound_end", owner),
                ),
                literalProducerIdentities = true,
                recordedSettingsReads = true,
            )
        val hub = SuiteConnections.replaying(scope.backgroundScope, tape)
        private val transport = hub.transport(owner) { error("Live transport started") }
        private val pump = hub.pump(transport) { error("Live pump started") }
        val minimalEntered = CompletableDeferred<Unit>()
        val allowMinimal = CompletableDeferred<Unit>()
        private val observedPump =
            object : ManagedSessionPump by pump {
                override val inbound =
                    pump.inbound.onEach {
                        if (holdMinimal && it.id == minimal.id) {
                            minimalEntered.complete(Unit)
                            allowMinimal.await()
                        }
                    }
            }
        private val readings = HostReadings()
        val repo =
            RemoteConversationRepository(
                SettingsReadProbe.wrap(observedPump),
                CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler)),
                negotiatedCapabilities = { setOf("thread", "interactive") },
                authoritativeHostId = "host",
                hostReadings = readings,
            )

        suspend fun ready() {
            pump.start()
            transport.connect()
            hub.awaitIdle()
        }

        suspend fun read() = SettingsReadProbe.read(repo, "chat", { true }, { readings.liveSessionBindings["chat"] })

        fun releaseLaterSettings() = hub.control("checkpoint", "later-settings") { error("Live control started") }

        fun releaseQuery() = assertTrue(pump.send(query.copy(id = 90)))

        suspend fun beforeObservation() = hub.beforeSettingsObservation(owner)

        suspend fun finish() {
            transport.close()
            hub.awaitIdle()
            hub.assertComplete()
        }

        fun closePhone() = transport.close()

        override fun close() = hub.close()

        private fun settings(
            id: Long,
            applied: Boolean,
            reply: Long? = null,
        ): Envelope {
            val extra = if (applied) ",\"effective_effort\":\"high\"" else ""
            return frame(
                id,
                "session_settings",
                """{"session_id":"session","model":"","effort":"","yolo":false,"permission_mode":"default","used_tokens":1,"window_tokens":100$extra}""",
                reply,
            ).copy(
                conversationId = JsonPrimitive("chat"),
                sessionProvenance = SessionProvenance(JsonPrimitive("session")),
            )
        }

        private fun frame(
            id: Long,
            type: String,
            payload: String,
            reply: Long? = null,
        ) = Envelope(
            id,
            type,
            "2026-10-23T00:00:00Z",
            MobileJson.parseToJsonElement(payload) as JsonObject,
            reply,
        )
    }
}
