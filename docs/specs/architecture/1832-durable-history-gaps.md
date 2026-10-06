# Durable history gaps (#1832)

## Files read

- `data/repository/ConversationRepository.kt`: `HistoryPosition`, `HistoryPage` and the default-tolerant history contract.
- `data/repository/HistoryPageReducer.kt`: `reduceOrderedHistoryPage`, delta identities and the shared live/cache reconciliation introduced by #1786.
- `data/repository/ThreadProjection.kt`: `mergeHistoryPage` atomically preserves held rows and durable ordering evidence.
- `data/repository/CachingConversationRepository.kt`: `observeMessages`, deletion guards and the separate row/position writers.
- `data/repository/StableConversationRepository.kt`: destination-bound history forwarding.
- `data/cache/ConversationCache.kt`: `cacheableThreadRows` excludes transient and raw-envelope rows.
- `data/cache/FileConversationCache.kt`: atomic thread documents, validated restore and trim invalidation.
- `ui/conversations/thread/ThreadViewModel.kt`: `historySeed`, `askForNewestPage` and the outstanding-request slot.
- `ui/conversations/thread/ThreadHistoryDemand.kt`: independent backwards cursor, stop and request budget.
- `ui/conversations/thread/ThreadHistoryRows.kt`, `ThreadScreen.kt`, `ThreadUiState.kt` and `MainActivity.kt`: oldest-end pull and rendering/wiring.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: page reduction and saved-position lessons.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: pointer-down arming prevents semantics scrolling from originating a request.
- `docs/knowledge/features/conversation-cache.md` and `caching-conversation-repository.md`: cache exclusions, connection-boundary merge base and deletion safeguards.
- `../pyrycode/docs/protocol-mobile.md` in the dispatcher-selected `PYRYCODE_SRC` checkout: Conversation history (v2), opaque cursor semantics and Security model.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot for node 16:8. The dark thread has chronological message bubbles, small primary-coloured session separators and a fixed composer; it has no gap marker frame. Per the operator's explicit decision, reuse the existing history-loading row's centred `bodySmall` / `onSurfaceVariant` label and spacing, with “Load earlier messages” and no count. Keep existing thread geometry and assets.

## Context

A newest side ask inserts cached-shutdown messages but discards the evidence needed to find intervening omissions. Legacy cached rows and saved `atStart` cannot prove durable completeness. This is client-only work, using #1786's reconciliation rather than another row merge. No decision record is needed.

In-flight #1283, #1655, #1827 and #1830 overlap thread files in different features; keep edits local and additive. Parent chain is #1832 → #1787 → #1681. Forecast is approximately 1450 written lines including tests and plan, four new model types, one new screen callback consumer and four acceptance criteria; measure again before handoff.

## Design

Add default-null coverage to `HistoryPosition`. A portable coverage value retains received durable spans, opaque page-edge cursors, per-gap walk cursors, unknown legacy coverage, newest cursor and durable row-identity evidence. Normalize only received ids: merge overlap/adjacency and derive holes between spans. Each known gap has its older durable anchor; unknown legacy coverage has none and closes only on `at_start`, including an empty page. Live and legacy identities supply deduplication and marker placement, never entry coverage.

The ViewModel incorporates each successful page's coverage without re-rendering its envelopes. Preserve the independent backwards walk's cursor/stop when filling a gap. A targeted pull issues at most one page, saving its next cursor even when the page was already covered. Cursorless gaps choose the nearest stored cursor above them. Refusal preserves the gap, invalidates that cursor and selects the latest usable newest-page cursor or empty cursor for the next gesture. No page or marker emission starts a request.

Expose positioned gap markers in thread state. Insert markers before their newer content; reader pulls select the first visible marker crossed toward older content, before considering oldest-end demand. Mere visibility is inert. Existing defaulted screen callbacks remain compatible.

Newest availability arrivals are queued behind the single outstanding history request rather than dropped. Every availability arrival while the ViewModel is alive contributes exactly one newest request; completion releases deferred work. Waiting for `historySeed` and repository availability remains mandatory. Clearing the ViewModel cancels requests and pending work.

## State and concurrency model

Coverage is a `StateFlow` owned by the conversation's ViewModel and seeded once from its destination-bound repository. Page state changes and claims run in `viewModelScope`; row reconciliation remains in the projection's atomic update. The request slot serializes ordinary, newest and gap asks. Availability counting does not originate retries or catch-up loops.

The caching wrapper writes the latest reconciled cacheable rows before coverage state. Persist only numeric spans, opaque cursors, identity/order metadata and content-free row proofs; no excluded envelopes. File state writes validate proofs against retained rows. Later row writes invalidate claims whose proofs disappear, including trimming. Process death between writes leaves older conservative state. Storage keeps its existing mutex, I/O dispatcher, atomic rename, host namespace and delete guards.

