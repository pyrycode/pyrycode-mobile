# Spec — seed channels with multi-session message histories (#10)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` (whole file, 290 lines) — primary edit target; the `companion object` holding `SEED_RECORDS`, `buildSeedRecords`, `seedChannel`, `seedDiscussion` lives here. Note especially `seedChannel` (lines 237-262), which produces today's one-session/zero-message seeds, and the private `ConversationRecord` (lines 183-187) — it already carries `messages: List<Message> = emptyList()`, so the storage shape is in place; this ticket fills it for channels.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:46-64` — `ThreadItem` sealed interface, `SessionBoundary` shape, `BoundaryReason` enum. Confirms the `Clear` reason is what `buildThreadItems` will tag seeded boundaries with (per the ticket's Technical Notes — out of scope to change).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:50-69` — `buildThreadItems`: sorts messages by timestamp, interleaves `SessionBoundary` between adjacent messages whose `sessionId` differs. Tells you the invariant your seed data must satisfy (each session's messages contiguous in time, no interleaving across sessions, no duplicate timestamps if you want stable order).
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` (15 lines) — `Message` shape: `id`, `sessionId`, `role`, `content`, `timestamp`, `isStreaming`. Seeds use `isStreaming = false`.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` (12 lines) — `Session` shape: `id`, `conversationId`, `claudeSessionUuid`, `startedAt`, `endedAt`. Historical sessions need non-null `endedAt`; the current session keeps `endedAt = null`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — `currentSessionId` and `sessionHistory: List<String>` shape. `sessionHistory` must list all session IDs in chronological order ending with `currentSessionId`.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` (whole file, 204 lines) — existing test suite. Two tests need attention:
  - Line 31-37 (`observeMessages_knownConversationWithNoMessages_emitsEmpty`) — currently asserts `seed-channel-personal` emits empty. **Will break under the new seed.** Redirect this test to a discussion (`seed-discussion-a` or `seed-discussion-b`), which AC keeps at zero messages.
  - Line 161-176 (`observeConversations_Channels_…orderedByLastUsedAtDescending`) — checks `channels.all { it.currentSessionId.isNotBlank() }` and that the three channels' `lastUsedAt` timestamps are unique and descending. Your new seeds must preserve those invariants — specifically, keep each channel's `Conversation.lastUsedAt` unchanged (the three existing values: `2026-05-05T20:15:00Z`, `2026-05-10T09:00:00Z`, `2026-05-08T15:30:00Z`) and ensure `currentSessionId` is the latest session's id.

## Context

#9 added per-conversation `messages: List<Message>` storage on `ConversationRecord` and the `buildThreadItems` pipeline that interleaves `SessionBoundary` markers between adjacent messages with different `sessionId`s. The pipeline is correct; it just has nothing to render — every seeded conversation ships with an empty messages list.

This ticket populates the three seeded channels with multi-session message histories so the Phase 1 channel list and the Phase 2 conversation thread screen have realistic data — both message rows and the horizontal-rule boundary delimiters between sessions.

Discussions (`seed-discussion-a`, `seed-discussion-b`) remain at zero messages. They're scratch conversations and may never need fixtures.

## Design

### Where the change lives

All changes are inside `FakeConversationRepository.kt`'s `companion object`. No public API change, no new file, no DI change. The constructor's `initialMessages` seam (line 21-28) keeps working unchanged: production callers (`FakeConversationRepository()`) pick up the seeded histories; tests that pass `initialMessages = mapOf(id to listOf(...))` continue to override per-channel.

### Seed shape per channel

Each of the three channels gets **2 sessions** (the existing current session + 1 historical session). The AC allows 2–3; pick 2 uniformly for simplicity and to keep the test assertions clean (boundary count = `sessions - 1` is well-defined either way, but the test reads better with a single expected number).

Per channel:

- **Historical session:** new `Session.id` (e.g. `seed-session-personal-1`, mirroring the existing convention of human-readable IDs). `startedAt` and `endedAt` both set; `endedAt` strictly before the current session's `startedAt`. 4 messages alternating `Role.User` / `Role.Assistant`.
- **Current session:** keeps the existing `Session.id` (`seed-session-personal`, etc.) and existing `claudeSessionUuid`. `startedAt = lastUsedAt` (unchanged), `endedAt = null`. 4 messages alternating roles, all timestamps strictly between the historical session's `endedAt` and the channel's `lastUsedAt` (or equal to `lastUsedAt` for the final message — either works; pick `<=` for the final and `<` for the rest to avoid timestamp collisions inside `buildThreadItems`'s `sortedBy { it.timestamp }`).

4 messages per session × 2 sessions × 3 channels = 24 messages total. Within the 3–8 AC range, alternating roles cleanly (User → Assistant → User → Assistant).

### Helper shape

**Extend** the existing `seedChannel` helper rather than introducing a sibling. The new shape:

```kotlin
private fun seedChannel(
    id: String,
    name: String,
    cwd: String,
    sessionId: String,
    claudeSessionUuid: String,
    lastUsedAt: Instant,
    history: List<SeedSession> = emptyList(),
): ConversationRecord
```

Where `SeedSession` is a private data class colocated in the companion object:

```kotlin
private data class SeedSession(
    val sessionId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val messages: List<SeedMessage>,
)

