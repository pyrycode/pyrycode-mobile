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

No new application state or coroutine jobs. Use synchronized Compose semantics snapshots and the proof's existing 10-second wait budget. Poll readiness, never retry a scenario or a tap, sleep, force expansion, or broaden an owned matcher. Repository completion is not a render fence.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Reset removes the list before the first closed check; the same family returns | Controlled reset/restoration navigation regression, followed by the complete existing pointer proof |
| List exists but belongs to a different Agent | Wrong-family readiness regression; cannot complete until the exact keys return |
| List or owned family never returns | Bounded negative readiness regression; absence must fail |
| Normal settled family opens and closes | Existing settled navigation, prose and authoritative scenario screen coverage |

## Error handling

No swallowed assertion failures. Missing and wrong-family projections remain unready and ultimately fail; ambiguous lists fail. Existing ownership, chrome-clear target, expansion action, child membership and settlement assertions remain authoritative. Logs contain static stages, counts and booleans only.

## Testing strategy

Write and execute the reset regression red before changing the proof, then green. Run affected Agent screen classes and the four proof callers' coverage. New regressions belong in sharedTest and need no device-only wrapper. Existing recorded scenarios require native execution for their asynchronous replay transport and activity lifecycle.

On Linux retain five consecutive separate native executions of `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`, each one executed/passed with zero failures/errors/skips. Preserve all failures, source revisions, raw logs/XML and hashes in persistent private evidence. Record focused regression counts. Complete configured UI/scripted coverage, including this method, remains pending for the dispatcher.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless apply/check, then push after the last main merge and run assembleDebug and pre-verify with the PR body.

## Open Questions

- Does a controlled empty projection reproduce the first missing merged-list assertion? Compare merged/unmerged samples rather than assuming the diagnostic proves visibility differences.
- Is the reset still active at proof entry or initiated only during cleanup? Preserve the original first failure separately from suppressed replay-close errors and report the evidence limits.
