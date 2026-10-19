# #2276 — Reader heading integration coverage

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` — `MarkdownReaderDestination`, `LinkedMarkdownReaderDestination`, `MarkdownReaderScreen` and `ReaderBodyTopGap` share the reader presentation and measured-header inset.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` — `MarkdownBlock`, `HeadingBlock`, `readerLineHeight` and `MarkdownBlockGap` already supply the shared six-level contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownHeadingScaleTest.kt` — exact metrics and active colour assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownHeadingSpacingTest.kt` — retain recursive renderer coverage without duplicating its container matrix.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt` — static-theme reader entry fixtures and first-paragraph inset checks.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt` — preserve all reference, wrapping, chrome and interaction assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt` — `assertDpEquals` keeps density-aware rounding strict at whole density.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderLoadTest.kt` — delegated fake repository and temporary-file retrieval pattern.
- `docs/knowledge/features/markdown-reader-screen.md`, `markdown-reader-screen-testing.md` and `shared-typography.md` — declared line height alone does not prove untrimmed boxes; assert measured heights and wrapping too.

## Design source

Figma contexts and screenshots inspected on 2026-10-19:
[heading specimen](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=892-13442),
[reader body](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2770), and
[mobile spacing demo](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=883-13446).
The specimen steps from Medium H1 20/28 through H2 18/26 and H3 16/24 to Bold H4–H6 14/20, all in Roboto with `onSecondaryContainer`. The reader shows a page title above sections, with its first line box at the 97dp reference top; the mobile demo combines the ordinary 16dp gap and 4dp heading inset into 20dp before a heading, with 12dp below.

## Change

Add `MarkdownReaderHeadingTest` under `app/src/sharedTest/` to prove one observable contract: the existing shared ATX heading presentation survives integration through both reader destinations. Use a retrieved temporary Markdown attachment through `MarkdownReaderDestination` and a held note through `LinkedMarkdownReaderDestination`. A single fixture contains all six levels, short and wrapping headings, adjacent headings and intervening paragraphs. No production change is expected; shared styles and gaps remain the sole owners. SETEXT and filename typography are unchanged. No in-flight branch overlaps the new test file. Forecast: about 300–350 written lines including this plan and evidence index; no exported production declarations or consumer updates, three acceptance criteria and no new error branches. The merged #2283 analogue confirms this is a small integration-test slice.

## Testing strategy

Run the eight entry/theme/font-scale combinations with native graphics. Assert exact size, line height, weight, tracking, SansSerif/Roboto family, active colour token and `Trim.None`; measure each line and total text-box height using existing pixel-rounding allowances. Require actual wrapping, no visual overflow, complete final text and reachable wrapped headings. Measure 20dp before, 12dp after and a single 20dp between headings; measure 28dp from the actual top bar to the first H1 before scrolling. Existing typography tests retain the equivalent first-paragraph assertion. Exercise a temporary controlled heading regression to establish a meaningful red result, restore it, then retain green XML with source revision and counts under `app/src/test/resources/reader-headings-2276/`. Preserve the existing reader reference assertions and run `MarkdownTypographyTest`, `MarkdownReaderDesignTest`, `MarkdownReaderScreenTest`, `MarkdownHeadingScaleTest` and `MarkdownHeadingSpacingTest`. Run lint, assembly, shared-test device compilation, formatting and final pre-verification. Complete Linux suites remain dispatcher-owned; macOS is outside scope. No live-provider or device-only test is needed for this local rendering contract.

## Revisions

- 2026-10-19: The first restored-source run passed the four 1.0-scale cases but failed the four 1.5-scale cases: a 28sp H1 line measured 37dp rather than the test's linear 42dp assumption. Convert the independently specified line-height token through `TextLayoutResult.layoutInput.density` instead of multiplying by font scale, matching Compose's Android sp conversion. Exact token, trim and font-scale assertions and the per-line pixel allowance remain unchanged. Retain the original failed XML. Run unit tests, device compilation and lint separately after the combined invocation exhausted its Java heap in both Kotlin compilers; retain those raw compiler diagnostics too.
- 2026-10-19: Direct sp conversion also differs from the rendered line box. Inspection of the pinned Compose 1.10.4 `resolveLineHeightInPx` confirms that enlarged-text line heights resolve relative to the scaled font size, then Android rounds the line box. Use a native `TextMeasurer` single-line control built from independent Figma metric literals and `Trim.None` instead of copying that framework algorithm. Assert every rendered line against this control; retain explicit nominal dp heights at scale 1.0, exact token/scale checks, unclipped node totals and unchanged rounding allowances at both scales. Preserve the second failed XML as well.
