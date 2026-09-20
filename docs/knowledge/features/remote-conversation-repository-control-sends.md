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
        id = requestId.incrementAndGet(),
        type = TYPE_REQUEST_SNAPSHOT, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = conversationId)),
    )
    val reply = sendAndAwaitReply(request)   // throws on server `error` / not-Open; decode below unreachable on failure
    return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text
}
```

- **The one genuinely-new bit is in the collector, not the method:** `screen_snapshot` is a *new*
  correlated reply type, so `TYPE_SCREEN_SNAPSHOT` is added to the success-arm `when` (see the [demux
  table](remote-conversation-repository-reads-and-thread-store.md#the-repository--one-projection-cold-fan-out) — it was previously an `else -> Unit` no-op, so
  existing flows are behaviorally unchanged). Everything else is the existing pattern: same single `init`
  collector, `pendingRequests`, `requestId`, `sendAndAwaitReply`. **No second pump subscription.**
- **Reuses the #374 wire DTOs verbatim:** encodes `RequestSnapshotPayloadDto` (`{conversation_id}`),
  decodes `ScreenSnapshotPayloadDto` (`{conversation_id, text, ts}`), returns `.text` only. `ts` is **never
  read**; `text` is returned **verbatim** — never parsed, trimmed, or sanitized (decode fidelity is the
  whole point of the floor). All (de)serialization is through `MobileJson`, never a default `Json`.
- **The decode runs caller-side, after `sendAndAwaitReply` returns**, so a malformed `screen_snapshot`
  (missing `text`) throws `SerializationException` (⊂ `IllegalArgumentException`) in the caller's coroutine
  and **never threatens the single inbound collector** — same posture as `createDiscussion` / `promote`.
- **No local membership guard:** an unknown `conversationId` surfaces through the server's
  `conversation.not_found` `error` → `IllegalArgumentException` (the same type the fake throws
  synchronously), the server being authoritative — matching `sendMessage`'s decision.
- **`security-sensitive` → a logging discipline:** the snapshot `text` is server-originated screen content
  returned literally, so the method adds **zero** `Log.*` — the request, envelope, reply, `conversationId`,
  and `text` are all unlogged (the #346 "content may be sensitive" posture). No new error mapping;
  `mapError` is reused unchanged. `TYPE_REQUEST_SNAPSHOT` / `TYPE_SCREEN_SNAPSHOT` join the full `TYPE_*`
  companion registry (every wire type is a named constant here).

## `dropQueuedMessage(conversationId, queuedMessageId)` — the `dequeue_message` outbound send (#466)

The **outbound peer** of the inbound `queue_state` decode ([`observeQueue`](remote-conversation-repository-thread-observables.md#observequeueconversationid--the-thread-observable-queued-backlog-460), #460): sends a
`dequeue_message` frame so the daemon removes a not-yet-drained message from a conversation's backlog. A
pure request/reply on the **reused** `sendAndAwaitReply` (#346) primitive — the `requestScreenSnapshot`
send-template minus the reply decode (the ack is empty), and **unlike** it, mutates **no** projection
([#466](../codebase/466.md)).

```kotlin
override suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = TYPE_DEQUEUE_MESSAGE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMessageId),
        ),
    )
    sendAndAwaitReply(request)   // throws on server `error` / not-Open; the empty {} ack carries nothing → ignored
}
```

- **No new collector arm, no new projection.** Success is the empty `{}` ack the success-arm already
  routes; the reply is **ignored** (no decode), and the backlog updates only via the next `queue_state` on
  the `queuedByConversation` projection — this send writes no state and has nothing to roll back. The one
  new bit is the outbound DTO + the companion const `TYPE_DEQUEUE_MESSAGE = "dequeue_message"`.
- **Encodes `DequeueMessagePayloadDto` (`{conversation_id, queued_msg_id}`)** through `MobileJson`;
  `queuedMsgId: Long` encodes to a JSON **number** (the wire `uint64`), symmetric with the inbound
  `QueuedMessageDto.queuedMsgId` — not a String (the pyrycode#720 trap). The caller echoes the
  `QueuedMessage.id` it got from `observeQueue` verbatim.
- **No `try`/`catch`, no new error mapping** — `sendAndAwaitReply` + `mapError` are reused verbatim:
  `conversation.not_found` → `IllegalArgumentException`, any other code (a stale / already-drained id, e.g.
  `queue.stale_id`) → `RelayErrorException(code, retryable)`, malformed → fallback `RelayErrorException`
  (never hangs), not-`Open` send → `IllegalStateException`. The no-catch path also preserves structured
  cancellation (the `catch(IllegalStateException)`-swallows-`CancellationException` trap does not apply).
- **`security-sensitive` → never-log + no idempotency key.** The frame has no text field; the request,
  envelope, ids, and reply are all unlogged (structural — the layer has no logger). No token/nonce: a
  monotonic `queued_msg_id` is never recycled, so a replayed drop hits an already-consumed id → a benign
  daemon stale-id reject (the `modal_cancel` no-token posture). Authorization is daemon-side.

## `interrupt()` — the bare v2 `interrupt` control-send (#458)

The **outbound, fire-and-forget** half of the remote-Esc feature: a bare `interrupt` control frame the daemon
maps to `turnevent.Cancel` → one Esc keystroke to the supervised claude (pyrycode#707). The
[`cancelModal`](remote-conversation-repository-live-stream-and-modals.md#answermodal--cancelmodal--the-v2-modal-answercancel-control-send-438) template with **one
behavioural departure** — interrupt gets **no reply**, so it uses plain `pump.send`, **not**
`sendAndAwaitReply` (awaiting a reply that never comes would hang). [#458](../codebase/458.md).

```kotlin
suspend fun interrupt() {
    check(pump.send(interruptRequest())) { "$TYPE_INTERRUPT not sent: session not connected" }
}
private fun interruptRequest(): Envelope = Envelope(
    id = requestId.incrementAndGet(), type = TYPE_INTERRUPT,
    ts = Clock.System.now().toString(), payload = JsonObject(emptyMap()),   // bare — no payload
)
```

- **Bare connection-level frame — no `conversationId` argument.** The method takes none; the payload is the
  empty object `{}` (the `listConversationsRequest()` precedent), with no `conversation_id` / no idempotency
  key. Claude serialises turns ⇒ at most one running turn ⇒ a bare frame is unambiguous. **Replay-safe** (a
  replayed Esc with no running turn is a daemon no-op), so the absence of a token is by design, not omission.
- **Fire-and-forget — plain `pump.send`, no awaited reply.** The `check` throws `IllegalStateException` when
  the pump is not `Open` (`send` returns `false`), reusing `sendAndAwaitReply`'s line-612 not-connected idiom
  so the caller (`ThreadViewModel.sendInterrupt`) can swallow it. New companion const `TYPE_INTERRUPT =
  "interrupt"` near `TYPE_MODAL_CANCEL`.
- **Concrete-only, injected as a defaulted suspend lambda** off the
  [coordinator passthrough](relay-repository-coordinator.md#outbound-interrupt-passthrough-458), exactly like
  `answerModal`/`cancelModal` — **not** on the interface. The discriminator is the payload: a frame with no
  `conversation_id` (modal-keyed, or here connection-level) is fetched off the concrete coordinator; a
  `conversation_id`-carrying frame (`requestScreenSnapshot` / `dropQueuedMessage`) goes on the interface +
  facade. See [Interrupt send path](interrupt-send-path.md).
- **The `interactive` gate is server-authoritative** — the phone always sends (minimal client); a
  non-interactive connection's interrupt is dropped daemon-side. **Permission-gate-exempt.** `security-sensitive`,
  PASS: outbound-only, no untrusted parse, the empty payload has no injection surface, never logs.

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

- **Fire-and-forget:** use plain `pump.send`, never `sendAndAwaitReply`. There is no
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
