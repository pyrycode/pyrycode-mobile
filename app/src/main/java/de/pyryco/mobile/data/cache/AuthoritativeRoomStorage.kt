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
            var removed = 0
            for ((id, revision) in saved.retirements) {
                if (dao.retirement(host, conversation, id) != null) continue
                dao.item(host, conversation, saved.epoch, id)?.let { record ->
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
            for ((item, record) in changes) {
                val ordinal = assigned[item.id] ?: requireNotNull(record).ordinal
                if (record == null) added++
                dao.upsertItem(
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
                    ),
                )
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
            if (trimmed > 0) {
                dao.trimItems(host, conversation, saved.epoch, trimmed)
                lastWriteCounts = lastWriteCounts.copy(itemDeletes = lastWriteCounts.itemDeletes + trimmed)
            }
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
                )
            }
        dao.deleteItems(serverId, conversationId)
        dao.deleteHeaders(serverId, conversationId)
        dao.deleteRetirements(serverId, conversationId)
        for ((id, revision) in fences) dao.retire(AuthoritativeRetirement(serverId, conversationId, id, revision))
        dao.insertItems(records)
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
            val saved = metadata(header)
            val selected = dao.displayWindow(host, conversation, header.epoch, size).asReversed()
            val extras = dao.rendererExtras(host, conversation, header.epoch)
            lastReadCounts = lastReadCounts.copy(queries = 4)
            val records = LinkedHashMap<Long, AuthoritativeItem>()
            (selected + extras).forEach { records[it.id] = it }
            val decoded = LinkedHashMap<Long, JsonObject>()
            val pending = ArrayDeque(records.values)
            val lookedUp = HashSet(records.keys)
            var sqlRows = selected.size + extras.size
            while (pending.isNotEmpty()) {
                val record = pending.removeFirst()
                val raw = decode(listOf(record), saved).single()
                val item = ThreadItemDto.decodeReplacement(raw)
                require(record.display == item.savedDisplayRow() && record.extra == item.savedRendererExtra())
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
            lastReadCounts = lastReadCounts.copy(itemReads = sqlRows)
            RelayLog.d {
                "event=authoritative_window rows=${selected.size} extras=${records.size - selected.size} queries=${lastReadCounts.queries} decodes=${lastReadCounts.itemDecodes}"
            }
            AuthoritativeThreadWindow(host, conversation, saved.copy(items = ordered.map { decoded.getValue(it.id) }))
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
            lastReadCounts = lastReadCounts.copy(ordinalReads = probe.size)
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
        dao.header(host, conversation).also { lastReadCounts = lastReadCounts.copy(evidenceReads = if (it == null) 0 else 1) }

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
            if (includeRetirements) dao.retirements(header.host, header.conversation).associate { it.id to it.revision } else emptyMap(),
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
        }
        return raw
    }

    private fun orderKey(item: ThreadItemDto): Long = item.order?.takeIf { it > 0 } ?: Long.MAX_VALUE

    private suspend fun <T> read(
        operation: String,
        block: suspend () -> T?,
    ): T? =
        withContext(ioDispatcher) {
            mutex.withLock {
                lastReadCounts = ReadCounts()
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
