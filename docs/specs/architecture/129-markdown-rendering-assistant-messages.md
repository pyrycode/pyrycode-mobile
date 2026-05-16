# Spec — markdown rendering in assistant messages (#129)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:80-98` — current `AssistantMessage` private composable. The only change to this file: swap the inner `Text(...)` call for `MarkdownText(...)` inside a `CompositionLocalProvider(LocalContentColor provides …)`. **Do not touch** `UserMessageBubble` (user messages stay plain text per AC2) or the file-private spacing tokens.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:1-14` — `Message.content: String` is the raw markdown source piped into `MarkdownText`. No upstream parsing; the renderer owns it.
- `gradle/libs.versions.toml` — the only mutation here is one `[versions]` line + one `[libraries]` line for the new dependency. Mirror the existing alphabetised ordering inside each section.
- `app/build.gradle.kts:78-106` — `dependencies { }` block. Add one `implementation(libs.jetbrains.markdown)` line after `implementation(libs.kotlinx.datetime)` (alphabetical-ish; the codebase isn't strict).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:272-303` — `PyrycodeMobileTheme` wires `typography = AppTypography` and supplies the `colorScheme` slots (`surface`, `surfaceContainer`, `surfaceContainerHigh`, `onSurface`, `primary`, `outlineVariant`) the renderer reads via `MaterialTheme`. No edit needed.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — verify `headlineSmall`, `titleLarge`, `titleMedium`, `bodyMedium` are present. If `AppTypography` does not override them, the M3 defaults stand; either way, the renderer references them by slot name, not by absolute size.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:252-260` — local precedent for `buildAnnotatedString { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, …)) { append(…) } }`. Mirror the import set and idiom in `MarkdownText.kt`.
- `docs/specs/architecture/128-message-bubble-user-assistant-variants.md:172-186` — **load-bearing precedent.** No Compose UI test infrastructure (no `ComposeTestRule`, no Robolectric). Visual verification is by `@Preview` only. AC6 ("a `@Preview` exercises every supported element in both light and dark themes") satisfies AC by preview-rendering check; no instrumented test is required or expected.
- The ticket body itself — AC list defines the supported element set (h1–h3, bold, italic, ordered list, unordered list, inline code, blockquote, links, fenced code block). The renderer covers exactly that surface; anything else is a graceful fallback (see § Error handling).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The same Conversation Thread frame `16:8` from #128. The assistant rows (nodes `16:32`, `16:42`, `16:54`) render plain text only — Figma does not show what rendered markdown elements (headings, lists, blockquotes) look like inside an assistant message. The visual contract for those elements is therefore **derived from M3 typography slots and the M3 color scheme** rather than Figma pixels: h1→`headlineSmall`, h2→`titleLarge`, h3→`titleMedium`, body→`bodyMedium`; `onSurface` for text; `surfaceContainer` for inline-code background and fenced-code block fill. The Figma frame **does** show a fenced code block at `16:45`–`16:50` (monospace text on `surface` with an `outlineVariant` border + a small "typescript" language label) — this ticket renders **without** the language label and **without** the outline border because full code-block styling (the bordered tile + the language label + syntax highlighting) is the scope of #130. Here the fenced block is a flat `surfaceContainer` rounded rectangle with monospace text inside.

The `MessageBubble.AssistantMessage` host container (flat, transparent, left-aligned, full-width) is unchanged from #128 — only the inner text rendering swaps from `Text` to `MarkdownText`.

## Context

`#128` introduced `MessageBubble` with a flat `Text(content, style = bodyMedium, color = onSurface)` for the assistant variant. This ticket re-skins that single `Text` call so the assistant variant renders `content` as markdown while keeping the host container, alignment, color contract, and user variant untouched.

Scope contract:

- **Assistant variant only.** The user variant continues to render `content` via plain `Text(...)` — user messages are not markdown sources. AC2 is explicit.
- **Element set is fixed by AC3 + AC4.** h1, h2, h3, bold, italic, ordered list, unordered list, inline code, blockquote, link, fenced code block. Nothing else (no tables, no images, no horizontal rules, no nested-emphasis polish, no GFM strikethrough / task lists).
- **Fenced code blocks render flat.** Plain monospace text on a `surfaceContainer` rounded rectangle with internal padding. **No language label, no outline border, no syntax highlighting** — all three land in #130. Indented (4-space) code blocks render identically.
- **No streaming-aware rendering.** `Message.isStreaming` is still ignored at this layer; #132 owns the typing caret + partial-token handling. The renderer treats `content` as a complete, final markdown string each composition.
- **No selection / copy.** Same as #128: no `SelectionContainer` wrapping. A future ticket can add it at the screen level.

## Design

### Library choice

Use **JetBrains' `org.jetbrains:markdown`** parser plus a hand-rolled Compose renderer. **Reject `compose-markdown`** (the `dev.jeziellago:compose-markdown` library mentioned in the ticket body as "preferred"), for two reasons that bind directly against the ACs:

1. **It's a Markwon-on-AndroidView wrapper.** The library renders into a `TextView` via `AndroidView`, not native Compose. That introduces an Android-View interop seam (text selection, accessibility, theming) that no other surface in this codebase has today, and it's a permanent dependency once accepted. The codebase is otherwise Compose-pure (the existing `ScannerScreen.kt`'s `buildAnnotatedString` usage is the local idiom).
2. **Its heading sizes are relative multipliers on a single base `style`.** Markwon's default theme renders h1/h2/h3 as `style.fontSize * 1.5/1.3/1.1` (or similar). Mapping each heading level to a **distinct M3 typography slot** (`headlineSmall` / `titleLarge` / `titleMedium` — see AC3 + AC5) requires writing a custom `MarkwonPlugin` that interrogates `MaterialTheme.typography.*.fontSize.toPx()` and writes the resolved values back into Markwon's theme. That custom plugin is roughly the same line count as a CommonMark→Compose visitor walk, while still leaving the AndroidView seam in place.

The JetBrains parser is a pure Kotlin Multiplatform AST builder (~50 KB jar, no Android dependencies, no transitive AndroidX). We own the rendering side, which is what makes "every text style resolves from `MaterialTheme.typography`; no `.sp` literals" (AC5) hold by construction rather than by post-hoc theme injection. The renderer's full surface is ~80–100 lines because the AC element set is small and bounded.

### Library wire-up

`gradle/libs.versions.toml`:

```toml
[versions]
+ jetbrainsMarkdown = "0.7.3"     # current stable on Maven Central as of 2026-05-16; dev pins to the
                                  # latest stable if a newer release exists at implementation time

[libraries]
+ jetbrains-markdown = { group = "org.jetbrains", name = "markdown", version.ref = "jetbrainsMarkdown" }
```

`app/build.gradle.kts`, in the `dependencies { }` block:

```kotlin
implementation(libs.jetbrains.markdown)
```

No plugin entry, no KSP / kapt, no proguard rules needed (the library is pure Kotlin and reflection-free).

### New file: `MarkdownText.kt`

Location: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`. Placed alongside `MessageBubble.kt` because the renderer is a component, not a thread-screen concern.

Public API (one composable, no overloads):

```kotlin
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
)
```

- **No `style` parameter.** Per AC5, every text style is owned by the renderer and resolved from `MaterialTheme.typography` by element kind.
- **No `color` parameter.** Text inherits the ambient `LocalContentColor.current`. The caller (`AssistantMessage`) provides `onSurface` via `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { MarkdownText(...) }`. This keeps `MarkdownText` reusable from any future surface (e.g. system messages, tool cards) without changing its signature.
- **No `onLinkClick` parameter.** Links open via the ambient `LocalUriHandler.current.openUri(url)` (AC3). External callers cannot intercept link taps; if a future surface needs to (e.g. an in-app deep-link router), introduce a parameter at that point.

Internal structure (signatures + behaviour; **do not pre-write the bodies**):

```kotlin
private val MarkdownFlavour: MarkdownFlavourDescriptor = CommonMarkFlavourDescriptor()

@Composable
private fun MarkdownDocument(root: ASTNode, source: String)

@Composable
private fun MarkdownBlock(node: ASTNode, source: String)

@Composable
private fun MarkdownInline(node: ASTNode, source: String, uriHandler: UriHandler): AnnotatedString
```

- **`MarkdownText`** captures `val uriHandler = LocalUriHandler.current` at the top, parses with `remember(markdown) { MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown) }` (so re-rendering the same content doesn't re-parse), then calls `MarkdownDocument(root, markdown)` inside a `Column(modifier)` whose `verticalArrangement = Arrangement.spacedBy(ParagraphSpacing)` supplies the inter-block gap.
- **`MarkdownDocument`** iterates `root.children`, dispatching each top-level block to `MarkdownBlock`. The top-level node kind from `org.intellij.markdown.MarkdownElementTypes.MARKDOWN_FILE` is the root; its children are the document's block elements.
- **`MarkdownBlock`** is a `when(node.type)` over `MarkdownElementTypes` / `MarkdownTokenTypes`. Block mapping:
  - `ATX_1` → `Text(MarkdownInline(node.headingContent, source, uriHandler), style = MaterialTheme.typography.headlineSmall)` — strip the leading `#` token from the inline source range.
  - `ATX_2` → `titleLarge`.
  - `ATX_3` → `titleMedium`.
  - `PARAGRAPH` → `Text(MarkdownInline(node, source, uriHandler), style = MaterialTheme.typography.bodyMedium)`.
  - `UNORDERED_LIST` → `Column { node.children.filterIsInstance(LIST_ITEM).forEach { Row { Text("•  ", style = bodyMedium); Column { /* recurse into LIST_ITEM children as blocks */ } } } }`.
  - `ORDERED_LIST` → same but the marker is `"${1-based index}. "`.
  - `BLOCK_QUOTE` → `Row { Box(Modifier.width(BlockquoteBarWidth).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)); Spacer(Modifier.width(BlockquoteContentIndent)); Column { /* recurse into block children, italic bodyMedium */ } }`.
  - `CODE_FENCE` / `CODE_BLOCK` → `Surface(shape = RoundedCornerShape(CodeBlockCornerRadius), color = MaterialTheme.colorScheme.surfaceContainer) { Text(rawCode, modifier = Modifier.padding(CodeBlockPadding), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }`. Strip the fence tokens (` ``` ` + optional info string + trailing fence) from `node.getTextInNode(source)`. The language info string is discarded — #130 owns rendering it.
  - **Fallback** `else -> Text(node.getTextInNode(source).toString().trim(), style = MaterialTheme.typography.bodyMedium)` — see § Error handling.
- **`MarkdownInline`** builds a single `AnnotatedString` by walking the node's descendants. Inline mapping:
  - `EMPH` → `withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(child inlines) }`.
  - `STRONG` → `withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(child inlines) }`.
  - `CODE_SPAN` → `withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceContainer)) { append(node.getTextInNode(source).toString().trim('`')) }`. Background paints at glyph-rect bounds — see § Open questions.
  - `LINK` / `INLINE_LINK` → `withLink(LinkAnnotation.Url(url, styles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)), linkInteractionListener = LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.url?.let { if (isSafeLinkScheme(it)) uriHandler.openUri(it) } })) { append(linkText) }`. The link text is the bracketed display text; the URL is the parenthesised destination, both extracted from the AST. The `isSafeLinkScheme` guard is defined in § Security; **do not omit it**.
  - `TEXT` (and any leaf token kind not listed) → `append(node.getTextInNode(source).toString())`.

File-private spacing constants at the top of `MarkdownText.kt` (named so the design intent is legible; same precedent as `MessageBubble.kt`):

- `private val ParagraphSpacing = 8.dp` — inter-block gap (paragraphs, headings, lists, code blocks).
- `private val ListItemIndent = 8.dp` — leading gap between bullet/number and item body.
- `private val BlockquoteBarWidth = 4.dp`.
- `private val BlockquoteContentIndent = 12.dp` — gap between bar and quoted content.
- `private val CodeBlockCornerRadius = 8.dp`.
- `private val CodeBlockPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)`.

No `.sp` literal anywhere in this file: every text size is reached through `MaterialTheme.typography.<slot>`.

### Modified file: `MessageBubble.kt`

Single edit: replace the inner `Text(...)` call in `AssistantMessage` (lines 91–96) with:

```kotlin
CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    MarkdownText(
        markdown = content,
        modifier = Modifier.fillMaxWidth(),
    )
}
```

The surrounding `Box(Modifier.fillMaxWidth().padding(bottom = MessageRowVerticalSpacing))` is unchanged. The `UserMessageBubble` composable is unchanged. The file-private spacing constants at the top of the file are unchanged. The previews already in the file (`MessageBubbleLightPreview`, `MessageBubbleDarkPreview`) continue to render the existing four-message sequence — those previews now visually verify that plain-text assistant content (no markdown syntax) still renders correctly through `MarkdownText`.

Add one new pair of previews to `MessageBubble.kt` (Light + Dark, mirroring the existing preview shape):

```kotlin
@Preview(name = "MessageBubble — Markdown · Light", showBackground = true, widthDp = 412)
@Composable
private fun MessageBubbleMarkdownLightPreview() { /* PyrycodeMobileTheme(false) { Surface { MessageBubble(previewMessage(Role.Assistant, MarkdownPreviewFixture)) } } */ }

