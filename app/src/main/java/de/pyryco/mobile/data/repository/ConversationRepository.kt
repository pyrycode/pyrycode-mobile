package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
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

    suspend fun createDiscussion(workspace: String? = null): Conversation

    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String? = null,
    ): Conversation

    suspend fun archive(conversationId: String)

    suspend fun unarchive(conversationId: String)

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

    data class SessionBoundary(
        val previousSessionId: String,
        val newSessionId: String,
        val reason: BoundaryReason,
        val occurredAt: Instant,
    ) : ThreadItem
}

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }
