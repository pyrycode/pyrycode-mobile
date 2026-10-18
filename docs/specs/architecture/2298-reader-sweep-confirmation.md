# Reader sweep failure confirmation (#2298)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: the coordinate method independently checks rounded constituents; the boundary method rejects drift beyond the local allowance.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt`: `assertDpEquals` retains its one-pixel default and exact density-1 comparisons.
- `app/src/androidTest/assets/reader-rounding-2292/evidence.txt`: retained red/green source identities, counts and diagnostics from the merged repair.
- `docs/knowledge/features/markdown-reader-screen.md` and `markdown-reader-screen-testing.md`: nominal reader geometry and the measured fractional-density rounding contract.
- `docs/specs/architecture/2292-reader-coordinate-rounding.md`: the repair's constituent checks and two-pixel boundary controls.

## Design source

No visual changes. The refined ticket explicitly permits evidence-only confirmation of merged #2297; no Figma section or new capture is required.

## Change

Confirm one behaviour: the reader coordinate assertion accepts the measured cumulative fractional-density rounding while retaining its strict constituent and boundary checks. Main `e02920bf71c18c9ce3ece6f11b18ee57ffaf1cbd` contains #2297, which fixes the exact failure reported by the preceding sweep. At density 2.625, three nominal 52.5px paragraph lines become 53px each, accumulating 1.5px; list lines and sibling gaps accumulate 2px. Preserve production rendering, nominal geometry and all tolerances. Retain fresh focused evidence and a content-free index at `app/src/androidTest/assets/reader-sweep-2298/evidence.txt`, linking #2292's accepted verifier evidence. No new logic or test is needed if the existing checks pass.

Scope: approximately 65 written lines across this plan and evidence index, zero production files, exported declarations, consumer migrations or reject branches; two acceptance criteria. In-flight branch scan found no overlap with these two output paths. #2278/#2284 touch the shared design test, which this ticket leaves unchanged.

## Testing strategy

- Run the existing native `referencePlacesBarRuleAndBodyAtFigmaCoordinates` and `adjacentBlockRoundingAllowanceKeepsItsPixelBoundary` methods together on Linux's managed Android 13 device. Native execution is required to check density-2.625 rounding; no device-only test is added.
- Run the focused Robolectric `MarkdownReaderDesignTest` class to preserve exact density-1 coverage. Inspect raw XML for executed, passed, failed, error and skipped counts; retain source revisions, phone diagnostics and verified hashes on the persistent agents evidence volume.
- Run lint, APK assembly, instrumented Kotlin compilation and changed-file formatting. Commit and push, then run final post-main-merge assembly and `scripts/pre-verify.py --gradle` with the prepared PR body.
- Keep the original 1726-executed/1725-passed/1-failed sweep recorded as failed. Complete gates and the next in-depth main sweep remain dispatcher-owned and pending. macOS is outside this Linux ticket's verification scope.
