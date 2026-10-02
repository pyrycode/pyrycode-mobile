package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem

/**
 * App-private custody of what the phone has already loaded from one host (#795).
 *
 * Everything else the phone holds is connection-scoped: a host's rows live only while a
 * `RemoteConversationRepository` is live, so a restart or an unreachable host draws that host empty.
 * This cache is the storage layer that lets previously loaded content outlive both. It is a cache,
 * never a source of truth: the daemon's list is authoritative for a host, and a read that fails is
 * indistinguishable from a host that was never written.
 *
 * Identity is exact, case-sensitive string equality on `serverId` and on [Conversation.id]. Neither
 * is normalized, validated or interpreted — a server id is daemon-supplied and opaque, so an
 * implementation must never paste one into a filesystem path or a log line.
 *
 * Values read back are the same daemon-authored text that arrived over the wire: passing through the
 * cache neither validates nor bounds them. A cached conversation name is exactly as untrusted as a
 * live one, and the render path owns its length bound and escaping either way.
 *
 * No `android.*` type appears here. `data/` is portable by project rule (root `CLAUDE.md`, § Don't);
 * only an implementation may reach for a platform storage handle.
 */
interface ConversationCache {
    /**
     * This host's cached conversation metadata, in the order it was written.
     *
     * Never throws for an expected storage failure: a host that was never written, a missing or
     * truncated document, unparseable bytes and an unsupported stored version all yield an empty
     * list. An empty result therefore **does not** mean the stored content was deleted, and a read
     * never repairs, rewrites or removes what it could not parse. Cancellation still propagates.
     */
    suspend fun readConversations(serverId: String): List<Conversation>

    /**
     * Replace this host's whole stored conversation set with [conversations], preserving their order.
     *
     * A whole-host replace rather than an upsert: the daemon's list is authoritative, so a
     * conversation it no longer reports stops being cached by the same write that stores the rest.
     * Other hosts are untouched. Fails with [ConversationCacheException] carrying an operation and a
     * static failure code; a failed write leaves the previously stored document intact.
     */
    suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ): Result<Unit>

    /**
     * The thread rows last written for [conversationId] under [serverId] (#797), in arrival order.
     *
     * Graceful like [readConversations]: anything unreadable yields an empty list, and a read never
     * repairs what it could not parse. A row read back is always settled — never streaming, never a
     * running tool — and no two rows share a thread list key: a message id, a session boundary's
     * identity, or a banner's, compaction divider's or model refusal's `(type, ts)` (#1353).
     *
     * The default stores nothing, so a double that does not exercise threads need not override it.
     */
    suspend fun readThread(
        serverId: String,
        conversationId: String,
    ): List<ThreadItem> = emptyList()

    /**
     * Replace [conversationId]'s stored thread with [cacheableThreadRows] of [rows] — never the raw
     * list, so no caller can persist an unrecognized, streaming or running row. Reports failure like
     * [writeConversations]; a failed write leaves the previous document intact.
     */
    suspend fun writeThread(
        serverId: String,
        conversationId: String,
        rows: List<ThreadItem>,
    ): Result<Unit> = Result.success(Unit)

    /**
     * This host's stored read positions (#877), keyed by conversation id. Graceful like
     * [readConversations]: anything unreadable yields an empty map, which reads as "everything read".
     *
     * The default stores nothing, so a double that does not exercise read positions need not override it.
     */
    suspend fun readReadPositions(serverId: String): Map<String, ReadPosition> = emptyMap()

    /**
     * Replace this host's whole stored read-position set with [positions]. Reports failure like
     * [writeConversations]; a failed write leaves the previous document intact.
     */
    suspend fun writeReadPositions(
        serverId: String,
        positions: Map<String, ReadPosition>,
    ): Result<Unit> = Result.success(Unit)

    /**
     * Remove every cached artefact belonging to [serverId], leaving every other host readable.
     *
     * An unknown host is a successful no-op, so a caller unpairing a host need not check first.
     */
    suspend fun removeHost(serverId: String): Result<Unit>

    /**
     * Remove every cached artefact keyed by [conversationId] under [serverId], leaving that host's
     * other conversations readable.
     *
     * That is its metadata entry, its thread rows (#797) and its read position (#877). A family added later must extend this
     * operation too, or a permanently deleted conversation would leave its content behind.
     *
     * An unknown conversation is a successful no-op.
     */
    suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit>
}

/**
 * How far the operator has read one conversation on one host (#877): a client-side mark, since the daemon
 * carries no read marker. [completedTurnId] marks the latest change that made the conversation unread: a
 * turn this phone saw complete live, or a client-minted token for a new thread row (#1361). [readTurnId]
 * is the mark the operator had seen when they last opened the conversation, or null when they have not
 * opened it since. Both are used only for equality.
 *
 * A conversation with no stored position is read.
 */
data class ReadPosition(
    val completedTurnId: String,
    val readTurnId: String?,
) {
    val unread: Boolean get() = readTurnId != completedTurnId
}

/**
 * How many of a thread's newest settled rows the cache keeps, so a long thread cannot grow without limit.
 * Desktop's saved-timeline parser caps a timeline at the same count and trims nothing below it (#1353).
 */
const val MAX_CACHED_THREAD_ROWS = 100_000

/**
 * The rows of a drawn thread the cache may hold (#797): its newest [MAX_CACHED_THREAD_ROWS] settled rows.
 *
 * Every settled row kind is kept — messages, session boundaries, banners, compaction dividers and model
 * refusals (#1353) — because history loads only when the user asks, so nothing else restores them. Drops
 * every [ThreadItem.UnrecognizedMessage] (unbounded, model-adjacent JSON its KDoc forbids persisting, and
 * this cache is plain files) and every in-flight row — a streaming message or a running tool call —
 * because those are live state: restored, they would be a permanent caret or spinner. The one definition
 * the cache enforces on write and the caching repository compares against, so the two can never disagree.
 */
fun cacheableThreadRows(rows: List<ThreadItem>): List<ThreadItem> =
    settledThreadRows(rows)
        .filterNot { it is ThreadItem.UnrecognizedMessage }
        .takeLast(MAX_CACHED_THREAD_ROWS)

/**
 * [rows] without its in-flight rows — a streaming message or a running tool call — which only a live
 * connection can settle. Unbounded and keeps unrecognized rows: it is what a thread may keep drawing
 * once its connection is gone, not what the cache may hold.
 */
fun settledThreadRows(rows: List<ThreadItem>): List<ThreadItem> =
    rows.filterNot { row ->
        row is ThreadItem.MessageItem &&
            (row.message.isStreaming || row.message.toolCall?.status == ToolCallStatus.Running)
    }

/**
 * Signals an expected storage failure from a [ConversationCache] mutation.
 *
 * Carries an operation name and a static failure code only. It deliberately takes **no cause**: a
 * serialization or IO message can embed a conversation name or cwd, and a crash reporter prints
 * causes, so attaching one would put cached content into a report. Same reasoning as
 * `PairedServerStoreException`.
 */
class ConversationCacheException(
    message: String,
) : Exception(message)
