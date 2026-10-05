package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.Immutable
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Immutable snapshot: neither its AST nor its pending presentation is changed after construction. */
@Immutable
internal class StreamingMarkdownBlock(
    val key: Int,
    val node: ASTNode,
    val source: String,
    val pending: PendingMarkdown,
)

/** The last parser block stays open, including loose lists, table headers and unclosed fences. */
internal class StreamingMarkdownCache(
    private val onParse: (String) -> Unit = {},
) {
    private var previous = ""
    private var consumed = 0
    private val completed = mutableListOf<StreamingMarkdownBlock>()
    private var blocks = emptyList<StreamingMarkdownBlock>()

    fun update(source: String): List<StreamingMarkdownBlock> {
        if (source == previous) return blocks
        if (!source.startsWith(previous)) {
            consumed = 0
            completed.clear()
        }
        previous = source
        val tail = source.substring(consumed)
        onParse(tail)
        val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(tail)
        val pending = PendingMarkdown(tail, root)
        val nodes = root.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
        val next = nodes.map { StreamingMarkdownBlock(consumed + it.startOffset, it, tail, pending) }
        blocks = completed.toList() + next
        if (next.size > 1) {
            completed += next.dropLast(1)
            consumed += next.last().node.startOffset
        }
        return blocks
    }
}

