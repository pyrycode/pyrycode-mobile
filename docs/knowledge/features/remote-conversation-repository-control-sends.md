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

## `startNewSession()` — the bare v2 `new_session` control-send ([#539](../codebase/539.md))

The **outbound, fire-and-forget** wire half of the "New session" (`/clear`) affordance: a bare
`new_session` control frame the daemon routes to `supervisor.StartNewSession()` best-effort, **no
ack, no reply, no broadcast owed** (pyrycode#831, split from #534 — the #534 body's "daemon acks"
premise was wrong, corrected at split). A **line-for-line mirror of [`interrupt()`](#interrupt--the-bare-v2-interrupt-control-send-458)**, one verb over, on the
**interface** (not concrete-only like `interrupt`/`answerModal`/`cancelModal`) because `startNewSession`
is already an interface method the fake also implements.

```kotlin
override suspend fun startNewSession(conversationId: String, workspace: String?): Session {
    check(pump.send(newSessionFrame())) { "$TYPE_NEW_SESSION not sent: session not connected" }
    return Session(id = "", conversationId = conversationId, claudeSessionUuid = "",
        startedAt = Clock.System.now(), endedAt = null)
}
private fun newSessionFrame(): Envelope = Envelope(
    id = requestId.incrementAndGet(), type = TYPE_NEW_SESSION,
    ts = Clock.System.now().toString(), payload = JsonObject(emptyMap()),   // bare — no payload
)
```

- **Bare connection-level frame — `conversationId`/`workspace` args are vestigial for this impl.** The
  daemon operates on the single live claude (per-conversation scoping is a deferred *server* ticket), so
  neither arg reaches the wire; they're meaningful only to the [fake](conversation-repository.md), which
  mints per-conversation. **Replay-safe** — a replayed `new_session` with no running turn is a daemon-side
  no-op — so, like `interrupt`, the frame carries no idempotency token by design.
- **Fire-and-forget — plain `pump.send`, never `sendAndAwaitReply`** (which would hang awaiting a reply the
  daemon never sends). The `check` throws `IllegalStateException` when not `Open`, the same `interrupt`
  idiom. New companion const `TYPE_NEW_SESSION = "new_session"` beside `TYPE_INTERRUPT`.
- **Placeholder `Session` return — the interface forces a `Session`, the wire yields no identity.** Success
  (and the real session identity) is observed later via the pre-existing `session_transition` marker
  (`reason: "clear"`, the [#336 fold](../codebase/336.md)) — **out of scope for this send-only slice**. The
  returned placeholder's `id`/`claudeSessionUuid` are empty-string "not-yet-assigned" sentinels (not a
  fabricated-to-look-real UUID); `conversationId` is the arg, `startedAt` is the send moment. Never
  persisted, never enters `projection`; the [#540](../codebase/540.md) UI-wire consumer discards it.
  Considered-and-rejected alternative: narrowing the interface return type to `Unit` — ripples to the fake +
  facade + interface for an XS slice, deferred.
- **`mutationsSupported` stays `false`** — at the time of this ticket its remaining sibling
  `changeWorkspace` still threw (`archive`/`unarchive` were wired live by [#549](../codebase/549.md);
  `changeWorkspace` itself was wired by [#560](../codebase/560.md), closing out the stubs), so flipping
  the one coarse flag would un-hide these actions in `ThreadOverflowMenu`. Menu reachability is a later
  coarse-flag milestone's concern (see the #537 family — "gate cleared ≠ buildable").
- `security-sensitive`, PASS: outbound-only, constant `{}` payload (no caller-derived data), the single ISE
  message is a static string, no logging, authorization is server-side (`interactive`, mirrors `interrupt`).