## Error handling

Keep existing domain history errors and local tail states. Gap failures release the slot and retain the marker; refused cursors wait for a new pull. Newest failures consume that arrival without retry. Cache failures log static codes and cannot advance durable coverage. Cancellation propagates. Cursors, entry content and row proofs never reach logs or exception prose.

## Testing strategy

Write failing focused tests before implementation. Pure coverage probes cover reversed arrival order, start/middle/end overlap, duplicates, adjacency, single missing ids, non-rendering ids, empty/one-row pages, unknown legacy coverage and `at_start`. Drive production reduction/merge for middle insertion, split assistant turns and live/legacy deduplication. Count requests through offline open, reconnect, pending older requests, cursorless rereads, refusal and closed ViewModels.

File-cache and wrapper tests restore with fresh instances, preserve opaque cursors through partial fill, scope hosts/conversations, exclude raw/transient rows, exercise failed row writes and row-before-state interruption, and invalidate trimmed claims. Shared Robolectric screen tests prove placement, first-visible targeting and visibility without requests. Run existing history-demand, reducer/reconciliation, projection, caching/file-cache and thread-history layout tests. Run focused tests, lint, assembleDebug, shared-test compilation and forced Spotless, then final whole unit/shared suite and `scripts/pre-verify.py --gradle` after merging main. Rung-3/force-stop proof belongs to #1833; no device scenario is added here.

## Open Questions

None. Runtime storage failures must preserve conservative coverage rather than attempt a new network request.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, Resuming from the saved position: entry coverage, markers, lazy fill and per-gap cursors.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, #1572 newest asks: deferred availability asks and reader targeting.
- `docs/knowledge/features/conversation-cache.md` and `caching-conversation-repository.md`: coverage/high-water persistence, conservative legacy migration and its `at_start` rule, safe row-before-state ordering.
- #1833 owns independent live and force-stop evidence.

## Security review

**Verdict:** PASS

- Trust boundaries: authenticated decoded history supplies durable ids; live/ring ids and legacy row identities cannot create spans. Cache restore validates row retention before accepting claims.
- Tokens: no credential lifecycle or storage changes; cursors remain opaque and are never credentials, paths or logged values.
- Files/storage: reuse app-private `noBackupFilesDir`, hashed host/conversation paths and atomic replacement. MUST FIX addressed in design: rows must land before state and state must be invalidated when required retained rows disappear. Existing plaintext message cache policy is unchanged; excluded envelopes remain excluded.
- Android surface: no components, intents, providers, links or WebViews are added. Markers contain only a bounded local resource string.
- Cryptography: keep the vendored Noise IK handshake, key stores and transport unchanged.
- Network/I/O: existing frame/page bounds and request limit remain; each pull costs at most one request, with no automatic retry or forward read.
- Logs: static lifecycle/error events only; no opaque cursor, envelope, row content or proof is printed.
- Concurrency: ViewModel cancellation owns all requests; preserve atomic projection merges and file mutex operations. Deletion guards apply to both writes.
- Threat model: a malicious relay can delay/drop but cannot forge authenticated entries. A hostile daemon cannot turn cursors or text into executable UI/storage paths. Rooted-device extraction and screenshot/accessibility/keyboard leakage are unchanged and owned by existing key-storage and UI policies.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-06

## Revisions

2026-10-06: A middle-page probe exposed two remaining holes inheriting one anchor. Preserve an old anchor only within its immediately older merged span; each resulting hole remains independently targetable. Assistant deltas on both sides of a hole require display-only fragments so a marker can sit between them without changing retained repository rows. Non-rendering newer spans use a standalone marker at the newest content edge. Marker targeting uses measured visible marker bounds.

2026-10-06: A partial legacy-turn probe showed a received delta can already exist inside a retained whole-turn row without sequence metadata. Bind received delta claims to ordered, non-overlapping text offsets and the retained whole-row hash. Persist hashes, offsets and lengths only; received delta text is transient and never serialized. Restored bindings remain valid only while that retained content matches. This proves retention, never legacy completeness; unknown coverage still closes only on `at_start`. Trimming also resets the independent backwards position while retaining conservative gap metadata.

2026-10-06: Inspection of backwards cursor refusal found its old whole-position clear would discard persisted gaps. Reset only backwards cursor/stop when durable coverage exists; preserve gaps/cursors for later targeted demand. Empty legacy-free positions retain the previous clear behavior.

Final sizing: approximately 1400 written lines including the plan, four exported model types, one screen callback consumer, four acceptance criteria and fewer than ten request rejection branches. All hard boundaries hold.
