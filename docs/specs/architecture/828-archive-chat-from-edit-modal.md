# #828 — Archive a chat from the mobile Edit chat modal

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChatEditorState`, `ChannelListViewModel.submitChatName`, `dismissChatEditor`, `HostChannelListState.isHostConnected` — #827's rename path. Archive reuses its target resolution, its `saving` guard and its `compareAndSet` terminal transitions.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `EditChatModal`, `ArchiveAction` — #826 already gates Archive on `hostAvailable && !loading` and ignores the name field. It reports the intent through `onArchiveRequested` and does not close itself. No change here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `ChatEditorModal` — the event set and the modal binding, whose `onArchiveRequested = {}` this ticket wires.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` destination's `onEvent` dispatch.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.archive`. `RemoteConversationRepository.archive` → `sendArchiveToggle` throws `RelayErrorException` on an error reply and `IllegalStateException` when not connected.
- `app/src/main/res/values/strings.xml` → `archive_failed` ("Couldn't archive this conversation. Try again."), the thread's generic archive failure. This ticket reuses it and adds no string.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `seedCollidingChats`, `Fixture`, `Host`, `Repo` — two hosts that both hold a chat with the id `"same"`. `Repo.observeConversations` already filters `archived`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `openChat`, `chatHost`, the `editChatModal_*` tests — the stateless harness for the modal binding.
- `docs/specs/architecture/827-rename-chat-from-tree-row.md` — the analogue, including its security review.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2320 (Edit chat content), shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The Edit Chat dialog contains a "Channel name:" label over a filled field. Below it is an outlined "Archive chat" button in the primary colour, then Cancel (outlined) and OK (filled). #826 shipped this look. This ticket only wires the Archive button, so nothing visual changes. The modal already disables the button through `EditChatModal`.

## Context

