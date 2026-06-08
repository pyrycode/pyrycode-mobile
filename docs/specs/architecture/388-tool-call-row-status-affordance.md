# Spec #388 — Tool-use row running/done/failed status affordance

**Ticket:** pyrycode-mobile #388 (`feat(ui/thread)`, `size:s`, **not** security-sensitive)
**Split from:** #368. **Blocked by:** #387 (correlated status-bearing tool rows — merged to `main`, PR #409).
**Consumes:** the `ToolCall.status` field #387 shipped. **Renders only** — no wire parse, no correlation here.

Extend the **existing** `ToolCallRow` composable so each tool row shows, at a glance, whether the call
is **running**, **done**, or **failed** — reading the status that already arrives on `ToolCall.status`.
Single production file (`ToolCallRow.kt`), plus two content-description strings. No new composable, no
new type, no signature change, no state added.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:1-292` — **the file you
  edit, in full.** Note the leading `Icon(iconForTool(toolCall.toolName))` in `CollapsedHeaderRow`
  (:109-114) — that single leading slot is where the status affordance lives. Note `ExpandedBody`
  (:126-134) renders `Input` then `Output` sections. Note the `rememberSaveable { expanded }` toggle
  (:56) — **preserve it untouched** (AC#2 "existing collapsed/expanded behaviour preserved"). Note the
  preview fixtures (:189-230) all omit `status` → they're `Done` by default (they already cover the Done
  state); the preview matrix (:233-266) and the Light/Dark `@Preview`s (:268-291) are what you extend.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:18-31` — **the input you read.**
  `enum class ToolCallStatus { Running, Done, Failed }` and `ToolCall.status: ToolCallStatus = Done`.
  This is the whole data contract; #387 already populates it (`Running` on `tool_use`, `Done`/`Failed` on
  the correlated `tool_result`). The row derives nothing — it reads `toolCall.status` verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt:24-93` — **the
  sibling idiom to mirror.** Just shipped (#407). It's the established pattern for a small live-status
  affordance: `SpinnerSize = 16.dp` / `SpinnerStrokeWidth = 2.dp` for the indeterminate
  `CircularProgressIndicator`; `stringResource(R.string.cd_…)` for the content description; light + dark
  `@Preview` pair; and the exact "design-owed frame not yet drawn, follow the app's M3 progress idiom
  until it lands" posture this ticket is in. Match its spinner sizing and its content-description style.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:50-56` — the **only**
  call site: `Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }`.
  Confirms `ToolCallRow` already receives the whole `ToolCall` (status included) → **no signature change,
  no caller edit** (verified: `codegraph_impact ToolCall` → 2 symbols, no consumer cascade).
