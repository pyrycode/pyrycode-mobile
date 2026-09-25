# Thread screen — how it works, ViewModel state

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### `SavedStateHandle.get<String>("conversationId").orEmpty()` (lifted to a `private val`)

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes. Post-#139 the `conversationId` is lifted to a `private val` field on the VM so the `map { … }` body and the `stateIn(initialValue = …)` both reference one source of truth; before #139 the inline `savedStateHandle.get<String>("conversationId").orEmpty()` lived directly in the `MutableStateFlow(...)` constructor. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the title `Text` is non-null at every callsite.

### `combine(observeConversations, observeMessages, pendingWorkspacePicker).stateIn(WhileSubscribed)` — three upstreams since #137

Post-#137 the single `.map { … }` over `observeConversations(All)` was widened to a `combine` of three upstreams:

```kotlin
combine(
    repository.observeConversations(ConversationFilter.All),
    repository.observeMessages(conversationId),
    pendingWorkspacePicker,
) { conversations, items, pickerVisible ->
    val conv = conversations.firstOrNull { it.id == conversationId }
    ThreadUiState(
        conversationId = conversationId,
        displayName = conv?.displayName() ?: conversationId,
        isPromoted = conv?.isPromoted ?: false,
        hasMessages = items.any { it is ThreadItem.MessageItem },
        workspaceLabel = conv?.workspaceLabel() ?: "scratch",
        workspacePickerVisible = pickerVisible,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = ThreadUiState(conversationId, displayName = conversationId))
```

`conv?.workspaceLabel()` in the snippet above called the **private extension function** `Conversation.workspaceLabel()`, then declared lower in this file (cwd-basename fallback: `"scratch"` for the sentinel cwd, else `cwd.substringAfterLast('/')`) — a name confusable with the domain property `Conversation.workspaceLabel: String?` added in #720 (daemon-authored opaque text, see [`data-model.md`](data-model.md#conversation)) by exactly one pair of parentheses: a `String?` property is not invokable, so the trailing `()` was what kept the call resolving to the extension rather than the property.

