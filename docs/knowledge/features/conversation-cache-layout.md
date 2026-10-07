# Conversation cache — Layout

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Layout

```
<root>/<sha256hex(serverId)>/conversations.json
<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json   (#797)
<root>/<sha256hex(serverId)>/read-positions.json                       (#877)
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

The stored document is a versioned envelope (`version: Int`, `conversations: List<...>`),
mirroring `StoredPairings`. The cache-local record type — not `@Serializable` annotations on
`Conversation` itself — is deliberate: see
[data model § What's deliberately absent](data-model.md#whats-deliberately-absent). `lastUsedAt`
is stored as `Instant.toString()` / parsed back with `Instant.parse(...)`, not epoch millis, so
the round-trip is exact to the nanosecond rather than truncated to millisecond precision.

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
CachedBanner? = null, compaction: CachedCompaction? = null, refusal: CachedRefusal? = null)` —
exactly one of the five is set, mapping
`ThreadItem.MessageItem` / `ThreadItem.SessionBoundary` / [`Banner`](banner-notice-row.md) /
[`CompactionBoundary`](session-boundary-delimiter.md#compactionboundarydivider-874-1358) /
[`ModelRefusal`](model-refusal-row.md) (never `UnrecognizedMessage`, which `cacheableThreadRows`
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
(#983). `parentToolUseId`, `denial` and `resultDetail` stay uncached. `CachedBoundary(previousSessionId,
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

Assistant attribution (`Message.parentToolUseId`, #1826) is also absent from disk serialization;
`CachedMessage`/`CachedSegment` and the document version are unchanged. Cache-only assistant rows
therefore restore with unknown (empty) attribution. Same-conversation wire/history evidence enriches
those rows in memory through `mergeCachedRows`, including legacy rows without recoverable segment
records. The wrapper retains reconciled rows across reconnect, so a later unattributed cache copy
cannot erase a known parent. A process restart can lose that hint until fresh evidence arrives.
See [parent precedence through seams and merges](remote-conversation-repository-assistant-reply-segments.md#parent-attribution-through-seams-and-merges-1826)
for conflict handling. File-cache round-trip and wrapper probes verify omission from stored bytes,
restoration as empty, enrichment and reconnect retention without a cache migration.

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

### The thread document's two writers (#1354)

Both writers rewrite one thread document under the file cache's `Mutex`, preserving the other
half. Since #1832 they also validate durable claims against the rows actually retained:

- **`writeThread`** receives untrimmed drawn rows, applies `cacheableThreadRows`, reads the stored
  position through the header-only decode, and calls `coverage.retainedBy(kept)`. Changed or removed
  row proofs invalidate every associated producing entry id, including tool-use/result producers.
  Row-limit trimming resets backwards cursor/`atStart` while preserving conservative gap metadata.
  Without coverage it retains #1354's trim behavior of dropping the position altogether.
- **`writeHistoryPosition`** reads the stored rows through the validated decode and checks/binds
  coverage against them before replacement. A corrupt row document supplies no retained rows;
  a null clear of a never-written document remains a no-op. The wrapper must have written rows
  first: the file lock prevents torn read-modify-write, but does not by itself order two caller
  operations. Its [rows-before-state mutex](caching-conversation-repository.md#the-saved-history-position-1354)
  supplies that ordering and preserves the trim reset in the subsequent state write.

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
persisted record. It survives only in the [wrapper's in-memory connection base](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

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
