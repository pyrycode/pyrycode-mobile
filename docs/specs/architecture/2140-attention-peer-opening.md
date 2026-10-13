# #2140 — Diagnose and repair attention peer opening

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn`, `answerHostPeer`, `pairAnswerHost` and `peerStep` locate the failure before the first attention assertion; retain the complete read/permission proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `dialLink`, `attempt`, `record` and `close` own relay establishment, handshake, correlated settlement and teardown.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start`, `dialUntilSettled`, `describe` and `close` own attempts and existing backoff.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: the process preserves token-bound static identity across sequential peers.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt`: `connect`, `Listener` and `terminate` expose relay establishment and classified closure.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt`: `drive`, `state` and `teardown` distinguish handshake completion from open-state failure.
- `app/src/test/java/de/pyryco/mobile/e2e/OffscreenPeerReadinessTest.kt`: existing sequential-identity proof does not exercise live transport establishment.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: preserve counted private evidence and distinguish historical causation from a controlled defect; instrumentation retains its application graph across methods.
- `docs/e2e-interactive-stream.md`: rung-3 attention/read contracts and existing rung-4 counterpart.

## Context

The original #1896 gate used Mobile `0c9e9a6f49f3282cb1680d598c10af3bf02702c0` and daemon `9c9cd5a25c337666bdbed47c29202e695a172733`: 65 executed, 62 passed, three failures; the unchanged subset passed 3/3. Attention failed in peer opening before attention assertions. The original daemon revision contains the shipped #3214 capacity repair. Its answer-daemon log records phone admission and two chat creations, but no accepted peer handshake during the failed opening interval. Original per-test phone evidence is unavailable. These facts do not establish a root cause.

## Design

First reproduce the named method through the existing isolated live launcher. If the captured transport/pump evidence cannot distinguish the failed stage, add local content-free attempt observations in `SecondClientPeer.dialLink` for relay establishment, Noise handshake and correlated settlement. Do not publish peer identities, tokens, frame bodies, arbitrary exception messages or prompts. Diagnose before choosing a repair. Record the supported mechanism and resulting bounded repair in Revisions before implementation departs from this investigation design. Diagnostics and an unchanged passing rerun alone cannot complete the ticket.

No product visuals or public API migration are planned. A test/harness repair stays local to the evidenced mechanism. A required sibling-repository repair is linked to its owner instead of patched here; a shipped repair is not duplicated. Overlapping branches #2009, #2032, #2090 and #2138 add later read diagnostics or other scenario helpers; retain their work and avoid restructuring shared blocks. Other in-flight scenario edits in `InteractiveStreamE2ETest` are additive and do not establish dependencies.

## State and concurrency model

Preserve the peer IO scope, caller-owned opening deadline, existing per-attempt bound and redial backoff. Observation must not consume the pump's single-consumer inbound stream or the transport's events a second time. Caller cancellation keeps its original cause and closes the failed attempt synchronously. Scenario finally still closes the peer and removes the exact paired answer host.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| A prior peer closes and a new peer uses the same pairing | Existing `sequentialClosedPeersCompleteBoundHandshakeAndEncryptedReadinessProbe`; focused same-process predecessor comparison if evidence indicates ordering. |
| Relay establishment, handshake or settling reply stalls | Controlled regression for the diagnosed stage, with original deadline and failed-attempt cleanup. |
| Caller cancels while opening | Controlled regression must preserve cancellation and leave no active attempt after cleanup if that mechanism changes. |
| A failed attempt is replaced | Preserve existing retry policy; any repair to attempt ownership needs deterministic lifecycle coverage. |
| Phone opens A; peer reads B while the phone stays on the list | Existing complete attention-dot live method, unchanged assertions and history-backed marks. |

## Error handling

Keep the existing timeout assertion and original cause. Report only static phase/outcome labels and safe counts/classifications. A failure in diagnostics must not replace the original operation failure. Classify transport establishment separately from handshake and settlement; a handshake alone is not readiness.

## Testing strategy

Run the named live method with fresh counted XML and correlated daemon/phone evidence. Write a deterministic failing regression for the established defect before applying the repair, using injected/fake operations where possible. Run affected existing peer lifecycle/readiness unit tests, lint, debug assembly, instrumentation compilation and forced Spotless. The existing live scenario remains device-only because it requires real asynchronous relay/daemon/Claude work. Preserve the scripted ping attention/read counterpart; run it if the shared peer path changes. Final post-merge checks include `scripts/pre-verify.py`. The dispatcher owns fresh full-live acceptance after verifier, explicitly including this method; focused evidence is not full-suite acceptance.

## Open Questions

- Which establishment stage fails, and is there a controlled source defect rather than a transient external failure? Resolve with correlated evidence and a red/green regression before claiming completion.
- Does same-process predecessor execution affect admission? Compare when fresh observations point to ordering.

## Documentation handoff

Pending for documentation: update `docs/e2e-interactive-stream.md`, “Phone read-mark proof (#1912)” and “Peer read clears phone attention (#1883)”, with the established peer-opening cause/repair, attribution limits, controlled regression and final counted live evidence. Documentation consumes supplied evidence; it does not obtain live proof.
