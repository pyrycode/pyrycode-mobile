package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.ui.conversations.thread.REPLY_SUGGESTION_PLACEHOLDER_TAG
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Both daemon rungs observe the actual repository offer, its user echo and the revisioned clear. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ComposeContentTestRule.assertReplySuggestionLongPress(
    repository: ConversationRepository,
    conversationId: String,
    timeoutMillis: Long,
) {
    val suggestion =
        runBlocking {
            withTimeout(timeoutMillis) {
                combine(
                    repository.observeConversations(ConversationFilter.All),
                    repository.observeSessionSettings(conversationId),
                ) { conversations, settings ->
                    conversations
                        .singleOrNull { it.id == conversationId }
                        ?.currentSessionId
                        ?.takeIf { it.isNotBlank() }
                        ?: settings?.takeUnless { it.held }?.sessionId?.takeIf { it.isNotBlank() }
                }.distinctUntilChanged()
                    .flatMapLatest { session ->
                        session?.let { repository.observeReplySuggestion(conversationId, it) } ?: flowOf(null)
                    }.filterNotNull()
                    .first { !it.suggestedReply.isNullOrBlank() }
            }
        }
    val text = requireNotNull(suggestion.suggestedReply)
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).assertTextEquals(text)
    onNodeWithContentDescription("Send message").performTouchInput { longClick() }
    val messages =
        runBlocking {
            withTimeout(timeoutMillis) {
                harnessThreadEvidence(repository).observe(conversationId).first { it.userEchoCount(text) == 1 }
            }
        }
    assertEquals(1, messages.userEchoCount(text))
    val clear =
        runBlocking {
            withTimeout(timeoutMillis) {
                repository
                    .observeReplySuggestion(conversationId, suggestion.sessionId)
                    .filterNotNull()
                    .first { it.revision > suggestion.revision && it.suggestedReply == null }
            }
        }
    assertTrue(clear.revision > suggestion.revision)
    waitUntil(timeoutMillis) { onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
}
