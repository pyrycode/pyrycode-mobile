# Conversation cache — Layout

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Layout

Since #2161, `appModule` binds `MigratingConversationCache` through
`openConversationCache(Context)`. Supported authoritative threads live in Room at
`<noBackupFilesDir>/conversation-cache.db`, including its sidecars. Lists and read
positions still use `FileConversationCache` under `<noBackupFilesDir>/conversations`;
settings and attachment storage are unchanged. The paths below describe those metadata
files and the retained version-1 thread files. Since #2385, only authoritative files are import
sources. Legacy `threads/` files are ignored during opening, reading and import, including
unknown-owner hashes; ownership is never inferred from filenames. Since #2386, schema 12
discards the legacy Room family too. Its backend, entities, DAO and wrapper delegations are
removed; dormant interface defaults and file codecs remain for #2387. Explicit scoped removal
still cleans up the corresponding files. No reopen recreates legacy tables or imports their files.

The production opener retains explicit automatic migrations through every shipped schema,
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12, without destructive fallback.
Exported schemas under
`app/src/main/roomSchemas/de.pyryco.mobile.data.cache.ConversationCacheDatabase/`
retain versions 1–11 unchanged alongside the new `12.json`. The 11 → 12
`DiscardLegacyMigration` uses `DeleteTable` for `legacy_headers`, `legacy_rows` and
`legacy_evidence`; SQLite drops their exclusive indexes with them. Supported tables are
`authoritative_headers`, `authoritative_items`, `authoritative_retirements` and `file_imports`,
with authoritative indexes retained on upgrades and fresh creation.

