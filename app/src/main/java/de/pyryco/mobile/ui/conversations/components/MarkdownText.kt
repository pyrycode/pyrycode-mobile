package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val ListItemIndent = 8.dp

// A reader item's blocks after its first paragraph: the hang the marker row gave them before #1533.
private val ReaderListContinuationIndent = 14.dp

// A list item's own syntax tokens, as opposed to the blocks it contains.
private val ListItemSyntax =
    setOf(
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.EOL,
    )

private val BlockquoteBarWidth = 4.dp
private val BlockquoteContentIndent = 12.dp
private val ReaderQuoteBarWidth = 3.dp

// The code area's treatment from Figma `134:4809` (`Code` › `Header` / `Content`), read off the node
// rather than carried over from #130's tile: its width is a 741dp desktop measure and does not
// transfer, but these do.
private val CodeBlockCornerRadius = 6.dp
private val CodeBlockBorderWidth = 1.dp
private val CodeBlockDividerWidth = 1.dp
private val CodeBlockHorizontalPadding = 16.dp
private val CodeBlockHeaderVerticalPadding = 8.dp
private val CodeBlockBodyVerticalPadding = 12.dp

// Gap between the code viewport and the copy rail; the control's own 6dp touch padding sits on top.
private val CodeBlockCopyRailGap = 8.dp

// The control's 6dp touch padding, subtracted so the glyph itself lands on the body's 16dp / 12dp insets.
private val CodeBlockCopyEndPadding = 10.dp
private val CodeBlockCopyBottomPadding = 6.dp

// Same value and reasoning as `MessageMetaRow`'s META_CONTENT_ALPHA (#644): the design names
// `Schemes/Inverse Primary`, which reads as de-emphasis only against the dark reference frame and is
// near-invisible on the light scheme's `background` fill. An alpha off the block's own content colour
// reads in both schemes.
private const val CODE_COPY_ALPHA = 0.80f

/** Tags the language header so tests can assert its absence on a block with no info string. */
internal const val CODE_BLOCK_HEADER_TAG = "code-block-header"
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
internal const val MAX_TABLE_COLUMNS = 32
internal const val MAX_TABLE_ROWS = 256

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
internal val MarkdownFlavour = GFMFlavourDescriptor()

/** Reader blocks follow the dedicated reader reference; thread blocks retain their interactive chrome. */
enum class MarkdownPresentation { Thread, Reader }

private val ReaderLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None)

/** Body presentation; headings and tables keep their own element-specific styles. */
data class MarkdownTextStyle(
    val body: TextStyle,
    val blockSpacing: Dp = 12.dp,
    val listItemSpacing: Dp = 4.dp,
    val code: TextStyle? = null,
    val presentation: MarkdownPresentation = MarkdownPresentation.Thread,
)

