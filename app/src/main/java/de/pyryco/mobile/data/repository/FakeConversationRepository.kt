package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Phase 1 in-memory implementation. Storage is a single [MutableStateFlow]
 * of `conversationId -> ConversationRecord`; mutators update it atomically
 * and observers re-emit on every change. Phase 4 replaces this with a
 * Ktor-backed remote implementation behind the same interface.
 */
class FakeConversationRepository(
    initialMessages: Map<String, List<Message>> = emptyMap(),
) : ConversationRepository {

    private val state = MutableStateFlow<Map<String, ConversationRecord>>(
        SEED_RECORDS.mapValues { (id, record) ->
            initialMessages[id]?.let { record.copy(messages = it) } ?: record
        },
    )

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        state.map { records ->
            records.values
                .map { it.conversation }
                .filter { conv ->
                    when (filter) {
                        ConversationFilter.All -> true
                        ConversationFilter.Channels -> conv.isPromoted
                        ConversationFilter.Discussions -> !conv.isPromoted
                    }
                }
                .sortedByDescending { it.lastUsedAt }
        }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        state.map { records ->
            val messages = records[conversationId]?.messages ?: return@map emptyList()
            buildThreadItems(messages)
        }

    private fun buildThreadItems(messages: List<Message>): List<ThreadItem> {
        if (messages.isEmpty()) return emptyList()
        val sorted = messages.sortedBy { it.timestamp }
        val result = ArrayList<ThreadItem>(sorted.size + sorted.size / 4)
        var previousSessionId: String? = null
        for (message in sorted) {
            val prior = previousSessionId
            if (prior != null && prior != message.sessionId) {
                result += ThreadItem.SessionBoundary(
                    previousSessionId = prior,
                    newSessionId = message.sessionId,
                    reason = BoundaryReason.Clear,
                    occurredAt = message.timestamp,
                )
            }
            result += ThreadItem.MessageItem(message)
            previousSessionId = message.sessionId
        }
        return result
    }

    override suspend fun createDiscussion(workspace: String?): Conversation {
        val now = Clock.System.now()
        val conversationId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val claudeSessionUuid = UUID.randomUUID().toString()
        val session = Session(
            id = sessionId,
            conversationId = conversationId,
            claudeSessionUuid = claudeSessionUuid,
            startedAt = now,
            endedAt = null,
        )
        val conversation = Conversation(
            id = conversationId,
            name = null,
            cwd = workspace ?: "",
            currentSessionId = sessionId,
            sessionHistory = listOf(sessionId),
            isPromoted = false,
            lastUsedAt = now,
        )
        val record = ConversationRecord(conversation, mapOf(sessionId to session))
        state.update { it + (conversationId to record) }
        return conversation
    }

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation {
        val now = Clock.System.now()
        lateinit var updated: Conversation
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            updated = record.conversation.copy(
                isPromoted = true,
                name = name,
                cwd = workspace ?: record.conversation.cwd,
                lastUsedAt = now,
            )
            records + (conversationId to record.copy(conversation = updated))
        }
        return updated
    }

    override suspend fun archive(conversationId: String) {
        state.update { records ->
            if (conversationId !in records) throw unknown(conversationId)
            records - conversationId
        }
    }

    override suspend fun rename(conversationId: String, name: String): Conversation {
        val now = Clock.System.now()
        lateinit var updated: Conversation
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            updated = record.conversation.copy(name = name, lastUsedAt = now)
            records + (conversationId to record.copy(conversation = updated))
        }
        return updated
    }

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = mintNewSession(conversationId, workspace)

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = mintNewSession(conversationId, workspace)

    private fun mintNewSession(conversationId: String, workspace: String?): Session {
        val now = Clock.System.now()
        lateinit var newSession: Session
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            val newSessionId = UUID.randomUUID().toString()
            val claudeSessionUuid = UUID.randomUUID().toString()
            newSession = Session(
                id = newSessionId,
                conversationId = conversationId,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = now,
                endedAt = null,
            )
            val closedSessions = record.sessions.mapValues { (_, s) ->
                if (s.id == record.conversation.currentSessionId && s.endedAt == null) {
                    s.copy(endedAt = now)
                } else s
            }
            val updatedConversation = record.conversation.copy(
                currentSessionId = newSessionId,
                sessionHistory = record.conversation.sessionHistory + newSessionId,
                cwd = workspace ?: record.conversation.cwd,
                lastUsedAt = now,
            )
            records + (
                conversationId to ConversationRecord(
                    conversation = updatedConversation,
                    sessions = closedSessions + (newSessionId to newSession),
                )
            )
        }
        return newSession
    }

    private fun unknown(id: String) =
        IllegalArgumentException("Unknown conversation: $id")

    private data class ConversationRecord(
        val conversation: Conversation,
        val sessions: Map<String, Session>,
        val messages: List<Message> = emptyList(),
    )

    companion object {
        private val SEED_RECORDS: Map<String, ConversationRecord> = buildSeedRecords()

        private fun buildSeedRecords(): Map<String, ConversationRecord> {
            // Intentionally not in lastUsedAt order — exercises the sort projection.
            val seeds = listOf(
                seedChannel(
                    id = "seed-channel-personal",
                    name = "Personal",
                    cwd = "~/Workspace/personal",
                    sessionId = "seed-session-personal",
                    claudeSessionUuid = "seed-claude-personal",
                    lastUsedAt = Instant.parse("2026-05-05T20:15:00Z"),
                ),
                seedChannel(
                    id = "seed-channel-pyrycode-mobile",
                    name = "Pyrycode Mobile",
                    cwd = "~/Workspace/pyrycode-mobile",
                    sessionId = "seed-session-pyrycode-mobile",
                    claudeSessionUuid = "seed-claude-pyrycode-mobile",
                    lastUsedAt = Instant.parse("2026-05-10T09:00:00Z"),
                ),
                seedChannel(
                    id = "seed-channel-joi-pilates",
                    name = "Joi Pilates",
                    cwd = "~/Workspace/joi-pilates",
                    sessionId = "seed-session-joi-pilates",
                    claudeSessionUuid = "seed-claude-joi-pilates",
                    lastUsedAt = Instant.parse("2026-05-08T15:30:00Z"),
                ),
                seedDiscussion(
                    id = "seed-discussion-b",
                    cwd = "~/.pyrycode/scratch",
                    sessionId = "seed-session-discussion-b",
                    claudeSessionUuid = "seed-claude-discussion-b",
                    lastUsedAt = Instant.parse("2026-05-09T08:00:00Z"),
                ),
                seedDiscussion(
                    id = "seed-discussion-a",
                    cwd = "~/.pyrycode/scratch",
                    sessionId = "seed-session-discussion-a",
                    claudeSessionUuid = "seed-claude-discussion-a",
                    lastUsedAt = Instant.parse("2026-05-11T14:00:00Z"),
                ),
            )
            return seeds.associateBy { it.conversation.id }
        }

        private fun seedChannel(
            id: String,
            name: String,
            cwd: String,
            sessionId: String,
            claudeSessionUuid: String,
            lastUsedAt: Instant,
        ): ConversationRecord {
            val session = Session(
                id = sessionId,
                conversationId = id,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = lastUsedAt,
                endedAt = null,
            )
            val conversation = Conversation(
                id = id,
                name = name,
                cwd = cwd,
                currentSessionId = sessionId,
                sessionHistory = listOf(sessionId),
                isPromoted = true,
                lastUsedAt = lastUsedAt,
            )
            return ConversationRecord(conversation, mapOf(sessionId to session))
        }

        private fun seedDiscussion(
            id: String,
            cwd: String,
            sessionId: String,
            claudeSessionUuid: String,
            lastUsedAt: Instant,
        ): ConversationRecord {
            val session = Session(
                id = sessionId,
                conversationId = id,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = lastUsedAt,
                endedAt = null,
            )
            val conversation = Conversation(
                id = id,
                name = null,
                cwd = cwd,
                currentSessionId = sessionId,
                sessionHistory = listOf(sessionId),
                isPromoted = false,
                lastUsedAt = lastUsedAt,
            )
            return ConversationRecord(conversation, mapOf(sessionId to session))
        }
    }
}
