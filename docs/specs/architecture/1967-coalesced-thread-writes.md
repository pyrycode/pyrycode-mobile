# Coalesced thread cache writes (#1967)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot`, `historyWrites`, `drawnThreads`, `writeHistoryPosition` and `delete` own scheduling and serialization.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadSnapshotSource.kt`: `ThreadSnapshot` keeps rows, suppression, order and read evidence together.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `observeThreadSnapshot` switches connections with an empty snapshot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `receivedThread`, `launchHistoryAsk` and `recordCoverage` independently collect snapshots and save coverage.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `ThreadDestinationFactory` supplies host-bound wrappers; constructor defaults remain unchanged.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: `cacheableThreadRows` bounds comparison while `writeThread` requires untrimmed input.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: row and position mutations preserve the other document half under the file mutex and validate retained coverage.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt`, `CachedThreadWorkerTest.kt`, `HistoryDurabilityTest.kt` and `HistoryCacheReworkTest.kt`: existing scheduling, retry, deletion, merge and full-cap durability assertions.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryMessageIdentityTest.kt`, `HistoryReconciliationTest.kt` and `RemoteConversationRepositoryTest.kt`: full-suite failures expose synchronous cache-read assumptions; retain their row/identity assertions after waiting for the quiet period.
- `docs/knowledge/features/caching-conversation-repository.md`: suppressed emptiness must not rebase; successful writes alone advance comparison; coverage saves reconcile rows before state.
- `docs/knowledge/features/conversation-cache-layout.md`: untrimmed input is necessary to clear stale cursor/stop claims, and a file mutex alone cannot order the two callers.
- `docs/specs/architecture/1966-cached-thread-worker.md`: merged prerequisite keeps merge/filter/equality off main and snapshot processing sequential.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: authenticated content remains untrusted, relay delays/replays cannot justify plaintext diagnostics.

## Context

Settled snapshots currently wait for whole-thread disk writes before the next snapshot is processed. Separate sequential snapshot processing from a collection-owned coalescing writer, preserving storage format, retention and merge semantics. No UI change or new operator action is introduced. No in-flight feature branch overlaps the planned files.

Sizing: forecast approximately 1100 written lines including this plan, private scheduling helpers, controlled probes and existing timing adjustments; zero exported declarations or signature migrations, four acceptance criteria, and fewer than ten scheduling/error branches. The deliverable is one persistence scheduling contract. No decision record is required.

## Design

Keep merge/rebase/suppression processing and downstream publication sequential exactly as in #1966. After delivery returns, filter/compare on the processing dispatcher and accept an immutable candidate containing untrimmed drawn rows, cacheable comparison rows and its drawn generation. A private per-collection writer retains the latest accepted candidate and a conflated channel. Only changed cacheable candidates reset its 100 ms delay; unchanged or streaming-only snapshots do not. A failed attempt enables a retry on the next snapshot, including an unchanged snapshot.

The writer waits for 100 ms without a changed candidate, then writes once. During a running write, newer candidates replace one another; the running write can finish, followed by the latest quiet candidate. Successful writes alone advance its persisted comparison baseline. Returning to the persisted rows replaces an obsolete pending candidate too. Disk work never holds the snapshot processing path.

Publish a monotonically increasing generation alongside each conversation's `drawnThreads` rows. Coverage writes select that drawn base while holding `historyWrites`, merge the delegate snapshot, and after successful row persistence mark the selected generation satisfied. An observer candidate at or before that generation is already incorporated or superseded by the newer drawn base and must not rewrite the document after the coverage save. Later drawn generations remain eligible. Update satisfaction after the row write even if the following position write fails: disk rows still supersede the earlier candidate. This is destination-local bookkeeping, not a wire or disk field.

Capture the current generation at collection entry, before cache reads can suspend. Only coverage newer than that boundary can supersede this collection's unchanged candidates. Retained coverage remains authoritative for successful row comparisons and stale-write rejection, but cannot alone turn an unchanged empty or streaming-only failed restore into pending work. Coverage saved during restoration still permits a later intentional removal. Changed rows and failed-write retries remain eligible independently of this exception.

## State and concurrency model

Use structured `coroutineScope` inside the cold flow. Its writer child runs on `processingDispatcher`; no repository/application scope or orphan job is added. Immutable candidate references and a thread-safe retry flag bridge collector and writer. Successful row baselines are shared under `historyWrites`; candidate completion and the collection-entry generation remain local to each writer. The conflated channel bounds pending work; merge generations are never conflated.

On normal completion, upstream failure or collector cancellation, non-cancellable cleanup cancels and joins the writer, then attempts the latest accepted candidate once if it is not already persisted or satisfied by coverage. It waits for actual I/O completion, not merely scheduling. Failure does not loop. Snapshots interrupted before candidate acceptance do not create new cleanup work. This is orderly coroutine cleanup, not process-death durability.

All row/history writes and confirmed deletion continue to hold `historyWrites`, then the cache's file mutex. Every actual writer checks the tombstone inside that lock. Confirmed deletion first marks the tombstone, waits for in-flight I/O and removes the document. Refused daemon deletion leaves the tombstone and cache untouched. Clear generation metadata during confirmed removal.