Archive chat is drawn but does nothing (#827 left `onArchiveRequested = {}`). Desktop's `EditChatDialogView` archives with no confirmation. It disables the action only while the host is unavailable, because the Archive screen's Restore brings an archived chat back. Mobile has a Restore path for each host behind `Routes.archive(serverId)`.

**File overlap (§ A2).** `origin/feature/803` touches `MainActivity.kt` (the thread destination) and `strings.xml` (the thread block). This ticket adds one dispatch line in the channel-list destination, next to #827's three lines, and does not edit `strings.xml`. The hunks are far apart, as they were for #827, so no blocker is set.

## Design

### State: `ChatEditorState`

It gains `archiveFailed: Boolean = false`. `failed` keeps meaning that the rename failed. `saving` now means that a write is in flight, whether it is a rename or an archive. The KDoc says so. One in-flight flag disables both actions, because the modal's `loading` gates both OK and Archive. Each action clears both failure flags when it starts, so at most one failure is shown.

### View model: `ChannelListViewModel.archiveChat()`

- It needs an open editor that is not `saving`. Otherwise it returns. It takes no name, because archiving never depends on the field.
- It resolves `hostSource.repositoryFor(target.serverId)` **at the press**, from the editor's own host. It never reads the selected host. With no repository it logs `chat_archive_rejected code=unavailable`, publishes `archiveFailed = true`, and sends nothing.
- Otherwise it publishes `pending = target.copy(saving = true, failed = false, archiveFailed = false)`. It then launches `live.archive(target.conversationId)` in `viewModelScope`. Success runs `compareAndSet(pending, null)`. An `Exception` that is not a `CancellationException` runs `compareAndSet(pending, pending.copy(saving = false, archiveFailed = true))`. It never calls `rename` or `delete`.
- The chat leaves the Chats section because the host's own conversation stream re-emits it with `archived = true`. The view model patches nothing. #827's rename worked the same way.
- `submitChatName` also clears `archiveFailed` in its pending and unavailable states, so a failed archive followed by a rename shows only the rename's outcome.

### Screen

- New event: `ChatArchiveRequested` (a `data object` with no ids, for the same reason `ChatEditNameSubmitted` has none).
- `ChatEditorModal` sets `onArchiveRequested = { onEvent(ChatArchiveRequested) }`. The error is chosen as follows: `archiveFailed` → `stringResource(R.string.archive_failed)`, else `failed` → `edit_chat_save_failed`, else null. `loading = editor.saving` is unchanged.
- `MainActivity` gets one dispatch line: `ChatArchiveRequested -> vm.archiveChat()`.

## State + concurrency model

Every transition runs on Main from a tap dispatch. Each press gets one `viewModelScope` launch, which clearing the view model cancels. The `saving` guard blocks a second archive and a concurrent rename. A late result, arriving after a dismissal or a reopen, cannot resurrect or overwrite the modal, because each result is applied with `compareAndSet` against the published pending state. The modal buffer is keyed on `conversationId`, and a failure keeps the same editor instance, so the typed name survives a failure.

## Error handling

- No repository at the press → `archiveFailed`, nothing sent.
- `archive` throws (`RelayErrorException`, `IllegalStateException`, anything else) → `archiveFailed`. The chat stays active, and the message is never read.
- The logs are content-free and use `RelayLog.d`: `chat_archive_started`, `chat_archive_rejected code=unavailable`, `chat_archive_failed`, `chat_archived`. No log line carries an id, a name or an exception message.

## Testing strategy

**Unit, `HostChannelListViewModelTest`.** `Repo` gains a recording `archive` that sets `archived = true` on its own rows, along with `archiveGate` and the existing `failure`. `delete` and `unarchive` are overridden to record calls, so the tests can assert that nothing else was sent.

- Archive on `("Host","same")` with no editor open sends nothing. With the editor open it archives only on Host's repo, sends no rename and no delete, and closes the editor. After the stream re-emits, the chat is gone from Host's `chats`, and Host's repo `Archived` filter holds it. host's chat with the same `"same"` id stays unarchived.
- The unavailable host (`available = false`): archive sets `archiveFailed` and sends nothing.
- Failure (`RelayErrorException` with a secret message): the editor stays open with `archiveFailed`, `!saving` and `!failed`, the row is still active, and no log line contains the message, an id or a name. `IllegalStateException` gets the same treatment. A retry succeeds and closes the editor. A gated archive that finishes after a dismissal leaves the editor closed. A second archive and a rename during the gate are both ignored.

**Compose, `ChannelListScreenTest`.**

- Tapping "Archive chat" emits exactly `ChatArchiveRequested`, even with a blank field.
- `archiveFailed` shows the generic `archive_failed` string, not the rename string, and the typed name stays.
- The host is down → Archive is disabled. `saving` → Archive is disabled.

**Real-Claude e2e.** The ticket gives the live archive round trip to #676. This ticket adds no scenario.

## Open questions

- Reuse `archive_failed` or add an `edit_chat_archive_failed`? Reuse it. It is generic, it is the thread's own archive copy, and it keeps `strings.xml` out of the #803 overlap.
- Should an archived chat that was the selected row clear `lastOpenedTarget`? No. The AC does not ask for it, and a highlight key that matches no row draws nothing.

## Documentation handoff

The ticket body names no documentation requirement. This handoff is pending for the documentation stage. `docs/knowledge/features/mobile-modal.md` § Callers should record that the channel list wires `EditChatModal`'s Archive action. `docs/knowledge/features/channel-list-screen-tree-and-controls.md` should note that archiving from the Chats row's modal happens without confirmation.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The design adds no inbound data. The archive reply is decoded at the existing `ConversationResponseDto` boundary inside `sendArchiveToggle`. The UI only sees the re-emitted list, which rows already render as bounded text.
- [Tokens] No findings. The design touches no pairing record or credential. `ChatEditorState` gains one boolean.
- [File / storage] No findings. There is no file I/O.
- [Android surface] No findings. The design adds no intent, deep link, provider or WebView.
- [Crypto] No findings. The archive travels inside the existing Noise session.
- [Network & I/O] No findings. The real risk is archiving on the wrong host, since ids are host-local. The repository is resolved from the editor's own `serverId` at the press, never from the selected host. The unit test uses two hosts with a colliding `"same"` id and asserts that the other host is never archived. The design sends no rename or delete, and the test's recording `Repo` asserts that too.
- [Error messages, logs] No findings. A failure becomes a flag, which resolves to one static string resource on screen. The exception's message never reaches state or a log. The test gives the failure a secret message and asserts that no log line contains it, an id or a name.
- [Concurrency] No findings. There is one launch, the `saving` guard is shared with rename, and the terminal transitions use `compareAndSet`. The gated test proves that a late result neither reopens the modal nor overwrites it.
- [Threat model] A hostile relay can drop or delay the archive. That surfaces as a generic failure or a spinner, and the operator can dismiss it. A hostile daemon can refuse the archive, which gives the same generic failure. Archiving takes no confirmation, by the ticket's design (desktop parity). Restore undoes it, so an accidental tap loses nothing. OUT OF SCOPE: whether the daemon enforces archive authorization per device, which is a daemon-side concern the protocol document owns.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
