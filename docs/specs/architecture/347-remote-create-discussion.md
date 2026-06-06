# Spec — `RemoteConversationRepository.createDiscussion` over v2 (`create_conversation`) (#347)

> Fills the `createDiscussion` stub on the already-merged `RemoteConversationRepository` with a live
> Mobile Protocol v2 implementation: encode a `create_conversation` request, send-and-await its
> correlated `conversation_created` reply through the class's single inbound collector, decode that
> reply into the returned `Conversation`, and **confirmed-insert** it into the read-path list
> projection only after the reply lands. Composes two already-merged building blocks — #346's
> `sendAndAwaitReply` correlation primitive and #318's `ConversationResponseDto.toConversation()`
> decode mapper — with one new `create_conversation` request encoder. Mirrors the merged #346
> `sendMessage` slice; the sibling #348 (`promote`) lands after this on the same class.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:60-189` — the
  class, the `projection` `StateFlow` (64-70), the `requestId` `AtomicLong` (95), the
  `pendingRequests` registry (97-106), the single `init` inbound collector (108-114), and
  `onInbound`'s `when(envelope.type)` (116-189). **This is the only production class you modify.**
  Extend the existing `TYPE_ACK ->` success arm (171-177) to also match `TYPE_CONVERSATION_CREATED`;
  fill the `createDiscussion` stub at **370-371**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:234-251` —
  `sendAndAwaitReply(request): JsonElement`, the **reused** #346 register→send→await primitive.
  Returns the reply payload (empty `{}` for an `ack`, the **bare conversation object** for a
  `conversation_created`); throws `IllegalStateException` on a not-`Open` pump and rethrows the
  collector's exceptional completion on a server `error`. `createDiscussion` calls it verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:391-424` —
  `sendMessage` (#346): the **template to mirror** for the request-build → `sendAndAwaitReply` →
  confirmed-insert → return shape. The one structural difference is called out in § Design below
  (`send_message` reconstructs its return from the input; `createDiscussion` decodes it from the
  reply).
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt:41-73` — the **reused**
  #318 decode boundary. `ConversationResponseDto` (the `conversation_created` payload: `id`,
  `name?`, `is_promoted`, `cwd`, `last_used_at`) + `fun ConversationResponseDto.toConversation()`.
  `createDiscussion` decodes the reply through this. **Do not re-implement it** (the ticket's "reuse
  it" note); the four domain placeholders (`currentSessionId=""`, `sessionHistory=emptyList()`,
  `isSleeping=false`, `archived=false`) are this mapper's, not yours.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt:118-159` — the **encode-only
  request-DTO pattern** to copy: `BackfillSincePayloadDto` and `SendMessagePayloadDto` (`@Serializable`,
  `@SerialName` snake_case, Go-struct field order, KDoc citing the server SSOT). The new
  `CreateConversationPayloadDto` is the same shape — but lives in its **own new file** (see § Design).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson`
  (`encodeDefaults=true`, `explicitNulls=false`, `ignoreUnknownKeys=true`). **Load-bearing for this
  slice's request encoding:** `encodeDefaults=true` emits `is_promoted:false` (a non-null default);
  `explicitNulls=false` **omits** a null `cwd` key. The server tolerates the absent key (its struct
  is `*string` without `omitempty` — see § Design "Wire encoding"). Always (de)serialize through
  `MobileJson`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-21` — the 9-field domain target
  **and** `DEFAULT_SCRATCH_CWD` (`"~/.pyrycode/scratch"`, line 21). Tests reference the constant, not
  the literal, when asserting the server-assigned scratch cwd.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:231-249,
  580-740, 742-902` — the test idiom: `runTest` + `runCurrent()`, the `startSend` launch helper
  (749-757, the pattern for a new `startCreate`), `ackEnvelope`/`errorEnvelope`/`conversationsEnvelope`
  (759-867, the pattern for a new `conversationCreatedEnvelope`), `FakeSessionPump`
  (`sent`/`sendResult`/`push`, 870-888), `collectConversations` (779-786), and `MIXED_FIXTURE`
  (894-900). **Remove** `assertUnsupported { repo.createDiscussion() }` from
  `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (line 239) and add `createDiscussion (#347)`
  to its 236-237 comment — `createDiscussion` is no longer a stub (exactly as #346 did for
  `sendMessage`).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:32` — the
  `createDiscussion(workspace: String? = null): Conversation` contract. Signature is **unchanged** —
  no consumer call-site cascade.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:116-141` — the
  observable contract to mirror: returns an unpromoted (`isPromoted=false`), unnamed (`name=null`)
  `Conversation`. **One divergence:** the fake picks `cwd = workspace ?: ""` locally; the remote
  takes `cwd` **from the server's reply** (AC #1 — the server assigns the scratch cwd).
- `docs/lessons.md` — not present in this repo; nothing to read.

## Context

Phase 4 backend integration. `RemoteConversationRepository.createDiscussion` currently throws
`UnsupportedOperationException` (`#314` stub). This slice makes it create a conversation over the
landed Noise session pump and surface the new conversation in the list.

This is the **second** of the three #314 mutation slices, after #346 (`sendMessage`) and before #348
(`promote`). All three edit this one class plus its test, so they **serialize** — #347 builds on
#346's merged state, #348 will build on #347's. The two building blocks this slice needs are already
on `main`:

- **Correlation** — #346's `sendAndAwaitReply(Envelope): JsonElement` + the `pendingRequests`
  registry (keyed on `Envelope.id`, completed by the single `init` collector on the matching
  `inReplyTo`).
- **Response decode** — #318's `ConversationResponseDto.toConversation()` decodes the bare
  conversation object a `conversation_created` carries at `Envelope.payload`.

This slice composes those two with one **new** `create_conversation` request encoder. Per
[[phase4-request-encoders-live-in-mutation-tickets]], there is **no shared request-mapping slice** —
the #316/#317/#318 mapping slices are decode-only; the request half is built here.

**Behavioral contrast with #346 (the central design point).** `send_message`'s reply is an empty
`ack`, so #346 *reconstructs* its return `Message` from the input. `create_conversation`'s reply is a
**typed `conversation_created` payload** carrying the server-assigned `id`/`cwd`, so this slice
*decodes* its return `Conversation` from the reply. The reply still rides the same correlation
primitive: `sendAndAwaitReply` hands the raw reply `JsonElement` back to the caller, which then
decodes it. The only `onInbound` change is routing `conversation_created` into the completion path —
today it falls through to `else -> Unit`, flagged in-code (line 184-186) as extended by the #314
slices.

## Design source

N/A — pure `data/`-layer wire/transport work; no UI, no Figma. The "design source" is the byte
contract: server SSOT `internal/protocol/conversations_write.go` (`CreateConversationPayload` /
`ConversationCreatedPayload`, #274) and `protocol-mobile.md §§ create_conversation /
conversation_created`.

## Design

### New request DTO — `CreateConversationPayloadDto`

New file `app/src/main/java/de/pyryco/mobile/data/network/CreateConversationPayloadDto.kt` (one
public `@Serializable data class`, so the filename matches the type per
[[ktlint-filename-rule-single-class]]). It does **not** go in `MessagePayload.kt`: that file is scoped
to message/thread payloads, and a conversation-lifecycle request belongs in its own file — symmetric
with the decode-side `ConversationResponseDto.kt`. `data/`-portable, no `android.*`.

Wire SSOT — server `CreateConversationPayload` (#274), all three fields `*T` (optional pointer)
without `omitempty`:

```go
type CreateConversationPayload struct {
    IsPromoted *bool   `json:"is_promoted"`
    Name       *string `json:"name"`
    Cwd        *string `json:"cwd"`
}
```

The Kotlin encoder models **only the two fields the mobile create flow sends**:

```kotlin
@Serializable
data class CreateConversationPayloadDto(
    @SerialName("is_promoted") val isPromoted: Boolean = false,
    val cwd: String? = null,
)
```

- **`name` is intentionally not modeled.** The mobile app creates **discussions** — unpromoted and
  *server-auto-named* (CLAUDE.md "Discussions … auto-named"). It never sends a `name` on
  `create_conversation`; user-naming is the separate `promote_conversation` flow (#348). An absent
  `name` key decodes to a nil pointer server-side → the server assigns the name (and the
  `conversation_created` reply comes back with `name: null` for an unnamed discussion). The DTO's
  KDoc must state this so a future reader does not "add `name` for completeness" — this is an
  encode-only DTO, model only what is sent (the #346 / `BackfillSincePayloadDto` discipline), not the
  full decode surface (which is #318's job).
- **`isPromoted` is sent explicitly as `false`.** `createDiscussion` always creates an unpromoted
  conversation; sending `is_promoted:false` removes any ambiguity about the server's
  default-when-absent. `encodeDefaults=true` emits it even though it equals the Kotlin default.
- **`cwd` carries the `workspace` argument verbatim** (or `null`). A non-null value pins the
  conversation's cwd; `null` requests a server-assigned scratch cwd (AC #1).

**Wire encoding (load-bearing, do not "fix").** Under `MobileJson` (`explicitNulls=false`), a null
`cwd` is **omitted** from the JSON, not emitted as `"cwd":null`:

| `createDiscussion(workspace)` | encoded `create_conversation` payload |
|---|---|
| `createDiscussion(null)` | `{"is_promoted":false}` |
| `createDiscussion("/work/proj")` | `{"is_promoted":false,"cwd":"/work/proj"}` |

The server's struct is `*string` **without** `omitempty`, and Go's `encoding/json` decodes an
**absent** key identically to an explicit `null` (both leave the pointer nil). #274 explicitly
sanctions this — "the binary fills server-side defaults when null is on the wire (or when a field is
absent under future-relaxed encoders)"; the phone *is* such a relaxed encoder. So the omitted `cwd`
key is wire-compatible and means "server assigns the scratch cwd." Do **not** try to force an explicit
`"cwd":null` (it would require a per-call `Json` config and buys nothing).

### Routing `conversation_created` into the completion path

`conversation_created` is a **correlated success reply** whose payload is the bare conversation
object — the same completion shape as an `ack`, only with a non-empty payload the caller decodes.
The existing `TYPE_ACK` arm (171-177) already anticipates this in its comment ("handed verbatim to
the waiter, which … (later, #347/#348) decodes it for a typed reply"). So extend that arm to match
both types — one shared branch, identical body:

```kotlin
TYPE_ACK, TYPE_CONVERSATION_CREATED ->
    envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
```

Update the arm's comment to note `conversation_created` is now wired (drop the "(later, #347/#348)"
hedge for the create case; #348 adds `TYPE_CONVERSATION_UPDATED` to the same arm). A
`conversation_created` whose `inReplyTo` matches no pending entry (or is null) stays a no-op —
`complete` is idempotent. The reply decode happens in the **caller's** coroutine (see below), not in
the collector, so a malformed `conversation_created` never throws inside `onInbound`.

New companion constants alongside the existing ones (447-494):

```kotlin
const val TYPE_CREATE_CONVERSATION = "create_conversation"   // request
const val TYPE_CONVERSATION_CREATED = "conversation_created" // correlated success reply
```

### `createDiscussion` itself

```kotlin
override suspend fun createDiscussion(workspace: String?): Conversation
```

Flow (≤ ~10 lines, mirroring `sendMessage`'s build → await → confirmed-insert → return):

1. Build the request `Envelope(id = requestId.incrementAndGet(), type = TYPE_CREATE_CONVERSATION,
   ts = Clock.System.now().toString(), payload =
   MobileJson.encodeToJsonElement(CreateConversationPayloadDto(cwd = workspace)))`.
2. `val reply = sendAndAwaitReply(request)` — throws on server `error` / not-`Open`; returns the
   `conversation_created` payload (the bare conversation object) on success.
3. Decode the reply into the returned domain object:
   `val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()`.
4. **Only after a successful decode**, confirmed-insert `conversation` into `projection` (AC #2),
   then `return conversation`.

The decode (step 3) runs in the caller's coroutine **after** `sendAndAwaitReply` returns (the
`pendingRequests` entry is already removed by its `finally`). A malformed `conversation_created`
(missing required field, bad `last_used_at`) throws the #318 boundary's typed exception
(`SerializationException`/`IllegalArgumentException`) **before** step 4, so the projection is never
mutated by a garbage reply — consistent with #318 ("surfaces the typed exception to the caller; no
partial/`null`-punned object"). No catch/wrap here; the ViewModel surfaces it. This is a thrown
exception, not a silent failure (AC #3 spirit).

### Confirmed-insert into the list projection (AC #2)

`createDiscussion` folds the returned `Conversation` into `projection` (the read-path list
`StateFlow`) **after** the reply, so `observeConversations` re-emits to include it without waiting on
a server-initiated `conversations` push. This mirrors #346's confirmed-insert ("mutate the read
projection only after the success reply") and makes AC #2 deterministic and self-contained.

Contract of the fold (a `projection.update { … }`, atomic CAS; extract a small private helper if it
reads cleaner — parallels `appendMessages`/`recordLastMessage`):

- Null-safe: `current.orEmpty()` (the projection is `null` until the first `conversations` snapshot;
  folding into `null` yields a single-element list, which `observeConversations` then emits).
- **Upsert by `id`**, not blind append: replace an existing entry with the same `id`, else append —
  the same dedup discipline as `appendMessages`. Robust if a `conversations` snapshot already
  delivered the conversation (race) before the fold runs.

**Why the fold is benign w.r.t. the collector.** The `TYPE_CONVERSATIONS` collector arm keeps its
blind full-replace (`projection.value = decoded.toConversations()`) — leave it unchanged (Simplicity
First). The fold uses `update { }` (CAS-retries against a concurrent set), so it never loses a
concurrent snapshot; the only race is a *stale* in-flight snapshot landing after the fold and
transiently dropping the new conversation — harmless because the server is authoritative and its
post-create snapshots include it. Moreover the folded `Conversation` is **field-equal** to the same
conversation as later mapped from a `conversations` snapshot (#316 and #318 use the identical
placeholder rule: `currentSessionId=""`, `sessionHistory=emptyList()`, `isSleeping=false`,
`archived=false`), so `StateFlow` conflation suppresses a redundant re-emit when the authoritative
snapshot arrives.

**Update the `projection` KDoc (64-70)** to name the second writer (`createDiscussion`'s
confirmed-insert) and the CAS-fold safety, exactly as #346 updated the `lastMessages` /
`messagesByConversation` KDocs (72-93). Leaving the "A single source of state" / single-writer claim
stale would be a correctness-doc lie.

## State + concurrency model

- **`projection` gains a second writer.** Documented single-writer (the `init` collector); this slice
  relaxes that to **collector + `createDiscussion`'s confirmed-insert**. Data-safety holds: the fold
  is an atomic `update { }` CAS upsert; the collector's full-replace is authoritative and convergent.
  No new `StateFlow`, no parallel mutable state.
- **No new collector, no second subscription.** `conversation_created` correlation rides the existing
  single `init` collector — the absolute constraint from the read-path slices
  ([[phase4-v2-wire-no-streaming]]). `createDiscussion` never collects `pump.inbound`.
- **Suspend/await.** `createDiscussion` suspends on `CompletableDeferred.await()` (inside
  `sendAndAwaitReply`) in the *caller's* coroutine (a ViewModel `viewModelScope`), not the connection
  scope. Caller cancellation → `CancellationException` → the primitive's `finally` removes the
  registry entry (no leak). The reply is delivered by the connection-scoped collector;
  `complete`/`completeExceptionally` are thread-safe and idempotent across the two coroutines.
- **Dispatcher.** None chosen here — in-memory state flow + a non-blocking `pump.send`. No IO/Main
  boundary in this slice (transport dispatching lives below the pump).

## Error handling

| Failure | Surfaced as | `projection` mutated? |
|---|---|---|
| Server `error` reply (`protocol.malformed`, `server.binary_offline`, unknown code) | `RelayErrorException(code, retryable, message)` via #346 `mapError` (AC #3) | No |
| Not connected (`pump.send` returns `false`) | `IllegalStateException` (#346 `sendAndAwaitReply` `check`) | No |
| Malformed `conversation_created` reply (missing field / bad `last_used_at`) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated | No (decode precedes the fold) |
| Malformed server `error` payload | #346 fallback `RelayErrorException` — waiter unblocked, collector survives | No |
| Caller coroutine cancelled mid-await | `CancellationException` (standard) | No |

The list is **never** mutated on any failure path — the confirmed-insert runs only after both
`sendAndAwaitReply` *and* the reply decode succeed. No silent failure: every non-success path throws
from the suspend call (AC #3). `conversation.not_found` (the #346 `IllegalArgumentException` branch in
`mapError`) is not a meaningful `create_conversation` error — `createDiscussion` references no existing
conversation — so it is not exercised here; `mapError` is reused as-is and needs no change.

## Testing strategy

Unit only (`./gradlew test`), extending `RemoteConversationRepositoryTest` with the existing
`runTest` + `runCurrent()` + `FakeSessionPump` idiom (no device, no real crypto). Two new helpers,
mirroring the #346 ones:

- `startCreate(repo, workspace): () -> Result<Conversation>` — launches `createDiscussion` on
  `backgroundScope` (it suspends awaiting the reply) and returns a getter for the eventual `Result`
  (copy of `startSend`, 749-757).
- `conversationCreatedEnvelope(inReplyTo, id, isPromoted=false, name=null, cwd, lastUsedAt, envId=99L)`
  — `type="conversation_created"`, payload a bare conversation object built via
  `MobileJson.parseToJsonElement("""{"id":…,"is_promoted":…,"cwd":…,"name":…,"last_used_at":…}""")`,
  `inReplyTo` set (mirror `ackEnvelope` / `conversationsEnvelope`).

Also update `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (231-249): remove the
`createDiscussion()` assertion (line 239) and add `createDiscussion (#347)` to the 236-237 comment.

New scenarios (bullet form — write in the project idiom; do not paste full bodies):

- **AC #1/#4 — request shape, scratch (null workspace).** `startCreate(repo, null)`; read the single
  sent envelope: assert `type == "create_conversation"` and `payload == {"is_promoted":false}` (the
  `cwd` key is **absent** — `explicitNulls=false`). Push a correlated `conversation_created` to
  unblock the launched coroutine.
- **AC #1/#4 — request shape, explicit workspace.** `startCreate(repo, "/work/proj")`; assert the
  sent payload `== {"is_promoted":false,"cwd":"/work/proj"}`.
- **AC #1 — created reply → unpromoted `Conversation` with the server's cwd.** `startCreate(repo, null)`;
  read `pump.sent.last().id`; push `conversationCreatedEnvelope(inReplyTo = sentId, id = "c-new",
  isPromoted = false, name = null, cwd = DEFAULT_SCRATCH_CWD, lastUsedAt = "2026-05-08T10:34:01Z")`;
  `runCurrent()`; assert the `Result` is success and the returned `Conversation` has `id == "c-new"`,
  `isPromoted == false`, `name == null`, `cwd == DEFAULT_SCRATCH_CWD` (reference the **constant**).
  Crucially, assert `cwd` is the **reply's** value, *not* the (null) workspace arg — proves the
  client uses the server-assigned scratch cwd.
- **AC #2 — `observeConversations` re-emits including the new conversation.** `collectConversations(repo,
  ConversationFilter.All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))` (baseline `[chan, disc]`);
  `startCreate(repo, null)`; push a `conversation_created` with a fresh `id`; `runCurrent()`; assert the
  latest emission now contains 3 conversations including the new one. Add a `ConversationFilter.Discussions`
  variant asserting the new (unpromoted) conversation appears in the Discussions tier.
- **AC #3 — server `error` → throws, list unchanged.** `collectConversations(All)`;
  `pump.push(conversationsEnvelope(MIXED_FIXTURE))`; `startCreate(repo, null)`; push
  `errorEnvelope(sentId, code = "server.binary_offline", retryable = true)`; `runCurrent()`; assert the
  `Result` is a failure with `RelayErrorException` exposing that `code`; assert the conversations list is
  still `[chan, disc]` (no new/partial entry — AC #3 "uncorrupted").
- **AC #3/#4 — not connected → `IllegalStateException`, list unchanged.** `FakeSessionPump.sendResult =
  false`; `collectConversations(All)`; `pump.push(conversationsEnvelope(MIXED_FIXTURE))`;
  `startCreate(repo, null)`; `runCurrent()`; assert failure with `IllegalStateException`; assert no
  `create_conversation` reply was processed and the list is unchanged.
- **Malformed `conversation_created` reply → throws, list unchanged.** Push a `conversation_created`
  whose payload **omits a required field** (e.g. drop `cwd`); assert `createDiscussion` fails with the
  #318 decode exception (a `SerializationException` — note: `SerializationException` is an
  `IllegalArgumentException` subtype); assert the list is unmutated (the fold never ran). Proves a
  garbage *success* reply cannot inject a partial conversation.
- **(Correlation hygiene — light.)** Broadly covered by #346's `uncorrelatedAck_isNoOp` test; optionally
  add a `conversation_created` with an `inReplyTo` matching no pending request and assert it is a no-op
  and a subsequent real `createDiscussion` round-trip still completes.

`ComposeTestRule` is not involved (pure data layer).

## Open questions

- **Connection-drop-mid-create leak.** Same single-entry `pendingRequests` leak #346 documented: if
  the connection scope is cancelled while a caller awaits, the deferred never completes until the
  *caller* is cancelled (ViewModel scope cancels on screen exit → `CancellationException`). No timeout
  added (no observed hang; a timeout value is a product call). A future slice or the connection
  coordinator (#302) may fail all `pendingRequests` on disconnect. Flagged, not fixed (Evidence-Based
  Fix Selection).
- **Shared projection-upsert helper, deferred.** #348 (`promote`) will also fold a returned/updated
  `Conversation` into `projection`. If this slice extracts a private `upsertConversation` helper,
  #348 reuses it; if it inlines, #348 extracts then (the second call site = the evidence). Either is
  fine — do **not** build a generalized helper for #348 ahead of its landing (Simplicity First); write
  the fold `createDiscussion` needs.

## Security review (`security-sensitive`)

> The canonical `security-review.md` referenced by the architect prompt is not symlinked into this
> worktree (same as #346/#318); the adversarial pass below walks the standard trust-boundary / input /
> secrets / IPC / crypto / DoS / logging / concurrency categories and renders a verdict.
> **Verdict: PASS.**

- **Trust boundaries.** The only untrusted input is the inbound `conversation_created` (or `error`)
  payload, decoded through the strict, all-or-nothing #318 `ConversationResponseDto` boundary (resp.
  #346 `mapError`). A malformed success reply throws the typed decode exception **before** any
  `Conversation` is built and **before** the projection fold — so a hostile/buggy server cannot inject
  a partial or `null`-punned conversation into the list (this is exactly the #318-reviewed property,
  reused here). No `eval`, shell, SQL, or path/URL construction from payload data → no injection
  surface. The channel is the already-authenticated Noise session; the relay/server is the
  user-paired trusted endpoint, not an arbitrary attacker.
- **Confirmed-insert is the security property.** A conversation is folded into the read projection
  **only** on a server `conversation_created` success reply, never on a failure path and never
  speculatively — so the list never shows a conversation the server did not create. Satisfied by
  construction (the fold is step 4, after both await and decode succeed); no rollback path to get
  wrong. Mirrors #346's confirmed-insert property.
- **Correlation integrity.** Request ids are client-minted and monotonic (`AtomicLong`) within a
  session; the server only echoes them as `inReplyTo`. A duplicate/forged `inReplyTo` resolves the
  matching pending request at most once (`CompletableDeferred` completion is idempotent). Cross-request
  confusion is bounded to the session's own id space against the paired server — no code change
  warranted under the paired-trust model (no observed attack).
- **Secret / sensitive-data exposure.** The `create_conversation` request and `conversation_created`
  reply carry no secret — `is_promoted` (bool), `cwd` (a path string), `name`, `id`, and one
  timestamp. The design logs **nothing**: no `Log.*` of the request `Envelope`, the reply
  `JsonElement`, the decoded `Conversation`, or `cwd`. `cwd` and `name` are non-secret but
  privacy-adjacent (filesystem path, user-chosen) — keep them off the log, matching the existing
  collector posture. No tokens or keys are touched. **Developer constraint:** do not add logging of
  the request/reply/`cwd`/`Conversation`.
- **`cwd` as a path string.** `cwd` is an attacker-influenceable filesystem-**path string** carried
  verbatim into the request and echoed back in the reply, but this slice never opens, resolves,
  canonicalises, or traverses it — `cwd` is opaque display data here (same deferral #318's review
  recorded). Any future code that turns a server-supplied `cwd` into an actual `File` must do its own
  canonicalisation + boundary check; out of scope and named so the obligation is on record.
- **Inter-process / Android attack surface.** N/A — no `Activity`/`Service`/`Receiver`/deep
  link/`WebView`, no exported component, no `android.*` import (`data/`-portable). Pure in-process
  transform over the Noise channel.
- **Cryptographic primitives.** N/A — no crypto executed; bytes arrive already decrypted by the Noise
  transport (#298/#309) upstream. No RNG used for security (the client-minted request id is a
  monotonic counter, not a secret); the `message_id`-style UUID minting of #346 is **not** present
  here (the server assigns the conversation id).
- **Resource / DoS.** `pendingRequests` grows by one per in-flight create and is removed in `finally`
  (success, error, cancellation) — bounded by caller concurrency, no unbounded growth. The
  `conversation_created` payload is a **single** bare object (five small fields), no array — less
  allocation amplification than the read-path list; string lengths bounded by the already-capped
  transport frame. `projection` grows by one entry per confirmed create (upsert-deduped by id), the
  normal data-model growth, not an attack amplifier.
- **Error messages / logs / telemetry.** No logging introduced. A `RelayErrorException` carries only
  the server-authored `error.message` (control text), never user input. Decode exceptions name the
  missing/mismatched **field**, never values.
- **Concurrency.** The one new shared-state interaction is the second writer on `projection`, via an
  atomic `update { }` CAS upsert; the collector's full-replace is convergent and authoritative. No
  TOCTOU, no lock, no new coroutine.

No FAIL findings; no spec revision required. Proceeding to commit.
