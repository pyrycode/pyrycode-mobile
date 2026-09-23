# ToolCallRow

Stateless row primitive rendering a single `ToolCall` payload in the conversation thread surface.
Two states — **collapsed** (default) and **expanded** — toggled by tapping anywhere on the row.
Restyled to the mobile tool-use design in #895 (Figma `134:4939` collapsed / `134:4941` expanded):
a bordered outline card (no fill tint), the verbatim tool name, a **subject** picked from the tool's
own input fields the way desktop picks its headline, a trailing **status** slot distinguishable
without colour, and a chevron. Expanded, it shows the input, the output once resolved, and — on a
denied call — claude's reason. Routed from [`MessageBubble`](./message-bubble.md)'s `Role.Tool` arm
via a null-safe `?.let`.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`).
Files: `ToolCallRow.kt` (the composable) and `ToolRowFormat.kt` (the pure, Compose-free text rules —
`formatToolElapsed`, `toolRowSubject`, `shortenToolPath` — split out so the thread's composer-status
area can reuse `formatToolElapsed` without importing Compose). Sibling of
[`MessageBubble`](./message-bubble.md), [`MarkdownText`](./markdown-text.md),
[`ConversationRow`](./conversation-row.md), `ArchiveRow.kt`.

## What it does

Consumes a [`ToolCall`](./data-model.md) instance — the payload unwrapped from `Message.toolCall` by
`MessageBubble`'s `Role.Tool` arm — and renders it as a full-width, 6dp-rounded outline card with a
single `M3` click target spanning the whole tile.

**Collapsed (default).** One line: the verbatim `toolName` (monospace, `tertiary`, capped at 160dp so
a long `mcp__server__tool` name cannot starve the rest of the row), then the **subject** (see
[Subject and elapsed text](#subject-and-elapsed-text)) taking the remaining weighted width with
ellipsis, then the **trailing status** (see [Trailing status](#trailing-status)), then a chevron. Name
and subject are two separate `Text` nodes — not one `AnnotatedString` — because the scripted-scenario
matchers and `ScriptedToolRowTest` need the tool name to appear in exactly one text node per row.

**Expanded.** Below the header, a height-bounded (`heightIn(max = 320.dp)`), internally scrolling
`Column` of sections — Input always, Output once resolved, Denial on a denied call with a denial — see
[Expanded body](#expanded-body).

Every string on the row — tool name, input field values, the `input` précis, output, `denial.message`,
`denial.decisionReason` — is claude's or the daemon's and renders only as inert `Text` or `CodeBlock`:
never a link, a path to open, markup, or a log line.

## Shape

```kotlin
@Composable
fun ToolCallRow(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
)
```

Unchanged across the #895 restyle: single `ToolCall` parameter (not the wrapping `Message`), `modifier`
defaulted, no `onClick` or `expanded` parameter — tap-to-toggle is internal via `rememberSaveable`. The
public surface stays minimal so #895 could restyle the whole render tree without touching call sites.

## How it works

### Stateful wrapper + stateless content split

```kotlin
@Composable
fun ToolCallRow(toolCall: ToolCall, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToolCallRowContent(toolCall = toolCall, expanded = expanded, onToggle = { expanded = !expanded }, modifier = modifier)
}
```

`rememberSaveable` serialises the `Boolean`, so rotation, dark-mode toggle and process death +
restore all preserve `expanded`. **No `key` parameter** — the default call-site identity suffices
because the `LazyColumn` item slot keys items on `Message.id`. A `ToolCall` that arrives updated in
place (a status flip, a new `elapsedSeconds` reading) is a **new value at the same call site**, so
`expanded` survives the update — this is the property #895's elapsed-reading and status-flip live
updates depend on, and `ToolCallRowTest.a_row_stays_expanded_while_it_is_updated_in_place` pins it.

**Lifetime tied to `LazyColumn` item disposal**, as before: scrolling a row off-screen and back resets
`expanded` to `false`. Unchanged Phase-0 trade-off; the fix if it becomes a problem is hoisting
`expanded` into the `ViewModel` keyed on `Message.id`, not a different `remember*` variant.

### Container and header

```kotlin
Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
)
└── Column(Modifier.clickable(onClickLabel = expand/collapse string, role = Role.Button, onClick = onToggle)
        .padding(horizontal = 12.dp, top = 8.dp, bottom = if (expanded) 12.dp else 8.dp))
    ├── HeaderRow(toolCall, expanded)
    └── if (expanded) ExpandedBody(toolCall)
