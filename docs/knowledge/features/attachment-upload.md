# Attachment upload — `ConversationRepository.uploadAttachment`

Uploads a file's bytes, name and declared MIME type to a conversation on **its owning host**, as
`attachment_chunk` frames over the connection the call was made on (#829). Data layer only — no UI.
[#932](https://github.com/pyrycode/pyrycode-mobile/issues/932) wires this call and the three-argument
`sendMessage` into the composer draft's send path (data only — see § Consumer below);
[#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) is the picker and the strip that let a user
actually fill that path — see [Thread screen § Composer pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments);
[#830](https://github.com/pyrycode/pyrycode-mobile/issues/830) sends a
message naming the uploaded ids; [#671](https://github.com/pyrycode/pyrycode-mobile/issues/671) reuses
the chunk payload shape for retrieval; [#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016)
(split from [#674](https://github.com/pyrycode/pyrycode-mobile/issues/674)) proves the phone's own upload
live against a real daemon and a second client. Wire contract: `../pyrycode/docs/protocol-mobile.md` § Attachments
(`attachment_chunk`, `attachment_stored`, "The `attachment_id` shape"). That section still says nothing
emits `attachment_stored` — stale; the daemon has emitted it since pyrycode#1897. That correction belongs
to the pyrycode repo, not here.

## The chunk plan — `data/network/AttachmentPayloads.kt`

`AttachmentChunkPlan(conversationId, attachmentId, bytes, filename, mimeType)` is the sender's
arithmetic, built once per upload:

- `totalChunks = max(1, ceil(size / 45000))` — `ATTACHMENT_CHUNK_BYTES = 45_000`. Every chunk but the
  last carries exactly that many raw bytes; the last carries the remainder. A 0-byte file still produces
  one chunk with empty `data`.
- The whole file's `sha256` is computed **once**, lowercase hex, and repeated on every chunk alongside
  the whole file's `size` — both stay constant across the set.
- `filename` / `mime_type` are cut to `ATTACHMENT_TEXT_MAX_BYTES = 255` UTF-8 bytes via
  `truncateUtf8`, which only ever cuts at a code-point boundary (never splits a multibyte character).
- `payload(index)` base64-encodes that one chunk's slice on demand — a large file is never held as every
  encoded chunk at once, only the raw `bytes` plus whichever single slice is being sent.
- `AttachmentChunkPayloadDto.toString()` and `AttachmentChunkPlan.toString()` both redact: no bytes, no
  filename, no digest. Never log any of those three (see Logging below).

## The transfer — `data/repository/AttachmentUpload.kt`

`AttachmentUploadTransfer(attachmentId)` is the upload-leg sibling of
[`DebugBundleTransfer`](relay-debug-bundle-transfer.md) — a connection-bound transfer that settles once,
routed first in `onInbound`, and failed by the inbound collector's `finally`. It correlates **two
different ways**, and both are needed:

- **Success** by the reply's own payload: `attachment_stored` whose `attachment_id` equals the id this
  phone minted → `Stored(attachmentId)`. The daemon's `in_reply_to` on that frame names whichever chunk
  completed the set (provenance, not a key), so it is never read.
- **Refusal** by the chunk envelope id: `expectReplyTo(envelopeId)` records each chunk's id **before**
  it is sent, so an `error` whose `in_reply_to` names one of them settles `Refused(code, retryable)` even
  if it arrives before the send call returns. An `error` naming an unrelated id settles nothing and falls
  through to the ordinary `TYPE_ERROR` handling, where it matches nothing in `pendingRequests` either.
  An undecodable error payload, or one whose `code` is empty or over 64 characters, settles
  `Refused("error.malformed_reply", retryable = false)` — the same code
  `RemoteConversationRepository.mapError` already uses for an undecodable `error`, not a new one.

`AttachmentUploadResult` is a sealed result, never an exception (`uploadAttachment` never throws except
on cancellation):

```kotlin
sealed interface AttachmentUploadResult {
    data class Stored(val attachmentId: String) : AttachmentUploadResult
    sealed interface Failed : AttachmentUploadResult
    data class Refused(val code: String, val retryable: Boolean) : Failed
    data object TooLarge : Failed
    data object ReconnectRequired : Failed
}
```

`Failed` variants carry **no id** — a failure can never be reported as stored, structurally, not just by
convention. `code` is daemon-authored text; consumers branch on it and never render or log it verbatim.

### The local 8 MB bound — `AttachmentUploadLimit`

`MAX_BYTES = 178 * ATTACHMENT_CHUNK_BYTES` (8,010,000 bytes; 178 chunks). This bound protects the
**phone's own socket**, not the daemon's storage:

- OkHttp's `WebSocket.send` closes the socket once more than 16 MiB is queued, and nothing on the send
  path (`NoiseSessionPump.send` → `OkHttpRelayTransport.send`) waits for that queue to drain.
- A 45000-byte chunk becomes roughly 80 KB on the wire after base64 → Noise encryption → base64 again,
  and roughly 87 KB worst-case with every metadata field (`filename`, `mime_type`) at its 255-byte bound.
- 178 chunks at ~87 KB stays under 16 MiB even at that worst case. A file sent in one loop above that
  would close the connection for **every conversation on that host**, not just the upload.
- The daemon applies its own, larger, unpublished per-upload bound (16 MiB) and answers
  `attachment.too_large` on the first chunk if crossed — that is a separate, daemon-side limit.
  Raising the 8 MB phone-side bound is a distinct change: it needs pacing against the transport's send
  queue (changes to `RelayTransport`, `OkHttpRelayTransport` and `NoiseSessionPump`), which is out of
  this ticket's data-layer scope.

`fits(size)` is checked **before any frame is sent** — an oversized file never sends a chunk, never takes
the upload lock, and its result says not to retry.

## Wiring

### `ConversationRepository` (interface)

`uploadAttachment(conversationId, bytes, filename, mimeType, onProgress): AttachmentUploadResult`
default-throws, the same idiom as `requestSystemPrompt` / `setSessionSettings`: `error("uploadAttachment
is not implemented for this ConversationRepository")`. `FakeConversationRepository` does not override it —
demo support is explicitly out of this ticket's scope. `CachingConversationRepository` passes it through
unchanged via `by delegate`. This keeps the ~20 existing test doubles that override only `sendMessage`
unaffected.

`onProgress: (sentChunks: Int, totalChunks: Int) -> Unit = { _, _ -> }` (#1326) defaults to no-op, so
every pre-existing caller's behaviour and result are unchanged. `RemoteConversationRepository` and
`StableConversationRepository` forward it unchanged; `StableConversationRepository` does not call it on
its own no-repository refusals (`TooLarge` / `ReconnectRequired` with no live repository), since no chunk
ever sent. It runs synchronously on the upload's coroutine and must not throw — documented on the
interface, since `MessageCommands` is its only caller. No `ThreadViewModel` wiring yet: the composer will
consume it in a later ticket.

### `MessageCommands`

New state beside the debug-bundle fields, touched only through `@Synchronized` helpers (never across a
suspension, mirroring the `DebugBundleTransfer` posture): `uploadLock: Mutex`,
`activeUpload: AttachmentUploadTransfer?`, `uploadInboundEnded: Boolean`. Since #915 this state — and
`uploadAttachment` itself — lives on `MessageCommands` (`data/repository/MessageCommands.kt`), not
`RemoteConversationRepository`; the repository declares none of it, and its `override suspend fun
uploadAttachment` is a one-line hand-off.

`uploadAttachment` checks the size bound first (no lock, no frame if it fails), then takes `uploadLock`
for the whole call — **one upload per connection at a time**; a second upload's chunks only start after
the first has settled, so the socket's send queue never carries two uploads' chunks at once. It mints
`UUID.randomUUID().toString()` (same idiom `sendMessage` uses for `message_id`), builds the
`AttachmentChunkPlan`, and registers the transfer via `beginUpload` — which returns `false` (→
`ReconnectRequired`) if the inbound collector has already ended, closing the race between a teardown and
a start. For each chunk: stop if already settled; record the envelope id with `expectReplyTo` **before**
sending; a `false` return or a thrown exception from the pump's `send` fails the transfer
`ReconnectRequired` and stops the loop (no further chunks) — that chunk is never reported. After a
successful send, and before the `yield()`, the loop calls `onProgress(index + 1, plan.totalChunks)`
unless `transfer.isSettled` (#1326) — the same flag the loop itself re-reads, so a chunk's report and the
chunk stop for the same reason, mirroring desktop's `reportProgress` in `attachmentTransfer.ts`. A
refusal that settles the transfer while a chunk's send is still in flight (an unconfined inbound
collector) skips that chunk's report; one that settles between chunks, at the following `yield`, still
reports the chunk that did reach the wire before the loop stops. `yield()` after each successful send
lets the inbound collector settle a refusal between chunks and makes the loop cooperatively cancellable. The
repository's `onInbound` routes `messageCommands.routeAttachmentUpload` right after
`messageCommands.routeDebugBundle`, both ahead of the general demux `when`.
The inbound collector's `finally` calls `messageCommands.endAttachmentUploads()`, which sets
`uploadInboundEnded` and
fails any still-active transfer `ReconnectRequired` — the daemon discards a partial upload with its
connection, so a retry after reconnecting resends every chunk from scratch. No upload timeout: a daemon
that never answers is ended by the connection's own liveness teardown, the same choice
`pyrycode-desktop`'s `attachmentTransfer` made (`pyrycode-desktop` `docs/knowledge/features/attachment-transfer.md`).

### Composer draft — the production caller (#932)

`ThreadViewModel.sendWithAttachments` is the first production caller of both `uploadAttachment` and the
three-argument `sendMessage`. It reads `ComposerDraftStore`'s per-pair pending-attachment list, uploads
whichever entries have no acknowledged id yet in send order, and names each id along with the pending
entry's own `displayName`/`mimeType` as a `MessageAttachment` (#983) — see [Thread screen § Composer
pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) for the store shape, the
retry-keeps-earlier-ids behavior and the content-URI trust boundary
(`ContentResolverAttachmentReader.isForeignContentUri`), and [data model §
`Message`](data-model.md#message) for what the confirmed row does with those references.
[#933](https://github.com/pyrycode/pyrycode-mobile/issues/933)
landed the picker and the strip that fill this path from the UI; [#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016)
landed the live proof: a `SecondClientPeer` retrieves what the phone attached through this path and its
digest matches the fixture.

### `StableConversationRepository` — the one one-shot that doesn't throw

Every other one-shot on this facade follows [the snapshot-or-throw
shape](stable-conversation-repository.md#one-shots--snapshot-or-throw): snapshot
`currentRepository.value` once, delegate, throw `IllegalStateException` if none is live.
`uploadAttachment` is a deliberate **fourth delegation posture** on this facade, alongside cold reads,
one-shot snapshot-or-throw, and fail-safe-deny capability reads: with **no live repository it returns a
result, not an exception** — `TooLarge` if the file doesn't fit the local bound even disconnected, else
`ReconnectRequired`. The acceptance criteria make "no live connection" one of the ordinary failure
outcomes of an upload, on the same footing as a mid-upload drop, so the facade's not-connected case has
to produce the same result type the connected path can produce, not a different exception a caller would
need a second catch clause for. With a live repository, the call snapshots it at entry exactly like
every other one-shot and delegates — so **changing the selected host mid-upload never moves an upload
already in flight**; it keeps running (or fails) against the connection it started on.

## Host binding is structural, not checked

Each live connection gets its own `RemoteConversationRepository` and its own inbound collector
([`RelayRepositoryCoordinator`](relay-repository-coordinator.md)). A reply arriving on host B's
connection is only ever offered to host B's `onInbound`, so it can physically never reach host A's
`AttachmentUploadTransfer` — there is no id-based cross-host check to get wrong, because the two
transfers never share an inbound stream. See [Destination ownership](dependency-injection-host-conversation-source.md#destination-ownership)
for how `StableConversationRepository` reaches the thread's own host with no new DI.

## Logging

`RelayLog.d` only, gated as always to debug builds: `event=attachment_chunk id=<uuid> index=<i>
total=<n>` per chunk, `event=attachment_upload id=<uuid> outcome=<Stored|Refused|TooLarge|ReconnectRequired>`
at settle. Never the bytes, filename, digest, MIME type, or daemon error text.

## Testing

`AttachmentPayloadsTest` covers 0, 45000 and 45001-byte files (1 chunk / 1 chunk / 2 chunks of
45000 + 1), the nine snake_case wire keys, and a multibyte filename/MIME type cut at a code-point
boundary. `RemoteConversationRepositoryAttachmentTest` (own `FakeSessionPump`, the sibling-test-class
pattern `RemoteConversationRepositorySystemPromptTest` established) drives the dual-correlation cases:
a stored reply for another id settling nothing before the right one settles it; a second repository
(a different host) receiving the first's `attachment_stored` never settling the first's upload; two
uploads with the same filename minting distinct ids and each settling only on its own reply; a refusal
mid-upload stopping further chunks; send failure/throw and pump close each giving `ReconnectRequired`
with no further chunks; an oversized file giving `TooLarge` with zero frames sent. Progress cases
(\#1326): an N-chunk upload reports `1..N` of `N` in order, each seen after that chunk was recorded by
the fake pump; a refusal pushed during a chunk's send and settled inside `send`
(`UnconfinedTestDispatcher`) skips that chunk's report; a refusal settled at the following `yield` still
reports the chunk that reached the wire; a refused (`sendResult = false`) or throwing send reports
nothing for the failed chunk. The existing tests, which pass no callback, prove the default no-op path is
unchanged.

Under the `testDebugUnitTest` build, `RelayLog.enabled` is `true` and the default sink calls
`android.util.Log`, which throws `"not mocked"` on plain JVM (there is no Robolectric in this module —
see [Relay diagnostic log § Testing](relay-log.md#testing)). Because that throw happens inside the
upload's own coroutine, it silently killed the coroutine without failing the transfer, so every
assertion only ever saw a result that never settled (`null`) — a repository test that reaches a
`RelayLog.d` call needs a capturing `RelayLog.sink` installed first, the same fix
`DebugBundleDownloadControllerTest` already uses. `StableConversationRepositoryTest` additions cover no
live repository (`ReconnectRequired`, or `TooLarge` if oversized even then) and that switching
`currentRepository` mid-upload leaves an in-flight call on the repository it started on.

No emulator scenario of its own: this is a data-layer ticket with no operator-facing flow of its own —
[#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016) adds the rung-3 live scenario
(`interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, `docs/e2e-interactive-stream.md`),
proving the phone's own upload reaches a real second client with matching bytes.
[#1017](https://github.com/pyrycode/pyrycode-mobile/issues/1017) adds
`interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`, proving that a link cut after one
chunk of a multi-chunk upload settles `ReconnectRequired` and that a retry, once the link is restored,
reaches the peer as exactly one message with the fixture's exact bytes — no duplicate, because the failed
attempt never reached `send_message`. The cut is fired deterministically from the `event=attachment_chunk`
`RelayLog` line above, not from a timer, so it can never race `attachment_stored`.

## Related

- Ticket: `docs/specs/architecture/829-attachment-upload.md` — design, security review, revisions.
- Sibling leg: [Attachment retrieval](attachment-retrieval.md) (#899) — the opposite correlation
  direction (by the request's own envelope id, since every answering frame names it), the phone-side
  512-chunk/23,040,000-byte retrieval bound, and the host-keyed `AttachmentStore` this leg has no
  equivalent of.
- Nearest shape: [Host diagnostic archive transfer](relay-debug-bundle-transfer.md) — the other
  connection-bound, settle-once transfer sharing the sole inbound consumer.
- Contract: [Conversation repository](conversation-repository.md) — the default-throwing member idiom.
- Implementation: [Remote conversation repository](remote-conversation-repository.md) — where the
  transfer is wired into `onInbound` and the inbound collector's `finally`.
- Facade: [Stable conversation repository](stable-conversation-repository.md) — the fourth delegation
  posture, snapshot-or-result.
- [Relay diagnostic log](relay-log.md) — the `RelayLog.d` calls this upload makes, and the JVM
  capturing-sink test requirement.
- DI: [Dependency injection](dependency-injection-host-conversation-source.md#destination-ownership) — why the upload stays on
  the thread's own host with no new wiring.
