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
selection internally. The `LazyColumn` passes `verticalArrangement = Arrangement.Top` (#1509; before
that it took the reversed-list default, `Arrangement.Bottom`, which pinned a stream shorter than the
viewport against the composer instead of under the header, as Figma `640:2646` and the pending-request
frame `639:2242` draw it). Under `reverseLayout = true`, `Arrangement.Top` places a short stream's
newest-first content at the viewport's top edge — the visual top of the arrangement is the thread's
*bottom* (newest) end, so this still reads newest-at-the-bottom once the reversed items are drawn; an
overflowing stream fills the viewport regardless of arrangement, so scrolling is unaffected. A short
list still reports `FollowNewestEnd`'s first-visible index and offset as 0, and the oldest row still
sits at the viewport's far edge for `isNearOldestEnd`, so neither reader needed a change. Covered by
`ThreadScreenShortStreamTest` (see [Thread screen — testing § Short-stream top anchoring
(#1509)](thread-screen-testing.md#short-stream-top-anchoring-1509)).

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

Split out to [Thread screen — subagent tool-row nesting](thread-screen-subagent-tool-rows.md) on 2026-10-01 to keep this document under the docs guard's size cap. See that document for the full section.

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

**Prompt arrival and edits are excluded from the growth signature while a batch is mounted (#1304), so field
sizing never drags a reader down.** Originally the streaming size-driven pin gained `questionState != null`
to its key and early-return guard, because item 0 while a batch is held is the prompt actions row, not a
streaming message bubble, and its field sizing (the Other `BringIntoViewRequester`, the IME) must not read as
growth. [#1314](https://github.com/pyrycode/pyrycode-mobile/issues/1314) carries the same rule forward:
`FollowNewestEnd`'s `promptPresent` parameter masks the newest row's content and the anchor's size out of the
growth signature while a prompt is mounted, so editing a field or the IME opening still cannot pull a history
reader down — see [Thread screen — subagent tool-row nesting § The newest-end follow rule
(#1314)](thread-screen-subagent-tool-rows.md#the-newest-end-follow-rule-1314).

**A newly arrived batch reveals itself to a reader already at the newest end, on the same rule that follows
a new message row.** A verifier pass on #1305 found this gap live, before the #1314 rework below existed:
an operator watching the newest end saw only the composer's "Waiting for answers" status reading (below)
until they thought to scroll further, because prompt rows insert below the anchored first visible row the
same way a new message row would, landing off-screen until something scrolls back to index 0. #1305 closed
it with a dedicated effect keyed on `questionState?.generation`, shaped like the #981 newest-row pin it sat
beside. **[#1314](https://github.com/pyrycode/pyrycode-mobile/issues/1314) folded that effect into the one
following rule** described in [Thread screen — subagent tool-row nesting § The newest-end follow rule
(#1314)](thread-screen-subagent-tool-rows.md#the-newest-end-follow-rule-1314): the prompt's identity
(`shownQuestion?.generation to openRequest?.modalId` — see #1306 and #1341 below for why it reads
`shownQuestion`) is part of `FollowNewestEnd`'s growth signature, so a new generation pins a reader who is
following exactly as a new message row would, and does nothing to a reader who has scrolled into history.
`scrollToItem(0)` reveals the actions row and the latest question, not the title first: under reverse
layout, scrolling to the title of a batch taller than the viewport would need a second post-measure scroll,
and a batch that already fits the viewport already shows its title at `scrollToItem(0)`. Covered by
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
[#1483](../../specs/architecture/1483-permission-request-frames.md) adds the sibling
`waitingForPermission: Boolean` (`openRequest != null && connectionState == ConnectionState.Connected`),
which reads `thread_status_waiting_for_permission` ("Waiting for permission") in the same box, behind the
same snowflake glyph, when `waitingForAnswers` is false. The two never race: `shownQuestion` is withheld
whenever a request is open (§ *Inline permission rows* below), so a question's own "Waiting for answers"
reading cannot show while a permission is open.

**`QuestionPromptProtection`** (the `FLAG_SECURE` / obscured-touch guard the old dialog window used to own)
is mounted directly by `ThreadScreen` — right after the screen's `rememberSaveable` block, so it is active
for the composition's whole life while any batch is held, independent of which prompt rows happen to be
visible. Since [#1306](permission-modal-overlay.md) the guard's condition is `questionState != null ||
openRequest != null` from **one call site**, so a question batch handing off to a permission request (or
the reverse) keeps the same owner throughout — see § *Inline permission rows and the shared reveal* below.
See [Question batch modal § Rendering](question-batch-modal.md#rendering) for the shared-ownership
mechanism this guards against overlapping navigation transitions. [#1341](https://github.com/pyrycode/pyrycode-mobile/issues/1341)
later withholds the question from the rows it draws while a request is open, but this condition still reads
`questionState`, not the withheld view — see § *Inline permission rows and the shared reveal* below for why.

### Inline permission rows and the shared reveal (#1306)

[The pending permission or trust request](permission-modal-overlay.md) moved out of its own dialog window
into this same `LazyColumn` in #1306, following the #1305 question batch above almost row for row.
`openRequest?.let { open -> permissionRequestItems(...) }` emits, immediately before the question items (so,
under `reverseLayout = true`, drawn **below** the question rows and **above** every message row — the
newest content on screen): Cancel, then the card (title, prompt, decision context, the session-grant offer
and the options) — see [Permission-modal overlay](permission-modal-overlay.md) for what each renders and its
`permission-cancel:$modalId` / `permission-card:$modalId` key scheme. The title moved inside the card as its
first line in [#1483](../../specs/architecture/1483-permission-request-frames.md), dropping the separate
`permission-title:$modalId` item. The empty-thread branch and `promptRowCount` both gained a matching
`openRequest` term (§ above and [the oldest-end history demand](thread-screen-oldest-end-history-demand.md)); `promptRowCount` adds a fixed
`PERMISSION_ROW_COUNT` rather than deriving a size from the request, since a permission/trust request always
renders the same row count — **2** since #1483 (was 3, when the title was its own item).

**The growth mask and the newest-end reveal generalize to either prompt kind rather than duplicating.** The
mask that excludes prompt content and sizing from the growth signature (§ above) reads `questionState != null
|| openRequest != null` (`FollowNewestEnd`'s `promptPresent`), in place of the #1305 `questionState != null`
alone. The reveal's identity widened from `questionState?.generation` alone to a `Pair`,
`shownQuestion?.generation to openRequest?.modalId` (`promptIdentity`, since #1341 below reads `shownQuestion`
rather than `questionState`), so either a new question generation or a new permission `modalId` — never both
signals doing the same job twice — pins a following reader under the one rule § *Inline question rows and the
newest-end reveal* above describes, which this reuses verbatim.

**Leaving the request open does not cancel it.** Back, opening another conversation, and switching to a
different chat all leave the request rendered (subject to the usual scroll-position rules above) rather
than dismissing it — only an explicit Cancel tap or a daemon resolution removes it. `MainActivity`'s
`DisposableEffect(vm) { onDispose { vm.onConversationLeft() } }` clears an armed non-default option on the
way out so a returning reader needs two fresh taps, but never touches the request itself or the
session-grant draft; see [Modal answer flow § Leaving the conversation](modal-answer-flow.md#leaving-the-conversation-clears-the-arm-not-the-grant-1306).

**[#1341](https://github.com/pyrycode/pyrycode-mobile/issues/1341) makes the permission request take
precedence over the question while both are outstanding in one chat.** `ThreadScreen` derives `val
shownQuestion = questionState.takeIf { openRequest == null }` beside `openRequest` and reads `shownQuestion`
everywhere it had read `questionState` to draw or measure the inline question — the question's lazy items,
`promptRowCount`, the empty-thread gate, and the `promptIdentity` reveal key, which widens again to
`shownQuestion?.generation to openRequest?.modalId`. The composer's `waitingForAnswers` reading switches to
`shownQuestion != null` too, so it never names a question that is not on screen. This mirrors desktop's
`ComposerSlot`, which hides `QuestionPanelSlot` while `selectHasOutstandingFor(conversationId)` holds. The
question's picks and Other text live in the hoisted `QuestionModalState` (#1305's draft store), never in
composition, so leaving the question out of composition loses nothing: `shownQuestion` reverts to
`questionState` the moment the request resolves, with the selection and Other text intact, and the
`promptIdentity` change reveals it to a reader parked at the newest end the same way a fresh arrival does.

**`QuestionPromptProtection` and `FollowNewestEnd`'s `promptPresent` keep reading `questionState`, not
`shownQuestion`.** Both conditions already hold whenever either prompt exists, so the withholding changes
nothing in practice — but it is a deliberate choice, not an oversight: keying either one on `shownQuestion`
would drop it for the one frame between the permission request resolving and the question returning, since
`shownQuestion` is briefly `null` on both sides of that handoff from the request's own perspective. The rule
generalizes past this ticket — a presence or protection check belongs on the state that exists, not on
whichever state happens to be the one currently rendered.

### The oldest-end history demand and retry (#777, #778, #1569, #1572)

Split into [Thread screen — the oldest-end history demand](thread-screen-oldest-end-history-demand.md)
when this document passed the size cap. Covers `OlderHistoryGesture`/`olderHistoryPull`, the 200dp ask
band, the retry/dead-end/offline tail, and the newest-page ask an open thread sends every time its host
becomes available (at open and after every reconnect, #1572 — widened from #1569's never-loaded-only
opening ask, because a reply stored while the thread was off-screen was never cached and a reconnect
discarded it).

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

**The `#777` history-loading row is inside this `else` arm too, which is a known gap.** The oldest-end loading affordance ([the oldest-end history demand](thread-screen-oldest-end-history-demand.md)) is appended inside the `LazyColumn`, so a channel that reads as empty (`!state.hasMessages`) renders `EmptyThreadState` instead while the opening history ask is in flight — the empty-thread case the ticket set out to fix. Verifier-flagged SHOULD FIX, not addressed by #777; see that document for the detail.

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

**The band is always composed, one glyph (#1312).** `StatusArm.None` still makes `StatusReading` emit
nothing, but the band no longer collapses when it does: `ThreadStatusArea` always draws the snowflake
(`ThreadStatusGlyph`) at its leading edge, in a weighted reading box that holds its place even when empty, so
the input field's position does not depend on which arm — or no arm — is showing. The waiting-for-answers
reading (above this order) still draws its own question glyph in the snowflake's place instead. The glyph
turns while `ThreadViewModel.isBusy || localSendPending` holds, independent of `statusArm`, so switching arms
never restarts the rotation; see [Thinking indicator § The band is always composed, and the glyph always
turns with it](thinking-indicator.md#working-and-stalled-1311) for the gate and the per-arm cleanup.
