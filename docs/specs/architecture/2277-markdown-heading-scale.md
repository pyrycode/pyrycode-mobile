# Markdown H1–H6 scale

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownBlock`, `HeadingBlock`, `appendLeaf`, and `readerLineHeight` share settled and streaming heading rendering.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdown.kt`: existing ATX leaf traversal already supports all six levels; no parsing change is needed.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`: `AppTypography` supplies shared roles that must remain unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt`: rendered `TextLayoutResult` inspection and existing mixed-content metrics.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: exact heading content, whitespace, inline styles and marker-only regression cases.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: pending inline constructs, caret placement, frozen blocks and SETEXT pending-table behaviour.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt`: link routing through actual rendered links.
- `docs/knowledge/features/markdown-text.md` and `markdown-text-internals.md`: trim edge tokens before constructing spans; preserve interior whitespace and use the shared inline walker.
- `docs/knowledge/features/shared-typography.md` and `markdown-reader-screen.md`: Android sans-serif is Roboto; retain reader untrimmed line boxes and long-run breaking.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=892-13442

The inspected screenshot stacks six heading/body samples on the assistant background. H1 and H2 step down to the existing title-medium H3, while H4–H6 share bold body-sized text. All headings use `onSecondaryContainer`; spacing in the reference belongs to a separate child of #2274.

Figma variable values: `Schemes/On Secondary Container` is `#d6e4f7`, on `Schemes/Primary Container` `#134a74` in the dark reference. Bind the existing semantic colour role so other active themes retain their own values. The typography style definitions confirm the metrics in Design below.

## Context

One observable behaviour: apply the heading scale consistently through the shared renderer. Exact content, inline styling, routing and streaming transitions are its verification floor, not separate deliverables. No decision record is needed.

Forecast: approximately 400 written lines including this plan and tests, two production files, no public API changes, one internal style holder, zero consumer signature updates, two acceptance criteria and no new error branches. The #768 analogue changed 158 inserted / 16 deleted lines; six-level metrics and both presentations add test coverage here.

Overlap: #2273 changes block gaps and separate test sections; #1766 has older streaming changes. Neither is a dependency; keep edits local and additive.

## Design

Add markdown-only typography tokens in `ui/theme/MarkdownTypography.kt`: H1 Medium 20/28/0, H2 Medium 18/26/0, and the shared H4–H6 Bold 14/20/0.25, all sans-serif. H3 uses `MaterialTheme.typography.titleMedium` with explicit sans-serif family. Do not change `AppTypography` or other shared roles.

Dispatch all six ATX types to `HeadingBlock`, selecting the matching token and applying existing reader line-height handling. Set heading text colour to `MaterialTheme.colorScheme.onSecondaryContainer`; inline link colours and code spans continue to come from the current inline walker. Keep the pending-table guard before dispatch, SETEXT raw fallback, marker-only handling, `appendLeaf`, long-run breaking and spacing untouched. No ViewModel or event changes.

## State and concurrency model

No new state, jobs, flows or dispatchers. Existing parsing memoisation, streaming cache and tail-local presentation keep their ownership and cancellation behaviour.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Trailing heading grows, including incomplete inline content | `MarkdownHeadingScaleTest.darkThreadHeadingsKeepTheirScaleThroughStreamingAndSettlement`, `darkReaderHeadingsKeepTheirScaleThroughStreamingAndSettlement` and their light-theme counterparts; `StreamingMarkdownTextTest.allHeadingLevels_keepPendingInlineContentAndTheCaret_thenSettleExactly` |
| Heading completes before a following paragraph grows | The four heading-scale methods above; `StreamingMarkdownTextTest.completedBlocks_areNeitherReparsedNorRecomposed_whileTheTailGrowsAndTheCaretBlinks` |
| Caret blinks and stream settles | The four heading-scale methods above; `StreamingMarkdownTextTest.caret_followsTheTrailingText_andNeverEntersCodeOrALink` |

No lifecycle or routing identity is introduced; process, socket and screen lifecycles remain outside this presentation change.

## Error handling

No new failures or result types. Missing heading content continues to render empty; unsupported SETEXT nodes retain the raw body fallback. Link routing retains its existing allowlist and opt-in path handling.

## Testing strategy

Write failing native-graphics shared Compose regressions first. Inspect actual rendered styles for all six levels, Thread/Reader, settled/growing/completed streaming, light/dark themes: size, height, tracking, weight, family, colour and reader line boxes. Extend exact-content and marker-only tests across six levels, preserve inline-code/link spans and pending constructs, and tap heading links to prove routing. Update the intentional old H1 expectation in `MarkdownTypographyTest` from 24/32 to 20/28.

Run focused affected renderer, typography, streaming, link and parsing tests, plus existing reader design coverage relevant to heading boxes. No device-only test is required: these contracts are observable through native-graphics Robolectric text layout and Compose interaction. Run lint, APK assembly, instrumented Kotlin compilation, forced Spotless and final `scripts/pre-verify.py --gradle` on Linux. Full acceptance belongs to dispatcher/verifier.

## Open Questions

None.

## Revisions

2026-10-18: Existing `MarkdownReaderDesignTest.referencePlacesBarRuleAndBodyAtFigmaCoordinates` encodes the old H1/H2 line boxes in downstream block positions. Preserve its gap assertions and update the expected positions for H1 32→28 and H2 28→26. This is maintenance of existing coverage; Reader integration additions remain with #2276.

2026-10-18: Rendered metric regressions found that this Compose version's `titleMedium` has 0.2sp tracking, whereas Figma's title/medium requires 0.15sp. Derive H3 from the theme role through `MarkdownTypography.h3`, overriding family and tracking locally; leave shared `AppTypography` unchanged.
