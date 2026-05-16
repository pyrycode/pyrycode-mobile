# Spec — Structured tool-call payload for `Role.Tool` messages (#191)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:1-15` — the entire current shape. Flat `data class Message(id, sessionId, role, content, timestamp, isStreaming)` plus `enum class Role { User, Assistant, Tool }`. The whole file is 15 lines; the new `ToolCall` type lands beside `Role` in the same file.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:403-540` — the seed-message machinery. Read **especially**:
  - `seedMsg(...)` factory (line 403) and the private `SeedMessage` data class (line 410) — both need a new optional `toolCall: ToolCall? = null` parameter so seeded tool messages can carry the payload.
  - `seedChannel(...)` `historicalMessages` / `currentMessageEntries` constructions (lines 454-477) and `seedDiscussion(...)` `messageEntries` construction (lines 524-534) — three `Message(...)` constructor sites that propagate the new field.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:174-200` — `sendMessage(...)` constructs `Message(role = Role.User, …)`. Confirm the constructor call still type-checks unchanged once the new field defaults to `null` (it does, because `sendMessage` uses named args).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:294-335` — the `seed-channel-pyrycode-mobile` current-session message list. This is where the seeded tool message goes (see § Seeded fixture).
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:44-80` — `seededChannels_emitExactlyOneBoundary_betweenTwoSessions` and `seededChannels_messagesAreChronologicallyOrdered` iterate all three seeded channels. Inserting a tool message into `seed-channel-pyrycode-mobile` must not break either (one boundary, chronological ordering still hold).
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:146-180` — `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` constructs `Message(...)` positionally (`Message("m1", sessionA, Role.User, "hi", Instant.parse("..."), false)`). The new optional field at the end of the constructor must NOT break these positional calls — the default value covers them.
- `app/src/test/java/de/pyryco/mobile/data/model/MessageTest.kt:1-47` — `sample()` builds `Message` with named args and `Role.User`; `role_has_exactly_user_assistant_tool` asserts `Role.entries` is `[User, Assistant, Tool]`. **No change required** under the nullable-field shape; included so the developer can confirm by reading.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:47-58` — the `Role.Tool -> Unit` no-op arm. **Do NOT touch this file.** See § Out of scope.
- `CLAUDE.md` (project root) — re-read § Conventions and § Don't. The "test-first" convention applies; the "no real backend until Phase 4" Don't reaffirms that this is a pure data-layer change against the fake.

## Context

`#131` is in flight to build `ToolCallRow`, a composable that renders `Role.Tool` messages as collapsed summaries (`Read · path/to/file.kt`) with an expandable details view. Today, every `Message` is flat — `role: Role`, `content: String` — so `Role.Tool` messages cannot distinguish tool name from input args from output text. The repository emits no tool messages either; there is nothing for #131 to render against.

This ticket lands the data-layer shape and seeds one realistic tool message. **No UI rendering** — the existing `Role.Tool -> Unit` no-op arm at `MessageBubble.kt:56` continues to render nothing; #131 replaces it with a real call.

The ticket body explicitly hands the shape choice to the architect and pre-sizes the trade-off: nullable field stays within S; full sealed hierarchy fans out across `Message.kt` + `MessageBubble.kt` + `FakeConversationRepository.kt` (≥16 `seedMsg` / `Message(...)` call sites) + two test files, tripping PO's red lines. **This spec chooses the nullable-field shape** to stay within S; see § Why nullable field, not sealed hierarchy for the consequences for #131.

## Design

### Files

Two production edits, one test edit. No new files.

- **Edit:** `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — add `data class ToolCall`, add `toolCall: ToolCall? = null` field on `Message`.
- **Edit:** `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — extend the private `SeedMessage` data class and the `seedMsg(...)` helper with an optional `toolCall` param; propagate through `seedChannel`/`seedDiscussion`; seed one tool message in `seed-channel-pyrycode-mobile`.
- **Edit:** `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` — add one test that asserts the seeded tool message round-trips through `observeMessages` with the structured payload intact.

