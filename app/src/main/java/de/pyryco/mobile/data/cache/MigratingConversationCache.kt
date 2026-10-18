package de.pyryco.mobile.data.cache

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

/**
 * The app's [ConversationCache] (#2161): conversation lists and read positions stay in [FileConversationCache]
 * under [root]; both thread families are served from Room after each exact owner's saved file is imported once.
 *
 * Import runs before any thread read or write of that owner and family. Its content and its completion marker
 * commit in one transaction, so an interrupted import leaves the file to retry and a committed one is never
 * repeated or overwritten by a leftover file. A rejected file keeps its bytes. A file that cannot be imported right
 * now (IO, access, database) is retried, and that family's writes fail meanwhile. When its content does arrive after
 * this process already served or refused that owner without it, every caller may still hold a view that lacks it, so
 * the family's writes stay refused until restart: a restore that saw nothing can never replace offline content.
 *
 * Removal commits a durable retirement with the database deletion, before the source files go, so a failed file
 * cleanup or a restart cannot re-import. A forgotten host's thread writes are refused in this process until it is
 * paired again ([admitHost]), which defeats a closing repository's held or final save.
 *
 * One [Mutex] orders import decisions, saves and removal; the caching repository's `historyWrites` sits above it.
 * The app must hold one instance, like [FileConversationCache].
 */
