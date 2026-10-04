# #1693 — Preserve peer binding before the background completion proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `runningToolPeer`, `peerStep`, and `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`; initial opening precedes the held turn and push proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open` and `dialLink`; settlement requires an authenticated handshake and correlated `list_conversations` answer.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: #1698 retains static identity by exact host/token, returning disposable copies for fresh Noise sessions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start` and `describe`; unaccepted dials never publish a settled link.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`, `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`, and `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: identity, responder/cipher conventions, and actual peer/graph cleanup coverage.
- `scripts/e2e-emulator.sh`: independent host-A peer token retains remote-permission authority; phone pairing is separate.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/development-verification-test-scheduling.md`, `docs/knowledge/features/push-messaging-service.md`, and `docs/e2e-interactive-stream.md`: counted results, identity custody, and real FCM proof.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Handshake and Security model are the wire/authentication source of truth.

## Change

All four retained branch/base stderr reports fail this method at initial peer `open`, before its notification steps. Their daemon logs contain respectively 102/84 (#1631 branch/base) and 102/90 (#1637 branch/base) `static_key_mismatch` / `bound_to_other_key` rejections. Sequential peers reused a token with newly generated static keys; the daemon correctly refused its already-bound token. Merged #1698 repairs that local harness defect. Its full live report at `52b646ac8f2c8a557b85a34f98f354eaf0c42c08` explicitly passes this method (53 executed, 1 unrelated failure, 0 skipped), with zero binding rejections. This is repaired-baseline evidence, not acceptance of this candidate.

Add a JVM regression in `PeerSettlingIdentityTest`: a token-binding test responder authenticates two sequential, independently constructed peer stores/factories, closes each fresh Noise session, and answers an encrypted correlated `list_conversations` probe on both. A fresh-static-key negative control must be rejected before its probe. This extends identity-only and hello-only coverage into the settlement contract without duplicating #1696's handshake-only test. Add a prior `runningToolPeer` open/close to the named live method before its scenario peer opens, making the regression independent of suite ordering. Keep original deadlines, foreground permission hold, background host-link close, privileged independent-peer allow, real FCM/replay wake, exactly-one completion alert, originating-thread content-intent assertion, and cleanup. No production or wire changes.

Shared-file overlaps #1631, #1642, #1686, #1694, #1695 and #1696 edit other scenario blocks (and #1694 shared diagnostics); none restructures this method. Keep this edit local. Written work is below 350 lines, no new exported types, no signature migrations, three criteria and one test-only authentication rejection.

## Testing strategy

Write the settlement regression before the scenario change. Observe it fail when a temporary per-instance identity cache reproduces the pre-#1698 lifecycle, then restore the merged repair and observe both positive and negative controls pass. Do not commit that negative-control mutation. Run `PeerSettlingIdentityTest`, `PeerDeviceStaticKeyStoreTest`, `NoiseSessionFactoryTest`, `NoiseIkSessionTest`, `PeerWaitTest`, and `RedialingLinkTest`. Run existing `PeerIdentityLifecycleTest` followed by `RepositoryBindingInstrumentedTest` on the managed device; actual Android application lifecycle/graph owners require instrumentation. Run one scripted reconnect scenario (zero Claude calls), lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.

Dispatcher handoff: full `python3 scripts/android-test-gate.py live` on the pushed candidate before merge, with exact tested commit, executed/failed/skipped counts, and explicit passing testcase for `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`. Use `Live tests: all` and preserve `needs-real-claude`. Loopback does not prove FCM; no candidate live pass is claimed by the builder.

## Security review

**Verdict:** PASS

- [Trust boundaries] The test responder models token-to-authenticated-static-key binding; changing static key must fail before a probe. No production decoder or trust boundary changes.
- [Tokens and credentials] Synthetic regression tokens only. The live prior peer uses the independent host-A peer token and retained process identity, never phone pairing/storage. No credentials or key arrays enter assertions, errors or logs.
- [Files and storage] No new runtime files, paths, persistence or backup exposure; test identities remain in memory.
- [Android attack surface] No component/intent changes. The existing notification content intent and payload-free FCM wake remain under their original assertions.
- [Cryptography] Vendored Noise IK and fresh responder/client sessions per dial; only static identity is retained. Destroy test ciphers/handshakes and wipe test private buffers in cleanup; no nonce-state sharing.
- [Network and I/O] Preserve capped reconnect backoff and all deadlines. The extra live peer must settle via its answered probe, then close in `use` cleanup before the scenario peer opens.
- [Errors and logs] Boolean identity comparisons and static rejection categories only. Do not print decrypted hello/probe bytes, tokens, keys or daemon-authored content.
- [Concurrency] No new production jobs. Prior peer closes even on opening failure; existing watcher cancellation and held-turn/alert/activity cleanup remain in `finally`.
- [Threat model] Malicious relay delay remains deadline-bounded, hostile frames retain existing decoding, and authentication still rejects a substituted identity. Disk token theft and UI leakage gain no new surface in this test-only patch; production Keystore and rendering remain unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
