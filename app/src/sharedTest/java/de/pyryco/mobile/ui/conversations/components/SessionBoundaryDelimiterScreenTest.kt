package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionBoundaryDelimiterScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val occurredAt = Instant.parse("2026-05-17T14:32:00Z")

    private fun clearBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.Clear,
            occurredAt = occurredAt,
            workspaceCwd = null,
        )

    private fun workspaceChangeBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.WorkspaceChange,
            occurredAt = occurredAt,
            workspaceCwd = "~/Workspace/Projects/KitchenClaw",
        )

    private fun idleEvictBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.IdleEvict,
            occurredAt = occurredAt,
            workspaceCwd = null,
        )

    private fun setContentWithCapturingUriHandler(
        boundary: ThreadItem.SessionBoundary,
        opened: MutableList<String>,
    ) {
        val capturing =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides capturing) {
                    SessionBoundaryDelimiter(boundary = boundary, memorySearch = absent)
                }
            }
        }
    }

    private val absent = MemorySearchReport(MemorySearchAvailability.Absent, emptyList())

    @Test
    fun installed_or_unknown_report_keeps_reset_explanation_without_install() {
        val installed =
            MemorySearchReport(
                MemorySearchAvailability.Unavailable,
                listOf(MemorySearchProvider("p", "Knowledge search", true, false, MemorySearchAvailability.Unavailable)),
            )
        val report = androidx.compose.runtime.mutableStateOf(installed)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = clearBoundary(), memorySearch = report.value)
            }
        }

        composeTestRule.onNodeWithText("New session — ", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Claude doesn't remember messages above this line.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = MemorySearchReport.Unknown }
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = absent }
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun renders_explanatory_sentence_and_install_button_for_Clear() {
        setContentWithCapturingUriHandler(clearBoundary(), mutableListOf())

        composeTestRule
            .onNode(
                hasText(
                    "Claude doesn't remember messages above this line.",
                    substring = true,
                ),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Search stored knowledge with a memory plugin.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun a_Codex_conversation_names_Codex_and_keeps_the_install_button() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = clearBoundary(), agent = ConversationAgent.Codex, memorySearch = absent)
            }
        }

        composeTestRule
            .onNode(
                hasText(
                    "Codex doesn't remember messages above this line.",
                    substring = true,
                ),
            ).assertIsDisplayed()
        composeTestRule.onNode(hasText("Claude doesn't remember", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun renders_Clear_label_prefix() {
        setContentWithCapturingUriHandler(clearBoundary(), mutableListOf())

        composeTestRule
            .onNode(hasText("New session — ", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun renders_WorkspaceChange_label_with_cwd_prefix() {
        setContentWithCapturingUriHandler(workspaceChangeBoundary(), mutableListOf())

        composeTestRule
            .onNode(
                hasText(
                    "Workspace changed to ~/Workspace/Projects/KitchenClaw — ",
                    substring = true,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun renders_IdleEvict_label_prefix() {
        setContentWithCapturingUriHandler(idleEvictBoundary(), mutableListOf())

        composeTestRule
            .onNode(hasText("Idle session ended — ", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun tapping_Install_opens_memory_plugin_docs_url_exactly_once() {
        val opened = mutableListOf<String>()
        setContentWithCapturingUriHandler(clearBoundary(), opened)

        composeTestRule.onNodeWithText("Install").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(MEMORY_PLUGIN_DOCS_URL), opened)
    }
}
