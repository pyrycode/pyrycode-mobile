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

**The restore only ever sees rows that reached this wrapper's own `observeMessages` collection.** A row
the daemon delivers to a thread nobody is observing — the operator is looking at another channel, or
another host's thread — is never written to the cache, because the write only happens inside this
block's own `collect`. A reconnect then rebuilds the connection-scoped projection that briefly held that
row, and it is gone: the replay cursor has already advanced past it, so Mode A replay does not resend it
either. [#1572](https://github.com/pyrycode/pyrycode-mobile/issues/1572) does not change this wrapper —
it recovers the row a different way, by having `ThreadViewModel` ask for the newest history page every
time an *open* thread's host becomes available, so the daemon re-delivers what this cache missed. See
[Thread screen § the oldest-end history
demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777) for that ask.

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
    override suspend fun readHistoryPosition(conversationId: String): HistoryPosition?
    override suspend fun writeHistoryPosition(conversationId: String, position: HistoryPosition?)
}
```

Kotlin class delegation (`by delegate`) means every member except `observeMessages`, `delete`
(#798), `retrieveAttachment` (#899) and the saved history position (`readHistoryPosition`/
`writeHistoryPosition`, #1354, see § The saved history position below) is plain pass-through — stall, queue, API retry, compaction,
thinking, usage limit, modals, archive, unarchive and every other one-shot keep their live-only
behaviour unchanged. Nothing restored can reopen a permission prompt or restart an indicator,
because nothing outside those three overrides is touched at all. Archive and unarchive deliberately
stay delegation: they are not removals, so neither can reach a cache-clearing path (see [Conversation
cache § Removal on unpair](conversation-cache.md#removal-on-unpair--forgetremovedhost) for the
wording this mirrors).

`requestHostSystemPrompt` and `setHostSystemPrompt` (#1774) also pass through by
Kotlin delegation. Current/default prompt strings never enter `ConversationCache`
or become offline readings. With the stable delegate, both operations keep its
[live-only snapshot and failed-result behavior](stable-conversation-repository.md#host-system-prompts--snapshot-or-result-1774).
`HostSystemPromptFacadesTest` checks read and write replies through this wrapper
and asserts that these calls perform no cache read or write.

`observeSessionError` (#1677) is also plain delegation: current and changing codes
pass through without a cache read or write. It retains no code or daemon prose and
cannot restore an error from history. With the stable delegate, disconnect emits
null and reconnect starts fresh, even while cached thread rows remain readable.
See [the session-error contract](remote-conversation-repository-state-errors-and-handoff.md#conversation-session-errors-1677).

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

`observeMessages` merges through
[`mergeCachedRows`](remote-conversation-repository-reads-and-thread-store-history-paging.md)
(`HistoryPageReducer.kt`), a sibling of the history walk's `mergeHistoryRows` built for this
wrapper's own direction: paging normally prepends an *older* page onto what is on screen; here the
restored rows are the older set and the live projection is the receiver:

```
drawn = snapshot.rows.mergeCachedRows(restoredWithoutSuppressedUserEchoes)
```

`mergeCachedRows` shares `mergeHistoryRows`'s join (`message_id` for a message, covering a
`tool_use_id` and a `turn_id`; the `(previousSessionId, newSessionId, occurredAt)` triple for a
boundary) and its [attachment-reference hint fill](remote-conversation-repository-reads-and-thread-store-history-paging.md),
so a restored row the daemon re-delivers is never drawn twice and a sent row's names come back even
when the live side's replayed copy has none. Merging into an empty live projection returns the
restored rows after suppression filtering. The disconnected case falls out of the same merge
that handles a reconnect. Where it differs from `mergeHistoryRows`: a row *only* the cache
holds does not always go to the front. It goes right after the live copy of the nearest cached row
above it that the live side also holds, and only goes in front when it has no such anchor — the
older rows a reconnect's newest page does not reach, or a page that does not overlap the cache at
all. Several cache-only rows sharing one anchor keep their cached relative order.

`BackgroundTaskLifecycle` (#1782) is also retained in the last-drawn **in-memory** base at a
connection boundary, although disk restore never supplies it. Reconnect backfills missing launch
fields and retains one launch/finish identity per conversation/task without moving held markers.
After ordinary cache-only rows have been placed, fresh lifecycle evidence uses the same
[neighbour fold as history](remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645):
leading evidence waits for its first overlapping neighbour, and subsequent anchors cannot move
backward. History and cache merges keep their different ordinary-row placement rules.

Overlap deduplication must retain anchors even when incoming assistant text is discarded or keeps
only an older prefix. Typed identities and `(turnId, seq)` overlap locate surviving neighbours; a
segment superseded by a whole-turn row anchors to that whole turn after cleanup. Sharing identity
lookup alone does not prove reconnect order: regressions must cover leading evidence, backward
anchors, differently keyed segments and whole-turn overlap in both merge directions, including
terminal-before-start, replay and older-page prepend.

The evidence remains invisible: `foldQueuedRows` excludes it before tool grouping/rendering,
`ThreadProjection.observeRowCounts` excludes it from visible-growth signals, and Channel info's
creation timestamp skips it. Scalar lifecycle writes preserve queue/echo bookkeeping through the
same atomic `ProjectionState`. Process death loses these markers; reconnect/history can reconstruct
them. The disk schema stays unchanged, and [cache filtering and trim accounting](conversation-cache.md#the-thread-documents-two-writers-1354)
both exclude them, so evidence alone cannot clear a saved history position.

For queue delivery (#1642), `ThreadSnapshotSource` supplies visible rows and
`suppressedUserMessageIds` together through Remote → Stable → Caching. The cache filters only
restored `Role.User` messages whose ids are suppressed while awaiting a delivered push.
Missing live rows alone never justify deleting unrelated offline history or cache-only rows.
The original base remains available for attachment hints when delivery arrives; suppression
is connection-local and is never serialized. Repositories without this contract retain the
list-only fallback with empty suppression.

Rows and suppression must come from the same `ThreadProjection.ProjectionState` generation.
Independent StateFlows could pair old tap-time rows with newly cleared suppression during
reopen, briefly show the echo before intervening tools, and persist that wrong order. Delivery
now establishes live position and clears suppression in one atomic fold. Wrapper tests with a
real file cache cover local/peer Send now, reopen, removal → tools → push, duplicates and offline
history retention; `VerifierSnapshotRaceTest.reopenDuringDelivery_neverEmitsUnsuppressedTapTimeRows`
controls the subscription/delivery interleaving that ordinary final-order assertions missed.

**The merge is key-indexed, not quadratic (#1353, verifier rework).** `mergeCachedRows` builds a
`HashMap<Any, Int>` once per call, mapping each live row's `joinIdentity()` to the first live index
holding it, then looks each cached row up in that map — O(live + cached) rather than the original
O(cached × live) `indexOfFirst { listOf(it).alreadyHolds(row) }` scan. This mattered only once
`MAX_CACHED_THREAD_ROWS` moved from 200 to 100000 in the same ticket (see [Conversation cache § What's
deliberately not here](conversation-cache.md#whats-deliberately-not-here)): `observeMessages` calls
`mergeCachedRows` on every emission of the delegate, in-flight `assistant_delta` updates included,
with no `flowOn` between `RemoteConversationRepository` and the `ViewModel`'s `stateIn`, so the merge
runs on `Main.immediate`. At a 200-row cap the quadratic scan was cheap; at 100000 — a normal size for
a persistent channel after weeks of tool-call-heavy use — it was roughly a million lambda calls plus
a one-element-list allocation per pair on every streaming delta, well past a 16 ms frame. The lesson:
lifting a cap on a cached or persisted collection changes the cost of whatever already runs over that
collection on each live emission, not only what gets written — the planned change (the cache format)
and the thing it broke (an unrelated merge function's complexity) were in different files, so neither
the plan's own file list nor its "no new writes during streaming" state-and-concurrency note caught it.
`joinIdentity()` encodes the same six keys `alreadyHolds` and the `holds*` predicates already use
(message id; boundary `(previousSessionId, newSessionId, occurredAt)`; unrecognized id; banner
`occurredAt`; compaction `occurredAt`; refusal `(fallbackModel != null, occurredAt)`), each led by its
kind so rows of different kinds can't collide — a second encoding of the same identity, flagged
non-blocking in review as worth deriving from one source later, but pinned equivalent for now by
`HistoryPageReducerTest.mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone`.

**Why a plain prepend broke on a row only the cache holds (PR #987, verifier rework).** An attachment
offer (#983) is the first kind of row the daemon never replays — the cache is its only retention —
so after a reconnect or a cold restart it is the one row in a turn the live side's newest page does
not re-deliver. `mergeHistoryRows`'s `fresh + kept` puts every such row **above the whole live page**,
not back beside the message that produced it: cache `[m1, a1, attachment-offer-X, a2]` under a fresh
page `[m1, a1, a2]` drew `[attachment-offer-X, m1, a1, a2]`, and the write-when-changed rule then
made that reordering permanent on disk. `mergeCachedRows` exists so this wrapper's restore, and only
this wrapper's restore, can anchor a cache-only row where it belongs; the history walk keeps
`mergeHistoryRows` and its ordinary-row skip-and-prepend contract, since that is the deliberate answer
to the ask-versus-answer race its own KDoc describes, not a general rule about row position.

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
`live` is empty **and suppression is empty** — a connection boundary — the base moves to
`settledThreadRows(lastDrawn)`, the rows the screen already drew with their in-flight (streaming / running-tool) rows stripped. While
live rows are flowing inside one connection, the base stays fixed, so a row the live side
deliberately removes (`RemoteConversationRepository.removeOwnEcho` on a dropped queued send) is
still honoured and not resurrected by an accumulating union — the same reasoning that ruled out an
accumulating union for the original restored-snapshot design.

```kotlin
var base = cache.readThread(serverId, conversationId)
var lastWritten = base
var lastDrawn = base
delegate.threadSnapshots(conversationId).collect { snapshot ->
    val live = snapshot.rows
    if (live.isEmpty() && snapshot.suppressedUserMessageIds.isEmpty()) {
        base = settledThreadRows(lastDrawn)
    }
    val restored = base.filterNot {
        it is ThreadItem.MessageItem && it.message.role == Role.User &&
            it.message.id in snapshot.suppressedUserMessageIds
    }
    val drawn = live.mergeCachedRows(restored)
    lastDrawn = drawn
    emit(drawn)
    val cacheable = cacheableThreadRows(drawn)
    if (cacheable != lastWritten) {
        // #1354: hand writeThread the untrimmed drawn rows, not cacheable — only then can the cache
        // see a trim at MAX_CACHED_THREAD_ROWS and drop a saved history position that no longer
        // matches the oldest kept row.
        if (cache.writeThread(serverId, conversationId, drawn).isSuccess) {
            lastWritten = cacheable
        } else {
            RelayLog.d { "event=thread_cache_write_failed" }
        }
    }
}
```

An empty visible snapshot with nonempty suppression is a pending-delivery reading within the
same connection, not a disconnect. Rebasing there would lose the fixed restore base and its
attachment metadata. The filtered drawn rows are emitted and cached, so reopening cannot
resurrect a hidden queued echo before its delivered push.

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
unrecognized frame can collide on id with a rebased row from the old connection, and the merge
(`mergeCachedRows` since #983, `mergeHistoryRows` before it — both share the same `alreadyHolds` join)
drops the older one in its usual fail-safe direction. The only effect is an earlier diagnostic row
silently disappearing; this cannot produce a duplicate key or a crash. Deferred, not fixed.

## What is written, and when

The write is the thread **as drawn** — restored-plus-live with exclusions applied — never the
live projection alone: right after a reconnect the live side holds only the newest page, and
writing it alone would shrink the cache. [`cacheableThreadRows`](conversation-cache.md#the-contract)
is the single definition of what may reach disk, shared with `ConversationCache`'s own write path
so the two can never disagree; the wrapper only decides *when* to call it, and, since #1354,
`writeThread` itself applies `cacheableThreadRows` to what it is handed — see below.

**`observeMessages` hands `writeThread` the drawn rows, not the already-trimmed cacheable ones
(#1354).** The wrapper's own `cacheable` is still what it compares against `lastWritten` to decide
*whether* to write, but the call itself passes `drawn`, because `writeThread`'s own contract is to
do the trimming and to drop a saved history position when that trim moves the oldest kept row away
from it (see [Conversation cache § The thread document's two
writers](conversation-cache.md#the-thread-documents-two-writers-1354)). A first version of this
change kept passing `cacheable` here, which meant the cache never actually saw a thread get
trimmed — a verifier finding on PR #1470: the direct-to-cache test that wrote 100001 rows passed,
but a thread that reached the same size through this wrapper kept a position that no longer
matched the oldest saved row, a silent, permanent gap in a very long saved channel. The lesson: a
cache rule that depends on the shape of its input has to be tested through its real caller, not
only called directly with the shape the rule expects.

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
a conversation that was later re-created under the same id. `writeHistoryPosition` (#1354, below)
checks the same `deleted` set before it touches the cache, so a page settling after a delete cannot
bring the thread document back with a position either.

## The saved history position (#1354)

```kotlin
override suspend fun readHistoryPosition(conversationId: String): HistoryPosition? =
    cache.readHistoryPosition(serverId, conversationId)

