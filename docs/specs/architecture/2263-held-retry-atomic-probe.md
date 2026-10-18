# Held-retry atomic probe on Linux (#2263)

## Files read

- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeOwnedUpsertTest.kt`: `atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts`, `fresh`, `open` and `wrapper` provide the controlled writer and separate disk-backed Room reader.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: transaction/import admission explains why the retry must be held outside Room.
- `docs/specs/architecture/2256-owned-authoritative-removal.md`: inherited rollback and pre-admission retry repair, merged through `f5bebca61f4c01d8a09842807469080a948f4318`.
- `docs/knowledge/features/conversation-cache.md`, `conversation-cache-testing.md` and `caching-conversation-repository-testing.md`: persistence requires fresh instances and controlled scheduling rather than timing assumptions.

## Change

Restore one useful behavior: an active Linux atomic rollback/final-flush probe. Preserve #2256's repair, all other probes and production code. Explicitly signal retry entry before transaction admission and await it after rollback, then assert retry remains unreleased around the exact prior-record `fresh()` assertion. After releasing retry and joining observer cancellation, retain the exact newest-record `fresh()` assertion, including untouched facts and prior certification. Keep the method visibly active when reconciling #2245 / PR #2264's ignore; that branch is a shared-file overlap, not a dependency. Retain raw red/green XML and source/count metadata under `app/src/test/resources/verification/held-retry-2263/`.

Scope: one behavior, three acceptance criteria, no exported types, production consumers or new production error branches. Forecast roughly 350 written lines including raw evidence and this plan, within the 1600-line ceiling; the code adjustment remains smaller than the estimate's 180-line allowance. The extra raw historical reports account for the evidence forecast. No UI or design-source requirement applies.

## Testing strategy

Use the existing failed Linux baseline as red evidence: #2245 run `0f7cf4bf367823bb43af94c02509cbc25ab3beb3fec037607a08c3d6d6ffea5d`, bundle digest `87b2dad6ab3434e180d9cdabef00ae1022c8424d5c5d53eebf4788875206bd25`; `baseline-red` is 1 executed/failed and `upsert-red` is 17 executed, 16 passed, 1 failed, neither skipped. Do not recreate the obsolete schedule in production.

Run `testDebugUnitTest` selecting the exact method, then the whole affected `AuthoritativeOwnedUpsertTest` class, with the existing full-retention setting when required. Inspect fresh XML for 1/1 and all current methods passing with zero skips, and retain both reports before the next run overwrites them. The retry-entry handshake and unreleased-gate assertions prove the independent reader completes while retry is held. Record source revision and test source hash with evidence. Run lint, assembly, Spotless apply/check, then final assembly and `scripts/pre-verify.py --gradle` after merging main. Complete configured gates belong to the dispatcher; macOS is outside this ticket's scope.

## State transitions and identity reuse

The named probe covers committed seed → held mutated transaction → newer facts → rollback acknowledgement → retry held outside Room → independent prior-record read → retry release → cancellation/final flush → fresh newest-record read. No application lifecycle or identity state changes.
