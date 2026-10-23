# #2476 — recorded Agent restart reliability

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/NegotiatedAgentRestartProof.kt`: `proveNegotiatedAgentRestart` owns saved-family, graph restart, navigation, replacement-connection and settlement assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `restartApp` fences old-phone replay before destroying the Activity and rebuilding its graph.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforePhoneRestart` delegates to the current replay hub.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` waits for downstream consumption; `beforePhoneRestart` selects existing phone owners only.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `harnessThreadEvidence` observes the real negotiated store passively.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PassiveThreadObservation.kt`: `PassiveThreadObservation` rejects retired subscriptions and connection/generation receipts.
- `app/src/androidTest/assets/recorded/suite/interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect.json`: existing 498-step scenario; retain its exact recording.
- `docs/recorded-tests.md`: explicit graph-restart readiness and downstream-consumption contracts must remain strict.
- `docs/e2e-interactive-stream.md`: negotiated Agent restart and settlement acceptance.
- `docs/knowledge/features/development-verification-test-scheduling.md`: a channel enqueue cannot certify downstream consumption; focused success does not explain an earlier suite failure.
- `docs/specs/architecture/2470-agent-restart-placement.md`: merged passive-observation, current-generation-key and required-fact-readiness repairs already exist and must not be duplicated.

## Context

One deliverable: reliably execute the existing recorded restart/settlement proof, with a causal regression for its remaining intermittent failure. The Linux #2467 bundle `49d8bcba4a25f3d28ea03920a6bbde795f40a2569b00d39c97c94155053c0b77` records failure then same-candidate success at `cfcb4083fb1425576ed18265309fd88f0fb7d2a1`, but its normalized failure element is empty. The original raw paths are absent on this worker. The historical #2367 verifier bundle likewise establishes occurrence, not its cause.

The initial focused Linux invocation at `ecad147dd` passed 1/1. This is diagnostic evidence only. No cause is inferred from that pass. Existing content-free native receipts cover graph replacement, persisted checkpoint, family placement, child navigation and settlement, so inspect the first actual failure before adding synchronization or changing product code.

## Design

Keep the original scenario, assertions, strict request payloads/owners/checkpoints and complete tape consumption. First reproduce the named method with fresh graph/cache state and retain each run's XML and phone log. Locate its first failed assertion or replay boundary rather than treating later cleanup errors as the cause.

The anticipated edit surface is the test-only restart proof and a focused shared/JVM regression for the demonstrated synchronization boundary. Any repair requires a dated revision here identifying the evidence, chosen contract, actual files and controlled failing regression before implementation. A product defect requires production-backed causal coverage before a bounded product repair. No production change, recording refresh, new dependency, timeout enlargement, assertion retry or skip is planned from an unproved hypothesis.

If focused execution cannot reproduce a failure whose retained raw details remain unavailable, request the smallest useful wider investigation with retained bundle references and explain why a narrower selection cannot answer it. Such a request is pending diagnosis, never acceptance.

## State and concurrency model

Retain existing scoped graph, registry, replay hub and UI collectors. Passive assertions must not acquire a cache reader or produce queries. The graph-restart fence runs while old request producers remain alive. Connection settlement observes the original pending send before retirement and requires a different Supported owner afterward. No additional job or dispatcher is introduced without an evidence-backed revision.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Session A finishes and rotates to B | Original recorded method: exact family, child terminals and session boundary. |
| Graph/cache/registry reconstruction | Original recorded method: different objects, exact saved checkpoint/coverage/family before Activity launch. |
| Earlier-session family reopens | Original recorded method: stable daemon identity, full content, finished placement and actual child navigation. |
| Pending session-B send retires its owner | Original recorded method: pending identity at retirement, different Supported connection and exactly one delivered settlement. |
| Same-epoch reset changes generation | Existing current passive receipt, required-fact gate and same-receipt check in `assertUiPlacement`; retain existing `PassiveAuthoritativeThreadObservationTest` rejection controls. |
| Fresh invocation | Three separate repaired native invocations, each with fresh scenario state and counted reports. |

## Error handling

