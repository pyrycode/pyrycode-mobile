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

2026-10-21 — The first native candidate `3d7ba31af799e2cae848670716a4695d354c7e4d` executes once and fails the new immediate marker-key guard. Retained phone evidence shows certification reset at the later-reply boundary; an immediate lookup still assumes rendered readiness. Retain this failure under `native-first-candidate-failed/`, without retrying that candidate. Resolve the rendered Agent identity from the same current snapshot that supplies the settled later reply, rather than reusing the earlier running projection's identity, and log only whether that identity changed. Before geometry correction, wait for one list containing this exact marker key; missing and wrong-family keys remain unready, ambiguity fails and the finished label must still belong to the owned marker. Share the original `THREAD_TIMEOUT_MS` budget between repository settlement and marker readiness using monotonic elapsed time and pass only the remaining duration into the local helper. No existing deadline is extended. `resetBeforeFinishedMarkerRevealWaitsForTheOwnedFamily` releases catch-up only after observed absence; the permanent wrong-family test now requires timeout. Keep the late-growth regression and the earlier proof-entry reset negatives. The historical reset timeline and this native failure establish readiness as another necessary condition at this same finished-marker boundary; the new identity diagnostic will distinguish identity replacement from projection-only reset. This remains one local scenario repair, below 450 written lines and with no production type or consumer migration.

2026-10-21 — Candidate `d442de05b415bf9b873210346ca4209f81928f61` passes 25 block tests and pre-verify, but its first native execution fails at `questionAnswerLocation.boundsOrReveal`: the frozen marker key has left the projection. The native log records `refreshed=true`, a ready exact key, then a certification reset, restored rows and `key_index=-1`. This is generation re-scoping during reveal, not merely an absent list before entry. `BackgroundAgentEvidence.renderedAgent` explicitly requires identities resolved from each current bound snapshot because authoritative epoch/generation changes re-scope them; this lesson now governs every reveal sample. Retain this failure in `native-1/` and restart acceptance only after a new repair.

The local finished-marker helper will accept a current-identity sampler, retain one absolute remaining deadline across all readiness samples, and resolve the raw Agent against the current admitted source before each keyed geometry read. Add an opt-in `questionAnswerTargetWithCurrentKey` bridge to the existing private geometry implementation, with a current-key callback. Keep the existing public `questionAnswerTarget` and `questionAnswerTap` signatures and default behavior unchanged: the fifteen existing consumers need no migration, while the private geometry function has only three direct callers including the new bridge. This replaces the earlier decision to leave that private implementation untouched; duplicating its chrome correction would create two algorithms. Preserve its three measurement attempts, strict key-removal failure for default callers, and one tap per transition. No exception is swallowed and no scenario or tap is retried.

`generationReplacementDuringFinishedRevealUsesTheCurrentOwnedKey` replaces the real screen's projection during geometry measurement, removes the old marker key, introduces the same owned Agent under its replacement key, and appends later rows that dispose its marker. It requires current-identity resolution and positive final finished-marker display. Run this red against the frozen-key helper and green after the opt-in bridge. Retain existing missing/wrong-family negatives and run affected default geometry coverage: block/prose/authoritative classes, the short-thread question scroll method, focused scripted background-Agent scenario, and five separate native passes on the final pushed code. The scope remains one scenario repair; no public consumer migration, production types, recording edits or deadline increases are introduced.

2026-10-21 — The controlled generation-replacement probe executes once and fails before repair with the exact native `tap target left the projected list` assertion through `boundsOrReveal`, with `key_index=-1` after its old-key-absent/new-owned-key-present checks. Retain XML and the frozen-key source patch under `generation-red/`. Implement the opt-in bridge and current-identity sampler; its deadline is absolute, shared across all samples, and its default callers retain their previous constant-key behavior.

2026-10-21 — On `d9350a7907df61c304795cdefafc4334e96d863a`, separate native executions one and two passed; execution three failed with `performScrollToKey` reporting that the sampled marker key could not be found, before geometry measurement. Retain all three under `generation-native-*/`; these are not five-pass acceptance. The generation can change between readiness and keyed scroll dispatch, so refreshing at geometry boundaries alone is insufficient.

Add `generationBetweenReadinessAndScrollDoesNotDispatchAStaleKey`, replacing the owned generation immediately after a successful readiness sample. For this opt-in finished-marker path, validate the exact key's current index and invoke `ScrollToIndex` together on the UI thread. A detached list or missing key dispatches no action and remains unready within the same absolute deadline; ambiguity still fails. The default question-answer helper retains its strict removed-key assertion and existing behavior. No scenario, assertion or tap retry, extra physical input, deadline increase, production or recording change is introduced. Run the new regression red first, then the complete affected class green and restart five native executions on a new pushed revision.

