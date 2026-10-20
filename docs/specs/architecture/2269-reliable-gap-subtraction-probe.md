# Reliable received-gap subtraction probe (#2269)

## Files read

- `app/src/test/java/de/pyryco/mobile/data/cache/OwnedLegacyCoverageTest.kt`: `open` and `anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk` combine four real disk fixtures, repeat saves and reopen readers under one default `runTest` deadline.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `writeHistoryPosition` captures received state and writes owned rows/evidence; the regression must retain this production path.
- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: `writeOwnedCoverage` preserves received anchors and conservative producer losses using localized storage work.
- `docs/knowledge/features/conversation-cache.md`, `conversation-cache-legacy-mutations.md` and `conversation-cache-testing.md`: fresh database instances prove persistence; localized counters and full fixtures must remain. Other full-cap disk probes already use measured, test-local deadlines.
- `docs/specs/architecture/2254-owned-legacy-coverage.md`: “preserve valid gap progress during producer subtraction” records the original passing contract and invalid-anchor control.
- Retained #2265 verifier bundle `0c087469a5fd5557f5d77cf1447830e3432a87813de8ef5d2420ab802ecea01d`: both exact-method reruns time out after all four mutation counters, including unchanged base `9281e2e5926e`.

## Change

Restore reliability of the existing producer-subtraction probe, one behaviour slice with its existing regression. First run the unchanged method on Linux and retain its XML. Add content-free monotonic phase timings around fixture preparation, initial persistence/restore, mutation, repeat saves, reopened assertions and final close. Keep timings outside the measured SQL window. Use a diagnostic run to establish whether work continues and completes or a specific operation remains suspended. Only if measured completion demonstrates cumulative real-disk cost, give this method a bounded local deadline with justified headroom. A suspended operation instead requires diagnosis and a revision before repair; no production change is planned without evidence.

Preserve both sizes (20/18000), change/removal paths, exact rows and evidence assertions, repeated saves, fresh database readers, conservative unknown/start state, withdrawn proof checks, equality of unrelated evidence and equal localized SQL/storage counts. Keep every other class probe and invalid-anchor control unchanged. No fixture reduction, skip, global deadline or product contract change. Retain raw historical/local XML and a small source-revision/count/timing manifest under `app/src/test/resources/verification/gap-subtraction-2269/`.

No overlapping in-flight feature branch touches the test at admission. Forecast: about 220 written text lines across test, plan and evidence manifest; no exported types, consumer changes or new product error branches; three acceptance criteria. Recheck after diagnosis.

## Testing strategy

The existing failing probe is the red regression. Run it unchanged, then instrumented with the original deadline before any justified local deadline repair. Run the repaired exact method and all of `OwnedLegacyCoverageTest`, checking every selected method executes and passes with zero failures/errors/skips. Preserve historical failures separately. Run lint, debug APK assembly and forced Spotless; merge main, push and run final assembly and `scripts/pre-verify.py --gradle`. Linux only; complete configured gates and JVM-gate inclusion belong to the dispatcher. No UI, device or real-Claude acceptance applies.

## Revisions

### 2026-10-18: measured cumulative disk work

The unchanged Linux baseline at `6e292874310dde62d1f8461f805dda9224ad9195` passed 1/1 in 54.708 seconds. Instrumented `1faf301baf71e781739a9d4a2fa8cc58308f056d` retained the original minute and passed 1/1 in 53.973 seconds. All four cases reached `complete`, including explicit database close, at 52.250 seconds of monotonic body time. The large change case ran from 1.804 to 30.005 seconds; removal ran from 30.005 to 52.250. Repeated reopen/restore/verification reads dominate; measured large localized writes took 423/285ms. Both sizes still execute 24 legacy SQL statements with identical relevant counters for each mutation path.

The retained historical failures occur in the final 18000-row removal case after its mutation counter: the remaining phase is mutation verification, repeated saves and reopened-reader verification. Their XML cannot identify the precise suspended call, and this run does not claim to reconstruct it. Fresh phase evidence shows every operation and database close completes; no unfinished-coroutine/Room defect is demonstrated. The earlier 49.092-second pass, fresh 54-second passes and historical 60-second cancellations establish inadequate aggregate runtime headroom. Preserve those failed captures rather than replacing them with green retries.

Use `runTest(timeout = 2.minutes)` only on this method, approximately twice the measured complete runtime, with no global setting change. Keep content-free phase markers outside the measured SQL window and close each final reader explicitly before its completion marker. Every original assertion, fixture and companion control remains intact. No production change, new state machine, exported declaration or consumer migration is required. The evidence manifest retains exact-method and complete-class results; wider configured acceptance remains dispatcher-owned.

The repaired exact-method run at `2bc42d88b54286d18b4ccb056f79429fabb03db5` passed 1/1 in 63.138 seconds, with body completion at 60.703 seconds. This run directly crosses the old deadline during `verify_final_reopen` of the 18000-row removal case (57.346–60.703 seconds), then closes immediately and completes. It demonstrates the cumulative-runtime cause without artificial delay or load injection: the final real disk reads/comparisons finish just beyond the former budget. All four localized mutation counters remain identical across sizes, at 24 SQL statements. Lint, assembly and Spotless application also passed in that invocation.

The affected class at the same test source revision passed all 16 methods, zero failures/errors/skips, in 116.183 seconds; the named method took 49.890 seconds and the unchanged invalid-anchor control passed. Repaired focused totals are 17 executed/passed. Raw XML, commands, durations, source revisions and hashes are retained under `app/src/test/resources/verification/gap-subtraction-2269/`. Final scope remains below the 220-line estimate for written text, with no production file or consumer update.
