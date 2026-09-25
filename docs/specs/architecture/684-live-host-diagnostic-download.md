# 684 — Prove host diagnostic downloads end to end (rung 3)

One live scenario on `InteractiveStreamE2ETest` that saves host A's diagnostic archive through
Settings → Log data with host B paired beside it, and proves the saved document is A's, complete,
and unaffected by a selection change or a cancelled picker. Test and script only; no production code.

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` — the #1085 host-B setup this
    copies: `pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))`, CAMERA granted first, B removed in `finally`.
  - `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `assertOpensAndSaves`,
    `insertDownload`, `deleteFixtures`, `readUri` — #1016's `ActivityIntentStub` answering
    `ACTION_CREATE_DOCUMENT` with a `MediaStore` download this app owns.
  - `interactiveTurn_muteChannel_roundTripsThroughTheHost`, `hostRepository` — creating a conversation on a
    host and calling `setMuted` through that host's own repository.
  - `awaitChannelList`, `awaitConnected`, `hostLabel`, `string`, `CD_OPEN_SETTINGS`, `OK_BUTTON` — drive helpers.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/ActivityIntentStub.kt` → `ActivityIntentStub` — answers a
  started action and records the intent.
- `app/src/main/java/de/pyryco/mobile/ui/settings/DebugBundleDownload.kt` → `DebugBundleDownloadController`
  (`requestArchive`, `onDestination`: a null destination is `PICKER_CANCELLED` and keeps the archive held),
  `DebugBundleModal` (OK means request / save / dismiss by state), `DEBUG_BUNDLE_FILE_NAME`,
  `DEBUG_BUNDLE_MEDIA_TYPE`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.SETTINGS` composable's `archiveLauncher`
  (`ActivityResultContracts.CreateDocument`) and the list's `onOpenSettings` (`Routes.settings(selectedServerId())`).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `openLogData` (names the owner row's
  `SettingsHostRow.name`), and `SettingsScreen.kt`'s Storage section Log data row.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `reconcile` (selection = last saved
  record; an unchanged record keeps its bundle), `requestDebugBundle` (exact-host routing), `selected`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServerCollectionStore.save`
  ("makes it the latest saved record"), `loadById`, `remove`.
- `app/src/main/java/de/pyryco/mobile/data/repository/DebugBundleTransfer.kt` → `DebugBundleTransfer.state`,
  `takeArchive`, `DebugBundleArchive.writeTo`; `RemoteConversationRepository.requestDebugBundle` allows one
  transfer per connection.
- `../pyrycode/internal/debugbundle/bundle.go` → `Assemble`, `Manifest` — member order manifest, logs,
  optional recording; `log_bytes` is the joined log text's length; `recording_bytes` 0 when absent.
- `../pyrycode/cmd/pyry/main.go` → `debugBundler` over `logRing.Snapshot()` of a 200-line ring;
  `fakeDebugBundler` is inert unless `PYRY_FAKE_DEBUG_BUNDLE_*` is set, which the LIVE path does not set.
- `../pyrycode/internal/relay/handlers/set_conversation_muted.go` → `SetConversationMuted` logs
  `conversation_id` at info on success only.
- `scripts/e2e-emulator.sh` (the `LIVE` `TEST_TARGET` list), `scripts/android-test-gate.py` (`LIVE_MINIMUM`).
- `docs/knowledge/features/settings-screen.md`, `docs/e2e-interactive-stream.md` — read only.

In flight: #1086 appends to the same three files (its own LIVE method, `LIVE_MINIMUM += 1`, a new scenario).
Not a dependency; my edits are appends, so a later merge only re-orders two adjacent list lines.

## Design source

**Figma:** N/A — test-only ticket; the Log data modal's visuals are #683's.

## Context

#682 (transfer) and #683 (Settings entry, modal, save) are proven against fakes only. This adds the live
proof the parity candidate #680 runs. No ADR.

## Design

One `@Test`: `interactiveTurn_logData_savesTheOwningHostsArchive` (zero claude turns), plus private helpers
and constants. Drive:

1. **Setup.** `awaitChannelList`, `awaitConnected`, grant CAMERA, `pairHostByCode(ARG_PAIR_CODE_B)`. B is now
   the selection. Install an `ActivityIntentStub` monitor.
2. **Select A, open A's Settings.** Re-save A's unchanged record (`store.save(loadById(A).record)`), wait until
   `registry.selected.value === registry.connectionFor(A)`, and assert A's bundle is the same instance (no
   reconnect). Tap the gear; scroll to and tap the Log data row; wait for the modal's scope sentence formatted
   with A's resolved name (`displayName` else server id, as `SettingsHostRow.name`).
3. **Mint A's marker, request.** On A's own repository: `createDiscussion()`, then `setMuted(id, true)`. The
   daemon-minted id is the marker: A's daemon logs it (`set_conversation_muted applied`) and B never saw it.
   Minted after the modal is open and immediately before OK, so the 200-line ring holds it. Tap OK.
4. **Move selection to B mid-transfer.** Right after the request tap, re-save B's record and wait for
   `selected === connectionFor(B)`. Then wait for the ready sentence (`log_data_ready` prefix), failing fast
   with the failure's name if any transfer failure sentence shows.
5. **Cancelled picker.** Stub `ACTION_CREATE_DOCUMENT` → `RESULT_CANCELED`. Count this app's `MediaStore`
   downloads, tap OK, wait for `log_data_failed_cancelled`. Assert: one picker intent, suggesting
   `DEBUG_BUNDLE_FILE_NAME` / `DEBUG_BUNDLE_MEDIA_TYPE`; no "Saved to" line; download count unchanged.
6. **Save.** `insertDownload` an empty target; stub → `RESULT_OK` with it. Tap OK in the same modal; wait for the
   saved sentence or the write-failed sentence; assert saved.
7. **Check A's document.** `readUri(target)` → `assertCompleteBundle(bytes)` → assert `logs.txt` contains the marker.
8. **Check B's archive.** `registry.requestDebugBundle(B)`, await `COMPLETE` (fail on any other terminal status,
   naming the status), `takeArchive().writeTo(ByteArrayOutputStream)`, `assertCompleteBundle`, assert the marker
   is absent from its `logs.txt`.
9. **`finally`.** Remove the monitor, `deleteFixtures(inserted)`, delete the marker discussion on A
   (`runCatching`, content-free warn on failure), `store.remove(B)`.

Helper contracts:

- `readBundle(archive: ByteArray): Map<String, ByteArray>` — `GZIPInputStream` read to EOF (a bad CRC or
  truncation throws), then a ustar walk over 512-byte blocks: name (bytes 0–99), octal size (124–135), typeflag;
  stops at the zero end-of-archive block and fails if the data ends before it or a body overruns. Duplicate
  member names fail.
- `assertCompleteBundle(archive): String` — members ⊆ {manifest.json, logs.txt, recording.cast}, manifest and
  logs present; recording present ⇔ `recording_present`; `logs.txt` size == `log_bytes`; recording size ==
  `recording_bytes` (and `recording_bytes` == 0 when absent). Returns `logs.txt` as UTF-8 for the marker check.
- `awaitLogData(vararg ids)` — wait until any of the given modal sentences is drawn (prefix before `%1$s` for
  the formatted ones), return which.

**Sensitivity.** Archive bytes live only in local variables; no assertion message, log line or failure text
interpolates archive content, the manifest's `recording_name`, or `logs.txt`. Messages name facts ("A's
archive lacks A's marker"), sizes and status names only.

## State + concurrency model

Test thread drives Compose via the rule; store/registry/repository calls use `runBlocking` +
`withTimeout`, as neighbouring scenarios do. B's transfer is collected with `state.first { terminal }` under
a timeout. No new scopes.

## Error handling

A missing `ARG_PAIR_CODE_B`/`ARG_SERVER_ID_B` fails through `twoHostArg` (reported blocker, never a pass).
Transfer failures fail the test with the status or failure enum name. Known live risk, not handled here: an
operator recording big enough to push the archive past the daemon's ~25 MB push ceiling or the phone's
`MAX_ARCHIVE_BYTES` ends the transfer; the failure then names `DISCONNECTED`/`INVALID_STREAM`.

## Testing strategy

This ticket is itself the rung-3 scenario. Local proof: `compileDebugAndroidTestKotlin`, `assembleDebug`,
`lint`. The live run is the dispatcher's `python3 scripts/android-test-gate.py live` after verifier
(`needs-real-claude`); no rung-4 twin (the daemon's archive is real-bundler output, which scripted mode
fakes). Registration: append the method to the LIVE `TEST_TARGET` list and `LIVE_MINIMUM += 1`.

## Documentation handoff

Pending for the documentation stage, after the live gate passes:
- `docs/e2e-interactive-stream.md` § Live mode (rung 3, live relay) and § Pre-ship gate: add the scenario to the
  LIVE inventory; record the mobile and daemon revisions, emulator image and outcome as the gate printed them.
- `docs/knowledge/features/settings-screen.md`, Log data entry: name this scenario as the live proof.

## Open questions

- Whether `internal` `DEBUG_BUNDLE_FILE_NAME` / `DEBUG_BUNDLE_MEDIA_TYPE` are visible to androidTest. If not,
  compare against the `EXTRA_TITLE` / type literals with a comment naming the constants.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — daemon bytes enter the test only through the app's own save path (the
  picked `MediaStore` document) and `DebugBundleTransfer`; the test parses them with a bounded tar walk
  (`readBundle` fails on overrun or missing end block) and never interprets a member name as a path.
- [Tokens] No findings — the test re-saves stored `PairedServer` records unchanged through the store; it never
  reads, logs or asserts on token or key fields (`PairedServer.toString` redacts anyway).
- [File / storage] SHOULD FIX (implemented in Phase B) — the saved document may hold the operator's real
  recording (A and B run under the real `HOME`). The target is an app-owned `MediaStore` download recorded in
  `inserted` before the picker returns, and `deleteFixtures` in `finally` removes it on every path; the cancelled
  picker creates nothing (asserted by count). Nothing is copied off device: no `adb pull`, no test artifact.
- [Android surface] No findings — no new intent filters; `ActivityIntentStub` is a test-process monitor.
- [Crypto] No findings — none used beyond the app's own transport.
- [Network] No findings — only the script's test daemons and the live relay the harness already uses; B's
  direct request goes through `RelayConnectionRegistry.requestDebugBundle`, the same exact-host path.
- [Logs / messages] MUST-hold rule, satisfied by design — no assertion message, `Log` call or thrown message
  contains archive bytes, `logs.txt` text, the manifest or its `recording_name`; marker checks are boolean
  `contains` with fixed messages. Instrumentation failure output is therefore content-free.
- [Concurrency] No findings — B's transfer is awaited with a timeout; the modal's transfer is bound to the
  Settings view model and dies with the activity.
- [Threat model] OUT OF SCOPE — the daemon's uncapped `recording.cast` (bundle past the push ceiling) belongs
  to pyrycode's `debugbundle.Assemble`, as its own comment on `pushQueueByteCeiling` says.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25
