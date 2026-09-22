# ToolCallRow

Stateless row primitive (#131) rendering a single `ToolCall` payload in the conversation thread surface. Two states — **collapsed** (default) and **expanded** — toggled by tapping anywhere on the row's `surfaceContainerHigh` tile. Collapsed view shows a **status-aware** leading slot plus a two-tone `ToolName · primary-arg` summary, single-line with ellipsis truncation. The leading slot is a pure function of [`ToolCall.status`](./data-model.md) (#388): an indeterminate spinner while `Running`, the per-tool icon when `Done`, an error-tinted glyph when `Failed`. Expanded view appends an "Input" / "Output" pair under the collapsed header — **the "Output" section is gated on resolution** (hidden while `Running`, since the data layer fills `output` only on the correlated `tool_result`); code-ish output (multi-line or > 80 chars) renders through [`MarkdownText`](./markdown-text.md)'s internal `CodeBlock` composable — since #657, the design's bordered header/body chrome rather than #130's flat tile, though `language = null` here means the header never renders — with no copy control (`copyable` defaults to `false` at this call site; whether tool output becomes copyable is a separate, not-yet-built decision), plain output renders as inline `Monospace` text on the parent surface. Toggle state survives configuration changes via `rememberSaveable`; no `ViewModel` coupling. Routed from [`MessageBubble`](./message-bubble.md)'s `Role.Tool` arm via a null-safe `?.let`.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ToolCallRow.kt`. Sibling of [`MessageBubble`](./message-bubble.md), [`MarkdownText`](./markdown-text.md), [`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Consumes a [`ToolCall(toolName, input, output)`](./data-model.md) instance — the payload unwrapped from `Message.toolCall` by `MessageBubble`'s `Role.Tool` arm — and renders it as a left-aligned, full-width rounded card with a single `M3 ripple` click target spanning the whole tile.

**Collapsed (default).** A 36dp-tall row with a 18dp leading **status slot** (spinner / per-tool icon / error glyph per [`ToolCall.status`](./data-model.md) — see [Status affordance](#status-affordance-388)) followed by an `AnnotatedString` of the form `"$toolName · $primaryArg"` rendered as `MaterialTheme.typography.bodyMedium` with `maxLines = 1` + `TextOverflow.Ellipsis`. The tool-name span uses `MaterialTheme.colorScheme.tertiary` + `FontFamily.Monospace` (matches Figma's `Roboto Mono Regular`); the separator and primary-arg span use `MaterialTheme.colorScheme.onSurfaceVariant` proportional (matches Figma's `body-small`). The summary renders for **all** statuses — only the leading slot varies — so a running call already shows tool name + input.

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
├── ToolCallStatusIcon(toolCall)            // status-aware leading slot (#388, see below)
└── Text(
        text = buildSummaryAnnotated(toolCall),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
```

`Modifier.weight(1f)` on the `Text` lets it absorb the remaining horizontal space so `maxLines = 1` + `TextOverflow.Ellipsis` clip the primary-arg when it would wrap (AC4 from the issue).

### Status affordance (#388)

The leading element is a private `ToolCallStatusIcon(toolCall)` — a **pure function of `toolCall.status`** ([`ToolCallStatus`](./data-model.md)), branching one exhaustive `when` (no `else`) into a **fixed `ToolCallIconSize = 18.dp` footprint** in all three states so a row resolving `Running → Done/Failed` never reflows:

```kotlin
@Composable
private fun ToolCallStatusIcon(toolCall: ToolCall) {
    when (toolCall.status) {
        ToolCallStatus.Running ->          // indeterminate spinner = "in progress"
            CircularProgressIndicator(
                modifier = Modifier.size(ToolCallIconSize)
                    .semantics { contentDescription = stringResource(R.string.cd_tool_running) },
                strokeWidth = ToolCallSpinnerStrokeWidth,   // 2.dp, mirrors ThinkingIndicator
            )
        ToolCallStatus.Done ->             // unchanged from #131 — the settled Figma design
            Icon(iconForTool(toolCall.toolName), contentDescription = null,
                 modifier = Modifier.size(ToolCallIconSize),
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        ToolCallStatus.Failed ->           // error-tinted glyph in the same slot
            Icon(Icons.Outlined.ErrorOutline,
                 contentDescription = stringResource(R.string.cd_tool_failed),
                 modifier = Modifier.size(ToolCallIconSize),
                 tint = MaterialTheme.colorScheme.error)
    }
}
```

The `Done` arm is the existing per-tool icon **moved verbatim** — the Done row is byte-identical to the shipped design. The status drives nothing else: it is read straight off `toolCall.status` (which [#387](../codebase/387.md) populates live — `Running` on `tool_use`, `Done`/`Failed` on the correlated `tool_result`; see [Live tool-call](./live-tool-call.md)), never derived from `output.isEmpty()` or any other field, and there is **no `remember`/`derivedStateOf` over it** (statelessness, AC#3 — the only local state remains the orthogonal `expanded` toggle). Because `ToolCall` is a stable `data class`, when #387 flips the status the data layer emits a **new** `ToolCall` value → the slot recomposes and the spinner is replaced by the resolved glyph with no reflow; the `CircularProgressIndicator` owns its own animation (no `LaunchedEffect`, no app-managed animation state).

**Why the leading slot, not a trailing badge:** it's the one decorative slot already in the row, so a swap there adds zero layout and keeps the `Done` state pixel-identical to the shipped Figma design — the most elegant home for a three-state affordance and the reason "Done already matches" stays trivially true.

**Design-owed.** The running spinner and failed glyph are **not** drawn in Figma `16-28` (flagged for Juhana in the ticket); they follow the app's M3 progress idiom (mirroring [`ThinkingIndicator`](./thinking-indicator.md)) until a frame lands. When the two states are drawn, only the `Running`/`Failed` arms need revisiting — the `Done` row and all behaviour are final. A stronger failed treatment (tinted border/surface) or an explicit success tick on `Done` would be localized changes to the same `when` arm; flagged, not built.

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
├── ExpandedSection(label = "Input",  content = toolCall.input)             // always
└── if (toolCall.status != ToolCallStatus.Running)                          // #388: on resolution
        ExpandedSection(label = "Output", content = toolCall.output)
```

**The `Output` section is gated on `status != Running` (#388).** While running, `output` is `""` (the data layer fills it on the correlated `tool_result`, [#387](../codebase/387.md)), so gating it (a) avoids an empty "Output" label mid-run and (b) makes "on resolution it reveals the output" (AC#2) literally true. A `Failed` row's `output` is the error/result summary #387 placed there, so Failed surfaces it under the **same** `Output` section — no separate error branch in the UI. The gate is on the enum, **not** on `output.isEmpty()`.

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

**No copy control on either section, by construction rather than by omission.** `CodeBlock`'s `copyable` parameter (#657) defaults to `false`, and this call site never passes it — so a code-ish "Output" section takes the restyled bordered chrome for free but renders no `cd_thread_copy_code` control. `ToolCallRowTest.code_ish_output_renders_the_code_block_without_a_copy_control` asserts the negative directly (`onNodeWithContentDescription(cd_thread_copy_code).assertDoesNotExist()`), alongside `onNodeWithTag(CODE_BLOCK_HEADER_TAG).assertDoesNotExist()` — this call site's `language` is always `null`, so the header never renders either. Whether tool output *should* become copyable is explicitly left open (#658's call), not decided by this component.

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
- **Two strings (#388):** `cd_tool_running` = "Tool call running" and `cd_tool_failed` = "Tool call failed" — content descriptions for the spinner / error glyph, following the `cd_*` convention (mirrors `cd_thread_thinking`). The `"Input"` / `"Output"` section labels remain inlined English-only Phase-0 copy; move to `strings.xml` when localisation lands.
- **No theme overrides.** Reads `colorScheme.surfaceContainerHigh`, `.tertiary`, `.onSurfaceVariant`, `.onSurface`, `.error` (#388, failed glyph tint), `typography.bodyMedium`, `.labelSmall` directly; the spinner uses the default primary token. All M3 defaults, no hardcoded hex.
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

Both delegate to a shared `ToolCallRowPreviewMatrix()` that renders 10 cells in a vertical `Column` inside `Surface { … }`:

| Row | Fixture | Status | State |
|---|---|---|---|
| 1–2 | `PreviewReadToolCall` | Done | collapsed / expanded |
| 3–4 | `PreviewEditToolCall` | Done | collapsed / expanded |
| 5–6 | `PreviewBashToolCall` | Done | collapsed / expanded |
| 7–8 | `PreviewRunningToolCall` (#388) | Running | collapsed / expanded |
| 9–10 | `PreviewFailedToolCall` (#388) | Failed | collapsed / expanded |

Total: **running + done + failed × collapsed/expanded × 2 themes**, across the existing 2 preview functions — so the Light + Dark `@Preview` pair cover each of the three statuses (AC#4; the original AC7 done-state coverage is unchanged). The expanded `Running` cell proves Output is hidden mid-run; the expanded `Failed` cell proves the error surfaces under Output with the error-tinted leading glyph.

The matrix invokes `ToolCallRowContent` directly (not `ToolCallRow`) with pinned `expanded` values — driving the stateless inner skips the `rememberSaveable` toggle, so the preview renders both states deterministically without needing two separate composables or an interaction harness.

**Preview fixtures** are file-private `val`s at the bottom of `ToolCallRow.kt` — not added to [`FakeConversationRepository`](./conversation-repository.md):

- `PreviewReadToolCall` — `toolName = "Read"`, a long `MessageBubble.kt` path that fires the ellipsis in the collapsed cell, a multi-line Kotlin-source `output` that fires the `CodeBlock` path. (`status` defaults to `Done`.)
- `PreviewEditToolCall` — `toolName = "Edit"`, a `Theme.kt` path, a diff-ish `output` (`@@ -42,3 +42,3 @@\n-val Foo = 1\n+val Foo = 2`) to exercise the multi-line `Edit` path.
- `PreviewBashToolCall` — `toolName = "Bash"`, `input = "git status"` (short, plain), a multi-line stdout `output` (`CodeBlock` path).
- `PreviewRunningToolCall` (#388) — `status = Running`, `output = ""` (`toolName = "Bash"`, a long-running input) — shows the spinner; expanded proves Output is gated out while running.
- `PreviewFailedToolCall` (#388) — `status = Failed`, an error-string `output` — shows the error glyph; expanded proves the error surfaces under Output.

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
- **`ToolCall.status` is rendered (#388).** [#387](../codebase/387.md) added a
  `status: ToolCallStatus = ToolCallStatus.Done` field to `ToolCall` and drives it **live** in the data
  layer (a `tool_use` opens a `Running` row, the correlated `tool_result` flips it to `Done`/`Failed` — see
  [Live tool-call](./live-tool-call.md)). [#388](../codebase/388.md) surfaces it: the leading slot is now a
  function of `status` (spinner / per-tool icon / error glyph) and the expanded `Output` section appears
  only on resolution — see [Status affordance](#status-affordance-388). The running/failed glyphs are
  design-owed (not in Figma `16-28`); the `Done` row is unchanged.
- **`Role.Tool` invariant is enforced upstream, not here.** `ToolCallRow` consumes `ToolCall` directly; the null-unwrap of `Message.toolCall` happens in `MessageBubble`'s `Role.Tool` arm. If a future ticket changes the data-class invariant (e.g. allows `toolCall = null` with `Role.Tool` for placeholder rows), `MessageBubble` is the seam to update — `ToolCallRow` is total over all `ToolCall` values its constructor can produce.
- **RTL.** `Arrangement.spacedBy` and `Alignment.CenterVertically` respect `LayoutDirection` automatically; the icon flips to the trailing edge in RTL locales without explicit handling.
- **Instrumented tests added in #388, extended in #657.** #131 shipped preview-verified with no test; [#388](../codebase/388.md) added a `ToolCallRowTest` (`androidTest`, 5 scenarios: running/failed/done status nodes + output gating) mirroring `ThinkingIndicatorTest`. #657 added a sixth scenario asserting the negative for this component specifically: code-ish output renders (no header, since `language` is always `null` here) but no `cd_thread_copy_code` node exists. It **compiles** (`assembleDebugAndroidTest`) but `connectedAndroidTest` is **not run** without a device — same posture as [#398](../codebase/398.md) / [#407](../codebase/407.md). Primary verification remains the `@Preview` matrix + `./gradlew assembleDebug` + `./gradlew lint`.

## Related

- Ticket notes: [`../codebase/131.md`](../codebase/131.md)
- Spec: `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`
- Upstream:
  - [Data model](./data-model.md) — `ToolCall(toolName, input, output)` data class and the `Message.toolCall` non-null-iff-`Role.Tool` invariant
  - [`MessageBubble`](./message-bubble.md) — `Role.Tool` arm routes here via `?.let`
  - [`MarkdownText`](./markdown-text.md) — `internal CodeBlock(content, language, copyable = false)` reused for code-ish output (widened from `private` to `internal` in #131; gained the design's bordered header/body chrome and the opt-in `copyable` parameter in #657, left at its default here)
  - [Conversation repository](./conversation-repository.md) — `FakeConversationRepository` seeds one `Role.Tool` fixture today (a `Read` on `ThreadScreen.kt`)
  - [Thread screen](./thread-screen.md) — the eventual `LazyColumn(reverseLayout = true)` host
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants, preview-pairing shape, stateful-wrapper + stateless-content split for previewability from #184)
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — tool-call card at `16:28`–`16:31`. Three deliberate deviations: background (`surfaceContainerHigh` no border, vs Figma `surface-container` + outline), leading icon added per AC5, expand affordance added per AC1. Two-tone tool-name-in-`tertiary` + arg-in-`onSurfaceVariant` split preserved verbatim from Figma.
- Downstream:
  - #191 — structured tool-message payload. Replaces `ToolCall.input: String` with a richer typed shape; `primaryArg` and `iconForTool` gain real branching at that point. Public `ToolCallRow(toolCall, modifier)` signature stays unchanged.
  - [#387](../codebase/387.md) / [Live tool-call](./live-tool-call.md) — added `ToolCall.status` and the live correlation that drives it (data layer).
  - [#388](../codebase/388.md) (**shipped**) — extends this component to render the `Running`/`Done`/`Failed` affordance (status-aware leading slot + resolution-gated `Output`); running/failed glyphs are design-owed.
  - Open: language inference from path extension (`Read`/`Edit` output sections piping into [#130](../codebase/130.md)'s syntax highlighter).
  - Open: `AnimatedVisibility` wrapper around `ExpandedBody`.
  - Open: TalkBack a11y semantics on the row's `Modifier.clickable`.
