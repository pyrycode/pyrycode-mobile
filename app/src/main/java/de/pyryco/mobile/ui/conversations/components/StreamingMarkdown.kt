package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.Immutable
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerBlock
import org.intellij.markdown.parser.MarkdownParser

/*
 * The streaming half of the reply renderer (#1766). The parser decides every block boundary; this file only
 * decides when a boundary the parser placed can no longer move, and how the one growing inline leaf at the end
 * is presented while its constructs are still open.
 *
 * The method is borrowed from the mature streaming renderers rather than their code, since no Kotlin one exists:
 * re-parse only the mutable tail, reuse unchanged blocks by identity, prove the freeze rule by replaying every
 * prefix against a full parse, and probe the unfinished tail with closers the reader never sees.
 */

/**
 * One root child of a streamed reply and the [source] it was parsed from. [offset] is where [source] starts in the
 * whole reply, so [key] is the node's absolute position. A frozen block keeps the same instance for the rest of the
 * reply, which is what lets Compose skip it.
 */
@Immutable
internal class StreamingMarkdownBlock(
    val offset: Int,
    val node: ASTNode,
    val source: String,
) {
    val key: Int get() = offset + node.startOffset

    /** Root-level line breaks and indentation render nothing, so only real blocks are drawn. */
    val isContent: Boolean get() = node.type != MarkdownTokenTypes.EOL && node.type != MarkdownTokenTypes.WHITE_SPACE
}

/**
 * Every root child of the reply in order. The first [frozenCount] are reused from earlier updates; the rest come
 * from this update's parse of the tail. [tail] is the growing last block's temporary presentation.
 */
@Immutable
internal class StreamingMarkdownSnapshot(
    val blocks: List<StreamingMarkdownBlock>,
    val frozenCount: Int,
    val tail: StreamingTail,
)

/**
 * The growing last block's presentation. [leaf] is the inline leaf the caret follows, or null when the block ends
 * in something that is not inline text, such as a code fence. [probe] replaces [leaf]'s inline content while an
 * opener in it is still unpaired, and [header] replaces a pending table header with its plain cell text.
 */
@Immutable
internal class StreamingTail(
    val leaf: ASTNode? = null,
    val probe: InlineProbe? = null,
    val header: PendingHeader? = null,
) {
    /** The node whose text the caret is appended to, or null when the caret takes its own line. */
    val caretTarget: ASTNode? get() = header?.target ?: leaf
}

/**
 * [node] is the leaf re-parsed by the parser's own inline pass over [source], which is the original text with
 * synthetic closers inserted at [synthetic]. Those characters never render, and a construct they close renders
 * plain, without style or link.
 */
@Immutable
internal class InlineProbe(
    val node: ASTNode,
    val source: String,
    val synthetic: IntRange,
)

/** A pipe line whose delimiter row has not arrived, shown as its header cells' plain text instead of [target]. */
@Immutable
internal class PendingHeader(
    val target: ASTNode,
    val text: String,
)

/**
 * Parses only the mutable tail of a growing reply and keeps completed top-level blocks.
 *
 * In org.jetbrains:markdown 0.7.3 no decision that closes a block reads past the first non-blank line after it,
 * so a boundary is final once the line that starts the next block has ended. The one exception is the link
 * reference definition matcher, whose label crosses lines for up to 999 characters and whose title runs to the
 * next blank line; [holdsDefinition] keeps such a block, and everything after it, mutable.
 *
 * [onParse] receives each parsed tail, so a test can count what was re-parsed.
 */
