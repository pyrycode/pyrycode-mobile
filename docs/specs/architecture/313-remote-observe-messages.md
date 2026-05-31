# Spec — RemoteConversationRepository.observeMessages: thread + backfill (v2)

**Ticket:** #313 — feat(data): RemoteConversationRepository.observeMessages — thread + backfill (v2)
**Size:** S (single production file; no new exported types; no consumer cascade — fills a stub)
**Labels:** `size:s`, `security-sensitive` (rework-count:2 — read § Prior-attempt artifact)

> **Verification status (read first).** This spec was authored in a session where the harness
> stopped returning **read** output (Read / `cat` / `grep` came back empty after the first
> batch). The following are **confirmed** from rendered output: the file inventory under
> `data/repository/` + `data/network/` + `data/model/`, the full `ConversationRepository` /
> `ThreadItem` contract, the `MobileWireCodec.kt` contents, and the ticket labels. The
> **internal shape** of `RemoteConversationRepository.kt` (collector layout, exact StateFlow
> names, the `observeMessages` stub body) and the exact `Envelope` field names are taken from
> the project's design-note memory for the sibling slices (#312 list, #317 mapper, #329
> last-message) and are marked **CONFIRM ON READ** below. Where a name is uncertain the spec
> says so — treat those as the first thing to verify when you open the file, not as gospel.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` —
  **read in full. The only production file you modify.** CONFIRM ON READ: (1) the single
  `pump.inbound` collector launched in `init` on the injected `CoroutineScope`, and how it
  demuxes by `Envelope.type` into StateFlows (the #312 conversation-list projection and the
  #329 last-message state); (2) the existing `message`-envelope handling added by #329 — you
  will **extend that same arm**, not add a second `message` decode; (3) the
  `observeMessages(conversationId)` **stub** this slice fills; (4) the held `SessionPump`
  reference and the request-id mechanism #329 may already use for any request/response.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:23` —
  `fun observeMessages(conversationId: String): Flow<List<ThreadItem>>` (do **not** change the
  signature). Lines 130–152: the `ThreadItem` sealed interface — construct `ThreadItem.MessageItem(message)`
  **only**; `ThreadItem.SessionBoundary` is out of scope (see Context).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` (#309) — CONFIRM ON
  READ: `inbound` is a **single-consumer** `Flow<Envelope>`; `send` is **non-suspending**
  (`fun send(envelope): Boolean`); and the `Envelope` correlation field used for request/response
  (memory: `inReplyTo`).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` — the `Envelope` type
  (`type`, `payload`, `ts`, `inReplyTo`) and the v2 wire payload models. CONFIRM whether
  `message_chunk` and `backfill_done` payload models already exist; if not, add them here as
  `@Serializable` data classes (small — mirror the existing `message` payload).
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` — the `message` payload
  model + its `toMessage(envelope)` mapper (#317): consumes the `Envelope` (timestamp =
  envelope `ts`), hardcodes `isStreaming = false`, and the **repo supplies the active session
  id** (no `session_id` on the wire). Reuse it for both `message` and each `message_chunk` row.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29` — `MobileJson` is the
  single configured `Json` for all v2 (de)serialization (`MobileJson.decodeFromString<T>(...)`).
  This file holds only the JSON config + base64 helpers — **not** the Envelope/codec models.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — the domain `Message` fields
  (`id`, `sessionId`, `role`, `content`/`text`, `timestamp`, `isStreaming`, …). CONFIRM the
  exact field names the mapper populates.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` —
  **read in full.** Extract the fake `SessionPump` (memory: `inbound` =
  `Channel(UNLIMITED).receiveAsFlow()`, `push` = `trySend`, outgoing `send` captured), the
  `backgroundScope` injection, and the `runCurrent()` driving idiom. Your new tests extend this
  file.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepository313Test.kt` —
  **a leftover from a prior #313 attempt (rework-count:2). Reconcile it: fold any usable cases
  into `RemoteConversationRepositoryTest` and delete this file, or repurpose it — do not ship
  two parallel test files for the same class.** See § Prior-attempt artifact.
- `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` — the
  immediately-preceding slice; copy its collector-extension + StateFlow-projection pattern.

---

## Context

