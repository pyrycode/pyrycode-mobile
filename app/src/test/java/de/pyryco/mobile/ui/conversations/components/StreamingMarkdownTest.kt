package de.pyryco.mobile.ui.conversations.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * #1766's freeze rule, proved the way the streaming renderers it borrows from prove theirs: replay every prefix of
 * a reply through one [StreamingMarkdownCache] and require the reused blocks plus the re-parsed tail to equal a
 * full parse of that prefix, node for node at absolute offsets. A frozen block must also stay the same instance
 * for the rest of the reply, which is what lets Compose skip it.
 */
class StreamingMarkdownTest {
    private fun parse(source: String): ASTNode = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)

    private fun signature(
        node: ASTNode,
        offset: Int,
    ): String =
        "${node.type}@${offset + node.startOffset}-${offset + node.endOffset}" +
            if (node.children.isEmpty()) "" else node.children.joinToString(",", "(", ")") { signature(it, offset) }

    /** Replays [document] one character at a time and returns how many top-level blocks ended up frozen. */
    private fun replay(
        name: String,
        document: String,
    ): Int {
        val cache = StreamingMarkdownCache()
        var frozen = emptyList<StreamingMarkdownBlock>()
        var frozenCount = 0
        for (end in 0..document.length) {
            val prefix = document.substring(0, end)
            val snapshot = cache.update(prefix)
            val expected = parse(prefix).children.map { signature(it, 0) }
            val actual = snapshot.blocks.map { signature(it.node, it.offset) }
            assertEquals("$name: prefix ${prefix.quoted()}", expected, actual)
            frozen.forEachIndexed { index, block ->
                assertSame("$name: frozen block $index was replaced at prefix ${prefix.quoted()}", block, snapshot.blocks[index])
            }
            frozen = snapshot.blocks.take(snapshot.frozenCount)
            frozenCount = frozen.count { it.isContent }
        }
        return frozenCount
    }

    private fun String.quoted(): String = "\"" + replace("\n", "\\n") + "\""

    // ------------------------------------------------------------------ the ticket's named cases

    @Test
    fun endOfInputInterrupters_afterParagraphListOrQuote_replayEveryPrefix() {
        val containers = listOf("Some paragraph", "- an item", "1. an item", "> a quote")
        val interrupters =
            listOf(
                "# heading",
                "#tag continues",
                "***",
                "***tail* continues",
                "```\ncode\n```",
                "```kotlin\nval x = 1\n```",
                "<div>",
                "<dividend> continues",
                "</div>",
                "</divx> continues",
                "<pre>\ntext\n</pre>",
                "<prefix> continues",
            )
        containers.forEach { container ->
            interrupters.forEach { interrupter ->
                val document = "$container\n$interrupter\nafter\n\nlast paragraph\n"
                replay("interrupter", document)
            }
        }
    }

    @Test
    fun lists_continuingItemsAndRuleLikeLines_replayEveryPrefix() {
        assertTrue(replay("ordered", "1. first\n\n2. second\n\nafter\n\nend\n") >= 1)
        assertTrue(replay("rule-like item", "* first\n\n* * *tail*\n\nafter\n\nend\n") >= 1)
        replay("rule after list", "* first\n\n* * *\n\nafter\n")
        replay("nested", "- a\n  - b\n\n  c\n- d\n\nafter\n")
    }

    @Test
    fun lazyAndSetext_replayEveryPrefix() {
        replay("lazy heading-like", "> q\n#tag\n\nafter\n")
        replay("heading after quote", "> q\n# h\n\nafter\n")
        replay("setext grows into text", "Title\n=x\n\nafter\n")
        replay("setext", "Title\n===\n\nafter\n\nend\n")
    }

    @Test
    fun definitions_swallowLaterBlocks_andStayMutableUntilDecided() {
        replay("title swallows a heading", "Intro\n\n[a]: /u \"t\n# h\nx\"\n\nafter\n\nend\n")
        replay("inside a list item", "- [a]: /u \"t\n# h\nx\"\n\nafter\n")
        replay("inside a quote", "> [a]: /u \"t\n# h\nx\"\n\nafter\n")
        replay("label across a blank line", "[a\n\nb]: /u\n\nafter\n\nend\n")
        replay("title never closes", "[a]: /u \"t\n# h\n\nafter\n\nend\n")
        // Found by the fuzz: the label crosses blank lines, so only a blank line after its `]:` settles it.
        replay("label closes past a blank line", "[b\n<dividend>\n| - |\n# h\n\n\nc]: /u\n\nafter\n\nend\n")

        // The heading may still be swallowed, so neither block freezes yet.
        val cache = StreamingMarkdownCache()
        val held = cache.update("[a]: /u \"t\n# h\nmore\n")
        assertEquals(0, held.frozenCount)
        // A blank line ends any title, so normal freezing resumes.
        val released = cache.update("[a]: /u \"t\n# h\nmore\n\nafter\n")
        assertTrue(released.frozenCount > 0)
    }

    @Test
    fun fences_closingFenceThatGrowsStaysCode_replayEveryPrefix() {
        replay("closing fence grows", "```\ncode\n```x\nmore\n```\n\nafter\n")
        replay("blank lines inside", "```\ncode\n\n\nmore\n```\nprose after\n\nend\n")
        val cache = StreamingMarkdownCache()
        val open = cache.update("Intro\n\n```\ncode\n\n\nstill code")
        assertEquals(
            MarkdownElementTypes.CODE_FENCE,
            open.blocks
                .last { it.isContent }
                .node.type,
        )
    }

    @Test
    fun pendingHeaders_replayEveryPrefix() {
        replay("code pipe", "| `A|B` |\n| --- | --- |\n| 1 | 2 |\n\nafter\n")
        replay("quoted", "> | A | B |\n> | --- | --- |\n> | 1 | 2 |\n\nafter\n")
        replay("setext first", "| A | B |\n--- | ---\n| 1 | 2 |\n\nafter\n")
        replay("not a table", "left | right\n\nnext\n\nend\n")
    }

    @Test
    fun pendingInline_replayEveryPrefix() {
        replay("bold around code", "**foo `bar**` tail\n\nnext\n")
        replay("link", "See [text](https://example.com/a) now\n\nnext\n")
        replay("escaped bang", "\\![text](https://example.com) x\n\nnext\n")
        replay("trailing ambiguity", "see ~/path\n\nnext\n")
    }

    /**
     * Desktop's parser let a table header rejoin the paragraph before it (pyrycode-desktop#1751). In 0.7.3 a
     * table cannot interrupt a paragraph, a list item or a quote, so the pipe lines stay in that block and the
     * plain freeze rule holds.
     */
    @Test
    fun tableDirectlyAfterParagraphListItemOrQuote_replayEveryPrefix() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n"
        listOf("Some paragraph text\n", "- item\n", "> quote line\n").forEach { lead ->
            replay("table after ${lead.trim()}", lead + table + "\nafter\n\nend\n")
        }
    }

    @Test
    fun realisticReply_freezesCompletedBlocksAndParsesOnlyTheTail() {
        val document =
            "# Plan\n\nFirst **bold** and `code`.\n\n- one\n- two\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\n" +
                "| Key | Value |\n| --- | --- |\n| a | b |\n\n> quoted\n\nDone [link](https://example.com).\n"
        assertTrue(replay("reply", document) >= 6)
        val parsed = mutableListOf<String>()
        val cache = StreamingMarkdownCache { parsed += it }
        val head = document.substringBefore("> quoted")
        cache.update(head)
        cache.update(head + "> quo")
        // Everything before the table was frozen by the first update, so only the tail is parsed again.
        assertTrue(parsed.last().length < 60)
        assertTrue(parsed.last().startsWith("| Key"))
    }

    // ------------------------------------------------------------------ seeded fuzz

    @Test
    fun seededFuzz_blockStarterLines_replayEveryPrefix() {
        val lines =
            listOf(
                "para text",
                "more words",
                "Title",
                "# h",
                "## h2",
                "#tag",
                "- item",
                "* item",
                "+ item",
                "1. one",
                "2) two",
                "  - nested",
                "    code",
                "> quote",
                ">",
                "> - quoted item",
                "```",
                "```kt",
                "~~~",
                "***",
                "---",
                "* * *",
                "===",
                "=x",
                "| A | B |",
                "| --- | --- |",
                "| 1 | 2 |",
                "--- | ---",
                "<div>",
                "</div>",
                "<dividend>",
                "<pre>",
                "</pre>",
                "<!-- c -->",
                "[a]: /u",
                "[a]: /u \"t",
                "x\"",
                "[b",
                "c]: /v",
                "> [a]: /u \"t",
                "- [a]: /u \"t",
                "  [c]: /w",
                "| x | y | z |",
                "| - |",
                "- [ ] task",
                "**bold",
                "text**",
                "",
                "",
                "",
            )
        val random = Random(1766)
        var frozenTotal = 0
        repeat(2000) { round ->
            val document =
                (0 until random.nextInt(3, 11)).joinToString("\n") { lines[random.nextInt(lines.size)] } +
                    if (random.nextBoolean()) "\n" else ""
            frozenTotal += replay("fuzz $round", document)
        }
        assertTrue("the fuzz must exercise freezing, not only the tail", frozenTotal > 1000)
    }

    // ------------------------------------------------------------------ the tail's presentation

    private fun tail(source: String): StreamingTail = StreamingMarkdownCache().update(source).tail

    @Test
    fun trailingLeaf_followsTheLastInlineText_andNotCode() {
        assertEquals(MarkdownElementTypes.PARAGRAPH, tail("a\n\n- item **b").leaf?.type)
        assertNull(tail("text\n\n```\ncode").leaf)
        assertNull(tail("- a\n- ").leaf)
        assertEquals(
            GFMElementTypes.TABLE,
            StreamingMarkdownCache()
                .update("| A |\n| - |\n| x")
                .blocks
                .last()
                .node.type,
        )
        assertNotNull(tail("| A |\n| - |\n| x").leaf)
    }

    @Test
    fun probe_isAbsentWhenNothingNewPairs() {
        listOf("2 * 3", "snake_case_name", "https://example.com/~user", "plain words", "done **bold**", "\\*\\*x")
            .forEach { assertNull(it, tail(it).probe) }
        // A paragraph closed by a blank line is final, so its literal opener stays literal.
        assertNull(tail("**open\n\nnext").probe)
    }

    @Test
    fun probe_closesInnermostFirst_andCountsItsParses() {
        val probe = checkNotNull(tail("**foo `bar**").probe)
        assertEquals("**foo `bar**`**", probe.source)
        assertEquals(12 until 15, probe.synthetic)
        val link = checkNotNull(tail("a [text](https://exa").probe)
        assertEquals(")", link.source.substring(link.synthetic.first, link.synthetic.last + 1))
        val title = checkNotNull(tail("[t](u \"ti").probe)
        assertEquals("\")", title.source.substring(title.synthetic.first, title.synthetic.last + 1))
    }

    @Test
    fun probe_isBoundedOnAPathologicalLeaf() {
        val leaf = "*a _b `c ~d ".repeat(40) + "[x](y"
        val probe = tail(leaf).probe
        // Whatever it found, it stopped within its budget and inserted only closers.
        if (probe != null) assertEquals(leaf, probe.source.removeRange(probe.synthetic))
    }

    @Test
    fun pendingHeader_needsAPipeLineAndAtMostAPartialDelimiterRow() {
        assertEquals("A B", tail("| A | B |\n").header?.text)
        assertEquals("A B", tail("| A | B |").header?.text)
        assertEquals("A B", tail("| A | B |\n| -").header?.text)
        assertEquals("A B", tail("| A | B |\n---").header?.text)
        assertEquals("A B", tail("> | A | B |").header?.text)
        assertEquals("A B", tail("> | A | B |\n> ---").header?.text)
        assertEquals("A B", tail("- | A | B |\n  ---").header?.text)
        assertEquals(
            2,
            tail("| `A|B` |")
                .header
                ?.text
                ?.split(' ')
                ?.size,
        )
        assertNull(tail("left | right\n\nnext").header)
        assertNull(tail("| A | B |\n| x").header)
        assertNull(tail("| A | B |\n| - |\n").header)
        assertNull(tail("no pipe here").header)
        // Once the delimiter row matches, the parser already draws the real table.
        assertEquals(
            GFMElementTypes.TABLE,
            StreamingMarkdownCache()
                .update("| A | B |\n--- | ---")
                .blocks
                .last()
                .node.type,
        )
    }
}
