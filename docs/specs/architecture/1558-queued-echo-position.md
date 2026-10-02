# #1558 — A queued own echo draws below the turn it waits behind

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` — `appendLiveMessage` (keeps a held id in place, #1351), `mintedMessageIds` (#781 ledger), `settleDrops` / `removeOwnEcho` (#859), `applyAssistantDelta`, `observe` (the one read, through `withOnlyLastRowStreaming`). Every change lands here.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` — `withAssistantDelta` extends the **last** row when it is a segment of the same turn, so an echo appended mid-turn splits the running reply into two segments; `withOnlyLastRowStreaming` settles every streaming row but the last.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — the `queue_state` arm (`queueProjection.apply`, then `threadProjection.settleDrops`) and the `message` arm (`appendLiveMessage` for user rows).
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` — `sendMessage` appends the echo and records the id as minted before the ack (#1355). Unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/QueueProjection.kt` — `current(conversationId)`, the snapshot the settle reads. Unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` — `foldQueuedRows` draws a claimed echo where `items` holds it. Its logic is unchanged; only the `ThreadRow` KDoc sentence saying "at the position it was sent" changes.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_peerQueue_staysConsistentAcrossClients`, and the top-to-bottom `boundsInRoot` check in the offline-peer scenario that the new assertion mirrors.
- `docs/knowledge/features/queued-backlog.md`, `thread-screen.md` (lesson carried: the fold is render-time and never folds `queue_state` into the message reducer; this plan keeps that line and does the move in the data layer, where the ledger is).

Overlap: #1497 also edits `InteractiveStreamE2ETest.kt`; this ticket's edit there is additive (one constant, a few lines in one method).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame draws no queued-row treatment (checked by the ticket on 2026-10-03). This ticket changes only where a row sits: the queued row keeps `QueuedMessageRow`, the delivered row keeps the user bubble. No visual check of a new treatment applies.

## Context

