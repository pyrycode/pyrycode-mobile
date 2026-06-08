# Spec — #385: decode v2 structured live-session stream envelopes

Decode the five v2 **binary → phone** structured-stream envelopes (`turn_state`, `assistant_delta`,
`tool_use`, `tool_result`, `turn_end`) into one typed in-process event family, surfaced as a hot
flow on the concrete `RemoteConversationRepository`, gated on the negotiated `interactive`
capability. **Decode seam only** — no correlation, no accumulation, no turn-lifecycle state machine,
no rendering. Those are consumer slices (#386 thinking indicator, #387 tool correlation, #337 live
assistant text).

Split from #368. Prerequisite #401 (advertise + surface `interactive`) has landed — the negotiated
set is already on `PumpState.Open.capabilities`. Follows the established v2 decode-slice pattern
(#316 / #317 / #318 / #374).

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` — **the closest precedent.**
  `MessagePayloadDto` (DTO with `@SerialName` snake_case mapping) + `toMessage()` mapper +
  `WireRole` enum (how a wire string maps to a closed Kotlin set). Mirror this file's shape for the
  five new DTOs and their mappers. Note: `WireRole` rejects unknown values at *decode* (throws);
  `turn_state.state` must NOT — read AC#3 handling in Design § 3 for the difference.
- `app/src/main/java/de/pyryco/mobile/data/network/SnapshotPayload.kt` — decode-only DTO precedent
  (#374): `@Serializable data class`, strict non-null fields, no Android imports, doc comment naming
  the wire SSOT. The new file follows this style.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:134-222` —
  the `init` single-consumer collector and the `onInbound(envelope)` `when (envelope.type)` demux.
  This is the integration point: one new grouped arm + one decode helper land here. Read the
  existing `TYPE_MESSAGE` / `TYPE_MESSAGE_CHUNK` arms for the try/catch-drop idiom (AC#4).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:67-81` —
  the constructor (note the defaulted `deviceName: String = ""` param; the new
  `negotiatedCapabilities` supplier param lands beside it, same defaulted-so-tests-compile shape).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:634-708` —
  the companion `TYPE_*` constants. Five new constants join here.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:135-149` —
  `onConnection()`: the single production construction site of `RemoteConversationRepository`. This
  is where the capability supplier is wired from the live `ManagedSessionPump`. **Non-suspending
  critical section** — do not add a suspension point here (see the class doc at :128-134).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:287-306` — `PumpState`
  sealed interface and `PumpState.Open.capabilities: Set<String>` (the #401 gate source).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:46-52` —
  `internal const val CAPABILITY_INTERACTIVE = "interactive"`. The membership token the gate checks.
  `internal` ⇒ reachable from `data/repository` (same module).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:141-169` —
  `ThreadItem` sealed interface + nested `data class`es + sibling enums. The sealed-family +
  nested-type idiom `LiveSessionEvent` mirrors.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` (the
  single configured `Json`; `ignoreUnknownKeys = true`). All decode goes through it.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1719-1752`
  — the `FakeSessionPump` (`Channel`-backed `inbound`, `push()`, togglable `send`) + companion
  helpers. The new tests extend this harness; the fake stays a plain `SessionPump`, the capability
  set is injected via the new constructor supplier (no `state` needed on the fake).
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — confirm the `data/model/` package is
  Android-free (it is); `LiveSessionEvent` lands here and must stay portable (CLAUDE.md).
- Memory: **ktlint filename rule** — a `.kt` file whose single public top-level type doesn't match
  the filename fails the build. `LiveSessionEvent.kt` has exactly one public top-level type (the
  sealed interface; the enum is nested). The DTO file has *multiple* top-level types → filename is
  free (cf. `MobileWireModels.kt`).
- Server wire SSOT: pyrycode `internal/protocol/interactive.go` + `testdata/{turn_state,
  assistant_delta,tool_use,tool_result,turn_end}.json` and `docs/protocol-mobile.md § Interactive
  events (v2, capability-gated)` (landed in pyrycode#607). Field shapes are inlined in Design § 1
  below — no need to fetch them.

---

## Context

Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The server now emits
five structured **binary → phone** envelopes, delivered **only** to a phone whose `interactive`
capability was echoed in `hello_ack` (pyrycode#607 wire types, #616 capability-gated fan-out). The
phone currently collapses everything to the coarse `message` fan-out; an `interactive` phone gets
the structured stream so the thread UI can render a thinking indicator, a tool-use timeline, and
incremental assistant text.

This slice lands the **decode boundary**: untrusted `Envelope.payload` JSON → one typed event
family, surfaced to in-process consumers. Everything semantic — correlating `tool_use` ↔
`tool_result`, accumulating `assistant_delta` text, driving a turn-lifecycle state machine, the UI —
is downstream and out of scope.

---

## Design

### Surface summary

| Item | Location | New/Mod | Visibility |
|------|----------|---------|------------|
| `LiveSessionEvent` sealed family (+ nested `TurnState.Phase` enum) | `data/model/LiveSessionEvent.kt` | new | public |
| 5 payload DTOs + 5 `toEvent()` mappers | `data/network/InteractivePayloads.kt` | new | `internal` |
| `liveSessionEvents` flow + demux arm + decode helper + 5 `TYPE_*` + capability supplier param | `data/repository/RemoteConversationRepository.kt` | mod | mixed |
| wire the capability supplier from the live pump | `data/repository/RelayRepositoryCoordinator.kt` | mod | — |

Four production files (2 new, 2 mod), one new public type. The DTOs are `internal` (the ticket's
"raw decode DTOs stay internal to `data/network`"). The flow is exposed on the **concrete**
repository, **not** on the `ConversationRepository` interface — see § 5.

### 1. Payload DTOs — `data/network/InteractivePayloads.kt` (new)

Five `@Serializable internal data class` DTOs, one per envelope, every field a required non-null
`String`/`Int`/`Boolean` (the server emits all fields always — no `omitempty`), snake_case wire
names carried by `@SerialName`. Decode through `MobileJson` only. Field contract (wire SSOT,
pyrycode#607):

```
TurnStatePayloadDto    { conversation_id: String; state: String }
AssistantDeltaPayloadDto { conversation_id; turn_id: String; seq: Int; text: String }
ToolUsePayloadDto      { conversation_id; turn_id; tool_use_id: String; name: String; input_summary: String }
ToolResultPayloadDto   { conversation_id; turn_id; tool_use_id; is_error: Boolean; result_summary: String }
TurnEndPayloadDto      { conversation_id; turn_id; stop_reason: String }
```

Notes pinned by the SSOT:
- `turn_state` carries **no** `turn_id` (only `conversation_id` + `state`); the other four do.
- `state` is a plain wire string, allowed `"thinking" | "responding" | "idle"`. Modeled as `String`
  in the DTO (not an enum) — the strict-enum approach (`WireRole`) would make an unrecognized state
  a *decode* failure, but AC#3 requires unrecognized/absent state to be **tolerated**, which is a
  mapper concern, not a decode concern. See § 3.
- `seq` is a per-turn non-negative ordering counter; `Int` matches the package's count-field idiom
  (`MessageChunkPayloadDto`-adjacent `max_messages`). Modeled but **not used to size any allocation
  at this seam** (consumer concern).
- `stop_reason` stays a plain `String` (wire values `end_turn | max_tokens | max_turn_requests |
  refusal | cancelled`); consumers map it. No enum — the ticket is decode-only and AC names only
  `state` for value-mapping.

### 2. Event family — `data/model/LiveSessionEvent.kt` (new, portable)

One sealed interface, five `data class` subtypes named for the wire types, a common
`conversationId` (every payload carries it — lets consumers route per-conversation without `when`),
and the three-value phase enum nested under the `TurnState` subtype (so the file has exactly one
public top-level type — ktlint). Contract:

```kotlin
sealed interface LiveSessionEvent {
    val conversationId: String

    data class TurnState(override val conversationId: String, val phase: Phase) : LiveSessionEvent {
        enum class Phase { Thinking, Responding, Idle }   // exactly the three documented values (AC#3)
    }
    data class AssistantDelta(override val conversationId: String, val turnId: String, val seq: Int, val text: String) : LiveSessionEvent
    data class ToolUse(override val conversationId: String, val turnId: String, val toolUseId: String, val name: String, val inputSummary: String) : LiveSessionEvent
    data class ToolResult(override val conversationId: String, val turnId: String, val toolUseId: String, val isError: Boolean, val resultSummary: String) : LiveSessionEvent
    data class TurnEnd(override val conversationId: String, val turnId: String, val stopReason: String) : LiveSessionEvent
}
```

Pure data, no Android imports (CLAUDE.md portability). Subtype names mirror the wire `type` strings
so the demux reads 1:1. `stopReason`/`inputSummary`/`resultSummary`/`text` are carried verbatim —
the seam does not trim, parse, or sanitize them (decode fidelity; sanitization is a rendering
concern, see Security review § Trust boundaries).

### 3. Mappers — `data/network/InteractivePayloads.kt` (with the DTOs)

One `internal fun XxxDto.toEvent()` per DTO producing the matching `LiveSessionEvent` (the same
`data/network` → `data/model` mapper direction as `MessagePayloadDto.toMessage()`):

- Four of them are total field copies returning a non-null `LiveSessionEvent`.
- `TurnStatePayloadDto.toEvent(): LiveSessionEvent?` is **nullable**: it maps `state` →
  `Phase` via a private `String.toPhase(): Phase?` (`"thinking"→Thinking`, `"responding"→Responding`,
  `"idle"→Idle`, `else → null`). A `null` phase means an **unrecognized** state value → the mapper
  returns `null` → the demux drops that one envelope, stream survives (AC#3). An **absent** `state`
  field is caught one layer up at decode (the required `String` field → `SerializationException`),
  also dropped (AC#3 + AC#4). Both "unrecognized" and "absent" converge on drop-without-crash.

The output phase set is exactly `{Thinking, Responding, Idle}` — no `Unknown` member. Surfacing an
`Unknown` would push a "what does Unknown mean" decision onto the consumer and widen the type
surface; for a pure decode seam, dropping the unmappable envelope is the minimal, defensive choice.
Recorded as an open question in case a consumer later wants explicit unknown-state visibility.

### 4. Demux + surface — `RemoteConversationRepository.kt` (mod)

**New hot flow.** A `private val mutableLiveSessionEvents = MutableSharedFlow<LiveSessionEvent>(...)`
exposed as `val liveSessionEvents: SharedFlow<LiveSessionEvent>`. Config:
`replay = 0` (events, not state — late subscribers do not replay; holding "latest" is a consumer
projection concern), a modest `extraBufferCapacity` (≈ 64), `onBufferOverflow =
BufferOverflow.DROP_OLDEST`. This makes `tryEmit` **infallible and non-blocking** — the load-bearing
invariant is that the single shared `pump.inbound` collector must never be stalled by a slow
live-event consumer (a `SUSPEND` buffer would let one consumer back-pressure the whole connection's
`conversations`/`message`/`ack` processing). Delivery is therefore best-effort under extreme
backpressure; a consumer needing lossless delivery owns its own buffering (open question).

**New constructor param.** `negotiatedCapabilities: () -> Set<String> = { emptySet() }`, placed
after `deviceName`. A **supplier**, not a value: the repository is constructed while the pump is
still `Handshaking`, but structured envelopes only arrive after `Open`, so the gate must read the
capability set **lazily at envelope-arrival time**. The default `{ emptySet() }` means "gate closed"
— existing two- and three-arg constructions (tests, the coordinator before wiring) compile and
behave unchanged; structured events are simply never surfaced until the supplier is wired.

**New demux arm** in `onInbound`'s `when (envelope.type)`, before the `else -> Unit`:

```kotlin
TYPE_TURN_STATE, TYPE_ASSISTANT_DELTA, TYPE_TOOL_USE, TYPE_TOOL_RESULT, TYPE_TURN_END -> {
    // AC#2: gate on the negotiated capability — ignore (never decode, never surface) without it.
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        decodeLiveSessionEvent(envelope)?.let { mutableLiveSessionEvents.tryEmit(it) }
    }
}
```

**New private helper** `decodeLiveSessionEvent(envelope): LiveSessionEvent?` — a single
`when (envelope.type)` selecting the DTO, decoding via `MobileJson.decodeFromJsonElement<…>`, and
calling `.toEvent()`, the whole body wrapped in one `try { … } catch (e: IllegalArgumentException)
{ null }`. `SerializationException ⊂ IllegalArgumentException`, so a missing/wrong-typed field
(malformed envelope, AC#4) and an unmappable turn_state both yield `null` → dropped, the lone
collector survives and the next envelope is processed normally ("one bad envelope does not drop the
next", AC#4). Behavior copies the existing `TYPE_MESSAGE` arm's drop idiom; **nothing here logs the
payload** (text / tool summaries may be sensitive — see Security review).

**Five new `TYPE_*` constants** in the companion (`TYPE_TURN_STATE = "turn_state"`, etc.), matching
the existing `TYPE_MESSAGE` style.

### 5. Why the flow is on the concrete repo, not the interface

`liveSessionEvents` is exposed on `RemoteConversationRepository` only — **not** added to the
`ConversationRepository` interface. Rationale:

- Adding it to the interface forces `FakeConversationRepository` to implement it and
  `StableConversationRepository` to delegate it → +2 production files → 6 total → trips the ≥5
  split gate. Decode-only work should not pay for interface/facade/fake plumbing it doesn't use.
- This is the **exact accepted pattern of `registerPushToken` (#359)**: a non-interface capability
  on the concrete repo, reached by the consumer through a concrete handle (the coordinator holds
  `repo` in `onConnection`). See `post-352-connection-scoped-repo-behind-facade`.
- Reachability for UI consumers (#386/#387/#337) — surfacing `liveSessionEvents` through the
  `StableConversationRepository` facade, or via a new coordinator seam — is a **consumer-slice
  wiring concern**, deferred. AC#1 ("surfaced through the repository") is satisfied by the concrete
  property; the facade question is downstream.

### 6. Capability wiring — `RelayRepositoryCoordinator.kt` (mod)

`onConnection()` builds the repo with `pump: ManagedSessionPump` (which exposes
`state: StateFlow<PumpState>`). Pass the supplier that snapshots the live negotiated set:

```kotlin
val repo = RemoteConversationRepository(
    pump, childScope, deviceName,
    negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() },
)
```

One added argument; **no new suspension point** (a `.value` read is non-suspending, preserving the
`onConnection` cancellation-atomicity invariant at :128-134). The supplier reads the *current* pump
state on each structured envelope; once `Open`, capabilities are connection-constant, so every
structured envelope (which can only arrive post-`Open`) sees the final negotiated set. This is the
single bridge from the coordinator-owned `ManagedSessionPump.state` to the repo's gate — the
`SessionPump` interface the repo consumes stays unchanged (no interface widening, per #401's
no-interface-change posture).

### Data-flow diagram

```
pump.inbound ─┐  (single consumer, RemoteConversationRepository.init)
              ▼
        onInbound(envelope)
              │  when(type)
   ┌──────────┴───────────────────────────────────────┐
   │ turn_state / assistant_delta / tool_use /         │
   │ tool_result / turn_end                            │
   │   ├─ CAPABILITY_INTERACTIVE in negotiatedCaps()?  │  no → ignore (AC#2)
   │   │      yes ▼                                     │
   │   ├─ decodeLiveSessionEvent(envelope)             │  malformed/unknown → null → drop (AC#3/#4)
   │   │      LiveSessionEvent ▼                        │
   │   └─ mutableLiveSessionEvents.tryEmit(it)         │  non-blocking, DROP_OLDEST
   └───────────────────────────────────────────────────┘
              │
   liveSessionEvents: SharedFlow<LiveSessionEvent>  ──►  (consumer slices #386/#387/#337)
```

---

## State + concurrency model

- **One consumer, unchanged.** No new collector — the five arms hang off the existing single
  `pump.inbound` collector in `init`. No second subscription (ticket constraint).
- **`SharedFlow`, not `StateFlow`.** Live-session events are a *stream*, not a current-value state.
  `replay = 0` + bounded `extraBufferCapacity` + `DROP_OLDEST`. Hot, fans out to N collectors off
  the one inbound consumer.
- **Non-blocking emission.** `tryEmit` from the non-suspending `onInbound`. With `DROP_OLDEST` it
  always succeeds (never rejects, never suspends) — the shared inbound collector is never stalled.
- **Lifecycle.** The flow lives on the connection-scoped repository; it dies with the connection
  (the repo is rebuilt per connection by the coordinator, #351). No process-global scope. Late
  subscribers get no history (`replay = 0`) — by design.
- **Gate read.** `negotiatedCapabilities()` reads `pump.state.value` (a `StateFlow.value` volatile
  read) per structured envelope; negligible cost even at `assistant_delta` token frequency.

## Error handling

| Failure | Layer | Result |
|---------|-------|--------|
| Malformed / partially-decodable payload (missing/wrong-typed field) | `MobileJson.decodeFromJsonElement` → `SerializationException` (⊂ `IllegalArgumentException`) | caught in `decodeLiveSessionEvent` → `null` → envelope dropped, stream survives, next envelope processed (AC#4) |
| `turn_state.state` unrecognized | `TurnStatePayloadDto.toEvent()` → `null` | envelope dropped, stream survives (AC#3) |
| `turn_state.state` absent | required-field decode → `SerializationException` | dropped (AC#3 via AC#4 path) |
| Structured envelope without `interactive` negotiated | gate `if` | ignored, never decoded, never surfaced (AC#2) |
| Buffer overflow (extreme backpressure) | `SharedFlow` `DROP_OLDEST` | oldest undelivered event dropped; collector never stalled |

No failure tears down the connection or the inbound collector — the entire seam fails closed and
silent. **No `catch` logs the payload.**

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), JUnit4 + `runTest`, extending the existing
`FakeSessionPump` harness in `RemoteConversationRepositoryTest.kt`. The fake stays a plain
`SessionPump`; the capability set is injected via the new `negotiatedCapabilities` constructor
supplier (no `state` needed on the fake — that's the whole point of the supplier shape). Pattern:
construct repo with `negotiatedCapabilities = { setOf("interactive") }`, collect `liveSessionEvents`
into a list on `backgroundScope`, `pump.push(envelope)`, `runCurrent()`, assert. Add envelope-builder
helpers (`turnStateEnvelope`, `assistantDeltaEnvelope`, …) mirroring the existing `messageEnvelope`
helper (raw-JSON payload via `MobileJson.parseToJsonElement`).

Scenarios (each a bullet → one test):

- **turn_state → TurnState(Thinking/Responding/Idle).** Push each of the three states; assert the
  surfaced `LiveSessionEvent.TurnState.phase` and `conversationId`. (Covers AC#1, AC#3 happy path.)
- **assistant_delta → AssistantDelta.** Push with `seq:0` and non-empty text; assert all four fields
  including the `seq == 0` boundary.
- **tool_use → ToolUse.** Assert `toolUseId`, `name`, `inputSummary`, `turnId`, `conversationId`.
- **tool_result → ToolResult.** Push with `is_error:false`; assert `isError == false` boundary +
  `resultSummary`. Push a second with `is_error:true`; assert `true`.
- **turn_end → TurnEnd.** Push `stop_reason:"end_turn"`; assert verbatim `stopReason` (a non-mapped
  string passes through unchanged — e.g. also assert an arbitrary `"some_future_reason"` survives).
- **Capability gate blocks (AC#2).** `negotiatedCapabilities = { emptySet() }`; push a well-formed
  `turn_state`; assert **no** emission (list stays empty after `runCurrent()`).
- **Capability gate allows (AC#2).** `{ setOf("interactive") }`; same envelope; assert one emission.
  (Optionally: a supplier returning a set *without* interactive but with another token still blocks.)
- **Malformed envelope dropped, next survives (AC#4).** Push a `tool_use` with a missing required
  field (e.g. no `tool_use_id`), then a valid `assistant_delta`; assert only the delta surfaces
  (the malformed one produced no emission, the stream was not torn down).
- **Unrecognized turn_state tolerated (AC#3).** Push `state:"compacting"`; assert no emission and
  that a subsequent valid envelope still surfaces.
- **Fan-out / interleave (optional, strengthens AC#1).** Push the full five-envelope sequence for
  one turn; assert the surfaced list is the five typed events in push order. Optionally a second
  collector receives the same stream (hot fan-out).

No instrumented tests (no Android, no UI in this slice). No coordinator test change required — the
one-line capability-supplier wiring introduces no behavior observable through the coordinator's
public surface; its correctness (reads `PumpState.Open.capabilities`) is covered structurally by the
repo-level gate tests using the same supplier shape.

## Open questions

- **`SharedFlow` overflow policy / buffer size.** `DROP_OLDEST` + 64 keeps emission non-blocking but
  makes delivery best-effort; under sustained `assistant_delta` flood with a slow consumer, old
  deltas drop (would corrupt naive text accumulation). Acceptable for a decode seam — the consumer
  (#337) owns lossless accumulation and can `buffer()`/`stateIn` as needed. Flagged so the consumer
  slice makes a deliberate choice rather than inheriting a silent default. If lossless turns out to
  be required at the seam, revisit (e.g. a `Channel`-backed bridge).
- **No `Unknown` turn-state phase.** Unrecognized/absent `state` drops the envelope rather than
  surfacing `Phase.Unknown`. If a consumer needs to distinguish "server sent a state I don't grok"
  from "no event," add an `Unknown` member then — out of scope here.
- **Facade reachability for UI consumers.** `liveSessionEvents` is on the concrete repo only; the
  `StableConversationRepository` facade / a coordinator seam that exposes it to ViewModels is
  consumer-slice work (#386/#387/#337), like `registerPushToken`'s live-caller wiring.

---

## Security review

**Verdict:** PASS

This ticket is `security-sensitive`: it adds a new untrusted-input parse point (server JSON →
typed events). Walked every applicable category adversarially.

**Findings:**

- **[Trust boundaries]** No MUST FIX. The design has a **single explicit boundary**:
  `decodeLiveSessionEvent(envelope)` in `RemoteConversationRepository`, decoding through the one
  configured `MobileJson` into `internal` DTOs, then mapping to `LiveSessionEvent`. Untrusted data
  (`Envelope.payload: JsonElement`) never reaches a consumer un-decoded — consumers hold only typed
  `LiveSessionEvent`s. The boundary runs **behind** the already-authenticated, AEAD-encrypted Noise
  channel, so the bytes are integrity-protected and from the paired daemon; the decode is still
  strict (fail-closed) as defense-in-depth against a buggy/compromised daemon. The seam carries
  strings **verbatim** (`text`, `inputSummary`, `resultSummary`, `stopReason`) and does not
  interpret them — **the rendering consumers (#386/#387/#337) must treat these as inert data, not
  markup/HTML/markdown-with-active-content**; called out here so the consumer slices own
  output-encoding/sanitization at render time. (Documented as a hand-off, not a gap in this slice —
  this slice neither renders nor logs the values.)
- **[Tokens, secrets, credentials]** N/A — this slice handles no tokens/keys/credentials. The
  `interactive` capability string is non-secret (advertised in clear `hello`); `CAPABILITY_INTERACTIVE`
  membership is a feature gate, not an authz secret. No token compare, no storage.
- **[File / storage operations]** N/A — purely in-memory; no filesystem, no path construction, no
  persistence. `conversation_id` from the wire is carried as an opaque string and never used to
  build a path or filename at this seam.
- **[Inter-process / Android attack surface]** N/A — no Intents, deep links, exported components,
  PendingIntents, ContentProviders, or WebView. `data/model` + `data/network` + `data/repository`
  only; no Android surface added.
- **[Cryptographic primitives]** N/A — no RNG, no hashing, no key handling. Runs above the Noise
  transport, which #298/#303 own; this slice introduces no crypto.
- **[Network & I/O]** No MUST FIX. No new socket, no timeout surface (inherits the existing
  transport/pump). **DoS resistance:** the `SharedFlow` buffer is **bounded** (`extraBufferCapacity
  ≈ 64`, `DROP_OLDEST`) — a hostile/buggy daemon flooding `assistant_delta` cannot grow unbounded
  memory at the seam, and `tryEmit` never blocks the shared inbound collector, so a flood on the
  structured stream cannot stall `conversations`/`message`/`ack` processing for the connection.
  `seq: Int` is decoded but **never used to size an allocation or index an array** at this seam
  (would be an amplification vector); it is an opaque ordering hint passed to consumers. The
  per-envelope decode is O(payload size) with no quadratic blowup.
- **[Error messages, logs, telemetry]** No MUST FIX — and this is the **load-bearing** finding for a
  message-content path. `assistant_delta.text`, `tool_use.input_summary`, and
  `tool_result.result_summary` can contain sensitive user/session content (code, paths, command
  output). The design's drop paths (`catch (e) { null }`, the `null`-from-mapper, the gate `if`)
  **log nothing** — no `Log.*`, no payload in any exception message, mirroring the existing
  `TYPE_MESSAGE` / `screen_snapshot` no-log posture (`RemoteConversationRepository` "emits no logs").
  MUST be preserved in implementation: **no diagnostic logging of decoded fields or raw payloads**,
  including in the malformed-drop branch. Code-review should verify zero `Log`/`println`/`print`
  calls touch payload-derived data in the new code.
- **[Concurrency]** No MUST FIX. No new coroutine/scope — the seam rides the existing single
  connection-scoped `pump.inbound` collector, which the coordinator cancels on connection drop
  (#351 key-wipe + scope-cancel invariant). The hot `SharedFlow` is connection-scoped (rebuilt per
  connection), so **no cross-connection data leak**: a new connection gets a fresh repo + fresh
  flow; events from a prior connection cannot reach a new connection's collectors. No shared-state
  check-then-mutate (the flow is append-only via `tryEmit`; no `StateFlow` CAS added). The gate's
  `pump.state.value` read is a lock-free volatile snapshot. No new mutex.
- **[Threat model alignment]** The relevant threat — a malformed or hostile structured envelope from
  a (possibly compromised) paired daemon — is addressed by fail-closed strict decode (drop, never
  crash, never tear down the stream) + the capability gate (defense-in-depth: even if the daemon
  ignores the negotiated set and pushes structured events to a non-`interactive` phone, the phone
  ignores them). UI-surface threats (screenshot leakage of rendered tool output, accessibility
  eavesdropping) belong to the rendering consumer slices (#386/#387/#388) — **out of scope here**,
  named for the consumer to pick up. This slice surfaces typed events to an in-process flow and
  renders nothing.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
