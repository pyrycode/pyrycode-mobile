# Thread screen — the oldest-end history demand

## Authoritative older demand

`ThreadViewModel.onDemandOlderHistory()` and `onRetryOlderHistory()` call the owning
`NegotiatedThread.older(conversationId)`. `ThreadHistoryDemand`, raw newest/history
walks, saved legacy cursor/coverage seeding and gap-target callbacks were removed in
\#2367. There is no authoritative producer of legacy gap markers, so the markers and
their folding/placement paths are removed too. Content identity and order come from
[daemon-built items](remote-conversation-repository-reads-and-thread-store.md).

Opening/reconnect belongs to the negotiated query owner. It uses the certified
checkpoint and authoritative newest/catch-up route, independently of reader movement.
Reader movement or explicit Retry can first hydrate older saved authoritative facts
through the cache wrapper; otherwise the owning server query uses the certified
continuation. Neither restored text nor a short page certifies coverage.
See [local/server paging](remote-conversation-repository-reads-and-thread-store-history-paging.md).

## Reader gesture and viewport

`OlderHistoryGesture` and `Modifier.olderHistoryPull` retain the two-viewport prefetch
band. Actual movement toward older content checks current geometry after list
consumption, including an end pull with no consumed distance. An active touch can
carry provenance into its own fling. Pointer cancellation and post-fling completion
clear it. Semantics/programmatic scrolling, layout, page arrival and reconnect do
not create reader demand; nested-scroll `UserInput` alone is insufficient provenance.
The observer consumes no movement. Further reader movement can ask again after a
page settles, while the query owner prevents overlapping remote work.

`isNearOldestEnd` uses the highest visible history row and mean visible history-row
height to estimate unloaded distance. With the oldest row visible, its edge gives
the exact distance. Reverse-layout after-content padding is added back. Prompt and
tail rows are excluded, and prompt row counts still offset the oldest content index.
Empty-thread pulls use their scrollable surface so nested scrolling reaches the
observer even when no list is drawn.

## Loading, retry and proof boundaries

Loading/retry/unavailable/offline presentation rows and scrolling/follow-newest remain.
Supported screen tests use supplied facts or literal presentation inputs; they do not
invoke removed production reduction just to manufacture fixtures. Physical-fling
fixtures convert dp/s using the test density. The raw-history-only native design
capture `historyTailFramesAt412By892` is retired; shared presentation coverage remains.

`DurableGapProof` now checks authoritative saved certification and actual recovery
receipts, reset/page boundaries and exact rendered order. Both deterministic and
real-Claude offline/reconnect callers remain enabled. Independent peer raw-history
diagnostics may establish the producer's watermark or attachment boundary, but they
cannot populate or certify phone content. See [current counted coverage](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367).