That trap is retired: [`#722`](https://github.com/pyrycode/pyrycode-mobile/issues/722) deleted the extension and replaced the call site with `workspaceLabel = workspaceDisplayName(cwd = conv?.cwd ?: "", label = conv?.workspaceLabel)`, where `workspaceDisplayName(cwd: String, label: String?): String` is a shared top-level function in `de.pyryco.mobile.ui.workspace` (`app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt`) — no `Conversation` receiver, so it cannot be confused with the property by a missing or extra pair of parens. `conv?.workspaceLabel` (no parens, the property) is now the only workspace-labelled symbol read off `Conversation` at this call site; it flows into the second, explicitly-named `label` parameter. See [`workspace-chip.md`](workspace-chip.md#workspacelabel-derivation) for the rule itself, including the unconditional label-wins-over-scratch arm and the `MAX_WORKSPACE_LABEL_CHARS` render-path clamp.

`pendingWorkspacePicker = MutableStateFlow(false)` is a private hot source backing the chip-opens-picker signal. `observeMessages` re-emits on `sendMessage` (the `hasMessages` flip happens there) and on `changeWorkspace` (a new `SessionBoundary` arrives; `hasMessages` stays `false` because boundaries don't count toward the `MessageItem`-only filter). The `initialValue` block is byte-identical to its pre-#137 shape — it still constructs `ThreadUiState(conversationId, displayName = conversationId)` with the four new fields defaulting; the `state_initialValue_isConversationIdPlaceholderBeforeSubscription` test continues to pass full equality. Conversation-missing edge case: `conv` is `null` → `isPromoted = false` (treated as discussion), `workspaceLabel = "scratch"` (safe default for the chip). The `combine` over three independent signals was the right shape; collapsing the message subscription into the conversations map by calling `observeMessages(id).first()` inside the lambda would have blocked the upstream — see [`../codebase/137.md`](../codebase/137.md) lessons learned.

### `observeConversations(All).map { firstOrNull }` — option (c) for `displayName` derivation

\#139 picked option (c) of three plumbing options for surfacing `displayName` in `ThreadUiState` — filter the existing `observeConversations(All)` flow inside the VM rather than adding a route nav arg (option a) or a new `observeConversation(id)` repo method (option b). Tradeoffs:

| Option | Files | Tradeoff |
| --- | --- | --- |
| (a) Second nav arg `displayName` | Route + 4 call sites + VM + state | URL-encoding for unicode at every call site, scatters fallback logic, no live-rename re-emission |
| (b) New `observeConversation(id)` on repo | Interface + Fake + VM + state + screen + new top-bar file = ≥5 `.kt` | Cleanest contract long-term; busts the S file-count red line |
| **(c) Filter `All` in VM** | VM + state + screen + new top-bar file + AppModule = 4 `.kt` | One-call-line scan, free rename re-emission, no repo-surface change — **picked** |

The `firstOrNull { it.id == conversationId }` scan is `O(n)` per upstream emission — acceptable at Phase 0 fake-cardinality (~5 records). When the Phase 4 Ktor-backed impl lands and the per-emission scan has a real cost, a follow-up introduces `observeConversation(id): Flow<Conversation?>` (option b's endpoint). `ConversationFilter.All` is the right filter (not `Channels` / `Discussions`) because the thread screen is reachable from the archive screen and future deep links; restricting to non-archived would silently break those paths.

### `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`

Same lifetime policy as `ChannelListViewModel` / `DiscussionListViewModel`: the upstream subscription re-uses across configuration changes (rotation) without leaking when the screen leaves the back stack for >5s. The `initialValue` falls back to `ThreadUiState(conversationId, displayName = conversationId)` — before the upstream's first emission lands, the AppBar renders the path id, matching the post-emission fallback when the id is missing. The fake's `MutableStateFlow.map` chain emits synchronously, so in practice the placeholder is invisible; it exists for type safety and process-death restoration.

The pre-#139 VM was `MutableStateFlow(initial).asStateFlow()` (synchronous, hot from line 1, no `viewModelScope.launch`). The shape upgrade to `stateIn(WhileSubscribed)` is **byte-identical at the destination**: the `val state: StateFlow<ThreadUiState>` surface and the `val state by vm.state.collectAsStateWithLifecycle()` consumer pattern do not change. The interchange is deliberate — picking `MutableStateFlow` in #126 cost zero at the destination so that #139's widening to a cold-flow upstream was a drop-in replacement.

### `private fun Conversation.displayName()` — re-declared, not extracted

```kotlin
private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
```

The same extension also lives **privately** in `ArchivedDiscussionsScreen.kt:164-166`. #139 deliberately did **not** extract to a shared helper — promotion to `internal fun` in a new file would have pushed the spec to a 5-`.kt` diff (busting the S-budget). Two occurrences is below the canonical extract-on-third-use threshold; the third caller (likely #140's overflow menu's "Rename …" label) is the ticket that extracts to `internal fun Conversation.displayName()` under `ui/conversations/` and deletes both private copies.

The fallback strings are **Kotlin literals**, not `stringResource(R.string.untitled_discussion)`. ViewModels have no `Context`, and the existing private extension in `ArchivedDiscussionsScreen.kt` has been shipping with literals since #176; maintaining symmetry with the existing pattern is more important than tightening string-resource layering in #139. Revisit when the app needs localization beyond English — at that point the fallback moves into the UI layer (the composable that consumes `displayName`) rather than the VM.

### Display-name fallback chain

`conv?.displayName() ?: conversationId` collapses through three layers:

1. `name?.takeIf { it.isNotBlank() }` — real name if set and non-blank
2. `if (isPromoted) "Untitled channel" else "Untitled discussion"` — kind-aware fallback for `name = null` or `name = ""`
3. `conversationId` — when `firstOrNull { it.id == conversationId }` returns `null` (path not user-reachable in production; defensive against deep links and process-death races)

`displayName` is always a non-null `String`, so the title `Text` never needs a null check.

### Free rename re-emission

When #141's rename success path eventually calls `repository.rename(conversationId, newName)`, the fake's `MutableStateFlow<State>` updates; `observeConversations(All)` re-emits a list with the renamed conversation; the `.map { … }` recomputes `displayName`; `stateIn` publishes the new `ThreadUiState`; `collectAsStateWithLifecycle()` triggers recomposition. The `state_displayName_reemitsOnRename` test pins this — #141's only TopAppBar-related work is wiring the dialog's success path to `repository.rename(...)`; the live-title-update is already wired. Out-of-scope item from #139's ticket body ("plumbing a live rename back into the title without re-navigating — covered when #141 lands") is actually **already free** post-#139.

### `items` and `queuedMessages` stay two `ThreadUiState` fields — the join with the backlog is render-time, not VM-time (#782)

`threadContent: Flow<ThreadContent>` (introduced by #461, widened by #778) combines
`threadItems`, `repository.observeQueue(conversationId)` and `historyDemand` into one
`ThreadContent(items, queued, historyTail)` carrier — the pre-combiner that keeps the outer
five-arm `combine(...)` at its typed arity ceiling (`observeConversations`, `threadContent`,
`pendingWorkspacePicker`, `transientDialogs`, `runConfigFlow`). `ThreadUiState` publishes `content.items`
and `content.queued` as two **separate** fields, `items: List<ThreadItem>` and
`queuedMessages: List<QueuedMessage>` — #782 does not touch this VM, this combine, or either field's
shape.

What #782 added lives entirely downstream, in [`ThreadScreen`](thread-screen-how-it-works-list-and-status-row.md):
`foldQueuedRows(state.items, state.queuedMessages)` joins the two fields into the `ThreadRow` list the
screen actually renders. The join is deliberately **not** a third VM-owned projection — `queue_state` is
daemon state (server SSOT pyrycode#720), not part of the `ThreadFold` turn-stream reducer that produces
`threadItems`, so folding it in here would blur a line the architecture keeps on purpose: the VM surfaces
two independent, still-separately-observable signals, and only the view is allowed to know that one of
them sometimes annotates the other. It is also why a replacing `queue_state` snapshot needs no
reconciliation logic anywhere in this file — `content.queued` is replaced wholesale by the next
`observeQueue` emission exactly as it always was, and the screen's `remember`-cached fold re-derives every
row from whatever `items` / `queuedMessages` pair is current. See
[Queued backlog rendering § The render-time join](queued-backlog-section.md#the-render-time-join-782) for
the fold's five correlation rules and [the list section](thread-screen-how-it-works-list-and-status-row.md)
for where it is called.

### The model-menu agent filter (#1110)

`runConfigFlow`'s menu arm — `repository.observeModelMenu(conversationId)` — is a separate `combine` from
`state`'s, at Kotlin's five-typed-argument ceiling already (`sessionSettings`, the menu, `pendingModel`,
`pendingEffort`, `pendingPermission`). Filtering a merged `multi_agent` menu down to this conversation's own
rows therefore needs the agent as a **sixth** input, which does not fit — so it joins by a chained
`.combine(conversationAgent) { menu, agent -> menu?.forAgent(agent) }` on the menu flow itself, ahead of the
five-arm `combine`, rather than growing that combine's arity. `forAgent` (a private top-level extension on
`ModelMenu`) keeps only the rows whose `agent == agent` (daemon order preserved) and zeroes `droppedModels`
for every agent but Claude — the count is the daemon's own tail-cut of Claude's rows and does not apply to
Codex's. Because this filter runs on the menu arm before `runConfig` applies `MAX_RENDERED_MODEL_CHOICES`,
`hiddenChoices` counts the filtered list, and `effortRecall.offer` (see [Thread composer footer § Remembered
effort recall](thread-composer-footer-effort-recall.md)) receives only the conversation's own agent's rows
through the ordinary `state` combine — no separate wiring was needed for AC 5 to fall out of the filter.

**`conversationAgent`, not the `state` combine's own `agent` field, feeds this filter — the two are resolved
twice on purpose.** [#1114](https://github.com/pyrycode/pyrycode-mobile/issues/1114) already reads
`conv?.agent ?: ConversationAgent.Claude` inline inside the `state` combine's lambda to set
`ThreadUiState.agent` (see [Data model § the agent name](data-model.md)); that value lives only inside the
lambda and cannot be reused by `runConfigFlow`, a sibling `combine` chain built from a different flow. #1110
instead hoists a private `conversations: Flow<List<Conversation>>` — `repository.observeConversations(All)`
shared with `shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)` — and derives
`conversationAgent: Flow<ConversationAgent> = conversations.map { it.firstOrNull { c -> c.id ==
conversationId }?.agent ?: ConversationAgent.Claude }.distinctUntilChanged()` from it, so both `runConfigFlow`
and `state` can each read the same upstream. **`state`'s first combine arm changed from
`repository.observeConversations(ConversationFilter.All)` to this shared `conversations`** for exactly that
reason — `RemoteConversationRepository.observeConversations` sends a `list_conversations` request on every
new subscription, so a second, independent subscription for the filter would have doubled that request per
thread opening. `shareIn` with no stop timeout is deliberate: the shared flow only needs to outlive `state`'s
own `WhileSubscribed(5_000)` upstream, never longer, so it stops with `state`'s last subscriber rather than
lingering on its own separate timeout.

**Edge case flagged by review, not yet reachable.** `conversationAgent` reads `Claude` while the shared list
does not yet hold this conversation's row — the same default `Conversation.agent` and `conversationAgentOf`
already use. Today that only happens before the first `list_conversations` reply lands, and nothing in
`state` or `runConfigFlow` can observe the model menu or offer a recall before then. If a future change ever
let `state` collect for a conversation the list hasn't upserted yet — e.g. a thread opened immediately after
local creation, before the daemon's confirmation round-trips — `EffortRecall.offer` would decide once against
Claude's rows (including the `default` row when nothing is saved) for a Codex conversation, and never
reconsider once `decided` flips. The daemon would refuse the resulting write, so no visible corruption
follows, but the recall opportunity for that opening would be spent on the wrong agent's vocabulary. Revisit
this note before changing when `state` starts collecting relative to the conversation list's first emission.

**#1116 duplicated this subscription, non-blocking.** The question-modal title also needs this
conversation's agent, but its collector lives outside `state`'s combine chain and was planned before this
section's shared `conversations` flow landed. It calls `repository.observeConversations(ConversationFilter.All)`
on its own rather than reading `conversationAgent`, so holding a question batch opens a second
`list_conversations` subscription alongside this one. `StateFlow` conflation absorbs it, so the verifier
passed it as a SHOULD FIX rather than blocking the merge — see [Question batch modal § Title names the
conversation's agent (#1116)](question-batch-modal.md#title-names-the-conversations-agent-1116). Fold that
collector onto this file's `conversationAgent` before adding another such lookup.
