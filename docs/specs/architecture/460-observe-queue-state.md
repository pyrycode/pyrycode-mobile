# Spec #460 — Decode `queue_state` into an observable per-conversation queue

**Ticket:** pyrycode-mobile #460 (`feat(data)`, `size:s`, `security-sensitive`)
**Split from:** #429 (epic pyrycode#597 Phase 3, queued-message backlog). This is the **data substrate** — decode + observe only. The render slice is #461 (blockedBy this); the drop slice is #462 (blockedBy #461).

This slice is a near-exact structural twin of **#395** (`observeStall`). Read `docs/specs/architecture/395-observe-stall-state.md` first: the decode→state→observe shape, the capability gate, the fail-closed drop idiom, the facade override, and the test harness are all reused verbatim. The only deltas are (a) the observable surface is an **ordered list** per conversation (not a `Boolean`), so it needs a portable element type, and (b) each `queue_state` is a **full snapshot that replaces** the conversation's backlog (no onset/clear edges — there is no live-session-arm hook).

## Files to read first

- `docs/specs/architecture/395-observe-stall-state.md` — **the precedent.** Whole document. Your design is this with `Set<String>` → `Map<String, List<QueuedMessage>>` and no clearing arm.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:248-408` — `onInbound` `when (envelope.type)` demux. **The load-bearing edit site.** The `TYPE_STALL` arm (357-369) is the exact template for the new `TYPE_QUEUE_STATE` arm: gate on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, decode-or-drop, single `update {}`. The `TYPE_MESSAGE` arm (266-287) shows the `(conversationId, X)` destructure + the `catch (IllegalArgumentException)` that covers both `SerializationException` and `Instant.parse` failures.
- `RemoteConversationRepository.kt:164-176` — the `stalledConversations` `MutableStateFlow<Set<String>>` field doc. `queuedByConversation` mirrors it (a `Map<String, List<QueuedMessage>>` instead of a `Set`). Same single-writer / connection-scoped / lost-on-reconnect posture.
- `RemoteConversationRepository.kt:454-468` — `decodeStall(envelope): String?`. `decodeQueueState(envelope): Pair<String, List<QueuedMessage>>?` mirrors it (returns id + ordered list instead of just id).
- `RemoteConversationRepository.kt:849-859` — the `observeStall` override (`stalledConversations.map { id in it }.distinctUntilChanged()`). `observeQueue` is `queuedByConversation.map { it[id].orEmpty() }.distinctUntilChanged()`.
- `RemoteConversationRepository.kt:1222-1226` — the `TYPE_STALL` const inside `private companion object`. Add `TYPE_QUEUE_STATE = "queue_state"` next to it, same doc style.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:32-43` — the `observeStall` interface method **with its `flowOf(false)` default**. `observeQueue` mirrors it with `flowOf(emptyList())`. `:152-182` — `ConversationFilter` / `ThreadItem` / `BoundaryReason` are public contract types **co-located with the interface** because they are its return/param element types. `QueuedMessage` joins them here — it is the element type of `observeQueue`'s return, exactly as `ThreadItem` is the element type of `observeMessages`. **Do not create a new model file** (see § Design, "Why no new file").
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:74` — `observeStall` facade override (`switchToLive(false) { … }`). `observeQueue` is `switchToLive(emptyList()) { … }`.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:1-79` — the file header doc (multi-type, `internal`, fail-closed strict-decode posture; ktlint single-class-filename rule does **not** apply) + `StallPayloadDto`. Add `QueueStatePayloadDto`, `QueuedMessageDto`, and `toQueue()` here — no new file.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt:70, 82-91` — `toMessage`'s `Instant.parse(envelope.ts)` idiom and the "malformed ts → IllegalArgumentException, caught at the decode boundary" contract. Each `QueuedMessageDto.ts` is parsed the same way in `toQueue()`.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` config: `encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`, **no `coerceInputValues`.** This is why `queued` must be a **nullable** field with `.orEmpty()` (§ Design, "Tolerating `null` vs `[]`"), not a non-nullable list.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:62, 86-100` — `CAPABILITY_INTERACTIVE = "interactive"` and `HelloClientPayload.capabilities` (defaults to `listOf(CAPABILITY_INTERACTIVE)` — the phone already advertises `interactive`). **Confirms the gate decision: `queue_state` rides the already-negotiated `interactive`; no new capability to advertise** (§ Design, "Capability gate").
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:3,10` — `import kotlinx.datetime.Instant` + `val timestamp: Instant`. `QueuedMessage.timestamp` is the same type, for the same reason.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1679-2010` — the #385/#395 test harness: `RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`, `FakeSessionPump`, `pump.push(...)`, `runCurrent()`, the capability-gate tests (`emptySet()` at 1777, `setOf("something_else")` at 1792), the `stall_*` tests (1918+), and the `stallEnvelope` / `turnStateEnvelope` builders + `collectStall` helper. Add a `queueStateEnvelope(conversationId, items)` builder and a `collectQueue` helper in the same shape.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:218-296` — `observeStall_whileAbsent_emitsFalse` (221) and `observeStall_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch` (234), plus the inline test-double override at 290. Mirror all three for `observeQueue` (default `emptyList()`).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — **no change.** It inherits the `flowOf(emptyList())` default (the cascade-avoidance lever; `codegraph_impact observeStall` confirms an interface default reaches only the 3 impls, zero consumer cascade).

