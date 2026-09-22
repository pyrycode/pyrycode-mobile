package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall
import de.pyryco.mobile.ui.workspace.workspaceDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * The one `history.*` wire code this screen branches on (#778) — the daemon refused the cursor, so the
 * walk restarts from the newest page instead of surfacing a dead end. Every other code, known or not,
 * falls through to a failure, so a hostile daemon cannot reach the restart branch by guessing. The code
 * vocabulary's SSOT is the protocol document, not this constant.
 */
private const val HISTORY_INVALID_CURSOR = "history.invalid_cursor"

sealed interface ThreadEvent {
    data object NewSession : ThreadEvent

    data object Rename : ThreadEvent

    data class RenameSubmit(
        val name: String,
    ) : ThreadEvent

    data object RenameDismiss : ThreadEvent

    data object ChangeWorkspace : ThreadEvent

    data object Archive : ThreadEvent

    data object Delete : ThreadEvent

    data object DeleteConfirm : ThreadEvent

    data object DeleteDismiss : ThreadEvent

    data object ChannelInfo : ThreadEvent

    data object ChannelInfoDismiss : ThreadEvent

    data object SaveAsChannel : ThreadEvent

    data class SaveAsChannelSubmit(
        val name: String,
        val workspace: WorkspaceChoice,
    ) : ThreadEvent

    data object SaveAsChannelDismiss : ThreadEvent
}

enum class WorkspaceChoice { DEDICATED, SCRATCH }

sealed interface ThreadNavigation {
    data object PopBack : ThreadNavigation
}

data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val showRenameDialog: Boolean = false,
    val saveAsChannelDialog: SaveAsChannelDialogState? = null,
    val items: List<ThreadItem> = emptyList(),
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val channelInfoOpen: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    val workspacePath: String = "",
    val lastUsedAt: Instant? = null,
    val sessionCount: Int = 0,
    // #807: the thread's whole run-configuration surface — what the daemon has configured, what it
    // published as selectable, and what a tap has asked for but not yet had confirmed. Replaces #544's
    // `selectedModel` / `selectedEffort` device-enum pair AND its `currentSessionId` routing field: the
    // session a write must address is [SessionSettings.sessionId], not Conversation.currentSessionId, and
    // one carrier for all three keeps the footer and the Status sheet agreeing by construction.
    val runConfig: ThreadRunConfig = ThreadRunConfig(),
    val yoloEnabled: Boolean = false,
    val mutationsSupported: Boolean = true,
    // #777/#778: what the thread's single oldest-end slot shows — loading, a retry, a dead end or
    // nothing. The walk's TERMINATION reasons deliberately do not reach the screen, only its failures:
    // the screen asks, the VM decides whether the ask is honoured, and a second copy of that decision in
    // Compose would be a second place to get it wrong.
    val historyTail: ThreadHistoryTail = ThreadHistoryTail.None,
)

data class SaveAsChannelDialogState(
    val initialName: String,
)

/**
 * One selectable model (#807) — a [de.pyryco.mobile.data.repository.ModelMenuRow] reduced to what the
 * Status sheet renders plus the argument a write sends back.
 *
 * **[value] is the only field that stays verbatim, and the only one that is never rendered.** It is the
 * argument [ConversationRepository.setSessionSettings] takes; it is an alias (`sonnet`), a bracketed
 * variant (`opus[1m]`) or `default`, so nothing parses it and nothing presents it as a version.
 * [label] and [detail] are the same daemon strings put through [inert] — see its KDoc for why the
 * client owes that.
 */
data class ThreadModelChoice(
    val value: String,
    val label: String,
    /** The row's `resolvedModel`, or `""` when it says nothing [label] does not already say. */
    val detail: String,
    val effortChoices: List<ThreadEffortChoice>,
)

/** One selectable reasoning-effort level of one [ThreadModelChoice] (#807). Same split as its parent:
 *  [value] is the verbatim write argument, [label] the inert render of it. */
data class ThreadEffortChoice(
    val value: String,
    val label: String,
)

