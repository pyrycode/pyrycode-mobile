package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThreadUiState(
    val conversationId: String,
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val state: StateFlow<ThreadUiState> =
        MutableStateFlow(
            ThreadUiState(
                conversationId = savedStateHandle.get<String>("conversationId").orEmpty(),
            ),
        ).asStateFlow()
}
