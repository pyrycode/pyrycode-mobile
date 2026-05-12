# Spec — Add message storage + boundary interleaving to `FakeConversationRepository` (#9)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-64` — full interface and the existing `ThreadItem` / `SessionBoundary` / `BoundaryReason` types. **Do not redefine these.** Produce instances at lines 52-64.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — full file. Specifically:
  - Lines 22 — single `state: MutableStateFlow<Map<String, ConversationRecord>>` is the only source of truth; the new feature extends this same state, not a sibling map.
  - Lines 38-39 — current `observeMessages` stub (`flowOf(emptyList())`). This is what we replace.
  - Lines 153-156 — `private data class ConversationRecord(conversation, sessions)`. We add a `messages: List<Message>` field with default `emptyList()`.
  - Lines 24-36 — `observeConversations` projection pattern (`state.map { records -> ... }`). The new `observeMessages` mirrors this shape.
  - Lines 158-258 — the `companion object` with `SEED_RECORDS` and `seedChannel(...)` / `seedDiscussion(...)`. The new `messages = emptyList()` default means **none of these helpers need changes** — they continue to compile unchanged.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — `Message(id, sessionId, role, content, timestamp, isStreaming)`. The interleaving key is `sessionId`; the ordering key is `timestamp` (`kotlinx.datetime.Instant`).
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` — full file. One existing test changes name + assertion shape (`observeMessages_emitsEmpty` at lines 22-25, currently a stub-era placeholder); two new tests are added. All other tests are untouched.
- `CLAUDE.md` (repo root) — "Conversations model" section. Sessions are nested under conversations; threads render messages chronologically across sessions with **delimiters at session boundaries**. That delimiter is what `SessionBoundary` represents.
- `gradle/libs.versions.toml` — no new dependencies. `kotlinx-datetime` and `kotlinx-coroutines-core` are already on the classpath (used by the existing repository).

## Context

`ConversationRepository.observeMessages(conversationId): Flow<List<ThreadItem>>` was declared in #3 / #4 with a `flowOf(emptyList())` stub. The thread screen (Phase 1, not yet built) will collect this flow and render messages with horizontal-rule delimiters between sessions. The interleaving logic must live in the repository — the AC is explicit: "without bespoke client logic."

This ticket implements the mechanism only. Seeded message *content* is delivered by #10. Defaulting per-conversation storage to empty keeps every existing test green: the five seeded conversations from #4/#5/#6 all start with zero messages, and the only existing message-related test (`observeMessages_emitsEmpty`) survives a one-line rename + assertion tweak.

## Design

### File layout — 1 production file, 1 test file

| File | Change |
|------|--------|
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | (a) add `messages: List<Message> = emptyList()` to `ConversationRecord`; (b) add an optional constructor parameter `initialMessages: Map<String, List<Message>> = emptyMap()` that lets tests pre-populate storage; (c) replace `observeMessages` stub with a `state.map { … }` projection; (d) add a private `buildThreadItems(messages: List<Message>): List<ThreadItem>` helper. |
| `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` | Repurpose the existing `observeMessages_emitsEmpty` stub test as `observeMessages_unknownConversation_emitsEmpty`; add `observeMessages_knownConversationWithNoMessages_emitsEmpty`; add `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary`. |

No new files. No new exported types. No new dependencies.

### Storage shape — extend `ConversationRecord`, not a sibling map

```kotlin
private data class ConversationRecord(
    val conversation: Conversation,
    val sessions: Map<String, Session>,
    val messages: List<Message> = emptyList(),
)
```

**Why extend `ConversationRecord` rather than add a sibling `MutableStateFlow<Map<String, List<Message>>>`.** All mutators in this class (`createDiscussion`, `promote`, `archive`, `rename`, `startNewSession`, `changeWorkspace`) update `state` atomically inside a single `state.update {}` block. A sibling state holder for messages would split the source of truth — a mutator that affects both conversation metadata and messages (none today, but inevitable in #10+ and beyond) would need to coordinate two `update {}` calls and could leave observers viewing torn state between the two emissions. Single state holder, single `update`, consistent across all observers. Same reason `sessions` lives inside the record and not in its own map.

**Default value `emptyList()`.** The five existing `seedChannel(...)` / `seedDiscussion(...)` helpers call `ConversationRecord(conversation, mapOf(sessionId to session))` positionally; with a defaulted third parameter, those call sites are untouched. `createDiscussion`'s `ConversationRecord(conversation, mapOf(sessionId to session))` (line 62) is similarly unaffected. Same for the `mintNewSession` mutator (line 141-144). **Zero existing mutator/seed-builder lines change.**

### Test injection — constructor parameter, not a test-only mutator

```kotlin
class FakeConversationRepository(
    initialMessages: Map<String, List<Message>> = emptyMap(),
) : ConversationRepository {

    private val state = MutableStateFlow(
        SEED_RECORDS.mapValues { (id, record) ->
            initialMessages[id]?.let { record.copy(messages = it) } ?: record
        }
    )
    // ...
}
```

Production callers (`PyryApp.kt`'s Koin module in #11+ and beyond) construct `FakeConversationRepository()` with the default; the parameter is invisible to them. Tests pass a `mapOf("seed-channel-personal" to listOf(msg1, msg2, ...))` to set up boundary scenarios. **Unknown conversation ids in `initialMessages` are silently ignored** — the `mapValues` only overrides records that exist in `SEED_RECORDS`. This is the conservative default; a test that typos a seed id sees an empty thread, not a runtime throw, but the boundary-AC test pins down expected size so a typo fails the assertion loudly anyway.

**Why constructor parameter instead of `internal fun seedMessages(...)`.** A test-only mutator is a smell — it opens runtime mutation of internal state to anything in the same module. The constructor parameter is read-once at construction and the same surface that #10 will lean on when it teaches `buildSeedRecords()` to attach message content to specific seeded conversations. (#10's natural extension point is `seedChannel(... , messages: List<Message> = emptyList())` and `seedDiscussion(... , messages: List<Message> = emptyList())`; that's #10's design choice. The constructor parameter exists for test scenarios where the fixture wants different message sets per test, independent of the production seed content.)

### `observeMessages` projection

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
    state.map { records ->
        val messages = records[conversationId]?.messages ?: return@map emptyList()
        buildThreadItems(messages)
    }
```

