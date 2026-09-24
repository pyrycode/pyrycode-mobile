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

## Revisions

### 2026-09-24 — rework after verifier FAIL on PR #952

- **Security review added.** The ticket carries `security-sensitive`. The first run did not run the `builder/security-review.md` pass, and the verifier failed the plan for that reason alone. The section below is that pass, run against the committed design and the shipped diff.
- **Clamped folder name clears the label.** The finding came from the security pass (Trust boundaries) and the verifier's second NIT. The modal clamps its seed to `MAX_WORKSPACE_LABEL_CHARS`. When a folder name is longer than that and has no label, the seed is a cut of the folder name, so an untouched OK used to send that cut as a new label instead of `null`. New contract:
  - `internal fun clampWorkspaceText(text: String): String` in `WorkspaceDisplayName.kt` is the single surrogate-safe clamp. `workspaceDisplayName` and `EditWorkspaceModal` both call it.
  - `workspaceLabelFor` also returns `null` when the trimmed input equals `clampWorkspaceText(folderName).trim()`.
  - The new test in `WorkspaceDisplayNameTest` is `labelRule_theModalsClampedSeedOfAnOverlongFolderNameClearsTheLabel`.
- **Length feedback wording.** This follows the verifier's first NIT. `edit_workspace_name_length` shipped as "UTF-8 bytes: %1$d/%2$d" instead of the plan's "%1$d/%2$d bytes". The shipped wording stays, because it names the daemon's unit, which a bare "bytes" does not. The strings comment records the reason.

### 2026-09-24 — rework after the gate regression on PR #952

- **The clamped-seed comparison applies only to a cut folder name.** The verifier's gate found that the previous entry's rule trimmed every folder name, so a typed `"Path"` cleared the label of folder `"Path "` and `workspaceSubmitRenamesOnlyTheEditorsHostAndCwdByTheLabelRuleAndCloses` failed. New contract for `workspaceLabelFor`:
  - It returns `null` when the trimmed input is blank or equals `folderName` exactly.
  - It also returns `null` when `clampWorkspaceText(folderName) != folderName` and the trimmed input equals that cut, trimmed. The cut is trimmed because the input is.
  - `WorkspaceDisplayNameTest` pins both halves: `"Path"` stays a label for folder `"Path "`, and a cut ending in a space still clears.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new inbound verb. Daemon-authored text reaches this UI through three values:
  - **The row's shown name.** `TreeWorkspaceRow` clamps it with `boundedRowText` before drawing it and before using it in the pencil's content description.
  - **The modal's seed and the confirmation prompt's name.** These are clamped once by `clampWorkspaceText` inside `EditWorkspaceModal` and rendered only as `Text` or as a `stringResource` format argument. They never reach a URL, a filename, a cache key or a log line.
  - **`cwd`.** It is a `remember` key and a write target only. The modal never draws it. `folderName` is used only to compare against, and the text that gets rendered is the clamped `boundedName`.
- [Trust boundaries] SHOULD FIX, now fixed: an untouched OK on an overlong folder seed stored the clamped cut as a label. See Revisions. `workspaceLabelFor` now recognises the cut.
- [Host and path targeting] No findings.
  - Writes go only to `WorkspaceEditorState.serverId` and `cwd`. Those come from `TreeWorkspaceEditTapped(group.serverId, group.cwd)`, never from `displayName`.
  - `openWorkspaceEditor` refuses a (`serverId`, `cwd`) pair that the named host's own snapshot does not hold.
  - `submitWorkspaceName` and `confirmWorkspaceArchive` resolve `hostSource.repositoryFor(target.serverId)` at the press. A host that went away gets a failure flag and no write, never another host's repository.
  - `HostChannelListViewModelTest` gives both hosts rows at the same `cwd` and asserts that the other host records no call.
  - The phone sends back the exact `cwd` it received and never builds or concatenates a path.
- [Destructive archive] No findings.
  - Archive needs two presses: Archive workspace, then the shell's OK on the in-place prompt. The prompt names the workspace and says every active chat and channel there moves to Archive.
  - `confirmWorkspaceArchive` refuses unless `confirmingArchive` is set and no write is in flight. `declineWorkspaceArchive` refuses mid-write, so Cancel or Back cannot race the close.
  - Partial success is covered by `archiveWorkspace`'s contract: confirmed rows stay archived and a retry archives only the rows still active. A row created at that `cwd` between the failure and the retry is archived by the retry, which matches what the prompt says.
  - Archive is reversible through Archive, and nothing is deleted or renamed.
- [Label bound] No findings. `isWorkspaceLabelTooLong` measures UTF-8 bytes against `MAX_WORKSPACE_LABEL_BYTES`, which is 128. It is checked in two places: in the modal, where OK is disabled and the field shows an error, and again in `submitWorkspaceName`, which rejects the call before any write. The daemon refuses over-bound labels anyway, so the client-side bound is a UX guard and not the security property.
- [Tokens / secrets] Not applicable. The change reads, stores and logs no token or key. It rides the existing Noise session through `ConversationRepository`.
- [File / storage] Not applicable. Nothing is persisted: the editor state is an in-memory `MutableStateFlow`, and the typed name lives in a `remember` buffer. There is no local file I/O and no path is built from input.
- [Inter-process] Not applicable. There is no new Activity, intent filter, deep link, pending intent or WebView.
- [Crypto] Not applicable. There is no new primitive, randomness or comparison against a secret.
- [Network & I/O] Not applicable. There is no new frame. `renameWorkspace` and `archiveWorkspace` (#663) use the existing supervisor, codec and timeouts.
- [Errors and logs] No findings.
  - Failures publish the `failed` and `archiveFailed` flags. The screen resolves them to the static `edit_workspace_save_failed` and `edit_workspace_archive_failed`, so no daemon message reaches the shell's live region.
  - `RelayLog` lines carry an event name, a static code and at most `cleared=<boolean>`. They never carry the label, `cwd`, server id or exception text.
  - `RelayLog` is gated by its `enabled` flag, and the modal's single `Log.d` is behind `BuildConfig.DEBUG`.
- [Concurrency] No findings.
  - Both writes are launched in `viewModelScope` and cancelled with the VM. `CancellationException` is rethrown.
  - Terminal transitions use `compareAndSet(pending, …)`, so a late result after Cancel or a reopen on another workspace cannot close, reopen or flag the wrong editor.
  - `saving` blocks a second submit or confirm.
  - Accepted, and matching the chat editor: dismissing the editor during a rename closes the modal while the rename already sent still completes on the daemon.
- [Threat model] OUT OF SCOPE: whether the daemon itself validates `renameWorkspace` and `archiveWorkspace` targets (it must refuse unknown paths and over-bound labels) is daemon-side (#663's contract). The live round trip is #676.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
