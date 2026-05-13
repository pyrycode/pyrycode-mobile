package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
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
                .map { record ->
                    val current = record.sessions[record.conversation.currentSessionId]
                    record.conversation.copy(isSleeping = current?.endedAt != null)
                }
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
                    history = listOf(
                        SeedSession(
                            sessionId = "seed-session-personal-1",
                            claudeSessionUuid = "seed-claude-personal-1",
                            startedAt = ts("2026-04-28T10:00:00Z"),
                            endedAt = ts("2026-04-28T10:30:00Z"),
                            messages = listOf(
                                seedMsg(Role.User, "Remind me to renew the apartment lease this week.", "2026-04-28T10:00:00Z"),
                                seedMsg(Role.Assistant, "Got it — I'll add a calendar entry for Friday afternoon.", "2026-04-28T10:05:00Z"),
                                seedMsg(Role.User, "Also draft a quick note to the landlord asking about the rate.", "2026-04-28T10:20:00Z"),
                                seedMsg(Role.Assistant, "Drafted. It's saved under Documents/landlord-note.md.", "2026-04-28T10:28:00Z"),
                            ),
                        ),
                    ),
                    currentMessages = listOf(
                        seedMsg(Role.User, "Pick up where we left off on the lease — did the landlord reply?", "2026-05-05T19:55:00Z"),
                        seedMsg(Role.Assistant, "No reply yet. Want me to send a follow-up?", "2026-05-05T19:58:00Z"),
                        seedMsg(Role.User, "Yes please, keep it short and polite.", "2026-05-05T20:10:00Z"),
                        seedMsg(Role.Assistant, "Sent. I'll let you know when they respond.", "2026-05-05T20:15:00Z"),
                    ),
                ),
                seedChannel(
                    id = "seed-channel-pyrycode-mobile",
                    name = "Pyrycode Mobile",
                    cwd = "~/Workspace/pyrycode-mobile",
                    sessionId = "seed-session-pyrycode-mobile",
                    claudeSessionUuid = "seed-claude-pyrycode-mobile",
                    lastUsedAt = Instant.parse("2026-05-10T09:00:00Z"),
                    history = listOf(
                        SeedSession(
                            sessionId = "seed-session-pyrycode-mobile-1",
                            claudeSessionUuid = "seed-claude-pyrycode-mobile-1",
                            startedAt = ts("2026-05-03T14:00:00Z"),
                            endedAt = ts("2026-05-03T14:25:00Z"),
                            messages = listOf(
                                seedMsg(Role.User, "What's left on the Phase 1 channel list scaffolding?", "2026-05-03T14:00:00Z"),
                                seedMsg(Role.Assistant, "We still need the empty state and the recent-discussions drilldown.", "2026-05-03T14:05:00Z"),
                                seedMsg(Role.User, "Let's tackle the empty state first.", "2026-05-03T14:15:00Z"),
                                seedMsg(Role.Assistant, "Started a sketch in ui/conversations/list/EmptyState.kt.", "2026-05-03T14:24:00Z"),
                            ),
                        ),
                    ),
                    currentMessages = listOf(
                        seedMsg(Role.User, "Picking this back up — any blockers on the channel list?", "2026-05-10T08:45:00Z"),
                        seedMsg(Role.Assistant, "Nothing major. The promotion dialog still needs wiring.", "2026-05-10T08:50:00Z"),
                        seedMsg(Role.User, "Open a ticket for the dialog and we'll size it next.", "2026-05-10T08:58:00Z"),
                        seedMsg(Role.Assistant, "Drafted as #TBD with the architect notes attached.", "2026-05-10T09:00:00Z"),
                    ),
                ),
                seedChannel(
                    id = "seed-channel-joi-pilates",
                    name = "Joi Pilates",
                    cwd = "~/Workspace/joi-pilates",
                    sessionId = "seed-session-joi-pilates",
                    claudeSessionUuid = "seed-claude-joi-pilates",
                    lastUsedAt = Instant.parse("2026-05-08T15:30:00Z"),
                    history = listOf(
                        SeedSession(
                            sessionId = "seed-session-joi-pilates-1",
                            claudeSessionUuid = "seed-claude-joi-pilates-1",
                            startedAt = ts("2026-04-30T11:00:00Z"),
                            endedAt = ts("2026-04-30T11:20:00Z"),
                            messages = listOf(
                                seedMsg(Role.User, "Can you pull next week's class schedule into a draft?", "2026-04-30T11:00:00Z"),
                                seedMsg(Role.Assistant, "Drafted Monday through Sunday with the usual reformer slots.", "2026-04-30T11:08:00Z"),
                                seedMsg(Role.User, "Anna is out Thursday — who can cover the 18:00 class?", "2026-04-30T11:15:00Z"),
                                seedMsg(Role.Assistant, "Marko has the slot open. I drafted a swap message for him.", "2026-04-30T11:19:00Z"),
                            ),
                        ),
                    ),
                    currentMessages = listOf(
                        seedMsg(Role.User, "Did Marko confirm the Thursday cover?", "2026-05-08T15:15:00Z"),
                        seedMsg(Role.Assistant, "Yes, confirmed yesterday. The schedule is updated.", "2026-05-08T15:18:00Z"),
                        seedMsg(Role.User, "Great — push the new schedule to the studio Slack tomorrow morning.", "2026-05-08T15:25:00Z"),
                        seedMsg(Role.Assistant, "Queued for 08:00. I'll include the reformer maintenance reminder too.", "2026-05-08T15:30:00Z"),
                    ),
                    currentSessionEndedAt = Instant.parse("2026-05-09T03:00:00Z"),
                ),
                seedDiscussion(
                    id = "seed-discussion-b",
                    cwd = DefaultScratchCwd,
                    sessionId = "seed-session-discussion-b",
                    claudeSessionUuid = "seed-claude-discussion-b",
                    lastUsedAt = Instant.parse("2026-05-09T08:00:00Z"),
                ),
                seedDiscussion(
                    id = "seed-discussion-a",
                    cwd = DefaultScratchCwd,
                    sessionId = "seed-session-discussion-a",
                    claudeSessionUuid = "seed-claude-discussion-a",
                    lastUsedAt = Instant.parse("2026-05-11T14:00:00Z"),
                ),
            )
            return seeds.associateBy { it.conversation.id }
        }

        private fun ts(iso: String): Instant = Instant.parse(iso)

        private fun seedMsg(role: Role, content: String, timestamp: String): SeedMessage =
            SeedMessage(role, content, Instant.parse(timestamp))

        private data class SeedMessage(
            val role: Role,
            val content: String,
            val timestamp: Instant,
        )

        private data class SeedSession(
            val sessionId: String,
            val claudeSessionUuid: String,
            val startedAt: Instant,
            val endedAt: Instant,
            val messages: List<SeedMessage>,
        )

        private fun seedChannel(
            id: String,
            name: String,
            cwd: String,
            sessionId: String,
            claudeSessionUuid: String,
            lastUsedAt: Instant,
            history: List<SeedSession> = emptyList(),
            currentMessages: List<SeedMessage> = emptyList(),
            currentSessionEndedAt: Instant? = null,
        ): ConversationRecord {
            val currentSession = Session(
                id = sessionId,
                conversationId = id,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = lastUsedAt,
                endedAt = currentSessionEndedAt,
            )
            val historicalSessions = history.map { seed ->
                Session(
                    id = seed.sessionId,
                    conversationId = id,
                    claudeSessionUuid = seed.claudeSessionUuid,
                    startedAt = seed.startedAt,
                    endedAt = seed.endedAt,
                )
            }
            val historicalMessages = history.flatMap { seed ->
                seed.messages.mapIndexed { index, msg ->
                    Message(
                        id = "${seed.sessionId}-msg-$index",
                        sessionId = seed.sessionId,
                        role = msg.role,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        isStreaming = false,
                    )
                }
            }
            val currentMessageEntries = currentMessages.mapIndexed { index, msg ->
                Message(
                    id = "$sessionId-msg-$index",
                    sessionId = sessionId,
                    role = msg.role,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    isStreaming = false,
                )
            }
            val conversation = Conversation(
                id = id,
                name = name,
                cwd = cwd,
                currentSessionId = sessionId,
                sessionHistory = history.map { it.sessionId } + sessionId,
                isPromoted = true,
                lastUsedAt = lastUsedAt,
            )
            val sessions = (historicalSessions + currentSession).associateBy { it.id }
            return ConversationRecord(
                conversation = conversation,
                sessions = sessions,
                messages = historicalMessages + currentMessageEntries,
            )
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
