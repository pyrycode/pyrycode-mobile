# #2099 — Diagnose and repair reopened-context reconnect readiness

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`, `setHostLink`, `awaitConnected`, and all eight `setHostLink` callers; preserve the offline peer turn and context-ask proof.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: `withTimeoutDiagnostic`; retain timeout cause and cancellation propagation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowConnection.kt`: existing exact-host authenticated readiness pattern.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `connectionFor`, `reconcile`, `selected`; saved credentials own bundles independently of compatibility selection.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt`: `RelayConnectionBundle.close`; distinguish permanent disposal from supervisor close.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt`: `connect`, `close`, `runLoop`; distinguish socket readiness from Noise readiness.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt`: `drive`, `teardown`; authenticated publication follows the Noise response.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `onConnection`, `currentRepository`, `liveRepository`; publication is scoped to an Open pump.
- `docs/knowledge/features/development-verification-test-scheduling.md`: process-wide identity, causal-evidence limits, and counted execution.
- `docs/knowledge/features/relay-reconnect-supervisor.md`, `relay-repository-coordinator.md`: resumable bundle lifetime and Open-gated publication.
- `docs/e2e-interactive-stream.md`: rung-3 harness and the existing context-ask contract.
- Sibling `pyrycode/docs/protocol-mobile.md`: authenticated transport and Security model; no wire changes.

## Context

The historical failure names `setHostLink(up = true)` but its diagnostic only says the captured coordinator has no repository. That does not distinguish registry removal/replacement, transport failure, authentication failure, or publication lag. The earlier retained prefix reports this method passing; the historical failing phone XML/logcat directories no longer exist locally. Retained daemon logs alone cannot establish the failing phone connection or an MCP relationship.

The work starts with correlated diagnosis, then one evidenced test or non-visual connection repair. A fresh baseline runs the exact method before mutation. Changes to replay ordering, visuals, or daemon/relay behavior require routing to their owner rather than expanding this plan. No decision record is warranted yet.

In-flight branches share the test file: #1682 (closed), #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092. Current changes are local to other methods; retain additive edits and do not migrate `awaitConnected` callers.

## Design

Add bounded, content-free stage evidence to the named scenario and `setHostLink` without changing its drive or deadline. Name registration lookup, supervisor close/connect, and repository wait separately. Snapshots distinguish the captured bundle from the currently registered target and selected bundle, socket presence, relay state, authenticated daemon state, and repository availability. Use static state names, booleans, timestamps, and hashed connection identifiers; never stringify credentials, errors, payloads, or state objects carrying free text.

Keep test observation helpers in `app/src/sharedTest/java/de/pyryco/mobile/e2e/` when JVM coverage needs them. Inject the evidence sink and wait inputs. The Android caller supplies the real registry/bundle and debug log sink. Do not introduce production observability or new dependencies just for this diagnosis.

Once evidence identifies the failing operation and cause, append a Revisions entry defining the exact repair and regression before implementing it. Do not treat an improved timeout message or a passing retry as a behavioral repair.

## State and concurrency model

The existing supervisor and coordinator remain application-owned and use their configured dispatchers. Diagnostic collectors are children of the bounded scenario wait and must finish or cancel when it returns. No new durable state, independent scope, retry loop, or timeout increase. The original 30-second reconnect budget applies to the whole operation.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Phone close then restore while peer remains live | Existing named live method retains peer `turn_end` and `context_usage`, then absent phone reading before reopen. |
| Target differs from selected host | Diagnostic regression verifies separate fields; an evidenced readiness repair must have exact-host controls. |
| Registration removed or bundle replaced during wait | Diagnostic regression reports captured-owner identity separately from current registration; do not silently retarget. |
| Socket open while authentication incomplete | Diagnostic regression distinguishes transport from authenticated readiness and repository publication. |
| Timeout, ordinary failure, cancellation | JVM regression preserves causes and propagates cancellation; diagnostics contain only static categories. |
| Reopen without another phone turn | Existing method requires a non-null repository reading and footer percentage after reopening. |

## Error handling

Preserve `withTimeoutDiagnostic` cause chaining. Report the failing static stage and a bounded snapshot; ordinary failures retain their original type/cause. A missing host fails explicitly. Evidence capture must not swallow scenario or cleanup failures. Historical attribution stays unresolved unless a correlated capture proves it.

## Testing strategy

Write JVM diagnostic/control regressions first and run them red, then green. After cause discovery, add a focused behavioral regression against the actual changed helper or production seam and demonstrate red/green on the same fixture. Check existing affected classes, including `PeerWaitTest` and any connection fixture used by the repair. The existing live scenario remains device-only because it needs the real relay, Noise session, off-phone Claude turn, and phone navigation.

Capture focused live XML/logcat and isolated daemon logs with Mobile/daemon revisions and hashes outside disposable worktrees. Focused execution is diagnostic evidence only. The dispatcher owns the fresh full live gate after verification; retain its suite and named-method executed/failed/skipped counts, XML, logcat and correlated daemon artifacts before merge. Keep `needs-real-claude`; if those artifacts are pending, name their exact retention files in the PR and request the builder artifact return.

Run focused unit checks, lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless, then assembleDebug and `scripts/pre-verify.py --gradle` after the last main merge. Do not run the full unit or live suite as a builder final sweep.

## Open Questions

- Which reconnect operation demonstrably stalls, and why? Pending correlated capture; no cause inferred from repository absence.
- Is the captured bundle still registered, and is the target distinct from compatibility selection? Pending per-stage snapshot.
- Does repair belong locally or to an existing daemon/relay owner? Pending evidence; shared files alone are not dependencies.

## Security review

**Verdict:** PASS

- [Trust boundaries] Diagnostic readiness observes the existing Noise-authenticated repository; it never substitutes relay socket readiness or selected-host state for authentication.
- [Tokens and credentials] Snapshots exclude pairing records, tokens, keys and raw connection identifiers. Only `RelayLog.redactConnId` tokens may correlate a connection.
- [Files and storage] No app storage, filenames, cache keys or backup rules change. Raw harness artifacts remain outside production assets until a reviewed sanitized retention handoff.
- [Android attack surface] No exported component, intent, WebView, permission or manifest change; instrumentation remains test-only.
- [Cryptography] Reuse the existing vendored Noise session and per-dial state. No key, nonce, identity or authorization changes.
- [Network and I/O] Keep the existing deadline and capped supervisor backoff. Malicious relay delay still fails the bounded wait; diagnostics cannot redial until success.
- [Errors and logs] SHOULD FIX: enforce bounded static stage/state categories and content-free evidence in tests. Never log exception messages, daemon text, URLs, headers or handshake material.
- [Concurrency] Diagnostic child collectors must cancel with the wait; snapshots are explicitly non-atomic observations, not proof of a causal ordering.
- [Threat model] Relay dropping/delaying frames is observable but not bypassed. Rooted-device token theft, hostile-frame parsing and UI capture protections remain in the existing storage/codec/UI owners; this test-only evidence path does not change them.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12
