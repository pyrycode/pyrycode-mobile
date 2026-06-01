# Remote conversation repository — the Phase 4 `ConversationRepository`

The **live, server-backed implementation** of the [`ConversationRepository`](conversation-repository.md)
contract — the Phase 4 counterpart to the in-memory `FakeConversationRepository`. It reads real server
state over the [Mobile Protocol v2](mobile-protocol-v2-wire-layer.md) Noise session instead of an
in-process seed store, and is swapped in for the Fake via a Koin module (per CLAUDE.md: the Phase 4
backend swap is architectural — replace the binding, don't special-case the UI).

Package: `de.pyryco.mobile.data.repository` (`RemoteConversationRepository` + the consumer-defined
`SessionPump` interface), same package as the contract and the Fake. Built **slice by slice**: the
conversation-list read path landed in [#312](../codebase/312.md), the last-message preview in
[#329](../codebase/329.md), and the thread read path in [#313](../codebase/313.md); the mutation path
(#314) extends the **same class** as it lands. Portable, `android.*`-free.

> **Ships dormant (no live binding yet).** #312 lands `RemoteConversationRepository` with **no Koin
> binding and no consumers** — the UI still binds to `FakeConversationRepository`. Wiring the live binding
> (and making the concrete pump satisfy `SessionPump`) is the DI/connection-coordinator slice's job
> ([#279](https://github.com/pyrycode/pyrycode-mobile/issues/279) / [#302](../codebase/302.md)), gated on
> paired state — see [Hand-off](#hand-off--the-live-binding-279--302). Adding a binding before then would
> be dead code.

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
    scope: CoroutineScope,   // connection-scoped (DI, #279/#302); tests pass runTest's backgroundScope
) : ConversationRepository
```

**Single source of state.** One `private val projection = MutableStateFlow<List<Conversation>?>(null)`
(`null` = list not yet loaded). It is the demuxed projection of the pump's inbound stream; every cold read
derives from it. No parallel mutable state.

**Single inbound consumer (the fan-out owner).** Because `pump.inbound` is hot and single-consumer, the
repository launches **exactly one** long-lived collector in `init` on the injected `scope`. That collector
demultiplexes each envelope by `Envelope.type`:

| `Envelope.type` | Handling |
|---|---|
| `"conversations"` | Decode `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload).toConversations()` (#316) → assign to `projection`. A **full-list snapshot** — both the reply to our request and any unsolicited server change-push arrive this way, so re-emission needs **no `in_reply_to` correlation**. Decode is wrapped in a per-envelope `try/catch` (a malformed snapshot is dropped, the collector survives). |
| `"message"` | Decode `MobileJson.decodeFromJsonElement<MessagePayloadDto>(payload)` (#317) → key by `conversation_id` (**read off the DTO before mapping** — the domain `Message` carries none) → `toMessage(envelope, sessionId = "")`, then **two folds** off the one decoded DTO: (a) the strictly-greater-by-`timestamp` `lastMessages` preview fold ([#329](../codebase/329.md)); (b) an arrival-order append into the `messagesByConversation` thread ([#313](../codebase/313.md)). Same per-envelope `try/catch` drop; **silent** (no payload logged, content may be sensitive). The singular live/echo `message`. See [`observeLastMessage`](#observelastmessageconversationid--the-live-last-message-preview-329) and [`observeMessages`](#observemessagesconversationid--the-live-thread-read-313) below. |
| `"message_chunk"` | The `backfill_since` **response** body ([#313](../codebase/313.md)): `MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(payload)` → map **each** row via the same `toMessage(envelope, sessionId = "")` (one envelope `ts` covers every row) → append the whole batch into `messagesByConversation` in one atomic `update`. Each row **self-routes** by its own `conversation_id` — no `in_reply_to` correlation. A single bad row drops the **whole chunk** in one `catch (IllegalArgumentException)`; silent. |
| anything else | **No-op** (intentional `else`, not a bug). `backfill_done` (`{delivered}`) needs no action — the `message_chunk` already delivered the history, the count is informational. Single-row `conversation_updated`/`conversation_created` deltas (#318 → #314) extend this `when` in their own slice. |

> **Why single-row deltas are not handled here.** `conversation_updated` / `conversation_created` are
> single-`Conversation` payloads mapped by [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s
> `ConversationResponseDto`, **not** #316's list mapper. Merging such a delta into the live list
> projection (so a promote/rename/archive made elsewhere re-emits without a full re-`list_conversations`)
> is owned by the mutation slice (#314), which depends on #318. This is exactly why the read path depends
> on **#316 only, not #318**. Until then, the production list refreshes on the next `conversations`
> snapshot (re-subscribe / reconnect).

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

## Stubs — the full interface compiles; later slices replace what they own

Every method other than the three live read paths — `observeConversations` (#312), `observeLastMessage`
(#329), and `observeMessages` (#313) — throws `UnsupportedOperationException` with a message naming the
owning follow-up, so the class compiles the full interface today and each slice replaces only the methods
it owns:

| Method(s) | Owner |
|---|---|
| `sendMessage`, `createDiscussion`, `promote` | #314 (mutation path) |
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
  owner (#279/#302) when the connection ends; the pump completing `inbound` on teardown also ends the
  collector naturally. All projections are in-memory and connection-scoped — lost on process death and
  re-derived from the live stream (+ a re-`backfill_since`) on reconnect.
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
| Unknown `Envelope.type` | no-op — `backfill_done` (informational) and single-row deltas (#314) fall here; `messages` (a never-defined type) also stays ignored |
| Stubbed method called | `UnsupportedOperationException` naming the owning follow-up |

**Why catch-and-drop:** the `ConversationRepository` flow type has no error channel and the Fake never
errors, so dropping is the only interface-consistent option. An uncaught decode throw would kill the
**single** inbound consumer, silently freezing **all** future conversation updates for the connection — a
severe failure against an untrusted (post-auth) server payload. The #316 mapper validates shape; the
repository keeps the consumer alive. Pre-`Open` send loss is **not** defended here (no buffering /
retry-on-`Open`): the coordinator wires the repository against an `Open` pump, and reconnect/re-request is
out of scope (#302).

## Hand-off — the live binding (#279 / #302)

The downstream DI / connection-coordinator slice must:

1. make the concrete pump conform — `class NoiseSessionPump(...) : SessionPump` + two `override`s (its
   members already match);
2. provide the connection-scoped `CoroutineScope` the repository's inbound collector runs on;
3. swap the Koin binding `ConversationRepository` from `FakeConversationRepository` to
   `RemoteConversationRepository` **when paired/connected** — gate the live binding on paired state per
   [[phase4-no-central-flag-gate-per-piece]] (there is no central Phase-4 flag).

Open hand-off items: **pre-`Open` request loss** (if a subscribe's `send` lands before the handshake
completes, the list stays empty until the next subscribe or a server push — the fix, if observed, is a
re-request on `PumpState.Open`, owned by #302); **`conversation_updated` delta-merge** (owned by the
#318-dependent #314); **`isSleeping`/session enrichment** in the list (arrives via the detail/message read
paths, not here).

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
  [`../codebase/313.md`](../codebase/313.md) (the thread read + backfill) — files/line refs, patterns,
  lessons, verification.
- Specs: `docs/specs/architecture/312-remote-conversation-repository-observe-list.md` ·
  `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` ·
  `docs/specs/architecture/313-remote-observe-messages.md`.
- Siblings (extend the same class + `onInbound` `when`): [#329](../codebase/329.md)
  (`observeLastMessage`, **landed** — consumes [#317](../codebase/317.md), rides the live `message`
  stream), [#313](../codebase/313.md) (`observeMessages`, **landed** — consumes #317 + adds the
  `backfill_since` → `message_chunk` thread plumbing; boundaries #336 / streaming #337 de-scoped, both
  blocked on server-side v2 protocol additions), #314 (mutations, consumes [#318](../codebase/318.md)).
- Hand-off: [#279](https://github.com/pyrycode/pyrycode-mobile/issues/279) / [#302](../codebase/302.md)
  (`NoiseSessionPump : SessionPump`, the connection scope, the paired-state Koin swap).
</content>
