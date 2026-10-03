# #1661: ChannelInfo and ComposerField captures settle the viewport through ViewportRule

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt`: `ViewportRule` and `@Viewport`, which apply density 160, the method's size and font scale before the inner rules run, then restore all three.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ToolRowDesignCaptureTest.kt`: the `order = 0` / `order = 1` rule pair and `@Viewport` on the compact method, the shape to mirror.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoCaptureTest.kt`: `setViewport`, `restoreViewport`, `shell`, `overrideOf` and the in-body `wm size 320x692` in `channelInfoAtCompactWidthWithLargeText` and `longProviderAtCompactWidthWithLargeText`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerFieldCaptureTest.kt`: the same `@Before`/`@After` pair and the in-body `wm size 280x400` in `compactLargeTextKeepsSendReachable`.

## Change

Both classes resize the display under the activity `createComposeRule()` has already launched, which is the race #1402 and #1425 fixed elsewhere: the launcher can take focus and the compose rule finds no hierarchy. Each class drops its `@Before setViewport`, `@After restoreViewport`, private `shell` and `overrideOf` helpers, the `oldSize`/`oldDensity` fields and the now-unused `instrumentation` field, and gains `ViewportRule()` as the `order = 0` rule ahead of `createComposeRule()` at `order = 1`. The compact methods carry `@Viewport("320x692")` (both ChannelInfo compact methods) and `@Viewport("280x400")` (`compactLargeTextKeepsSendReachable`) in place of their in-body `wm size` calls; the rest default to 412x892. `@Viewport`'s font scale stays at its 1.0 default, because the tests' `fontScale` comes from the `LocalDensity` override inside the composition, so the frames are unchanged. Every `capture` call keeps its current width and height assertions.

## Testing strategy

Device-only by nature: these are real-pixel capture tests that need the device shell for `wm`. The existing `capture` assertions on bitmap width and height prove the rule applied each method's viewport. Focused run of both classes on `pixel2Api33AtdDebugAndroidTest`, with executed, failed and skipped counts read from the managed-device XML and reported in the PR.
