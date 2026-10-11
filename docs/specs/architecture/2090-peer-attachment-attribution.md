# #2090: attribute peer-attachment live failures before cleanup

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `answerChat`, `restartApp`, `assertOpensAndSaves`, `awaitConnected`; distinct waits currently share anonymous coroutine deadlines.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `uploadAttachment`, `sendMessage`, `recorded`, `linkState`; observe existing state without new requests or identity changes.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `describe`; extract only link counts, never its dynamic end text.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `connectionFor`, `selected`, `hostConnections`; observe target readiness separately from compatibility selection.
- `scripts/e2e-evidence.py` at `de958e45e`: `safe_fields`, `log_records`, `read`, `capture`; explicit projection and bounded head/tail retention.
- `scripts/test_e2e_evidence.py` and `scripts/e2e-emulator.sh` at `de958e45e`: persistent, private, status-preserving export before and after owned process teardown.
- `docs/knowledge/features/development-verification-test-scheduling.md` and `development-verification-emulator-evidence.md`: missing state remains unknown; passing retries and accepted handshakes cannot diagnose this timeout.
- `docs/knowledge/features/attachment-upload.md`, `attachment-retrieval.md`, and `docs/e2e-interactive-stream.md`, “What rung 3 is made of”: preserve multi-chunk upload, real turn, cleared-cache history and byte proofs.

## Context

Historical Mobile `2bd039944754f24c920feef0efaec36a8ea7f6db`, merged with `ea3ab23e7e14aac63e59a3d05d28473e3905323f`, daemon `63aabec70dad3948c916332a139eb22533b8f4b8`, Claude 2.1.280: 65 executed/56 passed/9 failed/0 skipped, then 9/6/3/0. The assigned method failed with only a 30000 ms coroutine timeout, then passed. The recovered reports and three newer target passes establish no causal operation. Evidence is recorded on #2090 in `issuecomment-6114524566`. This recovery first repairs attribution and retention, then requires a failing capture before selecting a causal repair. It is not a repaired-flake claim or a diagnostic closing PR. No decision record is needed.

Reuse #2088's capture commits through `de958e45e` by merge. Its independent unresolved attachment mechanism does not need to land before these additive diagnostics. Other live-class overlaps (#1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2089, #2091, #2092) change other methods/list entries; keep additions local. #2108 is the open prerequisite for the historical HP capture, not evidence of this flake's cause.

Sizing: approximately 500 new written lines plus 731 reused capture lines, under 1600 total. Two new internal test types; one target caller and the three callers of private `assertOpensAndSaves` (default observation remains inert). No production, wire, UI design, dependency or state-machine changes. Three issue acceptance criteria, one repair deliverable; causal implementation remains contingent on evidence and must be re-sized when established.

## Design

Add `PeerAttachmentStage` and `PeerAttachmentEvidence` under sharedTest so the same wrapper used by instrumentation has JVM tests. Fixed stages distinguish peer opening, initial channel list/phone connection, chat creation and rename, upload, send acknowledgment, turn completion, graph restart, cache clearing, post-restart channel list/connection, thread opening, history retrieval, file open, file save and saved-overlay assertions. Each operation executes once; its existing deadline remains inside the wrapper. Record started/completed/failed, then rethrow the identical Throwable. Wrap open/save separately with an optional private-helper observer; leave all assertions intact.

Keep per-scenario chat/attachment IDs only after their actual creation. The target's observed create/rename uses the same `callOnLive` block and shared outer 30-second deadline as `answerChat`, recording the created ID before rename; no new retry is introduced. Snapshots read existing target repository/relay/daemon status, selected-host identity, peer link count and recognized frame counts. No readiness request or waiting is added. Hash host routing identities, validate UUIDs, cap integer values and allow only static enum states. Observation failures emit unknown through a best-effort fallback. Record before cleanup while graph, peer and fixtures still exist.

Extend the exporter's field and stage allowlists, including observation status, hashed target/selected identities, attachment ID, peer link/stored/ack/history counts and cache-cleared proof. Keep its private archive, input/output bounds, unknown states and pre-teardown lifetime. Add the helper to source-file provenance hashes. Raw credentials, messages, filenames, paths, errors and keys never enter these records.

## State and concurrency model

