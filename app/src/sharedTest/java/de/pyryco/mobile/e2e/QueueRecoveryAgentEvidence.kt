package de.pyryco.mobile.e2e

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
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

/** A retained replay cannot certify a replacement connection or document, even with identical item IDs. */
internal fun ThreadEvidence.isCurrentReplay(
    host: String,
    conversation: String,
    connection: Any?,
    snapshot: AuthoritativeThreadSnapshot?,
): Boolean =
    admitted &&
        owner.kind == ThreadEvidenceKind.Authoritative &&
        owner.host == host &&
        owner.conversation == conversation &&
        connection != null &&
        owner.connection === connection &&
        snapshot != null &&
        sourceSnapshot === snapshot

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

/** Rejoin the captured launch after a certified generation reset, retaining source and fact ownership. */
internal fun ThreadEvidence.settledAgentAfterRecovery(
    launched: ThreadEvidence,
    captured: AgentFamilyEvidence,
    followingText: String,
): AgentFamilyEvidence? {
    if (!admitted || !launched.admitted) return null
    if (owner.kind == ThreadEvidenceKind.Legacy) {
        return agentFamily(launched.owner)?.takeIf { settledAgentPrecedes(launched.owner, captured, followingText) }
    }
    val before = launched.owner
    val oldGeneration = before.generation ?: return null
    val currentGeneration = owner.generation ?: return null
    if (before.kind != owner.kind ||
        before.connection !== owner.connection ||
        before.host != owner.host ||
        owner.host.isNullOrBlank() ||
        before.conversation != owner.conversation ||
        before.epoch != owner.epoch ||
        owner.epoch.isNullOrBlank() ||
        currentGeneration < oldGeneration
    ) {
        return null
    }
    val family = agentFamily(owner) ?: return null
    val capturedTool = captured.root.rawToolUseId()?.takeIf { it.isNotBlank() } ?: return null
    if (family.root.rawToolUseId() != capturedTool) return null

    fun retains(
        previous: ThreadEvidenceRecord,
        current: ThreadEvidenceRecord,
    ): Boolean {
        val old = previous.fact ?: return false
        val now = current.fact ?: return false
        if (old.id != now.id ||
            old.kind != now.kind ||
            old.session.isNullOrBlank() ||
            old.turn.isNullOrBlank() ||
            old.session != now.session ||
            old.turn != now.turn ||
            old.parent != now.parent ||
            old.order != now.order ||
            old.agent != now.agent ||
            now.rev < old.rev
        ) {
            return false
        }
        if (previous.message.role == Role.Assistant) {
            if (previous.fullText == current.fullText) return true
            return old.active && !now.active && now.rev > old.rev && current.fullText.startsWith(previous.fullText)
        }
        val oldInput = old.content as? kotlinx.serialization.json.JsonObject ?: return false
        val input = now.content as? kotlinx.serialization.json.JsonObject ?: return false
        return listOf("tool_use_id", "name", "input").all { oldInput[it] == input[it] }
    }
    if (!retains(captured.root, family.root)) return null
    if (captured.children.any { old ->
            val current = family.children.singleOrNull { it.fact?.id == old.fact?.id } ?: return@any true
            !retains(old, current)
        }
    ) {
        return null
    }
    if (!settledAgentPrecedes(owner, family, followingText)) return null
    val reply =
        records.singleOrNull {
            it.message.role == Role.Assistant && it.agentId == null && it.fullText == followingText
        } ?: return null
    val snapshot = sourceSnapshot ?: return null
    val coverage = snapshot.coverage ?: return null
    val proven = listOf(family.root, reply) + family.children
    if (proven.any {
            val fact = it.fact ?: return@any true
            val order = fact.order?.takeIf { value -> value > 0 }?.toULong() ?: return@any true
            fact.rev > snapshot.checkpoint || order !in coverage.lowerOrder..coverage.upperOrder
        }
    ) {
        return null
    }
    return family
}

/** A controlled fragment must occur exactly once in this Agent's attributed assistant text. */
internal fun AgentFamilyEvidence.ownsSingleProseFragment(text: String): Boolean {
    if (text.isEmpty()) return false
    val fragment = Regex(Regex.escape(text))
    return children.filter { it.message.role == Role.Assistant }.sumOf { fragment.findAll(it.fullText).count() } == 1
}
