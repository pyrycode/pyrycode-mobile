# #2238: prove background Agent run navigation over a daemon-built thread

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeScenarioScreenTest.kt`: `mount` builds `ThreadScreen` without `collapseToolUses`; the new method goes here.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `verifyAgentRunNavigation`, the proof helper, used unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QueueRecoveryAgentFixtures.kt`: `finishedAgentScenarioItems`, the settled agent fixture.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QueueRecoveryAgentEvidence.kt`: `ThreadEvidence.agentFamily`, the source of the agent, run and child identities.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the `collapseToolUses` parameter (defaults to `false`) gating `foldHistoryToolRuns`; read only.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: `settledNavigationThenOwnedPointerTapsOpenAndCloseALongChildRun`, the legacy analogue that mounts with collapse on.

## Change

`mount` gains a `collapseToolUses: Boolean = false` parameter passed through to `ThreadScreen`, so the existing three tests keep their unfolded tree. A new test, `settledAgentRunNavigatesOpensAndClosesOverDaemonBuiltThread`, mounts `finishedAgentScenarioItems()` with collapse on, takes `agentId`, `runId` and `childIds` from `evidence.agentFamily(evidence.owner)`, and calls `verifyAgentRunNavigation` with owned child `child1827-after` under `background-agent-child:<agentId>` and the labels "Go to agent ↓", "Show tool uses" and "Hide tool uses". Nothing under `app/src/main/` changes; if the proof fails with collapse on, the ticket returns to the refiner with the failing assertion.

## Testing strategy

The new method is the test. It runs under Robolectric in `testDebugUnitTest --tests de.pyryco.mobile.e2e.AuthoritativeScenarioScreenTest`, beside the three existing methods, which must stay green.
