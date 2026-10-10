# Caching conversation repository — testing

Part of [caching-conversation-repository](caching-conversation-repository.md). Worker scheduling, restore, reconnect and coalesced persistence coverage.

## Testing

[`CachedThreadWorkerTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CachedThreadWorkerTest.kt)
adds seven scheduling/invariant probes (#1966). A held worker queues processing explicitly;
guarded lists assert traversal occurs in its context, avoiding elapsed-time thresholds:

- `processingInvariant_100000RowsRunOnHeldWorker_mainAndDeliveryProgress`: 100,000 cached
  rows exercise restore ordering, suppression, cache/live merge and unchanged-cache equality.
  A main sentinel progresses while work is held; downstream delivery stays on the collector,
  and a delivery gate delays filtering/writing. Unchanged rows do not write; changed rows do.
- `cachePolicyInvariant_100000LiveRowsAreFilteredOnlyAfterMainDelivery`: an empty cache
  lets merge return the live list without traversal. The 100,000 guarded live rows therefore
  independently prove cache-policy traversal runs on the worker after delivery; the writer
  still receives untrimmed drawn rows, including the excluded streaming input.
- `boundaryInvariant_suppressionAndDisconnectKeepUnsignedOrder`: repeated suppressed emptiness
  preserves the fixed base and attachment hints; disconnect rebases settled rows. Cached and
  live unsigned positions above the signed boundary, including `ULong.MAX_VALUE`, survive reconnect.
- `identityInvariant_overlapReplayAndReconnectKeepCacheOnlyNeighbours`: start/middle/end
  overlap, empty and one-row live pages, replay and reconnect retain exact ids, order and
  multiplicity with cache-only neighbours.
- `snapshotInvariant_heldGenerationKeepsRowsSuppressionOrderAndEvidenceTogether`: a newer
  generation arrives while the captured merge is held; each emitted snapshot retains its own
  suppression, order and read-evidence objects by identity, with the matching rows/write.
- `equalityInvariant_listObserverWaitsForWorkerAndSuppressesUnchangedRows`: list delivery
  waits for worker comparison and metadata-only changes cause no repeated list or cache write.
- `cancellationInvariant_heldWorkerCannotPublishOrWriteAfterExit`: cancellation while a
  merge is held leaves no downstream publication or write.

Existing immediate cache/history fixtures inject `UnconfinedTestDispatcher(testScheduler)`
as `processingDispatcher` to keep observation assertions deterministic. Disk assertions also
advance the 100 ms quiet period and run ready tasks; dispatcher injection alone does not make
persistence synchronous. Exact-cap retention probes must first prove those rows reached disk
before checking the position or replacing them with oversized rows. This is not a substitute
for the held-worker probes. Keep the [real-file full-cap and fresh-instance
checks](conversation-cache-testing.md#testing) alongside the scheduling probes.

[`CoalescedThreadWritesTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CoalescedThreadWritesTest.kt)
uses virtual time for burst boundaries, unchanged/streaming timing and bounded failure retries.
Held I/O proves snapshot progress and pending replacement. Fresh file-cache instances check
completion/cancellation flush, deletion, coverage overlap, trimming and reconnect. Same-wrapper
resubscription and multiple-collector probes protect the shared baseline and completed-candidate
guard. Failed-restore controls distinguish retained coverage from saves completed during restore,
including a save captured before collection entry.

