# #945 — decode and request the context usage reading per conversation

## Files read

- `../pyrycode/docs/protocol-mobile.md` § `context_usage` and § "Asking for a context usage reading on demand" —
  the wire contract; cited, not restated. The reject table (`conversation.not_found`, `context_usage.unavailable`)
  and the `as_of` remembered-answer rule shape the design below.
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `ModelMenuProjection` — the nearest
  analogue for **ask on subscription**: `observe` is `.map { it[id] }.distinctUntilChanged().onStart { ask }`,
  the ask is fire-and-forget through the injected `send` / `negotiatedCapabilities` / `nextRequestId`, and a
  refused send is swallowed, never retried.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionFactsProjection.kt`, `AnnouncedModelProjection.kt`
  (#890) — the per-conversation reading shape: `MutableStateFlow<Map<String, T>>`, `apply` behind the gate,
  `clear` from the `session_transition` arm, the `try`/`catch (IllegalArgumentException)` decode-or-drop idiom
  that discards the throwable.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the
  `TYPE_SESSION_FACTS`, `TYPE_SESSION_TRANSITION` and `TYPE_ERROR` arms), the `modelMenuProjection` construction
  (the `nextRequestId = { relayRequests.nextRequestId() }` lambda), `observeSessionFacts`, the `TYPE_*` constants.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeSessionFacts` default
  (`flowOf(null)`) and the `SessionFacts` domain type.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`,
  `observeSessionFacts` — the facade must override every cold read or the reading is stranded behind it.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentRepository` is
  Open-gated (#421), so a reconnect re-subscribes the facade only once the new pump negotiated `interactive`;
  the ask's capability guard therefore sees the real set.
- `app/src/main/java/de/pyryco/mobile/data/network/ModelListPayloads.kt` → `RequestModelListPayloadDto` — the
  one-key request shape; `InteractivePayloads.kt` → `ModelAnnouncedPayloadDto.toReading` (a value reject on a
  structurally valid frame).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson` (`ignoreUnknownKeys = true`,
  so the undecoded inventories and `model` cost nothing).
- `app/src/test/.../RemoteConversationRepositoryRunReadingsTest.kt` — fixture shape (`FakeSessionPump`, `probe`,
  `collect`); `StableConversationRepositoryTest.kt` → `observeSessionFacts_*` tests and `RecordingConversationRepository`.

## Design source

**Figma:** N/A — data layer only; the footer display is a separate ticket.

## Context

The daemon publishes `context_usage` after every completed turn and answers `request_context_usage` with the same
frame. Mobile decodes neither. This ticket holds one scalar reading per conversation and asks for a fresh one while
somebody observes it, so the follow-up footer ticket has a value to show. The reading is distinct from
`SessionSettings`' transcript-derived `usedTokens` / `windowTokens`; nothing here reads or writes that.

## Design

### Domain type (in `ConversationRepository.kt`, beside `SessionFacts`)

```kotlin
data class ContextUsage(val totalTokens: Long, val maxTokens: Long, val percentage: Int, val asOf: Instant?)
```

`percentage` is Claude's own number, held verbatim and never derived. `asOf` non-null marks a remembered answer.
`data` so `distinctUntilChanged` works by value.

### Contract (on `ConversationRepository`)

`fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = flowOf(null)` — `null` is "unavailable",
never zero. The default keeps the fake and every inline double unchanged.

### Wire DTOs (new file `data/network/ContextUsagePayloads.kt`)

- `ContextUsagePayloadDto(conversationId, totalTokens: Long, maxTokens: Long, percentage: Int, asOf: String? = null)`
  — the four scalars strict-required with no defaults (so a missing one fails the decode rather than producing a
  zero); `as_of` optional per the contract. The inventories and `model` are not declared.
- `ContextUsagePayloadDto.toReading(): ContextUsage?` — `null` for a negative `percentage`; otherwise a verbatim
  copy with `asOf` through `Instant.parse` (a malformed timestamp throws `IllegalArgumentException`, caught by the
  decoder, so the frame drops).
- `RequestContextUsagePayloadDto(conversationId)` — the request body.

### Projection (new file `data/repository/ContextUsageProjection.kt`)

`internal class ContextUsageProjection(send, negotiatedCapabilities, nextRequestId)`, the `ModelMenuProjection`
constructor. State:

- `readingByConversation: MutableStateFlow<Map<String, ContextUsage>>` — latest frame wins.
- `observerCounts: ConcurrentHashMap<String, Int>` — how many collectors watch each conversation. This is what
  "while observed" means for the post-transition ask.

Surface:

- `apply(envelope)` — decode, map, replace the entry. Push and reply are the same shape and route by the payload's
  own `conversation_id`; `in_reply_to` is not consulted, so a reply naming B lands under B.
- `observe(conversationId)` — `map { it[id] }.distinctUntilChanged()`, `onStart` increments the count and asks when
  it went 0→1, `onCompletion` decrements (removing the key at 0).
- `onSessionTransition(conversationId)` — remove the reading, **then** ask if the count is above zero.
- private `ask(conversationId)` — guards: empty id, `interactive` not negotiated. Builds a `request_context_usage`
  envelope with `nextRequestId()`, sends it, swallows a `false` or a thrown send. No ledger, no correlation map,
  no retry.

**Rejects need no code.** A `context_usage.unavailable` or `conversation.not_found` arrives on the `TYPE_ERROR` arm
with an `in_reply_to` that matches neither `RelayRequests`' waiters nor `ModelMenuProjection`'s asks, so both
lookups are no-ops. Nothing writes the reading, nothing re-sends; the reading stays absent until the next
turn-end frame. The `TYPE_ERROR` arm is not edited.

### Routing (in `RemoteConversationRepository`)

- Constants `TYPE_CONTEXT_USAGE = "context_usage"`, `TYPE_REQUEST_CONTEXT_USAGE = "request_context_usage"`,
  `ERROR_CONTEXT_USAGE_UNAVAILABLE = "context_usage.unavailable"` (the last documents the reject; tests use it).
- A `contextUsageProjection` built like `modelMenuProjection`.
- `TYPE_CONTEXT_USAGE` arm: `apply` behind `CAPABILITY_INTERACTIVE`.
- `TYPE_SESSION_TRANSITION` arm: `contextUsageProjection.onSessionTransition(conversationId)` beside the #890 clears.
- `observeContextUsage` override delegating to the projection.

### Facade (in `StableConversationRepository`)

`observeContextUsage` via `switchToLive<ContextUsage?>(null)`. A reconnect publishes a new repository; `flatMapLatest`
cancels the old subscription (its `onCompletion` decrements the old count) and subscribes the new one, whose
`onStart` sends the reconnect ask. `CachingConversationRepository` delegates by interface and needs no edit.

## State + concurrency model

No new coroutine or scope. The reading map is written only from the repository's single inbound collector
(`apply`, `onSessionTransition`), so apply and clear never race. `observerCounts` is touched from collectors'
coroutines (`onStart` / `onCompletion`) and read from the inbound collector, hence `ConcurrentHashMap.compute`
for the increment and decrement; a read racing a subscription can at worst send one ask more or fewer at the same
instant, which the daemon collapses per conversation. `ask` is non-suspending, so the first emission is not delayed.
State is connection-scoped: a fresh repository per connection starts empty.

## Error handling

A structural decode failure, a negative `percentage` or a malformed `as_of` drops that one envelope: the collector
survives, any prior reading stands, nothing is logged, and no zero reading is fabricated. A send the transport
refuses is swallowed (no throw into the subscribing collector). Rejects leave the reading absent as above.
Nothing logs, matching every sibling projection: the only value worth logging is the conversation id, which the
repository treats as a cross-conversation correlation key.

## Testing strategy

New unit test file `RemoteConversationRepositoryContextUsageTest.kt` (the #890 fixture shape), scenarios:

- **push**: `null` until a frame; a turn-end frame (no `in_reply_to`) lands verbatim, including a `percentage` that
  disagrees with `total/max`; a second frame replaces it; `as_of` parses to an `Instant`.
- **reply**: a frame with `in_reply_to` = the ask's envelope id replaces the reading.
- **ask on subscription**: one `request_context_usage` naming the id; a second concurrent collector sends none;
  after both cancel, a new subscription sends again; a closed `interactive` gate sends nothing and decodes nothing;
  an empty id sends nothing.
- **malformed**: each scalar missing, wrong-typed or `null`, a negative `percentage`, a malformed `as_of` — all
  dropped, prior reading stands, no zero reading ever appears, a later valid frame lands.
- **reject**: `context_usage.unavailable` and `conversation.not_found` replies leave the reading `null` and send
  nothing more; a later push fills it.
- **session_transition**: clears the reading and sends a new request while observed; sends nothing for an
  unobserved conversation.
- **isolation**: a frame for `c2` never changes or re-emits `c1`; `c2`'s transition neither clears `c1` nor asks for it.
- **reconnect**: a `StableConversationRepository` over two `RemoteConversationRepository`s; switching the live one
  sends one ask on the new pump and drops the old reading.

`StableConversationRepositoryTest`: `observeContextUsage` emits `null` while absent, delegates, and a switch drops the
reading (`RecordingConversationRepository` gains a push helper).

No Compose test and no rung-3 scenario: this is a data-layer slice with no operator-facing flow; the footer ticket
owns that.

## Documentation handoff

The ticket has no Documentation handoff section. The documentation stage should add the reading and its ask to the
live-stream overview (`docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md`) and to
`conversation-repository.md`'s read list. **Pending for the documentation stage.**

## Open questions

- None blocking. Whether a reject should clear a reading that a push already filled: no — the contract gives a
  reject no invalidation meaning, and the design writes nothing on a reject.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. One decode boundary in `ContextUsageProjection`'s private decoder; the DTO stays
  `internal` to `data/`. Consumers get `ContextUsage`, which holds only numbers and a parsed `Instant`. Every
  claude-authored string on the frame (`model`, the inventory names, `server_name`, `path`, `type`) is left
  undeclared, so it is discarded at decode and cannot reach UI, logs, a file path or an actuation. `server_name`
  therefore cannot be fed back into `mcp_reconnect`.
- [Trust boundaries] SHOULD FIX, covered by a test. Routing is the payload's daemon-authored `conversation_id`, never
  `in_reply_to`, so a reply cannot cross-route into another conversation's reading. Phase B proves that a frame for
  `c2` leaves `c1` unchanged.
- [Trust boundaries] No findings. A hostile or buggy daemon sending a zero-valued frame produces a zero reading only
  if it actually sent zeros; a missing field never does (no DTO defaults), and a negative `percentage` drops.
- [Tokens / secrets] No findings. No token, key or credential is added.
- [File / storage] No findings. In memory, connection-scoped, never persisted; `as_of` is parsed, never used as a path.
- [Android attack surface] No findings. No component, intent, deep link, WebView or push path changes.
- [Crypto] No findings. No cryptography; the Noise session is untouched.
- [Network & I/O] No findings. The only new outbound frame is one small request per 0→1 subscription, per reconnect
  and per observed transition; there is no timer and no retry, so a rejecting daemon cannot drive a loop. A daemon
  flooding `session_transition` for an observed conversation draws one request per transition, the same
  amplification `bumpSettingsRevision` already accepts, and the daemon collapses them per conversation. Inbound
  size is bounded by the transport's frame cap.
- [Logs] No findings. The decoder discards the caught throwable (kotlinx messages can quote input); no branch logs
  the conversation id or any field.
- [Concurrency] No findings. No new scope or job. The reading map has one writer; `observerCounts` uses atomic
  `compute`, and the one race it leaves costs at most a duplicate or missing ask at a subscription instant.
- [Threat model] OUT OF SCOPE. Rendering the percentage (bounds, formatting) belongs to the footer ticket split
  from #591.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
