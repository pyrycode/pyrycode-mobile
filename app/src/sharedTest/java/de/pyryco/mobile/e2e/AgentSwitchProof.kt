package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.ui.conversations.thread.dropdownLabel

/**
 * The only Codex model a live switch may run on: GPT-6 Luna, the cheapest. Matched on the published
 * `resolved_model`, never on list position, so a menu that leads with Sol or Astra cannot select them.
 */
internal const val SWITCH_CODEX_MODEL = "gpt-6-luna"

/** A row must be selectable without guessing a family, a cut field or an ambiguous UI label. */
internal fun switchModelRow(
    menu: ModelMenu,
    agent: ConversationAgent,
): ModelMenuRow? {
    val rendered = renderedModelRows(menu)
    return rendered.firstOrNull { row ->
        row.agent == agent &&
            (agent != ConversationAgent.Codex || row.resolvedModel == SWITCH_CODEX_MODEL) &&
            row.value.isNotBlank() &&
            row.resolvedModel.isNotBlank() &&
            row.truncatedFields.orEmpty().isEmpty() &&
            row.dropdownLabel(agent).isNotBlank() &&
            rendered.count { it.dropdownLabel(requireNotNull(it.agent)) == row.dropdownLabel(agent) } == 1
    }
}

internal fun isSwitchSuccessor(
    channel: Conversation,
    settings: SessionSettings,
    id: String,
    predecessors: Set<String>,
    row: ModelMenuRow,
): Boolean =
    channel.id == id &&
        channel.isPromoted &&
        channel.agent == row.agent &&
        channel.currentSessionId.isNotEmpty() &&
        channel.currentSessionId !in predecessors &&
        !settings.held &&
        settings.sessionId == channel.currentSessionId &&
        settings.model == row.value

/** Earlier replies, queued rows, duplicates and still-streaming copies cannot satisfy a later turn. */
internal fun hasOrderedSwitchMessages(
    messages: List<Message>,
    expected: List<Pair<Role, String>>,
): Boolean {
    val indices =
        expected.map { (role, text) ->
            messages
                .withIndex()
                .filter { (_, message) -> message.role == role && message.content.trim() == text }
                .singleOrNull()
                ?.takeUnless { it.value.isStreaming }
                ?.index ?: return false
        }
    return indices.zipWithNext().all { (first, next) -> first < next }
}

/** Admit one finalized marker-bearing reply; retain its actual body for later exact history proof. */
internal fun finalizedSwitchReply(
    messages: List<Message>,
    marker: String,
): Message? = messages.singleOrNull { it.role == Role.Assistant && it.content.contains(marker) }?.takeUnless { it.isStreaming }
