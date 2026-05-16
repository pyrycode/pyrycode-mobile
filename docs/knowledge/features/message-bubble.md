# MessageBubble

Stateless row primitive (#128) rendering a single `Message` in the conversation thread surface. Two visual variants dispatched off `Message.role`: a right-aligned painted bubble for `Role.User` (plain text) and a left-aligned flat text block for `Role.Assistant` (markdown-rendered via [`MarkdownText`](./markdown-text.md) since #129 — Claude-style, assistant text flows in the column with the screen surface behind it). `Role.Tool` is a deliberate no-op pending #131. Code-block styling lands in #130, streaming caret in #132. Eventual call site is the `LazyColumn(reverseLayout = true)` body of [`ThreadScreen`](./thread-screen.md), but #128 ships the component without consumer wiring; the wiring waits for #127's fake `observeMessages(...)` repository.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `MessageBubble.kt`. Sibling of [`DiscussionPreviewRow`](./discussion-preview-row.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Dispatches on `message.role` with a Kotlin `when`:

- **`Role.User`** → `UserMessageBubble(content, modifier)` — outer `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End)` pins to the right edge; inside, a `Surface(shape = UserBubbleShape, color = primaryContainer, modifier = Modifier.widthIn(max = UserBubbleMaxWidth))` wraps a `Text(content, style = bodyMedium, color = onPrimaryContainer)` with inner padding `14×12`. The asymmetric corner radii `(topStart = 20, topEnd = 20, bottomEnd = 6, bottomStart = 20)` give the bubble a "tail" pointing toward the user side.
- **`Role.Assistant`** → `AssistantMessage(content, modifier)` — `Box(Modifier.fillMaxWidth())` containing a left-aligned [`MarkdownText(markdown = content, modifier = Modifier.fillMaxWidth())`](./markdown-text.md) wrapped in `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { ... }` (since #129; pre-#129 was a plain `Text(content, …)`). **No `Surface`, no `Modifier.background(...)`, no shape, no inner padding.** The assistant message flows in the column with the screen's surface color behind it.
- **`Role.Tool`** → `Unit` — renders nothing. ThreadScreen will route `Role.Tool` to a separate tool-card composable (introduced in #131) before reaching this dispatcher. The no-op arm is the contract until then.

Each variant adds `Modifier.padding(bottom = MessageRowVerticalSpacing = 12.dp)` to its outer container, so the per-row vertical rhythm lives on the component, not on the eventual `LazyColumn` consumer. The last message in the list carries a trailing 12dp before the (eventual) composer / status row — acceptable visual padding, not a bug.

## Shape

```kotlin
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
)
```

Single `Message` parameter (not pre-split `(text, isUser)`). The eventual call site is `items(state.messages) { MessageBubble(it) }` — consumers don't pre-split the list by role. The dispatcher reads `message.role` and routes; `modifier` is defaulted (the `LazyColumn` item slot usually doesn't pass one).

The composable is **pure rendering** — no `remember`, no `LaunchedEffect`, no coroutines, no state hoisting. `Message` is a `data class` with all stable fields (`String`, `Role` enum, `Instant`, `Boolean`), so Compose's stability inference skips recompositions on identity-equal and `equals`-equal inputs without any `@Stable` / `@Immutable` annotation.

## How it works

### Role dispatch — exhaustive `when`, no default, no defensive throw

```kotlin
when (message.role) {
    Role.User -> UserMessageBubble(message.content, modifier)
    Role.Assistant -> AssistantMessage(message.content, modifier)
    // Tool-role rendering lands in #131; ThreadScreen will route it before reaching here.
    Role.Tool -> Unit
}
```

The `Role.Tool -> Unit` arm is **deliberate, not a TODO**. The framework contract is: ThreadScreen routes `Role.Tool` to the (yet-to-land) tool-card composable from #131 before the list reaches this dispatcher. Rendering nothing is the right default until that route exists. No `error("Tool role not yet handled")` — would crash exactly the path the contract guarantees never happens. No debug placeholder `Text("[tool]")` — would leak into production builds where the contract isn't yet in force.

The absence of a default `else ->` arm is also deliberate: it keeps the Kotlin compiler enforcing exhaustiveness against the `Role` enum, so a future fourth value (e.g. `Role.System`) is flagged at this site as a hard compile-time decision rather than silently absorbed into a fallback. Project-wide pattern for `when (role)` / `when (kind)` dispatchers in `ui/conversations/components/`.

### User variant — `Surface` paints the background, `Text` carries the inner padding

```kotlin
Surface(
    modifier = Modifier.widthIn(max = UserBubbleMaxWidth),
    shape = UserBubbleShape,
    color = MaterialTheme.colorScheme.primaryContainer,
) {
    Text(
        text = content,
        modifier = Modifier.padding(horizontal = BubbleHorizontalPadding, vertical = BubbleVerticalPadding),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}
```

Padding lives on the inner `Text`, not the outer `Surface`. The reverse (`Surface(modifier = Modifier.padding(...), ...)`) paints the bubble background *inside* the padded area, leaving the rounded corners floating in an unpainted gap. The order shipped here keeps the painted `primaryContainer` background extending under the padding band, so the bubble looks like a single solid shape.

`widthIn(max = UserBubbleMaxWidth = 320.dp)` caps the bubble at ~80% of a 412dp canvas (4dp-grid-aligned approximation of Figma's `w-[330px]` — `330.dp` is also acceptable for pixel-exact Figma match; pick one and name the constant). Short messages shrink-wrap; long messages wrap inside the cap.

### Assistant variant — flat, deliberately diverges from Figma; markdown-rendered since #129

```kotlin
Box(modifier = Modifier.fillMaxWidth().padding(bottom = MessageRowVerticalSpacing)) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        MarkdownText(
            markdown = content,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

**No `Surface`, no `Modifier.background(...)`, no shape.** The Figma frame (`16:25`, `16:32`, `16:42`, `16:54`) renders a bubbled assistant on `surface-container-high` with mirrored asymmetric rounding `(20/20/6/20)`. The #128 ticket body in both its Context paragraph ("Claude-style — flat assistant, bubbled user") and AC3 explicitly overrides that to a flat treatment. The architect spec records the precedence note: AC wins; Figma reconciliation is a downstream design call, not a #128 follow-up. If a code reviewer pushes back on the flat choice, the project escalation path is `needs-rework:po`, not in-PR debate.

The asymmetry between variants is intentional — the user bubble is a self-contained painted surface, the assistant message is text that flows in the column with the screen's surface color behind it.

**Why `CompositionLocalProvider(LocalContentColor provides onSurface)` rather than a `color: Color` argument on `MarkdownText` (since #129).** The renderer's signature is deliberately minimal (`markdown: String, modifier: Modifier = Modifier`) — no `color`, no `style`, no `onLinkClick`. The assistant surface owns the colour contract (`onSurface` reads against the screen's `surface`); future surfaces hosting the same renderer (system messages, tool cards) supply their own ambient via the same pattern. See [`MarkdownText`](./markdown-text.md) for the full rationale.

### Spacing — file-private named `val`s, no `LocalSpacing` provider

Five constants at the top of `MessageBubble.kt`:

```kotlin
private val MessageRowVerticalSpacing = 12.dp     // Figma gap-[12px] on node 16:21
private val UserBubbleMaxWidth = 320.dp           // ≈ 80% of 412dp canvas, 4dp-grid-aligned
private val UserBubbleShape = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomEnd = 6.dp, bottomStart = 20.dp,
)
private val BubbleHorizontalPadding = 14.dp       // Figma px-[14px]
private val BubbleVerticalPadding = 12.dp         // Figma py-[12px]
```

M3 does not expose a spacing scale, and the codebase has no `LocalSpacing` `CompositionLocal` provider. AC4 ("Vertical spacing between consecutive messages uses M3 spacing tokens (not raw `.dp` literals)") is satisfied at the spirit level: named values, design intent legible, no scatter of bare literals at multiple sites. Other components in `ui/conversations/components/` still use bare `.dp` literals (`ArchiveRow.kt:43`, `ConversationRow.kt:53`) — `MessageBubble` is the first beachhead; **don't back-fill** the named-constants pattern into sibling files until they grow their own naming pressure. The first cross-component spacing pressure (e.g. `MessageRowVerticalSpacing` shared with the #135 session-boundary delimiter) is the trigger for extracting these to an `internal val` peer file in the same package, mirroring the [`RelativeTime`](./discussion-preview-row.md#shared-relativetime-helper) helper's shape.

### Vertical rhythm — bottom-padding on the component, not `Arrangement.spacedBy` on the consumer

Each variant's outer `Row` / `Box` carries `Modifier.padding(bottom = MessageRowVerticalSpacing)`. Alternative considered: no padding inside `MessageBubble`; the eventual `LazyColumn` consumer uses `verticalArrangement = Arrangement.spacedBy(MessageRowVerticalSpacing)`. Picked the component-side direction because it keeps AC4 satisfied at the component level today, when no `LazyColumn` consumer is part of this ticket. Bottom-only (not symmetric `padding(vertical = 6.dp)`) avoids accumulating an extra leading gap above the topmost row. The trailing 12dp below the last message is the only visible artifact and is acceptable as a buffer before the (eventual) composer / status row.

If a downstream ticket later wants to switch to consumer-side `Arrangement.spacedBy(...)`, the migration is: remove the `.padding(bottom = MessageRowVerticalSpacing)` from both variant outers, expose the `MessageRowVerticalSpacing` constant via the file's package (or peer file), and set the arrangement on the `LazyColumn`. Either shape satisfies AC4; the component-side shape ships now.

### Ignored `Message` fields

- **`timestamp`** — not rendered in this ticket. Per-bubble or per-session-delimiter timestamp surfacing is a separate concern handled elsewhere.
- **`isStreaming`** — ignored. Streaming caret + animation lands in #132 without changing the public API; the role-arm composables internalise the change.
- **`id`, `sessionId`** — passed through `Message` for `equals` / recomposition stability and downstream consumption (the eventual `LazyColumn` may key items by `message.id`), but not visually surfaced here.

## Configuration

- **One transitive dependency since #129:** the assistant variant routes through [`MarkdownText`](./markdown-text.md), which is wired against `org.jetbrains:markdown` (see [ADR 0002](../decisions/0002-markdown-renderer-library.md)). The component file itself still uses only `androidx.compose.foundation.*` (`Row`, `Box`, `Column`, `Arrangement`, `fillMaxWidth`, `padding`, `widthIn`, `RoundedCornerShape`), `androidx.compose.material3.*` (`LocalContentColor`, `Surface`, `Text`, `MaterialTheme`), `androidx.compose.runtime.CompositionLocalProvider`, and `kotlinx.datetime.Clock` (in the preview helper only — production path uses the timestamp from the supplied `Message`).
- **No new strings.** User variant renders `Text(message.content, …)` plainly; assistant variant renders `MarkdownText(markdown = message.content, …)`. No resource lookup, no role prefix, no fallback copy.
- **No theme overrides.** Reads `colorScheme.primaryContainer`, `colorScheme.onPrimaryContainer`, `colorScheme.onSurface`, `typography.bodyMedium` directly; the assistant variant supplies `onSurface` to the renderer through `LocalContentColor` rather than passing it as a parameter. All are M3 defaults — no custom slots.

## Previews

Four `@Preview`s at the bottom of `MessageBubble.kt`, all `widthDp = 412`.

**Pair one — sequence rendering, plain-text-through-MarkdownText path** (added in #128, unchanged by #129). Both render a shared `MessageBubblePreviewSequence()` of four messages (User → Assistant → User → Assistant) inside `Surface { Column(Modifier.padding(horizontal = 16.dp)) { … } }`:

- **`MessageBubbleLightPreview`** — `PyrycodeMobileTheme(darkTheme = false)`. Verifies the user bubble's `primaryContainer` fill and `onPrimaryContainer` text contrast against the light surface, plus the assistant variant's `onSurface` text legibility. Since #129, the assistant rows in this preview also serve as the no-markdown-syntax verification path through [`MarkdownText`](./markdown-text.md) — markdown-free assistant content must still render correctly.
- **`MessageBubbleDarkPreview`** — same shape, `darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`.

The four-message sequence alternates roles to exercise both variants and the alternation rhythm. The last assistant message is a long multi-sentence string ("Good catch. There are three classes of edge case here, the most important being null user_ids in the legacy table — let me walk through each.") to verify multi-line wrap. The wrapping `Surface { Column(...) }` matches what the eventual `LazyColumn` runtime provides: the assistant variant's transparent background renders against the theme's surface color, and the user bubble has a 16dp column inset to pin against instead of the raw preview viewport edge.

**Pair two — markdown rendering** (added in #129; satisfies #129's AC6). `MessageBubbleMarkdownLightPreview` (`darkTheme = false`) and `MessageBubbleMarkdownDarkPreview` (`darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`) render a single assistant-variant `MessageBubble` whose content is a file-private `MARKDOWN_PREVIEW_FIXTURE` raw triple-quoted string exercising every supported markdown element: h1/h2/h3, bold, italic, inline code, link to `https://pyryco.de`, unordered list, ordered list, blockquote, fenced Kotlin code block. The shared body composable `MessageBubbleMarkdownPreviewBody()` wraps the single bubble in `Column(Modifier.padding(horizontal = 16.dp))` for consistent inset.

`previewMessage(role, content)` is a file-private factory that fixes the rest of the `Message` shape (`id = "preview-${role.name}"`, `sessionId = "preview-session"`, `timestamp = Clock.System.now()`, `isStreaming = false`). Keeps the call sites focused on the varying fields (role and content) instead of repeating the full constructor.

No preview for the `Role.Tool` arm — the `when` renders nothing for it, so a preview would be a blank surface with no visual signal.

## Edge cases / limitations

- **User variant is plain text; assistant variant renders markdown** (since #129). User messages render `Text(message.content)` with no parsing — the user wrote them, they're not markdown sources. Assistant messages render through [`MarkdownText`](./markdown-text.md): CommonMark element set (h1–h3, bold, italic, ordered/unordered lists, inline code, blockquote, inline links, fenced/indented code blocks). Unsupported AST kinds (tables, HTML, strikethrough, task lists, images) hit a `bodyMedium` raw-text fallback. Full code-block styling (outline border, language label, syntax highlighting) lands in #130.
- **No streaming caret.** `Message.isStreaming = true` renders identically to `false` — no animated caret, no per-character reveal. #132 adds the caret without changing the public API.
- **No timestamps in-bubble.** `Message.timestamp` is unused here. Per-bubble timestamps or per-session-delimiter timestamps are out of scope.
- **No long-press, no `SelectionContainer`, no copy affordance.** The composable uses bare `Text(...)`, not `SelectionContainer { Text(...) }`. If long-press-to-copy lands later, the right place is a screen-level wrap of the `LazyColumn` body in a `SelectionContainer`, not per-bubble selection.
- **`Role.Tool` renders nothing.** ThreadScreen is expected to filter or route `Role.Tool` upstream (currently no consumer is wired). If a `Role.Tool` message slips into a future `items(state.messages) { MessageBubble(it) }` call before #131 lands, the row will be invisible — visible only in the trailing 12dp gap (which collapses adjacent to surrounding messages). That's the contract until #131.
- **No tool-role passthrough.** `MessageBubble` does not know about `ToolCard` or any sibling composable; the role-routing is local to this `when`. #131 either replaces the `Unit` arm with a real composable call, or filters tool messages out of `state.messages` upstream — either path satisfies the contract.
- **RTL.** `Arrangement.End` and `Modifier.fillMaxWidth()` respect `LayoutDirection` automatically — in RTL locales the user bubble pins to the left and the assistant text flows from the right. The corner-radius asymmetry (`bottomEnd = 6.dp` on `RoundedCornerShape`) is direction-aware and flips correctly. No explicit RTL handling needed.
- **Bubble max-width is `320.dp`, not `330.dp`.** Chosen for 4dp-grid alignment; `330.dp` would be pixel-exact to Figma `w-[330px]`. Either is acceptable per the architect spec; the choice is documented in the file via the named constant.
- **No instrumented tests.** Codebase has no `androidTest` infrastructure for thread/list components beyond existing fixtures — same precedent as #126's `ThreadScreen` (see [`thread-screen.md`](./thread-screen.md) § Testing). Visual verification is by the two `@Preview`s + `./gradlew assembleDebug` + `./gradlew lint`.

## Related

- Ticket notes: [`../codebase/128.md`](../codebase/128.md), [`../codebase/129.md`](../codebase/129.md)
- Specs: `docs/specs/architecture/128-message-bubble-user-assistant-variants.md`, `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (assistant-variant rendering pipeline)
- Upstream: [data model](./data-model.md) (`Message`, `Role`), [Thread screen](./thread-screen.md) (the eventual `LazyColumn(reverseLayout = true)` host — body wiring is downstream of #127)
- Component pipeline: [`MarkdownText`](./markdown-text.md) (consumed by the assistant variant since #129)
- Sibling pattern references: [`DiscussionPreviewRow`](./discussion-preview-row.md) (closest stateless-row composable; mirrored preview-pairing shape), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — user variant matches nodes `16:23` / `16:39` / `16:51`; assistant variant **deliberately diverges** from `16:25` / `16:32` / `16:42` / `16:54` (Figma shows bubbled, AC mandates flat — AC wins, Figma reconciliation is a separate design call)
- Downstream:
  - #127 — fake `observeMessages(conversationId)` repository (no consumer wiring until this lands)
  - #130 — full code-block styling (outline border, language label, syntax highlighting). Replaces the `CodeBlock` composable inside [`MarkdownText`](./markdown-text.md) without changing `MessageBubble`'s API
  - #131 — tool-role rendering. Owns the `Role.Tool -> Unit` arm — either by introducing a `ToolMessage` composable and rewriting the arm, or by filtering `Role.Tool` upstream in `ThreadScreen`
  - #132 — streaming caret + animation (consumes `Message.isStreaming`; public API unchanged; partial markdown strings flow through `MarkdownText` unchanged)
