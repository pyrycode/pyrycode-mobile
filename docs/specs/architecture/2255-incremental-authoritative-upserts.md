# Incremental authoritative owned upserts (#2255)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `persistAuthoritative`, restore and final-retirement fences.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `certifyQuery`, `restore`, `reset` and stable ordering.
- `app/src/main/java/de/pyryco/mobile/data/cache/SavedAuthoritativeThread.kt`: raw facts, validation and retention.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: import admission, retirement and atomic storage hooks.
- `app/src/main/java/de/pyryco/mobile/data/cache/AuthoritativeRoomStorage.kt`: replacement, pages, metadata and classified transactions.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: authoritative owner keys, ordinals and DAO.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeThreadCacheTest.kt`: writer, bookkeeping and final-retirement regressions to retain.
- `docs/knowledge/features/conversation-cache-contract.md` and `caching-conversation-repository.md`: certification cannot follow partial omission; mutation and successful bookkeeping must finish together.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md`, specs for #2149 and #2161: Room custody and import-unobserved protection.

## Context

Ordinary authoritative saves replace a whole family, losing facts outside an owned view and rewriting unchanged payloads. This ticket activates incremental owned persistence through the supplied repository seam. Import and full-file compatibility remain replacements; individual removal, bounded reader activation and production route activation belong to sibling tickets. No new wire contract or decision record is needed.

One useful behaviour: save owned facts without losing unloaded facts, atomically with their certification and lifecycle fences. Storage plumbing has this single repository consumer and cannot usefully ship alone. Forecast: approximately 1350 written lines across six existing production files, one small new interface, focused tests and this plan; one new exported declaration, two consumer sites, three criteria and at most nine rejection branches. No schema change: existing ordinal becomes stable insertion identity, and order/rank selection uses existing scalar columns. #2175 overlaps the repository's route forwarding, separate from persistence; keep edits local.

## Design

