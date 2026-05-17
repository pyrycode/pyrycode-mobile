package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadScreenOverflowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun baseState(): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = false,
        )

    private fun setContent(events: MutableList<ThreadEvent>) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
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
    fun tapping_overflow_icon_renders_all_five_menu_items() {
        setContent(mutableListOf())

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
    }

    @Test
    fun tapping_new_session_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).performClick()

        assertEquals(listOf(ThreadEvent.NewSession), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertDoesNotExist()
    }

    @Test
    fun tapping_rename_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).performClick()

        assertEquals(listOf(ThreadEvent.Rename), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertDoesNotExist()
    }

    @Test
    fun tapping_change_workspace_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).performClick()

        assertEquals(listOf(ThreadEvent.ChangeWorkspace), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
    }

    @Test
    fun tapping_archive_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).performClick()

        assertEquals(listOf(ThreadEvent.Archive), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertDoesNotExist()
    }

    @Test
    fun tapping_channel_info_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).performClick()

        assertEquals(listOf(ThreadEvent.ChannelInfo), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertDoesNotExist()
    }

    @Test
    fun back_press_dismisses_open_menu_without_firing_event() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        assertTrue("No events expected after dismiss, got $events", events.isEmpty())
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertDoesNotExist()
    }
}
