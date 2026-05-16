# ConversationRepository — data-layer contract

The single interface every Phase 1 UI tier binds to and that the Phase 4 Ktor-backed implementation must satisfy.

Package: `de.pyryco.mobile.data.repository` (`app/src/main/java/de/pyryco/mobile/data/repository/`).

Phase 1 binding: `FakeConversationRepository` — in-memory implementation backing every Phase 1 ViewModel. See [Phase 1 implementation](#phase-1-implementation--fakeconversationrepository) below.

## Shape

```kotlin
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
    fun observeLastMessage(conversationId: String): Flow<Message?>

    suspend fun createDiscussion(workspace: String? = null): Conversation
    suspend fun promote(conversationId: String, name: String, workspace: String? = null): Conversation
    suspend fun archive(conversationId: String)
    suspend fun unarchive(conversationId: String)
    suspend fun rename(conversationId: String, name: String): Conversation
    suspend fun startNewSession(conversationId: String, workspace: String? = null): Session
    suspend fun changeWorkspace(conversationId: String, workspace: String): Session
}

enum class ConversationFilter { All, Channels, Discussions, Archived }

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

- **Streams are cold `Flow`s.** `observeConversations`, `observeMessages`, and `observeLastMessage` (#161) emit the current value on subscription and every subsequent change. Cancellation follows the collector's scope.
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
- **No eager boundary emission from `startNewSession` / `changeWorkspace`.** Boundaries are derived from message session-id deltas in the projection, not written into storage by mutators with the true `BoundaryReason` and the mutator's `Clock.System.now()`. This means every Phase 1 boundary surfaces as `BoundaryReason.Clear`, regardless of whether the underlying cause was `/clear`, idle-evict, or a workspace change. Lift reason metadata onto `Session` (or alongside `messages` on `ConversationRecord`) when the thread screen needs distinguishable reasons; until then, derived `Clear` is honest about the inference being lossy.
- **No `.distinctUntilChanged()`.** Consistent with `observeConversations`. Both projections re-emit on every `state.update {}` — even mutations that don't affect the observed slice. If a future chatty mutator (per-token streaming append) makes this matter, fix with a subtype-aware dedupe in the projection, not a sibling state holder.

## Dependency wiring

`kotlinx-coroutines-core 1.10.2` is pinned in `gradle/libs.versions.toml` and declared as `implementation(libs.kotlinx.coroutines.core)`. Compose and lifecycle pull coroutines onto the runtime classpath transitively, but `data/` declares its own pin so the contract isn't coupled to a transitive BOM bump.

`-core` (not `-android`) keeps the data layer JVM-portable for the Compose Multiplatform walk-back (`Main` dispatcher comes in via the UI layer's `-android` transitive).

## Phase 1 implementation — `FakeConversationRepository`

Same package; file `FakeConversationRepository.kt`. In-memory, no persistence, no network. Restart erases everything.

- **Storage:** `MutableStateFlow<Map<String, ConversationRecord>>` — a single state-holder keyed by `conversationId`. `ConversationRecord` is a private file-scoped wrapper that pairs the `Conversation` with the full `Session` values (the `Conversation` itself only stores session **ids**).
- **`observeConversations(filter)`** is derived from the state-holder via `Flow.map`, `filter`ed per `ConversationFilter` (matrix below), and `sortedByDescending { it.lastUsedAt }`. The sort is filter-agnostic — channel list and discussions drilldown both want most-recently-used first. Initial emission carries the seed records (see "Seed data" below). The projection also stamps `Conversation.isSleeping` from `record.sessions[currentSessionId]?.endedAt != null` (#20) — true exactly when the conversation's current session is closed and no new one has started yet. This is the one place that holds both the conversation and its session map; downstream `UiState` shapes consume an unchanged `List<Conversation>`. Phase 4's Ktor-backed impl will parse `isSleeping` from the wire response instead of deriving it. Filter matrix (#93): `All` = everything (literally — including archived); `Channels` = `isPromoted && !archived`; `Discussions` = `!isPromoted && !archived`; `Archived` = `archived` (regardless of `isPromoted`). The `!archived` clauses on the live tiers are symmetric on purpose — `All` is the only filter that surfaces tombstones unconditionally.
- **`observeMessages(conversationId)`** projects from the same state-holder via `state.map { records -> records[id]?.messages?.let(::buildThreadItems) ?: emptyList() }`. Messages live inside `ConversationRecord` as a defaulted `messages: List<Message> = emptyList()` field — single source of truth, no sibling map. `buildThreadItems` sorts by `Message.timestamp` ascending, then walks the sorted list inserting a `ThreadItem.SessionBoundary` whenever consecutive messages differ in `sessionId`. Derived boundaries carry `reason = BoundaryReason.Clear` and `occurredAt = message.timestamp` of the new session's first message — both are deliberate defaults that hold until eager boundary emission lands (see "What `observeMessages` does not do" below). Unknown id and known-with-no-messages collapse to the same `emptyList()` path (observation is tolerant; mutation is strict). See `../codebase/9.md`.
- **`observeLastMessage(conversationId)`** (#161) projects from the same state-holder via `state.map { records -> records[id]?.messages?.maxByOrNull { it.timestamp } }`. Emits the most-recent `Message` by `Message.timestamp` for the conversation, or `null` if the conversation has no messages or is unknown (same tolerant-observation rule as `observeMessages`). Sibling to `observeMessages` rather than a derived projection on top of it because the consumer ([`ChannelListViewModel`](./channel-list-viewmodel.md)'s `recentDiscussionLastMessages` map) only wants the most-recent element, not the full thread; running the full `buildThreadItems` projection for each recent-discussion row would do extra work and re-allocate the boundary list. The choice of `maxByOrNull` (linear scan over the unsorted list) rather than `sortedBy { … }.last()` is intentional — `observeMessages` already sorts the same list for its own projection, but the two projections don't share work in the Phase 1 fake. A batched `observeLastMessages(ids: Set<String>)` was considered as the API shape and rejected — the recent slice is capped at 3, so per-row subscriptions via `combine` are bounded. See `../codebase/161.md`.
- **Mutators** use `state.update { ... }` (atomic CAS, no `Mutex`). `lateinit var` escapes the affected entity out of the lambda so the suspend function can return it. Since #93, `archive(id)` flips the new `Conversation.archived` flag via `record.copy(conversation = record.conversation.copy(archived = true))` instead of removing the entry from the store; unknown id still throws via the existing `unknown(id)` helper. Idempotent by construction — the second `archive(id)` produces an `equals`-identical map and `MutableStateFlow.update` does not re-emit. `lastUsedAt` is deliberately not bumped (archive is a lifecycle transition, not usage); `isPromoted` is not touched (an archived channel routes to `Archived` via the matrix). Since #96, the inverse `unarchive(id)` sits immediately under `archive(id)` (interface line 32, impl block line 135) and shares the same scaffold — `record.copy(conversation = record.conversation.copy(archived = false))`, same `unknown(id)` throw on missing, same free idempotency from `equals`-stable `data class` copy. No precondition check on the current `archived` value; calling `unarchive` on an already-unarchived conversation is a silent no-op.
- **Session boundaries:** workspace change is a session-boundary reason per CLAUDE.md. `changeWorkspace` and `startNewSession` share a private `mintNewSession(...)` helper that mints a new `Session`, closes out the previously-active one (`endedAt = now`), and appends the new id to `Conversation.sessionHistory`.
- **Seed data:** initial state is three channels (`Pyrycode Mobile`, `Joi Pilates`, `Personal`) plus three discussions (`seed-discussion-a`, `seed-discussion-b`, `seed-discussion-archived`). Channels have stable `seed-channel-*` / `seed-session-*` ids, distinct `cwd`s, and a multi-session message history (one historical session ended days ago + the existing current session, four `User`/`Assistant`-alternating messages per session). Discussions share the scratch sentinel `cwd = "~/.pyrycode/scratch"` and carry `name = null` (auto-named at render time). Since #161, `seed-discussion-a` ships with 4 alternating `User`/`Assistant` messages spanning 12 minutes ending at the seed's `lastUsedAt` (`2026-05-11T14:00:00Z`) so the [`observeLastMessage`](#shape) non-null path is exercised end-to-end; `seed-discussion-b` and `seed-discussion-archived` stay `messages = emptyList()` so the null path stays reachable too. The third discussion `seed-discussion-archived` has `archived = true` (#93) and a deliberately older `lastUsedAt` so the archived stream is non-empty out of the box without any UI/test setup. All `lastUsedAt` values are fixed `Instant.parse(...)` literals. Built via a private `companion object` (`SEED_RECORDS` / `buildSeedRecords` / `seedChannel` / `seedDiscussion`) so the data is evaluated once at class load; channel histories are authored via `SeedSession` / `SeedMessage` fixture types that the helper expands into real `Session` / `Message` values. Seeds are inserted in source order that is deliberately *not* the sorted order — exercises the sort projection on both tiers. `seedChannel(...)` takes an optional `currentSessionEndedAt: Instant? = null` (#20) — `seed-channel-joi-pilates` opts in so one channel runtime-renders the sleeping indicator without depending on previews. `seedDiscussion(...)` takes optional `archived: Boolean = false` (#93) and `messages: List<SeedMessage> = emptyList()` (#161, mirroring `seedChannel`'s `currentMessages`). Invariant for any seed that carries messages: the seed's `lastUsedAt` equals the last message's `timestamp` so tests can assert against either anchor interchangeably. See `../codebase/5.md`, `../codebase/6.md`, `../codebase/10.md`, `../codebase/20.md`, `../codebase/93.md`, `../codebase/161.md`.
- **DI binding** landed in #45 alongside the first `ViewModel` consumer (`ChannelListViewModel`): `single { FakeConversationRepository() } bind ConversationRepository::class` in `de/pyryco/mobile/di/AppModule.kt`. Constructor-inject on the interface; Phase 4's remote impl replaces the `single { }` line with no consumer-side change.

Out of scope for the Phase 1 fake: persistence, `Dispatchers.IO` (everything is CPU-cheap map manipulation). Sort order moved into the repo with #5; ViewModels can rely on the emission being `lastUsedAt`-descending.

## What's deliberately absent

- **No `getConversation(id)` / `searchConversations` / `archiveBatch`.** Add when a real use case needs them.
- **No companion / factory.** Construction is Koin's job.
- **No `@Throws` annotation.** Kotlin doesn't enforce checked exceptions; doc-comments will name thrown types when concrete impls land.
- **No tests at the interface level.** An interface without an implementation can't be unit-tested. Behavioural tests live with `FakeConversationRepository` in `FakeConversationRepositoryTest.kt` (#4).

## Related

- Schema: `data-model.md` (`Conversation` / `Session` / `Message`)
- Ticket notes: `../codebase/3.md` (interface), `../codebase/4.md` (`FakeConversationRepository` skeleton), `../codebase/9.md` (message storage + boundary interleaving), `../codebase/10.md` (seeded multi-session histories), `../codebase/161.md` (new `observeLastMessage` projection + `seedDiscussion(messages = …)` parameter)
- Specs: `docs/specs/architecture/3-conversation-repository-interface.md`, `docs/specs/architecture/4-fake-conversation-repository.md`
- Decision (timestamps): `../decisions/0001-kotlinx-datetime-for-data-layer.md`
- Downstream: every Phase 1 UI ticket (channel list, discussion drilldown, thread, promotion, workspace switching); Phase 4 Ktor-backed remote impl. Eager boundary emission with the true `BoundaryReason` is a deferred follow-up — revisit when the thread screen needs to distinguish reasons.
- Seed history: `../codebase/5.md` (channels, sort projection), `../codebase/6.md` (discussions).
