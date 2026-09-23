# #859 — A phone drop settles on the next `queue_state`, not on an ack the daemon never sends

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `dropQueuedMessage`, `removeOwnEcho`, `mintedMessageIds`, `sendAndAwaitReply`, `interrupt` (the fire-and-forget `check(pump.send(…))` idiom), the `TYPE_QUEUE_STATE` arm of the inbound router, `TYPE_DEQUEUE_MESSAGE`'s KDoc — the whole change lives here.
- `app/src/main/java/de/pyryco/mobile/data/repository/QueueProjection.kt` → `QueueProjection.apply` / `current` — the snapshot the drop resolves against and the settle step reads. Unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `dropQueuedMessage` KDoc — states the ack contract; rewritten.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onDropQueued` KDoc — describes the awaited send; rewritten. The catches stay.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → the `dropQueuedMessage_*` tests and `startDropQueuedMessage` — they push an `ack`/`error` for the dequeue the real daemon never sends; rewritten.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerQueue_staysConsistentAcrossClients` step 4 / step 7 — the #859 comment marks where the thread assertions come back.
- `../pyrycode/docs/protocol-mobile.md` § Queue (v2) → `dequeue_message` has no reply; `queue_state` is pushed on every backlog change (enqueue, drain, dequeue). Cited, not restated.

## Design source

