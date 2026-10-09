# Thread screen — how it works, ViewModel state

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### `SavedStateHandle.get<String>("conversationId").orEmpty()` (lifted to a `private val`)

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes. Post-#139 the `conversationId` is lifted to a `private val` field on the VM so the `map { … }` body and the `stateIn(initialValue = …)` both reference one source of truth; before #139 the inline `savedStateHandle.get<String>("conversationId").orEmpty()` lived directly in the `MutableStateFlow(...)` constructor. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the title `Text` is non-null at every callsite.

### `combine(observeConversations, observeMessages, pendingWorkspacePicker).stateIn(WhileSubscribed)` — three upstreams since #137

The current `state` combines shared conversations, complete `threadContent`, the
workspace picker, transient dialogs and run configuration. Confirmed read marks
and other action/status readings join independently. The historical three-arm
shape became this content carrier as rows, queue and history grew; it no longer
reads `observeMessages` directly or joins received evidence separately.

`threadContent` carries rows, queued messages, history tail, markers and exact
read evidence. Since #1968, both demo and relay destination factories inject
`ThreadContentScheduling`: sequential `ThreadFold.reduce` and `render` run on
`Dispatchers.Default`, and complete content waits for Compose's Android UI frame
clock backed by Choreographer. At each frame with completed pending work, the
latest complete value publishes once. Initial nonempty content and the final
burst value need no further input. Composer, dialogs, actions and confirmed read
marks remain outside this pacing arm. Row folds still run in composition; this
bounds content-driven updates rather than establishing measured scrolling gains.

Raw snapshot/live admission preserves each source's order before any conflation.
The unbounded buffer directly after `merge` fuses with its intake channel, ahead
of worker suspension. A bounded send can delay even bookkeeping placed before
reduction and push pressure back into the repository's 64-slot dropping live
source. Shallow probes with suspending sources miss that loss. Only complete
accumulated display values may replace one another; the raw backlog can grow
while the worker is behind and is released on collection cancellation.
`noteNewestBoundary` stays on main at snapshot admission, and matching live deltas
establish the nonempty baseline there. Clearing after queued reduction could
erase a later turn outcome even when the boundary's display version is skipped.

