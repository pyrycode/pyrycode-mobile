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

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The daemon frame becomes a typed value at exactly one decoder per frame type, as before. `ThreadProjection`'s `decodeUnrecognizedMessage`, `decodeBanner` and `decodeCompactionBoundary` are private and reached only through `applyUnrecognizedMessage`, `applyBanner` and `applyCompactionBoundary`, and `applyToolDenied` / `applyToolProgress` keep their own decode. Each body is still one `try` / `catch (IllegalArgumentException)` that returns null or returns early, so a malformed payload or `ts` drops that one envelope and nothing propagates to the collector. No DTO escapes the class: callers pass an `Envelope` in and the store holds only `ThreadItem`s. Decoders whose output feeds more than the thread (`decodeLiveSessionEvent`, `decodeSessionTransition`, the `message` / `message_chunk` decodes) stay in the repository, so no frame is decoded twice or in two places. The render path is unchanged; the thread still reaches Compose only as `ThreadItem` text.
- [Trust boundaries — capability gate] No findings. Every `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` gate stays in the repository's `onInbound` arms, wrapping each hand-off. `mergeHistoryPage` now takes `interactive` as a parameter, and its only caller, `requestHistory`, computes it from `negotiatedCapabilities()` after the reply decodes, the same moment the old private function read it. `negotiatedCapabilities` defaults to `{ emptySet() }`, so an unwired repository still reduces no structured history entry: the merge still fails closed. The one difference is that the flag is now read even for an empty page, which is a pure read with no effect.
- [Tokens, secrets, credentials] No findings. The move touches no token, key or pairing material. The minted-id ledger holds client-generated message ids, whose generation stays in `sendMessage` and is unchanged.
- [File / storage] No findings. All moved state is in-memory `MutableStateFlow`s and an `AtomicLong`, connection-scoped exactly as before. Nothing is persisted.
- [Inter-process / Android surface] No findings. No manifest, intent, deep link, WebView or push change. `ThreadProjection` is `internal` with no Android import.
- [Cryptographic primitives] No findings. No crypto is touched. `unrecognizedRowId` is a local row counter, not a security value; its KDoc explaining why a monotonic counter suffices moved with it.
- [Network & I/O] No findings. The frame cap, transport, timeouts and reconnect discipline live in `data/network/` and are untouched. `observeMessages` still sends `backfill_since` before reading the projection.
- [Logs] No findings. `ThreadProjection` has no `Log`, `Timber` or `println` call on any branch, and the moved KDoc keeps the no-logging rule for payload fields (`raw`, `message_type`, `conversation_id`). The repository's existing arm-level logging is unchanged.
- [Concurrency] No findings. The projection has no scope and launches nothing, so every inbound write still runs on the repository's single inbound collector. `threadProjection` is initialised in the repository's property list before `init` starts that collector. Every write is still one `MutableStateFlow.update` with the same lambda body. The #781 / #859 correlation rule is unchanged: `recordMinted`, `recordDrop` and `withdrawDrop` are the same single-line updates `sendMessage` and `dropQueuedMessage` made; `settleDrops` still claims the pending entries in one atomic update against `QueueProjection.current`, which is now passed in rather than read from the field; and `removeOwnEcho` still removes only an id in `mintedMessageIds` and spends it before removing the row. `removeOwnEcho`'s check of `mintedMessageIds.value` before its update is a pre-existing check-then-act carried over as is; a double settle removes an already-removed row, which is idempotent, so it is recorded here and not changed in a move ticket.
- [Threat model] No findings. A hostile daemon frame is still decoded defensively and dropped on failure. A hostile relay can still only drop, delay or reorder frames, and the projection's folds are unchanged in how they handle each. No new threat is introduced, since behaviour is unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- **2026-09-23 — security review added after the implementation commit.** The ticket carried the `security-sensitive` label when the builder was dispatched, but the first run committed this plan and the implementation without the § A6 pass. The verifier's review of PR #918 caught this. The pass above was run against the committed code as well as this plan, checking the points the verifier named: the moved decoders' drop idiom, no logging in `ThreadProjection`, single-collector and atomic writes, the #781 / #859 ledger, and the fail-closed `interactive` flag passed to `mergeHistoryPage`. It found nothing the code does not honour, so the design and the code are unchanged.
