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
    fun turnEnd_returnsToIdle_whateverItsOutcome() =
        runTest {
            val ends =
                listOf(
                    LiveSessionEvent.TurnEnd("c1", turnId = "t1", stopReason = "end_turn"),
                    LiveSessionEvent.TurnEnd("c1", turnId = "t2", stopReason = "end_turn", outcome = "success", isError = true),
                    LiveSessionEvent.TurnEnd(
                        "c1",
                        turnId = "t3",
                        stopReason = "cancelled",
                        outcome = "error_during_execution",
                        isError = true,
                    ),
                )
            for (end in ends) {
                projection.apply(LiveSessionEvent.TurnState("c1", Phase.Thinking))
                projection.apply(end)
                assertEquals(Phase.Idle, projection.observe("c1").first())
            }
        }

    @Test
    fun framesForOneConversation_neverChangeAnother() =
        runTest {
            projection.apply(LiveSessionEvent.TurnState("a", Phase.Thinking))
            projection.apply(LiveSessionEvent.TurnState("b", Phase.Responding))
            projection.apply(LiveSessionEvent.TurnEnd("b", turnId = "t1", stopReason = "end_turn"))
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
    fun nonPhaseEvents_leaveThePhaseHeld() =
        runTest {
            projection.apply(LiveSessionEvent.TurnState("c1", Phase.Responding))
            projection.apply(LiveSessionEvent.AssistantDelta("c1", turnId = "t1", seq = 0, text = "hi"))
            projection.apply(LiveSessionEvent.ToolUse("c1", turnId = "t1", toolUseId = "u1", name = "Read", inputSummary = "f"))
            projection.apply(LiveSessionEvent.ToolResult("c1", turnId = "t1", toolUseId = "u1", isError = false, resultSummary = "ok"))
            projection.apply(LiveSessionEvent.ReplayGap("c1"))

            assertEquals(Phase.Responding, projection.observe("c1").first())
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
