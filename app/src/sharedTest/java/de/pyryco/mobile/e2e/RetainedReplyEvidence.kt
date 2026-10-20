package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A successful producer turn. It cannot stand in for repository admission or rendering. */
internal class ProducedReplyEvidence(
    val host: String,
    val conversation: String,
    val messageId: String,
    val turn: String,
    val session: String?,
    val text: String,
) {
    fun admitted(
        evidence: ThreadEvidence,
        currentHost: String,
    ): CompletedReplyObservation? {
        if (!evidence.admitted ||
            host != currentHost ||
            evidence.owner.host?.let { it != host } == true ||
            evidence.owner.conversation != conversation
        ) {
            return null
        }
        val user =
            evidence.records.singleOrNull {
                it.message.role == Role.User &&
                    if (it.fact == null) {
                        it.message.ordinaryId == messageId
                    } else {
                        val fields = it.fact.content as? JsonObject
                        it.fact.kind == "user_message" &&
                            it.fact.status == "delivered" &&
                            (fields?.get("message_id") as? JsonPrimitive)?.content == messageId
                    }
            } ?: return null
        val rowSession = user.session ?: return null
        if (session != null && rowSession.isNotBlank() && session != rowSession) return null
        if (user.turn != null && user.turn != turn) return null
        val family = evidence.settledReply(evidence.owner, rowSession, turn) ?: return null
        if (family.any { it.agentId != null || it.message.isStreaming } ||
            family.map { it.message.id }.distinct().size != family.size ||
            family.joinToString("") { it.fullText } != text
        ) {
            return null
        }
        val logicalSession = rowSession.takeIf { it.isNotBlank() } ?: session ?: return null
        return CompletedReplyObservation(
            evidence.owner,
            logicalSession,
            turn,
            text,
            family.filter { it.message.content.isNotBlank() },
            evidence,
            host,
        ).takeIf { it.records.isNotEmpty() }
    }

    override fun toString(): String = "ProducedReplyEvidence(redacted)"
}

/** Rebind a captured reply to admitted current-source records without substituting another turn. */
internal fun ThreadEvidence.retainedReply(
    captured: CompletedReplyObservation,
    currentHost: String,
): CompletedReplyObservation? {
    if (!admitted ||
        currentHost != captured.host ||
        owner.host?.let { it != currentHost } == true ||
        owner.conversation != captured.owner.conversation ||
        owner.kind != captured.owner.kind ||
        owner.epoch != captured.owner.epoch
    ) {
        return null
    }
    val family =
        (settledTurnReply(owner, captured.turn) ?: return null).filter { it.message.content.isNotBlank() }
    val originalSession = captured.records.first().session ?: return null
    if (family.any { it.session != originalSession && (originalSession.isNotEmpty() || it.session != captured.session) }) return null

    fun key(record: ThreadEvidenceRecord): Any = record.fact?.id ?: record.message.id
    if (family.size != captured.records.size ||
        family.map(::key) != captured.records.map(::key) ||
        family.zip(captured.records).any { (now, old) -> now.fullText != old.fullText || now.message.content != old.message.content } ||
        family.any { it.agentId != null || it.message.isStreaming } ||
        family.map(::key).distinct().size != family.size ||
        family.joinToString("") { it.fullText } != captured.producerText
    ) {
        return null
    }
    return CompletedReplyObservation(owner, captured.session, captured.turn, captured.producerText, family, this, currentHost)
}

/** Persistence only. Unrelated newer facts never grant this saved source current admission. */
internal fun ThreadEvidence.retainsCachedReply(
    captured: CompletedReplyObservation,
    currentHost: String,
): Boolean {
    if (owner.kind == ThreadEvidenceKind.Legacy) return retainedReply(captured, currentHost) != null
    val snapshot = sourceSnapshot ?: return false
    val epoch = captured.owner.epoch?.takeIf { it.isNotBlank() } ?: return false
    if (currentHost != captured.host ||
        owner.host != currentHost ||
        owner.conversation != captured.owner.conversation ||
        captured.owner.kind != ThreadEvidenceKind.Authoritative ||
        owner.epoch != epoch ||
        snapshot.epoch != epoch ||
        snapshot.resetNeeded ||
        captured.session.isBlank() ||
        captured.turn.isBlank() ||
        captured.records.isEmpty()
    ) {
        return false
    }
    val family =
        snapshot.items
            .filter {
                it.kind == "assistant_message" && it.session == captured.session && it.turn == captured.turn && (it.parent ?: 0) == 0L
            }.sortedBy { it.order }
    if (family.map { it.id } != captured.records.map { it.fact?.id } || family.map { it.id }.distinct().size != family.size) return false
    return family.zip(captured.records).all { (now, old) ->
        val original = old.fact ?: return@all false
        val retained = records.singleOrNull { it.fact?.id == now.id } ?: return@all false
        val text = ((now.content as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        now.shown &&
            !now.active &&
            now.status in setOf("done", "finished") &&
            now.rev >= original.rev &&
            now.rev <= snapshot.checkpoint &&
            retained.fact?.rev == now.rev &&
            retained.agentId == null &&
            !retained.message.isStreaming &&
            retained.session == captured.session &&
            retained.turn == captured.turn &&
            text == old.fullText &&
            retained.fullText == old.fullText &&
            retained.message.content == old.message.content
    }
}

/** Inspect the same raw saved source, including assistant facts omitted from display. */
internal fun ThreadEvidence.cachedReplyAbsent(produced: ProducedReplyEvidence): Boolean {
    if (owner.host != produced.host || owner.conversation != produced.conversation) return false
    return if (owner.kind == ThreadEvidenceKind.Authoritative) {
        if (produced.session.isNullOrBlank()) return false
        sourceSnapshot?.items?.none {
            it.kind == "assistant_message" && it.session == produced.session && it.turn == produced.turn
        } == true
    } else {
        records.none { it.message.role == Role.Assistant && it.turn == produced.turn }
    }
}
