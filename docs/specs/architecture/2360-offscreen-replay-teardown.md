# Preserve offscreen replay work before activity teardown (#2360)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the offscreen scenario retains exact completion, cache absence, recovery, uniqueness and ordering assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedActivityRule.kt`: the foreground fence runs after successful assertions, before activity teardown.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforePhoneTeardown` delegates to the replay hub; `finish` checks complete consumption.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforePhoneTeardown`, `beforeClose`, delivery serialization and test-end readiness.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `phoneClosesBeforeTeardown` excludes the post-test-end close; released work and checkpoint fences govern admission.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteConnectionsTest.kt`, `RecordedSuiteTapeTest.kt` and `RecordedActivityRuleTest.kt`: existing pre-close, strict admission and registry-disposal boundaries; new `RecordedPhoneTeardownTest` adds focused boundary probes.
- `docs/recorded-tests.md`, What replay proves: complete requests, owner correlation and causal observation remain strict.
- `docs/knowledge/features/development-verification-gates.md`, Scenario cleanup before activity teardown: keep request-producing jobs alive until their required prefix arrives.

## Context

The original #2349 macOS gate first recorded a sticky lifecycle mismatch: phone generation 1 closed from delayed `ProcessLifecycleOwner` stop with request 13/checkpoint 28 unconsumed. The passing same-worktree rerun is separate evidence. Cleanup then reports the sticky failure; it is not a second cause. The foreground fence currently visits only closes preceding test-end. This scenario instead records its final phone close after test-end. Destroying the activity can cancel a still-pending acquisition/query, leaving test-end waiting while delayed process stop closes the socket. No preceding-test or machine-load cause is established.

One deliverable: preserve this scenario's required replay work across the activity teardown scheduling boundary, with its regression. Forecast: about 450 written lines including probes, evidence and this plan; no production changes, exported production types, signature migrations or new rejection branches. Three acceptance criteria. No decision record needed.

Overlaps: #2325 edits tape query admission; #2347 adds restart fencing; #2348 adds downstream consumption to explicit close. Inspected their diffs: edits here are local to final activity teardown, do not require those branches, and preserve their seams.

## Design

Keep the existing pre-test-end close fence. Add a tape predicate for required phone request/reply work before the next test-end, bounded by the next observation checkpoint. The hub waits for that prefix while the activity remains alive, then drains its scheduled deliveries. It neither sends test-end early nor waits for test-end, physical phone close, inbound end or registry disposal. The existing outer cleanup still consumes test-end in its original order.

The predicate observes release state only, using the existing deferred read policy. It does not prepare optional query tails, consume missing requests, synthesize replies or alter payload/identity matching. Required duplicate queries remain required. Non-phone work and post-boundary work remain owned by their existing controls/fences.

## State and concurrency model

No production state or jobs change. Replay continues on its scoped coroutine dispatcher; the foreground wait uses the existing 30-second bound and 10 ms suspension cadence. Serialized delivery barriers establish queued reply delivery before returning. Failure remains sticky. Cancellation or deadline fails the wait and physical cleanup still runs.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Final query delayed until after scenario assertions; activity teardown cancels producer; delayed stop follows | `SuiteConnectionsTest.phoneTeardownRetainsRequiredWorkBeforeTestEnd` controls this interleaving, first red before repair. |
| Request accepted but reply delivery remains queued | Same regression holds the original scheduled reply and requires delivery before fence completion. |
| Required request never arrives | `RecordedPhoneTeardownTest.missingWorkAndAnEarlyCloseStillFail` checks incomplete readiness and strict early close rejection. |
| Foreign connection/host/conversation/epoch/checkpoint or duplicate request | `RecordedPhoneTeardownTest.foreignWorkCannotReleaseTheRequiredQuery` and `aRequiredRepeatedQueryCannotBeOmittedOrSatisfiedByADuplicateRequestId` reject mismatches and cannot release the fence. |
| Observation checkpoint precedes cleanup; disposal follows | Existing `phoneTeardownDoesNotWaitForRegistryDisposalAfterTheCompletionCheckpoint` and new checkpoint-boundary probe. |
| Repeated native execution, reconnect and original row identities | Five consecutive executions of the unchanged recorded offscreen method on macOS. |

## Error handling

No new admission exception or recovery path. Missing work times out under the existing bound. Unexpected work and premature lifecycle close continue to fail strict tape matching. Preserve all failed attempts and raw diagnostics.

## Testing strategy

Write and run the controlled hub regression before changing the player. Run affected `SuiteConnectionsTest`, `RecordedSuiteTapeTest` and `RecordedActivityRuleTest` classes, including invariant probes. Run the unchanged recorded offscreen method five consecutive times through the focused managed UI launcher, retaining counted XML, logs and revisions. Its existing device-only coverage exercises actual lifecycle/background scheduling and native assertions. Run lint, assembleDebug, instrumented Kotlin compilation, formatting and final pre-verify after merging main. The dispatcher owns complete suites and must execute this method in its configured UI gate before verifier PASS. Linux is outside this macOS ticket's verification scope.

## Open Questions

None. The controlled regression reproduced the original lifecycle mismatch before repair (one executed, one failed). It explicitly withholds request 13/checkpoint 28 until the fence starts, simulates delayed process stop on premature return, and keeps the queued reply distinct from request acceptance. The repaired four focused classes passed 75 tests, and all five consecutive focused native executions passed on `31ef54b52d59db58939deafeff639042b1066ea7`, with zero failures/errors/skips. The five probes also passed again after formatting on that committed source. Raw failed and passing results, source revisions and hashes are indexed in `app/src/test/resources/verification/recorded-teardown-2360/evidence.json`. Dispatcher configured-gate acceptance remains pending.
