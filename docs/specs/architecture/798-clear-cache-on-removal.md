# #798 — Clear cached conversation content when its host or conversation goes away

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` → `ConversationCache.removeHost`,
  `removeConversation` — the #795 removal operations this ticket wires. Both are id-exact, return
  `Result<Unit>`, and treat an unknown id as a successful no-op; `removeConversation` already takes the
  #797 thread rows with the metadata entry.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `FileConversationCache` —
  the real implementation the tests run on a `TemporaryFolder`, so "readable" is proved against the real
  hashed-directory layout rather than a double.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `ObservablePairedServerStore`,
  `onHostRemoved` — the #790 seam: runs with the removed id only after the delegate's `remove`
  succeeded, bound once in `appModule` "so any future removal path inherits the eviction".
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController.confirmUnpair` —
  calls `pairedServers.remove` and restores the editor with `unpairFailed` when it throws. Constructed
  inside `ChannelListViewModel` and `SettingsViewModel`; this plan does **not** change it.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ObservablePairedServerStore` binding, the
  `ConversationCache` single, `ThreadDestinationFactory.repository` — where the per-destination
  `CachingConversationRepository(stable, cache, serverId)` is built with the destination's own server id.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` →
  `CachingConversationRepository` — per-host, per-destination wrapper that owns `serverId`; today it
  overrides only `observeMessages`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `delete`, `archive`,
  `unarchive` — `delete` converges and tolerates unknown ids; archive/unarchive are not removals.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOverflowEvent`
  (`ThreadEvent.DeleteConfirm`) — the only production `delete` caller (thread overflow and the Channel
  Info sheet both land here); it calls `delete` on the destination's repository, then clears drafts, then
  `PopBack`.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `reconcile`, `update` — the list
  writer. Its `update` guard rejects a list once the host leaves `connections`, which bounds the unpair
  race discussed under Security review.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → the `store.revision` collector —
  reconciles asynchronously after the store's revision bump.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` →
  `confirmingUnpairDropsThatHostsDraftsAndLeavesEveryOtherHostsAlone`, `Fixture`, `Store.failRemove` —
  the #790 end-to-end unpair proof this ticket's unpair proof mirrors.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt` — the
  wrapper's existing test and `RelayLog` capture idiom.
- `docs/knowledge/features/` — no overview adds a constraint beyond the #790/#795/#797 specs above.

## Design source

**Figma:** N/A — no visual. The ticket wires two removal operations to existing confirmations and adds
no rendered surface.

## Context

#795 gave the cache `removeHost` and `removeConversation`; #796 and #797 write it. Nothing calls the
removals yet, so an unpaired host's conversation names and thread rows, and a permanently deleted
conversation's rows, stay in `noBackupFilesDir` indefinitely. This slice calls each removal at the one
place its removal is confirmed. No ADR.

## Design

### Unpair — through the store's removal hook, not through `confirmUnpair`

`confirmUnpair` already delegates removal side-effects to `ObservablePairedServerStore.onHostRemoved`
(#790 drafts). The cache removal joins that hook rather than becoming a new `HostEditorController`
dependency: the controller is built inside two view models, so a constructor parameter would cascade
through `ChannelListViewModel`, `SettingsViewModel`, their Koin bindings and their test constructions,
and the #790 KDoc already records why the hook is the right home ("any future removal path inherits
the eviction").

Changes in `ObservablePairedServerStore.kt`:

- `onHostRemoved` becomes `suspend (String) -> Unit`. `remove` already suspends, so the cache removal is
  **awaited** inside `remove`: when `confirmUnpair`'s `remove` returns, the content is gone, and the
  editor closes only after that. The hook still runs only after `delegate.remove` succeeded and after
  the revision bump, so a throwing store (AC 4) never reaches it. Existing `{ }` lambdas at the other
  constructor sites compile unchanged as suspend lambdas. The KDoc's "must not block" becomes "must not
  throw, and must suspend rather than block".
- New `internal fun forgetRemovedHost(drafts: ComposerDraftStore, cache: ConversationCache): suspend (String) -> Unit`
  — the one production hook, named so the JVM test binds the **same** function production binds
  rather than restating it (the #790 test restated `drafts::clearHost`, which a forgotten binding would
  leave green). It clears the host's drafts, then calls `cache.removeHost(serverId)` inside
  `withContext(NonCancellable)`, and on failure logs `event=host_cache_remove_failed`, identifier-free.
  A failure is not surfaced: the pairing is already gone, the same reasoning `confirmUnpair` records
  for the workspace clear.

`AppModule`'s binding becomes `ObservablePairedServerStore(KeystorePairedServerStore(get()), forgetRemovedHost(get(), get()))`.

### Delete — in the per-host caching wrapper

`CachingConversationRepository` overrides `delete(conversationId)`:

1. `delegate.delete(conversationId)` — if it throws, the conversation still exists on the daemon and the
   cache is untouched; the exception propagates to `launchGuardedRepoCall` exactly as today.
2. Mark `conversationId` as deleted in the instance (see below).
3. `cache.removeConversation(serverId, conversationId)` inside `withContext(NonCancellable)`; on failure
   log `event=conversation_cache_remove_failed`, identifier-free, and return normally — the delete
   succeeded.

The host comes from the wrapper's own `serverId`, which `ThreadDestinationFactory.repository` captured
from the destination that issued the call — never from a global selection. A blank server id gets no
wrapper, so nothing can be removed under the empty id. The demo path has no cache.

**No write after delete.** The thread that issued the delete keeps collecting `observeMessages` on this
same instance until `PopBack` clears it. A non-empty live emission in that window (a late delta), or a
retry of an earlier failed write, would call `writeThread` and put the rows back. The wrapper keeps a
thread-safe set of ids it deleted and `observeMessages` skips `writeThread` for them. The set lives and
dies with the destination's wrapper, so it cannot hide a conversation elsewhere.

`archive` / `unarchive` stay plain delegation. They are not overridden, so neither can reach a removal
(AC 3).

## State + concurrency model

No new scope or job. Both removals run in the caller's coroutine (`viewModelScope` via
`confirmUnpair` / `launchGuardedRepoCall`). `NonCancellable` covers only the cache call itself, which
is bounded file I/O on the cache's own IO dispatcher: once the irreversible step (pairing removal,
daemon deletion) has happened, a view model cleared mid-cleanup must not leave the content behind.
`FileConversationCache`'s mutex serializes the removal against any concurrent write.

