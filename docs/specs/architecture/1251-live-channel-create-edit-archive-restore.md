# #1251 — restore live channel create, edit, archive and restore proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_createEditArchiveChannel_readsPromptBack`, `interactiveTurn_archiveRestore_roundTripsListMembership`, `interactiveTurn_twoHostsArchive_staysPerHost`, and `selectHost` — the scenario, the reachable list-toolbar Archive route, and host selection.
- `scripts/e2e-emulator.sh` → the `LIVE` `TEST_TARGET` list — curated real-Claude selection.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM` and `run_on_device` — executed-count gate and focused `--tests` mode.
- `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list` and `test_live_curated_list_excludes_ignored_methods` — selector and floor checks.
- `docs/knowledge/features/channel-list-screen.md` → Channels section and toolbar behavior — the visible controls the test must drive.
- `docs/knowledge/features/development-verification.md` → emulator and real evidence — report XML execution, failures, and skips; preserve cleanup.
- `docs/e2e-interactive-stream.md` → existing live scenario and baseline — documentation-stage inventory and evidence location.

## Design source

N/A — this ticket changes only an instrumented test and its live selection, with no product UI change.

## Change

Remove the method's `@Ignore` and obsolete app default-workspace preference setup, assertion, and restoration. Keep the daemon-default-folder probe and compare the created channel's `cwd` to its `cwd`. Keep the empty Channels section, created and edited name/prompt readbacks, and fixture restoration and conversation deletion in `finally`. After the new session, wait for the second real reply before checking `SessionPromptStatus.Matches`. Archive from Edit channel, select the owning host, then restore through the list toolbar's Archive entry and Channels tab, using the success snackbar before returning to the list. Add the method to the curated `LIVE` selector and raise its executed-count floor to 41.

## Testing strategy

- First make `test_live_floor_matches_the_curated_list` require this method and a floor of 41; run it red before the script changes, then green after.
- Compile the changed instrumented class and run scoped script tests, `spotlessApply`, `lint`, and `assembleDebug`.
- Focused rung-3 command: `python3 scripts/android-test-gate.py live --tests de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack`. Inspect fresh XML and record executed, failed, and skipped counts. The dispatcher supplies the real-Claude environment and owns that live execution; pending evidence is explicit in the PR handoff.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md` § Live mode (rung 3, live relay) and § Pre-ship gate: update the channel scenario's route and curated count after the focused live result.

## Revisions

None.