`MessageBubble.kt` is **not** modified — see § Out of scope.

### `ToolCall` type

A new top-level `data class` co-located with `Message` in `Message.kt`:

```kotlin
data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
)
```

Three non-null `String` fields, matching the three pieces named in the ticket (tool name, call payload, response text). All three are required — Phase 0 fake data always has all three. A nullable `output` to represent "tool call in flight, no response yet" is **not** modeled here; that's a Phase 4 concern when streaming tool calls become real, and adding it now would defend a failure mode that hasn't been observed.

`Message.kt` gains exactly two things: the new `ToolCall` type, and the new field on `Message`. The file is currently 15 lines; after the change it is approximately 23.

### `Message` shape change

Add `toolCall: ToolCall? = null` as the **last** parameter on the `Message` data class:

```kotlin
data class Message(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Instant,
    val isStreaming: Boolean,
    val toolCall: ToolCall? = null,
)
```

The field is **last** and **has a default** of `null`. This is load-bearing:

- All existing positional `Message(...)` call sites (notably `FakeConversationRepositoryTest.observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` at lines 152-155, which uses six positional args) continue to type-check unchanged — the default fills the new slot.
- All existing named `Message(name = …)` call sites in `seedChannel`, `seedDiscussion`, `sendMessage`, and `previewMessage` in `MessageBubble.kt` continue to type-check unchanged — none of them pass `toolCall`, so the default applies.
- `data class` auto-generated `copy()`, `equals`, `hashCode`, and `toString` automatically include the new field. `MessageTest.copy_overrides_named_field_and_preserves_rest`, `MessageTest.equals_and_hashCode_match_for_identical_instances`, and `MessageTest.copy_produces_a_new_instance` all continue to pass — they only assert behaviour the data-class contract preserves (the `Role.User` fixture has `toolCall = null` on both sides of the comparison).

### Invariant: `toolCall != null` iff `role == Role.Tool`

The repository must maintain this correlation: every `Role.Tool` message carries a non-null `ToolCall`, and every non-Tool message carries a null one. **Document the invariant as a one-line KDoc on the `toolCall` field**; do not enforce it via an `init` block. The project's Evidence-Based Fix Selection rule discourages shipping defenses for unobserved failure modes, and `FakeConversationRepository` is the sole `Message` constructor entry point in Phase 0 — there is no external producer that could violate the invariant. If a real client (Phase 4) introduces a path where the invariant could drift, add the check then.

KDoc shape:

```kotlin
/** Non-null iff [role] is [Role.Tool]. */
val toolCall: ToolCall? = null,
```

### `FakeConversationRepository` changes

Four mechanical edits inside this file:

1. **Extend `SeedMessage`** (line 410): add `val toolCall: ToolCall? = null` as the last field.
2. **Extend `seedMsg(...)`** (line 403): add `toolCall: ToolCall? = null` as the last parameter; pass it into the `SeedMessage(...)` constructor.
3. **Propagate in `seedChannel`** (lines 457-465 and 469-476) and **`seedDiscussion`** (lines 526-533): the three `Message(...)` constructions take `toolCall = msg.toolCall` as the last named argument. All other fields stay as they are.
4. **Seed one tool message** in `seed-channel-pyrycode-mobile`'s `currentMessages` list (lines 316-334). Place it between the existing User message at `2026-05-10T08:58:00Z` and the existing Assistant message at `2026-05-10T09:00:00Z` (the streaming one). Choose a timestamp inside that gap, e.g. `2026-05-10T08:59:00Z`. The seeded `SeedMessage` has `role = Role.Tool`, a short user-facing `content` summary string (the renderer in #131 may or may not use this; it's the existing `Message.content` slot and the seeded value should still read sensibly), and a `ToolCall` payload with realistic values like `toolName = "Read"`, `input = "app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt"`, `output = "<a small, plausible Kotlin excerpt — a few lines>"`. The developer picks the exact strings; aim for "looks like a real Claude Code Read" without inventing a long fixture.

