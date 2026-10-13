package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSwitchProofTest {
    private val time = Instant.parse("2026-10-13T00:00:00Z")
    private val row = ModelMenuRow("resolved", "family", "Published", listOf("low", "high"), false, null, ConversationAgent.Codex)
    private val channel = Conversation("channel", "test", "", "successor", listOf("old"), true, time, agent = ConversationAgent.Codex)
    private val settings = SessionSettings("successor", "family", "low", EffectiveEffort.Applied("low"), "default", false, 0, 0)

    @Test fun selectionRequiresRenderedUncutUniqueRowsForTheTargetAgent() {
        assertEquals(row, switchModelRow(ModelMenu(listOf(row), 0), ConversationAgent.Codex))
        assertEquals(
            null,
            switchModelRow(ModelMenu(listOf(row.copy(truncatedFields = listOf("effort_levels"))), 0), ConversationAgent.Codex),
        )
        assertEquals(null, switchModelRow(ModelMenu(listOf(row, row.copy(value = "other")), 0), ConversationAgent.Codex))
        assertEquals(null, switchModelRow(ModelMenu(listOf(row.copy(agent = ConversationAgent.Claude)), 0), ConversationAgent.Codex))
        val hidden = List(32) { row.copy(agent = ConversationAgent.Claude, displayName = "row$it") } + row
        assertEquals(null, switchModelRow(ModelMenu(hidden, 0), ConversationAgent.Codex))
    }

    @Test fun successorRequiresFreshMatchingAgentChannelSessionAndModel() {
        assertTrue(isSwitchSuccessor(channel, settings, "channel", setOf("old"), row))
        assertFalse(isSwitchSuccessor(channel, settings.copy(held = true), "channel", setOf("old"), row))
        assertFalse(isSwitchSuccessor(channel, settings.copy(sessionId = "old"), "channel", setOf("old"), row))
        assertFalse(
            isSwitchSuccessor(channel.copy(currentSessionId = "old"), settings.copy(sessionId = "old"), "channel", setOf("old"), row),
        )
        assertFalse(isSwitchSuccessor(channel, settings, "other", setOf("old"), row))
        assertFalse(isSwitchSuccessor(channel.copy(agent = ConversationAgent.Claude), settings, "channel", setOf("old"), row))
        assertFalse(isSwitchSuccessor(channel, settings.copy(model = "other"), "channel", setOf("old"), row))
    }

    @Test fun historyRequiresExactlyOneFinalizedCopyInOriginalRelativeOrder() {
        val expected = listOf(Role.User to "ask", Role.Assistant to "reply", Role.User to "next", Role.Assistant to "fresh")
        val messages = expected.mapIndexed { i, (role, text) -> Message("m$i", "s$i", role, text, time, isStreaming = false) }
        assertTrue(hasOrderedSwitchMessages(messages, expected))
        assertFalse(hasOrderedSwitchMessages(messages.reversed(), expected))
        assertFalse(hasOrderedSwitchMessages(messages + messages.first(), expected))
        assertFalse(hasOrderedSwitchMessages(messages.dropLast(1), expected))
        assertFalse(hasOrderedSwitchMessages(messages.map { if (it.content == "fresh") it.copy(isStreaming = true) else it }, expected))
        assertFalse(hasOrderedSwitchMessages(messages.map { if (it.content == "fresh") it.copy(content = "reply") else it }, expected))
    }

    @Test fun finalizedMarkerAllowsSurroundingTextButRejectsDuplicatesStreamingAndReplay() {
        val reply = Message("reply", "session", Role.Assistant, "Here is **codex2052**.", time, isStreaming = false)
        assertEquals(reply, finalizedSwitchReply(listOf(reply), "codex2052"))
        assertEquals(null, finalizedSwitchReply(listOf(reply, reply.copy(id = "duplicate")), "codex2052"))
        assertEquals(null, finalizedSwitchReply(listOf(reply.copy(isStreaming = true)), "codex2052"))
        assertEquals(null, finalizedSwitchReply(listOf(reply), "claude2052"))
        val prompt = Message("user", "session", Role.User, "ask", time, isStreaming = false)
        val expected = listOf(Role.User to prompt.content, Role.Assistant to reply.content)
        assertTrue(hasOrderedSwitchMessages(listOf(prompt, reply), expected))
        assertFalse(hasOrderedSwitchMessages(listOf(reply, prompt), expected))
        assertFalse(hasOrderedSwitchMessages(listOf(prompt, reply.copy(content = "codex2052")), expected))
    }
}
