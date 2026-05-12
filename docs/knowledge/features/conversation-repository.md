# ConversationRepository — data-layer contract

The single interface every Phase 1 UI tier binds to and that the Phase 4 Ktor-backed implementation must satisfy.

Package: `de.pyryco.mobile.data.repository` (`app/src/main/java/de/pyryco/mobile/data/repository/`).

Phase 1 binding: `FakeConversationRepository` — in-memory implementation backing every Phase 1 ViewModel. See [Phase 1 implementation](#phase-1-implementation--fakeconversationrepository) below.

## Shape

```kotlin
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>

    suspend fun createDiscussion(workspace: String? = null): Conversation
    suspend fun promote(conversationId: String, name: String, workspace: String? = null): Conversation
    suspend fun archive(conversationId: String)
    suspend fun rename(conversationId: String, name: String): Conversation
    suspend fun startNewSession(conversationId: String, workspace: String? = null): Session
    suspend fun changeWorkspace(conversationId: String, workspace: String): Session
}

enum class ConversationFilter { All, Channels, Discussions }

sealed interface ThreadItem {
    data class MessageItem(val message: Message) : ThreadItem
    data class SessionBoundary(
        val previousSessionId: String,
        val newSessionId: String,
        val reason: BoundaryReason,
        val occurredAt: Instant,
    ) : ThreadItem
}

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }
```

## Conventions

- **Streams are cold `Flow`s.** `observeConversations` and `observeMessages` emit the current value on subscription and every subsequent change. Cancellation follows the collector's scope.
- **Mutators are `suspend` one-shots that return the affected entity.** Callers don't need to re-fetch; the observed stream re-emits the same change. The single non-`Unit` exception is `archive`, which returns `Unit` because no follow-up handle is meaningful.
- **Failures throw, they don't `Result`-wrap.** Unknown `conversationId` throws `IllegalArgumentException` (fake) — caller passed an id not from `observeConversations`, which is programmer error, not a runtime "not found" lookup. Phase 4's remote impl will introduce a separate not-found semantic for the network case. Promoting an already-promoted conversation is `IllegalStateException`; blank names are `IllegalArgumentException`. Stream-level errors terminate the flow and are caught at the UI's `catch { }`.
- **Dispatcher-agnostic.** No `flowOn(...)` in the contract; each impl picks its dispatcher (the Phase 1 fake stays in-memory and synchronous, Phase 4 will use `Dispatchers.IO`).
- **IDs are raw `String`.** The shipped #2 model uses `String` for `Conversation.id` and `Session.id`; matching that here avoids a model-wide value-class refactor inside a contract-only ticket. Promoting to `ConversationId` / `SessionId` value classes is a separate pass across `data/model/` + every call site.

## `ThreadItem` — why a sealed wrapper

The thread screen renders messages chronologically and inserts a horizontal-rule delimiter at each session boundary (`/clear`, idle-evict, workspace change — see CLAUDE.md → "Conversations model"). Above-delimiter messages are visually de-emphasized; the line below offers the memory-plugin install affordance.

The stream interleaves both kinds of row in order, so the consumer never paginates manually across `sessionHistory`. Design notes:

- `MessageItem` **wraps** `Message` rather than having `Message` implement `ThreadItem` directly — keeping a `data/repository/` type out of `data/model/`'s parent chain preserves the layer direction.
- `SessionBoundary` carries both `previousSessionId` and `newSessionId`. Previous anchors the delimiter to the messages above it; new gives the "claude doesn't remember above the line" prompt a stable handle.
- `BoundaryReason` lists exactly the three triggers CLAUDE.md names. No speculative `Manual` / `Other` / `Unknown` — add a value if and when a fourth trigger lands.

## What `observeMessages` does not do

- **No `PagingData`.** "Paginates transparently" in the ticket AC means the consumer doesn't drive pagination; it does not mean lazy windowing in the contract. The Phase 1 fake emits the full list; Phase 4 can window internally and emit a growing prefix without changing the signature. If the channel list or thread grows past hundreds of rows in practice, switching to Paging 3 is a contract change at that point.
- **No `Flow<Result<…>>`.** Element type is `ThreadItem`, not `Result<ThreadItem>`. Failures terminate the flow.

## Dependency wiring

`kotlinx-coroutines-core 1.10.2` is pinned in `gradle/libs.versions.toml` and declared as `implementation(libs.kotlinx.coroutines.core)`. Compose and lifecycle pull coroutines onto the runtime classpath transitively, but `data/` declares its own pin so the contract isn't coupled to a transitive BOM bump.

`-core` (not `-android`) keeps the data layer JVM-portable for the Compose Multiplatform walk-back (`Main` dispatcher comes in via the UI layer's `-android` transitive).

## Phase 1 implementation — `FakeConversationRepository`

Same package; file `FakeConversationRepository.kt`. In-memory, no persistence, no network. Restart erases everything.

- **Storage:** `MutableStateFlow<Map<String, ConversationRecord>>` — a single state-holder keyed by `conversationId`. `ConversationRecord` is a private file-scoped wrapper that pairs the `Conversation` with the full `Session` values (the `Conversation` itself only stores session **ids**).
- **`observeConversations(filter)`** is derived from the state-holder via `Flow.map`, `filter`ed on the `isPromoted` flag per `ConversationFilter`, and `sortedByDescending { it.lastUsedAt }`. The sort is filter-agnostic — channel list and discussions drilldown both want most-recently-used first. Initial emission carries the seed records (see "Seed data" below).
- **`observeMessages(conversationId)`** is currently `flowOf(emptyList())` — a cold one-shot. Real message storage and `SessionBoundary` interleaving land in #9; consumers binding ahead of #9 should treat this as single-shot empty. Deliberately not wired to the same state-holder so the swap in #9 doesn't carry misleading "messages live with conversations" intuition forward.
- **Mutators** use `state.update { ... }` (atomic CAS, no `Mutex`). `lateinit var` escapes the affected entity out of the lambda so the suspend function can return it. `archive` does an explicit membership check before `state.update { it - id }` because `Map.minus` is a no-op for missing keys — without the check, archiving an unknown id would silently succeed.
- **Session boundaries:** workspace change is a session-boundary reason per CLAUDE.md. `changeWorkspace` and `startNewSession` share a private `mintNewSession(...)` helper that mints a new `Session`, closes out the previously-active one (`endedAt = now`), and appends the new id to `Conversation.sessionHistory`.
- **Seed data:** initial state is three channels — `Pyrycode Mobile`, `Joi Pilates`, `Personal` — with stable `seed-channel-*` / `seed-session-*` ids, distinct `cwd`s, and fixed `Instant.parse(...)` `lastUsedAt`s. Built via a private `companion object` (`SEED_RECORDS` / `buildSeedRecords` / `seedChannel`) so the data is evaluated once at class load. Seeds are inserted in source order that is deliberately *not* the sorted order — exercises the sort projection. Discussions tier seeds land in #6 alongside this list. See `../codebase/5.md`.
- **No DI wiring yet.** No Koin module exists; the binding lands with the first ViewModel that consumes the fake.

Out of scope for the Phase 1 fake: persistence, `Dispatchers.IO` (everything is CPU-cheap map manipulation). Sort order moved into the repo with #5; ViewModels can rely on the emission being `lastUsedAt`-descending.

## What's deliberately absent

- **No `getConversation(id)` / `searchConversations` / `archiveBatch`.** Add when a real use case needs them.
- **No companion / factory.** Construction is Koin's job.
- **No `@Throws` annotation.** Kotlin doesn't enforce checked exceptions; doc-comments will name thrown types when concrete impls land.
- **No tests at the interface level.** An interface without an implementation can't be unit-tested. Behavioural tests live with `FakeConversationRepository` in `FakeConversationRepositoryTest.kt` (#4).

## Related

- Schema: `data-model.md` (`Conversation` / `Session` / `Message`)
- Ticket notes: `../codebase/3.md` (interface), `../codebase/4.md` (`FakeConversationRepository`)
- Specs: `docs/specs/architecture/3-conversation-repository-interface.md`, `docs/specs/architecture/4-fake-conversation-repository.md`
- Decision (timestamps): `../decisions/0001-kotlinx-datetime-for-data-layer.md`
- Downstream: #5/#6 (seed data on the Phase 1 fake), #9/#10 (real message stream + multi-session histories); every Phase 1 UI ticket (channel list, discussion drilldown, thread, promotion, workspace switching); Phase 4 Ktor-backed remote impl.
