# Remote conversation repository — the Phase 4 `ConversationRepository`

The **live, server-backed implementation** of the [`ConversationRepository`](conversation-repository.md)
contract — the Phase 4 counterpart to the in-memory `FakeConversationRepository`. It reads real server
state over the [Mobile Protocol v2](mobile-protocol-v2-wire-layer.md) Noise session instead of an
in-process seed store, and is swapped in for the Fake via a Koin module (per CLAUDE.md: the Phase 4
backend swap is architectural — replace the binding, don't special-case the UI).

Package: `de.pyryco.mobile.data.repository` (`RemoteConversationRepository` + the consumer-defined
`SessionPump` interface), same package as the contract and the Fake. Built **slice by slice**: the
conversation-list read path landed in [#312](../codebase/312.md), the last-message preview in
[#329](../codebase/329.md), and the thread read path in [#313](../codebase/313.md); the **first mutation
path — `sendMessage` — landed in [#346](../codebase/346.md)** (which also introduced the shared
`ack`/`error` request↔reply correlation primitive the remaining mutations reuse). The mutation slice
#314 was split on a per-method axis into #346 (`sendMessage`) / #347 (`createDiscussion`) / #348
(`promote`), each extending the **same class** as it lands. Portable, `android.*`-free.

> **Constructed per connection by #351; not yet the UI's binding.** #312 landed
> `RemoteConversationRepository` with no consumers. The
> [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) ([#351](../codebase/351.md), **landed**)
> now constructs one **per live connection** against the pump + a connection-scoped child scope, and made
> the concrete pump satisfy the contract (`NoiseSessionPump : ManagedSessionPump : SessionPump`). It
> publishes the live repository on `currentRepository` for the **#352** stable facade to consume.
> **[#350](../codebase/350.md) (landed)** added the flag-gated `conversationRepositoryModule` selector
> that binds `ConversationRepository` to the facade when `BuildConfig.USE_RELAY_REPOSITORY` is on — but
> the flag **defaults OFF**, so the UI still binds `FakeConversationRepository` today. (The build flag
> selects Fake vs. facade; paired-state still governs whether the bound facade has a *live* delegate, per
> [[phase4-no-central-flag-gate-per-piece]] — the two are orthogonal.) See [Hand-off](#hand-off--the-live-binding).

## Where it sits in the Phase 4 stack

```
UI ViewModels  ◀── observeConversations(filter): Flow<List<Conversation>>   (binding-agnostic: Fake or Remote)
        ▲
RemoteConversationRepository (#312+)   ◀── this doc
        │  send(list_conversations) ; collect inbound conversations snapshots
        ▼  (over the SessionPump interface)
NoiseSessionPump (#309) ─ inbound: Flow<Envelope> / send(Envelope): Boolean
        ▼
RelayTransport (#306) ─ OkHttp WS ─ Noise_IK (#303)
```

The repository consumes the pump over the **portable `SessionPump` interface** — it never reaches below
the pump to the raw frame transport or the Noise session, and it never re-implements wire↔domain mapping
(that is the [#316](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)
mapper's job). It runs **behind** the already-authenticated Noise channel: the internet-exposed frames +
crypto are the pump/transport's concern, and the untrusted-payload decode-and-validate boundary is the
#316 mapper — so the repository is plain orchestration over already-authenticated, delegated-decode data
(not `security-sensitive`).

## The `SessionPump` consumed contract

A minimal **consumer-defined** interface — the data layer's view of the Noise session pump:

```kotlin
interface SessionPump {
    val inbound: Flow<Envelope>            // hot, single-consumer, decrypted app envelopes
    fun send(envelope: Envelope): Boolean  // false if the session is not Open; never throws
}
```

It lives in `data/repository/` (next to its consumer), **not** in `data/network/` (where the concrete
`NoiseSessionPump` lives), mirroring the [`ConnectionStateSource`](connection-state.md) precedent: the
consumer defines the contract it needs; the real impl satisfies it a layer down. The two members match
`NoiseSessionPump`'s structurally, so the DI slice makes the concrete class conform with just
`: SessionPump` + two `override`s.

`inbound` is **hot and single-consumer** (the pump runs the one inbound collector regardless of
subscribers and surfaces each decrypted `Envelope` exactly once). `send` returns `false` (no throw) if
the session is not `Open`. Note the pump's consumer-awareness rule: a `false` return on an `Open` session
means the session is **spent** (a nonce was consumed) — do **not** re-send the same envelope on the same
session; the supervisor will reconnect with a fresh handshake. The repository does not buffer or retry —
see [Error handling](#error-handling).

## The repository — one projection, cold fan-out

```kotlin
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,        // connection-scoped child scope (the #351 coordinator); tests pass runTest's backgroundScope
    private val deviceName: String = "",  // connection-level device_name for register_push_token (#359); last + defaulted
) : ConversationRepository
```

The `deviceName` param ([#359](../codebase/359.md)) is the connection-level `device_name` that
[`registerPushToken`](#registerpushtokentoken--the-device-concern-push-registration-359) sends — the same
value [`NoiseClientInfo.deviceName`](noise-ik-session.md) puts in the `hello` payload. It is **last and
defaulted (`""`)** so that, when [#359](../codebase/359.md) shipped it, the one production construction site
([`RelayRepositoryCoordinator.onConnection()`](relay-repository-coordinator.md)) and all existing test sites
compiled unchanged — **zero edit fan-out** (which kept #359 off the now-merged #352 conflict path).
[#365](../codebase/365.md) then **closed that defer**: the coordinator passes the live value here
(`RemoteConversationRepository(pump, childScope, deviceName)`), sourced from
[`NoiseClientInfo.deviceName`](noise-ik-session.md) (`Build.MODEL`) in `AppModule`, and its connect-time
hook is the first live caller of `registerPushToken`. So `""` is **no longer the production value** — see
[`registerPushToken`](#registerpushtokentoken--the-device-concern-push-registration-359).

**The list projection.** One `private val projection = MutableStateFlow<List<Conversation>?>(null)`
(`null` = list not yet loaded); every cold read derives from it. It has **two writers** since
[#347](../codebase/347.md): the `init` collector's authoritative full-replace on each `conversations`
snapshot, **and** the two mutations' confirmed folds (an atomic CAS upsert via `upsertConversation`) —
`createDiscussion`'s insert (#347) and `promote`'s in-place upsert ([#348](../codebase/348.md)). All go
through `MutableStateFlow.update {}`, so they retry-merge rather than clobber — see
[State & concurrency model](#state--concurrency-model). `promote` additionally **reads** `projection.value`
(a lock-free snapshot) to resolve a conversation's existing cwd when its `workspace` argument is null. No
parallel mutable state.

**Single inbound consumer (the fan-out owner).** Because `pump.inbound` is hot and single-consumer, the
repository launches **exactly one** long-lived collector in `init` on the injected `scope`. That collector
demultiplexes each envelope by `Envelope.type`:

| `Envelope.type` | Handling |
|---|---|
| `"conversations"` | Decode `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload).toConversations()` (#316) → assign to `projection`. A **full-list snapshot** — both the reply to our request and any unsolicited server change-push arrive this way, so re-emission needs **no `in_reply_to` correlation**. Decode is wrapped in a per-envelope `try/catch` (a malformed snapshot is dropped, the collector survives). |
| `"message"` | Decode `MobileJson.decodeFromJsonElement<MessagePayloadDto>(payload)` (#317) → key by `conversation_id` (**read off the DTO before mapping** — the domain `Message` carries none) → `toMessage(envelope, sessionId = "")`, then **two folds** off the one decoded DTO: (a) the strictly-greater-by-`timestamp` `lastMessages` preview fold ([#329](../codebase/329.md)); (b) an arrival-order append into the `messagesByConversation` thread ([#313](../codebase/313.md)). Same per-envelope `try/catch` drop; **silent** (no payload logged, content may be sensitive). The singular live/echo `message`. See [`observeLastMessage`](#observelastmessageconversationid--the-live-last-message-preview-329) and [`observeMessages`](#observemessagesconversationid--the-live-thread-read-313) below. |
| `"message_chunk"` | The `backfill_since` **response** body ([#313](../codebase/313.md)): `MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(payload)` → map **each** row via the same `toMessage(envelope, sessionId = "")` (one envelope `ts` covers every row) → append the whole batch into `messagesByConversation` in one atomic `update`. Each row **self-routes** by its own `conversation_id` — no `in_reply_to` correlation. A single bad row drops the **whole chunk** in one `catch (IllegalArgumentException)`; silent. |
| `"ack"` / `"conversation_created"` / `"conversation_updated"` / `"screen_snapshot"` | Correlated **success** reply to an outgoing request, **one shared arm** ([#346](../codebase/346.md) / [#347](../codebase/347.md) / [#348](../codebase/348.md) / [#375](../codebase/375.md)): `envelope.inReplyTo?.let { pendingRequests[it]?.complete(envelope.payload) }`. An `ack` payload is the empty `{}` a bare-`ack` caller (`sendMessage`) ignores; a `conversation_created` (`createDiscussion`) / `conversation_updated` (`promote`) payload is the **bare conversation object** the mutation decodes for its typed return; a `screen_snapshot` (`requestScreenSnapshot`, #375) payload is the **rendered-screen object** the read decodes for its `text`. The payload is handed verbatim to the waiting suspend, which decodes (or ignores) it **in the caller's coroutine** — so a malformed reply never throws inside this collector. An `inReplyTo` matching no pending entry (or null) is a no-op; `complete` is idempotent (duplicate reply harmless). `screen_snapshot` is **always** a correlated reply (no unsolicited snapshot push), so an unmatched one is the same harmless no-op; `conversation_updated` is also the server's **unsolicited broadcast** on change (no `inReplyTo`), which must stay a harmless no-op here — the authoritative `conversations` snapshot, not this delta, drives an unsolicited list refresh. |
| `"error"` | Correlated **failure** reply ([#346](../codebase/346.md)): `envelope.inReplyTo?.let { pendingRequests[it]?.completeExceptionally(mapError(envelope.payload)) }` — unblocks the waiter exceptionally with the mapped domain error. `mapError` **never throws** (a malformed payload yields a fallback exception), so the lone collector survives; `completeExceptionally` is idempotent and a no-op when no entry matches. |
| anything else | **No-op** (intentional `else`, not a bug). `backfill_done` (`{delivered}`) needs no action — the `message_chunk` already delivered the history, the count is informational. **Unsolicited** single-row deltas (a server-pushed `conversation_created`/`conversation_updated` with no `inReplyTo` match) are caught by the success arm above and no-op there — merging them into the live projection is future work; the list refreshes on the next `conversations` snapshot. |

> **Correlated reply vs unsolicited delta — a single-`Conversation` payload is handled two ways.**
> `conversation_created` / `conversation_updated` are single-`Conversation` payloads mapped by
> [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s
> `ConversationResponseDto`, **not** #316's list mapper. When such a payload arrives as the **correlated
> reply** to *our own* mutation request (matching `inReplyTo`), it routes through the success arm and the
> mutation method decodes it + confirmed-folds it into the projection — `conversation_created` for
> `createDiscussion` ([#347](../codebase/347.md), **landed**), `conversation_updated` for `promote`
> ([#348](../codebase/348.md), **landed**). When the **same** payload arrives **unsolicited** (a promote/rename/archive made on *another*
> device, no `inReplyTo` match), it is still a no-op here — merging an unsolicited delta into the live
> projection is future work; the production list refreshes on the next `conversations` snapshot
> (re-subscribe / reconnect). This is why the *read* path depends on **#316 only, not #318**: #318 entered
> via the mutation slices.

## `observeConversations(filter)` — the live method

A **cold** `Flow`. On each collection it:

1. issues the request — `pump.send(listConversationsRequest())`, where the request is `Envelope(id =
   <AtomicLong>.incrementAndGet(), type = "list_conversations", ts = Clock.System.now().toString(),
   payload = JsonObject(emptyMap()))` (payload `{}` per protocol);
2. `emitAll`s the projection mapped through a pure `project(list, filter)` — `filterNotNull()` first, so a
   collector **blocks until the first snapshot loads**, then receives the current projection on
   subscription and every subsequent change.

`project(list, filter)` mirrors the Fake **exactly** so the UI behaves identically under either binding:

```
filter:  All        -> every row
         Channels   -> isPromoted && !archived
         Discussions-> !isPromoted && !archived
         Archived   -> archived
then:    sortedByDescending { lastUsedAt }
```

N concurrent collectors share the one projection (cold fan-out over a single hot inbound consumer) — the
multi-collector requirement is met with a single inbound consumer.

**send-on-each-subscribe is intentional.** Redundant `list_conversations` requests are absorbed by
`StateFlow` conflation (a value-equal snapshot does not re-emit), and re-subscribing (e.g. on lifecycle
resume) naturally re-issues the request — more robust than a send-once guard if an early send was dropped
pre-`Open`.

### List-tier placeholders (from #316)

The `conversations` wire summary does not carry full session/sleep/archive state, so the #316 mapper fills
four domain fields with documented list-tier defaults — `currentSessionId = ""`, `sessionHistory =
emptyList()`, `isSleeping = false`, `archived = false` — never `null`-punned. Full enrichment arrives via
the detail/message read paths (#313+), not here. (This is why `Archived` is empty under the pure list
path until a richer source lands: the list snapshot never carries `archived = true`.) The wire's
`last_message_ts` maps to **no** domain field — so the last-message preview is **not** derivable from
this payload; it comes from the message read path ([#329](../codebase/329.md), now live — see the
[`observeLastMessage`](#observelastmessageconversationid--the-live-last-message-preview-329) section
below). See [[v2-app-payload-shapes-ssot]].

## `observeLastMessage(conversationId)` — the live last-message preview (#329)

The channel list's last-message preview. Because the `conversations` wire summary carries no message
content (only `last_message_ts`, which maps to no domain field), the preview is sourced from the
**message read path**, not the list read. [#329](../codebase/329.md) implements it by **riding the live
`message` stream** (Design A — no `backfill_since` request, no `message_chunk` parsing):

- A second projection, `private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())`,
  is fed **only** by the same single `init` inbound collector via the `"message"` demux branch above —
  decode through the [#317](mobile-protocol-v2-wire-layer.md) `MessagePayloadDto.toMessage` boundary
  (`sessionId = ""`, a list-tier placeholder the preview never reads), key by the DTO's
  `conversation_id`, and fold the **strictly-greater-by-`timestamp`** message via
  `MutableStateFlow.update { }`. Strictly-greater means out-of-order older arrivals and re-delivered
  duplicates are no-ops — matching the Fake's `maxByOrNull { it.timestamp }` most-recent semantics.

- The method itself is a **pure cold projection**, issuing **no request** (it rides the stream):

  ```kotlin
  override fun observeLastMessage(conversationId: String): Flow<Message?> =
      lastMessages.map { it[conversationId] }.distinctUntilChanged()
  ```

  A `StateFlow` always has a current value, so every collector — including a `flatMapLatest`
  re-subscription from `ChannelListViewModel` — receives the current most-recent (or `null` when the
  conversation is absent/never-seen) on subscription, re-emits only on change, and supports unlimited
  concurrent collectors off the one inbound consumer.

> **Cold-start gap (still open after #313).** With Design A a quiescent conversation's preview stays
> empty until a live `message` arrives on **this** connection; it does not back-fill from history.
> [#313](../codebase/313.md) landed the backfill plumbing (`backfill_since` → `message_chunk`) but feeds
> it into the **thread** projection (`messagesByConversation`), **not** `lastMessages` — so the preview
> cold-start gap is unchanged. A later follow-up can fold `message_chunk` rows into `lastMessages` (the
> chunk's one envelope `ts` covers every row, so it needs a most-recent-row reconciliation, not a
> per-row `ts`). Until then the preview is a strict improvement over nothing: real content for any
> conversation with live traffic.

## `observeMessages(conversationId)` — the live thread read (#313)

The conversation thread: a chronological `List<ThreadItem.MessageItem>` of **backfilled history merged
ahead of the live `message` stream**, deduped by `message_id`, in wire/arrival order.
[#313](../codebase/313.md) implements it over a third projection fed by the **same** single inbound
collector:

- A third projection, `private val messagesByConversation = MutableStateFlow<Map<String,
  List<Message>>>(emptyMap())`, holds each conversation's ordered, `message_id`-deduped thread. It is
  written **only** by the one `init` collector, from **two** demux arms (see the table above): the
  `message` arm appends each live message, and the `message_chunk` arm appends a whole backfill batch.
  Both go through one accumulator:

  ```kotlin
  private fun appendMessages(rows: List<Pair<String, Message>>) {  // (conversationId, Message)
      if (rows.isEmpty()) return
      messagesByConversation.update { current -> /* per row: first-seen id appends at end;
          repeat id replaces in place (indexOfFirst), position fixed at first occurrence */ }
  }
  ```

  Dedup is **last-write-in-place**: a `message_id` seen in both the backfill chunk and a later live
  `message` appears once, at its first position, with the later payload winning. Batching a whole chunk
  into one atomic `update` avoids emitting an intermediate list per row.

- The method is a **cold** flow mirroring `observeConversations` exactly — issue the request, then fan
  out the per-conversation projection:

  ```kotlin
  override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
      flow {
          pump.send(backfillSinceRequest(conversationId))
          emitAll(messagesByConversation
              .map { it[conversationId].orEmpty().map(ThreadItem::MessageItem) }
              .distinctUntilChanged())
      }
  ```

  `distinctUntilChanged()` means a change to **another** conversation's slot does not re-emit this flow.
  Re-sends `backfill_since` on every subscription; re-delivered history is absorbed by the `message_id`
  dedup, so no idempotency guard is needed, and a pre-`Open` send (returns `false`) self-heals on the
  next subscribe while the live stream still fills the thread.

- **`backfill_since` is the one outgoing request.** `backfillSinceRequest(id)` builds `Envelope(type =
  "backfill_since", payload = BackfillSincePayloadDto(since_ts = "1970-01-01T00:00:00Z", conversation_id
  = id, max_messages = 10_000))` — full history from the Unix **epoch** (the wire `since_ts` is a
  *required* RFC-3339 timestamp, so "all history" is the epoch, not an absent field) with an advisory
  `max_messages` cap. Wire SSOT: server [#272](https://github.com/pyrycode/pyrycode/issues/272)
  `BackfillSincePayload` (`internal/protocol/messaging.go`). The reply (`message_chunk` + a terminal
  `backfill_done` the repo ignores) is correlated by `inReplyTo` on the wire, but the repo never reads
  it — each chunk row self-routes by its own `conversation_id`. See [[v2-app-payload-shapes-ssot]].

- **Ordering is wire/arrival order — no client-side timestamp sort.** A `message_chunk` carries **one**
  envelope `ts` for many rows, so a timestamp sort is impossible *and* wrong; the ordered inbound stream
  delivers the backfill chunk before the live messages the server emits afterward, so appending in
  arrival order yields history-then-live naturally. The encrypted stream cannot skip a frame — do not
  reorder around a gap.

> **Two de-scopes, both blocked on a server-side v2 protocol addition — neither is a bug.** The thread
> emits `ThreadItem.MessageItem`s **only**: the v2 wire has no session-transition representation, so
> `ThreadItem.SessionBoundary` cannot be constructed ([#336](https://github.com/pyrycode/pyrycode-mobile/issues/336),
> see [[phase4-v2-wire-no-session-boundary]]); and every message is *finished* (`isStreaming = false` via
> #317's `toMessage`), so live token-streaming cannot be produced
> ([#337](https://github.com/pyrycode/pyrycode-mobile/issues/337), see [[phase4-v2-wire-no-streaming]]).

> **Known follow-up: `appendMessages` dedup is O(n²) over thread size** (`indexOfFirst` + `existing +
> message` per row). Deferred by evidence — no live backfill yet (`max_messages` unexercised; the
> backend dispatcher is pyrycode #248), no observed perf failure. Swap to a `LinkedHashMap<messageId,
> Message>` accumulator when large-thread backfill goes live; the change is internal to `appendMessages`.

## `sendMessage(conversationId, text)` — the first mutation (#346)

Posts the user's text over v2 `send_message` and returns the persisted `Message`.
[#346](../codebase/346.md) implements it, and in doing so adds the **shared request↔reply correlation
primitive** the remaining mutations (#347/#348) reuse.

**The corrected wire premise is load-bearing.** `send_message` does **not** echo the sender its own
persisted `Message`. The only sender-correlated reply is an empty **`ack`** on success
(`in_reply_to == requestId`, payload `{}`) or an **`error`** on failure (`{code, message, retryable}`).
A `message` envelope reaching this device is a user-echo to *other* paired devices or the assistant's
*later, unsolicited* reply (`in_reply_to: null`), **never** a sender echo — so there is nothing to map
for the return value, and the sender's own thread updates **only** if `sendMessage` projects the
message locally. (The "send_message echo" phrasing still in the `TYPE_MESSAGE` code comments predates
this correction and is harmless — that path correctly handles the other-device / assistant cases.) See
[[phase4-send-message-acks-not-message-echo]] and [[phase4-request-encoders-live-in-mutation-tickets]].

**Decision (PO-owned, `security-sensitive`): confirmed-insert, not optimistic.** The message is
projected into the read path **only on the correlated `ack`** — never before, never on failure. There
is no rollback path because nothing is inserted speculatively. The trust property — the thread never
shows a message the server did not receive — holds **by construction**. (`Message` has no
delivery-status field, so an optimistic insert could only render as a normal-looking message that might
silently vanish; perceived send latency is a future UI-layer concern, not a data-layer one.)

The flow (≤ ~15 lines):

```kotlin
override suspend fun sendMessage(conversationId: String, text: String): Message {
    val messageId = UUID.randomUUID().toString()        // client-minted; the sender's correlation handle
    val sentAt = Clock.System.now()
    val request = Envelope(
        id = requestId.incrementAndGet(),               // the ENVELOPE id — distinct from messageId
        type = "send_message", ts = sentAt.toString(),
        payload = MobileJson.encodeToJsonElement(
            SendMessagePayloadDto(conversationId, messageId, text)),  // {conversation_id, message_id, text}
    )
    sendAndAwaitReply(request)                           // throws on error / not-Open; ignore the empty {} ack
    val message = Message(messageId, sessionId = "", Role.User, text, sentAt, isStreaming = false)
    recordLastMessage(conversationId, message)           // confirmed-insert into BOTH projections,
    appendMessages(listOf(conversationId to message))    //   only after the ack
    return message
}
```

This **mirrors `FakeConversationRepository.sendMessage`'s observable contract** (mint id, reconstruct a
`Role.User` message, insert, return) so the UI behaves identically under either binding. The one
intentional divergence: `sessionId = ""` (the remote's list-tier placeholder; the v2 wire carries no
`session_id`, boundaries are #336) vs the fake's `currentSessionId`. There is **no local cache guard**
for an unknown conversation — the server is authoritative (the cached `projection` can be stale), so an
unknown id surfaces through the server's `conversation.not_found` `error`. Observably identical to the
fake's synchronous throw; the contract pins the exception *type*, not the timing.

### The `ack`/`error` correlation primitive (reused by #347/#348)

The class still runs **exactly one** `pump.inbound` collector — `sendMessage` does **not** open a
second subscription. Correlation rides that single collector via a generic pending-reply registry:

```kotlin
// keyed by the request ENVELOPE id (requestId.incrementAndGet()), distinct from the payload message_id
private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

private suspend fun sendAndAwaitReply(request: Envelope): JsonElement {
    val deferred = CompletableDeferred<JsonElement>()
    pendingRequests[request.id] = deferred                       // register BEFORE send (no lost-reply race)
    return try {
        check(pump.send(request)) { "${request.type} not sent: session not connected" }  // → IllegalStateException
        deferred.await()                                          // collector completes it on ack/error
    } finally {
        pendingRequests.remove(request.id)                        // success, error, AND caller cancellation
    }
}
```

- **Keyed on the envelope id, not the payload `message_id`** — two distinct ids; conflating them would
  break correlation. The collector's `ack`/`error` arms complete the matching deferred by
  `Envelope.inReplyTo` (see the demux table above).
- **`ConcurrentHashMap`** matches the file's existing `java.util.concurrent` posture (`AtomicLong`);
  it's touched from both the connection-scoped collector coroutine and arbitrary caller coroutines.
- **Typed `<JsonElement>`** (the raw reply payload) so the one registry serves a bare-`ack` caller
  (`sendMessage` ignores the empty `{}`) **and** future typed-reply callers (#347/#348 decode
  `conversation_created`/`conversation_updated` from it). Those siblings add their success-type `when`
  branch and reuse the registry + the `error` branch + `mapError` **as-is**.
- **`sendMessage` suspends on `CompletableDeferred.await()` in the *caller's* coroutine** (a ViewModel
  scope), not the connection scope. Caller cancellation → `CancellationException` → the `finally`
  removes the entry (no leak). The reply is delivered by the collector coroutine;
  `complete`/`completeExceptionally` are thread-safe and idempotent across the two coroutines.

`mapError(payload): Throwable` turns a server `error` into the thrown domain exception: decode
`ErrorPayload` through `MobileJson`; `conversation.not_found` → `IllegalArgumentException("Unknown
conversation: …")` (fake parity), every other code → [`RelayErrorException`](mobile-protocol-v2-wire-layer.md)`(code,
retryable, message)`. A **decode failure is caught** and returns a fallback `RelayErrorException`
(`error.malformed_reply`) so the waiter is **never** left hanging and the lone collector survives — a
deterministic safety net, not a re-thrown exception. `mapError` logs nothing (message content stays off
the log).

> **`recordLastMessage` extraction.** The strictly-greater-by-timestamp fold (formerly inline in the
> `message` arm, [#329](../codebase/329.md)) was extracted to `private fun recordLastMessage(...)` so
> both the live `message` arm and `sendMessage` share it (mirroring the pre-existing `appendMessages`).
> Behavior-preserving; the one sanctioned edit to the collector's existing `TYPE_MESSAGE` body.

## `createDiscussion(workspace)` — the second mutation (#347)

Creates an unpromoted discussion over v2 `create_conversation` and returns the server-created
`Conversation`. [#347](../codebase/347.md) implements it by **composing** two already-merged building
blocks — #346's `sendAndAwaitReply` correlation primitive and [#318](mobile-protocol-v2-wire-layer.md)'s
`ConversationResponseDto.toConversation()` decode mapper — with one new `create_conversation` request
encoder (`CreateConversationPayloadDto`).

**The central contrast with `sendMessage`.** `send_message`'s reply is an empty `ack`, so `sendMessage`
*reconstructs* its return `Message` from the input. `create_conversation`'s reply is a **typed
`conversation_created` payload** carrying the **server-assigned** `id` and `cwd`, so `createDiscussion`
*decodes* its return `Conversation` from the reply — it cannot reconstruct it (the server assigns the id,
and a null `workspace` means the server picks the scratch `cwd`). The reply rides the **same** correlation
primitive: `sendAndAwaitReply` hands the raw reply `JsonElement` back, and the caller decodes it.

The flow (≤ ~10 lines):

```kotlin
override suspend fun createDiscussion(workspace: String?): Conversation {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = "create_conversation", ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(CreateConversationPayloadDto(cwd = workspace)),
    )
    val reply = sendAndAwaitReply(request)              // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    upsertConversation(conversation)                    // confirmed-insert — ONLY after a successful decode (AC #2)
    return conversation
}
```

- **The request encoder is `CreateConversationPayloadDto`** (`is_promoted` always `false`, optional
  `cwd`; `name` unmodeled — discussions are server-auto-named). Under `MobileJson` (`explicitNulls =
  false`) a null `cwd` is **omitted** from the JSON (not `"cwd":null`), which the server decodes
  identically to an absent key (its `*string` field has no `omitempty`) — so `createDiscussion(null)`
  encodes to `{"is_promoted":false}` and means "server assigns the scratch cwd". See the
  [wire-layer doc](mobile-protocol-v2-wire-layer.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
- **The returned `cwd` is the server's reply value, never the input.** A null `workspace` returns the
  server-assigned scratch cwd (`DEFAULT_SCRATCH_CWD`); this is the one divergence from
  `FakeConversationRepository.createDiscussion`, which picks `cwd = workspace ?: ""` locally (AC #1).
- **Decode precedes the fold, so a garbage reply cannot corrupt the list.** A malformed
  `conversation_created` (missing field / bad `last_used_at`) throws the #318 decode boundary's
  `SerializationException` / `IllegalArgumentException` **before** `upsertConversation` runs — the
  projection is never mutated by a failure path (AC #3). The decode runs in the **caller's** coroutine
  (after the `pendingRequests` entry is removed), so it never throws inside the single inbound collector.

### Confirmed-insert via `upsertConversation` (the projection's second writer)

```kotlin
private fun upsertConversation(conversation: Conversation) {
    projection.update { current ->                       // atomic CAS — retry-merges with a concurrent snapshot
        val existing = current.orEmpty()                 // null projection → single-element list
        val index = existing.indexOfFirst { it.id == conversation.id }
        if (index >= 0) existing.toMutableList().apply { this[index] = conversation }  // upsert by id
        else existing + conversation
    }
}
```

`createDiscussion` folds the returned `Conversation` into `projection` **after** the reply, so
`observeConversations` re-emits to include it without waiting on a server-pushed `conversations` snapshot
(AC #2 — deterministic and self-contained). It is an **upsert by `id`** (not blind append), so it's
idempotent against a re-delivered create and retry-merges with a concurrent authoritative snapshot. The
`conversations`-snapshot collector arm keeps its blind full-replace unchanged: because #316 and #318 fill
the **identical** four list-tier placeholders (`currentSessionId=""`, `sessionHistory=emptyList()`,
`isSleeping=false`, `archived=false`), the folded `Conversation` is field-equal to the same conversation
mapped later from a snapshot, so `StateFlow` conflation suppresses a redundant re-emit.
[#348](../codebase/348.md) (`promote`) **reuses this fold verbatim** — its second call site, where an
upsert replaces the existing *unpromoted* discussion entry in place with the promoted one. The second
consumer confirms the helper is right-shaped: no abstraction was extracted (the #347 open question is
resolved, not deferred).

> **Confirmed-insert is the trust property (mirrors #346).** A conversation is folded into the read
> projection **only** on a server `conversation_created` success reply, never speculatively and never on
> a failure path — so the list never shows a conversation the server did not create, by construction
> (the fold is the last step, after both `sendAndAwaitReply` *and* the decode succeed; no rollback path
> to get wrong). `mapError` is reused as-is — its `conversation.not_found` → `IllegalArgumentException`
> branch is **not exercised** here, since create references no existing conversation.

## `promote(conversationId, name, workspace)` — the third mutation (#348)

Promotes an existing (scratch) discussion into a named, persistent channel over v2
`promote_conversation`, and returns the now-promoted `Conversation`. [#348](../codebase/348.md) is
`createDiscussion` with **three deltas** — otherwise byte-for-byte the same build-request →
`sendAndAwaitReply` → decode-reply → confirmed-fold → return shape — and is almost pure composition:
it **reuses** #346's `sendAndAwaitReply` + `mapError`, #318's `ConversationResponseDto.toConversation()`,
and #347's `upsertConversation`, adding only a new `promote_conversation` request encoder
(`PromoteConversationPayloadDto`) and one `cwd`-resolution line.

The flow (≤ ~10 lines):

```kotlin
override suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation {
    // Null workspace ("promote in place") resolves to the conversation's existing cwd from the read
    // projection — the remote analog of the fake's `workspace ?: record.conversation.cwd`.
    val cwd = workspace ?: projection.value?.firstOrNull { it.id == conversationId }?.cwd ?: ""
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = "promote_conversation", ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            PromoteConversationPayloadDto(conversationId = conversationId, name = name, cwd = cwd),
        ),
    )
    val reply = sendAndAwaitReply(request)              // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    upsertConversation(conversation)                    // confirmed-upsert — ONLY after a successful decode (AC #2/#3)
    return conversation
}
```

- **Delta 1 — three required wire fields.** The request encoder
  `PromoteConversationPayloadDto(conversationId, name, cwd)` carries all three as **non-null** `String`s
  (contrast `CreateConversationPayloadDto`'s optional `cwd`) — there is no `explicitNulls` elision to
  reason about; the encoded payload always has `conversation_id`, `name`, `cwd`. See the
  [wire-layer doc](mobile-protocol-v2-wire-layer.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
- **Delta 2 — the reply is `conversation_updated`** (routed into the **same** success arm as
  `conversation_created`), decoded through the **same** #318 `ConversationResponseDto` (one DTO models
  both response types). A malformed reply throws the #318 decode exception in the **caller's** coroutine
  **before** `upsertConversation` runs, so the projection is never mutated by a garbage success reply
  (AC #3, "no partial promote") — and never inside the lone inbound collector.
- **Delta 3 — `cwd` resolution.** The domain `workspace` arg is nullable but the wire `cwd` is required.
  A **non-null** `workspace` (the `DEDICATED` choice) is sent verbatim; a **null** `workspace` (the
  `SCRATCH` choice / discussion-list default — both production callers reach this) means "promote in
  place" and resolves to the conversation's existing `cwd`, read from the `projection.value` snapshot.
  The `?: ""` final fallback is only reachable when `workspace` is null *and* the conversation is absent
  from the projection — **not reachable from the shipped UI** (it only promotes a visible, hence loaded,
  discussion); no throw is added for that should-not-happen state (Evidence-Based Fix Selection). The
  **returned** `cwd` always comes from the reply (server-authoritative), never the resolved request value
  (AC #1) — identical to #347's "return the reply's cwd, not the input."

The `upsertConversation` upsert **replaces the existing unpromoted discussion entry in place** (same
`id`), so `observeConversations` re-emits with the conversation now in the **Channels** tier and gone from
**Discussions** (AC #2) — list count unchanged, no duplicate.

> **`conversation.not_found` is meaningful here — the AC-#3 branch #347 could not exercise.** Unlike
> `createDiscussion`, `promote` references an **existing** conversation, so promoting an unknown id is a
> real server error. `mapError` is reused **unchanged** — its `conversation.not_found` →
> `IllegalArgumentException` branch (vs. `RelayErrorException` for any other code) is driven end-to-end by
> #348's tests for the first time. The confirmed-upsert is the trust property (mirrors #346/#347): the
> list never shows a promote the server did not perform, by construction.

## `registerPushToken(token)` — the device-concern push registration (#359)

Registers the phone's FCM push token with the paired daemon over v2 `register_push_token`, so the daemon
knows where to send a wake notification when the phone is backgrounded. [#359](../codebase/359.md)
implements it as a **pure request/reply** that reuses #346's correlation primitive and `mapError`
**verbatim** — it adds no `onInbound` branch (the `ack`/`error` arms already complete the pending
deferred) and no new error mapping.

**Two load-bearing departures from the three #314 mutations:**

1. **It is NOT a `ConversationRepository` interface method.** Push-token registration is a
   device/connection concern, not a conversation operation, so `registerPushToken` is a public method on
   the **concrete** class only — never an interface override. Per [[post-352-connection-scoped-repo-behind-facade]]
   the repo is connection-scoped behind the process-lifetime
   [`StableConversationRepository`](stable-conversation-repository.md) facade ViewModels hold, and that
   facade only delegates the `ConversationRepository` interface — so a non-interface method **deliberately
   will not reach consumers through the facade**. The live caller therefore holds the **concrete** handle:
   [#365](../codebase/365.md)'s connect-time hook in the coordinator (which retains the concrete
   `RemoteConversationRepository` it constructs) calls this directly — no facade exposure, new interface
   method, or DI reachability was added.
2. **It mutates no projection.** Unlike `sendMessage` / `createDiscussion` / `promote`, this registers a
   token and produces **no domain object** — the success signal is simply "the call returned without
   throwing". `projection` / `lastMessages` / `messagesByConversation` are untouched, so (unlike #346) no
   projection KDoc needed amending.

The flow (the entire method, ≤ ~12 lines):

```kotlin
suspend fun registerPushToken(token: String) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = "register_push_token", ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RegisterPushTokenPayloadDto(platform = "fcm", token = token, deviceName = deviceName)),
    )
    sendAndAwaitReply(request)   // throws on server `error` / not-Open; the empty {} ack carries nothing → ignored
}
```

- **`RegisterPushTokenPayloadDto`** is the fifth encode-only request DTO (`{platform, token, device_name}`,
  all required) — see the [wire-layer doc](mobile-protocol-v2-wire-layer.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
  `platform` is the constant `"fcm"`; `device_name` is the connection-level constructor `deviceName`.
- **`sendAndAwaitReply` does all the work, unchanged:** it throws `IllegalStateException` when `pump.send`
  returns `false` (session not Open), suspends until the correlated reply lands, returns normally on the
  empty `ack`, and rethrows the collector's exceptional completion on `error` (a `RelayErrorException`
  carrying `code`/`retryable` via the unchanged `mapError` — `server.binary_busy` retryable /
  `auth.invalid_token` not, so a caller can branch on `retryable`). The returned `{}` ack payload is
  ignored — there is nothing to decode and no projection to fold.
- **No client-side dedupe** — the server dedupes the `(platform, token, device_name)` triple, so this just
  sends. **Never logs the `token`** (the #346 no-secrets posture).

> **Device-name plumbing — the cross-slice handoff #359 deferred, closed by [#365](../codebase/365.md).**
> `device_name` must equal `NoiseClientInfo.deviceName`. #359 threaded it as the **last, defaulted**
> constructor param so that slice touched neither `AppModule` nor the
> [coordinator](relay-repository-coordinator.md) (no #352 conflict), leaving `""` as a placeholder with no
> live caller. #365 then added the live caller and threaded the real value: a `deviceName` param on
> `RelayRepositoryCoordinator` supplied from `NoiseClientInfo` in `AppModule`, passed through to this
> constructor. So `""` is **no longer the production value** — a live caller wired *without* that threading
> would have sent `device_name: ""`, polluting the server's `(platform, token, device_name)` dedup triple
> (pyrycode #319 acks with no registry touch only on a matching triple). The capability now stays dormant
> only until Firebase #361 *stores* a token for the hook to read.
>
> _(The #359 `deviceName` param KDoc at `RemoteConversationRepository.kt:72-76` still describes this as
> the Firebase sibling's pending handoff — a known-stale comment #365's code review flagged as an optional
> NIT and deferred, since the file is outside that PR's surface.)_

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
  table](#the-repository--one-projection-cold-fan-out) — it was previously an `else -> Unit` no-op, so
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

## Stubs — the full interface compiles; later slices replace what they own

Every method other than the three live read paths and the now-live `sendMessage` (#346) /
`createDiscussion` (#347) / `promote` ([#348](../codebase/348.md) — the last #314 mutation) throws
`UnsupportedOperationException` with a message naming the owning follow-up, so the class compiles the full
interface today and each slice replaces only the methods it owns:

| Method(s) | Owner |
|---|---|
| `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace` | follow-up (no v2 wire message defined yet) |

`delete`, `recentWorkspaces`, and `createWorkspaceFolder` are **not overridden** — they have interface
defaults (error / empty flow per the [contract](conversation-repository.md)) and are intentionally outside
this implementation's surface. All three read paths are now **cold flows that defer work to collection**
(the eager expression-body `throw` shape #312's NIT flagged is gone with the last read stub).

## State & concurrency model

- **Three `StateFlow` projections — `projection` (the conversation list, #312), `lastMessages` (#329's
  per-conversation most-recent `Message`), and `messagesByConversation` (#313's per-conversation ordered
  thread) — fed by one inbound collector** launched on the injected connection `scope`. No second
  collector or scope is added per slice; a single `message` envelope can update **two** projections
  (`lastMessages` + `messagesByConversation`). The scope (and thus the collector) is cancelled by its
  owner — the [#351 coordinator](relay-repository-coordinator.md) — when the connection ends; the pump completing `inbound` on teardown also ends the
  collector naturally. All projections are in-memory and connection-scoped — lost on process death and
  re-derived from the live stream (+ a re-`backfill_since`) on reconnect.
- **Two-or-more writers per projection, still data-safe (#346 / #347 / #348).** The mutations relaxed
  each projection from single-writer to **collector + confirmed fold(s)**: `sendMessage` (#346) is the
  second writer of `lastMessages` and `messagesByConversation`; `createDiscussion` (#347) and `promote`
  ([#348](../codebase/348.md)) both write the list `projection` via `upsertConversation`. Data-safety
  holds in every case: each write goes through an atomic `MutableStateFlow.update {}` (CAS) over a **pure**
  fold (`recordLastMessage`'s strictly-greater rule / `appendMessages`'s id-dedup / `upsertConversation`'s
  id-upsert), so concurrent writes from the caller coroutines retry-merge rather than clobber. For
  `projection` the collector's full-replace stays authoritative and convergent; the only race — a stale
  in-flight snapshot landing after the fold and transiently dropping/reverting the row — is harmless (the
  server's post-mutation snapshots include it) and is accepted under Evidence-Based Fix Selection.
  `promote` additionally **reads** `projection.value` (a lock-free snapshot) to resolve the cwd; the
  read-then-upsert pair is intentionally **not** atomic-as-a-pair — the resolved cwd is request data, not
  a guarded invariant, so a concurrent snapshot landing between only changes which authoritative cwd the
  request carries (benign — no TOCTOU of consequence). The KDoc on every affected field was updated to
  name its writers (and, for `projection`, `promote`'s read).
- **The `pendingRequests` registry (#346)** (`ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>`)
  is the only other shared mutable state: an in-flight mutation request registers a deferred keyed by its
  envelope id, the collector completes it on the correlated `ack`/`error`, and the awaiting caller
  removes its own entry in a `finally`. Bounded by caller concurrency (one entry per in-flight send,
  removed on success/error/cancellation) — no unbounded growth. The one documented gap: a
  connection-drop mid-await leaves a single stranded entry until the *caller* is cancelled (no timeout
  added; see [Hand-off](#hand-off--the-live-binding)).
- **Dispatcher inherited from the injected scope** (DI uses `Dispatchers.Default`; this is pure CPU/JSON
  work — the socket I/O is the transport's, below the pump). Not hard-coded.
- `observeConversations`, `observeLastMessage`, and `observeMessages` are cold; N concurrent collectors
  share the projections (fan-out off the single inbound consumer).

## Error handling

| Failure mode | Result |
|---|---|
| Malformed `conversations` payload | `IllegalArgumentException` caught per-envelope (covers both #316 families — `MissingFieldException` ⊂ `SerializationException`, and the kotlinx-datetime bad-timestamp throw); envelope **dropped**; collector survives; projection unchanged |
| Malformed `message` payload (missing field / unmappable role e.g. `system` / bad `ts`) | `IllegalArgumentException` caught per-envelope (covers the #317 `SerializationException` decode failure and the `Instant.parse(ts)` throw); envelope **dropped silently** (no payload logged — content may be sensitive); collector survives; `lastMessages` **and** `messagesByConversation` unchanged ([#329](../codebase/329.md) / [#313](../codebase/313.md)) |
| Malformed `message_chunk` (any one row bad) | the **whole chunk** dropped in one `catch (IllegalArgumentException)` (decode + map-all under one `try`); collector survives; thread unchanged ([#313](../codebase/313.md)) |
| `pump.send` returns `false` (session not `Open`) | request (`list_conversations` or `backfill_since`) silently not sent (no throw); the projection stays empty until a later subscribe succeeds or a push arrives — the live stream still fills the thread, and the next subscribe re-issues |
| `pump.inbound` completes (teardown) | collector completes; last projections retained; live `StateFlow` collectors simply stop receiving updates (do not complete) |
| Unknown `Envelope.type` | no-op — `backfill_done` (informational) falls to the intentional `else`; `messages` (a never-defined type) stays ignored. **Unsolicited** single-row deltas (a `conversation_created`/`conversation_updated` with no `inReplyTo` match) are caught by the success arm and no-op there. A *correlated* `conversation_created` / `conversation_updated` is **not** a no-op — it routes through the success arm (#347 / #348) |
| `sendMessage` — server `error` `conversation.not_found` (#346) | `IllegalArgumentException` (fake parity); **no projection mutated** (the confirmed-insert runs only after a successful `ack`) |
| `sendMessage` — any other server `error` (#346) | `RelayErrorException(code, retryable, message)` — structured for ViewModel branching; no projection mutated |
| `sendMessage` — `pump.send` returns `false` (not `Open`, #346) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `sendMessage` — malformed/undecodable `error` payload (#346) | `mapError` falls back to a `RelayErrorException(error.malformed_reply)` so the waiter is unblocked and the lone collector survives; no projection mutated |
| `createDiscussion` — server `error` (#347) | `RelayErrorException(code, retryable, message)` via the shared `mapError`; **no projection mutated** (the confirmed-insert runs only after a successful decode). `conversation.not_found` is not meaningful for create and is not exercised |
| `createDiscussion` — `pump.send` returns `false` (not `Open`, #347) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `createDiscussion` — malformed `conversation_created` reply (#347) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `upsertConversation`, so **no projection mutated** (a garbage success reply cannot inject a partial conversation) |
| `promote` — server `error` `conversation.not_found` (#348) | `IllegalArgumentException` via the shared `mapError` — promoting an unknown conversation is **meaningful** here (unlike create), so this branch **is** exercised; **no projection mutated** (the confirmed-upsert runs only after a successful decode) |
| `promote` — any other server `error` (#348) | `RelayErrorException(code, retryable, message)` via `mapError`; no projection mutated |
| `promote` — `pump.send` returns `false` (not `Open`, #348) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `promote` — malformed `conversation_updated` reply (#348) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `upsertConversation`, so **no projection mutated** (no partial promote) |
| `requestScreenSnapshot` — server `error` `conversation.not_found` / any other / not-`Open` send / malformed `screen_snapshot` reply (#375) | `IllegalArgumentException` / `RelayErrorException` / `IllegalStateException` respectively via the shared `mapError` + `sendAndAwaitReply`'s `check`; a malformed reply throws the #374 `SerializationException` (⊂ `IllegalArgumentException`) **caller-side** after `sendAndAwaitReply` returns. A pure read — **nothing mutated** on any path; nothing logged |
| Stubbed method called | `UnsupportedOperationException` naming the owning follow-up |

**Why catch-and-drop:** the `ConversationRepository` flow type has no error channel and the Fake never
errors, so dropping is the only interface-consistent option. An uncaught decode throw would kill the
**single** inbound consumer, silently freezing **all** future conversation updates for the connection — a
severe failure against an untrusted (post-auth) server payload. The #316 mapper validates shape; the
repository keeps the consumer alive. Pre-`Open` send loss is **not** defended here (no buffering /
retry-on-`Open`): the [#351 coordinator](relay-repository-coordinator.md) wires the repository against
an `Open` pump and builds a fresh chain per reconnect, so a pre-`Open` re-request stays out of scope here
(a future concern if a lost first request is ever observed).

## Hand-off — the live binding

The downstream DI / connection-coordinator work, and where it landed:

1. ✅ **Make the concrete pump conform** — `NoiseSessionPump : ManagedSessionPump : SessionPump` (the four
   members already matched; `override` added). Landed in [#351](../codebase/351.md).
2. ✅ **Provide the connection-scoped `CoroutineScope`** the repository's inbound collector runs on — the
   [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) builds a fresh child scope per live
   connection and constructs the repository against it. Landed in [#351](../codebase/351.md).
3. ✅ **Flag-gate the Koin binding `ConversationRepository`** between `FakeConversationRepository` and the
   live-backed facade. Landed in [#350](../codebase/350.md) as the `conversationRepositoryModule` selector
   (`if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()`), gated by
   the build-time `BuildConfig.USE_RELAY_REPOSITORY` flag — **default OFF**, so the bound
   `ConversationRepository` is still the Fake until the production flip. Flipping ON additionally depends
   on the v2 mutations (#346/#347/#348) and the server gaps #336 (boundaries) / #337 (streaming).

Open hand-off items: **pre-`Open` request loss** (if a subscribe's `send` lands before the handshake
completes, the list stays empty until the next subscribe or a server push — the fix, if observed, is a
re-request on `PumpState.Open`; #351 builds a fresh chain per reconnect but adds no re-request);
**unsolicited `conversation_created`/`conversation_updated` delta-merge** (the *correlated*-reply case
landed with #347/#348, but a server-pushed single-row delta with no `inReplyTo` match is still a no-op —
the list refreshes on the next `conversations` snapshot; merging deltas live remains future work);
**`isSleeping`/session enrichment** in the list (arrives via the detail/message read paths, not here).

Two more hand-offs opened by the `sendMessage` slice ([#346](../codebase/346.md)):

- **ViewModel error surface (still a follow-up — *not* #350).** All three live mutations — `sendMessage`
  (#346), `createDiscussion` (#347), and `promote` ([#348](../codebase/348.md)) — now throw
  `RelayErrorException` / `IllegalStateException` (not just `IllegalArgumentException`). The UI call sites
  (e.g. `ThreadViewModel.sendMessage`, `DiscussionListViewModel.confirmPromotion`) are currently
  fire-and-forget with no `try/catch` — harmless under the fake, but once the live remote is bound those
  exceptions would escape uncaught. **[#350](../codebase/350.md) did *not* address this** — it was the
  binding-selector slice only, ships with the flag OFF, and touched no ViewModel. Widening the ViewModel
  error handling (and documenting the widened exception set on the `ConversationRepository` interface
  KDoc) belongs to the flag-ON production flip, which remains a future follow-up.
- **Connection-drop-mid-send leak.** If the connection scope is cancelled while a caller still awaits a
  reply, the deferred never completes and the suspend hangs until the *caller* is cancelled (the
  ViewModel scope on screen exit). No timeout is added (no observed hang; a timeout value is a product
  call). A future slice — or the [#351 connection coordinator](relay-repository-coordinator.md), which
  already cancels the connection scope on drop — may fail all `pendingRequests` on disconnect.

## Testing

JVM unit only (`app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt`, `./gradlew test`),
JUnit4 + `runTest` + a hand-written **fake `SessionPump`** — `inbound` backed by a
`Channel<Envelope>(UNLIMITED).receiveAsFlow()` (so test pushes are not lost before the collector attaches);
`send` records each envelope and returns `true`; a `push` helper feeds inbound. The repository is
constructed with `backgroundScope` so its collector auto-cancels at test end. `conversations` payloads are
built from the same object-wrapped-array fixture shape as `ConversationsPayloadTest` via
`MobileJson.parseToJsonElement(raw)`.

> **Test idiom (reusable across the sibling slices #313/#314/#329):** drive the push→demux→project→emit
> cascade with **`runCurrent()`, not `advanceUntilIdle()`**. With a `Channel.receiveAsFlow()` inbound
> feeding a single repository-internal collector on `backgroundScope`, `advanceUntilIdle()` does not
> deliver the buffered channel item to the background collector (there are no timers to elapse), leaving
> projection-dependent assertions empty; `runCurrent()` drains the whole current-time cascade
> deterministically. See [[remote-repo-test-runcurrent-not-advanceuntilidle]] and
> [`codebase/312.md`](../codebase/312.md) § Lessons learned.

## Related

- Contract + Phase 1 binding: [Conversation repository](conversation-repository.md)
  (`ConversationRepository` interface, `ConversationFilter`, `ThreadItem`; the in-memory
  `FakeConversationRepository` this is the live counterpart to).
- Consumes: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) — the `inbound` /
  `send` surface, over the `SessionPump` interface. [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md)
  — `Envelope`, `MobileJson`, and the [#316](../codebase/316.md) `ConversationsPayload.toConversations()`
  decode-and-validate boundary. [Data model](data-model.md) — the domain `Conversation` it produces.
- Precedent: [Connection state](connection-state.md) — the consumer-defined-interface-in-`data/repository/`
  pattern `SessionPump` follows.
- Ticket notes: [`../codebase/312.md`](../codebase/312.md) (the list read path) ·
  [`../codebase/329.md`](../codebase/329.md) (the last-message preview) ·
  [`../codebase/313.md`](../codebase/313.md) (the thread read + backfill) ·
  [`../codebase/346.md`](../codebase/346.md) (`sendMessage` + the `ack`/`error` correlation primitive) ·
  [`../codebase/347.md`](../codebase/347.md) (`createDiscussion` + the `upsertConversation` confirmed-insert) ·
  [`../codebase/348.md`](../codebase/348.md) (`promote` + the `cwd`-resolution decision) ·
  [`../codebase/359.md`](../codebase/359.md) (`registerPushToken` — the first non-interface device-concern
  method + the deferred `deviceName` handoff) — files/line refs, patterns, lessons, verification.
- Specs: `docs/specs/architecture/312-remote-conversation-repository-observe-list.md` ·
  `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` ·
  `docs/specs/architecture/313-remote-observe-messages.md` ·
  `docs/specs/architecture/346-remote-send-message.md` ·
  `docs/specs/architecture/347-remote-create-discussion.md` ·
  `docs/specs/architecture/348-remote-promote.md` ·
  `docs/specs/architecture/359-register-push-token-wire-sender.md`.
- Siblings (extend the same class + `onInbound` `when`): [#329](../codebase/329.md)
  (`observeLastMessage`, **landed** — consumes [#317](../codebase/317.md), rides the live `message`
  stream), [#313](../codebase/313.md) (`observeMessages`, **landed** — consumes #317 + adds the
  `backfill_since` → `message_chunk` thread plumbing; boundaries #336 / streaming #337 de-scoped, both
  blocked on server-side v2 protocol additions), [#346](../codebase/346.md) (`sendMessage`, **landed** —
  the first mutation; added the shared `ack`/`error` correlation primitive), [#347](../codebase/347.md)
  (`createDiscussion`, **landed** — the second mutation; consumes [#318](../codebase/318.md)'s
  `ConversationResponseDto.toConversation()`, reuses #346's correlation primitive, and adds the
  `upsertConversation` confirmed-insert into the list projection), [#348](../codebase/348.md)
  (`promote`, **landed** — the last #314 mutation; consumes #318's `ConversationResponseDto`, reuses
  #346's primitive + #347's `upsertConversation` fold verbatim, adds the `promote_conversation` request
  encoder + the null-`workspace` `cwd` resolution), [#359](../codebase/359.md) (`registerPushToken`,
  **landed** — the **first non-interface** device-concern method; reuses #346's `sendAndAwaitReply` +
  `mapError` verbatim with **no** `onInbound` branch and **no** projection mutation, adds the
  `register_push_token` request encoder + the last/defaulted `deviceName` ctor param), [#365](../codebase/365.md)
  (`registerPushToken`'s **first live caller**, **landed** — the coordinator's connect-time hook re-sends it
  once per connection and threads the live `deviceName`, closing #359's `device_name: ""` defer; still
  dormant until Firebase #361 stores a token).
- Connection wiring: [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
  ([#351](../codebase/351.md), **landed**) — constructs this repository per live connection against the
  pump + a child scope, made `NoiseSessionPump : ManagedSessionPump : SessionPump`, and publishes the
  live instance on `currentRepository` (consumed by the **#352** facade). The flag-gated Koin binding
  selector that picks the Fake or the facade is **[#350](../codebase/350.md)** (landed; default OFF → Fake).
</content>
