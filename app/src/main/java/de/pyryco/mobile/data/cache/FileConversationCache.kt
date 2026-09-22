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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
 * `<root>/<sha256hex(serverId)>/conversations.json`. A server id is daemon-supplied and opaque, so it
 * is never pasted into a path: the directory name is the hex SHA-256 of its UTF-8 bytes, which means
 * no `/`, no `..`, no NUL and no reserved name can reach a path component, and no server id enters
 * the filesystem namespace at all. Conversation ids never touch a path; they live inside the
 * document. The hash is namespace derivation, not a security boundary — what it relies on is
 * collision resistance, so two hosts can never share a directory.
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

    override suspend fun readConversations(serverId: String): List<Conversation> =
        withContext(ioDispatcher) {
            mutex.withLock { readOrEmpty(serverId, "read") }
        }

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit> = mutate("write") { store(serverId, conversations) }

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
    ) {
        val directory = hostDirectory(serverId)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("conversation cache directory unavailable")
        }
        val document = File(directory, DOCUMENT_NAME)
        val temporary = File(directory, "$DOCUMENT_NAME.tmp")
        temporary.writeText(MobileJson.encodeToString(CachedConversations(VERSION, conversations.map { it.toRecord() })))
        Files.move(temporary.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun mutate(
        operation: String,
        block: () -> Unit,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
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

    private fun hostDirectory(serverId: String) = File(root, sha256Hex(serverId))

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOCUMENT_NAME = "conversations.json"
        const val VERSION = 1
    }
}

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
        workspaceLabel = workspaceLabel,
    )
