# #830 — Name uploaded attachments on a sent message

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `SendMessagePayloadDto` — the encode-only `send_message` payload that gains the optional field.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileJson` (via its use) — `encodeDefaults = true`, `explicitNulls = false`: a `null` default is omitted, an `emptyList()` default would add `[]` to every send.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `sendMessage`, `setSessionSettings`, `uploadAttachment` — the two-argument send contract, and the default-throwing member idiom that spares the seventeen test doubles.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `sendMessage` — mints `message_id`, sends through `sendAndAwaitReply`, confirmed-inserts only after the `ack`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `sendMessage`, `live` — one-shots delegate to the repository live at call entry.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt` → `AttachmentUploadLimit` — the published-bound object idiom the new bound mirrors.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryAttachmentTest.kt` — the attachment sibling test class the new remote tests join.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` → `RecordingConversationRepository` — the delegation double.
- `../pyrycode/docs/protocol-mobile.md` § Naming a message's attachments — the wire contract (cited, not restated).

In-flight overlap: `feature/874` adds a compaction-boundary arm to `ConversationRepository.kt` and `RemoteConversationRepository.kt`. Different blocks; a later merge may touch those files.

## Design source

N/A — data-layer ticket with no UI; the composer that calls this is #670.

## Context

The daemon resolves an upload only when a message names it (#829 produced the ids). Mobile has no way to name them: `SendMessagePayloadDto` has no `attachment_ids`, and `sendMessage(conversationId, text)` takes no ids. This ticket adds the wire field and an attachment-bearing send; #670's composer decides which ids belong to a draft, and #672 owns attachment references on `Message`.

## Design

**Wire.** `SendMessagePayloadDto` gains `@SerialName("attachment_ids") val attachmentIds: List<String>? = null`, declared last. `null` is omitted by `MobileJson`, so a text-only send encodes exactly as today.

**Bound.** A new `object MessageAttachmentIds` beside the DTO in `MessagePayload.kt`, the `AttachmentUploadLimit` idiom:

- `const val MAX: Int = 32`
- `fun forSend(ids: List<String>): List<String>?` — drops repeats keeping first occurrence order; throws `IllegalArgumentException` when more than `MAX` remain; returns `null` for an empty list, so "no ids" has one wire form (key omitted).

Counting after the repeat drop matches the AC; since the daemon counts raw elements, sending the deduplicated list keeps both counts equal. Id shape is not validated here: every id comes from this client's own minted upload, and the daemon refuses a malformed one.

**Contract.** `ConversationRepository` gains an overload:

```kotlin
suspend fun sendMessage(conversationId: String, text: String, attachmentIds: List<String>): Message =
    error("sendMessage with attachments is not implemented for this ConversationRepository")
```

The two-argument member is untouched, so no test double changes. KDoc states: ids in caller order, repeats dropped, more than `MessageAttachmentIds.MAX` refused with `IllegalArgumentException` before anything is sent, empty list identical to the two-argument send, failures as the two-argument send.

**Remote.** The existing two-argument `sendMessage` becomes a call to the three-argument override with `emptyList()`. The three-argument override calls `MessageAttachmentIds.forSend` first (so a refusal throws before a request id is taken or an envelope built), then runs today's body with `attachmentIds` on the DTO. Same `sendAndAwaitReply`, same confirmed insert after the `ack`, same `mintedMessageIds` record. The returned `Message` is unchanged (no attachment references, #672).

**Stable.** Overrides the three-argument member as `live.sendMessage(conversationId, text, attachmentIds)`: the repository live at entry, not-connected as `IllegalStateException`, like the two-argument send. The owner-bound per-host facade (#636) is a `StableConversationRepository`, so it reaches its own host.

## State + concurrency model

No new state, job or flow. The send runs on the caller's coroutine as today; cancellation is `sendAndAwaitReply`'s existing path.

## Error handling

- Too many ids: `IllegalArgumentException` from `MessageAttachmentIds.forSend`, before any frame; no projection changes.
- `attachment.not_found` / `protocol.malformed`: arrive as a correlated `error`; `sendAndAwaitReply` throws `RelayErrorException` with that code, as for any server error; the confirmed insert is unreachable.
- Logging: one content-free `RelayLog` line on an attachment-bearing send carrying only the id count (`event=send_message attachments=<n>`), and one on the refusal (`event=send_message outcome=too_many_attachments count=<n>`). Never the ids or text.

## Testing strategy

Unit tests only (no UI, not operator-facing on its own — the rung-3 scenario belongs to #670's composer flow).

- `MessagePayloadTest`: a DTO without ids encodes with no `attachment_ids` key; with ids encodes the array in order. `MessageAttachmentIds.forSend`: repeats dropped in first-seen order; empty → `null`; 32 distinct (with repeats beyond) accepted; 33 distinct throws.
- `RemoteConversationRepositoryAttachmentTest`:
  - send with repeated ids → one `send_message` naming the conversation, `attachment_ids` deduplicated in caller order; on `ack` the thread holds the sent message as a text-only send would.
  - send with `emptyList()` and the two-argument send → no `attachment_ids` key.
  - 33 distinct ids → `IllegalArgumentException`, nothing sent, thread unchanged.
  - correlated `attachment.not_found` error → `RelayErrorException` with that code, thread unchanged.
- `StableConversationRepositoryTest`: the three-argument send delegates with exact arguments and returns the live result; with none live it throws `IllegalStateException`.

## Documentation handoff

None named by the ticket. The feature overview for attachments / the conversation repository may note the new send overload and the 32-id bound — pending for the documentation stage.

## Open questions

- None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. This is an outbound field; nothing daemon-authored enters the phone. The ids a caller passes come from `AttachmentUploadResult.Stored`, which `AttachmentUploadTransfer` builds from its own client-minted id, not from reply text. The daemon is the authority on resolution: it resolves an id only under the message's own conversation and refuses a malformed or unknown one. Mobile deliberately does not re-validate the UUIDv4 shape; a caller that passed a foreign string gets `protocol.malformed` / `attachment.not_found` back through the ordinary error path, and nothing on the phone uses the id as a path, key or URL.
- [Cross-conversation / cross-host naming] No findings. The ids ride the same `send_message` as the `conversation_id` and go through the repository the send already targets (`StableConversationRepository.sendMessage` → `live` at entry). An id minted under another conversation or host fails to resolve daemon-side; the phone does not widen the scope.
- [Tokens, secrets, credentials] No findings. Attachment ids are identifiers, not bearer secrets (resolution is scoped by the authenticated Noise session and conversation). They are minted with `UUID.randomUUID` (`SecureRandom`-backed) by #829.
- [File / storage] Not applicable — the send touches no file or storage; the attachment bytes were stored by #829.
- [Android attack surface] Not applicable — no intent, deep link, push or WebView.
- [Crypto] Not applicable — the frame is sealed by the existing `NoiseIkSession` path unchanged.
- [Network & I/O] No findings. The bound (`MessageAttachmentIds.MAX` = 32 after repeats are dropped) caps the field at ~1.3 KB, under 2% of the envelope cap, and is enforced before any frame is built, so a buggy caller cannot make the daemon do unbounded resolution work or trip `protocol.malformed` on the count. Oversized `text` against the envelope cap is pre-existing behaviour, untouched.
- [Logs] No findings as designed: the new `RelayLog` lines carry only a count. The ids and text are never logged. `attachment.not_found` does not say which id failed, and the phone does not guess.
- [Concurrency] Not applicable — no new scope, job or shared state; the confirmed insert remains after the `ack` only, so a refused send leaves no phantom row.
- [Threat model] A hostile relay can drop or delay the send; the outcome is the existing `sendAndAwaitReply` failure/teardown path, with nothing inserted. `/clear` with ids: the daemon drops the ids unresolved; no phone-side effect.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
