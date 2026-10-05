package de.pyryco.mobile.ui.conversations.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    private fun text(source: String): String {
        val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
        val pending = PendingMarkdown(source, root)
        return root.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }.joinToString("\n") {
            pending.blockText(it) ?: streamingInlineText(it.children, source, pending)
        }
    }

    @Test fun pendingInlineShowsArrivedText() {
        mapOf(
            "**bold" to "bold",
            "*italic" to "italic",
            "_italic" to "italic",
            "__bold" to "bold",
            "~~strike" to "strike",
            "~strike" to "strike",
            "`code" to "code",
            "``a ` b" to "a ` b",
            "[text](" to "text",
            "[text](https://exa" to "text",
        ).forEach { (source, expected) ->
            assertEquals(source, expected, text(source))
        }
    }

    @Test fun completedNestedAndLiteralSourceStayWithRenderer() {
        listOf(
            "**bold**",
            "*italic*",
            "~~strike~~",
            "~strike~",
            "`**code** | [x](url)`",
            "\\*escaped\\*",
            "a_b",
            "run ~/a and ~/b now",
            "https://example.com",
            "\$20 and \$30",
            "![alt](image.png)",
        ).forEach { source ->
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
            val paragraph = root.children.single { it.type == MarkdownElementTypes.PARAGRAPH }
            assertEquals(source, inlineText(paragraph.children, source), text(source))
        }
        assertEquals("outer inner", text("**outer *inner*"))
        assertEquals("**literal** [x](url) |", text("`**literal** [x](url) |"))
        assertEquals("a *literal*", text("[a *literal*](https://exa"))
    }

    @Test fun pendingHeaderHidesPipesAndPartialSeparatorOnly() {
        listOf("| A | B |\n", "| A | B |\n| --- |", "| A | B |\n| --- | --").forEach {
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(it)
            assertEquals(it, "A B", PendingMarkdown(it, root).blockText(root.children.first { node -> node.children.isNotEmpty() }))
        }
        val source = "| A | B |\n| --- | --- |\n"
        val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
        assertTrue(root.children.any { it.type == GFMElementTypes.TABLE })
        assertEquals(null, PendingMarkdown(source, root).blockText(root.children.first { it.type == GFMElementTypes.TABLE }))
        val escaped = "| a\\|b | `x|y` |\n"
        val parsed = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(escaped)
        assertEquals("a\\|b x|y", PendingMarkdown(escaped, parsed).blockText(parsed.children.first { it.children.isNotEmpty() }))
    }

    @Test fun pendingHeaderAlsoKeepsPendingInlineTextPlainAndLinkDestinationsHidden() {
        val source = "| **bold | [text](https://exa |\n"
        val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
        assertEquals("bold text", PendingMarkdown(source, root).blockText(root.children.first { it.children.isNotEmpty() }))
    }

    @Test fun cacheRetainsCompletedBlockButKeepsListsAndTablesMutable() {
        val parsedLengths = mutableListOf<Int>()
        val cache = StreamingMarkdownCache { parsedLengths += it.length }
        val first = cache.update("**done**\n\ntail")
        val second = cache.update("**done**\n\ntail grows")
        assertSame(first.first(), second.first())
        assertTrue(parsedLengths.last() < "**done**\n\ntail grows".length)
        val listCache = StreamingMarkdownCache()
        assertEquals(1, listCache.update("- first\n\n- second").size)
        assertEquals(1, listCache.update("- first\n\n- second\n- third").size)
        val tableCache = StreamingMarkdownCache()
        tableCache.update("| A | B |\n")
        val table = tableCache.update("| A | B |\n| --- | --- |\n")
        assertEquals(GFMElementTypes.TABLE, table.single().node.type)
        assertEquals(
            MarkdownElementTypes.PARAGRAPH,
            cache
                .update("replacement")
                .single()
                .node.type,
        )
    }

    @Test fun everyAppendPrefixRemainsMutableUntilItsRealTableOrListBoundary() {
        val sources =
            listOf(
                "first\n\n| A | B |\n| --- | --- |\n| one | two |\n\nafter",
                "first\n\n- one\n\n- two\n  continued\n\nafter",
                "first\n\n```kotlin\nbody\n\nmore\n```\n\nafter",
            )
        sources.forEach { source ->
            val cache = StreamingMarkdownCache()
            for (length in 1..source.length) cache.update(source.take(length))
            val final = cache.update(source)
            val children = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source).children
            val expected = children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
            assertEquals(source, expected.map { it.type }, final.map { it.node.type })
            val expectedText = expected.map { it.getTextInNode(source).toString() }
            val finalText = final.map { it.node.getTextInNode(it.source).toString() }
            assertEquals(source, expectedText, finalText)
        }
    }

    @Test fun anOpenFenceOwnsBlankLinesUntilItsRealCloser() {
        val cache = StreamingMarkdownCache()
        val source = "```kotlin\nval x = 1\n\nval y = 2"
        val fence = cache.update(source).single()
        assertEquals(MarkdownElementTypes.CODE_FENCE, fence.node.type)
        assertEquals("val x = 1\n\nval y = 2", fencedCodeText(fence.node, fence.source))
        val closed = cache.update(source + "\n```\n\nafter")
        assertEquals(2, closed.size)
        assertEquals(MarkdownElementTypes.PARAGRAPH, closed.last().node.type)
    }
}
