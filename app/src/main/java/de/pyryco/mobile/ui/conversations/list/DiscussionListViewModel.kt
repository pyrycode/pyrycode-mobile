package de.pyryco.mobile.ui.conversations.list

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

sealed interface DiscussionListUiState {
    data object Loading : DiscussionListUiState

    data object Empty : DiscussionListUiState

    data class Loaded(
        val discussions: List<Conversation>,
        val pendingPromotion: PendingPromotion? = null,
    ) : DiscussionListUiState

    data class Error(
        val message: String,
    ) : DiscussionListUiState
}

data class PendingPromotion(
    val conversationId: String,
    val sourceName: String?,
)

sealed interface DiscussionListNavigation {
    data class ToThread(
        val conversationId: String,
    ) : DiscussionListNavigation
}

class DiscussionListViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    private val pendingPromotion = MutableStateFlow<PendingPromotion?>(null)

    val state: StateFlow<DiscussionListUiState> =
        combine(
            repository.observeConversations(ConversationFilter.Discussions),
            pendingPromotion,
        ) { discussions, pending ->
            if (discussions.isEmpty()) {
                // Drop a stale pending promotion if the discussion list collapsed
                // (e.g. the only entry was archived externally while the dialog
                // was open) — prevents a later Loaded emission from reviving it.
                if (pending != null) pendingPromotion.value = null
                DiscussionListUiState.Empty
            } else {
                DiscussionListUiState.Loaded(discussions, pending)
            }
        }.catch { e ->
            val raw = e.message
            emit(
                DiscussionListUiState.Error(
                    if (raw.isNullOrBlank()) "Failed to load discussions." else raw,
                ),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DiscussionListUiState.Loading,
        )

    private val navigationChannel = Channel<DiscussionListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<DiscussionListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: DiscussionListEvent) {
        when (event) {
            is DiscussionListEvent.RowTapped ->
                viewModelScope.launch {
                    navigationChannel.send(DiscussionListNavigation.ToThread(event.conversationId))
                }
            is DiscussionListEvent.SaveAsChannelRequested ->
                openPromotionDialog(event.conversationId)
            DiscussionListEvent.PromoteConfirmed -> confirmPromotion()
            DiscussionListEvent.PromoteCancelled -> pendingPromotion.value = null
            DiscussionListEvent.BackTapped -> Unit
        }
    }

    private fun openPromotionDialog(conversationId: String) {
        val current = state.value as? DiscussionListUiState.Loaded ?: return
        val match = current.discussions.firstOrNull { it.id == conversationId } ?: return
        pendingPromotion.value = PendingPromotion(conversationId, match.name)
    }

    private fun confirmPromotion() {
        val snapshot = pendingPromotion.value ?: return
        pendingPromotion.value = null
        viewModelScope.launch {
            repository.promote(
                conversationId = snapshot.conversationId,
                name = derivedChannelName(snapshot.sourceName),
                workspace = null,
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun derivedChannelName(sourceName: String?): String = sourceName?.takeIf { it.isNotBlank() } ?: "Untitled channel"

internal fun displayLabel(sourceName: String?): String = sourceName?.takeIf { it.isNotBlank() } ?: "Untitled discussion"
