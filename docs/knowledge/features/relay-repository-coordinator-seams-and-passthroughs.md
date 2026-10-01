# Relay repository coordinator — a Noise pump + remote repository per live connection — seams and passthroughs

Split out of [Relay repository coordinator — a Noise pump + remote repository per live connection](relay-repository-coordinator.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Relay repository coordinator — a Noise pump + remote repository per live connection](relay-repository-coordinator.md); see that document for the rest.

## Connect-time FCM push-token re-registration (#365)

Per the daemon's contract (`docs/protocol-mobile.md` § Phone background behaviour) the phone re-registers
its FCM push token on **every** WS connect, so the daemon's wake target self-heals across app restarts and
connection drops; the server de-duplicates the `(platform, token, device_name)` triple, so a repeat is a
cheap (~100 B) no-op. [#365](../codebase/365.md) adds that connect-time orchestration here — the only
Phase 4 FCM slice that touches the connection lifecycle — reusing [#359](../codebase/359.md)'s
`RemoteConversationRepository.registerPushToken` sender unchanged. [#361](../codebase/361.md) then turned
the one-shot send into a live collection so a token rotation reaches every already-open host without a
reconnect — see [push messaging service](push-messaging-service.md) for the writer, `PushTokenSink`.

After publishing the repo, `onConnection` launches `reregisterPushTokenOnOpen(pump, repo)` on the
per-connection `childScope`. The hook:

1. **Awaits the first transition out of `Handshaking`** — `pump.state.first { it is Open || it is Closed }`.
   `StateFlow.first {}` checks the current value first, so an already-`Open` pump fires with no missed-edge
   race.
2. **Aborts on a pre-Open `Closed`** (handshake fault / transport down) — `return`, nothing to register.
3. **Collects the token stream** — `pushTokens.filterNotNull().distinctUntilChanged().collect { token -> … }`.
   A `null` (nothing stored yet) is filtered out — the capability stays **dormant** until a token exists —
   and `distinctUntilChanged()` means only an actual rotation re-sends, not every replay of an unchanged
   `StateFlow`-backed value.
4. **Sends each distinct token** — `repo.registerPushToken(token)` per emission, swallowing failure per
   token so one failed send does not end the collection (the next rotation, or the next connection, tries
   again).

The hook **starts** exactly once per connection, guaranteed *structurally*: each connection builds a fresh
pump + child scope + hook, and the `PumpState` machine never revisits `Handshaking` (re-key stays `Open`).
Unlike the pre-#361 shape it does not **return** after its first send — it keeps collecting for as long as
the connection's child scope lives, so a token stored while this host is already `Open` reaches the daemon
without waiting for the next reconnect. A host that is offline when the token rotates gets it the ordinary
way: its next connection's hook starts fresh and sends the then-current value as its first emission. No
client-side dedup beyond `distinctUntilChanged()` — the server dedupes the triple.

Three load-bearing constraints shape it:

- **It uses the *concrete* `repo` handle, not `currentRepository`.** `registerPushToken` is **not** on the
  `ConversationRepository` interface (#359 — it is a device/connection concern), and `currentRepository` is
  interface-typed, so the hook calls it through the concrete `RemoteConversationRepository` captured at
  construction. This was the **first live caller** of the method #359 shipped dormant. It does **not** add a
  second `currentRepository` observer or a second connection-state subscription — it reuses the one
  `onConnection` collector + the pump's existing `state`.
- **`onConnection` stays non-suspending.** `launch` schedules and returns; all suspending work — including
  the now-indefinite collection — runs on the child scope, off the critical path, preserving the
  cancellation-atomicity / key-wipe invariant (below). The hook is **never** awaited inline.
- **Swallow, but propagate cancellation.** A narrow `catch` inside the collector re-throws
  `CancellationException` (a drop cancels `childScope` mid-collection — absorbing it would break
  structured-concurrency teardown, and would also silently end the collection for every later token) and
  swallows any other `Exception` **without logging** (the token is never logged; the daemon re-registers on
  the next connect by contract, or the next rotation retries). A server `error` (`RelayErrorException`) or
  not-Open `IllegalStateException` is swallowed per token; the collection continues for the next one.

### Closing #359's `device_name: ""` defer

\#359 left `RemoteConversationRepository`'s `deviceName` ctor param defaulted to `""` and flagged that
whichever slice adds the live caller must thread the real name. #365 is that slice: it adds the
`deviceName: String = ""` coordinator param, threads it into the repo (`RemoteConversationRepository(pump,
childScope, deviceName)`), and `AppModule` supplies the live `NoiseClientInfo.deviceName` (`Build.MODEL`).
This matters because pyrycode's handler (#319) acks-with-**no-registry-touch** only when
`(Platform, Token, DeviceName)` matches the stored device — an empty `device_name` would *fork* the
server's dedup triple into a duplicate registry entry. See [[post-352-connection-scoped-repo-behind-facade]].

## Two-part connection status (#392)

The coordinator publishes [`connectionStatus`](connection-status.md), the combined
`ConnectionStatus { relay, pyrycode }` model the Settings status line consumes — surfaced onto
[`SettingsViewModel`](settings-viewmodel.md) and rendered as the
[`ConnectionStatusLine`](connection-status-line.md) under the Server row in **[#398](../codebase/398.md)**
(the live wiring of parent epic #390, `blockedBy #397`). It owns the connection-scoped pump, so it is where the **pyrycode-leg readiness** (the
honest `relay → daemon` end-to-end signal) is derived — the relay leg's `Connected` only means
*socket-open*, not Noise-session-open. The leg reaches `PyrycodeLinkStatus.Connected` **only** once the
pump reaches `Open` (handshake complete) — never on bare socket-up, never between connections —
closing the false green that bit live testing on 2026-06-08. See
[connection status](connection-status.md) for the model and the leg semantics.

It is **pure derivation** — no new mutable status state:

- The live pump is reached through the single [`activeConnection`](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493)
  source (`conn?.pump`), written on the non-suspending `onConnection`/`teardownActive` critical section (the
  fresh `Connection` on connect, `null` on teardown). The pump reference stays **inside** `activeConnection` —
  only the *derived* readiness is exposed, never the pump itself (the pump is single-owner).
- A private `pyrycodeStatus: Flow<PyrycodeLinkStatus>` = `activeConnection.flatMapLatest { conn ->
  conn?.pump?.state ?: flowOf(null) }.map { it.toPyrycodeLinkStatus() }` — the same
  `flatMapLatest`-over-a-live-child idiom [`StableConversationRepository`](stable-conversation-repository.md)
  uses for `currentRepository`. It tracks the **current** pump across reconnects with no carryover
  (`flatMapLatest` cancels the prior pump's `state` collection); "no connection" maps through `null` to the
  `Down` floor.
- The public `connectionStatus: StateFlow<ConnectionStatus>` = `combine(relayStatus, pyrycodeStatus) {
  relay, pyrycode -> ConnectionStatus(relay, pyrycode) }.stateIn(scope, SharingStarted.Eagerly, …)` on
  the coordinator's **existing** `scope` (cancelled by `close()`, so it doesn't hang `runTest`).

The mapping `internal fun PumpState?.toPyrycodeLinkStatus()` (bottom of the file, sibling to #391's
`RelayLinkStatus.toConnectionState()`) is total over `PumpState` + `null`: `null`/`Closed → Down`,
`Handshaking → Handshaking`, `Open → Connected`. It **discards `Open.connId` and `Closed.cause`** —
the no-log / no-leak contract is structurally enforced (no relay/crypto-derived string reaches the
status surface). `relayStatus` and `connections` come directly from the owning bundle's supervisor;
neither needs a separate Koin binding. `AppModule` passes the registry
compatibility projection, `get<RelayConnectionRegistry>().connectionStatus`, into
`SettingsViewModel`, keeping both legs on the selected host.

> **Init-order gotcha.** `stateIn(scope, Eagerly, …)` runs at *property initialization*, so
> `connectionStatus`/`pyrycodeStatus` (and `currentRepository`/`currentModal`) must be declared **after**
> `scope` and `activeConnection` in the class body — referencing an earlier-declared field is a
> construction-time NPE (not a compile error). `activeConnection` sits just below `scope` (where the old
> holders were), so every deriver below it satisfies this.

## Live-session event seam (#406)

The decoded [`LiveSessionEvent`](live-session-events.md) stream ([#385](../codebase/385.md)) lives on
the **concrete** `RemoteConversationRepository.liveSessionEvents` — connection-scoped and **not** on the
`ConversationRepository` interface — so a UI ViewModel cannot reach it. The coordinator owns the
connection-scoped repository, so it threads that non-interface surface up exactly as `pyrycodeStatus`
reaches the concrete pump through `activeConnection` (and as `registerPushToken` reaches the concrete repo
through the construction-time handle):

```kotlin
val liveSessionEvents: Flow<LiveSessionEvent> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.liveSessionEvents ?: emptyFlow() }
```

- The concrete `repo` is reached through the single `activeConnection` source (`conn?.repo`) — its
  `Connection.repo` field is set as part of `activeConnection.value = Connection(pump, scope, repo, transport)` in
  `onConnection` and cleared to `null` in `teardownActive`, both **non-suspending** writes inside the same
  critical section, so the cancellation-atomicity invariant is preserved. The repo reference stays **inside**
  `activeConnection`: only the *derived* event flow is exposed, never the concrete repo reference. (Before
  [#493](../codebase/493.md) this was a separate private `activeRemoteRepo` mirror; the consolidation folded
  it into the one source.)
- **Cold, not `stateIn`'d.** Unlike `connectionStatus` (current-value state), these are *events* with no
  "current value", so `liveSessionEvents` is a cold `Flow` with no scope of its own. Each consumer's
  collection independently observes `activeConnection` (a `StateFlow`) and subscribes to the current
  repo's `SharedFlow` (both multi-subscriber-safe) — no `shareIn`. (A code-review NIT flagged that
  per-subscriber `flatMapLatest` re-derivation is fine at today's consumer count; revisit only if the
  count grows.)
- **Reconnection-surviving.** `flatMapLatest` cancels the prior connection's collection and switches to
  the fresh repo's `liveSessionEvents` on each new connection; `emptyFlow()` between connections. A push
  on a now-dead pump surfaces nowhere — a coordinator test pins this.
- **Generic, not turn-state-specific.** The seam carries the **full** `LiveSessionEvent` stream, not an
  `isThinking`/turn-state projection — reducing to "latest phase" is a consumer concern. The first
  consumer is [`ThreadViewModel.isThinking`](turn-state-thinking-flag.md) (#406, the thinking-indicator
  data half); #387 (tool timeline) and #337 (live assistant text) reuse the same flow without
  re-plumbing this layer. `AppModule` supplies the registry projection of the
  selected coordinator's flow, exactly like `connectionStatus`.

## Modal event seam (#445) and the hoisted currentModal fold (#492)

The decoded [`ModalEvent`](modal-events.md) stream ([#437](../codebase/437.md)) lives on the **concrete**
`RemoteConversationRepository.modalEvents` (`replay = 0`, connection-scoped, **not** on the interface) —
the same posture as `liveSessionEvents`, so a UI ViewModel cannot reach it directly. The coordinator
threads it up as a **byte-for-byte mirror** of the live-session seam — switching off the same single
`activeConnection` source (`conn?.repo`) — and, as of [#492](../codebase/492.md), **folds it here** into
a host-level projection. **[#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) widened
that projection from one modal to [`HostModalState`](current-modal-state.md), holding every outstanding
prompt on the host, keyed on `modalId`** — see [Current-modal state](current-modal-state.md) for the full
design; this section only covers the seam shape:

```kotlin
// #492: PRIVATE — its sole consumer is hostModals below. #1337 widens the element type to ModalEvent? and
// prefixes each connection's inner flow with a null "this connection just started" marker.
@OptIn(ExperimentalCoroutinesApi::class)
private val modalEvents: Flow<ModalEvent?> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.modalEvents?.onStart<ModalEvent?> { emit(null) } ?: emptyFlow() }

// #1337: every outstanding prompt + this connection's resolved ids, folded once per coordinator. A null
// input (the reconnect marker) resets to an empty HostModalState.
val hostModals: StateFlow<HostModalState> =
    modalEvents
        .scan(HostModalState()) { state, event -> if (event == null) HostModalState() else state.reduce(event) }
        .stateIn(scope, SharingStarted.Eagerly, HostModalState())

// #1337: the single-value view kept for the conversation-list attention readers until #1338.
val currentModal: StateFlow<ModalUiState> =
    hostModals.map { it.latestOutstanding }.stateIn(scope, SharingStarted.Eagerly, ModalUiState.Hidden)
```

- **`modalEvents` is cold and now `private`** — events, no current value. The fold that holds "which
  prompts are currently open" moved here in #492 from [`ThreadViewModel`](current-modal-state.md): folding
  it at a screen-scoped VM dropped any `modal_shown` fired before a thread screen subscribed (the source is
  `replay = 0`), so an outstanding prompt stayed stuck daemon-side while the phone rendered nothing. After
  the hoist nothing outside the coordinator reads the raw event stream, so it was demoted to `private`; its
  element type widened to `ModalEvent?` in #1337 for the reconnect marker is invisible past `hostModals`.
- **`hostModals` mirrors `currentRepository` / `connectionStatus`** — accumulate a `replay = 0`-derived
  stream `Eagerly` on the coordinator `scope` so `.value` is always the true current projection. Started
  `Eagerly` (not `WhileSubscribed`) is load-bearing: `scan` re-emits its seed on every fresh collection, so
  a resubscribe past a stop window would overwrite retained prompts with an empty `HostModalState`, and the
  `replay = 0` source won't replay to rebuild it (full rationale in [Current-modal
  state](current-modal-state.md#why-eagerly-not-whilesubscribed)). `currentModal` is a derived `Eagerly`
  `stateIn` on top, for the three single-value readers that only test the `Open` case (§ below). The pure
  `HostModalState.reduce` lives in `data/model` (moved there in #492, before the #1337 widening, so this
  `data`-layer coordinator can see it) and emits **no log** (modal fields may name a sensitive
  command/path).
- **Reconnection-surviving; retains across a plain teardown; clears on a *new* connection (revised by
  #1337).** `flatMapLatest` switches to the fresh repo's `modalEvents` (prefixed with the `onStart { emit(null) }`
  marker) on each new connection and cancels the prior; `emptyFlow()` between connections. The `.scan` sits
  **downstream** of `flatMapLatest`, so a connection drop alone does **not** restart it — every held prompt
  is **retained**, not reset (the #492 teardown decision, unchanged: the answer path is guarded by the
  deterministic `answerModal`/`cancelModal` null-guard, never by this UI projection, so retaining a stale
  prompt can't send an answer on a dead connection). What #1337 adds: when `activeConnection` switches to a
  *fresh* `Connection`, the reconnect marker is the first emission of that connection's inner flow, so it
  folds to an empty `HostModalState` strictly before that connection's first `modal_shown` is collected.
  This is safe because the daemon guarantees a connect-time re-send of every still-outstanding prompt
  (`protocol-mobile.md` § Reconcile on (re)connect) — see [Current-modal state §
  Lifecycle](current-modal-state.md#lifecycle-errors-edge-cases) for the full ordering proof.
- `AppModule` passes the registry's selected-host `hostModals` projection into `ThreadViewModel` and
  `PermissionDraftStore.bind` (the single-value `currentModal` stays for the conversation-list attention
  readers — `HostAttentionState.resolve`, `HostConversationSource.promptKeys`,
  `RelayConnectionRegistry.currentModal` — until [#1338](current-modal-state.md#related)). Every retained
  coordinator keeps folding its own host's prompts even while another host is selected; overlapping modal
  ids on different hosts never share an accumulator.

## Question-batch projection (#822)

The held [`QuestionBatch`](remote-conversation-repository-live-stream-and-modals.md#questionbatches--the-v2-clarification-batch-decodefold-seam-822)
list lives on the **concrete** `RemoteConversationRepository.questionBatches` — a `StateFlow`, not an
event stream, connection-scoped and **not** on the interface — the same reachability posture as
`modalEvents`. The coordinator switches to it off the same single `activeConnection` source, byte-for-byte
the `liveSessionEvents`/`modalEvents` `flatMapLatest` shape, but **stateIn's the switched flow directly**
instead of folding a `scan` on top — there is nothing to accumulate, because the fold already happened at
the repository seam:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val questionBatches: StateFlow<List<QuestionBatch>> =
    activeConnection
        .flatMapLatest { conn -> conn?.repo?.questionBatches ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

fun observeQuestionBatch(conversationId: String): Flow<QuestionBatch?> =
    questionBatches.map { it.batchFor(conversationId) }.distinctUntilChanged()
```

- **Started `Eagerly` for the same reason as `currentModal`** (#492): a `question_shown` that arrives
  before any thread screen subscribes must not be lost. Unlike `currentModal`'s `scan`, there is no
  seed-re-emission hazard here to make `Eagerly` load-bearing in that specific way — `stateIn` on a
  switched `StateFlow` just republishes the source's current value on each subscription — but `Eagerly`
  is still required so the projection itself exists (and starts collecting the active connection's
  batches) before any consumer subscribes.
- **Resets on every reconnect, including a plain teardown — unlike `hostModals`' retain-through-teardown
  (#492, revised by #1337).** `flatMapLatest` switches to the new connection's `questionBatches`, and that
  `StateFlow` starts at `emptyList()` **immediately on a disconnect** because `conn` itself goes `null` and
  the switched flow becomes `flowOf(emptyList())` right then — it does not wait for a replacement
  connection the way `hostModals`' reconnect marker does. So a batch held from the old connection is gone
  the instant the connection drops, not merely once a new one arrives — no manual clear needed, it falls
  out of "fresh repository per connection" structurally. This is correct here because the protocol's §
  Reconnect / Backfill semantics **resets** question state on reconnect by contract and rebuilds it from
  the daemon's connect-time reconcile, so a batch resolved while the phone was disconnected is simply
  absent from that reconcile and must not come back — a batch outstanding when the phone went offline is
  equally absent until a fresh connection's reconcile re-raises it, so there is no window where UI state
  disagrees with "offline means hidden." `hostModals` (since #1337 the daemon's `modal_shown` reconcile has
  the **same** guarantee) still differs in *when* it clears: it keeps a prompt visible through the
  disconnected gap itself and only clears at the next connection's first frame, because the deterministic
  `answerModal`/`cancelModal` null-guard (not this projection) is what keeps a tap from reaching a dead
  connection, so there is no safety reason to hide a still-possibly-true prompt the moment the link drops —
  see [Current-modal state § Lifecycle](current-modal-state.md#lifecycle-errors-edge-cases). **Do not copy
  `questionBatches`' immediate-on-drop reset onto `hostModals`** if this seam is ever refactored to look
  more alike; the two clear at different points in the reconnect sequence by design, not by oversight.
- **`observeQuestionBatch` is the per-conversation read, and the only one #661's panel should use.**
  `questionBatches` is the whole host's set across every conversation; reading it directly and rendering
  the first match, or filtering client-side without going through `batchFor`, risks showing one
  conversation's clarification question inside another conversation's thread. `batchFor` (`data/model/QuestionBatch.kt`)
  picks the first held batch for the given id — claude blocks on one `AskUserQuestion` at a time, so two
  outstanding batches for the same conversation is out of contract, and any deterministic tie-break is
  fine for that case.
- **The outbound half is a separate passthrough**, added by [#825](https://github.com/pyrycode/pyrycode-mobile/issues/825)
  below (§ [Outbound question-answer / refuse passthrough](#outbound-question-answer--refuse-passthrough-825)) —
  this seam itself stays read-only, the mirror of `modalEvents` before `answerModal`/`cancelModal` existed.

## Background-task roster (#677)

The held **`StateFlow<Map<String, BackgroundTaskRoster>>`** (`RemoteConversationRepository.backgroundTasks`)
that tracks work claude left running past its turn — daemon state, not turn content, so it never reaches
the thread timeline. Same shape as [`questionBatches`](#question-batch-projection-822): a per-connection
concrete `StateFlow`, switched (not folded) at the coordinator, `Eagerly`, so a frame that arrives before
any panel subscribes is not lost:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>> =
    activeConnection
        .flatMapLatest { conn -> conn?.repo?.backgroundTasks ?: flowOf(emptyMap()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

fun observeBackgroundTasks(conversationId: String): Flow<BackgroundTaskRoster?> =
    backgroundTasks.map { it[conversationId] }.distinctUntilChanged()

fun observeLiveBackgroundTaskCount(conversationId: String): Flow<Int> =
    observeBackgroundTasks(conversationId).map { it?.liveCount ?: 0 }.distinctUntilChanged()
```

The decode+fold itself lives on the repository at
[`BackgroundTaskProjection`](remote-conversation-repository-live-stream-and-modals.md#backgroundtasks--the-v2-background-task-decodefold-seam-677) —
this section covers only the part that is specific to the coordinator: the one piece of state that has to
survive a reconnect.

- **Resets on reconnect, like `questionBatches` — with one deliberate carry-over.** `flatMapLatest`
  switches to the new connection's fresh `backgroundTasks`, which starts empty because each connection
  builds a new `RemoteConversationRepository`. Unlike `questionBatches`, though, the daemon has no
  reconcile-on-connect contract for background tasks beyond re-sending its **last retained roster** — the
  same roster it already sent, including any task that has since finished. Mobile drops and rebuilds its
  connection on every return from the background, so without a carry-over a completed task would read as
  live again on every foreground.
- **The carry-over is narrow: only which task ids finished, not their `status`/`summary`.**
  `RelayRepositoryCoordinator` owns one `FinishedBackgroundTasks` instance for the life of the host — the
  [`replayCursor`](#reconnect-spanning-replay-cursor-412) precedent for state a fresh per-connection
  repository cannot hold — and threads it into each connection's repository in `onConnection`, the same
  parameter shape as `replayCursor`. `teardownActive` never touches it. Everything else about a task
  (its description, its last mid-life update, its terminal `status`/`summary`) resets with the connection
  and is rebuilt only from that connection's own frames; a re-listed finished task therefore comes back
  with `isFinished == true` but `finish == null`.
- **The finished set stays bounded by the conversation's own rosters, not by an unrelated cap.** Each
  roster calls `FinishedBackgroundTasks.retainOnly(conversationId, rowIds)`, forgetting any id the roster
  no longer lists. A task the daemon has fully forgotten is forgotten here too, on the next roster for
  that conversation.
- **`background_task_progress` (#1042) carries nothing across a reconnect — not even the narrow
  finished-id carry-over.** A task's `BackgroundTask.progress` lives only in the per-connection
  `BackgroundTaskProjection`, the same as its `latestUpdate`; a fresh connection starts every task without
  progress and rebuilds it from whatever `background_task_progress` frames that connection receives. The
  one thing the host-lifetime `FinishedBackgroundTasks` buys a re-listed finished task is staying finished
  — which, through the same `isFinished` check the projection already applies, is also why that task shows
  no progress on the reconnect that relists it.
- **Host isolation is structural**, the same as `questionBatches`: one coordinator per host, and the map
  and the finished set are both keyed by `conversationId` inside it, so a task id repeated across two
  hosts' conversations cannot cross between them.
- **Three deliberate differences from the desktop client's `backgroundTaskRosterStore`,** which this
  seam's `BackgroundTaskProjection` otherwise follows for its merge rules:
  1. Desktop's `setUpdatedTask` drops `status`/`summary` on a terminal update
     ([pyrycode-desktop#1558](https://github.com/pyrycode/pyrycode-desktop/issues/1558)), which is why its
     live count can stick at one after a task completes. This seam keeps both on the task's `finish` slot.
  2. Desktop matches `status` against the closed set `completed`/`failed`/`stopped`. This seam finishes a
     task on any non-empty `status` — the wire says "test `status != ""`, not a closed set" — so an
     unrecognised terminal value still counts.
  3. Desktop's later `unlistedStarts` (#1563) holds a started task outside the visible set until a roster
     lists it, because claude also sends starts for long foreground `Bash` calls a roster never carries.
     This ticket's one-set rule lists a start immediately instead. If a panel finds foreground starts
     inflating `liveCount`, desktop's `unlistedStarts` is the precedent to adopt.
- **No log.** `description`, `patch`, `status`, `summary` and progress's own `description`/
  `subagentType`/`lastToolName` are claude-authored, unsanitised strings — held as inert fields, never
  parsed (`patch` included), never used as a key besides `taskId`/`conversationId`, and never logged. A
  malformed frame is dropped inside `BackgroundTaskProjection.apply` without reading the caught
  exception's message, the `QueueProjection`/question-arm posture.
- **A consumer must read the per-conversation surface** (`observeBackgroundTasks` /
  `observeLiveBackgroundTaskCount`), not `backgroundTasks` directly — the whole-host map risks showing
  one conversation's tasks inside another's panel, the same rule as `observeQuestionBatch`. The
  Actions-menu panel and count that read this seam are [#678](https://github.com/pyrycode/pyrycode-mobile/issues/678);
  this seam is data only.

## Outbound modal-send passthrough (#451)

The **outbound mirror** of the inbound `modalEvents` seam: where `modalEvents` surfaces decoded modals *up*
to the ViewModel, the answer/cancel passthrough sends the user's decision *down* to the connection-scoped
concrete [`RemoteConversationRepository.answerModal` / `cancelModal`](remote-conversation-repository.md)
([#438](../codebase/438.md)). The asymmetry is correct: inbound is a stream (`Flow`); an answer/cancel is a
request/reply control **call**, so these are **suspend methods, not flows**. Both read the concrete repo off
the single `activeConnection` source (`activeConnection.value?.repo`):

```kotlin
suspend fun answerModal(modalId: String, optionId: String) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.answerModal(modalId, optionId)
}
// cancelModal(modalId) is identical, minus the optionId.
```

- **Null-guard only — both not-connected paths funnel to `IllegalStateException`.** When
  `activeConnection.value == null` (between connections) the guard throws. When a connection exists but the
  pump is still pre-`Open` (Handshaking), `repo.answerModal` → `sendAndAwaitReply` → `pump.send` returns
  false → `IllegalStateException` (the #438 precedent). So the passthrough needs **only** the null-guard — a
  redundant `Open` gate (like `currentRepository`'s, which exists for a different reason: facade
  publication) would be needless complexity, since the concrete send already fails fast.
- A server `error` propagates from the concrete repo as `RelayErrorException` **unchanged** — the
  passthrough neither catches nor maps it (the consuming [`ThreadViewModel`](modal-answer-flow.md) catches
  both exceptions and surfaces a one-shot error signal).
- **No log** — the `modalId`/`optionId` may name a sensitive command/path (never-log contract); the
  passthrough adds no `android.*` (data/ stays portable).
- The consumer is [`ThreadViewModel.sendAnswer` / `sendCancel`](modal-answer-flow.md), bound at the
  `AppModule` `ThreadViewModel` factory as suspend **method references**
  (`answerModal = registry::answerModal`, likewise cancel), which resolve the
  selected coordinator at call entry, matching the inbound compatibility seams.

## Outbound interrupt passthrough (#458)

The third outbound control passthrough forwards the open thread's conversation id
unchanged (#626). It reads the concrete repository from `activeConnection` to
select the transport; the argument selects the conversation to stop. See
[Interrupt send path](interrupt-send-path.md).

```kotlin
suspend fun interrupt(conversationId: String) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.interrupt(conversationId)
}
```

- **Null-guard only**, identical to `cancelModal`: between connections the guard throws; a connection that
  exists but whose pump is pre-`Open` surfaces as the concrete
  [`RemoteConversationRepository.interrupt`](remote-conversation-repository.md)'s `check(pump.send(...))` →
  `IllegalStateException`. No redundant `Open` gate. Never logs.
- **Explicit target:** the repository encodes the supplied id as
  `interrupt.payload.conversation_id`. No shared active-conversation cursor is read,
  so prior activity in A cannot choose the target of a call naming B.
- **Fire-and-forget:** the passthrough awaits no acknowledgment and changes no
  local turn state. The open conversation's inbound turn events remain authoritative.
- Bound at the `AppModule` `ThreadViewModel` factory as a suspend **method reference** (`interrupt =
  registry::interrupt`) into the VM's defaulted `suspend (String) -> Unit` lambda.
  The registry selects the host at call entry; the supplied id selects that host's
  conversation. The consumer is [`ThreadViewModel.onInterrupt` / `sendInterrupt`](interrupt-send-path.md);
  unlike `sendCancel` its failure catches are **empty** (no error channel or log).
  `CancellationException` is rethrown before the failure catches.

## Outbound question-answer / refuse passthrough (#825)

The outbound mirror of the [question-batch projection](#question-batch-projection-822) above: forwards
the operator's decision down to the connection-scoped concrete
[`RemoteConversationRepository.answerQuestionBatch` / `refuseQuestionBatch`](remote-conversation-repository-control-sends.md#answerquestionbatch--refusequestionbatch--the-v2-question_answer--question_refused-sends-825).
Same shape as the `answerModal`/`interrupt` passthroughs: both read the concrete repo off the single
`activeConnection` source.

```kotlin
suspend fun answerQuestionBatch(questionBatchId: String, answers: List<QuestionAnswer>) {
    val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
    repo.answerQuestionBatch(questionBatchId, answers)
}
// refuseQuestionBatch(questionBatchId) is the same null-guard, minus answers.
```

- **Null-guard only** — between connections the guard throws; a connection whose pump is pre-`Open`
  surfaces through the concrete repository's own `check(pump.send(...))` as `IllegalStateException`. No
  redundant `Open` gate, matching `answerModal` / `interrupt`.
- **Validation happens on the other side of the guard**, in the concrete repository, against the same
  held state `questionBatches` projects — not against this coordinator's `stateIn` copy, which trails by
  a dispatch. A batch folded a moment ago is answerable through this passthrough with no lag window.
- **Host isolation is structural.** Each host has its own coordinator, `activeConnection` and repository,
  so a send for host A's batch can only reach A's pump even when host B holds a batch sharing the same
  id.
- Bound at the `AppModule` `ThreadViewModel` factory beside `answerModal`/`interrupt` for
  [#661](https://github.com/pyrycode/pyrycode-mobile/issues/661)'s panel. Never logs; never grants a
  permission.

## Reconnect-spanning replay cursor (#412)

Each bundle's coordinator owns one in-memory [`ReplayCursor`](replay-cursor.md):
the latest interactive structured-stream [`Envelope.eventId`](mobile-protocol-v2-wire-layer.md)
observed across that bundle's reconnects. Explicit-record bundles for A and B
have separate coordinators and cursors even if their relay URL is the same.
The cursor cannot live on the per-connection
[`RemoteConversationRepository`](remote-conversation-repository.md), which is
rebuilt each reconnect: the next `hello` needs the old position before its new
inbound path exists. It is not persisted across bundle replacement or process
restart.

```kotlin
internal val replayCursor: ReplayCursor = ReplayCursor()   // one per coordinator, survives reconnects
```

- Each per-connection repository receives its coordinator's cursor through
  `replayCursor = replayCursor` in non-suspending `onConnection`. Inbound recording
  or a `resync` reset in A changes only A's cursor; B retains its position.
- `teardownActive` never clears the cursor. Drops, retry and background supervisor
  close preserve it while replacing the transport, pump, Noise session and
  repository. Bundle disposal ends this owner's usable lifetime; a new bundle
  starts empty.
- The [session factory supplier](noise-ik-session.md#factory-wiring) closes over
  the owning coordinator's `internal val replayCursor`, with no Koin lookup.
  It reads `.latest` at `hello`-build, advertising that position as
  `hello.last_event_id`; `null` omits the field. Reading an app-wide coordinator
  would mix hosts, and capturing a value at construction would miss later events.

The registry selects among retained explicit-record bundles; it never moves a
cursor between them. Selection changes leave all host cursors untouched. The
older `createCompatibility(store)` helper can reread a different host on redial
while retaining one cursor, so it is not used by app DI for collection ownership.
