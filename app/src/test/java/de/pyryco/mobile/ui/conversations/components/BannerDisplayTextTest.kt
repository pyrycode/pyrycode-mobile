package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The render-boundary stripping a `banner`'s claude-authored text owes (#873). The set mirrors desktop's
 * `bannerDisplayText`, so the two clients draw the same characters for the same frame.
 */
class BannerDisplayTextTest {
    @Test
    fun plainText_passesThroughUnchanged() {
        assertEquals("Blocked by hook: no rm -rf, please.", bannerDisplayText("Blocked by hook: no rm -rf, please."))
    }

    @Test
    fun tabAndNewlines_survive() {
        assertEquals("one\n\ttwo\r\nthree", bannerDisplayText("one\n\ttwo\r\nthree"))
    }

    @Test
    fun csiSequences_areStripped() {
        assertEquals("red bold plain", bannerDisplayText("\u001b[31mred\u001b[0m \u001b[1;4mbold\u001b[m plain"))
    }

    @Test
    fun eightBitCsi_isStripped() {
        assertEquals("ab", bannerDisplayText("a\u009b2Jb"))
    }

    @Test
    fun oscSequences_areStrippedWithEitherTerminator() {
        assertEquals(
            "link text",
            bannerDisplayText("\u001b]8;;https://evil.example\u0007link\u001b]8;;\u001b\\ text"),
        )
    }

    @Test
    fun unterminatedOsc_consumesTheRest() {
        assertEquals("safe ", bannerDisplayText("safe \u001b]0;window title that never ends"))
    }

    @Test
    fun dcsAndApcStrings_areStripped() {
        assertEquals("ab", bannerDisplayText("a\u001bPq#0;2;0;0;0\u001b\\b"))
        assertEquals("cd", bannerDisplayText("c\u001b_payload\u001b\\d"))
    }

    @Test
    fun otherEscapeSequences_areStripped() {
        assertEquals("ab", bannerDisplayText("a\u001b(Bb"))
        assertEquals("cd", bannerDisplayText("c\u001b7d"))
    }

    @Test
    fun c0C1AndDelControls_areStripped() {
        assertEquals("abcdef", bannerDisplayText("a\u0000b\u0008c\u000bd\u007fe\u0085f"))
    }

    @Test
    fun loneEscape_isStripped() {
        assertEquals("end", bannerDisplayText("end\u001b"))
    }
}