Earlier migrations still supply supported defaults and backfills. `OwnedRowsMigration`
retains its historical 3 → 4 codec dependencies until #2387; malformed obsolete row bytes
must survive that step without blocking their eventual discard. `DisplayWindowMigration`,
`RenderPlacementMigration` and `PlacementWitnessMigration` retain display, nested placement
and witness/contribution backfills. Missing older fields receive their supported SQL defaults
(including empty incarnation, zero flags/ids, empty placement keys and maximum renderer order)
and applicable backfills; existing raw payloads, identities, revisions, order, epoch,
incarnation, applied progress and certification/continuation retain their meaning. Backfill
work belongs to upgrade, not ordinary reopen. See the [all-schema proof](conversation-cache-testing.md#legacy-discard-upgrades-and-reopen-proof-2386).

Version 3 introduced the shared import ledger. Its primary key is `(host, conversation,
family)`, with static `outcome` values. Families are `legacy`, `authoritative` and `host`;
a host-wide retirement uses family `host` and conversation `""`. Historical legacy ledger
records and both thread families' retirement fences remain; legacy operations no longer read
or create import completion records. Authoritative imported content/evidence
and its completion marker commit in one transaction. Completed imports leave their source
bytes intact until explicit conversation or host removal; the ledger prevents a leftover
file from replacing a newer database save. See [import classification and write
refusals](conversation-cache-contract.md#the-contract) and [durable retirement](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost).

```
<root>/<sha256hex(serverId)>/conversations.json
<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json   (#797)
<root>/<sha256hex(serverId)>/read-positions.json                       (#877)
<root>/<sha256hex(serverId)>/authoritative-threads/<sha256hex(conversationId)>.json   (#2149)
```

A server id is daemon-supplied and opaque, so it is never pasted into a path: the host
directory name is the lowercase hex SHA-256 of the id's UTF-8 bytes. That means no `/`, no
`..`, no NUL and no reserved name can ever reach a path component, and no server id enters the
filesystem namespace at all — proven by a test that writes a `../../etc/passwd`-shaped server
id and asserts every stored file stays under the canonical root. A **conversation id is hashed
the same way** and filed under its host's directory rather than in a flat namespace, so
`removeHost`'s existing recursive delete of the host directory covers `threads/` for free with
no separate removal step. The hash is namespace derivation, not a security boundary — the
property relied on is collision resistance, not secrecy.

The authoritative family uses the same app-private `noBackupFilesDir` cache root and
hashed host/conversation namespaces. Its separate `CachedAuthoritativeThread(version,
serverId, conversationId, saved)` envelope currently has version 1 and verifies both owner
values on read. Sharing a numeric version with the legacy format does not make the documents
interchangeable: legacy row readers/writers never touch `authoritative-threads/`. Wrong owner,
version or invalid saved facts withhold the entire authoritative restore without rewriting it.

Under the file-cache mutex, one temporary-file/atomic-move replacement saves epoch, raw full
items, applied version, certified checkpoint and continuous coverage/page continuation together.
Unknown fields/kinds and explicit-null clears remain inert raw facts. Retention keeps at most
100000 full items in daemon order, including active and unknown kinds; trimming clears the
checkpoint and coverage, preserving only applied progress alongside retained items. See
[the authoritative contract](conversation-cache-contract.md#the-contract) for validation and
certification rules. Exported saved records and the envelope redact their string representations;
no cached content or owner values enter diagnostics. This remains the existing plaintext,
app-private, backup-excluded storage model.

`removeConversation` removes both thread documents for that host/conversation;
`removeHost` removes both directories with the host namespace. Same-id conversations under
another host are unaffected. Ordering of removal against pending, in-flight and final writes
belongs to the [wrapper's fences](caching-conversation-repository-concurrency.md#state-and-concurrency),
not to path hashing or the file mutex alone.

The stored document is a versioned envelope (`version: Int`, `conversations: List<...>`),
mirroring `StoredPairings`. The cache-local record type — not `@Serializable` annotations on
`Conversation` itself — is deliberate: see
[data model § What's deliberately absent](data-model.md#whats-deliberately-absent). `lastUsedAt`
is stored as `Instant.toString()` / parsed back with `Instant.parse(...)`, not epoch millis, so
the round-trip is exact to the nanosecond rather than truncated to millisecond precision.

### Indexed legacy Room layout (#2162)

[ADR 0009](../decisions/0009-room-for-indexed-conversation-cache.md) records the approved
SQLite/Room choice. `ConversationCacheDatabase` originally shipped at schema version 1, using Room 2.8.4
and KSP 2.3.6; the exported schema lives under `app/src/main/roomSchemas/`.
`openConversationCacheDatabase(Context)` in `di/ConversationCacheDatabaseFactory.kt` opens
`<noBackupFilesDir>/conversation-cache.db`. Its journal, WAL and SHM sidecars share that
backup-excluded directory. There is no destructive migration fallback. Owner strings are
bound SQL values, never filenames, with SQLite's default binary, case-sensitive equality.
The existing plaintext app-private cache threat model remains unchanged.

Schemas 1–11 historically included `legacy_headers`, `legacy_rows` and
`legacy_evidence`, with ordinal and evidence indexes. Schema 12 removes them and
`LegacyRoomStorage`. Legacy evidence cannot certify authoritative facts. Historical schema
exports remain the source for installed-database fixtures; changing only a current database's
version no longer models an older installation after table removal.

### The additive version 1 → 2 migration and authoritative tables (#2163)

Version 1 → 2 introduced authoritative storage additively. Upgrades now continue through
schema 12, discarding the legacy tables only at the final step. Supported Room storage keeps:

- `authoritative_headers`: exact host/conversation owner, epoch, per-write replacement
  generation (a fresh UUID per write), applied version, checkpoint, optional serialized coverage, item count and
  incarnation. Its primary key is `(host, conversation)`.
- `authoritative_items`: exact owner, epoch and item id, stable ordinal/order and complete
  raw JSON payload. The primary key includes `(host, conversation, epoch, id)`. The unique
  owner/epoch/ordinal index and display, extra, parent, placement, order/placement and
  contribution indexes support bounded authoritative selection.
- `authoritative_retirements`: exact host/conversation/item id and retired revision, keeping
  stale item replay from resurrecting removed facts.

Unknown raw fields remain facts rather than certification. See the
[authoritative contract](conversation-cache-contract.md#indexed-authoritative-room-storage-and-pages-2163)
for validation, continuation and certification rules.

**Atomic scoped removal.** `ConversationCacheDatabase.removeScope(host, conversation)`
deletes authoritative items, item retirements and headers for the exact owner in one Room
transaction. The migration-aware wrapper commits that removal together with shared ledger
retirement markers; see [removal fences](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost).
Ordinary authoritative replacement remains separate from scoped retirement. Metadata and
read positions retain file-backed ownership; provisional sends retain their independent
in-memory lifetime and gain no process-death persistence.

### The cache-local record must mirror every `Conversation` field (#999)

Being cache-local cuts both ways: nothing forces `CachedConversation` to track a field added to
`Conversation`, so a new domain field silently stops surviving a restart unless someone remembers
to extend the record by hand. `CachedConversation`/`Conversation.toRecord()`/
`CachedConversation.toDomain()` carry `muted: Boolean = false` beside `archived` for this reason —
[data model § `Conversation`](data-model.md#conversation)'s first pass mirrored `archived` through
the wire DTOs and missed this cache, and `HostConversationSource` publishes
`store.readConversations(...)` as the host's rows on start, before any live list arrives, so a
restored muted channel would have read `muted = false` and alerted on cold start. The `= false`
default keeps a document written before this field existed readable, the same reasoning as every
other additive field in this cache (see `CachedAttachment` above). Adding a boolean like this to
`Conversation` means updating this record and both mapping functions, not only the DTOs — check
here first, before the wire layer, since a cache miss is the harder failure to notice.

`CachedConversation.pinnedAt: String? = null` (#2213) mirrors `Conversation.pinnedAt` beside `muted`
and `workspaceLabel`, following `lastUsedAt`'s exact-text convention rather than epoch millis:
`Conversation.toRecord()` writes `pinnedAt?.toString()`, and `CachedConversation.toDomain()` restores it
with `pinnedAt?.let(Instant::parse)`, so a pin round-trips to the nanosecond across a fresh cache
instance. The host and conversation id stay the hashed-directory scope this cache already uses, so two
hosts sharing a conversation id keep independent pins — there is no cross-host identity to collide on.
The `= null` default keeps a document written before this field existed readable as unpinned, the same
reasoning as `muted`'s own default; `readConversations` does not retroactively repair such a document,
it only reads it as unpinned until the next write. A malformed stored `pinnedAt` (one that does not parse
as an `Instant`) withholds the whole document under this cache's existing parse-failure behavior — see
[Failure model — graceful reads, reporting mutations](conversation-cache-failure-model.md#failure-model--graceful-reads-reporting-mutations)
— rather than rewriting or dropping only that field; `FileConversationCacheTest` pins that the stored bytes
are left untouched. Unlike `agent` below, this field was added to the cache in the same ticket that added
it to the DTOs, so there is no cold-start window where a restored row reads unpinned while the live list
disagrees — `HostConversationSource`'s cold-start publish and the first live `conversations`/
`conversation_updated` snapshot agree on the same field from the start.

**Known gap, not yet fixed: `agent` ([#1108](https://github.com/pyrycode/pyrycode-mobile/issues/1108)).**
`Conversation.agent` was added without touching this cache — deliberately out of that ticket's scope, since
nothing renders the field yet and the mobile does not negotiate `multi_agent`, so the key never actually
arrives today. `CachedConversation` still has no `agent`, so `CachedConversation.toDomain()` produces the
default `ConversationAgent.Claude` on every restore, and `HostConversationSource` publishes that row on cold
start before the live list arrives — the same window this section describes for `muted`, but left open here
because there is no observed failure yet to fix against (Evidence-Based Fix Selection). The ticket that first
reads `Conversation.agent` for the model picker or the agent switch should either persist it next to `muted`
here or gate on the live list before trusting a cold-started value.

The thread document is the same shape, file-private to `FileConversationCache.kt`:
`CachedThread(version: Int, rows: List<CachedThreadRow>, history: CachedHistoryPosition? = null)`,
`CachedThreadRow(message: CachedMessage? = null, boundary: CachedBoundary? = null, banner:
CachedBanner? = null, compaction: CachedCompaction? = null, refusal: CachedRefusal? = null,
stopped: CachedStoppedTurn? = null)` — exactly one of the six is set, mapping
`ThreadItem.MessageItem` / `ThreadItem.SessionBoundary` / [`Banner`](banner-notice-row.md) /
[`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874-1358) /
[`ModelRefusal`](model-refusal-row.md) / [`StoppedTurn`](stopped-turn-row.md)
(never `UnrecognizedMessage`, which `cacheableThreadRows`
drops before a `CachedThreadRow` is ever built; `ThreadItem.toRecord()` throws if it ever reaches
it). The three newer fields (#1353) default to `null`, so a document written before this ticket —
holding only `message`/`boundary` rows — still decodes, and the stored `version` stays 1.
`CachedMessage(id, sessionId, role, content, timestamp, tool: CachedToolCall? = null, attachments:
List<CachedAttachment> = emptyList())` carries no `isStreaming` field — a restored row is always
settled, so the field would have nothing to encode. `CachedToolCall(toolName, input, output,
status, inputFields: Map<String, String> = emptyMap())` — `inputFields` added by #1575 so a restored
`Bash` row keeps its described/simple [header](tool-call-row.md#subject-and-elapsed-text) instead of
falling back to the `input_summary` précis; the default keeps a document written before #1575 (no
`inputFields` key) readable with empty fields, the same additive pattern as `CachedMessage.attachments`
(#983). `denial` and `resultDetail` stay uncached. `CachedToolCall.parentToolUseId: String? = null`
(#2221) is the tool-row half of subagent nesting: it is written from `ToolCall.parentToolUseId.ifEmpty
{ null }` and read back with `.orEmpty()`, the same nullable-not-`""`-defaulted shape as
`CachedMessage.parentToolUseId` below, for the same reason — see that field for the full rationale.
`CachedBoundary(previousSessionId,
newSessionId, reason, occurredAt, workspaceCwd:
String? = null)` round out the two original row kinds. `CachedBanner(level, text, truncated,
occurredAt)`, `CachedCompaction(preTokens: Long? = null, postTokens: Long? = null, manual,
occurredAt)` and `CachedRefusal(originalModel, fallbackModel: String? = null, banner,
bannerTruncated, occurredAt)` carry every field their domain row holds, stored verbatim as message
content already is — the render path owns stripping either way, not the cache. Enums serialize by
name; `Instant` fields (`timestamp`, `occurredAt`) follow `lastUsedAt`'s ISO-text convention, not
epoch millis. `occurredAt` is also each of the three new kinds' dedupe key — the same field
`ThreadRow.listKey()`, `HistoryPageReducer`'s `holdsBanner`/`holdsCompactionBoundary`/
`holdsModelRefusal` and `decodeThread` below all join on.

`CachedMessage.reconciliationId: String? = null` (#1941) round-trips the original ordinary id
through both mappings alongside the emitted `id`. The optional null default keeps older records
readable under version 1. Retaining only an emitted collision alias would make an original daemon
page look like a new ordinary row after process death. Explicit metadata preserves replay matching
without parsing aliases that could themselves be real daemon ids. Segment identity still comes
from `CachedSegment`; when reconciliation converts legacy content to segments, ordinary-only
metadata is cleared. See [Message identities](data-model.md#message) and
[restore ownership](caching-conversation-repository.md).

Assistant attribution (`Message.parentToolUseId`, #1826) is persisted as of #2221, which supersedes
\#1826's "disk serialization is unchanged" / "attribution is not serialized" decision for this field.
`CachedMessage.parentToolUseId: String? = null` is written from `Message.parentToolUseId.ifEmpty { null
}` and read back with `.orEmpty()`. The reconciliation field above still does not carry attribution — it
is a separate mechanism — and the document version is unchanged: the field is additive and nullable
rather than `""`-defaulted, so with `explicitNulls = false` a parentless main-thread row encodes byte-
for-byte as it did before #2221 and its `cachedThreadRowProof`/stored coverage hashes stay valid. Only a
row that carries a parent gains the key.

A document written before #2221 has no key for either `parentToolUseId`, so it restores with both empty
(unattributed/top level) exactly as before, through the same `ignoreUnknownKeys`/default-field mechanism
every other additive cache field here uses — it is not retroactively repaired. From then on, same-
conversation wire/history evidence still enriches an unattributed restored row in memory through
`mergeCachedRows`, including legacy rows without recoverable segment records, and the wrapper still
retains reconciled rows across reconnect, so a later unattributed cache copy cannot erase a known
parent. What #2221 changes is that the *next* write of an enriched row — including the collector's final
flush on cancellation — persists the parent it learned, so a subsequent restore (including after a
process restart) already carries it instead of relying solely on in-memory enrichment. See [parent
precedence through seams and merges](remote-conversation-repository-assistant-reply-segments.md)
for conflict handling. `AssistantParentCacheTest.diskFormatKeepsAttribution_wireEvidenceEnrichesUnattributedRows_andReconnectKeepsIt`
and `LegacyRowCodecTest` verify the attributed round trip, the pre-#2221 unattributed restore, enrichment
and reconnect/flush retention, and that a parentless row's encoded bytes and proof are unchanged.

`CachedAttachment(attachmentId, displayName: String? = null, mimeType: String? = null)` (#983) maps
`Message.attachments` 1:1; `explicitNulls = false` omits a `null` hint on encode rather than writing
`"displayName":null`, and a document written before this field existed decodes with `attachments =
emptyList()` through the same `ignoreUnknownKeys`/default-field mechanism every prior additive cache
field has used. Like `MessageAttachment`, its generated `toString` is overridden to print only
`attachmentId` — the name and MIME hint are untrusted display text (see [data model §
`Message`](data-model.md#message)) and this file-private class is exactly the kind of type a stray
log call could otherwise reach.

`CachedHistoryPosition(cursor, atStart, coverage = null)` mirrors the domain position without a
schema-version change. `CachedThread.history` still defaults to null, and older position records
decode with null coverage. Its `toString` prints only `atStart`; `HistoryCoverage.toString` prints
span/gap counts and the unknown flag, never opaque cursors, identity proofs or entry content.

### Thread document readers (#1949)

`readThread` and `readHistoryPosition` share one immutable `DecodedThread`.
Under the existing mutex, each read opens the current document and checks its exact
bytes through EOF with a bounded buffer before reuse. Both host/conversation path
and every byte, including length, must match; length/mtime alone miss same-size,
same-time replacement by another cache instance. Only one document is retained.

Mutations clear the retained decode before running. Missing files, changed bytes,
a different path or unreadable storage prevent reuse. Host changes cannot borrow
another host's decoded document. Since #2050, a captured read owns its immutable
bytes even if a later mutation retires them; it cannot republish that snapshot into
the cache. Future reads must establish freshness again.

`readThread` decodes the complete versioned document into typed `CachedThreadRows`
and validates every row before returning. Optional history no longer blocks row
readability. `readHistoryPosition` captures the same snapshot under the mutex,
then resolves its synchronized lazy history outside it: typed header decoding,
legacy/malformed-metadata fallback, validation and content-bound proofs all remain
required before any position is returned. Identical-byte reads share that work;
no background job or scope is introduced. Invalid rows withhold both rows and
position; invalid optional metadata leaves readable rows without a position.
Field order and unknown fields preserve this boundary. Metadata writers retain
the full typed `CachedThread` decoder and independent fallback.

A rows-only API is insufficient if its repository waits for optional coverage to
seed order before publishing. See [rows-first merging](caching-conversation-repository.md)
and [controlled first-draw evidence](thread-screen-testing-saved-first-draw-comparisons.md#allocation-margin-and-retained-evidence-2018).
Canonical persisted bytes, SHA-256 proofs and the serializer/provider stay unchanged.

Since #2026, thread records, their independent fallback, header reads and optional
metadata use immutable decoder-only `threadReadJson = Json(MobileJson) { explicitNulls = true }`.
Every nullable field in those records has a default, so omitted and explicit-null
values retain their meanings without per-record implicit-null tracking allocations.
Required fields remain required. Conversation/read-position readers, all writers
and canonical row-proof encoding stay on `MobileJson`, including its omitted-null
encoding: changing the encoder would change persisted proof inputs. Keep decoder
configuration separate from canonical encoding when optimizing restore.

All six thread row kinds use `parseThreadInstant` (#2026). Only fixed-width
`YYYY-MM-DDTHH:mm:ssZ` text takes the allocation-saving path: ASCII components are
read without substrings, validated by standard `LocalDateTime.of`, then converted
from UTC epoch seconds to the domain `Instant`. Other forms and invalid fast-path
components fall through to the original `Instant.parse`; fractions, offsets,
extended years, leap seconds and hour 24 retain that parser's existing behavior.
No custom calendar arithmetic or accepted-input widening is involved. This also
reduces the mandatory metadata writer reread's allocation before cold restore.
`DecodedThreadRestoreTest.persistedTimestampParsingRetainsCalendarValidationAndLegacyFormats`
pins the fallback and calendar behavior. Freshness, version, row/metadata rejection
and canonical proof checks remain in the same synchronous operation; see
[measured first-draw evidence](thread-screen-testing-saved-first-draw-comparisons.md#allocation-margin-and-retained-evidence-2018).

Row validation uses one identity-set pass and rejects every running tool and
kind-specific duplicate as before. Coverage validation checks already-normalized
unsigned spans in stored order and each gap against its neighbouring spans; it
must reject reversed, overlapping, adjacent or unsorted spans rather than repair
them by sorting/merging. Maximum unsigned endpoints remain valid. Fresh-byte tests
include same-length replacements across buffer/multibyte boundaries, valid
whitespace growth/truncation and rejection of a trailing second JSON document.

Coverage remains checked against every retained direct proof and any legacy binding.
When no legacy aliases exist, only alias-specific work is skipped. `retainedBy` reuses
the immutable coverage only after every claim matches its freshly calculated proof;
missing claims and delta-proof replacements still use the pruning/update path. Hash
inputs, full SHA-256 digests and lowercase hex encoding remain compatible with persisted
records. Proof and restore-order batches each own a local digest, reset by `digest()`
between independent inputs; it never crosses a suspension point or thread boundary.

See [decode freshness tests](conversation-cache-testing.md#testing) and the
[committed-frame restore regression](thread-screen-testing-saved-first-draw.md#saved-thread-first-draw-1949).

### History coverage compatibility views (#2018)

Unsigned stored spans, gaps, cursors, walks, row order and entry claims remain the
authoritative coverage representation. Signed compatibility collections and their
span-derived `highWater`/`unknownEdge` are computed once on demand with thread-safe
`lazy`, avoiding unused copies during unsigned restore. Public getter types and
signed clipping/filtering are unchanged: spans clip at `Long.MAX_VALUE`, while
unrepresentable ids are omitted from the applicable views. Each copied coverage
owns independent lazy views; accessing them does not add disk fields.
`UnsignedHistoryCoverageTest.signedCompatibilityInvariant_restoredAndCopiedViewsStayIndependentAndOffDisk`
pins these properties alongside existing signed/unsigned validation tests.

### The thread document's two writers (#1354)

These describe the retained `FileConversationCache` compatibility writer. Since #2161,
application thread writes use Room through the migration-aware cache; the repository's
ordering and successful-baseline rules below still apply.

Both writers rewrite one thread document under the file cache's `Mutex`, preserving the other
half. Since #2018 they encode the same `CachedThread` serializer/configuration directly
to a buffered UTF-8 temporary-file stream, close it, then perform the existing atomic
replacement. This avoids whole-document string/byte write buffers without changing
version, fields, row order, proofs, retention or error classification. Other document
writers keep their text encoding. Since #1832 they also validate durable claims
against the rows actually retained:

- **`writeThread`** receives untrimmed drawn rows, applies `cacheableThreadRows`, reads the stored
  position through the header-only decode, and calls `coverage.retainedBy(kept)`. Changed or removed
  row proofs invalidate every associated producing entry id, including tool-use/result producers.
  Row-limit trimming resets backwards cursor/`atStart` while preserving conservative gap metadata.
  Without coverage it retains #1354's trim behavior of dropping the position altogether.
- **`writeHistoryPosition`** reads the stored rows through the validated decode and checks/binds
  coverage against them before replacement. One validated domain-row list is reused
  for old-metadata validation and incoming claim retention/binding; the original
  stored records are serialized. A corrupt row document supplies no retained rows;
  a null clear of a never-written document remains a no-op. The wrapper must have written rows
  first: the file lock prevents torn read-modify-write, but does not by itself order two caller
  operations. Its [rows-before-state mutex](caching-conversation-repository.md)
  supplies that ordering and preserves the trim reset in the subsequent state write.

Deferred observer scheduling (#1967) does not change this document format. The wrapper's
`historyWrites` mutex orders coalesced row writes, coverage row/state saves and confirmed
removal. Both row writers publish actual successful cacheable rows into a shared baseline,
including across observer restarts. Coverage row success satisfies candidates through the
maximum captured history/selected drawn generation; worker publication order cannot make an
older candidate eligible to overwrite newer coverage. Observer success preserves that boundary.
Satisfaction advances even when the following position save fails, because its rows reached disk.

Capture order alone cannot distinguish retained coverage from a save captured before observer
entry that completes during restoration. The wrapper captures the successful `coverageRevision`
before reads and advances it only after a successful coverage row write; observer writes preserve
it. Already retained coverage cannot make an unchanged failed restore erase rows. A coverage save
completed during this collection can instead permit a newer intentional removal. See [the
wrapper's saved-history rules](caching-conversation-repository.md)
for candidate eligibility. Generations, revisions and baselines are destination-local metadata,
not serialized fields.

Both row mutations and their successful baseline bookkeeping finish together non-cancellably
under the wrapper lock. Atomic replacement may commit before a cancellable dispatcher return
reports success; losing that result would let final flush compare with stale rows and skip a
newer removal. Lock acquisition remains cancellable. Confirmed deletion marks its tombstone,
waits for any started mutation and removes its results; pending and cleanup writes check the
tombstone inside the same lock. A refused daemon deletion leaves cache state intact.

Ordinary row proofs hash the exact cache-policy record, including retained tool output and
attachments; an earliest order id or message text alone cannot prove all mutable producers.
Assistant deltas use fragment-specific proofs. A received delta already present inside a legacy
whole-turn row binds to ordered, non-overlapping text offsets and the retained whole-row hash.
The whole-row hash proves custody of that row, not each delta: every alias also needs overflow-safe
bounds and a matching fragment hash, with slices ordered by unsigned durable id. Restore and stale
writers apply the same checks; overlapping, reversed or mismatched slices cannot retain claims.
Only hashes, offsets and lengths persist; received delta text remains transient. Such a match
proves retention, never legacy completeness. Missing or changed proofs remove claims, so cache
policy exclusions cannot silently certify discarded cacheable content. Received non-rendering
entries can still establish spans without storing their raw envelopes.

`BackgroundTaskLifecycle` stays excluded before the row limit and serialization and adds no
persisted record. It survives only in the [wrapper's in-memory connection base](caching-conversation-repository.md).

**Trim accounting must use serialization's exclusions.** `threadRowsWereTrimmed` compares the
kept count with settled rows excluding `UnrecognizedMessage` and `BackgroundTaskLifecycle`.
Counting excluded markers would falsely clear cursor/stop below the cap. Pre-trimming in a caller
would instead hide genuine loss. Test the complete wrapper row/state operation through a fresh
file-cache restore: testing `writeThread` alone can pass while a later state write restores the
cursor or `atStart` that trimming just invalidated.

### Read positions (#877)

The read-position document is a versioned envelope over an **array**, not an object:
`CachedReadPositions(version: Int, positions: List<CachedReadPosition>)`,
`CachedReadPosition(conversationId, completedTurnId, readTurnId: String? = null)`. An array
keeps every daemon-authored conversation id a JSON *value*, matching the rest of this cache's
never-an-id-as-a-key discipline (see § Layout above — a conversation id is hashed for a path
for the same reason). `decodePositions` rejects a document whose entries do not have distinct
`conversationId`s, the same duplicate-identity rule `readConversations` applies to
`Conversation.id`, rather than picking a winner.
