# Conversation cache — owned legacy mutations

Split from [Conversation cache — contract](conversation-cache-contract.md).

### Owned incremental legacy mutations (#2253)

Ordinary production saves use the internal `OwnedLegacyCache` capability:
`CachingConversationRepository.ThreadWriter` calls `MigratingConversationCache.mutateLegacyRows`,
which admits the exact host/conversation/legacy family through the same import and retirement
checks as replacement writes, then calls `LegacyRoomStorage.mutateRows`. Unavailable or unobserved
imports and retired hosts still refuse writes. This storage capability changes no negotiated
route and cannot enable legacy fallback for an authoritative-only host. Full `writeThread`
replacement and file semantics remain available. History-position saves also use owned rows and
selective evidence updates (#2254), as described below. Partial readers are not activated.

A mutation carries changed cacheable rows, observed neighbour identities and explicit removed
renderer identities. Identity is typed and canonical, with length-delimited session-boundary
components; content, timestamps used for placement and unsigned durable ids do not substitute for
renderer identity. SQL predicates retain exact case-sensitive host/conversation ownership.
Updating a held row keeps its ordinal. New identities use available space between observed
neighbours without moving held rows; exhausted placement rejects the transaction. Each identity
appears once. Unobserved rows survive an incomplete view. Removal requires observed intent from
the repository, including confirmed projection disappearance or suppressed queued echoes;
omission, settlement exclusion and losing an unsettled row cannot authorize it. See
[writer ownership and capture fences](caching-conversation-repository.md#state-and-concurrency).

Schema version 4 adds the unique owner/renderer-identity index and scalar header row count via
an additive 3 → 4 Room auto-migration. Its backfill decodes existing rows once, preserves invalid
bytes with no indexed identity, and moves rows and their evidence together to spaced positive
`Long` ordinals. Old ordinal zero becomes the first positive slot. Full replacements and imports
use the same layout. Positive prefix allocation is essential: global evidence occupies ordinal
-1, so negative row positions could make a page select global cells as row-bound evidence and
withhold otherwise valid claims. Backfill is migration work, never ordinary mutation work.

Changed, removed or retention-discarded ordinals select only affected evidence, including aliased
fragment claims. Before removing any binding, the transaction follows the connected
producing-entry/claim component, including every sibling and its other producing ids. It withdraws
those claims, subtracts every affected id from received spans and records indexed exact-owner
producer losses with sibling identities. Recovery must validate every relevant sibling, including
unchanged claims and losses created during the same evidence transaction. Unrelated payloads,
ordinals and valid evidence remain exact.
Splitting spans/gaps uses `MobileJson`'s canonical unsigned model serializers: quoted numeric ids
fail the strict codec, including otherwise correct interval splits. Bounds through
`ULong.MAX_VALUE` remain exact; subtracting the maximum never wraps to zero.

Invalidation marks coverage unknown and clears saved completeness. Overflow beyond the existing
100000-row cap selects only the oldest excess rows, invalidates their affected evidence and resets
the backwards cursor/completeness conservatively. No discarded content stays certified. Ordinary
mutations read changed identities, neighbour ordinals and relevant evidence, without materializing,
decoding, serializing, deleting/reinserting or renumbering the whole saved thread. Failed validation,
placement or storage commits nothing. Expected failures retain the existing causeless/static-code
contract; diagnostics and internal counters expose no owners, identities, payloads or cursors.

A mutation changes the committed local generation. Older continuations reject with null, allowing
a new newest-page traversal to recover the entire committed snapshot. They never silently skip
rows or become daemon cursors. Local save/page exhaustion proves no server continuity or exhaustion.

#### Retained mutation and restart evidence

Linux focused runs retained in
[`owned-legacy-2253/results.json`](../../../app/src/test/resources/verification/owned-legacy-2253/results.json)
record **125 executed/passed, 0 failed, 0 skipped**. The sibling `green-*.xml` files contain the
counted results; historical failing reports and the earlier fixture patch remain alongside them.
Production revision: `74bc7a5c1cfdbad50263ab2100a849ce2c20abc0`; final dense-fixture revision:
`f92113bc4f9693bc3009af71316203b3c820901a`. The manifest distinguishes earlier runs with the
fixture-only working patch from the final committed fixture run.

| Focused selection | Executed/passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| `OwnedLegacyWritesTest` | 6 | 0 | 0 |
| `OwnedLegacyMigrationTest` | 1 | 0 | 0 |
| `OwnedLegacyConcurrencyTest` | 5 | 0 | 0 |
| `LegacyRoomStorageTest` | 18 | 0 | 0 |
| `MigratingConversationCacheTest` | 15 | 0 | 0 |
| `CoalescedThreadWritesTest` | 41 | 0 | 0 |
| `UnsignedHistoryCacheTest` | 11 | 0 | 0 |
| `HistoryMessageIdentityTest` | 17 | 0 | 0 |
| `CachedThreadWorkerTest` | 10 | 0 | 0 |
| `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence` | 1 | 0 | 0 |

`boundedInvariant_newestUpdateDoesNotScaleWithSavedHistory` measures the production repository
writer through migration-aware real on-disk Room, after import, restore and initial coverage save.
Both the 20-row and 18000-row fixtures have a producing claim and separate received span per row.
Each newest-row update records **1 row read, 1 decode, 1 serialization, 1 write; 8 evidence cells
read, 1 evidence decode, 3 evidence mutation statements and 46 SQL statements**. The probe also
rejects full saved-row/evidence reads and saved-row deletion for this update.

`ownershipInvariant_partialViewPreservesUnownedRowsAndEvidence` changes/removes loaded identities
in a 200-row owned view of 18000 saved rows, then reopens fresh database/cache/repository instances.
It compares all 17800 unloaded rows and 53400 associated evidence cells exactly, checks a second
host with colliding ids, rejects the stale token and completes a fresh traversal. The remaining
owned-write probes cover shared-claim rollback/retry and readable interval splitting, retention,
unsettled disappearance, and prepended-page evidence. The migration probe preserves held order,
untouched positions and the other host while inserting before the migrated first row. All five
concurrency methods cover held/newer writes and removal, failure/unchanged-emission/final retry,
cancellation after commit, overlapping collectors/reconnect, and deletion/host forgetting/restart.

The [verifier PASS at `54c70f599598bfefe57f556650c8c95bc490441d`](https://github.com/pyrycode/pyrycode-mobile/pull/2258#issuecomment-6244324619)
records Linux configured gates: JVM **5911 executed/passed, 0 failed/skipped**; UI **334
executed/passed, 0 failed, 1 skipped**; scripted **22 executed/passed, 0 failed/skipped**.
The dispatcher named-test report confirms all twelve owned-write/migration/concurrency methods
and `cachePolicyInvariant_100000LiveRowsAreFilteredOnlyAfterMainDelivery` passed in that full JVM
suite. The UI skip is `RenameDialogCaptureTest.renameAtFigmaViewport`, not a storage pass.
No real-Claude capture is required; other operating systems are outside this ticket's scope.

#### Owned coverage and received evidence (#2254)

`OwnedLegacyCoverageCache.writeOwnedLegacyCoverage` follows `mutateLegacyRows` through the
same migration/import/retirement admission. It receives the position, previously observed received
position and observed cacheable rows. Typed receipt differences select changed row/delta claims,
new interval portions and owned cursor/walk updates before serialization. Omitted unloaded rows
or evidence authorize no deletion; a first save owns only supplied facts. Explicit null position
retains metadata-clear semantics without deleting rows. Broad history replacement and file APIs
remain compatible, and negotiated support still forbids legacy fallback where required.

Claims bind to committed exact-owner row/delta proofs and fragment bounds/hashes through
`ULong.MAX_VALUE`. Additive producing claims with unchanged order, proof and fragment binding
preserve unchanged sibling bindings. Real withdrawals collect the connected component before
recovery selection, so same-transaction losses cannot evade sibling validation. Only fresh valid
received proofs clear their corresponding indexed producer losses; refreshing one sibling cannot
certify another stale sibling. Older boolean loss payloads remain conservative. A stale owned
proof withdraws certification while preserving readable rows. A new or changed unbound producer
outside row ownership, including an unsettled sibling sharing an old id, rejects the evidence
transaction; retry after its row settles. Malformed structures or storage failure roll back that
transaction. No failed evidence write undoes successful row bookkeeping.

Received interval additions exclude already observed portions: repeating an old span cannot heal
a producer invalidated by newer content. Select only relevant cells and proof rows; do not read,
decode or broadly replace saved evidence. Span extension and producer subtraction preserve an
existing gap's exact anchor/walk while the anchor remains inside the retained older span and its
edge names the adjacent newer span. Invalid anchors or changed edges withdraw their walks.
Recovery replays incoming gap/cursor/walk facts only beside affected recovery entries and only
after validating committed bounds. Preserve the predecessor gap sharing the older boundary.
An unchanged received position is skipped by receipt differences, so a later save cannot be relied
on to repair valid progress erased during row invalidation.

Unresolved producer loss keeps unknown/completeness conservative. Retention additionally persists
`retentionLoss` independently, even without prior metadata or a claim for the discarded row.
Incremental evidence cannot clear this unidentified-loss fence or restore backwards cursor/
completeness; recovery requires explicit metadata clear/full replacement and newly received
coverage. A local save, continuation or exhausted page never supplies received continuity, a
daemon cursor or server exhaustion. Evidence mutations also invalidate local tokens: reject an
old token and restart from newest rather than silently omitting rows.

#### Retained owned coverage and restart evidence (#2254)

Linux builder repair revision `5aac07d82b7fa482dbd65f55aa04cfaff944b45f` has **135 executed/passed,
0 failed, 0 skipped**. The documentation stage read the compressed JUnit reports and verified their
hashes against [`gap-rework-results.json`](../../../app/src/test/resources/verification/owned-coverage-2254/gap-rework-results.json).
Every selection used `python3 scripts/gradle-gate.py testDebugUnitTest --tests <selector>
--console=plain`; these are recorded runs, not tests run by documentation.

| Selection | Executed/passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| `OwnedLegacyCoverageTest` | 16 | 0 | 0 |
| `OwnedLegacyCoverageConcurrencyTest` | 9 | 0 | 0 |
| `CoalescedThreadWritesTest` | 41 | 0 | 0 |
| `HistoryDurabilityTest` | 10 | 0 | 0 |
| `UnsignedHistoryCacheTest` | 11 | 0 | 0 |
| `LegacyRoomStorageTest` | 18 | 0 | 0 |
| `MigratingConversationCacheTest` | 17 | 0 | 0 |
| `OwnedLegacyWritesTest` | 6 | 0 | 0 |
| `OwnedLegacyConcurrencyTest` | 5 | 0 | 0 |
| `OwnedLegacyMigrationTest` | 1 | 0 | 0 |
| `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence` | 1 | 0 | 0 |

All 16 coverage and nine concurrency methods are present and passed. They exercise 200 owned
rows over 18000 saved rows, preserve the other 17800 and unrelated evidence after reopening,
and isolate a second host. They cover unsigned maximum claims/fragments, shared-sibling and
same-transaction recovery, uncommitted producers, cursor/gap/unknown walks, retention, row/evidence
failure and retry, overlapping collectors/reconnect, cancellation, final flush and retirement.
The metadata-clear probe fences delayed reads without deleting rows.

After import/restore, short and fragmented 18000-row localized saves perform identical work:
45 legacy SQL statements for the retained localized row-save probe; additive claim extensions
perform 31, with one row read/decode/serialization and no row writes, as retained in
[`sibling-rework-results.json`](../../../app/src/test/resources/verification/owned-coverage-2254/sibling-rework-results.json). The final
`anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk` compares changes and suppression/
removal, repeated unchanged-position saves and restart: both sizes perform 24 legacy SQL
statements, one row read and ten evidence-cell reads. Changes decode/serialize one row; removals
neither. No full saved-row/evidence scan, broad replacement or unrelated ordinal rewrite occurs.
Its companion `anchorInvariant_subtractingAnchorWithdrawsItsInvalidWalk` passes too.

The [final verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2259#issuecomment-6248844041)
reviews `2e3a03ce3c57e9f1d6f1158fc8faf8e806a99036`. Current configured Linux JVM evidence is
**5955 executed, 5954 passed, 1 failed, 0 skipped**; the unrelated EXIF-thumbnail method passed a
separate exact-method retry (**1 executed/passed, 0 failed/skipped**), tracked by
[#2265](https://github.com/pyrycode/pyrycode-mobile/issues/2265). This does not erase the original
failure. UI records **334 executed/passed, 0 failed, 1 skipped** (`renameAtFigmaViewport` remains
unverified); scripted records **22 executed/passed, 0 failed/skipped**. The dispatcher's pass marker
names older commit `49ecc0328c9dd3328a446e6db3dc3dc556c6cacc`; current invocation evidence and the
accepted retry disposition, not that marker, support the final verdict. No real-Claude capture
is required; other operating systems are outside this Linux ticket's scope.

Historical reports remain beside the manifest: the final pre-repair capture at `cdf48b192`
records **2 executed, 1 passed, 1 failed, 0 skipped**. Earlier manifests retain the original
124-pass set, 129-pass recovery set (plus one separate migration-method pass) and 133-pass sibling
repair set at their per-selector revisions, with all earlier red captures. The migration watchdog
capture has no class XML/counts and remains failed; [#2260](https://github.com/pyrycode/pyrycode-mobile/issues/2260)
is now closed/merged, and all 17 current migration methods executed and passed.
