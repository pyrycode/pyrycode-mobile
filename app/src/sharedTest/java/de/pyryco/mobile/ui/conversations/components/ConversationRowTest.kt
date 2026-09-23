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
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class ConversationRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // 3h ago sits mid-bucket ("Nh ago"), so the relative time is identical whether
    // computed by the composable or by the test's expected-label builder.
    private val lastUsedAt = Clock.System.now() - 3.hours

    private fun channel(
        name: String,
        isSleeping: Boolean = false,
    ): Conversation =
        Conversation(
            id = "c1",
            name = name,
            cwd = "/tmp",
            currentSessionId = "c1-s",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = lastUsedAt,
            isSleeping = isSleeping,
        )

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    @Test
    fun activeChannel_exposesSingleMergedLabelOfNameAndTime() {
        val name = "leaky-faucet"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ConversationRow(conversation = channel(name), onClick = {})
            }
        }

        val expected = string(R.string.cd_conversation_row, name, formatRelativeTime(lastUsedAt))
        composeTestRule.onAllNodes(hasContentDescription(expected)).assertCountEquals(1)
    }

    @Test
    fun idleChannel_announcesIdleStateInMergedLabel() {
        val name = "leaky-faucet"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ConversationRow(conversation = channel(name, isSleeping = true), onClick = {})
            }
        }

        val time = formatRelativeTime(lastUsedAt)
        val idleLabel = string(R.string.cd_conversation_row_idle, name, time)
        val activeLabel = string(R.string.cd_conversation_row, name, time)
        composeTestRule.onAllNodes(hasContentDescription(idleLabel)).assertCountEquals(1)
        composeTestRule.onAllNodes(hasContentDescription(activeLabel)).assertCountEquals(0)
    }

    @Test
    fun channelRow_meetsMinimumTouchTargetHeight() {
        val name = "leaky-faucet"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ConversationRow(conversation = channel(name), onClick = {})
            }
        }

        val expected = string(R.string.cd_conversation_row, name, formatRelativeTime(lastUsedAt))
        composeTestRule.onNode(hasContentDescription(expected)).assertHeightIsAtLeast(48.dp)
    }
}
