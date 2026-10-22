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
    suspend fun resetAuthoritativeThread(serverId: String, conversationId: String, saved: SavedAuthoritativeThread): Result<Unit> =
        writeAuthoritativeThread(serverId, conversationId, saved)
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

**File-to-Room import (#2161, authoritative-only since #2385).** The active
`MigratingConversationCache` attempts authoritative import for each exact host/conversation
before its full reads, replacements, owned mutations or internal page wrappers use Room.
Owner ids come from cache operations; hashed filenames cannot recover owners.
Legacy entry points use Room only, bypassing source classification and import bookkeeping.
Opening, reading and importing leave legacy files untouched, including unknown-owner hashes,
and preserve existing legacy Room content. Legacy files supply no rows to either Room family
and cannot certify authoritative content. Explicit host/conversation removal retains its scoped
cleanup and durable fences. Dormant legacy storage/codecs remain for sibling retirement work;
the complete retirement fold belongs to #2364/#2387.
Authoritative import keeps raw facts, identities, order, epoch, revisions, applied progress
(including progress beyond the checkpoint), coverage and continuation.
Local page exhaustion adds no certification and never supplies a daemon cursor.

Authoritative import uses these static outcomes in the retained shared ledger; historical
legacy records and both families' retirement records remain intact:

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

A successful deferred import must not let a writer built from an earlier empty restore replace
it. The cache remembers authoritative reads or refusals while unavailable. If imported content later
arrives, authoritative writes fail with `import_unobserved` for the rest of this cache instance;
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
checkpoint, coverage, incarnation, retirements)`. The full-write API atomically replaces facts and metadata, binding raw
item objects and their epoch to the certified checkpoint and optional continuous history coverage. `SavedThreadCoverage`
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
production negotiated binding is supplied by #2245 and bounded opening by #2333 below.
Ordinary Room saves use the owned mutation contract below (#2255/#2256); full-file compatibility and
import continue to use replacement while preserving committed retirement fences.
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

The [caching wrapper](caching-conversation-repository.md) restores
old nonempty documents without coverage as unknown, even with saved `atStart`; their rows stay
readable. After the newest page, unknown coverage tracks the verified span's older edge;
its marker is subject to displayed-row eligibility.
Matching legacy rows or verified overlap proves deduplication, never completeness: unknown
coverage without an older durable anchor closes only on `at_start`, including an empty terminal
page. An empty uncovered cache ignores old backwards metadata unless `unsignedIncomplete` is set;
that flag and its usable cursor survive even when no rows remain.
Known holes require continuous received coverage joining their older anchor. See
[resuming history](remote-conversation-repository-reads-and-thread-store-history-paging.md)
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
rows, and clearing a never-written owner is a no-op. Each storage mutation commits its rows and
applicable evidence in one Room transaction; repository coverage saves commit owned rows before a separate
owned evidence transaction. Expected failures preserve the last committed representation.
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

See [owned legacy mutations and received evidence](conversation-cache-legacy-mutations.md) for
identity ownership, placement, coverage invalidation/recovery and counted Linux evidence.

### Indexed authoritative Room storage and pages (#2163)

`AuthoritativeRoomStorage` is an independently usable backend over the same database as
`LegacyRoomStorage`, exposing full `readAuthoritativeThread`/`writeAuthoritativeThread`
equivalent to `ConversationCache`'s, plus owned upserts/removals, `newestPage`/`beforePage` and scoped removal.
It is used by the application through the migration-aware cache (#2161); #2255 supplies ordinary
owned writes and #2333 supplies bounded production opening. Ownership is the same exact, case-sensitive
host-plus-conversation scoping as
every other family in this cache, with the authoritative family additionally scoped by epoch —
identical ids under another host, another conversation or another epoch are different rows,
never the same fact.

`writeAuthoritativeThread` validates and retains the replacement with the existing
`SavedAuthoritativeThread.retained()` (same rules as the file-backed cache: duplicate ids,
numeric/revision bounds, coverage and the 100000-item ordered-tail cap), then replaces the
header and every item row for that owner/epoch in one Room transaction — delete-then-insert,
never a partial update. Replacement retains committed same-incarnation retirement fences and
maximum applied progress, filters retired identities and rejects stale incarnations. Newly
submitted fences must pass the durable-revision check described below before replacement begins.
A tail-trimming replacement clears `checkpoint` to zero and `coverage`
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
daemon order with ordinal tie-breaking (selecting the newest window for `newestPage` and
earlier windows for `beforePage`, each returned chronologically) plus one
scalar-only continuation probe that never selects or decodes the probed row's payload — the
same bounded-read shape `LegacyRoomStorage`'s pages use. Growing history outside the requested
window never grows what a page reads or decodes; only the selected items' rows and the single
probed rank are touched, instrumented by the storage's internal `ReadCounts`. Decoded page
items are revalidated against their indexed identity/order and unique held ordinals as a full
read would, so a page can never surface a row that disagrees with its own index.

The page's nested `AuthoritativeThreadPage.Continuation` is opaque and belongs only to this
family: it binds the exact host, conversation, epoch and committed generation
(a fresh UUID on replacement or owned mutation, including metadata/placement-only commits),
plus the local rank to resume before. Signed storage ordinals are tie keys, not token positions.
A token from a wrong owner, a stale epoch or a superseded generation
returns null rather than a wrong or partial page. Replacement and owned mutation invalidate
every prior token, including within a reused epoch; restart from `newestPage` after rejection.
It cannot be
passed to `LegacyRoomStorage`'s paging, and a continuation running out (`continuation == null`)
says only that this backend's local storage is exhausted; it is never a server cursor and never
asserts server-side completeness. Reaching the end of local pages does not change applied
progress, checkpoint or coverage.

Scoped removal (`removeConversation`/`removeHost`) deletes both the legacy and authoritative
families, including authoritative retirement fences, for the requested owner in one Room transaction — see
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

### Bounded authoritative display windows (#2333)

`AuthoritativeWindowCache` is an optional import-aware opening/lifetime capability implemented
by `MigratingConversationCache`. `newestAuthoritativeWindow` returns `AuthoritativeThreadWindow`
with exact owner, committed partial facts/metadata and selected row contributions. It does not
reuse `AuthoritativeThreadPage`: a null continuation would incorrectly imply local exhaustion
while older saved facts remain omitted. Raw-fact pages and full reads keep their contracts.
The production repository requests 200 displayed contributions; fewer saved rows return all.

Room selects content and launch candidates independently in renderer placement order, each
limited to the row budget, then takes their combined newest contributions. Hidden/state-only
facts do not consume the budget; a fact's separated launch/content rows count independently.
Active facts and unordered shown queued/lost user feedback are indexed extras. Identity lookups
recursively close required parents and historical agent anchor/held-tie witnesses. These complete
facts are renderer inputs, not permission to draw their unselected rows or certify a range.
Only selected/current-overlap retirement evidence enters memory, rather than every historical
fence. SQL result counts include discarded candidates and duplicate extra results even when
payload decoding reuses an identity. Plain 18000-row opening returns/decodes 200 items in five
queries with one header evidence result/decode, zero ordinal results and no full read.

Selected payloads undergo full-item and exact owner/epoch/index identity/order validation,
including display/extra flags, parent, agent/background classification, active/ended order and
nullable renderer child order. Explicit zero sorts before positive child orders; absent order
sorts last, independently of the raw-fact paging key. Invalid selection restores nothing and
never repairs disk. Supplemental resolution validates winning roots **and descendants**, using
indexed contribution candidates over old/new ancestor paths when a witness is hidden, moved,
reparented or retired. Task-link/id validity is part of that structural boundary. Only resulting
witnesses/parents cross the payload boundary; unrelated sibling payloads remain unloaded.
Compound owner/epoch/order/placement-key seeks and positive `key > ''` ranges are required:
`key != ''` can scan an entire empty category despite a small result count. Query-plan assertions
therefore complement result/query/decode counters.

Initial and later unavailable supplemental reads withhold unchecked captures from delivery and
writer baselines. Observation-owned bounded retries retain the last validated view; stale owner,
incarnation, generation or cache lifetime rejects application. Hydration may retain the committed
certificate but cannot advance it over unapplied/unsaved facts, manufacture continuous coverage,
or replace a daemon cursor with local position. Owned saves retain omitted facts. Authoritative
reader paging uses the bounded continuation below; full-file compatibility APIs remain separate.
See [repository validation](caching-conversation-repository.md#bounded-authoritative-opening-2333).

#### Authoritative reader continuation (#2334)

One reader movement/retry selects up to 200 older display contributions plus required extras,
independently of held newest/catch-up. Committed owned placement changes extend storage-owned
reader bounds using old/new metadata already visited by the writer. This retains access to
moved older components regardless of boundary survival or distance. Retry belongs to its reader/generation; reset or replacement clears it. Local exhaustion cannot certify
coverage, set daemon exhaustion or overwrite daemon continuation. Owned saves retain unloaded
facts. The [reader paging contract and evidence](authoritative-reader-paging.md) covers bounded
metadata recovery, fragmented ranges, retirement and fresh reopen.

#### Placement metadata and deep-parent availability

Additive migrations 5→6 through 10→11 derive display/extra flags, placement keys, nullable child
order, anchor/tie identities and per-node contribution summaries without changing raw payloads
or certification. Import and accepted writes maintain the same metadata atomically. Version 11
rebuilds old keys non-destructively; earlier upgrade steps also use the bounded representation.
Existing no-backup database/sidecar custody remains unchanged.

`SavedPlacementIndex` uses fixed-width preorder ranks over a shared iterative agent forest,
retaining family phase/order/held-tie prefixes. Non-agent links are collapsed once, ancestor
classification is memoized, and sibling order matches nullable renderer order and held ordinal.
No descendant stores its complete ancestry: iterative traversal alone would still leave
quadratic allocation/storage if paths were copied. Keys are at most 62 characters; retained
forest metadata and aggregate key output grow linearly in admitted facts. Structural writes
load metadata for affected former/winning parent components and coherently rerank siblings,
leaving unrelated payloads untouched. This maintenance can visit an affected component;
opening payload bounds do not imply constant-time structural writes or migration.

Both contribution construction and winning-path reduction use iterative postorder and visited
sets, with one reduction per node and children grouped once. Back edges to unfinished ancestors
retain the existing empty-contribution cycle semantics. There is no parent-depth rejection or
one JVM stack frame per admitted fact. Deep chains/cycles and 18000 nested foreground agents
are covered across import, upgrade, fresh reopen and structural reparenting. Current full-gate
measurements retain 18000 ancestry visits, 18000 rank visits, 1098000 total key characters and
maximum 61-character display keys. Database sizes for import/upgrade/reopen/structural write are
17940480/17752064/17752064/18239488 bytes, with exact witnesses, raw payloads, certificates and
other-host content preserved. [Current counted evidence](thread-screen-testing-saved-first-draw.md#bounded-database-and-retained-file-acceptance-2333)
distinguishes these correctness/availability probes from native first-draw timing.

### Owned incremental authoritative mutations (#2255)

`CachingConversationRepository.ThreadWriter` calls the internal `AuthoritativeUpsertCache`
capability on `MigratingConversationCache`, which uses its existing import-aware admission before
`AuthoritativeRoomStorage.upsertAuthoritativeThread`. Exact host/conversation/family, epoch and
runtime generation still own every capture and commit. `import_unavailable`, `import_unobserved`
and forgotten-host fences refuse this path too. Saved facts grant no negotiated support or route;
this seam activates neither bounded readers nor a new wire operation. Full replacement/import
and file compatibility APIs remain available.

The mutation contains changed raw full facts, store-authored metadata, the prior metadata-only
successful/restored baseline, an explicit reset and accepted placement evidence. Ordinary omission
never removes a stored identity. Storage validates incoming facts with `toSnapshot`, selects only
incoming identities' prior payloads, validates their indexed identity/order and kind, and accepts
only newer revisions. Unknown raw fields, parent relationships and explicit clears remain exact.
A newest revision reads/decodes/serializes/writes that fact and required metadata, without a full
saved-thread read, full serialization, delete/reinsert or unrelated ordinal rewrite. Ordering-key
SQL may scan scalar keys without decoding unrelated payloads.

Placement admission is independent of payload admission. Runtime publication evidence preserves
accepted before/after tie blocks in candidate order through coalescing, partial restoration and
live-before-restore races, including unchanged peers and unchanged revisions. New entries append;
moves from below prepend to a tie, moves from above append; restoration appends saved peers behind
held live identities. Scalar queries retain already correct blocks' ordinals, otherwise reserve a
signed ordinal block for participating identities only. Placement-only work reads no payloads and
changes no payload bytes. Stale/conflicting incoming payloads cannot reposition a newer stored fact.
Inferring final placement only from durable and final numeric order loses intermediate moves;
filtering placement only by changed revisions loses unchanged live peers.

Facts, placement, applied progress and certification commit in one Room transaction. Applied
progress takes the maximum within the epoch; incomplete replies keep the prior certified
checkpoint/coverage. For an existing family, advancing certification requires monotonic progress
and the inherited
baseline epoch/checkpoint/coverage to match the durable certificate inside that transaction.
Local hydration, page exhaustion and active/parent extras cannot extend certification. Failure
preserves prior facts and metadata; no successful baseline advances.

Retention selects the oldest scalar-ordered excess over 100000 facts and atomically clears
checkpoint to zero and coverage to null, keeping applied progress. A partial store may still hold
the old certificate: neither repeating it on a revision nor a catch-up/coverage-only completion
inheriting it can reinstall certification for trimmed facts. A fresh conservative restore and
new store-authored completion can recover. Explicit reset changes the incarnation and
retires the family before its new facts commit, even when the epoch string is reused; ordinary
replacement cannot bypass committed fences through different epoch text. Ordinary
partial views cannot retire it. Confirmed conversation/host removal clears both families and
fences delayed work. Existing no-backup custody and content-free classified failures remain.

#### Explicit owned authoritative retirement (#2256)

`AuthoritativeThreadStore.captureRemoval` mints a redacted capture only for a currently held
daemon identity. Its family is authoritative by type; it binds the minting store, exact host and
conversation, epoch, incarnation, runtime generation and held revision. The repository's internal
`removeAuthoritativeFact` admits that capture under `historyWrites`, rechecking ownership and
revision in the store lock. Wrong-store/owner/generation or superseded-revision captures cannot
remove a fact. Repeating an already-retired capture is harmless. Omission from a partial view,
hydration, settlement filtering and parent relationships never request removal; removing a parent
does not remove its children. Other facts retain raw fields, explicit clears and stable order.

The accepted identity-to-revision fence outlives every delayed snapshot, full-item replay,
restore and restart within its epoch incarnation. Store restoration unions fences in that
incarnation and filters retired identities. Room's schema-5 additive 4 → 5 migration adds the
retirement table and default-empty header incarnation without discarding prior content. Old
version-1 files and pre-migration headers use the initial empty incarnation and no fences.
Saved-record validation rejects invalid fence identities/revisions, facts overlapping their fence,
and nonconservative certification alongside fences. Saved state still grants no connected support.

Incremental Room mutation checks only newly retired identities' durable payloads, then commits
the named deletion, fence and required metadata atomically. It leaves unrelated payloads and
ordinals untouched. Both Room replacement/import and file replacement independently check newly
submitted fences against validated durable facts inside their transaction/lock: a newer durable
revision rejects the entire mutation before any prior record changes. Committed fences survive
stale replacement, and retired payloads are filtered before admission. File authoritative
replacement is serialized across instances and retains atomic rename and exact envelope checks.

Any retirement forces `checkpoint = 0` and `coverage = null`, retaining maximum applied progress
within the same epoch/incarnation. Replay completion, hydration and extras cannot recertify an
incarnation containing deliberately withheld identities. This is stricter than retention-trim
recovery above: only explicit reset clears the retirement fences. Reset mints a fresh UUID
incarnation, even with reused epoch text, and atomically replaces prior facts/fences/certificate.
Old-incarnation work then rejects. The replacement fallback calls the explicit
`ConversationCache.resetAuthoritativeThread` entry: `MigratingConversationCache` applies import
observation and host-retirement admission before Room's reset transaction; ordinary replacement
cannot substitute for it. Confirmed scope deletion clears both families and fences while the
import ledger and host-retirement protection prevent leftover files or delayed work resurrecting
removed content. App-private no-backup custody is unchanged.

Transient failure preserves committed facts/certification and the successful baseline, retaining
removal intent for retry with the same capture. A definitive `AuthoritativeRemovalRejected`
instead returns a validated newer held fact after rollback; the writer withdraws only the matching
uncommitted fence and restores that fact and its tie position. Committed fences cannot be withdrawn.
Recovery peers last until commit or definitive rejection; the durable fence lasts until reset.
Admission and durable failures use static, causeless/redacted errors and content-free diagnostics.
See [writer concurrency](caching-conversation-repository-concurrency.md#state-and-concurrency) for cancellation,
pending-removal ordering and post-commit cleanup.

Removal changes the committed page generation. Old local tokens explicitly reject; resume from
`newestAuthoritativePage` and traverse fresh tokens. Empty pages still retain the durable fence
and conservative metadata. Neither local exhaustion nor this restart recovery certifies history.

#### Retained explicit-retirement evidence (#2256)

The [PR #2262 handoff](https://github.com/pyrycode/pyrycode-mobile/pull/2262) reports separate Linux
focused class runs at `3a9a1fa2e5e5b13cdb40bf5da0b79a33f01400bd`, each through
`python3 scripts/gradle-gate.py testDebugUnitTest --tests '<qualified-class>' --console=plain`:

| Class under `de.pyryco.mobile.data` | Executed/passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| `repository.AuthoritativeOwnedRemovalTest` | 18 | 0 | 0 |
| `repository.AuthoritativeOwnedUpsertTest` | 17 | 0 | 0 |
| `repository.AuthoritativeThreadCacheTest` | 23 | 0 | 0 |
| `repository.AuthoritativeThreadStoreTest` | 15 | 0 | 0 |
| `cache.AuthoritativeRoomStorageTest` | 11 | 0 | 0 |
| `cache.AuthoritativeFileCacheTest` | 4 | 0 | 0 |
| `cache.MigratingConversationCacheTest` | 17 | 0 | 0 |
| **Total reported focused** | **105** | **0** | **0** |

Original focused XML/revisions/hashes were at `/tmp/builder-2256/evidence/rework4-final/` when
reported. They are absent here; the downloaded builder bundle contains source identity/logs but
no focused XML or artifact pointers. These focused counts remain attributed to the builder,
not independently recounted. The accepted verifier handoff for the same source revision in
retained run `097bee94b53f609d8004ac5c3128b5af5ac1f7d7c358d19e938b372a87c4cda3`
independently counted all seven classes in current dispatcher XML: the same 105 methods executed
and passed, zero failures/errors/skips. This is full-suite evidence, not another focused run.

The named `AuthoritativeOwnedRemovalTest` methods executed and passed in that full JVM run:

- `ownershipInvariant_partialRemovalPreservesEveryOtherFact`: 18000 saved facts, 200 owned;
  exact reopen of the 17999 retained facts and the colliding other-host identity, no unrelated
  payload writes or ordinal changes; omission removes nothing.
- `ownershipInvariant_staleCapturesCannotRemoveNewerFacts`,
  `ownershipInvariant_durableNewerRevisionRejectsRemovalAtomically`,
  `ownershipInvariant_roomReplacementRejectsStaleRemovalAtomically` and
  `ownershipInvariant_fileReplacementRejectsStaleRemovalAtomically`: runtime/durable admission,
  exact prior certification, current/observed tie order and unrelated-save recovery.
- `retirementInvariant_replayAndRestoreCannotResurrect`,
  `retirementInvariant_storageFencesReplayAndOldIncarnationAcrossReopen` and
  `retirementInvariant_fileCompatibilityRetainsFenceAndResetLifetime`: replay/replacement,
  reopen, old-incarnation refusal and applied progress 20000 → 30000 → stale 20000 retaining 30000.
- `atomicInvariant_failurePreservesCertificateAndRetryCommits`,
  `retirementInvariant_heldUpsertThenRemovalCommitsFence`,
  `atomicInvariant_cancelledRemovalFinishesCommitBeforeReturning` and
  `retirementInvariant_finalFlushRefreshesRemoval`: rollback/retry, overlap, cancellation and
  removal admitted during final I/O.
- `retirementInvariant_resetDuringRemovalCreatesNewIncarnation`,
  `retirementInvariant_roomReplacementResetAdmitsOnlyNewIncarnation`,
  `retirementInvariant_scopeDeletionKeepsOtherHostExact` and
  `retirementInvariant_emptyPageStillRetainsItsFence`: held reset, fallback failure/retry with
  changed/reused epoch text, exact scope isolation and empty-page fence retention.
- `ownershipInvariant_pendingRejectionsRestoreOriginalTieOrder` and
  `retirementInvariant_successReleasesRecoveryButFailureRetainsIt`: multiple pending rejection
  orders, exact disk/observed ordering, unrelated-save recovery and recovery entries released
  after success while failed/held work retains them.

The same run passed the inherited upsert methods, schema/import checks and original final-retirement
regressions, including `retirementInvariant_resetDuringFinalIoRetiresReusedEpoch` and
`atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts`. The verifier counted 5974 executed,
5973 passed, 1 failed, 0 errors/skips in the complete JVM/build invocation. Its sole unrelated
failure, `AttentionNotifierTest.theSameAlertPostsOnceAcrossRepeatsAndAFreshProcess`, then passed
an exact-method retry: 1 executed/passed, 0 failures/errors/skips. The dispatcher accepted that
flaky disposition; the original failure remains a failure. Configured UI evidence was 334
executed/passed, 0 failures/errors, 1 skipped; scripted-all was 22 executed/passed, 0 failures/errors/skips.

The retained verifier handoff was read from `handoff/verifier-2256.md` in that evidence bundle;
its counted XML is identified by invocation `jvm-yjz58kwi` and retry `jvm-qk01bpr3`. Raw XML is
not present in the downloaded bundle or this documentation worktree, so these counts rely on the
verifier's recorded inspection rather than a new raw-report audit. The older injected gate marker
at `be34dabc0` is not the acceptance revision above. Historical rework4 red evidence reported
3 executed/failed, zero errors/skips; the first repair reported 18 executed, 17 passed, 1 failed,
zero errors/skips. None is relabelled as a pass. Documentation produced no runs; macOS is outside
this Linux ticket's verification scope. No live capture or route activation was required.

#### Retained authoritative mutation and restart evidence

Linux focused evidence at `a0d7f4467c7ba9f13f0bbc11fe8830e2d721015c` (2026-10-18): each class
ran separately through `python3 scripts/gradle-gate.py testDebugUnitTest --tests '<qualified class>'
--console=plain`. Retained JUnit XML, per-class revisions, `results.json` and hashes were read
alongside the [final verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2257#issuecomment-6246324504).
These are prior builder runs; documentation produced no test runs.

| Class under `de.pyryco.mobile.data` | Executed/passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| `repository.AuthoritativeOwnedUpsertTest` | 17 | 0 | 0 |
| `repository.AuthoritativeThreadCacheTest` | 23 | 0 | 0 |
| `repository.AuthoritativeThreadStoreTest` | 15 | 0 | 0 |
| `repository.AuthoritativeThreadQueriesTest` | 23 | 0 | 0 |
| `cache.AuthoritativeRoomStorageTest` | 11 | 0 | 0 |
| `cache.MigratingConversationCacheTest` | 15 | 0 | 0 |
| `cache.AuthoritativeFileCacheTest` | 4 | 0 | 0 |
| `repository.CoalescedThreadWritesTest` | 41 | 0 | 0 |
| **Total** | **149** | **0** | **0** |

All 17 owned-upsert methods are present and passed in the retained report, including
`boundedInvariant_ownedRevisionAndCertificationPreserveUnloadedFacts` (18000 saved/200 owned),
identity/ordering, deferred-import admission, failure/retry, post-commit cancellation, reused-epoch
reset, final-flush I/O/lock waits, retention/certification and deletion/other-host isolation.
They compare exact fresh cache/repository facts and metadata plus one-item page traversal;
mutations reject stale continuations before restart-from-newest recovery.

Retained numeric work below excludes fixture seeding/restoration; every row writes one header.
Ordinary saves add no full reads/replacements and preserve unrelated ordinals. The fixture's
replacement counter remains 1; held-read fixtures also retain their initial restore read.

| Probe operation | Payload reads/decodes | Input decodes/serializations/writes | Deletes | Scalar placement reads/writes |
| --- | ---: | ---: | ---: | ---: |
| 18000-fact newest revision | 1 / 1 | 1 / 1 / 1 | 0 | 0 / 0 |
| Addition | 0 / 0 | 1 / 1 / 1 | 0 | 2 / 0 |
| Certification-only | 0 / 0 | 0 / 0 / 0 | 0 | 0 / 0 |
| `orderingInvariant_heldUnchangedPeerAnchorsChangedRevision` | 1 / 1 | 1 / 1 / 1 | 0 | 2 / 0 |
| `orderingInvariant_heldUnchangedRevisionPersistsPlacementOnly` | 0 / 0 | 0 / 0 / 0 | 0 | 3 / 1 |

Raw reports were at `/tmp/builder-2255/evidence/recovery-focused/` when recorded; they are
retained evidence locations, not gate worktree build paths. The last two ordering probes first
failed against `b50eef33ffc50b8f98e79b32dd8c952b6cce53a7`: 2 executed, 2 failed, 0 errors/skips
(`recovery-red/`). Earlier live-before-restore red evidence at `23c69e6c9` recorded 1 executed,
1 failed, 0 errors/skips (`rework3-red/`). These historical failures remain failures.

The dispatcher's complete JVM gate at the same final revision additionally reports 5928
executed/passed, 0 failed/skipped, including all named owned-upsert scenarios. This is a full-suite
result, separate from the focused runs above. macOS is outside this Linux ticket's verification
scope; this storage seam requires no live route-activation proof.

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
