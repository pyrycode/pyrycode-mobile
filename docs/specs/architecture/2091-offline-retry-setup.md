# #2091 — Offline Retry scenario setup and failure evidence

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies` resolves a bundle before any setup wait; `awaitConnected` observes the selected host, not necessarily the outage host.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `rebuildGraph` replaces the registry between full-suite methods.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `reconcile` registers bundles asynchronously; `hosts` publishes registration; `connectionFor` gives exact-host ownership.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt`: `stopUntilRetryWindow` owns stop, pill observation and capped-backoff observation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OfflineRetryWindow.kt`: `awaitDeadline` and `remainingMs` enforce the producer-anchored 20-second budget.
- `app/src/test/java/de/pyryco/mobile/e2e/OfflineRetryWindowTest.kt`: current backoff and observer-lag regressions.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt`: `Fixture` exercises real registry, supervisor and Noise coordinator with virtual scheduling and two hosts.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: the rung-4 twin preserves the real outage and Retry proof.
- `scripts/e2e-daemon-fault.py` and `scripts/test_e2e_daemon_fault.py`: restart waits for owned-daemon relay registration; process ownership is already tested.
- `docs/knowledge/features/relay-reconnect-supervisor.md`: do not infer attempt counts from conflated UI status.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: retained XML and authoritative repository availability, not selected-host status, establish readiness.
- `docs/e2e-interactive-stream.md`, “Offline Retry proof”, and sibling `pyrycode/docs/protocol-mobile.md`, “Security model”: proof and trust contracts.

## Context

#1977's report records this method failing once and passing on rerun, without its failing phase. The original HP logs are absent here. A baseline scripted run at `b44067bbd` passed one test without failures or skips; that is not diagnosis of the historical failure.

The live scenario has a concrete setup race: `connectionFor` is required immediately after activity launch, while registry reconciliation runs on a background dispatcher. It also waits for compatibility `Connected`, which can describe Idle or another selected host. Full-suite methods share an application graph, can replace it with `rebuildGraph`, and can change selection. A deterministic delayed-registration and ready-peer control will test this mechanism, without claiming unavailable history proves which arm failed. No decision record is needed.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088 and #2089 edit other methods/imports in the live class. Keep this method's changes local; no branch supplies a required dependency.

## Design

Add a test-only `awaitOfflineRetryHost(registry, serverId, timeoutMs)` helper. Under one existing setup timeout it waits for exact-host registration, then that bundle's authoritative non-null repository. Return the owning bundle only after this readiness. The live scenario uses it after list arrival, replacing the early immediate lookup and selected-host wait. No production API changes.

Add test-only stage diagnostics used by this scenario and its controller. Fixed enum stages separate setup/list/create, daemon stop, visible Offline pill, capped backoff, daemon restart, pre-tap assertions, Retry tap, repository recovery, pill clearing, same-conversation check, prompt send and reply rendering. Capture only static relay/daemon class names, registration/selection/repository booleans, actual attempt, elapsed backoff and remaining deadline milliseconds. Never serialize a model, host record, exception message, message payload or credentials. Add diagnostic evidence as a suppressed assertion to the original exception and rethrow the original. Diagnostic failures must not replace the scenario failure. Report stage entry and failure through an injected sink (Android Log on device).

The owned stop/start implementation and all recovery assertions remain required. The controller's two consumers continue using actual attempt ≥ 6 and the original producer mark + 20 seconds. Observer lag, restart and tap continue consuming that same budget.

## State and concurrency model

