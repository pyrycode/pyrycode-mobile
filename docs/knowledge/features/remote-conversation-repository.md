# Remote conversation repository — the Phase 4 `ConversationRepository`

The **live, server-backed implementation** of the [`ConversationRepository`](conversation-repository.md)
contract — the Phase 4 counterpart to the in-memory `FakeConversationRepository`. It reads real server
state over the [Mobile Protocol v2](mobile-protocol-v2-wire-layer.md) Noise session instead of an
in-process seed store. Normal builds reach it through the Koin-bound
[`StableConversationRepository`](stable-conversation-repository.md) facade, keeping UI consumers
independent of connection churn and repository selection.

Package: `de.pyryco.mobile.data.repository` (`RemoteConversationRepository` + the consumer-defined
`SessionPump` interface), same package as the contract and the Fake. Built **slice by slice**: the
conversation-list read path landed in [#312](../codebase/312.md), the last-message preview in
[#329](../codebase/329.md), and the thread read path in [#313](../codebase/313.md); the **first mutation
path — `sendMessage` — landed in [#346](../codebase/346.md)** (which also introduced the shared
`ack`/`error` request↔reply correlation primitive the remaining mutations reuse). The mutation slice
\#314 was split on a per-method axis into #346 (`sendMessage`) / #347 (`createDiscussion`) / #348
(`promote`), each extending the **same class** as it lands. Portable, `android.*`-free.

> **Constructed per connection; consumed through the stable facade.** The
> [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) constructs one remote repository
> against each connection's pump and child scope, publishing it on `currentRepository` when the
> Noise pump is open. Normal builds select the facade through `conversationRepositoryModule`;
> `-PuseRelayRepository=false` selects `FakeConversationRepository` for a demo. The build flag
> selects the UI repository; pairing, lifecycle and connection readiness determine whether a live
> delegate exists. See [live binding](remote-conversation-repository-state-errors-and-handoff.md#hand-off--the-live-binding)
> and [dependency injection](dependency-injection.md).

## Map

Split on 2026-09-05 to keep this document under the 50000-byte cap the docs guard enforces. Each section named below moved verbatim, heading and anchors intact, into its own document:

- [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md) — `The repository — one projection, cold fan-out`, `observeConversations(filter) — the live method`, `observeLastMessage(conversationId) — the live last-message preview (#329)`, `observeMessages(conversationId) — the live thread read (#313)`, `The unified thread store and the session_transition fold (#336)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — send, create, promote and rename](remote-conversation-repository-send-create-promote-rename.md) — `sendMessage(conversationId, text) — the first mutation (#346)`, `createDiscussion(workspace) — the second mutation (#347)`, `promote(conversationId, name, workspace) — the third mutation (#348)`, `rename(conversationId, name) — the fourth mutation (#530)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — session settings, archive, delete and workspace change](remote-conversation-repository-conversation-writes.md) — `setSessionSettings(sessionId, model, effort, yolo) — the fifth mutation, first session-scoped ([#543](../codebase/543.md))`, `archive(conversationId) / unarchive(conversationId) — the sixth and seventh mutations (#549)`, `delete(conversationId) — the eighth mutation, first REMOVE-shaped one ([#532](../codebase/532.md))`, `changeWorkspace(conversationId, workspace) — the ninth mutation, last stub filled ([#560](../codebase/560.md))`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — workspace folders, recent workspaces and push registration](remote-conversation-repository-workspace-and-push.md) — `createWorkspaceFolder(name) — the tenth mutation, leanest write-verb, first override of a previously-defaulted read/write pair ([#564](../codebase/564.md))`, `recentWorkspaces() — the fourth read verb, leanest of the family, no fold ([#565](../codebase/565.md))`, `registerPushToken(token) — the device-concern push registration (#359)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — live stream, modal seams and the replay cursor](remote-conversation-repository-live-stream-and-modals.md) — `liveSessionEvents — the v2 structured-stream decode seam (#385)`, `modalEvents — the v2 permission/choice-modal decode seam (#437)`, `answerModal / cancelModal — the v2 modal answer/cancel control-send (#438)`, `recordReplayCursor(envelope) — the replay-cursor side-write (#412)`, `The resync arm — reset the cursor + surface the gap (#417)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — thread-observable states and live tool rows](remote-conversation-repository-thread-observables.md) — `observeStall(conversationId) — the thread-observable stall state (#395)`, `observeQueue(conversationId) — the thread-observable queued backlog (#460)`, `observeApiRetry(conversationId) — the thread-observable API-retry state (#593)`, `observeCompacting(conversationId) — the thread-observable compaction state (#596)`, `Live tool-call rows — applyToolUse / applyToolResult (#387)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — screen snapshot, dequeue, interrupt and new session](remote-conversation-repository-control-sends.md) — `requestScreenSnapshot(conversationId) — the parser-independent screen-snapshot read (#375)`, `requestHistory(conversationId, cursor, limit) — the on-disk history page read (#623)`, `dropQueuedMessage(conversationId, queuedMessageId) — the dequeue_message outbound send (#466)`, `interrupt(conversationId) — explicitly targeted v2 interrupt`, `startNewSession() — explicitly targeted v2 new_session`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off](remote-conversation-repository-state-errors-and-handoff.md) — `State & concurrency model`, `Error handling`, `Hand-off — the live binding`

The sections that stay here: `## Where it sits in the Phase 4 stack`, `## The `SessionPump` consumed contract`, `## Stubs — none remain; every method is now live`, `## Testing`, `## Related`.

## Where it sits in the Phase 4 stack

```
UI ViewModels  ◀── observeConversations(filter): Flow<List<Conversation>>
        ▲
StableConversationRepository   ◀── normal build binding; switches over coordinator.currentRepository
        ▲
RemoteConversationRepository (#312+)   ◀── this doc
        │  send(list_conversations) ; collect inbound conversations snapshots
        ▼  (over the SessionPump interface)
NoiseSessionPump (#309) ─ inbound: Flow<Envelope> / send(Envelope): Boolean
        ▼
RelayTransport (#306) ─ OkHttp WS ─ Noise_IK (#303)
```

The repository consumes the **portable `SessionPump` interface**, without reaching
below it to the raw transport or Noise session. Conversation wire-to-domain mapping
belongs to the [payload mappers](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope).
Authenticated payloads still require validation: the connection-owned
`DebugBundleTransfer` validates diagnostic chunk fields before retaining opaque
bytes. It shares the sole inbound consumer and exposes a separate
[host transfer API](relay-debug-bundle-transfer.md),
outside `ConversationRepository` and screen state.

## The `SessionPump` consumed contract

A minimal **consumer-defined** interface — the data layer's view of the Noise session pump:

```kotlin
interface SessionPump {
    val inbound: Flow<Envelope>            // hot, single-consumer, decrypted app envelopes
    fun send(envelope: Envelope): Boolean  // false if the session is not Open; never throws
}
```

It lives in `data/repository/` (next to its consumer), **not** in `data/network/` (where the concrete
`NoiseSessionPump` lives), mirroring the [`ConnectionStateSource`](connection-state.md) precedent: the
consumer defines the contract it needs; the real impl satisfies it a layer down. The two members match
`NoiseSessionPump`'s structurally, so the DI slice makes the concrete class conform with just
`: SessionPump` + two `override`s.

`inbound` is **hot and single-consumer** (the pump runs the one inbound collector regardless of
subscribers and surfaces each decrypted `Envelope` exactly once). `send` returns `false` (no throw) if
the session is not `Open`. Note the pump's consumer-awareness rule: a `false` return on an `Open` session
means the session is **spent** (a nonce was consumed) — do **not** re-send the same envelope on the same
session; the supervisor will reconnect with a fresh handshake. The repository does not buffer or retry —
see [Error handling](remote-conversation-repository-state-errors-and-handoff.md#error-handling).

## Stubs — none remain; every method is now live

The three live read paths plus `sendMessage` (#346) / `createDiscussion` (#347) / `promote` (#348) /
`rename` ([#530](../codebase/530.md)) / `startNewSession` ([#539](../codebase/539.md)) /
`setSessionSettings` ([#543](../codebase/543.md)) / `archive` / `unarchive` ([#549](../codebase/549.md)) /
`delete` ([#532](../codebase/532.md)) / `changeWorkspace` ([#560](../codebase/560.md)) /
`createWorkspaceFolder` ([#564](../codebase/564.md)) / `recentWorkspaces` ([#565](../codebase/565.md)) /
`requestHistory` (#623)
cover every method the interface declares that this repository overrides — `changeWorkspace` was the
**last** `UnsupportedOperationException` stub (#549's doc named it as the "remaining throwing sibling");
`createWorkspaceFolder` and `recentWorkspaces` were separate, interface-default (not throwing-stub)
methods that #564 and #565 respectively later gave live overrides. No method on this class throws an
unimplemented-stub exception, and no method still falls back to an interface default, any more. All
three read paths are **cold flows that defer work to collection** (the eager expression-body `throw`
shape #312's NIT flagged is gone with the last read stub).

`override val mutationsSupported: Boolean = false` (#507) still hardcodes the capability off even though
every mutation is now wired live ([#560](../codebase/560.md) closed the last stub) — flipping it is a
deliberate, separate coarse-flag milestone (the #537 family: "gate cleared ≠ buildable"; each
mutation-consuming affordance needs its own reachability check before the flag can safely flip). A UI
gating consumer reads it (through the [facade](stable-conversation-repository.md)) to hide these actions
until that milestone lands. See [`../codebase/507.md`](../codebase/507.md).

## Testing

JVM unit only (`app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt`, `./gradlew test`),
JUnit4 + `runTest` + a hand-written **fake `SessionPump`** — `inbound` backed by a
`Channel<Envelope>(UNLIMITED).receiveAsFlow()` (so test pushes are not lost before the collector attaches);
`send` records each envelope and returns `true`; a `push` helper feeds inbound. The repository is
constructed with `backgroundScope` so its collector auto-cancels at test end. `conversations` payloads are
built from the same object-wrapped-array fixture shape as `ConversationsPayloadTest` via
`MobileJson.parseToJsonElement(raw)`.

> **Test idiom (reusable across the sibling slices #313/#314/#329):** drive the push→demux→project→emit
> cascade with **`runCurrent()`, not `advanceUntilIdle()`**. With a `Channel.receiveAsFlow()` inbound
> feeding a single repository-internal collector on `backgroundScope`, `advanceUntilIdle()` does not
> deliver the buffered channel item to the background collector (there are no timers to elapse), leaving
> projection-dependent assertions empty; `runCurrent()` drains the whole current-time cascade
> deterministically. See [[remote-repo-test-runcurrent-not-advanceuntilidle]] and
> [`codebase/312.md`](../codebase/312.md) § Lessons learned.

## Related

- Contract + Phase 1 binding: [Conversation repository](conversation-repository.md)
  (`ConversationRepository` interface, `ConversationFilter`, `ThreadItem`; the in-memory
  `FakeConversationRepository` this is the live counterpart to).
- Consumes: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) — the `inbound` /
  `send` surface, over the `SessionPump` interface. [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md)
  — `Envelope`, `MobileJson`, and the [#316](../codebase/316.md) `ConversationsPayload.toConversations()`
  decode-and-validate boundary. [Data model](data-model.md) — the domain `Conversation` it produces.
- Precedent: [Connection state](connection-state.md) — the consumer-defined-interface-in-`data/repository/`
  pattern `SessionPump` follows.
- Ticket notes: [`../codebase/312.md`](../codebase/312.md) (the list read path) ·
  [`../codebase/329.md`](../codebase/329.md) (the last-message preview) ·
  [`../codebase/313.md`](../codebase/313.md) (the thread read + backfill) ·
  [`../codebase/346.md`](../codebase/346.md) (`sendMessage` + the `ack`/`error` correlation primitive) ·
  [`../codebase/347.md`](../codebase/347.md) (`createDiscussion` + the `upsertConversation` confirmed-insert) ·
  [`../codebase/348.md`](../codebase/348.md) (`promote` + the `cwd`-resolution decision) ·
  [`../codebase/359.md`](../codebase/359.md) (`registerPushToken` — the first non-interface device-concern
  method + the deferred `deviceName` handoff) ·
  [`../codebase/543.md`](../codebase/543.md) (`setSessionSettings` — the first session-scoped mutation,
  no state fold, new reply-type demux arm) ·
  [`../codebase/549.md`](../codebase/549.md) (`archive`/`unarchive` — shared request DTO for a symmetric
  verb pair, the `is_archived` decode-boundary extension, the dormant `rename` fix it carries) —
  files/line refs, patterns, lessons, verification.
- Specs: `docs/specs/architecture/312-remote-conversation-repository-observe-list.md` ·
  `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` ·
  `docs/specs/architecture/313-remote-observe-messages.md` ·
  `docs/specs/architecture/346-remote-send-message.md` ·
  `docs/specs/architecture/347-remote-create-discussion.md` ·
  `docs/specs/architecture/348-remote-promote.md` ·
  `docs/specs/architecture/359-register-push-token-wire-sender.md` ·
  `docs/specs/architecture/543-wire-session-settings.md` ·
  `docs/specs/architecture/549-archive-unarchive-conversation-wire.md` ·
  `docs/specs/architecture/623-paged-conversation-history.md`.
- Siblings (extend the same class + `onInbound` `when`): [#329](../codebase/329.md)
  (`observeLastMessage`, **landed** — consumes [#317](../codebase/317.md), rides the live `message`
  stream), [#313](../codebase/313.md) (`observeMessages`, **landed** — consumes #317 + adds the
  `backfill_since` → `message_chunk` thread plumbing; boundaries #336 / streaming #337 de-scoped, both
  blocked on server-side v2 protocol additions), [#346](../codebase/346.md) (`sendMessage`, **landed** —
  the first mutation; added the shared `ack`/`error` correlation primitive), [#347](../codebase/347.md)
  (`createDiscussion`, **landed** — the second mutation; consumes [#318](../codebase/318.md)'s
  `ConversationResponseDto.toConversation()`, reuses #346's correlation primitive, and adds the
  `upsertConversation` confirmed-insert into the list projection), [#348](../codebase/348.md)
  (`promote`, **landed** — the last #314 mutation; consumes #318's `ConversationResponseDto`, reuses
  #346's primitive + #347's `upsertConversation` fold verbatim, adds the `promote_conversation` request
  encoder + the null-`workspace` `cwd` resolution), [#359](../codebase/359.md) (`registerPushToken`,
  **landed** — the **first non-interface** device-concern method; reuses #346's `sendAndAwaitReply` +
  `mapError` verbatim with **no** `onInbound` branch and **no** projection mutation, adds the
  `register_push_token` request encoder + the last/defaulted `deviceName` ctor param), [#365](../codebase/365.md)
  (`registerPushToken`'s **first live caller**, **landed** — the coordinator's connect-time hook re-sends it
  once per connection and threads the live `deviceName`, closing #359's `device_name: ""` defer; still
  dormant until Firebase #361 stores a token), [#395](../codebase/395.md) (`observeStall`, **landed** —
  the fourth projection `stalledConversations`; a `TYPE_STALL` onset arm + a clearing hook folded into
  #385's live-session arm; **on the interface with a `flowOf(false)` default** so it reaches the thread
  through the facade, the deliberate inverse of #385's concrete-only `liveSessionEvents` — see
  [Stall state](stall-state.md)), [#460](../codebase/460.md) (`observeQueue`, **landed** — the fifth
  projection `queuedByConversation`; a `TYPE_QUEUE_STATE` **full-replace** arm with **no clearing hook**
  (a snapshot is whole-truth state, the simpler counterpart to #395's onset-only model), **on the interface
  with a `flowOf(emptyList())` default** so the backlog reaches the thread through the facade — see
  [Queued backlog](queued-backlog.md)), [#387](../codebase/387.md) (`applyToolUse`/`applyToolResult`,
  **landed** — correlate `tool_use`/`tool_result` into one evolving `Role.Tool` row keyed by
  `toolUseId`; a `when (event)` dispatch folded into #385's live-session arm mutates the existing
  `threadByConversation` so tool rows interleave by arrival order, the inverse choice from #395's
  separate projection — see [Live tool-call](live-tool-call.md)), [#417](../codebase/417.md) (the
  **`resync` arm**, **landed** — `reset()`s the [`ReplayCursor`](replay-cursor.md) #412 records + #416
  advertises and `tryEmit`s a control-derived [`LiveSessionEvent.ReplayGap`](live-session-events.md) on
  the existing `liveSessionEvents`; a payload-less inline marker read structurally, no DTO — see
  [the resync arm](remote-conversation-repository-live-stream-and-modals.md#the-resync-arm--reset-the-cursor--surface-the-gap-417)), [#543](../codebase/543.md)
  (`setSessionSettings`, **landed** — the sixth mutation and the **first session-scoped** one (`sessionId`,
  not `conversationId`); reuses `sendAndAwaitReply` + `mapError` verbatim, adds a **new** reply-type demux
  arm (`session_settings_updated`, not a reused one like `rename`/`promote`), and is the first mutation
  with **no state fold** since `registerPushToken` — its ack carries only an echoed `session_id`, nothing
  to project. Data-layer slice of the #536 split; #544 wires the Status-sheet controls to it and adds the
  `StableConversationRepository` facade delegation), [#549](../codebase/549.md) (`archive`/`unarchive`,
  **landed** — the sixth and seventh mutations; replace their `UnsupportedOperationException` throws,
  reuse `sendAndAwaitReply` + `mapError` + the existing `TYPE_CONVERSATION_UPDATED` demux arm verbatim,
  add one shared `ArchiveConversationPayloadDto` + a private `sendArchiveToggle` helper both overrides
  delegate to, and extend `ConversationResponseDto` with a defaulted `is_archived` field so the fold
  actually moves the conversation between tiers — data-layer slice of the #531 split; #550 wires the
  ViewModel surfacing, #551 the rung-3 e2e), [#532](../codebase/532.md) (`delete`, **landed** — the eighth
  mutation and the **first REMOVE-shaped** one; replaces the interface-default throw, adds a genuinely
  **new** reply type (`conversation_deleted`, requiring an `onInbound` demux registration rename/archive
  didn't need), and folds via a new `removeConversation` helper that clears all three read projections
  rather than upserting one. `conversation.not_found` converges as success — the deliberate divergence
  from rename/archive's IAE-crash-on-not-found. `mutationsSupported` stays `false`; the rung-3 e2e is
  #554, Inbox, blocked by this ticket), [#593](../codebase/593.md) (`observeApiRetry`, **landed** —
  the sixth projection `apiRetryByConversation`; a `TYPE_API_RETRY` **full-replace** arm with **no
  branch on `active`** and **no clearing hook** (`toStatus()` owns edge semantics; unlike #395 this
  arm never touches the live-session arm, so it does not clear a stall), following #460's
  payload-carrying `Map` shape rather than #395's bare `Set` because the wire carries a counter, **on
  the interface with a `flowOf(NotRetrying)` default** so the retry state reaches the thread through
  the facade — see [API-retry status](api-retry-status.md); UI reaction is sibling #594, not shipped),
  [#596](../codebase/596.md) (`observeCompacting`, **landed** — the seventh projection
  `compactingConversations`; a `TYPE_COMPACTING` arm that clones #395's bare `Set` (not #593's `Map`,
  since the wire carries no counter) but — unlike #395 — has an **explicit falling edge** on the wire,
  so `if (active)` branches inside the arm itself rather than a mapper owning it; never touches the
  live-session arm, so it does not clear a stall or fold a thread row, **on the interface with a
  `flowOf(false)` default** — see [Compacting state](compacting-state.md); UI reaction is sibling #597,
  not shipped), #623 (`requestHistory`, **landed** — the on-disk history page read; reuses
  `sendAndAwaitReply` + `mapError` verbatim, adds a **new** reply-type demux arm (`history_page`, not a
  reused one) and, like `registerPushToken`/`setSessionSettings`, folds no projection — a page is
  returned to the caller and folded into the timeline by #645. `at_start` is the **only** end-of-log
  signal, strict-decoded with no default so an absent key fails rather than silently reading as "keep
  walking". No live rung: this slice has no operator-facing surface; scroll-back's live rung is #646).
- Connection wiring: [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
  ([#351](../codebase/351.md), **landed**) — constructs this repository per live connection against the
  pump + a child scope, made `NoiseSessionPump : ManagedSessionPump : SessionPump`, and publishes the
  live instance on `currentRepository` (consumed by the **#352** facade). The flag-gated Koin binding
  selector that picks the Fake or the facade is **[#350](../codebase/350.md)**; since #631 it defaults
  to the facade, with the fake selected explicitly for demo builds and ordinary instrumentation.
</content>
