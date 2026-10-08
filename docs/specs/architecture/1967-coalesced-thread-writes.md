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

## State and concurrency model

Use structured `coroutineScope` inside the cold flow. Its writer child runs on `processingDispatcher`; no repository/application scope or orphan job is added. Immutable candidate references and a thread-safe retry flag bridge collector and writer. Writer baseline is owned by the writer and, after it is joined, by cleanup. The conflated channel bounds pending work; merge generations are never conflated.

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
- [Files and storage] Retain `FileConversationCache` app-private hashed host/conversation paths, atomic replacement and existing backup exclusions. The existing plaintext conversation cache policy and rooted-device confidentiality are outside this scheduling ticket; no new secret or file format is introduced.
- [Android attack surface] No manifest, exported component, intent, provider or WebView change.
- [Cryptography] No handshake, nonce, randomness or key changes; the vendored Noise variant remains unchanged.
- [Network and I/O] The daemon stream stays sequential. Pending disk work is conflated and failed writes do not busy-loop. Cleanup uses the cache's existing I/O scheduling and completion contract.
- [Errors, logs and telemetry] Retain only the existing debug static failure event; no new telemetry or identifying diagnostics.
- [Concurrency] SHOULD FIX: a delayed candidate could overwrite newer coverage rows. The drawn-generation satisfaction guard under `historyWrites` is required, including successful rows followed by failed state persistence. SHOULD FIX: deletion must defeat delayed and cleanup writes; preserve the in-lock tombstone guard and join the child before cleanup returns.
- [Threat model] Relay delay/flood/replay is handled by existing Noise/wire validation and bounded pending scheduling, without plaintext logs. Hostile text stays in existing typed/cache-policy paths. Token theft and UI screenshot/accessibility/keyboard leakage are unaffected; current key-store/render owners retain those mitigations. Process termination may lose pending rows, as explicitly permitted by the cleanup contract.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08
