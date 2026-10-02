# #1561 — A channel's top menu offers Edit, opening Edit channel

## Files read

- `ui/conversations/list/ChannelListViewModel.kt` — `openChannelEditor`, `submitChannelEdit`, `archiveChannel`, `closeChannelEditor`, `dismissChannelEditor`, `readChannelPrompt`, the private `channelPromptFor` and `boundedName`, the `publishedChannelEditor` combine and the disconnect sweep in `init` that closes the editor through `clearUnless("channel_editor")`. This is the logic that moves.
- `ui/conversations/list/ChannelListViewModel.kt` — `ChannelEditorState`, `ChannelPromptReading`, `HostConversationTarget`. These types stay where they are, so `EditChannelModal`, `ChannelListScreen` and every test that imports them keep their imports.
- `ui/host/HostEditor.kt` — `HostEditorController`: the precedent (#751) for an editor machine pulled out of `ChannelListViewModel` into a plain class taking the owner's `viewModelScope`. The new controller follows its shape.
- `ui/conversations/components/SystemPromptEditor.kt` — `SystemPromptEditor`, the thread's other plain-class editor bound to `viewModelScope` and the thread's `repository`.
- `ui/components/EditChannelModal.kt` — `EditChannelModal`, unchanged; the thread hosts it as the list's `ChannelEditorModal` does.
- `ui/conversations/thread/ThreadOverflowMenu.kt` — `ThreadOverflowMenu`, the `mutationsSupported` block holding Rename.
- `ui/conversations/thread/ThreadViewModel.kt` — `onOverflowEvent`, `sendArchive`, `leaveForList`, `conversations`, `hostAvailable`, `state`.
- `ui/conversations/thread/ThreadUiState.kt` — `ThreadEvent`, `ThreadUiState`.
- `ui/conversations/thread/ThreadScreen.kt` — where `RenameDialog` and `SaveAsChannelDialog` are hosted.
- `androidTest/.../e2e/InteractiveStreamE2ETest.kt` — `renameOpenThread`, which `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` drives on a **channel** opened through `openRow`; it taps Rename, which a channel no longer offers.

Overlapping in-flight branches, built through with additive edits: #1497 (`ThreadViewModel.kt`, `ThreadUiState.kt`, `InteractiveStreamE2ETest.kt`), #1562 (`ThreadScreen.kt`). Neither touches the blocks this ticket edits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-5883 (menu), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=671-5415 (Edit channel, unchanged)

The thread's existing Material 3 `DropdownMenu` keeps its shape and tokens; in a channel the second item's label changes from Rename to Edit, giving Reset session, Edit, Archive, Channel info. The Edit channel modal is the existing `EditChannelModal`, reused as is.

## Context

Today only `ChannelListViewModel` hosts Edit channel, from a Channels row's pen. A sibling ticket removes those pens, so the thread's menu becomes the only way in. The ticket asks for the editor's write chain to be shared rather than copied. No decision record is needed: #751's `HostEditorController` already set the "plain class composed into each owner" pattern.

## Design

### `ChannelEditorController` (new, `ui/conversations/list/ChannelEditorController.kt`)

A plain class, one instance per owner, holding the Edit channel modal's machine exactly as the list runs it today:

```kotlin
class ChannelEditorController(
    scope: CoroutineScope,                                              // the owner's viewModelScope
    isHostLive: (serverId: String) -> Boolean,
    repositoryFor: (serverId: String) -> ConversationRepository?,       // resolved at each press
    awaitRepository: suspend (serverId: String) -> ConversationRepository, // the prompt read waits on it
    onArchived: suspend () -> Unit = {},                                // after a confirmed archive
) {
    val state: Flow<ChannelEditorState?>      // editor with its own prompt reading published
    fun open(target: HostConversationTarget, name: String?, muted: Boolean)
    fun submit(name: String, systemPrompt: String?, muted: Boolean?)
    fun archive()
    fun dismiss()
    fun closeUnless(keep: (ChannelEditorState) -> Boolean)   // the list's disconnect sweep
}
```

- `open`: rejects with `channel_editor_open_rejected code=disconnected` when the host is not live; otherwise cancels any earlier read, publishes the editor (`savedName` = `boundedName` of the non-blank name, `savedMuted` = `muted`) and reads the prompt once through `awaitRepository`. Body moved from `openChannelEditor` after its unknown-channel check.
- `submit`, `archive`, `dismiss`: bodies moved verbatim from `submitChannelEdit`, `archiveChannel`, `dismissChannelEditor`, with `hostSource.repositoryFor` replaced by `repositoryFor` and `isHostLive` by the lambda. Same `compareAndSet` transitions, same rename → mute → prompt order with confirmed steps recorded, same log events. `archive` calls `onArchived()` after its terminal `compareAndSet`.
- `closeUnless`: the list's `clearUnless("channel_editor")` plus the prompt-read cancel, same log line.
- `channelPromptFor`, `boundedName` (only the channel editor uses it) and `readChannelPrompt` move with it as private members.

### `ChannelListViewModel`

Holds `ChannelEditorController(viewModelScope, ::isHostLive, hostSource::repositoryFor, awaitRepository = snapshots.map { repositoryFor }.filterNotNull().first())`. `openChannelEditor` keeps its unknown-channel check, then calls `open` with the snapshot's name and mute flag. `submitChannelEdit`, `archiveChannel` and `dismissChannelEditor` delegate. `hostState` combines `channelEditor.state` where it combined `publishedChannelEditor`; the `init` sweep calls `closeUnless`. Public API unchanged.

### Thread

- `ThreadEvent` gains `EditChannel`, `ChannelEditSubmit(name, systemPrompt, muted)` (with a `toString` that redacts the prompt, as `SystemPromptEdit` does), `ChannelEditArchive`, `ChannelEditDismiss`.
- `ThreadUiState` gains `channelEditor: ChannelEditorState? = null` and `hostAvailable: Boolean = true` (the modal's OK/Archive gate, from the view model's existing `hostAvailable`).
- `ThreadViewModel` holds `ChannelEditorController(viewModelScope, isHostLive = { hostAvailable.value }, repositoryFor = { repository }, awaitRepository = { hostAvailable.first { it }; repository }, onArchived = { leaveForList() })`. `EditChannel` reads this conversation from `conversations.first()`; it opens only when it is promoted, passing `serverId`, `conversationId`, its `name` and `muted`. The other three events delegate. `state` gains one chained `combine` with `channelEditor.state` and `hostAvailable`.
- `ThreadOverflowMenu`: inside the `mutationsSupported` block, a promoted conversation shows Edit (`thread_overflow_edit`, sends `EditChannel`) in Rename's slot; an unpromoted one keeps Rename. So Edit is hidden whenever Rename was.
- `ThreadScreen`: hosts `EditChannelModal` while `state.channelEditor` is set, wired like the list's `ChannelEditorModal` (same error strings, `hostAvailable = state.hostAvailable`, `loading = saving`).
- A new name reaches the thread title through `conversations`, which the repository re-emits on the confirmed rename; nothing is patched.

## State and concurrency model

The controller's two `MutableStateFlow`s and its read `Job` are as the list had them; every launch is in the owner's `viewModelScope`, so clearing the thread or the list cancels the read and any write chain. Calls arrive on Main from event handlers. The thread's `hostAvailable` is already an eager `StateFlow`. The thread does not close the modal on disconnect (the list's #1336 rule is about its own tree); the modal's controls disable through `hostAvailable`, and a press while down closes it as on the list.

## Error handling

Unchanged from the list: a failed rename/mute/prompt write sets `failed`, a failed archive `archiveFailed`, both resolved to static strings on screen; nothing daemon-authored is logged or shown. A thread whose conversation is not a channel (or not yet listed) ignores `EditChannel` with `channel_editor_open_rejected code=unknown_channel`.

## Testing strategy

- `HostChannelListViewModelTest` and `ChannelListScreenTest` run unchanged as the regression proof for the move.
- New `ThreadViewModelChannelEditTest` (unit, delegating fake over `FakeConversationRepository`): Edit opens with the seeded channel's name, stored prompt and mute flag; OK with only a new name sends one rename, closes, and the thread's `displayName` shows the new name; OK with nothing changed sends nothing; the modal's Archive archives and emits `PopBack`; dismiss sends nothing; Edit on a discussion opens nothing; the submit event's `toString` never prints the prompt.
- `ThreadOverflowMenuTest` (shared): a channel shows Edit and no Rename and Edit sends `EditChannel`; a discussion shows Rename and no Edit; `mutationsSupported = false` hides Edit.
- `ThreadScreenOverflowTest` (shared, a promoted base state): its Rename assertions become Edit; the modal shows while `channelEditor` is set, its Cancel sends `ChannelEditDismiss`, its OK sends `ChannelEditSubmit`.
- Rung 3: `renameOpenThread` in `InteractiveStreamE2ETest` learns the channel path (menu Edit → name field by `CHANNEL_NAME_FIELD_TAG` → OK), so `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` drives this ticket's flow against a real daemon. Its other callers rename chats and keep the Rename path. These go under `## Live tests`. No rung-4 twin: no turn to hold.

## Open Questions

- Should the thread close the modal on host disconnect like the list? Planned no (see State); revisit only if review asks.
