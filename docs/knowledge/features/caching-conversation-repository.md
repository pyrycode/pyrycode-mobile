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

**The observer caches rows that reach its collection; coverage saves also reconcile and write rows (#1832).** A row
the daemon delivers to a thread nobody is observing — the operator is looking at another channel, or
another host's thread — is not written to the cache unless a history-position save explicitly
reconciles it into this destination's cache. A reconnect rebuilds the projection that briefly held that
row, and it is gone: the replay cursor has already advanced past it, so Mode A replay does not resend it
either. [#1572](https://github.com/pyrycode/pyrycode-mobile/issues/1572) does not change this wrapper —
it recovers the row a different way, by having `ThreadViewModel` ask for the newest history page every
time an *open* thread's host becomes available, so the daemon re-delivers what this cache missed.
Since #1832 the client retains that page's durable coverage and
uses reader-targeted gap walks to fill intervening omissions lazily. See
[Thread screen § the oldest-end history
demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777) for that ask.

## Contract

```kotlin
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
    private val attachments: AttachmentStore? = null,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationRepository by delegate, ThreadSnapshotSource {
    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot>
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
    override suspend fun delete(conversationId: String)
    override suspend fun retrieveAttachment(conversationId: String, attachmentId: String): AttachmentRetrievalResult
    override suspend fun readHistoryPosition(conversationId: String): HistoryPosition?
    override suspend fun writeHistoryPosition(conversationId: String, position: HistoryPosition?)
}
```

Kotlin class delegation (`by delegate`) means every member except the thread observers, `delete`
(#798), `retrieveAttachment` (#899) and the saved history position (`readHistoryPosition`/
`writeHistoryPosition`, #1354, see § The saved history position below) is plain pass-through — stall, queue, API retry, compaction,
thinking, usage limit, modals, archive, unarchive and every other one-shot keep their live-only
behaviour unchanged. Nothing restored can reopen a permission prompt or restart an indicator,
because those live-only observables are not restored. Archive and unarchive deliberately
stay delegation: they are not removals, so neither can reach a cache-clearing path (see [Conversation
cache § Removal on unpair](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost) for the
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

`observeThreadSnapshot` (also used by `observeMessages`) merges through
[`mergeUnsignedCachedRows`](remote-conversation-repository-reads-and-thread-store-history-paging.md)
(`HistoryPageReducer.kt`), a sibling of the history walk's `mergeUnsignedHistoryRows` built for this
wrapper's own direction: paging normally prepends an *older* page onto what is on screen; here the
restored rows are the older set and the live projection is the receiver:

```
drawn = snapshot.rows.mergeUnsignedCachedRows(restoredWithoutSuppressedUserEchoes, baseOrder + snapshot.unsignedHistoryOrder, rendererOwners = lastDrawn)
```

`mergeUnsignedCachedRows` shares `mergeUnsignedHistoryRows`'s join (ordinary logical id for a message, covering a
`tool_use_id` and a `turn_id`; the `(previousSessionId, newSessionId, occurredAt)` triple for a
boundary) and its [attachment-reference hint fill](remote-conversation-repository-reads-and-thread-store-history-paging.md),
so a restored row the daemon re-delivers is never drawn twice and a sent row's names come back even
when the live side's replayed copy has none. Merging into an empty live projection returns the
restored rows after suppression filtering. The disconnected case falls out of the same merge
that handles a reconnect. Where it differs from `mergeUnsignedHistoryRows`: a row *only* the cache
holds does not always go to the front. It goes right after the live copy of the nearest cached row
above it that the live side also holds, and, without durable order, goes in front when it has no such anchor — the
older rows a reconnect's newest page does not reach, or a page that does not overlap the cache at
all. Several cache-only rows sharing one anchor keep their cached relative order.

Since #1786 both merges share one reconciliation. A cache-only row can also go between two shared rows,
and cache-only assistant text merges per `(turnId, seq)` delta, so a restored reply missing a middle or
suffix sequence gains only the missing text, on the correct side of tool and user rows. A legacy whole-turn
row written before segments existed dedupes only text it demonstrably contains and keeps distinct text.
Since #1910 restored coverage supplies exact unsigned row/delta ordering through
`receivedUnsignedHistoryOrder(coverage.unsignedPositions())`, combined with the live snapshot's
`unsignedHistoryOrder`. Both the observer merge and the position writer's fallback merge need
it: a disjoint older-gap page with no shared row anchor otherwise lands on the wrong side of cached
content, and equal timestamps cannot repair that. `ThreadSnapshot` forwards rows, suppression and
order from the same projection generation. Shared neighbours and timestamps remain fallback evidence
for rows without durable order; live rows are never sorted. The lookup stays key-indexed on large
threads. The fixed connection merge base and
the deliberate-removal suppression below are unchanged, so a removed live row is not resurrected.

Renderer ownership is independent of that fixed content/placement base (#1941).
`observeThreadSnapshot` supplies `lastDrawn` as owner claims; coverage writes supply the selected
drawn base or file fallback. Claims reserve ids only for identities surviving reconciliation,
before live receiver claims and newcomer allocation, including empty and overlap-only merges.
They supply no content, admission or placement and cannot resurrect a removed row. A fresh,
unseeded projection can hold an unmatched legacy row whose bare id belongs to a displayed cached
opening segment: treating the fresh live receiver as the only owner would transfer the reader's
key to the newcomer. See [segment allocation](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350).

Renderer collisions never decide admission (#1979). A cached assistant delta `(turnId, seq)`
and an ordinary user row with the same renderer id survive together in either arrival direction,
even when their text is identical. Typed `mergeIdentity` admits missing identities and suppresses
replayed identities before `withUniqueMessageKeys` allocates keys. The displayed cached segment
keeps its key; an aliased ordinary row keeps its original `ordinaryId` through `reconciliationId`,
so replay of the unaliased row finds it again. Occupied alias candidates remain with their owners.
Allocation cannot discard content or change retained-row order; neighbour and unsigned durable
bounds still determine placement. See [ordinary identity](data-model.md#message).

Unsigned positions preserve ordering across the signed boundary and through `ULong.MAX_VALUE`,
including held rows on both sides of an unresolved gap and split assistant deltas. The signed
restore adapter and unsigned adapter share one identity resolver for delta splitting and hashed
identity lookup; a change to one path must not silently alter which logical rows the other finds.

`BackgroundTaskLifecycle` (#1782) is also retained in the last-drawn **in-memory** base at a
connection boundary, although disk restore never supplies it. Reconnect backfills missing launch
fields and retains one launch/finish identity per conversation/task without moving held markers.
After ordinary cache-only rows have been placed, fresh lifecycle evidence uses the same
[neighbour fold as history](remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645):
leading evidence waits for its first overlapping neighbour, and subsequent anchors cannot move
backward. History and cache merges keep their different ordinary-row placement rules.

Overlap deduplication must retain anchors even when incoming assistant text is discarded or keeps
only an older prefix. `ThreadRowAnchors` indexes typed `mergeIdentity`, so an aliased user anchors
through ordinary identity rather than a same-key segment. For an original segment spanning several
retained rows, leading lifecycle evidence precedes its earliest represented neighbour and trailing
evidence follows its latest. Preserve that combined range: atomizing lifecycle input can place a
leading marker at a later retained row before another sequence supplies the earlier neighbour.
Proven legacy sequence records attach through ordinary identity to the original assistant row
before lifecycle placement; segments superseded by a whole turn resolve to that surviving whole turn.
Keep leading evidence, backward anchors, differently keyed segments, whole-turn overlap in both
directions, terminal-before-start, replay and older-page prepend in the lifecycle regressions.

The enabled `CoalescedThreadWritesTest.identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect`
checks direct admission, observation, empty disconnect/reconnect while a write is pending, ordinary
identity and successful fresh-file restore with stable keys/order. `HistoryMessageIdentityTest`
adds both arrival directions and replay (`identityInvariant_unsignedCacheCollisionRetainsBothArrivalDirectionsAndReplay`),
occupied aliases (`ownershipInvariant_unsignedCacheCollisionPreservesDisplayedSegmentAndOccupiedAliases`),
neighbour/durable bounds (`placementInvariant_unsignedCacheCollisionKeepsNeighboursAndDurableBounds`),
ordinary lifecycle anchors (`placementInvariant_unsignedCacheLifecycleAnchorsUseOrdinaryIdentityBesideSameKeySegment`),
combined ranges (`placementInvariant_lifecycleUsesCombinedSplitSegmentRange_inHistoryAndCacheReplay`)
and fresh restore/replay (`reconnectInvariant_unsignedCacheCollisionSurvivesFreshRestoreAndReplay`).
The lifecycle probes cover both unsigned history and cache lanes without moving retained rows.

[PR #2023's verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/2023#issuecomment-6090873234)
and its fresh unit XML confirm all these methods executed and passed in the full unit run:
5,156 executed/passed, 0 failed/errors/skipped. The four acceptance suites contributed 97 passes
(17 identity, 22 reconciliation, 21 unsigned history, 37 coalesced writes). This is full-suite
evidence, not a separate focused run; no scripted or real-Claude pass is claimed.

The evidence remains invisible: `foldQueuedRows` excludes it before tool grouping/rendering,
`ThreadProjection.observeRowCounts` excludes it from visible-growth signals, and Channel info's
creation timestamp skips it. Scalar lifecycle writes preserve queue/echo bookkeeping through the
same atomic `ProjectionState`. Process death loses these markers; reconnect/history can reconstruct
them. The disk schema stays unchanged, and [cache filtering and trim accounting](conversation-cache-layout.md#the-thread-documents-two-writers-1354)
both exclude them, so evidence alone cannot clear a saved history position.

For queue delivery (#1642), `ThreadSnapshotSource` supplies visible rows and
`suppressedUserMessageIds` together through Remote → Stable → Caching. The cache filters only
restored `Role.User` messages whose ordinary logical ids are suppressed while awaiting a delivered push.
Missing live rows alone never justify deleting unrelated offline history or cache-only rows.
The original base remains available for attachment hints when delivery arrives; suppression
is connection-local and is never serialized. Repositories without this contract retain the
list-only fallback with empty suppression. Renderer aliases must never shield a suppressed echo
from filtering, either in observation or history-position writes.

Rows and suppression must come from the same `ThreadProjection.ProjectionState` generation.
Independent StateFlows could pair old tap-time rows with newly cleared suppression during
reopen, briefly show the echo before intervening tools, and persist that wrong order. Delivery
now establishes live position and clears suppression in one atomic fold. Wrapper tests with a
real file cache cover local/peer Send now, reopen, removal → tools → push, duplicates and offline
history retention; `VerifierSnapshotRaceTest.reopenDuringDelivery_neverEmitsUnsuppressedTapTimeRows`
controls the subscription/delivery interleaving that ordinary final-order assertions missed.

**Keep per-emission work indexed and off the collector.** Raising
`MAX_CACHED_THREAD_ROWS` from 200 to 100000 (#1353) exposed the old quadratic
cache/live lookup: the fix indexed live identities once rather than scanning all live rows
for every cached row. The current unsigned merge retains indexed identity/order lookups.
A larger retention cap changes the cost of every live update, including streaming deltas,
not only disk writes.

Before [#1966](../../specs/architecture/1966-cached-thread-worker.md), observer processing
inherited the collector dispatcher, normally `Main.immediate` through the ViewModel.
Restore-order lookup, suppression filtering, merge/rebase and cache-policy filtering/comparison
now use the injected `processingDispatcher`, defaulting to `Dispatchers.Default`.
Moving only the merge would still leave long-list equality on the collector: both the
snapshot observer's cache-policy comparison and the list-only observer's distinct
comparison run on the worker. Worker scheduling is separate from write coalescing and UI
pacing; the controlled probes establish scheduling and main progress, not measured device
frame times.

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

The base also retains durable ordering: restore seeds `baseOrder` from persisted coverage, and
a connection boundary combines it with the last live order. Each merge receives
`baseOrder + snapshot.unsignedHistoryOrder`. Rows and ordering therefore survive reconnect together.
Drawn rows are emitted and retained in `drawnThreads` before a cache write. The observer hands
untrimmed `drawn` to `writeThread`, under the shared `historyWrites` mutex with a tombstone check
inside the lock. Only a successful write advances the shared persisted baseline; static failure
logging exposes no rows, ids or cursors.

An empty visible snapshot with nonempty suppression is a pending-delivery reading within the
same connection, not a disconnect. Rebasing there would lose the fixed restore base and its
attachment metadata. The filtered drawn rows are emitted and cached, so reopening cannot
resurrect a hidden queued echo before its delivered push.

After the rebase, `cacheableThreadRows(drawn)` matches the previous accepted candidate, so
a disconnect creates no new settled change or quiet-period delay. Already pending rows still
persist, and an earlier failed write remains retryable. `settledThreadRows` (not
`cacheableThreadRows`) is the rebase's own function: it drops only in-flight rows and applies no row-count bound, so a thread longer than
[`MAX_CACHED_THREAD_ROWS`](conversation-cache-contract.md#the-contract) does not visibly shrink on screen
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
writing it alone would shrink the cache. [`cacheableThreadRows`](conversation-cache-contract.md#the-contract)
is the single definition of what may reach disk, shared with `ConversationCache`'s own write path
so the two can never disagree; the wrapper only decides *when* to call it, and, since #1354,
`writeThread` itself applies `cacheableThreadRows` to what it is handed — see below.

**`observeMessages` hands `writeThread` the drawn rows, not the already-trimmed cacheable ones
(#1354).** The wrapper compares its `cacheable` rows against successful persisted rows to decide
*whether* to write, but the call itself passes `drawn`, because `writeThread`'s own contract is to
do the trimming and to drop a saved history position when that trim moves the oldest kept row away
from it (see [Conversation cache § The thread document's two
writers](conversation-cache-layout.md#the-thread-documents-two-writers-1354)). A first version of this
change kept passing `cacheable` here, which meant the cache never actually saw a thread get
trimmed — a verifier finding on PR #1470: the direct-to-cache test that wrote 100001 rows passed,
but a thread that reached the same size through this wrapper kept a position that no longer
matched the oldest saved row, a silent, permanent gap in a very long saved channel. The lesson: a
cache rule that depends on the shape of its input has to be tested through its real caller, not
only called directly with the shape the rule expects.

Since [#1967](../../specs/architecture/1967-coalesced-thread-writes.md), a collection-owned
writer accepts immutable candidates after downstream emission returns. Cache-policy filtering
and comparison run on `processingDispatcher`; a suspended downstream consumer still delays
acceptance, but disk I/O no longer delays processing or delivery of subsequent snapshots.

Settled changes separated by less than 100 ms form a burst. After 100 ms without a changed
cacheable candidate, the writer persists the latest rows. It holds one replaceable pending
candidate rather than a queue of whole-thread writes. A running write may finish first; newer
pending candidates replace each other while it runs. Returning to the successful persisted rows
also replaces obsolete pending work and can avoid I/O altogether.

Unchanged or streaming-only updates do not restart the delay. Opening offline creates no work
when the drawn rows equal restoration; streaming/running rows alone trigger no persistence.
Disconnect rebases retain the accepted settled rows without creating a new change. A coverage
save completed during this collection can make a later removal eligible even when it matches
restoration; the lifecycle rule is explained under [the saved history position](#the-saved-history-position-1354).

A failed write logs only `event=thread_cache_write_failed`, leaves the successful baseline
unchanged and enables retry on the next snapshot, including an unchanged one, or on final flush.
It does not retry autonomously while idle. No rows, ids, cursors or exception details enter this
event. Successful storage alone advances the comparison baseline shared by both row writers.

## State and concurrency

Each cold snapshot flow owns its merge base, ordering, last drawn rows and a structured
`coroutineScope` with a writer child on `processingDispatcher`. Sequential worker hops finish
processing each captured snapshot before taking another; merge generations are never conflated
or cancelled in favour of newer input. Only pending disk work is conflated. The collection's
bounded timer signals readiness; it does no merge or cache-policy work. No application or
repository scope owns the writer.

Only rows are replaced in the emitted snapshot; suppression, unsigned order and read evidence
remain those of the captured generation. Restored rows create no sight claims, history requests
or read commands. The list-only observer compares rows on the worker and emits distinct lists
on the collector. Snapshot generations are allocated at upstream capture, before worker
processing can suspend; held drawn rows carry that generation into coverage saves.

The wrapper's `historyWrites` mutex serializes observer writes, coverage-null position writes,
the complete coverage row/state operation and confirmed deletion. Lock order is wrapper then
file cache, with no callback into the wrapper. Both row writers publish their successful
`persistedThreads` baseline under this lock; it survives collection restarts. A writer also
remembers its completed candidate by identity, so cleanup cannot repeat that completed write
after another collector persists newer rows. Later candidates still compare against the shared
successful rows, not an obsolete collection-local baseline.

Lock acquisition and snapshot processing remain cancellable. Once either row writer starts a
mutation, the mutation and its successful baseline update finish together in `NonCancellable`
under `historyWrites`. Atomic replacement can commit before a cancellable dispatcher return
delivers success: joining a cancelled writer alone would otherwise leave disk and baseline
inconsistent and could skip a newer removal during flush. Failed writes update no baseline.

Normal upstream completion, upstream failure and collector cancellation run non-cancellable
cleanup: cancel/join the timer and writer, then attempt the latest accepted pending candidate
once unless already completed, persisted, satisfied by coverage or deleted. Cleanup waits for
actual I/O, leaves no orphan writer and never loops on storage failure. Cancellation then
propagates. A snapshot interrupted before acceptance creates no flush work. This guarantees
orderly cleanup with successful storage, not persistence through force-stop or process death.

## `delete` — removing the cache alongside the daemon (#798)

The delegate deletes first; refusal propagates before touching cache state. After success the
wrapper marks its thread-safe destination-local tombstone, then waits non-cancellably for
`historyWrites`, clears held drawn and persisted-generation metadata and removes the cached
conversation under this wrapper's host id. A failed removal logs a static event and does not turn daemon success into a
reported deletion failure. Archive/unarchive remain delegation.

**A pre-I/O tombstone check is insufficient (#1832).** A writer can pass it, suspend, and recreate
rows after removal. Scheduled writes and final flushes check the tombstone under the shared
mutex; deletion waits for an in-flight non-cancellable mutation, then removes its results.
This includes fallback row reads, observer writes, coverage-null writes and the interval between row and state writes. Once removal returns,
suspended writers cannot recreate the document. A fresh destination has a fresh tombstone set.

## The saved history position (#1354)

`readHistoryPosition` uses the wrapper's host namespace. Since #1832 it also reads whether cached
rows exist: an empty cache without unsigned spans or sticky `unsignedIncomplete` returns no
position, ignoring an old cursor/stop. A nonempty legacy cache with null coverage returns
`HistoryCoverage(unknown = true)`, with or
without saved `atStart`, while keeping its rows readable. Neither row identity nor that stop
certifies received entry ids. After the newest page the marker moves with the verified older
edge; matching legacy text and verified overlap leave unknown coverage unresolved. Only
`at_start`, including an empty terminal page, closes unknown coverage without an older durable
anchor. Unsigned spans, gaps, row/delta producing-entry claims, cursor/walk anchors and unknown
state round-trip with the position; `unsignedHighWater` derives from unsigned spans. Existing
positive signed metadata remains readable, and malformed optional metadata falls back to legacy
unknown without hiding rows. Signed readers expose only representable evidence. Upper-range
evidence keeps their `unknown`/`unsignedIncomplete` true and saved `atStart` false even after a
terminal page; the authoritative unsigned claims and `unsignedUnknown` remain independent.
See [history resumption](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354).

**Coverage saves write reconciled rows before state.** `writeHistoryPosition` obtains the current
delegate snapshot, then holds `historyWrites` across selecting held drawn rows (or a fallback
file read), suppression filtering, the durable-order-aware merge, `writeThread`, and the state
write. If the row write fails it logs `history_rows_write_failed` and returns without advancing
state. Coverage binds to exact retained row/delta proofs; the file writer validates these against
stored rows again. Interruption between writes leaves older conservative state, never new claims
for rows absent from storage. Excluded transient/raw-envelope rows stay excluded; only metadata
persists. Delta matches inside legacy whole turns prove retention, not completeness.
Their whole-row proof alone is insufficient: restore and stale binding also require bounded,
non-overlapping slices in unsigned durable order with matching fragment hashes. Removing a
maximum-id producer terminates the retained interval rather than wrapping its successor to zero.

**Capture order and save completion answer different questions (#1967).** After a successful
coverage row write, `persistedThreads` records its actual cacheable rows and satisfies observer
candidates through the maximum of the history snapshot's capture generation and the selected
drawn base's generation. A candidate captured before that boundary cannot rewrite newer rows
or coverage merely because its worker resumes later. Later drawn generations remain eligible.
Observer successes replace the shared row baseline while preserving coverage satisfaction;
otherwise resubscription could compare against older coverage rows and skip a needed write.

Each successful coverage row write also advances `coverageRevision`, even if the subsequent
position write fails or is cancelled. The observer captures that successful revision at entry,
before restoration can suspend. Retained coverage completed before entry cannot alone turn an
unchanged empty or streaming-only failed restore into a write. A save completed during restoration
can supersede this collection's candidate, even if captured before entry: a later generation
that intentionally removes its rows must remain eligible. Changed candidates and failed-write
retries remain independent of this exception. Capture generations reject stale work; successful
completion revisions distinguish overlapping saves from already retained coverage. Neither is a
wire or disk field. Row success publishes rows, satisfaction and revision together in the
non-cancellable section described above; position failure cannot undo that successful row write.

The complete operation passes untrimmed rows and uses shared cache-policy trim accounting. If
rows exceed the cap, the later position write also resets backwards cursor to empty and `atStart`
to false while retaining validated, conservative coverage. Otherwise a state write could undo the
row writer's reset: a direct row-writer test misses this production interleaving. Removed or changed
rows invalidate all their producing entry claims. Coverage-null writes use the same mutex and
tombstone guard, preserving default-tolerant repository behavior.

This replaces #1354's accepted position-before-row window and unresolved saved-position gaps.
Newest asks preserve coverage and queue behind outstanding asks; reader pulls fill one targeted
gap page at a time without changing the ordinary backwards walk. Cursor refusal preserves gap
metadata while resetting only the refused walk. Failures log static events without cursors, entry
content or row proofs.

[#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833) proves the saved position across
real process death. The external force-stop proof stops the actual app process without clearing
data, posts while it is dead, relaunches it under a new PID and finds the post drawn once without
scrolling, so the cached rows and saved position survived and only the newest page was needed. The
live and scripted gap proofs keep the cached baseline rows readable through reconnect while reader
pulls fill the durable gap. See the [#1833
evidence](../../e2e-interactive-stream.md#verification-status).

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

See [Caching conversation repository — testing](caching-conversation-repository-testing.md) for worker scheduling, restore, reconnect and coalesced persistence coverage.

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
  cache § Removal on unpair](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost) — the
  sibling removal path, `forgetRemovedHost`, that this wrapper's `delete` does not go through
- [Ticket #1354](https://github.com/pyrycode/pyrycode-mobile/issues/1354) and its plan,
  `docs/specs/architecture/1354-saved-history-position.md` — the saved history position
  (`readHistoryPosition`/`writeHistoryPosition`, § above), the `observeMessages` → `writeThread`
  untrimmed-rows contract, extended by #1832's durable coverage and row-before-state ordering; see
  [Conversation cache § The
  thread document's two writers](conversation-cache-layout.md#the-thread-documents-two-writers-1354) for
  the cache-side half
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); ticket
  [#797](../../specs/architecture/797-thread-row-cache.md) (this doc);
  [#798](../../specs/architecture/798-clear-cache-on-removal.md) (done — wires `delete` above to
  `removeConversation`)
