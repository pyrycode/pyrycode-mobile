# #827 — Rename a chat from its row in the mobile list

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel`, `HostChannelListState`, `HostConversationTarget` — the owner of the new editor state; `hostState`'s `combine` is already at five flows.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController`, `HostEditorState`, `HostEditorModal` — the model for the state shape: ids and display text only, `saving`/`failed` flags, `compareAndSet` terminal transitions, string resolution on screen.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `EditChatModal` — #826's stateless modal. It owns the name buffer, keyed on `conversationId`, clamps the pre-fill, trims on OK, and disables OK on `!hostAvailable || blank`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow`, `TreeRowControl`, `boundedRowText` — the row gains the pen; `TreeRowControl` stays its own semantics node inside the row's `selectable`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `treeSection`, `ChannelListScreen` — the events, the per-section row wiring and the modal binding.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` destination's `onEvent` dispatch.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource.repositoryFor`, `HostConversationSnapshot` — per-host repository lookup, and `snapshots` as a synchronous `StateFlow` read.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.rename`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`, `Host`, `Repo` — two hosts, `"Host"` and `"host"`, each with its own `Repo`; conversation ids can already collide across them.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `setTree`, `entry` — the stateless-screen harness.
- `docs/specs/architecture/744-host-row-edit-and-rename.md`, `docs/specs/architecture/826-edit-chat-modal.md` — the analogue and the modal's caller obligations: keep `error` generic, map failures to a string resource, never the daemon's message or the chat name.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=398-7257 (row), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2320 (Edit chat content, shipped by #826), shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The conversation row is an idle ring dot, then the name in `bodySmall`. Its hover state adds a pencil at the row's trailing edge, tinted in the primary family. The phone has no hover, so the pencil is drawn permanently on Chats rows, in a 48dp touch target, as the host row's pencil is (#744). The modal's look is #826's and does not change here.

## Context

Chats rows can only open their thread today. This adds a pen to each Chats row that opens #826's Edit chat modal on that row's own host and conversation, and wires OK to that host's `ConversationRepository.rename`. Channels rows get no pen, because editing a channel is #667. Archive chat stays unwired until #828.

**File overlap (§ A2).** Two in-flight branches touch files this ticket touches, in hunks far from this ticket's hunks, so no blocker is set:

- `feature/841` edits `RelayLinkStatus.isDisconnected()` in `ConversationTreeRows.kt` and adds `RelayLinkStatus.PairingRejected`. This ticket edits only `TreeConversationRow`. The availability check below compares with `==` and does not use an exhaustive `when` over `RelayLinkStatus`, so the new variant cannot break it once both branches merge.
- `feature/803` edits the thread destination in `MainActivity.kt` and the thread block of `strings.xml`. This ticket edits the channel-list destination, and it adds strings next to the `edit_chat_*` block.

## Design

### State: `ChatEditorState` (in `ChannelListViewModel.kt`)

```kotlin
data class ChatEditorState(
    val serverId: String,
    val conversationId: String,
    val initialName: String,      // "" for a chat with no name; raw, EditChatModal clamps it
    val saving: Boolean = false,
    val failed: Boolean = false,
)
```

This holds ids and display text only, like `HostEditorState`. `HostChannelListState` gains `chatEditor: ChatEditorState? = null`. It also gains the derived member `fun isHostConnected(serverId: String): Boolean`: true exactly when `hosts` holds that `serverId` and both legs of its snapshot's `connectionStatus` are `Connected` (compared with `==`). The screen passes that result to the modal's `hostAvailable`. Because it is derived from the same snapshot flow the rows draw from, it tracks disconnects and reconnects live. It never changes `chatEditor`, so the modal and its typed text survive both.

### View model: `ChannelListViewModel`

The editor state lives in a private `MutableStateFlow<ChatEditorState?>`. `hostState`'s `combine` is at five flows, the typed overload's maximum, so the two editor flows are paired first with an inner `combine(hostEditor.state, chatEditor, ::Pair)`.

- `openChatEditor(target: HostConversationTarget)` looks up the conversation synchronously in `hostSource.snapshots.value`, matching the host by `serverId` and then the conversation by id among that host's `chats`. If it is not found, it logs a reject and publishes nothing. Otherwise it publishes `ChatEditorState(serverId, conversationId, name?.takeIf(isNotBlank).orEmpty())`. It does not touch `lastOpenedTarget` and sends no navigation event. The event carries ids only: the name comes from this host's own snapshot, never from display text on the row.
- `submitChatName(name: String)`: there must be an open editor that is not saving. The trim is done here too, so the method's contract holds for any caller. A blank name is ignored, since the daemon rejects it and the modal disables OK for it. The method resolves `hostSource.repositoryFor(target.serverId)` **at the press**. It never uses the selected host or `ThreadDestinationFactory`. With no repository, it publishes `failed = true` and sends nothing. Otherwise it publishes `pending = saving`, and launches in `viewModelScope` `repo.rename(target.conversationId, trimmed)`. Success does `compareAndSet(pending, null)`. An `Exception` other than `CancellationException` does `compareAndSet(pending, pending.copy(saving = false, failed = true))`.
- `dismissChatEditor()` publishes `null` without a guard, as `HostEditorController.dismiss` does.

The row shows the new name after success because the repository's own conversation stream re-emits. This view model writes no local copy of the name.

### Row: `TreeConversationRow`

It gains `onEditTapped: (() -> Unit)? = null`. When the callback is non-null, a `TreeRowControl(Icons.Filled.Edit, contentDescription = cd_tree_chat_edit(bounded name))` sits at the trailing edge. The name `Text` then takes `weight(1f)`, so the pen is pushed to the end and a long name ellipsizes before it. When the callback is null, the row is unchanged. The name bound is the existing `boundedRowText`, which clamps to `MAX_WORKSPACE_LABEL_CHARS`, and it is computed once for both the text and the label. `TreeRowControl` already stays its own node with its own click inside the row's `selectable`, so a pen tap never reaches the row's `onClick`.

### Screen: `ChannelListScreen`

- New events: `TreeChatEditTapped(target: HostConversationTarget)`, `ChatEditNameSubmitted(name: String)` (no ids, for the reason `HostEditNameSubmitted` has none), and `ChatEditDismissed`.
- In `treeSection`, only the Chats section passes `onEditTapped = { onEvent(TreeChatEditTapped(target)) }`, where `target` is the row's own. Channels passes `null`.
- A private `ChatEditorModal` binding runs after `HostEditorModal` and renders only while `chatEditor != null`. It sets `hostAvailable = hostState.isHostConnected(serverId)`, `loading = saving`, and `error = stringResource(edit_chat_save_failed)` when `failed`. `onArchiveRequested = {}` stays unwired until #828.
- `MainActivity` gets three dispatch lines that go to `openChatEditor`, `submitChatName` and `dismissChatEditor`.

### Strings

- `cd_tree_chat_edit`: "Edit chat %1$s"
- `edit_chat_save_failed`: "Couldn't rename the chat. Try again."

The second string is generic and names only the failed operation.

## State + concurrency model

All transitions run on Main from tap dispatch. One `viewModelScope` launch runs per submit, and clearing the view model cancels it. The `saving` guard blocks a second submit. `compareAndSet` against the published pending state means that a write finishing after a dismissal, or after a reopen, can neither resurrect nor overwrite the modal. The repository is resolved once, at the press. If the host drops mid-write, `rename` throws and the modal shows the failure.

## Error handling

- Unknown target at open: reject log, nothing opens.
- No repository at OK: `failed`.
- `rename` throws: `failed`, the typed name stays in the modal (the same state instance family stays published, and the buffer is keyed on `conversationId`), and the stored name is unchanged.
- Logs are content-free, through `RelayLog.d`: `chat_editor_opened`, `chat_editor_open_rejected code=unknown_chat`, `chat_rename_started`, `chat_rename_rejected code=unavailable`, `chat_rename_failed`, `chat_renamed`, `chat_editor_dismissed`. No log carries an id, a name, or the exception's message.

## Testing strategy

**Unit, `HostChannelListViewModelTest`.** The test `Repo` gains a recording `rename` that can fail or be gated. Both fixture hosts get a chat with the **same id**, `"same"`, under different names.

- Open on `("Host","same")` pre-fills Host's name, and on `("host","same")` pre-fills host's. A nameless chat pre-fills `""`. An unknown id opens nothing. Opening changes neither `selected` nor the navigation channel.
- Submit with `"  New  "` renames only on `"Host"`'s repo with `"New"` and closes the editor. `"host"`'s same-id chat is never renamed, and after the repo's stream re-emits the projected row carries `"New"`.
- Availability: set the host's status to disconnected, and `isHostConnected` turns false while the editor stays the same. Reconnect, and it turns true again. Submitting while `repositoryFor` is null sets `failed` and sends nothing.
- Failure: `rename` throws with a message. The editor stays open with `failed`, `saving` is false, the stored name is unchanged, and no log contains the message, the name or the ids. A retry succeeds and closes. A gated write that completes after a dismissal does not reopen the editor. Dismiss sends nothing.

**Compose, `ChannelListScreenTest`.**

- Every Chats row has a pen named "Edit chat <name>", and Channels rows have none. Tapping the pen emits exactly `TreeChatEditTapped` with the row's own target, and emits no `TreeRowTapped`.
- With an open `chatEditor`, the field is pre-filled. OK emits `ChatEditNameSubmitted` with the trimmed name, and Cancel emits `ChatEditDismissed`. `failed` shows the generic string. The harness gets a mutable host state: flipping the host's status to disconnected disables OK, and flipping it back re-enables OK with the typed text intact.

**Real-Claude e2e.** The ticket assigns the live rename round trip to #676, the rung-3 follow-up in the #481/#482 shape. This ticket adds no scenario.

## Open questions

- Should the rename also clamp the typed name at the write, as the host editor does? No. The AC says "the trimmed name". The daemon owns chat names, and the transport's frame cap bounds the send. #826's security review settled this.
- Test tag for the pen: none. The Compose tests find the pen by its accessible name. #676 can add a tag if the live suite needs one.

## Documentation handoff

This ticket's body names no documentation requirement. Pending for the documentation stage: `docs/knowledge/features/channel-list-screen-tree-and-controls.md` should list the Chats row's pen, and `docs/knowledge/features/mobile-modal.md` § Callers should name the channel list as `EditChatModal`'s first caller.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The chat name is daemon-authored. It reaches only a `Text`, clamped by `boundedRowText`, and a content description, formatted from the same bounded value in `TreeConversationRow`. The modal's pre-fill is clamped by `EditChatModal` itself, surrogate-safe. The raw name sits in `ChatEditorState` only as long as the snapshot that already holds it does. It is never put in a log, a key, a URL or a test tag.
- [Tokens] No findings. The design touches no pairing record and no credential. `ChatEditorState` holds two ids, a name and two flags.
- [File / storage] No findings. No file I/O. The rename goes through the repository's existing wire path.
- [Android surface] No findings. No new intent, deep link, provider or WebView.
- [Crypto] No findings. The design adds no crypto. The write travels inside the existing Noise session.
- [Network & I/O] No findings. A rename to the wrong host is the real risk. The repository is resolved from the editor's own `serverId` at the press, never from the selected-host adapter. The unit test gives two hosts a conversation with the same id and asserts that the other host's is never renamed.
- [Error messages, logs] No findings. A failure maps to one static string resource resolved on screen. The exception's message never reaches state or a log. The unit test asserts that logs contain no name, no id and no exception message.
- [Concurrency] No findings. There is one `viewModelScope` launch, a `saving` guard, and `compareAndSet` terminal transitions, so a late completion cannot reopen a dismissed modal or overwrite a newer one. The test gates the write to prove this.
- [Threat model] Hostile daemon frame: covered by the text-only bounded render. A hostile relay can drop the write, which surfaces as a generic failure. A leak through an accessibility service of a chat name in a content description is OUT OF SCOPE. The name is not a secret, and the row already renders it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
