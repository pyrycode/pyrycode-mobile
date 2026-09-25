# #1089 — rung 3: a workspace label set from another client reaches every open surface, per host

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` (#1086) — the nearest shape: pair B by code, `pickNewDefaultFolder` for A's default from A's Settings, `createChatOn(serverIdA)`, and the `finally` that writes both stored defaults back and removes B.
  - `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` (#1087) — `awaitWorkspaceRow`, `workspaceEditDescription` (the pencil's description carries the row's shown name, folder or label), and the `runCatching` + content-free `Log.w` cleanup shape.
  - `runningToolPeer`, `peerStep` — the #849 peer on host A (`ARG_PEER_TOKEN`) and the timeout wrapper that names the step and link state.
  - Helpers reused as they are: `awaitChannelList`, `awaitConnected`, `pairHostByCode`, `openSettings`, `showHostSettings`, `heldConversation`, `hostRepository`, `scrollListTo`, `leaveThread`, `twoHostArg`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `exchange`, `history` (a resendable request whose reply is a typed frame, checked by type and failing with its `code`) — the shape the new `renameWorkspace` follows.
- `app/src/main/java/de/pyryco/mobile/data/network/RenameWorkspacePayloadDto.kt` → `RenameWorkspacePayloadDto` — the peer encodes the request with the app's own DTO; `null` label clears.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt` → `WorkspaceChip` — the chip's text is the hardcoded `"Workspace: <name> (change)"`; `ThreadScreen` draws it only for an unpromoted thread with no messages.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `defaultWorkspaceLabel` — the Settings row names a label only when some conversation of that host has `cwd ==` the stored default, and never for the scratch sentinel. So A's default must be a real folder with a conversation in it.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `workspaceDisplayName` — the fallback is the folder's last path element.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createDiscussion(workspace)`, `renameWorkspace(path, label)`, `delete` — B's conversation at the shared path, and cleanup.
- `../pyrycode/docs/protocol-mobile.md` § Renaming a workspace and the `workspace_updated` row — the requester's conn gets the correlated reply and no push; every other interactive conn gets the push. This is why the label is set from the peer.
- `scripts/e2e-emulator.sh` (LIVE `TEST_TARGET`) and `scripts/android-test-gate.py` (`LIVE_MINIMUM`) — where the method registers.

In-flight overlap: #1076 appends a live method to the same test class and both scripts. Not a dependency; my edits are appends, so a later merge may touch those lines.

## Design source

N/A — test and script only; no UI changes.

## Context

#720–#723 decode, apply and show a workspace label, proven only against fakes. This adds one live rung-3 scenario, split from #676, with hosts A and B paired. No ADR needed.

## Design

### Peer addition (`SecondClientPeer`)

```kotlin
internal suspend fun renameWorkspace(path: String, label: String?, timeoutMs: Long)
```

Sends `rename_workspace` with `RenameWorkspacePayloadDto` through `exchange(..., resend = true)` — setting a label is idempotent, so a link that ends before the reply may resend it. Checks the reply is `workspace_updated`, else fails naming only the error `code`. Neither the path nor the label is logged or put in a message.

### Scenario: `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`

Run-unique tokens from one timestamp: folder `e2e1089-<ms>`, labels `e2e1089-chip-<ms>`, `e2e1089-tree-<ms>`, `e2e1089-settings-<ms>`. A distinct label per surface means each wait can only be satisfied by that round's push.

1. **Setup.** `awaitChannelList`, `awaitConnected`, record A's stored default; grant CAMERA and `pairHostByCode(ARG_PAIR_CODE_B)`; record B's stored default.
2. **A's default is a real folder.** `openSettings` → `showHostSettings(A)` → `pickNewDefaultFolder(A, folder)` returns `path`. Back to the list.
3. **B's conversation at the same path.** `hostRepository(B).createDiscussion(path)`; assert its cwd equals `path` (both test daemons share `HOME`, so the folder exists on both).
4. **Open the peer** on host A.
5. **Thread chip.** `createChatOn(A)` opens A's new, empty discussion; assert its cwd is `path`. Chip reads `Workspace: <folder> (change)`. Peer sets the chip label → wait for the chip to read the label, with the thread still open. Assert the daemon-side labels (below). Peer clears → chip reads the folder again. Leave the thread.
6. **Tree row.** `awaitWorkspaceRow(folder)`. Peer sets the tree label → `awaitWorkspaceRow(label)`, and the tree still scrolls to a folder-named pencil: that one is B's, since A's now shows the label. Peer clears → the labelled pencil can no longer be scrolled to and the folder pencil is there.
7. **Settings row.** `openSettings` → `showHostSettings(A)`: the Default workspace row names the folder. Peer sets the settings label → the row names the label. Peer clears → the row names the folder. Back to the list.
8. **Nothing else moved.** A's stored default is still `path`, B's is still its original, and A's discussion's cwd is still `path`.

Per-host check after each set (`assertLabels`): A's discussion's `workspaceLabel` in A's live repository equals the label, and B's conversation's `workspaceLabel` in B's live repository is null. After each clear, both are null.

`finally`, each step in its own `runCatching` with a content-free `Log.w`: clear the label through A's repository (a clear of an unlabelled path is harmless); delete A's discussion and B's conversation; write both stored defaults back; remove B; close the peer. The label clear runs before the deletes, since `rename_workspace` refuses a path no conversation holds.

New constants: `LABEL_E2E_PREFIX = "e2e1089-"` and the chip's hardcoded text shape as a helper `workspaceChipText(name)`.

### Harness

- `scripts/e2e-emulator.sh`: append the method to the LIVE `TEST_TARGET` list with a one-line comment (no turn cost).
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 1` with its comment line.

## State + concurrency model

Test-only. The peer call runs through `peerStep` (`runBlocking` on the instrumentation thread), as the neighbouring scenarios do. The peer's recorder scope is unchanged.

## Error handling

Every wait is a bounded `waitUntil` or `withTimeout`; a peer timeout names the step and link state. A refused rename fails with its `code`. Cleanup failures are logged by exception class and swallowed.

## Testing strategy

The scenario is the test. Builder proof: `./gradlew compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, `spotlessApply`, and `python3 -m unittest scripts/test_android_test_gate.py` (it ties `LIVE_MINIMUM` to the curated list). The live run needs the operator's relay and both test daemons; the dispatcher runs `python3 scripts/android-test-gate.py live` after verifier for `needs-real-claude`. Zero real-claude turns, so no rung-4 twin: the scripted harness has no second-client seam (#848's finding) and the ticket asks for none.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: add the scenario to the LIVE inventory in § Live mode (rung 3, live relay) and § Pre-ship gate.

## Open questions

- Whether the tree composes A's and B's workspace rows for the same folder as two rows. Expected yes (the tree is per host). The tree wait needs only that a folder-named pencil remains once A's row shows the label.