A single snapshot subscription supplies both rows and evidence. Each sequential
fold carries its latest snapshot evidence through `presentedAs(rows)` into the
complete content value; synthetic live text cannot borrow unrelated snapshot
claims. History-gap overlays wait for `historySeed`. Pending or skipped versions
grant no sight, and a later presented version can qualify without another
subscription or backfill. Keep restored-row, unknown-id and gap barriers alongside
[lifecycle, viewport and reveal qualification](thread-screen.md#what-it-does).
See [frame/read probes](thread-screen-testing.md#frame-paced-content-1968) and the
[#1968 plan](../../specs/architecture/1968-frame-paced-thread-content.md).

Workspace labels use `workspaceDisplayName(cwd, label)`, with the daemon's domain
property `Conversation.workspaceLabel` passed as the label. The former private
`Conversation.workspaceLabel()` extension is retired; see
[workspace-chip derivation](workspace-chip.md#workspacelabel-derivation).
The picker remains a private hot signal and missing conversations retain the
id title, discussion tier and scratch workspace fallbacks.

### `observeConversations(All).map { firstOrNull }` — option (c) for `displayName` derivation

\#139 picked option (c) of three plumbing options for surfacing `displayName` in `ThreadUiState` — filter the existing `observeConversations(All)` flow inside the VM rather than adding a route nav arg (option a) or a new `observeConversation(id)` repo method (option b). Tradeoffs:

| Option | Files | Tradeoff |
| --- | --- | --- |
| (a) Second nav arg `displayName` | Route + 4 call sites + VM + state | URL-encoding for unicode at every call site, scatters fallback logic, no live-rename re-emission |
| (b) New `observeConversation(id)` on repo | Interface + Fake + VM + state + screen + new top-bar file = ≥5 `.kt` | Cleanest contract long-term; busts the S file-count red line |
| **(c) Filter `All` in VM** | VM + state + screen + new top-bar file + AppModule = 4 `.kt` | One-call-line scan, free rename re-emission, no repo-surface change — **picked** |

The `firstOrNull { it.id == conversationId }` scan is `O(n)` per upstream emission — acceptable at Phase 0 fake-cardinality (~5 records). When the Phase 4 Ktor-backed impl lands and the per-emission scan has a real cost, a follow-up introduces `observeConversation(id): Flow<Conversation?>` (option b's endpoint). `ConversationFilter.All` is the right filter (not `Channels` / `Discussions`) because the thread screen is reachable from the archive screen and future deep links; restricting to non-archived would silently break those paths.

### `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`

The heading preserves an older inbound anchor; since #1968 the current policy is
`SharingStarted.WhileSubscribed()` with zero stop timeout. Last-collector exit
immediately cancels upstream intake, worker work, pending content and the frame
awaiter. Recollection rebuilds the fold from the repository's current snapshot;
no prior collection's frame callback or raw backlog survives. `receivedThread`
also expires its replay immediately. The `StateFlow` retains its last published
value, while a new collection prepares fresh content.

The initial value still uses the conversation id as the display-name fallback.
`StateFlow<ThreadUiState>` and lifecycle-bound destination collection retain their
existing surface. Standalone ViewModels default to unpaced scheduling for semantic
tests; factory/DI tests must inject controlled scheduling and explicitly drive
frames rather than invoking the real Android Looper on the JVM.

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
the folded row/evidence reading, `repository.observeQueue(conversationId)`,
`historyDemand`, host availability and seeded history coverage into one
`ThreadContent(items, queued, historyTail, historyMarkers, evidence)` carrier — the pre-combiner that keeps the outer
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
own zero-timeout `WhileSubscribed()` upstream, never longer, so it stops with `state`'s last subscriber rather than
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

### Memory search in the current run configuration

`ThreadRunConfig.memorySearch` carries the decoded `SessionSettings.memorySearch` for this conversation's
current session. The existing `sessionSettings` flow supplies it through `runConfig`; a `null` settings
reading gives `MemorySearchReport.Unknown`. Opening another conversation creates its own ViewModel and
settings subscription. On reconnect or host replacement, the repository emits `null` before the new
read, so the previous report cannot appear as the new host's reading. A session transition refreshes the
same subscription; `forLiveSession` immediately masks a reading whose nonempty current session ID differs
from the settings session ID, then accepts the replacement reading when it arrives. This mask matters
because the conversation row can name the new session before its settings reply arrives.

`Unknown` means unconfirmed, even with no providers; only an explicit aggregate `Absent` confirms no
installation. The report describes search access, not knowledge capture. No UI renders this field yet;
the dependent UI must treat daemon display names as inert text. See [Conversation repository](conversation-repository.md)
for the portable types and [Application payloads](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590)
for decode behavior.

### Leaving for the list when the row turns archived from any source (#1399)

The thread leaves for the list once, whichever path marks its own row archived. The `conversations` flow
(the shared `observeConversations(ConversationFilter.All)` subscription backing both [`state`](#stateinviewmodelscope-whilesubscribed5_000-initialvalue--threaduistateidid) and `conversationAgent`) carries an `onEach`, placed
**upstream of its `shareIn`** so it runs once per list emission no matter how many downstream collectors
subscribe: when the emitted list holds this `conversationId` with `archived == true`, the VM calls a
private `leaveForList()` and sends `ThreadNavigation.PopBack`. A row that disappears, or is only renamed
or moved, does not pop — matching desktop's `conversationArchivedBridge.ts` (desktop #653).

`leaveForList()` is the single gate: an `AtomicBoolean` latch means only the first caller's `PopBack`
actually sends. This matters because `RemoteConversationRepository.onInbound` folds an archive reply into
the list *before* the originating `archive(...)` call returns, so the thread's own Archive action
(`ThreadEvent.Archive`, see [ChannelInfoSheet Archive/Delete + pop-back nav](thread-screen-how-it-works-sheets.md#channelinfosheet-archivedelete--pop-back-nav-post-227))
races the list-driven `onEach` above — both paths want to pop, and the latch ensures exactly one `PopBack`
reaches `MainActivity` regardless of which one wins the race. Delete is unaffected: a deleted row
disappears from the list rather than showing archived, so `DeleteConfirm` keeps its own unconditional
`PopBack` send outside `leaveForList()`. The list-driven exit logs one content-free `RelayLog.d` line,
`event=thread_left_archived`.

### Session errors and local-send settlement (#1678)

`ThreadViewModel.sessionError` observes only `observeSessionError(conversationId)`
and uses `stateIn(viewModelScope, SharingStarted.Eagerly, null)`. Its `onEach` closes
`localSendStage` with `closeLocalSendWindow("session_error")` before publishing any
non-null code. Lifecycle-bound screen collection cannot own this side effect:
a stopped screen still needs its Sending/Waiting window settled without subscribers.

Closure increments `localSendGeneration`. A successful acknowledgement for the
closed generation cannot restore Waiting or advance a newer send's window; only
that newer send's own acknowledgement may advance it. A later send opens a fresh
window. Existing own turn-state, failed-send and availability/reconnect closure
rules remain, and another conversation's error affects neither this signal nor
this window. Logs classify errors as static blocked/child_crashing/unknown values,
never interpolate raw codes or daemon prose.

`PyryNavHost` collects `sessionError` with `collectAsStateWithLifecycle` and passes
it to the stateless `ThreadScreen`'s defaulted nullable parameter. The screen forwards
it and `state.agent` to [ThreadTopOverlay](thread-top-overlay.md#the-session-error-pill-1678).
The repository owns clearing; rendering adds no dismissal, resend or retry rule.
