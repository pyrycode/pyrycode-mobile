package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [ConversationCache] backed by one JSON document per host under an app-private directory.
 *
 * ### The root is the caller's decision, and it must be `noBackupFilesDir`
 *
 * The app supplies [root]; wiring must place it under `Context.noBackupFilesDir`, never
 * `Context.filesDir`. The manifest ships `android:allowBackup="true"` with empty backup and
 * data-extraction rules, so anything under `filesDir` is carried into both cloud backup and
 * device-to-device transfer — while the pairing credentials that authorize reading this content are
 * Keystore-wrapped and do **not** transfer. A restored device would render one machine's
 * conversations to someone who never paired it and cannot reach the host. `noBackupFilesDir` is
 * excluded from both paths by definition, which makes this cache exactly as transferable as the
 * credentials it belongs to.
 *
 * Taking a [File] rather than a `Context` is the other half of that choice: the only platform
 * dependency is *who supplies the root*, so the whole contract — persistence included — is provable
 * in a plain JVM unit test instead of an instrumented one.
 *
 * ### Layout
 *
 * `<root>/<sha256hex(serverId)>/conversations.json`, and one thread document per conversation at
 * `<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json` (#797), and the host's read
 * positions at `<root>/<sha256hex(serverId)>/read-positions.json` (#877). A server id and a
 * conversation id are both daemon-supplied and opaque, so neither is pasted into a path: each path
 * component is the hex SHA-256 of the id's UTF-8 bytes, which means no `/`, no `..`, no NUL and no
 * reserved name can reach a path component, and no id enters the filesystem namespace at all. A
 * thread lives under its host's directory, so [removeHost]'s recursive delete covers it. The hash is
 * namespace derivation, not a security boundary — what it relies on is collision resistance, so two
 * hosts (or two conversations) can never share a path.
 *
 * ### Concurrency
 *
 * One [Mutex] per instance, held across each whole operation: [removeConversation] is
 * read-modify-write and would otherwise lose an update against a concurrent [writeConversations],
 * and holding it for reads too keeps one from observing a half-finished replace. The lock is per
 * *instance*, so the app must resolve a single one — the same requirement the paired-server store
 * records for its own mutations. Writes land through a temp file and an atomic move, so process
 * death mid-write leaves either the previous document or the new one, never a torn one.
 */
class FileConversationCache(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConversationCache {
    private val mutex = Mutex()

    // One immutable decode shared by the row and coverage readers, guarded by mutex.
    // Reads still inspect exact disk bytes, so another instance's same-size write cannot stay hidden.
    private data class DecodedThread(
        val document: File,
        val bytes: ByteArray,
        val rows: List<ThreadItem>,
        val history: HistoryPosition?,
    )

    private var decodedThread: DecodedThread? = null

    override suspend fun readConversations(serverId: String): List<Conversation> =
        withContext(ioDispatcher) {
            mutex.withLock { readOrEmpty(serverId, "read") }
        }

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit> = mutate("write") { store(serverId, conversations) }

    override suspend fun readThread(
        serverId: String,
        conversationId: String,
    ): List<ThreadItem> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    decodeThread(threadDocumentFor(serverId, conversationId))
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=read_thread status=failed code=$code" }
                    emptyList()
                }
            }
        }

    override suspend fun writeThread(
        serverId: String,
        conversationId: String,
        rows: List<ThreadItem>,
    ): Result<Unit> =
        mutate("write_thread") {
            val document = threadDocumentFor(serverId, conversationId)
            val kept = cacheableThreadRows(rows)
            // #1354: keep the saved history position, unless trimming moved the oldest row away from it.
            val trimmed = threadRowsWereTrimmed(rows)
            val saved = storedHistoryOrNull(document)
            val history =
                if (trimmed && saved?.coverage == null) {
                    null
                } else {
                    saved?.copy(
                        cursor = if (trimmed) "" else saved.cursor,
                        atStart = if (trimmed) false else saved.atStart,
                        coverage = saved.coverage?.retainedBy(kept),
                    )
                }
            val record = CachedThread(VERSION, kept.map { it.toRecord() }, history)
            writeAtomically(document, record)
        }

    override suspend fun readHistoryPosition(
        serverId: String,
        conversationId: String,
    ): HistoryPosition? =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    readDecodedThread(threadDocumentFor(serverId, conversationId))?.history
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=read_history status=failed code=$code" }
                    null
                }
            }
        }

    /**
     * Rewrites the thread document with [position] beside the rows currently readable in it (#1354), the
     * same read-modify-write rule as [removeConversation]: rows that cannot be read are not kept. Clearing a
     * thread that was never written writes nothing, so a clear never conjures a document.
     */
    override suspend fun writeHistoryPosition(
        serverId: String,
        conversationId: String,
        position: HistoryPosition?,
    ): Result<Unit> =
        mutate("write_history") {
            val document = threadDocumentFor(serverId, conversationId)
            if (position == null && !document.isFile) return@mutate
            val (storedRows, rows) =
                try {
                    decodeThreadDocument(document) ?: (emptyList<CachedThreadRow>() to emptyList<ThreadItem>())
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=write_history_read status=failed code=$code" }
                    emptyList<CachedThreadRow>() to emptyList<ThreadItem>()
                }
            val coverage = position?.coverage?.retainedBy(rows)?.boundTo(rows)
            val record = CachedThread(VERSION, storedRows, position?.let { CachedHistoryPosition(it.cursor, it.atStart, coverage) })
            writeAtomically(document, record)
        }

    override suspend fun readReadPositions(serverId: String): Map<String, ReadPosition> =
        withContext(ioDispatcher) {
            mutex.withLock { readPositionsOrEmpty(serverId, "read_positions") }
        }

    override suspend fun writeReadPositions(
        serverId: String,
        positions: Map<String, ReadPosition>,
    ): Result<Unit> = mutate("write_positions") { storePositions(serverId, positions) }

    override suspend fun removeHost(serverId: String): Result<Unit> =
        mutate("remove_host") {
            val directory = hostDirectory(serverId)
            if (!directory.deleteRecursively() && directory.exists()) {
                throw IOException("conversation cache host directory not removed")
            }
        }

    /**
     * Rewrites this host's document from what is currently readable, minus [conversationId].
     *
     * One rule covers all three cases. A healthy host loses exactly its target. A host whose document
     * is unreadable reads as empty, so the rewrite replaces those bytes with a valid empty document —
     * the removal still takes effect on content that could not have been isolated, which is the
     * behaviour a permanent deletion needs. A host that was never written is skipped entirely, so a
     * removal never conjures a directory for an unknown id.
     */
    override suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit> =
        mutate("remove_conversation") {
            if (documentFor(serverId).isFile) {
                store(serverId, readOrEmpty(serverId, "remove_conversation").filterNot { it.id == conversationId })
            }
            val thread = threadDocumentFor(serverId, conversationId)
            if (!thread.delete() && thread.exists()) {
                throw IOException("conversation cache thread not removed")
            }
            // #877: the same read-modify-write rule as the metadata document above.
            if (positionsDocumentFor(serverId).isFile) {
                storePositions(serverId, readPositionsOrEmpty(serverId, "remove_conversation") - conversationId)
            }
        }

    /** Decodes what is stored, or yields empty after one coded log line. Never repairs or deletes. */
    private fun readOrEmpty(
        serverId: String,
        operation: String,
    ): List<Conversation> =
        try {
            decode(documentFor(serverId))
        } catch (error: Exception) {
            val code = failureCode(error) ?: throw error
            RelayLog.d { "conversation_cache operation=$operation status=failed code=$code" }
            emptyList()
        }

    private fun decode(document: File): List<Conversation> {
        if (!document.isFile) return emptyList()
        val stored = MobileJson.decodeFromString<CachedConversations>(document.readText())
        require(stored.version == VERSION) { "unsupported conversation cache version" }
        // A duplicate id would let a later removal drop one row and leave the other drawing; reject
        // the document instead of picking a winner, as the paired-server decoder does for its ids.
        require(stored.conversations.distinctBy { it.id }.size == stored.conversations.size) {
            "duplicate conversation cache identity"
        }
        return stored.conversations.map { it.toDomain() }
    }

    private fun store(
        serverId: String,
        conversations: List<Conversation>,
    ) = writeAtomically(
        documentFor(serverId),
        MobileJson.encodeToString(CachedConversations(VERSION, conversations.map { it.toRecord() })),
    )

    private fun decodeThread(document: File): List<ThreadItem> = readDecodedThread(document)?.rows.orEmpty()

    private fun readDecodedThread(document: File): DecodedThread? {
        if (!document.isFile) {
            decodedThread = null
            return null
        }
        val started = System.nanoTime()
        decodedThread?.takeIf { it.document == document && matchesBytes(document, it.bytes) }?.let { return it }
        decodedThread = null
        val bytes = document.readBytes()
        val read = System.nanoTime()
        val record = decodeThreadRecord(bytes)
        val decoded = System.nanoTime()
        require(record.version == VERSION) { "unsupported conversation cache version" }
        val rows = validatedRows(record)
        val validated = System.nanoTime()
        val stored = validatedHistory(record.history, rows)
        val metadata = System.nanoTime()
        val history = stored?.copy(coverage = stored.coverage?.retainedBy(rows))?.toDomain()
        val retained = System.nanoTime()
        RelayLog.d {
            "event=thread_cache_restored rows=${rows.size} read_ms=${(read - started) / 1_000_000} " +
                "decode_ms=${(decoded - read) / 1_000_000} validate_ms=${(validated - decoded) / 1_000_000} " +
                "metadata_ms=${(metadata - validated) / 1_000_000} proofs_ms=${(retained - metadata) / 1_000_000}"
        }
        return DecodedThread(document, bytes, rows, history).also { decodedThread = it }
    }

    /** Read every current byte and EOF without allocating another whole-document copy. */
    private fun matchesBytes(
        document: File,
        bytes: ByteArray,
    ): Boolean =
        document.inputStream().buffered().use { reader ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var offset = 0
            var count = reader.read(buffer)
            while (count >= 0) {
                if (count > bytes.size - offset) return@use false
                for (index in 0 until count) {
                    if (buffer[index] != bytes[offset + index]) return@use false
                }
                offset += count
                count = reader.read(buffer)
            }
            offset == bytes.size
        }

    /**
     * The thread document, or `null` when none was written, rejected whole unless every row passes
     * [validatedRows], so a position is never read from a document whose rows are unreadable (#1354).
     */
    private fun decodeThreadDocument(document: File): Pair<List<CachedThreadRow>, List<ThreadItem>>? {
        if (!document.isFile) return null
        val stored = decodeThreadRecord(document.readBytes())
        require(stored.version == VERSION) { "unsupported conversation cache version" }
        val rows = validatedRows(stored)
        validatedHistory(stored.history, rows)
        return stored.rows to rows
    }

    private fun decodeThreadRecord(bytes: ByteArray): CachedThread =
        try {
            threadReadJson.decodeFromString<CachedThread>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            // Optional metadata must not participate in row decoding; legacy omitted spans remain compatible.
            val fallback = threadReadJson.decodeFromString<CachedThreadRead>(bytes.toString(Charsets.UTF_8))
            CachedThread(fallback.version, fallback.rows, decodeHistory(fallback.history))
        }

    private fun validatedHistory(
        history: CachedHistoryPosition?,
        rows: List<ThreadItem>,
    ): CachedHistoryPosition? =
        try {
            history.also { it?.coverage?.validated(rows) }
        } catch (_: IllegalArgumentException) {
            RelayLog.d { "conversation_cache operation=read_history_metadata status=failed code=invalid_data" }
            null
        }

    /**
     * Decodes a thread document's rows, rejecting what would mislead or crash the thread: a row of no kind or
     * of several, a running tool (a permanent spinner), and a repeated list key (two `LazyColumn` rows
     * with one key). A writer never produces any of these. A boundary's identity is its session pair and
     * instant, the triple its list key encodes (#775): an idle-evicted session keeps its id, so two
     * evictions legitimately share a pair. A banner and a compaction divider key on their instant, and a
     * refusal on its frame type and instant (#1353), and a stopped turn on its turn id (#1356) — the keys
     * `holdsBanner`, `holdsCompactionBoundary`, `holdsModelRefusal` and `holdsStoppedTurn` dedupe on.
     */
    private fun validatedRows(stored: CachedThread): List<ThreadItem> {
        val rows = stored.rows.map { it.toDomain() }
        val messages = HashSet<String>()
        val boundaries = HashSet<Triple<String, String, Instant>>()
        val banners = HashSet<Instant>()
        val compactions = HashSet<Instant>()
        val refusals = HashSet<Pair<Boolean, Instant>>()
        val stopped = HashSet<String>()
        rows.forEach { row ->
            when (row) {
                is ThreadItem.MessageItem -> {
                    require(row.message.toolCall?.status != ToolCallStatus.Running) { "running tool in thread cache" }
                    require(messages.add(row.message.id)) { "duplicate thread cache message identity" }
                }
                is ThreadItem.SessionBoundary ->
                    require(boundaries.add(Triple(row.previousSessionId, row.newSessionId, row.occurredAt))) {
                        "duplicate thread cache boundary identity"
                    }
                is ThreadItem.Banner -> require(banners.add(row.occurredAt)) { "duplicate thread cache banner identity" }
                is ThreadItem.CompactionBoundary ->
                    require(
                        compactions.add(row.occurredAt),
                    ) { "duplicate thread cache compaction identity" }
                is ThreadItem.ModelRefusal ->
                    require(refusals.add((row.fallbackModel != null) to row.occurredAt)) {
                        "duplicate thread cache refusal identity"
                    }
                is ThreadItem.StoppedTurn -> require(stopped.add(row.turnId)) { "duplicate thread cache stopped turn identity" }
                else -> Unit
            }
        }
        return rows
    }

    /**
     * The history position in [document], read without decoding its rows, for the row writer to keep
     * (#1354). Anything unreadable is no position: the row writer replaces the document either way.
     */
    private fun storedHistoryOrNull(document: File): CachedHistoryPosition? {
        if (!document.isFile) return null
        return try {
            val raw = threadReadJson.decodeFromString<CachedThreadHeader>(document.readText())
            raw.takeIf { it.version == VERSION }?.history?.let(::decodeHistory)
        } catch (error: Exception) {
            failureCode(error) ?: throw error
            null
        }
    }

    private fun decodeHistory(
        element: JsonElement?,
        rows: List<ThreadItem>? = null,
    ): CachedHistoryPosition? {
        if (element == null || element == JsonNull) return null
        return try {
            val raw = element as? JsonObject ?: throw IllegalArgumentException("invalid history metadata")
            val coverage = raw["coverage"]
            val compatible =
                if (coverage is JsonObject && "spans" !in coverage) {
                    JsonObject(raw + ("coverage" to JsonObject(coverage + ("spans" to JsonArray(emptyList())))))
                } else {
                    raw
                }
            threadReadJson.decodeFromJsonElement<CachedHistoryPosition>(compatible).also { it.coverage?.validated(rows) }
        } catch (error: Exception) {
            val code = failureCode(error) ?: throw error
            RelayLog.d { "conversation_cache operation=read_history_metadata status=failed code=$code" }
            null
        }
    }

    private fun readPositionsOrEmpty(
        serverId: String,
        operation: String,
    ): Map<String, ReadPosition> =
        try {
            decodePositions(positionsDocumentFor(serverId))
        } catch (error: Exception) {
            val code = failureCode(error) ?: throw error
            RelayLog.d { "conversation_cache operation=$operation status=failed code=$code" }
            emptyMap()
        }

    /** A repeated conversation id rejects the document, as [decode] does, rather than picking a winner. */
    private fun decodePositions(document: File): Map<String, ReadPosition> {
        if (!document.isFile) return emptyMap()
        val stored = MobileJson.decodeFromString<CachedReadPositions>(document.readText())
        require(stored.version == VERSION) { "unsupported conversation cache version" }
        require(stored.positions.distinctBy { it.conversationId }.size == stored.positions.size) {
            "duplicate read position identity"
        }
        return stored.positions.associate { it.conversationId to ReadPosition(it.completedTurnId, it.readTurnId) }
    }

    /** An array of entries, so a daemon-authored id is a JSON value on disk and never an object key. */
    private fun storePositions(
        serverId: String,
        positions: Map<String, ReadPosition>,
    ) = writeAtomically(
        positionsDocumentFor(serverId),
        MobileJson.encodeToString(
            CachedReadPositions(
                VERSION,
                positions.map { (id, position) -> CachedReadPosition(id, position.completedTurnId, position.readTurnId) },
            ),
        ),
    )

    private fun writeAtomically(
        document: File,
        text: String,
    ) = writeAtomically(document) { it.writeText(text) }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeAtomically(
        document: File,
        record: CachedThread,
    ) = writeAtomically(document) { temporary ->
        temporary.outputStream().buffered().use { MobileJson.encodeToStream(record, it) }
    }

    /** Temp file plus atomic move: process death mid-write leaves the previous document or the new one. */
    private fun writeAtomically(
        document: File,
        write: (File) -> Unit,
    ) {
        val directory = document.parentFile ?: throw IOException("conversation cache directory unavailable")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("conversation cache directory unavailable")
        }
        val temporary = File(directory, "${document.name}.tmp")
        write(temporary)
        Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun mutate(
        operation: String,
        block: () -> Unit,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    decodedThread = null
                    block()
                    RelayLog.d { "conversation_cache operation=$operation status=ok" }
                    Result.success(Unit)
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=$operation status=failed code=$code" }
                    // A serialization or IO message can embed a conversation name or cwd, and a crash
                    // reporter prints causes: the code travels, the cause never does.
                    Result.failure(ConversationCacheException("conversation cache $operation failed: $code"))
                }
            }
        }

    // Cancellation and unrelated programming errors are deliberately not classified.
    private fun failureCode(error: Exception): String? =
        when (error) {
            is SecurityException -> "denied"
            is IOException -> "io"
            // Serialization failures and a malformed stored timestamp are both IllegalArgumentException.
            is IllegalArgumentException -> "invalid_data"
            else -> null
        }

    private fun documentFor(serverId: String) = File(hostDirectory(serverId), DOCUMENT_NAME)

    private fun positionsDocumentFor(serverId: String) = File(hostDirectory(serverId), POSITIONS_DOCUMENT_NAME)

    private fun hostDirectory(serverId: String) = File(root, sha256Hex(serverId))

    private fun threadDocumentFor(
        serverId: String,
        conversationId: String,
    ) = File(File(hostDirectory(serverId), THREADS_DIRECTORY), "${sha256Hex(conversationId)}.json")

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOCUMENT_NAME = "conversations.json"
        const val THREADS_DIRECTORY = "threads"
        const val POSITIONS_DOCUMENT_NAME = "read-positions.json"
        const val VERSION = 1
    }
}

