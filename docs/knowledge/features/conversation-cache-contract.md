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
    suspend fun admitHost(serverId: String) = Unit
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

**File-to-Room import (#2161).** The active `MigratingConversationCache` attempts import for each
exact host/conversation and family before its full reads, replacements, owned mutations or internal
page wrappers use Room. Owner ids come from cache operations; hashed filenames cannot recover
owners, so unknown-owner sources stay untouched. Legacy and authoritative version-1 codecs
remain separate: legacy rows never become authoritative facts or certify their checkpoint.
Held row order, fields, unsigned evidence through `ULong.MAX_VALUE`, fragment bindings and
marker anchors survive valid legacy import. Authoritative import keeps raw facts, epoch,
revisions, applied progress (including progress beyond the checkpoint), coverage and continuation.
Local page exhaustion adds no certification and never supplies a daemon cursor.

The durable ledger uses these static outcomes:

| Outcome | Meaning |
| --- | --- |
| `absent` | An exception-reporting source open confirmed a missing path. |
| `imported` | Valid content and applicable evidence committed with this marker. |
| `invalid` | Validation rejected the source; its bytes remain untouched. |
| `unsupported` | The version header names an unsupported format; bytes remain untouched. |
| `superseded` | This family already has a database header; the file cannot overwrite it. |
| `retired` | Explicit removal prevents importing leftover sources, including after restart. |

Absence requires `Files.readAllBytes` to throw `NoSuchFileException`. Boolean predicates
such as `File.isFile` suppress metadata errors: a parent without search permission can look
missing. Denied access, security failures and other IO failures therefore remain retryable,
with no completion marker. Database/import failures also cannot install a successful empty
replacement. Reads retain the graceful empty/null fallback; writes whose import is unavailable
fail with the causeless static code `import_unavailable`, preserving recoverable offline bytes.
Cancellation before commit rolls back content and marker together; repeated imports and restart
respect committed markers. Cancellation and unclassified programming errors propagate.

Invalid optional legacy history does not invalidate readable rows. If the file reader's retained
history projection is subsequently rejected by Room validation, import retries with rows and no
history, logs static `outcome=history_rejected` and records `imported`. Only a rows-only retry
also rejected as `invalid_data` settles as `invalid`. Rejected claims certify nothing.

A successful deferred import must not let a writer built from an earlier empty restore replace
it. The cache remembers families read or refused while unavailable. If imported content later
arrives, writes of that family fail with `import_unobserved` for the rest of this cache instance;
a write that itself imports content is refused too. Later reads cannot prove that every active
writer observed the import and do not lift this refusal. Cancelled import callers also count as
unobserved: Room can commit before cancellation suppresses result delivery, so retry checks the
committed `imported` marker. Restart permits fresh restores of the imported content. Settling
as absent, invalid, unsupported or superseded imports no content and adds no such refusal.
Diagnostics use static family/outcome/operation codes without owners, paths, cursors or content.

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
production route activation remains with #1988 and bounded reading with #2068.
Authoritative Room storage (#2163) is bound through the migration-aware cache (#2161).

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
and owned mutation invalidate old tokens; local continuation is never a daemon cursor.

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

The application uses this backend through the import-aware cache (#2161), alongside
authoritative Room storage (#2163). #2068 owns bounded reader activation; #2253 supplies owned
legacy writes below. Legacy rows/evidence never become authoritative items or certify an
authoritative checkpoint. Existing file-cache operations,
metadata lists, read positions, settings and attachment-byte storage remain available.

### Owned incremental legacy mutations (#2253)

Ordinary production saves use the internal `OwnedLegacyCache` capability:
`CachingConversationRepository.ThreadWriter` calls `MigratingConversationCache.mutateLegacyRows`,
which admits the exact host/conversation/legacy family through the same import and retirement
checks as replacement writes, then calls `LegacyRoomStorage.mutateRows`. Unavailable or unobserved
imports and retired hosts still refuse writes. This storage capability changes no negotiated
route and cannot enable legacy fallback for an authoritative-only host. Full `writeThread`
replacement and file semantics remain available. Full-view history-position/coverage saves still
replace reconciled rows and bind evidence to their actual held ordinals; their optimization is
separate work. Partial readers are not activated.

A mutation carries changed cacheable rows, observed neighbour identities and explicit removed
renderer identities. Identity is typed and canonical, with length-delimited session-boundary
components; content, timestamps used for placement and unsigned durable ids do not substitute for
renderer identity. SQL predicates retain exact case-sensitive host/conversation ownership.
Updating a held row keeps its ordinal. New identities use available space between observed
neighbours without moving held rows; exhausted placement rejects the transaction. Each identity
appears once. Unobserved rows survive an incomplete view. Removal requires observed intent from
the repository, including confirmed projection disappearance or suppressed queued echoes;
omission, settlement exclusion and losing an unsettled row cannot authorize it. See
[writer ownership and capture fences](caching-conversation-repository.md#state-and-concurrency).

Schema version 4 adds the unique owner/renderer-identity index and scalar header row count via
an additive 3 → 4 Room auto-migration. Its backfill decodes existing rows once, preserves invalid
bytes with no indexed identity, and moves rows and their evidence together to spaced positive
`Long` ordinals. Old ordinal zero becomes the first positive slot. Full replacements and imports
use the same layout. Positive prefix allocation is essential: global evidence occupies ordinal
-1, so negative row positions could make a page select global cells as row-bound evidence and
withhold otherwise valid claims. Backfill is migration work, never ordinary mutation work.

Changed, removed or retention-discarded ordinals select only affected evidence, including aliased
fragment claims. The same transaction removes their producing claims and dependent claims that
share an affected producing id, subtracts those ids from intersecting received spans, and repairs
adjacent gaps/walk evidence. Unrelated payloads, ordinals and valid evidence remain exact.
Splitting spans/gaps uses `MobileJson`'s canonical unsigned model serializers: quoted numeric ids
fail the strict codec, including otherwise correct interval splits. Bounds through
`ULong.MAX_VALUE` remain exact; subtracting the maximum never wraps to zero.

Invalidation marks coverage unknown and clears saved completeness. Overflow beyond the existing
100000-row cap selects only the oldest excess rows, invalidates their affected evidence and resets
the backwards cursor/completeness conservatively. No discarded content stays certified. Ordinary
mutations read changed identities, neighbour ordinals and relevant evidence, without materializing,
decoding, serializing, deleting/reinserting or renumbering the whole saved thread. Failed validation,
placement or storage commits nothing. Expected failures retain the existing causeless/static-code
contract; diagnostics and internal counters expose no owners, identities, payloads or cursors.

A mutation changes the committed local generation. Older continuations reject with null, allowing
a new newest-page traversal to recover the entire committed snapshot. They never silently skip
rows or become daemon cursors. Local save/page exhaustion proves no server continuity or exhaustion.

#### Retained mutation and restart evidence

Linux focused runs retained in
[`owned-legacy-2253/results.json`](../../../app/src/test/resources/verification/owned-legacy-2253/results.json)
record **125 executed/passed, 0 failed, 0 skipped**. The sibling `green-*.xml` files contain the
counted results; historical failing reports and the earlier fixture patch remain alongside them.
Production revision: `74bc7a5c1cfdbad50263ab2100a849ce2c20abc0`; final dense-fixture revision:
`f92113bc4f9693bc3009af71316203b3c820901a`. The manifest distinguishes earlier runs with the
fixture-only working patch from the final committed fixture run.

| Focused selection | Executed/passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| `OwnedLegacyWritesTest` | 6 | 0 | 0 |
| `OwnedLegacyMigrationTest` | 1 | 0 | 0 |
| `OwnedLegacyConcurrencyTest` | 5 | 0 | 0 |
| `LegacyRoomStorageTest` | 18 | 0 | 0 |
| `MigratingConversationCacheTest` | 15 | 0 | 0 |
| `CoalescedThreadWritesTest` | 41 | 0 | 0 |
| `UnsignedHistoryCacheTest` | 11 | 0 | 0 |
| `HistoryMessageIdentityTest` | 17 | 0 | 0 |
| `CachedThreadWorkerTest` | 10 | 0 | 0 |
| `AuthoritativeRoomStorageTest.migrationInvariant_preservesLegacyContentAndEvidence` | 1 | 0 | 0 |

`boundedInvariant_newestUpdateDoesNotScaleWithSavedHistory` measures the production repository
writer through migration-aware real on-disk Room, after import, restore and initial coverage save.
Both the 20-row and 18000-row fixtures have a producing claim and separate received span per row.
Each newest-row update records **1 row read, 1 decode, 1 serialization, 1 write; 8 evidence cells
read, 1 evidence decode, 3 evidence mutation statements and 46 SQL statements**. The probe also
rejects full saved-row/evidence reads and saved-row deletion for this update.

`ownershipInvariant_partialViewPreservesUnownedRowsAndEvidence` changes/removes loaded identities
in a 200-row owned view of 18000 saved rows, then reopens fresh database/cache/repository instances.
It compares all 17800 unloaded rows and 53400 associated evidence cells exactly, checks a second
host with colliding ids, rejects the stale token and completes a fresh traversal. The remaining
owned-write probes cover shared-claim rollback/retry and readable interval splitting, retention,
unsettled disappearance, and prepended-page evidence. The migration probe preserves held order,
untouched positions and the other host while inserting before the migrated first row. All five
concurrency methods cover held/newer writes and removal, failure/unchanged-emission/final retry,
cancellation after commit, overlapping collectors/reconnect, and deletion/host forgetting/restart.

The [verifier PASS at `54c70f599598bfefe57f556650c8c95bc490441d`](https://github.com/pyrycode/pyrycode-mobile/pull/2258#issuecomment-6244324619)
records Linux configured gates: JVM **5911 executed/passed, 0 failed/skipped**; UI **334
executed/passed, 0 failed, 1 skipped**; scripted **22 executed/passed, 0 failed/skipped**.
The dispatcher named-test report confirms all twelve owned-write/migration/concurrency methods
and `cachePolicyInvariant_100000LiveRowsAreFilteredOnlyAfterMainDelivery` passed in that full JVM
suite. The UI skip is `RenameDialogCaptureTest.renameAtFigmaViewport`, not a storage pass.
No real-Claude capture is required; other operating systems are outside this ticket's scope.

### Indexed authoritative Room storage and pages (#2163)

`AuthoritativeRoomStorage` is an independently usable backend over the same database as
`LegacyRoomStorage`, exposing full `readAuthoritativeThread`/`writeAuthoritativeThread`
equivalent to `ConversationCache`'s, plus `newestPage`/`beforePage` and scoped removal. It is
used by the application through the migration-aware cache (#2161); #2068 owns bounded
reader activation and owned partial writes. Ownership is the same exact, case-sensitive
host-plus-conversation scoping as
every other family in this cache, with the authoritative family additionally scoped by epoch —
identical ids under another host, another conversation or another epoch are different rows,
never the same fact.

`writeAuthoritativeThread` validates and retains the replacement with the existing
`SavedAuthoritativeThread.retained()` (same rules as the file-backed cache: duplicate ids,
numeric/revision bounds, coverage and the 100000-item ordered-tail cap), then replaces the
header and every item row for that owner/epoch in one Room transaction — delete-then-insert,
never a partial update. A tail-trimming replacement clears `checkpoint` to zero and `coverage`
to null while keeping `appliedVersion`, the same certification loss the file-backed cache
applies on retention. A rejected replacement (duplicate/invalid identity, bad coverage, IO or
security failure) leaves the last committed header and items intact, surfaced as a causeless
`ConversationCacheException` inside the `Result`; cancellation and unclassified programming
failures propagate instead of being swallowed. `readAuthoritativeThread` reconstructs and
re-validates the full saved record from the header and every stored item, returning null rather
than repairing storage when stored JSON fails to parse or decode.

`newestPage`/`beforePage` return a distinct nullable `AuthoritativeThreadPage` — never a
shortened full read — carrying the exact owner, epoch, selected raw items, and the header's
committed `appliedVersion`/`checkpoint`/`coverage` exactly as `readAuthoritativeThread` would
report them: a page can never advance, shrink or otherwise disagree with the full record's
certification. For a positive page size, the query selects at most that many item rows in held
ordinal order (newest-first for `newestPage`, continuing older for `beforePage`) plus one
ordinal-only continuation probe that never selects or decodes the probed row's payload — the
same bounded-read shape `LegacyRoomStorage`'s pages use. Growing history outside the requested
window never grows what a page reads or decodes; only the selected items' rows and the single
probed ordinal are touched, instrumented by the storage's internal `ReadCounts`. Decoded page
items are revalidated against their indexed identity, revision and ordinal exactly as a full
read would, so a page can never surface a row that disagrees with its own index.

The page's nested `AuthoritativeThreadPage.Continuation` is opaque and belongs only to this
family: it binds the exact host, conversation, epoch and a per-write replacement generation
(a fresh UUID minted on every `writeAuthoritativeThread`, not a content hash), plus the local
ordinal to resume before. A token from a wrong owner, a stale epoch or a superseded generation
returns null rather than a wrong or partial page — any replacement invalidates every token
issued before it, even one from the same epoch reused by a later replacement. It cannot be
passed to `LegacyRoomStorage`'s paging, and a continuation running out (`continuation == null`)
says only that this backend's local storage is exhausted; it is never a server cursor and never
asserts server-side completeness. Reaching the end of local pages does not change applied
progress, checkpoint or coverage.

Scoped removal (`removeConversation`/`removeHost`) deletes both the legacy and authoritative
families for the requested owner in one Room transaction — see
[Layout § atomic scoped removal](conversation-cache-layout.md#indexed-legacy-room-layout-2162)
— so a conversation or host disappears from both families together or not at all. A family-local
write (an authoritative replacement, or `LegacyRoomStorage.writeThread`/history write) touches
only its own tables: writing authoritative facts leaves legacy rows and evidence for the same
owner unchanged, and vice versa. Result, token, header and page string representations redact
owner, epoch and content; only static operation/failure codes reach diagnostics.

`ConversationCacheDatabase` gained the authoritative tables at schema version 2 through
Room's additive
`AutoMigration(from = 1, to = 2)` — see
[Layout § the additive version 1 → 2 migration](conversation-cache-layout.md#indexed-legacy-room-layout-2162)
— adding the authoritative tables without touching existing legacy content. Version 3
adds the import ledger described above.

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
  persisting raw model-adjacent JSON. This cache uses plaintext app-private storage; desktop
  uses an encrypted secure store.

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
