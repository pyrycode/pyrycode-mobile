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
     * Today that is its metadata entry, because metadata is the only family this cache stores. A
     * family added later — the thread rows of #797 — must extend this operation, or a permanently
     * deleted conversation would leave its content behind.
     *
     * An unknown conversation is a successful no-op.
     */
    suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ): Result<Unit>
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
