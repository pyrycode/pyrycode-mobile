package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ArchiveTab { Channels, Discussions }

sealed interface ArchivedDiscussionsUiState {
    data object Loading : ArchivedDiscussionsUiState

    data class Loaded(
        val channels: List<Conversation>,
        val discussions: List<Conversation>,
        val selectedTab: ArchiveTab,
    ) : ArchivedDiscussionsUiState

    data class Error(
        val message: String,
    ) : ArchivedDiscussionsUiState
}

sealed interface ArchivedDiscussionsEvent {
    data class RestoreRequested(
        val conversationId: String,
        val displayName: String,
    ) : ArchivedDiscussionsEvent

    data object BackTapped : ArchivedDiscussionsEvent

    data class TabSelected(
        val tab: ArchiveTab,
    ) : ArchivedDiscussionsEvent
}

sealed interface ArchivedDiscussionsEffect {
    data class RestoreSucceeded(
        val displayName: String,
    ) : ArchivedDiscussionsEffect
}

class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(ArchiveTab.Discussions)

    private val _effects = Channel<ArchivedDiscussionsEffect>(Channel.BUFFERED)
    val effects: Flow<ArchivedDiscussionsEffect> = _effects.receiveAsFlow()

    val state: StateFlow<ArchivedDiscussionsUiState> =
        combine(
            repository.observeConversations(ConversationFilter.Archived),
            selectedTab,
        ) { conversations, tab ->
            val channels = conversations.filter { it.isPromoted }
            val discussions = conversations.filter { !it.isPromoted }
            ArchivedDiscussionsUiState.Loaded(
                channels = channels,
                discussions = discussions,
                selectedTab = tab,
            ) as ArchivedDiscussionsUiState
        }.catch { e ->
            val raw = e.message
            emit(
                ArchivedDiscussionsUiState.Error(
                    if (raw.isNullOrBlank()) "Failed to load archived discussions." else raw,
                ),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ArchivedDiscussionsUiState.Loading,
        )

    fun onEvent(event: ArchivedDiscussionsEvent) {
        when (event) {
            is ArchivedDiscussionsEvent.RestoreRequested ->
                viewModelScope.launch {
                    // Swallow failures: the AC scopes a snackbar only on success; a failed
                    // unarchive yields no UI surface in this slice. Re-throwing would crash
                    // viewModelScope's child via the default uncaught handler.
                    runCatching { repository.unarchive(event.conversationId) }
                        .onSuccess {
                            _effects.send(
                                ArchivedDiscussionsEffect.RestoreSucceeded(event.displayName),
                            )
                        }
                }
            is ArchivedDiscussionsEvent.TabSelected ->
                selectedTab.value = event.tab
            ArchivedDiscussionsEvent.BackTapped -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
