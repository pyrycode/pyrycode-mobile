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
        listOf("| A | B |\n", "| A | B |\n| --- |", "| A | B |\n| --- | :").forEach {
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

    @Test fun listsRetainGroupingAcrossEveryPartialMarkerOrRule() {
        listOf(
            "1. first\n\n2. second",
            "1. first\n\n23. second",
            "- first\n\n- second",
            "* first\n\n* * *tail*",
            "- first\n\n- - -tail",
        ).forEach { source ->
            val cache = StreamingMarkdownCache()
            for (length in 1..source.length) {
                val prefix = source.take(length)
                val actual = cache.update(prefix)
                val expected =
                    MarkdownParser(MarkdownFlavour)
                        .buildMarkdownTreeFromString(prefix)
                        .children
                        .filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
                assertEquals(prefix, expected.map { it.type }, actual.map { it.node.type })
                assertEquals(
                    prefix,
                    expected.map { it.getTextInNode(prefix).toString() },
                    actual.map { it.node.getTextInNode(it.source).toString() },
                )
            }
        }
    }

    @Test fun unsupportedLiteralRegionsKeepTheirPunctuation() {
        listOf("https://example.com/~user", "<em title=\"~user\">x</em>", "<em title=\"**user\">x</em>", "\$`literal`").forEach {
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(it)
            val paragraph = root.children.single { node -> node.type == MarkdownElementTypes.PARAGRAPH }
            assertEquals(it, inlineText(paragraph.children, it), text(it))
        }
    }

    @Test fun blankLineClosesPipeProseBeforeItCanBeFrozen() {
        val source = "left | right\n\nnext"
        assertEquals("left | right\nnext", text(source))
        val cache = StreamingMarkdownCache()
        val block = cache.update(source).first()
        assertEquals(null, block.pending.blockText(block.node))
        assertSame(block, cache.update(source + " grows").first())
    }

    @Test fun escapedAndCodeTrailingPipesRemainCellContent() {
        mapOf(
            "| A | B\\|\n" to "A B\\|",
            "| A | `B|" to "A B|",
            "| A | `B|`\n" to "A B|",
            "| A | `B\\`| C |\n" to "A B\\ C",
        ).forEach { (source, expected) ->
            assertEquals(source, expected, text(source))
        }
    }

    @Test fun separatorWithoutOuterPipesIsPendingEvenWhenParsedAsSetext() {
        listOf("| A | B |\n-", "| A | B |\n---", "| A | B |\n--- | :").forEach { source ->
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
            assertEquals(source, "A B", PendingMarkdown(source, root).blockText(root.children.first { it.children.isNotEmpty() }))
        }
        val source = "| A | B |\n--- | ---\n"
        val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
        assertEquals(null, PendingMarkdown(source, root).blockText(root.children.first { it.children.isNotEmpty() }))
    }

    @Test fun pendingLinkMatchesBracketsOutsideCodeAndEscapes() {
        mapOf(
            "[text `a]b`](https://exa" to "text `a]b`",
            "[text [nested] label](https://exa" to "text [nested] label",
            "[text \\] label](https://exa" to "text \\] label",
        ).forEach { (source, expected) ->
            assertEquals(source, expected, text(source))
        }
    }

    @Test fun unmatchedBracketsHaveLinearScanWorkAndRegionLocalStorage() {
        listOf(32, 64, 128, 512, 2048).forEach { length ->
            val source = "[".repeat(length)
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
            val pending = PendingMarkdown(source, root)
            assertEquals(length, pending.scannedCharacters)
            assertEquals(length, pending.protectionCapacity)
            assertEquals(source, streamingInlineText(root.children.single().children, source, pending))
        }
        val list = (1..80).joinToString("\n\n") { "- item [$it" }
        val listRoot = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(list)
        val listPending = PendingMarkdown(list, listRoot)
        assertTrue(listPending.scannedCharacters <= list.length)
        assertTrue(listPending.protectionCapacity <= list.length)
        val table = "| A | B |\n| --- | --- |\n" + (1..80).joinToString("\n") { "| [$it | [$it |" }
        val tableRoot = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(table)
        val tablePending = PendingMarkdown(table, tableRoot)
        assertTrue(tablePending.scannedCharacters <= table.length)
        assertTrue(tablePending.protectionCapacity <= table.length)
    }

    @Test fun invalidSeparatorClosedByNewlineCannotRemainAPendingHeader() {
        listOf("| A | B |\n---\nnext", "| A | B |\n| --- |\nnext").forEach { source ->
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
            val first = root.children.first { it.children.isNotEmpty() }
            assertEquals(source, null, PendingMarkdown(source, root).blockText(first))
        }
    }

    @Test fun actualTablesAtEofReleaseWithEveryOuterPipeCombinationAndStayMutable() {
        for (header in listOf("| A | B |", "A | B", "| A | B", "A | B |")) {
            for (separator in listOf("--- | ---", "| --- | ---", "--- | --- |", "| --- | --- |", "--- | --")) {
                val source = "$header\n$separator"
                val cache = StreamingMarkdownCache()
                for (length in 1..source.length) cache.update(source.take(length))
                val table = cache.update(source).single()
                assertEquals(source, GFMElementTypes.TABLE, table.node.type)
                assertEquals(source, null, table.pending.blockText(table.node))
                val extended = cache.update("$source\n| one | two |").single()
                assertEquals(source, GFMElementTypes.TABLE, extended.node.type)
                assertTrue(extended.node.getTextInNode(extended.source).contains("one"))
            }
        }
    }

    @Test fun actualTableGrammarWinsOverCodeAwarePendingPipeCountingAtEof() {
        val source = "| `A|B` |\n| --- | --- |"
        val cache = StreamingMarkdownCache()
        for (length in 1..source.length) cache.update(source.take(length))
        val block = cache.update(source).single()
        assertEquals(GFMElementTypes.TABLE, block.node.type)
        assertEquals(null, block.pending.blockText(block.node))
        val header = block.node.children.single { it.type == GFMElementTypes.HEADER }
        val cells = header.children.filter { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }
        assertEquals(listOf("`A", "B`"), cells.map { streamingInlineText(it.trimmedContent(), block.source, block.pending) })
        assertEquals(null, cache.update("$source\n").single().let { it.pending.blockText(it.node) })
    }

    @Test fun closedTableBodyCellsKeepSharedRendererLiteralDelimiters() {
        for (delimiter in listOf("`", "**", "__", "*", "_", "~~", "~")) {
            for (ending in listOf("", "\n")) {
                val source = "| A | B |\n| --- | --- |\n| ${delimiter}C|D$delimiter |$ending"
                val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
                val pending = PendingMarkdown(source, root)
                val row =
                    root.children
                        .single { it.type == GFMElementTypes.TABLE }
                        .children
                        .single { it.type == GFMElementTypes.ROW }
                val cells = row.children.filter { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }
                assertEquals(source, listOf("${delimiter}C", "D$delimiter"), cells.map { inlineText(it.trimmedContent(), source) })
                assertEquals(
                    source,
                    cells.map {
                        inlineText(it.trimmedContent(), source)
                    },
                    cells.map { streamingInlineText(it.trimmedContent(), source, pending) },
                )
            }
        }
    }

    @Test fun onlyTheGrowingFinalTableCellReceivesPendingMasks() {
        for ((arrived, expected) in listOf("**bold" to "bold", "`code" to "code", "[text](https://exa" to "text")) {
            val source = "| A | B |\n| --- | --- |\n| **literal | $arrived"
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)
            val pending = PendingMarkdown(source, root)
            val row =
                root.children
                    .single { it.type == GFMElementTypes.TABLE }
                    .children
                    .single { it.type == GFMElementTypes.ROW }
            val cells = row.children.filter { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }
            assertEquals(source, listOf("**literal", expected), cells.map { streamingInlineText(it.trimmedContent(), source, pending) })
        }
    }

    @Test fun pendingCodeInsideFormattingKeepsLiteralClosingPunctuationUntilCodeCloses() {
        for (delimiter in listOf("**", "*", "_", "__", "~~", "~")) {
            val prefix = "${delimiter}foo `bar$delimiter"
            assertEquals(prefix, "foo bar$delimiter", text(prefix))
            val closedCode = "$prefix baz`"
            assertEquals(closedCode, "foo bar$delimiter baz", text(closedCode))
            val complete = "$closedCode$delimiter"
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(complete)
            assertEquals(complete, "foo bar$delimiter baz", text(complete))
            assertEquals(inlineText(root.children.single().children, complete), text(complete))
        }
        assertEquals("lead a ~bar~", text("lead `a ~bar~"))
    }

    @Test fun escapedBangBeforePendingLinkUsesBackslashParityAndRetainsLiteralPrefix() {
        for (slashes in 0..4) {
            val prefix = "\\".repeat(slashes) + "!"
            val source = "$prefix[text](https://exa"
            val prefixRoot = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(prefix)
            val literalPrefix = inlineText(prefixRoot.children.single().children, prefix)
            assertEquals(source, if (slashes % 2 == 1) literalPrefix + "text" else source, text(source))
            val complete = "$source)"
            val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(complete)
            assertEquals(complete, inlineText(root.children.single().children, complete), text(complete))
        }
    }
}
