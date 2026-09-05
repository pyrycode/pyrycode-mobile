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