/** The read positions of one host (#877), versioned like [CachedConversations]. */
@Serializable
private data class CachedReadPositions(
    val version: Int,
    val positions: List<CachedReadPosition>,
)

@Serializable
private data class CachedReadPosition(
    val conversationId: String,
    val completedTurnId: String,
    val readTurnId: String? = null,
)

/** Versioned envelope, so a future shape change is rejected as unreadable rather than misread. */
@Serializable
private data class CachedConversations(
    val version: Int,
    val conversations: List<CachedConversation>,
)

/**
 * The cache's own record of [Conversation].
 *
 * The domain model is deliberately free of persistence concerns (ADR 0001 and the portability rule),
 * so the mapping lives here instead of as annotations on it. Every nullable field carries a `null`
 * default because [MobileJson]'s `explicitNulls = false` omits nulls on encode; its
 * `ignoreUnknownKeys` lets a downgraded app read a newer document's extra keys rather than discarding
 * the host's whole list. The type is `private` to this file, so no cached value can reach a log
 * through a stray `toString`.
 */
@Serializable
private data class CachedConversation(
    val id: String,
    val cwd: String,
    val currentSessionId: String,
    /** ISO-8601, not epoch millis: `Instant` round-trips exactly to the nanosecond through its text. */
    val lastUsedAt: String,
    val isPromoted: Boolean,
    val name: String? = null,
    val sessionHistory: List<String> = emptyList(),
    val isSleeping: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val workspaceLabel: String? = null,
)

