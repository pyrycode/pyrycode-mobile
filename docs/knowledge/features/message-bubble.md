# MessageBubble

Stateless row primitive (#128) rendering a single `Message` in the conversation thread surface. Two visual variants dispatched off `Message.role`: a right-aligned painted bubble for `Role.User` (plain text) and a left-aligned flat text block for `Role.Assistant` (markdown-rendered via [`MarkdownText`](./markdown-text.md) since #129 — Claude-style, assistant text flows in the column with the screen surface behind it). Assistant content reveals progressively with a blinking caret when `Message.isStreaming = true` (#184). `Role.Tool` is a deliberate no-op pending #131. Code-block styling landed in #130. Eventual call site is the `LazyColumn(reverseLayout = true)` body of [`ThreadScreen`](./thread-screen.md), but #128 ships the component without consumer wiring; the wiring waits for #127's fake `observeMessages(...)` repository.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `MessageBubble.kt`. Sibling of [`DiscussionPreviewRow`](./discussion-preview-row.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Dispatches on `message.role` with a Kotlin `when`:

- **`Role.User`** → `UserMessageBubble(content, modifier)` — outer `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End)` pins to the right edge; inside, a `Surface(shape = UserBubbleShape, color = primaryContainer, modifier = Modifier.widthIn(max = UserBubbleMaxWidth))` wraps a `Text(content, style = bodyMedium, color = onPrimaryContainer)` with inner padding `14×12`. The asymmetric corner radii `(topStart = 20, topEnd = 20, bottomEnd = 6, bottomStart = 20)` give the bubble a "tail" pointing toward the user side.
- **`Role.Assistant`** → `AssistantMessage(message, modifier)` — `Box(Modifier.fillMaxWidth())` containing, inside `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { ... }`, either a static [`MarkdownText(markdown = message.content, modifier = Modifier.fillMaxWidth())`](./markdown-text.md) (when `message.isStreaming = false`) or a private `StreamingAssistantBody(content = message.content, modifier = Modifier.fillMaxWidth())` (when `message.isStreaming = true`) that reveals content progressively with a blinking caret (#184). **No `Surface`, no `Modifier.background(...)`, no shape, no inner padding.** The assistant message flows in the column with the screen's surface color behind it.
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
    Role.Assistant -> AssistantMessage(message, modifier)
    // Tool-role rendering lands in #131; ThreadScreen will route it before reaching here.
    Role.Tool -> Unit
}
```

The assistant arm takes the full `Message` (since #184) rather than `message.content` so the streaming branch can read `isStreaming` and `content` together.

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
        if (message.isStreaming) {
            StreamingAssistantBody(
                content = message.content,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MarkdownText(
                markdown = message.content,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

**No `Surface`, no `Modifier.background(...)`, no shape.** The Figma frame (`16:25`, `16:32`, `16:42`, `16:54`) renders a bubbled assistant on `surface-container-high` with mirrored asymmetric rounding `(20/20/6/20)`. The #128 ticket body in both its Context paragraph ("Claude-style — flat assistant, bubbled user") and AC3 explicitly overrides that to a flat treatment. The architect spec records the precedence note: AC wins; Figma reconciliation is a downstream design call, not a #128 follow-up. If a code reviewer pushes back on the flat choice, the project escalation path is `needs-rework:po`, not in-PR debate.

The asymmetry between variants is intentional — the user bubble is a self-contained painted surface, the assistant message is text that flows in the column with the screen's surface color behind it.

**Why `CompositionLocalProvider(LocalContentColor provides onSurface)` rather than a `color: Color` argument on `MarkdownText` (since #129).** The renderer's signature is deliberately minimal (`markdown: String, modifier: Modifier = Modifier`) — no `color`, no `style`, no `onLinkClick`. The assistant surface owns the colour contract (`onSurface` reads against the screen's `surface`); future surfaces hosting the same renderer (system messages, tool cards) supply their own ambient via the same pattern. See [`MarkdownText`](./markdown-text.md) for the full rationale.

### Streaming variant — progressive reveal + blinking caret (since #184)

When `message.isStreaming = true`, the assistant arm routes to a private `StreamingAssistantBody(content, modifier)` instead of the static `MarkdownText(...)` call. The composable derives two pieces of state via `produceState`:

- `revealedLength: State<Int>` keyed on `content`. Producer: `while (value < content.length) { delay(STREAMING_REVEAL_STEP_MS); value = (value + STREAMING_REVEAL_STEP_CHARS).coerceAtMost(content.length) }`. Reveal rate is one character per `STREAMING_REVEAL_STEP_MS = 20L` tick → 50 chars/sec. The `key1 = content` causes the producer to restart from 0 if the content snapshot changes (Phase 4: token-by-token growth from the WS feed).
- `caretVisible: State<Boolean>` keyed on `Unit`. Producer: `while (true) { delay(STREAMING_CARET_BLINK_PERIOD_MS); value = !value }`. `STREAMING_CARET_BLINK_PERIOD_MS = 500L` → 1 Hz toggle / 0.5 Hz full blink cycle (conventional terminal caret cadence). Independent of the reveal — the caret keeps blinking after the prefix is fully revealed (visual "still live" signal) until `isStreaming` flips `false` and `AssistantMessage` un-mounts the streaming subtree.

Both producers cancel automatically when the composable leaves composition (`produceState`'s built-in cleanup tied to the composition's coroutine scope). No `LaunchedEffect`, no `DisposableEffect`, no `viewModelScope` involvement.

The two values feed a second private composable `StreamingAssistantBodyView(revealedText, caretVisible, modifier)` (pure rendering, no state) that computes `displayText = revealedText + (if (caretVisible) STREAMING_CARET_GLYPH else "")` and calls `MarkdownText(markdown = displayText, modifier = modifier)`. The state-shell vs view split exists so previews can call the view directly with a pinned snapshot — Android Studio's `@Preview` runtime renders `produceState`-driven composables but the captured snapshot of fast animations is non-deterministic; pinning gives a deterministic, reviewable mid-state.

**Caret as inline text, not a sibling composable.** `STREAMING_CARET_GLYPH = "▎"` (U+258E LEFT ONE QUARTER BLOCK) — matches the Figma frame `16:56` end-state visual. The caret is appended to the revealed prefix and flows through `MarkdownText` as ordinary text, inheriting `LocalContentColor.current` (which the surrounding `CompositionLocalProvider` already pins to `onSurface`) and landing pixel-adjacent to the last revealed character regardless of which block element the prefix ends in (paragraph, heading, list item, blockquote, fenced code). A side-rendered caret would need text-measurement APIs and block-element-specific offset math; routing through the input string sidesteps both.

**Zero animation cost when not streaming.** The `if (message.isStreaming)` branch is the seam — historical messages (the typical case in Phase 0 after one streaming demo, and effectively all assistant messages once Phase 4's WS feed flips messages to `isStreaming = false` on stream-end) take the unchanged static `MarkdownText(...)` path. No `produceState`, no coroutine, no extra recomposition for the static path.

**Reveal restart on `content` change.** `produceState(initialValue = 0, key1 = content)` semantics: changing the key cancels the running producer and starts a new one. Phase 0 content is static so the producer runs once and the value pins at `content.length`. Phase 4 will grow `content` token-by-token; each growth restarts the reveal from 0 with the new (longer) content. That's the right behaviour as a safe default; if profiling Phase 4 ever flags the restart as wasteful, the right fix is hoisting `revealedLength` into the `ViewModel` keyed on `messageId` — not pre-paid here.

**Lifetime tied to `LazyColumn` item disposal.** When a streaming message scrolls off-screen in `ThreadScreen`'s `LazyColumn`, the item composable is disposed, both `produceState` coroutines cancel, and `revealedLength` is lost. Scrolling back into view re-enters composition, `revealedLength` restarts from 0, and the message animates again from the top. Phase-0 acceptable. Phase 4 may want VM-side hoisting; not built now.

### Spacing — file-private named `val`s, no `LocalSpacing` provider

Constants at the top of `MessageBubble.kt`:

```kotlin
private val MessageRowVerticalSpacing = 12.dp     // Figma gap-[12px] on node 16:21
private val UserBubbleMaxWidth = 320.dp           // ≈ 80% of 412dp canvas, 4dp-grid-aligned
private val UserBubbleShape = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomEnd = 6.dp, bottomStart = 20.dp,
)
private val BubbleHorizontalPadding = 14.dp       // Figma px-[14px]
private val BubbleVerticalPadding = 12.dp         // Figma py-[12px]

