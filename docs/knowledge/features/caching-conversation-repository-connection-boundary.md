# Caching conversation repository — connection-boundary merge

Split from [Caching conversation repository](caching-conversation-repository.md).

### The merge base moves at a connection boundary, not on every emission

The naive version — fixing the merge base to the once-read `restored` snapshot for the whole
collection — shipped first and failed review (PR #837, first pass): `StableConversationRepository`
emits `emptyList()` on every disconnect (and again before a new connection's first page), so
`drawn` collapsed to the open-time snapshot on every disconnect, and the write-when-changed rule
wrote that shrunk snapshot back. Read → background → process death → offline reopen came back
blank on a first-read thread, which is the ticket's headline scenario.

The fix: the merge base is not fixed for the whole collection. It starts as `restored`. Each time
`live` is empty **and suppression is empty** — a connection boundary — the base moves to
`settledThreadRows(lastDrawn)`, the rows the screen already drew with their in-flight (streaming / running-tool) rows stripped. While
live rows are flowing inside one connection, the base stays fixed, so a row the live side
deliberately removes (`RemoteConversationRepository.removeOwnEcho` on a dropped queued send) is
still honoured and not resurrected by an accumulating union — the same reasoning that ruled out an
accumulating union for the original restored-snapshot design.

The base also retains durable ordering: restore seeds `baseOrder` from persisted coverage, and
a connection boundary combines it with the last live order. Each merge receives
`baseOrder + snapshot.unsignedHistoryOrder`. Rows and ordering therefore survive reconnect together.
Drawn rows are emitted and retained in `drawnThreads` before a cache write. The observer uses
owned changes when supported, otherwise untrimmed `drawn` replacement, under the shared
`historyWrites` mutex with a tombstone check inside the lock. Only a successful write advances
the shared persisted baseline; static failure logging exposes no rows, ids or cursors.

An empty visible snapshot with nonempty suppression is a pending-delivery reading within the
same connection, not a disconnect. Rebasing there would lose the fixed restore base and its
attachment metadata. The filtered drawn rows are emitted and cached, so reopening cannot
resurrect a hidden queued echo before its delivered push.

After the rebase, `cacheableThreadRows(drawn)` matches the previous accepted candidate, so
a disconnect creates no new settled change or quiet-period delay. Already pending rows still
persist, and an earlier failed write remains retryable. `settledThreadRows` (not
`cacheableThreadRows`) is the rebase's own function: it drops only in-flight rows and applies no row-count bound, so a thread longer than
[`MAX_CACHED_THREAD_ROWS`](conversation-cache-contract.md#the-contract) does not visibly shrink on screen
the moment its connection drops.

**Accepted residual:** the rebased base can carry a queued send's echo that the live side would
have dropped, if that echo is the *only* live row at the moment of disconnect — the accumulating
union's edge case, reintroduced at connection boundaries only. Not fixed: a queued send implies a
running turn with its own live rows, so the case has not been observed. See the plan's `##
Revisions` entry (2026-09-23) for the full argument.

**Known non-blocking follow-on (verifier finding, PR #837 second pass, accepted):** the rebased
base can also carry a previous connection's `ThreadItem.UnrecognizedMessage` rows, whose id
(`RemoteConversationRepository.unrecognizedRowId`) is a per-connection counter
(`"unrecognized-<n>"`). That id's KDoc assumes no reader ever observes rows from two connection
instances merged — an assumption the rebase breaks. After a reconnect, the new connection's first
unrecognized frame can collide on id with a rebased row from the old connection, and the merge
(`mergeCachedRows` since #983, `mergeHistoryRows` before it — both share the same `alreadyHolds` join)
drops the older one in its usual fail-safe direction. The only effect is an earlier diagnostic row
silently disappearing; this cannot produce a duplicate key or a crash. Deferred, not fixed.
