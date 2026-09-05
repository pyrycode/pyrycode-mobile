# Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## State & concurrency model

- **Five `StateFlow` projections — `projection` (the conversation list, #312), `lastMessages` (#329's
  per-conversation most-recent `Message`), `threadByConversation` (#313's per-conversation ordered
  thread), `stalledConversations` (#395's per-conversation stall `Set<String>`), and `queuedByConversation`
  (#460's per-conversation queued backlog `Map<String, List<QueuedMessage>>`) — fed by one inbound
  collector** launched on the injected connection `scope`. No second collector or scope is added per
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
- **Two-or-more writers per projection, still data-safe (#346 / #347 / #348).** The mutations relaxed
  each projection from single-writer to **collector + confirmed fold(s)**: `sendMessage` (#346) is the
  second writer of `lastMessages` and `threadByConversation`; `createDiscussion` (#347) and `promote`
  ([#348](../codebase/348.md)) both write the list `projection` via `upsertConversation`. Data-safety
  holds in every case: each write goes through an atomic `MutableStateFlow.update {}` (CAS) over a **pure**
  fold (`recordLastMessage`'s strictly-greater rule / `appendMessages`'s id-dedup / `upsertConversation`'s
  id-upsert), so concurrent writes from the caller coroutines retry-merge rather than clobber. For
  `projection` the collector's full-replace stays authoritative and convergent; the only race — a stale
  in-flight snapshot landing after the fold and transiently dropping/reverting the row — is harmless (the
  server's post-mutation snapshots include it) and is accepted under Evidence-Based Fix Selection.
  `promote` additionally **reads** `projection.value` (a lock-free snapshot) to resolve the cwd; the
  read-then-upsert pair is intentionally **not** atomic-as-a-pair — the resolved cwd is request data, not
  a guarded invariant, so a concurrent snapshot landing between only changes which authoritative cwd the
  request carries (benign — no TOCTOU of consequence). The KDoc on every affected field was updated to
  name its writers (and, for `projection`, `promote`'s read).
- **The `pendingRequests` registry (#346)** (`ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>`)
  is the only other shared mutable state: an in-flight mutation request registers a deferred keyed by its
  envelope id, the collector completes it on the correlated `ack`/`error`, and the awaiting caller
  removes its own entry in a `finally`. Bounded by caller concurrency (one entry per in-flight send,
  removed on success/error/cancellation) — no unbounded growth. The one documented gap: a
  connection-drop mid-await leaves a single stranded entry until the *caller* is cancelled (no timeout
  added; see [Hand-off](#hand-off--the-live-binding)).
- **Dispatcher inherited from the injected scope** (DI uses `Dispatchers.Default`; this is pure CPU/JSON
  work — the socket I/O is the transport's, below the pump). Not hard-coded.
- `observeConversations`, `observeLastMessage`, and `observeMessages` are cold; N concurrent collectors
  share the projections (fan-out off the single inbound consumer).

## Error handling

| Failure mode | Result |
|---|---|
| Malformed `conversations` payload | `IllegalArgumentException` caught per-envelope (covers both #316 families — `MissingFieldException` ⊂ `SerializationException`, and the kotlinx-datetime bad-timestamp throw); envelope **dropped**; collector survives; projection unchanged |
| Malformed `message` payload (missing field / unmappable role e.g. `system` / bad `ts`) | `IllegalArgumentException` caught per-envelope (covers the #317 `SerializationException` decode failure and the `Instant.parse(ts)` throw); envelope **dropped silently** (no payload logged — content may be sensitive); collector survives; `lastMessages` **and** `threadByConversation` unchanged ([#329](../codebase/329.md) / [#313](../codebase/313.md)) |
| Malformed `message_chunk` (any one row bad) | the **whole chunk** dropped in one `catch (IllegalArgumentException)` (decode + map-all under one `try`); collector survives; thread unchanged ([#313](../codebase/313.md)) |
| `pump.send` returns `false` (session not `Open`) | request (`list_conversations` or `backfill_since`) silently not sent (no throw); the projection stays empty until a later subscribe succeeds or a push arrives — the live stream still fills the thread, and the next subscribe re-issues |
| `pump.inbound` completes (teardown) | collector completes; last projections retained; live `StateFlow` collectors simply stop receiving updates (do not complete) |
| Unknown `Envelope.type` | no-op — `backfill_done` (informational) falls to the intentional `else`; `messages` (a never-defined type) stays ignored. **Unsolicited** single-row deltas (a `conversation_created`/`conversation_updated` with no `inReplyTo` match) are caught by the success arm and no-op there. A *correlated* `conversation_created` / `conversation_updated` is **not** a no-op — it routes through the success arm (#347 / #348) |
| `sendMessage` — server `error` `conversation.not_found` (#346) | `IllegalArgumentException` (fake parity); **no projection mutated** (the confirmed-insert runs only after a successful `ack`) |
| `sendMessage` — any other server `error` (#346) | `RelayErrorException(code, retryable, message)` — structured for ViewModel branching; no projection mutated |
| `sendMessage` — `pump.send` returns `false` (not `Open`, #346) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `sendMessage` — malformed/undecodable `error` payload (#346) | `mapError` falls back to a `RelayErrorException(error.malformed_reply)` so the waiter is unblocked and the lone collector survives; no projection mutated |
| `createDiscussion` — server `error` (#347) | `RelayErrorException(code, retryable, message)` via the shared `mapError`; **no projection mutated** (the confirmed-insert runs only after a successful decode). `conversation.not_found` is not meaningful for create and is not exercised |
| `createDiscussion` — `pump.send` returns `false` (not `Open`, #347) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `createDiscussion` — malformed `conversation_created` reply (#347) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `upsertConversation`, so **no projection mutated** (a garbage success reply cannot inject a partial conversation) |
| `promote` — server `error` `conversation.not_found` (#348) | `IllegalArgumentException` via the shared `mapError` — promoting an unknown conversation is **meaningful** here (unlike create), so this branch **is** exercised; **no projection mutated** (the confirmed-upsert runs only after a successful decode) |
| `promote` — any other server `error` (#348) | `RelayErrorException(code, retryable, message)` via `mapError`; no projection mutated |
| `promote` — `pump.send` returns `false` (not `Open`, #348) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited, no projection mutated |
| `promote` — malformed `conversation_updated` reply (#348) | the #318 decode boundary's `SerializationException` / `IllegalArgumentException`, propagated to the caller; decode precedes `upsertConversation`, so **no projection mutated** (no partial promote) |
| `setSessionSettings` — server `error` `session.not_found` (unhosted session, #543) | `RelayErrorException(code = "session.not_found", …)` via `mapError`'s else-branch — **not** `IllegalArgumentException` (unlike `conversation.not_found`; there is no IAE-crash path for this verb) |
| `setSessionSettings` — server `error` `protocol.malformed` (invalid model/effort) / `server.binary_offline` (#543) | `RelayErrorException(code, retryable, message)` via `mapError`; no projection mutated (there is none) |
| `setSessionSettings` — `pump.send` returns `false` (not `Open`, #543) | `IllegalStateException` from `sendAndAwaitReply`'s `check`; no request awaited |
| `setSessionSettings` — malformed `session_settings_updated` reply (#543) | the `SessionSettingsUpdatedPayloadDto` decode's `SerializationException` (⊂ `IllegalArgumentException`), propagated to the caller; the decode is validation-only (result discarded either way) |
| `requestScreenSnapshot` — server `error` `conversation.not_found` / any other / not-`Open` send / malformed `screen_snapshot` reply (#375) | `IllegalArgumentException` / `RelayErrorException` / `IllegalStateException` respectively via the shared `mapError` + `sendAndAwaitReply`'s `check`; a malformed reply throws the #374 `SerializationException` (⊂ `IllegalArgumentException`) **caller-side** after `sendAndAwaitReply` returns. A pure read — **nothing mutated** on any path; nothing logged |
| `dropQueuedMessage` — server `error` `conversation.not_found` / any other (a stale / already-drained id, e.g. `queue.stale_id`) / not-`Open` send (#466) | `IllegalArgumentException` / `RelayErrorException(code, retryable)` / `IllegalStateException` respectively via the shared `mapError` + `sendAndAwaitReply`'s `check`. The empty `{}` ack carries nothing to decode and is ignored. A pure send — **no projection mutated** on any path (the backlog updates only via a later `queue_state`); nothing to roll back; nothing logged |
| `interrupt` — not-`Open` send (`pump.send` → `false`, #458) | `IllegalStateException` from the `check` — **no reply awaited** (fire-and-forget, plain `pump.send` not `sendAndAwaitReply`), so it cannot hang. No projection mutated, nothing to roll back, nothing logged. The caller (`ThreadViewModel.sendInterrupt`) swallows it inert. No server-`error` path exists (the daemon sends no reply) |
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
retry-on-`Open`): the [#351 coordinator](relay-repository-coordinator.md) wires the repository against
an `Open` pump and builds a fresh chain per reconnect, so a pre-`Open` re-request stays out of scope here
(a future concern if a lost first request is ever observed).

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
   the build-time `BuildConfig.USE_RELAY_REPOSITORY` flag — **default OFF**, so the bound
   `ConversationRepository` is still the Fake until the production flip. Flipping ON additionally depends
   on the v2 mutations (#346/#347/#348) and the server gaps #336 (boundaries) / #337 (streaming).

Open hand-off items: **pre-`Open` request loss** (if a subscribe's `send` lands before the handshake
completes, the list stays empty until the next subscribe or a server push — the fix, if observed, is a
re-request on `PumpState.Open`; #351 builds a fresh chain per reconnect but adds no re-request);
**unsolicited `conversation_created`/`conversation_updated` delta-merge** (the *correlated*-reply case
landed with #347/#348, but a server-pushed single-row delta with no `inReplyTo` match is still a no-op —
the list refreshes on the next `conversations` snapshot; merging deltas live remains future work);
**`isSleeping`/session enrichment** in the list (arrives via the detail/message read paths, not here).

Two more hand-offs opened by the `sendMessage` slice ([#346](../codebase/346.md)):

- **ViewModel error surface (still a follow-up — *not* #350).** All three live mutations — `sendMessage`
  (#346), `createDiscussion` (#347), and `promote` ([#348](../codebase/348.md)) — now throw
  `RelayErrorException` / `IllegalStateException` (not just `IllegalArgumentException`). The UI call sites
  (e.g. `ThreadViewModel.sendMessage`, `DiscussionListViewModel.confirmPromotion`) are currently
  fire-and-forget with no `try/catch` — harmless under the fake, but once the live remote is bound those
  exceptions would escape uncaught. **[#350](../codebase/350.md) did *not* address this** — it was the
  binding-selector slice only, ships with the flag OFF, and touched no ViewModel. Widening the ViewModel
  error handling (and documenting the widened exception set on the `ConversationRepository` interface
  KDoc) belongs to the flag-ON production flip, which remains a future follow-up.
- **Connection-drop-mid-send leak.** If the connection scope is cancelled while a caller still awaits a
  reply, the deferred never completes and the suspend hangs until the *caller* is cancelled (the
  ViewModel scope on screen exit). No timeout is added (no observed hang; a timeout value is a product
  call). A future slice — or the [#351 connection coordinator](relay-repository-coordinator.md), which
  already cancels the connection scope on drop — may fail all `pendingRequests` on disconnect.
