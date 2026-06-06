# Spec — `RemoteConversationRepository.promote` over v2 (`promote_conversation`) (#348)

> Fills the last `#314` mutation stub (`promote`) on the already-merged `RemoteConversationRepository`
> with a live Mobile Protocol v2 implementation: encode a `promote_conversation` request, send-and-await
> its correlated `conversation_updated` reply through the class's single inbound collector, decode that
> reply into the returned promoted `Conversation`, and **confirmed-upsert** it into the read-path list
> projection only after the reply lands. Composes three already-merged building blocks — #346's
> `sendAndAwaitReply` correlation primitive, #318's `ConversationResponseDto.toConversation()` decode
> mapper (which already models `conversation_updated`), and #347's `upsertConversation` fold — with one
> new `promote_conversation` request encoder. Mirrors the merged #347 `createDiscussion` slice almost
> exactly; the one new design decision is **resolving the `cwd` to send when `workspace` is null**.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:430-434` — the
  `promote` stub you replace (the **only** method this slice fills). **This is the only production
  class you modify.**
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:399-428` —
  `createDiscussion` (#347): the **template to mirror** for the build-request → `sendAndAwaitReply` →
  decode-reply → confirmed-upsert → return shape. `promote` is the same shape with three differences
  (called out in § Design): it sends a `promote_conversation` (carrying `conversation_id`/`name`/`cwd`),
  awaits `conversation_updated` (not `conversation_created`), and resolves the wire `cwd` from the
  `workspace` arg **or** the existing projection entry.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:179-187` — the
  `TYPE_ACK, TYPE_CONVERSATION_CREATED ->` success arm and its comment (which already says
  "#348 routes `conversation_updated` through this same arm"). Extend it to
  `TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED ->`; the body is unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:288-305` — the
  **reused** `upsertConversation(conversation)` helper (#347): an atomic `update {}` CAS upsert by `id`
  into `projection`. `promote` calls it **verbatim** — an upsert replaces the existing (unpromoted)
  discussion entry in place with the promoted one. **Do not add a new fold helper.**
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:244-261` —
  `sendAndAwaitReply(request): JsonElement`, the **reused** #346 register→send→await primitive
  (returns the reply payload — the bare conversation object for a `conversation_updated`; throws
  `IllegalStateException` on a not-`Open` pump and rethrows the collector's exceptional completion on a
  server `error`). `promote` calls it verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:201-222` — the
  **reused** `mapError` (#346). **Note the contrast with #347:** for `promote`, `conversation.not_found`
  **is** a meaningful error (promoting an unknown conversation) and is exercised here — `mapError` already
  maps it to `IllegalArgumentException` (AC #3). No change to `mapError`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:67-78` — the
  `projection` `StateFlow` and its KDoc. `promote` is a **third** read source/writer: it reads
  `projection.value` to resolve the existing `cwd` (null-workspace case) and upserts the promoted result.
  Update the KDoc to name `promote`'s confirmed-upsert alongside `createDiscussion`'s.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:504-557` — the
  `companion object` constants. Add `TYPE_PROMOTE_CONVERSATION` + `TYPE_CONVERSATION_UPDATED` next to the
  existing `TYPE_CREATE_CONVERSATION` / `TYPE_CONVERSATION_CREATED` (526-530).
- `app/src/main/java/de/pyryco/mobile/data/network/CreateConversationPayloadDto.kt` — the **encode-only
  request-DTO pattern to copy** for the new `PromoteConversationPayloadDto` (`@Serializable`, `@SerialName`
  snake_case, Go-struct field order, KDoc citing the server SSOT, lives in its own file per
  [[ktlint-filename-rule-single-class]]).
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt:9-73` — the **reused** #318
  decode boundary. `ConversationResponseDto` + `toConversation()` **already model `conversation_updated`**
  (the KDoc says so explicitly — one DTO models both `conversation_created` and `conversation_updated`).
  `promote` decodes the reply through it. **Do not re-implement it**; the four domain placeholders
  (`currentSessionId=""`, `sessionHistory=emptyList()`, `isSleeping=false`, `archived=false`) are this
  mapper's, not yours.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson`
  (`encodeDefaults=true`, `explicitNulls=false`, `ignoreUnknownKeys=true`). All three promote-request
  fields are **non-null** `String`, so `explicitNulls` never elides one — the encoded payload always
  has `conversation_id`, `name`, `cwd`. Always (de)serialize through `MobileJson`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:145-165` — the
  observable contract to mirror: `promote` returns the conversation with `isPromoted=true`, the new
  `name`, and `cwd = workspace ?: record.conversation.cwd`. **The `workspace ?: existing.cwd` resolution
  is the behavior § Design reproduces over the wire** (the remote reads the existing cwd from
  `projection` instead of a local record).
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-21` — the 9-field domain target **and**
  `DEFAULT_SCRATCH_CWD` (`"~/.pyrycode/scratch"`, line 21). Tests reference the constant.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:743-973` — the
  **#347 `createDiscussion` test block to mirror**: the `startCreate` launch helper (998-1005, the
  pattern for a new `startPromote`), `conversationCreatedEnvelope` (1008-1028, the pattern for a new
  `conversationUpdatedEnvelope`), `errorEnvelope` (1035-1048), `FakeSessionPump` (1141-1159),
  `collectConversations` (1050-1057), and `MIXED_FIXTURE` (1165-1171 — its `disc` row is the
  unpromoted-scratch conversation to promote). Also: **remove** `assertUnsupported { repo.promote("c",
  "name") }` from `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (line 241) and add
  `promote (#348)` to its 238-240 comment — `promote` is no longer a stub (exactly as #347 did for
  `createDiscussion`).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:30-34` — the
  `promote(conversationId, name, workspace: String? = null): Conversation` contract. Signature is
  **unchanged** — no consumer call-site cascade. Production callers
  (`DiscussionListViewModel:104` passing `workspace=null`; `ThreadViewModel:272` passing
  `resolveWorkspace(...)` which is `null` for the SCRATCH choice) are untouched.
- `docs/specs/architecture/318-conversation-response-payload-domain-mapping.md` — the #318 decode-slice
  spec; confirms the one-DTO-models-both-responses decision and the strict-decode posture this slice
  relies on.
- `docs/lessons.md` — not present in this repo; nothing to read.

## Context

Phase 4 backend integration. `RemoteConversationRepository.promote` currently throws
`UnsupportedOperationException` (`#314` stub). This slice makes it promote a conversation over the landed
Noise session pump and surface the now-promoted channel in the list.

This is the **third and last** of the #314 mutation slices, after #346 (`sendMessage`) and #347
(`createDiscussion`). All three edit this one class plus its test, so they **serialize** — #348 builds on
#347's merged state. The three building blocks this slice needs are **all on `main`** (per
[[phase4-request-encoders-live-in-mutation-tickets]] — the request half is built here; there is no shared
request-mapping slice):

- **Correlation** — #346's `sendAndAwaitReply(Envelope): JsonElement` + the `pendingRequests` registry
  (keyed on `Envelope.id`, completed by the single `init` collector on the matching `inReplyTo`).
- **Response decode** — #318's `ConversationResponseDto.toConversation()`, which **already models
  `conversation_updated`** (its KDoc names it): the bare conversation object the reply carries at
  `Envelope.payload`.
- **List fold** — #347's `upsertConversation(conversation)`: the atomic CAS upsert-by-id into `projection`.

This slice composes those three with one **new** `promote_conversation` request encoder.

**Structural parity with #347 (the central design point).** `promote` is `createDiscussion` with three
deltas:

1. **Request** carries three fields (`conversation_id`, `name`, `cwd`), all required — vs. `create`'s
   two-field optional payload.
2. **Reply** is `conversation_updated` (routed into the existing success arm) — vs. `conversation_created`.
   Both decode through the **same** #318 `ConversationResponseDto`.
3. **`cwd` resolution** — `promote`'s `workspace` arg is `String?`, but the wire `cwd` is a required
   `String`. The null case resolves to the conversation's existing `cwd` (see § Design). `create` had no
   such resolution (it omits a null `cwd` and the server assigns a scratch cwd).

## Design source

N/A — pure `data/`-layer wire/transport work; no UI, no Figma. The "design source" is the byte contract:
server SSOT `internal/protocol/conversations_write.go` (`PromoteConversationPayload` /
`ConversationUpdatedPayload`, #274) and `protocol-mobile.md §§ promote_conversation /
conversation_updated`. The user-facing promote UI (`SaveAsChannelDialog`, the promotion confirmation
dialog) is already shipped and untouched by this `data/`-layer slice.

## Design

### New request DTO — `PromoteConversationPayloadDto`

New file `app/src/main/java/de/pyryco/mobile/data/network/PromoteConversationPayloadDto.kt` (one public
`@Serializable data class`, so the filename matches the type per [[ktlint-filename-rule-single-class]]).
Lives in its own file — symmetric with `CreateConversationPayloadDto.kt` and the decode-side
`ConversationResponseDto.kt`; **not** in `MessagePayload.kt`. `data/`-portable, no `android.*`.

Wire SSOT — server `PromoteConversationPayload` (#274), all three fields **required** (non-pointer
`string`): "a promoted conversation must carry a name and an effective cwd, and the `conversation_id` must
resolve to an existing row."

```go
type PromoteConversationPayload struct {
    ConversationID string `json:"conversation_id"`
    Name           string `json:"name"`
    Cwd            string `json:"cwd"`
}
```

The Kotlin encoder is the byte-mirror — three non-null fields, **no defaults** (every field is always
sent), snake_case `@SerialName` on `conversation_id`, declaration order matching the Go struct:

- `conversationId: String` → `@SerialName("conversation_id")`
- `name: String`
- `cwd: String`

KDoc must (a) cite the #274 SSOT, (b) state this is **encode-only** (the phone never decodes a
`promote_conversation` — model only what is sent, the `CreateConversationPayloadDto` discipline), and
(c) note all three fields are non-null/required (contrast `CreateConversationPayloadDto`'s optional
`cwd`), so a future reader does not "relax" `cwd` to nullable. Decode is by key **name**, so field order
has no wire effect; mirror the Go order for readability.

### `cwd` resolution — the one new decision (null `workspace`)

`promote(conversationId, name, workspace: String?)` has a **nullable** `workspace`, but the wire `cwd` is
a **required** `String`. Both production callers reach the null case in the common flow:

- `DiscussionListViewModel.confirmPromotion()` always passes `workspace = null`.
- `ThreadViewModel`'s `resolveWorkspace(...)` returns `null` for `WorkspaceChoice.SCRATCH` (and a real
  path for `DEDICATED`).

A null `workspace` means **"promote in place — keep the conversation's existing (scratch) cwd"**, exactly
the fake's `cwd = workspace ?: record.conversation.cwd`. The remote's analog to `record.conversation.cwd`
is the conversation's entry in the read projection. So:

```kotlin
val cwd = workspace ?: projection.value?.firstOrNull { it.id == conversationId }?.cwd ?: ""
```

- **Non-null `workspace`** (DEDICATED choice) → sent verbatim as `cwd`.
- **Null `workspace`** (SCRATCH choice / discussion-list default) → the existing `cwd` read from the
  projection snapshot. In the real flow the conversation being promoted is always a **visible, hence
  loaded** discussion, so the lookup hits (the `disc` row in `MIXED_FIXTURE` models exactly this).
- **`?: ""` final fallback** — only reachable if `workspace` is null *and* the conversation is absent from
  `projection` (projection still `null`, or the id is unknown). Not reachable from the shipped UI; the
  empty `cwd` is then either server-defaulted or rejected as a server `error` (surfaced via AC #3), never
  a crash and never a projection corruption. **No throw is added** for this should-not-happen state
  (Evidence-Based Fix Selection — no observed failure; adding a `check`/throw would be a defense for an
  unobserved mode).

**Why resolve locally instead of sending `cwd: ""` and letting the server default it.** The #274 wire
example always shows a real `cwd` for `promote_conversation` (unlike `create_conversation`, which shows
`"cwd": null`), and the SSOT comment requires "an effective cwd." The server's `promote_conversation`
*wire handler* is not yet built (#274 shipped only the DTO structs; the `Registry.Promote(id, name)`
primitive, #218, does not even consume `cwd`), so whether the server defaults an empty `cwd` on promote is
**undefined**. Resolving locally is robust regardless of that undefined behavior and is the faithful mirror
of the fake — so the common SCRATCH path sends a real cwd and works whether or not the server later defines
empty-cwd handling. Reading `projection.value` is a lock-free synchronous `StateFlow` snapshot — no
suspension, no new dependency (`projection` is already a member). Inline the one-liner; extract a private
`existingCwd(conversationId)` helper only if it reads cleaner (it is a single expression — Simplicity
First).

The **returned** `Conversation`'s `cwd`, by contrast, always comes from the **reply** (`conversation_updated`
→ `toConversation()`), server-authoritative — never the resolved request value (AC #1 "the resolved cwd").
Identical to #347's "return the reply's cwd, not the input."

### Routing `conversation_updated` into the completion path

`conversation_updated` is a **correlated success reply** whose payload is the bare conversation object —
the same completion shape as `conversation_created`. The success arm (179-187) already anticipates it
("#348 routes `conversation_updated` through this same arm"). Add the third type to the same branch — one
shared body, unchanged:

```kotlin
TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED ->
    envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
```

Update the arm's comment to name `conversation_updated` (the reply to `promote_conversation`, decoded by
`promote`). A `conversation_updated` whose `inReplyTo` matches no pending entry (or is null) stays a no-op
— `complete` is idempotent, and `conversation_updated` is **also** the server's unsolicited broadcast to
all phones on change (#274: "broadcast to all phones on this server-id"), which carries no `inReplyTo` and
must remain a harmless no-op here (the authoritative `conversations` snapshot, not this delta, drives an
unsolicited list refresh). The reply decode happens in the **caller's** coroutine (see below), not the
collector, so a malformed `conversation_updated` never throws inside `onInbound`.

New companion constants alongside `TYPE_CREATE_CONVERSATION` / `TYPE_CONVERSATION_CREATED`:

```kotlin
const val TYPE_PROMOTE_CONVERSATION = "promote_conversation"   // request
const val TYPE_CONVERSATION_UPDATED = "conversation_updated"   // correlated success reply (also unsolicited)
```

### `promote` itself

```kotlin
override suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation
```

Flow (≤ ~10 lines, mirroring `createDiscussion`):

1. Resolve `cwd` (§ "`cwd` resolution" above).
2. Build the request `Envelope(id = requestId.incrementAndGet(), type = TYPE_PROMOTE_CONVERSATION,
   ts = Clock.System.now().toString(), payload =
   MobileJson.encodeToJsonElement(PromoteConversationPayloadDto(conversationId, name, cwd)))`.
3. `val reply = sendAndAwaitReply(request)` — throws on server `error` / not-`Open`; returns the
   `conversation_updated` payload (the bare conversation object) on success.
4. Decode: `val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()`.
5. **Only after a successful decode**, `upsertConversation(conversation)` (the reused #347 fold), then
   `return conversation`.

The decode (step 4) runs in the caller's coroutine **after** `sendAndAwaitReply` returns (the
`pendingRequests` entry already removed by its `finally`). A malformed `conversation_updated` throws the
#318 boundary's typed exception (`SerializationException` / `IllegalArgumentException`) **before** step 5,
so `projection` is never mutated by a garbage reply. No catch/wrap here; the ViewModel surfaces it.

The upsert (step 5) **replaces** the existing unpromoted discussion entry (same `id`) in `projection` with
the promoted `Conversation` — so `observeConversations` re-emits with the conversation now in the Channels
tier and gone from the Discussions tier (AC #2). The `upsertConversation` dedup-by-id makes this an
in-place replacement, not a duplicate append.

**Update the `projection` KDoc (67-78)** to name `promote`'s confirmed-upsert as a writer (alongside
`createDiscussion`'s), and note `promote` also **reads** `projection.value` to resolve the existing cwd.
Leaving the writer list stale would be a correctness-doc lie (same discipline #347 applied).

## State + concurrency model

- **`projection` gains a third interaction.** Writers: the `init` collector (authoritative full-replace),
  `createDiscussion`'s confirmed-insert (#347), and now `promote`'s confirmed-upsert. `promote` also
  **reads** `projection.value` (a lock-free snapshot) to resolve the cwd. Data-safety holds: the upsert is
  the same atomic `update {}` CAS as #347; the collector's full-replace is authoritative and convergent.
  No new `StateFlow`, no parallel mutable state.
- **No new collector, no second subscription.** `conversation_updated` correlation rides the existing
  single `init` collector — the absolute constraint from the read-path slices
  ([[phase4-v2-wire-no-streaming]], [[phase4-no-central-flag-gate-per-piece]]). `promote` never collects
  `pump.inbound`.
- **Suspend/await.** `promote` suspends on `CompletableDeferred.await()` (inside `sendAndAwaitReply`) in
  the *caller's* coroutine (a ViewModel `viewModelScope`), not the connection scope. Caller cancellation →
  `CancellationException` → the primitive's `finally` removes the registry entry (no leak). The reply is
  delivered by the connection-scoped collector; `complete`/`completeExceptionally` are thread-safe and
  idempotent across the two coroutines.
- **cwd-resolution read vs. upsert write are not atomic-as-a-pair, and need not be.** `promote` reads
  `projection.value` (step 1) then upserts (step 5) much later (after the network round-trip). A
  concurrent `conversations` snapshot landing in between only changes which `cwd` the request carries (a
  benign read of an authoritative value); the later upsert is an independent CAS. No TOCTOU of consequence
  — the resolved cwd is request data, not a guarded invariant.
- **Dispatcher.** None chosen here — in-memory state-flow reads + a non-blocking `pump.send`. No IO/Main
  boundary in this slice (transport dispatching lives below the pump).

## Error handling

| Failure | Surfaced as | `projection` mutated? |
|---|---|---|
| Server `error` reply, `conversation.not_found` (promoting an unknown conversation) | `IllegalArgumentException` via #346 `mapError` (AC #3) | No |
| Server `error` reply, any other code (`protocol.malformed`, `server.binary_offline`, …) | `RelayErrorException(code, retryable, message)` via #346 `mapError` (AC #3) | No |
| Not connected (`pump.send` returns `false`) | `IllegalStateException` (#346 `sendAndAwaitReply` `check`) | No |
| Malformed `conversation_updated` reply (missing field / bad `last_used_at`) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated | No (decode precedes the upsert) |
| Malformed server `error` payload | #346 fallback `RelayErrorException` — waiter unblocked, collector survives | No |
| Caller coroutine cancelled mid-await | `CancellationException` (standard) | No |

The list is **never** mutated on any failure path — the confirmed-upsert runs only after both
`sendAndAwaitReply` *and* the reply decode succeed (AC #3 "no partial promote"). No silent failure: every
non-success path throws from the suspend call. **Contrast with #347:** `conversation.not_found` is a
*meaningful* error here (`promote` references an existing conversation) and is exercised in the tests;
`mapError` already maps it to `IllegalArgumentException`, so it is reused **unchanged**.

## Testing strategy

Unit only (`./gradlew test`), extending `RemoteConversationRepositoryTest` with the existing `runTest` +
`runCurrent()` + `FakeSessionPump` idiom (no device, no real crypto). Mirror the #347 `createDiscussion`
block. Two new helpers:

- `startPromote(repo, conversationId, name, workspace): () -> Result<Conversation>` — launches `promote`
  on `backgroundScope` (it suspends awaiting the reply) and returns a getter for the eventual `Result`
  (copy of `startCreate`, 998-1005).
- `conversationUpdatedEnvelope(inReplyTo, id, cwd, isPromoted=true, name, lastUsedAt, envId=99L)` —
  `type="conversation_updated"`, payload a bare conversation object built via
  `MobileJson.parseToJsonElement(...)` (copy of `conversationCreatedEnvelope`, 1008-1028, with the type
  string and `isPromoted=true` default swapped).

Also update `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (233-247): remove the
`promote("c", "name")` assertion (line 241) and add `promote (#348)` to the 238-240 comment.

New scenarios (bullet form — write in the project idiom; do not paste full bodies). Promote the `disc`
row from `MIXED_FIXTURE` (id `"disc"`, unpromoted, `cwd = "~/.pyrycode/scratch"`) unless noted:

- **AC #1/#4 — request shape, explicit workspace.** Seed nothing; `startPromote(repo, "disc",
  "weekly-planning", "/work/wp")`; `runCurrent()`; read the single sent envelope: assert
  `type == "promote_conversation"` and `payload ==
  {"conversation_id":"disc","name":"weekly-planning","cwd":"/work/wp"}`. Unblock with a correlated
  `conversation_updated`.
- **AC #1/#4 — request shape, null workspace resolves the existing cwd from the projection.**
  `collectConversations(repo, All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))`; `runCurrent()`;
  `startPromote(repo, "disc", "weekly-planning", null)`; `runCurrent()`; assert the sent payload `cwd ==
  "~/.pyrycode/scratch"` (`DEFAULT_SCRATCH_CWD`, referenced as the **constant**) — i.e. resolved from
  `disc`'s projection entry, not `""`. This is the new-decision test; it must seed the projection first.
- **AC #1/#4 — null workspace with no projection entry falls back to `""`.** No `conversations` push;
  `startPromote(repo, "ghost", "n", null)`; `runCurrent()`; assert the sent payload `cwd == ""` (the
  unreachable-in-practice fallback; proves it does not crash and sends a well-formed-but-empty cwd).
- **AC #1 — `conversation_updated` reply → promoted `Conversation` with the reply's cwd.**
  `startPromote(repo, "disc", "weekly-planning", null)` (or any); push
  `conversationUpdatedEnvelope(inReplyTo = sentId, id = "disc", isPromoted = true, name =
  "weekly-planning", cwd = "/work/wp", lastUsedAt = "2026-05-08T10:34:30Z")`; `runCurrent()`; assert the
  `Result` is success and the returned `Conversation` has `id == "disc"`, `isPromoted == true`, `name ==
  "weekly-planning"`, `cwd == "/work/wp"` (the **reply's** cwd, proving the return uses the
  server-assigned value, not the request's resolved cwd).
- **AC #2 — `observeConversations` re-emits the conversation now promoted (Channels tier, gone from
  Discussions).** `collectConversations(repo, All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))`;
  assert baseline `[chan, disc]`; `startPromote(repo, "disc", "wp", null)`; push a `conversation_updated`
  for `disc` with `isPromoted=true` and a later `last_used_at`; `runCurrent()`; assert the latest `All`
  emission still has 2 entries but `disc` is now promoted (no duplicate `disc`). Add a
  `ConversationFilter.Channels` collector asserting `disc` **appears** in Channels after promote, and a
  `ConversationFilter.Discussions` collector asserting `disc` is **absent** from Discussions after promote
  (the tier flip — the AC's "now appears promoted"). Folding via `upsertConversation` replaces the entry
  in place, so the list count is unchanged.
- **AC #3 — `conversation.not_found` → `IllegalArgumentException`, list unchanged.**
  `collectConversations(All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))`; `startPromote(repo,
  "missing", "n", "/p")`; push `errorEnvelope(sentId, code = "conversation.not_found")`; `runCurrent()`;
  assert the `Result` is a failure with `IllegalArgumentException`; assert the list is still `[chan, disc]`
  unchanged (no partial promote). **This is the AC-#3-named branch #347 could not exercise.**
- **AC #3 — other server `error` → `RelayErrorException`, list unchanged.**
  `startPromote(repo, "disc", "n", "/p")`; push `errorEnvelope(sentId, code = "server.binary_offline",
  retryable = true)`; assert failure with `RelayErrorException` exposing that `code`; assert `disc` is
  still unpromoted in the list.
- **AC #3/#4 — not connected → `IllegalStateException`, list unchanged.** `FakeSessionPump.sendResult =
  false`; `collectConversations(All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))`;
  `startPromote(repo, "disc", "n", "/p")`; `runCurrent()`; assert failure with `IllegalStateException`;
  assert no `promote_conversation` reply was processed and the list is unchanged (`disc` still unpromoted).
- **Malformed `conversation_updated` reply → throws, list unchanged.** Push a `conversation_updated` whose
  payload **omits a required field** (e.g. drop `cwd`); assert `promote` fails with the #318 decode
  exception (a `SerializationException`, which is an `IllegalArgumentException` subtype); assert the list
  is unmutated (the upsert never ran). Proves a garbage *success* reply cannot inject a partial promote.
- **(Correlation hygiene — light.)** Broadly covered by #346's `uncorrelatedAck_isNoOp`; optionally add a
  `conversation_updated` with an `inReplyTo` matching no pending request (the unsolicited-broadcast shape)
  and assert it is a no-op and a subsequent real `promote` round-trip still completes.

`ComposeTestRule` is not involved (pure data layer).

## Open questions

- **Server empty-`cwd` semantics on promote (informational).** The `?: ""` fallback only fires in the
  unreachable-from-UI case (null workspace + conversation absent from the projection). The server's
  `promote_conversation` wire handler is not yet built (#274 shipped only DTOs), so whether it defaults or
  rejects an empty `cwd` is undefined. No action now — the common path resolves a real cwd from the
  projection, and a server rejection of `""` surfaces cleanly as `RelayErrorException` (AC #3). Revisit
  only if the server handler lands and rejects empty for the common path (it should not — the common path
  never sends empty). Flagged, not fixed (Evidence-Based Fix Selection).
- **Connection-drop-mid-promote leak.** Same single-entry `pendingRequests` leak #346/#347 documented: if
  the connection scope is cancelled while a caller awaits, the deferred never completes until the *caller*
  is cancelled (ViewModel scope cancels on screen exit → `CancellationException`). No timeout added (no
  observed hang; a timeout value is a product call). A future slice or the connection coordinator (#302)
  may fail all `pendingRequests` on disconnect. Flagged, not fixed.
- **`upsertConversation` is now a confirmed two-consumer helper.** #347 deferred any generalization until a
  second call site; this slice **is** that second site and reuses it verbatim — no further abstraction
  needed (the helper already fits both). Resolved, not deferred.

## Security review (`security-sensitive`)

> The ticket carries the `security-sensitive` label, so this adversarial pass is mandatory. The canonical
> `security-review.md` referenced by the architect prompt is **not symlinked into this worktree** (same as
> #346/#347/#318); the pass below walks the standard trust-boundary / input / secrets / IPC / crypto / DoS
> / logging / concurrency categories on its own merits and renders a verdict. **Verdict: PASS.**

- **Trust boundaries.** The only untrusted input is the inbound `conversation_updated` (or `error`)
  payload, decoded through the strict, all-or-nothing #318 `ConversationResponseDto` boundary (resp. #346
  `mapError`). A malformed success reply throws the typed decode exception **before** any `Conversation` is
  built and **before** the projection upsert — so a hostile/buggy server cannot inject a partial or
  `null`-punned conversation into the list (the #318-reviewed property, reused here). The request's
  `conversation_id`/`name` are client-supplied (the user's chosen name, an id the user is acting on);
  `cwd` is resolved from the client's own `workspace` arg or its own projection — no payload-derived value
  is reflected unsafely. No `eval`, shell, SQL, or path/URL construction from payload data → no injection
  surface. The channel is the already-authenticated Noise session; the relay/server is the user-paired
  trusted endpoint.
- **Confirmed-upsert is the security property.** A promoted conversation is folded into the read projection
  **only** on a server `conversation_updated` success reply, never on a failure path and never
  speculatively — so the list never shows a promote the server did not perform (AC #3 "no partial
  promote"). Satisfied by construction (the upsert is step 5, after both await and decode succeed); no
  rollback path to get wrong. Mirrors #346/#347's confirmed-write property.
- **The `cwd` read from `projection` is the client's own state.** The null-workspace resolution reads a
  `cwd` the client already holds (delivered by a prior authoritative `conversations` snapshot, itself
  decoded through the #316 boundary) and echoes it back to the server as request data. It is not
  attacker-injected at this step, is never opened/resolved/traversed as a path here (opaque request
  string), and the `?: ""` fallback is a benign empty value. No path-traversal or SSRF surface in this
  slice; any future code turning a server-supplied `cwd` into an actual `File` must do its own
  canonicalisation + boundary check (the standing #318/#347 obligation, named here, owned by the
  I/O-performing ticket).
- **`conversation_updated` is also an unsolicited broadcast.** #274 says the server broadcasts
  `conversation_updated` to all phones on change. This slice routes it through the correlated success arm,
  which **safely no-ops** an envelope with no matching pending `inReplyTo` (the broadcast carries none).
  So an attacker cannot use a forged/broadcast `conversation_updated` to silently mutate the projection
  via this path — only a reply correlated to a *live local promote request* completes a deferred, and the
  resulting upsert is the conversation the local user asked to promote. An unsolicited change still
  refreshes the list only via the authoritative `conversations` snapshot, not this delta. No new injection
  vector.
- **Correlation integrity.** Request ids are client-minted and monotonic (`AtomicLong`) within a session;
  the server only echoes them as `inReplyTo`. A duplicate/forged `inReplyTo` resolves the matching pending
  request at most once (`CompletableDeferred` completion is idempotent). Cross-request confusion is bounded
  to the session's own id space against the paired server — no code change warranted under the paired-trust
  model (no observed attack).
- **Secret / sensitive-data exposure.** The `promote_conversation` request and `conversation_updated`
  reply carry no secret — `conversation_id`, `name`, `cwd` (a path string), `is_promoted` (bool), and one
  timestamp. The design logs **nothing**: no `Log.*` of the request `Envelope`, the reply `JsonElement`,
  the decoded `Conversation`, the resolved `cwd`, or `name`. `cwd`/`name` are non-secret but
  privacy-adjacent (filesystem path, user-chosen) — keep them off the log, matching the existing collector
  posture. No tokens or keys are touched. **Developer constraint:** do not add logging of the
  request/reply/`cwd`/`name`/`Conversation`.
- **Inter-process / Android attack surface.** N/A — no `Activity`/`Service`/`Receiver`/deep
  link/`WebView`, no exported component, no `android.*` import (`data/`-portable). Pure in-process
  transform over the Noise channel.
- **Cryptographic primitives.** N/A — no crypto executed; bytes arrive already decrypted by the Noise
  transport (#298/#309) upstream. No RNG used for security (the client-minted request id is a monotonic
  counter, not a secret; no UUID minting here — the server owns the conversation id).
- **Resource / DoS.** `pendingRequests` grows by one per in-flight promote and is removed in `finally`
  (success, error, cancellation) — bounded by caller concurrency, no unbounded growth. The
  `conversation_updated` payload is a **single** bare object (five small fields), no array — minimal
  allocation; string lengths bounded by the already-capped transport frame. The `projection.value` read is
  O(n) `firstOrNull` over the in-memory list (the user's own conversation count), not an attack amplifier.
  `projection` grows by zero on promote (upsert replaces in place).
- **Error messages / logs / telemetry.** No logging introduced. A `RelayErrorException` carries only the
  server-authored `error.message` (control text), never user input. Decode exceptions name the
  missing/mismatched **field**, never values.
- **Concurrency.** The new shared-state interactions are a lock-free `projection.value` read (cwd
  resolution) and the atomic `update {}` CAS upsert (reused #347 helper); the collector's full-replace is
  convergent and authoritative. The read-then-upsert pair is intentionally non-atomic (the resolved cwd is
  request data, not a guarded invariant — see § State). No lock, no new coroutine, no TOCTOU of
  consequence.

No FAIL findings; no spec revision required. Proceeding to commit.
