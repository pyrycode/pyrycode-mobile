# #1636: the scripted `stream` reply splits when the own echo's delivery lands mid-reply

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`, `interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread`: the timed-out `waitUntil` for "streamed world", which spans the 2nd→3rd delta.
- `scripts/e2e-fixtures/stream.jsonl`: three `assistant` lines, three message ids, one turn. The daemon's `flushDelta` (pyrycode `cmd/pyry/interactive_turn_v2.go`) flushes per message id, so they arrive as `seq` 0, 1, 2 of one `turn_id`.
- `ThreadProjection`: `settleQueuedEchoes`, `appendLiveMessage`, `moveOwnEchoToEnd`, `applyAssistantDelta`, `observe` / `withQueuedEchoesLast`, `OwnEchoQueue` (#1558).
- `HistoryPageReducer.kt`, `withAssistantDelta`: a delta extends the last row only when it is a segment of the same turn; any other last row opens a new segment. `passOver` skips queued echoes.
- `TurnPhaseProjection`: the per-conversation turn phase (#1313), the only phone-side record of whether a turn is open.
- `RemoteConversationRepository`, `TYPE_QUEUE_STATE` arm: where `settleQueuedEchoes` is called.
- pyrycode `internal/msgqueue/queue.go`, `drain`: `deliver` "can block for a whole claude turn"; the delivered `message` push and the drain's `queue_state` fire only after it returns, on the queue's goroutine, while the child's reply streams from the turn emitter's.
- `ThreadFold` (`ThreadViewModel`): renders the projection's segments once any exists, so the projection's rows are what the test sees.

## Context

Root cause. The phone draws its own message at tap time. When the daemon reports it in a `queue_state` snapshot, #1558 parks it below every row and, when the delivery confirmation arrives (the drain snapshot or the pushed `message`), moves it to the end of the thread. That is right for a message that waited behind a running turn. In the `stream` scenario nothing is running: the daemon enqueues the message, delivers it at once, and fakeclaude starts replying. Because the daemon confirms delivery only after `deliver` returns, on a different goroutine from the reply, the confirmation can reach the phone after the reply's first deltas. If it lands between delta 2 and delta 3, the echo moves below "Hello, streamed ", and delta 3 sees a user row last and opens a second segment "world". The thread ends `["Hello, streamed ", "hello", "world"]`, the turn still ends and the cache is written, and no node contains "streamed world". This matches the failing run's logcat (acknowledged, delivered 1.5 s later, turn alert, `write_thread status=ok`). A landing between delta 1 and 2 leaves "streamed world" intact, which is why the failure is rare.

The reinstall question is moot for this cause: the race needs nothing from `ping`.

No decision record needed; this narrows #1558's rule rather than changing its design.

## Design

An echo waits behind a turn only if a turn was open when the daemon first reported it queued. Only those echoes are parked (read last, passed over by deltas) and moved to the end on delivery. An echo first reported while the conversation was idle stays where it was drawn, above its own reply.

- `OwnEchoQueue` gains `behindTurn: Set<String>`, the subset of `queued` first seen while a turn was open, and a derived `parked = queued ∩ behindTurn`.
- `settleQueuedEchoes(queue, turnOpen: (conversationId) -> Boolean)`: an echo newly in `queued` joins `behindTurn` when `turnOpen` holds at that moment; the decision is sticky until it leaves `queued`. A drained echo moves to the end only when it was in `behindTurn`. Trail logging (`queued`, `delivered`) is unchanged and still follows `queued`.
- `appendLiveMessage`: the pushed copy of a queued echo moves it only when it was in `behindTurn`; the queue bookkeeping is unchanged.
- `applyAssistantDelta` passes `parked` as `passOver`; `observe` parks `parked`.
- `TurnPhaseProjection.isOpen(conversationId)`: the held phase is not idle.
- `RemoteConversationRepository`'s `queue_state` arm passes `turnPhaseProjection::isOpen`.

## State and concurrency model

No new coroutine or flow. `behindTurn` lives in the existing `ownEchoQueues` `MutableStateFlow`, written only by the single inbound collector, as today. `TurnPhaseProjection` is written by the same collector, so the phase read in the `queue_state` arm reflects every frame before that snapshot.

## Error handling

No new failure mode. A `turn_state` that arrives after the snapshot leaves an echo unparked; the cost is the pre-#1558 position for that message, never a lost or duplicated row.

## Testing strategy

- `ThreadProjectionTest`: new test, for both delivery confirmations (drain snapshot, pushed `message`): an echo queued while idle, then deltas 0 and 1, then the confirmation, then delta 2 → thread is `[echo, turn]` and the one segment reads "Hello, streamed world". Run against the unfixed projection first, it fails with the split rows.
- `ThreadProjectionTest`: the existing #1558 tests keep passing with `turnOpen = true` (the helper's default), which is their scenario.
- `RemoteConversationRepositoryTest`: the two #1558 repository tests push a `turn_state` before the snapshot, which is the scenario they describe, so they now prove the wiring from `TurnPhaseProjection`.
- `python3 scripts/android-test-gate.py scripted stream` for AC 3. The `stream` assertion is unchanged.

## Open Questions

- Does a scripted run reproduce the split often enough to count before and after? Emulator time is contended; if not, the unit reproduction is the evidence.

## Revisions

- 2026-10-03, resolving the open question: no scripted run executed during the build. Every `python3 scripts/android-test-gate.py scripted stream` attempt gave up after 300 s with "device busy, not a test result" (exit 75), because verifier and live-gate runs held the device. The unit reproduction in `ThreadProjectionTest` is the before/after evidence. It is red on the unfixed projection with `[turn-1, mine, turn-1#2]` and green after the fix. The design is unchanged.
