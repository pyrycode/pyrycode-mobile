# #895 — Restyle the tool row: subject, status labels, elapsed time

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ToolCallRow`, `ToolCallRowContent`, `CollapsedHeaderRow`, `ToolCallStatusIcon`, `ExpandedBody`, `ExpandedSection`, `buildSummaryAnnotated`, `iconForTool`, `isCodeIsh` — the whole surface being restyled.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall` (`inputFields`, `denial`, `elapsedSeconds`), `ToolCallStatus`, `ToolDenial` — the fields to render and their contracts (elapsed non-null only while `Running`; a cache-restored `Denied` row has no denial and no `inputFields`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `CodeBlock` — the bordered code block to reuse; its body text is hard-wired to `bodyMedium` (14sp).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → the `Role.Tool` arm of `MessageBubble` — sole caller; applies the gutter and stays untouched.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ToolCallRowTest.kt` — existing component tests; they match on `"Bash"` substring, `"Output"` and the two content descriptions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedToolRowTest.kt`, `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`, `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_toolPrompt_rendersToolStepInThread` — match on `cd_tool_running`, `cd_tool_failed` and the verbatim tool name `Bash` via `onNodeWithText(..., substring = true)`. `ScriptedToolRowTest` uses the single-node form, so the tool name must appear in exactly one text node per row and the subject/status must not contain it for scripted inputs.
- `docs/knowledge/features/tool-call-row.md` — prior decisions: `rememberSaveable` expansion keyed by the `LazyColumn` item identity; `CodeBlock` copy control stays off for tool content; the per-tool leading icon is replaced here.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/toolHeadline.ts` → `PREFERRED_FIELDS`, `PATH_FIELDS`, `pick`, `toolHeadline`; `shortenPath.ts` → `shortenPath`, `KEPT_SEGMENTS`; `ConversationScreen.tsx` → `formatToolElapsed` — the rules mirrored here.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (instances `I533:1956;134:4939` collapsed, `I533:1956;134:4941` expanded; components `134:4939` / `134:4941`)

A single-line row on the `background` fill with a 1dp `primaryContainer` border, 6dp radius, 12dp horizontal / 8dp vertical padding and 12dp gaps: the tool name in 14sp Roboto Mono `tertiary`, then the subject in `bodyMedium` `onBackground`, then a trailing count (left empty on mobile — no count on the wire). Expanded, the header gains a small chevron and the body stacks a bordered code block and 12sp monospace prose in `onBackground`, 12dp apart, with 12dp bottom padding.

## Context

The row still draws the pre-design card (`surfaceContainerHigh`, leading per-tool icon, `name · précis`) and folds `Denied` into the failed glyph. #810–#812 put `inputFields`, `denial` and `elapsedSeconds` on `ToolCall`; this ticket renders them. Subagent nesting and the composer's open-tool status are separate tickets; the latter will reuse `formatToolElapsed`, which is why it lives in its own file in the components package.

## Design

### New file `ui/conversations/components/ToolRowFormat.kt` (pure, no Compose)

- `internal fun formatToolElapsed(seconds: Int): String` — desktop's `formatToolElapsed`: `|s| < 60` → `"${sign}${|s|}s"`, else `"${sign}${|s|/60}m ${pad2(|s|%60)}s"`. Absolute value taken in `Long` so `Int.MIN_VALUE` cannot overflow.
- `internal fun toolRowSubject(toolName: String, inputFields: Map<String, String>, input: String): String` — desktop's `toolHeadline`, rules 1, 2 and 4 as the ticket states them:
  1. `toolName == "Bash"` exactly: first non-empty of `description`, `command`.
  2. First non-empty of `TOOL_SUBJECT_FIELDS` = `file_path, path, notebook_path, command, pattern, url, query, description`.
  3. Otherwise `input` (the précis) verbatim — also the cache-restored row's path, whose `inputFields` is empty.
  "Non-empty" is `!= ""`, not `isNotBlank()`, as desktop decided. A value picked from `TOOL_PATH_FIELDS` (`file_path, path, notebook_path`) is shortened; nothing else is. Desktop's rule 3 (any single-line field) is not in this ticket's contract and is not ported.
- `internal fun shortenToolPath(path: String): String` — split on `/`, drop empty segments; ≤ 4 segments → input unchanged; else `".../" + last four joined by "/"`.

### `ToolCallRow.kt` restyle

Public surface unchanged: `ToolCallRow(toolCall, modifier)` with `rememberSaveable` expansion, delegating to the stateless `ToolCallRowContent(toolCall, expanded, onToggle, modifier)`. The call-site identity is untouched, so a `ToolCall` replaced in place (status flip, new elapsed reading) keeps `expanded`.

- **Container:** `Surface(shape = RoundedCornerShape(6.dp), color = colorScheme.background, border = BorderStroke(1.dp, colorScheme.primaryContainer))`, `clickable(onClickLabel = show/hide details string, role = Role.Button)`. Inner `Column` padding 12dp horizontal, 8dp top, 8dp bottom collapsed / 12dp bottom expanded; 12dp gap between header and body.
- **Header row** (`Row`, center-aligned, 12dp gaps): `[name + subject group, weight(1f)] [TrailingStatus] [chevron]`. Because a `Row` measures unweighted children first, the trailing status and chevron always get their width; the group only gets what remains. Inside the group: tool name `Text` (verbatim `toolName`, `bodyMedium` + `FontFamily.Monospace`, `tertiary`, one line, ellipsis, `widthIn(max = 160.dp)` so a long MCP tool name cannot starve the subject) then subject `Text` (`toolRowSubject(...)`, `bodyMedium`, `onBackground`, `weight(1f)`, one line, ellipsis). Name and subject are separate text nodes.
- **`TrailingStatus`** — exhaustive `when (status)`, each arm with its own glyph and content description (the row is distinguishable without colour):
  - `Running` → optional elapsed `Text(formatToolElapsed(it))` in `labelMedium` `onSurfaceVariant` when `elapsedSeconds != null`, then a 14dp `CircularProgressIndicator` with `cd_tool_running`.
  - `Done` → `Icons.Outlined.Check`, `onSurfaceVariant`, new `cd_tool_done`.
  - `Failed` → `Icons.Outlined.ErrorOutline`, `error`, `cd_tool_failed` (kept).
  - `Denied` → `Icons.Outlined.Block`, `error`, new `cd_tool_denied`.
  Elapsed is read only in the `Running` arm, so done/failed/denied never show a time. No local timer. The count slot from Figma is not drawn.
- **Chevron:** `Icons.Filled.KeyboardArrowDown` / `KeyboardArrowUp`, 16dp, `onSurfaceVariant`, `contentDescription = null` (the click label carries the meaning).
- **Expanded body:** `Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), spacedBy(12.dp))` containing sections, each a `labelSmall` `onSurfaceVariant` caption (string resources) over content:
  - **Input** — if `inputFields` is non-empty, one entry per field in map order: the key as a `labelSmall` monospace caption over its value; else the `input` précis.
  - **Output** — only when `status` is `Done` or `Failed`.
  - **Denial** — only when `status == Denied` and `denial != null`: `denial.message`, then `denial.decisionReason` when non-empty. A cache-restored denied row (no denial) shows no denial section.
  - Content rendering (`ToolContent`): code-like (contains `\n`, longer than 80 chars, or a `command` field) → `CodeBlock(content, language = null, textStyle = bodySmall)`; otherwise wrapping `Text` in `bodySmall` + `FontFamily.Monospace`, `onBackground` (Figma's 12sp mono prose). All daemon text renders only as plain `Text`/`CodeBlock` — no links, no markup, no logging.
- **Removed:** `iconForTool`, `buildSummaryAnnotated`, `primaryArg` (the leading per-tool icon and `·` précis are gone per the design). Previews updated to cover running-with-elapsed, done, failed, denied, collapsed and expanded, light and dark.

### `MarkdownText.kt` — additive parameter

`CodeBlock` gains `textStyle: TextStyle = MaterialTheme.typography.bodyMedium`; its body `Text` uses `textStyle.copy(fontFamily = FontFamily.Monospace)`. Markdown callers are unchanged; the tool row passes `bodySmall` (12sp) to match the design's 12sp code block.

### Strings

`cd_tool_done` "Tool call done", `cd_tool_denied` "Tool call denied", `tool_row_input` "Input", `tool_row_output` "Output", `tool_row_denial` "Denied by claude", `tool_row_expand` "Show tool details", `tool_row_collapse` "Hide tool details".

## State + concurrency model

No new state beyond the existing `rememberSaveable` `expanded` Boolean and a composition-scoped `rememberScrollState` for the bounded body. No coroutines, no timers, no effects. Elapsed updates arrive as new `ToolCall` values from the data layer.

## Error handling

None new — all functions are total over their inputs. Empty tool name/subject render as empty text; a denied row without a denial simply omits the denial section.

## Testing strategy

- **Unit (`app/src/test/.../components/ToolRowFormatTest.kt`):** `formatToolElapsed` at `0`, `12`, `59`, `60`, `65`, `-65` (plus `-5`, `3600`); `toolRowSubject` preferred order (`file_path` over `command`, `path` over `pattern`, `url` over `query`, `description` last), empty value skipped, `Bash` puts `description` before `command` and falls back to `command` when description is empty, non-`Bash` (`BashOutput`, `Task`) keeps the general order, path fields shortened and non-path fields (a `command` with slashes) not, fallback to `input` for empty map / only-empty values / unknown keys; `shortenToolPath` at ≤ 4 segments unchanged, 5+ segments shortened, leading slash handled.
- **Compose UI (`ToolCallRowTest`, androidTest):** existing tests kept; new: done shows `cd_tool_done`; denied shows `cd_tool_denied` and not `cd_tool_failed`; running with `elapsedSeconds = 65` shows `1m 05s`; running without a reading and a done row carrying a stale reading show no elapsed text; the subject from `inputFields` appears in the collapsed row; expanded denied row shows the denial message; a row expanded, then updated in place (status `Running → Done`, elapsed changed) stays expanded.
- **E2E:** no new scenario. The rung-3 `interactiveTurn_toolPrompt_rendersToolStepInThread` (#481) and scripted `tool` / `tool-failed` scenarios keep matching the verbatim `Bash` name and the kept content descriptions; the dispatcher's gates run them. The elapsed reading depends on claude's transient heartbeats, which a durable scenario cannot pin; component tests cover its render.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/tool-call-row.md` describes the pre-design row (surface, leading icon, status arms, deviations); it needs updating to this design, the new `ToolRowFormat.kt` helpers and the `CodeBlock` `textStyle` parameter.