Preserve the first assertion/replay mismatch and all later failures as evidence. Missing or zero-test execution is unverified. Permanent missing facts, changed contents/ownership, wrong checkpoint, incomplete tape and cancelled consumers must still fail within existing deadlines. Logs remain content-free; raw native evidence stays private outside Git.

## Testing strategy

- Establish the cause with retained original evidence plus controlled red/green coverage that drives the responsible implementation.
- Run affected JVM/shared tests, including existing owner/reset rejection controls when their seam changes.
- Run the exact named recorded method in three separate Linux invocations after repair. This device-only scenario exercises real Activity graph reconstruction, on-device cache and background replay work; Robolectric does not replace that proof.
- Retain revision, executed/passed/failed/skipped counts, raw XML/logs and hashes for every attempt under persistent agents evidence storage.
- Run lint, debug assembly, instrumented Kotlin compilation for native/shared changes, focused formatting and forced Spotless checks. After the final ordinary merge of main, push and run debug assembly and `scripts/pre-verify.py --gradle`.
- Complete configured UI/JVM/scripted acceptance belongs to the dispatcher. Historical macOS evidence is context; new macOS execution is outside this Linux ticket.

## Open Questions

- Which first assertion or replay boundary explains this remaining occurrence? Resolve from actual failing evidence before choosing a repair.
- Does failure require preceding test order or machine load? One isolated pass establishes neither.

## Scope assessment

One observable repair and its regression; forecast at most 650 written lines, no new production exports or consumer signature migration, three acceptance criteria. Reassess actual repair boundaries before implementation. The `NegotiatedAgentRestartProof` diff on remote `feature/2175` is the older introduction, already superseded by merged #2470. Remote #2360 changes final teardown in `SuiteConnections`, not the restart proof; avoid overlapping that work unless evidence demonstrates a dependency.

## Revisions

### 2026-10-22 — first-failure evidence remains missing

No executable source changed. Four isolated named-method invocations and a selection containing the original report's preceding three recorded scenarios all passed on Linux: eight total executed/passed, zero failures/errors/skips. The named method executed five times. The three-method prefix was ping context usage, background-Agent following and peer queue consistency; the fresh selected report confirms that order before the named restart method. These results do not establish independence from the earlier non-recorded classes or original machine load.

| Attempt | Source | Gate directory | Executed / passed / failed / skipped |
| --- | --- | --- | --- |
| Isolated 1 | `ecad147dd` | `ui-6s4z6c81` | 1 / 1 / 0 / 0 |
| Isolated 2 | `ecad147dd`; plan-only commit during execution | `ui-ivgjan_y` | 1 / 1 / 0 / 0 |
| Isolated 3 | `11483e34975d42653dcdd20d4daf2923f5cd7c5a` | `ui-zw7fj67s` | 1 / 1 / 0 / 0 |
| Preceding recorded sequence | `11483e34975d42653dcdd20d4daf2923f5cd7c5a` | `ui-jv0ui84n` | 4 / 4 / 0 / 0 |
| Isolated 4 | `11483e34975d42653dcdd20d4daf2923f5cd7c5a` | `ui-3ckjhxvw` | 1 / 1 / 0 / 0 |

All five commands exited zero. Raw gate reports, native XML/protobufs and phone logs, revision receipts and verified SHA-256 manifests are retained privately under agents `logs/evidence/2476/builder-initial-diagnostics/`, in `baseline-1`, `baseline-2`, `baseline-3`, `preceding-sequence` and `baseline-5`. The first three contain 16 verified manifest entries each, the sequence 22, and the final isolated attempt 16. Every named method actually executed without skips. The unchanged first attempts observed saved checkpoint 81 restored exactly, actual connection replacement, same-epoch generation replacement and final placement success.

The original #2467 failure at `cfcb4083fb1425576ed18265309fd88f0fb7d2a1` remains failed, followed by its distinct successful rerun. The retrieved verifier bundle's gate output and retained failure copy contain only normalized XML with an empty failure element. They establish occurrence but cannot identify an assertion or replay boundary. Comparing source confirms the restart proof and `SuiteConnections` match this checkout, while that observed candidate also contains #2467's read-receipt prerequisite orchestration absent from current main. Do not equate the current focused passes with exact-source reproduction of that candidate. The older #2367 verifier bundle likewise lacks the first-failure details needed here. No evidence links the remaining occurrence to a particular previously merged placement repair or the open #2360 teardown repair.

