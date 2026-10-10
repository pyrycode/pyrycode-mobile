# Answer-host setup independence (#1899)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairAnswerHost`, `awaitConnected`, `pairHostByCode` and the question-answer round trip.
- Historical `InteractiveStreamE2ETest` at `a7a4b484d7260c4dc7418b85df9c10f2b7245398`: the same setup order; only `awaitConnected` in `PairPhone` uses a coroutine deadline.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: shared Koin graph and saved-host state across methods.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `observe` follows the latest saved surviving host, not the host about to be paired.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt`: `persist` and `verify` already wait for the exact newly saved host.
- `docs/knowledge/features/paired-server-store.md`: save order defines compatibility selection; preserve preceding entries in test cleanup.
- `docs/knowledge/features/lifecycle-connection-driver.md`: foreground ownership is independent of compatibility selection.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: peer status is not phone evidence; post-test DESTROYED focus snapshots do not establish lifecycle during the failing wait.
- `docs/e2e-interactive-stream.md`: rung-3 contract and retained evidence boundaries.
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModelTest.kt`: exact-record verification and cancellation coverage.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: navigation supplies the optional pairing target through `SavedStateHandle`, with the empty default normalized to null.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt`: targeted navigation checks the host label, read-only field and Back without saving.

## Design source

N/A — test/harness setup repair; no visual changes.

## Context

Historical stderr records a coroutine timeout after 30000 ms in `PairPhone`, before peer opening. At the tested revision, the only synchronous coroutine timeout in that setup is the compatibility `awaitConnected` before code pairing. The helper wrongly gates pairing the answer host on a preceding selected host's readiness. Compose pairing waits have different exception types. The answer daemon's retained handshakes are timestamped but lack method/phone attribution, so they cannot establish this phone's pairing. The lost phone logcat means the underlying reason the preceding host was unavailable (selection, transport or lifecycle) remains unknown. This repair removes that unrelated prerequisite; it does not claim to repair its unknown connection failure. No decision record is needed.

## Design

Expose the existing androidTest `pairAnswerHost` internally with a defaulted pairing-code parameter, so its five live callers remain unchanged and a device regression can exercise the identical helper. Initially retain the compatibility wait for the red reproduction. Then remove only that wait. Keep list arrival, camera permission, all paste-code/fingerprint UI actions and the newly saved host's existing connection verification. No production code, deadline or question assertion changes.

Add `AnswerHostSetupTest` outside the e2e package. Reuse the live class's actual Compose rule and helper, seed a fixture-owned preceding host before activity launch, and override only compatibility connection state plus the target verification observer. Hold compatibility state Offline throughout. The real PairCodeViewModel and encrypted store must confirm/save/name the exact target, invoke its verification once and return to the list; preceding entries stay intact. Native permission grant and Android Keystore storage require a device rather than Robolectric. Restore Koin definitions and remove only owned ids after activity teardown, including failed setup.

Shared file overlaps #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888 and #1900 affect other methods/additive changes, not this helper. Build through them locally.

Forecast: approximately 280–350 written lines including evidence and plan, two exported test declarations, five unchanged live consumers, three criteria, no production state-machine branches.

## State and concurrency model

Compatibility flow remains Offline and never authorizes the target. Target verification uses the existing ViewModel job and its exact saved record. Test-only runBlocking waits retain existing bounded deadlines. The Compose rule destroys the activity before the outer fixture restores Koin/store state; no test job or pairing outlives the fixture.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Previously selected host is Offline when pairing starts | `AnswerHostSetupTest.offlinePrecedingHostDoesNotBlockAnswerHostPairing` fails at the historical wait before repair, passes after removal. |
| Confirm saves and verifies the new host | Same regression requires exact record/name, one connect and one target verification, and list navigation. |
| Setup failure or repeated method execution | Outer fixture cleanup restores definitions and removes both unique owned ids; original entries/order are asserted preserved on success. |
| Targeted pairing after fixture teardown in the same instrumentation process | `AnswerHostSetupCleanupTest.targetedPairingAfterFixtureTeardownKeepsItsHostGuard` runs the actual setup fixture and its Compose rule to completion, then starts a separate Compose activity with production navigation; checks target label, read-only name, wrong-host rejection and unchanged saved entries. |
| Empty-target add-host route after teardown | The same regression navigates again with an empty target, requires an editable name and accepts the valid code through fingerprint confirmation without saving. |

## Error handling

Keep all existing UI and exact-host verification failures/deadlines. A target verification failure cannot become success merely because another host is online. Do not retry, ignore assertions or increase timeouts. Retain sanitized historical stderr and device red/green summaries under androidTest assets; no raw phone frames, tokens, keys or pairing payloads.

## Testing strategy

Run the new focused device regression with the compatibility wait intact and inspect its counted XML/timeout. Remove the wait and rerun the whole one-method class, inspecting counted XML. Run existing `PairCodeViewModelTest` and `PairingVerificationTest`, lint, assembleDebug, androidTest compilation and forced Spotless. After final main merge, push before assembleDebug and `scripts/pre-verify.py --gradle`. Dispatcher owns the fresh full live gate and explicit pass/counts/artifact paths for the unchanged question-answer method and all five helper consumers; builder's controlled regression is distinct from that acceptance.

## Open Questions

Resolved: the historical tested helper matches today's wait order. The lower-level preceding-host unavailability cannot be attributed from retained artifacts, and is not required for the setup independence fix. Fresh full live acceptance remains pending dispatcher execution.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “What rung 3 is made of” and question-answer verification evidence; record removed preceding-host prerequisite, unchanged exact-target verification and answer assertions, builder red/green evidence separately from fresh full live counts/named pass/retained artifact locations. Update `docs/knowledge/features/development-verification-emulator-evidence.md`, named-wait guidance, with the limits of the historical phone attribution.

## Security review

**Verdict:** PASS

- [Trust boundaries] Real `PairCodeViewModel` still parses and confirms the code; test-only override controls target readiness, never live readiness. Removing unrelated compatibility observation does not bypass target verification.
- [Trust boundaries, rework] Cleanup must restore the production `SavedStateHandle` target lookup and empty-target normalization. The same-process navigation regression proves a different host's code is rejected before fingerprint confirmation, then proves add-host remains available for an empty target. This addresses verifier finding 1; no production binding changes.
- [Tokens/secrets] Fixture credentials are static non-secret test values; actual pairing remains in the existing Keystore-wrapped collection. Never retain codes, keys, credentials or records in diagnostics.
- [Files/storage] Evidence paths are fixed under androidTest assets. Cleanup removes only two unique fixture-owned ids, preserving preceding encrypted records/order; no new storage format or backup behavior.
- [Android surface] Only instrumentation code changes. The native CAMERA grant belongs to the existing helper; no exported component, deep link, provider or WebView change.
- [Cryptography] Existing fingerprint, parser and Keystore paths remain. No Noise, key generation, nonce or wire changes.
- [Network/I/O] Real live pairing keeps existing deadlines, TLS/Noise and bounded exact-target verification. Controlled regression uses a no-op test controller and no real daemon/Claude calls.
- [Errors/logs] Sanitized historical evidence retains only fixed operation/error/revision/count metadata and explicitly distinguishes peer from phone. Post-teardown focus snapshots cannot identify the failing phone lifecycle.
- [Concurrency] Restore Koin only after the fixture activity closes; remove owned entries on every exit. No production scope, cancellation or hot-flow ownership changes.
- [Threat model] A relay delaying a preceding host cannot block independent pairing. Hostile frame decoding, rooted-device theft and UI-side token leakage remain enforced by existing production boundaries, unchanged here.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-10

## Revisions

- 2026-10-10: controlled device regression at the pre-repair helper (`54fd1bd52`) executed once and failed with the historical 30000 ms coroutine timeout, before target verification. Remove only the compatibility wait. The native fixture controls compatibility state and the PairCodeViewModel network-controller/readiness seams; it keeps the actual parser, fingerprint confirm, encrypted save, display-name write and exact-target verification path. Cleanup and unchanged answer assertions remain mandatory.
- 2026-10-10: verifier finding 1 identified persistent DI contamination: cleanup read explicit Koin parameters rather than navigation's `SavedStateHandle`. Restore the equivalent production binding, including the empty-target normalization. Add a device regression using two sequential JUnit rule evaluations: first the existing fixture and real setup helper, fully torn down; then an independent activity using `PyryNavHost` in the same process. No intervening Koin replacement is permitted. Exercise targeted and empty-target routes, reject a valid different-host code, and preserve all saved entries. Run it red before the binding repair, then green with both answer-host device classes and existing pairing unit tests. Device-only because the actual fixture uses native permissions and encrypted Keystore storage. Rework adds approximately 110 lines; total remains below 450 lines, with three exported test declarations, no new production types or changed live consumers. Full live acceptance remains dispatcher-owned.