// Streaming (#184)
private const val STREAMING_CARET_GLYPH = "▎"     // U+258E LEFT ONE QUARTER BLOCK — Figma node 16:56
private const val STREAMING_REVEAL_CHARS_PER_SECOND = 50
private const val STREAMING_REVEAL_STEP_CHARS = 1
private const val STREAMING_REVEAL_STEP_MS: Long = 1000L / STREAMING_REVEAL_CHARS_PER_SECOND  // 20ms at 50cps
private const val STREAMING_CARET_BLINK_PERIOD_MS: Long = 500L  // 1Hz toggle / 0.5Hz full blink cycle
```

M3 does not expose a spacing scale, and the codebase has no `LocalSpacing` `CompositionLocal` provider. AC4 ("Vertical spacing between consecutive messages uses M3 spacing tokens (not raw `.dp` literals)") is satisfied at the spirit level: named values, design intent legible, no scatter of bare literals at multiple sites. Other components in `ui/conversations/components/` still use bare `.dp` literals (`ArchiveRow.kt:43`, `ConversationRow.kt:53`) — `MessageBubble` is the first beachhead; **don't back-fill** the named-constants pattern into sibling files until they grow their own naming pressure. The first cross-component spacing pressure (e.g. `MessageRowVerticalSpacing` shared with the #135 session-boundary delimiter) is the trigger for extracting these to an `internal val` peer file in the same package, mirroring the [`RelativeTime`](./discussion-preview-row.md#shared-relativetime-helper) helper's shape.

### Vertical rhythm — bottom-padding on the component, not `Arrangement.spacedBy` on the consumer

Each variant's outer `Row` / `Box` carries `Modifier.padding(bottom = MessageRowVerticalSpacing)`. Alternative considered: no padding inside `MessageBubble`; the eventual `LazyColumn` consumer uses `verticalArrangement = Arrangement.spacedBy(MessageRowVerticalSpacing)`. Picked the component-side direction because it keeps AC4 satisfied at the component level today, when no `LazyColumn` consumer is part of this ticket. Bottom-only (not symmetric `padding(vertical = 6.dp)`) avoids accumulating an extra leading gap above the topmost row. The trailing 12dp below the last message is the only visible artifact and is acceptable as a buffer before the (eventual) composer / status row.

If a downstream ticket later wants to switch to consumer-side `Arrangement.spacedBy(...)`, the migration is: remove the `.padding(bottom = MessageRowVerticalSpacing)` from both variant outers, expose the `MessageRowVerticalSpacing` constant via the file's package (or peer file), and set the arrangement on the `LazyColumn`. Either shape satisfies AC4; the component-side shape ships now.

### Ignored `Message` fields

- **`timestamp`** — not rendered in this ticket. Per-bubble or per-session-delimiter timestamp surfacing is a separate concern handled elsewhere.
- **`id`, `sessionId`** — passed through `Message` for `equals` / recomposition stability and downstream consumption (the eventual `LazyColumn` may key items by `message.id`), but not visually surfaced here.

`isStreaming` is consumed by the assistant arm since #184 — see the streaming-variant section above.

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

**Pair two — markdown rendering** (added in #129; extended in #184). `MessageBubbleMarkdownLightPreview` (`darkTheme = false`) and `MessageBubbleMarkdownDarkPreview` (`darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`) invoke a shared body composable `MessageBubbleMarkdownPreviewBody()` that now renders two stacked entries inside `Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(MessageRowVerticalSpacing))`:

1. A half-revealed streaming snapshot — `Box(Modifier.fillMaxWidth())` wrapping `CompositionLocalProvider(LocalContentColor provides onSurface) { StreamingAssistantBodyView(revealedText = MARKDOWN_PREVIEW_FIXTURE.take(MARKDOWN_PREVIEW_FIXTURE.length / 2), caretVisible = true) }`. The pinned snapshot guarantees the preview shows the caret glyph at the half-content mark deterministically (the `produceState`-driven path would render at an unpredictable position). The surrounding `CompositionLocalProvider` is required here because the view is invoked outside an `AssistantMessage` wrapper.
2. The completed message — `MessageBubble(previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE))`, exercising the `isStreaming = false` static path through the full `MessageBubble` dispatch.

The fixture is the file-private `MARKDOWN_PREVIEW_FIXTURE` raw triple-quoted string that exercises every supported markdown element since #129: h1/h2/h3, bold, italic, inline code, link to `https://pyryco.de`, unordered list, ordered list, blockquote, fenced Kotlin code block (plus the JSON / Bash / Markdown fences added in #130). The same fixture appears in both halves of the preview so the visual diff between them is exactly the streaming overlay (half-text + caret) vs the completed render.

