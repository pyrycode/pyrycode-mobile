# Durable-gap failure isolation (#2514)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: durable-gap scenario, `severLink`, `restoreLink` and the unchanged ping proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`: `missPages` restarts the daemon; `catchUpAuthoritative` ends its probe only once entered.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeHistoryProbe.kt`: idempotent `end` permits outer cleanup before or after catch-up.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt`: `close` cancels supervision; `connect` starts it idempotently.
- `scripts/e2e-emulator.sh`: default ping selector and owned isolated fixture lifetime.
- `scripts/test_e2e_emulator_gradle.py`: selector regression beside existing normal-pair coverage.
- `docs/knowledge/features/development-verification.md` and `development-verification-test-scheduling.md`: focused execution, retained failures and counted evidence boundaries.
- `docs/e2e-interactive-stream.md`, “Scripted ping durable-gap proof (#1842)”: negotiated producer migration is merged; preserve every phone and producer assertion.

## Context

At main `bee371a57`, exceptions between deliberately stopping phone supervision and entering catch-up leave the phone offline. Peer-only cleanup does not resume supervision. This is one test failure-isolation repair, with its regressions, and no product reconnect change. No decision record is needed.

The ~450-line estimate remains appropriate: approximately 500 written lines including this plan, a one-consumer test fixture, controlled tests, native injection and evidence summaries; no production types or consumer migration. Four acceptance criteria and fewer than ten failure branches remain within the limits.

In-flight #1766, #2091, #2104, #2175, #2462 and #2511 share the scenario file but change other blocks; keep this diff local. #2513's producer migration has landed, removing the edit-coordination wait.

## Design

Add internal `DurableGapIsolation` under shared test sources, consumed only by the scripted durable-gap scenario and its regression tests. `sever(disconnect)` arms responsibility before invoking the disconnect. `run` preserves the exact body throwable, then on an exceptional exit after arming attempts the supplied restoration once. The scenario supplies the existing `restoreLink`, including its existing 30000 ms reconnect bound. This failure-path attempt cannot turn the original failure green. Successful execution does no extra reconnect and retains the original offline interval and catch-up proof.

Peer close and probe end run independently on every exit, even if restoration or another cleanup fails. The first failure remains primary, and later failures attach directly as suppressed exceptions in execution order. A cleanup failure on an otherwise successful run becomes the primary failure. Avoid self-suppression if callbacks throw the same instance.

Factor the scenario body into a private callable with an optional offline-arm injection. The normal annotated method calls it with no injection. A separate annotated native regression injects a unique assertion inside `missPages` after the first offline post batch, catches only that exact assertion, requires no secondary failures, and proves the same Koin graph, Activity and supervisor remain. Require a fresh authenticated repository compared with the pre-sever source, then navigate to the seeded channel and run the unchanged ping method on this same graph. Static logs identify injection, primary preservation, cleanup, fresh connection and subsequent ping completion without authored content or pairing material.

An explicit `DURABLE_GAP_ISOLATION_PROOF=1` flag selects only that native regression when running managed `scripted ping`. The default two-method selector is unchanged. Selector tests prove that isolation never silently joins the normal pair.

## State and concurrency model

The fixture is synchronous on the instrumentation thread and owns no coroutine jobs. Existing callbacks retain their bounded `runBlocking` operations. Restoration uses existing supervision rather than creating another connection scope. No production lifecycle or dispatcher changes.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Disconnect throws after starting teardown | `disconnectFailureStillRestoresAndCleans` |
| Failure immediately after sever | `failureAfterSeverRestoresAndPreservesPrimary` |
| Offline production or daemon restart throws | `offlineAndRestartFailuresRestoreBeforeCleanup` |
| Catch-up throws before or after normal reconnect | `catchUpFailureRestoresWithoutMaskingPrimary` |
| Normal reconnect and failure-path restoration both fail | `restorationAndCleanupFailuresAreSuppressedInOrder` |
| Successful offline interval and normal restoration | `successKeepsOfflineWindowAndDoesNotRestoreAgain` |
| Failure before deliberate disconnect | `failureBeforeSeverOnlyCleans` |
| Cleanup alone fails or throws the same throwable | `cleanupFailureRemainsVisible` and `sameThrowableDoesNotPreventRemainingCleanup` |
| Native offline failure followed by ping on original app graph | `interactiveTurn_durableGapFailure_restoresBeforeFollowingPing` |

No fixture reuse across scenarios: construct a fresh instance for each durable-gap arm.

## Error handling

Catch `Throwable` at the test cleanup boundary so assertions and timeout failures receive the same restoration guarantees. Preserve exact throwable identity and ordered suppressed failures. Do not retry production, restart the app, extend deadlines or weaken existing assertions. A real daemon restart failure may still prevent bounded phone restoration; it stays failed with both causes retained.

## Testing strategy

Write controlled regressions first against a peer-only legacy fixture and observe red, then add restoration and independent cleanup. Run `DurableGapIsolationTest` through `testDebugUnitTest`. Add and run the selector regression before changing the shell selector.

The focused native injection requires actual supervision, encrypted transport and native app graph identity, so belongs under `androidTest`. Run it separately through the managed ping launcher, retain its pre-repair failure and post-repair counted result. Run the unchanged managed Linux `scripted ping` pair and require 2 executed/passed, zero failures/errors/skips. Preserve 90000 ms reply bounds, original exact reply, identity, chronology, paging, cache, missed-ring and reconnect checks. Retain raw XML/logs, source and effective binary/APK identities and cleanup receipts in durable evidence with hashes; publish content-free summaries under test assets. Dispatcher configured acceptance remains pending for verifier.

Run affected JVM/Python checks, lint, assembly, instrumented Kotlin compilation, formatting and the final `pre-verify.py --gradle` after the last merge of main. No Claude turns or second-OS checks.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Scripted ping durable-gap proof (#1842),” with failure-path restoration, preserved primary failures, counted isolation proof and its boundary against normal configured acceptance. Preserve historical failures.
