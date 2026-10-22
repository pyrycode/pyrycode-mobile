# Caching conversation repository — authoritative offline content

## Contract

`CachingConversationRepository` wraps the host's stable repository and forwards its
`NegotiatedThread` with authoritative restore, observation and older demand. Offline
facts never negotiate support. Runtime snapshot/list observers, raw row merging,
legacy coverage/cursors and read/write-history-position APIs were removed in #2367.
No active consumer reads or writes the legacy cached-row family. Serialized storage
and its backend remain detached until #2364; their tests are storage history, not a
runtime compatibility contract.

## Restore and ownership

Observation reads saved authoritative facts, certification and retirement fences.
A pristine store can adopt saved certification; merge retains newer live revisions
and both held sides without inferring order. Generation, epoch/incarnation, exact
host/conversation and cache lifetime are checked after suspension. Disk cannot
supply current prompts, progress, pending sends or configuration readings.

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
admit their rows; full reader hydration clears this selection. See the
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

Native acceptance follows the [approved authoritative mapping](authoritative-reader-paging.md#approved-native-acceptance-mapping-2447); legacy projection/span measurements remain historical.


Reader movement and the existing explicit retry admit one local page of at most **200 additional
display contributions**, plus active inputs and required parent/placement witnesses. Indexed
local reads have their own observation-owned slot, so offline operation and held newest/catch-up
requests do not block them. Repeated movement during a read is dropped; completing a page,
reconnecting or receiving a network page never starts an automatic local walk. A failed local
read retains held content and its boundary for explicit retry; it cannot authorize a daemon
request. Local Loading/Retry belongs to the exact observation and runtime generation. A reset,
including reused epoch text, clears that state and obsolete cursor/work before delivering the
new snapshot. Replacement clears the old status; old completion or cleanup cannot clear its
new owner's state. Reader identity and cancellation are checked under the store monitor before
store and persisted-baseline application; registration and cleanup share that monitor. Same-generation failed-boundary retry is retained. Daemon queries keep their
existing coordinator slot; they do not set the legacy/local Loading tail. Exit, reset and
confirmed deletion fence pending reads and final saves.

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
Production uses the forwarded negotiated host route; no legacy observer remains.

Observation restores the [separate authoritative family](conversation-cache-contract.md#the-contract)
and captures committed raw facts plus epoch, applied progress, checkpoint and continuous coverage.
The production negotiated binding restores a bounded display window; other authoritative bindings
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

Ordinary candidates coalesce for a 100 ms quiet period using one replaceable pending
slot. Reset generations bypass that delay. Saves and successful baseline publication
finish atomically under the shared write lock; failed writes advance no baseline.
Collection cleanup joins owned work and flushes the latest accepted candidate,
rechecking reset/removal fences. This guarantees orderly cleanup, not persistence
through force-stop. See [concurrency](caching-conversation-repository-concurrency.md).

## Deletion and wiring

Daemon deletion must succeed before the destination tombstone is set. Writers check
that tombstone under the write lock; deletion waits for admitted work and removes
cached conversation data. Refusal preserves it, and another host's identical id is
unaffected. Archive/unarchive remain delegation. Attachment retrieval continues
through the exact host's `AttachmentStore`; content removal creates no new fetch.

The destination wraps the stable delegate before decorators and forwards negotiated
ownership through each wrapper. See [wiring](caching-conversation-repository-wiring.md)
and [testing](caching-conversation-repository-testing.md).