private fun Conversation.toRecord() =
    CachedConversation(
        id = id,
        cwd = cwd,
        currentSessionId = currentSessionId,
        lastUsedAt = lastUsedAt.toString(),
        isPromoted = isPromoted,
        name = name,
        sessionHistory = sessionHistory,
        isSleeping = isSleeping,
        archived = archived,
        muted = muted,
        workspaceLabel = workspaceLabel,
    )

private fun CachedConversation.toDomain() =
    Conversation(
        id = id,
        name = name,
        cwd = cwd,
        currentSessionId = currentSessionId,
        sessionHistory = sessionHistory,
        isPromoted = isPromoted,
        lastUsedAt = Instant.parse(lastUsedAt),
        isSleeping = isSleeping,
        archived = archived,
        muted = muted,
        workspaceLabel = workspaceLabel,
    )

/**
 * Versioned envelope for one conversation's settled thread rows (#797), newest last, and the history
 * position received for them (#1354). [history] defaults to `null`, so a document written before positions
 * were kept reads as rows with no position, and the row writer never adds one.
 */
@Serializable
private data class CachedThread(
    val version: Int,
    val rows: List<CachedThreadRow>,
    val history: CachedHistoryPosition? = null,
)

// Every nullable thread field has a default, so decoding needs no implicit-null tracking.
// Keep encoding on MobileJson: its omitted nulls define the persisted bytes and row proofs.
private val threadReadJson = Json(MobileJson) { explicitNulls = true }

