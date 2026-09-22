# #823 — Read and write a conversation's system prompt over the relay

## Files read

- `../pyrycode/docs/protocol-mobile.md` § "Setting a conversation's system prompt" and § "Reading a conversation's system prompt" — the wire contract (the SSOT; not restated here beyond what the design pins).
- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `RequestSessionSettingsPayloadDto`, `toSessionSettings`, `readEffectiveEffort` — the read-by-presence decode this ticket's reply decode mirrors (an absent key must stay distinct from a present value, and the thrown message names the key only).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the success-reply arm listing `TYPE_SESSION_SETTINGS`), `sendAndAwaitReply`, `mapError`, `readSessionSettings`, `rename`, `setSessionSettings`, the `TYPE_*` companion constants — the encode/await/decode shape both verbs follow, and the arm `system_prompt` must be registered in or its waiter hangs.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.requestHistory` / `setSessionSettings` (throwing defaults, so inline test doubles need no change), `SessionSettings` (where co-located domain types live).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `live`, `requestHistory` — the one-shot delegation pattern (throws `IllegalStateException` with no live repo).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `rename`, `unknown` — the in-memory mutation pattern and its not-found type.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `FakeSessionPump`, `startRename`, `errorEnvelope`, `conversationUpdatedEnvelope` — harness shapes; the file is ~8800 lines, so this ticket's remote tests go in a sibling test class with its own small pump.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` → `RecordingConversationRepository` — facade delegation test shape.

## Design source

N/A — data layer only; the ticket has no UI.

## Context

The daemon stores a per-conversation system prompt (`set_system_prompt`, pyrycode) and publishes a read (`request_system_prompt` → `system_prompt`, pyrycode #2152). Mobile has neither. This ticket adds the data-layer halves so #824's shared editing state (and, through it, #666 / #667) can read and save the prompt on the host that owns the channel. No UI, no ViewModel.

## Design

### Domain types (in `ConversationRepository.kt`, beside `SessionSettings`)

- `data class SystemPromptReading(val systemPrompt: String?, val sessionPromptStatus: SessionPromptStatus)` — `systemPrompt == null` means **no prompt stored** (wire: key absent); `""` is an explicitly empty prompt; any other string is the stored text, verbatim. The two fields are independent.
- `enum class SessionPromptStatus { Matches, Differs, NoSession }` — the three published values, nothing else.
- `object SystemPromptLimit` — the one public helper the editing state and modals reuse:
  - `const val MAX_BYTES = 8192`
  - `fun utf8Bytes(text: String): Int` — UTF-8 byte count.
  - `fun fits(text: String): Boolean` — `utf8Bytes(text) <= MAX_BYTES` (inclusive).

### Interface (`ConversationRepository`)

- `suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading` — default throws (`error(...)`), like `requestHistory`.
- `suspend fun setSystemPrompt(conversationId: String, systemPrompt: String?)` — `null` clears, `""` explicit empty, text verbatim. Default throws.

Both name a conversation, never a session; neither restarts or resets anything.

### Wire (`data/network/SystemPromptPayloads.kt`, new)

- `@Serializable data class RequestSystemPromptPayloadDto(@SerialName("conversation_id") val conversationId: String)` — encode-only.
- `fun setSystemPromptPayload(conversationId: String, systemPrompt: String?): JsonObject` — built with `buildJsonObject` rather than a DTO, because `MobileJson`'s `explicitNulls = false` would elide a `null` field. The clear is sent as an **explicit** `"system_prompt": null` (the protocol accepts null or omission; explicit is what the ticket pins and is unambiguous on the wire).
- `fun JsonElement.toSystemPromptReading(): SystemPromptReading` — the single validate boundary for the reply. Payload must be an object; `session_prompt_status` must be a string primitive naming one of `matches` / `differs` / `no_session`; `system_prompt` absent → `null`, a string primitive → its content verbatim (no trim), anything else (including an explicit JSON `null`, a number, an object) → throws. Throws `SerializationException` with static messages that name the key only — never the value, never its length.

### Remote (`RemoteConversationRepository`)

- New constants `TYPE_REQUEST_SYSTEM_PROMPT`, `TYPE_SYSTEM_PROMPT`, `TYPE_SET_SYSTEM_PROMPT`.
- `onInbound`: add `TYPE_SYSTEM_PROMPT` to the success-reply arm next to `TYPE_SESSION_SETTINGS`. The reply carries no `conversation_id`; it completes only the waiter whose request id it answers, so an unmatched one is a no-op.
- `requestSystemPrompt`: if `interactive` was not negotiated, throw `IllegalStateException` **before** building/sending a frame (the daemon would never answer). Otherwise build the envelope, `sendAndAwaitReply`, `toSystemPromptReading()`. No state is touched on success or failure.
- `setSystemPrompt`: `require(systemPrompt == null || SystemPromptLimit.fits(systemPrompt))` with a static message → `IllegalArgumentException` before any frame. Then encode, `sendAndAwaitReply`; the ack is `conversation_updated` (already routed to the waiter by its own arm) — decode it through `ConversationResponseDto` for shape validation and upsert the returned record exactly as `rename` does. `mapError` already maps `conversation.not_found` → `IllegalArgumentException` and every other code → `RelayErrorException`. Returns `Unit`.

### Stable facade

Both delegate to `live` verbatim — `IllegalStateException` when no repository is live.

### Fake (demo mode)

`private val systemPrompts = MutableStateFlow<Map<String, String>>(emptyMap())` — a missing key is "no prompt stored", a present value (including `""`) the stored text. `requestSystemPrompt` returns `SystemPromptReading(systemPrompts[id], NoSession)` for any id (an unknown one reads like a hosted one holding nothing, as the daemon answers). `setSystemPrompt` enforces the same byte limit, throws the fake's `unknown(...)` IAE for an unknown conversation, then removes the key on `null` or stores the value.

## State + concurrency model

Both calls are one-shot suspends on the caller's coroutine; no new flow, job or scope. The remote's only shared state touched is `pendingRequests` (via `sendAndAwaitReply`, which registers before sending and removes in `finally`; teardown sweeps it) and, on a successful write, the projection upsert `rename` already performs. Cancellation of the caller cancels the await and deregisters the waiter.

## Error handling

| Case | Result |
|---|---|
| Read on a conn without `interactive` | `IllegalStateException`, no frame sent |
| Pump not open / torn down mid-await | `IllegalStateException` (existing `sendAndAwaitReply` / `failAllPending`) |
| Malformed read reply (prompt not a string, unknown/absent status, not an object) | `SerializationException` for that read only; no state changes |
| Write over 8192 UTF-8 bytes | `IllegalArgumentException`, no frame sent |
| Write → `conversation.not_found` | `IllegalArgumentException` |
| Write → any other server error | `RelayErrorException` |
| Malformed write ack | decode exception, no upsert |

No branch logs; every message this ticket authors is a static literal, so neither the prompt, its length nor the conversation id can reach Logcat or an exception message.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`):

