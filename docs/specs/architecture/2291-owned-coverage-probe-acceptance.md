# Reliable owned coverage concurrency probes (#2291)

## Files read

- `app/src/test/java/de/pyryco/mobile/data/cache/OwnedLegacyCoverageConcurrencyTest.kt`: `Source`, `open`, and the intent/revision probes; local post-emission callback already acknowledges repository acceptance.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot`, `ThreadWriter.accept`, `ThreadWriter.finish`, `writeHistoryPosition`, and `retireAcceptedRows`; publication precedes acceptance, while committed rows and bookkeeping finish together under `NonCancellable`.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: real Room reads and classified writes suspend outside the test scheduler.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/conversation-cache.md`: cache ownership and restart contract.
- `docs/knowledge/features/conversation-cache-legacy-mutations.md`: row success must survive evidence failure and cancellation; preserve real persistence and exact claims.
- `docs/knowledge/features/conversation-cache-testing.md` and `docs/knowledge/features/development-verification-test-scheduling.md`: external I/O completion is distinct from a scheduler drain; release holds and finish readers before closing Room.

## Context

One behaviour slice: deterministic probes of the existing owned-row acceptance and persistence contract. No new product contract or decision record is needed. Linux only; complete configured gates belong to the dispatcher.

Before editing either test, compared the retained #2278 and #2279 verifier bundles:

- #2278 run `104b6f0f3206bdee9e88e1e88be578a848467f515f06bab36e296817de08afc2`, bundle digest `4f8854a65020f88434263e49e382533a994a55865f944dd7ad79f74752213ecc`: stderr classifies both assigned methods as `AssertionError`. Exact assertion messages are outside bundled stdout; the full original is only a pointer on build-fractal-2, unavailable locally. Same-tree rerun XML records both methods, 2 executed/passed, 0 failures/errors/skips.
- #2279 run `ad5057f1cab950880786516b6b7fd72425c3bb2c9e47f3f210ca222d6d3004c6`, bundle digest `5a8b0569871092d8b64589ec3c5356236b2f1e46b0efae7e85c579777b485a74`: stderr classifies the revision method as `AssertionError`; exact original assertion remains unknown. Exact-method rerun XML records 1 executed/passed, 0 failures/errors/skips.
- The separate gap-subtraction minute-long timeout has its own #2269 repair, already merged; it is not this failure signature.

Both probes assume progress before writer acceptance: the intent probe drains a scheduler while the first nonempty snapshot may still be reading Room history; the revision probe observes delivery, which occurs before `ThreadWriter.accept`. Force these interleavings before deciding whether either test needs a separate repair. Historical failures remain failures; controlled reproduction can establish a shared test defect without identifying the exact lost historical assertion.

## Design

Keep changes in the existing test class and ticket-local verification resources. Use `Source.afterEmission` for a snapshot-specific acknowledgement: `emit` returns only after the repository's collector has completed `ThreadWriter.accept`. A capture through `first()` aborts collection and cannot trigger this acknowledgement.

For the intent probe, intercept its first history-position read with entered/release deferreds, retaining the real disk read. Prove draining cannot acknowledge the held snapshot. After release and acceptance, inject the row transaction failure. Assert the exact pending key before and after failure, unchanged disk rows, successful retry pruning, and exact rows through a fresh database/cache reader.

For the revision probe, keep stale restore, post-row-commit hold, evidence failure and cancellation branches. Hold final publication in the collector and prove delivery has not acknowledged acceptance. Release publication and await the final one-row snapshot's post-emission callback before cancellation/final flush. Verify commit survival and exact final disk rows and matching proof keys through a fresh reader.

Add guaranteed cleanup around each changed probe: release every deliberate hold, complete saves, then cancel/join readers before `open` or teardown closes Room. Do not change other probes or introduce a shared harness.

No new exported types, consumer updates, dependencies, UI, logging or production changes are expected. No in-flight feature branch overlaps the assigned test file. Forecast: approximately 450 written lines including plan, diagnostics, test edits and retained evidence; four criteria, no product reject branches, comfortably below admission ceilings.

