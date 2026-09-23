# Remote conversation repository — the Phase 4 `ConversationRepository` — screen snapshot, dequeue, interrupt and new session

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `requestScreenSnapshot(conversationId)` — the parser-independent screen-snapshot read (#375)

Requests a one-shot text picture of the current claude screen and returns its rendered `text` — the
**always-available, parser-independent floor** of ADR 025's safe-degradation strategy (pyrycode#596/#618).
[#375](../codebase/375.md) is a near-verbatim mirror of `sendMessage`'s shape, but it **decodes its reply**
(like `createDiscussion` / `promote`) rather than reconstructing from input, and — unlike every prior
mutation — it **mutates no projection**. It is the **first pure read** to ride the #346 correlation
primitive.

The flow (the entire method, ≤ ~12 lines):

```kotlin
override suspend fun requestScreenSnapshot(conversationId: String): String {
    val request = Envelope(
        id = relayRequests.nextRequestId(),
        type = TYPE_REQUEST_SNAPSHOT, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = conversationId)),
    )
    val reply = relayRequests.sendAndAwaitReply(request)   // throws on server `error` / not-Open; decode below unreachable on failure
    return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text
}
```

- **The one genuinely-new bit is in the collector, not the method:** `screen_snapshot` is a *new*
  correlated reply type, so `TYPE_SCREEN_SNAPSHOT` is added to the success-arm `when` (see the [demux
  table](remote-conversation-repository-reads-and-thread-store.md#the-repository--one-projection-cold-fan-out) — it was previously an `else -> Unit` no-op, so
  existing flows are behaviorally unchanged). Everything else is the existing pattern: same single `init`
  collector, [`RelayRequests`](remote-conversation-repository-state-errors-and-handoff.md)'s `pendingRequests`, `nextRequestId` and
  `sendAndAwaitReply` (#914). **No second pump subscription.**
- **Reuses the #374 wire DTOs verbatim:** encodes `RequestSnapshotPayloadDto` (`{conversation_id}`),
  decodes `ScreenSnapshotPayloadDto` (`{conversation_id, text, ts}`), returns `.text` only. `ts` is **never
  read**; `text` is returned **verbatim** — never parsed, trimmed, or sanitized (decode fidelity is the
  whole point of the floor). All (de)serialization is through `MobileJson`, never a default `Json`.
- **The decode runs caller-side, after `RelayRequests.sendAndAwaitReply` returns**, so a malformed `screen_snapshot`
  (missing `text`) throws `SerializationException` (⊂ `IllegalArgumentException`) in the caller's coroutine
  and **never threatens the single inbound collector** — same posture as `createDiscussion` / `promote`.
- **No local membership guard:** an unknown `conversationId` surfaces through the server's
  `conversation.not_found` `error` → `IllegalArgumentException` (the same type the fake throws
  synchronously), the server being authoritative — matching `sendMessage`'s decision.
- **`security-sensitive` → a logging discipline:** the snapshot `text` is server-originated screen content
  returned literally, so the method adds **zero** `Log.*` — the request, envelope, reply, `conversationId`,
  and `text` are all unlogged (the #346 "content may be sensitive" posture). No new error mapping;
  `RelayRequests.mapError` is reused unchanged. `TYPE_REQUEST_SNAPSHOT` / `TYPE_SCREEN_SNAPSHOT` join the full `TYPE_*`
  companion registry (every wire type is a named constant here).

## `dropQueuedMessage(conversationId, queuedMessageId)` — the `dequeue_message` outbound send (#466)

The **outbound peer** of the inbound `queue_state` decode ([`observeQueue`](remote-conversation-repository-thread-observables.md#observequeueconversationid--the-thread-observable-queued-backlog-460), #460): sends a
`dequeue_message` frame so the daemon removes a not-yet-drained message from a conversation's backlog. The
daemon **never replies** to `dequeue_message` — not on success, and not when it cannot apply the request
(an unknown, already-delivered or in-flight head id is silently ignored); `docs/protocol-mobile.md` §
Queue (v2) lists no reply. The method originally called `sendAndAwaitReply` on the assumption of an empty
ack; that assumption was wrong, so the awaited deferred never completed against production, the ack-gated
echo removal never ran, and the caller's coroutine stayed suspended for the connection's life — found live
by #849's second-client scenario, fixed by #859: the send is now **fire-and-forget**, and the echo removal
is settled from the next `queue_state`, described in full at [Queued backlog § Dropping a queued
entry](queued-backlog.md#dropping-a-queued-entry-dequeue_message-466).

```kotlin
override suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long) {
    val request = Envelope(
        id = relayRequests.nextRequestId(),
        type = TYPE_DEQUEUE_MESSAGE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMessageId),
        ),
    )
    // resolved before the send — the confirming queue_state replaces the snapshot this reads from
    val echoId = queueProjection.current(conversationId)
        .firstOrNull { it.id == queuedMessageId }?.messageId.orEmpty()
    if (echoId.isNotEmpty()) {
        pendingDrops.update { it + (conversationId to (it[conversationId].orEmpty() + (queuedMessageId to echoId))) }
    }
    if (!pump.send(request)) {
        pendingDrops.update { it + (conversationId to (it[conversationId].orEmpty() - queuedMessageId)) }
        throw IllegalStateException("$TYPE_DEQUEUE_MESSAGE not sent: session not connected")
    }
}
```

- **Fire-and-forget, not `RelayRequests.sendAndAwaitReply` (#859).** The method sends through the raw `pump.send` (the
  `interrupt` idiom) and returns once the frame is on the wire. It registers in no `RelayRequests.pendingRequests` slot,
  so it can now throw only `IllegalStateException` from a not-connected session (`pump.send` returns
  `false`) — never `RelayErrorException` or `IllegalArgumentException`.
- **`pendingDrops: MutableStateFlow<Map<String, Map<Long, String>>>`** — `conversationId -> (queued_msg_id
  -> echo message id)`, connection-scoped and in-memory like `mintedMessageIds`. `dropQueuedMessage`
  **records before it sends**: the entry goes in first, so a confirming `queue_state` can never land
  before there is a request to settle against it. (An earlier design recorded after a successful send and
  settled immediately; that ordering left a window no deterministic unit test could exercise, because the
  fake pump's inbound collector never interleaves with a call that doesn't suspend — recording first
  closes the window structurally instead.) A failed send withdraws the entry it just added and throws;
  nothing stays recorded for a drop that never reached the daemon.
- **`removeOwnEcho` (#781) now runs from the inbound `TYPE_QUEUE_STATE` arm, not from this method.** After
  `queueProjection.apply(envelope)`, the collector calls `settleDrops` for every conversation with a
  pending entry (`pendingDrops.value.keys`); settling a conversation whose snapshot did not change is a
  no-op. `settleDrops` claims, in one atomic `MutableStateFlow.update`, every pending entry whose
  `queued_msg_id` the fresh snapshot no longer holds, then runs `removeOwnEcho` for each claimed entry's
  echo id — so a conversation's drop settles at most once even if a caller and the collector race. An item
  still in the snapshot (still queued, or an unrelated `queue_state`) stays pending. **Keyed on the
  requested `queued_msg_id`, never a backlog diff**: a backlog also shrinks when the daemon *drains* it
  normally, so an item that drains without a drop request from this device has no `pendingDrops` entry and
  keeps its echo. `removeOwnEcho` itself is unchanged — the `mintedMessageIds` membership check still
  enforces #781's multi-device rule (an empty id, one minted by another device, or an unresolved
  `queuedMessageId` all leave the thread untouched). Full correlation rules, the multi-device rationale
  and the security posture live in [Queued backlog § Dropping a queued
  entry](queued-backlog.md#dropping-a-queued-entry-dequeue_message-466) — not duplicated here.
- **The drain/drop race is accepted, not defended against (#859).** The daemon pushes the same
  `queue_state` for a removal and for a drain, and it ignores a dequeue it cannot apply. If the operator
  drops the head item just as the running turn ends, the item can drain before the dequeue lands; the next
  snapshot then lacks the item exactly as a successful drop would, and the phone removes the echo of a
  message claude did receive. The daemon gives no signal that distinguishes the two cases, and the
  operator did ask for the message to go, so no defence was added — see the `dropQueuedMessage` KDoc
  (`RemoteConversationRepository` and `ConversationRepository`) for the accepted trade-off.
- **Encodes `DequeueMessagePayloadDto` (`{conversation_id, queued_msg_id}`)** through `MobileJson`;
  `queuedMsgId: Long` encodes to a JSON **number** (the wire `uint64`), symmetric with the inbound
  `QueuedMessageDto.queuedMsgId` — not a String (the pyrycode#720 trap). The caller echoes the
  `QueuedMessage.id` it got from `observeQueue` verbatim.
- **`security-sensitive` → never-log + no idempotency key.** The frame has no text field; the request,
  envelope, ids and the `pendingDrops` ledger are all unlogged (structural — the layer has no logger). No
  token/nonce: a monotonic `queued_msg_id` is never recycled, so a replayed drop hits an already-consumed
  id → a benign daemon stale-id reject (the `modal_cancel` no-token posture). Authorization is daemon-side.

## `requestHistory(conversationId, cursor, limit)` — the on-disk history page read (#623)

One backward step of a conversation's scroll-back over v2 `request_history` (server pyrycode#2113,
answered by pyrycode#2116). The [`rename`](remote-conversation-repository-send-create-promote-rename.md#renameconversationid-name--the-fourth-mutation-530)
shape — encode → `RelayRequests.sendAndAwaitReply` → typed-decode — **minus the state fold**: a page is handed back
to the caller and folded into the timeline by #645, so this method touches no projection and caches
nothing.

```kotlin
override suspend fun requestHistory(conversationId: String, cursor: String, limit: Int): HistoryPage {
    val request = Envelope(
        id = relayRequests.nextRequestId(),
        type = TYPE_REQUEST_HISTORY, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RequestHistoryPayloadDto(conversationId = conversationId, cursor = cursor, limit = limit),
        ),
    )
    val reply = relayRequests.sendAndAwaitReply(request)   // throws on server `error` / not-Open; decode below unreachable on failure
    return MobileJson.decodeFromJsonElement<HistoryPagePayloadDto>(reply).toHistoryPage()
}
```

- **`cursor` and `limit` are forwarded verbatim**, never parsed, rebuilt or validated here — the daemon
  re-validates both: a cursor that does not decode, was minted for another conversation, or names a
  position no longer in the log is one merged `history.invalid_cursor`, and a negative limit is
  `history.invalid_page_size`.
- **`history_page` is a new correlated reply type**, added to the success-arm `when` alongside
  `TYPE_RECENT_WORKSPACES_LIST` — without that registration the waiting deferred hangs forever, the
  same hazard every new reply type on this seam carries.
- **Deliberately no `.catch {}`**, unlike its one-shot sibling [`recentWorkspaces`](remote-conversation-repository-workspace-and-push.md#recentworkspaces--the-fourth-read-verb-leanest-of-the-family-no-fold-565),
  which fails closed to empty for a picker: every failure must reach the caller so a walk can tell the
  one **retryable** code (`history.unavailable`) from the three permanent ones
  (`history.invalid_cursor` / `history.invalid_page_size` / `history.invalid_request`). `RelayRequests.mapError`
  needs no new mapping — it is already generic over unrecognised codes, and it already turns the
  daemon's `conversation.not_found` into the `IllegalArgumentException` the `ConversationRepository`
  contract pins for an unknown conversation.
- **Strict decode, no defaults.** `HistoryPagePayloadDto`/`HistoryEntryDto` require every field —
  `at_start` in particular must never default to `false`, which would read as "keep walking" on a page
  that omitted the key. A missing `entries`/`cursor`/`at_start` or a malformed `ts` throws at decode,
  scoped to this caller alone; the shared inbound collector never sees it and no other conversation's
  projection changes.
- **`HistoryEntryDto.payload` stays a raw `JsonElement`** through the decode — the mapper
  (`toHistoryPage()`, `data/network/HistoryPayloads.kt`) copies it across unexamined, so a consumer
  (#645) re-reduces it through the same per-type decode arms the live lane runs rather than paying for
  a second parse. `HistoryEntryDto.id` maps straight to `HistoryEntry.id`; an entry carrying both `id`
  and an unrelated `event_id`-shaped key decodes from `id` only — the two are different sequences that
  both look like small integers (see [`HistoryEntry`](conversation-repository.md#shape)).
- **`security-sensitive` → never-log.** Like `requestScreenSnapshot`, the class adds zero `Log.*` call
  sites for this method: the cursor and every entry's `type`/`payload` are replayed content and never
  reach Logcat, on any branch including the not-connected `check` and every decode-failure path.

<a id="interrupt--the-bare-v2-interrupt-control-send-458"></a>

## `interrupt(conversationId)` — explicitly targeted v2 `interrupt`

Stop sends the open thread's id as the sole payload field, `conversation_id`
(#626). A bare frame would leave targeting to the daemon's process-wide
follow-active cursor, which another device can move. See the authoritative
[Interrupt (v2) protocol](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#interrupt-v2).

The body lives on `ConversationCommands` (#914, `data/repository/ConversationCommands.kt`); the
repository's `suspend fun interrupt` is a one-line hand-off:

```kotlin
// ConversationCommands
suspend fun interrupt(conversationId: String) {
    check(send(interruptRequest(conversationId))) { "$TYPE_INTERRUPT not sent: session not connected" }
}
private fun interruptRequest(conversationId: String): Envelope = Envelope(
    id = requests.nextRequestId(), type = TYPE_INTERRUPT,
    ts = Clock.System.now().toString(),
    payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
)
```

- **Fire-and-forget:** each invocation calls the repository's pump `send` once, passed into
  `ConversationCommands`' constructor. There is no ack or error reply to await;
  `RelayRequests.sendAndAwaitReply` would hang. A false send result throws `IllegalStateException`,
  which `ThreadViewModel.sendInterrupt` swallows without a log or UI error. Cancellation still
  propagates through the ViewModel.
- **Inbound events own turn state:** this method changes no projection on success
  or failure. Sending does not prove that the turn stopped; the existing
  conversation-routed `turn_state`/`turn_end` events update the busy flag.
- **Preserve the callback seam:** the concrete method is reached through the repository's one-line
  hand-off, then the
  [coordinator passthrough](relay-repository-coordinator-seams-and-passthroughs.md#outbound-interrupt-passthrough-458)
  and a defaulted `suspend (String) -> Unit` callback. A `conversation_id` payload
  does not require adding it to `ConversationRepository` or the facade.
- **Daemon-owned validation:** the id is JSON data, never authorization. The
  daemon validates the named target and enforces `interactive`; an unaddressable
  target is silently inert and cannot redirect Stop to another conversation.
  No identifier or payload is logged.
- **Targeting regression:** `RemoteConversationRepositoryTest` seeds messages in
  A and B, exercises A, then asserts exactly one interrupt naming B with no reply
  fixture and unchanged messages in both threads. The
  [send-path tests](interrupt-send-path.md#testing) cover the other boundaries;
  cross-device live proof remains with #679.

<a id="startnewsession--the-bare-v2-new_session-control-send-539"></a>

## `startNewSession()` — explicitly targeted v2 `new_session`

The wire half of **Reset session** sends the open conversation's id directly from
`startNewSession(conversationId, workspace)` as the sole JSON payload field,
`conversation_id` (#625). See the authoritative upstream
[New session (v2) protocol](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#new-session-v2).
An empty payload lets the daemon's process-wide follow-active cursor choose the
conversation; another device's activity can move that cursor. Naming the viewed
conversation avoids that dependency. The daemon validates the id and enforces the
`interactive` capability; the id itself is not authorization. `workspace` is not
sent by this control operation.

The body lives on `ConversationCommands` (#914, `data/repository/ConversationCommands.kt`), beside
[`interrupt`](#interruptconversationid--explicitly-targeted-v2-interrupt); the repository's
`override suspend fun startNewSession` is a one-line hand-off.

- **Fire-and-forget:** the send goes through the repository's pump `send`, never
  `RelayRequests.sendAndAwaitReply`. There is no
  success ack to await. A false send result throws `IllegalStateException`, which
  the [thread action](thread-overflow-menu.md) surfaces using fixed local copy.
  Sending successfully does not establish that rotation completed.
- **Inbound events own session state:** neither a successful nor a failed send
  changes local projections. The existing `session_transition` fold supplies the
  real boundary and session identity. The required `Session` return is an
  unpersisted placeholder: empty `id` and `claudeSessionUuid`, the argument's
  `conversationId`, and the send-time `startedAt`. The UI discards it. Changing the
  interface return to `Unit` was deferred because it would also affect the fake
  and facade without improving this send path.
- **Targeting regression coverage:** `RemoteConversationRepositoryTest` asserts
  the exact B payload both without and after activity in A, with no reply fixture,
  and checks that observed messages in both conversations remain unchanged.
  Failed-send coverage also retains seeded messages; existing session-transition
  tests cover the later boundary. A single-conversation test alone cannot expose
  dependence on prior activity. Cross-device live proof is tracked by
  [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679).

<a id="answerquestionbatch--refusequestionbatch--the-v2-question_answer--question_refused-sends-825"></a>

## `answerQuestionBatch(questionBatchId, answers)` / `refuseQuestionBatch(questionBatchId)` — the v2 `question_answer` / `question_refused` sends (#825)

The outbound half of the [`questionBatches`](remote-conversation-repository-live-stream-and-modals.md#questionbatches--the-v2-clarification-batch-decodefold-seam-822)
seam: resolves a held `QuestionBatch` (`data/model/QuestionBatch.kt`) with an operator's answer or a
refusal. Per `protocol-mobile.md` § Question (v2), the daemon acks **neither** frame — an unknown or
already-resolved batch, a malformed answer, and a device without the remote-permission grant are all
dropped silently, and the inbound `question_dismissed` is the only resolution signal. So both methods
check everything checkable **before** sending and never treat a sent frame as a resolution.

The body of both methods now lives on `QuestionBatchProjection` (#913, `data/repository/QuestionBatchProjection.kt`);
the repository's `suspend fun`s keep their signature and KDoc and delegate:

```kotlin
suspend fun answerQuestionBatch(questionBatchId: String, answers: List<QuestionAnswer>): Unit =
    questionBatchProjection.answer(questionBatchId, answers)
suspend fun refuseQuestionBatch(questionBatchId: String): Unit = questionBatchProjection.refuse(questionBatchId)

// QuestionBatchProjection.answer / .refuse (not suspend — nothing in either body suspends):
fun answer(questionBatchId: String, answers: List<QuestionAnswer>) {
    val batch = heldQuestionBatch(questionBatchId)
    require(answers.map { it.questionIndex }.sorted() == batch.questions.indices.toList()) {
        "$TYPE_QUESTION_ANSWER must answer every question exactly once"
    }
    val payload = QuestionAnswerPayloadDto(
        questionBatchId = questionBatchId,
        answerToken = questionToken("answer", questionBatchId),
        answers = answers.sortedBy { it.questionIndex }.map { QuestionAnswerEntryDto(it.questionIndex, it.values) },
    )
    sendQuestionFrame(TYPE_QUESTION_ANSWER, MobileJson.encodeToJsonElement(payload))
}
// refuse(questionBatchId) is heldQuestionBatch(questionBatchId) + one question_refused frame, no answers.
```

- **Validates against this connection's own held state, not the coordinator's projection.** A private
  `heldQuestionBatch` on `QuestionBatchProjection` reads `mutableQuestionBatches.value` directly — the same
  state `questionBatches` projects, but read before the coordinator's `stateIn` republishes it, which
  trails by a dispatch.
  Validating against that trailing copy would briefly reject a batch that had just arrived and accept
  one that had just been dismissed. Absent → `IllegalStateException` covers every batch this connection
  cannot resolve: never shown, already dismissed, or dropped by the reconnect that rebuilt this
  repository (the [question-batch projection](relay-repository-coordinator-seams-and-passthroughs.md#question-batch-projection-822)
  resets to empty on every reconnect).
- **`answers` must cover every held question exactly once.** One `require` compares the sorted index set
  with `batch.questions.indices` — catching an empty list, a short or long list, a duplicate index, and
  an out-of-range index in one check, all as `IllegalArgumentException` before any frame is built.
  `QuestionAnswer.values` are the operator's strings, sent **verbatim** — never checked against the
  offered labels, and a multi-select answer's several values keep their order. Entries go out sorted into
  batch order regardless of the order the caller supplied them.
- **Fire-and-forget, like `interrupt`:** `sendQuestionFrame` is `check(pump.send(...))`, never
  `sendAndAwaitReply` — a reply would never come. A refused send throws `IllegalStateException` naming
  only the frame type, and reaches the caller.
- **No send clears the batch.** Neither method writes `mutableQuestionBatches`, on success or on any
  failure above — the batch stays held until an inbound `question_dismissed` retires it or a reconnect
  drops it. A `question_dismissed` landing between the held-state read and `pump.send` means the frame
  goes out for a batch the daemon has already resolved; the daemon drops it, the same outcome as the
  dismiss crossing the frame on the wire, and nothing local is corrupted since nothing was written.
- **`questionToken(verb, questionBatchId) = "$verb:$questionBatchId"`** mints the `answer_token`: stable
  across a retried send, different between an answer and a refusal of the same batch, and different
  across batches — entirely from data already in hand, so no `SecureRandom` is needed (the protocol
  states the token's secrecy does not matter; only stability and uniqueness do, and the daemon's real
  dedup is its one-shot consume of the batch id). It carries no answer value and no claude-authored text.
- **`security-sensitive` → static messages, no log.** Every `require`/`check` message here is a fixed
  string naming only the frame-type constant — never the batch id, an answer value, or a claude-authored
  `question`/`header`/`label`/`description`. Neither method reads those claude-authored fields at all;
  validation only ever touches `questions.size`.
- **Not reused:** `answerModal`'s option-id answer-token path and the modal DTOs. A question answer never
  grants a permission — the send touches no modal state.
- Reached through the [coordinator passthrough](relay-repository-coordinator-seams-and-passthroughs.md#outbound-question-answer--refuse-passthrough-825)
  for [#661](https://github.com/pyrycode/pyrycode-mobile/issues/661)'s panel, the same
  concrete-repo-then-coordinator path as `answerModal`/`cancelModal`.

## The on-demand ask — `request_model_list` (#792)

Not documented in this file: the ask lives beside the retention it feeds, not beside this file's bare
fire-and-forget sends. See [Live stream, modal seams and the replay cursor § The on-demand ask —
`request_model_list`](remote-conversation-repository-live-stream-and-modals.md#the-on-demand-ask--request_model_list-792)
for the trigger, the one-shot ledger, the split success/refusal reply paths and the no-retry rule. It
differs from `interrupt` / `startNewSession` above in one load-bearing way: those two have no reply to
await, while this verb's success (a `model_list` frame #791's own arm applies) and refusal (a correlated
`error`) arrive on different arms, so neither the bare-send shape nor `sendAndAwaitReply` fits — the
repository doc argues why in place.
