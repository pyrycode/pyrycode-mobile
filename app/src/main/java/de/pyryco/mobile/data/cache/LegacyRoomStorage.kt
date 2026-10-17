package de.pyryco.mobile.data.cache

import androidx.room.withTransaction
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.Evidence
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.Header
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.Row
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID

/** A bounded legacy projection. Evidence cannot certify omitted content or authoritative facts. */
class LegacyThreadPage internal constructor(
    val serverId: String,
    val conversationId: String,
    val rows: List<ThreadItem>,
    val evidence: LegacyPageEvidence?,
    val savedCursor: String?,
    val savedAtStart: Boolean,
    val continuation: Continuation?,
) {
    override fun toString(): String = "LegacyThreadPage(rows=${rows.size}, redacted)"

    /** Local held-order token, deliberately a different type from an opaque daemon cursor. */
    class Continuation internal constructor(
        internal val host: String,
        internal val conversation: String,
        internal val generation: String,
        internal val before: Int,
    ) {
        override fun toString(): String = "LegacyContinuation(redacted)"
    }
}

/** Relevant committed evidence only. These lists are a projection, never a full-cache certification. */
class LegacyPageEvidence internal constructor(
    internal val encoded: JsonObject,
) {
    private val projected = MobileJson.decodeFromJsonElement<HistoryCoverage>(encoded)
    val unsignedSpans get() = projected.unsignedSpans
    val unsignedGaps get() = projected.unsignedGaps
    val unsignedRowOrder get() = projected.unsignedRowOrder
    val unsignedRowEntries get() = projected.unsignedRowEntries
    val proofs get() = projected.proofs
    val legacyKeys get() = projected.legacyKeys
    val legacyOffsets get() = projected.legacyOffsets
    val deltaLengths get() = projected.deltaLengths
    val deltaHashes get() = projected.deltaHashes
    val unsignedCursors get() = projected.unsignedCursors
    val unsignedWalks get() = projected.unsignedWalks
    val newestCursor get() = projected.newestCursor
    val unsignedUnknown get() = projected.unsignedUnknown
    val savedHighWater = encoded["savedHighWater"]?.jsonPrimitive?.content?.toULong() ?: 0uL
    val savedUnknownEdge = encoded["savedUnknownEdge"]?.jsonPrimitive?.content?.toULong()
    val unsignedIncomplete get() = projected.unsignedIncomplete || savedHighWater > Long.MAX_VALUE.toULong()

    /** Validate held claims without requiring or certifying gaps omitted by this projection. */
    internal fun validated(rows: List<ThreadItem>): LegacyPageEvidence {
        val spans = unsignedSpans
        spans.forEachIndexed { index, span ->
            require(span.first > 0u && span.first <= span.last)
            if (index > 0) require(span.first > spans[index - 1].last && span.first - 1u != spans[index - 1].last)
        }
        require(unsignedGaps.map { it.edge }.distinct().size == unsignedGaps.size)
        unsignedGaps.forEach { gap ->
            val newer = spans.binarySearch { it.first.compareTo(gap.edge) }
            require(newer > 0 && gap.anchor in spans[newer - 1].first..spans[newer - 1].last)
        }

        fun covered(id: ULong): Boolean {
            val found = spans.binarySearch { it.first.compareTo(id) }
            val index = if (found >= 0) found else -found - 2
            return index >= 0 && id <= spans[index].last
        }
        require(unsignedRowOrder.keys == unsignedRowEntries.keys && proofs.keys == unsignedRowOrder.keys)
        require(unsignedRowOrder.all { (key, id) -> covered(id) && id in unsignedRowEntries.getValue(key) })
        require(unsignedRowEntries.values.all { ids -> ids.isNotEmpty() && ids.all(::covered) })
        require(unsignedCursors.keys.all { it > 0u } && legacyOffsets.values.all { it >= 0 } && deltaLengths.values.all { it >= 0 })
        val direct = rows.flatMap { it.historyKeys() }.toSet()
        require(legacyKeys.keys.all { it in direct || it in proofs })
        // Reuse exact row/delta hashes and legacy fragment checks; never expose a repaired claim.
        require(projected.retainedBy(rows) === projected)
        require(savedHighWater >= (spans.lastOrNull()?.last ?: 0uL))
        savedUnknownEdge?.let { require(unsignedUnknown && it > 0u && it <= savedHighWater) }
        return this
    }

    override fun toString(): String = "LegacyPageEvidence(redacted)"

    override fun equals(other: Any?): Boolean = other is LegacyPageEvidence && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()
}

