# #1419: a turn_end that arrives before its rows leaves them streaming

## Files read

- `data/repository/ThreadProjection.kt`: `threadByConversation`, `mintedMessageIds` (the in-memory, connection-scoped shape the new memory copies), `applyAssistantDelta`, `finalizeAssistantTurn`, `mergeHistoryPage`, `remove`, and `observe`, which reads through `withOnlyLastRowStreaming`.
- `data/repository/HistoryPageReducer.kt`: `withFinalizedTurn` (#1350 settles every segment of a turn), `reduceHistoryPage` and `withHistoryEntry` (the `interactive` gate on `turn_end`), `decodeLiveEvent`, and `mergeHistoryRows`.
- `app/src/test/.../data/repository/AssistantSegmentTest.kt`: the `@Ignore`d `turnEndOnANewerPageThanItsRows_settlesThem`, plus the `play`/`entries`/`page` script helpers the new tests reuse.
- `data/repository/CachingConversationRepository.kt`: `mergeCachedRows` runs over the observed rows. `cacheableThreadRows` drops streaming rows, so a cached row never streams and the cache path needs no change.
- Feature overview `streaming-assistant-turns.md`: #1350's lesson that every row but the last is read settled. That is why a late row of an ended turn streams only when it is the newest row.

In-flight overlaps on `ThreadProjection.kt` / `HistoryPageReducer.kt`: #1351, #1355, #1360, #1361. Each touches other functions additively, so none is a dependency. My edits stay local.

## Context

`withFinalizedTurn` flips only rows already in the thread, and nothing records that a turn has ended. Two cases reach this:

1. In a history walk, the newest page holds only `turn_end(t1)` and the older page holds `t1`'s deltas.
2. A live `turn_end(t1)` lands before the newest history page with `t1`'s deltas is merged.

In both, the late rows enter with `isStreaming = true`. The newest one keeps streaming because no row follows it, and the cache never stores it. No decision record is needed: this is a local fix inside the projection.

## Design

`ThreadProjection` gets a new private field, `endedTurns: MutableStateFlow<Map<String, Set<String>>>`, mapping each conversation to the turn ids whose `turn_end` it has seen. It sits beside `threadByConversation` and has the same lifetime as `mintedMessageIds`.

- `finalizeAssistantTurn(event)` records `event.turnId` first, then does the existing thread flip.
- `mergeHistoryPage(conversationId, page, interactive)` records the page's ended turns first. Inside the existing `update` lambda, it then runs the merge followed by `withSettledTurns(endedTurns.value[conversationId])`.
- `applyAssistantDelta(event)` settles the folded slice only when `event.turnId` is already ended. A delta that arrives after its own `turn_end` is out of order, and per the ticket it lands settled.
- `remove(conversationId)` also drops the conversation's entry from `endedTurns`.

`HistoryPageReducer.kt` gets two changes:

- `internal fun List<ThreadItem>.withSettledTurns(turnIds: Set<String>): List<ThreadItem>` does `withFinalizedTurn`'s flip for a set of turns in one pass and returns the same list when nothing changes. `withFinalizedTurn(event)` becomes `withSettledTurns(setOf(event.turnId))`, so both lanes share one matcher (segment `turnId`, or a bare-turn-id row with no segment).
- `internal fun endedTurnIds(entries: List<HistoryEntry>, interactive: Boolean): Set<String>` returns the `turn_id` of each decodable `turn_end` entry. It returns empty when the page is not interactive, the same gate `withHistoryEntry` applies, and a malformed entry costs only itself. It reuses `decodeLiveEvent`.

The ended set grows only. A turn that has ended stays ended, so later rows can never put it back into a streaming state.

## State and concurrency model

There are two `MutableStateFlow`s, each written through atomic `update`. The ordering rule is that each writer records into `endedTurns` before it updates the thread. A merge reads `endedTurns.value` inside its thread `update` lambda, so a CAS retry re-reads it. That leaves two possibilities:

- The merge reads after a concurrent finalize recorded its turn. The merge settles the turn's rows.
- The merge reads before the record. The finalize's own thread update then runs afterwards and flips the rows the merge added.

Either way no row of an ended turn stays streaming. No new coroutine, scope or dispatcher is introduced. Memory is one id per ended turn per conversation, and it dies with the connection (#351).

## Error handling

There are no new failure modes. A malformed `turn_end` entry is skipped by `endedTurnIds`, just as the reduction skips it. Nothing logs.

## Testing strategy

All tests are unit tests in `AssistantSegmentTest`, using its script helpers:

- AC 1: remove the `@Ignore` from `turnEndOnANewerPageThanItsRows_settlesThem`.
- AC 2, new `liveTurnEndBeforeTheNewestPage_settlesItsRows`: play `End` live, then merge a page with `FULL` minus the end. Every row of `t1` should be settled.
- AC 3, new `turnWithNoTurnEnd_stillStreamsAfterAMerge_andALaterTurnEndSettlesIt`: merge a page of `FULL.dropLast(1)`. The newest row streams. Then play `End` live, and every row settles.
- `turnEndOfAnotherTurn_leavesThisTurnStreaming`: merge `turn_end(other)`, then `t1`'s deltas. The newest `t1` row still streams.
- `remove_forgetsEndedTurns`: finalize `t1`, `remove`, then merge `t1`'s deltas. The newest row streams.
- The existing `withFinalizedTurn_leavesAnotherTurnsSegmentStreaming` and the rest of `AssistantSegmentTest` cover the refactor of `withFinalizedTurn`. `ThreadProjection`'s other suites (`RemoteConversationRepository*Test`, history-merge tests) run as focused checks.

None of this is operator-facing in the sense of needing a new flow, so no rung-3 scenario is required: it is a data-layer replay-ordering fix with no new UI path.

## Open Questions

None.
