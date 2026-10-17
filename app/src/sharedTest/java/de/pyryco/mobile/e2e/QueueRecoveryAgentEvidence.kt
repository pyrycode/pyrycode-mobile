package de.pyryco.mobile.e2e

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/*
 * Scripted queue, send-now, replay-order and background-agent assertions over source-bound evidence (#2174).
 * Daemon facts prove completion by their own revision, order and status, never by receipt, acknowledgement
 * or invented deltas; agent ownership is the rendered block and run, never a raw tool-use ID.
 */

private val COMPLETE = setOf("done", "finished")

/** Daemon facts by their own completion; legacy assistant rows by streaming state, tools by status. */
private val ThreadEvidenceRecord.isComplete: Boolean
    get() =
        fact?.let { !it.active && it.status in COMPLETE }
            ?: if (message.role == Role.Tool) message.toolCall?.status == ToolCallStatus.Done else !message.isStreaming

private fun ThreadEvidenceRecord.matchesItem(other: ThreadEvidenceRecord): Boolean =
    message.id == other.message.id && fact?.id == other.fact?.id

private fun ThreadEvidence.current(owner: ThreadEvidenceOwner): List<ThreadEvidenceRecord> =
    if (admitted && this.owner.matches(owner)) records else emptyList()

/** The one assistant record, still open with [prefix] after trimming. */
internal fun ThreadEvidence.heldOpenReply(
    owner: ThreadEvidenceOwner,
    prefix: String,
): ThreadEvidenceRecord? =
    current(owner)
        .singleOrNull { it.message.role == Role.Assistant }
        ?.takeIf { !it.isComplete && it.fullText.trimEnd() == prefix }

/** [held]'s item is still present and open. */
internal fun ThreadEvidence.holdsOpen(
    owner: ThreadEvidenceOwner,
    held: ThreadEvidenceRecord,
): Boolean = current(owner).any { it.matchesItem(held) && !it.isComplete }

/**
 * [held]'s item settled with exactly [text] and alone in its turn: legacy keeps the delta sequence and
 * length checks, and a daemon item needs a newer revision than the held one.
 */
internal fun ThreadEvidence.settledHeldReply(
    owner: ThreadEvidenceOwner,
    held: ThreadEvidenceRecord,
    text: String,
): ThreadEvidenceRecord? {
    val reply = current(owner).singleOrNull { it.matchesItem(held) } ?: return null
    val family = settledReply(owner, held.session ?: return null, held.turn ?: return null) ?: return null
    val turn = records.filter { it.message.role == Role.Assistant && it.session == held.session && it.turn == held.turn }
    val newer = reply.fact?.rev?.let { revision -> held.fact?.rev?.let { revision > it } } ?: (held.fact == null)
    return reply.takeIf { it.isComplete && it.fullText == text && family == listOf(it) && turn == listOf(it) && newer }
}

/**
 * Null when [prompt]'s single delivered echo follows the completed tool and precedes the settled [marker]
 * reply, by record order and, for daemon facts, by item order. Otherwise a content-free reason.
 */
internal fun ThreadEvidence.sendNowPlacementFailure(
    prompt: String,
    marker: String,
): String? {
    if (!admitted) return "unadmitted"
    val tool = records.indexOfFirst { it.message.role == Role.Tool }
    val users = records.withIndex().filter { (_, it) -> it.message.role == Role.User && it.fullText == prompt }
    val reply = records.indexOfFirst { it.message.role == Role.Assistant && marker in it.fullText }
    val placed = listOf(tool, users.singleOrNull()?.index ?: -1, reply)
    return when {
        placed.any { it < 0 } || users.size != 1 || userEchoCount(prompt) != 1 -> "missing tool=$tool users=${users.size} reply=$reply"
        !records[tool].isComplete || !records[reply].isComplete -> "incomplete tool=$tool reply=$reply"
        placed != placed.sorted() || placed.distinct().size != 3 -> "misplaced tool=$tool user=${placed[1]} reply=$reply"
        placed.mapNotNull { records[it].fact?.order }.let { orders ->
            orders.isNotEmpty() && (orders.size != 3 || orders.any { it <= 0 } || orders.zipWithNext().any { (a, b) -> a >= b })
        } -> "item order disagrees"
        else -> null
    }
}

private fun ThreadEvidence.mainAssistants() = records.filter { it.message.role == Role.Assistant && it.agentId == null }

/** Legacy keeps the list check; daemon facts need every main assistant item settled, joined in order to [expected]. */
internal fun ThreadEvidence.hasCompletedReplay(expected: String): Boolean =
    when (owner.kind) {
        ThreadEvidenceKind.Legacy -> records.map { ThreadItem.MessageItem(it.message) }.hasCompletedReplay(expected)
        ThreadEvidenceKind.Authoritative ->
            admitted &&
                mainAssistants().let { replies ->
                    replies.isNotEmpty() && replies.all { it.isComplete } && replies.joinToString("") { it.fullText } == expected
                }
    }

