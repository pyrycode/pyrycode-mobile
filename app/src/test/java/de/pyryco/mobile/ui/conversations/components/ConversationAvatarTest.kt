package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationAvatarTest {

    @Test
    fun `multi-word name returns lowercase first letters of first two words`() {
        assertEquals("kr", deriveInitials("kitchenclaw refactor", fallback = "id-1"))
        assertEquals("pd", deriveInitials("pyrycode discord integration", fallback = "id-2"))
    }

    @Test
    fun `hyphenated two-word name returns lowercase first letters`() {
        // Mirrors the Figma 'kc' bubble (channel "kitchen-claw").
        assertEquals("kc", deriveInitials("kitchen-claw", fallback = "id-1"))
    }

    @Test
    fun `hyphenated name splits on non-letter and uses first two words`() {
        assertEquals("lf", deriveInitials("leaky-faucet", fallback = "id-3"))
        assertEquals("rt", deriveInitials("rocd-thinking", fallback = "id-4"))
    }

    @Test
    fun `single word uses first two characters`() {
        assertEquals("sc", deriveInitials("scratch", fallback = "id-5"))
    }

    @Test
    fun `single-letter word pads with question mark`() {
        assertEquals("a?", deriveInitials("a", fallback = "fallback-id"))
    }

    @Test
    fun `null name falls back to first two characters of fallback id`() {
        assertEquals("ab", deriveInitials(null, fallback = "abc-123"))
    }

    @Test
    fun `blank name falls back to first two characters of fallback id`() {
        assertEquals("ab", deriveInitials("   ", fallback = "abc-123"))
    }

    @Test
    fun `blank name and short fallback pads with question mark`() {
        assertEquals("x?", deriveInitials("  ", fallback = "x"))
    }

    @Test
    fun `null name and blank fallback yields double question mark`() {
        assertEquals("??", deriveInitials(null, fallback = ""))
    }

    @Test
    fun `whitespace-padded multi-word name trims and uses initials`() {
        assertEquals("so", deriveInitials("  spaced  out  name  ", fallback = "id"))
    }

    @Test
    fun `palette index is deterministic and in range`() {
        val key = "kitchenclaw"
        val first = paletteIndexFor(key)
        val second = paletteIndexFor(key)
        assertEquals(first, second)
        assert(first in 0..2)
    }

    @Test
    fun `palette index handles negative hashCode without crashing`() {
        // String "a" hashCode is positive but other inputs may go negative; floorMod must keep it in [0,3)
        val keys = listOf("a", "longer key with many characters", "ZZZZZ", "", "🌟")
        for (k in keys) {
            val idx = paletteIndexFor(k)
            assert(idx in 0..2) { "paletteIndexFor($k) = $idx, expected 0..2" }
        }
    }
}
