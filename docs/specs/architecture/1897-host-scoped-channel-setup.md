# #1897 — host-scoped empty-channel setup

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_createEditArchiveChannel_readsPromptBack` archives only the harness host, then incorrectly waits for global tier-tag absence; preserve subsequent functional assertions and `finally` cleanup.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ConversationTree` and `treeHost` draw every host in a shared lazy list with global tier tags and host-specific add controls.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `snapshots` and `HostConversationSnapshot.rowsLoaded` distinguish a loaded empty host from absent/startup data.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: connected-host rendering fixture and event assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/DiscussionRename.kt`: shared test-only Compose drives run on JVM and device.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: shared instrumentation process retains pairings; cleanup and counted XML are required.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`: tier tags span hosts; lazy visibility is not host membership.
- `docs/e2e-interactive-stream.md`, “Channel create, edit and archive”: preserve creation/default-folder, prompt read/edit/reset/clear, archive/restore and cleanup.

## Design source

N/A — test setup and regression only; no product UI changes.

## Change

Extract the empty-host Channels-plus drive into a shared test-only Compose helper. Wait for the exact target host's snapshot to have `rowsLoaded` and no channels, then scroll to and click its host-qualified plus. Replace only the existing global-zero-row wait/plus drive in the live method. Its earlier repository-empty wait stays; the new wait additionally observes the list's collected source. Missing or unloaded hosts must not establish emptiness. Keep deadlines unchanged and retain the scenario's existing assertions, archive restoration and created-conversation deletion. No production files or live selectors change.

The historical original stderr maps the timeout to this global wait in `interactiveTurn_createEditArchiveChannel_readsPromptBack` on tested feature `f3728e7752` / main `e4ecbb099d`; daemon revision was `6019328b378cad587f69b7bc94de37febbdf8556`. Original counted XML: 64 executed, 13 failed, 0 skipped; failed-only rerun: 13 executed, 1 failed, 0 skipped, this method passed. Original phone rows were not retained: another host's presence is a hypothesis for that occurrence, not an observed historical state. Controlled two-host rendering will establish the setup defect without claiming historical host attribution.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888, #1896 and #1898 touch the live class in other methods; keep this edit local.

Sizing: one setup/synchronization deliverable, approximately 350 written lines including evidence/plan/tests; no exported production types, one caller update, three acceptance criteria, no state-machine reject branches.

## Testing strategy

- Add `EmptyHostChannelSetupTest` under shared tests using real `ChannelListScreen`: target A loaded/empty, B retains a channel and is selected. The same drive used live must emit only `TreeHostChannelAddTapped(A)` while B's channel remains. Run first with the old global wait to retain counted red evidence, then repair and run green.
- Guard absent/unloaded A and held nonempty A; after a controlled snapshot update the drive proceeds exactly once. These cases prevent startup/cache or stale list data from being mistaken for empty channels.
- Run the shared regression and existing `ChannelListScreenTest`, lint, assembleDebug, Android-test compilation and forced Spotless. A focused device regression checks the actual lazy-list/Compose drive; it remains a shared test because no device-only behavior is needed.
- The dispatcher owns the fresh full live gate including the unchanged rung-3 method, as explicitly assigned by the ticket. Pending evidence must name mobile/daemon revisions, artifact path, executed/failed/skipped counts and the named method's pass.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “Channel create, edit and archive” and verification status, record the repaired host-scoped setup, controlled evidence and fresh dispatcher full live result. `docs/knowledge/features/development-verification-emulator-evidence.md`, “Emulator and real evidence”, record that tier-tag absence across a shared lazy list cannot prove one host's emptiness.

## Revisions

2026-10-10: controlled old-drive reproduction failed at the global tier-tag wait (1 executed/failed, 0 skipped); repaired JVM and device regressions each passed 4/4. Retain historical stack, source excerpt and counted historical/controlled/device XML under `app/src/androidTest/assets/channel-setup-1897/`. Actual written work is approximately 480 lines including these evidence copies, within every sizing boundary; the helper design and scenario coverage remain as planned. Original row ownership remains unobserved. Dispatcher full live evidence remains pending.
