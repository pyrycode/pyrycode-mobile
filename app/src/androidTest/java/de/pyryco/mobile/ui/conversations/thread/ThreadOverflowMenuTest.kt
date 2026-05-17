package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadOverflowMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun menu_items_render_in_documented_order_when_expanded() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = {},
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
    }

    @Test
    fun tapping_new_session_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).performClick()

        assertEquals(listOf("dismiss", "event:NewSession"), log)
    }

    @Test
    fun tapping_rename_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).performClick()

        assertEquals(listOf("dismiss", "event:Rename"), log)
    }

    @Test
    fun tapping_change_workspace_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).performClick()

        assertEquals(listOf("dismiss", "event:ChangeWorkspace"), log)
    }

    @Test
    fun tapping_archive_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).performClick()

        assertEquals(listOf("dismiss", "event:Archive"), log)
    }

    @Test
    fun tapping_channel_info_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).performClick()

        assertEquals(listOf("dismiss", "event:ChannelInfo"), log)
    }
}
