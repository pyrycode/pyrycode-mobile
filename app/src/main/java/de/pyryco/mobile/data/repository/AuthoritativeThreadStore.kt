package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_ADDED
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_CHANGED
import de.pyryco.mobile.data.network.TYPE_THREAD_TEXT_APPEND
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.network.ThreadUpdateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Admitted daemon facts and provisional feedback. Certification excludes local sends. */
internal data class AuthoritativeThreadSnapshot(
    val epoch: String? = null,
    val items: List<ThreadItemDto> = emptyList(),
    val completedVersion: Long = 0,
    val checkpoint: Long = 0,
    val repairNeeded: Boolean = false,
    val resetNeeded: Boolean = false,
    val generation: Long = 0,
    val coverage: AuthoritativeThreadCoverage? = null,
    val pendingSends: List<AuthoritativePendingSend> = emptyList(),
    val orderMoves: Map<Long, AuthoritativeOrderMove> = emptyMap(),
    val incarnation: String = "",
    val retirements: Map<Long, Long> = emptyMap(),
    /** Transient opening contributions: false is content, true is a launch. Never durable certification. */
    val openingRows: Set<Pair<Long, Boolean>>? = null,
    val bodyLoads: Map<Pair<Long, Long>, BodyLoad> = emptyMap(),
    val bodyContents: Map<Pair<Long, Long>, JsonObject> = emptyMap(),
    val childPages: Map<Pair<Long, Long>, AgentChildPage> = emptyMap(),
    val childLoads: Map<Pair<Long, Long>, BodyLoad> = emptyMap(),
) {
    /** Expansion summaries never enter saved full facts or history coverage. */
    val expansionItems: List<ThreadItemDto> =
        buildMap {
            items.forEach { put(it.id, it) }
            val remaining = childPages.toMutableMap()
            do {
                var progressed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val (owner, page) = iterator.next()
                    if (get(owner.first)?.rev != owner.second) continue
                    page.items.forEach { child ->
                        val held = get(child.id)
                        if (child.id !in retirements &&
                            (held == null || held.parent == child.parent && child.rev > held.rev)
                        ) {
                            put(child.id, child)
                        }
                    }
                    iterator.remove()
                    progressed = true
                }
            } while (progressed)
        }.values.toList()

    fun hasToolBody(item: ThreadItemDto): Boolean = item.hasToolBody || (item.id to item.rev) in bodyContents

    override fun toString(): String = "AuthoritativeThreadSnapshot(content=***)"
}

internal data class AgentChildPage(
    val items: List<ThreadItemDto>,
    val cursor: JsonObject?,
    val version: ULong,
) {
    override fun toString() = "AgentChildPage(redacted)"
}

/** Runtime evidence of entry or the last accepted move, retained even when snapshots are conflated. */
internal data class AuthoritativeOrderMove(
    val capture: Long,
    val before: Boolean,
)

/** Certified continuous ordered history; parent/active extras do not contribute to this range. */
internal data class AuthoritativeThreadCoverage(
    val lowerOrder: ULong,
    val upperOrder: ULong,
    val continuation: ULong,
    val olderExists: Boolean,
)

internal enum class AuthoritativeThreadOutcome { Applied, Ignored, RepairNeeded, ResetNeeded, StaleGeneration }

/** In-memory facts keyed by source and conversation, independent of legacy projection/cache. */
internal class AuthoritativeThreadStore {
    private class RemovalPlacement(
        val order: Long,
        val peers: List<Long>,
    )

    private val removalPeers = mutableMapOf<Pair<String, String>, MutableMap<Long, RemovalPlacement>>()

    /** Content-free retained recovery size, independent of durable fences. */
    @Synchronized
    internal fun retainedRemovalPeerCount(): Int = removalPeers.values.sumOf { scope -> scope.values.sumOf { it.peers.size } }

    /** Called under historyWrites only after the mutation and successful baseline have committed. */
    @Synchronized
    fun commitRemovals(
        host: String,
        conversation: String,
        generation: Long,
        incarnation: String,
        retirements: Map<Long, Long>,
    ) {
        val current = current(host, conversation)
        if (current.generation != generation || current.incarnation != incarnation) return
        val key = host to conversation
        val pending = removalPeers[key] ?: return
        pending.keys.removeAll { id -> retirements[id]?.let { it == current.retirements[id] } == true }
        if (pending.isEmpty()) removalPeers.remove(key)
    }

