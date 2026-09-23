# Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## State & concurrency model

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
- **Two-or-more writers per projection, still data-safe (#346 / #347 / #348 / #721).** The mutations
  relaxed each projection from single-writer to **collector + confirmed fold(s)**: `sendMessage` (#346) is
  the second writer of `lastMessages` and `threadByConversation`; `createDiscussion` (#347) and `promote`
  ([#348](../codebase/348.md)) both write the list `projection` via `upsertConversation`. [#721](remote-conversation-repository-reads-and-thread-store.md#721-apply-workspace-label-updates-and-the-conversation_updated-split)
  adds two more writers **inside the collector itself**: an unsolicited `conversation_updated` reuses
  `upsertConversation`, and `workspace_updated` writes through the new `applyWorkspaceLabel` fold. Data-safety
  holds in every case: each write goes through an atomic `MutableStateFlow.update {}` (CAS) over a **pure**
  fold (`recordLastMessage`'s strictly-greater rule / `appendMessages`'s id-dedup / `upsertConversation`'s
  id-upsert / `applyWorkspaceLabel`'s cwd-keyed relabel), so concurrent writes from the caller coroutines
  retry-merge rather than clobber. For `projection` the collector's full-replace stays authoritative and
  convergent; the only race — a stale in-flight snapshot landing after a fold and transiently
  dropping/reverting the row — is harmless (the server's post-mutation snapshots include it) and is
  accepted under Evidence-Based Fix Selection. `promote` additionally **reads** `projection.value` (a
  lock-free snapshot) to resolve the cwd; the read-then-upsert pair is intentionally **not**
  atomic-as-a-pair — the resolved cwd is request data, not a guarded invariant, so a concurrent snapshot
  landing between only changes which authoritative cwd the request carries (benign — no TOCTOU of
  consequence). `#721`'s unsolicited-`conversation_updated` arm reads `RelayRequests.waiter(id)` after a caller
  may have already removed its own entry in `finally`; a duplicate reply arriving in that window is treated
  as unsolicited and folds as an idempotent re-upsert of the same record by the same id — benign by
  `ConversationListProjection.upsertConversation`'s dedup, not a new race. The KDoc on every affected field was updated to name its
  writers (and, for `projection`, `promote`'s read).
- **The `pendingRequests` registry (#346), since #914 owned by `RelayRequests`** (`data/repository/RelayRequests.kt`;
  `ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>`)
  tracks awaited replies: an in-flight mutation request registers a deferred keyed by its
  envelope id (minted by `RelayRequests.nextRequestId`), the collector looks the waiter up through
  `RelayRequests.waiter` and completes it on the correlated `ack`/`error`, and the awaiting caller (through
  `RelayRequests.sendAndAwaitReply`) removes its own entry in a `finally`. Bounded by caller concurrency (one
  entry per in-flight send, removed on success/error/cancellation). On collector termination,
  `RelayRequests.failAllPending` also fails and removes registered requests so connection loss does not
  strand an awaiting caller (#488). The repository still owns `onInbound`: it looks up a waiter through
  `RelayRequests.waiter` and decides how to complete it, so the `conversation_updated` fold-only-when-uncorrelated
  rule and the `workspace_updated` apply-then-complete order live in the repository, not in `RelayRequests`.
- **`RelayRequests.waiter` hands back the pending `CompletableDeferred` itself, not a `complete(id, payload):
  Boolean` helper.** A `complete`-style helper looked tidier when #914 moved this plumbing, but
  `Deferred.complete` returns `false` for an already-completed waiter with no other signal — the
  `conversation_updated` arm would then have folded a **duplicate** correlated reply into the list (no
  existing test catches a duplicate reply, since the daemon does not normally send one), a behaviour change
  the move must not introduce. Returning the waiter lets each `onInbound` arm keep its own completion
  decision — `conversation_updated` tests for a waiter before deciding to fold, `workspace_updated` fails a
  waiter on a malformed frame and completes it only after the apply — exactly as before the move.
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
| `pump.inbound` completes (teardown) | collector completes; last projections retained; live `StateFlow` collectors simply stop receiving updates (do not complete) |
| Unknown `Envelope.type` | no-op — `backfill_done` (informational) falls to the intentional `else`; `messages` (a never-defined type) stays ignored. `conversation_created` has no unsolicited half (the daemon never broadcasts a create), so an unmatched one is a harmless no-op in its own correlated-only arm |
| Malformed `conversation_updated` payload, **unsolicited** (no `in_reply_to`, or matching no pending request, #721) | decode via `ConversationResponseDto.toConversation()` throws `IllegalArgumentException` (⊃ `SerializationException`) before any fold; envelope **dropped**; collector survives; `projection` unchanged. A *correlated* `conversation_updated` (matches a pending request) is **not** decoded by the collector at all — the payload is handed verbatim to the waiter, which decodes in the caller's coroutine, so it can never throw inside this collector |
| Malformed `workspace_updated` payload — missing/wrong-typed `path` (#721) | `WorkspaceUpdatedPayloadDto` decode throws `IllegalArgumentException` (⊃ `SerializationException`), caught before `applyWorkspaceLabel` runs; envelope **dropped**; collector survives; `projection` unchanged; a later valid `workspace_updated` still applies. Neither `path` nor `label` is logged on this or any other branch |
| `workspace_updated` / unsolicited `conversation_updated` whose `path` / `id` matches no row (#721) | no-op by `StateFlow` conflation (the fold returns an element-equal list) — not an error; nothing re-emits |
| `sendMessage` — server `error` `conversation.not_found` (#346) | `IllegalArgumentException` (fake parity); **no projection mutated** (the confirmed-insert runs only after a successful `ack`) |
| `sendMessage` — any other server `error` (#346) | `RelayErrorException(code, retryable, message)` — structured for ViewModel branching; no projection mutated |
| `sendMessage` — `pump.send` returns `false` (not `Open`, #346) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `sendMessage` — malformed/undecodable `error` payload (#346) | `RelayRequests.mapError` falls back to a `RelayErrorException(error.malformed_reply)` so the waiter is unblocked and the lone collector survives; no projection mutated |
| `createDiscussion` (on `ConversationCommands` since #914) — server `error` (#347) | `RelayErrorException(code, retryable, message)` via the shared `RelayRequests.mapError`; **no projection mutated** (the confirmed-insert runs only after a successful decode). `conversation.not_found` is not meaningful for create and is not exercised |
| `createDiscussion` — `pump.send` returns `false` (not `Open`, #347) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `createDiscussion` — malformed `conversation_created` reply (#347) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `ConversationListProjection.upsertConversation`, so **no projection mutated** (a garbage success reply cannot inject a partial conversation) |
| `promote` (on `ConversationCommands` since #914) — server `error` `conversation.not_found` (#348) | `IllegalArgumentException` via the shared `RelayRequests.mapError` — promoting an unknown conversation is **meaningful** here (unlike create), so this branch **is** exercised; **no projection mutated** (the confirmed-upsert runs only after a successful decode) |
| `promote` — any other server `error` (#348) | `RelayErrorException(code, retryable, message)` via `RelayRequests.mapError`; no projection mutated |
| `promote` — `pump.send` returns `false` (not `Open`, #348) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `promote` — malformed `conversation_updated` reply (#348) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `ConversationListProjection.upsertConversation`, so **no projection mutated** (no partial promote) |
| `setSessionSettings` — server `error` `session.not_found` (unhosted session, #543) | `RelayErrorException(code = "session.not_found", …)` via `RelayRequests.mapError`'s else-branch — **not** `IllegalArgumentException` (unlike `conversation.not_found`; there is no IAE-crash path for this verb) |
| `setSessionSettings` — server `error` `protocol.malformed` (invalid model/effort) / `server.binary_offline` (#543) | `RelayErrorException(code, retryable, message)` via `RelayRequests.mapError`; no projection mutated (there is none) |
| `setSessionSettings` — `pump.send` returns `false` (not `Open`, #543) | `IllegalStateException` from `RelayRequests.sendAndAwaitReply`'s `check`; no request awaited |
| `setSessionSettings` — malformed `session_settings_updated` reply (#543) | the `SessionSettingsUpdatedPayloadDto` decode's `SerializationException` (⊂ `IllegalArgumentException`), propagated to the caller; the decode is validation-only (result discarded either way) |
| `requestScreenSnapshot` — server `error` `conversation.not_found` / any other / not-`Open` send / malformed `screen_snapshot` reply (#375) | `IllegalArgumentException` / `RelayErrorException` / `IllegalStateException` respectively via the shared `RelayRequests.mapError` + `RelayRequests.sendAndAwaitReply`'s `check`; a malformed reply throws the #374 `SerializationException` (⊂ `IllegalArgumentException`) **caller-side** after `RelayRequests.sendAndAwaitReply` returns. A pure read — **nothing mutated** on any path; nothing logged |
| `dropQueuedMessage` — not-`Open` send (`pump.send` → `false`, #859) | `IllegalStateException`. The withdrawal of the just-recorded `pendingDrops`/drop entry and the throw are the only outcome — the send is fire-and-forget (`pump.send`, not `RelayRequests.sendAndAwaitReply`) since #859, so there is no server `error` path any more. **The backlog row still updates only via a later `queue_state`** (nothing mutated there on any path); on the throw, `removeOwnEcho` (#781) is never reached, so the sender's own thread echo is left in place too — nothing to roll back, nothing logged. See [Queued backlog § Dropping a queued entry](queued-backlog.md#dropping-a-queued-entry-dequeue_message-466) |
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
