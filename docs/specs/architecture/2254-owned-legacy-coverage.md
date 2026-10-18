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
| Span extension preserves an existing received gap anchor/walk | `OwnedLegacyCoverageTest.anchorInvariant_extendingSpanKeepsReceivedGapWalk` |
| An unsaved streaming producer shares an old ID or introduces a new ID; settle and retry | `OwnedLegacyCoverageTest.certificationInvariant_uncommittedProducerWaitsForCommittedRow` |
| Retention removes previously certified content | `OwnedLegacyCoverageTest.retentionInvariant_coverageCannotRecertifyDiscardedRows` |
| Row failure, evidence failure, retry and row-before-evidence restart | `OwnedLegacyCoverageTest.failureInvariant_rowsAndEvidenceAdvanceSeparately` |
| Held coverage versus newer rows/removals, overlapping collector and reconnect | `OwnedLegacyCoverageConcurrencyTest.captureInvariant_delayedCoverageCannotUndoNewerRows` |
| Snapshot emission precedes suspending cleanup and newer completed, delivered or accepted work | `OwnedLegacyCoverageConcurrencyTest.captureInvariant_firstSnapshotCleanupCannotRenewOldCoverage` |
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

- Trust boundaries: MUST FIX addressed in design. Received metadata must be checked against committed exact-owner row/delta/fragment proofs; unchanged stale input cannot restore invalidated producers. Producer-loss cells retain every invalidated sibling identity, so recovery validates unchanged relevant claims too, including losses created by the current evidence transaction. Withdrawals collect the connected producing entries before removing bindings; additive claims with unchanged binding preserve sibling evidence. Retention without a producing claim has its own durable scalar fence; incremental evidence cannot discharge that unidentified loss.
- Received gap progress: producer subtraction preserves a previously received anchor/walk only while its anchor belongs to the retained older span and its edge is the adjacent newer span's start. Invalid anchors/edges lose their walks. This preserves unrelated received facts without restoring invalidated producer certification.
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

## Revisions

### 2026-10-18: accepted rows and recoverable invalidation

The initial implementation review found two ordering gaps before handoff. A coverage capture can omit a newer accepted row whose coalesced write has not started, and a successful coverage revision can precede a newer removal matching the collection's original restore. Remember per-row accepted intents with their original captures, merge eligible intents into the coverage base without pre-trimming, and retain the revision-based deliberate-removal exception in the owned writer. `heldInvariant_coverageIncludesNewerAcceptedRowsBeforeSatisfyingTheirCapture` and `revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` cover these boundaries with real Room.

Invalidated producing IDs use exact-owner indexed `losses` evidence cells in the existing flexible evidence table. A scalar existence query preserves conservative unknown/completeness after row or retention loss without reading all losses. Only accepted new content-bound producing claims clear their corresponding cells; full replacement and explicit metadata clear retain their existing semantics. This avoids permanently withholding completeness after valid new received proofs. `recoveryInvariant_receivedProofClearsOnlyItsOwnLoss` checks partial and complete recovery. `fragmentInvariant_ownedUpdatesKeepExactUnsignedBindings` checks maximum unsigned fragment bindings and changed content. No schema, public wire contract or Android surface changes.

Security review rechecked both changes: accepted intents retain original capture fences, and loss deletion requires exact-owner, committed row/delta proofs. Neither local row persistence nor a local continuation clears a loss.

### 2026-10-18: received gap anchors and upstream cleanup

Two adversarial probes failed before repair. `anchorInvariant_extendingSpanKeepsReceivedGapWalk` showed span extension moving a still-valid gap anchor and discarding its walk; interval repair now preserves an existing anchor/walk while its edge and retained older-span membership remain valid. `captureInvariant_firstSnapshotCleanupCannotRenewOldCoverage` showed `first()` cleanup suspending before capture allocation, allowing old content to overwrite a newer successful save. Capture now occurs at emission. Its three modes cover a newer save, newer suspended delivery and newer accepted rows: satisfaction includes only captures whose intents actually contributed to persisted rows, and newer accepted intents take precedence over the older snapshot. Indexed loss cells also exclude invalidated IDs from interval additions until fresh committed proofs admit them.

