# Atomic authoritative thread cache (#2149)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: additive default methods and legacy retention contract.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `mutate`, `writeAtomically`, hashed namespaces and removal.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `historyWrites`, successful baselines and deletion fence.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `certifyQuery`, `reset`, generation guard and revision upserts.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: `dispatch` resumes only `checkpoint`, not applied progress.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: supplied host/store, negotiated query methods and transient progress.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto.raw` preserves inert unknown fields and explicit patch clears.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueriesTest.kt`: supplied pump and interrupted-query probes.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeThreadCacheTest.kt`: real-file retirement interleavings and the controlled restore dispatcher probe used during rework.
- `docs/knowledge/features/conversation-cache.md`, `conversation-cache-contract.md`, `caching-conversation-repository.md`: no-backup custody, legacy settled-only retention and post-commit bookkeeping.
- Sibling `/work/Projects/pyrycode/docs/protocol-mobile.md`, “Thread query messages” and “Security model”: wire authority; no wire change.

## Context

#2148 exposes complete item upserts independently of certified resumable completion and continuous history. Those facts currently disappear on process death. Save and restore them as one distinguishable document, keeping the legacy row contract intact. #1988 owns route activation/presentation and combined live proof; #2067/#2068 own Room and bounded reading. No visual change or decision record is needed.

Sizing: one deliverable, approximately 1450 written lines including tests and this plan, six production files, two exported record types, no existing signature migrations, four criteria, at most eight rejection branches. No overlapping in-flight branches were found.

## Design

Add `SavedAuthoritativeThread` and its coverage record in `data/cache/`: epoch, raw full items, applied version, certified checkpoint and optional numeric continuous coverage/continuation. Runtime generation, connected capabilities, transient progress and query assembly are excluded. Redact record `toString`. Default-bodied cache methods retain existing doubles. A separate format/version and hashed authoritative-thread document prevent legacy rows from certifying facts. Store host/conversation in the envelope and verify them on read. The existing atomic replacement and cache mutex bind items and certification in one commit. Removal includes both thread families.

Validate the entire record using the existing item decoder and store constraints; keep explicit null optional patch clears and unknown kinds/fields inert. Normalize identities to newest revisions in daemon order. Apply the existing 100000-item retention ceiling to full facts without excluding active/unknown kinds. If retention removes any fact, clear checkpoint and coverage so omitted facts cannot be certified; keep applied progress distinguishable.

Add `AuthoritativeThreadStore.restore` with a captured generation. It rejects retired generations, reset-needed state and mismatched epochs; merges full items by newest revision without deleting held live facts. Existing certified live state wins over stale disk certification. A pristine store adopts saved certification; newer uncertified live state can retain the prior saved checkpoint/coverage after merging the saved facts. Runtime generation and repair flags come from current state, not disk.

Expose a host-checked store accessor on the supplied remote repository. The caching wrapper uses that store when its delegate is the supplied authoritative remote; otherwise it owns an offline store. Add internal authoritative observe/open/reset seams for the later activation consumer, leaving legacy methods and handshake negotiation unchanged. Open restores before asking the remote query dispatcher; offline restore never enables capabilities. Reset through the wrapper invalidates saved state under `historyWrites` before returning. Observed remote resets also replace old saved state; stale restores are independently rejected by the store generation guard.

Extend `ThreadWriter` to accept authoritative candidates alongside legacy candidates, using its same timer, conflated slot, serialized persistence and final flush. Authoritative candidates carry both store generation and wrapper capture sequence. Under `historyWrites`, reject deleted destinations, retired generations and captures older than the successful baseline. Only successful atomic writes update that baseline. The record comparison includes metadata, so certification-only changes persist. Reset candidates bypass the quiet period. Do not block snapshot delivery on ordinary disk I/O.

## State and concurrency model

Cold observers own their structured writer child and 100ms timer. Item preparation, restore decoding and store merging run on injected `processingDispatcher`; file I/O uses the cache's injected dispatcher. Lock order remains wrapper `historyWrites` then file-cache mutex; no cache callback enters the wrapper. Started mutation and successful-baseline bookkeeping share `NonCancellable`. Collector cancellation joins the writer and flushes the latest eligible candidate once, then follows any generation retired during final I/O until its replacement commits or a mutation fails. Restore captures generation before file I/O and rechecks deletion/generation before application on the worker. Confirmed deletion marks the tombstone before waiting for in-flight work, removes both families and clears baselines. Refused daemon deletion does neither.

