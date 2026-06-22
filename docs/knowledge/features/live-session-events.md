# Live-session events — the v2 structured-stream decode seam

The **decode boundary** that turns the five v2 **binary → phone** structured-stream wire envelopes
into one typed, in-process Kotlin event family the thread UI can consume without ever touching wire
bytes. Landed in [#385](../codebase/385.md) (split from #368), part of the Phase 2
structured-streaming exit-gate (pyrycode#596, ADR 025).

> The `LiveSessionEvent` family later gained one **control-derived** member that does **not** pass
> through this decode seam: `ReplayGap` ([#417](../codebase/417.md)), surfaced by the
> [`RemoteConversationRepository`](remote-conversation-repository.md) `resync` arm (not a decoded wire
> envelope). The decode seam below still concerns exactly the **five** render envelopes; see
> [§ The `ReplayGap` member](#the-replaygap-member-417).

This is **decode only** — wire → typed events. Everything semantic is downstream and out of scope:
correlating `tool_use`↔`tool_result`, accumulating `assistant_delta` text, the turn-lifecycle state
machine, and rendering all belong to the consumer slices (#386 thinking indicator, #387 tool-use
timeline, [#337] live assistant text).

## What the daemon sends

Once a phone advertises `interactive` and the daemon echoes it in `hello_ack` (see
[#401](../codebase/401.md) and the [Noise session pump](noise-session-pump.md)), the daemon delivers
five structured envelopes **only to that phone** (pyrycode#607 wire types, #616 capability-gated
fan-out). A non-`interactive` phone never receives them and keeps getting the coarse `message`
fan-out. The five:

| Wire `type` | Payload fields (all required, no `omitempty`) | Meaning |
|---|---|---|
| `turn_state` | `conversation_id`, `state` | coarse turn lifecycle (`thinking`/`responding`/`idle`); **no `turn_id`** |
| `assistant_delta` | `conversation_id`, `turn_id`, `seq`(int), `text` | incremental, coalesced assistant text |
| `tool_use` | `conversation_id`, `turn_id`, `tool_use_id`, `name`, `input_summary` | a tool invocation |
| `tool_result` | `conversation_id`, `turn_id`, `tool_use_id`, `is_error`(bool), `result_summary` | its result (matched to the call by `tool_use_id`) |
| `turn_end` | `conversation_id`, `turn_id`, `stop_reason` | end of a turn |

Field shapes are the server SSOT (`pyrycode internal/protocol` interactive structs +
`docs/protocol-mobile.md § Interactive events (v2, capability-gated)`). Code review verified every
DTO `@SerialName` and type against it byte-for-byte.

## The three layers

```
Envelope.payload: JsonElement   (untrusted, post-Noise-decrypt)
        │  MobileJson.decodeFromJsonElement<…PayloadDto>
        ▼
…PayloadDto    (data/network/InteractivePayloads.kt — @Serializable, internal, strict non-null)
        │  .toEvent()
        ▼
LiveSessionEvent    (data/model/LiveSessionEvent.kt — public, portable, no Android imports)
        │  liveSessionEvents.tryEmit(it)
        ▼
val liveSessionEvents: SharedFlow<LiveSessionEvent>   (RemoteConversationRepository, concrete)
        ▼
consumer slices (#386 / #387 / #337)
```

### 1. DTOs — `data/network/InteractivePayloads.kt` (`internal`)

Five `@Serializable internal data class` DTOs, one per envelope, every field a **required non-null**
`String`/`Int`/`Boolean`, snake_case wire names mapped to camelCase via `@SerialName` (load-bearing
Go-interop, not cosmetic). Decode always through the single configured
[`MobileJson`](mobile-protocol-v2-wire-layer.md), never a default `Json`. The strict non-null shape
is the **fail-closed** posture for an untrusted boundary: a missing/wrong-typed field fails the
structural decode with a `SerializationException` rather than `null`-punning, so the caller drops
the one malformed envelope and keeps the stream alive. The DTOs stay `internal` to `data/network`
(only `LiveSessionEvent` crosses the package boundary). The file carries multiple top-level types,
so the [[ktlint-filename-rule-single-class]] does not constrain its name (cf. `MobileWireModels.kt`).

### 2. Event family — `data/model/LiveSessionEvent.kt` (public, portable)

One `sealed interface LiveSessionEvent` with a common `val conversationId: String` (every payload
carries it, so a consumer routes per-conversation without a `when`) and five `data class` subtypes
named **1:1** for the wire `type` strings — `TurnState`, `AssistantDelta`, `ToolUse`, `ToolResult`,
`TurnEnd`. The three-value phase enum is **nested** under `TurnState`:

```kotlin
data class TurnState(override val conversationId: String, val phase: Phase) : LiveSessionEvent {
    enum class Phase { Thinking, Responding, Idle }   // exactly the three documented states
}
```

Nesting the enum keeps the file at exactly **one** public top-level type
([[ktlint-filename-rule-single-class]]). Pure data, **zero imports**, no Android types — `data/`
stays portable per CLAUDE.md (Compose Multiplatform walk-back surface). The free-form strings
(`text`, `inputSummary`, `resultSummary`, `stopReason`) are carried **verbatim** — the seam never
trims, parses, or sanitizes them (see [Trust boundary](#trust-boundary--no-payload-logging)).

A **sixth** subtype, `ReplayGap`, joined the family in [#417](../codebase/417.md) — but it is
**control-derived**, not a decoded wire envelope, so it has no DTO and no `toEvent()` mapper. See
[§ The `ReplayGap` member](#the-replaygap-member-417).

### 3. Mappers — `…PayloadDto.toEvent()` (in `InteractivePayloads.kt`)

One `internal fun XxxDto.toEvent()` per DTO, the same `data/network → data/model` direction as
`MessagePayloadDto.toMessage()`. Four are total field copies returning a non-null event.
`TurnStatePayloadDto.toEvent()` is **nullable**: it maps `state → Phase` via a private
`String.toPhase()` and returns `null` for an unrecognized value. That nullability is the whole
reason `state` is a plain `String` in the DTO instead of a strict serialized enum — see below.

## How it surfaces — the gate, the flow, the drop

The seam rides the **single existing** `pump.inbound` collector in
[`RemoteConversationRepository`](remote-conversation-repository.md) — no second subscription (ticket
constraint). One grouped arm joins the `onInbound` `when (envelope.type)` demux, before `else`:

```kotlin
TYPE_TURN_STATE, TYPE_ASSISTANT_DELTA, TYPE_TOOL_USE, TYPE_TOOL_RESULT, TYPE_TURN_END -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {            // AC#2 gate, before decode
        decodeLiveSessionEvent(envelope)?.let { mutableLiveSessionEvents.tryEmit(it) }
    }
}
```

- **Capability gate (AC#2).** `negotiatedCapabilities: () -> Set<String>` is a **supplier**
  defaulted to `{ emptySet() }`, read **per envelope**. Wired by the
  [coordinator](relay-repository-coordinator.md) from the live pump:
  `{ (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() }`. It is a supplier, not a
  captured value, because the repo is constructed while the pump is still `Handshaking` (set empty),
  but structured envelopes only arrive **post-`Open`** — so the lazy per-envelope read always sees
  the final, connection-constant negotiated set. The read is `pump.state.value`, a **non-suspending**
  `StateFlow.value` access, so the coordinator's `onConnection` cancellation-atomicity invariant is
  preserved (no suspension added to its critical section). The gate is checked **before** decode, so
  a non-`interactive` phone never even decodes the payload — defense-in-depth against a daemon that
  ignores the negotiated set.
- **The flow.** `val liveSessionEvents: SharedFlow<LiveSessionEvent>` —
  `MutableSharedFlow(replay = 0, extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)`. A
  `SharedFlow` not a `StateFlow` because these are *events*, not current-value state: late
  subscribers get no history (holding "latest" is a consumer concern). The bounded buffer +
  `DROP_OLDEST` make `tryEmit` **infallible and non-blocking** — the load-bearing invariant is that
  a slow live-event consumer must never back-pressure the shared inbound collector and stall the
  connection's `conversations`/`message`/`ack` processing. Delivery is therefore best-effort under
  extreme backpressure; a consumer needing lossless accumulation ([#337]) owns its own buffering.
- **The drop (AC#3 + AC#4).** `decodeLiveSessionEvent()` selects the DTO by `envelope.type`, decodes
  through `MobileJson`, calls `toEvent()`, all wrapped in one `try/catch (IllegalArgumentException)`.
  `SerializationException ⊂ IllegalArgumentException`, so a malformed/partially-decodable payload
  (missing or wrong-typed field) yields `null` and the one envelope is dropped — the lone collector
  survives, the next envelope is processed normally ("one bad envelope does not drop the next").

### Unknown / absent `turn_state.state` (AC#3)

`turn_state.state` has a closed value set (`thinking`/`responding`/`idle`) the seam must map, but AC
requires **tolerating** unrecognized/absent values rather than failing the stream. Two paths, both
converging on drop-without-crash:

- **Unrecognized** value (e.g. `"compacting"`): the DTO field is a plain `String`, decode succeeds,
  `toEvent()`'s `String.toPhase()` returns `null` → the mapper returns `null` → that one envelope is
  dropped.
- **Absent** `state`: the required `String` field makes *decode itself* throw → caught as AC#4 →
  dropped.

There is deliberately **no `Phase.Unknown`** member — surfacing one would push a "what does Unknown
mean" decision onto consumers and widen the type surface. If a consumer later needs to distinguish
"server sent a state I don't grok" from "no event", add `Unknown` then (open question, out of scope
here). This is why `state` is a `String` in the DTO and not a strict serialized enum: a strict enum
([`WireRole`](mobile-protocol-v2-wire-layer.md) style) would make an unknown value a *decode*
failure, conflating "unknown state" with "malformed envelope" — correct when an unknown value *is* a
protocol error, wrong here. See the pattern note in [#385](../codebase/385.md).

## Trust boundary & no-payload-logging

`security-sensitive`. The single explicit boundary is `decodeLiveSessionEvent()`: untrusted
`Envelope.payload` JSON → strict `internal` DTOs → typed `LiveSessionEvent`. Consumers only ever
hold typed events, never un-decoded payload. The boundary runs **behind** the already-authenticated,
AEAD-encrypted Noise channel ([#298](../codebase/298.md)/[#303](../codebase/303.md)) — bytes are
integrity-protected and from the paired daemon — but the decode is still strict (fail-closed) as
defense-in-depth against a buggy/compromised daemon.

- **No payload logging — load-bearing.** `assistant_delta.text`, `tool_use.input_summary`, and
  `tool_result.result_summary` can carry sensitive user/session content (code, paths, command
  output). **Nothing in this seam logs decoded fields or raw payloads**, including the malformed-drop
  branch (a bare `catch { null }`). Grep-confirmed zero `Log`/`println`/`print`/`Timber` on
  payload-derived data, mirroring the existing `TYPE_MESSAGE`/`screen_snapshot` no-log posture. Any
  future change here must preserve this.
- **Rendering consumers own output-encoding.** The strings are carried verbatim and uninterpreted —
  the rendering slices (#386/#387/#337/#388) **must treat them as inert data, not
  markup/HTML/markdown-with-active-content**, and own sanitization at render time. This slice renders
  nothing.
- **DoS posture.** The `SharedFlow` buffer is **bounded** (`DROP_OLDEST`, capacity 64) and `tryEmit`
  never blocks — a hostile/buggy daemon flooding `assistant_delta` can neither grow unbounded memory
  at the seam nor stall the shared inbound collector. `seq: Int` is decoded but **never sizes an
  allocation or indexes an array** at this seam (it's an opaque ordering hint for consumers).
- **No cross-connection leak.** The flow is connection-scoped (the repo is rebuilt per connection by
  the [coordinator](relay-repository-coordinator.md), #351); a new connection gets a fresh repo +
  fresh flow, so prior-connection events cannot reach a new connection's collectors.

Architect self-review verdict **PASS**; code review **PASS** with zero findings.

## Why on the concrete repo, not the interface

`liveSessionEvents` is on `RemoteConversationRepository` only — **not** on the
[`ConversationRepository`](conversation-repository.md) interface. Adding it there would force
`FakeConversationRepository` to implement it and
[`StableConversationRepository`](stable-conversation-repository.md) to delegate it (+2 prod files,
tripping the ≥5 split gate) for plumbing this decode slice doesn't use. This is the accepted
[`registerPushToken`](remote-conversation-repository.md) ([#359](../codebase/359.md)) pattern: a
non-interface capability on the concrete repo, reached by the consumer through a concrete handle (the
coordinator holds the repo). Surfacing it through a coordinator seam for the UI ViewModels was
**consumer-slice wiring**, deferred to #386/#387/#337 — now realized in
[#406](../codebase/406.md): the [coordinator](relay-repository-coordinator.md) exposes a generic,
reconnection-surviving `liveSessionEvents: Flow<LiveSessionEvent>` over the connection-scoped concrete
repo (`activeRemoteRepo.flatMapLatest { it?.liveSessionEvents ?: emptyFlow() }`), and
[`ThreadViewModel.isThinking`](turn-state-thinking-flag.md) is its first consumer. See
[[post-352-connection-scoped-repo-behind-facade]].

## Scope boundary

In scope: wire → typed events, the capability gate, fail-closed strict decode. Out of scope (named
for consumers): `tool_use`↔`tool_result` correlation, `assistant_delta` accumulation, the
turn-lifecycle state machine, rendering/sanitization, lossless delivery (consumer adds its own
`buffer()`/`stateIn`), and an `event_id` replay cursor (reconnect-replay #402) / a `stall`
type (#395) — both correctly excluded here. (The replay cursor's *gap* signal did later join the family
as the control-derived `ReplayGap` member — #417 — but via the resync arm, not this decode seam; see
[§ The `ReplayGap` member](#the-replaygap-member-417). `stall` correctly stayed out, landing as state.)

> **`tool_use`↔`tool_result` correlation landed in [#387](../codebase/387.md).** It is the first
> *consumer* of the `ToolUse`/`ToolResult` events: the [Live tool-call](live-tool-call.md) slice
> correlates the pair (by `toolUseId`) into one evolving `Role.Tool` thread row carrying a status
> (`Running → Done`/`Failed`), folded into `messagesByConversation` on the **same gated demux arm** (a
> `when (event)` dispatch alongside the #395 stall-clear and the `tryEmit` — no second subscription).
> The events are carried **verbatim** into the row's `ToolCall` fields; output-encoding remains the
> rendering consumer's job (#388). The turn-state phase reduction is [#406](../codebase/406.md)'s
> `isThinking`; live assistant-delta accumulation landed in [#337](../codebase/337.md) — see below.

> **`assistant_delta` accumulation landed in [#337](../codebase/337.md).** Unlike the tool-row
> ([#387](../codebase/387.md), folds into `data/`) and the stall flag, the
> [Streaming assistant turns](streaming-assistant-turns.md) slice consumes this seam at the **VM**:
> `ThreadViewModel` folds `liveSessionEvents` together with the #313 `observeMessages` projection
> (`merge → scan → render`) so an in-flight turn's `AssistantDelta` text accumulates (in `seq` order,
> per `turnId`, conversation-scoped) into one growing `isStreaming` `MessageItem` that settles into the
> finished `message` on `TurnEnd`. The verbatim text is **only concatenated** into `Message.content` and
> routed through the existing [`MessageBubble`](message-bubble.md) (#184) render — no new sink, no
> logging, honoring the verbatim-untrusted-text contract above.

> **The sixth type — `stall` (#395) — landed as state, not a `LiveSessionEvent`.** It is still
> correctly **not** one of these five streaming events (it is current-value state, not an event), so it
> never lands on `liveSessionEvents`. But [#395](../codebase/395.md) is now a **consumer** of this seam
> in the other direction: every successfully decoded `LiveSessionEvent` is forward progress, so it
> **clears** any active [stall](stall-state.md) for `event.conversationId` (the `stall` wire is
> onset-only, so recovery is inferred from these events — all five clear, incl. `turn_state: idle` and
> `turn_end`). The clearing hook is folded into the same gated demux arm, reading `conversationId` off
> the already-decoded event (no second decode). See [Stall state](stall-state.md).

> **A sibling decode family — [Modal events](modal-events.md) (#437) — mirrors this seam on its own
> flow.** The two `modal_shown`/`modal_dismissed` envelopes decode through the **same** three-layer
> pattern (`internal` DTOs in `InteractivePayloads.kt` → `toEvent()` mappers → a portable sealed family),
> on the **same** single inbound collector behind the **same** `interactive` gate, with the **same**
> fail-closed `try/catch (IllegalArgumentException)` drop. But they are deliberately **not** a sixth
> `LiveSessionEvent`: modal payloads carry **no `conversation_id`** (`modalId` is the sole correlation
> key), whereas every member here mandates `conversationId` and the demux routes on it — so they form
> their own [`ModalEvent`](modal-events.md) family on their own concrete-only
> [`modalEvents`](remote-conversation-repository.md#modalevents--the-v2-permissionchoice-modal-decode-seam-437)
> flow. Two further contrasts worth noting when adding a new interactive event: the modal mappers are
> **total** (`class`/`source`/`outcome` carried **verbatim** as `String`, never coerced to an enum that
> drops a forward-compat value — the inverse of `turn_state.state`'s nullable mapper above), and the modal
> arm **decode-and-emits only** — it does **not** clear a stall (a `modal_shown` means `claude` is
> *waiting* for input, not forward progress).

## The `ReplayGap` member (#417)

[#417](../codebase/417.md) added a sixth member, `data class ReplayGap(override val conversationId:
String)`, that carries **only** the conversation the gap concerns — no turn / event / text content. It
is **not** one of the five render envelopes and does **not** flow through the decode seam above:

- **Control-derived, not wire-decoded.** It is surfaced by the
  [`RemoteConversationRepository`](remote-conversation-repository.md) `resync` arm — the reaction to the
  daemon's `resync` marker (the phone's advertised [replay cursor](replay-cursor.md) position aged out of
  the daemon's bounded ring, so gap-free in-ring replay was impossible). The arm `tryEmit`s a `ReplayGap`
  onto **this same** `liveSessionEvents` `SharedFlow` — preferring the existing surface over a parallel
  channel — but there is **no `ReplayGapDto`** and no `toEvent()` (the resync marker is a payload-less
  inline `{conversation_id}` struct; the arm reads it structurally). See
  [Remote conversation repository § the resync arm](remote-conversation-repository.md#the-resync-arm--reset-the-cursor--surface-the-gap-417).
- **An observable signal a UI layer can later render** (e.g. a "messages may be missing" affordance) —
  this slice does not render it. The current consumer, [`ThreadViewModel`](turn-state-thinking-flag.md),
  **ignores** it: it is added to the `→ null` / `→ this` ignore-groups of the two exhaustive
  `when (event)` blocks (`thinkingTransition` / `reduceLive`) so the build stays green and the future
  rendering consumer is *forced* to handle it consciously (the `when`s deliberately keep no `else`).
- **Stable for Compose** (a `data class` with a single `String` field) — consumers that later render it
  stay skippable. Carries no verbatim user/tool text, so it is outside the no-payload-logging concern
  above (there is nothing sensitive to log).

## Related

- [#385 implementation notes](../codebase/385.md) — files, line refs, lessons.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the flow + the demux.
- [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the **consumer in the other direction**:
  a decoded `LiveSessionEvent` clears a stall for its conversation, folded into the same gated arm.
- [Live tool-call](live-tool-call.md) ([#387](../codebase/387.md)) — the **`tool_use`/`tool_result`
  consumer**: correlates the pair into one status-carrying `Role.Tool` thread row, dispatched on the
  same gated arm.
- [Relay repository coordinator](relay-repository-coordinator.md) — wires the capability supplier, and
  ([#406](../codebase/406.md)) surfaces the reconnection-surviving `liveSessionEvents` seam that brings
  these events to UI ViewModels.
- [Turn-state thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) — the first
  consumer: reduces `TurnState` to `ThreadViewModel.isThinking`.
- [Streaming assistant turns](streaming-assistant-turns.md) ([#337](../codebase/337.md)) — the
  **`AssistantDelta`/`TurnEnd` consumer**: accumulates an in-flight turn into one growing `isStreaming`
  thread row (VM-layer fold with the #313 finished-message projection).
- [Replay cursor](replay-cursor.md) ([#417](../codebase/417.md)) — the **`ReplayGap` producer**: the
  `resync` arm `reset()`s the cursor and `tryEmit`s the control-derived `ReplayGap` onto this flow (no
  DTO, no decode). See [§ The `ReplayGap` member](#the-replaygap-member-417).
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the **sibling decode family**: the same
  three-layer pattern + single-collector gated demux arm + fail-closed drop, on its own
  [`modalEvents`](remote-conversation-repository.md#modalevents--the-v2-permissionchoice-modal-decode-seam-437)
  flow (modal payloads carry no `conversation_id`, so not a sixth member here).
- [Noise session pump](noise-session-pump.md) — surfaces `PumpState.Open.capabilities` (#401), the
  gate source.
- [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) — `MobileJson`, `Envelope`,
  `@SerialName` Go-interop, the `WireRole` strict-enum contrast.
- Server SSOT: pyrycode#607 (wire types + capabilities), #616 (capability-gated fan-out), ADR 025
  § Phase 2 structured streaming, EPIC pyrycode#596.

[#337]: https://github.com/pyrycode/pyrycode-mobile/issues/337
