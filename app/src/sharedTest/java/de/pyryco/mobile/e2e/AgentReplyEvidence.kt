package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [block] is the rendered Agent identity; [family] is the attribution key [ThreadEvidence.settledReply] selects by. */
internal class ThreadEvidenceAgent(
    val block: String,
    val family: String,
) {
    override fun toString(): String = "ThreadEvidenceAgent(redacted)"
}

/** Rendered identities one live agent-reply scenario asserts, all from one admitted emission. */
internal class AgentReplyEvidence(
    val agent: ThreadEvidenceAgent,
    val heldReply: ThreadEvidenceRecord,
    val runId: String,
    val mainReply: ThreadEvidenceRecord,
) {
    override fun toString(): String = "AgentReplyEvidence(redacted)"
}

/**
 * Raw peer facts only locate an admitted background Agent root; they never become row keys.
 * Legacy roots are the tool row the raw call id names. Authoritative roots are the `agent` fact
 * recording the raw task id and, when it records one, the raw call id.
 */
internal fun ThreadEvidence.backgroundAgent(
    taskId: String,
    toolUseId: String,
): ThreadEvidenceAgent? {
    val root =
        records.singleOrNull {
            it.message.role == Role.Tool &&
                it.agentId == it.message.id &&
                when (owner.kind) {
                    ThreadEvidenceKind.Legacy -> it.message.ordinaryId == toolUseId
                    ThreadEvidenceKind.Authoritative -> {
                        val fields = it.fact?.content as? JsonObject
                        it.fact?.kind == "agent" &&
                            fields.text("task_id") == taskId &&
                            (fields?.get("tool_use_id") == null || fields.text("tool_use_id") == toolUseId)
                    }
                }
        } ?: return null
    val block = root.message.id
    return ThreadEvidenceAgent(block, if (owner.kind == ThreadEvidenceKind.Legacy) toolUseId else block)
}

/** The turn's settled family in its single recorded session, rendered inside [agent] or, without one, outside every block. */
internal fun ThreadEvidence.settledTurnReply(
    current: ThreadEvidenceOwner,
    turn: String,
    agent: ThreadEvidenceAgent? = null,
): List<ThreadEvidenceRecord>? {
    val session =
        records
            .filter { it.message.role == Role.Assistant && it.turn == turn }
            .map { it.session }
            .distinct()
            .singleOrNull() ?: return null
    return settledReply(current, session, turn, agent?.family.orEmpty())?.takeIf { family ->
        family.all { it.agentId == agent?.block }
    }
}

internal fun ThreadEvidence.settledMainReply(
    current: ThreadEvidenceOwner,
    turn: String,
): ThreadEvidenceRecord? = settledTurnReply(current, turn)?.lastOrNull { it.message.content.isNotBlank() }

/** The held paragraph, the run its owned tools fold into and a distinct main-lane reply. */
internal fun ThreadEvidence.agentReplyEvidence(
    current: ThreadEvidenceOwner,
    taskId: String,
    toolUseId: String,
    childTurn: String,
    mainTurn: String,
): AgentReplyEvidence? {
    val agent = backgroundAgent(taskId, toolUseId) ?: return null
    val held = settledTurnReply(current, childTurn, agent)?.lastOrNull { it.message.content.isNotBlank() } ?: return null
    val main = settledMainReply(current, mainTurn) ?: return null
    val run =
        records
            .firstOrNull { it.message.role == Role.Tool && it.agentId == agent.block && it.message.id != agent.block }
            ?.toolRunId ?: return null
    return AgentReplyEvidence(agent, held, run, main)
}

/** A held Agent can still revise its visible paragraph; this proves ownership, not settlement. */
internal fun ThreadEvidence.heldAgentReplyEvidence(
    current: ThreadEvidenceOwner,
    taskId: String,
    toolUseId: String,
    childTurn: String,
    mainTurn: String,
): AgentReplyEvidence? {
    if (!admitted || !owner.matches(current)) return null
    if (owner.kind == ThreadEvidenceKind.Legacy) return agentReplyEvidence(current, taskId, toolUseId, childTurn, mainTurn)
    val agent = backgroundAgent(taskId, toolUseId) ?: return null
    val paragraphs =
        records.filter {
            it.fact?.kind == "assistant_message" && it.turn == childTurn && it.agentId == agent.block && it.fullText.isNotBlank()
        }
    paragraphs.map { it.session }.distinct().singleOrNull() ?: return null
    val held = paragraphs.lastOrNull() ?: return null
    val main = settledMainReply(current, mainTurn) ?: return null
    val run =
        records
            .firstOrNull {
                it.message.role == Role.Tool && it.agentId == agent.block && it.message.id != agent.block
            }?.toolRunId ?: return null
    return AgentReplyEvidence(agent, held, run, main)
}

private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
