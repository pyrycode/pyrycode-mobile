package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ThreadEvent {
    data object NewSession : ThreadEvent

    data object Rename : ThreadEvent

    data object ChangeWorkspace : ThreadEvent

    data object Archive : ThreadEvent

    data object ChannelInfo : ThreadEvent
}

data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val items: List<ThreadItem> = emptyList(),
    val selectedModel: Model = Model.OPUS_4_7,
    val effort: String = "high",
    val tokenPercent: Int = 0,
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    private val pendingWorkspacePicker = MutableStateFlow(false)

    private val modelOverride = MutableStateFlow<Model?>(null)

    private val selectedModelFlow: Flow<Model> =
        combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }

    val state: StateFlow<ThreadUiState> =
        combine(
            repository.observeConversations(ConversationFilter.All),
            repository.observeMessages(conversationId),
            pendingWorkspacePicker,
            selectedModelFlow,
        ) { conversations, items, pickerVisible, selectedModel ->
            val conv = conversations.firstOrNull { it.id == conversationId }
            ThreadUiState(
                conversationId = conversationId,
                displayName = conv?.displayName() ?: conversationId,
                isPromoted = conv?.isPromoted ?: false,
                hasMessages = items.any { it is ThreadItem.MessageItem },
                workspaceLabel = conv?.workspaceLabel() ?: "scratch",
                workspacePickerVisible = pickerVisible,
                items = items,
                selectedModel = selectedModel,
                effort = STUB_EFFORT,
                tokenPercent = STUB_TOKEN_PERCENT,
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

    fun onOverflowEvent(event: ThreadEvent) {
        when (event) {
            ThreadEvent.Archive ->
                viewModelScope.launch {
                    repository.archive(state.value.conversationId)
                }
            ThreadEvent.NewSession,
            ThreadEvent.Rename,
            ThreadEvent.ChangeWorkspace,
            ThreadEvent.ChannelInfo,
            -> Unit
        }
    }

    companion object {
        // Phase 4 swap point: replace with backend AgentStatus flow.
        private const val STUB_EFFORT = "high"
        private const val STUB_TOKEN_PERCENT = 73
    }
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

private fun Conversation.workspaceLabel(): String =
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) {
        "scratch"
    } else {
        cwd.substringAfterLast('/').ifEmpty { cwd }
    }
