# Attention expiry reliability (#2490)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttention.kt`: `observeThreadAttention` publishes before launching expiry; `rememberThreadAttention` scopes collection to RESUMED.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention` republishes current authoritative completion facts on certification changes.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `publishAuthoritativeCompletions` retains and recertifies completion identities.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeCompletion.kt`: `key` identifies epoch and terminal item independently of certification revision.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished` retains the original native scenario and explicit clock advance.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttentionTest.kt`: exact expiry, replacement, suppression and cancellation contracts.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttentionNavigationTest.kt`: destination and background lifecycle ownership.
- `docs/recorded-tests.md`, Attention destination acquisition: preserve #2350's acquisition fence and strict replay without adding queries or changing recordings.
- `docs/knowledge/features/thread-screen.md` and `thread-top-overlay.md`: Finished lasts five seconds; Waiting wins and navigation never answers a prompt.
- `docs/specs/architecture/1793-attention-pill-live-flake-validation.md`: virtual time advancement is necessary and an earlier scoped pass does not establish sequence reliability.

## Context

The #2462 macOS gate at `4711539ab3` reached the final ten-second absence wait after Finished display and a 5,100 ms clock advance. Its retained stack and counted gate result remain historical failures. Begin with fresh isolated host/attention state and the exact method. Inspect preceding gate failures and available native logs before considering wider investigation. No decision record is needed.

One deliverable: a causally demonstrated expiry repair with its controlled regression and unchanged strict recorded scenario. Forecast: at most 450 written lines, one production file, no exported declarations or signature migrations, three acceptance criteria and fewer than ten decision branches. In-flight branches share `InteractiveStreamE2ETest` in other methods; none changes this method or `ThreadAttention`. Keep any edits local. Overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119, #2138, #2139, #2175, #2311, #2351, #2462.

## Design

Separate three observations: completion publication, timer registration and actual removal. Re-certification can republish the same completion identity; a genuinely new completion must continue to renew the five-second lifetime. Controlled tests will distinguish those events and a late timer registration before selecting the minimal repair in `observeThreadAttention`. Keep host/conversation-qualified identity and lifecycle-local ownership. Do not change recording matching, causal delivery, observation fences or deadlines. Any native diagnostic observer must be passive, content-free and scoped with cleanup.

## State and concurrency model

The cold attention flow and its children remain owned by the RESUMED destination's `produceState` coroutine. Cancellation removes its timer and subscription. Pure regressions use `runTest` with controlled scheduling; native replay uses the existing Compose clock and actual background repository work. No new dispatcher, background job or application dependency is introduced.

## State transitions and identity reuse

| Event | Required check |
| --- | --- |
| Same completion is recertified before or after expiry | `recertifyingSameCompletionCannotRenewOrResurrectFinished` distinguishes replay from new completion and proves actual null removal. |
| New completion on the same target | Existing `anotherFinishForSameConversation_restartsExpiry` retains renewal. |
| Colliding completion key on another host/conversation | `equalCompletionKeysOnDifferentTargetsRemainIndependent` retains separate completions on each identity axis. |
| Finished publication precedes timer scheduling | `finishedPublicationAlreadyOwnsItsScheduledExpiry` requires registration before publication, presence at 4,999 ms and removal at 5,000 ms. |
| Waiting replaces Finished or suppresses completion | Existing waiting/suppression tests and `recertifyingCompletionSuppressedByWaitingCannotReplayIt` retain precedence and no replay. |
| Navigation, Back, background and reopen | Existing `ThreadAttentionNavigationTest` and the exact recorded method retain cancellation, fresh waiting and no old finish. |

## Error handling

No new I/O or error outcomes. Failed observation, timeout or strict replay remains a failure; no retries, sleeps or relaxed query comparison. A never-expiring pill must fail the controlled expiry check.

## Testing strategy

Write and run the causal regression red before changing production. Retain raw reports and the source diff. Run `ThreadAttentionTest`, `ThreadAttentionNoticeTest`, `ThreadAttentionNavigationTest` and `InitialAttentionQueryTest` with counted JVM results. Run three consecutive separate focused invocations of `RecordedMobileSuiteTest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished` after repair on macOS, retaining every attempt, revision, raw XML and phone logs. Native coverage remains device-only because it drives actual background replay, application graph and device navigation. No fresh recording or live Claude turn is needed for the unchanged contract.

Run lint, assembleDebug, instrumented Kotlin compilation and forced formatting checks. Merge current main, push, then run final assembly and `scripts/pre-verify.py --gradle`. The dispatcher owns the complete configured UI gate; its named-method pass remains pending until that gate supplies it. Linux is outside verification scope.

## Open Questions

- Does the failing expiry reflect late timer registration, repeated identical completions, or both? Resolve with controlled scheduling and native content-free evidence; record the selected repair under Revisions.
- Are fresh isolated checks sufficient to explain the gate failure? Retain the observed preceding-test state and request a wider sequence investigation only if focused proof leaves a dependency unresolved.

## Revisions

