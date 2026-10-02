# #1549: Move the footer's context label under Actions when the row is full

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ThreadComposerFooter`
  (outer `Row`, bottom-aligned), `FooterTextRow` (the one-row `Layout` that gives `ContextSegment` the leftover
  width), `footerShrinkCap`, `ContextSegment`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterWidthTest.kt`: the 320dp and
  1.5x tests, including `compactWidthAndEnlargedText_highReadingKeepsTheFigureInItsDescription`, which records the
  ellipsis this ticket removes.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `compactAt320By700` and
  `threadStatusFramesAt412By892`, the captures the third criterion asks for.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-3308 (also `636:4066`, `676:3981`; one row
at `16:8`)

The compact `Input footer` (679:4115) is a top-aligned row: a wrapping group (`Info and buttons`, `flex-wrap`,
gap 4dp vertical and 16dp horizontal) holding the Actions button and the `Cxt high: 84%` text in `Schemes/error`,
then the trailing paperclip and tune at the top, level with Actions. Body-small text throughout; colours and text
are unchanged from #1412.

## Change

`FooterTextRow` measures the label's natural width first. When the buttons at their natural widths, the gap and
the label fit `maxWidth`, it lays out exactly as today (one row, everything bottom-aligned). Otherwise it wraps:
the buttons get the whole width (still capped by `footerShrinkCap`), the label is measured against the whole width
and placed at x = 0 on a second line 4dp (`FooterLineGap`) under the button row. The layout reports a
`FooterFirstRowBottom` `HorizontalAlignmentLine` at the button row's bottom, and the outer `Row` aligns
`FooterTextRow` by it and the paperclip and tune boxes by their own bottoms, so they stay level with Actions in
both shapes. In the one-row shape that line is the layout's bottom, so the 412x892 footer is unchanged. Nothing
outside `FooterTextRow` and the outer row's alignment moves.

## Testing strategy

In `ThreadComposerFooterWidthTest` (Robolectric, native graphics):

- Replace `compactWidthAndEnlargedText_highReadingKeepsTheFigureInItsDescription` with a test at 320dp and 1.5x,
  reading 84: the label is not ellipsized, sits below Actions (its top at least Actions' bottom) and starts at
  Actions' left; the paperclip and tune bottoms equal the Actions button's bottom and lie above the label.
- Add a 411dp, 1.0x test: the label stays on Actions' row (same bottom, to the right of Actions).
- Existing `compactWidthAndEnlargedText_keepThreeActionsSeparate` (37% still fits one row) and
  `contextText_sitsAtBottomOfFooterTapRow` cover the one-row path unchanged.

Device evidence: rerun `ThreadDesignCaptureTest#compactAt320By700` and `#threadStatusFramesAt412By892` on the
emulator and compare `compact-keyboard.png` and `thread.png` with `scripts/design-compare.py` against `676:3981`,
`639:3308` / `636:4066` and `16:8`.

## Revisions

- 2026-10-02: `alignBy` places the aligned group at the top of a row taller than its content, which a forced test
  size (and any fixed-height parent) gives the footer; the old `Alignment.Bottom` kept the controls at the bottom.
  The outer row now also takes `wrapContentHeight(Alignment.Bottom)`, so the footer keeps its bottom placement in
  a taller slot. Found by `contextText_sitsAtBottomOfFooterTapRow`.