/** Presentation only. All offsets refer to the original snapshot, never a manufactured destination. */
internal class PendingMarkdown(
    private val source: String,
    root: ASTNode,
) {
    private val hidden = BooleanArray(source.length)
    private val literal = BooleanArray(source.length)
    private val blockOverrides = mutableMapOf<Int, String>()

    init {
        visit(root)
    }

    fun blockText(node: ASTNode): String? = blockOverrides[node.startOffset]

    fun isLiteral(node: ASTNode): Boolean =
        node.startOffset < node.endOffset && (node.startOffset until node.endOffset).all { literal[it] || hidden[it] }

    fun text(node: ASTNode): String =
        buildString {
            for (i in node.startOffset until node.endOffset) if (!hidden[i]) append(source[i])
        }

    fun isHidden(node: ASTNode): Boolean = (node.startOffset until node.endOffset).all { hidden[it] }

    private fun visit(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> Unit
            MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.ATX_CONTENT, GFMTokenTypes.CELL -> inline(node)
            GFMElementTypes.TABLE -> if (!pendingTable(node)) node.children.forEach(::visit)
            else -> node.children.forEach(::visit)
        }
        if (node.type == MarkdownElementTypes.PARAGRAPH) pendingTable(node)
    }

    private fun inline(node: ASTNode) {
        val protected = BooleanArray(source.length)

        fun protect(part: ASTNode) {
            when (part.type) {
                MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.INLINE_LINK,
                MarkdownElementTypes.IMAGE, GFMElementTypes.INLINE_MATH,
                ->
                    for (i in part.startOffset until part.endOffset) protected[i] = true
                MarkdownElementTypes.EMPH, MarkdownElementTypes.STRONG, GFMElementTypes.STRIKETHROUGH ->
                    part.children.forEach { child ->
                        if (child.type == MarkdownTokenTypes.EMPH || child.type == GFMTokenTypes.TILDE) {
                            for (i in child.startOffset until child.endOffset) protected[i] = true
                        } else {
                            protect(child)
                        }
                    }
                else -> part.children.forEach(::protect)
            }
        }
        protect(node)
        val open = mutableMapOf<Pair<Char, Int>, ArrayDeque<IntRange>>()
        var i = node.startOffset
        while (i < node.endOffset) {
            if (protected[i]) {
                i++
                continue
            }
            val c = source[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '`') {
                val end = runEnd(i, node.endOffset, c)
                hide(i, end)
                for (index in end until node.endOffset) literal[index] = true
                break
            }
            if (c == '[' && source.getOrNull(i - 1) != '!') {
                val labelEnd = closingBracket(i, node.endOffset)
                if (labelEnd != null && source.getOrNull(labelEnd + 1) == '(') {
                    // The parser did not recognize a complete link; its whole suffix remains inert.
                    hide(i, i + 1)
                    hide(labelEnd, node.endOffset)
                    for (index in i + 1 until labelEnd) literal[index] = true
                    break
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                val end = runEnd(i, node.endOffset, c)
                val run = i until end
                val before = source.getOrNull(i - 1)
                val after = source.getOrNull(end)
                val isHomePath = c == '~' && end - i == 1 && after == '/'
                val canOpen = !isHomePath && tildeCanOpen(source, i, end) && (c != '_' || before?.isLetterOrDigit() != true)
                val canClose = tildeCanClose(source, i, end) && (c != '_' || after?.isLetterOrDigit() != true)
                val stack = open.getOrPut(c to (end - i)) { ArrayDeque() }
                if (canClose && stack.isNotEmpty()) {
                    stack.removeLast()
                } else if (canOpen) {
                    stack.addLast(run)
                }
                i = end
            } else {
                i++
            }
        }
        open.values.forEach { stack -> stack.forEach { hide(it.first, it.last + 1) } }
    }

    private fun runEnd(
        start: Int,
        limit: Int,
        character: Char,
    ): Int {
        var end = start + 1
        while (end < limit && source[end] == character) end++
        return end
    }

    private fun hide(
        start: Int,
        end: Int,
    ) {
        for (i in start until end) hidden[i] = true
    }

    private fun closingBracket(
        start: Int,
        limit: Int,
    ): Int? {
        var depth = 1
        var i = start + 1
        while (i < limit) {
            when (source[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    private fun pendingTable(node: ASTNode): Boolean {
        val text = node.getTextInNode(source).toString()
        val lines = text.split('\n')
        val header = lines.firstOrNull() ?: return false
        val pipes = structuralPipes(header)
        if (pipes.isEmpty()) return false
        val cells = tableCells(header, pipes)
        val separator = lines.getOrNull(1)
        if (separator != null && separator.any { it !in "|-: \t\r" }) return false
        if (separator != null) {
            val separatorCells = tableCells(separator, structuralPipes(separator))
            val completeLine =
                text.indexOf('\n', header.length + 1) >= 0 ||
                    source.getOrNull(node.endOffset) == '\n' ||
                    separator.trimEnd().endsWith('|')
            val valid = separatorCells.size == cells.size && separatorCells.all { it.trim().matches(Regex(":?-+:?")) }
            if (completeLine && valid) return false
        }
        val arrivedCells =
            cells.map { cell ->
                // A pending header is plain; keep escaped punctuation and code's literal pipes.
                val cellRoot = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(cell.trim())
                val paragraph = cellRoot.children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH }
                if (paragraph ==
                    null
                ) {
                    cell.trim()
                } else {
                    streamingInlineText(paragraph.children, cell.trim(), PendingMarkdown(cell.trim(), cellRoot))
                }
            }
        blockOverrides[node.startOffset] = arrivedCells.joinToString(" ")
        return true
    }

    private fun tableCells(
        line: String,
        pipes: List<Int>,
    ): List<String> {
        val edges = listOf(-1) + pipes + line.length
        return edges
            .zipWithNext()
            .map { (a, b) -> line.substring(a + 1, b).trim() }
            .let { cells ->
                var result = cells
                if (line.trimStart().startsWith('|')) result = result.drop(1)
                if (line.trimEnd().endsWith('|')) result = result.dropLast(1)
                result
            }
    }

    private fun structuralPipes(line: String): List<Int> {
        val pipes = mutableListOf<Int>()
        var codeRun = 0
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '`' -> {
                    var end = i + 1
                    while (end < line.length && line[end] == '`') end++
                    val length = end - i
                    if (codeRun == 0) {
                        codeRun = length
                    } else if (codeRun == length) {
                        codeRun = 0
                    }
                    i = end
                }
                '|' -> {
                    if (codeRun == 0) pipes += i
                    i++
                }
                else -> i++
            }
        }
        return pipes
    }
}
