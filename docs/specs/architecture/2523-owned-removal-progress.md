# Owned retirement regression progress (#2523)

## Files read
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeOwnedRemovalTest.kt`: the named recovery-release test, `open`, `observe`, and `close` own real Room and scheduler lifetimes.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `removeAuthoritativeFact` serializes owned retirement and awaits durable persistence; observation cancellation joins final writes.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `captureRemoval`, `remove`, and `retainedRemovalPeerCount` bind recovery peers to uncommitted retirement.
- `docs/knowledge/features/conversation-cache-contract.md`: “Explicit owned authoritative retirement (#2256)” requires independent durable fences and exact surviving facts.
- `docs/knowledge/features/caching-conversation-repository-testing.md` and `development-verification-test-scheduling.md`: a scheduler drain does not acknowledge Room I/O; a passing rerun alone does not diagnose a timeout.
- Historical #2505 verifier output at merged source `2a5d3096ef`: named method failed after 62.110 seconds, then passed separately in 3.473 seconds. The original output has no phase evidence.

## Context
One deliverable: repair this regression's timeout with causal evidence. The admitted estimate is approximately 250 written lines, no new exported types or consumer signature changes. Preserve all 256 facts, 64 successes, failure, retry, both ordering arms and independent Room reopen assertions. No UI, protocol, daemon or documentation changes are required. Historical failures remain failures.

## Design
First execute the unchanged named method with fresh Room state. Add test-local monotonic phase diagnostics around fixture creation, seeding, observation, cancellation, removals, failure, reopen and retry; retain raw XML and source identities. Execute the affected class to inspect sequencing. Keep the original deadline during diagnosis. Select the smallest fixture/scheduling repair supported by this evidence; a deadline change requires measured continued progress and a bounded method-local allowance. Record the measured cause and final choice under Revisions before handoff. No production redesign is admitted by this plan.

## State and concurrency model
The fixture injects one `StandardTestDispatcher(testScheduler)` into repository processing and cache operations. Room performs actual database work outside virtual time. The observer belongs to `backgroundScope`; its cancellation must finish before explicit removal assertions. Databases remain owned by the test and close in `@After`.

## State transitions and identity reuse
| Event | Regression proof |
| --- | --- |
| Explicit ties and absent order | `retirementInvariant_successReleasesRecoveryButFailureRetainsIt` runs both arms against freshly seeded storage. |
| 64 repeated successful removals | Same test checks zero retained recovery peers and increasing fences after every return. |
| Failed removal, then same-target retry | Same test checks 192 retained peers, unchanged durable facts after failure, then zero peers and exact remaining facts plus 65 identity/revision fences after retry. |
| Independent reopen and observer cancellation | Same test retains both fresh-instance reads and joins the observer before removals. |

## Error handling
Keep injected `IOException` and failure assertions. Diagnostics identify static phase/order codes and elapsed time, never persisted content. A failed or suspended operation remains a failed test; no retries, skips or assertion removal.

## Testing strategy
Retain original failure output and baseline results. Observe the regression failing for the diagnosed reason before repair. Run at least three independent fresh focused named-method invocations and the entire affected class once after repair on macOS, retaining XML, phase timings, revisions and executed/failed/skipped counts. Run focused build, lint and formatting checks, then final assembly and `scripts/pre-verify.py --gradle` after merging main and pushing. Full JVM acceptance remains dispatcher-owned; Linux is outside scope.

## Open Questions
- Which phase fails to progress: fixture/Room work, observer cancellation, removal persistence or teardown? Resolve with phase evidence before choosing the repair.
- Does class sequencing expose the failure that focused execution does not? If wider scope is necessary, request the shared investigation with retained evidence rather than running the complete suite.

## Revisions
- 2026-10-24 (recovery continuation): one defect remains within scope; existing written work is 529 lines including retained reports, and the next diagnostic plus bounded repair is forecast below 1,600 total lines with no exported types or signature migration. Run the admitted fresh-worker selection of `AuthoritativeWindowRestoreTest` then the complete affected class, recording actual order through the existing watchdog trace. Add operation-entry markers and a method-local sampler at 45 seconds, repeating every 10 seconds independently of `testScheduler`, without interrupting work. Capture the last phase, coroutine job tree, full thread/lock stacks and process CPU/GC counters. Keep the sampler alive through Room teardown and shut it down in teardown's `finally`. These diagnostics precede any repair decision; preserve the deadline and all persistence assertions. No other feature branch overlaps the affected test.
- 2026-10-24: retained both immutable historical failures, including #2493's 169.905-second return after the one-minute deadline. The unchanged fresh method passed in 12.437 seconds. Diagnostic class execution passed 18/18 (named method 3.465 seconds), and a diagnostic fresh-worker method passed 1/1 in 10.937 seconds. The raw reports and source hashes are under `app/src/test/resources/verification/removal-2523/`. Every measured removal progressed and cleanup completed in 2 ms; neither fresh Room state nor the affected class sequence reproduced the timeout. The historical awaited phase therefore remains unresolved. Keep the original deadline and every assertion; request shared wider diagnostic assessment before selecting a repair. This is preserved investigation work, not a completed reliability repair.
- Shared investigation assessment `72f8c11fb674b7cbca8c225d926cc7f1b6df4a05f8e377ab6e4d31d2f8b0f9e9` is queued for pushed diagnostic source `1e4179171e2fc04c9a99bdf8519111a227885623`; response `assessment-queued`, `executed: 0`. It requests assessment of the original configured macOS JVM order/resource state because the narrower checks cannot locate the historical stop. The causal explanation, demonstrated repair, three post-repair focused invocations, post-repair affected class and final gates remain pending. No PR or completion claim is made.
- 2026-10-24 (recovery execution): the launcher refused the two-class selection before admission, requiring one Mobile class per invocation. The permitted affected-class invocation passed 18/18 (named method 3.484s, body 3462ms, cleanup 2ms); the separate preceding-class invocation passed 25/25 (historically failing opening method 9.134s). Both were forced fresh workers on macOS, with actual method order retained in raw XML/watchdog traces under `app/src/test/resources/verification/removal-2523/`. The named method finished before the 45s sampler, so no failed boundary was captured. These independent passes cannot substitute for the same-worker sequence. Retain the original deadline and every assertion; request the exact sequence through shared investigation assessment with the runtime refusal and new diagnostic candidate. Cause, repair and post-repair acceptance remain unresolved.
- Exact-sequence assessment `300566a622371d6a10532b81fe76086dece4e5b66aa49966169d198cfd0cd3b4` is queued for pushed candidate `9475fba8e5619cd215a25fac46b7d386a77fa6d2`, response `assessment-queued`, `executed: 0`. It requests macOS admission/execution of `AuthoritativeWindowRestoreTest` followed by `AuthoritativeOwnedRemovalTest` in one fresh worker. It is neither executed evidence nor a running dependency; the normal launcher currently refuses that selection. No full suite, device recipe or second OS was requested.
