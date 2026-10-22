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

## Integrated evidence review — 2026-10-22

### Delivery and source identity

The managed relationship read confirms #2385–#2387 are closed leaves, with no children or truncated descendant lists. GitHub confirms actual merges, not replacement-only closures:

| Owner | Accepted candidate | Merged PR / revision | Central accepted verifier run |
| --- | --- | --- | --- |
| #2385 import retirement | `7c60f58390f9e66ee102788bda8b088bdce17675` | #2446 / `b6845bf5db508830966193632f0829bc1d08b998` | `bca6ea9cb1f35747b2bf24c06c2ccd0eb56f112e8b4e41c50f8356422b5b7c89` |
| #2386 preserving upgrade | `6f2f74a9d1d62f5dec3b0298fdbe23ea607b8531` | #2448 / `2df547ab26c1264ee79d9aca16709702487ec5cf` | `8818f8f91c255157d9cc81347b6cbcfd1be49add2a13a36081d758790eb1f18d` |
| #2387 API/codec retirement | `eda298fca8e0584107dbbb1ec3993ffd13b45eb6` | #2450 / `206eb3927d29819b7b2ff11cad760c6e5dcaa192` | `1cac1378fe6fc1580d9df9e42e70367c6fb81b974eabdd229a9c6632ec1bd7ed` |

Historical prerequisite #2363 was replaced, not implemented by closure. Its delivered fixture successor #2366 merged through #2371 at `3363ad514c3bbfa9af58b7d48d1714c1bbead3f6`; runtime successor #2367 merged through #2378 at `af6721b314e4414034a7a8b58c5630ad0e42eecb`. Runtime acceptance is central run `d482fc719f89537b595a02ab6b4700fa5a5d70a0624cb487f42f3db9df89392f`, candidate `440c460adf60e9f9a12266b6539d857d6670b873`. That historical record is macOS evidence, not a new Linux run; later storage candidates supply Linux integration evidence.

Comparing #2387's accepted candidate with this baseline gives an empty diff for all `app/`, `scripts/`, `gradle/` and root build/configuration sources. The only intervening changes are four knowledge documents. All eight indexed source SHA-256 values in `storage-api-retirement-2387/results.json` match this checkout, including production contracts/database/files, shared oracle, obsolete-byte fixture, device entry point and stream consumers. Thus the latest child already tested the integrated implementation; earlier child tests deleted during retirement are historical proof, not claimed current assertions. This ticket adds only this plan.

### Requirement-to-evidence matrix

AC numbers below refer to the archived original contract; the refined invariants retain them. Artifact roots and evidence qualifications follow the table.

