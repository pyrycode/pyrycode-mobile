# Spec — Seed `FakeConversationRepository` with 3 channels (#5)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — full file. This is the only production file we touch. Pay special attention to:
  - Lines 21 — the `state` initializer (currently `emptyMap()`); seeds go here.
  - Lines 23-34 — `observeConversations`; the `sortedByDescending { it.lastUsedAt }` line goes inside the existing `state.map { ... }` block.
  - Lines 151-154 — the `private data class ConversationRecord(...)` shape that seeds must conform to.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — constructor shape. Note `name: String?` (channels have non-null name), `cwd: String`, `currentSessionId: String`, `sessionHistory: List<String>`, `isPromoted: Boolean`, `lastUsedAt: Instant`.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` — `Session(id, conversationId, claudeSessionUuid, startedAt, endedAt)`. Each seeded channel needs a fully-formed `Session` in its `ConversationRecord.sessions` map, with `endedAt = null` (active).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — confirms `observeConversations` contract: emits current value on subscription + every change. The `sortedByDescending` projection stays inside the `Flow.map`, so every subsequent emission is also sorted.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` — full file. **Six existing tests need scope-narrowing** because they currently assert against the full emission (which used to be empty); enumerated under "Test updates" below.
- `docs/specs/architecture/4-fake-conversation-repository.md` — the immediately-preceding spec that established the storage shape and JUnit 4 / `runBlocking` / `Flow.first()` test idiom. Same conventions apply here.
- `CLAUDE.md` (repo root) — "Conversations model" section. Channels are promoted (`isPromoted = true`), user-named, persistent.
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — confirm `kotlinx-datetime` is already on the main classpath (it is — `FakeConversationRepository` already uses `Clock.System.now()`). **No new dependencies.**

## Context

