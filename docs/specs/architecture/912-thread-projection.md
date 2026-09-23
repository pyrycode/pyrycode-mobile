# #912 — Move the thread state into `ThreadProjection`

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `threadByConversation`, `mintedMessageIds`, `pendingDrops`, `unrecognizedRowId`, the thread writers (`appendMessages` … `finalizeAssistantTurn`, `removeOwnEcho`, `settleDrops`, `mergeHistoryPage`), the thread decoders (`decodeUnrecognizedMessage`, `decodeBanner`, `decodeCompactionBoundary`), `threadProjection`, and every caller: the `onInbound` arms, `sendMessage`, `dropQueuedMessage`, `requestHistory`, `removeConversation`, `observeMessages`. The whole move comes from this file.
- `app/src/main/java/de/pyryco/mobile/data/repository/CompactingProjection.kt`, `QueueProjection.kt` → the projection pattern to follow: an `internal class`, one instance per repository, private state, `apply`/`observe`, decoders that log nothing. `QueueProjection.current` is what `settleDrops` reads.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withMessage`, `withToolUse`, `holdsBoundary`, `mergeHistoryRows`, `reduceHistoryPage`: the pure folds the writers call. They are not changed.

Overlap: `feature/830` edits the head of `sendMessage` in the same file. That is a separate block, not a dependency. This ticket changes only the ledger line at the tail.

## Design source

N/A: data-layer refactor with no UI.

## Context

`RemoteConversationRepository` is 3546 lines, and almost every stream-event ticket edits its thread section. PR #819 (`23b84d30`) moved the status events out into one-file projections. This ticket applies the same move to the thread store. It is a move, not a redesign: behaviour, names and KDoc stay. The one exception is KDoc that says "this class" or links a symbol that is no longer in scope. No ADR is needed.

## Design

A new `internal class ThreadProjection` in `data/repository/ThreadProjection.kt`, constructed once as `private val threadProjection = ThreadProjection()` next to the status projections.

**State (moved, private):** `threadByConversation`, `mintedMessageIds`, `pendingDrops`, `unrecognizedRowId`. Their KDoc moves with them.

**Moved writes and reads.** Public means called by the repository.

| Member | Visibility | Notes |
|---|---|---|
| `appendMessages(rows)` | public | live `message`, `message_chunk`, `sendMessage` |
| `appendSessionBoundary(conversationId, boundary)` | public | the repository keeps `decodeSessionTransition` (it also feeds the session id, settings revision and clears) |
| `applyUnrecognizedMessage(envelope)` | public | new entry point: `decodeUnrecognizedMessage` then `appendUnrecognizedMessage` |
| `applyBanner(envelope)` | public | new entry point: `decodeBanner` then `appendBanner` |
| `applyCompactionBoundary(envelope)` | public | new entry point: `decodeCompactionBoundary` then `appendCompactionBoundary` |
| `appendUnrecognizedMessage`, `appendBanner`, `appendCompactionBoundary` and their three decoders | private | moved unchanged |
| `applyToolUse`, `applyToolResult`, `applyAssistantDelta`, `finalizeAssistantTurn` | public | take the decoded `LiveSessionEvent`; the repository keeps `decodeLiveSessionEvent` (it also feeds `liveSessionEvents`) |
| `applyToolDenied(envelope)`, `applyToolProgress(envelope)` | public | already decode their own envelope; moved unchanged |
| `recordMinted(conversationId, messageId)` | public | the `mintedMessageIds.update` line from `sendMessage` |
| `recordDrop(conversationId, queuedMessageId, echoId)` / `withdrawDrop(conversationId, queuedMessageId)` | public | the two `pendingDrops.update` lines from `dropQueuedMessage` |
| `settleDrops(queue: QueueProjection)` | public | replaces the arm's `pendingDrops.value.keys.forEach(::settleDrops)`; iterates pending conversations and calls the moved private `settleDrops(conversationId, queue)` |
| `removeOwnEcho` | private | moved unchanged |
| `mergeHistoryPage(conversationId, page, interactive: Boolean)` | public | the repository computes `interactive` from `negotiatedCapabilities()` and passes it in |
| `remove(conversationId)` | public | the `threadByConversation` line of `removeConversation`; the list and last-message removals stay |
| `observe(conversationId): Flow<List<ThreadItem>>` | public | the former `threadProjection(conversationId)`, renamed to the sibling `observe` because the repository field now carries the `threadProjection` name |

**What stays in the repository:** every `interactive` gate, the decoders whose output feeds anything else (`decodeLiveSessionEvent`, `decodeSessionTransition`, the `message` / `message_chunk` inline decodes), `recordLastMessage`, `updateCurrentSessionId`, `observeMessages` (which still sends `backfill_since` and then emits `threadProjection.observe`), `sendMessage`, `dropQueuedMessage` and `requestHistory`. The `onInbound` arms for these frames become one-line hand-offs to the projection.

## State + concurrency model

This is unchanged. Every write is still one atomic `MutableStateFlow.update` on the same flow. Every inbound write still runs on the repository's single inbound collector, because the projection has no scope and no coroutine of its own. `sendMessage`, `dropQueuedMessage` and `requestHistory` still write from caller coroutines through the same atomic updates. The state is still connection-scoped: one projection per repository, and one repository per connection.

## Error handling

This is unchanged. Each decoder catches `IllegalArgumentException` and returns null, so a malformed frame drops that one envelope and the collector survives. The new class has no logging call.

## Testing strategy

The existing tests under `app/src/test/java/de/pyryco/mobile/data/repository/` cover every moved path through the repository's public surface. They include `RemoteConversationRepository*Test` for the live folds, tool denied and tool progress, unrecognized messages, banners, compaction boundaries, the history merge and the queue drop. The move keeps every assertion unchanged, and a green run of those classes is the proof. No `ThreadProjectionTest` is added, because the class exposes no behaviour that the repository tests do not already reach. Run the repository test package with `testDebugUnitTest`, then `lint` and `assembleDebug`.

## Documentation handoff

This is pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` should name `ThreadProjection` as the home of the thread store, the minted-id ledger and the pending drops.
- `docs/knowledge/features/remote-conversation-repository.md`, § "Status projections: one file per status event", should list `ThreadProjection` beside the status projections, or link to where the thread store now lives.

## Open questions

- None. The `settleDrops` signature and the `observe` rename are decided above.
