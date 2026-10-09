# Reliable live reset context observation (#1869)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` and `hostRepository` use the concrete live repository and start a context watcher before Reset session.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: `observe` is an unbuffered projection of `MutableStateFlow`; `onSessionTransition` clears the reading.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `observeContextUsage` delegates directly to that projection; the serialized inbound collector applies clear and reply frames.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `runSettingsRereadEdges`, `rereadRunSettings` and `askForContextUsage` retain the reset-ended request path from #1761.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryContextUsageTest.kt`: channel-backed frame fixtures and existing clear/reply coverage.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageAskTest.kt`: reset edge and matching footer coverage.
- `app/build.gradle.kts`: shared test helpers compile for both JVM and instrumentation tests.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`: settings fallback cannot prove a repository response; equal token totals can still be fresh.
- `docs/e2e-interactive-stream.md` and `docs/specs/architecture/1761-reset-context-usage.md`: existing rung-3 proof and the observer's scheduling assumption.

## Context

The retained #1861 failure log reports a 90-second `TimeoutCancellationException` in the reset scenario; its same-tree rerun executed three methods with zero failures/errors/skips, including this method. The watcher is the scenario's 90-second coroutine deadline. Starting it undispatched only protects subscription: subsequent resumptions on Default can be delayed across the clear and reply. StateFlow then delivers only the reply, which `dropWhile { it != null }` rejects forever. Controlled scheduling must reproduce this with a real repository already holding the fresh reply, distinguishing observation failure from a missing product response.

The #1918 occurrence is different: its retained stderr locates a Compose timeout in `awaitDisplayedPingReply`, before reset. Its same-tree rerun ran two methods with zero failures/errors/skips. Do not claim the context repair diagnoses that earlier ping failure. Retained daemon logs record refusals but not successful context responses, so they alone cannot prove which clear/reply interleaving occurred.

Prerequisites #1731 / PR #1874 and #1867 / PR #1921 are merged. Overlapping branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1879, #1888 and #1900 edit other methods; keep the live edit local.

## Design

Extract the unchanged clear-then-non-null observation into `Flow<ContextUsage?>.awaitResetContextUsage` under `app/src/sharedTest/java/de/pyryco/mobile/e2e/`. After the red scheduling regression, collect inside `withContext(Dispatchers.Unconfined)` so the small, nonsuspending observer processes each concrete repository update inline, before the inbound collector applies its next frame. This test-only seam relies on the audited direct, unbuffered projection; it never performs UI work or sends a request. Keep the existing async startup, deadline, reset action, wrap-up/cleared status checks, delimiter checks, usable token window and exact reply-derived footer assertion. No product or wire change and no changed-value or timestamp freshness substitute.

Forecast: about 300 written lines in four files (plan, shared helper, JVM regression class, live method), one internal helper, one consumer update, three acceptance criteria and no new product reject branches. One deliverable: reliable observation of this reset scenario.

## State and concurrency model

The existing runBlocking parent owns the async watcher and its timeout. Only the context collection runs unconfined; filters and `first` perform no blocking or additional suspending work. Completion unregisters the observer, and parent cancellation/timeout removes it. Product scopes, refresh jobs, lifecycle cancellation and repository state remain as shipped.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Clear and reply applied without yielding to the scheduled watcher | `serializedClearAndReply_areObservedEvenWhenCallerIsDelayed` |
| A newer reading arrives without a clear | `readingWithoutClear_cannotSatisfyFreshness` |
| Clear arrives but no product response follows | `clearWithoutReply_staysPending` |
| Another conversation clears and receives a reply | `anotherConversationsReset_cannotSatisfyFreshness` |
| Repeated resets reuse the conversation id and return equal-valued readings | `repeatedResets_eachRequireTheirOwnClearAndReply` |
| Watcher cancellation | `cancelledWatcher_doesNotConsumeALaterReset` |

## Error handling

Missing clear or reply still fails under the existing deadline; neither old readings nor settings fallback satisfy the observer. The regression includes a scheduled negative control whose wait remains pending while the repository's current reading is fresh. No retry or ignored assertion is introduced.

## Testing strategy

Write the scheduling regression first against the extracted original observer and watch it fail with nonzero counts; switch the shared observer to inline collection and rerun. Drive real `RemoteConversationRepository` with a channel-backed pump, queuing transition and reply together. Run existing repository/context refresh/footer unit coverage, lint, assembleDebug, instrumentation compilation and forced Spotless checks, then the final main merge and pre-verify gate.

The existing live method remains device-only because it exercises the real daemon/Claude/relay and phone UI. This ticket assigns fresh live acceptance to the dispatcher, so skip a separate focused live rerun. The PR requests the full curated suite with `all` in `Live tests`, names the exact method in Testing, and hands off the dispatcher live gate with executed/failed/error/skipped counts and explicit method success. A focused JVM scheduling regression proves the observation race without Claude timing; no scripted reset/context twin exists because fakeclaude context replies are not established.

## Open Questions

None. If controlled scheduling does not distinguish the observer race from a missing response, reassess before changing the live test.

## Documentation handoff

- Pending for the documentation stage: update `docs/knowledge/features/thread-composer-footer-context-usage.md`, Context usage segment / Live-test trap, to describe the inline reset observer, its direct unbuffered projection requirement and unchanged clear-before-reply freshness proof.
- Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, Follow-ups to ticket / Reset-ended context refresh and Verification status, with the observer repair, retained failure/rerun distinction, controlled red/green counts and eventual full dispatcher live evidence.

## Revisions

- 2026-10-09: the original observer ran six regressions with three failures, including the queued clear/reply schedule; the other three correctly rejected missing clear, missing reply and another conversation's reset. Inline collection passed all six, alongside 30 existing repository/ViewModel context tests. Strengthened the scheduling regression with a distinct current reading to prove the repository applied the response independently of the watcher; repeated resets retain the equal-value proof. The #1918 retained stack points to the initial ping wait, not this observer, so it remains outside the diagnosed interleaving. Final written work is approximately 300 lines, within all sizing limits.
- 2026-10-10: rework finding 1 concerns the unchanged `SavedThreadFirstDrawDeviceTest.savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`. Identical isolated method commands failed on reviewed head `50f4fd083f66` (fragmented connected first open: 1072 ms) and exact merge base `60ba3f24fed1` (fragmented offline first open: 1864 ms); each executed 1, passed 0, failed 1, errors 0, skipped 0. The same device and unique persisted fixtures require no preceding suite method; the isolated branch reproduction confirms that narrower composition still exposes the failure. Production, this device class, build configuration and scripts are unchanged. This establishes an inherited gate failure, tracked by open blocker #2018; its performance root cause remains for that ticket. Keep the 1000 ms bound and negative control intact. Retained XML, phase logs and command/revision metadata are in the rework evidence folder named in PR #2020. The reset-observer design is unchanged; full scripted and live acceptance remain pending.