Add an internal `AuthoritativeUpsertCache` capability next to the cache consumer, implemented by `MigratingConversationCache`. Its owned mutation accepts changed raw facts with applied/checkpoint/coverage metadata and an explicit reset flag. The repository retains its existing full-write fallback for file caches and doubles. `persistAuthoritative` computes changed identities against the shared successful candidate (or the collection's restored baseline) under `historyWrites`; metadata-only commits contain no facts. Captures retain raw immutable objects without decoding the whole view again. Only storage validates/serializes changed facts. Full replacement APIs retain their current validation and retention.

Room validates the delta and metadata, reads only incoming identities by primary key, and accepts only newer revisions with the same kind. Stable ordinals remain unchanged for revisions; new identities allocate beyond the maximum ordinal. SQL orders pages/full reads by daemon order then stable ordinal, so equal-order ties keep held insertion order. Page tokens remain generation-bound ranks; every mutation rotates generation and stale continuations return null for restart-from-newest. No unrelated payload or ordinal is rewritten. Scalar SQL selection may scan ordering keys; it never decodes unrelated facts. Retention deletes the oldest scalar-selected identities over the cap and clears checkpoint/coverage in the same transaction.

Epoch mismatch or an explicit runtime-generation reset retires the family before its new facts commit. Reused epoch strings still reset. Ordinary omission never deletes. Applied progress takes the maximum within an epoch; older metadata cannot replace newer certification. Incomplete applied progress retains the prior certificate until a store-certified completion is included. Certification comes only from the authoritative store, never local page exhaustion, parent extras or hydration.

The migrating cache uses the existing import-aware write admission, preserving unavailable/unobserved refusals and forgotten-host fences. Full import and compatibility APIs remain unchanged. Internal write counters record payload reads, decodes, serializations, writes, deletes and header writes for the production repository-to-Room probe.

## State and concurrency model

No new jobs, scopes or flows. Cold collectors own the existing writer and timer. Snapshot capture uses the processing dispatcher; Room uses its injected I/O dispatcher. Lock order stays repository `historyWrites`, migrating cache mutex, storage mutex, Room transaction. Mutation and successful baseline publication finish in the existing non-cancellable section. Failure advances no baseline and final flush retries once. Existing generation checks before mutation and after final-flush mutation remain intact.

## State transitions and identity reuse

| Event | Focused coverage |
| --- | --- |
| 18000 stored facts, 200 owned, revision/add and metadata-only completion, fresh reopen | `AuthoritativeOwnedUpsertTest.boundedInvariant_ownedRevisionAndCertificationPreserveUnloadedFacts` |
| Reordered input, replay, equal-order ties, parent extras and explicit clears | `AuthoritativeOwnedUpsertTest.identityInvariant_revisionsAndTiesSurviveRestart` |
| Live revision during held initial restore, then repeated restore and ordinary revision | `AuthoritativeOwnedUpsertTest.orderingInvariant_liveBeforeRestorePreservesAcceptedTie` |
| Newer live revision while old commit held; cancellation and retry after failed commit | `AuthoritativeOwnedUpsertTest.atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts` |
| Reset during held work or final-flush I/O, including reused epoch | `AuthoritativeOwnedUpsertTest.retirementInvariant_resetDuringFinalIoRetiresReusedEpoch` and retained #2149 tests |
| Reset while final flush waits for lock; older collector or cancelled result delivery | retained `AuthoritativeThreadCacheTest` final-retirement, post-commit and older-collector regressions |
| Confirmed conversation/host deletion during held save; another host with colliding ids | `AuthoritativeOwnedUpsertTest.retirementInvariant_deletionFencesHeldSavesAndOtherHost` |
| Mutation invalidates continuation; restart walks exact ordered facts | bounded and identity probes above, retained Room paging tests |
| Retention exceeds cap | `AuthoritativeOwnedUpsertTest.retentionInvariant_trimClearsCertification` |

## Error handling

Existing classified transactions return content-free `ConversationCacheException` failures and roll back both facts and metadata. Cancellation and programming failures propagate. Unknown raw fields stay inert. Import failures, denied access, host retirement and invalid numeric/identity/kind data preserve the prior certificate and facts. Diagnostics contain only static operation/status codes and numeric work counters, never owners, epochs, payloads or exception causes.

## Testing strategy

Test first with a disk-backed Robolectric Room fixture, injected test dispatchers and the real production serialized writer. A partial restore adapter supplies only 200 owned facts while forwarding the upsert capability to the real migration-aware cache; this is the supplied seam before bounded reader activation. Fresh database/cache/repository instances assert exact facts and metadata after each lifecycle scenario. Retain numeric storage counters in test output and raw XML reports. Run affected authoritative repository/store/query, migration, file and Room classes, plus existing coalesced-writer regressions. No device-only or live check is required for this storage seam. Run Linux lint, assemble and formatting, push, merge main and run final assemble/pre-verify before opening the PR. Dispatcher owns full configured suites.

## Open Questions

None.

## Documentation handoff

- Pending: `docs/knowledge/features/conversation-cache-contract.md`, authoritative owned-upsert, certification and continuation contracts; include counted focused evidence, no documentation-stage runs.
- Pending: `docs/knowledge/features/caching-conversation-repository.md`, ordinary owned saves, successful baselines and final-retirement contracts; include counted focused evidence, no documentation-stage runs.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: validate the delta with `SavedAuthoritativeThread.toSnapshot`, and each selected prior payload against its indexed identity/order before revision comparison. SQL binds exact owner/epoch; no input becomes SQL text or a path. Unknown fields remain inert raw JSON.
- [Tokens] No credential generation, persistence or comparison is added. Existing pairing and Keystore stores are untouched.
- [Files/storage] Room transactions bind facts and certificate. Keep the existing no-backup database and sidecars, existing hashed file compatibility and ADR 0009 plaintext app-private threat model. No new path or backup surface.
- [Android surface] No components, providers, intents, WebViews or UI changes.
- [Cryptography] Noise/key/nonce custody is unchanged; UUIDs identify local page generations and are not authentication material.
- [Network/I/O] No new network operation; saved facts cannot establish negotiated support. Existing relay frame bounds and authentication remain upstream.
- [Errors/logs] SHOULD FIX: retain static classified errors and redacted representations; work counters contain numbers only. Never log cached text, owners, epochs or exception prose.
- [Concurrency] SHOULD FIX: delta calculation, atomic mutation and shared successful baseline stay under `historyWrites`; explicit reset must be driven by runtime generation even for a reused epoch. Retain final-retirement refresh after I/O and lock waits. Import admission and host fences remain mandatory on the new capability.
- [Threat model] Relay delay/reorder cannot manufacture certification; hostile facts pass existing full-item validation. Root/app-uid compromise can read the existing plaintext cache; encryption-at-rest policy is unchanged. No UI screenshot/keyboard/accessibility surface is added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17

## Revisions

### 2026-10-17: retention and certificate repetition

The owned mutation also carries `certify`, indicating that the store's checkpoint/coverage changed since the last successful candidate. A partial held view can retain an old certificate after Room trims an unloaded fact. Repeating that unchanged certificate on an item revision must preserve Room's cleared certification. New store-authored certification remains eligible only alongside already durable or atomically included facts. The retention probe performs a second revision after trimming to guard this boundary. Captures apply the existing ordered-tail cap without decoding the whole view; storage retains validation at the actual changed-fact boundary.

The initial deterministic Room executor prevented the held-transaction scheduler from progressing. Probes use normal Room executors, explicit restore readiness and transaction deferreds instead. The corrected single-method red run executed one test, failed once with 18000 expected versus 200 restored, and skipped none. Raw XML is retained under `/tmp/builder-2255/evidence/red-behaviour/` (production baseline `4fa383e36`, plan `e3e10860f`).

### 2026-10-17: repeated order moves and equal-order ties

Reviewing repeated order changes showed that a permanently fixed insertion ordinal can disagree with the store's stable sort after a fact moves away and later joins an equal-order tie. Ordinary revisions still preserve their ordinal. A changed order reserves an ordinal below the current minimum when joining a group from below, or above the maximum when joining from above. Multiple movers reserve a block in candidate order. Only changed facts move; signed ordinals are local tie keys, while continuations remain generation-bound ranks. `orderingInvariant_repeatedMovesIntoTiesOnlyRewriteMovedFacts` checks repeated moves, reversed batch input, explicit zero-order normalization and unchanged unrelated ordinals through fresh instances. No schema or consumer signature change is required.

Additional Room lifecycle probes: `atomicInvariant_postCommitCancellationPublishesBaselineBeforeFinalFlush` and `retirementInvariant_resetWhileFinalFlushWaitsForWriteLock` retain the mutation/bookkeeping and lock-wait boundaries on the incremental backend itself. The security review's concurrency findings apply unchanged: both still run under the existing writer lock and cancellation guard.

### 2026-10-18: verifier rework — coalesced ordering and certification prerequisites

Both findings repair the existing owned-persistence invariants rather than adding a separate behaviour. The revised forecast remains below 1600 total written lines, with two new internal declarations and fewer than ten consumer updates. In-flight #2175 changes route forwarding and #2253 changes legacy persistence; their shared-file edits are independent of the authoritative mutation and remain local.

1. `AuthoritativeThreadStore.publish` retains each identity's most recent accepted order move (revision and whether it joined from below). Captures carry these immutable runtime hints even if intermediate snapshots or writer candidates are conflated. `persistAuthoritative` selects moves newer than the successful baseline's item revision. Room reserves before/after ordinal blocks in final candidate order for those changed facts, including a return to the original durable order. Accepted move hints override inference from durable order; facts with no observed move retain the previous comparison fallback. Unchanged facts keep their payloads and ordinals. `orderingInvariant_coalescedMovesPreserveFinalTies` applies two moves before the timer flush, both into a new tie and back into the original tie, with and without delivery of the intermediate snapshot, then compares store order, one-item pages and fresh cache/repository instances with bounded work counters.
2. The owned mutation carries the metadata-only prior successful/restored baseline instead of a `certify` boolean. Room advances checkpoint/coverage only when that baseline's epoch, checkpoint and coverage still match the durable certificate, in addition to the existing monotonic progress checks. Retention clears the durable certificate atomically, so neither catch-up nor a coverage-changing completion inheriting the old certificate can reinstall it. A fresh restore can recover from the conservative durable baseline using new store-authored completion. `retentionInvariant_trimClearsCertification` adds both completion paths, zero-fact work counters and fresh repository assertions.

`retentionInvariant_coverageCompletionCannotReinstateTrimmedRange` starts with a zero checkpoint and non-null coverage, then trims and accepts a coverage-only completion. It isolates the coverage prerequisite check from checkpoint inequality. The original two regressions ran against production `33bd18b58`: 2 executed, 2 failed, 0 skipped/errors. Raw XML and the base revision are retained at `/tmp/builder-2255/evidence/rework-red/`.

Security re-review: PASS. The concurrency finding now requires accepted order-move history to survive coalescing and certificate prerequisites to be compared inside the Room transaction. Runtime hints are neither disk/wire facts nor logs; the mutation still validates changed payloads and preserves exact owner/generation fences. No new I/O, storage surface, credentials or UI is introduced.

### 2026-10-18: verifier rework — identities entering a partial held view

Finding 1 identifies another case of the existing stable-order invariant: absence from the held view does not imply absence from disk. `AuthoritativeThreadStore.publish` records an after-tie placement hint when an identity first enters the held view, including when its durable order is unchanged. Later accepted moves supersede that hint; successful-baseline revision filtering prevents it from moving the same identity again on an ordinary revision. The existing Room hint path applies that accepted placement while touching only the changed fact. No schema, signature, job or scope changes are needed.

`orderingInvariant_partialRestoreChangedOrderPreservesFinalTie` and `orderingInvariant_partialRestoreUnchangedOrderPreservesFinalTie` each restore 200 of 201 disk-backed facts, excluding the returning identity. They compare exact store order, one-item page traversal, fresh cache metadata/facts and a fresh repository; both also check unchanged unrelated ordinals, no full reads/replacements, bounded work counters and a later revision that preserves its assigned ordinal. This repair and its regressions remain one behaviour slice below the 1600-line ceiling. No in-flight branch overlaps these repair files. Merge `06ae7d38a` retained both main's owned legacy writes and this ticket's authoritative capability, with assembly and 53 writer tests passing before the merge commit.

Security re-review: PASS. Runtime placement hints remain generation-bound, immutable capture evidence; they grant no certification or connected capability. All incoming payload validation, exact owner/epoch predicates, transaction boundaries, cancellation fences and redacted diagnostics remain unchanged. Newly restored hints are filtered against the restored successful baseline before any delta write.

Both regressions failed against production baseline `06ae7d38a` with expected identities `[2, 1]` versus durable `[1, 2]`: 2 executed, 2 failed, 0 errors/skips. Raw XML, baseline revision and the test-only patch are retained under `/tmp/builder-2255/evidence/rework2-red/`. The first fixture run checked counters before asynchronous Room completion; a bounded page read now joins the writer through the existing cache mutex before inspecting counters or durable state.

### 2026-10-18: verifier rework — live insertion before restoration

Finding 1 repairs the same accepted stable-order invariant. Restoration can append saved peers behind a live fact without changing that fact's revision or numeric order. `restore` marks its publication as restoration; `publish` reconciles retained live placement hints to before-tie when newly restored peers join that order. A repeated restore adding no peers preserves the existing hint. Normal entry/move handling and successful-baseline revision filtering remain unchanged, so only the changed live payload and its placement cross the owned-write boundary.

`orderingInvariant_liveBeforeRestorePreservesAcceptedTie` holds the initial disk-backed read after capture, applies a newer full live revision to the empty runtime store, then releases restoration. It compares accepted store order, exact metadata/facts, one-item pages and a fresh repository. Numeric work counters require one fact read/decode/serialization/write and one header, no extra full read/replacement, and unchanged unrelated ordinals. A repeated full restore followed by an ordinary revision retains the assigned ordinal. Existing partial-restore, coalesced-order and writer retirement probes remain required.

Scope reassessment under `behaviour-slices-v1`: this fix and its regression are one inseparable repair, with no new exported declarations, state machine, jobs or scope. The branch currently has 1196 inserted and 17 deleted lines; the repair adds about 100 written lines, remaining below 1600. No in-flight feature branch changes either repair file.

Security re-review: PASS. The restoration flag and reconciled hints are runtime placement evidence only, bound to the existing exact host/conversation, epoch and runtime generation. They cannot certify facts, authorize a route or change storage custody. Payload validation, metadata prerequisites inside the Room transaction, mutation/baseline locking, reset/deletion fences and redacted diagnostics remain unchanged. No new I/O, paths, secrets, network or Android surface is introduced.

The regression failed against production `23c69e6c93d822e0712bb1fbe6b36b2b608d5ff9`: 1 executed, 1 failed, 0 errors/skips, expected identities `[2, 1]` versus durable `[1, 2]`. Raw XML, revision and test-only patch are retained at `/tmp/builder-2255/evidence/rework3-red/`. Its earlier run failed on the same fresh-record comparison with the record's redacted representation; that XML is retained in the `initial/` subdirectory.

### 2026-10-18: recovery repair — placement independent of payload revisions

The recovery brief extends finding 1 with two sequences of the same stable-order invariant. Placement evidence uses a store publication counter, independently of payload revisions, scoped by the existing epoch/runtime generation. Restoration records evidence for held live identities when saved peers join their tie; newly restored identities establish the baseline rather than new moves. The repository selects pending placements against the shared successful placement counter under `historyWrites`, including unchanged payloads, and publishes that counter only with a successful commit. Capture and final-retirement fences remain unchanged.

Room admits placement separately from newer-payload admission. Scalar identity/order/ordinal queries check each accepted before/after block in candidate order. A block already at the correct tie edge keeps its ordinals; otherwise only its participating identities receive a reserved ordinal block. Placement-only updates never read, decode or serialize their payloads. Changed facts are still validated and written once; stale incoming payloads cannot reposition newer stored revisions. Counters include scalar placement reads and placement-only writes. The before/after shape follows stable sort: entries append, moves from below prepend, moves from above append, and restore appends saved peers behind held identities.

`orderingInvariant_heldUnchangedPeerAnchorsChangedRevision` holds a read of tied A,B,C, accepts unchanged A and newer B, then checks accepted A,B,C against one-item pages and fresh cache/repository instances with one-payload work and unchanged ordinals. `orderingInvariant_heldUnchangedRevisionPersistsPlacementOnly` holds saved A,B, accepts unchanged B, and checks B,A with zero payload work, one placement-only write and unchanged A/unrelated ordinals. Both include an unchanged observer retry. All earlier writer, ordering, certification and retirement probes remain required.

Scope: one inseparable invariant repair; forecast below 1600 written lines, at most three new internal declarations overall, no schema or signature migration, jobs or scopes. In-flight #2175 touches route forwarding and #2254 touches legacy coverage/DAO methods; neither restructures authoritative persistence. Keep shared edits local. Documentation remains pending as above.

Security re-review: PASS. Placement evidence is immutable runtime data admitted through the same owner/epoch/generation and capture fences as payloads. Scalar queries bind exact owners and epochs; placement cannot create a fact or certification. Placement and payload mutations, metadata and successful bookkeeping share their existing atomic boundaries. Payload validation, import/host retirement, no-backup custody, cancellation and redacted failures remain unchanged. No new network, Android, cryptographic, secret or filesystem surface is introduced.

The two regressions failed against production `b50eef33ffc50b8f98e79b32dd8c952b6cce53a7` (plan-only head `a51f8debf`): 2 executed, 2 failed, 0 errors/skips. The first repair run passes both: one payload read/decode/serialization/write and two scalar reads for A,B,C; zero payload work, three scalar reads and one placement-only write for B,A. Raw XML, revisions and source patches are retained under `/tmp/builder-2255/evidence/recovery-red/` and `recovery-first-green/`.
