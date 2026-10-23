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

One deliverable: a causally demonstrated expiry repair with its controlled regression and unchanged strict recorded scenario. Forecast: at most 450 written lines, one production file, no exported declarations or signature migrations, three acceptance criteria and fewer than ten decision branches. In-flight branches share `InteractiveStreamE2ETest` in other methods; none changes this method or `ThreadAttention`. Keep any edits local.

## Design

Separate three observations: completion publication, timer registration and actual removal. Re-certification can republish the same completion identity; a genuinely new completion must continue to renew the five-second lifetime. Controlled tests will distinguish those events and a late timer registration before selecting the minimal repair in `observeThreadAttention`. Keep host/conversation-qualified identity and lifecycle-local ownership. Do not change recording matching, causal delivery, observation fences or deadlines. Any native diagnostic observer must be passive, content-free and scoped with cleanup.

## State and concurrency model

The cold attention flow and its children remain owned by the RESUMED destination's `produceState` coroutine. Cancellation removes its timer and subscription. Pure regressions use `runTest` with controlled scheduling; native replay uses the existing Compose clock and actual background repository work. No new dispatcher, background job or application dependency is introduced.

## State transitions and identity reuse

| Event | Required check |
| --- | --- |
| Same completion is recertified before or after expiry | New controlled `ThreadAttentionTest` case distinguishes replay from new completion and proves actual null removal. |
| New completion on the same target | Existing `anotherFinishForSameConversation_restartsExpiry` retains renewal. |
| Colliding completion key on another host/conversation | New controlled identity test retains separate completions. |
| Finished publication precedes timer scheduling | Controlled scheduler probe determines whether registration can lag the scenario's clock advance. |
| Waiting replaces Finished or suppresses completion | Existing waiting/suppression tests retain precedence and no replay. |
| Navigation, Back, background and reopen | Existing `ThreadAttentionNavigationTest` and the exact recorded method retain cancellation, fresh waiting and no old finish. |

## Error handling

No new I/O or error outcomes. Failed observation, timeout or strict replay remains a failure; no retries, sleeps or relaxed query comparison. A never-expiring pill must fail the controlled expiry check.

## Testing strategy

Write and run the causal regression red before changing production. Retain raw reports and the source diff. Run `ThreadAttentionTest`, `ThreadAttentionNoticeTest`, `ThreadAttentionNavigationTest` and `InitialAttentionQueryTest` with counted JVM results. Run three consecutive separate focused invocations of `RecordedMobileSuiteTest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished` after repair on macOS, retaining every attempt, revision, raw XML and phone logs. Native coverage remains device-only because it drives actual background replay, application graph and device navigation. No fresh recording or live Claude turn is needed for the unchanged contract.

Run lint, assembleDebug, instrumented Kotlin compilation and forced formatting checks. Merge current main, push, then run final assembly and `scripts/pre-verify.py --gradle`. The dispatcher owns the complete configured UI gate; its named-method pass remains pending until that gate supplies it. Linux is outside verification scope.

## Open Questions

- Does the failing expiry reflect late timer registration, repeated identical completions, or both? Resolve with controlled scheduling and native content-free evidence; record the selected repair under Revisions.
- Are fresh isolated checks sufficient to explain the gate failure? Retain the observed preceding-test state and request a wider sequence investigation only if focused proof leaves a dependency unresolved.
