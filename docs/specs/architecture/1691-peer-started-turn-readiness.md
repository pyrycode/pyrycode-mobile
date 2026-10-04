# Peer-started turn readiness regression (#1691)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_peerStartedTurn_continuesOnPhone` retains acknowledged ping, `turn_end`, and exactly-once display checks; mirror the prior-peer setup in `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, `request` and `close` preserve bounded handshake/probe settlement, no message replay, and cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start`, `dialUntilSettled` and `describe` explain why the old timeout did not identify the failing stage.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: `retainedKey` implements merged #1698's exact host/token identity continuity and defensive copies.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: `sequentialFactoriesPresentSameBoundIdentityInFreshNoiseHandshakes` checks the authenticated wire identity with fresh responders, beyond comparing stored arrays.
- `scripts/e2e-emulator.sh`: host-A peer pairing supplies one token to successive scenarios; no harness change needed.
- `docs/knowledge/features/development-verification-test-scheduling.md`: a standalone peer scenario needs a predecessor to reproduce the suite's bound-token lifecycle.
- `docs/knowledge/features/development-verification-emulator-evidence.md` and `docs/e2e-interactive-stream.md`: counted managed API 33 evidence and dispatcher-owned rung-3 execution.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model define binding and Noise authentication; preserve that contract.

## Change

The original failure is handshake authentication, before correlated readiness or sending a ping. The retained #1637 daemon log at `pyry-e2e.Cf5gl9/daemon.log` contains 102 occurrences each of `v2.handshake.reject.static_key_mismatch`, `bound_to_other_key`, and close code `4401`. Sequential peers generated different static keys for one reused token. PR #1701 (#1698), now merged, repaired that lifecycle; reuse it without another cache or daemon/relay change. All six original branch/base/rerun reports fail this method; #1698's subsequent full report has 53 executed, 1 unrelated failure, 0 skipped, and this method passing. That historical pass does not replace fresh acceptance for this branch.

Extract the scenario's pairing into a local value. Before opening the sending peer, open and close a separate peer with that same pairing, through existing `peerStep` and bounded `open`. This binds the token even when the scenario runs alone, so per-instance key rotation prevents the sending peer from settling. Preserve its independent identity, all operator-facing assertions, the one real-Claude turn, correlated readiness, and no-replay semantics. Both peers have existing guaranteed cleanup; no API, timeout, production, wire, or UI changes.

Overlap: #1631, #1642, #1686, #1693, #1694 and #1695 edit other methods or shared diagnostics in `InteractiveStreamE2ETest`; inspected diffs show no dependency. Keep edits local.

Sizing: under 120 written lines, zero exported types, zero signature migrations, four acceptance criteria, no new reject branches. One deliverable: regression protection of peer-started-turn readiness using the landed repair.

## Testing strategy

First run the existing authenticated-identity JVM regression red with the former per-instance key lifecycle temporarily reinstated; restore the landed repair before changing the scenario and run peer identity, redial, wait and session-factory tests green. Keep no temporary mutation in the commit. The added real prior-peer setup is the scenario regression; fresh live execution remains pending.

Run focused JVM tests with executed counts, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. Run one scripted reconnect scenario against isolated test binaries with zero Claude turns. The existing live scenario remains device-only because it exercises real relay/Noise IO and a real Claude producer. Do not execute the live suite in the builder.

The dispatcher must run fresh full `python3 scripts/android-test-gate.py live` on the repaired revision before documentation/merge, preserving revision, executed/failed/skipped counts and explicit passing evidence for `interactiveTurn_peerStartedTurn_continuesOnPhone`. Request `all` under the PR's Live tests section. Do not claim pending live checks passed.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, What rung 3 is made of / peer-started-turn scenario: record explicit prior-peer binding, authenticated static-key mismatch cause and reuse of #1698.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status: preserve fresh dispatcher full-live revision and counts, the named scenario's passing result and the historical diagnostic comparison.

## Security review

**Verdict:** PASS

- [Trust boundaries] Both opens retain `dialLink`'s authenticated Noise handshake and correlated readiness reply. No new trust is placed in daemon frames; token binding remains enforced.
- [Tokens, secrets and credentials] The existing exact host/token cache stays in test-process memory, independent of phone credentials. Reuse only the static identity, with defensive key copies; do not log pairing records, tokens, keys or payloads.
- [Files and storage] No disk credentials, paths, backups or app-storage changes. Publish only static diagnostic codes and counts from retained private reports.
- [Android attack surface] No components, intents, permissions, providers or rendering changes; only existing test peers are instantiated.
- [Cryptography] Reuse `NoiseSessionFactory` and the vendored IK suite. Every dial has fresh ephemeral and cipher state; no nonce sharing or authentication bypass.
- [Network and I/O] Existing transport defaults, bounded waits, reconnect backoff and probe remain. The prior peer sends no message and adds no Claude turn; sending peer retains no replay after lost acknowledgement.
- [Errors, logs and telemetry] `peerStep` names prior-open versus sender-open without secrets. No raw daemon logs, transcripts or new telemetry are published.
- [Concurrency] Prior peer closes through `use` before the sender opens; sender closes in existing `finally`. Retained-key mutex and existing IO scope cancellation remain unchanged.
- [Threat model] Malicious-relay dropping/delay stays bounded and Noise still prevents impersonation. Disk token theft gains no storage target; hostile frames and UI leakage retain existing parsing, text rendering and phone Keystore protections. No deferred protocol threat is expanded by this test-only change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
