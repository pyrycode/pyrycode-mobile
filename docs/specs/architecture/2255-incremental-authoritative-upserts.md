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