## Open questions

- Whether the tool-name width cap (160dp) reads well on-device for long MCP names — resolved at implementation by previews; revisit only if it clips common names.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — every daemon/claude string (`toolName`, `inputFields` keys and values, `input`, `output`, `denial.message`, `denial.decisionReason`) reaches Compose only as the `text` of a `Text` or the `content` of `CodeBlock` (which builds an `AnnotatedString` with syntax colour spans only, no link annotations since `language = null`). No `LinkAnnotation`, no `AnnotatedString.fromHtml`, no URI handler, no `Intent`. `toolRowSubject` and `shortenToolPath` return display strings only; nothing feeds them back into a path or URL operation.
- [Trust boundaries — length] No findings — the daemon caps each input value (4000 runes) and the result summary upstream; the collapsed subject is one line with ellipsis and the expanded body is height-bounded (`heightIn(max = 320.dp)`) with internal scroll, so an oversized value cannot take over the thread layout. `CodeBlock` scrolls horizontally rather than widening.
- [Trust boundaries — spoofing] SHOULD NOT FIX (no observed failure) — a bidi-override character in a subject could visually reorder that one text node. Name, subject and trailing status are separate nodes, so it cannot reorder the client-owned status glyph or content description; desktop leaves it unguarded for the same reason.
- [Tokens / secrets] No findings — no tokens handled; the row logs nothing.
- [File / storage] No findings — no filesystem access; a shortened path is display text and is never opened.
- [Android attack surface] No findings — no intents, deep links or WebViews; the click only toggles local expansion.
- [Crypto] Not applicable — no cryptographic operations.
- [Network & I/O] Not applicable — pure rendering over already-decoded model values.
- [Logs] No findings — the plan adds no logging; content descriptions are client-owned strings, never daemon text.
- [Concurrency] No findings — no coroutines or timers; elapsed display is a pure function of the current `ToolCall`.
- [Threat model] Hostile daemon frame: covered by the inert-text render and bounds above. Malicious relay: content-blind, cannot reach this layer. UI leakage (screenshots/accessibility reading tool text): OUT OF SCOPE — the thread already shows claude's text; no new exposure class.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
