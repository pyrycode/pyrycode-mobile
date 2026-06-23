# Queued backlog — the thread-observable list of messages waiting while claude is busy

A per-conversation **ordered list** the thread layer observes to learn which messages the daemon has
**queued** while claude is busy — so the phone can render the backlog instead of silently dropping the
turns the user fired during a long response. Landed in [#460](../codebase/460.md) (the data substrate,
split from #429). The **visible** render (the backlog list UI) shipped in
[#461](../codebase/461.md) → [`QueuedBacklog`](queued-backlog-section.md); the **outbound** drop send
(`dequeue_message`) shipped in [#466](../codebase/466.md) → [`dropQueuedMessage`](#dropping-a-queued-entry-dequeue_message-466),
and the per-row drop **affordance** that fires it shipped in [#467](../codebase/467.md) (the
[`QueuedBacklog`](queued-backlog-section.md) trailing close button → `ThreadViewModel.onDropQueued`).

This is the **data layer**: decode the inbound `queue_state` snapshot into observable state (#460), and
send the outbound `dequeue_message` drop (#466). It renders nothing — the
[queued backlog section](queued-backlog-section.md) (#461) shows the backlog and (#467) the
visible drop affordance.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())

// the portable element type, co-located with the contract (like ThreadItem)
data class QueuedMessage(val id: Long, val text: String, val timestamp: Instant)
```

- Emits the conversation's **current ordered backlog** (FIFO / enqueue order), `emptyList()` until the
  first `queue_state` snapshot lands, re-emitting the full new list on each snapshot. Cold flow;
  re-emits only on change (`distinctUntilChanged`), so a `queue_state` for *another* conversation never
  wakes this collector (AC #3).
- `QueuedMessage.id` is the daemon's per-conversation `queued_msg_id` — a wire **`uint64`** monotonic
  counter, decoded as a `Long` (same posture as `Envelope.eventId`; a `String` is wrong, pyrycode#720
  flags it). It is a plain ordinal, **not a secret/nonce**; [`dropQueuedMessage`](#dropping-a-queued-entry-dequeue_message-466)
  (#466) echoes it back verbatim to drop an entry. `timestamp` is enqueue time.
- **On the interface, with a `flowOf(emptyList())` default** — the same surfacing decision as
  [`observeStall`](stall-state.md). The thread ViewModel reaches the backlog through the
  [`StableConversationRepository`](stable-conversation-repository.md) facade it already holds, and the
  facade only delegates the `ConversationRepository` interface, so a concrete-only capability would be
  stranded ([[post-352-connection-scoped-repo-behind-facade]]). The default body is the cascade-escape
  valve: the [Fake](conversation-repository.md) and every inline test double inherit "never queued" and
  need no override (same lever as `observeStall` / `delete` / `requestScreenSnapshot`). Only the facade and
  the [live remote repo](remote-conversation-repository.md) override it.

## Full-snapshot replace — no onset/clearing edge

The decisive wire fact (server SSOT pyrycode#705/#720, `docs/protocol-mobile.md` § Queue (v2), ADR 025):
`queue_state = {conversation_id, queued: [{queued_msg_id, text, ts}]}` is a **full snapshot** of the
conversation's backlog — the wire form of `msgqueue.Snapshot(convID)`, in FIFO order. Every snapshot is
self-describing and authoritative, so:

| Edge | Source | Mechanism |
|---|---|---|
| **Update** | an inbound `queue_state` envelope | decode → **replace** that conversation's stored list in full (`it + (id to queue)`) |
| **Drain** | a `queue_state` with `queued: []` / `null` | the same replace, with an empty list — a legitimate "queue drained" snapshot, not a special case |

> **The deliberate counterpoint to [stall state](stall-state.md) (#395).** Both ride the same single
> `pump.inbound` collector and both are facade-reachable interface state — but stall is an **onset-only**
> `Boolean` whose recovery is *inferred* from the next forward-progress [`LiveSessionEvent`](live-session-events.md),
> whereas a queue snapshot carries the *complete current backlog* every time. So `queue_state` needs **no
> live-session-arm hook** and **no clearing inference**: the next snapshot (possibly empty) is the whole
> truth. A full-snapshot wire event is the *simpler* shape — an atomic per-key map replace, no cross-arm
> coupling.

## How it surfaces in the repository

All of the behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository.md#observequeueconversationid--the-thread-observable-queued-backlog-460)
on the **single existing** inbound collector — see that doc for the field, the demux arm, and the
projection. In short: a connection-scoped `MutableStateFlow<Map<String, List<QueuedMessage>>>` (key =
conversation id, value = the full ordered backlog), written **only** from `onInbound` (single writer → no
race), with `observeQueue` a cold `map { it[id].orEmpty() }.distinctUntilChanged()` projection.
Connection-scoped, in-memory: a fresh repo per connection (#351) starts empty, so a backlog **never
survives a reconnect** — it re-derives from the next live `queue_state`. A backlog is a transient "right
now" condition, not durable state.

## Dropping a queued entry (`dequeue_message`, #466)

The **outbound** half: `dropQueuedMessage(conversationId: String, queuedMessageId: Long)` sends a
`dequeue_message` frame `{conversation_id, queued_msg_id}` so the daemon removes a not-yet-drained message
before it reaches claude. It is the outbound **peer** of the inbound `queue_state` decode.

```kotlin
// ConversationRepository — beside requestScreenSnapshot, with a throwing default
suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long): Unit =
    error("dropQueuedMessage is not implemented for this ConversationRepository")
```

- **A pure request/reply with no observable-state effect.** Success is an empty `ack` — the method just
  returns. It mutates **no** `StateFlow`, mints no domain object, and is invisible to `observeQueue` /
  `observeMessages` / `observeLastMessage`. The backlog updates later, for free, when the daemon broadcasts
  the next `queue_state` on the `observeQueue` path above — so there is **nothing to roll back** on failure
  and **no optimistic mutation** to undo. The send surfaces the outcome; the [#467](../codebase/467.md) drop
  affordance fires it and swallows any failure **inert** (no user-visible error surface — AC #4 there).
- **`queuedMessageId` is the `QueuedMessage.id` echoed back verbatim** — a `Long` (the wire `uint64`),
  encoded by `DequeueMessagePayloadDto` to a JSON **number**, not a String (the pyrycode#720 trap). The
  daemon validates the `(conversation_id, queued_msg_id)` pair against its own per-conversation queue and
  stale-id rejects a mismatch; this slice neither re-derives nor trusts the id.
- **On the interface with a throwing default — the [`requestScreenSnapshot`](remote-conversation-repository.md)
  (#375) precedent, NOT the `answerModal`/`cancelModal` injected lambda.** Because the drop carries a
  `conversation_id` it is a **per-conversation** op the thread already reaches through the
  [`StableConversationRepository`](stable-conversation-repository.md) facade — so it needs no new ViewModel
  ctor param, no coordinator passthrough, no Koin wiring (modals, keyed by `modal_id` only, are app-level and
  fetched off the concrete coordinator instead). The fake and inline test doubles inherit the throwing
  default; only the [live remote repo](remote-conversation-repository.md) and the facade override it.
- **Reuses `sendAndAwaitReply` (#346) verbatim — no new error mapping.** Empty `ack` ⇒ success; `error`
  ⇒ caller-visible failure (`conversation.not_found` → `IllegalArgumentException`, any other code →
  `RelayErrorException(code, retryable)`, malformed → fallback `RelayErrorException`, never hangs); not
  connected ⇒ `IllegalStateException`. A stale / already-drained id (observed code `queue.stale_id`)
  surfaces **generically** as `RelayErrorException` — no bespoke queue-error mapping is pre-built, and the
  [#467](../codebase/467.md) drop affordance confirmed it: that slice swallows the error inert (no
  "already gone" visual), so no distinction was ever needed.
- **No idempotency key** (unlike `modal_answer`) — `queued_msg_id` is a monotonic per-conversation ordinal,
  never recycled, so a replayed drop targets an already-consumed id → a benign daemon stale-id reject, no
  double-effect hazard. The `modal_cancel` no-token posture.

See [#466](../codebase/466.md) for the files, the `DequeueMessagePayloadDto` encode DTO, and verification.

## Capability gate (fail-closed)

The `TYPE_QUEUE_STATE` arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[#385](../codebase/385.md) / [#395](../codebase/395.md) use, **riding the already-negotiated `interactive`
capability**; no new capability is introduced or advertised. The server already fans `queue_state` out
**only** to phones that advertised `interactive`, but the mobile gate is **defence in depth**: a
non-interactive phone that receives a spurious `queue_state` from a buggy/hostile daemon never decodes it
and never surfaces it.

> The separate ADR-025 statement that "viewing/dequeuing is *ungated* for any paired phone" is the
> **authorization** model — it means no extra per-device answer-gate like permission modals need — and is
> the **outbound [`dequeue_message`](#dropping-a-queued-entry-dequeue_message-466)** concern (#466). It
> does **not** loosen this inbound decode gate.

## Edge cases & limitations

- **`queued` tolerates `null` OR `[]` (the one wire latitude).** An empty backlog may marshal to either,
  since the producer (#722) recommends but is not forced to emit `[]`. The DTO models `queued` as
  nullable-defaulted (`List<QueuedMessageDto>? = null`) and `toQueue()` coalesces `null` / `[]` / a missing
  key to `emptyList()` — required because `MobileJson` sets no `coerceInputValues` (a wire `null` into a
  non-nullable list would throw). Every other field stays strict-required.
- **Malformed snapshot dropped, collector survives (AC #4).** A missing/wrong-typed `conversation_id`, a
  bad item (`queued_msg_id` as a string, missing `text`, unparseable `ts`) fails the strict decode of
  `QueueStatePayloadDto` (or `Instant.parse`) → that one envelope is dropped, the lone inbound collector
  lives, a later valid `queue_state` still surfaces. Element strictness is preserved: **one bad item drops
  the whole snapshot** (the `message_chunk` "one bad row drops the chunk" idiom). Same drop idiom as every
  other `onInbound` arm; **nothing logs the payload**.
- **Full-replace, not append.** A second `queue_state` for a conversation **replaces** its backlog; the
  list is never accumulated client-side. Wire array order is preserved verbatim — no sort, no dedup.
- **Not durable.** Lost on connection drop / process death; re-derived from the next live snapshot. The
  facade's `whenAbsent = emptyList()` reports an empty backlog between connections.

## Security

`security-sensitive`; architect self-review **PASS**, code-review **PASS**. One untrusted→trusted boundary
— `decodeQueueState(envelope): Pair<String, List<QueuedMessage>>?` through the single configured
`MobileJson`, behind the already-authenticated Noise channel. `QueuedMessage.text` is **user-authored
queued-message content** (potentially sensitive) and is carried **verbatim, never logged** — the same
discipline #385/#387 apply to `assistant_delta` / tool summaries; `decodeQueueState` logs nothing on the
drop path. The data layer surfaces only a `List<QueuedMessage>`, never an error or a raw `JsonElement`.
Memory posture is bounded by **replacement** (each snapshot overwrites a conversation's backlog, never
accumulates), the same daemon-supplied-id growth posture `lastMessages` / `messagesByConversation` /
`stalledConversations` already accept under the paired-daemon threat model. `queued_msg_id` is a
per-conversation counter, not a nonce (no constant-time-compare concern). UI-leakage threats
(screenshot/overlay of the rendered backlog text) were forwarded to **#461** (the visible render) and
**resolved there**: `entry.text` is the same user-content class the thread host already renders for sent
messages **without** `FLAG_SECURE`, so the [queued backlog section](queued-backlog-section.md) adds no new
screen-capture surface — see [#461](../codebase/461.md). #461 is therefore **not** `security-sensitive`.

## Related

- [#460 implementation notes](../codebase/460.md) (inbound decode) / [#466 implementation notes](../codebase/466.md)
  (outbound `dropQueuedMessage` send) — files, line refs, lessons, verification.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the `queuedByConversation`
  projection, the `TYPE_QUEUE_STATE` arm, the `observeQueue` projection, and the outbound `dropQueuedMessage`
  send.
- [Stall state](stall-state.md) (#395) — the structural twin: the decode→state→observe shape, gate, and
  test harness this reuses; the onset-only counterpoint to this full-snapshot model.
- [Live-session events](live-session-events.md) (#385) / [Modal events](modal-events.md) (#437) — the other
  capability-gated decode seams on the same single inbound collector.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted `observeQueue` joins;
  [`StableConversationRepository`](stable-conversation-repository.md) — the facade that makes it reach the
  thread ViewModel.
- Consumers: **#461** (render the backlog list — **shipped**, [`QueuedBacklog`](queued-backlog-section.md) /
  [codebase #461](../codebase/461.md)), **#466** (drop a queued entry via `dequeue_message` — **shipped**,
  [`dropQueuedMessage`](#dropping-a-queued-entry-dequeue_message-466) / [codebase #466](../codebase/466.md)),
  **#467** (the per-row drop affordance — **shipped**, the [`QueuedBacklog`](queued-backlog-section.md)
  trailing close button → `ThreadViewModel.onDropQueued` → this `dropQueuedMessage` send /
  [codebase #467](../codebase/467.md)).
- Server SSOT: pyrycode#705/#720 (`queue_state` / `dequeue_message` wire types, `queued_msg_id` `uint64`),
  #722 (producer), #723 (`dequeue_message` handler, live), `docs/protocol-mobile.md` § Queue (v2), ADR 025.
