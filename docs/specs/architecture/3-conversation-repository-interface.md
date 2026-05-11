# Spec — ConversationRepository interface (#3)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — full file. The interface's stream operations and mutating ops return / observe this exact type; field shapes (`id: String`, `currentSessionId: String`, `sessionHistory: List<String>`, `isPromoted: Boolean`) inform the method semantics. Note that `id` is `String`, not a `ConversationId` value class (see Open Questions).
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` — full file. `startNewSession` and `changeWorkspace` return this. `endedAt: Instant?` is what distinguishes the active session.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — full file. `Message` is the payload carried by the thread stream. `Role` is unrelated to session-boundary markers — do not extend `Role`.
- `CLAUDE.md` (repo root) — the "Conversations model" section is the authoritative prose for the discussion/channel distinction, what triggers session boundaries (`/clear`, idle-evict, workspace change), and what gets rendered as the horizontal-rule delimiter. The interface's vocabulary must match this prose 1:1.
- `docs/knowledge/features/data-model.md` — re-confirms the schema constraints from #2 and notes "No `SessionBoundary` marker yet" with "that type lands with `ConversationRepository.observeMessages(...)`" — this ticket. Lines 71–72 are the explicit handoff.
- `docs/knowledge/decisions/0001-kotlinx-datetime-for-data-layer.md` — any new field in `data/` that carries a timestamp uses `kotlinx.datetime.Instant`. `SessionBoundary.occurredAt` (defined below) is the one new timestamp in this ticket.
- `app/build.gradle.kts` — confirms `implementation(libs.kotlinx.coroutines.*)` is **not** yet on the classpath. `Flow` comes from `kotlinx.coroutines.flow` which Compose and lifecycle pull in transitively today, but the `kotlinx-coroutines-core` artifact has to be wired explicitly to use `Flow` in non-Compose code. See "Dependency wiring" below.
- `gradle/libs.versions.toml` — version catalog. Confirm no `kotlinx-coroutines` entry exists today; add one (see "Dependency wiring").
- `docs/specs/architecture/2-conversation-session-message-data-classes.md` — the architectural template this spec mirrors. Same conventions: package layout, dependency wiring via catalog, JUnit 4 only.

## Context

This is the data layer's behavioural seam. Every Phase 1 UI ticket (channel list, discussion drilldown, thread, promotion dialog, workspace picker) binds to this interface; Phase 4's Ktor-backed implementation drops in behind the same contract. Getting the surface right now avoids churn across every consumer.

The ticket is **contract-only** — no `Fake*` or `Remote*` class lives in this commit. The interface defines what callers can do; the next ticket (`FakeConversationRepository`) will satisfy it for Phase 1.

The schema types (`Conversation`, `Session`, `Message`, `Role`) landed in #2; this ticket adds the operations over them plus two supporting types: a filter for the list stream and a sealed wrapper for the thread stream that interleaves messages with synthetic session-boundary markers.

## Design

### File layout — 1 file in `app/src/main/java/de/pyryco/mobile/data/repository/`

Create the directory by writing the file; Gradle picks it up under the existing source root.

| File | Contents |
|------|----------|
| `ConversationRepository.kt` | `interface ConversationRepository`, `enum class ConversationFilter`, `sealed interface ThreadItem` (with `MessageItem` + `SessionBoundary` variants), `enum class BoundaryReason` |

Rationale for one file: the four declarations are a single logical contract — interface + the two supporting types its signatures reference + the enum that classifies a boundary. None has an independent consumer; splitting across files adds navigation hops without payoff. Same colocation reasoning that put `Role` in `Message.kt` in #2.

### Exact source — `ConversationRepository.kt`

```kotlin
package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Phase 1 data-layer contract. The fake (Phase 1) and Ktor-backed remote
 * (Phase 4) implementations both satisfy this surface.
 *
 * Stream-shaped reads are cold [Flow]s — collectors receive the current
 * value on subscription and every subsequent change. Mutating operations
 * are `suspend` one-shots that return the affected entity so callers do
 * not need to re-fetch; the affected stream(s) will also re-emit.
 */
interface ConversationRepository {

    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>

    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>

    suspend fun createDiscussion(workspace: String? = null): Conversation

    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String? = null,
    ): Conversation

    suspend fun archive(conversationId: String)

    suspend fun rename(conversationId: String, name: String): Conversation

    suspend fun startNewSession(
        conversationId: String,
        workspace: String? = null,
    ): Session

    suspend fun changeWorkspace(conversationId: String, workspace: String): Session
}

enum class ConversationFilter { All, Channels, Discussions }

/**
 * One row in the conversation thread. The stream interleaves messages
 * with synthetic [SessionBoundary] markers in chronological order; the
 * thread screen renders boundaries as horizontal-rule delimiters and
 * de-emphasizes messages above the latest delimiter.
 */
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

### Why these specific shapes

