package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalAction
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.scopedTo
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.LiveRefusalEvent
import de.pyryco.mobile.data.repository.McpStatus
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ReplySuggestion
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.data.repository.projectDisplay
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.SwitchBackOffer
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditor
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.conversations.components.attachmentTarget
import de.pyryco.mobile.ui.conversations.components.loadsOnShow
import de.pyryco.mobile.ui.conversations.components.turnRecoveryNotice
import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall
import de.pyryco.mobile.ui.conversations.list.ChannelEditorController
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.workspace.workspaceDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

private const val THREAD_HISTORY_PAGE_SIZE = 200

/**
 * The one `history.*` wire code this screen branches on (#778) — the daemon refused the cursor, so the
 * walk's next ask starts from the newest page instead of surfacing a dead end (#1352). Every other code,
 * known or not, falls through to a failure. The code vocabulary's SSOT is the protocol document, not
 * this constant.
 */
private const val HISTORY_INVALID_CURSOR = "history.invalid_cursor"

/** The permission options that allow (#818): the only answers that may carry the session grant. */
private val ALWAYS_ALLOW_OPTION_IDS = setOf("allow_once", "allow_always")

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    // #789: the app-scoped store holding this chat's unsent composer text. Required, unlike the inert
    // defaults below: a default would hand every ViewModel its own store, which is exactly the
    // destination-scoped ownership this ticket removes — and a miswire would reproduce it invisibly.
    private val draftStore: ComposerDraftStore,
    // #406: the coordinator's reconnection-surviving live-event seam, folded into the thread rows and
    // [turnOutcome]; the turn flags read the repository's held phase instead (#1313). Defaulted
    // to an empty flow so the fake-backed graph + existing tests stay inert.
    liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
    // #492: the host coordinator's process-scoped modal fold (#437/#445), folded once at the coordinator
    // layer. Since #1337 it holds every prompt outstanding on the host, whichever conversation raised it;
    // #816 scopes it to this thread as [currentModal]. Defaulted to an empty host so the fake-backed Koin
    // graph + non-modal tests stay inert.
    private val hostModal: StateFlow<HostModalState> = MutableStateFlow(HostModalState()),
    // #451: the outbound modal-send path → the coordinator's passthrough to the connection-scoped concrete
    // repo (RelayRepositoryCoordinator.answerModal / cancelModal). Defaulted no-ops so the fake-backed Koin
    // graph + existing ThreadViewModel tests stay inert. The VM holds only these two suspend lambdas, never
    // the facade-bypassing concrete repo or the coordinator (the outbound analog of the modalEvents flow).
    // #818: alwaysAllow grants the modal's offered rules for the session; see onModalOption for when it is set.
    private val answerModal: suspend (modalId: String, optionId: String, alwaysAllow: Boolean) -> Unit = { _, _, _ -> },
    private val cancelModal: suspend (modalId: String) -> Unit = { _ -> },
    // #458: the outbound `interrupt` send path → the coordinator's passthrough (RelayRepositoryCoordinator
    // .interrupt). Defaulted no-op so the fake-backed Koin graph + existing tests stay inert. The VM holds
    // only this suspend lambda, never the coordinator/concrete repo — same posture as answerModal/cancelModal.
    private val interrupt: suspend (conversationId: String) -> Unit = {},
    // #661: the coordinator's per-conversation question batch and its two sends (#822/#825). Defaulted
    // inert like answerModal/cancelModal; the question path never touches those two.
    questionBatch: (conversationId: String) -> Flow<QuestionBatch?> = { flowOf(null) },
    private val answerQuestionBatch: suspend (questionBatchId: String, answers: List<QuestionAnswer>) -> Unit = { _, _ -> },
    private val refuseQuestionBatch: suspend (questionBatchId: String) -> Unit = {},
    questionDraftStore: QuestionDraftStore? = null,
    // #1306: the app-scoped session-grant drafts, so Back keeps the checkbox for the same request. Absent in
    // tests and the demo host, where a private store stands in.
    permissionDraftStore: PermissionDraftStore? = null,
    // #1345: the app-scoped acknowledgements of failed MCP servers, so a tapped notice stays quiet when the chat
    // is reopened. Absent in tests and the demo host, where a private holder stands in.
    mcpFailureAcknowledgements: McpFailureAcknowledgements? = null,
    // #678: the coordinator's per-conversation background-task roster and its live count (#677). Read
    // through the destination-bound coordinator. Demo defaults remain inert.
    backgroundTasks: (conversationId: String) -> Flow<BackgroundTaskRoster?> = { flowOf(null) },
    backgroundTaskCount: (conversationId: String) -> Flow<Int> = { flowOf(0) },
    backgroundTaskStopSupported: Flow<Boolean> = flowOf(false),
    backgroundTaskStopRefusals: (conversationId: String) -> Flow<String> = { emptyFlow() },
    private val stopBackgroundTask: suspend (conversationId: String, taskId: String) -> Result<Unit> = { _, _ ->
        Result.failure(IllegalStateException("Background task stop unavailable"))
    },
    // #861: whether this thread's host has a live repository published — for a relay host, the
    // coordinator's `currentRepository` being non-null, which happens only after the Noise handshake,
    // later than the socket-level `Connected` [connectionStateSource] reports. Gates the history ask
    // (#1352), and keys the #1309 settings re-read and the #1410 context-usage ask. Defaulted to
    // always-available, as the demo path's fake repository is.
    private val repositoryAvailable: Flow<Boolean> = flowOf(true),
    // #843: whether this thread's own host rejected the saved pairing — the relay leg's distinct state,
    // which [connectionStateSource]'s legacy four cases fold into Offline. Defaulted to never, as the
    // demo path's fake host is never rejected.
    pairingRejected: Flow<Boolean> = flowOf(false),
    // #932: reads a pending attachment's bytes through its content URI at send time. Defaulted to a reader
    // that can read nothing, so the fake-backed graph and existing tests stay inert; production passes
    // ContentResolverAttachmentReader.
    private val attachmentReader: AttachmentReader = AttachmentReader { AttachmentRead.Unreadable },
    // #686: the phone's one remembered effort level, recalled once per opening by [effortRecall].
    // Defaulted to a store that remembers nothing, so the demo path and existing tests stay inert.
    rememberedEffort: RememberedEffortStore = RememberedEffortStore.None,
    // #1222: production persists an acknowledged model choice; fake and demo threads stay inert.
    private val rememberModel: suspend (String) -> Unit = {},
    // #1027: where a markdown attachment's kept file is read before the reader opens.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // #1340: folds this phone's own prompt actions into [hostModal] at once (the coordinator's
    // recordModalAction). Defaulted inert, so the fake-backed graph and existing tests keep their prompts.
    private val recordModalAction: (ModalAction) -> Unit = {},
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    /**
     * The host's prompts as this thread sees them (#816, #1337): this conversation's first outstanding
     * prompt, else its latest dismissal, else [ModalUiState.Hidden] (see [HostModalState.scopedTo]). Another
     * conversation's prompt never shows here. Seeded from the host's current value and collected `Eagerly`,
     * so `.value` is right from construction.
     */
    val currentModal: StateFlow<ModalUiState> =
        hostModal
            .map { it.scopedTo(conversationId) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, hostModal.value.scopedTo(conversationId))

    // #789: the owning host, read off the same handle the destination factory reads it from. Paired with
    // [conversationId] it keys this chat's composer draft — never the conversation id alone, which is
    // host-local and would collide across hosts. Both route arguments are required path segments and
    // `HostDestination` rejects an unresolvable owner, so this is never blank in production.
    private val serverId: String =
        savedStateHandle.get<String>("serverId").orEmpty()

    /**
     * This chat's unsent composer text (#789), `""` when it has none. Read from the app-scoped
     * [draftStore] rather than held here, so it survives this destination's composition — and,
     * `Eagerly`, so the seeded initial value and the first emission can never disagree.
     *
     * A [StateFlow] drops equal consecutive values, so another chat's or another host's edit cannot
     * recompose this composer. Deliberately **not** a [ThreadUiState] field: the state `combine` is at
     * its five-arity ceiling, and a sibling flow matches how `connectionState` / `isBusy` /
     * `currentModal` are already exposed.
     */
    val draft: StateFlow<String> =
        draftStore.drafts
            .map { it[serverId]?.get(conversationId).orEmpty() }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                draftStore.draftFor(serverId, conversationId),
            )

    /**
     * This chat's pending attachments (#932) in the order added, empty when it has none. Read from
     * [draftStore] beside [draft], exposed the same way and for the same reasons.
     */
    val pendingAttachments: StateFlow<List<PendingAttachment>> =
        draftStore.attachments
            .map { it[serverId]?.get(conversationId).orEmpty() }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                draftStore.attachmentsFor(serverId, conversationId),
            )

    private val _attachmentsSending = MutableStateFlow(false)

    /**
     * Whether a send carrying attachments is uploading or sending right now (#933). The strip shows it, and
     * [sendMessage] refuses a second tap while it holds, so one snapshot is never uploaded twice. Written on
     * the main thread only, and cleared however the send ends.
     */
    val attachmentsSending: StateFlow<Boolean> = _attachmentsSending.asStateFlow()

    private val _attachmentUploadProgress = MutableStateFlow<AttachmentUploadProgress?>(null)

    /**
     * The figure of the upload running now (#1327), or `null`. It names one entry and lives for one upload:
     * cleared when that upload is stored or fails, and however the send ends. Uploads under
     * [ATTACHMENT_PROGRESS_MIN_CHUNKS] never publish one.
     */
    val attachmentUploadProgress: StateFlow<AttachmentUploadProgress?> = _attachmentUploadProgress.asStateFlow()

    private val attachmentRefusalChannel = Channel<AttachmentRefusal>(capacity = Channel.BUFFERED)

    /**
     * One notice per pick that had entries refused (#933), with how many were refused for each reason. Counts
     * only, never a name, URI or type, so the snackbar it drives shows fixed local text.
     */
    val attachmentRefusals: Flow<AttachmentRefusal> = attachmentRefusalChannel.receiveAsFlow()

    private val attachmentSendFailureChannel = Channel<AttachmentSendFailure>(capacity = Channel.BUFFERED)

    /**
     * One notice per send that stopped at a failed read or upload (#1325), naming why. A reason only, never a
     * name, URI or the daemon's code, so the snackbar it drives shows fixed local text.
     */
    val attachmentSendFailures: Flow<AttachmentSendFailure> = attachmentSendFailureChannel.receiveAsFlow()

    private val sentMessageChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * One signal per send the daemon accepted, of text or attachments (#1314), desktop's `onMessageSent`. The
     * screen follows the newest end on it. Conflated: sends accepted before the screen collects follow once.
     */
    val sentMessages: Flow<Unit> = sentMessageChannel.receiveAsFlow()

    private val _attachmentStates = MutableStateFlow<Map<String, AttachmentViewState>>(emptyMap())

    /**
     * Each shown message attachment's state by id (#984). An id is absent until its load starts: when its
     * row is first shown ([onAttachmentShown]), or for a file not fetched on sight, when it is tapped
     * ([onAttachmentRequested], #1329). The screen draws an absent id as loading, or that file as its ready
     * row. A sibling flow for the same reason as [draft].
     */
    val attachmentStates: StateFlow<Map<String, AttachmentViewState>> = _attachmentStates.asStateFlow()

    // #1329: the action a tap asked for, by id, while that tap's load runs. Main thread only, like
    // [_attachmentsSending]: written by [onAttachmentRequested], removed when the load settles.
    private val pendingAttachmentRequests = mutableMapOf<String, Pair<MessageAttachment, AttachmentAction>>()

    private val attachmentLoadChannel = Channel<AttachmentLoaded>(capacity = Channel.BUFFERED)

    /**
     * One open or save per tapped file that loaded ready (#1329), for the screen to run through its
     * attachment actions. Carries the loaded source, so it never waits on [attachmentStates] recomposing.
     */
    val attachmentLoads: Flow<AttachmentLoaded> = attachmentLoadChannel.receiveAsFlow()

    // #507: snapshot the repository's mutation-capability once at construction (the mode is static per
    // build config — a Koin fake-vs-relay swap, never a runtime toggle). Reading through the facade here
    // is where its null-connection → false fail-safe-deny takes effect. Captured once so the combine value
    // and the initialValue can never disagree.
    private val mutationsSupported: Boolean = repository.mutationsSupported

    private val pendingWorkspacePicker = MutableStateFlow(false)

    private val pendingRenameDialog = MutableStateFlow(false)

    private val pendingSaveAsChannelDialog = MutableStateFlow<SaveAsChannelDialogState?>(null)

    private val pendingChannelInfo = MutableStateFlow(false)

    /**
     * The Channel info sheet's System prompt editor (#1342), present only while the sheet is open, so every
     * open starts from a fresh read. Bound to [repository], the reconnect-surviving facade.
     */
    private val promptEditor = MutableStateFlow<SystemPromptEditor?>(null)

    private val pendingDeleteConfirm = MutableStateFlow(false)

    /**
     * Edit channel from a channel's menu (#1561): the list's machine, bound to this thread's own repository.
     * Its OK and Archive follow [hostAvailable], as the list's follow its host's connection, and a confirmed
     * archive leaves the thread as the menu's Archive does.
     */
    private val channelEditor =
        ChannelEditorController(
            scope = viewModelScope,
            isHostLive = { hostAvailable.value },
            repositoryFor = { repository },
            awaitRepository = {
                hostAvailable.first { it }
                repository
            },
            onArchived = { leaveForList() },
        )

    private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()

    /** Set once the thread has left for an archived row (#1399), so its own archive pops only once. */
    private val leftArchived = AtomicBoolean(false)

    /** Sends the archive exit's [ThreadNavigation.PopBack] the first time only; returns whether it sent. */
    private suspend fun leaveForList(): Boolean {
        if (!leftArchived.compareAndSet(false, true)) return false
        navigationChannel.send(ThreadNavigation.PopBack)
        return true
    }

    /** A model tap whose write has not settled, or `null`. A matching fresh settings reading confirms it;
     *  a rejected write or lost settings context clears it. */
    private val pendingModel = MutableStateFlow<String?>(null)

    /** The [pendingModel] twin for effort (#807). */
    private val pendingEffort = MutableStateFlow<String?>(null)

    /**
     * This thread's switch-back offer (#1360), or `null`: at most one, armed only by a live session-scoped
     * fallback refusal ([onLiveRefusalEvent]), never by a row, so a restored refusal cannot arm it. Written on
     * Main only.
     */
    private val refusalOffer = MutableStateFlow<RefusalOffer?>(null)

    /** This opening's recall of the remembered effort (#686); its write is [startEffortRecall]. */
    private val effortRecall = EffortRecall(viewModelScope, rememberedEffort, ::startEffortRecall)

    /**
     * This conversation's saved run configuration (#590), the authority for the displayed model and
     * effort and for the session id a write addresses (#807).
     *
     * The `onEach` confirms a pending model only when a fresh reading names that raw value. A stale reply
     * cannot briefly put the old model back. A lost reading clears the pending context. The acknowledgement
     * echoes only the input session id and confirms nothing.
     * It rides this flow rather than a second collector because `observeSessionSettings` is cold and
     * per-collector — a separate subscription would send a second `request_session_settings` frame on
     * every thread entry.
     */
    private val sessionSettings: Flow<SessionSettings?> =
        repository
            .observeSessionSettings(conversationId)
            .onEach { reading ->
                // #1320: a held reading heads the subscription on a same-host reconnect where `null` used to,
                // so it ends the pending context the same way.
                val lost = reading == null || reading.held
                if (lost || pendingModel.value == reading?.model) pendingModel.value = null
                pendingEffort.value = null
                // #650: a `null` or held reading heads every new subscription (host switch, owning-host
                // reconnect) and `null` follows a failed read; a reading for another session means the session
                // was replaced. Either way the permission write belongs to a context that is gone. The check
                // runs before the tick below so the settle loop never sees a reading from the new context.
                permissionWrite?.let { write ->
                    if (lost || reading?.sessionId != write.sessionId) cancelPermissionWrite()
                }
                settingsReadings.update { SettingsReading(it.seq + 1, reading) }
            }

    /** The permission mode a write asked for (#650), while its request or settle runs; else `null`. */
    private val pendingPermission = MutableStateFlow<String?>(null)

    /** The outstanding permission write (#650), or `null`. At most one; set and cleared on Main only. */
    private var permissionWrite: PermissionWrite? = null

    /**
     * Every reading [sessionSettings] delivers, numbered (#650), so the settle loop can wait for the reply
     * to its own refresh without opening a second subscription — which would send a second
     * `request_session_settings` per trigger. The number moves on equal readings too.
     */
    private val settingsReadings = MutableStateFlow(SettingsReading(0L, null))

    /**
     * What claude says it runs (#891): the announced model and its build, each made inert here. Both #890
     * readings are per conversation and cleared by the repository on a session transition, so nothing
     * here tracks staleness. `SessionFacts.permissionMode` is claude's claim and is deliberately not read.
     * The second value is the raw announced model (#1308), the inherited mark's comparison key; a cut value
     * is incomplete and is left out, as [toChoice] leaves out a cut `resolvedModel`.
     */
    private val runningModel: Flow<Pair<ThreadRunningModel, String>> =
        combine(
            repository.observeAnnouncedModel(conversationId),
            repository.observeSessionFacts(conversationId),
        ) { announced, facts ->
            ThreadRunningModel(
                model = announced?.let { reportedText(it.model, it.truncated) },
                build =
                    facts?.let {
                        reportedText(it.claudeCodeVersion, CLAUDE_CODE_VERSION_FIELD in it.truncatedFields.orEmpty())
                    },
            ) to announced?.takeUnless { it.truncated }?.model.orEmpty()
        }

    /** Last selection on this connection; empty summaries cannot undo a known session replacement. */
    private var lastKnownSessionId = ""

    /**
     * The conversation list, shared (#1110) so [state] and [conversationAgent] ride one upstream
     * subscription: the remote repository sends a `list_conversations` request on every subscription.
     *
     * #1399: a list showing this row archived, from any client, leaves for the list. Upstream of `shareIn`
     * so it runs once per emission. A row that disappears, or is only renamed or moved, stays (desktop #653).
     */
    private val conversations: Flow<List<Conversation>> =
        repository
            .observeConversations(ConversationFilter.All)
            .onEach { list ->
                if (hostAvailable.value) {
                    list.firstOrNull { it.id == conversationId }?.currentSessionId?.takeIf { it.isNotEmpty() }?.let {
                        lastKnownSessionId = it
                    }
                }
                if (list.any { it.id == conversationId && it.archived } && leaveForList()) {
                    RelayLog.d { "event=thread_left_archived" }
                }
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /** The agent that runs this conversation (#1110); Claude while the list does not hold it yet. */
    private val conversationAgent: Flow<ConversationAgent> =
        conversations
            .map { list -> list.firstOrNull { it.id == conversationId }?.agent ?: ConversationAgent.Claude }
            .distinctUntilChanged()

    /**
     * The run-configuration arm of [state] (#807). Five inputs, which is exactly Kotlin's typed `combine`
     * ceiling — the reason this stays one arm of the five-arm `state` combine instead of needing a sixth
     * or the sibling-[StateFlow] shape [draft] uses. [runningModel] joins by a second, two-arm combine, and
     * Claude's reported context usage (#946) by a third, where [contextPercent] computes the one value the footer
     * and the Status sheet both show (#1411); the repository clears that reading itself.
     */
    private val runConfigFlow: Flow<ThreadRunConfig> =
        combine(
            sessionSettings,
            // #1110: the agent joins by a chained combine, since this one is at the typed ceiling. Filtering
            // here, before [runConfig] caps the rows, is what makes the hidden count the filtered list's.
            repository.observeModelMenu(conversationId).combine(conversationAgent) { menu, agent ->
                menu?.forAgent(agent) to agent
            },
            pendingModel,
            pendingEffort,
            pendingPermission,
        ) { settings, menuAndAgent, model, effort, permission ->
            // #1411: the settings ride along to the context-usage combine, which falls back to their token pair.
            runConfig(settings, menuAndAgent.first, menuAndAgent.second, model, effort, permission) to settings
        }.combine(runningModel) { (config, settings), (running, announced) ->
            config.copy(running = running, announcedModel = announced) to settings
        }.combine(repository.observeContextUsage(conversationId)) { (config, settings), usage ->
            config.copy(contextPercent = contextPercent(usage, settings))
        }

    /**
     * This conversation's published slash-command menu (#882), feeding both the Actions menu's absent
     * commands (#884) and the composer's type-ahead (#885). Seeded `null` so a repository that never emits
     * cannot stall [state].
     */
    private val slashCommandMenu: Flow<SlashCommandMenu?> =
        repository
            .observeSlashCommandMenu(conversationId)
            .onStart { emit(null) }
            .distinctUntilChanged()

    /**
     * This conversation's background-task roster and live count on this thread's host (#678). Each arm is
     * seeded so a source that never emits cannot stall [state]. The task strings stay inside the roster:
     * nothing here reads or logs their prose. Controls use only opaque task ids.
     */
    private data class TaskControls(
        val roster: BackgroundTaskRoster? = null,
        val count: Int = 0,
        val supported: Boolean = false,
        val expanded: Set<String> = emptySet(),
        val pending: Set<String> = emptySet(),
    )

    private val taskControlLock = Any()
    private val taskControls = MutableStateFlow(TaskControls())
    private val taskStopAttempts = mutableMapOf<String, Any>()

    init {
        viewModelScope.launch {
            backgroundTaskStopRefusals(conversationId).collect { taskId ->
                synchronized(taskControlLock) {
                    if (taskStopAttempts.remove(taskId) != null) {
                        taskControls.value = taskControls.value.copy(pending = taskControls.value.pending - taskId)
                        RelayLog.d { "event=background_task_action code=refused" }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                backgroundTasks(conversationId).onStart { emit(null) },
                backgroundTaskCount(conversationId).onStart { emit(0) },
                backgroundTaskStopSupported.onStart { emit(false) },
            ) { roster, count, supported -> Triple(roster, count, supported) }.collect { (roster, count, supported) ->
                synchronized(taskControlLock) {
                    val eligible =
                        if (supported) {
                            roster
                                ?.tasks
                                ?.filterNot { it.isFinished }
                                ?.mapTo(
                                    HashSet(),
                                ) { it.taskId }
                                .orEmpty()
                        } else {
                            emptySet()
                        }
                    taskStopAttempts.keys.retainAll(eligible)
                    val old = taskControls.value
                    taskControls.value =
                        TaskControls(roster, count, supported, old.expanded.intersect(eligible), old.pending.intersect(eligible))
                    if (old.pending != taskControls.value.pending || old.expanded != taskControls.value.expanded) {
                        RelayLog.d { "event=background_task_action code=roster_cleanup" }
                    }
                }
            }
        }
    }

    private fun toggleBackgroundTask(taskId: String) {
        synchronized(taskControlLock) {
            val current = taskControls.value
            if (!current.supported || current.roster?.tasks?.any { it.taskId == taskId && !it.isFinished } != true) return
            val opened = taskId !in current.expanded
            taskControls.value = current.copy(expanded = if (opened) current.expanded + taskId else current.expanded - taskId)
            RelayLog.d { "event=background_task_row expanded=$opened" }
        }
    }

    private fun sendBackgroundTaskStop(taskId: String) {
        val attempt = Any()
        synchronized(taskControlLock) {
            val current = taskControls.value
            if (!current.supported ||
                taskId !in current.expanded ||
                taskId in current.pending ||
                current.roster?.tasks?.any { it.taskId == taskId && !it.isFinished } != true
            ) {
                return
            }
            taskStopAttempts[taskId] = attempt
            // Before launch: a second tap cannot enqueue a second send, even before state recomposes.
            taskControls.value = current.copy(pending = current.pending + taskId)
            RelayLog.d { "event=background_task_action code=pending" }
        }
        viewModelScope.launch {
            if (synchronized(taskControlLock) { taskStopAttempts[taskId] !== attempt }) return@launch
            val result = stopBackgroundTask(conversationId, taskId)
            if (result.isFailure) {
                synchronized(taskControlLock) {
                    if (taskStopAttempts[taskId] === attempt) {
                        taskStopAttempts.remove(taskId)
                        taskControls.value = taskControls.value.copy(pending = taskControls.value.pending - taskId)
                        RelayLog.d { "event=background_task_action code=send_failed" }
                    }
                }
            }
        }
    }

    /**
     * Claude's latest estimate of this session's cost (#1346): the newest positive finite `cost_usd_total`
     * on this conversation's `turn_end`s. Each replaces the last (the value is already a running total, so
     * it is never summed), and an absent, zero, negative or non-finite one leaves the earlier value standing.
     * Collected eagerly so it lasts as long as this view model, as desktop keeps it while the timeline lives;
     * `WhileSubscribed` would forget it whenever the screen stops collecting. Claude's claim: never logged.
     */
    private val sessionCostUsd: StateFlow<Double?> =
        liveSessionEvents
            .filterIsInstance<LiveSessionEvent.TurnEnd>()
            .mapNotNull { event ->
                event.costUsdTotal?.takeIf { event.conversationId == conversationId && it.isFinite() && it > 0 }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Channel info's Session readings (#1346): what Claude reports about its session, with the permission
     * mode [runningModel] leaves unread, and [sessionCostUsd]. A second `observeSessionFacts` subscription is
     * a projection read that sends nothing. None of it reaches [runConfigFlow].
     */
    private val channelInfoSession: Flow<Pair<SessionFacts?, Double?>> =
        combine(repository.observeSessionFacts(conversationId), sessionCostUsd, ::Pair)

    /** This conversation's MCP server reading (#1344), seeded so a source that never emits cannot stall [state]. */
    private val mcpStatusReading: Flow<McpStatus> =
        repository
            .observeMcpStatus(conversationId)
            .onStart { emit(McpStatus()) }
            .distinctUntilChanged()

    private val transientDialogs: Flow<TransientDialogs> =
        combine(
            pendingRenameDialog,
            pendingSaveAsChannelDialog,
            pendingChannelInfo,
            pendingDeleteConfirm,
        ) { rename, save, channelInfo, deleteConfirm ->
            TransientDialogs(
                renameVisible = rename,
                saveAsChannel = save,
                channelInfoOpen = channelInfo,
                deleteConfirmVisible = deleteConfirm,
            )
        }

    /**
     * This conversation's backward history walk (#777) — cursor, in-flight, page count and stop reason in
     * one value. Written CAS-shaped: [claimHistorySlot]'s [MutableStateFlow.compareAndSet] loop for the
     * ask and the retry claims, and [MutableStateFlow.update] for every settle and fail. A plain
     * read-then-assign would open a real window, because the settle runs in a launched coroutine while the
     * claim runs on the caller's.
     *
     * It survives a reconnect (#1352): the cursor names a position in the daemon's append-only log, so the
     * next gesture after a reconnect continues from it. Its position is saved beside the cached rows when
     * an ask settles and restored at open by [historySeed] (#1354); the page count and failures are not.
     */
    private val historyDemand = MutableStateFlow(ThreadHistoryDemand())
    private val historyCoverage = MutableStateFlow(HistoryCoverage())
    private var pendingNewest = 0

    /**
     * Restores the history position saved when this thread was last open (#1354), so the first pull asks
     * past the rows the cache already drew instead of re-fetching the newest page. Reading asks nothing.
     * Reader demand is dropped until this finishes; the `init` block's newest-page ask waits for it.
     * No walk ask can carry the opening empty cursor once a saved one exists.
     */
    private val historySeed: Job =
        viewModelScope.launch {
            val saved = repository.readHistoryPosition(conversationId) ?: return@launch
            historyDemand.update {
                it.restored(
                    cursor = saved.cursor,
                    atStart = saved.atStart && saved.coverage?.unsignedIncomplete != true,
                )
            }
            historyCoverage.value = saved.coverage ?: HistoryCoverage()
        }

    /**
     * Whether this thread's host has a live repository right now (#1352), desktop's
     * `connectedConversationHostNow`: a history ask is sent only while it holds, and the oldest-end slot
     * shows the offline notice while it does not. Keyed on the published repository, not the socket, for
     * the #861 reason on [repositoryAvailable].
     */
    private val hostAvailable: StateFlow<Boolean> =
        repositoryAvailable
            .distinctUntilChanged()
            .onEach { available ->
                // A replacement connection may report only empty summaries after the session changed offline.
                // Forget its predecessor's selection, while held settings remain display-only.
                if (!available) lastKnownSessionId = ""
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The thread rows (#337): the #313 finished-message projection from [ConversationRepository.observeMessages]
     * folded together with the live `assistant_delta` stream so an in-flight turn renders as a single
     * growing `isStreaming` assistant message that settles into the finished message when the turn ends.
     *
     * `observeMessages` (cold, re-emits on subscribe) and [liveSessionEvents] (hot, `replay = 0`) are two
     * uncoordinated server emitters — there is no wire ordering between `turn_end` and the finished
     * `message`, and the finished `message` carries no `turn_id`. So the dedup is structural, not
     * id-correlated: see [ThreadFold.reduce]. With the inert empty-flow default the `merge` yields only the
     * `observeMessages` arm, so the thread behaves exactly as #313.
     */
    private val threadItems: Flow<List<ThreadItem>> =
        merge(
            repository.observeMessages(conversationId).map(ThreadInput::Finished),
            liveSessionEvents.map { ThreadInput.Live(it) },
        ).scan(ThreadFold(emptyList(), null)) { fold, input -> fold.reduce(input, conversationId) }
            .map { it.render() }
            .distinctUntilChanged()
            // #1357: a session transition reaches this thread only as a new boundary row.
            .onEach(::noteNewestBoundary)

    /**
     * The thread's content surface: the [threadItems] rows folded with the conversation's queued-message
     * backlog (#461). [ConversationRepository.observeQueue] is a thread-content stream (an ordered list of
     * not-yet-sent user text, the same category as [items]) rather than a transient cross-cutting signal,
     * so it is surfaced on [ThreadUiState] — not as a sibling [StateFlow] like [isStalled]. Combined here
     * so the five-arm typed `state` combine keeps one content arm; both inputs seed immediately (the `scan`
     * seeds `emptyList()`, `observeQueue` seeds `emptyList()`) and each already carries
     * `distinctUntilChanged`. Display preparation follows the latest snapshot on the worker dispatcher.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val threadContent: Flow<ThreadContent> =
        combine(
            threadItems,
            repository.observeQueue(conversationId),
            historyDemand,
            hostAvailable,
            historyCoverage,
        ) { items, queued, demand, connected, coverage ->
            Triple(items, coverage, ThreadContent(items, queued, demand.tail(connected), emptyList()))
        }.mapLatest { (items, coverage, content) ->
            if (coverage.unsignedGaps.isEmpty() && !coverage.unsignedUnknown) {
                content
            } else {
                withContext(projectionDispatcher) {
                    val context = currentCoroutineContext()
                    // Use the render fold's one-to-one queue correlation and lifecycle filtering.
                    val delivered = IdentityHashMap<ThreadItem, Unit>()
                    foldQueuedRows(items, content.queued).forEach { row ->
                        context.ensureActive()
                        if (row is ThreadRow.Delivered) delivered[row.item] = Unit
                    }
                    val display =
                        coverage.projectDisplay(
                            items,
                            checkActive = { context.ensureActive() },
                            isDisplayed = { it in delivered },
                        )
                    RelayLog.d { "event=history_display_projected rows=${display.rows.size} markers=${display.markers.size}" }
                    content.copy(
                        items = display.rows,
                        historyMarkers =
                            display.markers.map {
                                ThreadHistoryMarker(beforeRow = it.beforeRow, unsignedAnchor = it.anchor, displayRow = it.displayRow)
                            },
                    )
                }
            }
        }

    val state: StateFlow<ThreadUiState> =
        combine(
            conversations,
            threadContent,
            pendingWorkspacePicker,
            transientDialogs,
            runConfigFlow,
        ) { conversations, content, pickerVisible, dialogs, runConfig ->
            val conv = conversations.firstOrNull { it.id == conversationId }
            // #686: the one place that sees the settings reading and the live session together without a
            // second `observeSessionSettings` subscription, which would send another settings request.
            // The recall decides at most once, so a re-emission or a WhileSubscribed restart is harmless.
            effortRecall.offer(runConfig, conv?.currentSessionId.orEmpty())
            ThreadUiState(
                conversationId = conversationId,
                displayName = conv?.displayName() ?: conversationId,
                conversationName = conv?.name,
                isPromoted = conv?.isPromoted ?: false,
                agent = conv?.agent ?: ConversationAgent.Claude,
                hasMessages = content.items.any { it is ThreadItem.MessageItem },
                workspaceLabel = workspaceDisplayName(cwd = conv?.cwd ?: "", label = conv?.workspaceLabel),
                workspacePickerVisible = pickerVisible,
                showRenameDialog = dialogs.renameVisible,
                saveAsChannelDialog = dialogs.saveAsChannel,
                items = content.items,
                queuedMessages = content.queued,
                channelInfoOpen = dialogs.channelInfoOpen,
                deleteConfirmVisible = dialogs.deleteConfirmVisible,
                workspacePath = conv?.cwd ?: "",
                lastUsedAt = conv?.lastUsedAt,
                sessionCount = conv?.sessionHistory?.size ?: 0,
                runConfig = runConfig.forLiveSession(lastKnownSessionId),
                mutationsSupported = mutationsSupported,
                historyTail = content.historyTail,
                historyMarkers = content.historyMarkers,
            )
        }.combine(slashCommandMenu) { uiState, menu ->
            val slashCommandsAccepted = uiState.runConfig.capabilities?.slashCommands ?: true
            uiState.copy(absentActions = absentComposerActions(menu, slashCommandsAccepted), slashCommands = menu?.rows)
        }.combine(taskControls) { uiState, tasks ->
            uiState.copy(
                backgroundTasks = tasks.roster,
                backgroundTaskCount = tasks.count,
                backgroundTaskStopSupported = tasks.supported,
                expandedBackgroundTaskIds = tasks.expanded,
                pendingBackgroundTaskIds = tasks.pending,
            )
        }.combine(channelInfoSession) { uiState, (facts, cost) ->
            uiState.copy(reportedSessionFacts = facts, sessionCostUsd = cost)
        }.combine(mcpStatusReading) { uiState, mcp ->
            uiState.copy(mcpStatus = mcp)
        }.combine(combine(channelEditor.state, hostAvailable, ::Pair)) { uiState, (editor, available) ->
            uiState.copy(channelEditor = editor, hostAvailable = available)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue =
                ThreadUiState(
                    conversationId = conversationId,
                    displayName = conversationId,
                    mutationsSupported = mutationsSupported,
                ),
        )

    /**
     * The switch-back offer as the refusal row draws it (#1360), or `null`. Shown only while the latest
     * settings reading names a session to address, as desktop shows it, so the button is never one whose tap
     * [onSwitchBack] would drop. Pending while any model write is outstanding, the switch-back's own or the
     * menu's. Eager, so it holds while no screen collects it; [settingsReadings] is a plain state holder, so
     * reading it opens no settings subscription.
     */
    val switchBackOffer: StateFlow<SwitchBackOffer?> =
        combine(
            refusalOffer,
            pendingModel,
            settingsReadings
                .map {
                    it.settings
                        ?.sessionId
                        .orEmpty()
                        .isNotEmpty()
                }.distinctUntilChanged(),
        ) { offer, pending, addressable ->
            offer?.takeIf { addressable }?.let {
                SwitchBackOffer(it.occurredAt, it.originalModel, pending = pending != null, failed = it.failed)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // #1360: the offer's live inputs, for the life of the thread. A reconnect is neither, so it keeps
        // the offer. The announced model is a held reading; `distinctUntilChanged` keeps the same model handed
        // over again on a reconnect from counting as a new announcement.
        viewModelScope.launch { repository.observeLiveRefusalEvents(conversationId).collect(::onLiveRefusalEvent) }
        viewModelScope.launch {
            repository
                .observeAnnouncedModel(conversationId)
                .mapNotNull { it?.model }
                .distinctUntilChanged()
                .collect { model ->
                    if (model.isNotEmpty() && model != refusalOffer.value?.fallbackModel) clearRefusalOffer("announced")
                }
        }
    }

    /**
     * This thread's host connection, `Connected` once the host has answered the handshake (#1318). Started
     * eagerly (#1319) so [connectedFor] reads the live state at tap time even when no screen collects it;
     * a `WhileSubscribed` value stays at its initial `Connected` without a collector.
     */
    val connectionState: StateFlow<ConnectionState> =
        connectionStateSource
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ConnectionState.Connected,
            )

    /**
     * The tap-time read of this thread's host connection (#1321). Collected `Eagerly`, so it is current
     * with no screen collecting. Unlike [connectionState] it is seeded `null` rather than an optimistic
     * `Connected`, so a host that has not reported yet cannot be answered.
     */
    private val hostConnection: StateFlow<ConnectionState?> =
        connectionStateSource.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mcpAcknowledgements = mcpFailureAcknowledgements ?: McpFailureAcknowledgements()

    /**
     * The failed MCP server the Top overlay's notice names (#1345), desktop's `selectUnacknowledgedMcpFailureFor`:
     * the first server in report order whose status is exactly `failed` and that this host and conversation
     * have not acknowledged. `null` unless the host is [ConnectionState.Connected]. The name is Claude-authored:
     * the screen renders it bounded and inert, and nothing here logs it.
     */
    val mcpFailure: StateFlow<String?> =
        combine(
            mcpStatusReading,
            mcpAcknowledgements.observe(serverId, conversationId),
            hostConnection,
        ) { mcp, acknowledged, connection ->
            if (connection == ConnectionState.Connected) firstUnacknowledgedMcpFailure(mcp.report, acknowledged) else null
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether a prompt answer may be sent now (#1321): only while the host is [ConnectionState.Connected].
     * A refused tap changes nothing, so the prompt answers as it stood once the host reconnects.
     */
    private fun promptSendAllowed(kind: String): Boolean {
        if (hostConnection.value == ConnectionState.Connected) return true
        RelayLog.d { "event=prompt_send_blocked kind=$kind reason=not_connected" }
        return false
    }

    /**
     * The tap-time re-check (#1319), after desktop's: the screen greys these controls while the host is not
     * connected, and a tap that races a disconnect is dropped here before any state change or send. Logs
     * the static [action] code only.
     */
    private fun connectedFor(action: String): Boolean {
        if (connectionState.value == ConnectionState.Connected) return true
        RelayLog.d { "event=thread_action_skipped action=$action reason=not_connected" }
        return false
    }

    /**
     * Whether the thread offers Re-pair (#843): this thread's own host is in the rejected-pairing state,
     * so the connection banner's retry cannot succeed. A sibling [StateFlow] beside [connectionState]; the
     * screen draws the action and emits the tap, and the route it opens is keyed by the destination's own
     * server id.
     */
    val rePairAvailable: StateFlow<Boolean> =
        pairingRejected
            .onEach { if (it) RelayLog.d { "event=thread_repair_offered" } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * This conversation's turn phase as the repository holds it (#1313): the latest `turn_state`, back to
     * idle on `turn_end`, kept per conversation for the connection rather than folded here. A thread
     * opened mid-turn or resubscribing after [SharingStarted.WhileSubscribed] lapsed reads the current
     * phase at once, and a new connection reads idle until the daemon reports again.
     */
    private val turnPhase = repository.observeTurnPhase(conversationId)

    /**
     * Whether this conversation's agent is currently in its `thinking` phase (#406) — `true` only while
     * the held phase is [LiveSessionEvent.TurnState.Phase.Thinking]. A sibling [StateFlow] beside
     * [connectionState] (not a [ThreadUiState] field): like the connection signal it is a transient,
     * connection-scoped cross-cutting signal the stateless screen takes as a separate parameter. `false`
     * covers idle, `responding` and the fake's idle default.
     */
    val isThinking: StateFlow<Boolean> =
        turnPhase
            .map { it == LiveSessionEvent.TurnState.Phase.Thinking }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Whether this conversation's agent is currently **running a turn** (#459) — `true` while the held
     * phase is [LiveSessionEvent.TurnState.Phase.Thinking] **or** [LiveSessionEvent.TurnState.Phase.Responding].
     * The broader sibling of [isThinking]: the interrupt affordance must stay visible across the whole
     * in-flight turn, not just the thinking phase. Same posture and lifecycle as [isThinking].
     */
    val isBusy: StateFlow<Boolean> =
        turnPhase
            .map { it == LiveSessionEvent.TurnState.Phase.Thinking || it == LiveSessionEvent.TurnState.Phase.Responding }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    private val _suggestedReply = MutableStateFlow<SuggestedReply?>(null)
    val suggestedReply: StateFlow<SuggestedReply?> = _suggestedReply.asStateFlow()
    private var suggestionReading: ReplySuggestion? = null
    private var suggestionSession: String? = null
    private var suggestionBusy = false
    private val invalidatedSuggestions = mutableMapOf<String, ULong>()
    private val observedSuggestionRevisions = mutableMapOf<String, ULong>()

    init {
        viewModelScope.launch {
            turnPhase.collect { phase ->
                suggestionBusy = phase != LiveSessionEvent.TurnState.Phase.Idle
                refreshSuggestedReply()
            }
        }
        viewModelScope.launch { hostConnection.collect { refreshSuggestedReply() } }
        viewModelScope.launch {
            draftStore.drafts.map { it[serverId]?.get(conversationId).orEmpty() }.distinctUntilChanged().collect {
                _suggestedReply.value = null
                refreshSuggestedReply()
            }
        }
        viewModelScope.launch {
            _attachmentsSending.collect {
                // Even a completed upload must not revive a token armed before it started.
                _suggestedReply.value = null
                refreshSuggestedReply()
            }
        }
        viewModelScope.launch {
            combine(conversations, settingsReadings) { _, reading ->
                if (hostAvailable.value) {
                    lastKnownSessionId.takeIf { it.isNotEmpty() }
                        ?: reading.settings
                            ?.takeUnless { it.held }
                            ?.sessionId
                            ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }.distinctUntilChanged()
                .onEach { session ->
                    invalidateSuggestion("session")
                    suggestionReading = null
                    suggestionSession = session
                }.flatMapLatest { session ->
                    session?.let { repository.observeReplySuggestion(conversationId, it) } ?: flowOf(null)
                }.collect { reading ->
                    reading?.let {
                        // #1865 never emits a decreasing revision within one connection. A lower one
                        // therefore identifies a fresh daemon lifetime, whose watermarks start again.
                        val previous = observedSuggestionRevisions[it.sessionId]
                        if (previous != null && it.revision < previous) {
                            invalidatedSuggestions.remove(it.sessionId)
                        }
                        observedSuggestionRevisions[it.sessionId] = it.revision
                    }
                    suggestionReading = reading
                    refreshSuggestedReply()
                }
        }
    }

    private fun invalidateSuggestion(reason: String) {
        // Teardown may emit absence before Offline or a session replacement reaches this collector.
        suggestionSession?.let { session ->
            observedSuggestionRevisions[session]?.let { revision ->
                invalidatedSuggestions[session] =
                    maxOf(invalidatedSuggestions[session] ?: 0uL, revision)
            }
        }
        if (_suggestedReply.value != null) RelayLog.d { "event=reply_suggestion_hidden reason=$reason" }
        _suggestedReply.value = null
    }

    private fun refreshSuggestedReply() {
        if (suggestionBusy || hostConnection.value != ConnectionState.Connected) {
            invalidateSuggestion(if (suggestionBusy) "turn" else "connection")
            return
        }
        val reading = suggestionReading
        val text = reading?.suggestedReply
        if (reading == null ||
            reading.conversationId != conversationId ||
            reading.sessionId != suggestionSession ||
            text.isNullOrBlank() ||
            reading.revision <= (invalidatedSuggestions[reading.sessionId] ?: 0uL) ||
            draftStore.draftFor(serverId, conversationId).isNotEmpty()
        ) {
            _suggestedReply.value = null
            return
        }
        if (_suggestedReply.value?.reading != reading) {
            _suggestedReply.value = SuggestedReply(text, reading)
            RelayLog.d { "event=reply_suggestion_shown" }
        }
    }

    /** Re-check and consume the exact offer before any asynchronous submission, never editing the draft. */
    fun sendSuggestedReply(offer: SuggestedReply): Boolean {
        if (_suggestedReply.value !== offer ||
            offer.reading != suggestionReading ||
            offer.reading.sessionId != suggestionSession ||
            suggestionBusy ||
            hostConnection.value != ConnectionState.Connected ||
            _attachmentsSending.value ||
            draftStore.draftFor(serverId, conversationId).isNotEmpty()
        ) {
            RelayLog.d { "event=reply_suggestion_send_skipped reason=ineligible" }
            return false
        }
        invalidateSuggestion("consumed")
        RelayLog.d { "event=reply_suggestion_submitted" }
        submitMessage(offer.text) {}
        return true
    }

    private val _turnOutcome = MutableStateFlow<TurnRecoveryNotice?>(null)

    /**
     * The recovery advice for this conversation's last stopped turn (#1357), desktop's `latestTurnEnd` read by
     * `ComposerErrorSlotControl` — drives the status area's turn-outcome arm. A `turn_end` sets or replaces it
     * through [turnRecoveryNotice]; the next sign of activity clears it: a non-idle `turn_state`, an
     * assistant delta, a tool use or result ([nextTurnOutcome]), a new thinking reading, a new session
     * boundary ([noteNewestBoundary]), the user's send, and a reconnect. An `idle` `turn_state` never clears it,
     * since it may arrive on either side of the `turn_end` it accompanies.
     *
     * Held, not folded per subscriber, so a `turn_end` that lands while the screen is not collecting still
     * counts. Every writer runs on `viewModelScope`'s main dispatcher.
     */
    val turnOutcome: StateFlow<TurnRecoveryNotice?> = _turnOutcome.asStateFlow()

    /** The newest boundary row [threadItems] has drawn, once it has drawn a non-empty thread (#1357). */
    private var newestBoundary: ThreadItem.SessionBoundary? = null

    private var boundaryBaselineSeen = false

    /** The open Channel info sheet's System prompt state (#1342), or `null` while the sheet is closed. */
    val systemPrompt: StateFlow<SystemPromptEditorState?> =
        promptEditor
            .flatMapLatest { editor -> editor?.state ?: flowOf(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    /**
     * Whether this conversation is currently stalled (#395) — drives the prominent screen-snapshot CTA
     * (#396). A sibling [StateFlow] beside [connectionState] / [isThinking] (not a [ThreadUiState]
     * field): like them it is a transient, connection-scoped cross-cutting signal the stateless screen
     * takes as a separate parameter. Sourced from the already-injected [repository]; `observeStall`
     * already applies `distinctUntilChanged` in the remote impl and defaults to `false` in the facade,
     * so no extra operator is needed. `false` covers "no live connection" and "not stalled".
     */
    val isStalled: StateFlow<Boolean> =
        repository
            .observeStall(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    private val _localSendStage = MutableStateFlow(LocalSendStage.None)
    private var localSendGeneration = 0L

    /**
     * Sending until the repository's correlated acknowledgement, then Waiting until this conversation's
     * first turn_state (#1641). Closed by any phase, a session error, a failed send or availability change. A completion
     * cannot reopen a closed window or alter a newer send's window. Blank/refused/upload-failed sends
     * never open it. This is independent of [isBusy] and must never arm Stop on its own.
     */
    val localSendStage: StateFlow<LocalSendStage> = _localSendStage.asStateFlow()

    /** Latest error for this destination. Eager observation settles sends even while the screen is stopped. */
    val sessionError: StateFlow<String?> =
        repository
            .observeSessionError(conversationId)
            .onEach { code ->
                if (code != null) {
                    closeLocalSendWindow("session_error")
                    val classification =
                        when (code) {
                            "session.blocked" -> "blocked"
                            "session.child_crashing" -> "child_crashing"
                            else -> "unknown"
                        }
                    RelayLog.d { "event=thread_session_error state=present classification=$classification" }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether this conversation's remote claude is stuck retrying an API error, and at which attempt
     * (#593) — drives the "Retrying — attempt N/M" status that replaces the indefinite generic spinner
     * (#594). A sibling [StateFlow] beside [connectionState] / [isThinking] / [isStalled] (not a
     * [ThreadUiState] field): like them it is a transient, connection-scoped cross-cutting signal the
     * stateless screen takes as a separate parameter. Sourced from the already-injected [repository];
     * `observeApiRetry` already applies `distinctUntilChanged` in the remote impl and defaults to
     * [ApiRetryStatus.NotRetrying] in the facade, so no extra operator is needed — and none may be added,
     * since a climbed counter must reach the screen as a fresh emission. [ApiRetryStatus.NotRetrying]
     * covers "no live connection" and "not retrying".
     */
    val apiRetry: StateFlow<ApiRetryStatus> =
        repository
            .observeApiRetry(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ApiRetryStatus.NotRetrying,
            )

    /**
     * Whether this conversation's remote claude is currently auto-compacting its context (#596) — drives
     * the "Compacting conversation" status that replaces the indefinite generic spinner (#597). A sibling
     * [StateFlow] beside [connectionState] / [isThinking] / [isStalled] (not a [ThreadUiState] field): like
     * them it is a transient, connection-scoped cross-cutting signal the stateless screen takes as a
     * separate parameter. Sourced from the already-injected [repository]; `observeCompacting` already
     * applies `distinctUntilChanged` in the remote impl and defaults to `false` on the interface and the
     * facade, so no extra operator is needed. `false` covers "no live connection", "not compacting", and
     * "no `compacting` frame ever received".
     *
     * Dedup is **correct** here, deliberately unlike [apiRetry]'s "and none may be added" caveat: that
     * warning exists only because a climbed counter must reach the screen as a fresh emission, and it
     * inverts for a `Boolean`, which has no intermediate values to collapse. Do not import the inverted
     * rule by pattern-matching the sibling above.
     */
    val isCompacting: StateFlow<Boolean> =
        repository
            .observeCompacting(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Where this conversation's running Reset session is (#871) — drives the status area's resetting arm
     * (#872). A sibling [StateFlow] beside [isCompacting] (not a [ThreadUiState] field). `null` covers no
     * live connection and no reset running; the falling edge and the conversation's session transition
     * both clear it upstream in the projection. `observeResetting` already dedups, and a phase change is a
     * different [ResetStatus], so it reaches the screen without an extra operator.
     */
    val resetting: StateFlow<ResetStatus?> =
        repository
            .observeResetting(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    /**
     * What claude last reported about its usage-limit window for this conversation (#802) — drives the
     * status area's usage-limit arm (#804). A sibling [StateFlow] beside [apiRetry] / [isCompacting] (not a
     * [ThreadUiState] field). `null` covers no live connection, nothing reported, a benign clear, and a
     * reading whose `resets_at` has passed.
     *
     * **Re-read on a fixed cadence, because the expiry is applied only when read.** `observeUsageLimit`
     * compares `resets_at` against the clock on subscription and on each upstream change, and emits
     * nothing at the deadline itself. Each [usageLimitRereads] tick re-subscribes, so a displayed reading
     * leaves within [USAGE_LIMIT_REREAD_MS] of its reset without this layer re-deriving the rule — and
     * with no delay ever computed from `resets_at`, claude's unvalidated number. `StateFlow` equality drops
     * the identical re-reads, so ticks do not recompose the screen; the ticker runs only while subscribed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val usageLimit: StateFlow<UsageLimitReading?> =
        usageLimitRereads()
            .flatMapLatest { repository.observeUsageLimit(conversationId) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    /**
     * How far this conversation's current reasoning has got (#801) — drives the token reading the
     * thinking arm carries instead of a bare indefinite spinner (#803). A sibling [StateFlow] beside
     * [connectionState] / [isThinking] / [isStalled] / [isCompacting] (not a [ThreadUiState] field): like
     * them it is a transient, connection-scoped cross-cutting signal the stateless screen takes as a
     * separate parameter. Sourced from the already-injected [repository].
     *
     * `null` is **no reading, never "claude is not thinking"** — it covers no live connection, a
     * connection without the `interactive` capability, and the window before the first frame. Absence
     * proves nothing in either direction: the PTY surface emits none of these frames at all, and on the
     * emitting surface a gap may only mean the producer's rate bound has not been crossed. Nothing here
     * or downstream may infer a stall from it; [isStalled] is the separate signal for that.
     *
     * `observeThinkingProgress` already applies `distinctUntilChanged` in the remote impl and defaults to
     * `flowOf(null)` on the interface and the facade, so no extra operator is needed — **and none may be
     * added.** That one dedup carries both halves of the reading's contract: an identical repeat is
     * dropped, so the rendered label holds rather than being rewritten, while a *falling* reading is a
     * different [ThinkingProgress] value and does reach the screen. The reading restarts near zero at
     * every inference-request boundary, repeatedly inside one turn, so a second dedup, a `derivedStateOf`
     * or any running-maximum guard would break one half or the other.
     */
    val thinkingProgress: StateFlow<ThinkingProgress?> =
        repository
            .observeThinkingProgress(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    /**
     * Which non-default option of the currently-open modal is "armed" (#451) — tapped once and awaiting an
     * explicit second confirm — or `null`. Carries its own [ArmedModalOption.modalId] so a stale arm can
     * never pre-arm or auto-confirm a fresh modal (the modalId equality in [onModalOption] is the
     * deterministic guard; the scoping is the "resets on resolve" property). **Transient** — never
     * persisted (no `rememberSaveable` / [SavedStateHandle]); it resets on resolve / cancel / re-tap.
     */
    private val armedModalOption = MutableStateFlow<ArmedModalOption?>(null)

    /**
     * The option of the *currently-open* modal that is armed (tapped once, awaiting a second confirm), or
     * `null`. The render slice (#452) draws the armed affordance off this — the #445→#446 seam analog (this
     * behavior slice owns the arm state, the render slice renders it). **Scoped:** `null` unless the arm's
     * `modalId` matches the open modal, so a stale arm is invisible and a resolve nulls it automatically. A
     * sibling [StateFlow] to [currentModal]; [SharingStarted.Eagerly] matches it so `.value` is always the
     * true projection and a resolve immediately clears the affordance.
     */
    val armedOptionId: StateFlow<String?> =
        combine(currentModal, armedModalOption) { modal, arm ->
            if (modal is ModalUiState.Open && arm?.modalId == modal.modalId) arm.optionId else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The "don't ask again this session" offers the user accepted (#818), keyed on the prompt that showed
     * each: its [PermissionGrantDraft.modalId] and the exact rules it offered. Since #1306 they live in an
     * app-scoped store, heap only, so leaving through Back keeps this conversation's draft; a stale key is
     * simply invisible (see [alwaysAllowAccepted]) and a bound store retires it when the request changes.
     */
    private val grantDrafts = permissionDraftStore ?: PermissionDraftStore()

    /**
     * Whether the *currently-open* prompt's offer is accepted (#818): `true` only while the scoped modal is
     * [ModalUiState.Open], [offers][ModalUiState.Open.offersAlwaysAllow] the grant, and matches the accepted
     * key. A new, replaced, re-offered-with-other-rules or resolved prompt therefore reads as unaccepted by
     * construction. A sibling of [armedOptionId], started eagerly for the same reason.
     */
    val alwaysAllowAccepted: StateFlow<Boolean> =
        combine(currentModal, grantDrafts.observe(serverId, conversationId)) { modal, accepted ->
            modal is ModalUiState.Open && modal.offersAlwaysAllow && accepted == modal.alwaysAllowKey()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether this chat shows "Your answer was rejected." (#1340): the daemon refused an answer this chat sent,
     * and the user has not dismissed the notice. Read from the host fold, so it survives leaving the chat and
     * a reconnect, and shows in no other chat.
     */
    val answerRejected: StateFlow<Boolean> =
        hostModal
            .map { conversationId in it.rejectedConversations }
            .stateIn(viewModelScope, SharingStarted.Eagerly, conversationId in hostModal.value.rejectedConversations)

    private val questions = questionDraftStore ?: QuestionDraftStore()
    private val mutableQuestionModal = MutableStateFlow<QuestionModalState?>(null)
    val questionModal: StateFlow<QuestionModalState?> = mutableQuestionModal

    init {
        if (questionDraftStore == null) {
            viewModelScope.launch {
                questionBatch(conversationId).collect { batch ->
                    questions.reconcileHost(serverId, listOfNotNull(batch?.takeIf { it.conversationId == conversationId }))
                }
            }
            addCloseable { questions.dispose() }
        }
        viewModelScope.launch {
            questions
                .observe(serverId, conversationId)
                .combine(conversationAgent.onStart { emit(ConversationAgent.Claude) }) { held, agent ->
                    held?.copy(agent = agent)
                }.collect { mutableQuestionModal.value = it }
        }
    }

    fun onQuestionEvent(
        event: QuestionModalEvent,
        generation: Long = questionModal.value?.generation ?: -1,
    ) {
        val held = questions.current(serverId, conversationId) ?: return
        if (held.generation != generation) return
        when (event) {
            is QuestionModalEvent.OptionToggled ->
                editSelection(generation, event.questionIndex) { selection, question ->
                    if (event.optionIndex in
                        question.options.indices
                    ) {
                        selection.withOption(event.optionIndex, question.multiSelect)
                    } else {
                        selection
                    }
                }
            is QuestionModalEvent.OtherToggled ->
                editSelection(generation, event.questionIndex) { selection, question ->
                    selection.withOtherTicked(!selection.otherTicked, question.multiSelect)
                }
            is QuestionModalEvent.OtherTextChanged ->
                editSelection(generation, event.questionIndex) { selection, question ->
                    selection.copy(otherText = event.text).withOtherTicked(true, question.multiSelect)
                }
            QuestionModalEvent.Continue -> {
                val answers = held.answers()
                if (!held.locked && answers != null && promptSendAllowed("question_answer")) {
                    sendQuestion(held, "answer", answers) { answerQuestionBatch(it, answers) }
                }
            }
            QuestionModalEvent.Cancel ->
                if (!held.locked && promptSendAllowed("question_refuse")) {
                    sendQuestion(held, "refuse", null) { refuseQuestionBatch(it) }
                }
        }
    }

    private fun editSelection(
        generation: Long,
        questionIndex: Int,
        edit: (QuestionSelection, Question) -> QuestionSelection,
    ) {
        questions.update(serverId, conversationId, generation) { held ->
            if (held.locked || questionIndex !in held.selections.indices) return@update held
            val question = held.batch.questions[questionIndex]
            held.copy(selections = held.selections.toMutableList().also { it[questionIndex] = edit(it[questionIndex], question) })
        }
    }

    /**
     * The single question send (#661): locks the modal before launching, so a second Continue or Cancel
     * is a no-op, and applies the outcome only while the captured generation is still held. Catches
     * only the documented throws; logs static codes only, never an id, label or value.
     */
    private fun sendQuestion(
        held: QuestionModalState,
        kind: String,
        answers: List<QuestionAnswer>?,
        send: suspend (questionBatchId: String) -> Unit,
    ) {
        val generation = held.generation
        setQuestionPhase(generation, QuestionSendPhase.Sending)
        viewModelScope
            .launch {
                if (questions.current(serverId, conversationId)?.generation != generation) return@launch
                val outcome =
                    try {
                        if (!questions.submit(serverId, conversationId, generation, answers, send)) return@launch
                        QuestionSendPhase.Sent
                    } catch (e: CancellationException) {
                        setQuestionPhase(generation, QuestionSendPhase.Failed)
                        throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
                    } catch (e: RelayErrorException) {
                        QuestionSendPhase.Failed
                    } catch (e: IllegalStateException) {
                        QuestionSendPhase.Failed
                    } catch (e: IllegalArgumentException) {
                        QuestionSendPhase.Failed
                    }
                RelayLog.d { "event=question_send kind=$kind outcome=${if (outcome == QuestionSendPhase.Sent) "sent" else "failed"}" }
                setQuestionPhase(generation, outcome)
            }.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    questions.update(serverId, conversationId, generation) {
                        if (it.phase == QuestionSendPhase.Sending) it.copy(phase = QuestionSendPhase.Failed) else it
                    }
                }
            }
    }

    private fun setQuestionPhase(
        generation: Long,
        phase: QuestionSendPhase,
    ) {
        questions.update(serverId, conversationId, generation) { it.copy(phase = phase) }
    }

    private val newSessionErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "starting a new session failed" signal (#540) — the one-shot idiom of [navigationEvents], cloned
     * for the overflow "New session" action. Carries **no** payload (just [Unit]), so nothing sensitive can
     * leak through it; the render slice shows a transient snackbar with a **fixed local string**, never an
     * exception message. Fires exactly once per caught not-connected failure ([IllegalStateException] from
     * the fire-and-forget `new_session` send's `check(pump.send(...))`, #539). Success is passive — no
     * signal fires; the #336 fold renders the session-boundary delimiter when `session_transition` arrives.
     */
    val newSessionErrors: Flow<Unit> = newSessionErrorChannel.receiveAsFlow()

    private val archiveErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "archiving this conversation failed" signal (#556) — the [newSessionErrors] one-shot idiom,
     * cloned for the overflow / Channel-Info "Archive" action. Carries **no** payload (just [Unit]), so
     * nothing sensitive — least of all the server-supplied [RelayErrorException.message] — can leak through
     * it; the render slice shows a transient snackbar with a **fixed local string**, never an exception
     * message. Fires exactly once per caught failure: [RelayErrorException] from a server `error` reply
     * (archive is request/reply, so a correlated server error is reachable — unlike the fire-and-forget
     * `new_session` send), or [IllegalStateException] from a not-connected send. Success is passive — no
     * signal fires; the thread pops back and the confirmed upsert makes `observeConversations` re-emit
     * without this conversation (list-driven), so it leaves the main list with no explicit removal call.
     */
    val archiveErrors: Flow<Unit> = archiveErrorChannel.receiveAsFlow()

    private val markdownOpenFailureChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "this markdown file cannot be read" signal (#1027), the [archiveErrors] idiom: no payload, so
     * the screen shows the fixed open-failed sentence and nothing from the file.
     */
    val markdownOpenFailures: Flow<Unit> = markdownOpenFailureChannel.receiveAsFlow()

    // #1027: the open in flight, so a double tap cannot buffer a second navigation that fires on return.
    private var markdownOpenJob: Job? = null

    // #1050: the linked note just read live, held in memory only until the operator is back on the thread.
    private var linkedMarkdownNote: LinkedMarkdown? = null

    private val changeWorkspaceErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "changing this conversation's workspace failed" signal (#561) — the [archiveErrors] one-shot
     * idiom, cloned for the Workspace-Picker selection. Carries **no** payload (just [Unit]), so nothing
     * sensitive — least of all the server-supplied [RelayErrorException.message] — can leak through it; the
     * render slice shows a transient snackbar with a **fixed local string**, never an exception message.
     * Fires exactly once per caught failure: [RelayErrorException] from a server `error` reply
     * (change_workspace is request/reply, so a correlated server error is reachable — unlike the
     * fire-and-forget `new_session` send), or [IllegalStateException] from a not-connected send. Success is
     * passive — no signal fires and there is no [ThreadNavigation.PopBack] (the divergence from
     * [archiveErrors]): #560's confirmed upsert makes `observeConversations` re-emit with the new `cwd`, so
     * the workspace chip re-labels itself (list-driven) while the user stays on the thread.
     */
    val changeWorkspaceErrors: Flow<Unit> = changeWorkspaceErrorChannel.receiveAsFlow()

    private val sessionSettingsErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "applying a Status-sheet run-configuration change (model / effort / YOLO) failed" signal
     * (#544) — the [changeWorkspaceErrors] one-shot idiom, cloned for the Status-sheet controls. Carries
     * **no** payload (just [Unit]), so nothing sensitive — least of all the server-supplied
     * [RelayErrorException.message] — can leak through it; the render slice shows a transient snackbar with
     * a **fixed local string**, never an exception message. Fires exactly once per caught failure:
     * [RelayErrorException] from a server `error` reply (set_session_settings is request/reply, so a
     * correlated server error — `session.not_found` / `protocol.malformed` / `server.binary_offline`, all
     * `RelayErrorException`, #543 confirmed no IAE path — is reachable), or [IllegalStateException] from a
     * not-connected send. On each failure the changed control is reverted to its last-known value (the
     * `revert` lambda passed to [sendSessionSettings]) so the sheet never settles on a value the daemon did
     * not confirm; success is passive — no signal fires and the optimistic value stays.
     */
    val sessionSettingsErrors: Flow<Unit> = sessionSettingsErrorChannel.receiveAsFlow()

    /** Folds one live event into [turnOutcome]; events for other conversations leave it unchanged. */
    private fun nextTurnOutcome(
        current: TurnRecoveryNotice?,
        event: LiveSessionEvent,
    ): TurnRecoveryNotice? {
        if (event.conversationId != conversationId) return current
        return when (event) {
            is LiveSessionEvent.TurnEnd -> turnRecoveryNotice(event)
            is LiveSessionEvent.TurnState ->
                if (event.phase == LiveSessionEvent.TurnState.Phase.Idle) current else null
            is LiveSessionEvent.AssistantDelta,
            is LiveSessionEvent.ToolUse,
            is LiveSessionEvent.ToolResult,
            -> null
            is LiveSessionEvent.ReplayGap -> current
        }
    }

    private fun setTurnOutcome(
        notice: TurnRecoveryNotice?,
        reason: String,
    ) {
        val current = _turnOutcome.value
        if (notice == current) return
        _turnOutcome.value = notice
        RelayLog.d {
            if (notice == null) {
                "event=turn_recovery_notice state=cleared reason=$reason"
            } else {
                "event=turn_recovery_notice state=shown notice=${notice.name}"
            }
        }
    }

    private fun clearTurnOutcome(reason: String) = setTurnOutcome(null, reason)

    /**
     * Clear [turnOutcome] when [items] end on a boundary newer than the last one drawn (#1357). An empty list,
     * the fold's seed or a thread not loaded yet, sets no baseline, so the first load never reads as a
     * transition. An older history page prepends rows and leaves the newest boundary as it was.
     */
    private fun noteNewestBoundary(items: List<ThreadItem>) {
        if (items.isEmpty()) return
        val newest = items.lastOrNull { it is ThreadItem.SessionBoundary } as ThreadItem.SessionBoundary?
        if (boundaryBaselineSeen && newest != null && newest != newestBoundary) clearTurnOutcome("session_boundary")
        newestBoundary = newest
        boundaryBaselineSeen = true
    }

    init {
        // #1572: an open thread asks for the newest history page every time its host becomes available, at
        // open and after every reconnect. A reply that reached it while it was off-screen was never cached and
        // the reconnect dropped it; replay does not resend it, so only this ask brings it back. Older pages
        // are still asked for only by the reader (#1352), and the walk keeps its cursor across a reconnect.
        // This replaces #1569's opening ask, which a never-loaded thread only made.
        viewModelScope.launch {
            historySeed.join()
            var opened = false
            repositoryAvailable
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    askForNewestPage(reconnect = opened)
                    opened = true
                }
        }

        // Raw arrivals can precede the eager availability projection. Readiness releases pending
        // newest work without creating another arrival or reader demand.
        viewModelScope.launch {
            hostAvailable.filter { it }.collect { drainNewestPages() }
        }

        // #1311: a drop and the return both end the round trip the local-send window was waiting on.
        // `drop(1)` skips the availability the thread opened on, which the flow hands every collector.
        viewModelScope.launch {
            repositoryAvailable
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    closeLocalSendWindow("reconnect")
                    clearTurnOutcome("reconnect")
                }
        }

        // #1357: the live events set and clear the recovery notice, and a new thinking reading clears it. The
        // repository drops a reading on `turn_end`, so any reading that follows belongs to a later step.
        viewModelScope.launch {
            liveSessionEvents.collect { event ->
                val reason =
                    when (event) {
                        is LiveSessionEvent.TurnEnd -> "turn_end"
                        is LiveSessionEvent.TurnState -> "turn_state"
                        is LiveSessionEvent.AssistantDelta -> "assistant_delta"
                        is LiveSessionEvent.ToolUse -> "tool_use"
                        is LiveSessionEvent.ToolResult -> "tool_result"
                        is LiveSessionEvent.ReplayGap -> "replay_gap"
                    }
                if (event.conversationId == conversationId &&
                    (
                        event is LiveSessionEvent.AssistantDelta ||
                            event is LiveSessionEvent.ToolUse ||
                            event is LiveSessionEvent.ToolResult ||
                            (event is LiveSessionEvent.TurnState && event.phase != LiveSessionEvent.TurnState.Phase.Idle)
                    )
                ) {
                    invalidateSuggestion("turn")
                }
                setTurnOutcome(nextTurnOutcome(_turnOutcome.value, event), reason)
            }
        }
        viewModelScope.launch {
            repository.observeThinkingProgress(conversationId).filterNotNull().collect { clearTurnOutcome("thinking") }
        }

        // #1311: the daemon's first `turn_state` for this conversation, of any phase, closes the local-send
        // window. Collected here rather than behind a subscriber-bound stateIn, so it closes even while the
        // screen is not collecting.
        viewModelScope.launch {
            liveSessionEvents.collect { event ->
                if (event is LiveSessionEvent.TurnState && event.conversationId == conversationId) {
                    closeLocalSendWindow("turn_state")
                }
            }
        }

        // #1309: a conversation whose claude had not run yet reads no permission mode and no applied effort,
        // so the open thread asks again when any turn on its host ends and when a reset ends. Each new
        // connection starts a fresh running set. A bump while nothing collects [sessionSettings] sends nothing.
        viewModelScope.launch {
            runSettingsRereadEdges(liveSessionEvents).collect { reason ->
                rereadRunSettings(reason)
                if (reason == "reset_end") askForContextUsage(reason)
            }
        }

        // #1345: the MCP reading starts empty on every connection, so the thread asks once when its repository is
        // first available and again on each return, keyed there for the #861 reason. Gated as Channel
        // info's ask is. Only the reconnect ask logs: construction stays log-free, and the opening is already
        // logged as `thread_destination_bound`.
        viewModelScope.launch {
            var opened = false
            repositoryAvailable.distinctUntilChanged().collect { available ->
                if (!available) return@collect
                if (state.value.runConfig.mcpServersSupported) {
                    repository.requestMcpStatus(conversationId)
                    if (opened) RelayLog.d { "event=mcp_status_requested reason=reconnect" }
                }
                opened = true
            }
        }

        // #1410: ask for a fresh context reading when the thread opens on a live host and each time the host's
        // repository returns, as desktop does on open. Unlike the local-send collector above there is no
        // `drop(1)`: the opening availability is the open's own ask, and nothing else sends it. An opening `false` waits for the
        // repository's arrival. Each ask is one fire-and-forget frame; the reply lands on observeContextUsage.
        viewModelScope.launch {
            var opened = false
            repositoryAvailable
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    askForContextUsage(reason = if (opened) "reconnect" else null)
                    opened = true
                }
        }
    }

    /**
     * The Top overlay's MCP notice was tapped (#1345), desktop's `openMcpFailure`: acknowledge every server the
     * current report shows as failed, not only the one named, then open Channel info, which asks for fresh
     * status. Logs the count only.
     */
    fun onMcpFailureTapped() {
        val failed = failedMcpServerNames(state.value.mcpStatus.report)
        mcpAcknowledgements.acknowledge(serverId, conversationId, failed)
        RelayLog.d { "event=mcp_failure_acknowledged count=${failed.size}" }
        onOverflowEvent(ThreadEvent.ChannelInfo)
    }

    private fun openLocalSendWindow(): Long {
        if (_localSendStage.value == LocalSendStage.None) RelayLog.d { "event=local_send_window state=open" }
        localSendGeneration++
        _localSendStage.value = LocalSendStage.Sending
        return localSendGeneration
    }

    private fun closeLocalSendWindow(reason: String) {
        if (_localSendStage.value != LocalSendStage.None) RelayLog.d { "event=local_send_window state=closed reason=$reason" }
        localSendGeneration++
        _localSendStage.value = LocalSendStage.None
    }

    /**
     * Hand one send to the daemon inside the local window. Only its current generation may advance to
     * Waiting or close on failure; acceptance still tells the screen to follow the newest end (#1314).
     */
    private suspend fun <T> sendInLocalWindow(send: suspend () -> T): T {
        invalidateSuggestion("send")
        clearTurnOutcome("send")
        val generation = openLocalSendWindow()
        val sent =
            try {
                send()
            } catch (e: Throwable) {
                if (generation == localSendGeneration) closeLocalSendWindow("send_failed")
                throw e
            }
        if (generation == localSendGeneration && _localSendStage.value == LocalSendStage.Sending) {
            _localSendStage.value = LocalSendStage.Waiting
            RelayLog.d { "event=local_send_window state=waiting" }
        }
        RelayLog.d { "event=thread_send_accepted" }
        sentMessageChannel.trySend(Unit)
        return sent
    }

    /** The #1309 re-read edges as static reason codes: a turn ending on this host, and a reset ending. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runSettingsRereadEdges(liveSessionEvents: Flow<LiveSessionEvent>): Flow<String> =
        merge(
            repositoryAvailable
                .distinctUntilChanged()
                .flatMapLatest { available -> if (available) turnEndEdges(liveSessionEvents) else emptyFlow() }
                .map { "turn_end" },
            resetEndEdges(repository.observeResetting(conversationId)).map { "reset_end" },
        )

    /**
     * Ask for a fresh reading of this thread's run settings (#1309). The repository's `flatMapLatest` cancels
     * an in-flight read first, and a reply still replaces the whole reading. [reason] is a static code.
     */
    private fun rereadRunSettings(reason: String) {
        RelayLog.d { "event=run_settings_reread reason=$reason" }
        repository.refreshSessionSettings(conversationId)
    }

    /**
     * Ask for a fresh context reading on open, reconnect (#1410), and reset end (#1761). [reason] is a static
     * code, never the id. The open passes null because its destination binding is already logged and the ask
     * runs during construction.
     */
    private fun askForContextUsage(reason: String? = null) {
        if (reason != null) RelayLog.d { "event=context_usage_ask reason=$reason" }
        repository.requestContextUsage(conversationId)
    }

    /**
     * The reader pulled toward older messages at the thread's oldest end (#1352) — ask for the next page
     * back. The only ask besides Retry and the newest-page ask on each host arrival ([askForNewestPage]):
     * a page arriving never asks.
     *
     * Sends nothing while the host is not connected. [ThreadHistoryDemand.canAsk] drops an ask that
     * arrives while a request is outstanding or after the walk reached a terminal stop, and drops it
     * rather than queuing it.
     */
    fun onDemandOlderHistory() {
        if (!historySeed.isCompleted) {
            // Prefetch is movement-gated: completing the seed cannot replay an earlier movement.
            RelayLog.d { "event=history_ask_skipped reason=seeding" }
            return
        }
        if (!hostAvailable.value) {
            RelayLog.d { "event=history_ask_skipped reason=offline" }
            return
        }
        val claimed = claimHistorySlot { if (it.canAsk) it.asking() else null } ?: return
        launchHistoryAsk(claimed)
    }

    /**
     * The reader pressed the oldest-end retry affordance (#778) — ask again for the page that failed.
     *
     * Gated on [ThreadHistoryDemand.canRetry], so it is inert unless the walk actually stopped on a
     * retryable failure, and on the host being connected. The retry resumes from the **same** cursor,
     * keeping every loaded row and the walk's position across both the failure and the retry.
     */
    fun onRetryOlderHistory() {
        if (!hostAvailable.value) return
        val claimed = claimHistorySlot { if (it.canRetry) it.asking() else null } ?: return
        launchHistoryAsk(claimed)
    }

    /**
     * The open thread's host became available (#1572) — ask for the newest page, which brings back a reply
     * stored while the thread was off-screen. The page merges through the repository's dedup, so rows
     * already drawn do not repeat.
     *
     * It takes the walk's one outstanding-request slot, waiting behind a pull or retry. When
     * the walk's own next ask would carry the empty cursor anyway, the page is the walk's and settles as a
     * pull's would; otherwise the walk's cursor, saved position and stop reason are left as they were.
     */
    private fun askForNewestPage(reconnect: Boolean) {
        if (reconnect) RelayLog.d { "event=history_newest_ask reason=reconnect" }
        pendingNewest++
        drainNewestPages()
    }

    private fun drainNewestPages() {
        if (pendingNewest == 0 || !hostAvailable.value || historyDemand.value.inFlight) return
        // Re-set on every run of the claim, so after the loop it describes the claim that won.
        var walkPage = false
        val claimed =
            claimHistorySlot {
                val coverage = historyCoverage.value
                walkPage = it.newestPageAdvancesWalk ||
                    it.stoppedBy == HistoryWalkStop.AtStart &&
                    coverage.spans.isEmpty() &&
                    coverage.newestCursor == ""
                when {
                    walkPage -> it.asking()
                    !it.inFlight -> it.askingNewest()
                    else -> null
                }
            }
        when {
            claimed == null -> RelayLog.d { "event=history_newest_ask_skipped reason=in_flight" }
            walkPage -> {
                pendingNewest--
                launchHistoryAsk(claimed)
            }
            else -> {
                pendingNewest--
                launchNewestPageSideAsk()
            }
        }
    }

    /**
     * A newest-page ask that is not the walk's page. The repository merges rows; only coverage and
     * gap cursors advance here, leaving the backwards walk unchanged. A failure is logged with a
     * static event only and shows nothing, since the reader asked for no page; the next host arrival asks again.
     */
    private fun launchNewestPageSideAsk() {
        viewModelScope.launch {
            try {
                val page = repository.requestHistory(conversationId, cursor = "", limit = THREAD_HISTORY_PAGE_SIZE)
                recordCoverage(page, newest = true)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                RelayLog.d { "event=history_newest_ask_failed" }
            } catch (e: IllegalStateException) {
                RelayLog.d { "event=history_newest_ask_failed" }
            } catch (e: IllegalArgumentException) {
                RelayLog.d { "event=history_newest_ask_failed" }
            }
            historyDemand.update { it.newestSettled() }
            drainNewestPages()
        }
    }

    /**
     * The walk's single ask site.
     *
     * The repository has already merged the returned page into the thread. Here its durable ids update
     * coverage and its cursor/atStart advance the independent backwards walk; no row is rendered again.
     *
     * Exactly one ask is outstanding at a time ([claimHistorySlot]), so every settle and fail belongs to
     * the current ask. A page that settles across a reconnect still applies: its cursor stays valid.
     *
     * A received page's position is saved (#1354) before the settle releases the slot, so the one
     * outstanding ask also orders the saves. A failed ask saves nothing, leaving the saved position as it
     * was; a refused cursor clears it.
     */
    private fun launchHistoryAsk(claimed: ThreadHistoryDemand) {
        viewModelScope.launch {
            try {
                val page = fetchHistoryPage(claimed) ?: return@launch
                historyCoverage.update { it.received(page, newest = claimed.cursor.isEmpty()) }
                val coverage = historyCoverage.value
                val settled =
                    if (page.atStart && coverage.unsignedIncomplete) {
                        // Retain a usable walk and visible unknown state until signed consumers migrate.
                        RelayLog.d { "event=history_completeness_unavailable reason=unsigned_identity" }
                        claimed.failed(retryable = false)
                    } else {
                        claimed.settled(pageCursor = page.cursor, atStart = page.atStart)
                    }
                repository.writeHistoryPosition(
                    conversationId,
                    HistoryPosition(settled.cursor, settled.stoppedBy == HistoryWalkStop.AtStart, coverage),
                )
                historyDemand.update { settled }
            } finally {
                drainNewestPages()
            }
        }
    }

    private suspend fun recordCoverage(
        page: HistoryPage,
        newest: Boolean = false,
        target: ULong? = null,
    ) {
        historyCoverage.update { it.receivedUnsigned(page, newest, target) }
        if (historyCoverage.value.unsignedIncomplete) {
            historyDemand.update { if (it.stoppedBy == HistoryWalkStop.AtStart) it.copy(stoppedBy = null) else it }
        }
        val walk = historyDemand.value
        repository.writeHistoryPosition(
            conversationId,
            HistoryPosition(walk.cursor, walk.stoppedBy == HistoryWalkStop.AtStart, historyCoverage.value),
        )
    }

    fun onDemandHistoryGap(anchor: Long) {
        if (anchor >= 0) onDemandUnsignedHistoryGap(anchor.toULong())
    }

    fun onDemandUnsignedHistoryGap(anchor: ULong) {
        if (!historySeed.isCompleted || !hostAvailable.value) return
        val coverage = historyCoverage.value
        if (anchor == 0uL &&
            coverage.unsignedUnknownEdge == null ||
            anchor != 0uL &&
            coverage.unsignedGaps.none { it.anchor == anchor }
        ) {
            return
        }
        claimHistorySlot { if (!it.inFlight) it.askingNewest() else null } ?: return
        val cursor = coverage.cursorForUnsigned(anchor)
        viewModelScope.launch {
            try {
                recordCoverage(
                    repository.requestHistory(conversationId, cursor, limit = THREAD_HISTORY_PAGE_SIZE),
                    newest = cursor.isEmpty(),
                    target = anchor,
                )
                RelayLog.d { "event=history_gap_page_received" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RelayErrorException) {
                if (error.code == HISTORY_INVALID_CURSOR && cursor.isNotEmpty()) {
                    historyCoverage.update { it.refusedUnsigned(anchor) }
                    val walk = historyDemand.value
                    repository.writeHistoryPosition(
                        conversationId,
                        HistoryPosition(walk.cursor, walk.stoppedBy == HistoryWalkStop.AtStart, historyCoverage.value),
                    )
                    RelayLog.d { "event=history_gap_cursor_refused" }
                } else {
                    RelayLog.d { "event=history_gap_ask_failed" }
                }
            } catch (error: IllegalStateException) {
                RelayLog.d { "event=history_gap_ask_failed" }
            } catch (error: IllegalArgumentException) {
                RelayLog.d { "event=history_gap_ask_failed" }
            } finally {
                historyDemand.update { it.newestSettled() }
                drainNewestPages()
            }
        }
    }

    /** Ask for the page at [claimed]'s cursor, or settle the failure and return `null`. */
    private suspend fun fetchHistoryPage(claimed: ThreadHistoryDemand): HistoryPage? {
        try {
            return repository.requestHistory(conversationId, claimed.cursor, limit = THREAD_HISTORY_PAGE_SIZE)
        } catch (e: CancellationException) {
            throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
        } catch (e: RelayErrorException) {
            // A server error frame. Only the CODE is read, and only to choose a branch; e.message is
            // server-supplied and is never read, logged or surfaced. An unknown or differently-cased
            // code falls through to the failure branch, so the fallback here is the safe one.
            if (e.code == HISTORY_INVALID_CURSOR && claimed.cursor.isNotEmpty()) {
                // #1352: the daemon refused the cursor. The next gesture asks from the newest page;
                // nothing asks now. Reset the saved backwards position without dropping durable gaps.
                RelayLog.d { "event=history_cursor_refused" }
                val coverage = historyCoverage.value.takeIf { it.spans.isNotEmpty() || it.unknown }
                repository.writeHistoryPosition(conversationId, coverage?.let { HistoryPosition("", false, it) })
                historyDemand.update { it.cursorRefused() }
            } else {
                // A refusal of the NEWEST-page ask has nothing to fall back to, so it is a failure.
                failWalk(retryable = e.retryable)
            }
        } catch (e: IllegalStateException) {
            // A not-connected session, #488's teardown sweep, or the not-wired interface default. Not
            // retryable: a button with no connection behind it cannot work. A gesture after the
            // reconnect asks again (#1352).
            failWalk(retryable = false)
        } catch (e: IllegalArgumentException) {
            // An unknown conversation id, or a malformed page — kotlinx.serialization's
            // SerializationException is an IllegalArgumentException, so the decode failure lands here.
            failWalk(retryable = false)
        }
        return null
    }

    /** Settle a failed ask. [retryable] is a flag, never text. */
    private fun failWalk(retryable: Boolean) {
        RelayLog.d { "event=history_ask_failed retryable=$retryable" }
        historyDemand.update { it.failed(retryable = retryable) }
    }

    /**
     * Claim the walk's single outstanding-request slot under [claim]'s rule, returning the claimed demand
     * (whose `cursor` is the one to ask with) or `null` when the ask must be dropped.
     *
     * A [MutableStateFlow.compareAndSet] loop rather than a read-then-assign: the settle runs in a
     * launched coroutine, so a check-then-act would admit two concurrent asks through the window between
     * reading the rule and writing the in-flight flag. [claim] is pure and cheap, so re-running it on a
     * lost CAS is free.
     */
    private fun claimHistorySlot(claim: (ThreadHistoryDemand) -> ThreadHistoryDemand?): ThreadHistoryDemand? {
        while (true) {
            val current = historyDemand.value
            val claimed = claim(current) ?: return null
            if (historyDemand.compareAndSet(current, claimed)) return claimed
        }
    }

    /** Record an edit to this chat's composer (#789). Exact text; the store clears only on `""`. */
    fun onDraftChange(text: String) {
        _suggestedReply.value = null
        draftStore.setDraft(serverId, conversationId, text)
        refreshSuggestedReply()
    }

    /** Snapshot source into this destination's latest draft on Main, without sending or touching files. */
    fun replyToMessage(message: Message): String? {
        val label =
            when (message.role) {
                Role.User -> "User"
                Role.Assistant -> "Assistant"
                Role.Tool -> return null
            }
        val latest = draftStore.draftFor(serverId, conversationId)
        val separator = if (latest.isNotEmpty() && !latest.endsWith('\n')) "\n" else ""
        val quoted = latest + separator + label + ":\n\"" + message.content + "\"\n"
        onDraftChange(quoted)
        RelayLog.d { "event=message_reply_staged" }
        return quoted
    }

    /**
     * Send [text] trimmed, as desktop's `submitMessage` does (#1355): whitespace-only text sends nothing
     * and leaves the draft as it was. The draft clears **before** the send launches, and the repository
     * draws the echo before the daemon replies, so the message shows the moment Send is tapped.
     *
     * A refused send — a [de.pyryco.mobile.data.network.RelayErrorException] server error, a not-connected
     * [IllegalStateException] or an unwired [UnsupportedOperationException], each swallowed by
     * [launchGuardedRepoCall] — does not put the text back: the echo stays in the thread, as on desktop,
     * which has no failure surface for a send. This drops #789's restore-on-failure.
     */
    fun sendMessage(text: String) {
        if (!connectedFor("send")) return
        if (_attachmentsSending.value) return
        val trimmed = text.trim()
        // #1328: text is required even with files pending, as on desktop; blank leaves them for the next send.
        if (trimmed.isEmpty()) return
        submitMessage(trimmed) { withAttachments ->
            if (!withAttachments || draftStore.draftFor(serverId, conversationId) == text) onDraftChange("")
        }
    }

    private fun submitMessage(
        text: String,
        onReady: (Boolean) -> Unit,
    ) {
        invalidateSuggestion("send")
        val attachments = draftStore.attachmentsFor(serverId, conversationId)
        if (attachments.isNotEmpty()) {
            return sendWithAttachments(text, attachments, onUploaded = { onReady(true) }, onSent = {})
        }
        onReady(false)
        launchGuardedRepoCall {
            effortRecall.awaitWrite()
            sendInLocalWindow { repository.sendMessage(conversationId, text) }
        }
    }

    /**
     * Send [text] naming [attachments], this chat's pending entries as they stood when send was tapped
     * (#932). [text] is nonblank: ordinary drafts are trimmed, while [sendSuggestedReply] preserves the
     * confirmed offer verbatim. [onComposerCommand] passes its command.
     *
     * Each entry without an acknowledged id is read and uploaded in order, and its id recorded in
     * [draftStore] as soon as the daemon acknowledges it, so a later failure never costs a retry that
     * upload. One file's bytes are live at a time. The first failed read or upload stops the send before
     * anything else is uploaded or sent; a thrown upload or send is swallowed by [launchGuardedRepoCall].
     * Either way the text and every entry stay in the draft.
     *
     * Once every upload has succeeded, and before the send (#1355), [onUploaded] runs — [sendMessage]'s
     * guarded draft clear, which runs only while the store still holds the text as typed: the store, not the
     * derived [draft], because the exposed flow lags an edit made from inside a running coroutine. Both sides
     * and [onDraftChange] run on `viewModelScope`'s `Dispatchers.Main.immediate`, so text typed during the
     * uploads is never swallowed. Only the snapshot's entries are then removed, so an attachment added during
     * the uploads survives. After the send returns, [onSent] runs — [onComposerCommand]'s log, which leaves the
     * draft alone (#1348). A send that then fails restores neither, as a failed text send restores nothing.
     */
    private fun sendWithAttachments(
        text: String,
        attachments: List<PendingAttachment>,
        onUploaded: () -> Unit = {},
        onSent: () -> Unit,
    ) {
        _attachmentsSending.value = true
        val copies = attachments.mapNotNull { it.ownedPaste }.filter { it.retain() }
        val released = AtomicBoolean()
        val releaseCopies = { if (released.compareAndSet(false, true)) copies.forEach { it.release() } }
        // Covers a launch into an already-cancelled ViewModel scope, whose body never starts.
        val cancellation = viewModelScope.coroutineContext[Job]?.invokeOnCompletion { releaseCopies() }
        launchGuardedRepoCall {
            try {
                val target = state.value.conversationId
                val references = mutableListOf<MessageAttachment>()
                val originals = mutableMapOf<String, String>()
                for (entry in attachments) {
                    val id = entry.attachmentId ?: upload(target, entry) ?: return@launchGuardedRepoCall
                    // #983: the thread row names each file as it was uploaded.
                    references += MessageAttachment(id, entry.displayName, entry.mimeType)
                    if (entry.ownedPaste == null) originals[id] = entry.uri
                }
                // #984: before the send, because the confirmed row can be drawn while it is suspended. A
                // send that then fails leaves harmless entries: its retry names the same ids.
                draftStore.recordSentOriginals(serverId, conversationId, originals)
                onUploaded()
                draftStore.removeAttachments(serverId, conversationId, attachments.mapTo(HashSet()) { it.key })
                // #686: a message sent while this opening's recall write is outstanding follows it.
                effortRecall.awaitWrite()
                sendInLocalWindow { repository.sendMessage(target, text, references) }
                onSent()
            } finally {
                releaseCopies()
                cancellation?.dispose()
                // #933: however the send ended — sent, stopped by a failed read or upload, or a swallowed throw.
                _attachmentsSending.value = false
                _attachmentUploadProgress.value = null
            }
        }
    }

    /**
     * Read and upload one pending entry (#932): its acknowledged id, or `null` after logging why not and
     * sending one [attachmentSendFailures] notice (#1325).
     */
    private suspend fun upload(
        target: String,
        entry: PendingAttachment,
    ): String? {
        val bytes =
            when (val read = entry.ownedPaste?.read(ioDispatcher) ?: attachmentReader.read(entry.uri)) {
                is AttachmentRead.Bytes -> read.bytes
                AttachmentRead.TooLarge -> return attachmentSendFailed("read_too_large", AttachmentSendFailure.TOO_LARGE)
                AttachmentRead.Unreadable -> return attachmentSendFailed("read_failed", AttachmentSendFailure.UNREADABLE)
            }
        val result =
            repository.uploadAttachment(target, bytes, entry.displayName, entry.mimeType) { sent, total ->
                _attachmentUploadProgress.value = attachmentUploadProgress(entry.key, sent, total)
            }
        _attachmentUploadProgress.value = null
        when (result) {
            is AttachmentUploadResult.Stored -> {
                draftStore.markUploaded(serverId, conversationId, entry.key, result.attachmentId)
                return result.attachmentId
            }
            is AttachmentUploadResult.Failed -> return attachmentSendFailed("upload_failed", attachmentSendFailure(result))
        }
    }

    private fun attachmentSendFailed(
        outcome: String,
        failure: AttachmentSendFailure,
    ): String? {
        RelayLog.d { "event=composer_attachment_send outcome=$outcome" }
        attachmentSendFailureChannel.trySend(failure)
        return null
    }

    /**
     * Add a file to this chat's pending attachments (#932). The outcome is returned so the UI can show a
     * refusal; a refusal is also logged, by static code only — never the URI, name or type.
     */
    fun addAttachment(
        uri: String,
        displayName: String,
        mimeType: String,
        size: Long?,
        ownedPaste: OwnedPasteCopy? = null,
    ): AttachmentAddOutcome {
        val outcome = draftStore.addAttachment(serverId, conversationId, uri, displayName, mimeType, size, ownedPaste)
        when (outcome) {
            AttachmentAddOutcome.ADDED -> Unit
            AttachmentAddOutcome.TOO_LARGE -> RelayLog.d { "event=composer_attachment_add outcome=too_large" }
            AttachmentAddOutcome.TOO_MANY -> RelayLog.d { "event=composer_attachment_add outcome=too_many" }
        }
        return outcome
    }

    /**
     * Add what the picker returned (#933), in its order, through [addAttachment]. Refused entries are skipped
     * and the rest still added; when any were refused, one [attachmentRefusals] notice counts them by reason.
     */
    fun addPickedAttachments(picked: List<PickedAttachment>) {
        var tooLarge = 0
        var tooMany = 0
        for (entry in picked) {
            when (addAttachment(entry.uri, entry.displayName, entry.mimeType, entry.size, entry.ownedPaste)) {
                AttachmentAddOutcome.ADDED -> Unit
                AttachmentAddOutcome.TOO_LARGE -> tooLarge++
                AttachmentAddOutcome.TOO_MANY -> tooMany++
            }
        }
        if (tooLarge > 0 || tooMany > 0) attachmentRefusalChannel.trySend(AttachmentRefusal(tooLarge, tooMany))
    }

    /**
     * A message attachment's row is on screen (#984): start its load unless it already has a state, and only
     * when it is fetched on sight ([loadsOnShow], #1329); any other file waits for [onAttachmentRequested].
     * The claim is a compare-and-set, so a row shown twice loads once, and a failure waits for
     * [onRetryAttachment].
     */
    fun onAttachmentShown(attachment: MessageAttachment) {
        if (!loadsOnShow(attachment)) return
        if (claimAttachment(attachment.attachmentId) { it == null }) loadAttachment(attachment.attachmentId)
    }

    /**
     * A file not fetched yet was tapped or long-pressed (#1329): load it as a shown row would, and once it is
     * ready deliver [action] once through [attachmentLoads]. Only a tap that claims the load records the
     * action, so a tap on a row already loading, ready or failed does nothing here.
     */
    fun onAttachmentRequested(
        attachment: MessageAttachment,
        action: AttachmentAction,
    ) {
        val id = attachment.attachmentId
        if (!claimAttachment(id) { it == null }) return
        pendingAttachmentRequests[id] = attachment to action
        RelayLog.d { "event=thread_attachment_request id=$id action=${action.name.lowercase()}" }
        loadAttachment(id)
    }

    /** The retry control of a failed attachment (#984). Not found is final and has none. */
    fun onRetryAttachment(attachmentId: String) {
        if (claimAttachment(attachmentId) { it == AttachmentViewState.Failed }) loadAttachment(attachmentId)
    }

    /** Set [attachmentId] to loading if its state passes [claimable]; whether this call did. */
    private fun claimAttachment(
        attachmentId: String,
        claimable: (AttachmentViewState?) -> Boolean,
    ): Boolean {
        var claimed = false
        _attachmentStates.update { states ->
            claimed = claimable(states[attachmentId])
            if (claimed) states + (attachmentId to AttachmentViewState.Loading) else states
        }
        return claimed
    }

    /**
     * Show the phone's own original while it can still be read, else retrieve the bytes from this
     * thread's host (#984). Logs the id and a static outcome only: never a name, URI or path.
     */
    private fun loadAttachment(attachmentId: String) {
        viewModelScope.launch {
            val (state, outcome) =
                try {
                    val original = draftStore.sentOriginal(serverId, conversationId, attachmentId)
                    if (original != null && attachmentReader.canRead(original)) {
                        AttachmentViewState.Ready(AttachmentSource.Original(original), null, null) to "original"
                    } else {
                        retrieved(repository.retrieveAttachment(conversationId, attachmentId))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A repository that keeps no files throws; its message is not read.
                    AttachmentViewState.Failed to "failed"
                }
            RelayLog.d { "event=thread_attachment_load id=$attachmentId outcome=$outcome" }
            _attachmentStates.update { it + (attachmentId to state) }
            // #1329: a tap's action settles with its load, once; a load that did not end ready drops it.
            val (attachment, action) = pendingAttachmentRequests.remove(attachmentId) ?: return@launch
            val ready = state as? AttachmentViewState.Ready
            ready?.let { attachmentLoadChannel.trySend(AttachmentLoaded(attachmentTarget(attachment, it), it.source, action)) }
            RelayLog.d { "event=thread_attachment_request id=$attachmentId outcome=${if (ready != null) "delivered" else "dropped"}" }
        }
    }

    private fun retrieved(result: AttachmentRetrievalResult): Pair<AttachmentViewState, String> =
        when (result) {
            is AttachmentRetrievalResult.Retrieved ->
                AttachmentViewState.Ready(AttachmentSource.Kept(result.file), result.displayName, result.mimeType) to "retrieved"
            AttachmentRetrievalResult.NotFound -> AttachmentViewState.NotFound to "not_found"
            AttachmentRetrievalResult.TooLarge,
            AttachmentRetrievalResult.Invalid,
            AttachmentRetrievalResult.Unavailable,
            -> AttachmentViewState.Failed to "failed"
        }

    /**
     * A ready markdown attachment was tapped (#1027): read and strictly decode its kept file first, so a file
     * that cannot be shown leaves the operator here with the open-failed notice, and only a readable one opens
     * the reader, by id alone. Ignored while an earlier open is still reading. Logs the id and a static outcome.
     */
    fun onOpenMarkdownAttachment(attachmentId: String) {
        if (markdownOpenJob?.isActive == true) return
        markdownOpenJob =
            viewModelScope.launch {
                val document = readMarkdownAttachment(repository, conversationId, attachmentId, ioDispatcher)
                RelayLog.d { "event=thread_attachment_open id=$attachmentId outcome=${if (document != null) "reader" else "failed"}" }
                if (document != null) {
                    navigationChannel.send(ThreadNavigation.OpenMarkdown(attachmentId))
                } else {
                    markdownOpenFailureChannel.send(Unit)
                }
            }
    }

    /**
     * A markdown link in an assistant reply was tapped (#1050): read [path] live from this conversation's
     * workspace, one request per open, and open the reader only on a note it can show; anything else leaves
     * the operator here with the open-failed notice. Ignored while any open is still reading, so a second tap
     * sends nothing. The note is held for the reader's destination ([linkedMarkdown]), never saved. Logs a
     * static outcome only: never the path, the name or the text.
     */
    fun onOpenMarkdownLink(path: String) {
        if (markdownOpenJob?.isActive == true) return
        linkedMarkdownNote = null
        markdownOpenJob =
            viewModelScope.launch {
                val document = readLinkedMarkdown(repository, conversationId, path)
                RelayLog.d { "event=thread_markdown_link_open outcome=${if (document != null) "reader" else "failed"}" }
                if (document != null) {
                    linkedMarkdownNote = LinkedMarkdown(path, document)
                    navigationChannel.send(ThreadNavigation.OpenLinkedMarkdown)
                } else {
                    markdownOpenFailureChannel.send(Unit)
                }
            }
    }

    /**
     * The note [onOpenMarkdownLink] last read, with the path it read, for the reader it opens (#1050) and that
     * reader's Refresh (#1067); `null` once released.
     */
    fun linkedMarkdown(): LinkedMarkdown? = linkedMarkdownNote

    /** Drop the held note (#1050): the operator is back on the thread, so its reader has closed. */
    fun releaseLinkedMarkdown() {
        linkedMarkdownNote = null
    }

    /** Remove one pending attachment from this chat (#932), leaving the rest in order. */
    fun removeAttachment(key: Long) {
        draftStore.removeAttachment(serverId, conversationId, key)
    }

    /**
     * Send the Actions menu's [action] command (#884) as an ordinary message to this conversation, through
     * a guarded send, so a failed send is handled exactly as a composer message's. With pending files it goes
     * through [sendWithAttachments], as desktop's `sendText` takes them for both its callers (#1348), and so
     * opens the local send window that a text-only command does not; it is refused while an earlier
     * attachment send still owns them. It leaves the
     * typed draft alone, so there is no clear on success. A command the published menu proves absent is
     * refused here too, behind the greyed-out row. Reset session carries no command and never comes this
     * way. Logs static codes only.
     */
    fun onComposerCommand(action: ComposerAction) {
        if (!connectedFor("composer_action")) return
        val command = action.command ?: return
        if (action in state.value.absentActions) {
            RelayLog.d { "event=composer_action action=${action.value} outcome=absent" }
            return
        }
        if (_attachmentsSending.value) {
            RelayLog.d { "event=composer_action action=${action.value} outcome=busy" }
            return
        }
        val attachments = draftStore.attachmentsFor(serverId, conversationId)
        if (attachments.isNotEmpty()) {
            return sendWithAttachments(command, attachments) {
                RelayLog.d { "event=composer_action action=${action.value} outcome=sent" }
            }
        }
        launchGuardedRepoCall {
            effortRecall.awaitWrite()
            clearTurnOutcome("send")
            repository.sendMessage(conversationId, command)
            RelayLog.d { "event=composer_action action=${action.value} outcome=sent" }
        }
    }

    fun retry() {
        viewModelScope.launch { connectionStateSource.retry() }
    }

    fun onWorkspaceChipTapped() {
        pendingWorkspacePicker.value = true
    }

    fun onWorkspacePicked(path: String) {
        pendingWorkspacePicker.value = false
        sendChangeWorkspace(path)
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = false
    }

    /**
     * Handle a tap on modal option [optionId] (#451). The modal being answered is the VM's own
     * [currentModal] — **never** a caller-supplied id (the stateless screen passes only the tapped option;
     * the `modalId` is the VM's server-supplied state). Fail-safe-deny UX belt: the producer's highlighted
     * default ([ModalUiState.Open.defaultOptionId]) answers on a **single** tap; any other option **arms**
     * and requires an explicit **second** confirm of the *same* armed option before it sends; a tap of a
     * different option re-arms. No-op if no modal is open.
     */
    fun onModalOption(
        optionId: String,
        modalId: String? = null,
    ) {
        val open = scopedModal() as? ModalUiState.Open ?: return
        // #1306: a tap composed for a request that has since been replaced carries the old id.
        if (modalId != null && modalId != open.modalId) return
        // #1321: checked before the send or the arm, so a disabled option's tap changes nothing.
        if (!promptSendAllowed("permission_option")) return
        when {
            optionId == open.defaultOptionId -> sendAnswer(open.modalId, optionId, grantsAlwaysAllow(open, optionId))
            armedModalOption.value == ArmedModalOption(open.modalId, optionId) ->
                sendAnswer(open.modalId, optionId, grantsAlwaysAllow(open, optionId))
            else -> armedModalOption.value = ArmedModalOption(open.modalId, optionId)
        }
    }

    /** Cancel the currently-open modal (#451): clear any arm and send `modal_cancel`. No-op if no modal is
     *  open, or if [modalId] names a request that has been replaced (#1306). A cancel never carries the
     *  session grant, and it drops any acceptance (#818). */
    fun onModalCancel(modalId: String? = null) {
        val open = scopedModal() as? ModalUiState.Open ?: return
        if (modalId != null && modalId != open.modalId) return
        // #1321: before the arm and the grant draft are dropped, so a refused cancel keeps both.
        if (!promptSendAllowed("permission_cancel")) return
        armedModalOption.value = null
        grantDrafts.set(serverId, conversationId, null)
        sendCancel(open.modalId)
    }

    /**
     * The reader left this conversation's screen (#1306): drop any armed non-default option, so coming back
     * takes two fresh taps. The session-grant draft is kept; it belongs to the request, not the visit.
     */
    fun onConversationLeft() {
        armedModalOption.value = null
    }

    /** The rejection notice's X (#1340): this chat stops showing it. */
    fun onAnswerRejectionDismissed() {
        recordModalAction(ModalAction.RejectionDismissed(conversationId))
    }

    /**
     * Accept or withdraw the open prompt's "don't ask again this session" offer (#818). [modalId] is the
     * prompt the checkbox was drawn for, used only as a guard: a tap on a stale frame of a prompt that has
     * since been replaced is ignored rather than accepting the replacement. No-op unless this thread's
     * modal is open and offers the grant. It never sends and never arms, so accepting the offer is not the
     * second-tap confirmation of a non-default option.
     */
    fun onAlwaysAllowChanged(
        modalId: String,
        accepted: Boolean,
    ) {
        val open = scopedModal() as? ModalUiState.Open ?: return
        if (open.modalId != modalId || !open.offersAlwaysAllow) return
        grantDrafts.set(serverId, conversationId, if (accepted) open.alwaysAllowKey() else null)
    }

    /**
     * Whether answering [open] with [optionId] carries the session grant (#818): the prompt offers it, the
     * user accepted this exact offer, and the answer is an allow. A deny never carries it, matching the
     * desktop; the daemon would ignore it there anyway.
     */
    private fun grantsAlwaysAllow(
        open: ModalUiState.Open,
        optionId: String,
    ): Boolean =
        optionId in ALWAYS_ALLOW_OPTION_IDS &&
            open.offersAlwaysAllow &&
            grantDrafts.current(serverId, conversationId) == open.alwaysAllowKey()

    /**
     * The input guard's read of this thread's modal (#816). It reads the host flow synchronously instead of
     * [currentModal]'s collected copy, so a modal raised by another conversation can never be answered from
     * this thread, not even in the instant before that copy updates.
     */
    private fun scopedModal(): ModalUiState = hostModal.value.scopedTo(conversationId)

    /**
     * Send a `modal_answer` for [optionId] of modal [modalId] via the injected outbound path. Clears the arm
     * and closes the prompt **before** launching (#1340, desktop `answerPrompt`): the card leaves at once and
     * the daemon's later `modal_dismissed` for it is ignored. Catches **only** the two documented throws so
     * [kotlinx.coroutines.CancellationException] still propagates. A [RelayErrorException] is the daemon
     * refusing the answer, so this chat shows the rejection notice; an [IllegalStateException] means the
     * answer never reached the daemon, which re-sends the still-outstanding prompt on the next connection, so
     * nothing is shown. No modal field or daemon text is logged or kept.
     */
    private fun sendAnswer(
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean,
    ) {
        armedModalOption.value = null
        recordModalAction(ModalAction.AnsweredHere(modalId))
        viewModelScope.launch {
            try {
                answerModal(modalId, optionId, alwaysAllow)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                RelayLog.d { "event=permission_answer outcome=rejected" }
                recordModalAction(ModalAction.Rejected(conversationId))
            } catch (e: IllegalStateException) {
                RelayLog.d { "event=permission_answer outcome=unsent" }
            }
        }
    }

    /** The [sendAnswer] mirror for `modal_cancel` (no option id, no idempotency token): the prompt closes at
     *  once, and a refused or unsent cancel shows nothing (#1340, desktop `cancelPrompt`). */
    private fun sendCancel(modalId: String) {
        recordModalAction(ModalAction.AnsweredHere(modalId))
        viewModelScope.launch {
            try {
                cancelModal(modalId)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                RelayLog.d { "event=permission_cancel outcome=refused" }
            } catch (e: IllegalStateException) {
                RelayLog.d { "event=permission_cancel outcome=unsent" }
            }
        }
    }

    /** Stop this ViewModel's conversation. Sends whenever the host is connected (#1319); the daemon is authoritative on
     *  whether its turn is running and on the interactive gate. The affordance passes no arguments:
     *  [sendInterrupt] supplies the saved open [conversationId], without changing local turn state. */
    fun onInterrupt() {
        if (!connectedFor("interrupt")) return
        sendInterrupt()
    }

    /**
     * The [sendCancel] structural twin for `interrupt`, with **empty catch bodies**: interrupt is inert
     * on failure (AC #3 — no error channel, no log; any user-visible surface is #459's concern). The
     * not-connected path surfaces as [IllegalStateException] (coordinator null-guard / repo `check`).
     *
     * The [RelayErrorException] catch is **unreachable on the real path** — interrupt is fire-and-forget
     * (plain `pump.send`, no awaited reply), so a correlated server `error` can never originate. It is
     * retained for (1) the AC #4 relay-error-swallow test (exercised via an injected throwing double) and
     * (2) exact parity with [sendCancel], so the two outbound senders read identically. The
     * [CancellationException] rethrow **MUST precede** the typed catches (`j.u.c.CancellationException`
     * extends `IllegalStateException` on the JVM) so structured cancellation is never swallowed.
     */
    private fun sendInterrupt() {
        viewModelScope.launch {
            try {
                interrupt(conversationId)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                // Inert: unreachable on the real fire-and-forget path; retained for parity + AC #4 test.
            } catch (e: IllegalStateException) {
                // Inert: not-connected / pre-Open send fails silently (AC #3).
            }
        }
    }

    /**
     * Route the overflow "New session" tap (#540) to [ConversationRepository.startNewSession] — the bare
     * fire-and-forget `new_session` v2 send (#539). The **surfacing** twin of [sendInterrupt]: it always
     * passes the VM's own [conversationId] (never a caller-supplied id) with `workspace` defaulted, and
     * **discards** the returned placeholder [Session] — success is passive, the #336 fold renders the
     * delimiter when `session_transition` arrives.
     *
     * Only **two** catches, unlike the interrupt/drop swallow twins: `new_session` is fire-and-forget (no
     * awaited reply), so a correlated server `error` — hence [RelayErrorException] — can never originate;
     * adding that branch would be dead code routing a never-thrown error to the UI (evidence-based fix
     * selection). The not-connected [IllegalStateException] is the sole client-observable failure and is
     * surfaced via [newSessionErrorChannel]. The [CancellationException] rethrow **MUST precede** the typed
     * catch (`j.u.c.CancellationException` extends `IllegalStateException` on the JVM) so structured
     * cancellation on screen-exit teardown is never masked as a not-connected failure.
     */
    private fun sendNewSession() {
        viewModelScope.launch {
            try {
                repository.startNewSession(conversationId)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catch: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: IllegalStateException) {
                newSessionErrorChannel.trySend(Unit)
            }
        }
    }

    /**
     * Route the overflow / Channel-Info "Archive" tap (#556) to [ConversationRepository.archive] and
     * surface a failure. The **surfacing** twin of [sendNewSession] — always passes the VM's own
     * [conversationId] (never a caller-supplied id) — with two deliberate differences:
     *
     *  1. **Two catches, not one.** Archive is **request/reply** ([RemoteConversationRepository.archive]
     *     awaits a `conversation_updated`), so a server `error` reply — hence [RelayErrorException] — is a
     *     real, reachable outcome and is caught alongside the not-connected [IllegalStateException]. Both
     *     map to the same payload-free [archiveErrorChannel] signal; the caught `message` is **never** read
     *     (the server-supplied [RelayErrorException.message] must not reach the surface — AC #4). The
     *     [IllegalArgumentException] `conversation.not_found` and the #318 decode exception are **not**
     *     caught: not_found is unreachable (you only archive the conversation you are viewing, whose record
     *     is in the list by construction — parity with the shipped guard / #530), and a malformed reply is
     *     a fail-loud protocol violation.
     *  2. **Success-only [ThreadNavigation.PopBack].** It fires **only** after [ConversationRepository
     *     .archive] returns without throwing; a failure emits on [archiveErrors] and stays on the thread.
     *     On success the list is list-driven — the confirmed upsert makes `observeConversations` re-emit
     *     without this conversation, so there is no explicit removal call.
     *
     * The [CancellationException] rethrow **MUST precede** the typed catches (`j.u.c.CancellationException`
     * extends [IllegalStateException] on the JVM) so screen-exit teardown mid-archive propagates cleanly and
     * is never mis-surfaced as an archive failure (AC #3).
     */
    private fun sendArchive() {
        viewModelScope.launch {
            try {
                repository.archive(conversationId)
                // #1399: the reply may already have popped through [conversations]; leave once.
                leaveForList()
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                archiveErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                archiveErrorChannel.trySend(Unit)
            }
        }
    }

    /**
     * Route a Workspace-Picker selection (#561) to [ConversationRepository.changeWorkspace] and surface a
     * failure. Combines the two established surfacing twins: the **catch set** matches [sendArchive] (two
     * types — change_workspace is request/reply, so a server `error` reply is reachable), while the
     * **success continuation** matches [sendNewSession] (passive — no side effect):
     *
     *  1. **Two catches.** [RelayErrorException] from a server `error` reply (incl. the daemon rejecting an
     *     out-of-`$HOME` / empty path as `protocol.malformed`) is caught alongside the not-connected
     *     [IllegalStateException]; both map to the same payload-free [changeWorkspaceErrorChannel] signal.
     *     The caught `message` is **never** read (the server-supplied [RelayErrorException.message] must not
     *     reach the surface — AC #2). The [IllegalArgumentException] `conversation.not_found` and the #318
     *     decode exception are **not** caught: not_found is unreachable (you only change the workspace of the
     *     conversation you are viewing, whose record is in the list by construction — parity with the shipped
     *     guard / #530 / #556), and a malformed reply is a fail-loud protocol violation.
     *  2. **No [ThreadNavigation.PopBack]** (the divergence from [sendArchive]). Success does nothing else —
     *     the user stays on the thread; the chip is list-driven: #560's confirmed upsert makes
     *     `observeConversations` re-emit with the new `cwd`, so the workspace chip re-labels itself (AC #1).
     *     The vestigial [de.pyryco.mobile.data.model.Session] return (#560 — change_workspace has no session
     *     transition) is discarded.
     *
     * Always passes the VM's own [conversationId] (never re-derived); [path] is the user's own picker
     * selection, forwarded verbatim as a wire field to #560's already-secured `changeWorkspace` (the phone
     * never touches the filesystem with it — `$HOME` confinement is the daemon's job). The
     * [CancellationException] rethrow **MUST precede** the typed catches (`j.u.c.CancellationException`
     * extends [IllegalStateException] on the JVM) so screen-exit teardown mid-call propagates cleanly and is
     * never mis-surfaced as a change-workspace failure (AC #4).
     */
    private fun sendChangeWorkspace(path: String) {
        viewModelScope.launch {
            try {
                repository.changeWorkspace(conversationId, path)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                changeWorkspaceErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                changeWorkspaceErrorChannel.trySend(Unit)
            }
        }
    }

    /** One-way control, bound to this destination's owner; failures use the queue-drop treatment. */
    fun onSendQueuedNow(queuedMessageId: Long) {
        if (!state.value.runConfig.midTurnInputSupported) return
        viewModelScope.launch {
            try {
                repository.sendQueuedNow(conversationId, queuedMessageId)
                RelayLog.d { "event=send_queued_now_sent" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayErrorException) {
                RelayLog.d { "event=send_queued_now_failed code=relay_error" }
            } catch (e: IllegalStateException) {
                RelayLog.d { "event=send_queued_now_failed code=not_connected" }
            }
        }
    }

    /**
     * Drop queued message [queuedMessageId] from this conversation's backlog (#467) — fire the #466
     * `dequeue_message` send through the facade. Reachable as a [ConversationRepository] interface method
     * on the already-injected [repository], so — unlike interrupt/answerModal/cancelModal — there is no new
     * constructor lambda. Always passes the VM's own [conversationId] (never a caller-supplied id; the
     * screen forwards only the row's [Long] queued-message id).
     *
     * **No optimistic removal** (AC #3): the row leaves only on the next `queue_state` ([observeQueue]),
     * so this holds no state to roll back and a failed drop is inert (AC #4). The daemon never replies to
     * the dequeue (#859), so the call returns once the frame is sent; the repository removes the drop's
     * own echo from the thread when that snapshot arrives. The remote throws only when not connected;
     * the [RelayErrorException] catch covers the interface contract's other implementations.
     *
     * The catch contract mirrors [sendInterrupt] exactly: the [CancellationException] rethrow **MUST
     * precede** the typed catches (`j.u.c.CancellationException` extends `IllegalStateException` on the
     * JVM) so structured cancellation is never swallowed; the server-error and not-connected throws are
     * swallowed with no error surface.
     */
    fun onDropQueued(queuedMessageId: Long) {
        viewModelScope.launch {
            try {
                repository.dropQueuedMessage(conversationId, queuedMessageId)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                // Inert: server error (stale / already-drained id) swallowed (AC #4).
            } catch (e: IllegalStateException) {
                // Inert: not-connected / pre-Open send fails silently (AC #4).
            }
        }
    }

    /**
     * Apply a Status-sheet model change (#544, re-sourced by #807): mark the tap pending, send only the
     * changed field to the session the settings reading names, and clear the pending on failure.
     *
     * [value] is a published [ThreadModelChoice.value] — the daemon's own argument, forwarded verbatim
     * and never parsed. Three guards, each a real case rather than an optimisation: a radio `onClick`
     * fires even for the already-selected option; a second tap while a write is outstanding would put two
     * writes for one control in flight; and an empty session id means the daemon has no session to
     * address, which is read-only rather than a failure to surface.
     *
     * The pending is cleared by an arriving reading, not by the ack — see [sessionSettings]. The failure
     * path clears it, which is what restores the last confirmed reading.
     */
    fun onModelSelected(value: String) {
        if (!connectedFor("model")) return
        val config = state.value.runConfig
        if (config.pending || value == config.selectedModel) return
        if (!skipUnlessWritable(config)) return
        // #1360: a model the user picks replaces the way back to the refused one.
        clearRefusalOffer("menu")
        pendingModel.value = value
        sendSessionSettings(config.sessionId, model = value) { pendingModel.value = null }
    }

    /**
     * Switch back to the model claude refused on (#1360, desktop's `switchBack`): one write of the offer's
     * original model, verbatim, to the session the settings reading names. Unlike [onModelSelected] there is no
     * equal-value guard: after a session-scoped fallback the reading can still name the original model while
     * claude runs the fallback, so the write must go out anyway.
     *
     * Dropped while the host is not connected, without an offer, while any model write is pending, or without
     * a session to address. An acknowledged write ends the offer; a refused or failed one keeps it, marked
     * failed until the next tap. Both act only on the offer the write was sent for. Logs static codes only:
     * the model is claude's text.
     */
    fun onSwitchBack() {
        if (!connectedFor("switch_back")) return
        val offer = refusalOffer.value ?: return
        if (pendingModel.value != null) {
            RelayLog.d { "event=refusal_switch_back outcome=skipped reason=pending" }
            return
        }
        val config = state.value.runConfig
        if (!skipUnlessWritable(config)) return
        refusalOffer.value = offer.copy(failed = false)
        pendingModel.value = offer.originalModel
        RelayLog.d { "event=refusal_switch_back outcome=sent" }
        sendSessionSettings(
            config.sessionId,
            model = offer.originalModel,
            reportFailure = false,
            onAcked = {
                RelayLog.d { "event=refusal_switch_back outcome=acked" }
                refusalOffer.update { if (it?.occurredAt == offer.occurredAt) null else it }
            },
        ) {
            RelayLog.d { "event=refusal_switch_back outcome=failed" }
            pendingModel.value = null
            refusalOffer.update { if (it?.occurredAt == offer.occurredAt) it.copy(failed = true) else it }
        }
    }

    /**
     * Fold one live refusal event into the offer (#1360), after desktop's `reduceRefusalOffer`. Only a fallback
     * refusal whose `scope` is exactly `session` and that names both models arms; any other fallback refusal
     * clears; a no-fallback refusal changes nothing; a session transition clears. `scope` is claude's open
     * string, compared and never shown: a value this client does not know can only withhold the offer.
     */
    private fun onLiveRefusalEvent(event: LiveRefusalEvent) {
        when (event) {
            is LiveRefusalEvent.Refused -> {
                val refusal = event.refusal
                val fallbackModel = refusal.fallbackModel ?: return
                if (event.scope == SESSION_SCOPE && refusal.originalModel.isNotEmpty() && fallbackModel.isNotEmpty()) {
                    refusalOffer.value = RefusalOffer(refusal.originalModel, fallbackModel, refusal.occurredAt)
                    RelayLog.d { "event=refusal_offer outcome=armed" }
                    // The refusal means a session is running, but a reading taken before it spawned names none.
                    repository.refreshSessionSettings(conversationId)
                } else {
                    clearRefusalOffer("unqualified")
                }
            }
            LiveRefusalEvent.SessionReplaced -> clearRefusalOffer("session")
        }
    }

    private fun clearRefusalOffer(reason: String) {
        if (refusalOffer.value == null) return
        refusalOffer.value = null
        RelayLog.d { "event=refusal_offer outcome=cleared reason=$reason" }
    }

    /** The [onModelSelected] twin for effort (#807). [level] is a published [ThreadEffortChoice.value] of
     *  the selected row, forwarded verbatim — never `Effort.name.lowercase()`, whose five entries are this
     *  device's guess at a vocabulary the row itself publishes. */
    fun onEffortSelected(level: String) {
        if (!connectedFor("effort")) return
        effortRecall.cancel()
        val config = state.value.runConfig
        if (config.pending || level == config.selectedEffort) return
        if (!skipUnlessWritable(config)) return
        pendingEffort.value = level
        sendSessionSettings(config.sessionId, effort = level) { pendingEffort.value = null }
    }

    /** The recall write (#686): a tap's write path with the remembered level, reverted the same way. */
    private fun startEffortRecall(
        sessionId: String,
        level: String,
    ): Job {
        pendingEffort.value = level
        return sendSessionSettings(sessionId, effort = level) { pendingEffort.value = null }
    }

    /**
     * Apply a footer permission-mode choice (#650). [value] must be one of [PermissionModeOption]'s wire
     * values; anything else is dropped, so no daemon- or screen-supplied string becomes a posture write.
     *
     * Nothing is sent for the confirmed mode, without a confirmed mode (the button is hidden then), while
     * a permission write is outstanding, for a mode the footer does not offer ([offersPermission]), or
     * without a session to address. Unlike model and effort there is no optimistic value: the label stays
     * on the confirmed reading and [ThreadRunConfig.pendingPermission] only marks it pending.
     */
    fun onPermissionModeSelected(value: String) {
        if (!connectedFor("permission")) return
        val mode = PermissionModeOption.fromWire(value) ?: return
        val config = state.value.runConfig
        if (config.permissionMode.isEmpty() || value == config.permissionMode) return
        if (permissionWrite != null) return
        if (!config.offersPermission(mode)) return
        if (!skipUnlessWritable(config)) return
        sendPermissionMode(config.sessionId, mode)
    }

    /**
     * Send one permission write and settle it (#650). Bypass approvals goes out as `yolo = true` and every
     * other mode as `permissionMode`, never both.
     *
     * The ack confirms the request, not claude's mode, so it runs desktop #1544's settle rule: re-read at
     * once, then every [PERMISSION_SETTLE_INTERVAL_MS] for at most [PERMISSION_SETTLE_WINDOW_MS], and stop
     * when a reading reports [mode]. Each read waits for the previous reply, so reads never overlap. A
     * refusal or a send failure clears the pending mark, surfaces the existing [sessionSettingsErrors]
     * signal and asks for one re-read.
     *
     * The job is started lazily so [permissionWrite] is recorded before its body runs. It is cancelled by
     * [cancelPermissionWrite] when the context changes; the canceller clears the pending mark, so a
     * cancelled job never touches state again. The [CancellationException] rethrow precedes the typed
     * catches, as in [sendSessionSettings].
     */
    private fun sendPermissionMode(
        sessionId: String,
        mode: PermissionModeOption,
    ) {
        pendingPermission.value = mode.wire
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (mode == PermissionModeOption.Bypass) {
                        repository.setSessionSettings(sessionId, yolo = true)
                    } else {
                        repository.setSessionSettings(sessionId, permissionMode = mode.wire)
                    }
                } catch (e: CancellationException) {
                    throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
                } catch (e: RelayErrorException) {
                    failPermissionWrite(outcome = "refused")
                    return@launch
                } catch (e: IllegalStateException) {
                    failPermissionWrite(outcome = "failed")
                    return@launch
                }
                RelayLog.d { "event=permission_write outcome=acked" }
                settlePermission(mode.wire)
                finishPermissionWrite()
            }
        permissionWrite = PermissionWrite(sessionId, job)
        job.start()
    }

    /** The settle loop of [sendPermissionMode]. Logs only a static outcome and the read count. */
    private suspend fun settlePermission(requested: String) {
        var reads = 0
        val confirmed =
            withTimeoutOrNull(PERMISSION_SETTLE_WINDOW_MS) {
                var seen: Boolean
                do {
                    val asked = settingsReadings.value.seq
                    repository.refreshSessionSettings(conversationId)
                    val reading = settingsReadings.first { it.seq > asked }.settings
                    reads++
                    seen = reading?.permissionMode == requested
                    if (!seen) delay(PERMISSION_SETTLE_INTERVAL_MS)
                } while (!seen)
                true
            } ?: false
        RelayLog.d { "event=permission_settle outcome=${if (confirmed) "confirmed" else "expired"} reads=$reads" }
    }

    private fun finishPermissionWrite() {
        permissionWrite = null
        pendingPermission.value = null
    }

    private fun failPermissionWrite(outcome: String) {
        RelayLog.d { "event=permission_write outcome=$outcome" }
        finishPermissionWrite()
        sessionSettingsErrorChannel.trySend(Unit)
        repository.refreshSessionSettings(conversationId)
    }

    private fun cancelPermissionWrite() {
        permissionWrite?.job?.cancel()
        finishPermissionWrite()
    }

    /**
     * The read-only gate (#807): `true` when a write can be addressed. A `""` session id means the daemon
     * reported no session to address, and writing with one is refused server-side — so the tap is dropped
     * rather than failed. Logged content-free (static codes only, the [RelayLog] posture this file's
     * `event=history_ask_failed` already uses) because a silently dropped user action is otherwise
     * undiagnosable; no session id, model value or effort level is ever a log field.
     */
    private fun skipUnlessWritable(config: ThreadRunConfig): Boolean {
        if (config.writable) return true
        RelayLog.d { "event=run_config_write_skipped reason=no_session" }
        return false
    }

    /**
     * The shared outbound path for the model and effort controls (#544): send only the changed field(s)
     * to [ThreadUiState.currentSessionId] and, on failure, run [revert] to restore the control and surface a
     * one-shot [sessionSettingsErrors] signal when [reportFailure] is true. Switch-back reports inline
     * through its revert callback instead. The catch triad clones [sendChangeWorkspace] (set_session_settings
     * is request/reply, so a server `error` reply is reachable) with the two failure catches gaining the
     * [revert] call:
     *
     *  - success is **passive** — the optimistic value already displayed stays (the daemon's ack does not
     *    echo the settings, so "confirmed" means "the value that was sent and acked").
     *  - [RelayErrorException] (`session.not_found` / `protocol.malformed` / `server.binary_offline`; all
     *    map here — #543 confirmed no IAE path) → [revert] + signal. The caught `message` is **never** read.
     *  - [IllegalStateException] (facade `live` getter throws when not connected, or the pump-not-Open
     *    `check`) → [revert] + signal.
     *
     * The session id is snapshotted at entry (the same idiom as [conversationId] routing elsewhere). The
     * [CancellationException] rethrow **MUST precede** the typed catches (`j.u.c.CancellationException`
     * extends [IllegalStateException] on the JVM) so screen-exit teardown mid-send neither reverts nor
     * signals — the VM is dying and the optimistic value dies with it (a fresh VM re-seeds `override = null`).
     * The [IllegalArgumentException] / decode exceptions are **not** caught — a malformed ack is a fail-loud
     * protocol violation (parity with [sendArchive] / [sendChangeWorkspace]).
     */
    private fun sendSessionSettings(
        sessionId: String,
        model: String? = null,
        effort: String? = null,
        onAcked: () -> Unit = {},
        reportFailure: Boolean = true,
        revert: () -> Unit,
    ): Job =
        viewModelScope.launch {
            try {
                repository.setSessionSettings(sessionId, model, effort)
                if (model != null) rememberModel(model)
                // #686: an acknowledged effort write is the only thing that sets the remembered level.
                if (effort != null) effortRecall.remember(effort)
                // #807: the ack echoes only the input session id and confirms no value, so a settled write
                // asks for a fresh reading rather than promoting the optimistic one. The pending survives
                // until that reading lands (see [sessionSettings]); only the failure paths below clear it.
                repository.refreshSessionSettings(conversationId)
                // #1360: the switch-back's ack ends its offer.
                onAcked()
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                revert()
                if (reportFailure) sessionSettingsErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                revert()
                if (reportFailure) sessionSettingsErrorChannel.trySend(Unit)
            }
        }

    /** Closes the Channel info sheet and drops its System prompt editor; a write already sent still lands. */
    private fun closeChannelInfo() {
        // #1344: by any path — dismiss, Archive or a confirmed Delete. Only the call that actually closes the
        // sheet releases the MCP reconnect and toggle waits it may have started, so a daemon that never answers
        // cannot leave the section's controls disabled after the sheet reopens. Every caller is on the main
        // thread via [onOverflowEvent], so the read and the write below cannot interleave with another close.
        val wasOpen = pendingChannelInfo.value
        pendingChannelInfo.value = false
        promptEditor.value = null
        if (!wasOpen) return
        repository.endMcpReconnectWait(conversationId)
        repository.endMcpToggleWait(conversationId)
        RelayLog.d { "event=mcp_wait_released" }
    }

    /**
     * Opens Edit channel on this conversation with its name and mute flag as the list holds them (#1561).
     * Only a channel qualifies; a discussion's menu offers Rename instead.
     */
    private fun openChannelEditor() {
        viewModelScope.launch {
            val channel = conversations.first().firstOrNull { it.id == conversationId && it.isPromoted }
            if (channel == null) {
                RelayLog.d { "event=channel_editor_open_rejected code=unknown_channel" }
                return@launch
            }
            channelEditor.open(HostConversationTarget(serverId, conversationId), channel.name, channel.muted)
        }
    }

    fun onOverflowEvent(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.BackgroundTaskToggle -> toggleBackgroundTask(event.taskId)
            is ThreadEvent.BackgroundTaskStop -> sendBackgroundTaskStop(event.taskId)
            ThreadEvent.Archive -> {
                // Close the Channel Info Sheet if Archive was tapped from it (a harmless no-op from the
                // overflow menu, where it is already false); the send + success-only PopBack live in
                // sendArchive, off the shared silent guard (#556).
                closeChannelInfo()
                sendArchive()
            }
            ThreadEvent.Delete -> {
                // Close the Channel Info Sheet if Delete was tapped from it (#1651), the same precedent
                // Archive follows above: Figma draws the confirmation over the canvas, with no sheet behind
                // the scrim.
                closeChannelInfo()
                pendingDeleteConfirm.value = true
            }
            ThreadEvent.DeleteConfirm -> {
                pendingDeleteConfirm.value = false
                closeChannelInfo()
                launchGuardedRepoCall {
                    repository.delete(state.value.conversationId)
                    // #790: success-only — each of the
                    // three failure types [launchGuardedRepoCall] swallows skips this line, leaving the
                    // draft for a conversation that still exists. Keyed by the route's own pair, the one
                    // [onDraftChange] wrote under, never re-derived from `state`. Before the `PopBack`
                    // send so the eviction does not depend on it.
                    draftStore.clearConversation(serverId, conversationId)
                    navigationChannel.send(ThreadNavigation.PopBack)
                }
            }
            ThreadEvent.DeleteDismiss -> pendingDeleteConfirm.value = false
            ThreadEvent.Rename -> pendingRenameDialog.value = true
            is ThreadEvent.RenameSubmit -> {
                pendingRenameDialog.value = false
                launchGuardedRepoCall {
                    repository.rename(state.value.conversationId, event.name)
                }
            }
            ThreadEvent.RenameDismiss -> pendingRenameDialog.value = false
            ThreadEvent.EditChannel -> openChannelEditor()
            is ThreadEvent.ChannelEditSubmit -> channelEditor.submit(event.name, event.systemPrompt, event.muted)
            ThreadEvent.ChannelEditArchive -> channelEditor.archive()
            ThreadEvent.ChannelEditDismiss -> channelEditor.dismiss()
            ThreadEvent.SaveAsChannel -> {
                pendingSaveAsChannelDialog.value =
                    SaveAsChannelDialogState(
                        initialName = state.value.conversationName?.takeIf { it.isNotBlank() } ?: DEFAULT_CHANNEL_NAME,
                    )
                RelayLog.d { "event=save_as_channel_opened" }
            }
            is ThreadEvent.SaveAsChannelSubmit -> submitSaveAsChannel(event.name, event.systemPrompt)
            ThreadEvent.SaveAsChannelDismiss -> {
                pendingSaveAsChannelDialog.value = null
                RelayLog.d { "event=save_as_channel_dismissed" }
            }
            // #1309: opening either sheet re-reads the settings it shows. #1344: Channel info also asks for the
            // MCP reading, which starts empty on every connection, unless the session reports it cannot answer.
            // #1342: each open also mounts a fresh System prompt editor, whose construction reads the prompt.
            ThreadEvent.ChannelInfo ->
                if (pendingChannelInfo.compareAndSet(false, true)) {
                    promptEditor.value = SystemPromptEditor(viewModelScope, repository, conversationId)
                    rereadRunSettings("channel_info_open")
                    if (state.value.runConfig.mcpServersSupported) {
                        repository.requestMcpStatus(conversationId)
                        RelayLog.d { "event=mcp_status_requested" }
                    }
                }
            ThreadEvent.ChannelInfoDismiss -> closeChannelInfo()
            is ThreadEvent.SystemPromptEdit -> promptEditor.value?.edit(event.text)
            ThreadEvent.SystemPromptSave -> promptEditor.value?.save()
            ThreadEvent.SystemPromptClear -> promptEditor.value?.clear()
            // #1344: always the route's own conversation; the Claude-authored name only goes on the wire.
            is ThreadEvent.McpReconnect -> {
                repository.reconnectMcpServer(conversationId, event.serverName)
                RelayLog.d { "event=mcp_reconnect_sent" }
            }
            is ThreadEvent.McpToggle -> {
                repository.toggleMcpServer(conversationId, event.serverName, event.enabled)
                RelayLog.d { "event=mcp_toggle_sent enabled=${event.enabled}" }
            }
            ThreadEvent.RunConfigOpen -> rereadRunSettings("run_config_open")
            ThreadEvent.ChangeWorkspace -> pendingWorkspacePicker.value = true
            ThreadEvent.NewSession -> sendNewSession()
        }
    }

    /**
     * Save as channel's OK (#957): promote this conversation **in place** under the trimmed [name] — the
     * `workspace = null` promote keeps its `cwd`, id and history — then, once the promote is confirmed,
     * store a non-blank [systemPrompt] verbatim. A blank prompt writes nothing, so a prompt the chat
     * already stores is kept. The modal closes only when every write it asked for has been confirmed.
     *
     * A failure keeps the modal open with a [SaveAsChannelFailure] flag, never the exception's message.
     * A confirmed promote is recorded as [SaveAsChannelDialogState.promoted], so OK after a failed prompt
     * write retries only that write and never sends a second promote. Every terminal transition is a
     * `compareAndSet` against the state published before it, so a result landing after Cancel cannot
     * resurrect the modal; the writes themselves carry on, since the operator already pressed OK.
     *
     * Logs static event names only — never the name, the prompt, the id or an exception message.
     */
    private fun submitSaveAsChannel(
        name: String,
        systemPrompt: String,
    ) {
        val dialog = pendingSaveAsChannelDialog.value ?: return
        if (dialog.saving) return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !SystemPromptLimit.fits(systemPrompt)) {
            RelayLog.d { "event=save_as_channel_rejected" }
            return
        }
        val pending = dialog.copy(saving = true, failure = null)
        pendingSaveAsChannelDialog.value = pending
        viewModelScope.launch {
            if (!dialog.promoted) {
                RelayLog.d { "event=save_as_channel_promote_started" }
                try {
                    repository.promote(conversationId, trimmed, null)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    RelayLog.d { "event=save_as_channel_promote_failed" }
                    pendingSaveAsChannelDialog.compareAndSet(pending, pending.copy(saving = false, failure = SaveAsChannelFailure.Promote))
                    return@launch
                }
            }
            if (systemPrompt.isBlank()) {
                pendingSaveAsChannelDialog.compareAndSet(pending, null)
                RelayLog.d { "event=save_as_channel_saved" }
                return@launch
            }
            val promoted = pending.copy(promoted = true)
            pendingSaveAsChannelDialog.compareAndSet(pending, promoted)
            try {
                repository.setSystemPrompt(conversationId, systemPrompt)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                RelayLog.d { "event=save_as_channel_prompt_failed" }
                pendingSaveAsChannelDialog.compareAndSet(
                    promoted,
                    promoted.copy(saving = false, failure = SaveAsChannelFailure.SystemPrompt),
                )
                return@launch
            }
            pendingSaveAsChannelDialog.compareAndSet(promoted, null)
            RelayLog.d { "event=save_as_channel_saved" }
        }
    }

    /** A non-default option armed for a second confirm (#451), scoped to the [modalId] it belongs to.
     *  `private` → not an exported type; structural equality drives the second-confirm match. */
    private data class ArmedModalOption(
        val modalId: String,
        val optionId: String,
    )

    private fun ModalUiState.Open.alwaysAllowKey() = PermissionGrantDraft(modalId, alwaysAllowRules)

    /** The outstanding permission write (#650): the session it addressed, and the job that sends and
     *  settles it. */
    private class PermissionWrite(
        val sessionId: String,
        val job: Job,
    )

    /** One delivered settings reading and its arrival number (#650). */
    private data class SettingsReading(
        val seq: Long,
        val settings: SessionSettings?,
    )

    /**
     * The thread's content surface (#461): the rendered rows folded with the queued-message backlog and,
     * since #777, the history walk's oldest-end slot. The slot rides this arm rather than taking a sixth
     * one of its own because Kotlin's typed `combine` stops at five and [state] already uses all five.
     */
    private data class ThreadContent(
        val items: List<ThreadItem>,
        val queued: List<QueuedMessage>,
        val historyTail: ThreadHistoryTail,
        val historyMarkers: List<ThreadHistoryMarker>,
    )

    private data class TransientDialogs(
        val renameVisible: Boolean,
        val saveAsChannel: SaveAsChannelDialogState?,
        val channelInfoOpen: Boolean,
        val deleteConfirmVisible: Boolean,
    )
}

// Phase 4 swap point: replace with a generator that synthesizes a title from
// the first user message in the conversation.

/**
 * How often [ThreadViewModel.usageLimit] re-reads the usage-limit projection (#804), so a displayed reading
 * leaves within this long of its `resets_at`. A fixed cadence: nothing is ever scheduled from `resets_at`.
 */
internal const val USAGE_LIMIT_REREAD_MS = 30_000L

/** An immediate tick, then one every [USAGE_LIMIT_REREAD_MS]; cancelled with its collector. */
private fun usageLimitRereads(): Flow<Unit> =
    flow {
        while (true) {
            emit(Unit)
            delay(USAGE_LIMIT_REREAD_MS)
        }
    }

/** Save as channel's name seed for a conversation that has no name of its own (#957). */
private const val DEFAULT_CHANNEL_NAME = "New channel"

// ---- #544: Model / Effort → set_session_settings wire strings (file-private) ---------------------
//
// #807 deleted `Effort.wire()` / `Model.wire()`, the two enum-to-daemon-string mappers #544 added here.
// Their premise was that the phone knows the server's vocabulary; it does not. Every argument sent now
// comes from `ModelMenuRow.value` / `effortLevels` — the server's own strings, forwarded verbatim.

/**
 * The Actions menu's commands that [menu] proves absent (#884), after desktop's
 * `composerActionAvailability`. Proof needs a menu, a dropped count of exactly 0, no row with a truncated
 * `name` or `aliases`, and no row whose name or alias equals the command without its slash. Anything less
 * proves nothing, and every row stays enabled. [ComposerAction.ResetSession] is never absent.
 *
 * A session whose capability list reports [slashCommands] `false` (#1111) makes every command absent,
 * whatever the menu says. `true` never re-enables a command the menu proves absent.
 *
 * The published strings are workspace-authored. They are only compared here, never returned, rendered,
 * logged or sent.
 */
internal fun absentComposerActions(
    menu: SlashCommandMenu?,
    slashCommands: Boolean = true,
): Set<ComposerAction> {
    if (!slashCommands) return ComposerAction.entries.filter { it.command != null }.toSet()
    if (menu == null || menu.droppedCommands != 0) return emptySet()
    val rows = menu.rows
    if (rows.any { row -> row.truncatedFields.orEmpty().any { it == "name" || it == "aliases" } }) return emptySet()
    return ComposerAction.entries
        .filter { action ->
            val name = action.command?.removePrefix("/") ?: return@filter false
            rows.none { it.name == name || name in it.aliases }
        }.toSet()
}

/**
 * The client's share of the #791 trust boundary: one daemon-authored string reduced to inert display
 * text. `ModelMenuRow`'s contract is explicit that `displayName` / `resolvedModel` / `effortLevels` (and
 * `SessionSettings.model` / `effort` alongside them) are **claude-authored** and that the daemon bounds
 * but does not sanitize them — no control character and no terminal escape sequence is stripped anywhere
 * upstream, so the render boundary that owes it is this one.
 *
 * Two steps, each load-bearing. Dropping every [Char.isISOControl] character removes `ESC` and the whole
 * C0/C1 range — the terminal-escape vector — and with it the newlines and tabs that would break the
 * single-line footer. The [MAX_RUN_CONFIG_LABEL_CHARS] bound is the `MAX_WORKSPACE_LABEL_CHARS` posture
 * this codebase already applies to externally authored labels, against a producer cap that is daemon-side
 * and explicitly not a wire constant.
 *
 * **Never applied to a write argument.** `ThreadModelChoice.value` and `ThreadEffortChoice.value` stay
 * byte-identical to what the daemon published, because they are sent back, not shown. The output of this
 * function reaches `Text` and nothing else — never `MarkdownText`, a WebView, a URL, a filename, a
 * `testTag`, a map key or a log field.
 */
internal fun String.inert(): String = filterNot { it.isISOControl() }.take(MAX_RUN_CONFIG_LABEL_CHARS)

private const val MAX_RUN_CONFIG_LABEL_CHARS = 128

/**
 * One claude-reported value (#891) through the [inert] path, or `null` when nothing printable is left —
 * an all-control-character value is unavailable, not a blank row. [truncated] is the daemon's own flag,
 * widened to also say when the inert bound cut characters, so cut text is never presented as whole.
 */
internal fun reportedText(
    raw: String,
    truncated: Boolean,
): ThreadReportedText? {
    val printable = raw.filterNot { it.isISOControl() }
    if (printable.isEmpty()) return null
    return ThreadReportedText(raw.inert(), truncated || printable.length > MAX_RUN_CONFIG_LABEL_CHARS)
}

/** The `session_facts.truncated_fields` entry that names the build (pyrycode `docs/protocol-mobile.md`). */
private const val CLAUDE_CODE_VERSION_FIELD = "claude_code_version"

/**
 * The most published models this client will lay out. `ModelMenu.rows` is bounded by the producer, but
 * that cap is daemon-side and not a wire constant, and the Status sheet's Model section is a plain
 * `Column` rather than a lazy list — so an oversized menu from a buggy or hostile daemon would compose
 * every row at once. The remainder is reported as [ThreadRunConfig.hiddenChoices], kept apart from the
 * producer's own [ThreadRunConfig.droppedModels] so neither number is mistaken for the other.
 */
private const val MAX_RENDERED_MODEL_CHOICES = 32

/**
 * Folds the two daemon readings and the two pending taps into the surface both render sites read (#807).
 * A `null` reading is *unavailable* and never a device default: the flags say which, and no field is
 * manufactured to fill a gap.
 */
private fun runConfig(
    settings: SessionSettings?,
    menu: ModelMenu?,
    agent: ConversationAgent,
    pendingModel: String?,
    pendingEffort: String?,
    pendingPermission: String?,
): ThreadRunConfig {
    val rows = menu?.rows.orEmpty()
    val visibleRows = rows.filterNot { it.value == INHERITED_DEFAULT_MODEL_VALUE }
    val defaultRow =
        if (agent == ConversationAgent.Claude) {
            rows.filter { it.value == INHERITED_DEFAULT_MODEL_VALUE }.singleOrNull()
        } else {
            null
        }
    return ThreadRunConfig(
        choices = visibleRows.take(MAX_RENDERED_MODEL_CHOICES).map { it.toChoice(agent) },
        overflowChoices = visibleRows.drop(MAX_RENDERED_MODEL_CHOICES).map { it.toChoice(agent) },
        inheritedChoice = defaultRow?.toChoice(agent),
        inheritedResolutionUnique =
            defaultRow != null && visibleRows.count { it.resolvedModel == defaultRow.resolvedModel } == 1,
        menuAvailable = menu != null,
        droppedModels = menu?.droppedModels ?: 0,
        hiddenChoices = (visibleRows.size - MAX_RENDERED_MODEL_CHOICES).coerceAtLeast(0),
        settingsAvailable = settings != null,
        settingsHeld = settings?.held == true,
        savedModel = settings?.model.orEmpty(),
        savedEffort = settings?.effort.orEmpty(),
        pendingModel = pendingModel,
        pendingEffort = pendingEffort,
        sessionId = settings?.sessionId.orEmpty(),
        permissionMode = settings?.permissionMode.orEmpty(),
        pendingPermission = pendingPermission,
        appliedEffort = settings?.effectiveEffort ?: EffectiveEffort.Unavailable,
        capabilities = settings?.capabilities,
        memorySearch = settings?.memorySearch ?: MemorySearchReport.Unknown,
        agent = agent,
    )
}

/**
 * The rows [agent]'s conversation lists (#1110), in the daemon's order. A merged `multi_agent` menu is the
 * same for every conversation and the daemon refuses a model or effort outside the session's own agent, so
 * the other agent's rows, and rows naming an agent this client does not know, are left out. `droppedModels`
 * counts only Claude's cut entries, so a Codex conversation reports none.
 */
private fun ModelMenu.forAgent(agent: ConversationAgent): ModelMenu =
    ModelMenu(
        rows = rows.filter { it.agent == agent },
        droppedModels = if (agent == ConversationAgent.Claude) droppedModels else 0,
    )

/**
 * Hides a permission mode (#650), applied effort (#889), and memory search report left over from a replaced session. A
 * `session_transition` updates the conversation's current session before the settings re-read lands, so
 * until a reading for [liveSessionId] arrives, the reading on hand describes a session that is gone. An
 * empty [liveSessionId] is the v2 summary's placeholder and proves nothing. The saved model and effort are
 * choices rather than readings of the running child, so they stay.
 */
private fun ThreadRunConfig.forLiveSession(liveSessionId: String): ThreadRunConfig =
    if (liveSessionId.isNotEmpty() && liveSessionId != sessionId) {
        copy(
            permissionMode = "",
            appliedEffort = EffectiveEffort.Unavailable,
            memorySearch = MemorySearchReport.Unknown,
            capabilities = capabilities?.copy(midTurnInput = false),
        )
    } else {
        this
    }

/** How long a permission write's settle keeps re-reading after the ack (#650, desktop #1544). */
internal const val PERMISSION_SETTLE_WINDOW_MS = 15_000L

/** The pause between two settle reads once a reading has not yet reported the requested mode. */
internal const val PERMISSION_SETTLE_INTERVAL_MS = 500L

/** One published row, split into the verbatim write argument and inert display text. `resolvedModel`
 *  becomes [ThreadModelChoice.detail], which no screen draws since #1497, only when it says something the
 *  label does not. */
private fun ModelMenuRow.toChoice(agent: ConversationAgent): ThreadModelChoice {
    val label = dropdownLabel(agent)
    return ThreadModelChoice(
        value = value,
        label = label,
        detail = resolvedModel.inert().takeIf { it.isNotBlank() && it != label }.orEmpty(),
        effortChoices = effortLevels.map { ThreadEffortChoice(value = it, label = it.inert()) },
        supportsAutoMode = supportsAutoMode,
        resolvedModel = resolvedModel.takeUnless { "resolved_model" in truncatedFields.orEmpty() }.orEmpty(),
    )
}

/** The desktop dropdown label, shared with the host-backed scenario's dynamic assertion. */
internal fun ModelMenuRow.dropdownLabel(agent: ConversationAgent): String =
    if (agent == ConversationAgent.Claude) value.modelFamily().ifEmpty { displayName.inert() } else displayName.inert()

/** Desktop's dropdown family rule over a raw identifier; used for display and the inherited mark (#1308). */
internal fun String.modelFamily(): String {
    val bare = removePrefix("claude-")
    val head = bare.take(MAX_RUN_CONFIG_LABEL_CHARS).takeWhile { it in 'A'..'Z' || it in 'a'..'z' }
    return head.replaceFirstChar { it.uppercaseChar() }.inert()
}

/**
 * The ViewModel's switch-back offer (#1360): the arming refusal's two models, verbatim, and its row identity.
 * [failed] marks the last write for this offer as refused or failed.
 */
private data class RefusalOffer(
    val originalModel: String,
    val fallbackModel: String,
    val occurredAt: Instant,
    val failed: Boolean = false,
)

/** The one `model_refusal_fallback.scope` that arms a switch-back offer (#1360, desktop's rule). */
private const val SESSION_SCOPE = "session"

/**
 * How full the context window is, as a whole percent in 0..100, or `null` when unavailable (#1411, desktop's
 * `contextTokenSource` + `contextUsagePercent`). A present [usage] always supplies the pair, its `totalTokens`
 * over `maxTokens`, whatever it holds; only an absent one falls back to [settings]' `usedTokens` over
 * `windowTokens`. A window of `0` or less in the pair used is unavailable, never a fallback. Claude's own
 * `percentage` is not read, so the footer and the Status sheet share one clamp. Rounds half up, as `Math.round`.
 */
internal fun contextPercent(
    usage: ContextUsage?,
    settings: SessionSettings?,
): Int? {
    val (used, window) =
        when {
            usage != null -> usage.totalTokens to usage.maxTokens
            settings != null -> settings.usedTokens to settings.windowTokens
            else -> return null
        }
    if (window <= 0) return null
    return floor(used.toDouble() / window.toDouble() * 100 + 0.5).coerceIn(0.0, 100.0).toInt()
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