/**
 * [onOpenMarkdownPath] (#1050) opts a caller in to workspace notes: a link whose target is a markdown path
 * ([markdownLinkPath]) is handed to it instead of doing nothing. Only assistant replies pass it; with the
 * default every link keeps the `http` / `https` / `mailto` allowlist, which is what the reader relies on.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenMarkdownPath: ((String) -> Unit)? = null,
    style: MarkdownTextStyle = MarkdownTextStyle(MaterialTheme.typography.bodyMedium),
) {
    val uriHandler = rememberMarkdownUriHandler(onOpenMarkdownPath)
    val root =
        remember(markdown) {
            MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
        }
    val blockStyle =
        if (style.presentation == MarkdownPresentation.Reader) style.copy(body = style.body.readerLineHeight(style.presentation)) else style
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockStyle.blockSpacing),
    ) {
        root.children.forEach { child ->
            MarkdownBlock(child, markdown, uriHandler, blockStyle)
        }
    }
}

@Composable
private fun rememberMarkdownUriHandler(onOpenMarkdownPath: ((String) -> Unit)?): UriHandler {
    val platformHandler = LocalUriHandler.current
    val currentOnOpenMarkdownPath by rememberUpdatedState(onOpenMarkdownPath)
    // Every link tap goes through this one handler, so the block walkers below keep passing a UriHandler.
    val uriHandler =
        remember(platformHandler) {
            object : UriHandler {
                override fun openUri(uri: String) = routeMarkdownLink(uri, currentOnOpenMarkdownPath, platformHandler::openUri)
            }
        }
    return uriHandler
}

internal interface StreamingMarkdownObserver {
    fun parsed(length: Int)

    fun composed(key: Int)
}

internal val LocalStreamingMarkdownObserver = staticCompositionLocalOf<StreamingMarkdownObserver?> { null }
private val LocalPendingMarkdown = staticCompositionLocalOf<PendingMarkdown?> { null }

/** Reuses the same block and inline walkers; only the mutable suffix receives pending presentation. */
@Composable
internal fun StreamingMarkdownText(
    source: String,
    caretVisible: Boolean,
    modifier: Modifier = Modifier,
    onOpenMarkdownPath: ((String) -> Unit)? = null,
    style: MarkdownTextStyle = MarkdownTextStyle(MaterialTheme.typography.bodyMedium),
) {
    val observer = LocalStreamingMarkdownObserver.current
    val cache = remember(observer) { StreamingMarkdownCache { observer?.parsed(it.length) } }
    val blocks = remember(source, cache) { cache.update(source) }
    val handler = rememberMarkdownUriHandler(onOpenMarkdownPath)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(style.blockSpacing)) {
        blocks.forEachIndexed { index, block ->
            key(block.key) {
                if (index == blocks.lastIndex) {
                    Column {
                        StreamingBlockView(block, handler, style, observer)
                        // Separate from the AST and its link/code content. Alpha keeps blink geometry stable.
                        Text("▎", Modifier.alpha(if (caretVisible) 1f else 0f), style = style.body)
                    }
                } else {
                    StreamingBlockView(block, handler, style, observer)
                }
            }
        }
        if (blocks.isEmpty()) Text("▎", Modifier.alpha(if (caretVisible) 1f else 0f), style = style.body)
    }
}

@Composable
private fun StreamingBlockView(
    block: StreamingMarkdownBlock,
    handler: UriHandler,
    style: MarkdownTextStyle,
    observer: StreamingMarkdownObserver?,
) {
    SideEffect { observer?.composed(block.key) }
    CompositionLocalProvider(LocalPendingMarkdown provides block.pending) {
        MarkdownBlock(block.node, block.source, handler, style)
    }
}

@Composable
private fun MarkdownBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    style: MarkdownTextStyle,
) {
    val pendingText = LocalPendingMarkdown.current?.blockText(node)
    if (pendingText != null) {
        Text(pendingText, style = style.body)
        return
    }
    when (node.type) {
        MarkdownElementTypes.ATX_1 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.headlineSmall.readerLineHeight(style.presentation))
        MarkdownElementTypes.ATX_2 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.titleLarge.readerLineHeight(style.presentation))
        MarkdownElementTypes.ATX_3 ->
            HeadingBlock(node, source, uriHandler, MaterialTheme.typography.titleMedium.readerLineHeight(style.presentation))
        MarkdownElementTypes.PARAGRAPH ->
            Text(
                text = buildInline(node, source, uriHandler),
                style = style.body,
            )
        MarkdownElementTypes.UNORDERED_LIST ->
            ListBlock(node, source, uriHandler, style, ordered = false)
        MarkdownElementTypes.ORDERED_LIST ->
            ListBlock(node, source, uriHandler, style, ordered = true)
        MarkdownElementTypes.BLOCK_QUOTE ->
            BlockQuoteBlock(node, source, uriHandler, style)
        GFMElementTypes.TABLE ->
            TableBlock(node, source, uriHandler)
        MarkdownElementTypes.CODE_FENCE -> {
            val code = fencedCodeText(node, source)
            val language =
                node.children
                    .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                    ?.getTextInNode(source)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            if (style.presentation == MarkdownPresentation.Reader && language == null) {
                ReaderCodeBlock(code, style.code ?: MaterialTheme.typography.bodySmall)
            } else {
                CodeBlock(
                    code,
                    language,
                    copyable = true,
                    textStyle = style.code ?: MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                )
            }
        }
        MarkdownElementTypes.CODE_BLOCK ->
            if (style.presentation == MarkdownPresentation.Reader) {
                ReaderCodeBlock(indentedCodeText(node, source), style.code ?: MaterialTheme.typography.bodySmall)
            } else {
                CodeBlock(
                    indentedCodeText(node, source),
                    language = null,
                    copyable = true,
                    textStyle = style.code ?: MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                )
            }
        else -> {
            val text = node.getTextInNode(source).toString().trim()
            if (text.isNotEmpty()) {
                Text(text = text, style = style.body)
            }
        }
    }
}

