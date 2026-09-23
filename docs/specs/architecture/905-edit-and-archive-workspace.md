# #905 — Edit and archive a workspace from its tree row

## Files read

- `ui/conversations/components/ConversationTreeRows.kt` → `TreeWorkspaceRow`, `FoldableTreeRow` (its `trailing` slot), `TreeRowControl`, `boundedRowText` — the row gains a pencil exactly as `TreeConversationRow` gained one in #827.
- `ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `treeSection`, `ChatEditorModal` — the event and binding shapes to mirror.
- `ui/conversations/list/ChannelListViewModel.kt` → `HostChannelListState.isHostConnected`, `ChatEditorState`, `openChatEditor`, `submitChatName`, `archiveChat`, the typed `combine` behind `hostState` (at its five-flow limit, chat editor already paired with the host editor).
- `ui/host/HostEditor.kt` → `HostEditorController.requestUnpair` / `declineUnpair` / `confirmUnpair` — the confirm-in-place transitions, including "a failed confirm stays in the confirmation".
- `ui/components/EditHostModal.kt` → `EditHostModal`, `UnpairConfirmation`, `boundedText` — in-place confirmation on the shell's own footer.
- `ui/components/EditChatModal.kt` → `EditChatModal`, `ChatNameField`, `ArchiveAction` — field and outlined action styling to reuse in look.
- `ui/conversations/list/HostWorkspaceGroup.kt` → `HostWorkspaceGroup` — identity is (`serverId`, `cwd`); `displayName` is display text only.
- `ui/workspace/WorkspaceDisplayName.kt` → `workspaceDisplayName`, `MAX_WORKSPACE_LABEL_CHARS` — the folder's own name is `workspaceDisplayName(cwd, label = null)`.
- `data/repository/ConversationRepository.kt` → `renameWorkspace(path, label)`, `archiveWorkspace(path)` — both throw on refusal/disconnect; archive can partly succeed.
- `MainActivity.kt` → the `ChannelListEvent` dispatch `when`.
- `test/.../HostChannelListViewModelTest.kt` → `Fixture`, `Repo`, `row()` (both hosts' rows default to the same `cwd`, the collision the host-isolation test needs).
- `sharedTest/.../ChannelListScreenTest.kt`, `sharedTest/.../EditChatModalTest.kt`, `test/.../WorkspaceDisplayNameTest.kt` — where the new tests sit.
- `../pyrycode-desktop/.../EditWorkspaceDialog.tsx` → `EditWorkspaceDialogView` — desktop OK is disabled on `name.trim().length > 128`; mobile uses the ticket's UTF-8 byte bound instead.

In-flight overlap: none (checked every `origin/feature/*` against the files above).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2239 (content) inside the mobile shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (already implemented as `MobileModal`).

A `MobileModal` titled "Edit workspace" whose content column holds one "Input large" — a SemiBold `labelLarge` label "Workspace name (optional):" 8dp above a filled, underline-free `bodyMedium` well — then, 8dp lower, an outlined `primary`-bordered "Archive workspace" button in `bodyLarge` Medium; the shell's own Cancel/OK footer closes it. Same field and action treatment as `EditChatModal` (fill = `onPrimaryContainer` at 12%, per `EditHostModal`'s recorded light-scheme reason).

## Context

`renameWorkspace` / `archiveWorkspace` (#663) have no UI caller. This adds desktop's Edit workspace to the phone: a pencil on every workspace row, in both tree sections, opening a modal that targets that row's host and exact `cwd`.

## Design

### Label rule — `ui/workspace/WorkspaceDisplayName.kt`

- `internal const val MAX_WORKSPACE_LABEL_BYTES = 128` — the daemon's bound, in UTF-8 bytes.
- `fun workspaceLabelFor(name: String, folderName: String): String?` — trims `name`; blank or equal to `folderName` → `null` (clear), else the trimmed text.
- `fun isWorkspaceLabelTooLong(label: String?): Boolean` — `label != null` and its UTF-8 size exceeds the bound.
- Callers pass `folderName = workspaceDisplayName(cwd, label = null)`. Taking the folder name, not the `cwd`, keeps the path out of the modal.

### Row — `TreeWorkspaceRow`

New optional `onEditTapped: (() -> Unit)? = null`; when non-null it draws a `TreeRowControl(Icons.Filled.Edit, cd_tree_workspace_edit(bounded name))` in `FoldableTreeRow`'s trailing slot, the same shape as the chat row's pencil. That control is its own merging node, so a tap never folds. KDoc rewritten (drops the "draws no control / #663 / #664" text).

### Screen — `ChannelListScreen.kt`

- Events (ids only, like the chat editor's):
  - `TreeWorkspaceEditTapped(serverId, cwd)` from `group.serverId` / `group.cwd`, never `displayName`
  - `WorkspaceEditNameSubmitted(name)` (raw trimmed text; the VM applies the rule)
  - `WorkspaceEditDismissed`
  - `WorkspaceArchiveRequested`
  - `WorkspaceArchiveConfirmed`
  - `WorkspaceArchiveDeclined`
- `treeSection` passes `onEditTapped` for every workspace row in both sections.
- `WorkspaceEditorModal(hostState, onEvent)` binding: present while `hostState.workspaceEditor != null`; `hostAvailable = isHostConnected(serverId)`; `folderName = workspaceDisplayName(cwd, null)`; error from flags: `archiveFailed` → `edit_workspace_archive_failed`, `failed` → `edit_workspace_save_failed`.

### Modal — new `ui/components/EditWorkspaceModal.kt`

`internal fun EditWorkspaceModal(serverId, cwd, initialName, folderName, onDismissRequest, onSubmit: (String) -> Unit, onArchiveRequested, onArchiveConfirmed, onArchiveDeclined, modifier, hostAvailable = true, loading = false, error: String? = null, confirmingArchive = false)`

- `serverId` and `cwd` are `remember` keys for the name buffer only: they are never rendered, logged or reported.
- `initialName` is clamped at the boundary (surrogate-safe, `MAX_WORKSPACE_LABEL_CHARS`). The prompt names the workspace with that same clamped name, as a format argument.
- Editor mode:
  - The field has test tag `EDIT_WORKSPACE_NAME_FIELD_TAG` and `supportingText` length feedback: "`n`/128 bytes" of the trimmed text. `isError` is set when the text is too long.
  - OK is enabled when `hostAvailable` and `!isWorkspaceLabelTooLong(workspaceLabelFor(text, folderName))`. A blank name is allowed because it clears the label.
  - IME Done submits when OK is enabled.
  - `Archive workspace` is enabled when `hostAvailable && !loading`.
- Confirmation mode (`confirmingArchive`):
  - The title becomes `edit_workspace_archive_confirm_title`, and the content becomes one wrapping prompt.
  - The shell's OK maps to `onArchiveConfirmed`, and every dismissal route maps to `onArchiveDeclined`, as in `EditHostModal`.
  - `submissionEnabled = hostAvailable`.
- Light and dark `@Preview`s are provided for both modes.

### View model — `ChannelListViewModel.kt`

- `data class WorkspaceEditorState(serverId, cwd, initialName, confirmingArchive = false, saving = false, failed = false, archiveFailed = false)`, added to `HostChannelListState` as `workspaceEditor`. The fifth `combine` slot becomes `combine(hostEditor.state, chatEditor, workspaceEditor, ::Triple)`.
- `openWorkspaceEditor(serverId, cwd)`:
  - It looks up that host's snapshot and finds the first channel or chat whose `cwd` equals `cwd` exactly. If none exists, it logs a rejection and opens nothing.
  - `initialName = workspaceDisplayName(cwd, row.workspaceLabel)`, which is the name the row shows.
  - It does not change selection or navigation.
- `submitWorkspaceName(name)`:
  - It is ignored while saving or confirming.
  - It computes `workspaceLabelFor(name, workspaceDisplayName(cwd, null))` and ignores the call when the result is too long.
  - It resolves `hostSource.repositoryFor(serverId)` at the press. If none is available, it sets `failed`.
  - Otherwise it publishes `pending` and calls `renameWorkspace(cwd, label)`. It then uses `compareAndSet(pending, null)` on success, or sets `failed` and `saving = false` on failure.
- `requestWorkspaceArchive()` sets `confirmingArchive = true` and clears both failure flags. It is ignored while saving.
- `declineWorkspaceArchive()` sets `confirmingArchive = false` and clears `archiveFailed`. It is ignored while saving.
- `confirmWorkspaceArchive()`:
  - It requires `confirmingArchive` and no write in flight.
  - It resolves the repository in the same way. If none is available, it sets `archiveFailed`.
  - It calls `archiveWorkspace(cwd)`. Success closes the modal. Failure keeps the confirmation open with `archiveFailed`, so OK retries. Only rows that are still active are affected on retry, as documented by the repository.
- `dismissWorkspaceEditor()` sets the editor to `null` and sends nothing.
- Logs are content-free, for example `event=workspace_rename_failed`. They never contain the label, `cwd`, id or server message.

### MainActivity

Add six dispatch lines to the existing `when`.

### Strings

- `cd_tree_workspace_edit`: "Edit workspace %1$s"
- `edit_workspace_title`
- `edit_workspace_name_label`: "Workspace name (optional):"
- `edit_workspace_name_length`: "%1$d/%2$d bytes"
- `edit_workspace_archive`
- `edit_workspace_archive_confirm_title`: "Archive workspace?"
- `edit_workspace_archive_confirm_body`: "Every active chat and channel in %1$s on this host moves to Archive."
- `edit_workspace_save_failed`
- `edit_workspace_archive_failed`

## State + concurrency model

- One `MutableStateFlow<WorkspaceEditorState?>` is added. Writes are launched in `viewModelScope` and are cancelled with the VM.
- Terminal transitions use `compareAndSet` against the pending state, so a late result after Cancel or a reopen never touches the modal.
- Connection availability is derived on every draw through `isHostConnected` and is not stored.

## Error handling

- Refusal or a thrown `IllegalStateException` from either operation sets a flag, and the screen resolves it to a static string. The typed name survives because the buffer is keyed on (`serverId`, `cwd`), not on flags or on `confirmingArchive`.
- A disconnected host disables OK and Archive. A press that races a disconnect sets the failure flag and sends nothing.

## Testing strategy

- **`WorkspaceDisplayNameTest`** (unit) covers the label rule:
  - blank and whitespace → `null`
  - the folder's own name, including the `"scratch"` default and with surrounding spaces → `null`
  - other text → trimmed
  - byte bound: 128 bytes allowed, 129 rejected, multibyte counted in bytes, `null` never too long
- **`HostChannelListViewModelTest`** (unit). Both hosts hold rows at the same `cwd`, and `Repo` records `renameWorkspace` / `archiveWorkspace` calls. It covers:
  - open seeds the shown name and label, and rejects an unknown host or `cwd`
  - submit renames only the editor's host and `cwd`, using the rule, and closes; the other host gets nothing
  - blank or folder-name input sends `null`
  - an over-bound submit sends nothing
  - failure and an unavailable host keep the modal open with `failed` and log nothing sensitive; a late completion does not reopen it
  - request, decline and confirm: confirm archives only the editor's host and `cwd` and closes; failure stays confirming with `archiveFailed`; dismiss sends nothing
- **`EditWorkspaceModalTest`** (sharedTest, Robolectric):
  - it is seeded and clamped
  - OK reports the trimmed text; a blank name is allowed
  - an over-bound name disables OK and shows the error supporting text
  - the host being down disables OK and Archive
  - Archive reports the request; confirmation mode shows the prompt naming the workspace, OK confirms, and Cancel declines
  - the typed name survives a confirmation round trip
- **`ChannelListScreenTest`** (sharedTest):
  - a pencil appears on workspace rows in both sections, is at least 48dp, and names its workspace
  - a tap emits `TreeWorkspaceEditTapped(serverId, cwd)` and does not fold the row
  - the binding shows the static error strings
- No rung-3 scenario: per the ticket, the live round trip is #676.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls (#738): replace the "Not in this slice" paragraph with the workspace row's pencil.
- `docs/knowledge/features/mobile-modal.md` § Callers: add Edit workspace.
- `docs/knowledge/features/channel-list-screen.md`: correct the line that gives #664 the workspace row's add control.

## Open questions

- Should length feedback be shown always or only near the bound? Plan: always shown as supporting text, and marked as an error only past the bound.
