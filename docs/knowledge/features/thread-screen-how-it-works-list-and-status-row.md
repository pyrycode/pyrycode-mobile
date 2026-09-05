# Thread screen — how it works, the list, the chip, the empty state and the status row

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### `LazyColumn(reverseLayout = true)` — established in #126, populated in #246, dimmed in #136, nested in a `Column` since #201

The body shape since [#246](../codebase/246.md) iterates `state.items.asReversed()` with stable composite keys and dispatches at the `ThreadItem` sealed-interface level only — `MessageItem` → `MessageBubble(message = item.message)`, `SessionBoundary` → `SessionBoundaryDelimiter(boundary = item)`. The screen does **not** re-dispatch by `Message.role`; [`MessageBubble`](message-bubble.md) owns that selection internally. No `verticalArrangement = Arrangement.Bottom` override — `reverseLayout = true` already pins the first item to the bottom edge.

**Source-list reversal is required.** `observeMessages` returns items chronologically ascending (index 0 = oldest), but `LazyColumn(reverseLayout = true)` draws the **first** item at the bottom. For "newest at the bottom" the screen reverses before passing — `state.items.asReversed()` is the Kotlin stdlib O(1) view (no allocation, no copy), and it's a `List<ThreadItem>` so it slots into `itemsIndexed(...)` directly. Keys are computed from the underlying items, so the view's reversed index is irrelevant for identity.

**Stable keys are per-subtype with a string namespace prefix.** `MessageItem` → `"msg:${item.message.id}"` (the canonical row identity assigned at message creation in `FakeConversationRepository.sendMessage`, surviving all state transitions). `SessionBoundary` → `"boundary:${item.previousSessionId}->${item.newSessionId}"` — each transition is unique by construction (a session can only become "previous" once per stream, and the fake's `buildThreadItems` only emits a boundary when `prior != null && prior != message.sessionId`). The `"msg:"` / `"boundary:"` prefixes namespace the two subtypes so no key collision is possible between a message id and a session id that share a string. The `itemsIndexed(...)` key lambda ignores the `Int` first arg — identity stays anchored to item fields, not position (anti-pattern to mix the two).

**Above-delimiter opacity (since [#136](../codebase/136.md)).** Each row is wrapped in `Box(Modifier.alpha(rowAlpha))` around the existing `when (item)` dispatch. `rowAlpha` is computed inline: a `chronologicalIndex` is reconstructed from the reversed-list index (`state.items.size - 1 - reversedIndex`), then compared strict-`<` against a `cutoffChronologicalIndex = remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }`. Rows above the cutoff render at the file-private `ABOVE_DELIMITER_ALPHA = 0.55f` constant; rows at or after the cutoff (including the boundary itself) render at `1f`. `mostRecentSessionBoundaryIndex` is an `internal` top-level helper at the bottom of the file (`items.indexOfLast { it is ThreadItem.SessionBoundary }`); its `-1` return for the no-boundary case combines with the strict `<` to give AC3 ("zero boundaries → all rows full opacity") for free. The wrap inherits to every row variant — user/assistant `MessageBubble`, `ToolCallRow`, nested `SessionBoundaryDelimiter` — because `Modifier.alpha(...)` is a render-only `graphicsLayer` effect and none of the row composables hold internal opacity state. **Interaction is not gated** — `ToolCallRow`'s `clickable` `Surface` stays expandable above the cutoff (alpha runs in the draw layer, after pointer input). That matches the user-story intent ("still legible, can scroll up and re-read"); if a future ticket gates above-cutoff interaction, it adds the gate at the inner `Surface`'s `enabled =` (not by stripping the alpha modifier).

Post-#201 the `LazyColumn` is nested inside a `Column` wrapper alongside the `ConnectionBanner` (see [Connection-banner wiring](thread-screen-how-it-works-overlays-and-app-bar.md#connection-banner-wiring) below). The list carries `Modifier.fillMaxWidth().weight(1f)` rather than `.fillMaxSize()` — inside a `Column`, `fillMaxSize` ignores `weight` semantics and over-claims vertical space, fighting with siblings. The `reverseLayout = true` semantics are unchanged: the list scrolls upward from the bottom of its weight-allocated region, with the banner pinned above it.

**Streaming auto-scroll (since [#185](../codebase/185.md)).** While any `ThreadItem.MessageItem` in `state.items` carries `message.isStreaming = true`, the `LazyColumn` keeps the streaming bubble's growing bottom edge anchored at the viewport bottom. Six composition-scoped pieces of state hoisted at the top of the `else` block — adjacent to the existing `reversedItems` / `cutoffChronologicalIndex` lines — implement it: `val listState = rememberLazyListState()` (threaded as `state =` on the `LazyColumn`); `val hasStreamingMessage by remember(state.items) { derivedStateOf { state.items.any { it is ThreadItem.MessageItem && it.message.isStreaming } } }` (the gate on the auto-pin coroutine, scan re-runs only when the list reference changes); `var userScrolledAway by remember { mutableStateOf(false) }` (yield flag); `val autoScrollNestedScroll = remember { object : NestedScrollConnection { ... } }` (sets `userScrolledAway = true` iff `source == NestedScrollSource.UserInput && available.y != 0f`, attached via `Modifier.nestedScroll(autoScrollNestedScroll)` on the column); `LaunchedEffect(listState) { snapshotFlow { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }.collect { atBottom -> if (atBottom) userScrolledAway = false } }` (resumes auto-follow when the user manually returns to the bottom); and `LaunchedEffect(hasStreamingMessage, listState) { if (!hasStreamingMessage) return@LaunchedEffect; snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0 }.distinctUntilChanged().collect { if (!userScrolledAway) listState.scrollToItem(0) } }` (the auto-pin loop — re-anchors on every layout-pass size change of item 0). `scrollToItem(0)` (not `animateScrollToItem`) is the right primitive: instant, O(1) when already pinned, and it does **not** dispatch through `NestedScrollSource.UserInput` so it cannot recursively trip its own yield flag. Reverse-layout's bottom anchor is `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`; both effects rely on that. The auto-pin `LaunchedEffect` is gated by `hasStreamingMessage`, so non-streaming threads start no collector (AC4); when `isStreaming` flips `false` the effect re-launches with the new key and the early-return cancels the collector (AC3). The Phase-0 seed never flips `isStreaming = false` (the static seed stays streaming forever) — AC3's cancel path is verifiable only by code-review or a local seed flip + re-install; Phase 4's WS feed will exercise it naturally. The companion concern from [#184](../codebase/184.md) — `StreamingAssistantBody` losing its `revealedLength` when the bubble scrolls off-screen — is sidestepped (not solved) in the auto-pin happy path: keeping the bubble in the viewport prevents disposal. If the user yields by scrolling away during streaming, the bubble can still off-screen and re-reset on return; the Phase-4 hoist-into-VM fix from [#184](../codebase/184.md) is the proper remedy.

### Workspace-chip wiring (post-#137)

Between the `ConnectionBanner` and the `LazyColumn`, the body `Column` carries a conditional [`WorkspaceChip`](workspace-chip.md):

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

[`EmptyThreadState`](empty-thread-state.md) renders the centered "Send a message to get started" prompt; the caller supplies the `weight(1f)` so the prompt fills exactly the space the list would have occupied. The 24.dp horizontal inset is intentionally 8dp wider than the chip's `horizontal = 16.dp` — a centered single line wants more breathing room than a left-aligned chip on the 360dp portrait minimum. Predicate is `!state.hasMessages`, not `state.items.isEmpty()` — symmetric with the chip's `!hasMessages` half (chip and prompt appear/disappear together) and correct for the `SessionBoundary`-only edge case where the user taps the chip → `changeWorkspace` emits a boundary before any message lands (the prompt stays visible until a real `MessageItem` arrives, instead of letting a lonely delimiter float above the input bar). The `remember(state.items) { mostRecentSessionBoundaryIndex(...) }` block from [#136](../codebase/136.md) lives **inside the `else` arm only** — its key is `emptyList()` in the empty arm, so computing the cutoff there is wasted work.

### Status-row wiring (post-#145)

Inside the same `Scaffold.bottomBar` slot, the [`ThreadStatusRow`](thread-status-row.md) stacks above the [`ThreadInputBar`](thread-input-bar.md) inside a shared wrapper `Column`:

```kotlin
bottomBar = {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThreadStatusRow(
            model = state.selectedModel.label(),         // String → enum-derived in #253
            effort = state.selectedEffort.label(),       // String → enum-derived in #229
            onExpandClick = { sheetVisible = true },     // wired internally in #254
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Pre-#145 the slot held only `ThreadInputBar(onSend = onSendMessage)`. The wrapper `Column` is one of two new things in #145; the other is the `ThreadStatusRow` itself. Crucially, **`Modifier.imePadding()` lives inside `ThreadInputBar` on its own outer `Column` (since [#188](../codebase/188.md)), not on the `Scaffold` bottomBar** — so only the input bar lifts when the soft keyboard opens, and the status row stays visually anchored above the lifted input bar. No new `imePadding` on the outer wrapper.

The three new `ThreadUiState` fields shipped in #145 (`model`, `effort`, `tokenPercent`) were populated from companion-object constants (`STUB_MODEL = "Opus 4.7"`, `STUB_EFFORT = "high"`, `STUB_TOKEN_PERCENT = 73`) inside the same `combine(...)` block. In [#253](../codebase/253.md) `model: String` was retyped to `selectedModel: Model` and rewired to a pre-combined `selectedModelFlow = combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }`; the `STUB_MODEL` constant was deleted. In [#229](../codebase/229.md) `effort: String` was retyped to `selectedEffort: Effort` and rewired to a pre-combined `selectedEffortFlow` mirroring `selectedModelFlow`'s shape; `STUB_EFFORT` was deleted. `STUB_TOKEN_PERCENT` survived through #602, still landing inside the [`warning`](warning-color.md) band so the threshold-driven color rendered live in the running app, not just in previews — but by then nothing read it, since [#602](../codebase/602.md) had already dropped `ThreadStatusRow`'s usage segment. [#603](../codebase/603.md) executed the predicted Phase-4-swap-point cleanup: `tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants (the companion object's only members) are all deleted; `ThreadUiState` no longer has a token-percent concept. #591 will populate a real figure from a backend `AgentStatus` flow when the daemon serves one — against a shape it designs itself, not a restoration of these fields.

[#507](../codebase/507.md) added a trailing, defaulted `mutationsSupported: Boolean = true` to `ThreadUiState`, populated differently from every field above: not from a `combine` source but from a **snapshot captured once at VM construction** (`private val mutationsSupported = repository.mutationsSupported`), then written into **both** the `combine` result and the `.stateIn` `initialValue` so the two can never disagree. The mode is static per build config (a Koin fake-vs-relay swap, never a runtime toggle), so a one-shot snapshot is sufficient; reading through the [facade](stable-conversation-repository.md) here is where its null-connection → `false` fail-safe-deny takes effect. **The field was dormant in #507 (present in state, read by nothing); [#508](../codebase/508.md) wired the two consumers** — `ThreadScreen` now passes `mutationsSupported = state.mutationsSupported` into `ThreadTopAppBar` (→ [`ThreadOverflowMenu`](thread-overflow-menu.md), gating out New session / Rename / Change workspace / Archive) and into [`ChannelInfoSheet`](channel-info-sheet.md) (gating out the whole Actions section). Both surfaces gate on this signal and nothing else — no `USE_RELAY_REPOSITORY` / instance-type / relay-vs-fake check inside the composables. In fake mode (the default) it is `true` end-to-end, so both surfaces are unchanged.

The row reads the typed enums as `state.selectedModel.label()` (via the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `data/preferences/Model.kt`) and `state.selectedEffort.label()` (via the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `ui/settings/EffortPickerDialog.kt:77`, widened from `internal` to public in [#229](../codebase/229.md) when this slice became the second consumer) — `ThreadStatusRow`'s `model: String` / `effort: String` parameter shape is preserved so the row's previews, its signature, and its internal `AnnotatedString` body stay byte-identical. `ThreadViewModel.onModelSelected(model: Model)` and `onEffortSelected(effort: Effort)` are the per-conversation override surfaces — synchronous `MutableStateFlow.value` writes, no `appPreferences.setDefault*(...)` calls (overrides are in-memory only; Phase 4 will persist).

Post-[#254](../codebase/254.md) the `onExpandClick` parameter on `ThreadScreen` is **deleted** — the screen owns the trigger via an internal `{ sheetVisible = true }` lambda passed straight to the row. A new `onModelSelected: (Model) -> Unit = {}` parameter takes its slot on the signature, bound to `vm::onModelSelected` ([#253](../codebase/253.md)) at the `MainActivity` destination. [#229](../codebase/229.md) appended two more sheet callbacks: `onEffortSelected: (Effort) -> Unit = {}` and `onYoloToggled: (Boolean) -> Unit = {}`, both bound to the matching `vm::` method references. Tapping the row opens the [`StatusSheet`](status-sheet.md); model/effort selections forward to the VM and auto-close the sheet; YOLO toggles forward to the VM but **keep the sheet open** (a Switch is a state-change the user may want to immediately reverse). See the [Status Sheet hosting](thread-screen-how-it-works-sheets.md#status-sheet-hosting-post-254) section below for the host wiring.
