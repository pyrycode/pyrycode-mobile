package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The shared heading contract through the actual attachment and linked-note destinations. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MarkdownReaderHeadingTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val files = TemporaryFolder()

    private var referenceLineHeights = emptyList<Int>()

    @Test fun lightAttachmentHeadings() = assertReader(linked = false, dark = false, fontScale = 1f)

    @Test fun darkAttachmentHeadings() = assertReader(linked = false, dark = true, fontScale = 1f)

    @Test fun lightLinkedHeadings() = assertReader(linked = true, dark = false, fontScale = 1f)

    @Test fun darkLinkedHeadings() = assertReader(linked = true, dark = true, fontScale = 1f)

    @Test fun lightAttachmentHeadingsAtLargeText() = assertReader(linked = false, dark = false, fontScale = 1.5f)

    @Test fun darkAttachmentHeadingsAtLargeText() = assertReader(linked = false, dark = true, fontScale = 1.5f)

    @Test fun lightLinkedHeadingsAtLargeText() = assertReader(linked = true, dark = false, fontScale = 1.5f)

    @Test fun darkLinkedHeadingsAtLargeText() = assertReader(linked = true, dark = true, fontScale = 1.5f)

    private fun assertReader(
        linked: Boolean,
        dark: Boolean,
        fontScale: Float,
    ) {
        val source =
            "# Page title\n\n" +
                (1..6).joinToString("\n\n") { level ->
                    val marker = "#".repeat(level)
                    "Before $level\n\n$marker Heading $level\n\n$marker ${wrappedHeading(level)}\n\nAfter $level"
                }
        val file = files.newFile("Headings.md").apply { writeText(source) }
        val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override suspend fun retrieveAttachment(
                    conversationId: String,
                    attachmentId: String,
                ): AttachmentRetrievalResult {
                    assertEquals("heading-conversation", conversationId)
                    assertEquals("heading-attachment", attachmentId)
                    return AttachmentRetrievalResult.Retrieved(file, "Headings.md", "text/markdown")
                }
            }
        val note = LinkedMarkdown("notes/Headings.md", MarkdownDocument("Headings.md", source))
        var headingColor = Color.Unspecified
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark, dynamicColor = false) {
                headingColor = MaterialTheme.colorScheme.onSecondaryContainer
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val measurer = rememberTextMeasurer()
                    referenceLineHeights = (1..6).map { level ->
                        measurer.measure("Ag", style = expectedStyle(level)).size.height
                    }
                    if (linked) {
                        LinkedMarkdownReaderDestination(note, reread = { null }, onBack = {})
                    } else {
                        MarkdownReaderDestination(repository, "heading-conversation", "heading-attachment", onBack = {})
                    }
                }
            }
        }
        // The attachment destination reads the kept file on IO, outside Compose's main clock.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Page title").fetchSemanticsNodes().isNotEmpty() }
        val bar = rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithText("Page title").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertDpEquals(28.dp, first.top - bar.bottom, "first heading below measured bar")
        assertHeading("Page title", 1, fontScale, headingColor, wrapped = false)
        assertGap("Page title", "Before 1", 12)

        for (level in 1..6) {
            val wrapped = wrappedHeading(level)
            rule.onNodeWithText(wrapped).performScrollTo().assertIsDisplayed()
            assertHeading("Heading $level", level, fontScale, headingColor, wrapped = false)
            assertHeading(wrapped, level, fontScale, headingColor, wrapped = true)
            assertGap("Before $level", "Heading $level", 20)
            assertGap("Heading $level", wrapped, 20)
            assertGap(wrapped, "After $level", 12)
        }
    }

    private fun assertHeading(
        text: String,
        level: Int,
        fontScale: Float,
        color: Color,
        wrapped: Boolean,
    ) {
        val node = rule.onNodeWithText(text)
        val layout = node.layout()
        val style = layout.layoutInput.style
        val expected = expectedStyle(level)
        assertEquals("H$level family", expected.fontFamily, style.fontFamily)
        assertEquals("H$level size", expected.fontSize, style.fontSize)
        assertEquals("H$level line height", expected.lineHeight, style.lineHeight)
        assertEquals("H$level tracking", expected.letterSpacing, style.letterSpacing)
        assertEquals("H$level weight", expected.fontWeight, style.fontWeight)
        assertEquals("H$level colour", color, style.color)
        assertEquals("H$level trim", LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
        assertEquals("font scale", fontScale, layout.layoutInput.density.fontScale, 0f)
        assertFalse("H$level visual overflow", layout.hasVisualOverflow)
        if (wrapped) assertTrue("H$level must wrap", layout.lineCount > 1) else assertEquals("H$level single line", 1, layout.lineCount)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        // Native reference uses the independently specified tokens, including Android font scaling
        // and integer line-box rounding. A direct sp conversion differs from Compose's line-height resolver.
        val lineHeight = with(rule.density) { referenceLineHeights[level - 1].toDp() }
        if (fontScale == 1f) assertDpEquals(expected.lineHeight.value.dp, lineHeight, "H$level nominal line box")
        for (line in 0 until layout.lineCount) {
            assertFalse("H$level ellipsized line $line", layout.isLineEllipsized(line))
            assertDpEquals(
                lineHeight,
                with(rule.density) { (layout.getLineBottom(line) - layout.getLineTop(line)).toDp() },
                "H$level measured line $line",
            )
        }
        // Each line box rounds independently; constituent checks above retain the one-pixel bound.
        assertDpEquals(
            lineHeight * layout.lineCount,
            node.getUnclippedBoundsInRoot().height,
            "H$level total line boxes",
            pixels = layout.lineCount,
        )
        assertDpEquals(
            with(rule.density) { layout.size.height.toDp() },
            node.getUnclippedBoundsInRoot().height,
            "H$level node contains its full text layout",
        )
    }

    private fun expectedStyle(level: Int): TextStyle {
        val size =
            when (level) {
                1 -> 20
                2 -> 18
                3 -> 16
                else -> 14
            }
        val height =
            when (level) {
                1 -> 28
                2 -> 26
                3 -> 24
                else -> 20
            }
        val tracking =
            when (level) {
                1, 2 -> 0f
                3 -> 0.15f
                else -> 0.25f
            }
        return TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = size.sp,
            lineHeight = height.sp,
            letterSpacing = tracking.sp,
            fontWeight = if (level <= 3) FontWeight.Medium else FontWeight.Bold,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        )
    }

    private fun assertGap(
        before: String,
        after: String,
        expected: Int,
    ) {
        val above = rule.onNodeWithText(before).getUnclippedBoundsInRoot()
        val below = rule.onNodeWithText(after).getUnclippedBoundsInRoot()
        assertDpEquals(expected.dp, below.top - above.bottom, "$before -> $after")
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun wrappedHeading(level: Int) =
        "Section $level has a deliberately long heading that wraps across several lines while keeping every word visible in the reader"
}
