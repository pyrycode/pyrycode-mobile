package de.pyryco.mobile.e2e

import de.pyryco.mobile.ui.conversations.components.MarkdownFlavour
import de.pyryco.mobile.ui.conversations.components.fencedCodeText
import de.pyryco.mobile.ui.conversations.components.markdownPlainText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser

/** Required constructs and marker values; incidental delimiters, spacing and prose are unconstrained. */
internal class MarkdownReplyRequirements(
    private val bold: String,
    private val inline: String,
    private val code: String,
    private val cell: String,
    private val end: String,
) {
    fun satisfiedBy(source: String): Boolean {
        val tree = MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(source)

        fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
        val nodes = descendants(tree)

        fun contains(
            type: org.intellij.markdown.IElementType,
            text: String,
        ) = nodes.any {
            it.type == type && markdownPlainText(it.getTextInNode(source).toString()) == text
        }
        return contains(MarkdownElementTypes.STRONG, bold) &&
            contains(MarkdownElementTypes.CODE_SPAN, inline) &&
            nodes.any { it.type == MarkdownElementTypes.CODE_FENCE && fencedCodeText(it, source).trimEnd('\n') == code } &&
            nodes.any {
                it.type == GFMElementTypes.TABLE &&
                    markdownPlainText(it.getTextInNode(source).toString()).lines().any { row ->
                        row.split('\t').map(String::trim) == listOf(cell, "present")
                    }
            } &&
            contains(MarkdownElementTypes.PARAGRAPH, end)
    }
}
