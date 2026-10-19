## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_peerStartedTurn_continuesOnPhone`, `currentThread`, `awaitRetainedReply`, and `scrollListTo` supply the existing scenario, repository ownership checks, and scrolling facilities.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `userEchoCount` distinguishes delivered user echoes from rendered semantics.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-testing.md`: lazy thread rows must be checked while composed; repository facts establish retention independently of the viewport.
- `docs/recorded-tests.md`: reuse original native assertions and captured inputs without changing strict request or causal validation.

## Change

Repair only the peer-started turn's reopen verification. The existing wait searches composed semantics without revealing the prompt, and then counts that prompt after `awaitRetainedReply` has scrolled elsewhere. Read current admitted thread evidence to distinguish retention from composition, require exactly one delivered peer user message with the original identity/content, reveal its row through `scrollListTo` within the existing bounded wait, and assert its displayed count before scrolling to the retained reply. Keep `awaitRetainedReply`'s exact completed family, ownership, identity, and content checks. Preserve the peer send, initial completion observation, leave/reopen path, recordings, timeouts, and production code. Add content-free diagnostics for retention and composition so the focused failure and repair explain the cause.

One independently useful deliverable: reliable reopened peer-turn verification. Forecast: under 150 written lines including plan/evidence, zero new exported types, one scenario consumer, three acceptance criteria, no new state-machine branches. This is within the refiner's approximately 220-line estimate. No documentation handoff is required.

Remote branch scan found shared-file changes on #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139, #2175 and #2311. Their hunks address other scenarios/helpers; #2311's reply-observation migration is already on main through #2316. Keep the repair local to this scenario.

## Testing strategy

On Linux, run the existing named `RecordedMobileSuiteTest#interactiveTurn_peerStartedTurn_continuesOnPhone` unchanged first and retain its failure. Diagnose current admitted user/reply retention and composed prompt count after reopen, then rerun the repaired method with exactly one executed/passed case and zero skips. The existing device test is needed for the asynchronous recording/repository integration and real native lazy-list viewport. Its original recording supplies the regression; no fresh Claude capture or new device-only method is needed. Preserve raw XML/logs, revision and hashes in persistent private evidence, and publish a content-free summary under `app/src/androidTest/assets/peer-reopen-2317/`.

Run lint, APK assembly, instrumented Kotlin compilation, Spotless apply/check, and final assembly plus `scripts/pre-verify.py --gradle` after merging main. No JVM/shared test changes are anticipated. Dispatcher complete configured gates remain pending, including execution of this method in the UI gate; focused success does not prove suite-order independence.
