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

- **`running.model == null`** → `UnavailableNote(stringResource(R.string.status_sheet_running_model_unavailable))` — `"Not announced yet"`, the same honest-unavailable idiom [#601](../codebase/601.md) established for Context window, reused here rather than a blank row or a fallback to `selectedModel`.
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

Parameterless from [#601](../codebase/601.md) until [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) re-added `contextPercent: Int?`, the same [`ThreadRunConfig.contextPercent`](thread-composer-footer.md#context-usage-segment-946) the composer footer's `Cxt:` segment reads — the two surfaces show the identical value, so they cannot disagree. `Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` with two children:

1. Label — `contextPercent != null` renders `stringResource(R.string.status_sheet_context_used, contextPercent)` ("%1$d%% used"); `null` renders `stringResource(R.string.status_sheet_context_unavailable)` ("Context usage unavailable", moved off the pre-#946 inline literal). `bodyLarge`, `onSurface`. `contextPercent` is Claude's own reported percentage, verbatim — never `0`, and never derived from a token count. Replaces the Figma `20:152` figure when there is a reading; renders the honest-unavailable text when there is none.
2. Caption `Text("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).", style = bodySmall, color = onSurfaceVariant)` — Figma `20:155`, **byte-identical to the pre-#601 caption**, including the lowercase `claude's`. Unchanged by #946 — it renders in both the available and unavailable cases.

Read-only — no event surface, no callback, same as before #601 and #946. No auto-close (it's a display, not a picker) — taps inside the section do nothing.

**Wording is desktop-sourced, not invented.** pyrycode#1214 captured the desktop DOM on the same `stream-json` runner mobile ships against — `"...Context windowContext usage unavailableWhen full, oldest messages get dropped..."` — which pins both the exact unavailable-case text and the fact that desktop keeps the caption alongside it. The `"N% used"` wording is [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s own, client-authored to match the Figma text shape without carrying the token figures below.

**Deliberate, spec'd Figma divergence — narrower since [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946).** Figma node `20:100`/`20:151` reads `"73% used (146K of 200K tokens)"` over a severity-coloured progress bar — the node the pre-#601 stub literally came from. #946 renders the label's own percentage (matching the node's own text shape) but **not** the token figures or the bar: the ticket asks only for Claude's reported percentage, and the plan records the bar's severity colours as out of scope. What still diverges from the node is the token-count parenthetical and the bar; the caption and layout/padding/typography always matched. See `docs/specs/architecture/946-context-usage-footer.md` § Design source, and `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md` § Design source for the original divergence this narrows.

#### Deleted in #601: `formatTokens` and `progressColor`

Both file-private helpers — `formatTokens(n: Int) = "${n / 1000}K"` and a threshold `progressColor(percent: Int): Color` (`< 50 → primary`, `< 95 → warning`, `else → error`, the same boundaries `ThreadStatusRow.tokenPercentColor` used but for fill chroma rather than text emphasis) — existed solely to render the stub figure and its `LinearProgressIndicator`. #601 deleted both along with their only call sites (verified by grep: zero references anywhere in `app/src`), plus the `LinearProgressIndicator` import, `androidx.compose.ui.graphics.Color`, and `de.pyryco.mobile.ui.theme.warning`. `tokenPercentColor` itself was deleted from `ThreadStatusRow` in [#602](../codebase/602.md), and the row it lived on was retired outright in [#808](../codebase/808.md) — the two helpers were always deliberately unshared (see [§ Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations)), so neither deletion orphaned the theme slot.

## Related

Part of [StatusSheet](status-sheet.md); see that document for the shell, the Model and Effort sections,
`## Shape`, hosting in `ThreadScreen`, tests and edge cases. See also [Thread composer footer § Running
model](thread-composer-footer.md#running-model-891) and [Thread composer footer § Context usage
segment](thread-composer-footer.md#context-usage-segment-946) — the two footer readings these sections
mirror.