internal class StreamingMarkdownCache(
    private val onParse: (String) -> Unit = {},
) {
    private var previous: String? = null
    private var tailStart = 0
    private val frozen = ArrayList<StreamingMarkdownBlock>()
    private var snapshot: StreamingMarkdownSnapshot? = null

    fun update(source: String): StreamingMarkdownSnapshot {
        val last = previous
        snapshot?.let { if (source == last) return it }
        if (last == null || !source.startsWith(last)) {
            // Not an extension of what was shown, so nothing frozen can be trusted.
            frozen.clear()
            tailStart = 0
        }
        previous = source
        val offset = tailStart
        val tail = source.substring(offset)
        onParse(tail)
        val children = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(tail).children
        val content = children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
        var mutable = 0
        // Freeze in order: a block that must stay mutable holds every block after it.
        while (mutable < content.lastIndex && canFreeze(content[mutable], content[mutable + 1], tail)) mutable++
        var cut = 0
        if (mutable > 0) {
            val lineStart = tail.lastIndexOf('\n', content[mutable].startOffset - 1) + 1
            if (children.none { it.startOffset < lineStart && it.endOffset > lineStart }) cut = lineStart
        }
        children.forEach { if (it.endOffset <= cut) frozen += StreamingMarkdownBlock(offset, it, tail) }
        val live = children.filter { it.startOffset >= cut }.map { StreamingMarkdownBlock(offset, it, tail) }
        tailStart = offset + cut
        val trailing = live.lastOrNull { it.isContent }
        val presentation = trailing?.let { streamingTail(it.node, tail) } ?: StreamingTail()
        return StreamingMarkdownSnapshot(frozen.toList() + live, frozen.size, presentation).also { snapshot = it }
    }
}

/** [block]'s boundary is final once [next] exists and its first line has ended, unless a definition can grow. */
private fun canFreeze(
    block: ASTNode,
    next: ASTNode,
    source: String,
): Boolean = source.indexOf('\n', next.startOffset) >= 0 && !holdsDefinition(block, source)

// Indentation, quote markers and list markers, any number of times, before a possible definition label.
private val ContainerPrefix = Regex("""^(?:[ \t]*(?:>|(?:[-+*]|\d{1,9}[.)])(?=[ \t]|$)))*[ \t]*""")
private val BlankLine = Regex("""\r?\n[ \t]*\r?\n""")

/**
 * Whether a line of [block] opens a link reference definition label the parser could still complete: a label
 * still open within 999 characters, or one closed as `]:` with no complete blank line after it yet. The label
 * itself may cross blank lines, so the blank line that settles the definition is the first one after its `]:`.
 */
internal fun holdsDefinition(
    block: ASTNode,
    source: String,
): Boolean {
    var lineStart = source.lastIndexOf('\n', block.startOffset - 1) + 1
    while (lineStart < block.endOffset) {
        val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
        val label = lineStart + (ContainerPrefix.find(source.substring(lineStart, lineEnd))?.value?.length ?: 0)
        if (label < lineEnd && source[label] == '[') {
            val close = scanDefinitionLabel(source, label)
            when {
                close == LABEL_OPEN -> return true
                close >= 0 && BlankLine.find(source, maxOf(close, block.endOffset)) == null -> return true
            }
        }
        lineStart = lineEnd + 1
    }
    return false
}

private const val LABEL_OPEN = -1
private const val LABEL_NONE = -2

/**
 * The 0.7.3 label matcher's own walk. Returns the offset of a `]` followed by `:`, [LABEL_OPEN] when more text
 * could still complete a label, or [LABEL_NONE] when this cannot be a definition.
 */
private fun scanDefinitionLabel(
    source: String,
    start: Int,
): Int {
    var offset = start + 1
    var seenNonWhitespace = false
    for (step in 1..999) {
        if (offset >= source.length) return LABEL_OPEN
        var c = source[offset]
        if (c == '[' || c == ']') break
        if (c == '\\') {
            offset++
            if (offset >= source.length) return LABEL_OPEN
            c = source[offset]
        }
        if (!c.isWhitespace()) seenNonWhitespace = true
        offset++
    }
    if (offset >= source.length) return LABEL_OPEN
    if (source[offset] != ']' || !seenNonWhitespace) return LABEL_NONE
    return when {
        offset + 1 >= source.length -> LABEL_OPEN
        source[offset + 1] == ':' -> offset
        else -> LABEL_NONE
    }
}

private val InlineLeafTypes: Set<IElementType> =
    setOf(MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.ATX_CONTENT, GFMTokenTypes.CELL)

// Syntax tokens that close a container's line without being its content.
private val TrailingSyntax: Set<IElementType> =
    setOf(
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.ATX_HEADER,
        GFMTokenTypes.CHECK_BOX,
        GFMTokenTypes.TABLE_SEPARATOR,
    )

