package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Phase 1 in-memory implementation. Storage is a single [MutableStateFlow]
 * of `conversationId -> ConversationRecord`; mutators update it atomically
 * and observers re-emit on every change. Phase 4 replaces this with a
 * Ktor-backed remote implementation behind the same interface.
 */
class FakeConversationRepository : ConversationRepository {

    private val state = MutableStateFlow<Map<String, ConversationRecord>>(emptyMap())

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
        }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flowOf(emptyList())

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
    )
}
