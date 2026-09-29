# 1208 — Tool-row design variants

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ToolCallRow`, `ToolCallRowContent`, `HeaderRow`, `ExpandedBody`, `ToolContent` — state, status, content gates and current geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt` → `toolRowSubject`, `formatToolElapsed` — existing subject and elapsed rules; keep their behavior.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `CodeBlock` — shared border, padding, monospace and inert noncopyable code rendering; reuse without restyling Markdown.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ToolCallRowTest.kt` → `ToolCallRowTest` — existing state, status and security regression coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ToolRowNestingTest.kt` → `ToolRowNestingTest` — subagent semantics remain a thread-level contract.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_toolPrompt_rendersToolStepInThread` — durable live assertion currently expects the verbatim name in every header.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt` → `capture` — device screenshot and viewport pattern.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme` — fixed dark Material color roles.
- `docs/knowledge/features/tool-call-row.md` § "How it works" — the earlier subject, status and no-copy decisions.
- `docs/knowledge/features/shared-typography.md` § "Roles" — M3 body typography metrics established by #1207.
- `docs/knowledge/features/development-verification.md` § "Compose evidence" — capture provenance and device evidence.

## Design source

**Figma:** [Thread `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8), [simple `134:4881`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4881), [described collapsed `134:4904`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4904), [described expanded `134:4895`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4895), [code `134:4890`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4890). Inspected 2026-09-30.

The dark components use a background fill, 1 dp primary-container outline with 6 dp corners, 12 dp horizontal and 8 dp collapsed vertical padding. Simple is a 14 sp Roboto Mono tool name in tertiary followed by a 14 sp body-medium subject; described is a 14 sp body-medium description with a small right/down chevron. Expanded adds a 12 dp gap, 12 dp bottom padding, a 6 dp outlined code block with 16 × 12 dp content padding and 12 sp monospace text, then monospace result prose. The wide component canvases specify metrics and state, not a mobile fixed width; the 412 × 892 thread frame determines available width.

## Context

`ToolCallRow` currently uses the same name-and-subject header and large trailing chevron for every call. The ticket changes this leaf's visual choice using the description field already present in `ToolCall.inputFields`. #1207 owns surrounding message and Markdown typography. The Figma example's line count and copy glyph do not represent supplied mobile data or current product behavior.

## Design

- Keep `ToolCallRow(toolCall, modifier, subagentDepth)` and `rememberSaveable` expansion state. A nonempty `inputFields["description"]` selects the described header and is displayed verbatim as inert text. Calls without one retain `toolName` plus `toolRowSubject`; do not derive a line count or new summary. Keep the existing `toolRowSubject` priority rules intact.
- For described rows, render the exact Figma chevron artwork scaled to its component slot. For simple rows, omit the chevron as in `134:4881` while retaining the row's accessible expand/collapse action and available input/output details. Keep the status affordance after the content, with a separate accessible glyph for Running/Done/Failed/Denied and supplied running elapsed only.
- Size the row to `fillMaxWidth` within the caller's gutter; keep 1 dp primary-container outline, 6 dp corners, 12 dp horizontal padding, 8 dp collapsed vertical padding and 12 dp expanded bottom/gap. Allow description and simple subject to ellipsize at compact width while status and chevron remain reachable; expanded body scrolls vertically within the current height bound. The shared `CodeBlock` supplies outlined code chrome without enabling copy. Plain tool result and denial text stay inert 12 sp monospace; preserve existing input/output/denial status gates, showing only nonempty supplied content.
- Keep the `subagentDepth` semantics on the clickable column and leave the caller's nesting indentation unchanged. A small test-only capture fixture renders representative simple, described collapsed and described expanded rows in a 412 × 892 dark thread surface, plus a compact large-text state. Store device captures, current Figma renders and a labelled comparison/difference artifact under `app/src/androidTest/assets/tool-row-1208/` with capture context.
- Update the live tool scenario's durable assertion to look for the tool row's accessible resolved status rather than a name that the described header intentionally omits. Preserve its negative-control purpose by checking that the status is absent before the prompt and appears afterward. The dispatcher owns the fresh full rung-3 suite and its executed/failed/skipped counts.

