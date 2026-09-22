# #822 — Hold clarification question batches per host and conversation

## Files read

- `../pyrycode/docs/protocol-mobile.md` § Question (v2) (`question_shown`, `question_dismissed`) and § Reconnect / Backfill semantics — the wire contract; cited, not restated. Daemon `d278c264`.
- `../pyrycode/internal/protocol/testdata/question_shown.json`, `question_shown_empty.json`, `question_shown_zero.json`, `question_dismissed.json` — copied verbatim into the payload tests.
- `pyrycode-desktop/src/main/transport/inboundMessage.ts` → `parseQuestionShownPayload`, `parseQuestion`, `parseQuestionOption`, `parseQuestionDismissedPayload` — the strict decode to mirror: every field required and typed, `multi_select` type-checked (a quoted `"false"` is rejected), unknown keys tolerated, `source`/`outcome` not closed to a set.
- `pyrycode-desktop/src/renderer/src/store/questionBatches.ts` → `reduceQuestionBatches`, `selectBatchFor` — the fold: empty `questions` is a no-op, a repeated id replaces in place, dismiss removes by id, reconnect clears; the per-conversation read picks the **first** held batch for the conversation.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the `TYPE_MODAL_SHOWN, TYPE_MODAL_DISMISSED` arm and its `interactive` gate), `decodeModalEvent` (the decode-or-null idiom), `modalEvents`, companion `TYPE_MODAL_*` constants — where the new arm, decoder, state and constants sit.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentModal`, `currentRepository`, `activeConnection`, `onConnection`, `teardownActive` — the eager `flatMapLatest` + `stateIn` shape the new seam copies, and the teardown order that nulls the connection before a new one is published.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ModalShownPayloadDto`, `ModalDismissedPayloadDto` — the DTO posture (required non-null fields, verbatim strings). Not edited (#810 touches it).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson` — `ignoreUnknownKeys = true`, non-lenient: a non-string for a `String` field fails, but a quoted boolean decodes as a `Boolean`, so `multi_select` needs its own type check.
- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `readEffectiveEffort` — precedent for an explicit `JsonPrimitive.isString` check with a static, value-free exception message.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` → `RelayConnectionBundle.coordinator` — one coordinator per host bundle, which is what makes the fold per host.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` → `currentModal_accumulatesModalShownBeforeAnySubscriber`, `newEnv`, `FakeManagedPump` — the coordinator test harness the new cases reuse.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `FakeSessionPump` — the repo-level pump fake shape the new test class copies (the class is private there).
- `docs/knowledge/features/current-modal-state.md` — the #492 lesson: a `replay = 0` event flow folded downstream loses events fired before subscription; the fold must be eager.

## Design source

N/A — data layer only; no UI. The panel is #661.

## Context

`question_shown` / `question_dismissed` have no consumer on mobile. The panel (#661) and the answer/refusal sends (#825) need the outstanding batches held per host and readable per conversation. Questions are not modals — they carry a `conversation_id`, option identity is a label, and several can be outstanding — so nothing goes through `ModalEvent`, `ModalUiState` or `currentModal`.

Unlike `currentModal`, question state must **reset** on reconnect: the daemon's connect-time reconcile re-sends every outstanding batch, and a batch resolved while mobile was away is simply absent from it. Folding into state per connection (not into an event stream folded downstream) means the reconcile burst is never lost to a late subscriber and the reset falls out structurally.

## Design

### Model — new `data/model/QuestionBatch.kt`

Portable (no Android types):

- `data class QuestionOption(val label: String, val description: String)`
- `data class Question(val question: String, val header: String, val options: List<QuestionOption>, val multiSelect: Boolean)`
- `data class QuestionBatch(val conversationId: String, val questionBatchId: String, val questions: List<Question>)`
- Pure fold helpers on the held list (insertion-ordered `List<QuestionBatch>`):
  - `internal fun List<QuestionBatch>.withShown(batch: QuestionBatch): List<QuestionBatch>` — empty `questions` → `this` unchanged (does not add, does not replace); a held batch with the same `questionBatchId` → replaced at its index; otherwise appended.
  - `internal fun List<QuestionBatch>.withDismissed(questionBatchId: String): List<QuestionBatch>` — removes the matching batch; unknown id → `this` unchanged.
  - `internal fun List<QuestionBatch>.batchFor(conversationId: String): QuestionBatch?` — the first held batch for that conversation (desktop's `selectBatchFor` rule; the two-batch case is out of contract).

Batch ids and conversation ids are daemon-asserted and are the only keys. Claude-authored strings are held verbatim and never used as a key.

### Wire — new `data/network/QuestionPayloads.kt`

`@Serializable internal` DTOs mirroring the protocol tables: `QuestionOptionDto(label, description)`, `QuestionDto(question, header, options, @SerialName("multi_select") multiSelect: JsonPrimitive)`, `QuestionShownPayloadDto(conversation_id, question_batch_id, questions)`, `QuestionDismissedPayloadDto(question_batch_id, outcome, source)`. Every field is required and non-null.

- `internal fun QuestionShownPayloadDto.toBatch(): QuestionBatch` — total field copy preserving array order. `multiSelect` is read with an explicit check: a JSON string or `null` throws a `SerializationException` with a static message naming the key only (the `readEffectiveEffort` posture), because `MobileJson` would otherwise accept `"false"`.
- Dismiss decode needs no mapper: the repo reads `questionBatchId` off the DTO; `outcome` and `source` are decoded (strictness) and otherwise ignored.

### Repository — `RemoteConversationRepository`

- Companion constants `TYPE_QUESTION_SHOWN = "question_shown"`, `TYPE_QUESTION_DISMISSED = "question_dismissed"`.
- `private val mutableQuestionBatches = MutableStateFlow<List<QuestionBatch>>(emptyList())`; public `val questionBatches: StateFlow<List<QuestionBatch>>` on the concrete class only (the `modalEvents` posture — not on `ConversationRepository`).
- New `onInbound` arm `TYPE_QUESTION_SHOWN, TYPE_QUESTION_DISMISSED` beside the modal arm, behind the same `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` gate. It calls a private `foldQuestionFrame(envelope)` that decodes inside one `try`/`catch (IllegalArgumentException)` and applies `withShown` / `withDismissed` via `update`. A decode failure changes nothing. No log.

### Coordinator — `RelayRepositoryCoordinator`

- `val questionBatches: StateFlow<List<QuestionBatch>>` = `activeConnection.flatMapLatest { it?.repo?.questionBatches ?: flowOf(emptyList()) }.stateIn(scope, SharingStarted.Eagerly, emptyList())`.
- `fun observeQuestionBatch(conversationId: String): Flow<QuestionBatch?>` = `questionBatches.map { it.batchFor(conversationId) }.distinctUntilChanged()`.

`teardownActive` already nulls `activeConnection` before a new `Connection` is published, and each connection builds a fresh repository whose state starts empty, so old-connection batches can never be seen alongside new-connection frames. Nothing else in the coordinator changes.

## State + concurrency model

- The fold runs on the repository's single inbound collector (`scope.launch { pump.inbound.collect … }`), so writes are serial; `MutableStateFlow.update` is used anyway.
- The coordinator's projection is started `Eagerly` on the coordinator scope (the #492 reason) and switched per connection by `flatMapLatest`; the prior connection's inner collection is cancelled when its `Connection` is replaced. Cancelled by `close()`.
- `observeQuestionBatch` is cold over the hot `StateFlow`.

## Error handling

A malformed frame (missing/wrong-typed field, quoted or null `multi_select`, `null` array) is dropped: no held batch changes, the collector survives, neighbouring frames still fold. A frame on a non-`interactive` connection is ignored before decode. An unknown dismiss id is a no-op. Nothing is logged — the four claude-authored strings and the batch nonce stay out of logs and exception messages.

## Testing strategy

Unit tests only (no UI, no operator-facing flow yet, so no rung-3 scenario — the panel #661 owns that).

- **`data/network/QuestionPayloadsTest`** (new): the four daemon fixtures copied verbatim. `question_shown.json` → batch with both questions, options and `multi_select` in wire order; `question_shown_empty.json` → decodes with empty `questions`; `question_shown_zero.json` → empty strings and `false` survive; `question_dismissed.json` decodes with `source` `timeout`. Rejections: missing `question_batch_id`, numeric `header`, `"multi_select":"false"`, `"multi_select":null`, `"questions":null`, missing option `description`.
- **`data/repository/RemoteConversationRepositoryQuestionTest`** (new class, own `FakeSessionPump` copy): shown holds a batch; empty batch holds nothing and does not replace a held batch with the same id; repeated id replaces in place; two batches for different conversations both held; dismiss removes exactly once for known `source`/`outcome` and for unrecognised ones; unknown / repeated dismiss changes nothing; malformed frame holds nothing, leaves an existing batch, and a following valid frame still folds; non-`interactive` connection holds nothing.
- **`RelayRepositoryCoordinatorTest`** (added cases): batch held with no subscriber; `observeQuestionBatch` returns only its conversation's batch; replacing the connection drops old batches and a reconciled re-send is held once under its id; a batch not re-sent after reconnect stays gone; two coordinators (hosts A and B) with equal ids — only the host that received the frame shows it.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: a feature overview for the question-batch hold (or a section in `relay-repository-coordinator.md` / `remote-conversation-repository-live-stream-and-modals.md`) describing the reset-on-reconnect contrast with `currentModal`.

## Open questions

- Is a per-conversation `Flow` the right read shape for #661, or a snapshot getter? Resolved for now as `observeQuestionBatch(conversationId): Flow<QuestionBatch?>` plus the whole-host `questionBatches` `StateFlow`; #661 may add a ViewModel adapter.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one boundary: `foldQuestionFrame` decodes through the `QuestionPayloads.kt` DTOs and `toBatch`, and only typed `QuestionBatch` values leave it. The two ids are daemon-asserted and are the only keys (`withShown`/`withDismissed` match on `questionBatchId`, `batchFor` on `conversationId`). `question`, `header`, `label` and `description` are claude-authored; they are held verbatim as inert fields, never keyed, compared, logged or parsed. No render surface lands here, so the length bound and text-only rendering are owed by #661 at the Compose boundary.
- [Trust boundaries] SHOULD FIX (Phase B, doc comment) — `questionBatches` on the coordinator is the whole host's set across conversations. The conversation filter is `observeQuestionBatch`; the KDoc must say a per-conversation surface reads through it, so #661 cannot render one conversation's question in another thread.
- [Tokens] No findings — `question_batch_id` is an answer nonce, never logged and never placed in an exception message. The strict `multi_select` check throws a static message naming the key only.
- [File / storage] Not applicable — batches are in-memory `StateFlow` state per connection; nothing is persisted, so nothing survives process death or backup.
- [Inter-process] Not applicable — no intent, deep link, push or WebView change.
- [Crypto] Not applicable — no primitive touched; frames arrive already Noise-decrypted through the existing pump.
- [Network & I/O] OUT OF SCOPE — a hostile authenticated daemon can send many `question_shown` frames with distinct ids and grow the per-connection list until reconnect. Each frame is already size-bounded by the transport, the list is dropped with the connection, and the thread store's inbound folds have the same exposure. A count cap would need a contract number the protocol does not publish and would risk dropping legitimate reconcile batches. Not observed; defer until it is.
- [Error messages, logs] No findings — no log lines are added; a caught decode exception is discarded, never logged or rethrown (kotlinx messages can quote JSON input).
- [Concurrency] No findings — writes happen only on the repository's single inbound collector via `update`; the coordinator projection is `Eagerly` on the coordinator scope and cancelled by `close()`. `teardownActive` nulls `activeConnection` before a new connection is published and each connection has a fresh repository, so a batch from the old connection can never sit beside the new connection's frames, and a batch resolved while disconnected cannot be answered from stale state (#825 also resolves server-side exactly once).
- [Threat model] No findings — protocol threat 1 (prompt injection onto a render surface) is carried forward as inert data to #661; hosts are isolated by the per-host `RelayRepositoryCoordinator`, tested with equal ids on two coordinators.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
