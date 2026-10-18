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

Pending measured diagnosis; record the chosen repair and its evidence before handoff.
