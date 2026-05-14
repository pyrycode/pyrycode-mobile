# Spec: `unarchive()` primitive on `ConversationRepository` (#96)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-47` — interface contract; new method slots in next to `archive` (line 31)
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:125-133` — `archive()` impl; `unarchive()` is its inverse with the same `state.update { ... unknown(id) ... }` shape
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-46` — `observeConversations` filter projection; confirms the test assertions (Archived stream filters `conv.archived`, Discussions filters `!conv.isPromoted && !conv.archived`)
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:203` — `unknown(id)` helper to reuse for the unknown-ID case
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:326-333` — `seed-discussion-archived` is already in `SEED_RECORDS` with `archived = true`; the round-trip test should use it (no need to create new seed data)
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:209-286` — existing `archive_*` tests; the new `unarchive_*` tests follow the same idioms (`runBlocking`, `repo.observeConversations(filter).first()`, `assertTrue`-with-message style)
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:14` — confirms `archived: Boolean = false` lives on `Conversation` (no domain-model change needed)

## Context

#93 introduced the `archived: Boolean` flag and `ConversationFilter.Archived`, plus the flag-based `archive()` operation that retains the row (not delete). The Archived Discussions UI restore action (#94) needs the inverse primitive: clear the flag so the row reappears in `ConversationFilter.Discussions` (or `Channels`, if it had been promoted) and disappears from `ConversationFilter.Archived`. This ticket adds only the data-layer primitive — no UI, no ViewModel.

The split rationale is in the issue body: bundling into #93 would have pushed the foundation slice over the AC limit, and slicing the inverse out lets #94 start as soon as this primitive lands.

## Design

### Interface change

Add a single method to `ConversationRepository`, placed immediately below `archive` (line 31) to make the inverse-pairing obvious:

```kotlin
suspend fun unarchive(conversationId: String)
```

Signature mirrors `archive` exactly: raw `String` ID, `suspend`, no return value, no extra parameters. No KDoc beyond what the file header already establishes (mutating one-shot; affected streams re-emit).

### Fake impl

Add `FakeConversationRepository.unarchive(conversationId)` next to `archive()` (line 125). The body is the exact inverse of `archive()`:

- Atomic `state.update { records -> ... }`
- Unknown ID → `throw unknown(conversationId)` (reuse the existing helper at line 203)
- Set `conversation.archived = false` via `copy(conversation = record.conversation.copy(archived = false))`
- Behavior on an already-unarchived conversation: **silent success / idempotent**. The `copy(archived = false)` on a record whose flag is already `false` produces an equal `Conversation` value. The `StateFlow` deduplicates equal emissions, so the Archived/Discussions/Channels streams do not re-emit unnecessarily. This matches `archive()`'s established idempotent-no-precondition pattern (the existing `archive_isIdempotent` test, FakeConversationRepositoryTest.kt:274, confirms this is the established convention for this layer).

Decision rationale for idempotence over explicit precondition: the issue body explicitly delegates this choice to the architect. `archive()` chose silent idempotence and has a test that locks it in; choosing the same for `unarchive()` keeps the pair symmetric and avoids a precondition check that would have to be re-justified later if/when the remote Phase 4 implementation arrives (REST `DELETE`-style unarchive endpoints are conventionally idempotent).

The whole method is ~6 lines, structurally identical to `archive()`:

```kotlin
override suspend fun unarchive(conversationId: String) {
    state.update { records ->
        val record = records[conversationId] ?: throw unknown(conversationId)
        records + (
            conversationId to
                record.copy(conversation = record.conversation.copy(archived = false))
        )
    }
}
```

That snippet is the full body — included here because it IS the contract (mirroring an existing 8-line function), not because it pre-writes complex logic.

### State + concurrency model

Identical to `archive()`. `MutableStateFlow<Map<String, ConversationRecord>>.update { }` is atomic; observers re-emit on every non-equal change. No new flows, no dispatcher concerns, no scope-management. The conversation is a `suspend fun` only because it conforms to the interface — there is no actual suspension point in the Fake.

### Error handling

- Unknown `conversationId` → `IllegalArgumentException("Unknown conversation: <id>")` via the existing `unknown()` helper. Same as `archive()`.
- Conversation exists but is not archived → no-op (silent success). See idempotence rationale above.
- No other failure modes for the Fake impl.

### Testing strategy

Unit tests only (no instrumented tests — `./gradlew test`). Three new test cases in `FakeConversationRepositoryTest`, mirroring the `archive_*` cluster (lines 209-286):

1. **`unarchive_movesConversation_from_Archived_to_Discussions_andRetainsInStore`** — the AC test.
   - Use the existing `seed-discussion-archived` seed (no need to call `repo.archive()` first).
   - Pre-assert: appears in `Archived`, not in `Discussions`.
   - Call `repo.unarchive("seed-discussion-archived")`.
   - Assert: leaves `Archived`, appears in `Discussions`, still in `All`, still not in `Channels` (it's a discussion, not a promoted channel).

2. **`unarchive_onUnknownId_throws`** — mirrors `archive_onUnknownId_throws` (line 263). `repo.unarchive("nope")` inside `runBlocking { }` must throw `IllegalArgumentException`.

3. **`unarchive_isIdempotent`** — mirrors `archive_isIdempotent` (line 274). Calling `unarchive` twice on the same already-unarchived conversation must leave it in `Discussions` exactly once (i.e. the second call is a silent no-op and does not duplicate).

These are bullet-pointed scenarios, not test bodies — the developer writes the test code in the project's testing idiom (JUnit4 + `runBlocking { }` + `assertTrue`/`assertEquals` from `org.junit.Assert.*`, matching the existing file).

## Open questions

None. The issue body explicitly delegated the no-op-vs-precondition decision to the architect; that is resolved above (silent idempotent, matching `archive()`).
