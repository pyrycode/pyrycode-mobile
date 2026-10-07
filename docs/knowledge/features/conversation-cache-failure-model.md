# Conversation cache — Failure model — graceful reads, reporting mutations

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Failure model — graceful reads, reporting mutations

The same two-sided contract [paired server store](paired-server-store.md#failure-model--graceful-reads-strict-mutations)
uses:

- **Reads are graceful.** A missing root, missing host directory, missing file, invalid JSON,
  an unsupported stored `version`, an unparseable `lastUsedAt`, or a **duplicate
  `Conversation.id` inside one host's document** all yield `emptyList()` after one `RelayLog.d`
  line carrying the operation and a static failure code. A read never repairs, rewrites or
  deletes what it could not parse — a truncated document is still on disk, byte-for-byte,
  after the read that failed to parse it.
- **Mutations report.** `writeConversations`, `removeHost` and `removeConversation` return
  `Result<Unit>`, the same idiom `AppPreferences.editWorkspace` uses for a fallible `data/`
  write. A failure carries `ConversationCacheException("<operation> failed: <code>")` with
  **no cause attached** — a serialization or IO message can embed a conversation name or cwd,
  and a crash reporter prints causes, so attaching one would put cached content into a report.
  Same reasoning as `PairedServerStoreException`.

Failure classification: `SecurityException` → `denied`; `IOException` → `io`;
`IllegalArgumentException` (kotlinx-serialization failures and `Instant.parse` failures are
both this) → `invalid_data`. Anything else, cancellation included, propagates unchanged.

**`readThread` (#797) rejects the same tampered-or-buggy shapes a duplicate `Conversation.id`
rejects, for the same reason: a document that would hand the thread's `LazyColumn` two rows under
one key reads empty instead of drawing them.** Beyond the version check every family shares, a
thread document is rejected (→ `invalid_data` → empty) when: a row carries other than exactly one
of `message`/`boundary`/`banner`/`compaction`/`refusal`; any two messages share an id; any two
boundaries share their full `(previousSessionId, newSessionId, occurredAt)` identity (#775 — the
pair alone is *not* rejected, since an idle-evicted session keeps its id and a session evicted
twice legitimately sends the same pair twice with different instants); any two banners, or any two
compaction rows, share an `occurredAt`; any two refusals of the same frame type (`fallbackModel !=
null` or not) share an `occurredAt` (#1353); or any tool carries `ToolCallStatus.Running` — a
restored row is defined to always be settled, so a running status on disk is itself a corrupt
document, not a row to filter.

Removing an unknown host or an unknown conversation is a **successful no-op**, matching
`PairedServerCollectionStore.remove` — #798 does not need to check existence first.

**Duplicate ids are rejected on read but not on write.** `readConversations` rejects a document
whose conversations do not have distinct ids — the same rule `KeystorePairedServerStore`'s
decoder applies to its own ids, chosen so a later `removeConversation` can never delete one of
two same-id rows and leave the other drawing. `writeConversations`, however, stores whatever
list it is handed, including one with duplicate ids: the write reports success and the *next*
read of that host comes back empty, because the bytes it just wrote fail the same check. This
was flagged in review as a should-fix (the write should reject what the read would reject, the
way `KeystorePairedServerStore.save` structurally cannot produce a duplicate) and is not yet
closed. With no production consumer yet the practical exposure is bounded to "the host reads
empty until the next successful write," but a future caller building `conversations` from
something other than a daemon's already-deduplicated list should not rely on the write to
catch it.

### `removeConversation` against an unreadable document

One rule covers all three shapes a target host's document can be in: the removal rewrites the
host's document from what is currently **readable**, minus the target conversation.

- A healthy document loses exactly its target row.
- An unreadable document (truncated, invalid JSON, wrong version, duplicate id) already reads
  as empty, so the rewrite replaces those bytes with a valid empty document — the removal still
  takes effect on content that could not otherwise be isolated, which is what a permanent
  deletion (#798) needs.
- A host that was never written is skipped entirely, so a removal never conjures a directory
  for an unknown id.

This is the one place a mutation touches bytes that a read declined to repair — reads still
never repair anything; it is the removal, asked for explicitly, that rewrites.

`removeConversation` and `removeHost` (#797) also remove the thread family: a target
conversation's thread document is deleted alongside its metadata rewrite (absent → no-op;
present-but-undeletable → `IOException` → `io`), and `removeHost`'s recursive delete of the host
directory already covers `threads/`, since the thread family is filed under it (see § Layout).
The saved history position (#1354) is removed with it for free, since it lives inside the thread
document rather than in a file of its own — there is no separate removal step to forget.
`ConversationCache.removeConversation`'s KDoc records this as the rule any family added later must
follow, so a permanently deleted conversation never leaves content behind under a family that
forgot to extend the two removal operations.

`removeConversation` extends the same way to the read-position family (#877): when the
positions document exists, it is rewritten with the target conversation's entry dropped, by the
same read-what-is-readable-then-rewrite rule. `removeHost`'s recursive directory delete already
covers `read-positions.json` for free, since it sits alongside `conversations.json` under the
host directory rather than in its own family root.