- `SystemPromptPayloadsTest` (network): decode absent / `""` / text (multi-byte, whitespace kept verbatim) × each status; failures for explicit null, number, object prompt; unknown / absent / non-string status; non-object payload; failure messages contain no payload text. `setSystemPromptPayload` emits explicit null, `""`, text. `SystemPromptLimit`: 8192 multi-byte bytes fits, 8193 does not; ASCII and multi-byte counts.
- `RemoteConversationRepositorySystemPromptTest` (repository, own fake pump): read sends one `request_system_prompt {conversation_id}` and returns the decoded reading for the three states; `no_session` reads normally; non-interactive read throws without sending; a malformed reply fails the read and a later read still works (no state changed); unmatched `system_prompt` is ignored. Write sends one `set_system_prompt` with null / `""` / text; 8192-byte multi-byte text is sent, 8193 is refused with no frame; `conversation.not_found` → IAE; other code → `RelayErrorException`; ack upserts the record.
- `StableConversationRepositoryTest`: both delegate verbatim; both throw `IllegalStateException` with nothing live.
- `FakeConversationRepositoryTest`: the three states round-trip; unknown conversation reads as `(null, NoSession)`; write to unknown throws IAE; over-limit refused.

No Compose UI and no e2e scenario: this is a data-layer ticket with no operator-facing flow (the rung-3 clause does not fire).

## Documentation handoff

Pending for the documentation stage: fold the new `requestSystemPrompt` / `setSystemPrompt` surface into the relevant `docs/knowledge/features/` overviews (remote repository conversation writes / reads, the fake, the stable facade). The ticket names no specific reference document.

## Open questions

- Explicit JSON `null` in a `system_prompt` reply: the protocol says "string or absent"; the ticket says a non-string value fails the read. Resolved as **fail**.
