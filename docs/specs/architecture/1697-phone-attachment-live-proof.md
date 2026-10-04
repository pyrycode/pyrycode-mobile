# #1697 — Phone-to-peer attachment live proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, `runningToolPeer` and `peerStep`; the unchanged attachment assertions and diagnostic seam.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `history` and `retrieveAttachment`; authenticated readiness and peer-observed bytes.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: `loadOrCreate` and `identityFor`; current live peer identity custody after #1698 and #1686.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceKeyStoreTest.kt`: `recreatedPeerKeepsTheIdentityAlreadyBoundToItsPairing`; destructive reads must not erase the retained identity.
- `docs/knowledge/features/attachment-upload.md` and `attachment-retrieval.md`: the 45000-byte chunk contract and exact-byte retrieval coverage.
- `docs/knowledge/features/development-verification-test-scheduling.md`: a standalone peer pass does not establish sequential token reuse; counted full-suite evidence is required.
- `docs/e2e-interactive-stream.md`: Verification status; repaired live baselines and dispatcher-owned execution.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model; the daemon binds a token to its first accepted static key.

## Change

Record that the shared identity repair restored this scenario without changing its code. This checkout starts at `d54d7d9cad237c68290333f233180fadf635a192`, equal to fetched `origin/main`, and contains #1698's merge `d63e304b8e0151677ae6579c1bd61684c66bb86a` and #1686's subsequent live-peer key-store repair. The named scenario is unchanged from the failing base `acf0f6591cb7d956ae2584da1eda1c5d1ddd0c4b`. No demonstrated residual attachment defect warrants another repair, new test, timeout adjustment or weakened assertion. This ticket writes only this plan and publishes its evidence in the PR. Remote feature branches #1631, #1642, #1683 and #1690–#1695 share the read-only scenario file; there is no implementation overlap or dependency. Forecast: under 100 written lines, no new types, consumer updates or rejection branches, three acceptance criteria and one verification deliverable.

## Baseline and diagnosis

The dispatcher reports under `/Users/juhanailmoniemi/Workspace/Projects/pyrycode-mobile-agents/logs/` establish:

- `2026-10-03T23-52-08-061Z_real-claude-gate_#1698.log`: 53 executed, 52 passed, 1 failed, 0 errors, 0 skipped. The named phone-attachment method appears exactly once and passes. The unrelated question-answer failure passed a focused rerun; that rerun is not a second full-suite pass.
- `2026-10-04T02-43-28-151Z_real-claude-gate_#1692.log`: fresh full live suite, 53 executed, 53 passed, 0 failed/errors, 0 skipped. The named phone-attachment method appears exactly once and passes.
- `2026-10-04T02-57-03-837Z_real-claude-gate_#1686.log`: fresh full live suite, 53 executed, 53 passed, 0 failed/errors, 0 skipped; adjacent stderr records process exit 0. The named phone-attachment method appears exactly once with no failure, error or skip. The dispatcher tested `feature/1686` at `5aa6f23b79`, merged with main at `ad547c6425`, both now included in this checkout. Published gate evidence: https://github.com/pyrycode/pyrycode-mobile/issues/1686#issuecomment-5976044606.

Counted retained daemon diagnostics under `/private/var/folders/k0/gc07w9ws319b07n0plnw6y8r0000gn/T/` agree with the shared token/key-binding cause: `pyry-e2e.AOLbIc/daemon.log` has 102 occurrences each of `v2.handshake.reject.static_key_mismatch` and `bound_to_other_key`; the old base's `pyry-e2e.JKsJv3/daemon.log` has 84 each; repaired #1698's `pyry-e2e.shYptv/daemon.log` has zero each. These establish a shared authentication defect resolved by the prerequisite, rather than an attachment-byte defect. No method-specific stalled operation remains to repair.

## Testing strategy

Parse the retained XML independently and require exactly one passing named testcase, checking suite executed/failed/error/skipped counts and the process result separately. Compare the named scenario with the original failing base to verify unchanged phone picker, PNG and roughly 100 KB document, real turn completion, exactly one user message in X, two distinct attachment IDs, exact SHA-256 digest set and no user message in Y. Keep the document's three-chunk coverage. Run focused peer identity, readiness, redial and wait JVM regressions, lint, debug assembly and forced Spotless checks; no emulator check is needed for a plan-only diff. Existing device-only coverage needs the real picker, relay, daemon and Claude turn; it cannot be replaced by a JVM assertion. The builder does not run real-Claude tests. Hand the existing fresh full-suite proof to the dispatcher and retain `needs-real-claude`; list `all` in the PR so any new live gate remains full-suite. Do not represent a future dispatcher run as already passed.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status and phone-attachment coverage; link this ticket's no-code resolution to the already retained #1686 full-suite report, explicitly naming the phone-attachment passing testcase and 53/0/0 counts. Preserve the distinction between #1698's partial full-suite result plus focused rerun and #1686's entirely passing full suite.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `SecondClientPeer` still uses the production codec and authenticated Noise path. The proof compares bytes retrieved by a second client, rather than trusting the phone's cache or display labels. No boundary changes.
- [Tokens, secrets and credentials] `PeerDeviceKeyStore.identityFor` retains process-local host/token-separated identities and returns copies. This ticket never reads or publishes credential values and adds no persistence; #1698/#1686 already own custody.
- [Files and storage] `insertDownload` creates synthetic public picker fixtures and `deleteFixtures` removes them in guaranteed cleanup. They contain no credentials. Evidence inspection counts static events and XML outcomes without publishing daemon payloads or adding file paths derived from daemon text.
- [Android attack surface] `ActivityIntentStub` continues answering only the test's system picker. No exported component, provider root, intent grant or production resource changes.
- [Cryptography] The vendored Noise implementation, fresh per-dial state and SHA-256 fixture comparisons remain unchanged. No authentication bypass or key/nonce reuse is introduced.
- [Network and I/O] Existing bounded `open`, history and retrieval deadlines remain intact; no timeout is raised to hide authentication failure.
- [Errors, logs and telemetry] Publish static diagnostic event counts, source revisions and test names only. Never publish tokens, keys, transcripts, fixture bytes, decrypted messages or complete logs.
- [Concurrency] The scenario's `finally` still closes the peer and removes picker fixtures. No new coroutine, shared mutable registry or lifecycle owner is added.
- [Threat model] Malicious-relay interception retains Noise confidentiality and bounded waits; token theft defenses remain the existing app Keystore and daemon static-key binding. Hostile daemon frames still pass existing decoding/reassembly and exact-byte assertions. UI screenshot/keyboard exposure gains no new surface because no UI or input handling changes.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