| Original obligation / invariant | Delivery and named proof | Evidence disposition |
| --- | --- | --- |
| AC1: every shipped schema upgrades without destructive recreation; fresh databases contain no legacy tables/indexes/DAOs | #2386 `DiscardLegacyMigration`, production `openConversationCacheDatabase`; #2387 retains empty `OwnedRowsMigration` schema step without old-byte decoding. `LegacyDiscardMigrationTest.preservationInvariant_allShippedSchemasUpgradeAndReopen`, `preservationInvariant_freshCreationAndReopen` | Exports 1–11 retained; resulting schema 12. Latest configured JVM XML passes all three migration methods; current focused class passes 3/3. Native method covers 2, 11 and fresh, each opened then reopened twice. |
| AC1: exact authoritative facts, identities, revisions/order, epoch/incarnation, progress, coverage/certification/continuation and retirement/import fences | #2386 shared oracle `verify` compares every supported table/column with historical-schema defaults; `verifySupportedOperations` exercises retained content. #2387 reuses it after removing the obsolete callback | Latest configured native XML contains the device method 1/1 passed; accepted verifier attests nine raw `exact=true` observations. Focused native raw XML/phone log independently rechecked here. Older schemas receive only applicable defaults/backfills. |
| AC2: no legacy backend, imports, row/history/coverage APIs, owned legacy mutations, paging wrappers, codecs/models or active content-decoder dependencies | #2385 import removal; #2386 deletes legacy backend/entities/DAO; #2387 deletes dormant declarations/codecs/coverage and the historical content callback. `LegacyStorageRetirementTest.retirementInvariant_noLegacyContentEntryPointsRemain` | Latest accepted whole-diff review reports no findings; focused retirement assertion 1/1 passed. Historical migration names, schema exports, fixed cleanup paths and ledger family markers are intentionally retained, not active content routes. |
| AC2/AC3: known and unknown-owner leftover legacy files stay inert on repeated reopen; never infer ownership from hashes | #2385 `MigratingConversationCacheTest.ownershipInvariant_legacyFilesStayInertAcrossReopens`; #2386/#2387 shared oracle retains known/unknown bytes and asserts no schema resurrection/import/certification | Current migrating-cache class remains enabled (13/13 in latest child focused XML); matrix passes. Source hashes and latest configured JVM/native execution cover the final retired-API implementation. |
| AC2/AC3: valid authoritative import remains one-time, newer Room content wins, retirement prevents resurrection | `MigratingConversationCacheTest` import/retry/retirement assertions and oracle `verifySupportedOperations`; authoritative file and Room storage classes | Latest child focused counts: migrating 13, authoritative file 4, Room 13, all passed. Oracle imports under case-distinct hosts, saves appliedVersion 41, reopens and rejects retired owner/item sources. |
| AC3: two case-sensitive hosts with colliding conversation IDs; confirmed host/conversation removal isolates owners | Oracle `owners`, `verifySupportedOperations`; `AuthoritativeOwnedRemovalTest`, `ConversationCacheFactoryTest` | Latest child focused owned-removal 18/18 and factory 3/3; matrix retains other conversations and case-distinct hosts after deletion/reopen. No new owner admission is inferred from cache reads. |
| AC2/AC3: metadata, supported read retry/checkpoint state, pending-send identity/acceptance retain independent lifetimes | `FileConversationCacheTest`, `FileConversationCacheReadPositionTest`, `AuthoritativeSendReconciliationTest`; oracle pending restore assertions | Latest child focused counts 23, 3 and 7, all passed. Pending local state survives authoritative restore; snapshot excludes provisional sends. This does not promise process-death persistence for pending sends. |
| AC3: mixed suites retain failure/cancellation, atomic rollback, unobserved-import write refusal, owned-write, no-backup and saved-pairing admission assertions | `MigratingConversationCacheTest`, `AuthoritativeRoomStorageTest`, `AuthoritativeOwnedUpsertTest`, `AuthoritativeOwnedRemovalTest`, `ConversationCacheFactoryTest` | Current classes retain supported assertions; latest focused owned-upsert 17/17, other counts above. Legacy-exclusive fixtures/tests, including obsolete skipped cases, were deleted; retained obsolete-file bytes are inert data. |
| AC4: authoritative thread opening, offline reading, older demand and pending sends stay enabled | Latest child complete JVM/shared/UI/scripted gates; retained saved-database opening and descriptor cancellation; authoritative repository and pending-send checks | No storage assertion is disabled here. Latest accepted configured results below cover the integrated implementation. #2447's independent replacement timing probes are not supplied or certified by this ticket. |
| AC4: focused Linux storage/factory and actual native production upgrade/reopen, plus accepted complete configured gates | #2385 focused 89/89; #2386 focused 60/60 plus discovery-repair JVM 3/3 and device 1/1; #2387 focused 114/114, device 1/1, attachment replay 1/1 and scripted stream 2/2 | Reuse exact-source child evidence, distinguish focused/replay/native/configured runs. Current Linux focused migration class 3/3 is supplemental. Parent configured acceptance remains dispatcher/verifier-owned. |
| Documentation: all six named paths, supported import/opening/paging retained; current obsolete compatibility instructions removed; historical records preserved | Child documentation merges provide the initial fold. This plan's Documentation handoff assigns the final integrated update and evidence limits to the documentation stage | Pending documentation stage; no knowledge files edited and no historical evidence deleted. |

### Retained artifacts and counted configured results

Child committed indexes: `app/src/test/resources/verification/legacy-import-2385/results.json`, `legacy-discard-2386/results.json`, `legacy-discard-2386/rework-results.json` and `storage-api-retirement-2387/results.json`. Their original pending fields describe their creation time; the later accepted central records above establish subsequent configured completion.

For #2387, raw focused evidence remains at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2387/builder-20261022-ddce853b4/`. Rechecked all 58 manifest entries, all 15 focused JVM report identities/counts (114 executed/passed), and native/replay/scripted report hashes with no mismatches. Native report `ui-3hg5a2c8/dispatcher.xml` and `native/TEST-pixel2Api33Atd_0-_app-.xml` retain one passing method; `native/shard_0/logcat-de.pyryco.mobile.data.cache.LegacyDiscardMigrationDeviceTest-preservationInvariant_productionUpgradeAndFreshReopen.txt` contains nine exact observations. Source, app/test APK and daemon/relay identities remain in the committed index. Replay is zero-live-turn consumer evidence, not provider acceptance.

Downloaded accepted #2387 bundle using `fleet-task.py evidence 2387 --run 1cac1378fe6fc1580d9df9e42e70367c6fb81b974eabdd229a9c6632ec1bd7ed`. Bundle digest: `9333794885bccfb789b342ec160b879816d7d4d4359528f35e2e19c78d95dd6c`; all 138 SHA256SUMS entries match. Its `run.json`, `handoff/verifier-2387.md`, logs and `pointers.json` preserve machine `build-hp-1`, accepted source and original paths. The full JVM log is pointer-retained on this host, hash `cfdd3efa0905977d32f883e3f6a2f7f50b31f9088e81271699529120c6c96234`; verified against the pointer before parsing its complete XML. Parsed retained configured XML rather than inferring success from exit codes:

