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

## Revisions

- **2026-09-25 — security review added (verifier finding on PR #1073).** The ticket carries `security-sensitive`, and the committed plan had no `## Security review` section. The § A6 pass below was run against this plan and the implementation already on the branch. Verdict PASS. No design or code change follows from it. The contract stays as written under **Design**.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The daemon-to-UI boundary for this ticket is the two new daemon-text sinks, the progress `description` (activity line) and `last_tool_name` (meta line). Both pass through the panel's single display boundary, `boundedText` → `printableText`. That strips C0 and C1 control characters (keeping `\n` and `\t`) and bounds each field to `MAX_PANEL_TEXT_CHARS`, the same treatment every other panel field gets. The activity goes through `TaskField`. The tool name goes through `boundedText` in `TaskProgress` before it is joined. Both render as plain `Text`: no `LinkAnnotation`, no clickable modifier, no autolinking. Neither is used as a URL, a filename, a key or a log line. `subagent_type` is decoded but never rendered.
- [Trust boundaries — format strings] No findings. Daemon text is never a format pattern or format argument. `progressCounters` takes only the three `Long` counters. Its string and plural resources are fixed patterns filled with client-computed numbers. The tokens plural's `%1$s` receives the client-built figure from `tokenFigure`. The tool name is joined on after formatting, with `joinToString`.
- [Trust boundaries — meta-line spoofing, accepted] The tool name shares one `Text` with the counters. A hostile value could therefore imitate counter segments, for example `"Bash · 99 tools"`. It could also carry a Unicode bidi override that `isISOControl` does not strip, reordering how the counters after it display. This gives no new capability. The only author able to send such a value is the daemon, or claude through it, and that same author sends the integers the counters are formatted from, so it could simply send different integers. Third-party MCP tool names reach claude already constrained by the API's `[a-zA-Z0-9_-]` tool-name rule. Bidi controls inside any single panel field are the existing panel-wide behaviour, not something this ticket introduces. Per evidence-based fix selection, no defence is added for an unobserved failure.
- [Counters from daemon integers] No findings. Negative readings clamp to zero, because the protocol says the counters are not monotonic. The plural quantity is clamped to `Int.MAX_VALUE` before `getQuantityString`. Half-up rounding in `tokenFigure` uses division and remainder, not `+500`, so `Long.MAX_VALUE` cannot overflow. `Long.MIN_VALUE` clamps to 0. The largest possible output is a ~19-digit number: bounded, and still a number.
- [Tokens, secrets, credentials] No findings. The ticket reads no token, key or credential, and stores or logs nothing.
- [File / storage] No findings. There is no file, path or storage access. The progress frame lives only in memory, in the roster #1042 holds.
- [Inter-process / Android attack surface] No findings. There is no intent, deep link, pending intent, push, content provider or WebView. The card stays non-clickable, and the new text adds no action.
- [Cryptographic primitives] No findings. None are used or touched. The frame arrives through the existing `NoiseIkSession` transport and the #1042 decoder.
- [Network & I/O] No findings. No frame, verb or decoder changes. Wire decoding and its bounds belong to #1042 (`BackgroundTaskPayloads`). This ticket only renders the held model.
- [Error messages, logs, telemetry] No findings. The block logs nothing and emits no telemetry. No daemon text reaches Logcat.
- [Concurrency] No findings. No coroutine, flow or state is added. `TaskProgress` is a pure composable of `BackgroundTask.progress`, and it reads `Resources` from `LocalContext` during composition.
- [Threat model — hostile daemon frame] No findings. An oversized or control-laden activity or tool name is bounded and stripped, then marked cut when the client or the daemon (`truncatedFields` naming `description` / `last_tool_name`) cut it. A finished task never shows progress, even with a non-null frame, because `TaskRow` gates on `isFinished`.
- [Threat model — UI-side leakage] OUT OF SCOPE. The activity line can show claude's current file path or command on screen, which a screenshot or an accessibility service can read. That exposure already exists for the task description and the latest update. Screen-capture protection for the thread is not part of this ticket, and no ticket covers it yet.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25
