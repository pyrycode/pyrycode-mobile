# #825 — Answer or refuse clarification question batches

## Files read

- `../pyrycode/docs/protocol-mobile.md` § Question (v2) → `question_answer`, `question_refused` — the wire contract (fields, provenance, contract bounds, the vendor `response` field not sent). Cited, not restated.
- `../pyrycode/internal/protocol/testdata/question_answer.json`, `question_refused.json` — the payload shapes the encode tests compare against.
- `pyrycode-desktop/src/renderer/src/screens/conversation/questionResolution.ts` → `answerQuestionBatch`, `refuseQuestionBatch`; `src/main/daemonConnection.ts` → `answerQuestions`, `refuseQuestions` — desktop mints a random token and clears optimistically; mobile does neither (AC3, AC4).
- `app/src/main/java/de/pyryco/mobile/data/model/QuestionBatch.kt` → `QuestionBatch`, `withShown`, `withDismissed` — #822's model; the answer-entry type goes beside it.
- `app/src/main/java/de/pyryco/mobile/data/network/QuestionPayloads.kt` → the inbound DTOs; the two outbound DTOs go beside them.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `interrupt` / `interruptRequest` (the fire-and-forget `check(pump.send(...))` shape to copy), `answerModal` / `answerToken` (the deterministic-token precedent, and the path **not** reused), `mutableQuestionBatches` (the per-connection held state validated against), companion `TYPE_*` constants.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `answerModal`, `cancelModal`, `interrupt` (the null-guard passthrough shape), `activeConnection`, `teardownActive`, `questionBatches`.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` → `SessionPump.send` returns `Boolean` — the "connection cannot send" signal.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryQuestionTest.kt` → `FakeSessionPump`, `shown` — the harness the repository cases extend.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` → `openInteractiveConnection`, `questionShownEnvelope`, `questionBatches_areHeldPerHost`, `FakeManagedPump` — the harness the coordinator cases extend.
- `docs/specs/architecture/822-question-batch-hold.md` § Security review — the never-log-the-nonce posture this ticket keeps.

## Design source

N/A — data layer only; no UI. The panel that calls these sends is #661.

## Context

#822 holds question batches per host and connection; nothing answers them. The daemon resolves a batch from `question_answer` or `question_refused`, and replies to neither: an unknown/resolved batch, a malformed answer and an ungranted device are all dropped silently, and the only resolution signal is the `question_dismissed` #822 already folds. So mobile validates what it can before sending, reports a failed send to the caller, and never treats a sent frame as a resolution.

## Design

### Model — `data/model/QuestionBatch.kt`

- `data class QuestionAnswer(val questionIndex: Int, val values: List<String>)` — one operator selection for the question at `questionIndex` of the held batch. `values` are operator-authored (free text or a copied label), sent verbatim, never compared with the offered labels.

### Wire — `data/network/QuestionPayloads.kt`

`@Serializable internal` outbound DTOs matching the daemon fixtures field-for-field:

- `QuestionAnswerEntryDto(@SerialName("question_index") questionIndex: Int, values: List<String>)`
- `QuestionAnswerPayloadDto(question_batch_id, answer_token, answers: List<QuestionAnswerEntryDto>)`
- `QuestionRefusedPayloadDto(question_batch_id, answer_token)`

No `response` field, no `conversation_id`. Nothing is shared with the modal DTOs.

### Repository — `RemoteConversationRepository`

- Companion `TYPE_QUESTION_ANSWER = "question_answer"`, `TYPE_QUESTION_REFUSED = "question_refused"`.
- `suspend fun answerQuestionBatch(questionBatchId: String, answers: List<QuestionAnswer>)`
  1. Looks up `questionBatchId` in this connection's `mutableQuestionBatches.value`; absent → `IllegalStateException` (never shown, dismissed, or dropped by the reconnect that built this empty repository).
  2. `require` the answers are non-empty and cover the held batch exactly once: `answers.size == questions.size` and the index set equals `0 until questions.size` → otherwise `IllegalArgumentException`.
  3. Builds one envelope with entries sorted by `questionIndex` (batch order), `values` untouched, and `check(pump.send(...))` → `IllegalStateException` when the pump refuses.
- `suspend fun refuseQuestionBatch(questionBatchId: String)` — step 1, then one `question_refused` envelope via `check(pump.send(...))`.
- `private fun questionToken(verb: String, questionBatchId: String): String` — `"$verb:$questionBatchId"` with `verb` ∈ {`answer`, `refuse`}. Pure: a retry of the same send reuses the token; answer and refusal of one batch differ by prefix; different batches differ by suffix. It carries no value and no claude-authored text — the batch id is the daemon-minted nonce. The daemon's actual dedup is its one-shot consume of the batch id.
- Neither method touches `mutableQuestionBatches`: the batch stays held until `question_dismissed` or a reconnect retires it. Neither awaits a reply.

All exception messages are static: no batch id, no value, no label.

### Coordinator — `RelayRepositoryCoordinator`

- `suspend fun answerQuestionBatch(questionBatchId: String, answers: List<QuestionAnswer>)` and `suspend fun refuseQuestionBatch(questionBatchId: String)` beside `interrupt`: null-guard on `activeConnection.value?.repo` (→ `IllegalStateException`), then delegate.

Validation happens in the connection's repository, against the same state `questionBatches` projects, rather than against the coordinator's `stateIn` copy, which trails the repository by a dispatch. A batch folded a moment ago is answerable and one dismissed a moment ago is not, with no lag window. Host isolation is structural: each host has its own coordinator, connection and repository, so a send for host A can only reach A's pump.

## State + concurrency model

No new coroutines, flows or state. Both sends are non-suspending bodies on the caller's coroutine (declared `suspend` for parity with the siblings #661 will wire). The held list is read once per send (`StateFlow.value`); a `question_dismissed` landing between that read and `pump.send` means the frame goes out for a resolved batch, which the daemon drops — the same outcome as a dismiss crossing the frame on the wire, and not a local state corruption since nothing is written.

## Error handling

| Case | Result |
|---|---|
| No active connection | `IllegalStateException` (coordinator) |
| Batch not held on this connection | `IllegalStateException`, nothing sent |
| Empty answers / count mismatch / out-of-range or duplicate index | `IllegalArgumentException`, nothing sent |
| `pump.send` returns false | `IllegalStateException` |

No case mutates held state or logs.

## Testing strategy

Unit tests only; no UI, and the operator-facing flow (and its rung-3 scenario) belongs to #661, which wires these sends to a panel.

- **`RemoteConversationRepositoryQuestionTest`** (extended; its `FakeSessionPump` gains a `sent` list and an `accepts` switch):
  - answer sends one `question_answer` whose payload equals the expected JSON: batch id, token, entries in batch order when given out of order, free-text and multi-value `values` verbatim, no `response` key.
  - refuse sends one `question_refused` with exactly `question_batch_id` and `answer_token`.
  - unknown batch, dismissed batch → both sends fail, nothing sent.
  - empty answers, too few, too many, duplicate index, negative and too-large index → fail, nothing sent.
  - pump refuses → the send fails.
  - the batch stays held after a successful send and after each failure.
  - tokens: repeated answer → same token; answer vs refusal → different; two batches → different; token contains no value or label text.
- **`RelayRepositoryCoordinatorTest`** (added cases):
  - with equal batch ids on hosts A and B, answering and refusing on A put frames on A's pump only.
  - a batch from the prior connection fails after reconnect with nothing sent.
  - no active connection → `IllegalStateException`.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the question-batch section of the coordinator / repository overview should gain the two sends, the no-ack / no-clear contract, and the deterministic token.

## Open questions

- Should a single-select question reject more than one value? No: the ticket says values are sent as given and nothing is checked against the batch beyond coverage; the daemon's resolver does not reject it either.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — two outbound boundaries, `answerQuestionBatch` and `refuseQuestionBatch`. The only claude-authored strings in reach are the held batch's `question`/`header`/`label`/`description`; neither method reads them — validation reads `questions.size` only — and no label becomes a key, token or message. Operator-authored `values` go into the `values` array and nowhere else. The correlation is the batch id alone (no `conversation_id`), per the protocol, so the phone never asserts a conversation.
- [Trust boundaries] No findings — never granting a permission from an answer: the sends touch no modal path, no `answerModal` option-id mapping, and no permission state.
- [Tokens] No findings — `answer_token` is an idempotency key whose secrecy does not matter (protocol § `question_answer`); a deterministic `questionToken` meets the stability/uniqueness AC and carries only the verb and the daemon's nonce. It is never logged. No `SecureRandom` is needed because nothing about the token is a secret.
- [File / storage] Not applicable — nothing is persisted.
- [Inter-process] Not applicable — no intent, deep link, push or WebView change.
- [Crypto] Not applicable — frames go through the existing Noise pump's `send`.
- [Network & I/O] No findings — per-frame work is bounded by the held batch (answer count must equal its 1–4 questions). A value's length is not bounded here; the protocol publishes no bound and the transport's encrypted-frame cap is the byte limit. A frame the pump refuses fails to the caller.
- [Error messages, logs] SHOULD FIX (Phase B) — every `require`/`check` message must be a static string: `kotlinx`/stdlib messages built by interpolation would put the batch nonce or an operator value into an exception a caller might log. No log line is added.
- [Concurrency] No findings — no new scope; the dismiss-vs-send race writes nothing locally and the daemon drops the late frame. Host isolation holds because the send reads the coordinator's own `activeConnection`, never a shared registry.
- [Threat model] No findings — threat 1 (claude-authored text) is kept off the inbound leg by the positional design; the stale-batch threat across reconnects is closed because a new connection's repository starts empty, so an answer for a batch not re-asserted by the reconcile fails before sending.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
