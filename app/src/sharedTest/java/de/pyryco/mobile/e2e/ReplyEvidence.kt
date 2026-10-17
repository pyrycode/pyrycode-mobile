package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * #2170: reply, reopen and readiness predicates over source-bound [ThreadEvidence]. Each one means the
 * same thing for the legacy harness binding and for admitted authoritative facts. Authoritative records
 * use only the item's own kind, status and activity; receipt, queued acceptances and parents never settle.
 */

/** An assistant reply still receiving text: a streaming legacy row or an active `assistant_message` fact. */
internal fun ThreadEvidenceRecord.isOpenReply(): Boolean =
    message.role == Role.Assistant &&
        if (fact == null) message.isStreaming else fact.kind == "assistant_message" && fact.active

/** A complete reply. A failed or interrupted authoritative item is neither open nor finished. */
internal fun ThreadEvidenceRecord.isFinishedReply(): Boolean =
    message.role == Role.Assistant &&
        if (fact == null) {
            !message.isStreaming
        } else {
            fact.kind == "assistant_message" && !fact.active && fact.status in setOf("done", "finished")
        }

/** Main-thread attribution, as [ThreadEvidence.loadedMainReply] reads it for each source. */
private fun ThreadEvidenceRecord.isMain(): Boolean =
    agentId == null && if (fact == null) message.parentToolUseId.isEmpty() else (fact.parent ?: 0) == 0L

/** The first open main-thread assistant reply whose rendered text [accept]s. */
internal fun ThreadEvidence.openMainReply(accept: (String) -> Boolean): ThreadEvidenceRecord? =
    if (admitted) records.firstOrNull { it.isMain() && it.isOpenReply() && accept(it.message.content) } else null

/**
 * The one record with rendered identity [id], read only from evidence [captured] still owns. Another host,
 * connection, epoch or generation, or unadmitted evidence, yields nothing even when item IDs collide.
 */
internal fun ThreadEvidence.reply(
    captured: ThreadEvidenceOwner,
    id: String,
): ThreadEvidenceRecord? = if (admitted && captured.matches(owner)) records.singleOrNull { it.message.id == id } else null

/** Whether an assistant reply's full text holds [token]; a diagnostic boolean only. */
internal fun ThreadEvidence.holdsAssistantText(token: String): Boolean =
    admitted &&
        records.any {
            it.message.role == Role.Assistant && (it.fact == null || it.fact.kind == "assistant_message") && token in it.fullText
        }

/** Whether a finished main-thread assistant reply reads `ping`; partial, Agent-child and user rows cannot. */
internal fun ThreadEvidence.holdsPingReply(): Boolean =
    admitted && records.any { it.isMain() && it.isFinishedReply() && it.fullText.isPingReply() }

/**
 * Indices of the delivered queued user row. Legacy rows carry the app `message_id`; an authoritative
 * `user_message` is matched only by its `content.queued_msg_id`, never by message ID, text or item ID.
 */
internal fun ThreadEvidence.deliveredQueuedUser(
    messageId: String,
    queuedMsgId: Long,
): List<Int> =
    if (!admitted) {
        emptyList()
    } else {
        records.indices.filter { index ->
            val record = records[index]
            val fact = record.fact
            record.message.role == Role.User &&
                if (fact == null) {
                    record.message.id == messageId
                } else {
                    val handle = (fact.content as? JsonObject)?.get("queued_msg_id") as? JsonPrimitive
                    fact.kind == "user_message" &&
                        fact.status == "delivered" &&
                        handle?.isString == false &&
                        handle.content == queuedMsgId.toString()
                }
        }
    }

internal fun ThreadEvidence.lastToolIndex(): Int = if (admitted) records.indexOfLast { it.message.role == Role.Tool } else -1

internal fun ThreadEvidence.isToolFree(): Boolean = admitted && records.none { it.message.role == Role.Tool }

/** The first evidence from the still-current source; a replaced connection's evidence is never returned. */
internal suspend fun <R : Any> StateFlow<R?>.liveThreadEvidence(
    conversation: String,
    bind: (R) -> ThreadEvidenceSource,
): ThreadEvidence = firstOnLive({ bind(it).observe(conversation) }) { true }
