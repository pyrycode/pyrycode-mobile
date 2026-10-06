package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1766 on a rendered frame: the streaming body reuses completed blocks, shows unfinished constructs without their
 * punctuation, keeps the caret out of the parsed text, and settles into exactly what [MarkdownText] draws.
 *
 * Every comparison renders both bodies at the same width, so "the same" includes where each line wraps.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class StreamingMarkdownTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private class RecordingObserver : StreamingMarkdownObserver {
        val parsed = mutableListOf<String>()
        val composed = mutableMapOf<Int, Int>()

        override fun parsed(tail: String) {
            parsed += tail
        }

        override fun composed(key: Int) {
            composed[key] = (composed[key] ?: 0) + 1
        }
    }

    private val streamed = mutableStateOf("")
    private val settled = mutableStateOf("")
    private val caret = mutableStateOf(false)

    private fun renderBoth(observer: StreamingMarkdownObserver? = null) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = false) {
                Surface {
                    Column {
                        Box(Modifier.width(BODY_WIDTH).testTag(STREAMING_TAG)) {
                            StreamingMarkdownText(streamed.value, caretVisible = caret.value, observer = observer)
                        }
                        Box(Modifier.width(BODY_WIDTH).testTag(SETTLED_TAG)) {
                            MarkdownText(settled.value)
                        }
                    }
                }
            }
        }
    }

    /**
     * One drawn text: its characters, spans, link targets and bounds relative to its body. A text that carried the
     * caret is compared by where it starts, since the caret's own glyph is the one difference settlement allows.
     */
    private data class Drawn(
        val text: String,
        val spans: List<String>,
        val links: List<String>,
        val bounds: Rect,
        val hadCaret: Boolean,
    )

    private fun assertSameBody(
        name: String,
        settledBody: List<Drawn>,
        streamingBody: List<Drawn>,
    ) {
        assertEquals("$name: texts", settledBody.map { it.text }, streamingBody.map { it.text })
        assertEquals("$name: spans", settledBody.map { it.spans }, streamingBody.map { it.spans })
        assertEquals("$name: links", settledBody.map { it.links }, streamingBody.map { it.links })
        settledBody.zip(streamingBody).forEach { (settledText, streamingText) ->
            if (streamingText.hadCaret) {
                assertEquals("$name: ${settledText.text} starts", settledText.bounds.topLeft, streamingText.bounds.topLeft)
            } else {
                assertEquals("$name: ${settledText.text} bounds", settledText.bounds, streamingText.bounds)
            }
        }
    }

    private fun drawn(tag: String): List<Drawn> {
        val origin =
            composeTestRule
                .onNode(hasTestTag(tag))
                .fetchSemanticsNode()
                .boundsInRoot.topLeft
        return composeTestRule
            .onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.drawn(origin) }
            .filter { it.text != STREAMING_CARET_GLYPH }
    }

    private fun SemanticsNode.drawn(origin: androidx.compose.ui.geometry.Offset): Drawn {
        var text: AnnotatedString = config[SemanticsProperties.Text].single()
        val hadCaret = text.text.endsWith(STREAMING_CARET_GLYPH)
        if (hadCaret) text = text.subSequence(0, text.length - 1)
        return Drawn(
            text = text.text,
            spans = text.spanStyles.filter { it.start < it.end }.map { "${it.start}-${it.end}:${it.item}" },
            links = text.getLinkAnnotations(0, text.length).map { "${it.start}-${it.end}:${(it.item as? LinkAnnotation.Url)?.url}" },
            bounds = boundsInRoot.translate(-origin.x, -origin.y),
            hadCaret = hadCaret,
        )
    }

    /** Word ends as the reveal steps them: each word with the whitespace after it. */
    private fun wordSteps(source: String): List<Int> {
        val steps = mutableListOf<Int>()
        var end = 0
        while (end < source.length) {
            end = nextWordEnd(source, end)
            steps += end
        }
        return steps
    }

    private fun nextWordEnd(
        source: String,
        from: Int,
    ): Int {
        var end = from
        while (end < source.length && source[end].isWhitespace()) end++
        while (end < source.length && !source[end].isWhitespace()) end++
        while (end < source.length && source[end].isWhitespace()) end++
        return end
    }

    /** Streams [source] word by word, then requires the streamed body to equal the settled one. */
    private fun assertSettlesUnchanged(
        name: String,
        source: String,
    ) {
        composeTestRule.runOnIdle {
            streamed.value = ""
            settled.value = source
        }
        wordSteps(source).forEach { end ->
            composeTestRule.runOnIdle { streamed.value = source.substring(0, end) }
        }
        composeTestRule.waitForIdle()
        assertSameBody(name, drawn(SETTLED_TAG), drawn(STREAMING_TAG))
    }

    // ------------------------------------------------------------------ reuse

    @Test
    fun completedBlocks_areNeitherReparsedNorRecomposed_whileTheTailGrowsAndTheCaretBlinks() {
        val observer = RecordingObserver()
        renderBoth(observer)
        val head = "# Title\n\nFirst paragraph with **bold**.\n\n- one\n- two\n\n```kotlin\nval x = 1\n```\n\n"
        composeTestRule.runOnIdle { streamed.value = head + "Trailing line\n\nGrowing" }
        composeTestRule.waitForIdle()
        val frozenKeys = listOf(0, head.indexOf("First"), head.indexOf("- one"), head.indexOf("```"))
        val before = frozenKeys.map { observer.composed[it] ?: 0 }
        assertTrue("every completed block composed once", before.all { it == 1 })
        val parsesBefore = observer.parsed.size

        listOf(" more", " words", " arrive", " here").forEach { word ->
            composeTestRule.runOnIdle { streamed.value += word }
            composeTestRule.runOnIdle { caret.value = !caret.value }
        }
        composeTestRule.waitForIdle()

        assertEquals("completed blocks are not recomposed", before, frozenKeys.map { observer.composed[it] ?: 0 })
        val later = observer.parsed.drop(parsesBefore)
        assertEquals("one parse per arrival, none per blink", 4, later.size)
        assertTrue("only the tail is parsed again", later.all { it.startsWith("Trailing line") })
        val growing = (head + "Trailing line\n\n").length
        assertTrue("the growing block recomposes", (observer.composed[growing] ?: 0) >= 5)
    }

    // ------------------------------------------------------------------ settlement parity, one per named case

    @Test
    fun completeMarkdown_settlesWithTheSameTextFormattingLinksAndBounds() {
        renderBoth()
        val cases =
            listOf(
                "interrupter heading" to "Some paragraph\n# heading\nafter\n",
                "interrupter text" to "Some paragraph\n#tag continues *here*\n",
                "interrupter rule" to "- an item\n***\nafter\n",
                "interrupter fence" to "> a quote\n```\ncode\n```\nafter\n",
                "interrupter html" to "Some paragraph\n<dividend> continues\n",
                "lists" to "1. first\n\n2. second\n\n* first\n\n* * *tail*\n",
                "lazy" to "> q\n#tag\n",
                "setext" to "Title\n=x\n",
                "definition" to "[a]: /u \"t\n# h\nx\"\n\nafter\n",
                "fence grows" to "```\ncode\n```x\nmore\n```\n\nafter\n",
                "pending header" to "| `A|B` |\n| --- | --- |\n| 1 | 2 |\n\n> | A | B |\n> | --- | --- |\n",
                "not a table" to "left | right\n\nnext\n",
                // Complete forms of the pending cases: the probe finds nothing left open, so nothing changes.
                "pending inline" to "**foo `bar**`** tail and [text](https://example.com/a) now\n",
                "literal inline" to "2 * 3 and https://example.com/~user and \\*\\*x and `a **b` done\n",
                "escaped bang" to "\\![text](https://example.com) x\n",
                "mixed reply" to
                    "# Plan\n\nFirst **bold**, *it*, ~~gone~~ and `code`.\n\n- one\n- two\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\n" +
                    "| Key | Value |\n| --- | --- |\n| a | **b** |\n\nDone [link](https://example.com).",
            )
        cases.forEach { (name, source) -> assertSettlesUnchanged(name, source) }
    }

    /** The one transition the probe cannot know in advance: a home path's tilde looks like an open strike. */
    @Test
    fun trailingAmbiguity_aHomePathChangesOnSettlement() {
        renderBoth()
        composeTestRule.runOnIdle {
            streamed.value = "see ~/path"
            settled.value = "see ~/path"
        }
        composeTestRule.waitForIdle()
        assertEquals("see /path", drawn(STREAMING_TAG).single().text)
        assertEquals("see ~/path", drawn(SETTLED_TAG).single().text)
        assertNotEquals(drawn(SETTLED_TAG).map { it.text }, drawn(STREAMING_TAG).map { it.text })
    }

    // ------------------------------------------------------------------ pending presentation

    private fun streamedText(source: String): AnnotatedString {
        composeTestRule.runOnIdle { streamed.value = source }
        composeTestRule.waitForIdle()
        val nodes =
            composeTestRule
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(STREAMING_TAG)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        return nodes.last().config[SemanticsProperties.Text].single()
    }

    private fun AnnotatedString.withoutCaret(): String = text.removeSuffix(STREAMING_CARET_GLYPH)

    private fun AnnotatedString.hasNoFormatting(): Boolean =
        spanStyles.none {
            it.item.fontWeight == FontWeight.Bold ||
                it.item.fontStyle == FontStyle.Italic ||
                it.item.fontFamily == FontFamily.Monospace ||
                it.item.textDecoration == TextDecoration.LineThrough
        } &&
            getLinkAnnotations(0, length).isEmpty()

    @Test
    fun pendingInlineConstructs_showTheirTextPlain_withoutPunctuationOrLinks() {
        renderBoth()
        val cases =
            listOf(
                "**bold" to "bold",
                "Some `code" to "Some code",
                "See [text](" to "See text",
                "See [text](https://exa" to "See text",
                "See [text](<https://exa" to "See text",
                "See [text](https://example.com \"tit" to "See text",
                "an *italic" to "an italic",
                "an _italic" to "an italic",
                "~~struck" to "struck",
                "a ~struck" to "a struck",
                "**foo `bar**" to "foo bar**",
                "\\![text](https://exa" to "\\!text",
                "[a `]` b](https://exa" to "a `]` b",
                "**bold *both" to "bold both",
            )
        cases.forEach { (source, expected) ->
            val text = streamedText(source)
            assertEquals(source, expected, text.withoutCaret())
            assertTrue("$source must render plain and untappable", text.hasNoFormatting())
        }
    }

    @Test
    fun literalPunctuation_staysLiteral_andCompleteConstructsKeepTheirFormatting() {
        renderBoth()
        listOf("2 * 3", "snake_case_name", "https://example.com/~user", "\\*\\*x", "`a **b` c").forEach { source ->
            composeTestRule.runOnIdle { settled.value = source }
            val text = streamedText(source)
            assertEquals(source, drawn(SETTLED_TAG).single().text, text.withoutCaret())
        }
        val complete = streamedText("**bold** and `code` and [link](https://example.com)")
        assertEquals("bold and code and link", complete.withoutCaret())
        assertTrue(complete.spanStyles.any { it.item.fontWeight == FontWeight.Bold && complete.text.substring(it.start, it.end) == "bold" })
        assertTrue(complete.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        val link = complete.getLinkAnnotations(0, complete.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun pendingTableHeader_showsItsCellsPlain_untilTheRealTableArrives() {
        renderBoth()
        listOf("| A | B |", "| A | B |\n", "| A | B |\n| -", "| A | B |\n---", "| A | B |\n| --- | --- | -").forEach { source ->
            assertEquals(source, "A B", streamedText(source).withoutCaret())
        }
        assertEquals("A B", streamedText("> | A | B |\n").withoutCaret())
        composeTestRule.runOnIdle { streamed.value = "| A | B |\n| --- | --- |\n" }
        composeTestRule.waitForIdle()
        assertEquals(listOf("A", "B"), drawn(STREAMING_TAG).map { it.text })
    }

    // ------------------------------------------------------------------ caret and code

    @Test
    fun caret_followsTheTrailingText_andNeverEntersCodeOrALink() {
        renderBoth()
        composeTestRule.runOnIdle { caret.value = true }
        assertTrue(streamedText("Plain words").text.endsWith("words$STREAMING_CARET_GLYPH"))
        assertEquals("text$STREAMING_CARET_GLYPH", streamedText("[text](https://exa").text)

        composeTestRule.runOnIdle { streamed.value = "Intro\n\n```kotlin\nval x = 1\n\n\nval y" }
        composeTestRule.waitForIdle()
        val texts = drawn(STREAMING_TAG).map { it.text }
        // The open fence keeps its blank lines as code, and the caret takes its own line below it.
        assertTrue(texts.contains("val x = 1\n\n\nval y"))
        assertTrue(texts.none { STREAMING_CARET_GLYPH in it })
        assertEquals(
            1,
            composeTestRule
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(STREAMING_CARET_GLYPH))),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )

        composeTestRule.runOnIdle { streamed.value = "Intro\n\n```kotlin\nval x = 1\n```\nAfter the fence" }
        composeTestRule.waitForIdle()
        val closed = drawn(STREAMING_TAG).map { it.text }
        assertTrue(closed.contains("val x = 1"))
        assertEquals("After the fence", closed.last())
    }

    // ------------------------------------------------------------------ pacing and transitions through the bubble

    private val bubbleMessage = mutableStateOf(message("", isStreaming = true))

    private fun message(
        content: String,
        isStreaming: Boolean,
    ) = Message(
        id = "m-1766",
        sessionId = "s1",
        role = Role.Assistant,
        content = content,
        timestamp = Instant.parse("2026-01-13T12:55:00Z"),
        isStreaming = isStreaming,
    )

    private fun renderBubble() {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = false) {
                Surface {
                    Box(Modifier.width(BUBBLE_WIDTH).testTag(BUBBLE_TAG)) {
                        MessageBubble(bubbleMessage.value, metaRowVisible = false)
                    }
                }
            }
        }
    }

    private fun bubbleTexts(): List<String> = drawn(BUBBLE_TAG).map { it.text }

    /** Ends the turn within one frame, less than one reveal tick. */
    private fun settle(reply: String) {
        composeTestRule.runOnIdle {
            bubbleMessage.value = message(reply, isStreaming = false)
            Snapshot.sendApplyNotifications()
        }
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    /** Advances frame by frame and records each change of the drawn body, with the time it was first drawn. */
    private fun revealTimeline(untilMs: Long): List<Pair<Long, String>> {
        val start = composeTestRule.mainClock.currentTime
        val changes = mutableListOf<Pair<Long, String>>()
        while (composeTestRule.mainClock.currentTime - start < untilMs) {
            composeTestRule.mainClock.advanceTimeByFrame()
            val shown = bubbleTexts().joinToString("\n").trimEnd()
            if (shown != changes.lastOrNull()?.second) changes += (composeTestRule.mainClock.currentTime - start) to shown
        }
        return changes
    }

    @Test
    fun pacing_aSmallBacklogRevealsOneFormattedWordPerTick() {
        composeTestRule.mainClock.autoAdvance = false
        bubbleMessage.value = message("Short **bold** reply here", isStreaming = true)
        renderBubble()
        val timeline = revealTimeline(untilMs = 400).filter { it.second.isNotEmpty() }
        assertEquals(listOf("Short", "Short bold", "Short bold reply", "Short bold reply here"), timeline.map { it.second })
        timeline.zipWithNext().forEach { (a, b) ->
            assertTrue("one word per 33 ms tick", b.first - a.first >= STREAMING_REVEAL_STEP_MS - 1)
        }
    }

    @Test
    fun pacing_aLargeFormattedBacklogCatchesUpWithinFifteenTicks_andNeverShowsPunctuation() {
        composeTestRule.mainClock.autoAdvance = false
        val reply = "Intro with **bold words** and `code` " + "word ".repeat(60)
        bubbleMessage.value = message(reply, isStreaming = true)
        renderBubble()
        val timeline = revealTimeline(untilMs = 600)
        timeline.forEach { (_, shown) -> assertFalse("structural punctuation never shows: $shown", "**" in shown || "`" in shown) }
        val full = reply.replace("**", "").replace("`", "").trimEnd()
        val first = timeline.first { it.second.isNotEmpty() }.first
        val caughtUp = timeline.first { it.second == full }.first
        // 495 ms of reveal budget, measured from the first step.
        assertTrue(
            "caught up after ${caughtUp - first} ms",
            caughtUp - first <= (STREAMING_CATCH_UP_TICKS - 1) * STREAMING_REVEAL_STEP_MS + 16,
        )
        assertTrue("several words per tick for a large backlog", timeline.size <= STREAMING_CATCH_UP_TICKS + 1)
    }

    @Test
    fun completion_beforeTheRevealCatchesUp_showsTheWholeReplyAtOnce_andMalformedSourceFallsBack() {
        composeTestRule.mainClock.autoAdvance = false
        val reply = "Intro **open bold " + "word ".repeat(200)
        bubbleMessage.value = message(reply, isStreaming = true)
        renderBubble()
        composeTestRule.mainClock.advanceTimeBy(STREAMING_REVEAL_STEP_MS * 2)
        val streaming = bubbleTexts().single()
        assertTrue(streaming.length < reply.length / 2)
        assertTrue("the pending bold hides its opener", streaming.startsWith("Intro open bold"))

        settle(reply)
        // The never-closed bold is the existing renderer's literal fallback once the reply is final.
        assertEquals(reply.trimEnd(), bubbleTexts().single().trimEnd())
    }

    @Test
    fun clearingIsStreaming_onACaughtUpReply_keepsTextFormattingLinksAndPositions() {
        composeTestRule.mainClock.autoAdvance = false
        val reply =
            "# Plan\n\nFirst **bold** and [link](https://example.com).\n\n- one\n- two\n\n" +
                "| Key | Value |\n| --- | --- |\n| a | b |\n\nDone *now*"
        bubbleMessage.value = message(reply, isStreaming = true)
        renderBubble()
        // Caught up after 16 ticks; at 1100 ms the caret's second toggle has turned it back on.
        composeTestRule.mainClock.advanceTimeBy(1_100)
        val streaming = drawn(BUBBLE_TAG)
        assertTrue("still streaming, so the caret is drawn", streaming.any { it.hadCaret })
        settle(reply)
        val settledBody = drawn(BUBBLE_TAG)
        assertTrue("settled, so the caret is gone", settledBody.none { it.hadCaret })
        assertSameBody("bubble", settledBody, streaming)
    }

    private companion object {
        const val STREAMING_TAG = "streaming-body"
        const val SETTLED_TAG = "settled-body"
        const val BUBBLE_TAG = "bubble-host"
        val BODY_WIDTH = 240.dp
        val BUBBLE_WIDTH = 320.dp
        const val STREAMING_CATCH_UP_TICKS = 15
    }
}
