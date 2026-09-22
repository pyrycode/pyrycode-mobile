package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.CodeStructure
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
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
private val TableBorderWidth = 1.dp
private val TableCellHorizontalPadding = 12.dp
private val TableCellVerticalPadding = 4.dp
private val TableCellPadding =
    PaddingValues(
        horizontal = TableCellHorizontalPadding,
        vertical = TableCellVerticalPadding,
    )
private val TaskMarkSize = 14.dp
private val TaskMarkTickSize = 10.dp
private val TaskMarkBorderWidth = 1.dp
private val TaskMarkCornerRadius = 3.dp
private val TaskMarkTopInset = 4.dp // sits the mark on the first text line, not the item's top edge

/**
 * The table grid is one composable per cell with no lazy layout, so its extent is a fan-out budget
 * over text this device did not write (#681 security review). Both bounds sit far above any real
 * reply — Claude's widest tables run to a handful of columns — so no legitimate table is truncated;
 * what they remove is the amplification tail, where a few kilobytes of pipes become tens of
 * thousands of measured composables, re-paid on every streaming reveal tick.
 */
private const val MAX_TABLE_COLUMNS = 32
private const val MAX_TABLE_ROWS = 256

/**
 * GFM, not CommonMark, since #681. `org.jetbrains:markdown` offers no per-construct registration, so
 * this one switch is what brings tables, task lists and strikethrough — and it necessarily also
 * brings bare-URL autolinks and `$…$` maths, which this file deliberately does NOT dispatch on. They
 * reach the `else` arms below and render as their own source characters; `MarkdownTextParsingTest`
 * pins that, because the property rests on an absent branch and an absent branch reddens nothing.
 *
 * The constructor flags configure only the HTML generating providers, which this renderer never
 * builds — it walks the AST itself — so their defaults are inert here rather than relied upon.
 */
private val MarkdownFlavour = GFMFlavourDescriptor()

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
        GFMElementTypes.TABLE ->
            TableBlock(node, source, uriHandler)
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
    val colors = currentInlineColors()
    val text =
        buildAnnotatedString {
            appendInlineChildren(
                node.children.filter {
                    it.type != MarkdownTokenTypes.ATX_HEADER &&
                        it.type != MarkdownTokenTypes.WHITE_SPACE &&
                        it.type != MarkdownTokenTypes.EOL
                },
                source,
                uriHandler,
                colors,
            )
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
            val checkBox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            Row {
                if (checkBox == null) {
                    Text(
                        text = if (ordered) "${index + 1}." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // GFM draws the mark INSTEAD of the marker, so this arm replaces the bullet
                    // rather than preceding it. Per item and not per list: one list may mix task
                    // items with plain ones, and a plain sibling keeps its bullet.
                    TaskMark(isCheckedTaskMark(checkBox.getTextInNode(source).toString()))
                }
                Spacer(Modifier.width(ListItemIndent))
                Column(
                    verticalArrangement = Arrangement.spacedBy(ParagraphSpacing),
                ) {
                    item.children
                        .filter {
                            it.type != MarkdownTokenTypes.LIST_BULLET &&
                                it.type != MarkdownTokenTypes.LIST_NUMBER &&
                                it.type != GFMTokenTypes.CHECK_BOX &&
                                it.type != MarkdownTokenTypes.WHITE_SPACE &&
                                it.type != MarkdownTokenTypes.EOL
                        }.forEach { child -> MarkdownBlock(child, source, uriHandler) }
                }
            }
        }
    }
}

/**
 * The static mark GFM draws in place of a task item's bullet (#681; desktop's `.task-mark`).
 *
 * INERT BY CONSTRUCTION, and the omissions are the design: no `clickable`, no `toggleable`, no
 * `ToggleableState` semantics, no `focusable`. A bare `contentDescription` on a node with no action
 * is announced by TalkBack and offers nothing to activate — which is exactly "exposes the state,
 * is not actionable". A `Checkbox` would have been the shorter spelling and the wrong one: the mark
 * renders a message that has already been sent, so there is nothing for a tap to change.
 *
 * The description is a static string chosen by the boolean, never the item's own text. Interpolating
 * the text would push daemon-authored content into an announcement path it does not otherwise reach.
 */
