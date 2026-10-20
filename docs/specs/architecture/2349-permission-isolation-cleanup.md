# Fence permission-isolation host cleanup (#2349)

## Files read
- `docs/recorded-tests.md`: What replay proves, Capture and compatibility, and Read acknowledgements and temporary prompts require strict requests, causal delivery and complete consumption.
- `docs/knowledge/features/development-verification.md` and `development-verification-gates.md`: focused native replay and durable counted evidence; complete gates belong to the dispatcher.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionPrompts_heldPerConversation` removes its host immediately after peer completion.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` maps the current phone owner and is a no-op in capture and ordinary execution.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` retains the existing 30-second bound and waits for required requests and queued deliveries.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `closeReady`, `prepareClose`, and sticky failure handling keep strict matching.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteConnectionsTest.kt` and `RecordedActivityRuleTest.kt`: existing close-fence and failure-preservation patterns.
- The original and current-main failure bundles under agents `logs/evidence/2343/builder-20261021-macos/`: both show premature phone closure after all permission stages complete.

## Context
One behaviour: paired-host cleanup must preserve the phone connection until required replay work finishes. This repair and its regression form one useful slice. Peer turn end does not prove phone catch-up completion. No production contract, recording, UI or dependency change is needed.

## Design
Add an internal test-only `withPermissionIsolationCleanup` wrapper beside `PermissionIsolationStage`. The scenario body retains all existing assertions and deadlines. Cleanup closes the peer first, preserving captured lifecycle order, calls `RecordedSuiteNative.beforeHostLinkClose(serverId)`, then removes the paired host. The wrapper attempts every cleanup action even on failure. The scenario's throwable remains primary; otherwise the first cleanup throwable is primary, with later failures suppressed without self-suppression. No tape or scheduler rules change.

Remote branches overlap the scenario file: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139, #2175, #2246, #2311, #2347. This edit is local to permission isolation; #2347 changes `restartApp`, independently. No dependency on those branches.

## State and concurrency model
No production state or coroutine scope changes. The existing instrumentation thread blocks in the replay-only fence while the live phone graph continues background requests. The shared helper is inline so controlled coroutine tests use the actual fence with virtual time. Closing the peer precedes the fence; host removal follows it. A fence timeout still allows physical cleanup and remains a test failure.

## State transitions and identity reuse
| Event | Regression |
| --- | --- |
| Peer completes while required phone query is held, then released | `hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion` |
| Required query never arrives | `missingPhoneWorkFailsAtTheExistingDeadlineAndStillRemovesHost` |
| Wrong owner or full payload arrives | `aReplayMismatchRemainsFirstThroughCleanup` plus existing tape tests |
| Scenario and cleanup both fail | `scenarioFailureKeepsCleanupFailuresSuppressedInOrder` |
| Capture or ordinary execution has no replay fence | `ordinaryCleanupKeepsPeerThenHostOrder` |
No identity allocation or reuse changes; existing tape owner/request correlation remains authoritative.

## Error handling
Retain the original scenario error and the first replay mismatch. Missing work fails within the existing 30-second fence, without fabricated requests or cancellation as a success. Cleanup runs all actions and retains subsequent errors as suppressed exceptions. Existing content-free replay diagnostics remain unchanged.

## Testing strategy
Write the controlled regression against the extracted old cleanup first and observe early removal fail. Then enable the fence. Exercise actual `SuiteConnections` and `RecordedSuiteTape`, holding a real accepted catch-up send until peer completion and retaining delayed delivery. Add timeout, strict owner/payload mismatch and failure aggregation probes. Run the new class plus affected `SuiteConnectionsTest`, `RecordedSuiteTapeTest` and `RecordedActivityRuleTest`. Run focused macOS native `RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation` with the original fixture, retaining revision, exit, XML/logcat, hashes and counts. The existing device method needs the real Android graph and background scheduling. No new device-only test is added. Dispatcher configured UI acceptance remains pending before verifier PASS. Run lint, assembly, Android test compilation, formatting and final pre-verify checks.

## Open Questions
None. Scope forecast: approximately 400 written lines, three acceptance criteria, zero exported production types or migrated consumers, one scenario integration. Below the numeric ceilings; no independently useful second deliverable.
