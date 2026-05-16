# MarkdownText

CommonMark renderer for assistant-message content (#129). Single public composable that takes a markdown source string and renders it into native Compose primitives — every text style resolved from `MaterialTheme.typography`, every link routed through `LocalUriHandler`, every container colour pulled from `MaterialTheme.colorScheme`. No Android-View interop seam; the renderer is pure Compose end-to-end.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`). Sibling of [`MessageBubble`](./message-bubble.md). Library choice rationale: [ADR 0002](../decisions/0002-markdown-renderer-library.md).

## What it does

Parses an input string with `org.jetbrains:markdown`'s `MarkdownParser(CommonMarkFlavourDescriptor()).buildMarkdownTreeFromString(...)` and walks the resulting AST, dispatching each top-level block to a Compose composable. Block kinds supported: ATX headings (h1–h3), paragraphs, ordered and unordered lists, blockquotes, fenced code blocks, indented code blocks. Inline kinds supported: bold (`STRONG`), italic (`EMPH`), inline code (`CODE_SPAN`), inline links (`INLINE_LINK`). Unsupported AST node kinds (tables, HTML, horizontal rules, strikethrough, task lists, images, autolinks beyond `[text](url)`) fall back to rendering the raw source text of that node as a plain `bodyMedium` paragraph — the renderer never throws.

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

The parse is keyed on the `markdown` string itself — the AST is rebuilt only when the source string changes. Typical assistant messages parse in well under a millisecond; the `remember` is defensive against pathological multi-KB messages, not a hot-path optimisation. Streaming (#132) re-parses per token batch — that's still sub-millisecond for typical batch sizes and not the place to pre-optimise.

### Block dispatch

`MarkdownBlock(node, source, uriHandler)` is a `when (node.type)` over `MarkdownElementTypes`:

| AST type | Renders as |
|---|---|
| `ATX_1` | `Text(buildInline(...), style = headlineSmall)` |
| `ATX_2` | `Text(buildInline(...), style = titleLarge)` |
| `ATX_3` | `Text(buildInline(...), style = titleMedium)` |
| `PARAGRAPH` | `Text(buildInline(...), style = bodyMedium)` |
| `UNORDERED_LIST` | `Column { … Row { Text("•"); Spacer; Column { recurse } } … }` |
| `ORDERED_LIST` | same but marker is `"${index + 1}."` (1-based) |
| `BLOCK_QUOTE` | `Row { Box(width = 4dp, fillMaxHeight, background = outlineVariant); Spacer(12dp); Column { italic paragraphs } }` |
| `CODE_FENCE` | `Surface(shape = RoundedCornerShape(8dp), color = surfaceContainer) { Text(joined CODE_FENCE_CONTENT lines, monospace, padded) }` |
| `CODE_BLOCK` | same as `CODE_FENCE` but joins `CODE_LINE` tokens |
| _else_ | `Text(node.getTextInNode(source).trim(), style = bodyMedium)` — fallback |

The top-level `MarkdownText` wraps the dispatched children in `Column(modifier, verticalArrangement = Arrangement.spacedBy(ParagraphSpacing))` so inter-block gaps come from the column, not from per-element padding. List items use the same `spacedBy(ParagraphSpacing)` so nested blocks inside a list item separate the same way as top-level blocks.

### Heading inline content

`HeadingBlock` filters the heading node's children for `ATX_HEADER` / `WHITE_SPACE` / `EOL` tokens (the `#` marker and the whitespace after it) before walking the remainder through `appendInline`. The marker tokens never appear in the rendered string.

### Inline dispatch

`appendInline(node, source, uriHandler, codeSpanBg, linkColor)` is a `when (node.type)` over `MarkdownElementTypes` and `MarkdownTokenTypes`, writing into an `AnnotatedString.Builder`:

| AST type | Builds |
|---|---|
| `EMPH` | `withStyle(SpanStyle(fontStyle = Italic)) { recurse children }` |
| `STRONG` | `withStyle(SpanStyle(fontWeight = Bold)) { recurse children }` |
| `CODE_SPAN` | `withStyle(SpanStyle(fontFamily = Monospace, background = surfaceContainer)) { append(text.trim('\`')) }` |
| `INLINE_LINK` | `withLink(LinkAnnotation.Url(url, styles = TextLinkStyles(SpanStyle(color = primary, textDecoration = Underline)), linkInteractionListener = …)) { append(linkText) }` |
| `EOL` | `append(" ")` — collapses CommonMark soft-breaks to a single space |
| _leaf token, no children_ | `append(node.getTextInNode(source).toString())` |
| _element with children_ | recurse into each child |

Link text is built from the `LINK_TEXT` child's children, filtering `LBRACKET` / `RBRACKET` tokens. URL is the `LINK_DESTINATION` child's text, trimmed and stripped of surrounding `<` / `>` (for angle-bracket destinations). Code-span text is the node's raw text trimmed of backticks.

`buildInline(node, source, uriHandler): AnnotatedString` is the `@Composable` wrapper that reads `MaterialTheme.colorScheme.surfaceContainer` and `.primary` once (Composable color lookups), then constructs the `AnnotatedString` via `buildAnnotatedString { appendInline(...) }`.

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
- Image sources — `![alt](url)` falls through to raw-text fallback; no image fetch, no SSRF surface.
- Code-block content — plain monospace `Text`; strings are not executed or interpreted.
- Long-content DoS — Phase 0 messages are bounded; Phase 4 inherits Claude API response-size limits.

### File-private spacing constants

```kotlin
private val ParagraphSpacing = 8.dp           // inter-block gap (and intra-list-item half-gap)
private val ListItemIndent = 8.dp             // gap between bullet/number and item body
private val BlockquoteBarWidth = 4.dp
private val BlockquoteContentIndent = 12.dp   // gap between bar and quoted content
private val CodeBlockCornerRadius = 8.dp
private val CodeBlockPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
```

Same shape as [`MessageBubble`](./message-bubble.md)'s file-private constants — named for design intent, kept local until a second site in the same package needs the same value. **No `.sp` literal anywhere in this file**; every text size is reached through `MaterialTheme.typography.<slot>`.

## Configuration

- **Library dependency:** `implementation(libs.jetbrains.markdown)` in `app/build.gradle.kts`. Catalog pin: `jetbrainsMarkdown = "0.7.3"`. No KSP, no kapt, no proguard rules.
- **No new strings.** Renders the input markdown verbatim; no resource lookup.
- **No theme overrides.** Reads `colorScheme.surfaceContainer`, `colorScheme.outlineVariant`, `colorScheme.primary` and `typography.headlineSmall` / `titleLarge` / `titleMedium` / `bodyMedium`. All are M3 defaults — no custom slots, no `CompositionLocal` overrides beyond consumer-supplied `LocalContentColor`.
- **No DI.** Pure leaf composable; no Koin module touched.

## Previews

`MessageBubble.kt` carries the canonical preview pair for this renderer (the renderer's only consumer today), not `MarkdownText.kt` itself. `MessageBubbleMarkdownLightPreview` and `MessageBubbleMarkdownDarkPreview` both render a file-private `MARKDOWN_PREVIEW_FIXTURE` that exercises every supported element (h1/h2/h3, bold, italic, inline code, link, unordered list, ordered list, blockquote, fenced code block) inside the `MessageBubble` host. Satisfies AC6.

## Edge cases / limitations

- **Inline-code background paints at glyph-rect bounds, not at a padded rectangle.** `SpanStyle(background = …)` on an `AnnotatedString` span has no horizontal padding option in Compose's text API — the background tints exactly the glyph rect. Short identifiers (`getUserId()`) read fine; longer code spans look tight. **Acceptable for #129.** If the tightness becomes a complaint, the replacement shape is `InlineTextContent` per code span — don't pre-build that now.
- **Soft line breaks collapse to a single space.** `EOL` tokens inside a paragraph map to `append(" ")` rather than `append("\n")`. Matches CommonMark's "soft-break = space" rendering rule. Hard breaks (two trailing spaces or a backslash before the newline) are not yet specially handled — they fall through to the same single-space behaviour. Not in AC.
- **Fenced code blocks render flat** — `Surface(surfaceContainer)` with rounded corners and monospace text. **No language label, no outline border, no syntax highlighting** — all three land in #130. The fence's info string (the `kotlin` after the opening ```` ``` ````) is discarded.
- **Indented code blocks render identically to fenced.** `CODE_BLOCK` and `CODE_FENCE` both hit the same `CodeBlock` composable.
- **Lists are flat-bulleted.** Unordered lists use `"•"`; ordered lists use `"${index + 1}."` from the 1-based item position within the list. Nested-list indentation depth comes from the recursive `MarkdownBlock` call inside the list item's `Column` — no per-level indent multiplier.
- **Blockquote paragraphs render italic.** Non-paragraph children inside a blockquote (e.g. a nested list) recurse through `MarkdownBlock` without the italic override. The blockquote bar is `outlineVariant` and spans the intrinsic height of the content column.
- **No selection / copy.** The composable uses bare `Text(...)`, not `SelectionContainer { Text(...) }`. If long-press-to-copy lands later, the right place is a screen-level wrap of the thread `LazyColumn` body, not per-renderer.
- **Streaming is not specially handled.** `MarkdownText` treats `markdown` as a complete, final string each composition; partial markdown (an unclosed `**bold` mid-stream) still parses (the JetBrains parser is total) and renders as best it can. #132 owns streaming-aware behaviour at the `MessageBubble` layer; this renderer doesn't need to change.
- **Renderer is total.** The JetBrains parser produces an AST for any input string — there is no exception path. Unsupported element kinds hit the `else` fallback (raw text as a `bodyMedium` paragraph), so the message is never blank.

## Related

- Ticket notes: [`../codebase/129.md`](../codebase/129.md)
- Spec: `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`
- Decision: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md)
- Consumer: [`MessageBubble`](./message-bubble.md) (assistant variant only — user messages stay plain text)
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants; preview pairing shape)
- Local precedent for `buildAnnotatedString` / `SpanStyle` idioms: `ScannerScreen.kt:252-260`
- Downstream:
  - #130 — full code-block styling (language label, outline border, syntax highlighting). Will replace the `CodeBlock` composable here without changing `MarkdownText`'s public API
  - #132 — streaming caret + animation. Operates at the `MessageBubble` layer; passes partial `markdown` strings through this renderer unchanged
