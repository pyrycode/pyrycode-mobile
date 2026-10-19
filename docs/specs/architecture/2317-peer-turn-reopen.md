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

## Revisions

2026-10-19: The Linux baseline reproduced the timeout (one executed, one failed, zero skipped). Its raw device log also reveals a suppressed `RecordedSuiteTape.send` request mismatch from the reopened screen's `StableConversationRepository` subscription: `backfill_since` arrives after the recorded backfill has already been consumed. `RemoteConversationRepository.observeThreadSnapshot` sends that request before emitting rows. `withOwnedReply` introduces another cold subscription before the peer send; the fixture contains two initial backfill requests and one reopen request. The extra subscription consumes the latter early. The recording's final backfill has no correlated refusal, so `SuitePolledReads` intentionally cannot normalize this group as unsupported polling. This is a replay admission problem before rendering; offscreen composition is not established as the cause.

The proposed current-data read was tested at `2f36172fa47944defb6344127d47f784aad26705`; it immediately exposed another strict backfill mismatch (one executed, one failed, zero skipped). That experiment was reverted without changing the fixture, timeouts or original assertions. The initial plan's assumption that `currentThread`/`awaitRetainedReply` were passive reads was wrong: they collect cold flows and can issue more requests. Both runs and the inherited evidence are retained with verified hashes under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2317/builder-20261019-peer-turn/`; the checked summary is `app/src/androidTest/assets/peer-reopen-2317/investigation.txt`.

Return for refinement of the same single deliverable. Permit a source-owned passive observation of the application's existing subscription for this scenario, covering initial completion and reopened retention without issuing additional reads. Preserve strict replay, exact identity/content and duplicate checks, and the original peer/send/navigation path. Include controlled tests for no extra requests and wrong-source rejection, then the named Linux recorded method. This crosses the ticket's explicit exclusion of reply-observation contract changes, so no implementation or successful acceptance is claimed. No split, live capture, product scrolling change or relaxed normalization is proposed. Lint and final handoff checks remain unrun because there is no completed repair or PR.

2026-10-19, refined repair: the current issue explicitly admits test-only passive observation and wiring. Continue the same behaviour on the preserved branch; historical failures above remain failures. The design below supersedes the cold-read proposal.

## Context

The extra observer changes strict replay request consumption. Observe the application's existing collection instead. No production behaviour, recording, request admission or timeout changes are needed. No decision record or documentation handoff is required.

## Additional files read

- `E2eTestApplication.kt`, `tappedRelayRepositoryModule`: destination decoration already receives application snapshots.
- `UnrecognizedRowSentinel.kt`, `TappingConversationRepository`: preserve the exact snapshot and existing recorder taps.
- `AppModule.kt`, `ThreadDestinationFactory.repository`: decoration wraps the host-bound cache/facade for each destination.
- `StableConversationRepository.kt`, `runConfigurationReadings.currentConnection`: exposes the live underlying repository identity without collecting.
- `OwnedReplyObservation.kt`, `admitted` and `checkCurrent`: retain producer completion and exact delivered-family admission.
- `RetainedReplyEvidence.kt`, `retainedReply`: retain session/turn, identity and complete-content comparison.
- `DurableHistoryProbeTest.kt`: mirror pass-through subscription-count coverage.

## Design

Add a scoped passive thread probe in shared test sources. A lease observes only subscriptions created during its lifetime, stores their latest exact snapshots, and removes them when the collection ends. A snapshot reading carries an opaque subscription identity plus source-bound legacy `ThreadEvidence`. Test-application wiring resolves host ownership by matching the decorated repository's actual current connection to the registry, then captures that identity at subscription start. Later checks revalidate both captured ownership and current registry ownership; never relabel an old snapshot with a new connection.

Only the destination decorator enables this probe. Existing compatibility/global repository decoration and other helper consumers retain their contracts. An optional tap argument preserves existing decorator callers. The peer scenario arms the lease before creating its destination, uses the passive evidence for the existing `OwnedReplyObservation` admission loop, then requires a different active subscription after leave/reopen. Do not alter other `withOwnedReply` callers: provide optional passive input for this scenario while retaining the existing cold observation default.

After reopen, establish the exact original user identity/content and uniqueness in current data. Reveal and count that user row before scrolling to each retained assistant record. Revalidate current passive evidence after rendering; use `retainedReply` for the same completed family. Log only counts/booleans and lifecycle codes, never text or identities.

## State and concurrency model

The probe opens no coroutine, repository collection or request. Synchronize its lease and subscription map because the application collects on worker dispatchers while instrumentation reads. Each existing application collection owns its token and removes it in `finally`. Closing the lease clears all references and detaches the process-level probe. Existing producer observation remains scoped through `OwnedReplyProbe.observingSessions` and the peer's `finally` cleanup.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Application collection starts and emits | `PassiveThreadObservationTest` proves exact pass-through and one upstream collection/request. |
| Probe sampled repeatedly | Same test proves sampling adds no collections/requests. |
| Leave/reopen, even with equal rows | Subscription-lifetime regression rejects old token and requires a new active collection; named recorded scenario proves navigation. |
| Subscription ends or is replaced | Focused regression rejects ended/replaced readings, including equal identifiers. |
| Connection, host or conversation changes | Focused regression rejects mismatched ownership and captured connection replacement. |
| Lease closes, including failure | Cleanup regression proves no retained readings and a later lease starts empty. |
| Retained row disappears, duplicates or changes | Existing owned/retained reply regressions plus exact user retention regression reject them. |

## Error handling

Missing or stale passive readings cannot admit a reply. Existing bounded waits fail with structural diagnostics. Source changes fail currentness checks. Exceptions/cancellation propagate and collection/lease cleanup runs in `finally`; no fallback cold read is allowed for this scenario.

## Testing strategy for refined repair

Write focused shared helper regressions first and observe a red compile/missing implementation, then implement passivity/ownership. Run `PassiveThreadObservationTest`, `OwnedReplyObservationTest`, `RetainedReplyEvidenceTest` and `DurableHistoryProbeTest`, plus any affected shared rendering assertion class. Run the named recorded Linux method with existing capture bytes and native assertions. Retain raw counted XML/logs and revision privately, and append a content-free evidence summary. This existing device test exercises asynchronous real repository delivery and native lazy-list composition. Dispatcher owns full configured gates and suite-order acceptance.

Forecast: approximately 650-800 written lines including tests, wiring and this revision; at most three new internal test types, one scenario consumer, four acceptance criteria, fewer than ten rejection branches. Current overlap scan matches the earlier list; changes stay local and additive. No real dependency was found.

## Open Questions

None. The refiner resolved the observer-contract scope; implementation must preserve source ownership and strict replay.
