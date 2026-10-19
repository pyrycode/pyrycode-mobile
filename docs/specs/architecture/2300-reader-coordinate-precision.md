# Reader coordinate assertion precision (#2300)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: `referencePlacesBarRuleAndBodyAtFigmaCoordinates` subtracts nonzero dp coordinates; `adjacentBlockRoundingAllowanceKeepsItsPixelBoundary` currently probes around zero only.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt`: `assertDpEquals` preserves exact whole-density comparisons and explicit pixel limits; its shared callers stay unchanged.
- `docs/knowledge/features/markdown-reader-screen.md`: reader geometry and typography contract.
- `docs/knowledge/features/markdown-reader-screen-testing.md`: retain constituent gap/height checks alongside adjacent-top checks.

## Change

Fix the one local adjacent-top comparison by recovering each snapped integer pixel coordinate before subtracting at fractional density, then comparing the pixel distance with the expected dp distance converted to pixels. This avoids float cancellation at the two-pixel boundary without increasing its limit. Whole-density comparisons retain the original exact dp subtraction. Keep the default one-pixel allowance, the two selected two-pixel intervals and all other reader assertions. No production geometry changes. Add a private helper with an explicit density input so JVM regression checks exercise density 2.625 as well as density 1.

One useful behaviour slice: reliable reader coordinate assertions, proved by boundary regressions and the existing rendered reference test. Forecast: about 110 written lines including this plan, zero production types or production callers, two acceptance criteria, no state-machine reject branches. The refiner estimated about 70 lines; the extra work is explicit regression coverage and the plan. Remote feature branches currently have no overlapping edits to the design test; #2276 was noted by refinement as potential additive integration coverage.

## Testing strategy

Run the named reference method unchanged on managed Android 13 first and retain its failed report. Add a failing numeric regression using nonzero snapped coordinates at density 2.625 in both error directions; accept two pixels, reject three, reject two under the default one-pixel limit, and preserve exact density-1 checks including subpixel rejection. Then repair the private helper and run the full `MarkdownReaderDesignTest` JVM class and the named Android method on Linux. Existing zero-origin boundary assertions remain. Retain raw reports, source revisions, density and counted outcomes, including failures, in persistent ticket evidence. The complete gates and post-merge sweep remain dispatcher-owned. Run focused lint/build/format/compile checks and final `pre-verify.py --gradle` after merging main. No live-provider evidence or new device-only test is needed.

## Revisions

### 2026-10-19 — Snap the expected distance to the same pixel grid

The first repaired Android run passed the original paragraph interval but exposed the next interval: the expected 38dp converts to 99.75 ideal pixels, while the actual snapped distance is 101 pixels. Comparing an ideal fractional pixel distance with a whole-pixel observation still mismatches coordinate systems. Round the expected distance to its nearest integer pixel too (100 pixels here), then retain the original one- or two-pixel integer limit. Whole-density comparisons remain exact. `nonintegralExpectedDistanceKeepsItsSnappedPixelBoundaries` covers both directions around that rounded expectation, including rejection beyond each limit. Retain the intermediate failed native report alongside the original failure. This remains the same local numeric-comparison repair; production geometry, reference dp values and allowance counts do not change.
