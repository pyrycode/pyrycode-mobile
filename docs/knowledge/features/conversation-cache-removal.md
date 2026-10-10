# Conversation cache — Removal on unpair — forgetRemovedHost

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Removal on unpair — `forgetRemovedHost`

[#798](../../specs/architecture/798-clear-cache-on-removal.md) wires `removeHost` to the one place a
pairing is actually removed: `internal fun forgetRemovedHost(drafts: ComposerDraftStore, cache:
Lazy<ConversationCache>, attachments: Lazy<AttachmentStore>): suspend (String) -> Unit` in
`di/ObservablePairedServerStore.kt` is the production `onHostRemoved` hook
`ObservablePairedServerStore.remove` runs once `delegate.remove` and the revision bump have both
succeeded — see [paired server store § Wiring & usage](paired-server-store.md#wiring--usage) for the
hook's own contract. It clears the host's composer drafts first (`ComposerDraftStore.clearHost`, see
[Thread screen § Composer draft ownership](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)), then, inside one
`withContext(NonCancellable)` block so a view model cleared mid-cleanup cannot strand the forgotten
host's content or files on disk, calls `cache.value.removeHost(serverId)` and then
[`attachments.value.removeHost(serverId)`](attachment-retrieval.md#host-store--datacacheattachmentstorekt)
(#900) — each runs whether or not the other one failed. A failed cache removal logs the static
`event=host_cache_remove_failed`; a failed attachment removal logs the static
`event=host_attachments_remove_failed`. Neither is surfaced — the pairing is already gone by then, so
reporting a failure would claim the host is still paired when it is not — and neither logs the id or the
removal's own message.

`cache` and `attachments` are both `Lazy`, not their plain types, so resolving the paired-server store
binding never constructs either: both roots are `Context`-derived directories (the cache's is
`Context.noBackupFilesDir`, see § Root and storage scope above; the attachment store's is
`noBackupFilesDir/attachments`), and the JVM tests that resolve `appModule`'s paired-server store without
a `Context` would otherwise fail with `MissingAndroidContextException` the moment that binding runs. The
Koin binding is `single { ObservablePairedServerStore(KeystorePairedServerStore(get()), forgetRemovedHost(get(), lazy { get() }, lazy { get() })) }`.

Named rather than written inline in `appModule`, for the same reason the #790 draft eviction was: a
JVM test that restates the hook as its own lambda stays green if production forgets a step, while one
that binds `forgetRemovedHost` itself cannot. `HostChannelListViewModelTest`'s fixture binds
`forgetRemovedHost(drafts, lazyOf(cache), lazyOf(attachments))` over a real `FileConversationCache` and a
real `AttachmentStore` on a `TemporaryFolder` for exactly this reason.

Permanent deletion does not go through this hook — see [Caching conversation repository §
delete](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798) for
`removeConversation`'s call site, which is a `CachingConversationRepository` override, not a paired-
server-store hook.

Archive and unarchive call neither removal. Both stay plain `by delegate` forwarding on
`CachingConversationRepository`, so an archived conversation's cached content is unreachable through
either code path this section or the linked one describes.
