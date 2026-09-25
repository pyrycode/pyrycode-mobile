package de.pyryco.mobile.ui.conversations.components

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.LinkGeneratingProvider
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

// The heading levels the plain-text copy strips. The renderer styles only the first three and shows the
// rest as source, but the copy promises no heading markers at any level.
private val AtxHeadings =
    setOf(
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
    )

private val ListItemMarkers =
    setOf(
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.EOL,
    )

private val QuoteMarkers = setOf(MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL)

private const val NESTED_INDENT = "  "

/**
 * [markdown] as the reader shows it, without its syntax (#1067): heading and quote markers, emphasis, code
 * and strike delimiters, list markers and link targets are gone, and code blocks keep their code verbatim.
 * Inline text comes from [inlineText], the renderer's own walk, so what the screen hides the copy drops too.
 * Blocks are separated by a blank line, list items by a line break, table cells by a tab.
 */
internal fun markdownPlainText(markdown: String): String {
    val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
    return blocksText(root.children, markdown).joinToString("\n\n")
}

private fun blocksText(
    nodes: List<ASTNode>,
    source: String,
): List<String> = nodes.map { blockText(it, source) }.filter { it.isNotEmpty() }

private fun blockText(
    node: ASTNode,
    source: String,
): String =
    when (node.type) {
        in AtxHeadings ->
            node.children
                .firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
                ?.let { inlineText(it.trimmedContent(), source) }
                .orEmpty()
        // A quote's continuation line keeps its `>` and the space after it inside the paragraph.
        MarkdownElementTypes.PARAGRAPH ->
            inlineText(
                node.children.filterIndexed { index, child ->
                    child.type != MarkdownTokenTypes.BLOCK_QUOTE &&
                        !(
                            child.type == MarkdownTokenTypes.WHITE_SPACE &&
                                node.children.getOrNull(index - 1)?.type == MarkdownTokenTypes.BLOCK_QUOTE
                        )
                },
                source,
            ).trim()
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> listText(node, source)
        MarkdownElementTypes.BLOCK_QUOTE ->
            blocksText(node.children.filter { it.type !in QuoteMarkers }, source).joinToString("\n\n")
        MarkdownElementTypes.CODE_FENCE -> fencedCodeText(node, source)
        MarkdownElementTypes.CODE_BLOCK -> indentedCodeText(node, source)
        GFMElementTypes.TABLE -> tableText(node, source)
        else -> node.getTextInNode(source).toString().trim()
    }

/** One line per item and no marker; an item's nested list is indented under it. */
private fun listText(
    list: ASTNode,
    source: String,
): String =
    list.children
        .filter { it.type == MarkdownElementTypes.LIST_ITEM }
        .joinToString("\n") { item ->
            item.children
                .filter { it.type !in ListItemMarkers }
                .map { child ->
                    val text = blockText(child, source)
                    if (child.type == MarkdownElementTypes.UNORDERED_LIST || child.type == MarkdownElementTypes.ORDERED_LIST) {
                        text.lines().joinToString("\n") { NESTED_INDENT + it }
                    } else {
                        text
                    }
                }.filter { it.isNotEmpty() }
                .joinToString("\n")
        }

/** Header then body rows, within the renderer's own table bounds; the delimiter row is syntax. */
private fun tableText(
    table: ASTNode,
    source: String,
): String {
    val rows =
        (
            table.children.filter { it.type == GFMElementTypes.HEADER } +
                table.children.filter { it.type == GFMElementTypes.ROW }
        ).take(MAX_TABLE_ROWS)
            .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    val columnCount = (rows.firstOrNull()?.size ?: 0).coerceAtMost(MAX_TABLE_COLUMNS)
    return rows.joinToString("\n") { cells ->
        cells.take(columnCount).joinToString("\t") { inlineText(it.trimmedContent(), source) }
    }
}

/**
 * [markdown] as HTML (#1067), through `org.intellij.markdown`'s generator over the same parse and flavour
 * [MarkdownText] uses. The HTML leaves the app on the clipboard, so the note's own markup never passes
 * through: raw HTML is written as escaped text, a link keeps its `href` only for [isSafeLinkScheme]'s schemes
 * and is otherwise its text, an image is its alt text, and a code fence drops its info string. No other
 * attribute carries note text.
 */
