# Delete capture viewport mapping (#2534)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: `assertDeleteGeometry`, `deleteSurfaceBounds`, `walk`; retain readability, targets and physical dismissal checks.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: `launch`, `insets`, `capture`; effective Activity insets include ATD synthetic bars, while visual evidence requires real bars and nonblank hardware pixels.
- `app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt`: full-display size, density and font scale are controlled before Activity launch.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadDeleteGeometryTest.kt`: existing dimensions, typography, three body lines, targets and outside-pointer tests.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `DeleteConfirmationDialog` delegates placement to the platform; no production change.
- `app/build.gradle.kts`: shared test sources compile into JVM and device tests.
- `docs/knowledge/features/development-verification-compose-evidence.md`: translate separate dialog roots into screen coordinates; synthetic geometry cannot establish visual proof.
- `docs/knowledge/features/development-verification-api35-selection.md`: the two named viewport methods are an admitted focused selection.
- `docs/knowledge/features/channel-list-screen.md`, `docs/knowledge/features/thread-screen.md`: list capture walk and Delete ownership.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=673-3665

Context and screenshot inspected on 2026-10-24. The rounded Default surface is 316×220 at x=48/y=312 in the reference, with 24 dp padding, headlineSmall title, bodyMedium three-line body and labelLarge actions. Existing surfaceContainerHigh/onSurface/onSurfaceVariant/primary tokens and platform centering remain unchanged. Runtime placement maps to the usable screen viewport rather than a fixed status-relative y coordinate.

## Change

Replace the fixed 312 dp top-offset assertion with a 2 dp vertical-center assertion against independently measured Activity `currentWindowMetrics.bounds`, inset once by the union of effective system bars and display cutout. Dialog semantics bounds continue to be translated by their own root's screen location. A shared test-only helper computes the usable viewport and asserts centering, allowing controlled regressions to exercise exactly the capture's contract. Use effective Activity insets so ATD geometry remains covered; cutout and bar overlap use per-edge maxima. Record full/usable viewport, effective and dialog insets, measured and expected surface bounds in the Delete sidecar. Retain all existing size, readability, growth, target and physical dismissal checks. No state or production interfaces change.

One useful behaviour and its regression; forecast approximately 260 written lines across the plan, capture assertion and two shared-test files, no new exported types or production consumer updates, three acceptance criteria, no state-machine reject branches. The inspected #1861 analogue wrote 60 inserted/6 deleted lines; extra work here covers asymmetric clearance controls and explicit sidecar provenance. Remote feature-branch scan found no overlapping changed paths at planning time; #2418 remains a downstream consumer, not an incoming dependency.

## Testing strategy

Test first: controlled symmetric 24/24 and asymmetric 49/24 clearance must predict screen tops 336 and 348.5 respectively, plus overlapping cutouts, nonzero screen origins, synthetic bars, density-scaled tolerance and a displaced-surface negative control. Run the new `DeleteCaptureGeometryTest` and existing `ThreadDeleteGeometryTest` on Linux. Run both named `ListDesignCaptureTest` viewport methods on full `pixel8Api35` with `requireRealSystemBars=true`; device-only because real bars, separate windows, physical Back/taps and hardware PNGs are required. Preserve raw XML/counts, PNGs, sidecars, source/APK/launcher identities and cleanup evidence, with the walk reaching `hostStates`. This does not certify #2418 host glyph comparisons. Run lint, assembly, instrumented compilation, formatting and final pre-verify; complete suites stay dispatcher-owned.

## State transitions and identity reuse

None: this repair is a stateless geometry assertion. Existing repeated Cancel, actual Back and outside taps remain in `walk` and must keep Channel Info closed and the conversation present.

## Documentation handoff

Pending for documentation stage:

- `docs/knowledge/features/development-verification-compose-evidence.md`, dialog-coordinate paragraph: explain both-edge inset mapping and link fresh counted evidence; preserve historical failures and provenance.
- `app/src/androidTest/assets/design-1220/list/index.md`, Delete confirmation entry: explain the same mapping and link fresh counted evidence; preserve historical failures and provenance.
