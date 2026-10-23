package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ReplySuggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Keep the session inputs and offer on one current connection, including after a producer-gap redial. */
internal suspend fun StateFlow<ConversationRepository?>.awaitReplySuggestion(conversationId: String): ReplySuggestion =
    firstOnLive({ it.replySuggestions(conversationId) }) { !it.suggestedReply.isNullOrBlank() }

internal suspend fun StateFlow<ConversationRepository?>.awaitSuggestionEcho(
    conversationId: String,
    text: String,
): ThreadEvidence = firstOnLive({ harnessThreadEvidence(it).observe(conversationId) }) { it.userEchoCount(text) == 1 }

internal suspend fun StateFlow<ConversationRepository?>.awaitSuggestionClear(suggestion: ReplySuggestion): ReplySuggestion =
    firstOnLive({ it.observeReplySuggestion(suggestion.conversationId, suggestion.sessionId) }) {
        it.revision > suggestion.revision && it.suggestedReply == null
    }

@OptIn(ExperimentalCoroutinesApi::class)
private fun ConversationRepository.replySuggestions(conversationId: String): Flow<ReplySuggestion?> =
    combine(observeConversations(ConversationFilter.All), observeSessionSettings(conversationId)) { conversations, settings ->
        conversations.singleOrNull { it.id == conversationId }?.currentSessionId?.takeIf { it.isNotBlank() }
            ?: settings?.takeUnless { it.held }?.sessionId?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged().flatMapLatest { session ->
        session?.let { observeReplySuggestion(conversationId, it) } ?: flowOf(null)
    }
