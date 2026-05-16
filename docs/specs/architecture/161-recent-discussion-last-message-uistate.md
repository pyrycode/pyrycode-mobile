# 161 — surface recent-discussion last-message in ChannelListUiState

## Context

`ChannelListUiState.{Empty, Loaded}.recentDiscussions` is `List<Conversation>` — no slot for a per-row last message. The UI follow-up #162 wants to drop the hardcoded placeholder body (`R.string.discussion_preview_placeholder_body`) from `DiscussionPreviewRow` and render the discussion's most-recent message instead, so the data has to be reachable from the UiState first.

This is a data-shape ticket only. AC require zero composable-signature changes, zero rendered-UI changes, and the placeholder string staying put — #162 owns those.

Split from #159.

## Design source

N/A — no UI rendering changes in this ticket. #162 carries the visual swap and references Figma there.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:9-49` — interface contract. The new method drops in next to `observeMessages`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:22-74` — in-memory storage shape (`state: MutableStateFlow<Map<String, ConversationRecord>>`) and the existing `observeMessages` projection. The new method projects from the same `record.messages` list.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:441-469` — `seedDiscussion(...)` helper. Its signature gains an optional `messages: List<SeedMessage>` parameter that mirrors the `currentMessages` slot already on `seedChannel`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:228-289` — seed-channel `currentMessages` and `seedMsg(role, content, timestamp)` patterns. Reuse this shape verbatim for `seed-discussion-a`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:18-99` — `ChannelListUiState` sealed hierarchy and the `combine` that produces it. The new field lands on `Empty` and `Loaded` (Loading + Error unaffected); the combine grows one more input.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:106-116` — `seededDiscussions_remainEmpty` will fail once `seed-discussion-a` carries messages. Narrow it to `seed-discussion-b` + `seed-discussion-archived`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:341-424` — `stubRepo` / `erroringRepo` helpers. Both need an `observeLastMessage` override (single-line `TODO("not used")` is fine for `erroringRepo`; `stubRepo` returns `flowOf(null)` so existing tests still match the default-empty-map case).
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — confirms `Message` is portable (no Android imports); safe to reference from the repository interface.

## Design

### Shape choice: parallel map with a default empty value

The new state slot is `recentDiscussionLastMessages: Map<String, Message>` on `ChannelListUiState.Empty` and `ChannelListUiState.Loaded`, with **default value `emptyMap()`**. Key = conversation id of a recent discussion; absent key = "no last message" (cleaner than `Map<String, Message?>` — null map values are an anti-pattern).

**Why parallel map, not a composite `RecentDiscussion(conversation, lastMessage)`:** the ticket's AC explicitly forbid any composable-signature change. A composite type would force the private `RecentDiscussionsSection(discussions: List<Conversation>, …)` in `ChannelListScreen.kt` to change its parameter type plus its `forEach` body — that's exactly the change AC says belongs in #162. Parallel map keeps `List<Conversation>` untouched in UiState; #162 then iterates the existing list and reads `state.recentDiscussionLastMessages[conversation.id]` per row inside `RecentDiscussionsSection`.

**Why default value `emptyMap()`:** without a default, every existing `Empty(...)` / `Loaded(...)` construction site (6 in `ChannelListScreen.kt` previews, 6 in `ChannelListViewModelTest`, 2 in `ChannelListScreenTest`) would need an explicit `recentDiscussionLastMessages = emptyMap()` argument — a 14-site mechanical cascade. With the default, none of those sites change; only the VM's emission sites and the new tests need to touch the field. The data-class equality semantics still work: the VM emits with an empty map (because the stub `observeLastMessage` returns null) ⇒ matches expected `Empty/Loaded(...)` that omits the new arg.

### Repository surface

Add one method to `ConversationRepository`:

```kotlin
fun observeLastMessage(conversationId: String): Flow<Message?>
```

Semantics: emits the most-recent `Message` (by `Message.timestamp`) for the conversation, or `null` if the conversation has no messages or doesn't exist. Cold flow, same shape as `observeMessages` — re-emits on every state change.

