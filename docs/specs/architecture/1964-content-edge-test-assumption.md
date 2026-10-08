# Repair the message content-edge test assumption

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadContentEdgeTest.kt`: `messageEdge_isInsideBubblePaddingWithAndWithoutMetadata` already compares the reported edge to measured bubble bottom minus padding; its final movement assertion fails. Preserve `toolEdge_usesToolSurfaceIncludingExpandedContent` unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer` applies `MessageActionPairHeight` as a minimum surface height and centres inner content, so metadata need not move the edge.
- `docs/knowledge/features/thread-screen-testing-foreground-read-tracking.md`: retain message inner-content versus tool-surface edge coverage.
- `docs/knowledge/features/development-verification-gates.md` and `scripts/android-test-gate.py`: shared tests run under Robolectric but require an explicitly selected managed-device run for this ticket.
- `docs/specs/architecture/1953-isolate-read-tracking.md`: existing edge callbacks measure geometry without changing it.

## Change

Remove the message test's assumption that showing metadata increases the absolute trailing edge, and remove the comparison's unused initial-edge bookkeeping. Keep the one-pixel comparison to measured bubble bottom minus `BubbleVerticalPadding` in both metadata states; assert the edge is inside the bubble in the same helper for both states. Remove the no-longer-needed stored bubble bounds. Production geometry, callbacks and read behavior stay unchanged. The collapsed/expanded tool test remains intact, including its expansion comparison.

This is one test repair: approximately 40 written lines including the plan, no production files, no new exported declarations, no consumer migrations and no error branches; two acceptance criteria. No in-flight feature branch edits the target test.

## Testing strategy

Run the existing class first under fresh focused Robolectric execution and inspect its XML to reproduce the movement assertion failure. After the repair, rerun both methods with `testDebugUnitTest --tests de.pyryco.mobile.ui.conversations.thread.ThreadReadContentEdgeTest --rerun`, then select that same class with `:app:pixel2Api33AtdDebugAndroidTest --rerun`. Record both method names and executed, failed and skipped counts from fresh XML in the PR. Keep the tests in `sharedTest`; no new device-only test, operator flow or live scenario is needed.

Run lint, assembleDebug, Android test Kotlin compilation and Spotless. After the final main merge and push, run the full unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` with the PR body. The later main Android sweep remains dispatcher-owned.
