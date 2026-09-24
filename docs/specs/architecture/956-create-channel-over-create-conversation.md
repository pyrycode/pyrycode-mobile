# #956 — Create a named, promoted channel in a workspace over `create_conversation`

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/CreateConversationPayloadDto.kt` → `CreateConversationPayloadDto` — the encode-only request DTO; its KDoc forbids modelling `name` until a consumer exists. This ticket is that consumer.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt` → `ConversationCommands.createDiscussion`, `ConversationCommands.promote` — the encode → `RelayRequests.sendAndAwaitReply` → `ConversationResponseDto` decode → `ConversationListProjection.upsertConversation` shape the new command mirrors.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createDiscussion`, `setSystemPrompt` — the interface, and the default-throws cascade-avoidance pattern (`setSystemPrompt`, `requestSystemPrompt`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the one-line `createDiscussion` hand-off to `conversationCommands`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `createDiscussion` via `live` (throws `IllegalStateException` with no connection).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `createDiscussion`, `promote`, `bumpWorkspace` — the in-memory create path.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `CachingConversationRepository` delegates `by delegate` and does not override `createDiscussion`, so the new method passes through untouched.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → the `createDiscussion` test block, `startCreate`, `conversationCreatedEnvelope`, `collectConversations`, `MIXED_FIXTURE` — the private fixtures the new tests reuse, so they go in this file beside the `createDiscussion` block.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` → `oneShots_whileAbsent_throwIllegalState`, `oneShots_whenLive_delegateWithExactArgsAndReturn`, `RecordingConversationRepository`.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` → the `createDiscussion_*` tests.
- `../pyrycode/docs/protocol-mobile.md` § `create_conversation` / `conversation_created` rows, and daemon `internal/protocol/conversations_write.go` → `CreateConversationPayload` (`is_promoted`, `name`, `cwd`, all nullable pointers, in that order).

In-flight overlap: #899 and #945 add unrelated methods to `ConversationRepository.kt`, `RemoteConversationRepository.kt` and `StableConversationRepository.kt`. Not a dependency; edits here are additive and local, so a later merge may touch those files.

## Design source

N/A — data-layer ticket; no UI consumes the operation yet (the Create channel modal is a follow-up).

## Context

Mobile can only create an unpromoted, unnamed discussion and promote it afterwards. Desktop's Create channel sends one `create_conversation` with `is_promoted: true`, the trimmed name and the workspace's exact `cwd`. This adds the same repository operation so the follow-up modal creates a channel as one conversation from the start.

## Design

### Wire DTO

`CreateConversationPayloadDto` gains `val name: String? = null`, declared between `isPromoted` and `cwd` (Go struct order). Under `MobileJson` (`explicitNulls = false`) a null `name` is omitted, so `createDiscussion`'s encoded payload stays byte-identical: `{"is_promoted":false}` / `{"is_promoted":false,"cwd":…}`. The "Do NOT add `name`" paragraph is rewritten: `name` is modelled for the channel create, and is null (omitted) on the discussion create.

### Commands

```kotlin
// ConversationCommands
suspend fun createChannel(name: String, workspace: String): Conversation
```

Encodes `CreateConversationPayloadDto(isPromoted = true, name = name, cwd = workspace)` — both verbatim, no trim — and runs the same send → await → decode → confirmed-upsert body as `createDiscussion`. The shared body moves into one private `create(payload: CreateConversationPayloadDto): Conversation`, which both public commands call; `createDiscussion`'s behaviour and wire are unchanged. The returned conversation is the decoded reply, never the request.

### Repository contract

```kotlin
// ConversationRepository
suspend fun createChannel(name: String, workspace: String): Conversation =
    error("createChannel is not implemented for this ConversationRepository")
```

KDoc: sends name and workspace verbatim (trimming is the caller's), returns the daemon's confirmed conversation which appears as a promoted row in `observeConversations`; throws on server error, disconnected session or malformed reply and inserts nothing. Default throws, like `setSystemPrompt`, so inline test doubles need no override.

Implementations:
- `RemoteConversationRepository.createChannel` → one-line hand-off to `conversationCommands.createChannel`.
- `StableConversationRepository.createChannel` → `live.createChannel(name, workspace)` (throws `IllegalStateException` with no live connection, like every one-shot).
- `FakeConversationRepository.createChannel` → a new promoted conversation with `name`, `cwd = workspace`, a fresh session, `bumpWorkspace(cwd)`. The shared record-building moves into one private helper both `createDiscussion` and `createChannel` call.
- `CachingConversationRepository` — nothing; `by delegate` forwards it.

## State + concurrency model

No new state or jobs. The call suspends on `RelayRequests.sendAndAwaitReply` in the caller's scope exactly as `createDiscussion` does; cancellation propagates the same way. The only state change is the confirmed upsert into `ConversationListProjection` after decode.

## Error handling

Identical to `createDiscussion`: `RelayErrorException` for a server `error`, `IllegalStateException` when the session is not connected, the #318 decode exception (`SerializationException` ⊂ `IllegalArgumentException`) for a malformed reply. All throw before the upsert, so nothing is inserted.

Logging: none. `ConversationCommands` is documented as "Nothing here logs" and `createDiscussion` adds no log; the name and path are operator-chosen and the reply daemon-authored, so the new path stays off the log entirely rather than introduce the class's first logger.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`), fakes as today.