Recovery assessment `d1d37640cc5df8d75b97f904f6a33adbf7c74f476a02fdc36d1481f30740d503` is queued with **zero executed tests**. Its request references this ticket's retained refiner bundle `29f082c5a681b641a7de45dd607a57a41409916b3185c752ce41d0385ce2ac25`, digest `72a2aa1be0f9fbfe58718618a223618257175b9e37be581a52478a68b5c59737`. First recover the original first-failure phone log; if unavailable, assess only the exact named method at the observed `cfcb4083fb1425576ed18265309fd88f0fb7d2a1` revision. No complete suite was requested or launched. The request is retained beside the private diagnostic evidence as `investigation-request.json`.

The assessment becomes eligible only after this worker ends. Cause, controlled regression, repair and repaired-candidate acceptance remain pending. No speculative code change or recording replacement is justified by these diagnostic passes; no implementation PR is opened. Resume implementation from the first actual failing boundary supplied by recovery, updating the design and writing its causal regression before the repair.

### 2026-10-22 — recovered premature replacement-Activity close

The recovered bundle `49d8bcba4a25f3d28ea03920a6bbde795f40a2569b00d39c97c94155053c0b77`
(digest `dd4e208bb4a95b25b78f8c83aa15fac53a698ce25ddeffba0f771949996e3581`),
`logs/verifier-gate__2467_5.stderr.log.failure-1792690822568.log`, resolves the first open
question. At source `cfcb4083fb1425576ed18265309fd88f0fb7d2a1`, the first sticky mismatch
is lifecycle step 69: `phone/host-0/2:close` precedes required `thread_catch_up` and
`test-control:test-end`. Query request 13/checkpoint 0 is unmapped at tape index 480.
The stack originates in `LifecycleConnectionDriver.onStop`; subsequent end/finish
exceptions preserve that mismatch. The separate same-candidate rerun passed. No evidence
establishes the historical host load or requires preceding tests to trigger this race.

`proveNegotiatedAgentRestart` currently uses the replacement Activity as a lexical
resource. It closes before `backgroundAgentReplyProof` fixture/peer cleanup and the
outer `LiveSessionCleanupRule.end`. The final recorded Activity fence therefore cannot
protect the replacement's request producers or inbound consumers. This is distinct from
#2470's already merged passive observation and placement repair.

Add `LiveSessionCleanupRule.withRestartedActivity(activity, proof)`, transferring the
replacement into rule ownership before running the proof. After the existing `end`
callback completes (or fails), close retained Activities exactly once, preserving the
first body/end failure and suppressing later close failures. Extend the restart proof
with an injected activity runner, defaulting to its existing lexical lifetime for the
standalone scripted caller; the interactive caller supplies the cleanup rule's runner.
Do not change replay readiness, consume controls early, or wait for a close whose tape
boundary requires a later end control. All original scenario assertions remain intact.

The causal regression in `SuiteCloseConsumptionTest` drives the real remote repository:
certify a query, reset the same epoch, hold the resulting checkpoint-0 query across proof
return, then release it during ordered session cleanup. It must establish a fresh
generation/checkpoint from the real reply, consume the cleanup control and test-end,
and only then close its resource. First run this against an extraction of the existing
lexical `.use` behaviour and retain its premature-close failure; run unchanged after
ownership repair. Existing missing-query, changed-owner/checkpoint and terminal-tail
negative controls stay enabled. Add rule tests for success, body failure, end failure,
close failure and once-only cleanup. No new job outlives the owned resource.