`previewMessage(role, content)` is a file-private factory that fixes the rest of the `Message` shape (`id = "preview-${role.name}"`, `sessionId = "preview-session"`, `timestamp = Clock.System.now()`, `isStreaming = false`). Keeps the call sites focused on the varying fields (role and content) instead of repeating the full constructor.

No preview for the `Role.Tool` arm — the `when` renders nothing for it, so a preview would be a blank surface with no visual signal.

## Edge cases / limitations

- **User variant is plain text; assistant variant renders markdown** (since #129). User messages render `Text(message.content)` with no parsing — the user wrote them, they're not markdown sources. Assistant messages render through [`MarkdownText`](./markdown-text.md): CommonMark element set (h1–h3, bold, italic, ordered/unordered lists, inline code, blockquote, inline links, fenced/indented code blocks). Unsupported AST kinds (tables, HTML, strikethrough, task lists, images) hit a `bodyMedium` raw-text fallback. Full code-block styling (outline border, language label, syntax highlighting) lands in #130.
- **Streaming reveal is character-by-character at a fixed rate; no token-batch awareness.** Phase 0 has no token-batch source, so the "tokens" in token-reveal are characters. Phase 4's WS feed will grow `Message.content` token-batch by token-batch; the `produceState(key1 = content)` restart semantics mean each batch arrival restarts the reveal from 0 with the new content. Safe default; if profiling shows the restart is wasteful, the right fix is hoisting `revealedLength` to the VM keyed on `messageId` — see #184's lessons.
- **Streaming state is lost on `LazyColumn` item disposal.** Scrolling a streaming message off-screen disposes the item, cancels both `produceState` coroutines, and forgets `revealedLength`. Scrolling back re-mounts the composable and the reveal restarts from 0. Phase-0 acceptable.
- **Caret inside an open markdown construct falls back to plain text.** If the reveal cut lands inside an unclosed `**`, an unclosed backtick, an unterminated code fence, or an open `[link text`, the parser drops to its `else` raw-text fallback (per [`MarkdownText`](./markdown-text.md)) and the caret renders inside the unstyled fallback. Acceptable per AC2 — "the formatting that *can* apply does"; partial constructs go through the existing fallback.
- **No timestamps in-bubble.** `Message.timestamp` is unused here. Per-bubble timestamps or per-session-delimiter timestamps are out of scope.
- **No long-press, no `SelectionContainer`, no copy affordance.** The composable uses bare `Text(...)`, not `SelectionContainer { Text(...) }`. If long-press-to-copy lands later, the right place is a screen-level wrap of the `LazyColumn` body in a `SelectionContainer`, not per-bubble selection.
- **`Role.Tool` renders nothing.** ThreadScreen is expected to filter or route `Role.Tool` upstream (currently no consumer is wired). If a `Role.Tool` message slips into a future `items(state.messages) { MessageBubble(it) }` call before #131 lands, the row will be invisible — visible only in the trailing 12dp gap (which collapses adjacent to surrounding messages). That's the contract until #131.
- **No tool-role passthrough.** `MessageBubble` does not know about `ToolCard` or any sibling composable; the role-routing is local to this `when`. #131 either replaces the `Unit` arm with a real composable call, or filters tool messages out of `state.messages` upstream — either path satisfies the contract.
- **RTL.** `Arrangement.End` and `Modifier.fillMaxWidth()` respect `LayoutDirection` automatically — in RTL locales the user bubble pins to the left and the assistant text flows from the right. The corner-radius asymmetry (`bottomEnd = 6.dp` on `RoundedCornerShape`) is direction-aware and flips correctly. No explicit RTL handling needed.
- **Bubble max-width is `320.dp`, not `330.dp`.** Chosen for 4dp-grid alignment; `330.dp` would be pixel-exact to Figma `w-[330px]`. Either is acceptable per the architect spec; the choice is documented in the file via the named constant.
- **No instrumented tests.** Codebase has no `androidTest` infrastructure for thread/list components beyond existing fixtures — same precedent as #126's `ThreadScreen` (see [`thread-screen.md`](./thread-screen.md) § Testing). Visual verification is by the two `@Preview`s + `./gradlew assembleDebug` + `./gradlew lint`.

## Related

- Ticket notes: [`../codebase/128.md`](../codebase/128.md), [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/184.md`](../codebase/184.md)
- Specs: `docs/specs/architecture/128-message-bubble-user-assistant-variants.md`, `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md`
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (assistant-variant rendering pipeline), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md) (fenced-code styling)
- Upstream: [data model](./data-model.md) (`Message`, `Role`, `isStreaming`), [Thread screen](./thread-screen.md) (the eventual `LazyColumn(reverseLayout = true)` host — body wiring is downstream of #127)
- Component pipeline: [`MarkdownText`](./markdown-text.md) (consumed by the assistant variant since #129; the streaming caret in #184 rides through it as inline text without changing its signature)
- Sibling pattern references: [`DiscussionPreviewRow`](./discussion-preview-row.md) (closest stateless-row composable; mirrored preview-pairing shape), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — user variant matches nodes `16:23` / `16:39` / `16:51`; assistant variant **deliberately diverges** from `16:25` / `16:32` / `16:42` / `16:54` (Figma shows bubbled, AC mandates flat — AC wins, Figma reconciliation is a separate design call); streaming end-state at `16:54` → `16:56` shows the `▎` caret in body-text flow, which #184 reproduces exactly
- Downstream:
  - #127 — fake `observeMessages(conversationId)` repository (no consumer wiring until this lands)
  - #131 — tool-role rendering. Owns the `Role.Tool -> Unit` arm — either by introducing a `ToolMessage` composable and rewriting the arm, or by filtering `Role.Tool` upstream in `ThreadScreen`
  - #185 — auto-scroll behaviour that keeps the thread anchored to the bottom as the streaming message grows; consumes the same `Message.isStreaming` contract at the `ThreadScreen` layer, no change required inside `MessageBubble`
