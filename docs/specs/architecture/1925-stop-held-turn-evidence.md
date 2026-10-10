# Diagnose the live Stop permission wait (#1925)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, `STOP_HOLD_PROMPT`, `peerStep` and `awaitPingReplyNamingLayer` establish the unchanged behaviour and the failing pre-Stop seam.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `awaitPermissionModal`, `recorded`, `record` and `dialLink` establish the peer observation and readiness contract.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` and `PingReplyDiagnosis.kt`: preserve timeout causes and reduce raw frames to content-free evidence.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt` and `PingReplyDiagnosisTest.kt`: deterministic wait and frame fixtures.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `hostModals` is an immediately readable phone projection; no diagnostic subscription or network request is needed.
- `docs/knowledge/features/development-verification-test-scheduling.md`: an open link is not turn delivery; evidence needs a named stage and counted execution.
- `docs/knowledge/features/modal-events.md` and `interrupt-affordance.md`: distinguish permission delivery from foreground Stop eligibility.
- `docs/e2e-interactive-stream.md`: the real Stop contract and dispatcher-owned full-live proof.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: interactive event, turn-end and security contracts remain authoritative.

## Context

Both original stderr reports fail before Stop at the 90-second permission wait, with link 1 open and zero replacements. The retained #1870 isolated daemon namespace is `e2e-auto-840bce75`. Its durable history for conversation `55cb9ba3-7182-4b87-aee8-a8ddb72999cd` contains this test-authored exact held prompt, identifying this scenario independently of an inferred test order. The matching daemon log in `pyry-e2e.RQHz3X` creates the conversation at 2026-10-07T17:32:09.432Z and enqueues the phone message at 17:32:10.327Z. History records its user echo at 17:32:38.892336Z, thinking at 17:32:44.190248Z and thinking progress through 17:33:54.038381Z. It contains no tool-use, tool-result or turn-end frame. The stderr permission timeout establishes that the peer observed no modal; durable history alone does not establish whether one was emitted. The next conversation is created at 17:33:42.163Z, consistent with the failed permission wait. This proves turn activity, not a command executing or a model refusal, and does not prove why the prompt was absent.

The #1968 namespace `e2e-auto-9900c196` contains no durable held-command marker. Its daemon log has many permission-posture delivery refusals, but no retained scenario/conversation correlation establishes that they caused this particular failure. Do not claim either a repeated #1456 ping loss or a behavioural root-cause repair. Repair the demonstrated evidence gap, and track the behavioural investigation in #2032 using the new stage evidence. No decision record is needed.

## Design

Add `StopHoldEvidence.kt` under shared tests with a pure frame summary and a synchronous `withStopHoldDiagnostics` wrapper. Only this Stop method uses them. Surround the existing phone send and first permission wait, emitting a submission stage before send, a ready stage on success and a failure stage before instruction/peer cleanup. Include wall-clock milliseconds and a validated canonical UUID conversation identifier so daemon events can be correlated. Retain failure evidence in the assertion message as well as the test-only `StopHoldProbe` Logcat tag.

Reduce only this conversation’s frames to capped counts: user echo, turn state, thinking progress, assistant delta/message, tool use/result, permission/modal shown, refusal, session error and turn end. Emit latest state and stop reason only from fixed protocol allowlists; malformed or unfamiliar values become `unknown`. Phone evidence reads `coordinator.hostModals.value` once and reduces this conversation’s permission presence to a nullable boolean. No pairing material, prompt/command/instructions, message text, model output, raw error codes, identifiers from payloads or tool names are retained in diagnostic output. Output size is independent of frame count and string length.

Classifications report observed stages: permission at peer, permission at phone but absent at peer, turn ended, tool activity without observed permission, other turn activity, user echo only, or no observed activity. They must not equate no observed activity with a turn never starting, tool use with successful execution, or missing permission with refusal. Unknown delivery and absent transient phone state remain explicitly unproven.

Preserve the real held command and prompt, once-only approval, phone Stop, cancelled turn end, Stop disappearance, same-conversation follow-up ping and second turn end, and absent held reply token. No retries, longer deadlines, skipped assertions, production edits, peer changes, shared setup changes or live-selector removal.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888, #1896 and #1898 touch other scenarios in the live class; inspected hunks do not restructure this method. Keep edits local.

Sizing: one deliverable (scenario evidence), approximately 450 written lines including this plan/tests, zero production files, at most two test-only types, no signature migrations, three acceptance criteria and fewer than ten classification/failure branches. Sketch and written-plan counts both satisfy the builder limits.

