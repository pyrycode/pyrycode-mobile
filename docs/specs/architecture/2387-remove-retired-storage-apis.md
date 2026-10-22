# Retire dormant legacy storage contracts (#2387)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: obsolete row/history methods and supported metadata/read-state/authoritative contract.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: exclusive row/history codecs, supported authoritative envelopes and scoped file cleanup.
- `app/src/main/java/de/pyryco/mobile/data/cache/OwnedLegacyCache.kt`: unused owned-row capability and migration identity dependency.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: `OwnedRowsMigration` is the last production row-decoder consumer.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: authoritative import, cancellation, unobserved-write and retirement fences remain supported.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `HistoryPosition` is storage-only.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: exclusive coverage models, row proofs and identity helpers have no supported runtime consumer.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/RestoredSubagentNestingTest.kt`: preserve parent attribution and rendered nesting using supported facts.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt`: retain deletion, archive, attachment and static-log assertions with authoritative seeds.
- `docs/knowledge/features/conversation-cache-contract.md`, `conversation-cache-removal.md`, `conversation-cache-testing.md`: retain exact ownership, committed import cancellation and schema/reopen oracle; stale docs are documentation-stage work.
- Parent #2364 archived scope and allocation; merged #2385/#2386 retirement contracts and #2387 refinement establish this leaf's boundaries.

## Context

At `2df547ab2`, runtime removal, authoritative-only import and preserving schema 12 have landed. Dormant successful-empty APIs and legacy codecs remain, including a historical migration callback. Remove this single storage family without changing supported UI, wire or storage semantics. No new decision record is required.

