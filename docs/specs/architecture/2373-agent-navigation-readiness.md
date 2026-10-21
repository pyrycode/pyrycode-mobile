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
