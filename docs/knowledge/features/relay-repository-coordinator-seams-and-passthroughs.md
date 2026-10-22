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
> `connectionStatus`/`pyrycodeStatus` (and `currentRepository`/`hostModals`) must be declared **after**
> `scope` and `activeConnection` in the class body — referencing an earlier-declared field is a
> construction-time NPE (not a compile error). `activeConnection` sits just below `scope` (where the old
> holders were), so every deriver below it satisfies this.

## Current turn state and content

The coordinator forwards admitted session-owned turn state and live readings.
The former raw `liveSessionEvents` content seam is removed in #2367. The exact host's
`NegotiatedThread` forwards authoritative content and queries through stable/cache
wrappers. The store survives reconnect; connection-owned decoders and transient
progress do not supply or certify content. See [live state](remote-conversation-repository-live-stream-and-modals.md).

## Modal event seam (#445) and the hoisted hostModals fold (#492)

The coordinator publishes one eagerly collected `hostModals: StateFlow<HostModalState>`
per host, containing every outstanding permission request and resolved identities.
Collection follows the single `activeConnection` and reselects its source when the
owning pump reaches Open, after capability negotiation (#2084).

- **Supplied route:** the repository folds admitted events immediately into
  `authoritativeHostModals`. The coordinator collects this held state rather than the
  replay-zero `modalEvents` stream, preserving local `rejectedConversations`. A prompt
  received before subscription is therefore still visible. Folding only the event
  stream loses that request even with an eager coordinator if admission wins the race.
- **Legacy route:** the coordinator calls `reconnected()` before collecting the fresh
  repository's `modalEvents`, then reduces each event into its host state. This retains
  #1337's reconnect reset and existing event semantics without a second inbound consumer.
- **Local actions:** `recordModalAction` forwards to the active repository's supplied
  fold and updates coordinator state synchronously. Otherwise a later held-state emission
  could overwrite a local dismissal or draft/action state. The model fold emits no logs.
- **Teardown/reconnect:** plain teardown retains held permission presentation, while
  sends fail without an active connection. A fresh repository supplies its own outstanding
  set; historical answered items never rebuild actionable state. Question batches remain
  connection-owned and switch to empty on teardown/reconnect. Family/session retirement
  removes only the owning conversation's requests, retaining resolved/retired identities;
  it never sends an answer.

Outbound supplied permission/question actions require an outstanding admitted identity
in the concrete repository. The ViewModel's captured request ids/generations and
synchronous scoped checks prevent a callback for a retired request from acting on its
replacement. History/catch-up remains inert even for actual `notice` / `prompt_answered`
records; reconnect needs newly supplied outstanding requests, with no action replay.
See [session retirement](remote-conversation-repository-status-projections.md#supplied-session-ownership-and-family-retirement-2084)
and [supplied screen inputs](thread-screen.md#supplied-session-readings-and-presentation-2084).

`AppModule` passes the registry's host-scoped projection into `ThreadViewModel` and
`PermissionDraftStore.bind`. Each retained coordinator holds its own host's prompts
while another host is selected; identical request ids on different hosts share no fold.
The conversation-list attention readers use the outstanding list, and no selection-
scoped `currentModal` supplies authority. See [Current-modal state](current-modal-state.md).

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

- **Started `Eagerly` for the same reason as `hostModals`** (#492): a `question_shown` that arrives
  before any thread screen subscribes must not be lost. Unlike `hostModals`' `scan`, there is no
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

## Supplied tasks and progress

Canonical facts supply task identity, ownership, parent, placement and lifecycle.
Raw started/updated/roster reconstruction and runtime work from #2266/#2267 were
removed in #2367. Session-owned task progress remains a transient reading. A control
send or missing roster entry cannot prove completion. Current-owner action targets
retain their validation and stop/refusal passthrough below.

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

## Outbound background-task stop passthrough (#1829)

The coordinator exposes `supportsBackgroundTaskStop: StateFlow<Boolean>`,
`suspend fun stopBackgroundTask(conversationId: String, taskId: String): Result<Unit>`
and `observeBackgroundTaskStopRefusals(conversationId: String): Flow<String>`.
The send captures `activeConnection.value?.repo` once and forwards both opaque keys
unchanged. No connection returns a failed `Result` with the static
`IllegalStateException("Background task stop not connected")`. The
[concrete send contract](remote-conversation-repository-control-sends.md#per-task-stop-support-send-and-refusal-1829)
defines acceptance, static failures, cancellation and subscribing before send.

Support is eagerly derived by switching to the current pump's state: true only
for `PumpState.Open` whose negotiated capabilities contain `stop_background_task`.
Absent capability, handshaking, closed and disconnected states report false.
Another host's grant and an earlier connection's grant cannot supply support.
The concrete repository rechecks its lazy capability supplier at send entry;
the asynchronously republished support value is not a send gate. Detection grants
no access; daemon `interactive` enforcement still decides whether it can act.

Refusal observation is a cold `activeConnection.flatMapLatest` flow, switching
to the current repository's per-conversation events and to `emptyFlow()` between
connections. Each host owns a separate coordinator and connection ledger, even
when conversation, task and request ids coincide across hosts.

Only an `error` with code exactly `stop_background_task.refused` and an
`in_reply_to` matching a pending local stop emits its registered task id to its
originating conversation. The reflected payload `conversation_id` asserts no
membership and has no routing authority. Before decoding `ErrorPayload`, require
JSON strings for `code` and `message`, and an unquoted JSON boolean for `retryable`.
The serializer can coerce primitives into strings, so typed decoding alone is
insufficient. Missing or wrong required field types, malformed payloads and other
codes emit nothing and leave valid pending correlation intact. Unknown, missing,
stale and duplicate request correlations also emit nothing.

Registration precedes send. A synchronized ledger consumes a matched refusal once,
atomically with emission, and a newer attempt for the same conversation/task pair
supersedes its old entry. Terminal updates and roster omission retire matching
entries as described [above](#background-task-roster-677). Failed or cancelled
sends remove only their own request id, preserving a concurrent newer attempt.
Coordinator teardown ends admission and clears tracking before cancellation;
inbound collector termination also ends it. Nothing is replayed or persisted on
reconnect, so an old refusal cannot affect a later request.

Stop diagnostics contain only fixed event names and static result codes. Never
log daemon or local identifiers, reflected conversation ids, raw frames, daemon
message text or exception messages. This differs from the roster's absence of
logging without weakening its content boundary.

`RemoteConversationRepositoryStopTaskTest` and `RelayRepositoryCoordinatorTest`
cover these races, lifecycle edges and host isolation. In particular, test a
terminal update for an unlisted task, an older send failing during a newer attempt,
and identical ids on independent hosts; a visible-row-only or single-host fixture
would miss those failures.

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

## Reconnect content recovery

The coordinator no longer owns `ReplayCursor` or passes replay suppliers to Noise
sessions. Reconnect uses the host-owned authoritative store and certified checkpoint
through fresh connection-owned queries. There is no raw fallback, resync or backfill.
See [query ownership](remote-conversation-repository-reads-and-thread-store.md).
