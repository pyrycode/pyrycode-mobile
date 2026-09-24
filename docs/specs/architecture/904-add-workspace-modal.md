# #904 — Add workspace from a host row, in the modal shell

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel` (`openHostWorkspacePicker`, `pickHostWorkspace`, `dismissHostWorkspacePicker`, `sendHostDiscussion`, `submitChatName`, the `hostState` combine), `HostChannelListState` (`workspacePickerServerId`, `isHostConnected`), `ChatEditorState` — the state being replaced and the host-resolved-write pattern to follow.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent` (`TreeHostAddLongPressed`, `WorkspacePicked`, `WorkspacePickerDismissed`), `ChannelListScreen`, `ChatEditorModal` — the binding shape to mirror.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`'s `CHANNEL_LIST` destination and `HostWorkspaceRepository` — the sheet's composition-local binding, which this screen no longer needs.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — shell contract (never closes itself; `submissionEnabled`, `loading`, `error`).
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `EditChatModal` — nearest caller: stateless, host-availability gate, content-free debug log, surrogate-safe clamp.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` / `WorkspacePickerSheet.kt` → `WorkspacePickerInternal`, `WorkspacePickerSheetContent` — today's recents/create flow and the strings the device suites match ("Recent", "Create new folder under pyry-workspace…"). Both stay for the thread and Settings.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt` → `CreateFolderDialog` — reused unchanged for the new-folder entry; its focus effect already lives in its own dialog window.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `repositoryFor`, `snapshots` — the host-resolved lookup; `repositoryFor` is point-in-time, so recents re-resolve on snapshot emissions.
- `app/src/main/java/de/pyryco/mobile/data/repository/WorkspaceCommands.kt` → `recentWorkspaces` (one-shot fetch per collection, fails closed to empty), `createWorkspaceFolder` (returns the daemon's path).
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `MAX_WORKSPACE_LABEL_CHARS` and the surrogate-safe clamp idiom.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`, `Host`, `Repo` — two-host fixture with a selected-adapter decoy; the picker tests being rewritten.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt` → `flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges` — the relay-level two-host proof that calls the removed method.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`, `openWorkspacePicker`.
- `docs/knowledge/features/mobile-modal.md` § Caller contract, § Callers — clamp where untrusted text enters, `hasText` passes on clipped text, confirmation as content swap.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls (#738).

In-flight overlap: #883 deletes `LiteralScreenNavigationTest.kt` (and edits unrelated blocks of `MainActivity.kt`); #875/#883/#889 append to `strings.xml`. None is a dependency — edits here stay additive/local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The node is the shared modal shell (drawn as Edit host), which `MobileModal` already implements: full-height `primaryContainer` surface, `titleLarge` header with close glyph over an `inversePrimary` rule, content centred in a scroll area, outlined Cancel + filled OK footer. There is no Add workspace content frame; the content is the adapted picker, drawn with the shell's existing tokens: `labelLarge` SemiBold section labels (as `EditHostModal`'s field label), radio rows with the path in `bodyMedium` monospace, and an outlined `small`-shape action with a 1dp `primary` border for the new-folder entry (as `EditChatModal`'s Archive action).

## Context

The long-press on a host row's add control opens the bottom-sheet picker; a failure to start the chat surfaces after the sheet has gone. This moves that path into `MobileModal` as **Add workspace** (desktop's host-row dialog on the phone): select a recent folder or create one, then OK starts an unpromoted chat there on that host and opens it. Tap on the add control, the thread's and Settings' pickers are unchanged.

## Design

### State (view model)

```kotlin
data class AddWorkspaceState(
    val serverId: String,
    val selected: String? = null,
    val busy: Boolean = false,          // a folder creation or a chat start in flight
    val createFailed: Boolean = false,
    val startFailed: Boolean = false,
)
```

`HostChannelListState` loses `workspacePickerServerId` and gains `addWorkspace: AddWorkspaceState?` and `addWorkspaceRecent: List<String>`. In the `hostState` combine, the slot `pendingHostWorkspacePicker` held becomes `combine(addWorkspace, addWorkspaceRecent, ::Pair)`, so the typed combine stays at five.

`addWorkspaceRecent` is derived: `addWorkspace.map { it?.serverId }.distinctUntilChanged()` → `flatMapLatest` to, for a non-null id, `hostSource.snapshots.map { hostSource.repositoryFor(id) }.distinctUntilChanged().filterNotNull().flatMapLatest { it.recentWorkspaces() }`, capped to `MAX_ADD_WORKSPACE_RECENTS` (50), `catch` → empty, `onStart` empty. Each emission is tagged with the server id it was fetched for (`serverId to list`), and the `hostState` combine publishes the list only when that id equals the open state's `serverId`, else empty — so the `combine` can never pair one host's modal with another host's list, even for the one emission between a retarget and `flatMapLatest` switching. Keying on the server id alone means a selection or flag change never re-fetches; closing and reopening does (the recents "re-fetch on every open" behaviour the e2e depends on). Re-resolving on snapshot emissions picks up a host that connects after the modal opened; `filterNotNull` keeps the last list through a disconnect.

### Transitions (`ChannelListViewModel`)

- `openAddWorkspace(serverId)` — rejects an id absent from `hostSource.snapshots` (content-free log); otherwise publishes `AddWorkspaceState(serverId)`. Replaces `openHostWorkspacePicker`.
- `selectAddWorkspaceFolder(path)` — ignored when closed or `busy` (a change mid-flight would break the terminal `compareAndSet`); sets `selected`, clears both failure flags.
- `createAddWorkspaceFolder(name)` — ignored when closed, `busy` or blank after trim. Resolves `hostSource.repositoryFor(state.serverId)` at the press; null → `createFailed`, nothing sent. Else publishes `pending = busy, flags cleared` and launches `createWorkspaceFolder(trimmed)`: failure → `compareAndSet(pending, pending.copy(busy = false, createFailed = true))` (selection intact); success → `compareAndSet(pending, pending.copy(busy = false, selected = path))`. Creating never starts the chat.
- `submitAddWorkspace()` — ignored when closed, `busy` or no selection. Same resolution; null → `startFailed`. Else `pending` and launch `createDiscussion(selected)`: failure → `compareAndSet(... startFailed = true)`; success → only if `compareAndSet(pending, null)` succeeds, record `lastOpenedTarget` and send the target on `hostNavigationChannel` (a result landing after Cancel neither reopens the modal nor navigates).
- `dismissAddWorkspace()` — `addWorkspace.value = null`; sends nothing.

`pickHostWorkspace` and `dismissHostWorkspacePicker` are removed; `sendHostDiscussion` stays for `createHostDiscussion`. Failures publish flags only; `CancellationException` rethrown.

### Component — `ui/components/AddWorkspaceModal.kt`

```kotlin
@Composable
internal fun AddWorkspaceModal(
    recent: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
)
```

Stateless apart from the `CreateFolderDialog` visibility (`rememberSaveable`). Draws `MobileModal(title = "Add workspace", submissionEnabled = selected != null && hostAvailable, loading, error)`. Content: when `selected` is not among `recent`, a "New folder" section holding that row; a "Recent" section when `recent` is non-empty; an empty-state line when both are empty; then the outlined "Create new folder under pyry-workspace…" action (disabled while `loading`), which opens `CreateFolderDialog` stacked over the shell and forwards its trimmed name through `onCreateFolder`. Rows are `selectable(role = RadioButton)` with a leading `RadioButton(onClick = null)`, disabled while `loading`; `onSelect` receives the raw path from the list, never the displayed text. Displayed text is the path clamped to `MAX_PATH_DISPLAY_CHARS` (512) without splitting a surrogate pair, wrapping rather than `maxLines`.

The new-folder dialog is a second window over the shell. The #745 content-swap rule is for a confirmation of the shell's own decision; here the stacked dialog is the reused existing path the ticket names, is itself dismissible, and returns to the shell with its selection unchanged.

### Screen and route

- `ChannelListEvent`: remove `WorkspacePicked`, `WorkspacePickerDismissed`; add `AddWorkspaceSelected(path)`, `AddWorkspaceFolderCreateRequested(name)`, `AddWorkspaceSubmitted`, `AddWorkspaceDismissed` (no ids — the target is the open state's).
- `ChannelListScreen` drops `WorkspacePicker` and draws a private `AddWorkspaceModalBinding(hostState, onEvent)`, present exactly while `hostState.addWorkspace` is non-null: `hostAvailable = hostState.isHostConnected(serverId)`, `loading = busy`, error resolved from the flags to static strings.
- `MainActivity`: the `CHANNEL_LIST` destination no longer wraps the screen in `HostWorkspaceRepository` (nothing on it reads `LocalWorkspacePickerRepository` any more); event arms map to the five new methods.
- Strings (`strings.xml`): `add_workspace_title`, `add_workspace_recent` ("Recent"), `add_workspace_new_folder_section`, `add_workspace_create_folder` ("Create new folder under pyry-workspace…"), `add_workspace_empty`, `add_workspace_create_failed`, `add_workspace_start_failed`.

## State + concurrency model

All jobs in `viewModelScope`. `addWorkspace` is a `MutableStateFlow`; terminal transitions are `compareAndSet` against the published pending state, and every entry point refuses while `busy`, so a late completion after dismiss/reopen is inert. `addWorkspaceRecent` is cold, collected through `hostState`'s `WhileSubscribed` sharing, and cancelled by `flatMapLatest` when the modal closes or retargets.

## Error handling

`createWorkspaceFolder` / `createDiscussion` throw on not-connected, server error and malformed reply: caught (cancellation rethrown), mapped to `createFailed` / `startFailed`, logged as a static event name. The UI resolves each flag to a static string; the daemon's message never reaches state, UI or logs. A null repository at the press fails the same way without sending. Recents fail closed to empty.

## Testing strategy

- **Unit (`HostChannelListViewModelTest`)**, replacing the picker tests; `Repo` gains `recentWorkspaces`/`createWorkspaceFolder` overrides with a gate and a failure hook.
  - Open on host A while the selected adapter is B: recents are A's only; a created folder is made on A and becomes the selection without starting a chat; OK creates the chat on A with that exact path and navigates; B untouched; state closes.
  - Failed create and failed start keep the modal open, selection intact, the matching flag set, no navigation; logs carry no server message, path or id.
  - Submit/create while busy is ignored; a completion after dismiss neither reopens nor navigates; dismiss sends nothing; submit with no selection sends nothing; an unavailable host fails the press without sending (adapted `unavailableTargets…` and `guardedFailures…` tests).
- **Compose (`ChannelListScreenTest`)**: the modal draws exactly while `addWorkspace` is set; OK is disabled with no selection and while the host is disconnected, enabled with both; tapping a recent row emits `AddWorkspaceSelected` with the raw path; a selection absent from recents is drawn; an error flag shows its static string.
- **Device, relay-backed (`LiteralScreenNavigationTest`)**: `flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges` opens through `openAddWorkspace`, creates, presses OK — still asserts host B receives none of the four verbs.
- **Rung-3 (`InteractiveStreamE2ETest`)**: `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` creates the folder in the modal, waits for OK to be enabled, presses OK, and keeps its re-open "Recent" check. `openWorkspacePicker` keeps the tag-based handle. No rung-4 twin exists for this scenario and none is added (the scripted harness has no workspace-folder fixture).

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls (#738): the long-press now opens Add workspace.
- `docs/knowledge/features/mobile-modal.md` § Callers: add Add workspace.

## Open questions

- Whether the e2e needs an explicit wait for OK to enable after the create completes (the snapshot's link status must read Connected). Resolve by waiting on an enabled OK node in the scenario.

## Security review

**Verdict:** PASS (after one revision: first pass FAIL on the cross-host recents pairing, fixed in § State)

**Findings:**

- [Trust boundaries] MUST FIX (resolved in the plan): the first draft combined `addWorkspace` and an untagged recents flow, so for one `combine` emission after a retarget, host B's modal could carry host A's daemon-authored folder list, and a tap then would send A's path to B. Recents are now tagged with their fetch host and published only when that host matches the open state's `serverId`.
- [Trust boundaries] No further findings. Recent paths and the created-folder path are daemon-authored text: rendered only as `Text`, clamped to `MAX_PATH_DISPLAY_CHARS` without splitting a surrogate pair, and the list is capped to `MAX_ADD_WORKSPACE_RECENTS` in the view model so a hostile reply cannot put thousands of rows into the non-lazy shell column. They never reach a log, URL, filename or key. The raw path goes back to the same host verbatim in `createDiscussion`, and that host enforces confinement, as the sheet relied on before.
- [Tokens] No findings. The path touches no credential. `serverId` is used only to resolve `hostSource.repositoryFor` and is never logged.
- [File / storage] No findings. The phone never opens a path. The operator-typed folder name goes to `create_workspace_folder`, which the daemon confines under `pyry-workspace/`. Nothing is persisted. `rememberSaveable` holds only the create dialog's visibility boolean.
- [Inter-process] No findings. There is no new component, intent, deep link or pending intent.
- [Crypto] No findings. The existing Noise session is reached through the repository only.
- [Network & I/O] No findings. There is no new verb (`recent_workspaces`, `create_workspace_folder`, `create_conversation`). The `busy` guard refuses a second create or start while one is in flight, so a double tap cannot send a duplicate `create_conversation`.
- [Logs] No findings. Only static event names are logged. The failure strings are static resources, and the exception message never enters state. The unit test asserts that no server message, path or id reaches a log line.
- [Concurrency] No findings. Terminal transitions use `compareAndSet` against the published pending state. Selection is refused while `busy`, so the machine cannot stick in `busy`. A completion after Cancel neither reopens the modal nor navigates. The accepted residual is that Cancel during an in-flight start cannot recall the already-sent verb. The chat then appears in the list, unopened, and the dismissal itself sends nothing.
- [Threat model] No findings. A relay that drops the reply leaves the modal loading, and Cancel/Close/Back stay available (the shell keeps dismissal live while loading). A new open starts from a fresh state. UI-side screenshot exposure of folder paths is unchanged from the sheet and OUT OF SCOPE here, since no ticket asks for `FLAG_SECURE` on editing modals.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- **2026-09-23 (implementation): Open question resolved, no design change.** The rung-3 scenario and the relay-backed `flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges` both wait for an enabled `OK` node (`hasText("OK") and isEnabled()`) before pressing it. The device test also asserts that no `create_conversation` went out between the folder's creation and the OK press.
