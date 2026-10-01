# Thread screen — how it works, the list, the chip, the empty state and the status row

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### `LazyColumn(reverseLayout = true)` — established in #126, populated in #246, dimmed in #136, nested in a `Column` since #201, rows folded with the queued backlog since #782

The body shape since [#246](../codebase/246.md) iterated `state.items.asReversed()` with stable composite keys and dispatched at the `ThreadItem` sealed-interface level. **Since [#782](../codebase/782.md) the list walks `ThreadRow`s, not `ThreadItem`s directly:** `val rows = remember(state.items, state.queuedMessages) { foldQueuedRows(state.items, state.queuedMessages) }` joins the thread's items against the daemon's queued backlog (see
[Queued backlog rendering § The render-time join](queued-backlog-section.md#the-render-time-join-782)),
and `itemsIndexed(items = rows.asReversed(), ...)` dispatches at the `ThreadRow` sealed-interface level
instead: `ThreadRow.Delivered` re-enters the pre-#782 `when (item)` dispatch on its wrapped `ThreadItem`
(`MessageItem` → `MessageBubble(message = item.message)`, `SessionBoundary` →
`SessionBoundaryDelimiter(boundary = item, agent = state.agent)` (agent since
[#1112](https://github.com/pyrycode/pyrycode-mobile/issues/1112) — see [Session boundary delimiter § The
agent name](session-boundary-delimiter.md#edge-cases--limitations)), `UnrecognizedMessage` →
`UnrecognizedMessageRow(item = item)`),
`ThreadRow.Queued` → `QueuedMessageRow(text = row.text, onDrop = { onDropQueued(row.queuedMessageId) })`.
The screen does **not** re-dispatch by `Message.role`; [`MessageBubble`](message-bubble.md) owns that
selection internally. No `verticalArrangement = Arrangement.Bottom` override — `reverseLayout = true`
already pins the first item to the bottom edge.

**Source-list reversal is required.** `observeMessages` returns items chronologically ascending (index 0 = oldest), but `LazyColumn(reverseLayout = true)` draws the **first** item at the bottom. For "newest at the bottom" the screen reverses before passing — `rows.asReversed()` (pre-#782: `state.items.asReversed()`) is the Kotlin stdlib O(1) view (no allocation, no copy), and it's a `List<ThreadRow>` so it slots into `itemsIndexed(...)` directly. Keys are computed from the underlying rows, so the view's reversed index is irrelevant for identity.

**Stable keys are per-subtype with a string namespace prefix — four namespaces since #782.** The key
function is `ThreadRow.listKey(chronologicalIndex)`, defined beside the fold in `ThreadRow.kt` rather
than inline in the screen, so the fold and its key-uniqueness argument sit together. `ThreadRow.Delivered`
re-derives the pre-#782 per-`ThreadItem` keys: `MessageItem` → `"msg:${message.id}"` (the canonical row
identity assigned at message creation, surviving all state transitions), `SessionBoundary` →
`"boundary:${previousSessionId.length}:$previousSessionId${newSessionId.length}:$newSessionId@$occurredAt"`
— the boundary's full `(previousSessionId, newSessionId, occurredAt)` identity, length-prefixing the
two daemon-supplied ids so an id containing a separator character cannot make two distinct triples
spell the same key ([#775](../codebase/775.md); the pair alone is *not* unique — an idle-evicted
session keeps its id, so a session evicted twice sends the same pair twice with different instants,
and both are real rows), `UnrecognizedMessage`
→ `"unrecognized:$id"`. `ThreadRow.Queued` adds a fourth namespace, and it is the one place the pre-#782
one-to-one mapping between "row" and "key namespace" breaks on purpose: a **matched** `Queued` row (a
send the daemon still reports parked) takes `echoId?.let { "msg:$it" }` — **the same key its `Delivered`
form carries** — which is exactly what leaves the row in place, unrecreated, when the next snapshot
delivers it (AC #2 of #782). An **unmatched** row (no echo this device minted) keys on its position,
`"queued-row:$chronologicalIndex"`, deliberately **not** on the snapshot's `queued_msg_id`: that value is
daemon-supplied and nothing on this client checks it for uniqueness, so a snapshot repeating one would
mint two identical `LazyColumn` keys and crash the thread — a hazard the #782 security review caught and
closed by keying on position instead, which is unique by construction. All four namespaces are distinct
string literals, so no arm can collide with another. The `itemsIndexed(...)` key lambda ignores the `Int`
first arg for the `Delivered` / matched-`Queued` arms — identity stays anchored to item fields — but the
unmatched arm's key is deliberately position-derived, the one namespace where position *is* the identity.

**Above-delimiter opacity (since [#136](../codebase/136.md)).** Each row is wrapped in `Box(Modifier.alpha(rowAlpha))` around the existing `when (row)` dispatch. `rowAlpha` is computed inline: a `chronologicalIndex` is reconstructed from the reversed-list index (`rows.size - 1 - reversedIndex`, since #782 — pre-#782 this read `state.items.size`), then compared strict-`<` against a `cutoffChronologicalIndex = remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }` — **this cutoff itself still reads `state.items`, unchanged by #782**, because `rows` shares a prefix with `items` index-for-index and only ever appends unmatched queued rows after them, so the two index spaces agree wherever a boundary can land. Rows above the cutoff render at the file-private `ABOVE_DELIMITER_ALPHA = 0.55f` constant; rows at or after the cutoff (including the boundary itself) render at `1f`. `mostRecentSessionBoundaryIndex` is an `internal` top-level helper at the bottom of the file (`items.indexOfLast { it is ThreadItem.SessionBoundary }`); its `-1` return for the no-boundary case combines with the strict `<` to give AC3 ("zero boundaries → all rows full opacity") for free. The wrap inherits to every row variant — user/assistant `MessageBubble`, `ToolCallRow`, nested `SessionBoundaryDelimiter`, and since #782 `QueuedMessageRow` — because `Modifier.alpha(...)` is a render-only `graphicsLayer` effect and none of the row composables hold internal opacity state. **Interaction is not gated** — `ToolCallRow`'s `clickable` `Surface` stays expandable above the cutoff (alpha runs in the draw layer, after pointer input). That matches the user-story intent ("still legible, can scroll up and re-read"); if a future ticket gates above-cutoff interaction, it adds the gate at the inner `Surface`'s `enabled =` (not by stripping the alpha modifier).

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

### Inline question rows and the newest-end reveal (#1305)

[`AskUserQuestion`'s held batch](question-batch-modal.md) moved from its own `Dialog` into this
`LazyColumn` in #1305. `questionState?.let { pending -> ... }` emits, immediately before the message-row
`itemsIndexed(...)` block (so, under `reverseLayout = true`, drawn **below** every message row — the
newest content on screen), an actions item, one item per question in original order, then a title item;
see [Question batch modal § Rendering](question-batch-modal.md#rendering) for what each renders and the
`"question-actions:$generation"` / `"question:$generation:$it"` / `"question-title:$generation"` key
scheme. The empty-thread branch (`!state.hasMessages && state.queuedMessages.isEmpty()`, § *Empty-state
branch* below) also gates on `questionState == null`, so a thread whose only content is a pending batch
renders the `LazyColumn` with the prompt rows, not `EmptyThreadState`. [#1306](permission-modal-overlay.md)
adds the same `openRequest == null` gate beside it for a pending permission or trust request — see §
*Inline permission rows and the shared reveal* below, which reuses every mechanism this section describes.

**Prompt rows count as a fixed prefix in the oldest-end history predicate (§ below), never as loaded
history.** `val promptRowCount = questionState?.let { it.batch.questions.size + 2 } ?: 0` (`+2` for the
actions and title items) is added to `rows.size` for `historyRowCount`, and a new `hasHistoryRows =
rows.isNotEmpty()` — **not** `historyRowCount > 0` — gates the demand instead. Without `hasHistoryRows`, a
thread with a pending batch and zero loaded message rows would have `historyRowCount > 0` true from the
prompt rows alone and could fire `onDemandOlderHistory()` against a thread that has no history to page.

**Prompt arrival and edits are excluded from the streaming auto-pin.** The streaming size-driven
`LaunchedEffect(hasStreamingMessage, listState)` (§ *Streaming auto-scroll* above) gained
`questionState != null` to its key and its early-return guard — while a batch is mounted, the streaming pin
does not re-anchor on item 0's size, because item 0 while a batch is held is the prompt actions row, not a
streaming message bubble, and its field sizing (the Other `BringIntoViewRequester`, the IME) must not drag a
history reader back to the bottom.

**A second, dedicated effect reveals a newly arrived batch to a reader already at the newest end** — the
\#981 newest-row pin (above) only follows a new *message* row, and prompt rows insert below the anchored
first visible row the same way a new message row would, landing off-screen until something scrolls back to
index 0. A verifier pass on #1305 found this gap live: an operator watching the newest end saw only the
composer's "Waiting for answers" status reading (below) until they thought to scroll further. The fix
mirrors #981's shape: `val promptGeneration by rememberUpdatedState(questionState?.generation)`, then
`LaunchedEffect(listState) { snapshotFlow { promptGeneration }.drop(1).filterNotNull().collect { ... } }`.
`drop(1)` skips the effect's own first collected value, for the same reason #981's does — a configuration
change must not pull a reader who scrolled away back to the newest end on recreation — and a `null`
generation (dismissal) is filtered out rather than driving a scroll. On a genuine new generation, the
effect scrolls to index 0 **only** when the reader has not scrolled away **and** is still at the newest
end: `firstVisibleItemScrollOffset == 0 && (firstVisibleItemIndex == 0 || first?.key == newestRowKey)`. The
key half of that check — not just `userScrolledAway == false` — covers a reader moved into history
programmatically (`userScrolledAway` only flips on real `NestedScrollSource.UserInput`, so it alone cannot
see that move); the existing history-anchor regression test is exactly that case and stays unchanged.
`scrollToItem(0)` reveals the actions row and the latest question, not the title first: under reverse
layout, scrolling to the title of a batch taller than the viewport would need a second post-measure scroll,
and a batch that already fits the viewport already shows its title at `scrollToItem(0)`. The same
`try { scrollToItem(0) } catch (e: CancellationException) { ensureActive() }` pattern #981 uses tolerates a
resting-finger mutation refusal without the effect silently dying for the rest of the composition (§ *The
newest-row pin* above explains why the catch is required, not defensive). Covered by
`ThreadInlineQuestionTest.arrival_reveals_the_batch_to_a_reader_at_the_newest_end`
(`app/src/sharedTest/.../thread/`); `arrival_and_edits_preserve_a_history_reader_and_prompt_rows_do_not_advance_history_demand`
in the same file covers the complementary case — a reader scrolled into history is not pulled forward by
batch arrival or edits, and prompt rows do not move the oldest-end predicate.

**The composer status band gets a fourth, static reading.** `ThreadStatusArea` takes a new
`waitingForAnswers: Boolean` parameter (`questionState != null && connectionState ==
ConnectionState.Connected`) that, when true, renders a fixed "Waiting for answers" row (`R.string.question_waiting_for_answers`,
the question glyph tinted `primary`) in place of the usual `StatusReading` dispatch — this is what the
verifier's Rework 3 absence check additionally waits to disappear (`awaitNoInlineQuestion`, see
[Real-claude e2e coverage](../../e2e-interactive-stream.md)), since it is always composed and not subject to
the lazy list's offscreen-vs-absent ambiguity the title and row tags have.

**`QuestionPromptProtection`** (the `FLAG_SECURE` / obscured-touch guard the old dialog window used to own)
is mounted directly by `ThreadScreen` — right after the screen's `rememberSaveable` block, so it is active
for the composition's whole life while any batch is held, independent of which prompt rows happen to be
visible. Since [#1306](permission-modal-overlay.md) the guard's condition is `questionState != null ||
openRequest != null` from **one call site**, so a question batch handing off to a permission request (or
the reverse) keeps the same owner throughout — see § *Inline permission rows and the shared reveal* below.
See [Question batch modal § Rendering](question-batch-modal.md#rendering) for the shared-ownership
mechanism this guards against overlapping navigation transitions.

### Inline permission rows and the shared reveal (#1306)

[The pending permission or trust request](permission-modal-overlay.md) moved out of its own dialog window
into this same `LazyColumn` in #1306, following the #1305 question batch above almost row for row.
`openRequest?.let { open -> permissionRequestItems(...) }` emits, immediately before the question items (so,
under `reverseLayout = true`, drawn **below** the question rows and **above** every message row — the
newest content on screen): Cancel, the card (prompt, decision context, the session-grant offer and the
options), then the title — see [Permission-modal overlay](permission-modal-overlay.md) for what each
renders and its `permission-cancel:$modalId` / `permission-card:$modalId` / `permission-title:$modalId` key
scheme. The empty-thread branch and `promptRowCount` both gained a matching `openRequest` term (§ above and
§ *The oldest-end history demand* below); `promptRowCount` adds a fixed `PERMISSION_ROW_COUNT = 3` rather
than deriving a size from the request, since a permission/trust request always renders exactly three rows.

**The streaming pin and the newest-end reveal generalize to either prompt kind rather than duplicating.**
The streaming auto-pin's guard became `questionState != null || openRequest != null` (a local
`promptPresent` val) in place of the #1305 `questionState != null` alone. The reveal effect's key widened
from `questionState?.generation` alone to a `Pair`, `questionState?.generation to openRequest?.modalId`, so
either a new question generation or a new permission `modalId` — never both signals doing the same
job twice — re-triggers the scroll to index 0 under the same `drop(1)` / `filter` / anchored-reader guard
the #1305 effect established; see § *Inline question rows and the newest-end reveal* above for the full
mechanism, which this reuses verbatim.

**Leaving the request open does not cancel it.** Back, opening another conversation, and switching to a
different chat all leave the request rendered (subject to the usual scroll-position rules above) rather
than dismissing it — only an explicit Cancel tap or a daemon resolution removes it. `MainActivity`'s
`DisposableEffect(vm) { onDispose { vm.onConversationLeft() } }` clears an armed non-default option on the
way out so a returning reader needs two fresh taps, but never touches the request itself or the
session-grant draft; see [Modal answer flow § Leaving the conversation](modal-answer-flow.md#leaving-the-conversation-clears-the-arm-not-the-grant-1306).

### The oldest-end history demand (#777)

`requestHistory` ([remote repository § the walk that finally calls
`requestHistory`](remote-conversation-repository-reads-and-thread-store-history-paging.md#the-walk-that-finally-calls-requesthistory-777))
had no caller until #777 wired the screen's scroll position to `ThreadViewModel.onDemandOlderHistory()`
via a new defaulted `onDemandOlderHistory: () -> Unit = {}` parameter (`MainActivity` binds
`vm::onDemandOlderHistory`, the only consumer). Two pieces live in the same `else` arm as the
streaming-auto-scroll state above, right beside `reversedItems` / `cutoffChronologicalIndex`.

**The predicate: under `reverseLayout = true`, "reached the oldest row" is the LAST visible index, not the
first.** Index 0 is the newest row (§ above), so older rows take higher indices and the reader hits the
oldest loaded row when the list's last visible item reaches the end of the data. A `LaunchedEffect(listState)`
runs a `snapshotFlow` that computes `oldestVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
?: -1`, tests `historyRowCount > 0 && oldestVisible >= historyRowCount - 1`, applies `distinctUntilChanged()`,
and calls `onDemandOlderHistory()` on a `true` edge.

**The row count is read through `rememberUpdatedState(rows.size)` (since #782 — pre-#782:
`state.items.size`), never through `layoutInfo.totalItemsCount`.** The obvious shape — compare against the
`LazyColumn`'s own item total — counts the oldest-end loading row itself (below), so a page answering
`atStart = false` with zero entries self-drives with no further user input: ask → the indicator mounts →
the total rises → the page settles → the indicator unmounts → the total falls → the predicate re-fires on
the new edge. Sourcing the count from `rows.size` through `rememberUpdatedState` (and the callback the
same way) makes the indicator's own presence unable to move the predicate: at the oldest end the last
visible index is `rowCount` with the indicator mounted and `rowCount - 1` without it, and `>=` holds
either way, so `distinctUntilChanged` sees no edge and issues no second demand. #782 moved this from
`state.items.size` to `rows.size` for the identical reason it moved the alpha cutoff's chronological
index: an unmatched queued row can sit at the newest end of `rows` without a corresponding entry in
`items`, and the oldest-end predicate must count what the list actually renders. **#1305** added a further
`promptRowCount` on top of `rows.size` and replaced the `historyRowCount > 0` gate with a dedicated
`hasHistoryRows` — see § *Inline question rows and the newest-end reveal* above. **#1306** added
`PERMISSION_ROW_COUNT` to the same `promptRowCount` sum for an open permission/trust request — see §
*Inline permission rows and the shared reveal* above.

**This needed a 30-row regression test to catch, not a 3-row one.** The first version of the demand-loop
test used three short rows and passed even against the deliberately-broken `totalItemsCount` predicate —
when every row fits the viewport, the mounted indicator is visible too, so the last visible index tracks
the total either way and the bug hides. Only a list long enough to scroll, with the loading indicator
mounting *above* the viewport at the oldest end, exercises the difference; the shipped test seeds 30 rows
and scrolls there first. Verified by mutation: the `totalItemsCount` variant fails it (2 demands, expected
1), the shipped variant passes. The lesson generalizes to any `layoutInfo`-derived predicate — an
under-filled list is the default way such a test accidentally passes.

**The affordance rides `reverseLayout` for free.** A keyed `item(key = "history-loading")` rendering
`HistoryLoadingRow()` is appended after the `itemsIndexed(...)` block. Because `reverseLayout = true` draws
a later item further up, appending it after the message rows places it at the **oldest** end without any
special-casing of index 0. `HistoryLoadingRow` is a private composable mirroring
[`ThinkingIndicator`](thinking-indicator.md)'s shipped idiom — a 16dp indeterminate
`CircularProgressIndicator`, a `bodySmall` / `onSurfaceVariant` label, and a merged
`semantics { contentDescription = … }` — because the Figma thread frame (16:8) carries no history-loading
element of its own; both strings are `strings.xml` resources (`thread_history_loading_label`,
`cd_thread_history_loading`) with no interpolation, so nothing daemon-authored reaches this row. [#778](../codebase/778.md) widened the gate from a `Boolean` to `state.historyTail` — see § below.

**Known gap: the affordance is unreachable while the thread reads as empty.** The loading row lives inside
the `else` arm of `if (!state.hasMessages)` (§ *Empty-state branch* below), so a channel whose every loaded
row predates this connection renders `EmptyThreadState` instead of the loading row while the opening ask is
in flight — a one-round-trip flash of the empty placeholder rather than a visible loading state, on exactly
the case the ticket set out to fix. Verifier-flagged as SHOULD FIX (non-blocking) on the #777 PR and not
addressed in that ticket; still open — #778 widened the same slot without closing this gap, since it lives
in the same `else` arm.

### The oldest-end history retry and restart (#778)

[#777](../codebase/777.md)'s loading row was a one-state affordance gated on a `Boolean`; [#778](../codebase/778.md)
widens the **same slot** to four mutually exclusive states without adding rows — the one-slot invariant is
enforced by construction, since `when (state.historyTail)` emits at most one `item(key = "history-tail")`.

- **`ThreadUiState.historyLoading: Boolean` was replaced outright by `historyTail: ThreadHistoryTail`**
  (`None` / `Loading` / `Retry` / `DeadEnd`), read from `ThreadHistoryDemand.tail()` via the same
  `ThreadContent` `combine` arm #777 used — no sixth arm, because Kotlin's typed `combine` stops at five.
  The walk's *termination* reasons (`AtStart` / `NotAdvancing` / `PageCap`) still never reach the screen,
  only its two failure reasons do — the screen asks, the ViewModel decides whether the ask is honoured, and
  a second copy of that decision in Compose would be a second place to get it wrong.
- **`HistoryRetryRow(onRetry)`** is an `errorContainer` / `onErrorContainer` `Surface` on
  `MaterialTheme.shapes.small` — the shipped [`ConnectionBanner`](connection-banner.md) idiom, reused
  rather than a new error style, because the Figma thread frame carries no history element of its own but
  does carry one error-plus-action affordance (the status-area "Pairing error - Re-pair" chip) in that same
  shape. Its content `Row` carries `Modifier.clickable(role = Role.Button)`, a `bodySmall` failure label and
  a `labelLarge` action label, with a merged `contentDescription`. Pressing it calls the new defaulted
  `onRetryOlderHistory: () -> Unit = {}` parameter, wired by `MainActivity` to `vm::onRetryOlderHistory` —
  the only consumer.
- **`HistoryDeadEndRow()`** is the same `Surface` with no `clickable` and no action label — visible, with
  nothing to press, because a closed-session or non-retryable failure has no button that could work; the
  reconnect restart is what actually recovers a closed session, not a tap.
- **Neither row reads `RelayErrorException.message`.** Both strings are local `strings.xml` resources
  (`thread_history_retry_label`, `thread_history_retry_action`, `thread_history_dead_end_label`, plus their
  content-description twins) with no interpolation — the same "nothing daemon-authored reaches this row"
  posture `HistoryLoadingRow` already had.
- **The demand predicate is unchanged and must stay that way.** It already counts `state.items.size`
  through `rememberUpdatedState` rather than `layoutInfo.totalItemsCount` (§ above) — widening the oldest-end
  slot from one row to three possible ones does not move it, for the identical reason: the slot's own
  presence must never be able to re-trigger the predicate that mounts it.

### Workspace-chip wiring (post-#137)

The older body `Column` carried a conditional [`WorkspaceChip`](workspace-chip.md):

```kotlin
if (!state.isPromoted && !state.hasMessages) {
    WorkspaceChip(
        workspaceLabel = state.workspaceLabel,
        onClick = onWorkspaceChipTapped,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

The `&&` predicate stays inlined at the call site (not derived onto a hidden `showWorkspaceChip` boolean) because the same two raw fields will be re-consumed by #138 (empty-state copy) and #208 (overflow → picker entry point) — pre-fusing them would force re-derivation downstream. `workspaceLabel` is already-derived (the VM resolves `Conversation.cwd` → basename at the flow boundary via the private `workspaceLabel()` extension); composables never see the raw path. The chip lives in the existing `Column` slot, **not** inside the `LazyColumn` as a header item — it doesn't participate in scroll, doesn't get recycled, doesn't need a `key`, and the current empty `items(emptyList<Unit>()) { }` body has no `item { … }` slot to add it to without forcing a structural rewrite that #128's eventual `items(state.messages) { … }` would have to undo.

Inside the same `Column`, immediately after the `Scaffold`'s closing brace, the [`WorkspacePicker`](workspace-picker.md) host is rendered as a **`Scaffold` sibling** (not inside the content slot):

```kotlin
WorkspacePicker(
    visible = state.workspacePickerVisible,
    onPicked = onWorkspacePicked,
    onDismiss = onWorkspacePickerDismissed,
)
```

The picker's `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order — sibling-to-Scaffold mirrors [`ChannelListScreen.kt:180-184`](channel-list-screen.md) exactly (the canonical wiring shape from [#221](../codebase/221.md)). #208 will reuse this exact host call by routing its overflow tap to the same `pendingWorkspacePicker.value = true` flag; no second `WorkspacePicker` invocation needed.

The three VM handlers (`onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`) all mirror `ChannelListViewModel`'s picker-trigger handlers. `onWorkspacePicked(path)` clears the flag *then* launches `repository.changeWorkspace(conversationId, path)` via [`launchGuardedRepoCall`](guarded-repo-launch.md) (guarded since [#490](../codebase/490.md) — `changeWorkspace` is one of the not-yet-wired remote methods that throw `UnsupportedOperationException`, so the guard swallows that plus the not-connected / server-error cases); the returned `Session` is discarded — the `Conversation.cwd` update propagates back via the `observeConversations` re-emission to the `combine` arm, and `workspaceLabel` recomputes automatically. `onWorkspacePickerDismissed` clears the flag only — no repository call. See [`WorkspaceChip`](workspace-chip.md) for the full data-flow.

### Empty-state branch (post-#138)

Below the chip and above the `bottomBar` composer, the body `Column` carries a single `if (!state.hasMessages) … else …` branch:

```kotlin
if (!state.hasMessages) {
    EmptyThreadState(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
    )
} else {
    val reversedItems = state.items.asReversed()
    val cutoffChronologicalIndex =
        remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), reverseLayout = true) {
        itemsIndexed(items = reversedItems, key = { _, item -> … }) { reversedIndex, item -> … }
    }
}
```

[`EmptyThreadState`](empty-thread-state.md) renders the centered "Send a message to get started" prompt; the caller supplies the `weight(1f)` so the prompt fills exactly the space the list would have occupied. The 24.dp horizontal inset is intentionally 8dp wider than the chip's `horizontal = 16.dp` — a centered single line wants more breathing room than a left-aligned chip on the 360dp portrait minimum. Predicate is `!state.hasMessages`, not `state.items.isEmpty()` — symmetric with the chip's `!hasMessages` half (chip and prompt appear/disappear together) and correct for the `SessionBoundary`-only edge case where the user taps the chip → `changeWorkspace` emits a boundary before any message lands (the prompt stays visible until a real `MessageItem` arrives, instead of letting a lonely delimiter float above the input bar). **Since [#782](../codebase/782.md) the predicate is `!state.hasMessages && state.queuedMessages.isEmpty()`** — a backlog item this device minted no echo for renders as its own `ThreadRow.Queued` row with nothing else in `rows` to anchor it (see
[Queued backlog rendering § Position in the list](queued-backlog-section.md#position-in-the-list)), so
the empty-state prompt must yield to it; when a backlog item *is* matched, its echo is already a
`MessageItem` and `hasMessages` alone already covers that case, so the added clause changes nothing for
it. The `remember(state.items) { mostRecentSessionBoundaryIndex(...) }` block from [#136](../codebase/136.md) lives **inside the `else` arm only** — its key is `emptyList()` in the empty arm, so computing the cutoff there is wasted work.

**The `#777` history-loading row is inside this `else` arm too, which is a known gap.** The oldest-end loading affordance (§ *The oldest-end history demand* above) is appended inside the `LazyColumn`, so a channel that reads as empty (`!state.hasMessages`) renders `EmptyThreadState` instead while the opening history ask is in flight — the empty-thread case the ticket set out to fix. Verifier-flagged SHOULD FIX, not addressed by #777; see § above for the detail.

### Status-row wiring (post-#145, retired in #808)

**[#808](../codebase/808.md) replaced this whole section's surface.** `ThreadStatusRow` — the single `model · effort` line described below — is deleted; the `bottomBar` column now mounts [`ThreadComposerFooter`](thread-composer-footer.md) (two independent buttons opening an [Options overlay](options-overlay.md)) in its place, and the screen's `Scaffold` moved inside a `Box` to host that overlay. See [thread-composer-footer.md](thread-composer-footer.md) for the current wiring; the section below is kept as the history of the row it replaced, through #145–#807.

Inside the same `Scaffold.bottomBar` slot, the (retired) `ThreadStatusRow` stacked above the [`ThreadInputBar`](thread-input-bar.md) inside a shared wrapper `Column`:

```kotlin
bottomBar = {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThreadStatusRow(
            model = state.runConfig.modelLabel,
            effort = state.runConfig.effortLabel,
            onExpandClick = { sheetVisible = true },     // wired internally in #254
            modifier = Modifier.padding(horizontal = ComposerGutter),
            pending = state.runConfig.pending,           // #807
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Pre-#145 the slot held only `ThreadInputBar(onSend = onSendMessage)`. The wrapper `Column` is one of two new things in #145; the other is the `ThreadStatusRow` itself. Crucially, **`Modifier.imePadding()` lives inside `ThreadInputBar` on its own outer `Column` (since [#188](../codebase/188.md)), not on the `Scaffold` bottomBar** — so only the input bar lifts when the soft keyboard opens, and the status row stays visually anchored above the lifted input bar. No new `imePadding` on the outer wrapper.

The three new `ThreadUiState` fields shipped in #145 (`model`, `effort`, `tokenPercent`) were populated from companion-object constants (`STUB_MODEL = "Opus 4.7"`, `STUB_EFFORT = "high"`, `STUB_TOKEN_PERCENT = 73`) inside the same `combine(...)` block. In [#253](../codebase/253.md) `model: String` was retyped to `selectedModel: Model` and rewired to a pre-combined `selectedModelFlow = combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }`; the `STUB_MODEL` constant was deleted. In [#229](../codebase/229.md) `effort: String` was retyped to `selectedEffort: Effort` and rewired to a pre-combined `selectedEffortFlow` mirroring `selectedModelFlow`'s shape; `STUB_EFFORT` was deleted. `STUB_TOKEN_PERCENT` survived through #602, still landing inside the [`warning`](warning-color.md) band so the threshold-driven color rendered live in the running app, not just in previews — but by then nothing read it, since [#602](../codebase/602.md) had already dropped `ThreadStatusRow`'s usage segment. [#603](../codebase/603.md) executed the predicted Phase-4-swap-point cleanup: `tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants (the companion object's only members) are all deleted; `ThreadUiState` no longer has a token-percent concept. #591 will populate a real figure from a backend `AgentStatus` flow when the daemon serves one — against a shape it designs itself, not a restoration of these fields.

**[#807](../codebase/807.md) retired `selectedModel: Model` / `selectedEffort: Effort` in favour of one `runConfig: ThreadRunConfig` field**, sourced from the daemon's own [`observeSessionSettings`](conversation-repository.md) + [`observeModelMenu`](conversation-repository.md) readings rather than `AppPreferences.defaultModel` / `defaultEffort` — see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing) for the full sourcing story and [status-sheet.md](status-sheet.md) for the choice surface. `state.selectedModel.label()` / `state.selectedEffort.label()` are gone; the row now reads `state.runConfig.modelLabel` / `state.runConfig.effortLabel` — computed properties on `ThreadRunConfig` itself (`ThreadViewModel.kt`), not call-site `.label()` extensions, because the label a daemon-published row carries has to be resolved against the published menu, not derived from a fixed three-entry enum. `ThreadStatusRow` also gained a `pending: Boolean = false` parameter (above) that drops the row's alpha further while a run-configuration write is outstanding.

[#507](../codebase/507.md) added a trailing, defaulted `mutationsSupported: Boolean = true` to `ThreadUiState`, populated differently from every field above: not from a `combine` source but from a **snapshot captured once at VM construction** (`private val mutationsSupported = repository.mutationsSupported`), then written into **both** the `combine` result and the `.stateIn` `initialValue` so the two can never disagree. The mode is static per build config (a Koin fake-vs-relay swap, never a runtime toggle), so a one-shot snapshot is sufficient; reading through the [facade](stable-conversation-repository.md) here is where its null-connection → `false` fail-safe-deny takes effect. **The field was dormant in #507 (present in state, read by nothing); [#508](../codebase/508.md) wired the two consumers** — `ThreadScreen` now passes `mutationsSupported = state.mutationsSupported` into `ThreadTopAppBar` (→ [`ThreadOverflowMenu`](thread-overflow-menu.md), gating out New session / Rename / Change workspace / Archive) and into [`ChannelInfoSheet`](channel-info-sheet.md) (gating out the whole Actions section). Both surfaces gate on this signal and nothing else — no `USE_RELAY_REPOSITORY` / instance-type / relay-vs-fake check inside the composables. In fake mode (the default) it is `true` end-to-end, so both surfaces are unchanged.

Through [#807](../codebase/807.md) the row read the typed enums as `state.selectedModel.label()` / `state.selectedEffort.label()`, and `ThreadViewModel.onModelSelected(model: Model)` / `onEffortSelected(effort: Effort)` wrote a per-conversation in-memory override over `AppPreferences.defaultModel` / `defaultEffort`. **#807 replaced both sides.** The row now reads `state.runConfig.modelLabel` / `state.runConfig.effortLabel` (`ThreadRunConfig` computed properties — "unknown" with no settings reading, "default" for an inherited `""`, otherwise the daemon-published row's label made inert), and `ThreadViewModel.onModelSelected(value: String)` / `onEffortSelected(level: String)` forward a published [`ModelMenuRow.value`](conversation-repository.md) / effort-level string verbatim to `ConversationRepository.setSessionSettings`, addressed to `SessionSettings.sessionId` — never `AppPreferences`. `ThreadStatusRow`'s `model: String` / `effort: String` parameter shape is unchanged; only what feeds them moved off the device enums.

Post-[#254](../codebase/254.md) the `onExpandClick` parameter on `ThreadScreen` is **deleted** — the screen owns the trigger via an internal `{ sheetVisible = true }` lambda passed straight to the row. A new `onModelSelected: (Model) -> Unit = {}` parameter took its slot on the signature, bound to `vm::onModelSelected` ([#253](../codebase/253.md)) at the `MainActivity` destination; [#229](../codebase/229.md) appended `onEffortSelected: (Effort) -> Unit = {}` and `onYoloToggled: (Boolean) -> Unit = {}`. **[#807](../codebase/807.md) retyped the first two to `(String) -> Unit`**, matching the write arguments above; `onYoloToggled`'s signature is unchanged, but it now shares the same session-id routing and read-only gate as the other two (see [thread-composer-footer.md](thread-composer-footer.md) / [`ThreadViewModel`](thread-screen-how-it-works-state.md)). Tapping the row opens the [`StatusSheet`](status-sheet.md); model/effort selections forward to the VM and auto-close the sheet; YOLO toggles forward to the VM but **keep the sheet open** (a Switch is a state-change the user may want to immediately reverse). See the [Status Sheet hosting](thread-screen-how-it-works-sheets.md#status-sheet-hosting-post-254) section below for the host wiring.

### The arm order (#1311)

Not to be confused with the retired `ThreadStatusRow` above (`model · effort`) — this is `ThreadStatusArea`,
the composer's turn-status band (§ "fourth, static reading" above covers its `waitingForAnswers` arm, above
this order).

[#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311) ported desktop's `workingIndicatorState` so
the band keeps one reading up for the whole running turn, instead of going dark once no tool is open during
`responding`. The precedence used to live only as a `when`'s clause order; it now lives once, as `statusArm`
beside `StatusReading`, returning a `StatusArm` enum: `None, Connection, Resetting, ApiRetry, Compacting,
Stalled, TurnOutcome, Thinking, Working, RunningTool`, top wins. Reset session now outranks api-retry
(matching desktop); `Stalled` (riding [`StallProjection`](stall-state.md) unchanged) and `Working` (the
`responding` phase, no open tool) are new. Rationale:
[Thinking indicator § Working and stalled](thinking-indicator.md#working-and-stalled-1311).