## Context

Epic pyrycode#597 Phase 3 adds a queued-message backlog: while claude is busy, the daemon buffers inbound phone turns in `internal/msgqueue` and broadcasts the current backlog as a `queue_state` envelope. The phone needs to **see** that backlog (this slice) and later **cancel** an entry (#462).

The pyrycode wire side is **already live and closed**: the `queue_state` / `dequeue_message` wire types (pyrycode#705 → #720) and the `dequeue_message` handler (pyrycode#723) are merged. This slice is the mobile consumer of the inbound half.

**The decisive wire facts, established from the SSOT before design** (pyrycode `docs/protocol-mobile.md` § Queue (v2); spec #720; ADR 025):

1. **Wire shape:** `queue_state = {conversation_id: string, queued: [{queued_msg_id, text, ts}]}`. It is a **full snapshot** of the conversation's backlog (the wire form of `msgqueue.Snapshot(convID)`), in FIFO / enqueue order.
2. **`queued_msg_id` is a `uint64` JSON number**, not a string/nonce (it is a per-conversation monotonic counter). The mobile DTO decodes it as a **`Long`** (the same posture as `Envelope.eventId: Long?`, also a wire `uint64`). Decoding it as a `String` is wrong and pyrycode#720 explicitly flags it.
3. **`queued` may marshal to `null` OR `[]` for an empty backlog** — both are valid wire (`[]QueuedItem(nil)` → `null`; the producer #722 *recommends* but is not forced to emit `[]`). The decoder must tolerate both and treat them identically (empty backlog). This is the **one** documented latitude; every other field stays strict-required (fail-closed).
4. **Capability gate:** `queue_state` is fanned out **only to phones that negotiated `interactive`** ("interactive, capability-gated" in the protocol table). It rides the **already-negotiated `interactive` capability** — **no new capability is introduced or advertised.** (The separate ADR-025 "viewing/dequeuing is *ungated* for any paired phone" statement is the *authorization* model — it means no extra per-device gate like permission-modals need — and is the outbound-`dequeue` concern of #462; it does not change this inbound decode gate.)

## Design

The change extends the existing single-inbound-collector demux in `RemoteConversationRepository`. **No new class, no new file** — four production files, all additive edits:

| File | Edit |
|------|------|
| `data/network/InteractivePayloads.kt` | add `internal data class QueuedMessageDto`, `internal data class QueueStatePayloadDto`, and `internal fun QueueStatePayloadDto.toQueue()` |
| `data/repository/ConversationRepository.kt` | add the public `QueuedMessage` model type (next to `ThreadItem`) and the `observeQueue` interface method **with a default** |
| `data/repository/StableConversationRepository.kt` | add the facade `observeQueue` override |
| `data/repository/RemoteConversationRepository.kt` | add the `queuedByConversation` state field, the `observeQueue` override, the `TYPE_QUEUE_STATE` arm, the `decodeQueueState` helper, the `TYPE_QUEUE_STATE` const |

### Why no new file (the 4-file shape)

`QueuedMessage` is the element type of an interface return (`observeQueue(): Flow<List<QueuedMessage>>`), exactly as `ThreadItem` is the element type of `observeMessages(): Flow<List<ThreadItem>>`. The codebase already co-locates such contract element types **with the interface** in `ConversationRepository.kt` (`ThreadItem`, `ConversationFilter`, `BoundaryReason`). Putting `QueuedMessage` there matches that precedent and keeps the ktlint single-class-filename rule satisfied (the file already has multiple public top-level types). A standalone `data/model/QueuedMessage.kt` would be the only model split from its consuming contract — inconsistent, and it is what would have pushed this to a needless 5th file. #395 stayed at 4 files for the same reason (its surface was a primitive `Boolean`, needing no element type at all).

### Key types & contracts

**1. Decode DTOs** (network, `internal`, in `InteractivePayloads.kt`):

```kotlin
@Serializable
internal data class QueuedMessageDto(
    @SerialName("queued_msg_id") val queuedMsgId: Long,  // wire uint64 → Long
    val text: String,
    val ts: String,                                       // RFC-3339, parsed in toQueue()
)

@Serializable
internal data class QueueStatePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val queued: List<QueuedMessageDto>? = null,           // tolerate wire null OR [] — see below
)
```

`conversationId`, `queuedMsgId`, `text`, `ts` are all **required, non-null** — the fail-closed posture of every sibling DTO (a missing/wrong-typed field throws `SerializationException ⊂ IllegalArgumentException`, dropping the one envelope). The **only** relaxation is `queued`, addressed next.

**2. Tolerating `null` vs `[]`.** `MobileJson` has `explicitNulls = false` and **no `coerceInputValues`**, so a wire `"queued": null` into a *non-nullable* `List<…>` field would throw. Modeling `queued` as **nullable-defaulted** (`List<QueuedMessageDto>? = null`) accepts all three documented producer shapes — `null`, `[]`, `[…]` — and a missing key, and the mapper coalesces:

```kotlin
internal fun QueueStatePayloadDto.toQueue(): List<QueuedMessage> =
    queued.orEmpty().map { QueuedMessage(id = it.queuedMsgId, text = it.text, timestamp = Instant.parse(it.ts)) }
```

Element-level strictness is preserved: a bad **item** (`queued_msg_id` as a string, missing `text`, unparseable `ts`) throws inside the `map` and drops the **whole** snapshot — the established "one bad row drops the whole chunk" idiom of the `TYPE_MESSAGE_CHUNK` arm. `Instant.parse` failures surface as `IllegalArgumentException`, caught at the decode boundary (§ Error handling).

**3. Portable model type** (`ConversationRepository.kt`, public, next to `ThreadItem`):

```kotlin
/** One message waiting in a conversation's queued backlog while claude is busy (#460).
 *  [id] is the daemon's per-conversation `queued_msg_id` counter; [timestamp] is enqueue time. */
data class QueuedMessage(val id: Long, val text: String, val timestamp: Instant)
```

**4. Interface method** (with default, next to `observeStall`):

```kotlin
/** Emits [conversationId]'s ordered queued-message backlog (FIFO), empty until the first
 *  `queue_state` snapshot lands; re-emits the full backlog on each new snapshot. Cold.
 *  Default flowOf(emptyList()): impls without an interactive wire inherit "never queued". */
fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())
```

The default is the cascade-avoidance lever (same as `observeStall` / `delete` / `requestScreenSnapshot`): the fake and every inline test double inherit it and compile unchanged. Verified via `codegraph_impact observeStall`: an interface default reaches only the 3 impls; no consumer cascade.

**5. Facade override** (`StableConversationRepository`, mirroring `observeStall`):

```kotlin
override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
    switchToLive(emptyList()) { it.observeQueue(conversationId) }
```

`whenAbsent = emptyList()`: no live connection ⇒ empty backlog. This also gives the correct reconnect behaviour — a fresh `RemoteConversationRepository` per connection starts with an empty map (#351), and `flatMapLatest` drops the prior connection's flow, so a backlog never survives a reconnect (it is re-derived from the next live `queue_state`).

**6. Remote impl** (`RemoteConversationRepository`):

- New connection-scoped field, mirroring `stalledConversations`:
  ```kotlin
  private val queuedByConversation = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())
  ```
  Keyed by conversation id; the value is the **full ordered backlog** for that conversation.
- Override (1:1 with `observeStall`):
  ```kotlin
  override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
      queuedByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()
  ```
  `orEmpty()` gives empty-until-first-snapshot (AC #2). `distinctUntilChanged` means a `queue_state` for **another** conversation does not re-emit this flow (AC #3), and a value-identical re-snapshot does not re-emit.
- `decodeQueueState` helper (mirrors `decodeStall`):
  ```kotlin
  private fun decodeQueueState(envelope: Envelope): Pair<String, List<QueuedMessage>>? =
      try {
          val dto = MobileJson.decodeFromJsonElement<QueueStatePayloadDto>(envelope.payload)
          dto.conversationId to dto.toQueue()
      } catch (e: IllegalArgumentException) { null }
  ```

### Data flow — full-replace snapshot (one new `onInbound` arm)

```
pump.inbound (single collector, wire order)
   └─ queue_state {conversation_id, queued:[…]}  ──[gate: interactive]──>
        decodeQueueState(env)?.let { (id, queue) ->
            queuedByConversation.update { it + (id to queue) }   // FULL REPLACE for this conv (AC #1/#2)
        }
```

The new `TYPE_QUEUE_STATE` arm, alongside `TYPE_STALL`:

```kotlin
TYPE_QUEUE_STATE -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        decodeQueueState(envelope)?.let { (conversationId, queue) ->
            queuedByConversation.update { it + (conversationId to queue) }
        }
    }
}
```

**Full-replace, not merge:** each `queue_state` is the authoritative current backlog (`msgqueue.Snapshot`), so `it + (id to queue)` overwrites that conversation's entry and leaves every other conversation untouched (AC #3). Wire array order is preserved verbatim — no sort, no dedup (AC #1). **Unlike #395 there is NO live-session-arm hook**: a backlog is replaced by the next snapshot, never "cleared" by forward-progress events, so the `TYPE_TURN_STATE…` arm is not touched. This arm is strictly additive and self-contained.

## State + concurrency model

- **Single writer.** `queuedByConversation` is written **only** from `onInbound`, on the one long-lived inbound collector launched in `init` on the connection-scoped `scope`. No cross-coroutine race. `update {}` (not `.value =`) matches the sibling projections' memory-visibility posture and keeps writers uniform.
- **Connection-scoped, in-memory.** A fresh repository per connection starts with an empty map; state is lost on connection drop / process death and re-derived from the next live `queue_state`. This is the intended lifetime — a backlog is "right now" state, not durable.
- **Cold fan-out.** `observeQueue` is a cold `map` + `distinctUntilChanged` projection of the one `StateFlow`; N collectors share the single inbound consumer (same multiplexing guarantee as `observeStall` / `observeLastMessage`). A fresh collector receives the current value (empty until a snapshot lands) on subscription.
- **No new coroutine, no dispatcher choice.** Pure in-process state folding on the existing collector; no `viewModelScope`, no IO/Default switch. The facade adds no scope.

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Malformed `queue_state` payload (missing/wrong-typed `conversation_id`; `queued` not an array; a bad item — `queued_msg_id` as string, missing `text`, unparseable `ts`) | `decodeQueueState` | `SerializationException` / `Instant.parse` `IllegalArgumentException` caught → `null` → the one envelope dropped, **single inbound collector survives** (AC #4). A later valid `queue_state` still updates state — proves the collector was not torn down. |
| `queued` is `null` or `[]` (empty backlog) | `toQueue` | `.orEmpty()` → empty list stored; the conversation's backlog is replaced with empty (a legitimate "queue drained" snapshot, not an error). |
| `queue_state` on a non-interactive connection | `TYPE_QUEUE_STATE` gate | Dropped before decode; never surfaces (fail-closed, defence in depth). |

`decodeQueueState` mirrors `decodeStall` / `decodeLiveSessionEvent`: one `try { decode → (id, list) } catch (IllegalArgumentException) { null }`. **Nothing logs the payload** — `text` is user-authored queued-message content and may be sensitive; the no-log idiom is uniform across every `onInbound` arm. The data layer surfaces only a `List<QueuedMessage>`, never an error.

## Testing strategy

Unit only — `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"` and `"…StableConversationRepositoryTest"`. Pure data layer: no Compose, no device. Test-first (RED → GREEN). Reuse the #385/#395 harness verbatim: `FakeSessionPump`, `RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`, `pump.push(...)`, `runCurrent()`. Add a `queueStateEnvelope(conversationId, items)` builder beside `stallEnvelope` and a `collectQueue(repo, id)` helper beside `collectStall`.

Scenarios (bullets — developer writes them in the project idiom; assert against the wire SSOT, not the fake):

- **Ordered decode (AC #1, #5).** interactive repo; collect `observeQueue("c1")`; push a `queue_state` with items `[(1,"a",t1), (2,"b",t2)]` → emits `[] → [QueuedMessage(1,"a",t1), QueuedMessage(2,"b",t2)]`, **in wire order**. Assert `id` is the `Long` from `queued_msg_id` and `timestamp` is `Instant.parse(ts)`.
- **`queued_msg_id` decoded as a number, not a string (the #720 trap).** A fixture with `"queued_msg_id": 7` decodes to `id == 7L`; a fixture with `"queued_msg_id": "7"` is a wrong-typed field → dropped (proves strict numeric decode).
- **Empty-until-first-state (AC #2).** Collect before any push → `[emptyList()]`. Then a `queue_state` with `queued: []` (and a second test with `queued: null`) → both stay empty (no spurious non-empty emission; `distinctUntilChanged` suppresses the equal re-emit).
- **Re-emit on each new snapshot / full replace (AC #2).** push `[(1,"a")]` → `[(1,"a")]`; push `[(2,"b"),(3,"c")]` → replaces to `[(2,"b"),(3,"c")]` (not appended).
- **Per-conversation isolation (AC #3).** `queue_state` for `"c1"` leaves `observeQueue("c2")` at `emptyList()`; a snapshot for `"c2"` does not re-emit `observeQueue("c1")`.
- **Malformed dropped, collector survives (AC #4).** push a `queue_state` with a missing `conversation_id` / a `queued` item missing `text` / a `queued_msg_id` as a string / an unparseable `ts` → no crash, stays empty; then a valid `queue_state("c1", …)` still surfaces (proves the single inbound consumer is alive).
- **Capability gate (fail-closed).** `negotiatedCapabilities = { emptySet() }` and `{ setOf("something_else") }`: push `queueStateEnvelope("c1", …)` → stays empty, never surfaces. (Mirror the #385/#395 gate tests at 1777 / 1792.)
- **Facade delegation (`StableConversationRepositoryTest`, mirror `observeStall`).** `observeQueue` emits `emptyList()` while `currentRepository` is `null`; delegates to the live repo; a prior backlog does not leak across a connection switch.

(`FakeConversationRepository` needs no change — it inherits the `flowOf(emptyList())` default; the facade `whenAbsent = emptyList()` path already covers "never queued".)

## Open questions

1. **Does the render slice (#461) need stall/queue interaction?** A stalled conversation will typically also have a non-empty queue (turns pile up while claude is stuck). This slice exposes the two states **independently** (`observeStall` + `observeQueue`); any combined "stalled with N waiting" presentation is a #461 UI derivation over both flows, **not** a data-layer concern. Flag to the #461 architect; do not pre-build it.
2. **`queued_msg_id` width.** Kept `Long` to match the wire `uint64` (ids ≥ 1, monotonic per conversation — never near `Long.MAX`). Same posture as `Envelope.eventId: Long?`. If a future drop slice (#462) needs to echo the id back, it reuses this `Long` verbatim — no string conversion.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings — the untrusted network→process boundary is a single explicit point: `decodeQueueState(envelope): Pair<String, List<QueuedMessage>>?` in `RemoteConversationRepository`, decoding `QueueStatePayloadDto` through the one configured `MobileJson` (the same boundary as `decodeStall` / `decodeLiveSessionEvent`). Downstream holds only parsed types — a `String` conversation id and `QueuedMessage(Long, String, Instant)` records; no raw `JsonElement` escapes. The one wire latitude (`queued` null/[]) is collapsed to `emptyList()` at the boundary, so no nullable list reaches consumers.
- **[Trust boundaries / capability gate]** No findings — the `TYPE_QUEUE_STATE` arm is gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, identically to the `stall` / structured-stream / modal arms. A buggy/hostile authenticated daemon that ignores the server-side fan-out gate (pyrycode#722) still cannot push a `queue_state` to a non-interactive phone (fail-closed, defence in depth). Asserted by the gate tests. The ADR-025 "viewing is ungated for any paired phone" statement is the *authorization* model (no per-device answer-gate, unlike modals) and concerns the **outbound** `dequeue_message` of #462 — it does not loosen this inbound decode gate.
- **[Error messages, logs, telemetry]** No findings — `decodeQueueState` logs nothing on the drop path, matching every other `onInbound` arm; no payload, id, error, or `text` reaches Logcat/Timber/crash reporters. `QueuedMessage.text` is **user-authored queued-message content** (potentially sensitive) and is carried **verbatim, never logged** — the same discipline #385/#387 apply to `assistant_delta` / tool summaries. The data layer surfaces only a `List`; no error type is exposed to callers.
- **[Concurrency]** No findings — `queuedByConversation` has a single writer (the lone `init` inbound collector), so snapshots cannot race; `update {}` (not check-then-`.value=`) avoids any TOCTOU on the `StateFlow`. `observeQueue` is a **cold** per-collector `map` + `distinctUntilChanged` projection (not a subscriber-shared hot flow), so there is no cross-screen state leak. Lifetime is connection-scoped and cancelled with the connection scope (#279/#302) — no coroutine outlives its owner.
- **[Network & I/O]** No findings — rides the already-authenticated Noise channel and the existing single-consumer `pump.inbound`; adds no socket, timeout, or frame-size decision. **Memory posture:** state is a `Map<conversationId, List<QueuedMessage>>`. Per-conversation it is **bounded by replacement** — each `queue_state` *overwrites* the prior backlog for that id (not an accumulation), so a flood of snapshots for one conversation grows nothing. The backlog size itself is the daemon's `msgqueue` cap (server-authoritative, not phone-controlled here). A daemon fabricating distinct conversation ids could grow the map's key set — the **same** posture the existing `lastMessages` / `messagesByConversation` / `stalledConversations` maps already accept against daemon-supplied ids under the authenticated-paired-daemon threat model; `queue_state` adds no new boundary. Not a MUST FIX (consistent with accepted design); any per-connection conversation cap belongs across all four projections, not this one alone. The per-item `text` is daemon-relayed but ultimately the user's own queued input echoed back; it is never executed, parsed, or persisted here.
- **[Tokens/secrets, File/storage, IPC/Android surface, Cryptographic primitives]** Not applicable — this ticket adds no token/credential handling, no filesystem or storage operation, no exported component / deep link / `PendingIntent`, and no cryptographic primitive. It is an in-process decode + state projection behind the existing Noise transport. `queued_msg_id` is a plain per-conversation counter, **not** a nonce/secret (pyrycode#720 § Security note), so no constant-time-compare or RNG concern applies.
- **[Threat model alignment]** Mobile UI-leakage threats (screenshot/overlay/accessibility eavesdropping of the rendered backlog, which contains user message text) are out of scope for this **data** slice and belong to #461 (the visible render); named here so #461 picks them up. The wire-shape, capability-gate, and verbatim-no-log threats are addressed above.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-23
