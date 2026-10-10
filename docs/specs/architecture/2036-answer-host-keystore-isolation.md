# Answer-host fixture and pairing-key isolation (#2036)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/AnswerHostSetupTest.kt`: `fixture` saves through the app store, restores `ConnectionStateSource` and the production `SavedStateHandle`-based `PairCodeViewModel` binding, then removes its two random IDs.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/AnswerHostSetupCleanupTest.kt`: `targetedPairingAfterFixtureTeardownKeepsItsHostGuard` retains two sequential activity lifetimes without replacing Koin between them.
- `app/src/androidTest/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStoreTest.kt`: `tearDown`, the two lost-key methods and `writePlaintext` use the production wrap alias despite private test DataStores.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt`: `readSnapshot` distinguishes failure, `mutate` strictly authenticates the retained blob, and key lookup currently uses a fixed alias.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `appModule` supplies the production store and target lookup that fixture restoration must preserve.
- `docs/knowledge/features/paired-server-store.md`: encrypted empty collections remain persisted after removal; graceful `list()` emptiness does not prove storage readability.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: fixture success alone does not establish process-global graph restoration; second activity lifetime is essential.
- `docs/knowledge/features/development-verification-test-scheduling.md`: use the existing host-wide device hold, including its FIFO wait.
- `../pyrycode/docs/protocol-mobile.md`: Security model remains authoritative; no wire change.

## Context

A sweep reports read, save and then removal failures classified as Keystore. A teardown exception can mask the original save failure. The investigation lead is a test deleting the production pairing key while the app retains its encrypted collection, including an encrypted empty collection. Fresh isolated, class and two-shard evidence must establish which operation fails first before repairing it. This does not warrant a new decision record.

Sizing: one fixture/storage isolation deliverable, three acceptance criteria, approximately 650 written lines including selected sanitized evidence, zero new exported types and no consumer migration. Preserve the existing public constructor. CodeGraph reports eight caller edges; only the storage test's four construction sites need the internal test seam. Remote feature branch overlap check found none in the four intended files.

## Design

Preserve the public `KeystorePairedServerStore(dataStore, ioDispatcher)` constructor and its fixed production alias. Add an internal constructor taking the wrap alias to let real-Keystore tests own both their private DataStore and a unique test-only alias. All store instances, fault wrappers, legacy writers and lost-key tests in `KeystorePairedServerStoreTest` use that same per-test alias. Delete only that pairing alias in teardown. Do not change encryption, strict decoding, read failure classification, atomic mutation, credentials or public storage contracts.

Use strict `readSnapshot().getOrThrow()` assertions in answer-host setup and restoration rather than accepting fallback-empty reads. Fixture cleanup removes only its random IDs that were actually persisted, preserves preceding records/names/order, and restores the existing production-equivalent Koin bindings on success and failure. Preserve the original failure if cleanup also fails by retaining the cleanup failure as suppressed; failures are never discarded.

Add a causal device regression that retains named, ordered entries in the real app store, drives the storage test's real lost-key operation and teardown against its private DataStore, then verifies app storage readability and answer-host cleanup. Existing target-guard coverage remains unchanged in meaning. Add failed-setup restoration coverage after fixture persistence and after Koin installation; verify the original source and target guard through the restored graph.

No new UI, dependency, exported type, ViewModel or navigation contract.

## State and concurrency model

No new production state, jobs or flows. DataStore mutations remain serialized inside `edit` on the injected IO dispatcher. A test owns its DataStore scope and cancels/joins it before deleting its private file and key. Device evidence runs sequentially under the existing host-wide hold. The fixture rule restores bindings after the inner activity rule closes and cancels the pairing ViewModel.

## State transitions and identity reuse

| Event | Contract and device test |
| --- | --- |
| Storage test loses its wrap key and tears down | Private test key loss cannot invalidate app blobs; `storageTestKeyLossAndTeardownPreserveAppPairings` exercises the actual lost-key test and teardown. |
| Successful answer-host pairing then fixture exit | Only fixture IDs disappear; existing named records and order survive; `offlinePrecedingHostDoesNotBlockAnswerHostPairing` plus `targetedPairingAfterFixtureTeardownKeepsItsHostGuard`. |
| Setup/body fails after fixture save and DI installation | Original failure propagates, owned pairing is removed and production definitions restored; `failedFixtureRestoresBindingsAndPreservesAppPairings`. |
| Second activity in the same process | No intervening Koin replacement; read-only target name, wrong-host rejection and editable empty target; existing cleanup regression. |
| Rejection or confirmation cancellation | Strict snapshot unchanged; existing cleanup regression. |
| Reopened storage with the same private alias | Existing storage class tests cover recency, names, corruption, strict mutations and credential redaction. |

## Error handling

Production outcomes do not change. Lost-key mutations still throw the redacted `PairedServerStoreException`; snapshot reads still return failed `Result`. Fixture assertions use a successful snapshot, never graceful fallback. Teardown must not overwrite the primary failure, hide a cleanup failure or erase app preferences. If evidence disproves the key-deletion lead, revise this design before implementation.

## Testing strategy

These tests are device-only because they require actual Android Keystore custody and persisted app DataStore state, plus two real activity lifetimes. Before implementation, run the cleanup method, both complete answer-host classes and the same two-shard non-e2e selection as the retained #2027 baseline on `pixel2Api33Atd` (Android 13/API 33 AOSP ATD). Then add and run the causal regression red, implement isolation, rerun it green, rerun cleanup in isolation and all affected classes, and repeat the in-depth selection. Confirm both named acceptance methods executed and passed; record misses/skips and unrelated failures independently.

Retain commands, tested revision/dirty diff identity, exit status, counts, sanitized XML and selected content-free logcat under `app/src/androidTest/assets/answer-host-2036/`. Full sweep XML may be compressed after removing logs and credential-bearing error details; selected XML and counts stay readable. Do not commit raw logcat, blobs or credential text. Normal configured UI/scripted gates remain dispatcher-owned; UI evidence must confirm both acceptance methods passed with counts. No real-Claude scenario is required for this nonvisual harness repair.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck; after the last merge of main, push then run assembleDebug and `scripts/pre-verify.py --gradle` with the PR body.

## Open Questions

- Which fresh sweep operation first invalidates the app pairing blob? Resolve with ordered XML/logcat and causal regression before repair.
- Does failed fixture setup leave original saved entries and graph intact? Resolve with strict snapshot and failed-body regression.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/development-verification-emulator-evidence.md`, answer-host fixture-restoration discussion: established cause, fixture/storage ownership boundary, counted before/after evidence and dispatcher gate evidence.
- `docs/e2e-interactive-stream.md`, “Answer-host setup independence (#1899)”: same cause and counted evidence, retaining the two-activity DI-restoration distinction.
- `docs/knowledge/features/paired-server-store.md`, custody/constructor contract: production alias remains fixed; internal test construction isolates aliases without weakening strict mutation or redaction contracts.

## Security review

**Verdict:** PASS

- [Trust boundaries] The internal constructor accepts only fixture-generated aliases. Public production construction retains the fixed alias; QR/daemon fields never become alias or path components.
- [Tokens, secrets and credentials] Real AES-256-GCM and non-exportable Android Keystore keys remain mandatory. Tests use synthetic credentials; durable evidence strips input, blob, token, name and provider/parser details.
- [Files and storage] App-private DataStore retains atomic `edit` semantics. Fixture deletion is limited to its own IDs and private test file/key; no app preferences reset, backup or file-location change.
- [Android attack surface] No components, intent filters, PendingIntents, permissions, WebViews or exported surfaces change.
- [Cryptography] Only key ownership changes for tests. Production AES parameters and provider-generated fresh IVs remain; lost-key authentication failure is still exercised on real storage.
- [Network and I/O] No network/parser/TLS change. Existing controlled connection and verification fakes avoid live daemon dependencies.
- [Errors, logs and telemetry] SHOULD FIX: use strict snapshots in all fixture preservation assertions and retain the primary error if cleanup fails; selected evidence may contain static event/classification codes only.
- [Concurrency] Private alias and private DataStore share one fixture lifetime; cancel/join before deletion. No second production instance or new coroutine owner. Koin restoration follows activity closure.
- [Threat model] Relay/hostile frame handling and UI token-entry behavior are unchanged per protocol Security model. Token theft from disk remains mitigated by Keystore wrapping. The confused-test threat is addressed by removing production-key deletion from the pairing-store test.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

- 2026-10-10: Fresh baseline at `75a10c4a4` reproduced exactly 1573 executed, 1572 passed, 1 failed, 2 skipped, 0 errors, exit 1. The named setup method passed; cleanup failed. Its selected logcat orders failed read, failed save, then failed remove. The causal `storageTestKeyLossAndTeardownPreserveAppPairings` probe (test-only patch on that revision) executed once and failed after the real lost-key method and teardown, with a failed app snapshot and suppressed failed removal. This establishes production pairing-key deletion by the private-DataStore test as the cause; encrypted app blobs, including empty collections, outlive that deletion. The internal alias seam and per-test ownership in the Design stand unchanged.
- 2026-10-10: The failed-save probe executed once and failed because cleanup replaced the original save error with an unowned removal error. The fixture now tracks successful seed persistence, strictly reads the remaining owned IDs before removal, and preserves the primary exception with any cleanup exception suppressed. A failed initial snapshot changes no bindings or storage; a failed save restores bindings without removing unowned IDs. The second-activity check now uses a retained named host instead of an unsaved ID, strengthening stored-name/read-only coverage. The failed-body variant retains the two sequential activity lifetimes.
