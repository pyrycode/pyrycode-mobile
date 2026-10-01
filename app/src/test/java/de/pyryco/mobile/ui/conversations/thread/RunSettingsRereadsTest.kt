package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.repository.ResetStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1309: the edges that make an open thread re-read its run settings. */
class RunSettingsRereadsTest {
    @Test
    fun runningThenIdle_isOneEdge() = assertTurnEdges(1, turn("a", Phase.Thinking), turn("a", Phase.Idle))

    @Test
    fun aRepeatedIdle_isNoFurtherEdge() = assertTurnEdges(1, turn("a", Phase.Responding), turn("a", Phase.Idle), turn("a", Phase.Idle))

    @Test
    fun idleWithNoRunningTurn_isNoEdge() = assertTurnEdges(0, turn("a", Phase.Idle))

    @Test
    fun aTurnStarting_isNoEdge() = assertTurnEdges(0, turn("a", Phase.Thinking), turn("a", Phase.Responding))

    @Test
    fun thinkingThenRespondingThenIdle_isOneEdge() =
        assertTurnEdges(1, turn("a", Phase.Thinking), turn("a", Phase.Responding), turn("a", Phase.Idle))

    @Test
    fun eachConversationEndsItsOwnTurn() =
        assertTurnEdges(
            2,
            turn("a", Phase.Thinking),
            turn("b", Phase.Thinking),
            turn("b", Phase.Idle),
            turn("a", Phase.Idle),
        )

    @Test
    fun anotherConversationsIdle_doesNotEndThisTurn() = assertTurnEdges(0, turn("a", Phase.Thinking), turn("b", Phase.Idle))

    @Test
    fun turnEndAndStreamEvents_areNoEdge() =
        assertTurnEdges(
            0,
            turn("a", Phase.Thinking),
            LiveSessionEvent.AssistantDelta("a", "t", 0, "x"),
            LiveSessionEvent.TurnEnd("a", "t", "end_turn"),
            LiveSessionEvent.ReplayGap("a"),
        )

    @Test
    fun aResetEnding_isOneEdge() = assertResetEdges(1, null, wrappingUp, restarting, null)

    @Test
    fun noResetOrAResetStarting_isNoEdge() = assertResetEdges(0, null, null, wrappingUp)

    @Test
    fun twoResets_areTwoEdges() = assertResetEdges(2, wrappingUp, null, restarting, null, null)

    private fun assertTurnEdges(
        expected: Int,
        vararg events: LiveSessionEvent,
    ) = runTest {
        assertEquals(expected, turnEndEdges(flowOf(*events)).toList().size)
    }

    private fun assertResetEdges(
        expected: Int,
        vararg readings: ResetStatus?,
    ) = runTest {
        assertEquals(expected, resetEndEdges(flowOf(*readings)).toList().size)
    }

    private fun turn(
        conversationId: String,
        phase: Phase,
    ) = LiveSessionEvent.TurnState(conversationId, phase)

    private val wrappingUp = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
    private val restarting = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written)
}