/**
 * The thread's run configuration (#807) — the daemon's saved reading, the vocabulary it published, and a
 * tap that has not yet been confirmed, in one value the footer line and the Status sheet both read.
 *
 * **Nothing here falls back to `AppPreferences`.** An unavailable reading is rendered as *unknown*: the
 * three-entry `Model` and five-entry `Effort` device enums are this phone's guesses, and a value this
 * server never published is refused server-side.
 *
 * @param choices The published models in the daemon's own order, which is the display order.
 * @param menuAvailable Whether a menu was ever published for this conversation. `false` with empty
 *   [choices] is "no list"; `true` with empty [choices] is the different, equally legal reading that
 *   claude offered nothing.
 * @param droppedModels Entries the **producer** cut, exactly as reported and never recomputed from
 *   `choices.size` — what lets the sheet say "10 of 47" rather than present a shortened menu as complete.
 * @param hiddenChoices Entries **this client** cut at [MAX_RENDERED_MODEL_CHOICES]. Separate from
 *   [droppedModels] so each number keeps its provenance; the sheet sums them for display only.
 * @param settingsAvailable Whether a settings reading is available at all. `false` ⇒ both labels read
 *   unknown; it covers no connection, no `interactive` capability, and the window before the first reply.
 * @param savedModel The saved model override verbatim, `""` meaning "no override, inherited default".
 * @param savedEffort The **saved** effort choice verbatim, `""` meaning inherited default. Never
 *   `effectiveEffort`, which is claude's *applied* reading and is #651's, not this surface's.
 * @param pendingModel / @param pendingEffort A tap whose write has not settled, or `null`. Cleared by an
 *   arriving reading — never by the acknowledgement, which is not a reading.
 * @param sessionId The session a write must address. **`""` means the daemon has no session to address**,
 *   so the controls are read-only and nothing is sent.
 */
data class ThreadRunConfig(
    val choices: List<ThreadModelChoice> = emptyList(),
    val menuAvailable: Boolean = false,
    val droppedModels: Int = 0,
    val hiddenChoices: Int = 0,
    val settingsAvailable: Boolean = false,
    val savedModel: String = "",
    val savedEffort: String = "",
    val pendingModel: String? = null,
    val pendingEffort: String? = null,
    val sessionId: String = "",
) {
    /** What the surfaces show: a pending tap while one is outstanding, the confirmed reading otherwise. */
    val selectedModel: String get() = pendingModel ?: savedModel

    /** The [selectedModel] twin for effort. */
    val selectedEffort: String get() = pendingEffort ?: savedEffort

    /** The published row [selectedModel] names, or `null` when the menu published no matching one. */
    val selectedChoice: ThreadModelChoice? get() = choices.firstOrNull { it.value == selectedModel }

    /** The effort levels **the selected row** supports. Empty is a positive statement that this model
     *  exposes no effort control — never a cue to substitute the `Effort` entries. */
    val effortChoices: List<ThreadEffortChoice> get() = selectedChoice?.effortChoices.orEmpty()

    /** Whether a write is outstanding: the surfaces keep it visibly distinct from confirmed state. */
    val pending: Boolean get() = pendingModel != null || pendingEffort != null

    /** Whether a write can be addressed at all — the `""`-session-id read-only gate. */
    val writable: Boolean get() = sessionId.isNotEmpty()

    /** The footer's model segment. */
    val modelLabel: String get() = label(selectedModel) { selectedChoice?.label }

    /** The footer's effort segment. No menu lookup: a level is its own label. */
    val effortLabel: String get() = label(selectedEffort) { null }

    /**
     * The three display states the contracts keep apart, collapsed to one string for the footer: no
     * reading at all is *unknown*; a reading of `""` is the daemon's inherited default, which is a real
     * answer rather than an absent one; anything else is the published label when the menu named one and
     * the reported value itself — made [inert], since it is daemon-authored too — when it did not.
     */
    private inline fun label(
        raw: String,
        published: () -> String?,
    ): String =
        when {
            !settingsAvailable -> UNKNOWN_RUN_CONFIG_LABEL
            raw.isEmpty() -> INHERITED_RUN_CONFIG_LABEL
            else -> published() ?: raw.inert()
        }
}

internal const val UNKNOWN_RUN_CONFIG_LABEL = "unknown"

