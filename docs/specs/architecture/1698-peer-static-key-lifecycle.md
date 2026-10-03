# #1698 — Retain the test peer's bound static identity

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: constructor, `dialLink`, `close`, and `ThrowawayDeviceKeyStore`; the key currently outlives reconnects but not peer instances.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `runningToolPeer`, and `restartApp`; preserve the complete attachment scenario.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `rebuildGraph`; rebuild disposes app owners within the same instrumentation process.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: peer close and reconnect ownership stay unchanged.
- `app/src/main/java/de/pyryco/mobile/data/crypto/DeviceStaticKeyStore.kt`: `DeviceStaticKeyPair` and the store contract.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt`: `PairedServer` supplies exact server id and token.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt`: `create` wipes the returned private-key buffer, so retained keys must never escape by reference.
- `scripts/e2e-emulator.sh`: peer token minting and curated live selection reuse one token across scenarios.
- `scripts/android-test-gate.py` and `scripts/test_android_test_gate.py`: `E2E_ONLY_SOURCES`, UI skip classification and the source-dependency guard.
- `app/src/androidTest/java/de/pyryco/mobile/di/RepositoryBindingInstrumentedTest.kt`: `ordinaryInstrumentation_explicitlyBindsFakeRepository` asserts no-relay graph isolation.
- `docs/knowledge/features/device-static-keystore.md`: serialize first creation and never silently regenerate an identity.
- `docs/knowledge/features/noise-ik-session.md`: reuse only the static identity; each reconnect creates fresh Noise session state and caller-owned key copies.
- `docs/knowledge/features/development-verification-test-scheduling.md`: counted fresh XML is required; the ordinary device gate excludes the e2e package.
- `docs/e2e-interactive-stream.md`: existing real-Claude harness and restart seams.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model are the wire/security authority.

## Context

The daemon binds a pairing token to the first accepted device static key. Sequential test peers incorrectly generate new keys for that token. Retained failing branch/base daemon logs contain respectively 102 and 84 `static_key_mismatch` / `bound_to_other_key` events. This ticket repairs test identity custody, without changing production storage or daemon authentication. No decision record is needed.

Sizing: approximately 350 written lines across a plan, one shared helper, JVM regressions, a focused device regression, and a local peer edit. One deliverable, three acceptance criteria, at most three new test-only types, zero consumer updates, and one misuse guard. Codegraph finds one constructor caller for the removed nested store. No in-flight feature branch overlaps the planned files.

## Design

Add internal `PeerDeviceStaticKeyStore` under `app/src/sharedTest/java/de/pyryco/mobile/e2e/`. Its companion retains a nested map keyed by exact server id and exact token, with no concatenation or generated credential-bearing `toString`. The class binds to its constructor pairing and refuses requests for another server id using a static error message. Relay URL and server-key metadata do not rotate this host/token identity.

Generate each retained X25519 pair through vendored `Noise.createDH("25519")`, destroying the temporary DH object. `loadOrCreate` and `publicKey` return copies; retained arrays are private. Retention lasts only for the instrumentation process, independent of peer sockets, app credentials, and Koin graph ownership. No disk persistence, clearing-on-close API, or new dependency is introduced.

Replace `ThrowawayDeviceKeyStore` in `SecondClientPeer` with the helper and correct the obsolete lifecycle comments. Give its store property internal test-only visibility for an instrumentation regression to prove the actual peer wiring. Leave all eight peer callers and the complete attachment scenario unchanged.

## State and concurrency model

One companion-owned coroutine `Mutex` serializes lookup and first generation across store instances. No suspension occurs inside the lookup/generation block; readers receive fresh arrays. The helper owns no jobs or flows. Existing peer scope cancellation and link teardown remain unchanged; the retained static identity is not a session cipher or nonce counter. Graph rebuild cannot dispose the companion map.

## Error handling

A mismatched server-id request fails immediately with a credential-free argument error. Key generation failures do not insert a partial pair. Existing `NoiseSessionFactory` error mapping and peer dial timeouts remain unchanged. No new logging is needed: identity custody stays silent, and existing harness lifecycle diagnostics remain available.

## Testing strategy

Write JVM regressions first under `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`. Prove continuity across equivalent pairing/store instances, host isolation, token isolation, public/private defensive copies (including zeroing), concurrent creation, and repeated `NoiseSessionFactory.create`/close/reload using the same retained identity. Use synthetic credentials and `runTest`; failure messages must not print key bytes.

Add `PeerIdentityLifecycleTest` in the instrumentation e2e package. Construct actual sequential peers, read their internal store identities, close them, rebuild `E2eTestApplication` on the main thread with no activity alive, and compare the next peer's identity. It needs the installed test application and actual Android graph/lifecycle owners, which a pure JVM helper test cannot supply. Run this one class on the managed Android 13 device; it uses no network or Claude credential.

