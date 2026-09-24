# Thread screen — composer drafts and attachments

Split out of [Thread screen](thread-screen.md) on 2026-09-24 to keep that document under the 50000-byte size cap the docs guard enforces. Both sections below moved here verbatim and kept their heading, so their anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### Composer draft ownership

Unsent composer text is owned by `ComposerDraftStore` ([#789](https://github.com/pyrycode/pyrycode-mobile/issues/789)) — an app-scoped Koin `single { ComposerDraftStore() }` in `di/AppModule.kt`, one process-wide instance reaching every thread destination, the same per-call shape `preferences` already used. Before this, the self-owning `ThreadInputBar` overload held its text in `rememberSaveable`, so a draft's lifetime was the thread destination's composition: navigating away lost it, and the next chat opened in that slot inherited whatever that composition state happened to hold. The store is keyed by the `(serverId, conversationId)` pair, never the conversation id alone — ids are host-local (see [Host identity and snapshots](dependency-injection-host-conversation-source.md#host-identity-and-snapshots)), so a bare-id key would leak one host's unsent text into another host's composer. In-memory only: nothing is written to disk or `SavedStateHandle`, so a draft does not survive process death, but it does survive navigation, configuration change, and the lifecycle driver's foreground/background connection cycling, since the store holds no connection and no disk handle. [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) held [`ThreadInputBar`'s field itself](thread-input-bar.md#draft-binding--cursor-at-end-undo-and-redo-885-934) to the same heap-only rule when it migrated to `TextFieldState` — see that document for why `rememberTextFieldState` would have broken this contract.

**Created:** `ThreadDestinationFactory.thread` takes the store as a third constructor argument (`.thread(get(), get(), get())`) and passes it to `ThreadViewModel`, which also reads `serverId` off the same `SavedStateHandle` the factory reads it from, beside the existing `conversationId` read. **Restored:** `ThreadViewModel.draft: StateFlow<String>` maps `draftStore.drafts` down to this pair's entry, `stateIn`'d on `viewModelScope` with `SharingStarted.Eagerly` and seeded from `draftStore.draftFor(serverId, conversationId)` so the initial value and the first emission can never disagree — this is what makes returning to a chat show its exact prior text, whitespace included. `MainActivity`'s `PyryNavHost` collects it with `collectAsStateWithLifecycle()` alongside the destination's other flows and binds it to `ThreadScreen`'s `draft` parameter; `onDraftChange = vm::onDraftChange` closes the loop, calling `draftStore.setDraft(serverId, conversationId, text)` on every keystroke. Both `ThreadScreen` parameters default to `""` / `{}` so the ~30 existing `androidTest` call sites, none of which type into the composer, keep rendering an empty composer unchanged. The self-owning `ThreadInputBar` overload is deleted; the stateless overload (`text`, `onTextChange`, `onSend`) is the only one left, and `ThreadScreen`'s `bottomBar` mount now passes `text = draft`, `onTextChange = onDraftChange`, `onSend = { onSendMessage(draft) }`.

**Cleared:** only from inside `ThreadViewModel.sendMessage`'s `launchGuardedRepoCall` block, after `repository.sendMessage` returns — "the suspend call returned" is "the daemon accepted it," the same success-only-continuation shape `sendArchive` already used. The three failure types the guard swallows (`RelayErrorException`, a not-connected `IllegalStateException`, an unwired `UnsupportedOperationException`) all skip the clear, so a refused send leaves the text in place instead of silently eating it as the old tap-to-clear behavior did. The clear is additionally guarded on equality — `if (draftStore.draftFor(serverId, conversationId) == text) onDraftChange("")` — reading `ComposerDraftStore` directly rather than the exposed `draft` flow. An earlier version of this design compared against `draft` and was wrong: `draft` is a *derived* flow, so an edit made from inside an already-running coroutine does not reach it until that dispatch yields, and the guard cleared text it had never seen (caught by `sendMessage_whenTheDraftChangedInFlight_leavesTheNewTextAlone`, see [thread-screen-testing.md](thread-screen-testing.md)). The rule going forward: expose the derived flow for rendering, read the store's own `MutableStateFlow.value` for deciding. `ThreadEvent.NewSession` (Reset session) reads and writes nothing on the store, so it leaves a conversation's draft untouched.

**Evicted, not just cleared, when the host or the conversation goes away ([#790](https://github.com/pyrycode/pyrycode-mobile/issues/790)).** Nothing in the shapes above removes a draft except an accepted send, so a draft otherwise outlives the thing it was written for: a `serverId` is stable across a re-pair, so unpairing and re-pairing the same server resurfaces text typed before the unpair, and a deleted conversation's draft would sit in the map for the life of the process. Two evictions close that:

- `ComposerDraftStore.clearHost(serverId)` drops that host's whole bucket — `_drafts.update { it - serverId }`, a no-op on an unknown or already-empty id. It runs from `ObservablePairedServerStore.remove`, *after* `delegate.remove` and the revision bump, as the first step of [`forgetRemovedHost`](conversation-cache.md#removal-on-unpair--forgetremovedhost), the required `onHostRemoved: suspend (String) -> Unit` constructor parameter — not from `HostEditorController.confirmUnpair`, even though `confirmUnpair` already carries the matching "only after removal succeeds" idiom for `AppPreferences.removeDefaultWorkspace`. Neither owner of the unpair gesture (`ChannelListViewModel`, `SettingsViewModel`) owns a composer, so a controller-side hook would have threaded a `ComposerDraftStore` dependency through both purely to reach a nested controller; the store is also the seam the paired-server-store overview already requires every app mutation to go through, so a future removal path inherits the eviction without having to remember it. `forgetRemovedHost`'s second step removes the host's cached conversation content ([#798](https://github.com/pyrycode/pyrycode-mobile/issues/798)) — same hook, same ordering guarantee, no separate wiring. See [paired-server-store.md § Wiring & usage](paired-server-store.md#wiring--usage) for the hook's contract (required, must not throw, must suspend rather than block) and why `save`/`setDisplayName` deliberately do not evict.
- `ComposerDraftStore.clearConversation(serverId, conversationId)` drops one pair — `setDraft(serverId, conversationId, "")` under a name that says "this chat is gone" rather than "edited to empty". It runs inside `ThreadEvent.DeleteConfirm`'s `launchGuardedRepoCall` block, after `repository.delete` returns and before the `PopBack` send — the same success-only position `sendMessage`'s own clear occupies, so a failed delete (any of the three throwables the guard swallows) leaves the draft for a conversation that still exists. Keyed by the constructor's own `serverId`/`conversationId` — the pair `onDraftChange` wrote under — never re-derived from `state.value`. That same `repository.delete` call is where the conversation's cached content leaves too, once the destination's repository is a [`CachingConversationRepository`](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798) — a separate override on the repository, not a second draft-store call.

Both draft evictions are synchronous `MutableStateFlow.update` calls with no coroutine of their own; `ThreadViewModel.draft` picks up an eviction the same way it picks up any other emission, and a `StateFlow` dropping equal consecutive values means evicting one host's bucket cannot recompose another host's composer. No log line on either path carries draft text — a draft is private message content, and neither `ObservablePairedServerStore` nor `ComposerDraftStore` is a `data class`, so a bound `onHostRemoved` receiver can't render into a crash trace via a generated `toString()`.

### Composer pending attachments

Beside the text map, `ComposerDraftStore` keeps a second `(serverId, conversationId)`-keyed map of
`PendingAttachment` entries ([#932](https://github.com/pyrycode/pyrycode-mobile/issues/932)) — a content
URI plus display name, MIME type, size and, once uploaded, an acknowledged id. Same nesting, same
"empty entries and buckets are absent" rule, same eviction: `clearHost` / `clearConversation` drop a
pair's attachments together with its text (see § Composer draft ownership above).
`ThreadViewModel.pendingAttachments` mirrors `draft`'s shape — mapped from the store, seeded
synchronously, `Eagerly`. `addAttachment` refuses an entry over `AttachmentUploadLimit.MAX_BYTES` or one
that would push the pair past `MessageAttachmentIds.MAX` (32), checked inside the store's own
`update {}` so two concurrent adds can't both pass at 31.

`sendMessage` snapshots the pair's attachment list at tap time. An entry that already carries an
`attachmentId` is skipped; the rest are read through `AttachmentReader` — bytes only at send time, one
file's at once, the store itself never holds file bytes — and uploaded via
[`ConversationRepository.uploadAttachment`](attachment-upload.md), in order. Any read or upload failure
stops the send: text and every entry stay, and ids already acknowledged are kept so a retry does not
re-upload them. On success `uploadAttachment`'s ids are named to `sendMessage`, then text and the sent
snapshot's attachments clear together — an entry added after the snapshot survives, the same
"the message is what was tapped" guarantee the text draft's in-flight guard already gives.

**Sent originals (#984).** Beside the text and pending-attachment maps, `ComposerDraftStore` keeps a
third, unexposed `(serverId, conversationId)`-keyed map, attachment id to the content URI it was uploaded
from: `fun recordSentOriginals(serverId, conversationId, originals: Map<String, String>)` and
`fun sentOriginal(serverId, conversationId, attachmentId): String?`. `ThreadViewModel.sendWithAttachments`
calls `recordSentOriginals` once every upload in the snapshot has succeeded and **before**
`repository.sendMessage` — the confirmed row can render while that call is still suspended, so the
originals must already be there when it does; an id recorded for a send that then fails is harmless,
since a retry reuses the same ids. Not a `StateFlow`: nothing renders this map, and the thread reads one
entry at most once per attachment it shows (see [MessageBubble — attachment slot § Load
lifecycle](message-bubble-attachment-slot.md#load-lifecycle-since-984)). Same rules as the other two maps — in memory
only, never logged, `clearHost` / `clearConversation` drop it with the text and the pending attachments.
A picker grant does not outlive the process, so neither does this entry; "sent in this app session," not
"sent, ever," is the guarantee — reopening the app after a background/foreground cycle keeps it (the
store is app-scoped), a process death does not.

**Reading a content URI is a trust boundary.** `ContentResolver.openInputStream` opens `file://`,
`android.resource://` and this app's own non-exported providers with the app's identity, so a URI handed
back by another app's picker could otherwise make the app upload its own private files.
`ContentResolverAttachmentReader.isForeignContentUri` refuses everything but a `content` URI whose
provider authority is neither this app's package nor a dotted sub-authority of it, checked before the
resolver is touched. `Uri.getAuthority()` keeps a `userId@` prefix — the form a pick from another Android
profile carries — and the resolver strips that prefix before choosing a provider, so comparing the raw
authority against the package name let `content://0@de.pyryco.mobile.fileprovider/…` through as
"foreign". The guard compares `authority.substringAfterLast('@')` instead, which still accepts a
genuinely foreign authority behind a user-id prefix. [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934)'s
paste path reuses this same function on a pasted clip item's URI before anything is queried — see
[Thread input bar § Image paste into the field](thread-input-bar.md#image-paste-into-the-field-934).

`AttachmentReader` is bound as a `Lazy<AttachmentReader>` constructor parameter on
`ThreadDestinationFactory`, not resolved eagerly — the real reader needs `androidContext()`, and several
test containers build the factory without one. Resolving lazily means only a container that actually
builds a thread destination pays for it; one that does (`NotificationTapNavigationTest`,
`LiteralScreenNavigationTest`) must bind an inert `AttachmentReader { AttachmentRead.Unreadable }`, the
posture `RelayConnectionFactoryTest` already used for its own inert override.

**`AttachmentReader.canRead(uri): Boolean` (#984)** answers whether a grant still lets the app open `uri`,
without reading any bytes — the thread shows a sent file's own original ([Sent
originals](#composer-pending-attachments) above) only while this is true. A default method on the `fun
interface` (`= false`), so the single-method-lambda call sites in tests stay valid and a reader that
cannot tell sends the thread to retrieval instead of hanging on `Loading`.
`ContentResolverAttachmentReader.canRead` applies the same `isForeignContentUri` refusal `read` does, then
opens and immediately closes an `AssetFileDescriptor`. **A coroutine timeout around the open does not
bound it**: `withContext(io) { resolver.openInputStream(uri) }` blocks in a Binder call that never observes
coroutine cancellation, so `withTimeoutOrNull` around it still waits for the provider. The bound is a
`CancellationSignal` passed into `openAssetFileDescriptor(uri, "r", signal)` — the same open `read` performs
— cancelled by a deadline job after `CAN_READ_TIMEOUT = 5.seconds`, or at once if the caller itself is
cancelled. Every exception, including the `OperationCanceledException` the signal raises, is dropped unread
and answers `false`, the same posture as `read`'s own failure handling.

**The picker and the strip ([#933](https://github.com/pyrycode/pyrycode-mobile/issues/933)).** The
paperclip opens `rememberAttachmentPicker`'s `OpenMultipleDocuments()` launcher; a cancel calls nothing,
otherwise `describePickedAttachment` drops any refused URI and hands the rest to `addPickedAttachments`,
which adds each via `addAttachment` and reports `TOO_LARGE`/`TOO_MANY` refusals as one counts-only
notice. `ComposerAttachmentStrip` renders the list as a `LazyRow` between the status area and input field
when non-empty, thumbnailing an image or falling back to the file tile. `attachmentsSending` blocks a
second `sendMessage` and hides remove controls while `sendWithAttachments` runs.

**A paste joins the same path ([#934](https://github.com/pyrycode/pyrycode-mobile/issues/934)).** Pasting an
image into the composer, or a keyboard's image insert, hands the field image content URIs through
[`Modifier.contentReceiver`](thread-input-bar.md#image-paste-into-the-field-934) instead of the picker's
document-picker launcher, but from `ThreadScreen` down it is the identical sink: `rememberPastedImageReceiver`
describes the URIs off the main thread and calls `onAttachmentsPicked` — the same callback the picker uses —
so the size and count refusals, the snackbar, and `ComposerAttachmentStrip`'s rendering are unchanged and
`MainActivity` does not change. Pasted text still goes into the field as text; only a `content:` URI under a
foreign provider and an `image/*` clip type is diverted, and the provider's own `getType` is re-checked
before the item is kept. A clip item the receiver accepts but whose provider later disagrees on type is
dropped, not returned to the field as text — see [Thread input bar § Image paste into the field](thread-input-bar.md#image-paste-into-the-field-934).
