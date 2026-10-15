package de.pyryco.mobile.data.cache

import androidx.room.withTransaction
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeHeader
import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
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
) {
    override fun toString(): String = "AuthoritativeThreadPage(items=${items.size}, redacted)"

    /** Family-specific local ordinal; never a server continuation or a portable full snapshot. */
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
    )

    internal var lastReadCounts = ReadCounts()
        private set

    suspend fun readAuthoritativeThread(
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread? =
        read("read_authoritative") {
            val header = header(serverId, conversationId) ?: return@read null
            val saved = metadata(header)
            val records = dao.items(serverId, conversationId, header.epoch, Int.MAX_VALUE, MAX_CACHED_THREAD_ROWS + 1).asReversed()
            require(records.size == header.itemCount)
            saved.copy(items = decode(records, saved, 0))
        }

    suspend fun writeAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> =
        mutate("write_authoritative") {
            val kept = saved.retained()
            val records =
                kept.toSnapshot().items.mapIndexed { ordinal, item ->
                    AuthoritativeItem(serverId, conversationId, kept.epoch, item.id, ordinal, orderKey(item), item.raw.toString())
                }
            dao.deleteItems(serverId, conversationId)
            dao.deleteHeaders(serverId, conversationId)
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
                ),
            )
        }

    suspend fun newestPage(
        serverId: String,
        conversationId: String,
        size: Int,
    ): AuthoritativeThreadPage? = page(serverId, conversationId, null, size)

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
                    token.host != host || token.conversation != conversation || token.epoch != header.epoch ||
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
            val items = decode(records, saved, first)
            val probe = dao.probe(host, conversation, header.epoch, first)
            lastReadCounts = lastReadCounts.copy(ordinalReads = probe.size)
            require(probe == if (first > 0) listOf(first - 1) else emptyList<Int>())
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
            )
        }

    private suspend fun header(
        host: String,
        conversation: String,
    ): AuthoritativeHeader? =
        dao.header(host, conversation).also { lastReadCounts = lastReadCounts.copy(evidenceReads = if (it == null) 0 else 1) }

    private fun metadata(header: AuthoritativeHeader): SavedAuthoritativeThread {
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
        ).also { it.toSnapshot() }
    }

    private fun decode(
        records: List<AuthoritativeItem>,
        saved: SavedAuthoritativeThread,
        first: Int,
    ): List<JsonObject> {
        lastReadCounts = lastReadCounts.copy(itemReads = records.size)
        val raw =
            records.map { record ->
                lastReadCounts = lastReadCounts.copy(itemDecodes = lastReadCounts.itemDecodes + 1)
                MobileJson.parseToJsonElement(record.payload).jsonObject
            }
        val decoded = saved.copy(items = raw).toSnapshot().items
        require(decoded.map { it.raw } == raw)
        records.zip(decoded).forEachIndexed { offset, (record, item) ->
            require(record.id == item.id && record.ordinal == first + offset && record.orderKey == orderKey(item))
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
                try {
                    database.withTransaction { block() }.also { log(operation, "ok") }
                } catch (error: Exception) {
                    val code = failureCode(error) ?: throw error
                    log(operation, code)
                    null
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

    private fun log(
        operation: String,
        code: String,
    ) = RelayLog.d { "authoritative_room_cache operation=$operation code=$code" }
}
