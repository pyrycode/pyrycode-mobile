# Translucent thread chrome (#1646)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen`, `ThreadStatusArea`, `FollowNewestEnd` wiring and live window anchors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt`: `ThreadTopAppBar` visible geometry and 48dp controls.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `BubbleFrame` owns 16dp trailing row space.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt` and `TurnOutcomeIndicator.kt`: status pills already disable their overlay shadow.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ThreadColors.kt` and `Theme.kt`: theme-local canvas roles and static-dark frame treatment.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadMessageAreaTopTest.kt`, `ThreadScreenShortStreamTest.kt`, `ThreadScreenFollowTest.kt`, `ThreadFrameTest.kt`: existing geometry, pointer and anchoring proof.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`, `DesignCapture.kt`, and `MainActivityInsetsDeviceTest.kt`: framebuffer evidence and actual IME/inset checks.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-testing.md`, `thread-screen-how-it-works-list-and-status-row.md`, `thread-screen-how-it-works-overlays-and-app-bar.md`: preserve reverse-list ordering, short-thread top alignment, stable keys, footer touch overflow and window-based menu anchors.
- `docs/knowledge/features/development-verification-compose-evidence.md`: real bars and nonblank framebuffer captures are required for blur proof.
- `gradle/libs.versions.toml` and `app/build.gradle.kts`: Compose and Kotlin versions and full Pixel 8 device configuration.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8, with `620:1577`, `696:4677`, `675:6160`, top bar `731:6010`, and input area `134:5013`, inspected on 2026-10-04.

Messages visibly continue behind the full-width header and input layers. The header uses a #09141D gradient from opaque to 9% at its rule, with 10dp-to-zero backdrop blur; the composer uses #0B0E11 from zero to 60% at 25% height, with zero-to-10dp blur at 20% height. Existing Material theme typography, glyph assets, field and footer remain; header content and status shapes receive black 20% shadows offset 4dp with 5dp blur.

## Context

Scaffold currently excludes both bars from the message viewport. Preserve its bar slots and snackbar layering, but let content fill the screen area beneath them. No protocol, repository or ViewModel change, and no real-Claude scenario is needed for this chrome-only change as confirmed by the refiner.

Overlaps: #1631 adds background-task callbacks and #1642 adds queued-send callbacks in separate blocks; the older #1283-notice-placement branch contains already-landed status placement work. None requires a design dependency. #1630 owns row-specific trailing space; recheck the combined ordinary-row resting gap when either ticket lands second.

## Design

Add Haze 1.5.4 core through the version catalog. Its `hazeSource` records the message area while `hazeEffect` samples it behind sharp foreground controls. Use its real progressive blur, with linear easing and no noise or tint; paint the Figma gradient separately. This avoids duplicating LazyColumn content or blurring controls. Keep modifiers reusable for later reader/header adoption without changing those screens.

Measure each bar's actual height in local Compose state, excluding IME padding. Apply oldest padding of measured header height plus 28dp and pin the top pills at that same coordinate. The viewport fills the screen area above the IME. Keep Arrangement.Top, remembered list state and FollowNewestEnd unchanged. Empty content remains centered between chrome boundaries.

At the newest end, list padding reserves the composer up to its status band. For an ordinary text message, account for BubbleFrame's existing 16dp trailing space so its visible surface rests exactly 12dp above the band. Other row kinds receive no additional trailing gap beyond their own internal spacing. The composer owns 16dp top/bottom and 20dp content gutters. Its measured height follows attachments and multiline input.

Move the header's content-row start to 24dp while preserving 48dp targets; the row's visible 28dp height and 16dp rule gap produce a 69dp bar. Apply shape-following shadow through a recorded graphics layer, colour-filtered to the theme scrim at 20%, blurred and translated, then draw the original content sharp. Apply it once around the status band; existing per-pill elevations remain zero there.

Both bar surfaces intercept pointer input without adding fake accessibility actions. Child controls retain their existing gestures. Window-coordinate footer and input anchors remain unchanged, so menus follow the IME lift.

## State and concurrency model

Only UI-local measured heights and HazeState are new. No new jobs, dispatchers, hot flows or ViewModel state. Composition owns graphics resources and pointer-input cancellation. Existing lifecycle socket closure and list follow jobs remain unchanged.

## Error handling

No new domain failure branch. Haze handles unsupported rendering through its own fallback; min SDK 33 supports the progressive renderer. Hardware evidence remains mandatory and may not be substituted with JVM pixels or synthetic bars.

## Testing strategy

First change ThreadMessageAreaTopTest to assert the full viewport, rule-relative oldest alignment and pinned pill position, and watch it fail. Add focused native Compose assertions for newest resting gap, measured draft/attachment height changes and pointer isolation. Update intentional geometry expectations in existing frame/short-stream coverage, then run those classes and ThreadScreenFollowTest.

Extend ThreadDesignCaptureTest with explicit scrolled states underneath both bars including missing `696:4677`, and recapture affected methods covering `16:8`, `620:1577`, `675:6160`. Device-only because progressive blur and framebuffer composition require hardware rendering. Run the full pixel8Api35 image with requireRealSystemBars=true, inspect nonblank PNGs, compare to current Figma, and retain PNGs, comparisons, inset/viewport metadata and executed/failed/skipped XML counts. Run focused populated-thread keyboard methods through close/reopen. Run lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck; dispatcher owns the complete gate.

## Open Questions

None. Budget forecast: approximately 1100 written lines including plan, production, tests and expectation changes; no more than two new internal types, no signature migration, five acceptance criteria and no new reject branches. Recount before implementation commit.

## Revisions

- 2026-10-04: The header height comes directly from Scaffold's measured top padding; the composer is measured inside its IME padding. Expanded viewport tests must send drags between chrome bounds and distinguish underlapping nodes from the clear reading area. Broader inline-question coverage exposed a 4px history-anchor shift when the ordinary-row rest adjustment changes on prompt arrival. Preserve that keyed reader's physical position with requestScrollToItem and a matching offset adjustment while idle, without cancelling active drags. The question actions' reveal check excludes both chrome reservations. Transparent margins in the cached shadow layer prevent clipping the 24dp band's Default shadow.
