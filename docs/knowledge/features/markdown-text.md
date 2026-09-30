# MarkdownText

GFM renderer for assistant-message content (#129; fenced-code styling extended in #130; tables, task lists and strikethrough added in #681 by switching the parser flavour from CommonMark to GFM; code-block chrome restyled and given a per-block copy control in #657). Single public composable that takes a markdown source string and renders it into native Compose primitives — body styling supplied by the caller or default theme token, every link routed through `LocalUriHandler`, every container colour pulled from `MaterialTheme.colorScheme`. No Android-View interop seam; the renderer is pure Compose end-to-end.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`). Sibling of [`MessageBubble`](./message-bubble.md). Library choice rationale: [ADR 0002](../decisions/0002-markdown-renderer-library.md) — the parser library choice (`org.jetbrains:markdown`) and the "own the renderer" decision still stand; the *flavour* it names (`CommonMarkFlavourDescriptor`) does not — #681 switched to `GFMFlavourDescriptor` and the ADR predicted exactly that move in its Consequences section. [ADR 0003](../decisions/0003-syntax-highlighter-library.md) covers the code-block syntax highlighter, unaffected by the flavour change.

## What it does

Parses an input string with `org.jetbrains:markdown`'s `MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(...)` and walks the resulting AST, dispatching each top-level block to a Compose composable. Block kinds supported: ATX headings (h1–h3), paragraphs, ordered and unordered lists, task lists, blockquotes, fenced code blocks, indented code blocks, pipe tables. Inline kinds supported: bold (`STRONG`), italic (`EMPH`), inline code (`CODE_SPAN`), inline links (`INLINE_LINK`), strikethrough (both `~~double~~` and `~single~` tilde forms). `org.jetbrains:markdown` offers no per-construct registration, so the single flavour switch that unlocks these three constructs also changes what two *unwanted* node kinds parse as — bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) — see [Edge cases / limitations](#edge-cases--limitations) for what they render as and why. Unsupported AST node kinds (HTML, horizontal rules) fall back to rendering the raw source text of that node using the selected body style — the renderer never throws. Images are a separate case, not a fallback one — see the corrected claim in [Link safety](#link-safety--scheme-allowlist).

## Public surface

```kotlin
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenMarkdownPath: ((String) -> Unit)? = null,
    style: MarkdownTextStyle = MarkdownTextStyle(MaterialTheme.typography.bodyMedium),
)
```

`MarkdownTextStyle` carries `body`, `blockSpacing`, `listItemSpacing`, optional `code`, and
`presentation` (default `MarkdownPresentation.Thread`). Finished and streaming
[`MessageBubble`](message-bubble.md) use M3 `bodyMedium` (14sp/20sp), 12dp block gaps and
4dp sibling-item gaps. The [reader](markdown-reader-screen.md#what-it-does) selects
`MarkdownPresentation.Reader`, `bodyLarge` (16sp/24sp), 12dp/6dp gaps and 13sp/20sp code.

The reader uses untrimmed line-height boxes for headings and prose, including nested list and quote
text; Android's default trimmed boxes otherwise shorten the Figma paragraph and list. Thread line
boxes stay as before. Headings and tables keep their element-specific typography. The presentation
choice changes code and quote appearance, while parser, inline links and routing stay shared.

**No `color` parameter** — text inherits `LocalContentColor.current`; assistant bubbles and the reader
supply `onSurface`.

**`onOpenMarkdownPath` is opt-in.** Only the assistant reply paths pass this callback to open workspace
notes. With the default `null`, including inside the reader, workspace paths stay inert. Allowed
`http`, `https` and `mailto` links use the ambient `LocalUriHandler` in either case. See
[Markdown-path links](#markdown-path-links-since-1050).

## How it works

### Parse memoisation

```kotlin
val root = remember(markdown) {
    MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
}
```

The parse is keyed on the `markdown` string itself — the AST is rebuilt only when the source string changes. Typical assistant messages parse in well under a millisecond; the `remember` is defensive against pathological multi-KB messages, not a hot-path optimisation. Streaming (#184) re-parses per reveal tick (~50/sec) — that's still sub-millisecond per parse and well inside frame budget; the blink toggle adds another 2 parses/sec on top. Not the place to pre-optimise.

### Block dispatch

`MarkdownBlock(node, source, uriHandler, style)` is a `when (node.type)` over `MarkdownElementTypes`:

| AST type | Renders as |
|---|---|
| `ATX_1` | `Text(buildInline(...), style = headlineSmall)` |
| `ATX_2` | `Text(buildInline(...), style = titleLarge)` |
| `ATX_3` | `Text(buildInline(...), style = titleMedium)` |
| `PARAGRAPH` | `Text(buildInline(...), style = style.body)` |
| `UNORDERED_LIST` | `Column { … Row { Text("•"); Spacer; Column { recurse } } … }` — an item carrying a `GFMTokenTypes.CHECK_BOX` child renders `TaskMark(checked)` instead of the bullet; see [Task lists](#task-lists) |
| `ORDERED_LIST` | same but marker is `"${index + 1}."` (1-based); both list kinds dispatch through the same `ListBlock`, so a `CHECK_BOX` child replaces the marker here too — untested directly, but true by the shared code path |
| `BLOCK_QUOTE` | Thread: 4dp `onSurfaceVariant` bar and italic paragraphs. Reader: 3dp `outlineVariant` bar, 12dp gap and regular `bodyLarge` / `onSurfaceVariant` text. |
| `GFMElementTypes.TABLE` | `TableBlock(...)` — see [Tables](#tables) |
| `CODE_FENCE` | Reader unlabelled fence: `ReaderCodeBlock`; labelled fence and thread: `CodeBlock` with copy control — see [Code blocks](#code-blocks). |
| `CODE_BLOCK` | Reader indented block: `ReaderCodeBlock`; thread: `CodeBlock`. Both use extracted source text. |
| _else_ | `Text(node.getTextInNode(source).trim(), style = style.body)` — fallback |

`Column` arrangements use `style.blockSpacing` between top-level blocks and recursively inside list items and quotes; sibling list items use `style.listItemSpacing`. Gaps come from the columns, not per-element padding.

### Heading inline content

A heading's own children are just the marker and one content node — `# Heading with text` parses as `ATX_1 [ATX_HEADER, ATX_CONTENT]`, with the marker's whitespace nested *inside* `ATX_CONTENT`, not beside it. `HeadingBlock` takes that single `ATX_CONTENT` child (`firstOrNull`, since a marker-only `##` has none) and walks its `trimmedContent()` — the same edge-whitespace trim [Tables](#tables) uses for cells — through `appendInline`. Trimming the edges is not the same fix as filtering one level deeper: `ATX_CONTENT`'s *interior* `WHITE_SPACE` tokens are the real spaces between words (`` ## `code` and **bold** `` lexes as `WHITE_SPACE, CODE_SPAN, WHITE_SPACE, TEXT, WHITE_SPACE, STRONG`), so dropping every whitespace child rather than only the edges would render `` `code`andbold ``. The closed form `## Trailing ##` keeps its closing marker outside `ATX_CONTENT` (a heading's own second `ATX_HEADER`-shaped token) but its trailing space inside, so both edges come off together. Fixed in #768, which #681 had already worked around at the test level (`substring = true` on the one heading assertion that existed) rather than at the source — a `substring` match is exactly the shape of test that can pass on this defect. `MarkdownTextParsingTest` pins all four AST facts the fix rests on — the `[ATX_HEADER, ATX_CONTENT]` direct-child shape, interior-vs-edge whitespace, the closed form's trailing space, and the marker-only heading's missing content child — so a parser bump that moves any of them reddens a unit test instead of silently re-indenting every heading in every reply.

### Inline dispatch

`appendInline(node, source, uriHandler, colors: InlineColors)` is a `when (node.type)` over `MarkdownElementTypes` and `MarkdownTokenTypes`, writing into an `AnnotatedString.Builder`. `InlineColors` (`codeSpanBackground`, `link`, `struck`) replaced the two trailing `Color` parameters when #681 added a third — a sixth positional argument on a recursive function is where call sites start transposing arguments.

| AST type | Builds |
|---|---|
| `EMPH` | `withStyle(SpanStyle(fontStyle = Italic)) { recurse children }` |
| `STRONG` | `withStyle(SpanStyle(fontWeight = Bold)) { recurse children }` |
| `GFMElementTypes.STRIKETHROUGH` | `withStyle(SpanStyle(textDecoration = LineThrough, color = onSurfaceVariant)) { recurse children, filtering out the two TILDE tokens }` — only the `~~double~~` form arrives as this node; see [Strikethrough](#strikethrough) for the `~single~` form, which does not |
| `CODE_SPAN` | `withStyle(SpanStyle(fontFamily = Monospace, background = surfaceContainer)) { append(text.trim('\`')) }` |
| `INLINE_LINK` | `withLink(LinkAnnotation.Url(url, styles = TextLinkStyles(SpanStyle(color = primary, textDecoration = Underline)), linkInteractionListener = …)) { append(linkText) }` |
| `EOL` | `append(" ")` — collapses CommonMark soft-breaks to a single space |
| _leaf token, no children_ | `append(node.getTextInNode(source).toString())` — this is the arm `GFM_AUTOLINK` hits (a childless leaf), so a bare URL renders as its own text and never as a link |
| _element with children_ | recurse into each child — this is the arm `INLINE_MATH` hits, so `$20 and $30` rebuilds verbatim from its `DOLLAR` / `TEXT` / `WHITE_SPACE` leaves |

Link text is built from the `LINK_TEXT` child's children, filtering `LBRACKET` / `RBRACKET` tokens. URL is the `LINK_DESTINATION` child's text, trimmed and stripped of surrounding `<` / `>` (for angle-bracket destinations) — read from the node's own source span rather than from its children, so it stays byte-identical to the CommonMark case even though GFM nests a `GFM_AUTOLINK` child inside `LINK_DESTINATION` where CommonMark held `TEXT`/`:`/`TEXT`. Code-span text is the node's raw text trimmed of backticks.

**Every sibling-list walk goes through `appendInlineChildren(children, source, uriHandler, colors)`, not a per-child loop.** `appendInline`'s own `else` arm, its `EMPH` / `STRONG` / `STRIKETHROUGH` arms, and `HeadingBlock` all pass their (already-filtered) child list through it. The seam exists for one reason: pairing bare `~single~` tildes needs to see siblings together, which a node walked one at a time cannot do — see [Strikethrough](#strikethrough).

**`internal fun inlineText(nodes, source): String`, since #1067,** runs this same `appendInlineChildren` walk into a throwaway `AnnotatedString` with an inert `UriHandler` and unspecified `InlineColors`, then returns `.text`. It lives here rather than in `MarkdownConversions.kt` because it has to be *this* walk, not a second hand-written one — [the reader's plain-text copy](markdown-reader-screen.md#copy-and-refresh-menu-since-1067) drops exactly the delimiters and link targets this renderer hides by construction, and a parser or dispatch change here can't silently desync the two.

### Tables

`GFMElementTypes.TABLE` dispatches to `TableBlock`. The `HEADER`'s `CELL` children are row 0, each `ROW`'s `CELL` children a further row; column count is row 0's size. A cell's own children are ordinary inline nodes, walked by the same `appendInlineChildren` as everywhere else.

**Column-major layout**, not row-major: a `Row` of per-column `Column`s, each `Column` at `IntrinsicSize.Max` width with its cells at `fillMaxWidth`. That is what gives a column its own widest-cell width with no measuring pass, and what gives `textAlign` a box to align within. Rows stay in step across columns only because **cells never wrap** — each is one line of `bodyMedium` (`softWrap = false`, `maxLines = 1`), whose `lineHeight` comes from the type style rather than the glyphs, so a monospace or bold cell sits at the same height as plain text. A table too wide for the bubble scrolls horizontally (`Modifier.horizontalScroll` on the outer `Box`) instead of reflowing; the bubble and thread keep their own width because the scroll is on the table's content, not on the message column.

**Alignment** comes from `parseTableAlignments(delimiterRow)` (`internal`, pure, unit-tested): splits the delimiter row on `|`, drops a blank leading/trailing segment (GFM permits the row with or without outer pipes), and reads each spec — leading-and-trailing `:` → `Center`, trailing `:` only → `End`, anything else → `Start`. A column the delimiter row doesn't describe defaults to `Start`.

**Cell content is trimmed at the token level, not the built string.** `| Name |` lexes a cell's children as `WHITE_SPACE, TEXT("Name"), WHITE_SPACE` — walking them as they arrive renders `" Name "`, and the padding is visible rather than cosmetic: it's what `textAlign` centres or end-aligns, so a right-aligned column would sit a space short of its own edge. `ASTNode.trimmedContent()` (`internal` since #1067, shared with the reader's plain-text heading and table-cell copy) drops leading/trailing `WHITE_SPACE` children before the walk; trimming an already-built `AnnotatedString` instead would leave its span offsets pointing past the trimmed text. Found by the first device run of `MarkdownTextTest`, not by preview inspection — an exact-text assertion catches it where a `substring` one would not.

**Ragged input degrades, it doesn't error.** A short row reads as empty cells (`cells.getOrNull(column)`); a long row's overflow is dropped, and there is nothing addressable to recover — the lexer has already fused that overflow into the trailing separator token. `columnCount` and `MAX_TABLE_ROWS` are also where two fan-out caps apply: `MAX_TABLE_COLUMNS = 32`, `MAX_TABLE_ROWS = 256` (both `internal` since #1067, shared with the reader's plain-text table copy). The table is one composable `Text` per cell with no lazy layout, so a hostile or merely malformed reply amplifies a few kilobytes of pipes into tens of thousands of measured composables, re-paid on every streaming reveal tick (#184 re-parses ~50×/sec). Both bounds sit far above any real reply — flagged in the #681 security review as a SHOULD FIX, applied with two `take()` calls rather than a heuristic. A table hitting either cap silently drops the excess rows/columns; nothing surfaces that a cap was hit.

**Collapsed borders in `onSurfaceVariant`, not `primaryContainer`.** Each cell draws its own top and start edges (`tableCellEdges`); the table's content `Row` draws the closing end and bottom edges (`tableOuterEdges`) — every internal grid line is drawn exactly once, where a four-sided `Modifier.border` per cell would double the seam between neighbours. The colour was shipped as `MaterialTheme.colorScheme.primaryContainer` (transcribed from desktop's `--color-primary-container`) and was invisible — **1.01:1** contrast against the bubble, in both themes. That isn't a near-miss on this one palette: M3 assigns `primaryContainer` and `secondaryContainer` the same tone by construction (90 light / 30 dark), and the assistant `MessageBubble` grounds it on `secondaryContainer`, so the pairing is luminance-identical in *any* M3 palette, generated or dynamic. `onSurfaceVariant` measures 7.27:1 light / 5.51:1 dark against that bubble and is the only measured candidate clearing WCAG 1.4.11's 3:1 in both themes; it's now the shared token for the table grid, the task-mark border, struck text, and — since #770 — the blockquote bar: four de-emphasised structural elements, one contrast-checked token. `outlineVariant` is explicitly not a substitute (1.00:1 dark on the same ground); [`BlockQuoteBlock`](#block-dispatch)'s bar carried that same defect, pre-existing since #129, until #770 moved it to `onSurfaceVariant`. **The lesson generalises: porting a desktop CSS custom property onto the M3 slot of the same name carries no contrast guarantee**, because the two design systems ground their message content on different container colours. Both bindings carry a comment recording the contrast bar any future replacement has to clear, since a pixel test would cost more than it proves.

### Task lists

A `LIST_ITEM` carrying a `GFMTokenTypes.CHECK_BOX` child renders a `TaskMark(checked)` **instead of** its bullet or number — GFM replaces the marker rather than adding to it, and `CHECK_BOX` joins `ListBlock`'s existing token filter set so it never reaches the fallback as a stray `[ ]`. A list can mix task items with plain ones; a plain sibling keeps its bullet. `isCheckedTaskMark(rawCheckBox: String): Boolean` (`internal`, pure, unit-tested) reads a token's raw text (`"[x] "`, `"[X] "`, `"[ ] "`) case-insensitively.

`TaskMark` is a 14dp bordered square (`onSurfaceVariant`, 3dp corner radius) holding a `Check` icon only when checked, inset from the item's top edge so it sits on the first text line rather than at the item's top (desktop's `vertical-align: -3px`).

**Inert by construction, and the omissions are the point.** The mark carries `Modifier.semantics { contentDescription = … }` (`markdown_task_mark_checked` / `markdown_task_mark_unchecked`, both static strings with no format argument) and *nothing else* — no `clickable`, no `toggleable`, no `ToggleableState` semantics, no `focusable`. A bare `contentDescription` on a non-actionable node is announced by TalkBack and offers no action to take, which is exactly "exposes the state, is not actionable"; `MarkdownTextTest` asserts `assertHasNoClickAction()` on both states rather than resting on the description alone. A `Checkbox` would have been shorter and wrong — the mark renders a message that has already been sent, so there is nothing for a tap to change. The description is chosen by the boolean alone, never by the item's own text, so no daemon-authored content reaches this announcement path.

### Strikethrough

Two source forms, because the parser only recognises one of them as a node. `~~double~~` arrives as a `GFMElementTypes.STRIKETHROUGH` element (see the [Inline dispatch](#inline-dispatch) table). `~single~` does not: this library's `StrikeThroughDelimiterParser` has no single-tilde option (its only constructor is no-arg), so `~x~` leaves bare `GFMTokenTypes.TILDE` tokens as ordinary siblings. Desktop strikes that form too — micromark's `singleTilde` default is on — so mobile reassembles it at render time to hold parity.

**The pairing is flanking-aware, not positional**, and the reason is a concrete failure mode rather than a spec nicety: a line carrying two home-relative paths, `~/a ~/b`. Pairing "the next tilde closes the last one" would strike `/a ` in the middle of a path. Under GFM's flanking rule the second tilde is preceded by whitespace, so it cannot close; the first finds no closer; the line renders verbatim. This is the same hazard desktop's `remarkGfmSubset` docstring names and reports as non-reproducing, and it's pinned here the same way, in `MarkdownTextParsingTest`.

`tildeCanOpen(source, start, end)` / `tildeCanClose(source, start, end)` (`internal`, pure over a string and offsets, unit-tested) implement GFM's left-/right-flanking test: for `tildeCanOpen`, the following character must not be whitespace, and either it isn't punctuation or the preceding character is whitespace/punctuation; `tildeCanClose` is the mirror. Start- and end-of-input both count as whitespace. `singleTildeRuns` (private) does the actual pairing in **one forward pass holding at most one pending opener** — deliberately not "for each opener, search forward for a closer," which is quadratic in the tildes of one paragraph and reachable with a line of lone `~` characters (flagged in the #681 security review, folded into the design before it shipped). Closing is tried before opening at each tilde, because a tilde can be both and CommonMark resolves that the same way. Anything left unpaired — an unclosed `~~x` included — renders as its literal `~` characters, the same total-fallback property the rest of the renderer relies on.

`buildInline(node, source, uriHandler): AnnotatedString` is the `@Composable` wrapper that reads `MaterialTheme.colorScheme.surfaceContainer` and `.primary` once (Composable color lookups), then constructs the `AnnotatedString` via `buildAnnotatedString { appendInline(...) }`.

### Code blocks

`CodeBlock` remains the thread, labelled-fence and [`ToolCallRow`](./tool-call-row.md)
renderer, with its header, syntax treatment and visible copy control. The reader routes only
unlabelled fences and indented blocks to `ReaderCodeBlock`: a full-width `surfaceContainer` panel
using `MaterialTheme.shapes.small` (8dp here), 12dp inset, 13sp/20sp monospace
`onSurfaceVariant` text and horizontal scrolling. Its panel tap copies that block's extracted
source, bounded by `MAX_CLIPBOARD_CHARS`; the minimum height is capped at two text lines.
Labelled reader fences retain `CodeBlock` and its visible copy action. Keep this dispatch by
language presence: the [reader reference](markdown-reader-screen.md#what-it-does) depicts an
unlabelled fence, while existing labelled-fence copy behavior remains part of the product.
[The thread code comparison](https://github.com/pyrycode/pyrycode-mobile/blob/44ac0889/app/src/androidTest/assets/thread-message-1207/code-boundary-side-by-side.png)
uses node `132:3959` (inspected 2026-09-29). [The reader comparison](../../../app/src/androidTest/assets/markdown-reader-1291/overlay-difference.png)
uses node `553:2574` (inspected 2026-09-30) and shows the plain panel and quote rule.

Visual structure since #657 (Figma `134:4809`, the assistant container's `Code` instance — header/divider/body, read off the node rather than #130's flat tile):

```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(CodeBlockCornerRadius),        // 6.dp
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(CodeBlockBorderWidth, MaterialTheme.colorScheme.primaryContainer),
) {
    Column {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                modifier = Modifier.fillMaxWidth()
                    .testTag(CODE_BLOCK_HEADER_TAG)
                    .drawBehind { /* 1dp onPrimaryContainer line along the bottom edge */ }
                    .padding(horizontal = CodeBlockHorizontalPadding, vertical = CodeBlockHeaderVerticalPadding),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Text(annotated, softWrap = false, style = textStyle.copy(fontFamily = Monospace), ...)
            }
            if (copyable) {
                CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = CODE_COPY_ALPHA)) {
                    CopyTextControl(text = content, contentDescription = stringResource(R.string.cd_thread_copy_code))
                }
            }
        }
    }
}
```

**The divider hangs on the header, not the body** (desktop's decision, ported rather than re-derived): it's drawn as the header `Text`'s own bottom edge via `drawBehind`, so a block with no info string — an indented block, or a bare fence with no language — renders as `Column { Row(body) }` with neither an empty header bar nor a stranded rule. `CODE_BLOCK_HEADER_TAG` exists to let tests assert that absence directly rather than inferring it from missing text.

**Only the code viewport scrolls.** The header sits above the scroll chain (unchanged from #130's anchoring behaviour) and the copy control is a body-row sibling *outside* the `horizontalScroll` `Box`, not an overlay on top of it — a fixed right-hand rail, per the same anchoring rule the language label already followed. It cannot end up on top of the code at any scroll offset, because it was never inside the scrolling subtree to begin with. The body `Row`'s height is at least the control's own height, which is what gives an empty fence room to contain the glyph without the row collapsing under it. The code `Text`'s `end` padding drops from the full `CodeBlockHorizontalPadding` to the narrower `CodeBlockCopyRailGap` when `copyable` is true, so the rail has a fixed gap rather than overlapping the last characters of a short line.

**Copy control and source exactness.** The mounted control is [`CopyTextControl`](./message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644) from `MessageMetaRow.kt` (imported, not reimplemented — it was left `internal` rather than file-private in #644 for exactly this reuse). It copies `content` — the block's own extracted source string, never text read back out of the rendered `Text` node — so two code blocks in one message each copy only their own text, and a copy can never pick up characters from a sibling block or the surrounding message. Its glyph tint is `LocalContentColor.current.copy(alpha = CODE_COPY_ALPHA)` (0.80), **not** the design's named `Schemes/Inverse Primary` token: `#644` already hit this for the meta row's own copy glyph — `inversePrimary` reads as near-invisible de-emphasis only against the dark reference frame, and is `#9DCBFC`-on-`#F8F9FF` (illegible) against this block's light-scheme `background` fill. An alpha expression off the block's own content colour (here, `onBackground`) reads correctly in both schemes; the design's other named tokens (fill, border, divider, header label colour) are taken as given because none of them hit that trap.

Each `CodeBlock` invocation still gets its own `rememberScrollState()` — independent fenced blocks scroll independently, unchanged from #130.

#### Exact-source extraction

The fenced and indented arms no longer join their content tokens directly — `fencedCodeText(fence: ASTNode, source: String)` and `indentedCodeText(block: ASTNode, source: String)` (both `internal`, pure, unit-tested) walk each block's children line by line instead, and the difference is not cosmetic. The old `joinToString("\n")` over content tokens alone silently collapsed runs of blank lines, because a blank line inside a fence arrives as two adjacent `EOL` tokens with **no** `CODE_FENCE_CONTENT` between them to join: `a\n\n\nb` rendered — and copied — as `a\nb`. The shared `codeLines` helper instead treats each `EOL` as closing the current line (so an empty line is still a line), skips a fence's container-prefix `WHITE_SPACE` children (a list item's indent, a blockquote's `> `, neither of which is code), stops at `CODE_FENCE_END`, and keeps a final unterminated line (the streaming case: a fence the reveal hasn't closed yet still returns its partial content). `indentedCodeText` additionally strips the CommonMark 4-space (or one-tab) indent marker per line before joining — that marker is syntax, not code, and any indentation past it is the code's own and survives.

This closed two visible defects at once, both following from "the copied text must be the rendered text": a fence's interior blank lines now render (previously silently dropped), and an indented block no longer shows its 4-space marker as part of the code. Rendered and copied text are the same string by construction — the copy control receives exactly `content`, and `content` is exactly what `Text` renders.

**Syntax highlighting** (#130) uses `dev.snipme:highlights` 1.1.0 — see [ADR 0003](../decisions/0003-syntax-highlighter-library.md). The library tokenises the input string and returns a `CodeStructure` with `PhraseLocation(start, end)` lists per category; `buildHighlightedCode(content, structure)` walks each list and applies `SpanStyle`s bound to `MaterialTheme.colorScheme`:

| Category | M3 slot | Span attributes |
|---|---|---|
| `keywords` | `tertiary` | `fontWeight = Medium` |
| `annotations` | `tertiary` | — |
| `strings` | `secondary` | — |
| `literals` | `primary` | — |
| `comments`, `multilineComments` | `onSurfaceVariant` | `fontStyle = Italic` |
| punctuation / operators / default | inherited `LocalContentColor.current` | — |

**Language resolution** (`resolveSyntaxLanguage` in `MarkdownText.kt`): case-insensitive, trimmed. `kotlin` / `kt` / `kts` → `KOTLIN`; `bash` / `sh` / `shell` / `zsh` → `SHELL`; `json` → `JAVASCRIPT` (no dedicated JSON lexer in the library — JSON's grammar is a subset of JS object literals so strings, numbers, and punctuation tokenise correctly); `markdown` / `md` → `null` (no Markdown lexer); anything else → `null`. When the resolver returns `null`, the library is never called — the block renders as plain monospace, the label still renders if present.

**Memoisation:** `remember(language) { resolveSyntaxLanguage(language) }` then `remember(content, syntaxLanguage) { tokeniseCode(...) }`. Re-tokenises only when `(content, language)` changes.

**Defensive total renderer:** `tokeniseCode` wraps the `Highlights.Builder().code(...).language(...).build().getCodeStructure()` call in `runCatching { … }.getOrNull()`. On throw, returns `null` and the block falls through to plain monospace. `applySpan` coerces `PhraseLocation` offsets to `(0, content.length)` before `addStyle` to harden against a future library version returning out-of-range spans (which would otherwise throw `IndexOutOfBoundsException`).

### Link safety — scheme allowlist

`#129` carries the `security-sensitive` label. The mitigation is a predicate at the bottom of `MarkdownText.kt`, `internal` since #1067 so [the reader's Copy as HTML](markdown-reader-screen.md#copy-and-refresh-menu-since-1067) shares this one allowlist rather than keeping a second copy:

```kotlin
internal fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}
```

The link's `LinkInteractionListener` consults this predicate before calling `uriHandler.openUri(url)`. **Rejected URLs no-op silently** — the visible underlined link span remains drawn, the tap does nothing. The renderer never blocks the link from rendering visually; it only blocks the navigation. Visual fidelity therefore doesn't depend on URL safety.

**Why allowlist, not denylist.** A denylist (`intent://`, `javascript:`, …) is fragile against future scheme additions and against case / whitespace tricks (`InTeNt://`, `\tjavascript:`). The allowlist is enforced at the `lowercase()` scheme prefix, so it rejects anything that doesn't normalise to `http` / `https` / `mailto`. New schemes that turn out safe later (e.g. `tel:` for click-to-dial) are an explicit one-line addition rather than a silent default.

**Out of scope** (each is acceptable for Phase 0):

- HTTPS-vs-HTTP downgrade warning — both pass.
- URL canonicalisation / IDN homograph attacks — destination trust is the user's job; the allowlist limits the *scheme*, not the *destination*.
- HTML / script content — `HTML_BLOCK` / `HTML_INLINE` hit the `else -> Text(...)` fallback and render as literal text. No execution path.
- **Image sources — corrected claim, measured during #681.** `![alt](url)` does **not** fall through to the raw-text fallback on either flavour: `IMAGE` has children under both CommonMark and GFM, so the inline walker recurses and renders `!` followed by the alt text as a tappable, scheme-gated link — this predates #681 and the flavour switch leaves it unchanged. The AC4 property that actually matters — no image is ever fetched, on either flavour — still holds and is pinned by test; there is no image loader anywhere in this renderer, so an `IMAGE` node's `INLINE_LINK` child routes through the same `isSafeLinkScheme` gate as any other link. No SSRF surface, but this file previously stated the wrong reason.
- Code-block content — plain monospace `Text`; strings are not executed or interpreted.
- Long-content DoS — Phase 0 messages are bounded; Phase 4 inherits Claude API response-size limits. #681 adds its own bound for tables specifically — see [Tables](#tables)'s `MAX_TABLE_COLUMNS` / `MAX_TABLE_ROWS`.
- **New in #681 — `GFM_AUTOLINK` and `INLINE_MATH` add no new link-like surface.** `GFM_AUTOLINK` is a childless leaf with no dispatcher arm, so a daemon cannot manufacture a tappable link out of prose that never contained link markup. `INLINE_MATH` has children but no arm either; its leaves re-append their own source text. Table cells and task-list items route through the same `appendInline` / `appendInlineChildren` as every other inline site, so they inherit this gate rather than opening a second one.

### Markdown-path links (since #1050)

`onOpenMarkdownPath` lets a workspace-note link in an assistant reply open [the in-app reader](markdown-reader-screen.md) instead of doing nothing, without touching `isSafeLinkScheme` or any block-walker signature — every private block function still takes a plain `UriHandler`.

- `internal fun markdownLinkPath(target: String): String?` — the path to send, or `null` when [target] is not a markdown path. Strips a trailing `#fragment` (from the *last* `#` — `a.md#x#y` keeps `#x` as part of the path and so is not a markdown path; harmless, since the link then just stays inert rather than misrouting), then a trailing `:line` or `:line:column` (digits only, `LinkLineSuffix`), then requires no URL scheme (`LinkScheme`, RFC 3986's `^[A-Za-z][A-Za-z0-9+.-]*:`) and a `.md` / `.markdown` suffix, any case. Stripping the `:line` suffix *before* the scheme check is what keeps `Plan.md:12` from reading as scheme `plan.md`. The stripped path is returned exactly as written — the phone never decodes, resolves or confines it; the daemon's `read_workspace_file` does.
- `internal fun routeMarkdownLink(target, onOpenMarkdownPath, openUri)` — a markdown path with a non-null callback goes to the callback and never to `openUri`; anything else falls through to the existing `isSafeLinkScheme` allowlist.
- `MarkdownText` wraps `LocalUriHandler.current` in a `remember`ed private `UriHandler` whose `openUri` calls `routeMarkdownLink` (the callback read through `rememberUpdatedState`, so a recomposition with a new lambda doesn't need a new handler instance); the `INLINE_LINK` arm's `LinkInteractionListener` now calls this wrapping handler for every link target unconditionally, rather than checking `isSafeLinkScheme` itself first.

Consumer wiring: [`MessageBubble`](message-bubble.md)'s `onOpenMarkdownLink` parameter reaches both assistant render paths (finished `MarkdownText` and the streaming `StreamingAssistantBody` / `StreamingAssistantBodyView` chain) but not `UserMessageBubble`, which never receives it. [Thread screen](thread-screen.md) and [Markdown reader screen](markdown-reader-screen.md#linked-note-live-since-1050) cover the ViewModel-side single-read guard and the route the callback ultimately drives.

### File-private spacing constants

```kotlin
private val ListItemIndent = 8.dp             // gap between bullet/number and item body
private val BlockquoteBarWidth = 4.dp
private val BlockquoteContentIndent = 12.dp   // gap between bar and quoted content
private val CodeBlockCornerRadius = 6.dp          // #657; was 8.dp under #130's flat tile
private val CodeBlockBorderWidth = 1.dp           // #657
private val CodeBlockDividerWidth = 1.dp          // #657; the header's own bottom-edge line
private val CodeBlockHorizontalPadding = 16.dp    // #657; was 12.dp
private val CodeBlockHeaderVerticalPadding = 8.dp // #657
private val CodeBlockBodyVerticalPadding = 12.dp  // #657
private val CodeBlockCopyRailGap = 8.dp           // #657; code-text end padding when copyable
private val CodeBlockCopyEndPadding = 10.dp       // #657; lands the glyph on the body's 16dp inset
private val CodeBlockCopyBottomPadding = 6.dp     // #657; lands the glyph on the body's 12dp inset
private const val CODE_COPY_ALPHA = 0.80f         // #657; same value/reasoning as META_CONTENT_ALPHA
internal const val CODE_BLOCK_HEADER_TAG = "code-block-header"   // #657; absence-assertion hook
private val TableBorderWidth = 1.dp
private val TableCellHorizontalPadding = 12.dp
private val TableCellVerticalPadding = 4.dp
private val TableCellPadding =
    PaddingValues(horizontal = TableCellHorizontalPadding, vertical = TableCellVerticalPadding)
private val TaskMarkSize = 14.dp
private val TaskMarkTickSize = 10.dp
private val TaskMarkBorderWidth = 1.dp
private val TaskMarkCornerRadius = 3.dp
private val TaskMarkTopInset = 4.dp   // sits the mark on the first text line, not the item's top edge
```

Plus two `const val`s bounding table fan-out (`MAX_TABLE_COLUMNS = 32`, `MAX_TABLE_ROWS = 256`; see [Tables](#tables)) — `internal` since #1067, the rest of this list stays file-private. Block spacing comes from `MarkdownTextStyle`, not a file-private constant: the message default is 12dp and the reader supplies its own 12dp. Text sizes use `MaterialTheme.typography` roles; the thread code role overrides line height to 20sp.

## Configuration

- **Library dependencies:** `implementation(libs.jetbrains.markdown)` (parser; catalog pin `jetbrainsMarkdown = "0.7.3"`) and `implementation(libs.snipme.highlights)` (code-block tokeniser since #130; catalog pin `snipmeHighlights = "1.1.0"`) in `app/build.gradle.kts`. No KSP, no kapt, no proguard rules for either.
- **Two strings since #681, plus one since #657:** `markdown_task_mark_checked` / `markdown_task_mark_unchecked` — static TalkBack labels for the task-mark, chosen by a boolean, carrying no format argument and no daemon-authored text. `cd_thread_copy_code` ("Copy this code block") is the accessible name for each code block's copy control, in the same `cd_thread_*` family as `MessageMetaRow`'s `cd_thread_copy_message`. Otherwise renders the input markdown verbatim; no other resource lookup.
- **No renderer-local theme overrides.** Block colours and typography come from `MaterialTheme`; prose comes from `MarkdownTextStyle.body`. The reader quote rule uses `outlineVariant`; thread quotes use `onSurfaceVariant` for contrast against their bubble. Shared text roles come from the [app type ramp](shared-typography.md). The code copy glyph uses `LocalContentColor` at `CODE_COPY_ALPHA`.
- **No DI.** Pure leaf composable; no Koin module touched.

## Previews

`MessageBubble.kt` carries the canonical preview pair for this renderer (for thread defaults), not `MarkdownText.kt` itself. `MessageBubbleMarkdownLightPreview` and `MessageBubbleMarkdownDarkPreview` both render a file-private `MARKDOWN_PREVIEW_FIXTURE` that exercises every supported element (h1/h2/h3, bold, italic, inline code, link, unordered list, ordered list, a mixed task list, both strikethrough forms beside a home-relative-paths line that must *not* strike, a three-column table with all three alignments, blockquote, fenced code blocks for Kotlin / JSON / Bash / Markdown) inside the `MessageBubble` host. Satisfies #129's AC6, #130's AC7 and #681's AC5.

## Edge cases / limitations

- **Inline-code background paints at glyph-rect bounds, not at a padded rectangle.** `SpanStyle(background = …)` on an `AnnotatedString` span has no horizontal padding option in Compose's text API — the background tints exactly the glyph rect. Short identifiers (`getUserId()`) read fine; longer code spans look tight. **Acceptable for #129.** If the tightness becomes a complaint, the replacement shape is `InlineTextContent` per code span — don't pre-build that now.
- **Soft line breaks collapse to a single space.** `EOL` tokens inside a paragraph map to `append(" ")` rather than `append("\n")`. Matches CommonMark's "soft-break = space" rendering rule. Hard breaks (two trailing spaces or a backslash before the newline) are not yet specially handled — they fall through to the same single-space behaviour. Not in AC.
- **Thread and labelled reader fences use syntax highlighting and bordered header/body chrome** — `Surface(background fill, primaryContainer border, 6dp corners)`, a header bar with a divider above the body when the fence carries a language, monospace text with token colours bound to `MaterialTheme.colorScheme`, horizontal scroll for long lines (no wrap), and a per-block copy control. See [Code blocks](#code-blocks) for the full mapping; library choice in [ADR 0003](../decisions/0003-syntax-highlighter-library.md).
- **Highlighter language coverage is bounded.** Kotlin / Bash / JSON (via the JavaScript lexer — JSON's grammar is a subset) tokenise; Markdown and any other language fall through to plain monospace but still show the language label. Adding a language is a one-line addition to `resolveSyntaxLanguage` if the library exposes it.
- **Thread indented blocks use `CodeBlock` without a header; reader indented blocks use the plain panel.** CommonMark indented blocks have no info string, so thread `CodeBlock` suppresses the header and divider.
- **Lists are flat-bulleted.** Unordered lists use `"•"`; ordered lists use `"${index + 1}."` from the 1-based item position within the list. Nested-list indentation depth comes from the recursive `MarkdownBlock` call inside the list item's `Column` — no per-level indent multiplier.
- **Blockquote appearance is presentation-specific.** Thread paragraphs remain italic with an `onSurfaceVariant` bar; reader paragraphs are regular with an `outlineVariant` bar. Nested non-paragraph blocks recurse through `MarkdownBlock`.
- **No free-text selection.** Prose, tables, lists and quotes use bare `Text`, while code blocks expose block-only copying: thread and labelled reader fences show `CopyTextControl`; plain reader panels copy on tap. The reader menu separately copies the whole note.
- **Streaming is not specially handled.** `MarkdownText` treats `markdown` as a complete, final string each composition; partial markdown (an unclosed `**bold` mid-stream) still parses (the JetBrains parser is total) and renders as best it can. #184 owns streaming-aware behaviour at the `MessageBubble` layer — `StreamingAssistantBody` appends a `▎` caret glyph to the revealed prefix and passes the result through this renderer unchanged. Streaming uses the default body style.
- **Renderer is total.** The JetBrains parser produces an AST for any input string — there is no exception path. Unsupported element kinds hit the `else` fallback (raw text using the selected body style), so the message is never blank. #681 leaned on exactly this property to switch parser flavours without suppressing anything — see the next two points.
- **Parser flavour is GFM since #681, and that changes more than the three constructs it was switched on for.** `org.jetbrains:markdown` has no per-construct registration — `GFMFlavourDescriptor` is the only off-the-shelf way to reach tables, task lists and strikethrough, and it necessarily also brings bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) into every message this renderer sees, whether or not that message uses any of the three wanted constructs.
- **Bare URLs and `$…$` render as their own literal characters — deliberately, by omission rather than suppression.** `GFM_AUTOLINK` is a childless leaf with no dispatcher arm, so `appendInline`'s `else` appends its raw text; there is no link annotation to tap. `INLINE_MATH` has children with no arm either, so the `else` recurses and its `DOLLAR` / `TEXT` / `WHITE_SPACE` leaves rebuild the `$…$` span verbatim. Block `$$…$$` reaches `MarkdownBlock`'s own `else` and renders as raw text the same way. `MarkdownTextParsingTest` pins the AST shape each depends on, because the property here rests on an *absent* branch, and an absent branch reddens nothing on its own if the library ever adds one.
- **Task marks are inert by construction.** See [Task lists](#task-lists) — no `clickable`, `toggleable`, `ToggleableState` or `focusable`, on purpose; a bare `contentDescription` is announced but offers nothing to activate.
- **Table cells never wrap, and a too-wide table scrolls rather than reflows.** See [Tables](#tables). A short row pads with empty cells; a long row's overflow is dropped with nothing addressable left to recover; `MAX_TABLE_COLUMNS` (32) / `MAX_TABLE_ROWS` (256) truncate a pathologically large table with no visible signal that truncation happened.
- **Table layout is column-major** (a `Row` of per-column `Column`s), where an HTML table is naturally row-major. No problem has been observed from this — Compose's accessibility node sort bands by vertical overlap, which recovers row-major reading order for a TalkBack pass without any code here arranging for it — but it's a consequence of a deliberate layout choice (see [Tables](#tables)'s column-width rationale) rather than an oversight, and worth knowing before "make the table row-major" is proposed as a simplification.
- **Image sources are not a raw-text fallback, on either flavour — a claim this document previously stated wrong.** `IMAGE` has children under CommonMark and under GFM, so `![alt](url)` renders `!` plus the alt text as a tappable, scheme-gated link. Unaffected by the #681 flavour switch; see [Link safety](#link-safety--scheme-allowlist) for the corrected entry.

## Related

- Ticket notes: [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/131.md`](../codebase/131.md) (`docs/knowledge/codebase/` is frozen since 2026-09-05; #681 has no per-ticket file — its record is the spec below)
- Specs: `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`, `docs/specs/architecture/681-gfm-tables-task-lists-strikethrough.md` (measured AST facts per node kind, the security review, and the `## Revisions` history behind the table-border and cell-trimming fixes), `docs/specs/architecture/768-atx-heading-leading-space.md` (the heading leading-space fix; measured AST facts for `ATX_CONTENT`, security review), `docs/specs/architecture/657-code-block-chrome-and-copy.md` (the design's header/body chrome, the exact-source extraction helpers, and the `inversePrimary`-illegibility rationale for `CODE_COPY_ALPHA`)
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (parser library choice; predicted the #681 flavour swap in its own Consequences section), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md)
- Consumers: [`MessageBubble`](./message-bubble.md) assistant variant (user messages stay plain text); [`ToolCallRow`](./tool-call-row.md) reuses the `internal CodeBlock` for code-ish tool output since #131, with the opt-in `copyable` parameter (#657) left at its `false` default — see [ToolCallRow](./tool-call-row.md)
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants; preview pairing shape)
- Local precedent for `buildAnnotatedString` / `SpanStyle` idioms: `ScannerScreen.kt:252-260`
- Downstream:
  - #184 — streaming caret + animation (landed). Operates at the `MessageBubble` layer; passes the revealed-prefix-plus-caret string through this renderer unchanged each tick. Partial code fences flow through `CodeBlock` unchanged; the `remember(content, syntaxLanguage)` re-tokenises per reveal tick (sub-millisecond on typical sizes). Tables and task lists flow through the same unchanged-signature path; `singleTildeRuns` re-runs per reveal tick behind its `children.none { TILDE }` early-out rather than being memoised, since `appendInlineChildren` builds outside composition where `remember` is unavailable
  - #657 — code block header/body chrome and per-block copy control (landed). See [Code blocks](#code-blocks); an unterminated (streaming) fence's partial content still extracts correctly, so #184's per-tick re-render carries no new edge case
  - #680 — live desktop/mobile comparison of the same replies, covering the three constructs this ticket added
  - #1050 — `onOpenMarkdownPath` routes an assistant-reply markdown-path link to [the live linked-note reader](markdown-reader-screen.md#linked-note-live-since-1050) instead of leaving it inert; see [Markdown-path links](#markdown-path-links-since-1050)
  - #1067 — the reader's [copy and refresh menu](markdown-reader-screen.md#copy-and-refresh-menu-since-1067) reuses this file's own parse: `inlineText`, `MarkdownFlavour`, `isSafeLinkScheme`, `trimmedContent`, `MAX_TABLE_COLUMNS` and `MAX_TABLE_ROWS` all went from `private`/file-private to `internal` for it, visibility-only
