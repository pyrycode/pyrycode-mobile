package de.pyryco.mobile.notifications

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MarkdownFlavour
import de.pyryco.mobile.ui.conversations.components.markdownPlainText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.MarkdownParser

/** Inert notification text; the bound includes the truncation mark and never splits a code point. */
internal fun notificationPreview(text: String?): String? {
    if (text == null) return null
    val plain = markdownPlainText(withoutLinkTargets(text))
    val cleaned = StringBuilder()
    var pendingSpace = false
    var index = 0
    while (index < plain.length) {
        val point = plain.codePointAt(index)
        index += Character.charCount(point)
        when {
            Character.isWhitespace(point) || Character.isSpaceChar(point) -> pendingSpace = cleaned.isNotEmpty()
            Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt() -> Unit
            else -> {
                if (pendingSpace) cleaned.append(' ')
                pendingSpace = false
                cleaned.appendCodePoint(point)
            }
        }
    }
    if (cleaned.isEmpty()) return null
    val result = cleaned.toString()
    return if (result.codePointCount(0, result.length) > 200) result.substring(0, result.offsetByCodePoints(0, 199)) + "…" else result
}

/** Only exact segment identity and complete settled delta evidence authorize a reply preview. */
internal fun completionReply(
    rows: List<ThreadItem>,
    turnId: String,
): String? {
    val segments =
        rows
            .mapNotNull { (it as? ThreadItem.MessageItem)?.message }
            .filter { it.role == Role.Assistant && it.segment?.turnId == turnId && it.parentToolUseId.isEmpty() }
    if (segments.isEmpty() || segments.any { it.isStreaming }) return null
    val deltas = segments.flatMap { it.segment?.deltas.orEmpty() }
    if (deltas.isEmpty() || deltas.map { it.seq } != deltas.indices.toList()) return null
    if (segments.any { row -> row.segment?.deltas?.sumOf { it.length } != row.content.length }) return null
    return segments.lastOrNull { it.content.isNotBlank() }?.content
}

/** The existing prose conversion omits inline targets, but preserves reference-link source verbatim. */
private fun withoutLinkTargets(source: String): String {
    val root = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)

    fun rewrite(node: ASTNode): String =
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK ->
                node
                    .getTextInNode(
                        source,
                    ).toString()
            MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.AUTOLINK, MarkdownTokenTypes.HORIZONTAL_RULE -> ""
            MarkdownTokenTypes.HTML_TAG -> ""
            MarkdownTokenTypes.HTML_BLOCK_CONTENT -> node.getTextInNode(source).toString().replace(Regex("<[^>]*>"), " ")
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
                node.children
                    .filter { it.type != MarkdownTokenTypes.SETEXT_1 && it.type != MarkdownTokenTypes.SETEXT_2 }
                    .joinToString("") { rewrite(it) }
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT || it.type == MarkdownElementTypes.LINK_LABEL }
                    ?.children
                    ?.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }
                    ?.joinToString("") { rewrite(it) }
                    .orEmpty()
            MarkdownElementTypes.IMAGE ->
                node.children.filter { it.type != MarkdownTokenTypes.EXCLAMATION_MARK }.joinToString(
                    "",
                ) { rewrite(it) }
            else -> {
                if (node.children.isEmpty()) {
                    node.getTextInNode(source).toString()
                } else {
                    buildString {
                        var cursor = node.startOffset
                        node.children.forEach { child ->
                            append(source, cursor, child.startOffset)
                            append(rewrite(child))
                            cursor = child.endOffset
                        }
                        append(source, cursor, node.endOffset)
                    }
                }
            }
        }
    return rewrite(root)
}