Additional files read: `LiveSessionCleanupRule`, its JVM tests, `RecordedActivityRule`,
`SuiteCloseConsumptionTest`, `SuiteConnections`, `RecordedSuiteTape`, and
`AuthoritativeThreadQueries.reset`. Compare remote #2360's foreground fence without
importing its older tape/consumption implementation; this repair needs no hub/tape edit.
Remote overlap: #2175 contains the superseded original restart block; the other shared
`InteractiveStreamE2ETest` branches change separate scenarios. Keep the caller edit local.
One behaviour plus its causal proof remains one deliverable, forecast under 950 written
lines, no new production exports, one caller adaptation and three acceptance criteria.
Documentation stage: pending update to `docs/recorded-tests.md`, explicit graph-restart
readiness, and `docs/e2e-interactive-stream.md`, negotiated Agent restart and settlement,
describing replacement ownership and counted Linux evidence.

| Event | Regression |
| --- | --- |
| Same-epoch reset query held beyond proof return | `replacementActivitySurvivesResetQueryAndOrderedSessionCleanup` |
| Body/end/close failure with a retained replacement | `LiveSessionCleanupRuleTest` ownership and failure tests |
| Complete repaired scenario with fresh graph/cache | Three independent focused Linux invocations of the original method |

The controlled fixture requires checkpoint 24 after certifying checkpoint 12, so
already-covered terminal-tail normalization cannot replace the delayed query. Replay
transport closure stores its first mismatch for outer reporting; the fixture calls
`SuiteConnections.awaitIdle` at physical close to surface that error before later
cleanup assertions. Earlier fixture versions failed their explicit lifetime assertion
(one executed/failed each) and remain retained rather than being called replay-boundary
reproductions.

The final controlled pre-repair run executed one test, failed one, skipped none (exit 1).
Its first cause is `Recorded suite lifecycle mismatch at step 3: phone/host/2:close`,
with the real post-reset `thread_catch_up` and fixture cleanup still required. The later
lifetime assertion is suppressed. Raw XML, binary reports and exact extraction patch
against `8b3c30bbe160392db8fe2c48a474eb2783dc3d02` are retained under agents
`logs/evidence/2476/builder-lifetime-repair/regression-red/` with 12 verified manifest
entries. The same folder family retains both earlier assertion failures and the complete
verified original #2467 failure/rerun bundle. Only rule ownership changes for green.

After ownership repair, the unchanged causal test and both existing consumption tests
passed: 3 executed/passed, zero failures/errors/skips, exit 0. Evidence is retained in
`logs/evidence/2476/builder-lifetime-repair/regression-green/` (11 verified files),
including the exact source patch and raw XML. The cancelled-collector negative still
times out under its original bound; terminal consumption is still required before close.

### 2026-10-22 — repaired focused Linux evidence

Pushed implementation `a3bfa8ad7629c75e86c20db9484700e8a237d6c7` passed the exact
recorded method in three separate fresh native invocations. Every invocation rebuilt or
validated its APKs, launched a fresh scenario, and completed the unchanged tape and all
original assertions. No recording, matching policy, retry, skip or deadline changed.

| Attempt | Gate directory | Executed / passed / failed / errors / skipped | Private evidence |
| --- | --- | --- | --- |
| Native 1 | `ui-pjkxu39n` | 1 / 1 / 0 / 0 / 0 | `native-1` |
| Native 2 | `ui-o90tw8ks` | 1 / 1 / 0 / 0 / 0 | `native-2` |
| Native 3 | `ui-6453ivcl` | 1 / 1 / 0 / 0 / 0 | `native-3` |

All commands exited zero. Each attempt has nine verified retained files under agents
`logs/evidence/2476/builder-lifetime-repair/`: raw native XML and phone log, normalized
counts, image/phase diagnostics, revision/APK receipt and manifest. The source revision
above is identical in all three receipts. The app APK SHA-256 is
`6bed26813c48fea169637680df9ad4332dc91e52df19396ae37fae9249e1904f`; test APK SHA-256
is `812ed44f5ac31bc66f6c18ff75beb1a037bd02e00fb04a95d677d54720a1a05a`.

Seven separately selected JVM classes passed 113 tests, zero failures/errors/skips:
`SuiteCloseConsumptionTest` 3, `LiveSessionCleanupRuleTest` 5, `SuiteConnectionsTest`
28, `RecordedSuiteTapeTest` 49, `RecordedActivityRuleTest` 2,
`PassiveAuthoritativeThreadObservationTest` 3, and `AuthoritativeThreadQueriesTest` 23.
The latter six ran at the pushed implementation above and have eleven verified retained
files each in `focused-<class>`; the consumption class's exact pre-commit source patch
is in `regression-green`. Its causal method is unchanged from the retained red run;
only the extracted owner's lexical close changed to rule ownership.