Scope forecast: roughly 1100 surviving written lines plus exclusive deletions, no new exported production declarations or reject branches. More than 15 retained test consumers exceed the coupling ceiling. The existing `needs-human:sizing` marker records the depth-two exception (#2167 → #2364 → #2387); the removal and its necessary retained proof stay together under the verification floor. No further split.

Remote overlap: #2175/#2325 touch `ConversationRepository`; #2266/#2334/#2437 touch `ConversationCacheDatabase`. Their supported authoritative additions are independent of the obsolete declaration/callback removal; keep edits local. #2447 owns saved-first-draw restoration.

## Design

Delete row/history methods, owned legacy capabilities, exclusive serialized row/history/coverage records and helpers. Preserve authoritative facts, import/page/window APIs, metadata, read positions, removal paths and durable legacy ledger markers. Keep the obsolete-file path only for explicit scoped cleanup. Remove the obsolete `OwnedRowsMigration` content backfill while keeping the named auto-migration spec and exported schemas, so Room still traverses every shipped version without parsing legacy bytes.

Delete exclusive codec/legacy tests, including skipped compatibility cases. Adapt mixed doubles and fixtures to `SavedAuthoritativeThread` and production authoritative projection; no compatibility adapter or legacy backend is introduced. Keep original supported assertions, including parent/attachment identities, cancellation, rollback and unobserved writes. Historical discarded-file bytes are inert literal test data rather than output of a retired writer.

## State and concurrency model

No jobs, dispatchers, locks or flow lifetimes change. Calls retain caller cancellation; wrapper mutex, Room transactions and file atomic writes keep their existing order. Pairing admission and host retirement remain unchanged. No wire changes.

## State transitions and identity reuse

| Event | Retained proof |
| --- | --- |
| Every shipped schema upgrades, then reopens twice | `LegacyDiscardMigrationTest.preservationInvariant_allShippedSchemasUpgradeAndReopen` |
| Fresh creation and reopen | `LegacyDiscardMigrationTest.preservationInvariant_freshCreationAndReopen` |
| Old malformed bytes on the 3→4 path | all-schema matrix; do not invoke a row decoder |
| Known/unknown-owner legacy files across reopen | matrix and `MigratingConversationCacheTest.ownershipInvariant_legacyFilesStayInertAcrossReopens` |
| Colliding host/conversation ids, isolated removal and retirement | matrix, migrating-cache retirement probes and authoritative owned-removal probes |
| Cancellation after import commit; retry without observing imported facts | migrating-cache cancellation/unobserved-write probes |
| Owned updates, removal, reused epoch and stale revisions | retained `AuthoritativeOwnedUpsertTest` / `AuthoritativeOwnedRemovalTest` |
| Parent attribution and attachments after supported restore | adapted nesting and stream cache assertions |

## Error handling

No new error branches. Supported failures remain coded, causeless storage outcomes; cancellation propagates. Old bytes no longer have a content decoder and cannot acquire authoritative certification. Historical SQL/schema validation still fails normally rather than destructively recreating a database.

## Testing strategy

First add a focused retirement assertion against public/runtime storage declarations and observe it fail before deleting APIs. Retain and run the all-schema oracle, authoritative storage/import/removal/factory tests and affected retained-consumer classes on Linux. Native `LegacyDiscardMigrationDeviceTest.preservationInvariant_productionUpgradeAndFreshReopen` reuses the existing oracle against real SQLite; run this focused method and retain XML/counts/source identity and preservation output. Compile Android/shared consumers, run lint, assemble and formatting, then final post-merge assembly and `pre-verify.py --gradle`. Complete configured gates, including counted native-method discovery/execution, remain dispatcher-owned. macOS is outside scope. No new live scenario is required.

## Open Questions

None. Follow actual consumers during removal; record any design change under Revisions.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/conversation-cache-contract.md` and `caching-conversation-repository.md` to expose only supported storage APIs. Update `thread-screen-testing-saved-first-draw.md` to remove obsolete file compatibility instructions while retaining authoritative opening, paging and timing requirements. Link #2385/#2386 import/schema evidence. #2364 owns the final integrated fold. Preserve past specs, archives, exported schemas and all historical evidence.

## Revisions

### 2026-10-22: measured retirement and retained proof

The implementation at `ddce853b46e9e7b7679d2e366a5a4af528abed6a` writes 165 lines and removes 2900 lines across 22 source/test files, including six production files. The raw added/deleted count exceeds 1600; the existing depth-two sizing exception and `needs-human:sizing` marker apply. Most removed lines are exclusive codecs, coverage models and their tests. Retained mixed consumers use supported facts; historical discarded files are literal test data. No exported production declarations or runtime reject branches are added. The design and security verdict remain unchanged.

The existing recorded attachment-history replay executes the adapted cache-clearing helper and original native assertions. The focused scripted `stream` selection also exercises its existing queued-echo companion. These checks supplement the upgrade oracle; they do not replace complete configured acceptance.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed by design: remove all legacy content parsing, including the historical migration callback; malformed old bytes must never become authoritative facts. Preserve validated authoritative envelope admission.
- Tokens/secrets: no credential generation, storage or access changes. Pairing and Keystore ownership remain in existing components.
- Files/storage: app-private no-backup roots, hashed owner paths, atomic file writes and transactional Room storage remain unchanged. Explicit cleanup retains exact host/conversation scope. Existing plaintext content custody is unchanged; encryption redesign is outside this retirement and remains with the established cache threat model.
- Android attack surface: no component, intent, provider, UI or exported surface changes.
- Cryptography: no Noise/key/nonce changes; hashing retained owner paths uses the existing SHA-256 implementation.
- Network/I/O: no network changes; imports retain retryable I/O classification and supported validation.
- Errors/logs: no payload, owner identity, cursor, token or exception cause may be added to logs. Existing static diagnostics and privacy assertions remain.
- Concurrency: preserve existing lock order, caller cancellation, atomic rollback and post-commit unobserved-import fences; retain the cancellation and owned-write probes.
- Threat model: relay ordering/flooding, key theft and UI leakage are unchanged transport/Keystore/UI responsibilities. Hostile stored legacy content becomes inert; hostile authoritative facts remain validated at the existing supported boundary.
