# 246 — Wire `ThreadItem` stream into `ThreadScreen`'s `LazyColumn`

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:1-119` — full screen body. The `LazyColumn(reverseLayout = true)` at line 67-75 is what this ticket replaces; the placeholder `items(items = emptyList<Unit>()) { }` is the only thing inside it today. Note both previews construct `ThreadUiState(conversationId = …, displayName = …)` positionally with defaults — the new `items` field's default of `emptyList()` keeps these previews compiling. The preview seed this ticket adds replaces the empty-state defaults to render a populated thread.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:1-98` — full ViewModel. The `combine` at line 40-53 already collects `repository.observeMessages(conversationId)` and binds it as `items` inside the lambda. Today only `hasMessages = items.any { it is ThreadItem.MessageItem }` is derived from it; this ticket adds one line to also expose the full list on `ThreadUiState`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:124-152` — `ThreadItem` sealed interface (and the doc comment at 124-129 that fixes the **chronological** ordering invariant: "stream interleaves messages with synthetic `SessionBoundary` markers in chronological order"). The screen treats `MessageItem` as opaque per AC — no role re-dispatch.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:51-90` — confirms the chronological-order invariant in implementation: `buildThreadItems(...)` sorts by `Message.timestamp` ascending before interleaving boundaries. Index 0 of the returned list is the **oldest** item; index N is the newest. Drives the `reverseLayout` correctness analysis below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:47-57` — source of truth for role → bubble dispatch. The screen calls `MessageBubble(message = item.message)` and lets the bubble handle `Role.User` / `Role.Assistant` / `Role.Tool` internally.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:44-54` — the delimiter's public signature: `SessionBoundaryDelimiter(boundary: ThreadItem.SessionBoundary, modifier: Modifier = Modifier)`. No additional plumbing.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:1-22` — `Message` (id, sessionId, role, content, timestamp, isStreaming, toolCall), `Role { User, Assistant, Tool }`, `ToolCall(toolName, input, output)`. Preview seed constructs these inline.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:165-213` — the `sendMessage_*` cases observe `repository.observeMessages(...)` and assert on `ThreadItem.MessageItem.message`. The single new test added by this ticket mirrors this same shape against `vm.state.value.items`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `16:8` is the populated Conversation Thread Screen — the same canvas this ticket renders into. Vertical stack inside the message-list region: `MessageBubble` rows (user-aligned end with `primaryContainer`, assistant-aligned start with `surface` background, tool rows as outlined chip-style frames) interleaved with a centered `SessionBoundaryDelimiter` (horizontal divider + label + explanatory line). Rows above the delimiter render at 50% opacity in the Figma; **that opacity treatment is explicitly out of scope** — #136 owns it and is `blocked-by` this ticket. The chip, delimiter, and bubble components all already exist; this ticket only assembles them via the data stream.

## Context

`ThreadScreen.kt`'s `LazyColumn` iterates `emptyList<Unit>()` (line 74) — placeholder from the initial scaffolding in #126. All row composables (`MessageBubble`, `SessionBoundaryDelimiter`) and the data source (`ConversationRepository.observeMessages(conversationId): Flow<List<ThreadItem>>`) already exist. `ThreadViewModel` already collects the stream inside its `combine` block and binds the value as `items` in the reduction lambda — but only `hasMessages: Boolean` is derived from it; the actual `List<ThreadItem>` is discarded before reaching the screen.

This ticket exposes the already-collected list on `ThreadUiState` and replaces the placeholder iterator with a typed `when`-dispatch on the two `ThreadItem` subtypes. No new flows, no new repository surfaces, no new components.

Downstream blocked tickets:
- **#136** (above-delimiter opacity) — layers `alpha` modifiers on top of the render loop this ticket establishes.
- Empty-state copy, status row, scroll-to-bottom — all assume a populated `LazyColumn`.

Out of scope here: opacity treatment, scroll-to-bottom behavior, empty-state copy, status row, streaming/typing indicators, message append animations.

