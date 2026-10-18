# Incremental owned legacy coverage saves (#2254)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `writeHistoryPosition`, `ThreadWriter`, `PersistedThread`, snapshot capture and deletion fences.
- `app/src/main/java/de/pyryco/mobile/data/cache/OwnedLegacyCache.kt`: owned row capability, renderer identity and successful baseline application from #2253.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: exact-family import admission and retirement fencing.
- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: owned row transactions, affected-producing-claim invalidation, interval repair and evidence encoding.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: indexed owner/identity, ordinal and evidence queries.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: received unsigned intervals, proof binding, fragments and validation.
- `app/src/test/java/de/pyryco/mobile/data/cache/OwnedLegacyWritesTest.kt` and `OwnedLegacyConcurrencyTest.kt`: production-to-disk counters, restart and controlled concurrency patterns.
- `docs/knowledge/features/conversation-cache-contract.md` and `caching-conversation-repository.md`: omitted content is unowned; capture generations and successful coverage revisions serve different purposes; row success must survive later evidence failure.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md` and `docs/specs/architecture/2161-import-saved-threads-room.md`: no-backup Room custody, separate legacy family and import-unobserved protection.

Overlapping branches #2175 and #2255 change authoritative routing/writes in shared files. This ticket keeps legacy changes local and additive; neither supplies a dependency.

## Context

Ordinary row saves are incremental after #2253. Coverage saves still replace every reconciled row, then decode every saved row and replace all evidence. This ticket changes that single production save behaviour while retaining the full replacement and file contracts. It activates no partial reader and changes no negotiated route. ADR 0009 already covers the storage choice; no new decision record is needed.

## Design

Extend the internal owned capability with an evidence save carrying the received position, the previous observed position and observed rows. Keep the broad `ConversationCache` operations and replacement-only fallback unchanged. The migration wrapper admits this operation through the same exact host/conversation/legacy import and retirement checks as owned rows.

The repository remembers observed received coverage separately from successful rows. Seed it from existing restore/metadata reads, without adding a full read to a localized save. Compare typed coverage collections before serialization. The evidence mutation includes only changed row claims, interval additions and owned cursor/walk changes; omission outside the observed rows is not removal. A first save owns only the facts it actually supplies. A successful evidence write advances its evidence baseline; a failure retains retryable differences.

Coverage saves reconcile against held rows and the successful row baseline, derive changed cacheable renderer identities and explicit observed removals, and use `mutateLegacyRows`. Updating a row keeps its ordinal; omitted unloaded rows remain held. Use the existing neighbour allocation, retention cap and affected-evidence invalidation. Publish the actual successful row baseline, capture satisfaction and incremented coverage revision together before attempting evidence. Preserve newer successful rows when a delayed capture resumes.

Room selects only changed evidence keys, relevant unsigned intervals and the rows needed to check their proofs. Reuse exact row/delta and fragment binding. Never rebind an unchanged stale claim to changed content. Interval additions are received differences, not the incoming full interval set: an old interval included again by a delayed save cannot heal a producer invalidated by a newer row mutation. Merge those additions with intersecting/adjacent committed intervals and repair only relevant gaps and walks. Keep unrelated cells exact. Cursor/walk removals require prior observation; absence from a partial view cannot clear unrelated anchors.

Retain scalar high-water/unknown semantics conservatively. Retention or invalidated content clears completeness and cannot be reversed merely by a position write. Explicit null position keeps metadata-clear semantics without row deletion. Evidence mutations change the local generation so old continuation tokens reject and recover through a new newest-page traversal. Local continuation/exhaustion supplies no daemon cursor or received continuity.

## State and concurrency model

No new scope, flow or background job. Existing caller-owned processing runs on the injected dispatcher. Lock order remains repository `historyWrites`, migration mutex, storage mutex, Room transaction. Snapshot capture occurs before suspending processing or lock acquisition. Once a row mutation starts, its result and successful row bookkeeping finish in `NonCancellable`. Evidence commits separately after rows; failure leaves conservative committed evidence. Deletion tombstones before waiting and host retirement refuses delayed writes.

## State transitions and identity reuse

| Event | Required probe |
| --- | --- |
| 200 observed rows over 18000 saved rows; localized change/removal; restart and second host | `OwnedLegacyCoverageTest.ownershipInvariant_partialCoveragePreservesUnloadedRowsAndEvidence` |
| Same localized coverage change on short and fragmented 18000-row histories | `OwnedLegacyCoverageTest.boundedInvariant_coverageSaveDoesNotScaleWithSavedHistory` |
| Maximum unsigned producer, shared claims, fragments and delayed stale evidence | `OwnedLegacyCoverageTest.certificationInvariant_changedProducersCannotRegainStaleClaims` |
| Cursor refusal, gap fill and unknown-walk changes | `OwnedLegacyCoverageTest.anchorInvariant_ownedUpdatesPreserveUnrelatedAnchors` |
| Retention removes previously certified content | `OwnedLegacyCoverageTest.retentionInvariant_coverageCannotRecertifyDiscardedRows` |
| Row failure, evidence failure, retry and row-before-evidence restart | `OwnedLegacyCoverageTest.failureInvariant_rowsAndEvidenceAdvanceSeparately` |
| Held coverage versus newer rows/removals, overlapping collector and reconnect | `OwnedLegacyCoverageConcurrencyTest.captureInvariant_delayedCoverageCannotUndoNewerRows` |
| Cancel after row commit; newer removal matches original restore; evidence fails | `OwnedLegacyCoverageConcurrencyTest.revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` |
| Final flush, confirmed deletion and host forgetting while coverage is held | `OwnedLegacyCoverageConcurrencyTest.retirementInvariant_coverageCannotResurrectRemovedOwners` |
| Null position and local token invalidation/recovery | ownership/anchor probes plus retained `LegacyRoomStorageTest` regressions |

## Error handling

Reuse classified `Result<Unit>` failures with causeless static diagnostics. Invalid proof, unsigned interval, placement or storage failure rolls back its transaction. Failed rows advance no successful baseline or evidence. Failed evidence advances no evidence baseline and cannot undo successful row revision bookkeeping. Cancellation before mutation remains cancellable; started row mutation completes with bookkeeping. No payload, identity, owner, proof, cursor or path enters logs.

## Testing strategy

Write failing production repository-to-real-disk-Room probes first. Count SQL plus storage reads, decodes, serializations and writes after initial import/restore, comparing identical owned work against short and 18000-row fragmented histories. Reopen database/cache instances for durable assertions; compare untouched evidence cells exactly. Controlled suspend hooks expose the commit/bookkeeping and row/evidence boundaries. Probe each ticket invariant with reordered/overlapping/duplicate inputs and repeated identities where applicable. No device test or real-Claude capture is needed for this storage behaviour.

Run affected new classes plus `CoalescedThreadWritesTest`, `HistoryDurabilityTest`, `UnsignedHistoryCacheTest`, `LegacyRoomStorageTest`, `MigratingConversationCacheTest` and the migration regression on Linux. Retain counted raw reports and the tested revision. Run lint, assembly, formatting and final `pre-verify.py --gradle` after merging main; complete suites belong to the dispatcher.

## Open Questions

None. Implementation details must retain the received-difference versus committed-content distinction above.

Sizing: one independently useful behaviour (incremental production coverage save), including its storage plumbing and regression floor. Forecast approximately 1450 written lines including this plan, implementation and focused probes; at most two internal declarations, two production consumers and eight rejection branches. Three acceptance criteria. Recount before handoff.

## Documentation handoff

Pending for documentation stage: update owned coverage, evidence/removal and failure/concurrency contracts in `docs/knowledge/features/conversation-cache-contract.md` and `docs/knowledge/features/caching-conversation-repository.md`, including successful row baseline versus later evidence failure and local continuation versus daemon coverage. Record counted builder results.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design. Received metadata must be checked against committed exact-owner row/delta/fragment proofs; unchanged stale input cannot restore invalidated producers. Typed differences and indexed proof checks enforce this boundary.
- Tokens/secrets: no credential or key handling is added. Opaque daemon cursors remain private metadata, excluded from diagnostics.
- Files/storage: reuse app-private no-backup Room and its sidecars, exact SQL owner predicates, existing import ledger and transactions. No caller-controlled filesystem paths or change to the accepted plaintext conversation-cache threat model.
- Android attack surface: no manifest, component, intent, provider, WebView or UI change.
- Cryptography: no change to Noise, key custody or nonce handling; existing SHA-256 content proofs remain binding checks, not authentication claims.
- Network/I/O: no wire or network change, new request or widened frame. Unavailable/unobserved imports continue refusing writes.
- Errors/logs: reuse static classified operation/failure events and redacted internal records/counters; no content, proofs, owner ids or cursors in exceptions or telemetry.
- Concurrency: MUST FIX addressed in design. Started row commit and successful baseline/revision publication are indivisible under the existing lock; later evidence failure cannot roll back the in-memory revision. Snapshot fences reject delayed collectors; removal/retirement wins over cleanup.
- Threat model: relay reorder/drop/flood and hostile frames remain behind existing decoding/Noise boundaries; this change trusts only received typed facts bound to committed rows. Rooted-device plaintext cache access and existing screenshot/accessibility exposure remain within the unchanged product threat model; no new credential or UI surface is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-18
