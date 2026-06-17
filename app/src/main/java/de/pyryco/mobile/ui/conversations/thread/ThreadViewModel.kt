package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
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
    val lastMessage = finished.lastOrNull { it is ThreadItem.MessageItem } as? ThreadItem.MessageItem
    val synthetic =
        Message(
            // Stable per-turn id, distinct namespace from the server message_id — the LazyColumn key
            // never collides, and the two are never both present (finalise drops this in the same emission).
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
