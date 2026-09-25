package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1050: which link targets are workspace markdown paths, and where a tap on each one goes. */
class MarkdownLinkRoutingTest {
    @Test
    fun markdownPaths_areSentAsWritten_withoutFragmentOrLineSuffix() {
        mapOf(
            "notes/Plan.md" to "notes/Plan.md",
            "Plan.md:12" to "Plan.md",
            "Plan.md:12:5" to "Plan.md",
            "docs/A.MARKDOWN#usage" to "docs/A.MARKDOWN",
            "notes/Plan.Md:3#top" to "notes/Plan.Md",
            "My%20Plan.md" to "My%20Plan.md",
            "../x.md" to "../x.md",
            "/abs/notes.markdown" to "/abs/notes.markdown",
        ).forEach { (target, path) -> assertEquals(target, path, markdownLinkPath(target)) }
    }

    @Test
    fun schemesOtherFilesAndNonDigitSuffixes_areNotMarkdownPaths() {
        listOf(
            "https://example.com/a.md",
            "file:a.md",
            "mailto:a.md",
            "content://x/a.md",
            "notes/a.txt",
            "a.md.txt",
            "Plan.md:x",
            "Plan.md:",
            "#a.md",
            "",
        ).forEach { assertNull(it, markdownLinkPath(it)) }
    }

    @Test
    fun aMarkdownPath_goesToTheCallback_andNeverToAnotherApp() {
        val opened = mutableListOf<String>()
        val paths = mutableListOf<String>()
        routeMarkdownLink("notes/Plan.md:12", { paths += it }, { opened += it })
        assertEquals(listOf("notes/Plan.md"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun withoutACallback_aMarkdownPathStaysInert() {
        val opened = mutableListOf<String>()
        routeMarkdownLink("notes/Plan.md", null) { opened += it }
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun otherLinks_keepTodaysSchemeAllowlist_withOrWithoutACallback() {
        listOf<((String) -> Unit)?>(null, { error("not a markdown path: $it") }).forEach { callback ->
            val opened = mutableListOf<String>()
            listOf("https://example.com/a.md", "http://h", "mailto:a@b.c", "javascript:alert(1)", "file:a.md", "intent:x")
                .forEach { routeMarkdownLink(it, callback) { uri -> opened += uri } }
            assertEquals(listOf("https://example.com/a.md", "http://h", "mailto:a@b.c"), opened)
        }
    }
}
