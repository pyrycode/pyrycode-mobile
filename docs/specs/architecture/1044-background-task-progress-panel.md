# #1044 — Show a running background task's progress on the mobile panel

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt` → `BackgroundTaskProgress`, `BackgroundTask.progress` — the held frame (#1042); `progress` is already `null` once `isFinished`, but the panel still gates on `isFinished` itself (AC#2).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` → `TaskRow`, `TaskField`, `CutMarker`, `boundedText`, `wasCut`, `previewRoster` — where the block goes and the inert-text helpers it reuses.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt` → `setPanel`, `task`, `markers` — the Robolectric fixtures the new screen tests extend.
- `app/src/main/res/values/strings.xml` → `background_tasks_*`, `thread_attachments_too_large` — the plural shape to mirror.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `resources.getQuantityString` use — the existing plural-resolution idiom.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActionsTest.kt` — Robolectric setup under `app/src/test` for a resource-reading unit test.
- pyrycode `docs/protocol-mobile.md` § `background_task_progress` — `truncated_fields` can name `description` and `last_tool_name`; counters are cumulative and not guaranteed monotonic.

In-flight overlap: #1021, #1043, #1068 and #878 also add to `strings.xml`. Edits there are appended entries only.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-877

On each running card, a "Progress" column (2dp gap) sits between the task description and the "Latest update" block: the current activity in body text (`onSurfaceVariant`, wraps), then a meta line in small body text (`outline`) reading "Bash · 4 tools · 18k tokens · 2m 41s". Finished cards carry no progress block.

## Context

#1042 holds the latest `background_task_progress` per task; nothing renders it. The panel shows running cards that look identical whether the task is busy or stuck. No ADR warranted.

## Design

**Formatter — new file `BackgroundTaskProgressFormat.kt` (`ui/conversations/thread/`).**

- `internal fun progressCounters(resources: Resources, toolUses: Long, totalTokens: Long, durationMs: Long): List<String>` — returns the three counter segments in order: tools, tokens, elapsed. Only the three integers feed it; the tool name is joined on in the composable. Each negative reading is clamped to zero first.
- Tools: `R.plurals.background_tasks_progress_tools` ("%1$d tool" / "%1$d tools"), quantity = the count clamped to `Int`.
- Tokens: under 1000 the whole number; from 1000 up, thousands rounded half-up (computed without the `+500` overflow) with a `k` suffix. Plural `R.plurals.background_tasks_progress_tokens` takes the figure as `%1$s`; quantity = the raw count clamped to `Int`, so 1 is singular and any k-figure is plural.
- Elapsed, from whole seconds (`durationMs / 1000`): under 60 → `background_tasks_elapsed_seconds` ("%1$ds"); under 3600 → `background_tasks_elapsed_minutes` ("%1$dm %2$02ds"); otherwise `background_tasks_elapsed_hours` ("%1$dh %2$02dm", seconds dropped).

**Panel — `BackgroundTaskPanel.kt`.**

- New private `TaskProgress(progress: BackgroundTaskProgress)` composable; `TaskRow` calls it directly after the description `TaskField` and before the finish summary and `LatestUpdate`, only when `!task.isFinished && task.progress != null`.
- Layout: a `Column` with `spacedBy(2.dp)`: `TaskField(progress.description, cutByDaemon = wasCut(progress.truncatedFields, "description"), bodyMedium, onSurfaceVariant)` — so the activity gets the same printable-and-bounded treatment and cut marker as every field; then the meta `Text` (`bodySmall`, `outline`).
- Meta: `boundedText(progress.lastToolName)`; its segment is dropped when the bounded text is blank; the remaining segments plus `progressCounters(...)` joined by `" · "`.
- A `CutMarker` follows the meta line when `truncatedFields` names `last_tool_name` or the tool name was cut for display — the same rule every other field follows (the protocol lists `last_tool_name` as cuttable).
- `subagentType` is not rendered. Nothing is clickable, logged, keyed or parsed.
- Preview: `previewRoster`'s two running tasks gain progress matching the Figma frame.

## State + concurrency model

None added: pure rendering of the already-held `BackgroundTask.progress`.

## Error handling

No failure modes: negative counters clamp to zero; oversized or control-character text is bounded by `boundedText`; an empty tool name drops its segment.

## Testing strategy

- **Unit (Robolectric, `app/src/test/.../BackgroundTaskProgressFormatTest.kt`)** calling `progressCounters` with application resources: "1 tool" and "1 token" singulars; 999 → "999 tokens", 1000 → "1k tokens", 1499/1500 half-up; elapsed "41s", "0s", "2m 41s", "1m 05s", "1h 03m"; negative readings → "0 tools", "0 tokens", "0s".
- **Screen (Robolectric, `BackgroundTaskPanelTest`)**:
  - running task with progress shows activity and meta ("Bash · 4 tools · 18k tokens · 2m 41s"), activity below description, meta below activity, "Latest update" below meta (bounds order);
  - running task without progress shows neither;
  - finished task with non-null progress shows no block;
  - `truncatedFields = ["description"]` on the progress frame puts one marker; `last_tool_name` puts one;
  - empty `lastToolName` drops its segment ("1 tool · 840 tokens · 41s");
  - activity and meta text are not clickable, control characters are stripped.
- No device-only test; not an operator-facing daemon interaction beyond rendering, so no rung-3 scenario (display of an existing frame; the flow sends nothing).

## Open questions

- Should a blank activity line be skipped? Resolution: render as-is — a wire frame always carries a description, and `TaskField` renders the same way for every field.

## Documentation handoff

Pending for the documentation stage: fold the progress block (layout, formatting rules, `last_tool_name` marker) into `docs/knowledge/features/` for the background-task panel. The ticket names no reference doc of its own.
