package de.pyryco.mobile.data.cache

import androidx.room.withTransaction
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.FileImport
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The app's [ConversationCache] (#2161): conversation lists and read positions stay in [FileConversationCache]
 * under [root]; both thread families are served from Room after each exact owner's saved file is imported once.
 *
 * Import runs before any thread read or write of that owner and family. Its content and its completion marker
 * commit in one transaction, so an interrupted import leaves the file to retry and a committed one is never
 * repeated or overwritten by a leftover file. A rejected file keeps its bytes. A file that cannot be read right
 * now (IO, access) is retried, and that family's writes fail meanwhile, so a restore that saw nothing can never
 * replace offline content it did not see.
 *
 * Removal commits a durable retirement with the database deletion, before the source files go, so a failed file
 * cleanup or a restart cannot re-import. A forgotten host's thread writes are refused in this process until a
 * later conversation list is written for it, which defeats a closing repository's held or final save.
 *
 * One [Mutex] orders import decisions, saves and removal; the caching repository's `historyWrites` sits above it.
 * The app must hold one instance, like [FileConversationCache].
 */
class MigratingConversationCache internal constructor(
    root: File,
    private val database: ConversationCacheDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val beforeCommit: suspend () -> Unit,
) : ConversationCache {
    constructor(
        root: File,
        database: ConversationCacheDatabase,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(root, database, ioDispatcher, {})

    private val files = FileConversationCache(root, ioDispatcher)
    internal val legacy = LegacyRoomStorage(database, ioDispatcher, beforeCommit)
    internal val authoritative = AuthoritativeRoomStorage(database, ioDispatcher, beforeCommit)
    private val mutex = Mutex()
    private val settled = HashSet<Triple<String, String, String>>()
    private val retiredHosts = HashSet<String>()

    override suspend fun readConversations(serverId: String): List<Conversation> = files.readConversations(serverId)

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit> =
        mutex.withLock {
            files.writeConversations(serverId, conversations).onSuccess { retiredHosts -= serverId }
        }

    override suspend fun readReadPositions(serverId: String): Map<String, ReadPosition> = files.readReadPositions(serverId)

    override suspend fun writeReadPositions(
        serverId: String,
        positions: Map<String, ReadPosition>,
    ): Result<Unit> = files.writeReadPositions(serverId, positions)

    override suspend fun readThread(
        serverId: String,
        conversationId: String,
    ): List<ThreadItem> = read(serverId, conversationId, LEGACY) { legacy.readThread(serverId, conversationId) }

    override suspend fun writeThread(
        serverId: String,
        conversationId: String,
        rows: List<ThreadItem>,
    ): Result<Unit> = write(serverId, conversationId, LEGACY, "write_thread") { legacy.writeThread(serverId, conversationId, rows) }

    override suspend fun readHistoryPosition(
        serverId: String,
        conversationId: String,
    ): HistoryPosition? = read(serverId, conversationId, LEGACY) { legacy.readHistoryPosition(serverId, conversationId) }

    override suspend fun writeHistoryPosition(
        serverId: String,
        conversationId: String,
        position: HistoryPosition?,
    ): Result<Unit> =
        write(serverId, conversationId, LEGACY, "write_history") { legacy.writeHistoryPosition(serverId, conversationId, position) }

    override suspend fun readAuthoritativeThread(
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread? =
        read(serverId, conversationId, AUTHORITATIVE) { authoritative.readAuthoritativeThread(serverId, conversationId) }

    override suspend fun writeAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> =
        write(serverId, conversationId, AUTHORITATIVE, "write_authoritative") {
            authoritative.writeAuthoritativeThread(serverId, conversationId, saved)
        }

    /** A bounded legacy page, imported first so no page reader can bypass the saved file (#2068 activates readers). */
    internal suspend fun newestLegacyPage(
        serverId: String,
        conversationId: String,
        size: Int,
    ): LegacyThreadPage? = read(serverId, conversationId, LEGACY) { legacy.newestPage(serverId, conversationId, size) }

    internal suspend fun beforeLegacyPage(
        serverId: String,
        conversationId: String,
        token: LegacyThreadPage.Continuation,
        size: Int,
    ): LegacyThreadPage? = read(serverId, conversationId, LEGACY) { legacy.beforePage(serverId, conversationId, token, size) }

    override suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit> =
        mutex.withLock {
            val stored =
                commit("remove_conversation") {
                    database.removeScope(serverId, conversationId)
                    database.imports().record(FAMILIES.map { FileImport(serverId, conversationId, it, RETIRED) })
                }
            if (stored.isSuccess) FAMILIES.forEach { settled += Triple(serverId, conversationId, it) }
            // The tombstone is already durable, so a source left behind by a failed delete can never import.
            val sources = files.removeConversation(serverId, conversationId)
            stored.and(sources)
        }

    override suspend fun removeHost(serverId: String): Result<Unit> =
        mutex.withLock {
            retiredHosts += serverId
            val stored =
                commit("remove_host") {
                    database.removeScope(serverId, null)
                    database.imports().clearHost(serverId)
                    database.imports().record(listOf(FileImport(serverId, "", HOST, RETIRED)))
                }
            stored.and(files.removeHost(serverId))
        }

    private suspend fun <T> read(
        serverId: String,
        conversationId: String,
        family: String,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            ensureImported(serverId, conversationId, family)
            block()
        }

    private suspend fun write(
        serverId: String,
        conversationId: String,
        family: String,
        operation: String,
        block: suspend () -> Result<Unit>,
    ): Result<Unit> =
        mutex.withLock {
            when {
                serverId in retiredHosts -> failure(operation, "host_retired")
                !ensureImported(serverId, conversationId, family) -> failure(operation, "import_unavailable")
                else -> block()
            }
        }

    /** True once this owner's family is settled: imported, absent, rejected, superseded or retired. */
    private suspend fun ensureImported(
        serverId: String,
        conversationId: String,
        family: String,
    ): Boolean {
        val key = Triple(serverId, conversationId, family)
        if (key in settled) return true
        val recorded =
            transaction("read_import") { database.imports().settled(serverId, conversationId, family) }
                .getOrElse { return false }
        if (recorded.isNotEmpty()) {
            settled += key
            return true
        }
        val source =
            if (family == LEGACY) {
                files.legacySource(serverId, conversationId)
            } else {
                files.authoritativeSource(serverId, conversationId)
            }
        if (source is SavedSource.Unavailable) {
            logImport(family, "unavailable_${source.code}")
            return false
        }
        var attempt = importSource(serverId, conversationId, family, source)
        val readable = (source as? SavedSource.Readable<*>)?.value as? LegacySource
        if (attempt.code == INVALID_DATA && readable?.history != null) {
            // Rows stay readable without history that does not validate here, as the file reader keeps them.
            attempt = importSource(serverId, conversationId, family, SavedSource.Readable(LegacySource(readable.rows, null)))
        }
        if (attempt.code == INVALID_DATA) {
            attempt =
                commit("import") { database.imports().record(listOf(FileImport(serverId, conversationId, family, INVALID))) }
                    .map { INVALID }
        }
        val outcome = attempt.getOrElse { return false }
        settled += key
        logImport(family, outcome)
        return true
    }

    /** Commits [source]'s content with its outcome; a family that already holds database content is never overwritten. */
    private suspend fun importSource(
        serverId: String,
        conversationId: String,
        family: String,
        source: SavedSource<*>,
    ): Result<String> {
        var outcome = ABSENT
        return commit("import") {
            val holds =
                if (family == LEGACY) {
                    database.legacy().header(serverId, conversationId) != null
                } else {
                    database.authoritative().header(serverId, conversationId) != null
                }
            outcome =
                when {
                    holds -> SUPERSEDED
                    source is SavedSource.Rejected -> if (source.code == "unsupported_version") UNSUPPORTED else INVALID
                    source is SavedSource.Readable<*> -> {
                        when (val value = source.value) {
                            is LegacySource -> legacy.importThread(serverId, conversationId, value.rows, value.history)
                            is SavedAuthoritativeThread -> authoritative.importThread(serverId, conversationId, value)
                        }
                        IMPORTED
                    }
                    else -> ABSENT
                }
            database.imports().record(listOf(FileImport(serverId, conversationId, family, outcome)))
        }.map { outcome }
    }

    /** One transaction whose commit is preceded by the test hook, so a probe can hold or fail it after the writes. */
    private suspend fun commit(
        operation: String,
        block: suspend () -> Unit,
    ): Result<Unit> =
        transaction(operation) {
            block()
            beforeCommit()
        }

    private suspend fun <T> transaction(
        operation: String,
        block: suspend () -> T,
    ): Result<T> =
        withContext(ioDispatcher) {
            try {
                Result.success(database.withTransaction { block() })
            } catch (error: Exception) {
                failure(operation, failureCode(error) ?: throw error)
            }
        }

    /** Causeless and content-free; [code] is static, and [Result.code] reads it back for import decisions. */
    private fun <T> failure(
        operation: String,
        code: String,
    ): Result<T> {
        RelayLog.d { "migrating_cache operation=$operation code=$code" }
        return Result.failure(ConversationCacheException("conversation cache $operation failed: $code"))
    }

    private val Result<*>.code: String? get() = exceptionOrNull()?.message?.substringAfterLast(": ")

    private fun logImport(
        family: String,
        outcome: String,
    ) = RelayLog.d { "event=thread_cache_import family=$family outcome=$outcome" }

    private fun failureCode(error: Exception): String? =
        when {
            error is CancellationException -> null
            error is SecurityException -> "denied"
            error is IOException -> "io"
            error is IllegalArgumentException -> "invalid_data"
            error is IllegalStateException && !database.isOpen -> "io"
            generateSequence<Class<*>>(error.javaClass) { it.superclass }.any {
                it.name == "android.database.SQLException" || it.name == "androidx.sqlite.SQLiteException"
            } -> "io"
            else -> null
        }

    private companion object {
        const val LEGACY = "legacy"
        const val AUTHORITATIVE = "authoritative"
        const val HOST = "host"
        val FAMILIES = listOf(LEGACY, AUTHORITATIVE)
        const val ABSENT = "absent"
        const val IMPORTED = "imported"
        const val INVALID = "invalid"
        const val UNSUPPORTED = "unsupported"
        const val SUPERSEDED = "superseded"
        const val RETIRED = "retired"
        const val INVALID_DATA = "invalid_data"
    }
}

private fun Result<Unit>.and(other: Result<Unit>): Result<Unit> = if (isFailure) this else other
