# #1785 — Reliable Offline Retry backoff observation

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt`: `stopUntilRetryWindow` counts transient relay status transitions; `recoveryTimeRemaining` bounds startup and recovery.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies` preserves host, conversation, null repository, visible pill and new reply assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply` shares the controller.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt`: `runLoop`, `backoff`, `backoffBaseSeconds`, `jitteredBackoffMs`; consecutive failures reset only after a stable connection.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt`: fake transport and virtual-clock backoff coverage.
- `app/build.gradle.kts`: shared test sources compile into both JVM and instrumentation tests.
- `docs/knowledge/features/relay-reconnect-supervisor.md`: DaemonAbsent uses the same escalating backoff as ordinary failures.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: retained fresh XML and per-test logcat boundaries.
- `docs/e2e-interactive-stream.md`: Offline Retry proof and rung-3/rung-4 contracts.

## Context

The retained #1751 failure XML has 5 executed, 1 failed, 0 skipped; stderr identifies the controller's 90-second observation timeout, before daemon restart or reply. The harness recorded mobile revision `0d5b82be7a05153d497d84da2f1a11fe109864a3`, daemon `65df98859f32e49ba59a42c4446d650b7625cf62`, Claude 2.1.280. Original per-test logcat is no longer present, so the historical occurrence cannot identify exactly which Connecting states were missed. A deterministic regression must establish the observer failure mechanism rather than infer it from a same-tree rerun.

The controller treats conflated UI state as a lossless transition stream and assumes six observed failures equal the actual sixth backoff. Connecting can be overwritten before collection, leaving fewer counted failures; retained failure history can also put the actual cap earlier. Both can make the observation exceed 90 seconds. No reconnect policy or UI change is intended; no decision record is needed.

Overlap: #1833 adds daemon-control request functionality in `DaemonFaultControl`; its changes are in different methods and do not require waiting.

## Design

Expose a small internal, content-free `RelayBackoff` snapshot from the supervisor: actual attempt and monotonic start mark. Publish a new snapshot for each backoff and clear it on exit/close with identity protection against cancelled-loop cleanup. No constructor or existing interface changes. UI continues consuming relayStatus.

Extract the controller's observation into `OfflineRetryWindow` under sharedTest so a JVM regression can drive exactly the helper used by both device methods. First retain the existing transition-count algorithm to reproduce the bug, then await the actual backoff snapshot with attempt at least six. Return a monotonic deadline 20 seconds after that backoff began. Observer lag consumes the budget; it never extends it. Both callers continue to pass the inferred deadline to the controller and retain all existing assertions. Timeout diagnostics identify the backoff-observation phase and static status/attempt values.

## State and concurrency model

The supervisor keeps its existing owned coroutine loop, dispatcher, retries and jitter. The diagnostic StateFlow is hot and bounded to the current backoff; it is not an event counter and does not need every intervening emission. A deadline is anchored at the producer before waiting. The controller subscribes before stopping the owned daemon and cancels its observation on exit. Backgrounding still closes the connection through the existing driver.

## Error handling

Missing cap produces an explicit observation-phase failure including current static relay status and backoff attempt. An expired proof deadline fails before waiting for repository recovery. Fault-control HTTP errors and finally restart behavior stay as they are. No payloads, tokens, identifiers or remote text enter new diagnostics.

## Testing strategy

- Drive the production supervisor with immediate 4404 failures while its StateFlow collector misses transient Connecting states. The exact shared observer must time out before repair and obtain the actual capped deadline afterwards.
- Cover retained failure history across a short-lived successful connection and both daemon-absent and ordinary failures.
- Use a test monotonic clock to prove observer/startup lag subtracts from the deadline and expiry fails. Compare the 20-second deadline to every capped jitter interval's 24-second minimum; without Retry, the production supervisor must not redial within it.
- Run the supervisor and new observer JVM classes, lint, assembleDebug, compileDebugAndroidTestKotlin and forced Spotless.
- Run scripted `offline-retry`, retain its fresh XML and revision with executed/failed/skipped counts. Existing device methods need a real daemon/socket and background scheduler; they remain device-only. No new device scenario is needed.
- Dispatcher after verification: fresh full live suite (`all`), explicitly including the rung-3 Offline Retry method, retaining XML, revision and counts. Builder does not claim live execution passed.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “Offline Retry proof”: replace counted sixth UI transition with the actual supervisor capped-backoff snapshot and producer-anchored monotonic deadline, preserving the 20-second/24-second proof boundary.

## Open Questions

- None. The focused scripted gate validates device access to the internal diagnostic snapshot and deadline behavior.

## Size

Forecast approximately 350–450 written lines including plan, regression tests and extraction; one new internal production type, no changed constructor, two existing controller consumers, four acceptance criteria, no new state-machine failure branches. Within every builder ceiling.
