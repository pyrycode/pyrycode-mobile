package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ephemeral admitted evidence; identity and content never appear in diagnostics or persisted metadata. */
internal class AuthoritativeCompletion(
    val owner: RemoteConversationRepository,
    val host: String,
    val conversation: String,
    private val epoch: String,
    private val generation: Long,
    val item: ThreadItemDto,
    val certifiedVersion: Long,
    sourceFrom: ULong?,
    previous: AuthoritativeCompletion?,
) {
    val key: String = "thread:${epoch.length}:$epoch:${item.id}"
    private val sourceRanges = certifiedRanges(sourceFrom, previous)

    /** Ranges are (source version, captured version]; gaps never acquire certification by merging. */
    private fun certifiedRanges(
        sourceFrom: ULong?,
        previous: AuthoritativeCompletion?,
    ): List<ULongRange> {
        val retained =
            previous
                ?.takeIf {
                    it.owner === owner &&
                        it.host == host &&
                        it.conversation == conversation &&
                        it.epoch == epoch &&
                        it.generation == generation &&
                        it.key == key &&
                        it.item.session == item.session &&
                        it.item.turn == item.turn &&
                        it.item.rev <= item.rev
                }?.sourceRanges
                .orEmpty()
        val captured = certifiedVersion.toULong()
        val added = sourceFrom?.takeIf { it < captured }?.let { listOf(it..captured) }.orEmpty()
        return buildList {
            for (range in (retained + added).sortedBy { it.first }) {
                val last = lastOrNull()
                if (last != null && range.first <= last.last) {
                    set(lastIndex, last.first..maxOf(last.last, range.last))
                } else {
                    add(range)
                }
            }
        }
    }

    fun isCurrent(snapshot: AuthoritativeThreadSnapshot): Boolean {
        if (snapshot.epoch != epoch || snapshot.generation != generation || snapshot.resetNeeded) return false
        val current = snapshot.items.firstOrNull { it.id == item.id } ?: return false
        return current.isCompletion() && current.rev >= item.rev && current.session == item.session && current.turn == item.turn
    }

    /** Full authoritative items own their text; only a certified range proves which is the completed reply's last segment. */
    fun preview(snapshot: AuthoritativeThreadSnapshot): String? {
        val certified = certifiedVersion
        if (!isCurrent(snapshot) ||
            snapshot.repairNeeded ||
            snapshot.checkpoint < snapshot.completedVersion ||
            certified < snapshot.completedVersion
        ) {
            return null
        }
        val coverage = snapshot.coverage ?: return null
        val completion = snapshot.items.firstOrNull { it.id == item.id } ?: return null
        val end = completion.order?.takeIf { it > 0 } ?: return null
        val session = completion.session?.takeIf { it.isNotBlank() } ?: return null
        val turn = completion.turn?.takeIf { it.isNotBlank() } ?: return null

        fun covered(fact: ThreadItemDto): Boolean {
            val order = fact.order?.takeIf { it > 0 }?.toULong() ?: return false
            if (fact.rev > certified) return false
            // Paging coverage stays fixed when a nonzero catch-up certifies newly created tail items.
            return order >= coverage.lowerOrder &&
                order < coverage.upperOrder ||
                sourceRanges.any { range ->
                    fact.id.toULong() > range.first && order > range.first && order <= range.last && fact.rev.toULong() <= range.last
                }
        }
        if (!covered(completion)) return null
        // Unknown placement must reach coverage validation and invalidate the entire reply range.
        val replies =
            snapshot.items.filter {
                it.kind == "assistant_message" &&
                    it.session == session &&
                    it.turn == turn &&
                    (it.parent ?: 0) == 0L &&
                    it.shown &&
                    (it.order == null || it.order <= end)
            }
        if (replies.isEmpty() ||
            replies.any {
                it.active ||
                    it.status !in setOf("done", "finished") ||
                    it.rev > snapshot.checkpoint ||
                    !covered(it)
            }
        ) {
            return null
        }
        val texts =
            replies.map { reply ->
                val content = reply.content as? JsonObject ?: return null
                for ((field, expected) in listOf("conversation_id" to conversation, "turn_id" to turn)) {
                    val value = content[field] ?: continue
                    if (value !is JsonPrimitive || !value.isString || value.content != expected) return null
                }
                (content["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            }
        return texts.lastOrNull { it.isNotBlank() }
    }

    override fun toString(): String = "AuthoritativeCompletion(redacted)"
}

internal fun ThreadItemDto.isCompletion(): Boolean =
    kind == "turn_end" && !active && status in setOf("done", "finished", "failed", "stopped") && (parent ?: 0) == 0L