Per-row, not batched: the recent slice is capped at 3 (`RECENT_DISCUSSIONS_LIMIT`), so `combine` over up to 3 sources is bounded; a batched `observeLastMessages(ids: Set<String>)` would be over-engineering for Phase 1. Method name mirrors `observeMessages` so the surface stays self-similar.

### FakeConversationRepository implementation

1. **`observeLastMessage(id)` body** — project from the existing `state` flow: `state.map { records[id]?.messages?.maxByOrNull { it.timestamp } }`. Reuses the same `MutableStateFlow` that backs `observeMessages`; emissions are automatically consistent.

2. **`seedDiscussion` helper** — add an optional `messages: List<SeedMessage> = emptyList()` parameter (mirror `seedChannel`'s `currentMessages`). When non-empty, build `Message` entries the same way `seedChannel` does for `currentMessages` (id = `"$sessionId-msg-$index"`, `sessionId` = the seed's `sessionId`, `isStreaming = false`), and pass them to the returned `ConversationRecord(messages = ...)`.

3. **Extend `seed-discussion-a`** — pass 3–4 short messages spanning a few minutes ending at the seed's `lastUsedAt` (`2026-05-11T14:00:00Z`). Reuse the `seedMsg(Role.User|Assistant, "...", "<iso>")` factory. Content suggestion (architect freehand — developer may keep or rewrite): a quick user question and assistant reply pair, then a short follow-up; the last message's timestamp must equal `2026-05-11T14:00:00Z` so it's deterministic for the test. Keep `seed-discussion-b` and `seed-discussion-archived` empty (the no-messages path).

### ChannelListViewModel state composition

The current `combine(channelsFlow, discussionsFlow) { ... }` grows a third input: a derived `Flow<Map<String, Message>>` over the recent slice. Sketch (contract — developer writes the operators):

- Derive a **recent-ids flow** from the discussions flow: `discussions.map { it.take(RECENT_DISCUSSIONS_LIMIT).map(Conversation::id) }.distinctUntilChanged()`. `distinctUntilChanged` is required so a stable id set doesn't tear down the inner combine on every re-emission.
- `flatMapLatest` over the recent-ids flow: when ids is empty, `flowOf(emptyMap())`; otherwise `combine(ids.map { id -> repository.observeLastMessage(id).map { msg -> id to msg } }) { pairs -> pairs.mapNotNull { (id, msg) -> msg?.let { id to it } }.toMap() }`. Null entries are filtered out — the map only carries non-null last messages.
- Top-level `combine(channelsFlow, discussionsFlow, lastMessagesFlow) { channels, discussions, lastMessages -> ... }` builds `Empty` / `Loaded` with the new field populated.

**Key invariant:** the map only contains entries for ids in the current recent slice. If `RECENT_DISCUSSIONS_LIMIT` rows shift (new discussion appears, becomes most-recent), `flatMapLatest` cancels the old per-row subscriptions and re-subscribes for the new id set — no stale entries leak through.

**No change to `RECENT_DISCUSSIONS_LIMIT`, no change to ordering, no change to the `recentDiscussions`/`recentDiscussionsCount` fields.**

### Loading semantics

The `Loading` state currently persists until both source flows emit. With the third input, `Loading` persists until **all three** emit. The recent-ids derivation emits as soon as `discussions` emits (empty list ⇒ `emptyMap()` synchronously). So in practice the loading window is unchanged: the third input is ready the same tick as `discussionsFlow` settles. Verify with the existing `loadingPersists_untilBothFlowsEmit` test — keep it as-is; it still passes because the stub `observeLastMessage` returns `flowOf(null)` which `combine` consumes synchronously.

### Error handling

The existing `.catch { e -> emit(Error(...)) }` covers the new flow too — any exception thrown by `observeLastMessage` propagates through `combine` into the same catch block. No new error pathways. The error message format is unchanged.

## State + concurrency model

