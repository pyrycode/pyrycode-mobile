# #667 — Edit channel from a Channels row

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel`, `HostChannelListState`, `ChatEditorState`, `CreateChannelState`, `openChatEditor`, `submitChatName`, `archiveChat`, `submitCreateChannel`, the `addWorkspaceRecent` host-tagged flow — the modal-state shape, the `compareAndSet` terminal transitions and the host-tagging idiom this ticket mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SystemPromptEditor.kt` → `SystemPromptEditor`, `SystemPromptEditorState.Loaded.changed` — considered and not used (see Context); its `changed` rule and redacted `toString` are copied.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `repositoryFor` — per-connection, returns `null` while disconnected; a reconnect yields a new repository.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` § "Exact-host repository access" — a retired repository can still be cached; resolve at the press.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `rename`, `archive`, `requestSystemPrompt`, `setSystemPrompt`, `SystemPromptReading`, `SessionPromptStatus`, `SystemPromptLimit`.
- `app/src/main/java/de/pyryco/mobile/data/network/SystemPromptPayloads.kt` → `toSystemPromptReading` — no size bound on an inbound prompt (security review).
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `ChannelFormFields` — the shared name-and-prompt form; gains a prompt enabled flag and a static note line.
- `app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt`, `EditChatModal.kt` → `CreateChannelModal`, `EditChatModal`, `ArchiveAction` — the modal shape (remember-keyed buffers, surrogate-safe clamp, outlined archive action).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow` — its pen's content description is hard-wired to `cd_tree_chat_edit`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `ChatEditorModal`, `CreateChannelModalBinding`, `treeSection` — event and binding shape; channel rows pass `onEditTapped = null`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`'s channel-list event `when`.
- `app/src/test/.../HostChannelListViewModelTest.kt` → `Fixture`, `Repo`, `seedCollidingChannels` — the two-host collision harness.
- `app/src/sharedTest/.../ChannelListScreenTest.kt` → `chatRowPencil_…`, `editChatModal_…`, `createChannelModal_…`.
- `docs/knowledge/features/system-prompt-editor.md`, `mobile-modal.md` — prompt redaction, no-session-call rule, shell error announcement.

In-flight overlap: #878 (adds `attention` beside `onEditTapped` on the tree conversation row) and #883 (MainActivity). Both additive; build through.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=500-2120 (content), shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369, row pen https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=398-7257

"Edit channel" title with close glyph over a separator; a **Channel name:** filled single-line well, a **Channel system prompt:** filled multi-line well (both `label-large` emphasized labels, `body-medium` text), an outlined `primary` **Archive channel** button (`body-large` medium), then Cancel (outlined) and OK (filled `primary`). This is exactly `ChannelFormFields` plus `EditChatModal`'s archive action inside `MobileModal`. The frame draws no reading or next-session state; each is one static line under the prompt field in the over-limit message's `supportingText` style. The row pen is the chat row's permanent pencil.

## Context

Channels rows have no edit control. This adds the pen and an Edit channel modal: rename, stored prompt, archive. Workspace is out of scope.

**Why not `SystemPromptEditor`.** It binds one repository at construction and writes through it. A host's repository is per connection: `LifecycleConnectionDriver` closes it on background and the foreground reconnect produces a new one. An operator who leaves to copy prompt text and returns would hold an editor whose every save goes to a retired repository and fails forever. Every other list write resolves `repositoryFor(serverId)` at the press (`submitChatName`); this one must too. The view model therefore reads the prompt itself (once, on the host's repository when it is available) and writes through the repository resolved at OK. It copies the editor's `changed` rule (absent prompt reads as `""`) and its redaction. `SystemPromptEditor` stays unused; the documentation stage should record why in `system-prompt-editor.md`.

## Design

### State (`ChannelListViewModel.kt`)

```kotlin
sealed interface ChannelPromptReading {
    data object Reading : ChannelPromptReading
    data object Unavailable : ChannelPromptReading      // read failed, or reply over the byte limit
    data class Read(val prompt: String?, val status: SessionPromptStatus) : ChannelPromptReading  // toString redacts prompt
}

data class ChannelEditorState(
    val serverId: String,
    val conversationId: String,
    val savedName: String,          // snapshot name, clamped; becomes the trimmed name once a rename is confirmed
    val prompt: ChannelPromptReading = ChannelPromptReading.Reading,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val archiveFailed: Boolean = false,
)
```

