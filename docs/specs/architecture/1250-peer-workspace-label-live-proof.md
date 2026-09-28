# Peer workspace-label live proof (#1250)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`, `createChatOn`, `heldConversation`, `awaitChipShows`, `cleanupCreatedConversation`, `runningToolPeer` — current peer scenario, thread assertion and partial-setup cleanup patterns.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createWorkspaceFolder`, `createDiscussion`, `changeWorkspace`, `renameWorkspace` — existing host-scoped commands used by the scenario; no production edit.
- `scripts/e2e-emulator.sh` → `LIVE` `TEST_TARGET` assembly — curated rung-3 selection and `LIVE_TESTS` focused override.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, `parse_live_tests` — executed-count floor and focused method selection.
- `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list`, `test_live_curated_list_excludes_ignored_methods` — selector guard to extend before implementation.
- `docs/knowledge/features/workspace-chip.md` → `WorkspaceChip`, `workspaceDisplayName` — empty discussion shows label or folder basename without navigating.
- `docs/knowledge/features/development-verification.md` → device and real-evidence checks — fresh XML and nonzero execution are required for a live claim.
- `docs/e2e-interactive-stream.md` → rung-3 live inventory — documentation stage owns the coverage record.

## Context

#1239 retired Settings' Default workspace row. The ignored #1089 scenario still drives that row to create a folder and to assert a second label transition. The repository and empty discussion's thread chip remain reachable. No in-flight feature branch overlaps the test or live gate files.

## Design

- Remove this method's #1245 ignore and its Settings navigation, stored-default setup, row assertions and restoration. Create a run-unique real folder through host A's `createWorkspaceFolder`; both hosts share the test HOME, so create B's discussion and move A's empty discussion to the returned canonical path.
- Keep A's thread open through the peer's set and clear. Assert the chip initially shows the folder basename, then shows the unique peer label, then returns to the basename. At each transition, assert A's host repository carries the matching label and B's conversation at the same path carries none. Check the owning conversation's cwd remains the created path.
- Keep label clear, both conversation deletes, peer close and B unpair in `finally`. Capture host A's pre-create ID set so cleanup can discover the new discussion even when `createChatOn` fails before returning its ID. Capture B's returned ID for its cleanup.
- Add the method to the curated `LIVE` target and raise `LIVE_MINIMUM` once. Extend the selector test so omission of this method reddens the cheap guard.

## State and concurrency model

The existing peer's rename calls are suspend round trips. The test waits for the open chip's Compose state after each push, then reads each host's repository projection. No new job or state holder is introduced. The thread remains mounted until both transitions have been observed.

## Error handling

Missing folder creation, peer frames, chip transition or host isolation fails the method. Bounded cleanup attempts log exception type without hiding the original failure; the created folder remains under `~/pyry-workspace`, as other live scenarios do.

## Testing strategy

- First add a selector assertion and run `scripts/test_android_test_gate.py` to see it fail while the method remains ignored and unselected. Then migrate the scenario and make the selector and floor checks green.
- Run the focused touched-scope Gradle checks, including `compileDebugAndroidTestKotlin`. This test requires the real host daemon and relay, so it belongs in `androidTest`, not Robolectric; it spends zero Claude turns. A scripted twin adds no useful coverage beyond the existing repository projection tests and cannot prove the live two-host push.
- Focused rung-3 command: `python3 scripts/android-test-gate.py live --tests de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`. Inspect fresh XML and record executed, failed and skipped counts. Dispatcher credentials and the labelled full live gate remain later-stage acceptance if unavailable to the builder.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md` § “Live mode (rung 3: live relay)” and the peer workspace-label inventory to describe the thread-chip-only proof, curated count and actual focused live XML counts when available.

## Open questions

- Whether focused real-Claude authentication and emulator access are available in this builder environment. If unavailable, hand the exact command and missing XML evidence to the dispatcher live gate; do not treat compilation as live execution.

## Revisions

- Builder handoff (2026-09-28): the shared practice assigns authenticated real-Claude execution to the dispatcher. Mark this issue `needs-live-artifacts` alongside its existing `needs-real-claude` label. After review, run the focused command in Testing strategy, then return the result to the builder. Pending builder-owned files are `scripts/fixtures/peer-workspace-label-live/1250.xml` (the exact sanitized focused dispatcher XML) and `scripts/fixtures/peer-workspace-label-live/1250-context.json` (actual app/daemon revisions, UTC time, command, process exit, executed/failed/skipped counts and XML SHA-256). Validate the selected method executed once without a failure or skip, commit/push the files and result note, and remove only `needs-live-artifacts`. No placeholder evidence or pass claim is made before the run. The documentation stage still owns `docs/e2e-interactive-stream.md`.
