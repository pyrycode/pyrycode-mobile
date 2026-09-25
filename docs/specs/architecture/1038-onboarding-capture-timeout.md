# #1038 — Onboarding screenshot capture tolerates a redraw timeout

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt` → `ScannerFrameTest.capture` — saves the `scanner_frame` node as a PNG after the layout assertions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` → `PairCodeScreenTest.capture` — saves the root node as a PNG; already calls `rule.waitForIdle()` first.

In-flight branches: none touch these two files.

## Design source

N/A — test-only change; no UI is rendered differently.

## Change

A new androidTest helper, `ComposeTestRule.saveScreenshot(dir: String, name: String, node: () -> SemanticsNodeInteraction)`, in `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScreenshotCapture.kt`. It calls `node().captureToImage()` up to three times, catching only `ComposeTimeoutException` (the exception `captureToImage` → `forceRedraw` throws after its 2000 ms wait), with `waitForIdle()` before each retry so it starts from a settled frame. It writes `<dir>/<name>.png` only after a capture succeeds, so a skipped screenshot leaves no empty file. When every attempt times out it logs `Log.w` with the screenshot name and the exception message and returns, so the test continues. Any other exception still fails the test.

Both `capture` helpers keep their directory resolution and their node selector and delegate the capture and write to `saveScreenshot`. `PairCodeScreenTest.capture` keeps its leading `rule.waitForIdle()`. Layout assertions and click counts are untouched.

## Testing strategy

No new test: the helper is test infrastructure and the ticket's proof is the focused managed-device run of both classes on `pixel2Api33Atd`, checking that the PNGs are still written. The timeout itself cannot be reproduced deterministically on an idle emulator, so the skip path is proven by reading, not by a run.

## Documentation handoff

None.
