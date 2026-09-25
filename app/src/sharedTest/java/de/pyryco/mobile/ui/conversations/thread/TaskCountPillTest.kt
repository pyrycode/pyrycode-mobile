package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1043: the status band's task count pill, hosted on [ThreadScreen]. The count is
 * [ThreadUiState.backgroundTaskCount]; the pill opens the same panel the Actions menu's row does.
 * Native graphics, so the pill's label measures its real width and the band its real height.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskCountPillTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var count by mutableIntStateOf(0)

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

    private fun setThread(
        initialCount: Int,
        isThinking: Boolean = false,
        withMessage: Boolean = false,
    ) {
        count = initialCount
        val roster =
            BackgroundTaskRoster(
                listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)),
                0,
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Test channel",
                            isPromoted = true,
                            hasMessages = withMessage,
                            items = if (withMessage) listOf(newestMessage) else emptyList(),
                            backgroundTasks = roster,
                            backgroundTaskCount = count,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = isThinking,
                )
            }
        }
    }

    private fun pill(text: String) = composeTestRule.onNodeWithText(text)

    private val newestMessage =
        ThreadItem.MessageItem(
            Message(
                id = "m1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "newest message",
                timestamp = Instant.parse("2026-05-20T10:00:00Z"),
                isStreaming = false,
            ),
        )

    // The list is laid out from its newest end, so the newest message follows the message area's bottom,
    // which is the composer's top: the band's height moves it.
    private fun messageBottom() =
        composeTestRule
            .onNodeWithText("newest message")
            .getUnclippedBoundsInRoot()
            .bottom

    @Test
    fun pill_showsBesideALiveReading_atTheRightOfIt() {
        setThread(initialCount = 2, isThinking = true)

        val thinking = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_thinking))
        thinking.assertIsDisplayed()
        pill("2 tasks running").assertIsDisplayed()
        assertTrue(
            pill("2 tasks running").getUnclippedBoundsInRoot().left >= thinking.getUnclippedBoundsInRoot().right,
        )
    }

    @Test
    fun pill_showsAlone_atTheBandsRightEnd() {
        setThread(initialCount = 3)

        pill("3 tasks running").assertIsDisplayed()
        val rootRight = composeTestRule.onRoot().getUnclippedBoundsInRoot().right
        assertEquals(rootRight - ComposerGutter, pill("3 tasks running").getUnclippedBoundsInRoot().right)
    }

    @Test
    fun oneTask_readsSingular() {
        setThread(initialCount = 1)

        pill("1 task running").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 tasks running").assertDoesNotExist()
    }

    @Test
    fun zeroCount_showsNoPill_andTheBandCollapsesAsBefore() {
        setThread(initialCount = 0, withMessage = true)
        val collapsedBottom = messageBottom()
        composeTestRule.onNodeWithText("running", substring = true).assertDoesNotExist()

        count = 2
        composeTestRule.waitForIdle()
        pill("2 tasks running").assertIsDisplayed()
        // The pill alone raises the band by its own 24dp and the column's 8dp gap.
        assertEquals(collapsedBottom - 32.dp, messageBottom())

        count = 0
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("running", substring = true).assertDoesNotExist()
        assertEquals(collapsedBottom, messageBottom())
    }

    @Test
    fun tappingThePill_opensTheBackgroundTaskPanel() {
        setThread(initialCount = 1)
        composeTestRule.onNodeWithText("sleep 300").assertDoesNotExist()

        pill("1 task running").performClick()

        composeTestRule.onNodeWithText("Background tasks").assertIsDisplayed()
        composeTestRule.onNodeWithText("sleep 300").assertIsDisplayed()
    }
}
