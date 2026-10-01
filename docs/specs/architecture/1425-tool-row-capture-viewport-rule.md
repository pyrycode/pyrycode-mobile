# #1425 — ToolRowDesignCaptureTest: resize before the compose activity launches

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ToolRowDesignCaptureTest.kt` — `setViewport`, `restoreViewport` and the in-body `wm size 320x700` in `compactLargeTextKeepsDescriptionAndStatusReachable`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt` — the `viewport` `TestRule` at `order = 0` and the private `Viewport` annotation that #1402 introduced; the shape copied here.

## Change

Replace the `@Before setViewport` / `@After restoreViewport` pair and the in-body resize with the #1402 shape: a private `@Viewport(size)` annotation and an `order = 0` `TestRule` that reads the original size and density overrides, applies `wm density 160` and the annotated size (default `412x892`), waits for idle, evaluates the test and restores both in `finally`. The compose rule moves to `order = 1`, so its activity launches only after the final viewport is in place. `compactLargeTextKeepsDescriptionAndStatusReachable` gets `@Viewport("320x700")` and loses its `wm size` and `waitForIdleSync` lines. Assertions, capture names and dimensions are unchanged. Test-only; no production file moves, and the other capture tests that call `wm size` stay out of scope per the ticket.

## Testing strategy

Device-only by nature (real `wm` shell and real pixels). Five consecutive focused runs of the class with `./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.components.ToolRowDesignCaptureTest`, each read from fresh XML as `tests="2" failures="0"`, recorded on the PR.
