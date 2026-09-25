# #1041 — Redraw the background task panel to its Figma design

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` → `BackgroundTaskPanel`, `TaskRow`, `TaskField`, `CutMarker`, `wasCut`, `printableText` — the whole surface this ticket redraws; the inert-text rule and the 4096-char bound live here and stay.
- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt` → `BackgroundTask`, `BackgroundTaskUpdate`, `BackgroundTaskRoster` — every value shown is already here; `isFinished` can be `true` with `finish == null` (the reconnect case).
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileReadOnlyModal`, `MobileModalShell` — unchanged. The shell paints Figma's `onPrimaryFixed` modal as `primaryContainer`, gives content a vertically scrolling, vertically centred `Column` with 12 dp gaps, and maps Figma's 6 dp radius to `shapes.small`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/SuccessColors.kt` → `ColorScheme.success` — the Completed tag colour.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` — the repo's monospace idiom, `typography.*.copy(fontFamily = FontFamily.Monospace)`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt` — existing Robolectric cases; `finishedTask_isLabelledFinished` moves to the tag.
- `app/src/main/res/values/strings.xml` → `background_tasks_*` — existing strings kept; new ones appended.
- `docs/knowledge/features/success-color.md` — `success` is a single-field slot; the Completed tint is `success` at alpha, no new slot.

In-flight overlap: #1021 and #878 also append to `strings.xml`; additive only, no dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-876 (frames 568-877 Populated, 568-932 Capped/cut/update variants, 568-981 Empty, 568-997 Never reported; components 563-1054 Task status tag, 563-1055 Cut marker)

A column of section labels ("Running · n", "Finished · n", `secondary`, label type) each followed by rounded 6 dp task cards (`onPrimary` tint, 41 % running / 22 % finished, 14/12 dp padding, 8 dp gap). A card's first line is the raw task type in 12 sp monospace `primary` with a pill status tag on the right (6 dp dot + 11 sp medium label; Running `primaryContainer`/`onPrimaryContainer`, Completed `success` on a 16 % `success` tint, Failed `errorContainer`/`onErrorContainer`, Stopped `secondaryContainer`/`onSecondaryContainer`), then the description (monospace for `local_bash`), the finish summary, and a "Latest update" `outline` label over a `surface` code block in monospace `onSurfaceVariant` (italic `outline` "No change reported" when empty). Cut markers are dashed `tertiary` 4 dp chips; the partial notice is a filled `secondaryContainer` row with a dot; the empty readings are a centred 32 dp ring (solid for empty, dashed for never reported), a 16 sp medium title and a 14 sp `onSurfaceVariant` support line.

Deviations: the progress lines on running rows (activity, last tool, counts, elapsed) are out of scope (no progress frame yet). The modal ground is the shell's `primaryContainer`, not Figma's `onPrimaryFixed`, so a Running tag's `primaryContainer` ground sits on the card tint rather than on the Figma navy; the tag still reads because the card tint shifts the ground. The empty-state 160 dp top pad is replaced by the shell's existing vertical centring.

## Context

#678 shipped the panel with no Figma frame: plain text rows, one "Finished" label for every terminal state, patch as body text. The design now exists; this ticket redraws the list inside the unchanged `MobileReadOnlyModal`. No data or wire change. Not operator-flow-new: nothing is sent or received, so no rung-3 scenario.

## Design

Two production files plus strings.

### `ui/conversations/thread/TaskStatusTag.kt` (new)

The Figma component, visual only:

- `internal enum class TaskTagStyle { Running, Completed, Failed, Stopped }`
- `@Composable internal fun TaskStatusTag(style: TaskTagStyle, label: String, modifier: Modifier = Modifier)` — pill (`CircleShape`), padding start 8 / end 10 / vertical 2 dp, 6 dp dot in the label colour, label `labelSmall`, `maxLines = 1`, ellipsis. Colours per the style mapping in Design source. Not clickable.

### `BackgroundTaskPanel.kt` (redrawn)