    /** A capture of one owned authoritative fact, never inferred from absence or a projected row. */
    internal class Removal internal constructor(
        val host: String,
        val conversation: String,
        val epoch: String,
        val incarnation: String,
        val generation: Long,
        val id: Long,
        val revision: Long,
        internal val owner: AuthoritativeThreadStore,
    ) {
        override fun toString(): String = "AuthoritativeRemoval(redacted)"
    }

    @Synchronized
    fun captureRemoval(
        host: String,
        conversation: String,
        id: Long,
    ): Removal? {
        val current = current(host, conversation)
        val epoch = current.epoch ?: return null
        if (current.resetNeeded) return null
        return current.items.firstOrNull { it.id == id }?.let {
            Removal(host, conversation, epoch, current.incarnation, current.generation, id, it.rev, this)
        }
    }

    @Synchronized
    fun remove(target: Removal): AuthoritativeThreadOutcome {
        val current = current(target.host, target.conversation)
        if (target.owner !== this ||
            current.resetNeeded ||
            current.epoch != target.epoch ||
            current.incarnation != target.incarnation ||
            current.generation != target.generation
        ) {
            return AuthoritativeThreadOutcome.StaleGeneration
        }
        if (target.id in current.retirements) return AuthoritativeThreadOutcome.Ignored
        val held = current.items.firstOrNull { it.id == target.id } ?: return AuthoritativeThreadOutcome.Ignored
        if (held.rev != target.revision) return AuthoritativeThreadOutcome.Ignored
        val pending = removalPeers.getOrPut(target.host to target.conversation) { mutableMapOf() }
        val order = orderKey(held)
        val peers =
            current.items
                .filter { orderKey(it) == order }
                .map { it.id }
                .toMutableList()
        // Other uncommitted removals still own tie relationships if storage later refuses them.
        for ((id, placement) in pending) {
            if (placement.order != order) continue
            val split = placement.peers.indexOf(id)
            val before =
                placement.peers
                    .drop(split + 1)
                    .firstOrNull { it in peers }
                    ?.let(peers::indexOf)
            val after =
                placement.peers
                    .take(split)
                    .asReversed()
                    .firstOrNull { it in peers }
                    ?.let { peers.indexOf(it) + 1 }
            peers.add(before ?: after ?: 0, id)
        }
        pending[target.id] = RemovalPlacement(order, peers)
        publish(
            target.host,
            target.conversation,
            current.copy(
                items = current.items.filterNot { it.id == target.id },
                retirements = current.retirements + (target.id to target.revision),
            ),
        )
        RelayLog.i { "event=thread_store code=removed" }
        return AuthoritativeThreadOutcome.Applied
    }

    /** Withdraw only an uncommitted intent whose exact revision storage definitively superseded. */
    @Synchronized
    fun rejectRemoval(
        host: String,
        conversation: String,
        generation: Long,
        incarnation: String,
        revision: Long,
        held: ThreadItemDto,
    ) {
        val current = current(host, conversation)
        if (current.generation != generation || current.incarnation != incarnation || current.retirements[held.id] != revision) return
        val peers = removalPeers[host to conversation]?.get(held.id)?.peers.orEmpty()
        val split = peers.indexOf(held.id).coerceAtLeast(0)
        val positions =
            current.items
                .mapIndexedNotNull { index, item ->
                    if (orderKey(item) == orderKey(held)) item.id to index else null
                }.toMap()
        val before = peers.drop(split + 1).firstNotNullOfOrNull { positions[it] }
        val after =
            peers
                .take(split)
                .asReversed()
                .firstNotNullOfOrNull { positions[it] }
                ?.plus(1)
        val restored = current.items.toMutableList().apply { add(before ?: after ?: size, held) }
        publishSnapshot(
            host,
            conversation,
            current.copy(
                items = ordered(restored),
                completedVersion = maxOf(current.completedVersion, held.rev),
                retirements = current.retirements - held.id,
            ),
            preservePlacement = true,
        )
    }

