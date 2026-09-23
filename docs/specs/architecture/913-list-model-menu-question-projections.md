# #913 — Move the conversation list, model menus and question batches into projections

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the whole move comes from here: `projection`, `lastMessages`, `upsertConversation`, `applyWorkspaceLabel`, `recordLastMessage`, `updateCurrentSessionId`, `removeConversation`, `project`, `observeConversations`, `observeLastMessage`; `modelMenusByConversation`, `askedModelMenus`, `modelListAsks`, `decodeModelList`, `askForModelMenu`, `onModelListRefusal`, `observeModelMenu`; `mutableQuestionBatches`, `questionBatches`, `foldQuestionFrame`, `answerQuestionBatch`, `refuseQuestionBatch`, `heldQuestionBatch`, `sendQuestionFrame`, `questionToken`. Callers: the `onInbound` arms (`conversations`, `message`, `conversation_updated`, `workspace_updated`, `error`, `model_list`, `session_transition`, `question_*`), `createDiscussion`, `promote`, `sendMessage`, `sendArchiveToggle`, `delete`, `rename`, `setSystemPrompt`, `changeWorkspace`, `archiveWorkspace`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`, `QueueProjection.kt` → the pattern: `internal class`, one instance per repository, private state, `apply` / `observe` / `current`, decoders that catch `IllegalArgumentException` and log nothing.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt` → how a projection names repository companion constants (`RemoteConversationRepository.TYPE_…`) and how the repository republishes a projection's `StateFlow` (`backgroundTasks`).
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` → `send(envelope): Boolean` is non-suspending, so a projection can take it as a plain function.
- `docs/specs/architecture/912-thread-projection.md` → the previous move in this family; this plan follows its shape and its rule that a frame decodes in the projection only when the projection is its sole consumer.
- `docs/knowledge/features/remote-conversation-repository.md` § "Status projections: one file per status event" → the documented pattern.

No in-flight feature branch touches `RemoteConversationRepository.kt` or the three new files.

## Design source

N/A: data-layer refactor with no UI.

## Context

`RemoteConversationRepository` is still 3115 lines after #912. Three self-contained clusters of state remain in it: the conversation list with its last-message previews, the model menus with their one-shot ask, and the clarification batches. This ticket moves each into its own `internal` class in `data/repository/`, the #912 / #819 shape. It is a move: behaviour, names and KDoc stay, and KDoc links to a member that moved now name its new owner. No ADR is needed.

## Design

Each class is constructed once in the repository's property list, next to `threadProjection`, so its state stays connection-scoped (one repository per connection, #351). The repository keeps every `interactive` gate, the routing in `onInbound`, every public function, and every companion constant; the new classes name those constants as `RemoteConversationRepository.TYPE_…`.

### `ConversationListProjection` (`ConversationListProjection.kt`)

State (private, KDoc moves): `projection`, `lastMessages`.

| Member | Visibility | Replaces |
|---|---|---|
| `applySnapshot(envelope)` | public | the `conversations` arm's decode-or-drop and full replace; the arm becomes a one-line hand-off (still ungated) |
| `upsertConversation(conversation)` | public | moved unchanged |
| `applyWorkspaceLabel(path, label)` | public | moved unchanged |
| `recordLastMessage(conversationId, message)` | public | moved unchanged |
| `updateCurrentSessionId(conversationId, newSessionId)` | public | moved unchanged |
| `remove(conversationId)` | public | the list and last-message lines of `removeConversation` |
| `observe(filter): Flow<List<Conversation>>` | public | `projection.filterNotNull().map { project(it, filter) }` |
| `observeLastMessage(conversationId): Flow<Message?>` | public | the body of the repository's `observeLastMessage` |
| `current(): List<Conversation>` | public | the one-time snapshot `projection.value.orEmpty()` read by `promote` and `archiveWorkspace` |
| `project` | private | moved unchanged |

Stays in the repository: the `message` decode (it feeds the thread too), the `conversation_updated` waiter check and decode (it is the same `ConversationResponseDto` decode the mutations use), the `workspace_updated` decode (it also completes or fails a waiter), and `removeConversation`, which calls `conversationListProjection.remove` and `threadProjection.remove`. `observeConversations` still sends `list_conversations` before emitting `conversationListProjection.observe(filter)`. `promote`'s cwd resolution becomes `current().firstOrNull { … }?.cwd ?: ""`, which equals the old `projection.value?.firstOrNull { … }?.cwd ?: ""` for a `null` projection too.

### `ModelMenuProjection` (`ModelMenuProjection.kt`)

Constructor: `ModelMenuProjection(send: (Envelope) -> Boolean, negotiatedCapabilities: () -> Set<String>, nextRequestId: () -> Long)`. The repository passes `pump::send`, its own `negotiatedCapabilities` supplier, and `{ requestId.incrementAndGet() }`. That last one must be a lambda, not the bound reference `requestId::incrementAndGet`: `requestId` is declared below the projection fields, and a bound reference would capture it before it is initialised.

State (private, KDoc moves): `modelMenusByConversation`, `askedModelMenus`, `modelListAsks`.

| Member | Visibility | Replaces |
|---|---|---|
| `apply(envelope)` | public | the `model_list` arm body: consume the `inReplyTo` ask entry, then `decodeModelList` and replace the conversation's entry |
| `applyRefusal(inReplyTo: Long, payload)` | public | the `error` arm's `modelListAsks.remove(id)?.let { onModelListRefusal(…) }` |
| `observe(conversationId): Flow<ModelMenu?>` | public | the body of `observeModelMenu`, including `onStart { askForModelMenu(…) }` |
| `askForModelMenu`, `onModelListRefusal`, `decodeModelList` | private | moved unchanged, apart from `requestId.incrementAndGet()` → `nextRequestId()` and `pump.send` → `send` |

The long `model_list` arm comment moves into `apply`, as #912 moved arm comments. The `error` arm keeps `pendingRequests[id]?.completeExceptionally(mapError(…))` beside the hand-off, and its comment on the two disjoint maps stays.

### `QuestionBatchProjection` (`QuestionBatchProjection.kt`)

Constructor: `QuestionBatchProjection(send: (Envelope) -> Boolean, nextRequestId: () -> Long)`, wired the same way.

State (private, KDoc moves): `mutableQuestionBatches`, exposed as `batches: StateFlow<List<QuestionBatch>>`. The repository's public `questionBatches` becomes `= questionBatchProjection.batches`, the `backgroundTasks` shape.

| Member | Visibility | Replaces |
|---|---|---|
| `apply(envelope)` | public | `foldQuestionFrame`, moved unchanged |
| `answer(questionBatchId, answers)` | public | the body of `answerQuestionBatch` |
| `refuse(questionBatchId)` | public | the body of `refuseQuestionBatch` |
| `heldQuestionBatch`, `sendQuestionFrame`, `questionToken` | private | moved unchanged, apart from the send and id suppliers |

`answer` and `refuse` are not `suspend`: nothing in them suspends. The repository's `suspend fun answerQuestionBatch` / `refuseQuestionBatch` keep their signatures and KDoc and delegate. `answerToken` stays in the repository, since it belongs to `answerModal`.

## State + concurrency model

Unchanged. Every write is the same atomic `MutableStateFlow.update`, `ConcurrentHashMap` or concurrent-set operation on the same state. Inbound writes still run on the repository's single inbound collector, because no new class has a scope or launches a coroutine. Caller-coroutine writes (`upsertConversation` from the mutations, `recordLastMessage` from `sendMessage`, `askForModelMenu` from a subscribing collector) take the same atomic paths as before. Every envelope id, the model-list ask and the question sends included, still comes from the repository's one `requestId`, so `modelListAsks` and `pendingRequests` stay disjoint.

## Error handling

Unchanged. `applySnapshot`, `decodeModelList`, `onModelListRefusal` and the question fold each catch `IllegalArgumentException` and drop the frame or return, so a malformed payload leaves the state intact and the collector alive. `askForModelMenu` still swallows a throwing send as `false`. `sendQuestionFrame` still throws `IllegalStateException` with a static message when the pump refuses, and `heldQuestionBatch` still throws with a static message. No new class logs anything or forwards a caught exception.

## Testing strategy

The existing repository tests cover every moved path through the public surface: `RemoteConversationRepositoryTest` (list snapshots, upserts, workspace labels, session-id fold, last message, delete, model menus and their asks and refusals), `RemoteConversationRepositoryQuestionTest` (question fold, answer, refuse), and `RelayRepositoryCoordinatorTest` / `StableConversationRepositoryTest` through the facade. The move changes no assertion, and a green run of the `de.pyryco.mobile.data.repository` test package is the proof. No new test class is added: none of the three classes exposes behaviour the repository tests do not already reach. Then `lint` and `assembleDebug`.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` should name `ConversationListProjection` as the owner of the list projection and the last-message previews, where it describes them.
- `docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md` should name `ModelMenuProjection` as the owner of the model menus and their ask ledger, and `QuestionBatchProjection` as the owner of the clarification batches, where it describes them.