/** Read rows directly while leaving malformed optional metadata independent of row readability. */
@Serializable
private data class CachedThreadRead(
    val version: Int,
    val rows: List<CachedThreadRow>,
    val history: JsonElement? = null,
)

/** [CachedThread] without its rows: the row writer reads only the position it keeps (#1354). */
@Serializable
private data class CachedThreadHeader(
    val version: Int,
    val history: JsonElement? = null,
)

/** A [HistoryPosition] (#1354). [cursor] is opaque and stays out of [toString]. */
@Serializable
private data class CachedHistoryPosition(
    val cursor: String,
    val atStart: Boolean,
    val coverage: HistoryCoverage? = null,
) {
    fun toDomain() = HistoryPosition(cursor, atStart, coverage)

    override fun toString(): String = "CachedHistoryPosition(atStart=$atStart)"
}

/**
 * Exactly one field set; a row with none or several is unreadable. Every field defaults to `null`, so a
 * document written before banners, compaction dividers and refusals were kept (#1353), or before stopped
 * turns were (#1356), still reads.
 */
@Serializable
private data class CachedThreadRow(
    val message: CachedMessage? = null,
    val boundary: CachedBoundary? = null,
    val banner: CachedBanner? = null,
    val compaction: CachedCompaction? = null,
    val refusal: CachedRefusal? = null,
    val stopped: CachedStoppedTurn? = null,
)

