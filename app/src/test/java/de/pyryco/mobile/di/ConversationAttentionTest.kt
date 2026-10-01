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
        assertEquals(ConversationAttention.WaitingForAnswer, resolveAttention(waiting = true, running = true, unread = false))
        assertEquals(ConversationAttention.Running, resolveAttention(waiting = false, running = true, unread = true))
        assertEquals(ConversationAttention.Unread, resolveAttention(waiting = false, running = false, unread = true))
        assertEquals(ConversationAttention.Idle, resolveAttention(waiting = false, running = false, unread = false))
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

    // #1451: desktop has no failed state, so a failed or stopped-early turn is an ordinary completed turn.
    @Test
    fun failedAndStoppedEarlyTurnsAreUnreadUntilOpened() {
        listOf(end("c", "t1", isError = true), end("c", "t1", stopReason = "max_tokens")).forEach { ended ->
            val unread = HostAttentionState().onEvent(ended, viewing = false)
            assertEquals(mapOf("c" to ConversationAttention.Unread), unread.resolved())
            assertEquals(emptyMap<String, ConversationAttention>(), unread.opened("c").resolved())
            assertEquals(emptyMap<String, ConversationAttention>(), HostAttentionState().onEvent(ended, viewing = true).resolved())
        }
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
            mapOf("a" to ConversationAttention.Unread, "b" to ConversationAttention.Unread),
            state.disconnected().resolved(),
        )
        val merged = state.restored(mapOf("b" to ReadPosition("t0", "t0"), "e" to ReadPosition("t9", null)))
        assertEquals(ReadPosition("t1", null), merged.positions["b"])
        assertEquals(ConversationAttention.Unread, merged.resolved()["e"])
    }

    @Test
    fun anOpenPromptOrQuestionBatchWaitsOnlyForItsOwnConversation() {
        val running = HostAttentionState().onEvent(state("c", Phase.Thinking), viewing = false)
        assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), running.resolve(listOf(modal("c")), emptyList()))
        assertEquals(mapOf("c" to ConversationAttention.Running), running.resolve(listOf(modal("")), emptyList()))
        assertEquals(
            mapOf("c" to ConversationAttention.Running, "q" to ConversationAttention.WaitingForAnswer),
            running.resolve(emptyList(), listOf(QuestionBatch("q", "b", emptyList()))),
        )
    }

    // #1338: desktop's `selectHasOutstandingFor` — a chat waits while any outstanding prompt belongs to it.
    @Test
    fun everyChatHoldingAPromptWaits_andAnsweringOneLeavesTheOther() {
        val idle = HostAttentionState()
        val both = listOf(modal("a", "m1"), modal("b", "m2"), modal("b", "m3"))
        assertEquals(
            mapOf("a" to ConversationAttention.WaitingForAnswer, "b" to ConversationAttention.WaitingForAnswer),
            idle.resolve(both, emptyList()),
        )
        assertEquals(mapOf("b" to ConversationAttention.WaitingForAnswer), idle.resolve(both.drop(1), emptyList()))
        assertEquals(mapOf("b" to ConversationAttention.WaitingForAnswer), idle.resolve(listOf(modal("b", "m3")), emptyList()))
        assertEquals(emptyMap<String, ConversationAttention>(), idle.resolve(emptyList(), emptyList()))
    }

    // #1452: a stalled, retrying, compacting or resetting chat is busy, and busy reads as Running.
    @Test
    fun aBusyConversationRunsBelowAPromptAndAboveUnread() {
        val busy = HostAttentionState().withBusy(setOf("b"))
        assertEquals(mapOf("b" to ConversationAttention.Running), busy.resolved())
        assertEquals(mapOf("b" to ConversationAttention.WaitingForAnswer), busy.resolve(listOf(modal("b")), emptyList()))
        assertEquals(
            mapOf("b" to ConversationAttention.WaitingForAnswer),
            busy.resolve(emptyList(), listOf(QuestionBatch("b", "q", emptyList()))),
        )
        val unread = HostAttentionState().onEvent(end("b", "t1"), viewing = false).withBusy(setOf("b"))
        assertEquals(mapOf("b" to ConversationAttention.Running), unread.resolved())
        assertEquals(mapOf("b" to ConversationAttention.Unread), unread.withBusy(emptySet()).resolved())
    }

    @Test
    fun disconnectClearsBusyAndRunning() {
        val state = HostAttentionState().onEvent(state("r", Phase.Thinking), viewing = false).withBusy(setOf("b"))
        assertEquals(
            mapOf("r" to ConversationAttention.Running, "b" to ConversationAttention.Running),
            state.resolved(),
        )
        assertEquals(HostAttentionState(), state.disconnected())
    }

    private fun HostAttentionState.resolved() = resolve(emptyList(), emptyList())

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

    private fun modal(
        conversationId: String,
        modalId: String = "m",
    ) = ModalUiState.Open(modalId, "permission", "title", "prompt", emptyList(), "deny", conversationId)
}
