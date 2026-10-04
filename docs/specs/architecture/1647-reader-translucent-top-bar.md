# Reader content beneath translucent chrome (#1647)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` and `MarkdownReaderTopBar` currently stack the body below the bar.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChromeEffects.kt`: `chromeBackdrop` samples a sibling source and blocks underlying pointer targets; `defaultChromeShadow` keeps foreground alpha sharp.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: sibling Haze source/effect integration and measured chrome reservations.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt`: reader-owned existing spacing and 48dp targets.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: resting geometry and physical pointer coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt`: existing menu, copy, refresh, save and scroll actions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadCanvasPaletteTest.kt`: existing canvas checks must distinguish the new gradient from the canvas.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `runConfigurationAndReaderAt412By892` and fake linked-note fixture.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: real system bars and nonblank hardware framebuffer contract.
- `docs/knowledge/features/markdown-reader-screen.md`: reader metrics, palette and menu contracts remain unchanged.
- `docs/knowledge/features/development-verification-compose-evidence.md`: hardware captures are required for blur; relocation viewport can include obscured content.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574 and shared bar https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=731-6010, inspected 2026-10-04 through context and screenshots.
The full-width bar has a dark downward gradient, progressive backdrop blur and Default shadow on its title/glyph row. Existing vectors and Material `titleLarge`, `bodyLarge`, `onPrimaryContainer` and reader palette are reused. The title starts at 24dp, the rule ends at 69dp and body begins at 97dp, with 20dp gutters and 16dp bottom padding. Frame 696:5101 places its error pill 28dp beneath the rule; migration and capture belong to #1604, absent in this tree.

## Context

The current viewport prevents reader text from scrolling under chrome. Adopt the already-shipped shared effects without changing Markdown rendering or actions. No decision record is needed. Estimate: approximately 450 written lines including plan, test changes and evidence notes; zero new exported types, no public signature changes, four acceptance criteria and no new failure branches. #1619 overlaps only in `ThreadDesignCaptureTest` with separate additive methods and fixture state; there is no dependency.

## Design

Replace the stacked bar/body with sibling layers in the existing `Box`: full-size vertical scroll source, then fixed bar, then existing snackbar. Measure the bar including its rule but excluding `BarBottomGap`; reserve measured height plus 28dp as scrollable body top padding. Retain reader control geometry, overflow's Box anchor, inert title, and existing Markdown styles. Apply `chromeBackdrop` with the resolved `headerBackdrop` to the full-width bar and `defaultChromeShadow` once to the content row. No duplicate Markdown tree or extra system insets.

## State and concurrency model

Haze source, scroll position and measured bar height are composition-local state. Density converts the measured pixel height to dp on layout updates, including font scaling. Existing copy/save/refresh coroutine ownership and cancellation remain unchanged; there are no new jobs, flows or dispatchers.

## Error handling

No new I/O or failure modes. Existing notices remain bottom snackbars until #1604. The bar measurement is the future notice anchor; do not include a second 16dp bottom gap.

## Testing strategy

Write geometry/underlap tests first and run them red before implementation. At default and 1.5x font scale prove measured bar-to-heading 28dp clearance, full drawing viewport, fixed 48dp targets, scroll reachability and 16dp final padding. Pointer tests place an actionable link and a code panel beneath the bar and prove bar background/title/controls do not dispatch underlying actions, with positive taps outside chrome. Run existing reader design/screen and palette tests; palette assertions must use the gradient instead of flat-canvas assumptions within reader chrome.
Extend `runConfigurationAndReaderAt412By892` to retain the resting reader and explicitly scrolled-under-bar frame using the same fake note, enlarged only for the scrolled state. Device-only reason: progressive backdrop blur needs hardware framebuffer pixels. Run that method on full `pixel8Api35` with `requireRealSystemBars=true`, hold the shared device lock, inspect executed/failed/skipped counts and retain reader PNGs, sidecars, XML, fresh Figma exports and comparisons under `app/src/androidTest/assets/reader-chrome-1647/`. This local visual adoption needs no daemon or new real-Claude scenario. Run lint, assembleDebug, Android test compilation, formatting and forced spotlessCheck.

## Open Questions

None.
