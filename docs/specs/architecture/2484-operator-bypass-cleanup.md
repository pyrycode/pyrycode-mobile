# Operator-bypass replay host cleanup

## Files read
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` removes its host before the activity teardown fence.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` selects the current phone for the dedicated host.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` waits for required requests, delivery and downstream consumption.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PermissionIsolationCleanup.kt`: `withPermissionIsolationCleanup` already preserves scenario failures and attempts every cleanup action.
- `app/src/test/java/de/pyryco/mobile/e2e/PermissionIsolationCleanupTest.kt`: existing delayed-work, missing-work, identity and failure-order probes.
- `app/src/androidTest/assets/recorded/suite/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.json`: final request 82 at checkpoint 33 and its terminal reply.
- `docs/recorded-tests.md`: “What replay proves” and “Scenario host-removal readiness”; phone completion cannot be inferred from peer completion or rendered text.
- `docs/knowledge/features/development-verification-gates.md`: scenario cleanup must run while phone request producers remain alive.

## Context
The #2462 macOS failure on `2db93ebd8a` stores a lifecycle mismatch at step 39: phone close precedes its required final catch-up. Progress names request 82/checkpoint 33; the causal stack closes through `RelayConnectionRegistry.reconcile`. The later test-end exception rethrows this mismatch. Evidence bundle `db5461c8f6c2f795f941b095d278cb092cf3f6c602d586d56879963b8dcda166`, digest `6a6b24f31c0b914c6968c7d6cc0f1f10d35cc296709078899f7aaf85ad89e193`, retains `logs/verifier-gate__2462_5.stderr.log` and the passing rerun. No product or wire contract changes, new capture or decision record are needed.

## Design
Extract the operator-bypass scenario's cleanup into an internal shared-test function, `withOperatorBypassCleanup`, so a JVM regression exercises the same cleanup used by the native scenario. Initially reproduce the existing peer-close/host-remove order; then delegate to `withPermissionIsolationCleanup`, injecting the existing `RecordedSuiteNative.beforeHostLinkClose(serverId)` between peer close and host removal. This small seam has one scenario consumer and its regression; it introduces no production type or replay admission rule. Keep every scenario assertion and recording byte unchanged.

Remote overlap scan found independent edits to the large scenario file on #2483 and historical feature branches (#1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139, #2175, #2311, #2351, #2462). Keep this edit local to operator-bypass cleanup. #2360 changes activity teardown, which occurs after this host removal; its shared-helper changes are not needed here.

Scope: one behaviour and its regression, approximately 250 written lines, zero new exported types, one scenario consumer, three acceptance criteria, no new state-machine reject branches.

## State and concurrency model
No production jobs or flows change. The replay fence uses the existing 30-second deadline and leaves repository collectors and request producers alive until the selected phone's required prefix is consumed. Capture and ordinary execution use the existing no-op native fence. Deferred gates in `runTest` control request release and reply consumption without wall-clock sleeps or retries.

## State transitions and identity reuse
| Event | Check |
| --- | --- |
| Scenario returns while final catch-up is held | `OperatorBypassCleanupTest.finalCapturedCatchUpFinishesBeforeHostRemoval` holds request 82, then terminal consumption, and verifies removal waits for both. |
| Scenario fails during cleanup | `OperatorBypassCleanupTest.scenarioFailureStillDrainsAndRemovesHost` preserves the scenario throwable while draining and removing the host. |
| Missing phone work or changed identity/checkpoint | Existing `PermissionIsolationCleanupTest.missingPhoneWorkFailsAtTheExistingDeadlineAndStillRemovesHost` and `aReplayMismatchRemainsFirstThroughCleanup` retain strict failure and cleanup; extend wrong-owner probes to another host and another connection. |
| Repeated cleanup failure or same throwable | Existing failure-order and self-suppression probes in `PermissionIsolationCleanupTest`. |

## Error handling
Reuse the existing cleanup failure contract: attempt peer close, replay fence and host removal; preserve the scenario throwable as primary, otherwise the first cleanup throwable; suppress later failures without self-suppression. A fence failure still attempts physical removal and fails the test. No timeout, matching, owner selection or recording normalization changes.

## Testing strategy
Run the new JVM regression against the extracted old cleanup and retain its counted failure before implementing the fence. Reuse the genuine capture's final request/reply and lifecycle tail, adding only connection bootstrap for the focused regression. Hold the request and downstream consumption independently; compare the exact payload and remapped reply identity. Run `OperatorBypassCleanupTest`, `PermissionIsolationCleanupTest`, and `SuiteCloseConsumptionTest` after repair. Run the named `RecordedMobileSuiteTest` method on macOS with the managed Android gate and preserve raw XML/logs, hashes, revision and executed/failed/skipped counts. It remains device-only because it delegates existing native UI assertions and background repository work. Run lint, APK assembly, instrumented compilation, formatting and final pre-verify. Complete UI-suite acceptance belongs to the dispatcher; Linux is outside this ticket's verification scope.

## Open Questions
None. No evidence establishes preceding-test contamination or load as a cause.