Mirrors `observeConversations`'s shape (cold flow derived from `state` via `.map`). Re-emits on every `state.update {}` regardless of whether the change affected *this* conversation — same emission characteristic as `observeConversations`. The AC bullet "re-emits whenever message storage mutates" is satisfied as a property of the `MutableStateFlow.map` composition; no separate emission-tracking machinery needed.

**Unknown conversation id and known-with-no-messages collapse to the same path.** An unknown id misses the map lookup → emit `emptyList()`. A known id with `messages = emptyList()` flows into `buildThreadItems(emptyList())` → returns `emptyList()`. Both AC clauses satisfied by one branch each.

**No `.distinctUntilChanged()`.** Consistency with `observeConversations` (which doesn't have it either). A subtype-aware dedupe is a future optimization, not a current AC requirement. Don't preempt.

### `buildThreadItems` — one linear pass

```kotlin
private fun buildThreadItems(messages: List<Message>): List<ThreadItem> {
    if (messages.isEmpty()) return emptyList()
    val sorted = messages.sortedBy { it.timestamp }
    val result = ArrayList<ThreadItem>(sorted.size + sorted.size / 4) // headroom for ~25% boundaries
    var previousSessionId: String? = null
    for (message in sorted) {
        val prior = previousSessionId
        if (prior != null && prior != message.sessionId) {
            result += ThreadItem.SessionBoundary(
                previousSessionId = prior,
                newSessionId = message.sessionId,
                reason = BoundaryReason.Clear,
                occurredAt = message.timestamp,
            )
        }
        result += ThreadItem.MessageItem(message)
        previousSessionId = message.sessionId
    }
    return result
}
```

Three decisions worth pinning:

1. **Sort by `Message.timestamp` ascending inside the projection, not at storage time.** Storage is a `List<Message>`; nothing else in the class promises insertion order matches timestamp order, so the projection has to sort defensively. The cost is `O(n log n)` per emission, which is fine for Phase 1 fixture sizes (tens to hundreds of messages, not thousands).

2. **`BoundaryReason.Clear` as the derived-boundary default.** The AC explicitly hands reason-mapping to the architect and says boundaries can be derived from session-id deltas alone in this ticket. With no metadata distinguishing "user typed `/clear`" from "idle eviction" from "workspace change," `Clear` is the right pick: it's the most frequent reason in pyrycode's conversations model, and the UI delimiter's affordance (memory-plugin install hint) reads naturally for `Clear` even when the underlying cause was something else. When eager boundary emission lands (out-of-scope per the ticket), per-session reason metadata can be added to `Session` or `ConversationRecord` and threaded through here; until then, `Clear` is honest about the inference being lossy.

3. **`occurredAt = message.timestamp` (the *new* session's first message).** Alternative would be the *previous* session's last-message timestamp. Picking the new-message timestamp places the boundary visually at the moment the new session started producing observable content — which matches "occurred at" semantically better than "the previous session's last word." Either choice is defensible; pin one so the AC test can assert.

**Why a manual loop instead of `fold`/`zipWithNext`/etc.** The loop is ~15 lines and reads top-to-bottom; a chained-collection version would save 3 lines while obscuring the "boundary emitted between consecutive elements" structure. Pyrycode's bias is the readable imperative form for fixture-quality code paths.

### Why not eager boundary emission yet

`startNewSession` and `changeWorkspace` could write a `SessionBoundary` directly into `messages` (or a sibling `boundaries` list) at mutation time, attaching the *true* reason (`Clear` vs `WorkspaceChange`) and the *true* `occurredAt` (the mutator's `Clock.System.now()`). The ticket's "Out of scope" bullet explicitly defers this. Two reasons it's right to defer:

1. **Phase 1 has no live consumer for boundaries yet.** The thread screen isn't built. There's nothing to observe a richer boundary, so building the richer mechanism costs LOC for zero behavioural payoff.
2. **The seeded-message ticket (#10) hasn't decided how it wants to attach reasons.** Coupling this ticket's design to a presumed `reason: BoundaryReason` field on `Session` would lock #10 into that shape. Derive now, design eagerly later when #10 (and the thread UI ticket) make the shape concrete.

## State + concurrency model

Unchanged. Single `MutableStateFlow<Map<String, ConversationRecord>>`. All reads via `state.map { ... }`, all writes via `state.update { ... }`. No new dispatchers, no new scopes, no new flow operators. Cold-stream semantics on `observeMessages` match `observeConversations` (subscriber gets the current value on collection start and every subsequent state mutation).

This ticket adds **zero new mutators**. Future tickets (#10 seeded message content; future "send message" tickets) will use the same `state.update { records -> records + (id to records[id]!!.copy(messages = ...)) }` pattern that the existing mutators already establish.

## Error handling

Unchanged. Unknown `conversationId` to `observeMessages` returns an empty list (per AC) — no throw. This is asymmetric with `promote`/`rename`/etc. which throw `IllegalArgumentException` on unknown id, but the asymmetry is correct: observation should be tolerant (a stale id from a recomposing screen should produce an empty thread, not crash), whereas mutation should be strict (a mutator with a stale id is a programming error).

## Testing strategy

`./gradlew test`. JUnit 4 + `kotlinx.coroutines.runBlocking` + `Flow.first()`, exactly as in the existing test file. No new test dependencies.

### Existing tests — one rename + assertion update, others untouched

| Test | Line(s) | Change |
|------|---------|--------|
| `observeMessages_emitsEmpty` | 22-25 | **Rename** to `observeMessages_unknownConversation_emitsEmpty`. Keep the assertion as-is (`"any-id"` is genuinely unknown). The new name makes the test's intent unambiguous now that there's a *known-conversation-with-no-messages* test alongside it. |

All other tests are untouched. None of them touch `observeMessages` or assert on `ConversationRecord` internals. Seed counts (3 channels + 2 discussions = 5) are preserved because `messages: List<Message> = emptyList()` defaults silently.

### New tests — three

```kotlin
@Test
fun observeMessages_knownConversationWithNoMessages_emitsEmpty() = runBlocking {
    val repo = FakeConversationRepository()
    assertEquals(emptyList<ThreadItem>(), repo.observeMessages("seed-channel-personal").first())
}
```

Pins down AC bullet 3's *known-conversation-with-no-messages* clause. Uses a stable seeded id (`"seed-channel-personal"` from #5) so the assertion doesn't depend on `createDiscussion()` minting a UUID.

```kotlin
@Test
fun observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary() = runBlocking {
    val sessionA = "session-a"
    val sessionB = "session-b"
    val messages = listOf(
        Message("m1", sessionA, Role.User,      "hi",    Instant.parse("2026-05-10T10:00:00Z"), false),
        Message("m2", sessionA, Role.Assistant, "hello", Instant.parse("2026-05-10T10:01:00Z"), false),
        Message("m3", sessionB, Role.User,      "again", Instant.parse("2026-05-10T11:00:00Z"), false),
        Message("m4", sessionB, Role.Assistant, "back",  Instant.parse("2026-05-10T11:01:00Z"), false),
    )
    val repo = FakeConversationRepository(
        initialMessages = mapOf("seed-channel-personal" to messages),
    )

    val items = repo.observeMessages("seed-channel-personal").first()

    // 4 messages + 1 boundary between m2 and m3.
    assertEquals(5, items.size)

    val boundaries = items.filterIsInstance<ThreadItem.SessionBoundary>()
    assertEquals(1, boundaries.size)
    val b = boundaries.single()
    assertEquals(sessionA, b.previousSessionId)
    assertEquals(sessionB, b.newSessionId)
    assertEquals(Instant.parse("2026-05-10T11:00:00Z"), b.occurredAt)
    assertEquals(BoundaryReason.Clear, b.reason)

    // Boundary sits exactly between m2 (last of A) and m3 (first of B).
    val boundaryIndex = items.indexOfFirst { it is ThreadItem.SessionBoundary }
    assertEquals(ThreadItem.MessageItem(messages[1]), items[boundaryIndex - 1])
    assertEquals(ThreadItem.MessageItem(messages[2]), items[boundaryIndex + 1])

    // Chronological ordering is preserved overall.
    val messageItems = items.filterIsInstance<ThreadItem.MessageItem>()
    assertEquals(messages, messageItems.map { it.message })
}
```

Pins down AC bullet 2 (interleaving, ordering, single boundary at the delta) and AC bullet 4 (the explicit ACS-named test). The `reason = Clear` and `occurredAt = first-message-of-B.timestamp` assertions lock in the two architect-judgement-call defaults so a future change there breaks the test loudly.

```kotlin
// Already covered: observeMessages_unknownConversation_emitsEmpty (renamed from the existing stub test).
```

### What's not tested (out of scope)

- **Eager re-emission on a message-storage mutation.** This ticket adds no message mutator (the constructor injects a snapshot; nothing changes it post-construction). Re-emission is a property of `MutableStateFlow.map`, not of new code introduced here. #10 / future ticket adds the first message mutator and the re-emission test alongside it.
- **`BoundaryReason.IdleEvict` and `BoundaryReason.WorkspaceChange`.** The derived path emits `Clear` for every boundary. Other reasons land when eager emission lands.
- **Out-of-order timestamps within a single session.** The projection sorts by `timestamp` ascending unconditionally; if a test fixture inserts two same-session messages in reverse-timestamp order, the projection silently reorders. No AC pins this; no test asserts it. If #10 needs deterministic out-of-order behaviour it can pin it then.
- **Equal timestamps.** `sortedBy` is stable; ties preserve insertion order. No AC pins this; no test asserts it.
- **Concurrent collection** — two simultaneous collectors on the same `conversationId`. Phase 0 has no such consumer.

## Out of scope

- Seeded message *content* (#10).
- Eager boundary emission from `startNewSession` / `changeWorkspace` with the true `BoundaryReason`.
- Per-session reason metadata on `Session` or `ConversationRecord`.
- DI wiring of `initialMessages` for production (production passes the default; tests pass non-default).
- Anything in `ui/conversations/thread/` — the thread screen is a separate ticket.

## Open questions

None. Storage shape (extend `ConversationRecord`), test-injection path (constructor parameter), derived-reason default (`Clear`), and `occurredAt` choice (new-session first-message timestamp) are all pinned.
