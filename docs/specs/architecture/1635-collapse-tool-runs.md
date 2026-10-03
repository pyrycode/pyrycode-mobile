# #1635 — Fold runs of consecutive tool rows into one "Using tools: N" row

## Files read

- `ui/conversations/thread/ThreadRow.kt` — `ThreadRow`, `foldQueuedRows`, `ThreadRow.listKey`, `toolNestingDepths`. The new fold and arm sit here, and the key argument must stay true.
- `ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen`'s `rows` derivation, the `itemsIndexed` row renderer, `FollowNewestEnd` inputs, the private `isToolRow` neighbour predicate (#1577).
- `ui/conversations/components/ToolCallRow.kt` — `ToolCallRowContent`'s outline/fill/padding, `TrailingStatus`, `StatusGlyph`, `overlapNextByBorder`, the `tool_row_chevron_down` drawable.
- `ui/conversations/components/MessageBubble.kt` — the `Role.Tool` arm that indents by `toolNestingDepth`; expanded run rows reuse it unchanged.
- `MainActivity.kt` — the thread destination's `ThreadScreen` call; already injects `AppPreferences`.
- `data/preferences/AppPreferences.kt` — `collapseToolUses` (#1634), default `true`.
- `app/src/sharedTest/.../ConsecutiveToolRowSpacingTest.kt`, `ToolRowNestingTest.kt`, `app/src/test/.../ThreadRowsTest.kt` — patterns to mirror.
- `docs/knowledge/features/tool-call-row.md`, `thread-screen-subagent-tool-rows.md` — the flush-join (#1577) and indent (#896) lessons: the gutter and indent live in `MessageBubble`, not the row.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=726-5376

The run header is a tool row: `Schemes/background` fill, 1 dp `Schemes/primary-container` outline, 6 dp corners, 12 dp horizontal and 8 dp vertical padding (36 dp tall), 12 dp gaps. Left: "Using tools: N" in `bodyMedium` / `onBackground`, then the tool row's 8×4 chevron (down collapsed `726:5377`, up expanded `726:5440`), a spacer, and the trailing status: check (`onSurfaceVariant`), the 14 dp running spinner (`726:5525`), or "K failed" in `labelMedium` / `error` with the `ErrorOutline` icon (`726:5573`). Expanded, the run's tool rows follow the header flush (each outline overlaps the previous by 1 dp, as #1577).

## Context

#1634 stored the "Collapse assistant tool uses" setting (on by default). This ticket reads it in the thread and folds runs of two or more adjacent tool rows. Render-time only; nothing stored or sent changes. No decision record needed — it is a third render-time fold beside `foldQueuedRows` and `toolNestingDepths`.

## Design

**Fold** — in `ThreadRow.kt`:

- New arm `ThreadRow.ToolRun(runId: String, tools: List<Message>, expanded: Boolean)`. `runId` is the run's first tool row's message id (its `tool_use_id`); `tools` are the run's tool messages in order.
- `internal fun foldToolRuns(rows: List<ThreadRow>, expandedRuns: Set<String>): List<ThreadRow>` — walks the queued-folded rows; a maximal run of ≥ 2 adjacent `isToolRow()` rows becomes one `ToolRun`, followed by the run's original `Delivered` rows when `runId in expandedRuns`. A lone tool row and every non-tool row pass through untouched. O(rows).
- `ThreadRow?.isToolRow()` moves from `ThreadScreen.kt` (private) to `ThreadRow.kt` (internal), so the fold and the #1577 neighbour check share one predicate (`Role.Tool` with a non-null `toolCall`). Sub-agent rows are tool rows, so they join runs; their depth still comes from `toolNestingDepths(state.items)`.
- `listKey`: `ToolRun -> "tool-run:$runId"`. A fifth distinct namespace; `runId` is a message id that `withMessage`'s upsert keeps unique, and each run has a distinct first row, so no two headers collide. Expanded tool rows keep their own `msg:` keys and appear once (collapsed runs omit them), so the `msg:` half of the argument still holds. The KDoc on `listKey` gains this bullet.

**Screen** — `ThreadScreen` gains `collapseToolUses: Boolean = false` (default off keeps every existing screen test drawing as today). `rows` becomes `foldToolRuns(queuedRows, expandedRuns)` when on, else the queued fold as today; every downstream use (`FollowNewestEnd`, `oldestRowIndex`, keys, neighbour check) reads the display rows. `expandedRuns` is `remember { mutableStateOf(emptySet<String>()) }`; the header's tap toggles its `runId`. Because the run's id is its first row, a run stays expanded while new tool rows join it at the end. The header's `joinsNextToolRow` is `expanded`.

**Composable** — new `ui/conversations/components/ToolRunRow.kt`: `ToolRunRow(tools: List<Message>, expanded: Boolean, onToggle: () -> Unit, modifier)`. Same Surface/outline/padding as `ToolCallRowContent`, clickable with `Role.Button` and an expand/collapse click label. Status: `running = any Running`, `failed = count of Failed or Denied`. When `failed > 0` the "K failed" text draws; the glyph is the spinner while any run, else the error icon when any failed, else the check. Content descriptions: `cd_tool_running`, `cd_tool_failed`, `cd_tool_done`.

`ToolCallRow.kt` shares, as `internal`: the dimension constants the header needs, `overlapNextByBorder`, a `ToolRunningSpinner()` and `ToolStatusGlyph(...)` (the existing `StatusGlyph`). The header draws `tool_row_chevron_down`, rotated 180° when expanded (Figma's up chevron is the same glyph flipped).

**Strings** — `tool_run_label` "Using tools: %1$d", `tool_run_failed` "%1$d failed", `tool_run_expand` "Show tool uses", `tool_run_collapse` "Hide tool uses".

**MainActivity** — collects `appPreferences.collapseToolUses` with `collectAsStateWithLifecycle(initialValue = true)` and passes it, so toggling the setting updates an open thread.

Overlaps: none found on in-flight branches.

## State and concurrency model

No new coroutines. `expandedRuns` is UI-local Compose state, not saved across process death. The preference is a cold DataStore flow collected under the destination's lifecycle.

## Error handling

None new. A `Role.Tool` row with no `toolCall` is not a tool row (it draws nothing today) and ends a run.

## Testing strategy

- Unit, `ThreadRowsTest` (JVM): run boundaries (assistant / user / boundary / banner split runs; two runs in one turn), a lone tool row passes through, nested sub-agent rows join the run, expanded run emits header + its rows in order, `listKey` uniqueness across a folded list, collapsed run hides its rows.
- Screen, new `app/src/sharedTest/.../ToolRunCollapseTest.kt` on the real `ThreadScreen`: "Using tools: 3" shows and tool names are hidden; tap expands (tool names show, sub-agent rows keep their indent) and tap collapses; run stays expanded when a tool row joins; done check / running spinner / "1 failed" + error icon by content description; N and status update live; `collapseToolUses = false` draws the rows as today and flipping it on folds without remounting.
- Existing: `ConsecutiveToolRowSpacingTest`, `ToolRowNestingTest`, `ScriptedToolRowTest`, `ThreadRowsTest`.
- No rung-3 scenario: the ticket's Technical Notes say the live scenario keeps passing because the status icons keep the tool row content descriptions, and a lone tool row is unchanged.

## Open Questions

- Running plus failed at once: decided "K failed" text with the spinner glyph — neither signal is hidden.
- A history page that prepends tool rows to the oldest run changes its first id, so an expanded run there collapses. Accepted: rare, and nothing is lost.

## Revisions

- 2026-10-03: `ToolRunRow` takes `toolCalls: List<ToolCall>` rather than `tools: List<Message>`, since the header reads only statuses; `ThreadScreen` maps the run's messages to their tool calls. The shared pieces in `ToolCallRow.kt` are `ToolRunningSpinner`, `ToolDoneGlyph` and `ToolFailedGlyph` (each with its content description) rather than a generic `ToolStatusGlyph`. Adding the `ToolRun` arm made `ThreadRowsTest`'s exhaustive `when` over the queued fold's output need a branch, which fails the test if the queued fold ever emits one. The fold unit tests sit in their own `ToolRunFoldTest` beside `ThreadRowsTest`.