`RemoteConversationRepositoryTest` (new block beside `createDiscussion`, with a `startCreateChannel` helper):
- sends exactly one `create_conversation` whose payload is `{"is_promoted":true,"name":"  Weekly planning ","cwd":"/work/wp"}` — whitespace kept, proving verbatim.
- on a `conversation_created` reply whose name differs from the request, returns the reply's values (id, name, `isPromoted = true`, cwd).
- after success the conversation appears in `ConversationFilter.Channels` and not in `Discussions`.
- server `error` → `RelayErrorException`, list unchanged.
- `pump.sendResult = false` → `IllegalStateException`, list unchanged.
- malformed reply → `IllegalArgumentException`, list unchanged.
- The existing `createDiscussion_nullWorkspace_…` / `createDiscussion_explicitWorkspace_…` tests pin `createDiscussion`'s unchanged payload (exact `JsonElement` equality, so an emitted `name` key would fail them).

`StableConversationRepositoryTest`:
- `oneShots_whileAbsent_throwIllegalState` gains `createChannel`.
- `oneShots_whenLive_delegateWithExactArgsAndReturn` gains `createChannel` args + returned instance, via a recording override on `RecordingConversationRepository`.

`FakeConversationRepositoryTest`:
- `createChannel` returns a promoted conversation with the given name and cwd verbatim, which appears in `Channels` and not `Discussions`.

Interface default: covered by the build — `ThrowingConversationRepository` and other inline doubles compile without an override.

No rung-3/rung-4 scenario: no operator-facing flow ships here; the modal follow-up owns it.

## Open questions

None.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/remote-conversation-repository-send-create-promote-rename.md`: add the promoted-create operation beside `createDiscussion`.
- `docs/knowledge/features/conversation-repository.md`: add `createChannel` to the contract.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the one inbound boundary is the existing `ConversationResponseDto` decode inside the shared `ConversationCommands` create body; a malformed reply throws before `ConversationListProjection.upsertConversation`, so nothing partial is folded. The daemon-authored `name`/`cwd`/`workspace_label` in the reply enter the same projection, by the same decode, that `promote`, `rename` and `list_conversations` already feed; rendering them as text is the existing consumers' posture and the follow-up modal's. Outbound, the operator-chosen name and path are sent verbatim inside the sealed envelope; the daemon is the validation authority (as for `rename`, where it rejects empty titles as `protocol.malformed`), surfaced as an ordinary `RelayErrorException`.
- [Trust boundaries] OUT OF SCOPE — a length bound or trim on the name before sending belongs to the Create channel modal follow-up (split from #666), which owns the input field; this operation deliberately sends verbatim per the ticket.
- [Tokens] No findings — no token, key or credential is created, stored or read; the request rides the existing authenticated Noise session.
- [File / storage] No findings — `workspace` is a daemon-host path carried as an opaque string; the phone never opens, resolves or concatenates it into a local path. No new persistence.
- [Android attack surface] No findings — no intent, deep link, pending intent, push path, provider or WebView is added.
- [Crypto] No findings — no primitive touched; encryption and framing stay with `NoiseIkSession`.
- [Network & I/O] No findings — reuses `RelayRequests.sendAndAwaitReply`, the same correlated-waiter, teardown-fails-waiter path as `createDiscussion`; no new socket, timeout or frame-size setting.
- [Logs] No findings — the new path logs nothing: not the name, the path, the payload, nor the server-supplied `RelayErrorException` message. Consistent with `ConversationCommands`' "Nothing here logs".
- [Concurrency] No findings — no new scope or job; the call suspends in the caller's scope and cancels with it. The projection mutation is the existing `upsertConversation`, applied once after decode.
- [Threat model] No findings — a malicious relay cannot forge a reply inside the AEAD session, only drop or delay it (the call then waits or fails on teardown, inserting nothing). A hostile daemon frame is decoded defensively and folded as data only.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
