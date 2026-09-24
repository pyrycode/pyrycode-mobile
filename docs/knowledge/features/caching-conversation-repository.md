# Caching conversation repository — keeping a thread readable while its host is unreachable

[`CachingConversationRepository`](../../../app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt)
(`data/repository/`) wraps one host's
[`StableConversationRepository`](stable-conversation-repository.md)
so a conversation opened with no live connection — a fresh process, a background reopen, a dead
connection — draws the settled rows it last held instead of an empty thread. Landed in
[#797](../../specs/architecture/797-thread-row-cache.md), split from
[#647](https://github.com/pyrycode/pyrycode-mobile/issues/647). It reads and writes the thread-row
family [#795](conversation-cache.md)/[#796](conversation-cache.md) added to the app-private
[`ConversationCache`](conversation-cache.md).

## Why it exists

A thread's rows live in the connection-scoped `RemoteConversationRepository`.
`StableConversationRepository.observeMessages` emits `emptyList()` between connections, and
nothing survives process death, so a conversation read an hour ago opened blank the moment the
phone lost reception or was relaunched offline. Everything else about a live connection —
permission modals, the thinking indicator, stall, queue, API retry, compaction — is genuinely
live state with no offline meaning, so only `observeMessages` needed a restore.

## Contract

```kotlin
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
    private val attachments: AttachmentStore? = null,
) : ConversationRepository by delegate {
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
    override suspend fun delete(conversationId: String)
    override suspend fun retrieveAttachment(conversationId: String, attachmentId: String): AttachmentRetrievalResult
}
```

Kotlin class delegation (`by delegate`) means every member except `observeMessages`, `delete`
(#798) and `retrieveAttachment` (#899) is plain pass-through — stall, queue, API retry, compaction,
thinking, usage limit, modals, archive, unarchive and every other one-shot keep their live-only
behaviour unchanged. Nothing restored can reopen a permission prompt or restart an indicator,
because nothing outside those three overrides is touched at all. Archive and unarchive deliberately
stay delegation: they are not removals, so neither can reach a cache-clearing path (see [Conversation
cache § Removal on unpair](conversation-cache.md#removal-on-unpair--forgetremovedhost) for the
wording this mirrors).

## Retrieving an attachment for this host (#899)

```kotlin
override suspend fun retrieveAttachment(conversationId: String, attachmentId: String): AttachmentRetrievalResult =
    attachments?.retrieve(serverId, conversationId, attachmentId) { delegate.fetchAttachment(conversationId, attachmentId) }
        ?: delegate.retrieveAttachment(conversationId, attachmentId)
```

This wrapper is the natural home for `retrieveAttachment` for the same reason it already holds the
thread-row cache: it is the one place that knows both `serverId` and a live delegate to fetch through.
[`AttachmentStore`](attachment-retrieval.md) does the actual work — single-flighting concurrent
retrievals of the same file, checking for a kept file first, and writing verified bytes temp-then-rename
— this wrapper only supplies the host identity and the fetch function. With no store (`attachments ==
null`) the call is plain delegation, the same fallback every other member of this class not listed above
already has by construction. See [Attachment retrieval](attachment-retrieval.md) for the store's layout,
bound, single-flight and failure handling; this file only covers the wiring.

## How the restore merges with live rows

`observeMessages` reuses the one dedup the codebase already has for this shape —
[`mergeHistoryRows`](remote-conversation-repository-reads-and-thread-store-history-paging.md)
(`HistoryPageReducer.kt`) — from the other side of its usual direction. Paging normally prepends
an *older* page onto what is on screen; here the
restored rows are the older set and the live projection is the receiver:

```
drawn = live.mergeHistoryRows(restored)
```

One join key per row kind (`message_id` for a message, covering a `tool_use_id` and a `turn_id`;
the `(previousSessionId, newSessionId)` pair for a boundary), and a prepend rather than a re-sort,
because a thread is in arrival order by deliberate choice. Merging into an empty live projection
returns the restored rows verbatim — the disconnected case needs no branch of its own, it falls
out of the same merge that handles a reconnect.

The restored snapshot (`cache.readThread(serverId, conversationId)`) is read **once per
collection**. A later failed read therefore cannot blank rows already drawn, and the read is the
only place a cache failure can reach this wrapper — writes fail independently (see below).

### The merge base moves at a connection boundary, not on every emission

The naive version — fixing the merge base to the once-read `restored` snapshot for the whole
collection — shipped first and failed review (PR #837, first pass): `StableConversationRepository`
emits `emptyList()` on every disconnect (and again before a new connection's first page), so
`drawn` collapsed to the open-time snapshot on every disconnect, and the write-when-changed rule
wrote that shrunk snapshot back. Read → background → process death → offline reopen came back
blank on a first-read thread, which is the ticket's headline scenario.

The fix: the merge base is not fixed for the whole collection. It starts as `restored`. Each time
`live` is empty — a connection boundary — the base moves to `settledThreadRows(lastDrawn)`, the
rows the screen already drew with their in-flight (streaming / running-tool) rows stripped. While
live rows are flowing inside one connection, the base stays fixed, so a row the live side
deliberately removes (`RemoteConversationRepository.removeOwnEcho` on a dropped queued send) is
still honoured and not resurrected by an accumulating union — the same reasoning that ruled out an
accumulating union for the original restored-snapshot design.

```kotlin
var base = cache.readThread(serverId, conversationId)
var lastWritten = base
var lastDrawn = base
delegate.observeMessages(conversationId).collect { live ->
    if (live.isEmpty()) base = settledThreadRows(lastDrawn)
    val drawn = live.mergeHistoryRows(base)
    lastDrawn = drawn
    emit(drawn)
    val cacheable = cacheableThreadRows(drawn)
    if (cacheable != lastWritten) {
        if (cache.writeThread(serverId, conversationId, cacheable).isSuccess) {
            lastWritten = cacheable
        } else {
            RelayLog.d { "event=thread_cache_write_failed" }
        }
    }
}
```

After the rebase, `cacheableThreadRows(drawn)` equals whatever the previous emission already
wrote, so a disconnect **writes nothing** — the only exception is retrying an earlier failed
write. `settledThreadRows` (not `cacheableThreadRows`) is the rebase's own function: it drops only
in-flight rows and applies no row-count bound, so a thread longer than
[`MAX_CACHED_THREAD_ROWS`](conversation-cache.md#the-contract) does not visibly shrink on screen
the moment its connection drops.

**Accepted residual:** the rebased base can carry a queued send's echo that the live side would
have dropped, if that echo is the *only* live row at the moment of disconnect — the accumulating
union's edge case, reintroduced at connection boundaries only. Not fixed: a queued send implies a
running turn with its own live rows, so the case has not been observed. See the plan's `##
Revisions` entry (2026-09-23) for the full argument.

**Known non-blocking follow-on (verifier finding, PR #837 second pass, accepted):** the rebased
base can also carry a previous connection's `ThreadItem.UnrecognizedMessage` rows, whose id
(`RemoteConversationRepository.unrecognizedRowId`) is a per-connection counter
(`"unrecognized-<n>"`). That id's KDoc assumes no reader ever observes rows from two connection
instances merged — an assumption the rebase breaks. After a reconnect, the new connection's first
unrecognized frame can collide on id with a rebased row from the old connection, and
`mergeHistoryRows` drops the older one in its usual fail-safe direction. The only effect is an
earlier diagnostic row silently disappearing; this cannot produce a duplicate key or a crash.
Deferred, not fixed.

## What is written, and when

The write is the thread **as drawn** — restored-plus-live with exclusions applied — never the
live projection alone: right after a reconnect the live side holds only the newest page, and
writing it alone would shrink the cache. [`cacheableThreadRows`](conversation-cache.md#the-contract)
is the single definition of what may reach disk, shared with `ConversationCache`'s own write path
so the two can never disagree; the wrapper only decides *when* to call it.

A write happens only when `cacheableThreadRows(drawn)` differs from `lastWritten` (initially the
restored snapshot). That means:

- Opening a conversation offline writes nothing — `drawn == restored`.
- An `assistant_delta` stream writes nothing until the turn settles, because every intermediate
  emission's cacheable set is unchanged until an in-flight row's exclusion condition clears.
- A disconnect writes nothing, per the rebase above, unless retrying an earlier failed write.

A failed write logs one static event (`RelayLog.d { "event=thread_cache_write_failed" }`) and does
**not** advance `lastWritten`, so the next drawn change retries it. Nothing about a row, a
conversation id or a server id is ever logged.

## State and concurrency

No scope is owned and nothing is launched — `observeMessages` returns a cold `flow {}`, and its
`base` / `lastWritten` / `lastDrawn` state is local to that block. Two screens observing the same
conversation each get their own collection and their own local state; the cache's per-instance
`Mutex` serializes their writes if both happen to fire (last writer wins with a complete drawn
set). Cancelling the collector (the ViewModel's `viewModelScope`, in practice) cancels any
in-flight write; the atomic move in `FileConversationCache` means a cancelled write leaves the
previous document intact, never a torn one.

## `delete` — removing the cache alongside the daemon (#798)

```kotlin
override suspend fun delete(conversationId: String) {
    delegate.delete(conversationId)
    deleted += conversationId
    withContext(NonCancellable) { cache.removeConversation(serverId, conversationId) }
        .onFailure { RelayLog.d { "event=conversation_cache_remove_failed" } }
}
```

`delegate.delete(conversationId)` runs first and unguarded: a refused delete (the daemon's
`conversation.not_found` aside — see [remote repository §
delete](remote-conversation-repository-conversation-writes.md#deleteconversationid--the-eighth-mutation-first-remove-shaped-one-532))
propagates before the cache is touched, so the cached content for a conversation that still exists on
the daemon is never removed. Only once that call returns does the wrapper mark the id deleted and
remove the cached copy — `cache.removeConversation(serverId, conversationId)` — inside
`withContext(NonCancellable)`, for the same reason `forgetRemovedHost` uses it: the daemon-side
deletion already happened, so a screen cleared mid-cleanup must not strand the content. A failed cache
removal logs one static `event=conversation_cache_remove_failed` line and is not surfaced — `delete`
still reports success, since the conversation genuinely is gone.

The host is this wrapper's own `serverId`, captured by `ThreadDestinationFactory.repository` from the
destination that issued the call — never a global selection (see § Wiring below). A blank `serverId`
gets no `CachingConversationRepository` at all, so nothing can be removed under the empty id.

**No write after delete.** The thread that issued the delete keeps collecting `observeMessages` on
this same wrapper instance until its screen's `PopBack`, and a late live emission in that window (or a
retry of an earlier failed write) would otherwise call `writeThread` and put the deleted conversation's
rows straight back. A thread-safe `deleted: MutableSet<String>` (`ConcurrentHashMap.newKeySet()`) holds
every id this instance deleted; `observeMessages`'s write guard becomes `cacheable != lastWritten &&
conversationId !in deleted`. The set lives and dies with this wrapper instance — a fresh destination
for the same conversation (a re-open after `PopBack`) gets a fresh, empty set, so the guard cannot hide
a conversation that was later re-created under the same id.

## Wiring — under `decorateRepository`, not in it

`ThreadDestinationFactory.repository(serverId, bundle)` (`di/AppModule.kt`) wraps the
`StableConversationRepository` it builds in `CachingConversationRepository` **before** handing the
result to the `decorateRepository` hook:

```kotlin
decorateRepository(
    if (cache != null && serverId.isNotEmpty()) CachingConversationRepository(stable, cache, serverId, attachments) else stable,
)
```

`attachments` (#899) follows `cache` through the same conditional — a blank `serverId` gets neither, so
retrieved files, like restored rows, are never filed under the empty id's namespace.

`E2eTestApplication` replaces `decorateRepository` with `::TappingConversationRepository` for its
instrumented harness. Because the cache sits *underneath* that hook rather than inside it, the
tapping decorator still observes the restored, merged thread — a cache wired the other way round
would be invisible to exactly the harness [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
uses to prove offline reading and reconnect reconciliation live. The demo branch
(`FakeConversationRepository`) never reaches this code path and gets no
cache. A blank `serverId` (a malformed route) also gets no cache, so restored rows are never filed
under the empty id's namespace.

`hostConversationModule(useRelay, decorateRepository)` passes `cache = if (useRelay) get() else
null` into `ThreadDestinationFactory`'s constructor — the same `useRelay` gate
[`HostConversationSource.relay(get(), cache = get())`](dependency-injection-host-conversation-source.md#restore-from-the-on-disk-cache-796)
already follows for the host-list restore. No new Koin binding was added for this ticket; both
consumers resolve the single `ConversationCache` #796 bound.

`ThreadDestinationFactory` gained the matching `attachments: AttachmentStore? = null` constructor
param (#899), gated `if (useRelay) get() else null` the same way as `cache`, resolving the app's single
`AttachmentStore` bound in `AppModule` over `File(androidContext().noBackupFilesDir, "attachments")`.
Because that `get()` runs inside the `single { ThreadDestinationFactory(...) }` block, any Koin
container that resolves a `ThreadDestinationFactory` under `useRelay = true` now needs an
`AttachmentStore` binding too — see [Dependency injection §
AttachmentStore](dependency-injection.md#attachmentstore-and-context-free-thread-destination-containers-899)
for the four test containers that needed the same `InertConversationCache`-shaped override.

Three `RelayConnectionFactoryTest` containers build a thread destination under `useRelay = true`
with no `androidContext()`. Resolving the cache in `ThreadDestinationFactory` meant those
containers now need the same `single<ConversationCache> { InertConversationCache }` override their
sibling container already carried for #796 — before this change only `HostConversationSource`'s
containers needed it. Any future container built the same way inherits this requirement.

## Testing

[`CachingConversationRepositoryTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt) —
JVM unit tests against a fake `ConversationCache` and a `MutableStateFlow`-backed delegate. Cases:

- offline (delegate emits empty): draws restored rows verbatim and writes nothing;
- reconnect: the live newest page merges over restored ids/boundaries with no duplicate key, older
  restored rows precede;
- a streaming delta sequence writes only once the row settles; an unchanged settled set writes
  nothing;
- failed read → live rows only, no blank thread; failed write → the next change retries it;
- **a disconnect keeps the rows drawn during the connection and writes nothing** — the regression
  test for the rework above; it fails with the rebase disabled and passes with it;
- **a reconnect after a disconnect merges over everything drawn so far** — covers the connection
  that follows a rebase;
- non-thread flows are pure delegation (e.g. `observeStall` / `observeQueue` untouched by the
  cache).

Six further cases (#798), added on a real `FileConversationCache` (`TemporaryFolder`) so "the rest is
readable" is proved against the real hashed-directory layout rather than a fake: a permanent delete
removes exactly that conversation's cached metadata and thread and leaves a same-id conversation under
a different host untouched; a delete the daemon refuses (a throwing `delegate.delete`) leaves the
cache intact and propagates; archiving then unarchiving through the wrapper leaves cached metadata and
thread readable (`archive`/`unarchive` are plain delegation, so this is really a regression guard on
class delegation staying intact); a thread collected through the wrapper does not write a late row
back after its conversation is deleted (the `deleted` set); and a cache whose `removeConversation`
fails still lets `delete` return, logs the one static event, and leaks no server or conversation id
into a captured log line.

One further case (#899): `retrieveAttachment` goes through a fake `AttachmentStore`-shaped fetch with
this wrapper's own `serverId` and the delegate's `fetchAttachment` as the fetch function — a wiring
regression guard, not a proof of the store's own behaviour (that lives in
[`AttachmentStoreTest`](attachment-retrieval.md#testing)).

No Compose UI test: restored rows draw through the same composables a live row does, below the
existing [`ConnectionBanner`](connection-banner.md) in its offline state. Live continuity across
a real reconnect — a loaded conversation staying readable while its host link is cut and
reconciling a peer's turn once the link is restored — is proven live by
[#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`); this
wrapper's scripted coverage (`stream`, `reconnect`, `replay-order`) ran green with zero real turns,
per the dispatcher gate on PR #837's re-review.

## Related

- [Conversation cache](conversation-cache.md) — the storage layer this wrapper reads and writes;
  `cacheableThreadRows` / `settledThreadRows` are defined there, not here
- [Stable conversation repository](stable-conversation-repository.md) — the delegate this wrapper
  sits directly on top of, and the source of the `emptyList()` connection-boundary signal this
  wrapper's rebase depends on
- [Remote conversation repository — reads and thread store history
  paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) —
  `mergeHistoryRows`, the one dedup this restore reuses from the other side
- [Dependency injection](dependency-injection.md) — `ThreadDestinationFactory.repository` wiring,
  `decorateRepository`, and the `useRelay` cache/attachments gates
- [Attachment retrieval](attachment-retrieval.md) (#899) — `AttachmentStore`, the host-keyed store this
  wrapper's `retrieveAttachment` delegates to: its layout, single-flight, bound and failure handling
- [Paired server store § Wiring & usage](paired-server-store.md#wiring--usage) and [Conversation
  cache § Removal on unpair](conversation-cache.md#removal-on-unpair--forgetremovedhost) — the
  sibling removal path, `forgetRemovedHost`, that this wrapper's `delete` does not go through
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); ticket
  [#797](../../specs/architecture/797-thread-row-cache.md) (this doc);
  [#798](../../specs/architecture/798-clear-cache-on-removal.md) (done — wires `delete` above to
  `removeConversation`)
