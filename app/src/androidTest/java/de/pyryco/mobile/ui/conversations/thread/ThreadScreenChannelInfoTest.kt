package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadScreenChannelInfoTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun message(id: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.User,
                content = "message $id",
                timestamp = Instant.parse("2026-05-20T10:00:00Z"),
                isStreaming = false,
            ),
        )

    private fun channelInfoState(): ThreadUiState =
        ThreadUiState(
            conversationId = "ch_abc123",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = true,
            channelInfoOpen = true,
            workspacePath = "~/Workspace/Projects/KitchenClaw",
            lastUsedAt = Instant.parse("2026-05-29T10:00:00Z"),
            sessionCount = 3,
            items = listOf(message("m0"), message("m1")),
        )

    private fun setContent(events: MutableList<ThreadEvent>) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = channelInfoState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }
    }

    @Test
    fun sheet_displays_workspace_path_and_counts_when_open() {
        setContent(mutableListOf())

        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("~/Workspace/Projects/KitchenClaw").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total messages").assertIsDisplayed()
        // sessionCount = 3, messageCount = 2 (counts the two MessageItems)
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun tapping_rename_emits_rename_then_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Rename").performClick()

        assertEquals(listOf(ThreadEvent.Rename, ThreadEvent.ChannelInfoDismiss), events)
    }

    @Test
    fun tapping_change_workspace_emits_change_then_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Change workspace").performClick()

        assertEquals(listOf(ThreadEvent.ChangeWorkspace, ThreadEvent.ChannelInfoDismiss), events)
    }

    @Test
    fun tapping_archive_emits_only_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Archive").performClick()

        assertEquals(listOf(ThreadEvent.ChannelInfoDismiss), events)
    }

    @Test
    fun tapping_delete_emits_only_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf(ThreadEvent.ChannelInfoDismiss), events)
    }

    @Test
    fun tapping_close_emits_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription("Close").performClick()

        assertEquals(listOf(ThreadEvent.ChannelInfoDismiss), events)
    }
}
