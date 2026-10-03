# ToolCallRow

Row primitive rendering a single `ToolCall` payload in the conversation thread surface.
Two states — **collapsed** (default) and **expanded** — toggled by tapping anywhere on the row.
The dark mobile variants use a bordered outline card: only `Bash` with a description selects the
described header and chevron; every other row, including `Bash` with just a command, draws the
simple header (see [Collapsed](#collapsed) below). Both variants retain a status slot
distinguishable without colour. Expanded, the row
shows supplied input, resolved output and, for a denied call, claude's reason. It is routed from
[`MessageBubble`](./message-bubble.md)'s `Role.Tool` arm via a null-safe `?.let`.

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

**Collapsed (default).** `toolHeadline` (`ToolRowFormat.kt`, #1315) picks the header, testing the
tool name before any field: only `Bash` (exact match — `BashOutput` does not qualify) with a
nonempty `inputFields["description"]` takes the described header — the description verbatim as a
body-medium string with a small right chevron. `Bash` with a nonempty `command` and no description
instead takes the simple header with the command as its monospace lead and no subject, so a shell
command occupies the whole row. Every other case — a non-`Bash` call (including `Agent`/`Task` with
a description), `BashOutput`, or a `Bash` call with neither field — takes the simple header with
`toolName` as the lead and the [subject](#subject-and-elapsed-text), if nonempty, in body-medium
text; it has no chevron. **The 160dp lead cap (`ToolNameMaxWidth`) applies only when a subject
follows the lead** — it exists to leave room for the subject, so a lone command or tool name with no
subject ellipsizes at the row's own available width instead. Both variants keep the
[trailing status](#trailing-status). The text ellipsizes within compact width while status stays
visible. The whole row remains the accessible expand/collapse target, including the simple variant
without a visible chevron.

**Expanded.** The described chevron points down. Below the header, a height-bounded
(`heightIn(max = 320.dp)`), internally scrolling `Column` shows only nonempty supplied sections — see
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
    subagentDepth: Int = 0,
    joinsNextToolRow: Boolean = false,
)
```

Unchanged across the #895 restyle: single `ToolCall` parameter (not the wrapping `Message`), `modifier`
defaulted, no `onClick` or `expanded` parameter — tap-to-toggle is internal via `rememberSaveable`. The
public surface stays minimal so #895 could restyle the whole render tree without touching call sites.

**`subagentDepth` (since #896)**, defaulted to `0` so every pre-#896 call site is unaffected — see
[Subagent step description](#subagent-step-description-since-896) below. `ToolCallRow` does not read its
own indent from this value; the caller ([`MessageBubble`](./message-bubble.md)) applies the indent to the
`modifier` it passes in, and `subagentDepth` only drives the row's own accessible description.

## How it works

### Stateful wrapper + stateless content split

```kotlin
@Composable
fun ToolCallRow(toolCall: ToolCall, modifier: Modifier = Modifier, subagentDepth: Int = 0) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToolCallRowContent(toolCall, expanded, { expanded = !expanded }, modifier, subagentDepth)
}
```

`rememberSaveable` serialises the `Boolean`, so rotation, dark-mode toggle and process death +
restore all preserve `expanded`. **No `key` parameter** — the default call-site identity suffices
because the `LazyColumn` item slot keys items on `Message.id`. A `ToolCall` that arrives updated in
place (a status flip, a new `elapsedSeconds` reading) is a **new value at the same call site**, so
`expanded` survives the update — this is the property #895's elapsed-reading and status-flip live
updates depend on, and `ToolCallRowTest.a_row_stays_expanded_while_it_is_updated_in_place` pins it.

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

`HeaderRow` has a 20dp minimum height, yielding a 36dp collapsed card with its 8dp vertical padding.
The first 412dp device capture measured only 32dp without that minimum: text style line height alone
did not guarantee the designed row height. The status is unweighted; the headline receives remaining
width and ellipsizes. In the described variant, the chevron sits beside the description within that
weighted group. The simple variant omits it. Consecutive cards retain 12dp bottom spacing.

`HeaderRow` itself no longer inspects `inputFields["description"]` (#1315) — it calls `toolHeadline`
once and switches on the returned `ToolHeadline` sealed type (`Described` vs. `Simple`), mirroring
desktop's `toolHeadlineRuns` in `toolHeadline.ts`.

### Consecutive tool rows sit flush (#1577)

"Consecutive cards retain 12dp bottom spacing" above holds only when the **next thread row is not
also a tool row** — see [Thread screen § Consecutive tool rows sit
flush](thread-screen-subagent-tool-rows.md#consecutive-tool-rows-sit-flush-1577) for how
[`ThreadScreen`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt)
derives that from the next chronological row and passes it down as `joinsNextToolRow`, through
[`MessageBubble`](./message-bubble.md), to this composable. When `true`, the outer `Surface`'s
modifier chain swaps `Modifier.padding(bottom = MessageRowVerticalSpacing)` for a private
`Modifier.overlapNextByBorder()` — a `layout` modifier that measures and draws the `Surface` at its
full height but *reports* a height one `ToolCallBorderWidth` (1dp) short. The next row's `Surface`
then stacks directly on top of that short-reported height, so its top outline lands exactly on this
row's bottom outline and the two 1dp borders draw as one line, matching the "Consequent tool uses"
group in Figma `620:1792`. Nothing about expansion changes: the shrink is on the outer `Surface`, not
the `Column` that toggles, so a collapsed and an expanded joining row overlap the same way.

The `Surface` also gains `Modifier.testTag(TOOL_ROW_TAG)` (`"tool-row"`), applied **after** the
shrink/padding branch, so a test tag's bounds are always the full drawn outline — never the
one-pixel-short reported height — whether or not the row joins its neighbour.

**Known gap, left as-is (#1577 verifier review):** `overlapNextByBorder` only knows this row joins the
*next* one; it does not know whether rows at different `subagentDepth` indents stack this way. Figma
`620:1792` shows only same-depth joins, so a run of tool rows across a subagent boundary has no design
reference, and `ThreadScreen`'s `isToolRow` check does not exclude it — their outlines overlap with
offset left edges in that case. Likewise a per-row `alpha` wrapper above the thread's session delimiter
can clip the 1dp overflow or double-draw it at partial alpha; cosmetic only, and one line still shows.

### Subagent step description (since #896)

When `subagentDepth > 0`, the clickable `Column` above gains a `Modifier.semantics { contentDescription =
… }` chained **before** `.clickable(...)`, holding the localized `cd_tool_subagent_step` string ("Subagent
step, level %1$d") formatted with the depth. At `subagentDepth == 0` no `semantics` modifier is added — the
row's accessibility tree is byte-for-byte what it was before #896.

**The description sits on the `clickable` `Column`, not the outer `Surface`.** Putting it on the `Surface`
instead becomes a *separate* TalkBack stop from the row's own click target — a user would hear "Subagent
step, level 1" and then, as a second swipe, the row's actual expand/collapse announcement. The `Column` is
already the row's merge-descendants semantics node (`clickable` creates one), so a `contentDescription` set
there merges into that same node and TalkBack reads it together with the existing `onClickLabel` / `Role.Button`
announcement — one stop, not two. This is why the modifier chain adds `semantics` immediately ahead of
`clickable` rather than wrapping the whole `Surface`.

The indent that makes nesting visible sighted is applied by the caller
([`MessageBubble`](./message-bubble.md#subagent-nesting-indent-since-896)) to this composable's `modifier`
parameter — `ToolCallRow` itself draws no indent and holds no layout state for nesting. The description is
therefore the *only* signal a screen-reader user gets for depth; a sighted user gets the indent, a TalkBack
user gets the level number, and neither depends on colour (the AC's requirement).

### Subject and elapsed text

These rules live in `ToolRowFormat.kt`, mirrored from desktop's `toolHeadline.ts` / `shortenPath.ts` /
`ConversationScreen.tsx`'s `formatToolElapsed`, and are pure functions with no Compose import so the
composer's status area ([`ThinkingIndicator`](thinking-indicator.md#the-running-tool-897), #897) can call
`formatToolElapsed` directly:

```kotlin
internal fun toolHeadline(toolName: String, inputFields: Map<String, String>, input: String): ToolHeadline
internal fun toolRowSubject(toolName: String, inputFields: Map<String, String>, input: String): String
internal fun shortenToolPath(path: String): String
internal fun formatToolElapsed(seconds: Int): String
```

**`toolHeadline`** (#1315) is `HeaderRow`'s single entry point, mirroring desktop's
`toolHeadlineRuns`: it tests `toolName == "Bash"` (exact — `BashOutput` does not qualify) before
looking at any field. When `Bash` and `description` is nonempty it returns
`ToolHeadline.Described(description)`. When `Bash` and `command` is nonempty with no description it
returns `ToolHeadline.Simple(lead = command, subject = "")`. Every other case, including a `Bash`
call with neither field, falls through to `ToolHeadline.Simple(lead = toolName, subject =
toolRowSubject(...))`.

**`toolRowSubject`.** For `toolName == "Bash"` (exact match — `BashOutput` does not qualify), the first
non-empty of `description`, `command`; otherwise the first non-empty of `TOOL_SUBJECT_FIELDS =
file_path, path, notebook_path, command, pattern, url, query, description`; otherwise the `input`
précis verbatim. **"Non-empty" is `value != ""`, not `isNotBlank()`** — a whitespace-only field wins
over a lower-priority field, matching desktop's decision, not a Kotlin-idiomatic default. A field
picked from `TOOL_PATH_FIELDS = file_path, path, notebook_path` is shortened through
`shortenToolPath`; nothing else is. **Since #1575, `inputFields` is persisted in the disk cache**
(`CachedToolCall.inputFields`, defaulted `emptyMap()` so a pre-#1575 cache file still reads back), so a
restored row keeps the same headline and subject it had live. The précis fallback's production trigger
is now a row with no fields at all — a pre-#1575 cache file, or a daemon that sent no `input` — not
cache restoration itself. Desktop's third rule (fall back to any single-line
field before the précis) is **not** ported; this ticket's contract stops at the four listed above.
**Since #1315, `toolHeadline` only reaches this function's `Bash` branch when both `description` and
`command` are empty** — `toolHeadline` itself already handles the two `Bash`-with-a-field cases, so
in production this function's own `Bash`-specific lookup is effectively dead code, reachable only
through the précis fallback. It is left as-is (the plan scoped this ticket to `toolHeadline`, not a
`toolRowSubject` rewrite).

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

**Result count (#1316).** `ToolCall.resultDetail` — the daemon's `result_detail`, e.g. `"265 lines"` —
draws as one-line, ellipsized `labelMedium`/`onSurfaceVariant` text before the status glyph, on any
non-`Running` row, when it is non-null and non-empty (`Modifier.testTag(TOOL_RESULT_DETAIL_TAG)`). A
`Running` row never shows it even if a stale value were present; a `null` or empty value draws nothing
and leaves no gap, because `spacedBy` only places gaps between children. The whole trailing `Row` is
wrapped in a private `Modifier.maxHalfWidth()` (a `layout` modifier that halves the incoming
`constraints.maxWidth`) mirroring desktop's `.tool-row__right { max-width: 50% }`; the count `Text`
takes `Modifier.weight(1f, fill = false)` so it is the group's only shrinkable child — however long the
daemon's value is, it ellipsizes inside that half-row cap and can never push the status glyph or the
tool name/headline off the row. `ToolCall.resultDetail` itself is set by `withToolResult`'s first fold
(see [Live tool-call § First result wins](live-tool-call.md#first-result-wins-and-first-denial-wins-1316));
a cache-restored row's `resultDetail` is `null` (not persisted, same posture as `denial` and
`elapsedSeconds`), so a restored resolved row shows no count even though it is `Done`/`Failed`/`Denied`.

### Expanded body

```kotlin
Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), spacedBy(12.dp))
├── ExpandedSection("Input")   { nonempty input fields, else nonempty input précis }
├── ExpandedSection("Output")  { nonempty output when status is Done or Failed }
└── ExpandedSection("Denied by claude") { nonempty denial on Denied }
```

The body is **height-bounded with internal scroll** rather than growing the thread row without limit
— new in #895; the pre-restyle row had no bound. `ExpandedSection` is a `labelSmall` caption
(`tool_row_input` / `tool_row_output` / `tool_row_denial` string resources, replacing the pre-#895
English-only inline literals) over its content.

**Input.** Nonempty `inputFields` render in map order, each key as a monospace `labelSmall` caption
over its value. Empty field values are omitted. If no nonempty fields remain, the nonempty `input`
précis is used instead. Since #1575 a cache-restored row has its `inputFields` back and follows this
same rule as a live row; only a row with no fields at all (a pre-#1575 cache file, or a daemon that
sent no `input`) falls back to the précis. If neither source has content, there is no Input section.

**Output.** Gated on `status == Done || status == Failed` and nonempty `output`. A `Running` row
has no output yet (the data layer fills it on the correlated `tool_result`) and a `Denied` row's
reason belongs in the Denied section instead.

**Denied.** Gated on `status == Denied`, a nonnull denial and at least one nonempty denial string.
`denial.message` renders, then `denial.decisionReason`, omitting either when empty. **A cache-restored
`Denied` row has no `denial`**
(that field is not persisted — see [Live tool-call § Denied](./live-tool-call.md#denied-811)) and
shows no Denied section at all, not an empty one.

**Content rendering (`ToolContent`).** The `command` field always uses the shared bordered
`CodeBlock`; other input and denial content uses it for line breaks or more than 80 characters.
Results use it only for line breaks. A long single-paragraph result wraps as 12sp monospace prose:
the earlier length heuristic boxed that prose even though the design shows it wrapping. Code blocks
keep their 6dp border, 16 × 12dp padding, horizontal scrolling and `copyable = false`; the old Figma
copy glyph does not change the product's no-copy rule. All other content is inert 12sp monospace
`Text` with 20sp line height and no URL or Markdown action.

### File-private spacing constants

Renamed and re-tuned to the #895 design (`ToolCallCornerRadius` 12dp → 6dp, new
`ToolCallBorderWidth`, status/spinner sizing, `ToolNameMaxWidth` and `ToolCallExpandedMaxHeight`).
The described variant adds a small chevron slot, a 20dp header minimum and 12dp expanded gap.
Every value is still a `dp` literal at the declaration site only — no `.dp` literal at any call site.
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
  scoped to path fields only, and the précis fallback **only** for a nonempty map whose keys none of
  `TOOL_SUBJECT_FIELDS` match — an empty map (since #1575, a field-less row) yields an empty subject
  instead; `shortenToolPath` at the 4-segment boundary and with a leading slash. `toolHeadline` (#1315)
  is pinned separately: `Bash` with a description (`Described`), `Bash` with a command and no
  description (`Simple`, empty subject), a non-`Bash` call with a description (`Simple`, name +
  subject), `BashOutput` with both fields (not treated as `Bash`), and `Bash` with neither field
  (`Simple`, name + empty subject, since #1575). **#1575 lesson:** the fallback cases must use a
  JSON-shaped précis value (e.g. `"{\"foo\":1}"`), not a plain string like `"ls -la"` — a plain-string
  précis reads the same whether or not the real bug (an unparsed `input_summary` JSON fragment leaking
  into the header) is present, so it can pass while the row still shows raw JSON.
- **`ToolCallRowTest`** lives in `app/src/sharedTest`, not `app/src/androidTest` — see
  [development-verification § where a screen test goes](./development-verification-gates.md#where-a-screen-test-goes).
  Covers each status's own content description (and that `Denied` does *not* also show
  `cd_tool_failed`), the elapsed text at a running reading vs. no reading vs. a stale reading on a
  resolved row, simple/described selection, chevron presence, supplied-only sections, inert
  noncopyable content, the Denied section's message, compact enlarged-text reachability and the
  expanded-stays-expanded-through-an-in-place-update case. Since #1315 the simple/described coverage
  is: a `Bash` command alone leads with the command and shows no `Bash` text and no chevron; a
  non-`Bash` call (`Agent`) with a description keeps its name and subject with no chevron;
  `BashOutput` with both `command` and `description` is not treated as `Bash`; and, **since #1575,**
  a call with no fields at all — including a row restored from a pre-#1575 cache file — is headed by
  its name alone, with no précis in the collapsed header. **Since #1316,** the same test file covers
  the result
  count on `Done`/`Failed`/`Denied`; its absence on `Running` even with a `resultDetail` value set
  (a case that cannot occur via the reducer but is still asserted at the row); `null` and empty both
  drawing nothing with no gap; and a 3000-character `resultDetail` value keeping the status glyph and
  tool name/headline displayed within the row's bounds, proving the `maxHalfWidth()` cap.
- **Compose testing gotcha (#895 lesson):** `onNodeWithTag(TOOL_ELAPSED_TAG)` *finds* the elapsed
  `Text` node fine even though it sits inside the row's `clickable` `Column` (a merge-descendants
  semantics node), but `.assertIsDisplayed()` on that lookup fails with "not displayed" although the
  layout is correct. The fix is `onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true)`. A
  text-based lookup against the same node (`onNodeWithText("4s")`) does **not** need it and would stay
  green even if the child were actually clipped, because it matches against the merged parent row —
  so a tag-scoped display assertion inside any `clickable`/merged row in this codebase should default
  to `useUnmergedTree = true`, not just this component.
- **E2E:** the existing rung-3 `interactiveTurn_toolPrompt_rendersToolStepInThread` now looks for
  the resolved row's accessible Done status, absent before the prompt and visible after the turn.
  A described header omits the tool name, so a name-based matcher could fail while the row is correct.
  The fresh full live suite executed all 43 selected methods with 0 failures and 0 skips, including
  this named method as a passing testcase; no separate focused live run was needed.
  The scripted `tool` / `tool-failed` scenarios still use the kept running/failed descriptions.
  The elapsed reading depends on transient heartbeats and remains under component coverage.
  A tool-row header rule change can break this coverage in the other direction too: #1315's rung-4
  `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_toolStepRunsThenCompletes`
  found its resolved row by matching the text `Bash`, and the `tool` fixture's `Bash` call carries
  only a `command` field with no `description` — so under the new `toolHeadline` rule the row stopped
  showing `Bash` at all. The assertion now matches on the fixture's command (`echo hello`, the
  `TOOL_COMMAND` constant) instead, which stays visible whether the daemon sends input fields (the
  command lead) or not (the `Bash`-name-plus-précis fallback).
- **Visual evidence:** [`tool-row-1208` captures](../../../app/src/androidTest/assets/tool-row-1208/capture-context.txt)
  pair actual 412 × 892 API 33 emulator frames with the inspected Figma nodes and a labelled
  comparison/difference image. The 320dp, 1.5× font-scale capture includes a scrolled result.
  `ToolRowDesignCaptureTest` executed 2 tests with 0 failures and 0 skips; the focused
  `MainActivityInsetsDeviceTest.populatedThreadKeyboardAt412By892` executed 1 with 0 failures and
  0 skips. The wide Figma canvases and missing combined mobile state limit pixel comparison.
- **`subagentDepth` (#896) is not covered by `ToolCallRowTest`.** It is exercised end to end through the
  real `ThreadScreen` fold instead: `ToolRowNestingTest` (`app/src/sharedTest/.../thread/`) asserts that a
  matched child and a grandchild each carry their own "Subagent step, level N" description and that a
  top-level or unmatched-parent row carries none. See [Thread screen § Subagent tool-row
  nesting](./thread-screen-subagent-tool-rows.md#subagent-tool-row-nesting-896) and the
  derivation's own unit coverage, `ToolNestingDepthsTest`.
- **`joinsNextToolRow` (#1577) is covered end to end through the real `ThreadScreen` fold**, the same
  pattern as `subagentDepth`: `ConsecutiveToolRowSpacingTest` (`app/src/sharedTest/.../thread/`, beside
  `ToolRowNestingTest`) asserts the second of two adjacent tool rows' top equals the first row's bottom
  minus 1dp, both collapsed and after expanding the first row (and checks the row actually grew on
  expansion), and that the gap before a following assistant reply stays at least 12dp. See [Thread
  screen § Consecutive tool rows sit
  flush](thread-screen-subagent-tool-rows.md#consecutive-tool-rows-sit-flush-1577) for how
  `ThreadScreen` derives the flag.

## Edge cases / limitations

- **Expansion is local to the keyed row.** In-place updates preserve it; it is not repository state.
- **Missing supplied data.** Empty input, output and denial values do not create sections. A simple
  row with an empty `toolName` still renders that empty text slot without crashing.
- **No expand/collapse animation.** The conditional `if (expanded) ExpandedBody(toolCall)` still
  adds/removes the subtree directly; a future ticket could wrap it in `AnimatedVisibility` without
  touching the public API.
- **RTL.** `Arrangement.spacedBy` / `Alignment.CenterVertically` respect `LayoutDirection`
  automatically.
- **No language inference from path extension** for the Input/Output code blocks — `language` is
  always `null`. Open since before #895; a future ticket could add a path-extension → language helper
  for `Read`/`Edit` fields specifically.
- **The composer's open-tool status indicator is separate** and reuses `formatToolElapsed` from
  the Compose-free formatter. Subagent nesting shipped in #896.
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
  `docs/specs/architecture/895-tool-row-restyle.md`,
  `docs/specs/architecture/896-nest-subagent-tool-rows.md`,
  `docs/specs/architecture/1575-cached-tool-input-fields.md`,
  `docs/specs/architecture/1577-flush-consecutive-tool-rows.md`
- Upstream:
  - [Data model](./data-model.md) — `ToolCall(toolName, input, inputFields, output, status,
    denial, elapsedSeconds, parentToolUseId, resultDetail)` and the `Message.toolCall`
    non-null-iff-`Role.Tool` invariant
  - [Live tool-call](./live-tool-call.md) — the data layer that produces `status`, `inputFields`,
    `denial`, `elapsedSeconds` and `resultDetail` on the same `ToolCall`; `Denied`'s "no `denial` on
    cache restore" rule, the `tool_progress`/`tool_denied` fold rules and the #1316 first-result/
    first-denial-wins rules live there, not here
  - [`MessageBubble`](./message-bubble.md) — `Role.Tool` arm routes here via `?.let`, applies the
    content gutter
  - [`MarkdownText`](./markdown-text.md) — `internal CodeBlock(content, language, copyable = false,
    textStyle = bodySmall)` reused for code-ish input/output; `textStyle` added by #895, `copyable`
    stays at its `false` default here per the current no-copy product decision
  - [development-verification](./development-verification.md) — component-test placement
    (`sharedTest` vs. `androidTest`) and the managed-device gate this row's tests run under
- Downstream:
  - **#896** — subagent tool-row nesting (split from #658): added `subagentDepth` and the "Subagent step,
    level N" description here; the caller ([`MessageBubble`](./message-bubble.md)) owns the indent. See
    [Subagent step description](#subagent-step-description-since-896) above.
  - **#897** — naming the open tool in the composer's status area (split from #658): calls
    `formatToolElapsed` directly from [`ThinkingIndicator`](thinking-indicator.md#the-running-tool-897);
    nothing in this file changed for it.
  - **#1316** — added the result count (`ToolCall.resultDetail`) to `TrailingStatus` and the
    `maxHalfWidth()` cap on the trailing group. See [Result count](#trailing-status) above.
  - **#1575** — persisted `inputFields` in the disk cache so a restored row keeps its live headline
    and subject; changed the field-less fallback from the `input` précis to an empty subject. See
    [Subject and elapsed text](#subject-and-elapsed-text) above and [Conversation
    cache](./conversation-cache.md) for the cache side.
  - **#1577** — added `joinsNextToolRow` and the `overlapNextByBorder()` shrink so consecutive tool
    rows share one 1dp outline instead of a 12dp gap; `ThreadScreen` derives the flag from the next
    chronological row. See [Consecutive tool rows sit flush](#consecutive-tool-rows-sit-flush-1577)
    above and [Thread screen § Consecutive tool rows sit
    flush](thread-screen-subagent-tool-rows.md#consecutive-tool-rows-sit-flush-1577).
- Still open:
  - Language inference from path extension for `Read`/`Edit` code blocks
  - `AnimatedVisibility` around the expanded body