    private val snapshots = MutableStateFlow<Map<Pair<String, String>, AuthoritativeThreadSnapshot>>(emptyMap())

    @Synchronized
    fun registerPending(
        host: String,
        conversation: String,
        deviceId: String?,
        message: de.pyryco.mobile.data.model.Message,
    ) {
        val current = current(host, conversation)
        publish(host, conversation, current.copy(pendingSends = current.pendingSends + AuthoritativePendingSend(deviceId, message)))
        RelayLog.i { "event=thread_send code=provisional" }
    }

    @Synchronized
    fun identifyPending(
        host: String,
        conversation: String,
        messageId: String,
        deviceId: String?,
    ) {
        val current = current(host, conversation)
        publish(
            host,
            conversation,
            current.copy(
                pendingSends =
                    current.pendingSends.map {
                        if (it.message.id == messageId) it.copy(deviceId = deviceId) else it
                    },
            ),
        )
    }

    @Synchronized
    fun isCurrentQueueTarget(target: AuthoritativeQueueTarget): Boolean {
        val current = current(target.host, target.conversation)
        if (current.resetNeeded || current.epoch != target.epoch || current.generation != target.generation) return false
        val item = current.items.firstOrNull { it.id == target.acceptedItemId } ?: return false
        return authoritativeQueueTarget(target.host, target.conversation, target.epoch, target.generation, item) == target
    }

    /** Keep validation and fire-and-forget send inside the same admission lock. */
    @Synchronized
    fun withCurrentQueueTarget(
        target: AuthoritativeQueueTarget,
        send: () -> Boolean,
    ): Boolean = isCurrentQueueTarget(target) && send()

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
    fun bodyLoad(
        host: String,
        conversation: String,
        id: Long,
        revision: Long,
        state: BodyLoad?,
    ) {
        val current = current(host, conversation)
        if (current.expansionItems.none { it.id == id && it.rev == revision && !current.hasToolBody(it) }) return
        val loads = current.bodyLoads.filterKeys { it.first != id }.toMutableMap()
        if (state != null) loads[id to revision] = state
        if (loads != current.bodyLoads) publish(host, conversation, current.copy(bodyLoads = loads))
    }

    /** Expansion certifies only this body, never history, placement or a recovery watermark. */
    @Synchronized
    fun enrichBody(
        host: String,
        request: ThreadQueryRequest.Body,
        data: ThreadQueryReply.Body,
        generation: Long,
        incarnation: String,
    ): Boolean {
        val current = current(host, request.conversationId)
        if (current.generation != generation ||
            current.incarnation != incarnation ||
            current.epoch != request.epoch ||
            current.resetNeeded
        ) {
            return false
        }
        val held =
            current.expansionItems.firstOrNull {
                it.id.toULong() == request.itemId &&
                    it.rev.toULong() == request.expectedRev &&
                    it.kind == request.kind
            }
                ?: return false
        if (current.hasToolBody(held)) return true
        val contents = data.content ?: return false
        publish(
            host,
            request.conversationId,
            current.copy(
                bodyContents = current.bodyContents.filterKeys { it.first != held.id } + ((held.id to held.rev) to contents),
                bodyLoads = current.bodyLoads - (held.id to held.rev),
            ),
        )
        RelayLog.i { "event=thread_body code=loaded" }
        return true
    }

    @Synchronized
    fun childLoad(
        host: String,
        conversation: String,
        id: Long,
        rev: Long,
        state: BodyLoad?,
    ) {
        val current = current(host, conversation)
        if (current.expansionItems.none { it.id == id && it.rev == rev }) return
        val loads = current.childLoads.filterKeys { it.first != id }.toMutableMap()
        if (state != null) loads[id to rev] = state
        if (loads != current.childLoads) publish(host, conversation, current.copy(childLoads = loads))
    }

