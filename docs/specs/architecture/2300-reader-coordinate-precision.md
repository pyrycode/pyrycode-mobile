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

### 2026-10-19 — Refined constituent contract before continuation

The live issue now resolves the remaining native expectation: preserve the shared panel and derive its interval from independently checked constituents. In `referencePlacesBarRuleAndBodyAtFigmaCoordinates`, check the two-line code's 12sp/20sp monospace style, 20sp baseline advance and 34dp trimmed text height with the default pixel bound. Check each 12dp padding edge, panel height as measured text height plus two independently rounded padding values, and the following 16dp gap. A private `referenceCodeInterval` converts that constituent sum to dp; only the code-to-quote aggregate uses it. Its expectation never uses the observed panel height or top-to-top interval. Retain the existing 58dp panel assertion and every other reader check, with new exact constituent checks preserving 58dp/74dp at density 1.

First add `codeIntervalRoundsConstituentsAndKeepsItsOnePixelBoundary` against the old aggregate model and observe its fractional-density failure. Then implement the constituent sum and verify both error directions accept one pixel and reject two, including exact and subpixel density-1 controls. Run the full affected JVM class and named Android 13 method on Linux; retain earlier red evidence and new raw reports, revisions, density and counts. No new device-only test, production edit, shared assertion edit or documentation-stage requirement is introduced.

This remains one useful reader-assertion repair. Forecast: about 200 written lines across the test and plan, zero exported production types or consumer migrations, three acceptance criteria, no lifecycle/identity state or error branches. Current remote feature branches have no overlap with the design test. Complete gates and the post-merge sweep stay dispatcher-owned.

### 2026-10-19 — Snap the expected distance to the same pixel grid

The first repaired Android run passed the original paragraph interval but exposed the next interval: the expected 38dp converts to 99.75 ideal pixels, while the actual snapped distance is 101 pixels. Comparing an ideal fractional pixel distance with a whole-pixel observation still mismatches coordinate systems. Round the expected distance to its nearest integer pixel too (100 pixels here), then retain the original one- or two-pixel integer limit. Whole-density comparisons remain exact. `nonintegralExpectedDistanceKeepsItsSnappedPixelBoundaries` covers both directions around that rounded expectation, including rejection beyond each limit. Retain the intermediate failed native report alongside the original failure. This remains the same local numeric-comparison repair; production geometry, reference dp values and allowance counts do not change.

### 2026-10-19 — Remaining native interval requires refinement

At `15f1a5e1b72026845f6d2acb94c0b558e43be5b5`, all 20 JVM methods pass, but the named Android method reaches another existing interval: quote below code measures 196 pixels versus the snapped 194-pixel expectation and a one-pixel limit. Even the original nominal comparison would reject this: 74dp is 194.25 pixels at density 2.625, a 1.75-pixel error. `CodeBlock` in `MarkdownText.kt` sizes its panel from text and padding; this result does not identify whether its native geometry or the aggregate reference expectation should change. A float precision repair alone cannot satisfy this remaining bound. Refinement must specify how to validate native code-panel rounding while preserving the requested geometry and limits, or explicitly authorise a revised geometry/allowance contract. Preserve this as one repair with its regression coverage rather than splitting off its private comparison helper. Native acceptance remains failed; no PR is ready. Raw reports, source snapshots and hash manifests for every failed run and both passing JVM class runs are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2300/builder-20261019/`.
