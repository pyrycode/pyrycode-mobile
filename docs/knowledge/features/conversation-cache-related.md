# Conversation cache — Related

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Related

- [Ticket #1354](https://github.com/pyrycode/pyrycode-mobile/issues/1354) and its plan,
  `docs/specs/architecture/1354-saved-history-position.md` — the saved history position inside
  the thread document, and the two-writer read-modify-write rule § The thread document's two
  writers above describes; see
  [Remote conversation repository § Resuming from the saved position](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354)
  for the `ThreadViewModel`/`ThreadHistoryDemand` side that reads and writes it
- [Ticket #1353](https://github.com/pyrycode/pyrycode-mobile/issues/1353) and its plan,
  `docs/specs/architecture/1353-cache-notices-compaction-refusals.md` —
  banners, compaction dividers and model refusals joined the thread's cacheable rows, and the row
  limit moved to 100000; its Revisions entry also covers the `mergeCachedRows` key-index fix (see
  [Caching conversation repository § How the restore merges with live rows](caching-conversation-repository.md#how-the-restore-merges-with-live-rows))
- [Ticket #795](https://github.com/pyrycode/pyrycode-mobile/issues/795) and its plan,
  `docs/specs/architecture/795-app-private-conversation-cache.md` (design, security review,
  and the `removeConversation` revision above)
- [Data model](data-model.md#whats-deliberately-absent) — why the persistence record is
  cache-local rather than annotations on `Conversation`
- [Paired server store](paired-server-store.md) — the nearest sibling in shape: graceful reads,
  reporting mutations, a versioned envelope, a causeless exception, and the backup-exclusion
  reasoning this cache's storage-scope decision extends
- [Relay log](relay-log.md) — the only logging facility this layer uses
- [Caching conversation repository](caching-conversation-repository.md) — the wrapper that reads
  and writes the thread-row family this doc's § The contract and § Layout describe (#797)
- [Dependency injection § Attention state](dependency-injection-host-conversation-source.md#attention-state-877) — the
  per-host `HostAttentionState` fold that reads and writes the read-position family (#877)
- Split from [#647](https://github.com/pyrycode/pyrycode-mobile/issues/647); downstream:
  [#796](https://github.com/pyrycode/pyrycode-mobile/issues/796) (done — host list restore, see
  [dependency injection § Restore from the on-disk cache](dependency-injection-host-conversation-source.md#restore-from-the-on-disk-cache-796)),
  [#797](../../specs/architecture/797-thread-row-cache.md) (done — thread-row family + thread
  restore), [#798](../../specs/architecture/798-clear-cache-on-removal.md) (done — `removeHost` wired
  to unpair via [`forgetRemovedHost`](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost), `removeConversation`
  wired to permanent deletion via
  [`CachingConversationRepository.delete`](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798);
  archive and unarchive call neither), [#877](../../specs/architecture/877-conversation-attention-state.md)
  (done — read-position family)
- [Clear retained attachment files on unpair (#900)](../../specs/architecture/900-clear-attachments-on-unpair.md) —
  gave `forgetRemovedHost` its third, attachment-store step; see [Attachment retrieval § Host
  store](attachment-retrieval.md#host-store--datacacheattachmentstorekt)
