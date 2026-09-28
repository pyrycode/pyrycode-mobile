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
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.Instant
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

    @Test
    fun boundary_and_overflow_follow_report_and_conversation_change() {
        val available =
            MemorySearchReport(
                MemorySearchAvailability.Available,
                listOf(MemorySearchProvider("p", "Search", true, true, MemorySearchAvailability.Available)),
            )
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s0",
                newSessionId = "s1",
                reason = BoundaryReason.Clear,
                occurredAt = Instant.parse("2026-05-20T10:00:00Z"),
            )
        val state =
            androidx.compose.runtime.mutableStateOf(
                baseState().copy(hasMessages = true, items = listOf(boundary), runConfig = ThreadRunConfig(memorySearch = available)),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state.value,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Claude doesn't remember messages above this line.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        val installItem = string(R.string.thread_overflow_install_memory_plugin)
        composeTestRule.onNodeWithText(installItem).assertDoesNotExist()

        composeTestRule.runOnIdle {
            state.value =
                state.value.copy(
                    runConfig = ThreadRunConfig(memorySearch = MemorySearchReport(MemorySearchAvailability.Absent, emptyList())),
                )
        }
        composeTestRule.onNodeWithText(installItem).assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value = state.value.copy(conversationId = "c2", runConfig = ThreadRunConfig())
        }
        composeTestRule.onNodeWithText(installItem).assertDoesNotExist()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
    }

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
    fun tapping_overflow_icon_renders_all_six_menu_items() {
        setContent(mutableListOf())

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()

        composeTestRule.onNodeWithText("Reset session").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
    }

    @Test
    fun tapping_new_session_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText("Reset session").performClick()

        assertEquals(listOf(ThreadEvent.NewSession), events)
        composeTestRule.onNodeWithText("Reset session").assertDoesNotExist()
    }

    @Test
    fun resetFailure_showsFixedMessageAndRetainsThread() {
        val errors = Channel<Unit>(Channel.BUFFERED)
        val state =
            baseState().copy(
                hasMessages = true,
                items =
                    listOf(
                        ThreadItem.MessageItem(
                            Message("m1", "s1", Role.User, "Retained message", Instant.parse("2026-09-20T10:00:00Z"), false),
                        ),
                    ),
            )
        val errorFlow = errors.receiveAsFlow()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    newSessionErrors = errorFlow,
                )
            }
        }
        composeTestRule.onNodeWithText("Retained message").assertIsDisplayed()
        composeTestRule.runOnIdle { errors.trySend(Unit) }
        composeTestRule.onNodeWithText("Couldn't reset the session. Check your connection.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retained message").assertIsDisplayed()
        errors.close()
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
