# Stop scenario peer-opening regression (#1696)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` opens its peer before sending the held turn; preserve every Stop assertion.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `dialLink` requires a handshake and correlated `list_conversations` answer; `close` tears down the pump synchronously.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: process-scoped host/token identity from merged #1698, with defensive copies and serialized generation.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: store and factory lifecycle regressions; extend coverage to the authenticated identity on the wire.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`: `TestResponder` demonstrates the vendored Noise responder API.
- `scripts/e2e-emulator.sh`: host-A peer pairing is minted once with remote-permission capability, then supplied to successive scenarios.
- `scripts/android-test-gate.py`: the named Stop method remains curated; live setup stays dispatcher-owned.
- `docs/knowledge/features/development-verification-gates.md`: fresh executed counts and androidTest compilation are required; e2e-only changes skip the ordinary UI gate.
- `docs/e2e-interactive-stream.md`: stop-running-turn contract and #1698 full-suite evidence; the method name predates removal of Interrupted-label assertions.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Device static key and Security model are authoritative; daemon token binding must remain intact.

## Context

All four retained stderr reports for #1631/#1637, branch and base, fail at initial peer open, before the phone sends `STOP_HOLD_PROMPT`. Matching retained daemon logs in `pyry-e2e.AOLbIc`, `pyry-e2e.JKsJv3`, `pyry-e2e.Cf5gl9`, and `pyry-e2e.tWgsL4` contain respectively 102/84/102/90 `static_key_mismatch` and `bound_to_other_key` occurrences, and zero redemption-window occurrences. Successive peers formerly generated new static keys while sharing a token the daemon had already bound. #1698 (merged PR #1701) repairs this lifecycle; reuse it without another key cache or daemon change. The earlier post-Stop reply-loss investigation is a different failure stage.

## Design

Before the Stop scenario opens its observing peer, open and close a separate peer using the identical pairing. This supplies a prior accepted identity even when the method runs alone, making its existing readiness check sensitive to per-instance key rotation. Both opens use the existing handshake/probe contract and bounded waits, without sending another Claude turn. Keep every permission, cancellation, Stop-removal, same-thread reply, second-turn-end and absent-held-reply assertion intact.

Add one JVM regression that constructs sequential `NoiseSessionFactory` instances with the retained peer store, reads each `noise_init` through a fresh vendored Noise responder, and checks the authenticated static public key remains identical while handshake messages differ. Verify the encrypted hello carries the same synthetic token, using content-free assertion messages. This checks actual handshake input rather than only comparing store arrays. No new exported type, dependency, production behavior, or wire contract.

Overlap: #1631 and #1642 touch other methods in `InteractiveStreamE2ETest`; keep this change local to the Stop method.

Sizing: approximately 200 written lines including this plan and regressions, zero exported types, zero signature migrations, three acceptance criteria, no new reject branches. One deliverable: regression protection for Stop peer readiness after the shared repair.

## State and concurrency model

The prior peer owns its existing IO coroutine scope and closes through `use`; the observing peer retains its existing `finally` cleanup. Shared static identity stays process-scoped under `PeerDeviceStaticKeyStore`'s mutex. Each dial and JVM responder has fresh handshake state; no ciphers or nonces are shared. The JVM test uses `runTest` and an injected test dispatcher, with all Noise state destroyed in cleanup.

## Error handling

Existing `peerStep` categorizes prior-open versus observing-open timeouts without exposing credentials. No retries, timeout changes, error suppression, or changed cancellation outcomes. A rotated identity fails the static-identity assertion on the JVM and prevents the observing peer from settling live.

## Testing strategy

- Run the new JVM regression red with the former per-instance identity lifecycle temporarily reinstated, then restore the landed process-scoped repair and run the affected peer/factory/redial/wait tests green. Commit no temporary mutation.
- Run focused JVM tests, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply, and forced spotlessCheck; record executed counts.
- Run the scripted reconnect scenario against isolated test binaries with zero Claude turns. The existing Stop method requires a real daemon/relay and Claude, so remains device-only; its full live execution is explicitly handed to the dispatcher.
- The dispatcher must run fresh full `python3 scripts/android-test-gate.py live` on this branch and record tested commit, executed/failed/skipped counts, and the named Stop method's passing testcase. No prior full-suite result substitutes for this gate.

## Open Questions

None. The retained rejection evidence and current daemon contract identify the cause; #1698 is already merged into this worktree.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, stop-running-turn coverage entry: record confirmed token/static-key mismatch cause, reuse of #1698, explicit prior-peer regression setup, and fresh dispatcher full-suite evidence. State that cancellation removes Stop and that since #1357 no Interrupted status label is asserted.

## Security review

**Verdict:** PASS

- [Trust boundaries] The JVM responder uses `HandshakeState.readMessage` to authenticate the initiator key and recover hello; the live scenario keeps `SecondClientPeer.dialLink`'s handshake/probe boundary. Daemon authentication is unchanged.
- [Tokens] Only synthetic JVM tokens enter the new regression. Real pairing remains instrumentation-only and memory-only; never print pairing records, tokens, key arrays, or transcripts. Static identity reuse is scoped to the exact host/token by the landed store.
- [Files and storage] No credential files, cache paths or app storage changes. New tests and the plan contain no captured secret material.
- [Android attack surface] No component, intent, provider, permission, or UI changes; only existing e2e peer instances are added.
- [Cryptography] Use the vendored `Noise_IK_25519_ChaChaPoly_BLAKE2s` responder and `NoiseSessionFactory`. Fresh handshake states preserve ephemeral/cipher independence. Destroy responders and sessions and wipe the test responder's private key.
- [Network and I/O] The added live open uses existing bounded readiness and transport defaults; no TLS relaxation, new endpoints, longer waits or redial changes.
- [Errors and logs] Assertions name identity continuity and encrypted hello categories only; comparisons must not dump keys or tokens. Retained-log investigation publishes counts and static rejection codes only.
- [Concurrency] Prior peer closes before observing peer opens; each is cleaned on failure. Shared store serialization remains intact. The JVM test has no unowned job.
- [Threat model] Malicious relay handling, authenticated frame decoding, phone Keystore custody and screenshot/accessibility protections remain the current protocol/product contract. This test-only change adds no rendered content or disk token storage and never bypasses daemon key binding.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