- **`observeConversations(filter)` returns `Flow<List<Conversation>>`, not `Flow<PagingData<…>>`.** The Phase 1 fake holds a small in-memory list; Paging 3 is dead weight at this size and adds a Gradle dependency for no payoff. The remote impl in Phase 4 can switch to a paged shape if the channel list grows past ~hundreds; that would be a contract change either way.
- **`observeMessages(...)` returns `Flow<List<ThreadItem>>`, same reason.** "Paginates transparently" in the AC means the *consumer* doesn't manage pagination — not that there's lazy loading. The repository can emit a growing window internally (or switch to Paging 3 in Phase 4) without changing this signature. List shape keeps the fake trivial.
- **`ConversationFilter` is a plain enum, not a sealed class.** Three values, no associated data, no extension axis. Sealed classes are for cases with payloads or open hierarchies; neither applies.
- **`ThreadItem` is a `sealed interface` (not `sealed class`).** Both variants are `data class`es with no shared state; interface is the lighter parent that still gives exhaustive `when`.
- **`MessageItem` wraps `Message` rather than `Message` directly implementing `ThreadItem`.** Implementing the interface on `Message` would put a `data/repository/` type as the parent of a `data/model/` type — backwards layer dependency. The one-field wrapper is the smaller cost.
- **`SessionBoundary` carries both `previousSessionId` and `newSessionId`.** The thread screen needs both: previous-session id to anchor the delimiter to the visible messages above it, new-session id to render the "claude doesn't remember above the line" prompt with a stable handle. Carrying both also lets the consumer detect when two consecutive boundaries collapse into one (unlikely, but cheap to support).
- **`BoundaryReason` enum lists exactly the three triggers CLAUDE.md names: `Clear`, `IdleEvict`, `WorkspaceChange`.** Do not invent a fourth value speculatively (no `Manual`, no `Unknown`, no `Other`). If a fourth trigger lands, add it then.
- **All ID parameters are `String`, not a `ConversationId` / `SessionId` value class.** See Open Questions — the value classes do not exist on disk; #2 shipped raw `String` ids. Adopting `String` here matches the shipped model. Introducing value classes is a separate refactor across the model + every consumer; doing it in this ticket is scope creep beyond AC1 ("interface + supporting types only").

### What's deliberately absent

- **No `flowOn(...)` / dispatcher specification.** Implementations choose their own dispatcher; the contract is dispatcher-agnostic. Composing `flowOn` here would force every impl into the same dispatcher.
- **No `Result<T>` / `Either<Failure, T>` return type on the suspending mutators.** Phase 1 callers throw on programmer error (not-found ids), and the Phase 4 remote impl can throw typed network exceptions the same way. The single non-throwing path keeps consumers simple; if a use case actually needs to recover from a not-found, we add a `getConversation(id): Conversation?` query then. See Error handling.
- **No `archiveBatch`, no `searchConversations`, no `getConversation` query.** AC enumerates the operations; nothing more.
- **No nullability on `Conversation` / `Session` return values from mutators.** Successful mutation always returns a concrete entity; failure throws. A nullable return type would force every UI call site to handle a null case that can't actually happen on the happy path.
- **No `@Throws` annotation.** Kotlin doesn't enforce checked exceptions; the doc-comments on individual methods would be the place to mention thrown types, but with zero implementations to point at, that's premature documentation. Phase 1's fake spec can name its exception types when it lands.
- **No companion / factory.** Construction is Koin's job (Phase 1+); the interface stays construction-agnostic.

### Dependency wiring — `kotlinx-coroutines-core` via version catalog

`Flow` comes from `kotlinx.coroutines.flow.Flow`. Today `app/build.gradle.kts` does not declare `kotlinx-coroutines-core`; the Compose + lifecycle deps pull it onto the runtime classpath transitively, but **declaring an interface that returns `Flow<…>` in a `main/` source file requires `kotlinx-coroutines-core` on the *compile* classpath**, so the dependency must be wired explicitly. (Verify with the build: if `assembleDebug` succeeds without adding the dep — i.e. a transitive `api` configuration already exposes `kotlinx-coroutines-core` to compile — skip the wiring step; otherwise add it as below.)

Edit `gradle/libs.versions.toml`:

Add under `[versions]` (alphabetical — between `kotlinxDatetime` and `kotlin`, or wherever the alphabetical neighbour sits at the time of edit):

```toml
kotlinxCoroutines = "1.10.2"
```

Add under `[libraries]` (near the other `kotlinx-*` entry):

```toml
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
```

Edit `app/build.gradle.kts` — add to the `dependencies { }` block, alongside `implementation(libs.kotlinx.datetime)`:

```kotlin
implementation(libs.kotlinx.coroutines.core)
```

