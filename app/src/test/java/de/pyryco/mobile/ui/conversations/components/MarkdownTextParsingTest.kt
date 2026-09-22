package de.pyryco.mobile.ui.conversations.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser half of `MarkdownText` (#681), provable without a device: `org.jetbrains:markdown` is
 * pure Kotlin, and the project ships no Robolectric, so everything that does not need a rendered
 * frame belongs here and everything that does belongs in `MarkdownTextTest` under `androidTest`.
 *
 * Two kinds of test live here and they fail for different reasons. The helper tests fail when this
 * repo's logic is wrong. The parser-contract tests fail when the *library* changes what it hands the
 * renderer — they pin the measured AST facts the dispatcher's arms and its fallback rest on, so a
 * version bump that moves one of them reddens here rather than silently changing what a reply looks
 * like.
 */
class MarkdownTextParsingTest {
    private fun parse(source: String): ASTNode = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    private fun ASTNode.descendants(): Sequence<ASTNode> = sequenceOf(this) + children.asSequence().flatMap { it.descendants() }

    private fun ASTNode.firstOfType(type: org.intellij.markdown.IElementType): ASTNode? = descendants().firstOrNull { it.type == type }

    private fun ASTNode.childTypes(): List<org.intellij.markdown.IElementType> = children.map { it.type }

    // ---------------------------------------------------------------- alignments

    @Test
    fun `delimiter row reads each column's declared alignment`() {
        assertEquals(
            listOf(
                TableColumnAlignment.Start,
                TableColumnAlignment.Center,
                TableColumnAlignment.End,
                TableColumnAlignment.Start,
            ),
            parseTableAlignments("|:------|:-----:|------:|-------|"),
        )
    }

    /** GFM permits the delimiter row with or without its outer pipes; both describe two columns. */
    @Test
    fun `delimiter row without outer pipes reads the same columns`() {
        assertEquals(
            listOf(TableColumnAlignment.Start, TableColumnAlignment.End),
            parseTableAlignments(":---|---:"),
        )
    }

    @Test
    fun `delimiter row tolerates padding around each spec`() {
        assertEquals(
            listOf(TableColumnAlignment.Center, TableColumnAlignment.Start),
            parseTableAlignments("|  :-:  |  ---  |"),
        )
    }

    /**
     * A delimiter row describing fewer columns than the header is not an error — the renderer reads
     * past the end of this list and defaults, so the list is simply short.
     */
    @Test
    fun `delimiter row shorter than the header yields only what it declares`() {
        assertEquals(listOf(TableColumnAlignment.End), parseTableAlignments("|---:|"))
    }

    // ---------------------------------------------------------------- task marks

    @Test
    fun `check box token text distinguishes checked from unchecked`() {
        assertTrue(isCheckedTaskMark("[x] "))
        assertTrue(isCheckedTaskMark("[X] "))
        assertFalse(isCheckedTaskMark("[ ] "))
    }

    // ---------------------------------------------------------------- tilde flanking

    /** Offsets of every bare `~` token the parser left unclaimed, in source order. */
    private fun bareTildes(source: String): List<ASTNode> = parse(source).descendants().filter { it.type == GFMTokenTypes.TILDE }.toList()

    private fun canOpenClose(
        source: String,
        index: Int,
    ): Pair<Boolean, Boolean> {
        val tilde = bareTildes(source)[index]
        return tildeCanOpen(source, tilde.startOffset, tilde.endOffset) to
            tildeCanClose(source, tilde.startOffset, tilde.endOffset)
    }

    @Test
    fun `a single-tilde pair opens and closes`() {
        assertEquals(true to false, canOpenClose("~single~", 0))
        assertEquals(false to true, canOpenClose("~single~", 1))
    }

    /**
     * The hazard desktop's `remarkGfmSubset` docstring names and reports as non-reproducing: two
     * home-relative paths on one line. Both tildes can open and NEITHER can close, so nothing pairs
     * and the line renders verbatim. This is the test that would redden if the pairing were ever
     * simplified to "next tilde wins" — which would strike `/a ` in the middle of a path.
     */
    @Test
    fun `two home-relative paths on one line pair with nothing`() {
        val source = "run ~/a and ~/b now"
        assertEquals(true to false, canOpenClose(source, 0))
        assertEquals(true to false, canOpenClose(source, 1))
    }

    @Test
    fun `an unclosed double tilde pairs with nothing`() {
        val source = "~~unclosed"
        assertEquals(true to false, canOpenClose(source, 0))
        assertEquals(true to false, canOpenClose(source, 1))
    }

    // ------------------------------------------------- parser contract: the three constructs

    @Test
    fun `a pipe table parses into header, delimiter row and body rows`() {
        val source = "| A | B |\n|:--|--:|\n| a1 | b1 |\n| a2 | b2 |\n"
        val table = parse(source).firstOfType(GFMElementTypes.TABLE)
        assertNotNull("GFM should parse a pipe table into a TABLE node", table)
        val header = table!!.children.single { it.type == GFMElementTypes.HEADER }
        assertEquals(2, header.children.count { it.type == GFMTokenTypes.CELL })
        assertEquals(2, table.children.count { it.type == GFMElementTypes.ROW })
        // The delimiter row arrives as ONE separator token that is a direct child of TABLE — the
        // separators inside HEADER and ROW are the single `|` glyphs between cells.
        val delimiter =
            table.children.first { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        assertEquals("|:--|--:|", delimiter.getTextInNode(source).toString())
    }

    @Test
    fun `a task list item carries a check box token beside its bullet`() {
        val source = "- [ ] todo\n- [x] done\n- plain\n"
        val items =
            parse(source)
                .firstOfType(org.intellij.markdown.MarkdownElementTypes.UNORDERED_LIST)!!
                .children
                .filter { it.type == org.intellij.markdown.MarkdownElementTypes.LIST_ITEM }
        assertEquals(3, items.size)
        val marks =
            items.map { item ->
                item.children
                    .firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
                    ?.getTextInNode(source)
                    ?.toString()
            }
        assertEquals(listOf("[ ] ", "[x] ", null), marks)
    }

    @Test
    fun `a double tilde span parses into a strikethrough node`() {
        val node = parse("~~struck~~").firstOfType(GFMElementTypes.STRIKETHROUGH)
        assertNotNull(node)
        assertEquals("~~struck~~", node!!.getTextInNode("~~struck~~").toString())
    }

    /**
     * The measurement the render-time pairing exists for: this library's
     * `StrikeThroughDelimiterParser` takes no single-tilde option and emits no node for `~x~`, where
     * desktop's micromark extension does (its `singleTilde` default is on). The day a library bump
     * starts producing a STRIKETHROUGH here, this test reddens and the pairing can be deleted.
     */
    @Test
    fun `a single tilde span parses into bare tilde tokens, not a strikethrough node`() {
        val source = "~single~"
        assertEquals(null, parse(source).firstOfType(GFMElementTypes.STRIKETHROUGH))
        assertEquals(2, bareTildes(source).size)
    }

    // ------------------------------------------------- parser contract: AC4, what must NOT change

    /**
     * A bare URL becomes a dedicated node kind under GFM where CommonMark left plain text. It is a
     * CHILDLESS LEAF, which is the whole reason the renderer's fallback emits its source characters
     * rather than a link — there is no arm for it and no children to recurse into.
     */
    @Test
    fun `a bare URL parses as a childless autolink token`() {
        val source = "Visit https://example.com/path now"
        val autolink = parse(source).firstOfType(GFMTokenTypes.GFM_AUTOLINK)
        assertNotNull(autolink)
        assertTrue("the fallback depends on this leaf having no children", autolink!!.children.isEmpty())
        assertEquals("https://example.com/path", autolink.getTextInNode(source).toString())
    }

    /**
     * `$…$` becomes an INLINE_MATH element under GFM, which HAS children — so the renderer recurses
     * and each leaf appends its own source text. What this pins is that the concatenation is lossless:
     * the dollars are their own tokens, so the span rebuilds verbatim rather than losing its markers.
     */
    @Test
    fun `an inline math span rebuilds verbatim from its leaf tokens`() {
        val source = "Cost is \$20 and \$30 per item"
        val math = parse(source).firstOfType(GFMElementTypes.INLINE_MATH)
        assertNotNull("GFM's MathParser claims this span; CommonMark left it as text", math)
        val rebuilt =
            math!!
                .descendants()
                .filter { it.children.isEmpty() }
                .joinToString("") { it.getTextInNode(source).toString() }
        assertEquals(math.getTextInNode(source).toString(), rebuilt)
    }

    /** Raw HTML is untouched by the flavour switch: still a token pair, still rendered as text. */
    @Test
    fun `raw html stays a plain tag token under the GFM flavour`() {
        val source = "<b>bold</b>"
        val tag = parse(source).firstOfType(org.intellij.markdown.MarkdownTokenTypes.HTML_TAG)
        assertNotNull(tag)
        assertTrue(tag!!.children.isEmpty())
    }

    // ------------------------------------------------- parser contract: ATX headings (#768)

    /**
     * The nesting the heading trim rests on. A heading's DIRECT children are the marker and one
     * content node — the marker whitespace is not among them, which is why filtering at this level
     * (as `HeadingBlock` did from #129 until #768) never saw it and every heading rendered a
     * leading space.
     */
    @Test
    fun `an atx heading nests its content one level inside ATX_CONTENT`() {
        val source = "# Heading with text"
        val heading = parse(source).firstOfType(MarkdownElementTypes.ATX_1)!!
        assertEquals(
            listOf(MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.ATX_CONTENT),
            heading.childTypes(),
        )
        val content = heading.children.single { it.type == MarkdownTokenTypes.ATX_CONTENT }
        assertEquals(" Heading with text", content.getTextInNode(source).toString())
        assertEquals(MarkdownTokenTypes.WHITE_SPACE, content.childTypes().first())
    }

    /**
     * Why the fix trims the EDGES and does not simply extend the old filter one level down:
     * `ATX_CONTENT`'s interior `WHITE_SPACE` tokens are the real spaces between words, so dropping
     * every one of them would render `` `code`andbold ``.
     */
    @Test
    fun `interior whitespace inside ATX_CONTENT is the spacing between words`() {
        val source = "## `code` and **bold**"
        val content =
            parse(source)
                .firstOfType(MarkdownElementTypes.ATX_2)!!
                .children
                .single { it.type == MarkdownTokenTypes.ATX_CONTENT }
        assertEquals(
            listOf(
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownElementTypes.CODE_SPAN,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownTokenTypes.TEXT,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownElementTypes.STRONG,
            ),
            content.childTypes(),
        )
    }

    /**
     * The closed form. The closing `##` is the heading's own second marker token and never reaches
     * `ATX_CONTENT`, but the space before it does — so this form rendered `" Trailing "`, padded at
     * both ends, and both ends come off together.
     */
    @Test
    fun `a closed atx heading keeps its trailing space inside ATX_CONTENT`() {
        val source = "## Trailing ##"
        val heading = parse(source).firstOfType(MarkdownElementTypes.ATX_2)!!
        val content = heading.children.single { it.type == MarkdownTokenTypes.ATX_CONTENT }
        assertEquals(" Trailing ", content.getTextInNode(source).toString())
        assertEquals(MarkdownTokenTypes.WHITE_SPACE, content.childTypes().last())
    }

    /**
     * A marker-only heading has NO content child at all. A lookup that assumed one was present
     * would throw, in a renderer this repo documents as total.
     */
    @Test
    fun `a marker-only heading has no ATX_CONTENT child`() {
        val heading = parse("##").firstOfType(MarkdownElementTypes.ATX_2)!!
        assertEquals(listOf(MarkdownTokenTypes.ATX_HEADER), heading.childTypes())
    }
}