Run affected helper/factory/redial/wait JVM tests, lint, assembleDebug, compileDebugAndroidTestKotlin, formatting, and forced spotlessCheck. Run one deterministic reconnect scenario for the shared harness. The dispatcher must run the full `python3 scripts/android-test-gate.py live` suite after verification (`## Live tests` = `all`), retain executed/failed/skipped counts and the named attachment method's passing result, and compare repaired daemon diagnostics with the prior binding rejections. Pending live evidence is an explicit handoff, not a claimed pass.

## Open Questions

None. Retention is deliberately process-scoped, matching one instrumentation run; process death uses fresh harness pairing tokens.

## Security review

**Verdict:** PASS

- [Trust boundaries] The helper accepts only the test harness's parsed `PairedServer`. Exact server/token separation and the server-id guard prevent a caller from retrieving another host's key. It adds no trust to daemon content.
- [Tokens, secrets and credentials] Test credentials and static keys stay in process memory only. Private arrays never escape without copying; zeroing handshake copies cannot destroy the bound identity. Revoked/new tokens have separate entries; process exit ends retention. No app credential storage is used.
- [Files and storage] No path, backup, disk write, or persistent credential entry is added.
- [Android attack surface] No component, provider, intent, UI, or permission changes. The graph regression runs only in the test APK.
- [Cryptography] Vendored Noise secure key generation and the existing IK suite remain unchanged. Only static identity is reused; session creation supplies fresh handshake/cipher state, preserving nonce separation. Token lookup is local exact map selection, not an attacker-facing authentication comparison.
- [Network and I/O] Existing transport bounds, timeouts and redial ownership remain unchanged. The daemon's key-binding rejection is preserved.
- [Errors, logs and telemetry] Map contents, tokens, keys and plaintext must never be logged or included in assertions. Tests use boolean equality assertions for secret buffers. No generated identity-key representation or telemetry is added.
- [Concurrency] A single mutex protects first creation and retrieval; no nested locks or lifecycle jobs are introduced. Failed generation publishes nothing.
- [Graph lifecycle] Rebuilds must retain the instrumentation run's original fake/relay mode, carry the existing DataStore, unregister the previous lifecycle driver and dispose Koin owners. The lifecycle regression must rebuild in guaranteed cleanup so later no-relay tests keep fake bindings. No credentials are re-saved or read into evidence.
- [Threat model] Against protocol-mobile's Security model: relay MITM/server-id impersonation remains prevented by pinned Noise authentication; drops/replay still use existing transport/Noise protections. Disk token theft gains no new target because nothing is persisted. Hostile frames, prompt injection and UI leakage have unchanged parsing/rendering surfaces. In-process test-key extraction remains a test-process compromise risk, bounded to disposable harness credentials and process lifetime; production Keystore behavior is unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Revisions

### 2026-10-04 — verifier rework

The verifier found that the new lifecycle test referenced `SecondClientPeer` outside the gate's e2e-only classification, and that `rebuildGraph` changed an ordinary no-relay run to relay mode. Add `PeerIdentityLifecycleTest` to `E2E_ONLY_SOURCES` and cover its UI skip classification while retaining the source-dependency guard and the exclusions for the shared application/runner. Preserve the original fake/relay mode in `E2eTestApplication.rebuildGraph`, chosen from the same instrumentation relay argument as `onCreate`, with unchanged DataStore carry-over and lifecycle-owner disposal. Rebuild in the lifecycle test's guaranteed cleanup and assert repository mode and DataStore continuity afterward. Run it followed by `RepositoryBindingInstrumentedTest` in one no-relay instrumentation process and retain fresh counted XML before running the scripted reconnect scenario.

The only in-flight overlap is #1642 adding a scripted scenario entry to `scripts/android-test-gate.py`; it changes a different block and is not a dependency. Revised scope remains one deliverable, three criteria, no new exported types or consumer changes, and under 500 written lines including the original implementation, tests, plan and rework.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, rung-3 peer-started-turn description: replace the obsolete per-scenario throwaway identity description with process-scoped exact host/token reuse; retain fresh per-dial Noise state and separation from app credentials.
- Pending documentation stage: `docs/knowledge/features/development-verification-test-scheduling.md`, Test scheduling and harnesses: record the peer key helper's defensive-copy requirement (`NoiseSessionFactory` wipes caller buffers), graph-mode cleanup and the focused lifecycle-plus-binding device command/evidence route.
- Pending dispatcher live gate before documentation and merge: record full `python3 scripts/android-test-gate.py live` executed/failed/skipped counts, the named attachment method's passing result and the repaired daemon diagnostic comparison against prior `static_key_mismatch` / `bound_to_other_key` rejections. No live pass is claimed here.
