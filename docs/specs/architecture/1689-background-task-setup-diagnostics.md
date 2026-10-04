# #1689 — Identify background-task live setup failures

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`, `answerChat`, `runningToolPeer`, `peerStep`, and `openBackgroundTasks`; preserve real task and phone assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, and `linkState`; handshake and encrypted readiness probe precede task observation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `callOnLive`; preserve replacement-only retry and cancellation.
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt`: report only the sealed state's class, excluding daemon-authored version metadata.
- `docs/specs/architecture/1698-peer-static-key-lifecycle.md` and `docs/specs/architecture/1686-peer-pairing-key-continuity.md`: landed identity repair; current peer uses `PeerDeviceKeyStore` without duplicating custody.
- `docs/specs/architecture/967-reconnect-composer-controls-live.md`: Revisions permits Finished or No background tasks after the empty roster.
- `docs/knowledge/features/thread-composer-footer-actions-menu.md`: count excludes finished tasks; roster can remove completed cards.
- `docs/knowledge/features/development-verification-test-scheduling.md`: label setup waits and require counted execution; existing key-store tests alone do not replace live proof.
- `docs/e2e-interactive-stream.md`: rung-3 ownership and retained #1698 full-suite proof.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model; daemon binding is authoritative.

## Context

Original #1631 branch/base XML records 53/19 executed, 20/17 failed, and zero skipped; the named scenario failed with an unnamed 30000 ms coroutine timeout. Both chat setup and peer opening use that deadline, so the stack and teardown launcher focus cannot locate the wait. Retained primary daemon logs contain 102/84 static-key-mismatch rejections with bound-to-other-key reasons. #1698 is closed and its repair is present.

The retained dispatcher full run after #1698 (`2026-10-03T23-52-08-061Z_real-claude-gate_#1698.log`) records 53 executed, 52 passed, one unrelated question-answer failure, zero skipped; this background-task method passed. Its retained primary daemon log (`pyry-e2e.shYptv/daemon.log`) has zero mismatch/bound-to-other-key events. No remaining scenario-local task defect is established. Add actionable setup diagnostics, without changing authentication, task timing, or assertions. No decision record is needed.

Sizing: about 260 written lines, three implementation/test files plus this plan, no production changes, zero exported production types or simultaneous consumer updates, three acceptance criteria, one timeout branch. One deliverable: protect the existing live scenario's setup diagnosis.

In-flight overlaps: #1631, #1642, #1683, #1690, #1691, #1693, #1694, and #1695 touch `InteractiveStreamE2ETest`; their edits add a panel entry or alter other methods/helpers. This change stays local to this scenario's setup, leaving `answerChat` and `peerStep` unchanged; no redesign dependency.

## Design

Add internal test-only `createLiveChatWithDiagnostics` in `sharedTest`, generic in repository and chat types. It accepts the current repository StateFlow, typed relay-status StateFlow, unchanged overall/replacement deadlines, and create/rename operations. Execute the existing `callOnLive` create-then-rename contract, tracking static stages: await live repository, create discussion, rename discussion. On a coroutine timeout, report the stage, current repository presence, and relay-state class only. Re-evaluate state at failure time; never stringify repository, chat, status fields, or exceptions beyond the timeout cause.

Use this helper only in the named scenario in place of its `answerChat` call, retaining the same unique name and operation order. Label the phone connection wait locally with the typed relay-state class and repository-presence boolean. Use existing `peerStep` for peer opening. Keep all real-Claude, privileged approval, pill/menu positive count, reported type, completion count zero, Finished-or-empty, and unreported-state rejection assertions unchanged.

## State and concurrency model

The helper owns no scope or jobs. One caller-owned bounded coroutine tracks the current step across `callOnLive` retries, resetting to create when a replacement repository arrives. Existing replacement retry, overall 30000 ms deadline, and cancellation remain unchanged. The instrumentation test uses its existing bounded `runBlocking` bridge; peer cleanup stays in `finally`.

## Error handling

Only `TimeoutCancellationException` becomes a stage-labelled assertion. Non-timeout failures and external cancellation propagate unchanged. No larger timeout, authentication retry bypass, synthetic task frames, skip, or log is introduced.

## Testing strategy

Write failing JVM regressions first for missing repository, stalled create, stalled rename, failure-time relay status (including hostile UpdateRequired metadata excluded from output), replacement retry, success, and external cancellation/non-timeout failure propagation. Use `runTest`, synthetic objects, and typed status flows. Then implement and run these plus existing live-read, peer-key, and peer-wait coverage. Existing real task scenario remains device-only because it requires the relay, installed phone UI, privileged peer, and real Claude.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, formatting, forced spotlessCheck, and one deterministic reconnect scenario (zero Claude turns). The builder does not run real Claude. Dispatcher must run a fresh full `python3 scripts/android-test-gate.py live` suite after verification, recording tested commit, executed/failed/skipped counts and explicit execution/pass of the named method before documentation and merge. `## Live tests` is `all` to preserve sequential token reuse.

## Open Questions

None. Prior repaired-suite evidence supports the shared identity diagnosis; fresh acceptance on this change remains a dispatcher handoff.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status and background-task coverage: record original/repaired diagnosis, setup labels, fresh dispatcher full-suite counts and named-method result.
- Pending dispatcher live proof before documentation and merge: fresh full-suite XML and command result, with the named background-task testcase explicitly passed; prior #1698 evidence is not this branch's acceptance result.

## Security review

**Verdict:** PASS

- [Trust boundaries] Operations consume existing decoded repositories/chats. Diagnostics read only static stages, a repository-presence boolean and the sealed relay-state class; no daemon-authored field gains trust.
- [Tokens, secrets and credentials] No new custody, rotation or key access. Reuse landed peer repair. Tokens, keys, pairing records, chat names and message bodies must never enter the diagnostic.
- [Files and storage] No runtime file, path, backup or storage changes; evidence belongs outside the worktree.
- [Android attack surface] Test-only helper and existing instrumentation method; no components, intents, providers or permission changes.
- [Cryptography] Existing vendored Noise IK and daemon first-key binding remain unchanged; no session/key/nonce changes.
- [Network and I/O] Existing retry and deadline contracts remain intact. Relay class names exclude `UpdateRequired.minClientVersion`; no endpoint or raw error is printed.
- [Errors, logs and telemetry] SHOULD FIX: regression-test failure-time state and exclusion of adversarial version text and object representations. Keep timeout-only causes; do not wrap arbitrary credential-bearing failures in new diagnostics.
- [Concurrency] Caller-owned coroutine, no detached jobs; external cancellation propagates. Replacement retry remains bounded by the existing total deadline.
- [Threat model] Protocol-mobile prompt injection, server-id impersonation, malicious relay, token theft, hostile frames and UI leakage retain existing mitigations. Delayed/dropped relay traffic receives a bounded content-free setup failure; no new persistent secret or screenshot surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
