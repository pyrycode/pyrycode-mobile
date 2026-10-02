package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live refusal signal (#1360): [RemoteConversationRepository.observeLiveRefusalEvents] carries each live
 * refusal frame with its `scope`, and each session transition, in wire order. Only live frames reach it,
 * which is what keeps a history page or the cache from arming the thread's switch-back offer.
 */
class RemoteConversationRepositoryRefusalEventsTest {
    @Test
    fun liveRefusals_carryTheFoldedRowAndScope_inWireOrder() =
        runTest {
            val (pump, repo) = repo()
            val events = collect(repo.observeLiveRefusalEvents("c1"))
            runCurrent()

            pump.push(fallback("c1", "claude-opus-5-5", "claude-sonnet-5", scope = "session", ts = TS, id = 1L))
            pump.push(noFallback("c1", "claude-opus-5-5", ts = TS2, id = 2L))
            pump.push(sessionTransition("c1", id = 3L))
            runCurrent()

            assertEquals(
                listOf(
                    LiveRefusalEvent.Refused(row("claude-opus-5-5", "claude-sonnet-5", TS), scope = "session"),
                    LiveRefusalEvent.Refused(row("claude-opus-5-5", null, TS2), scope = null),
                    LiveRefusalEvent.SessionReplaced,
                ),
                events,
            )
        }

    @Test
    fun anOpenScope_isCarriedVerbatim() =
        runTest {
            val (pump, repo) = repo()
            val events = collect(repo.observeLiveRefusalEvents("c1"))
            runCurrent()

            pump.push(fallback("c1", "a", "b", scope = " Session ", ts = TS, id = 1L))
            runCurrent()

            assertEquals(listOf(LiveRefusalEvent.Refused(row("a", "b", TS), scope = " Session ")), events)
        }

    @Test
    fun anotherConversationsFrames_areNotDelivered() =
        runTest {
            val (pump, repo) = repo()
            val events = collect(repo.observeLiveRefusalEvents("c1"))
            runCurrent()

            pump.push(fallback("c2", "a", "b", scope = "session", ts = TS, id = 1L))
            pump.push(sessionTransition("c2", id = 2L))
            runCurrent()

            assertEquals(emptyList<LiveRefusalEvent>(), events)
        }

    @Test
    fun malformedOrUngatedFrames_emitNothing() =
        runTest {
            val (pump, repo) = repo()
            val events = collect(repo.observeLiveRefusalEvents("c1"))
            runCurrent()

            // No scope, then a malformed ts.
            pump.push(
                probe(
                    "model_refusal_fallback",
                    """{"conversation_id":"c1","original_model":"a","fallback_model":"b","refusal_category":"x",""" +
                        """"banner":"","truncated_fields":null,"dropped_fields":null}""",
                    TS,
                    id = 1L,
                ),
            )
            pump.push(fallback("c1", "a", "b", scope = "session", ts = "not-a-time", id = 2L))
            runCurrent()
            assertEquals(emptyList<LiveRefusalEvent>(), events)

            val (ungatedPump, ungated) = repo(capabilities = emptySet())
            val ungatedEvents = collect(ungated.observeLiveRefusalEvents("c1"))
            runCurrent()
            ungatedPump.push(fallback("c1", "a", "b", scope = "session", ts = TS, id = 1L))
            ungatedPump.push(sessionTransition("c1", id = 2L))
            runCurrent()
            assertEquals(emptyList<LiveRefusalEvent>(), ungatedEvents)
        }

    @Test
    fun aRepeatedFrame_isSignalledAgainButFoldsOneRow() =
        runTest {
            val (pump, repo) = repo()
            val events = collect(repo.observeLiveRefusalEvents("c1"))
            val threads = collect(repo.observeMessages("c1"))
            runCurrent()

            pump.push(fallback("c1", "a", "b", scope = "session", ts = TS, id = 1L))
            pump.push(fallback("c1", "a", "b", scope = "session", ts = TS, id = 2L))
            runCurrent()

            assertEquals(2, events.size)
            assertEquals(1, threads.last().filterIsInstance<ThreadItem.ModelRefusal>().size)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.repo(
        capabilities: Set<String> = setOf(CAPABILITY_INTERACTIVE),
    ): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> {
        val emissions = mutableListOf<T>()
        backgroundScope.launch { flow.collect { emissions += it } }
        return emissions
    }

    private fun row(
        original: String,
        fallback: String?,
        ts: String,
    ) = ThreadItem.ModelRefusal(original, fallback, "Retried.", bannerTruncated = false, occurredAt = Instant.parse(ts))

    private fun fallback(
        conversationId: String,
        original: String,
        fallback: String,
        scope: String,
        ts: String,
        id: Long,
    ): Envelope =
        probe(
            "model_refusal_fallback",
            """{"conversation_id":"$conversationId","original_model":"$original","fallback_model":"$fallback",""" +
                """"scope":"$scope","refusal_category":"cyber","banner":"Retried.","truncated_fields":null,""" +
                """"dropped_fields":null}""",
            ts,
            id,
        )

    private fun noFallback(
        conversationId: String,
        original: String,
        ts: String,
        id: Long,
    ): Envelope =
        probe(
            "model_refusal_no_fallback",
            """{"conversation_id":"$conversationId","original_model":"$original","refusal_category":"cyber",""" +
                """"banner":"Retried.","truncated_fields":null,"dropped_fields":null}""",
            ts,
            id,
        )

    private fun sessionTransition(
        conversationId: String,
        id: Long,
    ): Envelope =
        probe(
            "session_transition",
            """{"conversation_id":"$conversationId","previous_session_id":"s1","new_session_id":"s2",""" +
                """"reason":"clear","occurred_at":"$TS","workspace_cwd":null}""",
            TS,
            id,
        )

    private fun probe(
        type: String,
        payload: String,
        ts: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = type, ts = ts, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
        const val TS2 = "2026-09-23T10:00:05Z"
    }
}
