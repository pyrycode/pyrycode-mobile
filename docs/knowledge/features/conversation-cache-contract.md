# Conversation cache — The contract

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## The contract

```kotlin
interface ConversationCache {
    suspend fun readConversations(serverId: String): List<Conversation>
    suspend fun writeConversations(serverId: String, conversations: List<Conversation>): Result<Unit>
    suspend fun readThread(serverId: String, conversationId: String): List<ThreadItem> = emptyList()
    suspend fun writeThread(serverId: String, conversationId: String, rows: List<ThreadItem>): Result<Unit> =
        Result.success(Unit)
    suspend fun readAuthoritativeThread(serverId: String, conversationId: String): SavedAuthoritativeThread? = null
    suspend fun writeAuthoritativeThread(serverId: String, conversationId: String, saved: SavedAuthoritativeThread): Result<Unit> =
        Result.success(Unit)
    suspend fun readHistoryPosition(serverId: String, conversationId: String): HistoryPosition? = null
    suspend fun writeHistoryPosition(serverId: String, conversationId: String, position: HistoryPosition?): Result<Unit> =
        Result.success(Unit)
    suspend fun readReadPositions(serverId: String): Map<String, ReadPosition> = emptyMap()
    suspend fun writeReadPositions(serverId: String, positions: Map<String, ReadPosition>): Result<Unit> =
        Result.success(Unit)
    suspend fun removeHost(serverId: String): Result<Unit>
    suspend fun removeConversation(serverId: String, conversationId: String): Result<Unit>
}

data class ReadPosition(val completedTurnId: String, val readTurnId: String?) {
    val unread: Boolean get() = readTurnId != completedTurnId
}

const val MAX_CACHED_THREAD_ROWS = 100_000

fun cacheableThreadRows(rows: List<ThreadItem>): List<ThreadItem>
fun settledThreadRows(rows: List<ThreadItem>): List<ThreadItem>

class ConversationCacheException(message: String) : Exception(message)
```