- All flows are cold; the top-level `stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = Loading)` is unchanged.
- `flatMapLatest` over the recent-ids flow guarantees that when the recent slice changes (e.g., a new discussion takes the #1 spot), the obsolete per-row `observeLastMessage` subscriptions are cancelled before new ones start. No leak, no race.
- `distinctUntilChanged` on the ids list prevents re-subscription churn when the discussions flow re-emits without changing the recent-3.
- Single `StateFlow<ChannelListUiState>` exposed by the VM — no sidecar flow, satisfies the project's "single source of state per ViewModel" convention.

## Error handling

- `observeLastMessage` on an unknown id ⇒ emits `null`. Same shape as `observeMessages` emitting `emptyList()` for unknown ids; no exception.
- VM's existing `.catch { e -> emit(Error(...)) }` covers any unexpected throw from the new flow.
- No new user-visible error surfaces; UI behavior is unchanged this ticket.

## Testing strategy

All unit tests via `./gradlew test`. No instrumented changes (the existing `ChannelListScreenTest` keeps passing with no edits because the default `emptyMap()` matches what the test composes).

### New tests in `FakeConversationRepositoryTest`

- `observeLastMessage_returnsNull_whenConversationHasNoMessages` — call `observeLastMessage("seed-discussion-b").first()`, assert null.
- `observeLastMessage_returnsNull_whenConversationUnknown` — `observeLastMessage("does-not-exist").first()`, assert null.
- `observeLastMessage_returnsMostRecentByTimestamp_whenMessagesExist` — call `observeLastMessage("seed-discussion-a").first()`, assert non-null and assert `timestamp == Instant.parse("2026-05-11T14:00:00Z")` (the latest seeded). This anchors the "most-recent by timestamp" contract.

### Updated test in `FakeConversationRepositoryTest`

- `seededDiscussions_remainEmpty` (line 106): narrow the id list from `["seed-discussion-a", "seed-discussion-b", "seed-discussion-archived"]` to `["seed-discussion-b", "seed-discussion-archived"]`. Rename to `seededDiscussions_remainEmpty_exceptDiscussionA` if it makes intent clearer; either is fine.

### New test in `ChannelListViewModelTest`

- `recentDiscussionLastMessages_populatedFromFake_endToEnd` — construct `ChannelListViewModel(FakeConversationRepository())`, collect first non-Loading state, assert:
  - `state` is `Empty` (the fake seeds 3 channels, but the test should use the fake's actual data — verify whether the resulting state is `Empty` or `Loaded` per the seeds. The fake seeds 3 channels, so state is `Loaded`. Adjust assertion accordingly.)
  - `state.recentDiscussionLastMessages["seed-discussion-a"]` is non-null and has the expected timestamp.
  - `state.recentDiscussionLastMessages["seed-discussion-b"]` is absent (not in the map at all — non-null filtering).
- This is the AC-mandated "exercise both cases — last message present and no messages" test, done at the VM layer through the real fake.

### Updated test helpers in `ChannelListViewModelTest`

- `stubRepo(...)`: add `override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)` (import `kotlinx.coroutines.flow.flowOf`). With this, every existing stub-backed test produces `recentDiscussionLastMessages = emptyMap()`, which matches the default on the expected `Empty/Loaded(...)` — **no other test changes needed**.
- `erroringRepo(...)`: add `override fun observeLastMessage(conversationId: String): Flow<Message?> = TODO("not used")`. The erroring tests never collect past the initial Error emission, so this is unreachable.

### What does NOT change

- `ChannelListScreen.kt` — no edits. All preview construction sites (`Empty(...)` / `Loaded(...)`) rely on the new field's default; `RecentDiscussionsSection`'s signature and body are untouched.
- `ChannelListScreenTest.kt` — no edits, same reason.
- `DiscussionPreviewRow.kt`, `strings.xml` — out of scope (owned by #162).
- `Conversation.kt`, `Message.kt` — no model changes; the new field references `Message` directly.

## Open questions

None blocking. One judgment call left to the developer: the exact content of the 3–4 messages seeded into `seed-discussion-a`. Keep them short, in `User → Assistant → User → Assistant` order matching channel seeds, with the last message's `timestamp` exactly `2026-05-11T14:00:00Z` (the seed's `lastUsedAt`). The test asserts on the timestamp only, not the content — content can be any plausible scratch-thread snippet.
