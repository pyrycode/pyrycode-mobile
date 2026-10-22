# Drop legacy Room tables through preserving upgrades (#2386)

## Files read

- `data/cache/ConversationCacheDatabase.kt`: schema 11, automatic migration chain, authoritative backfills, legacy entities/DAO and `removeScope`.
- `data/cache/MigratingConversationCache.kt`: legacy backend bindings; authoritative import, unobserved-write and durable retirement fences.
- `data/cache/LegacyRoomStorage.kt`: backend and exclusive page types removed with their DAO.
- `di/ConversationCacheDatabaseFactory.kt`: production no-backup opener, without destructive fallback.
- `data/cache/ConversationCache.kt`: dormant default legacy methods remain for #2387.
- `data/cache/OwnedLegacyCache.kt`: historical `OwnedRowsMigration` dependencies remain for #2387.
- `data/cache/AuthoritativeRoomStorageTest.kt`, `OwnedLegacyMigrationTest.kt`: disk fixtures and mixed-family assertions to adapt.
- `docs/knowledge/features/conversation-cache-layout.md`, `conversation-cache-removal.md`, `conversation-cache-testing.md`: exact owner isolation, fresh instances and cancelled-result delivery must remain covered.
- ADR 0009 and `docs/specs/architecture/2161-import-saved-threads-room.md`: plaintext app-private custody, atomic import ledger and unobserved-write protection.
- Parent #2364 archived body, sibling #2385 plan and refined #2386: #2385 is merged; #2387 owns remaining dormant APIs/codecs, #2364 final acceptance.

## Context

Installed and fresh databases must contain only supported authoritative storage. Remove obsolete tables and the backend/delegations that cannot compile without them in one useful slice. No new decision record: ADR 0009 already owns storage choice and custody.

Forecast: approximately 1550 written lines including the new schema, tests and this plan; large exclusive-backend/test deletions are additional. No more than two new exported declarations; four acceptance criteria; no new runtime rejection branches. Eleven coupled wrapper accesses exceed the call-site ceiling. The refiner recorded `needs-human:sizing`; #2167 → #2364 → #2386 is depth two, and a backend-detachment-only child fails the verification floor, so continue here under the existing exception. Overlapping #2266 is legacy-only work; #2334 and #2437 add authoritative paging queries/wrappers. Edits stay local and retain supported additions in either merge order.

## Design

Advance schema 11 to 12 using an explicit `AutoMigration` with `DeleteTable` declarations for `legacy_headers`, `legacy_rows`, `legacy_evidence`. SQLite drops their exclusive indexes with the tables. Preserve schemas 1–11 byte-for-byte and export 12. Keep the existing migration chain and its authoritative derived-index backfills; retain `OwnedRowsMigration` until #2387 retires its codecs. Malformed historical legacy bytes must not prevent upgrade.

Delete `LegacyRoomStorage` and its exclusive page/evidence types, database entities/DAO and legacy deletes in `removeScope`. Remove the wrapper's owned-legacy implementation, counts, paging and row/history delegations; dormant interface defaults remain until #2387. Do not add a replacement backend. Keep existing shared `file_imports` records and both-family removal markers, including historical legacy records, so no migration alters supported fences. Authoritative import, saves, reads and metadata/read-position delegation retain their implementation.

## State and concurrency model

No new jobs, scopes, dispatchers or flows. Room upgrades execute transactionally. Cache work stays caller-owned on injected IO; lock order remains repository history writes → cache mutex → storage/file mutex. Preserve import content-plus-ledger atomicity, cancellation propagation and refusal of writes based on unobserved imported content. Pending local sends remain in-memory repository/store state and gain no process-death persistence.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Every historical source version upgrades, then opens twice again with colliding case-sensitive owners | `LegacyDiscardMigrationTest.preservationInvariant_allShippedSchemasUpgradeAndReopen` |
| Oldest authoritative and latest schema upgrade on native SQLite; fresh database creation | `LegacyDiscardMigrationTest.preservationInvariant_productionUpgradeAndFreshReopen` |
| Fresh creation and repeated reopen | `LegacyDiscardMigrationTest.preservationInvariant_freshCreationAndReopen` |
| Conversation/host removal after upgrade and restart; leftover source files | migration matrix plus retained `MigratingConversationCacheTest` retirement/inert-file probes |
| Retired item replay, raw order/revisions/certification and supported save/read | migration matrix plus authoritative storage/owned-removal probes |
| Authoritative import retried, fails or cancels before/after commit | retained `MigratingConversationCacheTest` classification/atomicity methods |
| Saved pairing re-admits host after cancellation or forgetting | retained `ConversationCacheFactoryTest` repair methods |
| Pending feedback crosses authoritative restoration/acceptance | retained `AuthoritativeSendReconciliationTest` |

## Error handling

No destructive fallback or new error contract. Preserve existing static IO/denied/invalid classifications and cancellation propagation. Migration must not parse discarded content except the retained historical migration; explicitly probe malformed bytes there. Content, owners, cursors and exception messages never enter migration diagnostics.

## Testing strategy