- `app/src/main/res/values/strings.xml:1-20` — the `cd_*` content-description convention (e.g.
  `cd_thread_thinking`). Add `cd_tool_running` and `cd_tool_failed` here, same naming shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterTest.kt`
  — the `ComposeTestRule` component-test pattern (`createComposeRule`, `PyrycodeMobileTheme { … }`,
  `onNodeWithContentDescription`/`onNodeWithText`). Reference **only if** you write the optional
  instrumented test (see Testing strategy); not required by the AC.
- `docs/specs/architecture/387-correlate-tool-use-result-live-tool-call-items.md` — read for posture
  (how `Running`/`Done`/`Failed` and `output` are populated upstream); nothing to copy.

---

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-28

The collapsed **done** row (`16-28`, inside the Conversation Thread Screen `16-8`) is a rounded-12 pill
on `Schemes/surface-container` with a `Schemes/outline-variant` border, `px-12`/`py-8`, `gap-8`,
centre-aligned: the tool name in `Schemes/tertiary` Roboto Mono 13px (`read_file`), then
`Schemes/on-surface-variant` body-small (12px, 0.4 tracking) for the argument summary
(`…/schema.ts · 184 lines`). **The running and failed status affordances are not drawn** — they are a
small design-owed addition (flagged for Juhana in the ticket body). Per the ticket, the behavioural ACs
stand and the `done` row already matches the shipped component, so this spec proceeds with a
**design-owed default** that follows the app's existing Material 3 progress idiom (mirroring
`ThinkingIndicator`'s spinner) rather than blocking on a frame. Pixel-fidelity for the running/failed
glyphs is deferred to a future design pass; the visual choice below is intentionally conservative and
swappable. **The Done state is left exactly as today** (no checkmark added) — it is the settled design.

> Design-owed note (carry forward): the running spinner and failed glyph are not in `16-28`. When Juhana
> draws them, only the two new states need revisiting; the Done row and all behaviour are final.

---

## Context

Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). #387 turned the
`tool_use`/`tool_result` wire pair into one evolving thread row carrying `ToolCall.status`
(`Running` → `Done`/`Failed`, correlated by `toolUseId`). Today `ToolCallRow` renders the tool name,
argument summary, and an expandable input/output view — but ignores `status`: a running call looks
identical to a finished one. This slice adds the **visual** distinction. It is presentation-only: it
reads `toolCall.status`, derives nothing, parses nothing.

---

## Design

One file, three small changes, all inside `ToolCallRow.kt`; plus two strings.

| Change | Where | What |
|--------|-------|------|
| 1. Status-aware leading slot | `CollapsedHeaderRow` (:103-123) | swap the single leading `Icon` for a `when (toolCall.status)` slot |
| 2. Output revealed on resolution | `ExpandedBody` (:126-134) | render the `Output` section only when `status != Running` |
| 3. Preview coverage | fixtures + matrix (:189-266) | add `Running` + `Failed` fixtures; existing fixtures cover `Done` |
| 4. Two strings | `res/values/strings.xml` | `cd_tool_running`, `cd_tool_failed` |

### 1. Status-aware leading slot (AC#1)

The leading element of `CollapsedHeaderRow` becomes a function of `toolCall.status`. **The slot keeps a
fixed `ToolCallIconSize` (18.dp) footprint in all three states** so a running row resolving to
done/failed never reflows the row. Contract (one `when`, ~12 lines — not a new composable unless the
developer prefers a private `ToolCallStatusIcon(status, toolName)` helper):

- `Running` → `CircularProgressIndicator(modifier = Modifier.size(ToolCallIconSize), strokeWidth = 2.dp)`,
  wrapped so it carries `contentDescription = stringResource(R.string.cd_tool_running)`. Indeterminate
  spinner = "in progress." (Match `ThinkingIndicator`'s 2.dp stroke; size to the row's 18.dp slot.)
- `Done` → `Icon(iconForTool(toolCall.toolName), contentDescription = null, tint = onSurfaceVariant)` —
  **identical to today** (:109-114). The Done row is unchanged; `iconForTool` and its decorative-null
  semantics stay as-is.
- `Failed` → `Icon(Icons.Outlined.ErrorOutline, contentDescription = stringResource(R.string.cd_tool_failed),
  tint = MaterialTheme.colorScheme.error)`. An error-tinted glyph in the same slot.
  (`material-icons-extended` is on the classpath — `ErrorOutline` is available; the developer may pick an
  equivalent M3 error glyph.)

The summary text (`toolName · input`) renders for **all** statuses unchanged — so a running row already
shows tool name + input (AC#2, first half). Only the leading slot varies.

> Why the leading slot (not a trailing badge): it's the one decorative slot already in the row, so a
> swap there adds zero layout and leaves Done pixel-identical to the shipped design — the most elegant
> place for a three-state affordance, and it keeps the "done already matches" guarantee trivially true.

### 2. Output revealed on resolution (AC#2)

`ExpandedBody` renders the `Input` section always, and the `Output` section **only when
`toolCall.status != ToolCallStatus.Running`**. While running, `output` is `""` (the data layer fills it
on the correlated `tool_result`), so gating it (a) avoids an empty "Output" label mid-run and (b) makes
"on resolution it reveals the output" literally true. A `Failed` row's `output` is the error/result
summary #387 placed there, so Failed shows it under the same `Output` section — no separate error branch
in the UI. The `rememberSaveable { expanded }` toggle and the click-to-expand behaviour are **untouched**
(AC#2, "existing collapsed/expanded behaviour preserved").

### 3. Statelessness (AC#3)

Already structurally satisfied and must stay so: `status` is a **pure input** read from
`toolCall.status`. The composable adds **no new state** and derives nothing — the only local state is the
pre-existing `expanded` toggle, which is orthogonal to status. Do not introduce a `remember`/
`derivedStateOf` over status, do not compute status from `output.isEmpty()` or any other field. Read the
enum, branch, render.

### 4. Previews (AC#4)

Extend the existing preview matrix to cover all three statuses, keeping the existing Light + Dark
`@Preview` pair (which already wrap the matrix — no new `@Preview` functions needed, the AC's "light +
dark" is met by the existing two). Add two fixtures beside the existing (Done-by-default) ones:

- `PreviewRunningToolCall` — `status = Running`, `output = ""` (e.g. `toolName = "Bash"`, a long-running
  input). Shown collapsed **and** expanded (expanded proves Output is hidden while running).
- `PreviewFailedToolCall` — `status = Failed`, `output` = an error string. Shown collapsed **and**
  expanded (expanded proves the error surfaces under Output, error-tinted leading glyph).
- The existing `PreviewRead/Edit/Bash` fixtures already exercise `Done` (collapsed + expanded).

Net: the matrix shows running, done, and failed; both `@Preview`s render it → light + dark cover each of
the three statuses (AC#4).

### Recomposition correctness

`ToolCall` is a stable `data class` (all fields `String`/`enum`), so `ToolCallRowContent` /
`CollapsedHeaderRow` stay skippable. When #387 flips a row's status, the data layer emits a **new**
`ToolCall` value → the leading slot recomposes and the spinner is replaced by the resolved glyph; the
fixed slot size means no reflow. The `CircularProgressIndicator` owns its own animation — no app-managed
animation state, no `LaunchedEffect`. No `key()`/`derivedStateOf` needed.

### Data-flow (unchanged upstream; this slice is the last hop)

```
#387: tool_use/tool_result fold → ToolCall(status = Running → Done/Failed, output filled on result)
   ▼  Message(role = Tool, toolCall = …)  in observeMessages thread stream
