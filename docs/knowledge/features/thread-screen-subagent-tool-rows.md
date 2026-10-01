# Thread screen — subagent tool-row nesting

Split out of [Thread screen § how it works, the list, the chip, the empty state and the status
row](thread-screen-how-it-works-list-and-status-row.md) on 2026-10-01 to keep that document under the
50000-byte size cap the docs guard enforces. The section moved here verbatim and kept its heading, so its
anchor is unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge
cases and its links.

### Subagent tool-row nesting (#896)

Beside `cutoffChronologicalIndex`, the same `else` arm computes `val toolDepths = remember(state.items) {
toolNestingDepths(state.items) }` and the `ThreadItem.MessageItem` dispatch arm passes
`toolNestingDepth = toolDepths[item.message.id] ?: 0` into `MessageBubble` — the only change #896 made to
this file; the fold, the keys, the alpha wrap and every other arm are untouched. `toolNestingDepths` itself
is `internal fun toolNestingDepths(items: List<ThreadItem>): Map<String, Int>`, declared in `ThreadRow.kt`
beside `foldQueuedRows` (a pure derivation next to a pure derivation), not inside the screen.

**What it computes.** A tool row's [`Message.id`](data-model.md) is its own `tool_use_id`; its
[`ToolCall.parentToolUseId`](data-model.md) (#810) names the `Agent`/`Task` call whose subagent made it,
or `""` for the main thread. A row's depth is `0` when `parentToolUseId` is empty or names no *tool* row
loaded in the thread (an older page not yet fetched, an id belonging to some other kind of row, or —
because the disk cache doesn't persist the field — any cache-restored row), otherwise `1 +` its parent's
depth. The map holds only rows with depth `> 0`; a row absent from it renders at top level, which is what
`?: 0` above falls back to.

**Matching is independent of list order.** Candidates are collected into a `parentOf: Map<String, String>`
first (one pass over `items`), then each row's depth is found by walking up its parent chain in that map,
memoising every id the walk passes through `depthOf` so no id is walked twice — O(tool rows) total however
deep the nesting goes, and correct whether a row's parent appears earlier or later in the list.

**Cycle rule.** `parentToolUseId` is a grouping *hint*, not a capability (`protocol-mobile.md` §
`tool_use`) — the daemon is never expected to send a loop, but the derivation still has to terminate if one
somehow arrives (a corrupt cache row parented to itself, for instance). The walk tracks the ids on its
current path in an `onPath` set and stops the moment it would revisit one; that row counts as top level,
and every row walked before it on the path counts up from there. Deterministic for a given list, and it
never loops, regardless of how the cycle is shaped (a two-row A→B→A pair and a one-row self-parent both
terminate the same way).

**Rejected alternative: a single forward pass.** A simpler shape — walk `items` once, looking up each row's
already-computed depth by its `parentToolUseId` as it goes — is *not* what's shipped, because it silently
leaves a row flat whenever its parent appears *later* in the list: the parent's depth isn't known yet at
the point the child is visited, forward-only. The two-map walk above (`parentOf` built first, `depthOf`
filled by a per-row backward walk) matches "a tool row loaded anywhere in the thread" as the AC requires,
at the same O(tool rows) cost, and the cycle guard above is the price of allowing that backward walk to
happen at all.

Tested independently of any composable in `ToolNestingDepthsTest` (`app/src/test/.../thread/`): main-thread
row absent from the map, matched child = 1, grandchild = 2, unmatched parent absent, empty-id row ignored,
a parent id that matches a *user* message (not a tool row) does not nest, a parent listed after its child
still nests, and both cycle shapes (a two-row loop, a self-parent) terminate per the rule above. The
Compose-level assertion — that the indent and the "Subagent step, level N" description actually reach the
rendered row — lives in `ToolRowNestingTest` (`app/src/sharedTest/.../thread/`), which mounts the real
`ThreadScreen`; see [`MessageBubble` § Subagent nesting
indent](message-bubble.md#subagent-nesting-indent-since-896) and [`ToolCallRow` § Subagent step
description](tool-call-row.md#subagent-step-description-since-896) for what each level actually renders.
No rung-3 scenario: this is a layout change over rows that already stream, and the live data path is
unchanged.

The message region's `LazyColumn` fills its weighted `Box`, with the top overlay drawn after it. The list keeps `reverseLayout = true`, scrolling upward from the bottom; connection readings in the composer and an Offline Retry overlay do not reserve list height. See [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).

**Streaming auto-scroll (since [#185](../codebase/185.md)).** While any `ThreadItem.MessageItem` in `state.items` carries `message.isStreaming = true`, the `LazyColumn` keeps the streaming bubble's growing bottom edge anchored at the viewport bottom. Six composition-scoped pieces of state hoisted at the top of the `else` block — adjacent to the existing `reversedItems` / `cutoffChronologicalIndex` lines — implement it: `val listState = rememberLazyListState()` (threaded as `state =` on the `LazyColumn`); `val hasStreamingMessage by remember(state.items) { derivedStateOf { state.items.any { it is ThreadItem.MessageItem && it.message.isStreaming } } }` (the gate on the auto-pin coroutine, scan re-runs only when the list reference changes); `var userScrolledAway by remember { mutableStateOf(false) }` (yield flag); `val autoScrollNestedScroll = remember { object : NestedScrollConnection { ... } }` (sets `userScrolledAway = true` iff `source == NestedScrollSource.UserInput && available.y != 0f`, attached via `Modifier.nestedScroll(autoScrollNestedScroll)` on the column); `LaunchedEffect(listState) { snapshotFlow { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }.collect { atBottom -> if (atBottom) userScrolledAway = false } }` (resumes auto-follow when the user manually returns to the bottom); and `LaunchedEffect(hasStreamingMessage, listState) { if (!hasStreamingMessage) return@LaunchedEffect; snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0 }.distinctUntilChanged().collect { if (!userScrolledAway) listState.scrollToItem(0) } }` (the auto-pin loop — re-anchors on every layout-pass size change of item 0). `scrollToItem(0)` (not `animateScrollToItem`) is the right primitive: instant, O(1) when already pinned, and it does **not** dispatch through `NestedScrollSource.UserInput` so it cannot recursively trip its own yield flag. Reverse-layout's bottom anchor is `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`; both effects rely on that. The auto-pin `LaunchedEffect` is gated by `hasStreamingMessage`, so non-streaming threads start no collector (AC4); when `isStreaming` flips `false` the effect re-launches with the new key and the early-return cancels the collector (AC3). The Phase-0 seed never flips `isStreaming = false` (the static seed stays streaming forever) — AC3's cancel path is verifiable only by code-review or a local seed flip + re-install; Phase 4's WS feed will exercise it naturally. The companion concern from [#184](../codebase/184.md) — `StreamingAssistantBody` losing its `revealedLength` when the bubble scrolls off-screen — is sidestepped (not solved) in the auto-pin happy path: keeping the bubble in the viewport prevents disposal. If the user yields by scrolling away during streaming, the bubble can still off-screen and re-reset on return; the Phase-4 hoist-into-VM fix from [#184](../codebase/184.md) is the proper remedy.

**The newest-row pin (#981).** The auto-pin above only runs while `hasStreamingMessage` is true, and even
then only re-anchors on a *size* change to the item at index 0 — a reply that arrives already finalized, a
tool row, the operator's own echo, or any other row that becomes the new newest row without ever
registering `isStreaming` never trips it. Under `reverseLayout = true`, `LazyListState` keeps its first
visible row anchored by key: inserting a new newest row at index 0 pushes the previous first row to index
1, which stays anchored at the bottom edge, so the new row lands below the viewport, uncomposed — invisible
to the user and to a Compose test's `onNode` assertions — until something scrolls back to index 0. When the
rows still fit the viewport, the list's own measure pulls index 0 back in to fill the gap, which is why
this hid intermittently rather than always: [#977](../codebase/977.md)'s live diagnosis on the #687
operator-bypass scenario found the daemon's allowed-Read reply finalizing about 1.4s after the allow, after
the streaming pin's one delta had already been collected and had nothing left to re-trigger it. A second
composition-scoped effect, added beside the size-driven pin, fixes this independently of streaming state:
`val newestRowKey by rememberUpdatedState(rows.lastOrNull()?.listKey(rows.lastIndex))`, then
`LaunchedEffect(listState) { snapshotFlow { newestRowKey }.drop(1).collect { if (!userScrolledAway) { try
{ listState.scrollToItem(0) } catch (e: CancellationException) { ensureActive() } } } }`. Keyed on the
newest row's *identity* rather than `isStreaming`, so any new newest row re-anchors, while a streaming row
that only grows keeps its key and is left to the size-driven pin above. `drop(1)` skips the effect's own
first collected value: `userScrolledAway` is plain `remember` (not saveable) while `listState` is, so
without the skip a configuration change would pull a reader who had scrolled away back to the newest end on
recreation. The `try`/`catch` around `scrollToItem` is required, not defensive: a finger resting at the
newest end holds the list's `MutatorMutex` at `UserInput` priority while `userScrolledAway` is still
`false` (it flips only on the *next* `NestedScrollSource.UserInput` delta), so a row arriving in that window
gets its `scrollToItem` refused with a `CancellationException("Current mutation had a higher priority")`;
unlike the streaming pin above, which relaunches on every `hasStreamingMessage` flip, this effect never
relaunches, so an uncaught refusal would silently end it for the rest of the composition — every later
non-streaming row would then land hidden until the screen left composition. `catch` + `ensureActive()`
costs one scroll on a refusal and still lets a genuine cancellation propagate. One known gap, harmless: an
unmatched `ThreadRow.Queued` keys on its chronological index (`"queued-row:$chronologicalIndex"`, § keys
above), so an older-history prepend shifts that index and changes `newestRowKey` even when no row actually
arrived at the newest end — but the effect only calls `scrollToItem(0)` while `userScrolledAway` is false,
which means the reader is already at the newest end, so the resulting scroll is a no-op. Covered by
`ThreadScreenNewestRowTest` (`app/src/sharedTest/.../thread/`), which seeds 30 rows so the list overflows
(the same #777 lesson below applies to any `layoutInfo`-derived test): a complete non-streaming reply
arriving at the newest end is composed and visible; a reader who dragged away with a real
`performTouchInput { swipeDown() }` (not `performScrollToIndex`, which never sets the
`NestedScrollSource.UserInput` yield flag) is not pulled back, for both a non-streaming and a streaming
arrival; and a scroll refused under a resting finger costs only that one row — the next arrival after the
finger lifts is still followed.
