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
    val archived: Boolean = false,
)
```

`isPromoted` is the single flag that splits the two UI tiers (see CLAUDE.md → "Conversations model"):

- **Discussions** (`isPromoted = false`) — auto-named (`name == null`), throwaway, scratch cwd, 30-day auto-archive.
- **Channels** (`isPromoted = true`) — user-named (`name != null`), persistent, dedicated cwd by default, eligible for memory plugins.

`sessionHistory` is an ordered list of past `Session.id`s; `currentSessionId` is the live one. Together they let the thread screen paginate messages chronologically across session boundaries.

`isSleeping` is `true` when the conversation's current Claude session is closed (i.e. the next user message will start a fresh session). Defaulted to `false` so the existing constructor sites needn't pass it. **Phase 1**: derived in `FakeConversationRepository.observeConversations` from `currentSession.endedAt != null` — see [`conversation-repository.md`](conversation-repository.md). **Phase 4**: parsed directly from the conversations endpoint response (the server reports the bool); the contract on this field is what survives the migration. Surfaced visually as a leading status dot on `ConversationRow` (#20) — see [`conversation-row.md`](conversation-row.md).

`archived` is `true` once `ConversationRepository.archive(id)` has flipped the flag (#93). Authoritative bit, not derived: Phase 1 stores it on the data class; Phase 4 will parse it from the wire response. Defaulted to `false` so existing constructor sites don't change. Routes the conversation into the `ConversationFilter.Archived` slice and out of `Channels` / `Discussions`; the live tiers carry an explicit `!archived` clause so a hypothetical archived channel can't regress the channel list. The trivial inverse `unarchive(...)` is a follow-up ticket. See [`conversation-repository.md`](conversation-repository.md) for the filter matrix.

Co-located in the same file: a top-level `const val DEFAULT_SCRATCH_CWD: String = "~/.pyrycode/scratch"` — the sentinel `cwd` for conversations with no bound workspace. Single source of truth for the value: imported by `FakeConversationRepository` seeds and `ConversationRow` (which suppresses its workspace label when `cwd == DEFAULT_SCRATCH_CWD`). Introduced in #19; renamed from `DefaultScratchCwd` in #83 to satisfy ktlint's `property-naming` default (Kotlin official style for top-level `const val`). Lives at `data/model/Conversation.kt` because both the UI and data layers already depend on this package, and a separate `WorkspacePaths.kt` for one constant would be premature.

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
    /** Non-null iff [role] is [Role.Tool]. */
    val toolCall: ToolCall? = null,
)

enum class Role { User, Assistant, Tool }

data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
)
```

`isStreaming = true` while assistant output is still arriving over the wire (Phase 4+). Phase 0/1 fake data is `isStreaming = false` for every message except one demo seed (the last assistant message of `seed-channel-pyrycode-mobile`'s current session, added in #184) so the [streaming reveal animation](./message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184) can be observed end-to-end without a real WS feed.

`toolCall` (#191) carries the three pieces a `Role.Tool` message needs the [`ToolCallRow`](./message-bubble.md) consumer (#131) to discriminate: `toolName` (e.g. `"Read"`, `"Edit"`, `"Bash"`), `input` (the call payload — file path, command string, params), and `output` (the response text — file contents, command stdout, etc.). The field is **last** on `Message` and **defaulted to `null`** so every existing positional and named `Message(...)` call site is source-compatible. The invariant — `toolCall != null iff role == Role.Tool` — lives as a one-line KDoc on the field; it is **not** enforced at construction (no `init { }` guard, no `require(...)`). Reasoning: `FakeConversationRepository` is the sole `Message` producer in Phase 0 — there is no external path that could violate the invariant, so a runtime check would defend an unobserved failure mode. Phase 4's wire-parsing path is the right place to enforce it when it lands. No `output: String?` for "tool call in flight, no response yet" — that's a Phase 4 streaming concern; modelling it now would be the same kind of speculative defense. The shape was chosen (over a sealed `Message` hierarchy with a `Tool` variant) for size discipline: nullable-field is a 3-file change with zero forced consumer edits; sealed promotion would have cascaded into ≥16 `seedMsg` / `Message(...)` call sites + two test files. If #131 finds the lack of compile-time discrimination genuinely painful, a sealed promotion ticket can be split out — the `toolCall: ToolCall?` field added here becomes the payload of the eventual `Tool` variant, so nothing is wasted. See `../codebase/191.md` for the trade-off in full.

## Why `kotlinx.datetime.Instant`

CLAUDE.md's "Don't" section names Compose Multiplatform as a walk-back trigger. `java.time.Instant` is JVM-only; `kotlinx.datetime.Instant` works on every Kotlin target. The data layer must stay portable, so every timestamp in this package uses the kotlinx type. See `../decisions/0001-kotlinx-datetime-for-data-layer.md`.

## What's deliberately absent

- **No serialization annotations.** `@Serializable` / `@JsonClass` belong to Phase 4 when the wire protocol ships. Adding them speculatively pulls in a Gradle plugin (`kotlin("plugin.serialization")`) the project doesn't otherwise need.
- **No `require(...)` / `init { }` validation.** Schema-shape only; constructors cannot fail. The repository layer will enforce invariants (e.g. `currentSessionId ∈ sessionHistory ∪ {new}`) when it lands.
- **No `SessionBoundary` marker here.** CLAUDE.md describes a synthetic marker the repository interleaves into the message stream to drive thread-screen delimiters; that type lives with the repository contract as `ThreadItem.SessionBoundary` in `data/repository/ConversationRepository.kt` (landed in #3). See `conversation-repository.md`.
- **No persistence.** DataStore / Room are out of scope; the fake repository will hold these in memory.

## Related

- Ticket notes: `../codebase/2.md` (skeleton), `../codebase/191.md` (`Message.toolCall: ToolCall? = null` + new `ToolCall(toolName, input, output)` type)
- Spec: `docs/specs/architecture/2-conversation-session-message-data-classes.md`, `docs/specs/architecture/191-tool-message-structured-payload.md`
- Decision: `../decisions/0001-kotlinx-datetime-for-data-layer.md`
- Downstream: `conversation-repository.md` (#3 contract — also propagates `toolCall` through `SeedMessage`/`seedMsg(...)` since #191), conversation list + thread UI (the eventual `ToolCallRow` consumer in #131).
