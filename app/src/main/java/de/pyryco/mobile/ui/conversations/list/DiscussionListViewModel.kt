package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DiscussionListUiState {
    data object Loading : DiscussionListUiState
    data object Empty : DiscussionListUiState
    data class Loaded(val discussions: List<Conversation>) : DiscussionListUiState
    data class Error(val message: String) : DiscussionListUiState
}

sealed interface DiscussionListNavigation {
    data class ToThread(val conversationId: String) : DiscussionListNavigation
}

class DiscussionListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {

    val state: StateFlow<DiscussionListUiState> =
        repository.observeConversations(ConversationFilter.Discussions)
            .map<List<Conversation>, DiscussionListUiState> { discussions ->
                if (discussions.isEmpty()) DiscussionListUiState.Empty
                else DiscussionListUiState.Loaded(discussions)
            }
            .catch { e ->
                val raw = e.message
                emit(
                    DiscussionListUiState.Error(
                        if (raw.isNullOrBlank()) "Failed to load discussions." else raw,
                    ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = DiscussionListUiState.Loading,
            )

    private val navigationChannel = Channel<DiscussionListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<DiscussionListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: DiscussionListEvent) {
        when (event) {
            is DiscussionListEvent.RowTapped -> viewModelScope.launch {
                navigationChannel.send(DiscussionListNavigation.ToThread(event.conversationId))
            }
            DiscussionListEvent.BackTapped -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
