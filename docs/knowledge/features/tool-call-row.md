# ToolCallRow

Stateless row primitive (#131) rendering a single `ToolCall` payload in the conversation thread surface. Two states — **collapsed** (default) and **expanded** — toggled by tapping anywhere on the row's `surfaceContainerHigh` tile. Collapsed view shows a leading tool-type icon plus a two-tone `ToolName · primary-arg` summary, single-line with ellipsis truncation. Expanded view appends an "Input" / "Output" pair under the collapsed header; code-ish output (multi-line or > 80 chars) renders through [`MarkdownText`](./markdown-text.md)'s internal `CodeBlock` composable, plain output renders as inline `Monospace` text on the parent surface. Toggle state survives configuration changes via `rememberSaveable`; no `ViewModel` coupling. Routed from [`MessageBubble`](./message-bubble.md)'s `Role.Tool` arm via a null-safe `?.let`.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ToolCallRow.kt`. Sibling of [`MessageBubble`](./message-bubble.md), [`MarkdownText`](./markdown-text.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Consumes a [`ToolCall(toolName, input, output)`](./data-model.md) instance — the payload unwrapped from `Message.toolCall` by `MessageBubble`'s `Role.Tool` arm — and renders it as a left-aligned, full-width rounded card with a single `M3 ripple` click target spanning the whole tile.

**Collapsed (default).** A 36dp-tall row with a 18dp leading `Outlined` icon (`Description` / `Edit` / `Terminal` / `Build` fallback per tool name, case-insensitive) followed by an `AnnotatedString` of the form `"$toolName · $primaryArg"` rendered as `MaterialTheme.typography.bodyMedium` with `maxLines = 1` + `TextOverflow.Ellipsis`. The tool-name span uses `MaterialTheme.colorScheme.tertiary` + `FontFamily.Monospace` (matches Figma's `Roboto Mono Regular`); the separator and primary-arg span use `MaterialTheme.colorScheme.onSurfaceVariant` proportional (matches Figma's `body-small`). The icon is tinted `onSurfaceVariant`.

**Expanded.** Below the collapsed header (separated by an 8dp top padding), a `Column` of two `ExpandedSection`s — `"Input"` then `"Output"`. Each section is a `labelSmall` caption (in `onSurfaceVariant`) on top of the body. Body dispatch follows the code-ish heuristic: `content.contains('\n') || content.length > 80` routes through `CodeBlock(content, language = null)` from [`MarkdownText`](./markdown-text.md); otherwise, plain `bodyMedium` `Monospace` `Text` on the parent surface (no nested coloured tile).

## Shape

```kotlin
@Composable
fun ToolCallRow(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
)
```

**Single parameter is `ToolCall`** — the inner payload, not the wrapping `Message`. The `Role`-dispatch and `Message.toolCall` null-unwrap happen one level up in [`MessageBubble`](./message-bubble.md)'s `Role.Tool` arm; `ToolCallRow` is a leaf renderer that consumes the already-unwrapped value.

**`modifier` defaulted to `Modifier`.** The caller is `MessageBubble`'s `Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }`; in practice the eventual `LazyColumn` item slot doesn't pass one.

**No `onClick` parameter, no `expanded` parameter.** Tap-to-toggle is internal to the composable via `rememberSaveable`. The public surface is intentionally minimal — the row owns its expansion state, the caller owns nothing beyond the payload.

The composable is **pure rendering on top of one piece of internal state**. The state shell holds `var expanded by rememberSaveable { mutableStateOf(false) }`; the inner stateless `ToolCallRowContent(toolCall, expanded, onToggle, modifier)` does the actual layout work. The split exists for preview ergonomics — see [Previews](#previews).

## How it works

### Stateful wrapper + stateless content split

```kotlin
@Composable
fun ToolCallRow(toolCall: ToolCall, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToolCallRowContent(
        toolCall = toolCall,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    )
}

@Composable
private fun ToolCallRowContent(
    toolCall: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) { ... }
```

`rememberSaveable` serialises the `Boolean` to the saved-instance-state bundle, so rotation, dark-mode toggle, and process death + restore all preserve `expanded`. **No `key` parameter** — the default `rememberSaveable` key (composable call-site identity) suffices because the eventual `LazyColumn` item slot keys items on `Message.id`, giving each `ToolCallRow` instance a stable identity per message.

**Lifetime tied to `LazyColumn` item disposal.** When the row scrolls off-screen and the item composable is disposed, `expanded` is lost; scrolling back in remounts the composable with `expanded = false`. Phase-0 acceptable; if users complain about losing expansion state on scroll-away later, the fix is hoisting `expanded` into the `ViewModel` keyed on `Message.id` (`Map<MessageId, Boolean>`), not switching `remember*` variants. Same trade-off shape as [`MessageBubble`](./message-bubble.md)'s streaming `revealedLength`.

The stateless inner is invoked from the previews directly with pinned `(expanded = false)` / `(expanded = true)` snapshots — no coroutine, no saved-state harness needed.

### Visual contract

```kotlin
Row(modifier.fillMaxWidth().padding(bottom = MessageRowVerticalSpacing))
└── Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(ToolCallCornerRadius),          // 12.dp
        color  = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    └── Column(modifier = Modifier.padding(
            horizontal = ToolCallHorizontalPadding,                 // 12.dp
            vertical   = ToolCallVerticalPadding,                   // 8.dp
        ))
        ├── CollapsedHeaderRow(toolCall)                            // always present
        └── if (expanded) ExpandedBody(toolCall)                    // conditional
```

The outer `Row.padding(bottom = MessageRowVerticalSpacing = 12.dp)` carries the per-variant rhythm, matching [`MessageBubble`](./message-bubble.md)'s pattern — vertical rhythm lives on the component, not on the eventual `LazyColumn` consumer.

The `Surface` is `fillMaxWidth` — the tool card stretches edge-to-edge of the column, not capped like the user bubble's `widthIn(max = 320.dp)`. Figma's tool card is a flat one-line text element whose width is content-driven; full-width here keeps the `Modifier.clickable` target wider and reads more consistently in a list.

`Modifier.clickable(onClick = onToggle)` uses the **default M3 ripple and indication**. No custom ripple colour, no `onClickLabel`, no `Role.Button` semantics. A future a11y review may surface the gap — see [Edge cases / limitations](#edge-cases--limitations).

### Three deliberate Figma deviations

Figma `16:28`–`16:31` shows the tool-call card with `surface-container` fill + 1dp `outline-variant` border, no icon, no expand affordance — always-collapsed, content-only. This component diverges on three points (the ticket body is authoritative per the architect spec for #131):

1. **Background.** `surfaceContainerHigh` with no border. Within the thread, `Role.User` bubble (`primaryContainer`), tool card (`surfaceContainerHigh`), and the eventual session delimiter (transparent) form a three-step contrast ladder legible without an outline stroke.
2. **Leading icon.** 18dp `Icon` tinted `onSurfaceVariant`, `contentDescription = null` (the adjacent `Text` carries the semantic content — a TalkBack user hears `"Read · path/to/file.kt"`, not `"Read · path/to/file.kt, document icon"`). Same posture as `ArchiveRow`'s leading `Refresh` icon.
3. **Expand affordance.** Tap-to-toggle expanded state with no chevron / caret rendered — the entire surface is the click target. A chevron is design-for-hypothetical-future; the whole row is the tap target.

The two-tone tool-name-in-`tertiary` + arg-in-`onSurfaceVariant` split is **preserved verbatim from Figma** — it's the strongest visual cue that this row is a tool call, not a regular message.

### Collapsed header row

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(ToolCallHeaderGap),     // 8.dp
)
├── Icon(
│       imageVector = iconForTool(toolCall.toolName),
│       contentDescription = null,
│       modifier = Modifier.size(ToolCallIconSize),                       // 18.dp
│       tint = MaterialTheme.colorScheme.onSurfaceVariant,
│   )
└── Text(
        text = buildSummaryAnnotated(toolCall),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
```

`Modifier.weight(1f)` on the `Text` lets it absorb the remaining horizontal space so `maxLines = 1` + `TextOverflow.Ellipsis` clip the primary-arg when it would wrap (AC4 from the issue).

`buildSummaryAnnotated(toolCall)` is a `@Composable` builder reading `MaterialTheme.colorScheme.tertiary` and `.onSurfaceVariant` once, then producing an `AnnotatedString`:

```kotlin
buildAnnotatedString {
    withStyle(SpanStyle(color = toolNameColor, fontFamily = FontFamily.Monospace)) {
        append(toolCall.toolName)
    }
    withStyle(SpanStyle(color = argColor)) {
        append(" · ")
        append(primaryArg(toolCall))
    }
}
```

Two colour-stops match Figma. The monospace `fontFamily` on the tool name mirrors Figma's `Roboto Mono Regular`; the arg span inherits proportional `bodyMedium`. Both spans render through `MaterialTheme.typography.bodyMedium` for consistency with the rest of the row.

### Expanded body

```kotlin
Column(
    modifier = Modifier.padding(top = ToolCallExpandedTopPadding),          // 8.dp
    verticalArrangement = Arrangement.spacedBy(ToolCallExpandedGap),        // 8.dp
)
├── ExpandedSection(label = "Input",  content = toolCall.input)
└── ExpandedSection(label = "Output", content = toolCall.output)
```

`ExpandedSection(label, content)` is a private helper:

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(ToolCallExpandedGap / 2)) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isCodeIsh(content)) {
        CodeBlock(content = content, language = null)
    } else {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

The `"Input"` / `"Output"` caption labels are English-only Phase-0 copy; when localisation lands they move to `strings.xml` along with the rest of the UI.

### Primary-arg derivation

```kotlin
private fun primaryArg(toolCall: ToolCall): String = toolCall.input.trim()
```

Given the current [`ToolCall(input: String, output: String)`](./data-model.md) shape from #127, the `input` field carries the path (for `Read` / `Edit`) or the command (for `Bash`) directly as a flat string. **No multi-param JSON to parse, no first-param lookup.** The AC's three cases all reduce to "use `input` directly".

**Forward-compatibility.** If #191's eventual structured payload replaces `input: String` with a typed `Map<String, JsonElement>` or similar, this helper gains real branching. For #131, the trivial body is the contract. The public `ToolCallRow(toolCall, modifier)` signature stays unchanged across that swap.

**Empty-input edge case.** If `input.trim().isEmpty()` (e.g. `Bash` with no command — degenerate), the summary renders as `"Bash · "` with a trailing dot-space. Acceptable visual; the row is still tappable, the expanded body still shows the (empty) input and the output. Not worth special-casing.

### Icon mapping

```kotlin
private fun iconForTool(toolName: String): ImageVector = when (toolName.trim().lowercase()) {
    "read" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "bash" -> Icons.Outlined.Terminal
    else   -> Icons.Outlined.Build
}
```

Match is **case-insensitive** on the trimmed tool name — the seeded fixture uses `"Read"` (PascalCase, matching Claude Code's tool names); future wire-format calls may arrive in lowercase, so canonicalising at the comparison point is cheaper than canonicalising the data.

**Outlined family**, not filled — passive metadata, not interactive controls; visual weight reads better against the `surfaceContainerHigh` tile. If a future ticket lands a darker / higher-contrast tonal slot for the tool card, re-evaluate against `Icons.Filled.*` (same import-path shape, mechanical swap).

`Icons.Outlined.Description`, `.Terminal`, and `.Build` ship in **`androidx.compose.material:material-icons-extended`** — added as a library dependency by this ticket. The `Edit` icon is in `material-icons-core` (already on the classpath since pre-#131).

### Code-ish heuristic

```kotlin
private const val CODE_ISH_LENGTH_THRESHOLD = 80

private fun isCodeIsh(content: String): Boolean =
    content.contains('\n') || content.length > CODE_ISH_LENGTH_THRESHOLD
```

The `80` threshold matches typical terminal-column conventions and aligns roughly with the `bodyMedium` monospace wrap point on a 412dp canvas. **No special-casing of empty strings** — `"".contains('\n')` is `false` and `"".length > 80` is `false`, so empty outputs render as plain (empty) `Text` rather than an empty `CodeBlock`.

The heuristic is applied **per-section**, independently. In practice, `Read`'s `input` (a path) is short + single-line → plain `Text`; `Read`'s `output` (file contents) is almost always multi-line → `CodeBlock`. `Bash`'s input (a command) is usually short + single-line → plain; output varies.

### File-private spacing constants

```kotlin
private val MessageRowVerticalSpacing = 12.dp
private val ToolCallCornerRadius = 12.dp
private val ToolCallHorizontalPadding = 12.dp
private val ToolCallVerticalPadding = 8.dp
private val ToolCallHeaderGap = 8.dp
private val ToolCallIconSize = 18.dp
private val ToolCallExpandedTopPadding = 8.dp
private val ToolCallExpandedGap = 8.dp
private const val CODE_ISH_LENGTH_THRESHOLD = 80
```

`MessageRowVerticalSpacing = 12.dp` is **redeclared** here rather than imported from [`MessageBubble.kt`](./message-bubble.md). The duplication is intentional — promoting it to an `internal` shared constant is a refactor that touches all variants; the size-S budget for #131 doesn't include that cleanup. A future ticket introducing `Spacing.kt` or a `LocalSpacing` `CompositionLocal` provider can collapse the two declarations then. **Don't pre-pay** for the abstraction; the rule-of-three triggers it when a third component declares the same constant.

Every value is a `dp` literal at the declaration site; consuming composables reference the named constant. **No `.dp` literal at any call site.**

## Configuration

- **One transitive dependency added in #131:** `androidx.compose.material:material-icons-extended` (BOM-managed; one `[libraries]` line in `gradle/libs.versions.toml`, one `implementation(...)` line in `app/build.gradle.kts`). Carries the `Description`, `Terminal`, and `Build` icons. R8 / minify-release strips the unused ~6997 other icons in release builds; debug builds carry the full set (~3–4 MB of bloat).
- **No new strings.** `"Input"` and `"Output"` are inlined English-only Phase-0 copy. Move to `strings.xml` when localisation lands.
- **No theme overrides.** Reads `colorScheme.surfaceContainerHigh`, `.tertiary`, `.onSurfaceVariant`, `.onSurface`, `typography.bodyMedium`, `.labelSmall` directly. All M3 defaults.
- **No `ViewModel` wiring, no DI changes.** Pure leaf composable; the only `Composable` lookup is the colour and typography scheme.

## Routing from `MessageBubble`

```kotlin
// MessageBubble.kt:55
when (message.role) {
    Role.User -> UserMessageBubble(message.content, modifier)
    Role.Assistant -> AssistantMessage(message, modifier)
    Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }
}
```

The `?.let` absorbs the [`Message`](./data-model.md) invariant (`toolCall` non-null iff `role == Role.Tool`) silently. If a data-layer bug ever produces `Role.Tool` with `toolCall = null`, the arm renders nothing — identical to the pre-#131 `Role.Tool -> Unit` posture. **No `!!`, no `requireNotNull`, no `error(...)`.** Visible-bug-but-not-crash failure mode.

Routing happens inside `MessageBubble`'s `when`, not lifted to [`ThreadScreen`](./thread-screen.md). The architect spec considered both; `MessageBubble` was picked because it already owns the `Role`-fanout, and lifting Tool-routing one level up would force every future consumer of `Message` to repeat the null-unwrap.

## Previews

Two `@Preview` functions at the bottom of `ToolCallRow.kt`, both `widthDp = 412`:

- **`ToolCallRowLightPreview`** — `PyrycodeMobileTheme(darkTheme = false)`.
- **`ToolCallRowDarkPreview`** — `PyrycodeMobileTheme(darkTheme = true)`, `uiMode = Configuration.UI_MODE_NIGHT_YES`.

Both delegate to a shared `ToolCallRowPreviewMatrix()` that renders 6 cells in a vertical `Column` inside `Surface { … }`:

| Row | Tool | State |
|---|---|---|
| 1 | `PreviewReadToolCall` | collapsed |
| 2 | `PreviewReadToolCall` | expanded |
| 3 | `PreviewEditToolCall` | collapsed |
| 4 | `PreviewEditToolCall` | expanded |
| 5 | `PreviewBashToolCall` | collapsed |
| 6 | `PreviewBashToolCall` | expanded |

Total: **3 tools × 2 states × 2 themes = 12 visual cells across 2 preview functions**, satisfying issue AC7.

The matrix invokes `ToolCallRowContent` directly (not `ToolCallRow`) with pinned `expanded` values — driving the stateless inner skips the `rememberSaveable` toggle, so the preview renders both states deterministically without needing two separate composables or an interaction harness.

**Preview fixtures** are file-private `val`s at the bottom of `ToolCallRow.kt` — not added to [`FakeConversationRepository`](./conversation-repository.md):

- `PreviewReadToolCall` — `toolName = "Read"`, a long `MessageBubble.kt` path that fires the ellipsis in the collapsed cell, a multi-line Kotlin-source `output` that fires the `CodeBlock` path.
- `PreviewEditToolCall` — `toolName = "Edit"`, a `Theme.kt` path, a diff-ish `output` (`@@ -42,3 +42,3 @@\n-val Foo = 1\n+val Foo = 2`) to exercise the multi-line `Edit` path.
- `PreviewBashToolCall` — `toolName = "Bash"`, `input = "git status"` (short, plain), a multi-line stdout `output` (`CodeBlock` path).

Keeping the previews self-contained means changing the rendering doesn't ripple into the data-layer fake, and the seed pipeline isn't tied to preview cosmetics.

## Edge cases / limitations

- **Toggle state is lost on `LazyColumn` item disposal.** Scrolling a row off-screen disposes the item and forgets `expanded`. Scrolling back re-mounts the composable with `expanded = false`. Phase-0 acceptable; the right fix when users complain is `ViewModel`-side hoisting keyed on `Message.id`, not a different `remember*` variant.
- **Empty `input` renders as `"ToolName · "`** (trailing dot-space). Visually odd but renders without crash; not worth special-casing for the degenerate case.
- **Empty `toolName` falls through to `Icons.Outlined.Build`** and the summary renders as `" · ${input}"` (leading separator). Visually odd; acceptable for never-observed data-layer corruption.
- **Empty `output` renders an empty plain `Text`** under the `"Output"` caption. The caption stays.
- **Long single-line output (> 80 chars, no newline)** is classified `isCodeIsh = true` and routes through `CodeBlock(language = null)` with horizontal scroll — long lines scroll instead of wrapping, same behaviour as [#130](../codebase/130.md)'s `softWrap = false`.
- **Very long multi-line output (e.g. 10k+ lines from a large `Read`)** lays out the full `Text` node; Compose handles arbitrarily long text without crashing, but layout cost scales with content length. **Out of scope for #131.** Phase 0 fixtures are bounded; Phase 4 inherits Claude API response-size limits. If users hit it, a follow-up adds a "Show more" affordance or a max-height cap with internal scroll.
- **No language inference from path extension.** `Read` / `Edit` outputs always pass `language = null` to `CodeBlock`, so syntax highlighting from #130 doesn't fire. A future ticket can add a one-helper `extensionToLanguage(path: String): String?` that the `Output`-section `ExpandedSection` uses when the tool is `Read` / `Edit`. Cheap to bolt on — deferred so the visual baseline lands first.
- **Tap-target height (~36dp) is below M3's 48dp minimum.** Single-line collapsed row at `12dp` horizontal × `8dp` vertical padding plus `bodyMedium` text height. Acceptable in a scrolling list (the whole row is the target, adjacent tool calls are spaced by 12dp); a future ticket may add `minHeight(48.dp)` to the `Surface` if a11y review flags it. Not pre-built.
- **No disclosure affordance (chevron / caret).** If users miss that the row is tappable, the standard M3 pattern is a trailing `Icons.Outlined.ExpandMore` / `ExpandLess` glyph. Hold until preview review or in-app use surfaces the need.
- **No expand/collapse animation.** Conditional `if (expanded) ExpandedBody(toolCall)` adds/removes the subtree on toggle. Out of scope for #131; a future ticket can wrap in `AnimatedVisibility(visible = expanded) { ExpandedBody(toolCall) }` without touching the public API.
- **No TalkBack "expanded" / "collapsed" announcement.** `Modifier.clickable` carries no `onClickLabel` or `Role.Button` semantics. Not in AC; add only if a11y review surfaces the gap. The hook is `Modifier.clickable(onClick = onToggle, onClickLabel = if (expanded) "Collapse" else "Expand", role = Role.Button)`.
- **`Role.Tool` invariant is enforced upstream, not here.** `ToolCallRow` consumes `ToolCall` directly; the null-unwrap of `Message.toolCall` happens in `MessageBubble`'s `Role.Tool` arm. If a future ticket changes the data-class invariant (e.g. allows `toolCall = null` with `Role.Tool` for placeholder rows), `MessageBubble` is the seam to update — `ToolCallRow` is total over all `ToolCall` values its constructor can produce.
- **RTL.** `Arrangement.spacedBy` and `Alignment.CenterVertically` respect `LayoutDirection` automatically; the icon flips to the trailing edge in RTL locales without explicit handling.
- **No instrumented tests.** Codebase has no Compose UI test infrastructure — same precedent as [#126](../codebase/126.md) / [#128](../codebase/128.md) / [#129](../codebase/129.md) / [#130](../codebase/130.md) / [#184](../codebase/184.md). Visual verification by the two `@Preview`s + `./gradlew assembleDebug` + `./gradlew lint` passing.

## Related

- Ticket notes: [`../codebase/131.md`](../codebase/131.md)
- Spec: `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`
- Upstream:
  - [Data model](./data-model.md) — `ToolCall(toolName, input, output)` data class and the `Message.toolCall` non-null-iff-`Role.Tool` invariant
  - [`MessageBubble`](./message-bubble.md) — `Role.Tool` arm routes here via `?.let`
  - [`MarkdownText`](./markdown-text.md) — `internal CodeBlock(content, language)` reused for code-ish output (widened from `private` to `internal` in #131)
  - [Conversation repository](./conversation-repository.md) — `FakeConversationRepository` seeds one `Role.Tool` fixture today (a `Read` on `ThreadScreen.kt`)
  - [Thread screen](./thread-screen.md) — the eventual `LazyColumn(reverseLayout = true)` host
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants, preview-pairing shape, stateful-wrapper + stateless-content split for previewability from #184)
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — tool-call card at `16:28`–`16:31`. Three deliberate deviations: background (`surfaceContainerHigh` no border, vs Figma `surface-container` + outline), leading icon added per AC5, expand affordance added per AC1. Two-tone tool-name-in-`tertiary` + arg-in-`onSurfaceVariant` split preserved verbatim from Figma.
- Downstream:
  - #191 — structured tool-message payload. Replaces `ToolCall.input: String` with a richer typed shape; `primaryArg` and `iconForTool` gain real branching at that point. Public `ToolCallRow(toolCall, modifier)` signature stays unchanged.
  - Open: language inference from path extension (`Read`/`Edit` output sections piping into [#130](../codebase/130.md)'s syntax highlighter).
  - Open: `AnimatedVisibility` wrapper around `ExpandedBody`.
  - Open: TalkBack a11y semantics on the row's `Modifier.clickable`.
