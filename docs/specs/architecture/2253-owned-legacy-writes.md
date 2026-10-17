# Owned incremental legacy saves (#2253)

## Files read

- `data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `persistedThreads`, snapshot capture, coverage writes and deletion; reuse the serialized writer and successful bookkeeping boundary.
- `data/cache/ConversationCache.kt`: replacement and settlement contracts stay available.
- `data/cache/MigratingConversationCache.kt`: `write` preserves unavailable/unobserved import and host retirement fences.
- `data/cache/LegacyRoomStorage.kt`: `replace`, `storeHistory`, pages and classified transactions; preserve canonical codecs and separate evidence.
- `data/cache/ConversationCacheDatabase.kt`: `LegacyDao`, owner-scoped rows, evidence and headers.
- `data/cache/FileConversationCache.kt`: canonical row encoding, validation and proofs.
- `data/repository/HistoryCoverage.kt`: `retainedBy`, producing claims and unsigned interval subtraction.
- `docs/knowledge/features/conversation-cache-contract.md`: exact ownership, conservative evidence and independent authoritative family.
- `docs/knowledge/features/caching-conversation-repository.md`: capture order, fixed connection base, suppression, failed-write retry and non-cancellable mutation/bookkeeping.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md` and `docs/specs/architecture/2161-import-saved-threads-room.md`: no-backup Room custody, import refusal and cancellation lessons.

Overlap: #2175 changes authoritative routing in `CachingConversationRepository`; legacy edits are local and independent.

## Context

Ordinary saves currently replace every saved row and load all evidence. A view owns only identities it observed, so replacement also cannot support an incomplete view. Ordinary saves will calculate owned changes in the existing writer and commit them through the migration-aware cache. Full replacements, file compatibility, full-view coverage saves and route negotiation remain available. No new reader or decision record is needed.

## Design

Add an internal owned-write capability beside `ConversationCache`. Its mutation contains changed cacheable rows with observed neighbour anchors and deliberate removed identities. It is consumed by `ThreadWriter` and implemented by `MigratingConversationCache`; other caches keep replacement semantics. Row identity follows the canonical renderer identity, with typed prefixes and unambiguous boundary components. Exclusion or an unsettled identity present in raw drawn rows is never a removal. Only disappearance from an observed drawn baseline is deliberate; reconciliation and suppression remain upstream of mutation calculation.

Under `historyWrites`, compare the candidate against the shared successful baseline, reject older captures, calculate changed rows and removals, and call the owned capability. Advance the capture fence and baseline only after success, within the existing `NonCancellable` boundary. Preserve coverage revision bookkeeping and full replacements in `writeHistoryPosition`.

Room gains an indexed renderer identity on legacy rows and a scalar row count on legacy headers, through an additive version-4 auto-migration. Its post-migration hook backfills identities with the existing decoder and assigns spaced held ordinals to rows and evidence together. Full replacements/imports create the same layout. Existing rows keep ordinals on update/removal; new rows use observed neighbours and available integer space, rejecting an impossible placement rather than silently misordering. Local continuation generation changes on mutation, so stale tokens explicitly reject and a newest traversal recovers.

`LegacyRoomStorage` reads only changed identities, neighbour ordinals and affected evidence. Changed or removed row ordinals identify producing claims, including aliased fragments. Invalidate those claims and any dependent claim sharing a removed producing id atomically; subtract producing ids from intersecting spans and repair only adjacent gap evidence. Preserve unrelated payloads/evidence exactly. Mark coverage unknown and completeness conservative after invalidation; never create daemon cursors or certification. Enforce the existing cap with oldest-row selection only on overflow; retained certification cannot refer to discarded content. Internal mutation counters report rows read/decoded/serialized/written and evidence work independently of initial restore/import.

## State and concurrency model

No new scopes, flows or jobs. Collection-owned writer, coalesced pending slot and injected dispatchers remain. Lock order is repository `historyWrites`, cache mutex, storage mutex, Room transaction. Mutation and successful baseline finish together after starting. Cache import/refusal and retirement checks remain on the owned path. Confirmed deletion fences before waiting for writes; host forgetting wins after held work and refuses final writes.

## State transitions and identity reuse

| Event | Focused probe |
| --- | --- |
| Partial owned update/removal, colliding second host, reopen | `OwnedLegacyWritesTest.ownershipInvariant_partialViewPreservesUnownedRowsAndEvidence` |
| Newest update with short/18000 saved rows | `OwnedLegacyWritesTest.boundedInvariant_newestUpdateDoesNotScaleWithSavedHistory` |
| Affected producing claims, unsigned maximum, token rejection/recovery | `OwnedLegacyWritesTest.ownershipInvariant_partialViewPreservesUnownedRowsAndEvidence` |
| Retention overflow | `OwnedLegacyWritesTest.retentionInvariant_discardedContentCannotRemainCertified` |
| Held write, newer candidate/removal, coalescing | `OwnedLegacyConcurrencyTest.orderingInvariant_newerOwnedChangesWinHeldWrites` |
| Failure, unchanged emission and final retry | `OwnedLegacyConcurrencyTest.failureInvariant_unchangedEmissionAndFinalFlushRetry` |
| Cancellation after commit | `OwnedLegacyConcurrencyTest.cancellationInvariant_committedWriteAndBaselineFinishTogether` |
| Overlapping collectors and reconnect | `OwnedLegacyConcurrencyTest.captureInvariant_oldCollectorsCannotUndoNewerChanges` |
| Held write followed by confirmed deletion/forgetting | `OwnedLegacyConcurrencyTest.retirementInvariant_removalWinsHeldWritesAndRestart` |
| Existing version-3 storage upgraded | `OwnedLegacyMigrationTest.migrationInvariant_existingRowsGainOwnedIndexWithoutChangingHeldOrder` |
| Unsettled replacement, disconnect and a different settled row | `OwnedLegacyWritesTest.settlementInvariant_unsettledRowCannotRemoveSavedIdentity` |

