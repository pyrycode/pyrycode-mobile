# Wait for the owned Agent projection before proving navigation

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `verifyAgentRunNavigation` immediately reveals the closed run before its first navigation tap.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerLocation` requires the merged scroll container; its chrome and single-tap guards remain intact.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished` reads owned evidence from the phone repository, then invokes the proof while reset/catch-up can still change the screen.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: real-screen fixtures and the existing settled navigation proof.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` replaces the message list with an empty-thread surface when the projection empties.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md` and `development-verification-compose-evidence.md`: retain exact ownership, collapsed representative membership, physical taps and positive nested-child visibility.
- #2246 retained failure and rerun: first closed-run lookup fails before input; later replay-close mismatch is suppressed teardown fallout. The phone logs show reset and zero projected rows after `late_agent_tools`.

## Context

One behaviour slice: make the existing recorded Agent navigation proof tolerate a demonstrated transient projection reset, without accepting missing or unrelated content. No product or visual change and no decision record. A matching unmerged node in the failure diagnostic alone does not prove a merging defect: that diagnostic can read a later tree.

## Design

Reproduce the first closed-run check over a controlled real-screen reset, then restore the same owned family. Add a local readiness boundary to `verifyAgentRunNavigation` only if that reproduces the failure. Require one merged message list and the exact Agent root, marker and closed-run representative in its key mapping before revealing the control. Keep the original closed/open/closed proof and every ownership and visibility assertion. Report static readiness counts through the existing evidence callback so a controlled regression can release its reset after observing the unavailable projection. Do not change the fifteen-consumer question-answer helper.

Shared-file overlap: #2246 adds optional owned-child keyed reveal later in this same proof. Preserve that independent change when it reaches main; our entry readiness boundary is additive and local.

Forecast: approximately 300–450 written lines, three acceptance criteria, no new exported types, no signature or consumer migration, fewer than ten reject branches. The repair and its causal regression are one independently useful deliverable.

## State and concurrency model

No new application state or coroutine jobs. Use synchronized Compose semantics snapshots and the same 10-second duration already used for proof transitions; their existing deadlines remain unchanged. Poll readiness, never retry a scenario or a tap, sleep, force expansion, or broaden an owned matcher. Repository completion is not a render fence.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Reset removes the list before the first closed check; the same family returns | `BackgroundAgentBlocksScreenTest.resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily`, followed by the complete existing pointer proof |
| List exists but belongs to a different Agent | `BackgroundAgentBlocksScreenTest.anotherAgentsListCannotPassTheAgentNavigationProof`; cannot complete until the exact keys return |
| List or owned family never returns | `BackgroundAgentBlocksScreenTest.missingListCannotPassTheAgentNavigationProof`; absence must fail |
| Normal settled family opens and closes | `BackgroundAgentBlocksScreenTest.settledNavigationThenOwnedPointerTapsOpenAndCloseALongChildRun` and `AuthoritativeScenarioScreenTest.settledAgentRunNavigatesOpensAndClosesOverDaemonBuiltThread`, plus existing prose coverage |

## Error handling

No swallowed assertion failures. Missing and wrong-family projections remain unready and ultimately fail; ambiguous lists fail. Existing ownership, chrome-clear target, expansion action, child membership and settlement assertions remain authoritative. Logs contain static stages, counts and booleans only.

## Testing strategy

Write and execute the reset regression red before changing the proof, then green. Run affected Agent screen classes and the four proof callers' coverage. New regressions belong in sharedTest and need no device-only wrapper. Existing recorded scenarios require native execution for their asynchronous replay transport and activity lifecycle.

On Linux retain five consecutive separate native executions of `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`, each one executed/passed with zero failures/errors/skips. Preserve all failures, source revisions, raw logs/XML and hashes in persistent private evidence. Record focused regression counts. Complete configured UI/scripted coverage, including this method, remains pending for the dispatcher.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless apply/check, then push after the last main merge and run assembleDebug and pre-verify with the PR body.

## Open Questions