internal class MigratingConversationCache(
    root: File,
    private val database: ConversationCacheDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: suspend () -> Unit = {},
) : ConversationCache,
    AuthoritativeUpsertCache,
    OwnedLegacyCoverageCache {
    private val files = FileConversationCache(root, ioDispatcher)
    private val legacy = LegacyRoomStorage(database, ioDispatcher, beforeCommit)
    private val authoritative = AuthoritativeRoomStorage(database, ioDispatcher, beforeCommit)
    private val mutex = Mutex()
    private val settled = HashSet<Triple<String, String, String>>()

    // Owners this process read or refused before their import settled: a caller may hold a view without the file.
    private val servedWithout = HashSet<Triple<String, String, String>>()

    // Owners whose file content arrived after such a view. No later write can show it saw that content.
    private val unobserved = HashSet<Triple<String, String, String>>()
    private val retiredHosts = HashSet<String>()

    /** The legacy storage's counts for the last page or thread read, for bounded-decoding probes. */
    internal val legacyReadCounts get() = legacy.lastReadCounts
    internal val legacyMutationCounts get() = legacy.lastMutationCounts

    override suspend fun mutateLegacyRows(
        host: String,
        conversation: String,
        upserts: List<OwnedLegacyRow>,
        removed: Set<String>,
    ): Result<Unit> = write(host, conversation, LEGACY, "mutate_rows") { legacy.mutateRows(host, conversation, upserts, removed) }

    override suspend fun writeOwnedLegacyCoverage(
        host: String,
        conversation: String,
        position: HistoryPosition,
        previous: HistoryPosition?,
        observed: List<ThreadItem>,
    ): Result<Unit> =
        write(host, conversation, LEGACY, "owned_history") {
            legacy.writeOwnedCoverage(host, conversation, position, previous, observed)
        }

    override suspend fun readConversations(serverId: String): List<Conversation> = files.readConversations(serverId)

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit> = files.writeConversations(serverId, conversations)

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

    override suspend fun resetAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> =
        write(serverId, conversationId, AUTHORITATIVE, "reset_authoritative") {
            authoritative.upsertAuthoritativeThread(serverId, conversationId, saved, true, null, emptyMap())
        }

    internal val authoritativeWriteCounts get() = authoritative.lastWriteCounts
    internal val authoritativeFullReads get() = authoritative.fullReads
    internal val authoritativeReplacements get() = authoritative.replacements

    override suspend fun upsertAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
        reset: Boolean,
        baseline: SavedAuthoritativeThread?,
        orderMoves: Map<Long, Boolean>,
    ): Result<Unit> =
        write(serverId, conversationId, AUTHORITATIVE, "upsert_authoritative") {
            authoritative.upsertAuthoritativeThread(serverId, conversationId, saved, reset, baseline, orderMoves)
        }

    /** Bounded pages, imported first so no page reader can bypass the saved file (#2068 activates readers). */
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

    internal suspend fun newestAuthoritativePage(
        serverId: String,
        conversationId: String,
        size: Int,
    ): AuthoritativeThreadPage? = read(serverId, conversationId, AUTHORITATIVE) { authoritative.newestPage(serverId, conversationId, size) }

    internal suspend fun beforeAuthoritativePage(
        serverId: String,
        conversationId: String,
        token: AuthoritativeThreadPage.Continuation,
        size: Int,
    ): AuthoritativeThreadPage? =
        read(serverId, conversationId, AUTHORITATIVE) { authoritative.beforePage(serverId, conversationId, token, size) }

    override suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit> =
        mutex.withLock {
            val stored =
                commit {
                    database.removeScope(serverId, conversationId)
                    database.imports().record(FAMILIES.map { FileImport(serverId, conversationId, it, RETIRED) })
                }.result("remove_conversation")
            if (stored.isSuccess) FAMILIES.forEach { settled += Triple(serverId, conversationId, it) }
            // The tombstone is already durable, so a source left behind by a failed delete can never import.
            val sources = files.removeConversation(serverId, conversationId)
            if (stored.isFailure) stored else sources
        }

    override suspend fun removeHost(serverId: String): Result<Unit> =
        mutex.withLock {
            retiredHosts += serverId
            val stored =
                commit {
                    database.removeScope(serverId, null)
                    database.imports().clearHost(serverId)
                    database.imports().record(listOf(FileImport(serverId, "", HOST, RETIRED)))
                }.result("remove_host")
            val sources = files.removeHost(serverId)
            if (stored.isFailure) stored else sources
        }

    override suspend fun admitHost(serverId: String) {
        mutex.withLock { retiredHosts -= serverId }
    }

    private suspend fun <T> read(
        serverId: String,
        conversationId: String,
        family: String,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            val key = Triple(serverId, conversationId, family)
            when (ensureImported(key)) {
                Import.Unavailable -> servedWithout += key
                Import.Arrived -> if (key in servedWithout) unobserved += key
                Import.Ready -> Unit
            }
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
            val key = Triple(serverId, conversationId, family)
            if (serverId in retiredHosts) return@withLock failure(operation, "host_retired")
            when (ensureImported(key)) {
                Import.Unavailable -> {
                    servedWithout += key
                    failure(operation, "import_unavailable")
                }
                // This write was built without the content that just arrived, so it must not replace it.
                Import.Arrived -> {
                    unobserved += key
                    failure(operation, "import_unobserved")
                }
                Import.Ready -> if (key in unobserved) failure(operation, "import_unobserved") else block()
            }
        }

    /** [Import.Arrived] when this call committed the file's content; any other settled outcome is [Import.Ready]. */
    private suspend fun ensureImported(key: Triple<String, String, String>): Import =
        try {
            importFamily(key)
        } catch (cancelled: CancellationException) {
            // Room may have committed before cancellation prevents delivery back to this caller's dispatcher.
            servedWithout += key
            throw cancelled
        }

    private suspend fun importFamily(key: Triple<String, String, String>): Import {
        if (key in settled) return Import.Ready
        val (serverId, conversationId, family) = key
        val recorded =
            when (val read = transaction { database.imports().settled(serverId, conversationId, family) }) {
                is CacheTransaction.Committed -> read.value
                is CacheTransaction.Failed -> return unavailable(family, read.code)
            }
        if (recorded.isNotEmpty()) {
            if (key in servedWithout && recorded.any { it.outcome == IMPORTED }) unobserved += key
            settled += key
            return Import.Ready
        }
        val source =
            if (family == LEGACY) {
                files.legacySource(serverId, conversationId)
            } else {
                files.authoritativeSource(serverId, conversationId)
            }
        if (source is SavedSource.Unavailable) return unavailable(family, source.code)
        var attempt = importSource(serverId, conversationId, family, source)
        val readable = (source as? SavedSource.Readable<*>)?.value as? LegacySource
        if ((attempt as? CacheTransaction.Failed)?.code == INVALID_DATA && readable?.history != null) {
            // Today's reader serves a retained history projection without validating it again; Room validates what
            // it stores. Rejected claims certify nothing, so the rows stay readable without them, as the reader keeps
            // rows beside any other invalid optional history.
            logImport(family, "history_rejected")
            attempt = importSource(serverId, conversationId, family, SavedSource.Readable(LegacySource(readable.rows, null)))
        }
        if ((attempt as? CacheTransaction.Failed)?.code == INVALID_DATA) {
            attempt = commit { INVALID.also { database.imports().record(listOf(FileImport(serverId, conversationId, family, it))) } }
        }
        val outcome =
            when (attempt) {
                is CacheTransaction.Committed -> attempt.value
                is CacheTransaction.Failed -> return unavailable(family, attempt.code)
            }
        settled += key
        logImport(family, outcome)
        return if (outcome == IMPORTED) Import.Arrived else Import.Ready
    }

    /** Commits [source]'s content with its outcome; a family that already holds database content is never overwritten. */
    private suspend fun importSource(
        serverId: String,
        conversationId: String,
        family: String,
        source: SavedSource<*>,
    ): CacheTransaction<String> =
        commit {
            val holds =
                if (family == LEGACY) {
                    database.legacy().header(serverId, conversationId) != null
                } else {
                    database.authoritative().header(serverId, conversationId) != null
                }
            val outcome =
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
            outcome
        }

    /** One transaction whose commit is preceded by the test hook, so a probe can hold or fail it after the writes. */
    private suspend fun <T> commit(block: suspend () -> T): CacheTransaction<T> =
        withContext(ioDispatcher) { database.classifiedTransaction(beforeCommit, block) }

    private suspend fun <T> transaction(block: suspend () -> T): CacheTransaction<T> =
        withContext(ioDispatcher) { database.classifiedTransaction(block = block) }

    private fun CacheTransaction<*>.result(operation: String): Result<Unit> =
        when (this) {
            is CacheTransaction.Committed -> Result.success(Unit)
            is CacheTransaction.Failed -> failure(operation, code)
        }

    /** Causeless and content-free: [code] is static. */
    private fun failure(
        operation: String,
        code: String,
    ): Result<Unit> {
        RelayLog.d { "migrating_cache operation=$operation code=$code" }
        return Result.failure(ConversationCacheException("conversation cache $operation failed: $code"))
    }

    /** Nothing is recorded, so the source stays recoverable and the next operation retries. */
    private fun unavailable(
        family: String,
        code: String,
    ): Import = Import.Unavailable.also { logImport(family, "unavailable_$code") }

    private fun logImport(
        family: String,
        outcome: String,
    ) = RelayLog.d { "event=thread_cache_import family=$family outcome=$outcome" }

    private enum class Import { Ready, Unavailable, Arrived }

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
