package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1067: the reader's copy formats — the note as plain text and as HTML — and the clipboard bounds. */
class MarkdownConversionsTest {
    @Test
    fun plainText_dropsTheSyntaxTheRendererHides() {
        val markdown =
            """
            # Plan

            ### Small *heading*

            Some **bold**, _italic_, ~~struck~~, ~single~ and `code` text with a [link](https://example.com/x).
            A second line.

            > Quoted **words**
            > and more

            - one
            - two
              - nested
            1. first
            2. second
            - [x] done
            - [ ] open
            """.trimIndent()

        assertEquals(
            "Plan\n\n" +
                "Small heading\n\n" +
                "Some bold, italic, struck, single and code text with a link. A second line.\n\n" +
                "Quoted words and more\n\n" +
                "one\ntwo\n  nested\n\n" +
                "first\nsecond\n\n" +
                "done\nopen",
            markdownPlainText(markdown),
        )
    }

    @Test
    fun plainText_keepsCodeBlocksVerbatim_andTableCells() {
        val markdown =
            """
            ```kotlin
            val x = "**not bold**"

            println(x)
            ```

                indented `code`

            | Name | Value |
            |:-----|------:|
            | **a** | 1 |
            """.trimIndent()

        assertEquals(
            "val x = \"**not bold**\"\n\nprintln(x)\n\n" +
                "indented `code`\n\n" +
                "Name\tValue\na\t1",
            markdownPlainText(markdown),
        )
    }

    @Test
    fun plainText_ofAllSixHeadingLevels_hasNoMarkers() {
        assertEquals("A\n\nB\n\nC", markdownPlainText("#### A\n\n##### B\n\n###### C"))
    }

    @Test
    fun html_rendersTheNote() {
        val html = markdownHtml("# Plan\n\nSome **bold** and a [link](https://example.com/a?b=1&c=2).")

        assertTrue(html, html.contains("<h1>Plan</h1>"))
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<a href=\"https://example.com/a?b=1&amp;c=2\">link</a>"))
    }

    @Test
    fun html_escapesRawHtml_asText() {
        val html = markdownHtml("<script>alert(1)</script>\n\nInline <img src=x onerror=alert(1)> here.")

        assertFalse(html, html.contains("<script"))
        assertFalse(html, html.contains("<img"))
        assertTrue(html, html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html, html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun html_keepsHrefOnly_forTheRenderersSchemes() {
        val html =
            markdownHtml(
                "[a](http://a.example) [b](HTTPS://b.example) [c](mailto:c@example.com) " +
                    "[d][ref] <https://e.example> https://f.example\n\n[ref]: https://d.example",
            )

        assertTrue(html, html.contains("<a href=\"http://a.example\">a</a>"))
        assertTrue(html, html.contains("<a href=\"HTTPS://b.example\">b</a>"))
        assertTrue(html, html.contains("<a href=\"mailto:c@example.com\">c</a>"))
        assertTrue(html, html.contains("<a href=\"https://d.example\">d</a>"))
        assertTrue(html, html.contains("<a href=\"https://e.example\">"))
        assertTrue(html, html.contains("<a href=\"https://f.example\">"))
    }

    @Test
    fun html_writesOnlyTheText_forAnyOtherLink() {
        listOf(
            "javascript:alert(1)",
            "java&#115;cript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "file:///etc/passwd",
            "data:text/html,x",
            "intent://x#Intent;end",
            "notes/Plan.md",
            "vbscript:x",
        ).forEach { target ->
            val html = markdownHtml("[text]($target)")
            assertFalse("$target → $html", html.contains("<a"))
            assertFalse("$target → $html", html.contains("href"))
            assertTrue("$target → $html", html.contains("text"))
        }
        val autolink = markdownHtml("<javascript:alert(1)>")
        assertFalse(autolink, autolink.contains("<a"))
    }

    @Test
    fun html_neverLetsNoteTextOpenOrCloseAnAttribute() {
        val quoted = markdownHtml("[x](https://a.example/\"onmouseover=\"alert(1) \"title\")")
        assertFalse(quoted, quoted.contains("onmouseover=\""))
        assertFalse(quoted, quoted.contains("title="))

        val fence = markdownHtml("```\"><script>alert(1)</script>\ncode\n```")
        assertFalse(fence, fence.contains("<script"))
        assertTrue(fence, fence.contains("code"))
    }

    @Test
    fun html_writesImagesAsTheirAltText() {
        val html = markdownHtml("![a diagram](https://tracker.example/p.png) and ![ref][r]\n\n[r]: https://x.example/q.png")

        assertFalse(html, html.contains("<img"))
        assertFalse(html, html.contains("tracker.example"))
        assertTrue(html, html.contains("a diagram"))
    }

    @Test
    fun aNoteAtTheReadersBound_convertsWithinTheClipBounds() {
        val note = "<".repeat(262_144)

        val html = boundClipHtml(markdownHtml(note))
        val plain = boundClipText(markdownPlainText(note))

        assertTrue(html.length <= MAX_CLIPBOARD_CHARS)
        assertTrue(plain.length <= MAX_CLIPBOARD_CHARS)
        assertTrue(html.endsWith(">"))
    }

    @Test
    fun clipBounds_leaveShortTextAlone_andCutLongHtmlAfterATag() {
        assertEquals("<p>x</p>", boundClipHtml("<p>x</p>"))
        assertEquals("abc", boundClipText("abc"))

        val long = "<p>" + "a".repeat(MAX_CLIPBOARD_CHARS) + "</p>"
        assertEquals("<p>", boundClipHtml(long))
        assertEquals(MAX_CLIPBOARD_CHARS, boundClipText("b".repeat(MAX_CLIPBOARD_CHARS + 5)).length)
    }
}