- Does a controlled empty projection reproduce the first missing merged-list assertion? Compare merged/unmerged samples rather than assuming the diagnostic proves visibility differences.
- Is the reset still active at proof entry or initiated only during cleanup? Preserve the original first failure separately from suppressed replay-close errors and report the evidence limits.

## Revisions

2026-10-21 — `resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily` fails before repair: one executed, one failed, zero errors/skips. It mounts the real screen with a settled owned family, publishes an empty reset, then invokes the original proof. The failure is the same missing merged-list `performScrollToNode` stack through the first `assertClosed`, before input. Both merged and unmerged lists are absent in this controlled state. This establishes a reset/readiness mechanism, not a persistent semantics-merging defect. The historical diagnostic's unmerged lookup may sample the returning list; it cannot establish simultaneity. Historical logs put reset and zero projected rows after `late_agent_tools` and before destruction, while the replay-close mismatch is explicitly suppressed under the original assertion. They do not expose the exact semantics snapshot at the instant of failure.

The local readiness boundary uses the existing 10-second wait duration and a single synchronized list sample to require `msg:<agentId>`, `agent-start:<agentId>` and `msg:<runId>`. It emits content-free readiness changes and does not catch assertions, scroll, or tap while unready. The reset regression releases catch-up only after observing absence, then executes the complete original navigation/open/owned-child/close proof. Permanent absence and another Agent's list must time out; ambiguity fails immediately. No product, recording, request matching, deadline constant, shared question-answer helper, or caller signature changes. The original baseline also passed one native execution, with six owned children and two late tools on main's existing recording; #2246's retained failed candidate used its newer genuine recording with three late tools. Keep these source/evidence identities distinct. All Open Questions are resolved to these stated evidence limits.

