package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.CodeStructure
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val ParagraphSpacing = 8.dp
private val ListItemIndent = 8.dp
private val BlockquoteBarWidth = 4.dp
private val BlockquoteContentIndent = 12.dp
private val CodeBlockCornerRadius = 8.dp
private val CodeBlockHorizontalPadding = 12.dp
private val CodeBlockVerticalPadding = 8.dp
private val CodeBlockLabelVerticalPadding = 4.dp
private val CodeBlockPadding =
    PaddingValues(
        horizontal = CodeBlockHorizontalPadding,
        vertical = CodeBlockVerticalPadding,
    )

private val MarkdownFlavour = CommonMarkFlavourDescriptor()

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val root =
        remember(markdown) {
            MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
        }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ParagraphSpacing),
    ) {
        root.children.forEach { child ->
            MarkdownBlock(child, markdown, uriHandler)
        }
    }
}

@Composable
private fun MarkdownBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
) {
    when (node.type) {
        MarkdownElementTypes.ATX_1 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.headlineSmall)
        MarkdownElementTypes.ATX_2 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.titleLarge)
        MarkdownElementTypes.ATX_3 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.titleMedium)
        MarkdownElementTypes.PARAGRAPH ->
            Text(
                text = buildInline(node, source, uriHandler),
                style = MaterialTheme.typography.bodyMedium,
            )
        MarkdownElementTypes.UNORDERED_LIST ->
            ListBlock(node, source, uriHandler, ordered = false)
        MarkdownElementTypes.ORDERED_LIST ->
            ListBlock(node, source, uriHandler, ordered = true)
        MarkdownElementTypes.BLOCK_QUOTE ->
            BlockQuoteBlock(node, source, uriHandler)
        MarkdownElementTypes.CODE_FENCE -> {
            val code =
                node.children
                    .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
                    .joinToString("\n") { it.getTextInNode(source).toString() }
            val language =
                node.children
                    .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                    ?.getTextInNode(source)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            CodeBlock(code, language)
        }
        MarkdownElementTypes.CODE_BLOCK -> {
            val code =
                node.children
                    .filter { it.type == MarkdownTokenTypes.CODE_LINE }
                    .joinToString("\n") { it.getTextInNode(source).toString() }
            CodeBlock(code, language = null)
        }
        else -> {
            val text = node.getTextInNode(source).toString().trim()
            if (text.isNotEmpty()) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HeadingBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    style: androidx.compose.ui.text.TextStyle,
) {
    val codeSpanBg = MaterialTheme.colorScheme.surfaceContainer
    val linkColor = MaterialTheme.colorScheme.primary
    val text =
        buildAnnotatedString {
            node.children
                .filter {
                    it.type != MarkdownTokenTypes.ATX_HEADER &&
                        it.type != MarkdownTokenTypes.WHITE_SPACE &&
                        it.type != MarkdownTokenTypes.EOL
                }.forEach { child ->
                    appendInline(child, source, uriHandler, codeSpanBg, linkColor)
                }
        }
    Text(text = text, style = style)
}

@Composable
private fun ListBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    ordered: Boolean,
) {
    val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    Column(verticalArrangement = Arrangement.spacedBy(ParagraphSpacing / 2)) {
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(ListItemIndent))
                Column(
                    verticalArrangement = Arrangement.spacedBy(ParagraphSpacing),
                ) {
                    item.children
                        .filter {
                            it.type != MarkdownTokenTypes.LIST_BULLET &&
                                it.type != MarkdownTokenTypes.LIST_NUMBER &&
                                it.type != MarkdownTokenTypes.WHITE_SPACE &&
                                it.type != MarkdownTokenTypes.EOL
                        }.forEach { child -> MarkdownBlock(child, source, uriHandler) }
                }
            }
        }
    }
}

@Composable
private fun BlockQuoteBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
) {
    val barColor = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier =
                Modifier
                    .width(BlockquoteBarWidth)
                    .fillMaxHeight()
                    .background(barColor),
        )
        Spacer(Modifier.width(BlockquoteContentIndent))
        Column(verticalArrangement = Arrangement.spacedBy(ParagraphSpacing)) {
            node.children
                .filter {
                    it.type != MarkdownTokenTypes.BLOCK_QUOTE &&
                        it.type != MarkdownTokenTypes.WHITE_SPACE &&
                        it.type != MarkdownTokenTypes.EOL
                }.forEach { child ->
                    if (child.type == MarkdownElementTypes.PARAGRAPH) {
                        Text(
                            text = buildInline(child, source, uriHandler),
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                ),
                        )
                    } else {
                        MarkdownBlock(child, source, uriHandler)
                    }
                }
        }
    }
}

