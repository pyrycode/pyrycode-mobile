package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeHeader
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativePosition
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeRetirement
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Selected raw facts with committed certification. Local exhaustion says nothing about the server. */
class AuthoritativeThreadPage internal constructor(
    val serverId: String,
    val conversationId: String,
    val epoch: String,
    val items: List<JsonObject>,
    val appliedVersion: Long,
    val checkpoint: Long,
    val coverage: SavedThreadCoverage?,
    val continuation: Continuation?,
    val incarnation: String = "",
    val retirements: Map<Long, Long> = emptyMap(),
) {
    override fun toString(): String = "AuthoritativeThreadPage(items=${items.size}, redacted)"

    /** Family-specific local rank; never a server continuation or a portable full snapshot. */
    class Continuation internal constructor(
        internal val host: String,
        internal val conversation: String,
        internal val epoch: String,
        internal val generation: String,
        internal val before: Int,
    ) {
        override fun toString(): String = "AuthoritativeContinuation(redacted)"
    }
}

/** Independently callable backend. Application binding/import belongs to #2161. */
class AuthoritativeRoomStorage internal constructor(
    private val database: ConversationCacheDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: suspend () -> Unit,
) {
    constructor(database: ConversationCacheDatabase, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(database, ioDispatcher, {})

    private val dao = database.authoritative()
    private val mutex = Mutex()

    internal data class ReadCounts(
        val itemReads: Int = 0,
        val itemDecodes: Int = 0,
        val ordinalReads: Int = 0,
        val evidenceReads: Int = 0,
        val evidenceDecodes: Int = 0,
        val queries: Int = 0,
    )

    internal var lastReadCounts = ReadCounts()
        private set

    internal data class WriteCounts(
        val itemReads: Int = 0,
        val itemDecodes: Int = 0,
        val inputDecodes: Int = 0,
        val itemSerializations: Int = 0,
        val itemWrites: Int = 0,
        val itemDeletes: Int = 0,
        val headerWrites: Int = 0,
        val ordinalReads: Int = 0,
        val ordinalWrites: Int = 0,
        val retirementWrites: Int = 0,
    )

    internal var lastWriteCounts = WriteCounts()
        private set
    internal var fullReads = 0
        private set
    internal var replacements = 0
        private set

    suspend fun readAuthoritativeThread(
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread? =
        read("read_authoritative") {
            fullReads++
            val header = header(serverId, conversationId) ?: return@read null
            val saved = metadata(header)
            val records = dao.items(serverId, conversationId, header.epoch, Int.MAX_VALUE, MAX_CACHED_THREAD_ROWS + 1).asReversed()
            lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
            require(records.size == header.itemCount)
            saved.copy(items = decode(records, saved))
        }

    suspend fun writeAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> = mutate("write_authoritative") { importThread(serverId, conversationId, saved) }

    /** Only changed payloads cross the codec boundary; omission never deletes a fact. */
    internal suspend fun upsertAuthoritativeThread(
        host: String,
        conversation: String,
        saved: SavedAuthoritativeThread,
        reset: Boolean,
        baseline: SavedAuthoritativeThread?,
        orderMoves: Map<Long, Boolean>,
    ): Result<Unit> =
        mutate("upsert_authoritative") {
            lastWriteCounts = WriteCounts()
            val incoming = saved.toSnapshot().items
            lastWriteCounts = lastWriteCounts.copy(inputDecodes = incoming.size)
            val previousHeader = dao.header(host, conversation)
            require(
                reset || previousHeader == null || (previousHeader.epoch == saved.epoch && previousHeader.incarnation == saved.incarnation),
            )
            val retire = reset || (previousHeader != null && previousHeader.epoch != saved.epoch)
            if (retire) {
                dao.deleteItems(host, conversation)
                dao.deleteRetirements(host, conversation)
                lastWriteCounts = lastWriteCounts.copy(itemDeletes = previousHeader?.itemCount ?: 0)
            }
            val header = previousHeader?.takeUnless { retire }
            val previous = header?.let { metadata(it, includeRetirements = false) }
            val formerParents = mutableSetOf<Long>()
            var removed = 0
            for ((id, revision) in saved.retirements) {
                if (dao.retirement(host, conversation, id) != null) continue
                dao.item(host, conversation, saved.epoch, id)?.let { record ->
                    record.parentId.takeIf { it > 0 }?.let(formerParents::add)
                    val held =
                        requireNotNull(previous)
                            .copy(items = listOf(MobileJson.parseToJsonElement(record.payload).jsonObject))
                            .toSnapshot()
                            .items
                            .single()
                    require(held.id == id && orderKey(held) == record.orderKey)
                    if (held.rev > revision) throw AuthoritativeRemovalRejected(held)
                    lastWriteCounts =
                        lastWriteCounts.copy(itemReads = lastWriteCounts.itemReads + 1, itemDecodes = lastWriteCounts.itemDecodes + 1)
                }
                removed += dao.removeItem(host, conversation, saved.epoch, id)
                dao.retire(AuthoritativeRetirement(host, conversation, id, revision))
                lastWriteCounts = lastWriteCounts.copy(retirementWrites = lastWriteCounts.retirementWrites + 1)
            }
            lastWriteCounts = lastWriteCounts.copy(itemDeletes = lastWriteCounts.itemDeletes + removed)
            val positions = mutableMapOf<Long, AuthoritativePosition>()
            val rejected = mutableSetOf<Long>()
            val changes =
                incoming.mapNotNull { item ->
                    if (dao.retirement(host, conversation, item.id) != null) {
                        rejected += item.id
                        return@mapNotNull null
                    }
                    val record = dao.item(host, conversation, saved.epoch, item.id)
                    val held =
                        record?.let {
                            lastWriteCounts =
                                lastWriteCounts.copy(
                                    itemReads = lastWriteCounts.itemReads + 1,
                                    itemDecodes = lastWriteCounts.itemDecodes + 1,
                                )
                            val raw = MobileJson.parseToJsonElement(it.payload).jsonObject
                            val decoded =
                                requireNotNull(previous)
                                    .copy(items = listOf(raw))
                                    .toSnapshot()
                                    .items
                                    .single()
                            require(it.id == decoded.id && it.orderKey == orderKey(decoded))
                            require(decoded.kind == item.kind)
                            positions[item.id] = AuthoritativePosition(it.id, it.ordinal, it.orderKey)
                            decoded
                        }
                    if (held != null && (item.rev < held.rev || (item.rev == held.rev && item.raw != held.raw))) rejected += item.id
                    if (held != null && item.rev <= held.rev) null else item to record
                }

            // Placement is independent of payload revisions. Keep accepted blocks together, including
            // unchanged live peers restored ahead of saved ties. Correct blocks need no ordinal writes.
            val moving = orderMoves.filterKeys { it !in rejected }.toMutableMap()
            changes.forEach { (item, record) ->
                if (record == null ||
                    orderKey(item) != record.orderKey
                ) {
                    moving.putIfAbsent(item.id, record != null && orderKey(item) > record.orderKey)
                }
            }
            for (id in moving.keys) {
                if (id !in positions) {
                    dao.position(host, conversation, saved.epoch, id)?.let { positions[id] = it }
                    lastWriteCounts = lastWriteCounts.copy(ordinalReads = lastWriteCounts.ordinalReads + 1)
                }
            }
            val changed = changes.associate { it.first.id to it.first }
            val groups =
                moving.entries.filter { it.key in positions || it.key in changed }.groupBy {
                    (changed[it.key]?.let(::orderKey) ?: positions.getValue(it.key).orderKey) to it.value
                }
            val assigned = mutableMapOf<Long, Int>()
            var minimum: Int? = null
            var maximum: Int? = null
            for ((placement, entries) in groups) {
                val (order, before) = placement
                val held = entries.mapNotNull { positions[it.key]?.takeIf { position -> position.orderKey == order }?.ordinal }
                if (held.size == entries.size && held.zipWithNext().all { (a, b) -> a < b }) {
                    val count = dao.tieSideCount(host, conversation, saved.epoch, order, if (before) held.last() else held.first(), before)
                    lastWriteCounts = lastWriteCounts.copy(ordinalReads = lastWriteCounts.ordinalReads + 1)
                    if (count == entries.size) continue
                }
                val edge =
                    if (before) {
                        minimum ?: (dao.minimumOrdinal(host, conversation, saved.epoch) ?: 0).also {
                            lastWriteCounts = lastWriteCounts.copy(ordinalReads = lastWriteCounts.ordinalReads + 1)
                        }
                    } else {
                        maximum ?: (dao.maximumOrdinal(host, conversation, saved.epoch) ?: -1).also {
                            lastWriteCounts = lastWriteCounts.copy(ordinalReads = lastWriteCounts.ordinalReads + 1)
                        }
                    }
                val first = if (before) edge.toLong() - entries.size else edge.toLong() + 1
                require(first >= Int.MIN_VALUE && first + entries.size - 1 <= Int.MAX_VALUE)
                entries.forEachIndexed { index, entry -> assigned[entry.key] = (first + index).toInt() }
                if (before) minimum = first.toInt() else maximum = (first + entries.size - 1).toInt()
            }
            var added = 0
            val placementChanged = mutableSetOf<Long>()
            for ((item, record) in changes) {
                val ordinal = assigned[item.id] ?: requireNotNull(record).ordinal
                if (record == null) added++
                val replacement =
                    AuthoritativeItem(
                        host,
                        conversation,
                        saved.epoch,
                        item.id,
                        ordinal,
                        orderKey(item),
                        item.raw.toString(),
                        item.savedDisplayRow(),
                        item.savedRendererExtra(),
                        parentId = item.parent ?: 0,
                        agent = item.kind == "agent",
                        background = item.savedBackgroundAgent(),
                        active = item.active,
                        endedOrder = item.endedOrder ?: 0,
                        displayKey = record?.displayKey.orEmpty(),
                        launchKey = record?.launchKey.orEmpty(),
                        rendererOrder = item.order ?: Long.MAX_VALUE,
                        anchorId = record?.anchorId ?: 0,
                        tieId = record?.tieId ?: 0,
                        anchorContribution = record?.anchorContribution.orEmpty(),
                        tieContribution = record?.tieContribution.orEmpty(),
                    )
                val newPlacement = replacement.copy(payload = "", displayKey = "", launchKey = "")
                val oldPlacement = record?.copy(payload = "", displayKey = "", launchKey = "")
                if (newPlacement != oldPlacement) {
                    placementChanged += item.id
                    record?.parentId?.takeIf { it > 0 }?.let(formerParents::add)
                }
                dao.upsertItem(replacement)
                lastWriteCounts =
                    lastWriteCounts.copy(
                        itemSerializations = lastWriteCounts.itemSerializations + 1,
                        itemWrites = lastWriteCounts.itemWrites + 1,
                    )
            }
            for ((id, ordinal) in assigned) {
                if (id in changed) continue
                dao.moveItem(host, conversation, saved.epoch, id, ordinal)
                lastWriteCounts = lastWriteCounts.copy(ordinalWrites = lastWriteCounts.ordinalWrites + 1)
            }
            val count = (header?.itemCount ?: 0) + added - removed
            val trimmed = (count - MAX_CACHED_THREAD_ROWS).coerceAtLeast(0)
            val trimmedRecords = if (trimmed > 0) dao.oldestIndexItems(host, conversation, saved.epoch, trimmed) else emptyList()
            formerParents += trimmedRecords.map { it.parentId }.filter { it > 0 }
            if (trimmed > 0) {
                dao.trimItems(host, conversation, saved.epoch, trimmed)
                lastWriteCounts = lastWriteCounts.copy(itemDeletes = lastWriteCounts.itemDeletes + trimmed)
            }
            val affectedIds = placementChanged + assigned.keys + saved.retirements.keys + trimmedRecords.map { it.id }
            updatePlacements(host, conversation, saved.epoch, affectedIds, formerParents)
            val applied = maxOf(saved.appliedVersion, previous?.appliedVersion ?: 0)
            // Completion can inherit a certificate whose required facts retention already removed.
            // Advancing metadata alone never restores those prerequisites.
            val acceptCertificate =
                previous == null ||
                    (
                        baseline != null &&
                            baseline.epoch == previous.epoch &&
                            baseline.checkpoint == previous.checkpoint &&
                            baseline.coverage == previous.coverage &&
                            saved.appliedVersion >= previous.appliedVersion &&
                            saved.checkpoint >= previous.checkpoint
                    )
            val checkpoint =
                if (trimmed > 0 || dao.hasRetirements(host, conversation)) {
                    0
                } else if (acceptCertificate) {
                    saved.checkpoint
                } else {
                    previous?.checkpoint ?: 0
                }
            val coverage =
                if (trimmed > 0 || dao.hasRetirements(host, conversation)) {
                    null
                } else if (acceptCertificate) {
                    saved.coverage
                } else {
                    previous?.coverage
                }
            dao.upsertHeader(
                AuthoritativeHeader(
                    host,
                    conversation,
                    saved.epoch,
                    UUID.randomUUID().toString(),
                    applied,
                    checkpoint,
                    coverage?.let { MobileJson.encodeToString(it) },
                    count - trimmed,
                    saved.incarnation,
                ),
            )
            lastWriteCounts = lastWriteCounts.copy(headerWrites = 1)
        }

    /** #2161: also a file import's replacement inside the caller's transaction, so its completion marker commits with it. */
    internal suspend fun importThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ) {
        replacements++
        val incoming = saved.toSnapshot()
        val header = dao.header(serverId, conversationId)
        require(header == null || header.incarnation == saved.incarnation)
        require(header == null || header.epoch == saved.epoch || !dao.hasRetirements(serverId, conversationId))
        val retired =
            if (header?.epoch ==
                saved.epoch
            ) {
                dao.retirements(serverId, conversationId).associate { it.id to it.revision }
            } else {
                emptyMap()
            }
        val previous = header?.takeIf { it.epoch == saved.epoch }?.let { metadata(it, includeRetirements = false) }
        for ((id, revision) in saved.retirements) {
            if (id in retired) continue
            dao.item(serverId, conversationId, saved.epoch, id)?.let { record ->
                val held = ThreadItemDto.decodeReplacement(decode(listOf(record), requireNotNull(previous)).single())
                if (held.rev > revision) throw AuthoritativeRemovalRejected(held)
            }
        }
        val fences = saved.retirements + retired
        val kept =
            saved
                .copy(
                    items = incoming.items.filterNot { it.id in fences }.map { it.raw },
                    appliedVersion = maxOf(saved.appliedVersion, previous?.appliedVersion ?: 0),
                    retirements = fences,
                    checkpoint = if (fences.isEmpty()) saved.checkpoint else 0,
                    coverage = saved.coverage.takeIf { fences.isEmpty() },
                ).retained()
        val records =
            kept.toSnapshot().items.mapIndexed { ordinal, item ->
                AuthoritativeItem(
                    serverId,
                    conversationId,
                    kept.epoch,
                    item.id,
                    ordinal,
                    orderKey(item),
                    item.raw.toString(),
                    item.savedDisplayRow(),
                    item.savedRendererExtra(),
                    parentId = item.parent ?: 0,
                    agent = item.kind == "agent",
                    background = item.savedBackgroundAgent(),
                    active = item.active,
                    endedOrder = item.endedOrder ?: 0,
                    rendererOrder = item.order ?: Long.MAX_VALUE,
                )
            }
        dao.deleteItems(serverId, conversationId)
        dao.deleteHeaders(serverId, conversationId)
        dao.deleteRetirements(serverId, conversationId)
        for ((id, revision) in fences) dao.retire(AuthoritativeRetirement(serverId, conversationId, id, revision))
        val placement = SavedPlacementIndex(records)
        dao.insertItems(
            records.map { record ->
                val keys = placement.keys.getValue(record.id)
                val anchor = placement.anchors[record.id]
                record.copy(
                    displayKey = keys.first,
                    launchKey = keys.second,
                    anchorId = anchor?.third?.id ?: 0,
                    tieId =
                        anchor?.second?.id ?: 0,
                    anchorContribution = placement.contributions.getValue(record.id).first,
                    tieContribution = placement.contributions.getValue(record.id).second,
                )
            },
        )
        dao.insertHeader(
            AuthoritativeHeader(
                serverId,
                conversationId,
                kept.epoch,
                UUID.randomUUID().toString(),
                kept.appliedVersion,
                kept.checkpoint,
                kept.coverage?.let { MobileJson.encodeToString(it) },
                records.size,
                kept.incarnation,
            ),
        )
    }

    /** Re-rank the affected parent components using metadata only, visiting every edge once. */
    private suspend fun updatePlacements(
        host: String,
        conversation: String,
        epoch: String,
        changed: Set<Long>,
        formerParents: Set<Long>,
    ) {
        val facts = linkedMapOf<Long, AuthoritativeItem>()
        val visited = mutableSetOf<Long>()
        val pending = ArrayDeque(changed + formerParents)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val record = facts[id] ?: dao.indexItem(host, conversation, epoch, id)?.also { facts[id] = it }
            record?.parentId?.takeIf { it > 0 }?.let(pending::addLast)
            dao.indexChildren(host, conversation, epoch, id).forEach { child ->
                facts[child.id] = child
                pending.add(child.id)
            }
        }
        val placement = SavedPlacementIndex(facts.values.sortedBy { it.ordinal })
        for (id in facts.keys) {
            val keys = placement.keys.getValue(id)
            val anchor = placement.anchors[id]
            dao.placement(host, conversation, epoch, id, keys.first, keys.second, anchor?.third?.id ?: 0, anchor?.second?.id ?: 0)
            val summary = placement.contributions.getValue(id)
            dao.contributions(host, conversation, epoch, id, summary.first, summary.second)
        }
    }

    suspend fun newestPage(
        serverId: String,
        conversationId: String,
        size: Int,
    ): AuthoritativeThreadPage? = page(serverId, conversationId, null, size)

    /** Visible authored rows plus indexed active inputs and identity-resolved parent closure. */
    internal suspend fun newestDisplayWindow(
        host: String,
        conversation: String,
        size: Int,
    ): AuthoritativeThreadWindow? =
        read("read_display_window") {
            require(size > 0)
            val header = header(host, conversation) ?: return@read null
            val saved = metadata(header, includeRetirements = false)
            val content = dao.displayWindow(host, conversation, header.epoch, size)
            val launches = dao.launchWindow(host, conversation, header.epoch, size)
            val contributions =
                (content.map { it.displayKey to it } + launches.map { it.launchKey to it })
                    .sortedBy { it.first }
                    .takeLast(size)
            val selected = contributions.map { it.second }.distinctBy { it.id }
            val extras = dao.rendererExtras(host, conversation, header.epoch)
            lastReadCounts = lastReadCounts.copy(queries = 4)
            val records = LinkedHashMap<Long, AuthoritativeItem>()
            (selected + extras).forEach { records[it.id] = it }
            val decoded = LinkedHashMap<Long, JsonObject>()
            val pending = ArrayDeque(records.values)
            val lookedUp = HashSet(records.keys)
            var sqlRows = content.size + launches.size + extras.size
            val witnessed = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val record = pending.removeFirst()
                for (witness in placementWitnesses(host, conversation, header.epoch, record, witnessed)) {
                    sqlRows++
                    if (lookedUp.add(witness.id)) {
                        records[witness.id] = witness
                        pending.addLast(witness)
                    }
                }
                val raw = decode(listOf(record), saved).single()
                val item = ThreadItemDto.decodeReplacement(raw)
                require(record.display == item.savedDisplayRow() && record.extra == item.savedRendererExtra())
                require(
                    record.parentId == (item.parent ?: 0L) &&
                        record.agent == (item.kind == "agent") &&
                        record.background == item.savedBackgroundAgent() &&
                        record.active == item.active &&
                        record.endedOrder == (item.endedOrder ?: 0L) &&
                        record.rendererOrder == (item.order ?: Long.MAX_VALUE),
                )
                decoded[item.id] = raw
                val parent = item.parent?.takeIf { it > 0 }
                if (parent != null && lookedUp.add(parent)) {
                    lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                    dao.item(host, conversation, header.epoch, parent)?.let {
                        sqlRows++
                        records[it.id] = it
                        pending.addLast(it)
                    }
                }
            }
            val ordered = records.values.sortedWith(compareBy<AuthoritativeItem> { it.orderKey }.thenBy { it.ordinal })
            val fences = relevantRetirements(host, conversation, records.keys)
            lastReadCounts = lastReadCounts.copy(itemReads = sqlRows)
            RelayLog.d {
                "event=authoritative_window rows=${selected.size} extras=${records.size - selected.size} " +
                    "queries=${lastReadCounts.queries} decodes=${lastReadCounts.itemDecodes}"
            }
            val window = saved.copy(items = ordered.map { decoded.getValue(it.id) }, retirements = fences).also { it.toSnapshot() }
            val rows = contributions.mapTo(mutableSetOf()) { (key, record) -> record.id to (key == record.launchKey) }
            extras.filter { it.active }.forEach { record ->
                if (record.display) rows += record.id to false
                if (record.launchKey.isNotEmpty()) rows += record.id to true
            }
            AuthoritativeThreadWindow(
                host,
                conversation,
                window,
                rows,
                contributions.firstOrNull()?.let { (key, record) ->
                    AuthoritativeDisplayCursor(
                        host,
                        conversation,
                        header.epoch,
                        header.incarnation,
                        header.generation,
                        key,
                        record.id,
                        key == record.launchKey,
                    )
                },
            )
        }

    /** Seek from the reader, not from newest, even after an owned save invalidated its generation. */
    internal suspend fun beforeDisplayWindow(
        host: String,
        conversation: String,
        cursor: AuthoritativeDisplayCursor,
        admitted: Set<Pair<Long, Boolean>>,
        size: Int,
    ): AuthoritativeThreadWindow? =
        read("read_older_display_window") {
            require(size in 1..200)
            val header = header(host, conversation) ?: return@read null
            if (cursor.host != host ||
                cursor.conversation != conversation ||
                cursor.epoch != header.epoch ||
                cursor.incarnation != header.incarnation
            ) {
                return@read null
            }
            var before = cursor.key
            var boundaryLaunch = cursor.launch
            if (cursor.generation != header.generation) {
                lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                val boundary = dao.indexItem(host, conversation, header.epoch, cursor.id)
                val currentKey = if (cursor.launch) boundary?.launchKey else boundary?.displayKey
                if (!currentKey.isNullOrEmpty() && !currentKey.startsWith("!")) before = maxOf(before, currentKey)
            }
            val contributions = mutableListOf<Pair<Boolean, AuthoritativeItem>>()
            while (contributions.size < size) {
                val content = dao.displayBefore(host, conversation, header.epoch, before + if (boundaryLaunch) "~" else "", size)
                val launches = dao.launchBefore(host, conversation, header.epoch, before, size)
                lastReadCounts =
                    lastReadCounts.copy(
                        queries = lastReadCounts.queries + 2,
                        ordinalReads = lastReadCounts.ordinalReads + content.size + launches.size,
                    )
                val candidates =
                    (content.map { false to it } + launches.map { true to it })
                        .sortedWith(
                            compareBy<Pair<Boolean, AuthoritativeItem>> { if (it.first) it.second.launchKey else it.second.displayKey }
                                .thenBy { it.first },
                        ).takeLast(size)
                if (candidates.isEmpty()) break
                val unseen = candidates.filter { (launch, item) -> (item.id to launch) !in admitted }
                contributions.addAll(0, unseen.takeLast(size - contributions.size))
                val oldest = candidates.first()
                before = if (oldest.first) oldest.second.launchKey else oldest.second.displayKey
                boundaryLaunch = oldest.first
            }
            val saved = metadata(header, includeRetirements = false)
            val selected = contributions.mapTo(mutableSetOf()) { it.second.id }
            val records = linkedMapOf<Long, AuthoritativeItem>()
            if (selected.isNotEmpty()) {
                dao.selectedItems(host, conversation, header.epoch, selected).forEach { records[it.id] = it }
                lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                require(records.keys == selected)
            }
            val extras = dao.rendererExtras(host, conversation, header.epoch)
            var payloadResults = selected.size + extras.size
            lastReadCounts =
                lastReadCounts.copy(
                    queries = lastReadCounts.queries + 1,
                    itemReads = selected.size + extras.size,
                )
            extras.forEach { records[it.id] = it }
            val pending = ArrayDeque(records.values)
            val lookedUp = records.keys.toMutableSet()
            val witnessed = mutableSetOf<String>()
            val decoded = linkedMapOf<Long, JsonObject>()
            while (pending.isNotEmpty()) {
                val record = pending.removeFirst()
                val raw = decode(listOf(record), saved).single()
                val item = ThreadItemDto.decodeReplacement(raw)
                require(record.display == item.savedDisplayRow() && record.extra == item.savedRendererExtra())
                require(
                    record.parentId == (item.parent ?: 0L) &&
                        record.agent == (item.kind == "agent") &&
                        record.background == item.savedBackgroundAgent() &&
                        record.active == item.active &&
                        record.endedOrder == (item.endedOrder ?: 0L) &&
                        record.rendererOrder == (item.order ?: Long.MAX_VALUE),
                )
                decoded[item.id] = raw
                val parents = placementWitnesses(host, conversation, header.epoch, record, witnessed).toMutableList()
                payloadResults += parents.size
                item.parent?.takeIf { it > 0 && lookedUp.add(it) }?.let { parent ->
                    lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                    dao.item(host, conversation, header.epoch, parent)?.let {
                        parents.add(it)
                        payloadResults++
                    }
                }
                for (parent in parents) {
                    if (parent.id !in records) {
                        lookedUp += parent.id
                        records[parent.id] = parent
                        pending.addLast(parent)
                    }
                }
            }
            val fences = relevantRetirements(host, conversation, records.keys)
            lastReadCounts = lastReadCounts.copy(itemReads = payloadResults)
            val ordered = records.values.sortedWith(compareBy<AuthoritativeItem> { it.orderKey }.thenBy { it.ordinal })
            val rows = contributions.mapTo(mutableSetOf()) { (launch, item) -> item.id to launch }
            extras.filter { it.active }.forEach { item ->
                if (item.display) rows += item.id to false
                if (item.launchKey.isNotEmpty()) rows += item.id to true
            }
            val next =
                contributions.firstOrNull()?.let { (launch, item) ->
                    AuthoritativeDisplayCursor(
                        host,
                        conversation,
                        header.epoch,
                        header.incarnation,
                        header.generation,
                        if (launch) item.launchKey else item.displayKey,
                        item.id,
                        launch,
                    )
                }
            RelayLog.d {
                "event=authoritative_local_page rows=${contributions.size} queries=${lastReadCounts.queries} decodes=${lastReadCounts.itemDecodes}"
            }
            AuthoritativeThreadWindow(
                host,
                conversation,
                saved.copy(items = ordered.map { decoded.getValue(it.id) }, retirements = fences),
                rows,
                next,
            )
        }

    /** Required winning-live parents and overlapping retirement evidence, under the same disk owner. */
    internal suspend fun parentClosure(
        host: String,
        conversation: String,
        epoch: String,
        ids: Set<Long>,
        evidence: Set<Long>,
        placementRoots: Set<Long> = emptySet(),
        structuralChanges: List<ThreadItemDto> = emptyList(),
    ): SavedAuthoritativeThread? =
        read("read_parent_closure", accumulate = true) {
            val header = header(host, conversation)?.takeIf { it.epoch == epoch } ?: return@read null
            val saved = metadata(header, includeRetirements = false)
            val records = linkedMapOf<Long, AuthoritativeItem>()
            val decoded = linkedMapOf<Long, JsonObject>()
            val pending = ArrayDeque(ids + placementRoots)
            val seen = mutableSetOf<Long>()
            val witnessed = mutableSetOf<String>()
            var resolvedChanges = false
            while (pending.isNotEmpty() || !resolvedChanges) {
                if (pending.isEmpty()) {
                    resolvedChanges = true
                    pending.addAll(winningPlacementWitnesses(host, conversation, saved, structuralChanges, records, decoded))
                    if (pending.isEmpty()) break
                }
                val id = pending.removeFirst()
                if (!seen.add(id)) continue
                lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                val record = dao.item(host, conversation, epoch, id) ?: continue
                val raw = decode(listOf(record), saved).single()
                records[id] = record
                decoded[id] = raw
                if (id in placementRoots) {
                    for (witness in setOf(record.anchorId, record.tieId).filter { it > 0 }) {
                        if (witness !in seen) pending.addLast(witness)
                    }
                }
                placementWitnesses(host, conversation, epoch, record, witnessed).forEach { witness ->
                    if (witness.id !in seen) pending.addLast(witness.id)
                }
                ThreadItemDto
                    .decodeReplacement(raw)
                    .parent
                    ?.takeIf { it > 0 }
                    ?.let(pending::addLast)
            }
            val fences = relevantRetirements(host, conversation, evidence + seen)
            val ordered = records.values.sortedWith(compareBy<AuthoritativeItem> { it.orderKey }.thenBy { it.ordinal })
            saved.copy(items = ordered.map { decoded.getValue(it.id) }, retirements = fences).also { it.toSnapshot() }
        }

    /** Reduce only changed ancestor paths; unchanged sibling subtrees supply one indexed summary. */
    private suspend fun winningPlacementWitnesses(
        host: String,
        conversation: String,
        saved: SavedAuthoritativeThread,
        changes: List<ThreadItemDto>,
        loaded: Map<Long, AuthoritativeItem>,
        decoded: Map<Long, JsonObject>,
    ): Set<Long> {
        if (changes.isEmpty()) return emptySet()
        val facts = loaded.toMutableMap()
        val affected = mutableSetOf<Long>()
        val formerChildren = mutableMapOf<Long, MutableSet<Long>>()

        suspend fun index(id: Long): AuthoritativeItem? {
            facts[id]?.let { return it }
            lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
            return dao.indexItem(host, conversation, saved.epoch, id)?.also {
                facts[id] = it
                lastReadCounts = lastReadCounts.copy(itemReads = lastReadCounts.itemReads + 1)
            }
        }

        suspend fun ancestors(start: Long) {
            var id = start
            val seen = mutableSetOf<Long>()
            while (id > 0 && seen.add(id)) {
                val item = index(id) ?: break
                affected += id
                id = item.parentId
            }
        }
        for (item in changes) {
            val stored = decoded[item.id]?.let(ThreadItemDto::decodeReplacement)
            if (stored != null && (stored.rev >= item.rev || stored.sameSavedStructure(item))) continue
            val old = index(item.id)
            old?.let { formerChildren.getOrPut(it.parentId) { mutableSetOf() }.add(it.id) }
            ancestors(old?.parentId ?: 0)
            val base = old ?: AuthoritativeItem(host, conversation, saved.epoch, item.id, Int.MAX_VALUE, orderKey(item), "")
            facts[item.id] =
                base.copy(
                    orderKey = orderKey(item),
                    rendererOrder = item.order ?: Long.MAX_VALUE,
                    display = item.savedDisplayRow(),
                    parentId = item.parent ?: 0,
                    agent = item.kind == "agent",
                    background = item.savedBackgroundAgent(),
                    active = item.active,
                    endedOrder = item.endedOrder ?: 0,
                )
            affected += item.id
            ancestors(item.parent ?: 0)
        }
        val summaries = mutableMapOf<Long, Pair<String, String>>()
        val witnesses = mutableSetOf<Long>()
        val affectedFacts = affected.map { facts.getValue(it) }
        val children = affectedFacts.groupBy { it.parentId }
        for (item in savedContributionPostorder(affectedFacts)) {
            val id = item.id
            // Exclude both saved and winning child positions; moved children cannot supply stale summaries.
            val excluded = formerChildren[id].orEmpty() + children[id].orEmpty().map { it.id }
            val anchor = dao.anchorContribution(host, conversation, saved.epoch, id, excluded)
            val tie = dao.tieContribution(host, conversation, saved.epoch, id, excluded)
            lastReadCounts =
                lastReadCounts.copy(
                    queries = lastReadCounts.queries + 2,
                    itemReads = lastReadCounts.itemReads + listOfNotNull(anchor, tie).size,
                )
            val keys = children[id].orEmpty().mapNotNull { summaries[it.id] }
            val first = (keys.map { it.first } + listOfNotNull(anchor)).filter { it.isNotEmpty() }.minOrNull().orEmpty()
            val held = (keys.map { it.second } + listOfNotNull(tie)).filter { it.isNotEmpty() }.minOrNull().orEmpty()
            if (item.agent) {
                for (key in listOf(first, held).filter { it.isNotEmpty() }) witnesses += key.substringAfterLast(':').toLong()
            }
            summaries[id] = item.savedContributions(first to held)
        }
        return witnesses
    }

    /** An indexed family key retains both its full-history anchor and the first held child tie. */
    private suspend fun placementWitnesses(
        host: String,
        conversation: String,
        epoch: String,
        record: AuthoritativeItem,
        seen: MutableSet<String>,
    ): List<AuthoritativeItem> {
        val result = mutableListOf<AuthoritativeItem>()
        for (storedKey in listOf(record.displayKey, record.launchKey)) {
            val key = storedKey.removePrefix("!")
            if (key.length < 51 || key[20] != '1') continue
            val family = key.substringBefore(':')
            if (!seen.add(family)) continue
            val anchor = key.substring(1, 20).toLong()
            val tieOrder = key.substring(21, 40).toLong()
            val ordinal = (key.substring(40, 50).toLong() + Int.MIN_VALUE).toInt()
            lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
            dao.placementWitness(host, conversation, epoch, tieOrder.takeIf { it > 0 } ?: Long.MAX_VALUE, ordinal)?.let(result::add)
            if (anchor != tieOrder) {
                lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                val content = dao.anchorWitness(host, conversation, epoch, "$family:", "$family;", anchor)
                if (content != null) {
                    result += content
                } else {
                    lastReadCounts = lastReadCounts.copy(queries = lastReadCounts.queries + 1)
                    dao.launchAnchorWitness(host, conversation, epoch, "$family:", "$family;", anchor)?.let(result::add)
                }
            }
        }
        lastReadCounts = lastReadCounts.copy(itemReads = lastReadCounts.itemReads + result.size)
        return result
    }

    private suspend fun relevantRetirements(
        host: String,
        conversation: String,
        ids: Set<Long>,
    ): Map<Long, Long> {
        val fences = linkedMapOf<Long, Long>()
        for (batch in ids.toList().chunked(800)) {
            val rows = dao.relevantRetirements(host, conversation, batch)
            lastReadCounts =
                lastReadCounts.copy(
                    queries = lastReadCounts.queries + 1,
                    evidenceReads = lastReadCounts.evidenceReads + rows.size,
                )
            rows.forEach { fences[it.id] = it.revision }
        }
        return fences
    }

    suspend fun beforePage(
        serverId: String,
        conversationId: String,
        token: AuthoritativeThreadPage.Continuation,
        size: Int,
    ): AuthoritativeThreadPage? = page(serverId, conversationId, token, size)

    suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit> = mutate("remove_conversation") { database.removeScope(serverId, conversationId) }

    suspend fun removeHost(serverId: String): Result<Unit> = mutate("remove_host") { database.removeScope(serverId, null) }

    private suspend fun page(
        host: String,
        conversation: String,
        token: AuthoritativeThreadPage.Continuation?,
        size: Int,
    ): AuthoritativeThreadPage? =
        read("read_page") {
            require(size > 0)
            val header = header(host, conversation) ?: return@read null
            if (token != null &&
                (
                    token.host != host ||
                        token.conversation != conversation ||
                        token.epoch != header.epoch ||
                        token.generation != header.generation
                )
            ) {
                return@read null
            }
            val saved = metadata(header)
            val before = token?.before ?: header.itemCount
            require(before in 0..header.itemCount)
            val count = minOf(size, before)
            val records = dao.items(host, conversation, header.epoch, before, count).asReversed()
            require(records.size == count)
            val first = before - count
            val items = decode(records, saved)
            val probe = dao.probe(host, conversation, header.epoch, first)
            lastReadCounts = lastReadCounts.copy(ordinalReads = probe.size, queries = lastReadCounts.queries + 2)
            require(probe.size == if (first > 0) 1 else 0)
            val continuation =
                if (probe.isEmpty()) {
                    null
                } else {
                    AuthoritativeThreadPage.Continuation(
                        host,
                        conversation,
                        header.epoch,
                        header.generation,
                        first,
                    )
                }
            AuthoritativeThreadPage(
                host,
                conversation,
                header.epoch,
                items,
                saved.appliedVersion,
                saved.checkpoint,
                saved.coverage,
                continuation,
                saved.incarnation,
                saved.retirements,
            )
        }

    private suspend fun header(
        host: String,
        conversation: String,
    ): AuthoritativeHeader? =
        dao.header(host, conversation).also {
            lastReadCounts =
                lastReadCounts.copy(
                    queries = lastReadCounts.queries + 1,
                    evidenceReads = lastReadCounts.evidenceReads + if (it == null) 0 else 1,
                )
        }

    private suspend fun metadata(
        header: AuthoritativeHeader,
        includeRetirements: Boolean = true,
    ): SavedAuthoritativeThread {
        require(header.itemCount in 0..MAX_CACHED_THREAD_ROWS && header.generation.isNotEmpty())
        val coverage =
            header.coverage?.let {
                lastReadCounts = lastReadCounts.copy(evidenceDecodes = lastReadCounts.evidenceDecodes + 1)
                MobileJson.decodeFromString<SavedThreadCoverage>(it)
            }
        return SavedAuthoritativeThread(
            header.epoch,
            emptyList(),
            header.appliedVersion,
            header.checkpoint,
            coverage,
            header.incarnation,
            if (includeRetirements) {
                dao
                    .retirements(header.host, header.conversation)
                    .also { rows ->
                        lastReadCounts =
                            lastReadCounts.copy(
                                queries = lastReadCounts.queries + 1,
                                evidenceReads = lastReadCounts.evidenceReads + rows.size,
                            )
                    }.associate { it.id to it.revision }
            } else {
                emptyMap()
            },
        ).also { it.toSnapshot() }
    }

    private fun decode(
        records: List<AuthoritativeItem>,
        saved: SavedAuthoritativeThread,
    ): List<JsonObject> {
        lastReadCounts = lastReadCounts.copy(itemReads = lastReadCounts.itemReads + records.size)
        val raw =
            records.map { record ->
                lastReadCounts = lastReadCounts.copy(itemDecodes = lastReadCounts.itemDecodes + 1)
                MobileJson.parseToJsonElement(record.payload).jsonObject
            }
        val decoded = saved.copy(items = raw).toSnapshot().items
        require(decoded.map { it.raw } == raw)
        require(records.map { it.ordinal }.distinct().size == records.size)
        records.zip(decoded).forEach { (record, item) ->
            require(record.id == item.id && record.orderKey == orderKey(item))
            require(record.rendererOrder == (item.order ?: Long.MAX_VALUE))
        }
        return raw
    }

    private fun orderKey(item: ThreadItemDto): Long = item.order?.takeIf { it > 0 } ?: Long.MAX_VALUE

    private suspend fun <T> read(
        operation: String,
        accumulate: Boolean = false,
        block: suspend () -> T?,
    ): T? =
        withContext(ioDispatcher) {
            mutex.withLock {
                if (!accumulate) lastReadCounts = ReadCounts()
                when (val read = database.classifiedTransaction(block = block)) {
                    is CacheTransaction.Committed -> read.value.also { log(operation, "ok") }
                    is CacheTransaction.Failed -> null.also { log(operation, read.code) }
                }
            }
        }

    private suspend fun mutate(
        operation: String,
        block: suspend () -> Unit,
    ): Result<Unit> =
        try {
            withContext(ioDispatcher) {
                mutex.withLock {
                    when (val done = database.classifiedTransaction(beforeCommit, block)) {
                        is CacheTransaction.Committed -> Result.success(Unit).also { log(operation, "ok") }
                        is CacheTransaction.Failed -> {
                            log(operation, done.code)
                            Result.failure(ConversationCacheException("conversation cache $operation failed: ${done.code}"))
                        }
                    }
                }
            }
        } catch (rejected: AuthoritativeRemovalRejected) {
            log(operation, "stale_removal")
            Result.failure(rejected)
        }

    private fun log(
        operation: String,
        code: String,
    ) = RelayLog.d { "authoritative_room_cache operation=$operation code=$code" }
}

internal fun ThreadItemDto.savedDisplayRow(): Boolean = shown && (order ?: 0) > 0

internal fun ThreadItemDto.savedRendererExtra(): Boolean =
    active || (shown && kind == "user_message" && (order ?: 0) <= 0 && status in setOf("queued", "lost"))
