# #914 — Move request plumbing and conversation commands out of the repository

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the source of the move: `requestId`, `pendingRequests`, `sendAndAwaitReply`, `failAllPending`, `mapError`; `createDiscussion`, `promote`, `rename`, `archive`, `unarchive`, `sendArchiveToggle`, `delete`, `removeConversation`, `startNewSession`, `newSessionFrame`, `interrupt`, `interruptRequest`, `registerPushToken`, `answerModal`, `answerToken`, `cancelModal`. The re-pointed sites: the `init` collector's `finally`; the `onInbound` arms `conversation_updated`, `workspace_updated`, the `ack` family and `error`; the `modelMenuProjection` and `questionBatchProjection` lambdas; every remaining `requestId.incrementAndGet()` (debug bundle, list, recent workspaces, history, settings, backfill, send, upload, snapshot, dequeue, set settings, system prompt, change workspace, workspace folder, rename workspace); `archiveWorkspace`, which calls `archive`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`, `ModelMenuProjection.kt`, `QuestionBatchProjection.kt` → the #913 pattern this follows: `internal class`, one instance per repository, `send: (Envelope) -> Boolean` and `nextRequestId: () -> Long` constructor suppliers, companion constants imported as `RemoteConversationRepository.Companion.TYPE_…`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` → `remove`, the thread half of `removeConversation`.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` → `send` is non-suspending, so it passes as a plain function.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → the only production caller of `registerPushToken` / `answerModal` / `cancelModal` / `interrupt`; unchanged, but one comment names the old call chain.
- `docs/specs/architecture/913-list-model-menu-question-projections.md` → the previous move in this family; this plan follows its shape.

In-flight check: no remote `feature/*` branch touches `data/repository/`.

## Design source

N/A: data-layer refactor with no UI.

## Context

`RemoteConversationRepository` still carries the request↔reply plumbing and the conversation commands built on it. This ticket moves the plumbing into `RelayRequests` and the commands onto it in `ConversationCommands`, so a ticket adding a command edits neither the routing nor the counter. It is a move: behaviour, names, KDoc and ordering stay. No ADR is needed.

## Design

Both classes are `internal`, in `data/repository/`, constructed once in the repository's property list, so their state stays connection-scoped (one repository per connection, #351). The repository keeps `onInbound`, every `interactive` gate, `mutationsSupported`, and every companion constant.

### `RelayRequests` (`RelayRequests.kt`)

Constructor: `RelayRequests(send: (Envelope) -> Boolean)`; the repository passes `pump::send`.

| Member | Visibility | Replaces |
|---|---|---|
| `requestId` | private | moved unchanged |
| `pendingRequests` | private | moved unchanged, KDoc moves |
| `nextRequestId(): Long` | public | every `requestId.incrementAndGet()` in the repository and both projection lambdas |
| `waiter(inReplyTo: Long?): CompletableDeferred<JsonElement>?` | public | every `envelope.inReplyTo?.let { id -> pendingRequests[id] }` lookup in `onInbound` |
| `sendAndAwaitReply(request): JsonElement` | public `suspend` | moved unchanged; `pump.send` → `send` |
| `failAllPending()` | public | moved unchanged |
| `mapError(payload): Throwable` | public | moved unchanged; the `error` arm calls it |

`waiter` returns the entry itself rather than completing it, so each arm keeps its exact old logic: `conversation_updated` still tests for a waiter before deciding to fold, `workspace_updated` still fails a waiter on a malformed frame and completes it only after the apply, and a duplicate reply that finds an already-completed waiter is still a no-op rather than a fold. The `ack`-family arm becomes `relayRequests.waiter(envelope.inReplyTo)?.complete(envelope.payload)`; the `error` arm becomes `relayRequests.waiter(id)?.completeExceptionally(relayRequests.mapError(envelope.payload))` beside the unchanged `modelMenuProjection.applyRefusal`. The collector's `finally` keeps its order: `endDebugBundle()`, `endAttachmentUploads()`, then `relayRequests.failAllPending()`.

### `ConversationCommands` (`ConversationCommands.kt`)

Constructor: `ConversationCommands(requests: RelayRequests, send: (Envelope) -> Boolean, conversationList: ConversationListProjection, threadProjection: ThreadProjection, deviceName: String)`.

- Public: `createDiscussion`, `promote`, `rename`, `archive`, `unarchive`, `delete`, `startNewSession`, `interrupt`, `registerPushToken`, `answerModal` (keeps `alwaysAllow: Boolean = false`), `cancelModal`. All `suspend`, as today.
- Private: `sendArchiveToggle`, `newSessionFrame`, `interruptRequest`, `answerToken`, `removeConversation`.

Bodies move unchanged apart from `requestId.incrementAndGet()` → `requests.nextRequestId()`, `sendAndAwaitReply` → `requests.sendAndAwaitReply`, `pump.send` → `send`, and `conversationListProjection` → `conversationList`. `interrupt` and `startNewSession` still `check(send(…))` with the same messages and await nothing. `removeConversation` still clears both `conversationList` and `threadProjection`, on a confirmed or already-gone delete only.

### Repository wiring and hand-offs

`relayRequests` and `conversationCommands` replace `requestId` at its declaration point, after `conversationListProjection` and `threadProjection` and before `pendingRequests`' old spot (which is deleted). `ModelMenuProjection` and `QuestionBatchProjection` stay declared above them, so they keep reading the counter through a lambda, `{ relayRequests.nextRequestId() }`, evaluated only at send time; their KDoc names `relayRequests` instead of `requestId`.

