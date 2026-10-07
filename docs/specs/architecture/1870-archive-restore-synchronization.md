# #1870 — Archive/restore live-test synchronization

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_archiveRestore_roundTripsListMembership`, `openArchiveTab`, `awaitChannelList`, and `cleanupCreatedConversation` define the drive and failure cleanup.
- `scripts/android-test-gate.py`: `run_on_device` retains raw XML and per-test logcat separately from sanitized dispatcher summaries.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt`: `ArchivedDiscussionsScreen` collects restore success effects sequentially and draws the tab selector.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onEvent` launches rename independently of navigation; archive also has an observed-row exit path.
- `docs/knowledge/features/archived-discussions-screen.md`: the default tab is Channels; switching tabs and awaiting completed restore remain distinct prerequisites.
- `docs/knowledge/features/channel-list-screen.md`: active discussions live in the host's Chats section.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: raw reports survive only in the gate's retained artifact directory.
- `docs/e2e-interactive-stream.md`: archive/restore is a zero-Claude-turn rung-3 scenario with genuine membership inversions.

## Context

The October occurrences prove intermittency but their summary XML omits failure stacks. Historical detailed reports have not been found in surviving worktrees. Capture a fresh raw failure before naming a cause or repairing a guard. One older connected report is insufficient to attribute the October failures.

This is one test reliability deliverable, estimated within 550 written lines, at most two production files, no required exported API migration, four acceptance criteria, and fewer than ten reject branches. No visual redesign is intended. Overlapping branches #1682, #1689, #1690, #1691, #1693, #1695, #1731, #1766, #1833, and #1867 change other scenarios in the same class; keep edits local.

## Design

Use fresh raw XML and the matching per-test logcat to locate the first failed stage. If more detail is needed, add static stage diagnostics only to the assigned scenario, retaining exceptions and `finally` cleanup. Repair only the evidenced synchronization/isolation defect or local product condition; record the resulting contract under Revisions before committing the repair. Do not retry mutations, increase sleeps, weaken membership assertions, or change the live selector.

Retain a runtime-unique discussion name and its known host conversation ID, observe active presence before archive, active absence after archive, Archive presence, completed restore, and active presence after restore. Keep the existing restore-success guard unless evidence specifically disproves its adequacy. Cleanup must delete the created discussion on every exit.

## State and concurrency model

The scenario drives the real activity and isolated relay/daemon. Compose waits must observe the relevant completed UI state rather than just a destination's existence. Repository work belongs to destination ViewModels; leaving a destination can cancel unfinished mutations. No new production scope or dispatcher is planned.

## Error handling

A failed observation remains a failure with its original exception and a named stage. Diagnostic output contains static stage names and exception classes, never daemon-authored content. If the cause belongs to a sibling repository, file and link its fix as a blocker before dependent work.

## Testing strategy

Run the unchanged live method to capture diagnostic evidence, then create a focused deterministic regression that forces the diagnosed condition. Watch that proof fail before repairing the implementation or test helper and pass afterwards; record executed, failed, and skipped counts. Prefer a shared Compose test exercising the actual helper and production screen, or a unit test for a pure product condition. Device-only proof is appropriate only if the failure depends on the real activity, device input, or background repository dispatch that Robolectric cannot drive.

Run relevant existing coverage, lint, APK assembly, androidTest compilation, and forced Spotless. After the final merge of main and push, run the whole JVM/shared suite, assembly, and `scripts/pre-verify.py --gradle`. The dispatcher owns a fresh full post-verifier live gate and its revision-linked evidence; a focused diagnostic run is not full live acceptance.

## Open Questions

- Which stage fails, and what raw evidence connects it to a cause? Resolve with fresh diagnostic artifacts before repair.
- What deterministic condition reproduces that cause? Record the chosen proof and contract under Revisions.

## Revisions

2026-10-07: the unchanged scenario failed on mobile revision `b2df00ba07768e800f4bc3cc5adb6f9c464858b9`, daemon revision `6019328b378cad587f69b7bc94de37febbdf8556`, in `build/dispatcher-tests/live-rdr24u97`. Its per-test logcat records a 30-second `ComposeTimeoutException` at the first active-list presence wait after Rename and Back, before archive. A standalone run on the same code passed. Add failure-only, content-free diagnostics for rename confirmation, active membership, composed presence, and discovery by a full lazy-list scroll; rethrow the original failure and preserve cleanup. The evidence does not yet distinguish an unfinished rename from an off-viewport row, so no repair is selected yet.

2026-10-07: extract the two unchanged active-presence waits into test-only `awaitArchiveRoundTripChat`, shared by the live drive and `ArchiveRoundTripChatTest`. The candidate viewport probe mounts the production `ChannelListScreen` with 24 preceding alphabetic chats and the target in its projection but outside the composed viewport. It fails at the extracted presence wait; its missing-chat negative control passes (2 executed, 1 failed, 0 skipped). Retain that XML and the original live target's raw stack under `app/src/androidTest/assets/archive-restore-1870/`. This isolates a candidate condition; live failure-only diagnostics must still establish whether it caused the reproduced occurrence.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, archive/restore scenario and verification status, with the diagnosed synchronization contract and fresh full live-gate revision, artifact path, counts, and explicit method pass once the dispatcher supplies them.
