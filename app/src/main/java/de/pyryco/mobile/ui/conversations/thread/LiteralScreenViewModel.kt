package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hoisted MVI state for the manual "show the literal screen" surface (#379 renders it). The
 * snapshot text is server-originated and may carry sensitive on-screen content, so [Content] holds
 * it verbatim and redacts its [toString]; no state here is ever logged.
 */
sealed interface LiteralScreenUiState {
    /** The snapshot read is in flight (also the initial state — the surface opens into a spinner). */
    data object Loading : LiteralScreenUiState

    data class Content(
        val text: String,
    ) : LiteralScreenUiState {
        // Security net (AC#4): the verbatim screen text must never reach a log / crash dump via an
        // accidental "$state". Mirrors ScannerUiState.Decoded.toString. Does not touch
        // equals/hashCode, so assertEquals(Content("x"), …) still holds.
        override fun toString(): String = "Content(text=<redacted ${text.length} chars>)"
    }

    data class Error(
        val reason: LiteralScreenError,
    ) : LiteralScreenUiState
}

/**
 * One value per failure path of [ConversationRepository.requestScreenSnapshot]. Carrying a closed
 * enum (not the raw exception / server message) keeps the error state free of any server-supplied
 * string. Every reason is retryable via [LiteralScreenEvent.Retry]. User-facing copy is #379's job.
 */
enum class LiteralScreenError { UnknownConversation, ServerError, NotConnected }

sealed interface LiteralScreenEvent {
    /** Initial fetch, fired once when the surface opens. */
    data object Request : LiteralScreenEvent

    /** Re-fetch after an error. */
    data object Retry : LiteralScreenEvent
}

/**
 * Orchestrates a single "show the literal screen" request against
 * [ConversationRepository.requestScreenSnapshot] and exposes the result as hoisted [state]. The
 * [conversationId] is fixed for the VM's lifetime (read from the route), so [LiteralScreenEvent.Retry]
 * re-issues against the same id.
 */
class LiteralScreenViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
) : ViewModel() {
    // Only the non-sensitive route arg is read from SavedStateHandle — the snapshot text is never
    // written back into it (saved-state is serialized to the disk-backed instance-state bundle).
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    private val _state = MutableStateFlow<LiteralScreenUiState>(LiteralScreenUiState.Loading)
    val state: StateFlow<LiteralScreenUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun onEvent(event: LiteralScreenEvent) {
        when (event) {
            LiteralScreenEvent.Request, LiteralScreenEvent.Retry -> load()
        }
    }

    private fun load() {
        loadJob?.cancel() // latest-request-wins: a stale slow read can't overwrite newer state.
        loadJob =
            viewModelScope.launch {
                _state.value = LiteralScreenUiState.Loading
                try {
                    _state.value = LiteralScreenUiState.Content(repository.requestScreenSnapshot(conversationId))
                } catch (e: CancellationException) {
                    throw e // job/scope cancellation must propagate, never be mapped to an Error.
                } catch (e: Exception) {
                    _state.value = LiteralScreenUiState.Error(reasonFor(e))
                }
            }
    }

    private fun reasonFor(e: Exception): LiteralScreenError =
        when (e) {
            is IllegalArgumentException -> LiteralScreenError.UnknownConversation
            is RelayErrorException -> LiteralScreenError.ServerError
            is IllegalStateException -> LiteralScreenError.NotConnected
            else -> LiteralScreenError.ServerError // defensive fallback for any unexpected throwable.
        }
}
