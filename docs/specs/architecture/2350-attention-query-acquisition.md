# Attention replay initial-query acquisition (#2350)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished` owns the original navigation, permission, completion and expiry assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `drain`, `request`, `assertComplete` preserve exact queries and causal delivery.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: `acquire`, `enqueue`, `accept` explain active-query coalescing and retirement under one monitor.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `certifyQuery` advances checkpoint and coverage only after the terminal marker.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `negotiatedThread` exposes passive host-qualified snapshots without acquiring a destination.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueriesTest.kt`: controlled terminal responses and real query-controller construction.
- `docs/recorded-tests.md`: exact request counts, observation checkpoints, temporary prompts and independent peer progress remain mandatory.
- `../pyrycode/docs/protocol-mobile.md`: Thread query messages define separate batch and terminal coverage shapes; read in the sibling product checkout.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: host-qualified destination ownership and attention workers.
- `docs/knowledge/features/development-verification.md`: focused native evidence and dispatcher gate ownership.

## Context

The attention worker opens B's initial query before navigation. Tapping while it is active coalesces acquisition; tapping after certification produces the second captured query. Strict replay correctly holds later phone frames when that query is missing. This is one behaviour slice: synchronize the scenario's acquisition with initial certification, with its regression proof. No production contract change or decision record is needed.

Estimate: approximately 400 written lines across the plan, one shared test helper, its controlled JVM tests and the existing native scenario. No exported production types, signature migrations or new production error branches; three acceptance criteria. This is below the ticket's ~700-line estimate and all sizing limits.

Overlaps: remote feature branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139, #2175, #2246, #2311, #2347 and #2349 touch the scenario file. The local insertion is confined to this attention scenario; #2349 changes permission cleanup and #2347 changes restart, neither is a dependency.

## Design

Add `awaitInitialAttentionQuery` under shared test e2e helpers. It passively collects the exact host/conversation's authoritative snapshot until a nonempty epoch, positive certified checkpoint and certified window exist without repair/reset flags. The scenario calls it after the waiting pill is displayed and before its original tap, bounded by the existing thread timeout. B is freshly created and has never been acquired on the phone, so that certificate belongs to its initial attention query. No peer state, batch-only content or elapsed delay establishes readiness.

The production query controller certifies and retires its active query under the same monitor that acquisition takes. Observing certification therefore permits navigation to wait for retirement through that existing lock. No extra open, acquire or refresh call is introduced. The original tap remains the only destination acquisition.

The tests drive the real `AuthoritativeThreadQueries`, store and strict tape with small controlled wire fixtures. A held terminal response separates initial send, batch receipt, certification and navigation. Both acquisition-intent timings must issue the exact initial and checkpoint-13 requests. An explicit unsynchronized control demonstrates the old coalescing/missing-query failure.

## State and concurrency model

The helper owns no scope or retained state; its suspending collection is cancelled by its caller's timeout or cancellation. Native replay still uses real background query processing. The controlled tests use `runTest` and its scheduler, with no sleeps. Production jobs, locks and connection lifetimes are unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Navigation requested while initial query is active | `activeInitialQueryDefersAcquisitionUntilCertified` holds the terminal response, verifies no acquisition, then requires both exact sends. |
| Navigation requested after certification | `completedInitialQueryAcquiresWithoutAnotherWait` requires the checkpoint query immediately. |
| Batch received without terminal marker | Active-query test requires the helper to remain suspended. |
| Unfenced acquisition coalesces | `unfencedAcquisitionLeavesRequiredQueryAndPhoneCompletionPending` demonstrates incomplete replay despite independent peer progress. |
| Missing, duplicate, wrong-owner, conversation, epoch or checkpoint request | Strict-tape rejection probes preserve complete matching and failure. |
| Observation boundary or connection generation changes | Tape probes retain checkpoint and reconnect fences; existing `RecordedSuiteTapeTest` and `AuthoritativeThreadQueriesTest` cover admission. |
| Caller cancellation before certification | Helper cancellation test verifies no acquisition or extra request. |

## Error handling

Timeout or cancellation fails the scenario; it cannot fall back to tapping early. Tape mismatches stay sticky failures. No query or frame becomes optional, and no recording is changed.

## Testing strategy

Write the controlled JVM regressions first, observe the active-query failure with an unfenced helper, then implement the passive wait. Run the new class plus `RecordedSuiteTapeTest` and `AuthoritativeThreadQueriesTest`. Run lint, debug assembly, instrumented Kotlin compilation and forced formatting checks. The native method remains device-only because it drives the real activity, input and background connection processing. Run exactly `de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished` on macOS; retain raw reports, logcat, revision and counted outcomes for every attempt. Complete configured UI acceptance belongs to the dispatcher before verifier PASS. Linux is outside scope. No live daemon or Claude capture is required because the request contract and fixture stay unchanged.

## Open Questions

None. If focused evidence requires production admission or a different capture contract, stop implementation and return that causal scope for reassessment.

## Revisions

- 2026-10-21: The negative reply-attribution/generation probe uses a standalone real query controller: rejecting an old generation legitimately sends a fresh zero-checkpoint repair, which cannot belong to the successful two-query tape. Exact tape matching remains unchanged. The checkpoint probe asserts reply fencing; read-only request matching itself may precede that observation.