private data class SeedMessage(
    val role: Role,
    val content: String,
    val timestamp: Instant,
)
```

`SeedMessage` is a thin authoring affordance — it lets the seed list omit `id`, `sessionId`, and `isStreaming = false`, which the helper fills in. The helper assigns a stable id per message (e.g. `"${sessionId}-msg-${index}"`) so tests can compare structurally if they need to, and passes `isStreaming = false`.

The current-session messages stay outside the `history` parameter — add a `currentMessages: List<SeedMessage> = emptyList()` parameter too, with timestamps strictly after the last history session's `endedAt`. This keeps the call-site obvious about which messages belong to which session.

Final signature:

```kotlin
private fun seedChannel(
    id: String,
    name: String,
    cwd: String,
    sessionId: String,
    claudeSessionUuid: String,
    lastUsedAt: Instant,
    history: List<SeedSession> = emptyList(),
    currentMessages: List<SeedMessage> = emptyList(),
): ConversationRecord
```

The helper:
1. Builds the current `Session` as today (unchanged shape: `endedAt = null`, `startedAt = lastUsedAt`).
2. Builds a `Session` per `SeedSession` entry with the given `startedAt` / `endedAt`.
3. Flattens all messages into a single `List<Message>` in this order: history sessions in list order, then current-session messages. Caller is responsible for chronological ordering of timestamps — the helper does not sort. (Optionally assert in debug, but skip — `buildThreadItems` sorts at observe time, and the AC test enforces ordering.)
4. Sets `Conversation.sessionHistory = history.map { it.sessionId } + sessionId` and `currentSessionId = sessionId`.
5. Returns `ConversationRecord(conversation, sessions = (historySessions + currentSession).associateBy { it.id }, messages = allMessages)`.

`seedDiscussion` is unchanged — no parameters added, no body change. Discussions keep their empty messages list via the default.

### Seed content

Thematic, brief, plausible. No need for cleverness — just enough to look like real conversation snippets. Examples (you can adjust wording; what matters is theme alignment):

**`seed-channel-personal`** (`2026-05-05T20:15:00Z` last used):
- Historical session `seed-session-personal-1` (started `2026-04-28T10:00:00Z`, ended `2026-04-28T10:30:00Z`): 4 messages around a general personal-org topic (e.g. "remind me to renew the lease", "I'll add a calendar entry…").
- Current session `seed-session-personal`: 4 messages timestamped `2026-05-05T19:55:00Z` → `2026-05-05T20:15:00Z`, general topic.

**`seed-channel-pyrycode-mobile`** (`2026-05-10T09:00:00Z`):
- Historical session `seed-session-pyrycode-mobile-1` (started `2026-05-03T14:00:00Z`, ended `2026-05-03T14:25:00Z`): 4 messages about Phase 1 scaffolding ("what's left on the channel list…", "we still need the empty state…").
- Current session: 4 messages about ongoing work, timestamps strictly inside `2026-05-10T08:45:00Z` … `2026-05-10T09:00:00Z`.

**`seed-channel-joi-pilates`** (`2026-05-08T15:30:00Z`):
- Historical session `seed-session-joi-pilates-1` (started `2026-04-30T11:00:00Z`, ended `2026-04-30T11:20:00Z`): 4 messages on studio topics (class schedule, instructor cover).
- Current session: 4 messages around `2026-05-08T15:15:00Z` → `2026-05-08T15:30:00Z`.

All content English; keep messages short (1–2 sentences). No PII, no profanity, no copyrighted excerpts.

### Invariants the developer must enforce

1. Historical-session `endedAt` ≠ null; current-session `endedAt` = null. (AC explicit.)
2. For each channel, every historical session's `endedAt` ≤ the current session's `startedAt`. Within each session, messages' timestamps are strictly ascending. Across sessions, the maximum timestamp of session N ≤ the minimum timestamp of session N+1. This is what makes `buildThreadItems`'s timestamp-sort produce contiguous per-session message runs and exactly `sessions - 1` boundaries.
3. `Conversation.lastUsedAt` is unchanged from today's values. (Don't drift the channel-list ordering test at line 161-176.)
4. `Conversation.currentSessionId` = the existing session id (e.g. `seed-session-personal`). `Conversation.sessionHistory` = `[historical-1.id, …, current.id]`.
5. Each `Message.sessionId` matches its enclosing session's id. (Trivial — but a typo here silently breaks boundary placement.)

### State + concurrency model

No change. `FakeConversationRepository`'s `MutableStateFlow<Map<String, ConversationRecord>>` is initialized at construction time as before; the only difference is that `SEED_RECORDS` now contains populated `messages` for the three channels. Existing `observeMessages` / `observeConversations` collectors require no change.

### Error handling

None. Seed data is pure constants — no IO, no parsing, no failure modes. The compiler enforces shape.

## Testing strategy

Unit tests only (`./gradlew test`). Add to `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt`.

### New tests (AC-driven)

1. **Boundary count per seeded channel.** For each of the three channel ids, assert that `observeMessages(id).first().filterIsInstance<ThreadItem.SessionBoundary>().size == 1` (since we picked 2 sessions per channel; equivalent to "sessions − 1"). Easiest as a parameterized loop or three near-identical assertions.

2. **Chronological ordering.** For each seeded channel, the list emitted by `observeMessages` has non-decreasing timestamps across `MessageItem` entries:
   ```kotlin
   val items = repo.observeMessages(id).first()
   val msgTimestamps = items.filterIsInstance<ThreadItem.MessageItem>().map { it.message.timestamp }
   assertEquals(msgTimestamps.sorted(), msgTimestamps)
   ```

3. **(Recommended additional)** `sessionHistory` and `currentSessionId` shape:
   - `conversation.sessionHistory.last() == conversation.currentSessionId`
   - `conversation.sessionHistory.size == 2` (matches sessions per channel)
   - `conversation.sessionHistory.toSet().size == 2` (no duplicates)

   Pull the conversation via `observeConversations(ConversationFilter.Channels).first().first { it.id == channelId }`.

4. **Discussions remain empty.** `observeMessages("seed-discussion-a").first()` and `observeMessages("seed-discussion-b").first()` both emit `emptyList()`. (One assertion each, or a single loop.)

### Test that needs updating

`observeMessages_knownConversationWithNoMessages_emitsEmpty` (lines 31-37). Today it uses `seed-channel-personal`; that id will now have messages. Change the conversation id to `seed-discussion-a` (which AC guarantees stays empty). Don't delete the test — it covers the `known id, zero messages` branch (`buildThreadItems(emptyList())` returning `emptyList`), which is semantically distinct from `unknown id` (the `?: return@map emptyList()` early return at line 46).

### Test that incidentally validates the change

`observeConversations_Channels_emitsThreeSeededChannels_orderedByLastUsedAtDescending` (line 161-176) — should still pass without changes, but the developer should run the whole suite and confirm. The assertion `channels.all { it.currentSessionId.isNotBlank() }` and the timestamp-uniqueness check are the load-bearing invariants the new seeds must not break.

### Out of test scope

- Don't add a test for `BoundaryReason` value — the ticket explicitly defers `BoundaryReason` semantics, and the existing test at line 39-71 already asserts the hard-coded `Clear` value.
- Don't add a "session count = N" structural test on `ConversationRecord.sessions` — that field is `private`. Test via the public observation surface (`sessionHistory.size`).

## Open questions

None blocking. Two minor authoring choices the developer can make freely:

1. Whether to use a tiny helper like `private fun seedMessage(role: Role, content: String, ts: String) = SeedMessage(role, content, Instant.parse(ts))` to keep the seed list readable. Recommended but not required.
2. Whether historical-session ids use a `-1` suffix (`seed-session-personal-1`) or a topic-based name (`seed-session-personal-old`). The `-1` suffix is forward-compatible if someone later wants `-2`; pick that.