## State and concurrency model

Existing `UnconfinedTestDispatcher(testScheduler)` remains the processing dispatcher and reader owner is `backgroundScope`; Room keeps real `Dispatchers.IO`. Deferred gates establish happens-before relationships across actual I/O, publication, acceptance and cancellation. Scheduler drains only demonstrate that held work cannot proceed; they are not acknowledgements. No sleeps, acceptance retries or timeout increases.

## State transitions and identity reuse

| Event | Required observation | Test |
| --- | --- | --- |
| Empty restore then first live row with Room read held | Scheduler drain leaves acceptance incomplete; release and post-emission callback precede failed save | `intentInvariant_rowFailureKeepsRetryableWork` |
| Failed row save then successful retry of same row identity | Exact intent retained after failure, disk unchanged; successful retry prunes and survives reopen | `intentInvariant_rowFailureKeepsRetryableWork` |
| Rows committed then evidence fails | Exact two-row disk state survives; stale restore is still held | `revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` |
| Cancellation during deliberately held non-cancellable commit | Commit observed, cancelled save incomplete while held, released save completes cancelled; rows survive | `revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` |
| Stale restore released after final one-row snapshot reuses row 1 | Publication held before acceptance; explicit final acceptance precedes reader cancellation and exact row-2 removal/proof pruning | `revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` |
| Assertion failure or cancellation during any held phase | Finally releases holds and joins owned work before Room closes | Both changed probes |

## Error handling

Controlled `IOException` stays at the real transaction hook. Row failures remain retryable; evidence failures never undo committed rows. Cancellation remains cancellation and the existing non-cancellable commit must remain held until explicitly released. Assertion failures retain their diagnostic results and always release test-owned work.

## Testing strategy

First commit this plan. Add controlled holds to both existing probes and run both with the unrepaired ordering, retaining failing raw output and counted XML. Then repair synchronization and run both methods and the entire affected class with the managed Gradle helper on Linux. The two modified methods themselves retain the controlled regressions; no generic harness or device test is needed for real disk-backed Robolectric Room.

Retain source revisions, commands, failure signatures, raw output/XML and hashes under `app/src/test/resources/verification/owned-acceptance-2291/`. Run lint, debug assembly, Spotless apply/check and final `pre-verify.py --gradle` after the last main merge. Full JVM acceptance remains pending for dispatcher evidence; focused greens do not erase original failures or claim full-gate reliability.

## Open Questions

- Resolved: the two controlled holds reproduce both assertions. Red evidence at `e5a22b25243caa8debec1a81d11532086d357fef` records 2 executed, 2 failed, 0 errors/skips: the row-failure probe sees an empty intent map, and the revision probe retains both disk rows after pre-acceptance cancellation. These establish the shared missing-acceptance boundary; the exact original historical assertions remain unknown.

## Revisions

### 2026-10-18: controlled diagnosis

Both red probes fail at the expected contract assertions while held before acceptance. Keep both tests together and repair only their observation ordering. The intent retry now captures an empty live snapshot, so successful persistence must consume the retained exact pending row rather than receiving it again from the source. The existing post-emission acknowledgement is sufficient; no product change or new harness is needed.

### 2026-10-18: focused verification and scope recheck

Repaired source `0ce36236b2ee3bd507705922ef6e38517781778b` passes the exact assigned pair (2 executed/passed) and the full affected class (9 executed/passed), each with 0 failures/errors/skips on Linux. The pair command also passes lint and debug assembly. Raw console captures, complete compressed XML, source revisions, commands and hashes are retained under `app/src/test/resources/verification/owned-acceptance-2291/`. The cold red capture explicitly marks one truncated compiler-warning chunk; its test-result output and complete XML are intact. Green captures are complete. Original failure details remain unknown beyond their retained classification.

Final scope remains one behaviour with no production changes, exported types, consumer updates or new reject branches. Approximately 1100 written lines including retained console evidence (most of the increase over the 450-line forecast is compiler-warning capture), below the 1600-line ceiling. Full configured gates remain dispatcher-owned; other operating systems are outside this ticket's verification scope.