override suspend fun writeHistoryPosition(conversationId: String, position: HistoryPosition?) {
    if (conversationId in deleted) return
    cache.writeHistoryPosition(serverId, conversationId, position)
        .onFailure { RelayLog.d { "event=history_position_write_failed" } }
}
```

Plain forwarding to the cache under this wrapper's own `serverId` — the same host scoping every
other override here uses — with the same two guards the row writer already has: skipped for a
conversation this instance deleted, and a failed write logged and swallowed rather than surfaced,
since losing a position costs only one re-fetched page on the next open. `ThreadViewModel` is the
only caller: it reads once at open, through `historySeed`, and writes only when a `requestHistory`
ask **settles** (a failed ask calls neither method, so the cache is never asked to touch a position
for one) — see [Remote conversation repository § Resuming from the saved
position](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354)
for that side. `decorateRepository` and the e2e `TappingConversationRepository` are both `by
delegate`, so both new members forward with no edit, the same reasoning `delete` and
`retrieveAttachment`'s own sections give for why those wrappers needed no change either.

**The gap-filling note above no longer holds once a position is saved.** This doc's intro to §
The merge base used to say a reconnect's history walk fills a gap left when more than one page
arrived while the app was offline. That was true only while every walk started from the newest
page. Once a position is saved, the first pull continues from older than the cached rows and never
returns to the newest page, so a gap above the cached base — left when the daemon's reconnect
replay buffer was exceeded or the daemon restarted — stays until the saved position is cleared
(`history.invalid_cursor`) or the conversation is removed. The ticket requires resuming from the
saved position and desktop behaves the same way, so this was accepted rather than fixed; whether to
fetch the newest page on open when the cached tail looks stale is an open product question raised
on PR #1470, with no follow-up ticket filed yet.

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
  cache);
- **a cold restore keeps a cache-only row in place (#983):** cache `[m1, a1, offer, a2]` under a
  live page `[m1, a1, a2]` draws, and writes back, `[m1, a1, offer, a2]` — both the drawn thread and
  the cache write-back are asserted, since the write-when-changed rule would otherwise persist the
  regression it was written to catch;
- an empty-then-page reconnect (disconnect, then a fresh page) keeps the same anchoring;
- a cache-only row with no anchor above it (an older row a newest page does not reach) still goes in
  front, matching the pre-#983 behaviour for that case;
- a sent row's names, dropped by the live side's history-replayed copy, come back through the cache
  merge's hint fill.

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

Further cases on the real `FileConversationCache` (#1354): a position written through
`writeHistoryPosition` survives a concurrent row write from `observeMessages` and reads back under
this wrapper's `serverId`; a deleted conversation's position write is skipped, the same `deleted`
guard the row writer already has; and a drawn thread trimmed at `MAX_CACHED_THREAD_ROWS` and
written through `observeMessages` itself drops its saved position — the regression test for the
verifier finding above, which fails if `observeMessages` goes back to pre-trimming before the
`writeThread` call.

One further case (#899): `retrieveAttachment` goes through a fake `AttachmentStore`-shaped fetch with
this wrapper's own `serverId` and the delegate's `fetchAttachment` as the fetch function — a wiring
regression guard, not a proof of the store's own behaviour (that lives in
[`AttachmentStoreTest`](attachment-retrieval.md#testing)).

Four further cases (#1353): a `FileConversationCache`-backed restore draws a banner, a compaction
divider and a model refusal in their original positions alongside a message and a boundary, offline
— pinning that the real file-backed cache round-trips the three kinds end to end, not only the fakes
above; and one case per kind where the live page re-delivers the same cached row (same identity,
different incidental fields) and it draws once, in place, through `mergeCachedRows`'s `alreadyHolds`
join rather than twice. `HistoryPageReducerTest.mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone`
pins the same join at the `joinIdentity()`/`heldAt` level (see § How the restore merges with live
rows above) for all six kinds, including that a refusal of the other frame type stays a separate
row.

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
  `mergeHistoryRows` and `mergeCachedRows`, the join and hint fill this restore shares with the
  history walk, and where the two merges' position rules diverge
- [Remote conversation repository § Status projections](remote-conversation-repository.md#status-projections-one-file-per-status-event) —
  `AttachmentOfferProjection`, the source of the cache-only offer row `mergeCachedRows` exists to keep
  in place (#983)
- [Dependency injection](dependency-injection.md) — `ThreadDestinationFactory.repository` wiring,
  `decorateRepository`, and the `useRelay` cache/attachments gates
- [Attachment retrieval](attachment-retrieval.md) (#899) — `AttachmentStore`, the host-keyed store this
  wrapper's `retrieveAttachment` delegates to: its layout, single-flight, bound and failure handling
- [Paired server store § Wiring & usage](paired-server-store.md#wiring--usage) and [Conversation
  cache § Removal on unpair](conversation-cache.md#removal-on-unpair--forgetremovedhost) — the
  sibling removal path, `forgetRemovedHost`, that this wrapper's `delete` does not go through
- [Ticket #1354](https://github.com/pyrycode/pyrycode-mobile/issues/1354) and its plan,
  `docs/specs/architecture/1354-saved-history-position.md` — the saved history position
  (`readHistoryPosition`/`writeHistoryPosition`, § above), the `observeMessages` → `writeThread`
  untrimmed-rows contract, and the accepted gap-filling change; see [Conversation cache § The
  thread document's two writers](conversation-cache.md#the-thread-documents-two-writers-1354) for
  the cache-side half
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); ticket
  [#797](../../specs/architecture/797-thread-row-cache.md) (this doc);
  [#798](../../specs/architecture/798-clear-cache-on-removal.md) (done — wires `delete` above to
  `removeConversation`)
