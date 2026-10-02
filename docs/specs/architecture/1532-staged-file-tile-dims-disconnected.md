# #1532: the staged file tile dims while the thread is not connected

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt`: `ComposerAttachmentStrip`, `AttachmentItem` and `FileTile`, where the tile's tint is fixed to `primary`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` mounts the strip and already derives `connected` from `connectionState` for #1319's composer gating.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ThreadComposerFooter`'s `connected: Boolean = true` parameter, the precedent for the new one.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStripTest.kt`: the strip's screen tests and their `ThreadScreen` harness.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740

The Connecting (`627:1740`), Reconnecting (`627:4657`) and Offline (`627:4910`) frames draw the composer's `File` tile (`627:4597`) with its page outline and `M3/body/small-emphasized` "PDF" label in `Schemes/Inverse Primary`, which is #32628D in the dark scheme the app always runs. The connected frame `16:8` (`390:7217`) uses `Schemes/Primary`, #9DCBFC. Nothing else in the tile changes, and image tiles are the same in all four frames.

## Change

`ComposerAttachmentStrip` gains `connected: Boolean = true`, passed through `AttachmentItem` to `FileTile`, which tints its page and label `colorScheme.inversePrimary` when not connected and `colorScheme.primary` when connected, as it does now. `ThreadScreen` passes its existing `connected` value. The `sending` alpha still applies on top, and the remove control, image tiles and layout stay as they are. The #1290 note in `FileTile` keeps primary as the connected tint for contrast. Dimming the disconnected tile is the intent of the connection frames, because nothing can be sent until the connection returns.

## Testing strategy

A new Robolectric screen test, `ComposerFileTileTintTest` under `app/src/sharedTest/.../thread/`, uses `@GraphicsMode(NATIVE)`. It renders `ThreadScreen` in the dark theme with one staged PDF in each of `Connected`, `Connecting`, `Reconnecting` and `Offline`. It captures the tile's pixels below the remove control and checks that the brightest pixel is near `primary` when connected and near `inversePrimary` otherwise. Going through `ThreadScreen` checks that the screen passes `connected` as well as the tint itself. The existing `ComposerAttachmentStripTest` runs too, to show the geometry and sending behaviour have not changed.