## State transitions and identity reuse

| Event | Required deterministic test |
| --- | --- |
| Restart with fresh cache/repository offline, then negotiated reconnect | `restartRestoresFactsAndOnlyCertifiedReconnectCheckpoint` |
| Partial query with newer live revision and outside parent, disconnect/restart | `interruptedBatchesKeepPriorCertificationWithNewestItems` |
| Completion changes only checkpoint/coverage after items were saved | `certificationOnlyChangesPersistAtomically` |
| Repeated/reordered/overlapping revisions, empty and single-item records | `identityProbesConvergeWithoutExpandingCoverage` |
| Restore suspended across newer live items/certification | `staleRestoreCannotReplaceNewerLiveFactsOrCertification` |
| Restore suspended across reset, including reused epoch and repeated reset | `resetFencesCapturedRestoreAndFinalWrites` |
| Reset waits for an in-flight old-generation write before durable replacement | `resetDuringInflightWriteCannotRestoreRetiredItems` |
| Remote query reset arrives during reset or earlier snapshot delivery, then its collector is cancelled | `remoteResetSurvivesCancellationDuringDelivery`, `remoteResetDuringEarlierDeliverySurvivesCancellation` |
| Overlapping resets supersede queued invalidation while the replacement is held or fails | `overlappingResetCannotAcknowledgeHeldReplacement`, `overlappingResetCannotAcknowledgeFailedReplacement` |
| A further reset supersedes final cleanup while it waits for the write lock; its later explicit acknowledgement reuses the committed baseline | `finalFlushWaitingForWriteLockPersistsLatestReset` |
| A correlated remote reset arrives while final-flush I/O is held after collection ends | `remoteResetDuringFinalFlushIoPersistsRetirementBeforeCleanupCompletes` |
| Older collector/final flush after newer committed state | `olderFinalFlushCannotOverwriteNewerCommit` |
| Older captured emission returns after a newer save, then collection is cancelled | `olderCapturedEmissionCannotOverwriteNewerCommitOnCancellation` |
| I/O held while another snapshot arrives | `heldIoDoesNotBlockAuthoritativeDelivery` |
| Save fails then unchanged emission/final flush retries | `failedSavePreservesAtomicRecordAndBaseline` |
| Reset mutation fails | `resetFailurePreservesSavedRepresentationAndReportsContentFreeFailure` |
| Cancellation after atomic commit before result delivery | `postCommitCancellationKeepsSuccessfulBaseline` |
| Confirmed deletion during pending/in-flight/final writes; another host with same id | `deletionFencesWritesAndKeepsOtherHost` |
| Confirmed deletion after disk restore has captured its result | `deletionFencesCapturedRestore` |
| Refused deletion | `refusedDeletionPreservesSavedFacts` |

No UI lifecycle state is added; re-collection and cancellation are covered above. Connection capability and transient state remain owned by the remote connection.

## Error handling

Unreadable, wrong-version, wrong-owner or structurally invalid authoritative records restore nothing and log only static operation/failure codes. They never fall back to converting legacy rows. Failed writes preserve the prior atomic document and successful baseline, with next-emission/final-flush retry. No exception contents, items, epochs, routing ids, coverage or raw JSON reach logs. No new UI error state or network outcome is added.

## Testing strategy

Write failing repository tests first using `TemporaryFolder`, real `FileConversationCache`, the supplied negotiated remote/pump and deterministic test dispatchers. Gate reads/writes with delegating cache doubles for race control while actual successful storage remains a real file. Assert exact raw item objects, revision/order/parent, epoch, applied version, checkpoint and coverage together through a fresh file-cache instance. Add cache format/owner/corruption/retention tests. Run existing `CoalescedThreadWritesTest`, `CachingConversationRepositoryTest`, `FileConversationCacheThreadTest`, `AuthoritativeThreadStoreTest` and `AuthoritativeThreadQueriesTest`. No device test or live scenario is needed for this data-layer seam; activation/live proof belongs to #1988. Run focused tests, lint, assemble, formatting and final pre-verify after merging main.

## Open Questions

