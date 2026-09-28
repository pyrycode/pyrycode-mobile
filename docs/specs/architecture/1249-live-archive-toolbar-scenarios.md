# Live Archive scenarios through the list toolbar (#1249)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_archiveRestore_roundTripsListMembership`, `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost`, `selectHost`, `hostConversationIds`, `archivedIds`, `hostRepository` — existing live assertions, selected-host navigation, and cleanup patterns.
- `scripts/e2e-emulator.sh` → the `LIVE` `TEST_TARGET` assembly — curated method selection and focused `LIVE_TESTS` override.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, `parse_live_tests` — executed-count floor and focused live selection.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListTopBar` and `ArchiveTapped` — current archive entry and selected-host routing.
- `docs/knowledge/features/channel-list-screen.md` → list toolbar — confirms “Open archive” is the current entry.
- `docs/knowledge/features/settings-screen.md` → Settings modal — confirms the retired Archive and default-workspace rows.
- `docs/knowledge/features/development-verification.md` → emulator and real evidence — XML and nonzero execution are required for a live claim.
- `docs/e2e-interactive-stream.md` → live-mode rung 3 — the documentation stage owns its coverage record.

## Context

#1239 removed the Settings rows these ignored scenarios used. The list toolbar opens Archive for the selected host. The two-host method's default-workspace proof no longer describes reachable UI, but its host-isolated Archive proof remains useful. No in-flight numeric feature branch overlaps the test or harness files.

## Design

- Remove the two #1245 ignores. The discussion scenario keeps its unique rename and list presence → absence → archive presence → restored list presence sequence. Enter Archive with `CD_OPEN_ARCHIVE`; wait for `RESTORED_SNACKBAR` before the single Back to the list.
- Rename the two-host method to `interactiveTurn_twoHostsArchive_staysPerHost`. Pair B, create one uniquely named chat on A and one chat on B, record B's active and archived ID sets, and check they stay identical after A's archive and restore. Select A through `selectHost` before the toolbar tap, then enter Archive with `CD_OPEN_ARCHIVE`. Check A's chat in A's archived set and the Archive UI; wait for restoration success before leaving.
- In `finally`, delete conversations created by these methods and remove the second pairing. Cleanup must run after a partial setup failure, using nullable captured IDs and the existing bounded repository deletion pattern.
- Add both runnable method names to the `LIVE` curated `TEST_TARGET` and raise `LIVE_MINIMUM` by two. A focused `--tests` live invocation selects exactly these methods; the dispatcher later runs the full labelled live gate.

## State and concurrency model

These are synchronous instrumentation drives of existing app and daemon flows. The `ArchivedDiscussionsViewModel` restore coroutine belongs to the Archive destination, so each method waits for the success snackbar before Back can dispose it. No new coroutine or state holder is introduced.

## Error handling

Missing UI, failed daemon round trips, or changed host membership fail the test. Cleanup logs only exception type and uses the existing `runCatching` pattern so a cleanup failure does not replace the assertion failure.

## Testing strategy

- The two live methods are the behavior proof; their old ignored state is the RED baseline. Compile androidTest and run the touched build checks. Validate curated method count against the floor.
- Focused rung-3 command: `python3 scripts/android-test-gate.py live --tests <both class#method selectors>`. Inspect fresh XML for executed, failed, and skipped counts. The required real-Claude run uses dispatcher credentials after verifier; report it as pending until its artifacts exist.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md` § “Live mode (rung 3: live relay)” and its verification status with the restored discussion and two-host Archive coverage plus the revision-linked live XML evidence and counts once the live gate runs.

## Open questions

- Whether the focused live run can execute in this builder environment. Resolve through the dispatcher-owned live gate if Claude authentication is unavailable; do not claim a pass from compilation or skipped XML.

## Revisions

- Builder handoff: the shared practice assigns real-Claude execution to the dispatcher after verifier. The two selected method names and XML counts remain pending that live gate; compilation and curated-list checks establish only that the implementation is ready for it.
