# #1087 — rung 3: add, rename and archive a workspace from the list

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (#562) — the Add workspace drive through `openWorkspacePicker` (long-press on host A's add control, create folder, OK starts the chat) that this scenario reuses.
  - `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` (#1086) — the nearest shape: `createChatOn`-style id capture, `renameOpenThread`, `heldConversation(...).cwd`, `archivedIds`, restore through `openSettings` + `showHostSettings` + the "Restored" snackbar wait, and a `finally` that puts shared state back.
  - Helpers reused as they are: `awaitChannelList`, `awaitConnected`, `hostConversationIds`, `newHostConversationId`, `heldConversation`, `archivedIds`, `scrollListTo`, `renameOpenThread`, `leaveThread`, `hostRepository`, `string`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeWorkspaceRow` — the pencil's content description is `cd_tree_workspace_edit` with the row's shown name, so it identifies the workspace row by folder name or label.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditWorkspaceModal.kt` → `EditWorkspaceModal`, `EDIT_WORKSPACE_NAME_FIELD_TAG` — the name field, the Archive action and the in-place confirmation on the shell's OK.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `renameWorkspace(path, label)` (a `null` label clears it), `delete(conversationId)` — the cleanup calls.
- `docs/specs/architecture/905-edit-and-archive-workspace.md` — the label rule (a blank name or the folder's own name clears the label, so the scenario's label differs from the folder name) and the modal's strings.
- `scripts/e2e-emulator.sh` (the `LIVE` `TEST_TARGET` list) and `scripts/android-test-gate.py` (`LIVE_MINIMUM`) — where the method registers.

In-flight overlap: #1076 appends a live method to the same test class and both scripts, and #1071 edits `android-test-gate.py` elsewhere. Neither is a dependency; my edits are appends, so a later merge may touch those lines.

## Design source

N/A — test and script only; no UI changes.

## Context

#904 (`AddWorkspaceModal`) and #905 (`EditWorkspaceModal`) are proven only against fakes. This adds one live rung-3 scenario on host A, split from #676. No ADR needed.

## Design

One new `@Test` in `InteractiveStreamE2ETest`: `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost`. Zero real-claude turns: folder creation, chat start, conversation rename, workspace rename, archive and restore are daemon round trips.

Run-unique tokens from one timestamp: folder `e2e1087-<stamp>`, label `e2e1087-label-<stamp>`, chat name `e2e1087-chat-<stamp>`. The label differs from the folder name so the #905 rule does not clear it.

Steps:

1. `awaitChannelList`, `awaitConnected`; record host A's conversation ids.
2. **AC-1 add.** `openWorkspacePicker` (long-press on host A's row) → create folder → OK once enabled. The thread opens. Rename the chat with `renameOpenThread(chatName)` so the Archive screen can find it later (a scratch chat is auto-named "Untitled discussion"). Read the new id with `newHostConversationId`; assert that `heldConversation(...).cwd` ends with `/<folder>`. Keep the cwd for cleanup. Leave the thread.
3. **AC-1 row.** Wait until the tree scrolls to the pencil whose description is `cd_tree_workspace_edit(folder)`: the workspace row is in the tree.
4. **AC-2 rename.** Tap that pencil; wait for the field tagged `EDIT_WORKSPACE_NAME_FIELD_TAG`; replace its text with the label; tap the enabled OK. Wait until the pencil `cd_tree_workspace_edit(label)` is on the list and the folder-named one is gone. The daemon side: `heldConversation(...).workspaceLabel` equals the label.
5. **AC-2 archive.** Tap the labelled pencil → "Archive workspace" → wait for the `edit_workspace_archive_confirm_title` confirmation → OK. Wait until the tree can no longer scroll to the labelled pencil (as #1086 waits for its archived row), then `archivedIds(...) { chatId in it }`.
6. **AC-2 restore.** `openSettings` → `showHostSettings(A)` → "Archived discussions" → the Archived screen → tap `Restore <chatName>` → wait for the "Restored" snackbar (#551's cancellation guard) → Back twice to the list.
7. **AC-2 label survives.** Wait until the tree scrolls to the pencil `cd_tree_workspace_edit(label)` again. Assert that `heldConversation(...).workspaceLabel` is still the label.

`finally`: if the cwd was captured, `renameWorkspace(cwd, null)`; if the id was captured, `delete(chatId)` (archived or not). Each call is in its own `runCatching` with a content-free `Log.w`, as `restoreSettings` does, so a cleanup failure never hides the scenario's own failure. The created folder remains under `~/pyry-workspace`, as with #562. The run-unique name keeps repeated runs apart.

New constants: `WORKSPACE_E2E_PREFIX = "e2e1087-"`. The modal's strings are read from resources through `string(...)`.

Registration: append the method to the `LIVE` `TEST_TARGET` list in `scripts/e2e-emulator.sh` with a one-line comment (no turn cost; 41 methods, 39 turns), and `LIVE_MINIMUM += 1` in `scripts/android-test-gate.py` with its comment line.

## State + concurrency model

Test-only. Daemon reads use the existing `runBlocking` + `withTimeout` helpers on the instrumentation thread, as the neighbouring scenarios do.

## Error handling

Every wait is a bounded `waitUntil` or `withTimeout`, which fails with the helper's message. Cleanup failures are logged and swallowed.

## Testing strategy

The scenario is the test. Builder proof: `./gradlew compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, `spotlessApply`. The live run needs the operator's relay and daemon (`python3 scripts/android-test-gate.py live`). The dispatcher runs it after verifier for `needs-real-claude`. No scripted twin: the flow has no claude turn to script.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: add the scenario to the LIVE inventory in § Live mode (rung 3, live relay) and § Pre-ship gate.
- `docs/knowledge/features/channel-list-screen.md` and `docs/knowledge/features/channel-list-viewmodel-related.md`: point the #676 entry's mention of #905's rename/archive flow at this scenario.

## Open questions

- Whether the tree draws the workspace pencil in both sections for a chat-only cwd. Expected: only in Chats, one node. The waits use `onFirst` after a scroll, so two nodes would not break them either.
