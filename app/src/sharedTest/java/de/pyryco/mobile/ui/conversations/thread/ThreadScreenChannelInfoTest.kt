package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL
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

    private fun report(
        availability: MemorySearchAvailability,
        installed: Boolean = true,
        enabled: Boolean = true,
    ) = MemorySearchReport(
        availability,
        listOf(MemorySearchProvider("p", "Notebook Search", installed, enabled, availability)),
    )

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

    private fun deleteConfirmState(): ThreadUiState = channelInfoState().copy(channelInfoOpen = false, deleteConfirmVisible = true)

    private fun setContent(
        events: MutableList<ThreadEvent>,
        state: ThreadUiState = channelInfoState(),
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
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
        composeTestRule.onNodeWithText("Folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Workspace").assertDoesNotExist()
        composeTestRule.onNodeWithText("~/Workspace/Projects/KitchenClaw").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total messages").assertIsDisplayed()
        // sessionCount = 3, messageCount = 2 (counts the two MessageItems)
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun channel_info_shows_provider_name_and_effective_status() {
        val state =
            channelInfoState().copy(
                runConfig = ThreadRunConfig(memorySearch = report(MemorySearchAvailability.Available)),
            )
        setContent(mutableListOf(), state)

        composeTestRule.onNodeWithText("Notebook Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Memory search available").assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
    }

    @Test
    fun channel_info_lists_each_provider_with_its_own_state() {
        val providers =
            listOf(
                MemorySearchProvider("a", "Notebook Search", true, true, MemorySearchAvailability.Available),
                MemorySearchProvider("b", "Archive Index", true, true, MemorySearchAvailability.Unavailable),
                MemorySearchProvider("c", "Local Notes", false, false, MemorySearchAvailability.Unknown),
            )
        setContent(
            mutableListOf(),
            channelInfoState().copy(
                runConfig = ThreadRunConfig(memorySearch = MemorySearchReport(MemorySearchAvailability.Unavailable, providers)),
            ),
        )

        composeTestRule.onNodeWithText("Notebook Search").assertExists()
        composeTestRule.onNodeWithText("Memory search available").assertExists()
        composeTestRule.onNodeWithText("Archive Index").assertExists()
        composeTestRule.onNodeWithText("Memory search unavailable").assertExists()
        composeTestRule.onNodeWithText("Local Notes").assertExists()
        composeTestRule.onNodeWithText("Not installed").assertExists()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
    }

    @Test
    fun channel_info_bounds_daemon_supplied_provider_name() {
        val longName = "A".repeat(300)
        val provider = MemorySearchProvider("p", longName, true, true, MemorySearchAvailability.Available)
        setContent(
            mutableListOf(),
            channelInfoState().copy(
                runConfig = ThreadRunConfig(memorySearch = MemorySearchReport(MemorySearchAvailability.Available, listOf(provider))),
            ),
        )

        composeTestRule.onNodeWithText("A".repeat(120)).assertExists()
        composeTestRule.onNodeWithText(longName).assertDoesNotExist()
    }

    @Test
    fun channel_info_distinguishes_disabled_absent_and_unknown_after_conversation_change() {
        val state =
            androidx.compose.runtime.mutableStateOf(
                channelInfoState().copy(
                    runConfig = ThreadRunConfig(memorySearch = report(MemorySearchAvailability.Unavailable, enabled = false)),
                ),
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

        composeTestRule.onNodeWithText("Notebook Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Memory search available").assertDoesNotExist()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()

        composeTestRule.runOnIdle {
            state.value =
                channelInfoState().copy(
                    conversationId = "ch_other",
                    displayName = "Other channel",
                    runConfig = ThreadRunConfig(memorySearch = MemorySearchReport.Unknown),
                )
        }
        composeTestRule.onNode(hasText("Other channel") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeTestRule.onNodeWithText("Status unknown").assertIsDisplayed()
        composeTestRule.onNodeWithText("None").assertDoesNotExist()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()

        composeTestRule.runOnIdle {
            state.value =
                state.value.copy(
                    runConfig = ThreadRunConfig(memorySearch = MemorySearchReport(MemorySearchAvailability.Absent, emptyList())),
                )
        }
        composeTestRule.onNodeWithText("None").assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun channel_info_install_opens_memory_plugin_docs_for_confirmed_absence() {
        val opened = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            channelInfoState().copy(
                                runConfig =
                                    ThreadRunConfig(
                                        memorySearch = MemorySearchReport(MemorySearchAvailability.Absent, emptyList()),
                                    ),
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Install").performClick()
        assertEquals(listOf(MEMORY_PLUGIN_DOCS_URL), opened)
    }

    @Test
    fun actions_section_is_hidden_when_mutations_unsupported() {
        setContent(mutableListOf(), state = channelInfoState().copy(mutationsSupported = false))

        // The whole Actions section is gated out in relay mode (AC#2). The
        // heading lookup is scoped to the sheet because the composer footer
        // draws its own "Actions" button (#884); the "About" lookup proves the
        // scope matches the sheet, so the absence check cannot pass vacuously.
        composeTestRule.onNode(hasText("About") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeTestRule.onNode(hasText("Actions") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        composeTestRule.onNodeWithText("Rename").assertDoesNotExist()
        composeTestRule.onNodeWithText("Change workspace").assertDoesNotExist()
        composeTestRule.onNodeWithText("Archive").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()

        // The sheet stays viewable as read-only info (AC#2).
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("~/Workspace/Projects/KitchenClaw").assertIsDisplayed()
    }

    @Test
    fun tapping_rename_emits_rename_then_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Rename").performClick()

        assertEquals(listOf(ThreadEvent.Rename, ThreadEvent.ChannelInfoDismiss), events)
    }

    @Test
    fun channelInfoOmitsChangeWorkspaceAction() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Change workspace").assertDoesNotExist()
        assertEquals(emptyList<ThreadEvent>(), events)
    }

    @Test
    fun tapping_archive_emits_archive() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithText("Archive").performClick()

        assertEquals(listOf(ThreadEvent.Archive), events)
    }

    @Test
    fun tapping_delete_emits_delete() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        // The Session section (#1346) sits above Actions, so Delete starts below the 320dp-wide viewport.
        composeTestRule.onNodeWithText("Delete").performScrollTo().performClick()

        assertEquals(listOf(ThreadEvent.Delete), events)
    }

    @Test
    fun delete_confirm_dialog_displays_title_and_body() {
        setContent(mutableListOf(), state = deleteConfirmState())

        composeTestRule.onNodeWithText("Delete conversation?").assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText("This permanently deletes \"Test channel\" and all its sessions and messages. This can’t be undone.") and
                    hasAnyAncestor(isDialog()),
            ).assertIsDisplayed()
    }

    @Test
    fun tapping_dialog_delete_emits_delete_confirm() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events, state = deleteConfirmState())

        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf(ThreadEvent.DeleteConfirm), events)
    }

    @Test
    fun tapping_dialog_cancel_emits_delete_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events, state = deleteConfirmState())

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(ThreadEvent.DeleteDismiss), events)
    }

    @Test
    fun tapping_close_emits_dismiss() {
        val events = mutableListOf<ThreadEvent>()
        setContent(events)

        composeTestRule.onNodeWithContentDescription("Close").performClick()

        assertEquals(listOf(ThreadEvent.ChannelInfoDismiss), events)
    }
}
