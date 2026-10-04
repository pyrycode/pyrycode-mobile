# #1686 — Preserve the live peer's paired identity

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, `answerHostPeer`, `pairAnswerHost`, `answerChat`, and `peerStep` establish the scenario and its waits.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, and `ThrowawayDeviceKeyStore` reveal that each scenario generates a different key for the same harness token.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start` keeps redialing until a handshake and probe settle.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `callOnLive` already protects `answerChat` from repository replacement; its API does not need changing.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt`: `create` wipes the private-key copy supplied by the store.
- `scripts/e2e-emulator.sh`: `mint_answer_pairing` creates one answer-peer token for the entire suite, with remote-answer permission and an isolated daemon HOME.
- `docs/knowledge/features/noise-ik-session.md`: static identity persists across handshakes; callers must receive independent private-key copies.
- `docs/knowledge/features/development-verification-test-scheduling.md`: the routine UI gate excludes e2e; compilation is not live proof.
- `docs/e2e-interactive-stream.md`: permission-answer and question-answer scenarios and the second-client harness contract.
- Sibling `pyrycode/docs/protocol-mobile.md`: “Device-static key” documents #2734's token-to-static-key binding and rejects a changed key with `auth.invalid_token` / `4401`.
- Sibling `pyrycode/internal/relay/v2session_handshake.go`: `handleNoiseInit` calls `Validate` / `BindStaticKey`; the recorded daemon revision `dcaecd416535250b48a444eda887edc48f76913a` contains that enforcement.

## Context

The stalled operation is `SecondClientPeer.open`, not a permission answer. A suite mints one answer-peer pairing but constructs a new `SecondClientPeer` for each scenario. Its instance-local `ThrowawayDeviceKeyStore` generates a fresh Noise static key every time. Since daemon #2734, the first accepted peer binds that pairing to its key; subsequent peers are permanently rejected while `RedialingLink.start` repeatedly dials until the outer 30-second deadline.

The branch and base stderr reports both record the unnamed 30-second timeout. Their retained answer-daemon logs supply the missing discriminator: `v2.handshake.reject.static_key_mismatch`, device names ending `answer-peer`, reason `bound_to_other_key`. The branch log at `/var/folders/k0/gc07w9ws319b07n0plnw6y8r0000gn/T/pyry-e2e.AOLbIc/daemon-answer.log` contains 18 such rejects; the base log at `pyry-e2e.JKsJv3/daemon-answer.log` contains 12. The #1637 branch's `pyry-e2e.Cf5gl9/daemon-answer.log` contains 18. These are content-free extracted event counts, not copied private logs. The first branch rejection is at `2026-10-03T23:39:28.171+03:00`, base at `2026-10-04T00:22:55.984+03:00`. Neither answer log reports stale redemption. The same shared cause explains later peers in related tickets #1682 and #1702; neither has an established independent repair.

No production correction or decision record is needed. This restores the test client's identity contract without altering daemon admission or the operator's credentials.

## Design

Extract the existing in-memory Noise key generation into `PeerDeviceKeyStore` under `app/src/sharedTest/java/de/pyryco/mobile/e2e/`. `SecondClientPeer` constructs this store from its `PairedServer`, retaining its existing constructor and all consumers.

The store's process-local registry maps a server id and SHA-256 token fingerprint to one keypair. The same pairing across scenario instances keeps the same key. Different tokens on one host, and different hosts, retain separate identities. Relay URL changes do not replace identity. Neither tokens nor keys are written to disk or included in diagnostics. `loadOrCreate` and `publicKey` return fresh byte-array copies and reject a server id other than their pairing's host.

Use the existing vendored Noise `25519` generator. Generate and publish under one lock so racing instances cannot bind competing keys. The instrumentation process owns the registry lifetime; fresh harness runs mint fresh tokens and process teardown releases the retained keys. `peer.close` still closes sockets, jobs and session keys, but must not destroy the run's paired identity.

