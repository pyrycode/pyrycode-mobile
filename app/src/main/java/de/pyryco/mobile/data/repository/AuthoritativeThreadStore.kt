package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_ADDED
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_CHANGED
import de.pyryco.mobile.data.network.TYPE_THREAD_TEXT_APPEND
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadUpdateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Complete daemon facts only. Generation belongs to asynchronous sync work, not wire identity. */
internal data class AuthoritativeThreadSnapshot(
    val epoch: String? = null,
    val items: List<ThreadItemDto> = emptyList(),
    val completedVersion: Long = 0,
    val checkpoint: Long = 0,
    val repairNeeded: Boolean = false,
    val resetNeeded: Boolean = false,
    val generation: Long = 0,
) {
    override fun toString(): String = "AuthoritativeThreadSnapshot(content=***)"
}

internal enum class AuthoritativeThreadOutcome { Applied, Ignored, RepairNeeded, ResetNeeded, StaleGeneration }

/** In-memory facts keyed by source and conversation, independent of legacy projection/cache. */
internal class AuthoritativeThreadStore {
    private val snapshots = MutableStateFlow<Map<Pair<String, String>, AuthoritativeThreadSnapshot>>(emptyMap())

    fun current(
        host: String,
        conversation: String,
    ): AuthoritativeThreadSnapshot = snapshots.value[host to conversation] ?: AuthoritativeThreadSnapshot()

    fun observe(
        host: String,
        conversation: String,
    ): Flow<AuthoritativeThreadSnapshot> =
        snapshots.map { it[host to conversation] ?: AuthoritativeThreadSnapshot() }.distinctUntilChanged()

    @Synchronized
    fun apply(
        host: String,
        update: ThreadUpdateDto,
        generation: Long,
    ): AuthoritativeThreadOutcome {
        val conversation = update.conversationId
        guard(host, conversation, update.epoch, generation)?.let { return it }
        if (!number(update.version) || update.version < update.rev) return requestRepair(host, conversation)
        if (update.type == TYPE_THREAD_ITEM_ADDED) {
            val item = update.item ?: return requestRepair(host, conversation)
            return applyBatch(host, conversation, update.epoch, update.version, listOf(item), generation)
        }
        val current = current(host, conversation)
        val held = current.items.firstOrNull { it.id == update.itemId } ?: return requestRepair(host, conversation)
        if (held.rev != update.baseRev || update.rev <= held.rev) return requestRepair(host, conversation)
        val replacement =
            try {
                val fields =
                    when (update.type) {
                        TYPE_THREAD_ITEM_CHANGED -> {
                            val changes = update.changes ?: return requestRepair(host, conversation)
                            if ("id" in changes ||
                                "kind" in changes ||
                                ("rev" in changes && changes["rev"] != JsonPrimitive(update.rev))
                            ) {
                                return requestRepair(host, conversation)
                            }
                            held.raw + changes
                        }
                        TYPE_THREAD_TEXT_APPEND -> {
                            if (held.kind !in setOf("user_message", "assistant_message")) return requestRepair(host, conversation)
                            val content = held.content as? JsonObject ?: return requestRepair(host, conversation)
                            val text =
                                (content["text"] as? JsonPrimitive)
                                    ?.takeIf { it.isString }
                                    ?.content ?: return requestRepair(host, conversation)
                            val suffix = update.text ?: return requestRepair(host, conversation)
                            held.raw + ("content" to JsonObject(content + ("text" to JsonPrimitive(text + suffix))))
                        }
                        else -> return requestRepair(host, conversation)
                    }
                ThreadItemDto.decodeReplacement(JsonObject(fields + ("rev" to JsonPrimitive(update.rev))))
            } catch (_: IllegalArgumentException) {
                return requestRepair(host, conversation)
            }
        if (!valid(replacement, update.version)) return requestRepair(host, conversation)
        publish(
            host,
            conversation,
            current.copy(
                epoch = update.epoch,
                items = ordered(current.items.map { if (it.id == replacement.id) replacement else it }),
                completedVersion = maxOf(current.completedVersion, update.version),
            ),
        )
        RelayLog.i { "event=thread_store code=applied" }
        return AuthoritativeThreadOutcome.Applied
    }

    /** Full batches are upserts, never coverage, deletion or catch-up completion. */
    @Synchronized
    fun applyBatch(
        host: String,
        conversation: String,
        epoch: String,
        version: Long,
        items: List<ThreadItemDto>,
        generation: Long,
    ): AuthoritativeThreadOutcome {
        guard(host, conversation, epoch, generation)?.let { return it }
        val current = current(host, conversation)
        val merged = merge(current.items, items, version) ?: return requestRepair(host, conversation)
        publish(
            host,
            conversation,
            current.copy(epoch = epoch, items = ordered(merged), completedVersion = maxOf(current.completedVersion, version)),
        )
        RelayLog.i { "event=thread_store code=batch" }
        return if (merged == current.items &&
            current.completedVersion >= version
        ) {
            AuthoritativeThreadOutcome.Ignored
        } else {
            AuthoritativeThreadOutcome.Applied
        }
    }

