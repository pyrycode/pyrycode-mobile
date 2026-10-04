# Offscreen recovery peer readiness (#1692)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`, `runningToolPeer`, `peerStep`; retain all offscreen/cache/recovery checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, `attempt`, `close`; settlement requires both handshake and correlated readiness-probe reply.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start` retries unsettled links, without diagnosing their failed stage.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: `retainedKey` already fixes exact host/token identity custody in merged #1698.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: existing wire-identity regression stops after the initiator hello; complement it with complete encrypted probe exchanges.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`: `TestResponder` and `establish` demonstrate fresh responder state and mirrored transport ciphers.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: lifecycle regression exercises real application graph cleanup without live credentials.
- `docs/knowledge/features/development-verification-test-scheduling.md`: same-token sequential peers must retain identity, and executed counts must come from fresh XML.
- `docs/knowledge/features/noise-ik-session.md`: fresh per-dial cryptographic state and caller-owned private-key copies are essential.
- `docs/e2e-interactive-stream.md`: rung-3 harness, #1036 redialing and #1698 identity repair evidence.
- `scripts/e2e-emulator.sh`: named offscreen method remains in the curated live selector.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: static key custody and Security model remain authoritative; no wire change.

## Context

The four original/repeated #1631/#1637 branch/base stderr reports fail at initial peer open, before the first prompt. Their retained daemon logs in `pyry-e2e.AOLbIc`, `pyry-e2e.JKsJv3`, `pyry-e2e.Cf5gl9` and `pyry-e2e.tWgsL4` contain respectively 102/84/102/90 `static_key_mismatch` rejections with `bound_to_other_key`. The same pairing token was reused with new per-instance static keys. Authentication therefore failed before probe settlement, rather than at newest-page recovery. Merged #1698 (PR #1701) already repairs this Mobile-owned lifecycle. Reuse it; do not add another identity cache or alter daemon authorization.

## Design

Open and close a prior `runningToolPeer` with the exact same pairing before the scenario opens its observing peer. Both opens retain the existing handshake/probe readiness contract and timeout. This exposes identity rotation independently of suite order, adds no Claude turns, and preserves the ping cache, permission-held offscreen completion, phone unread fold, absent held reply after reconnect, and four exactly-once chronological recovered rows.

Add `OffscreenPeerReadinessTest` under JVM tests. Sequential fresh factories and fresh vendored Noise responders authenticate the same token-bound static identity, complete hello/hello_ack, and exchange encrypted `list_conversations`/`conversation_list` envelopes with matching correlation ids. Destroy each session and responder cipher pair before constructing the next. This checks readiness beyond array identity comparisons without replacing real peer participation in the live scenario.

Overlaps: #1631, #1642, #1686, #1693, #1694 and #1695 touch other methods or helpers in the same live class; this edit stays local to the offscreen method and needs none of their changes.

Sizing: one deliverable, three acceptance criteria, approximately 260 written lines including plan/tests, zero production changes, zero new exported types or signature migrations, no new state-machine reject branches. The written plan remains below every sizing boundary.

## State and concurrency model

Prior peer cleanup uses `use`; observing peer cleanup retains its existing `finally`. Each owns the existing IO scope and pump. Only the process-scoped static identity is reused under the landed store mutex. No handshake or cipher state is shared. JVM work uses `runTest` with an injected test dispatcher and guaranteed Noise cleanup.

## Error handling

Prior-open and observing-open timeout messages identify distinct static steps through `peerStep`. No extra retries, larger deadlines, ignored tests or weaker assertions. JVM token/key checks use content-free messages and never print envelopes or raw arrays.

## Testing strategy

- Add the JVM regression first; temporarily reinstate the former per-instance identity lifecycle to establish a failing token-binding assertion, then restore #1698's exact implementation and run green. Commit no mutation.
- Run focused peer readiness, identity, redial, wait and factory JVM tests with counted XML; lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.
- Run the existing device lifecycle-plus-repository-binding check (real Android application owners and storage cannot be replaced by pure JVM state) and scripted reconnect scenario using isolated binaries and zero Claude turns.
- Dispatcher after verification: fresh **full** live suite on the repaired merge candidate, reporting commit, executed/failed/skipped counts and explicit pass of `de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`. Keep enabled, curated and `needs-real-claude`; no builder live pass is claimed.

## Open Questions

None. The shared repair has landed and the retained rejection evidence identifies authentication as the failing stage. Any subsequent live failure must be assessed by its own failing step.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, Coverage — hardened and Verification status: record same-token prior-peer protection, authentication evidence, repaired merge candidate and dispatcher full live counts with explicit named-method pass.
- Pending documentation stage: `docs/knowledge/features/development-verification-test-scheduling.md`, Test scheduling and harnesses: record complete encrypted readiness-probe coverage across sequential closed sessions.

## Security review

**Verdict:** PASS

- Trust boundaries: the regression authenticates keys with vendored Noise responders; live `dialLink` still requires handshake and correlated probe, with no bypass.
- Tokens: only synthetic JVM pairings; real credentials remain in the existing instrumentation path and process-memory store. Assertions never print them.
- Files/storage: no new persistence or credential files; the existing app cache is read by unchanged live assertions.
- Android attack surface: no components, intents, permissions or manifest changes; the extra peer remains test-only.
- Cryptography: fresh `Noise_IK_25519_ChaChaPoly_BLAKE2s` state per session, with only the static identity retained; destroy cipher pairs and wipe responder private-key buffers in cleanup.
- Network/I/O: existing bounded live dialing and backoff remain; no TLS, frame-cap or relay URL changes. The JVM responder is in-memory and uses no external endpoint.
- Errors/logs: use static categories for binding/probe failures, never plaintext, tokens, keys, frames or transcripts in assertion output.
- Concurrency: prior and observing peers close deterministically; no new scope or shared mutable production state.
- Threat model: pinned Noise still protects against relay impersonation; malicious relay denial remains bounded by unchanged deadlines. Token theft, hostile daemon rendering and UI leakage receive no new surface because this change introduces no storage/rendering/input path. No authorization check is weakened.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Revisions

- 2026-10-04: Protocol fixture correction during implementation: `RemoteConversationRepository.TYPE_CONVERSATIONS` names the readiness response `conversations`, not the proposed `conversation_list`. Use the existing wire verb in the encrypted regression; the handshake, binding and correlation contracts are unchanged.