## Error handling

Retain `Result<Unit>` storage outcomes and the existing static `event=thread_cache_write_failed` diagnostic. A failure leaves the baseline unchanged, enables next-snapshot retry and allows one final flush attempt; no autonomous busy retry. Tombstoned or history-satisfied candidates do not invoke storage. Cancellation propagates after cleanup. No row text, ids, cursor, payload or exception detail enters logs.

## Testing strategy

Write controlled unit probes first and observe their failures. Use virtual time for the 100 ms burst boundary, unchanged/streaming rows, returned-to-baseline candidates and failed-write retry. Hold cache I/O with deferred gates and prove subsequent snapshots are processed/delivered and intermediate pending candidates are replaced. Assert completion/cancellation waits for the final write, and fresh `FileConversationCache` instances restore the accepted rows. Probe both failed scheduled and failed final attempts without repeated idle retries.

For the listed invariants, test merged cached/live candidates with overlaps, replay, cache-only neighbours, empty/one-row snapshots, suppression and disconnect/reconnect between acceptance and persistence. Control deletion during pending, in-flight and final writes; retain refused-delete and host-isolation assertions. Hold the history row/state operation across stale observer candidates and later snapshots; test successful rows with failed state, and full-cap trimming through the observer followed by a fresh restore. Existing merge/identity probes remain authoritative; only synchronous write timing expectations change.

Run `CachingConversationRepositoryTest`, `CachedThreadWorkerTest`, `HistoryDurabilityTest`, `HistoryCacheReworkTest` and new coalescing probes, plus affected existing cache tests exposed by the full unit suite. Run lint, assembleDebug, Spotless and final unit/shared suite and pre-verify after the final main merge. No device, scripted or real-Claude scenario is needed for data-layer scheduling.

## Open Questions

None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new parser or trust boundary. Candidates contain existing `ThreadSnapshot` model rows; cache restoration does not grant read evidence or make daemon text trusted.
- [Tokens] No token/key storage or handling is added. Candidate diagnostics must not interpolate rows, ids, cursors or exceptions.
- [Files and storage] Retain `FileConversationCache` app-private `noBackupFilesDir` root, hashed host/conversation paths, atomic replacement and existing backup exclusions. The existing plaintext conversation cache policy and rooted-device confidentiality are outside this scheduling ticket; no new secret or file format is introduced.
- [Android attack surface] No manifest, exported component, intent, provider or WebView change.
- [Cryptography] No handshake, nonce, randomness or key changes; the vendored Noise variant remains unchanged.
- [Network and I/O] The daemon stream stays sequential. Pending disk work is conflated and failed writes do not busy-loop. Cleanup uses the cache's existing I/O scheduling and completion contract.
- [Errors, logs and telemetry] Retain only the existing debug static failure event; no new telemetry or identifying diagnostics.
- [Concurrency] SHOULD FIX: a delayed candidate could overwrite newer coverage rows. The drawn-generation satisfaction guard under `historyWrites` is required, including successful rows followed by failed state persistence. Assign observer generations at capture before worker processing, and satisfy through the history snapshot generation and selected drawn base; newer candidates must compare with the actual successful history baseline. SHOULD FIX: deletion must defeat delayed and cleanup writes; preserve the in-lock tombstone guard and join the child before cleanup returns.
- [Concurrency, rework] Resolved verifier finding 1: successful observer writes can supersede coverage rows before collection restart. Keep the actual successful row baseline in destination-local `persistedThreads`, updated by both row writers inside `historyWrites`. Observer success preserves the coverage satisfaction generation; failed writes leave both fields unchanged. Collection-local baselines cannot override this shared record. No new scope, I/O, parser, storage path, credential, cryptography or diagnostic is introduced; the other reviewed boundaries remain unchanged.
- [Concurrency, rework] A successfully completed candidate is no longer pending. Keep collection-local completion by candidate identity so cleanup cannot repeat an older completed write after another collector persists newer rows. Pending candidates still compare against the shared successful baseline, and failures remain retryable. The probe `baselineInvariant_completedCandidateCannotOverwriteAnotherCollectorsNewerRows` guards this interaction.
- [Concurrency, rework] A positive `coverageGeneration` proves a save happened, but retained coverage cannot prove it superseded work in a later collection. Capture the generation at collection entry before restoration suspends, and require coverage newer than that boundary for unchanged-candidate supersession. This protects durable rows and coverage after a graceful failed restore on both scheduled persistence and final flush, while allowing coverage saved during restoration to be superseded by a newer removal. Shared successful row comparisons and stale-candidate rejection retain the full coverage generation; changed candidates and failed-write retries do not depend on the supersession exception. No new trust boundary, I/O, scope, diagnostic or persisted field is introduced.
- [Threat model] Relay delay/flood/replay is handled by existing Noise/wire validation and bounded pending scheduling, without plaintext logs. Hostile text stays in existing typed/cache-policy paths. Token theft and UI screenshot/accessibility/keyboard leakage are unaffected; current key-store/render owners retain those mitigations. Process termination may lose pending rows, as explicitly permitted by the cleanup contract.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