## State and concurrency model

No new jobs, collectors or persistent state. The instrumentation thread owns the wrapper and its timestamps. Each snapshot reads the peer’s existing retained frames and the phone’s current host projection; snapshots across those observers are not atomic. Do not infer cross-observer ordering from their absence. Peer cancellation and exact host-instruction restoration remain the existing finally paths.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Permission appears after send | `successEmitsStagesAndReturnsWithoutRetry` |
| Permission wait fails before cleanup | `failureRetainsEvidenceAndOriginalCause` |
| No delivery evidence, user echo only, thinking, tool activity, or terminal turn | `observationsDistinguishStartupThinkingToolsAndEnd` |
| Phone holds permission but peer does not | `phonePermissionSeparatesObserverGap` |
| Another conversation or replayed/unknown frame arrives | `otherConversationsAndUnknownStringsDoNotLeak` |
| Cancellation or diagnostic snapshot failure | `cancellationPassesThrough`, `diagnosticFailureDoesNotHideWaitFailure` |
| New invocation under the same conversation identity | `eachInvocationHasIndependentStages` |

## Error handling

Keep existing peer wait deadlines and exceptions. An assertion failure gains the bounded stage evidence and retains its original failure as cause. Cancellation passes through without relabelling. A diagnostic snapshot failure emits a static unavailable marker and cannot replace the wait failure. No diagnostic network I/O or added wait can delay the permission deadline.

## Testing strategy

Write pure summary and callback-wrapper JVM regressions first, run red against contract stubs, then implement and run green. Verify stage order before cleanup, one send/wait only, original-cause preservation, classification, UUID validation, malicious/oversized fields, constant output bounds and per-invocation isolation. Run existing peer-wait, ping-diagnosis and host-instruction regressions alongside them. The live Stop test remains device-only because it requires a real phone/relay/daemon and Claude. The ticket explicitly assigns fresh full-live proof to the dispatcher, so no separate focused live retry substitutes for it.

Run focused JVM tests, lint, assembleDebug, androidTest compilation, Spotless apply/forced check, and final main-merge assemble/pre-verify. The dispatcher must run a fresh passing full live gate and retain tested commit, this named passing testcase and executed/failed/skipped counts. Compilation and diagnostic regressions do not prove that the flake is eliminated.

## Open Questions

What delays the held turn or prevents its permission ask? Unproven; #2032 must use fresh stage evidence. #1968 turn delivery is also unproven. This ticket deliberately claims an evidence repair only.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, stop-running-turn coverage: record #1870’s proven thinking-stage evidence, #1968’s remaining delivery gap, the new bounded probe and #2032. Do not describe diagnostics as eliminating the flake.
- Pending documentation stage: the same file, “Verification status”: retain the fresh dispatcher-supplied full-live tested commit, named Stop testcase and executed/failed/skipped counts.

## Security review

**Verdict:** PASS

- [Trust boundaries] `stopHoldEvidence` filters conversation ownership and maps frames to fixed counters/allowlists; never stringify an envelope or arbitrary payload field.
- [Tokens] No new token generation/storage. Real pairing, keys and instructions remain memory-only in unchanged setup and must never enter probe output.
- [Files and storage] Diagnostics use existing test Logcat and failure reports, not new credential/body files. The canonical UUID is correlation metadata only, never used to construct a path.
- [Android attack surface] Test-only helpers add no exported components, intents, permissions, providers, URLs or rendered content.
- [Cryptography] Existing Noise handshake/static-key reuse is untouched. No transcript, cipher, nonce or key evidence is logged.
- [Network and I/O] Read existing in-memory snapshots only; do not send a diagnostic request, subscribe, retry or extend a deadline. Existing relay limits and TLS remain intact.
- [Errors and logs] MUST FIX addressed in design: allowlist state/stop labels and canonicalize the correlation UUID, cap counters, and omit all arbitrary strings. Regression-test hostile IDs, text, tool names, errors and overlong labels. Only test-authored stages, numeric time, booleans and canonical correlation metadata enter the test-only probe.
- [Concurrency] Synchronous scenario wrapper adds no jobs and snapshots before cleanup. Non-atomic cross-client observations are labelled as observations rather than causal proof; cancellation is preserved.
- [Threat model] Relay drop/delay/flood remains bounded by existing waits; fixed-output summarization adds no plaintext payload logging. Hostile daemon fields are excluded/allowlisted. Phone token theft and UI screenshot/accessibility exposure remain the existing protocol/Keystore contract; this change introduces neither credential storage nor UI content.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10
