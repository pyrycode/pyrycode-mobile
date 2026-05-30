package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class DiscussionPreviewRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // 2h ago sits mid-bucket ("Nh ago"), so the relative time is identical whether
    // computed by the composable or by the test's expected-label builder.
    private val lastUsedAt = Clock.System.now() - 2.hours

    private fun discussion(name: String): Conversation =
        Conversation(
            id = "d1",
            name = name,
            cwd = "/tmp",
            currentSessionId = "d1-s",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = lastUsedAt,
        )

    private fun message(content: String): Message =
        Message(
            id = "m1",
            sessionId = "d1-s",
            role = Role.Assistant,
            content = content,
            timestamp = lastUsedAt,
            isStreaming = false,
        )

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    @Test
    fun discussionWithMessage_exposesSingleMergedLabelOfNameAndTime() {
        val name = "Help me debug auth flow"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionPreviewRow(
                    conversation = discussion(name),
                    lastMessage = message("The token refresh is happening…"),
                    onClick = {},
                )
            }
        }

        val expected = string(R.string.cd_discussion_preview_row, name, formatRelativeTime(lastUsedAt))
        composeTestRule.onAllNodes(hasContentDescription(expected)).assertCountEquals(1)
    }

    @Test
    fun discussionWithoutMessage_stillExposesNameAndTimeLabel() {
        val name = "Quick regex for log parsing"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionPreviewRow(
                    conversation = discussion(name),
                    lastMessage = null,
                    onClick = {},
                )
            }
        }

        val expected = string(R.string.cd_discussion_preview_row, name, formatRelativeTime(lastUsedAt))
        composeTestRule.onAllNodes(hasContentDescription(expected)).assertCountEquals(1)
    }

    @Test
    fun discussionRow_meetsMinimumTouchTargetHeight() {
        val name = "Help me debug auth flow"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionPreviewRow(
                    conversation = discussion(name),
                    lastMessage = null,
                    onClick = {},
                )
            }
        }

        val expected = string(R.string.cd_discussion_preview_row, name, formatRelativeTime(lastUsedAt))
        composeTestRule.onNode(hasContentDescription(expected)).assertHeightIsAtLeast(48.dp)
    }
}
