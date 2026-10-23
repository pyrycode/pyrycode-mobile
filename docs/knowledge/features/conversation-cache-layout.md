# Conversation cache — Layout

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Layout

Since #2161, `appModule` binds `MigratingConversationCache` through
`openConversationCache(Context)`. Supported authoritative threads live in Room at
`<noBackupFilesDir>/conversation-cache.db`, including its sidecars. Lists and read
positions still use `FileConversationCache` under `<noBackupFilesDir>/conversations`;
settings and attachment storage are unchanged. The paths below describe those metadata
files and retained version-1 authoritative thread files. Since #2385, only authoritative files are import
sources. Legacy `threads/` files are ignored during opening, reading and import, including
unknown-owner hashes; ownership is never inferred from filenames. Since #2386, schema 12
discards the legacy Room family too. Its backend, entities, DAO and wrapper delegations are
removed; #2387 also removes dormant row/history methods, models and file codecs. Explicit scoped removal
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
retains the named historical 3 → 4 schema step with no content callback (#2387); no retired
row decoder or identity helper runs, even for malformed obsolete bytes. `DisplayWindowMigration`,
`RenderPlacementMigration` and `PlacementWitnessMigration` retain display, nested placement
and witness/contribution backfills. Missing older fields receive their supported SQL defaults
(including empty incarnation, zero flags/ids, empty placement keys and maximum renderer order)
and applicable backfills; existing raw payloads, identities, revisions, order, epoch,
incarnation, applied progress and certification/continuation retain their meaning. Backfill
work belongs to upgrade, not ordinary reopen. See the [all-schema proof](conversation-cache-testing.md#legacy-discard-upgrades-and-reopen-proof-2386)
and [integrated source attribution](conversation-cache-testing.md#integrated-storage-retirement-acceptance-2364).

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
<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json   (inert obsolete files only)
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
values on read. Obsolete row files have no decoder or conversion route into this family. Wrong owner,
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
other additive metadata field in this cache. Adding a boolean like this to
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

### Retired thread-file representation

The row/history envelope, six row codecs, reconciliation/fragment proofs and dual row/history
writers described by earlier cache revisions were removed in #2387. No supported decoder or
upgrade callback reads those bytes. Historical format details remain in past specs and the
frozen archive; they are not a compatibility acceptance path. Retained latency observations
are in [saved-first-draw history](thread-screen-testing-saved-first-draw.md).

### Thread document readers (#1949)

The former `DecodedThread` row/history reader is retired. Supported file restoration reads
`SavedAuthoritativeThread` through its validated owner-scoped envelope; production opening
uses the [bounded authoritative window](conversation-cache-contract.md#bounded-authoritative-display-windows-2333).

### History coverage compatibility views (#2018)

Legacy row/fragment coverage views are retired. Supported certification is the authoritative
checkpoint/coverage/continuation contract above; local paging never increases it.

### The thread document's two writers (#1354)

The former independent row/history writers are retired. Authoritative file replacement and
Room owned mutations retain atomicity and retirement fences; the repository's
[successful-baseline and writer rules](caching-conversation-repository.md#what-is-written-and-when)
remain required.

### Read positions (#877)

The read-position document is a versioned envelope over an **array**, not an object:
`CachedReadPositions(version: Int, positions: List<CachedReadPosition>)`,
`CachedReadPosition(conversationId, completedTurnId, readTurnId: String? = null)`. An array
keeps every daemon-authored conversation id a JSON *value*, matching the rest of this cache's
never-an-id-as-a-key discipline (see § Layout above — a conversation id is hashed for a path
for the same reason). `decodePositions` rejects a document whose entries do not have distinct
`conversationId`s, the same duplicate-identity rule `readConversations` applies to
`Conversation.id`, rather than picking a winner.