- `BackgroundTaskPanel` keeps its signature, logging and branch order. Inside the modal it emits one `Column(spacedBy(10.dp))`: partial notice first (unchanged `> 0` rule, restyled as `PartialNotice`), then either an `EmptyReading` or the two groups.
- Groups: `running = tasks.filterNot { it.isFinished }`, `finished = tasks.filter { it.isFinished }`, each keeping claude's order. A group is drawn only when non-empty: a `GroupLabel` then its cards. Label is `background_tasks_group_running` / `_finished` ("Running · %1$d"), or the `_shown` variants ("Running · %1$d shown") when `droppedTasks > 0`. A 4 dp spacer precedes the Finished label when both groups draw.
- `TaskRow(task)`: card `Column` (`shapes.small`, `onPrimary` at 0.41 / 0.22, padding 14×12, 8 dp gap), still `semantics(mergeDescendants = true)` and not clickable. Order:
  1. Header `Row`: task type (bounded, monospace `bodySmall`, `primary`, `weight(1f)`) and `TaskTag(task)` with `widthIn(max = 160.dp)` so an unknown long word cannot push the row. The type's cut marker is its own element on the line directly under the header.
  2. Description via `TaskField` — monospace `bodyMedium` when the raw `taskType == "local_bash"`, else `bodyMedium`; `onSurface` running, `onSurfaceVariant` finished; then its cut marker.
  3. Finish summary (non-empty only) via `TaskField`, `bodyMedium` `onSurfaceVariant`, then its cut marker.
  4. `latestUpdate?.let`: "Latest update" (`labelMedium`, `outline`), then a `surface` block (`shapes.small`, padding 10×8) holding either the bounded patch in monospace `bodySmall` `onSurfaceVariant`, or, for an empty patch, "No change reported" in `bodyMedium` italic `outline`. The patch cut marker sits directly after the block, outside it. `latestUpdate == null` → no label, no block.
- `TaskTag(task)` resolves the reading, then calls `TaskStatusTag`:
  - `!isFinished` → Running / "Running".
  - `isFinished && finish == null` → Stopped / "Finished" (existing `background_tasks_finished`).
  - `finish.status` `"completed"` / `"failed"` / `"stopped"` (exact match) → Completed / Failed / Stopped with their string resources.
  - any other status → Stopped style, label = `boundedText(status).text`. The label falls back to "Finished" when that text is blank (an empty or all-control-character word) or equals "running" ignoring case and surrounding whitespace, so no terminal status ever reads Running or draws a blank pill (see Security review). The wire calls `status` an open set with only `completed` observed (protocol-mobile.md § `background_task_updated`), so the three known words match exactly.
- `TaskField(raw, cutByDaemon, style, color)` keeps its rule: `printableText`, bound to `MAX_PANEL_TEXT_CHARS`, marker if cut by daemon or by the bound. The printable-and-bound step is extracted to `boundedText(raw): BoundedText(text, cutForDisplay)` so the header's type and the tag's unknown word go through the same step without `TaskField`'s sibling marker.
- `CutMarker()` → a `Box` with a dashed 1 dp `tertiary` border (drawn in `drawBehind` with `PathEffect.dashPathEffect`, `shapes.extraSmall` radius 4 dp), padding 6×1 dp, text `labelSmall` `tertiary`. Same string, still its own element.
- `PartialNotice(count)` → `Row` on `secondaryContainer`, `shapes.small`, padding 12×10, 8 dp gap, 8 dp dot and `labelLarge` text, both `onSecondaryContainer`. Same string.
- `EmptyReading(dashedRing, title, support)` → centred `Column` (12 dp gap): 32 dp `Canvas` ring in `outline` (1.5 dp stroke; dashed via `dashPathEffect` when `dashedRing`), title `titleMedium` `onSurface`, support `bodyMedium` `onSurfaceVariant`, both `TextAlign.Center`. Empty roster → solid ring + `background_tasks_empty` + `background_tasks_empty_support`; `null` roster → dashed + `background_tasks_unreported` + `background_tasks_unreported_support`.
- Previews: populated (running + completed + failed + unknown + reconnect, dropped > 0), empty, never reported; light and dark.

### Strings (appended)

`background_tasks_group_running`, `background_tasks_group_running_shown`, `background_tasks_group_finished`, `background_tasks_group_finished_shown`, `background_tasks_status_running`, `background_tasks_status_completed`, `background_tasks_status_failed`, `background_tasks_status_stopped`, `background_tasks_latest_update`, `background_tasks_empty_support`, `background_tasks_unreported_support`.

## State + concurrency model

None added. Pure composition over the `BackgroundTaskRoster?` the caller passes; the existing `LaunchedEffect(Unit)` debug log is unchanged.

## Error handling

No new failure modes. Daemon strings (description, type, patch, summary, status word) stay inert plain `Text`: not clickable, not copied, not parsed, not a key, not a test tag, not logged. The status word is compared for equality against three constants and otherwise only displayed after `printableText` and the bound; the log line keeps its content-free counts.

## Testing strategy

Robolectric screen tests in `BackgroundTaskPanelTest` (`app/src/sharedTest`), composing the panel directly:

