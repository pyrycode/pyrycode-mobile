package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ReadPosition
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.QuestionBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAttentionTest {
    @Test
    fun precedenceOrdersEachAdjacentPair() {
        assertEquals(
            ConversationAttention.WaitingForAnswer,
            resolveAttention(waiting = true, running = true, failed = false, unread = false),
        )
        assertEquals(ConversationAttention.Running, resolveAttention(waiting = false, running = true, failed = true, unread = false))
        assertEquals(ConversationAttention.Failed, resolveAttention(waiting = false, running = false, failed = true, unread = true))
        assertEquals(ConversationAttention.Unread, resolveAttention(waiting = false, running = false, failed = false, unread = true))
        assertEquals(ConversationAttention.Idle, resolveAttention(waiting = false, running = false, failed = false, unread = false))
    }

    @Test
    fun turnStateDrivesRunningUntilIdleOrTurnEnd() {
        val thinking = HostAttentionState().onEvent(state("c", Phase.Thinking), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Running), thinking.resolved())
        val responding = HostAttentionState().onEvent(state("c", Phase.Responding), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Running), responding.resolved())
        assertEquals(emptyMap<String, ConversationAttention>(), thinking.onEvent(state("c", Phase.Idle), viewing = true).resolved())
        assertEquals(emptyMap<String, ConversationAttention>(), thinking.onEvent(end("c", "t1"), viewing = true).resolved())
    }

    @Test
    fun aTurnCompletedWhileNotViewingIsUnreadAndWhileViewingIsRead() {
        assertEquals(
            mapOf("c" to ConversationAttention.Unread),
            HostAttentionState().onEvent(end("c", "t1"), viewing = false).resolved(),
        )
        val viewed = HostAttentionState().onEvent(end("c", "t1"), viewing = true)
        assertEquals(emptyMap<String, ConversationAttention>(), viewed.resolved())
        assertEquals(ReadPosition("t1", "t1"), viewed.positions["c"])
    }

    @Test
    fun failedAndStoppedEarlyAreFailedButInterruptedIsNot() {
        val failed = HostAttentionState().onEvent(end("c", "t1", isError = true), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Failed), failed.resolved())
        val stopped = HostAttentionState().onEvent(end("c", "t1", stopReason = "max_tokens"), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Failed), stopped.resolved())
        val interrupted = HostAttentionState().onEvent(end("c", "t1", stopReason = "cancelled"), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Unread), interrupted.resolved())
        val viewed = HostAttentionState().onEvent(end("c", "t1", isError = true), viewing = true)
        assertEquals(emptyMap<String, ConversationAttention>(), viewed.resolved())
    }

    @Test
    fun openingClearsUnreadAndFailedAndTheNextTurnClearsFailed() {
        val failed = HostAttentionState().onEvent(end("c", "t1", isError = true), viewing = false)
        assertEquals(emptyMap<String, ConversationAttention>(), failed.opened("c").resolved())
        val nextTurn = failed.onEvent(state("c", Phase.Thinking), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.Running), nextTurn.resolved())
        assertEquals(mapOf("c" to ConversationAttention.Unread), nextTurn.onEvent(state("c", Phase.Idle), viewing = false).resolved())
        assertEquals(HostAttentionState(), HostAttentionState().opened("unknown"))
    }

    @Test
    fun aReDeliveredTurnCountsOnce() {
        val read = HostAttentionState().onEvent(end("c", "t1", isError = true), viewing = false).opened("c")
        assertEquals(read, read.onEvent(end("c", "t1", isError = true), viewing = false))
        // A cold process holds no counted list; the stored position alone recognises the turn.
        val restored = HostAttentionState().restored(mapOf("c" to ReadPosition("t2", "t2")))
        assertEquals(emptyMap<String, ConversationAttention>(), restored.onEvent(end("c", "t2"), viewing = false).resolved())
        val older = HostAttentionState().onEvent(end("c", "t1"), viewing = true).onEvent(end("c", "t2"), viewing = true)
        assertEquals(emptyMap<String, ConversationAttention>(), older.onEvent(end("c", "t1"), viewing = false).resolved())
    }

    @Test
    fun malformedTurnIdsEndRunningButNeverCount() {
        val running = HostAttentionState().onEvent(state("c", Phase.Thinking), viewing = false)
        assertEquals(HostAttentionState(), running.onEvent(end("c", ""), viewing = false))
        assertEquals(HostAttentionState(), running.onEvent(end("c", "x".repeat(MAX_TURN_ID_CHARS + 1)), viewing = false))
    }

    @Test
    fun countedTurnsAndPositionsStayBounded() {
        var state = HostAttentionState()
        repeat(MAX_COUNTED_TURNS_PER_CONVERSATION + 5) { state = state.onEvent(end("c", "t$it"), viewing = false) }
        assertEquals(MAX_COUNTED_TURNS_PER_CONVERSATION, state.counted.getValue("c").size)
        repeat(MAX_READ_POSITIONS + 3) { state = state.onEvent(end("c$it", "t"), viewing = false) }
        assertEquals(MAX_READ_POSITIONS, state.positions.size)
        assertTrue("c" !in state.positions)
    }

    @Test
    fun disconnectClearsRunningOnlyAndLiveWinsOverARestore() {
        val state =
            HostAttentionState()
                .onEvent(end("a", "t1", isError = true), viewing = false)
                .onEvent(end("b", "t1"), viewing = false)
                .onEvent(state("d", Phase.Thinking), viewing = false)
        assertEquals(
            mapOf("a" to ConversationAttention.Failed, "b" to ConversationAttention.Unread),
            state.disconnected().resolved(),
        )
        val merged = state.restored(mapOf("b" to ReadPosition("t0", "t0"), "e" to ReadPosition("t9", null)))
        assertEquals(ReadPosition("t1", null), merged.positions["b"])
        assertEquals(ConversationAttention.Unread, merged.resolved()["e"])
    }

    @Test
    fun anOpenPromptOrQuestionBatchWaitsOnlyForItsOwnConversation() {
        val running = HostAttentionState().onEvent(state("c", Phase.Thinking), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), running.resolve(modal("c"), emptyList()))
        assertEquals(mapOf("c" to ConversationAttention.Running), running.resolve(modal(""), emptyList()))
        assertEquals(
            mapOf("c" to ConversationAttention.Running, "q" to ConversationAttention.WaitingForAnswer),
            running.resolve(ModalUiState.Dismissed("m", "allow", "remote", "q"), listOf(QuestionBatch("q", "b", emptyList()))),
        )
    }

    @Test
    fun aNewRowMarksABackgroundConversationUnreadAndNeverAViewedOne() {
        val unread = HostAttentionState().rowsAdded("c", viewing = false, token = "r1")
        assertEquals(mapOf("c" to ConversationAttention.Unread), unread.resolved())
        assertEquals(HostAttentionState(), HostAttentionState().rowsAdded("c", viewing = true, token = "r1"))
        // A second row while still unread keeps the position, so a stored turn id stays recognisable.
        val ended = HostAttentionState().onEvent(end("c", "t1"), viewing = false)
        assertEquals(ended, ended.rowsAdded("c", viewing = false, token = "r2"))

        val read = unread.opened("c")
        assertEquals(emptyMap<String, ConversationAttention>(), read.resolved())
        assertEquals(read, read.rowsAdded("c", viewing = true, token = "r2"))
        val again = read.rowsAdded("c", viewing = false, token = "r2")
        assertEquals(mapOf("c" to ConversationAttention.Unread), again.resolved())
        assertEquals(ReadPosition("r2", "r1"), again.positions["c"])
    }

    @Test
    fun aTurnEndAfterItsRowsStillCountsOnceAndKeepsTheConversationUnread() {
        val rows = HostAttentionState().rowsAdded("c", viewing = false, token = "r1")
        val ended = rows.onEvent(end("c", "t1"), viewing = false)
        assertEquals(listOf("t1"), ended.counted["c"])
        assertEquals(mapOf("c" to ConversationAttention.Unread), ended.resolved())
        assertEquals(ended, ended.onEvent(end("c", "t1"), viewing = false))
        // A turn end the operator watched, after rows they did not, is read.
        assertEquals(emptyMap<String, ConversationAttention>(), rows.onEvent(end("c", "t1"), viewing = true).resolved())
    }

    @Test
    fun aRowMovesItsConversationToTheNewestBoundedPosition() {
        var state = HostAttentionState().rowsAdded("old", viewing = false, token = "r")
        repeat(MAX_READ_POSITIONS - 1) { state = state.onEvent(end("c$it", "t"), viewing = false) }
        state = state.opened("old").rowsAdded("old", viewing = false, token = "r2")
        state = state.onEvent(end("new", "t"), viewing = false)
        assertEquals(MAX_READ_POSITIONS, state.positions.size)
        assertTrue("old" in state.positions)
        assertTrue("c0" !in state.positions)
    }

    private fun HostAttentionState.resolved() = resolve(ModalUiState.Hidden, emptyList())

    private fun state(
        id: String,
        phase: Phase,
    ) = LiveSessionEvent.TurnState(id, phase)

    private fun end(
        id: String,
        turnId: String,
        stopReason: String = "end_turn",
        isError: Boolean = false,
    ) = LiveSessionEvent.TurnEnd(id, turnId, stopReason, isError = isError)

    private fun modal(conversationId: String) = ModalUiState.Open("m", "permission", "title", "prompt", emptyList(), "deny", conversationId)
}