Phase 0 UI work needs fixture data to render against. `FakeConversationRepository` currently starts empty. This ticket seeds three channels into its initial `state` so composables building the channel list (#7+ and successors) have something realistic to display until the real backend lands in Phase 4.

The parallel ticket #6 will add unpromoted discussion seeds to the same `state` initializer. This ticket's seeds are channels only — leave the discussions side as-is so #6's diff is purely additive.

## Design

### File layout — 1 production file, 1 test file

| File | Change |
|------|--------|
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | Replace `emptyMap()` with a `buildSeedState()` companion call. Add `.sortedByDescending { it.lastUsedAt }` inside `observeConversations`. |
| `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` | Replace one existing test, scope-narrow six others, add one new test for the seed + ordering AC. |

No new files. No new exported types.

### Seed data — fixed, deterministic, three channels

Seeds must be **deterministic**: identical values across runs, identical `id`s, identical `lastUsedAt`s. This rules out `UUID.randomUUID()` and `Clock.System.now()` for seeded entries. Use:

- **Stable string `id`s** with a `seed-` prefix so they're obviously fixture data when seen in logs (`seed-channel-pyrycode-mobile`, `seed-channel-joi-pilates`, `seed-channel-personal`). The `id` type is plain `String`, not `UUID`, so stable human-readable ids are valid and easier to debug than fixed UUID literals.
- **Fixed `Instant.parse(...)` literals** for `lastUsedAt` and `startedAt`. Three distinct timestamps, ordered so the descending sort produces a non-trivial reordering at construction time (i.e. don't put them in the same order as the seed entries in source — that way the test verifies sorting actually happens, not just incidental insertion order).

The three seeds:

| Seed | name | cwd | currentSessionId | lastUsedAt |
|------|------|-----|------------------|------------|
| 1 | `"Pyrycode Mobile"` | `"~/Workspace/pyrycode-mobile"` | `"seed-session-pyrycode-mobile"` | `2026-05-10T09:00:00Z` |
| 2 | `"Joi Pilates"` | `"~/Workspace/joi-pilates"` | `"seed-session-joi-pilates"` | `2026-05-08T15:30:00Z` |
| 3 | `"Personal"` | `"~/Workspace/personal"` | `"seed-session-personal"` | `2026-05-05T20:15:00Z` |

`claudeSessionUuid` values are also stable strings: `"seed-claude-pyrycode-mobile"`, `"seed-claude-joi-pilates"`, `"seed-claude-personal"`. These are placeholders — the real Claude session UUID flows from the CLI in Phase 4.

`isPromoted = true` for all three. `name` is the non-null table value. `sessionHistory = listOf(currentSessionId)` (single session each, like a freshly-created conversation). `Session.endedAt = null` (sessions are active).

**Construction order in source ≠ sorted order.** Insert seeds into the source-level seed list in a deliberately non-sorted order (e.g. Personal first, then Pyrycode Mobile, then Joi Pilates) so the AC test that verifies descending order can't pass by accident.

### Sort projection

Add `.sortedByDescending { it.lastUsedAt }` to `observeConversations`:

```kotlin
override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
    state.map { records ->
        records.values
            .map { it.conversation }
            .filter { conv ->
                when (filter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Channels -> conv.isPromoted
                    ConversationFilter.Discussions -> !conv.isPromoted
                }
            }
            .sortedByDescending { it.lastUsedAt }
    }
```

The sort applies uniformly to all three filter values. This is the desired behaviour for the UI in every case — the channel list and the discussions drilldown both want most-recently-used first. #6 inherits this for free; its added discussions also sort by `lastUsedAt` desc. **Do not** make the sort filter-conditional.

### Seed builder

Keep the construction private and inline at the top of the class. Use a `companion object` so the seeds are evaluated once at class load, not once per instance (instances are short-lived in tests, but a `companion object` documents intent: this is fixture data, not per-instance state).

```kotlin
class FakeConversationRepository : ConversationRepository {

    private val state = MutableStateFlow<Map<String, ConversationRecord>>(SEED_RECORDS)

    // ... rest unchanged except the .sortedByDescending line above ...

    companion object {
        private val SEED_RECORDS: Map<String, ConversationRecord> = buildSeedRecords()

        private fun buildSeedRecords(): Map<String, ConversationRecord> {
            // Intentionally not in lastUsedAt order — exercises the sort projection.
            val seeds = listOf(
                seedChannel(
                    id = "seed-channel-personal",
                    name = "Personal",
                    cwd = "~/Workspace/personal",
                    sessionId = "seed-session-personal",
                    claudeSessionUuid = "seed-claude-personal",
                    lastUsedAt = Instant.parse("2026-05-05T20:15:00Z"),
                ),
                seedChannel(
                    id = "seed-channel-pyrycode-mobile",
                    name = "Pyrycode Mobile",
                    cwd = "~/Workspace/pyrycode-mobile",
                    sessionId = "seed-session-pyrycode-mobile",
                    claudeSessionUuid = "seed-claude-pyrycode-mobile",
                    lastUsedAt = Instant.parse("2026-05-10T09:00:00Z"),
                ),
                seedChannel(
                    id = "seed-channel-joi-pilates",
                    name = "Joi Pilates",
                    cwd = "~/Workspace/joi-pilates",
                    sessionId = "seed-session-joi-pilates",
                    claudeSessionUuid = "seed-claude-joi-pilates",
                    lastUsedAt = Instant.parse("2026-05-08T15:30:00Z"),
                ),
            )
            return seeds.associateBy { it.conversation.id }
        }

        private fun seedChannel(
            id: String,
            name: String,
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
                name = name,
                cwd = cwd,
                currentSessionId = sessionId,
                sessionHistory = listOf(sessionId),
                isPromoted = true,
                lastUsedAt = lastUsedAt,
            )
            return ConversationRecord(conversation, mapOf(sessionId to session))
        }
    }
}
```

**Visibility of `ConversationRecord` from the companion:** `ConversationRecord` is `private` to `FakeConversationRepository`. Kotlin's companion object can see the enclosing class's `private` nested types — no visibility change needed.

**Import addition required:** `import kotlinx.datetime.Instant` at the top of the file (alongside the existing `import kotlinx.datetime.Clock`).

### What `#6` will need from this design (not your problem, but informs choices)

#6 will add three discussion seeds in the same `buildSeedRecords()` builder, with `isPromoted = false`, `name = null`. The split is clean — your three channel seeds and #6's three discussion seeds are independent entries in the same `seeds` list. The merge conflict at integration time, if any, is a small additive one (both PRs append to the same `seeds` list). Order the discussion-seed additions after the channel block when #6 lands; nothing in this ticket needs to reserve space.

## State + concurrency model

Unchanged from #4. The `state` initializer now holds three records instead of zero; `MutableStateFlow.update {}` semantics, `Flow.map` derivation, and `viewModelScope` collection all behave identically. No new concurrency primitives.

## Error handling

Unchanged. Seeds are static — no IO, no parse failures (the `Instant.parse(...)` calls run on stable literals at class load; they cannot fail unless the strings are mistyped, which the build will catch). No new failure modes.

## Testing strategy

`./gradlew test`. JUnit 4 + `runBlocking` + `Flow.first()`. No new test dependencies.

### New test — the AC test

```kotlin
@Test
fun observeConversations_Channels_emitsThreeSeededChannels_orderedByLastUsedAtDescending() = runBlocking {
    val repo = FakeConversationRepository()
    val channels = repo.observeConversations(ConversationFilter.Channels).first()

    assertEquals(3, channels.size)
    assertEquals(
        listOf("Pyrycode Mobile", "Joi Pilates", "Personal"),
        channels.map { it.name },
    )
    // Distinct cwd per channel
    assertEquals(3, channels.map { it.cwd }.toSet().size)
    // Distinct, descending lastUsedAt
    val timestamps = channels.map { it.lastUsedAt }
    assertEquals(timestamps.sortedDescending(), timestamps)
    assertEquals(3, timestamps.toSet().size)
    // Every channel has a stable, non-blank currentSessionId
    assertTrue(channels.all { it.currentSessionId.isNotBlank() })
    // Sanity: all three are promoted
    assertTrue(channels.all { it.isPromoted })
}
```

This single test covers all five AC bullets except the Discussions-emits-empty one (covered in the rewritten `observeConversations_emitsExpectedSeeds_initially_for_all_filters` below).

### Existing test — rewrite

`observeConversations_emitsEmpty_initially_for_all_filters` no longer makes sense. Replace it with a single test that pins down the new initial counts:

```kotlin
@Test
fun observeConversations_emitsExpectedSeeds_initially_for_all_filters() = runBlocking {
    val repo = FakeConversationRepository()
    assertEquals(3, repo.observeConversations(ConversationFilter.All).first().size)
    assertEquals(3, repo.observeConversations(ConversationFilter.Channels).first().size)
    assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.Discussions).first())
}
```

The `Discussions` empty-list assertion is what locks in the AC bullet "Discussions emits empty" and what #6 will flip when it lands.

### Existing tests — scope-narrowing

Six tests currently assert against the full emission of `observeConversations(All)` (e.g. `assertEquals(listOf(created), all)`, `.single()`, `assertEquals(emptyList(), …)` after archive). They were correct when the initial state was empty; with seeds, they must filter or scope to the conversation under test.

**Pattern:** wherever a test holds a `created: Conversation` reference, replace `repo.observeConversations(All).first()` assertions with `… .first { it.id == created.id }` or `… .filter { it.id == created.id }`. For "after archive, the conversation is gone" tests, change to "after archive, no emission contains `created.id`."

| # | Test | Current shape | Fix |
|---|------|---------------|-----|
| 1 | `createDiscussion_appearsIn_observeConversations_All` | `assertEquals(listOf(created), all)` | `assertTrue(all.any { it.id == created.id })`; optionally also `assertEquals(4, all.size)` to assert "seeds plus new one" |
| 2 | `createDiscussion_appearsIn_Discussions_filter_butNotIn_Channels` | `Discussions size == 1`, `Channels size == 0` | `Discussions size == 1`; `Channels size == 3`; `Channels.none { it.id == created.id }` |
| 3 | `archive_removes_from_observeConversations` | `assertEquals(emptyList(), …)` | `assertTrue(repo.observeConversations(All).first().none { it.id == created.id })` |
| 4 | `rename_updates_name_and_reEmits` | `assertEquals(listOf(renamed), all)` | `val found = repo.observeConversations(All).first().first { it.id == created.id }`; `assertEquals("renamed", found.name)` |
| 5 | `changeWorkspace_returnsFreshSession_andUpdatesCwd` | `.single()` to fetch the conversation | `.first { it.id == created.id }` |
| 6 | `promote_movesConversation_from_Discussions_to_Channels` | `Discussions == 0`, `Channels == 1` | `Discussions == 0`; `Channels.any { it.id == created.id }` (Channels size is now 4) |

The remaining tests (`observeMessages_emitsEmpty`, `createDiscussion_isUnpromoted_andHasNullName`, `promote_flipsIsPromoted_andApplies_name_and_workspace`, `startNewSession_returnsFreshSession_withDifferentId`, `promote_onUnknownId_throws`) inspect the returned value or behaviour without touching the full emission — leave them as-is.

### What's not covered (out of scope)

- Seeded `currentSessionId` is in `sessionHistory` — implicit in the construction shape; not separately asserted.
- Seeded `Session` objects in `ConversationRecord.sessions` — internal storage detail, not visible via the public interface. The `observeConversations` emissions are what consumers see.
- Discussion seeds — owned by #6.
- Message stream seeds — owned by #9/#10.

## Out of scope

- Discussion seeds (#6).
- Message-stream seeds and `SessionBoundary` interleaving (#9, #10).
- DI wiring (#11 or first ViewModel ticket).
- Persistence — pure in-memory; restart restores the same three seeds (deterministically, by design).
- Localizing the seed `name`s — these are display strings for fixture data, not user-facing copy that requires `strings.xml`.

## Open questions

None. Seed identities, timestamps, sort projection, and the six tests' scope-narrowing pattern are all pinned down.
