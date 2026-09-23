# #890 — decode `model_announced` and `session_facts` into per-conversation readings

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ResettingProjection.kt` → `ResettingProjection` — the
  nearest analogue: a `Map<String, T>` reading per conversation, `apply` behind the gate, `clear` from the
  `session_transition` arm, `observe` as `.map { it[id] }.distinctUntilChanged()`. Both new projections copy
  its shape.
- `app/src/main/java/de/pyryco/mobile/data/repository/ApiRetryProjection.kt`, `CompactingProjection.kt` →
  the decode-or-drop idiom (`try`/`catch (IllegalArgumentException)`, caught throwable discarded, nothing logged).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s
  `when (envelope.type)` arms (`TYPE_RESETTING`, `TYPE_SESSION_TRANSITION`), the projection fields, the
  `observeResetting` override, the `TYPE_*` constants in the companion.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeResetting` default
  and `ResetStatus` — where the contract and domain type sit.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`,
  `observeResetting` — the facade must override every cold read or the reading is stranded behind it; its
  `flatMapLatest` is what clears on reconnect and host switch.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → delegates by
  `ConversationRepository by delegate`, so it forwards the new reads with no edit.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `RateLimitedPayloadDto` (the
  `truncated_fields` nullable-with-default posture and the SECURITY KDoc wording), `UnrecognizedMessagePayloadDto`
  (a strict `truncated: Boolean`).
- `app/src/test/.../RemoteConversationRepositoryTest.kt` → the `resetting_*` tests and envelope helpers;
  `StableConversationRepositoryTest.kt` → `observeResetting_*` and `RecordingConversationRepository`.
- `../pyrycode/docs/protocol-mobile.md` § `model_announced`, § `session_facts` — the wire contract; cited, not
  restated. The daemon always writes `"truncated_fields":null` when nothing was cut (turnbridge outbound tests).
- `docs/knowledge/features/api-retry-status.md` — the facade-default lesson: a read that is not on the
  interface is stranded behind `StableConversationRepository`.

## Design source

**Figma:** N/A — data layer only; no UI in this ticket (the rendering is a separate ticket).

## Context

The daemon sends two per-turn frames from claude's `system/init` line that mobile does not decode. This
ticket makes both readable per conversation, so a later UI ticket can show what claude says is running.
The announced model is a separate value from `SessionSettings.model`, and `session_facts.permission_mode`
is a separate value from the confirmed permission reading (#650); nothing here touches either.

## Design

### Domain types (in `ConversationRepository.kt`, beside `ResetStatus`)

```kotlin
data class AnnouncedModel(val model: String, val truncated: Boolean)
data class SessionFacts(val claudeCodeVersion: String, val permissionMode: String, val truncatedFields: List<String>?)
```

Both are `data` classes so `distinctUntilChanged` works by value. Every `String` is claude-authored and
held verbatim. The KDoc marks each as untrusted text to render as inert text only, and states that neither
is the saved override or the confirmed permission mode.

### Contract (on `ConversationRepository`)

- `fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = flowOf(null)`
- `fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = flowOf(null)`

The interface defaults give the fake and every inline test double `null` with no override.

### Wire DTOs (in `InteractivePayloads.kt`)

- `ModelAnnouncedPayloadDto(conversationId, model, truncated: Boolean)` — all strict-required.
  `toReading(): AnnouncedModel?` returns `null` for an empty `model`, the contract's "never empty" rule.
- `SessionFactsPayloadDto(conversationId, claudeCodeVersion, permissionMode, truncatedFields: List<String>? = null)`
  — the three strings strict-required, `truncated_fields` nullable with a default like `RateLimitedPayloadDto`
  (the daemon always writes the key; `MobileJson`'s `explicitNulls = false` already collapses an omitted key
  to `null`). `toFacts(): SessionFacts` is a total verbatim copy; empty strings are valid.

### Projections (two new files, one per wire event, the repository's convention)

`AnnouncedModelProjection` and `SessionFactsProjection`, each with:

- a private `MutableStateFlow<Map<String, T>>`;
- `apply(envelope)` — decode, map, and `update { it + (id to reading) }` (latest wins, a plain replace);
- `clear(conversationId)` — `update { it - conversationId }`;
- `observe(conversationId): Flow<T?>` — `.map { it[conversationId] }.distinctUntilChanged()`;
- a private decoder with one `try`/`catch (IllegalArgumentException)` that returns `null` and discards the throwable.

### Routing (in `RemoteConversationRepository`)

- Constants `TYPE_MODEL_ANNOUNCED = "model_announced"`, `TYPE_SESSION_FACTS = "session_facts"`.
- Two `onInbound` arms, each calling its projection's `apply` behind `CAPABILITY_INTERACTIVE`.
- The `TYPE_SESSION_TRANSITION` arm clears both projections for the decoded conversation id, next to
  `resettingProjection.clear`.
- `observeAnnouncedModel` / `observeSessionFacts` overrides delegate to the projections.
- Neither arm touches the stall, thread, turn, settings or live-event state.

### Facade (in `StableConversationRepository`)

Two overrides via `switchToLive<T?>(null)`. A reconnect or host switch publishes a fresh repository (#351),
and `flatMapLatest` drops the old one's reading, so both clears come from existing structure.

## State + concurrency model

No new coroutine. Each projection's map is written only from the repository's single inbound collector,
so apply and clear never race; `update {}` matches the sibling posture. `observe` is a cold projection of
a `StateFlow`, so a new collector gets the current reading at once. State is connection-scoped.

## Error handling

A missing required field, a wrong type, or an empty `model` drops that one envelope. The collector
survives, the prior reading stands, and nothing is logged. There is no exception path to the UI.

## Testing strategy

A new unit test file `RemoteConversationRepositoryRunReadingsTest.kt`, since `RemoteConversationRepositoryTest`
is 10k lines. It holds its own envelope helpers and uses `FakeSessionPump`. Scenarios:

- `model_announced`: `null` before a frame, then the verbatim reading with `truncated` both ways; the value keeps
  surrounding whitespace, an odd case and a non-list id unchanged.
- latest wins: a second frame with a different model replaces the first.
- `session_facts`: verbatim fields, empty strings accepted, `truncated_fields` as `null` and as a list, an
  unrecognised `permission_mode` passes through.
- isolation: a frame for `c2` leaves `c1` unchanged and emits nothing on `c1`.
- clearing: a `session_transition` for `c1` clears both readings for `c1` and leaves `c2` standing.
- malformed input: missing fields, wrong types and empty `model` are dropped, the prior reading stands, and a later valid frame lands.
- the capability gate: a closed gate decodes neither frame.
- no side effects: neither frame touches the stall state, the thread or `observeSessionSettings`, and
  `session_facts` leaves the settings reading's permission mode unchanged.

In `StableConversationRepositoryTest`: `null` while absent, delegation, and a connection switch drops the
reading. It covers reconnect and host switch; `RecordingConversationRepository` gains two push helpers.

The fake needs no test: the interface default supplies `null`, and the facade's absent-state test uses the
same default. This is not an operator-facing flow, so there is no rung-3 scenario.

## Documentation handoff

The ticket has no Documentation handoff section. The documentation stage should add the two readings to the
live-stream overview (`docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md`) and
`conversation-repository.md`'s read list. **Pending for the documentation stage.**

## Open questions

- None blocking. The daemon uses a JSON `null` for `truncated_fields`, and the DTO follows the
  `RateLimitedPayloadDto` precedent.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Each new frame has one decode boundary in its projection's private decoder.
  The untrusted DTO stays `internal` to `data/`. Consumers get `AnnouncedModel` / `SessionFacts`, whose KDoc
  marks the strings as untrusted claude-authored text to render as inert text only. Nothing uses them in a URL,
  attribute, filename, cache key, log line or code path. The length bound comes from the daemon at construction
  and from the transport frame cap in `OkHttpRelayTransport`, the `RateLimitedPayloadDto` precedent. The
  `truncated` / `truncated_fields` flags carry the cut, so no client cap is added.
- [Trust boundaries] SHOULD FIX, covered by a test. `session_facts.permission_mode` is claude's claim. The plan
  keeps it out of `SessionSettings` and the #650 confirmed-permission reading. Phase B must prove that a
  `session_facts` frame leaves `observeSessionSettings` unchanged.
- [Tokens / secrets] No findings. The ticket adds no token, key or credential.
- [File / storage] No findings. The readings are in memory and scoped to one connection. Nothing is persisted,
  and `CachingConversationRepository` caches thread rows only.
- [Android attack surface] No findings. The ticket adds no component, intent, deep link or WebView.
- [Crypto] No findings. The ticket adds no cryptography and does not change the Noise session.
- [Network & I/O] No findings. The ticket changes no transport. A hostile daemon can send a very high rate of
  frames, but each one only replaces a map entry, so memory grows at most with the number of conversation ids
  it names. The `session_transition`, `stall` and other `Map`-keyed readings have the same property.
- [Logs] No findings. Both decoders discard the caught throwable, because a kotlinx message can quote input.
  No new arm logs any field, including `conversation_id`.
- [Concurrency] No findings. The inbound collector is the only writer, and `update {}` does the write. No new
  scope or job is created. The observers are cold per-collector projections.
- [Threat model] OUT OF SCOPE. Sanitising control characters and escape sequences at render time belongs to
  the UI ticket that renders these strings. The protocol puts that duty on the client render boundary, and
  this data layer holds the value verbatim, as the ticket requires.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