N/A — no visual change (per the ticket's `## Figma` section). An existing bubble stops being drawn after a drop.

## Context

`dropQueuedMessage` awaits a correlated reply to `dequeue_message`; the daemon sends none, so `removeOwnEcho` only ever runs if the teardown sweep fails the deferred (it does not — it throws), and the dropped echo stays reading as a delivered message. The only confirmation the daemon gives is the next `queue_state` for that conversation lacking the item.

File-overlap check (§ A2): open PRs #820 (`feature/803`, `ThreadViewModel.kt`) and #834 (`feature/823`, `ConversationRepository.kt`, `RemoteConversationRepository.kt`) touch the same files, but their hunks are in disjoint regions (session-settings / system-prompt additions, a thinking-token flow) — none within 35 lines of the regions this ticket edits. Proceeding without a block; git merges disjoint hunks cleanly.

## Design

**Fire-and-forget send.** `dropQueuedMessage` sends the envelope with `check(pump.send(request)) { … }` (the `interrupt` idiom) and returns. It no longer registers in `pendingRequests`, so it neither awaits nor can throw `RelayErrorException` / `IllegalArgumentException`. A not-connected session still throws `IllegalStateException`, and nothing is recorded.

**New ledger: `pendingDrops: MutableStateFlow<Map<String, Map<Long, String>>>`** — `conversationId -> (queued_msg_id -> echo message id)`. Connection-scoped and in-memory like `mintedMessageIds`.

Sequence in `dropQueuedMessage`:
1. Resolve `echoId` from `queueProjection.current(conversationId)` before the send (unchanged).
2. Send; throw on not-connected.
3. If `echoId` is non-empty, record `queuedMessageId -> echoId` in `pendingDrops`, then `settleDrops(conversationId)` immediately — a `queue_state` may already have landed between the send and the record.

`private fun settleDrops(conversationId: String)` — reads the current snapshot's `queued_msg_id` set, atomically removes from `pendingDrops[conversationId]` every entry whose id is absent, then calls `removeOwnEcho(conversationId, echoId)` for each removed entry. The atomic claim means an entry settles at most once even when the inbound collector and the drop call settle concurrently.

**Inbound arm.** After `queueProjection.apply(envelope)` in the `TYPE_QUEUE_STATE` arm, settle every conversation with a pending drop (`pendingDrops.value.keys`). Settling a conversation whose snapshot did not change is a no-op, so this avoids widening `QueueProjection.apply`'s signature for the conversation id. Stays behind the same `interactive` gate.

`removeOwnEcho` is unchanged: the `mintedMessageIds` membership check still enforces #781's multi-device rule (`""` and foreign ids remove nothing).

**Why this is not the ruled-out backlog diff.** Removal is keyed on a `queued_msg_id` this device asked to drop. An item that leaves the snapshot without a drop request from this device has no `pendingDrops` entry, so a normal drain keeps its echo.

**The drain/drop race (Technical Notes).** If the dropped item is the head and the running turn ends before the dequeue lands, the item drains, the daemon silently ignores the dequeue, and the next `queue_state` lacks the item exactly as a successful drop would. The phone cannot tell the two apart and removes the echo. This is accepted and documented in the KDoc: the operator asked for that message to go, and the daemon gives no signal to distinguish the cases, so no defence is added. The same applies to an item that drained between the phone reading the snapshot and sending the dequeue.

A drop whose confirming `queue_state` never arrives before the connection closes leaves the echo (the ledger dies with the repository), the safe side.

## State + concurrency model

- `pendingDrops` has two writers: the caller's coroutine (record + settle) and the single inbound collector (settle). Every write is an atomic `MutableStateFlow.update`, the posture `mintedMessageIds` and `appendMessages` already use.
- No new jobs or scopes. `dropQueuedMessage` stays `suspend` (the interface is unchanged) but no longer suspends in practice.
- `ThreadViewModel.onDropQueued`'s coroutine now completes as soon as the frame is sent.

## Error handling

- Not connected → `IllegalStateException` from `check`, nothing recorded, echo stays; `onDropQueued` swallows it.
- A dequeue the daemon cannot apply (unknown / delivered / in-flight id) is silent on the wire; the pending entry stays until a snapshot lacks the id or the connection closes. Unbounded growth is not a concern: one entry per operator tap, connection-scoped.
- `onDropQueued` keeps its `RelayErrorException` catch because the interface contract permits other implementations; KDoc notes the remote no longer throws it.
- Nothing logs a payload, id or text (unchanged).

## Testing strategy

Unit tests in `RemoteConversationRepositoryTest` (rewritten `dropQueuedMessage_*` section; no test pushes an `ack` or `error` for the dequeue):
- wire contract and large-id encoding (kept, without the ack push); the call completes once the frame is sent, with no reply.
- not connected → `IllegalStateException`, echo stays even after a later `queue_state` without the item.
- own minted item dropped, then `queue_state` without it → echo removed, every other row keeps its order.
- own minted item dropped, before any new `queue_state` → echo still present.
- `queue_state` still containing the dropped id (e.g. a snapshot for an enqueue) → echo stays.
- a second own item that leaves the snapshot with no drop request (drain) → its echo stays while the dropped one's goes.
- `""` message id, foreign message id, `queued_msg_id` absent from the snapshot → no thread row removed after the following `queue_state`.
- a `queue_state` for another conversation settles nothing.
- the snapshot already lacking the item when the drop is recorded settles immediately.

E2E: restore `awaitGoneFromThread(DROP_PROMPT)` after the drop in step 4 and `inThreadList(DROP_PROMPT)` count 0 in step 7 of `interactiveTurn_peerQueue_staysConsistentAcrossClients`; delete the #859 comment and update the scenario KDoc bullet. Compiled locally (`compileDebugAndroidTestKotlin`); the live run is the dispatcher's `needs-real-claude` gate after verifier.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the feature overview covering the queue / thread echo (#781) should record that a drop settles on the next `queue_state`, not an ack, and the drain/drop race outcome.

## Open questions

- None blocking. `settleDrops` iterating every pending conversation per `queue_state` vs. threading the conversation id out of `QueueProjection.apply` — chose iteration to keep `QueueProjection` untouched.

## Revisions

**2026-09-23, during implementation — the request is recorded before the send, not after it.** The plan recorded the pending drop after a successful send and then settled immediately, to catch a confirming `queue_state` landing between the two. That window cannot be exercised by a deterministic unit test (the fake pump's inbound collector never interleaves with a non-suspending call), and recording first closes it structurally instead: `dropQueuedMessage` records `queuedMessageId -> echoId`, then sends, and on a not-connected send withdraws the entry and throws `IllegalStateException`. No immediate settle is needed; the inbound `queue_state` arm is the only settler. The corresponding "settles immediately" test case is dropped from the testing strategy. The e2e restore replaces step 4's queued-row wait with `awaitGoneFromThread(DROP_PROMPT)` and step 7's queued-row count with `inThreadList(DROP_PROMPT)`, because #848's list matcher counts a queued row and a bubble alike, so each check covers both.

**2026-09-23, rework after the verifier's first pass — security review added.** The ticket carried `security-sensitive` before the first build began, and the plan was committed without the adversarial pass `builder/security-review.md` requires. The pass below was run against the design as implemented (including the record-before-send revision above). It found no MUST FIX, so no code changes. The only code touched in this rework is a rewrap of the `ThreadViewModel.onDropQueued` KDoc (a verifier NIT). The other NIT, the withdraw after a failed send, is recorded under Concurrency below as accepted.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The new boundary is daemon-authored `queue_state` now triggering removal of a local thread row. The frame is decoded once by `QueueProjection.apply` into typed `QueuedMessage`s, and the removal path accepts nothing else. A snapshot can remove a row only when all three hold: (1) this device asked to drop that exact `queued_msg_id` (an entry in `pendingDrops`, written only by `dropQueuedMessage` from an operator tap), (2) the echo id recorded for it is non-empty, and (3) that id is in `mintedMessageIds`, the set of UUIDs this device minted in `sendMessage` (checked again in `removeOwnEcho`). A snapshot that omits items this device did not ask to drop, including a hostile or buggy daemon emptying the backlog, removes nothing. The worst a hostile snapshot can do is remove the one echo this device asked to drop. One residual inherited from #781 and unchanged here: the echo id is resolved from the daemon-relayed `message_id` at drop time (protocol § Queue, `message_id` is relayed verbatim and uniqueness is unenforced), so a daemon that relabels a queued item with the id of an already-delivered echo of this device could make an operator's drop remove that delivered bubble. That is bounded to this device's own display rows, needs an operator tap, deletes nothing on the daemon, and comes from the authenticated paired peer that already authors all thread content inside the Noise session. Accepted. No daemon text is rendered or stored by this change; `pendingDrops` holds only a `Long` and a UUID string per entry.
- [Tokens, secrets] No findings. No token, key or credential is created, stored, read or logged.
- [File / storage] No findings. `pendingDrops` is an in-memory `MutableStateFlow`, never persisted. It is scoped to one connection because the repository is rebuilt per connection (#351), so a drop whose confirmation never arrives dies with the connection and leaves its echo in place, the safe side.
- [Android attack surface] No findings. No intent, deep link, pending intent, push path, provider or WebView is added or changed.
- [Crypto] No findings. No primitive touched. The echo ids are the existing `UUID.randomUUID()` values from `sendMessage`, used as correlation keys and never as secrets.
- [Network & I/O] No findings. The outbound frame is unchanged, and so are the transport's frame cap, timeouts and backoff. Removing the awaited reply takes away a pending-request slot that previously sat until the teardown sweep. Nothing waits on the wire now. A hostile relay can only drop or delay the confirming `queue_state`, which leaves the echo in place.
- [Logs] No findings. No new log call. No payload, `queued_msg_id`, `message_id` or text is logged, the same as before.
- [Concurrency] No MUST FIX. `pendingDrops` has two writers: `dropQueuedMessage` records and withdraws, and the inbound collector settles through `settleDrops`. Every write is an atomic `MutableStateFlow.update`. `settleDrops` claims the absent entries inside one `update` and runs `removeOwnEcho` only on what it claimed, so an entry settles at most once. No new coroutine, scope or job is launched. Accepted NIT: a failed send's withdraw removes any entry for that `queued_msg_id`, including one from an earlier tap whose send succeeded. That needs a double tap on the same row coinciding with a disconnect, and the repository is replaced on reconnect anyway. The worst outcome is an echo that stays, the safe side.
- [Threat model — integrity, not security] The drain/drop race is an accepted integrity trade-off, documented in `dropQueuedMessage`'s KDoc. The daemon sends the same `queue_state` for a drain and for a removal, and it ignores a dequeue it cannot apply. So a dropped item that drains first, before the dequeue lands, settles as a drop, and the phone hides the echo of a message claude did receive. The operator asked for that message to go, the daemon gives no signal to tell the cases apart, and nothing but this device's own display row is affected. A malicious relay (content-blind, on-path) can delay or drop frames only, which leaves the echo shown.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