internal fun markdownHtml(markdown: String): String {
    val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
    val providers =
        MarkdownFlavour
            .createHtmlGeneratingProviders(LinkMap.buildLinkMap(root, markdown), null)
            .mapValues { (type, provider) ->
                if (provider is LinkGeneratingProvider) {
                    AllowlistedLinkProvider(
                        provider,
                        image = type == MarkdownElementTypes.IMAGE,
                    )
                } else {
                    provider
                }
            }.toMutableMap<IElementType, GeneratingProvider>()
    providers[MarkdownElementTypes.HTML_BLOCK] = RawHtmlAsTextProvider(block = true)
    providers[MarkdownTokenTypes.HTML_BLOCK_CONTENT] = RawHtmlAsTextProvider(block = false)
    providers[MarkdownTokenTypes.HTML_TAG] = RawHtmlAsTextProvider(block = false)
    providers[MarkdownElementTypes.AUTOLINK] = AutolinkProvider
    providers[GFMTokenTypes.GFM_AUTOLINK] = AutolinkProvider
    providers[MarkdownElementTypes.CODE_FENCE] = CodeFenceProvider
    return HtmlGenerator(markdown, root, providers, includeSrcPositions = false).generateHtml()
}

/**
 * [text] escaped for both element content and a double-quoted attribute value. With [ampersand] false a `&`
 * is left as written: a link destination arrives from the library with its entities already encoded, and
 * escaping them again would corrupt the URL. A `&` cannot end an attribute; only the quote, always escaped, can.
 */
private fun escapeHtml(
    text: CharSequence,
    ampersand: Boolean = true,
): String =
    buildString(text.length) {
        text.forEach { character ->
            when (character) {
                '&' -> append(if (ampersand) "&amp;" else "&")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

/** The library's link, reference-link or image provider behind one href allowlist; titles are dropped. */
private class AllowlistedLinkProvider(
    private val inner: LinkGeneratingProvider,
    private val image: Boolean,
) : LinkGeneratingProvider(baseURI = null) {
    override fun getRenderInfo(
        text: String,
        node: ASTNode,
    ): RenderInfo? = inner.getRenderInfo(text, node)

    override fun renderLink(
        visitor: HtmlGenerator.HtmlGeneratingVisitor,
        text: String,
        node: ASTNode,
        info: RenderInfo,
    ) {
        // The destination has been through the library's normalisation, so an entity-encoded scheme is
        // judged decoded.
        val destination = info.destination.toString()
        val linked = !image && isSafeLinkScheme(destination)
        if (linked) visitor.consumeTagOpen(node, "a", "href=\"${escapeHtml(destination, ampersand = false)}\"")
        labelProvider.processNode(visitor, text, info.label)
        if (linked) visitor.consumeTagClose("a")
    }
}

/** `<https://…>` and a GFM bare URL: a link for an allowed scheme, otherwise the address as text. */
private object AutolinkProvider : GeneratingProvider {
    override fun processNode(
        visitor: HtmlGenerator.HtmlGeneratingVisitor,
        text: String,
        node: ASTNode,
    ) {
        val destination =
            node
                .getTextInNode(text)
                .toString()
                .removePrefix("<")
                .removeSuffix(">")
        val escaped = escapeHtml(destination)
        if (isSafeLinkScheme(destination)) {
            visitor.consumeHtml("<a href=\"$escaped\">$escaped</a>")
        } else {
            visitor.consumeHtml(escaped)
        }
    }
}

/** Raw HTML from the note, written as the text it is. */
private class RawHtmlAsTextProvider(
    private val block: Boolean,
) : GeneratingProvider {
    override fun processNode(
        visitor: HtmlGenerator.HtmlGeneratingVisitor,
        text: String,
        node: ASTNode,
    ) {
        val escaped = escapeHtml(node.getTextInNode(text))
        visitor.consumeHtml(if (block) "<p>$escaped</p>" else escaped)
    }
}

/** A fence as `<pre><code>` with the code escaped; its info string never reaches a `class` attribute. */
private object CodeFenceProvider : GeneratingProvider {
    override fun processNode(
        visitor: HtmlGenerator.HtmlGeneratingVisitor,
        text: String,
        node: ASTNode,
    ) {
        visitor.consumeHtml("<pre><code>${escapeHtml(fencedCodeText(node, text))}</code></pre>")
    }
}

/** [text] within the clipboard's bound, [MAX_CLIPBOARD_CHARS]. */
internal fun boundClipText(text: String): String = text.take(MAX_CLIPBOARD_CHARS)

/** [html] within [MAX_CLIPBOARD_CHARS], cut after the last `>` inside the bound so no tag or entity is split. */
internal fun boundClipHtml(html: String): String =
    if (html.length <= MAX_CLIPBOARD_CHARS) html else html.substring(0, html.lastIndexOf('>', MAX_CLIPBOARD_CHARS - 1) + 1)