## Design

### `ThreadUiState` widening

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`

Add one field at the **end** of the `data class`:

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val items: List<ThreadItem> = emptyList(),
)
```

`items` is appended last with a `emptyList()` default. This is backwards-compatible: every existing `ThreadUiState(...)` constructor call (two in `ThreadScreen.kt`'s previews, six in `ThreadViewModelTest.kt`, plus the `stateIn(initialValue = …)` at line 56-61) uses named arguments for the named fields; the new default propagates without changes to those sites.

Rationale for `emptyList()` default: matches the empty-state of a never-subscribed `ThreadUiState`. The `stateIn(WhileSubscribed)` `initialValue` at line 57-61 inherits the default — the test `state_initialValue_isConversationIdPlaceholderBeforeSubscription` asserts the exact `ThreadUiState(conversationId = …, displayName = …)` and continues to pass because the new field's default extends the structural equality with the same `emptyList()` on both sides.

### `ThreadViewModel.combine` wiring

Same file. The `combine` lambda at line 44-53 already binds `items` (the parameter name for `repository.observeMessages(conversationId)`'s emitted value). Add **one** line — `items = items` — inside the `ThreadUiState(...)` constructor:

- The expression `items.any { it is ThreadItem.MessageItem }` at line 50 stays exactly as-is (derives `hasMessages`).
- No new collection allocation. The list reference flows straight from the repository through `combine` into state.

This is the entire ViewModel-side change for this ticket: one field on the data class, one named-argument line in the reducer.

### `ThreadScreen` `LazyColumn` body

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Replace the placeholder `items(items = emptyList<Unit>()) { }` block at line 74 with a typed `items(...)` call that:

1. Iterates **`state.items.asReversed()`** (see "Source-list reversal" below for the correctness analysis).
2. Provides a stable composite key per `ThreadItem` subtype.
3. Dispatches at the sealed-interface level only — `MessageItem` → `MessageBubble`, `SessionBoundary` → `SessionBoundaryDelimiter`.

Shape (contract — not a copy-paste; developer writes idiomatic Kotlin in the project's style):

```kotlin
items(
    items = state.items.asReversed(),
    key = { item ->
        when (item) {
            is ThreadItem.MessageItem -> "msg:${item.message.id}"
            is ThreadItem.SessionBoundary ->
                "boundary:${item.previousSessionId}->${item.newSessionId}"
        }
    },
) { item ->
    when (item) {
        is ThreadItem.MessageItem -> MessageBubble(message = item.message)
        is ThreadItem.SessionBoundary -> SessionBoundaryDelimiter(boundary = item)
    }
}
```

Add the necessary imports: `de.pyryco.mobile.data.repository.ThreadItem`, `de.pyryco.mobile.ui.conversations.components.MessageBubble`, `de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter`.

Per AC, do **not** re-dispatch by `Message.role` at the screen — `MessageBubble` owns the role → bubble selection internally (see `MessageBubble.kt:52-56`). The screen treats `MessageItem` as opaque.

### Stable keys

The `key` function must return a value that is stable across recompositions and unique per row in the displayed list.

- **`MessageItem`**: `"msg:${item.message.id}"`. `Message.id` is the canonical row identity (assigned at message creation in `FakeConversationRepository.sendMessage` and survives all state transitions).
- **`SessionBoundary`**: `"boundary:${previousSessionId}->${newSessionId}"`. Each transition is unique by construction — a session can only become "previous" once per stream and only one `newSessionId` follows it. The fake's `buildThreadItems` only emits a boundary when `prior != null && prior != message.sessionId`, which guarantees the `prev → new` pair is unique per stream position.

The `"msg:"` / `"boundary:"` prefixes namespace the two subtypes so no key collision is possible between, e.g., a message id and a session id that happen to share a string.

### Source-list reversal — `asReversed()` is required

The combination of two invariants forces a reversal here:

1. `observeMessages` returns items **chronologically ascending** — index 0 is the oldest, index N is the newest (`ConversationRepository.kt:124-129` doc; `FakeConversationRepository.kt:69` `messages.sortedBy { it.timestamp }`).
2. `LazyColumn(reverseLayout = true)` draws the **first** item at the bottom and the **last** item at the top. (This is what gives chat UIs the standard "newest at the bottom, scrolled-to-bottom anchor" behavior.)

For "newest message at the bottom of the screen," index 0 of the list passed to `items(...)` must be the **newest** item. The source list has the newest at index N, so the screen must reverse it before passing to `items(...)`.

Use `state.items.asReversed()` — a Kotlin stdlib O(1) view over the source list, no allocation, no copy. The view is itself a `List<ThreadItem>`, so it slots into `items(items = …, key = …)` without further wrapping. Stable keys are computed from the underlying items, so the reversed view's index is irrelevant for identity.

The `reverseLayout = true` flag at line 72 stays exactly as-is; AC: "Existing `reverseLayout = true` is preserved."

### Preview seed

Replace the empty-state defaults in `ThreadScreenLightPreview` / `ThreadScreenDarkPreview` with a populated `ThreadUiState.items` list. AC: "one user message, one assistant message, one tool-call message, and one `SessionBoundary` — in both light and dark variants."

Introduce a single private helper inside `ThreadScreen.kt` (top-level `private fun previewItems(): List<ThreadItem>` or similar) that constructs the seed list once and is reused by both preview composables. Build items inline with the public constructors — no `FakeConversationRepository` dependency:

- `Message(id = "u1", sessionId = "s1", role = Role.User, content = "Can you help me think through the schema migration plan?", timestamp = <fixed Instant>, isStreaming = false)` wrapped in `ThreadItem.MessageItem`.
- `Message(id = "a1", sessionId = "s1", role = Role.Assistant, content = "Sure — let me read the existing schema first.", timestamp = <later>, isStreaming = false)` wrapped in `MessageItem`.
- `Message(id = "t1", sessionId = "s1", role = Role.Tool, content = "", timestamp = <later>, isStreaming = false, toolCall = ToolCall(toolName = "read_file", input = "kitchenclaw/db/schema.ts", output = "184 lines"))` wrapped in `MessageItem`. (Per `Message.toolCall` invariant: non-null iff `role == Role.Tool`.)
- `ThreadItem.SessionBoundary(previousSessionId = "s1", newSessionId = "s2", reason = BoundaryReason.Clear, occurredAt = <later>, workspaceCwd = null)`.

Use `Instant.parse("2026-05-17T14:32:00Z")` (and small offsets via `Instant.parse(...)` literals — `kotlinx.datetime.Instant` has no infix arithmetic) for the timestamps. Order the items chronologically ascending in the seed; the screen's `asReversed()` produces the visual order that matches the Figma layout (user → assistant → tool → boundary, with newest at the bottom).

Pass the seed via:

```kotlin
ThreadUiState(
    conversationId = "seed-channel-personal",
    displayName = "kitchenclaw refactor",
    items = previewItems(),
    // existing fields keep their defaults
)
```

Both light and dark previews call the same helper. The `WorkspaceChip` continues not to render in the preview because `isPromoted = false` and `hasMessages = false` would normally trigger it, but **`hasMessages` is a default** in the preview `ThreadUiState` — the preview composable does not call into the ViewModel's reducer, so `hasMessages` stays `false` and the chip renders. To match the Figma (no chip on the populated thread), set `isPromoted = true` in the preview so the `WorkspaceChip` gate at `ThreadScreen.kt:57` evaluates to false. The display name `"kitchenclaw refactor"` in the Figma is a channel name, so `isPromoted = true` is also semantically faithful.

### Untouched state

Per AC: `connectionState` flow, `pendingWorkspacePicker`, and the existing fields on `ThreadUiState` (`hasMessages`, `workspaceLabel`, `isPromoted`, `displayName`) are **untouched**. The only `data class` change is the appended `items` field; the only `combine` change is the appended `items = items` line.

## State + concurrency model

No change. `combine(observeConversations, observeMessages, pendingWorkspacePicker)` already emits on the existing `viewModelScope` with `SharingStarted.WhileSubscribed(5_000)`; `items` rides through the same reducer. `Flow<List<ThreadItem>>` is cold, single dispatcher, no new coroutines, no manual cancellation. Snapshot semantics: each `combine` emission produces a fresh `ThreadUiState` with the latest `items` reference — Compose's structural equality on `List<ThreadItem>` (a `data class` of immutable elements) is sufficient for recomposition skipping.

## Error handling

No change. `observeMessages` returns an empty list for unknown conversation ids (per `FakeConversationRepository.kt:51-55`); the empty list flows through to the screen and renders nothing in the `LazyColumn`. No new failure modes introduced.

## Testing strategy

Unit tests (`./gradlew test`) only. Existing `ThreadViewModelTest` covers the `observeMessages → hasMessages` derivation already. Add **one** new case that asserts the full list passes through to `state.items`:

- **`state_items_reflectsObserveMessagesStream`** — Construct `FakeConversationRepository`, start the VM on `"seed-channel-personal"` (the fake's seeded channel has multiple seeded messages, per the existing `state_chipFields_reflectChannelAndMessagePresence` test at line 273-301 which asserts `hasMessages = true` after the same setup). Collect `vm.state`. After `advanceUntilIdle()`, assert `vm.state.value.items.isNotEmpty()` and that the first element is a `ThreadItem.MessageItem`. Mirrors the shape of `sendMessage_nonBlankText_appendsToConversation` at line 192-213 — same `runTest` + `UnconfinedTestDispatcher` + collector-cancel pattern.

That's the entire test scope for this ticket: a single passthrough assertion. Compose-side rendering correctness is covered by the previews (light and dark) and by visual inspection — no `ComposeTestRule` instrumentation needed for this scope (no scroll behavior, no interaction). Future tickets (#136 opacity, scroll-to-bottom) will own their own Compose-side tests.

Existing tests should all continue passing unchanged. The new `items` field is appended with a default; no existing test constructs `ThreadUiState` with positional arguments past the defaults.

## Acceptance Criteria mapping

- AC1 (`items: List<ThreadItem>` on `ThreadUiState`, default `emptyList()`, populated from existing `combine`) → "`ThreadUiState` widening" + "`ThreadViewModel.combine` wiring" sections.
- AC2 (`LazyColumn` iterates `state.items` with stable keys, sealed-interface dispatch only) → "`ThreadScreen` `LazyColumn` body" + "Stable keys" sections.
- AC3 (`reverseLayout = true` preserved; chronological-order semantics respected) → "Source-list reversal — `asReversed()` is required" section; verdict: source-list reversal **is** needed because `observeMessages` is chronologically ascending and `reverseLayout = true` puts the first item at the bottom; `state.items.asReversed()` is the resolution.
- AC4 (`connectionState`, `pendingWorkspacePicker`, existing fields untouched) → "Untouched state" section.
- AC5 (Compose preview with all four item types in light + dark) → "Preview seed" section.

## Open questions

None — every AC has a concrete instruction above. Two judgment calls the developer may surface in PR review:

1. **Key format.** `"msg:${id}"` / `"boundary:${prev}->${new}"` is one of several reasonable encodings. The developer may collapse to a single sealed `ThreadItemKey` if they prefer (e.g. by adding a `val key: String` to `ThreadItem` subtypes) — but the spec recommends the string-prefix form because it stays local to the screen and doesn't widen the data-layer contract. The change is a refactor; not in scope here.
2. **Preview seed extraction.** If `previewItems()` grows past ~25 lines, the developer may extract it to a sibling file (`ThreadScreenPreviewData.kt`). For four items it stays inline.
