package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.ui.conversations.thread.REPLY_SUGGESTION_PLACEHOLDER_TAG
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Both daemon rungs observe the actual repository offer, its user echo and the revisioned clear. */
internal fun ComposeContentTestRule.assertReplySuggestionLongPress(
    repositories: StateFlow<ConversationRepository?>,
    conversationId: String,
    timeoutMillis: Long,
) {
    fun checkpoint(stage: String) {
        Log.i("ReplySuggestionProof", "event=reply_suggestion_checkpoint stage=$stage")
    }
    checkpoint("offer")
    val suggestion =
        runBlocking {
            withTimeout(timeoutMillis) {
                repositories.awaitReplySuggestion(conversationId)
            }
        }
    val text = requireNotNull(suggestion.suggestedReply)
    checkpoint("placeholder")
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).assertTextEquals(text)
    checkpoint("long_press_release")
    onNodeWithContentDescription("Send message").performTouchInput { longClick() }
    checkpoint("user_echo")
    val messages =
        runBlocking {
            withTimeout(timeoutMillis) {
                repositories.awaitSuggestionEcho(conversationId, text)
            }
        }
    assertEquals(1, messages.userEchoCount(text))
    checkpoint("revisioned_clear")
    val clear =
        runBlocking {
            withTimeout(timeoutMillis) {
                repositories.awaitSuggestionClear(suggestion)
            }
        }
    assertTrue(clear.revision > suggestion.revision)
    checkpoint("placeholder_removed")
    waitUntil(timeoutMillis) { onAllNodesWithTag(REPLY_SUGGESTION_PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    checkpoint("complete")
}