**The thread-row family (#797).** `readThread`/`writeThread` follow the same graceful-read,
reporting-mutation shape as the conversation family, over `List<ThreadItem>` (`data/repository/`,
not the cache package) instead of `List<Conversation>`. Both carry a **default body on the
interface** — `emptyList()` / `Result.success(Unit)`, the precedent
`ConversationRepository.refreshSessionSettings` already sets — so a double that does not exercise
threads (`InertConversationCache`, and the fakes in `HostConversationSourceTest` and
`RelayConnectionFactoryTest`) needed no edit for this ticket. `writeThread` is a **whole-thread
replace**, like `writeConversations` is a whole-host replace, and it always stores
`cacheableThreadRows(rows)` — never the caller's raw list — so no caller can persist an
unrecognized, streaming or in-flight-tool row by constructing a `ThreadItem` list itself.

**The authoritative thread family (#2149).** `readAuthoritativeThread`/`writeAuthoritativeThread`
are additive, default-bodied methods over `SavedAuthoritativeThread(epoch, items, appliedVersion,
checkpoint, coverage)`. One atomic replacement binds full raw item objects and their epoch to the
certified reconnect checkpoint and optional continuous history coverage. `SavedThreadCoverage`
holds `lowerOrder`, `upperOrder`, `continuation` and `olderExists`; parent or active facts outside
that range do not enlarge it. Applied progress may exceed the checkpoint after an interrupted
multi-batch reply: saving newer complete items does not certify the unfinished reply.

The store retains one item per identity at its newest accepted revision in daemon order.
Raw objects preserve identity, revision, order, parents, explicit-null clears and inert unknown
fields/kinds. Full active facts are retained as saved facts; connection capabilities, runtime
generation, query assembly and transient live progress are excluded. Unlike the legacy row
family, this family does not apply settled-row or unknown-kind exclusions. Its 100000-item cap
keeps the ordered tail; any trimming sets `checkpoint` to zero and `coverage` to null while
retaining applied progress. Omitted facts must never remain certified.

The [separate envelope](conversation-cache-layout.md#layout) validates version and exact host/
conversation ownership. Invalid full items, duplicate identities, numeric/revision bounds or
coverage invalidate the whole record. Reads return null without repairing disk; failed writes
preserve the last successful document. Records redact `toString`, and diagnostics contain only
static operation/failure codes. Legacy rows remain readable through `readThread` and cannot be
converted into authoritative items or certify this checkpoint. Both thread families are removed
with the conversation or host. The sole save/restore consumer is the
[supplied caching seam](caching-conversation-repository.md#what-is-written-and-when);
production route activation remains with #1988, authoritative Room storage with #2163
(split from #2067), and bounded reading with #2068.

**The saved history position (#1354, extended by #1832 and #1910).** `HistoryPosition(cursor, atStart,
coverage = null)` lives inside the thread document beside its rows, under the same host and
conversation namespace. `coverage` stores received durable spans, known gaps, unknown legacy
coverage, page-edge/per-gap/newest cursors and content-free row identity/order/retention metadata.
High-water is derived from the highest retained span; live/ring ids and legacy row identities
never certify durable ids. No raw excluded envelopes are persisted. Removal of the thread or host
also removes this metadata. Cursors and entry content never reach logs or exception prose.

Span endpoints, gap anchors/edges, row/delta order and producing-entry sets, page-edge cursors and
walk anchors retain exact unsigned ids through `ULong.MAX_VALUE`. `unsignedHighWater` derives from
`unsignedSpans`. Stored field names remain unchanged (`spans`, `gaps`, `rowOrder`, `rowEntries`,
`cursors`, `walks`, `unknown`), so positive signed numeric documents still load without a format
rewrite; older metadata that omitted empty spans defaults to an empty list. Signed construction
and lower-range projections remain compatible, while upper-range evidence keeps the signed UI
view conservatively unknown through sticky `unsignedIncomplete` and suppresses saved `atStart`.
This does not discard authoritative unsigned claims or make `unsignedUnknown` true.

Optional history metadata is decoded separately from rows. Invalid numeric or structural metadata
discards the saved position with a content-free diagnostic; retained rows remain readable. A row's
order id must belong to its producing-entry set, and all claimed ids must be covered. Claims are
then checked against retained content, so metadata cannot certify removed rows.

The [caching wrapper](caching-conversation-repository.md#the-saved-history-position-1354) restores
old nonempty documents without coverage as unknown, even with saved `atStart`; their rows stay
readable. After the newest page, unknown coverage tracks the verified span's older edge;
its marker is subject to displayed-row eligibility.
Matching legacy rows or verified overlap proves deduplication, never completeness: unknown
coverage without an older durable anchor closes only on `at_start`, including an empty terminal
page. An empty uncovered cache ignores old backwards metadata unless `unsignedIncomplete` is set;
that flag and its usable cursor survive even when no rows remain.
Known holes require continuous received coverage joining their older anchor. See
[resuming history](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354)
for lazy demand and marker/cursor behavior.

Visible marker eligibility is documented in the parent
[cache contract](conversation-cache.md#the-contract); saved unresolved coverage remains authoritative.

### Indexed legacy operations and pages (#2162)

`LegacyRoomStorage` supplies full `readThread`/`writeThread`, `readHistoryPosition`/
`writeHistoryPosition`, `removeConversation` and `removeHost` operations independently of the
application binding. Whole-thread replacement still applies `cacheableThreadRows`, validates
uniqueness and retains content-bound history only for committed rows. Changed/removed proofs
invalidate their producing claims. A 100000-row trim drops a position without coverage; with
coverage it resets cursor/`atStart` and retains conservative evidence. History replacement binds
and validates claims against the stored rows; a null position clears metadata without deleting
rows, and clearing a never-written owner is a no-op. Every mutation commits rows and applicable
evidence in one Room transaction. Expected failures preserve the last committed representation.
The [indexed layout](conversation-cache-layout.md#indexed-legacy-room-layout-2162) also describes
atomic scoped removal and backup exclusion of the database and its sidecars.

`newestPage(serverId, conversationId, size)` and `beforePage(serverId, conversationId,
Continuation, size)` return a distinct nullable `LegacyThreadPage`, never a shortened full-cache
read. A positive size selects at most that many payloads in held ordinal order, plus at most
one ordinal-only continuation probe; its payload is never decoded. Newest selects the tail and
before selects earlier ordinals. Repeated pages traverse an unchanged snapshot exactly once
even with equal or misleading timestamps. The result carries its exact owner. Its opaque local
`Continuation` belongs only to the legacy family and binds exact case-sensitive host,
conversation and committed generation. Wrong-owner or stale tokens return null. Replacement
invalidates old tokens; local continuation is never a daemon cursor.

`LegacyPageEvidence` exposes typed unsigned spans, gaps, selected row/delta claims and proofs,
fragment bindings and relevant cursor/walk evidence. It is a projection, not full
`HistoryCoverage`: omitted history is neither loaded nor certified. Saved high-water/unknown-edge
and global unknown/incomplete flags retain their committed meanings even when selected rows fit
the signed range. `savedCursor` and `savedAtStart` are saved metadata, not deductions from local
page exhaustion. Local exhaustion cannot close gaps, change server completeness or rewrite
opaque cursors.

Evidence selection uses selected ordinals/producing claims, intersecting spans/gaps and needed
adjacent spans, nearest edge/gap cursors and unknown-walk evidence. Cursor selection must use
the selected claim window, not an intersecting continuous span's full endpoints: older anchors
inside that span would otherwise grow page reads/decoding. Closed intervals sharing the selected
boundary both intersect it. The interval, gap-walk and gap-cursor queries therefore include the
strict predecessor (`low < first`) alongside intervals starting at `first`, filtered by
`high >= first` and `low <= last`. This preserves both gaps and their retry/cursor evidence
without loading unrelated older history.

Parseability alone is insufficient. Selected evidence payloads must match their SQL indexes
and row ordinals; projection validation checks ordered spans, gap structure, covered producing
claims, exact row/delta proofs and fragment bounds/hashes. It does so without requiring omitted
gaps or certifying omitted rows. Invalid optional evidence withholds the entire projection,
saved cursor and saved completeness while preserving readable rows. Reads never repair storage.
Full metadata reads remain available without row payload decoding; content binding is established
on committed writes, and page reads additionally check selected content.

Expected SQLite, IO, security and invalid-data failures follow the graceful-read/reporting-mutation
contract: empty rows or null metadata/pages on read, and causeless `ConversationCacheException`
failures inside mutation `Result`s. Cancellation and unclassified programming errors propagate.
Diagnostics contain static operation/failure codes; page, evidence, continuation and schema
result strings redact content, cursors and owner ids.

The backend remains unbound. #2161 owns import/application binding, #2163 authoritative Room
storage and #2068 reader activation/owned partial writes. Legacy rows/evidence never become
authoritative items or certify an authoritative checkpoint. Existing file-cache operations,
metadata lists, read positions, settings and attachment-byte storage remain available.

**The read-position family (#877, fallback-only since #1883).**
`readReadPositions`/`writeReadPositions` retain their graceful-read,
reporting-mutation, default-bodied shape over `Map<String, ReadPosition>` keyed by
conversation id. Writes replace the whole host map. No format replacement or migration
is needed: this app-private, backup-excluded family remains the older-daemon fallback.

`completedTurnId` holds a completed daemon turn id or a phone-minted UUID row token;
`readTurnId` is the token copied on local open, or `null`. `unread` is their inequality,
and an absent position is locally read. These are equality keys, never durable entry ids.
`HostConversationSource` is the production reader/writer, retaining bounded positions
across restart. For a conversation with a confirmed daemon `readUpTo`, completion,
row growth and opening leave local positions unchanged and attention instead compares
the repository's known unsigned latest durable id with that shared mark. Old stored
positions may remain but cannot override shared facts.

Shared marks are not restored from disk, and cached conversation fields cannot establish
connected-daemon support. A replacement repository drops the previous connection's read
facts; an older daemon omitting them resumes local bookkeeping. See
[Attention state](dependency-injection-host-conversation-source.md#attention-state-877)
for support lifetime, generation ordering and bounds (`MAX_READ_POSITIONS`,
`MAX_TURN_ID_CHARS`).

Two top-level functions in `ConversationCache.kt` define what a thread may hold, used by both the
cache (enforced on write) and [`CachingConversationRepository`](caching-conversation-repository.md)
(compared against on every emission), so the two can never disagree about what "settled" means:

- **`settledThreadRows(rows)`** drops only the in-flight rows — a `Message` with `isStreaming` or
  whose `toolCall?.status == ToolCallStatus.Running` — leaving `UnrecognizedMessage`,
  [`Banner`](banner-notice-row.md), [`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874-1358)
  and [`ModelRefusal`](model-refusal-row.md) rows and the row count untouched. This is what a thread may
  keep **drawing** once its connection is gone, not what the cache may **hold**: it is also the caching
  repository's merge-base rebase on a disconnect (see that doc), where the bound would otherwise shrink a
  long thread on screen the moment it goes offline.
- **`cacheableThreadRows(rows)`** is `settledThreadRows(rows)` with only `UnrecognizedMessage` rows
  (unbounded, model-adjacent JSON; its KDoc forbids persisting it) additionally dropped, and the
  result bounded to the newest `MAX_CACHED_THREAD_ROWS` (100000) via `takeLast` — the thread is in
  arrival order, so "newest" is the tail. This is what may reach disk.

  **Every other settled row kind is kept, including [`Banner`](banner-notice-row.md),
  [`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874-1358) and
  [`ModelRefusal`](model-refusal-row.md) (#1353).** Those three used to be dropped here and restored
  by history replay (#873, #874, #875) on every open. Once history loads only on the user's request
  (an owner decision outside this ticket), replay stopped running on a routine reopen, so a cached
  thread that still excluded them lost those rows the moment the live page didn't re-deliver them.
  Desktop's saved timeline already kept every settled row kind except the live-only attachment
  offer, so this closes the gap rather than inventing a new rule — see
  [`DurableThreadItem`/`isDurable`](https://github.com/pyrycode/pyrycode/blob/main/src/shared/chatHistory.ts)
  in the sibling desktop checkout. `UnrecognizedMessage` stays out on purpose: its KDoc forbids
  persisting raw model-adjacent JSON, and this cache is plain files, not desktop's encrypted secure
  store.

**Identity is exact, case-sensitive string equality** on `serverId` and on `Conversation.id` —
the same rule [`PairedServerCollectionStore`](paired-server-store.md#the-contract) states for
its own ids. Neither is normalized or validated. The contract speaks the domain `Conversation`
type, not a persistence record: its eventual reader, `HostConversationSource`'s
`HostConversationSnapshot`, already types `channels`/`chats` as `List<Conversation>`.

`writeConversations` is a **whole-host replace, not an upsert** — the daemon's list is
authoritative for a host, so a conversation it no longer reports stops being cached by the
same call that stores the rest, with no diffing or second call in the eventual consumer. Order
is preserved verbatim; the cache does not re-sort.

Values read back are the same daemon-authored text that arrived over the wire. Passing through
the cache neither validates nor bounds them — a cached conversation name is exactly as
untrusted as a live one, and the render path owns length-bounding and escaping either way.

No `android.*` type appears in the storage contract. Android database construction stays
at the `di/` platform boundary; `FileConversationCache` accepts a `java.io.File` root.
See [storage scope](conversation-cache.md).