    /** Caller certifies complete supplied state, not merely receipt of a page/batch or live update. */
    @Synchronized
    fun establishComplete(
        host: String,
        conversation: String,
        epoch: String,
        version: Long,
        items: List<ThreadItemDto>,
        generation: Long,
    ): AuthoritativeThreadOutcome {
        guard(host, conversation, epoch, generation)?.let { return it }
        val current = current(host, conversation)
        if (version < current.completedVersion) return requestRepair(host, conversation)
        val merged = merge(current.items, items, version) ?: return requestRepair(host, conversation)
        publish(
            host,
            conversation,
            current.copy(epoch = epoch, items = ordered(merged), completedVersion = version, checkpoint = version, repairNeeded = false),
        )
        RelayLog.i { "event=thread_store code=complete" }
        return AuthoritativeThreadOutcome.Applied
    }

    @Synchronized
    fun requestRepair(
        host: String,
        conversation: String,
    ): AuthoritativeThreadOutcome {
        val current = current(host, conversation)
        publish(host, conversation, current.copy(repairNeeded = true))
        RelayLog.i { "event=thread_store code=repair" }
        return AuthoritativeThreadOutcome.RepairNeeded
    }

    @Synchronized
    fun requestHostRepair(host: String) {
        snapshots.value.keys
            .filter { it.first == host }
            .forEach { requestRepair(host, it.second) }
    }

    /** Explicit reset fences all previously captured work, even when the supplied epoch is reused. */
    @Synchronized
    fun reset(
        host: String,
        conversation: String,
        epoch: String,
    ): AuthoritativeThreadOutcome {
        if (host.isEmpty() || conversation.isEmpty() || epoch.isEmpty()) return requestRepair(host, conversation)
        val generation = current(host, conversation).generation + 1
        publish(host, conversation, AuthoritativeThreadSnapshot(epoch = epoch, generation = generation, repairNeeded = true))
        RelayLog.i { "event=thread_store code=reset" }
        return AuthoritativeThreadOutcome.Applied
    }

    private fun guard(
        host: String,
        conversation: String,
        epoch: String,
        generation: Long,
    ): AuthoritativeThreadOutcome? {
        val current = current(host, conversation)
        if (generation != current.generation) return AuthoritativeThreadOutcome.StaleGeneration
        if (current.resetNeeded) return AuthoritativeThreadOutcome.ResetNeeded
        if (host.isEmpty() || conversation.isEmpty() || epoch.isEmpty()) return requestRepair(host, conversation)
        if (current.epoch != null && current.epoch != epoch) {
            publish(
                host,
                conversation,
                AuthoritativeThreadSnapshot(
                    epoch = current.epoch,
                    generation = current.generation + 1,
                    repairNeeded = true,
                    resetNeeded = true,
                ),
            )
            RelayLog.i { "event=thread_store code=epoch" }
            return AuthoritativeThreadOutcome.ResetNeeded
        }
        return null
    }

    private fun merge(
        held: List<ThreadItemDto>,
        incoming: List<ThreadItemDto>,
        version: Long,
    ): List<ThreadItemDto>? {
        if (!number(version)) return null
        val merged = held.associateBy { it.id }.toMutableMap()
        for (item in incoming) {
            if (!valid(item, version)) return null
            val previous = merged[item.id]
            if (previous != null && previous.kind != item.kind) return null
            if (previous == null || item.rev > previous.rev) merged[item.id] = item
        }
        return merged.values.toList()
    }

    private fun valid(
        item: ThreadItemDto,
        version: Long,
    ): Boolean =
        item.id > 0 &&
            item.rev >= item.id &&
            item.rev <= version &&
            number(item.id) &&
            number(item.rev) &&
            listOfNotNull(item.order, item.endedOrder, item.parent).all(::number)

    private fun number(value: Long): Boolean = value in 0..9007199254740991L

    // Zero is an explicit patch clear, never a position; stable ties never invent id/arrival ordering.
    private fun ordered(items: List<ThreadItemDto>): List<ThreadItemDto> =
        items.sortedBy { item -> item.order?.takeIf { it > 0L } ?: Long.MAX_VALUE }

    private fun publish(
        host: String,
        conversation: String,
        snapshot: AuthoritativeThreadSnapshot,
    ) {
        snapshots.value = snapshots.value + ((host to conversation) to snapshot)
    }
}
