package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MarkdownHeadingSpacingTest {
    @get:Rule
    val rule = createComposeRule()

    private val source = mutableStateOf("")
    private val streaming = mutableStateOf(false)
    private val caret = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)

    private fun show(
        isStreaming: Boolean,
        reader: Boolean = false,
    ) {
        streaming.value = isStreaming
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                    val style =
                        MarkdownTextStyle(
                            body = if (reader) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            blockSpacing = if (reader) 12.dp else 37.dp,
                            listItemSpacing = if (reader) 6.dp else 7.dp,
                            presentation = if (reader) MarkdownPresentation.Reader else MarkdownPresentation.Thread,
                        )
                    Box(Modifier.width(280.dp)) {
                        if (streaming.value) {
                            StreamingMarkdownText(source.value, caret.value, Modifier.testTag("body"), style = style)
                        } else {
                            MarkdownText(source.value, Modifier.testTag("body"), style = style)
                        }
                    }
                }
            }
        }
    }

    private fun update(markdown: String) {
        rule.runOnIdle { source.value = markdown }
        rule.waitForIdle()
    }

    private fun bounds(text: String): DpRect = rule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private fun gap(
        before: String,
        after: String,
        expected: Float,
    ) {
        assertEquals("$before -> $after", expected, (bounds(after).top - bounds(before).bottom).value, 0.6f)
    }

    private fun edges(
        first: String,
        last: String,
    ) {
        val body = rule.onNodeWithTag("body").getUnclippedBoundsInRoot()
        assertEquals("no leading gap", body.top.value, bounds(first).top.value, 0.6f)
        assertEquals("no trailing gap", body.bottom.value, bounds(last).bottom.value, 0.6f)
    }

    @Test
    fun settledRootUsesHeadingGapsForEveryLevel() = rootGaps(isStreaming = false)

    @Test
    fun streamingRootUsesHeadingGapsForEveryLevel() = rootGaps(isStreaming = true)

    @Test
    fun readerSettledRootKeepsItsTwelveDpOrdinaryGap() = rootGaps(isStreaming = false, reader = true)

    @Test
    fun readerStreamingRootKeepsItsTwelveDpOrdinaryGap() = rootGaps(isStreaming = true, reader = true)

    private fun rootGaps(
        isStreaming: Boolean,
        reader: Boolean = false,
    ) {
        show(isStreaming, reader)
        for (level in 1..6) {
            val marker = "#".repeat(level)
            update("\n\nBefore\n\n$marker Heading\n\n$marker Next\n\nAfter\n\nOrdinary\n\n")
            gap("Before", "Heading", 20f)
            gap("Heading", "Next", 20f)
            gap("Next", "After", 12f)
            gap("After", "Ordinary", if (reader) 12f else 37f)
            edges("Before", "Ordinary")
        }
    }

    @Test
    fun settledNestedBlocksKeepHeadingGapsAndContainerEdges() = nestedGaps(isStreaming = false)

    @Test
    fun streamingNestedBlocksKeepHeadingGapsAndContainerEdges() = nestedGaps(isStreaming = true)

    @Test
    fun readerSettledNestedBlocksKeepHeadingGapsAndContainerEdges() = nestedGaps(isStreaming = false, reader = true)

    @Test
    fun readerStreamingNestedBlocksKeepHeadingGapsAndContainerEdges() = nestedGaps(isStreaming = true, reader = true)

    private fun nestedGaps(
        isStreaming: Boolean,
        reader: Boolean = false,
    ) {
        show(isStreaming, reader)
        for (level in 1..6) {
            val marker = "#".repeat(level)
            // The final heading ends its item: only the configured sibling-item gap follows it.
            update("- $marker First\n\n  prose\n\n  $marker Middle\n\n  $marker Last\n- Sibling")
            gap("First", "prose", 12f)
            gap("prose", "Middle", 20f)
            gap("Middle", "Last", 20f)
            val sibling = if (reader) "•  Sibling" else "Sibling"
            gap("Last", sibling, if (reader) 6f else 7f)
            edges("First", sibling)
            val listLeft = bounds("First").left
            assertEquals(listLeft, bounds("Middle").left)
            assertEquals(listLeft, bounds("Last").left)

            update("> $marker First\n>\n> prose\n>\n> $marker Middle\n>\n> $marker Last")
            gap("First", "prose", 12f)
            gap("prose", "Middle", 20f)
            gap("Middle", "Last", 20f)
            edges("First", "Last")
            val quote = rule.onNodeWithTag("body").getUnclippedBoundsInRoot()
            assertEquals("quote rule plus content indent", if (reader) 15f else 16f, (bounds("First").left - quote.left).value, 0.6f)

            // Reader's first-paragraph path embeds the bullet, then indents later blocks.
            update("- lead\n\n  $marker Heading\n\n  tail")
            gap(if (reader) "•  lead" else "lead", "Heading", 20f)
            gap("Heading", "tail", 12f)
            edges(if (reader) "•  lead" else "lead", "tail")
            assertEquals(bounds("Heading").left, bounds("tail").left)
            if (reader) assertEquals(14f, (bounds("Heading").left - bounds("•  lead").left).value, 0.6f)
        }
    }

    @Test
    fun settledEdgeHeadingsHaveNoOuterSpacing() = edgeHeadings(isStreaming = false)

    @Test
    fun streamingEdgeHeadingsHaveNoOuterSpacing() = edgeHeadings(isStreaming = true)

    private fun edgeHeadings(isStreaming: Boolean) {
        show(isStreaming)
        for (level in 1..6) {
            val marker = "#".repeat(level)
            for (single in listOf("$marker Heading", "- $marker Heading", "> $marker Heading", "- > $marker Heading")) {
                update(single)
                edges("Heading", "Heading")
            }
            update("$marker First\n\nbody\n\n$marker Last")
            gap("First", "body", 12f)
            gap("body", "Last", 20f)
            edges("First", "Last")
        }
    }

    @Test
    fun setextKeepsTheOrdinaryGapInBothRenderers() {
        show(isStreaming = false)
        for (isStreaming in listOf(false, true)) {
            rule.runOnIdle { streaming.value = isStreaming }
            update("Before\n\nSetext\n======\n\nAfter")
            gap("Before", "Setext\n======", 37f)
            gap("Setext\n======", "After", 37f)
        }
    }

    @Test
    fun fontScalingChangesTextHeightButKeepsHeadingGapsInDp() {
        show(isStreaming = false)
        update("Before\n\n## Heading\n\nAfter")
        val originalHeight = bounds("Heading").height
        for (isStreaming in listOf(false, true)) {
            rule.runOnIdle {
                streaming.value = isStreaming
                fontScale.value = 1.5f
            }
            gap("Before", "Heading", 20f)
            gap("Heading", "After", 12f)
            assertTrue(bounds("Heading").height > originalHeight)
        }
    }

    @Test
    fun headingTailKeepsSpacingThroughCompletionAndSettlement() {
        show(isStreaming = true)
        for (level in 1..6) {
            rule.runOnIdle { streaming.value = true }
            val prefix = "Before\n\n${"#".repeat(level)} Heading"
            update(prefix)
            gap("Before", "Heading", 20f)
            val growing = bounds("Heading")
            rule.runOnIdle { caret.value = true }
            gap("Before", "Heading$STREAMING_CARET_GLYPH", 20f)
            assertEquals(growing.top, bounds("Heading$STREAMING_CARET_GLYPH").top)
            rule.runOnIdle { caret.value = false }
            update("$prefix grows")
            assertEquals(growing.top, bounds("Heading grows").top)
            // A completed following line freezes the heading in the streaming cache.
            update("$prefix grows\n\nAfter\n\nTail\n")
            gap("Before", "Heading grows", 20f)
            gap("Heading grows", "After", 12f)
            val completed = listOf("Before", "Heading grows", "After", "Tail").map(::bounds)
            rule.runOnIdle { streaming.value = false }
            assertEquals(completed, listOf("Before", "Heading grows", "After", "Tail").map(::bounds))
        }
    }
}
