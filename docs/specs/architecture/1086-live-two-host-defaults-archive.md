# #1086 — rung 3: each host's default workspace and Archive stay its own across two hosts

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` (#847) and
    `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` (#1085): pairing B with
    `pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))` and removing B in `finally` through
    `PairedServerCollectionStore.remove`. The new scenario copies that frame.
  - `interactiveTurn_archiveRestore_roundTripsListMembership` (#551): the one-host archive-from-thread and
    restore-from-Archive drive, including the "Restored" snackbar wait that keeps the restore coroutine
    from being cancelled by the Back navigation.
  - `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (#566): the picker's "Create new
    folder" row, the create dialog and the run-unique folder prefix discipline under the shared real
    `~/pyry-workspace`.
  - Helpers reused: `hostConversationIds` (the three-argument form that follows a redial with
    `firstOnLive`), `newHostConversationId`, `heldConversationName`, `renameOpenThread`, `scrollListTo`,
    `createChat` / `awaitHostAddControl`, `hostLabel`, `awaitChannelList`, `awaitConnected`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `createHostDiscussion`
  reads `AppPreferences.defaultWorkspace(serverId)` for the tapped host row's own host.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `defaultWorkspace`,
  `onDefaultWorkspaceTapped`, `onSelectDefaultWorkspace`: the Settings row reads and writes only the
  destination's captured owner (#714).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → the Connection section's
  `HostIdentityRow` per host (owner row carries `settings_host_current` "This server" and opens the editor;
  any other row hops to that host's Settings, replacing the entry), and the "Default workspace" row whose
  supporting text is `workspaceDisplayName` (the folder basename for an unlabelled folder).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` →
  `WorkspacePickerInternal`: a created folder's path, as `workspace_folder_created` returned it, goes
  straight to `onPicked`, so Settings stores the daemon's canonical absolute path.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the Settings destination (owner from the route,
  `onOpenHost` hop, `onOpenArchivedDiscussions` inheriting that owner) and `ChannelListEvent.SettingsTapped`
  (captures the selected host, which is B once B is paired).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `defaultWorkspace(serverId)`,
  `setDefaultWorkspace(serverId, cwd)`.
- `../pyrycode/docs/protocol-mobile.md` § `create_conversation` / `conversation_created` /
  `workspace_folder_created` and the 2026-09-24 changelog entry (#2568): both the created folder's path and
  a created conversation's `cwd` are the resolved realpath, so they compare equal byte for byte.
- `docs/knowledge/features/channel-list-viewmodel-testing.md`, `archived-discussions-screen.md`,
  `navigation.md`: each still names #676 as the owner of this live proof (documentation handoff below).
- `scripts/e2e-emulator.sh` (the `LIVE=1` `TEST_TARGET` list) and `scripts/android-test-gate.py`
  (`LIVE_MINIMUM`).

## Design source

N/A — test and script only; no UI changes.

## Context

#714 and #715 scoped the Settings default-workspace row and the Archive screen to their host, proven only
against fakes. This adds the rung-3 scenario that proves both with two real daemons paired. Zero
real-claude turns: folder creation, create-discussion, rename, archive and unarchive are daemon
round-trips. No overlapping in-flight branches.

## Design

One new `@Test` on `InteractiveStreamE2ETest`:
`interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost`.

Steps:

1. Arrive on the list, wait connected, record A's stored default. Grant CAMERA, pair B by code, record B's
   stored default.
2. **A's default.** Open A's Settings, tap "Default workspace", create folder `e2e1086-a-<ms>` from the
   picker. Wait until the row shows that basename and A's stored default is non-scratch. Assert the stored
   value ends in `/<folderA>`. Assert B's stored default is still its recorded original.
3. **B's default.** Hop to B's Settings through its Connection row and repeat with `e2e1086-b-<ms>`. Assert
   A's stored default is still `pathA`. Hop back to A's Settings: its row still shows `folderA` and no
   node shows `folderB`.
4. Back to the list. **Create on A** from A's host-row add control: the new id's `cwd`, as A's live
   repository lists it, equals `pathA`. Rename the chat to a run-unique name so it can be archived and
   found. Back.
5. **Create on B** from B's host-row add control (scrolled into view): the new id's `cwd` equals `pathB`.
   Back.
6. Snapshot B: its `All` ids and its `Archived` ids.
7. **Archive** A's renamed chat from its thread overflow. Its name leaves the list; A's `Archived` ids gain
   it. B's two snapshots are unchanged.
8. **Restore** from A's Archive: A's Settings → "Archived discussions" → tap "Restore <name>" → wait for
   the "Restored" snackbar → Back twice. The name is back on the list, A's `Archived` ids no longer hold
   it, A's `Discussions` ids do. B's two snapshots are unchanged.
9. `finally`: restore both stored defaults to the recorded originals, then remove B from the store, as #847
   does.

Test-only helper changes (all private to the class):

- `openHostSettings(serverId)`: from the list, tap the gear; if `serverId`'s Connection row is not the
  owner row ("This server"), tap it to hop; then wait until it is. Landing is proven by the badge, not by
  which host was selected.
- `pickNewDefaultFolder(folderName)`: tap "Default workspace", create the folder, and wait for the row to
  show the basename.
- `heldConversation(serverId, conversationId): Conversation` extracted from `heldConversationName`, which
  delegates to it, so `cwd` can be read the same way.
- `hostConversationIds(serverId, what, ready)` gains a `filter: ConversationFilter = ConversationFilter.All`
  parameter before `ready`. Existing callers are unchanged.
- `createChat` / `awaitHostAddControl` gain a `serverId` parameter that defaults to host A's harness id, so
  the two bodies that name the control stay two.
- Constants: `DEFAULTS_FOLDER_PREFIX = "e2e1086-"`, `DEFAULTS_CHAT_NAME_PREFIX = "e2e1086-chat-"`,
  `DEFAULT_WORKSPACE_ROW = "Default workspace"`.

## State + concurrency model

No production state. Data-layer reads go through the current Koin graph (`GlobalContext`) with
`runBlocking` plus `withTimeout` / `withTimeoutOrNull`, as the existing helpers do. Every eventually
consistent read (stored default after a pick, archived set after archive or restore) waits on a
predicate rather than reading once.

## Error handling

A pick that fails shows the picker's "Couldn't create folder" dialog. The row wait then times out and the
test fails naming the step. `finally` writes back both defaults even on a red run, so later scenarios'
`createChat` keep creating in A's original default.

## Testing strategy

The scenario is the test. It joins the `LIVE=1` list in `scripts/e2e-emulator.sh` at zero added turns
(38 methods become 39, still 39 turns), and `LIVE_MINIMUM` in `scripts/android-test-gate.py` rises by 1.
Builder verification: `./gradlew compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, `spotlessApply`.
Executing the scenario needs the live relay and the host-B daemon. The dispatcher's
`python3 scripts/android-test-gate.py live` runs it after the verifier (`needs-real-claude`).

## Open questions

- Is the Settings Connection row's merged node matchable by `hasText(serverId)`? The row draws the server
  id as its own `Text` and the clickable `Column` merges descendants, so it should be. If not, match by the
  host label.
- Does B's add control need a scroll? B's section sits below A's, so the scenario scrolls to its tag first.

## Documentation handoff (pending, documentation stage)

- `docs/e2e-interactive-stream.md`: add the scenario to the LIVE inventory in § Live mode (rung 3, live
  relay) and § Pre-ship gate (39 methods, 39 turns).
- Re-point the lines naming #676 as owner of the two-host defaults / archive-restore live proof at this
  scenario: `docs/knowledge/features/channel-list-viewmodel-testing.md` (the "different defaults on two
  live hosts" sentence), `docs/knowledge/features/archived-discussions-screen.md` (the restore note and
  follow-up (f)), `docs/knowledge/features/navigation.md` (the archive/restore sentences in § Testing and
  the follow-ups line).
