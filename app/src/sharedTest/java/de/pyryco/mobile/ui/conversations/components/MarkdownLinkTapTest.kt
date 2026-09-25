package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1050: a tapped link to a workspace markdown note in an assistant reply hands its path to the thread,
 * streaming or finished, and every other link and every other caller keeps today's handling.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkTapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val paths = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private val uriHandler =
        object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

    private fun show(content: @Composable () -> Unit) =
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Surface { Column { content() } }
                }
            }
        }

    private fun message(
        role: Role,
        content: String,
        isStreaming: Boolean = false,
    ) = Message("m-${role.name}", "s1", role, content, Instant.parse("2026-09-25T12:00:00Z"), isStreaming)

    /** Tap the first link in the node showing [text], waiting until it is one: a streaming reply reveals its source gradually. */
    private fun tapLink(text: String) {
        composeTestRule.waitUntil(TIMEOUT_MS) {
            runCatching { composeTestRule.onNodeWithText(text, substring = true).performFirstLinkClick() }.isSuccess
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun aFinishedReply_handsTheNotesPathToTheThread_andNothingToAnotherApp() {
        show { MessageBubble(message(Role.Assistant, "See [Plan](notes/Plan.md:12#top) now."), onOpenMarkdownLink = { paths += it }) }

        tapLink("Plan")

        assertEquals(listOf("notes/Plan.md"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aStreamingReply_handsTheNotesPathToTheThread() {
        show { MessageBubble(message(Role.Assistant, "[Plan](Plan.MARKDOWN)", isStreaming = true), onOpenMarkdownLink = { paths += it }) }

        tapLink("Plan")

        assertEquals(listOf("Plan.MARKDOWN"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aWebLinkInAReply_stillOpensThroughTheUriHandler() {
        show { MessageBubble(message(Role.Assistant, "[site](https://example.com/a.md)"), onOpenMarkdownLink = { paths += it }) }

        tapLink("site")

        assertEquals(emptyList<String>(), paths)
        assertEquals(listOf("https://example.com/a.md"), opened)
    }

    @Test
    fun withoutTheOptIn_aNotesLinkStaysInert() {
        show { MarkdownText("[Plan](notes/Plan.md)") }

        tapLink("Plan")

        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun theOperatorsOwnMessage_isNotALink() {
        show { MessageBubble(message(Role.User, "[Plan](notes/Plan.md)"), onOpenMarkdownLink = { paths += it }) }

        composeTestRule.onNodeWithText("[Plan](notes/Plan.md)").performClick()

        assertEquals(emptyList<String>(), paths)
        assertEquals(emptyList<String>(), opened)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