- 2026-10-08: `coverageInvariant_newerRemovalComparedWithActualPersistedHistoryRows` exposed a baseline gap: a coverage save can add rows while the observer still remembers an earlier successful write. Keep the coverage save's cacheable rows together with its satisfied generation. Later candidates compare against that actual successful history baseline, and unchanged candidates superseded by a history save remain eligible when a newer drawn generation deliberately removes history rows. Only successful storage updates publish this bookkeeping. The bounded timer runs in the collecting scope and only signals readiness; filtering/comparison and the writer remain on the processing dispatcher.

- 2026-10-08: Final sizing is approximately 930 written lines including deleted/replaced code, the plan and 23 new probes. There are no exported declarations or signature migrations; the four acceptance criteria and scheduling/reject branches remain within the ticket limits.

- 2026-10-08: The renderer-collision probe independently fails in the unchanged `mergeUnsignedCachedRows`, before the writer runs. Filed #1979 in Inbox and retained `identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect` with an explicit ignored blocker. The scheduling implementation does not change merge admission; existing renderer-owner and reconnect probes remain enabled.

- 2026-10-08: `coverageInvariant_capturedOlderSnapshotCannotInvalidateNewerSaveWhenWorkerResumes` reproduced lost newer rows/coverage when an older snapshot completed worker processing after a history save. Observer generation allocation now happens at upstream capture, before merge work; a successful coverage row write satisfies through the maximum of its captured snapshot generation and selected drawn generation. Publication time cannot make stale work newer. The full unit suite also exposed four synchronous cache-read expectations in three existing test files; only their virtual-time persistence waits change. Final sizing remains below 1200 written lines with no exported declaration or signature migration.

- 2026-10-08: Verifier findings 1 and 2 drove this rework. `baselineInvariant_resubscriptionPersistsRowsAfterObserverSupersedesCoverage` and its final-flush twin reproduced dropped rows on the same wrapper after coverage `[a,b]`, observer `[a]`, unsubscribe, and resubscribe with `[a,b]`. Replace the collection-local success baseline with shared `PersistedThread` bookkeeping: every successful observer or coverage row write updates its cacheable rows under `historyWrites`, while only coverage writes advance `coverageGeneration`. The initial restored rows remain the fallback before any wrapper write. Failed storage cannot advance either field, and stale candidates still respect the coverage guard. The exact-cap retention test now waits the quiet period and proves its rows reached disk before checking the saved position or accepting an oversized candidate. Rework adds two lifecycle probes, no exported declaration or signature migration, and keeps total written work below 1300 lines.

- 2026-10-08: A further shared-baseline probe reproduced an older collector rewriting its already-completed candidate during cancellation after a second collector saved newer rows. Track the successfully completed candidate by identity within each writer and skip repeated persistence of that candidate. This tracks pending-work completion, not a row-comparison baseline: later accepted candidates still consult actual shared successful rows under `historyWrites`. Failed attempts never mark completion. The additional probe and private guard keep written work below 1300 lines and scheduling/reject branches below ten.

- 2026-10-08: `unchangedInvariant_observerBaselineDoesNotTurnFailedRestoreIntoAnEmptyWrite` reproduced an unchanged empty candidate erasing earlier observer-persisted rows after a graceful failed read on resubscription. The shared record now includes observer-only writes, so its existence alone cannot mean history superseded a candidate. Require positive `coverageGeneration` for that acceptance exception. An unchanged or streaming-only snapshot with no coverage save again creates no pending write; changed candidates and failed-write retries remain eligible. Four added rework probes and their fixes keep total written work below 1300 lines.

- 2026-10-08: The second verifier's finding 1 and both `unchangedInvariant_retainedCoverageCannotEraseFailedRestore*` probes reproduced durable row loss with positive coverage retained from an earlier collection; the prior positive-generation guard was insufficient. Capture the current generation at collection entry before the first cache read, and require a strictly newer coverage generation for unchanged-candidate supersession. Retained coverage still governs shared baseline comparison and stale-write rejection. The scheduled and final-flush probes preserve rows and coverage through a fresh cache instance; `coverageInvariant_saveDuringRestoreStillAllowsNewerUnchangedRemoval` protects coverage acquired during a suspended restore, and `retryInvariant_retainedCoverageStillAllowsChangedRowsAndUnchangedFailureRetry` protects settled changes and retry without an idle loop. Four new probes, no exported declaration or signature migration, and total written work below 1400 lines.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/caching-conversation-repository.md`, “What is written, and when” and “State and concurrency”: settled-change coalescing, the 100 ms quiet period, replaceable pending work, unchanged/streaming timing, failed-write retry and collection-owned orderly cleanup versus process death.
- Pending for the documentation stage: `docs/knowledge/features/caching-conversation-repository.md`, “The saved history position” and “delete”, and `docs/knowledge/features/conversation-cache-layout.md`, “The thread document's two writers”: capture-order coverage satisfaction, shared successful row baselines across collection restarts and both writers, the collection-entry boundary distinguishing retained coverage from current-collection supersession, successful rows followed by failed position saves, and deletion defeating pending, in-flight and final writes.
