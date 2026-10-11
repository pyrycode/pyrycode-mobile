# Localize the missing Stop held-turn permission (#2032)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/StopHoldEvidence.kt`: `withStopHoldDiagnostics` misses non-assertion send/wait failures; `stopHoldEvidence` already bounds payload reduction.
- `app/src/test/java/de/pyryco/mobile/e2e/StopHoldEvidenceTest.kt`: original assertion cause, cancellation, redaction and count-cap contracts stay required.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, `sendFromPhone` and `STOP_HOLD_PROMPT` define the unchanged real interrupt exercise.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `record`, `recorded`, `awaitPermissionModal` and `allowOnce` retain arrivals and enforce once-only approval; dismissal has no conversation id.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt`: phone decoded receipt differs from projection and rendering; correlate dismissals through the prior shown modal.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `modalEvents` and `hostModals` provide receipt and held-state observations without network I/O.
- `scripts/e2e-emulator.sh`: `cleanup` deletes successful-run daemon logs; retain a private copy while the admitted gate is running.
- `docs/knowledge/features/development-verification-test-scheduling.md`: link readiness and absent durable modal history do not establish emission or delivery.
- `docs/knowledge/features/modal-events.md` and `interrupt-affordance.md`: modal dismissal routing and Stop eligibility remain separate contracts.
- `docs/e2e-interactive-stream.md`: existing rung-3 Stop assertions and prior evidence limits.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: authoritative modal and security wire contract.

## Context

The demonstrated fault is missing diagnostics when Send readiness or malformed modal decoding throws an exception. The behavioural cause of missing permission is still unproven. Complete the capture prerequisite, obtain one admitted authenticated focused run, then localize using correlated daemon delivery/permission-bridge evidence. A passing capture without localization returns to refinement, with preserved evidence and changes, rather than claiming a behavioural repair. No decision record is required yet.

## Design

Extend the existing wrapper to capture every non-cancellation `Exception` as well as assertions. Preserve the exact non-assertion object; preserve the existing assertion wrapper/cause chain. Diagnostic snapshot exceptions/assertions reduce to a static unavailable marker. No raw exception text enters new evidence.

Add one test-only bounded temporal recorder beside the existing helper. Record local wall-clock observations from before submission through the complete scenario result, before host-instruction/peer cleanup. Distinguish peer retained wire arrivals, phone decoded modal receipts, phone outstanding-state transitions and sparse UI observations. Associate a dismissal with a previously observed permission id in memory, never publish ids or payload text. Cap tracked modal identities and timeline entries at 64 and counters at 999; report overflow/observer reset explicitly. Sampling timestamps prove observation time, not emission time, and sparse/conflated state cannot exclude transient rendering.

Add a read-only peer accessor returning this conversation's permission lifecycle frames, including dismissals matched to its shown ids. Do not alter peer authentication/readiness, transport, waits or approval. Scenario-local coroutine collectors/sampling bracket the existing body. Synchronous UI samples at submission, permission-ready, post-dismissal and final result are labelled separately; no selector/assertion/deadline changes.

Retain capture input identity (Mobile commit and dirty diff hash, daemon source revision and binary hash/version, Claude version), fresh raw XML/logcat, private daemon logs/history/session references, submission/result interval and canonical conversation/session mapping. Publish only a checked content-free manifest under `app/src/androidTest/assets/stop-hold-2032/`; raw artifacts remain private. Causal conclusions require delivery and permission-bridge evidence correlated to that interval/session. Delayed/missing delivery, terminal refusal/end, tool activity and observer loss remain distinct, with unsupported conclusions marked unproven.

Only after localization, reassess the causal repair against this plan and sizing, write its failing regression and revise the plan before implementation. External ownership requires an owner issue/native blocker; product changes or scope beyond this scenario return to refinement. Preserve the held command, approval once, phone Stop, cancelled first end, Stop disappearance, same-thread real ping/second end, absent held reply token, #1696 readiness and #1721 exact instruction restoration.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009 and #2057 touch other live methods; their hunks do not restructure Stop. Keep changes local and additive.

Sizing after design: one investigation/repair deliverable, five acceptance criteria, about 750–950 total written lines including tests/plan/evidence, one new test-only type, one wrapper consumer, fewer than ten error/classification branches. No production changes planned; reassess causal ownership/size after capture. Both sketch and written plan meet the hard boundaries.

## State and concurrency model

One scenario-owned `CoroutineScope(Dispatchers.Default)` samples retained peer frames and phone projection every 100 ms; a separate child collects decoded phone modal events starting undispatched before submission. Cancel and join all children in the scenario's inner finally before exact instruction restoration and peer close. No new production flow/job or network request. The recorder synchronizes mutation/snapshot; data is invocation-local. Callback snapshot failures cannot mask the primary failure. Instrumentation-thread UI reads stay outside background collectors.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Send-readiness exception before permission | `sendReadinessExceptionRetainsEvidenceBeforeCleanup` |
| Malformed modal decode after submission | `malformedPermissionDecodeRetainsTheSameException` |
| Assertion or exception in diagnostic snapshot | `nonAssertionSnapshotFailurePreservesOriginalException`, existing snapshot assertion tests |
| Cancellation in body or snapshot | existing `cancellationPassesThrough`, `cancellationDuringSnapshotPassesThrough` |
| Permission shown then dismissed between projection samples | `receiptsRetainTransientPermissionAndCorrelateDismissal` |
| Replayed modal/foreign conversation or foreign dismissal | `replayedAndForeignModalsDoNotChangeOwnedCounts` |
| Projection transitions, sparse UI and observer reset | `projectionRenderingAndReceiptAreDistinctObservations` |
| Flood/cap overflow and malicious payloads | `temporalOutputAndIdentityStorageAreBoundedAndContentFree` |
| Same conversation reused in a new scenario invocation | `temporalInvocationsDoNotShareIdentityOrState`, existing independent wrapper stages |
| Scenario success/failure before cleanup | focused live named Stop method; recorder final snapshot and cancellation/join in inner finally |

## Error handling

Cancellation propagates without a failure snapshot. Non-cancellation snapshot errors emit unavailable evidence and retain the original failure. Collector failures are reported with fixed observer-unavailable labels, never exception messages; loss of observation prevents a causal claim. Missing authentication/prerequisite/admission means environment failure with zero executed, not a reproduced Stop failure. Preserve all existing cleanup/cause contracts.

## Testing strategy

Write exception and temporal regressions first; run them red before helper implementation, then green with existing `StopHoldEvidenceTest`, `PeerWaitTest`, `PingReplyDiagnosisTest` and `WithClearedHostInstructionsTest`. The existing live method is device-only because it requires real phone input, authenticated Noise peers, isolated daemon and Claude. Run the exact focused gate selector required by the issue after diagnostic/temporal checks and androidTest compilation, with launcher login lookup escalated and installed SDK/JDK. Read fresh XML and count actual selected testcase execution. Retain raw artifacts privately before harness cleanup. Run lint, assemble, compileAndroidTest and forced Spotless; push recoverable work before final main-merge assemble/pre-verify.

A separate causal red/green regression and repair are mandatory if localized. The later dispatcher owns a fresh full live gate with the named Stop passing testcase, tested commit, raw XML and executed/failed/skipped counts. Focused capture, diagnostics and a passing rerun do not replace that acceptance gate or prove elimination of the flake.

## Open Questions

What prevents the absent permission? Unproven until correlated capture. Can current daemon logs establish bridge emission/delivery? Explicitly report unavailable evidence. If the capture passes without localization, route to refinement with the exact remaining gap and preserved branch; do not invent a behavioural fix.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, stop-running-turn coverage under “What rung 3 is made of” and “Verification status”, with proven cause/repair, limitations, artifact references and fresh counted dispatcher full-live result. Documentation records evidence, does not obtain capture. Do not describe diagnostic-only changes or passing reruns as eliminating the flake.

## Security review

**Verdict:** PASS

- [Trust boundaries] Recorder consumes untrusted payloads only through fixed type/class matches and in-memory opaque modal identity equality. UUID correlation is validated; arbitrary strings never enter output. Test foreign/replayed/hostile frames.
- [Tokens] Login is obtained only by the approved live launcher; no auth/pairing/key/instruction material enters capture manifests. Preserve existing authentication and static-key reuse.
- [Files and storage] Raw XML/logcat/daemon/history artifacts remain private in task-specific temporary/build directories; publish paths and SHA-256 plus fixed counters only. Review extracts before committing. Never copy credentials or use payload-derived paths.
- [Android attack surface] Test-only observation adds no exported component, provider, intent or UI content.
- [Cryptography] Existing Noise and Keystore untouched; no decrypted bytes/transcripts or keys logged.
- [Network and I/O] Existing managed admission/isolated host only; no diagnostic request, deadline increase or retry. Relay drop/delay remains bounded by existing waits.
- [Errors and logs] MUST FIX addressed: same original non-assertion exception, original assertion cause and cancellation retained; snapshots never publish raw messages. Timeline labels/counters/timestamps only, with explicit caps/overflow and unproven cause.
- [Concurrency] Scenario scope cancels/joins before cleanup. Synchronize recorder access. Label sampling/StateFlow conflation and sparse UI observations; absence is not emission proof.
- [Threat model] Malicious relay delay/flood produces bounded observation, no plaintext disclosure or new hang. Hostile daemon fields are filtered/capped. Existing rooted-device token theft, screenshots/accessibility/IME exposure are unaffected by test-only diagnostics.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

- 2026-10-11: androidTest compilation established that coordinator `modalEvents` is private. The scenario follows its public `currentRepository` with `collectLatest` and collects the concrete remote repository's decoded modal stream; replacement cancels the prior collector and records an observer reset. No production visibility or API changes. Modal identities are retained only as fixed-size private SHA-256 values, with a 64-entry cap; these hashes never enter evidence.

- 2026-10-11: a realistic Send-readiness regression and bytecode inspection established that `ComposeTimeoutException` extends `Throwable` directly, neither `Exception` nor `AssertionError`. Capture `Throwable` after the assertion arm, excluding cancellation, to preserve the exact original object. Diagnostic snapshots also handle non-cancellation `Throwable`, so the real Compose timeout cannot bypass failure capture or mask a primary failure.

- 2026-10-11: focused capture at clean Mobile `5f99a626cf411d8769ac408473d487865ad902e4` passed the named Stop method once (1 executed/passed, 0 failed/errors/skipped). Both clients observed shown/dismissed permission and the two required turn ends. No missing-permission fault or cause was reproduced, so the required outcome is refinement with retained evidence, without a behavioural repair or closing PR. Preserve the existing UUID validator for the final result marker as well as submission/permission markers; that output-only hardening occurred after capture and is not behavioural proof. The capture also exposed uncalibrated phone/host wall-clock domains and sparse UI samples; do not infer cross-clock chronology or transient UI absence from them.
