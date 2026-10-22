# Remote conversation repository — live state and modals

## Authoritative content and transient readings

Daemon-built thread items alone supply messages, tools, notices, refusals, session
boundaries, queue/task facts and their order. Raw assistant deltas, turn-end content,
queue snapshots, task started/updated/roster frames and raw session transitions no
longer reconstruct them. `ThreadProjection`, queue matching and background placement
inference are removed. `liveSessionEvents` is no longer a runtime content seam.
See [item/query ownership](remote-conversation-repository-reads-and-thread-store.md).

The existing single inbound collector retains current session state, status,
configuration, tool/task progress, prompts and attachment transport. Progress can
update a supplied task's live reading; it cannot create a task, infer its parent,
settle it or certify history. Canonical effects supply refusal and root-turn outcome
observations. A raw turn-end cannot establish completion or refresh readings.

## Session admission and held settings

`SessionLiveAdmission` checks authenticated host, exact conversation and producing
session before decoding/publication or correlated waiter completion. Authoritative
conversation summaries or an ordered family-clear snapshot reconcile replacement;
a fresh reading, timestamp or request correlation cannot borrow current ownership.
Retired producers remain rejected across reconnect. Family clears retire only their
own readings; replacement also clears prompts, progress and configuration holders.
See [family retirement](remote-conversation-repository-status-projections.md#supplied-session-ownership-and-family-retirement-2084).

Successfully decoded, admitted settings are copied to the existing host-held settings
holder before live publication. This preserves the last valid display while disconnected.
Replacement clears both holders. A reply correlated to an old request still cannot
repopulate either holder from the retired session. An unavailable settings target
may be empty while its producing-session identity remains exact.

Model announcements, session facts and context usage remain readings, separate from
write targets and persisted content. Equal settings replies retain arrival ordinals
so confirmation polling observes them. Missing data stays unavailable; it cannot be
filled from removed raw history or completion events.

## Prompts and modal ownership

Permission/trust modals and question batches remain session-owned live state. They
are not restored from content/cache. Modal events are admitted before publication,
and local answer/cancel actions do not manufacture daemon resolution. Reconnect and
session replacement retire the relevant state. The answer validation and token rules
below apply after admission, using the connection's own held prompt.

## Replay retirement

There is no runtime replay cursor, `last_event_id` hello advertisement, resync fold
or backfill request. Reconnect uses authoritative queries and certified checkpoints.
Independent producer diagnostics in shared tests may observe raw history; those
observations never populate or certify the phone's content.

## `questionBatches` — the v2 clarification-batch decode+fold seam (#822)

A **held `StateFlow<List<QuestionBatch>>`** (`QuestionBatchProjection.mutableQuestionBatches` /
`batches`, exposed on the repository as `questionBatches`, #913), not an event
stream — the deliberate difference from
[`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385) and
[`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437) above. Decodes the two v2
**binary → phone** clarification-batch envelopes — `question_shown` (claude's whole `AskUserQuestion`
call in one frame) and `question_dismissed` (its retirement) — into `QuestionBatch`
(`data/model/QuestionBatch.kt`) via the DTOs and `toBatch()` in the new `data/network/QuestionPayloads.kt`
(kept out of `InteractivePayloads.kt`, which #810 also touches). Not a modal: a batch carries its own
`conversation_id` and several can be outstanding at once, so none of this touches `ModalEvent`,
`ModalUiState` or `currentModal`.

- **Rides the same single existing `pump.inbound` collector.** A new grouped
  `TYPE_QUESTION_SHOWN, TYPE_QUESTION_DISMISSED` arm joins the `onInbound` `when (envelope.type)` demux,
  gated identically on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the reused #385 supplier). It
  calls a private `foldQuestionFrame(envelope)` that decodes inside one `try { … } catch
  (IllegalArgumentException) { }` and applies `List<QuestionBatch>.withShown` / `.withDismissed` via
  `mutableQuestionBatches.update {}` — the `decodeModalEvent` drop idiom, but folding straight into held
  state rather than `tryEmit`ting an event. A malformed frame leaves the held list untouched and the
  single collector survives; nothing is logged (the exception can quote the JSON input).
- **Held state, not `replay = 0`, because the daemon's reconcile is a burst, not one event.** Protocol §
  Reconnect / Backfill: on connect the daemon re-sends every outstanding batch for every conversation, in
  no guaranteed order. A `SharedFlow` folded downstream — the `liveSessionEvents`/`modalEvents` shape —
  can lose part of that burst to a late subscriber, the same failure mode [#492](current-modal-state.md)
  fixed for modals by hoisting the fold to the coordinator. The fix here is different: fold directly into
  a `StateFlow` at this seam, so a late reader of `questionBatches` always sees whatever the burst has
  folded so far rather than a partial replay of it. No hoist is needed for this state to survive a late
  subscriber — see the [coordinator](relay-repository-coordinator.md#questionbatches--the-v2-clarification-batch-projection-822)
  for why the state itself still resets on reconnect (the opposite of `currentModal`).
- **The empty-`questions` and repeated-id rules live on the pure fold, not the decode.**
  `QuestionShownPayloadDto.toBatch()` always produces a `QuestionBatch`, even with an empty `questions`
  list; `List<QuestionBatch>.withShown` (`data/model/QuestionBatch.kt`) is what turns an empty batch into
  a no-op (it neither adds nor replaces a held batch) and replaces a held batch sharing a
  `questionBatchId` in place rather than appending a second. `withDismissed` removes by id and is a no-op
  for an unknown or already-removed one. Both rules mirror desktop's `reduceQuestionBatches`.
- **`multi_select` needs an explicit type check, or a quoted `"false"` silently decodes true-typed.**
  [`MobileJson`](mobile-protocol-v2-wire-layer.md) is non-lenient — a JSON number where a `String` is
  expected fails decode — but it still parses a *quoted* `"false"` into a Kotlin `Boolean`, because
  `JsonPrimitive.booleanOrNull` reads the primitive's content regardless of whether it is a JSON string.
  `QuestionDto.multiSelect` is decoded as a raw `JsonPrimitive`; a private `strictBoolean()` rejects a
  string or non-boolean primitive itself, with a static message naming only the key (the
  `readEffectiveEffort` posture — never interpolate the offending value). A decode test that never
  supplies a `"multi_select":"false"` fixture passes just as well with the type check missing, so the
  string-typed case has to be an explicit rejection test, not just the `question_shown_zero.json` numeric
  `false` fixture.
- **On the concrete repo only, like `modalEvents`** — not on `ConversationRepository`. The panel
  ([#661](https://github.com/pyrycode/pyrycode-mobile/issues/661)) and the answer/refusal sends
  ([#825](https://github.com/pyrycode/pyrycode-mobile/issues/825)) reach it through
  [`RelayRepositoryCoordinator.questionBatches`](relay-repository-coordinator.md#questionbatches--the-v2-clarification-batch-projection-822),
  the same concrete-repo-then-coordinator reachability path as `modalEvents`.

`security-sensitive`, but plain orchestration: decode runs behind the authenticated Noise channel, and
`question`, `header`, `label` and `description` are claude-authored and unsanitised — held as inert
fields and never used as a key (the two ids are daemon-asserted and are the only keys), never logged, and
never placed in an exception message.


## The model-list and slash-command-list menu retentions (#791, #792, #882)

Moved to [Remote conversation repository — the model-list and slash-command-list menu retentions](remote-conversation-repository-model-and-slash-command-menus.md) on 2026-09-24 to keep this document under the 50000-byte size cap the docs guard enforces: the `TYPE_MODEL_LIST` inbound arm and its connection-scoped retention (#791), the `request_model_list` on-demand ask (#792), and the `TYPE_SLASH_COMMAND_LIST` inbound arm (#882) it left room for.


## `answerModal` / `cancelModal` — the v2 modal answer/cancel control-send (#438)

The **outbound (phone → binary) half** of the permission modal feature: the two control messages that
answer or cancel a modal the [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437)
seam (#437) surfaced. [#438](../codebase/438.md) implements them as **two concrete suspend methods** that
mirror [`registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359) (#359)
exactly — a pure request/reply over the **reused** `sendAndAwaitReply` (#346) primitive, with no new
correlation infra, no projection mutation, and no interface/facade/Fake touch:

```kotlin
suspend fun answerModal(modalId: String, optionId: String)   // modal_answer{modal_id, option_id, answer_token}
suspend fun cancelModal(modalId: String)                     // modal_cancel{modal_id}
```

Each builds an `Envelope(id = requestId.incrementAndGet(), type = TYPE_MODAL_ANSWER/_CANCEL, ts =
Clock.System.now()…, payload = MobileJson.encodeToJsonElement(dto))` and `sendAndAwaitReply`s it,
**discarding the empty `{}` ack** — success is simply "the call returned without throwing". The
[`ModalAnswerPayloadDto` / `ModalCancelPayloadDto`](mobile-protocol-v2-wire-layer-request-encoders.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346)
encode DTOs (new `data/network/ModalOutboundPayloads.kt`) are the **encode mirror** of #437's decode DTOs.

- **`modalId`/`optionId` are echoed verbatim — never parsed or validated.** They are the opaque tokens
  the caller (#439, via the #437 decode) hands in; the daemon validates `modalId` against its own
  outstanding modal (first-answer-wins; a stale id is rejected) and maps `optionId` against its own
  recorded option list (pyrycode#701/#703/#706). The phone asserts only *which* modal and *which* offered
  option — never a conversation. A client-side check here would be security theatre that could diverge
  from the daemon.
- **`answer_token` — a deterministic, stateless idempotency key (the one real design decision).** Minted
  by a **pure** `private fun answerToken(modalId, optionId) = "${modalId.length}:$modalId:$optionId"` — no
  stored state, no random, no clock. Purity gives both AC#2 properties **by construction**: the same
  `(modalId, optionId)` always yields the same token, so a **resend of one logical answer carries the
  identical token** and the daemon collapses the replay to a no-op (stability with no remembered cache,
  no eviction lifecycle); **distinct answers always yield distinct tokens** (both ids participate — two
  different modals each answered with option id `"allow"` are distinct answers). The modal id is
  **length-prefixed** so opaque ids containing the `:` separator can't alias (`("a:b","c")` → `3:a:b:c`
  vs `("a","b:c")` → `1:a:b:c`). It is **not** authorization — that is `modalId` validity (#706) + the
  per-device answer gate (#702, default OFF); pyrycode#701 makes *secrecy an explicit non-goal*, so the
  predictable derivation is **safe** (a guessed token grants nothing) and *stronger* than a remembered
  UUID for the stability AC. (Rejected: random UUID + a cache keyed by `(modalId, optionId)` — buys
  nothing the pure derivation lacks while adding mutable shared state, thread-safety, and an eviction
  question coupling the slice to the inbound `modal_dismissed` stream.)
- **`modal_cancel` carries no token.** Cancel is not idempotency-keyed (pyrycode#701 shape `{modal_id}`);
  a re-cancel of an already-resolved modal is a stale-`modal_id` reject the daemon handles.
- **Failure surfaces deterministically (AC#4), nothing swallowed.** A not-`Open` session
  (`pump.send` → `false`) throws `IllegalStateException` synchronously (no hang); a server `error` —
  **including the ungranted-device reject** (pyrycode#702/#703) — propagates as
  [`RelayErrorException`](mobile-protocol-v2-wire-layer.md)`(code, retryable, message)`. This slice does
  **not** catch or interpret the reject: switching the modal to read-only on it is **#440's** concern.
  No new error type, no new mapping — inherited from `sendAndAwaitReply`/`mapError` verbatim.
- **`modal_dismissed` is not awaited here.** The `ack` confirms the daemon *received and will process*
  the answer; the modal's eventual *resolution* arrives asynchronously as the inbound `modal_dismissed`
  event on [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437) (#437). Two
  distinct signals — this slice owns only send + ack/error correlation.
- **Concrete-only, like `registerPushToken`/`liveSessionEvents`/`modalEvents`.** Modal answering is a
  device/control capability, not conversation CRUD, so it is **not** on the
  [`ConversationRepository`](conversation-repository.md) interface, the
  [facade](stable-conversation-repository.md), or the Fake (which would force a +2-file plumbing cascade
  for a method they never call). The #439 render consumer reaches these by fetching the concrete
  `RemoteConversationRepository` (the `registerPushToken` reachability story) — downstream consumer-slice
  work, not this slice's. Two `TYPE_MODAL_ANSWER`/`TYPE_MODAL_CANCEL` companion constants join the block.

`security-sensitive` (a high-consequence outbound control — the answer injects a permission decision into
`claude`), verdict **PASS** (architect self-review + code review): the design keeps the daemon the sole
authority (the payload carries only opaque ids + a non-authoritative dedup key), mints nothing
trust-bearing, and **never logs the payload** (the modal may name a sensitive command/path — e.g. "Allow
`rm -rf build/`"), mirroring `registerPushToken`'s never-log-the-token posture. See the
[#438 implementation notes](../codebase/438.md) for the line refs, the test matrix, and the *untested
length-prefix injectivity* lesson.