    @Synchronized
    fun enrichChildren(
        host: String,
        request: ThreadQueryRequest.Children,
        data: List<ThreadItemDto>,
        marker: ThreadQueryReply.Children,
        generation: Long,
        incarnation: String,
    ): Boolean {
        val current = current(host, request.conversationId)
        if (current.generation != generation ||
            current.incarnation != incarnation ||
            current.epoch != request.epoch ||
            current.resetNeeded
        ) {
            return false
        }
        val parent =
            current.expansionItems.firstOrNull { it.id.toULong() == request.itemId && it.rev.toULong() == request.expectedRev }
                ?: return false
        val key = parent.id to parent.rev
        val old = current.childPages[key]
        if (old != null && marker.checkpoint.version < old.version) return false
        if (request.cursor != null && (old?.cursor != request.cursor || old.version != marker.checkpoint.version)) return false
        if (data.any { child -> current.expansionItems.any { it.id == child.id && it.parent != child.parent } }) return false
        val rows = (if (request.cursor == null) emptyList() else old?.items.orEmpty()) + data
        if (rows.map { it.id }.distinct().size != rows.size) return false
        publish(
            host,
            request.conversationId,
            current.copy(
                childPages =
                    current.childPages.filterKeys { it.first != parent.id } +
                        (key to AgentChildPage(rows, marker.cursor, marker.checkpoint.version)),
                childLoads = current.childLoads - key,
            ),
        )
        RelayLog.i { "event=thread_children code=loaded" }
        return true
    }

    /** Disk facts do not own a runtime generation, connection capabilities or transient state. */
    @Synchronized
    fun restore(
        host: String,
        conversation: String,
        saved: AuthoritativeThreadSnapshot,
        generation: Long,
        expected: AuthoritativeThreadSnapshot? = null,
        openingRows: Set<Pair<Long, Boolean>>? = null,
        olderSaved: Boolean = false,
    ): AuthoritativeThreadOutcome {
        val current = current(host, conversation)
        if (expected != null && current != expected) return AuthoritativeThreadOutcome.Ignored
        // A reset already owns this store, even if its epoch string was reused before this read began.
        if (generation != current.generation || current.generation != 0L) return AuthoritativeThreadOutcome.StaleGeneration
        if (current.resetNeeded || (current.epoch != null && current.epoch != saved.epoch)) return AuthoritativeThreadOutcome.Ignored
        if (host.isEmpty() || conversation.isEmpty() || saved.epoch.isNullOrEmpty()) return AuthoritativeThreadOutcome.Ignored
        if (current.incarnation.isNotEmpty() && current.incarnation != saved.incarnation) return AuthoritativeThreadOutcome.Ignored
        val retirements = saved.retirements + current.retirements
        val merged = merge(current.items, saved.items, saved.completedVersion, retirements) ?: return AuthoritativeThreadOutcome.Ignored
        val adoptCertification = current.checkpoint == 0L
        val completedVersion = maxOf(current.completedVersion, saved.completedVersion)
        val checkpoint = if (adoptCertification) saved.checkpoint else current.checkpoint
        publishSnapshot(
            host,
            conversation,
            current.copy(
                epoch = saved.epoch,
                incarnation = saved.incarnation,
                retirements = retirements,
                items = ordered(if (olderSaved) insertOlderSaved(current.items, saved.items, merged) else merged),
                completedVersion = completedVersion,
                checkpoint = checkpoint,
                coverage = if (adoptCertification && current.coverage == null) saved.coverage else current.coverage,
                openingRows = openingRows?.plus(current.openingRows ?: current.items.flatMap { listOf(it.id to false, it.id to true) }),
                // Live updates may lead the checkpoint until completion; a known-live supplement adds no new gap.
                repairNeeded = current.repairNeeded || saved.completedVersion > current.completedVersion && completedVersion > checkpoint,
            ),
            restoring = true,
            preservePlacement = olderSaved,
        )
        RelayLog.i { "event=thread_store code=restored" }
        return AuthoritativeThreadOutcome.Applied
    }

    /** Insert older committed tie peers before their next held saved peer, preserving held/live order. */
    private fun insertOlderSaved(
        held: List<ThreadItemDto>,
        saved: List<ThreadItemDto>,
        merged: List<ThreadItemDto>,
    ): List<ThreadItemDto> {
        val winners = merged.associateBy { it.id }
        val heldIds = held.mapTo(HashSet()) { it.id }
        val pending = linkedMapOf<Long, MutableList<ThreadItemDto>>()
        val before = mutableMapOf<Long, List<ThreadItemDto>>()
        saved.forEach { committed ->
            val item = winners[committed.id] ?: return@forEach
            val order = orderKey(item)
            if (item.id !in heldIds) {
                pending.getOrPut(order) { mutableListOf() }.add(item)
            } else if (orderKey(committed) == order) {
                pending.remove(order)?.let { before[item.id] = it }
            }
        }
        return buildList {
            held.forEach { item ->
                addAll(before[item.id].orEmpty())
                winners[item.id]?.let(::add)
            }
            pending.values.forEach(::addAll)
        }
    }

