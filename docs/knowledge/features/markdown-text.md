# MarkdownText

GFM renderer for assistant-message content (#129; fenced-code styling extended in #130; tables, task lists and strikethrough added in #681 by switching the parser flavour from CommonMark to GFM). Single public composable that takes a markdown source string and renders it into native Compose primitives — every text style resolved from `MaterialTheme.typography`, every link routed through `LocalUriHandler`, every container colour pulled from `MaterialTheme.colorScheme`. No Android-View interop seam; the renderer is pure Compose end-to-end.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`). Sibling of [`MessageBubble`](./message-bubble.md). Library choice rationale: [ADR 0002](../decisions/0002-markdown-renderer-library.md) — the parser library choice (`org.jetbrains:markdown`) and the "own the renderer" decision still stand; the *flavour* it names (`CommonMarkFlavourDescriptor`) does not — #681 switched to `GFMFlavourDescriptor` and the ADR predicted exactly that move in its Consequences section. [ADR 0003](../decisions/0003-syntax-highlighter-library.md) covers the code-block syntax highlighter, unaffected by the flavour change.

## What it does

Parses an input string with `org.jetbrains:markdown`'s `MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(...)` and walks the resulting AST, dispatching each top-level block to a Compose composable. Block kinds supported: ATX headings (h1–h3), paragraphs, ordered and unordered lists, task lists, blockquotes, fenced code blocks, indented code blocks, pipe tables. Inline kinds supported: bold (`STRONG`), italic (`EMPH`), inline code (`CODE_SPAN`), inline links (`INLINE_LINK`), strikethrough (both `~~double~~` and `~single~` tilde forms). `org.jetbrains:markdown` offers no per-construct registration, so the single flavour switch that unlocks these three constructs also changes what two *unwanted* node kinds parse as — bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) — see [Edge cases / limitations](#edge-cases--limitations) for what they render as and why. Unsupported AST node kinds (HTML, horizontal rules) fall back to rendering the raw source text of that node as a plain `bodyMedium` paragraph — the renderer never throws. Images are a separate case, not a fallback one — see the corrected claim in [Link safety](#link-safety--scheme-allowlist).

## Public surface

```kotlin
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
)
```

**No `style` parameter** — every text style is owned by the renderer and resolved from `MaterialTheme.typography` by element kind, satisfying AC5's "no raw `TextStyle` or `.sp` literals" contract by construction.

**No `color` parameter** — text inherits the ambient `LocalContentColor.current`. The consumer (`AssistantMessage` in [`MessageBubble`](./message-bubble.md)) wraps the call in `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { MarkdownText(...) }`. Keeps the renderer reusable from any future surface (system messages, tool cards) without changing the signature.

**No `onLinkClick` parameter** — links open via the ambient `LocalUriHandler.current.openUri(url)`. External callers cannot intercept link taps; if a future surface needs to (e.g. an in-app deep-link router), introduce a parameter at that point, not pre-emptively.

## How it works

### Parse memoisation

```kotlin
val root = remember(markdown) {
    MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown)
}
```

The parse is keyed on the `markdown` string itself — the AST is rebuilt only when the source string changes. Typical assistant messages parse in well under a millisecond; the `remember` is defensive against pathological multi-KB messages, not a hot-path optimisation. Streaming (#184) re-parses per reveal tick (~50/sec) — that's still sub-millisecond per parse and well inside frame budget; the blink toggle adds another 2 parses/sec on top. Not the place to pre-optimise.

### Block dispatch

`MarkdownBlock(node, source, uriHandler)` is a `when (node.type)` over `MarkdownElementTypes`:

| AST type | Renders as |
|---|---|
| `ATX_1` | `Text(buildInline(...), style = headlineSmall)` |
| `ATX_2` | `Text(buildInline(...), style = titleLarge)` |
| `ATX_3` | `Text(buildInline(...), style = titleMedium)` |
| `PARAGRAPH` | `Text(buildInline(...), style = bodyMedium)` |
| `UNORDERED_LIST` | `Column { … Row { Text("•"); Spacer; Column { recurse } } … }` — an item carrying a `GFMTokenTypes.CHECK_BOX` child renders `TaskMark(checked)` instead of the bullet; see [Task lists](#task-lists) |
| `ORDERED_LIST` | same but marker is `"${index + 1}."` (1-based); both list kinds dispatch through the same `ListBlock`, so a `CHECK_BOX` child replaces the marker here too — untested directly, but true by the shared code path |
| `BLOCK_QUOTE` | `Row { Box(width = 4dp, fillMaxHeight, background = outlineVariant); Spacer(12dp); Column { italic paragraphs } }` |
| `GFMElementTypes.TABLE` | `TableBlock(...)` — see [Tables](#tables) |
| `CODE_FENCE` | `CodeBlock(joined CODE_FENCE_CONTENT lines, language = first FENCE_LANG child or null)` — see [Code blocks](#code-blocks) |
| `CODE_BLOCK` | `CodeBlock(joined CODE_LINE tokens, language = null)` — indented 4-space blocks have no info string |
| _else_ | `Text(node.getTextInNode(source).trim(), style = bodyMedium)` — fallback |

The top-level `MarkdownText` wraps the dispatched children in `Column(modifier, verticalArrangement = Arrangement.spacedBy(ParagraphSpacing))` so inter-block gaps come from the column, not from per-element padding. List items use the same `spacedBy(ParagraphSpacing)` so nested blocks inside a list item separate the same way as top-level blocks.

### Heading inline content

`HeadingBlock` filters the heading node's children for `ATX_HEADER` / `WHITE_SPACE` / `EOL` tokens (the `#` marker and the whitespace after it) before walking the remainder through `appendInline`. The marker tokens never appear in the rendered string.

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

### Tables

`GFMElementTypes.TABLE` dispatches to `TableBlock`. The `HEADER`'s `CELL` children are row 0, each `ROW`'s `CELL` children a further row; column count is row 0's size. A cell's own children are ordinary inline nodes, walked by the same `appendInlineChildren` as everywhere else.

**Column-major layout**, not row-major: a `Row` of per-column `Column`s, each `Column` at `IntrinsicSize.Max` width with its cells at `fillMaxWidth`. That is what gives a column its own widest-cell width with no measuring pass, and what gives `textAlign` a box to align within. Rows stay in step across columns only because **cells never wrap** — each is one line of `bodyMedium` (`softWrap = false`, `maxLines = 1`), whose `lineHeight` comes from the type style rather than the glyphs, so a monospace or bold cell sits at the same height as plain text. A table too wide for the bubble scrolls horizontally (`Modifier.horizontalScroll` on the outer `Box`) instead of reflowing; the bubble and thread keep their own width because the scroll is on the table's content, not on the message column.

**Alignment** comes from `parseTableAlignments(delimiterRow)` (`internal`, pure, unit-tested): splits the delimiter row on `|`, drops a blank leading/trailing segment (GFM permits the row with or without outer pipes), and reads each spec — leading-and-trailing `:` → `Center`, trailing `:` only → `End`, anything else → `Start`. A column the delimiter row doesn't describe defaults to `Start`.

**Cell content is trimmed at the token level, not the built string.** `| Name |` lexes a cell's children as `WHITE_SPACE, TEXT("Name"), WHITE_SPACE` — walking them as they arrive renders `" Name "`, and the padding is visible rather than cosmetic: it's what `textAlign` centres or end-aligns, so a right-aligned column would sit a space short of its own edge. `ASTNode.trimmedContent()` drops leading/trailing `WHITE_SPACE` children before the walk; trimming an already-built `AnnotatedString` instead would leave its span offsets pointing past the trimmed text. Found by the first device run of `MarkdownTextTest`, not by preview inspection — an exact-text assertion catches it where a `substring` one would not.

**Ragged input degrades, it doesn't error.** A short row reads as empty cells (`cells.getOrNull(column)`); a long row's overflow is dropped, and there is nothing addressable to recover — the lexer has already fused that overflow into the trailing separator token. `columnCount` and `MAX_TABLE_ROWS` are also where two fan-out caps apply: `MAX_TABLE_COLUMNS = 32`, `MAX_TABLE_ROWS = 256` (both file-private constants). The table is one composable `Text` per cell with no lazy layout, so a hostile or merely malformed reply amplifies a few kilobytes of pipes into tens of thousands of measured composables, re-paid on every streaming reveal tick (#184 re-parses ~50×/sec). Both bounds sit far above any real reply — flagged in the #681 security review as a SHOULD FIX, applied with two `take()` calls rather than a heuristic. A table hitting either cap silently drops the excess rows/columns; nothing surfaces that a cap was hit.

**Collapsed borders in `onSurfaceVariant`, not `primaryContainer`.** Each cell draws its own top and start edges (`tableCellEdges`); the table's content `Row` draws the closing end and bottom edges (`tableOuterEdges`) — every internal grid line is drawn exactly once, where a four-sided `Modifier.border` per cell would double the seam between neighbours. The colour was shipped as `MaterialTheme.colorScheme.primaryContainer` (transcribed from desktop's `--color-primary-container`) and was invisible — **1.01:1** contrast against the bubble, in both themes. That isn't a near-miss on this one palette: M3 assigns `primaryContainer` and `secondaryContainer` the same tone by construction (90 light / 30 dark), and `MessageBubble` — this renderer's only caller — always grounds it on `secondaryContainer`, so the pairing is luminance-identical in *any* M3 palette, generated or dynamic. `onSurfaceVariant` measures 7.27:1 light / 5.51:1 dark against that bubble and is the only measured candidate clearing WCAG 1.4.11's 3:1 in both themes; it's now the shared token for the table grid, the task-mark border, and struck text — three de-emphasised structural elements, one contrast-checked token. `outlineVariant` is explicitly not a substitute (1.00:1 dark on the same ground) — [`BlockQuoteBlock`](#block-dispatch)'s bar uses it and has the identical defect, pre-existing since #129, filed as #770 rather than fixed here. **The lesson generalises: porting a desktop CSS custom property onto the M3 slot of the same name carries no contrast guarantee**, because the two design systems ground their message content on different container colours. The binding carries a comment recording the contrast bar any future replacement has to clear, since a pixel test would cost more than it proves.

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

Both `CODE_FENCE` and `CODE_BLOCK` dispatch to a single `internal fun CodeBlock(content: String, language: String?)`. Widened from `private` to `internal` in #131 (one-keyword edit, no body change) so [`ToolCallRow`](./tool-call-row.md) can reuse it for code-ish tool output via `language = null` — the same rounded `surfaceContainer` monospace surface, the same horizontal-scroll behaviour, no duplication of the visual contract. The two existing in-file call sites (`CODE_FENCE` / `CODE_BLOCK`) compile unchanged because `internal` is a superset of `private`-within-file. Visual structure since #130:

```kotlin
Surface(shape = RoundedCornerShape(CodeBlockCornerRadius), color = surfaceContainer) {
    Box {
        Text(
            text = annotated,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(CodeBlockPadding),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
        )
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = CodeBlockHorizontalPadding, vertical = CodeBlockLabelVerticalPadding),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

The language label sits **outside** the `horizontalScroll` chain, so it stays anchored to the corner regardless of scroll position. Each `CodeBlock` invocation gets its own `rememberScrollState()` — independent fenced blocks scroll independently.

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

`#129` carries the `security-sensitive` label. The mitigation is a file-private predicate at the bottom of `MarkdownText.kt`:

```kotlin
private fun isSafeLinkScheme(url: String): Boolean {
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

### File-private spacing constants

```kotlin
private val ParagraphSpacing = 8.dp           // inter-block gap (and intra-list-item half-gap)
private val ListItemIndent = 8.dp             // gap between bullet/number and item body
private val BlockquoteBarWidth = 4.dp
private val BlockquoteContentIndent = 12.dp   // gap between bar and quoted content
private val CodeBlockCornerRadius = 8.dp
private val CodeBlockHorizontalPadding = 12.dp
private val CodeBlockVerticalPadding = 8.dp
private val CodeBlockLabelVerticalPadding = 4.dp   // top inset of the language label (#130)
private val CodeBlockPadding =
    PaddingValues(horizontal = CodeBlockHorizontalPadding, vertical = CodeBlockVerticalPadding)
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

Plus two file-private `const val`s bounding table fan-out (`MAX_TABLE_COLUMNS = 32`, `MAX_TABLE_ROWS = 256`; see [Tables](#tables)). Same shape as [`MessageBubble`](./message-bubble.md)'s file-private constants — named for design intent, kept local until a second site in the same package needs the same value. **No `.sp` literal anywhere in this file**; every text size is reached through `MaterialTheme.typography.<slot>`.

## Configuration

- **Library dependencies:** `implementation(libs.jetbrains.markdown)` (parser; catalog pin `jetbrainsMarkdown = "0.7.3"`) and `implementation(libs.snipme.highlights)` (code-block tokeniser since #130; catalog pin `snipmeHighlights = "1.1.0"`) in `app/build.gradle.kts`. No KSP, no kapt, no proguard rules for either.
- **Two strings since #681:** `markdown_task_mark_checked` / `markdown_task_mark_unchecked` — static TalkBack labels for the task-mark, chosen by a boolean, carrying no format argument and no daemon-authored text. Otherwise renders the input markdown verbatim; no other resource lookup.
- **No theme overrides.** Reads `colorScheme.surfaceContainer`, `colorScheme.outlineVariant`, `colorScheme.primary`, `colorScheme.secondary`, `colorScheme.tertiary`, `colorScheme.onSurfaceVariant` and `typography.headlineSmall` / `titleLarge` / `titleMedium` / `bodyMedium` / `labelSmall`. All are M3 defaults — no custom slots, no `CompositionLocal` overrides beyond consumer-supplied `LocalContentColor`.
- **No DI.** Pure leaf composable; no Koin module touched.

## Previews

`MessageBubble.kt` carries the canonical preview pair for this renderer (the renderer's only consumer today), not `MarkdownText.kt` itself. `MessageBubbleMarkdownLightPreview` and `MessageBubbleMarkdownDarkPreview` both render a file-private `MARKDOWN_PREVIEW_FIXTURE` that exercises every supported element (h1/h2/h3, bold, italic, inline code, link, unordered list, ordered list, a mixed task list, both strikethrough forms beside a home-relative-paths line that must *not* strike, a three-column table with all three alignments, blockquote, fenced code blocks for Kotlin / JSON / Bash / Markdown) inside the `MessageBubble` host. Satisfies #129's AC6, #130's AC7 and #681's AC5.

## Edge cases / limitations

- **Inline-code background paints at glyph-rect bounds, not at a padded rectangle.** `SpanStyle(background = …)` on an `AnnotatedString` span has no horizontal padding option in Compose's text API — the background tints exactly the glyph rect. Short identifiers (`getUserId()`) read fine; longer code spans look tight. **Acceptable for #129.** If the tightness becomes a complaint, the replacement shape is `InlineTextContent` per code span — don't pre-build that now.
- **Soft line breaks collapse to a single space.** `EOL` tokens inside a paragraph map to `append(" ")` rather than `append("\n")`. Matches CommonMark's "soft-break = space" rendering rule. Hard breaks (two trailing spaces or a backslash before the newline) are not yet specially handled — they fall through to the same single-space behaviour. Not in AC.
- **Fenced code blocks render with syntax highlighting since #130** — `Surface(surfaceContainer)` (flat, no outline border — ticket body pinned this over the Figma `surface` + `outline-variant` border), monospace text with token colours bound to `MaterialTheme.colorScheme`, horizontal scroll for long lines (no wrap), optional top-right language label when the fence info string is non-blank. See [Code blocks](#code-blocks) for the full mapping; library choice in [ADR 0003](../decisions/0003-syntax-highlighter-library.md).
- **Highlighter language coverage is bounded.** Kotlin / Bash / JSON (via the JavaScript lexer — JSON's grammar is a subset) tokenise; Markdown and any other language fall through to plain monospace but still show the language label. Adding a language is a one-line addition to `resolveSyntaxLanguage` if the library exposes it.
- **Long first lines can sit under the language label.** The label overlays the top-right corner of the code body without a separator strip; if the first code line is wide enough to extend under the label area, glyphs and label text overlap visually. Acceptable for Phase 0 — preview-verifiable. Mitigations a future ticket might pick from: pad the first line, tint the label background, or reintroduce a header strip (the Figma original).
- **Indented code blocks render identically to fenced but never carry a language label.** `CODE_BLOCK` passes `language = null` to `CodeBlock`; CommonMark indented blocks have no info-string syntax.
- **Lists are flat-bulleted.** Unordered lists use `"•"`; ordered lists use `"${index + 1}."` from the 1-based item position within the list. Nested-list indentation depth comes from the recursive `MarkdownBlock` call inside the list item's `Column` — no per-level indent multiplier.
- **Blockquote paragraphs render italic.** Non-paragraph children inside a blockquote (e.g. a nested list) recurse through `MarkdownBlock` without the italic override. The blockquote bar is `outlineVariant` and spans the intrinsic height of the content column.
- **No selection / copy.** The composable uses bare `Text(...)`, not `SelectionContainer { Text(...) }`. If long-press-to-copy lands later, the right place is a screen-level wrap of the thread `LazyColumn` body, not per-renderer.
- **Streaming is not specially handled.** `MarkdownText` treats `markdown` as a complete, final string each composition; partial markdown (an unclosed `**bold` mid-stream) still parses (the JetBrains parser is total) and renders as best it can. #184 owns streaming-aware behaviour at the `MessageBubble` layer — `StreamingAssistantBody` appends a `▎` caret glyph to the revealed prefix and passes the result through this renderer unchanged. The renderer never changed signature.
- **Renderer is total.** The JetBrains parser produces an AST for any input string — there is no exception path. Unsupported element kinds hit the `else` fallback (raw text as a `bodyMedium` paragraph), so the message is never blank. #681 leaned on exactly this property to switch parser flavours without suppressing anything — see the next two points.
- **Parser flavour is GFM since #681, and that changes more than the three constructs it was switched on for.** `org.jetbrains:markdown` has no per-construct registration — `GFMFlavourDescriptor` is the only off-the-shelf way to reach tables, task lists and strikethrough, and it necessarily also brings bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) into every message this renderer sees, whether or not that message uses any of the three wanted constructs.
- **Bare URLs and `$…$` render as their own literal characters — deliberately, by omission rather than suppression.** `GFM_AUTOLINK` is a childless leaf with no dispatcher arm, so `appendInline`'s `else` appends its raw text; there is no link annotation to tap. `INLINE_MATH` has children with no arm either, so the `else` recurses and its `DOLLAR` / `TEXT` / `WHITE_SPACE` leaves rebuild the `$…$` span verbatim. Block `$$…$$` reaches `MarkdownBlock`'s own `else` and renders as raw text the same way. `MarkdownTextParsingTest` pins the AST shape each depends on, because the property here rests on an *absent* branch, and an absent branch reddens nothing on its own if the library ever adds one.
- **Task marks are inert by construction.** See [Task lists](#task-lists) — no `clickable`, `toggleable`, `ToggleableState` or `focusable`, on purpose; a bare `contentDescription` is announced but offers nothing to activate.
- **Table cells never wrap, and a too-wide table scrolls rather than reflows.** See [Tables](#tables). A short row pads with empty cells; a long row's overflow is dropped with nothing addressable left to recover; `MAX_TABLE_COLUMNS` (32) / `MAX_TABLE_ROWS` (256) truncate a pathologically large table with no visible signal that truncation happened.
- **Table layout is column-major** (a `Row` of per-column `Column`s), where an HTML table is naturally row-major. No problem has been observed from this — Compose's accessibility node sort bands by vertical overlap, which recovers row-major reading order for a TalkBack pass without any code here arranging for it — but it's a consequence of a deliberate layout choice (see [Tables](#tables)'s column-width rationale) rather than an oversight, and worth knowing before "make the table row-major" is proposed as a simplification.
- **Two pre-existing rendering defects were found by #681's tests and filed rather than fixed, because both predate this ticket and are outside its scope.** #768 — every ATX heading renders with a leading space (`HeadingBlock` filters marker whitespace among the `ATX_n` node's direct children, but the parser nests that token one level inside `ATX_CONTENT`; since #129). #770 — the blockquote bar in `BlockQuoteBlock` is drawn in `outlineVariant`, which is **1.00:1 contrast** against the `secondaryContainer` bubble in dark theme (1.32:1 light) — the same "ported token, wrong ground" defect the table border had before its fix, one construct earlier in this same file, since #129.
- **Image sources are not a raw-text fallback, on either flavour — a claim this document previously stated wrong.** `IMAGE` has children under CommonMark and under GFM, so `![alt](url)` renders `!` plus the alt text as a tappable, scheme-gated link. Unaffected by the #681 flavour switch; see [Link safety](#link-safety--scheme-allowlist) for the corrected entry.

## Related

- Ticket notes: [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/131.md`](../codebase/131.md) (`docs/knowledge/codebase/` is frozen since 2026-09-05; #681 has no per-ticket file — its record is the spec below)
- Specs: `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`, `docs/specs/architecture/681-gfm-tables-task-lists-strikethrough.md` (measured AST facts per node kind, the security review, and the `## Revisions` history behind the table-border and cell-trimming fixes)
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (parser library choice; predicted the #681 flavour swap in its own Consequences section), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md)
- Consumers: [`MessageBubble`](./message-bubble.md) assistant variant (user messages stay plain text); [`ToolCallRow`](./tool-call-row.md) reuses the `internal CodeBlock` for code-ish tool output since #131
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants; preview pairing shape)
- Local precedent for `buildAnnotatedString` / `SpanStyle` idioms: `ScannerScreen.kt:252-260`
- Filed, not fixed here (both found by #681's tests, both pre-existing since #129, both out of this ticket's scope): #768 (ATX heading leading-space defect), #770 (blockquote bar contrast defect, the same class of bug as the table-border fix above)
- Downstream:
  - #184 — streaming caret + animation (landed). Operates at the `MessageBubble` layer; passes the revealed-prefix-plus-caret string through this renderer unchanged each tick. Partial code fences flow through `CodeBlock` unchanged; the `remember(content, syntaxLanguage)` re-tokenises per reveal tick (sub-millisecond on typical sizes). Tables and task lists flow through the same unchanged-signature path; `singleTildeRuns` re-runs per reveal tick behind its `children.none { TILDE }` early-out rather than being memoised, since `appendInlineChildren` builds outside composition where `remember` is unavailable
  - #680 — live desktop/mobile comparison of the same replies, covering the three constructs this ticket added
