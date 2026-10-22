package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1313: the turn phase held per conversation for one connection. Each read is the conversation's latest
 * phase, idle until a `turn_state` says otherwise and again after its `turn_end`.
 */
class TurnPhaseProjectionTest {
    private val projection = TurnPhaseProjection()

    @Test
    fun aConversationWithNoFrames_readsIdle() =
        runTest {
            assertEquals(Phase.Idle, projection.observe("c1").first())
        }

    @Test
    fun everyPhase_isHeldAsTheLatestReading() =
        runTest {
            for (phase in listOf(Phase.Thinking, Phase.Responding, Phase.Idle, Phase.Responding)) {
                projection.apply(LiveSessionEvent.TurnState("c1", phase))
                assertEquals(phase, projection.observe("c1").first())
            }
        }

    @Test
    fun framesForOneConversation_neverChangeAnother() =
        runTest {
            projection.apply(LiveSessionEvent.TurnState("a", Phase.Thinking))
            projection.apply(LiveSessionEvent.TurnState("b", Phase.Responding))
            projection.apply(LiveSessionEvent.TurnState("b", Phase.Idle))
            projection.apply(LiveSessionEvent.TurnState("c", Phase.Idle))

            assertEquals(Phase.Thinking, projection.observe("a").first())
            assertEquals(Phase.Idle, projection.observe("b").first())
        }

    @Test
    fun aConversationNobodyObserved_holdsItsPhaseForItsFirstReader() =
        runTest {
            projection.apply(LiveSessionEvent.TurnState("b", Phase.Thinking))
            projection.apply(LiveSessionEvent.TurnState("b", Phase.Responding))

            assertEquals(Phase.Responding, projection.observe("b").first())
        }

    @Test
    fun aChangeElsewhere_doesNotReEmitThisConversation() =
        runTest {
            val readings = mutableListOf<Phase>()
            backgroundScope.launch { projection.observe("a").collect { readings += it } }
            runCurrent()

            projection.apply(LiveSessionEvent.TurnState("b", Phase.Thinking))
            runCurrent()
            projection.apply(LiveSessionEvent.TurnState("a", Phase.Thinking))
            runCurrent()

            assertEquals(listOf(Phase.Idle, Phase.Thinking), readings)
        }
}