    /** Capture notification ownership while the live route still holds its reset lock. */
    fun repairGenerations(
        host: String,
        conversation: String?,
    ): Map<String, Long> =
        snapshots.value.entries
            .filter { it.key.first == host && (conversation == null || it.key.second == conversation) && it.value.repairNeeded }
            .associate { it.key.second to it.value.generation }

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
                openingRows = current.openingRows?.plus(listOf(replacement.id to false, replacement.id to true)),
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
        val merged = merge(current.items, items, version, current.retirements) ?: return requestRepair(host, conversation)
        publish(
            host,
            conversation,
            current.copy(
                epoch = epoch,
                items = ordered(merged),
                completedVersion = maxOf(current.completedVersion, version),
                openingRows = current.openingRows?.plus(items.flatMap { listOf(it.id to false, it.id to true) }),
            ),
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
        val merged = merge(current.items, items, version, current.retirements) ?: return requestRepair(host, conversation)
        publish(
            host,
            conversation,
            current.copy(
                epoch = epoch,
                items = ordered(merged),
                completedVersion = version,
                checkpoint = version,
                repairNeeded = false,
                openingRows = current.openingRows?.plus(items.flatMap { listOf(it.id to false, it.id to true) }),
            ),
        )
        RelayLog.i { "event=thread_store code=complete" }
        return AuthoritativeThreadOutcome.Applied
    }

