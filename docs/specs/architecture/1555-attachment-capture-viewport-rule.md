# #1555 — Attachment capture: set each viewport before the activity launches

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/AttachmentVisualCaptureTest.kt` → `pendingAndSentAttachments_matchReferenceGeometryAt412By892`, `fileArtworkAndLabels_haveReadableContrastOnBothBubblesAndThread`, `shell`, `overrideOf` — the only file that changes.
- `app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt` → `ViewportRule`, `Viewport` — the shared order-0 rule, used unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt` → its `viewport` / `rule` pair — the #1402 shape to mirror.

## Design source

N/A — test-infrastructure change; the captured output and its sizes stay the same.

## Change

`createAndroidComposeRule<ComponentActivity>()` launches its activity before the test body, which then runs `wm density 160`, `wm size 412x892` and later `wm size 320x640` under the live activity. A resize there can recreate or refocus the activity while the launcher holds focus, which is the observed `forceRedraw` timeout with `EmptyHomeActivity` focused (#1402).

Split the geometry test into one method per viewport: `pendingAndSentAttachments_matchReferenceGeometryAt412By892` (`@Viewport("412x892")`, strip-width assertion, thumbnail wait, `emulator-412x892.png`) and `compactLargeText_fileRowFitsAt320By640` (`@Viewport("320x640")`, in-composition `fontScale = 1.5f`, file-row overflow assertion, `emulator-320x640-large-text.png`). The MediaStore image fixture, the decoder and the thread state move into one private helper that both methods call, with the fixture deleted in its `finally`. The in-body `wm` calls and the saved-override restore go; the shared `ViewportRule` applies density 160, the final size and system font scale 1.0 before launch and restores all three after.

The rule is at `order = 0` with the compose rule at `order = 1`, but wrapped so it applies only to methods carrying `@Viewport`. Without an annotation `ViewportRule` would force 412x892 at density 160 onto `fileArtworkAndLabels_haveReadableContrastOnBothBubblesAndThread`, whose pixel regions (for example rows 35–55 of the pending tile) were measured at the device's own density; that test makes no viewport change today and keeps none.

## Testing strategy

Device-only by nature: real `wm size` / density and real window pixels on the managed device, which Robolectric cannot give. Run the class five times with `./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.components.AttachmentVisualCaptureTest` and record each XML's executed count (3) and failures (0) on the PR, plus the presence and pixel sizes of the two captures and their sidecars.

## Revisions

- 2026-10-02: The first device runs passed, but the split 320x640 capture showed two of the three pending image tiles still on their PNG placeholder. Before the split, that capture ran after the 412x892 one had already waited for the thumbnails; now it starts cold. The thumbnail wait moves into a private `awaitPendingThumbnails` helper that both geometry methods call before capturing, so both captures show the decoded rock tiles. Assertions and capture names are unchanged.
- 2026-10-02: With the viewport fix in place, run 5 of the second five-run batch still failed `pendingAndSentAttachments_matchReferenceGeometryAt412By892` with the ticket's `forceRedraw` timeout. That logcat shows no focus loss: the activity stayed RESUMED from launch to teardown. It was the first method after a cold boot, with a 1.9 s frame ("Davey") and skipped frames while the device finished post-boot work. The thumbnail wait polled `captureToImage`, and every poll calls `forceRedraw`, which throws once a single frame takes more than 2 s. The launcher focus in the gate's record is likely what the display showed after the failed test tore its activity down, not the cause. `awaitPendingThumbnails` now polls semantics instead: it waits until each image tile's merged node carries no `SemanticsProperties.Text`, because the placeholder file tile contributes its "PNG" type label and the decoded `Image` contributes none. No window capture remains in either geometry method (`capture` draws the root view itself). The viewport change stays, since it removes the separate resize-under-activity hazard the ticket names.
- 2026-10-02: The next batch failed once more, on run 1 after a cold boot. This time it was `fileArtworkAndLabels_haveReadableContrastOnBothBubblesAndThread`, on its first `captureToImage`, with the same `forceRedraw` 2 s timeout. So the hazard is any window capture during a cold emulator's slow first frames, not only polling. The contrast test's three captures now go through a private `drawNode` helper. It reads the node's `boundsInWindow`, draws the activity's decor view into a bitmap on the idle main thread (as `capture` already does) and crops to those bounds. The class no longer calls `captureToImage`. The assertions and their pixel regions are unchanged.
