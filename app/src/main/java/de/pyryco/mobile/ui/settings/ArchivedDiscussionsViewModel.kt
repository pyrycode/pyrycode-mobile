package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ArchivedDiscussionsUiState {
    data object Loading : ArchivedDiscussionsUiState

    data object Empty : ArchivedDiscussionsUiState

    data class Loaded(
        val discussions: List<Conversation>,
    ) : ArchivedDiscussionsUiState

    data class Error(
        val message: String,
    ) : ArchivedDiscussionsUiState
}

sealed interface ArchivedDiscussionsEvent {
    data class RestoreRequested(
        val conversationId: String,
    ) : ArchivedDiscussionsEvent

    data object BackTapped : ArchivedDiscussionsEvent
}

class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<ArchivedDiscussionsUiState> =
        repository
            .observeConversations(ConversationFilter.Archived)
            .map { conversations ->
                val discussions = conversations.filter { !it.isPromoted }
                if (discussions.isEmpty()) {
                    ArchivedDiscussionsUiState.Empty
                } else {
                    ArchivedDiscussionsUiState.Loaded(discussions)
                }
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
                    repository.unarchive(event.conversationId)
                }
            ArchivedDiscussionsEvent.BackTapped -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
