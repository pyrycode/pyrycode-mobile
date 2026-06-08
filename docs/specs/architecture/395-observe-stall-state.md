# Spec #395 — Observe the `stall` v2 wire event as a thread-observable stall state

**Ticket:** pyrycode-mobile #395 (`feat(data)`, `size:s`, `security-sensitive`)
**Split from:** #373 (data slice; the UI reaction — promoting the screen-snapshot action — is sibling #396, blockedBy this).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:190-308` — `onInbound` `when (envelope.type)` dispatch + `decodeLiveSessionEvent`. **This is the load-bearing edit site:** the live-session arm (`TYPE_TURN_STATE, …` at 265-276) is where stall *clearing* hooks in, and the new `TYPE_STALL` arm (stall *onset*) goes alongside it. Note the existing drop-without-crash idiom (`try { … } catch (IllegalArgumentException) { return }`).
- `RemoteConversationRepository.kt:119-142, 506` — the `projection` / `lastMessages` `MutableStateFlow` pattern and the **exact** per-conversation projection idiom (`observeLastMessage` = `lastMessages.map { it[id] }.distinctUntilChanged()`). `observeStall` mirrors this 1:1.
- `RemoteConversationRepository.kt:157-180` — the `liveSessionEvents` `SharedFlow` doc. **Read it to understand what you are NOT doing:** that surface is `replay=0`, concrete-only, deliberately *off* the interface. Stall is the opposite shape — current-value *state*, on the interface, facade-reachable (AC #4). Do not surface stall on `liveSessionEvents`.
- `RemoteConversationRepository.kt:720-808` — the `private companion object` const block; add `TYPE_STALL = "stall"` here next to the other v2 type strings.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-137` — the interface. Add `observeStall` near `observeLastMessage` (line 30). The `delete` (61) / `requestScreenSnapshot` (135) defaults show the "default impl so non-overriding impls inherit, no cascade" idiom to copy.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:56-72` — `switchToLive` + the `observeLastMessage` facade override. Add `observeStall` with `switchToLive(false) { … }`.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:1-63` — the `internal` live-session decode DTOs. **The file doc explicitly notes it carries multiple top-level types, so ktlint's single-class-filename rule does not apply** — add `StallPayloadDto` here, no new file.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:26-78` — confirms every variant exposes `val conversationId: String`. The clearing logic reads `event.conversationId` directly off the already-decoded event — no second decode.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:52` — `internal const val CAPABILITY_INTERACTIVE = "interactive"` (already imported into `RemoteConversationRepository`). The gate uses this.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1470-1720` — the **#385 interactive-event test idiom**: `RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`, `FakeSessionPump`, `pump.push(...)`, `runCurrent()`, the capability-gate tests (`emptySet()` / `setOf("something_else")` at 1573/1588). Stall tests follow the same harness exactly.
- `RemoteConversationRepositoryTest.kt:1950-2043` — the envelope-builder helpers (`turnStateEnvelope`, `assistantDeltaEnvelope`, …). Add a `stallEnvelope(conversationId)` builder in the same shape.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:245` — the `observeLastMessage` delegation test; mirror it for `observeStall` (delegates to live repo; `false` while no connection live).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:95-100` — confirms the thread ViewModel is constructed with `private val repository: ConversationRepository` (the `StableConversationRepository` facade, via Koin). This is the reachability proof for AC #4: putting `observeStall` on the interface makes it reachable by the #396 consumer through the same facade the thread already holds.

## Context

pyrycode#624 (+ server children #638/#639) shipped the **stall transport** server-side: tui-driver's one-shot `stall_detected` signal (`EventKindStallDetected`, v1.3.0) is bridged through the daemon to a v2 mobile `stall` wire type, fanned out **only to phones that advertise `interactive`** (#401, merged to `main`) and delivered as a **non-droppable control event** (a stall is never silently dropped). Per ADR-025 § Safe degradation, a stall means "claude may be waiting" — PTY quiet while not-idle and no JSONL progress, typically a screen-parser break — and the phone surfaces it over the structured session view.

This ticket adds the **mobile consumer**: decode the inbound `stall` envelope into a per-conversation, thread-observable stall *state* (onset and clearing) reachable through the repository surface the thread already observes. The UI reaction (promoting the always-available screen-snapshot action when stalled) is sibling #396, which depends on this.

**The one decisive wire fact, established before design** (ADR-025; server codebase doc #638): the `stall` payload is `{conversation_id}` only — the peer of `turn_state` minus the `state` field — and **tui-driver's stall signal has no clearing edge.** There is no `stall_cleared` wire type and there will not be one. Recovery is therefore *inferred mobile-side*: the next forward-progress event for the conversation ends the stall. This is exactly ADR-025's stated model ("the phone infers 'responding' from delta arrival").

## Design

The change extends the existing single-inbound-collector demux in `RemoteConversationRepository`. It introduces **no new class** and **no new file** — four production files, all additive edits:

| File | Edit |
|------|------|
| `data/network/InteractivePayloads.kt` | add `internal data class StallPayloadDto` |
| `data/repository/ConversationRepository.kt` | add `observeStall` interface method **with a default** |
| `data/repository/StableConversationRepository.kt` | add the facade `observeStall` override |
| `data/repository/RemoteConversationRepository.kt` | add the stall state field, the `observeStall` override, the `TYPE_STALL` onset arm, the clearing hook in the live-session arm, the `decodeStall` helper, the `TYPE_STALL` const |

### Key types & contracts

**1. Decode DTO** (network, `internal`, in `InteractivePayloads.kt`):

```kotlin
@Serializable
internal data class StallPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
```

No `toEvent()` mapper: a stall is **state**, not one of the five `LiveSessionEvent` streaming events. It never lands on `liveSessionEvents`. The strict required-`String` field is the fail-closed posture: a missing/wrong-typed `conversation_id` fails the structural decode (dropped), consistent with the sibling DTOs' contract documented at the top of the file.

**2. Interface method** (`ConversationRepository`), defined next to `observeLastMessage`:

```kotlin
/** Emits whether [conversationId] is currently stalled … Cold; re-emits on every change.
 *  Default flowOf(false): impls without an interactive wire (the fake, inline test doubles)
 *  inherit "never stalled" and need no override. */
fun observeStall(conversationId: String): Flow<Boolean> = flowOf(false)
```

The default is the cascade-avoidance lever (same as `delete` / `requestScreenSnapshot` / `recentWorkspaces`): `FakeConversationRepository` and every inline test double inherit `flowOf(false)` and compile unchanged. Only the facade and the remote impl override it. This is what keeps the edit fan-out bounded (verified via `codegraph_impact observeLastMessage`: interface-method impact reaches the three impls only; a default absorbs the implementer cascade and there is no consumer cascade — consumers gain a callable method, none are forced to change).

**3. Facade override** (`StableConversationRepository`), mirroring `observeLastMessage`:

```kotlin
override fun observeStall(conversationId: String): Flow<Boolean> =
    switchToLive(false) { it.observeStall(conversationId) }
```

`whenAbsent = false`: with no live connection the phone reports "not stalled". This also gives the correct **reconnect** behaviour — each connection builds a fresh `RemoteConversationRepository` with an empty stall set (#351), and `flatMapLatest` drops the prior connection's stall flow, so a stall never survives a reconnect (a reconnected stream re-derives state from live events).

**4. Remote impl** (`RemoteConversationRepository`):

- New connection-scoped field, mirroring `lastMessages`:
  ```kotlin
  private val stalledConversations = MutableStateFlow<Set<String>>(emptySet())
  ```
  Membership = stalled. A `Set` makes onset/clear/idempotency trivial and naturally per-conversation.
- Override:
  ```kotlin
  override fun observeStall(conversationId: String): Flow<Boolean> =
      stalledConversations.map { conversationId in it }.distinctUntilChanged()
  ```
  1:1 with `observeLastMessage`. `distinctUntilChanged` means a stall change to *another* conversation does not re-emit this flow.

### Data flow — onset and clearing (both inside `onInbound`)

```
pump.inbound (single collector, wire order)
   │
   ├─ stall {conversation_id}            ──[gate: interactive]──> decodeStall → stalledConversations += id   (ONSET, AC#1)
   │
   └─ turn_state | assistant_delta |     ──[gate: interactive]──> decodeLiveSessionEvent(env)?.let { ev ->
        tool_use | tool_result | turn_end                            stalledConversations -= ev.conversationId   (CLEAR, AC#2)
                                                                      mutableLiveSessionEvents.tryEmit(ev)  // unchanged #385 behaviour
                                                                    }
```

- **Onset** — new `TYPE_STALL` arm. Gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, identically to the live-session arm: a non-interactive phone that receives a spurious `stall` from a buggy/hostile daemon **never decodes it and never surfaces it** (fail-closed; defence in depth on top of the server-side gate). On a clean decode: `stalledConversations.update { it + conversationId }`.
- **Clearing** — folded into the **existing** live-session arm. Any successfully decoded `LiveSessionEvent` is forward progress, so it ends the stall for `event.conversationId`: `stalledConversations.update { it - event.conversationId }`. This covers all five event types (including `turn_state: idle` and `turn_end` — both mean the "quiet while not-idle, no progress" condition no longer holds). No new decode: the `conversationId` is already on the decoded event. Clearing rides the same `interactive` gate (it lives inside the gated arm), so onset and clearing are symmetric.

### What clears, precisely

Clearing keys off a **successfully decoded** `LiveSessionEvent`. Two non-clearing cases, both deliberate and conservative:
- A *malformed* live-session envelope decodes to `null` (no trustworthy `conversationId` to route a clear) → does not clear. Correct: we cannot attribute forward progress we couldn't parse.
- An *unrecognized* `turn_state` value (e.g. a future `"compacting"`) maps to `null` (`TurnStatePayloadDto.toEvent()` returns null for unknown states) → does not clear. Correct: the stall persists until a recognized forward-progress event arrives, rather than guessing semantics of an unknown state.

The `stall` envelope itself carries no `state`/recovery flag (it is `{conversation_id}` only), so there is no "self-clearing" stall variant to handle — re-receipt of `stall` for an already-stalled conversation is an idempotent `Set` add.

## State + concurrency model

- **Single writer.** `stalledConversations` is written **only** from `onInbound`, which runs on the one long-lived inbound collector coroutine launched in `init` on the connection-scoped `scope`. Onset and clearing are both on that coroutine, processed in wire arrival order — there is no cross-coroutine race between them. `MutableStateFlow.update {}` is still used (not `.value =`) to match the sibling projections' memory-visibility posture and keep the writers uniform.
- **Connection-scoped, in-memory.** A fresh repository per connection starts with an empty set; the state is lost on connection drop / process death and re-derived from the live stream on reconnect. This is the intended lifetime — a stall is a transient "right now" condition, not durable state.
- **Cold fan-out.** `observeStall` is a cold `map`+`distinctUntilChanged` projection of the one `StateFlow`; N collectors share the single inbound consumer (same multiplexing guarantee as `observeConversations` / `observeLastMessage`). A fresh collector receives the current value (`false` until a stall lands) on subscription.
- **No new coroutine, no dispatcher choice.** Pure in-process state folding on the existing collector; no `viewModelScope`, no IO/Default switch. The facade adds no scope (it `flatMapLatest`es on the consumer's scope, unchanged).

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Malformed `stall` payload (missing / wrong-typed `conversation_id`) | `decodeStall` | `SerializationException ⊂ IllegalArgumentException` caught → `null` → the one envelope dropped, **single inbound collector survives** (AC #3). A later valid `stall` still flips state — proves the collector wasn't torn down. |
| `stall` on a non-interactive connection | `TYPE_STALL` gate | Dropped before decode; never surfaces (fail-closed). |
| Malformed / unrecognized live-session envelope | `decodeLiveSessionEvent` (existing) | `null` → neither surfaces on `liveSessionEvents` nor clears a stall. Unchanged #385 behaviour. |

`decodeStall(envelope): String?` mirrors `decodeLiveSessionEvent`: one `try { decode → conversationId } catch (IllegalArgumentException) { null }`. **Nothing logs the payload** — consistent with the whole `onInbound` posture (the `conversation_id` is low-sensitivity, but the no-log idiom is uniform and there is no diagnostic need to break it). The UI surfaces the stall as a banner/affordance (#396); the data layer surfaces only a `Boolean`, never an error.

## Testing strategy

Unit only — `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"` (and the `StableConversationRepositoryTest`). Pure data layer: no Compose, no device, no instrumented test. Test-first (RED → GREEN). Reuse the #385 harness verbatim: `FakeSessionPump`, `RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`, `pump.push(...)`, `runCurrent()` (per the established idiom — `advanceUntilIdle()` does not deliver the channel item; there are no timers). Add one `stallEnvelope(conversationId)` builder beside `turnStateEnvelope`.

Scenarios (bullets, not full bodies — developer writes them in the project idiom):

- **Onset flips on (AC #1, #5).** interactive repo; collect `observeStall("c1")`; push `stallEnvelope("c1")` → emissions go `false → true`.
- **Round-trip on→off (AC #2, #5 — the explicit "flips on then off").** stall `"c1"` → `true`; then push `turnStateEnvelope("c1", "thinking")` → `false`.
- **Each forward-progress event clears.** one test per type (or a parameterized set): after a stall, a `turn_state:responding`, `turn_state:idle`, `assistant_delta`, `tool_use`, `tool_result`, `turn_end` for `"c1"` each clears it. (`idle` and `turn_end` clearing is the deliberate contract — assert it.)
- **Malformed stall dropped, collector survives (AC #3).** push a `stall` with no/blank/wrong-typed `conversation_id` → no crash, stays `false`; then a valid `stallEnvelope("c1")` still flips `true` (proves the single inbound consumer is alive).
- **Capability gate (fail-closed).** `negotiatedCapabilities = { emptySet() }` and `{ setOf("something_else") }`: push `stallEnvelope("c1")` → stays `false`, never surfaces. (Mirror the #385 gate tests.)
- **Per-conversation isolation.** stall `"c1"` → `observeStall("c2")` stays `false`. Clearing routes by id: stall `"c1"` and `"c2"`; a forward event for `"c1"` clears only `"c1"`, `"c2"` stays `true`.
- **`distinctUntilChanged`.** stalling `"c2"` does not re-emit `observeStall("c1")`.
- **Facade delegation (`StableConversationRepositoryTest`, mirror `observeLastMessage`).** `observeStall` delegates to the live repo; emits `false` while `currentRepository` is `null`; a new connection switches to the new repo's stall flow and a prior stall does not leak across the switch.

(`FakeConversationRepository` needs no change — it inherits the `flowOf(false)` default; an optional one-liner test asserting the fake never stalls is fine but not required, since the facade `whenAbsent=false` path already covers "never stalled".)

## Open questions

1. **Does `idle`/`turn_end` clearing match the #396 UX?** The data-layer contract is the conservative, faithful one: *any* forward-progress event ends the stall, because the stall condition (quiet-while-not-idle) no longer holds. If #396 wants a distinct "finished" vs "recovered mid-turn" presentation, that is a UI-layer derivation over `observeStall` + the existing `liveSessionEvents`/turn state — **not** a data-layer change here. Flag to the #396 architect, don't pre-build it.
2. **Unknown future `turn_state` values during an active turn.** They decode to `null` today and so do not clear (documented above). Acceptable now; revisit only if the daemon starts emitting forward-progress states the mapper doesn't recognize *while a stall is live* — that would be a `LiveSessionEvent` mapper concern (shared with #386), not a stall concern.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings — the untrusted network→process boundary is a single explicit point: `decodeStall(envelope): String?` in `RemoteConversationRepository`, decoding `StallPayloadDto` through the one configured `MobileJson`, mirroring the existing `decodeLiveSessionEvent` boundary. Downstream holds only a parsed `String` (the conversation id) and a `Boolean` stall flag — no raw payload escapes. The boundary is *narrower* than the sibling events: `stall` carries no free-form text fields (no `text`/`summary`/`stop_reason`), so there is no verbatim-sensitive-content surface to mishandle.
- **[Network & I/O]** No findings — rides the already-authenticated Noise channel and the existing single-consumer `pump.inbound`; adds no socket, no timeout surface, no frame-size decision. Memory posture: stall state is a `Set<String>` of conversation ids (membership, not accumulation); repeated `stall` for the *same* conversation is an idempotent add and grows nothing, and each entry holds no free-form text (contrast `liveSessionEvents`, whose unbounded-text risk #385 handled with `DROP_OLDEST` — stall has no such surface). A daemon flooding *distinct* fabricated conversation ids could grow the set, but that is the **same** growth posture the existing `lastMessages` / `messagesByConversation` per-conversation maps already accept against daemon-supplied ids, under the same authenticated-paired-daemon threat model — stall adds no new boundary and carries the lightest per-conversation footprint of the three (one id vs a `Message` / a message list). Not a MUST FIX (consistent with accepted design); a hard per-connection cap on tracked conversations, if ever wanted, belongs across all three projections, not stall alone.
- **[Error messages, logs, telemetry]** No findings — `decodeStall` logs nothing on the drop path, matching every other `onInbound` arm; no payload, id, or error reaches Logcat/Timber/crash reporters. The data layer surfaces only a `Boolean`; no error type is exposed to callers.
- **[Concurrency]** No findings — `stalledConversations` has a single writer (the lone `init` inbound collector), so onset and clearing cannot race; `update {}` (not check-then-`.value=`) avoids any TOCTOU on the `StateFlow`. The flow is cold (per-collector `map`+`distinctUntilChanged`), not a subscriber-shared hot flow, so no cross-screen state leak. Lifetime is connection-scoped and cancelled with the connection scope (#279/#302) — no coroutine outlives its owner.
- **[Trust boundaries / capability gate]** No findings — onset is gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, so a buggy/hostile daemon that ignores the server-side fan-out gate still cannot push a `stall` to a non-interactive phone (fail-closed, defence in depth). This is asserted by the gate tests.
- **[Tokens/secrets, File/storage, IPC/Android surface, Cryptographic primitives]** Not applicable — this ticket adds no token/credential handling, no filesystem or storage operation, no exported component / deep link / `PendingIntent`, and no cryptographic primitive. It is an in-process decode + state projection behind the existing Noise transport.
- **[Threat model alignment]** Mobile UI-leakage threats (screenshot/overlay/accessibility eavesdropping of a stall banner) are out of scope for this data slice and belong to #396 (the visible reaction); named here so #396 picks them up. The wire-shape and capability-gate threats are addressed above.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