## State and concurrency model

Expansion remains composition-local `rememberSaveable` state in `ToolCallRow`, keyed by the thread's existing message identity. The row introduces no job, flow or dispatcher; incoming status values recompose at the same call site, preserving expansion. The thread's existing disposal and background connection behavior remain unchanged.

## Error handling

No new I/O or parsing path is added. Missing description selects simple; missing elapsed emits no time; missing output or denial emits no invented text. Existing status glyphs remain exhaustive. Long untrusted strings are rendered as `Text`/`CodeBlock` and clipped or scrolled within the row instead of becoming a URL, file path, log value or action.

## Testing strategy

- Extend `ToolCallRowTest` with RED tests for header selection, chevron presence/absence, supplied-only data, described toggle, code/no-copy, all status affordances, in-place update and compact enlarged-text reachability. Run the existing row, nesting and scripted tool-row classes as focused regression coverage.
- Add a focused device capture class with a real 412 × 892 logical viewport and compact 320 dp/1.5× text case. Inspect each nonblank capture against the five Figma nodes, retain a labelled overlay/difference and report any unrepresented mobile state. Run that class on the managed API 33 device and inspect XML executed/failure/skip counts; compile androidTest.
- Run focused unit tests, lint, assembleDebug and forced Spotless. The dispatcher runs the full UI/scripted gates and, after verifier, the fresh full live suite. Do not claim the live criterion before its evidence exists.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/tool-call-row.md` §§ "What it does", "How it works", "Testing" and "Edge cases / limitations" with simple/described selection, expanded geometry, no-copy/status contract and capture evidence. The ticket has no separate reference-documentation criterion. Preserve `docs/e2e-interactive-stream.md`'s rung-3 coverage list; the scenario changes its assertion but does not add a scenario.

## Open questions

- The component examples omit status glyphs, while the mobile product requires all four statuses. Resolve by retaining the existing trailing status slot and note its pixel-comparison limit in the PR.
- The Figma code component contains an older copy glyph. Resolve by keeping `CodeBlock(copyable = false)` per the ticket and note the deliberate difference in the comparison.

## Revisions

- 2026-09-30, device comparison: the existing `HeaderRow` measured 32 dp at density 1 because Compose's measured text box is shorter than Figma's 20 dp line. Give the header a 20 dp minimum to meet the 36 dp collapsed row (8 dp padding on each side). The existing length heuristic also boxed a long single-paragraph result that Figma shows as wrapping monospace prose. For results, use the bordered `CodeBlock` when the supplied text contains line breaks; render a single paragraph as wrapping monospace text. Keep the command-field code block and no-copy rule.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `ToolCall` enters `ToolCallRow` as daemon-authored text. The design uses only inert `Text` and the noncopyable `CodeBlock`; no data-derived route, URI, filename, semantics tag or log field is added.
- [Tokens and storage] No credential is created, persisted or exposed by this row. Tool content can itself mention a token, so captures use fixed synthetic fixture strings and no production payload.
- [File and Android surface] Screenshot evidence writes only fixed test artifact names under the instrumentation output directory; production adds no storage, intent, WebView or exported component.
- [Cryptography and network] No transport, frame decoding, key or connection setting changes. The existing relay/Noise boundary is unchanged.
- [Errors, logs and telemetry] Preserve content-free existing status semantics. No raw tool input, result or denial enters a log; no new telemetry is introduced.
- [Concurrency] `rememberSaveable` mutation stays on the Compose event path. There is no new coroutine, shared mutable flow or socket ownership.
- [Threat model] A hostile daemon's text remains inert and subject to the existing model/transport limits. The row's height bound and scroll prevent an expanded result from taking unbounded vertical thread space. Screenshot leakage and host-side payload limits are outside this presentation ticket and remain owned by their current layers.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30