None. The additive supplied seam intentionally stops before production route activation.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/conversation-cache-contract.md`, “The contract”, and `conversation-cache-layout.md`, “Layout”: the separate atomic authoritative family, ownership validation, raw full-item retention and certification loss on trimming.
- Pending for the documentation stage: `docs/knowledge/features/caching-conversation-repository.md`, “What is written, and when” and “State and concurrency”: the supplied authoritative save/restore/open/reset seams, shared writer ordering, restore processing on the worker, restore/deletion/reset fences through final I/O and reporting reset failures. Production route activation and combined live proof remain with #1988.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: cached JSON is still untrusted. Decode complete items and validate numeric/revision/coverage facts before restoring; reject wrong owner/version as a whole. Unknown content remains inert and never enters paths or logs.
- [Tokens] No credentials are added or saved. Saved items remain conversation content; pairing/Keystore custody is unchanged.
- [Files and storage] Reuse hashed host/conversation paths, atomic temp-and-move and existing app-private `noBackupFilesDir` binding. Preserve the existing plaintext conversation-cache threat model: app uid/root compromise can read content; encryption-at-rest migration is outside #2149 and storage migration belongs to #2067. No external/backup storage or input-derived path components.
- [Android attack surface] No component, intent, provider, WebView or rendering change.
- [Cryptography] Only existing SHA-256 namespace hashing; Noise, keys and nonces unchanged.
- [Network and I/O] Existing query decoder, transport caps/timeouts and negotiated capability gate unchanged; disk facts cannot establish support.
- [Errors/logs] SHOULD FIX: redact both new records and log only static event/operation/failure codes, never exception causes or cached data. Test content-free logs.
- [Concurrency] SHOULD FIX: fence suspended restores with runtime generation and deletion; check every queued/final candidate inside `historyWrites`; keep atomic mutation and baseline together under cancellation. Tests exercise each fence.
- [Concurrency, rework] MUST FIX (resolved design): accept a reset-generation candidate non-cancellably before downstream delivery, so cancellation cannot omit retirement from the existing writer's final flush. After joining in-flight work, cleanup captures later reset generations during delivery, lock waits and final mutation I/O. Under `historyWrites`, each successful final mutation and baseline update is followed by another generation check; a newer retirement requires another atomic replacement before cleanup returns. A failure stops cleanup with static failure reporting and leaves the last successful baseline intact. A superseded explicit reset succeeds only if that baseline proves its generation or a later generation reached disk; otherwise return a content-free failure. Ordinary delivery remains independent of disk I/O and uses its existing acceptance ordering. Restore decoding and merging stay on `processingDispatcher`, with the original captured generation and deletion fence retained.
- [Threat model] Relay delay/drop/reorder leaves prior certification resumable; host-keyed saved state cannot authorize a connection. Hostile facts are decoded and kept inert. Token theft, static-key compromise and UI screenshot/accessibility leakage remain unchanged and outside this non-UI persistence work.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-14

## Revisions

- 2026-10-14: reset returns a reporting `Result<Unit>` after durable replacement. A cache failure retains the last valid document, and cannot be acknowledged as successful invalidation. `restore` rejects any store already owned by a runtime reset, including a read begun after reset with a reused epoch; process-restored records start in generation zero. The supplied remote accessor is additive and host checked, with an offline store fallback; no existing signatures or callers are migrated.
- 2026-10-14 (verifier findings 1–3): reset-generation acceptance precedes downstream emission in a non-cancellable processing hop, retaining final-flush work if delivery is cancelled. Cleanup refreshes a reset generation received during suspended delivery after joining started writes. Supersession alone is not durable invalidation: a rejected explicit reset reports failure unless a successfully persisted generation already satisfies it. Restore derives repair from merged applied progress and the retained checkpoint, preserving current repair requests while avoiding stale partial disk state reopening repair after newer live certification. Real-file remote-reset and held/failed overlapping-reset probes cover these contracts; the stale-restore probe now saves applied version 20/checkpoint 10 and checks repair after live completion through 30. No new coordinator, state type or caller migration is added; rework remains below the 1600-line written-work ceiling.
- 2026-10-14 (cleanup probe): final-flush reset refresh also occurs inside `historyWrites`, closing the lock-wait window after its initial capture. `finalFlushWaitingForWriteLockPersistsLatestReset` verifies cleanup waits for the latest invalidation and a later explicit reset reuses that successful baseline without a duplicate mutation.
- 2026-10-14 (verifier `d54420f1fa26`, findings 1–2): final persistence rechecks generation after successful mutation/bookkeeping and replaces any retirement that arrived during I/O before cleanup returns, without retrying a failed mutation. The real-file remote-query probe holds final I/O and its retirement replacement. Restore decoding/merging now use the processing dispatcher; `restoreDecodingAndMergeWaitForProcessingDispatcherWhileMainProgresses` controls that worker and proves caller progress, worker-only item traversal and store merge.
