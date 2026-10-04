# #1694 — Diagnose the background permission-push wait

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`, `peerStep`, `awaitPushRegistered`, `sendAppToBackground`, `setHostLink`; distinguish peer opening from push and host-link waits.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, `sendMessage`, `awaitPermissionModal`; an opening peer must complete its handshake and answer a probe before proceeding.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` and `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt`: bounded waits and content-free errors; host/peer step diagnostics belong beside these test-only helpers.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`: `start` and `describe`; failed handshakes cannot settle an open session.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt` and its JVM tests: #1698 already retains the identity bound to the shared peer token, with defensive copies and fresh Noise sessions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: sequential actual peers retain their identity through close and graph rebuild.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/development-verification-test-scheduling.md`, `docs/knowledge/features/push-messaging-service.md`, and `docs/knowledge/features/lifecycle-connection-driver.md`: token-bound peer continuity, counted evidence, and the bounded payload-free FCM wake.
- `docs/e2e-interactive-stream.md`: background-push scenarios, Live mode and #1698 evidence; loopback cannot prove FCM delivery.

## Change

The retained #1631 base daemon log accepted one harness peer, then rejected all subsequent peer handshakes (84 `static_key_mismatch` / `bound_to_other_key` rejections). The scenario opens a new peer before registering push or backgrounding, and `SecondClientPeer.open` cannot finish without an accepted handshake and answered probe. This identifies the opening wait as the causal 30-second stall, inferred from handshake evidence and control flow rather than a localized stack. #1698, already on this branch's base, repairs that shared-token identity lifecycle. Its fresh full dispatcher live report executed 53 methods, failed one unrelated method, skipped none, and explicitly passed this scenario. This is baseline evidence, not acceptance of the new candidate.

Make this scenario exercise two sequential peers even in a focused live run: open and close a prior peer with the same pairing before opening the scenario peer. This makes identity continuity a prerequisite independent of JUnit ordering. Route its open, send-message and permission waits through the existing `peerStep`. Extract that wrapper's timeout-to-assertion behavior into a small suspending `withTimeoutDiagnostic` helper in `PeerWait.kt`, preserving the original exception as cause and obtaining content-free state only on failure. Use the helper in `setHostLink` to distinguish close and reconnect waits and report repository presence. No signature changes or simultaneous consumer migrations are needed.

Keep the background-before-peer-message ordering, real FCM requirement, timeout values, exactly-one alert and original `postTime` assertions, second reconnect, and held-turn/notification cleanup intact. No production behavior or wire contract changes. Shared-file overlaps with #1631, #1642, #1686 and #1696 affect different scenario blocks; keep edits local. Sizing: under 300 written lines, no new exported types, zero consumer signature updates, three acceptance criteria, one existing timeout classification.

## Testing strategy

Write JVM tests first for a rejected dial timing out as the named opening step with current link state, a stalled message acknowledgement reporting its own step, successful results, and non-timeout failures/cancellation passing through. Observe missing diagnostics fail before implementing the helper. Re-run `PeerDeviceStaticKeyStoreTest`, `PeerWaitTest`, and `RedialingLinkTest` for the underlying repair and diagnostic boundary.

Run the existing focused `PeerIdentityLifecycleTest` on the managed Android 13 device (it needs actual Android graph/lifecycle owners), followed by `RepositoryBindingInstrumentedTest` in the same process to verify cleanup. Run the deterministic reconnect scenario to check shared host-link harness behavior; it cannot prove push. Run lint, assembleDebug, compileDebugAndroidTestKotlin, formatting and forced spotlessCheck.

Dispatcher handoff: run the full live suite on the pushed candidate (`Live tests: all`, since `peerStep` and `setHostLink` are shared). Before merge, retain tested commit, executed/failed/skipped counts, and explicit confirmation that `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` ran and passed. The builder does not run real-Claude gates or claim a candidate/main live pass in advance. Preserve the issue's inherited-live-test marker and `needs-real-claude` label.

## Security review

**Verdict:** PASS

- [Trust boundaries] `withTimeoutDiagnostic` consumes test-authored labels and boolean/count/link-state summaries only. No frame parsing, push payload or daemon-authored text enters its diagnostics.
- [Tokens] The prior peer reuses `PeerDeviceStaticKeyStore`'s exact host/token identity; secrets stay in process memory and are never printed. Each dial retains fresh Noise state. No credential storage changes.
- [Files and storage] No runtime file writes or new paths; retained reports are read as evidence only.
- [Android attack surface] No manifest, intent, exported component or pending-intent changes; the existing payload-free FCM wake and Home background operation stay intact.
- [Cryptography] Reuse vendored Noise and #1698's defensive key copies. Do not generate a replacement key for an already-bound token or change authentication.
- [Network and I/O] All existing deadlines and capped redial backoff remain. A rejected or silent relay still fails within the original bound.
- [Errors and logs] SHOULD FIX: diagnostic callbacks must remain lazy and content-free, preserve the timeout cause, and leave other failures unchanged. Unit regressions check these contracts. No new log or telemetry output.
- [Concurrency] Prior peer uses guaranteed `use` cleanup. The scenario's watcher cancellation, held-turn release, peer close and alert cancellation remain in `finally`. The diagnostic helper owns no jobs and does not swallow ordinary cancellation.
- [Threat model] Dropped/delayed relay traffic remains deadline-bounded and visible as a named failure. Hostile frames still go through unchanged production decoding. Disk token theft and UI-side leakage introduce no new surface because this patch changes only test orchestration and static diagnostics.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Revisions

### 2026-10-04 — Pin the opening wait to the retained scenario

The isolated base daemon's `conversations.json` maps `e2e955-prompt-1791062837050` to conversation `52fb7ee0-7f62-445e-8f56-f90136224b8b`. Its retained `pyry-e2e.JKsJv3/daemon.log` records creation at 00:27:17.607+03:00 and successful rename at 00:27:17.632+03:00, followed by peer handshake rejections at 00:27:17.703, 00:27:18.793, 00:27:24.972, 00:27:33.089 and 00:27:41.195. The next scenario creates its chat at 00:27:49.097. Thus the named scenario reached its peer-opening call, which spent the original 30-second window retrying a token bound to another static key; it never reached push registration, backgrounding or alert assertions. The repaired #1698 full live report at `52b646ac8f2c8a557b85a34f98f354eaf0c42c08` explicitly passes this method (53 executed, 1 unrelated failure, 0 skipped), and `pyry-e2e.shYptv/daemon.log` has zero mismatch/bound-key rejections. This strengthens the diagnosis without changing the design or claiming candidate acceptance.
