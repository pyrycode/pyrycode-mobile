# #1394 — rung-3 QR pairing waits for the host before opening the channel list

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` and `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` (two-host setup, CAMERA grant, `finally` removal), `pairHostByCode` (the list's pair-another-host control, `PAIR_TIMEOUT_MS` wait), `assertEachUnderOwnHost`, `hostLabel`, `heldConversationName`. The only file this ticket edits.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: the `Routes.SCANNER` composable. Its `LaunchedEffect` on `Decoded` parses the payload and sends `PairingPrepared`; on `Paired` it navigates to the channel list.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt`: `onEvent` accepts `QrDecoded` from `ReadyToScan`; `ConfirmPairing` from `AwaitingConfirm` moves to `Verifying`, saves the record, then waits for the host.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the `ScannerViewModel` `viewModel { }` definition, built from the store, `RelayConnectionRegistry` and `registry::pairingStatus`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt`: `PairedServerCollectionStore.setDisplayName`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt`: `scannerPasteReturnsFromEveryRecoveryStateWithoutSaving` fetches the VM through its own `NavHostController`.
- `docs/specs/architecture/1386-scanner-pairing-verification.md`, `docs/specs/architecture/1085-live-second-host-rename-unpair.md`.

## Design source

N/A, test-only; no visual change (the ticket's `## Figma` says so). The verifier's visual check is skipped on purpose.

## Context

#1386 made the scanner report a pairing done only after the host answers. JVM and Robolectric tests cover it. This adds the rung-3 scenario, in the #481 / #482 shape, that drives the scanner's confirm → wait → channel-list path over the real Noise/relay path. It is zero real-claude turns and needs the second live host that only rung 3 provisions, so there is no rung-4 twin (the ticket gives the reason).

## Change

One new `@Test`, `interactiveTurn_scannerConfirm_waitsForHostThenOpensList`, plus a small helper `pairHostByScanner(payload)` beside `pairHostByCode`.

**Reaching the scanner VM.** The ticket suggests `ViewModelProvider(nav.getBackStackEntry(Routes.SCANNER))`, but that works only in `PairCodeScreenTest`, which owns its `NavHostController`. `MainActivity` creates its controller inside `setContent`, and Compose's `NavHost` does not tag the view with it, so the e2e class has no handle. The ticket forbids production changes. So the helper overrides the Koin `ScannerViewModel` definition for the scenario with one that builds the VM exactly as `AppModule` does and records the instance. In `finally` it loads the same definition without the capture. Both are one function, `scannerViewModelModule(onCreated)`, a mirror of the `AppModule` definition, in the same way `E2eTestApplication.tappedRelayRepositoryModule` already mirrors the repository binding. The VM is still created by the route's `koinViewModel` and lives in the route's back-stack entry, so its scope, the `Paired` navigation and the pop are the production ones.

**Helper flow.** Grant CAMERA, tap the list's pair-another-host control, wait for the captured VM to report `ReadyToScan` (the route resolves it without the dialog once CAMERA is granted), send `ScannerEvent.QrDecoded(payload)` on the main thread, wait for "Confirm pairing", tap it, then wait up to `PAIR_TIMEOUT_MS` for the channel-list tag. The test sends nothing else; the route's own effect sends `PairingPrepared`.

**Scenario.** Host A on the list and connected; pair host B with `twoHostArg(ARG_PAIR_CODE_B)` through the helper. Assert host B is saved in the store and the scanner route is gone, then wait for `ARG_COLLISION_NAME_B`'s row. A scanner pairing saves no display name, so both hosts would label as "Unnamed host" and "under B's label" would be unreadable. The test therefore names B with `setDisplayName(serverIdB, HOST_B_NAME)` (the store call the Edit host modal makes), then runs `assertEachUnderOwnHost(labelB to nameB, labelA to nameA)` and its converse, where `nameA` is whatever A holds now for the seeded id (#847 renames it). Host B is removed in `finally`, and the Koin definition is restored there too.

Overlapping in-flight branches #1329, #1332, #1337, #1342, #1346, #1410, #1412 also add scenarios to this file; the edit is additive.

## Testing strategy

The scenario is the test. It is device-only by nature: it needs a real host daemon, relay and a second live host. It compiles under `./gradlew compileDebugAndroidTestKotlin`. It runs only in the live suite (`python3 scripts/android-test-gate.py live`), which the dispatcher runs after the verifier; the builder does not run the live suite. The PR's `## Live tests` lists the new method; no existing method or shared helper changes.

## Documentation handoff

- `docs/e2e-interactive-stream.md`, the scenario coverage list: add `interactiveTurn_scannerConfirm_waitsForHostThenOpensList` (#1394). Pending for the documentation stage.
