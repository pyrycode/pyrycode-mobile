package de.pyryco.mobile.e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownNoteLinkTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun thePromptContainingTheRequestedLinkDoesNotCaptureTheAssistantLinkTap() {
        val paths = mutableListOf<String>()
        val linkText = "e2e1050-open-fixture"
        val fileName = "e2e1050-note-fixture.md"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Surface {
                    Column {
                        MessageBubble(
                            message(
                                Role.User,
                                notePrompt(
                                    "pyrycode-mobile-note-fixture-first",
                                    fileName,
                                    "reply with exactly this markdown link and nothing else: [$linkText]($fileName)",
                                ),
                            ),
                            onOpenMarkdownLink = paths::add,
                        )
                        MessageBubble(message(Role.Assistant, "[$linkText]($fileName)"), onOpenMarkdownLink = paths::add)
                    }
                }
            }
        }
        val link = markdownNoteLinkMatcher(linkText)
        composeTestRule.onAllNodes(link, useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodes(link, useUnmergedTree = true).onFirst().performFirstLinkClick()
        composeTestRule.onAllNodes(link, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(listOf(fileName), paths)
    }

    @Test
    fun reopeningFindsTheOriginalRenderedLinkAfterTheRewriteReply() {
        val paths = mutableListOf<String>()
        val label = "open-note"
        val name = "note.md"
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Surface {
                    Column {
                        MessageBubble(message(Role.User, "Reply with [$label]($name)"))
                        MessageBubble(message(Role.Assistant, "Here is [$label]($name)."), onOpenMarkdownLink = paths::add)
                        MessageBubble(message(Role.User, notePrompt("second", name, "reply with a single short word")))
                        MessageBubble(message(Role.Assistant, "Done."))
                    }
                }
            }
        }
        val link = markdownNoteLinkMatcher(label)
        composeTestRule.onAllNodes(link, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodes(link, useUnmergedTree = true).onFirst().performFirstLinkClick()
        assertEquals(listOf(name), paths)
    }

    @Test
    fun theRequestedLabelWithoutItsOwnRenderedLinkIsNotReady() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Surface {
                    Column {
                        MessageBubble(message(Role.User, "[open-note](note.md)"))
                        MessageBubble(message(Role.Assistant, "[open-note]("))
                        MessageBubble(message(Role.Assistant, "open-note [other](note.md)"))
                    }
                }
            }
        }
        composeTestRule.onAllNodes(markdownNoteLinkMatcher("open-note"), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun message(
        role: Role,
        content: String,
    ) = Message("fixture-${role.name}", "fixture-session", role, content, Instant.parse("2026-10-12T00:00:00Z"), isStreaming = false)
}
