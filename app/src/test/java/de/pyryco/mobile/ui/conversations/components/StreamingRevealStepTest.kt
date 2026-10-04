package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRevealStepTest {
    @Test
    fun shortReply_revealsOneWholeWordPerStep() {
        assertEquals(33L, STREAMING_REVEAL_STEP_MS)
        val content = "One small reply"
        var revealed = 0
        listOf("One ", "One small ", content).forEachIndexed { tick, expected ->
            revealed = nextStreamingRevealLength(content, revealed, 15 - tick)
            assertEquals(expected, content.take(revealed))
        }
    }

    @Test
    fun whitespace_isPreservedAndSeparatesWords() {
        val content = "  One\t\n two\u2003three  "
        val first = nextStreamingRevealLength(content, 0, 15)
        assertEquals("  One\t\n ", content.take(first))
        assertEquals("  One\t\n two\u2003", content.take(nextStreamingRevealLength(content, first, 14)))
    }

    @Test
    fun appendedText_continuesFromRevealedPrefix() {
        val prefix = "Already visible "
        val content = prefix + "new words"
        assertEquals(prefix + "new ", content.take(nextStreamingRevealLength(content, prefix.length, 15)))
    }

    @Test
    fun emptyFinishedAndWhitespaceOnlyText_reachesItsEnd() {
        listOf("", "done", " \t\n").forEach { content ->
            assertEquals(content.length, nextStreamingRevealLength(content, content.length, 15))
        }
        assertEquals(3, nextStreamingRevealLength(" \t\n", 0, 15))
    }

    @Test
    fun singleLongWord_isNotSplit() {
        val content = "x".repeat(2000)
        assertEquals(content.length, nextStreamingRevealLength(content, 0, 15))
    }

    @Test
    fun twoThousandCharacterBacklog_catchesUpWithinFifteenWordAlignedSteps() {
        val content = "word ".repeat(400)
        var revealed = 0
        for (remaining in 15 downTo 1) {
            val next = nextStreamingRevealLength(content, revealed, remaining)
            assertTrue(next > revealed)
            assertEquals(' ', content[next - 1])
            revealed = next
        }
        assertEquals(content.length, revealed)
        assertTrue(15 * STREAMING_REVEAL_STEP_MS <= 500L)
    }
}
