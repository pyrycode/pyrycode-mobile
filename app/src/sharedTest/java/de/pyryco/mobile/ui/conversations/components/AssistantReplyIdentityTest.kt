package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.legacyThreadEvidence
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantReplyIdentityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun identityFindsTheCompletedReplyAcrossWordingAndFormattingChanges() {
        var message by mutableStateOf(Message("reply", "s", Role.Assistant, "Yes, completed!", Instant.fromEpochMilliseconds(0), false))
        compose.setContent { PyrycodeMobileTheme(darkTheme = true) { MessageBubble(message) } }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { message = message.copy(content = "**Finished.**\n\nThe requested work is complete.") }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { message = message.copy(id = "other") }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("assistant-reply:other", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun userTextAndUnfinishedAssistantTextCannotStandInForACompletedReply() {
        var message by mutableStateOf(Message("reply", "s", Role.User, "Yes, completed!", Instant.fromEpochMilliseconds(0), false))

        fun completedReply() = legacyThreadEvidence("h", "c", this, listOf(ThreadItem.MessageItem(message))).loadedMainReply()
        compose.setContent { PyrycodeMobileTheme(darkTheme = true) { MessageBubble(message) } }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertDoesNotExist()
        assertNull(completedReply())
        compose.runOnIdle { message = message.copy(role = Role.Assistant, isStreaming = true) }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertIsDisplayed()
        assertNull(completedReply())
        compose.runOnIdle { message = message.copy(isStreaming = false) }
        compose.onNodeWithTag("assistant-reply:reply", useUnmergedTree = true).assertIsDisplayed()
        assertNotNull(completedReply())
    }
}