MessageBubble:  Role.Tool -> ToolCallRow(toolCall)        ← no change
   ▼
ToolCallRow:  leading slot = when(status){Running→spinner, Done→toolIcon, Failed→errorGlyph}
              ExpandedBody:  Output section shown iff status != Running     ← this slice
```

---

## State + concurrency model

None added. The composable is stateless w.r.t. status (pure function of `toolCall.status`); the sole
local state is the pre-existing `rememberSaveable { expanded }`. No coroutine, no `Flow`, no
`viewModelScope` — the live updates flow in from #387's repository folds through the unchanged
`observeMessages` → thread `LazyColumn`, and arrive here as new `ToolCall` values. This slice consumes
them; it neither collects nor launches anything.

## Error handling

No new failure modes — rendering only. The three enum cases are total (`when (status)` is exhaustive over
`ToolCallStatus`; no `else`). `output` is whatever the data layer set (`""` while running, result/error
summary on resolution) — rendered as inert text, never parsed or interpreted. No network/IO/parse here;
those belong to #385/#387 upstream.

## Testing strategy

**Required (AC#4):** the previews above. This component shipped (#131/#191) **preview-verified with no
instrumented test**, and the AC asks specifically for previews — follow that precedent. Verify the
previews render in Android Studio's preview pane (or `./gradlew assembleDebug` to confirm they compile)
covering running/done/failed in light and dark.

**Recommended (optional), not required by the AC:** a small `ToolCallRowTest` instrumented test
(`./gradlew connectedAndroidTest`, device required) mirroring `SessionBoundaryDelimiterTest`'s
`ComposeTestRule` shape. The content descriptions added in change 1 make the new states cheap to assert.
Scenarios (bullets → one test each; developer writes bodies in the project idiom):

- **Running shows the spinner.** Render a `ToolCall(status = Running)`; assert a node with
  `cd_tool_running` content description is displayed.
- **Failed shows the error glyph.** Render `status = Failed`; assert a node with `cd_tool_failed` is
  displayed.
- **Done shows neither status glyph.** Render `status = Done`; assert the tool summary text is displayed
  and no `cd_tool_running`/`cd_tool_failed` node exists (Done is the plain tool-icon row).
- **Output hidden while running, shown on resolution.** Render `status = Running` expanded → assert the
  "Output" label is **not** present; render `status = Done`/`Failed` (non-empty `output`) expanded →
  assert "Output" + the output text are present. (Expand via the click the test performs, mirroring
  `tapping_…performClick()`.)

Unit (`testDebugUnitTest`) is N/A — there is no non-Compose logic to test (the leading-slot `when` and
the output gate are pure-Compose branches; covered by previews/instrumented).

## Open questions

1. **Failed visual is a leading error glyph only** — no red border/background on the whole pill. That's
   the conservative design-owed default (the failed frame isn't drawn). If Juhana's design wants a
   stronger failed treatment (tinted border/surface), it's a localized change to the same slot/`Surface`;
   flagged, not built.
2. **Done adds no checkmark.** Per the ticket, Done already matches the shipped design — it resolves back
   to the normal tool-icon row. If a future design wants an explicit success tick, it's the `Done` arm of
   the same `when`.
3. **No `turnId`/correlation concerns reach here** — those were settled in #387. The row is a pure
   function of one `ToolCall`; out-of-order/duplicate handling already happened upstream.
