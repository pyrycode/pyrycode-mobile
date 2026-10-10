# Confirm landed thread geometry repair (#1890)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadDeleteGeometryTest.kt`: `layout`, default/long-name geometry and all four pointer tests retain the landed test-thread semantics access.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt`: `assertStreamTop` and both stream-top methods retain the header, clearance, absolute position and pill-height assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: `retryRow_keepsItsLabelsFullLineBox_andLeavesTheStandard16dpGapBelowIt` retains untrimmed labels, separate padding/gap checks and the total target.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/PixelSnapping.kt`: `assertDpEquals` stays exact at integral density and allows the specified device pixels otherwise.
- `docs/specs/architecture/1887-thread-geometry-portability.md`: the landed repair's rounding justification and test-thread contract.
- `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/development-verification-gates.md`: existing thread geometry and sharedTest runner guidance.
- PR #1903: exact managed-device selection and previous counted evidence.

## Change

Confirm repair `1a333fcf24a127b2f4621398c7171cde58ad62ac` on builder base `f3186e5a5ed013540cf74ff410e9e36dcd75b775`. Evidence-only completion is the expected result. Preserve sharedTest placement, design targets (97dp stream top, 24dp pill, 60dp retry row), separate spacing/type/clipping assertions and existing pixel allowances. Change only a reproduced portability failure in the named methods, if one appears, recording any design departure before repair. No production changes or new test logic are planned. No in-flight feature branch overlaps these three test files at planning time. Forecast: under 80 written lines, no new exported declarations, consumer updates or state-machine branches; two acceptance criteria support one verification deliverable.

## Testing strategy

Run fresh Robolectric selection through `testDebugUnitTest --rerun` and the matching managed Android 13 `pixel2Api33AtdDebugAndroidTest --rerun`: the whole six-method `ThreadDeleteGeometryTest`, the two named modal stream-top methods and the named history retry-row method (nine cases total). Inspect fresh XML, copy it outside the worktree before another run can overwrite it, and record per-method executed/failed/skipped counts, command status, result paths and base commit in the PR. Existing tests supply the proof; no artificial red test is needed for evidence-only confirmation. Run lint, debug assembly and formatting, then merge main, push, and run final assembly plus `scripts/pre-verify.py --gradle`. The dispatcher owns the later in-depth main sweep; no live scenario is needed for test-only confirmation.
