# 1207 — Thread message presentation

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageContainer`, `AssistantMessage`, `StreamingAssistantBodyView`: common bubble geometry, source-text copy handoff, streaming render.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownText`, `MarkdownBlock`, `CodeBlock`, `routeMarkdownLink`: presentation seams and link/copy behavior.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` → `MessageMetaRow`, `CopyTextControl`: timestamp and clipboard treatment.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `SessionBoundaryDelimiterContent`, `RuleLabelRow`, `BoundaryRule`: separator and retained explanation.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`, `Color.kt`, `Type.kt` → `PyrycodeMobileTheme`, `AppTypography`: fixed dark roles and shared M3 text ramp.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: row order and prior-boundary de-emphasis stay with the caller.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`: its explicit `MarkdownTextStyle` keeps reader spacing independent.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`, `MarkdownTextTest.kt`, `SessionBoundaryDelimiterScreenTest.kt` → existing behavior and geometry assertions to extend.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameCaptureTest.kt` → emulator fixture and capture approach.
- `docs/knowledge/features/message-bubble.md`, `markdown-text.md`, `session-boundary-delimiter.md`, `shared-typography.md` → prior design adaptations and typography lesson; metadata and separator adaptations are superseded by the fixed-dark reference.
- `docs/knowledge/features/development-verification.md` → device capture caveats and visual evidence rules.

## Design source

**Figma:** [thread `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8), [message area `132:3959`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-3959), [assistant `132:4443`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4443), [user `132:4441`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4441), [metadata `132:4379`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4379), [reset `119:3843`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=119-3843).

Inspected 2026-09-29 in the fixed dark variant. The 412 × 892 thread has 20 dp gutters and opposing 100 dp role insets; 6 dp blue filled bubbles have 20 × 16 dp padding, 12 dp content gaps, `bodyMedium` text and `bodySmall` metadata. The assistant aligns left, the user right, and the reset is a centered `bodySmall` label between 1 dp rules with 12 dp gaps. The frame has a soft bubble shadow. The component nodes include attachment slots owned by #1211; `132:3959` includes a code block and separator but is drawn at 741 dp, so its component styling transfers while its desktop width does not.

## Context

#644 installed the shared bubble structure, #1161 the fixed-dark fills, #1206 the thread frame and #1226 the shared typography. The current renderer still differs at metadata tint and touch-height, rule color, Markdown block gap, and code font size. This pass aligns those values while preserving the product's reset explanation, memory affordance, Markdown interactions and chronological state. No new architecture decision is needed.

## Design

- Keep `MessageContainer`'s role alignment, width cap, fill, radius and padding. Add the design's soft shadow to its visible surface without changing the layout bounds. Keep the 16 dp row cadence shared with queued rows.
- In `MessageMetaRow`, use `colorScheme.inversePrimary` in the fixed static dark palette, matching `#32628D` for timestamp and copy glyph; keep the current readable fallback for other palettes. Let the glyph retain an expanded hit area but keep the visible metadata row at 16 dp through layout that does not add height or overlap adjacent controls. `CopyTextControl` continues to copy the supplied source text with its existing limit.
- Make the thread's default `MarkdownTextStyle.blockSpacing` 12 dp to match message paragraph gaps; the reader's explicit 12 dp style remains unchanged. Render code text at `bodySmall` 12 sp / 16 sp role with its existing monospace family and Figma's 20 sp line height, while preserving the code surface, syntax coloring, horizontal scroll and per-block copy. Heading and inline styles keep the existing M3 role mapping and link routing. Streaming uses the same presentation defaults.
- Use `colorScheme.inversePrimary` at 60% for `BoundaryRule` in fixed dark, with a legible fallback outside it; keep `colorScheme.primary` for the label. Keep the reason/time label, agent-specific explanation, conditional Install link, and `ThreadScreen`'s order/de-emphasis logic intact.
- Add deterministic device capture fixtures with the design's long text, short text, code and reset examples. Produce 412 × 892 side-by-side and labelled difference artifacts against current Figma renders. Explicitly annotate the conflict between Figma's generic “Session reset” and the product's reason/time label plus explanation, and the 741 dp message-area reference.

## State and concurrency model

Presentation changes introduce no state or job. `StreamingAssistantBody` retains its composition-bound reveal and caret producers. `ThreadScreen` continues to own reversed history order and above-boundary opacity. Clipboard and URI actions remain user-triggered.

## Error handling

No new I/O or failure type. Invalid Markdown and disallowed links retain `MarkdownText`'s text fallback and `routeMarkdownLink` allowlist. Clipboard writes keep `MAX_CLIPBOARD_CHARS`; visual capture uses fixed synthetic messages only.

## Testing strategy

- Add a shared Compose screen test that first fails on the old metadata tint/visible row height, paragraph gap and separator rule. Extend existing bubble and boundary checks for 412 dp and 320 dp, enlarged text, copy, streaming, Markdown link routing and code copy, without duplicating existing assertions.
- Run focused shared tests under `testDebugUnitTest`; run `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` and `spotlessApply`.
- Add a focused device capture method beside `ThreadFrameCaptureTest` for a real emulator at 412 × 892 and compact/enlarged variants. Compare captures to Figma `16:8` plus `132:3959` component crops; include labelled side-by-side and difference images in the PR. Existing keyboard and menu tests remain focused regression coverage. A live-Claude scenario is unnecessary: no operator action or wire behavior changes.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/message-bubble.md` § “Token mapping” and § “Meta row and copy control”; `docs/knowledge/features/markdown-text.md` § “Code blocks” and § “File-private spacing constants”; `docs/knowledge/features/session-boundary-delimiter.md` § “Rule / label / rule” and § “Spacing constants”. Record the exact fixed-dark mappings and capture evidence. No shared documentation is edited by this builder.

## Open questions

- The Figma file does not expose a last-modified date through the design-context or metadata response. Record the inspection date and state that design modification date is unavailable.
- The supplied design does not show heading and inline variants at phone width; preserve existing M3 roles and prove they remain readable and interactive.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Daemon-authored `Message.content` already reaches `MarkdownText` as an AST rendered by Compose `Text`, never HTML or WebView. This plan changes style values only and retains `routeMarkdownLink` as the single link action gate.
- [Tokens] No token generation, persistence, display or logging path changes. Captures contain only synthetic fixture text.
- [File and storage] The only new files are fixed test captures under `app/src/androidTest/assets/`; no daemon path is written or opened by this change.
- [Android attack surface] No exported component, intent, provider, pending intent or WebView is added.
- [Cryptography] No cryptographic or Noise code is touched.
- [Network and I/O] No wire frame or connection code changes. Existing URL scheme allowlisting and Markdown-path handoff remain.
- [Logs and telemetry] Message and copied text remain absent from logs; capture fixture content is synthetic.
- [Concurrency] Existing `StreamingAssistantBody` jobs remain tied to composition. No new coroutine, shared state or mutex is added.
- [Threat model] A hostile daemon can still supply Markdown text, but this plan preserves literal Compose rendering and copy bounds; malformed text remains within the current parser's fallback behavior. Relay, token-theft and screenshot-leakage controls remain in their existing owners.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-29
