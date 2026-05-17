# Spec — auto-scroll thread to follow streaming messages (#185)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:107-142` — current `LazyColumn(reverseLayout = true)` block. The list is fed `reversedItems = state.items.asReversed()`, so reversed index `0` = newest chronological item, which under `reverseLayout = true` is rendered at the **bottom** of the viewport. The column currently has no `state` argument; this ticket hoists a `rememberLazyListState()` and threads it through. No structural change to the `itemsIndexed { ... }` body.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:42-57` — `ThreadScreen` signature. **Unchanged** — auto-scroll is a screen-internal concern; no new parameters, no new callbacks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:104-117` — the `AssistantMessage` dispatch on `message.isStreaming`. When `true`, it routes to `StreamingAssistantBody` (added in #184) which character-reveals `content` via `produceState`. This is what makes the bubble's measured height grow over time. ThreadScreen does **not** need to know about the reveal mechanism — it observes the resulting layout-info size change on item 0.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5-13` — `Message.isStreaming: Boolean`. This is the flag ThreadScreen scans `state.items` for.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadItem.kt` — the `ThreadItem.MessageItem` / `ThreadItem.SessionBoundary` sealed shape. Auto-scroll cares only about `MessageItem` whose `message.isStreaming = true`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:392-405` — the one currently-seeded `isStreaming = true` message in the `seed-channel-pyrycode-mobile` channel. Demo verification of this ticket lives in that thread; no changes to the seed.
- `docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md:281` — explicit hand-off note: "**`StreamingAssistantBody` lifetime when scrolled off-screen.** … Phase 4 may want to hoist `revealedLength` into the `ViewModel` so it persists across scroll-induced disposals; that's a sibling-of-#185 concern, not this ticket's." This spec confirms #185 does **not** hoist `revealedLength`; it observes layout-info size on item 0 instead.
- `docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md:33` — #184's scope contract: "**Only the streaming visual.** Auto-scroll behaviour that keeps the thread anchored as the message grows is sibling ticket #185 — explicitly out of scope here (ticket body, Context paragraph). No `LazyListState.scrollToItem` calls inside this composable." This spec is the redemption of that promise; all `LazyListState` work lives in `ThreadScreen.kt`.
- `gradle/libs.versions.toml` — confirm no new dependency. Compose BOM `2026.02.01` is recent enough that `NestedScrollSource.UserInput` (1.7+) and `LazyListState.layoutInfo.visibleItemsInfo[*].size` are stable. No additions needed.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Conversation Thread frame `16:8`. No new visual elements introduced — this ticket is pure scroll behaviour on the existing `LazyColumn`. The Figma's streaming end-state (last assistant bubble at `16:54` → `16:56`, mid-reveal with the `▎` caret) is the same demo target #184 reproduced; this ticket keeps that bubble's growing bottom edge anchored at the viewport bottom as it streams.

## Context

`#184` shipped the streaming visual (token-reveal + blinking caret) and explicitly deferred auto-scroll to this ticket. The seeded `isStreaming = true` assistant message in `seed-channel-pyrycode-mobile` reveals over ~10 s at 50 cps; without auto-scroll, the bubble's growing bottom edge can drift below the visible window (especially as `MarkdownText` re-flows across paragraph / list-item / code-fence boundaries during the reveal), forcing the user to scroll manually to follow the reply.

