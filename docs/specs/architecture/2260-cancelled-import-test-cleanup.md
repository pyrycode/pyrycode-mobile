# Bounded cancelled import test cleanup (#2260)

## Files read

- `app/src/test/java/de/pyryco/mobile/data/cache/MigratingConversationCacheTest.kt`: the cancelled committed import probe, its four combinations, full-family reads/replacements and restart checks.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: `ensureImported` preserves the cancellation fence; no production change is needed.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: `classifiedTransaction` propagates cancellation rather than classifying it as a storage failure.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-testing.md`: preserve real disk imports and fresh-instance persistence checks.
- `docs/knowledge/features/development-verification-test-scheduling.md`: cancellation requests do not establish completion; explicitly stop owned work.
- `docs/specs/architecture/2161-import-saved-threads-room.md`: nearest import/cancellation design, located through QMD.
- #2254's `rework-results.json` at `2739a690cc5719e9a255962f8d382d303beffb6c`: timeout has no reliable counts; unchanged method and class retries passed 1/1 and 15/15 separately.

## Context

The selected scheduler stays paused while Room commits. One cancellation drain followed by a join can miss a later IO continuation. This is a test scheduling repair, not evidence of a production cache defect. The regression and bounded failure probe prove this single behavior and stay with their sole consumer. No decision record is needed.

Sizing: approximately 260 written lines including plan, tests, helper and evidence manifest, zero exported types, zero production callers, four acceptance criteria and one timeout branch. The #2161 analogue inserted 70 test lines; this adds two controlled cleanup probes and evidence. No overlapping in-flight branch changes the test file as of the initial fetch.

## Design

Keep a private `cancelAndDrain` helper beside the affected test. Cancel the reader, repeatedly drain its selected `TestCoroutineScheduler`, and return only after `isCompleted`. Use a real-dispatcher timeout of 10 seconds and report reader cancellation/completion and scheduler state if it expires. Never join after timeout. A callback after the first drain synchronizes the regression's late continuation; a shorter injectable timeout keeps the non-completion probe focused.

The real reader runs in an explicitly owned test scope independent of the enclosing `runBlocking`. Cancel that scope on every exit so a failed cleanup cannot trigger an implicit enclosing-scope join. Complete the import release gate on every exit before cleanup. Leave every existing method and assertion intact, including both families, both entry paths, observed commit, cancelled reader, import-unobserved refusal, exact content and restart/no-replacement checks.

## State and concurrency model

Production jobs and flows do not change. The test reader uses `StandardTestDispatcher` and a dedicated owner scope, cancelled in `finally`. Cleanup runs under `NonCancellable` on `Dispatchers.Default`, so its timeout/delay progresses independently of the paused scheduler and caller cancellation. No unbounded join is used. Controlled probes hold a non-cancellable finalizer on a deferred gate and release it in `finally`; fallback cleanup drains under a separate bound.

## State transitions and identity reuse

| Event | Test |
| --- | --- |
| Cancellation after committed import, read/write entry, legacy/authoritative family | `classificationInvariant_cancelledCommittedImportCannotReadmitUnobservedWrites` |
| Completion enqueued strictly after the first cancellation drain | `cleanupInvariant_lateContinuationCompletesAfterInitialDrain` |
| Completion unavailable until after cleanup deadline; held work released on failure | `cleanupInvariant_nonCompletionFailsWithoutWaitingForOwner` |
| Restart after cancellation preserves both exact families and prevents replacement | Existing restart assertions in the committed-import test |

## Error handling

Timeout produces a diagnostic test failure within 10 seconds, independent of virtual time. Detached scope ownership avoids structured waiting after that failure. Probe gates are released even if setup or assertions fail; no production error handling changes.

## Testing strategy

Write the two controlled probes against an extraction of the existing single-drain/join cleanup first, with an external real-time bound so red evidence returns without the watchdog. Retain fresh XML and raw command output. Then repair the helper and run the new methods, the existing affected method and the entire affected class through normal Linux Gradle admission. Keep #2254's failed capture and passing retries distinct in the evidence manifest. No device or live check is needed. Full acceptance remains dispatcher-owned.

Run focused JVM tests, lint, debug assembly, changed-file formatting, forced formatting check, then final assembly and `scripts/pre-verify.py --gradle` after merging main and pushing. Retain source revisions, commands and counted XML under `app/src/test/resources/verification/cancelled-import-2260/`.

## Open Questions

None.

## Revisions

### 2026-10-18: failure-path proof and evidence sizing

The non-completion probe now awaits the enclosing cleanup coroutine while the child's release gate remains held. This proves failure returns without an implicit parent wait; releasing the gate before checking the caller would miss that defect. Retaining the separate red captures, green runs and historical evidence raises the forecast to approximately 450 written lines, still one behavior slice within every ceiling. The production cache and existing import assertions remain unchanged.
