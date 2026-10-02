# #1548 — Draw the thread's input area over the screen gradient

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen`'s Scaffold `bottomBar` column (the input area) and the frame's `drawWithCache` glow on the outer `Box`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `ThreadColors` construction: `surface` and `background` are the same colour in every theme (the static-dark canvas, or `colorScheme.surface == colorScheme.background`), and only static dark has a `glow`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadCanvasPaletteTest.kt` — `assertCanvas` pins the composer surround to `surface` in every mode.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` — `threadStatusFramesAt412By892` captures `thread` against `16:8`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame's radial glow (primaryContainer, centred near the top third, scrimmed 30%) runs behind the whole screen, including the `Input area` (status area, attachment strip, input field and Actions/context footer). The input area paints nothing of its own; only the input field keeps its translucent container. The stream ends above the input area.

## Change

Remove the `.background(threadColors.surface)` modifier from the `bottomBar` column in `ThreadScreen` (and its now-unused import), and update the comment above it. The Scaffold's container is already transparent when the frame draws the glow, and in every other theme the frame paints `threadColors.background`, which equals `threadColors.surface`, so only static dark changes visibly. The stream still ends above the input area because the content column takes the Scaffold's `inner` padding and the message `LazyColumn` clips to its bounds; nothing else moves.

## Testing strategy

`ThreadCanvasPaletteTest.assertCanvas` currently asserts the composer-surround pixel equals the flat `surface`, which is the band. In static dark on the thread it becomes: the composer-surround pixel is bluer than the flat canvas (the glow shows through, as the existing `glow` check does at y=200). The other modes keep the equality to `surface`, which still equals the frame's paint. Write that change first and watch it fail against the current code.

Evidence: run `ThreadDesignCaptureTest#threadStatusFramesAt412By892` on `pixel8Api35` with `requireRealSystemBars=true`, then `scripts/design-compare.py` against the `16:8` export, and confirm the side-by-side shows no band.