/**
 * Daemon facts only: one settled turn of distinct items in increasing order joins exactly to [expected],
 * and [prompt]'s one delivered echo precedes its last item. Legacy keeps its delta checks instead.
 */
internal fun ThreadEvidence.authoritativeReplayFailure(
    expected: String,
    prompt: String,
): String? {
    if (owner.kind != ThreadEvidenceKind.Authoritative) return "legacy evidence"
    if (!hasCompletedReplay(expected)) return "incomplete"
    val replies = mainAssistants()
    val turn = replies.map { it.session to it.turn }.distinct().singleOrNull() ?: return "split turns"
    if (settledReply(owner, turn.first ?: return "no session", turn.second ?: return "no turn") != replies) return "unsettled family"
    val orders = replies.map { it.fact?.order ?: 0 }
    if (orders.any { it <= 0 } || orders != orders.sorted() || orders.distinct().size != orders.size) return "item order"
    val users = records.filter { it.message.role == Role.User && it.fullText == prompt }
    if (users.size != 1 || userEchoCount(prompt) != 1) return "users=${users.size}"
    return if ((users.single().fact?.order ?: 0) in 1 until orders.last()) null else "user after reply"
}

/** Rendered text plus the source's own replay checks: legacy deltas, or daemon revisions and order. */
internal fun ComposeContentTestRule.assertOrderedReplay(
    evidence: ThreadEvidence,
    expected: String,
    prompt: String,
) {
    when (evidence.owner.kind) {
        ThreadEvidenceKind.Legacy -> assertOrderedReplay(evidence.records.map { ThreadItem.MessageItem(it.message) }, expected, prompt)
        ThreadEvidenceKind.Authoritative -> {
            assertEquals(expected, renderedReplyText(expected))
            assertNull("authoritative replay", evidence.authoritativeReplayFailure(expected, prompt))
        }
    }
}

/** One rendered Agent block: [agentId] and [runId] are the identities its header and run tags carry. */
internal class AgentFamilyEvidence(
    val agentId: String,
    val runId: String?,
    val root: ThreadEvidenceRecord,
    val children: List<ThreadEvidenceRecord>,
) {
    val tools: List<ThreadEvidenceRecord> get() = children.filter { it.message.role == Role.Tool }
    val childIds: List<String> get() = children.map { it.message.id }

    override fun toString(): String = "AgentFamilyEvidence(redacted)"
}

/** The single block whose root row is its own block, as the header tag requires, with every owned row. */
internal fun ThreadEvidence.agentFamily(owner: ThreadEvidenceOwner): AgentFamilyEvidence? {
    val all = current(owner)
    val root = all.singleOrNull { it.message.role == Role.Tool && it.agentId == it.message.id } ?: return null
    val agentId = root.agentId ?: return null
    val children = all.filter { it.agentId == agentId && it !== root }
    return AgentFamilyEvidence(agentId, children.firstOrNull { it.message.role == Role.Tool }?.toolRunId, root, children)
}

internal fun AgentFamilyEvidence.ownsSingleProse(text: String): Boolean =
    children.count { it.message.role == Role.Assistant && it.fullText == text } == 1

/** Prose of [text] exists, and none of it belongs to [family]'s block. */
internal fun ThreadEvidence.holdsOutsideAgent(
    text: String,
    family: AgentFamilyEvidence,
): Boolean =
    admitted && records.filter { it.fullText == text }.let { rows -> rows.isNotEmpty() && rows.none { it.agentId == family.agentId } }

/**
 * [family]'s block under the same identity has completed, with every tool in one run under the launching
 * turn, and all of it precedes the unowned main reply [text]. Legacy order is item order; its rendered
 * placement is the device test's bounds check.
 */
internal fun ThreadEvidence.settledAgentPrecedes(
    owner: ThreadEvidenceOwner,
    family: AgentFamilyEvidence,
    text: String,
): Boolean {
    val settled = agentFamily(owner)?.takeIf { it.agentId == family.agentId } ?: return false
    val reply = records.indexOfFirst { it.message.role == Role.Assistant && it.agentId == null && it.fullText == text }
    val owned = (listOf(settled.root) + settled.children).map { records.indexOf(it) }
    return reply >= 0 &&
        settled.root.isComplete &&
        settled.tools.isNotEmpty() &&
        settled.tools.all { it.isComplete && it.toolRunId == settled.runId && it.turn == settled.root.turn } &&
        owned.all { it in 0 until reply }
}
