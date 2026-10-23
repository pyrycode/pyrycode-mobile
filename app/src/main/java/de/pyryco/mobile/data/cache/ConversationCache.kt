package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Conversation

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

    /** Graceful read of full saved daemon facts; never converts legacy rows. */
    suspend fun readAuthoritativeThread(
        serverId: String,
        conversationId: String,
    ): SavedAuthoritativeThread? = null

    /** Atomic full facts and certification. */
    suspend fun writeAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> = Result.success(Unit)

    /** Explicit new incarnation; ordinary replacements must preserve committed retirement fences. */
    suspend fun resetAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
    ): Result<Unit> = writeAuthoritativeThread(serverId, conversationId, saved)

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
     * [serverId] was paired, possibly again after [removeHost] (#2161). A cache that refuses a forgotten host's thread
     * writes in this process, so a closing repository's held or final save cannot recreate its content, accepts them
     * again from here. The default holds no such fence.
     */
    suspend fun admitHost(serverId: String) = Unit

    /**
     * Remove every cached artefact keyed by [conversationId] under [serverId], leaving that host's
     * other conversations readable.
     *
     * That is its metadata entry, its authoritative facts and obsolete source files, and its read
     * position (#877). A family added later must extend this operation too, or a permanently deleted conversation would leave its content behind.
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
 * How many of a thread's newest authoritative facts the cache keeps, so saved content stays bounded.
 * Desktop's saved-timeline parser caps a timeline at the same count and trims nothing below it (#1353).
 */
const val MAX_CACHED_THREAD_ROWS = 100_000

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
