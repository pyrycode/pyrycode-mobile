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