2026-10-21 — The readiness-to-dispatch regression executes once and fails before repair with the same `performScrollToKey` missing-key exception as native execution three. Raw XML and the pre-repair source patch are retained in `dispatch-red/`. Implement the atomic index validation/scroll dispatch in the opt-in helper; its readiness and geometry checks still use the same absolute budget and final owned finished-label assertion.

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
| Catch-up temporarily removes the finished marker before reveal | `resetBeforeFinishedMarkerRevealWaitsForTheOwnedFamily` |
| Generation replacement changes the owned marker key during geometry measurement | `generationReplacementDuringFinishedRevealUsesTheCurrentOwnedKey` |
| Generation changes after readiness and before keyed scroll dispatch | `generationBetweenReadinessAndScrollDoesNotDispatchAStaleKey` |
| Expected marker is missing but another finished Agent is present | `anotherFinishedAgentCannotSatisfyTheOwnedMarkerCheck` |
| Owned marker is running while another Agent is finished | `anotherAgentsFinishedLabelCannotSatisfyTheOwnedMarkerCheck` |
| Reset/wrong family before pointer proof | Retain `resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily`, `missingListCannotPassTheAgentNavigationProof`, `anotherAgentsListCannotPassTheAgentNavigationProof` |

### Testing strategy and handoff

Run the new growth probe red against the extracted old one-shot operation, then green; retain raw XML and source identities. Run the full affected Agent block class plus existing prose/authoritative proof classes. Retain five separate consecutive Linux native passes of the named recorded method on the pushed repaired revision and the unchanged recording identity, with XML and phone logs. Run the focused scripted background-Agent scenario and required lint/assembly/compilation/format/final gates. Complete configured UI/scripted acceptance remains pending for the dispatcher, including this method. macOS is outside scope.

## Documentation handoff

Pending for documentation stage: `docs/knowledge/features/thread-screen-subagent-tool-rows.md`, Agent navigation evidence, and `docs/knowledge/features/development-verification-compose-evidence.md`, Compose evidence: distinguish this earlier finished-marker reveal race from proof-entry readiness; record the new controlled red/green and native source/recording identities without claiming the historical geometry was captured.

## Recurrence repair plan (2026-10-22)

