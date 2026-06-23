# Spec — `dequeue_message` outbound wire frame (#466)

**Ticket:** [#466](https://github.com/pyrycode/pyrycode-mobile/issues/466) · **Size:** S · **Security-sensitive:** yes (new internet-exposed outbound frame)

Phase-3 outbound half of the queued-message backlog (epic pyrycode#597). The inbound half shipped:
#460 decodes `queue_state` into the observable per-conversation backlog (`observeQueue`), #461 renders it.
This slice adds the **data-layer send** that dispatches a `dequeue_message` frame for one queued-message
id and reports success/failure against the existing request↔ack correlation. **No UI** — the per-row drop
affordance is the follow-up slice #467, which this ticket blocks.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:146-162` — `requestScreenSnapshot`,
  the **defaulted-throwing interface-method** precedent the new method mirrors (KDoc + `= error(...)` default
  shape). Also `:55` `observeQueue` and `:203-207` `QueuedMessage` (note `id: Long`) — the element whose `id`
  we echo back.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1046-1077` —
  `requestScreenSnapshot` override: the **exact send template** (build `Envelope` → `MobileJson.encodeToJsonElement`
  → `sendAndAwaitReply`). Also `:1154-1174` `cancelModal` (the closest single-other-field send), `:599-616`
  `sendAndAwaitReply`, `:565-577` `mapError`, `:1262-1266`/`:1334-1344` the companion constants block (where
  `TYPE_DEQUEUE_MESSAGE` goes).
- `app/src/main/java/de/pyryco/mobile/data/network/ModalOutboundPayloads.kt` (whole, 60 lines) — the
  **outbound encode-only DTO** precedent (`ModalCancelPayloadDto`): `internal data class`, `@SerialName`,
  rich never-log KDoc. The new file mirrors it.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:84-121` — the **inbound** queue DTO:
  `queued_msg_id` is `uint64` ↔ `Long` (`@SerialName("queued_msg_id") val queuedMsgId: Long`), **not** a String
  (the pyrycode#720 trap). Mirror this field name + type on the outbound DTO.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-33` — the single `MobileJson` config
  (`encodeDefaults` / `explicitNulls=false` / `ignoreUnknownKeys`). Encode through `MobileJson`, never a default `Json`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:117` — the
  `requestScreenSnapshot` facade delegation. Add the `dropQueuedMessage` one-line delegation right beside it.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1477-1543` — the
  `cancelModal` test block: the `FakeSessionPump` idiom (`pump.sent`, `pump.push(ackEnvelope/errorEnvelope)`,
  `pump.sendResult = false`), the `startCancelModal` helper to mirror, and the ack / error / not-connected
  assertion shapes. This is the template for the new tests.

---

## Context

ADR 025 (mobile remote head) lets a phone queue `send_message` turns into a daemon-resident FIFO while
claude is busy. The daemon broadcasts the live backlog as `queue_state` (decoded by #460). This slice closes
the **drop** loop: send a `dequeue_message` frame so the daemon removes a not-yet-drained message from the
queue before it reaches claude.

- **Wire SSOT:** pyrycode spec #720 + ADR 025 — `dequeue_message = {conversation_id, queued_msg_id}`.
  Daemon handler is live (pyrycode#723, closed).
- **No echo, no optimistic mutation.** Success is an empty `ack`. The backlog updates later, for free, when
  the daemon broadcasts the next `queue_state` on the already-shipped `observeQueue` path. This slice has
  **no observable-state side effect** — its sole job is to send the frame and report the outcome.

Splitting the send from the affordance isolates the brand-new internet-exposed outbound frame (the trust
boundary) into one focused, reviewable data slice.

---

## Design

### Placement: defaulted interface method (not injected lambda)

The drop carries a `conversation_id`, so it is a **per-conversation** operation the thread already reaches
through the `ConversationRepository` facade. That is exactly the `requestScreenSnapshot` shape, not the
`answerModal`/`cancelModal` shape (modals key off `modal_id` only — app-level, no `conversation_id` — so they
are fetched off the concrete coordinator). Choosing the interface-method precedent means **no** new ViewModel
ctor param, **no** coordinator passthrough, **no** Koin wiring: the follow-up UI (#467) calls
`repository.dropQueuedMessage(...)` on the facade the `ThreadViewModel` already holds.

### 1. New interface method (`ConversationRepository.kt`)

Add, next to `requestScreenSnapshot`, with a KDoc mirroring its shape:

```kotlin
suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long): Unit =
    error("dropQueuedMessage is not implemented for this ConversationRepository")
```

- **Throwing default** — the fake and inline test doubles inherit it untouched (the same cascade-avoidance
  documented on `delete` / `createWorkspaceFolder` / `requestScreenSnapshot`). **Do not** override it on
  `FakeConversationRepository`; no test double calls it, so the default is unreachable in tests.
- `queuedMessageId: Long` matches `QueuedMessage.id` — the caller passes the `id` it received from
  `observeQueue` verbatim. No re-derivation; the round-trip is `Long → Long` (same width #460 already accepted).
  Modeling it as a `String` is the pyrycode#720 trap.
- Returns `Unit`: success is "returned without throwing". KDoc must state: no projection side effect — the
  backlog is updated only by a subsequent `queue_state` (#460).

### 2. Outbound DTO — new file `data/network/QueueOutboundPayloads.kt`

Mirror `ModalOutboundPayloads.kt` exactly (a dedicated outbound-payloads file keeps the inbound
`InteractivePayloads.kt` "decode-only" contract clean — the same inbound/outbound file split modals use):

```kotlin
@Serializable
internal data class DequeueMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("queued_msg_id") val queuedMsgId: Long,
)
```

- Field order per the Go-struct wire SSOT: `conversation_id`, then `queued_msg_id`.
- `queuedMsgId: Long` — encodes to a JSON **number** (what the daemon's `uint64` parser expects), symmetric
  with the inbound `QueuedMessageDto.queuedMsgId: Long`. Not a String.
- KDoc carries the **never-log** posture (see Error handling) and "Always encode through `MobileJson`,
  never a default `Json`", mirroring `ModalCancelPayloadDto`.

### 3. Remote override (`RemoteConversationRepository.kt`)

Override `dropQueuedMessage` modeled on `requestScreenSnapshot` (1046-1077) / `cancelModal` (1154-1174):
build an `Envelope(id = requestId.incrementAndGet(), type = TYPE_DEQUEUE_MESSAGE, ts = Clock.System.now().toString(),
payload = MobileJson.encodeToJsonElement(DequeueMessagePayloadDto(conversationId, queuedMessageId)))`, then
`sendAndAwaitReply(request)` and **ignore** the empty `{}` ack (no decode, no projection mutation). No
`try`/`catch` — exceptions from `sendAndAwaitReply` propagate verbatim (see Error handling). Add the constant
to the companion:

```kotlin
const val TYPE_DEQUEUE_MESSAGE = "dequeue_message"
```

### 4. Facade delegation (`StableConversationRepository.kt`)

`dropQueuedMessage` is an interface method, so the facade must delegate it for the call to reach the
connection-scoped live repo (exactly as it delegates `requestScreenSnapshot` at :117):

```kotlin
override suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long): Unit =
    live.dropQueuedMessage(conversationId, queuedMessageId)
```

The `live` getter throws `IllegalStateException(NOT_CONNECTED)` when no connection is live at call entry —
the same type the remote throws on a not-`Open` pump, so a caller catches one type either way (AC #4).

### Data flow

```
#467 UI (later)         dropQueuedMessage(convId, id: Long)
        │                        │
        ▼                        ▼
StableConversationRepository ──► RemoteConversationRepository
   (delegate to `live`,            build Envelope{dequeue_message,
    or throw ISE if absent)         {conversation_id, queued_msg_id}}
                                        │  encode via MobileJson
                                        ▼
                                  sendAndAwaitReply ──► pump.send
                                        │
                          ┌─────────────┼──────────────┐
                       empty ack      error         send==false
                          │             │                │
                       return Unit   throw via        throw ISE
                       (no mutation)  mapError         (AC #4)
                                     (AC #3)
```

The backlog UI updates **only** via the next `queue_state` → `observeQueue` (#460/#461). This send touches
no `StateFlow`, mints no domain object, and is invisible to `observeMessages`/`observeQueue`/`observeLastMessage`.

---

## State + concurrency model

- **No new coroutine, no `StateFlow`, no projection mutation.** A one-shot `suspend` that runs on the
  caller's coroutine (the future #467 `ThreadViewModel` job). The repo just sends + awaits via the existing
  `sendAndAwaitReply` primitive.
- **Cancellation:** `dropQueuedMessage` adds **no** `try`/`catch`. `sendAndAwaitReply` uses `try`/`finally`
  (removes its pending entry on success, error, and caller cancellation) and rethrows verbatim, so structured
  cancellation is preserved. The `catch(IllegalStateException)`-swallows-`CancellationException` trap does not
  apply here — there is no catch block on this path.
- **Dispatcher:** inherits the caller's; the send/await is non-blocking (`pump.send` + `CompletableDeferred.await`).
  No Main/IO boundary is introduced.

No `UiState`/`Event` types — this is a data-layer one-shot with no ViewModel surface.

---

## Error handling

All three outcomes reuse the existing `sendAndAwaitReply` + `mapError` machinery — **no new error-mapping code**:

| Reply | Behavior | AC |
|-------|----------|-----|
| empty `ack` | return `Unit`; no state mutation | #2 |
| `error`, code `conversation.not_found` | `mapError` → `IllegalArgumentException` (consistent with sibling per-conversation ops) | #3 |
| `error`, any other code | `mapError` → `RelayErrorException(code, retryable, message)` | #3 |
| `error`, malformed payload | `mapError` fallback → `RelayErrorException(error.malformed_reply)` — never hangs | #3 |
| not connected (`pump.send` false, or facade `live` absent) | `IllegalStateException` | #4 |

- **Surfacing for #467:** the failure propagates to the caller; the downstream UI leaves the backlog
  unchanged on any throw. This slice surfaces, it does not handle — there is nothing to roll back (no
  optimistic mutation).
- **No queue-specific error code is special-cased.** The daemon's response to a stale / already-drained
  `queued_msg_id` is server-defined; whatever code it returns surfaces generically as `RelayErrorException`,
  which satisfies AC #3. Adding a bespoke mapping would defend a failure mode not yet observed — defer it
  unless #467 needs to distinguish it visually (see Open questions).

### Never-log (AC #5)

The `data/repository` + `data/network` layers contain **no logger** (verified: no `android.util.Log`,
`Timber`, or `println` anywhere in either package). The never-log property is therefore **structural** — the
implementation contains zero log statements — and is documented on the DTO + method KDoc, exactly as every
sibling outbound control send (`modal_answer`, `modal_cancel`, `register_push_token`, `request_snapshot`),
none of which has a dedicated log-capture test because the layer has no logging seam to assert against. Do
**not** introduce a logger seam just to test this; that would defend an unobserved failure mode and add
surface the AC does not require. The AC is satisfied by the no-log impl + KDoc note + the frame-shape /
outcome tests below. `conversation_id` and the `queued_msg_id` ordinal are not secrets, but the no-log posture
holds uniformly across the layer regardless.

---

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest --tests "...RemoteConversationRepositoryTest"`); no instrumented
test. Add a `startDropQueuedMessage(repo, conversationId, queuedMessageId)` helper mirroring `startCancelModal`,
and a test block mirroring `cancelModal` (1477-1543). Scenarios (inputs → expected):

- **Frame shape (AC #1, #5):** call `dropQueuedMessage("c-1", 42L)`; assert `pump.sent.single { it.type == "dequeue_message" }`
  has payload exactly `{"conversation_id":"c-1","queued_msg_id":42}` — confirms the two-key shape, `queued_msg_id`
  is a JSON **number** (not a quoted string), and no extra keys.
- **Empty ack → success, no mutation (AC #2):** push `ackEnvelope(sent.id)`; assert the call succeeds; assert
  `observeQueue`/`observeMessages`/`observeLastMessage` for that conversation are unchanged (no projection write).
- **Server error → caller-visible failure (AC #3):** push `errorEnvelope(sent.id, code = "...", retryable = ...)`;
  assert the result's exception is `RelayErrorException` exposing `code` + `retryable`.
- **conversation.not_found → IllegalArgumentException (AC #3):** push `errorEnvelope(sent.id, code = "conversation.not_found")`;
  assert `IllegalArgumentException` (consistent with `sendMessage`/`requestScreenSnapshot`).
- **Not connected → IllegalStateException (AC #4):** set `pump.sendResult = false`; assert the result's
  exception is `IllegalStateException` and the call does not hang.
- *(Optional)* large-id round-trip: a `queued_msg_id` near `Long.MAX_VALUE` encodes as a bare number — guards
  the #720 width trap.

**Facade delegation** (`StableConversationRepositoryTest.kt`): the facade's existing behaviour-neutral
delegation pattern covers this; add one test asserting `dropQueuedMessage` delegates to the live repo and one
asserting it throws `IllegalStateException` when `currentRepository` is `null`, matching the sibling one-shots.

Never-log: satisfied structurally (see Error handling) — no test asserts it, matching all sibling sends.

---

## Open questions

- **Stale `queued_msg_id` error code.** pyrycode#723's handler removes a not-yet-drained message; its response
  to an unknown / already-drained id is server-defined. It surfaces generically as `RelayErrorException` today
  (AC #3 met). If #467 later needs to show a distinct "already gone" message vs a transient failure, that
  mapping is added then, in the consumer slice — not pre-built here.
- **Sibling sequencing (informational, no action).** The interrupt-send slice #458 (currently blocked at
  architect time, no code) will, when implemented, add a sibling interface method touching the same three
  files (`ConversationRepository.kt`, `RemoteConversationRepository.kt`, `StableConversationRepository.kt`).
  #466 has no overlap **now** (branch scan clean). #466 lands first; #458's architect run will find feature/466
  on these files and block on it — the ordering resolves without action here.

---

## Security review

**Verdict:** PASS

This is a **send-only outbound** slice (the phone is the sender). Several categories are not applicable; each
names the design decision that makes it so rather than a bare "N/A".

**Findings:**

- **[Trust boundaries]** No finding — no *new* boundary. The outbound `DequeueMessagePayloadDto` is built from
  caller-trusted values (`conversationId`/`queuedMessageId`, which #467 receives from `observeQueue`'s decoded,
  server-originated rows). The only inbound boundary is the `error` reply → `mapError` (`RemoteConversationRepository.kt:565-577`),
  the pre-existing, hardened #346 boundary reused **verbatim** (malformed → fallback `RelayErrorException`,
  never hangs). The empty `ack` is not decoded. Downstream (#467) holds only `Unit` or a typed exception.
- **[Tokens, secrets, credentials]** N/A — no token is generated, stored, or compared. Unlike `modal_answer`,
  this frame carries **no idempotency key**, and that is correct: `queued_msg_id` is a monotonic per-conversation
  ordinal (never recycled), so a replayed drop targets an already-consumed id → daemon stale-id reject, with
  no double-effect hazard. This is the `modal_cancel` no-token posture (pyrycode#701). `queued_msg_id` and
  `conversation_id` are explicitly non-secret (`QueuedMessage` KDoc).
- **[File / storage]** N/A — no filesystem I/O, no path construction, no persistence. The backlog projection is
  in-memory and owned by #460; this slice writes no local state.
- **[Android attack surface]** N/A — no `Activity`/`Service`/`BroadcastReceiver`/`ContentProvider`, no Intent,
  no deep link, no `PendingIntent`, no `WebView`. An internal repository method called only by the in-process
  ViewModel; no exported surface → no third-party-app trigger.
- **[Cryptographic primitives]** N/A — no RNG, hashing, key storage, or secret comparison. `requestId.incrementAndGet()`
  is a pre-existing monotonic counter, not security-relevant randomness. Transport encryption (Noise_IK) is the
  unchanged layer below.
- **[Network & I/O]** No new finding — the slice introduces no `OkHttpClient`/TLS/timeout/frame-size config; it
  sends over the existing `pump.send`. The outbound frame is small and bounded (two scalar fields, no unbounded
  text). **No injection vector:** `conversationId` is JSON-*encoded* (escaped) by kotlinx through `MobileJson`,
  not string-concatenated. OUT OF SCOPE (inherited, not worsened): `sendAndAwaitReply`'s `deferred.await()` has
  no per-call read deadline — a silent daemon hangs the caller until its scope cancels. This is a property of
  the shared #346 primitive, identical across every send; a per-call timeout would be a cross-cutting change.
- **[Error messages, logs, telemetry]** No finding — never-log is **structural** (no logger exists in
  `data/repository` or `data/network` — verified by grep) plus the documented DTO/method KDoc note (AC #5). The
  surfaced `RelayErrorException.message` is daemon-supplied and carries ids/reasons only; the `dequeue_message`
  frame has **no text field**, so leaking queued message *body* content through an error is structurally
  impossible. No new telemetry.
- **[Concurrency]** No finding — no coroutine launched (one-shot on the caller's scope), no `StateFlow`/state
  mutated (no projection write), no Mutex, no check-then-mutate. Cancellation-safe: the method adds **no**
  `try`/`catch`, reusing `sendAndAwaitReply`'s `try`/`finally`, so structured cancellation is preserved and the
  `catch`-swallows-`CancellationException` trap is avoided. Process death mid-send leaves no partial local state
  (no optimistic mutation); the next `queue_state` is authoritative.
- **[Threat model alignment]** No finding — authorization is **daemon-side**: the phone asserts
  `(conversation_id, queued_msg_id)`; the daemon validates both against the per-pairing Noise session and the
  per-conversation queue. The phone never self-authorizes (same posture as `send_message`/`modal_answer`). A
  confused caller can act only within the user's own paired authority; a mismatched id pair (an id from a
  different conversation) is rejected → a benign `RelayErrorException`, never a wrong-message drop. No UI surface
  in this slice — mobile-UI threats (screenshot/overlay/etc.) belong to the #467 affordance.
  OUT OF SCOPE (inherited): reply-correlation spoofing resistance is a transport/#346 property (the daemon is the
  authenticated Noise peer), unchanged here.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-23