/** A settled [Message]: there is no `isStreaming`, because an in-flight row is never written. */
@Serializable
private data class CachedMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: String,
    val tool: CachedToolCall? = null,
    /** #983. Defaulted, so a document written before references existed reads back with none. */
    val attachments: List<CachedAttachment> = emptyList(),
    /** #1350. Defaulted, so a row written before segments existed reads back with none. */
    val segment: CachedSegment? = null,
    /** Local ordinary-row identity survives emitted collision aliases and restart. */
    val reconciliationId: String? = null,
)

/**
 * An [AssistantSegment]'s record (#1350): the turn id and, per folded delta in order, its `seq` and text
 * length. Two parallel lists rather than one object per delta, to keep a long reply's record small.
 */
@Serializable
private data class CachedSegment(
    val turnId: String,
    val seqs: List<Int>,
    val lengths: List<Int>,
)

/** One [MessageAttachment]: `null` hints are omitted on encode (`explicitNulls = false`) and read back as `null`. */
@Serializable
private data class CachedAttachment(
    val attachmentId: String,
    val displayName: String? = null,
    val mimeType: String? = null,
) {
    override fun toString(): String = "CachedAttachment(attachmentId=$attachmentId)"
}

/** [inputFields] (#1575) defaults to empty, so a tool record written before fields were kept reads back with none. */
@Serializable
private data class CachedToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus,
    val inputFields: Map<String, String> = emptyMap(),
)