The named permission scenario uses the existing `peerStep` wrapper for its open wait so a future timeout names the stalled peer operation. All prompt, grant, arm, tool execution, output and peer-dismissal assertions remain identical. `answerChat` has 22 callers; no shared-helper signature or consumer changes are proposed.

Overlaps: #1631 and #1642 add unrelated scenario steps in `InteractiveStreamE2ETest`; this local open-wait edit does not depend on either branch.

## State and concurrency model

Only the test process owns new state: a synchronized key registry, with no coroutines, flows or I/O. Existing peer scopes and redial cancellation stay unchanged. Factory wiping affects returned copies only. There is no ViewModel or UI state change.

## Error handling

Existing crypto generator failures propagate as test setup failures. Wrong-host reads fail before returning any key material. No retries or auth bypasses are added. `peerStep` retains the existing timeout and reports only a static step label and content-free link state.

## Testing strategy

Write JVM regressions first using the same store implementation consumed by the peer. Recreating a peer store with the same pairing must preserve the public and private key after the first reader's returned arrays are wiped; changed token and changed host must yield independent keys; a wrong-host request must fail. A concurrent first access must return one identity. The continuity regression fails with instance-local storage before the shared registry repair.

Run `PeerDeviceKeyStoreTest`, existing `LiveConnectionReadsTest`, `RedialingLinkTest`, `PeerWaitTest`, and `NoiseSessionFactoryTest`, plus lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck. Run one relevant scripted stream scenario on the managed device to check that the e2e harness still executes; it does not prove the permission scenario. The existing rung-3 scenario stays device-only because it needs a real relay, host daemon and Claude.

The dispatcher must run the fresh full curated live suite before merge, explicitly report executed/failed/skipped counts, and confirm `InteractiveStreamE2ETest#interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` executed and passed. This builder never treats pending live execution as a pass.

## Open Questions

None. Token-bound identity persistence is specified by the sibling protocol and confirmed by both retained daemon logs.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “What rung 3 is made of” / second-client peer and permission-answer paragraphs, to describe a process-local key shared by all peers using one harness pairing, rather than a new identity per scenario. Record the #2734 binding and this suite-order regression in the verification topic.

## Security review

**Verdict:** PASS

- [Trust boundaries] The repair changes only test identity custody. `NoiseSessionFactory` and `NoiseSessionPump` remain the authenticated frame boundary; daemon `Validate` / `BindStaticKey` are unchanged. No daemon text becomes a registry key.
- [Tokens] The registry indexes by server id and SHA-256 fingerprint, never the raw token. Keys stay in test-process memory; no credentials are copied from the operator. Changed pairing tokens select new identities.
- [Files and storage] No new filesystem writes or persisted secrets. The phone's Keystore and pairing stores remain separate from the peer.
- [Android attack surface] No component, intent, permission, provider or UI changes. Existing isolated harness privileges stay unchanged.
- [Cryptography] Reuse vendored Noise X25519 generation. Preserve only static identity; every session still creates fresh Noise ephemerals and cipher counters. Returned arrays must be copies so factory wiping cannot corrupt the registry.
- [Network and I/O] No wire, timeout, TLS, frame-bound or backoff changes. Rejects remain rejects; the harness supplies the already-bound identity instead of weakening admission.
- [Errors and logs] No new logging of registry identifiers, fingerprints, pairing records or key bytes. The open timeout uses the existing static `peerStep` diagnostic.
- [Concurrency] MUST FIX addressed in the design: key creation and publication share a lock, preventing two first readers from producing conflicting identities for one token. Test simultaneous access and destructive mutation of returned copies.
- [Threat model] A malicious relay retains only denial-of-service capability; authentication and bounds are unchanged. Rooted-device disk theft is unaffected because the peer writes no disk secrets. Hostile frame rendering and phone UI leakage remain outside this test-only change's surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
