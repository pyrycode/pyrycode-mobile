# Remote conversation repository — authoritative history paging

## Opening, catch-up and older pages

Production uses [authoritative queries and store coverage](remote-conversation-repository-reads-and-thread-store.md).
The query owner opens/reopens desired conversations against held certified checkpoints.
`ThreadViewModel` forwards reader older/retry demand to `NegotiatedThread.older`.
An opened conversation with `coverage.olderExists` can enqueue an older query. Its
exclusive `before_order` comes from certified numeric continuation. Only a correlated,
validated terminal marker can extend continuous coverage; page size, rendered count,
extra active/parent items and recovered text cannot substitute for that marker.

One remote query slot belongs to the connection. Local cancellation/timeout can
abandon assembly while replies drain; it cannot manufacture remote completion.
Failures retain prior facts and certification. Reconnect creates new query identities
and decoders over the host-owned store, preserving newer held revisions and reset
fences. Read retries remain owner-qualified and use supplied shown versions.

## Older saved content

Bounded production opening restores a newest display window. The cache wrapper can
accept older reader demand in its active observation's child scope and hydrate the
remaining saved authoritative history. It forwards failed/refused hydration to the
negotiated server route only while the observation, destination and cache lifetime
remain current. A nonzero reset generation bypasses that hydration. Opening,
reconnect and page arrival never trigger this reader-only full restore. This bridge
reads authoritative facts, not legacy cached rows. See [cache restore](caching-conversation-repository.md).

## Removed runtime history

`requestHistory`, `HistoryPageReducer`, `HistoryDisplayProjection`, `ThreadProjection`,
segment joins, inferred ordering and raw coverage/gap walks are removed. There is
no saved legacy history position in the repository contract. The dormant storage
backend/serialized types remain only until #2364. Past specs and evidence describe
their historical behavior, not a supported fallback.

The [screen gesture](thread-screen-oldest-end-history-demand.md) remains independent
of receipt/certification. `DurableGapProof` uses saved authoritative certification,
real recovery queries and rendered order. Independent test peers may still inspect
raw producer history for diagnostic watermark/attachment assertions; those replies
never enter the phone store.
