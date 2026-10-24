# Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## State & concurrency model

- **Agent-switch pending work (#1117)** belongs to one connection's synchronized
  `SwitchAgentCommands` ledger. Admission, registration and non-suspending send
  share the same monitor as confirmation, refusal, cancellation removal and end,
  so teardown cannot sweep and then admit an orphaned waiter. Refused or throwing
  sends return typed `Unavailable`; caller cancellation propagates and removes
  only that registration in `finally`. No new operation job, timeout or retry is
  introduced. Coordinator teardown ends switches synchronously before cancelling
  the collector; the collector's `finally` and its idempotent job completion hook
  also end admission and settle all pending switches. **Finally alone is
  insufficient:** cancelling a collector before its body starts skips that block.
  `scopeCancellationBeforeCollectorStartsEndsPendingCalls` protects this case.
  An ended repository rejects later calls; a fresh connection gets a fresh ledger,
  so reused request ids cannot settle work from the previous connection. See
  [agent-switch confirmation](remote-conversation-repository-conversation-writes.md#agent-switching-1117).
- **Session errors have inbound, caller-send and teardown writers (#1677).**
  `SessionErrorProjection` holds a connection-local `StateFlow<Map<String, String>>`;
  pure atomic `update`/`getAndUpdate` operations replace or remove one conversation's
  code without clobbering another. Its cold per-conversation read supplies the current
  nullable code immediately and suppresses duplicate values and unrelated updates.
  No extra collector, job or timer is needed. It is excluded from `HostReadings`,
  replay reconstruction and the disk cache; teardown resets the map.
- **Five `StateFlow` projections — `projection` (the conversation list, #312), `lastMessages` (#329's
  per-conversation most-recent `Message`), `threadByConversation` (#313's per-conversation ordered
  thread), `stalledConversations` (#395's per-conversation stall `Set<String>`), and `queuedByConversation`
  (#460's per-conversation queued backlog `Map<String, List<QueuedMessage>>`, both held in their own
  projection classes since 2026-09-22) — fed by one inbound collector** launched on the injected connection `scope`. No second collector or scope is added per
  slice; a single `message` envelope can update **two** projections (`lastMessages` +
  `threadByConversation`), and a single decoded `LiveSessionEvent` both `tryEmit`s on `liveSessionEvents`
  and clears `stalledConversations` for its conversation (#395). `stalledConversations` is **single-writer**
  on this collector (onset on a `stall` arm, clearing on the live-session arm — never from a caller
  coroutine), so onset and clearing cannot race. The scope (and thus the collector) is cancelled by its
  owner — the [#351 coordinator](relay-repository-coordinator.md) — when the connection ends; the pump completing `inbound` on teardown also ends the
  collector naturally. All projections are in-memory and connection-scoped — lost on process death and
  re-derived from the live stream (+ a re-`backfill_since`) on reconnect. `queuedByConversation` (#460) is
  the same shape: **single-writer** on this collector (one `TYPE_QUEUE_STATE` full-replace arm, no clearing
  hook), each snapshot overwriting one conversation's entry. The #417 `resync` arm adds **no projection**:
  it `reset()`s the coordinator's process-scoped [`ReplayCursor`](replay-cursor.md) (the **same single
  writer** as `recordReplayCursor`, so reset and record never race within a connection) and `tryEmit`s a
  `ReplayGap` on `liveSessionEvents`.
- **The list `projection` has exactly one writer for every conversation mutation: the inbound collector
  itself (#346 / #347 / #348 / #721 / #996 / #2213).** `sendMessage` (#346) remains the second writer of
  `lastMessages` and `threadByConversation` — that pair is unrelated to the list. But every conversation-row
  mutation — `createDiscussion`/`createChannel` (#347), `promote` ([#348](../codebase/348.md)), the archive
  toggle, mute, rename, `setSystemPrompt` and `changeWorkspace` — now shares one `onInbound` arm,
  `TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED ->`, that decodes `ConversationResponseDto`, folds
  it into `projection` via `upsertConversation`, and **only then** hands the stored row to
  `RelayRequests.completeConversationReply`, which captures it on the pending request and completes the
  caller's deferred. The command functions no longer decode their own reply or call `upsertConversation`
  themselves — they call `RelayRequests.sendAndAwaitConversation(request)` and return whatever the collector
  already published. A correlated reply and an unsolicited other-device push both fold through this one arm,
  keyed by the payload's own `id`; `conversation_created` is additionally gated to only fold when a waiter
  is registered (the daemon never broadcasts a create unsolicited), while `conversation_updated` folds
  unconditionally (the correlated and push cases are indistinguishable at the frame). This closes the window
  #996 left open: before #2213, a correlated reply folded in the collector but a *second*, independent
  upsert ran again in the caller's coroutine after resumption, so a newer queued record (an unsolicited push,
  or another mutation's reply) that arrived and folded **after** the collector's first fold but **before**
  the caller resumed could be silently overwritten by the caller replaying its own, now-stale, decoded copy.
  Capturing the row at the collector and returning that capture — never re-decoding or re-upserting on the
  caller's side — makes inbound arrival order the only order that matters: whichever record folds last
  **wins and stays won**, regardless of which caller is still suspended waiting for an earlier reply.
  Cancelling the caller changes nothing either way, since the fold already happened on the collector before
  the caller was ever resumed. A malformed reply still folds nothing (decode precedes every fold) and fails
  the waiting command with the #318 decode exception, exactly as before. `promote` still **reads**
  `projection.value` (a lock-free snapshot) to resolve the cwd before sending; that read is unrelated to this
  write path and remains intentionally not atomic with it, for the reason given below.
  `RemoteConversationRepositoryPinTest` (added for #2213, but exercising every affected command, not only
  pins) is the regression suite: `queuedCommandRepliesCannotOverwriteLaterConfirmedPins` pins the ordering
  guarantee directly, `replyAndPushDeliveryIsIdempotentInEitherOrder` and
  `equalConversationIdsRemainConnectionLocal` cover duplicate/host-isolation safety,
  `malformedCommandRepliesFailWithoutPublishing` and `cancelledCommandsKeepInboundConfirmedPins` cover the
  failure and cancellation edges, and `restoredPinsSurviveOfflineAndReconcileWithFreshSnapshots` covers
  reconnect: a cold-started row restored from [the disk cache](conversation-cache-layout.md#the-cache-local-record-must-mirror-every-conversation-field-999)
  while the host was unreachable keeps its pin through `projection`'s existing per-id upsert rules once a
  live connection's first `conversations` snapshot or `conversation_updated` push supplies the current
  value — no separate reconciliation step, no duplicate row, the same fold every other field already relies
  on. The resolved cwd is request data, not a guarded invariant, so a concurrent snapshot landing between the
  read and the send only changes which authoritative cwd the request carries (benign — no TOCTOU of
  consequence). The KDoc on every affected field and command was updated to name the collector as the sole
  writer.
- **The `pendingRequests` registry (#346), since #914 owned by `RelayRequests`** (`data/repository/RelayRequests.kt`;
  `ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>`)
  tracks awaited replies: an in-flight mutation request registers a deferred keyed by its
  envelope id (minted by `RelayRequests.nextRequestId`), the collector looks the waiter up through
  `RelayRequests.waiter` and completes it on the correlated `ack`/`error`, and the awaiting caller (through
  `RelayRequests.sendAndAwaitReply`) removes its own entry in a `finally`. Bounded by caller concurrency (one
  entry per in-flight send, removed on success/error/cancellation). On collector termination,
  `RelayRequests.failAllPending` also fails and removes registered requests so connection loss does not
  strand an awaiting caller (#488). The repository still owns `onInbound`: it looks up a waiter through
  `RelayRequests.waiter` and decides how to complete it, so the `conversation_updated` fold-then-complete order
  (#996) and the `workspace_updated` apply-then-complete order live in the repository, not in `RelayRequests`.
- **`RelayRequests.waiter` hands back the pending `CompletableDeferred` itself, not a `complete(id, payload):
  Boolean` helper.** A `complete`-style helper looked tidier when #914 moved this plumbing, but
  `Deferred.complete` returns `false` for an already-completed waiter with no other signal — the
  `conversation_updated` arm would then have folded a **duplicate** correlated reply into the list (no
  existing test catches a duplicate reply, since the daemon does not normally send one), a behaviour change
  the move must not introduce. Returning the waiter lets each `onInbound` arm keep its own completion
  decision — at the time `conversation_updated` tested for a waiter before deciding to fold, `workspace_updated`
  failed a waiter on a malformed frame and completed it only after the apply — exactly as before the move.
  Since #996 `conversation_updated` folds first and completes any waiter afterwards, so a duplicate reply now
  folds by design, harmlessly. Since #2213 the `conversation_created`/`conversation_updated` arm completes
  through the typed `RelayRequests.completeConversationReply(inReplyTo, payload, conversation)` instead of
  calling `waiter(...)?.complete(payload)` directly: it captures the folded `Conversation` on the pending
  request's own `PendingReply` *before* completing the deferred, so `sendAndAwaitConversation` can return
  that capture without decoding the payload a second time. `completeConversationReply` checks
  `pending.payload.isCompleted` itself rather than relying on `Deferred.complete`'s boolean, for the same
  already-completed-waiter reason this bullet gives above. Every other arm — `ack`, `system_prompt`,
  `workspace_updated`, `error` — still completes through the plain `waiter(...)?.complete(...)` /
  `completeExceptionally(...)` path; only the two conversation-reply types needed a captured value alongside
  the raw payload.
- **Diagnostic archive transfers** use the same `RelayRequests.nextRequestId` allocator and sole
  inbound collector, with a separate synchronized `DebugBundleTransfer` retained
  for the connection lifetime — since #915 the admission logic and the retained transfer live on
  `MessageCommands` (`data/repository/MessageCommands.kt`), beside the attachment-upload state; the
  repository's `internal fun requestDebugBundle()` / `endDebugBundle()` are one-line hand-offs, kept
  `internal` because `RelayRepositoryCoordinator` and `DebugBundleTransferTest` call them on the
  repository. Admission reserves it before sending; chunk/done
  frames and bundle-correlated errors are offered to it before ordinary handlers.
  A settled attempt remains retained to absorb late frames and prevent unsafe
  reuse. The collector's `finally` calls `endDebugBundle()` (the repository hand-off) to disable
  admission permanently and settle any incomplete transfer, then `messageCommands.endAttachmentUploads()`,
  then `RelayRequests.failAllPending()`
  — the collector's order (debug bundle, then attachment uploads, then the sweep) held unchanged across
  the #914 and #915 moves.
  Coordinator teardown also calls `endDebugBundle()` synchronously before
  cancellation. See [host API and retry lifetime](relay-debug-bundle-transfer.md).
- **Dispatcher inherited from the injected scope** (DI uses `Dispatchers.Default`; this is pure CPU/JSON
  work — the socket I/O is the transport's, below the pump). Not hard-coded.
- `observeConversations`, `observeLastMessage`, and `observeMessages` are cold; N concurrent collectors
  share the projections (fan-out off the single inbound consumer).

## Error handling

| Failure mode | Result |
|---|---|
| Diagnostic send returns false or throws | Static `SEND_FAILED`; no raw exception reaches transfer state or logs; another attempt requires reconnect. |
| Bundle-correlated `error` | `REFUSED`, determined solely by `in_reply_to`; the daemon error body is not decoded. Unrelated errors retain their existing routing. |
| Malformed bundle chunk/done, ordering gap/duplicate or count mismatch | `INVALID_STREAM`; partial chunks wiped/released, no archive exposed, ordinary inbound routing continues. Late bundle frames cannot change the result. |
| Inbound completion, failure or cancellation; coordinator teardown | Receiving bundle becomes `DISCONNECTED`; admission closes. Already settled results remain unchanged. |
| Malformed `conversations` payload | `IllegalArgumentException` caught per-envelope (covers both #316 families — `MissingFieldException` ⊂ `SerializationException`, and the kotlinx-datetime bad-timestamp throw); envelope **dropped**; collector survives; projection unchanged |
| Malformed `message` payload (missing field / unmappable role e.g. `system` / bad `ts`) | `IllegalArgumentException` caught per-envelope (covers the #317 `SerializationException` decode failure and the `Instant.parse(ts)` throw); envelope **dropped silently** (no payload logged — content may be sensitive); collector survives; `lastMessages` **and** `threadByConversation` unchanged ([#329](../codebase/329.md) / [#313](../codebase/313.md)) |
| Malformed `message_chunk` (any one row bad) | the **whole chunk** dropped in one `catch (IllegalArgumentException)` (decode + map-all under one `try`); collector survives; thread unchanged ([#313](../codebase/313.md)) |
| `pump.send` returns `false` (session not `Open`) | request (`list_conversations` or `backfill_since`) silently not sent (no throw); the projection stays empty until a later subscribe succeeds or a push arrives — the live stream still fills the thread, and the next subscribe re-issues |
| `pump.inbound` completes (teardown) | collector completes; session errors reset to null; other last projections retained; live `StateFlow` collectors do not complete |
| Unknown `Envelope.type` | no-op — `backfill_done` (informational) falls to the intentional `else`; `messages` (a never-defined type) stays ignored. `conversation_created` has no unsolicited half (the daemon never broadcasts a create), so since #2213 its shared arm with `conversation_updated` drops an unmatched-waiter `conversation_created` before decode — a harmless no-op, not a fold |
| Malformed `conversation_created` / `conversation_updated` payload, correlated or unsolicited (#721, #996, #2213) | decode via `MobileJson.decodeFromJsonElement<ConversationResponseDto>` throws `IllegalArgumentException` (⊃ `SerializationException`), caught before any fold; nothing is folded; collector survives; `projection` unchanged. `RelayRequests.completeConversationReply` is still called with a `null` captured conversation, so a matching waiter's deferred completes normally (not exceptionally) with the raw payload, but `sendAndAwaitConversation` then finds no captured row and throws its own `SerializationException("Malformed conversation reply")` in the caller's coroutine — a static message naming no field, not the original decode exception. The catch logs nothing, since the payload carries the conversation's name and cwd |
| Malformed `workspace_updated` payload — missing/wrong-typed `path` (#721) | `WorkspaceUpdatedPayloadDto` decode throws `IllegalArgumentException` (⊃ `SerializationException`), caught before `applyWorkspaceLabel` runs; envelope **dropped**; collector survives; `projection` unchanged; a later valid `workspace_updated` still applies. Neither `path` nor `label` is logged on this or any other branch |
| `workspace_updated` / unsolicited `conversation_updated` whose `path` / `id` matches no row (#721) | no-op by `StateFlow` conflation (the fold returns an element-equal list) — not an error; nothing re-emits |
| `sendMessage` — server `error` `conversation.not_found` (#346) | `IllegalArgumentException` (fake parity); **the echo stays projected** ([#1355](https://github.com/pyrycode/pyrycode-mobile/issues/1355) draws it before the await, so a refusal no longer unwinds it) |
| `sendMessage` — any other server `error` (#346) | `RelayErrorException(code, retryable, message)` — structured for ViewModel branching; the echo stays projected (#1355) |
| `sendMessage` — `pump.send` returns `false` (not `Open`, #346) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; the echo is already projected by this point (#1355) |
| `sendMessage` — malformed/undecodable `error` payload (#346) | `RelayRequests.mapError` falls back to a `RelayErrorException(error.malformed_reply)` so the waiter is unblocked and the lone collector survives; the echo stays projected (#1355) |
| `createDiscussion` (on `ConversationCommands` since #914) — server `error` (#347) | `RelayErrorException(code, retryable, message)` via the shared `RelayRequests.mapError`; **no projection mutated** (the confirmed-insert runs only after a successful decode). `conversation.not_found` is not meaningful for create and is not exercised |
| `createDiscussion` — `pump.send` returns `false` (not `Open`, #347) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `createDiscussion` — malformed `conversation_created` reply (#347, #2213) | the shared collector arm's decode fails before any fold, so **no projection mutated** (a garbage success reply cannot inject a partial conversation); `RelayRequests.sendAndAwaitConversation` then throws its own `SerializationException("Malformed conversation reply")` in `createDiscussion`'s caller, not the original decode exception — see the shared row above |
| `promote` (on `ConversationCommands` since #914) — server `error` `conversation.not_found` (#348) | `IllegalArgumentException` via the shared `RelayRequests.mapError` — promoting an unknown conversation is **meaningful** here (unlike create), so this branch **is** exercised; **no projection mutated** (the confirmed-upsert runs only after a successful decode) |
| `promote` — any other server `error` (#348) | `RelayErrorException(code, retryable, message)` via `RelayRequests.mapError`; no projection mutated |
| `promote` — `pump.send` returns `false` (not `Open`, #348) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `promote` — malformed `conversation_updated` reply (#348, #2213) | the shared collector arm's decode fails before any fold, so **no projection mutated** (no partial promote); `promote`'s own `sendAndAwaitConversation` call then throws the same generic `SerializationException`, not the original decode exception — see the shared row above |
| `setSessionSettings` — server `error` `session.not_found` (unhosted session, #543) | `RelayErrorException(code = "session.not_found", …)` via `RelayRequests.mapError`'s else-branch — **not** `IllegalArgumentException` (unlike `conversation.not_found`; there is no IAE-crash path for this verb) |
| `setSessionSettings` — server `error` `protocol.malformed` (invalid model/effort) / `server.binary_offline` (#543) | `RelayErrorException(code, retryable, message)` via `RelayRequests.mapError`; no projection mutated (there is none) |
| `setSessionSettings` — `pump.send` returns `false` (not `Open`, #543) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited |
| `setSessionSettings` — malformed `session_settings_updated` reply (#543) | the `SessionSettingsUpdatedPayloadDto` decode's `SerializationException` (⊂ `IllegalArgumentException`), propagated to the caller; the decode is validation-only (result discarded either way) |
| `requestScreenSnapshot` — server `error` `conversation.not_found` / any other / not-`Open` send / malformed `screen_snapshot` reply (#375) | `IllegalArgumentException` / `RelayErrorException` / `IllegalStateException` respectively via the shared `RelayRequests.mapError` + `RelayRequests.sendAndAwaitReply`'s `check`; a malformed reply throws the #374 `SerializationException` (⊂ `IllegalArgumentException`) **caller-side** after `RelayRequests.sendAndAwaitReply` returns. A pure read — **nothing mutated** on any path; nothing logged |
| `dropQueuedMessage` — not-`Open` send (`pump.send` → `false`, #859) | `IllegalStateException`. The withdrawal of the just-recorded `pendingDrops`/drop entry and the throw are the only outcome — the send is fire-and-forget (`pump.send`, not `RelayRequests.sendAndAwaitReply`) since #859, so there is no server `error` path any more. **The backlog row still updates only via a later `queue_state`** (nothing mutated there on any path); on the throw, `removeOwnEcho` (#781) is never reached, so the sender's own thread echo is left in place too — nothing to roll back, nothing logged. See [Queued backlog § Dropping a queued entry](queued-backlog.md) |
| `interrupt` (on `ConversationCommands` since #914) — not-`Open` send (`send` → `false`, #458) | `IllegalStateException` from the `check` — **no reply awaited** (fire-and-forget, a plain pump send not `RelayRequests.sendAndAwaitReply`), so it cannot hang. No projection mutated, nothing to roll back, nothing logged. The caller (`ThreadViewModel.sendInterrupt`) swallows it inert. No server-`error` path exists (the daemon sends no reply) |
| Malformed `stall` payload (missing / wrong-typed `conversation_id`, #395) | `decodeStall` catches `IllegalArgumentException` (⊃ `SerializationException`) → `null` → the one envelope dropped, **single inbound collector survives** (AC #3); `stalledConversations` unchanged; nothing logged. A later valid `stall` still flips state |
| `stall` on a non-`interactive` connection (#395) | dropped **before** decode by the `TYPE_STALL` capability gate — never surfaces (fail-closed, defence in depth on the server-side fan-out gate) |
| Malformed `queue_state` payload (bad `conversation_id`, a bad item — `queued_msg_id` as a string, missing `text`, unparseable `ts`, #460) | `decodeQueueState` catches `IllegalArgumentException` (⊃ `SerializationException`, + the per-item `Instant.parse`) → `null` → the one envelope dropped, **collector survives** (AC #4); `queuedByConversation` unchanged; nothing logged. **One bad item drops the whole snapshot.** A later valid `queue_state` still surfaces |
| `queued` is `null` / `[]` / absent (empty backlog, #460) | `toQueue()` coalesces via `.orEmpty()` → the conversation's backlog is replaced with `emptyList()` — a legitimate "queue drained" snapshot, not an error (the one wire latitude; `MobileJson` has no `coerceInputValues`) |
| `queue_state` on a non-`interactive` connection (#460) | dropped **before** decode by the `TYPE_QUEUE_STATE` capability gate — never surfaces (fail-closed, defence in depth) |
| Malformed / unrecognized-`state` live-session envelope while a stall is live (#395) | `decodeLiveSessionEvent` → `null` → neither surfaces on `liveSessionEvents` nor **clears** the stall (no trustworthy `conversationId` / unknown forward-progress semantics) — the stall persists until a recognized forward-progress event arrives. Unchanged #385 decode behaviour |
| `resync` on a non-`interactive` connection (#417) | dropped **before** any effect by the `TYPE_RESYNC` capability gate — no cursor reset (a no-op anyway; `latest` is `null` on a non-interactive connection), no `ReplayGap` (fail-closed) |
| `resync` with missing / non-string `conversation_id` (#417) | the cursor **is** reset (unconditional on the type match — process-global); `resyncConversationId` returns `null` so **no** `ReplayGap` is surfaced; the collector survives (`resyncConversationId` cannot throw). A later valid `resync` still surfaces the gap |
| `resync` with a valid `conversation_id` (#417) | cursor reset (next reconnect omits `last_event_id`) **and** `LiveSessionEvent.ReplayGap(id)` emitted on `liveSessionEvents`; nothing logged |
| Stubbed method called | `UnsupportedOperationException` naming the owning follow-up |

**Why catch-and-drop:** the `ConversationRepository` flow type has no error channel and the Fake never
errors, so dropping is the only interface-consistent option. An uncaught decode throw would kill the
**single** inbound consumer, silently freezing **all** future conversation updates for the connection — a
severe failure against an untrusted (post-auth) server payload. The #316 mapper validates shape; the
repository keeps the consumer alive. Pre-`Open` send loss is **not** defended here (no buffering /
retry-on-`Open`): the [coordinator](relay-repository-coordinator.md) publishes the repository through
an Open-gated `currentRepository` and builds a fresh chain per reconnect. The facade only subscribes
after that connection's pump is ready; see the [coordinator's Open gate](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493).

### Conversation session errors (#1677)

`observeSessionError(conversationId)` exposes the latest nullable wire code, including
unknown and empty string codes. The `session_error` arm runs only with negotiated
`interactive`; without it the frame has no effect. `SessionErrorPayloadDto` requires
`conversation_id`, `code` and `message` to be JSON strings. Missing fields, null,
numbers, booleans, arrays and objects drop the frame without changing held state or
terminating inbound collection. Additional fields are ignored. Only id/code leave
the decode boundary: daemon prose is discarded and never retained, rendered or
logged. Diagnostics contain only static event/outcome strings, never raw codes,
ids, payloads or exception messages.

The clearing edges are deliberately narrower than stall's forward-progress rule:

- Either `sendMessage` overload clears that conversation at entry, before delegation
  or awaiting acknowledgement, including attachment sends and sends that fail.
- A successfully decoded, recognized non-idle `turn_state` (`thinking` or
  `responding`) clears its conversation. Idle, unknown or malformed turn states,
  other live frames (including `turn_end` and `session_transition`), and sends or
  turn states for other conversations leave the held code intact.
- Inbound completion or connection-scope cancellation resets all errors. A fresh
  connection starts empty; the frame has no replay event id. No error triggers an
  automatic request, retry or message resend.

Send completion does not clear again or restore an earlier code. An error received
while a send awaits acknowledgement therefore survives that acknowledgement.
Required turn-state routing/state fields are also checked with `JsonPrimitive.isString`
before DTO decoding: a numeric routing id must never clear the error for its string
representation. A Kotlin serialization `String` declaration alone does not enforce
this boundary; see the [payload decoding guidance](mobile-protocol-v2-wire-layer-application-payloads.md#the-model-list-retention-791).

The [repository contract](conversation-repository.md) defaults to `flowOf(null)`,
keeping fakes and test doubles compatible. The [stable facade](stable-conversation-repository.md#cold-reads--flatmaplatest-switch-with-an-empty-fallback)
and [caching decorator](caching-conversation-repository.md#contract) forward the
observation without retaining it. [#1678](https://github.com/pyrycode/pyrycode-mobile/issues/1678)
renders the held code as an [inert thread Error pill](thread-top-overlay.md#the-session-error-pill-1678)
and eagerly closes the local send window before publication. `session.blocked` means
queued delivery was abandoned; `session.child_crashing` retains the queued message
while the child restarts. Clearing at send entry removes the pill even if that send
fails; non-idle progress or reconnect also removes it. Idle and unrelated traffic
leave it visible. An acknowledgement alone neither removes the pill nor reopens
Sending/Waiting. #1677 supplies the data contract; #1678 adds its presentation.
The wire source of truth remains the daemon's `docs/protocol-mobile.md` and
`internal/protocol/messaging.go` (`SessionErrorPayload`).

`RemoteConversationRepositorySessionErrorTest` exercises malformed-frame survival,
current-value subscription, isolation, both send paths, clear/non-clear edges,
pending-ack races, teardown, stable switching with and without held readings, cache
delegation without disk writes, and the default fake observation. Keep the numeric
routing-id and pending-ack cases: ordinary valid-frame tests cannot catch either trap.

The debug phase observation records only the decoded enum, on both legacy and
supplied `thread` + `interactive` admission paths (#2338). Peer receipt cannot prove
phone admission when their negotiated capabilities differ. Keep content and idle
non-clearing assertions alongside malformed-state and later-error cases; a reply
rendering successfully does not prove that non-idle progress reached the phone.
See [the publisher probe and integrated recovery evidence](../../e2e-interactive-stream.md#publisher-probe-and-integrated-linux-diagnostics-2338)
for the causal Core repair, bounded private trace and counted Linux results.

## Hand-off — the live binding

The downstream DI / connection-coordinator work, and where it landed:

1. ✅ **Make the concrete pump conform** — `NoiseSessionPump : ManagedSessionPump : SessionPump` (the four
   members already matched; `override` added). Landed in [#351](../codebase/351.md).
2. ✅ **Provide the connection-scoped `CoroutineScope`** the repository's inbound collector runs on — the
   [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) builds a fresh child scope per live
   connection and constructs the repository against it. Landed in [#351](../codebase/351.md).
3. ✅ **Flag-gate the Koin binding `ConversationRepository`** between `FakeConversationRepository` and the
   live-backed facade. Landed in [#350](../codebase/350.md) as the `conversationRepositoryModule` selector
   (`if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()`), gated by
   the build-time `BuildConfig.USE_RELAY_REPOSITORY` flag. Since #631 the Gradle property
   `useRelayRepository` defaults to `true`, making the stable facade the normal app binding;
   `-PuseRelayRepository=false` selects the fake demo. Ordinary instrumentation without relay arguments
   explicitly selects fake in either build mode. See [dependency injection](dependency-injection.md).
   Connection establishment remains owned by the lifecycle driver, supervisor and coordinator;
   the build flag only selects the repository injected into UI consumers.

The live binding relies on the coordinator's [Open gate](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493)
to prevent the initial list request from being lost during handshaking (#421/#493).

Open hand-off items: **`conversation_created`** stays correlated-reply-only by design — the daemon never
broadcasts a create, so there is no unsolicited case to merge (the *correlated* half landed with #347); the
matching `conversation_updated` item is **closed**, since [#721](remote-conversation-repository-reads-and-thread-store.md#721-apply-workspace-label-updates-and-the-conversation_updated-split)
now folds an unsolicited `conversation_updated` into the live projection by id instead of waiting for the
next snapshot. **`isSleeping`/session enrichment** in the list remains open (arrives via the detail/message
read paths, not here).

The earlier `sendMessage` hand-offs are implemented:

- **ViewModel relay failures:** `sendMessage`, `createDiscussion` and `promote` call sites use
  [guarded repository calls](guarded-repo-launch.md), which catch relay/not-connected failures and
  preserve cancellation. These existing guards remain in place when real becomes the default.
- **Connection loss during an awaited reply:** the inbound collector's `finally` runs
  `RelayRequests.failAllPending`, completing registered requests with `IllegalStateException` and removing them
  (#488). Callers can handle connection loss without waiting for their own scope to end.
