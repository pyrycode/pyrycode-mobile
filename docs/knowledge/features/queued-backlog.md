# Queued backlog — the thread-observable list of messages waiting while claude is busy

A per-conversation **ordered list** the thread layer observes to learn which messages the daemon has
**queued** while claude is busy — so the phone can render the backlog instead of silently dropping the
turns the user fired during a long response. Landed in [#460](../codebase/460.md) (the data substrate,
split from #429). The **visible** render (the backlog list UI) shipped in
[#461](../codebase/461.md) → [`QueuedBacklog`](queued-backlog-section.md); the **outbound** drop send
(`dequeue_message`) shipped in [#466](../codebase/466.md) → [`dropQueuedMessage`](#dropping-a-queued-entry-dequeue_message-466),
the per-row drop **affordance** that fires it shipped in [#467](../codebase/467.md) (the
[`QueuedBacklog`](queued-backlog-section.md) trailing close button → `ThreadViewModel.onDropQueued`), and
carrying the item's `message_id` to drop the sender's own undelivered thread echo alongside the backlog
entry shipped in #781 (below).

This is the **data layer**: decode the inbound `queue_state` snapshot into observable state (#460), and
send the outbound `dequeue_message` drop (#466). It renders nothing — the
[queued backlog section](queued-backlog-section.md) (#461) shows the backlog and (#467) the
visible drop affordance.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())

// the portable element type, co-located with the contract (like ThreadItem)
data class QueuedMessage(val id: Long, val text: String, val timestamp: Instant, val messageId: String = "")
```

- Emits the conversation's **current ordered backlog** (FIFO / enqueue order), `emptyList()` until the
  first `queue_state` snapshot lands, re-emitting the full new list on each snapshot. Cold flow;
  re-emits only on change (`distinctUntilChanged`), so a `queue_state` for *another* conversation never
  wakes this collector (AC #3).
- `QueuedMessage.id` is the daemon's per-conversation `queued_msg_id` — a wire **`uint64`** monotonic
  counter, decoded as a `Long` (same posture as `Envelope.eventId`; a `String` is wrong, pyrycode#720
  flags it). It is a plain ordinal, **not a secret/nonce**; [`dropQueuedMessage`](#dropping-a-queued-entry-dequeue_message-466)
  (#466) echoes it back verbatim to drop an entry. `timestamp` is enqueue time.
- **`messageId` (#781) is the `send_message` client `message_id` the daemon relays verbatim on every
  item (pyrycode#2092).** Strict-required on the wire DTO — `""` is a legal *value* (the client sent
  none, or this is a daemon predating #2092's relay — see Edge cases), not an absent field — but
  defaulted to `""` on the domain type so the constructor stays positional-compatible with every
  existing `QueuedMessage(...)` preview literal. It **addresses nothing**: `dequeue_message` still
  resolves `conversation_id` + `queued_msg_id`. It is **compared for equality only** — never rendered,
  never used as a list key (it is client-chosen and unique **nowhere**; two items may legally carry the
  same value, and keying a `LazyColumn` on it would crash on the duplicate — the same hazard
  `ThreadItem.UnrecognizedMessage.id`'s KDoc warns about for a different field), never logged. See
  [Dropping a queued entry](#dropping-a-queued-entry-dequeue_message-466) for what it is spent on.
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

All of the behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#observequeueconversationid--the-thread-observable-queued-backlog-460)
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

- **The backlog row still leaves only on the next `queue_state`** (#467's non-optimistic ruling, which the
  daemon owns) — the method mutates no `queuedByConversation` entry itself.
  **The sender's own thread echo is different (#781).** `sendMessage` posts that row locally as it issues
  the `send_message` request ([#1355](https://github.com/pyrycode/pyrycode-mobile/issues/1355) moved this
  ahead of the ack), because interactive mode streams no user-message event back — the daemon never
  authored it, so a confirmed drop also removes it, or the thread keeps a row that reads as a message
  claude received when it never was. The removal targets exactly one `ThreadItem.MessageItem`, correlated
  on `QueuedMessage.messageId`, and is invisible to `observeQueue` / `observeLastMessage`.
- **There is no `ack` to be "successful" on ([#859](https://github.com/pyrycode/pyrycode-mobile/issues/859),
  found live by #849's second-client scenario).** `handleDequeueMessage` (pyrycode
  `internal/relay/v2session_modal.go`) sends **no reply and no broadcast** for `dequeue_message` —
  `../pyrycode/docs/protocol-mobile.md` § Queue (v2) names none either; convergence is the next
  `queue_state` alone, including for a request the daemon cannot apply (an unknown, already-delivered or
  in-flight head id is silently ignored). The method originally called `sendAndAwaitReply` on the
  assumption of an empty ack; that assumption was wrong, so the awaited deferred never completed against
  production — it was failed only by the connection-teardown sweep — `removeOwnEcho` never ran, and the
  caller's coroutine stayed suspended for the connection's life. Unit tests never caught this because the
  fake transport answered the dequeue with an `ack` the real daemon does not send. **#859 fixes this by not
  awaiting a reply at all:** `dropQueuedMessage` sends through the raw `pump.send` (the `interrupt` idiom)
  and returns once the frame is on the wire, throwing only `IllegalStateException` for a not-connected
  session. The confirmation moves to the inbound side: a new connection-scoped ledger,
  `RemoteConversationRepository.pendingDrops` (`conversationId -> (queued_msg_id -> echo message id)`),
  records the requested drop **before** the send (so a confirming snapshot can never land before there is
  a request to settle against it; a failed send withdraws the entry). The `TYPE_QUEUE_STATE` arm calls
  `settleDrops` after every `queueProjection.apply`, which atomically claims every pending entry whose
  `queued_msg_id` the fresh snapshot no longer holds and runs `removeOwnEcho` for each — so a drop settles
  at most once. Keyed on the requested id, never on the backlog shrinking, so an item that drains normally
  (no drop request from this device) keeps its echo. **The one ambiguity is accepted, not defended
  against**: if the head item drains just before the dequeue lands, the daemon ignores the now-moot
  dequeue and the next snapshot looks exactly like a successful drop, so the phone removes the echo of a
  message claude did receive — the daemon gives no signal that tells the two cases apart, and the operator
  did ask for that message to go.
- **The correlation is resolved and spent entirely inside `RemoteConversationRepository` — no UI,
  ViewModel or facade signature change.** `dropQueuedMessage(conversationId, queuedMessageId)` still takes
  only the `queued_msg_id` the caller already has; the repository looks up that item's `messageId` in its
  **own** `queuedByConversation` snapshot before sending, because a successful drop provokes a fresh
  `queue_state` that would remove the item first if read afterwards (safe to read early since
  `queued_msg_id` is a per-conversation counter that is never recycled). Once the confirming `queue_state`
  settles the drop, the resolved id is checked against a connection-scoped **minted-id ledger** —
  `conversationId -> the message ids this device minted and echoed`, written by `sendMessage` as it issues
  the send ([#1355](https://github.com/pyrycode/pyrycode-mobile/issues/1355); a failed send's id is left in
  the ledger too, harmlessly, since no queued item will ever carry it) — and the matching thread row is
  removed only if the id is non-empty and present in that ledger,
  which also consumes it (so a second queued item legally sharing the same `message_id` removes nothing on
  its own drop). **This is § Queue (v2)'s multi-device rule made mechanical**: the thread projection alone
  is not a valid correlation store, because it also holds rows folded from history pages (#623/#778) that
  can carry another device's ids, and `message_id` is client-chosen with uniqueness enforced nowhere —
  matching against the projection directly would let a colliding id delete a row this phone never sent. An
  item carrying `""`, one minted by another device, or a `queuedMessageId` no longer in the snapshot all
  correlate with nothing: the send still goes, no thread row is touched, and **text is never compared**.
  A throw from the send skips the removal entirely (the withdrawn `pendingDrops` entry has nothing to
  settle), so a failed drop leaves both the entry and the echo in place — there is still nothing to roll
  back. The send surfaces the outcome; the [#467](../codebase/467.md) drop affordance fires it and
  swallows any failure **inert** (no user-visible error surface — AC #4 there).
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
- **Fire-and-forget through the raw `pump.send`, not `sendAndAwaitReply` (#859).** The daemon replies to
  neither a successful dequeue nor one it cannot apply, so the only throw left is `IllegalStateException`
  for a not-connected session (`pump.send` returns `false`); no `RelayErrorException` or
  `IllegalArgumentException` reaches the caller from this send any more. A stale / already-drained id
  (the wire's `queue.stale_id`) is one of the requests the daemon silently ignores — it was never
  distinguished from success even under the old ack-based mapping, and the [#467](../codebase/467.md) drop
  affordance already swallows any failure inert (no "already gone" visual), so nothing downstream needed
  the distinction.
- **No idempotency key** (unlike `modal_answer`) — `queued_msg_id` is a monotonic per-conversation ordinal,
  never recycled, so a replayed drop targets an already-consumed id → a benign daemon stale-id reject, no
  double-effect hazard. The `modal_cancel` no-token posture.

See [#466](../codebase/466.md) for the files, the `DequeueMessagePayloadDto` encode DTO, and verification.

## Own echo position: a queued message draws below the turn it waits behind (#1558)

Sending while a turn is running drew the echo straight into the thread at tap time ([#1355](https://github.com/pyrycode/pyrycode-mobile/issues/1355)),
[`foldQueuedRows`](queued-backlog-section.md) then drew its queued treatment **in that same slot** ([#782](#dropping-a-queued-entry-dequeue_message-466)),
and on delivery [`appendLiveMessage`](remote-conversation-repository-reads-and-thread-store.md#observemessagesconversationid--the-live-thread-read-313)
kept the held row **where it was** ([#781](#dropping-a-queued-entry-dequeue_message-466)). So the transcript showed the message above the reply it
waited behind, while the daemon's own stored history — which parks a queued message at *delivery*, after the
turn's `turn_end` — had the order the ticket asks for. #1558 fixes the client's view only; it does not
re-order a thread already cached wrong on a device, and it adds no new Figma treatment — the queued row keeps
`QueuedMessageRow`, the delivered row keeps the user bubble.

**The move lives in `ThreadProjection`, not in the fold.** Only `ThreadProjection` can see the minted-id
ledger ([#781](#dropping-a-queued-entry-dequeue_message-466)); `foldQueuedRows` cannot, so a move inside the
render-time join would let a `queue_state` naming a foreign id relocate another device's row. `ThreadProjection`
tracks a per-conversation `OwnEchoQueue(queued, delivered)`: `queued` is the intersection of the minted-id
ledger and the latest `queue_state` snapshot's ids, minus `delivered`; `delivered` is every id already moved,
so a daemon repeating an id in a later snapshot (legal — `message_id` is unique nowhere) can never park the
row a second time.

- **While queued**, [`observe`](remote-conversation-repository-reads-and-thread-store.md#observemessagesconversationid--the-live-thread-read-313)
  reads every row in `queued` out of the thread, runs the ordinary streaming-settle rule
  ([`withOnlyLastRowStreaming`](streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350))
  over what is left, and appends the parked rows back in thread order — so a reply row that streams in *after*
  the echo was sent still reads above it, and the running reply keeps its single streaming caret.
  [`foldQueuedRows`](queued-backlog-section.md) still runs unchanged on top of this already-reordered list,
  so its own position rule ("every thread item appears exactly once, at its own index, in order") stays true;
  it simply never sees the echo at its tap-time index any more.
- **The store itself keeps tap-time order while queued — only the read reorders.** Moving the echo in the
  store at queue time was tried and rejected: `withAssistantDelta` extends only the thread's *last* row, so
  every later delta of the running turn would open a fresh segment below an echo moved early. Leaving the
  store alone and reordering only in `observe` means the data layer's other writers (`appendMessages`,
  `applyAssistantDelta`, the tool folds) need no awareness of queued echoes at all.
- **The running reply stays one bubble.** `HistoryPageReducer.withAssistantDelta` gained an optional
  `passOver: Set<String>` naming user rows to skip when picking the "last row" a delta extends —
  `ThreadProjection.applyAssistantDelta` passes the conversation's `queued` set, so a delta still extends the
  reply it belongs to instead of opening a second segment below the parked echo. The history reducer's own
  caller passes nothing; see [Remote conversation repository § Assistant reply
  segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350).
  **`passOver` only ever names an id already in `queued`**, which the echo joins on the *first* `queue_state`
  that reports it — not at tap time. A delta that lands between the tap and that first snapshot still opens a
  second segment below the echo, exactly as it did before this ticket; the gap is the same shape as the one
  [Streaming assistant turns](streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350)
  already records for a cache composed after `observe`'s settle rule.
- **On delivery, the row moves to the end exactly once**, on whichever of two daemon frames names it first:
  `settleQueuedEchoes`, called from the `queue_state` arm right after `settleDrops` (see [Remote conversation
  repository § Control sends](remote-conversation-repository-control-sends.md)), moves every id the fresh
  drain snapshot no longer holds; `appendLiveMessage` moves an id the instant the daemon pushes its delivered
  `message` copy, before the held-row check that would otherwise leave it in place. **The store move always
  happens before the id stops counting as queued**, in both arrival orders, so no intermediate emission ever
  shows the row back at its tap-time position — moving first and un-parking second is what closes that window,
  not a special case for either order.
- **Only this device's own user rows ever move.** `moveOwnEchoToEnd` checks the minted-id ledger and
  `Role.User` before touching the store, and the `observe` read applies the same `Role.User` check to its
  own `queued` membership test (a security-review finding, since the ledger's `queued ⊆ minted` invariant
  held only by construction otherwise) — so a daemon frame naming another device's id, or a non-user row,
  never moves or changes a row.
- **A dropped echo is removed, not moved.** [`settleDrops`](remote-conversation-repository-control-sends.md)
  runs first and spends the dropped id's ledger membership, so by the time `settleQueuedEchoes` looks at the
  same snapshot the id is already gone from the ledger and cannot be moved.
- **The daemon reports every send in a `queue_state`, idle sends included** (`Queue.EnqueueAttached` calls
  `notify` on every enqueue) — the plan's "an idle send never appears in a snapshot" claim does not hold. An
  idle send is unaffected anyway: it is already the thread's last row, and the daemon pushes its delivered
  `message` right after the stdin write, before claude's first streamed token can arrive, so it is never
  observed parked. A client design should not assume an idle send is invisible to this path.
- **A known remaining gap:** the protocol does not promise the delivered `message` reaches the phone before
  the drained turn's first `assistant_delta`. In practice the daemon sends the delivered copy first, since
  the push follows the stdin write while the reply needs an API round trip; if that order ever flipped, the
  reply would open above the still-parked echo and split around it.
- **Live coverage:** `InteractiveStreamE2ETest.interactiveTurn_peerQueue_staysConsistentAcrossClients` asserts,
  after its existing step 6, that the drained row sits below the last row of the peer's wait turn via
  `boundsInRoot` — it is the only live method that draws a queued row, so it is the one live test this fix
  needed. See [the real-Claude ladder](../../e2e-interactive-stream.md).

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
- **A daemon predating pyrycode#2092 loses the whole backlog view, not just correlation (#781).**
  `message_id` is strict-required on `QueuedMessageDto`; an older daemon that omits the key entirely
  fails the structural decode of the whole item, so the snapshot drops via the existing "one bad item
  drops the chunk" idiom above — the backlog view goes empty rather than degrading to "no correlation".
  Accepted deliberately (the wire contract states the field is always present) and recorded here so a
  version-skew symptom of "the backlog went blank" is traceable back to this trade.
- **The channel-list preview still shows a dropped message (#781).** `sendMessage` also writes
  `lastMessages`, which drives the conversation-row preview on the [channel list](channel-list-screen.md);
  a confirmed drop does not revert it, because the previous value is not retained and re-deriving one from
  a possibly-partial thread would regress the preview to an older or absent message. Out of scope for the
  echo-removal AC, which names thread rows only — a decision point for whichever ticket folds the backlog
  into the thread list.
- **A backlog diff is deliberately not the removal trigger (#781).** A backlog also shrinks when the
  daemon *drains* it and runs the message normally, so a diff-driven removal would delete the echo of
  every message that ran to completion — a worse lie than the one this ticket fixes. `pyrycode-desktop`'s
  #1213 rejected the same shape for the same reason.
- **The minted-id ledger** (`RemoteConversationRepository.mintedMessageIds`, `conversationId -> ids this
  device minted and echoed`) holds one entry per successful send minus every consumed drop, and is
  connection-scoped and in-memory like every sibling projection (#351) — it dies with the connection and
  is never persisted or observed.

## Security

`security-sensitive`; architect self-review **PASS**, code-review **PASS**. One untrusted→trusted boundary
— `decodeQueueState(envelope): Pair<String, List<QueuedMessage>>?` through the single configured
`MobileJson`, behind the already-authenticated Noise channel. `QueuedMessage.text` is **user-authored
queued-message content** (potentially sensitive) and is carried **verbatim, never logged** — the same
discipline #385/#387 apply to `assistant_delta` / tool summaries; `decodeQueueState` logs nothing on the
drop path. The data layer surfaces only a `List<QueuedMessage>`, never an error or a raw `JsonElement`.
Memory posture is bounded by **replacement** (each snapshot overwrites a conversation's backlog, never
accumulates), the same daemon-supplied-id growth posture `lastMessages` / `threadByConversation` /
`stalledConversations` already accept under the paired-daemon threat model. `queued_msg_id` is a
per-conversation counter, not a nonce (no constant-time-compare concern). UI-leakage threats
(screenshot/overlay of the rendered backlog text) were forwarded to **#461** (the visible render) and
**resolved there**: `entry.text` is the same user-content class the thread host already renders for sent
messages **without** `FLAG_SECURE`, so the [queued backlog section](queued-backlog-section.md) adds no new
screen-capture surface — see [#461](../codebase/461.md). #461 is therefore **not** `security-sensitive`.

**#781 (echo removal via `message_id`), also `security-sensitive`, architect self-review PASS.** No new
trust boundary — `message_id` crosses untrusted→trusted at the same `decodeQueueState` seam as every other
queue field. `QueuedMessage.messageId` is a **public** domain field with no type-level distinction from a
trusted string, so its KDoc carries the constraint as a contract: compared for equality only, never
rendered, never a list key (duplicate values are legal and would crash a `LazyColumn` keyed on it), never
logged. The **minted-id ledger never holds a wire-supplied value** — it only ever holds ids this device
itself minted via `UUID.randomUUID()` (a 122-bit CSPRNG v4 UUID) in `sendMessage`, so no wire input can
grow it, and no constant-time compare is warranted (the id guards no authority — it addresses nothing on
the wire and the phone already discloses every one of its own ids to the daemon on send). **Accepted, not
a finding:** a paired daemon — already inside the trust domain per ADR 025 and able to fabricate arbitrary
thread content by simpler means — knows every `message_id` this phone has sent and could forge a
`queue_state` item carrying one to make an operator-triggered drop delete a *different* local echo; impact
is bounded to one row on one device, nothing is disclosed, and no data is lost (the daemon holds the
transcript). The ledger's actual job is defeating a **paired device**, which does not know this phone's
minted ids and so cannot forge a match.

**#1558 (own echo position), also `security-sensitive`, self-review PASS.** The same ledger now also gates a
*reorder*, not only a removal: `moveOwnEchoToEnd` and the `observe` read both require `Role.User` membership
in `mintedMessageIds` before touching a row. A hostile `queue_state` or `message` can therefore only move a
row this device minted — never rewrite its content, and never touch an assistant, tool or foreign-device row
— so the existing #781 threat model (forging a match costs a paired daemon nothing it couldn't already do by
simpler means; a paired device cannot forge a match at all) extends unchanged to the move.

## Related

- [#460 implementation notes](../codebase/460.md) (inbound decode) / [#466 implementation notes](../codebase/466.md)
  (outbound `dropQueuedMessage` send) — files, line refs, lessons, verification. #781 (`message_id` +
  echo removal) postdates the frozen archive; its notes live in this document and in
  [conversation-repository.md](conversation-repository.md).
- [Remote conversation repository](remote-conversation-repository.md) — hosts the `TYPE_QUEUE_STATE` arm and the outbound
  `dropQueuedMessage` send; `QueueProjection` holds the `queuedByConversation` state, the decode and the
  `observeQueue` read.
- [#1558 implementation notes](https://github.com/pyrycode/pyrycode-mobile/issues/1558) (own echo position —
  postdates the frozen archive; notes live in this document, [Queued backlog rendering](queued-backlog-section.md),
  [Remote conversation repository § reads and the thread store](remote-conversation-repository-reads-and-thread-store.md),
  [§ Control sends](remote-conversation-repository-control-sends.md), [Streaming assistant
  turns](streaming-assistant-turns.md) and [§ Assistant reply
  segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350)).
- [Stall state](stall-state.md) (#395) — the structural twin: the decode→state→observe shape, gate, and
  test harness this reuses; the onset-only counterpoint to this full-snapshot model.
- [API-retry status](api-retry-status.md) (#593) — follows this arm's payload-carrying `Map` projection
  shape (rather than stall's bare `Set`) because it too carries more than a boolean's worth of state —
  a counter with both a rising and falling edge, where this arm has a full-replace snapshot with neither.
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
