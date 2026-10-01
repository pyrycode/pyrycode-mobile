package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.scopedTo
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.conversations.components.attachmentTarget
import de.pyryco.mobile.ui.conversations.components.loadsOnShow
import de.pyryco.mobile.ui.conversations.components.turnOutcomeReport
import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall
import de.pyryco.mobile.ui.workspace.workspaceDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one `history.*` wire code this screen branches on (#778) — the daemon refused the cursor, so the
 * walk restarts from the newest page instead of surfacing a dead end. Every other code, known or not,
 * falls through to a failure, so a hostile daemon cannot reach the restart branch by guessing. The code
 * vocabulary's SSOT is the protocol document, not this constant.
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
    // #406: the coordinator's reconnection-surviving live-event seam, reduced to [isThinking]. Defaulted
    // to an empty flow so the fake-backed graph + existing tests stay inert (the flag holds `false`).
    liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
    // #492: the host coordinator's process-scoped, reconnection-surviving "current modal" projection
    // (#437/#445), folded once at the coordinator layer. It holds the host's single modal whichever
    // conversation raised it; #816 scopes it to this thread as [currentModal]. Defaulted to a fresh
    // MutableStateFlow(Hidden) so the fake-backed Koin graph + non-modal tests stay inert.
    private val hostModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden),
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
    // #678: the coordinator's per-conversation background-task roster and its live count (#677). Read
    // only: nothing here sends. Defaulted to "nothing reported" and 0, which is what a demo host shows.
    backgroundTasks: (conversationId: String) -> Flow<BackgroundTaskRoster?> = { flowOf(null) },
    backgroundTaskCount: (conversationId: String) -> Flow<Int> = { flowOf(0) },
    // #861: whether this thread's host has a live repository published — for a relay host, the
    // coordinator's `currentRepository` being non-null, which happens only after the Noise handshake,
    // later than the socket-level `Connected` [connectionStateSource] reports. Keys the #778 walk
    // restart. Defaulted to always-available, as the demo path's fake repository is.
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
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    /**
     * The host's modal as this thread sees it (#816): shown only when the conversation that raised it is
     * this thread's own, else [ModalUiState.Hidden] (see [scopedTo]). Seeded from the host's current value
     * and collected `Eagerly`, so `.value` is right from construction.
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

    private val attachmentRefusalChannel = Channel<AttachmentRefusal>(capacity = Channel.BUFFERED)

    /**
     * One notice per pick that had entries refused (#933), with how many were refused for each reason. Counts
     * only, never a name, URI or type, so the snackbar it drives shows fixed local text.
     */
    val attachmentRefusals: Flow<AttachmentRefusal> = attachmentRefusalChannel.receiveAsFlow()

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

    private val pendingDeleteConfirm = MutableStateFlow(false)

    private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()

    /** A model tap whose write has not settled, or `null`. A matching fresh settings reading confirms it;
     *  a rejected write or lost settings context clears it. */
    private val pendingModel = MutableStateFlow<String?>(null)

    /** The [pendingModel] twin for effort (#807). */
    private val pendingEffort = MutableStateFlow<String?>(null)

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

    /**
     * The conversation list, shared (#1110) so [state] and [conversationAgent] ride one upstream
     * subscription: the remote repository sends a `list_conversations` request on every subscription.
     */
    private val conversations: Flow<List<Conversation>> =
        repository
            .observeConversations(ConversationFilter.All)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /** The agent that runs this conversation (#1110); Claude while the list does not hold it yet. */
    private val conversationAgent: Flow<ConversationAgent> =
        conversations
            .map { list -> list.firstOrNull { it.id == conversationId }?.agent ?: ConversationAgent.Claude }
            .distinctUntilChanged()

    /**
     * The run-configuration arm of [state] (#807). Five inputs, which is exactly Kotlin's typed `combine`
     * ceiling — the reason this stays one arm of the five-arm `state` combine instead of needing a sixth
     * or the sibling-[StateFlow] shape [draft] uses. [runningModel] joins by a second, two-arm combine, and
     * Claude's reported context usage (#946) by a third; the repository clears that reading itself.
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
            runConfig(settings, menuAndAgent.first, menuAndAgent.second, model, effort, permission)
        }.combine(runningModel) { config, (running, announced) -> config.copy(running = running, announcedModel = announced) }
            .combine(repository.observeContextUsage(conversationId)) { config, usage ->
                config.copy(contextPercent = usage?.percentage)
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
     * nothing here reads, logs or keys on them.
     */
    private val backgroundTaskReading: Flow<Pair<BackgroundTaskRoster?, Int>> =
        combine(
            backgroundTasks(conversationId).onStart { emit(null) },
            backgroundTaskCount(conversationId).onStart { emit(0) },
            ::Pair,
        ).distinctUntilChanged()

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
     * This conversation's backward history walk (#777) — cursor, in-flight, page count, stop reason and
     * walk generation in one value. Written from four places, all CAS-shaped: [claimHistorySlot]'s
     * [MutableStateFlow.compareAndSet] loop for the ask and the retry claims, [restartHistoryWalk]'s own
     * loop, and [applyToWalk]'s generation-guarded [MutableStateFlow.update] for every settle and fail. A
     * plain read-then-assign would open a real window, because the settle runs in a launched coroutine
     * while the claim runs on the caller's.
     *
     * Not persisted — no [SavedStateHandle], no DataStore. The repository's projections are
     * connection-scoped, so a cursor that outlived its connection would be a stale-cursor bug; #778
     * restarts the walk on a new connection instead of resuming it.
     */
    private val historyDemand = MutableStateFlow(ThreadHistoryDemand())

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
            liveSessionEvents.map(ThreadInput::Live),
        ).scan(ThreadFold(emptyList(), null)) { fold, input -> fold.reduce(input, conversationId) }
            .map { it.render() }
            .distinctUntilChanged()

    /**
     * The thread's content surface: the [threadItems] rows folded with the conversation's queued-message
     * backlog (#461). [ConversationRepository.observeQueue] is a thread-content stream (an ordered list of
     * not-yet-sent user text, the same category as [items]) rather than a transient cross-cutting signal,
     * so it is surfaced on [ThreadUiState] — not as a sibling [StateFlow] like [isStalled]. Combined here
     * so the five-arm typed `state` combine keeps one content arm; both inputs seed immediately (the `scan`
     * seeds `emptyList()`, `observeQueue` seeds `emptyList()`) and each already carries
     * `distinctUntilChanged`, so this never stalls and adds no operator.
     */
    private val threadContent: Flow<ThreadContent> =
        combine(threadItems, repository.observeQueue(conversationId), historyDemand) { items, queued, demand ->
            ThreadContent(items, queued, demand.tail())
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
                runConfig = runConfig.forLiveSession(conv?.currentSessionId.orEmpty()),
                mutationsSupported = mutationsSupported,
                historyTail = content.historyTail,
            )
        }.combine(slashCommandMenu) { uiState, menu ->
            val slashCommandsAccepted = uiState.runConfig.capabilities?.slashCommands ?: true
            uiState.copy(absentActions = absentComposerActions(menu, slashCommandsAccepted), slashCommands = menu?.rows)
        }.combine(backgroundTaskReading) { uiState, (roster, count) ->
            uiState.copy(backgroundTasks = roster, backgroundTaskCount = count)
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
     * Whether this conversation's agent is currently in its `thinking` phase (#406) — `true` only while
     * the latest `turn_state` for [conversationId] is [LiveSessionEvent.TurnState.Phase.Thinking],
     * `false` for `responding` / `idle` / `turn_end` or before any event. A sibling [StateFlow] beside
     * [connectionState] (not a [ThreadUiState] field): like the connection signal it is a transient,
     * connection-scoped cross-cutting signal the stateless screen takes as a separate parameter. The
     * reduction emits only on a phase transition, so [stateIn]'s last value is retained for events that
     * leave the flag unchanged; `false` covers both "no event yet" and the inert empty-flow default.
     */
    val isThinking: StateFlow<Boolean> =
        liveSessionEvents
            .mapNotNull { event -> thinkingTransition(event) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Whether this conversation's agent is currently **running a turn** (#459) — `true` while the latest
     * `turn_state` for [conversationId] is [LiveSessionEvent.TurnState.Phase.Thinking] **or**
     * [LiveSessionEvent.TurnState.Phase.Responding], `false` for `idle` / `turn_end` or before any event.
     * The broader sibling of [isThinking] (which is `true` for `thinking` only): the interrupt affordance
     * (#459) must stay visible across the whole in-flight turn, not just the thinking phase. Same posture
     * and lifecycle as [isThinking] — a hoisted [StateFlow] beside [connectionState] the stateless screen
     * takes as a separate parameter, backed by its own [busyTransition] reducer (a dedicated reducer is
     * simpler than combining [isThinking] with a second flow and matches the established sibling pattern).
     * The reduction emits only on a busy/not-busy transition, so [stateIn]'s last value is retained for
     * events that leave the flag unchanged; `false` covers both "no event yet" and the inert empty-flow
     * default.
     */
    val isBusy: StateFlow<Boolean> =
        liveSessionEvents
            .mapNotNull { event -> busyTransition(event) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * How this conversation's last turn ended, when it did not end cleanly (#805) — drives the status
     * area's turn-outcome arm. A sibling [StateFlow] beside [isThinking] / [isBusy] over the same live
     * events, with the same lifetime. `null` covers no event yet, a clean last turn, and a next turn that
     * has started.
     *
     * A `turn_end` replaces the value outright (a clean one clears a stale report), and only `thinking` /
     * `responding` clear it otherwise — never `idle`, which may arrive on either side of the `turn_end` it
     * accompanies. [isThinking] and [isBusy] are untouched: they already turn off on any `turn_end`.
     */
    val turnOutcome: StateFlow<TurnOutcomeReport?> =
        liveSessionEvents
            .runningFold(null as TurnOutcomeReport?) { current, event -> nextTurnOutcome(current, event) }
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

    private val modalSendErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "a modal send (answer or cancel) failed" signal (#451) — the established one-shot VM→UI
     * event idiom this VM already uses for [navigationEvents]. Carries **no** modal payload (just [Unit]),
     * so nothing sensitive can leak through it; the render slice (#452) shows a transient snackbar. Fires
     * exactly once per caught failure ([RelayErrorException] from a server `error`, incl. the
     * ungranted-device reject pyrycode#702; [IllegalStateException] from a not-connected session).
     */
    val modalSendErrors: Flow<Unit> = modalSendErrorChannel.receiveAsFlow()

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
     * One-shot "starting a new session failed" signal (#540) — the [modalSendErrors] one-shot idiom, cloned
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

    /**
     * Folds one live event to the next [isThinking] value, or `null` to leave the flag unchanged. Routes
     * by [conversationId] first (AC #3 — other conversations never move the flag), then maps the turn
     * phase: `thinking` ⇒ `true`; `responding` / `idle` / `turn_end` ⇒ `false`; the non-phase events
     * (`assistant_delta` / `tool_use` / `tool_result`) are not transitions ⇒ `null`.
     */
    private fun thinkingTransition(event: LiveSessionEvent): Boolean? {
        if (event.conversationId != conversationId) return null
        return when (event) {
            is LiveSessionEvent.TurnState -> event.phase == LiveSessionEvent.TurnState.Phase.Thinking
            is LiveSessionEvent.TurnEnd -> false
            is LiveSessionEvent.AssistantDelta,
            is LiveSessionEvent.ToolUse,
            is LiveSessionEvent.ToolResult,
            is LiveSessionEvent.ReplayGap,
            -> null
        }
    }

    /**
     * Folds one live event to the next [isBusy] value, or `null` to leave the flag unchanged. Mirrors
     * [thinkingTransition] exactly; the **only** difference is the phase predicate — a turn is "running"
     * across the `thinking` **and** `responding` phases. Routes by [conversationId] first (other
     * conversations never move the flag), then maps the turn phase: `thinking` / `responding` ⇒ `true`;
     * `idle` / `turn_end` ⇒ `false`; the non-phase events (`assistant_delta` / `tool_use` / `tool_result`
     * / replay-gap) are not transitions ⇒ `null`.
     */
    private fun busyTransition(event: LiveSessionEvent): Boolean? {
        if (event.conversationId != conversationId) return null
        return when (event) {
            is LiveSessionEvent.TurnState ->
                event.phase == LiveSessionEvent.TurnState.Phase.Thinking ||
                    event.phase == LiveSessionEvent.TurnState.Phase.Responding
            is LiveSessionEvent.TurnEnd -> false
            is LiveSessionEvent.AssistantDelta,
            is LiveSessionEvent.ToolUse,
            is LiveSessionEvent.ToolResult,
            is LiveSessionEvent.ReplayGap,
            -> null
        }
    }

    /** Folds one live event into [turnOutcome]; events for other conversations leave it unchanged. */
    private fun nextTurnOutcome(
        current: TurnOutcomeReport?,
        event: LiveSessionEvent,
    ): TurnOutcomeReport? {
        if (event.conversationId != conversationId) return current
        return when (event) {
            is LiveSessionEvent.TurnEnd -> turnOutcomeReport(event)
            is LiveSessionEvent.TurnState ->
                if (event.phase == LiveSessionEvent.TurnState.Phase.Idle) current else null
            is LiveSessionEvent.AssistantDelta,
            is LiveSessionEvent.ToolUse,
            is LiveSessionEvent.ToolResult,
            is LiveSessionEvent.ReplayGap,
            -> current
        }
    }

    init {
        // AC #1 of #777: opening a thread asks for the newest page. A phone that only ever rendered the
        // live stream showed nothing that predated its connection.
        requestOlderHistory()

        // #778: a new connection restarts the walk from the newest page. The repository's projections are
        // connection-scoped, so a cursor minted on one connection is not valid on the next — the walk
        // restarts rather than resumes.
        //
        // #861: keyed on the repository becoming available, not on the socket. A relay host's supervisor
        // reports Connected at socket-open, before the Noise handshake publishes the repository, so a
        // restart keyed there asked a null repository and settled as a permanent dead end.
        //
        // `drop(1)` after `distinctUntilChanged` drops exactly the availability the thread opened on — the
        // flow hands every collector its current value on subscription, so restarting on it would restart
        // the walk this init has just started, spending a page of budget and a round trip on every open.
        // Only a RETURN to available counts. A first value of unavailable correctly makes the repository's
        // arrival a restart: the opening ask on the absent repository already failed.
        viewModelScope.launch {
            repositoryAvailable
                .distinctUntilChanged()
                .drop(1)
                .collect { available ->
                    if (available) {
                        RelayLog.d { "event=history_walk_restart reason=reconnect" }
                        restartHistoryWalk(fromWalk = historyDemand.value.walk)
                    }
                }
        }

        // #1309: a conversation whose claude had not run yet reads no permission mode and no applied effort,
        // so the open thread asks again when any turn on its host ends and when a reset ends. Each new
        // connection starts a fresh running set. A bump while nothing collects [sessionSettings] sends nothing.
        viewModelScope.launch { runSettingsRereadEdges(liveSessionEvents).collect(::rereadRunSettings) }
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
     * The reader has reached the oldest loaded row (#777) — ask for the next page back. Safe to call as
     * often as the list's scroll predicate fires: [ThreadHistoryDemand.canAsk] drops an ask that arrives
     * while a request is outstanding or after the walk has stopped, and drops it rather than queuing it.
     */
    fun onDemandOlderHistory() {
        requestOlderHistory()
    }

    /**
     * The reader pressed the oldest-end retry affordance (#778) — ask again for the page that failed.
     *
     * Gated on [ThreadHistoryDemand.canRetry], so it is inert unless the walk actually stopped on a
     * retryable failure. The retry resumes from the **same** cursor, keeping every loaded row and the
     * walk's position across both the failure and the retry.
     */
    fun onRetryOlderHistory() {
        val claimed = claimHistorySlot { if (it.canRetry) it.retrying() else null } ?: return
        launchHistoryAsk(claimed)
    }

    /** Issue one backward step of the walk, if the demand allows one (#777). */
    private fun requestOlderHistory() {
        val claimed = claimHistorySlot { if (it.canAsk) it.asking() else null } ?: return
        launchHistoryAsk(claimed)
    }

    /**
     * The walk's single ask site.
     *
     * The returned [de.pyryco.mobile.data.repository.HistoryPage] is read for its `cursor` and `atStart`
     * and **nothing else**: `RemoteConversationRepository.requestHistory` has already merged the page's
     * entries into the thread this VM reads through `observeMessages`, so folding them here as well
     * would render every loaded row twice. Nothing needs a second fold.
     *
     * Every write back is guarded on [claimed]'s [ThreadHistoryDemand.walk] (#778), so an ask superseded
     * by a restart writes nothing at all. On reconnect that case is real, not theoretical: the ask issued
     * on the connection that just died is still in flight, and its late settle would otherwise store that
     * dead connection's cursor as the live walk's.
     */
    private fun launchHistoryAsk(claimed: ThreadHistoryDemand) {
        val walk = claimed.walk
        viewModelScope.launch {
            try {
                val page = repository.requestHistory(conversationId, claimed.cursor)
                applyToWalk(walk) { it.settled(pageCursor = page.cursor, atStart = page.atStart) }
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                // A server error frame. Only the CODE is read, and only to choose a branch; e.message is
                // server-supplied and is never read, logged or surfaced. An unknown or differently-cased
                // code falls through to the failure branch, so the fallback here is the safe one.
                if (e.code == HISTORY_INVALID_CURSOR && claimed.cursor.isNotEmpty()) {
                    // AC #3: the daemon refused the cursor, so walk the log again from the newest page
                    // rather than surfacing a dead end. Bounded because restartHistoryWalk carries the
                    // page budget.
                    RelayLog.d { "event=history_walk_restart reason=invalid_cursor" }
                    restartHistoryWalk(fromWalk = walk)
                } else {
                    // A refusal of the NEWEST-page ask is permanent, not a restart: restarting would
                    // re-send the same empty cursor for the same refusal, at round-trip speed with no
                    // user input. There is nothing to restart to when the walk is already at the newest
                    // page, and this is what keeps the restart cycle structurally impossible rather than
                    // merely capped.
                    failWalk(walk, retryable = e.retryable)
                }
            } catch (e: IllegalStateException) {
                // A not-connected session, #488's teardown sweep, or the not-wired interface default.
                // Not retryable: a button with no connection behind it cannot work, and the reconnect
                // restart above is what actually recovers this case.
                failWalk(walk, retryable = false)
            } catch (e: IllegalArgumentException) {
                // An unknown conversation id, or a malformed page — kotlinx.serialization's
                // SerializationException is an IllegalArgumentException, so the decode failure lands here.
                failWalk(walk, retryable = false)
            }
        }
    }

    /** Settle a failed ask, if it still belongs to the current walk. [retryable] is a flag, never text. */
    private fun failWalk(
        walk: Int,
        retryable: Boolean,
    ) {
        RelayLog.d { "event=history_ask_failed retryable=$retryable" }
        applyToWalk(walk) { it.failed(retryable = retryable) }
    }

    /** Apply [transform] only while the walk is still the one the ask was issued on (#778). */
    private fun applyToWalk(
        walk: Int,
        transform: (ThreadHistoryDemand) -> ThreadHistoryDemand,
    ) {
        historyDemand.update { if (it.walk == walk) transform(it) else it }
    }

    /**
     * Restart the walk from the newest page on the **same** page budget (#778), and ask for that page
     * unless the budget is already spent.
     *
     * Returns without a write when the generation has already moved — two restarts can genuinely race
     * (a refused cursor and a reconnect), and each taking a distinct generation means at most one settle
     * applies: two round trips for one page of budget, which spends the bound faster rather than
     * laundering it.
     */
    private fun restartHistoryWalk(fromWalk: Int) {
        while (true) {
            val current = historyDemand.value
            if (current.walk != fromWalk) return
            val restarted = current.restarted()
            if (historyDemand.compareAndSet(current, restarted)) {
                if (restarted.inFlight) launchHistoryAsk(restarted)
                return
            }
        }
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
        draftStore.setDraft(serverId, conversationId, text)
    }

    /**
     * Send [text], then clear this chat's draft — **only** once the daemon has accepted it (#789).
     *
     * The clear sits *inside* the guarded lambda, which is the whole mechanism:
     * [launchGuardedRepoCall]'s catches wrap the block, so a [de.pyryco.mobile.data.network
     * .RelayErrorException] server error, a not-connected [IllegalStateException] or an unwired
     * [UnsupportedOperationException] skips this line and leaves the text in the composer, ready to
     * resend. Before this, the composer cleared on tap and the guard swallowed the failure, so a
     * refused send silently ate the message. Same success-only-continuation shape as [sendArchive].
     *
     * The equality guard keeps an in-flight send from eating text typed while it was in flight. It
     * compares against [ComposerDraftStore] directly and **not** against [draft]: the exposed flow is
     * derived, so its value lags an edit made from inside an already-running coroutine until that
     * dispatch yields, and the guard would then clear text it had never seen. The store's own value is
     * the authority and is read synchronously. [draft] is for rendering; this is for deciding.
     *
     * It is a check-then-act after a suspension point and is safe because both sides and
     * [onDraftChange] run on `viewModelScope`'s `Dispatchers.Main.immediate`, so no edit can interleave
     * between them.
     */
    fun sendMessage(text: String) {
        if (!connectedFor("send")) return
        if (_attachmentsSending.value) return
        // #1328: text is required even with files pending, as on desktop; blank leaves them for the next send.
        if (text.isBlank()) return
        val attachments = draftStore.attachmentsFor(serverId, conversationId)
        if (attachments.isNotEmpty()) return sendWithAttachments(text, attachments)
        launchGuardedRepoCall {
            // #686: a message sent while this opening's recall write is outstanding follows it.
            effortRecall.awaitWrite()
            repository.sendMessage(state.value.conversationId, text)
            if (draftStore.draftFor(serverId, conversationId) == text) onDraftChange("")
        }
    }

    /**
     * Send [text] naming [attachments], this chat's pending entries as they stood when send was tapped
     * (#932). [text] is never blank: [sendMessage] refuses that before reading the attachments (#1328).
     *
     * Each entry without an acknowledged id is read and uploaded in order, and its id recorded in
     * [draftStore] as soon as the daemon acknowledges it, so a later failure never costs a retry that
     * upload. One file's bytes are live at a time. The first failed read or upload stops the send before
     * anything else is uploaded or sent; a thrown upload or send is swallowed by [launchGuardedRepoCall].
     * Either way the text and every entry stay in the draft — the way a failed text send is reported.
     *
     * On success the text clears under [sendMessage]'s in-flight guard, and only the snapshot's entries
     * are removed, so an attachment added while this send was in flight survives it.
     */
    private fun sendWithAttachments(
        text: String,
        attachments: List<PendingAttachment>,
    ) {
        _attachmentsSending.value = true
        launchGuardedRepoCall {
            try {
                val target = state.value.conversationId
                val references = mutableListOf<MessageAttachment>()
                val originals = mutableMapOf<String, String>()
                for (entry in attachments) {
                    val id = entry.attachmentId ?: upload(target, entry) ?: return@launchGuardedRepoCall
                    // #983: the thread row names each file as it was uploaded.
                    references += MessageAttachment(id, entry.displayName, entry.mimeType)
                    originals[id] = entry.uri
                }
                // #984: before the send, because the confirmed row can be drawn while it is suspended. A
                // send that then fails leaves harmless entries: its retry names the same ids.
                draftStore.recordSentOriginals(serverId, conversationId, originals)
                // #686: a message sent while this opening's recall write is outstanding follows it.
                effortRecall.awaitWrite()
                repository.sendMessage(target, text, references)
                if (draftStore.draftFor(serverId, conversationId) == text) onDraftChange("")
                draftStore.removeAttachments(serverId, conversationId, attachments.mapTo(HashSet()) { it.key })
            } finally {
                // #933: however the send ended — sent, stopped by a failed read or upload, or a swallowed throw.
                _attachmentsSending.value = false
            }
        }
    }

    /** Read and upload one pending entry (#932): its acknowledged id, or `null` after logging why not. */
    private suspend fun upload(
        target: String,
        entry: PendingAttachment,
    ): String? {
        val bytes =
            when (val read = attachmentReader.read(entry.uri)) {
                is AttachmentRead.Bytes -> read.bytes
                AttachmentRead.TooLarge -> return attachmentSendFailed("read_too_large")
                AttachmentRead.Unreadable -> return attachmentSendFailed("read_failed")
            }
        val result = repository.uploadAttachment(target, bytes, entry.displayName, entry.mimeType)
        if (result !is AttachmentUploadResult.Stored) return attachmentSendFailed("upload_failed")
        draftStore.markUploaded(serverId, conversationId, entry.key, result.attachmentId)
        return result.attachmentId
    }

    private fun attachmentSendFailed(outcome: String): String? {
        RelayLog.d { "event=composer_attachment_send outcome=$outcome" }
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
    ): AttachmentAddOutcome {
        val outcome = draftStore.addAttachment(serverId, conversationId, uri, displayName, mimeType, size)
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
            when (addAttachment(entry.uri, entry.displayName, entry.mimeType, entry.size)) {
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
     * the same guarded send [sendMessage] runs, so a failed send is handled exactly as a composer message's.
     * It leaves the typed draft alone, so there is no clear on success. A command the published menu proves
     * absent is refused here too, behind the greyed-out row. Reset session carries no command and never
     * comes this way. Logs static codes only.
     */
    fun onComposerCommand(action: ComposerAction) {
        if (!connectedFor("composer_action")) return
        val command = action.command ?: return
        if (action in state.value.absentActions) {
            RelayLog.d { "event=composer_action action=${action.value} outcome=absent" }
            return
        }
        launchGuardedRepoCall {
            effortRecall.awaitWrite()
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
     * Send a `modal_answer` for [optionId] of modal [modalId] via the injected outbound path. Clears the
     * arm **before** launching — the second-confirm gesture is consumed on the attempt (success or
     * failure); [currentModal] stays [ModalUiState.Open] until the daemon resolves it, so the user may
     * answer again after a failure (no auto-retry — first-answer-wins is server-side). Catches **only** the
     * two documented throws so [kotlinx.coroutines.CancellationException] still propagates; on failure it
     * surfaces a one-shot [modalSendErrors] event and nothing else (no log, no [currentModal] mutation).
     */
    private fun sendAnswer(
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean,
    ) {
        armedModalOption.value = null
        viewModelScope.launch {
            try {
                answerModal(modalId, optionId, alwaysAllow)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                modalSendErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                modalSendErrorChannel.trySend(Unit)
            }
        }
    }

    /** The [sendAnswer] mirror for `modal_cancel` (no option id, no idempotency token). Same never-log,
     *  catch-only-the-two-documented-throws, one-shot-error posture. */
    private fun sendCancel(modalId: String) {
        viewModelScope.launch {
            try {
                cancelModal(modalId)
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                modalSendErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                modalSendErrorChannel.trySend(Unit)
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
                navigationChannel.send(ThreadNavigation.PopBack)
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
        pendingModel.value = value
        sendSessionSettings(config.sessionId, model = value) { pendingModel.value = null }
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
     * one-shot [sessionSettingsErrors] signal. The catch triad clones [sendChangeWorkspace] (set_session_settings
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
            } catch (e: CancellationException) {
                throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
            } catch (e: RelayErrorException) {
                revert()
                sessionSettingsErrorChannel.trySend(Unit)
            } catch (e: IllegalStateException) {
                revert()
                sessionSettingsErrorChannel.trySend(Unit)
            }
        }

    fun onOverflowEvent(event: ThreadEvent) {
        when (event) {
            ThreadEvent.Archive -> {
                // Close the Channel Info Sheet if Archive was tapped from it (a harmless no-op from the
                // overflow menu, where it is already false); the send + success-only PopBack live in
                // sendArchive, off the shared silent guard (#556).
                pendingChannelInfo.value = false
                sendArchive()
            }
            ThreadEvent.Delete -> pendingDeleteConfirm.value = true
            ThreadEvent.DeleteConfirm -> {
                pendingDeleteConfirm.value = false
                pendingChannelInfo.value = false
                launchGuardedRepoCall {
                    repository.delete(state.value.conversationId)
                    // #790: success-only, the position [sendMessage]'s own clear occupies — each of the
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
            // #1309: opening either sheet re-reads the settings it shows; closing sends nothing.
            ThreadEvent.ChannelInfo -> if (pendingChannelInfo.compareAndSet(false, true)) rereadRunSettings("channel_info_open")
            ThreadEvent.ChannelInfoDismiss -> pendingChannelInfo.value = false
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
        copy(permissionMode = "", appliedEffort = EffectiveEffort.Unavailable, memorySearch = MemorySearchReport.Unknown)
    } else {
        this
    }

/** How long a permission write's settle keeps re-reading after the ack (#650, desktop #1544). */
internal const val PERMISSION_SETTLE_WINDOW_MS = 15_000L

/** The pause between two settle reads once a reading has not yet reported the requested mode. */
internal const val PERMISSION_SETTLE_INTERVAL_MS = 500L

/** One published row, split into the verbatim write argument and the inert render of it. `resolvedModel`
 *  becomes [ThreadModelChoice.detail] only when it says something the label does not. */
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

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