private fun TextStyle.readerLineHeight(presentation: MarkdownPresentation): TextStyle =
    if (presentation == MarkdownPresentation.Reader) copy(lineHeightStyle = ReaderLineHeightStyle) else this

/**
 * The heading's content is nested ONE LEVEL DOWN, and that is the whole subtlety (#768). `# Title`
 * parses as `ATX_HEADER [#]` beside `ATX_CONTENT [ Title]`, with the marker's whitespace inside the
 * content node — so a filter over the heading's own children (what this did from #129) never saw
 * that token, and every heading rendered a space indented from the paragraphs around it. Walking
 * `ATX_CONTENT` puts the markers outside the walk by construction: the opening one, the closing one
 * of the `## Title ##` form, and any trailing `EOL`.
 *
 * Only the EDGE whitespace goes, via the same [trimmedContent] the table cells use. `ATX_CONTENT`'s
 * interior `WHITE_SPACE` tokens are the real spaces between words — `` ## `code` and **bold** ``
 * lexes as `WHITE_SPACE, CODE_SPAN, WHITE_SPACE, TEXT, WHITE_SPACE, STRONG` — so dropping every one
 * of them a level deeper would render `` `code`andbold ``.
 *
 * A marker-only `##` has no `ATX_CONTENT` child at all, hence the null arm rather than a lookup that
 * assumes one: it renders empty, as it already did, in a renderer that is total by design.
 */
@Composable
private fun HeadingBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    style: androidx.compose.ui.text.TextStyle,
) {
    val colors = currentInlineColors()
    val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
    val text =
        buildAnnotatedString {
            if (content != null) {
                appendInlineChildren(content.trimmedContent(), source, uriHandler, colors)
            }
        }
    Text(text = text, style = style)
}

@Composable
private fun ListBlock(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    style: MarkdownTextStyle,
    ordered: Boolean,
) {
    val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    Column(verticalArrangement = Arrangement.spacedBy(style.listItemSpacing)) {
        items.forEachIndexed { index, item ->
            val checkBox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            val blocks = item.children.filter { it.type !in ListItemSyntax }
            val marker = if (ordered) "${index + 1}." else "•"
            val lead = blocks.firstOrNull()
            if (style.presentation == MarkdownPresentation.Reader && checkBox == null && lead?.type == MarkdownElementTypes.PARAGRAPH) {
                // #1533: Figma `553:2574` writes each item as one `•  text` paragraph, so a wrapped line
                // returns to the list's edge instead of hanging under the item text.
                Column(verticalArrangement = Arrangement.spacedBy(style.blockSpacing)) {
                    Text(
                        text =
                            buildAnnotatedString {
                                append("$marker  ")
                                append(buildInline(lead, source, uriHandler))
                            },
                        style = style.body,
                    )
                    // The frame draws no nested content; later blocks keep the old hang so nesting still reads.
                    blocks.drop(1).forEach { child ->
                        Box(Modifier.padding(start = ReaderListContinuationIndent)) {
                            MarkdownBlock(child, source, uriHandler, style)
                        }
                    }
                }
                return@forEachIndexed
            }
            Row {
                if (checkBox == null) {
                    Text(
                        text = marker,
                        style = style.body,
                    )
                } else {
                    // GFM draws the mark INSTEAD of the marker, so this arm replaces the bullet
                    // rather than preceding it. Per item and not per list: one list may mix task
                    // items with plain ones, and a plain sibling keeps its bullet.
                    TaskMark(isCheckedTaskMark(checkBox.getTextInNode(source).toString()))
                }
                Spacer(Modifier.width(ListItemIndent))
                Column(
                    verticalArrangement = Arrangement.spacedBy(style.blockSpacing),
                ) {
                    blocks.forEach { child -> MarkdownBlock(child, source, uriHandler, style) }
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
    style: MarkdownTextStyle,
) {
    // The reader follows its dedicated Figma frame's subdued rule and regular text. Thread quotes
    // keep the higher-contrast bar and italics: outlineVariant disappears against their bubble.
    val reader = style.presentation == MarkdownPresentation.Reader
    val barColor = if (reader) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier =
                Modifier
                    .width(if (reader) ReaderQuoteBarWidth else BlockquoteBarWidth)
                    .fillMaxHeight()
                    .background(barColor),
        )
        Spacer(Modifier.width(BlockquoteContentIndent))
        Column(verticalArrangement = Arrangement.spacedBy(style.blockSpacing)) {
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
                                style.body.copy(
                                    color = if (reader) MaterialTheme.colorScheme.onSurfaceVariant else style.body.color,
                                    fontStyle = if (reader) FontStyle.Normal else FontStyle.Italic,
                                ),
                        )
                    } else {
                        MarkdownBlock(child, source, uriHandler, style)
                    }
                }
        }
    }
}

