package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Idle threshold after which an unpromoted discussion is auto-archived by
 * [FakeConversationRepository.sweep]. `internal` so the rule and its boundary
 * test share one source of truth. A `val` (not `const`) because [Duration]
 * cannot be a compile-time constant.
 */
internal val ARCHIVE_IDLE_THRESHOLD: Duration = 30.days

/**
 * Pure idle-archive predicate, port of the canonical pyrycode `ShouldArchive`.
 * Promoted channels never auto-archive; an unpromoted discussion archives once
 * it has been idle for at least [ARCHIVE_IDLE_THRESHOLD] at [referenceTime].
 * The boundary is inclusive (`>=`): idle for exactly the threshold archives.
 * [referenceTime] is caller-supplied — the rule never reads the system clock —
 * which keeps the boundary tests deterministic with literal instants.
 */
internal fun shouldArchive(
    conversation: Conversation,
    referenceTime: Instant,
): Boolean =
    !conversation.isPromoted &&
        referenceTime - conversation.lastUsedAt >= ARCHIVE_IDLE_THRESHOLD

/**
 * Deterministic canned screen text the [FakeConversationRepository] returns from
 * [FakeConversationRepository.requestScreenSnapshot] for a known conversation (#375), giving UI/tests
 * an observable, verbatim contract. Non-blank and whitespace-laden on purpose: the leading and
 * trailing spaces let a test prove the consumer returns the text untrimmed. The fake performs no
 * network and no mutation — a snapshot is a pure read.
 */
internal const val FAKE_SCREEN_SNAPSHOT_TEXT =
    "  claude screen snapshot (fake)\n  > Ready for input.\n  "

/**
 * Phase 1 in-memory implementation. Storage is a single [MutableStateFlow]
 * of `conversationId -> ConversationRecord`; mutators update it atomically
 * and observers re-emit on every change. Phase 4 replaces this with a
 * Ktor-backed remote implementation behind the same interface.
 */
class FakeConversationRepository : ConversationRepository {
    private val state = MutableStateFlow(SEED_RECORDS)

    /** Demo daemon: supplies complete authored facts through the same route as a remote host. */
    override val negotiatedThread: NegotiatedThread =
        object : NegotiatedThread() {
            override val host = "demo"
            override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(this))
            override val store = AuthoritativeThreadStore()

            override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
                state.map { records ->
                    val items = records[conversationId]?.authoredItems().orEmpty()
                    store.establishComplete(
                        host,
                        conversationId,
                        "demo",
                        items.maxOfOrNull { it.rev } ?: 0,
                        items,
                        store.current(host, conversationId).generation,
                    )
                    store.current(host, conversationId)
                }

            override suspend fun open(conversationId: String) = true

            override fun older(conversationId: String) = false

