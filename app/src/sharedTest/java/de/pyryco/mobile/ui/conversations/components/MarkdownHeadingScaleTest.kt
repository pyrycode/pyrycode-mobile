package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MarkdownHeadingScaleTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun darkThreadHeadingsKeepTheirScaleThroughStreamingAndSettlement() = assertScale(false, true)

    @Test
    fun lightThreadHeadingsKeepTheirScaleThroughStreamingAndSettlement() = assertScale(false, false)

    @Test
    fun darkReaderHeadingsKeepTheirScaleThroughStreamingAndSettlement() = assertScale(true, true)

    @Test
    fun lightReaderHeadingsKeepTheirScaleThroughStreamingAndSettlement() = assertScale(true, false)

    @Test
    fun setextHeadingsRetainTheRawBodyFallback() {
        val reader = mutableStateOf(false)
        val streaming = mutableStateOf(false)
        val source = "First\n=====\n\nSecond\n-----"
        rule.setContent {
            PyrycodeMobileTheme {
                val style =
                    MarkdownTextStyle(
                        body = if (reader.value) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        presentation = if (reader.value) MarkdownPresentation.Reader else MarkdownPresentation.Thread,
                    )
                if (streaming.value) {
                    StreamingMarkdownText(source, caretVisible = true, style = style)
                } else {
                    MarkdownText(source, style = style)
                }
            }
        }
        for (isReader in listOf(false, true)) {
            for (isStreaming in listOf(false, true)) {
                rule.runOnIdle {
                    reader.value = isReader
                    streaming.value = isStreaming
                }
                for (text in listOf("First\n=====", "Second\n-----")) {
                    val results = mutableListOf<TextLayoutResult>()
                    rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    val style = results.single().layoutInput.style
                    assertEquals(if (isReader) 16.sp else 14.sp, style.fontSize)
                    assertEquals(FontWeight.Normal, style.fontWeight)
                }
            }
        }
    }

    private fun assertScale(
        reader: Boolean,
        dark: Boolean,
    ) {
        val source = mutableStateOf("")
        val streaming = mutableStateOf(true)
        val caret = mutableStateOf(true)
        var headingColor = Color.Unspecified
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark) {
                headingColor = MaterialTheme.colorScheme.onSecondaryContainer
                val style =
                    MarkdownTextStyle(
                        body = if (reader) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        presentation = if (reader) MarkdownPresentation.Reader else MarkdownPresentation.Thread,
                    )
                if (streaming.value) {
                    StreamingMarkdownText(source.value, caret.value, style = style)
                } else {
                    MarkdownText(source.value, style = style)
                }
            }
        }
        for (level in 1..6) {
            val marker = "#".repeat(level)
            rule.runOnIdle {
                streaming.value = true
                caret.value = true
                source.value = "$marker Grow"
            }
            assertHeading("Grow$STREAMING_CARET_GLYPH", level, reader, headingColor)
            rule.runOnIdle { source.value += " more" }
            assertHeading("Grow more$STREAMING_CARET_GLYPH", level, reader, headingColor)
            rule.runOnIdle { caret.value = false }
            assertHeading("Grow more", level, reader, headingColor)
            rule.runOnIdle { source.value += "\n\nFollowing paragraph\n" }
            assertHeading("Grow more", level, reader, headingColor)
            rule.runOnIdle { source.value += "still growing" }
            assertHeading("Grow more", level, reader, headingColor)
            rule.runOnIdle { streaming.value = false }
            assertHeading("Grow more", level, reader, headingColor)
        }
    }

    private fun assertHeading(
        text: String,
        level: Int,
        reader: Boolean,
        color: Color,
    ) {
        val results = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val style = results.single().layoutInput.style
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
        assertEquals("H$level size", size.sp, style.fontSize)
        assertEquals("H$level line height", height.sp, style.lineHeight)
        assertEquals("H$level tracking", tracking.sp, style.letterSpacing)
        assertEquals("H$level weight", if (level <= 3) FontWeight.Medium else FontWeight.Bold, style.fontWeight)
        assertEquals("H$level family", FontFamily.SansSerif, style.fontFamily)
        assertEquals("H$level colour", color, style.color)
        if (reader) assertEquals("H$level reader trim", LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
    }
}
