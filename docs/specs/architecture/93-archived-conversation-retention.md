# Spec: Retain conversations on archive() + expose archived filter + seed (#93)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:1-21` — current data class; you'll add one field.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-49` — interface + `ConversationFilter` enum; you'll add one enum case.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-45` — filter `when` block; needs a new branch and an `!archived` predicate added to existing branches.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:124-129` — `archive()` body; flip from removal to flag mutation.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:308-322,419-445` — existing `seedDiscussion(...)` helper + seed list site; add the archived seed here.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:17-23,155-162,208-214` — seed-count assertions to bump, the `archive_removes_from_observeConversations` test to rewrite, the `createDiscussion_appearsIn_observeConversations_All` assertion to bump.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:341-379` — `stubRepo` does `when(filter) { ... }` over the enum; add a new branch so it stays exhaustive.

Codegraph confirms `ConversationFilter` has only those two consumer sites that switch on the enum exhaustively (`FakeConversationRepository.observeConversations` and `ChannelListViewModelTest.stubRepo`). `DiscussionListViewModelTest`'s stubs return `source` regardless of filter and don't need updates. No other production code switches on `ConversationFilter`.

## Context

Ticket #77 (Archived Discussions UI) is split into a data-layer slice (this ticket) and a UI slice. Today `FakeConversationRepository.archive(id)` deletes the conversation from the in-memory store, so the upcoming UI has nothing observable to render an "Archived" list against. This ticket converts archive into flag-based retention and exposes the archived stream via the existing filter enum so the UI slice can subscribe without further data-layer work.

The sibling `unarchive(id)` operation is intentionally out of scope and lands in a follow-up.

## Design

### Model change

`Conversation` gains a single boolean flag with a `false` default:

```kotlin
data class Conversation(
    // ...existing fields...
    val isSleeping: Boolean = false,
    val archived: Boolean = false,
)
```

The default keeps every existing construction site (named-arg `Conversation(...)` calls in `createDiscussion`, `seedChannel`, `seedDiscussion`, and `.copy(...)` calls in `promote`/`rename`/`mintNewSession`) source-compatible — no other call sites need to change.

### Filter shape — new enum variant

The AC leaves the shape open. We extend the existing enum:

```kotlin
enum class ConversationFilter { All, Channels, Discussions, Archived }
```

Rationale: `ConversationFilter` is already the single typed handle for "which slice of the conversation store"; adding a parallel `observeArchived...()` method would fragment the interface for no gain. The new variant follows the same shape consumers already wire through Koin / ViewModels.

### Filter semantics matrix

After the change, `observeConversations(filter)` in `FakeConversationRepository` applies:

| Filter        | Predicate                          | Seed count after change |
|---------------|------------------------------------|-------------------------|
| `All`         | (no predicate — everything)        | 6                       |
| `Channels`    | `isPromoted && !archived`          | 3                       |
| `Discussions` | `!isPromoted && !archived`         | 2                       |
| `Archived`    | `archived`                         | 1                       |

`All` keeps its literal "everything in the store" meaning — the PO's note about bumping seed-count assertions presumes this. `Channels` gets `!archived` by symmetry with `Discussions`, so an archived channel (if one ever existed) would not regress the channel list. `Archived` returns archived items regardless of `isPromoted`; narrowing further is a UI-side concern.

### `archive(conversationId)` semantics

Replace the existing removal with a flag flip via `state.update { ... }`:

