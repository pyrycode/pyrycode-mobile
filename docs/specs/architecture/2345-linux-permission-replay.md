# Linux permission-isolation replay reliability (#2345)

## Files read

- `docs/recorded-tests.md`: Complete native suite, Scenario host-removal readiness and Atomic transport close distinguish required phone work from peer completion and preserve strict replay.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification.md`: counted focused native checks and dispatcher ownership of complete gates.
- `docs/specs/architecture/2349-permission-isolation-cleanup.md`: the required repair moved from split parent #2343 into merged #2349.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionPrompts_heldPerConversation` retains all A/B isolation assertions and now fences pairing removal.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PermissionIsolationCleanup.kt`: `withPermissionIsolationCleanup` closes the peer, waits for phone readiness, then removes the host, retaining earlier failures.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` resolves the current phone owner in replay.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` waits within the existing 30-second deadline for required requests and queued delivery.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteThreadReadReceipts.kt`: `delivered` tracks query certification; `permits` formerly discarded earlier valid windows when newer live facts arrived.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteReadReceiptsTest.kt`: `authoritativeTape` supplies strict read-admission fixtures; the genuine permission recording supplies the residual race and invalidation probes.
- `app/src/test/java/de/pyryco/mobile/e2e/PermissionIsolationCleanupTest.kt`: controlled delayed catch-up/delivery, missing-work deadline, exact owner/payload rejection and failure-preservation regressions.
- Retained #2266 verifier bundle `529932756cd59581e2f8dd545096dd66ab9f8afd3781aa6b2a6688576ed78947`: original Linux failure and focused rerun, including the first stored mismatch rather than only its cleanup rethrows.

## Change

Verify the one requested behaviour on Linux after consuming #2349, merged as `313f151cc10e101675c717f750ca6bc56c13c502` with implementation `eaeb636274498f67d50738de3f7b6ca54db3c830`. The original #2266 failure closes `phone/host-2/0` through `RelayConnectionRegistry.reconcile` while a required `thread_catch_up` remains unsent (`matched=45 remaining=14`). Test-end and delivery errors rethrow that stored mismatch. Peer-observed turn end does not establish phone readiness; the activity-teardown fence runs after scenario pairing removal. Reuse #2349's controlled regression and cleanup fence; add no redundant repair if three fresh focused native runs and the existing regression confirm this cause is covered. Commit a content-free evidence index at `app/src/test/resources/verification/permission-isolation-2345/evidence.json`, retaining raw reports, diagnostics, source revisions, hashes, counts and failed attempts outside Git. If a distinct residual cause appears, revise this plan before implementation and preserve strict matching and every original isolation assertion. No production request, fixture, deadline or visual change is planned. Forecast under 300 written lines, zero new exported types or migrated callers, three acceptance criteria and no new state machine. No in-flight numeric feature branch touches either proposed new path.

## Testing strategy

Run the existing `PermissionIsolationCleanupTest` class on Linux. Reuse its prerequisite's controlled red evidence: `hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion` fails with old cleanup and passes with the fence, requiring both the actual catch-up send and delayed correlated reply before removal. Run `de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation` through `scripts/android-test-gate.py ui --tests` in three separate fresh invocations. Each must execute and pass, with zero failures/errors/skips; inspect raw XML and permission-stage diagnostics. The existing native test exercises real Android graph/background scheduling; no new device-only test is added. Compare the original gate sequence/load and any later Linux occurrence before claiming the same cause. Focused passes alone do not prove suite-order independence: the configured complete Linux UI gate remains pending for dispatcher/verifier acceptance. Request wider recovery assessment only if focused evidence cannot answer a concrete residual question. macOS is outside this ticket's scope. Run lint, assembly, formatting and final `scripts/pre-verify.py --gradle` after the final merge/push; preserve counted evidence in the PR.

## Documentation handoff

- Pending for documentation stage: `docs/recorded-tests.md`, Scenario host-removal readiness / Counted evidence: add this ticket's Linux diagnosis, three fresh focused results and later configured dispatcher UI result, retaining historical failures and the distinction between focused proof and suite acceptance.

## Revisions

### 2026-10-21: probe the distinct later read rejection

The retained #2333 verifier bundle `aa50c1275b8fff3d751fa35116ada6a633cf53041f15fa238f708222402efbd5` at `b832d64e85` reports a different first failure: `Read receipt checkpoint rejected: target=13 previous=0 delivered=false maximum=null action=10`. The peer's B-answer send rethrows the earlier sticky failure. The gate counted 336 executed, 335 passed and one failed, with one unrelated skip; its focused comparison passed 1/1. No replay read-evidence source changed between that revision and the inspected main. Do not attribute this to the cleanup fence.

Probe `SuiteReadReceiptsTest.aReadQualifiedByDeliveredCertifiedContentSurvivesANewerLiveRevision`: certify a real query window, deliver a newer live revision, then send the read qualified from the earlier delivered window. `SuiteThreadReadReceipts.permits` currently consults only the latest presentation, so a pending newer revision may erase proof that the old read was valid. Confirm the controlled failure before choosing a repair. If confirmed, retain only actually observed eligible watermarks for the exact owner/conversation/store generation; incomplete queries, foreign owners/hosts/conversations, unknown watermarks and reset/repair generations must remain rejected. Never derive eligibility from a peer or a merely enqueued response. A same-epoch reset requires new query certification. Run focused read-receipt, tape, connection and cleanup regressions plus three fresh native invocations after any repair; earlier native passes remain baseline evidence. Reassessed forecast: approximately 500 written lines including evidence, no exported types/signature migrations and the same single scenario reliability behaviour. No new production contract or recording is needed.

## Context

The controlled probe executed once and failed once against unchanged replay code with `Read receipt checkpoint rejected: target=11 previous=0 delivered=false maximum=11 action=0`. Its raw XML and exact sources are retained in agents `logs/evidence/2345/builder-20261021-linux/read-race-control/`. Two current native passes remain baseline evidence. The original close race is covered by #2349; this ticket repairs the residual in-flight read-evidence race, without changing product read admission.

## Design

`SuiteThreadReadReceipts` will remember the watermarks that passed its existing complete-delivery/certification predicate at each delivered-frame boundary, keyed by phone owner, conversation and store generation. A later live revision cannot revoke evidence for an already qualified in-flight read. New watermarks still need their own delivered metadata, complete query certificate and compatible shown rows. `permits` uses only retained eligible evidence from the exact owner, or the existing same-phone/host retention policy. Reset generations and repair/reset flags invalidate earlier evidence. Existing `SuiteReadReceipts.send` still validates complete payload shape, monotonicity, action bounds and response correlation. No scheduler, query matching, recording, production state or ViewModel change is required.

## State and concurrency model

The evidence map belongs to the existing tape-scoped read tracker. The tape/hub synchronization already serializes request and delivery bookkeeping; no coroutine, dispatcher, lock or lifetime is added. Each replay creates a fresh tracker. Record eligibility before and after a delivered frame, so both the old window and newly certified windows are represented. Repair clears the affected family/conversation evidence; generation equality fences reset and epoch reuse.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Certified window, then newer live revision before the pending read send | `aReadQualifiedByDeliveredCertifiedContentSurvivesANewerLiveRevision` |
| The original recording's A answer adds its notice after checkpoint 13 | `theRecordedPermissionAnswerCannotEraseTheEarlierCertifiedReadWindow` |
| New watermark arrives without a new complete certificate | `aNewWatermarkStillNeedsItsOwnDeliveredCertification` |
| Same epoch resets, followed by fresh query certification | `aSameEpochResetNeedsFreshCompleteEvidenceForTheRepeatedWatermark` |
| Wrong host, client or conversation tries to borrow retained evidence | `rememberedEvidenceCannotCrossConversationHostOrClient` and existing attribution tests |
| Query is only enqueued/partial, mismatched or repaired | `aQueryRepairCannotRetainOldReadEvidence` and existing certification tests |

## Error handling

Unknown/unqualified reads still fail with the existing sticky mismatch. Reset/repair cannot recycle an older certificate. Missing cleanup work retains #2349's existing deadline and failure ordering. No exception or retry is converted to success.

## Open Questions

The original-recording probe independently failed 1/1 before the repair: the pre-answer checkpoint-13 predicate passes, then the captured answer notice erases its eligibility. Raw XML and exact sources are retained in `recording-read-race-control/` beside the first controlled failure. This establishes the residual mechanism without a wider sequence diagnostic; the same failure can occur within a single fresh tape. No unresolved design question remains. Preserve both historical Linux failures and all controlled/native attempts. Complete configured Linux UI acceptance remains dispatcher-owned; focused proof does not establish suite-order independence.

## Verification

On committed repair `ca074c0e7161a9cf68d79c74a9531064b58fabbf`, three separate fresh Linux native invocations each executed/passed the named permission method, with zero failures/errors/skips (8.353, 8.275 and 8.528 seconds). Each log completes all 26 original permission stages. The five affected JVM classes passed 109/109, including all six new read-evidence probes and #2349's seven cleanup probes. Lint, debug assembly and instrumented Kotlin compilation passed. The content-free index at `app/src/test/resources/verification/permission-isolation-2345/evidence.json` identifies historical failures, both controlled reds, baseline passes, exact revisions, report hashes and persistent raw diagnostics.

The launcher refused multi-class JVM selection and aggregate recorded native-class selection before execution. JVM classes subsequently ran separately; aggregate native coverage stays with the configured dispatcher UI gate. This is a scope restriction, with zero executed tests, not additional passing coverage. Full configured Linux UI acceptance and the documentation handoff remain pending for their owning stages.
