# #2114 — background Agent later-reply diagnostics

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished`, `hostRepository`, `sendFromPhone`, and `awaitPingReplyNamingLayer`; localize the later send/wait, preserving all placement/navigation assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `recorded` and `linkState`; independent authenticated connection observations.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PingReplyDiagnosis.kt`: `PingReplyEvidence`; its completion-count shortcut cannot prove an exact answer.
- `app/src/test/java/de/pyryco/mobile/e2e/PingReplyDiagnosisTest.kt`: fixture patterns for pure diagnostic tests.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `Message` and `AssistantSegment`; main-lane identity and streaming completion.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `observeMessages`, `observeQueue`, and `observeTurnPhase`; read independently of composition.
- `docs/knowledge/features/development-verification.md` and `thread-screen-testing-background-agent-viewport.md`: counted evidence and unchanged placement/navigation coverage.
- `docs/e2e-interactive-stream.md`: rung vocabulary and the documented ping diagnosis limitation.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: authoritative interactive frames, user-message delivery and security model.
- `/tmp/builder-2114/evidence/diagnosis.json`: prior passing run lacks exact peer/repository/composed snapshots; separate clocks differ by approximately 1.64 seconds.

## Context

The historical timeout has no established owner. Add scenario-local diagnostics before considering repair; a passing rerun establishes only its own run. No production change or decision record is proposed.

Shared-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100, #2102, #2104 and #2112. Inspected branch diffs edit other scenarios except #2112's earlier launch request; preserve it when merged. No required dependency.

## Design

Add test-only `LaterReplyDiagnosis.kt` under sharedTest and JVM regression tests under test. Five internal types: probe, peer reading, repository reading, composition reading, observation. No API migration. The probe holds a pre-send peer-frame count/identity baseline and repository-row baseline. Observations at baseline, after send, and success/failure are emitted immediately as content-free structured Logcat evidence before any subsequent scroll or cleanup.

Peer parsing groups main-lane deltas by turn and sequence, excludes baseline frames and turns, and compares their joined text exactly with the expected answer. Record correlated hashes of the delivered user message and answer turns, each turn's exact flag, length and completion. Include separately labelled host-envelope timestamps; never order these against the phone clock. User delivery and queue observations name the later prompt only by equality and hashed identity. No wire field joins user-message identity to answer turn: record that causal join as unknown rather than claiming it.

Read the owning host's current phone repository directly through the registry, without waiting for repository availability or requesting history. Bound each flow first-reading to one second, retaining null as unknown. Exact assistant rows must be new, main-lane and finished/streaming reported independently; retain hashed row/turn identity. Observe queue/phase independently so one unavailable flow does not erase another reading.

Composition reads exact text matches without scrolling, including global count, count under the thread list, displayed count and repository row's `IndexForKey` membership when available. Reads are one snapshot, not a new polling wait. Preserve partial readings if a semantics query fails. Composition synchronization uses the existing bounded Compose test rule; no new device worker or unowned coroutine.

The wrapper calls the unchanged phone send and original wait once. Failure capture cannot replace the original throwable or make a timeout successful. Local cleanup attempts both fixture releases, preference restoration and peer closure even when one fails; preserve the original scenario failure. Emit only static capture/cleanup error codes, never exception messages.

## State and concurrency model

The probe belongs to one synchronous instrumentation scenario; no background job or production state. Repository reads use cancellable `runBlocking` with `withTimeoutOrNull(1000)` per flow on the instrumentation thread. Snapshots can differ in time: record phone wall/monotonic sampling bounds and host timestamps independently. Unknown readings never become false facts.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Pre-send stale answer or late child answer | `baselineAndChildRepliesDoNotCount` |
| Main turn completes with different text | `completedDifferentAnswerIsNotExact` |
| Exact peer answer absent from repository | `peerAndRepositoryRemainIndependent` |
| Repository exact answer uncomposed or offscreen | `compositionAbsenceAndOffscreenStayDistinct` |
| Missing or malformed readings | `unavailableReadingsStayUnknown` |
| Capture and cleanup fail after timeout | `failureSurvivesCaptureAndCleanupErrors` |
| Successful wait then capture failure | `successfulWaitIsNotChangedByDiagnostics` |

No lifecycle state survives a scenario, reconnect, process death or identifier reuse. Baselines exclude earlier turn and row identities; the peer link reading exposes reconnect limitations without repairing them.

## Error handling

Each diagnostic read is isolated and unavailable observations remain unknown. Retain the exact original throwable. On successful scenarios cleanup failures still fail the scenario. Raw peer frames and repository content are compared only in memory and never serialized.

## Testing strategy

Write failing JVM tests first for the pure parser and wrapper; run `LaterReplyDiagnosisTest` and existing `PingReplyDiagnosisTest`. The device-only reason is real authenticated sockets, real Claude/daemon execution, IME send and lazy-list semantics on an actual device. Run one instrumented focused real-Claude background Agent method through the credential-owning launcher. Retain counted XML, content-free phone observations and private host history/transcript; correlate identities across clock domains. Existing rung-4 placement twin remains unchanged; diagnostics do not claim historical reproduction or causal repair. Run scoped lint/build/Android-test compilation and forced Spotless, then final `assembleDebug` and `pre-verify.py --gradle` after main merge. Full live gate is dispatcher-owned after a demonstrated repair and verifier pass.

## Open Questions

- Does the instrumented run demonstrate a localized harness cause? If not, retain and push diagnostics/evidence, then return the unresolved boundary for refinement; do not claim reliability repaired.

## Security review

**Verdict:** PASS

- Trust boundaries: peer payloads and daemon-authored text are untrusted; compare in memory only. Hash all identifiers; normalize timestamps by parsing them. Do not log raw payload fields or peer link error strings.
- Tokens/credentials: existing credential-owning launcher and peer retain ownership. No new generation, persistence or credential access.
- Files/storage: raw captures stay in private temporary/build directories, outside committed and APK assets. Published evidence contains counts, hashes, timestamps and static flags only; no daemon-authored content.
- Android attack surface: test sources only, no component, intent, provider or manifest changes.
- Cryptography: reuse SHA-256 for diagnostic identity labels; no Noise or key/nonce changes.
- Network/I/O: one-second repository reads; no history request, retries or new network endpoint. Existing frame limits remain. Relay delay/loss is recorded as unknown causality.
- Errors/logs: SHOULD FIX implemented by static unknown/capture-error labels; never serialize exception messages, arbitrary link descriptions, authored text or keys.
- Concurrency: synchronous scenario owner, bounded cancellable flow reads, no worker outliving cleanup. No production locks/state changes.
- Threat model: relay dropping/delaying frames remains possible; diagnostics cannot assert delivery from completion counts. Rooted-device token theft and accessibility/screenshot leakage retain existing storage/UI ownership; no new secret sink. Hostile/malformed frames yield unknown facts rather than logged content.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-12

## Revisions

- 2026-10-12: preserve the original send-to-wait deadline by subtracting after-send capture elapsed time from the existing wait budget. Record send-return separately; sender acknowledgement is unknown because the repository does not expose it. Baseline turn hashes and baseline exact-row hashes make exclusion reviewable. Malformed/conflicting/missing delta sequences cannot establish either an exact or completed-different answer.
