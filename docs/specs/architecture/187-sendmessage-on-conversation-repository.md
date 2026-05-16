# Spec — `sendMessage` on `ConversationRepository` (#187)

Adds a single mutator that appends a user-authored `Message` to a conversation's in-memory thread. Interface contract + Fake impl + tests. No UI wiring (lands in a follow-up).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-56` — interface surface; the new method slots in alongside the other mutators (after `changeWorkspace`).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:109-218` — every existing mutator (`promote`, `archive`, `unarchive`, `rename`, `mintNewSession`) is the template: each uses `state.update`, throws via `unknown(id)` on miss, and (where relevant) bumps `lastUsedAt` to the same `Clock.System.now()` value used for the operation. Match this shape.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:220-224` — `ConversationRecord` shape (`conversation`, `sessions`, `messages: List<Message>`). The new method appends to `messages`.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5-14` — `Message` fields and `Role` enum.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:182-456` — established test style for mutators (`createDiscussion`, `promote`, `archive`, `rename`, etc.): `runBlocking { ... }`, `repo.observeX(...).first()` assertions, `try { … } catch (_: IllegalArgumentException) { /* expected */ }` for the unknown-id case. The new tests follow the same idioms.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt:348-419` — canonical example of an anonymous `object : ConversationRepository { ... TODO("not used") ... }` stub. Every test-side impl across 4 files follows this pattern; the new method adds one more `TODO("not used")` line per object (see § Cascade in test stubs).

## Context

`ConversationRepository` currently has observation streams plus six lifecycle mutators (`createDiscussion`, `promote`, `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace`) but no way to append a message. The thread input bar (forthcoming) and Phase 4 streaming both need this surface. Phase 0 is in-memory; the contract holds across the Phase 4 Ktor-backed remote impl (will route through the WS bridge under the hood).

## Design

### Interface addition (one method)

In `ConversationRepository`, after `changeWorkspace`:

```kotlin
/**
 * Appends a user-authored [Message] to the conversation's current session.
 * Returns the persisted message. Throws [IllegalArgumentException] if
 * [conversationId] does not exist. Caller is responsible for non-blank
 * validation of [text]; this method does not trim or reject blank input.
 */
suspend fun sendMessage(
    conversationId: String,
    text: String,
): Message
```

That is the entire interface change.

### `FakeConversationRepository.sendMessage` implementation

Follow the `rename`-shaped template (mutator that returns the mutated entity and bumps `lastUsedAt`). Behavior:

1. Capture `val now = Clock.System.now()` once at the top.
2. `state.update { records -> … }`:
   - `val record = records[conversationId] ?: throw unknown(conversationId)`
   - Mint `val messageId = UUID.randomUUID().toString()` — matches the runtime-minted ID pattern already used by `createDiscussion` and `mintNewSession`. (Seed messages use `"$sessionId-msg-$index"`; that is a convenience for static seeds, not a runtime contract.)
   - Construct `Message(id = messageId, sessionId = record.conversation.currentSessionId, role = Role.User, content = text, timestamp = now, isStreaming = false)`.
   - Produce the updated record: `record.copy(conversation = record.conversation.copy(lastUsedAt = now), messages = record.messages + newMessage)`.
   - Return `records + (conversationId to updatedRecord)`.
3. Return the constructed `Message` (lift it out of the `state.update` block via `lateinit var appended: Message`, same idiom as `rename`/`mintNewSession` use to surface the freshly minted entity).

Code size: ~20 lines. No new helpers, no new types, no changes to `ConversationRecord` or `unknown(id)`.

### Cascade in test stubs

Four test files declare anonymous `object : ConversationRepository { … }` stubs that override every interface member. Adding a method to the interface requires adding one override per object literal. Total: **10 object literals across 4 files** (1 in `SettingsViewModelTest.kt`, 2 in `ChannelListViewModelTest.kt`, 3 in `DiscussionListViewModelTest.kt`, 4 in `ArchivedDiscussionsViewModelTest.kt`).

