package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The retired item's label (#883). A literal, because its string resource was removed with it.
private const val RETIRED_LITERAL_SCREEN_ITEM = "Show the literal screen"

@RunWith(AndroidJUnit4::class)
class ThreadOverflowMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val absent = MemorySearchReport(MemorySearchAvailability.Absent, emptyList())

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private class RecordingUriHandler : UriHandler {
        val openedUris = mutableListOf<String>()

        override fun openUri(uri: String) {
            openedUris += uri
        }
    }

    @Test
    fun channel_menu_items_render_in_documented_order_when_expanded() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = true,
                    memorySearch = absent,
                    onDismiss = {},
                    onEvent = {},
                )
            }
        }

        // #883: the literal-screen view is retired — absent here (promoted) and in the discussion test.
        composeTestRule.onNodeWithText(RETIRED_LITERAL_SCREEN_ITEM).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_install_memory_plugin)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.save_as_channel_action)).assertDoesNotExist()
    }

    @Test
    fun discussion_menu_items_render_in_documented_order_when_expanded() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = false,
                    onDismiss = {},
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(RETIRED_LITERAL_SCREEN_ITEM).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.save_as_channel_action)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_install_memory_plugin)).assertDoesNotExist()
    }

    @Test
    fun mutation_actions_are_hidden_when_mutations_unsupported() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = true,
                    memorySearch = absent,
                    mutationsSupported = false,
                    onDismiss = {},
                    onEvent = {},
                )
            }
        }

        // Relay mode can't perform these mutations, so they must be non-invocable (AC#1).
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_new_session)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_archive)).assertDoesNotExist()

        // The non-mutating entries survive the gate (AC#1).
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_install_memory_plugin)).assertIsDisplayed()
    }

    @Test
    fun tapping_new_session_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = true,
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
                    isPromoted = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_rename)).performClick()

        assertEquals(listOf("dismiss", "event:Rename"), log)
    }

    @Test
    fun tapping_archive_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = true,
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
                    isPromoted = true,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).performClick()

        assertEquals(listOf("dismiss", "event:ChannelInfo"), log)
    }

    @Test
    fun tapping_save_as_channel_dismisses_then_dispatches_event() {
        val log = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = false,
                    onDismiss = { log.add("dismiss") },
                    onEvent = { log.add("event:$it") },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.save_as_channel_action)).performClick()

        assertEquals(listOf("dismiss", "event:SaveAsChannel"), log)
    }

    @Test
    fun tapping_install_memory_plugin_dismisses_and_opens_docs_url() {
        val log = mutableListOf<String>()
        val fakeHandler = RecordingUriHandler()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides fakeHandler) {
                    ThreadOverflowMenu(
                        expanded = true,
                        isPromoted = true,
                        memorySearch = absent,
                        onDismiss = { log.add("dismiss") },
                        onEvent = { log.add("event:$it") },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(string(R.string.thread_overflow_install_memory_plugin)).performClick()

        assertEquals(listOf("dismiss"), log)
        assertEquals(listOf(MEMORY_PLUGIN_DOCS_URL), fakeHandler.openedUris)
    }

    @Test
    fun channel_install_item_tracks_current_report() {
        val available =
            MemorySearchReport(
                MemorySearchAvailability.Available,
                listOf(MemorySearchProvider("p", "Search", true, true, MemorySearchAvailability.Available)),
            )
        val report = androidx.compose.runtime.mutableStateOf(available)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadOverflowMenu(
                    expanded = true,
                    isPromoted = true,
                    memorySearch = report.value,
                    onDismiss = {},
                    onEvent = {},
                )
            }
        }

        val install = string(R.string.thread_overflow_install_memory_plugin)
        composeTestRule.onNodeWithText(install).assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = MemorySearchReport.Unknown }
        composeTestRule.onNodeWithText(install).assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = absent }
        composeTestRule.onNodeWithText(install).assertIsDisplayed()
    }
}
