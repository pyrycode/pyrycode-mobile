# Remote conversation repository — older demand and retry

## Current route

`ThreadHistoryDemand` and its raw-history walk/retry/restart paths were removed in
\#2367. `ThreadViewModel` forwards both older demand and explicit Retry to the exact
host's `NegotiatedThread.older`. Opening/reconnect synchronization belongs to the
query owner, not a screen-owned newest-page queue.

See [authoritative local/server paging](remote-conversation-repository-reads-and-thread-store-history-paging.md)
for continuation, terminal certification and cancellation, and
[screen demand](thread-screen-oldest-end-history-demand.md) for reader provenance.
Past raw-history walk behavior remains in historical specs and the frozen archive.
