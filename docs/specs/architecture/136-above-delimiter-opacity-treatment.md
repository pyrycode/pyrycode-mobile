# 136 — Above-delimiter message opacity treatment

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:75-97` — the `LazyColumn(reverseLayout = true)` body that #246 just landed. The `items(...)` block here is the only thing this ticket modifies; iteration is over `state.items.asReversed()` with stable per-subtype keys. Both the cutoff lookup and the per-row alpha wrap go inside this block.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:107-197` — `previewItems()` + `ThreadScreenLightPreview` / `ThreadScreenDarkPreview`. The new preview (AC6) sits alongside these and re-uses the same `ThreadUiState(...)` / `ThreadScreen(...)` scaffolding with a richer `items` list.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:124-152` — `ThreadItem` sealed interface and the doc comment that fixes the invariant: "stream interleaves messages with synthetic `SessionBoundary` markers **in chronological order**." Index 0 is the oldest; the last index is the newest. The "most recent boundary" cutoff math depends entirely on this invariant.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:44-105` — the rendered delimiter row. Confirms it has no internal opacity / blending state; wrapping it in a parent `Modifier.alpha(...)` is sufficient to dim nested delimiters (AC2's "nested `SessionBoundaryDelimiter` rows above the cutoff").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:47-117` — `MessageBubble` dispatches `Role.User → UserMessageBubble`, `Role.Assistant → AssistantMessage`, `Role.Tool → ToolCallRow`. None of them maintain alpha state of their own — a `Modifier.alpha(...)` on the row wrapper passes through to all three variants. The streaming caret in `StreamingAssistantBody` (lines 119-141) also inherits the wrapper alpha, which is the correct behavior on the rare edge where a streaming message ends up above the cutoff.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:52-100` — the tool-row `Surface` is `clickable`. `Modifier.alpha(...)` on a parent does not disable interaction (alpha is a render-only effect), but dimmed tool rows above the cutoff remain expandable. That matches the "still legible, can scroll up and re-read" intent in the user story; no AC requires disabling above-cutoff interaction.
- `docs/specs/architecture/246-wire-thread-items-into-lazycolumn.md` — the immediate predecessor spec. The "Source-list reversal — `asReversed()` is required" section is the source of truth for the chronological-vs-reversed indexing convention this ticket inherits.
- `docs/specs/architecture/135-session-boundary-delimiter.md` (skim) — the delimiter composable spec. No direct dependency on its internals; just confirms the public signature `SessionBoundaryDelimiter(boundary: ThreadItem.SessionBoundary, modifier: Modifier = Modifier)`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `16:8` is the populated Conversation Thread Screen. The four rows above the centered `SessionBoundaryDelimiter` (one user `MessageBubble`, one assistant `MessageBubble`, one `ToolCallRow`, one assistant `MessageBubble`) render at `opacity: 0.5` in the Figma frame (`opacity-50` on each row's `Frame`); the four rows at and below the delimiter render at full opacity. This ticket reproduces that treatment, parameterized so any number of `SessionBoundary` markers in the stream produces the same result against the most recent boundary. The user story specifies `alpha = 0.55f` — the spec uses that value, not the Figma's 0.5, to keep above-cutoff text comfortably legible against the Material 3 dark surface.

## Context

Visual de-emphasis for messages above the most recent `ThreadItem.SessionBoundary` in `ThreadScreen.kt`'s `LazyColumn`. Pairs with the delimiter composable from #135 and the render-loop integration from #246.

The chronological-vs-reversed indexing question is already resolved by #246: `state.items` is chronologically ascending; the screen iterates `state.items.asReversed()` so the newest row anchors to the bottom under `reverseLayout = true`. "Above the most recent boundary" is a **chronological** concept (older messages, lower index in `state.items`), independent of layout reversal. The cutoff math operates on chronological indices and the per-row alpha is derived inside the `items(...)` lambda after converting the reversed iteration index back to a chronological index.

This is a presentation-only change. No ViewModel surface widens; no `ThreadUiState` field changes; no new public types. The cutoff is a pure function of `state.items`, so it lives in the composable and is `remember`'d against the list reference.

Out of scope: any change to data-layer types, to `ThreadUiState`, to `ThreadViewModel`, to the existing `SessionBoundaryDelimiter`, `MessageBubble`, or `ToolCallRow` composables, or to the keying scheme inside `items(...)`.

## Design

### Cutoff computation — pure helper

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Add a top-level `internal` (or file-private) helper next to `previewItems()` at the bottom of the file:

```kotlin
internal fun mostRecentSessionBoundaryIndex(items: List<ThreadItem>): Int =
    items.indexOfLast { it is ThreadItem.SessionBoundary }
```

Semantics:
- Returns `-1` when `items` is empty or contains no `SessionBoundary` (per `List.indexOfLast` contract).
- Returns the chronological index of the most recent (latest) `SessionBoundary` otherwise.

Why a named helper rather than an inline lambda: it is pure, has well-defined edge cases, and is the only piece of business logic in this ticket worth unit-testing. The wrap (apply alpha to a `Box`) is purely structural; the cutoff math is the only thing that can produce a wrong result. Keeping it as a named, internally-visible function lets the test exercise it directly without spinning up a `ComposeTestRule`.

`internal` (not `private`) so the unit test under `app/src/test/...` can call it. The cost of the wider visibility is negligible: the file is the only consumer in production code; nothing else in the module has reason to reach for it.

### Per-row alpha — `LazyColumn` body

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`, replacing the body of the existing `items(...)` block at lines 82-96.

Switch the iteration to `itemsIndexed(...)` so the reversed-list index is available inside the lambda. Reuse the existing key function. Around the existing `when (item)` dispatch, wrap each row in a `Box` modified by `Modifier.alpha(alphaForRow)`. Compute `alphaForRow` inline against the cutoff:

Shape (contract — not a copy-paste; developer writes idiomatic Kotlin in the project's style):

```kotlin
val reversedItems = state.items.asReversed()
val cutoffChronologicalIndex = remember(state.items) {
    mostRecentSessionBoundaryIndex(state.items)
}
LazyColumn(
    modifier = ...,
    reverseLayout = true,
) {
    itemsIndexed(
        items = reversedItems,
        key = { _, item ->
            when (item) {
                is ThreadItem.MessageItem -> "msg:${item.message.id}"
                is ThreadItem.SessionBoundary ->
                    "boundary:${item.previousSessionId}->${item.newSessionId}"
            }
        },
    ) { reversedIndex, item ->
        val chronologicalIndex = state.items.size - 1 - reversedIndex
        val rowAlpha =
            if (chronologicalIndex < cutoffChronologicalIndex) AboveDelimiterAlpha else 1f
        Box(modifier = Modifier.alpha(rowAlpha)) {
            when (item) {
                is ThreadItem.MessageItem -> MessageBubble(message = item.message)
                is ThreadItem.SessionBoundary -> SessionBoundaryDelimiter(boundary = item)
            }
        }
    }
}
```

Comparison is strict `<`: rows whose chronological index equals the cutoff (i.e. the boundary itself) render at full opacity (AC1 — "rows at or after that boundary render at full opacity"). The latest boundary is itself the cutoff line and stays full-opacity; only older boundaries (and their preceding rows) are dimmed (AC5).

When `cutoffChronologicalIndex == -1` (no boundaries — AC3), every row's `chronologicalIndex >= 0` is also `>= 0`, never `< -1`, so every row renders at `1f`. The single-boundary case (AC4) and multi-boundary case (AC5) both reduce to "everything strictly before the latest boundary's chronological index is dimmed."

Replace the existing `items(...)` import with `itemsIndexed`:
`import androidx.compose.foundation.lazy.itemsIndexed`

Add imports:
- `androidx.compose.foundation.layout.Box`
- `androidx.compose.runtime.remember`
- `androidx.compose.ui.draw.alpha`

Remove the now-unused `import androidx.compose.foundation.lazy.items`.

### Constants

Top of `ThreadScreen.kt`, alongside any existing private file-level constants (the file currently has none at the top, but the components in the same package follow the convention of `private val`s above the first composable — match that):

```kotlin
private const val AboveDelimiterAlpha = 0.55f
```

A named constant rather than the literal `0.55f` because (a) the user story explicitly cites the value, (b) the same value is referenced by the cutoff comparison and the (single) production call site, and (c) future "design-token-driven value" replacement (the ticket says "or equivalent design-token-driven value") only needs to change one site. No token system exists today; the constant is the placeholder.

### Preview seed (AC6)

Same file. Add a second `previewItems`-style helper that returns a list containing **at least two** `SessionBoundary` markers so the "older boundary is itself dimmed" behavior (AC5) is observable in the preview. Suggested shape (the developer is free to vary the content as long as the structural invariant holds):

- Message (user, session `s0`, oldest timestamp) — will be dimmed (above older boundary).
- Message (assistant, session `s0`) — will be dimmed.
- `SessionBoundary` (`s0` → `s1`, `BoundaryReason.Clear`) — will be dimmed (older boundary).
- Message (user, session `s1`) — will be dimmed (above latest boundary but after the older one).
- Message (assistant, session `s1`) — will be dimmed.
- Tool message (session `s1`, `ToolCall(toolName = "read_file", ...)`) — will be dimmed.
- `SessionBoundary` (`s1` → `s2`, `BoundaryReason.WorkspaceChange`, `workspaceCwd = "~/Workspace/Projects/KitchenClaw"`) — full opacity (the cutoff).
- Message (user, session `s2`) — full opacity.
- Message (assistant, session `s2`) — full opacity.

Add two `@Preview` composables (light + dark) that pass this fixture into `ThreadScreen(...)` via `ThreadUiState(..., items = previewItemsWithBoundaries())`. Mirror the existing `ThreadScreenLightPreview` / `ThreadScreenDarkPreview` setup — same `ThreadUiState` shape (`isPromoted = true`, `displayName = "kitchenclaw refactor"`, `connectionState = ConnectionState.Connected`, etc.) so the `WorkspaceChip` gate stays false and the screen is just the populated list.

Preview names: `"Thread — Above-delimiter dim · Light"` and `"Thread — Above-delimiter dim · Dark"`. Both use `widthDp = 412` to match the existing previews. The dark preview wraps in `PyrycodeMobileTheme(darkTheme = true)`; the light preview in `PyrycodeMobileTheme(darkTheme = false)`.

The existing single-boundary `previewItems()` and its two `@Preview` composables stay as-is — they continue to exercise the typical "one boundary" path. The new preview is additive.

Use `Instant.parse(...)` literals with strictly ascending timestamps (the screen's `asReversed()` produces visual order, so chronological order in the source list is what's relevant to the cutoff math; no special ordering required for the preview to work, but ascending matches the production invariant).

### Existing behavior preserved

- `LazyColumn(reverseLayout = true)` stays exactly as-is.
- `state.items.asReversed()` stays as-is — the `O(1)` view from #246 continues to drive iteration.
- Stable keys (`"msg:..."`, `"boundary:..."`) stay as-is.
- The two existing `@Preview` composables (`ThreadScreenLightPreview`, `ThreadScreenDarkPreview`) and their `previewItems()` seed stay as-is.
- No change to `ThreadUiState`, `ThreadViewModel`, or any data-layer type.
- No change to `MessageBubble`, `ToolCallRow`, or `SessionBoundaryDelimiter` internals — the alpha is applied externally by the wrapping `Box`.

## State + concurrency model

No change. The cutoff index is derived synchronously inside composition. `remember(state.items)` caches the scan against the list reference (the `combine` reducer in `ThreadViewModel` produces a fresh `ThreadUiState` each emission, so a new `items` reference triggers re-scan — which is the correct invalidation key). No new coroutines, flows, or scopes.

The `Modifier.alpha(...)` modifier introduces a `graphicsLayer` per dimmed row (Compose's standard implementation). This is acceptable for chat UIs at this scale: `LazyColumn` composes only visible rows, and per-row graphicsLayer overhead is small relative to row content. Compose's standard idiom for content-level opacity; no perf concern at this scope.

## Error handling

None. The cutoff is a pure function with no failure modes: `indexOfLast` returns `-1` on no match and never throws. The alpha computation is total over all `Int` inputs.

## Testing strategy

Unit tests only (`./gradlew test`). New test file:

`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenCutoffTest.kt`

Direct unit tests against `mostRecentSessionBoundaryIndex`. Bullet-pointed scenarios (developer writes the test code in the project's testing idiom — JUnit4 + assertEquals against `Int`):

- `emptyList_returnsNegativeOne` — `mostRecentSessionBoundaryIndex(emptyList())` returns `-1`.
- `messagesOnly_returnsNegativeOne` — A list of three `ThreadItem.MessageItem`s with no boundaries returns `-1`.
- `singleBoundary_returnsItsChronologicalIndex` — `[msg, msg, boundary, msg]` returns `2`.
- `multipleBoundaries_returnsIndexOfLatest` — `[msg, boundary, msg, boundary, msg, boundary, msg]` returns `5` (the latest boundary's chronological index).
- `boundaryAtFirstPosition_returnsZero` — `[boundary, msg, msg]` returns `0`.
- `boundaryAtLastPosition_returnsLastIndex` — `[msg, msg, boundary]` returns `2` (so nothing is dimmed — every preceding message is "above" but the only boundary is also the latest; full coverage of the boundary-itself-equals-cutoff case).

These six cases together fully exhaust the AC matrix (AC3 → first two; AC4 → third + sixth; AC5 → fourth; AC1's "rows at or after the boundary render at full opacity" → sixth specifically, since the boundary row's chronological index equals the cutoff, and the strict `<` comparison excludes it from dimming).

Construct items inline using the public constructors:
- `ThreadItem.MessageItem(Message(id = "...", sessionId = "...", role = Role.User, content = "...", timestamp = Instant.parse("..."), isStreaming = false))`
- `ThreadItem.SessionBoundary(previousSessionId = "...", newSessionId = "...", reason = BoundaryReason.Clear, occurredAt = Instant.parse("..."), workspaceCwd = null)`

No fakes, no coroutines, no test dispatcher — `mostRecentSessionBoundaryIndex` is synchronous and pure.

**Compose-side rendering correctness** (the per-row `Box(Modifier.alpha(...))` wrap, the strict `<` comparison flowing through to actual rendered opacity) is covered by the new `@Preview` composables. No `ComposeTestRule` instrumentation in this ticket — there is no interactive behavior to assert and no scroll behavior to test. If a future ticket layers on top (e.g. animating the alpha transition when a new boundary arrives), it owns its own Compose-side test.

Existing `ThreadViewModelTest` is unaffected; no ViewModel surface changes.

## Acceptance Criteria mapping

- AC1 (rows strictly before the most recent boundary render at `alpha = 0.55f`; rows at or after render at full opacity) → "Per-row alpha — `LazyColumn` body" section, specifically the strict `<` comparison + the `AboveDelimiterAlpha = 0.55f` constant. Tested by `boundaryAtLastPosition_returnsLastIndex` (boundary's own chronological index returned → strict `<` excludes it from dimming).
- AC2 (uniform treatment across user `MessageBubble`, assistant `MessageBubble`, `ToolCallRow`, nested `SessionBoundaryDelimiter`) → the `Box(Modifier.alpha(...))` wraps the entire `when (item)` dispatch, so every row variant inherits the alpha. Confirmed by the "Files to read first" notes on each component (none holds internal alpha state).
- AC3 (zero boundaries → every row full opacity) → cutoff = `-1` from `indexOfLast`; every chronological index `>= 0`, so the `< -1` check is always false. Tested by `emptyList_returnsNegativeOne` and `messagesOnly_returnsNegativeOne`.
- AC4 (exactly one boundary → only rows above are dimmed) → tested by `singleBoundary_returnsItsChronologicalIndex`.
- AC5 (multiple boundaries → only the latest acts as cutoff; older boundaries are themselves dimmed) → tested by `multipleBoundaries_returnsIndexOfLatest`. The preview seed includes two boundaries so the older-boundary-is-dimmed behavior is visually inspectable.
- AC6 (new Compose preview with ≥ two `SessionBoundary` markers, light + dark) → "Preview seed (AC6)" section.

## Open questions

None — every AC is mapped to a concrete instruction above. One judgment call the developer may surface in PR review:

1. **`itemsIndexed` vs. capturing chronological index in the key.** The spec uses `itemsIndexed(...)` to get the reversed-list index inside the lambda, then converts to chronological. An alternative is to enrich the key to include the chronological index and read it back via the item — but that conflates row identity with position and is exactly the anti-pattern Compose's `key =` lambda is designed to avoid. Stick with `itemsIndexed`.