### Files read and context

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerLocation` unconditionally follows the atomic current-key reveal with a separate matcher scroll.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `assertFinishedAgentMarker` already supplies absolute-deadline readiness and atomic index/scroll dispatch; its geometry reads resample current identity.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: preserve earlier growth, generation, reset and wrong-family probes; use the real screen for the new boundary.
- `docs/knowledge/features/thread-screen-agent-navigation-evidence.md` and `development-verification-agent-reveal-evidence.md`: earlier acceptance is historical; preserve three geometry measurements, exact ownership and default helper behavior.
- Original #2387 failed/rerun logs in fleet bundle `1cac1378fe6fc1580d9df9e42e70367c6fb81b974eabdd229a9c6632ec1bd7ed`, digest `9333794885bccfb789b342ec160b879816d7d4d4359528f35e2e19c78d95dd6c`: the first assertion is the redundant `performScrollToNode` inside the finished-marker bridge on `eda298fca8e0584107dbbb1ec3993ffd13b45eb6`. Its merged scroll container is absent; the diagnostic's later unmerged match is not a simultaneous snapshot. The summary XML marks this case failed, then passed on the same tree; stderr retains the assertion stack. No suppressed replay-close failure or phone timeline is retained for this occurrence. Do not transfer older teardown explanations to this one.

One useful behavior: finish revealing the exact owned marker across a reset after atomic keyed reveal. Both previous repairs are on baseline `2df547ab26c1264ee79d9aca16709702487ec5cf`. The new failure is before navigation entry readiness, and after the atomic keyed-scroll boundary added by #2430. No product, visual, recording or protocol change; no decision record. No remote feature branch overlaps the two intended files at planning time.

Forecast: under 250 written lines including this revision and probes; zero new exported types, no consumer/signature migration, four criteria and fewer than ten rejection branches. The causal probe and local repair form one deliverable.

### Design and error handling

Add a content-free `keyed-ready` evidence stage after the current-key readiness check, before the existing unconditional matcher scroll, so a controlled probe can publish a real empty projection at exactly that boundary. Run the probe against the unchanged operation and retain the missing merged-list assertion. Restore the exact owned family only when the next identity/readiness sample observes the reset.

For the opt-in current-key path, use its existing atomic keyed reveal directly and proceed to the existing bounded geometry measurements. Omit the redundant matcher scroll only on that path. Every geometry read continues through current-key readiness; an absent or wrong-family projection must time out within the original absolute deadline. Default `questionAnswerTarget` and `questionAnswerTap` retain their matcher scroll, strict key-removal assertion and signatures. No exception catching, scenario/tap retry, extra input, forced expansion, sleep or deadline/measurement increase.

### State and concurrency model

No application state, jobs or lifecycle changes. Controlled test publication uses `runOnIdle`; the existing identity sampler owns readiness and its original absolute deadline. All three geometry measurements and final owned finished-text assertion remain authoritative.

### State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Reset removes the list after atomic reveal; the same owned family returns | `resetAfterKeyedRevealWaitsForTheOwnedFinishedMarker` |
| Same boundary resets permanently, or returns only another finished Agent | `resetAfterKeyedRevealCannotAcceptAnotherFinishedAgent` |
| Earlier growth, reset, generation replacement and stale dispatch | Retain the complete `BackgroundAgentBlocksScreenTest` class, including its existing negative probes |

### Testing strategy

Run the new reset regression red on the baseline containing both landed repairs, then green with the local branch change. Run all Agent block/prose/authoritative screen coverage and the short-thread question scroll caller. Run the focused scripted background-Agent scenario and five consecutive separate native invocations of the named recorded method on the final pushed Kotlin revision, retaining fresh XML/logs and source/recording hashes. Native replay needs the real asynchronous activity/transport lifecycle. Run lint, assembly, instrumented compilation, formatting and final pre-verify after merging main. Complete configured UI/scripted acceptance remains dispatcher-owned and pending; macOS is outside scope.

### Open questions

The historical stack identifies the unsafe operation, but the original phone timeline and failure-time semantics geometry are unavailable in the published bundle. The controlled reset will establish the mechanism without claiming it recovers that historical interleaving.

2026-10-22 — `resetAfterKeyedRevealWaitsForTheOwnedFinishedMarker` executed once and failed on the baseline containing both prior repairs, at the same `questionAnswerLocation` / `performScrollToNode` missing merged-list assertion as #2387. The only helper addition in that red control was the content-free boundary callback. The real screen had no list after reset; restoration required the next readiness sample, which the old matcher scroll never reached. Raw XML and the exact source patch are retained under `recurrence-20261022/reset-red-complete/`. Omit that redundant scroll only for the current-key bridge; default callers and the three geometry measurements remain unchanged. The original bundle's 138 file hashes were verified. Its summary marks this method failed once and passed once on the two-method same-tree rerun; no phone timeline or suppressed teardown stack is present, so historical render ordering remains unknown.

2026-10-22 — The repaired causal and wrong-family probes pass in the 28-method block class, alongside all earlier reset, growth, generation/dispatch and ownership negatives. Separate affected selections pass eight prose methods, four authoritative scenario methods and the short-thread question-scroll method: 41 executed/passed, zero failures/errors/skips. These reports retain their exact pre-merge working patches. No test/helper Kotlin changed afterward. Main `206eb3927d29819b7b2ff11cad760c6e5dcaa192` merged without conflicts; lint, APK assembly and instrumented Kotlin compilation passed.

Five consecutive separate native executions of `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished` pass on clean pushed revision `890702783a7611403693f8e7ce1b4a0426b73b29`, each one executed/passed with zero failures/errors/skips. Each retained phone log includes `keyed-ready` and `closed-confirmed`. The unchanged recording SHA-256 is `61709080f051154c15f0125e99e367ab011d0e8963f0074e6621ee18facd7baa`. The focused scripted background-Agent selection passes both placement/settlement and restart/reconnect methods, zero failures/errors/skips, on the same Mobile revision and daemon `2c68b595440dd7e8e843af9cff0d9a708bcc81a7`. Private XML, phone logs, source/recording metadata, failed control and isolated daemon/relay diagnostics with verified hashes are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2373/recurrence-20261022/`. The cause is recorded on #2373. Historical render timing remains an explicit evidence limit; no design question remains open. Full configured UI/scripted acceptance and the documentation handoff remain pending for their owning stages.

### Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen-agent-navigation-evidence.md`, finished-marker recurrence, and `docs/knowledge/features/development-verification-agent-reveal-evidence.md`, atomic scroll dispatch. Record the post-keyed-reveal reset boundary, its causal evidence, relationship to both earlier repairs and counted Linux acceptance; preserve historical failures and evidence limits.
