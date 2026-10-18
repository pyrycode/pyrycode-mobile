# Reader body typography (#2283)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` supplies the style shared by attachment and linked-note readers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownBlock`, `BlockQuoteBlock`, `TableBlock` and `readerLineHeight` control recursive body rendering and reader line boxes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt`: reader entry-point, recursive spacing and thread typography coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: native layout, wrap, quote, code and chrome assertions.
- `docs/knowledge/features/markdown-reader-screen.md` and `shared-typography.md`: preserve in-paragraph reader markers and use native graphics to detect trimmed line boxes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2770

Read the design context and screenshot: a scrolling markdown column with regular Roboto bodyMedium 14/20, 0.25 tracking, underlined coloured links, and regular On Surface Variant quotes beside a 3dp bar. Apply the ticket's On Secondary Container role to prose, lists and table cells. Preserve the existing chrome, gutters, headings, code and block gaps.

## Change

Select `MaterialTheme.typography.bodyMedium` with `onSecondaryContainer` in `MarkdownReaderScreen`. Pass the existing markdown style to the private `TableBlock` (one caller), using its body style only for Reader so table cells also inherit colour and untrimmed line boxes; keep header emphasis. Quote paragraphs already override colour and font style, and inline links already override colour and underline. This is one observable behaviour: matching reader body typography across both entry points. Forecast: about 220 written lines across two production files, two test files and this plan; no exported declarations, state or error branches. In-flight #1766 and #2279 touch other blocks in `MarkdownText.kt`; keep this change local and additive.

## Testing strategy

First update reader expectations and add native-graphics assertions for font family, size, height, tracking, colour, link spans and untrimmed line boxes in `MarkdownTypographyTest`, covering attachment and linked-note readers and both themes. Update `MarkdownReaderDesignTest` geometry for the smaller body and retain an explicitly wrapping list fixture, quote bar, chrome, scrolling and pointer assertions. Watch the focused tests fail before implementation, then pass. Run existing reader screen, palette, markdown table/link and heading tests, affected device reader capture coverage, lint, APK assembly, instrumented compilation and forced Spotless checks. Finish with the required merge and `scripts/pre-verify.py --gradle` on Linux; full acceptance belongs to dispatcher/verifier.