@Preview(name = "MessageBubble — Markdown · Dark", showBackground = true, widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessageBubbleMarkdownDarkPreview() { /* same, darkTheme = true */ }
```

with a file-private fixture constant that exercises every supported element:

```kotlin
private const val MarkdownPreviewFixture = """
# Heading 1
## Heading 2
### Heading 3

Plain paragraph with **bold**, *italic*, `inline code`, and a [link](https://pyryco.de).

- Unordered list item 1
- Unordered list item 2

1. Ordered list item 1
2. Ordered list item 2

> Blockquote — single line of quoted text.

```kotlin
fun migrate(legacy: List<LegacyOrder>): List<Order> =
    legacy.map { it.toModern() }
```
"""
```

(AC6 is satisfied by these two previews — they cover every element in both themes. The pre-existing `MessageBubblePreviewSequence` previews continue to exercise the user variant and the no-markdown assistant rendering path.)

### Module wiring

None beyond the library wire-up above. Koin DI modules are unchanged — `MarkdownText` is a pure composable with no injectable dependency. The renderer is a leaf component used only inside `MessageBubble.AssistantMessage`; no exposure further up the tree.

## State + concurrency model

`MarkdownText` is a pure stateless composable:

- **Parse memoisation.** The parse is wrapped in `remember(markdown) { MarkdownParser(MarkdownFlavour).buildMarkdownTreeFromString(markdown) }` so the AST is rebuilt only when the `markdown` string itself changes. Typical assistant messages parse in well under a millisecond; the `remember` is defensive against pathological multi-KB messages, not a hot-path optimisation.
- **No `LaunchedEffect`, no coroutines, no flows.** Parsing is synchronous and CPU-only.
- **Recomposition stability.** The single `String` input means `MarkdownText` skips recomposition when the same `markdown` value is passed (Compose's stability inference handles `String`).
- **Streaming.** Out of scope for this ticket (#132). When streaming arrives, the streaming path will call `MarkdownText(partialContent, …)` on each token batch; the `remember(markdown)` will re-parse per batch. That's fine for typical Claude token-batch granularity (~10–50 chars per tick) — full-AST re-parse of a few thousand chars is sub-millisecond. If streaming-frame parsing ever shows up in a trace, the optimisation is incremental parsing inside #132's scope, not premature here.

## Error handling

The JetBrains parser is **total**: any input string produces an AST. There is no exception path to handle.

Element kinds **not** in the AC list (HTML blocks, tables, horizontal rules, footnotes, definition lists, strikethrough, task lists, autolinks beyond the `[text](url)` form) hit the **`else` fallback** in `MarkdownBlock` / `MarkdownInline`: render the raw source text of that node as a plain `bodyMedium` paragraph. This guarantees the message is never blank — the visual fidelity for unsupported markdown is degraded but readable, and the developer doesn't have to enumerate every AST node kind exhaustively.

**No defensive throws** — no `error("unsupported node kind: …")`. The fallback IS the contract. If Claude API responses start emitting tables or strikethrough often, a follow-up ticket can extend the renderer's element map; until then the fallback is acceptable degradation.

**Link routing is gated by `isSafeLinkScheme(url: String): Boolean` — see § Security.** The full rationale for the allowlist lives in the security section; the error-handling contract is simply: rejected links no-op silently (the renderer still draws the underlined link span, but tapping it does nothing). No toast, no log, no error UI — the visual signal (drawn-but-inert) is acceptable for a Phase 0 client.

## Security

The ticket carries the `security-sensitive` label. The one finding from the architect-side pass is link-scheme handling — every other surface in this renderer is either inert (plain text rendering) or unsupported (HTML / images fall through to raw-text fallback, no execution path).

**Finding: rendered `[text](url)` links must not fire arbitrary URI schemes.**

`uriHandler.openUri(url)` delegates to an Android `Intent.ACTION_VIEW`. A model-emitted (or, in Phase 4, attacker-influenced) link with scheme `intent://…#Intent;…` can target arbitrary app components with attacker-controlled extras on some Android configurations; `javascript:` is historically problematic in cross-app URI routing; and exotic schemes (`tel:`, `sms:`, `geo:`, `market:`, custom deep links) produce surprising side effects when tapped. The fake repo in Phase 0 is author-controlled, but the `security-sensitive` label IS the operator's signal that the defense belongs here, not as a future TODO.

**Mitigation — scheme allowlist.** Add a file-private predicate at the bottom of `MarkdownText.kt`:

```kotlin
private fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}
```

The link's `LinkInteractionListener` consults this predicate before calling `uriHandler.openUri(url)` (see § Design / `MarkdownInline` / `LINK` mapping). Rejected URLs no-op silently — the visible underlined link span remains drawn, the tap does nothing. The renderer never blocks the link from rendering visually; it only blocks the navigation. Visual fidelity therefore doesn't depend on URL safety.

**Why this allowlist and not a denylist.** A denylist (`intent://`, `javascript:`, …) is fragile against future scheme additions and against case / whitespace tricks (`InTeNt://`, `\tjavascript:`). The allowlist is enforced at the `lowercase()` scheme prefix, so it rejects anything that doesn't normalise to `http`/`https`/`mailto`. New schemes that are deemed safe later (e.g. `tel:` for a click-to-dial affordance) are an explicit one-line addition rather than a silent default.

**Not in scope for this ticket:**

- **`http://` vs `https://`.** Both pass the allowlist; a follow-up could downgrade `http://` to `https://` or surface a "leaving secure context" affordance. Not the threat model here.
- **URL canonicalisation / IDN homograph attacks.** Trustworthiness of the destination is the user's job; the allowlist limits the *scheme*, not the *destination*.
- **HTML / script content.** The renderer doesn't parse or execute HTML — `HTML_BLOCK` / `HTML_INLINE` nodes hit the `else -> Text(...)` fallback and render as literal text.
- **Image sources.** Not supported. `![alt](url)` falls through to raw-text fallback; no image fetch, no SSRF surface.
- **Code-block content.** Plain monospace `Text` — strings are not executed or interpreted.
- **Long-content DoS.** Phase 0 messages are bounded; Phase 4 will inherit Claude API response-size limits. Not gated here.

**Re-run verdict: PASS.** All listed categories are either inert by construction or have a deterministic mitigation in the spec.

## Testing strategy

### Unit tests — none

The renderer is pure-rendering Compose code; no logic to assert without a Compose host. Same precedent as `#128` (no Robolectric, no Compose JVM-host on `testImplementation`). Building that infrastructure is over-scope.

### Compose UI tests — none

Same precedent as `#126` and `#128`: no `ComposeTestRule` setup in this codebase. Visual verification is by `@Preview` rendering in Android Studio:

1. **`MessageBubbleMarkdownLightPreview`** and **`MessageBubbleMarkdownDarkPreview`** render the fixture exhaustively, both themes. Every element in AC3 + AC4 is visible.
2. **`MessageBubbleLightPreview`** and **`MessageBubbleDarkPreview`** (pre-existing) continue to render — they now exercise the no-markdown-syntax assistant rendering path through `MarkdownText`.
3. **`./gradlew assembleDebug`** must pass.
4. **`./gradlew lint`** must pass with no new warnings. The new monospace-background `SpanStyle` and the `LinkAnnotation.Url` API are post-1.7 Compose surfaces; the project's Compose BoM (`2026.02.01`, in `libs.versions.toml`) is well past that bar.

Code-review treats successful preview rendering + lint pass as the AC6 acceptance signal.

### `./gradlew test`

No new unit tests added. Existing test suite stays green.

## Open questions

- **Inline code background paints at glyph-rect bounds, not at padded rectangle.** Setting `SpanStyle(background = surfaceContainer)` on an `AnnotatedString` span has no horizontal padding option in Compose's text API — the background tints exactly the glyph rect of the inline span. The visual reads acceptably for short identifiers; longer code spans look tight. **Acceptable for #129.** If the tightness becomes a complaint, a follow-up replaces the `SpanStyle` approach with `InlineTextContent` per code span. Don't pre-build that now.
- **GFM vs CommonMark.** The spec defaults to `CommonMarkFlavourDescriptor` because every element in AC3 + AC4 is CommonMark. If Claude API responses turn out to lean on GFM (tables, strikethrough, task lists), swap to `GFMFlavourDescriptor()` and extend the renderer in a follow-up. The `else -> Text(...)` fallback prevents user-visible breakage in the meantime.
- **Library version pin.** Spec recommends `0.7.3` as of 2026-05-16. The developer should check Maven Central for the current latest stable at wire-up time and pin to that. Either way the API surface used here (`MarkdownParser`, `CommonMarkFlavourDescriptor`, `MarkdownElementTypes`, `ASTNode.children` / `getTextInNode`) has been stable across `0.x` releases.
- **Heading slot mapping (h1/h2/h3 → `headlineSmall`/`titleLarge`/`titleMedium`).** The mapping prioritises in-chat readability over "headings should be page-title sized". Assistant messages are body-flow content inside a bubble-less paragraph stream — using `displayLarge` for `# H1` would look comically large at chat scale. If a designer pushes back, the change is one line per slot in the renderer; do not re-debate this in the PR. The visual is preview-verifiable in both themes.
- **Selectable text.** Out of scope. If long-press-to-copy becomes desirable later, wrap the screen-level body in a `SelectionContainer` at the `ThreadScreen` layer (not inside `MarkdownText`). Adding it here would force every consumer into selection mode.