    /** All batches have applied; only this request's separate marker can certify its captured facts. */
    @Synchronized
    fun certifyQuery(
        host: String,
        request: ThreadQueryRequest,
        success: ThreadQueryReply.Success,
        generation: Long,
    ): AuthoritativeThreadOutcome {
        val conversation = request.conversationId
        val checkpoint = success.checkpoint
        guard(host, conversation, checkpoint.epoch, generation)?.let { return it }
        val current = current(host, conversation)
        if (checkpoint.version > 9007199254740991uL) return requestRepair(host, conversation)
        var coverage = current.coverage
        when (val certified = success.coverage) {
            is ThreadQueryReply.Coverage.CatchUp -> {
                if (request !is ThreadQueryRequest.CatchUp || certified.fromVersion != current.checkpoint.toULong()) {
                    return requestRepair(host, conversation)
                }
            }
            is ThreadQueryReply.Coverage.Window -> {
                if (certified.lowerOrder > 9007199254740991uL ||
                    certified.upperOrder > 9007199254740991uL ||
                    certified.continuation > 9007199254740991uL
                ) {
                    return requestRepair(host, conversation)
                }
                val range =
                    AuthoritativeThreadCoverage(certified.lowerOrder, certified.upperOrder, certified.continuation, certified.olderExists)
                if (request is ThreadQueryRequest.Page) {
                    if (coverage == null || request.beforeOrder != coverage.continuation || certified.upperOrder != request.beforeOrder) {
                        return requestRepair(host, conversation)
                    }
                    coverage = range.copy(upperOrder = coverage.upperOrder)
                } else {
                    coverage =
                        if (coverage != null && range.lowerOrder <= coverage.upperOrder && range.upperOrder >= coverage.lowerOrder) {
                            val lower = minOf(coverage.lowerOrder, range.lowerOrder)
                            range.copy(
                                lowerOrder = lower,
                                upperOrder = maxOf(coverage.upperOrder, range.upperOrder),
                                continuation = lower,
                                olderExists = if (lower == range.lowerOrder) range.olderExists else coverage.olderExists,
                            )
                        } else {
                            range
                        }
                }
            }
        }
        val version = checkpoint.version.toLong()
        val catchesUp = request is ThreadQueryRequest.CatchUp && version >= current.completedVersion
        publish(
            host,
            conversation,
            current.copy(
                epoch = checkpoint.epoch,
                coverage = coverage,
                checkpoint = if (catchesUp) version else current.checkpoint,
                completedVersion = if (catchesUp) version else current.completedVersion,
                repairNeeded = if (request is ThreadQueryRequest.CatchUp) !catchesUp else current.repairNeeded,
            ),
        )
        RelayLog.i { "event=thread_store code=certified" }
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
        publish(
            host,
            conversation,
            AuthoritativeThreadSnapshot(
                epoch = epoch,
                incarnation = UUID.randomUUID().toString(),
                generation = generation,
                repairNeeded = true,
                pendingSends = current(host, conversation).pendingSends,
            ),
        )
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
                    incarnation = current.incarnation,
                    retirements = current.retirements,
                    completedVersion = if (current.retirements.isEmpty()) 0 else current.completedVersion,
                    generation = current.generation + 1,
                    repairNeeded = true,
                    resetNeeded = true,
                    pendingSends = current.pendingSends,
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
        retirements: Map<Long, Long>,
    ): List<ThreadItemDto>? {
        if (!number(version)) return null
        val merged = held.filterNot { it.id in retirements }.associateBy { it.id }.toMutableMap()
        for (item in incoming) {
            if (!valid(item, version)) return null
            if (item.id in retirements) continue
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
    private fun ordered(items: List<ThreadItemDto>): List<ThreadItemDto> = items.sortedBy(::orderKey)

    private fun orderKey(item: ThreadItemDto): Long = item.order?.takeIf { it > 0L } ?: Long.MAX_VALUE

    private fun publish(
        host: String,
        conversation: String,
        snapshot: AuthoritativeThreadSnapshot,
        restoring: Boolean = false,
    ) = publishSnapshot(host, conversation, snapshot, restoring)

    private fun publishSnapshot(
        host: String,
        conversation: String,
        snapshot: AuthoritativeThreadSnapshot,
        restoring: Boolean = false,
        preservePlacement: Boolean = false,
    ) {
        val previous = current(host, conversation)
        val sameGeneration = previous.generation == snapshot.generation && previous.epoch == snapshot.epoch
        if (!sameGeneration) {
            removalPeers.remove(
                host to conversation,
            )
        } else {
            removalPeers[host to conversation]?.keys?.retainAll(snapshot.retirements.keys)
        }
        val held = if (sameGeneration) previous.items.associateBy { it.id } else emptyMap()
        val moves = if (sameGeneration) previous.orderMoves.toMutableMap() else mutableMapOf()
        val capture = (moves.values.maxOfOrNull { it.capture } ?: 0) + 1
        val restoredOrders =
            if (restoring) {
                snapshot.items.filter { it.id !in held }.mapTo(mutableSetOf()) { it.order?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE }
            } else {
                emptySet()
            }
        snapshot.items.takeUnless { preservePlacement }.orEmpty().forEach { item ->
            val old = held[item.id]
            if (old == null && !restoring) {
                // Merge appends newly held identities, even when they already exist outside a partial restore.
                moves[item.id] = AuthoritativeOrderMove(capture, before = false)
            } else if (old != null) {
                val oldOrder = old.order?.takeIf { it > 0 } ?: Long.MAX_VALUE
                val newOrder = item.order?.takeIf { it > 0 } ?: Long.MAX_VALUE
                // Restore appends saved peers behind live facts, superseding their earlier entry/move hints.
                if (newOrder in restoredOrders || oldOrder != newOrder) {
                    moves[item.id] = AuthoritativeOrderMove(capture, before = newOrder in restoredOrders || newOrder > oldOrder)
                }
            }
        }
        val unresolved = snapshot.pendingSends.filterNot { pending -> snapshot.items.any { it.matchesPending(pending) } }
        if (unresolved.size != snapshot.pendingSends.size) RelayLog.i { "event=thread_send code=settled" }
        val certified =
            if (snapshot.retirements.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(
                    checkpoint = 0,
                    coverage = null,
                    repairNeeded = true,
                )
            }
        snapshots.value =
            snapshots.value + ((host to conversation) to certified.copy(pendingSends = unresolved, orderMoves = moves.toMap()))
    }
}
