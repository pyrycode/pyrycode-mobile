# Indexed authoritative Room storage (#2163)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: shared legacy schema and scoped DAO queries.
- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: transactions, failure classification, page tokens and removal; keep writes family-local.
- `app/src/main/java/de/pyryco/mobile/data/cache/SavedAuthoritativeThread.kt`: `toSnapshot` and `retained` define validation, stable daemon order and certification loss on trimming.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: graceful reads, reporting mutations and exact owner equality.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto.decodeReplacement` preserves explicit-null clears and unknown raw fields.
- `app/src/main/java/de/pyryco/mobile/di/ConversationCacheDatabaseFactory.kt`: fixed app-private no-backup database path; remains unchanged.
- `app/src/test/java/de/pyryco/mobile/data/cache/LegacyRoomStorageTest.kt`: real-disk restart probes and read instrumentation pattern.
- `docs/knowledge/features/conversation-cache-contract.md`: authoritative facts and legacy evidence never certify each other; pages are separate projections.
- `docs/knowledge/features/conversation-cache-layout.md`: exported Room schema, binary owner comparison, portable contracts and no destructive migration.
- `../pyrycode/docs/protocol-mobile.md`: existing wire contract and Security model; no wire changes.

## Context

#2162 is merged. Add an independently usable authoritative backend for #2161 without changing application binding, import, repository consumers or the saved-data format. No overlapping in-flight numeric feature branch touches the planned existing source files. No decision record is needed: reuse the approved Room storage and existing plaintext app-private cache threat model.

## Design

Raise `ConversationCacheDatabase` to version 2 with Room's additive auto-migration from the checked-in version 1 schema. Add authoritative headers (exact owner, epoch, replacement generation, applied progress, checkpoint and serialized optional coverage) and item rows (owner, epoch, item id, stable sorted ordinal and full raw JSON). Composite identity and unique owner/epoch/ordinal indexes keep families isolated and bounded reverse selection indexed. Generated schema stays under `app/src/main/roomSchemas`.

`AuthoritativeRoomStorage` exposes full `readAuthoritativeThread`/`writeAuthoritativeThread`, `newestPage`, `beforePage`, and scoped removal. Validate and retain replacements with `SavedAuthoritativeThread.retained`; insert sorted raw facts and header in one transaction after removing superseded authoritative rows. Full reads validate reconstructed saved data. Page reads validate only the header and selected raw facts against indexed identity/order, never decode omitted payloads. A separate ordinal-only query probes at most one preceding row. Instrument actual selected item reads/decodes, ordinal probes, header/evidence reads and coverage decodes.

`AuthoritativeThreadPage` carries exact owner, epoch, raw items, committed appliedVersion/checkpoint/coverage, and a nested opaque local continuation bound to owner, epoch and replacement generation. It cannot be passed to legacy paging. Local exhaustion changes no server certification. No new ViewModel, UI state, consumer migration or dependency.

Extend database scoped removal to delete both families in one transaction. Legacy `writeThread` must continue calling its family-local removal helper, so only public scoped removal uses the shared deletion operation. Authoritative replacement deletes only authoritative tables.

## State and concurrency model

Caller-owned suspend operations run on an injected IO dispatcher, serialized per instance with a mutex. Room transactions give coherent reads and replacements across instances. No jobs, flows, background downloads or retained lifecycle state. Cancellation propagates and unfinished transactions roll back; reads never repair storage.

## State transitions and identity reuse

| Event | Regression test in `AuthoritativeRoomStorageTest` |
| --- | --- |
| Close/reopen, equal orders, missing/zero order, reversed arrivals, exhaustive pages | `orderingInvariant_restartAndBackwardTraversal` |
| Same item revised, explicit-null clear, removed item, epoch changed or reused | `replacementInvariant_revisionsAndTokens` |
| Empty/one-row snapshot and page limits | `pageInvariant_emptyAndSingleItem` |
| Incomplete applied progress and saved coverage through local exhaustion | `certificationInvariant_pagesNeverAdvanceSavedEvidence` |
| Duplicate/invalid replacement, IO failure, cancellation | `atomicInvariant_failedMutationsPreserveBothFamilies` and `validationInvariant_rejectsInvalidReplacements` |
| Tail trimming | `retentionInvariant_trimDropsCertificationOnly` |
| Identical ids under other owner/family, scoped deletion/recreation | `ownershipInvariant_familiesAndRemovalStayIsolated` |
| Existing version 1 database upgraded | `migrationInvariant_preservesLegacyContentAndEvidence` |
| Growing omitted history | `boundedInvariant_onlySelectedPayloadsDecode` |

## Error handling

Mirror legacy expected SQLite/IO/security/invalid-data classification: null full/page reads, causeless `ConversationCacheException` in mutation results, static operation/failure logs. Cancellation and unclassified programming failures propagate. Page, token, schema records and read counters expose no content, owner or cursor in strings.

## Testing strategy

Write real on-disk Robolectric tests first, observe their failure, then implement. Fresh instances verify full raw round trips, certification, exhaustive traversal and owner/family isolation. Inject failure immediately before commit to prove rollback after SQL changes; corrupt storage proves graceful reads without repair. Build a version 1 database from its exported schema and reopen through Room's automatic migration. Read counts compare 200-item windows over small and larger histories and prove no payload read for the single continuation probe. Probe all listed invariants with production operations. Retain existing authoritative file/cache, legacy Room and codec regression tests. No UI/device/live tests apply to this data-only backend. Run focused unit tests, lint, assembleDebug, formatting and final pre-verify with compile checks; record executed/failed/skipped XML counts.

## Open Questions

None. Forecast: approximately 1450 written lines including generated schema, tests and this plan; three production files, three new public types including the nested token, no consumer signature updates, five acceptance criteria and fewer than ten classified reject branches. This fits the hard 1600-line ceiling.

## Security review

**Verdict:** PASS

- Trust boundaries: saved daemon JSON remains inert. `retained` validates before writes; selected reads check raw identity/revision and indexed ordinal ownership. No legacy conversion.
- Tokens/secrets: no credentials are stored. Continuations contain local database generation/owner information, are redacted and checked against the committed header; generation UUIDs are identity markers, not authorization secrets.
- Files/storage: bound SQL values prevent owner strings becoming paths. Fixed database construction stays in `noBackupFilesDir`, excluding WAL/journal/SHM sidecars. Reuse approved plaintext app-private content storage; rooted-device content access remains outside this cache threat model. Auto-migration adds tables without dropping legacy data.
- Android surface: no components, intents, providers, WebViews or UI changes.
- Cryptography: no crypto or Noise changes and no keys or nonces introduced.
- Network/IO: no network path; indexed LIMIT queries bound payload reads to the requested page and one payload-free probe.
- Errors/logs: SHOULD FIX in implementation: redact every new result/entity/token string and strip original exception causes. Test sensitive sentinels in results and diagnostics.
- Concurrency: Room transaction owns facts and certification together, including removal; cancellation rollback tested. Tokens reject replacement even when epoch is reused.
- Threat model: relay tampering/reordering and token theft remain with existing Noise/Keystore boundaries. Hostile saved facts are validated and kept inert. UI leakage remains with existing rendering; this ticket creates no UI. No new relay or credential exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-15