Security review rechecked conservative certification and capture ordering against these failures; no new trust boundary or public wire/storage schema is introduced.

Stale content-proof mismatch is a conservative owned-claim withdrawal rather than a transaction failure: invalidate it while retaining readable rows. Malformed structures and classified storage failures still roll back the evidence transaction. Neither outcome undoes the successful row revision.

Corrected final scope recount: 1992 inserted/deleted text lines, including the 337-line generated verification manifest. The earlier 1683-line count was an arithmetic error. GitHub parentage is #2068 → #2249 → #2254, so the split-depth rule requires continuing this grandchild with `needs-human:sizing`. A first-emission capture/fence repair could have shipped against the full writer separately; the owned evidence plumbing remains with its sole production consumer. Required ordering, restart and bounded-work probes remain intact.

### 2026-10-18: uncommitted producing rows

`certificationInvariant_uncommittedProducerWaitsForCommittedRow` failed when a streaming row's received claim advanced completion before that row was eligible for persistence. Refuse the evidence transaction when an unbound producer introduces a new ID or a changed claim outside row ownership, including a sibling sharing an already-received ID. Preserve the successful row baseline/revision and prior conservative metadata; retry the same received position after the row settles. The probe covers both producer identities and restart. Security review rechecked that this rejection runs before evidence mutation, uses a static classified error, and adds no saved-row or saved-evidence scan. Existing stale owned-proof withdrawal remains conservative.

### 2026-10-18: verifier recovery and accepted-intent repairs

Findings 1 and 2 repair the existing conservative-certification invariant. Persist `retentionLoss` independently of producing-ID losses, including when no earlier coverage header exists. Incremental coverage never clears this fence or restores the backwards cursor/completeness. Recovery of unidentified discarded content requires the existing explicit metadata-clear/full-replacement contract and newly received coverage; repeating a retained position is insufficient. `retentionInvariant_uncertifiedDiscardSurvivesRestartAndLaterEvidence` exercises the production repository and real Room with 100000 rows, no oldest-row claim, repeated evidence writes and restart.

Producer-loss cells also retain the invalidated sibling keys using a JSON array in their existing private payload. When a changed claim touches a lost producer, load only that producer's indexed loss cell, include all its siblings in committed-proof validation, and keep its loss until every relevant sibling binds. Missing or unchanged stale proofs block shared recovery; unrelated cells remain exact. Existing boolean loss payloads remain readable and conservative. `recoveryInvariant_sharedProducerRequiresEverySiblingProof` proves partial-refresh refusal across restart followed by complete recovery. Security self-review rechecked exact-owner selection, fragment aliases, unsigned IDs, private no-backup storage and static diagnostics; no new boundary or schema is introduced.

Finding 3 bounds accepted-intent custody to pending work: after either row writer publishes success, remove entries at or below its successful capture fence using conditional map removal, preserving concurrently replaced newer intents. Acceptance also refuses intents already satisfied while it was preparing them. Row failure preserves retries; evidence failure cannot restore satisfied payloads. The three intent probes below cover ordinary/coverage success, concurrent acceptance, row failure/retry and evidence failure against real Room. This resource-lifetime repair could ship independently, but the existing grandchild split-depth exception requires continuing all review findings here; no acceptance or verification floor is reduced. Forecast for this repair: approximately 300 additional written lines, no public declarations or signature changes.

| Rework event | Required probe |
| --- | --- |
| Uncertified retention discard, reopen, repeated evidence saves and explicit full-metadata recovery | `retentionInvariant_uncertifiedDiscardSurvivesRestartAndLaterEvidence` |
| Shared producer receives only one fresh sibling proof, then every fresh proof, across restart | `recoveryInvariant_sharedProducerRequiresEverySiblingProof` |
| Ordinary/final-flush row success or coverage row success followed by evidence failure | `intentInvariant_successPrunesOnlySatisfiedRows` |
| Failed rows followed by successful retry | `intentInvariant_rowFailureKeepsRetryableWork` |
| New identity or newer replacement of the same identity accepted during an older ordinary/coverage commit | `intentInvariant_concurrentAcceptanceSurvivesOlderSuccess` |

