# #1088 — rung 3: create, edit and archive a channel, with its prompt read back

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_muteChannel_roundTripsThroughTheHost` / `setMuteInEditChannel` (#1021) — the Edit channel drive from a Channels row's pen (`cd_tree_channel_edit`), the title wait, `EDIT_CHANNEL_OK` and the close-means-confirmed wait.
  - `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` (#1087) — the nearest shape: run-unique tokens from one stamp, `newHostConversationId`, archive then `archivedIds`, restore through `openSettings` + `showHostSettings` + "Archived discussions" + the "Restored" snackbar wait, and a `finally` of `runCatching` cleanups.
  - `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (#541) — the thread overflow's Reset session and `awaitDisplayedSessionBoundary`.
  - Helpers reused as they are: `awaitChannelList`, `awaitConnected`, `hostRepository`, `hostConversationIds`, `newHostConversationId`, `archivedIds`, `channelRow`, `awaitChannelRow`, `scrollListTo`, `openRow`, `leaveThread`, `sendFromPhone`, `openSettings`, `showHostSettings`, `string`, `modalCancel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `openCreateChannel` (opens only on a workspace where the host already holds an active channel), `submitCreateChannel` (create, then the prompt, then opens the channel's thread), `openChannelEditor` / `readChannelPrompt` (one `requestSystemPrompt` per open), `submitChannelEdit`, `archiveChannel` (no confirmation).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → the tree's `TreeWorkspaceRow` call: the plus is drawn on Channels-section workspace rows only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeWorkspaceRow`: the plus's description is `cd_tree_workspace_new_channel` with the row's shown name.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `workspaceDisplayName`: an unlabelled workspace shows its folder's name, so a run-unique folder gives a run-unique plus.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` → `EditChannelModal`: the prompt field is disabled until the reading arrives; a `Differs` reading draws `edit_channel_prompt_next_session`; the outlined "Archive channel" action.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `CHANNEL_NAME_FIELD_TAG`, `CHANNEL_PROMPT_FIELD_TAG`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt` → the Archived screen opens on the Discussions tab; archived channels are under `archived_tab_channels`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createWorkspaceFolder`, `createChannel`, `requestSystemPrompt`, `SessionPromptStatus`, `delete`.
- `../pyrycode/docs/protocol-mobile.md` § `request_system_prompt` (`session_prompt_status`) and § New session: a reset of a conversation with a live child runs a daemon wrap-up turn before the respawn.
- `scripts/e2e-emulator.sh` (the `LIVE` `TEST_TARGET` list) and `scripts/android-test-gate.py` (`LIVE_MINIMUM`).

In-flight overlap: #1076 appends a live method to the same test class and both scripts. It is not a dependency; my edits are appends.

## Design source

N/A — test and script only; no UI changes.

## Context

`CreateChannelModal` (#958) and `EditChannelModal` (#667) are proven only against fakes, apart from #1021's mute row. This adds one live rung-3 scenario on host A, split from #676. No ADR needed.

## Design

One new `@Test` in `InteractiveStreamE2ETest`: `interactiveTurn_createEditArchiveChannel_readsPromptBack`.

Run-unique tokens from one timestamp with a new `CHANNEL_E2E_PREFIX = "e2e1088-"`: the anchor folder `e2e1088-<stamp>`, the anchor channel `e2e1088-anchor-<stamp>`, the channel's first name `e2e1088-<stamp>-a` and its new name `e2e1088-<stamp>-b`. Two fixed prompts, `CHANNEL_PROMPT_FIRST` and `CHANNEL_PROMPT_SECOND`, both harmless to the ping.

**Precondition.** Create channel opens only from a Channels-section workspace row, which exists only where the host already holds an active channel. The scenario seeds one on the host directly, as #1021 seeds its channel: `createWorkspaceFolder(folder)` then `createChannel(anchorName, path)`. The workspace row then shows the run-unique folder name, so its plus (`cd_tree_workspace_new_channel(folder)`) is unambiguous.

Steps:

1. `awaitChannelList`, `awaitConnected`; seed the anchor; record host A's ids.
2. **AC-1 create.** Scroll to the folder's plus and tap it; wait for `create_channel_title`; type the first name into `CHANNEL_NAME_FIELD_TAG` and the first prompt into `CHANNEL_PROMPT_FIELD_TAG`; tap OK. The channel's thread opens (send button). Read its id with `newHostConversationId`.
3. **AC-1 ping.** Send `PING_PROMPT` and wait for the reply; the session now runs with the first prompt. Leave the thread.
4. **AC-1 edit.** Open Edit channel from the first name's pen; wait until the prompt field holds the first prompt (the create's prompt read back, and the field enabled). Replace the name and the prompt; OK; wait for the modal to close.
5. **AC-1 read back.** The Channels row shows the new name and the old one is gone. Reopen Edit channel from the new name's pen: the name field holds the new name, the prompt field the new prompt, and the next-session line is shown. Cancel.
6. **AC-2 new session.** Open the row; thread overflow → Reset session; wait for the session boundary. Send a second ping and wait until the daemon's `requestSystemPrompt` reports `Matches`, which needs a session spawned after the edit to be running, whether the respawn was eager or the ping spawned it. Leave the thread.
7. **AC-2 read back.** Reopen Edit channel: the prompt field holds the new prompt, and once it does (the reading has arrived) the next-session line is absent.
8. **AC-3 archive.** In the same modal tap "Archive channel"; wait for the modal to close and for the list to no longer scroll to the new name's Channels row; `archivedIds { id in it }`.
9. **AC-3 restore.** `openSettings` → `showHostSettings(A)` → "Archived discussions" → the Channels tab → tap `Restore <new name>` → the "Restored" snackbar → Back twice to the list. `awaitChannelRow(newName)`; no Channels row carries the first name.

Shared helpers added: `openChannelEditor(name)` (pen tap and title wait) and `awaitPromptField(prompt)` (wait until the tagged prompt field is enabled and holds the text).

`finally`: `delete` the channel and the anchor, each in its own `runCatching` with a content-free `Log.w`. The anchor folder remains under `~/pyry-workspace`, as #1087's does.

**Turn cost.** Two pings, plus the daemon's wrap-up turn on Reset session, which the list's count leaves out as it does for #541. The list goes to 42 methods and 41 turns.

Registration: append the method to the `LIVE` `TEST_TARGET` list in `scripts/e2e-emulator.sh` with a one-line comment, and `LIVE_MINIMUM += 1` in `scripts/android-test-gate.py` with its comment line.

## State + concurrency model

Test-only. Daemon reads use `runBlocking` + `withTimeout` on the instrumentation thread, as the neighbouring scenarios do.

## Error handling

Every wait is a bounded `waitUntil` or `withTimeout`. Cleanup failures are logged by class name and swallowed.

## Testing strategy

The scenario is the test. Builder proof: `./gradlew compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, `spotlessApply`. The live run needs the operator's relay and daemon (`python3 scripts/android-test-gate.py live`); the dispatcher runs it after verifier for `needs-real-claude`. No scripted twin: `request_system_prompt`'s verdict comes from the daemon's real spawn, and the scripted backend does not model a prompt.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: add the scenario and its turn count (two pings plus the reset's wrap-up turn) to the LIVE inventory in § Live mode (rung 3, live relay) and § Pre-ship gate.
- `docs/knowledge/features/channel-list-viewmodel-related.md`: point the #676 entry's mention of #667's rename/archive/prompt round trip at this scenario.

## Open questions

- Whether Reset session respawns eagerly. The `Matches` wait after the second ping holds either way.
- Whether the thread overflow draws Reset session on a channel. #541's comment says it is gated on `mutationsSupported` only.
