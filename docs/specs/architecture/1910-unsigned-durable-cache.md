# #1910 — Unsigned durable coverage and cache restoration

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: `received`, `boundTo`, `retainedBy`, interval removal, and signed display helpers establish coverage and retention.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `ReducedHistoryPage`, `mergeRows`, `mergeCachedRows`, and `receivedHistoryOrder` already have unsigned reduction/ordering beneath signed cache adapters.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadSnapshotSource.kt`: `ThreadSnapshot.unsignedHistoryOrder` supplies rows and unsigned order atomically.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeMessages` and `writeHistoryPosition` restore fixed merge bases and write rows before state under `historyWrites`.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: thread records, header reads, atomic replacement, and position writes preserve host/conversation isolation and retention.
- `HistoryCoverageTest`, `UnsignedHistoryTest`, `HistoryCacheReworkTest`, `FileConversationCacheThreadTest`, and `CachingConversationRepositoryTest`: signed fixtures, partial fills, legacy rows, trimming and failed-write safeguards.
- `docs/knowledge/features/conversation-cache.md`: saved position and two-writer rules require exact content proofs and untrimmed caller input.
- `docs/knowledge/features/caching-conversation-repository.md`: disjoint gap fills need persisted durable order, including equal timestamps; fixed merge bases prevent resurrection.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: unknown legacy coverage closes only on terminal history; gaps close only by joining received spans.
- Sibling `pyrycode/docs/protocol-mobile.md`, “A history entry” and “Joining a page to the live stream”, resolved through `PYRYCODE_SRC`: authoritative wire contract.

## Context

#1909 provides exact unsigned received history identities and atomic projection order. Persistence and restoration still use signed coverage, losing those claims above the signed boundary. This ticket migrates the cache/coverage contract while preserving signed UI consumers for #1911. No decision record is needed.

Forecast: 1100–1400 total written lines including replacements, tests and plan; four production files, two new exported interval types, fewer than ten existing consumer updates, three acceptance criteria and at most six metadata rejection categories. No in-flight feature branch overlaps the planned production files.

## Design

`UnsignedHistorySpan` and `UnsignedHistoryGap` represent exact positive unsigned endpoints. `HistoryCoverage` stores unsigned spans, gaps, row/delta order and entry sets, cursor boundaries and walk anchors. Its signed secondary constructor and signed properties preserve existing lower-range fixtures/readers. Persisted field names remain compatible with old positive numeric metadata. The unsigned unknown state is distinct from conservative signed uncertainty; upper-range evidence sets sticky `unsignedIncomplete` so unmigrated UI cannot certify omitted signed content. Existing legacy omitted-content uncertainty stays conservative.

`receivedUnsigned` accepts an unsigned target; signed `received`, `cursorFor`, and `refused` delegate only representable anchors. Reduction consumes #1909's unsigned claims. Normalize overlaps/adjacency without incrementing the maximum. Removing a retained row's maximum claim terminates the remaining interval instead of wrapping to zero. Gap splitting retains an anchor in each immediately older received span. Unknown legacy coverage without an older anchor closes only on `atStart`.

Cache-only unsigned merge/order adapters use the existing reconciliation engine. Both wrapper merge paths consume restored unsigned coverage and the snapshot's authoritative unsigned order. Held-row order, delta deduplication, suppression and connection-boundary rebasing retain their contracts.

File reads decode rows independently of optional history metadata. Invalid numeric/structural metadata yields no saved position with a static diagnostic, while valid retained rows remain readable and the wrapper treats them as legacy unknown. Validate intervals/claims/cursor anchors and retention before admitting saved claims. Existing positive signed documents deserialize without a bulk format rewrite. Writes retain atomic replacement, row-before-state ordering, trim resets and app-private host/conversation paths. No raw excluded envelopes, checkpoints, queues or commands are added.

## State and concurrency model

No new jobs, scopes, flows or dispatchers. The wrapper's collector owns cancellation, fixed merge bases and atomic projection snapshots; `historyWrites` serializes row/state writes. File-cache operations retain their per-instance mutex, injected I/O dispatcher and atomic rename. Connection shutdown behavior is unchanged.

## Error handling

Malformed optional history is discarded independently from valid rows, using content-free static logs. I/O mutations retain `Result<Unit>` with existing domain failures. Failed row writes cannot publish new claims; stale writers and trimming remove claims through the same unsigned retention calculations.

## Testing strategy

Write and execute a failing metadata round-trip regression first. Unit invariant probes then exercise signed-boundary/max overlap and adjacency, maximum removal, reversed arrival/replay/empty pages, split deltas, unresolved and partially filled gaps across fresh cache instances, legacy unknown terminal handling, host/conversation isolation, stale writes, trimming and failed writes. Assert restored order and that observation/state writes trigger no history request or read command. Retain existing lower-range fixture and legacy-cache coverage, updating only assertions that intentionally depended on declined upper claims.

Run focused coverage/cache/reconciliation/projection tests with nonzero counts, lint, assembleDebug and forced Spotless. After final main merge and push run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`. This data-only ticket needs no new Compose/device or real-Claude scenario.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/conversation-cache.md`, “The saved history position”: unsigned metadata and positive signed legacy compatibility.
- `docs/knowledge/features/caching-conversation-repository.md`: unsigned restore/order contract and conservative signed UI view.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, saved-position section: unsigned coverage, cursor/walk anchors and legacy compatibility.

## Revisions

- 2026-10-07: Keep the primary constructor's required unsigned spans last, avoiding JVM erasure collision with the defaulted signed secondary constructor. Legacy documents that omitted empty spans receive an empty default in optional metadata decoding. Signed properties are transient immutable projections computed once per coverage instance, avoiding repeated map construction in existing display consumers.

- 2026-10-07: The malformed-metadata invariant probe exposed an admitted order id outside its row's producing-entry set. Disk validation now requires that relationship in addition to span membership and retained proofs; the probe remains unchanged. Span membership uses binary search over normalized spans to keep validation proportional to claims times log spans rather than claims times spans.

- 2026-10-07: Verifier finding 1 showed that whole-row proofs admitted overlapping, out-of-bounds or mismatched legacy delta slices. One shared binding validator now requires overflow-safe bounds, non-overlapping slices in unsigned durable order and matching fragment hashes. Optional metadata decoding rejects invalid legacy bindings independently of rows; `retainedBy` and `boundTo` remove claims that fail the same checks. Probes cover disk restore, stale writes and valid controls at lower ids, the signed boundary and uint64 maximum.

- 2026-10-07: Verifier finding 2 identified duplicate persisted-identity resolution in the signed and unsigned restore adapters. Both now delegate to one generic private resolver, preserving their existing numeric contracts while sharing delta splitting, identity hashing and lookup.