Only test-local stage and optional deadline state are added. Registry/connection flows remain hot, owned by their existing scopes. Readiness suspends under the caller's timeout and cancellation; it sends no retry and opens no socket. Controller backoff observation remains subscribed before stop and cancelled on exit. Diagnostic stage updates are synchronous at awaited operation boundaries.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fresh/rebuilt graph with delayed host reconciliation | `offlineRetrySetupWaitsForDelayedRegistration` |
| Earlier method leaves peer B selected and ready while target A handshakes | `offlineRetrySetupDoesNotAcceptReadySelectedPeer` |
| Setup timeout/cancellation | `offlineRetrySetupTimeoutDoesNotReturnALateHost` |
| Any awaited operation throws; diagnostic callback also throws | `OfflineRetryDiagnosticsTest` original-exception and diagnostic-failure cases |
| Owned daemon outage/restart, same host and conversation, tap, new reply | Existing scripted twin; full dispatcher live scenario |
| Lag consumes recovery budget; passive recovery cannot pass before Retry deadline | Existing `OfflineRetryWindowTest` plus focused passive-redial boundary regression |

## Error handling

Setup timeout stays a real failure, labelled with its awaited stage and static state. Stop/restart, cap observation, Retry recovery and rendered reply failures remain distinct. Preserve the original throwable and its stack. Cleanup still restores the owned daemon; retain cleanup failure on the original failure where present. No blanket scenario retries, timeout extension or assertion removal.

## Testing strategy

Write readiness regressions against the current immediate lookup/selected-host behavior first and retain RED XML; repair and run GREEN. Unit-test diagnostics with exceptions carrying sentinel secret text and a failing evidence sink, ensuring new diagnostics contain no supplied payload and the original exception survives. Run the existing backoff regressions and a passive-redial negative control against the real supervisor.

Run focused unit classes, daemon-controller Python tests, lint, assembleDebug, instrumentation compile and forced Spotless. Run scripted `offline-retry` after repair and retain fresh XML, tested revision, executed/failed/skipped counts. Existing device tests need real daemon sockets/background scheduling; no new device class is added.

Dispatcher after verification owns a fresh full live gate (`all`), explicitly passing `InteractiveStreamE2ETest#interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`, with revision, XML and counts. A focused pass is never full-suite proof.

## Open Questions

- The historical failing phase is unknown. Resolve only with retained failure evidence; keep that limit explicit alongside the deterministic setup reproduction and new diagnostics.
- If fresh evidence demonstrates a different failure, revise this plan before expanding the repair, and route a sibling-owned cause to its owner.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “Offline Retry proof”: record exact-host setup readiness, stage evidence and the final scripted/full-live tested revisions and counts, while preserving the existing producer-anchored recovery contract.

## Size

Forecast 500–650 total written lines including plan and regressions, three test-only internal types, two controller consumers, four acceptance criteria, no new production state-machine branches. Within every builder ceiling.

## Security review

**Verdict:** PASS

- [Trust boundaries] Diagnostics accept static enum stages and derive only class names, booleans and timings from local connection state. No daemon-authored string enters new evidence.
- [Tokens, secrets] MUST FIX resolved in design: never stringify `PairedServer`, connection status payloads or the caught exception; attach a separate content-free diagnostic to the original throwable. Existing Keystore/token handling remains unchanged.
- [Files and storage] Evidence uses harness-owned build XML/private scratch paths. No new input-derived file path, persistent cache or secret store.
- [Android attack surface] No manifest/component/deep-link/provider changes; test-only sink uses existing instrumentation Log.
- [Cryptography] Reuse production Noise and fake-peer fixture; no new key generation or cipher changes.
- [Network and I/O] Keep loopback-only owned-daemon control, existing bounded socket waits and relay-readiness acknowledgment. Setup adds no network request and does not retry.
- [Input validation] Stage vocabulary is closed and diagnostics exclude host ids, prompts and arbitrary remote fields.
- [Concurrency] One setup deadline bounds registration and repository readiness; cancelled observation cannot return a late host. Keep producer-anchored Retry deadline unchanged and test passive-redial exclusion.
- [Threat model] A delaying/malicious relay can fail the bounded test but cannot extend its proof budget or leak plaintext through new evidence. Token theft, hostile-frame decoding and UI credential-entry leakage remain handled by unchanged production boundaries in the protocol's Security model; this test does not relax them.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12
