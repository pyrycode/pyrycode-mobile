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

## Documentation handoff

- Pending documentation stage: `docs/recorded-tests.md`, “Explicit graph-restart readiness (#2347)”: replacement Activity ownership through ordered cleanup, with causal regression evidence.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, “Negotiated Agent restart and settlement (#2246)”: counted repaired Linux invocations and dispatcher acceptance status.
