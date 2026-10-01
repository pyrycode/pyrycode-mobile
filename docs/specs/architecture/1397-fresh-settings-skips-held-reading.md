# #1397: five live settings scenarios read #1320's held reading as fresh

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `freshSettings` (the helper that takes the subscription's first non-null reading), `awaitPermissionReading` (polls through `freshSettings`), `awaitContextSegment`, `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` and the `@Ignore`s on the five methods.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt`: `observeSessionSettings` emits `HostReadings.observeHeldSessionSettings` as its `onStart` head, then each live reply.
- `app/src/main/java/de/pyryco/mobile/data/repository/HostReadings.kt`: `observeHeldSessionSettings` marks the head `held = true` with `permissionMode = ""`.
- `scripts/e2e-emulator.sh` (LIVE curated list), `scripts/android-test-gate.py` (`LIVE_MINIMUM`), `scripts/test_android_test_gate.py` (`test_live_floor_matches_the_curated_list`): #1325's exclusions, commit `c59a40f6`.

Overlap: #1410 adds lines next to the #1325 comments in both scripts; additive, built through.

## Change

`freshSettings` takes the first reading with `!held` instead of the first non-null one, so it returns the reply to its own `request_session_settings`. `awaitPermissionReading` and every other fresh-value caller already go through it. `awaitContextSegment` returns the text it matched, so the reconnect method can capture the `Cxt: N%` shown after the first turn and, after the cut and restore, wait for that same held reading instead of `Cxt: n/a` (#1317). Its other steps stay and its KDoc describes the held reading. The five `@Ignore`s go, the five methods return to the LIVE list where #1325 took them out, the #1325/#1397 comments go, `LIVE_MINIMUM += 5` replaces the `-= 5`, and the script test pins 44 and asserts the reconnect-footer and operator-bypass methods are present. No production code changes: both moves under the test were intended desktop parity.

## Testing strategy

`python3 -m unittest scripts/test_android_test_gate.py` proves the floor matches the list and that no listed method is `@Ignore`d. `./gradlew compileDebugAndroidTestKotlin` compiles the helper change. The five methods run only on the dispatcher's live gate (`python3 scripts/android-test-gate.py live`), which this ticket's acceptance names; the PR lists `all` under `## Live tests` because `freshSettings` is shared across the class.

## Revisions

### 2026-10-01: the reconnect method's model mark follows the held announcement

The live gate (log `2026-10-01T12-32-28-212Z_real-claude-gate_#1397`) passed 43 of 44. `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` got past the held `Cxt: N%` and the fresh reading, then failed with `run configuration 'Model' never settled on '<inherited label>'`. The cause is #1317's other held reading, not the held settings: the first turn's announced model is held across the reconnect too, and `ThreadRunConfig.selectedChoice` marks the row an inherited chat's announcement maps to (#1308), not the default row's resolution that `inheritedModelLabel` names. Before #1317 the reconnect cleared the announcement, so the old expectation held. Step 3 now reads the announced model and checks the mark with `announcedRow` and `awaitAnnouncedMark`, then `awaitFooter` on the marked label, as `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` does. Step 5's target also skips the marked row, so the pick is a real change. No production code changes.