The historical host load and preceding non-recorded classes remain unestablished.
Recovered first-failure evidence plus the controlled query delay resolve the repair
without a wider diagnostic: they demonstrate this lifetime gap without a suite prefix.
The earlier pending recovery assessment supplied the missing stderr rather than a
new native execution. Complete configured acceptance, including counted execution of
the named method in the dispatcher UI gate, and independent review remain pending.

### 2026-10-23 — reopened generation-0 rotation boundary

The newly published #2462 verifier bundle
`db5461c8f6c2f795f941b095d278cb092cf3f6c602d586d56879963b8dcda166`
(digest `6a6b24f31c0b914c6968c7d6cc0f1f10d35cc296709078899f7aaf85ad89e193`)
has 138 verified manifest entries. Its reviewed source is
`2db93ebd8a8280d25c7c071fc11b2af4da55b198`. The initial configured UI run
executed 333 methods: 331 passed, two failed, zero errors, one additional skip.
The separate same-candidate rerun passed both methods without skips. This
occurrence's primary error is a 30000 ms timeout; cleanup then records step 41's
`background-fixture` mismatch. The pending work is generation 0's request 35,
checkpoint 81, before graph replacement. Request 30/checkpoint 77 matched and
its replies make the session divider visible. The failed stderr is
`logs/verifier-gate__2462_5.stderr.log.failure-1792716578639.log`; the rerun is
`logs/2026-10-23T00-49-38-642Z_verifier-gate-rerun__2462.log`.

This differs from the repaired lexical close (generation 2), the earlier
checkpoint-87-versus-0 mismatch, and #2483's pre-rotation run lookup. The original
phone log and effective APK hashes are not included in this bundle; do not claim
their identity or historical load/order beyond the retained reports.

Source exposes a bounded rotation race to test: `authoritativeThreadEvidence`
admits live divider facts before the catch-up terminal certifies them. Reopening
the destination at that point calls `AuthoritativeThreadQueries.acquire` while
the preceding query is active; `enqueue` coalesces the reopen. After its terminal,
no new query remains to supply the recorded checkpoint-81 catch-up. The restart
fence cannot create missing application demand. Test this mechanism before
changing readiness; no product query-coalescing change is intended.

Extract the existing next-session lookup into test-only
`SessionRotationReadiness.certifiedNextSession`. A real-repository regression
holds the rotation terminal while the live divider is already visible, then
reopens through the same lookup and requires the next exact-checkpoint query.
Run the old lookup red before requiring certification of the observed live
version. The repaired lookup must stay unready until the same owned snapshot
has a checkpoint covering its completed version, then reopen once. Keep passive
observation, all original assertions and matching, no retries, recording edits
or deadline changes. Add missing/stale/foreign rotation negatives as needed.

Files additionally read: `AuthoritativeThreadQueries.acquire`, `enqueue` and
`accept`; `ThreadEvidence.authoritativeThreadEvidence`;
`SuiteCloseConsumptionTest`; `SuiteConnections.beforePhoneRestart` and
`beforeClose`; current #2462 and #2483 branch diffs. #2462 changes the later
placement sampler and process-stop fence; #2175 contains the superseded proof.
Keep this earlier rotation edit local. Forecast under 650 newly written lines,
no production exports or signature migrations, one reliability behavior with its
causal regression. If the controlled test does not establish this mechanism,
retain its result and revise the diagnosis before implementing a repair.

| Event | Regression |
| --- | --- |
| Live session divider precedes its query terminal and destination reopen | `rotationReopenWaitsForCertificationBeforeAcquiringItsQuery` |
| Missing, stale or unrelated rotation evidence | Focused readiness negatives beside the causal regression |
| Actual graph/cache replacement and settlement | Three fresh Linux invocations of the unchanged recorded method |