## Open questions

- None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Each frame still becomes a typed value at exactly one decoder. `conversations` moves to `ConversationListProjection.applySnapshot`, `model_list` stays in `decodeModelList` (now in `ModelMenuProjection`), the refusal code read stays in `onModelListRefusal`, and the question frames stay in the question fold (now `QuestionBatchProjection.apply`). Each is still one `try` / `catch (IllegalArgumentException)`, and no DTO escapes a class: callers pass an `Envelope` in and the state holds only `Conversation`, `Message`, `ModelMenu` and `QuestionBatch`. Decodes that feed more than one owner (`message`, `conversation_updated`, `workspace_updated`) stay in the repository, so nothing is decoded twice. The #791 routing rule survives the move: `apply` consumes the `inReplyTo` ask entry and discards it, and the menu still lands under the payload's own `conversation_id`, so an answer naming B cannot be stored under A. Render paths are unchanged; claude-authored menu rows and question text still reach Compose only as text.
- [Trust boundaries — capability gate] No findings. The `model_list` and `question_*` hand-offs stay inside the repository's `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` arms. `askForModelMenu`'s own guard reads the same `negotiatedCapabilities` supplier, passed into `ModelMenuProjection`'s constructor, so it still fails closed on an unwired repository (`{ emptySet() }`). The `conversations` arm stays ungated, as before.
- [Tokens, secrets, credentials] No findings. `questionToken` moves unchanged; it carries the verb and the daemon-minted batch id and no answer value, and it was never a secret. `answerToken` stays in the repository. No key or pairing material is touched.
- [File / storage] No findings. All moved state is in-memory `MutableStateFlow`s, a `ConcurrentHashMap` and a concurrent key set, connection-scoped as before. Nothing is persisted.
- [Inter-process / Android surface] No findings. No manifest, intent, deep link, WebView or push change. The three classes are `internal` with no Android import.
- [Cryptographic primitives] No findings. No crypto or randomness is touched.
- [Network & I/O] No findings. The transport is untouched. Every envelope id, the model-list ask and the question sends included, still comes from the repository's one `requestId`, through the `nextRequestId` supplier, so `modelListAsks` and `pendingRequests` stay disjoint and the `error` arm cannot consume the wrong reply. The supplier must be a lambda over `requestId`, not a bound reference evaluated before `requestId` is initialised; the plan's Design section says so. `observeConversations` still sends `list_conversations` before reading the projection.
- [Logs] No findings. No new class has a `Log`, `Timber`, `RelayLog` or `println` call. The static exception messages in `heldQuestionBatch` and `sendQuestionFrame` move unchanged and quote no nonce or value. Caught decode exceptions are still discarded, never forwarded.
- [Concurrency] No findings. No new class owns a scope or launches anything, so inbound writes stay on the single collector. The projections are initialised in the property list before `init` starts that collector, and `questionBatchProjection` is declared before the public `questionBatches` that reads it. The one-shot ask keeps its atomic `askedModelMenus.add` test-and-set, the register-before-send ordering, and the rollback of both entries on a refused send. The deliberately non-atomic "menu already retained" read before the test-and-set is carried over as documented.
- [Threat model] No findings. A hostile daemon frame is still decoded defensively and dropped on failure; a hostile relay can still only drop, delay or reorder. Behaviour is unchanged, so no new threat is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