@Serializable
private data class CachedBoundary(
    val previousSessionId: String,
    val newSessionId: String,
    val reason: BoundaryReason,
    val occurredAt: String,
    val workspaceCwd: String? = null,
)

/** A [ThreadItem.Banner] (#1353). [text] is claude-authored, stored as the row holds it and rendered inert on restore. */
@Serializable
private data class CachedBanner(
    val level: BannerLevel,
    val text: String,
    val truncated: Boolean,
    val occurredAt: String,
)

/**
 * A [ThreadItem.CompactionBoundary] (#1353): a `null` token count is omitted on encode and read back as `null`.
 * [failed] (#1358) defaults to `false`, so a divider saved before failures were kept reads as not failed.
 */
@Serializable
private data class CachedCompaction(
    val preTokens: Long? = null,
    val postTokens: Long? = null,
    val manual: Boolean,
    val occurredAt: String,
    val failed: Boolean = false,
)

/** A [ThreadItem.ModelRefusal] (#1353). Model names and [banner] are claude-authored, stored as the row holds them. */
@Serializable
private data class CachedRefusal(
    val originalModel: String,
    val fallbackModel: String? = null,
    val banner: String,
    val bannerTruncated: Boolean,
    val occurredAt: String,
)

/**
 * A [ThreadItem.StoppedTurn] (#1356). [reason] and [category] are agent-authored, stored as the row holds them
 * and sanitized again when a restored row renders.
 */