The unchanged Linux baseline at `a48e9ced0e9bc7e9193445d576afb6fb498a7b99`
is queued for shared admission, zero tests executed so far. Complete configured
acceptance remains dispatcher-owned; historical macOS failures remain failed.

### 2026-10-23 — retain controlled omission; historical attribution pending

The unchanged recorded method passed on Linux in `ui-suzgk2r4`: one executed,
one passed, zero failures/errors/skips, exit 0. Its base is
`a5d7e3adb7203bd1f29a426947f9c9bc66bd83fd`, with an unused shared-test lookup
extraction and the new JVM diagnostic present. The native caller and scenario
are unchanged. App APK SHA-256 is
`207d419cd215b34ffbdc0caa0f8d735eec0434f8cc52d5fb42e3662f92905ff0`;
test APK SHA-256 is
`c2af6ca0a175538e867b6e15b7c7d7e1665634b941506774d725487af90cb622`.
The receipt, full input hashes, patch, raw XML/phone log and completion phases
are retained in `baseline-linux` (19 verified manifest entries). This is a
diagnostic pass, not repaired-candidate acceptance.

The first JVM fixture attempt executed once and failed before the intended
boundary: its repository sent before the replay pump reached Open, leaving
checkpoint zero. Preserve `rotation-fixture-red-1` as a setup failure, not causal
proof. The corrected fixture drives Open before constructing the repository.
It then holds the real checkpoint-77 query's terminal while the admitted
session divider and completed version 81 are visible with checkpoint 77.
The original readiness lookup reopens at this point. After terminal release,
the actual request payloads contain only checkpoints 0 and 77, omitting 81.
`rotationReopenWaitsForCertificationBeforeAcquiringItsQuery` fails that exact
request assertion: one executed/failed, zero errors/skips, exit 1.
`rotation-controlled-red` retains six verified files, including raw XML/binary
reports, the exact patch/extraction and receipt. A terminal advancing to 84
prevents normalization from substituting for the missing final query.

This establishes query coalescence in the controlled interleaving. It does not
distinguish coalescence from cancellation or an overlapping reader in the
historical occurrence. That occurrence's original phone traces, effective APK
identities and cleanup receipts for `ui-q2ll4ly_` and `ui-7b4oc0t5` are absent
from the newest bundle and its pointers. Do not infer those binary hashes from
the earlier builder's source-equivalent pass. The original generation-0
checkpoint-81 omission and separate rerun remain retained and distinct from all
earlier failure boundaries.

Recovery assessment
`845d55f150984a3ca26ce592d4315befdef365a8558bd5db1c65e094e3d2731d`
is queued with zero executed tests. It requests recovery of those existing
artifacts first; its maximum proposed execution scope is the exact named
method at `2db93ebd8a8280d25c7c071fc11b2af4da55b198`, never a complete
suite or macOS verification. The request and all new private evidence are under
agents `logs/evidence/2476/builder-recurrence-20261023/`; the original bundle is
in `original-2462`, with all 138 manifest entries verified. The request uses
this ticket's verified refiner bundle
`ea45570c450701d2d663caac33f3dcf9916c823e8a163f570afdcb09bb997074`
as provenance, and names the newly recovered verifier bundle explicitly.

No certification guard has been added and the extraction is not wired into
the native proof. The new diagnostic remains deliberately red on the branch;
no assertion is skipped or retried. Before selecting a synchronization repair,
recover the original reader/query trace and establish whether the demonstrated
coalescing mechanism explains it. Then complete controlled red/green, the three
fresh repaired Linux invocations, affected regression checks and normal final
gates. No new implementation PR or acceptance claim is made in this blocked
diagnostic handoff. Historical host load and preceding non-recorded test effects
remain unestablished; the retained XML names the same three preceding recorded
methods as the earlier investigation, without proving their causality.

### 2026-10-23 — resume certified rotation repair

The preserved controlled red establishes a useful repair despite the unresolved
historical coalescence/cancellation distinction. Require `certifiedNextSession`
to match the current authoritative owner and exact current snapshot, reject
repair/reset/incomplete evidence, and require its checkpoint to cover both the
completed version and the projected rotation fact. Wire that lookup into
`proveNegotiatedAgentRestart` before reopening the destination. Preserve all
later graph, family, navigation, settlement and cleanup assertions.

