# #2214 — Set and clear host-owned channel pins after confirmation

## Files read

- `data/repository/ConversationCommands.kt` — `setMuted` and `sendArchiveToggle`: the round trip to mirror (envelope id from `RelayRequests.nextRequestId`, `sendAndAwaitConversation`).
- `data/repository/RelayRequests.kt` — `sendAndAwaitConversation` (throws `IllegalStateException` when the pump refuses the send, `SerializationException` when the reply carried no decodable row), `mapError` (`conversation.not_found` → `IllegalArgumentException`, every other code → `RelayErrorException`), `failAllPending` (teardown mid-await → `IllegalStateException`).
- `data/repository/RemoteConversationRepository.kt` — the `TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED` arm of `routeInbound`: decodes `ConversationResponseDto`, publishes through `ConversationListProjection.upsertConversation` in arrival order, then `completeConversationReply`. A malformed frame publishes nothing. `setMuted` override and the `TYPE_SET_CONVERSATION_MUTED` constant.
- `data/repository/ConversationRepository.kt` — `setMuted`'s default that throws, kept for inline test doubles.
- `data/repository/StableConversationRepository.kt` — `live` (throws `IllegalStateException(NOT_CONNECTED)` with no live repository) and `setMuted` forwarding.
- `data/repository/FakeConversationRepository.kt` — `setMuted`'s `state.update` flip and `unknown`.
- `data/network/SetConversationMutedPayloadDto.kt` — encode-only payload with a required, default-free boolean.
- `data/network/ConversationResponseDto.kt`, `data/model/Conversation.kt` — `pinnedAt: Instant?` decoded and mapped by #2213.
- Tests: `RemoteConversationRepositoryPinTest` (#2213 fixtures: `row(pin)`, `snapshot`, `update`, `commandNames`/`executeCommand` probes over every conversation command), `RemoteConversationRepositoryMuteTest`, `StableConversationRepositoryTest` (`oneShots_whileAbsent_throwIllegalState`, `RecordingConversationRepository.setMutedCalls`), `FakeConversationRepositoryTest` (`setMuted_*`).
- `../pyrycode/docs/protocol-mobile.md` § `set_conversation_pinned` and the `conversation_updated` row: the contract cited below, not restated.
- Overviews `remote-conversation-repository-conversation-writes.md` (§ `setMuted`) and `conversation-repository-fake-implementation.md`: the boolean must have no Kotlin default so `false` reaches the wire; the write returns nothing and relies on the inbound fold.

Overlap: #1947 also edits `RemoteConversationRepository.kt`; this change there is one constant and one one-line override, additive.

## Context

Core #3538 activated `set_conversation_pinned`; #2213 made mobile decode and retain `pinned_at`. This ticket adds the write so a client can pin or unpin a channel and every device receives the host's confirmed record. No UI controls. No decision record needed: the design is the mute command's shape.

## Design

- New `data/network/SetConversationPinnedPayloadDto` — `@Serializable`, encode-only, `conversation_id: String`, `pinned: Boolean`, both required with no default (the host refuses missing/null `pinned` as `protocol.malformed`).
- `RemoteConversationRepository.TYPE_SET_CONVERSATION_PINNED = "set_conversation_pinned"`.
- `ConversationCommands.setPinned(conversationId, pinned)` — builds one envelope of that type with the DTO and awaits `requests.sendAndAwaitConversation`. Returns `Unit`, like `setMuted`.
- `ConversationRepository.setPinned(conversationId, pinned): Unit` — interface method whose default throws `error(...)`, so unrelated test doubles compile unchanged. KDoc: success means `observeConversations` shows the host's confirmed `pinnedAt`; failure types as `setMuted`.
- `RemoteConversationRepository.setPinned` — one-line hand-off to `conversationCommands.setPinned`.
- `StableConversationRepository.setPinned` — `live.setPinned(conversationId, pinned)`; with no live repository `live` throws `IllegalStateException`.
- `FakeConversationRepository.setPinned` — `state.update`: unknown id throws `unknown(id)` with no change; `pinned = true` keeps an existing `pinnedAt` or stamps `Clock.System.now()`; `pinned = false` sets `null`. No `lastUsedAt` bump. The fake does not imitate `conversation.not_promoted`; the ticket asks only for unknown-id rejection.

Data flow: request → host → correlated `conversation_updated` → the existing update arm decodes and upserts it by payload `id` before waking the caller → the uncorrelated push of the same record takes the same arm and upserts by `id` again, leaving one row and the host's timestamp. The command never writes the projection itself, so a pending request changes nothing and no local timestamp is invented on the remote path.

## State and concurrency model

No new jobs or flows. The request runs in the caller's coroutine; the reply is folded on the repository's single inbound collector in arrival order, which already guarantees a later confirmed record wins over an earlier reply (#2213's `replyAndPushDeliveryIsIdempotentInEitherOrder`). Socket close mid-await is the existing `failAllPending` sweep. The fake's `state.update` is an atomic CAS.

## State transitions and identity reuse

| Event | Test |
|---|---|
| Request pending, no reply yet | `setPinned_pendingRequestLeavesConfirmedStateUntilReplyArrives` |
| Correlated reply then duplicate push (and push first) | `setPinned_replyAndDuplicatePushInEitherOrderKeepOneRowAndHostTimestamp` |
| Repeat pin; host returns the original timestamp | same test, second write |
| Unpin after pin | `setPinned_sendsExactVerbAndExplicitBoolean` |
| Reply queued behind a newer confirmed record | `queuedCommandRepliesCannotOverwriteLaterConfirmedPins` with `setPinned` added to `commandNames` |
| Caller cancelled after reply queued | `cancelledCommandsKeepInboundConfirmedPins` with `setPinned` added |
| Socket closed before send / during await | `setPinned_disconnectedSessionFailsAndKeepsConfirmedRecord` |
| Snapshot or reconnect after a failure | `setPinned_refusalsAndMalformedReplyPreserveConfirmedRecordUntilSnapshot`, `setPinned_disconnectedSessionFailsAndKeepsConfirmedRecord` |
| Same conversation id on two hosts | `setPinned_onOneHostLeavesTheOtherHostsSameIdUntouched` |
| Fake repeat pin | `setPinned_repeatPinKeepsTimestamp_unpinClears` |

## Error handling

All through existing seams; none folds anything:

- Host `error` `protocol.unknown_type` (older host) or `conversation.not_promoted` → `RelayErrorException` with that code.
- `conversation.not_found` → `IllegalArgumentException`.
- Pump not open → `IllegalStateException` before awaiting; teardown during the await → `IllegalStateException` from `failAllPending`.
- Malformed reply (undecodable row, bad `pinned_at`) → the arm logs the existing content-free `event=conversation_read_update outcome=malformed`, publishes nothing, and the caller gets `SerializationException` (an `IllegalArgumentException`).

The command adds no log line of its own; the inbound arm already logs the malformed case without payload content.

## Testing strategy

Unit tests only (pure data layer, `runTest`, the fake pump):

- `RemoteConversationRepositoryPinTest` (extend; reuses #2213's fixtures): add `setPinned` to `commandNames`/`executeCommand` so the queued, malformed, and cancelled probes cover it; give `FakeSessionPump` an `open` flag. New tests named in the table above, including `setPinned_refusalsAndMalformedReplyPreserveConfirmedRecordUntilSnapshot` iterating `protocol.unknown_type`, `conversation.not_promoted`, `conversation.not_found` and a malformed reply, asserting the held row (pin, mute, archive stamp, label, agent) is unchanged.
- `StableConversationRepositoryTest`: `setPinned` in `oneShots_whileAbsent_throwIllegalState`; `setPinned_whenLive_delegatesVerbatim` with a `setPinnedCalls` recorder.
- `FakeConversationRepositoryTest`: pin stamps a time, repeat keeps it, unpin clears, unknown id throws and changes nothing.

No screen or device test: no UI.

## Invariants (trial:invariant-probes)

- *Only a decoded host record changes remote pin state* → pending test, malformed probe, queued-reply probe.
- *Reply and broadcast upsert one row; repeat pin uses the host timestamp* → reply/push either-order test, fake repeat test.
- *Failures leave the confirmed record; a reconnect snapshot refreshes it* → refusals/malformed test, disconnected test, snapshot refresh assertion.

## Open Questions

- None blocking. Whether `setPinned` should validate that the reply's id and pin state match the request (as `routeReadMarkReply` does for read marks): not adopted, to keep the mute contract the ticket names; the folded record is the host's confirmed record either way.

## Revisions

- 2026-10-17: Test naming only, no contract change. The unpin case folded into `setPinned_sendsExactVerbAndExplicitBoolean` (it asserts the explicit-null reply is adopted), and the refusal and snapshot-refresh cases share `setPinned_refusalsAndMalformedReplyPreserveConfirmedRecordUntilSnapshot`. The table above names the tests as landed.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only inbound data is the host's `conversation_updated`, crossing at one place: the `TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED` arm of `RemoteConversationRepository.routeInbound`, which decodes through `ConversationResponseDto` (strict `Instant` parse for `pinned_at`, #2213) and publishes nothing on failure. A hostile host could answer the pin request with another conversation's record; that record is upserted exactly as an uncorrelated push of it would be, so it grants nothing a push could not, and the command's success never stands in for state — consumers read `pinnedAt` from `observeConversations`, never from the call. No daemon-authored text reaches Compose in this ticket.
- [Tokens] No findings. The command carries a conversation id and a boolean; no credential is created, read or sent beyond the existing Noise session.
- [Files and storage] No findings. No new storage; the confirmed `pinnedAt` reaches disk only through #2213's existing `FileConversationCache` mirror under app-private storage with its atomic write.
- [Android surface] No findings. No component, intent, deep link, push payload or WebView is added; the command has no UI caller in this slice.
- [Cryptography] No findings. The frame rides the existing `NoiseSessionPump.send` encrypt-then-enqueue path; nothing touches keys, nonces or the handshake.
- [Network and I/O] OUT OF SCOPE. A relay that silently drops only the reply leaves the caller suspended until the caller's scope is cancelled or the socket's teardown sweep (`RelayRequests.failAllPending`) runs — the same posture as `setMuted` and every other awaited command. No state is mutated meanwhile. Bounding the wait (or cancelling on screen exit) belongs to the first UI caller, #2215.
- [Errors and logs] SHOULD FIX. The command must add no log line that carries the conversation id, the requested boolean or the reply's `pinned_at`; the malformed case is already logged by the inbound arm as the static `event=conversation_read_update outcome=malformed`. `RelayErrorException` messages come from the host's static error text and are not logged here. The verifier checks that `ConversationCommands.setPinned` adds no `RelayLog` call with dynamic content.
- [Concurrency] No findings. No new coroutine or scope: the request runs in the caller's coroutine, the fold runs on the single inbound collector in arrival order, so a stale reply cannot overwrite a later confirmed record (covered by the queued-reply probe). The fake mutates through `MutableStateFlow.update` only; its `Clock.System.now()` read inside the CAS lambda is harmless on retry because only the winning value is stored.
- [Threat model] Malicious relay: can drop or delay the request or reply (handled as above: nothing folds, caller fails at teardown) but cannot forge or replay a record inside the Noise session. Hostile daemon frame: decoded defensively, malformed frames dropped without partial mutation (`malformedCommandRepliesFailWithoutPublishing` extended to `setPinned`). Token theft and UI leakage: not touched by this slice.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17