/** The reader's unlabelled code panel from Figma 553:2574; a tap still copies this block's bounded source. */
@Composable
private fun ReaderCodeBlock(
    content: String,
    textStyle: TextStyle,
) {
    // Match the one- and two-line reference boxes; longer content grows naturally without a huge min constraint.
    val minimumLines = if ('\n' in content) 2 else 1
    val minimumHeight = 24.dp + with(LocalDensity.current) { textStyle.lineHeight.toDp() } * minimumLines
    val clipboard = LocalClipboardManager.current
    val copyDescription = stringResource(R.string.cd_thread_copy_code)
    Surface(
        onClick = { clipboard.setText(AnnotatedString(content.take(MAX_CLIPBOARD_CHARS))) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minimumHeight)
                .testTag("reader-code-panel")
                .semantics { contentDescription = copyDescription },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = content,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            style = textStyle.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant),
            softWrap = false,
        )
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
    // The grid conveys the table's structure, so this line has to clear WCAG 1.4.11's 3:1 against
    // the bubble it renders on — always `secondaryContainer`, since `MessageBubble` is the only
    // caller. Desktop's `--color-primary-container` does not survive that port: M3 gives
    // `primaryContainer` and `secondaryContainer` the same tone by construction (90 light / 30
    // dark), so that pairing is luminance-identical — 1.01:1 — in any palette, generated or
    // dynamic, and the collapsed grid below lands as nothing. `onSurfaceVariant` measures 7.27:1
    // light and 5.51:1 dark, and is already this file's token for the task mark and struck text.
    // Do not swap this for `outlineVariant` (1.00:1 dark) without measuring against the bubble.
    val borderColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        appendInlineChildren(cell.trimmedContent(), source, uriHandler, colors)
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

/**
 * Inline children with the source's own padding dropped — a table cell's since #681, and a
 * heading's `ATX_CONTENT` since #768, which is the identical defect one construct away.
 *
 * `| Name |` lexes the cell as `WHITE_SPACE, TEXT("Name"), WHITE_SPACE`, so walking the children as
 * they arrive renders `" Name "` — the pipes' breathing room, promoted into the content. GFM says a
 * cell's content is its inline content with leading and trailing whitespace trimmed, and here it is
 * visible rather than cosmetic: the padded string is what `textAlign` centres and end-aligns, so a
 * right-aligned column would sit a space short of its own edge.
 *
 * Trimming the TOKENS and not the built string is what keeps the span offsets honest — an
 * `AnnotatedString` trimmed after the fact carries styles that no longer line up with its text.
 */
internal fun ASTNode.trimmedContent(): List<ASTNode> =
    children
        .dropWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }
        .dropLastWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }

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

/**
 * The design's code area (#657; Figma `134:4809`, desktop's `.code-block`): a bordered box with an
 * optional language header above a divider, and the code below it.
 *
 * THE DIVIDER HANGS ON THE HEADER, not on the body, so a block with no info string has neither an
 * empty bar nor a stranded rule — it is simply the body. Desktop settled the same way.
 *
 * ONLY THE CODE VIEWPORT SCROLLS. The header sits above the scroll chain and the copy control beside
 * it in the body's `Row`, as a rail rather than an overlay: it cannot move while the code scrolls,
 * and no scroll offset can put code underneath it. The row's height is at least the control's, which
 * is what gives an empty fence room for the glyph.
 *
 * [copyable] is opt-in because `ToolCallRow` shares this chrome for code-ish tool output and
 * deliberately carries no copy control; whether that output becomes copyable is #658's call. The
 * control copies [content] — the string this block was built from — never text read back out of the
 * rendered tree.
 *
 * [textStyle] sets the code's size; the block always draws it monospace. The tool row passes the
 * design's 12sp `bodySmall` (#895); markdown keeps the default.
 */
