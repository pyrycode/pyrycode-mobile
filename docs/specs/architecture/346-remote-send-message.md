# Spec — `RemoteConversationRepository.sendMessage` over v2 (`send_message`) (#346)

> Fills the `sendMessage` stub on the already-merged `RemoteConversationRepository` with a live
> Mobile Protocol v2 implementation: encode a `send_message` request, correlate its `ack`/`error`
> reply through the class's single inbound collector, and **confirmed-insert** the user's message
> into the read-path projections only after the `ack`. Introduces the shared request↔reply
> correlation primitive that siblings #347 (`createDiscussion`) / #348 (`promote`) reuse.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:52-162` — the class, its three projections (`projection`, `lastMessages`, `messagesByConversation`), the `requestId` `AtomicLong`, the single `init` inbound collector, and `onInbound`'s `when(envelope.type)`. **This is the only production class you modify.** Extract `recordLastMessage` from the inline `TYPE_MESSAGE` fold at lines 128-135; add `ack`/`error` branches to the `when`; fill the `sendMessage` stub at 290-293.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:164-187` — `appendMessages(rows)`: the existing atomic, order-preserving, id-deduped writer for `messagesByConversation`. `sendMessage` reuses it verbatim (`appendMessages(listOf(conversationId to message))`).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:255-281` — `sendMessage` (the observable contract to mirror: mint id, reconstruct `Role.User` message, insert, return) and `unknown(id)` at line 335 (`IllegalArgumentException("Unknown conversation: $id")` — the message format AC #3 mirrors).
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:17-30` — `inbound: Flow<Envelope>` (hot, single-consumer) + `send(envelope): Boolean` (non-throwing; `false` when not `Open`). The whole transport surface this slice touches.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-44, 67-73` — `Envelope(id, type, ts, payload, inReplyTo)` and `HelloAckPayload`. Add the new `ErrorPayload` (and `RelayErrorException`) alongside the handshake payloads here — multi-type file, so no ktlint filename violation.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt:113-139` — `MessageChunkPayloadDto` and especially `BackfillSincePayloadDto` (the **encode-only** request-DTO pattern, `@SerialName` snake_case, Go-struct field order). The new `SendMessagePayloadDto` is the same shape; add it in this file.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` config (`encodeDefaults`, `explicitNulls=false`, `ignoreUnknownKeys=true`). `ignoreUnknownKeys` is why a 3-field `ErrorPayload` tolerates the server's 4th field (`retry_after_s`). Always (de)serialize through `MobileJson`.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5` — `Message(id, sessionId, role, content, timestamp, isStreaming, toolCall=null)`; `Role.User`. The reconstructed message's exact fields.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:33-49, 263-405, 660-700` — the test idiom: `runTest` + `runCurrent()`, the `FakeSessionPump` (UNLIMITED channel, `sent` list, `push()`), the `messageEnvelope`/`collectLastMessage`/`collectMessages` helpers. New tests follow this shape exactly; `FakeSessionPump.send` needs a togglable result for the `send`-returns-`false` case.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:78-87` — the `sendMessage` interface contract (returns persisted `Message`; throws `IllegalArgumentException` for unknown id; blank-text is the caller's concern). Signature is **unchanged** — no consumer call-site cascade.

## Context

Phase 4 backend integration. `RemoteConversationRepository` (the live v2 data layer) currently
throws `UnsupportedOperationException` from `sendMessage`. This slice makes it send the user's text
over the landed Noise session pump and update the conversation's message streams.

**The corrected wire premise is load-bearing.** `send_message` does **not** echo the sender its own
persisted `Message`. The only sender-correlated reply is an empty **`ack`** on success
(`in_reply_to == requestId`, payload `{}`) or an **`error`** on failure
(`{code, message, retryable}`). A `message` envelope reaching this device is either a user-echo to
*other* paired devices or the assistant's *later, unsolicited* reply (`in_reply_to: null`) — never a
sender echo. (The stale "send_message-echo" phrasing in `MessagePayload.kt:14-15` and
`RemoteConversationRepository.kt:111-118` predates this correction; **do not touch it** — the
`TYPE_MESSAGE` inbound path is correct for the *other-device / assistant* cases it actually handles.)
So the sender's thread updates **only** if `sendMessage` projects the message locally — and per the
PO decision below, only after the `ack` confirms server receipt.

**Decision (PO-owned, `security-sensitive`): confirmed-insert, not optimistic.** The message is
projected into the read path **only on the correlated `ack`** — never before, never on failure. No
rollback path exists because nothing is inserted speculatively. Rationale (verbatim from the ticket):
`Message` has no delivery-status field, so an optimistic insert could only render as a normal-looking
message that might silently vanish — the worse trust outcome. Perceived send latency is a future
UI-layer concern and does not change this data-layer contract.

## Design

### New wire models (encode/decode DTOs)

Two new serializable DTOs and one exception. All `data/`-portable (no `android.*`).

- **`SendMessagePayloadDto`** → add to `MessagePayload.kt` (encode-only, mirrors `BackfillSincePayloadDto`).
  Wire SSOT: server `internal/protocol/messaging.go` `SendMessagePayload` (#272). Field order matches
  the Go struct. All three fields required; `message_id` is client-generated.

  ```kotlin
  @Serializable
  data class SendMessagePayloadDto(
      @SerialName("conversation_id") val conversationId: String,
      @SerialName("message_id") val messageId: String,
      val text: String,
  )
  ```

- **`ErrorPayload`** → add to `MobileWireModels.kt` (decode-only). Wire SSOT: server
  `internal/protocol/handshake.go` `ErrorPayload`. The server also emits `retry_after_s`; it is
  **intentionally not modeled** — `MobileJson`'s `ignoreUnknownKeys=true` tolerates it on decode.
  `code`/`message`/`retryable` are single-word wire names, no `@SerialName` needed.

  ```kotlin
  @Serializable
  data class ErrorPayload(val code: String, val message: String, val retryable: Boolean)
  ```

- **`RelayErrorException`** → add to `MobileWireModels.kt`. The thrown form of a server `error` whose
  code is not `conversation.not_found`. Carries the structured fields so a ViewModel can branch on
  `retryable` and surface `code`/`message`. Signature contract:

  ```kotlin
  class RelayErrorException(val code: String, val retryable: Boolean, message: String) : Exception(message)
  ```

  **No `AckPayload` model.** The `ack` payload is empty `{}` and is never decoded — correlation is
  purely on `Envelope.inReplyTo`. An unused `AckPayload` would be dead code; modeling it is
  deliberately omitted. (This is a conscious choice, not an oversight — call it out if reviewed.)

### The shared correlation primitive (reused by #347/#348)

The class already runs **exactly one** `pump.inbound` collector (single-consumer, launched in `init`).
This slice introduces a generic pending-reply registry that the collector resolves by request id, and
a small `suspend` helper that registers, sends, and awaits. **This is the infrastructure #347/#348
inherit** — they add their own success-type branches (`conversation_created` / `conversation_updated`)
to the same `when`, completing the same registry's deferreds; the `error` branch and the registry are
shared as-is.

- New field: `private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()`.
  Keyed by the request *envelope* id (`requestId.incrementAndGet()`), **distinct from** the payload's
  `message_id`. Typed `<JsonElement>` (the reply's raw payload) so the one registry serves bare-`ack`
  callers (which ignore it) and future typed-reply callers (#347/#348 decode it). `ConcurrentHashMap`
  matches the file's existing `java.util.concurrent` choice (`AtomicLong`); touched from both the
  collector coroutine and arbitrary caller coroutines.

- New `suspend` helper — the reusable register→send→await primitive. Behavior: register the deferred
  under `request.id` **before** sending (no lost-reply race); throw `IllegalStateException` and clean
  up if `pump.send` returns `false`; `await()` the reply (rethrows the collector's exceptional
  completion on `error`); remove the entry in `finally` (covers success, error, and caller
  cancellation). Returns the reply payload (the empty `{}` for an `ack`).

  ```kotlin
  private suspend fun sendAndAwaitReply(request: Envelope): JsonElement
  ```

  Asserted by the AC #5 round-trip tests (request shape, ack-resolves, error-throws, send-false-throws).

- Two new `when` branches in `onInbound` (the collector never throws — completions are idempotent and
  thread-safe; a malformed `error` payload still unblocks the waiter rather than hanging it):

  ```kotlin
  TYPE_ACK -> envelope.inReplyTo?.let { pendingRequests[it]?.complete(envelope.payload) }
  TYPE_ERROR -> envelope.inReplyTo?.let { pendingRequests[it]?.completeExceptionally(mapError(envelope.payload)) }
  ```

  An `ack`/`error` whose `inReplyTo` matches no pending entry (or is null) is a no-op — `list_conversations`
  and `backfill_since` never draw `ack`/`error` replies, so the only ids in the registry are
  `send_message` (and later create/promote) requests.

- `mapError(payload: JsonElement): Throwable` — decode `ErrorPayload` through `MobileJson`; if
  `code == "conversation.not_found"` return `IllegalArgumentException("Unknown conversation: …")`
  (AC #3, mirroring the fake's type and message), else `RelayErrorException(code, retryable, message)`
  (AC #4). A decode failure (malformed error payload) returns a fallback `RelayErrorException` so the
  waiter never hangs. `mapError` does not log the payload.

### `sendMessage` itself

```kotlin
override suspend fun sendMessage(conversationId: String, text: String): Message
```

Flow (≤ ~15 lines):
1. Mint `messageId = UUID.randomUUID().toString()` (client-side, mirrors the fake) and capture
   `sentAt = Clock.System.now()`.
2. Build the request `Envelope(id = requestId.incrementAndGet(), type = "send_message", ts = now,
   payload = MobileJson.encodeToJsonElement(SendMessagePayloadDto(conversationId, messageId, text)))`.
3. `sendAndAwaitReply(request)` — ignore the empty `{}` ack payload; this throws on `error`/not-Open.
4. **Only after the ack returns**, reconstruct the message and confirmed-insert it into **both**
   projections, then return it:
   - `Message(id = messageId, sessionId = "", role = Role.User, content = text, timestamp = sentAt, isStreaming = false)`.
     `sessionId = ""` is the remote's established placeholder (the inbound mappers use it; the v2 wire
     carries no `session_id` — do **not** resolve `currentSessionId`).
   - `recordLastMessage(conversationId, message)` then `appendMessages(listOf(conversationId to message))`.

No local projection-cache guard for unknown conversation: the server is authoritative for conversation
existence (the cached `projection` can be stale), so unknown-id surfaces through the server's
`conversation.not_found` `error` → `IllegalArgumentException`. Observably identical to the fake's
synchronous throw; the contract pins the exception type, not the timing.

### `recordLastMessage` extraction

Extract the inline strictly-greater-by-timestamp fold at `RemoteConversationRepository.kt:128-135`
into `private fun recordLastMessage(conversationId: String, message: Message)`. The `TYPE_MESSAGE`
branch calls it instead of the inline block (behavior-preserving); `sendMessage` calls it too. This
is a DRY extraction **directly motivated by reuse** (mirrors the pre-existing `appendMessages`
helper), not gratuitous refactoring — it is the one sanctioned edit to the collector's existing
`TYPE_MESSAGE` body. Do not otherwise alter sibling-filled methods.

### New companion constants

`TYPE_SEND_MESSAGE = "send_message"`, `TYPE_ACK = "ack"`, `TYPE_ERROR = "error"`,
`ERROR_CONVERSATION_NOT_FOUND = "conversation.not_found"`.

## State + concurrency model

- **Single source of state per projection, two writers now.** `lastMessages` and
  `messagesByConversation` were documented single-writer (the `init` collector). This slice relaxes
  that to **collector + `sendMessage`'s confirmed-insert**. Data-safety holds: every write goes
  through `MutableStateFlow.update { }` (atomic CAS); the folds are pure (`recordLastMessage`'s
  strictly-greater rule, `appendMessages`'s id-dedup), so concurrent writes from the two coroutines
  retry-merge correctly. **Update the KDoc on both fields** (`RemoteConversationRepository.kt:64-82`)
  to name the second writer — leaving the "single writer" claim stale would be a correctness-doc lie.
- **No new collector, no second subscription.** Ack/error correlation rides the existing single
  `init` collector — the absolute constraint from the read-path slices. `sendMessage` never collects
  `pump.inbound`.
- **Suspend/await.** `sendMessage` suspends on `CompletableDeferred.await()` in the *caller's*
  coroutine (a ViewModel `viewModelScope`), not the connection scope. Caller cancellation →
  `CancellationException` → `finally` removes the registry entry (no leak). The reply is delivered by
  the connection-scoped collector coroutine; `complete`/`completeExceptionally` are thread-safe and
  idempotent across the two coroutines.
- **Dispatcher.** None chosen here — pure in-memory state flow + a non-blocking `pump.send`. No IO/Main
  boundary in this slice (transport dispatching lives below the pump).

## Error handling

| Failure | Surfaced as | Projection mutated? |
|---|---|---|
| Unknown conversation (server `error` `conversation.not_found`) | `IllegalArgumentException` (AC #3) | No |
| Other server `error` (`protocol.malformed`, `server.binary_offline`, unknown code) | `RelayErrorException(code, retryable, message)` (AC #4) | No |
| Not connected (`pump.send` returns `false`) | `IllegalStateException` (AC #4) | No |
| Malformed `error` payload (can't decode) | fallback `RelayErrorException` — waiter unblocked, collector survives | No |
| Caller coroutine cancelled mid-await | `CancellationException` (standard) | No |

The thread is **never** mutated on any failure path — the confirmed-insert (step 4) runs only after
`sendAndAwaitReply` returns normally. No silent failure: every non-success path throws from the
suspend call.

## Testing strategy

Unit only (`./gradlew test`), extending `RemoteConversationRepositoryTest` with the existing
`runTest` + `runCurrent()` + `FakeSessionPump` idiom (no device, no real crypto). One small test-helper
change: give `FakeSessionPump` a togglable send result (e.g. `var sendResult = true; override fun
send(e) = e.also { sent += it }.let { sendResult }`) so a test can simulate not-Open.

New scenarios (bullet form — write in the project idiom):
- **AC #5 request shape:** call `sendMessage("c1", "hi")` (on `backgroundScope`); feed a correlated
  `ack`; assert the single `sent` envelope has `type == "send_message"` and payload
  `{conversation_id:"c1", message_id:<minted>, text:"hi"}` (assert the three fields; `message_id` is a
  non-blank minted value).
- **AC #1/#2 ack resolves + both streams re-emit:** collect `observeMessages("c1")` and
  `observeLastMessage("c1")`; call `sendMessage`; feed the correlated `ack` (`in_reply_to` = the sent
  envelope id); assert the call returns a `Role.User` `Message` with `id == minted`, `content == "hi"`,
  `sessionId == ""`; assert `observeMessages` now ends with that `MessageItem` and `observeLastMessage`
  emits it. (To learn the sent envelope id: read `pump.sent.last().id`.)
- **AC #4 server error throws, projections unchanged:** feed a correlated `error`
  `{code:"server.binary_offline", message:"…", retryable:true}`; assert `sendMessage` throws
  `RelayErrorException` exposing that `code`; assert both streams emitted nothing for `c1`.
- **AC #3 unknown conversation:** feed a correlated `error` `{code:"conversation.not_found",…}`; assert
  `IllegalArgumentException`; projections unchanged.
- **AC #4 not-connected throws:** set `FakeSessionPump.sendResult = false`; assert `sendMessage` throws
  `IllegalStateException`; assert no envelope was "sent" beyond the registry attempt and projections
  unchanged.
- **Correlation hygiene:** an `ack` with an `inReplyTo` matching no pending request is a no-op (no
  crash, collector survives — prove with a subsequent valid send round-trip).
- Confirm the existing `stubMethods_throwUnsupportedOperationNamingTheFollowUp` test
  (`RemoteConversationRepositoryTest.kt:230-247`) is updated: `sendMessage` is no longer a stub —
  remove its `assertUnsupported`/`#314` assertion line.

`ComposeTestRule` is not involved (pure data layer).

## Open questions

- **Connection-drop-mid-send leak.** If the connection scope is cancelled (collector stops) while a
  caller still awaits, no `ack`/`error` will arrive and the deferred never completes — the suspend
  hangs until the *caller* is cancelled. In practice the ViewModel scope cancels on screen exit
  (unblocks via `CancellationException`) and the supervisor surfaces disconnect to the UI. No timeout
  is added here (no observed hang; choosing a timeout value is a product call). A future slice — or
  the connection coordinator (#302) — may fail all `pendingRequests` on disconnect. Flagged, not fixed
  (Evidence-Based Fix Selection).
- **`retry_after_s`.** Modeled away for now (tolerated by `ignoreUnknownKeys`). If a future retry-UI
  needs it, add `@SerialName("retry_after_s") val retryAfterSeconds: Int? = null` to `ErrorPayload`.

## Security review (`security-sensitive`)

> The canonical `security-review.md` referenced by the architect prompt is not symlinked into this
> worktree; the adversarial pass below walks the standard trust-boundary / input / secrets / DoS
> categories and renders a verdict. **Verdict: PASS.**

- **Trust boundaries.** The only untrusted input is the inbound `ack`/`error` envelope payload, decoded
  through `MobileJson` (strict, lenient-unknown-keys). A malformed `error` payload is caught in
  `mapError` and converted to a fallback exception — it cannot crash or hang the collector. No `eval`,
  no shell, no SQL, no path/URL construction from payload data → no injection surface. The channel is
  the already-authenticated Noise session established by pairing; the relay/server is the user-paired
  trusted endpoint, not an arbitrary attacker.
- **Correlation integrity.** Request ids are client-minted and monotonic (`AtomicLong`) within a
  session; the server only echoes them as `inReplyTo`. A buggy/duplicate `inReplyTo` could at most
  resolve the matching pending request once (`CompletableDeferred` completion is idempotent — a second
  `ack`/`error` for the same id is a no-op). Cross-request confusion is bounded to the session's own id
  space against the paired server. No code change warranted under the paired-trust model (no observed
  attack); documented here as the boundary.
- **Confirmed-insert is the security property.** A message is projected **only** on a server `ack`, so
  the thread never shows a message the server did not receive — satisfied by construction, no rollback
  path to get wrong. This is exactly the trust property the prior review demanded.
- **Secret / sensitive-data exposure.** Message content (`text`) is sensitive. The design logs
  **nothing** — `mapError` does not log the payload, and neither the request `Envelope` (carries
  `text`) nor the reconstructed `Message` is logged, preserving the existing collector posture
  ("message content may be sensitive"). The `RelayErrorException` message carries only the server's
  human-facing `error.message` (server-authored control text), never the user's `text`. No tokens or
  keys are touched by this slice. **Developer constraint:** do not add `Log.*` of the request payload,
  envelope, `text`, or the reconstructed `Message`.
- **Resource / DoS.** `pendingRequests` grows by one per in-flight send and is removed in `finally`
  (success, error, cancellation) — bounded by caller concurrency, no unbounded growth. The one
  documented leak (connection drop) is single-entry-per-stranded-send and freed on caller cancellation.
- **Input validation.** Blank-text is the caller's concern (interface contract, AC #3). `message_id`
  is a server-opaque client UUID (not user-controlled, no injection). Unknown `conversationId` is
  rejected by the server and surfaced as `IllegalArgumentException`.

No FAIL findings; no spec revision required. Proceeding to commit.
