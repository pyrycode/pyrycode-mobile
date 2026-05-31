# Spec — RemoteConversationRepository.observeMessages: thread + backfill (v2)

**Ticket:** #313 — feat(data): RemoteConversationRepository.observeMessages — thread + backfill (v2)
**Size:** S (sized down within S; single production file, no exported-type additions, no consumer cascade)
**Labels:** `size:s`, `security-sensitive` (see § Security considerations)

> **Authoring note (read this first).** This spec was authored during a session where the
> harness suppressed all tool *output* (Read/Bash returned empty, though the calls
> succeeded). File references below therefore use **symbol-level anchors** (class / function /
> field names) rather than line numbers — **open each file and confirm the anchor before
> editing.** The structural facts are grounded in the merged sibling slices (#312 class
> scaffolding, #317 message mapper, #329 observeLastMessage) and the project-memory notes
> cited inline. If any anchor does not match the current code, treat it as the open question it
> is and re-read before proceeding.

---

## Files to read first

Read these before writing code. Each is the contract this slice extends; the sibling slices
established the exact patterns to copy.

- `app/src/main/java/de/pyryco/mobile/data/network/RemoteConversationRepository.kt` — **read in
  full.** This is the only production file you modify. Extract: (1) the single `pump.inbound`
  collector launched in `init` on the injected `CoroutineScope`, and how it demuxes by
  `Envelope.type` into StateFlows (`projection` for the #312 conversation list, the
  last-message state for #329); (2) the `observeMessages(conversationId)` **stub** this slice
  fills; (3) the held `SessionPump` reference and how `send`/`inbound` are used; (4) the
  request-id generation pattern if #329 already established one.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — the
  `observeMessages` signature (do **not** change it) and the `ThreadItem` sealed hierarchy
  (`ThreadItem.MessageItem`, `ThreadItem.SessionBoundary`). This slice constructs
  `MessageItem` **only**. Also confirm the `Message` domain type fields.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` — confirm encode support
  for the outgoing `backfill_since` envelope and decode support for `message`, `message_chunk`,
  and `backfill_done`. #317 added `message` decode + `MessagePayloadDto.toMessage`; #329
  consumed `message`. **`message_chunk` + `backfill_done` decode may not exist yet** — if
  absent, adding the DTOs + decode arms is in scope for this slice (small; mirror the existing
  `message` arm).
- `app/src/main/java/de/pyryco/mobile/data/network/` (the `SessionPump` / `Envelope` defs, #309)
  — confirm: `inbound: Flow<Envelope>` is **single-consumer**; `fun send(envelope): Boolean`
  is **non-suspending**; `Envelope` carries `type`, `payload`, `ts`, `inReplyTo` (the
  correlation field), and an outgoing request-id mechanism.
- `app/src/test/java/de/pyryco/mobile/data/network/RemoteConversationRepositoryTest.kt` — **read
  in full.** Extract the fake `SessionPump` shape (`inbound` = `Channel(UNLIMITED).receiveAsFlow()`,
  `push` = `trySend`, and how outgoing `send` envelopes are captured), the `backgroundScope`
  injection, and the `runCurrent()` driving idiom. Your new tests extend this file.
- Project-memory (background, not code): the v2 wire carries **no** session-boundary data and
  **no** streaming signal — both are de-scoped here (see Context). The test-driver gotcha:
  drive the cascade with `runCurrent()`, **not** `advanceUntilIdle()`.

---

## Context

Phase 4 backend integration. Third slice of the `RemoteConversationRepository` work (split from
#278), built on the class + collector scaffolding from #312 and the message mapper from #317.

`observeMessages(conversationId)` currently a stub. This slice makes it stream a conversation's
full thread over Mobile Protocol v2: historical messages from past sessions **backfilled** ahead
of the **live** `message` stream, merged into one chronological `List<ThreadItem.MessageItem>`,
deduped by `message_id`.

**Two things are de-scoped — do not attempt them, and do not treat their absence as a bug:**

1. **Session-boundary delimiters (→ #336).** The v2 wire has no session-transition representation:
   `MessagePayload` / `MessageChunkPayload` are `{conversation_id, message_id, role, text}` — no
   `session_id`, no timestamp, no boundary/reason/cwd field — and the v2 app-message type set has
   no `session_boundary` type (server SSOT: `pyrycode/internal/protocol/messaging.go`,
   `codes.go`). `ThreadItem.SessionBoundary` **cannot be constructed from the wire.** This slice
   emits `MessageItem`s only.
2. **Live token-streaming (→ streaming follow-up).** The wire has no partial/`done` flag;
   `message` is one *finished* message, `message_chunk` is a batch of *finished* messages.
   `MessagePayloadDto.toMessage` (#317) hardcodes `isStreaming = false`. Do not invent a streaming
   wire field. Every mapped `MessageItem` is finished.

---

## Design

### Where the work goes — extend the existing collector, do NOT subscribe independently

This is the single most important design constraint, and the reason the earlier (removed) #313
spec was scrapped:

> `SessionPump.inbound` is **single-consumer**, and `RemoteConversationRepository` already owns
> the one collector in `init`. `observeMessages` MUST demux backfill/live frames **inside that
> existing collector** and project into per-conversation state. It must **NOT** open a second
> `pump.inbound.collect` (e.g. a cold `flow { pump.inbound.onStart{…}.collect{…} }`) — a second
> consumer of a single-consumer stream is a correctness bug.

So the data flow is:

```
pump.inbound (single collector in init)
   └─ when Envelope.type == "message"        → upsert into messagesByConversation[payload.conversation_id]
   └─ when Envelope.type == "message_chunk"  → upsert each row into messagesByConversation[row.conversation_id]
   └─ when Envelope.type == "backfill_done"  → resolve conversationId via pendingBackfill[envelope.inReplyTo]; mark complete
   └─ (existing) "conversations" → projection (#312);  "message" also feeds last-message state (#329)

observeMessages(id) = messagesByConversation flow for `id`, mapped to List<ThreadItem.MessageItem>,
                      with .onStart { requestBackfillOnce(id) }
```

### New internal state (all private to `RemoteConversationRepository`)

| State | Type | Purpose |
|---|---|---|
| per-conversation messages | `MutableStateFlow<Map<String, MessageThread>>` (or `MutableStateFlow<MessageThread>` per id, lazily created under a lock) | accumulates messages keyed by conversationId; the source the cold flow derives from |
| `pendingBackfill` | `MutableMap<String, String>` (requestId → conversationId) | correlates `backfill_done` (which carries **only** `inReplyTo`, no `conversation_id`) back to its conversation; guarded by the same dispatcher/single-threaded confinement the collector runs on |
| `backfillRequested` | `MutableSet<String>` (conversationIds) | idempotency guard so re-collection of `observeMessages(id)` does not re-issue `backfill_since` |

`MessageThread` is an ordering-preserving, message_id-deduped accumulator. Recommended shape: a
`LinkedHashMap<String, Message>` keyed by `message_id` (first insertion fixes position; a repeat
`message_id` updates in place without moving — satisfies "deduped by message_id"). Expose its
`values.toList()` for the projection. Keep this an internal detail; the public flow yields
`List<ThreadItem.MessageItem>`.

> **State-source rule (CLAUDE.md).** Single source of state. The per-conversation messages live
> in **one** StateFlow owned by the repo and mutated **only** inside the `init` collector. The
> cold `observeMessages` flow is a *derivation* of it, never a parallel mutable store.

### `observeMessages` shape (contract, not implementation)

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
```

Behaviour:
- Returns a **cold** flow derived from the per-conversation message StateFlow, mapped
  `List<Message>` → `List<ThreadItem.MessageItem>` (one `MessageItem` per message, in stored
  order).
- `.onStart { requestBackfillOnce(conversationId) }` — first collection sends one `backfill_since`
  envelope via `pump.send(...)` (non-suspending), records `requestId → conversationId` in
  `pendingBackfill`, and adds the id to `backfillRequested`. Subsequent collections are no-ops on
  the request (the StateFlow replays current accumulated state).
- A live `message` for `conversationId` re-emits the updated list (StateFlow update). A `message`
  for a **different** conversation updates that conversation's slot only — this conversation's
  derived flow does not re-emit (AC #3). If using a single `MutableStateFlow<Map<...>>`, derive
  with `.map { it[id].orEmpty() }.distinctUntilChanged()` so an unrelated-key change does not
  re-emit this flow.

### Ordering (AC #1, Technical Notes)

**Wire/arrival order — no client-side timestamp sort.** The `message_chunk` carries one envelope
`ts` for many messages, so a timestamp sort is impossible and wrong. Backfill rows are appended in
chunk order; live messages append in arrival order; the encrypted ordered stream cannot skip a
frame, so arrival order is authoritative. Do not reorder around any gap.

### Mapping

Reuse `MessagePayloadDto.toMessage(envelope)` (#317) for both `message` and each `message_chunk`
row. It consumes the `Envelope` (timestamp = envelope `ts`), sets `isStreaming = false`, and the
repo supplies the active session id (no `session_id` on the wire). `MessageItem` wraps the mapped
`Message`.

---

## State + concurrency model

- **One** collector, launched in `init` on the injected constructor `CoroutineScope` (tests pass
  `backgroundScope`). No new coroutine, no new dispatcher, no timers.
- `pendingBackfill` / `backfillRequested` / the message map are mutated **only** from inside that
  collector (and `requestBackfillOnce`, which the cold flow's `onStart` invokes on the collector's
  confinement). Since the slice has no `delay()`, all resumptions land at current virtual time.
- `pump.send` is non-suspending (`Boolean`); call it directly from `onStart`. If it returns
  `false` (transport down), the flow still serves live messages once the pump recovers — do not
  throw.
- Shutdown/cancellation: the flow is cold and lifecycle-bound by the collector
  (`collectAsStateWithLifecycle` at the UI). Nothing new to dispose; the repo collector's lifetime
  is the injected scope (unchanged from #312).

---

## Error handling

| Failure | Layer | Handling |
|---|---|---|
| Malformed `message` / `message_chunk` / `backfill_done` payload | wire decode (`MobileWireCodec`) | decode failure surfaces per the existing codec contract (#317); a single bad frame is logged and skipped, not fatal — confirm the existing arm's behaviour and match it |
| `error` envelope `inReplyTo` a `backfill_since` request | collector | resolve + **remove** the `pendingBackfill[inReplyTo]` entry (prevents map leak), leave the thread live-only. Log once. (Light-touch — no observed failure requires more; see Open questions.) |
| `pump.send` returns `false` (transport down at request time) | `onStart` | do not throw; do not mark `backfillRequested` so a later collection can retry. Live messages still flow when the pump recovers |
| `backfill_done` for an unknown/already-resolved `inReplyTo` | collector | no-op (idempotent) |

No new user-facing error surface — this is a data-layer slice; the thread screen already renders
whatever list it receives.

---

## Testing strategy

Unit tests only (`./gradlew test`), extending `RemoteConversationRepositoryTest`. **Drive every
cascade with `runCurrent()` after each `pump.push(...)` and after launching each collector — NOT
`advanceUntilIdle()`** (the slice has no timers; `advanceUntilIdle` empirically fails to deliver
the buffered channel item to the `backgroundScope` collector). Capture outgoing envelopes through
the fake `send` to assert the `backfill_since` request and to drive the response.

Scenarios (write as the project's test idiom; these are the cases, not the code):

- **Backfill then live (AC #1, #2, #4).** Collect `observeMessages(A)`; assert a `backfill_since`
  for A was sent. Push a `message_chunk` (history rows for A) + `backfill_done(inReplyTo=req)`;
  assert the emission is the ordered `List<MessageItem>` matching chunk order. Then push a live
  `message` for A; assert it re-emits with the new item appended last.
- **Dedupe by message_id (AC #2).** A `message_id` present both in the backfill chunk and in a
  later live `message` appears **once** (position fixed at first occurrence).
- **Conversation isolation (AC #3).** While collecting `observeMessages(A)`, push a `message` for
  conversation **B**; assert A's flow does **not** re-emit.
- **Arrival order, no timestamp sort (AC #1).** Push messages whose (would-be) timestamps are out
  of order; assert stored/emitted order follows arrival, not timestamp.
- **backfill_done correlation (AC #2).** `backfill_done` carries only `inReplyTo`; assert it
  resolves to the correct conversation via `pendingBackfill` and completes A's backfill without a
  `conversation_id` on the done frame.
- **Round-trip against the v2 server double (AC #4 — the headline acceptance test).** Seed
  backfilled history + live `message` envelopes through the fake pump; assert the expected ordered
  `List<ThreadItem.MessageItem>`.
- *(If you add `message_chunk` / `backfill_done` decode to `MobileWireCodec`)* add codec
  encode/decode round-trip tests mirroring the existing `message` codec tests.

---

## Security considerations

> This ticket carries the `security-sensitive` label. **A full label-gated security-review pass
> per `security-review.md` was NOT completed this run** (the harness suppressed tool output, so
> `security-review.md` could not be read and the structured pass could not be run). The findings
> below are an inline first cut; **the formal pass must be re-run** before this spec is treated as
> security-cleared. Flag this to the operator.

Trust boundary: this slice processes **already-decrypted, post-Noise** application payloads. Frame
confidentiality / integrity / ordering / replay are handled by the Noise_IK transport beneath
(#306–#309); this layer trusts the pump's plaintext `Envelope` stream. Surfaces to reason about:

- **Cross-conversation misrouting.** A message must land in the thread of the `conversation_id`
  **in its own payload row**, never the conversation that happens to be collecting. Key the
  message map on `payload.conversation_id` / `row.conversation_id` — *not* on the requesting flow's
  id. (Enforced by design above; assert via the isolation test.)
- **Unbounded growth (resource).** A hostile/buggy server could send an enormous `message_chunk`
  or an endless live stream, growing the per-conversation map without bound. Today's fake/relay is
  trusted and no such failure is observed → **do not** add a cap now (evidence-based; defer). Note
  it as a known follow-up rather than building speculative defense.
- **`pendingBackfill` map leak.** Every `backfill_since` adds an entry; ensure `backfill_done`
  **and** an `error` reply both remove it (see Error handling) so a stream of unanswered requests
  can't grow the map.
- **message_id dedup semantics.** Dedup is positional first-write-wins; a repeated `message_id`
  with *different* text updates in place. That is a server-trust decision (the server owns
  `message_id` uniqueness) — acceptable under the post-Noise trust model; record it so it's a
  conscious choice, not an accident.

No new secrets, key material, persistence, IPC, `android.*`, or external input parsing is
introduced beyond the existing codec path. `data/` stays portable (no `android.*`).

---

## Open questions

- **Exact current shape of the `observeMessages` stub and the StateFlow names** in
  `RemoteConversationRepository.kt` (e.g. is the last-message state from #329 a single StateFlow
  or a map?) — confirm on first read; the design assumes one collector + named StateFlows per the
  #312/#329 pattern.
- **Does `MobileWireCodec` already decode `message_chunk` + `backfill_done`?** If yes, this slice
  is pure repo logic; if no, add the DTOs + decode arms (small, in scope). Confirm before sizing
  your test list.
- **Request-id generation for `backfill_since`** — reuse whatever #329 established if it issues
  requests; otherwise a monotonic counter confined to the collector is sufficient (avoid
  `Math.random`/UUID-from-clock concerns in tests — a counter is deterministic).
- **`backfill_since` request parameters** (the "since" cursor: per-conversation, all-history vs
  incremental). The AC says "historical messages from past sessions" → full history on first
  collect is the safe default; confirm the server's `backfill_since` semantics against
  `pyrycode/internal/protocol/messaging.go` (testdata `backfill_since.json`).
- **Active session id supplied to `toMessage`** — the wire has no `session_id`; #317 left "where
  the remote `Session` is sourced" as an open #312 question. For a `MessageItem`-only thread this
  may be a benign placeholder; confirm `MessageItem` does not require a meaningful session id
  (boundaries are de-scoped, so it should not).

---

## Scope self-check

Production source files modified/created: **1** (`RemoteConversationRepository.kt`), plus
*conditionally* `MobileWireCodec.kt` if chunk/done decode is missing → **≤ 2**. New exported
types: **0** (`observeMessages` and `ThreadItem.MessageItem` already exist; new state is private).
No consumer cascade (filling a stub; signature unchanged). Well within `s`. No split.
