package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.REPLY_SUGGESTION_PLACEHOLDER_TAG
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Both daemon rungs observe the actual repository offer, its user echo and the revisioned clear. */
internal fun ComposeContentTestRule.assertReplySuggestionLongPress(
    repository: ConversationRepository,
    conversationId: String,
    timeoutMillis: Long,
    progress: ReplySuggestionProgress = ReplySuggestionProgress(),
) = progress.run {
    progress.at(ReplySuggestionStage.SessionReady)
    val suggestion =
        runBlocking {
            withTimeout(timeoutMillis) {
                val session =
                    repository
                        .observeSessionSettings(conversationId)
                        .filterNotNull()
                        .first { !it.held }
                        .sessionId
                progress.at(ReplySuggestionStage.DaemonOffer)
                repository.observeReplySuggestion(conversationId, session).filterNotNull().first { !it.suggestedReply.isNullOrBlank() }
            }
        }
    val text = requireNotNull(suggestion.suggestedReply)
    progress.at(ReplySuggestionStage.Placeholder)
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).assertTextEquals(text)
    progress.at(ReplySuggestionStage.LongPressRelease)
    onNodeWithContentDescription("Send message").performTouchInput { longClick() }
    progress.at(ReplySuggestionStage.UserEcho)
    val messages =
        runBlocking {
            withTimeout(timeoutMillis) {
                repository.observeMessages(conversationId).first { it.suggestedReplyCount(text) == 1 }
            }
        }
    assertEquals(1, messages.suggestedReplyCount(text))
    progress.at(ReplySuggestionStage.RevisionedClear)
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
    progress.at(ReplySuggestionStage.PlaceholderRemoved)
    waitUntil(timeoutMillis) { onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
}

private fun List<ThreadItem>.suggestedReplyCount(text: String): Int =
    filterIsInstance<ThreadItem.MessageItem>().count { it.message.role == Role.User && it.message.content == text }
