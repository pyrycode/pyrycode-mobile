package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp")
@RunWith(AndroidJUnit4::class)
class MarkdownReaderDesignTest {
    @get:Rule val rule = createComposeRule()

    private var variant = Color.Unspecified

    private fun show(
        name: String = "Builder Pipeline - Plan.md",
        markdown: String = REFERENCE_MARKDOWN,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                variant = MaterialTheme.colorScheme.onSurfaceVariant
                MarkdownReaderScreen(MarkdownDocument(name, markdown), onBack = onBack)
            }
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun referencePlacesBarRuleAndBodyAtFigmaCoordinates() {
        show()
        val title = rule.onNodeWithText("Builder Pipeline - Plan.md").getUnclippedBoundsInRoot()
        val heading = rule.onNodeWithText("Builder Pipeline Plan").getUnclippedBoundsInRoot()
        val code = rule.onNodeWithTag("reader-code-panel").getUnclippedBoundsInRoot()
        val quote = rule.onNodeWithText("Tokens first, running time second.").getUnclippedBoundsInRoot()
        val paragraph = rule.onNodeWithText("The main board runs four roles", substring = true).getUnclippedBoundsInRoot()
        val subheading = rule.onNodeWithText("Next steps").getUnclippedBoundsInRoot()
        val firstItem = rule.onNodeWithText("Pin all five agents repos", substring = true).getUnclippedBoundsInRoot()

        assertEquals(56f, title.left.value, 1f)
        assertEquals(24f, title.top.value, 1f)
        assertEquals(20f, heading.left.value, 1f)
        assertEquals(97f, heading.top.value, 1f)
        assertEquals(141f, paragraph.top.value, 1f)
        assertEquals(225f, subheading.top.value, 1f)
        assertEquals(265f, firstItem.top.value, 1f)
        assertEquals(20f, code.left.value, 1f)
        assertEquals(385f, code.top.value, 1f)
        assertEquals(372f, code.width.value, 1f)
        assertEquals(64f, code.height.value, 1f)
        assertEquals(35f, quote.left.value, 1f)
        assertEquals(461f, quote.top.value, 2f)
    }

    @Test fun readerCodeAndQuoteUseReferenceRoles() {
        show()
        val code = layout("pyry release --both\npyry status").layoutInput.style
        val quote = layout("Tokens first, running time second.").layoutInput.style

        assertEquals(13.sp, code.fontSize)
        assertEquals(20.sp, code.lineHeight)
        assertEquals(FontFamily.Monospace, code.fontFamily)
        assertEquals(variant, code.color)
        assertEquals(16.sp, quote.fontSize)
        assertEquals(24.sp, quote.lineHeight)
        assertNotEquals(FontStyle.Italic, quote.fontStyle)
        assertEquals(variant, quote.color)
    }

    @Test fun longNameAndBodyLeaveControlsAndMenuReachable() {
        show(
            name = "a-very-long-markdown-file-name-that-must-not-overlap-either-control.md",
            markdown = REFERENCE_MARKDOWN + "\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" },
        )
        val back = rule.onNodeWithContentDescription("Back")
        val more = rule.onNodeWithContentDescription("More actions")
        back.assertIsDisplayed()
        more.assertIsDisplayed()
        val title = rule.onNodeWithText("a-very-long-markdown-file-name", substring = true).getUnclippedBoundsInRoot()
        assertTrue(title.left >= back.getUnclippedBoundsInRoot().right)
        assertTrue(title.right <= more.getUnclippedBoundsInRoot().left)
        more.performClick()
        rule.onNodeWithText("Save to device").assertIsDisplayed()
        rule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()
    }

    @Test fun headerPointerAreasRouteOnlyTheirOwnActions() {
        var backs = 0
        show(onBack = { backs++ })
        rule.onNodeWithText("Builder Pipeline - Plan.md").performTouchInput { click() }
        assertEquals(0, backs)
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions").performTouchInput { click() }
        rule.onNodeWithText("Copy as markdown").assertIsDisplayed()
        rule.onNodeWithText("Copy as markdown").performClick()
        rule.onNodeWithContentDescription("Back").performTouchInput { click() }
        assertEquals(1, backs)
    }

    @Test fun plainReaderCodeScrollsHorizontallyInsideItsPanel() {
        val line = "release " + (1..60).joinToString(" ") { "argument$it" }
        show(markdown = "```\n$line\n```")
        rule.onNodeWithText(line).assertIsDisplayed()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        scroll.performTouchInput { swipeLeft() }
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("plain code did not scroll: $before -> $after", after > before)
        assertEquals(
            372f,
            rule
                .onNodeWithTag("reader-code-panel")
                .getUnclippedBoundsInRoot()
                .width.value,
            1f,
        )
    }

    private companion object {
        const val REFERENCE_MARKDOWN =
            "# Builder Pipeline Plan\n\nThe main board runs four roles since 2026-09-01. Each ticket moves from " +
                "[refiner](https://example.com) to builder, then through review and the merge gate.\n\n## Next steps\n\n" +
                "- Pin all five agents repos to one dispatcher version\n- Measure token spend per role\n" +
                "- Retire the old single-role runner\n\n```\npyry release --both\npyry status\n```\n\n" +
                "> Tokens first, running time second."
    }
}