@Composable
internal fun CodeBlock(
    content: String,
    language: String?,
    copyable: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val syntaxLanguage = remember(language) { resolveSyntaxLanguage(language) }
    val structure =
        remember(content, syntaxLanguage) {
            if (syntaxLanguage == null) null else tokeniseCode(content, syntaxLanguage)
        }
    val annotated = buildHighlightedCode(content, structure)
    val dividerColor = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodeBlockCornerRadius),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(CodeBlockBorderWidth, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column {
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(CODE_BLOCK_HEADER_TAG)
                            .drawBehind {
                                val stroke = CodeBlockDividerWidth.toPx()
                                val y = size.height - stroke / 2
                                drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), stroke)
                            }.padding(
                                horizontal = CodeBlockHorizontalPadding,
                                vertical = CodeBlockHeaderVerticalPadding,
                            ),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    Text(
                        text = annotated,
                        modifier =
                            Modifier.padding(
                                start = CodeBlockHorizontalPadding,
                                end = if (copyable) CodeBlockCopyRailGap else CodeBlockHorizontalPadding,
                                top = CodeBlockBodyVerticalPadding,
                                bottom = CodeBlockBodyVerticalPadding,
                            ),
                        style = textStyle.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                    )
                }
                if (copyable) {
                    CompositionLocalProvider(
                        LocalContentColor provides LocalContentColor.current.copy(alpha = CODE_COPY_ALPHA),
                    ) {
                        CopyTextControl(
                            text = content,
                            contentDescription = stringResource(R.string.cd_thread_copy_code),
                            modifier =
                                Modifier.padding(
                                    end = CodeBlockCopyEndPadding,
                                    bottom = CodeBlockCopyBottomPadding,
                                ),
                        )
                    }
                }
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
 * A fenced block's code exactly as authored (#657) — the one string both rendered and copied.
 *
 * WALKED LINE BY LINE, not joined from the content tokens, and that is the whole point: a blank line
 * inside a fence is two adjacent `EOL`s with no `CODE_FENCE_CONTENT` between them, so a join over
 * the content tokens alone collapsed every run of blank lines. Each `EOL` closes a line here, so an
 * empty line is still a line.
 *
 * `WHITE_SPACE` children are a container's prefix — a list item's indent, a quote's `> ` — and are
 * not code; the code's own indentation is inside the content token and survives. The walk starts
 * after the opening line's `EOL`, so the info string never enters, and stops at the closing fence.
 * A fence the streaming reveal has not closed yet ends on its last partial line, which is kept.
 */
internal fun fencedCodeText(
    fence: ASTNode,
    source: String,
): String {
    val body = fence.children.dropWhile { it.type != MarkdownTokenTypes.EOL }.drop(1)
    return codeLines(body, source, MarkdownTokenTypes.CODE_FENCE_CONTENT).joinToString("\n")
}

/**
 * An indented block's code exactly as authored (#657). The four-space marker is syntax rather than
 * code — CommonMark strips it, and desktop renders without it — so it comes off each line (a single
 * leading tab counts as the marker); any indentation beyond it is the code's own and stays.
 */
internal fun indentedCodeText(
    block: ASTNode,
    source: String,
): String =
    codeLines(block.children, source, MarkdownTokenTypes.CODE_LINE)
        .joinToString("\n") { line ->
            if (line.startsWith('\t')) line.drop(1) else line.drop(line.takeWhile { it == ' ' }.length.coerceAtMost(4))
        }

/**
 * The lines [tokens] spell out: each [contentType] token appends to the current line and each `EOL`
 * ends it. A trailing line with no `EOL` after it is kept only if it holds content, so a block that
 * ends on a line break does not grow a phantom empty line.
 */