private val LeafContainers: Set<IElementType> =
    setOf(
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        GFMElementTypes.TABLE,
        GFMElementTypes.HEADER,
        GFMElementTypes.ROW,
    )

/**
 * What ends [block], found through its last content child at each level: an inline leaf, a setext heading (which
 * may be a pipe header waiting for its delimiter row), or null when the block ends in anything else, such as code.
 */
private fun trailingNode(block: ASTNode): ASTNode? {
    var node = block
    while (node.type !in InlineLeafTypes && node.type !in SetextTypes) {
        if (node.type !in LeafContainers) return null
        node = node.children.lastOrNull { it.type !in TrailingSyntax } ?: return null
    }
    return node
}

// 0.7.3 reads a quoted `> ---` under a line as a level-one setext underline, so both levels may be a pipe header.
private val SetextTypes: Set<IElementType> = setOf(MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2)

/** The inline leaf that ends [block], or null when it ends in something else. */
internal fun trailingLeaf(block: ASTNode): ASTNode? = trailingNode(block)?.takeIf { it.type in InlineLeafTypes }

// After a paragraph only its line end and the next line's container prefix may have arrived; anything else
// started a new block or closed the paragraph.
private val OpenParagraphEnd = Regex("""[ \t]*(?:\r?\n[ \t>]*)?""")
private val OpenLineEnd = Regex("""[ \t]*""")

/** The trailing block's temporary presentation: the caret's leaf, a pending table header or an inline probe. */
internal fun streamingTail(
    block: ASTNode,
    source: String,
): StreamingTail {
    val leaf = trailingNode(block) ?: return StreamingTail()
    if (leaf.type in SetextTypes) return StreamingTail(header = setextHeader(leaf, source))
    val after = source.substring(leaf.endOffset)
    val open =
        when (leaf.type) {
            MarkdownElementTypes.PARAGRAPH -> OpenParagraphEnd.matches(after)
            else -> OpenLineEnd.matches(after)
        }
    if (!open) return StreamingTail(leaf = leaf)
    if (leaf.type == MarkdownElementTypes.PARAGRAPH) {
        paragraphHeader(leaf, source)?.let { return StreamingTail(leaf = leaf, header = it) }
    }
    return StreamingTail(leaf = leaf, probe = probeInline(leaf, source))
}

private val PartialDelimiterRow = Regex("""[|\-: \t]*""")

/** `| A | B |` followed by nothing or a partial delimiter row, as a paragraph. */
private fun paragraphHeader(
    paragraph: ASTNode,
    source: String,
): PendingHeader? {
    val lines = source.substring(paragraph.startOffset, paragraph.endOffset).split('\n')
    if (lines.size > 2) return null
    if (lines.size == 2) {
        // A finished second line that did not make a table never will.
        if (source.indexOf('\n', paragraph.endOffset) >= 0) return null
        if (!PartialDelimiterRow.matches(lines[1].trimStart(' ', '\t', '>'))) return null
    }
    return pendingHeader(paragraph, paragraph.startOffset, source)
}

/** `| A | B |` over a growing `---`, which the parser reads as a setext heading until the row has its pipes. */
private fun setextHeader(
    heading: ASTNode,
    source: String,
): PendingHeader? {
    val content = heading.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT } ?: return null
    if ('\n' in source.substring(content.startOffset, content.endOffset)) return null
    if (!OpenLineEnd.matches(source.substring(heading.endOffset))) return null
    val underline = source.substring(source.lastIndexOf('\n', heading.endOffset - 1) + 1, heading.endOffset)
    if (!PartialDelimiterRow.matches(underline.trimStart(' ', '\t', '>'))) return null
    return pendingHeader(heading, content.startOffset, source)
}

/**
 * Appends a delimiter row sized the way the parser counts header cells, and shows the header cells as plain text
 * if that parses as a table. The row is never shown, and nothing else in the probe is either.
 */
