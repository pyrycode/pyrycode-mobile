# Reopened thread context ask live regression (#1682)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`, `peerStep`, `setHostLink`, and prior-peer scenarios; preserve the reading proof and use established diagnostics.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `keyStore`, `open`, `dialLink`, `close`; readiness requires authenticated Noise plus an answered probe, and close cancels the peer scope.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: the actual live peer store retains host/token identity independently of each session.
- `app/src/test/java/de/pyryco/mobile/e2e/OffscreenPeerReadinessTest.kt`: reuse the complete handshake/encrypted probe fixture, currently testing the older store.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` and `RelayRepositoryCoordinator.kt`: `observeContextUsage` reads the pairing-held projection; connection replacement does not strand this reading.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`: session-settings percentages cannot establish that the open ask answered.
- `docs/knowledge/features/development-verification-test-scheduling.md`: sequential peers must authenticate the same bound identity; reports need executed counts.
- `docs/e2e-interactive-stream.md`: rung-3 prerequisites, context-ask proof and dispatcher live acceptance.
- `docs/specs/architecture/1686-peer-pairing-key-continuity.md`: retained content-free daemon events identify shared token/key rejection, already repaired on main.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: static-key binding and Security model remain authoritative; no wire change.

## Context

Both original base XML summaries report this method failed with an empty failure element (19 tests, 17 and 18 failures respectively). Their stderr companions show an unnamed 30000 ms coroutine timeout. Those stacks cannot identify a scenario stage. The related shared investigation found `static_key_mismatch` / `bound_to_other_key` rejections from recreating peers on the same token; #1686 already fixes this with `PeerDeviceKeyStore`. No independent residual product failure is established. This ticket guards that repair in the context-ask scenario and makes any new failure attributable without weakening the proof. No decision record is needed.

## Design

Construct one pairing record for both peers. Open and close a prior peer using `use`, then open the observing peer, so the scenario exercises identity reuse independently of suite order and adds no Claude turn. Route each peer open, message acknowledgement, turn completion and context frame wait through existing `peerStep` with distinct static labels.

Track static phone-side stage names in this method and wrap coroutine and Compose timeouts with that stage and content-free peer link state. Distinguish initial phone readiness/chat creation, disconnection, reconnection, pre-open reading, reopened reading and footer. Preserve the null-before-open assertion, non-null-after-open wait, peer-only offline turn and percentage assertion. Restore a cut phone link in cleanup if a wait fails while offline. Do not increase deadlines, add sleeps, change footer design, resend turns or alter daemon authorization.

Extend `OffscreenPeerReadinessTest` with a second entry point using the actual live `PeerDeviceKeyStore`; parameterize its existing fixture with a key-store constructor. Keep the older-store regression. Both entry points complete sequential fresh Noise sessions, authenticate the same token-bound identity and exchange correlated encrypted readiness probes, closing the first before starting the second.

Overlaps: #1631, #1642, #1689, #1690, #1691, #1693, #1694, #1695 and #1702 touch other methods/helpers in the live class. This edit stays local to the named scenario and requires none of those branches.

Sizing: one deliverable, three criteria, approximately 170 written lines including plan and regression, zero production files, zero new exported types, no signature migrations or new state-machine rejection branches. All hard limits hold.

## State and concurrency model

Only test-local diagnostic stage and cleanup state are added. Prior peer closes before observing peer opens. Existing peer scopes and pumps retain their cancellation paths; only static identity survives. JVM fixtures use `runTest` and injected test dispatchers, fresh responders/ciphers and guaranteed destruction. Context observation continues using the existing pairing-held projection.

## Error handling

Peer failures name their operation and existing link categories. Phone coroutine/Compose timeouts name a static phase. Original causes remain attached; no credentials, pairing records, key arrays, frame bodies, conversation names or message text enter new diagnostics. Assertions still fail normally; cleanup restoration never substitutes for acceptance.

## Testing strategy

- Add the actual-store JVM regression first. Temporarily reinstate per-instance key ownership in the test-only store, run the new method and require its token-binding assertion to fail; restore the landed store before implementation. No mutation is committed.
- Run affected peer readiness/store/factory/redial/wait JVM tests; inspect counted XML. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.
- Run existing `PeerIdentityLifecycleTest` followed by `RepositoryBindingInstrumentedTest` in one no-relay process, and the isolated scripted reconnect scenario. Device-only reason: real application graph/storage/lifecycle; these checks do not answer the real on-demand context ask.
- Dispatcher after verification: **fresh full rung-3 live suite** on the repaired branch, with executed/failed/skipped counts, candidate commit and explicit confirmation that the named context-ask method ran and passed. PR `Live tests` is `all`; retain `needs-real-claude`. Builder claims no live pass. Scripted fake Claude cannot replace this acceptance.

## Open Questions

None for implementation. A fresh live failure requiring product changes outside this harness scope needs refinement based on its named step.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, context-ask coverage and Verification status: record prior-peer protection, phase diagnostics, diagnosed cause, candidate commit and full dispatcher live counts with explicit named-method pass.
- Pending documentation stage: `docs/knowledge/features/development-verification-test-scheduling.md`, readiness coverage: record that the shared encrypted fixture also exercises the actual live `PeerDeviceKeyStore`, separately from the older store.

## Security review

**Verdict:** PASS

- Trust boundaries: existing `SecondClientPeer.dialLink` still requires authenticated Noise and an answered readiness probe; no authorization bypass or new frame decoder.
- Tokens/credentials: reuse the landed memory-only host/token store, separate from phone credentials. JVM inputs are synthetic; no new persistence or credential output.
- Files/storage: no production storage, paths or backup changes; evidence contains counts and static diagnostic categories only.
- Android attack surface: test-only scenario; no exported components, intents, permissions, providers or UI changes.
- Cryptography: retain only static identity, using fresh vendored Noise IK sessions/ciphers; destroy responders/ciphers and wipe fixture private-key buffers. No nonce, algorithm or binding changes.
- Network/I/O: retain bounded opens/waits and existing backoff; no frame-cap, TLS or relay-validation changes. The fixture has no network endpoint.
- Errors/logs: static stage labels and `linkState` counts/categories only. Never output real tokens, keys, prompts, decrypted frames, identifiers or daemon-authored payload text.
- Concurrency: `use`/`finally` close both peers; failed offline work restores the harness phone link. No new scopes, shared production state or retries around assertions.
- Threat model: Noise still protects relay impersonation/MITM and replay; malicious delays remain bounded by existing deadlines. Token theft gains no disk target. Hostile frames retain existing decoding/rendering, and screenshots/accessibility/input gain no new surface. Prompt injection and rooted-device key extraction remain existing risks outside this test change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Revisions

- 2026-10-04: The new actual-store regression failed under temporary per-instance identity ownership at the token-binding assertion (1 executed, 1 failed, 0 skipped); the landed store was restored before scenario edits. Directly inspected main-daemon logs from the original/repeated branch/base harnesses: `AOLbIc`, `JKsJv3`, `Cf5gl9`, `tWgsL4` contain 102, 84, 102, 90 `static_key_mismatch` / `bound_to_other_key` events respectively. This supports the shared authentication diagnosis; the original anonymous scenario stacks still cannot identify an individual wait.
- 2026-10-04: The footer matcher now accepts the existing `Cxt high:` percentage label as the nearby ping scenario does. This corrects the stale normal-only matcher without changing the null-before-open or non-null-after-open proof, the UI, or any timeout.