/** Callable unbound backend for #2161; metadata and authoritative storage are separate families. */
class LegacyRoomStorage internal constructor(
    private val database: ConversationCacheDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: suspend () -> Unit,
) {
    constructor(database: ConversationCacheDatabase, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(database, ioDispatcher, {})

    private val dao = database.legacy()
    private val mutex = Mutex()

    internal data class ReadCounts(
        val rowReads: Int = 0,
        val rowDecodes: Int = 0,
        val ordinalReads: Int = 0,
        val evidenceReads: Int = 0,
        val evidenceDecodes: Int = 0,
    )

    internal var lastReadCounts = ReadCounts()
        private set

    suspend fun readThread(
        serverId: String,
        conversationId: String,
    ): List<ThreadItem> =
        read("read_thread", emptyList()) {
            decodeRows(dao.rows(serverId, conversationId, Int.MAX_VALUE, MAX_CACHED_THREAD_ROWS + 1).asReversed())
        }

    /** Metadata reads never decode row payloads; committed writes bind evidence to those payloads. */
    suspend fun readHistoryPosition(
        serverId: String,
        conversationId: String,
    ): HistoryPosition? =
        read("read_history", null) {
            history(dao.header(serverId, conversationId))
        }

    suspend fun writeThread(
        serverId: String,
        conversationId: String,
        rows: List<ThreadItem>,
    ): Result<Unit> =
        mutate("write_thread") {
            val kept = cacheableThreadRows(rows)
            val payloads = kept.map(::encodeLegacyRow)
            decodeLegacyRows(payloads) // Same uniqueness and settled-row validation as file reads.
            val saved = history(dao.header(serverId, conversationId))
            val trimmed = threadRowsWereTrimmed(rows)
            val retained =
                if (trimmed && saved?.coverage == null) {
                    null
                } else {
                    saved?.copy(
                        cursor = if (trimmed) "" else saved.cursor,
                        atStart = if (trimmed) false else saved.atStart,
                        coverage = saved.coverage?.retainedBy(kept),
                    )
                }
            replace(serverId, conversationId, payloads, retained, kept)
        }

    /** #2161: a file import's replacement inside the caller's transaction, so its completion marker commits with it. */
    internal suspend fun importThread(
        serverId: String,
        conversationId: String,
        rows: List<ThreadItem>,
        history: HistoryPosition?,
    ) {
        val kept = cacheableThreadRows(rows)
        val payloads = kept.map(::encodeLegacyRow)
        decodeLegacyRows(payloads)
        replace(serverId, conversationId, payloads, history, kept)
    }

    private suspend fun replace(
        serverId: String,
        conversationId: String,
        payloads: List<String>,
        history: HistoryPosition?,
        kept: List<ThreadItem>,
    ) {
        remove(serverId, conversationId)
        dao.insertRows(payloads.mapIndexed { ordinal, payload -> Row(serverId, conversationId, ordinal, payload) })
        storeHistory(serverId, conversationId, history, kept)
    }

    suspend fun writeHistoryPosition(
        serverId: String,
        conversationId: String,
        position: HistoryPosition?,
    ): Result<Unit> =
        mutate("write_history") {
            val header = dao.header(serverId, conversationId)
            if (header == null && position == null) return@mutate
            val rows = decodeRows(dao.rows(serverId, conversationId, Int.MAX_VALUE, MAX_CACHED_THREAD_ROWS + 1).asReversed())
            val bound = position?.copy(coverage = position.coverage?.retainedBy(rows)?.boundTo(rows))
            bound?.coverage?.validated(rows)
            dao.deleteHeaders(serverId, conversationId)
            dao.deleteEvidence(serverId, conversationId)
            storeHistory(serverId, conversationId, bound, rows)
        }

    suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit> = mutate("remove_conversation") { database.removeScope(serverId, conversationId) }

    suspend fun removeHost(serverId: String): Result<Unit> = mutate("remove_host") { database.removeScope(serverId, null) }

    suspend fun newestPage(
        serverId: String,
        conversationId: String,
        size: Int,
    ): LegacyThreadPage? = page(serverId, conversationId, null, size)

    suspend fun beforePage(
        serverId: String,
        conversationId: String,
        token: LegacyThreadPage.Continuation,
        size: Int,
    ): LegacyThreadPage? = page(serverId, conversationId, token, size)

    private suspend fun page(
        host: String,
        conversation: String,
        token: LegacyThreadPage.Continuation?,
        size: Int,
    ): LegacyThreadPage? =
        read("read_page", null) {
            require(size > 0)
            val header = dao.header(host, conversation)
            if (token != null &&
                (token.host != host || token.conversation != conversation || token.generation != header?.generation)
            ) {
                return@read null
            }
            val records = dao.rows(host, conversation, token?.before ?: Int.MAX_VALUE, minOf(size, MAX_CACHED_THREAD_ROWS)).asReversed()
            val rows = decodeRows(records)
            val first = records.firstOrNull()?.ordinal ?: 0
            val probe = dao.probe(host, conversation, first)
            lastReadCounts = lastReadCounts.copy(ordinalReads = probe.size)
            val evidence =
                if (header?.coverageHeader == null) {
                    null
                } else {
                    optionalHistory {
                        val scalars = decodeHeader(header.coverageHeader)
                        val selected = readEvidence(dao.rowEvidence(host, conversation, first, records.lastOrNull()?.ordinal ?: -1))
                        val entries = selected.filter { it.kind == "rowEntries" || it.kind == "rowOrder" }
                        val low =
                            entries.minOfOrNull { it.low } ?: scalars["savedUnknownEdge"]
                                ?.jsonPrimitive
                                ?.content
                                ?.toULong()
                                ?.position()
                        val high = entries.maxOfOrNull { it.high } ?: low
                        val adjacent =
                            if (low == null || high == null) {
                                emptyList()
                            } else {
                                val spans = readEvidence(dao.intervals(host, conversation, "spans", low, high))
                                val gaps = readEvidence(dao.intervals(host, conversation, "gaps", low, high))
                                val intervals = spans + gaps
                                val edges =
                                    readEvidence(dao.olderSpan(host, conversation, low)) +
                                        readEvidence(dao.newerSpan(host, conversation, high))
                                val unknownEdge =
                                    scalars["savedUnknownEdge"]
                                        ?.jsonPrimitive
                                        ?.content
                                        ?.toULong()
                                        ?.position()
                                val cursors =
                                    readEvidence(dao.cursors(host, conversation, low, high)) +
                                        readEvidence(dao.gapCursors(host, conversation, low, high)) +
                                        readEvidence(dao.gapWalks(host, conversation, low, high)) +
                                        readEvidence(dao.edgeCursor(host, conversation, low)) +
                                        if (unknownEdge ==
                                            null
                                        ) {
                                            emptyList()
                                        } else {
                                            readEvidence(dao.edgeCursor(host, conversation, unknownEdge))
                                        }
                                intervals + edges + cursors
                            }
                        val projection = LegacyPageEvidence(assemble(scalars, selected + adjacent)).validated(rows)
                        val ordinals = records.zip(rows).flatMap { (record, row) -> row.historyKeys().map { it to record.ordinal } }.toMap()
                        require(selected.all { it.ordinal == (ordinals[it.key] ?: ordinals[projection.legacyKeys[it.key]]) })
                        projection
                    }
                }
            val validHistory = header?.coverageHeader == null || evidence != null
            val continuation =
                if (probe.isEmpty() || header == null) {
                    null
                } else {
                    LegacyThreadPage.Continuation(host, conversation, header.generation, first)
                }
            LegacyThreadPage(
                host,
                conversation,
                rows,
                evidence,
                header?.cursor?.takeIf { validHistory },
                validHistory && header?.atStart == true,
                continuation,
            )
        }

    private suspend fun history(header: Header?): HistoryPosition? {
        if (header?.cursor == null) return null
        return optionalHistory {
            val coverage =
                header.coverageHeader?.let {
                    val json = assemble(decodeHeader(it), readEvidence(dao.allEvidence(header.host, header.conversation)))
                    MobileJson.decodeFromJsonElement<HistoryCoverage>(json).validated()
                }
            HistoryPosition(header.cursor, header.atStart, coverage)
        }
    }

    private suspend fun storeHistory(
        host: String,
        conversation: String,
        position: HistoryPosition?,
        rows: List<ThreadItem>,
    ) {
        val coverage = position?.coverage?.validated(rows)
        val json = coverage?.let { MobileJson.encodeToJsonElement(it).jsonObject }
        val ordinals = rows.flatMapIndexed { ordinal, row -> row.historyKeys().map { it to ordinal } }.toMap()
        val cells =
            buildList {
                json?.forEach { (kind, value) ->
                    fun cell(
                        key: String,
                        part: Int,
                        payload: JsonElement,
                        low: ULong = 0u,
                        high: ULong = low,
                    ) {
                        val alias = coverage?.legacyKeys?.get(key)
                        add(
                            Evidence(
                                host,
                                conversation,
                                kind,
                                key,
                                part,
                                ordinals[key] ?: ordinals[alias] ?: -1,
                                low.position(),
                                high.position(),
                                payload.toString(),
                            ),
                        )
                    }
                    when (value) {
                        is JsonArray ->
                            value.forEachIndexed { part, interval ->
                                val fields = interval.jsonObject
                                val low = fields[if (kind == "spans") "first" else "anchor"].unsigned()
                                val high = fields[if (kind == "spans") "last" else "edge"].unsigned()
                                cell(low.position(), part, interval, low, high)
                            }
                        is JsonObject ->
                            value.forEach { (key, payload) ->
                                when {
                                    kind == "rowEntries" ->
                                        (payload as JsonArray).forEachIndexed {
                                            part,
                                            id,
                                            ->
                                            cell(key, part, id, id.unsigned())
                                        }
                                    kind == "rowOrder" -> cell(key, 0, payload, payload.unsigned())
                                    kind == "cursors" || kind == "walks" -> cell(key, 0, payload, key.toULong())
                                    else -> cell(key, 0, payload)
                                }
                            }
                        else -> Unit // Only scalar flags and opaque cursors remain in the header.
                    }
                }
            }
        val scalars =
            json
                ?.mapValues { (_, value) ->
                    when (value) {
                        is JsonArray -> JsonArray(emptyList())
                        is JsonObject -> JsonObject(emptyMap())
                        else -> value
                    }
                }?.toMutableMap()
                ?.apply {
                    val bound = checkNotNull(coverage)
                    put("savedHighWater", JsonPrimitive(bound.unsignedHighWater.toString()))
                    bound.unsignedUnknownEdge?.let { put("savedUnknownEdge", JsonPrimitive(it.toString())) }
                }
        dao
            .insertHeader(
                Header(
                    host,
                    conversation,
                    UUID.randomUUID().toString(),
                    position?.cursor,
                    position?.atStart ?: false,
                    scalars
                        ?.let {
                            JsonObject(it).toString()
                        },
                ),
            )
        dao.insertEvidence(cells)
    }

    private fun assemble(
        header: JsonObject,
        cells: List<Evidence>,
    ): JsonObject {
        val fields = header.toMutableMap()
        cells.distinctBy { Triple(it.kind, it.key, it.part) }.groupBy { it.kind }.forEach { (kind, values) ->
            fields[kind] =
                if (kind == "spans" || kind == "gaps") {
                    JsonArray(values.sortedBy { it.low }.map(::decodeEvidence))
                } else {
                    JsonObject(
                        values.groupBy { it.key }.mapValues { (_, parts) ->
                            if (kind ==
                                "rowEntries"
                            ) {
                                JsonArray(parts.sortedBy { it.part }.map(::decodeEvidence))
                            } else {
                                decodeEvidence(parts.single())
                            }
                        },
                    )
                }
        }
        return JsonObject(fields)
    }

    private fun decodeHeader(payload: String): JsonObject {
        lastReadCounts =
            lastReadCounts.copy(evidenceReads = lastReadCounts.evidenceReads + 1, evidenceDecodes = lastReadCounts.evidenceDecodes + 1)
        return MobileJson.parseToJsonElement(payload).jsonObject
    }

    private fun readEvidence(cells: List<Evidence>): List<Evidence> {
        lastReadCounts = lastReadCounts.copy(evidenceReads = lastReadCounts.evidenceReads + cells.size)
        return cells
    }

    private fun decodeEvidence(cell: Evidence): JsonElement {
        lastReadCounts = lastReadCounts.copy(evidenceDecodes = lastReadCounts.evidenceDecodes + 1)
        val payload = MobileJson.parseToJsonElement(cell.payload)
        when (cell.kind) {
            "spans", "gaps" -> {
                val fields = payload.jsonObject
                val low = fields[if (cell.kind == "spans") "first" else "anchor"].unsigned().position()
                val high = fields[if (cell.kind == "spans") "last" else "edge"].unsigned().position()
                require(cell.low == low && cell.high == high && cell.key == low)
            }
            "rowOrder", "rowEntries" -> require(cell.low == payload.unsigned().position() && cell.high == cell.low)
            "cursors", "walks" -> require(cell.low == cell.key.toULong().position() && cell.high == cell.low)
        }
        return payload
    }

    private fun decodeRows(rows: List<Row>): List<ThreadItem> {
        lastReadCounts =
            lastReadCounts.copy(rowReads = lastReadCounts.rowReads + rows.size, rowDecodes = lastReadCounts.rowDecodes + rows.size)
        require(rows.size <= MAX_CACHED_THREAD_ROWS)
        return decodeLegacyRows(rows.map { it.payload })
    }

    private suspend fun remove(
        host: String,
        conversation: String?,
    ) {
        dao.deleteEvidence(host, conversation)
        dao.deleteRows(host, conversation)
        dao.deleteHeaders(host, conversation)
    }

    private suspend fun <T> read(
        operation: String,
        fallback: T,
        block: suspend () -> T,
    ): T =
        withContext(ioDispatcher) {
            mutex.withLock {
                lastReadCounts = ReadCounts()
                try {
                    database.withTransaction { block() }.also { log(operation, "ok") }
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    log(operation, code)
                    fallback
                }
            }
        }

    private suspend fun mutate(
        operation: String,
        block: suspend () -> Unit,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    database.withTransaction {
                        block()
                        beforeCommit()
                    }
                    log(operation, "ok")
                    Result.success(Unit)
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    log(operation, code)
                    Result.failure(ConversationCacheException("conversation cache $operation failed: $code"))
                }
            }
        }

    private suspend fun <T> optionalHistory(block: suspend () -> T): T? =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            log("read_history_metadata", "invalid_data")
            null
        }

    private fun failureCode(error: Exception): String? =
        when {
            error is CancellationException -> null
            error is SecurityException -> "denied"
            error is IOException -> "io"
            error is IllegalArgumentException -> "invalid_data"
            error is IllegalStateException && !database.isOpen -> "io"
            // Platform SQLite failures are classified without an Android dependency in the data contract.
            generateSequence<Class<*>>(error.javaClass) { it.superclass }.any {
                it.name == "android.database.SQLException" ||
                    it.name == "androidx.sqlite.SQLiteException"
            } -> "io"
            else -> null
        }

    private fun log(
        operation: String,
        code: String,
    ) = RelayLog.d { "legacy_room_cache operation=$operation code=$code" }
}

private fun ULong.position(): String = toString().padStart(20, '0')

private fun JsonElement?.unsigned(): ULong = requireNotNull(this).jsonPrimitive.content.toULong()
