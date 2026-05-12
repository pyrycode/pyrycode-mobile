# Spec — Seed `FakeConversationRepository` with 2 discussions (#6)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — full file. The only production file we touch. Pay attention to:
  - Lines 158-218 — the existing `companion object` with `SEED_RECORDS`, `buildSeedRecords()`, and `seedChannel(...)` from #5. We extend the same `seeds` list and add a sibling `seedDiscussion(...)` helper.
  - Lines 153-156 — `private data class ConversationRecord(...)` shape (unchanged).
  - Lines 24-36 — `observeConversations` already projects `.sortedByDescending { it.lastUsedAt }` (added by #5); applies to Discussions for free.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — constructor. `name: String?` is nullable (discussion seeds set it to `null`), `cwd: String` is non-null (discussions share a scratch sentinel string), `isPromoted: Boolean` is `false` for discussions.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` — `Session(id, conversationId, claudeSessionUuid, startedAt, endedAt)`. Each seeded discussion gets a fully-formed `Session` with `endedAt = null`.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` — full file. **Four existing tests need count bumps** because their assertions hard-code current seed counts (3 channels, 0 discussions); after this ticket those become (3 channels, 2 discussions). Enumerated under "Test updates" below.
- `docs/specs/architecture/5-fake-conversation-repository-channel-seeds.md` — the immediately-preceding ticket's spec. Same shape, same conventions; this ticket is its discussion-tier mirror.
- `CLAUDE.md` (repo root) — "Conversations model" section. Discussions are unpromoted (`isPromoted = false`), auto-named (`name = null`, the UI renders a synthetic name at display time), share scratch cwd by definition.
- `gradle/libs.versions.toml` — confirm `kotlinx-datetime` is on the classpath (it is — `FakeConversationRepository` already imports `Instant`). **No new dependencies.**

## Context

Phase 0 UI needs fixture data for the unpromoted-conversation tier so the "Recent discussions" pill (#12+) renders against real records without each call site having to invoke `createDiscussion` in setup. #5 already seeded three channels into `FakeConversationRepository`'s initial `state`; this ticket adds two discussions to the same seed list.

The change is purely additive on the production side: two entries appended to the existing `seeds` list in `buildSeedRecords()`, plus one new `seedDiscussion(...)` helper alongside `seedChannel(...)`. The channels block is not touched. Test changes are count bumps on assertions that hard-code the old (3, 0) seed counts.

## Design

### File layout — 1 production file, 1 test file

| File | Change |
|------|--------|
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | Append two `seedDiscussion(...)` calls to the existing `seeds` list. Add a private `seedDiscussion(...)` helper in the companion. |
| `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` | Bump four existing count assertions. Add one new test for the discussion-seed AC. |

No new files. No new exported types. No new dependencies.

### Seed data — fixed, deterministic, two discussions

Same determinism contract as the channel seeds: stable string `id`s with a `seed-` prefix, fixed `Instant.parse(...)` literals for `lastUsedAt` and `startedAt`. No `UUID.randomUUID()`, no `Clock.System.now()` for seeded entries.

| Seed | name | cwd | currentSessionId | claudeSessionUuid | lastUsedAt |
|------|------|-----|------------------|-------------------|------------|
| A | `null` | `"~/.pyrycode/scratch"` | `"seed-session-discussion-a"` | `"seed-claude-discussion-a"` | `2026-05-11T14:00:00Z` |
| B | `null` | `"~/.pyrycode/scratch"` | `"seed-session-discussion-b"` | `"seed-claude-discussion-b"` | `2026-05-09T08:00:00Z` |

Conversation ids: `"seed-discussion-a"`, `"seed-discussion-b"`.

`isPromoted = false` for both. `name = null` for both — per CLAUDE.md's conversations model, discussions are auto-named at render time, so the data-layer name is genuinely `null` (not an empty string).

**Shared scratch cwd.** Both discussions point at the same `"~/.pyrycode/scratch"` sentinel. This matches the conversations-model contract ("discussions share scratch by definition"). The literal string is a fixture marker; it does not need to correspond to a real filesystem path because nothing in Phase 0 actually opens it.

**Stable, distinct timestamps.** A is newer than B. Both are older than the most-recent channel (`Pyrycode Mobile`, `2026-05-10T09:00:00Z`) — that's intentional but not load-bearing; nothing asserts a cross-tier order because Discussions and Channels are queried via separate filters.

**Construction order in source ≠ sorted order.** Insert B first, then A, in the `seeds` list. A's `lastUsedAt` is newer, so `sortedByDescending { lastUsedAt }` reverses them — proving the projection is doing work, not relying on incidental insertion order. Same pattern as #5.

### Seed builder — extend, don't rewrite

`buildSeedRecords()` already exists and assembles its result via `seeds.associateBy { it.conversation.id }`. The cleanest change is to append two `seedDiscussion(...)` entries to the existing `seeds` list, after the three `seedChannel(...)` entries:

```kotlin
private fun buildSeedRecords(): Map<String, ConversationRecord> {
    val seeds = listOf(
        // ... existing three seedChannel(...) entries (unchanged) ...
        seedDiscussion(
            id = "seed-discussion-b",
            cwd = "~/.pyrycode/scratch",
            sessionId = "seed-session-discussion-b",
            claudeSessionUuid = "seed-claude-discussion-b",
            lastUsedAt = Instant.parse("2026-05-09T08:00:00Z"),
        ),
        seedDiscussion(
            id = "seed-discussion-a",
            cwd = "~/.pyrycode/scratch",
            sessionId = "seed-session-discussion-a",
            claudeSessionUuid = "seed-claude-discussion-a",
            lastUsedAt = Instant.parse("2026-05-11T14:00:00Z"),
        ),
    )
    return seeds.associateBy { it.conversation.id }
}
```

The new helper sits alongside `seedChannel(...)` in the companion. It differs from `seedChannel` in three places: no `name` parameter (always `null`), no implicit name in the docs ("discussions are auto-named"), and `isPromoted = false`:

```kotlin
private fun seedDiscussion(
    id: String,
    cwd: String,
    sessionId: String,
    claudeSessionUuid: String,
    lastUsedAt: Instant,
): ConversationRecord {
    val session = Session(
        id = sessionId,
        conversationId = id,
        claudeSessionUuid = claudeSessionUuid,
        startedAt = lastUsedAt,
        endedAt = null,
    )
    val conversation = Conversation(
        id = id,
        name = null,
        cwd = cwd,
        currentSessionId = sessionId,
        sessionHistory = listOf(sessionId),
        isPromoted = false,
        lastUsedAt = lastUsedAt,
    )
    return ConversationRecord(conversation, mapOf(sessionId to session))
}
```

**Why a separate helper instead of parameterising `seedChannel` with `name: String?` and `isPromoted: Boolean`.** Two tiers, two helpers — keeps each call site readable (the `seedChannel`/`seedDiscussion` verb at the call site is the documentation). Combining them into a `seedRecord(name: String?, isPromoted: Boolean, ...)` would save ~15 lines but cost call-site clarity and reintroduce the `(null, false)`/`(non-null, true)` correlation as a runtime concern. Pyrycode's bias is the readable call site, especially in fixture code.

### Sort projection

Already done by #5 — `observeConversations` projects `.sortedByDescending { it.lastUsedAt }` uniformly across all filters. The Discussions filter inherits this for free. No change needed.

## State + concurrency model

Unchanged. `state` initializer now holds five records (three channels + two discussions) instead of three. `MutableStateFlow.update {}` semantics, `Flow.map` derivation, and downstream collection all behave identically.

## Error handling

Unchanged. Seeds are static `Instant.parse(...)` literals; the build catches typos at compile-time-equivalent (class-load). No new failure modes.

## Testing strategy

`./gradlew test`. JUnit 4 + `runBlocking` + `Flow.first()`. No new test dependencies.

### New test — the AC test

```kotlin
@Test
fun observeConversations_Discussions_emitsTwoSeededDiscussions_orderedByLastUsedAtDescending() = runBlocking {
    val repo = FakeConversationRepository()
    val discussions = repo.observeConversations(ConversationFilter.Discussions).first()

    assertEquals(2, discussions.size)
    // Discussions are auto-named at render time; data-layer name is null.
    assertTrue(discussions.all { it.name == null })
    // Discussions are unpromoted by definition.
    assertTrue(discussions.all { !it.isPromoted })
    // Shared scratch cwd by definition.
    assertEquals(setOf("~/.pyrycode/scratch"), discussions.map { it.cwd }.toSet())
    // Every discussion has a stable, non-blank currentSessionId.
    assertTrue(discussions.all { it.currentSessionId.isNotBlank() })
    // Distinct currentSessionIds across the two seeds.
    assertEquals(2, discussions.map { it.currentSessionId }.toSet().size)
    // Distinct, descending lastUsedAt.
    val timestamps = discussions.map { it.lastUsedAt }
    assertEquals(timestamps.sortedDescending(), timestamps)
    assertEquals(2, timestamps.toSet().size)
}
```

This test pins down the four AC bullets that concern the Discussions tier: two seeds, both `isPromoted = false`, both `name = null`, distinct `lastUsedAt`s, present in the `Discussions` filter emission on first collection.

**Channels-unaffected AC.** The fifth AC bullet ("Channels filter is unaffected") is locked in by the existing `observeConversations_Channels_emitsThreeSeededChannels_orderedByLastUsedAtDescending` test from #5 (lines 114-130 of the test file) — it asserts exactly three channels with specific names. If this ticket's changes accidentally added a channel or removed one, that test would fail. No new assertion needed.

### Existing tests — count bumps

Four tests hard-code the current `(3 channels, 0 discussions)` seed counts. Bump them to `(3 channels, 2 discussions)`:

| # | Test | Line(s) | Current | New |
|---|------|---------|---------|-----|
| 1 | `observeConversations_emitsExpectedSeeds_initially_for_all_filters` | 16-18 | All=3; Channels=3; Discussions=`emptyList()` | All=5; Channels=3; **Discussions=2** (replace the `assertEquals(emptyList<Any>(), …)` with `assertEquals(2, repo.observeConversations(ConversationFilter.Discussions).first().size)`) |
| 2 | `createDiscussion_appearsIn_observeConversations_All` | 32 | `assertEquals(4, all.size)` | `assertEquals(6, all.size)` |
| 3 | `createDiscussion_appearsIn_Discussions_filter_butNotIn_Channels` | 48 | `assertEquals(1, … Discussions … .size)` | `assertEquals(3, … Discussions … .size)` |
| 4 | `promote_movesConversation_from_Discussions_to_Channels` | 69 | `assertEquals(0, … Discussions … .size)` | `assertEquals(2, … Discussions … .size)` (the two seeded discussions remain after the one created-and-promoted leaves the tier) |

Note that test #4's Channels assertion (`assertEquals(4, channels.size)` at line 71) stays at 4: three seeded channels plus one newly-promoted discussion. The arithmetic happens to be unchanged from #5 because this ticket doesn't add or remove channels.

The remaining tests (`observeMessages_emitsEmpty`, `createDiscussion_isUnpromoted_andHasNullName`, `promote_flipsIsPromoted_andApplies_name_and_workspace`, `archive_removes_from_observeConversations`, `rename_updates_name_and_reEmits`, `startNewSession_returnsFreshSession_withDifferentId`, `changeWorkspace_returnsFreshSession_andUpdatesCwd`, `observeConversations_Channels_emitsThreeSeededChannels_orderedByLastUsedAtDescending`, `promote_onUnknownId_throws`) either scope their assertions to a specific `created.id` or assert tier counts that this ticket doesn't change. Leave them as-is.

### What's not covered (out of scope)

- Discussion seed `Session` objects in `ConversationRecord.sessions` — internal storage, not visible via the public interface. The `observeConversations` emissions are what consumers see.
- Auto-naming of discussions at render time — owned by the UI ticket that renders the Recent-discussions pill (#12+), not by the data layer.
- Cross-tier ordering (e.g. interleaving Channels and Discussions in an `All` query). The `All` filter currently sorts by `lastUsedAt` desc across both tiers; no AC asserts a specific cross-tier ordering and no UI surfaces this view in Phase 0.
- Message-stream seeds for discussions — owned by #9/#10.

## Out of scope

- Channels seed adjustments (#5 owns this).
- DI wiring (#11 or first ViewModel ticket).
- Persistence — pure in-memory; restart restores the same five seeds (deterministically, by design).
- Real scratch-directory path resolution. `"~/.pyrycode/scratch"` is a fixture sentinel, not a path the app reads.

## Open questions

None. Seed identities, timestamps, the shared scratch sentinel, the helper-vs-parameterise choice, and the four count bumps are all pinned down.