## Error handling

- Store `remove` throws → hook never runs → cache intact, editor shows `unpairFailed` (unchanged).
- `removeHost` fails → one static log line; unpair still reports success.
- `delegate.delete` throws → no removal; existing guarded-call handling.
- `removeConversation` fails → one static log line; delete still reports success.
- Unknown host / never-cached conversation → the cache's success no-op; no log.

## Testing strategy

JVM unit tests only; there is no new UI and no operator-facing flow change, so no rung-3/4 scenario.

`HostChannelListViewModelTest` — the fixture binds `ObservablePairedServerStore(store, forgetRemovedHost(drafts, cache))`
with a real `FileConversationCache` on a `TemporaryFolder` (the existing #790 draft test keeps passing
through the same hook). New test, driven through the view model's unpair gesture:

- AC 1 + AC 4: seed conversations and a thread for `"Host"` and `"host"` (case-differing ids); a
  failed removal leaves both hosts' conversations and thread readable; a successful one leaves `"Host"`
  empty and `"host"` intact; no server or conversation id appears in any captured log line.

`CachingConversationRepositoryTest` — real `FileConversationCache` on a `TemporaryFolder`:

- AC 2: two conversations cached (metadata + thread) under one host plus one under another; `delete`
  removes that conversation's metadata and thread and leaves the rest readable.
- A throwing delegate `delete` leaves the cache intact and propagates.
- A thread collected through the wrapper does not re-write rows for the conversation after `delete`
  (live emits a new row after the delete; the thread file stays empty).
- AC 3: `archive` then `unarchive` through the wrapper leaves conversation metadata and thread readable.
- Removal failure (a cache whose `removeConversation` fails) logs the static line and `delete` returns.

## Open questions

- None blocking. Whether `forgetRemovedHost` should live in `ObservablePairedServerStore.kt` or
  `AppModule.kt` is cosmetic; the plan puts it beside the hook it produces.

## Documentation handoff

Pending for the documentation stage: fold the unpair/delete cache removal into the owning overviews
(conversation cache / host editor) — which removal clears what, and that archive/unarchive do not.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only inputs are a server id and a conversation id, both passed
  opaquely to the #795 cache, which hashes the server id into the path and keeps conversation ids inside
  documents or hashed thread paths; nothing here concatenates either into a path, log or URL.
- [Tokens] No findings — the hook receives only the `serverId`, never the `PairedServer` record; the
  token and static key never reach the cache or a log.
- [File / storage] No findings on scope (`noBackupFilesDir`, unchanged). The design goal itself is this
  category: the ordering (credential removal first, cache removal only after success) is what AC 4
  observes, and `NonCancellable` keeps a cancelled view model from stranding content after the
  irreversible step.
- [File / storage] OUT OF SCOPE — residual unpair race: `RelayConnectionRegistry` reconciles the revision
  bump asynchronously, so a daemon list accepted by `HostConversationSource.update` in the milliseconds
  between the hook's `removeHost` and the registry dropping the host could write that host's list back.
  Not observed; the written content is what the daemon itself just sent, it is unrendered (no host
  entry reads it unless the same server id is re-paired, whereupon the live list replaces it), and a
  deterministic fix needs a tombstone in the cache contract. File a bug if observed.
- [File / storage] SHOULD FIX (in this ticket) — thread re-write after delete by the issuing thread's own
  collector; addressed by the deleted-id set in `CachingConversationRepository` and its test.
- [Inter-process] No findings — no manifest, intent, or provider change.
- [Crypto] No findings — no primitive touched.
- [Network & I/O] No findings — no frame, URL or socket change; `delete`'s wire request is unchanged.
- [Logs] No findings — two new lines, `event=host_cache_remove_failed` and
  `event=conversation_cache_remove_failed`, static and identifier-free; the unpair test asserts no id
  reaches a log line.
- [Concurrency] No findings — no new scope; the cache's single mutex serializes removal against writes;
  the deleted-id set is a concurrent set read by the collector and written by `delete`.
- [Threat model] OUT OF SCOPE — forensic recovery of deleted file blocks on a rooted device; the cache is
  unencrypted app-private storage by #795's design, and removal is `File.delete`, not secure erase.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

**2026-09-23 — `forgetRemovedHost` takes `Lazy<ConversationCache>`.** Resolving the cache eagerly in
the store binding made `HostChannelListViewModelTest.appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`
fail with `MissingAndroidContextException`: that JVM test resolves `appModule`'s paired-server store
without a Context, and the cache's root is `noBackupFilesDir`. The hook now resolves the cache on first
removal (`lazy { get() }` in `appModule`, `lazyOf(cache)` in the test fixture). Behaviour is unchanged;
the alternative, overriding the cache binding in every JVM test that loads `appModule`, would have fanned
out across test files for no production benefit.
