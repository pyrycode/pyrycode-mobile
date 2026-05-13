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

sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data object Empty : ChannelListUiState
    data class Loaded(val channels: List<Conversation>) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}

sealed interface ChannelListNavigation {
    data class ToThread(val conversationId: String) : ChannelListNavigation
}

class ChannelListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {

    val state: StateFlow<ChannelListUiState> =
        repository.observeConversations(ConversationFilter.Channels)
            .map<List<Conversation>, ChannelListUiState> { channels ->
                if (channels.isEmpty()) ChannelListUiState.Empty
                else ChannelListUiState.Loaded(channels)
            }
            .catch { e ->
                val raw = e.message
                emit(
                    ChannelListUiState.Error(
                        if (raw.isNullOrBlank()) "Failed to load channels." else raw,
                    ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ChannelListUiState.Loading,
            )

    private val navigationChannel = Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ChannelListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: ChannelListEvent) {
        when (event) {
            ChannelListEvent.CreateDiscussionTapped -> viewModelScope.launch {
                val conversation = repository.createDiscussion()
                navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))
            }
            is ChannelListEvent.RowTapped, ChannelListEvent.SettingsTapped -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
