# Cached thread snapshot processing on a worker (#1966)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot` owns per-collection merge/rebase state and emits before writing under `historyWrites`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadSnapshotSource.kt`: `ThreadSnapshot` binds rows, suppression, unsigned order and read evidence to one generation.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeUnsignedCachedRows` retains placement and renderer ownership from #1941, already merged.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: `settledThreadRows` is unbounded; `cacheableThreadRows` applies disk exclusions and retention.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt`: immediate collectors cover streaming write timing, retry, suppression, real-file persistence, trimming and deletion.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryMessageIdentityTest.kt`, `HistoryReconciliationTest.kt`, `HistoryAliasCorrelationTest.kt`, `AssistantParentCacheTest.kt`, `HistoryCacheReworkTest.kt`, `UnsignedHistoryCacheTest.kt`, `RemoteConversationRepositoryTest.kt`, `RemoteConversationRepositoryReplySuggestionTest.kt`: cache collectors must keep deterministic scheduling when the default worker changes.
- `docs/knowledge/features/caching-conversation-repository.md`: suppressed emptiness must preserve the fixed base and attachment hints; disconnect rebases only settled rows, retaining durable order.
- `docs/knowledge/features/conversation-cache-testing.md`: retain real-file full-cap trim and fresh-instance assertions.
- `docs/specs/architecture/1917-fragmented-history-projection.md`: controlled worker/main scheduling proves responsiveness without elapsed-time thresholds.

## Context

Restore filtering, merge, cache filtering and equality currently inherit the collector dispatcher. A 100,000-row cached channel repeats this work while streaming. Move only observer processing off main; write coalescing and UI pacing remain separate tickets. No new storage, protocol, UI behavior or decision record is required. No in-flight branch overlaps the planned files. #1941 is closed and its renderer-owner implementation is present.

Forecast: about 550 written lines including tests, scheduling injections and this plan; no new exported types, two acceptance criteria and no new error branches. Constructor searches find more than ten test consumers needing deterministic worker injection (the core cache test alone has more than ten immediate streaming collectors). This exceeds the consumer-update limit. A separate dispatcher-plumbing slice would be consumed only by this worker ticket, so the handoffs floor rule requires retaining it here rather than creating that split. Production construction retains a defaulted worker and requires no migration.

## Design

Append an injected `CoroutineDispatcher` defaulting to `Dispatchers.Default`. Use sequential `withContext` calls, without buffered `flowOn`, independent launches or latest-only cancellation. Initialize restored ordering on the worker; process each captured immutable `ThreadSnapshot` on the worker, including boundary detection, settled rebase, suppression filtering, unsigned-order merge and renderer ownership. Retain per-collection base, base order, last order, last drawn and last written.

Publish drawn metadata and emit `snapshot.copy(rows = drawn)` on the collector. Only after downstream emission returns, run `cacheableThreadRows` and equality on the worker. Keep the existing tombstone checks, `historyWrites` mutex, untrimmed write input, retry-on-failure and successful-write-only advancement of `lastWritten`. The list-only observer also performs its distinct comparison on the worker so equality does not return to main through `observeMessages`.

## State and concurrency model

The cold flow owns its sequential local state. Worker contexts remain children of the collecting coroutine; collection cancellation prevents publication or a later write from pending work. Do not conflate snapshots or collect another generation before processing the current one. No scope is added. The wrapper's shared drawn map, tombstones and write mutex retain their existing roles. Cache I/O scheduling stays with the cache implementation and writes still begin after delivery.

## Error handling

No new failure branch. Cache write failure leaves `lastWritten` unchanged and emits the existing content-free failure event. Cancellation propagates. Snapshot read evidence is forwarded unchanged; cached restoration creates no sight claims, read commands or history requests.

## Testing strategy

Add a failing controlled main/worker probe over 100,000 rows before implementation. Instrument list traversal/equality with context checks to prove restore-order lookup, suppression filtering, merge and cache filtering/equality execute on the worker. Hold worker execution while a main sentinel progresses. Assert downstream delivery runs on the collector, and gate it to prove cache filtering and writing still wait until delivery returns. Verify unchanged rows do not write and changed rows do.

Add small invariant probes for cached/live order, exact ids, multiplicity, cache-only neighbours, overlaps at start/middle/end, duplicates/replay, empty and one-row inputs, suppression versus disconnect and unsigned order across reconnect. Forward each snapshot's suppression/order/read evidence by identity, and assert restored rows gain no read evidence. Cancel held work and assert no publication/write.

Inject an immediate test dispatcher into existing cache-observer fixtures without changing their behavior assertions. Run all affected cache, history and identity classes plus the whole unit/shared suite. Run lint, assembleDebug, formatting and pre-verify after merging main. This is data-layer scheduling work; no new operator action, device test, scripted scenario or real-Claude scenario is required.

## Open Questions

None.
