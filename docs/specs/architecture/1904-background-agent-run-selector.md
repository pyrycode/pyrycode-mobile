# Repair the background Agent live run selector

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent` currently matches the root Agent id instead of its child run.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentProseScreenTest.kt`: `agentRunControlHasStableOwnershipWhenAnOrdinaryRunHasTheSameLabel` covers independent owned controls; strengthen it with genuinely identical labels.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns` leaves the root separate and takes `runId` from the first child tool in loaded order.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` preserves loaded order within the joined family.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the `ThreadRow.ToolRun` arm tags the header with that run id; child prose carries its Agent ownership tag.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` is the existing scripted placement/visibility twin.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-subagent-tool-rows.md`: run identity is the first tool message id; repository ownership and run identity differ.
- `docs/knowledge/features/development-verification.md` and `development-verification-gates.md`: shared screen tests run on Robolectric; e2e host/relay IO requires device instrumentation.
- Both `2026-10-07T08-34-36-084Z_real-claude-gate[_base]_#1854` XML reports and their stderr reports: branch has 64 executed, 4 failed, 0 skipped; main has 1 executed, 1 failed, 0 skipped. Both fail at the first owned-run scroll with a root-id tag.

## Change

Wait for the attributed paragraph and an owned child tool to reach the phone repository. Reuse that loaded snapshot for the one-segment attribution proof and select its first tool message whose tool parent is the held Agent id. Match the clickable run control beneath `tool-run:<child tool id>` for both open and close. The fixture explicitly requests direct foreground Bash children, so no recursive ownership resolver is needed. Preserve every visibility, main-continuation and teardown assertion, the curated selector, and all product behavior. Strengthen the existing shared regression with a second owned child tool so both independent runs really have the same label, and derive its owned run id from loaded child ownership.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1731, #1766, #1818 and #1833 touch the live test file. Their diffs affect other methods or local diagnostics; no dependency or shared restructuring is required.

Sizing: approximately 100 written lines across this plan and two test files, zero production files, new exported declarations, signature migrations or error branches; three acceptance criteria and one deliverable.

## Testing strategy

Run the strengthened owned-control regression with the stale root selector first and confirm its failure, then switch to loaded child identity and run `BackgroundAgentProseScreenTest`, `ToolRunFoldTest`, `BackgroundAgentProseTest` and `ToolRunCollapseTest`. Run the existing `background-agent` scripted scenario and read fresh counted XML. The changed live method stays under androidTest because it exercises real daemon/relay IO; the dispatcher owns fresh full live-gate XML proving its execution/pass, with executed/failed/skipped counts. No separate focused live run is required by this ticket.

Run lint, assemble, Android-test Kotlin compilation and Spotless. After the last main merge, push and run the whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle` with the PR body. Record the diagnosis against both inherited reports in the PR. #1867 remains responsible for the separate navigation-close failure.