- Grouping: a mixed roster shows "Running · 2" and "Finished · 1"; running-only shows no Finished label and vice versa.
- Dropped > 0: labels read "Running · n shown".
- Each tag: running → "Running"; `completed` / `failed` / `stopped` → "Completed" / "Failed" / "Stopped"; no terminal row reads "Running" (count check).
- Unknown status `"cancelled"` → "cancelled" tag, not clickable; a status of control characters only → "Finished"; a status of `" Running "` → "Finished", and no node reads "Running".
- Reconnect (`isFinished`, `finish == null`) → "Finished". Replaces `finishedTask_isLabelledFinished`.
- Patch: shown with a "Latest update" label; empty → "No change reported"; never updated → no "Latest update".
- Both empty readings: title and support line, never each other's.
- Existing cut-marker, partial-notice, bound, control-character and not-clickable cases keep passing (the unknown status word joins the not-clickable list).

Style (colours, dashed borders, rings) is checked against the Figma screenshots via `@Preview`, not asserted. Tag style is not separately unit-tested: the mapping is only observable through the label, which the screen tests assert.

## Open questions

- Whether the header's 160 dp tag cap is enough on a 320 dp Robolectric screen with a long type; resolve while running the tests.

## Documentation handoff

The ticket has no Documentation handoff section. Pending for the documentation stage: the background-task panel's overview (wherever #678's panel is described) should record the grouping, the status-tag mapping including the unknown-status and reconnect cases, and the two empty-reading support lines.

## Security review

**Verdict:** PASS (after one revision to the plan above)

**Findings:**

- [Trust boundaries] Revised (was MUST FIX) — `finish.status` is claude-authored and open-set, and this ticket is the first to render it. As first drafted, an unknown word was shown raw, so a terminal status of `"running"` would have drawn a tag reading Running on a finished row: a daemon string passing for the app's own claim, and a breach of the ticket's "no terminal status reads Running". The plan now resolves the tag in `TaskTag`: only the three exact wire words pick a style; any other word is `printableText` + `MAX_PANEL_TEXT_CHARS` bounded, and falls back to the app's "Finished" when blank or equal to "running" ignoring case and whitespace. Tested.
- [Trust boundaries] No further findings — every daemon string (type, description, patch, summary, status word) still reaches only a plain `Text` through `printableText` and the 4096-char bound (`boundedText`, shared by `TaskField`, the header's type and the tag). Nothing is clickable, selectable, copied, parsed, used as a `key()` or test tag, or logged. The monospace choice compares the raw `taskType` for equality only; the status word is compared for equality only. The row keeps `mergeDescendants` with no click action.
- [Trust boundaries] SHOULD FIX — layout: a 4096-char status word or type must not widen the row. The tag carries `maxLines = 1` with ellipsis and `widthIn(max = 160.dp)`, and the type takes `weight(1f)`; verified by the screen tests at Robolectric's 320 dp width.
- [Trust boundaries] OUT OF SCOPE — `printableText` strips ISO control characters but keeps Unicode format characters (bidi overrides such as U+202E), so a word can be visually reordered, e.g. to read "failed" in the grey Stopped style. This is pre-existing for every panel field since #678 and not widened here: the tag's style is chosen by exact match on the raw word, so a spoofed word never gets a Completed or Failed colour. No ticket filed; the documentation stage can record it as an accepted limit.
- [Tokens, secrets] Not applicable — the ticket reads no token, key or credential; the panel receives only `BackgroundTaskRoster?`.
- [File / storage] Not applicable — nothing is written or read from storage; no path is built from daemon text.
- [Inter-process / Android surface] Not applicable — no manifest, intent, deep link, pending intent, provider or WebView change. The modal shell's window flags are unchanged.
- [Cryptographic primitives] Not applicable — no crypto, RNG or comparison against a secret.
- [Network & I/O] Not applicable — nothing new is sent or received; the frame decoding and bounds from #677 are untouched.
- [Logs] No findings — the existing debug-only `background_tasks_panel_opened` line logs the reading and two counts. No daemon string, status word or tag label is added to it.
- [Concurrency] Not applicable — no coroutine, flow or shared state added; the composable is a pure function of its argument.
- [Threat model] Hostile daemon frame: covered by the bound and inert rendering above. Malicious relay: content-blind, no new exposure. UI leakage: task text was already displayed by #678; no new surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

- **2026-09-25, during implementation.** `MobileModalShell` centres its content vertically, so a short task list would float mid-panel instead of starting under the header as the Populated frame draws it. The populated branch now follows its list with a `Spacer(Modifier.weight(1f))` in the shell's `ColumnScope`. A weight only shares the space the other children leave, so the spacer is zero once the list outgrows the viewport and scrolling is unaffected. The two empty readings keep the shell's centring. Resolves the open question: at Robolectric's width the header's `weight(1f)` type and the 160 dp tag cap keep a 4096-char status word on one line (`overLongUnknownStatus_isBoundedToOneLine`).