internal const val INHERITED_RUN_CONFIG_LABEL = "default"

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
    // #492: the coordinator's process-scoped, reconnection-surviving "current modal" projection (#437/#445),
    // folded once at the coordinator layer (no longer per-thread-screen) and re-exposed here verbatim as
    // [currentModal]. Defaulted to a fresh MutableStateFlow(Hidden) so the fake-backed Koin graph + non-modal
    // tests stay inert — exactly as the old empty-flow default did. As a `val` ctor property it *is* the
    // exposed StateFlow (no wrapping); the arm/answer/cancel logic reads its `.value` unchanged (AC #3).
    val currentModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden),
    // #451: the outbound modal-send path → the coordinator's passthrough to the connection-scoped concrete
    // repo (RelayRepositoryCoordinator.answerModal / cancelModal). Defaulted no-ops so the fake-backed Koin
    // graph + existing ThreadViewModel tests stay inert. The VM holds only these two suspend lambdas, never
    // the facade-bypassing concrete repo or the coordinator (the outbound analog of the modalEvents flow).
    private val answerModal: suspend (modalId: String, optionId: String) -> Unit = { _, _ -> },
    private val cancelModal: suspend (modalId: String) -> Unit = { _ -> },
    // #458: the outbound `interrupt` send path → the coordinator's passthrough (RelayRepositoryCoordinator
    // .interrupt). Defaulted no-op so the fake-backed Koin graph + existing tests stay inert. The VM holds
    // only this suspend lambda, never the coordinator/concrete repo — same posture as answerModal/cancelModal.
    private val interrupt: suspend (conversationId: String) -> Unit = {},
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

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

    /** A model tap whose write has not settled (#807), or `null`. Single-writer, like [yoloEnabled]:
     *  [onModelSelected] sets it, a failed write clears it, and [sessionSettings]'s `onEach` clears it
     *  when a reading lands. */
    private val pendingModel = MutableStateFlow<String?>(null)

    /** The [pendingModel] twin for effort (#807). */
    private val pendingEffort = MutableStateFlow<String?>(null)

    /**
     * This conversation's saved run configuration (#590), the authority for the displayed model and
     * effort and for the session id a write addresses (#807).
     *
     * The `onEach` is the pending-clearing rule AC #3 states: **an arriving reading** ends a pending
     * selection, never the acknowledgement, which echoes only the input session id and confirms nothing.
     * It rides this flow rather than a second collector because `observeSessionSettings` is cold and
     * per-collector — a separate subscription would send a second `request_session_settings` frame on
     * every thread entry. A reading that lands between a tap and its ack clears early, deliberately: a
     * reading outranks an unacked optimistic value for the same reason the ack does not outrank it.
     */
    private val sessionSettings: Flow<SessionSettings?> =
        repository
            .observeSessionSettings(conversationId)
            .onEach {
                pendingModel.value = null
                pendingEffort.value = null
            }

    private val yoloEnabled = MutableStateFlow(false)

    /**
     * The run-configuration arm of [state] (#807). Five inputs, which is exactly Kotlin's typed `combine`
     * ceiling — the reason this stays one arm of the five-arm `state` combine instead of needing a sixth
     * or the sibling-[StateFlow] shape [draft] uses.
     */
    private val runConfigFlow: Flow<RunConfig> =
        combine(
            sessionSettings,
            repository.observeModelMenu(conversationId),
            pendingModel,
            pendingEffort,
            yoloEnabled,
        ) { settings, menu, model, effort, yolo ->
            RunConfig(runConfig(settings, menu, model, effort), yolo)
        }

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
            repository.observeConversations(ConversationFilter.All),
            threadContent,
            pendingWorkspacePicker,
            transientDialogs,
            runConfigFlow,
        ) { conversations, content, pickerVisible, dialogs, runConfig ->
            val conv = conversations.firstOrNull { it.id == conversationId }
            ThreadUiState(
                conversationId = conversationId,
                displayName = conv?.displayName() ?: conversationId,
                isPromoted = conv?.isPromoted ?: false,
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
                runConfig = runConfig.config,
                yoloEnabled = runConfig.yoloEnabled,
                mutationsSupported = mutationsSupported,
                historyTail = content.historyTail,
            )
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

    val connectionState: StateFlow<ConnectionState> =
        connectionStateSource
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionState.Connected,
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

    private val modalSendErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)

    /**
     * One-shot "a modal send (answer or cancel) failed" signal (#451) — the established one-shot VM→UI
     * event idiom this VM already uses for [navigationEvents]. Carries **no** modal payload (just [Unit]),
     * so nothing sensitive can leak through it; the render slice (#452) shows a transient snackbar. Fires
     * exactly once per caught failure ([RelayErrorException] from a server `error`, incl. the
     * ungranted-device reject pyrycode#702; [IllegalStateException] from a not-connected session).
     */
    val modalSendErrors: Flow<Unit> = modalSendErrorChannel.receiveAsFlow()

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

    init {
        // AC #1 of #777: opening a thread asks for the newest page. A phone that only ever rendered the
        // live stream showed nothing that predated its connection.
        requestOlderHistory()

        // #778: a new connection restarts the walk from the newest page. The repository's projections are
        // connection-scoped, so a cursor minted on one connection is not valid on the next — the walk
        // restarts rather than resumes.
        //
        // Two deliberate choices here. It collects the SOURCE rather than the [connectionState] StateFlow:
        // that flow is WhileSubscribed and seeds Connected, so its first value is synthetic and its
        // upstream depends on the screen being subscribed. And `drop(1)` after `distinctUntilChanged`
        // drops exactly the connection the thread opened on — the source hands every collector its current
        // value on subscription, so restarting on it would restart the walk this init has just started,
        // spending a page of budget and a round trip on every open. Only a RETURN to connected counts.
        // A first value of Offline correctly makes the following connect a restart: the opening ask on
        // that connection already failed.
        viewModelScope.launch {
            connectionStateSource
                .observe()
                .map { it == ConnectionState.Connected }
                .distinctUntilChanged()
                .drop(1)
                .collect { connected ->
                    if (connected) {
                        RelayLog.d { "event=history_walk_restart reason=reconnect" }
                        restartHistoryWalk(fromWalk = historyDemand.value.walk)
                    }
                }
        }
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
        if (text.isBlank()) return
        launchGuardedRepoCall {
            repository.sendMessage(state.value.conversationId, text)
            if (draftStore.draftFor(serverId, conversationId) == text) onDraftChange("")
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
    fun onModalOption(optionId: String) {
        val open = currentModal.value as? ModalUiState.Open ?: return
        when {
            optionId == open.defaultOptionId -> sendAnswer(open.modalId, optionId)
            armedModalOption.value == ArmedModalOption(open.modalId, optionId) ->
                sendAnswer(open.modalId, optionId)
            else -> armedModalOption.value = ArmedModalOption(open.modalId, optionId)
        }
    }

    /** Cancel the currently-open modal (#451): clear any arm and send `modal_cancel`. No-op if no modal is
     *  open. */
    fun onModalCancel() {
        val open = currentModal.value as? ModalUiState.Open ?: return
        armedModalOption.value = null
        sendCancel(open.modalId)
    }

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
    ) {
        armedModalOption.value = null
        viewModelScope.launch {
            try {
                answerModal(modalId, optionId)
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

    /** Stop this ViewModel's conversation. Always attempts the send; the daemon is authoritative on
     *  whether its turn is running and on the interactive gate. The affordance passes no arguments:
     *  [sendInterrupt] supplies the saved open [conversationId], without changing local turn state. */
    fun onInterrupt() {
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
     * so this holds no state to roll back and a failed drop is inert (AC #4). The catch contract mirrors
     * [sendInterrupt] exactly: the [CancellationException] rethrow **MUST precede** the typed catches
     * (`j.u.c.CancellationException` extends `IllegalStateException` on the JVM) so structured cancellation
     * is never swallowed; the server-error and not-connected throws are swallowed with no error surface.
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
        val config = state.value.runConfig
        if (config.pending || level == config.selectedEffort) return
        if (!skipUnlessWritable(config)) return
        pendingEffort.value = level
        sendSessionSettings(config.sessionId, effort = level) { pendingEffort.value = null }
    }

    /**
     * The [onModelSelected] twin for YOLO (#544). [yoloEnabled] is a raw [Boolean] source, so the guard
     * and the revert compare/restore its `.value` directly.
     *
     * #807 re-routed its session id along with the other two: [sendSessionSettings] is shared, and the
     * authoritative key is the settings reading's. Keeping `Conversation.currentSessionId` alive for this
     * one control would leave two routing sources — a second place to get the same thing wrong.
     */
    fun onYoloToggled(enabled: Boolean) {
        if (enabled == yoloEnabled.value) return
        val config = state.value.runConfig
        if (!skipUnlessWritable(config)) return
        val previous = yoloEnabled.value
        yoloEnabled.value = enabled
        sendSessionSettings(config.sessionId, yolo = enabled) { yoloEnabled.value = previous }
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
     * The shared outbound path for the three Status-sheet controls (#544): send only the changed field(s)
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
        yolo: Boolean? = null,
        revert: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                repository.setSessionSettings(sessionId, model, effort, yolo)
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
            ThreadEvent.SaveAsChannel ->
                pendingSaveAsChannelDialog.value =
                    SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)
            is ThreadEvent.SaveAsChannelSubmit -> {
                pendingSaveAsChannelDialog.value = null
                launchGuardedRepoCall {
                    repository.promote(
                        state.value.conversationId,
                        event.name,
                        resolveWorkspace(event.name, event.workspace),
                    )
                }
            }
            ThreadEvent.SaveAsChannelDismiss -> pendingSaveAsChannelDialog.value = null
            ThreadEvent.ChannelInfo -> pendingChannelInfo.value = true
            ThreadEvent.ChannelInfoDismiss -> pendingChannelInfo.value = false
            ThreadEvent.ChangeWorkspace -> pendingWorkspacePicker.value = true
            ThreadEvent.NewSession -> sendNewSession()
        }
    }

    /** A non-default option armed for a second confirm (#451), scoped to the [modalId] it belongs to.
     *  `private` → not an exported type; structural equality drives the second-confirm match. */
    private data class ArmedModalOption(
        val modalId: String,
        val optionId: String,
    )

    /** The run-configuration arm's payload: the #807 daemon-sourced surface plus the YOLO flag, which is
     *  a [ThreadUiState] field of its own rather than part of [ThreadRunConfig] — #807 re-sources model
     *  and effort only, and folding YOLO into that type would move a control this ticket does not touch. */
    private data class RunConfig(
        val config: ThreadRunConfig,
        val yoloEnabled: Boolean,
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
private const val AUTO_SUGGESTED_CHANNEL_NAME = "New channel"

private fun resolveWorkspace(
    name: String,
    choice: WorkspaceChoice,
): String? =
    when (choice) {
        WorkspaceChoice.DEDICATED -> "pyry-workspace/channels/${name.toChannelSlug()}"
        WorkspaceChoice.SCRATCH -> null
    }

private fun String.toChannelSlug(): String =
    lowercase()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^a-z0-9-]"), "")
        .trim('-')
        .ifEmpty { "channel" }

// ---- #544: Model / Effort → set_session_settings wire strings (file-private) ---------------------
//
// #807 deleted `Effort.wire()` / `Model.wire()`, the two enum-to-daemon-string mappers #544 added here.
// Their premise was that the phone knows the server's vocabulary; it does not. Every argument sent now
// comes from `ModelMenuRow.value` / `effortLevels` — the server's own strings, forwarded verbatim.

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
    pendingModel: String?,
    pendingEffort: String?,
): ThreadRunConfig {
    val rows = menu?.rows.orEmpty()
    return ThreadRunConfig(
        choices = rows.take(MAX_RENDERED_MODEL_CHOICES).map { it.toChoice() },
        menuAvailable = menu != null,
        droppedModels = menu?.droppedModels ?: 0,
        hiddenChoices = (rows.size - MAX_RENDERED_MODEL_CHOICES).coerceAtLeast(0),
        settingsAvailable = settings != null,
        savedModel = settings?.model.orEmpty(),
        savedEffort = settings?.effort.orEmpty(),
        pendingModel = pendingModel,
        pendingEffort = pendingEffort,
        sessionId = settings?.sessionId.orEmpty(),
    )
}

/** One published row, split into the verbatim write argument and the inert render of it. `resolvedModel`
 *  becomes [ThreadModelChoice.detail] only when it says something the label does not. */
private fun ModelMenuRow.toChoice(): ThreadModelChoice {
    val label = displayName.inert()
    return ThreadModelChoice(
        value = value,
        label = label,
        detail = resolvedModel.inert().takeIf { it.isNotBlank() && it != label }.orEmpty(),
        effortChoices = effortLevels.map { ThreadEffortChoice(value = it, label = it.inert()) },
    )
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

// ---- #337: live assistant-delta accumulation (pure, file-private) -------------------------------

/** The merged input to the thread fold: the finished projection plus the live structured stream. */
private sealed interface ThreadInput {
    /** A finished-message projection from `observeMessages` (#313). */
    data class Finished(
        val items: List<ThreadItem>,
    ) : ThreadInput

    /** One decoded live structured event from `liveSessionEvents` (#385). */
    data class Live(
        val event: LiveSessionEvent,
    ) : ThreadInput
}

/** The in-flight streaming turn being accumulated, or `null` between turns. */
private data class StreamingTurn(
    val turnId: String,
    /** Accumulated [LiveSessionEvent.AssistantDelta] text, in `seq` order. */
    val text: String,
    /** Ordering/dup guard for the current turn — deltas with `seq <= lastSeq` are ignored. */
    val lastSeq: Int,
    /** `turn_end` seen → render `isStreaming = false` (settled) but keep the item until the finished message. */
    val ended: Boolean,
    /** Assistant [ThreadItem.MessageItem] ids present when the turn started — the finalise oracle. */
    val baselineAssistantIds: Set<String>,
)

/** The fold accumulator: the latest finished projection plus the current [StreamingTurn]. */
private data class ThreadFold(
    val finished: List<ThreadItem>,
    val stream: StreamingTurn?,
)

/**
 * Folds one [ThreadInput] into the next [ThreadFold]. Pure and side-effect-free — **no logging** of
 * delta text or any payload field (the verbatim text is untrusted/sensitive; see [LiveSessionEvent]).
 *
 * The conversation-id guard is checked **first** for every live event (AC #2/#3 confidentiality —
 * mirrors `thinkingTransition`). Finalise is structural, not id-correlated: the streaming item is
 * dropped the moment the finished list gains an assistant message whose id was not present when the
 * turn started — i.e. this turn's persisted message has arrived, whether before or after `turn_end`.
 */
private fun ThreadFold.reduce(
    input: ThreadInput,
    conversationId: String,
): ThreadFold =
    when (input) {
        is ThreadInput.Finished -> {
            val turnPersisted =
                stream != null &&
                    input.items.any {
                        it is ThreadItem.MessageItem &&
                            it.message.role == Role.Assistant &&
                            it.message.id !in stream.baselineAssistantIds
                    }
            ThreadFold(finished = input.items, stream = if (turnPersisted) null else stream)
        }
        is ThreadInput.Live -> reduceLive(input.event, conversationId)
    }

private fun ThreadFold.reduceLive(
    event: LiveSessionEvent,
    conversationId: String,
): ThreadFold {
    if (event.conversationId != conversationId) return this
    return when (event) {
        is LiveSessionEvent.AssistantDelta -> reduceDelta(event)
        is LiveSessionEvent.TurnEnd -> {
            val current = stream
            if (current != null && current.turnId == event.turnId) {
                copy(stream = current.copy(ended = true))
            } else {
                this
            }
        }
        is LiveSessionEvent.TurnState,
        is LiveSessionEvent.ToolUse,
        is LiveSessionEvent.ToolResult,
        is LiveSessionEvent.ReplayGap,
        -> this
    }
}

private fun ThreadFold.reduceDelta(delta: LiveSessionEvent.AssistantDelta): ThreadFold {
    val current = stream
    return when {
        // A new turn (or first delta) — supersedes any unfinalised prior turn.
        current == null || current.turnId != delta.turnId ->
            copy(
                stream =
                    StreamingTurn(
                        turnId = delta.turnId,
                        text = delta.text,
                        lastSeq = delta.seq,
                        ended = false,
                        baselineAssistantIds = finished.assistantIds(),
                    ),
            )
        // In-order delta for the current turn — append.
        delta.seq > current.lastSeq ->
            copy(stream = current.copy(text = current.text + delta.text, lastSeq = delta.seq))
        // Out-of-order or replayed delta — ignore (AC #2).
        else -> this
    }
}

/** Renders the fold to thread rows: the finished projection, plus the streaming turn appended last. */
private fun ThreadFold.render(): List<ThreadItem> {
    val turn = stream ?: return finished
    // Key-uniqueness guard (#425): the daemon may set `turnId == message_id`, so the synthetic's id can
    // equal a persisted message's id and the structural finalise can miss the collision (when the colliding
    // id was already in the turn's baseline). The thread keys every MessageItem as "msg:<id>", so two items
    // sharing an id crash LazyColumn. Append the synthetic only when no finished message already carries
    // this turn's id — render-time, source-independent, total over every interleaving.
    if (finished.any { it is ThreadItem.MessageItem && it.message.id == turn.turnId }) return finished
    val lastMessage = finished.lastOrNull { it is ThreadItem.MessageItem } as? ThreadItem.MessageItem
    val synthetic =
        Message(
            id = turn.turnId,
            sessionId = lastMessage?.message?.sessionId.orEmpty(),
            role = Role.Assistant,
            content = turn.text,
            timestamp = lastMessage?.message?.timestamp ?: Instant.fromEpochMilliseconds(0),
            isStreaming = !turn.ended,
        )
    return finished + ThreadItem.MessageItem(synthetic)
}

private fun List<ThreadItem>.assistantIds(): Set<String> =
    asSequence()
        .filterIsInstance<ThreadItem.MessageItem>()
        .filter { it.message.role == Role.Assistant }
        .map { it.message.id }
        .toSet()
