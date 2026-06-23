package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

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
    val channelInfoOpen: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    val workspacePath: String = "",
    val lastUsedAt: Instant? = null,
    val sessionCount: Int = 0,
    val selectedModel: Model = Model.OPUS_4_7,
    val selectedEffort: Effort = Effort.HIGH,
    val yoloEnabled: Boolean = false,
    val tokenPercent: Int = 0,
    val tokensUsed: Int = 0,
    val tokensTotal: Int = 0,
)

data class SaveAsChannelDialogState(
    val initialName: String,
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    private val appPreferences: AppPreferences,
    // #406: the coordinator's reconnection-surviving live-event seam, reduced to [isThinking]. Defaulted
    // to an empty flow so the fake-backed graph + existing tests stay inert (the flag holds `false`).
    liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
    // #445: the coordinator's reconnection-surviving modal-event seam (#437), folded to [currentModal].
    // Defaulted to an empty flow so the fake-backed graph + existing tests stay inert (state holds Hidden).
    modalEvents: Flow<ModalEvent> = emptyFlow(),
    // #451: the outbound modal-send path → the coordinator's passthrough to the connection-scoped concrete
    // repo (RelayRepositoryCoordinator.answerModal / cancelModal). Defaulted no-ops so the fake-backed Koin
    // graph + existing ThreadViewModel tests stay inert. The VM holds only these two suspend lambdas, never
    // the facade-bypassing concrete repo or the coordinator (the outbound analog of the modalEvents flow).
    private val answerModal: suspend (modalId: String, optionId: String) -> Unit = { _, _ -> },
    private val cancelModal: suspend (modalId: String) -> Unit = { _ -> },
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    private val pendingWorkspacePicker = MutableStateFlow(false)

    private val pendingRenameDialog = MutableStateFlow(false)

    private val pendingSaveAsChannelDialog = MutableStateFlow<SaveAsChannelDialogState?>(null)

    private val pendingChannelInfo = MutableStateFlow(false)

    private val pendingDeleteConfirm = MutableStateFlow(false)

    private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()

    private val modelOverride = MutableStateFlow<Model?>(null)

    private val selectedModelFlow: Flow<Model> =
        combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }

    private val effortOverride = MutableStateFlow<Effort?>(null)

    private val selectedEffortFlow: Flow<Effort> =
        combine(appPreferences.defaultEffort, effortOverride) { default, override -> override ?: default }

    private val yoloEnabled = MutableStateFlow(false)

    private val runConfigFlow: Flow<RunConfig> =
        combine(
            selectedModelFlow,
            selectedEffortFlow,
            yoloEnabled,
        ) { model, effort, yolo -> RunConfig(model, effort, yolo) }

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

    val state: StateFlow<ThreadUiState> =
        combine(
            repository.observeConversations(ConversationFilter.All),
            threadItems,
            pendingWorkspacePicker,
            transientDialogs,
            runConfigFlow,
        ) { conversations, items, pickerVisible, dialogs, runConfig ->
            val conv = conversations.firstOrNull { it.id == conversationId }
            ThreadUiState(
                conversationId = conversationId,
                displayName = conv?.displayName() ?: conversationId,
                isPromoted = conv?.isPromoted ?: false,
                hasMessages = items.any { it is ThreadItem.MessageItem },
                workspaceLabel = conv?.workspaceLabel() ?: "scratch",
                workspacePickerVisible = pickerVisible,
                showRenameDialog = dialogs.renameVisible,
                saveAsChannelDialog = dialogs.saveAsChannel,
                items = items,
                channelInfoOpen = dialogs.channelInfoOpen,
                deleteConfirmVisible = dialogs.deleteConfirmVisible,
                workspacePath = conv?.cwd ?: "",
                lastUsedAt = conv?.lastUsedAt,
                sessionCount = conv?.sessionHistory?.size ?: 0,
                selectedModel = runConfig.model,
                selectedEffort = runConfig.effort,
                yoloEnabled = runConfig.yoloEnabled,
                tokenPercent = STUB_TOKEN_PERCENT,
                tokensUsed = STUB_TOKENS_USED,
                tokensTotal = STUB_TOKENS_TOTAL,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue =
                ThreadUiState(
                    conversationId = conversationId,
                    displayName = conversationId,
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
     * The single hoisted "current modal" projection (#445): which permission/choice modal is currently
     * outstanding, folded from the `replay = 0` [modalEvents] stream (#437) via [ModalUiState.reduce]
     * (`Shown` → `Open`; matching `Dismissed` → `Dismissed`; non-matching `Dismissed` → no-op; last-shown
     * wins). A sibling [StateFlow] beside [isThinking] / [isStalled] (not a [ThreadUiState] field), taken
     * as a separate parameter by the stateless render screen (#446). Because modal events carry **no**
     * `conversation_id` ([ModalEvent] keys on `modalId` only), this is **app-level** — a single active
     * modal across the app — so there is no per-[conversationId] filter (contrast [thinkingTransition]).
     *
     * **Started [SharingStarted.Eagerly], a deliberate deviation from the `WhileSubscribed` siblings.**
     * `scan` re-emits its initial accumulator on every fresh upstream collection; under `WhileSubscribed`
     * a resubscription past the stop window would restart the `scan` and overwrite a retained `Open` with
     * `Hidden`, and because [modalEvents] is `replay = 0` the prior events do not replay to rebuild it — a
     * still-open modal would silently clear. `Eagerly` collects for the VM lifetime, so the accumulator
     * runs exactly once and `.value` is always the true current projection (the coordinator's
     * accumulate-a-`replay=0`-stream precedent: `currentRepository` / `connectionStatus`). Cost is
     * negligible — modals are one-at-a-time, user-driven, low-rate.
     */
    val currentModal: StateFlow<ModalUiState> =
        modalEvents
            .scan<ModalEvent, ModalUiState>(ModalUiState.Hidden) { state, event -> state.reduce(event) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ModalUiState.Hidden,
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

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(state.value.conversationId, text)
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
        viewModelScope.launch {
            repository.changeWorkspace(conversationId, path)
        }
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

    fun onModelSelected(model: Model) {
        modelOverride.value = model
    }

    fun onEffortSelected(effort: Effort) {
        effortOverride.value = effort
    }

    fun onYoloToggled(enabled: Boolean) {
        yoloEnabled.value = enabled
    }

    fun onOverflowEvent(event: ThreadEvent) {
        when (event) {
            ThreadEvent.Archive -> {
                pendingChannelInfo.value = false
                viewModelScope.launch {
                    repository.archive(state.value.conversationId)
                    navigationChannel.send(ThreadNavigation.PopBack)
                }
            }
            ThreadEvent.Delete -> pendingDeleteConfirm.value = true
            ThreadEvent.DeleteConfirm -> {
                pendingDeleteConfirm.value = false
                pendingChannelInfo.value = false
                viewModelScope.launch {
                    repository.delete(state.value.conversationId)
                    navigationChannel.send(ThreadNavigation.PopBack)
                }
            }
            ThreadEvent.DeleteDismiss -> pendingDeleteConfirm.value = false
            ThreadEvent.Rename -> pendingRenameDialog.value = true
            is ThreadEvent.RenameSubmit -> {
                pendingRenameDialog.value = false
                viewModelScope.launch {
                    repository.rename(state.value.conversationId, event.name)
                }
            }
            ThreadEvent.RenameDismiss -> pendingRenameDialog.value = false
            ThreadEvent.SaveAsChannel ->
                pendingSaveAsChannelDialog.value =
                    SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)
            is ThreadEvent.SaveAsChannelSubmit -> {
                pendingSaveAsChannelDialog.value = null
                viewModelScope.launch {
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
            ThreadEvent.NewSession -> Unit
        }
    }

    /** A non-default option armed for a second confirm (#451), scoped to the [modalId] it belongs to.
     *  `private` → not an exported type; structural equality drives the second-confirm match. */
    private data class ArmedModalOption(
        val modalId: String,
        val optionId: String,
    )

    private data class RunConfig(
        val model: Model,
        val effort: Effort,
        val yoloEnabled: Boolean,
    )

    private data class TransientDialogs(
        val renameVisible: Boolean,
        val saveAsChannel: SaveAsChannelDialogState?,
        val channelInfoOpen: Boolean,
        val deleteConfirmVisible: Boolean,
    )

    companion object {
        // Phase 4 swap point: replace with backend AgentStatus flow.
        private const val STUB_TOKEN_PERCENT = 73
        private const val STUB_TOKENS_USED = 146_000
        private const val STUB_TOKENS_TOTAL = 200_000
    }
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

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

private fun Conversation.workspaceLabel(): String =
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) {
        "scratch"
    } else {
        cwd.substringAfterLast('/').ifEmpty { cwd }
    }

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
