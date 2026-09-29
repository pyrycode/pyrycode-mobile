package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.thread.LinkedMarkdown
import de.pyryco.mobile.ui.conversations.thread.LinkedMarkdownReaderDestination
import de.pyryco.mobile.ui.conversations.thread.MarkdownDocument
import de.pyryco.mobile.ui.conversations.thread.MarkdownReaderScreen
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MarkdownTypographyTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun reader_uses_large_body_and_recursively_spaced_blocks() {
        rule.setContent {
            PyrycodeMobileTheme {
                MarkdownReaderScreen(MarkdownDocument("Typography.md", fixture), onBack = {})
            }
        }
        assertBody(16, 24, 12f, 6f)
        assertSpecialStyles()
    }

    @Test
    fun linked_note_uses_the_same_reader_style() {
        rule.setContent {
            PyrycodeMobileTheme {
                LinkedMarkdownReaderDestination(
                    note = LinkedMarkdown("Typography.md", MarkdownDocument("Typography.md", fixture)),
                    reread = { null },
                    onBack = {},
                )
            }
        }
        assertBody(16, 24, 12f, 6f)
    }

    @Test
    fun default_renderer_keeps_message_typography_and_spacing() {
        rule.setContent { PyrycodeMobileTheme { MarkdownText(fixture) } }
        assertBody(14, 20, 12f, 4f, taskGap = 6f)
        assertSpecialStyles(codeSize = 12)
    }

    @Test
    fun finished_reply_keeps_message_typography_and_spacing() {
        showMessage(streaming = false)
        assertBody(14, 20, 12f, 4f, taskGap = 6f)
    }

    @Test
    fun streaming_reply_keeps_message_typography_and_spacing() {
        showMessage(streaming = true)
        rule.mainClock.advanceTimeBy(10_000)
        assertBody(14, 20, 12f, 4f, taskGap = 6f)
    }

    private fun showMessage(streaming: Boolean) {
        rule.setContent {
            PyrycodeMobileTheme {
                MessageBubble(
                    Message("typography", "session", Role.Assistant, fixture, Instant.fromEpochSeconds(0), streaming),
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertBody(
        size: Int,
        lineHeight: Int,
        blockGap: Float,
        itemGap: Float,
        taskGap: Float = itemGap,
    ) {
        val bodyTexts =
            listOf(
                "Paragraph one",
                "Paragraph two",
                "Parent",
                "Child one",
                "Child two",
                "Sibling",
                "Ordered one",
                "Ordered two",
                "Task one",
                "Task two",
                "Quote one",
                "Quote two",
                "Inner quote one",
                "Inner quote two",
                "Quote item one",
                "Quote item two",
            )
        for (text in bodyTexts) {
            val style =
                rule
                    .onNodeWithText(text, useUnmergedTree = true)
                    .layout()
                    .layoutInput.style
            assertEquals(text, size.sp, style.fontSize)
            assertEquals(text, lineHeight.sp, style.lineHeight)
        }
        for (marker in listOf("•", "1.", "2.")) {
            val nodes = rule.onAllNodesWithText(marker, useUnmergedTree = true)
            nodes.assertCountEquals(if (marker == "•") 6 else 1)
            for (index in nodes.fetchSemanticsNodes().indices) {
                val style = nodes[index].layout().layoutInput.style
                assertEquals(marker, size.sp, style.fontSize)
                assertEquals(marker, lineHeight.sp, style.lineHeight)
            }
        }
        for (quote in listOf("Quote one", "Quote two", "Inner quote one", "Inner quote two")) {
            assertEquals(
                FontStyle.Italic,
                rule
                    .onNodeWithText(quote, useUnmergedTree = true)
                    .layout()
                    .layoutInput.style.fontStyle,
            )
        }
        assertGap("Paragraph one", "Paragraph two", blockGap)
        assertGap("Paragraph two", "Parent", blockGap)
        assertGap("Parent", "Child one", blockGap)
        assertGap("Child one", "Child two", itemGap)
        assertGap("Child two", "Sibling", itemGap)
        assertGap("Ordered one", "Ordered two", itemGap)
        // The task mark is taller than the message text's measured line box.
        assertGap("Task one", "Task two", taskGap)
        assertGap("Quote one", "Quote two", blockGap)
        assertGap("Quote two", "Inner quote one", blockGap)
        assertGap("Inner quote one", "Inner quote two", blockGap)
        assertGap("Inner quote two", "Quote item one", blockGap)
        assertGap("Quote item one", "Quote item two", itemGap)
    }

    private fun assertGap(
        before: String,
        after: String,
        expected: Float,
    ) {
        val first = rule.onNodeWithText(before, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second = rule.onNodeWithText(after, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("$before -> $after", expected, (second.top - first.bottom).value, 0.6f)
    }

    private fun assertSpecialStyles(codeSize: Int = 14) {
        for ((text, size, height) in listOf(Triple("Heading", 24, 32), Triple("Cell", 14, 20), Triple("val x = 1", codeSize, 20))) {
            val style =
                rule
                    .onNodeWithText(text, useUnmergedTree = true)
                    .layout()
                    .layoutInput.style
            assertEquals(text, size.sp, style.fontSize)
            assertEquals(text, height.sp, style.lineHeight)
        }
    }

    private val fixture =
        """
        Paragraph one

        Paragraph two

        - Parent
          - Child one
          - Child two
        - Sibling

        1. Ordered one
        2. Ordered two

        - [ ] Task one
        - [x] Task two

        > Quote one
        >
        > Quote two
        >
        > > Inner quote one
        > >
        > > Inner quote two
        >
        > - Quote item one
        > - Quote item two

        # Heading

        | Header |
        | --- |
        | Cell |

        ```kotlin
        val x = 1
        ```

        End
        """.trimIndent()
}
