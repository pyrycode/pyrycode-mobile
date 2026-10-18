# Explicit owned authoritative retirement

## Files read

- `data/repository/AuthoritativeThreadStore.kt`: `restore`, `applyBatch`, `publish`, `reset` own admitted facts and runtime generations.
- `data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `persistAuthoritative`, `resetAuthoritativeThread` serialize mutation and successful bookkeeping, including final retirement refresh.
- `data/cache/SavedAuthoritativeThread.kt`: validated full-file compatibility and snapshot conversion.
- `data/cache/AuthoritativeUpsertCache.kt`: the existing single-consumer owned mutation capability.
- `data/cache/AuthoritativeRoomStorage.kt`: atomic changed-fact persistence, metadata prerequisites and generation-bound pages.
- `data/cache/ConversationCacheDatabase.kt`: authoritative DAO, schema migration and scope deletion.
- `data/cache/MigratingConversationCache.kt`: import observation and host retirement guard the existing mutation capability.
- `data/repository/AuthoritativeOwnedUpsertTest.kt`: real disk/partial-view fixture and controlled writer holds.
- `docs/knowledge/features/conversation-cache-contract.md`, `docs/knowledge/features/caching-conversation-repository.md`: omission is never removal; final cleanup must refresh retirement after both lock waits and I/O.
- ADR 0009 and specs for #2149, #2161 and #2255: no-backup custody, exact owner predicates, import fencing and incremental mutation compatibility.

## Context

An explicitly removed owned fact currently has no durable identity fence. One useful behavior ships here: removal remains removed across delayed work and restart. Its store admission, serialized persistence and restart fence cannot ship independently. No new route, wire verb, capability negotiation or UI is introduced. Documentation-stage updates suffice; no new decision record is needed.

## Design

Add a redacted internal removal capture containing exact host/conversation, authoritative family, epoch, incarnation, runtime generation, identity and revision. Only the store mints captures for currently held facts. Admission compares all captured ownership and revision evidence; repeated retirement is harmless. The repository accepts a capture for its exact host, mutates the store under `historyWrites`, and persists the resulting candidate using `persistAuthoritative`. Its result acknowledges only committed retirement, or a proven committed superseding reset. Failure leaves the durable baseline unchanged and allows the same capture to retry.

Snapshots and saved records carry an incarnation and identity-to-captured-revision retirements. Existing records default to the initial empty incarnation and empty fences. Explicit reset assigns a fresh UUID incarnation, including reused epoch text, and clears fences. Store merges filter retired identities without cascading through parent relationships; restoration unions fences within an incarnation and cannot reinstall old certification. Raw fields and stable ordering of retained facts remain exact.

Room adds an authoritative retirement table keyed by exact host/conversation/id and a default-empty incarnation column on its header, through a non-destructive auto-migration. The existing owned mutation receives fence changes in the saved candidate, validates the incarnation, checks any durable held revision before deletion, and commits only the named deletions/fences plus metadata. Ordinary upserts consult the durable fence before payload admission. Removal clears checkpoint/coverage while retaining applied progress; stale certificate prerequisites cannot reverse this. Reset clears the old facts and fences atomically. Full replacement/import compatibility preserves fences and rejects stale incarnations. Scope deletion removes fences alongside both families; import ledger and host fences remain unchanged.

Each successful mutation changes the existing page generation. An old token is rejected; recovery restarts with `newestAuthoritativePage`, then follows new tokens. No ordinal renumbering or implicit omission removal occurs.

## State and concurrency model

No new jobs or scopes. Use injected processing/IO dispatchers and existing observer-owned `ThreadWriter`. Lock order remains `historyWrites`, migration cache mutex, storage mutex, Room transaction. Started mutation and successful baseline publication finish together in `NonCancellable`. Retirement state survives coalescing. Cleanup refreshes same-generation retirement changes as well as reset generations before and after final I/O. Old snapshots are reconciled against current fences inside the write lock.

## State transitions and identity reuse

| Event | Focused probe in `AuthoritativeOwnedRemovalTest` |
| --- | --- |
| Exact owned removal, partial omission, cross-host collision, restart | `ownershipInvariant_partialRemovalPreservesEveryOtherFact` |
| Wrong owner/family/generation or superseded revision | `ownershipInvariant_staleCapturesCannotRemoveNewerFacts` |
| Replay, delayed snapshot or stale restoration after removal | `retirementInvariant_replayAndRestoreCannotResurrect` |
| Failed transaction then same capture retry | `atomicInvariant_failurePreservesCertificateAndRetryCommits` |
| Held upsert overlapping removal | `retirementInvariant_heldUpsertThenRemovalCommitsFence` |
| Cancellation/final flush while retirement changes | `retirementInvariant_finalFlushRefreshesRemoval` |
| Held removal followed by reset, including reused epoch | `retirementInvariant_resetDuringRemovalCreatesNewIncarnation` |
| Confirmed conversation deletion and host forgetting | `retirementInvariant_scopeDeletionKeepsOtherHostExact` |
| Schema upgrade and file compatibility | focused Room migration/import and file-cache regressions |

## Error handling

Rejected capture returns a static causeless failure. Durable mutation failures retain existing classified errors and static redacted logs; no owner, epoch, item text, payload, token or exception cause is logged. Cancellation before admission creates no acknowledged retirement. Once admitted, persistence and baseline publication finish together; failed storage remains retryable without claiming durability.

## Testing strategy

Write failing disk-backed Robolectric tests first, using real Room and the production serialized writer with a 200-fact view over 18000 facts. Fresh database/cache/repository instances compare exact facts, metadata, fences, host isolation and ordering. Controlled before-commit holds and failures probe cancellation, overlap and reset. Check bounded named-fact work and unchanged unrelated ordinals; old tokens reject and fresh traversal loses no retained facts. Keep all inherited owned upsert, #2149 final-retirement, store, migration/import, Room and file regressions. Run affected Linux JVM classes, lint, assembly, Spotless and pre-verify. Complete configured suites remain dispatcher-owned; no live or device-only test is needed.

## Open Questions

None. Forecast: about 1350 total written lines including tests, plan and generated schema; two new internal types, no existing signature migration, two production consumer sites, three acceptance criteria and at most eight rejection branches. In-flight #2175 (route forwarding) and #2254 (legacy coverage/DAO) overlap files but not this behavior; keep edits local.

## Documentation handoff

Pending for the documentation stage: update explicit ownership, retirement lifetime, failure/retry and certification rules in `docs/knowledge/features/conversation-cache-contract.md` (owned authoritative mutations) and `docs/knowledge/features/caching-conversation-repository.md` (authoritative persistence and concurrency). Record counted focused evidence; documentation produces no runs.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: removal admission must use store-minted exact captures and compare revision inside the store lock; Room must independently reject a newer durable revision. Saved facts/fences pass numeric and identity validation in `toSnapshot`.
- [Tokens] No credentials or authentication material added. Incarnation UUIDs identify local reset lifetimes only.
- [Files and storage] Existing app-private plaintext/no-backup custody remains per ADR 0009. No new path construction. Room transactions bind deletion, fence and conservative metadata atomically; migration preserves existing records.
- [Android surface] No components, intents, providers, keyboard or rendered UI changes.
- [Cryptography] Noise, keys, nonces and transport authentication are unchanged.
- [Network/I/O] No network verb or capability inferred from disk. Existing upstream frame validation/bounds remain; replay cannot bypass retirement filtering.
- [Errors/logs] SHOULD FIX: captures and fences have redacted representations; emit static admission/durable failure codes only, with no content or owner identifiers.
- [Concurrency] SHOULD FIX: refresh same-generation retirements under `historyWrites`, including final-flush I/O, and publish successful baseline only after commit. Keep import observation and host-retirement checks on the existing capability. Reused epoch reset changes incarnation and rejects old work.
- [Threat model] Relay delay/reorder is handled by fences; hostile saved facts remain validated inert data. Root/app-uid access to existing plaintext cache is unchanged; no credentials or UI leakage surface is added.

**Reviewer:** builder (self-review)
**Date:** 2026-10-18
