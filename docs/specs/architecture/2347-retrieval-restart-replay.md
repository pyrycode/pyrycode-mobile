# Interrupted retrieval replay restart

## Files read

- `docs/recorded-tests.md`: strict payload, close, observation and capture contracts; #2219 covers a later cut, not graph restart.
- `docs/knowledge/features/attachment-retrieval.md`: the cut must occur at the real retrieval log boundary, with Retry and file hashes retained.
- `docs/knowledge/features/relay-reconnect-supervisor.md`: graph disposal closes the current supervisor.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `restartApp` destroys the activity before rebuilding; the retrieval scenario retains its original assertions.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` fences an explicit cut, while `beforePhoneTeardown` fences final activity teardown.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `rebuildGraph` removes the old lifecycle observer and disposes the registry; the Application keeps its single preference DataStore.
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

## Investigation evidence

The retained tape distinguishes producer completion from phone catch-up: peer `turn_end`
precedes the phone's version-0 query, its two replies establishing version 20, then the
required version-20 query and final reply before generation 0 closes. Seeing the peer
finish therefore cannot prove the phone's required pre-restart work has completed.
The original progress names that second query as unconsumed. `prepareTails` cannot
omit it using the earlier version-0 request; its same-checkpoint proof is absent.

The original shard's five preceding recorded cases were thinking-spinner, permission
answer routing, attention dot, background task and scanner confirmation. Recorded graph
cleanup restores pairings/preferences and rebuilds the ordinary graph between cases;
the missing fence is inside this scenario's explicit restart, bypassing final teardown.
Original sweep used two managed shards; the focused reproduction used one. No claim
that host load caused the race, or that a focused pass proves suite independence.

Controlled unfenced baseline: the new restart entry point initially returned immediately,
matching the old absence of a pre-restart wait. `SuiteConnectionsTest` executed 26,
passed 23, failed all three new restart regressions, skipped 0, exit 1. Its first
failure is early release before required catch-up, not a compiler or environment error.
The strengthened baseline actually closes the old transport after the unfenced wait;
it reproduces `phone/host/0:close, expected phone/host/0:thread_catch_up` with the
version-20 query unconsumed (26 executed, 23 passed, 3 failed, 0 skipped, exit 1).
The implemented fence passes 26/26; strict tape 44/44 and supervisor 38/38 also pass,
all with zero failures/errors/skips and exit 0.
Raw failed and passing XML, baseline sources, original sweep logs and focused baseline
phone diagnostics are retained privately at
`$AGENTS_REPO_PATH/logs/evidence/2347/builder-20261021-restart/`.

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
