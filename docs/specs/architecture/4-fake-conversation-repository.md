# Spec — FakeConversationRepository (empty-state scaffold) (#4)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — full file. The interface this class implements, plus the supporting types (`ConversationFilter`, `ThreadItem`, `BoundaryReason`). All method signatures here must be matched exactly.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — full file. Constructor shape for the value the mutators return.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` — full file. `startNewSession` and `changeWorkspace` return this. `endedAt: Instant?` is `null` while the session is active.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — skim. Not used directly in this ticket (no real message storage yet — see #9), but kept for orientation when reading `ThreadItem`.
- `app/src/test/java/de/pyryco/mobile/data/model/ConversationTest.kt` — full file. Mirrors the JUnit 4 style used in this repo: `org.junit.Test`, `Assert.assertEquals`, a private `sample()` helper, snake_case method names. The new test file follows this idiom.
- `app/build.gradle.kts` — confirm `libs.kotlinx.coroutines.core` and `libs.kotlinx.datetime` are already on the main classpath, and only `libs.junit` is on the test classpath. **No new dependencies are added by this ticket** — tests use `runBlocking` (from `kotlinx-coroutines-core`) and `Flow.first()`; we do not add `kotlinx-coroutines-test`.
- `CLAUDE.md` (repo root) — "Conversations model" section. Workspace change is a session-boundary reason; `changeWorkspace` therefore mints a new `Session` (returns it; the boundary marker emission itself is #9's problem, not ours).
- `docs/specs/architecture/3-conversation-repository-interface.md` — the spec that produced the interface this ticket implements. Same conventions (one file per cohesive unit, JUnit 4, kotlinx-datetime), and explicit handoff: "the next ticket will satisfy it for Phase 1."

## Context

This is the in-memory implementation of `ConversationRepository` that ViewModels bind to throughout Phase 1. It returns empty state on first observation; seed data and message histories land in follow-up tickets (#5, #6, #9, #10). The Ktor-backed remote implementation arrives in Phase 4 once pyrycode's mobile API ships, behind the same interface.

The ticket is **scaffold-only**: storage shape, mutator semantics, and emission semantics are nailed down here so consumers can rely on the contract; no hardcoded conversations, no synthetic boundary markers in the message stream.

## Design

### File layout — 2 files

| File | Contents |
|------|----------|
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | `class FakeConversationRepository : ConversationRepository`. Single state-holder, in-memory storage, all interface methods. |
| `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` | JUnit 4 behavioural tests. |

No DI wiring in this ticket — Koin modules don't exist yet; the binding lands with the first ViewModel that consumes it.

### Storage shape — single state-holder

```kotlin
private val state = MutableStateFlow<Map<String, ConversationRecord>>(emptyMap())
```

- **Why `MutableStateFlow<Map<...>>` and not `MutableStateFlow<List<...>>`:** mutators (`promote`, `archive`, `rename`, `startNewSession`, `changeWorkspace`) lookup by `conversationId`; a map gives O(1) lookups and atomic `update {}` semantics. Insertion order is not part of the contract at this stage (sort order is the ViewModel's responsibility; #5 will reintroduce that concern with seed data).
- **Why a `ConversationRecord` wrapper and not just `Conversation`:** we need to also track the conversation's active `Session` and any past sessions (each `Session` has its own `id`, `claudeSessionUuid`, `startedAt`, `endedAt`). `Conversation` only stores session **ids**, not full `Session` objects. The fake needs the full `Session` value so `startNewSession`/`changeWorkspace` can return it.

```kotlin
private data class ConversationRecord(
    val conversation: Conversation,
    val sessions: Map<String, Session>, // keyed by session id; includes current + history
)
```

- **Why atomic `update {}` and no `Mutex`:** `MutableStateFlow.update {}` performs an atomic compare-and-set on the value; concurrent callers retry safely. There are no multi-step invariants spanning two `update {}` calls in this class. A `Mutex` would add complexity without payoff.
- **Why `suspend` modifiers are kept even though bodies don't suspend:** the interface declares them `suspend`; we honor that. Phase 4's `RemoteConversationRepository` will actually suspend on Ktor calls — keeping the signatures identical avoids churn at consumers.

### Emission semantics

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
    }
```

- `Flow.map` on a `StateFlow` produces a cold flow that emits the current value on subscription and on every `state` change — this is what the interface's "Stream-shaped reads are cold Flows — collectors receive the current value on subscription and every subsequent change" doc comment requires.
- Initial state is `emptyMap()`, so first emission for any filter is `emptyList()` ✅ AC.

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
    flowOf(emptyList())
```

- AC explicitly accepts `flowOf(emptyList())` for this ticket. Real message storage + `SessionBoundary` interleaving arrives in #9. Do **not** wire `observeMessages` to `state` — that would mislead consumers into thinking messages live in the same state-holder.

### Mutator semantics

All mutators use `state.update { records -> ... }`. For unknown `conversationId`, throw `IllegalArgumentException("Unknown conversation: $conversationId")` — these are contract violations (caller passed an id not from `observeConversations`), and silent no-ops mask bugs. AC's "operate on in-memory storage without throwing" is read as "must actually work end-to-end; no `TODO()` / `NotImplementedError`" — not "must swallow unknown ids."

#### `createDiscussion(workspace: String? = null): Conversation`

- Mint new `conversationId` and `sessionId` via `UUID.randomUUID().toString()`.
- Mint `claudeSessionUuid` similarly (fake; Phase 4 gets a real one from the CLI).
- `cwd = workspace ?: ""` — discussions default to a "scratch cwd" per CLAUDE.md; the empty string stands in for "unassigned scratch" at this stage. Don't synthesize fake paths.
- `now = Clock.System.now()`.
- Build the new `Conversation` with `isPromoted = false`, `name = null`, `currentSessionId = sessionId`, `sessionHistory = listOf(sessionId)`, `lastUsedAt = now`.
- Build the new `Session` with `conversationId`, `claudeSessionUuid`, `startedAt = now`, `endedAt = null`.
- Insert into `state` and return the `Conversation`.

#### `promote(conversationId, name, workspace?): Conversation`

- Find the existing record; throw `IllegalArgumentException` if absent.
- `record.copy(conversation = conv.copy(isPromoted = true, name = name, cwd = workspace ?: conv.cwd, lastUsedAt = now))`.
- Return the updated `Conversation`.

#### `archive(conversationId)`

- `state.update { it - conversationId }`. Throw if absent (the `-` operator is a no-op for missing keys, so do the membership check explicitly first, before `update`).
- After archive, the conversation is gone from `observeConversations` emissions for every filter ✅ AC.

#### `rename(conversationId, name): Conversation`

- `record.copy(conversation = conv.copy(name = name, lastUsedAt = now))`. Throw if absent.

#### `startNewSession(conversationId, workspace? = null): Session`

- Find record; throw if absent.
- Mint new `sessionId` + `claudeSessionUuid`.
- New `Session`: `conversationId`, `claudeSessionUuid`, `startedAt = now`, `endedAt = null`.
- Close out the previously-active session: `oldSession.copy(endedAt = now)` (so the record's `sessions` map reflects reality for whoever queries it later — not strictly required by AC, but consistent with the `endedAt: Instant?` semantic in `Session.kt`).
- Update `conversation`: `currentSessionId = newSessionId`, `sessionHistory = sessionHistory + newSessionId`, `cwd = workspace ?: conv.cwd`, `lastUsedAt = now`.
- Return the new `Session`.

#### `changeWorkspace(conversationId, workspace): Session`

- Per CLAUDE.md, workspace change is a session-boundary reason — it mints a new session.
- Delegate to the same internal logic as `startNewSession`, with `workspace` non-null.
- Return the new `Session`.

Extract the shared body into a private helper (`fun newSessionFor(record: ConversationRecord, workspace: String?, now: Instant): Pair<ConversationRecord, Session>`) — `startNewSession` and `changeWorkspace` are otherwise identical. **This is the only abstraction the file should introduce**; do not pre-factor anything else.

### Exact source — `FakeConversationRepository.kt`

```kotlin
package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Phase 1 in-memory implementation. Storage is a single [MutableStateFlow]
 * of `conversationId -> ConversationRecord`; mutators update it atomically
 * and observers re-emit on every change. Phase 4 replaces this with a
 * Ktor-backed remote implementation behind the same interface.
 */
class FakeConversationRepository : ConversationRepository {

    private val state = MutableStateFlow<Map<String, ConversationRecord>>(emptyMap())

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
        }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flowOf(emptyList())

    override suspend fun createDiscussion(workspace: String?): Conversation {
        val now = Clock.System.now()
        val conversationId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val claudeSessionUuid = UUID.randomUUID().toString()
        val session = Session(
            id = sessionId,
            conversationId = conversationId,
            claudeSessionUuid = claudeSessionUuid,
            startedAt = now,
            endedAt = null,
        )
        val conversation = Conversation(
            id = conversationId,
            name = null,
            cwd = workspace ?: "",
            currentSessionId = sessionId,
            sessionHistory = listOf(sessionId),
            isPromoted = false,
            lastUsedAt = now,
        )
        val record = ConversationRecord(conversation, mapOf(sessionId to session))
        state.update { it + (conversationId to record) }
        return conversation
    }

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation {
        val now = Clock.System.now()
        lateinit var updated: Conversation
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            updated = record.conversation.copy(
                isPromoted = true,
                name = name,
                cwd = workspace ?: record.conversation.cwd,
                lastUsedAt = now,
            )
            records + (conversationId to record.copy(conversation = updated))
        }
        return updated
    }

    override suspend fun archive(conversationId: String) {
        state.update { records ->
            if (conversationId !in records) throw unknown(conversationId)
            records - conversationId
        }
    }

    override suspend fun rename(conversationId: String, name: String): Conversation {
        val now = Clock.System.now()
        lateinit var updated: Conversation
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            updated = record.conversation.copy(name = name, lastUsedAt = now)
            records + (conversationId to record.copy(conversation = updated))
        }
        return updated
    }

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = mintNewSession(conversationId, workspace)

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = mintNewSession(conversationId, workspace)

    private fun mintNewSession(conversationId: String, workspace: String?): Session {
        val now = Clock.System.now()
        lateinit var newSession: Session
        state.update { records ->
            val record = records[conversationId] ?: throw unknown(conversationId)
            val newSessionId = UUID.randomUUID().toString()
            val claudeSessionUuid = UUID.randomUUID().toString()
            newSession = Session(
                id = newSessionId,
                conversationId = conversationId,
                claudeSessionUuid = claudeSessionUuid,
                startedAt = now,
                endedAt = null,
            )
            val closedSessions = record.sessions.mapValues { (_, s) ->
                if (s.id == record.conversation.currentSessionId && s.endedAt == null) {
                    s.copy(endedAt = now)
                } else s
            }
            val updatedConversation = record.conversation.copy(
                currentSessionId = newSessionId,
                sessionHistory = record.conversation.sessionHistory + newSessionId,
                cwd = workspace ?: record.conversation.cwd,
                lastUsedAt = now,
            )
            records + (conversationId to ConversationRecord(
                conversation = updatedConversation,
                sessions = closedSessions + (newSessionId to newSession),
            ))
        }
        return newSession
    }

    private fun unknown(id: String) =
        IllegalArgumentException("Unknown conversation: $id")

    private data class ConversationRecord(
        val conversation: Conversation,
        val sessions: Map<String, Session>,
    )
}
```

### Test file — `FakeConversationRepositoryTest.kt`

JUnit 4 only. Use `runBlocking` from `kotlinx-coroutines-core` (already on classpath) plus `Flow.first()` — no `kotlinx-coroutines-test`. `Flow.first()` on a `StateFlow`-derived flow returns immediately.

```kotlin
package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeConversationRepositoryTest {

    @Test
    fun observeConversations_emitsEmpty_initially_for_all_filters() = runBlocking {
        val repo = FakeConversationRepository()
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.All).first())
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.Channels).first())
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.Discussions).first())
    }

    @Test
    fun observeMessages_emitsEmpty() = runBlocking {
        val repo = FakeConversationRepository()
        assertEquals(emptyList<Any>(), repo.observeMessages("any-id").first())
    }

    @Test
    fun createDiscussion_appearsIn_observeConversations_All() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val all = repo.observeConversations(ConversationFilter.All).first()
        assertEquals(listOf(created), all)
    }

    @Test
    fun createDiscussion_isUnpromoted_andHasNullName() = runBlocking {
        val repo = FakeConversationRepository()
        val c = repo.createDiscussion()
        assertEquals(false, c.isPromoted)
        assertNull(c.name)
    }

    @Test
    fun createDiscussion_appearsIn_Discussions_filter_butNotIn_Channels() = runBlocking {
        val repo = FakeConversationRepository()
        repo.createDiscussion()
        assertEquals(1, repo.observeConversations(ConversationFilter.Discussions).first().size)
        assertEquals(0, repo.observeConversations(ConversationFilter.Channels).first().size)
    }

    @Test
    fun promote_flipsIsPromoted_andApplies_name_and_workspace() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val promoted = repo.promote(created.id, name = "my-channel", workspace = "/work")
        assertEquals(true, promoted.isPromoted)
        assertEquals("my-channel", promoted.name)
        assertEquals("/work", promoted.cwd)
    }

    @Test
    fun promote_movesConversation_from_Discussions_to_Channels() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        repo.promote(created.id, name = "my-channel")
        assertEquals(0, repo.observeConversations(ConversationFilter.Discussions).first().size)
        assertEquals(1, repo.observeConversations(ConversationFilter.Channels).first().size)
    }

    @Test
    fun archive_removes_from_observeConversations() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        repo.archive(created.id)
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.All).first())
    }

    @Test
    fun rename_updates_name_and_reEmits() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val renamed = repo.rename(created.id, "renamed")
        assertEquals("renamed", renamed.name)
        assertEquals(listOf(renamed), repo.observeConversations(ConversationFilter.All).first())
    }

    @Test
    fun startNewSession_returnsFreshSession_withDifferentId() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val newSession = repo.startNewSession(created.id)
        assertNotEquals(created.currentSessionId, newSession.id)
        assertEquals(created.id, newSession.conversationId)
        assertNull(newSession.endedAt)
    }

    @Test
    fun changeWorkspace_returnsFreshSession_andUpdatesCwd() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion(workspace = "/old")
        val newSession = repo.changeWorkspace(created.id, "/new")
        assertEquals(created.id, newSession.conversationId)
        assertNotEquals(created.currentSessionId, newSession.id)
        val current = repo.observeConversations(ConversationFilter.All).first().single()
        assertEquals("/new", current.cwd)
    }

    @Test
    fun promote_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.promote("nope", name = "x") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
```

## State + concurrency model

- **Single source of state:** `MutableStateFlow<Map<String, ConversationRecord>>`. Every read flow (`observeConversations`) is derived from it via `Flow.map`. No parallel mutable state.
- **Concurrency:** mutators call `state.update { ... }`, which performs atomic CAS with retry. No `Mutex`. Safe under concurrent callers from any thread.
- **Dispatcher:** none chosen. The fake does CPU-only map manipulation; `viewModelScope.launch` from a consumer dispatches to `Main`, and the work performed here is sub-microsecond. No need for `withContext(Dispatchers.Default)`.
- **`observeMessages` is a hot-vs-cold note:** `flowOf(emptyList())` is a cold flow that emits one value and completes. Consumers expecting an open subscription should not be surprised at this stage — #9 replaces this with a long-lived stream derived from `state` once messages are real.
- **Cancellation / shutdown:** nothing to clean up. No `Job`, no `CoroutineScope` owned by the class. The class is stateless from a lifecycle perspective; consumers (ViewModels) manage their own `viewModelScope`.

## Error handling

- **Unknown `conversationId`:** `IllegalArgumentException`. Programmer error — caller passed an id not from `observeConversations`. Surfacing this loudly is correct for Phase 1; Phase 4's remote impl will distinguish "not found" vs network failure, but we don't model that yet.
- **No other failure modes** at this stage: no IO, no network, no parse, no permission.

## Testing strategy

- **Unit only.** `./gradlew test`. No instrumented tests.
- **JUnit 4 + `runBlocking` + `Flow.first()`.** No `kotlinx-coroutines-test`, no MockK. Idiomatic for a `StateFlow`-derived flow that always has a current value.
- **What's covered:**
  - Initial empty emissions for every filter value (AC).
  - `observeMessages` initial empty emission (AC).
  - `createDiscussion` appends and appears in next `All` emission (AC behavioral test).
  - `createDiscussion` produces `isPromoted = false` and `name = null` (AC).
  - Filter discrimination: discussion appears in `Discussions`, not `Channels`; promoted conversation flips between filters.
  - `promote` sets `isPromoted = true` and applies `name` / `workspace`.
  - `archive` removes from emissions.
  - `rename` updates `name`.
  - `startNewSession` returns a fresh `Session` with a new id, same `conversationId`, `endedAt = null`.
  - `changeWorkspace` returns a fresh `Session` and updates the stored `cwd`.
  - `promote` on unknown id throws — locks down the error-handling contract for the developer agent.
- **What's not covered (out of scope):** seed data, message stream, `SessionBoundary` interleaving, multi-session histories. These belong to #5, #6, #9, #10.

## Out of scope

- DI wiring (no Koin module exists yet; #11 or the first ViewModel ticket introduces it).
- Adding `kotlinx-coroutines-test` to the catalog (not needed at this stage; revisit when virtual-time tests are required).
- Persistence — pure in-memory; restart erases everything.
- Sort order on `observeConversations` emissions — the ViewModel will sort by `lastUsedAt` once seed data lands. The fake emits map-value order, which is intentionally unspecified at this stage.

## Open questions

None. Storage shape, mutator semantics, error handling, and emission semantics are all pinned down by AC + CLAUDE.md.
