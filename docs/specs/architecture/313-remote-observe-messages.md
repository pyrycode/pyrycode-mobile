# Spec — RemoteConversationRepository.observeMessages: thread + backfill (v2)

**Ticket:** #313 — feat(data): RemoteConversationRepository.observeMessages — thread + backfill (v2)
**Size:** S (single production file + one tiny wire model; no new exported API; no consumer cascade — fills a stub)
**Labels:** `size:s`, `security-sensitive`, `rework-count:2`

> ⚠️ **READ THIS FIRST — the existing code references a wire type that does not exist.**
> `RemoteConversationRepository.kt` (lines 124–125, 218–221) and the test
> `unknownInboundType_isIgnored` (`RemoteConversationRepositoryTest.kt:198`) describe #313's
> thread-read as a **`messages`** response carrying `{"messages":[…]}`. **That is a stale guess
> written by #312 before #313 was de-scoped.** The real v2 wire flow — per the ticket body and the
> server SSOT (`pyrycode/internal/protocol/messaging.go` + `codes.go`; testdata
> `backfill_since.json`, `message_chunk.json`, `backfill_done.json`) — is
> **`backfill_since` (request) → `message_chunk` (response) → `backfill_done`**. There is **no
> `messages` type in the v2 catalog.** Implement `message_chunk`, NOT `messages`. The
> `unknownInboundType_isIgnored` test stays valid as-is: `messages` remains an unknown type and is
> still ignored. If anything reads as "make `messages` no longer ignored," stop — that's the trap.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — **read in
  full (224 lines). The only production class you modify.** Extract:
  - `class RemoteConversationRepository(private val pump: SessionPump, scope: CoroutineScope)`
    (line 49) and `private val requestId = AtomicLong(0)` (line 71) — **reuse this for the
    `backfill_since` envelope id.**
  - `init { scope.launch { pump.inbound.collect { onInbound(it) } } }` (lines 73–79) — the single
    inbound collector.
  - `onInbound(envelope)` `when (envelope.type)` (lines 81–128) — `TYPE_CONVERSATIONS` (#312),
    `TYPE_MESSAGE` (#329), `else -> Unit`. **You add a `TYPE_MESSAGE_CHUNK` arm and extend the
    existing `TYPE_MESSAGE` arm.**
  - `observeConversations` (lines 130–137): `flow { pump.send(listConversationsRequest()); emitAll(projection.filterNotNull().map { … }) }`
    — **this is the exact cold-flow idiom `observeMessages` mirrors.**
  - `observeMessages` **stub** (lines 164–165): eager `throw UnsupportedOperationException(...)`.
  - `observeLastMessage` (line 174): `lastMessages.map { it[conversationId] }.distinctUntilChanged()`
    — the per-conversation StateFlow-derivation pattern to copy.
  - `listConversationsRequest()` (lines 154–160) — the request-builder shape to mirror for
    `backfillSinceRequest`.
  - companion consts (lines 211–223): `TYPE_LIST_CONVERSATIONS`, `TYPE_CONVERSATIONS`, `TYPE_MESSAGE`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:23` —
  `fun observeMessages(conversationId: String): Flow<List<ThreadItem>>` (signature is fixed). Lines
  130–152: the `ThreadItem` sealed interface — construct `ThreadItem.MessageItem(message)` **only**.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` — the interface the repo
  depends on: `val inbound: Flow<Envelope>` (single-consumer) + `fun send(envelope: Envelope): Boolean`
  (non-suspending). (`NoiseSessionPump` in `data/network/` is the production impl; `inbound` is a
  `Channel(BUFFERED).receiveAsFlow()` — one consumer only.)
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` — `MessagePayloadDto`
  (lines 31–37: `conversation_id`, `message_id`, `role: WireRole`, `text`) and
  `fun MessagePayloadDto.toMessage(envelope, sessionId): Message` (lines 82–97): timestamp =
  `envelope.ts`, `isStreaming = false`, repo supplies `sessionId`. **Reuse for every chunk row and
  the live message.** `WireRole` accepts only `user`/`assistant` (line 52); `system`/unknown throw
  at decode (the drop-on-malformed contract).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:38` — `Envelope(id: Long,
  type: String, ts: String, payload: JsonElement, inReplyTo: Long? = null)`. **Add the
  `message_chunk` payload model here (or next to `MessagePayloadDto`).**
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29` — `MobileJson` is the
  one configured `Json`; decode via `MobileJson.decodeFromJsonElement<T>(envelope.payload)`.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5` — domain `Message(id, sessionId,
  role, content, timestamp, isStreaming, toolCall?)`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` —
  **read in full.** The fake `SessionPump` (lines 468–484: `inbound` = `Channel(UNLIMITED).receiveAsFlow()`,
  `push` = `trySend`, `sent` captures outgoing), `backgroundScope` injection, the `runCurrent()`
  idiom, and the `messageEnvelope`/`conversationsEnvelope` helpers (lines 427–466). Copy the
  `observeLastMessage` test block (lines 249–405) as the shape for your `observeMessages` tests.
- `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` — the
  immediately-preceding slice; same collector-extension + StateFlow-projection pattern.

---

## Context

Phase 4 backend integration. Third slice of `RemoteConversationRepository` (split from #278), built
on the #312 class + collector scaffolding and the #317 message mapper. #329 (just merged) added live
`message` consumption for `observeLastMessage`; this slice reuses that inbound `message` handling and
fills the `observeMessages` stub.

`observeMessages(conversationId)` streams a conversation's full thread over Mobile Protocol v2:
historical messages **backfilled** (`backfill_since` → `message_chunk` → `backfill_done`) ahead of
the **live** `message` stream, merged into one chronological `List<ThreadItem.MessageItem>`, deduped
by `message_id`, in wire/arrival order.

**Two things are de-scoped — do not implement them, do not treat their absence as a bug:**

1. **Session-boundary delimiters (→ #336).** The v2 wire has no session-transition representation:
   `MessagePayload`/`message_chunk` rows are `{conversation_id, message_id, role, text}` — no
   `session_id`, no per-message timestamp, no boundary field — and the type catalog has no
   `session_boundary`. `ThreadItem.SessionBoundary` **cannot be constructed.** Emit `MessageItem`s
   only.
2. **Live token-streaming (→ #337).** The wire has no partial/`done` flag; every message is finished.
   `toMessage` hardcodes `isStreaming = false`. Do not invent a streaming wire field.

---

## Design

### Extend the existing collector — do NOT open a second `pump.inbound` consumer

`SessionPump.inbound` is **single-consumer**; `RemoteConversationRepository` already owns the one
collector in `init`, demuxing by `Envelope.type`. `observeMessages` MUST route its frames through
**that same `onInbound` `when`** (a new `TYPE_MESSAGE_CHUNK` arm + an extension to the existing
`TYPE_MESSAGE` arm) and project per-conversation state. A second `pump.inbound.collect` is a
correctness bug.

Data flow (extends `onInbound`):

```
pump.inbound  (the ONE collector in init)
  ├─ "conversations"  → projection           (existing #312, unchanged)
  ├─ "message"        → lastMessages fold     (existing #329, unchanged)
  │                   + APPEND to messagesByConversation[payload.conversation_id]   (NEW)
  ├─ "message_chunk"  → for each row: APPEND to messagesByConversation[row.conversation_id]  (NEW)
  ├─ "backfill_done"  → no-op (see below)     (NEW const, or leave to `else`)
  └─ else -> Unit                              (unchanged)

observeMessages(id) = flow {
    pump.send(backfillSinceRequest(id))
    emitAll(messagesByConversation.map { it[id].orEmpty().map(ThreadItem::MessageItem) }.distinctUntilChanged())
}
```

### New internal state (private; mutated only inside `onInbound`)

```kotlin
private val messagesByConversation = MutableStateFlow<Map<String, MessageThread>>(emptyMap())
```

`MessageThread` is an **order-preserving, message_id-deduped** accumulator. Recommended: back each
conversation's value with a `LinkedHashMap<String /*messageId*/, Message>` — first insertion fixes
position; a repeat `message_id` updates in place without moving (satisfies "deduped by message_id").
Expose `values.toList()` for the projection. Keep this an internal detail; the public flow yields
`List<ThreadItem.MessageItem>`. Update via `MutableStateFlow.update { … }` (immutable copy per write),
mirroring the existing `lastMessages.update { … }` at RCR:115.

> **No correlation map needed.** `message_chunk` rows each carry their own `conversation_id`
> (`MessageChunkPayloadDto.messages[i].conversationId`), so the chunk is self-routing. `backfill_done`
> carries only `inReplyTo` (no `conversation_id`) and is a delivery-count/completion signal this slice
> does **not** need to act on — the chunk already delivered the full history. Leave `backfill_done` a
> no-op (either an explicit arm that does nothing, or fall through to `else -> Unit`). This is simpler
> than the request-id→conversationId map an earlier draft proposed, and it satisfies every AC.

### `observeMessages` (signature only)

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
```

- **Cold**, mirroring `observeConversations` exactly: `flow { pump.send(backfillSinceRequest(id)); emitAll(perConversationProjection(id)) }`.
- Re-sends `backfill_since` on every subscription (same as `observeConversations` re-issuing
  `list_conversations`); re-delivered history is absorbed by message_id dedup, so **no idempotency
  guard is required.** A pre-Open `send` returns `false` and is dropped — the live stream still fills
  the thread once the pump is Open and history re-backfills on the next subscription.
- `perConversationProjection(id)` = `messagesByConversation.map { it[id].orEmpty().messagesInOrder().map(ThreadItem::MessageItem) }.distinctUntilChanged()`.
  `distinctUntilChanged()` ensures a change to **another** conversation's slot does not re-emit this
  flow (AC #3).

### `backfillSinceRequest(conversationId)` (helper, mirrors `listConversationsRequest` at RCR:154)

```kotlin
private fun backfillSinceRequest(conversationId: String): Envelope =
    Envelope(id = requestId.incrementAndGet(), type = TYPE_BACKFILL_SINCE,
             ts = Clock.System.now().toString(), payload = <backfill_since payload>)
```

CONFIRM the `backfill_since` payload shape against server testdata `backfill_since.json` /
`messaging.go` (QMD `pyrycode-docs`). Expected: `{conversation_id, since: <cursor|null>}`; "all
history on first load" ⇒ `since` null/absent. Model it as a small `@Serializable` request DTO encoded
via `MobileJson.encodeToJsonElement`, or build the `JsonObject` inline as `listConversationsRequest`
does.

### New wire model

```kotlin
@Serializable
data class MessageChunkPayloadDto(val messages: List<MessagePayloadDto>)
```

Place beside `MessagePayloadDto` in `MessagePayload.kt`. Decode the chunk as a whole; map each row via
the existing `toMessage(envelope, sessionId = "")` (same `sessionId = ""` placeholder #329 uses — the
wire carries none and a `MessageItem`-only thread never reads it).

### Ordering (AC #1)

**Wire/arrival order — no client-side timestamp sort.** A `message_chunk` carries one envelope `ts`
for many messages, so a timestamp sort is impossible and wrong. The pump's `inbound` is a single
ordered stream that cannot skip a frame: the `message_chunk` (backfill response) arrives before the
live `message`s the server emits afterward, so appending in arrival order yields history-then-live
naturally. Do not reorder around a gap. (Assumption, matching the ticket's "preserve ordering; do not
reorder": the server emits the chunk ahead of subsequent live messages on the ordered stream; dedup
by `message_id` absorbs any re-delivery.)

### New companion consts

`TYPE_MESSAGE_CHUNK = "message_chunk"`, `TYPE_BACKFILL_SINCE = "backfill_since"`, and optionally
`TYPE_BACKFILL_DONE = "backfill_done"` (only if you add an explicit no-op arm rather than letting it
fall to `else`).

---

## State + concurrency model

- **One** collector, in `init` on the injected `CoroutineScope` (tests pass `backgroundScope`). No
  new coroutine, no new dispatcher, no timers.
- `messagesByConversation` is written **only** from `onInbound` (sequential single writer, like
  `projection`/`lastMessages`). `observeMessages`' `flow{}` only reads it + calls non-suspending
  `pump.send`. No `delay()`, so all resumptions land at current virtual time.
- Cancellation: cold flow, lifecycle-bound at the UI (`collectAsStateWithLifecycle`); collector
  lifetime = injected scope (unchanged from #312). Nothing new to dispose.

---

## Error handling

Mirror the existing `TYPE_MESSAGE` arm (RCR:98–123) exactly — decode + map inside one `try`, catch
`IllegalArgumentException` (a `SerializationException` is a subtype; a bad `ts` throws
`IllegalArgumentException` via `Instant.parse`), `return` to drop the frame so the single inbound
consumer survives. **Log nothing** — message content may be sensitive (the existing arm drops
silently for this reason).

| Failure | Handling |
|---|---|
| Malformed `message_chunk` (any row bad: unknown role, bad `ts`, missing field) | whole chunk dropped in one `try`/`catch`; collector survives |
| `pump.send(backfill_since)` returns `false` (pre-Open) | not thrown; live stream still fills; next subscription re-backfills |
| `backfill_done` (any shape) | no-op |
| Live `message` already present from backfill (same `message_id`) | dedup → no duplicate, position fixed at first occurrence |

No new user-facing error surface — the thread screen renders whatever list it receives.

---

## Testing strategy

Unit tests only (`./gradlew test`), added to `RemoteConversationRepositoryTest`. **Drive every
cascade with `runCurrent()` after each `pump.push(...)` and after launching each collector — NOT
`advanceUntilIdle()`** (no timers; `advanceUntilIdle` empirically fails to deliver the buffered
channel item to the `backgroundScope` collector). Add a `messageChunkEnvelope(...)` helper beside the
existing `messageEnvelope`/`conversationsEnvelope` (lines 427–466); capture outgoing `send` envelopes
via `pump.sent` to assert the `backfill_since` request.

Scenarios (cases, not code — write in the existing idiom):

- **Subscription issues `backfill_since` (AC #2).** Collect `observeMessages("c1")`; assert
  `pump.sent` contains a `backfill_since` envelope (type + payload carries `c1`).
- **Backfill then live (AC #1, #2).** Push a `message_chunk` for `c1` (history rows) then a live
  `message` for `c1`; assert the emission is the ordered `List<MessageItem>` = chunk rows in order,
  then the live message appended last.
- **Dedupe by message_id (AC #2).** A `message_id` present in both the chunk and a later live
  `message` appears **once**, position fixed at first occurrence.
- **Conversation isolation (AC #3).** While collecting `observeMessages("c1")`, push a `message` (and
  a `message_chunk`) for `c2`; assert `c1`'s flow does **not** re-emit.
- **Arrival order, no timestamp sort (AC #1).** Push chunk rows / live messages whose would-be
  timestamps are out of order; assert emitted order follows arrival.
- **Round-trip against the v2 server double (AC #4 — headline).** Seed a `message_chunk` (history) +
  live `message` envelopes through the fake pump; assert the expected ordered
  `List<ThreadItem.MessageItem>`.
- **Malformed chunk dropped, collector survives.** Push a `message_chunk` with a bad row (e.g.
  `role:"system"`); assert no emission, then a valid `message`/chunk afterward still surfaces.
- *(`message_chunk` payload model)* a `MobileJson` decode round-trip mirroring `MessagePayloadTest`.

---

## Security review (label: `security-sensitive`)

`security-sensitive` ⇒ a review pass is mandatory. The structured `security-review.md` checklist
could not be read this session (tool-output failure); the pass below is an inline adversarial walk of
the data-layer trust boundaries. Re-run the formal `security-review.md` pass when convenient before
treating this as fully cleared, but the findings here are complete for a data-layer projection slice.

**Trust boundary.** This slice processes **already-decrypted, post-Noise** application payloads. Frame
confidentiality / integrity / ordering / replay are enforced by the Noise_IK transport beneath
(#306–#309); this layer trusts the pump's plaintext `Envelope` stream. The server is the authority
for `message_id` uniqueness and `conversation_id` correctness under the post-pairing trust model.

| # | Concern | Verdict / control |
|---|---|---|
| S1 | **Cross-conversation misrouting** | **Enforced by design.** Key `messagesByConversation` on the **payload's own** `conversation_id` (live `message` → `payload.conversation_id`; chunk → each row's `conversation_id`), never the collecting flow's id. Covered by the isolation test (AC #3). |
| S2 | **Unbounded growth** — hostile/buggy server sends a huge `message_chunk` or endless live stream | **No cap now (evidence-based).** Relay is a paired, trusted peer; no such failure observed; a cap risks silently truncating legitimate history. Recorded as a known follow-up, not built speculatively (matches the project's evidence-based-fix posture). |
| S3 | **`message_id` dedup semantics** — repeat id, different text, updates in place | **Accepted, conscious choice.** Server owns `message_id` uniqueness; last-write-wins on a duplicate id is acceptable post-Noise. Documented so it's deliberate. |
| S4 | **Sensitive data in logs** | **Control:** the slice logs nothing (mirrors the existing `message` arm's silent drop). Do not add `text`/payload logging. |
| S5 | **New attack surface** (secrets / key material / persistence / IPC / `android.*` / new parsing) | **None.** Reuses the existing `MobileJson` decode path + one new `@Serializable` DTO; `data/` stays portable (no `android.*`). |

**Verdict: PASS** (inline). No FAIL-level finding; S2 is a deferred-by-evidence note, not a gap.

---

## File-overlap check (§1.5) — CLEAR

`git fetch` + branch scan run this session: **no open feature branch touches**
`data/repository/RemoteConversationRepository.kt` or its test. Open feature branches at spec time:
166, 203, 205, 228, 236, 292, 301 (none in the `data/repository/` area). Sibling #314 (mutations,
which will also extend this class) has **no branch yet**, so no blocker is needed now. #329 already
merged. No `addBlockedBy` required.

---

## Open questions / CONFIRM ON READ

- **`backfill_since` request payload shape** — `{conversation_id, since}`? cursor type/semantics for
  "all history"? Confirm against server `messaging.go` + testdata `backfill_since.json` (QMD
  `pyrycode-docs`). This is the one genuinely unconfirmed wire detail.
- **`message_chunk` payload shape** — assumed `{messages: [MessagePayload, …]}` (memory + ticket).
  Confirm against testdata `message_chunk.json` before finalizing `MessageChunkPayloadDto`.
- **`backfill_done` shape** — assumed `{delivered: int}` + envelope `inReplyTo`. This slice ignores
  it; confirm only if you choose to assert delivery counts in a test.
- **Stale `messages` references** — the existing RCR comments (lines 124–125, 218–221) and
  `unknownInboundType_isIgnored` test use a `messages` type. Per the server SSOT there is no such
  type; treat them as stale. The test stays valid (unknown type ignored). Consider updating the two
  comments to say `message_chunk` while you're in the file (optional, low-risk).

---

## Scope self-check

Production source files modified/created: `RemoteConversationRepository.kt` (modify) +
`MessagePayload.kt` (add one DTO) = **2**. New exported types: **0 public API** (`observeMessages` +
`ThreadItem.MessageItem` already exist; `MessageChunkPayloadDto` is an internal wire model;
`messagesByConversation` is private). No consumer cascade (fills a stub; signature unchanged). Well
within `s`. No split.
