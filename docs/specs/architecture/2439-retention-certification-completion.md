# Retention certification proof completion (#2439)

## Files read

- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeOwnedUpsertTest.kt`: `retentionInvariant_trimClearsCertification`, `open`, `PartialCache`, `observe`, `fresh` and `assertWork` exercise the full-cap disk fixture, collector final flush and independent reopen.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeAuthoritativeThread` restores before collecting and calls `ThreadWriter.finish` during cancellation.
- `docs/knowledge/features/conversation-cache.md`, `conversation-cache-contract.md`, `conversation-cache-testing.md` and `caching-conversation-repository-testing.md`: preserve real disk/reopen evidence, retention limits, bounded writes and controlled retry admission.
- `docs/specs/architecture/2263-held-retry-atomic-probe.md`: nearest repair and retained red/green evidence pattern.

## Context

The Linux gate and standalone main run exceeded the default one-minute `runTest` deadline. The exception alone cannot distinguish slow full-cap Room work from a suspended collector or writer. The original gate path has since been overwritten by passing output; preserve the failure from #2373's retained verifier bundle instead. No UI or wire change applies.

One behavior slice: restore reliable completion of the existing retention/certification proof. Forecast approximately 450 written lines including retained XML/output and this plan, zero new exported types, zero production consumer changes and no new reject branches; three acceptance criteria. The evidence increases the refined estimate without exceeding any ceiling. Overlaps: #2334 and #2437 contain identical timeout/phase-marker edits in this method; keep this change local and reconcile that hunk without depending on either feature.

## Design

First retain the failed evidence and run the unchanged named method against a fresh database. Add content-free monotonic phase timings around fixture construction, seeding, observation, final flush, independent reads, rejected metadata-only certification and successful conservative certification. Keep every fixture row, exact record assertion and write-work assertion unchanged.

Choose the repair from measured progress. If synchronous full-cap work exceeds the default deadline while all phases finish correctly, give only this method a bounded two-minute timeout, with the measured allowance recorded here. If a scheduler/cancellation wait fails to progress, repair that demonstrated cause rather than increasing its timeout. Retain fresh raw focused-method and affected-class output/XML with source identities under `app/src/test/resources/verification/retention-2439/`.

## State and concurrency model

No application state or dispatcher changes are planned. The test uses `StandardTestDispatcher(testScheduler)` for cache/processing work and `backgroundScope` for observation. `cancelAndJoin` must still finish the production writer before independent Room reads. Timings use wall-clock monotonic time, not virtual scheduler time.

## State transitions and identity reuse

`retentionInvariant_trimClearsCertification` covers full-cap seed → overflow trim → same-id newer revision → cancellation/final flush → independent reopen → catch-up completion and reopen → page completion and reopen → fresh conservative baseline certification and reopen. All existing exact-row, checkpoint, coverage and work-count assertions remain active.

## Error handling

Keep the coroutine deadline finite; do not skip failures or catch assertion errors. Phase markers identify the last completed boundary on failure and contain only static codes and elapsed durations.

## Testing strategy

Run the unchanged named method first and retain its outcome, supplementing historical red evidence if it passes. Run the instrumented method with the original deadline before deciding on an allowance. Then run the repaired method and the entire affected `AuthoritativeOwnedUpsertTest` class with full-retention admission, retaining fresh executed/passed/failed/skipped counts and raw reports. Linux only. Run lint, assembly and forced Spotless checks, then final assembly and `scripts/pre-verify.py --gradle` after the last main merge. Complete JVM acceptance remains dispatcher-owned; no native/live scenario is needed for this Room-backed JVM regression.

## Open Questions

- Which phase consumes the deadline, and does a finite longer allowance permit all existing assertions to complete? Resolve with focused timings before committing the repair.

## Revisions

### 2026-10-21: measured full-cap read cost

Resolved the open question using the original-deadline diagnostic: fixture construction 364 ms, seed 5577 ms, first observation 114 ms, trim flush 41 ms, revision flush 7 ms, completion flushes 4 ms each and recovery flush 5 ms. Four independent full-cap reads and their assertions cost 10357, 10674, 10816 and 10467 ms, approximately 86% of the 49146 ms measured body. No observation or final-flush wait failed to progress. The unchanged method passed in 51.312 seconds and the instrumented method in 50.841 seconds, while retained main evidence failed at 68.685 seconds after all five writes. The default one-minute deadline is too close to the real synchronous storage/decoding work; this is not evidence of a deadlock or a defective collector.

Set only this method's timeout to two minutes: less than 2.5 times the observed body cost and approximately 1.75 times the historical failed duration. Keep all four independent reads, the full 100000-row fixture, every exact-row/certification and work-bound assertion, and phase markers. The longer allowance still bounds an actual hang. No production change is justified. Fresh repaired-method and class reports are retained under `app/src/test/resources/verification/retention-2439/`: separate runs executed/passed 1/1 and 17/17, respectively, with zero failures/errors/skips.

### 2026-10-21: complete Linux acceptance

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2443#issuecomment-6328796989) reviewed candidate `75afa8d5a2c55cef6f46b1d5059938a1ba007404`. Dispatcher invocation `jvm-dq3to5jr` of `python3 scripts/jvm-test-gate.py --with-build-checks` executed/passed 6447 tests, zero failures/errors/skips. All 17 `AuthoritativeOwnedUpsertTest` methods passed, including `retentionInvariant_trimClearsCertification` in 45.861 seconds. Complete JVM acceptance is satisfied. The [owning testing topic](../../knowledge/features/conversation-cache-testing.md#full-cap-retention-certification-proof-2439) preserves the measured allowance, historical failure, focused evidence and #2334 overlap reconciliation note. macOS is outside this Linux ticket's verification scope.
