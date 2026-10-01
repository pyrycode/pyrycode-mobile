package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1313: `turn_state` and `turn_end` fold into the repository's held turn phase for every conversation,
 * whether or not a thread is observing it, behind the `interactive` gate.
 */
class RemoteConversationRepositoryTurnPhaseTest {
    @Test
    fun framesForAnUnobservedConversation_areHeldForItsFirstReader() =
        runTest {
            val (pump, repo) = repo()
            pump.push(turnState("c2", "thinking", id = 1L))
            pump.push(turnState("c2", "responding", id = 2L))
            runCurrent()

            assertEquals(Phase.Responding, repo.observeTurnPhase("c2").first())
            assertEquals(Phase.Idle, repo.observeTurnPhase("c1").first())
        }

    @Test
    fun turnEnd_returnsTheConversationToIdle() =
        runTest {
            val (pump, repo) = repo()
            pump.push(turnState("c1", "thinking", id = 1L))
            pump.push(turnEnd("c1", id = 2L))
            runCurrent()

            assertEquals(Phase.Idle, repo.observeTurnPhase("c1").first())
        }

    @Test
    fun withoutInteractive_framesLeaveThePhaseIdle() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            pump.push(turnState("c1", "thinking", id = 1L))
            runCurrent()

            assertEquals(Phase.Idle, repo.observeTurnPhase("c1").first())
        }

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun turnState(
        conversationId: String,
        state: String,
        id: Long,
    ): Envelope = probe("turn_state", """{"conversation_id":"$conversationId","state":"$state"}""", id)

    private fun turnEnd(
        conversationId: String,
        id: Long,
    ): Envelope = probe("turn_end", """{"conversation_id":"$conversationId","turn_id":"t1","stop_reason":"end_turn"}""", id)

    private fun probe(
        type: String,
        payload: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))

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
        const val TS = "2026-10-01T10:00:00Z"
    }
}