@Composable
private fun TaskMark(checked: Boolean) {
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant
    val description =
        stringResource(
            if (checked) R.string.markdown_task_mark_checked else R.string.markdown_task_mark_unchecked,
        )
    Box(modifier = Modifier.padding(top = TaskMarkTopInset)) {
        Box(
            modifier =
                Modifier
                    .size(TaskMarkSize)
                    .border(
                        width = TaskMarkBorderWidth,
                        color = markColor,
                        shape = RoundedCornerShape(TaskMarkCornerRadius),
                    ).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = markColor,
                    modifier = Modifier.size(TaskMarkTickSize),
                )
            }
        }
    }
}

/** Reads a `CHECK_BOX` token's raw text (`"[x] "`, `"[X] "`, `"[ ] "`). */
internal fun isCheckedTaskMark(rawCheckBox: String): Boolean =
    rawCheckBox
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trim()
        .equals("x", ignoreCase = true)

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

/**
 * A GFM pipe table (#681; desktop's `.bubble__markdown table`).
 *
 * COLUMN-MAJOR — a `Row` of per-column `Column`s — because that is what makes a column's width its
 * own widest cell without a measuring pass. Row heights stay in step across columns for a reason
 * worth stating, since it is what a later edit would break: **cells never wrap**. Each is one line
 * of `bodyMedium`, whose `lineHeight` comes from the type style rather than from the glyphs, so a
 * monospace code span and a bold header sit at the same height as plain text. Allow a cell to wrap
 * and the columns desync vertically, because nothing here aligns rows across columns. Not wrapping
 * is also the behaviour the ticket asks for: a table too wide for the bubble scrolls rather than
 * reflowing into an unreadable column of fragments.
 *
 * The scroll lives on the `Box` and the closing edges on the content `Row`, so the table's right
 * edge travels with the scroll as desktop's does, and the bubble's own measure is unchanged.
 */
