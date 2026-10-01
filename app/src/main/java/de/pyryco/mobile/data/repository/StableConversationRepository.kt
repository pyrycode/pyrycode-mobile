package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The stable [ConversationRepository] reference ViewModels hold across relay connection churn (#352).
 *
 * #351's [RelayRepositoryCoordinator] builds a fresh connection-scoped [RemoteConversationRepository]
 * per live relay connection and publishes the current one — or `null` between connections — on
 * `currentRepository`. ViewModels resolve a single repository at construction and hold it for their
 * lifetime, so a connection-scoped repository captured directly would be a dead reference the moment
 * its connection dropped. This facade is the stable indirection: one process-lifetime singleton whose
 * object identity never changes (AC #1), delegating every call to whichever connection-scoped
 * repository is currently live and switching transparently as connections come and go.
 *
 * **Holds no scope and launches no coroutine.** Cold reads are collected on the consumer's scope;
 * one-shots run on the caller's coroutine. The only state is the injected [currentRepository], owned
 * and written by the coordinator — this facade only **reads** it (`.value` for one-shots, the flow
 * for cold reads), so there is no lock, no `MutableStateFlow`, and no TOCTOU here.
 *
 * **Cold reads** [observeConversations]/[observeMessages]/[observeLastMessage]/[recentWorkspaces]
 * switch over [currentRepository] with [flatMapLatest]: a new value cancels the prior inner flow and
 * subscribes the new one, so the previous connection's projection is dropped the instant the value
 * changes — combined with #351 giving each connection a distinct repository instance, a reader can
 * never observe a previous connection's data after a reconnect (AC #2). While no connection is live
 * each cold read emits its defined empty projection (`emptyList()` / `null`) and resumes on the next
 * connection (AC #3).
 *
 * **One-shots** snapshot the live repository at call entry and delegate to it; with no connection live
 * they throw [IllegalStateException] — the same type [RemoteConversationRepository] throws on a
 * not-`Open` pump, so a caller catches one type whether the connection was absent at call time
 * (here) or dropped mid-flight (the delegate). Delegation is behaviour-neutral: a wired mutation, a
 * throwing stub, or a wired error all propagate verbatim (AC #4) — the facade adds nothing.
 *
 * **Held readings (#1317).** Given a host's [HostReadings], the five pushed readings it holds (announced model,
 * session facts, context usage, usage limit, slash-command menu) are read from it directly rather than
 * switched, so they stay on screen across a reconnect and the gap before it. The compatibility singleton
 * passes none and keeps the switched behaviour. Since #1320 the settings reading and the model menu are held
 * too, but still read through the live repository while one is connected, because that subscription is what
 * asks the connection for them; the holder answers only in the gap between connections.
 *
 * Emits **no logs**, consistent with the coordinator/pump/supervisor posture: it moves only object
 * references and already-decoded domain values, and must not log repo contents or the not-connected
 * event.
 */
class StableConversationRepository(
    private val currentRepository: StateFlow<ConversationRepository?>,
    private val heldReadings: HostReadings? = null,
) : ConversationRepository {
    /**
     * Switch a cold read over [currentRepository]: delegate to the live repository's [select] flow, or
     * emit [whenAbsent] once while no connection is live. [flatMapLatest] cancels the prior inner flow
     * on every change, so a reconnect drops the previous connection's projection (AC #2) and a `null`
     * falls back to the empty projection, resuming when the next connection arrives (AC #3).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> switchToLive(
        whenAbsent: T,
        select: (ConversationRepository) -> Flow<T>,
    ): Flow<T> = currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }

    /**
     * [switchToLive] for a reading the host also holds (#1320): the live repository's [select] flow while a
     * connection is live, [held] while none is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> switchToLiveOrHeld(
        held: Flow<T>,
        select: (ConversationRepository) -> Flow<T>,
    ): Flow<T> = currentRepository.flatMapLatest { repo -> repo?.let(select) ?: held }

    /** The currently-live repository snapshot, or [IllegalStateException] if no connection is live. */
    private val live: ConversationRepository
        get() = currentRepository.value ?: throw IllegalStateException(NOT_CONNECTED)

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        switchToLive(emptyList()) { it.observeConversations(filter) }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        switchToLive(emptyList()) { it.observeMessages(conversationId) }

    override fun observeLastMessage(conversationId: String): Flow<Message?> =
        switchToLive<Message?>(null) { it.observeLastMessage(conversationId) }

    override fun observeStall(conversationId: String): Flow<Boolean> = switchToLive(false) { it.observeStall(conversationId) }

    override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
        switchToLive(emptyList()) { it.observeQueue(conversationId) }

    override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> =
        switchToLive(ApiRetryStatus.NotRetrying) { it.observeApiRetry(conversationId) }

    override fun observeCompacting(conversationId: String): Flow<Boolean> = switchToLive(false) { it.observeCompacting(conversationId) }

    /** The live connection's held turn phase (#1313); idle with no connection, and a new one starts idle. */
    override fun observeTurnPhase(conversationId: String): Flow<LiveSessionEvent.TurnState.Phase> =
        switchToLive(LiveSessionEvent.TurnState.Phase.Idle) { it.observeTurnPhase(conversationId) }

    override fun observeResetting(conversationId: String): Flow<ResetStatus?> =
        switchToLive<ResetStatus?>(null) { it.observeResetting(conversationId) }

    /**
     * The announced-model reading for [conversationId] (#890). With [heldReadings] (#1317) it reads the host's
     * held reading, which survives reconnects and the gap between them and is dropped when the pairing ends.
     * Without it, it switches over the live connection: [flatMapLatest] drops the previous connection's
     * projection and the gap reports nothing.
     */
    override fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> =
        heldReadings?.observeAnnouncedModel(conversationId)
            ?: switchToLive<AnnouncedModel?>(null) { it.observeAnnouncedModel(conversationId) }

    /** The session-facts reading for [conversationId] (#890), held or switched as [observeAnnouncedModel] is. */
    override fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> =
        heldReadings?.observeSessionFacts(conversationId) ?: switchToLive<SessionFacts?>(null) { it.observeSessionFacts(conversationId) }

    /**
     * The context-usage reading for [conversationId] (#945), held or switched as [observeAnnouncedModel] is. Held,
     * it survives a reconnect until the open thread's [requestContextUsage] answer (#1410) or the next turn's push
     * replaces it; without [heldReadings] it stays absent until one of them lands.
     */
    override fun observeContextUsage(conversationId: String): Flow<ContextUsage?> =
        heldReadings?.observeContextUsage(conversationId) ?: switchToLive<ContextUsage?>(null) { it.observeContextUsage(conversationId) }

    /** Switched, never held (#1343): the MCP reading is per connection, so a reconnect starts from nothing. */
    override fun observeMcpStatus(conversationId: String): Flow<McpStatus> =
        switchToLive(McpStatus()) { it.observeMcpStatus(conversationId) }

    /**
     * The five MCP commands (#1343) forward through [currentRepository]`.value`, the [refreshSessionSettings]
     * shape, not [live]: with no connection nothing can be sent, so nothing is set and nothing throws.
     */
    override fun requestMcpStatus(conversationId: String) {
        currentRepository.value?.requestMcpStatus(conversationId)
    }

    override fun reconnectMcpServer(
        conversationId: String,
        serverName: String,
    ) {
        currentRepository.value?.reconnectMcpServer(conversationId, serverName)
    }

    override fun toggleMcpServer(
        conversationId: String,
        serverName: String,
        enabled: Boolean,
    ) {
        currentRepository.value?.toggleMcpServer(conversationId, serverName, enabled)
    }

    override fun endMcpReconnectWait(conversationId: String) {
        currentRepository.value?.endMcpReconnectWait(conversationId)
    }

    override fun endMcpToggleWait(conversationId: String) {
        currentRepository.value?.endMcpToggleWait(conversationId)
    }

    /**
     * The files offered in [conversationId] on the owner host's live connection (#898). The switch is what
     * makes offers live-only across connections: a reconnect or a host switch drops the previous
     * connection's offers rather than carrying one host's files over to the next.
     */
    override fun observeAttachmentOffers(conversationId: String): Flow<List<AttachmentOffer>> =
        switchToLive(emptyList()) { it.observeAttachmentOffers(conversationId) }

    /**
     * The usage-limit reading for [conversationId] (#802). With [heldReadings] (#1317) it reads the host's held
     * reading, kept across reconnects and dropped when the pairing ends, so one account's quota posture is never
     * attributed to another host or a later pairing. Without it, it switches over the live connection like
     * every other cold read. `null` is the same "nothing to read" value an unheard conversation produces, so a
     * consumer has one absent case, not two.
     */
    override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> =
        heldReadings?.observeUsageLimit(conversationId) ?: switchToLive<UsageLimitReading?>(null) { it.observeUsageLimit(conversationId) }

    /**
     * The thinking-progress reading for [conversationId] (#801), switched over the live connection like
     * every other cold read — and here the switch is the **whole clearing mechanism for a reconnect**,
     * not just plumbing. The reading has no falling edge on the wire, so a retained one would report the
     * depth of a think that has since finished; [flatMapLatest] dropping the previous connection's
     * projection the instant the connection changes is what makes that structurally impossible, with no
     * clear written into any demux arm. `null` while none is live is the same "no reading" value an
     * unheard conversation produces, so a consumer has one absent case, not two.
     */
    override fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> =
        switchToLive<ThinkingProgress?>(null) { it.observeThinkingProgress(conversationId) }

    /**
     * The settings reading for [conversationId] (#590), switched over the live connection like every
     * other cold read — and here the switch is the **host-isolation mechanism**, not just plumbing:
     * [flatMapLatest] drops the previous connection's read the instant the connection changes, and the
     * new repository issues a fresh one against the new host. `null` while none is live is the same
     * "reading unavailable" value a failed read produces, so a consumer has one absent case, not two.
     *
     * With [heldReadings] (#1320) the gap reports the host's held reading instead, invalidated as the next
     * connection's head will be, so the footer keeps its model and effort while the permission mode waits for
     * the new connection's reply. The holder is this host's alone and dropped with its pairing, so the
     * isolation above still holds.
     */
    override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
        heldReadings?.let { held ->
            switchToLiveOrHeld(held.observeHeldSessionSettings(conversationId)) { it.observeSessionSettings(conversationId) }
        } ?: switchToLive<SessionSettings?>(null) { it.observeSessionSettings(conversationId) }

    /**
     * The model menu for [conversationId] (#791), switched over the live connection like every other
     * cold read — and here the switch is the **host-isolation mechanism**: the published vocabulary
     * varies by machine and account, so [flatMapLatest] dropping the previous connection's projection is
     * what stops one host's models being offered for another's conversation. `null` while none is live
     * is the same "unavailable" value an unheard conversation produces, so a consumer has one absent
     * case, not two.
     *
     * With [heldReadings] (#1320) the gap reports the host's held menu, and a new connection starts from it
     * while it asks again; the holder is this host's alone and dropped with its pairing.
     */
    override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> =
        heldReadings?.let { held ->
            switchToLiveOrHeld(held.observeModelMenu(conversationId)) { it.observeModelMenu(conversationId) }
        } ?: switchToLive<ModelMenu?>(null) { it.observeModelMenu(conversationId) }

    /**
     * The slash-command menu for [conversationId] (#882), held or switched as [observeAnnouncedModel] is (#1317).
     * Either way one host's commands are never offered for another's conversation. `null` is the same "no
     * frame heard" value an unheard conversation produces.
     */
    override fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> =
        heldReadings?.observeSlashCommandMenu(conversationId)
            ?: switchToLive<SlashCommandMenu?>(null) { it.observeSlashCommandMenu(conversationId) }

    /**
     * Invalidate [conversationId]'s settings reading on the live repository (#590). Deliberately routed
     * through [currentRepository]`.value` rather than [live]: an invalidation with no connection is a
     * no-op, not an [IllegalStateException] — the caller has nothing to recover, and the next connection
     * re-reads on subscription regardless. The one facade method that must not throw.
     */
    override fun refreshSessionSettings(conversationId: String) {
        currentRepository.value?.refreshSessionSettings(conversationId)
    }

    /** Ask the live repository for a fresh context reading (#1410); a no-op with no connection, like [refreshSessionSettings]. */
    override fun requestContextUsage(conversationId: String) {
        currentRepository.value?.requestContextUsage(conversationId)
    }

    /**
     * Delegates the capability to the live repository's value, reporting `false` when no connection is
     * live (fail-safe-deny — the safe answer for a gating consumer is "hide the actions"). This is the
     * plain-`Boolean` analog of [observeStall]'s `switchToLive(false)`: a getter that re-reads
     * [currentRepository] `.value` on every access, like [live], so a connection landing after facade
     * construction is reflected — never a construction-time snapshot.
     */
    override val mutationsSupported: Boolean
        get() = currentRepository.value?.mutationsSupported ?: false

    override fun recentWorkspaces(): Flow<List<String>> = switchToLive(emptyList()) { it.recentWorkspaces() }

    override suspend fun createDiscussion(workspace: String?): Conversation = live.createDiscussion(workspace)

    override suspend fun createChannel(
        name: String,
        workspace: String?,
    ): Conversation = live.createChannel(name, workspace)

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation = live.promote(conversationId, name, workspace)

    override suspend fun archive(conversationId: String): Unit = live.archive(conversationId)

    override suspend fun unarchive(conversationId: String): Unit = live.unarchive(conversationId)

    override suspend fun setMuted(
        conversationId: String,
        muted: Boolean,
    ): Unit = live.setMuted(conversationId, muted)

    override suspend fun delete(conversationId: String): Unit = live.delete(conversationId)

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = live.rename(conversationId, name)

    override suspend fun setSessionSettings(
        sessionId: String,
        model: String?,
        effort: String?,
        yolo: Boolean?,
        permissionMode: String?,
    ) = live.setSessionSettings(sessionId, model, effort, yolo, permissionMode)

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = live.startNewSession(conversationId, workspace)

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = live.changeWorkspace(conversationId, workspace)

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message = live.sendMessage(conversationId, text)

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<MessageAttachment>,
    ): Message = live.sendMessage(conversationId, text, attachments)

    override suspend fun createWorkspaceFolder(name: String): String = live.createWorkspaceFolder(name)

    /**
     * The workspace verbs (#663) go to this host's live repository with the path and label untouched,
     * so the facade is the host targeting and adds no routing of its own.
     */
    override suspend fun renameWorkspace(
        path: String,
        label: String?,
    ): Unit = live.renameWorkspace(path, label)

    override suspend fun archiveWorkspace(path: String): Unit = live.archiveWorkspace(path)

    override suspend fun requestScreenSnapshot(conversationId: String): String = live.requestScreenSnapshot(conversationId)

    override suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ): Unit = live.dropQueuedMessage(conversationId, queuedMessageId)

    /**
     * One-shot delegation with the cursor and limit forwarded **verbatim** (#623) — a page is per-ask
     * data, so there is nothing to project, switch or cache here. With no connection live this throws
     * [IllegalStateException] before any frame is built, the same type the delegate throws on a
     * not-`Open` pump, so a walking caller handles one type either way.
     */
    override suspend fun requestHistory(
        conversationId: String,
        cursor: String,
        limit: Int,
    ): HistoryPage = live.requestHistory(conversationId, cursor, limit)

    /**
     * One-shot delegation of the system-prompt read and write (#823) to the live repository — the one
     * for this host's connection, so the id and value pass through verbatim with no routing here. With
     * no connection live both throw [IllegalStateException] before any frame is built.
     */
    override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading = live.requestSystemPrompt(conversationId)

    override suspend fun setSystemPrompt(
        conversationId: String,
        systemPrompt: String?,
    ): Unit = live.setSystemPrompt(conversationId, systemPrompt)

    /**
     * Uploads on the repository live at call entry (#829), so a later connection change never moves an
     * upload in flight. With none live this is a result rather than [IllegalStateException]: the upload
     * contract reports every failure as a value, and an oversized file says not to retry even here.
     */
    override suspend fun uploadAttachment(
        conversationId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
    ): AttachmentUploadResult {
        val repository =
            currentRepository.value
                ?: return if (AttachmentUploadLimit.fits(bytes.size)) {
                    AttachmentUploadResult.ReconnectRequired
                } else {
                    AttachmentUploadResult.TooLarge
                }
        return repository.uploadAttachment(conversationId, bytes, filename, mimeType, onProgress)
    }

    /** Fetches on the repository live at call entry (#899), like [uploadAttachment]; none live is a retryable failure. */
    override suspend fun fetchAttachment(
        conversationId: String,
        attachmentId: String,
    ): AttachmentFetchResult =
        currentRepository.value?.fetchAttachment(conversationId, attachmentId) ?: AttachmentRetrievalResult.Unavailable

    /** Reads on the repository live at call entry (#1049), like [fetchAttachment]; none live is a retryable failure. */
    override suspend fun readWorkspaceFile(
        conversationId: String,
        path: String,
    ): AttachmentFetchResult = currentRepository.value?.readWorkspaceFile(conversationId, path) ?: AttachmentRetrievalResult.Unavailable

    private companion object {
        const val NOT_CONNECTED = "No live relay connection"
    }
}
