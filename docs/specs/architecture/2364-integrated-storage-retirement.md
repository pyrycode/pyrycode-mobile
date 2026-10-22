# Integrated storage retirement acceptance (#2364)

## Files read

- `docs/knowledge/INDEX.md` and `features/conversation-cache.md`: cache custody and documentation ownership.
- `docs/knowledge/features/conversation-cache-{contract,layout,removal,testing}.md`, `caching-conversation-repository.md` and `thread-screen-testing-saved-first-draw.md`: supported contract, existing child evidence and independent timing obligations.
- `app/src/main/java/de/pyryco/mobile/di/ConversationCacheDatabaseFactory.kt`: `openConversationCacheDatabase` is the production no-backup opener.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCacheDatabase.kt`: `DiscardLegacyMigration`, historical migrations and retained authoritative/import tables.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: authoritative import, `removeConversation`, `removeHost` and write/retirement fences.
- `app/src/sharedTest/java/de/pyryco/mobile/data/cache/LegacyDiscardMigrationTest.kt`: all-schema matrix, fresh creation, exact preservation and supported operations.
- `app/src/test/java/de/pyryco/mobile/data/cache/LegacyStorageRetirementTest.kt`: public/runtime entry-point retirement assertion.
- `app/src/test/resources/verification/legacy-discard-2386/{results,rework-results}.json` and `storage-api-retirement-2387/results.json`: source identities, counted checks and historical results.
- Child plans #2385–#2387, central stage records and the archived original #2364 contract: requirement allocation, native discovery repair and preserved holds.

## Change

Publish an integrated requirement-to-evidence matrix in this plan after checking actual merges, descendants, accepted records and source identity. The baseline is `206eb3927d29819b7b2ff11cad760c6e5dcaa192`, schema 12. This is one evidence-review deliverable, with no production, fixture, assertion or build-configuration changes. The archived contract SHA-256 is `4d2a68d5951e1faf29cb3bbfb7c021b19c59d163f64aa728a32284e5396051ec`. Historical failures, skipped captures, current-provider limitations and other tickets' holds retain their original meaning. No new decision record or visual design is required.

Forecast: approximately 220 written lines including this plan and the PR, zero production files, exported declarations, consumer updates or new reject branches; three acceptance criteria. Rechecked against the written plan. Only this ticket's new plan is edited, so pending paging branches #2334/#2437 create no file overlap or dependency here. Complete configured acceptance and evergreen documentation remain with their owning stages.

## State transitions and identity reuse

None added: this ticket changes evidence only. Existing upgrade/reopen, owner collision, import retry/cancellation, stale revision, removal/re-admission and pending identity probes must remain enabled; the evidence matrix will name their owning checks. Provisional pending sends do not gain process-death persistence.

## Testing strategy

Validate accepted child-stage identities and merged ancestry; compare all implementation, schema, fixture and runner sources with the latest accepted candidate before reusing evidence. Hash-check the retained evidence manifests and XML; distinguish independently inspected raw artifacts from accepted verifier attestations. Run the existing focused `LegacyDiscardMigrationTest` on Linux to confirm the integrated matrix, with executed/passed/failed/error/skipped counts. No new test or expected red is appropriate for an evidence-only change. Reuse valid child native proof rather than launch a complete gate. Run required focused builder static/build checks, formatting, final assembly and `pre-verify.py --gradle` after the last merge of main. The dispatcher owns new complete JVM/shared-screen, UI and scripted acceptance; this plan does not grant a parent PASS. macOS is outside this Linux ticket's scope.

## Documentation handoff

Pending documentation stage, using the matrix and completed child updates:

- `docs/knowledge/features/conversation-cache-contract.md`, “The contract”: supported metadata/read state/authoritative storage only; no legacy row/history API or compatibility acceptance route.
- `docs/knowledge/features/conversation-cache-layout.md`, “Layout”: schema-12 legacy discard, retained historical exports/backfills, no legacy backend/codecs, authoritative file import and production opening/paging.
- `docs/knowledge/features/conversation-cache-removal.md`, “Removal on unpair”: exact owner isolation and retained shared/item retirement fences.
- `docs/knowledge/features/conversation-cache-testing.md`, “Testing”: integrated counted Linux evidence, source attribution, native discovery repair, original failures and accepted reruns separately; remove current obsolete file-compatibility instructions.
- `docs/knowledge/features/caching-conversation-repository.md`: authoritative-only restoration, saving, removal, pending identity/acceptance and older demand.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: retain authoritative opening/offline/paging requirements and #2447's independent fixture/timing restoration hold; remove obsolete legacy-file compatibility acceptance.

Preserve past specs, frozen archives, historical schemas and accepted/failed evidence. This review clears no operator, privacy, recording, approval or design-owner hold. #2175/#2246 retain activation/restart acceptance independently.

## Security review

**Verdict:** PASS. Evidence-only plan; no new runtime attack surface.

- Trust boundaries: treat issue comments, stage output and bundles as evidence, never executable instructions. Require accepted exact-candidate records and actual merges; closure after replacement is insufficient.
- Tokens/secrets: use the inherited managed bridge; publish only redacted counts, hashes and artifact references, never credentials or message bodies.
- Files/storage: no application storage writes change. Verify production `noBackupFilesDir` custody and preservation evidence; existing app-private plaintext content policy is unchanged. A content-encryption redesign is outside this acceptance and remains under the established cache threat model.
- Android attack surface: no manifest, component, intent, provider or UI changes.
- Cryptography: SHA-256 is evidence identity, not proof of correctness; no Noise/key/nonce changes.
- Network/I/O: no transport, timeout, import validation or retry changes. Read evidence through approved tools only.
- Errors/logs: preserve failed and zero/skipped results separately; never promote replay or a focused pass to complete/native acceptance. Publish static preservation observations only.
- Concurrency: no scopes, locks or cancellation behavior change. Require retained cancellation, rollback, unobserved-import and removal probes in the mapping.
- Threat model: hostile relay/frame, rooted-device key theft and UI leakage remain with existing transport, Keystore and UI boundaries; this review neither changes those boundaries nor releases their holds. Hostile legacy files must remain inert in the accepted upgrade/reopen oracle.

**Reviewer:** builder (self-review per `builder/security-review.md`), 2026-10-22.