- If `conversationId !in records` → throw `IllegalArgumentException` (existing `unknown(id)` helper; preserves prior behaviour).
- Otherwise, replace the entry with `record.copy(conversation = record.conversation.copy(archived = true))`.
- Idempotent: archiving an already-archived conversation is a no-op (the copy is harmless and `StateFlow.update` won't re-emit if the value is equal).
- Do **not** mutate `lastUsedAt`. Archiving is a lifecycle transition, not usage.
- Do **not** touch `isPromoted`. Archiving a channel (not exercised by this ticket) would leave it promoted-and-archived; the filter matrix routes it correctly.

The function remains `suspend` with `Unit` return — surface unchanged.

### Seed data

Add one archived discussion to `buildSeedRecords()` alongside the existing two unarchived ones, using a new sibling helper `seedArchivedDiscussion(...)` *or* a new boolean parameter on `seedDiscussion(...)` — developer's call; pick whichever keeps the seed list readable. Proposed seed:

- `id = "seed-discussion-archived"`
- `cwd = DEFAULT_SCRATCH_CWD`
- `sessionId = "seed-session-discussion-archived"`
- `claudeSessionUuid = "seed-claude-discussion-archived"`
- `lastUsedAt = Instant.parse("2026-04-15T12:00:00Z")` (deliberately older than the two live discussions — natural sort order for an archive view)
- `isPromoted = false`, `archived = true`
- No messages (matches the existing discussion seeds).

### State + concurrency model

No changes. Single `MutableStateFlow<Map<String, ConversationRecord>>` remains the source of truth. `archive()` mutates via `state.update { ... }`; observers re-emit on the next collection because the map identity changes. No new dispatchers, scopes, or hot/cold conversions.

### Error handling

Unchanged. `archive()` still throws `IllegalArgumentException` for unknown ids via the existing `unknown(id)` helper. No new failure modes introduced.

## Testing strategy

All unit tests; no instrumented coverage needed (pure data-layer). `./gradlew test`.

### Updates to existing tests in `FakeConversationRepositoryTest`

- `observeConversations_emitsExpectedSeeds_initially_for_all_filters` — bump `All` expectation from `5` to `6`; add an assertion that `Archived` returns `1`. `Channels` (3) and `Discussions` (2) assertions stay.
- `createDiscussion_appearsIn_observeConversations_All` — bump expected size from `6` to `7`.
- `archive_removes_from_observeConversations` — rewrite per AC. Rename to something like `archive_movesConversation_from_Discussions_to_Archived_andRetainsInStore`. Assertions:
  - Before archive: created discussion is in `Discussions`, not in `Archived`.
  - After archive: created discussion is NOT in `Discussions`, NOT in `Channels`, IS in `Archived`, and still IS in `All`.
- `seededDiscussions_remainEmpty` — extend the id list to also include `seed-discussion-archived` (the new archived seed should likewise have no messages).
- `observeConversations_Discussions_emitsTwoSeededDiscussions_orderedByLastUsedAtDescending` — should continue to pass unchanged (Discussions count stays 2 because the new seed is filtered out by `!archived`). Verify by running, don't pre-emptively edit.

### New tests in `FakeConversationRepositoryTest`

Cover behaviour the AC calls out explicitly:

- The seeded archived discussion appears in the `Archived` stream.
- The seeded archived discussion does NOT appear in the `Discussions` stream.
- The seeded archived discussion DOES appear in the `All` stream (proves retention, not deletion).
- `archive()` on an unknown id throws `IllegalArgumentException` (preserves prior contract — add this if not already covered; today only `promote_onUnknownId_throws` exists).
- `archive()` is idempotent: calling twice succeeds and the conversation remains archived (single membership in the `Archived` stream — not duplicated).

### Updates to `ChannelListViewModelTest`

- `stubRepo` (~line 346) `when (filter) { ... }` — add `ConversationFilter.Archived -> TODO("not used")` so the `when` stays exhaustive. No semantic change.

`DiscussionListViewModelTest` does not need updates (its stubs don't switch on the enum).

## Open questions

None — AC is explicit and the filter-shape decision is made above. Developer can proceed straight to red→green.

## Out of scope

- `unarchive(conversationId)` — sibling ticket.
- The Archived Discussions UI itself — split from #77, separate ticket.
- A `ConversationId` value type — Technical Notes explicitly forbid introducing it here.
- Any change to channel-list / discussion-list UI behaviour. Only the `ConversationFilter` enum gains a case; existing UI ViewModels keep requesting `Channels` / `Discussions` unchanged.