### Seeded fixture

Insertion site: `seed-channel-pyrycode-mobile` current-session `currentMessages` list (`FakeConversationRepository.kt:316-334`), between the User at `08:58:00Z` and the streaming Assistant at `09:00:00Z`. Timestamp `2026-05-10T08:59:00Z` keeps chronological order.

One tool message is sufficient to satisfy AC3 ("at least one seeded channel … contains one or more tool messages"). Multiple tool messages can land in #131 if its render fixture needs them. **Do not** add tool messages to other channels in this ticket — keeps the diff minimal and keeps the existing per-channel test assertions (boundary count, chronological order) trivially correct.

### Test changes

Add **one** test to `FakeConversationRepositoryTest`. Existing tests need no changes — none of them assert on tool-message content, and the new tool message doesn't disturb session boundaries or chronology in `seed-channel-pyrycode-mobile`.

New test scenario (the developer writes the test code in the project's idiom — `runBlocking { … }`, `org.junit.Assert.*`, etc.):

- **Name:** `observeMessages_emitsSeededToolMessage_withStructuredPayload` (or similar).
- **Arrange:** construct a default `FakeConversationRepository()`.
- **Act:** collect the first emission of `repo.observeMessages("seed-channel-pyrycode-mobile")`.
- **Assert:**
  - Locate the tool message: `filterIsInstance<ThreadItem.MessageItem>()`, then `filter { it.message.role == Role.Tool }`. Assert exactly one.
  - On that message: `toolCall` is non-null; `toolCall.toolName`, `toolCall.input`, `toolCall.output` each match the seeded values (use the same string literals the developer chose for the seed; the test is a round-trip on the structured payload). `assertEquals` on each.

This satisfies AC4 ("observeMessages emits the seeded tool message as a ThreadItem.MessageItem with the structured payload intact — a test … locates the tool message in the emitted list and inspects toolName / input / output").

**Do not** add a test that constructs a `Message(role = Role.User, toolCall = ToolCall(...))` to assert the invariant throws — the invariant is documented via KDoc, not enforced via `init` (see § Invariant).

### `MessageTest.kt` — no changes

`MessageTest.sample()` uses `Role.User` with no `toolCall`, which is consistent with the invariant. `copy()`/`equals`/`hashCode` tests continue to pass because `data class` auto-generates over the new field uniformly. `role_has_exactly_user_assistant_tool` continues to pass — the `Role` enum is unchanged.

If the developer feels the urge to add a `ToolCall` equality/copy test for thoroughness, it's optional and not part of AC. Skipping it is fine.

## State + concurrency model

Pure data-layer change. No new flows, no new coroutines, no new dispatchers. `FakeConversationRepository.observeMessages` continues to emit `Flow<List<ThreadItem>>` over the same `MutableStateFlow<Map<String, ConversationRecord>>` it does today. The only difference is that one of the emitted `ThreadItem.MessageItem`s now wraps a `Message` with a non-null `toolCall`.

## Error handling

None. The new field has a default; the data-class machinery handles all the structural concerns. There is no I/O, no parsing, no nullable surface beyond `toolCall` itself, and no UI yet.

## Testing strategy

Unit tests only — `./gradlew test`. No instrumented test, no `ComposeTestRule`. The new test in `FakeConversationRepositoryTest` follows the existing `runBlocking { … .first() … }` idiom (see lines 18-25 for the canonical shape). `MessageTest` is unchanged.

## Out of scope

- **`MessageBubble.kt`** — do not edit. The `Role.Tool -> Unit` no-op arm at line 56 keeps `when (message.role)` exhaustive over the unchanged `Role` enum; the build stays green without touching this file. Rendering lives in #131.
- **Compile-time exhaustiveness for tool messages** — the ticket body notes that #131 "expects the existing `Role.Tool -> Unit` no-op arm in `MessageBubble.kt:56` to become a compile-time exhaustiveness error once this ticket lands." Under the nullable-field shape chosen here, **it does not** — the enum is unchanged, so the `when` arm continues to be a valid (and reachable) branch. See § Why nullable field, not sealed hierarchy for the consequence for #131.
- **Multiple seeded tool messages, tool messages in other channels, tool message in historical sessions** — one tool message in one channel's current session satisfies AC; anything beyond is for the consuming ticket (#131) to ask for.
- **`output: String?` for in-flight tool calls** — not modeled; deferred to Phase 4 streaming work.
- **`ToolCallRow` composable, replacement of the `Role.Tool -> Unit` arm, previews** — all #131.

## Why nullable field, not sealed hierarchy

The ticket body hands the architect the choice and pre-quantifies the cost of the sealed-hierarchy alternative: it requires editing `Message.kt`, `MessageBubble.kt`, and `FakeConversationRepository.kt` (≥16 `seedMsg` / `Message(...)` call sites), plus `FakeConversationRepositoryTest.kt` and `MessageTest.kt`. That trips multiple red lines (>3 production files, >10 consumer call sites needing simultaneous updates). It would force a split into a typed-shape slice and a fixture-migration slice.

Choosing nullable-field instead:

- **Cost now:** 2 production files, 1 test file, ~30 LOC, zero forced consumer edits (the default value absorbs them).
- **Cost later (when #131 wants type-level discrimination):** a follow-up ticket can promote `Message` to a sealed hierarchy with `Message.UserOrAssistant(role, content, …)` and `Message.Tool(toolCall, …)` variants. At that point the migration is the same shape the sealed-hierarchy slice would take here — except it's been deferred until there's a concrete consumer that benefits.

The trade-off the ticket body asks about — "either type-level via sealed/sibling type, or a non-null `toolCall` field on `Message`" — comes out in favour of the nullable shape because:

1. PO's size guardrail explicitly favours it: this is a one-ticket data-layer change, not a refactor cascade.
2. #131 can discriminate at the data level today: a `when (message.role) { Role.Tool -> ToolCallRow(message.toolCall!!) … }` arm works fine and the `!!` is justified by the documented invariant.
3. The "compile-time exhaustiveness" affordance #131 prefers is an ergonomic gain, not a correctness requirement. #131 can document the invariant at the call site (or introduce a tiny `requireToolCall(message): ToolCall` helper) without depending on the type system.

If, when #131 runs, the consuming agent finds the lack of compile-time discrimination genuinely painful (rather than just stylistically unfortunate), they can route back through PO to split out a sealed-hierarchy promotion ticket. That decision is theirs, not ours — by deferring the type-shape decision, this ticket avoids pre-paying a refactor cost on speculation.

## Acceptance-criteria mapping

- **AC1** (structured `toolName`, `input`, `output` fields independently accessible) — `data class ToolCall(toolName, input, output)` exposes each as a `val` on the `toolCall: ToolCall?` field of `Message`.
- **AC2** (User/Assistant semantics preserved) — the new field defaults to `null`; the data class auto-generated machinery (`copy`, `equals`, `hashCode`) handles the new field uniformly; existing repo methods unchanged; existing tests continue to pass without modification.
- **AC3** (at least one seeded channel has one or more tool messages with realistic values) — one tool message seeded in `seed-channel-pyrycode-mobile` at `2026-05-10T08:59:00Z` with realistic `toolName="Read"` / `input=<a real-looking path>` / `output=<a short Kotlin excerpt>`.
- **AC4** (`observeMessages` emits the seeded tool message with payload intact; test asserts the round-trip) — new test in `FakeConversationRepositoryTest` locates the tool message in the emitted `List<ThreadItem>` and asserts `toolCall.toolName`, `toolCall.input`, `toolCall.output` match the seeded values.

## Open questions

None. The shape and the seed placement are both committed; the developer should not need to make architectural decisions during implementation. Any judgment call (exact `output` excerpt, exact `content` summary string for the tool message) is a small fixture choice within the bounds of "realistic".
