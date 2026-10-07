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

**The read-position family (#877).** `readReadPositions`/`writeReadPositions` follow the same
graceful-read, reporting-mutation, default-bodied shape as the other two families, over
`Map<String, ReadPosition>` keyed by conversation id. `writeReadPositions` is a **whole-host
replace**, like `writeConversations`. `ReadPosition.completedTurnId` is the latest turn this
phone saw complete live for that conversation; `readTurnId` is the one the operator had seen
as of their last open, or `null` if they have not opened it since a turn completed —
`unread` is simply `readTurnId != completedTurnId`. A conversation absent from the map is
read. Both ids are daemon-authored turn ids, used only for equality, exactly like
`Conversation.id` above. The one production writer and reader is
`HostConversationSource`'s per-host attention fold — see
[dependency injection § Attention state](dependency-injection-host-conversation-source.md#attention-state-877) for
`HostAttentionState`, the pure fold that produces the map this family persists, and for the
bounds (`MAX_READ_POSITIONS`, `MAX_TURN_ID_CHARS`) that keep a hostile daemon from growing
the document without limit.

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

No `android.*` type appears in the contract. `data/` is portable by project rule (root
`CLAUDE.md` § Don't); only `FileConversationCache` may reach for a platform storage handle, and
even there the only platform type is `java.io.File` — see § Root and storage scope below.
