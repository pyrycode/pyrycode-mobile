# 2285 — Reader ordinary block spacing

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` supplies the shared attachment/linked-note style and outer padding.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownText`, `ListBlock` and `BlockQuoteBlock` propagate block spacing independently of sibling-item spacing.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt`: `assertBody` measures recursive gaps for both note sources; `assertBlockBounds` measures code surfaces.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: `referencePlacesBarRuleAndBodyAtFigmaCoordinates` and scrolling/header interaction checks retain precise geometry.
- `docs/knowledge/features/markdown-reader-screen.md`, `markdown-text.md`, `markdown-text-internals.md` and `development-verification-gates.md`: native graphics and unclipped bounds measure line boxes and code surfaces, rather than internal code text padding.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2770

The inspected dark Markdown body stacks headings, paragraphs, a three-item list, a bordered code panel and a ruled quote. Its ordinary stack gap is 16 px, sibling items use 6 px, and bottom padding is 16 px. Existing Material typography, colour roles and code-panel treatment stay with their owning tickets; heading-specific 20/12 gaps are owned by #2278 and integration #2276.

## Change

Change the reader's explicit `MarkdownTextStyle.blockSpacing` override from 12 dp to 16 dp, including recursive ordinary content in lists and quotes. Keep `listItemSpacing` at 6 dp and preserve the measured-bar-plus-28 dp top padding, 20 dp gutters and 16 dp bottom padding. No new state, types, signatures or reject branches are needed. This is one independently useful spacing behaviour, with about 160 written lines including tests and this plan, within all ceilings. Remote #2278 overlaps only the reference test's numeric expectations; additive, local expectation updates remain independently mergeable, and this ticket does not introduce or override its heading policy.

## Testing strategy

Update the existing attachment and linked-note recursive-spacing assertions to 16 dp, then run red before changing production. Add focused bounds checks for both note sources covering paragraph/paragraph, paragraph/list, list/code, paragraph/code and code/paragraph boundaries, retaining 6 dp sibling gaps and existing outer padding. Measure both labelled and plain code surfaces. Update affected reference coordinates precisely with unchanged tolerances; later heading integration owns its heading-specific expectations. Run the typography, reader design, reader screen and reader link interaction coverage on Linux, plus lint, debug assembly, instrumented Kotlin compilation, Spotless and final pre-verification. Existing device-only memory and capture scenarios do not assert ordinary spacing or changed control geometry; no new device-only or live evidence is required.

## Revisions

- 2026-10-18: Inspection of `MarkdownReaderCaptureTest` confirms existing reference and compact captures exercise the visually changed body. Run that focused device class as additional visual/interaction coverage; no new device test is added. The initial typography run executed nine tests: four reader tests failed on the expected 16-versus-12 dp gap, while five thread/default tests passed.
- 2026-10-18: The first green attempt passed recursive reader spacing but exposed a fixture measurement issue: a one-line plain panel is 44 dp visually inside Material's 48 dp minimum interactive layout. Use the reference's two-line panel to measure its full block surface without that inset; preserve exact 16 dp assertions and leave code-panel sizing with its sibling ticket.
