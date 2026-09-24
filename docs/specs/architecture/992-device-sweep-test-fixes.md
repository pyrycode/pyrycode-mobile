# #992 — Device sweep: thread-list selector and clipboard-guarded paste test

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenNewestRowTest.kt` → `scrollAwayFromTheNewestEnd`, `a_scroll_refused_under_a_resting_finger_does_not_stop_later_rows_being_followed` — both select the list with `hasScrollAction()`, which on the device also matches the composer's `TextFieldState` field.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt` → `hasScrollToIndexAction()` — #934's fix for the same double match; the analogue.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerPasteTest.kt` → `pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty`, `putOnClipboard` — the device clipboard service refuses a content URI the caller cannot read.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerImagePasteDeviceTest.kt` — the device proof for image paste, using a real `MediaStore` image.
- `app/src/androidTest/java/de/pyryco/mobile/di/RepositoryBindingInstrumentedTest.kt` → `assumeTrue` with a reason — the repo's idiom for leaving a test out of a run.
- `docs/knowledge/features/development-verification.md` § `TextFieldState` — records the `hasScrollAction()` pitfall.

## Design source

N/A — test-only change, no UI.

## Change

`ThreadScreenNewestRowTest` selects the thread list with `hasScrollToIndexAction()` instead of `hasScrollAction()` in both places. Only the `LazyColumn` exposes `ScrollToIndex`, so the selector matches one node on the device and under Robolectric. The drags stay `performTouchInput` on that node, so the yield flag is still set through user input.

`ComposerPasteTest`'s image test starts with `assumeTrue(reason, Build.FINGERPRINT == "robolectric")`. On the device the method is skipped, and the reason and a KDoc line name the clipboard's URI-permission check and `ComposerImagePasteDeviceTest` as the device proof. Under Robolectric the test runs unchanged with the same foreign `content://media/...` URI. Rejected: a world-readable foreign URI such as a settings-provider one to pass the clipboard check. It depends on platform provider permissions and would not cover more than the device test already does. No production file changes.

## Testing strategy

The change is the tests themselves. Proof: both classes green under Robolectric (`testDebugUnitTest --tests`), and both classes run on the managed API 33 device, with executed and skipped counts read from the fresh XML: 5 newest-row tests pass, 2 paste tests pass and the image test is skipped.

## Revisions

- 2026-09-24, during build: `ThreadScreenNewestRowTest` has 4 tests, not 5, so the device run expects 4 newest-row passes. No design change.
