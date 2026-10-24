# Thread screen — how it works, ViewModel state

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### `SavedStateHandle.get<String>("conversationId").orEmpty()` (lifted to a `private val`)

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes. Post-#139 the `conversationId` is lifted to a `private val` field on the VM so the `map { … }` body and the `stateIn(initialValue = …)` both reference one source of truth; before #139 the inline `savedStateHandle.get<String>("conversationId").orEmpty()` lived directly in the `MutableStateFlow(...)` constructor. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the title `Text` is non-null at every callsite.

### Authoritative content and separate controls

The ViewModel observes the owning negotiated thread and derives presentation from
complete supplied items, current progress and pending local sends. Raw snapshot
subscription, `ThreadFold` intake, legacy gap/coverage work and checkpoint fallback
were removed in #2367. Off-worker presentation and frame pacing retain complete
values; skipped/pending versions grant no sight. Composer, prompts, actions and
configuration remain independent of content delivery.

Read qualification requires exact current owner/generation/revision and supplied
shown metadata. Receipt, restored text and terminal facts alone cannot mark content
seen. The qualified retry path remains host-owned. Markdown reveal, grouping,
follow-newest, scrolling and Agent pinning remain supported.

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

### Queue presentation and controls

Queue/task placement and lifecycle come from authoritative items. Local pending
sends remain until accepted by exact device/message identity. Runtime queue matching,
roster inference and raw echo settlement are removed. Current recorded-target
controls require the active presentation owner; stale literal controls are inert.

### The model-menu agent filter (#1110)

Run configuration, opened from the footer Tune icon, is the sole model picker.
Since #1118 it shows both Claude and Codex rows in daemon order, without agent
headings. `runConfig` excludes unknown-agent rows and hidden `default` metadata,
then renders at most 32 known-agent rows. The not-listed count includes overflow
from that whole merged list plus `droppedModels`, even for a Codex conversation.
Each row carries its own agent and uses the daemon's bounded inert display name;
its raw value is retained verbatim for writes. A published pick must resolve to
one known visible row; ambiguous values cannot start either operation.

Visibility does not determine settings scope. `ThreadRunConfig` derives confirmed
selection, inherited resolution, effort and permission metadata from the
conversation's own-agent subset. Ambiguity checks include overflow rows, but a
radio mark requires a rendered row and matches both agent and value. Claude's
hidden default metadata remains usable for inherited selection; a Codex
conversation with no saved model cannot inherit Claude's default or effort
vocabulary. See [remembered effort recall](thread-composer-footer-effort-recall.md).

An own-agent pick retains the settings-write path. An other-agent pick captures
its row and opens the [shared MobileModal](mobile-modal.md) confirmation, naming
the source agent, target agent and model. Client-owned copy explains hand-over,
the slower and more expensive uncached first reply, and full-bypass turning off.
Cancel, close and Back dismiss without sending. Confirmation claims sending on
Main before launching exactly one `switchAgent` operation; selected effort is
included only when the target row publishes that nonempty level. While sending,
the pending label/radio belongs to the captured target and settings writes are
blocked. The initiating phone's switching status, including outgoing hand-over
wording during wrap-up, takes precedence over ordinary reset wording. Reset
progress, settings clear and stale readings cannot settle it.

**Command settlement, display identity and settings authority are separate.**
The repository's authenticated result clears the phone-local request; success
refreshes settings once immediately, without awaiting a conversation projection.
A conflated StateFlow can skip both the confirmed target session and a disconnect
gap, so waiting for either can leave an already successful switch blocking every
control. Failure clears pending and shows static client-owned copy with no daemon
text, retry or promise that hand-over/backlog effects were undone. Recollection
never resends; the ViewModel scope owns cancellation and no command is persisted.

Display restoration retains only a bounded label, connection/agent/session binding
and optional represented row identity. Failure can restore an unlisted or
inherited confirmed label even after settings disappear; no missing row gets a
radio mark. A successful target display candidate binds only to a newer known
target successor on the initiating connection. A superseding switch-back, removed
conversation or replacement with no successor binding installs no obsolete target
label. Observation versions travel with their actual values: older cached inputs
cannot retire a newly settled candidate. Fresh superseding readings or incompatible
newer identity evidence permanently retire display snapshots, including replacement
during sending; returning to an old identity cannot revive them. These snapshots
supply no effort, permission, capabilities or writability.

After an agent change, settings require fresh current-session authority, including
changes initiated by another client. `RunConfigurationReadings` optionally tags
conversation and settings inputs with their producing connection;
`StableConversationRepository` tags inside the captured delegate subscription.
Tagging after switching delegates would let a detached reading borrow replacement
authority, especially when agent/session values are unchanged and no gap emits.
Held, detached and retired initiating-session readings cannot enable settings.
A known transition binding requires an exact session match. When the current
replacement summary omits its session ID, a fresh non-held conversation-correlated
reply on that connection can establish authority; an absent conversation cannot.
The outer thread projection uses that admitted session for applied-effort and
permission masking instead of a detached connection's last known session.
Metadata-only upserts and snapshots preserve the authenticated transition's session
binding on existing rows. Ordinary pending model writes also bind to their
initiating agent/session and are invalidated by replacement independently of an
agent-switch request. Ordinary reset projection retains its existing behavior.

The conversation list is shared between run configuration and the outer thread
state. Keep agent lookups on that shared subscription: a second independent
`observeConversations(All)` sends another `list_conversations` request per opening.
The question-modal lookup's historical duplicate is tracked in
[Question batch modal](question-batch-modal.md#title-names-the-conversations-agent-1116).

Deterministic regression coverage spans merged projection, both switch directions,
queued settlement, permanent display retirement and replacement authority in
`ThreadViewModelAgentModelMenuTest`, `ThreadViewModelAgentSwitchTest`,
`ThreadViewModelAgentSwitchReplacementTest` and the Tune-driven shared
`ThreadAgentSwitchUiTest`. Real daemon/relay proof remains with
[#2052](https://github.com/pyrycode/pyrycode-mobile/issues/2052):
`InteractiveStreamE2ETest.interactiveTurn_switchAgentFromModelPick_confirmsAndReplies`
will exercise Claude → Codex → Claude through Run configuration, preserved
channel/history, replies and target effort options. That follow-up owns multi-agent
harness prerequisites and counted live evidence; deterministic proof here supplies
no live-switch coverage. See the [#1118 plan](../../specs/architecture/1118-agent-switch-from-model.md).

### Memory search in the current run configuration

`ThreadRunConfig.memorySearch` carries the decoded `SessionSettings.memorySearch` for this conversation's
current session. The existing `sessionSettings` flow supplies it through `runConfig`; a `null` settings
reading gives `MemorySearchReport.Unknown`. Opening another conversation creates its own ViewModel and
settings subscription. Reconnect or host replacement invalidates the previous report;
connection-tagged admission cannot depend on observing a `null` gap, which may be
conflated away. The fresh authority rules above reject detached or stale readings.
A session transition refreshes the same subscription; `forLiveSession` immediately
masks a reading whose nonempty current session ID differs from the settings session
ID, then accepts the admitted replacement reading when it arrives. This mask matters
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
