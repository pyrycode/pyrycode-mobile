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
