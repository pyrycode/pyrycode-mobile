# Reader coordinate rounding repair (#2292)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: `referencePlacesBarRuleAndBodyAtFigmaCoordinates` compares adjacent block tops against the #2285 coordinate table.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt`: `assertDpEquals` already accepts a local pixel count and stays exact at density 1; its default remains unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownText`, `ListBlock` and `HeadingBlock` round text heights and layout spacing independently.
- `docs/knowledge/features/markdown-reader-screen.md`: reference geometry and untrimmed reader line boxes remain the contract.
- `docs/knowledge/features/development-verification-gates.md`: cumulative bounds need constituent checks; shared tests retain exact density-1 assertions.

## Design source

No visual change: the refinement comment explicitly excludes production rendering and visual criteria. Keep main's #2285 coordinates; no new Figma inspection is needed for this test-only repair.

## Change

Repair one behaviour: adjacent-block coordinate comparisons distinguish independently rounded text/layout dimensions from spacing regressions. Measure the named method on the managed density-2.625 device before changing its comparison, recording line counts, text heights and gaps. Use the existing helper's local `pixels` argument only where the measured constituents justify a larger bound, with separate height/gap checks so the total allowance cannot conceal a layout regression. Retain every coordinate assertion and nominal position. Add focused boundary controls proving acceptance within the justified bound, rejection beyond it, and exact comparisons at density 1. No production code, shared default tolerance, dependency, lifecycle state or exported declaration changes.

Overlap: #2278 and #2284 change the same coordinate table for separate features; neither is a dependency. Keep this repair local when merging their future geometry updates.

Forecast: approximately 130 written lines across this plan, the design test and a small evidence index; zero exported types or consumer migrations, two acceptance criteria, no state-machine branches. This remains one repair with its regression proof.

## Testing strategy

- First run the unchanged named method on the managed Android 13 fractional-density device; retain its counted failure and revision.
- Run measurement/assertion diagnostics before choosing the final local bound; retain the observed constituent contributions in a revision below.
- Rerun the named device method after repair and the focused Robolectric `MarkdownReaderDesignTest` class, including boundary controls; inspect XML executed/passed/failed/skipped counts.
- Run lint, APK assembly, instrumented Kotlin compilation and changed-file formatting. After the final main merge, push and run APK assembly plus `scripts/pre-verify.py --gradle`.
- Retain raw reports, revisions and hashes on the persistent agents evidence volume and a content-free index under `app/src/androidTest/assets/reader-rounding-2292/`. Complete gates and the post-merge Linux main sweep belong to the dispatcher; macOS is outside this ticket's scope.
