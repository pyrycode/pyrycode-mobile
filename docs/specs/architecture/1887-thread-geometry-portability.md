# Thread geometry assertion portability (#1887)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadDeleteGeometryTest.kt`: `layout` queries semantics inside the main-thread callback; existing methods cover geometry, type roles and clipping.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt`: the open-card and rejected-pill methods retain the 97dp top and 24dp pill targets.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: the retry geometry method retains the full line box and 60dp total target.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt`: `assertDpEquals` allows device pixels at fractional density and remains exact at integral density.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt`: `ThreadTopAppBar` stacks 14dp top padding, 48dp controls, 6dp rule gap and a 1dp rule.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `MessageAreaTopInset` adds 28dp below the measured header.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `HistoryRetryRow` stacks a 20dp label line, two 12dp insets and `HistoryTailBottomGap` of 16dp.
- `docs/knowledge/features/thread-screen.md`: the header and stream geometry contract stays unchanged.
- `docs/knowledge/features/thread-screen-testing.md`: retain shared geometry and clipping coverage.
- `docs/knowledge/features/development-verification-gates.md`: fractional-density edges snap to pixels; assert component gaps separately from accumulated positions.

## Change

Fetch the delete text semantics node on the test thread before invoking its text-layout action on the main thread. Replace fixed dp tolerances in the affected modal and history methods with `assertDpEquals`, keeping every design target. At density 2.625, the header's independently rounded 14/48/6/1dp segments total 182px and its 28dp clearance adds 74px, giving 97.52381dp (1.375px above 97dp). Allow two accumulated pixels for that absolute position, while separately checking the measured 69dp header and 28dp clearance within one pixel. The retry row's 20dp line, two 12dp paddings and 16dp gutter total 159px, giving 60.57143dp (1.5px above 60dp); allow two pixels for the total while checking line height, padding and gutter separately within one pixel. Keep the existing type-role, spacing and clipping assertions. No production or source-set changes.

Overlap: #1886 edits other history gesture methods and helpers in `ThreadScreenHistoryTest`; these local retry assertions are independent.

## Testing strategy

Run the five existing affected methods before repair to establish baseline failures, including the managed device where Robolectric does not expose the original thread misuse or density. Rerun those methods on both runners after repair and inspect fresh XML for each method's executed/failed/skipped counts. Run the whole delete geometry class after changing its shared `layout` helper. Retain all tests under `sharedTest`; no new device-only test or live scenario is needed for this test-only repair. Run focused affected classes, lint, debug assembly, androidTest compilation and formatting, then merge main, push and run the full JVM suite, assembly and `scripts/pre-verify.py --gradle` before opening the PR.