Rework verification on Linux at `c5529900e3f772398c899f97576ce73d599d7898`: latest affected selectors executed/passed 129, failed/skipped 0. The exact migration cancellation method also passed separately (1 executed/passed). An earlier unchanged migration-class attempt hit its 180-second watchdog and 30-second interrupt grace, with no class XML/counts available; raw binary events/results and its thread dump remain a failed capture, tracked in #2260 (priority:low, Backlog). No assertion was ignored or timeout increased. `app/src/test/resources/verification/owned-coverage-2254/rework-results.json` retains commands, revisions, counts, historical red results and artifact hashes. Documentation handoff also includes unidentified retention loss, recovery of every shared sibling and successful-intent retirement.

Final rework scope recount: 2560 inserted/deleted text lines against main, including the original 337-line and new 250-line generated verification manifests. The existing #2068 → #2249 → #2254 grandchild exception and `needs-human:sizing` remain; the required recovery, concurrency, restart and bounded-work verification floor is retained.

### 2026-10-18: same-transaction sibling custody (verifier finding 1)

This repairs the same content-certification invariant, with its regression, rather than adding a separate behaviour. A claim-only extension must preserve unchanged siblings; a real withdrawal must account for every producing entry whose binding it removes. `invalidateOwnedEvidence` follows the indexed producing-entry/claim connections to a fixed point before deleting any claim and creates a loss for each affected entry, including unsigned maximum IDs. It reads only this connected evidence component.

`writeOwnedCoverage` applies necessary withdrawals before expanding recovery candidates, so newly created losses participate in the existing sibling-proof checks. Memoized exact-identity row lookups serve both withdrawal and validation. A received additive claim with unchanged order, proof and fragment binding does not withdraw existing evidence; it still passes committed-row proof validation before acceptance. All other withdrawals retain conservative loss until every relevant sibling binds. Transaction rejection rolls these evidence changes back together; successful row bookkeeping remains independent.

| Rework event | Required probe in `OwnedLegacyCoverageTest` |
| --- | --- |
| Claim-only extension, unchanged/unloaded sibling, restart, then sibling change/removal; identical short/18000-row fragmented save work | `recoveryInvariant_claimExtensionPreservesSiblingBindings` |
| Withdrawal creates shared losses during this evidence transaction; recovery includes unchanged siblings or keeps their entries uncertified | `recoveryInvariant_currentWithdrawalValidatesEverySibling` |

Security self-review: MUST FIX addressed at `writeOwnedCoverage`/`invalidateOwnedEvidence`. New losses are visible before recovery selection, connected entries retain sibling custody, and additive replacement cannot silently remove a sibling's binding. Exact owner predicates, no-backup custody, fragment/unsigned proofs, static diagnostics, cancellation, retirement and all other reviewed boundaries remain unchanged. No new public declaration, schema, scope, job or consumer signature. Forecast: about 250 additional written lines across production, regression tests, evidence and this revision; the existing grandchild sizing exception continues without reducing verification.

Documentation handoff additionally covers same-transaction losses, connected producing-entry invalidation and preserved unchanged sibling bindings in the two existing cache topics.

The first repaired candidate passed the twelve existing coverage methods and preserved the previously missing sibling cells. Both new methods then exposed a fixture expectation error: unsigned-maximum coverage deliberately suppresses saved `atStart`. The extension/completeness probe now uses fragmented lower-range IDs so it still requires completeness before loss and its removal afterward. The separate connected-maximum probe explicitly requires `unsignedIncomplete` and conservative saved `atStart` while comparing every received evidence field. No production contract or assertion about missing/stale bindings was relaxed; both historical failures remain archived.

