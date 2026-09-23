# #829 — Upload an attachment to a conversation on its owning host

## Files read

- `../pyrycode/docs/protocol-mobile.md` § Attachments → `attachment_chunk`, `attachment_stored`, "The `attachment_id` shape", and § Error codes rows `attachment.invalid_chunk` / `too_large` / `storage_failed` — the wire contract. Not restated here. (The section still says nothing emits `attachment_stored`; the daemon has since pyrycode#1897.)
- `app/src/main/java/de/pyryco/mobile/data/repository/DebugBundleTransfer.kt` → `DebugBundleTransfer` — the nearest shape: a connection-bound transfer that settles once, routed first in `onInbound`, failed by the collector's `finally`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `requestDebugBundle`, `endDebugBundle`, `routeDebugBundle`, the `init` collector's `finally`, `onInbound`'s `TYPE_ERROR` arm, `mapError`, `sendMessage` (mints ids with `UUID.randomUUID()`), `requestId` — where the upload is wired.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `requestSystemPrompt` / `setSessionSettings` — the default-throwing member idiom that leaves the ~20 test doubles untouched.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `live`, `refreshSessionSettings` — one-shots snapshot the connection-scoped repository at call entry.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `CachingConversationRepository` — delegates the interface `by delegate`, so the new member passes through with no edit.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` → `SessionPump.send` — non-suspending, non-throwing by contract; the repository still catches a throw (the `requestDebugBundle` posture).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` → `Envelope`, `ErrorPayload`.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `base64StdEncode`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog.d` — debug-gated, content-free logging.
- `docs/knowledge/features/dependency-injection.md` § Destination ownership — the thread holds an owner-bound `StableConversationRepository` over its host's coordinator (#636), so an upload on the repository surface stays on the thread's host with no new DI.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySystemPromptTest.kt` → its private `FakeSessionPump` — the sibling-test-class pattern the new repository test follows.

## Design source

N/A — data layer only; no UI. #670's composer is the consumer.

## Context

Mobile has no attachment transport. The daemon stores an upload that arrives as `attachment_chunk` frames and answers the completing chunk with `attachment_stored`, or a refused chunk with an `attachment.*` error. This ticket adds the upload call on the repository surface. Sending a message that names the uploaded ids is #830; retrieval (#671) reuses the chunk payload; #674 proves the flow live.

No ADR is warranted; the local size bound's derivation belongs in the feature overview (see Documentation handoff).

## Design

### `data/network/AttachmentPayloads.kt` (new)

- `@Serializable data class AttachmentChunkPayloadDto` — the nine wire fields, snake_case via `@SerialName`: `conversation_id`, `attachment_id`, `index`, `total_chunks`, `filename`, `mime_type`, `size` (Long), `sha256`, `data`. Encode-only here; #671 decodes the same shape.
- `class AttachmentChunkPlan(conversationId, attachmentId, bytes: ByteArray, filename, mimeType)` — the sender's arithmetic, built once per upload:
  - `totalChunks = max(1, ceil(bytes.size / CHUNK_BYTES))`;
  - `sha256` computed once with `MessageDigest.getInstance("SHA-256")`, lowercase hex;
  - `filename` and `mimeType` trimmed to `MAX_TEXT_BYTES` (255) UTF-8 bytes, cutting only at a code-point boundary;
  - `fun payload(index: Int): AttachmentChunkPayloadDto` — slices `[index * CHUNK_BYTES, min(size, (index+1) * CHUNK_BYTES))`, base64-encodes lazily, so an 8 MB file is never held as 178 encoded strings at once.
  - `toString()` redacts (no bytes, no filename, no digest).
- `const val ATTACHMENT_CHUNK_BYTES = 45_000`, `ATTACHMENT_TEXT_MAX_BYTES = 255` (in the plan's companion).
- `internal fun truncateUtf8(value: String, maxBytes: Int): String` — the code-point-safe cut.

### `data/repository/AttachmentUpload.kt` (new)

```kotlin
sealed interface AttachmentUploadResult {
    data class Stored(val attachmentId: String) : AttachmentUploadResult
    sealed interface Failed : AttachmentUploadResult
    data class Refused(val code: String, val retryable: Boolean) : Failed   // daemon error on a chunk
    data object TooLarge : Failed                                            // local bound; do not retry
    data object ReconnectRequired : Failed                                   // no connection / send refused / drop
}

object AttachmentUploadLimit {
    const val MAX_BYTES: Int = 178 * 45_000   // 8_010_000
    fun fits(size: Int): Boolean
}

internal class AttachmentUploadTransfer(val attachmentId: String) {
    fun expectReplyTo(envelopeId: Long)                  // record a chunk's envelope id before sending it
    fun accept(envelope: Envelope): Boolean              // true when this envelope settled / belonged to it
    fun fail(result: AttachmentUploadResult.Failed)      // idempotent; first settle wins
    val isSettled: Boolean
    suspend fun await(): AttachmentUploadResult
}
```

- `Failed` variants carry **no id** — a failure never exposes the id as stored.
- `accept` correlates two ways: `attachment_stored` whose payload `attachment_id` equals this id → `Stored`; `error` whose `in_reply_to` is one of the recorded chunk envelope ids → `Refused(code, retryable)` decoded from `ErrorPayload` (undecodable → `Refused(ERROR_MALFORMED_REPLY-equivalent "protocol.malformed_reply", retryable = false)`). Anything else returns `false`. `in_reply_to` on `attachment_stored` is ignored (protocol: provenance, not key).
- Settlement is a `CompletableDeferred<AttachmentUploadResult>`; `complete` is first-wins, so every outcome settles once.
- **MAX_BYTES derivation** (KDoc): OkHttp closes the socket once more than 16 MiB is queued; a chunk is ≈80 KB on the socket after base64 → Noise → base64, worst case ≈87 KB with metadata at its bounds; 178 × 87 KB < 16 MiB. The daemon's own per-upload bound (16 MiB, unpublished) still answers `attachment.too_large`.

### `ConversationRepository` (interface)

```kotlin
suspend fun uploadAttachment(conversationId: String, bytes: ByteArray, filename: String, mimeType: String): AttachmentUploadResult =
    error("uploadAttachment is not implemented for this ConversationRepository")
```

Default-throwing, like `requestSystemPrompt`. `FakeConversationRepository` does not override (the ticket scopes demo support out). `CachingConversationRepository` passes through via `by delegate`.

### `RemoteConversationRepository`

New state beside the debug-bundle fields: `uploadLock: Mutex`, `activeUpload: AttachmentUploadTransfer?`, `uploadInboundEnded: Boolean` (the last two touched only under `@Synchronized` helpers, the `requestDebugBundle` posture).

`override suspend fun uploadAttachment(...)`:
1. `!AttachmentUploadLimit.fits(bytes.size)` → `TooLarge` before anything else (no lock, no frame).
2. `uploadLock.withLock { … }` — one upload per connection at a time; the next waits until the previous settles, so the socket queue has drained before its chunks go out.
3. Mint `UUID.randomUUID().toString()`; build the plan and the transfer; `beginUpload(transfer)` (synchronized) returns `false` when inbound has ended → `ReconnectRequired`.
4. For each index: stop if `transfer.isSettled`; allocate `requestId.incrementAndGet()`; `transfer.expectReplyTo(id)` **before** sending; `pump.send` (a throw counts as `false`); on `false` → `transfer.fail(ReconnectRequired)` and stop; log id/index/total; `yield()` so the inbound collector can settle a refusal between chunks and caller cancellation is cooperative.
5. `transfer.await()`; `finally` clears `activeUpload` (synchronized) and logs the outcome kind.

`onInbound` routes `routeAttachmentUpload(envelope)` right after `routeDebugBundle`; an unmatched `attachment_stored` or `error` falls through unchanged (the `error` to the existing `TYPE_ERROR` arm, where chunk ids match nothing in `pendingRequests`).

The `init` collector's `finally` calls `endAttachmentUploads()` (synchronized): sets `uploadInboundEnded`, fails the active transfer with `ReconnectRequired`. A waiter queued on the lock then sees the flag in `beginUpload`.

### `StableConversationRepository`

`override suspend fun uploadAttachment(...)` — snapshot `currentRepository.value`; with none live return `TooLarge` if the file does not fit, else `ReconnectRequired` (not a throw: the AC makes "no live connection" a failure result). Otherwise delegate to the snapshot, so a later connection change never moves an in-flight upload.

## State + concurrency model

- The upload runs on the caller's coroutine (the composer's `viewModelScope` in #670). No new scope, no timeout; the connection's own liveness teardown ends a silent daemon (desktop made the same choice).
- `uploadLock` is the only `Mutex`; nothing else is taken while it is held except the short `@Synchronized` monitor (never held across a suspension).
- Cancellation: caller cancel → the loop's `yield()`/`await()` throws, `finally` clears `activeUpload`; the daemon discards the partial upload with the connection or keeps it keyed to an id nobody reuses.
- Connection teardown (scope cancel or inbound completion) → collector `finally` → `endAttachmentUploads()`.
- Host binding is structural: each connection has its own `RemoteConversationRepository` and inbound collector, so a reply on another host's connection can only reach that repository's transfer.

## Error handling

| Outcome | Result |
|---|---|
| file > `MAX_BYTES` | `TooLarge` (no frame sent) |
| no live connection (facade) / inbound already ended | `ReconnectRequired` |
| `pump.send` returns false or throws | `ReconnectRequired`, no further chunks |
| connection drops mid-upload | `ReconnectRequired` |
| `error` naming one of this upload's chunks | `Refused(code, retryable)` — the daemon's advice verbatim |
| `attachment_stored` naming this id | `Stored(id)` |

No exceptions escape to the caller except `CancellationException`.

## Logging

`RelayLog.d` only: `event=attachment_chunk id=<uuid> index=<i> total=<n>` per chunk and `event=attachment_upload id=<uuid> outcome=<Stored|Refused|TooLarge|ReconnectRequired>` at settle. Never bytes, filename, digest, mime type, daemon error code/message, or a path.

## Testing strategy

Unit tests only (no UI).

`AttachmentPayloadsTest` (plan):
- 0 bytes → 1 chunk with empty `data`, `size` 0, the empty-input sha256.
- 45000 bytes → 1 chunk of 45000; 45001 → 2 chunks, 45000 + 1.
- Every chunk repeats `size`, lowercase 64-hex `sha256`, `total_chunks`, ids; the concatenated decoded `data` equals the input.
- Encoded JSON keys are the nine snake_case names.
- Filename over 255 bytes of multibyte text is cut to ≤ 255 bytes at a code-point boundary (valid round-trip); mime type likewise; short values unchanged.

`RemoteConversationRepositoryAttachmentTest` (own `FakeSessionPump`):
- 45001-byte upload sends exactly 2 `attachment_chunk` frames naming the conversation and one lowercase UUIDv4; `Stored(id)` only after `attachment_stored` with that id.
- `attachment_stored` for another id settles nothing; the right one then settles.
- A second repository (host B) receiving `attachment_stored` for A's id does not settle A's upload.
- Two uploads of the same filename get distinct ids; B's chunks are not sent until A settles; each settles only on its own reply.
- `error` whose `in_reply_to` names a chunk → `Refused(code, retryable)` for a retryable and a non-retryable code; no further chunks are sent after it (the pump pushes the refusal during chunk 1's send of a 3-chunk file).
- `error` naming an unrelated id settles nothing.
- `send` returning false → `ReconnectRequired`, no further chunks; send throwing → same.
- Pump close mid-upload → `ReconnectRequired`; an upload started after close → `ReconnectRequired` with no frame.
- Oversized file (`MAX_BYTES + 1`) → `TooLarge`, zero frames.

`StableConversationRepositoryTest` additions:
- No live repository → `ReconnectRequired`; oversized with none live → `TooLarge`.
- Delegation snapshots at entry: switching `currentRepository` while a (suspended) upload is in flight leaves it on the first repository, and the result is the first's.

No emulator scenario: this is a data-layer ticket with no operator-facing flow of its own; #674 proves it live.

## Documentation handoff

The ticket carries no Documentation handoff section. Pending for the documentation stage (suggested, not required by an AC): fold the upload surface and the 8 MB local-bound derivation into the owning repository feature overview. The protocol document's stale "nothing emits `attachment_stored`" belongs to the pyrycode repo, not this one.

## Open questions

- Should an unmatched `attachment_stored` be consumed rather than falling through? Falls through to `onInbound`'s `else -> Unit`; no behaviour difference. Resolve in Phase B by leaving it unconsumed unless a test shows otherwise.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] SHOULD FIX — `Refused.code` is daemon-authored text that #670 will branch on. `AttachmentUploadTransfer.accept` is the single inbound boundary for this feature: it compares `attachment_stored`'s payload id by exact equality against the id this phone minted (never adopts a daemon id), matches refusals only by the envelope ids it recorded, and must bound `code` (a non-string, empty or over-64-byte code becomes the malformed-reply code with `retryable = false`). Consumers map codes to their own strings; the KDoc on `Refused` says the code is never rendered or logged. A daemon that answers `attachment_stored` early for our id settles the upload `Stored` and stops further chunks — the daemon is the storage authority, so that is its own claim to make, not an escalation.
- [Trust boundaries — outbound] No findings — `filename` / `mime_type` are user-supplied and cut to 255 UTF-8 bytes at a code-point boundary in `AttachmentChunkPlan`; the daemon sanitises them and never treats them as paths (protocol § Trust and content hygiene). `conversation_id` comes from the caller's own model, validated daemon-side against its registry.
- [Tokens / secrets] No findings — the attachment id is explicitly not a capability (protocol § `attachment_id`); `UUID.randomUUID()` is `SecureRandom`-backed anyway and yields the canonical lowercase v4 shape.
- [File / storage] No findings — no filesystem access on the phone; the bytes arrive in memory and the filename is never a path. OUT OF SCOPE — bounding the read of a picked file *before* allocating it (content-URI size check) belongs to #670's composer, which produces the `ByteArray`.
- [IPC] No findings — no Android components, intents or providers.
- [Crypto] No findings — SHA-256 through `MessageDigest`, integrity only; transport crypto untouched (Noise IK via the vendored library).
- [Network & I/O] No findings — the OkHttp 16 MiB queue closure is the real exposure; `AttachmentUploadLimit.MAX_BYTES` (178 × 45000) keeps one upload under it at the worst-case ≈87 KB frame, and `uploadLock` guarantees a second upload's chunks only follow a settled first. Every chunk stays under the 65519-byte envelope cap by the 45000 / 255 / 64 bounds. No upload timeout by design: the connection's liveness teardown ends a silent peer.
- [Logs] No findings — `RelayLog.d` only (debug-gated), id / index / total / outcome kind; never bytes, filename, digest, mime type or daemon error text. `AttachmentChunkPlan.toString` redacts.
- [Concurrency] No findings — one `Mutex`, never nested; `activeUpload` / `uploadInboundEnded` only touched in `@Synchronized` helpers, never across a suspension; `beginUpload` checks the ended flag and registers atomically, so a teardown racing a start cannot leave an unfailable transfer. `finally` clears `activeUpload` only if it is still this transfer.
- [Threat model] No findings — a hostile relay can drop or delay but not forge (Noise); a drop ends in `ReconnectRequired` via the collector's `finally`, a delay holds the lock until liveness teardown. A reply on another host's connection cannot reach this transfer because each connection has its own repository and collector.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
