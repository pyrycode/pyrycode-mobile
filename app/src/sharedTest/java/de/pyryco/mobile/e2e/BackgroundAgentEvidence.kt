package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * #2173: background-agent waits and joins over source-bound [ThreadEvidence]. Raw peer `tool_use_id`s only
 * correlate to admitted records; headers, markers, runs and list keys use the identities the bound source
 * renders. Each step resolves those identities from the snapshot it reads, because an authoritative epoch or
 * generation change re-scopes them.
 */

/** The wire `tool_use_id` behind a tool record, for correlation only; never a rendering key. */
internal fun ThreadEvidenceRecord.rawToolUseId(): String? =
    when {
        message.role != Role.Tool -> null
        fact == null -> message.ordinaryId
        fact.kind in setOf("tool_call", "agent") ->
            ((fact.content as? JsonObject)?.get("tool_use_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }

/** A background Agent as the bound source renders it, joined from one raw peer ID. */
internal class RenderedAgentFamily(
    val owner: ThreadEvidenceOwner,
    /** The header's `background-agent:`, the marker's `agent-start:` and the root row's `msg:` identity. */
    val agentId: String,
    /** The block's other records, tools and attributed prose, in thread order. */
    val children: List<ThreadEvidenceRecord>,
) {
    val childIds: List<String> get() = children.map { it.message.id }

    /** The projected run identity of the block's first tool. */
    val runId: String? get() = children.firstOrNull { it.message.role == Role.Tool }?.toolRunId

    /** Whether each raw peer tool ID names a tool this block owns. */
    fun ownsTools(rawIds: Collection<String>): Boolean =
        rawIds.all { raw -> children.any { it.message.role == Role.Tool && it.rawToolUseId() == raw } }

    override fun toString(): String = "RenderedAgentFamily(redacted)"
}

/** The admitted Agent root whose raw `tool_use_id` is [rawAgent] and which owns its rendered block. */
internal fun ThreadEvidence.renderedAgent(rawAgent: String): RenderedAgentFamily? {
    if (!admitted || rawAgent.isEmpty()) return null
    val root = records.singleOrNull { it.rawToolUseId() == rawAgent && it.agentId == it.message.id } ?: return null
    return RenderedAgentFamily(owner, root.message.id, records.filter { it !== root && it.agentId == root.message.id })
}

/**
 * Strict settlement of [turn]'s main reply, or of the reply owned by the Agent whose raw ID is [rawAgent].
 * The session is the one session holding that turn's assistant records. Legacy families keep raw parent
 * attribution and contiguous deltas; authoritative families use the projected block and each item's own
 * terminal revision. Both come from [ThreadEvidence.settledReply].
 */
internal fun ThreadEvidence.settledTurnReply(
    turn: String,
    rawAgent: String = "",
): List<ThreadEvidenceRecord>? {
    if (!admitted) return null
    val family =
        when {
            rawAgent.isEmpty() -> ""
            owner.kind == ThreadEvidenceKind.Legacy -> rawAgent.takeIf { renderedAgent(it) != null }
            else -> renderedAgent(rawAgent)?.agentId
        } ?: return null
    val session =
        records
            .filter { it.message.role == Role.Assistant && it.turn == turn && (it.fact == null || it.fact.kind == "assistant_message") }
            .map { it.session }
            .distinct()
            .singleOrNull() ?: return null
    return settledReply(owner, session, turn, family)
}

/** The single delivered user record whose full text is [text]. */
internal fun ThreadEvidence.deliveredUser(text: String): ThreadEvidenceRecord? =
    if (!admitted) {
        null
    } else {
        records
            .filter {
                it.message.role == Role.User &&
                    it.fullText == text &&
                    (it.fact == null || it.fact.kind == "user_message" && it.fact.status == "delivered")
            }.singleOrNull()
    }

/** Retain a finished Agent between canonical messages from before and after its finish. */
internal fun ThreadEvidence.retainsFinishedAgentPlacement(
    expected: ThreadItemDto,
    before: ThreadItemDto,
    after: ThreadItemDto? = null,
): Boolean {
    if (!admitted || owner.kind != ThreadEvidenceKind.Authoritative) return false
    val launched = expected.order ?: return false
    val ended = expected.endedOrder ?: return false
    val older = before.order ?: return false
    if (expected.kind != "agent" || expected.active || ended <= launched || older <= launched || older >= ended) return false
    if (after != null && (after.order == null || after.order <= ended)) return false
    val rootIndex = records.indexOfFirst { it.fact?.id == expected.id }
    val beforeIndex = records.indexOfFirst { it.fact?.id == before.id }
    val root = records.getOrNull(rootIndex)?.fact ?: return false
    if (root.order != launched || root.endedOrder != ended || root.status != expected.status || root.active) return false
    if (records.getOrNull(beforeIndex)?.fact?.order != older || beforeIndex >= rootIndex) return false
    if (after == null) return true
    val afterIndex = records.indexOfFirst { it.fact?.id == after.id }
    return afterIndex > rootIndex && records.getOrNull(afterIndex)?.fact?.order == after.order
}
