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

## Verification results

2026-10-10: Both fresh focused commands exited 0 on the planned builder base. Each runner executed/passed nine tests, with zero failures, errors or skips. No portability repair was needed; production and test sources remain unchanged.

| Method | Robolectric executed/failed/skipped | Android 13 executed/failed/skipped |
| --- | --- | --- |
| `ThreadDeleteGeometryTest#default_matches_frame_geometry_and_type_roles` | 1/0/0 | 1/0/0 |
| `ThreadDeleteGeometryTest#long_name_grows_surface_without_clipping_body` | 1/0/0 | 1/0/0 |
| `ThreadDeleteGeometryTest#pointer_at_delete_target_extension_confirms_once` | 1/0/0 | 1/0/0 |
| `ThreadDeleteGeometryTest#pointer_at_cancel_target_extension_dismisses_once` | 1/0/0 | 1/0/0 |
| `ThreadDeleteGeometryTest#pointer_beside_left_surface_edge_dismisses_once` | 1/0/0 | 1/0/0 |
| `ThreadDeleteGeometryTest#pointer_beside_right_surface_edge_dismisses_once` | 1/0/0 | 1/0/0 |
| `ThreadScreenModalTest#open_request_card_sits_flush_on_the_stream_top` | 1/0/0 | 1/0/0 |
| `ThreadScreenModalTest#answer_rejected_pill_sits_flush_on_the_stream_top_at_its_frame_height` | 1/0/0 | 1/0/0 |
| `ThreadScreenHistoryTest#retryRow_keepsItsLabelsFullLineBox_andLeavesTheStandard16dpGapBelowIt` | 1/0/0 | 1/0/0 |

JVM XML: `app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.ui.conversations.thread.{ThreadDeleteGeometryTest,ThreadScreenModalTest,ThreadScreenHistoryTest}.xml`, timestamps `2026-10-10T00:02:10.779Z`–`2026-10-10T00:02:18.042Z`, preserved with per-method counts in `/tmp/builder-1890/focused-jvm/`.

Device XML: `app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/TEST-pixel2Api33Atd-_app-.xml`, timestamp `2026-10-10T00:12:22`, SHA-256 `060bf2c13d24b7910696496e61e4e8e50bc437e56f891f01699f2d79673b7740`, preserved with per-method counts in `/tmp/builder-1890/focused-device/`. The exact focused commands are recorded in the PR; the later in-depth main sweep remains dispatcher-owned.