@Composable
internal fun CodeBlock(
    content: String,
    language: String?,
) {
    val syntaxLanguage = remember(language) { resolveSyntaxLanguage(language) }
    val structure =
        remember(content, syntaxLanguage) {
            if (syntaxLanguage == null) null else tokeniseCode(content, syntaxLanguage)
        }
    val annotated = buildHighlightedCode(content, structure)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodeBlockCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box {
            Text(
                text = annotated,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(CodeBlockPadding),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                softWrap = false,
            )
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                horizontal = CodeBlockHorizontalPadding,
                                vertical = CodeBlockLabelVerticalPadding,
                            ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun buildHighlightedCode(
    content: String,
    structure: CodeStructure?,
): AnnotatedString {
    if (structure == null) return AnnotatedString(content)
    val keywordColor = MaterialTheme.colorScheme.tertiary
    val stringColor = MaterialTheme.colorScheme.secondary
    val literalColor = MaterialTheme.colorScheme.primary
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(content)
        val length = content.length
        structure.keywords.forEach {
            applySpan(it, length, SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium))
        }
        structure.annotations.forEach {
            applySpan(it, length, SpanStyle(color = keywordColor))
        }
        structure.strings.forEach {
            applySpan(it, length, SpanStyle(color = stringColor))
        }
        structure.literals.forEach {
            applySpan(it, length, SpanStyle(color = literalColor))
        }
        structure.comments.forEach {
            applySpan(it, length, SpanStyle(color = commentColor, fontStyle = FontStyle.Italic))
        }
        structure.multilineComments.forEach {
            applySpan(it, length, SpanStyle(color = commentColor, fontStyle = FontStyle.Italic))
        }
    }
}

private fun AnnotatedString.Builder.applySpan(
    location: PhraseLocation,
    contentLength: Int,
    style: SpanStyle,
) {
    val start = location.start.coerceIn(0, contentLength)
    val end = location.end.coerceIn(start, contentLength)
    if (end > start) addStyle(style, start, end)
}

private fun tokeniseCode(
    content: String,
    language: SyntaxLanguage,
): CodeStructure? =
    runCatching {
        Highlights
            .Builder()
            .code(content)
            .language(language)
            .build()
            .getCodeStructure()
    }.getOrNull()

private fun resolveSyntaxLanguage(fenceLang: String?): SyntaxLanguage? {
    val name = fenceLang?.trim()?.lowercase() ?: return null
    if (name.isEmpty()) return null
    return when (name) {
        "kotlin", "kt", "kts" -> SyntaxLanguage.KOTLIN
        // JSON's grammar is a subset of JavaScript object literals — closest enum match exposed
        // by dev.snipme:highlights, which has no dedicated JSON lexer. Strings, numbers, and
        // punctuation tokenise correctly under JAVASCRIPT.
        "json" -> SyntaxLanguage.JAVASCRIPT
        "bash", "sh", "shell", "zsh" -> SyntaxLanguage.SHELL
        else -> null
    }
}

@Composable
private fun buildInline(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
): AnnotatedString {
    val codeSpanBg = MaterialTheme.colorScheme.surfaceContainer
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        appendInline(node, source, uriHandler, codeSpanBg, linkColor)
    }
}

private fun AnnotatedString.Builder.appendInline(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    codeSpanBg: Color,
    linkColor: Color,
) {
    when (node.type) {
        MarkdownElementTypes.EMPH ->
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children
                    .filter { it.type != MarkdownTokenTypes.EMPH }
                    .forEach { appendInline(it, source, uriHandler, codeSpanBg, linkColor) }
            }
        MarkdownElementTypes.STRONG ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children
                    .filter { it.type != MarkdownTokenTypes.EMPH }
                    .forEach { appendInline(it, source, uriHandler, codeSpanBg, linkColor) }
            }
        MarkdownElementTypes.CODE_SPAN ->
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeSpanBg,
                ),
            ) {
                append(node.getTextInNode(source).toString().trim('`'))
            }
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText =
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.children
                    ?.filter {
                        it.type != MarkdownTokenTypes.LBRACKET &&
                            it.type != MarkdownTokenTypes.RBRACKET
                    }?.joinToString("") { it.getTextInNode(source).toString() }
                    ?: ""
            val url =
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.getTextInNode(source)
                    ?.toString()
                    ?.trim()
                    ?.removePrefix("<")
                    ?.removeSuffix(">")
                    ?: ""
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles =
                        TextLinkStyles(
                            style =
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                        ),
                    linkInteractionListener =
                        LinkInteractionListener { link ->
                            (link as? LinkAnnotation.Url)?.url?.let { target ->
                                if (isSafeLinkScheme(target)) uriHandler.openUri(target)
                            }
                        },
                ),
            ) {
                append(linkText)
            }
        }
        MarkdownTokenTypes.EOL -> append(" ")
        else ->
            if (node.children.isEmpty()) {
                append(node.getTextInNode(source).toString())
            } else {
                node.children.forEach {
                    appendInline(it, source, uriHandler, codeSpanBg, linkColor)
                }
            }
    }
}

private fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}