Phase 4 backend integration. Third slice of `RemoteConversationRepository` (split from #278),
built on the #312 class + collector scaffolding and the #317 message mapper. The preceding slice
(#329, just merged) added live-`message` consumption for `observeLastMessage`; this slice reuses
that same inbound `message` handling and fills the `observeMessages` stub.

`observeMessages(conversationId)` streams a conversation's full thread over Mobile Protocol v2:
historical messages from past sessions **backfilled** (`backfill_since` → `message_chunk` →
`backfill_done`) ahead of the **live** `message` stream, merged into one chronological
`List<ThreadItem.MessageItem>`, deduped by `message_id`.

**Two things are de-scoped — do not implement them, do not treat their absence as a bug:**

1. **Session-boundary delimiters (→ #336).** The v2 wire has no session-transition representation:
   `MessagePayload`/`MessageChunkPayload` are `{conversation_id, message_id, role, text}` (no
   `session_id`, no per-message timestamp, no boundary/reason/cwd), and the v2 app-message type
   set has no `session_boundary` type (server SSOT: `pyrycode/internal/protocol/messaging.go`,
   `codes.go`). `ThreadItem.SessionBoundary` **cannot be constructed from the wire.** Emit
   `MessageItem`s only.
2. **Live token-streaming (→ streaming follow-up #337).** The wire has no partial/`done` flag;
   `message` is one *finished* message, `message_chunk` is a batch of *finished* messages.
   `toMessage` hardcodes `isStreaming = false`. Do not invent a streaming wire field.

---

## Design

### Where the work goes — extend the existing collector; do NOT subscribe independently

This is the load-bearing constraint, and the reason the earlier (removed) #313 spec was scrapped:

> `SessionPump.inbound` is **single-consumer**, and `RemoteConversationRepository` already owns
> the one collector in `init`. `observeMessages` MUST demux backfill/live frames **inside that
> existing collector** and project into per-conversation state. It must **NOT** open a second
> `pump.inbound.collect` (e.g. a cold `flow { pump.inbound.onStart{…}.collect{…} }`) — a second
> consumer of a single-consumer stream is a correctness bug. #329 already added the `message`
> arm; extend it, don't duplicate it.

Data flow:

```
pump.inbound  (the ONE collector in init)
  ├─ "message"        → (existing #329 path) + upsert toMessage(env) into messagesByConversation[payload.conversation_id]
  ├─ "message_chunk"  → for each row: upsert toMessage(row,env) into messagesByConversation[row.conversation_id]
  ├─ "backfill_done"  → conversationId = pendingBackfill.remove(env.inReplyTo); (history complete; no-op if unknown)
  └─ (existing) "conversations" → list projection (#312)

observeMessages(id) = perConversation(id) flow .map { it.map(::MessageItem) } .onStart { requestBackfillOnce(id) }
```

### New internal state (all `private`, mutated only inside the init collector / `requestBackfillOnce`)

| State | Suggested type | Purpose |
|---|---|---|
| messages-by-conversation | `MutableStateFlow<Map<String, MessageThread>>` (or a lazily-created per-id `MutableStateFlow`) | source the cold flow derives from; the single source of message state |
| `pendingBackfill` | `MutableMap<String, String>` (requestId → conversationId) | correlate `backfill_done` (carries **only** `inReplyTo`, no `conversation_id`) back to its conversation |
| `backfillRequested` | `MutableSet<String>` (conversationIds) | idempotency guard so re-collecting `observeMessages(id)` does not re-issue `backfill_since` |

`MessageThread` = an ordering-preserving, `message_id`-deduped accumulator. Recommended:
`LinkedHashMap<String, Message>` keyed by `message_id` — first insertion fixes position; a repeat
`message_id` updates in place without moving (satisfies "deduped by message_id"). Expose
`values.toList()`.

> **Single source of state (CLAUDE.md).** Message state lives in **one** repo-owned StateFlow
> mutated only by the collector. The cold `observeMessages` flow is a *derivation*, never a
> parallel mutable store.

### `observeMessages` contract (signature only — not implementation)

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
```

- **Cold**, derived from the per-conversation message StateFlow, mapped `List<Message>` →
  `List<ThreadItem.MessageItem>` in stored order.
- `.onStart { requestBackfillOnce(conversationId) }`: first collection sends one `backfill_since`
  envelope via `pump.send(...)` (non-suspending), records `requestId → conversationId` in
  `pendingBackfill`, and marks `backfillRequested`. Re-collection replays current state without
  re-requesting.
- If using a single `MutableStateFlow<Map<…>>`, derive with
  `.map { it[id].orEmpty() }.distinctUntilChanged()` so a change to **another** conversation's
  slot does not re-emit this flow (AC #3).

### Ordering (AC #1)

**Wire/arrival order — no client-side timestamp sort.** A `message_chunk` carries one envelope
`ts` for many messages, so a timestamp sort is impossible and wrong. Backfill rows append in chunk
order; live messages append in arrival order; the ordered encrypted stream cannot skip a frame, so
arrival order is authoritative. Do not reorder around a gap.

### Mapping

Reuse `toMessage(envelope)` (#317, in `MessagePayload.kt`) for both `message` and each
`message_chunk` row: timestamp = envelope `ts`, `isStreaming = false`, repo supplies the active
session id. `MessageItem` wraps the mapped `Message`.

---

## State + concurrency model

- **One** collector, launched in `init` on the injected constructor `CoroutineScope` (tests pass
  `backgroundScope`). No new coroutine, no new dispatcher, no timers.
- `pendingBackfill` / `backfillRequested` / the message map are mutated only from the collector
  and from `requestBackfillOnce` (invoked by the cold flow's `onStart`). The slice has no
  `delay()`; all resumptions land at current virtual time.
- `pump.send` is non-suspending; call it from `onStart`. If it returns `false` (transport down),
  do **not** throw and do **not** mark `backfillRequested` — a later collection retries; live
  messages still flow when the pump recovers.
- Cancellation: the flow is cold and lifecycle-bound at the UI (`collectAsStateWithLifecycle`);
  the collector's lifetime is the injected scope (unchanged from #312). Nothing new to dispose.

---

## Error handling

| Failure | Layer | Handling |
|---|---|---|
| Malformed `message` / `message_chunk` / `backfill_done` payload | `MobileJson` decode in the collector | log once and skip the frame; match the existing `message`-arm behaviour (#329). One bad frame is not fatal |
| `error` envelope `inReplyTo` a `backfill_since` | collector | `pendingBackfill.remove(inReplyTo)` (prevents map leak); leave the thread live-only; log once |
| `pump.send` returns `false` at request time | `onStart` | do not throw; do not mark `backfillRequested` (retry on next collect) |
| `backfill_done` for unknown/already-resolved `inReplyTo` | collector | no-op (idempotent) |

No new user-facing error surface — the thread screen renders whatever list it receives.

---

## Testing strategy

Unit tests only (`./gradlew test`), in `RemoteConversationRepositoryTest` (after reconciling the
leftover `…313Test.kt`). **Drive every cascade with `runCurrent()` after each `pump.push(...)` and
after launching each collector — NOT `advanceUntilIdle()`** (no timers; `advanceUntilIdle`
empirically fails to deliver the buffered channel item to the `backgroundScope` collector).
Capture outgoing `send` envelopes to assert the `backfill_since` request and to drive the response.

Scenarios (cases, not code — write in the project idiom):

- **Backfill then live (AC #1, #2).** Collect `observeMessages(A)`; assert a `backfill_since` for
  A was sent. Push `message_chunk` (history rows for A) + `backfill_done(inReplyTo=req)`; assert
  the emission is the ordered `List<MessageItem>` in chunk order. Push a live `message` for A;
  assert it re-emits with the new item appended last.
- **Dedupe by message_id (AC #2).** A `message_id` in both the backfill chunk and a later live
  `message` appears **once** (position fixed at first occurrence).
- **Conversation isolation (AC #3).** While collecting `observeMessages(A)`, push a `message` for
  conversation **B**; assert A's flow does **not** re-emit.
- **Arrival order, no timestamp sort (AC #1).** Push messages whose would-be timestamps are out of
  order; assert emitted order follows arrival.
- **backfill_done correlation (AC #2).** `backfill_done` carries only `inReplyTo`; assert it
  resolves to A via `pendingBackfill` and completes A's backfill without a `conversation_id` on
  the done frame.
- **Round-trip against the v2 server double (AC #4 — headline).** Seed backfilled history + live
  `message` envelopes through the fake pump; assert the expected ordered
  `List<ThreadItem.MessageItem>`.
- *(Only if you add `message_chunk` / `backfill_done` payload models)* a `MobileJson`
  encode/decode round-trip mirroring the existing `message` payload test.

---

## Security review (label: `security-sensitive`)

This ticket is `security-sensitive`, so a review pass is mandatory. The structured
`security-review.md` checklist could not be read this session (read-output failure); the pass
below is an inline adversarial walk of the data-layer trust boundaries. **Re-run the formal
`security-review.md` pass when read access is restored** before treating this as fully cleared.

**Trust boundary.** This slice processes **already-decrypted, post-Noise** application payloads.
Frame confidentiality / integrity / ordering / replay are enforced by the Noise_IK transport
beneath (#306–#309); this layer trusts the pump's plaintext `Envelope` stream. The server is the
authority for `message_id` uniqueness and `conversation_id` correctness under the post-pairing
trust model.

| # | Concern | Verdict / control |
|---|---|---|
| S1 | **Cross-conversation misrouting** — a message landing in the wrong thread | **Enforced by design.** Key the message map on the **payload's own** `conversation_id` (`payload.conversation_id` / per-row `conversation_id`), never on the collecting flow's id. `backfill_done` resolves its conversation via `pendingBackfill`, not via the active collector. Covered by the isolation test (AC #3). |
| S2 | **Unbounded growth (resource)** — a hostile/buggy server sends a huge `message_chunk` or endless stream, growing `messagesByConversation` without bound | **No cap now (evidence-based).** The relay is a paired, trusted peer and no such failure is observed. Adding a cap would risk silently truncating legitimate history. Recorded as a known follow-up, not built speculatively. |
| S3 | **`pendingBackfill` map leak** — every `backfill_since` adds an entry | **Controlled.** Both `backfill_done` **and** an `error` reply must `remove(inReplyTo)` (see Error handling). `send`-failure does not add an entry. |
| S4 | **`message_id` dedup semantics** — a repeated id with different text updates in place | **Accepted, conscious choice.** The server owns `message_id` uniqueness; under the post-Noise trust model, last-write-wins on a duplicate id is acceptable. Documented so it's deliberate, not accidental. |
| S5 | **Sensitive data in logs** — message `text` / ids in per-frame log calls | **Control:** log frame `type` + `message_id` + `conversation_id` only; **never log `text`**. Match the existing wire-layer logging convention (`decodeServerStaticPubkey` already models "name the failure, never echo the bytes"). |
| S6 | **New attack surface** — secrets / key material / persistence / IPC / `android.*` / new parsing | **None introduced.** Reuses the existing `MobileJson` decode path; `data/` stays portable (no `android.*`). No new secret handling. |

**Verdict: PASS** (inline). No FAIL-level finding; S2 is a deferred-by-evidence note, not a gap.

---

## Prior-attempt artifact

This ticket is at `rework-count:2`, and a `RemoteConversationRepository313Test.kt` exists on
`feature/313` from an earlier attempt (the earlier boundary-carrying spec was removed in commit
`5ebbe7a` after the v2 wire was found to carry no session-boundary data). **CONFIRM ON READ what
else, if anything, that attempt left on the branch** (`git diff --name-only origin/main...HEAD`).
Reconcile the duplicate test file (fold usable cases into `RemoteConversationRepositoryTest`,
delete `…313Test.kt`); do not ship two parallel test files for the same class.

---

## Open questions / CONFIRM ON READ

- **Collector + StateFlow names** in `RemoteConversationRepository.kt` (the #312 list projection
  and #329 last-message state) — confirm before wiring the new arm.
- **`Envelope` field names** (`type`, `payload`, `ts`, `inReplyTo`) and the request-id mechanism
  — confirm against `NoiseSessionPump.kt` / `MobileWireModels.kt`; reuse #329's request-id helper
  if one exists, else a collector-confined monotonic counter (deterministic in tests — avoid
  UUID/clock).
- **Do `message_chunk` + `backfill_done` payload models already exist?** If not, add them
  (`@Serializable`, in `MobileWireModels.kt`). Confirm before sizing your test list.
- **`backfill_since` request parameters** (the "since" cursor: full-history vs incremental).
  Full history on first collect is the safe default; confirm against `messaging.go` testdata
  (`backfill_since.json`).
- **Active session id passed to `toMessage`** — the wire carries no `session_id`; #317 left "where
  the remote `Session` is sourced" as an open #312 question. For a `MessageItem`-only thread a
  benign placeholder is acceptable (boundaries de-scoped); confirm `MessageItem` needs no
  meaningful session id.

---

## File-overlap check (§1.5) — INCOMPLETE this session

The mandatory branch-overlap check could not complete (read-output failure prevented reading
`git diff`/`git branch -r` results). **Sibling #314 (mutations) is known to extend the same
`RemoteConversationRepository.kt` + test file** (per project memory). Before the developer runs,
re-verify: if `origin/feature/314` (or any sibling) touches
`data/repository/RemoteConversationRepository.kt`, set `addBlockedBy` between #313 and it so they
don't collide at merge. #329 already merged, so the established pattern is sequential landing of
these slices.

---

## Scope self-check

Production source files modified/created: **1** (`RemoteConversationRepository.kt`), plus
*conditionally* the wire-models file if `message_chunk`/`backfill_done` models are missing →
**≤ 2**. New exported types: **0** (`observeMessages` + `ThreadItem.MessageItem` already exist;
new state is private). No consumer cascade (fills a stub; signature unchanged). Well within `s`.
No split.
