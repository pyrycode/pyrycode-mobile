package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BareObsidianLinksTest {
    private fun targets(source: String): List<String> = bareObsidianRanges(source).map { source.substring(it) }

    @Test
    fun candidates_keepExactCaseEscapesAndSourceRanges() {
        val first = "obsidian://open?vault=V&file=Note"
        val second = "ObSiDiAn://open?vault=Second%20brain&file=A%2fB.MD"
        val source = "See $first and $second now"
        assertEquals(listOf(first, second), targets(source))
        assertEquals(
            listOf(
                source.indexOf(first) until source.indexOf(first) + first.length,
                source.indexOf(second) until source.indexOf(second) + second.length,
            ),
            bareObsidianRanges(source),
        )
    }

    @Test
    fun onlyRealStartsAdmitCandidates() {
        val url = "obsidian://open?file=Note.md"
        listOf("", "See ", "(", "\"", "'", "“", "‘", "\u2003").forEach { prefix ->
            assertEquals(prefix, listOf(url), targets(prefix + url))
        }
        listOf("word", "/", "=", "[", "\\").forEach { prefix -> assertTrue(prefix, targets(prefix + url).isEmpty()) }
        assertEquals(listOf(url, url), targets("$url\u2003$url"))
    }

    @Test
    fun punctuation_validatesWholeThenTrimsTheMaximalRunOnce() {
        val base = "obsidian://open?file=Note.md"
        listOf(".", ",", ";", ")", "!", "?", "\"", "'", "”", "’", ".);!?\"’").forEach { punctuation ->
            assertEquals(punctuation, listOf(base), targets("($base$punctuation"))
        }
        listOf("!", ",", ";", ")", "?", "\"", "'", "”", "’").forEach { punctuation ->
            val admitted = "obsidian://open?file=Note$punctuation"
            assertEquals("admitted punctuation remains part of the target", listOf(admitted), targets(admitted))
        }
        listOf("obsidian://open?file=x.hta.", "$base&unknown=x!", "$base&append=x)").forEach { assertTrue(it, targets(it).isEmpty()) }
    }

    @Test
    fun refusedTokens_neverExposeAnAdmittedPrefixOrEmbeddedTarget() {
        listOf(
            "obsidian://new?file=Note",
            "obsidian://Open?file=Note",
            "obsidian://opening?file=Note",
            "obsidian://open?file=Note&append=x",
            "obsidian://open?file=Note&unknown=x",
            "obsidian://open?file=x.hta",
            "obsidian://open?file=Note%",
            "obsidian://open?file=Note%GG",
            "obsidian://open?file=Note%26append=x",
            "obsidian://open?file=Note%23fragment",
            "obsidian://open?file=Note#fragment",
            "obsidian://open?file=%ED%A0%80",
            "obsidian://open?unknown=(obsidian://open?file=Good)",
            "javascript:alert(1)",
            "file:///tmp/a.md",
            "http://example.com",
            "https://example.com",
            "www.example.com",
            "a@example.com",
        ).forEach { assertTrue(it, targets(it).isEmpty()) }
    }

    @Test
    fun arrivingSourceEnd_blocksEvenIndependentlyValidPrefixesAndPunctuation() {
        listOf(
            "obsidian://open?file=No",
            "obsidian://open?file=Note",
            "obsidian://open?file=Note!",
            "obsidian://open?file=Note.md.",
        ).forEach { source ->
            assertTrue(source, bareObsidianRanges(source, arrivingSourceEnd = source.length).isEmpty())
            val terminated = "$source "
            assertEquals(
                targets(source),
                bareObsidianRanges(terminated, arrivingSourceEnd = terminated.length).map { terminated.substring(it) },
            )
        }
    }

    @Test
    fun syntheticClosers_doNotEstablishCompletionButRealClosersDo() {
        val url = "obsidian://open?file=Note.md"
        val probed = "$url**"
        assertTrue(
            bareObsidianRanges(probed, url.indices, arrivingSourceEnd = url.length, synthetic = url.length until probed.length).isEmpty(),
        )
        assertEquals(listOf(url.indices), bareObsidianRanges(probed, url.indices, arrivingSourceEnd = probed.length))
        assertEquals(
            listOf(url.indices),
            bareObsidianRanges(
                "$url** ",
                url.indices,
                arrivingSourceEnd = url.length + 1,
                synthetic =
                    url.length until probed.length,
            ),
        )
        assertTrue(bareObsidianRanges(probed, arrivingSourceEnd = url.length, synthetic = url.length until probed.length).isEmpty())
    }
}
