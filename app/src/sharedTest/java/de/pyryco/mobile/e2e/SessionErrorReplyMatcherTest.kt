package de.pyryco.mobile.e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun sessionErrorRecoveryPrompt(marker: String): String =
    "Without using tools, reply with only the single word recovered1731 and no punctuation. $marker"

internal fun sessionErrorReplyMatcher(): SemanticsMatcher =
    hasText("recovered1731") and
        hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and hasClickAction())

@RunWith(AndroidJUnit4::class)
class SessionErrorReplyMatcherTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sentPromptsAloneCannotProveAssistantRendering_thenReplyDoes() {
        val showReply = mutableStateOf(false)
        val prompts = listOf("held", "fresh").map { sessionErrorRecoveryPrompt("marker=$it-1731") }
        compose.setContent {
            PyrycodeMobileTheme {
                Column {
                    prompts.forEachIndexed { index, prompt ->
                        MessageBubble(message("user-$index", Role.User, prompt), onToggleMetaRow = {})
                    }
                    if (showReply.value) {
                        MessageBubble(message("assistant", Role.Assistant, "recovered1731"), onToggleMetaRow = {})
                    }
                }
            }
        }
        prompts.forEach { compose.onNode(hasText(it), useUnmergedTree = true).assertIsDisplayed() }
        val reply = sessionErrorReplyMatcher()
        compose.onAllNodes(reply, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { showReply.value = true }
        compose.onAllNodes(reply, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(reply, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun message(
        id: String,
        role: Role,
        content: String,
    ) = Message(
        id = id,
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = Instant.parse("2026-10-07T00:00:00Z"),
        isStreaming = false,
    )
}