private fun pendingHeader(
    target: ASTNode,
    start: Int,
    source: String,
): PendingHeader? {
    val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
    val split = GitHubTableMarkerBlock.splitByPipes(source.substring(start, lineEnd))
    if (split.size < 2) return null
    val cells = split.indices.count { (it > 0 && it < split.lastIndex) || split[it].isNotBlank() }
    if (cells == 0) return null
    val lineStart = source.lastIndexOf('\n', start - 1) + 1
    // Quote markers carry over to the probe's row; list markers become the indentation that continues the item.
    val prefix = source.substring(lineStart, start).map { if (it == '>' || it == '\t') it else ' ' }.joinToString("")
    val probe = source.substring(0, lineEnd) + "\n" + prefix + "|" + " --- |".repeat(cells)
    val header =
        MarkdownParser(MarkdownFlavour)
            .buildMarkdownTreeFromString(probe)
            .descendants()
            .firstOrNull { it.type == GFMElementTypes.HEADER && it.startOffset in lineStart..lineEnd }
            ?: return null
    val text =
        header.children
            .filter { it.type == GFMTokenTypes.CELL }
            .joinToString(" ") { inlineText(it.trimmedContent(), probe) }
    return PendingHeader(target, text)
}

private fun ASTNode.descendants(): Sequence<ASTNode> = sequenceOf(this) + children.asSequence().flatMap { it.descendants() }

/** Bounds the probe's extra inline parses of the trailing leaf on each update. */
internal const val MAX_PROBE_PARSES = 12

private val LinkClosers = listOf(")", ">)", "\")", "')")

/**
 * The trailing leaf with closers appended for the openers the parser left unpaired, innermost first, re-parsed
 * by the parser's own inline pass until nothing new pairs. Null when nothing pairs, so the leaf renders exactly
 * as the parse already stands.
 */
internal fun probeInline(
    leaf: ASTNode,
    source: String,
): InlineProbe? {
    var insert = leaf.endOffset
    while (insert > leaf.startOffset && source[insert - 1].isWhitespace()) insert--
    if (insert == leaf.startOffset) return null
    val parser = MarkdownParser(MarkdownFlavour)
    val tried = HashSet<Int>()
    var closers = ""
    var current = leaf
    var currentSource = source
    var parses = 0
    while (parses < MAX_PROBE_PARSES) {
        val openers =
            unpairedOpeners(current, currentSource, insert)
                .filter { it.position !in tried }
                .sortedByDescending { it.position }
        var paired = false
        search@ for (opener in openers) {
            tried += opener.position
            for (closer in opener.closers) {
                if (parses >= MAX_PROBE_PARSES) break@search
                val trialClosers = closers + closer
                val trialSource = source.substring(0, insert) + trialClosers + source.substring(insert, leaf.endOffset)
                val trial = parser.parseInline(leaf.type, trialSource, leaf.startOffset, leaf.endOffset + trialClosers.length)
                parses++
                if (opener.pairs(trial, trialSource, insert)) {
                    closers = trialClosers
                    current = trial
                    currentSource = trialSource
                    paired = true
                    break@search
                }
            }
        }
        if (!paired) break
    }
    return if (closers.isEmpty()) null else InlineProbe(current, currentSource, insert until insert + closers.length)
}

private enum class OpenerKind { Emphasis, Tilde, Code, Link }

private class Opener(
    val kind: OpenerKind,
    val position: Int,
    val length: Int,
    val closers: List<String>,
) {
    /** Whether [trial] closes this opener with a delimiter at or after [insert], the first synthetic offset. */
    fun pairs(
        trial: ASTNode,
        source: String,
        insert: Int,
    ): Boolean {
        val run = position until position + length
        return trial.descendants().any { node ->
            when (kind) {
                OpenerKind.Emphasis ->
                    (node.type == MarkdownElementTypes.EMPH || node.type == MarkdownElementTypes.STRONG) &&
                        node.startOffset in run &&
                        node.endOffset > insert
                OpenerKind.Tilde ->
                    (node.type == GFMElementTypes.STRIKETHROUGH && node.startOffset in run && node.endOffset > insert) ||
                        singleTildeRuns(node.children, source).any { (open, close) ->
                            node.children[open].startOffset == position && node.children[close].startOffset >= insert
                        }
                OpenerKind.Code -> node.type == MarkdownElementTypes.CODE_SPAN && node.startOffset == position && node.endOffset > insert
                OpenerKind.Link ->
                    node.type == MarkdownElementTypes.INLINE_LINK &&
                        node.endOffset > insert &&
                        node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }?.endOffset == position + 1
            }
        }
    }
}

