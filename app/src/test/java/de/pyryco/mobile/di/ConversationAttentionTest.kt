package de.pyryco.mobile.di

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.repository.ConversationReadMarks
import org.junit.Assert.assertEquals
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
    fun anOpenPromptOrQuestionBatchWaitsOnlyForItsOwnConversation() {
        val running = HostAttentionState().onEvent(state("c", Phase.Thinking))
        assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), running.resolve(listOf(modal("c")), emptyList()))
        assertEquals(mapOf("c" to ConversationAttention.Running), running.resolve(listOf(modal("")), emptyList()))
        assertEquals(
            mapOf("c" to ConversationAttention.Running, "q" to ConversationAttention.WaitingForAnswer),
            running.resolve(emptyList(), listOf(QuestionBatch("q", "b", emptyList()))),
        )
    }

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
    fun disconnectClearsBusyAndRunning() {
        val state = HostAttentionState().onEvent(state("r", Phase.Thinking)).withBusy(setOf("b"))
        assertEquals(
            mapOf("r" to ConversationAttention.Running, "b" to ConversationAttention.Running),
            state.resolved(),
        )
        assertEquals(HostAttentionState(), state.disconnected())
    }

    @Test fun unsignedShownMarksNeverUseLegacyReceiptAsAuthority() {
        val cases =
            listOf(
                Triple(0uL, 0uL, false),
                Triple(0uL, 1uL, true),
                Triple(10uL, 10uL, false),
                Triple(11uL, 10uL, false),
                Triple(Long.MAX_VALUE.toULong(), ULong.MAX_VALUE, true),
                Triple(ULong.MAX_VALUE, ULong.MAX_VALUE, false),
            )
        for ((read, shown, unread) in cases) {
            val state = HostAttentionState(readMarks = mapOf("c" to ConversationReadMarks(read, 999u, shown, true)))
            assertEquals(if (unread) mapOf("c" to ConversationAttention.Unread) else emptyMap(), state.resolved())
        }
        val unavailable = HostAttentionState(readMarks = mapOf("c" to ConversationReadMarks(0u, ULong.MAX_VALUE)))
        assertEquals(emptyMap<String, ConversationAttention>(), unavailable.resolved())
    }

    @Test fun daemonMarksKeepPromptRunningUnreadPrecedenceAndSurviveDisconnect() {
        val unread = HostAttentionState(readMarks = mapOf("c" to ConversationReadMarks(0u, null, 1u, true)))
        val running = unread.onEvent(state("c", Phase.Thinking))
        assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), running.resolve(listOf(modal("c")), emptyList()))
        assertEquals(mapOf("c" to ConversationAttention.Running), running.resolved())
        assertEquals(mapOf("c" to ConversationAttention.Unread), running.disconnected().resolved())
        assertEquals(
            emptyMap<String, ConversationAttention>(),
            unread.withReadMarks(mapOf("c" to ConversationReadMarks(1u, null, 1u, true))).resolved(),
        )
    }

    private fun HostAttentionState.resolved() = resolve(emptyList(), emptyList())

    private fun state(
        id: String,
        phase: Phase,
    ) = LiveSessionEvent.TurnState(id, phase)

    private fun modal(
        conversationId: String,
        modalId: String = "m",
    ) = ModalUiState.Open(modalId, "permission", "title", "prompt", emptyList(), "deny", conversationId)
}