## Error handling

Reuse causeless classified `Result` failures and static diagnostics. No owner, payload, identity, cursor or path enters logs. Invalid mutation/placement rolls back. Failure advances no successful baseline. Import unavailable/unobserved and host retirement remain refusals, including final flush. Cancellation outside started mutation propagates normally.

## Testing strategy

Write focused real on-disk Room/repository probes first and observe red before implementation. Use existing `beforeCommit` controls for holds/failure and fresh database/cache/repository instances for durability. Count mutation-only work after restoration/import; compare short and 18000-row histories. Retain and run `CoalescedThreadWritesTest`, `MigratingConversationCacheTest`, `LegacyRoomStorageTest` and affected unsigned/identity/coverage compatibility classes. Run Linux lint, assembly, formatting and final `pre-verify.py --gradle`. Preserve counted XML and raw reports. Full JVM/UI/scripted acceptance belongs to the dispatcher; no live Claude evidence or device-only test is required.

## Open Questions

None. Placement exhaustion rejects the mutation; readers recover stale local tokens from newest.

Sizing: one useful production behaviour, three acceptance criteria, at most three new exported declarations and three production consumer sites. Forecast approximately 600 production lines, 700 test lines, 650 generated schema lines and this plan (about 2030 total). This exceeds 1600 including additive schema output. #2253 is a grandchild (#2068 → #2249 → #2253); the split-depth rule requires continuing. The storage capability has one production consumer and cannot be split from it under the floor rule.

## Documentation handoff

Pending documentation stage: update owned-row, affected-evidence invalidation, removal and failure/concurrency contracts in `docs/knowledge/features/conversation-cache-contract.md` and `docs/knowledge/features/caching-conversation-repository.md`. Record counted results and explain that full-view coverage saves remain until their separate optimization.

## Security review

**Verdict:** PASS

- Trust boundaries: owned changes originate from reconciled repository rows; canonical cache validation and exact host/conversation SQL predicates enforce admission. Excluded/unsettled rows cannot authorize removal.
- Tokens: no credentials added. Local continuations remain owner/generation-bound and cannot become daemon cursors.
- Files/storage: reuse app-private `noBackupFilesDir` Room and sidecars; existing plaintext cache threat model is unchanged. Transaction covers row changes, affected evidence and header. Migration preserves data rather than rebuilding storage.
- Android surface: no components, intents, providers or UI added.
- Cryptography: no new primitives; existing proof hashes and unsigned strings remain exact.
- Network/I/O: no transport or negotiation changes; authoritative-only hosts gain no legacy fallback. Indexed mutation work avoids full saved-thread materialization.
- Errors/logs: SHOULD FIX: counters and lifecycle diagnostics expose counts/static codes only; test sentinel owners/content against logs and errors.
- Concurrency: SHOULD FIX: advance the shared capture fence for ordinary successes, and finish mutation plus baseline under cancellation. Held-write, overlapping-collector and retirement probes cover stale resurrection.
- Threat model: delayed/reordered daemon data stays under existing reconciliation/negotiation; no relay plaintext exposure. Rooted-device storage access and UI screenshot leakage retain existing scope; no new surface.

**Reviewer:** builder self-review
**Date:** 2026-10-17

## Revisions

### 2026-10-17: stale content reused by a later capture

`OwnedLegacyConcurrencyTest.captureInvariant_oldCollectorsCannotUndoNewerChanges` failed after a newer collector saved text and an older collector resumed, reconnected and received a different identity. Fencing only the candidate's capture rejects its first stale save but treats its unchanged old text as a new change in the later candidate. Each candidate must therefore carry the capture of each actual row change and deliberate removal. Only changes newer than the shared successful fence are eligible. Mutation bookkeeping retains newer baseline content for rows a candidate did not change; a new capture alone cannot refresh stale content. This strengthens the concurrency finding in the security review.

The additive migration widens legacy ordinals to `Long` (SQLite keeps INTEGER affinity), preserving old ordinal zero and multiplying the held spacing together with evidence ordinals. Invalid row bytes survive with no indexed identity; existing graceful reads still withhold them. Mutation rejects exhausted neighbour space instead of silently moving held rows. Full-view coverage saves bind evidence to the actual held ordinals after mutation.

The strengthened settlement probe also failed when a saved identity became unsettled, disappeared on disconnect and a different settled row arrived. Comparing only the final candidate with disk incorrectly classified that disappearance as deliberate removal. Removal intents now come from cacheable identities present in the preceding observed raw view and absent in the next raw view. Settlement exclusion never creates an intent. An intent survives coalescing/failure until a settled reappearance or a successful newer fence supersedes it. Successful baseline application preserves chronological neighbour slots and the existing retention cap.

The migration probe's second insertion into the same held gap exposed immediate ordinal exhaustion when the first insert used the next integer. Placement now divides available space among the pending rows sharing that older boundary, retaining space on either side for later owned changes without moving existing ordinals. Restored identity indexes initialize lazily during worker acceptance, after cached first draw, preserving the existing processing boundary.
