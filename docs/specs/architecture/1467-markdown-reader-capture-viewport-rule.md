# #1467 MarkdownReaderCaptureTest: apply the viewport before the compose rule launches

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderCaptureTest.kt`: `setViewport`, `restoreViewport` and the `wm size 320x700` call in `compactLargeTextKeepsControlsAndBodyReachable`, the file to change.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt`: the `viewport` rule (`order = 0`) and private `Viewport` annotation, the shape to copy.
- `docs/knowledge/features/development-verification.md`: the #1402 "No compose hierarchies found" passage.

## Change

Replace the `@Before setViewport` and `@After restoreViewport` pair, and the in-body `wm size 320x700`, with an `order = 0` `TestRule` that reads the current overrides, applies `wm density 160` and the method's size (from a private per-method `@Viewport` annotation, default `412x892`), evaluates the base statement, and restores both in `finally`. The compose rule moves to `order = 1`, so its activity launches only after the display has its final size. `compactLargeTextKeepsControlsAndBodyReachable` gets `@Viewport("320x700")`. Assertions, capture names and sizes are unchanged. No shared rule is extracted (out of scope per the ticket).

## Testing strategy

The class is itself the test. Five consecutive focused runs of the class on `pixel2Api33Atd`, each reporting `tests="2" failures="0"` in the managed-device XML, quoted on the PR.

## Documentation handoff

Pending for the documentation stage: in `docs/knowledge/features/development-verification.md`, under the #1402 "No compose hierarchies found" passage, replace "The rule is now a plain copy in two classes; extract it to a shared `TestRule` before a third capture test needs it" with wording that many capture classes now carry their own copy, `MarkdownReaderCaptureTest` included (#1467).
