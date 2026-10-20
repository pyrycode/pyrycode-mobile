# Interrupted retrieval replay restart

## Files read

- `docs/recorded-tests.md`: strict payload, close, observation and capture contracts; #2219 covers a later cut, not graph restart.
- `docs/knowledge/features/attachment-retrieval.md`: the cut must occur at the real retrieval log boundary, with Retry and file hashes retained.
- `docs/knowledge/features/relay-reconnect-supervisor.md`: graph disposal closes the current supervisor.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `restartApp` destroys the activity before rebuilding; the retrieval scenario retains its original assertions.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` fences an explicit cut, while `beforePhoneTeardown` fences final activity teardown.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` checks required work and queued delivery while jobs remain alive.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `prepareClose`, `closeReady` and `phoneClosesBeforeTeardown` preserve connection ownership and strict work.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteConnectionsTest.kt`: current close regressions and virtual-time helpers.

## Design source

N/A: test lifecycle repair only; no visual or product behaviour change.

## Context

The original macOS main sweep at `77b5717d6d3a1dba9f1a92a251162e7f9b3770e2`
first rejects step 16, phone generation 0 closing with `thread_catch_up` still required.
Its cause stack is `restartApp` → `rebuildGraph` → registry disposal → supervisor close.
The later 30-second reconnect and cleanup failures propagate that sticky mismatch.
This predates the retrieval cut and differs from both #2219 races.
Fresh focused unchanged-main replay passed 1/1 with no skips, exit 0 (4.206 seconds),
so this is an ordering defect, not an unconditional retrieval failure.
Retain the original failure and focused result separately; focused success proves no suite independence.
No decision record is needed.

## Design

Add `SuiteConnections.beforePhoneRestart`: snapshot currently unclosed phone transport
owners under the hub monitor, then call existing `beforeClose` for each outside it.
Do not select peer owners, already closed generations or future generations.
Expose this through `RecordedSuiteNative.beforePhoneRestart`, replay-only, and invoke it
from `restartApp` before Activity destruction. Live capture and ordinary tests are no-ops.
Reuse strict close readiness, payload matching, causal delivery and existing deadlines.
Do not synthesize requests, relax tape consumption, change the recording or change production.
The final-teardown fence remains separate because it intentionally includes replacements
before scenario completion; waiting for those during restart would deadlock relaunch.

All scenario assertions remain: peer upload, empty thread cache on the new graph, actual
retrieval-triggered cut, unnamed failed Retry row, reconnect, one ready row, open/save hashes.
One behaviour slice: restart preserves required replay work until the old graph can close.
Forecast: under 400 written lines, four code/test files and this plan, no new types,
one new consumer call, three acceptance criteria, no state-machine reject branches.
Remote overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879,
#1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098,
#2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139,
#2175, #2246 and #2311 touch the large scenario file; #2311 also wraps native factories.
Their hunks do not restructure `restartApp` or the close fences; keep edits additive and local.

## State and concurrency model

No new jobs or flows. The instrumentation caller suspends through `runBlocking` outside
Android main while existing graph workers and the delivery scope advance required work.
Only owner selection holds the hub monitor; waits release it. Existing 30-second bounds
and failure propagation remain. The snapshot deliberately excludes a graph not yet created.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Restart while a current phone query is pending | `phoneRestartWaitsForCurrentQueriesWithoutWaitingForFuturePhonesOrPeers` |
| Relaunch and second restart with a new generation | same test, second fence after replacement creation |
| Current query never arrives | `phoneRestartCannotOmitARequiredQuery` |
| Wrong query checkpoint | `phoneRestartStillRejectsAChangedQuery` |
| Real graph destruction, empty cache, cut, Retry, reconnect | original `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` native method |

## Error handling

Missing required work times out with the existing bound and remains incomplete.
Changed payloads retain the sticky strict tape failure; readiness cannot clear it.
No new UI error, I/O outcome, logging payload or production failure mode.

## Testing strategy

Write controlled virtual-time regressions first, demonstrate that an unfenced restart
releases early, then implement the wait. Cover current vs future/peer owners, successive
generations, missing and changed required queries, queued replies and full consumption.
Run `SuiteConnectionsTest`, `RecordedSuiteTapeTest` and supervisor close coverage.
Run the exact recorded native method after repair; run the affected recorded class because
the restart helper is shared. Native coverage needs the real Activity/Koin teardown and
app-private cache, unavailable in the pure JVM test. macOS only; Linux is outside scope.
Run lint, assembly, instrumented Kotlin compilation, formatting and final pre-verify.
Configured complete UI acceptance remains owned by dispatcher and required before verifier PASS.
If focused evidence and the original sequence cannot explain independence, retain failure
bundles and request the narrowest necessary wider investigation through the shared route.

## Open Questions

None: original first-failure evidence identifies the missing restart fence. Full configured
gate independence remains acceptance evidence, not a reason to weaken strict matching.

## Security review

**Verdict:** PASS

- [Trust boundaries] `beforePhoneRestart` selects only locally registered phone owners; full recorded frame validation remains in `RecordedSuiteTape.send`.
- [Tokens] No credentials are created, read or logged by the fence. Existing replay credentials remain isolated.
- [Files and storage] No new storage path or content handling; existing app-private cache clearing and open/save digest assertions stay intact. Raw diagnostics stay private outside Git.
- [Android attack surface] Test-only helpers add no manifest component, intent, provider or exported entry point.
- [Cryptography] No crypto changes. Replay remains offline and makes no encryption claim.
- [Network and I/O] Missing queries must fail under existing deadlines. No extra request, deadline extension, transport or recording replacement.
- [Errors and logs] No payload or message logging added; retained public evidence contains structural counts and hashes only.
- [Concurrency] Snapshot under the hub monitor, suspend outside it, before Activity destruction. Regressions must reject early completion and avoid waiting on future generations or independent peers.
- [Threat model] Malicious relay, token theft, hostile daemon parsing and UI leakage are unchanged production boundaries. This offline harness repair grants no trust or coverage for those threats; strict malformed/request rejection remains mandatory.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-21
