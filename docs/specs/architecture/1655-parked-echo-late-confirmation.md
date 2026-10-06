# #1655: reserve a parked echo's position before the next reply

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `OwnEchoQueue`, `finalizeAssistantTurn`, `settleQueuedEchoes`, `appendLiveMessage` and `observeSnapshot` own placement and its atomic bookkeeping.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `withAssistantDelta` skips parked user rows but otherwise extends only the last eligible segment.
- `app/src/main/java/de/pyryco/mobile/data/repository/TurnPhaseProjection.kt`: `isOpen` classifies queue admission; a phase carries no turn identity.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` routes turn ends before queue settlement through the single collector.
- `app/src/test/java/de/pyryco/mobile/data/repository/ThreadProjectionTest.kt`: ordinary drain, idle echo, Send now, foreign-id, non-user, duplicate and drop coverage.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt`: queue and Send now envelope sequences prove the production wiring.
- `docs/knowledge/features/queued-backlog.md`: the #1636 remaining race and #1642 distinction between queue removal and delivered push placement constrain this fix.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: receiver-owned segment deduplication must remain untouched; arrival order across echoes is not shared with history.
- `docs/knowledge/features/thread-screen.md` and `remote-conversation-repository.md`: existing thread treatment and connection-owned projection architecture.
- `docs/specs/architecture/1636-stream-reply-split-by-own-echo-move.md`: the parked-echo follow-up recorded under Open Questions.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: `turn_state`, `turn_end`, Queue (v2), and Security model are the wire source of truth.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/internal/msgqueue/queue.go`: `drain` confirms delivery after its write returns independently of streamed reply frames.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read node `16:8` with design context and its screenshot. The thread has left-aligned assistant bubbles, right-aligned user bubbles with attachment slots and timestamps, session rules, and a bottom composer over the dark background. Existing Material 3 body-medium/body-small typography and scheme tokens remain applicable. This projection fix changes chronological placement only; it requires no new Compose component, asset or visual geometry.

## Context

A queued own echo can outlive the turn it waited behind. Moving it to the thread's end on a confirmation between the next reply's deltas splits that reply. The first waiting turn's live end is the available boundary: `turn_state` has no turn id, and tool rows do not retain their enclosing turn id, so reconstructing a turn's complete extent at delivery would require unnecessary model changes. Reserve the existing row's position at that boundary instead. No decision record is needed.

Sizing: one behaviour, two acceptance criteria, approximately 400–500 written lines including tests and this plan, one production file, no new exported declarations or signature changes, and no new error branches. The #1636 analogue wrote 185 inserted and 26 deleted lines; the extra coverage protects #1642 interleavings. This remains below every builder limit.

Overlap: #1782 adds background-task lifecycle folds to `ThreadProjection` and a test helper arm in `RemoteConversationRepositoryTest`; those edits are independent of echo settlement. Keep edits local and build through the overlap.

## Design

- Add `OwnEchoQueue.reserved` for parked echoes whose store positions have been reserved at the first live turn end after admission. It is connection-local metadata, bounded by pending own echoes, and is consumed by the first pushed confirmation.
- In `finalizeAssistantTurn`, finalize the ending turn (including any stopped row), then move unreserved, minted user echoes in `parked` to the end in their existing thread order. Reserve those ids in the same `ProjectionState` update. A repeated end must not reserve echoes admitted behind a newer turn; use the existing ended-turn membership to distinguish a first end.
- The echo remains queued and reads below live rows until confirmation; deltas continue passing over parked ids. Its store slot is now before the next turn's first segment.
- Ordinary queue removal of a reserved echo preserves that slot even when the next turn is open. Unreserved open-turn removal and locally requested Send now retain #1642's deferred, hidden `awaitingPush` behaviour. Closed-turn removal without a reservation retains its existing fallback placement.
- An ordinary pushed confirmation of a reserved echo consumes queue and placement metadata without moving it. Explicit `sentNow`, a local Send now intent, or an unreserved deferred push still uses push placement. `placementPending` remains until the first push so a peer Send now can correct an ordinary provisional settlement exactly once.
- Preserve the minted-id and user-role checks, original row object and metadata, idle classification, delivered-id repeat protection, drop-before-settle ordering, existing observables and trail logging. No reducer, wire model, public repository API or UI change.

## State and concurrency model

No new job, dispatcher or flow. Reservations and rows share the existing `ProjectionState` CAS update, so reopening observers cannot see mixed generations. The sole inbound collector orders live ends, queue snapshots and pushes. Concurrent sends and history merges retry against the same state. The existing `endedTurns` record is written before finalization and still settles late history rows; its first-end check does not span a suspension point. Connection teardown releases the whole projection, and `remove` removes reservations with the conversation's echo metadata.

## Error handling

No new error or result type. If no live turn end was observed, retain current confirmation placement rather than guessing a boundary. Foreign ids and non-user rows cannot reserve or move. A spent drop id cannot be resurrected by reservation; a later real delivered push retains the existing append-once behaviour. Reservation does not mark delivery or emit a delivery log.

## Testing strategy

- Write two `ThreadProjectionTest` regressions first and observe each fail: turn-1 has text, tool and later text; B is queued behind it; turn-1 ends; turn-2 emits 0 and 1; the first confirmation is either drain snapshot or pushed message; delta 2 follows. Assert all turn-1 rows, B once, then one complete turn-2 segment. Observe immediately after each confirmation and verify a second confirmation preserves the original B row, attachments, timestamp and text.
- Add focused boundary guards for duplicate ends with a newer queued echo, multiple queued echoes retaining order, conversation isolation, and a failed/tool-only ending where appropriate. Extend existing foreign, non-user and dropped-echo coverage across reservation.
- Add a repository regression with actual turn-state/turn-end/queue/message envelopes, including the next turn being open at queue removal. Preserve all existing #1558, #1636 and #1642 unit tests.
- Run `ThreadProjectionTest`, `RemoteConversationRepositoryTest`, `RemoteConversationRepositoryMessageTrailTest`, `HistoryPageReducerTest` and `AssistantSegmentTest`; then lint, assembleDebug, spotlessApply and forced spotlessCheck. No shared screen or device test changes are needed because message treatment and interaction are unchanged. This controlled data-layer interleaving has no live-only acceptance criterion or new operator flow; the dispatcher owns its routine full gates.

## Open Questions

None. The missing turn identity is resolved by reserving row positions at the live turn-end boundary; Send now retains its explicit push-position override.

## Revisions

- 2026-10-05: FIFO boundary guards showed that reserving every parked echo at one end places a second queued message before the first message's reply, even though the daemon delivers one entry per turn. Reserve only the pending FIFO head at a first live end. `OwnEchoQueue.backlog` retains snapshot message-id order, including foreign ids, minus confirmed deliveries; user pushes consume the corresponding entry. An own echo behind a peer or another own echo is reserved at its later waiting turn's end. The original two interleaving regressions remain unchanged. The two new FIFO guards failed before this refinement and constrain the revised design. No signature or wire change; estimated written work remains below 500 lines.
- 2026-10-05: the history-before-live-end regression failed when first-end detection used `endedTurns`, because a history page could consume that marker without reserving the echo. `ProjectionState.liveEndedTurns` now records live-end membership atomically with rows and reservations. History still settles text through `endedTurns` but cannot consume a live reservation boundary. The live set is a subset of the existing connection-owned ended-turn ledger, is preserved by row-only folds, and is cleared by `remove` with the conversation. Duplicate live ends still cannot reserve an echo behind a newer turn. The new guard was red before this change.
- 2026-10-05 (verifier rework on PR #1790): two MUST FIX findings. First, reserving the FIFO head's store position reversed still-queued echoes in the observed thread. `observe` now orders parked rows by `OwnEchoQueue.backlog`, so a reserved store position never redefines queued display order. Second, a delivered peer head was restored by settling an unchanged snapshot, for example when another conversation's `queue_state` settled every tracked conversation. `OwnEchoQueue.consumedBacklog` remembers delivered user ids that are still in the current snapshot and is pruned to the snapshot on each settle, so it stays bounded by the decoded snapshot. Minted-id and user-role checks are unchanged. The verifier's third finding, `send-now`, passed against daemon revision 65df9885 with this repair. Three new regressions, one per trigger, were red before the repair.

- 2026-10-07: the refined contract and PR #1790's latest two MUST FIX findings supersede reservation-only placement for field-present pushes. Decode optional `MessagePayloadDto.queuedMsgId: Long?` (matching `QueuedMessage.id`) and pass it from `RemoteConversationRepository.onInbound` to `appendLiveMessage`. A modern user push consumes exactly that conversation's entry and fixes its stream position once. A pending minted user echo moves to that position even if a queue removal or legacy reservation preceded it; later pushes, snapshots and turn ends cannot move it again. Idle echoes keep tap-time placement. No-echo/Codex pushes follow received order without inferring a prior turn boundary. Omitted fields retain the bounded single-echo reservation fallback and Send now rules; the two information-deficient legacy sequences from the verdict remain outside the contract.
- 2026-10-07: `OwnEchoQueue.backlog` will retain queue entries, rather than message ids. `consumedBacklog` will contain queue-entry ids scoped by the enclosing conversation, retained for the connection so removal followed by a stale snapshot cannot resurrect delivery. Row deduplication and local ownership remain message-id based; a separate first-push row ledger prevents a new entry reusing an already delivered own id from relocating that held row. Legacy pushes consume only the first matching entry available in the snapshot. Observed queued rows use the first matching entry's FIFO index. Drops still spend minted ownership before settlement. History folds preserve these ledgers and held metadata; a fresh projection rebuilds them from fresh snapshots and replay.
- 2026-10-07: add red-before/green-after entry-identity and multi-turn probes in `ThreadProjectionTest` and production-envelope probes in `RemoteConversationRepositoryTest`, plus codec presence/omission/malformed checks and additive history decoding in existing specs. Every step of the delayed-snapshot sequence checks FIFO, segment continuity and metadata. Include peer duplicate ids, delivered-own id reuse, duplicate pushes, absent/empty/repeated/other-conversation snapshots, ownership/drop controls, Send now, history overlap and fresh-connection replay. Run the original field-absent regressions unchanged. The existing deterministic Send now method is dispatcher-owned fresh acceptance evidence; run its focused scenario during development too. No new live-Claude flow or device test is added.
- 2026-10-07: sizing against the revised design is approximately 1100–1400 total written lines including the existing PR, plan and probes, three production files, no new exported type, one production caller plus test helper defaults, four acceptance criteria and fewer than eight reject branches. `codegraph_callers` found no member caller; repository search found the inbound arm and default-compatible tests. Remote feature-branch overlap scan found no other branch touching these three production files. Figma node `16:8` context and screenshot were read again; existing message geometry applies. Merge `da2e6383` preserves main's history order and background-task metadata and passed 643 selected tests and the debug build before this revision.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/queued-backlog.md`, heading Own echo position, to close #1636's follow-up and explain modern entry identity and stream placement, queued display order, delayed snapshot settlement, duplicate message ids, metadata retention, Send now and legacy/no-echo/Codex limits. Carry placement/segmentation and history-versus-live boundary lessons into `remote-conversation-repository-reads-and-thread-store.md`, its history-paging topic and `remote-conversation-repository-assistant-reply-segments.md`; adjust `thread-screen.md` queued-order guidance where needed. These replace the reservation-only explanation. Documentation records builder and dispatcher evidence and does not produce test evidence.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The additive queue id decodes through `MessagePayloadDto`; malformed types are silently discarded at the existing boundary. Entry identity does not grant row ownership: modern placement still requires pending eligibility, minted membership and a held user row. Duplicate-entry guards and the first-push row ledger prevent repeat relocation. Existing envelope decoding remains the boundary. `finalizeAssistantTurn` selects only this conversation's parked, minted user rows, and `moveOwnEchoToEnd` retains its independent minted-id and user-role checks. Daemon-chosen ids grant no authority over foreign or tool rows; text never enters a new sink.
- [Tokens] No findings. Reservations contain only own message ids; no token, key, credential or pairing path is changed.
- [Files and storage] No findings. Metadata stays in memory for one connection and is removed with its conversation. No filename, cache key, storage format or backup rule changes.
- [Android attack surface] No findings. No component, intent, deep link, push handler, WebView or Android dependency changes.
- [Cryptography] No findings. Noise, authenticated transport and key storage are unchanged; the additive message decode does not change cryptographic framing.
- [Network and I/O] No findings. Modern consumed entry ids are connection-local per-conversation metadata, one scalar per delivered user entry, like the existing delivered-row and ended-turn ledgers; they must survive absent snapshots to reject stale reassertions. Reconnect and conversation removal release them. Queue snapshots still replace the pending entry list. No new frame size, I/O or text sink is introduced. The new reservation set is limited to already parked minted echoes and is consumed on push. Hostile snapshots cannot grow it with arbitrary foreign ids. The pending `backlog` contains only entries from the already-decoded current snapshot, is replaced on each snapshot and shrinks on user delivery; only the scalar consumption ledger accumulates across snapshots, as required to reject stale entry reassertions. A foreign head can delay a reservation but cannot authorize moving a foreign row. No network call, timeout or frame cap changes.
- [Errors, logs and telemetry] No findings. Keep the existing content-free `MessageTrail` queued/delivered/drop events; reservation is not delivery. Never log message content, attachments, decrypted payloads or credentials.
- [Concurrency] No findings. Rows, reservation metadata and the revised `liveEndedTurns` membership fold atomically. History ends cannot consume a live boundary, and repeated live ends do not reserve a newer echo. The live set is a subset of already retained ended ids and is cleared by `remove`. Deferred Send now and drop guards remain covered by tests.
- [Threat model] A malicious relay can delay/drop ciphertext but gains no plaintext path. A hostile daemon can alter the order of this device's own rows through frames, but cannot move foreign/non-user rows through reservation. Rooted-device token theft and screenshots/accessibility/keyboard leakage remain under their existing storage and UI controls, which this in-memory ordering fix does not change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
