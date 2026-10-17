# Live agent reply and stop evidence (#2172)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent` and `interactiveTurn_stopBackgroundTask_completesThenRepliesAgain`, the seven consumers: two `observeMessages` observations, three `holdsSettledAssistantReply` uses, and the raw `parentToolUseId` joins that pick the held paragraph and the run key.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt` (#2169): `harnessThreadEvidence` stays the single legacy binding; `ThreadEvidence.settledReply` takes a raw parent for legacy families and a projected Agent block for authoritative ones; records carry rendered `agentId` and `toolRunId`.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettledAssistantReply.kt`: legacy delta settlement, kept unchanged with `SettledAssistantReplyTest`.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/FinishedSelectionReply.kt`: the #2169 shape for a consumer predicate shared with JVM probes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` makes an agent item's own scoped identity its block id and reads `content.task_id` / `content.tool_use_id` as recorded raw facts.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` gives legacy block id = the root tool message id (an alias when collided), with `ordinaryId` the raw tool use id.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns` folds an agent block's prose and tools into one run keyed by its first tool; `foldHistoryToolRuns` only splits main-lane rows, so agent run ids match.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: legacy live assistant rows carry `sessionId = ""`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadFixtures.kt`: `authoritativeAgent`, `authoritativeItem`, `authoritativeSnapshot` for probes.
- `docs/recorded-tests.md`: `RecordedMobileSuiteTest` replays these methods through the same assertions; outgoing requests must not change.
- `docs/specs/architecture/2169-shared-thread-evidence.md`: lessons carried here: rendered aliases are not wire identities; a positive parent needs a resolved block; receipt is not settlement.

## Context

Required-thread activation (#2175) flips `harnessThreadEvidence` once. These two live methods still read legacy messages directly and key rendered checks by raw tool ids, which would silently mis-key under the authoritative route (scoped identities). This test-only ticket moves their observation, settlement and rendered identities onto #2169 evidence. No production files, no route change, no outgoing request change, so recordings are unaffected. No in-flight branch touches these files.

## Design

New shared file `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentReplyEvidence.kt`:

- `ThreadEvidenceAgent(block, family)`: rendered Agent block id plus the key `settledReply` expects (legacy raw tool use id, authoritative block id).
- `ThreadEvidence.backgroundAgent(taskId, toolUseId): ThreadEvidenceAgent?` correlates raw peer facts to an admitted root. Legacy: the Tool record whose `ordinaryId` is the raw tool use id and which renders as its own block. Authoritative: the `agent` fact whose `content.task_id` equals the raw task id, whose `content.tool_use_id` (when present) equals the raw tool use id, and which renders as its own block. Raw ids never become row keys.
- `ThreadEvidence.settledTurnReply(current, turn, agent?)`: takes the turn's single recorded session from its assistant records (none or several fails), delegates to `settledReply`, then requires every family record to render inside `agent.block` (or outside every block for the main lane).
- `AgentReplyEvidence(agent, heldReply, runId, mainReply)` and `ThreadEvidence.agentReplyEvidence(current, taskId, toolUseId, childTurn, mainTurn)`: the settled child family's last nonblank paragraph, the settled main family's last nonblank reply, and the rendered run id of the first owned child tool (non-root, same block, folded into a run).
- `ThreadEvidence.settledMainReply(current, turn)`: last nonblank record of the settled main-lane family.

Consumers: both methods observe `harnessThreadEvidence(it).observe(chat)` through the existing `firstOnLive`, keep `THREAD_TIMEOUT_MS`, and pass `it.owner` as current. The agent-reply method tags `assistant-reply:`, `background-agent-child:` and `tool-run:` from `AgentReplyEvidence`; every UI step, permission loop, fixture release and cleanup stays as is. The stop method tags its body from `settledMainReply`; its Stop targeting, roster/panel completion proof and fixture checks stay untouched.

## State and concurrency model

No new jobs or scopes. Cold mapping flows run inside the caller's `withTimeout`; `firstOnLive` keeps cancelling retired connection collectors.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Same item ids from another host | `collidingHostCannotSatisfyAgentReplyOrMainReply` |
| Connection replaced while evidence incomplete | `retiredConnectionEvidenceIsRejectedAndOnlyTheCurrentSourceSettles` |
| Child or main item active, running, blank, or main reply parented under the Agent | `incompleteOrMisattributedRepliesDoNotSettle` |
| Turn spanning two sessions | `incompleteOrMisattributedRepliesDoNotSettle` |
| Raw task or tool id disagreeing with admitted facts | `authoritativeAgentReplyUsesProjectedIdentities` |
| Legacy aliased root vs raw parent attribution | `legacyAgentReplyKeepsRawAttributionAndRenderedBlocks` |
| Running background task while main turn settles (stop) | `stopMainReplySettlesOnItsOwnTurnOnly` |

## Error handling

Missing or mismatched evidence returns null; callers wait within existing timeouts and fail with their existing messages. No exceptions added.

## Testing strategy

- JVM `app/src/test/java/de/pyryco/mobile/e2e/AgentReplyEvidenceTest.kt`, written first: the probes in the table, on authoritative facts and one legacy parity case.
- Shared screen probe `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentReplyEvidenceScreenTest.kt` (Robolectric): mount `ThreadScreen` with an authoritative projection and collapsed tool uses, derive ids only from `agentReplyEvidence`, then replay the scenario's rendered steps: closed run under `tool-run:<runId>` hides the paragraph; opening shows exactly one paragraph under `background-agent-child:<block>`; closing hides it; the main reply renders outside the block.
- Existing `ThreadEvidenceTest`, `SettledAssistantReplyTest` unchanged and rerun.
- `compileDebugAndroidTestKotlin`; recorded replay of both methods via `python3 scripts/android-test-gate.py ui --tests 'de.pyryco.mobile.recording.RecordedMobileSuiteTest#…'` with counts. No new device-only test.

## Open Questions

- Does the recorded replay exercise the legacy session as `""` throughout each turn? Resolve from the recorded run.

## Documentation handoff

- Pending for the documentation stage: `docs/e2e-interactive-stream.md`: record the migrated scope of `interactiveTurn_backgroundAgent_replyStaysUnderAgent` and `interactiveTurn_stopBackgroundTask_completesThenRepliesAgain` (evidence observation, settlement, rendered identities), the named recorded results and counts this PR supplies, and that the route exercised is the legacy harness binding; these passes do not prove negotiated activation (#2175).