This ticket adds the auto-scroll: while any thread item has `isStreaming = true`, the `LazyColumn` stays anchored to the bottom (reverse-layout's newest-item position) as the streaming bubble grows. A user-initiated scroll yields the auto-follow; returning to the bottom resumes it.

Scope contract:

- **Behaviour-only change.** No new composables, no new public types, no `ViewModel` surface change. All wiring lives inside `ThreadScreen.kt`.
- **Reverse-layout's "newest" is index 0.** `state.items.asReversed()` puts the chronologically-last item at reversed index 0; `LazyColumn(reverseLayout = true)` renders that at the viewport bottom. "Scroll to the newest content" therefore means `scrollToItem(0)` with `firstVisibleItemScrollOffset = 0`.
- **No `revealedLength` hoisting.** Per #184's open question, hoisting the reveal progress into `ThreadViewModel` is a Phase-4 concern (so the reveal survives scroll-induced disposal of `StreamingAssistantBody`). Phase 0's auto-scroll observes item 0's measured size via `LazyListState.layoutInfo`, not the internal reveal state. This keeps #184's encapsulation intact.
- **User-scroll detection via nested-scroll, not by inferring from offset drift.** When item 0 grows naturally, the anchor offset can transiently drift before the next layout pass; that's not a user scroll. A `NestedScrollConnection` that filters on `NestedScrollSource.UserInput` is the unambiguous source.
- **No animation.** `scrollToItem(0)` is the right primitive (instant, no flicker); `animateScrollToItem(0)` is not — animating during ~50 reveal ticks/sec produces visible jitter and the animation's duration would overlap subsequent ticks.
- **No new tests.** This codebase has no `ComposeTestRule` setup; #128 / #129 / #130 / #184 all relied on `@Preview` + manual demo. AC verification is by running the app against the existing streaming seed.

## Design

### Hoist `LazyListState` in `ThreadScreen`

In the `else` branch at `ThreadScreen.kt:106` (the `state.hasMessages == true` arm that renders the `LazyColumn`), introduce a hoisted state:

```kotlin
val listState = rememberLazyListState()
```

Pass it to the `LazyColumn`:

```kotlin
LazyColumn(
    state = listState,
    modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .nestedScroll(autoScrollNestedScroll),  // see § NestedScroll
    reverseLayout = true,
) { ... }
```

No change to the `itemsIndexed { ... }` body, the `key` function, the `cutoffChronologicalIndex` logic, or any other rendering surface.

### Derive `hasStreamingMessage`

Adjacent to `cutoffChronologicalIndex` (line 108–109):

```kotlin
val hasStreamingMessage by remember(state.items) {
    derivedStateOf {
        state.items.any { it is ThreadItem.MessageItem && it.message.isStreaming }
    }
}
```

`derivedStateOf` wrapped in `remember(state.items)` so the scan only re-runs when the items list reference changes, not on every recomposition. AC4 ("no auto-scroll happens in threads where no message is streaming") gates everything below this flag.

### User-scroll detection (`NestedScrollConnection`)

A `NestedScrollConnection` that flips a `userScrolledAway: MutableState<Boolean>` when the scroll source is `NestedScrollSource.UserInput` (drag or fling — both user-initiated). `scrollToItem` is a direct-state mutation and does **not** generate `UserInput` nested-scroll events, so the auto-pin loop (next section) cannot trip its own user-scroll flag.

Signature + behavior (signature + 1-line summary, **not** the body):

- `var userScrolledAway by remember { mutableStateOf(false) }` — state held at the `ThreadScreen`-`Column` scope.
- `val autoScrollNestedScroll = remember { ... }` — a `NestedScrollConnection` whose `onPreScroll(available, source)` sets `userScrolledAway = true` iff `source == NestedScrollSource.UserInput` and `available.y != 0f`. The `available.y != 0f` guard avoids false positives from zero-delta scroll events. Always returns `Offset.Zero` (does not consume).

Attached via `Modifier.nestedScroll(autoScrollNestedScroll)` on the `LazyColumn`.

### Resume auto-scroll when user returns to bottom

AC2 explicitly allows resuming auto-scroll on manual return to the bottom. A small `LaunchedEffect` watches the bottom-anchored predicate and clears `userScrolledAway`:

```kotlin
LaunchedEffect(listState) {
    snapshotFlow {
        listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
    }.collect { atBottom ->
        if (atBottom) userScrolledAway = false
    }
}
```

Keyed on `listState` (stable instance per composition) so the effect is created once per screen entry and cancels on exit. The predicate uses `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0` — in reverse layout, this is the bottom anchor exactly. `snapshotFlow` emits on every change and re-derives only when the read state changes, so the cost is one collector for the lifetime of the screen.

### Auto-pin loop

A second `LaunchedEffect`, keyed on `hasStreamingMessage`, observes the bottom item's measured size and re-pins the anchor whenever item 0's height grows:

```kotlin
LaunchedEffect(hasStreamingMessage, listState) {
    if (!hasStreamingMessage) return@LaunchedEffect
    snapshotFlow {
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0
    }
        .distinctUntilChanged()
        .collect {
            if (!userScrolledAway) {
                listState.scrollToItem(0)
            }
        }
}
```

How it works:

- `LazyListState.layoutInfo.visibleItemsInfo` is a snapshot-backed list re-derived after each layout pass; the entry at `index == 0` carries the bottom item's measured `size` (in pixels). When `StreamingAssistantBody` reveals a new character, `MarkdownText` re-flows, item 0's measured size changes, and the `snapshotFlow` emits.
- `distinctUntilChanged()` drops no-op re-emissions (layout passes that don't change item 0's size).
- The `!userScrolledAway` guard satisfies AC2: while the user has manually scrolled away from the bottom, the auto-pin is suppressed; once they return to the bottom, the resume-effect above clears the flag and the next size-change re-pins.
- When `hasStreamingMessage` flips to `false`, the `LaunchedEffect` re-launches and exits early; the collector is cancelled. AC3 satisfied — normal scroll behaviour resumes.
- The effect itself is gated by `hasStreamingMessage`, so AC4 holds: in non-streaming threads, no collector runs.

`scrollToItem(0)` is the right primitive:

- Instant (no animation), so 50 calls/sec during the reveal don't queue or stutter.
- A no-op when already at `(0, 0)` — cheap when the natural reverseLayout anchor already pinned the bottom.
- Does not generate a `NestedScrollSource.UserInput` event, so it cannot recursively trip `userScrolledAway`.

### Where the new code lives in `ThreadScreen.kt`

All additions slot into the `else` branch at line 106 (the `state.hasMessages == true` arm), **above** the `LazyColumn` block:

```text
} else {
    val reversedItems = state.items.asReversed()
    val cutoffChronologicalIndex = remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
    // <NEW> listState + hasStreamingMessage + userScrolledAway + autoScrollNestedScroll
    // <NEW> LaunchedEffect(listState) { … snapshotFlow … reset userScrolledAway on isAtBottom }
    // <NEW> LaunchedEffect(hasStreamingMessage, listState) { … snapshotFlow on visibleItemsInfo[0].size … }
    LazyColumn(
        state = listState,                                         // <NEW>
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .nestedScroll(autoScrollNestedScroll),                 // <NEW>
        reverseLayout = true,
    ) { … }
}
```

Net additions: ~25–35 LOC. No other code in `ThreadScreen.kt` changes (preview helpers, `mostRecentSessionBoundaryIndex`, the `state.hasMessages == false` branch, the `Scaffold` chrome, the `StatusSheet` block — all unchanged).

### `ThreadViewModel` — no changes

The auto-scroll is screen-internal. `ThreadUiState`, `ThreadEvent`, `sendMessage`, `retry`, etc. are unchanged. No new flow, no new event, no new field.

### `MessageBubble` / `StreamingAssistantBody` — no changes

#184's encapsulation stands: the reveal lives inside `StreamingAssistantBody`'s private `produceState`. ThreadScreen does not read `revealedLength`; it observes the resulting layout-info size on item 0. The two layers stay decoupled.

### Module wiring

None. No new Koin module, no new dependency, no new public API. All new imports are already on the classpath:

- `androidx.compose.foundation.lazy.rememberLazyListState`
- `androidx.compose.runtime.derivedStateOf` / `LaunchedEffect` / `mutableStateOf` / `remember` / `snapshotFlow`
- `androidx.compose.ui.geometry.Offset`
- `androidx.compose.ui.input.nestedscroll.NestedScrollConnection`
- `androidx.compose.ui.input.nestedscroll.NestedScrollSource`
- `androidx.compose.ui.input.nestedscroll.nestedScroll`
- `kotlinx.coroutines.flow.distinctUntilChanged`

## State + concurrency model

Three composition-scoped pieces of state plus two `LaunchedEffect` coroutines, all owned by `ThreadScreen` and tied to its composition lifetime:

- **`listState: LazyListState`** — `rememberLazyListState()`. Survives recomposition within the screen entry. Cancelled / re-created on configuration change (no `rememberSaveable` — scroll position isn't preserved across process death today, and adding it is out of scope).
- **`userScrolledAway: MutableState<Boolean>`** — `remember { mutableStateOf(false) }`. Flips `true` on user drag/fling (via `NestedScrollConnection`); flips `false` when the snapshotFlow on `isAtBottom` observes the user returning to the bottom.
- **`hasStreamingMessage: State<Boolean>`** — `remember(state.items) { derivedStateOf { … } }`. Pure derivation from `state.items`; no coroutine.
- **`LaunchedEffect(listState) { snapshotFlow { isAtBottom }.collect { … } }`** — runs for the lifetime of the screen entry. Single-collector cost.
- **`LaunchedEffect(hasStreamingMessage, listState) { snapshotFlow { item0.size }.collect { … } }`** — re-launched when `hasStreamingMessage` flips. When it flips to `false`, the early `return@LaunchedEffect` cancels the collector. The coroutine itself is short-lived in non-streaming state.

Dispatcher: both effects run on `Dispatchers.Main` (Compose's effect dispatcher); both do trivial work (predicate checks, one `scrollToItem` call). Frame budget: `scrollToItem` is synchronous and O(1) when already pinned; under streaming at 50 cps the worst case is ~50 invocations/sec, each microsecond-scale. No risk of jank.

**Cancellation.** `LaunchedEffect` semantics: cancel-on-key-change and cancel-on-leave-composition. Both effects honor that — the resume-effect cancels on screen exit; the auto-pin effect cancels on screen exit or on `hasStreamingMessage` flipping. The `snapshotFlow` collectors cancel with their parent coroutine.

**Recomposition stability.** `ThreadUiState` is a `data class` with stable fields (`String`, `Boolean`, `Int`, `List<ThreadItem>`, `Model` enum); Compose's stability inference treats it as stable. The `else` branch's new variables (`listState`, `userScrolledAway`, `hasStreamingMessage`) are state holders that participate in fine-grained recomposition — only the consumers (the `LaunchedEffect` keys, the `LazyColumn`'s `state` parameter, the `nestedScroll` modifier) re-evaluate when they change.

**Why `snapshotFlow` over `withFrameMillis` polling.** Polling at 60 Hz unconditionally would do work every frame even when item 0 hasn't moved. `snapshotFlow` only emits when the read state changes; combined with `distinctUntilChanged()`, the auto-pin runs exactly once per character revealed (when the layout pass produces a new size). Same effective behavior, far fewer wasted reads.

**Why not `LazyListState.canScrollForward` / `canScrollBackward`.** These are convenience flags for "is there content past the viewport edge"; they don't track "is the user at the anchor." The `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0` pair is the precise predicate.

## Error handling

N/A. No I/O, no parsing, no failure modes. All operations are direct state reads and Compose-internal scroll state mutations; `scrollToItem(0)` on a `LazyListState` is total (accepts any non-negative index; out-of-range clamps). `produceState`-style cancellation is handled by Compose. The composable never throws.

No telemetry, no logs. Auto-scroll is a visual; no observability hook is added.

## Testing strategy

### Unit tests — none

Same precedent as `#128` / `#129` / `#130` / `#184`: this codebase has no Robolectric setup and no Compose JVM-host on `testImplementation`. The behaviour is pure Compose state + scroll-state mutations, not verifiable without a Compose host. Building that infrastructure is out of scope for a ~30-LOC ticket.

### Compose UI tests — none

Same precedent: no `ComposeTestRule` setup in the codebase. Visual verification is by running the app:

1. **`./gradlew installDebug`** to a device/emulator.
2. Open `seed-channel-pyrycode-mobile` (the demo channel with the seeded `isStreaming = true` assistant message from #184).
3. **AC1** — observe the streaming bubble grows from short → ~480 chars over ~10 s; the LazyColumn keeps the bottom of the bubble visible the whole time, without manual scrolling.
4. **AC2** — during streaming, drag the list upward (away from the bottom). The auto-scroll yields immediately; the view does not snap back. Optional: drag back to the bottom and confirm auto-scroll resumes for the remainder of the reveal.
5. **AC3** — wait for the reveal to complete (caret continues blinking but content stops growing per #184's design). Scroll up — the list scrolls freely; nothing yanks it back. Note: AC3 says "When `isStreaming` flips to `false`, auto-scroll stops." Phase 0's seed never flips `isStreaming` to `false` (the static seed stays `true` forever). To exercise the AC3 path in Phase 0, the developer can temporarily flip the seed to `false` and re-install; this is a local verification step, not a code change to commit.
6. **AC4** — open any other channel (`seed-channel-joi-pilates`, etc.) with no streaming message. Scroll the list — behaviour is identical to pre-#185: no auto-snap, no anchor pull. (Tested by inspection; the auto-pin `LaunchedEffect` early-returns when `hasStreamingMessage` is false, so no side effects can fire.)
7. **`./gradlew assembleDebug`** and **`./gradlew lint`** must pass with no new warnings.

### `./gradlew test`

The existing `FakeConversationRepositoryTest` does not touch UI scroll behaviour; this ticket's changes don't alter any seeded data or repository contract. No test should break and none needs to be added.

## Open questions

- **Reverse-layout natural anchoring.** Compose's `LazyColumn(reverseLayout = true)` does anchor at the bottom when items overflow the viewport, but the precise behaviour when item 0's height grows mid-frame is not strongly documented. The auto-pin loop is the belt-and-suspenders: even if the natural anchor holds in 95% of cases, the explicit `scrollToItem(0)` on size-change is a cheap no-op when already pinned and a correctness restore when not. If a profiler ever flags the redundant call, the optimisation is to gate `scrollToItem(0)` on `firstVisibleItemScrollOffset != 0`; don't pre-build that now.
- **Stable seed never flips `isStreaming = false`.** Phase 0's seed is a static `Message(isStreaming = true)`; AC3's "stops when `isStreaming` flips false" path is not exercised by the running demo. It is verifiable by code-review reading the `LaunchedEffect` gate, or by a manual one-line seed flip + re-install. Phase 4's real backend will exercise the path naturally.
- **`scrollToItem` vs `animateScrollToItem`.** This spec mandates `scrollToItem` (instant). If during implementation the reviewer prefers a brief animation, the swap is one identifier; but expect visible stutter at 50 ticks/sec because each animation's duration overlaps the next tick. Recommend sticking with the instant primitive unless code-review explicitly asks otherwise.
- **`NestedScrollSource` API stability.** `NestedScrollSource.UserInput` is the Compose 1.7+ consolidated source for drag + fling. Compose BOM `2026.02.01` is well past that; no version concern. If the project ever downgrades, `NestedScrollSource.Drag` is the pre-1.7 equivalent.
- **Configuration-change behaviour.** `rememberLazyListState()` does survive recomposition but not configuration change (rotation). On rotation, the list re-creates and scrolls to the top — which under reverseLayout is index 0, the bottom. That's the right place for a streaming-follow demo. No need for `rememberSaveable` here.
- **Hoisting `revealedLength` into `ThreadViewModel` (Phase 4).** Per #184's open question: when `StreamingAssistantBody` scrolls off-screen and back on, the reveal restarts from 0. The auto-scroll designed here does not prevent that — it keeps the streaming bubble visible, which sidesteps off-screen disposal in the common case. Phase 4's hoist-into-VM work, if it happens, is independent of this ticket.

## Related

- Spec: [`docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md`](./184-streaming-token-reveal-blinking-caret.md) — defines `Message.isStreaming` usage and `StreamingAssistantBody`. This ticket consumes the streaming-bubble's growing height without coupling to its internals.
- Spec: [`docs/specs/architecture/126-thread-screen-skeleton-nav-route-viewmodel.md`](./126-thread-screen-skeleton-nav-route-viewmodel.md) — defines `ThreadScreen` / `ThreadViewModel` / `ThreadUiState` shapes. Unchanged here.
- Sibling: #132 — parent feature ("streaming UX in the thread") that was split into #184 (visual) and #185 (this, scroll).
- Downstream (Phase 4): real backend streaming via Ktor + WS will feed `Message.content` that grows at the repo layer (not just visually). The same auto-pin logic handles that case unchanged — item 0's measured size changes for the same reason (text re-flows), so the snapshotFlow fires and re-pins.
