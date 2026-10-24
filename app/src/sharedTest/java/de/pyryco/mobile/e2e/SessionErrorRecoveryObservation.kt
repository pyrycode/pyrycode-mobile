package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Permanent acceptance identity; no payload or correlation value belongs in diagnostics. */
internal class RecoveryAcceptance(
    val id: Long,
    val queuedMsgId: ULong,
    val messageId: String,
    val text: String,
    private val clientSentAt: JsonElement?,
) {
    fun matches(item: ThreadItemDto): Boolean =
        item.id == id &&
            item.kind == "user_message" &&
            item.field("message_id") == messageId &&
            item.field("text") == text &&
            (item.content as? JsonObject)?.get("client_sent_at") == clientSentAt &&
            item.queueHandle() == queuedMsgId

    override fun toString(): String = "RecoveryAcceptance(redacted)"

    companion object {
        fun from(item: ThreadItemDto): RecoveryAcceptance {
            val handle = checkNotNull(item.queueHandle()) { "canonical acceptance has no exact queue handle" }
            val correlation = checkNotNull(item.field("message_id")) { "canonical acceptance has no correlation" }
            check(correlation.isNotBlank()) { "canonical acceptance has blank correlation" }
            return RecoveryAcceptance(
                item.id,
                handle,
                correlation,
                checkNotNull(item.field("text")),
                (item.content as? JsonObject)?.get("client_sent_at"),
            )
        }
    }
}

/** Scenario-only proof over the independent peer's completed, current source queries. */
internal class SessionErrorRecoveryObservation(
    private val producer: ProducerThreadObservation,
) {
    private val baseline = checkNotNull(producer.evidence()) { "recovery observer needs a certified baseline" }
    private val baselineItems = checkNotNull(baseline.sourceSnapshot).items
    private val initialIds = baselineItems.map { it.id }.toSet()
    private val priorTurns =
        baselineItems
            .mapNotNull { it.turn }
            .toSet()

    private fun items(): List<ThreadItemDto>? {
        val evidence = producer.evidence() ?: return null
        val owner = evidence.owner
        if (!evidence.admitted ||
            owner.host != baseline.owner.host ||
            owner.conversation != baseline.owner.conversation ||
            owner.epoch != baseline.owner.epoch ||
            owner.kind != ThreadEvidenceKind.Authoritative ||
            owner.connection !== producer.connection
        ) {
            return null
        }
        val snapshot = evidence.sourceSnapshot ?: return null
        if (snapshot.repairNeeded || snapshot.resetNeeded) return null
        return snapshot.items.takeIf { producer.evidence()?.owner?.matches(owner) == true }
    }

    fun queued(): List<RecoveryAcceptance>? =
        items()
            ?.filter {
                it.kind == "user_message" && it.status == "queued" && it.active
            }?.map(RecoveryAcceptance::from)

    fun deliveries(): List<RecoveryAcceptance>? =
        items()
            ?.filter {
                it.id !in initialIds && it.kind == "user_message" && it.status == "delivered" && !it.active && (it.order ?: 0) > 0
            }?.map(RecoveryAcceptance::from)

    fun retained(accepted: RecoveryAcceptance): Boolean =
        items()?.singleOrNull { it.id == accepted.id }?.let { accepted.matches(it) && it.status == "queued" && it.active } == true

    fun dropped(accepted: RecoveryAcceptance): Boolean =
        items()?.singleOrNull { it.id == accepted.id }?.let {
            accepted.matches(it) &&
                it.status == "dropped" &&
                !it.active &&
                !it.shown &&
                it.order == null
        } ==
            true

    fun completed(accepted: RecoveryAcceptance): Boolean {
        val items = items() ?: return false
        val user = items.singleOrNull { accepted.matches(it) && it.status == "delivered" && !it.active && it.shown } ?: return false
        val order = user.order?.takeIf { it > 0 } ?: return false
        val session = user.session?.takeIf { it.isNotBlank() } ?: return false
        if (items.any { it.kind == "session_divider" && (it.order ?: 0) > order }) return false
        val nextUser =
            items.filter { it.kind == "user_message" && it.status == "delivered" && (it.order ?: 0) > order }.minOfOrNull {
                it.order
                    ?: Long.MAX_VALUE
            }
                ?: Long.MAX_VALUE
        val after =
            items
                .filter {
                    it.session == session && (it.parent ?: 0) == 0L && (it.order ?: 0) > order && (it.order ?: 0) < nextUser
                }.sortedBy { it.order }
        val candidate = after.firstOrNull { it.kind == "assistant_message" } ?: return false
        val turn = candidate.turn?.takeIf { it.isNotBlank() && it !in priorTurns } ?: return false
        if (!user.turn.isNullOrBlank() && user.turn != turn) return false
        val family = after.filter { it.kind == "assistant_message" && it.turn == turn }
        if (family.any {
                it.active ||
                    !it.shown ||
                    it.status !in
                    setOf(
                        "done",
                        "finished",
                    ) ||
                    it.field("text").isNullOrBlank()
            }
        ) {
            return false
        }
        val terminal = after.singleOrNull { it.kind == "turn_end" && it.turn == turn } ?: return false
        return !terminal.active &&
            terminal.status == "done" &&
            (terminal.order ?: 0) > (family.last().order ?: Long.MAX_VALUE) &&
            (terminal.content as? JsonObject).isSuccessfulReplyEnd()
    }
}

private fun ThreadItemDto.field(name: String): String? =
    ((content as? JsonObject)?.get(name) as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.content

private fun ThreadItemDto.queueHandle(): ULong? =
    ((content as? JsonObject)?.get("queued_msg_id") as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull()?.takeIf {
        it >
            0uL
    }