`HostChannelListState.channelEditor: ChannelEditorState?`. Internally the view model keeps two flows: `channelEditor: MutableStateFlow<ChannelEditorState?>` (whose `prompt` stays at its default and is never read) and `channelPrompt: MutableStateFlow<Pair<HostConversationTarget, ChannelPromptReading>?>`. The published editor is `editor.copy(prompt = reading)` only when the reading's target equals the editor's, else `Reading`, so no emission pairs one channel's modal with another's prompt. Keeping the reading out of `channelEditor` means a read landing mid-write cannot break the write's `compareAndSet`.

### Transitions

- `openChannelEditor(target)`: finds the channel in **that host's** `snapshots.value[..].channels`; unknown or chat → nothing. Cancels any previous read job, resets `channelPrompt` to `target to Reading`, publishes the state with `savedName` = the name clamped to `MAX_WORKSPACE_LABEL_CHARS`, surrogate-safe. It then launches the read job: wait for `repositoryFor(serverId)` to be non-null on the snapshots flow, `requestSystemPrompt(id)` once, and publish `Read`. A reply whose prompt does not fit `SystemPromptLimit` publishes `Unavailable`, as does a failure. No selection, navigation or session call.
- `submitChannelEdit(name, systemPrompt: String?)`: ignored with no editor or while `saving`. Blank trimmed name → rejected. The prompt draft counts only when the modal sent one **and** the published reading is `Read`. A draft over the limit → rejected. No repository → `failed`. Otherwise publish `pending`, then:
  1. rename iff `trimmed != savedName.trim()`; failure → `failed`, stop; success → `savedName = trimmed`.
  2. write the draft verbatim iff `draft != read.prompt.orEmpty()`. On failure → `failed`, and the rename is not repeated on retry.
  3. `compareAndSet(current, null)` closes the modal.
  Every terminal step is a `compareAndSet` against the state the chain published, so a dismissal or a reopen is never touched. The chain is not cancelled by a dismissal because OK was pressed.
- `archiveChannel()`: `archiveChat`'s shape: `archive(id)` on the repository resolved at the press, no name or prompt condition. Success closes the modal; failure sets `archiveFailed`.
- `dismissChannelEditor()`: nulls the editor and cancels the read job.

### UI

- `TreeConversationRow` gains `@StringRes editDescription: Int = R.string.cd_tree_chat_edit`; Channels rows pass `cd_tree_channel_edit` with `TreeChannelEditTapped(target)`.
- `ChannelFormFields` gains `promptEnabled: Boolean = true` and `promptNote: String? = null`. The note is drawn as `supportingText` when the prompt is not over the limit. Existing callers are unchanged.
- New `ui/components/EditChannelModal.kt` → `EditChannelModal(conversationId, initialName, prompt: ChannelPromptReading, onSubmit(name, systemPrompt: String?), onArchiveRequested, onDismissRequest, hostAvailable, loading, error)`:
  - Name buffer: `remember(conversationId)`, prefilled with `initialName`.
  - Prompt buffer: `remember(conversationId) { mutableStateOf<String?>(null) }`. The shown value is `typed ?: read.prompt.orEmpty()` (null while not `Read`), derived with no effect.
  - The field is disabled while the value is null. The note shows the reading line, the unavailable line, or the next-session line on `Differs`.
  - OK: `hostAvailable && name.isNotBlank() && (shown == null || fits(shown))`.
  - Archive (a private outlined action as in `EditChatModal`): `hostAvailable && !loading`.
  - Neither buffer uses `rememberSaveable`.
- `ChannelListScreen`: events `TreeChannelEditTapped(target)`, `ChannelEditSubmitted(name, systemPrompt: String?)` (with `toString` redacting the prompt), `ChannelArchiveRequested` and `ChannelEditDismissed`, plus a private `ChannelEditorModal` binding. The error is static: `edit_channel_save_failed`, or the existing `archive_failed`.
- `MainActivity`: four event arms.
- Strings: `cd_tree_channel_edit`, `edit_channel_title`, `edit_channel_archive`, `edit_channel_save_failed`, `edit_channel_prompt_reading`, `edit_channel_prompt_unavailable`, `edit_channel_prompt_next_session`.

## State + concurrency model

- All jobs run in `viewModelScope` on Main. There is one read job at a time (a `Job?` field), cancelled on reopen and on dismiss.
- A reading that lands after a close is tagged with its target and never published into another channel's modal.
- A write chain runs to completion once OK is pressed, and its terminal transitions are `compareAndSet`.

## Error handling

