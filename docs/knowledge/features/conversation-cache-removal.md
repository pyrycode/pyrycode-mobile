# Conversation cache — Removal on unpair — forgetRemovedHost

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Removal on unpair — `forgetRemovedHost`

`ObservablePairedServerStore.remove` calls the production `forgetRemovedHost` hook after
pairing removal and revision publication. It clears composer drafts and MCP failure
acknowledgements, then removes sharing shortcuts, cache content and attachments under
`NonCancellable`. Cache and attachment removals each run even if the other returns a failure.
Static `event=host_cache_remove_failed` / `event=host_attachments_remove_failed` diagnostics
contain no owner or exception message; cleanup failure does not claim the already removed
pairing is still present. See [paired-server wiring](paired-server-store.md#wiring--usage).

Since #2161, `MigratingConversationCache` serializes imports, thread saves and scoped removal
under one mutex. Lock order remains repository `historyWrites`, cache mutex, then storage/file
mutex; there is no second repository save coordinator. Confirmed conversation removal deletes
authoritative items, headers and item-retirement rows and records both historical family
`retired` markers in the shared `file_imports` ledger in one transaction,
then deletes the file-backed metadata, read position and both source documents. Host removal
clears authoritative storage for that exact host, replaces its ledger rows with a host-wide `retired`
marker, then removes the host's file directory. A committed retirement prevents leftover
sources from re-importing after cleanup failure or restart. Same-id owners on other hosts
remain readable, including case-distinct `Host`/`host` owners. A failed database retirement
is reported as failure, not successful removal.

Schema 12 (#2386) drops only legacy content/evidence tables and their exclusive indexes.
It preserves `file_imports`, including historical legacy completion/retirement records,
authoritative completion records and host/conversation fences. The legacy family marker
remains meaningful even though there is no legacy backend. Leftover known/unknown-owner
legacy files stay inert during opening/import; explicit removal still cleans known scoped
files without inferring owners from hashes. Authoritative content and its import completion
record commit together, so retained source bytes cannot overwrite a newer Room save.

Item retirement is a separate fence in `authoritative_retirements`: retained revisions reject
stale item resurrection within a surviving conversation. Whole-scope removal clears those
rows together with content and installs the ledger fence against stale file import. Neither
operation promotes provisional sends into authoritative facts or certification. Metadata,
supported read positions and pending sends retain their independent lifetimes. See the
[upgrade/reopen proof](conversation-cache-testing.md#legacy-discard-upgrades-and-reopen-proof-2386).

Host removal also installs an in-process thread-write fence (`host_retired`), protecting against
held, queued or final saves from the closing repository. Neither a read nor `writeConversations`
lifts it: the forgotten host's own list collector can still publish a stale list. Only a
successfully persisted pairing invokes `onHostSaved` → `admitSavedHost` → `cache.admitHost`,
which takes the same mutex and lifts the fence for that host. Admission retains the durable
host retirement marker so leftover pre-unpair sources remain ineligible for import. On restart
there are no closing-process writers, while the durable marker still prevents resurrection.

`ObservablePairedServerStore.save` leaves pairing persistence cancellable. Once persistence
succeeds, revision publication and saved-host admission finish together under `NonCancellable`.
Cancellation while admission waits on the cache mutex therefore cannot strand a persisted
pairing behind the write fence; failed persistence admits nothing. There is no new job or scope.

`appModule` supplies `admitSavedHost(lazy { get() })` as `onHostSaved` and
`forgetRemovedHost(...)` as `onHostRemoved`. The cache and attachment dependencies remain
`Lazy`, so resolving the pairing store in a context-free fixture does not construct their
Android-derived roots. See [dependency injection](dependency-injection.md#how-it-works).

`forgetRemovedHost` is named rather than written inline in `appModule`, for the same reason the #790 draft eviction was: a
JVM test that restates the hook as its own lambda stays green if production forgets a step, while one
that binds `forgetRemovedHost` itself cannot. `HostChannelListViewModelTest`'s fixture binds
`forgetRemovedHost(drafts, lazyOf(cache), lazyOf(attachments))` over a real `FileConversationCache` and a
real `AttachmentStore` on a `TemporaryFolder` for exactly this reason.

Permanent deletion does not go through this hook — see [Caching conversation repository §
delete](caching-conversation-repository.md) for
`removeConversation`'s call site, which is a `CachingConversationRepository` override, not a paired-
server-store hook.

Archive and unarchive call neither removal. Both stay plain `by delegate` forwarding on
`CachingConversationRepository`, so an archived conversation's cached content is unreachable through
either code path this section or the linked one describes.