            override fun cancel(conversationId: String) = Unit
        }

    private fun ConversationRecord.authoredItems(): List<ThreadItemDto> =
        buildList {
            fun append(
                kind: String,
                session: String,
                content: JsonObject,
                active: Boolean = false,
                status: String = "done",
            ) {
                val id = size.toLong() + 1
                add(
                    ThreadItemDto.decode(
                        JsonObject(
                            buildMap {
                                put("id", JsonPrimitive(id))
                                put("rev", JsonPrimitive(id))
                                put("order", JsonPrimitive(id))
                                put("kind", JsonPrimitive(kind))
                                put("session", JsonPrimitive(session))
                                put("agent", JsonPrimitive(if (conversation.agent == ConversationAgent.Codex) "codex" else "claude"))
                                put("active", JsonPrimitive(active))
                                put("status", JsonPrimitive(status))
                                put("shown", JsonPrimitive(true))
                                put("summary", JsonPrimitive(""))
                                put("content", content)
                            },
                        ),
                    ),
                )
            }
            conversation.sessionHistory.forEachIndexed { index, sessionId ->
                if (index > 0) {
                    val boundary = boundariesBySessionId[sessionId]
                    append(
                        "session_divider",
                        sessionId,
                        JsonObject(
                            buildMap {
                                put("previous_session_id", JsonPrimitive(conversation.sessionHistory[index - 1]))
                                put("new_session_id", JsonPrimitive(sessionId))
                                val reason =
                                    when (boundary?.reason) {
                                        BoundaryReason.WorkspaceChange -> "workspace_change"
                                        BoundaryReason.IdleEvict -> "idle_evict"
                                        else -> "clear"
                                    }
                                put("reason", JsonPrimitive(reason))
                                sessions[sessionId]?.startedAt?.let { put("occurred_at", JsonPrimitive(it.toString())) }
                                boundary?.workspaceCwd?.let { put("workspace_cwd", JsonPrimitive(it)) }
                            },
                        ),
                    )
                }
                messages.filter { it.sessionId == sessionId }.forEach { message ->
                    val tool = message.toolCall
                    val content =
                        JsonObject(
                            buildMap {
                                put("message_id", JsonPrimitive(message.id))
                                put("device_id", JsonPrimitive("demo"))
                                put("text", JsonPrimitive(message.content))
                                put("sent_at", JsonPrimitive(message.timestamp.toString()))
                                if (tool != null) {
                                    put("name", JsonPrimitive(tool.toolName))
                                    put("input_summary", JsonPrimitive(tool.input))
                                    put("input", JsonObject(tool.inputFields.mapValues { JsonPrimitive(it.value) }))
                                    put("result", JsonObject(mapOf("result_summary" to JsonPrimitive(tool.output))))
                                }
                            },
                        )
                    val kind =
                        when (message.role) {
                            Role.User -> "user_message"
                            Role.Assistant -> "assistant_message"
                            Role.Tool -> if (tool?.toolName in setOf("Agent", "Task")) "agent" else "tool_call"
                        }
                    append(kind, sessionId, content, message.isStreaming, if (message.role == Role.User) "delivered" else "done")
                }
            }
        }

    private val recents = MutableStateFlow<List<String>>(initialRecents())

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        state.map { records ->
            records.values
                .map { record ->
                    val current = record.sessions[record.conversation.currentSessionId]
                    record.conversation.copy(isSleeping = current?.endedAt != null)
                }.filter { conv ->
                    when (filter) {
                        ConversationFilter.All -> true
                        ConversationFilter.Channels -> conv.isPromoted && !conv.archived
                        ConversationFilter.Discussions -> !conv.isPromoted && !conv.archived
                        ConversationFilter.Archived -> conv.archived
                    }
                }.sortedByDescending { it.lastUsedAt }
        }

    override fun observeLastMessage(conversationId: String): Flow<Message?> =
        state.map { records ->
            records[conversationId]?.messages?.maxByOrNull { it.timestamp }
        }

    override fun recentWorkspaces(): Flow<List<String>> = recents

    override suspend fun createDiscussion(workspace: String?): Conversation =
        insertNew(name = null, cwd = workspace ?: "", isPromoted = false)

    override suspend fun createChannel(
        name: String,
        workspace: String?,
    ): Conversation = insertNew(name = name, cwd = workspace ?: "", isPromoted = true)

    private fun insertNew(
        name: String?,
        cwd: String,
        isPromoted: Boolean,
    ): Conversation {
        val now = Clock.System.now()
        val conversationId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val claudeSessionUuid = UUID.randomUUID().toString()
        val session =
            Session(
                id = sessionId,
                conversationId = conversationId,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = now,
                endedAt = null,
            )
        val conversation =
            Conversation(
                id = conversationId,
                name = name,
                cwd = cwd,
                currentSessionId = sessionId,
                sessionHistory = listOf(sessionId),
                isPromoted = isPromoted,
                lastUsedAt = now,
            )
        val record = ConversationRecord(conversation, mapOf(sessionId to session))
        state.update { it + (conversationId to record) }
        bumpWorkspace(conversation.cwd)
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
            updated =
                record.conversation.copy(
                    isPromoted = true,
                    name = name,
                    cwd = workspace ?: record.conversation.cwd,
                    lastUsedAt = now,
                )
            records + (conversationId to record.copy(conversation = updated))
        }
        bumpWorkspace(updated.cwd)
        return updated
    }

    override suspend fun archive(conversationId: String) {
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            records + (
                conversationId to
                    record.copy(conversation = record.conversation.copy(archived = true))
            )
        }
    }

    override suspend fun unarchive(conversationId: String) {
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            records + (
                conversationId to
                    record.copy(conversation = record.conversation.copy(archived = false))
            )
        }
    }

    override fun observeReadMarks(conversationId: String): Flow<ConversationReadMarks?> =
        state
            .map { records ->
                records[conversationId]?.let { ConversationReadMarks(it.readUpTo, null, it.authoredItems().size.toULong(), true) }
            }.distinctUntilChanged()

    override suspend fun markConversationRead(
        conversationId: String,
        upTo: ULong,
    ): Result<ULong> {
        var stored = 0uL
        return try {
            state.update { records ->
                val record = records[conversationId] ?: throw IllegalArgumentException("Unknown conversation")
                stored = maxOf(record.readUpTo, minOf(upTo, record.authoredItems().size.toULong()))
                records + (conversationId to record.copy(readUpTo = stored))
            }
            Result.success(stored)
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        }
    }

    override suspend fun setMuted(
        conversationId: String,
        muted: Boolean,
    ) {
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            records + (conversationId to record.copy(conversation = record.conversation.copy(muted = muted)))
        }
    }

    /** Stands in for the host's pin (#2214): a repeat pin keeps its first time, unpin clears it. */
    override suspend fun setPinned(
        conversationId: String,
        pinned: Boolean,
    ) {
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            val pinnedAt = if (pinned) record.conversation.pinnedAt ?: Clock.System.now() else null
            records + (conversationId to record.copy(conversation = record.conversation.copy(pinnedAt = pinnedAt)))
        }
    }

    /**
     * Applies [shouldArchive] across the whole store at [referenceTime], flipping
     * `archived = true` on every unpromoted, not-already-archived match so that
     * auto-archived discussions converge on the same observable state as manually
     * archived ones (reachable under [ConversationFilter.Archived] and
     * [ConversationFilter.All], still [unarchive]-able). Returns the count newly
     * archived. Idempotent: re-running with the same reference time archives
     * nothing, returns 0, and does not re-emit (the rebuilt map is value-equal).
     *
     * Fake-only — not on [ConversationRepository]. In Phase 4 the real backend
     * sweeps server-side on its own schedule; a client-triggered sweep would be
     * meaningless on the remote impl. The live trigger (a scheduler calling this
     * with `Clock.System.now()`) lands with the backend.
     */
    suspend fun sweep(referenceTime: Instant): Int {
        var newlyArchived = 0
        state.update { records ->
            newlyArchived = 0
            records.mapValues { (_, record) ->
                val conv = record.conversation
                if (!conv.archived && shouldArchive(conv, referenceTime)) {
                    newlyArchived++
                    record.copy(conversation = conv.copy(archived = true))
                } else {
                    record
                }
            }
        }
        return newlyArchived
    }

    // See ConversationRepository.delete contract: tolerant of unknown ids
    // (Map - missingKey returns an equals-identical map, so StateFlow does not re-emit).
    override suspend fun delete(conversationId: String) {
        val route = negotiatedThread
        if (route.store.current(route.host, conversationId).epoch != null) {
            route.store.reset(route.host, conversationId, "demo")
            route.store.establishComplete(
                route.host,
                conversationId,
                "demo",
                0,
                emptyList(),
                route.store.current(route.host, conversationId).generation,
            )
        }
        state.update { it - conversationId }
    }

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation {
        val now = Clock.System.now()
        lateinit var updated: Conversation
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            updated = record.conversation.copy(name = name, lastUsedAt = now)
            records + (conversationId to record.copy(conversation = updated))
        }
        return updated
    }

    // Empty reset text is demo data, never a copy of the daemon's shipped instructions.
    private val hostSystemPrompt = MutableStateFlow(HostSystemPromptReading("", ""))

    override suspend fun requestHostSystemPrompt(): Result<HostSystemPromptReading> = Result.success(hostSystemPrompt.value)

    override suspend fun setHostSystemPrompt(systemPrompt: String): Result<HostSystemPromptReading> {
        if (!SystemPromptLimit.fits(systemPrompt)) {
            return Result.failure(IllegalArgumentException("Host system prompt exceeds the byte limit"))
        }
        val reading = HostSystemPromptReading(systemPrompt, "")
        hostSystemPrompt.value = reading
        return Result.success(reading)
    }

    /**
     * Stored system prompts by conversation (#823), in memory for demo mode. A missing key is "no prompt
     * stored"; a present value, `""` included, is the stored text — so the three states stay distinct.
     */
    private val systemPrompts = MutableStateFlow<Map<String, String>>(emptyMap())

    // Any id reads, an unknown one like a hosted conversation holding nothing — the daemon's posture.
    // Demo mode runs no session, so the verdict is always NoSession.
    override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading =
        SystemPromptReading(systemPrompts.value[conversationId], SessionPromptStatus.NoSession)

    override suspend fun setSystemPrompt(
        conversationId: String,
        systemPrompt: String?,
    ) {
        require(systemPrompt == null || SystemPromptLimit.fits(systemPrompt)) { "system prompt exceeds the byte limit" }
        if (conversationId !in state.value) throw unknown(conversationId)
        systemPrompts.update { if (systemPrompt == null) it - conversationId else it + (conversationId to systemPrompt) }
    }

    /**
     * The [setSessionSettings] requests received, in call order — the observable seam #544's ViewModel
     * tests assert send-on-change against. The Fake models **no** per-session settings state (the data
     * model has none, and adding one is out of scope): it only records what was requested, reusing the
     * wire DTO [SetSessionSettingsPayloadDto] as the record shape (structural equality for free).
     */
    val setSessionSettingsCalls: List<SetSessionSettingsPayloadDto> get() = recordedSessionSettings

    private val recordedAgentSwitches = mutableListOf<SwitchAgentCall>()
    val switchAgentCalls: List<SwitchAgentCall> get() = recordedAgentSwitches.toList()

    /** Deterministic caller-test refusal; null confirms the named conversation's new agent. */
    var switchAgentFailure: SwitchAgentFailure? = null

    override suspend fun switchAgent(
        conversationId: String,
        agent: ConversationAgent,
        model: String,
        effort: String?,
    ): Result<Unit> {
        recordedAgentSwitches += SwitchAgentCall(conversationId, agent, model, effort)
        switchAgentFailure?.let { return Result.failure(it) }
        if (conversationId !in state.value) {
            return Result.failure(SwitchAgentFailure(SwitchAgentFailure.Category.ConversationNotFound))
        }
        state.update { records ->
            records.mapValues { (id, record) ->
                if (id == conversationId) record.copy(conversation = record.conversation.copy(agent = agent)) else record
            }
        }
        return Result.success(Unit)
    }

    private val recordedSessionSettings = mutableListOf<SetSessionSettingsPayloadDto>()

    override suspend fun setSessionSettings(
        sessionId: String,
        model: String?,
        effort: String?,
        yolo: Boolean?,
        permissionMode: String?,
    ) {
        recordedSessionSettings += SetSessionSettingsPayloadDto(sessionId, model, effort, yolo, permissionMode)
    }

    /**
     * Settings readings by conversation (#590) — seeded **empty on purpose**. An unseeded conversation
     * reads `null`, the same "unavailable" a live repository reports before its first reply, so a
     * consumer's unavailable path is the Fake's default rather than a case a test has to arrange. The
     * Fake manufactures no posture and no effort of its own: AC #3's whole point is that a default here
     * would be a lie about what the daemon confirmed.
     */
    private val sessionSettings = MutableStateFlow<Map<String, SessionSettings>>(emptyMap())

    /** Seed or clear one conversation's reading — the test/preview seam for a populated footer. */
    fun setSessionSettingsReading(
        conversationId: String,
        reading: SessionSettings?,
    ) {
        sessionSettings.update { if (reading == null) it - conversationId else it + (conversationId to reading) }
    }

    /**
     * The conversations a fresh reading was asked for, in call order — the seam a ViewModel test uses to
     * assert that a settled settings write asked for one, mirroring [setSessionSettingsCalls].
     */
    val sessionSettingsRefreshes: List<String> get() = recordedSettingsRefreshes

    private val recordedSettingsRefreshes = mutableListOf<String>()

    override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
        sessionSettings.map { it[conversationId] }.distinctUntilChanged()

    private val modelMenus = MutableStateFlow<Map<String, ModelMenu>>(emptyMap())

    /**
     * Seed or clear one conversation's model menu (#791) — the test/preview seam for a populated model
     * and effort control. The Fake has no wire, so a menu exists here only because a caller put it
     * there: an unseeded conversation reads unavailable and the Fake substitutes no `Model` entries and
     * no `Effort` levels, which is the lie this reading exists to prevent.
     */
    fun setModelMenu(
        conversationId: String,
        menu: ModelMenu?,
    ) {
        modelMenus.update { if (menu == null) it - conversationId else it + (conversationId to menu) }
    }

    override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> = modelMenus.map { it[conversationId] }.distinctUntilChanged()

    private val slashCommandMenus = MutableStateFlow<Map<String, SlashCommandMenu>>(emptyMap())

    /**
     * Seed or clear one conversation's slash-command menu (#882) — the test/preview seam for the composer's
     * command offers. The Fake has no wire, so a menu exists only because a caller put it there; an unseeded
     * conversation reads `null`.
     */
    fun setSlashCommandMenu(
        conversationId: String,
        menu: SlashCommandMenu?,
    ) {
        slashCommandMenus.update { if (menu == null) it - conversationId else it + (conversationId to menu) }
    }

    override fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> =
        slashCommandMenus.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Records the ask and re-emits nothing: the Fake has no wire to re-read, so a seeded reading is
     * already current. Non-throwing, like every implementation of this method.
     */
    override fun refreshSessionSettings(conversationId: String) {
        recordedSettingsRefreshes += conversationId
    }

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = mintNewSession(conversationId, workspace)

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = mintNewSession(conversationId, workspace)

    override suspend fun createWorkspaceFolder(name: String): String {
        require(name.isNotBlank()) { "name must not be blank" }
        val path = "pyry-workspace/$name"
        bumpWorkspace(path)
        return path
    }

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message {
        val now = Clock.System.now()
        lateinit var appended: Message
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            appended =
                Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = record.conversation.currentSessionId,
                    role = Role.User,
                    content = text,
                    timestamp = now,
                    isStreaming = false,
                )
            records + (
                conversationId to
                    record.copy(
                        conversation = record.conversation.copy(lastUsedAt = now),
                        messages = record.messages + appended,
                    )
            )
        }
        return appended
    }

    /**
     * Return the deterministic canned [FAKE_SCREEN_SNAPSHOT_TEXT] for a known [conversationId], or
     * throw [IllegalArgumentException] for an unknown one (#375). A pure read: no network, no
     * mutation — it only checks membership against the current [state].
     */
    override suspend fun requestScreenSnapshot(conversationId: String): String {
        state.value[conversationId] ?: throw unknown(conversationId)
        return FAKE_SCREEN_SNAPSHOT_TEXT
    }

    private fun mintNewSession(
        conversationId: String,
        workspace: String?,
    ): Session {
        val now = Clock.System.now()
        lateinit var newSession: Session
        lateinit var resultCwd: String
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            val newSessionId = UUID.randomUUID().toString()
            val claudeSessionUuid = UUID.randomUUID().toString()
            newSession =
                Session(
                    id = newSessionId,
                    conversationId = conversationId,
                    claudeSessionUuid = claudeSessionUuid,
                    startedAt = now,
                    endedAt = null,
                )
            val closedSessions =
                record.sessions.mapValues { (_, s) ->
                    if (s.id == record.conversation.currentSessionId && s.endedAt == null) {
                        s.copy(endedAt = now)
                    } else {
                        s
                    }
                }
            val updatedConversation =
                record.conversation.copy(
                    currentSessionId = newSessionId,
                    sessionHistory = record.conversation.sessionHistory + newSessionId,
                    cwd = workspace ?: record.conversation.cwd,
                    lastUsedAt = now,
                )
            resultCwd = updatedConversation.cwd
            records + (
                conversationId to
                    record.copy(
                        conversation = updatedConversation,
                        sessions = closedSessions + (newSessionId to newSession),
                        boundariesBySessionId =
                            record.boundariesBySessionId +
                                (
                                    newSessionId to
                                        AuthoredBoundary(
                                            if (workspace == null) BoundaryReason.Clear else BoundaryReason.WorkspaceChange,
                                            workspace,
                                        )
                                ),
                    )
            )
        }
        bumpWorkspace(resultCwd)
        return newSession
    }

    private fun bumpWorkspace(cwd: String) {
        if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) return
        recents.update { current -> listOf(cwd) + current.filterNot { it == cwd } }
    }

    /** One history entry for [message] at durable log position [id] — a `message`-shaped stored frame. */

    private fun unknown(id: String) = IllegalArgumentException("Unknown conversation: $id")

    private data class ConversationRecord(
        val conversation: Conversation,
        val sessions: Map<String, Session>,
        val messages: List<Message> = emptyList(),
        val boundariesBySessionId: Map<String, AuthoredBoundary> = emptyMap(),
        val readUpTo: ULong = 0uL,
    )

    private data class AuthoredBoundary(
        val reason: BoundaryReason,
        val workspaceCwd: String?,
    )

    companion object {
        private val SEED_RECORDS: Map<String, ConversationRecord> = buildSeedRecords()

        private fun initialRecents(): List<String> =
            SEED_RECORDS.values
                .map { it.conversation }
                .sortedByDescending { it.lastUsedAt }
                .map { it.cwd }
                .filterNot { it.isEmpty() || it == DEFAULT_SCRATCH_CWD }
                .distinct()

        private fun buildSeedRecords(): Map<String, ConversationRecord> {
            // Intentionally not in lastUsedAt order — exercises the sort projection.
            val seeds =
                listOf(
                    seedChannel(
                        id = "seed-channel-personal",
                        name = "Personal",
                        cwd = "~/Workspace/personal",
                        sessionId = "seed-session-personal",
                        claudeSessionUuid = "seed-claude-personal",
                        lastUsedAt = Instant.parse("2026-05-05T20:15:00Z"),
                        history =
                            listOf(
                                SeedSession(
                                    sessionId = "seed-session-personal-1",
                                    claudeSessionUuid = "seed-claude-personal-1",
                                    startedAt = ts("2026-04-28T10:00:00Z"),
                                    endedAt = ts("2026-04-28T10:30:00Z"),
                                    messages =
                                        listOf(
                                            seedMsg(Role.User, "Remind me to renew the apartment lease.", "2026-04-28T10:00:00Z"),
                                            seedMsg(Role.Assistant, "Got it — I'll add a calendar.", "2026-04-28T10:05:00Z"),
                                            seedMsg(Role.User, "Also draft a quick note to the landlord.", "2026-04-28T10:20:00Z"),
                                            seedMsg(Role.Assistant, "Drafted. It's saved under.", "2026-04-28T10:28:00Z"),
                                        ),
                                    nextBoundaryReason = BoundaryReason.Clear,
                                ),
                            ),
                        currentMessages =
                            listOf(
                                seedMsg(Role.User, "Pick up where we left off on the lease — did.", "2026-05-05T19:55:00Z"),
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
                        history =
                            listOf(
                                SeedSession(
                                    sessionId = "seed-session-pyrycode-mobile-1",
                                    claudeSessionUuid = "seed-claude-pyrycode-mobile-1",
                                    startedAt = ts("2026-05-03T14:00:00Z"),
                                    endedAt = ts("2026-05-03T14:25:00Z"),
                                    messages =
                                        listOf(
                                            seedMsg(Role.User, "What's left on the Phase 1 channel list.", "2026-05-03T14:00:00Z"),
                                            seedMsg(Role.Assistant, "We still need the empty state and.", "2026-05-03T14:05:00Z"),
                                            seedMsg(Role.User, "Let's tackle the empty state first.", "2026-05-03T14:15:00Z"),
                                            seedMsg(Role.Assistant, "Started a sketch in.", "2026-05-03T14:24:00Z"),
                                        ),
                                    nextBoundaryReason = BoundaryReason.Clear,
                                ),
                            ),
                        currentMessages =
                            listOf(
                                seedMsg(Role.User, "Picking this back up — any blockers on the.", "2026-05-10T08:45:00Z"),
                                seedMsg(Role.Assistant, "Nothing major. The promotion dialog still.", "2026-05-10T08:50:00Z"),
                                seedMsg(Role.User, "Open a ticket for the dialog and we'll size it.", "2026-05-10T08:58:00Z"),
                                seedMsg(
                                    Role.Tool,
                                    "Read app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt",
                                    "2026-05-10T08:59:00Z",
                                    toolCall =
                                        ToolCall(
                                            toolName = "Read",
                                            input = "app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt",
                                            output =
                                                """
                                                @Composable
                                                fun ThreadScreen(
                                                    state: ThreadUiState,
                                                    onEvent: (ThreadEvent) -> Unit,
                                                ) {
                                                    Scaffold(topBar = { ThreadTopBar(state, onEvent) }) { padding ->
                                                        MessageList(state.items, modifier = Modifier.padding(padding))
                                                    }
                                                }
                                                """.trimIndent(),
                                        ),
                                ),
                                seedMsg(
                                    Role.Assistant,
                                    """
                                    Drafted as #TBD with the architect notes. Three things I'd add for the dialog ticket:

                                    1. **Validation** — name must be non-empty after trim; reject if a channel with the same name already exists in this conversation list.
                                    2. **Workspace picker** — default to the current cwd, allow override via the existing `WorkspacePicker` composable.
                                    3. **Cancel affordance** — Esc / back-press dismisses without promoting.

                                    Want me to sketch the `UiState` shape before we open the issue?
                                    """.trimIndent(),
                                    "2026-05-10T09:00:00Z",
                                    isStreaming = true,
                                ),
                            ),
                    ),
                    seedChannel(
                        id = "seed-channel-joi-pilates",
                        name = "Joi Pilates",
                        cwd = "~/Workspace/joi-pilates",
                        sessionId = "seed-session-joi-pilates",
                        claudeSessionUuid = "seed-claude-joi-pilates",
                        lastUsedAt = Instant.parse("2026-05-08T15:30:00Z"),
                        history =
                            listOf(
                                SeedSession(
                                    sessionId = "seed-session-joi-pilates-1",
                                    claudeSessionUuid = "seed-claude-joi-pilates-1",
                                    startedAt = ts("2026-04-30T11:00:00Z"),
                                    endedAt = ts("2026-04-30T11:20:00Z"),
                                    messages =
                                        listOf(
                                            seedMsg(Role.User, "Can you pull next week's class schedule.", "2026-04-30T11:00:00Z"),
                                            seedMsg(Role.Assistant, "Drafted Monday through Sunday with.", "2026-04-30T11:08:00Z"),
                                            seedMsg(Role.User, "Anna is out Thursday — who can cover.", "2026-04-30T11:15:00Z"),
                                            seedMsg(Role.Assistant, "Marko has the slot open. I drafted.", "2026-04-30T11:19:00Z"),
                                        ),
                                    nextBoundaryReason = BoundaryReason.IdleEvict,
                                ),
                            ),
                        currentMessages =
                            listOf(
                                seedMsg(Role.User, "Did Marko confirm the Thursday cover?", "2026-05-08T15:15:00Z"),
                                seedMsg(Role.Assistant, "Yes, confirmed yesterday. The schedule is.", "2026-05-08T15:18:00Z"),
                                seedMsg(Role.User, "Great — push the new schedule to the studio.", "2026-05-08T15:25:00Z"),
                                seedMsg(Role.Assistant, "Queued for 08:00. I'll include the.", "2026-05-08T15:30:00Z"),
                            ),
                        currentSessionEndedAt = Instant.parse("2026-05-09T03:00:00Z"),
                    ),
                    seedDiscussion(
                        id = "seed-discussion-b",
                        cwd = DEFAULT_SCRATCH_CWD,
                        sessionId = "seed-session-discussion-b",
                        claudeSessionUuid = "seed-claude-discussion-b",
                        lastUsedAt = Instant.parse("2026-05-09T08:00:00Z"),
                    ),
                    seedDiscussion(
                        id = "seed-discussion-a",
                        cwd = DEFAULT_SCRATCH_CWD,
                        sessionId = "seed-session-discussion-a",
                        claudeSessionUuid = "seed-claude-discussion-a",
                        lastUsedAt = Instant.parse("2026-05-11T14:00:00Z"),
                        messages =
                            listOf(
                                seedMsg(Role.User, "What's a good name for a scratch script.", "2026-05-11T13:48:00Z"),
                                seedMsg(Role.Assistant, "Something descriptive of the task — try.", "2026-05-11T13:52:00Z"),
                                seedMsg(Role.User, "Use try-experiment-1.sh and move on.", "2026-05-11T13:58:00Z"),
                                seedMsg(Role.Assistant, "Renamed. Ready when you are.", "2026-05-11T14:00:00Z"),
                            ),
                    ),
                    seedDiscussion(
                        id = "seed-discussion-archived",
                        cwd = DEFAULT_SCRATCH_CWD,
                        sessionId = "seed-session-discussion-archived",
                        claudeSessionUuid = "seed-claude-discussion-archived",
                        lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
                        archived = true,
                    ),
                )
            return seeds.associateBy { it.conversation.id }
        }

        private fun ts(iso: String): Instant = Instant.parse(iso)

        private fun seedMsg(
            role: Role,
            content: String,
            timestamp: String,
            isStreaming: Boolean = false,
            toolCall: ToolCall? = null,
        ): SeedMessage = SeedMessage(role, content, Instant.parse(timestamp), isStreaming, toolCall)

        private data class SeedMessage(
            val role: Role,
            val content: String,
            val timestamp: Instant,
            val isStreaming: Boolean = false,
            val toolCall: ToolCall? = null,
        )

        private data class SeedSession(
            val sessionId: String,
            val claudeSessionUuid: String,
            val startedAt: Instant,
            val endedAt: Instant,
            val messages: List<SeedMessage>,
            val nextBoundaryReason: BoundaryReason? = null,
            val nextWorkspaceCwd: String? = null,
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
            val currentSession =
                Session(
                    id = sessionId,
                    conversationId = id,
                    claudeSessionUuid = claudeSessionUuid,
                    startedAt = lastUsedAt,
                    endedAt = currentSessionEndedAt,
                )
            val historicalSessions =
                history.map { seed ->
                    Session(
                        id = seed.sessionId,
                        conversationId = id,
                        claudeSessionUuid = seed.claudeSessionUuid,
                        startedAt = seed.startedAt,
                        endedAt = seed.endedAt,
                    )
                }
            val historicalMessages =
                history.flatMap { seed ->
                    seed.messages.mapIndexed { index, msg ->
                        Message(
                            id = "${seed.sessionId}-msg-$index",
                            sessionId = seed.sessionId,
                            role = msg.role,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            isStreaming = msg.isStreaming,
                            toolCall = msg.toolCall,
                        )
                    }
                }
            val currentMessageEntries =
                currentMessages.mapIndexed { index, msg ->
                    Message(
                        id = "$sessionId-msg-$index",
                        sessionId = sessionId,
                        role = msg.role,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        isStreaming = msg.isStreaming,
                        toolCall = msg.toolCall,
                    )
                }
            val conversation =
                Conversation(
                    id = id,
                    name = name,
                    cwd = cwd,
                    currentSessionId = sessionId,
                    sessionHistory = history.map { it.sessionId } + sessionId,
                    isPromoted = true,
                    lastUsedAt = lastUsedAt,
                )
            val sessions = (historicalSessions + currentSession).associateBy { it.id }
            val orderedSessionIds = history.map { it.sessionId } + sessionId
            val boundariesBySessionId =
                history
                    .mapIndexedNotNull { index, seed ->
                        val reason = seed.nextBoundaryReason ?: return@mapIndexedNotNull null
                        val successorId = orderedSessionIds[index + 1]
                        successorId to AuthoredBoundary(reason, seed.nextWorkspaceCwd)
                    }.toMap()
            return ConversationRecord(
                conversation = conversation,
                sessions = sessions,
                messages = historicalMessages + currentMessageEntries,
                boundariesBySessionId = boundariesBySessionId,
            )
        }

        private fun seedDiscussion(
            id: String,
            cwd: String,
            sessionId: String,
            claudeSessionUuid: String,
            lastUsedAt: Instant,
            archived: Boolean = false,
            messages: List<SeedMessage> = emptyList(),
        ): ConversationRecord {
            val session =
                Session(
                    id = sessionId,
                    conversationId = id,
                    claudeSessionUuid = claudeSessionUuid,
                    startedAt = lastUsedAt,
                    endedAt = null,
                )
            val conversation =
                Conversation(
                    id = id,
                    name = null,
                    cwd = cwd,
                    currentSessionId = sessionId,
                    sessionHistory = listOf(sessionId),
                    isPromoted = false,
                    lastUsedAt = lastUsedAt,
                    archived = archived,
                )
            val messageEntries =
                messages.mapIndexed { index, msg ->
                    Message(
                        id = "$sessionId-msg-$index",
                        sessionId = sessionId,
                        role = msg.role,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        isStreaming = msg.isStreaming,
                        toolCall = msg.toolCall,
                    )
                }
            return ConversationRecord(
                conversation = conversation,
                sessions = mapOf(sessionId to session),
                messages = messageEntries,
            )
        }
    }
}
