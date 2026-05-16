package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadViewModelTest {
    @Test
    fun state_carriesConversationIdFromSavedStateHandle() {
        val handle = SavedStateHandle(initialState = mapOf("conversationId" to "abc-123"))
        val vm = ThreadViewModel(handle)
        assertEquals(ThreadUiState(conversationId = "abc-123"), vm.state.value)
    }

    @Test
    fun state_isImmediatelyAvailableWithoutSubscriber() {
        val handle = SavedStateHandle(initialState = mapOf("conversationId" to "xyz-9"))
        val vm = ThreadViewModel(handle)
        // No collect{} — value must be readable synchronously.
        assertEquals("xyz-9", vm.state.value.conversationId)
    }

    @Test
    fun state_collapsesAbsentConversationIdToEmptyString() {
        val handle = SavedStateHandle(initialState = emptyMap())
        val vm = ThreadViewModel(handle)
        assertEquals(ThreadUiState(conversationId = ""), vm.state.value)
    }
}
