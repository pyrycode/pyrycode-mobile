package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.ChannelEditorState
import de.pyryco.mobile.ui.conversations.list.ChannelPromptReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.semantics.Role as SemanticsRole

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

        composeTestRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG).assertIsDisplayed()
        composeTestRule.assertNoSessionBoundaryExplanation()
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
        // #1578: the overflow item is the offer; the boundary draws no Install of its own.
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()

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

        val reset = composeTestRule.onNodeWithText("Reset session")
        reset.assertIsDisplayed()
        assertEquals(SemanticsRole.Button, reset.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTrue(!reset.fetchSemanticsNode().config.contains(SemanticsProperties.Selected))
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_edit)).assertIsDisplayed()
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
    fun outsideTap_consumesBackControl_andPreservesComposerFocus() {
        var backs = 0
        val events = mutableListOf<ThreadEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = { backs++ },
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }
        val field = composeTestRule.onNode(hasSetTextAction())
        field.performTouchInput { click() }
        field.assertIsFocused()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performTouchInput { click() }
        field.assertIsFocused()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_back)).performTouchInput { click() }
        composeTestRule.onNodeWithText("Reset session").assertDoesNotExist()
        field.assertIsFocused()
        assertEquals(0, backs)
        assertTrue(events.isEmpty())
    }

    @Test
    fun headerMenu_tracksLiveWindowAnchor_andLayerOrigin() {
        val top = mutableStateOf(10.dp)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Box(Modifier.padding(top = top.value)) {
                    ThreadScreen(
                        state = baseState(),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }
        val button = composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions))
        button.performClick()
        for (offset in listOf(10.dp, 40.dp)) {
            composeTestRule.runOnIdle { top.value = offset }
            val anchor = button.getUnclippedBoundsInRoot()
            val row = composeTestRule.onNodeWithText("Reset session").getUnclippedBoundsInRoot()
            // The column begins at +4dp; its first row follows the shared 2dp vertical inset.
            assertEquals(anchor.bottom.value + 6f, row.top.value, 1f)
            assertTrue(
                row.right.value <= composeTestRule
                    .onNodeWithContentDescription(
                        string(R.string.cd_options_overlay_dismiss),
                    ).getUnclippedBoundsInRoot()
                    .right.value - 8f + 1f,
            )
        }
    }

    @Test
    fun headerAndFooterMenus_doNotStack() {
        setContent(mutableListOf())
        composeTestRule.onNodeWithText("Actions").performClick()
        composeTestRule.onNodeWithText("Compact session").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText("Compact session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reset session").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText("Reset session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Actions").performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_edit)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Compact session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Actions").performClick()
        composeTestRule.onNodeWithText("Compact session").assertIsDisplayed()
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
    fun tapping_edit_fires_event_and_closes_menu() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_edit)).performClick()

        assertEquals(listOf(ThreadEvent.EditChannel), events)
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_edit)).assertDoesNotExist()
    }

    @Test
    fun edit_channel_modal_shows_while_open_and_reports_ok_and_cancel() {
        val events = mutableListOf<ThreadEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        baseState().copy(
                            channelEditor =
                                ChannelEditorState(
                                    serverId = "host-a",
                                    conversationId = "c1",
                                    savedName = "Personal",
                                    prompt = ChannelPromptReading.Read("stored", SessionPromptStatus.NoSession),
                                    savedMuted = true,
                                ),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.edit_channel_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(ThreadEvent.ChannelEditSubmit("Personal", "stored", muted = true), ThreadEvent.ChannelEditDismiss), events)
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