Notes:
- **Version `1.10.2`** is the latest stable on Maven Central compatible with Kotlin 2.2.x. Do not switch to an `-RC` or `-Beta`.
- **Catalog accessor.** `kotlinx-coroutines-core` → `libs.kotlinx.coroutines.core` in the build script (each `-` becomes a `.`).
- **Why `-core` and not `-android`.** The data layer must stay JVM-portable for the Compose Multiplatform walk-back (CLAUDE.md "Don't"). `kotlinx-coroutines-android` is the JVM/Android variant that adds the `Main` dispatcher; the UI layer pulls that in transitively via Compose / lifecycle. `data/` only needs `core`.
- **No additional Gradle plugin.** `kotlinx-coroutines-core` is a plain library.

If the verification step shows `Flow` already resolves without an explicit declaration (e.g. an existing dep exposes it via `api`), still add the explicit entry — it pins `data/`'s contract to a coroutines version we control rather than a transitive accident that could regress on a future BOM bump.

## State + concurrency model

The interface is dispatcher-agnostic. Concrete impls choose:
- Fake (next ticket): synchronous in-memory state, `MutableStateFlow`-backed observers, mutators run on whichever dispatcher the caller used (typically `viewModelScope` → `Main.immediate`). No `flowOn` needed; in-memory work is fast.
- Remote (Phase 4): `flowOn(Dispatchers.IO)` on the streams; mutators suspend on Ktor calls that already do their own IO-dispatcher hop.

Cancellation: cold `Flow`s. When the collector's scope cancels, the source stops producing. Mutating ops follow standard `suspend` cancellation — if the caller's `viewModelScope` is cancelled mid-flight, the op aborts and the underlying state should be left in a recoverable shape (the fake satisfies this trivially by being synchronous; the remote impl handles it in Phase 4).

There is no shared mutable state at the interface level — the contract is a set of functions, not a singleton with fields. Thread-safety is per-impl.

## Error handling

The mutating operations throw on failure; they do not wrap returns in `Result<T>`. Concrete failure modes:

- **Conversation not found** (every mutator that takes a `conversationId`) — fake throws `NoSuchElementException`; remote throws a `ConversationNotFoundException` or maps a 404 to one. Both are unchecked.
- **Promoting an already-promoted conversation** (`promote`) — fake throws `IllegalStateException`; remote maps 409 to the same.
- **Renaming with an empty name** (`rename`, `promote`) — fake throws `IllegalArgumentException` after `name.isBlank()`; remote returns 422 → same.
- **Workspace string malformed** (`changeWorkspace`, `createDiscussion`, `promote`, `startNewSession`) — out of scope for the contract; validation lives in the impl. The interface accepts any `String?`.

The streams (`observeConversations`, `observeMessages`) do not surface errors via the `Flow` value type — there's no `Result` wrapper in the list element type. A stream-level failure (e.g. Phase 4 network drop) terminates the flow with an exception that the collector handles via `catch { }` at the UI boundary. The fake never throws from a stream.

The UI surfaces errors per the existing pattern (sealed `UiState` with an `Error` variant) — that translation happens in each consuming ViewModel, not in this layer.

## Testing strategy

**No tests in this ticket.** An interface without an implementation cannot be unit-tested in isolation — there's nothing to instantiate. The next ticket (`FakeConversationRepository`) ships behaviour and the tests that cover it.

AC4 (`./gradlew assembleDebug` passes) is the entire build-time verification: the file compiles, the imports resolve, the dependency wiring is correct. Manual verification step the developer should run:

```bash
./gradlew assembleDebug
```

A successful `assembleDebug` confirms:
1. The interface and its supporting types compile against the existing `data/model/` types.
2. The `kotlinx-coroutines-core` and `kotlinx-datetime` artifacts resolve.
3. The new package is picked up by the source set.

No `./gradlew test` requirement — there are no test files. `./gradlew lint` is optional; lint has historically not flagged interface-only files.

## Open questions

- **`ConversationId` / `SessionId` value classes.** The ticket body's Technical Notes say "prefer the `ConversationId` / `SessionId` value types defined in #2 over raw `String` for method parameters." Those value classes do **not** exist on disk — #2 shipped `Conversation.id: String` and `Session.id: String`. This spec uses raw `String` to match the shipped model. If we want value classes, that's a separate refactor across `Conversation.kt`, `Session.kt`, `Message.sessionId`, and every future repository call site — explicitly out of scope here per AC3 ("interface and supporting types only"). Flag for PO: either (a) accept raw `String` in this ticket and open a follow-up to introduce value classes across `data/model/` + this interface in one consistent pass, or (b) bounce this ticket back to add value classes in a precursor.
- **Active-session inference on a fresh conversation.** `createDiscussion()` returns a `Conversation` whose `currentSessionId` must point at a real `Session`. That session has to exist in the same repository state. The fake will mint one alongside the conversation; the contract doesn't say so explicitly because it falls out of the `Conversation` schema invariants from #2. No spec change needed — flagging so the next ticket's spec handles it.
- **Whether `changeWorkspace` to the same workspace is a no-op or starts a session anyway.** AC says "returns the new session this transition creates." Strict reading: always create a new session. Pragmatic reading: same workspace → no-op, return the current session. Spec leaves this to the fake's behaviour (and the next ticket's spec); the interface signature accommodates either.
