package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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

    data object RestoreFailed : ArchivedDiscussionsEffect
}

/**
 * @param repository the owning host's own repository (#715), bound by the route rather than by
 *   compatibility selection. Every read and every write on this screen goes through it, so a
 *   selection change, a reconnect or an unpair can move neither the rows nor the restore target.
 * @param hostLabel that host's resolved display name, or blank when it names none. Defaulted so the
 *   seventeen unit-test construction sites that predate this ownership stay call-compatible, the way
 *   `ThreadViewModel`'s own collaborators are; Koin always passes it.
 */
class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
    hostLabel: Flow<String> = flowOf(""),
) : ViewModel() {
    private val selectedTab = MutableStateFlow(ArchiveTab.Discussions)

    /**
     * The owning host, for the header.
     *
     * Its own flow rather than a field on [ArchivedDiscussionsUiState.Loaded], because the header is
     * drawn outside the state branch and has to stay stable across `Loading` and `Error` too. Blank
     * means no host is named — never another host's identity.
     */
    val host: StateFlow<String> =
        hostLabel.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = "",
        )

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
                    // Success is list-driven (observeConversations re-emits without this
                    // conversation); on failure surface a fixed local string, never a silent
                    // no-op. A server `error` reply is reachable (unarchive is request/reply) as
                    // RelayErrorException; not-connected is IllegalStateException from the `live`
                    // path. Both map to the payload-free RestoreFailed — the server-supplied
                    // message is never read. The CancellationException rethrow MUST precede the
                    // typed catches: j.u.c.CancellationException extends ISE on the JVM, so teardown
                    // mid-restore stays inert and is never mis-surfaced as a failure.
                    try {
                        repository.unarchive(event.conversationId)
                        _effects.send(
                            ArchivedDiscussionsEffect.RestoreSucceeded(event.displayName),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RelayErrorException) {
                        _effects.send(ArchivedDiscussionsEffect.RestoreFailed)
                    } catch (e: IllegalStateException) {
                        _effects.send(ArchivedDiscussionsEffect.RestoreFailed)
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