- Rename or prompt write fails → `failed` (static string). Archive fails → `archiveFailed`.
- A missing repository at the press → the matching flag, with nothing sent.
- A read failure or an oversize reply → `Unavailable`. The name and archive still work, and no prompt is written.
- `CancellationException` is rethrown on every catch.
- Logs are static event names: `channel_editor_opened`, `…_open_rejected code=unknown_channel`, `channel_prompt_read`, `channel_prompt_read_failed` (with `code=oversize` for an oversize reply), `channel_edit_rejected code=invalid|unavailable`, `channel_rename_failed`, `channel_prompt_write_failed`, `channel_edited`, `channel_archive_started|failed`, `channel_archived`, `channel_editor_dismissed`. No log carries a name, prompt, byte count, id or exception message.

## Testing strategy

Unit tests in `HostChannelListViewModelTest` use the two-host collision (the same id on both hosts, with different names and prompts). `Repo` gains a scripted `requestSystemPrompt` (a stored map, a gate, a failure count, a status).

- Open reads the name and prompt from the row's own host only. The reading goes `Reading` → `Read` verbatim with `Differs`. A chat target or an unknown host opens nothing. Nothing is selected, navigated or written, and no session call is made.
- A host that is disconnected at open stays `Reading` and reads once it connects. An oversize or failed reply → `Unavailable`.
- Submit sends only what changed:
  - An untouched form sends nothing and closes.
  - Name only → one rename on the own host. Prompt only → one verbatim write. Both → rename, then write.
  - A stored `null` with an empty draft writes nothing.
- Prompt write fails after a rename → `failed`, and `savedName` is updated. The retry writes only the prompt. A rename failure sets `failed`.
- The logs are content-free.
- An unread prompt never writes. While the read is pending or failed, submit renames only and archive works.
- Archive runs on its own host only and closes. The channel leaves `channels`. Delete and unarchive are never called. A failure sets `archiveFailed`.
- Rejects:
  - No editor, blank name, over-limit draft, unavailable host, second OK in flight.
  - A result landing after a dismiss cannot reopen the modal.

Screen tests in `ChannelListScreenTest` (sharedTest, Robolectric):

- The channel pen names its channel and emits `TreeChannelEditTapped` for its own host without selecting. This extends the chat-pen test.
- The modal is prefilled with the name. The prompt field is disabled with the reading line, then shows the prompt verbatim once `Read`, with the `Differs` note. OK reports the trimmed name and the prompt, the event's `toString` redacts it, and Cancel dismisses.
- An unread prompt reports `null`. Archive is enabled with a blank name and disabled while the host is down.
- Failures are static strings. OK is disabled when the host is down.

No device-only test. The live check is owned by #676.

## Open questions

- Resolved in the design: a reply over the byte limit is `Unavailable`, not truncated, so a truncated prompt can never be written back.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: `system-prompt-editor.md` should record that #667 did not adopt `SystemPromptEditor` (the per-connection repository binding, above). `mobile-modal.md` § Callers and `channel-list-screen-tree-and-controls.md` should gain the channel pen and Edit channel.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The prompt crosses in at `toSystemPromptReading` and reaches Compose only through `ChannelPromptReading.Read`, shown as `TextField` text in `EditChannelModal`, never as markup, a URL or a log line. SHOULD FIX (applied in the design): the decoder has no size bound, so `openChannelEditor`'s read job publishes a reply over `SystemPromptLimit.MAX_BYTES` as `Unavailable`. It is not rendered, and a write can never follow from it. The daemon-authored name is clamped to `MAX_WORKSPACE_LABEL_CHARS` at open, surrogate-safe.
- [Cross-host confusion] The name is read from the target host's own snapshot. The reading is tagged with its `HostConversationTarget` and published only onto the matching editor. Writes resolve `repositoryFor(editor.serverId)` at the press. Tests use colliding ids across two hosts.
- [Tokens / secrets] A prompt may hold a pasted credential. `Read.toString` and `ChannelEditSubmitted.toString` redact it. The buffers use `remember`, not `rememberSaveable`, so the prompt stays out of the saved-state Bundle. No prompt is written to disk here.
- [Logs / error text] The logs are static event names with no name, prompt, byte count, id or exception message, and a test asserts that. The error strings are static resources because the shell announces them aloud.
- [Unintended writes] A pending or failed read, or a modal that has not shown the prompt (`systemPrompt == null`), never writes. The draft is compared to the reading, so an untouched form sends nothing. This prevents wiping a stored prompt the operator never saw.
- [Concurrency] The read job is cancelled on reopen or dismiss, and its result is target-tagged. The write chain's terminal steps are `compareAndSet`, and an in-flight OK blocks a second write. A background/foreground reconnect is handled by resolving the repository at the press.
- [Session side effects] Only `requestSystemPrompt`, `setSystemPrompt`, `rename` and `archive` are called. Nothing starts or resets a session.
- [File / IPC / crypto / network] Not applicable: no new storage, intents, primitives or frames.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
