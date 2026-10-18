# Explicit owned authoritative retirement

## Files read

- `data/repository/AuthoritativeThreadStore.kt`: `restore`, `applyBatch`, `publish`, `reset` own admitted facts and runtime generations.
- `data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `persistAuthoritative`, `resetAuthoritativeThread` serialize mutation and successful bookkeeping, including final retirement refresh.
- `data/cache/SavedAuthoritativeThread.kt`: validated full-file compatibility and snapshot conversion.
- `data/cache/AuthoritativeUpsertCache.kt`: the existing single-consumer owned mutation capability.
- `data/cache/ConversationCache.kt`, `data/cache/FileConversationCache.kt`: full-replacement fallback, explicit reset admission and atomic file compatibility.
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
| Explicit removal caller cancelled during started I/O | `atomicInvariant_cancelledRemovalFinishesCommitBeforeReturning` |
| Held removal followed by reset, including reused epoch | `retirementInvariant_resetDuringRemovalCreatesNewIncarnation` |
| Confirmed conversation deletion and host forgetting | `retirementInvariant_scopeDeletionKeepsOtherHostExact` |
| A newer revision already committed outside the held view | `ownershipInvariant_durableNewerRevisionRejectsRemovalAtomically` |
| Replayed replacement/upsert, restart and reused-epoch reset | `retirementInvariant_storageFencesReplayAndOldIncarnationAcrossReopen` |
| Schema upgrade and file compatibility | `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence`, `retirementInvariant_fileCompatibilityRetainsFenceAndResetLifetime` |
| Replacement fallback reset, failure, retry and reused epoch text | `retirementInvariant_roomReplacementResetAdmitsOnlyNewIncarnation` |
| Multiple pending rejections, observer order and unrelated-save recovery | `ownershipInvariant_pendingRejectionsRestoreOriginalTieOrder` |
| Repeated committed removals and failed removal retention | `retirementInvariant_successReleasesRecoveryButFailureRetainsIt`, `retirementInvariant_finalFlushRefreshesRemoval` |

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

## Revisions

### 2026-10-18: shared restored baseline

The explicit seam can persist before an observer has made any successful mutation. Preserve the validated, committed restoration as the initial shared baseline under `historyWrites`, without advancing its capture fence or adopting live merged facts as durable. Otherwise removing one identity from an unchanged partial view would rewrite every retained owned payload. Existing observer-local restoration remains unchanged; successful mutations supersede the initial baseline normally. The partial-view probe checks zero payload writes and unchanged unrelated ordinals. Security review remains PASS: only an accepted exact-owner, generation-checked restore can initialize this baseline, and no connected capability is inferred.

Retirement keeps checkpoint zero and coverage null until explicit reset: replay completion cannot certify a range containing deliberately withheld identities. Saved records validate this conservative certificate and disallow a fact overlapping its fence. Incarnation defaults preserve existing version-1 files and pre-migration Room content; explicit reset assigns a fresh UUID. The authoritative capture type fixes its family and binds the minting store in addition to exact ownership, epoch, incarnation, generation and revision.

The first fixture run failed before removal because its full items omitted required fields (1 executed/failed). After correcting the fixture, the behavioral regression ran against plan `575562ee5` and the no-op seam: 1 executed, 1 failed, 0 errors/skips, expected 17999 versus 18000 facts. Raw XML and the test source are retained under `/tmp/builder-2256/evidence/red/`.

The inherited `retirementInvariant_resetDuringFinalIoRetiresReusedEpoch` runs two independent epoch cases against the same disk path. Its second seed reused the first case's retired incarnation. Explicitly remove that scope before each seed, retaining every held-I/O, cleanup and exact saved-state assertion. Full replacement cannot bypass a committed incarnation or clear fences through different epoch text. The first inherited run at `98f47c998` executed 17 tests, with 16 passes and this setup failure; raw XML is retained under `/tmp/builder-2256/evidence/inherited-setup-failure/`.

The next inherited run passed that reset probe but exposed a transaction-executor race in `atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts`: `runCurrent` does not join Room rollback, and its retry hold was inside another write transaction while a fresh database opened. Await the failed cache result to prove rollback finished, then hold retry at the capability boundary before Room admission. Preserve both the exact prior-certificate assertion and the exact final-flush assertion. No production contract changes. The 17-test run at `19000b46b` had 16 passes and this failure; raw XML is retained under `/tmp/builder-2256/evidence/inherited-held-failure/`.

### 2026-10-18: verifier retirement recovery and file replacement

Both findings repair the existing retirement lifetime invariant. Room returns a typed, redacted definitive rejection containing its validated newer held fact after rollback. Under `historyWrites`, the writer withdraws only the matching uncommitted fence and merges that newer fact into the same incarnation/generation; transient failures retain retry intent. Successful baselines still advance only on commit. Extend `ownershipInvariant_durableNewerRevisionRejectsRemovalAtomically` to persist an unrelated update after rejection and reopen disk.

File replacement must read and retain committed same-incarnation fences, filter retired identities and invalidate certification; stale incarnations reject without changing bytes. Add an explicit reset entry on `ConversationCache`, consumed only by the serialized fallback writer; ordinary replacement cannot mint an incarnation. Serialize authoritative file replacement across instances. Extend `retirementInvariant_fileCompatibilityRetainsFenceAndResetLifetime` with stale original replacements, reused-epoch reset rejection and new-incarnation admission through fresh instances.

Security re-review: PASS. Typed rejection has static exception prose and no cause; validated content remains internal. Withdrawal checks generation/incarnation/revision and never clears committed fences. File validation, exact envelope ownership and atomic rename remain; only explicit reset clears retirement lifetime. No network, credentials, Android surface or storage custody changes. Forecast remains within 1600 written lines with one additional internal rejection type, no existing signature migration and the same three acceptance criteria. In-flight #2245 also overlaps route forwarding only. Documentation handoff remains pending.

Android lint rejected `Files.readString` at min SDK 33; use the existing `Files.readAllBytes` UTF-8 pattern for the same exception-reporting open. No contract changes. The failed lint report at `e7ac5ac95` is retained under `/tmp/builder-2256/evidence/rework-lint-failure/`.

The equal-order rejection probe failed once at `adcd715d4`: recovery minted a peer-placement hint. `publishSnapshot` now preserves existing placement captures when recovering the newer durable fact; ordinary `publish` callers keep their contract. The extended rejection probe compares exact retained order and unchanged ordinals. Raw failure/source: `/tmp/builder-2256/evidence/rework-order-red/`.

### 2026-10-18: replacement admission parity and observer placement

Verifier findings 1–3 at `be34dabc0` expose the same retirement invariants on supported replacement paths and rejection recovery. Before changing any committed content, `replaceAuthoritative` and `importThread` compare each newly submitted fence against the validated durable fact inside their existing lock/transaction. A newer durable revision returns `AuthoritativeRemovalRejected`, leaving the entire prior record intact and reusing the writer's definitive-rejection recovery. Committed fences remain authoritative. Room replacement retains maximum applied progress within the same epoch/incarnation, matching file replacement.

The store records the removed identity's ordered tie peers at admission. Rejection reinserts the protected newer fact before its nearest surviving successor, or after its nearest surviving predecessor, then orders by the durable order key. This runtime-only placement evidence is discarded when its fence is withdrawn or the generation resets; it supplies no certification or persistent placement move. Extend the existing durable-rejection probe to assert current and observed lists, exact reopen state and unrelated-save recovery for incremental Room, Room replacement and file replacement. Extend the replay/reopen probe with applied progress 20000 → 30000 → stale 20000 and assert retained progress, fences and conservative certification.

Security re-review: PASS. Durable facts and new fences are validated before mutation under existing exact-owner predicates and atomic boundaries; typed rejection remains redacted and causeless. Runtime tie identities stay internal, generation-scoped and unlogged. Tokens, credentials, cryptography, network, Android surfaces, cache paths and no-backup custody are unchanged. No new job, dependency, wire operation or UI is introduced.

The four regressions failed against unchanged production (4 executed/failed, no errors/skips); raw reports and source are in `/tmp/builder-2256/evidence/rework3-red/`. The first repaired class run had 14 passes and one Room-replacement prior-record assertion failure. Close the initial observer before explicit removal so an automatic save of recovered conservative state cannot race that transaction-only assertion; then reobserve the same repository for the unrelated-save recovery check. Keep the exact prior certificate, current/observed order and final reopen assertions. The initial repaired run is retained in `rework3-first-repair/`; no production contract changes.

Sizing reassessment under `behaviour-slices-v1`: this is repeated repair of the original retirement invariant, with no independently useful new deliverable. The preserved branch contains 1552 inserted / 42 deleted lines (1594 total), including the 446-line generated schema. The repair adds approximately 110 written lines, no exported type and no required consumer migration. Confirmed parent #2250 / grandparent #2068 prohibit another split; continue building under the split-depth rule and record `needs-human:sizing`. No in-flight feature branch overlaps the three production files at this reassessment. Documentation handoff remains pending as above.

### 2026-10-18: fallback reset and pending removal recovery

Findings 1–3 at `67e9976cf` repair the original retirement lifetime and exact-order invariants. `MigratingConversationCache.resetAuthoritativeThread` passes through the existing migration-aware write admission, then uses Room's existing explicit-reset mutation transaction. This preserves import-observation and host-retirement refusal, atomically clears the prior facts/fences/certificate, and admits the new incarnation even with reused epoch text. Ordinary replacement still rejects old incarnations. The disk-backed fallback probe covers both epoch cases, failure before commit, restart and new facts after reset.

Removal placement captures must include other pending removals that shared the captured tie. At admission, merge those identities back into the captured peer order using their surviving successor/predecessor anchors. A definitive rejection can then recover multiple pending removals in either rejection order without losing their relationship. Extend failure/retry coverage to compare current and observed order, exact disk order, unrelated-update recovery and preserved ordinals.

After successful persistence and baseline publication, release recovery peer lists for the exact committed generation/incarnation and retirement revisions. Keep durable fences and recovery evidence for uncommitted removals, including changes admitted during held I/O. Generation changes and definitive rejection retain their existing cleanup. A content-free internal entry count proves that repeated successful tied/absent-order removals retain no recovery history, while failed transactions keep evidence until retry succeeds.

Security re-review: PASS. Reset uses the existing exact-owner migration admission and Room transaction; it cannot bypass unavailable/unobserved imports or host retirement. Recovery and its cleanup stay under the store lock and `historyWrites`; cleanup checks generation/incarnation/revision and never withdraws a durable fence. Captured identities remain internal and unlogged; diagnostics expose counts only. Tokens, credentials, cache paths, no-backup custody, cryptography, network and Android surfaces are unchanged. No job or dependency is added.

Sizing reassessment: one retirement behavior, initially 1675 inserted / 46 deleted lines (1721 total); the formatted final diff has 1910 inserted / 47 deleted lines (1957 total), including the 446-line schema. No exported type or signature migration. Confirmed grandchild depth and existing `needs-human:sizing` require continuing. In-flight #2175 and #2245 overlap only route forwarding in `CachingConversationRepository`; these local persistence edits are independent. Documentation handoff remains pending.

The three new regressions failed against unchanged production at plan `843784bf6`: 3 executed/failed, 0 errors/skips. Raw XML, test source, patch and hashes are retained in `/tmp/builder-2256/evidence/rework4-red/`. The retention probe originally inspected the old private lists to observe the leak; it now uses a content-free internal count, with the same assertions. Recovery captures additionally retain their order key so pending removals from different ties cannot acquire a relationship.

The first repaired class run executed 18 tests: 17 passed and the new held-I/O count assertion failed (0 errors/skips). That fixture uses distinct order keys, so one peer entry is retained, not three. Correct only that count to one; preserve the held-state, cleanup-to-zero and exact reopened-state assertions. No production contract changes. Raw XML and the patch are retained in `/tmp/builder-2256/evidence/rework4-first-repair/`.
