# Current-owner scripted ping read proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread`, original read, unread and peer-read assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: `OwnedReplyProbe.wrap`, preserving current replay readiness and producer recovery while adding a passive trace.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidenceOwner.matches`, strict ownership remains unchanged.
- Retained commit `8534e6ca2babec13ba8ac0981565590fb946dd19`: `PingReadProof`, its seven tests, native ping sampling, metadata trace and attention diagnostic.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: exact-host repository and attention ownership.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `completeAuthoritativeReadSummary`, `refreshUnavailableAuthoritativeReadFacts` and `invalidateWithdrawnReadSummary`; #2493's current correlation and revocable recovery are already wired and stay untouched.
- `docs/knowledge/INDEX.md`, `features/development-verification.md`, `features/development-verification-gates.md`: current-source evidence, strict settlement and focused native boundaries.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: attention precedence and authoritative current repository selection.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: bounded opening-summary recovery, revocable queued authority and independent daemon read facts from #2493.
- `docs/e2e-interactive-stream.md`, Phone read-mark proof (#1912), and `docs/recorded-tests.md`, Unavailable-summary recording integration (#2467): receipt/classification order and historical evidence limits.
- Configured Core `docs/protocol-mobile.md`, Security model: the authoritative wire/security contract; no wire change here.

## Design source

N/A: test proof and content-free diagnostic only; no visual behavior changes.

## Context

Continue PR #2468 under #2494 without modifying its original branch, worktree or evidence.
Prerequisite #2493 / PR #2499 merged through main at `90c0747af00780798f266d48543a5425fb455857`.
The starting main is `ba8c54c3d18a2d3a2f93acfa60b8c2004674a1e8` and includes that merge.
Reuse its metadata/replay/restart implementation; do not transplant older versions of shared helpers.
No decision record is needed.

The authoritative predecessor verifier bundle is run `18ddeeede50af3312b066b5571ec64d526f549729171adc788b23a62599b240c`,
digest `9c15a3b6b49c4423d57457adb506488a19c734fd2290ca637bf83999116e7028`.
Its two configured attempts on `4711539ab3da57ea23670fd46b0a9b48b9d2a478` each ran two methods: one pass, one final-read timeout, zero errors/skips.
Both terminal observations had current exact certification but unavailable shown metadata; confirmed positions differed (9 and 0).
This observation does not identify the cause. Opening-summary recovery and later focused passes do not retrospectively explain either failure.
Keep recovery receipt `1645eda4baf1d7e3bfb6eaff05f6530580dfe8fa123d6e050476668b53bd3081` as assessment-queued, zero executed.
No sequence/load or complete-suite builder invocation is authorized. #2469 retains further occurrence reconciliation.

Remote overlap: #1766, #1947, #2091, #2098, #2104, #2175, #2311, retained #2462, #2511 and #2514.
Their changes concern other methods, additive hooks or existing predecessors; #2514 owns durable-gap restoration.
Keep this extraction local to ping and additive diagnostics; no dependency on their unmerged work.

## Design

Restore internal `pingReadMarkConfirmed` and `PingReadProofTest` from the retained commit.
Keep the captured durable host/conversation/epoch/reply fixed; form a candidate owner using only the current supported connection and snapshot generation.
Require authoritative admitted evidence, exact raw reply, sufficient checkpoint, no repair/reset, available shown metadata and daemon-confirmed read coverage.
After sampling, require identity with a fresh immutable snapshot. No stale certificate or read position can prove another owner.
The native consumer resolves the current concrete repository each sample, reads its evidence and marks, then repeats snapshot, connection and support checks after bounded diagnostics.
Retain the controlled reconnect after initial reply certification and the original 90000 ms final wait.
Retain all original rendering, durable-gap, unread, peer-read, chronology and teardown assertions.

Add only `OwnedReplyProbe.readTrace` and its inbound/outbound callbacks to the current helper; retain `ThreadQueryReadiness.wrap`, `producerSnapshot`, `admittedIfCurrent` and existing receipt regressions.
Restore `HostConversationSource.attentionDiagnostic` without requests or mutation.
The native trace selects fixed frame kinds, bounded numeric metadata and hashed owner coordinates; never log payloads or text.
The callback is cleared in a nested `finally` even if terminal diagnostic sampling throws.

## State and concurrency model

No production jobs, flows or lifecycle state added. The existing synchronized attention diagnostic takes one read-only snapshot.
The passive callback is volatile, installed only around this native proof and cleared on exit.
Each sample may race replacement/reset; reject changed snapshot identity both inside the helper and after diagnostics/support checks.
Reconnect transfers neither sight nor certification; the replacement must independently certify the same exact reply.

## State transitions and identity reuse

| Event | Required proof / test |
| --- | --- |
| Replacement connection with retained durable reply | `replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner`; native controlled reconnect |
| Supported source changes during sampling | `replacementWhileSamplingCannotMixOwnersOrUseRetiredFacts` |
| Missing/behind shown or confirmed positions | `qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation` |
| Incomplete checkpoint, reset, repair or missing epoch | `certificationMustBeCurrentAndCoverTheExactReplyRevision` |
| Reply id reused across host/conversation/epoch or changed raw facts | `reusedReplyIdsCannotCrossHostConversationEpochOrChangedFacts` |
| Same connection reset with reused epoch | `reusedEpochResetDuringReadSamplingRequiresFreshExactReplyCertification` |
| Same connection reset with changed epoch, later certification or same-generation repair | `changedEpochResetDuringReadSamplingCannotProveOriginalReply` and reused-epoch test |
| Native proof exit or sampling error | nested `finally` clears the passive trace; normal scenario teardown remains |

## Error handling

Unavailable evidence returns false and retains the existing assertion deadline; no inferred shown/read values, pending writes or checkpoint-only fallback.
Diagnostics do not issue recovery requests. Product summary recovery remains entirely in #2493.
Retain failures as failures and keep distinct residual causes outside this proof repair after assessment.

## Testing strategy

Restore the seven controlled unit regressions first and execute the retained reset-sampling control without its final snapshot fence; expect the two reset cases to fail for withdrawn certification.
Then restore the reviewed fence and run `PingReadProofTest`, unchanged `ThreadEvidenceTest`, `OwnedReplyObservationTest`, `NegotiatedThreadActivationTest` and affected host-source tests.
The invariant probes above cover delayed facts, duplicate/held positions, foreign identities, replacement and same-connection reset.
Run focused managed `scripted ping` on macOS: both named ping and durable-gap methods must execute/pass, zero failures/errors/skips.
Native execution is necessary for real encrypted daemon/relay transport, foreground read qualification, reconnect and peer confirmation; Robolectric cannot establish these facts.
Preserve raw XML, phone/control/daemon/relay traces, effective source/APK/binary identities, cleanup and verified hashes in persistent private evidence.
Run lint, assembleDebug, instrumented Kotlin compilation, formatting and final `pre-verify.py --gradle` after merging main and pushing.
Dispatcher-owned configured scripted acceptance and independent configured gates stay pending; focused results cannot replace them.
Linux is outside this ticket's verification scope.

## Open Questions

- Historical configured timeouts: cause remains unestablished; retain the existing queued recovery receipt and all failures. No passing retry will be described as causal evidence.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, Phone read-mark proof (#1912): current-owner exact-reply certification, same-connection withdrawal, final snapshot fence, counted integrated proof and residual investigation outcome.
- Pending documentation stage: `docs/recorded-tests.md`, Unavailable-summary recording integration (#2467): integrated native evidence and its distinction from configured acceptance. Preserve historical failures, tailed durable-gap logs, differing protobuf copies and #2493's metadata/replay/graph-restart documentation.

## Security review

**Verdict:** PASS

- Trust boundaries: `pingReadMarkConfirmed` trusts only current supported authoritative evidence and exact raw reply equality; host/conversation/epoch remain fixed. Global `ThreadEvidenceOwner.matches` stays strict.
- Tokens/secrets: neither diagnostic reads credentials. The trace logs only fixed classifications and numeric facts; owner coordinates are hashed, and message/payload/keys/tokens are excluded.
- Files/storage: no application storage changes. Raw evidence stays private with owner-only permissions and hashes; no daemon-authored input becomes a filename.
- Android attack surface: no component, intent, provider, WebView or rendering change.
- Cryptography: existing Noise implementation, keys and counters are untouched; replacement evidence cannot borrow a retired certificate.
- Network/I/O: no new requests, dependencies, timeout increases or retries; #2493 owns bounded metadata recovery. Existing frame caps/TLS/transport behavior remain.
- Errors/logs: bounded native diagnostics, no arbitrary frame types or text. Production diagnostic returns only static flags and numeric positions and emits nothing itself.
- Concurrency: MUST FIX addressed in design: same-connection reset can retain confirmed marks while withdrawing certification; mandatory snapshot identity checks inside the proof and after diagnostics reject it. Callback cleanup uses nested `finally`.
- Threat model: delayed/reordered relay facts cannot qualify retired or changed evidence. Rooted-device token theft, hostile-frame parsing and screenshot/keyboard access remain with existing Keystore, codec and UI protections; this proof introduces no new access path.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-24