A message sent mid-turn is appended to the thread store at tap time (#1355). `foldQueuedRows` draws its queued form in that slot (#782), and on delivery `appendLiveMessage` keeps the held row where it is (#1351). So the transcript shows it above the reply it waited behind, while the daemon's own history records it after that turn's `turn_end`. The fix must move only this device's own echoes (the ledger is in `ThreadProjection`, not visible to the render-time fold), which is why the placement lives in the projection rather than in `foldQueuedRows`. No decision record needed.

## Design

All in `ThreadProjection`, plus one call in the repository and one optional parameter on `withAssistantDelta`.

**New state.** `ownEchoQueues: MutableStateFlow<Map<String, OwnEchoQueue>>`, with private `data class OwnEchoQueue(queued: Set<String>, delivered: Set<String>)`:
- `queued` — ids that are in `mintedMessageIds` **and** in the latest `queue_state` snapshot's `message_id`s, minus `delivered`. Only ever built from the ledger intersection, so it is a subset of this device's minted ids.
- `delivered` — ids already settled to the foot by a delivery, so a later snapshot repeating the id (legal duplicate `message_id`) can never park the row again.

**Read: parked echoes draw last.** `observe(conversationId)` combines the thread and `ownEchoQueues`: rows that are user `MessageItem`s whose id is in `queued` are taken out, the remaining rows go through `withOnlyLastRowStreaming`, and the parked rows are appended after them in thread order. The store order is untouched, so every writer keeps writing as today, and a row of the running turn that streams in later still reads above the parked echo (AC 1). The streaming segment keeps its caret because the last-row rule runs over the rows without the parked echo. `foldQueuedRows` then claims the echo at the tail and draws it as a `Queued` row before any unmatched (foreign) backlog rows.

**Delivery: settle to the foot once, on whichever comes first.**
- `settleQueuedEchoes(queue: QueueProjection)` — new, called from the repository's `queue_state` arm right after `settleDrops`. For each conversation in the ledger or in `ownEchoQueues`: ids in `queued` that the snapshot no longer holds are *drained*; each is moved to the end of the store (`moveOwnEchoToEnd`), then the state is replaced by `queued = (snapshot ids ∩ minted) − delivered`, `delivered += drained`.
- `appendLiveMessage` — if the pushed id is in `queued`, move the held echo to the end of the store first and mark it delivered; then the existing held-id check runs unchanged (a held row is kept as is, a missing one is appended).
- `moveOwnEchoToEnd(conversationId, id)` — private; no-op unless the id is in `mintedMessageIds` and the first `MessageItem` carrying it is a `Role.User` row. Moves that row, unchanged (content, attachments, send time), to the end in one atomic update.

The store is moved **before** `queued` drops the id, so every intermediate read already shows the echo at the foot: it never returns to its tap-time slot (AC 2), in either arrival order.

**Running reply stays one bubble.** `withAssistantDelta(event, timestamp, passOver: Set<String> = emptySet())` — the "last row" it extends is the last row that is not a user row whose id is in `passOver`. `applyAssistantDelta` passes the conversation's `queued` set; the history reducer passes nothing, so its behaviour is unchanged. Deltas that arrive between the tap and the first snapshot listing the echo still open a second segment, as today.

**Unchanged paths (AC 3).** An idle send is never in a snapshot, so it is never parked or moved. Another device's queued item is not in the ledger, so it stays a plain unmatched row. A dropped item: `settleDrops` runs first, removes the echo and spends its minted id, so the drained id fails `moveOwnEchoToEnd`'s ledger check.

## State and concurrency model

`ownEchoQueues` is written only by the single inbound collector (the `queue_state` and `message` arms); `threadByConversation` keeps its several writers, and `moveOwnEchoToEnd` goes through one atomic `update`, like `removeOwnEcho`. `observe` stays cold (`combine` + `distinctUntilChanged`). Connection-scoped and in-memory like the ledger; a reconnect starts empty. Nothing new launches a coroutine.

## Error handling

No new failure surface: every input is already decoded. A drained id whose row is gone, or whose first row with that id is not a user row, or which is not minted, changes nothing. Nothing here logs (the class's rule).

## Testing strategy

Unit tests in `ThreadProjectionTest` (feeding `QueueProjection` real `queue_state` envelopes):
- queued own echo reads below a running turn's rows, including a delta and a tool row that arrive after it, and the running segment stays one streaming row;
- drain snapshot first, then the pushed `message`: the echo sits after the turn's rows, unchanged, and the reply's delta lands below it; every emission keeps it at the foot;
- pushed `message` first, then the drain snapshot: same;
- idle send (never in a snapshot) is not moved by the pushed `message` (the existing `appendLiveMessage_heldId_leavesRowUnchanged` keeps covering this);
- a snapshot id that is not minted, and a minted id whose held row is not a user row, never move or change a row;
- a dropped own echo (#859) is removed, not moved;
- a snapshot repeating a delivered id does not park it again.

`ThreadRowsTest` needs no new case: the fold's contract is unchanged. One repository-level test in `RemoteConversationRepositoryTest` proves the `queue_state` arm calls the settle.

Rung 3: `interactiveTurn_peerQueue_staysConsistentAcrossClients` gains, after step 6, a top-to-bottom `boundsInRoot` check: the wait turn's reply bubble (`pyrywait`, its last row), then the drained `PING_PROMPT`, then the ping reply. Device-only by nature (real daemon, real Claude); run by the dispatcher's live gate.

## Open Questions

- Does a repository-level fixture for `queue_state` plus send exist that the wiring test can reuse? Resolve while writing it.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The new move is triggered by two daemon frames (`queue_state`, `message`), both already decoded at their existing boundaries (`QueueProjection.decodeQueueState`, the repository's `message` arm). The daemon controls only *which id* it names and *when*; the move acts only on ids in `mintedMessageIds` (checked in `moveOwnEchoToEnd`, not only by the set's construction), only on a `Role.User` row, and only reorders — it never changes a row's content, so a hostile frame cannot rewrite or relocate assistant, tool or another device's rows (AC 4).
- [Trust boundaries] SHOULD FIX. The read transform in `observe` relies on `queued ⊆ minted` from construction. Keep the role check (`Role.User`) in the read's predicate so a non-user row sharing a minted id is never moved by the read either; the unit test for a colliding non-user row covers both paths.
- [Tokens] No findings: no credentials touched.
- [Files & storage] No findings: in-memory, connection-scoped state only; nothing new is written to disk or cache.
- [Android surface] No findings: no components, intents or WebViews touched.
- [Cryptography] No findings: no crypto touched.
- [Network & I/O] No findings: no new frames, sizes or sockets. A daemon flooding `queue_state` costs one O(ledger + snapshot) pass per frame, the same order as `settleDrops`.
- [Errors & logs] No findings. Nothing in `ThreadProjection` logs; the new code adds no log line, so message text and ids never reach Logcat.
- [Concurrency] No findings. `ownEchoQueues` has one writer (the inbound collector); thread moves are single atomic `update`s, ordered move-then-unpark so no intermediate read shows the echo at its tap-time slot. A concurrent `sendMessage` append retry-merges through the same `update`.
- [Threat model] Hostile daemon frame: handled by the ledger + role guard above. Malicious relay reordering frames: cannot reach inside the Noise session; within the session, either arrival order of drain and `message` is handled. Token theft / UI leakage: not affected by this change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
