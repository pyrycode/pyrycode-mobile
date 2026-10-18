# Markdown heading spacing (#2278)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownText`, `StreamingMarkdownText`, `ListBlock`, `BlockQuoteBlock` own sibling gaps; `MarkdownBlock` skips blank fallback nodes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdown.kt`: `StreamingMarkdownBlock` and its content filtering preserve completed block identity.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: native layout-box assertions and ordinary-gap regression.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: settlement bounds and completed-block reuse regressions.
- `docs/knowledge/features/markdown-text.md` and `markdown-text-internals.md`: Reader's inline list-marker path needs the same sibling policy as the regular list path.
- `docs/knowledge/features/markdown-reader-screen.md`: retain Reader gutters and outer padding; screen integration belongs to #2276.
- `docs/knowledge/features/development-verification-gates.md`: shared bounds tests use native graphics and allow pixel rounding.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=892-13442

Read design context and screenshot on 2026-10-18. The specimen stacks all six heading levels above body paragraphs: 12dp below headings, 20dp before subsequent headings (16dp ordinary gap plus 4dp in the reference). Retain existing MarkdownTypography, Material colour tokens and text styling; spacing is measured between layout boxes in dp.

## Context

One deliverable: shared ATX sibling spacing, with its nested and streaming regression proof. SETEXT retains raw-body rendering and ordinary gaps. Forecast: about 480 written lines across one production file, a focused shared test file and this plan; no new exported production types, signature changes or state-machine rejects, three acceptance criteria. No decision record needed.

Remote overlaps: #2279 changes URI admission in different functions and can merge independently; the retained #1766 remote branch describes streaming code already present on main. Keep edits local to spacing containers.

## Design

Replace uniform inter-block arrangements with explicit sibling gaps. One private gap helper chooses 20dp when the next rendered sibling is an ATX heading, otherwise 12dp when the previous sibling is an ATX heading, otherwise the caller's ordinary gap. Apply it to both roots, both list-content paths and quote children. Filter blank/syntax-only children before selecting neighbours. Never insert a gap without a previous rendered sibling. Preserve list-item arrangements, indentation, quote rule and the streaming standalone caret's existing ordinary gap.

No ViewModel, UiState, Event, dependencies or public API changes. Streaming keys and the independently skippable `StreamingBlock` remain intact.

## State and concurrency model

No new state, jobs, flows or dispatchers. Gaps derive solely from adjacent current AST block types. Existing streaming cache and composition lifetimes remain unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Heading tail grows and caret blinks | `MarkdownHeadingSpacingTest.headingTailKeepsSpacingThroughCompletionAndSettlement` checks each ATX level, stable heading position and 20dp preceding gap. |
| Heading becomes a completed block, then settles | Same test adds following blocks, checks 12dp following gap, then switches to settled rendering and compares layout bounds. |
| Source replaced with another level or nested fixture | Root and nested spacing tests update source in the same composition; no stale neighbour spacing survives. |

No connection/session identity or lifecycle state is introduced.

## Error handling

No new I/O or error path. Keep total AST rendering and existing unsupported-node fallback, including SETEXT. No new lifecycle event or classified failure needs logging.

## Testing strategy

Write native-graphics shared Compose bounds tests before implementation and observe the 20dp/12dp rule fail under uniform spacing. Cover settled and streaming root, all ATX levels, consecutive headings, list/quote nesting, single/first/last headings, syntax-only children, ordinary and sibling-item gaps, indentation and quote bounds. Exercise shared Reader presentation with explicit 12dp ordinary and 6dp item gaps, including its inline-marker path. Check font scale changes text height while gaps stay dp. Keep tests at small fixture sizes within the default window.

Run the new focused class plus existing `MarkdownTextTest`, `StreamingMarkdownTextTest`, heading-scale, parsing, typography and Reader geometry coverage. Reconcile existing absolute Reader coordinates only where heading spacing intentionally changes them. Run lint, APK assembly, instrumented compilation, formatting and final pre-verification. No device-only or real-Claude test is needed for deterministic local renderer geometry; dispatcher owns full acceptance.

## Open Questions

None.
