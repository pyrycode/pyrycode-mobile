# Interrupted retrieval peer regression (#1690)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, `runningToolPeer`, `peerStep`, `cutLinkOn` and `pullForOlderHistory` define the independent upload, cache-cleared replay, request-triggered cut and Retry proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open` requires a settled handshake and readiness probe; `close` cancels the peer scope.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: merged #1698 retains exact host/token identity while returning defensive copies.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: `sequentialFactoriesPresentSameBoundIdentityInFreshNoiseHandshakes` checks the authenticated identity through the vendored Noise responder.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: graph rebuild and peer close preserve identity without contaminating repository mode.
- `scripts/e2e-emulator.sh` and `scripts/android-test-gate.py`: peer tokens are shared across scenarios; focused scripted reconnect uses isolated daemon/relay binaries.
- `docs/knowledge/features/attachment-retrieval.md`: owning-host retrieval and retryable connection failure remain unchanged.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: executed XML counts distinguish evidence from skipped live checks.
- `docs/e2e-interactive-stream.md`: interrupted-transfer contract and previous full-suite evidence.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: static-key binding and Security model are authoritative.

## Context

All six retained #1631/#1637 branch/base/rerun stderr reports show an unlabelled 30000 ms coroutine timeout for this method. Their retained daemon logs contain respectively 102/84/90 and 102/90/93 `v2.handshake.reject.static_key_mismatch` events with `bound_to_other_key`. Those logs prove an authentication defect across the shared peer harness, but do not independently localize this method's historical timeout or prove Retry was reached.

Reuse #1698's merged repair (PR #1701), also shared with #1691: successive peer instances must retain the static key bound to their reused pairing token. Differential live evidence supports this cause: the target testcase passed in both full suites after the repair, `2026-10-03T23-52-08-061Z_real-claude-gate_#1698.log` and `2026-10-04T00-46-36-696Z_real-claude-gate_#1696.log`. Each suite executed 53 tests, with 1 unrelated question-answer failure and 0 skips; neither is a fresh gate for this branch. No retrieval product change or decision record is needed.

## Design

Make the existing interrupted-retrieval scenario a standalone regression for the shared repair: open and close a prior `runningToolPeer` with the same pairing before opening the uploading peer. Its accepted handshake binds the token even when no other scenario ran first. The uploading peer must then settle using that identity. No additional Claude turn is sent.

Track the current bounded-wait phase using static labels local to this method. Convert a coroutine timeout into an assertion naming that phase and the existing content-free peer link state. Cover initial and restarted phone readiness, chat creation, peer open, upload, send acknowledgement, turn completion, cache-cleared restart, request-triggered cut, reconnect/Retry, and open/save. Do not print dynamic ids, filenames, tokens, keys, payloads or file bytes. Existing Compose and cut assertions retain their specific failure messages.

Keep the independent upload, message attachment id, cleared thread cache, older-history gesture, retrieval-request cut, failed unnamed row and Retry, exactly one ready filename row, and open/save fixture digests unchanged. No timeout, assertion, signature, production file, dependency, or wire change.

Overlap: #1631, #1642, #1686, #1691, #1692, #1693, #1694 and #1695 edit other scenarios in `InteractiveStreamE2ETest`; #1694 also adds diagnostics to shared helpers. This change remains local to the interrupted-retrieval method and needs none of their additions.

Sizing: approximately 130 written lines including plan and scenario changes; zero new exported types, zero consumer migrations, four acceptance criteria, no state-machine reject branches. One deliverable: interrupted-retrieval regression protection after the shared peer repair.

## State and concurrency model

The prior peer closes through `use` before the uploading peer opens. The uploading peer retains its existing `finally` cleanup. Static identities remain process-scoped under the existing mutex; each dial retains fresh Noise handshake/cipher state. Phase labels are confined to the instrumentation thread and create no job or flow.

## Error handling

Existing waits keep their deadlines and cancellation behavior. Only a `TimeoutCancellationException` escaping this scenario gains a static phase label; errors and retrieval failures otherwise preserve existing handling. Cleanup still restores the link, closes peers/scenarios, removes the intent monitor and deletes fixture outputs.

## Testing strategy

- Reproduce the shared cause with the existing authenticated-handshake JVM regression: temporarily reinstate per-instance identity custody, run it red, then restore the merged repair and run peer identity/factory/redial/wait tests green. The temporary mutation must not be committed.
- Run the existing device-only `PeerIdentityLifecycleTest` regression (real Android graph/DataStore lifetime), and scripted reconnect with zero real Claude turns. Count fresh XML executions.
- Run focused JVM checks, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.
- This existing rung-3 scenario needs real daemon/relay, Android storage/intents and Claude. Dispatcher must obtain a fresh full `python3 scripts/android-test-gate.py live` on the repaired revision, record executed/failed/skipped counts and explicitly confirm this named method ran and passed before documentation/merge. Previous results do not substitute for this pending gate.

## Open Questions

None for implementation. The original method's exact timeout phase is unrecoverable from retained generic stacks; bounded phase diagnostics localize any recurrence in the dispatcher run. Existing post-repair live evidence supports the shared authentication cause rather than an attachment retrieval defect.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, interrupted-transfer scenarios: record reuse of #1698/#1701, standalone prior-peer binding regression, static phase diagnostics, and fresh full dispatcher counts with this method's explicit result.

## Security review

**Verdict:** PASS

- [Trust boundaries] Both peers retain `dialLink`'s authenticated Noise handshake and correlated readiness reply. No daemon frame, name or byte parsing changes.
- [Tokens] Prior and uploading peers use the same instrumentation pairing only in memory, independently of phone credentials. No new storage, token copying into artifacts or authentication bypass.
- [Files and storage] Existing app-private attachment retrieval, cleared thread cache and fixture output cleanup remain unchanged; no new paths or captured credential files.
- [Android attack surface] No component, permission, intent or provider changes. Existing intent stub and monitor cleanup remain intact.
- [Cryptography] Reuse the vendored Noise IK implementation and retained host/token static identity; every dial owns fresh ephemeral/cipher state. No nonce/key schedule changes.
- [Network and I/O] Prior open uses the same bounded handshake/probe and transport defaults; no additional Claude turn, longer timeout, new endpoint or TLS relaxation.
- [Errors and logs] Phase labels are static literals and peer state is counts/static categories. Never interpolate ids, filenames, pairing data, key material, payload text or bytes into diagnostics.
- [Concurrency] Prior peer closes synchronously via `use`; uploading peer and cut retain existing cleanup. No new scope, mutex or hot flow.
- [Threat model] Relay delay/drop still fails bounded waits without plaintext disclosure; token theft, hostile frame decoding and UI leakage remain covered by the existing protocol/product boundaries. The test does not weaken daemon token/static-key binding.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