The stub to paste into each, matching the established `TODO("not used")` pattern:

```kotlin
override suspend fun sendMessage(
    conversationId: String,
    text: String,
): Message = TODO("not used")
```

Place it after the last existing override in each object (typically after `changeWorkspace`). These are mechanical compile-fix edits — the new method is unused by any ViewModel under test in this ticket. Do not introduce shared helpers or refactor the stubs; each file already inlines its overrides and the consistency is intentional.

## State + concurrency model

- Mutation is one atomic `state.update { … }` call. `MutableStateFlow.update` already serializes via CAS — no extra locking.
- `now` is captured once before `state.update` and used for both `Message.timestamp` and `Conversation.lastUsedAt`, so observers see a consistent pair.
- `observeMessages(id)` and `observeLastMessage(id)` re-emit automatically because they `map` over `state`.
- `observeConversations(filter)` re-emits the conversation row with the new `lastUsedAt`; the descending-`lastUsedAt` sort projection in `observeConversations` rearranges affected conversations correctly without any extra work here.
- No coroutine launch / dispatcher choice — `suspend` only because the interface mutators are `suspend` and the future remote impl will be I/O-bound. The Fake impl does no actual I/O.

## Error handling

- Unknown `conversationId` → `throw IllegalArgumentException("Unknown conversation: $conversationId")` via the existing `unknown(id)` helper. Same shape as every other mutator.
- No other failure modes in Phase 0. Blank/whitespace `text` is explicitly out of scope per the ticket; this method accepts whatever the caller passes. Do not add trim or `require(text.isNotBlank())` guards — that belongs at the UI layer.

## Testing strategy

Unit tests only (`./gradlew test`). Add to `FakeConversationRepositoryTest.kt`. Use the existing `runBlocking { … }` + `.first()` idiom. No new fakes or fixtures.

Test cases (one `@Test` each):

- **`sendMessage_appendsMessageAtTailOfObserveMessages`** — pick a seeded discussion or channel (e.g. `seed-discussion-a` which has 4 seeded messages); call `repo.sendMessage(id, "hello")`; observe `repo.observeMessages(id).first()`; assert the last `ThreadItem.MessageItem` wraps a `Message` with `content == "hello"`, `role == Role.User`, `isStreaming == false`, and `sessionId` equal to the channel's `currentSessionId`. Assert size grew by exactly 1 (no spurious `SessionBoundary` — the new message is in the current session).
- **`sendMessage_observeLastMessage_reEmitsTheNewMessage`** — same setup; call `sendMessage`; assert `repo.observeLastMessage(id).first()` equals the returned `Message`.
- **`sendMessage_updatesLastUsedAt_andReEmitsViaObserveConversations`** — pick a discussion (e.g. `seed-discussion-b` which has the earlier of the two discussion timestamps, so promoting its `lastUsedAt` will reorder the discussion list); read `lastUsedAt` before; call `sendMessage`; read `observeConversations(ConversationFilter.Discussions).first()`; assert the conversation's `lastUsedAt` strictly increased, equals the returned `Message.timestamp`, and the discussion now sorts first.
- **`sendMessage_onUnknownId_throws`** — `try { runBlocking { repo.sendMessage("nope", "hi") } ; fail(...) } catch (_: IllegalArgumentException) { /* expected */ }`. Mirrors `promote_onUnknownId_throws`.

No instrumented tests. No MockK. No `ComposeTestRule`. No clock injection (the test asserts a *strict increase* in `lastUsedAt`, not a specific instant — `Clock.System.now()` is fine).

## Open questions

None blocking. The ID strategy choice (UUID over `"$sessionId-msg-$index"`) is recorded above; revisit only if Phase 4's remote impl needs server-assigned IDs, in which case the contract may flip to "server returns the persisted Message with its assigned id" — that's a Phase 4 problem, not this ticket's.
