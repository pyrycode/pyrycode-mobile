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
- [Remote conversation repository — the Phase 4 `ConversationRepository` — thread-observable states and live tool rows](remote-conversation-repository-thread-observables.md) — `observeStall(conversationId) — the thread-observable stall state (#395)`, `observeQueue(conversationId) — the thread-observable queued backlog (#460)`, `observeApiRetry(conversationId) — the thread-observable API-retry state (#593)`, `observeCompacting(conversationId) — the thread-observable compaction state (#596)`, `observeThinkingProgress(conversationId) — the thread-observable thinking-progress reading (#801)`, `Live tool-call rows — applyToolUse / applyToolResult (#387)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — screen snapshot, dequeue, interrupt and new session](remote-conversation-repository-control-sends.md) — `requestScreenSnapshot(conversationId) — the parser-independent screen-snapshot read (#375)`, `requestHistory(conversationId, cursor, limit) — the on-disk history page read (#623)`, `dropQueuedMessage(conversationId, queuedMessageId) — the dequeue_message outbound send (#466)`, `interrupt(conversationId) — explicitly targeted v2 interrupt`, `startNewSession() — explicitly targeted v2 new_session`, `answerQuestionBatch(questionBatchId, answers) / refuseQuestionBatch(questionBatchId) — the v2 question_answer / question_refused sends (#825)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off](remote-conversation-repository-state-errors-and-handoff.md) — `State & concurrency model`, `Error handling`, `Hand-off — the live binding`

The sections that stay here: `## Where it sits in the Phase 4 stack`, `## Status projections: one file per status event`, `## The repository split (#912–#916): complete`, `## The `SessionPump` consumed contract`, `## Stubs — none remain; every method is now live`, `## Testing`, `## Related`.

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
outside `ConversationRepository` and screen state. `AttachmentUploadTransfer` (#829) is its upload-leg
sibling — also connection-owned, also routed first in `onInbound`, but **on** the
`ConversationRepository` surface (`uploadAttachment`) rather than beside it, since the destination is a
conversation, not the whole host. See [Attachment upload](attachment-upload.md).

## Status projections: one file per status event

Since 2026-09-22 the status events the thread observes each live in their own small internal class
beside the repository: `StallProjection` (#395), `QueueProjection` (#460), `ApiRetryProjection` (#593),
`CompactingProjection` (#596), `ThinkingProgressProjection` (#801), `UsageLimitProjection` (#802),
`AnnouncedModelProjection` (#890) and `SessionFactsProjection` (#890).
Each holds the state, the decoder and the cold read that used to sit in `RemoteConversationRepository`,
under the same names, so the per-event sections in the
[thread-observables document](remote-conversation-repository-thread-observables.md) and the
[live-stream document's `model_announced` / `session_facts` section](remote-conversation-repository-live-stream-and-modals.md#model_announced--session_facts--the-announced-model-and-session-facts-readings-890)
still describe them. Only the owning class changed, and behaviour did not. `UsageLimitProjection`'s
clearing edge is self-contained — a benign `status` frame clears its own entry inside `apply`, so it
needs no hook on another event's arm the way the stall/thinking-progress clears do.
`AnnouncedModelProjection` and `SessionFactsProjection` are each cleared only by the
`session_transition` arm, the same `ResettingProjection` shape, since neither `model_announced` nor
`session_facts` carries a falling edge of its own.

`AttachmentOfferProjection` (#898) holds the files the daemon offered per conversation on this
connection — state, decoder and read in the same one-class-per-event shape — but sits outside this
gated family: its `onInbound` arm calls `apply(envelope)` **unconditionally**, with no
`CAPABILITY_INTERACTIVE in negotiatedCapabilities()` check. The protocol delivers `attachment_offered`
to every attached client, outside the interactive family, the same posture as the upload leg's
`attachment_stored` (see `AttachmentUploadTransfer` below); the daemon routes nothing, so filtering on
`conversation_id` happens inside `observe`, as every other arm's projection does. First arrival wins for
a repeated attachment id. See [Conversation repository → `AttachmentOffer`](conversation-repository.md)
for the domain type, the id-shape validation and the code-point-level display-name cleaning, and its
SECURITY note.

The repository keeps three things. Its `onInbound` arm checks the negotiated `interactive` capability
and calls the projection's `apply(envelope)`. Its `observe…` override returns the projection's
`observe(conversationId)`. And a clear that one event causes in another stays in the arm that causes
it, through the projection's `clear`: every decoded live-session event clears that conversation's
stall, and a `turn_end` or `session_transition` clears its thinking-progress reading.
`dropQueuedMessage` reads `QueueProjection.current` to resolve the echo id before it sends.

A new status event takes the same shape: a new `…Projection.kt` holding its state, decoder and read,
plus one field, one arm and one override in the repository. The split exists so that sibling tickets
adding events in parallel stop editing the same lines of one very large file.

**The thread store followed the same split (#912), as `ThreadProjection`.** (See § The repository split
(#912–#916): complete, below, for the full command-class picture #914–#916 added on top of this.) It isn't a status event —
it's the thread itself: the ordered per-conversation rows, the minted-id ledger (#781) and the
pending-drops ledger (#859), plus every write that folds a thread row (`appendMessages`,
`appendSessionBoundary`, `applyUnrecognizedMessage`, `applyBanner`, `applyCompactionBoundary`,
`applyToolUse`/`applyToolResult`/`applyToolDenied`/`applyToolProgress`,
`applyAssistantDelta`/`finalizeAssistantTurn`, `mergeHistoryPage` and `remove`). The repository still owns
the `interactive` gate in each `onInbound` arm and still owns `requestHistory`, which records into the
projection instead of into a repository field; `sendMessage` and `dropQueuedMessage` moved on to
`MessageCommands` (#915), which records into the same projection. `observeMessages` fans out through
`threadProjection.observe(conversationId)`. See
[Remote conversation repository — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md)
for the store itself.

## The repository split (#912–#916): complete

Five tickets moved everything out of `RemoteConversationRepository` that was not routing: the thread
store into `ThreadProjection` (#912), the conversation list and last-message previews into
`ConversationListProjection` (#913), conversation-scoped commands (create, promote, rename, archive,
unarchive, delete, new session, interrupt, push-token registration, modal answer/cancel) into
`ConversationCommands` (#914), message and transfer commands (sending, uploads, the screen snapshot,
dequeue, the debug bundle) into `MessageCommands` (#915), and session settings, the system prompt and
workspace management into `SessionSettingsCommands` / `WorkspaceCommands` (#916). Each moved cluster kept
its behaviour, names and KDoc; the repository holds a one-line hand-off for every public method that
moved. No further cluster is scheduled to move.

What the repository still owns, after #916:

- **The single inbound collector and `onInbound`'s demux** — the one `pump.inbound` consumer, routing
  every envelope type to the right projection, command class or waiter. The two routing sites #916 left
  in place: the `session_transition` arm calls `sessionSettingsCommands.bumpSettingsRevision`, and the
  `TYPE_WORKSPACE_UPDATED` arm applies the label through `conversationListProjection.applyWorkspaceLabel`
  and calls `workspaceCommands.malformedWorkspaceReply()` on a decode failure.
- **Every status projection** the thread reads from: `ConversationListProjection`, `ThreadProjection`,
  `StallProjection`, `QueueProjection`, `ApiRetryProjection`, `CompactingProjection`, `UsageLimitProjection`,
  `ThinkingProgressProjection`, `ResettingProjection`, `ModelMenuProjection`, `SlashCommandMenuProjection`,
  `QuestionBatchProjection`,
  `BackgroundTaskProjection`, `AnnouncedModelProjection`, `SessionFactsProjection` and
  `AttachmentOfferProjection` — each its own small class, constructed once per repository instance, per
  the split described above in § Status projections.
  `SlashCommandMenuProjection` (#882) is the one member with no send and no capabilities supplier of its
  own — the frame it retains declares no inbound verb, so it takes the `ApiRetryProjection` minimal shape,
  not `ModelMenuProjection`'s (which also owns the `request_model_list` ask). `AttachmentOfferProjection`
  (#898) is the one member whose `onInbound` arm ignores the negotiated capabilities entirely — see
  § Status projections above.
- **`RelayRequests`** — the one request-id counter and reply-waiter table every command class, and the
  repository's own remaining reads, share.
- **The reads that fan out directly to a projection, with no command-class indirection**:
  `observeConversations`, `observeMessages`, `observeLastMessage`, `observeStall`, `observeQueue`,
  `observeApiRetry`, `observeCompacting`, `observeResetting`, `observeUsageLimit`,
  `observeThinkingProgress`, `observeModelMenu`, `observeSlashCommandMenu`, `observeAnnouncedModel`,
  `observeSessionFacts` and `observeAttachmentOffers` (#898).
- **`requestHistory`** — the on-disk history page read; kept here because it folds its page straight into
  `ThreadProjection`, and #916 explicitly left it in place.
- **The v2 structured-stream and modal decode seams** — `liveSessionEvents`, `modalEvents`,
  `answerQuestionBatch` / `refuseQuestionBatch` (fold into `QuestionBatchProjection`), and the replay
  cursor (`recordReplayCursor`, the resync arm).
- **The four command classes, as constructed dependencies, plus a one-line hand-off for each of their
  public methods**: `conversationCommands` (#914), `messageCommands` (#915), and `sessionSettingsCommands`
  / `workspaceCommands` (both #916).

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
  walking". No live rung: this slice has no operator-facing surface; scroll-back's live rung is #646),
  [#801](../../specs/architecture/801-thinking-progress-decode.md) (`observeThinkingProgress`,
  **landed** — the eighth projection `thinkingProgressByConversation`; a `TYPE_THINKING_PROGRESS`
  **full-replace** arm with **no branch and no clearing hook of its own** — the wire carries no falling
  edge, so its two clears fold into the existing `TurnEnd` branch of the live-session arm and the
  existing `TYPE_SESSION_TRANSITION` arm instead, following #593's payload-carrying `Map` shape rather
  than #395/#596's bare `Set` because the wire carries a reading, **on the interface with a
  `flowOf(null)` default** — see [Thinking-progress state](thinking-progress-state.md); UI reaction is
  an unfiled sibling of the same #653 split).
- Sibling transfer: [Attachment upload](attachment-upload.md) ([#829](https://github.com/pyrycode/pyrycode-mobile/issues/829),
  **landed**) — `uploadAttachment`, the `AttachmentUploadTransfer` upload-leg sibling of
  `DebugBundleTransfer`, the 8 MB local socket-queue bound, and the `StableConversationRepository`
  snapshot-or-result delegation it needed instead of the usual snapshot-or-throw.
- Sibling observable: `observeAttachmentOffers` (#898, **landed**, PR
  [#931](https://github.com/pyrycode/pyrycode-mobile/pull/931)) — the ninth status-family projection,
  `AttachmentOfferProjection`, decoding `attachment_offered` into `AttachmentOffer`; the one arm in the
  family that ignores the `interactive` capability check, matching `attachment_stored`'s delivery
  posture above rather than its status-projection siblings'; standard `switchToLive(emptyList()) { … }`
  facade delegation. Fetching the offered bytes and rendering the offer in the thread are the sibling
  and follow-up tickets `#671`/`#672`.
- Connection wiring: [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
  ([#351](../codebase/351.md), **landed**) — constructs this repository per live connection against the
  pump + a child scope, made `NoiseSessionPump : ManagedSessionPump : SessionPump`, and publishes the
  live instance on `currentRepository` (consumed by the **#352** facade). The flag-gated Koin binding
  selector that picks the Fake or the facade is **[#350](../codebase/350.md)**; since #631 it defaults
  to the facade, with the fake selected explicitly for demo builds and ordinary instrumentation.
</content>