@Serializable
private data class CachedStoppedTurn(
    val turnId: String,
    val reason: String,
    val category: String,
    val occurredAt: String,
)

// Only settled rows reach here: `cacheableThreadRows` has already dropped in-flight and unrecognized ones.
internal fun cachedThreadRowProof(row: ThreadItem): String = cachedThreadRowProof(row, MessageDigest.getInstance("SHA-256"))

/** The caller owns this digest for one synchronous batch; digest() resets it between rows. */
internal fun cachedThreadRowProof(
    row: ThreadItem,
    digest: MessageDigest,
): String = digest.digest(MobileJson.encodeToString(row.toRecord()).toByteArray(Charsets.UTF_8)).toHexString()

private fun ThreadItem.toRecord(): CachedThreadRow =
    when (this) {
        is ThreadItem.MessageItem ->
            CachedThreadRow(
                message =
                    CachedMessage(
                        id = message.id,
                        reconciliationId = message.reconciliationId,
                        sessionId = message.sessionId,
                        role = message.role,
                        content = message.content,
                        timestamp = message.timestamp.toString(),
                        tool = message.toolCall?.let { CachedToolCall(it.toolName, it.input, it.output, it.status, it.inputFields) },
                        attachments = message.attachments.map { CachedAttachment(it.attachmentId, it.displayName, it.mimeType) },
                        segment =
                            message.segment?.let { segment ->
                                CachedSegment(segment.turnId, segment.deltas.map { it.seq }, segment.deltas.map { it.length })
                            },
                    ),
            )
        is ThreadItem.SessionBoundary ->
            CachedThreadRow(
                boundary = CachedBoundary(previousSessionId, newSessionId, reason, occurredAt.toString(), workspaceCwd),
            )
        is ThreadItem.Banner -> CachedThreadRow(banner = CachedBanner(level, text, truncated, occurredAt.toString()))
        is ThreadItem.CompactionBoundary ->
            CachedThreadRow(compaction = CachedCompaction(preTokens, postTokens, manual, occurredAt.toString(), failed))
        is ThreadItem.ModelRefusal ->
            CachedThreadRow(
                refusal = CachedRefusal(originalModel, fallbackModel, banner, bannerTruncated, occurredAt.toString()),
            )
        is ThreadItem.StoppedTurn -> CachedThreadRow(stopped = CachedStoppedTurn(turnId, reason, category, occurredAt.toString()))
        is ThreadItem.BackgroundTaskLifecycle -> throw IllegalStateException("lifecycle evidence is never cached")
        is ThreadItem.UnrecognizedMessage -> throw IllegalStateException("unrecognized rows are never cached")
    }

