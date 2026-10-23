# Replay-order release ownership (#2486)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: replay-order scenario, `severLink`, `restoreLink`; the test requires a held thinking state before deliberate offline production.
- `scripts/e2e-emulator.sh`: replay-order selection and second-fragment watcher; any new phone unregister currently releases the reply.
- `scripts/test_e2e_stream_hold.py`: executes the actual shell selection/watcher against controlled logs and release files.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt`: existing fixed `releaseStream` control supports explicit offline release.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: passive host route and authenticated connection ownership.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QueueRecoveryAgentEvidence.kt`: completion, order, segmentation and echo checks remain intact.
- `app/src/test/java/de/pyryco/mobile/e2e/QueueRecoveryAgentEvidenceTest.kt`: source identity probes alongside ordered replay and echo controls.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `certifyQuery` and document identity show why valid publications can replace a snapshot without retiring its source.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeScenarioScreenTest.kt`: real rendered daemon-order assertion and its negative control.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt`: split/unsplit rendering and missing, duplicate, reordered and late-echo controls.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: existing Noise trust boundaries remain unchanged by this test-only release repair.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md`, replay-order: empty aggregate failures cannot identify the failed predicate; segmented replies are valid.

## Context

One behaviour: the replay-order scenario must release its reply only during its deliberate outage. Historical Linux #2483 and macOS #2344 daemon logs both show `v2.push.producer_gap.teardown` before reply completion and a fresh handshake shortly afterwards. The release watcher accepts that automatic disconnect, so the completed reply can replace thinking before the test observes it. Original instrumentation details are unavailable; preserve that limitation and capture a focused run before claiming an exact historical predicate.

## Design

Use the existing phone-checkpoint release mechanism for replay-order. After the thinking assertion and successful `severLink`, invoke `DaemonFaultControl.releaseStream`, then retain the original offline window and restore. The fixed host control writes only the harness-owned release file. Automatic reconnects cannot release the fixture. Add static phase logs around initial readiness, thinking, sever, release, restore, rendered text and final evidence.

Keep exact rendered `alpha bravo charlie`, completion, item identity/order and single prompt echo assertions. Resolve the tested server's coordinator and negotiated route, retain its conversation identity before sending, and require final evidence to belong to the current supported connection and current snapshot. Do not sort retained evidence or retry the scenario. No production types, signatures, transport contracts or timeout values change.

Shared-file overlaps include #1766, #2052, #2088, #2090, #2091, #2097, #2104, #2114, #2121, #2175, #2442 and #2462. Changes stay local to replay-order and its regression; inspected active #2104/#2462 work changes the rule/ping paths, not this release boundary.

Forecast: roughly 350 written lines including this plan, diagnostics, shell regression and invariant probes; 0 exported types, 0 signature migrations, 4 acceptance criteria, no new production reject branches. This is one repair with its regression, below the 1600-line ceiling.

## State and concurrency model

The existing owned shell watcher waits for the release file; the existing fault server writes it. The instrumentation thread runs the scenario sequentially. Existing supervisor ownership, cancellation, watcher teardown and control timeouts stay unchanged. Passive evidence observation never opens a new query or connection.

## State transitions and identity reuse

| Event | Check |
| --- | --- |
| Automatic disconnect before thinking is observed | Controlled shell regression emits producer-gap unregister and verifies no release. |
| Repeated automatic reconnect/unregister and enqueue | Same regression verifies the held fragment stays closed. |
| Deliberate sever then explicit release | Regression verifies explicit release completes watcher; native replay-order checks thinking before sever and exact reply afterwards. |
| Reconnect replaces authenticated source under same host/conversation | Final proof requires current route connection/snapshot; focused ownership probes reject retired connection, other host/conversation and changed document. |
| Same document advances while Compose synchronizes rendered text | `sameDocumentPublicationDuringRenderDoesNotRetireReplayOwnership` and `replayRefreshAcceptsSameDocumentAdvanceDuringRender` accept ownership and validate refreshed facts. |
| Fresh facts invalidate otherwise correct captured text/echo proof | `replayRefreshRejectsNewDuplicateEchoDespiteCorrectOldFactsAndRender` fails on a duplicate current echo despite the valid old facts and rendered reply. |
| Duplicate, missing, reordered or segmented replay; duplicate/late echo | Existing `QueueRecoveryAgentEvidenceTest`, `AuthoritativeScenarioScreenTest` and `ScriptedThreadRenderTest` retain their negative and success probes. |

## Error handling

Existing original deadlines and exceptions remain. Static phase start/complete logs identify the pending predicate without dumping text, tokens, identifiers or exception bodies. Control failure remains an immediate failure. Cleanup stays with the existing gate and harness owners.

## Testing strategy

Write and run the real shell watcher regression before the repair; it must fail on automatic unregister releasing the reply. Run it green after selection changes. Add pure ownership probes if a helper is necessary for the final current-source check. Run existing replay helper and native-render probes, lint, assembly, instrumentation compilation and formatting. Run focused `scripted replay-order` on Linux, inspecting counted XML, raw instrumentation, phone logcat and isolated daemon/relay/control logs. The native test requires real socket recovery and background dispatchers; Robolectric cannot replace that evidence. Dispatcher owns full scripted-all acceptance. No real Claude or second-OS run.

## Open Questions

- Which predicate failed in fresh focused execution? Record evidence and any design adjustment in Revisions; original empty aggregate failures alone cannot answer it.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, replay-order (#477, Layer 2d): replace disconnect-log release description with explicit phone release after deliberate sever; retain segmented replay and original timing contracts.

## Security review

**Verdict:** PASS

- Trust boundaries: `DaemonFaultControl.releaseStream` uses the existing fixed test-only action and harness-owned file; accepts no path or daemon text. Final evidence must match the tested host/conversation and current authenticated source.
- Tokens: no credentials added or copied; pairing output and private HOME stay out of published artifacts.
- Files/storage: release path remains fixed by the isolated harness. Raw evidence stays private on the agents volume; publish only checked summaries/hashes.
- Android attack surface: no manifest, component, deep link or provider changes.
- Cryptography: real Noise transport remains untouched; no changes to keys, nonces or algorithms.
- Network/I/O: existing bounded control request and supervisor backoff remain; no timeout increase or new endpoint.
- Logs: static phase names only; never message bodies, tokens, keys, transcripts or headers.
- Concurrency: explicit action follows completed close and null repository; no additional job or lock; existing cleanup owns shell children. After render synchronization, final proof refreshes current facts on the supported authenticated connection and matches host, conversation, epoch, generation and document incarnation. Same-document publication is allowed; a retired source or replaced document cannot certify success. Fresh facts must independently pass all replay checks.
- Threat model: dropped/reordered traffic must still fail ordered evidence; no plaintext disclosure to relay. Rooted-disk theft and UI screenshot/keyboard exposure are outside this test-only repair, with existing production storage/UI boundaries unchanged.

**Reviewer:** builder self-review. **Date:** 2026-10-23.

## Revisions

- 2026-10-23 rework, finding 1: snapshot reference identity across `assertOrderedReplay` conflates valid publication with source retirement. `isCurrentReplay` will compare authenticated connection, host, conversation, epoch and generation, rejecting null/unadmitted/reset/repair state. The authoritative assertion will synchronize rendered text first, then invoke a supplied refresh callback and run unchanged completion/order/identity/echo checks on freshly projected current facts. No Compose synchronization follows that capture. Unit probes cover same-document advancement and document replacement; screen probes advance the snapshot during rendering and reject invalid fresh facts despite correct old evidence and rendered text. Rework stays within the same replay ownership invariant: approximately 180 additional written lines, no production types or signature migrations, eight existing helper consumers retain default behavior. Active overlaps #1766, #2091, #2104, #2175 and #2462 remain local to other scenarios/rule setup.
- 2026-10-23 rework, finding 2: preserve the configured 24-test gate failure (23 passed, queued-echo failed), plus both unsupported exact-selection attempts (zero executed). Run the supported focused `scripted stream` scenario. A focused pass cannot establish inheritance or sequence/load behavior; request dispatcher investigation with the original bundle and exact pushed repaired candidate. Complete configured scripted-all acceptance remains pending dispatcher execution, never waived.
- 2026-10-23 rework security review: PASS after adversarial review of the revised ownership boundary. MUST FIX freshness is addressed by reading current facts after render synchronization; matching epoch/generation alone cannot certify stale facts. Retired connections and colliding host/conversation identities remain rejected, and invalid fresh facts retain the replay failure. No changes to credentials, storage, Android surface, crypto, transport, timeouts or cleanup; static content-free phase logs and private evidence custody remain unchanged. Relay drop/reorder threats still fail exact ordered replay; disk theft and UI leakage remain outside this test-only change.

- 2026-10-23: causal investigation identifies the open-turn observation as the historical failed phase by inference. Linux #2483 logs send at 01:36:26.630Z, producer-gap teardown at 01:36:27.682Z, reconnect at 01:36:29.336Z and final close at 01:37:56.827Z. There is no deliberate five-second outage after that automatic reconnect. macOS #2344 shows the same sequence and approximately 90-second send-to-teardown interval. Original instrumentation stacks and phone logs were not retained in those bundles; the exact exception cannot be recovered. The controlled real-watcher regression directly fails because automatic unregister releases the fixture, then passes after explicit release replaces that fence.
- 2026-10-23: add `ThreadEvidence.isCurrentReplay` and a focused identity probe to enforce the ticket's current-source invariant. Keep completion/order/echo checks separate and unchanged. It rejects retired/null connections, colliding host/conversation identifiers, replaced snapshots/generations and reset state.
- 2026-10-23: the initial native method executed/passed 1/1, but its harness exited 2 because the builder edited the running shell script before it finished. Preserve this unsuccessful command separately; it is not repaired acceptance evidence. Subsequent validation runs use an unchanged tree throughout each command.
- 2026-10-23: resolve the open question with the historical timing inference above and controlled watcher failure. The repaired Linux native run on `d16145cdcca126f90fe048b735972daa1bd72117` executed/passed 1 with no failures/errors/skips. Phone phases prove thinking, close, successful offline release, the unchanged five-second window, restore, rendered text and current-source/order/echo completion. Three focused JVM classes total 29 executed/passed; the harness tests total 10 passed. Private raw evidence and binary/APK hashes are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2486/builder-release-20261023/`. Full scripted-all acceptance stays with the dispatcher.