No application jobs or StateFlows change. Test snapshots are synchronous reads of current state, not atomic across phone and peer. Inline operation wrappers allow the existing suspend calls and outer coroutine cancellation to behave unchanged. Logger/clock/snapshot failures cannot replace a scenario failure. The shell owns bounded persistent export; reuse #2088's cancellation and export-failure controls.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Each distinct 30-second boundary stalls | `PeerAttachmentEvidenceTest.distinctDeadlinesRetainTheTimeoutAndAttributeTheOperation` |
| Parent cancellation during a stage | `PeerAttachmentEvidenceTest.cancellationIsRethrownBeforeCleanup` |
| Observer, clock or logger fails | `PeerAttachmentEvidenceTest.observationFailuresRemainUnknownAndPreserveTheFailure` |
| Operation succeeds and identifiers are reused | `PeerAttachmentEvidenceTest.repeatedStagesRunOnceAndUseFreshSnapshots` |
| Cleanup destroys the observed graph or peer | `PeerAttachmentEvidenceTest.failureAndFinalSnapshotPrecedeCleanup` |
| Late records in noisy bounded logcat | `EvidenceRetentionTest.test_peer_stages_survive_bounded_noisy_logs` |
| Export fails or is cancelled | Existing `test_export_failure_keeps_staging_and_original_status` and `test_cancellation_preserves_status_and_archive` |
| Hostile fields or stage labels contain credentials | New Kotlin redaction test and exporter peer allowlist test |

## Error handling

Record only failure status, never Throwable text. Preserve the original exception object, cause and deadline. Missing snapshots are unknown, not zero or disconnected. Logger failure gets one bounded fallback attempt, then remains unobserved. Export failure retains staging and original runner status. No retry-until-pass or longer timeout is introduced.

## Testing strategy

Write and run controlled timeout/retention tests before implementation. Baseline anonymous 30000 ms stalls must have no stage attribution; the new wrapper must report the correct stage while retaining the exact timeout at the same virtual time. Run focused evidence, cleanup and orchestration Python regressions and affected JVM peer/interval tests. Compile Android tests, assemble, lint, forced Spotless and pre-verify. Instrumentation remains device-only because it uses a real Noise peer, app restart and Android viewer/save intents. No new device-only test is added. A focused scripted ping checks the reused runner export with zero Claude turns.

After #2108 clears, coordinate one ordinarily authenticated HP gate with the original 65-method selection and historical Mobile/daemon/Claude revisions plus this capture overlay. Record tooling substitutions, actual method order, counts, XML/logcat and private persistent paths. Localize a reproduced failure before adding causal repair/regression under Revisions. Passing capture cannot close #2090. The dispatcher owns the fresh full passing live gate before documentation, explicitly executing/passing the assigned method and retaining all four counts, source revisions and XML/logcat locations.

## Open Questions

- Which operation and mechanism caused the original occurrence? Unresolved; no historical peer-identity/outbox repair is inferred.
- Can the historical HP selection complete after #2108's device-loss repair? Pending that prerequisite; diagnostics can be prepared independently.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “What rung 3 is made of”, peer-attachment paragraph: describe attributed stages and persistent evidence limits, then record the established mechanism/regression and fresh full-live named pass when available. Do not publish private raw logs.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed: the Kotlin snapshot formatter and exporter independently validate explicit fields, static stages, bounded counts, UUIDs and hashes; reject arbitrary dynamic strings.
- [Tokens] MUST FIX addressed: no pairing/token/key access, environment dump or raw Throwable text; planted credentials must be excluded from both emitted and exported observations.
- [Files/storage] Reuse private, unique archives, bounded head/tail reads, symlink rejection and atomic writes; no new app storage or backup exposure.
- [Android surface] No component, intent, provider or manifest change; existing digest/grant/save assertions remain required.
- [Cryptography] Only SHA-256 of routing IDs for correlation; peer keys, Noise variant, nonce lifecycle and identity custody stay unchanged.
- [Network/I/O] Snapshots make no request and add no deadline, suspension or retry; original peer/frame bounds remain in place.
- [Errors/logs] MUST FIX addressed: observation failures become unknown and cannot mask the original exception; logs carry static status only.
- [Concurrency] SHOULD FIX addressed: failure and final snapshots precede cleanup; controlled cancellation verifies object identity and ordering. Non-atomic cross-link readings remain an explicit limit.
- [Threat model] Hostile daemon text cannot become a new log record; credential theft on a rooted device and existing viewer grants are outside this diagnostic change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12