The three `postCommit` probes hold real atomic replacement before success returns, then accept
a newer removal: cancellation, normal completion and coverage-save cancellation must all wait
and restore the latest rows. Holding only before mutation misses this boundary. The renderer-key
collision probe is enabled by [#1979](https://github.com/pyrycode/pyrycode-mobile/issues/1979):
`identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect` retains both
typed identities through a held write, empty disconnect, reconnect and fresh-file restore. See
[merge admission and counted evidence](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

`HistoryMessageIdentityTest` uses a real file cache for both arrival orders, unseeded reconnects,
original-page replay, empty emissions, persisted rows and coverage saves. Its independent writer
probe exercises file fallback without an observer; seeding every fresh projection with cached rows
would bypass the ownership failure. `HistoryAliasCorrelationTest` checks logical echo suppression
through observation and history writes, alongside replay and delivery.

[`CachingConversationRepositoryTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt) —
JVM unit tests against a fake `ConversationCache` and a `MutableStateFlow`-backed delegate. Cases:

- offline (delegate emits empty): draws restored rows verbatim and writes nothing;
- reconnect: the live newest page merges over restored ids/boundaries with no duplicate key, older
  restored rows precede;
- a streaming delta sequence writes only once the row settles; an unchanged settled set writes
  nothing;
- failed read → live rows only, no blank thread; failed write → the next change retries it;
- **a disconnect keeps the rows drawn during the connection and writes nothing** — the regression
  test for the rework above; it fails with the rebase disabled and passes with it;
- **a reconnect after a disconnect merges over everything drawn so far** — covers the connection
  that follows a rebase;
- non-thread flows are pure delegation (e.g. `observeStall` / `observeQueue` untouched by the
  cache);
- **a cold restore keeps a cache-only row in place (#983):** cache `[m1, a1, offer, a2]` under a
  live page `[m1, a1, a2]` draws, and writes back, `[m1, a1, offer, a2]` — both the drawn thread and
  the cache write-back are asserted, since the write-when-changed rule would otherwise persist the
  regression it was written to catch;
- an empty-then-page reconnect (disconnect, then a fresh page) keeps the same anchoring;
- a cache-only row with no anchor above it (an older row a newest page does not reach) still goes in
  front, matching the pre-#983 behaviour for that case;
- a sent row's names, dropped by the live side's history-replayed copy, come back through the cache
  merge's hint fill.

Six further cases (#798), added on a real `FileConversationCache` (`TemporaryFolder`) so "the rest is
readable" is proved against the real hashed-directory layout rather than a fake: a permanent delete
removes exactly that conversation's cached metadata and thread and leaves a same-id conversation under
a different host untouched; a delete the daemon refuses (a throwing `delegate.delete`) leaves the
cache intact and propagates; archiving then unarchiving through the wrapper leaves cached metadata and
thread readable (`archive`/`unarchive` are plain delegation, so this is really a regression guard on
class delegation staying intact); a thread collected through the wrapper does not write a late row
back after its conversation is deleted (the `deleted` set); and a cache whose `removeConversation`
fails still lets `delete` return, logs the one static event, and leaks no server or conversation id
into a captured log line.

Further cases on the real `FileConversationCache` (#1354): a position written through
`writeHistoryPosition` survives a concurrent row write from `observeMessages` and reads back under
this wrapper's `serverId`; a deleted conversation's position write is skipped, the same `deleted`
guard the row writer already has; and a drawn thread trimmed at `MAX_CACHED_THREAD_ROWS` and
written through `observeMessages` itself drops its saved position — the regression test for the
verifier finding above, which fails if `observeMessages` goes back to pre-trimming before the
`writeThread` call.

One further case (#899): `retrieveAttachment` goes through a fake `AttachmentStore`-shaped fetch with
this wrapper's own `serverId` and the delegate's `fetchAttachment` as the fetch function — a wiring
regression guard, not a proof of the store's own behaviour (that lives in
[`AttachmentStoreTest`](attachment-retrieval.md#testing)).

Four further cases (#1353): a `FileConversationCache`-backed restore draws a banner, a compaction
divider and a model refusal in their original positions alongside a message and a boundary, offline
— pinning that the real file-backed cache round-trips the three kinds end to end, not only the fakes
above; and one case per kind where the live page re-delivers the same cached row (same identity,
different incidental fields) and it draws once, in place, through `mergeCachedRows`'s `alreadyHolds`
join rather than twice. `HistoryPageReducerTest.mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone`
pins the same join at the `joinIdentity()`/`heldAt` level (see § How the restore merges with live
rows above) for all six kinds, including that a refusal of the other frame type stays a separate
row.

`HistoryDurabilityTest` covers coverage/row-write failures and interruption; the real-file
`HistoryCacheReworkTest` guards the three complete-operation traps from #1832:

- Disjoint older-gap insertion stays chronological through observer/fallback merges, reconnect and
  fresh restore, including persisted marker placement.
- Saved `atStart` and saved cursor remain reset after trimming through both writes; fresh restore
  drives ordinary backwards demand rather than merely inspecting a row writer's output.
- Gated deletion during fallback reads, between row/state writes, coverage-null writes and observer
  writes cannot recreate disk content; late writes are also rejected.

These probes use production merges and fresh file-cache instances. Device proof is separate: #1833's
live, scripted and external force-stop runs, recorded in the [#1833
evidence](../../e2e-interactive-stream.md#verification-status). The JVM probes do not establish
those results.

`UnsignedHistoryCoverageTest` and `UnsignedHistoryCacheTest` exercise signed-boundary/max-id
adjacency, maximum removal, overlap and split gap anchors, partial fills across fresh restores,
split-delta deduplication, positive signed documents, malformed metadata, trim resets and failed
writes. Restore probes assert no history request or read command. Legacy-alias regressions include
valid controls and reject overlapping slices, unsafe bounds, mismatched hashes, reversed durable
order and missing slice metadata on both restore and stale writes.

No Compose UI test for the original restore: restored rows draw through the same composables a
live row does, below the
existing [`ConnectionBanner`](connection-banner.md) in its offline state. Live continuity across
a real reconnect — a loaded conversation staying readable while its host link is cut and
reconciling a peer's turn once the link is restored — is proven live by
[#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`); this
wrapper's scripted coverage (`stream`, `reconnect`, `replay-order`) ran green with zero real turns,
per the dispatcher gate on PR #837's re-review.