2026-10-21 — Final coverage mapping names the implemented regressions above. The reset regression changed from one failed execution before repair to a pass with the complete pointer proof; all 21 block, nine prose and four authoritative scenario tests passed. Five consecutive separate native executions of the named recorded method each executed/passed once with zero failures/errors/skips on clean pushed code revision `528c4f966cf2af40e88c77283619845eda96be79`; the focused scripted background-Agent caller also passed once. Raw XML/logs, source metadata and verified hashes are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2373/builder-20261021/`. This final plan clarification changes no tested Kotlin or recording. Full configured UI/scripted acceptance remains pending for the dispatcher.

2026-10-21 — Main advanced to include #2246 during final verification. The merge gate correctly rejected the stale branch. Merge its newer genuine recordings and optional owned-child keyed reveal without changing this ticket's readiness boundary, then repeat the causal screen class and five native executions on that merged recording. Preserve the earlier five passes as older-capture evidence, not acceptance of the newer capture. This resolves the recorded shared-file overlap; no recording is authored or modified by this ticket.

2026-10-21 — The new `finishedMarkerIsRemeasuredAfterLaterReplyGrowth` executes once and fails at the original finished-label display assertion under the extracted one-shot operation. Its preceding assertions establish that the exact marker key remains projected while the finished marker is no longer displayed. This reproduces stale reveal across later-reply rendering on the already-repaired baseline. The repair checks the exact key, calls the existing bounded keyed geometry correction, then asserts the finished text on that same owned marker. Only the failing later-reply check changes: the earlier finish wait already waits for status composition and is retained. This narrows the proposed two checks to one consumer without changing its deadline. The #2334 raw gate logs, first-assertion XML and same-tree passing XML are retained under `recurrence-20261021/historical-available`; the original phone timeline was inspected before verifier cleanup, but that scenario's phone log was not preserved in its selected archive. The exact historical geometry remains unknown.

2026-10-21 — The first repaired class run passes 23 tests but the growth probe fails its intermediate expectation that the marker must become hidden: the initial keyed reveal establishes a history anchor and prevents that displacement. Keep the same late-growth stimulus, exact projected-key and final finished-display assertions; replace that intermediate failure-state expectation with a stronger final chrome-clear geometry assertion. The original red evidence still demonstrates the original one-shot failure. This adjusts the probe to accept prevention as well as correction of the race; it does not relax ownership, finished status, final visibility or collapsed membership. Retain this failed probe separately as `keyed-anchor-probe/`.

## Recurrence repair plan (2026-10-21)

### Files read and context

The original files above were inspected again on `403971e49`, together with `AgentStartMarker` and the owning Agent/Compose evidence topics. The new #2334 failure on `2b4001820ed8b6dc409993dee99c0b4038ac5e05` is the finished-marker display assertion after the later phone reply, before `verifyAgentRunNavigation`. The retained native XML reports that original assertion; phone logs show reset/catch-up and further content publication in the same interval. Replay delivery/checkpoint mismatches are suppressed cleanup failures. The same-tree rerun passes once, zero failures/errors/skips. There is no geometry snapshot at the original reveal, so its exact render interleaving is unknown.

One deliverable remains: correctly reveal and assert the exact finished Agent marker while the later reply's rendered layout can still change. No product/visual/recording changes or decision record. Forecast: 200–350 written lines including tests and this plan, no new exported types, two local consumer updates, three criteria and fewer than ten rejection branches. #2367 overlaps the native scenario file and the screen fixture's mount/projection setup; these edits are local to a new probe and the two finished-marker reveals and need none of that branch's changes. Other remote overlaps are outside this method.

### Design and error handling

Extract the existing finished-marker reveal/assertion into `assertFinishedAgentMarker` beside the Agent proof so the real-screen regression drives the same operation as the native scenario. First retain the one-shot behavior for the red control, adding only a content-free geometry evidence seam. Then use the existing keyed `questionAnswerTarget` reveal/remeasurement with the exact Agent marker key, description matcher and finished label. Require the exact marker key to remain projected; missing keys and another Agent must fail. Keep the finished-label display assertion after the corrected reveal. Use this local helper at both finished-marker checks; do not change `questionAnswerLocation` or its fifteen consumers.

The controlled probe grows a short later reply into a tall settled reply after the initial marker reveal, requires that the exact finished marker remains projected but is no longer displayed, then requires the corrected reveal to show it. This models repository completion preceding screen projection/layout, independently of the earlier proof-entry reset. Preserve all original readiness negatives, pointer/open/child/close proof, final ordering, strict replay matching and deadlines. No extra tap, forced expansion, sleep or scenario retry.

### State and concurrency model

No application state, jobs or lifecycle changes. Controlled state publication uses the synchronized Compose evidence seam; real execution uses existing Compose synchronization and bounded geometry corrections, with no new deadline.

### State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Finished marker is displaced by late later-reply rendering | `finishedMarkerIsRemeasuredAfterLaterReplyGrowth` |
| Expected marker is missing but another finished Agent is present | `anotherFinishedAgentCannotSatisfyTheOwnedMarkerCheck` |
| Owned marker is running while another Agent is finished | `anotherAgentsFinishedLabelCannotSatisfyTheOwnedMarkerCheck` |
| Reset/wrong family before pointer proof | Retain `resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily`, `missingListCannotPassTheAgentNavigationProof`, `anotherAgentsListCannotPassTheAgentNavigationProof` |

### Testing strategy and handoff

Run the new growth probe red against the extracted old one-shot operation, then green; retain raw XML and source identities. Run the full affected Agent block class plus existing prose/authoritative proof classes. Retain five separate consecutive Linux native passes of the named recorded method on the pushed repaired revision and the unchanged recording identity, with XML and phone logs. Run the focused scripted background-Agent scenario and required lint/assembly/compilation/format/final gates. Complete configured UI/scripted acceptance remains pending for the dispatcher, including this method. macOS is outside scope.

## Documentation handoff

Pending for documentation stage: `docs/knowledge/features/thread-screen-subagent-tool-rows.md`, Agent navigation evidence, and `docs/knowledge/features/development-verification-compose-evidence.md`, Compose evidence: distinguish this earlier finished-marker reveal race from proof-entry readiness; record the new controlled red/green and native source/recording identities without claiming the historical geometry was captured.
