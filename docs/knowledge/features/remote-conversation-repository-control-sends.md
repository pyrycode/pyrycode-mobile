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
request/reply on the **reused** `sendAndAwaitReply` (#346) primitive — the `requestScreenSnapshot`
send-template minus the reply decode (the ack is empty) ([#466](../codebase/466.md)). Since #781, a
confirmed ack also removes the sender's own undelivered thread echo — see below.

```kotlin
override suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = TYPE_DEQUEUE_MESSAGE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMessageId),
        ),
    )
    // resolved before the send (#781) — a successful drop replaces the snapshot this reads from
    val echoId = queuedByConversation.value[conversationId].orEmpty()
        .firstOrNull { it.id == queuedMessageId }?.messageId.orEmpty()
    sendAndAwaitReply(request)   // throws on server `error` / not-Open; the empty {} ack carries nothing → ignored
    removeOwnEcho(conversationId, echoId)
}
```

- **No new collector arm; the backlog projection itself is still untouched.** Success is the empty `{}`
  ack the success-arm already routes; the reply is **ignored** (no decode), and the backlog row updates
  only via the next `queue_state` on the `queuedByConversation` projection — this send writes no queue
  state and has nothing to roll back there. The new bits are the outbound DTO, the companion const
  `TYPE_DEQUEUE_MESSAGE = "dequeue_message"`, and — since #781 — the sender's own thread echo removal
  described immediately below.
- **`removeOwnEcho` (#781) — the ack also drops this device's own undelivered thread row.** `sendMessage`
  posts a local echo `Message` after its own ack, because interactive mode streams no user-message event
  back; if the daemon never runs it, that echo reads as a message claude received when it never was.
  `queuedMessageId`'s `messageId` (relayed by pyrycode#2092) is resolved from `queuedByConversation`
  **before** the send — reading after would usually race the ack's own fresh `queue_state` — and, only on
  a successful ack, checked against a connection-scoped `mintedMessageIds` ledger
  (`conversationId -> ids this device minted and echoed`, written by `sendMessage`). A match removes the
  one `ThreadItem.MessageItem` carrying that id from `threadByConversation` and consumes the ledger entry;
  an empty id, an id minted by another device, or an unresolved `queuedMessageId` all leave the thread
  untouched. The full correlation rules, the multi-device rationale (`message_id` is client-chosen and
  unique nowhere, and the thread also holds rows folded from history pages that can carry foreign ids),
  and the security posture live in [Queued backlog § Dropping a queued
  entry](queued-backlog.md#dropping-a-queued-entry-dequeue_message-466) — not duplicated here.
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

## `requestHistory(conversationId, cursor, limit)` — the on-disk history page read (#623)

One backward step of a conversation's scroll-back over v2 `request_history` (server pyrycode#2113,
answered by pyrycode#2116). The [`rename`](remote-conversation-repository-send-create-promote-rename.md#renameconversationid-name--the-fourth-mutation-530)
shape — encode → `sendAndAwaitReply` → typed-decode — **minus the state fold**: a page is handed back
to the caller and folded into the timeline by #645, so this method touches no projection and caches
nothing.

```kotlin
override suspend fun requestHistory(conversationId: String, cursor: String, limit: Int): HistoryPage {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = TYPE_REQUEST_HISTORY, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RequestHistoryPayloadDto(conversationId = conversationId, cursor = cursor, limit = limit),
        ),
    )
    val reply = sendAndAwaitReply(request)   // throws on server `error` / not-Open; decode below unreachable on failure
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
  (`history.invalid_cursor` / `history.invalid_page_size` / `history.invalid_request`). `mapError`
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

```kotlin
suspend fun interrupt(conversationId: String) {
    check(pump.send(interruptRequest(conversationId))) { "$TYPE_INTERRUPT not sent: session not connected" }
}
private fun interruptRequest(conversationId: String): Envelope = Envelope(
    id = requestId.incrementAndGet(), type = TYPE_INTERRUPT,
    ts = Clock.System.now().toString(),
    payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
)
```

- **Fire-and-forget:** each invocation calls plain `pump.send` once. There is no
  ack or error reply to await; `sendAndAwaitReply` would hang. A false send result
  throws `IllegalStateException`, which `ThreadViewModel.sendInterrupt` swallows
  without a log or UI error. Cancellation still propagates through the ViewModel.
- **Inbound events own turn state:** this method changes no projection on success
  or failure. Sending does not prove that the turn stopped; the existing
  conversation-routed `turn_state`/`turn_end` events update the busy flag.
- **Preserve the callback seam:** the concrete method is reached through the
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