- 2026-10-23: controlled `recertifyingSameCompletionCannotRenewOrResurrectFinished` and `finishedPublicationAlreadyOwnsItsScheduledExpiry` both fail against unchanged production (17 executed, 15 passed, 2 failed, zero skipped). The former reproduces renewal at the original five-second boundary. The latter observes publication with a receiver running before the queued expiry child: registration is still false. Its original report includes that assertion as a suppressed failure after the cancelled collector also fails final removal; the refined probe records registration at publication and asserts it directly outside the collector.
- The final pre-repair run executes 18 tests: 15 passed, 3 failed, zero errors/skips. It directly fails registration, duplicate renewal and `recertifyingCompletionSuppressedByWaitingCannotReplayIt`. Retained raw XML, unchanged production source and test snapshot: `logs/evidence/2490/controlled-direct-red/` in the agents checkout.
- Fresh unchanged-production native runs each pass once (1 executed/passed, zero failed/skipped). The passive diagnostic confirms that the second publication is the same completion key, not a second turn: `repeated=false` followed 44 ms later by `repeated=true`, before the successful clock advance. `HostConversationSource.launchAttention` republishes the retained completion list when its certificate changes; `AuthoritativeCompletion.key` remains unchanged. This explains how a later certificate can renew the timer after a one-shot clock advance even though the rendered value is unchanged.
- Selected repair: `observeThreadAttention` remembers host/conversation/key pairs within its existing collection, after current/muted filtering and before Waiting suppression. Repeated identities neither renew nor resurrect Finished; a new terminal identity still replaces it for five seconds. Suppressed identities remain discarded when Waiting clears. Start the expiry child undispatched and publish only after its first suspension registers the delay. Static scheduled, duplicate-suppressed and expired events distinguish registration, renewal rejection and removal without logging identities.
- The historical #2462 final-wait stack and reports are retained under the private evidence root `logs/evidence/2490/baseline/`. The bundle is `18ddeeede50af3312b066b5571ec64d526f549729171adc788b23a62599b240c`; the original phone log and host-load samples were not present in that bundle or the removed gate worktree. Its five preceding cases in report order passed (rename, host system prompt, reconnect slash commands, markdown, remembered model). Earlier unrelated offscreen/background-agent failures remain tracked separately. Fresh single-host attention state and the controlled regressions reproduce the failure mechanisms without preceding state; no wider investigation is needed on current evidence. Raw controlled red and native diagnostic reports, source patches, revisions and verified manifests are retained under `logs/evidence/2490/{controlled-red,diagnostic-before-repair}/` in the agents checkout. The baseline native run exercised `45b86ff05dd71c8904a006c8b8c8a7c6489616c7`; its `revision.txt` records the later plan-only retention head, whose product/test scenario was unchanged when that run began.

Final focused validation on macOS: 18 expiry, 10 presentation, 4 lifecycle/navigation and 11 acquisition tests executed/passed, all with zero failures/errors/skips. Three consecutive exact recorded-method invocations on pushed `e80f952ad9e8c20c3dd5e8e795ebd8f706b8527f` each executed/passed one case without failures/errors/skips. In `native-2`, expiry occurred before the repeated completion arrived during the clock advance; duplicate suppression prevented the former rearm. The checked index `app/src/test/resources/verification/attention-expiry-2490/evidence.json` records every attempt, source revision, counts, fixture hash, raw paths and verified manifest hashes. Both investigation questions are resolved by focused evidence; the configured full UI gate remains dispatcher-owned and pending before verifier PASS.

## Rework investigation (2026-10-23)

Verifier finding 1 concerns the final Y model-selection wait, a separate behaviour already tracked by #2351 and repair #2356. The named scenario and its saved-model assertion are unchanged by this branch. Start with the exact method, preserve every failure, and compare unchanged main production in this same worktree before assigning inheritance. The first fresh branch invocation on `5b66b8422a6c242877b86f31c43de1e99b221a60` executes one case and fails only in strict teardown (close before final checkpoint), not the selected Model wait; raw reports and logcat are retained at `logs/evidence/2490/rework-model-branch-1/` in the agents checkout.

Temporarily apply the inverse attention production patch as an uncommitted diagnostic control, verify all production sources equal `origin/main`, and run only the exact recorded model method. Preserve revision, source patch, production hashes, XML and logcat, then reapply the expiry repair without changing it. This is evidence gathering, not a proposed product change. Neither a focused pass nor a distinct teardown failure resolves the verifier's selection finding. If the comparison leaves the failure's ownership unresolved, request the smallest useful recovery investigation through the shared route and keep model repair and complete acceptance pending. No wider suite is builder-owned. This rework adds only scoped evidence and plan entries; the original expiry behaviour, strict replay and deadlines stay unchanged.

## Documentation handoff

Pending for documentation stage: `docs/recorded-tests.md`, Attention destination acquisition (#2350), update the expiry paragraph to explain completion identity deduplication and registration before publication; preserve the separate acquisition fence and unchanged recording contract. `docs/knowledge/features/thread-top-overlay.md`, attention pill/testing, record that only a new terminal identity renews the existing five-second lifetime and link the counted evidence.
