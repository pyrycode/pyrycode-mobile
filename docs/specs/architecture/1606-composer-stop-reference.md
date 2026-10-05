## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`: `ThreadInputBar` selects Stop and centers its painter in the message button.
- `app/src/main/res/drawable/ic_composer_send.xml`: existing Figma-vector resource and theme tint pattern to mirror.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBarStyleTest.kt`: native pixel sampling and action-routing coverage.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerFieldCaptureTest.kt`: `typedAndStopAt412By892` provides connected, busy, empty-draft static-dark device pixels.
- `docs/knowledge/features/thread-input-bar.md`: Stop must remain available for a blank busy draft; typed text must still send.
- `docs/knowledge/features/development-verification-compose-evidence.md`: retain fresh metadata and counted XML; copy only the owned capture.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=114-3549

Inspected `Action=Stop` on 2026-10-05: a container-less 48px button centers a 28px filled-circle glyph with a rounded square cutout. The supplied SVG fills the 28px viewport; its cutout spans 8.75–19.25px with 1.75px corner radii. Keep the existing Material 3 primary tint.

## Change

Replace the inset Material StopCircle painter with `ic_composer_stop`, an Android vector preserving the supplied Figma path, and name Stop reference `114:3549` in the icon comment. Keep the existing button dimensions, tint, descriptions, draft selection and callbacks. Refresh the existing device fixture's metadata with the current date and Stop reference, and retain its fresh Stop PNG, configuration sidecar and counted passing XML under `app/src/androidTest/assets/design-1220/thread/`. Forecast: about 140 written lines, no exported declarations or consumer updates, two criteria, no failure branches. In-flight #1727 changes image grant forwarding in `ThreadInputBar`; its separate block is no dependency.

## Testing strategy

First add a native-rendered Stop silhouette assertion beside `ThreadInputBarStyleTest`'s Send pixel test: a 48dp target, full 28dp centered circle, clear rounded square and no button container. Watch it fail with the existing inset Material icon, then implement. Run the complete style class plus existing draft, Enter-key, connection-gate and footer geometry/interaction coverage. Reuse `ComposerFieldCaptureTest.typedAndStopAt412By892` on the managed device; device-only reason is saved actual device pixels. Inspect the PNG against the Figma render, record executed/failed/skipped counts and configuration. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. This changes an existing glyph only and introduces no operator flow requiring a new live scenario.

## Documentation handoff

Pending documentation stage: append the capture comparison verdict against `114:3549` to `app/src/androidTest/assets/design-1220/thread/index.md`, using the retained Stop PNG, configuration and XML. Also correct the obsolete no-Stop-reference claim under `docs/knowledge/features/thread-input-bar.md`, “The message-input button — one control, two actions”.