Each moved public function stays as a one-line hand-off with a one-line KDoc naming its owner (e.g. `override suspend fun rename(conversationId: String, name: String): Conversation = conversationCommands.rename(conversationId, name)`), in its current position. The full KDoc moves to `ConversationCommands`. `archiveWorkspace` still calls the repository's `archive`.

KDoc links: links in the repository and its companion to a moved *private* member (`sendAndAwaitReply`, `mapError`, `pendingRequests`, `failAllPending`, `requestId`, `removeConversation`, `answerToken`) now name the new owner. Links to a moved *public* command (`[rename]`, `[promote]`, …) still resolve to the hand-off and stay. Code comments outside the repository that name the repository's `pendingRequests`, `sendAndAwaitReply`, `mapError` or `removeConversation` (`ModelMenuProjection`, `ThreadProjection`, `ConversationListProjection`, `RelayRepositoryCoordinator`) are re-pointed at `RelayRequests` / `ConversationCommands`, comment-only.

## State + concurrency model

Unchanged. `requestId` is still one `AtomicLong` per repository and `pendingRequests` one `ConcurrentHashMap`; neither class has a scope or launches anything. Waiters are completed only on the single inbound collector (through `waiter`) or swept in its `finally`; every state write in the commands still follows a confirmed reply on the caller's coroutine. Every envelope id — the projections' asks, the fire-and-forget frames and the remaining repository requests included — still comes from the one counter, so `ModelMenuProjection`'s ask ledger and `pendingRequests` stay disjoint.

## Error handling

Unchanged. `mapError` never throws and falls back to `ERROR_MALFORMED_REPLY`; `sendAndAwaitReply` registers before sending, throws the not-connected `IllegalStateException`, and deregisters in `finally`; `failAllPending` fails with `PENDING_REQUEST_TORN_DOWN` and logs nothing. `delete`'s `catch (IllegalArgumentException)` stays scoped to the await alone. Nothing new logs.

## Testing strategy

The existing tests reach every moved path through the public surface: `RemoteConversationRepositoryTest` (create, promote, rename, archive, delete incl. already-gone, new session, interrupt, push token, modal answer / cancel, error mapping, teardown sweep, the disjoint-ledger test), `RelayRepositoryCoordinatorTest` and `StableConversationRepositoryTest` through the facade. No assertion changes; a green `testDebugUnitTest --tests "de.pyryco.mobile.data.repository.*"` is the proof, then `lint` and `assembleDebug`. No new test class: neither new class exposes behaviour the repository tests do not already reach.

## Documentation handoff

Pending for the documentation stage: the feature overviews that describe the request plumbing or these commands name `RelayRequests` and `ConversationCommands` as their owners, where each describes them — at least `remote-conversation-repository-conversation-writes.md` and `remote-conversation-repository-control-sends.md`, and also `remote-conversation-repository-send-create-promote-rename.md`, `remote-conversation-repository-state-errors-and-handoff.md` and `remote-conversation-repository-workspace-and-push.md` where they describe `sendAndAwaitReply`, `pendingRequests`, `failAllPending`, `mapError` or a moved command.

## Open questions

- None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Each reply is still decoded once, caller-side, after `RelayRequests.sendAndAwaitReply` returns: `ConversationResponseDto` in `createDiscussion` / `promote` / `rename` / `sendArchiveToggle`, `ConversationDeletedPayloadDto` in `delete`, `ErrorPayload` in `mapError`. A malformed reply still throws on the caller's deferred and never on the collector. `delete` still removes the id it sent, never the id the reply echoes, so a lying relay cannot redirect the removal. `waiter` hands back the pending deferred rather than a completing helper precisely so the `conversation_updated` arm's fold-only-when-uncorrelated rule and the `workspace_updated` arm's apply-then-complete order cannot drift in the move.
- [Tokens, secrets, credentials] No findings. `registerPushToken` still sends the FCM token and the constructor `deviceName` and logs neither. `answerToken` moves unchanged: a deterministic idempotency key over `(modalId, optionId)`, never a secret. No key material is touched.
- [File / storage] No findings. All moved state is in memory (`AtomicLong`, `ConcurrentHashMap`), connection-scoped as before. Nothing is persisted.
- [Inter-process / Android surface] No findings. No manifest, intent, push-handler, deep-link or WebView change; both classes are `internal` with no Android import, keeping `data/` portable.
- [Cryptographic primitives] No findings. No crypto or randomness is touched; `requestId` is a correlation counter, not a nonce.
- [Network & I/O] No findings. The transport is untouched. There is still exactly one counter per connection, now inside `RelayRequests`; the projections read it through a lambda evaluated at send time, so declaration order cannot capture an uninitialised instance, and a request id can never be issued twice or shared between `pendingRequests` and the model-list ask ledger — the `error` arm cannot consume another request's reply. `interrupt` / `startNewSession` stay fire-and-forget and register no waiter.
- [Logs] No findings. Neither new class logs. `failAllPending`'s message stays the static `PENDING_REQUEST_TORN_DOWN`; `mapError` never logs the payload; the not-connected `check` messages carry only the wire type.
- [Concurrency] No findings. No new coroutine or scope. The teardown sweep still runs last in the collector's `finally`, after the debug-bundle and upload endings, and is non-suspending so it completes inside a cancelling coroutine. Register-before-send and deregister-in-`finally` move verbatim, so there is no lost-reply race and no leaked waiter on caller cancellation.
- [Threat model] No findings. A hostile relay can still only drop, delay or reorder; a dropped reply is released by the teardown sweep, as before. A hostile daemon's malformed error or reply is still decoded defensively. Behaviour is unchanged, so no new threat is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
