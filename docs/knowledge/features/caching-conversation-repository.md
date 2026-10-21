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

For snapshots requiring reconciliation, `observeThreadSnapshot` (also used by
`observeMessages`) merges through
[`mergeUnsignedCachedRows`](remote-conversation-repository-reads-and-thread-store-history-paging.md)
(`HistoryPageReducer.kt`), a sibling of the history walk's `mergeUnsignedHistoryRows` built for this
wrapper's own direction: paging normally prepends an *older* page onto what is on screen; here the
restored rows are the older set and the live projection is the receiver:

```
drawn = snapshot.rows.mergeUnsignedCachedRows(restoredWithoutSuppressedUserEchoes, baseOrder + snapshot.unsignedHistoryOrder, rendererOwners = lastDrawn)
```

Since #2050, validated cached rows can publish with an empty live snapshot and
its atomic suppression without waiting for optional history. The first nonempty
live snapshot still awaits saved history and resolves unsigned base order on the
injected processing dispatcher before merging. Hold metadata independently in
regressions: holding only the newest response cannot expose an indirect order-seed
wait. Repeated rows-only opens retain suppression and cancel pending ordering with
the observer; no early subscription or independent background scope is added.
See [cache snapshot ownership](conversation-cache-layout.md#thread-document-readers-1949).

Since #2153, the first empty, unsuppressed delegate snapshot of each collection
publishes the validated cache list directly through the existing processing boundary.
`readThread` already guarantees settled rows and unique renderer keys; repeating
atomization and key reconciliation before delivery added measurable cold-open cost.
The guard is collection-local so recollection reuses that collection's restored list,
with exact row objects/keys and the delegate snapshot's receipt/order metadata.
Generation allocation and deferred writer traversal stay in place. Initial nonempty
or suppressed snapshots and every later snapshot still take the reconciliation path;
an empty disconnect after live content must retain the last-drawn rebase.
See [first-draw comparisons and traversal controls](thread-screen-testing-saved-first-draw.md#initial-snapshot-reconciliation-and-the-later-draw-miss-2153).

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

See [connection-boundary merging](caching-conversation-repository-connection-boundary.md) for
rebasing, deliberate removals and the retained reconnect evidence.

## Bounded authoritative opening (#2333)

With the production negotiated host binding and `AuthoritativeWindowCache`, concurrent open,
acquire and observe callers share one newest **200 display-row** restoration (all if fewer).
`MigratingConversationCache.newestAuthoritativeWindow` keeps import admission and exact cache
lifetime ownership. The disk read proceeds independently of a held newest/catch-up reply;
normal coordinator-owned newest refresh and missing-local-data recovery remain active.
Cached facts never establish negotiated support. File caches and unbound repository fixtures
retain their full-restoration contract.

The window counts renderer contributions rather than raw facts: hidden/state-only facts consume
no row, while launch and content from one fact can consume two. Indexed selection includes
active inputs, queued/lost unordered feedback, recursively required parents and historical
anchor/held-tie witnesses outside the selected rows. Complete parent/witness facts must not
expose their unselected launch or content rows. Transient `openingRows` selects individual
contributions and filters block membership and first-draw read evidence; it is scoped to the
snapshot owner/generation, never persisted or used as certification. Accepted live revisions
admit their rows; older local pages extend this selection without exposing extras. See the
[storage selection and placement contract](conversation-cache-contract.md#bounded-authoritative-display-windows-2333).

Durable certification and hydrated display state are independent. A validated window seeds the
successful baseline with precisely its committed facts and metadata, excluding unsaved live
winners. Owned upserts preserve omitted disk facts. Later hydration extends the committed
raw/fence baseline without renewing checkpoint or continuous coverage; active/parent/witness
extras certify no range. Revision merge preserves newest raw objects, explicit clears, stable
identities and held order. A local rank, window size or page exhaustion is never a daemon
continuation or proof of continuity.

### Validation before delivery and persistence

Supplemental indexed reads resolve missing parents, relevant retirement fences and placement
witnesses against the **winning** live structure. Root order alone is insufficient: a loaded
witness can become hidden, move, change parent or retire, and task classification can change
through a cleared/invalid `task_id` or `task_link`. Valid background classification uses a
nonblank string task id, object task link and positive order, matching the renderer. Resolve
replacement witnesses before delivering that projection or admitting its writer candidate;
owned removal serializes resolution behind its durable mutation.

Initial supplemental failure leaves the captured window pending. Null/classified failed reads
withhold unchecked output and retain the last validated snapshot and baseline. Observation-owned
capped-backoff retries resolve only supplemental inputs, independently of another live mutation;
they never start a full-history read. Successful validation returns the exact atomically applied
capture. A changed live capture repeats resolution; stale work ends on reset, exit or retirement.
Final flush and removal acknowledgement use validated payloads and fences, so a successful
storage no-op cannot turn unchecked live content into a committed baseline.

Disk selection uses cache IO admission and a Room transaction; validation, winner maps, sorting,
merge and baseline preparation use `processingDispatcher`. Capture exact host/conversation,
authoritative epoch/incarnation, runtime generation and cache lifetime before suspension and
recheck before merge/save, including reused ids/epoch text. Exit cancels pending validation and
hydration; confirmed conversation/host deletion fences final saves while preserving colliding
identities on another host. See [writer custody](caching-conversation-repository-concurrency.md#state-and-concurrency).

### Older authoritative saved pages (#2334)

Reader movement and the existing explicit retry admit one local page of at most **200 additional
display contributions**, plus active inputs and required parent/placement witnesses. Indexed
local reads have their own observation-owned slot, so offline operation and held newest/catch-up
requests do not block them. Repeated movement during a read is dropped; completing a page,
reconnecting or receiving a network page never starts an automatic local walk. A failed local
read retains held content and its boundary for explicit retry; it cannot authorize a daemon
request. Exit, reset and confirmed deletion fence pending reads and final saves.

The cursor binds exact host/conversation, authoritative epoch/incarnation, disk generation and
content/launch identity. Storage retains a weakly owned, content-free seek bound for each live
cursor. Its owned writer already traverses metadata for changed components; it compares old
contribution positions with the pre-write reader bound and extends that bound to include their
new placement. The bound and new generation become visible only after the database transaction
commits. A rollback leaves reader state unchanged. This covers moved components beyond any
fixed predecessor lookahead, even when the boundary and nearer components remain unchanged.
It adds no whole-thread payload scan, payload lookahead or newest restart. Skip already admitted
content/launch identities before decoding selected payloads. Missing generation custody retains
retry; a write from a different storage instance cannot bless an old cursor. Production cache
readers and writers share one instance, and fresh reopen obtains a new committed cursor.

Fragmented saved groups remain readable without certifying the holes between them. Only a
successful local exhaustion forwards that reader demand to the existing authoritative
coordinator, whose certified continuous boundary owns daemon continuation. Local paging cannot
set daemon `olderExists` false, manufacture coverage or overwrite a daemon cursor. Hydration
extends only the successful committed baseline; owned writes preserve unloaded facts, raw
revision winners, explicit clears, held ordering and retirement fences. Read the
[storage continuation contract](conversation-cache-contract.md#authoritative-reader-continuation-2334)
and [counted paging evidence](thread-screen-testing-saved-first-draw.md#authoritative-reader-paging-2334).

## What is written, and when

**Supplied authoritative persistence (#2149).** Internal `observeAuthoritativeThread`,
`openAuthoritativeThread`, `removeAuthoritativeFact` and `resetAuthoritativeThread` seams share
the existing `ThreadWriter` persistence path.
With a supplied `RemoteConversationRepository` whose store accessor matches this host, the
wrapper uses that remote's authoritative store; otherwise it owns an offline store.
Production route activation and combined live proof remain with #2245; the stable-delegate
legacy route described below is unchanged.

Observation restores the [separate authoritative family](conversation-cache-contract.md#the-contract)
and captures committed raw facts plus epoch, applied progress, checkpoint and continuous coverage.
The production negotiated binding restores a bounded display window; compatibility-only bindings
retain the full read.
Ordinary Room saves use `AuthoritativeUpsertCache` through `MigratingConversationCache`: under
`historyWrites`, `persistAuthoritative` selects changed identities against the shared successful
candidate, or the collection's restored baseline. Omission preserves unloaded facts. Metadata-only
certification and placement-only changes also persist atomically. File caches and doubles without
the capability retain full replacement; import admission and host retirement still apply.
Bounded production opening is described above (#2333). Explicit owned removal (#2256) uses a store-minted
capture of one authoritative identity, binding the minting store, exact host/conversation,
epoch/incarnation, runtime generation and held revision. Partial omission, hydration and settlement
filtering never mint removal intent. Removing a parent does not cascade to its children.
The same 100 ms quiet period,
replaceable pending slot and serialized writer serve these candidates; reset generations bypass
the delay. Ordinary delivery remains independent of held disk I/O. Failed saves keep durable facts,
metadata and the successful baseline intact, enabling next-emission or final-flush retry.

Removal atomically persists its identity fence with deletion and conservative metadata through
that same path. The fence filters delayed candidates, replay and restore across disk reopen.
Checkpoint stays zero and coverage null while any fence exists, even after replay completion;
applied progress remains monotonic within the same epoch/incarnation. Explicit reset alone starts
a new incarnation and clears fences, including with reused epoch text. Replacement-only caches
receive `resetAuthoritativeThread`, rather than ordinary replacement, for that operation.
The migration-aware Room fallback preserves import-observation and host-retirement admission
before its atomic reset. Room/file replacements retain committed fences, reject stale incarnations
and independently reject new fences captured before a newer durable revision. These seams add
no daemon verb, UI action or negotiated capability.

Open restores before asking the remote query dispatcher. Reconnect resumes only the certified
checkpoint, so an interrupted reply may leave newer full items saved alongside the prior
checkpoint/coverage. Offline restore establishes neither connected-daemon capability nor current
transient progress; protocol selection still requires current connection negotiation.

**Legacy row persistence (#2253).** Candidates come from the reconciled thread as drawn,
with [`cacheableThreadRows`](conversation-cache-contract.md#the-contract) defining disk eligibility.
The production `MigratingConversationCache` supplies `OwnedLegacyCache`: `ThreadWriter` sends
changed owned rows with observed neighbour anchors and deliberate removals through its import-aware
Room mutation path. An incomplete view preserves unobserved saved rows and unrelated evidence.
Changed/removed producing claims are invalidated atomically; held identities and ordinals survive.
This changes no negotiated route or legacy-fallback verdict and activates no partial reader.

Removal intents compare successive observed raw drawn views: a previously cacheable identity
absent from the next raw view can be removed, including confirmed projection removals and
suppressed queued echoes. An unsettled row still present in raw rows cannot be removed merely
because cache policy excludes it. Its later disappearance on disconnect also supplies no removal
intent. Intents survive coalescing/failure until settled reappearance or a successful newer fence
supersedes them; subtracting a partial candidate from disk would wrongly delete unowned history.

Caches without this capability retain whole-thread replacement. The wrapper passes **untrimmed
drawn rows** to `writeThread`, comparing cacheable rows only to decide whether to write (#1354).
Otherwise the cache cannot detect retention loss and reset stale cursor/completeness claims, as
the real-caller regression on PR #1470 demonstrated. Owned Room mutations enforce the same cap
from the saved header count, selecting oldest excess rows and invalidating affected evidence.
Coverage saves use the same owned row capability plus selective received-evidence updates
(#2254); broad replacement APIs and file compatibility remain. See the [owned storage contract and counted
restart/mutation results](conversation-cache-legacy-mutations.md#owned-incremental-legacy-mutations-2253).

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

See [restore, writer and retirement concurrency](caching-conversation-repository-concurrency.md#state-and-concurrency)
for generation checks, successful baselines, cancellation and final-flush ownership.

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

**Coverage saves commit owned rows before received evidence (#2254).** Capture the delegate
snapshot at emission, then hold `historyWrites` across reconciliation, row mutation, successful
bookkeeping and the separate evidence write. `OwnedLegacyCoverageCache` reconciles successful
held rows, pending intents, suppression and durable order. Changed cacheable renderer identities, neighbour anchors and deliberate
observed removals go through `mutateLegacyRows`. Unloaded rows and unrelated evidence survive;
held identities keep ordinals. Localized saves reuse restore/metadata observations. File caches
and replacement-only doubles retain untrimmed `writeThread` followed by bound history replacement, including trim accounting.

After row success, publish the actual successful row baseline, capture satisfaction and incremented
`coverageRevision` together in `NonCancellable`, and retire satisfied intents. Failed rows log
`history_rows_write_failed`, advance none of these and attempt no evidence. Evidence commits
separately: its failure logs `history_position_write_failed`, keeps receipt differences retryable
and leaves only prior conservative claims after row invalidation. It cannot undo successful row
bookkeeping, including cancellation after commit. Claims require committed rows.

**Capture order and successful completion are separate fences.** Older collectors and reconnects
cannot renew unchanged stale content. Satisfaction covers only contributing captures; newer
accepted changes/removals remain eligible. Observers capture the successful coverage revision at
entry before restoration can suspend. Coverage completed before entry does not itself turn an
unchanged empty/streaming-only failed restore into write work. A coverage row success during the
collection admits a newer deliberate removal even when its rows match the original restore,
even after evidence failure. Ordinary success preserves coverage revision/satisfaction while
advancing shared rows. Neither fence is a wire/disk field.

Observed received positions are remembered separately from successful rows. Seed from existing
restore/metadata reads; advance receipt memory only after evidence success, retaining omitted
unowned facts. Compare typed claims and intervals before serialization. Supply the current
position, previous observation and observed rows to `writeOwnedLegacyCoverage`; absence outside
ownership authorizes no deletion. Null position explicitly clears metadata without deleting rows,
under the same mutex/tombstone guard; a sentinel prevents delayed metadata reads reseeding the
cleared baseline.

Proofs bind exact committed rows/deltas and non-overlapping fragment slices, bounds and hashes in
unsigned durable order through `ULong.MAX_VALUE`. Delta matches prove retention, not completeness.
Stale owned proofs withdraw claims while keeping
rows readable; a changed/new unbound producer outside row ownership rejects evidence until its
row settles. Connected producing-entry invalidation accounts for all siblings before deletion,
including losses created in this transaction. Additive claims preserve unchanged sibling bindings;
recovery validates every relevant sibling before clearing that producer's loss. Repeating received
spans cannot restore invalidated certification. Unidentified retention loss persists independently
of producer claims and requires explicit metadata-clear/full-replacement recovery plus newly
received coverage; incremental saves cannot restore the discarded cursor/completeness.

Span extension and producer subtraction preserve an existing received gap anchor/walk while the
anchor belongs to the retained older span and the edge remains the adjacent newer span's start.
Invalid anchors lose their walks. Receipt differences skip unchanged facts, so row invalidation
must preserve valid progress itself. Only relevant committed bounds can admit replay of received
recovery anchors. Local continuation/exhaustion creates no daemon cursor, received continuity or
server exhaustion. Evidence commits invalidate old local tokens; recover by traversal from newest.
See [owned coverage and Linux evidence](conversation-cache-legacy-mutations.md#owned-coverage-and-received-evidence-2254)
for loss recovery, 135 passed focused checks, bounded 20/18000-row work and configured-gate results.

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

See [repository wiring](caching-conversation-repository-wiring.md#wiring--under-decoraterepository-not-in-it)
for composition, attachment storage and context-free test containers.

## Testing

`AuthoritativeWindowRestoreTest` uses fresh disk/cache/repository/ViewModel instances through
production destination selection over 18000 displayed rows. Its 25 probes cover exact selection,
extras, placement, relevant fences, demand forwarding, held restoration/live overlap, unavailable
validation/recovery, certification, reset, exit, deletion and colliding hosts. Keep these alongside
owned-upsert/removal and final-retirement regressions: native latency alone cannot establish
placement or writer correctness. [Counted current acceptance](thread-screen-testing-saved-first-draw.md#bounded-database-and-retained-file-acceptance-2333)
records these full-suite passes and the separate native measurements.

See [Caching conversation repository — testing](caching-conversation-repository-testing.md) for worker scheduling, restore, reconnect and coalesced persistence coverage.

The [retained #2255 focused evidence](conversation-cache-contract.md#retained-authoritative-mutation-and-restart-evidence)
records 149 Linux passes at `a0d7f4467`, zero failures/errors/skips, covering owned upserts,
writer cleanup, retry, cancellation, reset, deletion and exact restart state.

The [#2256 retirement evidence](conversation-cache-contract.md#retained-explicit-retirement-evidence-2256)
records builder-reported 105 focused Linux passes at `3a9a1fa2e5e5b13cdb40bf5da0b79a33f01400bd`
and the accepted verifier's independent count of the same 105 affected methods in its full JVM
run, with zero affected failures/errors/skips. It distinguishes unavailable original focused XML
from the retained verifier report, names the fallback-reset, pending-rejection and recovery-lifetime
probes, and preserves the unrelated full-suite failure/retry. Documentation produced no test runs.

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
- [Remote conversation repository § Status projections](remote-conversation-repository-status-projections.md#status-projections-one-file-per-status-event) —
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