@Composable
private fun TableBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
) {
    val colors = currentInlineColors()
    val borderColor = MaterialTheme.colorScheme.primaryContainer
    // Row 0 is the header; the rest are body rows. A short row reads as empty cells and a long
    // row's overflow is dropped — the lexer has already fused that overflow into a trailing
    // separator token, so there is nothing addressable left to render.
    val rows =
        (
            node.children.filter { it.type == GFMElementTypes.HEADER } +
                node.children.filter { it.type == GFMElementTypes.ROW }
        ).take(MAX_TABLE_ROWS)
            .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    val columnCount = (rows.firstOrNull()?.size ?: 0).coerceAtMost(MAX_TABLE_COLUMNS)
    if (columnCount == 0) return
    // The delimiter row is ONE separator token that is a direct child of TABLE; the separators
    // inside a HEADER or ROW are the single `|` glyphs between cells.
    val delimiterRow =
        node.children
            .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
            ?.getTextInNode(source)
            ?.toString()
            .orEmpty()
    val alignments = remember(delimiterRow) { parseTableAlignments(delimiterRow) }
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row(modifier = Modifier.tableOuterEdges(borderColor, TableBorderWidth)) {
            repeat(columnCount) { column ->
                val textAlign = alignments.getOrElse(column) { TableColumnAlignment.Start }.toTextAlign()
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    rows.forEachIndexed { rowIndex, cells ->
                        val cell = cells.getOrNull(column)
                        val style = MaterialTheme.typography.bodyMedium
                        Text(
                            text =
                                if (cell == null) {
                                    AnnotatedString("")
                                } else {
                                    buildAnnotatedString {
                                        appendInlineChildren(cell.children, source, uriHandler, colors)
                                    }
                                },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .tableCellEdges(borderColor, TableBorderWidth)
                                    .padding(TableCellPadding),
                            style = if (rowIndex == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                            textAlign = textAlign,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

internal enum class TableColumnAlignment { Start, Center, End }

/**
 * The per-column alignments a delimiter row declares, in column order. Columns the row does not
 * describe are simply absent from the result — the caller defaults them to `Start`, which is what
 * GFM and desktop's `text-align: start` both do.
 */
internal fun parseTableAlignments(delimiterRow: String): List<TableColumnAlignment> {
    val segments = delimiterRow.split('|').toMutableList()
    // GFM permits the row with or without its outer pipes; a blank edge segment is a pipe's own
    // shadow rather than a column.
    if (segments.firstOrNull()?.isBlank() == true) segments.removeAt(0)
    if (segments.lastOrNull()?.isBlank() == true) segments.removeAt(segments.lastIndex)
    return segments.map { segment ->
        val spec = segment.trim()
        when {
            spec.startsWith(':') && spec.endsWith(':') -> TableColumnAlignment.Center
            spec.endsWith(':') -> TableColumnAlignment.End
            else -> TableColumnAlignment.Start
        }
    }
}

private fun TableColumnAlignment.toTextAlign(): TextAlign =
    when (this) {
        TableColumnAlignment.Start -> TextAlign.Start
        TableColumnAlignment.Center -> TextAlign.Center
        TableColumnAlignment.End -> TextAlign.End
    }

/**
 * One cell's top and start edges. THIS IS THE BORDER COLLAPSE: every internal grid line is drawn
 * exactly once, by the cell below-or-right of it, where a four-sided `Modifier.border` per cell
 * would leave a doubled seam between neighbours. The table's own closing edges come from
 * [tableOuterEdges], so the two must stay a pair.
 */
private fun Modifier.tableCellEdges(
    color: Color,
    width: Dp,
): Modifier =
    drawBehind {
        val stroke = width.toPx()
        val inset = stroke / 2
        drawLine(color, Offset(0f, inset), Offset(size.width, inset), stroke)
        drawLine(color, Offset(inset, 0f), Offset(inset, size.height), stroke)
    }

/** The table's closing end and bottom edges — the two lines [tableCellEdges] leaves undrawn. */
private fun Modifier.tableOuterEdges(
    color: Color,
    width: Dp,
): Modifier =
    drawBehind {
        val stroke = width.toPx()
        val inset = stroke / 2
        drawLine(
            color,
            Offset(size.width - inset, 0f),
            Offset(size.width - inset, size.height),
            stroke,
        )
        drawLine(
            color,
            Offset(0f, size.height - inset),
            Offset(size.width, size.height - inset),
            stroke,
        )
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

/**
 * The three theme colours the inline walker needs, as one value rather than three trailing
 * parameters. Introduced with the struck colour in #681 — a sixth positional `Color` on a recursive
 * function is where call sites start transposing arguments.
 */
private data class InlineColors(
    val codeSpanBackground: Color,
    val link: Color,
    val struck: Color,
)

@Composable
private fun currentInlineColors(): InlineColors =
    InlineColors(
        codeSpanBackground = MaterialTheme.colorScheme.surfaceContainer,
        link = MaterialTheme.colorScheme.primary,
        struck = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
private fun buildInline(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
): AnnotatedString {
    val colors = currentInlineColors()
    return buildAnnotatedString {
        appendInline(node, source, uriHandler, colors)
    }
}

private fun AnnotatedString.Builder.appendInline(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    colors: InlineColors,
) {
    when (node.type) {
        MarkdownElementTypes.EMPH ->
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineChildren(
                    node.children.filter { it.type != MarkdownTokenTypes.EMPH },
                    source,
                    uriHandler,
                    colors,
                )
            }
        MarkdownElementTypes.STRONG ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineChildren(
                    node.children.filter { it.type != MarkdownTokenTypes.EMPH },
                    source,
                    uriHandler,
                    colors,
                )
            }
        GFMElementTypes.STRIKETHROUGH ->
            withStyle(struckSpanStyle(colors)) {
                appendInlineChildren(
                    node.children.filter { it.type != GFMTokenTypes.TILDE },
                    source,
                    uriHandler,
                    colors,
                )
            }
        MarkdownElementTypes.CODE_SPAN ->
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.codeSpanBackground,
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
                                    color = colors.link,
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
        // The total fallback, and the reason #681 could switch flavours without suppressing
        // anything: a node kind with no arm renders its own source characters. GFM_AUTOLINK is a
        // childless leaf, so a bare URL appends as text and is never a link; INLINE_MATH has
        // children whose leaves (DOLLAR, TEXT, WHITE_SPACE) rebuild the `$…$` span verbatim.
        else ->
            if (node.children.isEmpty()) {
                append(node.getTextInNode(source).toString())
            } else {
                appendInlineChildren(node.children, source, uriHandler, colors)
            }
    }
}

/**
 * Walks a sibling list, pairing any bare `~` tokens into struck runs before appending.
 *
 * The pairing pass is why children are walked as a LIST rather than one at a time. `~~x~~` arrives
 * as a STRIKETHROUGH node and never reaches here, but `~x~` does not: this library's
 * `StrikeThroughDelimiterParser` takes no single-tilde option (its only constructor is no-arg), so
 * it leaves the tildes as loose siblings. Desktop strikes that form, because micromark's
 * `singleTilde` default is on, so parity has to be reassembled here.
 */
private fun AnnotatedString.Builder.appendInlineChildren(
    children: List<ASTNode>,
    source: String,
    uriHandler: UriHandler,
    colors: InlineColors,
) {
    val runs = singleTildeRuns(children, source)
    var index = 0
    while (index < children.size) {
        val close = runs[index]
        if (close == null) {
            appendInline(children[index], source, uriHandler, colors)
            index++
        } else {
            withStyle(struckSpanStyle(colors)) {
                for (inner in index + 1 until close) {
                    appendInline(children[inner], source, uriHandler, colors)
                }
            }
            index = close + 1
        }
    }
}

private fun struckSpanStyle(colors: InlineColors): SpanStyle = SpanStyle(color = colors.struck, textDecoration = TextDecoration.LineThrough)

/**
 * Bare-tilde pairs, as `openIndex -> closeIndex` into [children]. Anything unpaired is absent, and
 * absent means the caller appends the `~` as the literal character it is.
 *
 * ONE FORWARD PASS holding at most one pending opener — deliberately not "for each opener, search
 * forward for a closer", which is quadratic in the tildes of a single paragraph and is reached by a
 * line of lone `~` characters, re-paid on every streaming reveal tick. Closing is tested before
 * opening because a tilde may be both, and CommonMark resolves that the same way.
 */
private fun singleTildeRuns(
    children: List<ASTNode>,
    source: CharSequence,
): Map<Int, Int> {
    if (children.none { it.type == GFMTokenTypes.TILDE }) return emptyMap()
    val runs = mutableMapOf<Int, Int>()
    var pendingOpen = -1
    children.forEachIndexed { index, child ->
        if (child.type == GFMTokenTypes.TILDE) {
            if (pendingOpen >= 0 && tildeCanClose(source, child.startOffset, child.endOffset)) {
                runs[pendingOpen] = index
                pendingOpen = -1
            } else if (tildeCanOpen(source, child.startOffset, child.endOffset)) {
                pendingOpen = index
            }
        }
    }
    return runs
}

/**
 * GFM's left-flanking rule over the source characters either side of a `~` token, which for this
 * delimiter is the whole of "can open".
 *
 * FLANKING, NOT POSITION, and the difference is a real reply rather than a specification detail: a
 * line carrying two home-relative paths (`~/a ~/b`) pairs under "the next tilde wins" and strikes
 * `/a ` in the middle of a path. Under this rule the second tilde is preceded by whitespace, so it
 * cannot close, the first finds no closer, and the line renders verbatim. Desktop's
 * `remarkGfmSubset` docstring names that same hazard and reports the same non-reproduction;
 * `MarkdownTextParsingTest` pins it here as `AssistantMarkdown.test.tsx` pins it there.
 */
internal fun tildeCanOpen(
    source: CharSequence,
    start: Int,
    end: Int,
): Boolean {
    val before = source.getOrNull(start - 1)
    val after = source.getOrNull(end)
    if (isFlankingWhitespace(after)) return false
    return !isFlankingPunctuation(after) ||
        isFlankingWhitespace(before) ||
        isFlankingPunctuation(before)
}

/** The mirror of [tildeCanOpen] — GFM's right-flanking rule, the whole of "can close". */
internal fun tildeCanClose(
    source: CharSequence,
    start: Int,
    end: Int,
): Boolean {
    val before = source.getOrNull(start - 1)
    val after = source.getOrNull(end)
    if (isFlankingWhitespace(before)) return false
    return !isFlankingPunctuation(before) ||
        isFlankingWhitespace(after) ||
        isFlankingPunctuation(after)
}

/** Start-of-input and end-of-input count as whitespace, per the flanking definition. */
private fun isFlankingWhitespace(character: Char?): Boolean = character == null || character.isWhitespace()

private fun isFlankingPunctuation(character: Char?): Boolean =
    character != null && !character.isLetterOrDigit() && !character.isWhitespace()

private fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}
