package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingMarkdownComposeTest {
    @get:Rule val compose = createComposeRule()
    private val source = mutableStateOf("")
    private val caret = mutableStateOf(true)
    private val streaming = mutableStateOf(true)
    private val taps = mutableListOf<String>()
    private var parses = 0
    private val parsedLengths = mutableListOf<Int>()
    private val compositions = mutableMapOf<Int, Int>()
    private val observer =
        object : StreamingMarkdownObserver {
            override fun parsed(length: Int) {
                parses++
                parsedLengths += length
            }

            override fun composed(key: Int) {
                compositions[key] = (compositions[key] ?: 0) + 1
            }
        }
    private val uri =
        object : UriHandler {
            override fun openUri(uri: String) {
                taps += uri
            }
        }

    private fun show(initial: String) {
        source.value = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides uri, LocalStreamingMarkdownObserver provides observer) {
                    Box(Modifier.width(280.dp)) {
                        if (streaming.value) {
                            StreamingMarkdownText(source.value, caret.value)
                        } else {
                            MarkdownText(source.value)
                        }
                    }
                }
            }
        }
    }

    private fun change(value: String) {
        compose.runOnIdle { source.value = value }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun texts(): List<AnnotatedString> =
        compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .filter { it.text != "▎" }

    private fun annotated(text: String) =
        texts()
            .also {
                assertTrue(
                    "expected $text in ${it.map { value -> value.text }}",
                    it.any { value ->
                        value.text ==
                            text
                    },
                )
            }.single { it.text == text }

    @Test fun pendingInlineIsPlainAndLinksStayInertUntilActualClose() {
        show("**bold `code [label](https://exa")
        assertEquals("bold code [label](https://exa", texts().single().text)
        assertTrue(texts().single().spanStyles.isEmpty())
        change("[label](https://exa")
        val pending = annotated("label")
        assertTrue(pending.getLinkAnnotations(0, pending.length).isEmpty())
        compose.onNodeWithText("label").performClick()
        assertTrue(taps.isEmpty())
        change("[label](https://example.com)")
        val complete = annotated("label")
        assertEquals("https://example.com", (complete.getLinkAnnotations(0, complete.length).single().item as LinkAnnotation.Url).url)
        compose.onNodeWithText("label").performClick()
        assertEquals(listOf("https://example.com"), taps)
    }

    @Test fun completedBlocksDoNotParseOrComposeOnTrailingAppendOrBlink() {
        show("**earlier**\n\ntrailing")
        val count = compositions[0]
        assertTrue(count != null && count > 0)
        val parseCount = parses
        change("**earlier**\n\ntrailing grows")
        assertEquals(count, compositions[0])
        assertEquals(parseCount + 1, parses)
        assertEquals("trailing grows".length, parsedLengths.last())
        val afterAppend = compositions.toMap()
        compose.runOnIdle { caret.value = false }
        compose.mainClock.advanceTimeBy(512)
        assertEquals(parseCount + 1, parses)
        assertEquals(afterAppend, compositions)
        assertTrue(annotated("earlier").spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun continuingListAndPendingTableCanStillChangeGrouping() {
        show("- first\n\n- second")
        change("- first\n\n- second\n- third")
        compose.onNodeWithText("third").assertExists()
        change("| A | B |\n| --- | --")
        assertEquals(listOf("A B"), texts().map { it.text })
        change("| A | B |\n| --- | --- |\n| one | two |\n")
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithText("B").assertExists()
        compose.onNodeWithText("one").assertExists()
        compose.onNodeWithText("two").assertExists()
    }

    @Test fun fencesKeepBlankLinesAndCaretOutsideCodeThenReleaseFollowingProse() {
        show("```kotlin\nval x = 1\n\nval y = 2")
        val code = annotated("val x = 1\n\nval y = 2")
        assertFalse(code.text.contains("▎"))
        compose.onNodeWithText("▎").assertExists()
        change("```kotlin\nval x = 1\n\nval y = 2\n```\n\n**after**")
        annotated("val x = 1\n\nval y = 2")
        assertTrue(annotated("after").spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun completeBodyHasIdenticalTextStylesAndTargetsWhenStreamingClears() {
        show("**bold** *italic* `code` [link](https://example.com)\n\n```text\nbody\n```\n\n| A | B |\n| --- | --- |\n| one | two |")
        val before = texts()
        val prose = before.first()
        assertTrue(prose.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(prose.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(prose.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        compose.runOnIdle { streaming.value = false }
        compose.mainClock.advanceTimeBy(64)
        val after = texts()
        assertEquals(before.map { it.text }, after.map { it.text })
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.spanStyles, b.spanStyles)
            assertEquals(
                a.getLinkAnnotations(0, a.length).map {
                    (it.item as LinkAnnotation.Url).url
                },
                b.getLinkAnnotations(0, b.length).map { (it.item as LinkAnnotation.Url).url },
            )
        }
        compose.onNodeWithText("▎").assertDoesNotExist()
    }

    @Test fun completionShowsBacklogImmediatelyAndMalformedFinalUsesFallback() {
        val message =
            mutableStateOf(
                Message(
                    "id",
                    "session",
                    Role.Assistant,
                    "**bold " + "word ".repeat(40),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    isStreaming = true,
                ),
            )
        compose.mainClock.autoAdvance = false
        compose.setContent { PyrycodeMobileTheme { MessageBubble(message.value, metaRowVisible = false) } }
        compose.mainClock.advanceTimeBy(80)
        assertFalse(texts().any { it.text.contains("word ".repeat(40)) })
        compose.runOnIdle { message.value = message.value.copy(isStreaming = false) }
        compose.mainClock.advanceTimeBy(64)
        assertEquals(message.value.content, texts().single().text)
        compose.onNodeWithText("▎").assertDoesNotExist()
    }

    @Test fun unsupportedAndEscapedSourceSurviveCompletion() {
        show("\\*literal\\* and \$20 and \$30 and https://example.com\n\n---")
        val before = texts().map { it.text }
        compose.runOnIdle { streaming.value = false }
        compose.mainClock.advanceTimeBy(64)
        assertEquals(before, texts().map { it.text })
    }

    private fun settleAndCompare() {
        val before = texts()
        compose.runOnIdle { streaming.value = false }
        compose.mainClock.advanceTimeBy(64)
        assertEquals(before.map { it.text }, texts().map { it.text })
        assertEquals(before.map { it.spanStyles }, texts().map { it.spanStyles })
        assertEquals(
            before.map { value -> value.getLinkAnnotations(0, value.length).map { (it.item as LinkAnnotation.Url).url } },
            texts().map { value -> value.getLinkAnnotations(0, value.length).map { (it.item as LinkAnnotation.Url).url } },
        )
    }

    @Test fun partialOrderedListMarkerRejoinsWithoutRenumberingAtSettlement() {
        show("1. first\n\n2")
        change("1. first\n\n2. second")
        assertEquals(listOf("1.", "first", "2.", "second"), texts().map { it.text })
        settleAndCompare()
    }

    @Test fun literalPunctuationAndClosedPipeProseSurviveAppendAndSettlement() {
        show("https://example.com/~user <em title=\"~user\">x</em>\n\nleft | right\n\nnext")
        val expected = listOf("https://example.com/~user <em title=\"~user\">x</em>", "left | right", "next grows")
        change(source.value + " grows")
        assertEquals(expected, texts().map { it.text })
        settleAndCompare()
    }

    @Test fun quotedAndSetextTableHeadersReleaseOnlyOnRealSeparator() {
        show("> | A | B |\n")
        assertEquals(listOf("A B"), texts().map { it.text })
        change("> | A | B |\n> ---")
        assertEquals(listOf("A B"), texts().map { it.text })
        change("> | A | B |\n> --- | ---\n> | one | two |\n")
        assertEquals(setOf("A", "B", "one", "two"), texts().map { it.text }.toSet())
        change("| A | B |\n---")
        assertEquals(listOf("A B"), texts().map { it.text })
        change("| A | B |\n--- | ---\n| one | two |\n")
        assertEquals(setOf("A", "B", "one", "two"), texts().map { it.text }.toSet())
        settleAndCompare()
    }

    @Test fun codeAndEscapedPipesStayVisibleInPendingCells() {
        show("| A | B\\|\n")
        assertEquals(listOf("A B\\|"), texts().map { it.text })
        change("| A | `B|")
        assertEquals(listOf("A B|"), texts().map { it.text })
        change("| A | `B|`\n| --- | --- |\n")
        // The shared GFM parser rejects an unescaped code pipe as a table; retain its complete fallback.
        assertEquals(listOf("| A | B| | --- | --- |"), texts().map { it.text })
        settleAndCompare()
    }

    @Test fun pendingLinkWithCodeBracketNeverExposesOrTapsThePartialDestination() {
        show("[text `a]b`](https://exa")
        val label = annotated("text `a]b`")
        assertTrue(label.getLinkAnnotations(0, label.length).isEmpty())
        compose.onNodeWithText(label.text).performClick()
        assertTrue(taps.isEmpty())
        change("[text `a]b`](https://example.com)")
        val complete = annotated(label.text)
        assertEquals("https://example.com", (complete.getLinkAnnotations(0, complete.length).single().item as LinkAnnotation.Url).url)
        compose.onNodeWithText(label.text).performClick()
        assertEquals(listOf("https://example.com"), taps)
        settleAndCompare()
    }

    @Test fun newlineClosedInvalidHeaderUsesRendererFallbackBeforeSettlement() {
        show("| A | B |\n---\nnext")
        assertTrue(texts().any { it.text.contains("| A | B |") })
        assertTrue(texts().any { it.text.contains("next") })
        settleAndCompare()
    }
}
