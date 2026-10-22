package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
 * `<root>/<sha256hex(serverId)>/conversations.json`, and supported authoritative documents at
 * `<root>/<sha256hex(serverId)>/authoritative-threads/<sha256hex(conversationId)>.json`, and the host's read
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

    private class DecodedAuthoritative(
        val document: File,
        val serverId: String,
        val conversationId: String,
        val bytes: ByteArray,
        val saved: SavedAuthoritativeThread,
    )

    // One owner-checked decode, guarded by mutex. Every read still compares the complete current disk bytes.
    private var decodedAuthoritative: DecodedAuthoritative? = null

    override suspend fun readConversations(serverId: String): List<Conversation> =
        withContext(ioDispatcher) {
            mutex.withLock { readOrEmpty(serverId, "read") }
        }

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit> = mutate("write") { store(serverId, conversations) }

    override suspend fun readAuthoritativeThread(
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread? =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val document = authoritativeDocumentFor(serverId, conversationId)
                    val bytes = Files.readAllBytes(document.toPath())
                    val held = decodedAuthoritative
                    val saved =
                        if (held?.document == document &&
                            held.serverId == serverId &&
                            held.conversationId == conversationId &&
                            held.bytes.contentEquals(bytes)
                        ) {
                            held.saved
                        } else {
                            decodedAuthoritative = null
                            decodeAuthoritative(bytes, serverId, conversationId).also {
                                decodedAuthoritative = DecodedAuthoritative(document, serverId, conversationId, bytes, it)
                            }
                        }
                    RelayLog.d { "conversation_cache operation=read_authoritative status=ok" }
                    saved
                } catch (error: Exception) {
                    decodedAuthoritative = null
                    if (error is NoSuchFileException) return@withLock null
                    val code = failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=read_authoritative status=failed code=$code" }
                    null
                }
            }
        }

    /** #2161: the owner-checked authoritative envelope classified for import, validated as [readAuthoritativeThread] does. */
    internal suspend fun authoritativeSource(
        serverId: String,
        conversationId: String,
    ): SavedSource<SavedAuthoritativeThread> =
        source(authoritativeDocumentFor(serverId, conversationId)) { bytes ->
            decodeAuthoritative(bytes, serverId, conversationId)
        }

    /** Reads never repair: a rejected document keeps its bytes, and only IO or access failures stay retryable. */
    private suspend fun <T> source(
        document: File,
        decode: (ByteArray) -> T,
    ): SavedSource<T> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    // Boolean file predicates suppress access errors; only a missing-path exception proves absence.
                    val bytes = Files.readAllBytes(document.toPath())
                    if (MobileJson.decodeFromString<CachedVersion>(bytes.toString(Charsets.UTF_8)).version != VERSION) {
                        SavedSource.Rejected("unsupported_version")
                    } else {
                        SavedSource.Readable(decode(bytes))
                    }
                } catch (_: NoSuchFileException) {
                    SavedSource.Absent
                } catch (error: Exception) {
                    when (val code = failureCode(error) ?: throw error) {
                        "invalid_data" -> SavedSource.Rejected(code)
                        else -> SavedSource.Unavailable(code)
                    }
                }
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeAuthoritative(
        bytes: ByteArray,
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread {
        val start = System.nanoTime()
        val record = ByteArrayInputStream(bytes).use { MobileJson.decodeFromStream<CachedAuthoritativeThread>(it) }
        val parsed = System.nanoTime()
        require(record.version == VERSION && record.serverId == serverId && record.conversationId == conversationId)
        RelayLog.d { "event=authoritative_file_decode parse_ns=${parsed - start}" }
        return record.saved.ownedValidated()
    }

    override suspend fun writeAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> = replaceAuthoritative(serverId, conversationId, saved, reset = false)

    override suspend fun resetAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> = replaceAuthoritative(serverId, conversationId, saved, reset = true)

    private suspend fun replaceAuthoritative(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
        reset: Boolean,
    ): Result<Unit> =
        authoritativeWrites.withLock {
            mutate("write_authoritative") {
                val incoming = saved.toSnapshot()
                val document = authoritativeDocumentFor(serverId, conversationId)
                val previous =
                    if (reset) {
                        null
                    } else {
                        try {
                            decodeAuthoritative(Files.readAllBytes(document.toPath()), serverId, conversationId)
                        } catch (_: java.nio.file.NoSuchFileException) {
                            null
                        }
                    }
                require(previous == null || previous.incarnation == saved.incarnation)
                require(previous == null || previous.epoch == saved.epoch || previous.retirements.isEmpty())
                val held = previous?.takeIf { it.epoch == saved.epoch }
                held?.toSnapshot()?.let { durable ->
                    for (item in durable.items) {
                        val revision = saved.retirements[item.id] ?: continue
                        if (item.id !in durable.retirements && item.rev > revision) throw AuthoritativeRemovalRejected(item)
                    }
                }
                val retired = saved.retirements + held?.retirements.orEmpty()
                val kept =
                    saved
                        .copy(
                            items = incoming.items.filterNot { it.id in retired }.map { it.raw },
                            appliedVersion = maxOf(saved.appliedVersion, held?.appliedVersion ?: 0),
                            retirements = retired,
                            checkpoint = if (retired.isEmpty()) saved.checkpoint else 0,
                            coverage = saved.coverage.takeIf { retired.isEmpty() },
                        ).retained()
                writeAtomically(
                    document,
                    MobileJson.encodeToString(CachedAuthoritativeThread(VERSION, serverId, conversationId, kept)),
                )
            }
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
            val thread = obsoleteThreadDocumentFor(serverId, conversationId)
            if (!thread.delete() && thread.exists()) {
                throw IOException("conversation cache thread not removed")
            }
            val authoritative = authoritativeDocumentFor(serverId, conversationId)
            if (!authoritative.delete() && authoritative.exists()) throw IOException("authoritative cache not removed")
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
                    decodedAuthoritative = null
                    block()
                    RelayLog.d { "conversation_cache operation=$operation status=ok" }
                    Result.success(Unit)
                } catch (error: Exception) {
                    val code = if (error is AuthoritativeRemovalRejected) "stale_removal" else failureCode(error) ?: throw error
                    RelayLog.d { "conversation_cache operation=$operation status=failed code=$code" }
                    // A serialization or IO message can embed a conversation name or cwd, and a crash
                    // reporter prints causes: the code travels, the cause never does.
                    Result.failure(
                        if (error is AuthoritativeRemovalRejected) {
                            error
                        } else {
                            ConversationCacheException(
                                "conversation cache $operation failed: $code",
                            )
                        },
                    )
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

    private fun obsoleteThreadDocumentFor(
        serverId: String,
        conversationId: String,
    ) = File(File(hostDirectory(serverId), THREADS_DIRECTORY), "${sha256Hex(conversationId)}.json")

    private fun authoritativeDocumentFor(
        serverId: String,
        conversationId: String,
    ) = File(File(hostDirectory(serverId), "authoritative-threads"), "${sha256Hex(conversationId)}.json")

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val authoritativeWrites = Mutex()
        const val DOCUMENT_NAME = "conversations.json"
        const val THREADS_DIRECTORY = "threads"
        const val POSITIONS_DOCUMENT_NAME = "read-positions.json"
        const val VERSION = 1
    }
}

/** #2161: a saved file classified before import. Only [Unavailable] (IO or access) is retried; nothing here repairs. */
internal sealed interface SavedSource<out T> {
    data object Absent : SavedSource<Nothing>

    class Readable<T>(
        val value: T,
    ) : SavedSource<T> {
        override fun toString(): String = "Readable(redacted)"
    }

    /** [code] is `invalid_data` or `unsupported_version`; the rejected bytes stay on disk. */
    data class Rejected(
        val code: String,
    ) : SavedSource<Nothing>

    data class Unavailable(
        val code: String,
    ) : SavedSource<Nothing>
}

/** Only the envelope version, read first so another version is classified rather than misread as damage. */
@Serializable
private data class CachedVersion(
    val version: Int,
)

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
    val pinnedAt: String? = null,
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
        pinnedAt = pinnedAt?.toString(),
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
        pinnedAt = pinnedAt?.let(Instant::parse),
    )

/** Owner-checked envelope for supported authoritative facts and certification. */
@Serializable
private data class CachedAuthoritativeThread(
    val version: Int,
    val serverId: String,
    val conversationId: String,
    val saved: SavedAuthoritativeThread,
) {
    override fun toString(): String = "CachedAuthoritativeThread(content=***)"
}
