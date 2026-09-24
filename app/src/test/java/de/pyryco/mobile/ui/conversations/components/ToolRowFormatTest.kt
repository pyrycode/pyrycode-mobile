package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tool row's two text rules (#895), mirrored from desktop: `formatToolElapsed` and `toolHeadline` +
 * `shortenPath` in `toolHeadline.ts` / `shortenPath.ts`.
 */
class ToolRowFormatTest {
    // ---- formatToolElapsed ----------------------------------------------------------------------

    @Test
    fun `elapsed under a minute renders as seconds`() {
        assertEquals("0s", formatToolElapsed(0))
        assertEquals("12s", formatToolElapsed(12))
        assertEquals("59s", formatToolElapsed(59))
    }

    @Test
    fun `elapsed from a minute renders as minutes and two-digit seconds`() {
        assertEquals("1m 00s", formatToolElapsed(60))
        assertEquals("1m 05s", formatToolElapsed(65))
        assertEquals("60m 00s", formatToolElapsed(3600))
    }

    @Test
    fun `negative elapsed keeps its sign`() {
        assertEquals("-1m 05s", formatToolElapsed(-65))
        assertEquals("-5s", formatToolElapsed(-5))
    }

    @Test
    fun `the most negative reading does not overflow`() {
        assertEquals("-35791394m 08s", formatToolElapsed(Int.MIN_VALUE))
    }

    // ---- toolRowSubject: preferred order ---------------------------------------------------------

    @Test
    fun `file_path wins over every later field`() {
        val fields = mapOf("command" to "ls", "file_path" to "src/a.kt", "description" to "d")
        assertEquals("src/a.kt", toolRowSubject("Read", fields, "précis"))
    }

    @Test
    fun `path wins over pattern and url wins over query`() {
        assertEquals("src", toolRowSubject("Grep", mapOf("pattern" to "foo", "path" to "src"), "p"))
        assertEquals("https://x", toolRowSubject("WebFetch", mapOf("query" to "q", "url" to "https://x"), "p"))
    }

    @Test
    fun `description is the last preferred field`() {
        assertEquals("q", toolRowSubject("WebSearch", mapOf("description" to "d", "query" to "q"), "p"))
        assertEquals("d", toolRowSubject("Task", mapOf("description" to "d", "prompt" to "long"), "p"))
    }

    @Test
    fun `an empty preferred value is skipped`() {
        assertEquals("ls", toolRowSubject("X", mapOf("file_path" to "", "command" to "ls"), "p"))
    }

    // ---- toolRowSubject: Bash override -----------------------------------------------------------

    @Test
    fun `Bash puts description before command`() {
        val fields = mapOf("command" to "git status", "description" to "Show working tree status")
        assertEquals("Show working tree status", toolRowSubject("Bash", fields, "p"))
    }

    @Test
    fun `Bash without a description falls back to command`() {
        assertEquals("git status", toolRowSubject("Bash", mapOf("command" to "git status"), "p"))
        assertEquals("git status", toolRowSubject("Bash", mapOf("command" to "git status", "description" to ""), "p"))
    }

    @Test
    fun `the override is for Bash exactly`() {
        val fields = mapOf("command" to "tail", "description" to "d")
        assertEquals("tail", toolRowSubject("BashOutput", fields, "p"))
        assertEquals("tail", toolRowSubject("bash", fields, "p"))
    }

    // ---- toolRowSubject: path shortening ---------------------------------------------------------

    @Test
    fun `a long path field is shortened to its last four segments`() {
        val fields = mapOf("file_path" to "/Users/me/src/app/ui/thread/Screen.kt")
        assertEquals(".../app/ui/thread/Screen.kt", toolRowSubject("Read", fields, "p"))
    }

    @Test
    fun `notebook_path is a path field`() {
        val fields = mapOf("notebook_path" to "a/b/c/d/e.ipynb")
        assertEquals(".../b/c/d/e.ipynb", toolRowSubject("NotebookEdit", fields, "p"))
    }

    @Test
    fun `a non-path field is never shortened`() {
        val command = "grep -r foo a/b/c/d/e/f"
        assertEquals(command, toolRowSubject("X", mapOf("command" to command), "p"))
        val url = "https://example.com/a/b/c/d/e"
        assertEquals(url, toolRowSubject("WebFetch", mapOf("url" to url), "p"))
    }

    // ---- toolRowSubject: fallback ----------------------------------------------------------------

    @Test
    fun `no fields falls back to the precis`() {
        assertEquals("git status", toolRowSubject("Bash", emptyMap(), "git status"))
    }

    @Test
    fun `only empty or unknown fields fall back to the precis`() {
        assertEquals("précis", toolRowSubject("X", mapOf("file_path" to "", "symbol" to "Foo"), "précis"))
    }

    // ---- shortenToolPath -------------------------------------------------------------------------

    @Test
    fun `a path of four segments or fewer is returned verbatim`() {
        assertEquals("a/b/c/d.kt", shortenToolPath("a/b/c/d.kt"))
        assertEquals("/a//b/c/", shortenToolPath("/a//b/c/"))
        assertEquals("", shortenToolPath(""))
    }

    @Test
    fun `a longer path keeps its last four segments behind the ellipsis`() {
        assertEquals(".../b/c/d/e.kt", shortenToolPath("/a/b/c/d/e.kt"))
        assertEquals(".../c/d/e/f", shortenToolPath("a/b//c/d/e/f/"))
    }
}