```

`background` fill + 1dp `primaryContainer` border replaces the pre-#895 `surfaceContainerHigh` filled
tile — the card now reads as an outline, not a tonal surface. The click target carries
`onClickLabel` (the localized "Show/Hide tool details" string) and `Role.Button`, which the pre-#895
row did not — a TalkBack user now hears the row is a toggle, not just gets a bare click.

`HeaderRow` is a `Row` whose **trailing status and chevron are unweighted and the name-plus-subject
group is `weight(1f)`**: because a `Row` measures unweighted children first, the status and chevron
always get their width and the group only gets what is left — this is what makes the subject
ellipsize instead of pushing the status off-row when it is long.

### Subject and elapsed text

Both rules live in `ToolRowFormat.kt`, mirrored from desktop's `toolHeadline.ts` / `shortenPath.ts` /
`ConversationScreen.tsx`'s `formatToolElapsed`, and are pure functions with no Compose import so a
future ticket's composer status area can call `formatToolElapsed` directly:

```kotlin
internal fun toolRowSubject(toolName: String, inputFields: Map<String, String>, input: String): String
internal fun shortenToolPath(path: String): String
internal fun formatToolElapsed(seconds: Int): String
```

**`toolRowSubject`.** For `toolName == "Bash"` (exact match — `BashOutput` does not qualify), the first
non-empty of `description`, `command`; otherwise the first non-empty of `TOOL_SUBJECT_FIELDS =
file_path, path, notebook_path, command, pattern, url, query, description`; otherwise the `input`
précis verbatim. **"Non-empty" is `value != ""`, not `isNotBlank()`** — a whitespace-only field wins
over a lower-priority field, matching desktop's decision, not a Kotlin-idiomatic default. A field
picked from `TOOL_PATH_FIELDS = file_path, path, notebook_path` is shortened through
`shortenToolPath`; nothing else is. A row restored from the disk cache has an empty `inputFields` map
(that field is not persisted) and always falls back to the `input` précis — this is the fallback path's
production trigger, not just a defensive default. Desktop's third rule (fall back to any single-line
field before the précis) is **not** ported; this ticket's contract stops at the four listed above.

**`shortenToolPath`.** Splits on `/`, drops empty segments (so a leading `/` does not count as one);
`≤ 4` segments left unchanged, otherwise `.../` + the last four joined by `/`. The shortened string is
display text — nothing feeds it back into a path or URL operation.

**`formatToolElapsed`.** claude's `tool_progress` reading: `|s| < 60` → `"${sign}${|s|}s"`, otherwise
`"${sign}${|s|/60}m ${pad2(|s|%60)}s"` (`65` → `"1m 05s"`). The absolute value is taken in `Long`
before formatting so `Int.MIN_VALUE` cannot overflow on negation. A negative reading keeps its sign
(`-65` → `"-1m 05s"`) — it is an upstream value to render, not an error to guard against.

### Trailing status

An exhaustive `when (toolCall.status)`, each arm with its **own** glyph and content description, so
`Running`/`Done`/`Failed`/`Denied` are distinguishable without colour:

| Status | Glyph | Tint | Content description |
|---|---|---|---|
| `Running` | `CircularProgressIndicator` (14dp) | default | `cd_tool_running` (kept from #388) |
| `Done` | `Icons.Outlined.Check` | `onSurfaceVariant` | `cd_tool_done` (new) |
| `Failed` | `Icons.Outlined.ErrorOutline` | `error` | `cd_tool_failed` (kept from #388) |
| `Denied` | `Icons.Outlined.Block` | `error` | `cd_tool_denied` (new) |

`cd_tool_running` and `cd_tool_failed` are **kept verbatim** because the scripted `tool` /
`tool-failed` scenarios and `ScriptedToolRowTest` match on them. Before #895, `Denied` reused the
`Failed` glyph and description as a stop-gap (#811) — #895 (split from #658, which owned this
obligation) gives it its own arm; a denied row and a failed row are no longer visually or
semantically identical.

**Elapsed is read only in the `Running` arm**, and only rendered when `toolCall.elapsedSeconds != null`
— a `Done`/`Failed`/`Denied` row never shows a time even if it carries a stale `elapsedSeconds` value
from before it resolved (`ToolCallRowTest.a_resolved_row_shows_no_time_even_with_a_stale_reading`
pins this). The row runs **no local timer**; every reading is a value already on the `ToolCall`
the data layer handed it. The `Text` carries `Modifier.testTag(TOOL_ELAPSED_TAG)` so a test can assert
its absence without matching against a specific formatted string.

Figma's collapsed instance also shows a result count ("184 lines"); that slot is **not drawn** on
mobile — the wire's `tool_result` carries only `result_summary`, with no count, and nothing derives
one from the output text.

### Expanded body

```kotlin
Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), spacedBy(12.dp))
├── ExpandedSection("Input")   { one entry per inputFields key, else the input précis }
├── ExpandedSection("Output")  { only when status is Done or Failed }
└── ExpandedSection("Denied by claude") { only when status == Denied and denial != null }
```

The body is **height-bounded with internal scroll** rather than growing the thread row without limit
— new in #895; the pre-restyle row had no bound. `ExpandedSection` is a `labelSmall` caption
(`tool_row_input` / `tool_row_output` / `tool_row_denial` string resources, replacing the pre-#895
English-only inline literals) over its content.

**Input.** When `inputFields` is non-empty, one `InputField(key, value)` per entry **in map order**
— the key as a monospace `labelSmall` caption over its value. Falls back to the single `input` précis
when the map is empty, which is always true for a cache-restored row.

**Output.** Gated on `status == Done || status == Failed`, unchanged in spirit from #388's
resolution gate — a `Running` row has no output yet (the data layer fills it on the correlated
`tool_result`) and a `Denied` row's reason belongs in the Denied section instead, not Output.

**Denied.** Gated on `status == Denied && denial != null`. `denial.message` renders, then
`denial.decisionReason` when it is non-empty. **A cache-restored `Denied` row has no `denial`**
(that field is not persisted — see [Live tool-call § Denied](./live-tool-call.md#denied-811)) and
shows no Denied section at all, not an empty one.

**Content rendering (`ToolContent`).** Code-like content — contains `\n`, longer than 80 chars, or is
the `command` input field specifically — takes `CodeBlock(content, language = null, textStyle =
bodySmall)`; anything else wraps as `bodySmall` `FontFamily.Monospace` `Text` in `onBackground`
(Figma's 12sp mono prose; #895 lowered this from `bodyMedium` — see
[Configuration](#configuration)). The `command`-field special case exists so a `Bash` call's command
always gets code-block chrome even when it is a single short line that the length/newline heuristic
alone would not catch.

### File-private spacing constants

Renamed and re-tuned to the #895 design (`ToolCallCornerRadius` 12dp → 6dp, new
`ToolCallBorderWidth`, `ToolCallStatusIconSize`/`ToolCallSpinnerSize`/`ToolCallChevronSize` replacing
the single `ToolCallIconSize`, new `ToolNameMaxWidth` and `ToolCallExpandedMaxHeight`). Every value is
still a `dp` literal at the declaration site only — no `.dp` literal at any call site.
`MessageRowVerticalSpacing = 12.dp` is still redeclared here rather than imported from
`MessageBubble.kt`, same rule-of-three deferral as before #895.

## Configuration

- **Strings (#895):** `cd_tool_done`, `cd_tool_denied`, `tool_row_input`, `tool_row_output`,
  `tool_row_denial`, `tool_row_expand`, `tool_row_collapse` — added to `strings.xml`, replacing the
  inline `"Input"` / `"Output"` literals from #131. `cd_tool_running` and `cd_tool_failed` are #388's
  originals, unchanged.
- **`CodeBlock` gained an additive `textStyle` parameter** ([`MarkdownText.kt`](./markdown-text.md)):
  `textStyle: TextStyle = MaterialTheme.typography.bodyMedium`, and the block's body `Text` now uses
  `textStyle.copy(fontFamily = FontFamily.Monospace)` instead of a hardwired `bodyMedium`. Markdown's
  own callers are unaffected by the default; `ToolCallRow` passes `bodySmall` (12sp) to match this
  design. Any future caller needing a different code-block text size reuses this parameter rather than
  duplicating `CodeBlock`.
- **`material-icons-extended`** (added in #131) is unaffected by #895: `iconForTool`, the per-tool
  leading icon, is removed entirely (no icon replaces it), but `Icons.Outlined.Check` / `.Block` /
  `.ErrorOutline` and `Icons.Filled.KeyboardArrowDown` / `.KeyboardArrowUp` are already on the
  classpath via other consumers (`TurnOutcomeIndicator`, `ConversationTreeRows`,
  `ThreadComposerFooter`), so #895 needed no dependency change.
- **No `ViewModel` wiring, no DI changes.** Pure leaf composable.

## Routing from `MessageBubble`

Unchanged by #895:

```kotlin
Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier.padding(horizontal = MessageContentGutter)) }
```

The `?.let` absorbs the [`Message`](./data-model.md) invariant (`toolCall` non-null iff
`role == Role.Tool`) silently — a data-layer bug producing `Role.Tool` with `toolCall = null` renders
nothing, no `!!`, no crash. `MessageBubble` applies the thread's content gutter at this call site;
\#895's architect spec explicitly left this arm untouched and the row's own layout is the whole scope
of the restyle.

## Previews

Two `@Preview` functions (`ToolCallRowLightPreview`, `ToolCallRowDarkPreview`, both `widthDp = 412`)
delegate to a shared `ToolCallRowPreviewMatrix()`: five fixtures collapsed
(`PreviewReadToolCall` Done, `PreviewBashToolCall` Done, `PreviewRunningToolCall` Running with an
elapsed reading, `PreviewFailedToolCall` Failed, `PreviewDeniedToolCall` Denied with a denial), then
three of those (`Read`, `Running`, `Denied`) again expanded — covering every status, the elapsed
text, `inputFields`-derived subjects, and both the Output and Denied expanded sections. As before
\#895, the matrix drives `ToolCallRowContent` directly with pinned `expanded` values rather than
`ToolCallRow`, so previews render both states deterministically with no saved-state harness. Preview
fixtures stay file-private `val`s here, not added to `FakeConversationRepository` — changing the
rendering doesn't ripple into the data-layer fake.

## Testing

- **`ToolRowFormatTest`** (`app/src/test/.../components/`, plain JUnit, no Compose) — pins
  `formatToolElapsed` at `0, 12, 59, 60, 65, -65` (plus `-5`, `3600`); `toolRowSubject`'s preferred
  order, the `Bash` override and its own fallback to `command`, empty-value skipping, path shortening
  scoped to path fields only, and précis fallback for an empty/unknown-keys map; `shortenToolPath` at
  the 4-segment boundary and with a leading slash.
- **`ToolCallRowTest`** lives in `app/src/sharedTest`, not `app/src/androidTest` — see
  [development-verification § where a screen test goes](./development-verification.md#where-a-screen-test-goes).
  Covers each status's own content description (and that `Denied` does *not* also show
  `cd_tool_failed`), the elapsed text at a running reading vs. no reading vs. a stale reading on a
  resolved row, the subject sourced from `inputFields`, the Denied section's message, and the
  expanded-stays-expanded-through-an-in-place-update case.
- **Compose testing gotcha (#895 lesson):** `onNodeWithTag(TOOL_ELAPSED_TAG)` *finds* the elapsed
  `Text` node fine even though it sits inside the row's `clickable` `Column` (a merge-descendants
  semantics node), but `.assertIsDisplayed()` on that lookup fails with "not displayed" although the
  layout is correct. The fix is `onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true)`. A
  text-based lookup against the same node (`onNodeWithText("4s")`) does **not** need it and would stay
  green even if the child were actually clipped, because it matches against the merged parent row —
  so a tag-scoped display assertion inside any `clickable`/merged row in this codebase should default
  to `useUnmergedTree = true`, not just this component.
- **E2E:** no new scenario for #895. The rung-3 `interactiveTurn_toolPrompt_rendersToolStepInThread`
  (#481) and the scripted `tool` / `tool-failed` scenarios keep matching the verbatim `Bash` name (now
  its own text node, unchanged by the restyle) and the kept `cd_tool_running` / `cd_tool_failed`
  descriptions. The elapsed reading depends on claude's transient heartbeats, which a durable scenario
  cannot pin, so only the unit and component tests cover its render.

## Edge cases / limitations

- **Toggle state is lost on `LazyColumn` item disposal** — unchanged Phase-0 trade-off; see
  [Stateful wrapper + stateless content split](#stateful-wrapper--stateless-content-split).
- **Empty `input` / empty `toolName`** still render as empty text at that slot rather than crashing;
  not special-cased, same posture as before #895.
- **No result count.** Figma shows one; the wire has none to show. Not derived from output length.
- **No expand/collapse animation.** The conditional `if (expanded) ExpandedBody(toolCall)` still
  adds/removes the subtree directly; a future ticket could wrap it in `AnimatedVisibility` without
  touching the public API.
- **RTL.** `Arrangement.spacedBy` / `Alignment.CenterVertically` respect `LayoutDirection`
  automatically.
- **No language inference from path extension** for the Input/Output code blocks — `language` is
  always `null`. Open since before #895; a future ticket could add a path-extension → language helper
  for `Read`/`Edit` fields specifically.
- **Subagent nesting and the composer's open-tool status indicator are separate tickets**, not built
  here — #895's scope is this row's own render. The composer indicator is the reason
  `formatToolElapsed` lives in its own Compose-free file rather than as a private function in
  `ToolCallRow.kt`.
- **Bidi-override characters in a subject are unguarded** (security review, SHOULD NOT FIX, no
  observed failure) — a bidi-override in a subject field could visually reorder that one text node,
  but name, subject and trailing status are separate nodes so it cannot reorder the status glyph or
  its content description. Desktop leaves the same case unguarded.

## Related

- Ticket notes: [`../codebase/131.md`](../codebase/131.md) (original component),
  [`../codebase/388.md`](../codebase/388.md) (status affordance, superseded by #895's per-state
  glyphs), [`../codebase/387.md`](../codebase/387.md) (live status/correlation data layer)
- Specs: `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`,
  `docs/specs/architecture/388-tool-call-row-status-affordance.md`,
  `docs/specs/architecture/895-tool-row-restyle.md`
- Upstream:
  - [Data model](./data-model.md) — `ToolCall(toolName, input, inputFields, output, status,
    denial, elapsedSeconds, parentToolUseId)` and the `Message.toolCall` non-null-iff-`Role.Tool`
    invariant
  - [Live tool-call](./live-tool-call.md) — the data layer that produces `status`, `inputFields`,
    `denial` and `elapsedSeconds` on the same `ToolCall`; `Denied`'s "no `denial` on cache restore"
    rule and the `tool_progress`/`tool_denied` fold rules live there, not here
  - [`MessageBubble`](./message-bubble.md) — `Role.Tool` arm routes here via `?.let`, applies the
    content gutter
  - [`MarkdownText`](./markdown-text.md) — `internal CodeBlock(content, language, copyable = false,
    textStyle = bodyMedium)` reused for code-ish input/output; `textStyle` added by #895, `copyable`
    stays at its `false` default here (whether tool content becomes copyable is still open, #658's call)
  - [development-verification](./development-verification.md) — component-test placement
    (`sharedTest` vs. `androidTest`) and the managed-device gate this row's tests run under
- Downstream / still open:
  - Subagent nesting under a parent tool row (split from #658, not #895)
  - Naming the open tool in the composer's status area, reusing `formatToolElapsed` from
    `ToolRowFormat.kt` (split from #658, not #895)
  - Language inference from path extension for `Read`/`Edit` code blocks
  - `AnimatedVisibility` around the expanded body
