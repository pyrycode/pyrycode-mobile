# Import saved threads and activate Room storage (#2161)

## Files read

- `data/cache/ConversationCache.kt`: the seam to keep; graceful reads, reporting mutations, `removeConversation`/`removeHost` scope.
- `data/cache/FileConversationCache.kt`: `readDecodedThread` (rows then lazily validated history), `readAuthoritativeThread` (version and owner check, `toSnapshot`), `removeConversation`/`removeHost` (delete both source documents), `failureCode`. Today's reader semantics are the import contract.
- `data/cache/LegacyRoomStorage.kt`: `writeThread`/`storeHistory` (validated replacement), `mutate`/`failureCode`, `newestPage`/`beforePage`, `lastReadCounts`.
- `data/cache/AuthoritativeRoomStorage.kt`: `writeAuthoritativeThread` (retained raw facts, header with epoch/appliedVersion/checkpoint/coverage).
- `data/cache/ConversationCacheDatabase.kt`: version 2, `removeScope` (both families, one transaction, nests inside an outer `withTransaction` as the storages already do).
- `data/cache/SavedAuthoritativeThread.kt`: `retained`, `toSnapshot` validation.
- `data/repository/CachingConversationRepository.kt`: `historyWrites`, generation capture, `persistedThreads`/`persistedAuthoritative` baselines, `NonCancellable` mutation+bookkeeping, `delete`. Unchanged; lock order is wrapper, then cache.
- `di/AppModule.kt` (`ConversationCache` single), `di/ConversationCacheDatabaseFactory.kt` (`openConversationCacheDatabase`), `di/ObservablePairedServerStore.kt` (`forgetRemovedHost` calls `removeHost` under `NonCancellable`).
- `docs/knowledge/features/conversation-cache-layout.md` (Layout, #2162/#2163 sections), `caching-conversation-repository.md` (State and concurrency): removal ordering belongs to wrapper fences plus the cache's own serialization; reads never repair.
- Tests to mirror: `LegacyRoomStorageTest` (on-disk Robolectric Room, fragmented fixture traversal), `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence` (builds a v1 file and upgrades through the auto-migration chain), `CachingConversationRepositoryTest` delegate shape.

No in-flight `feature/*` branch touches these files.

## Context

#2162 and #2163 delivered unbound Room backends. The app still binds `FileConversationCache`. This ticket binds a migration-aware cache at the `ConversationCache` seam and imports each owner's two file families once, with no loss, no resurrection and no overwrite of newer database content. Metadata (`conversations.json`), read positions and attachments stay where they are. A decision record is not needed: Room was approved in ADR 0009, and this ticket adds an import ledger, not a new storage choice.

## Design

**`MigratingConversationCache`** (new, `data/cache/`), implements `ConversationCache`:

- Constructed from the cache root `File` and a `ConversationCacheDatabase`, plus an injected IO dispatcher; an internal constructor adds a `beforeCommit` test hook passed to both storages and to import commits.
- Owns one `FileConversationCache(root)` for metadata, read positions and source reading, and one `LegacyRoomStorage` and `AuthoritativeRoomStorage` (exposed `internal` for #2068's page reader).
- `readConversations`, `writeConversations`, `readReadPositions`, `writeReadPositions` delegate to the file cache unchanged. A successful `writeConversations` also lifts this process's host write fence (below).
- Thread operations (`readThread`, `writeThread`, `readHistoryPosition`, `writeHistoryPosition` → legacy family; `readAuthoritativeThread`, `writeAuthoritativeThread` → authoritative family) run under one cache `Mutex`, first calling `ensureImported(host, conversation, family)`, then delegating to the Room storage.
- `newestLegacyPage`/`beforeLegacyPage` (internal) import first, then delegate, so a page reader can never bypass import.

**Import ledger.** Database version 3 adds `file_imports(host, conversation, family, outcome)` (primary key on the first three) through `AutoMigration(from = 2, to = 3)`; no destructive fallback. Families are `legacy`, `authoritative` and `host` (host-wide row, conversation `""`). Outcomes are static: `absent`, `imported`, `invalid`, `unsupported`, `superseded`, `retired`. A `FileImportDao` reads the owner's row or the host row, upserts rows and clears a host's rows.

**`ensureImported` contract.** Returns ready or unavailable.
1. A key already settled in this instance, or any ledger row (owner row or host row), is ready. Nothing re-reads the source.
2. Otherwise classify the source through the file cache's validated codec (below). An IO or security failure is `unavailable`: nothing is recorded, the bytes stay, the next operation retries.
3. Otherwise, in one Room transaction: if the family already holds a database header, record `superseded` and import nothing (a leftover file never overwrites database content); else write the decoded content through the storage's in-transaction import helper and record `imported`, or record `absent`/`invalid`/`unsupported`. Then `beforeCommit`. A transaction failing with `invalid_data` records `invalid` in a second marker-only transaction; any other classified failure is `unavailable`.
4. Reads proceed either way and read what the database holds. **Writes of a family whose import is unavailable fail** with a causeless `ConversationCacheException("… import_unavailable")`, so a repository whose restore saw nothing can never replace unimported offline content.

**Validated codec seam** in `FileConversationCache.kt` (internal, under the file mutex): `legacySource(serverId, conversationId)` and `authoritativeSource(serverId, conversationId)` return a sealed `SavedSource<T>`: `Absent`, `Readable(value)`, `Rejected(code)` with `invalid_data` or `unsupported_version`, or `Unavailable(code)`. A version-only header is decoded first, so another version is `unsupported_version` and never a decode error. Legacy `Readable` carries rows exactly as `readThread` decodes them, plus the history exactly as `readHistoryPosition` returns it: invalid optional history yields `null` while the rows stay readable. The lazy-history body of `readDecodedThread` moves into one private function both paths call, so they cannot drift. Authoritative `Readable` carries the `SavedAuthoritativeThread` after the same version, exact-owner and `toSnapshot` checks `readAuthoritativeThread` performs, through one shared private decoder.

**In-transaction import helpers.** `LegacyRoomStorage.importThread(host, conversation, rows, history)` and `AuthoritativeRoomStorage.importThread(host, conversation, saved)` are internal. They run the existing family-local delete-insert bodies (`storeHistory` validation, `retained`) without their own mutex or transaction, so content and marker commit together. The public write paths are unchanged.

**Removal and retirement** (under the cache mutex):
- `removeConversation`: one transaction runs `removeScope(host, conversation)` and records `retired` for both families. Then the file cache's `removeConversation` deletes the metadata entry, read position and both source documents. The result fails if either step failed. Because the tombstone commits first, a failed source cleanup or a restart cannot re-import.
- `removeHost`: one transaction runs `removeScope(host, null)`, clears the host's ledger rows and records the host-wide `retired` row. Then the file cache's `removeHost` runs. The host is added to an in-memory write fence: thread-family writes for it fail (`host_retired`) until a later successful `writeConversations` for that host, which is the first thing a re-paired host's live list does. A queued, held or final save from the forgotten host's closing repository therefore cannot recreate content. After a restart no stale save exists, and the durable host row keeps leftover sources from importing.

Source files are kept after a successful import: the ledger prevents re-import, and a downgrade still finds them. They go when the conversation or host is removed.

**Binding.** `openConversationCache(context)` in `di/ConversationCacheDatabaseFactory.kt` builds `MigratingConversationCache(File(noBackupFilesDir, "conversations"), openConversationCacheDatabase(context))`. `appModule`'s `ConversationCache` single calls it. `FileConversationCache` stays for its own tests and as the metadata store.

## State and concurrency model

No new scope, job or flow. All cache work is caller-owned suspend work on the injected IO dispatcher. Lock order: repository `historyWrites`, then the `MigratingConversationCache` mutex, then a storage's or file cache's own mutex, with no callbacks upward. The cache mutex serializes import decisions, saves and scoped removal, so a held import or save finishes before a removal starts, and the removal wins. Cancellation inside an import transaction rolls it back with no marker, so the source stays recoverable. The repository's existing `NonCancellable` blocks cover mutation plus baseline. Room transactions give cross-instance atomicity. The settled-key set and the host fence are in-memory sets guarded by the mutex.

## State transitions and identity reuse

| Event | Test (`MigratingConversationCacheTest` unless named) |
| --- | --- |
| Restart after committed import; repeated import | `idempotenceInvariant_committedImportIsNeverRepeatedOrOverwritten` |
| Interruption (IO failure, cancellation) before commit | `atomicityInvariant_interruptedImportKeepsSourceAndRetries` |
| Same ids under another host, another case, other family | `ownershipInvariant_bothFamiliesMigrateExactlyAndStayIsolated` |
| Rejected, unsupported, wrong-owner or unavailable source | `classificationInvariant_rejectedSourcesArePreservedNeverEmptyReplaced` |
| Readable rows with invalid optional history | `ownershipInvariant_invalidOptionalHistoryImportsRowsWithoutCertification` |
| Deletion while import or save is held; failed source cleanup; restart | `retirementInvariant_deletionWinsOverHeldWorkAndRestart` |
| Host forgetting while a save is held; late final save; restart; re-pair | `retirementInvariant_hostForgettingFencesSavesAndRestart` |
| Incomplete progress and certification through import and pages | `certificationInvariant_importAndPagesKeepSavedCheckpoint` |
| Fragmented 18000-row file migrated, paged newest/before | `boundedInvariant_fragmentedFixtureMigratesAndPagesWithinBounds` |
| Repository restore, save, delete over migrated storage, fresh instances | `repositoryInvariant_restoreSaveAndDeleteUseRoomStorage` |
| Database v1/v2 file upgraded | existing `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence` now runs the 1→2→3 chain |

## Error handling

Reads stay graceful: an unavailable import or a database failure yields the storage's empty/null fallback. Mutations return causeless `ConversationCacheException` with static codes (`io`, `denied`, `invalid_data`, `import_unavailable`, `host_retired`). Cancellation and unclassified programming errors propagate. Logs: `event=thread_cache_import family=<legacy|authoritative> outcome=<static>` and `operation=… code=…`, never owners, paths, cursors or content.

## Testing strategy

Robolectric unit tests over real temporary source files written by `FileConversationCache` and an on-disk Room database, reopened between assertions (fresh `MigratingConversationCache` and `ConversationCacheDatabase` instances), written first and seen failing. Hold points use the internal `beforeCommit` hook (suspend on a `CompletableDeferred`, or throw). The unavailable-source probe makes the source unreadable and is skipped (`assume`) where the process can still read it, for example as root. `ConversationCacheFactoryTest` (Robolectric, `di/`) proves `openConversationCache` writes thread content into `conversation-cache.db` under `noBackupFilesDir` and nothing under `filesDir`. No device-only test: Robolectric gives the real platform paths and the existing `ConversationCacheBindingInstrumentedTest` keeps proving metadata placement. Retained unchanged and run: `AuthoritativeThreadCacheTest`, `CoalescedThreadWritesTest`, `DecodedThreadRestoreTest` (#2050 fixture, timing and exact rows), `LegacyRoomStorageTest`, `AuthoritativeRoomStorageTest`, `FileConversationCache*Test`.

## Open Questions

- Does Room accept the outer `withTransaction` around the storage import helpers and `removeScope`? Expected yes: the storages already nest `removeScope` inside `mutate`'s transaction.

Forecast: about 370 production lines, about 700 test lines, and this plan, about 1230 written lines in total, plus the generated `roomSchemas/…/3.json`. One new exported type (`MigratingConversationCache`), one exported factory function, one consumer call site (`appModule`), five acceptance criteria, seven classified outcomes.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The file-to-database boundary is the pair `legacySource`/`authoritativeSource`. Imported content passes the same validation as today's readers (row uniqueness and settled rows, history coverage validation, `toSnapshot`, exact owner envelope), and the storages validate again inside the import transaction. Owners come only from the caller's exact ids, never from hashed filenames. SHOULD FIX: the authoritative owner check must compare both ids exactly, so a document copied under another conversation's hash is `invalid` and never imported. A probe covers it.
- [Tokens] No findings. No credentials are stored. Ledger rows hold owner ids and static outcomes only.
- [Files and storage] Paths still derive from SHA-256 of the ids. The database and its sidecars stay in `noBackupFilesDir`, proven by the factory test. Plaintext app-private storage keeps the existing threat model (ADR 0009). Encryption at rest is out of scope for this migration. Rejected bytes are never rewritten or deleted except by explicit removal.
- [Android surface] No findings. No component, intent, provider or WebView is added.
- [Cryptography] No findings. The design adds no primitives and uses SHA-256 only for existing namespacing.
- [Network and I/O] No findings. Nothing downloads. Import is bounded by the existing 100000-row cap, and page reads go through bounded storage pages after import.
- [Errors and logs] SHOULD FIX: import logs and exceptions carry static family/outcome/code only. A test asserts that sentinel owner and content strings never reach `RelayLog` or exception messages.
- [Concurrency] One cache mutex orders import, save and removal under the wrapper's `historyWrites`. Tombstones are durable and commit with the removal. The in-memory host fence stops stale final saves in-process. SHOULD FIX: lift the fence only after a successful `writeConversations`, never on reads. OUT OF SCOPE: a stale conversation-list write from a forgotten host landing after re-pair is the existing metadata behaviour of the file cache, not changed here.
  - Revised 2026-10-17 (rework, see Revisions): a list write must not lift the fence, because the forgotten host's own list collector can write one after `removeHost` and before the closing repository's final save. Only a saved pairing lifts it (`admitHost`). A deferred import that commits content after this process served or refused that owner without it leaves every write of that family refused until restart, since no caller can show its view includes the import.
- [Threat model] Relay and daemon threats are unchanged: imported content is saved daemon data that stays inert text. A rooted-device reader of app-private storage is unchanged in scope. Interrupted imports cannot expose a half-imported thread, because content and marker commit together. No UI surface.
  - Revised 2026-10-17 (rework, see Revisions): offline history is also at risk when an import is deferred. A restore that ran while the import was unavailable read nothing, and a save merged from it would replace the content once the import commits. The `import_unobserved` refusal closes that path.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/conversation-cache-layout.md`, “Layout”: version 3 `file_imports` ledger, the binding is now active, and source files kept until removal.
- `docs/knowledge/features/conversation-cache-contract.md`, “The contract”: import classification, `import_unavailable` write refusal, superseded and retired outcomes.
- `docs/knowledge/features/conversation-cache-removal.md`, “Removal on unpair”: durable host retirement and the in-process write fence lifted by `writeConversations`.
- `docs/knowledge/features/dependency-injection.md`: `openConversationCache` replaces the `FileConversationCache` binding.

## Revisions

### 2026-10-17: rework after the first verifier review

1. **A write never replaces content its caller did not see** (MUST FIX 1). `ensureImported` now reports whether the call committed the file's content (`Arrived`). The cache remembers each owner and family it read, or refused to write, while the import was unavailable. Content that arrives after such a view marks that key unobserved, and so does content that a write's own import committed. Writes of an unobserved key fail with the static code `import_unobserved` until the process restarts. A later read does not lift this. The repository's open collection, and its `drawnThreads` rows that `writeHistoryPosition` reuses, can still hold the empty restore, and the cache cannot tell those writers from a fresh one. After a restart every restore reads the imported content. An import that settles as `absent`, `invalid`, `unsupported` or `superseded` leaves the database as an earlier read saw it, so it marks nothing. Probes: `classificationInvariant_deferredImportIsNeverReplacedByAnUnobservedWrite` (a Room `io` failure, both families, and a write that performs the import) and `classificationInvariant_unreadableSourceIsRetriedAndBlocksWrites` (an unreadable source, both families).
2. **Rows-only retry when Room rejects only the history** (MUST FIX 2). This departs from Design step 3. `readHistoryPosition` validates the saved claims and then serves `retainedBy(rows)` without validating again. Room's `storeHistory` validates the claims it stores. Suppose two rows share one history entry id and one row's proof is stale. Retention drops that entry's id with the stale row and leaves the other row's order claim outside every span. The reader serves that projection, and Room rejects it as `invalid_data`. Claims that do not validate certify nothing, so the import retries with the rows and no history position, as today's reader treats any other invalid optional history. It logs `outcome=history_rejected` and records `imported`. Only a retry that also fails as `invalid_data` records `invalid`. Probe: `ownershipInvariant_historyOnlyRoomRejectsImportsRowsWithoutCertification`.
3. **The host fence lifts only on a saved pairing** (SHOULD FIX, host fence). `writeConversations` delegates to the file cache again and lifts nothing. `ConversationCache.admitHost(serverId)` defaults to a no-op, and `MigratingConversationCache` uses it to lift the fence. `ObservablePairedServerStore` gains `onHostSaved`, called after a successful `save`, and `appModule` binds it to `admitSavedHost`. Probes: `retirementInvariant_hostForgettingFencesSavesAndRestart` (a list write between `removeHost` and the final save, and a list write after re-forgetting) and `ConversationCacheFactoryTest.repairInvariant_savedPairingReadmitsTheForgottenHostsThreadWrites` (unpair and re-pair through the store).
4. **One transaction classifier** (SHOULD FIX, duplication). `ConversationCacheDatabase.classifiedTransaction` runs a block and then the `beforeCommit` hook in one transaction. It returns `CacheTransaction.Committed(value)` or `CacheTransaction.Failed(code)` through the single private `failureCode`. Both storages' `read`/`mutate` and the migrating cache use it, so import decisions read a typed code instead of parsing exception messages.
5. **Narrower surface** (NITs). The storages are private to `MigratingConversationCache`. Both families have import-first page wrappers (`newestAuthoritativePage`/`beforeAuthoritativePage` beside the legacy ones), and `legacyReadCounts` exposes the bounded-decoding counts. `MigratingConversationCache` and `openConversationCache` are `internal`. Test hold flags are atomic.

State transitions added by this revision:

| Event | Test |
| --- | --- |
| Restore while the import is unavailable, then a save after it commits; a write that imports | `classificationInvariant_deferredImportIsNeverReplacedByAnUnobservedWrite`, `classificationInvariant_unreadableSourceIsRetriedAndBlocksWrites` |
| Room rejects only the history the reader serves | `ownershipInvariant_historyOnlyRoomRejectsImportsRowsWithoutCertification` |
| Stale list write after forgetting; re-pair through the pairing store | `retirementInvariant_hostForgettingFencesSavesAndRestart`, `ConversationCacheFactoryTest.repairInvariant_savedPairingReadmitsTheForgottenHostsThreadWrites` |

The Error handling codes gain `import_unobserved`.