The repository serializes `accept` and `acquire` on the same query monitor.
Certification publishes during `accept`, before retirement; the reopening
destination must acquire after that terminal finishes, rather than recursively
inside publication. Extend the controlled test to retain terminal consumption
and query lifecycle ordering, and require the exact checkpoint-81 request and
its subsequent checkpoint-84 certification. Readiness negatives cover partial,
missing, stale, reset and foreign ownership, and an unrelated divider. No
production synchronization or query coalescing changes are planned.

One behavior and its causal regression remain the deliverable, under 650 new
written lines (under 1600 total branch work), no production exports or API
migration. Overlaps with #2462 are later placement sampling and process-stop
fencing; #2483 changes the earlier tool-run lookup. Neither is a dependency.
The original phone logs, APK identities and cleanup receipts for `ui-q2ll4ly_`
and `ui-7b4oc0t5` remain missing; preserve that limitation and emit bounded
content-free rotation owner/checkpoint receipts in the three focused Linux runs.

### 2026-10-23 — certified rotation wired; controlled green

The guarded lookup is now wired before the original `openThread`. It compares
the current owner and exact store snapshot, requires checkpoint coverage of its
completed version, and retains exactly one boundary from the previous session
to a different session. The accepted reading supplies the session identity;
later updates cannot make a second lookup fail after readiness was established.
The native owner receipt now includes completed version and a bounded
`certified_rotation` stage. No production source or recording changed.

At `c12e2c03a350df018f09799b12253c78f8edb9d3`, the unchanged pre-repair
control executed once and failed on the missing checkpoint-81 payload. Its
six-file manifest verifies in `logs/evidence/2476/builder-certified-rotation-20261023/rotation-red-current`.
After the guard, `SuiteCloseConsumptionTest` passed 4/4 and
`SessionRotationReadinessTest` passed 7/7, both with zero failures/errors/skips.
Their exact dirty-source patches, raw XML, binary reports and input hashes are
retained in `rotation-green` and `focused-SessionRotationReadinessTest` beneath
that evidence root. The causal fixture preserves the original exact-payload
assertion and adds a post-emission terminal-consumption receipt and an assertion
that partial divider facts leave reopening pending. It then observes the real
checkpoint-81 request, subsequent checkpoint-84 certification and complete tape
consumption. The three existing consumption/ownership controls remain enabled.

| Event | Focused regression |
| --- | --- |
| Partial rotation before its terminal | `rotationReopenWaitsForCertificationBeforeAcquiringItsQuery`, `visibleDividerCannotReplaceItsMissingTerminal` |
| Current snapshot replaced, even within one generation | `aRetainedSnapshotCannotCertifyTheCurrentReading` |
| Connection, host, conversation, epoch or generation changes | `foreignOwnersCannotCertifyRotation`, `receiptMustDescribeItsOwnSnapshotEpochAndGeneration` |
| Reset, missing or unadmitted evidence | `resetRepairAndUnadmittedReadingsCannotCertifyRotation` |
| Unrelated, duplicate or self-referential divider | `unrelatedSelfAndAmbiguousDividersCannotSupplyTheNextSession` |

The historical first boundary is resolved; its precise coalescence/cancellation
interleaving, original effective APK identities and cleanup receipts remain
unestablished. The controlled interleaving establishes the readiness defect,
without attributing historical host load or preceding tests. Native repaired
acceptance and the remaining focused classes are still pending at this commit.

### 2026-10-23 — repaired Linux focused evidence complete

The seven focused JVM classes passed 119 executed tests, zero failures/errors/skips:
consumption 4, readiness 7, passive observation 3, query coordination 23,
connections 28, tape 49 and cleanup ownership 5. The latter five receipts identify
implementation `796d479f4bc1a1e2c7ae4c417e4b03ddef141b6b`; the first two retain
their exact pre-commit patches. Each class ran in its own managed invocation.
The earlier multi-class selection refusal executed zero tests and is retained
separately as `selection-refusal.json`.

After a clean merge of main, including the accepted #2483 pre-restart lookup
repair, pushed source `76f9b235b845cc65f1ce6b1ad7d89f71828a2c02` passed the
exact original recorded method in three independent fresh Linux invocations:

| Attempt | Gate | Executed / passed / failed / errors / skipped |
| --- | --- | --- |
| Native 1 | `ui-6dv8xoii` | 1 / 1 / 0 / 0 / 0 |
| Native 2 | `ui-wcxanwkz` | 1 / 1 / 0 / 0 / 0 |
| Native 3 | `ui-0q5wzbgs` | 1 / 1 / 0 / 0 / 0 |

Every command exited zero, with a clean executable tree. Each `native-N` folder
under agents `logs/evidence/2476/builder-certified-rotation-20261023/` retains
18 verified manifest entries: raw XML/native reports and phone logs, source/input
identities, effective APK hashes and managed-image/completion phases. All three
cleanup receipts finish successfully. Each phone observes certified checkpoint
81/completed version 81 before reopening, then graph/cache reconstruction and
replacement-connection settlement. App APK SHA-256 is
`207d419cd215b34ffbdc0caa0f8d735eec0434f8cc52d5fb42e3662f92905ff0`;
test APK SHA-256 is
`f1bb8fa3a5fc3eaf08ed8fb8282d4b4345a244563d459f9b6bd6583341fd4835`.
Both hashes and the source revision are identical across all three attempts.

Lint, debug assembly and instrumented Kotlin compilation passed after that last
main merge. Final pre-verify remains the next builder check. Complete configured
acceptance, including counted execution of this named method in the dispatcher
UI gate, and independent review remain pending. Historical artifact gaps and
the accepted #2478 repair retain their separate status. No macOS test, complete
suite or genuine Claude turn was launched for this unchanged recording.

### 2026-10-23 — historical acceptance finding routed for refinement

Verifier finding 1 at `6720222f055e2985e2d3722fccb466f13c4416d0`
remains unresolved. The historical first boundary is identified, but its cause
and effective APK identities are not established. The controlled readiness
defect does not distinguish coalescing, cancellation or another reader in the
original occurrence. No historical identity is inferred from the repaired runs.

The authoritative verifier record `c6f33e1223c15230ce8b0f476e7e86d0fc6c34a3f6131998949fdeddacca5ac3`
accepts the implementation and its focused evidence. Its Linux configured UI
gate executed/passed 333 methods, including the exact restart method, with zero
failures/errors and one additional skip. The JVM gate passed 5,365 methods;
scripted acceptance retains its unrelated #2442 failure and separate passing
rerun. These results do not satisfy the missing historical diagnosis.

Rechecked all 138 original recurrence manifest entries and retrieved the latest
retained recovery assessment and #2462 builder/refiner bundles. None supplies
the original `ui-q2ll4ly_` / `ui-7b4oc0t5` phone traces, effective APK identity
receipts or cleanup phases. Recovery assigned the useful certification repair
without changing acceptance criterion 1. The criterion therefore remains open.

Artifact-recovery assessment
`76eec13279d1e25036333c00ca0eeec63be1f18b2ce94614418894bed6dcc244`
is queued with zero executed tests. It references the retained verifier finding
and original failure, requests only recovery of existing artifacts, and asks
for an explicit refinement decision if they cannot be recovered. No new native
execution or wider suite is requested: a later pass cannot recover historical
binary identities or prove that occurrence's interleaving. Preserve PR #2492,
its repair and all counted evidence while resolving this requirement through
refinement. No source, recording, assertion, deadline or acceptance criterion
changed in this handoff; no new tests ran.

## Documentation handoff

- Pending documentation stage: `docs/recorded-tests.md`, “Explicit graph-restart readiness (#2347)”: replacement Activity ownership through ordered cleanup, with causal regression evidence.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, “Negotiated Agent restart and settlement (#2246)”: counted repaired Linux invocations and dispatcher acceptance status.
- Pending documentation stage, both sections above: add the demonstrated partial-rotation/certification defect and its guarded reopen regression, together with the fresh counted Linux evidence. Preserve the original failure and separate rerun, missing historical phone/APK/cleanup artifacts, and accepted #2478 results; qualify each completion claim to the cause actually proved.