| Linux #2387 configured invocation | Executed | Passed | Failed | Errors | Skipped | Retained evidence |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| JVM/build, exit 0 | 5259 | 5259 | 0 | 0 | 0 | `$AGENTS_REPO_PATH/logs/verifier-gate_#2387_4.log`; `jvm-ngg9apnb/{dispatcher.xml,invocation.json}` |
| Original UI, exit 1 | 328 | 326 | 2 | 0 | 1 | Bundle `logs/verifier-gate__2387_5.log` embeds complete normalized XML; original `ui-oczy52g5` |
| Exact same-candidate UI rerun, exit 0 | 2 | 2 | 0 | 0 | 0 | Bundle `logs/2026-10-22T05-23-44-738Z_verifier-gate-rerun__2387.log`; original `ui-1b_ca8h4` |
| Scripted-all, exit 0 | 24 | 24 | 0 | 0 | 0 | Bundle `logs/verifier-gate__2387_6.log`; original `scripted-all-4a85al5g` |

The configured UI XML independently confirms `LegacyDiscardMigrationDeviceTest.preservationInvariant_productionUpgradeAndFreshReopen` executed/passed once without errors/skips. The accepted verifier additionally inspected raw `ui-oczy52g5/0-TEST-pixel2Api33Atd_0-_app-.xml` and `22-logcat-de.pyryco.mobile.data.cache.LegacyDiscardMigrationDeviceTest-preservationInvariant_productionUpgradeAndFreshReopen.txt` for nine exact observations. Those configured raw files are cited from that accepted review, not claimed independently re-read here. The retained focused native XML/phone log were independently re-read. Pre-verify, docs guard and all 211 script unit tests passed according to the accepted record.

### Findings, failures and holds

- #2386 discovery FAIL at `5a241432f05a351f64dc6916566c4f562165dafc`, central run `e001207a996f590db086e9f14fa66d6a248ef0db9c65f8daf646228067b4ccc4`, remains a failure: its configured UI contained zero required native cases despite a focused 1/1 pass. The accepted repair's discoverable device entry closes that finding; #2386 configured native 1/1 and #2387 configured native 1/1 remain separate runs.
- #2386 original UI had 328 executed, 327 passed, 1 failed and 1 skipped; its exact rerun passed 1/1. #2387 original failures are background-agent following (#2373) and model-change isolation (#2368); both trackers remain open. The unrelated `RenameDialogCaptureTest.renameAtFigmaViewport` skip is not a pass or storage acceptance obligation.
- Expected-red evidence remains: #2385 inert import bookkeeping 1 failed; #2386 fresh legacy-table guard 1 failed, plus fixture-development/optional-index failures; #2387 retired-API guard 1 failed. Their hashes and original revisions remain in child indexes/manifests. No historical failure is overwritten by current evidence.
- #2367's retained rejected/waiting candidates remain historical: 62 JVM regressions and removed mixed notification probes, permission/save native failures, then replacement-snapshot compilation failure. Accepted run `d482fc719f89537b595a02ab6b4700fa5a5d70a0624cb487f42f3db9df89392f` names the corresponding repairs and fresh configured checks. Its nonblocking unused-constant SHOULD FIX and orphan-comment NIT are not claimed repaired here; they belong to that runtime review, not a newly invented storage repair. No blocking storage finding remains in the latest accepted child records.
- #2447 is still open with `needs-human:manual-takeover`, owning #2334/#2437 full-file/timing fixture restoration and their approved authoritative mapping. Its absent replacement probes remain absent. This review does not clear that hold or certify those parents. #2175/#2246 activation/restart acceptance remains independent. Existing privacy, recording, approval and design-owner release conditions are unchanged.
- No current-provider live scenario is required by this storage contract. Native SQLite execution is required and has counted accepted evidence; replay is not substituted for it. Historical #2367 genuine recording/semantic-review evidence stays attributed there. No new capture, paired/live claim, second-OS request or wider diagnostic is made.

### Current builder verification

On Linux, `python3 scripts/gradle-gate.py testDebugUnitTest --tests de.pyryco.mobile.data.cache.LegacyDiscardMigrationTest --console=plain --warning-mode=none` exited 0 at plan commit `fefee7ebc`. Fresh `app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.data.cache.LegacyDiscardMigrationTest.xml` reports 3 executed/passed, 0 failed/errors/skipped, including every-shipped-schema, fresh creation and production-upgrade methods. Its system output contains 45 exact observations. This is Robolectric evidence, separate from the reused real native result.

Final builder static/build/format results will be recorded in the PR after execution. New parent complete configured acceptance remains pending with the dispatcher/verifier; the evidence-only builder handoff is not a feature PASS. Final integrated documentation remains pending with its owning stage.
