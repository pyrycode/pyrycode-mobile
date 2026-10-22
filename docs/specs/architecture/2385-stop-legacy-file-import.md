# Stop importing legacy thread files (#2385)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: `ensureImported`, `importFamily`, `importSource`, thread/page entry points and retirement fences.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `legacySource`, `authoritativeSource`, `source` and scoped removal.
- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: `importThread`, retained Room reads/writes and paging.
- `app/src/test/java/de/pyryco/mobile/data/cache/MigratingConversationCacheTest.kt`: on-disk reopen, classification, cancellation delivery and removal probes.
- `app/src/test/java/de/pyryco/mobile/di/ConversationCacheFactoryTest.kt`: production no-backup opener and saved-pairing admission.
- `docs/knowledge/features/conversation-cache.md`, `conversation-cache-layout.md`, `conversation-cache-testing.md`: source custody, fresh-instance reads and bounded cancellation cleanup. Keep `cancelAndDrain` and its adversarial tests.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md` and `docs/specs/architecture/2161-import-saved-threads-room.md`: existing plaintext app-private threat model, atomic content/ledger commits and unobserved-write fences.
- #2364 archived original scope, #2386/#2387 allocations and #2367 completion: runtime consumers are detached; schema/API retirement and native upgrade proof belong to siblings.

## Context

Legacy files must never recreate obsolete content. This single behavior can ship while dormant legacy Room APIs remain. No schema, runtime rendering, wire contract or dependency changes are needed; no new decision record.

In-flight #2266, #2334 and #2437 overlap the cache wrapper. Their changes concern lifetime/paging wrappers; preserve those additive seams. None supplies a dependency needed by this design.

## Design

Keep the existing cache seam and serialization. `ensureImported` returns Ready immediately for the retained legacy family, before any ledger or file access. All legacy thread/history/owned mutation/page operations therefore use Room only. Host write admission and explicit removal retain their existing semantics.

Delete `FileConversationCache.legacySource`, its exclusive `LegacySource` carrier and `LegacyRoomStorage.importThread`. Narrow `importFamily`/`importSource` to `SavedSource<SavedAuthoritativeThread>` and remove legacy source dispatch and optional-history retry. Keep ordinary file legacy storage/codecs until #2387; the wrapper never uses them for importing. Keep both retirement family records and existing historical ledger rows.

Authoritative decoding still validates version, owner and snapshot before importing. Content and ledger commit atomically; newer Room content and retirement records take precedence. Metadata and supported read positions still delegate to file storage. Pending sends have no dependency on this import route.

## State and concurrency model

No new state, flows, scopes or jobs. Caller-owned suspend work uses the injected IO dispatcher. Lock order remains cache mutex then Room/file storage. Only authoritative import participates in settled/servedWithout/unobserved bookkeeping. Cancellation before commit rolls back; cancelled delivery after commit preserves the unobserved-write refusal. Explicit host/conversation removal keeps durable fences and scoped source cleanup.

## State transitions and identity reuse

All tests below are in `MigratingConversationCacheTest` unless qualified.

| Event | Regression |
| --- | --- |
| Two case-distinct hosts reuse conversation ids; fresh database/cache reopens twice | `ownershipInvariant_legacyFilesStayInertAcrossReopens` |
| Pre-existing legacy Room rows coexist with different file rows | `ownershipInvariant_legacyFilesStayInertAcrossReopens` |
| Unknown-owner hashed legacy files remain on disk | `ownershipInvariant_legacyFilesStayInertAcrossReopens` |
| New authoritative save then repeated restart; pre-existing Room content without import marker | `idempotenceInvariant_committedImportIsNeverRepeatedOrOverwritten` |
| IO failure or cancellation before import commit, then retry | `atomicityInvariant_interruptedImportKeepsSourceAndRetries` |
| Invalid, unsupported, wrong-owner or absent authoritative source | `classificationInvariant_rejectedSourcesArePreservedNeverEmptyReplaced` |
| File/parent access denied across restart and recovery | `classificationInvariant_unreadableSourceIsRetriedAndBlocksWrites`, `classificationInvariant_inaccessibleSourceParentIsRetriedAcrossRestart` |
| Restore before successful retry; first write imports content | `classificationInvariant_deferredImportIsNeverReplacedByAnUnobservedWrite` |
| Read/write import commits, caller cancelled before delivery | `classificationInvariant_cancelledCommittedImportCannotReadmitUnobservedWrites` |
| Remove conversation during held import/save, restore leftover files, restart | `retirementInvariant_deletionWinsOverHeldWorkAndRestart` |
| Forget host during save, stale list write, restart and readmit | `retirementInvariant_hostForgettingFencesSavesAndRestart` |
| Saved pairing and cancelled admission behind cache mutex | `ConversationCacheFactoryTest.repairInvariant_savedPairingReadmitsTheForgottenHostsThreadWrites`, `repairInvariant_cancelledSavedPairingFinishesAdmissionAfterMutexWait` |
| Page reads after import retain exact certification | `certificationInvariant_importAndPagesKeepSavedCheckpoint` |

## Error handling

Preserve authoritative static rejection/outcome codes, retryable IO/access failures, atomic rollback and `import_unavailable`/`import_unobserved` refusals. Legacy Room error handling stays unchanged; source-file errors cannot affect its reads/writes. Logs contain only static operation/family/outcome codes, never identifiers, content, source bytes or credentials.

## Testing strategy

First add the inert-file regression and run it red against unchanged production. Adapt mixed-family tests to authoritative assertions, removing only legacy-import-only tests/helpers. Keep cancellation cleanup probes unchanged. Move factory storage and pairing proofs to authoritative snapshots. Use real on-disk Room with fresh instances and existing Robolectric fixtures, without a new device test. Existing supported read-state/metadata checks remain. Native production upgrade proof belongs to #2386 and final integration to #2364.

Run Linux focused `MigratingConversationCacheTest`, `ConversationCacheFactoryTest`, `FileConversationCacheThreadTest`, `LegacyRoomStorageTest`, `OwnedLegacyMigrationTest` and `AuthoritativeRoomStorageTest`, one class per invocation; retain raw output, fresh XML, source identities and executed/passed/failed/skipped counts. Run required lint, assemble, formatting and final `pre-verify.py --gradle`. Complete configured acceptance is dispatcher-owned. macOS is outside this ticket's scope.

Forecast: about 850 written lines including plan/tests/evidence; 4 production files, no new exported types, no signature migrations, 3 exclusive import callers, 3 acceptance criteria, no new reject branches. One useful behavior; regression and supporting plumbing stay together.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update import descriptions in `docs/knowledge/features/conversation-cache-contract.md` (The contract), `conversation-cache-layout.md` (Layout) and `conversation-cache-testing.md` (Testing). Authoritative import remains; legacy files are ignored; supported failure and retirement guarantees remain. #2364/#2387 own the complete retirement fold. Preserve historical specs, archives and evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] Remove the legacy file-to-Room boundary entirely. `authoritativeSource` retains exact envelope owner/version/snapshot validation; typed `importSource` cannot accept legacy rows as facts.
- [Tokens] No credential generation/storage changes. Cache fixtures contain synthetic data; logs retain static codes only.
- [Files and storage] Keep app-private `noBackupFilesDir` Room/file custody and the ADR 0009 plaintext cache threat model. No new encryption claim. Do not scan hashed legacy filenames or infer owners; opening/import leaves their bytes untouched. Explicit removal retains its existing scoped cleanup.
- [Android attack surface] No components, intents, providers or UI changes.
- [Cryptography] No cryptography or key lifecycle changes; existing hashed paths remain unchanged.
- [Network and I/O] No network changes. Authoritative source errors remain retryable; confirmed missing paths alone settle absent. Tests retain denied-parent recovery across restart.
- [Errors and logs] Static import classifications remain content-free; rejection tests assert no source content/owner leakage. No raw error or payload logging.
- [Concurrency] Preserve mutex order and atomic Room content/ledger transaction. Keep cancelled-result and unobserved-write probes for both authoritative read and write imports. Legacy calls skip import only, retaining Room serialization and host fence.
- [Threat model] Relay/frame handling and UI leakage are unchanged by this storage-only work. Rooted-device plaintext access remains the accepted ADR 0009 threat model. Cross-owner import and resurrection are guarded by exact authoritative ownership and durable retirement; tests probe colliding ids, leftover files and repeated reopen.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-22

## Revisions

### 2026-10-22: regression details confirmed during implementation

An empty legacy Room page is a valid empty page rather than null; the inert-file regression asserts its empty rows. The red run failed earlier, on unexpected legacy import bookkeeping, so its failure still proves the retired import path. Scoped removal tests now compare the other host's legacy source bytes directly instead of expecting those files to import. No production contract changed from the design above.