Write an on-disk all-schema regression first and observe it fail on retained legacy tables. Construct source databases from exported schemas, seed all supported authoritative columns, raw unknown fields, colliding ids under `Host`/`host` and two conversations each, plus shared ledger and item retirements. Compare every original supported column exactly; separately assert added defaults/backfills. Open through `openConversationCacheDatabase`, close and reopen fresh instances twice. Assert table/index absence, exact schema, save/read and isolated removal. Native method covers version 2, version 11 and fresh creation on real on-device SQLite, using packaged historical schema fixtures; real storage is its device-only reason.

Adapt mixed tests by deleting only obsolete legacy assertions/fixtures; delete legacy-exclusive backend/mutation tests that require the removed implementation. Retain authoritative failure, cancellation, isolation, paging, retirement, unobserved-write, factory no-backup/admission and pending-send tests. Keep historical evidence unchanged. Run affected classes individually on Linux, the focused native method, lint, assembly, instrumented compilation and forced formatting checks. Retain fresh XML/raw output, source identities and executed/passed/failed/skipped counts. Complete configured acceptance belongs to the dispatcher; macOS is outside this ticket's verification scope.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/conversation-cache-layout.md`, “Layout” (schema chain/backfills and supported layout); `docs/knowledge/features/conversation-cache-removal.md`, “Removal on unpair” (retained import/retirement fences); and `docs/knowledge/features/conversation-cache-testing.md`, “Testing” (counted upgrade/reopen proof). #2364/#2387 retain the complete contract/repository/thread documentation fold. Preserve historical schemas, specs, frozen archives and accepted/failed evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] Remove the legacy SQL-to-domain boundary. Authoritative file import retains exact owner/version validation; no discarded legacy data can become authoritative facts or certification.
- [Tokens] No secret generation/storage changes; fixtures use synthetic data. Never log bodies, owners, cursor values, tokens or keys.
- [Files and storage] Keep the fixed app-private `noBackupFilesDir` database and sidecars, hashed file namespaces and ADR 0009 plaintext threat model. No new encryption claim. Do not scan filenames to infer owners; leftover unknown-owner legacy files stay inert. Transactional migration removes only fixed legacy table names.
- [Android attack surface] No component, intent, provider or UI change. The native regression exercises storage only.
- [Cryptography] No key, nonce, hash derivation or Noise changes.
- [Network and I/O] No transport change. Authoritative source access failures remain retryable and cannot settle as absent; preserve original failure tests.
- [Errors and logs] No new runtime logs are needed for an automatic Room schema transition. Existing static cache operation/outcome diagnostics remain; raw fixture comparisons stay test-only.
- [Concurrency] Keep content/ledger atomic commits, cache serialization and unobserved-write fences. Cancellation and program errors propagate unchanged. Probe fresh instances, import cancellation and removal races with the original tests.
- [Threat model] Malicious relay/frame and UI-side leakage remain governed by unchanged transport/UI boundaries. Rooted plaintext access remains ADR 0009's accepted scope. Cross-owner deletion and stale resurrection are concrete storage threats: binary ownership, ledger fences and exact preservation probes cover them.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-22

## Revisions

### 2026-10-22: share the exact disk contract with native execution

Use one `AndroidJUnit4` class in `sharedTest` for the matrix and focused native method, with historical schemas supplied as JVM resources and test-APK assets. This avoids duplicating the preservation oracle. Seed nested agents as well as unknown raw facts so authoritative placement backfills have nontrivial parent/witness work. The matrix also exercises leftover known/unknown-owner legacy files, authoritative one-time import, newer saves, durable host/conversation fences and pending feedback across restoration. Retirement snapshots intentionally have zero checkpoint/no coverage; certified sibling conversations retain nonzero evidence. No runtime contract changed.

The existing nested-agent migration probe previously modelled v10 by changing only the version of a current database. Restore v10's legacy table/index DDL from its exported schema before that version change; otherwise the new drop migration correctly fails against a fixture missing historical tables. Keep its full nested-agent, structural-write, source-size and repeated-reopen assertions unchanged.

### 2026-10-22: discover native preservation coverage in the configured UI gate

Verifier finding 1 found that explicit selection of a shared test did not make it part of the configured UI gate, which discovers classes only under `androidTest`. Add `LegacyDiscardMigrationDeviceTest.preservationInvariant_productionUpgradeAndFreshReopen` there and delegate to the existing shared method. This keeps the exact schema 2, schema 11 and fresh-creation oracle, including two reopens and supported-operation assertions, while retaining all three JVM methods unchanged. Real on-device SQLite remains the device-only reason. The native row above now uses this device entry point; the shared method remains JVM coverage.

The repair is one behaviour: make required native preservation coverage discoverable. It adds one test class, no production consumer updates or reject branches, and fewer than 100 written lines including evidence and this revision; the complete branch stays below 1600 written lines under the recorded depth-two coupling exception. Security review remains PASS: the entry point reuses synthetic, app-private fixtures and their existing bounded cleanup, without adding a storage, logging, credential or concurrency boundary.

Before the repair, the gate's own `device_only_classes` omitted the entry point. Afterward, verify its inclusion, rerun the shared JVM matrix and the focused device method, and retain fresh XML, preservation observations and source/APK hashes. The complete configured UI run remains dispatcher-owned and must include this non-skipped passing device method before verifier PASS; a focused pass is not that acceptance result. Preserve the earlier configured omission and all historical evidence.