private fun CachedThreadRow.toDomain(): ThreadItem {
    var kinds = 0
    if (message != null) kinds++
    if (boundary != null) kinds++
    if (banner != null) kinds++
    if (compaction != null) kinds++
    if (refusal != null) kinds++
    if (stopped != null) kinds++
    require(kinds == 1) { "thread cache row must be one kind" }
    if (message != null) {
        return ThreadItem.MessageItem(
            Message(
                id = message.id,
                reconciliationId = message.reconciliationId,
                sessionId = message.sessionId,
                role = message.role,
                content = message.content,
                timestamp = parseThreadInstant(message.timestamp),
                isStreaming = false,
                toolCall = message.tool?.let { ToolCall(it.toolName, it.input, it.output, it.status, it.inputFields) },
                attachments = message.attachments.map { MessageAttachment(it.attachmentId, it.displayName, it.mimeType) },
                segment = message.segment?.toDomain(message.content),
            ),
        )
    }
    boundary?.let {
        return ThreadItem.SessionBoundary(
            it.previousSessionId,
            it.newSessionId,
            it.reason,
            parseThreadInstant(it.occurredAt),
            it.workspaceCwd,
        )
    }
    banner?.let { return ThreadItem.Banner(it.level, it.text, it.truncated, parseThreadInstant(it.occurredAt)) }
    compaction?.let {
        return ThreadItem.CompactionBoundary(
            it.preTokens,
            it.postTokens,
            it.manual,
            parseThreadInstant(it.occurredAt),
            it.failed,
        )
    }
    stopped?.let { return ThreadItem.StoppedTurn(it.turnId, it.reason, it.category, parseThreadInstant(it.occurredAt)) }
    val refusal = checkNotNull(refusal)
    return ThreadItem.ModelRefusal(
        refusal.originalModel,
        refusal.fallbackModel,
        refusal.banner,
        refusal.bannerTruncated,
        parseThreadInstant(refusal.occurredAt),
    )
}

/** Whole-second UTC records need no general format-parser state; other forms retain the legacy parser. */
private fun parseThreadInstant(text: String): Instant {
    if (text.length == 20 &&
        text[4] == '-' &&
        text[7] == '-' &&
        text[10] == 'T' &&
        text[13] == ':' &&
        text[16] == ':' &&
        text[19] == 'Z'
    ) {
        fun number(
            start: Int,
            length: Int = 2,
        ): Int {
            var value = 0
            for (index in start until start + length) {
                val digit = text[index] - '0'
                if (digit !in 0..9) return -1
                value = value * 10 + digit
            }
            return value
        }
        val year = number(0, 4)
        if (year >= 0) {
            try {
                val date = LocalDateTime.of(year, number(5), number(8), number(11), number(14), number(17))
                return Instant.fromEpochSeconds(date.toEpochSecond(ZoneOffset.UTC))
            } catch (_: DateTimeException) {
                // The original parser owns rejection, including leap seconds and 24:00:00.
            }
        }
    }
    return Instant.parse(text)
}

/**
 * The [AssistantSegment] this record describes, or `null` when it does not describe [content] (#1350):
 * empty, of unequal lists, with a `seq` that does not rise or a negative length, or with lengths that do not
 * sum to the content's length. A bad record costs the row its join with a newer half, never the document.
 */
private fun CachedSegment.toDomain(content: String): AssistantSegment? {
    if (seqs.isEmpty() || seqs.size != lengths.size) return null
    if (seqs.zipWithNext().any { (a, b) -> b <= a } || lengths.any { it < 0 }) return null
    if (lengths.sumOf { it.toLong() } != content.length.toLong()) return null
    return AssistantSegment(turnId, seqs.zip(lengths) { seq, length -> SegmentDelta(seq, length) })
}
