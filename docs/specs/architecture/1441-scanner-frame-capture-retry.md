# #1441 — ScannerFrameTest pixel check captures with retry

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScreenshotCapture.kt` — `saveScreenshot`, the #1038 retry-then-skip around `captureToImage`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt` — `checkFrame`, whose dark 412dp branch calls `captureToImage` unprotected.

## Change

Extract the retry loop from `saveScreenshot` into an internal `ComposeTestRule.captureWithRetry(name, node): ImageBitmap?` in `ScreenshotCapture.kt`: up to `CAPTURE_ATTEMPTS` tries with `waitForIdle()` between them, returning `null` and logging the skip when every attempt throws `ComposeTimeoutException`. `saveScreenshot` keeps its signature and calls the helper. `checkFrame`'s "blue atmosphere above plain footer" check captures through the helper and runs the assertion whenever an image comes back. No production code moves.

## Testing strategy

Device-only by nature (real pixels via `captureToImage`). Focused managed-device run of the whole `ScannerFrameTest` class on `pixel2Api33Atd`, reporting executed, failed and skipped counts from the result XML. The timeout path itself cannot be forced deterministically; it is the same loop `saveScreenshot` has run since #1038.
