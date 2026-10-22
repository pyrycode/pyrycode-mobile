# Remote conversation repository — control sends

## Content acceptance and retained controls

Message, attachment, prompt, configuration and task controls remain supported.
Local pending sends register before asynchronous identity lookup/send, retain exact
host/conversation/device/message identity and survive reconnect/reset until matched
by an admitted daemon user item. An ack proves receipt only. Raw message/queue events
and diagnostics cannot settle content. See [send registration](remote-conversation-repository-send-create-promote-rename.md).

Raw `requestHistory` and its reply APIs are removed. Opening and older demand use
[authoritative queries](remote-conversation-repository-reads-and-thread-store.md).
Host-held settings survive a disconnected gap only after current-producer admission;
replacement retires both holders as described in [live state](remote-conversation-repository-live-stream-and-modals.md#session-admission-and-held-settings).

## `requestScreenSnapshot(conversationId)` — the parser-independent screen-snapshot read (#375)

Requests a one-shot text picture of the current claude screen and returns its rendered `text` — the
**always-available, parser-independent floor** of ADR 025's safe-degradation strategy (pyrycode#596/#618).
[#375](../codebase/375.md) is a near-verbatim mirror of `sendMessage`'s shape, but it **decodes its reply**
(like `createDiscussion` / `promote`) rather than reconstructing from input, and — unlike every prior
mutation — it **mutates no projection**. It is the **first pure read** to ride the #346 correlation
primitive. The body now lives on `MessageCommands` (#915, `data/repository/MessageCommands.kt`), beside
`sendMessage`, `uploadAttachment` and `dropQueuedMessage`; the repository's `override suspend fun
requestScreenSnapshot` is a one-line hand-off.

The flow (the entire method, ≤ ~12 lines):

```kotlin
// MessageCommands
suspend fun requestScreenSnapshot(conversationId: String): String {
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_REQUEST_SNAPSHOT, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = conversationId)),
    )
    val reply = requests.sendAndAwaitReply(request)   // throws on server `error` / not-Open; decode below unreachable on failure
    return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text
}
```

- **The one genuinely-new bit is in the collector, not the method:** `screen_snapshot` is a *new*
  correlated reply type, so `TYPE_SCREEN_SNAPSHOT` is added to the success-arm `when` (see the [demux
  table](remote-conversation-repository-reads-and-thread-store.md) — it was previously an `else -> Unit` no-op, so
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

## `dropQueuedMessage(conversationId, queuedMessageId)` — supplied queue targets

`dequeue_message` retains the exact conversation and daemon-supplied numeric queued
message id. The presentation owner selects that target from an admitted authoritative
user item; raw queue snapshots, equal text and minted echo ledgers supply no target.
Sending never removes a content row or proves dropped/delivered settlement. A later
admitted item revision supplies the actual lifecycle. The same rule applies to
`sendQueuedNow`: it is a control request, not optimistic content acceptance.


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
  session-owned `turn_state` readings update the busy flag; canonical terminal facts
  supply content outcomes.
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

## Per-task stop support, send and refusal (#1829)

`RemoteConversationRepository` exposes `supportsBackgroundTaskStop: Boolean`,
`suspend fun stopBackgroundTask(conversationId: String, taskId: String): Result<Unit>`
and `observeBackgroundTaskStopRefusals(conversationId: String): Flow<String>`.
These concrete APIs stay outside `ConversationRepository`; the
[coordinator passthrough](relay-repository-coordinator-seams-and-passthroughs.md#outbound-background-task-stop-passthrough-1829)
selects the owning host's connection. The task-panel consumer belongs to
[#1830](https://github.com/pyrycode/pyrycode-mobile/issues/1830).

The phone advertises `stop_background_task` in `HelloClientPayload` defaults.
Support requires that detection string in the current open connection's negotiated
`hello_ack` capabilities. Detection grants no authorization: the daemon's
`interactive` gate remains authoritative. The wire authority is upstream's
[Stop background task (v2)](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#stop-background-task-v2).

`BackgroundTaskStops` registers the originating conversation/task pair before
calling the pump's raw send once. The envelope has a fresh connection request id
from `RelayRequests.nextRequestId`, a timestamp, and verbatim JSON string fields
`conversation_id` and `task_id`. It creates no reply waiter, interrupt, retry or
optimistic mutation of messages, turn state or task status. `Result.success(Unit)`
means only send acceptance: there is no success reply to await, and completion
comes from admitted authoritative task facts. Raw roster omission proves nothing.

Subscribe to the refusal flow and establish collection **before invoking the
send**. It is a replay-free shared event flow with a 64-event buffer and
`DROP_OLDEST`; an event without a subscriber is not retained. A refusal may arrive
during send, and remains an asynchronous event even if the send returns success.
Each value is the locally registered opaque task id for the requested conversation,
never daemon text. The existing single inbound collector offers errors to this
independent ledger alongside reply waiters and ask ledgers. Correlation and
[retirement rules](relay-repository-coordinator-seams-and-passthroughs.md#outbound-background-task-stop-passthrough-1829)
prevent reflected ids or old attempts from selecting a target.

Unsupported or ended repositories return a failed `Result` containing
`IllegalStateException("Background task stop unsupported")`. A rejected send or
ordinary send exception returns `IllegalStateException("Background task stop not sent")`,
without preserving exception text. Cancellation retires the attempt and rethrows
`CancellationException`. These failures establish no task outcome.

`RemoteConversationRepositoryStopTaskTest` pins the exact envelope and unchanged
state without a reply fixture, plus a refusal injected during send. Tests also
exercise conflicting reflected ids, malformed errors and retirement; merely
emitting a refusal after send would miss the registration race.

<a id="startnewsession--the-bare-v2-new_session-control-send-539"></a>

Each refusal correlation expires after **30 seconds** in the existing connection
scope, even for successful sends to distinct targets without disconnect. Replacement
for the same target, refusal, send failure, cancellation and connection end cancel
and retire the matching expiry. An old expiry/refusal cannot retire a newer attempt.
Expiry changes no task/content state and creates no retry. Subscribe before sending
so a refusal delivered during send is observable. The distinct-target expiry and
same-target replacement regressions protect against an unbounded successful ledger.


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
  changes local projections. Authoritative items supply the
  real boundary; admitted summaries/family clears establish live session identity. The required `Session` return is an
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
`request_model_list`](remote-conversation-repository-model-and-slash-command-menus.md#the-on-demand-ask--request_model_list-792)
for the trigger, the one-shot ledger, the split success/refusal reply paths and the no-retry rule. It
differs from `interrupt` / `startNewSession` above in one load-bearing way: those two have no reply to
await, while this verb's success (a `model_list` frame #791's own arm applies) and refusal (a correlated
`error`) arrive on different arms, so neither the bare-send shape nor `sendAndAwaitReply` fits — the
repository doc argues why in place.