// Inside these nothing is a delimiter: code, link targets, autolinks, raw HTML and maths stay literal.
private val OpaqueInline: Set<IElementType> =
    setOf(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE,
        MarkdownElementTypes.AUTOLINK,
        MarkdownTokenTypes.AUTOLINK,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK,
        MarkdownTokenTypes.HTML_TAG,
        GFMElementTypes.INLINE_MATH,
        GFMElementTypes.BLOCK_MATH,
    )

private class InlineToken(
    val node: ASTNode,
    val paired: Boolean,
    val insideLink: Boolean,
)

/** The leaf's delimiter runs and pending link targets the parse left without a partner, before [limit]. */
private fun unpairedOpeners(
    leaf: ASTNode,
    source: String,
    limit: Int,
): List<Opener> {
    val tokens = ArrayList<InlineToken>()

    fun walk(
        node: ASTNode,
        insideLink: Boolean,
    ) {
        if (node.type in OpaqueInline) return
        val tildePairs = singleTildeRuns(node.children, source).flatMap { listOf(it.key, it.value) }.toSet()
        node.children.forEachIndexed { index, child ->
            if (child.children.isEmpty()) {
                tokens += InlineToken(child, isPairedDelimiter(node, index) || index in tildePairs, insideLink)
            } else {
                walk(child, insideLink || child.type == MarkdownElementTypes.INLINE_LINK)
            }
        }
    }
    walk(leaf, false)
    val openers = ArrayList<Opener>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val node = token.node
        index++
        if (node.startOffset >= limit || token.paired) continue
        when (node.type) {
            MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE -> {
                val character = source[node.startOffset]
                var end = node.endOffset
                while (index < tokens.size) {
                    val next = tokens[index]
                    if (next.paired || next.node.type != node.type || next.node.startOffset != end || source[end] != character) break
                    end = next.node.endOffset
                    index++
                }
                if (end <= limit && canOpenRun(source, node.startOffset, end, character)) {
                    val kind = if (character == '~') OpenerKind.Tilde else OpenerKind.Emphasis
                    openers +=
                        Opener(kind, node.startOffset, end - node.startOffset, listOf(character.toString().repeat(end - node.startOffset)))
                }
            }
            MarkdownTokenTypes.BACKTICK ->
                openers +=
                    Opener(
                        OpenerKind.Code,
                        node.startOffset,
                        node.endOffset - node.startOffset,
                        listOf(
                            "`".repeat(
                                node.endOffset - node.startOffset,
                            ),
                        ),
                    )
            MarkdownTokenTypes.RBRACKET -> {
                val next = tokens.getOrNull(index)?.node
                if (!token.insideLink && next?.type == MarkdownTokenTypes.LPAREN && next.startOffset == node.endOffset) {
                    openers += Opener(OpenerKind.Link, node.startOffset, 1, LinkClosers)
                }
            }
        }
    }
    return openers
}

/** Whether child [index] of [parent] is one of the delimiters the parser paired into [parent]. */
private fun isPairedDelimiter(
    parent: ASTNode,
    index: Int,
): Boolean {
    val width =
        when (parent.type) {
            MarkdownElementTypes.EMPH -> 1
            MarkdownElementTypes.STRONG, GFMElementTypes.STRIKETHROUGH -> 2
            else -> return false
        }
    return index < width || index >= parent.children.size - width
}

/** GFM's left-flanking rule, plus the underscore's intraword rule; the parser still decides what pairs. */
private fun canOpenRun(
    source: CharSequence,
    start: Int,
    end: Int,
    character: Char,
): Boolean {
    if (!tildeCanOpen(source, start, end)) return false
    return character != '_' || source.getOrNull(start - 1)?.isLetterOrDigit() != true
}