The corrected probes then passed 13/14 methods and exposed one remaining recovery consequence: temporarily withdrawing a connected component recreates its gap at a different anchor, while unchanged received gap/walk facts are skipped by the receipt-difference filter. Revalidate only incoming gaps adjacent to spans containing indexed recovery entries, and replay their received cursor/walk anchors only after the committed older/newer spans confirm the gap. This includes row-step losses and same-transaction losses without any saved-evidence scan. The connected-maximum probe now compares gap, walk and cursor fields explicitly as well as the complete evidence object. Security review rechecked that restored anchors come from received facts and actual committed bounds; local continuation and exhaustion still create no daemon evidence. No signature, public declaration, schema or concurrency change.

The next run passed both new probes but caught a regression in the retained bounded-work and evidence-retry tests: the closed interval query for gap replacement includes the predecessor gap sharing the older-span boundary. Replace only cells whose high endpoint is the validated newer span's low endpoint, preserving that predecessor exactly. Count all selected span/gap cells and actual replacement writes. The original failure/retry and bounded-history assertions remain unchanged; their fresh reports retain this failed candidate. This repair stays within the same received-evidence preservation invariant.

The coverage class then passed all 14 methods and lint. The ordinary owned-row shared-producer regression still expected entry 3 to remain covered after deleting its only producing claim, the same stale-certification defect named by this verdict. Strengthen that expectation to require entry 3's absence and compare unrelated row evidence exactly; retain its rollback/retry, proof and completeness assertions. No production change follows this test correction. Preserve the original 5/6 failed class capture alongside the stronger green regression.

Final focused Linux results: 133 executed/passed, 0 failed/skipped. `sibling-rework-results.json` under `app/src/test/resources/verification/owned-coverage-2254/` records every selector, exact revision, raw XML hash and historical failure. Production repair revision is `e2430a1e0`; `9e8e61e87` changes only the stronger test expectation, plan and evidence. Both short/18000-row localized row saves execute 45 legacy SQL statements with identical storage counters; additive claim extensions execute 31 with one row read/decode/serialization and no row writes. The existing grandchild sizing exception remains; documentation and complete configured gates retain their later-stage ownership.

### 2026-10-18: preserve valid gap progress during producer subtraction (verifier finding 1)

This is a repair of the existing received-evidence preservation invariant, together with its regression. `subtractEntry` must retain an existing gap's exact anchor and walk when the anchor remains within the retained older span and the edge still names the adjacent newer span. Compute repaired gaps before removing old cells, following `addReceivedSpan`'s membership/edge rule; delete a walk only when its gap is no longer valid. Producer claims and spans still lose the invalidated entry, and completeness remains conservative. No new received continuity or cursor is inferred.

`OwnedLegacyCoverageTest.anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk` drives the production repository into real Room with unchanged received positions after a row change or suppression/removal. Repeat saves, reopen and repeat again; compare unrelated cells exactly and compare the same save's SQL/storage work on short and fragmented 18000-row histories after restore. A companion probe removes the actual anchor to require deletion of its now-invalid walk. The existing coverage, owned-row, concurrency, durability, unsigned and migration regressions remain required.

Security self-review: MUST FIX addressed at `subtractEntry`. Exact-owner indexed interval queries determine validity using committed retained span membership and the unchanged adjacent edge. Retaining already received progress does not discharge a producer loss, restore its proof, change completeness or introduce a local continuation as daemon evidence. An invalid anchor or changed edge still withdraws the walk. The repair changes no scope, job, schema, signature, public declaration, credential, Android surface, network or logging boundary; no content is logged. Existing no-backup custody, transaction rollback, retirement and row-success bookkeeping apply unchanged. Verdict remains PASS for this revised design.

Forecast: approximately 160 written lines for production, tests, plan and counted evidence, excluding compressed raw reports. The existing grandchild sizing exception remains; this finding repairs the same invariant and introduces no independently useful second behaviour. No overlapping feature branch touches the planned production/test files at repair admission. Documentation handoff additionally includes valid received gap-anchor/walk preservation in the two existing cache topics.
