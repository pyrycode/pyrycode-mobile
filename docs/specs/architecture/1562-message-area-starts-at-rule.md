# #1562 — The message area starts at the header's rule

## Files read

- `ui/conversations/thread/ThreadTopAppBar.kt` — `ThreadTopAppBar`'s `HorizontalDivider` pads `BarBottomGap` (16 dp) under the rule; `BarBottomGap` is shared with the Markdown reader's bar in `MarkdownReaderScreen.kt`, which keeps it.
- `ui/conversations/thread/ThreadScreen.kt` — the 12 dp `Spacer` above `thread-message-region`, the reversed `LazyColumn`, the `EmptyThreadState` branch and `TopOverlayTopGap`.
- `ui/conversations/thread/ThreadHistoryRows.kt` — `isNearOldestEnd` measures the oldest row against `viewportEndOffset`; top content padding in a reversed list is the after-content side, so the measure stays geometric and needs no change.
- Tests measuring the region: `ThreadFrameTest.referenceFrame_placesHeaderAndMessageRegionAtFigmaAnchors`, `ThreadScreenShortStreamTest`. Overlap: #1494 also edits `ThreadScreen.kt`, in other blocks.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (and `685:4337`)

`Top bar` (533:1948) ends with its 1 dp rule at y 68–69 of the 412 × 892 frame, and the message area's frame (`Frame 1`, 621:3571) starts at y 69 directly under it, so scrolled messages run up to the rule. Pills, a short or empty thread and the `Input area` keep their positions.

## Change

The thread's rule drops its 16 dp bottom padding and `ThreadScreen` drops the 12 dp spacer, so `thread-message-region` starts at the rule's bottom edge (y 69 in the reference frame, from 97). To keep everything else still, a new `MessageAreaTopInset = 28.dp` in `ThreadScreen.kt` is applied as the `LazyColumn`'s top `contentPadding` (the oldest end under `reverseLayout`), as top padding on `EmptyThreadState`, and as `TopOverlayTopGap`. Content padding is inside the list's clip, so scrolled rows draw through it up to the rule while an unscrolled short stream sits where it did. The reader's `BarBottomGap` is untouched.

## Testing strategy

- `ThreadFrameTest.referenceFrame_placesHeaderAndMessageRegionAtFigmaAnchors`: the region top moves from 97 to 69 (the Figma `Frame 1` anchor).
- `ThreadScreenShortStreamTest`: the short stream's top gap is now measured from the region top plus `MessageAreaTopInset`, so it still proves the stream starts at the old position.
- New sharedTest `ThreadMessageAreaTopTest` at 412 × 892, recording each value's pre-change position: an overflowing thread at its newest end draws a row across the region top, the rule (AC 1); the empty-state text's centre, a top-overlay pill's top and the composer field's top stay at their pre-change y (AC 2).
