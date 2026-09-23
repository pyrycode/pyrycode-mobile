# Remote conversation repository — the Phase 4 `ConversationRepository` — send, create, promote and rename

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `sendMessage(conversationId, text)` — the first mutation (#346)

Posts the user's text over v2 `send_message` and returns the persisted `Message`.
[#346](../codebase/346.md) implements it, and in doing so adds the **shared request↔reply correlation
primitive** the remaining mutations (#347/#348) reuse.

**The corrected wire premise is load-bearing.** `send_message` does **not** echo the sender its own
persisted `Message`. The only sender-correlated reply is an empty **`ack`** on success
(`in_reply_to == requestId`, payload `{}`) or an **`error`** on failure (`{code, message, retryable}`).
A `message` envelope reaching this device is a user-echo to *other* paired devices or the assistant's
*later, unsolicited* reply (`in_reply_to: null`), **never** a sender echo — so there is nothing to map
for the return value, and the sender's own thread updates **only** if `sendMessage` projects the
message locally. (The "send_message echo" phrasing still in the `TYPE_MESSAGE` code comments predates
this correction and is harmless — that path correctly handles the other-device / assistant cases.) See
[[phase4-send-message-acks-not-message-echo]] and [[phase4-request-encoders-live-in-mutation-tickets]].

**Decision (PO-owned, `security-sensitive`): confirmed-insert, not optimistic.** The message is
projected into the read path **only on the correlated `ack`** — never before, never on failure. There
is no rollback path because nothing is inserted speculatively. The trust property — the thread never
shows a message the server did not receive — holds **by construction**. (`Message` has no
delivery-status field, so an optimistic insert could only render as a normal-looking message that might
silently vanish; perceived send latency is a future UI-layer concern, not a data-layer one.)

The flow (≤ ~15 lines):

```kotlin
override suspend fun sendMessage(conversationId: String, text: String): Message {
    val messageId = UUID.randomUUID().toString()        // client-minted; the sender's correlation handle
    val sentAt = Clock.System.now()
    val request = Envelope(
        id = relayRequests.nextRequestId(),              // the ENVELOPE id — distinct from messageId
        type = "send_message", ts = sentAt.toString(),
        payload = MobileJson.encodeToJsonElement(
            SendMessagePayloadDto(conversationId, messageId, text)),  // {conversation_id, message_id, text}
    )
    relayRequests.sendAndAwaitReply(request)             // throws on error / not-Open; ignore the empty {} ack
    val message = Message(messageId, sessionId = "", Role.User, text, sentAt, isStreaming = false)
    recordLastMessage(conversationId, message)           // confirmed-insert into BOTH projections,
    appendMessages(listOf(conversationId to message))    //   only after the ack
    return message
}
```

This **mirrors `FakeConversationRepository.sendMessage`'s observable contract** (mint id, reconstruct a
`Role.User` message, insert, return) so the UI behaves identically under either binding. The one
intentional divergence: `sessionId = ""` (the remote's list-tier placeholder; the v2 wire carries no
`session_id`, boundaries are #336) vs the fake's `currentSessionId`. There is **no local cache guard**
for an unknown conversation — the server is authoritative (the cached `projection` can be stale), so an
unknown id surfaces through the server's `conversation.not_found` `error`. Observably identical to the
fake's synchronous throw; the contract pins the exception *type*, not the timing.

### The `ack`/`error` correlation primitive (reused by #347/#348, moved to `RelayRequests` by #914)

The class still runs **exactly one** `pump.inbound` collector — `sendMessage` does **not** open a
second subscription. Correlation rides that single collector via a generic pending-reply registry. Since
\#914 the registry, the counter and the correlated await live on `RelayRequests`
(`data/repository/RelayRequests.kt`), split out of the repository the way the projections were (#912,
\#913); the repository still owns `onInbound` and looks a waiter up through `RelayRequests.waiter` to
complete or fail it:

```kotlin
// RelayRequests — keyed by the request ENVELOPE id (nextRequestId()), distinct from the payload message_id
private val requestId = AtomicLong(0)
private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

fun nextRequestId(): Long = requestId.incrementAndGet()

fun waiter(inReplyTo: Long?): CompletableDeferred<JsonElement>? = inReplyTo?.let { id -> pendingRequests[id] }

suspend fun sendAndAwaitReply(request: Envelope): JsonElement {
    val deferred = CompletableDeferred<JsonElement>()
    pendingRequests[request.id] = deferred                       // register BEFORE send (no lost-reply race)
    return try {
        check(send(request)) { "${request.type} not sent: session not connected" }  // → IllegalStateException
        deferred.await()                                          // repository's onInbound completes it via waiter()
    } finally {
        pendingRequests.remove(request.id)                        // success, error, AND caller cancellation
    }
}
```

- **Keyed on the envelope id, not the payload `message_id`** — two distinct ids; conflating them would
  break correlation. The repository's `onInbound` `ack`/`error` arms look the matching deferred up through
  `RelayRequests.waiter(envelope.inReplyTo)` and complete or fail it themselves (see the demux table above);
  `RelayRequests` hands back the waiter rather than completing it so each arm keeps its own completion rule.
- **`ConcurrentHashMap`** matches the file's existing `java.util.concurrent` posture (`AtomicLong`);
  it's touched from both the connection-scoped collector coroutine and arbitrary caller coroutines.
- **Typed `<JsonElement>`** (the raw reply payload) so the one registry serves a bare-`ack` caller
  (`sendMessage` ignores the empty `{}`) **and** every typed-reply caller (#347/#348 decode
  `conversation_created`/`conversation_updated` from it). Those siblings add their success-type `when`
  branch and reuse the registry + the `error` branch + `RelayRequests.mapError` **as-is**. Every request on
  the connection takes its envelope id from `RelayRequests.nextRequestId` — the fire-and-forget frames and
  the asks `ModelMenuProjection`/`QuestionBatchProjection` send included — so the model-list ask ledger and
  `pendingRequests` stay disjoint.
- **`sendMessage` suspends on `CompletableDeferred.await()` in the *caller's* coroutine** (a ViewModel
  scope), not the connection scope. Caller cancellation → `CancellationException` → the `finally`
  removes the entry (no leak). The reply is delivered by the collector coroutine;
  `complete`/`completeExceptionally` are thread-safe and idempotent across the two coroutines.
- **On teardown, every pending deferred is failed — not left to hang ([#488](../codebase/488.md)).** The
  single `init` collector's body is wrapped in `try { … } finally { endDebugBundle(); endAttachmentUploads();
  relayRequests.failAllPending() }`. When the connection
  tears down — the primary trigger is [`RelayRepositoryCoordinator.teardownActive()`](relay-repository-coordinator.md)
  cancelling the collector's scope (`CancellationException` at the `collect` suspension), and the `finally`
  also covers `pump.inbound` completing or throwing — `RelayRequests.failAllPending()` completes **every**
  still-registered
  deferred exceptionally with a plain `IllegalStateException("connection torn down before reply")` and clears
  the map. So an awaiting `sendAndAwaitReply` caller (a tapped permission answer, a sent message, a promote,
  …) throws **promptly** instead of suspending forever (the pre-#488 behaviour was a **silent answer-drop**
  that blocked flipping `USE_RELAY_REPOSITORY` on). The exception type deliberately **matches the
  not-connected `check(...)` above** (one failure mode for every caller) and is deliberately **not** a
  `CancellationException` (which `extends IllegalStateException` on the JVM), so `await()` surfaces a real
  error rather than reading as the caller's own scope dying. The sweep is **non-suspending** (so it runs to
  completion inside the cancelling coroutine) and **logs nothing** (the message is a static const; the swept
  reply payloads are discarded). It uses the values iterator's `remove()` (not `map.clear()`), so a request
  registered concurrently *during* the sweep is never wiped without completion — `ConcurrentHashMap`-safe +
  idempotent. See [#488](../codebase/488.md).

`RelayRequests.mapError(payload): Throwable` turns a server `error` into the thrown domain exception: decode
`ErrorPayload` through `MobileJson`; `conversation.not_found` → `IllegalArgumentException("Unknown
conversation: …")` (fake parity), every other code → [`RelayErrorException`](mobile-protocol-v2-wire-layer.md)`(code,
retryable, message)`. A **decode failure is caught** and returns a fallback `RelayErrorException`
(`error.malformed_reply`) so the waiter is **never** left hanging and the lone collector survives — a
deterministic safety net, not a re-thrown exception. `mapError` logs nothing (message content stays off
the log).

> **`recordLastMessage` extraction.** The strictly-greater-by-timestamp fold (formerly inline in the
> `message` arm, [#329](../codebase/329.md)) was extracted to `private fun recordLastMessage(...)` so
> both the live `message` arm and `sendMessage` share it (mirroring the pre-existing `appendMessages`).
> Behavior-preserving; the one sanctioned edit to the collector's existing `TYPE_MESSAGE` body.

### Naming a message's attachments — `sendMessage(conversationId, text, attachmentIds)` (#830)

`send_message` can name the [uploaded attachments](attachment-upload.md) (#829) a message references, in
`SendMessagePayloadDto`'s optional `attachment_ids` (`@SerialName`, declared last). Under `MobileJson`
(`encodeDefaults = true`, `explicitNulls = false`) the field defaults to `null`, which is **omitted**, not
encoded as `[]` — an `emptyList()` default would have added the key to every existing text-only send.

`MessageAttachmentIds` (`data/network/MessagePayload.kt`) is the `AttachmentUploadLimit` idiom applied to
this bound: `MAX = 32`; `forSend(ids)` drops repeats keeping first-occurrence order, throws
`IllegalArgumentException` when more than `MAX` distinct ids remain, and returns `null` for an empty list
— so "no ids" has exactly one wire form. **The daemon counts the raw `attachment_ids` array elements,
while the acceptance criteria count ids after repeats are dropped; sending the deduplicated list makes
both counts equal**, so a caller can't be refused for repeats it didn't intend as distinct ids.

`ConversationRepository` gains a third `sendMessage` overload (default-throwing, the `setSessionSettings`
idiom — the two-argument member is untouched, so none of the seventeen existing test doubles change):

```kotlin
suspend fun sendMessage(conversationId: String, text: String, attachmentIds: List<String>): Message =
    error("sendMessage with attachments is not implemented for this ConversationRepository")
```

`RemoteConversationRepository`'s two-argument `sendMessage` now calls the three-argument override with
`emptyList()`. The override calls `MessageAttachmentIds.forSend` **before** minting `message_id` or
building the envelope, so a too-many-ids refusal throws before any request id is taken or frame sent —
otherwise it's the unchanged #346 flow (same `RelayRequests.sendAndAwaitReply`, same confirmed-insert only after the
`ack`), with `attachmentIds` set on the DTO. The returned `Message` carries no attachment reference of its
own (#672). A daemon refusal (`attachment.not_found`, `protocol.malformed`) arrives as a correlated
`error` and throws `RelayErrorException` through the same path as any other `sendMessage` failure — the
confirmed insert is unreachable, so a refused send leaves the thread unchanged.

`StableConversationRepository` overrides the three-argument member as
`live.sendMessage(conversationId, text, attachmentIds)` — the repository live at call entry, the same
snapshot-or-throw posture as the two-argument send (see [Stable conversation
repository](stable-conversation-repository.md)).

**Test gotcha:** `observeMessages` sends its own catch-up request as soon as it's collected. A test that
collects the thread first and then asserts on `pump.sent.single()` fails with "more than one element" —
filter the captured frames to `type == "send_message"` instead of asserting there's exactly one frame
sent.

## `createDiscussion(workspace)` — the second mutation (#347)

Creates an unpromoted discussion over v2 `create_conversation` and returns the server-created
`Conversation`. [#347](../codebase/347.md) implements it by **composing** two already-merged building
blocks — #346's `sendAndAwaitReply` correlation primitive and [#318](mobile-protocol-v2-wire-layer.md)'s
`ConversationResponseDto.toConversation()` decode mapper — with one new `create_conversation` request
encoder (`CreateConversationPayloadDto`). The body now lives on `ConversationCommands` (#914,
`data/repository/ConversationCommands.kt`); the repository's `override suspend fun createDiscussion` is a
one-line hand-off.

**The central contrast with `sendMessage`.** `send_message`'s reply is an empty `ack`, so `sendMessage`
*reconstructs* its return `Message` from the input. `create_conversation`'s reply is a **typed
`conversation_created` payload** carrying the **server-assigned** `id` and `cwd`, so `createDiscussion`
*decodes* its return `Conversation` from the reply — it cannot reconstruct it (the server assigns the id,
and a null `workspace` means the server picks the scratch `cwd`). The reply rides the **same** correlation
primitive: `RelayRequests.sendAndAwaitReply` hands the raw reply `JsonElement` back, and the caller decodes it.

The flow (≤ ~10 lines):

```kotlin
// ConversationCommands
suspend fun createDiscussion(workspace: String?): Conversation {
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_CREATE_CONVERSATION, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(CreateConversationPayloadDto(cwd = workspace)),
    )
    val reply = requests.sendAndAwaitReply(request)     // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    conversationList.upsertConversation(conversation)   // confirmed-insert — ONLY after a successful decode (AC #2)
    return conversation
}
```

- **The request encoder is `CreateConversationPayloadDto`** (`is_promoted` always `false`, optional
  `cwd`; `name` unmodeled — discussions are server-auto-named). Under `MobileJson` (`explicitNulls =
  false`) a null `cwd` is **omitted** from the JSON (not `"cwd":null`), which the server decodes
  identically to an absent key (its `*string` field has no `omitempty`) — so `createDiscussion(null)`
  encodes to `{"is_promoted":false}` and means "server assigns the scratch cwd". See the
  [wire-layer doc](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
- **The returned `cwd` is the server's reply value, never the input.** A null `workspace` returns the
  server-assigned scratch cwd (`DEFAULT_SCRATCH_CWD`); this is the one divergence from
  `FakeConversationRepository.createDiscussion`, which picks `cwd = workspace ?: ""` locally (AC #1).
- **Decode precedes the fold, so a garbage reply cannot corrupt the list.** A malformed
  `conversation_created` (missing field / bad `last_used_at`) throws the #318 decode boundary's
  `SerializationException` / `IllegalArgumentException` **before** `ConversationListProjection.upsertConversation`
  runs — the
  projection is never mutated by a failure path (AC #3). The decode runs in the **caller's** coroutine
  (after the `RelayRequests.pendingRequests` entry is removed), so it never throws inside the single inbound
  collector.

### Confirmed-insert via `ConversationListProjection.upsertConversation` (the projection's second writer)

Since #913 this fold lives on `ConversationListProjection` (`data/repository/ConversationListProjection.kt`),
not on the repository:

```kotlin
// ConversationListProjection
fun upsertConversation(conversation: Conversation) {
    projection.update { current ->                       // atomic CAS — retry-merges with a concurrent snapshot
        val existing = current.orEmpty()                 // null projection → single-element list
        val index = existing.indexOfFirst { it.id == conversation.id }
        if (index >= 0) existing.toMutableList().apply { this[index] = conversation }  // upsert by id
        else existing + conversation
    }
}
```

`createDiscussion` folds the returned `Conversation` into `projection` **after** the reply, so
`observeConversations` re-emits to include it without waiting on a server-pushed `conversations` snapshot
(AC #2 — deterministic and self-contained). It is an **upsert by `id`** (not blind append), so it's
idempotent against a re-delivered create and retry-merges with a concurrent authoritative snapshot. The
`conversations`-snapshot collector arm keeps its blind full-replace unchanged: because #316 and #318 fill
the **identical** four list-tier placeholders (`currentSessionId=""`, `sessionHistory=emptyList()`,
`isSleeping=false`, `archived=false`), the folded `Conversation` is field-equal to the same conversation
mapped later from a snapshot, so `StateFlow` conflation suppresses a redundant re-emit.
[#348](../codebase/348.md) (`promote`) **reuses this fold verbatim** — its second call site, where an
upsert replaces the existing *unpromoted* discussion entry in place with the promoted one. The second
consumer confirms the helper is right-shaped: no abstraction was extracted (the #347 open question is
resolved, not deferred). Since #914 both `createDiscussion` and `promote` reach it as `conversationList`,
the `ConversationListProjection` instance `ConversationCommands` is constructed with.

> **Confirmed-insert is the trust property (mirrors #346).** A conversation is folded into the read
> projection **only** on a server `conversation_created` success reply, never speculatively and never on
> a failure path — so the list never shows a conversation the server did not create, by construction
> (the fold is the last step, after both `RelayRequests.sendAndAwaitReply` *and* the decode succeed; no
> rollback path
> to get wrong). `RelayRequests.mapError` is reused as-is — its `conversation.not_found` →
> `IllegalArgumentException`
> branch is **not exercised** here, since create references no existing conversation.

## `promote(conversationId, name, workspace)` — the third mutation (#348)

Promotes an existing (scratch) discussion into a named, persistent channel over v2
`promote_conversation`, and returns the now-promoted `Conversation`. [#348](../codebase/348.md) is
`createDiscussion` with **three deltas** — otherwise byte-for-byte the same build-request →
`sendAndAwaitReply` → decode-reply → confirmed-fold → return shape — and is almost pure composition:
it **reuses** #346's `sendAndAwaitReply` + `mapError`, #318's `ConversationResponseDto.toConversation()`,
and #347's `upsertConversation`, adding only a new `promote_conversation` request encoder
(`PromoteConversationPayloadDto`) and one `cwd`-resolution line. The body now lives on `ConversationCommands`
(#914, `data/repository/ConversationCommands.kt`), beside `createDiscussion`; the repository's `override
suspend fun promote` is a one-line hand-off.

The flow (≤ ~10 lines):

```kotlin
// ConversationCommands
suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation {
    // Null workspace ("promote in place") resolves to the conversation's existing cwd from the read
    // projection — the remote analog of the fake's `workspace ?: record.conversation.cwd`.
    val cwd = workspace ?: conversationList.current().firstOrNull { it.id == conversationId }?.cwd ?: ""
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_PROMOTE_CONVERSATION, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            PromoteConversationPayloadDto(conversationId = conversationId, name = name, cwd = cwd),
        ),
    )
    val reply = requests.sendAndAwaitReply(request)     // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    conversationList.upsertConversation(conversation)   // confirmed-upsert — ONLY after a successful decode (AC #2/#3)
    return conversation
}
```

- **Delta 1 — three required wire fields.** The request encoder
  `PromoteConversationPayloadDto(conversationId, name, cwd)` carries all three as **non-null** `String`s
  (contrast `CreateConversationPayloadDto`'s optional `cwd`) — there is no `explicitNulls` elision to
  reason about; the encoded payload always has `conversation_id`, `name`, `cwd`. See the
  [wire-layer doc](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346).
- **Delta 2 — the reply is `conversation_updated`** (routed into the **same** success arm as
  `conversation_created`), decoded through the **same** #318 `ConversationResponseDto` (one DTO models
  both response types). A malformed reply throws the #318 decode exception in the **caller's** coroutine
  **before** `ConversationListProjection.upsertConversation` runs, so the projection is never mutated by a
  garbage success reply
  (AC #3, "no partial promote") — and never inside the lone inbound collector.
- **Delta 3 — `cwd` resolution.** The domain `workspace` arg is nullable but the wire `cwd` is required.
  A **non-null** `workspace` (the `DEDICATED` choice) is sent verbatim; a **null** `workspace` (the
  `SCRATCH` choice / discussion-list default — both production callers reach this) means "promote in
  place" and resolves to the conversation's existing `cwd`, read from `ConversationListProjection.current()`.
  The `?: ""` final fallback is only reachable when `workspace` is null *and* the conversation is absent
  from the projection — **not reachable from the shipped UI** (it only promotes a visible, hence loaded,
  discussion); no throw is added for that should-not-happen state (Evidence-Based Fix Selection). The
  **returned** `cwd` always comes from the reply (server-authoritative), never the resolved request value
  (AC #1) — identical to #347's "return the reply's cwd, not the input."

The `ConversationListProjection.upsertConversation` upsert **replaces the existing unpromoted discussion
entry in place** (same
`id`), so `observeConversations` re-emits with the conversation now in the **Channels** tier and gone from
**Discussions** (AC #2) — list count unchanged, no duplicate.

> **`conversation.not_found` is meaningful here — the AC-#3 branch #347 could not exercise.** Unlike
> `createDiscussion`, `promote` references an **existing** conversation, so promoting an unknown id is a
> real server error. `RelayRequests.mapError` is reused **unchanged** — its `conversation.not_found` →
> `IllegalArgumentException` branch (vs. `RelayErrorException` for any other code) is driven end-to-end by
> #348's tests for the first time. The confirmed-upsert is the trust property (mirrors #346/#347): the
> list never shows a promote the server did not perform, by construction.

## `rename(conversationId, name)` — the fourth mutation (#530)

Renames an existing conversation (channel or discussion) over v2 `rename_conversation`, and returns the
renamed `Conversation`. [#530](../codebase/530.md) is `promote` ([#348](../codebase/348.md)) **minus
`cwd`** — otherwise byte-for-byte the same build-request → `sendAndAwaitReply` → decode-reply →
confirmed-fold → return shape, and pure composition: it reuses #346's `sendAndAwaitReply` + `mapError`,
\#318's `ConversationResponseDto.toConversation()`, and #347's `upsertConversation`, adding only a new
`rename_conversation` request encoder (`RenameConversationPayloadDto`). The body now lives on
`ConversationCommands` (#914, `data/repository/ConversationCommands.kt`), beside `promote`; the
repository's `override suspend fun rename` is a one-line hand-off.

The flow (≤ ~10 lines):

```kotlin
// ConversationCommands
suspend fun rename(conversationId: String, name: String): Conversation {
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_RENAME_CONVERSATION, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            RenameConversationPayloadDto(conversationId = conversationId, name = name),
        ),
    )
    val reply = requests.sendAndAwaitReply(request)     // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    conversationList.upsertConversation(conversation)   // confirmed-upsert — ONLY after a successful decode
    return conversation
}
```

- **No `cwd` — the one structural delta from `promote`.** The wire SSOT
  (`RenameConversationPayload`, server #820) is deliberately **not** a reuse of
  `PromoteConversationPayloadDto`: a rename neither has nor means a `cwd`. `RenameConversationPayloadDto`
  carries exactly `conversation_id` / `name`, both required.
- **The reply is `conversation_updated`**, routed into the **same** success arm `promote`'s reply already
  uses (added by #348) — no `onInbound` change needed. Decoded through the **same** #318
  `ConversationResponseDto`. A malformed reply throws the #318 decode exception in the caller's coroutine
  **before** `ConversationListProjection.upsertConversation` runs, so the projection is never mutated by a
  garbage success reply.
- **`name` is forwarded verbatim.** The `RenameDialog` is the sole trim authority; the daemon re-validates
  and rejects empty/whitespace titles server-side (`protocol.malformed`), surfaced as an ordinary
  `RelayErrorException` — no second client-side validation surface is added.
- **The returned `name` is server-authoritative** (the reply's value, not the request's) — identical to
  `promote`'s "return the reply's cwd, not the input" discipline.
- **`conversation.not_found` → `IllegalArgumentException`**, reusing `RelayRequests.mapError` unchanged.
  Reachable only
  if the conversation is deleted server-side between opening the thread and renaming — the call site
  (`ThreadViewModel`'s `RenameSubmit`) always passes the currently-open, hence server-known,
  `conversationId`, matching `promote`'s reachability profile. The [`#490`](../codebase/490.md) guard
  deliberately does **not** catch `IllegalArgumentException` (crash-as-programming-bug-signal), so this
  path is unreachable-by-construction from the shipped UI, not silently swallowed.

The `ConversationListProjection.upsertConversation` upsert **replaces the existing conversation entry in
place** (same `id`), so
`observeConversations` re-emits with the new name in whichever tier (Channels/Discussions) it already sits
in, and the thread top bar's `displayName` (derived from the same projection) re-emits too — both AC
surfaces from one fold, no ViewModel change.
