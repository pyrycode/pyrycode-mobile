# #1928 — Measure short-stream surface anchoring

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenShortStreamTest.kt`: both fixtures and `assertTopAnchored` currently allow text padding above the content.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer` tags its outer surface with `MESSAGE_BUBBLE_TEST_TAG`, gives it a 96dp minimum height and centres its body.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadMessageList` retains `Arrangement.Top` and measured-header-plus-28dp padding.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt`: `ThreadTopAppBar` supplies the fixture's 69dp header.
- `docs/knowledge/features/thread-screen-testing.md`: accumulated header and clearance rounding permits two pixels on density 2.625; short streams must retain spare space below content.
- `docs/knowledge/features/message-bubble.md`: enlarging the visible surface preserves the accessible Copy/Reply targets and row spacing.
- `docs/knowledge/features/development-verification-gates.md`: shared tests execute on both JVM and managed device; fresh XML counts establish execution.

## Design source

N/A: test-only assertion repair; the refiner explicitly confirms no Figma section is required. Production layout and accessible targets remain intact.

## Change

Before changing the assertion, run the existing two-case class on the managed Android 13 device with diagnostic bounds for the text and outer bubble in its failure message. Proceed only if the surface starts at the expected inset while the centred text exceeds the stale limit. Select `MESSAGE_BUBBLE_TEST_TAG` for the ordinary-message case and retain `permission-request-card` for the permission case. Replace the broad text-padding range with equality to the fixture's 69dp header plus 28dp clearance, allowing at most two pixels of accumulated rounding. Retain the assertion that spare space below the surface exceeds the space above it, so bottom anchoring fails. No new types, state, production changes or dependencies. No in-flight branch overlaps this test file.

## Testing strategy

Use the original device failure as the red assertion and record the diagnostic bounds. After repair, run both cases through focused `testDebugUnitTest` and `pixel2Api33AtdDebugAndroidTest --rerun`, reading and retaining fresh XML with executed/failed/skipped counts. Temporarily mutation-check bottom anchoring in `ThreadMessageList` on the JVM, then revert only that experiment and confirm both cases pass. Run lint, assembleDebug, Android-test compilation and Spotless; after the final merge of main and push, run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`. The dispatcher owns the next full main device sweep; no real-Claude scenario is needed for a test-only repair.
