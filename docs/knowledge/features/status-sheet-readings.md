# StatusSheet — running model and context window readings

Split out of [StatusSheet](status-sheet.md) on 2026-09-24 to keep that document under the 50000-byte size
cap the docs guard enforces. These two sections moved here verbatim and kept their headings (promoted
from `###` to `##`, which does not change their anchor text), so their anchors are unchanged. Part of
[StatusSheet](status-sheet.md); see that document for the shell, the Model and Effort sections, hosting,
tests and edge cases.

## `RunningModelSection` (#891)

```kotlin
@Composable
private fun RunningModelSection(running: ThreadRunningModel)
```

Not in Figma node `20:100` — the design has no running-model row; this section reuses the node's existing section/row style and theme tokens rather than inventing new ones (see [§ Design source in the architecture doc](https://github.com/pyrycode/pyrycode-mobile/blob/main/docs/specs/architecture/891-status-sheet-running-model.md)). Two claude-reported values, each a [`ThreadReportedText`](thread-composer-footer.md#running-model-891)`?` already made inert by [`ThreadViewModel.reportedText`](thread-composer-footer.md#running-model-891):

- **`running.model == null`** → `UnavailableNote(stringResource(R.string.status_sheet_running_model_unavailable))` — `"Not reported yet"`, rather than a blank row or a fallback to `selectedModel`.
- **`running.model != null`** → a `bodyLarge`/`onSurface` `Text` carrying `withTruncationMark(model.text, model.truncated, ...)` (an `AnnotatedString`), tagged `Modifier.testTag(RUNNING_MODEL_TEST_TAG)` — the anchor the rung-3 `InteractiveStreamE2ETest` scenario waits on and reads. **No `maxLines`/`overflow`**: the ViewModel's 128-character inert bound already caps the length, and an ellipsis would clip exactly the truncation mark that says characters are missing.
- **`running.build != null`** → a `Caption`-style `bodySmall`/`onSurfaceVariant` line, `stringResource(R.string.status_sheet_running_build, build.text)` (`"Claude Code %1$s"`) through the same `withTruncationMark` treatment. `build == null` (claude reported an empty `claude_code_version`) omits the line entirely rather than rendering an empty caption.

**`withTruncationMark(text, truncated, mark): AnnotatedString`** (file-private) appends the client-owned italic `" (truncated)"` mark (`status_sheet_running_truncated`) as **text**, not a separate icon or `contentDescription`, so it is both visible and read by TalkBack as part of the line — pinned by `a_truncated_value_carries_a_visible_and_announced_mark` in [Tests](status-sheet-hosting-tests-and-edge-cases.md#tests). Named `withTruncationMark` rather than `reportedText` — the verifier's PR #936 review flagged that name colliding (across packages, but confusingly) with `ThreadViewModel.reportedText`, the function that produces the `ThreadReportedText` this one renders. The build line's `Caption` call goes through a same-named `Caption(text: AnnotatedString)` overload added alongside `Caption(text: String)` (which now just wraps it in `AnnotatedString(text)`) — added on the same review round so the build line's style lives in one place instead of repeating `Caption`'s modifier/style inline.

**`RUNNING_MODEL_TEST_TAG`** is a top-level `const val`, not `internal` — the rung-3 `InteractiveStreamE2ETest` (a different Gradle source set, `androidTest`) imports it directly rather than duplicating the literal tag string. It sits on a plain `Text` inside `RunningModelSection`'s `Column` with no merging parent above it, so the default (unmerged) semantics tree finds it — the plan's one open question, resolved during implementation with no change needed.

**`SessionFacts.permissionMode` is never read here or anywhere in this section.** The claimed permission posture claude reports alongside its build is deliberately not rendered — see [Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations) for why, and [Thread composer footer § Permission mode](thread-composer-footer.md#permission-mode-650) for the one permission reading this sheet is allowed to reflect.

## `ContextWindowSection`

```kotlin
@Composable
private fun ContextWindowSection(contextPercent: Int?)
```

`contextPercent` is the same nullable reported percentage used by the footer. A reading
renders `N% used`; absence renders `Not reported yet` via `status_sheet_context_unavailable`.
It never derives a percentage from token counts. The current modal has one reading line:
the older Context window helper caption and progress bar are absent. This reading is
read-only and never triggers a settings write. The current dark Figma frame has no
unavailable variant; its old token-count mockup is historical.

## Related

Part of [StatusSheet](status-sheet.md); see that document for the shell, the Model and Effort sections,
`## Shape`, hosting in `ThreadScreen`, tests and edge cases. See also [Thread composer footer § Running
model](thread-composer-footer.md#running-model-891) and [Thread composer footer § Context usage
segment](thread-composer-footer.md#context-usage-segment-946) — the two footer readings these sections
mirror.
