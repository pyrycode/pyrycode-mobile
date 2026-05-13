# Data model — Conversation / Session / Message

The core schema for everything the app shows. Mirrors the entity shape that pyrycode CLI's `conversations.json` registry (Phase 3) and the Discord client (Phase 2) will share — same fields across all consumers.

Package: `de.pyryco.mobile.data.model` (`app/src/main/java/de/pyryco/mobile/data/model/`).

## Types

### `Conversation`

```kotlin
data class Conversation(
    val id: String,
    val name: String?,             // null for discussions; user-set for channels
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,       // false = discussion, true = channel
    val lastUsedAt: Instant,
    val isSleeping: Boolean = false,
)
```

`isPromoted` is the single flag that splits the two UI tiers (see CLAUDE.md → "Conversations model"):

- **Discussions** (`isPromoted = false`) — auto-named (`name == null`), throwaway, scratch cwd, 30-day auto-archive.
- **Channels** (`isPromoted = true`) — user-named (`name != null`), persistent, dedicated cwd by default, eligible for memory plugins.

`sessionHistory` is an ordered list of past `Session.id`s; `currentSessionId` is the live one. Together they let the thread screen paginate messages chronologically across session boundaries.

`isSleeping` is `true` when the conversation's current Claude session is closed (i.e. the next user message will start a fresh session). Defaulted to `false` so the existing constructor sites needn't pass it. **Phase 1**: derived in `FakeConversationRepository.observeConversations` from `currentSession.endedAt != null` — see [`conversation-repository.md`](conversation-repository.md). **Phase 4**: parsed directly from the conversations endpoint response (the server reports the bool); the contract on this field is what survives the migration. Surfaced visually as a leading status dot on `ConversationRow` (#20) — see [`conversation-row.md`](conversation-row.md).

Co-located in the same file: a top-level `const val DefaultScratchCwd: String = "~/.pyrycode/scratch"` — the sentinel `cwd` for conversations with no bound workspace. Single source of truth for the value: imported by `FakeConversationRepository` seeds and `ConversationRow` (which suppresses its workspace label when `cwd == DefaultScratchCwd`). Introduced in #19; lives at `data/model/Conversation.kt` because both the UI and data layers already depend on this package, and a separate `WorkspacePaths.kt` for one constant would be premature.

### `Session`

```kotlin
data class Session(
    val id: String,
    val conversationId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant?,
)
```

A session is one continuous claude conversation. New sessions begin on `/clear`, idle-evict, or workspace change — those transitions are what the thread screen renders as horizontal-rule delimiters. `claudeSessionUuid` is the identifier claude itself uses (distinct from our `id`, which is mobile-side).

`endedAt == null` indicates the currently-active session.

### `Message`

```kotlin
data class Message(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Instant,
    val isStreaming: Boolean,
)

enum class Role { User, Assistant, Tool }
```

`isStreaming = true` while assistant output is still arriving over the wire (Phase 4+). For Phase 0/1 fake data, every message is `isStreaming = false`.

## Why `kotlinx.datetime.Instant`

CLAUDE.md's "Don't" section names Compose Multiplatform as a walk-back trigger. `java.time.Instant` is JVM-only; `kotlinx.datetime.Instant` works on every Kotlin target. The data layer must stay portable, so every timestamp in this package uses the kotlinx type. See `../decisions/0001-kotlinx-datetime-for-data-layer.md`.

## What's deliberately absent

- **No serialization annotations.** `@Serializable` / `@JsonClass` belong to Phase 4 when the wire protocol ships. Adding them speculatively pulls in a Gradle plugin (`kotlin("plugin.serialization")`) the project doesn't otherwise need.
- **No `require(...)` / `init { }` validation.** Schema-shape only; constructors cannot fail. The repository layer will enforce invariants (e.g. `currentSessionId ∈ sessionHistory ∪ {new}`) when it lands.
- **No `SessionBoundary` marker here.** CLAUDE.md describes a synthetic marker the repository interleaves into the message stream to drive thread-screen delimiters; that type lives with the repository contract as `ThreadItem.SessionBoundary` in `data/repository/ConversationRepository.kt` (landed in #3). See `conversation-repository.md`.
- **No persistence.** DataStore / Room are out of scope; the fake repository will hold these in memory.

## Related

- Ticket notes: `../codebase/2.md`
- Spec: `docs/specs/architecture/2-conversation-session-message-data-classes.md`
- Decision: `../decisions/0001-kotlinx-datetime-for-data-layer.md`
- Downstream: `conversation-repository.md` (#3 contract), conversation list + thread UI.
