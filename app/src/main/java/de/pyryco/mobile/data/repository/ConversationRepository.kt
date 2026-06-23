package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant

/**
 * Phase 1 data-layer contract. The fake (Phase 1) and Ktor-backed remote
 * (Phase 4) implementations both satisfy this surface.
 *
 * Stream-shaped reads are cold [Flow]s — collectors receive the current
 * value on subscription and every subsequent change. Mutating operations
 * are `suspend` one-shots that return the affected entity so callers do
 * not need to re-fetch; the affected stream(s) will also re-emit.
 */
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>

    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>

    /**
     * Emits the most-recent [Message] (by [Message.timestamp]) for the
     * conversation, or `null` if the conversation has no messages or is
     * unknown. Cold flow, re-emits on every state change.
     */
    fun observeLastMessage(conversationId: String): Flow<Message?>

    /**
     * Emits whether [conversationId] is currently stalled — its remote claude has
     * stopped making forward progress (PTY quiet while not idle, no JSONL progress;
     * typically a screen-parser break). `true` on stall onset, `false` once the wire
     * signals recovery (the next forward-progress event). Cold flow; re-emits on every
     * change. The thread layer observes this to react to a stall (#396).
     *
     * Default `flowOf(false)` — implementations without an interactive wire (the fake,
     * inline test doubles) inherit "never stalled" and need no override, the same
     * cascade-avoidance as [delete] / [requestScreenSnapshot] / [recentWorkspaces].
     */
    fun observeStall(conversationId: String): Flow<Boolean> = flowOf(false)

    /**
     * Emits [conversationId]'s ordered queued-message backlog (FIFO) — the messages waiting while
     * claude is busy (#460). Each `queue_state` snapshot the daemon broadcasts replaces the backlog in
     * full; the flow re-emits the new ordered list. Empty until the first snapshot lands. Cold flow;
     * re-emits on every change. The thread layer observes this to render the backlog (#461).
     *
     * Default `flowOf(emptyList())` — implementations without an interactive wire (the fake, inline
     * test doubles) inherit "never queued" and need no override, the same cascade-avoidance as
     * [observeStall] / [delete] / [requestScreenSnapshot].
     */
    fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())

    suspend fun createDiscussion(workspace: String? = null): Conversation

    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String? = null,
    ): Conversation

    suspend fun archive(conversationId: String)

    suspend fun unarchive(conversationId: String)

    /**
     * Permanently removes the conversation from the store. Tolerant of unknown
     * ids: calling `delete` on an id that is not present is a silent no-op.
     *
     * Unlike [archive] and [unarchive], which throw [IllegalArgumentException]
     * on unknown ids, `delete` converges on the post-condition — after a
     * successful return, the conversation is not in [observeConversations].
     *
     * Streams collected for the deleted conversation re-emit the empty
     * projection ([observeMessages] → `emptyList()`; [observeLastMessage] →
     * `null`); they do not complete.
     *
     * Default throws — implementations that do not support deletion inherit
     * the default. The Channel Info sheet is the only production consumer;
     * test fakes never invoke this method, so the throwing default is
     * unreachable in tests today.
     */
    suspend fun delete(conversationId: String): Unit = error("delete is not implemented for this ConversationRepository")

    suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation

    suspend fun startNewSession(
        conversationId: String,
        workspace: String? = null,
    ): Session

    suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session

    /**
     * Appends a user-authored [Message] to the conversation's current session.
     * Returns the persisted message. Throws [IllegalArgumentException] if
     * [conversationId] does not exist. Caller is responsible for non-blank
     * validation of [text]; this method does not trim or reject blank input.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message

    /**
     * Workspace folders previously bound to any conversation, deduped and ordered
     * most-recent-first by the latest cwd-affecting write across all conversations.
     *
     * Excludes "no bound workspace" cwds: the empty string `""` and
     * [DEFAULT_SCRATCH_CWD]. Cold flow; re-emits on every state change.
     *
     * Default returns an empty flow — implementations that do not track workspace
     * history (e.g. test fakes that ignore this surface) inherit the default and
     * do not need to override.
     */
    fun recentWorkspaces(): Flow<List<String>> = flowOf(emptyList())

    /**
     * Creates a new workspace folder under the `pyry-workspace/` prefix and
     * registers it in the recents stream. Returns the created path string
     * (e.g. `"pyry-workspace/scratch-1"`).
     *
     * Phase 0 implementations operate in-memory only; no filesystem I/O.
     * Phase 4 implementations will create the folder server-side.
     *
     * Throws [IllegalArgumentException] if [name] is blank or whitespace-only.
     * Trimming and basename normalization are caller concerns.
     *
     * Default throws — implementations that do not support folder creation
     * (e.g. test fakes that ignore this surface) inherit the default. The
     * Workspace Picker is the only production consumer; test fakes never
     * invoke this method, so the throwing default is unreachable in tests.
     */
    suspend fun createWorkspaceFolder(name: String): String =
        error("createWorkspaceFolder is not implemented for this ConversationRepository")

    /**
     * Requests the current claude screen for [conversationId] and returns its rendered text — the
     * always-available, parser-independent snapshot floor (pyrycode#596, ADR 025 § Safe degradation).
     * The returned text is **verbatim**: never parsed, trimmed, or sanitized — decode fidelity is the
     * whole point of the floor.
     *
     * Throws [IllegalArgumentException] for an unknown [conversationId] (the fake throws it
     * synchronously; the remote surfaces the server's `conversation.not_found` as the same type).
     * Throws on a server error ([de.pyryco.mobile.data.network.RelayErrorException]) or a
     * not-connected session ([IllegalStateException]) — the caller handles failure.
     *
     * Default throws — implementations that do not support snapshots inherit it, so the inline test
     * doubles need no override (the same cascade-avoidance as [delete] / [createWorkspaceFolder]).
     */
    suspend fun requestScreenSnapshot(conversationId: String): String =
        error("requestScreenSnapshot is not implemented for this ConversationRepository")
}

enum class ConversationFilter { All, Channels, Discussions, Archived }

/**
 * One row in the conversation thread. The stream interleaves messages
 * with synthetic [SessionBoundary] markers in chronological order; the
 * thread screen renders boundaries as horizontal-rule delimiters and
 * de-emphasizes messages above the latest delimiter.
 */
sealed interface ThreadItem {
    data class MessageItem(
        val message: Message,
    ) : ThreadItem

    /**
     * Marks a transition between two sessions in the thread stream.
     *
     * Invariant: [workspaceCwd] is non-null iff [reason] is
     * [BoundaryReason.WorkspaceChange]. For [BoundaryReason.Clear] and
     * [BoundaryReason.IdleEvict] callers must observe `null`. Documented
     * here and asserted in tests; not enforced at construction.
     */
    data class SessionBoundary(
        val previousSessionId: String,
        val newSessionId: String,
        val reason: BoundaryReason,
        val occurredAt: Instant,
        val workspaceCwd: String? = null,
    ) : ThreadItem
}

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }

/**
 * One message waiting in a conversation's queued backlog while claude is busy (#460). The element type
 * of [ConversationRepository.observeQueue], co-located with the contract it serves (like [ThreadItem]).
 *
 * [id] is the daemon's per-conversation `queued_msg_id` counter (a wire `uint64`, so [Long]); it is a
 * monotonic ordinal, not a secret. [timestamp] is enqueue time.
 */
data class QueuedMessage(
    val id: Long,
    val text: String,
    val timestamp: Instant,
)