private fun codeLines(
    tokens: List<ASTNode>,
    source: String,
    contentType: org.intellij.markdown.IElementType,
): List<String> {
    val lines = mutableListOf<String>()
    val line = StringBuilder()
    var open = false
    for (token in tokens) {
        when (token.type) {
            contentType -> {
                line.append(token.getTextInNode(source))
                open = true
            }
            MarkdownTokenTypes.EOL -> {
                lines += line.toString()
                line.clear()
                open = false
            }
            MarkdownTokenTypes.CODE_FENCE_END -> break
        }
    }
    if (open) lines += line.toString()
    return lines
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
    val pending: PendingMarkdown? = null,
)

@Composable
private fun currentInlineColors(): InlineColors =
    InlineColors(
        codeSpanBackground = MaterialTheme.colorScheme.surfaceContainer,
        link = MaterialTheme.colorScheme.primary,
        struck = MaterialTheme.colorScheme.onSurfaceVariant,
        pending = LocalPendingMarkdown.current,
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

/**
 * The text [nodes] render as, without styles or links (#1067): the same walk [buildInline] runs, so the
 * reader's plain-text copy drops exactly the delimiters the screen hides. The handler and colours are inert,
 * since nothing here is drawn or tapped.
 */
internal fun inlineText(
    nodes: List<ASTNode>,
    source: String,
): String =
    buildAnnotatedString {
        appendInlineChildren(nodes, source, InertUriHandler, InertInlineColors)
    }.text

private object InertUriHandler : UriHandler {
    override fun openUri(uri: String) = Unit
}

private val InertInlineColors = InlineColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)

internal fun streamingInlineText(
    nodes: List<ASTNode>,
    source: String,
    pending: PendingMarkdown,
): String = buildAnnotatedString { appendInlineChildren(nodes, source, InertUriHandler, InertInlineColors.copy(pending = pending)) }.text

private fun AnnotatedString.Builder.appendInline(
    node: ASTNode,
    source: String,
    uriHandler: UriHandler,
    colors: InlineColors,
) {
    val pending = colors.pending
    if (pending?.isHidden(node) == true) return
    if (pending?.isLiteral(node) == true) {
        append(pending.text(node))
        return
    }
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
                            // The handler decides: a markdown path, an allowed scheme, or nothing.
                            (link as? LinkAnnotation.Url)?.url?.let(uriHandler::openUri)
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
                append(pending?.text(node) ?: node.getTextInNode(source).toString())
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
    val runs =
        singleTildeRuns(children, source).filter { (open, close) ->
            colors.pending?.isHidden(children[open]) != true &&
                colors.pending?.isHidden(children[close]) != true
        }
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

// A trailing `:line` or `:line:column`, digits only, as editors and claude write a location.
private val LinkLineSuffix = Regex("""(:\d+){1,2}$""")

// RFC 3986's scheme: a letter, then letters, digits, `+`, `-` or `.`, then the colon.
private val LinkScheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

/**
 * The workspace path a link [target] names (#1050), or `null` when it is not a markdown path: after dropping
 * a trailing `#fragment` and then a trailing `:line` or `:line:column`, it has no URL scheme and ends in `.md`
 * or `.markdown`, in any case. The suffix goes before the scheme check, or `Plan.md:12` would read as the
 * scheme `plan.md`. The path is returned as written: the phone never decodes, resolves or confines it,
 * the daemon does.
 */
internal fun markdownLinkPath(target: String): String? {
    val path = target.substringBeforeLast('#').replace(LinkLineSuffix, "")
    if (LinkScheme.containsMatchIn(path)) return null
    return path.takeIf { it.endsWith(".md", ignoreCase = true) || it.endsWith(".markdown", ignoreCase = true) }
}

/**
 * Where a tapped link [target] goes (#1050): a markdown path to [onOpenMarkdownPath] when the caller opted
 * in, and otherwise to [openUri] only for an allowed scheme. Anything else does nothing.
 */
internal fun routeMarkdownLink(
    target: String,
    onOpenMarkdownPath: ((String) -> Unit)?,
    openUri: (String) -> Unit,
) {
    val path = onOpenMarkdownPath?.let { markdownLinkPath(target) }
    when {
        path != null -> onOpenMarkdownPath(path)
        isSafeLinkScheme(target) -> openUri(target)
    }
}

internal fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}
