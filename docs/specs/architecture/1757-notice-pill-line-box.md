# Notice pill full line box (#1757)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` shares one text layout across both Surface variants.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt`: native metrics and visible bounds keep touch targets separate.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt` and `ViewportRule.kt`: real MainActivity, fixed viewport, framebuffer capture and configuration sidecar.
- `docs/knowledge/features/notice-pill.md`: wrapping, icons, shadow and callback contracts remain unchanged.
- `docs/knowledge/features/development-verification-compose-evidence.md`: real bars and nonblank hardware pixels are required for visual evidence.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619 (Error), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6617 (Default).

Read both design contexts and screenshots. Each rounded pill centres body-small 12/16 text vertically within 8/4 padding and 6dp corners; the compact trailing X occupies an 8dp slot. Existing MaterialTheme colour roles and exported close asset stay in use.

## Change

Copy the local bodySmall style with LineHeightStyle alignment Centre and trim None so its full line height survives at the top and bottom. Do not set a fixed height: wrapped lines and font scaling must continue to grow naturally. No signatures, tokens, padding, shadow, icons or actions change. Overlapping branches #1603 and #1735 change documentation and colour/semantics configuration independently; this edit stays local to Text.style. Forecast: roughly 230 written lines including tests and plan, no new exported production types, no consumer updates, two acceptance criteria and no new failure branches.

## Testing strategy

Add a native-graphics shared component regression, first run red, asserting each variant's single-line text height is 16dp and visible background is 24dp with 4dp vertical inset. Also exercise scaled/wrapped capped text and the existing click/dismiss paths. Run ThreadTopOverlayTest, TaskCountPillTest, ThreadActivityIndicatorVisualTest and ScriptedTurnOutcomeTest as affected caller coverage. Add a device-only design-harness component capture on MainActivity: real bars and hardware framebuffer pixels cannot be proved by Robolectric. Retain its 412x892 density/font-scale-1 PNG and configuration sidecar, measure both background regions independently of touch bounds, and compare with Figma's 24px reference. Run focused device coverage, lint, assembleDebug, compileDebugAndroidTestKotlin and forced Spotless checks. This is typography geometry with existing actions, not a new daemon-facing flow.

## Revisions

- 2026-10-05: resolved the caller test names against the repository. The capture uses the design harness to host both production variants directly in a full-screen MainActivity fixture; retained solid-pixel measurements prove 24px backgrounds independently of expanded touch targets. No production design change.
