# #899 — Retrieve a conversation attachment into app-private storage on its owning host

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `AttachmentChunkPayloadDto` (decoded unchanged on this leg), `isAttachmentIdShape` (the path-safety gate), `attachmentDisplayName` (the display-name sanitiser #898 already ships), `ATTACHMENT_CHUNK_BYTES`.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt` → `AttachmentUploadTransfer`, `AttachmentUploadLimit` — the upload leg's correlation and its per-connection bound; the retrieval leg mirrors its shape but correlates the opposite way (one request envelope id).
- `app/src/main/java/de/pyryco/mobile/data/repository/DebugBundleTransfer.kt` → `DebugBundleTransfer.accept` / `fail`, `MAX_ARCHIVE_BYTES` — the settle-once receiver, one `require` chain classified as one outcome, zeroing on failure, and the 32 MiB push-queue reasoning.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `uploadLock`, `beginUpload`, `endAttachmentUploads`, `routeAttachmentUpload` — one-at-a-time per connection, refuse-after-inbound-ended.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (route ahead of everything), the `init` collector's `finally` (the teardown door), `uploadAttachment` hand-off.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `uploadAttachment` (default-throwing member, the cascade-avoidance pattern).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `uploadAttachment` (live-at-entry, failure-as-value when absent).
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → the host-bound wrapper; `serverId` captured per destination.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → the `noBackupFilesDir` doc comment, `sha256hex(serverId)` host directories, `writeAtomically` (temp + `ATOMIC_MOVE`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ConversationCache` binding, `ThreadDestinationFactory.repository` (where `CachingConversationRepository` is built — a **new instance per call**, so per-host shared state cannot live in the wrapper).
- `../pyrycode/docs/protocol-mobile.md` § Attachments → `attachment_chunk`, "Reassembly & integrity", "Retrieval, and its two terminal signals", "Trust and content hygiene", `request_attachment`. Cited, not restated.
- `pyrycode-desktop` `docs/knowledge/features/attachment-retrieval.md` / `attachment-reassembly-and-store.md` → 30 s idle deadline armed at send and reset per accepted chunk; 512-chunk magnitude bound (23,040,000 bytes).

In-flight overlap: #945 (additive members in `ConversationRepository`, `StableConversationRepository`, `RemoteConversationRepository`) and #883 / #932 (`AppModule.kt`). No dependency; edits here stay additive and local.

## Context

#829 shipped upload; #898 shipped offers. Nothing fetches bytes back. This ticket is the data-layer retrieval that #672 renders and opens. Unpair cleanup of retained files is a sibling ticket; the layout here keys by host so that ticket is one recursive delete.

## Design

Three layers, matching who knows what:

1. **Connection (`RemoteConversationRepository`)** — knows the socket, not the host. Sends `request_attachment`, reassembles the correlated chunks in memory, verifies, and returns the verified content. Never touches the filesystem.
2. **Facade (`StableConversationRepository`)** — delegates the fetch to the connection live at call entry; none live → retryable failure.
3. **Host (`CachingConversationRepository` + `AttachmentStore`)** — knows `serverId`. The store is an app singleton: it single-flights per `(host, conversation, attachment)`, returns a kept file without asking, otherwise asks the connection layer and writes the verified bytes temp-then-rename.

### Wire type — `AttachmentPayloads.kt`

- `internal data class RequestAttachmentPayloadDto(conversation_id, attachment_id)`, both always present.
- Chunks decode through the existing `AttachmentChunkPayloadDto`; its retrieval-leg `conversation_id` is empty and ignored.

### Result types — new `data/repository/AttachmentRetrieval.kt`

```kotlin
sealed interface AttachmentFetchResult {
    class Fetched(val content: AttachmentContent, val displayName: String, val mimeType: String) : AttachmentFetchResult
}
sealed interface AttachmentRetrievalResult {
    data class Retrieved(val file: File, val displayName: String, val mimeType: String) : AttachmentRetrievalResult
    sealed interface Failed : AttachmentRetrievalResult, AttachmentFetchResult
    data object NotFound : Failed      // attachment.not_found, or an id that fails isAttachmentIdShape
    data object TooLarge : Failed      // claimed size over AttachmentRetrievalLimit; nothing allocated
    data object Invalid : Failed       // the stream broke the contract (any reassembly/integrity rule)
    data object Unavailable : Failed   // retryable: stream_aborted, other error codes, no connection,
                                       // refused send, dropped connection, stall, local write failure
}
class AttachmentContent internal constructor(chunks: List<ByteArray>) { val size: Long; fun writeTo(out: OutputStream); toString() = opaque }
```

`displayName` is `attachmentDisplayName(filename)`; `mimeType` is the sniffed hint passed through the same sanitiser (no controls, ≤ 255 UTF-8 bytes) — a hint, never dispatched on here. Neither is logged.

**Bound — `AttachmentRetrievalLimit.MAX_CHUNKS = 512`, `MAX_BYTES = 512 × 45000 = 23,040,000`.** Reassembly is in memory, so the bound is a heap budget: one retrieval per connection at a time holds at most ~23 MB (chunks are kept per index and streamed to disk, never concatenated into a second copy). It sits above the daemon's 16 MiB upload bound, so everything the phone or any client uploaded fits, and near the ceiling of what the daemon's 32 MiB (base64) push queue can deliver in one stream (≈ 24 MB raw). It equals desktop's figure, so both clients refuse the same files. The daemon publishes no bound for host-produced files; a larger one is `TooLarge`.

### Reassembly — `AttachmentRetrievalTransfer` (internal, same file)

One request, settled once, `@Synchronized` like `DebugBundleTransfer`.

- `accept(envelope): Boolean` claims only frames whose `inReplyTo == requestId` and whose type is `attachment_chunk` or `error`; everything else returns `false` and flows on.
- `error` → decode `ErrorPayload`; `attachment.not_found` → `NotFound`; any other code (including `attachment.stream_aborted`) or a malformed payload → `Unavailable`.
- `attachment_chunk` → one validation chain; any violation settles `Invalid` (or `TooLarge`):
  - decode fails, or `attachment_id` ≠ the requested id → `Invalid`;
  - first chunk fixes `total_chunks`, `size`, `sha256`, `filename`, `mime_type`: `size` < 0 → `Invalid`; `size` > `MAX_BYTES` → `TooLarge`; `total_chunks` ≠ `max(1, ceil(size / 45000))` → `Invalid`. Only then is the per-index slot array (`total_chunks` ≤ 512 entries) allocated;
  - later chunks: `total_chunks`, `size` or `sha256` differing from the first → `Invalid` (filename/mime changes are ignored — hints from the first chunk are kept);
  - `index` outside `[0, total_chunks)` or already filled → `Invalid`;
  - `data` must be canonical standard base64 (round-trip, as `DebugBundleTransfer`), decode to ≤ 45000 bytes, and keep the running total ≤ `size` (subtraction, no overflow) → else `Invalid`;
  - when every slot is filled: total == `size` and lowercase-hex sha256 over the slots in index order `==` `sha256` exactly → `Fetched`; else `Invalid`.
- `fail(failure)` settles unless settled; every non-success settle zero-fills and drops the slots.
- `activity: StateFlow<Int>` counts accepted chunks, read by the stall timer.

### Per-connection driver — `AttachmentRetrievals` (internal, same file)

Built by `RemoteConversationRepository` with `nextRequestId`, `send = pump::send`, and `stallTimeout: Duration = 30.seconds`.

- `suspend fun fetch(conversationId, attachmentId): AttachmentFetchResult` — both ids must pass `isAttachmentIdShape` or → `NotFound` without sending. Takes a `Mutex` (one retrieval per connection, which is what bounds memory to one buffer), registers the transfer (refused → `Unavailable` once inbound ended), sends one `request_attachment` (false/throw → `Unavailable`), then waits: each `stallTimeout` window with no new accepted chunk and no settle → `fail(Unavailable)`. Unregisters in `finally`. Logs `event=attachment_request id=<A>` and `event=attachment_retrieval id=<A> outcome=<Class>`; per accepted chunk `event=attachment_chunk_in id=<A> index=<i> total=<n>`.
- `route(envelope): Boolean` — `@Synchronized`, offers to the active transfer.
- `end()` — `@Synchronized`, marks inbound ended and fails the active transfer `Unavailable`.

`RemoteConversationRepository`: builds it, routes it in `onInbound` right after `routeAttachmentUpload`, calls `end()` in the collector `finally` next to `endAttachmentUploads()`, and `override suspend fun fetchAttachment(...) = attachmentRetrievals.fetch(...)`.

### Interface — `ConversationRepository.kt`

- `suspend fun fetchAttachment(conversationId, attachmentId): AttachmentFetchResult` — connection-level; default throws. Callers above the host wrapper use the next one.
- `suspend fun retrieveAttachment(conversationId, attachmentId): AttachmentRetrievalResult` — the kept file on this repository's host; default throws.

`StableConversationRepository.fetchAttachment`: live-at-entry delegation, `Unavailable` when none.

### Host store — new `data/cache/AttachmentStore.kt`

`class AttachmentStore(root: File, ioDispatcher = Dispatchers.IO)`, one Koin `single` over `File(noBackupFilesDir, "attachments")` (same backup reasoning as `FileConversationCache`).

- `suspend fun retrieve(serverId, conversationId, attachmentId, fetch: suspend () -> AttachmentFetchResult): AttachmentRetrievalResult`.
- Layout: `<root>/<sha256hex(serverId)>/<conversationId>/<attachmentId>` (content) and `<attachmentId>.meta.json` (`{version, display_name, mime_type}`). Both ids are used as components **only after** `isAttachmentIdShape`; an id that fails it → `NotFound`, no fetch, no path built. The server id is hashed, never pasted. Nothing from `filename` reaches a path.
- Single-flight: a synchronized map `key → CompletableDeferred<AttachmentRetrievalResult?>`. The leader, under leadership, first reads the kept pair (content file + decodable meta) → `Retrieved` with no fetch; otherwise calls `fetch`; on `Fetched` writes meta via temp + `ATOMIC_MOVE`, then content via `<attachmentId>.part` + `ATOMIC_MOVE` (content last is the commit point); an `IOException` deletes the temp and yields `Unavailable`. Followers await the same deferred. A leader cancelled mid-flight completes the deferred with `null`, and a follower that reads `null` loops and becomes the next leader — a follower never inherits another caller's cancellation.

`CachingConversationRepository`: new `attachments: AttachmentStore? = null` param; `retrieveAttachment` → `attachments.retrieve(serverId, c, a) { delegate.fetchAttachment(c, a) }`, or delegation when null. `AppModule`: the `single` binding and `ThreadDestinationFactory(attachments = …)` passing it through.

## State + concurrency model

- No new scope. `fetch` runs on the caller's coroutine; the stall timer is `withTimeoutOrNull` on `activity`, so cancellation of the caller cancels the wait and the `finally` unregisters the transfer (late chunks then fall through `onInbound`'s `else -> Unit`).
- The inbound collector only calls `route` (synchronized, CPU-only: decode + digest); no file I/O on the collector.
- Store I/O on `ioDispatcher`; single-flight map guarded by `synchronized`, never held across a suspension.
- Connection teardown: collector `finally` → `end()` → active transfer `Unavailable`; later `fetch` on that dead repository → `Unavailable`. A reconnect is a new repository, so a later retrieval sends a fresh request.

## Error handling

Every failure is a value; nothing throws except cancellation. No failure leaves a file under A: content is renamed into place only after `Fetched`, and `Fetched` exists only after length and digest matched. UI mapping is #672's.

## Testing strategy

Unit tests (`./gradlew testDebugUnitTest`), JVM only:

- `AttachmentRetrievalTransferTest` — 0, 45000 and 45001-byte files; out-of-order chunks; wrong attachment id; duplicate index; out-of-range index; `total_chunks` / `size` / `sha256` changed mid-stream; `total_chunks` inconsistent with `size`; size over bound → `TooLarge`; negative size; non-canonical base64; oversize chunk; length and digest mismatch; uppercase digest rejected; `not_found` → `NotFound`; `stream_aborted` → `Unavailable`; foreign `in_reply_to` not claimed.
- `RemoteConversationRepositoryAttachmentRetrievalTest` (fake pump, as the upload test) — one `request_attachment` naming C and A; success end to end; invalid id shape sends nothing; refused send; dropped connection (pump closes) → `Unavailable`; stall after `stallTimeout` of virtual time → `Unavailable`; a retry after a failure sends a fresh request; logs carry no filename/digest.
- `AttachmentStoreTest` (temp dir) — success writes exact bytes under the host directory with no `.part` left; kept file returned with no fetch; concurrent retrievals → one fetch, same outcome; failure leaves no file; a later retrieval after failure fetches again; invalid id shapes never call fetch; two hosts separate.
- `StableConversationRepositoryTest` — `fetchAttachment` absent → `Unavailable`.
- `CachingConversationRepositoryTest` — `retrieveAttachment` goes through the store with this wrapper's `serverId` and the delegate's fetch.

No Compose surface and no operator-facing flow (the UI is #672), so no rung-3/4 scenario here.

## Open questions

- Should an `error` code other than `not_found` / `stream_aborted` (e.g. a future concurrency refusal) be retryable? Chosen: yes (`Unavailable`), since the phone cannot tell it apart from a transient refusal.

## Documentation handoff

Pending for the documentation stage: the ticket names none explicitly. Candidate homes: `docs/knowledge/features/attachment-upload.md` (or a new retrieval overview) and `remote-conversation-repository.md`; the protocol doc's stale "Nothing answers it yet" under `request_attachment` belongs to the pyrycode repo.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one inbound boundary, `AttachmentRetrievalTransfer.accept`, admits a frame only by `in_reply_to` equal to this request's envelope id and a payload `attachment_id` equal to the requested one; everything after it holds an `AttachmentContent` whose length and digest already matched. `filename` and `mime_type` leave the transfer only through `attachmentDisplayName` (no ISO controls, no format/separator/unpaired-surrogate code points, ≤ 255 UTF-8 bytes) and are never used as a path or logged. Both ids are checked with `isAttachmentIdShape` before they are sent, logged or joined into a path.
- [Tokens] No findings — nothing here creates, stores or sends a credential; `request_attachment` carries two validated ids over the existing Noise session.
- [File / storage] No findings on traversal — path components are `sha256hex(serverId)` and two shape-validated UUIDs; the remote filename never reaches the filesystem. Storage is `noBackupFilesDir` (app-private, excluded from cloud backup and device transfer, for `FileConversationCache`'s reason). Writes are temp + `ATOMIC_MOVE`, content last, so a partial file is never readable under A's name and process death leaves at most a `.part` that the next retrieval of A overwrites.
- [File / storage] SHOULD FIX — a write failure must delete its `.part` and must not log the `IOException` message, which carries the local path. Phase B: `event=attachment_store_failed id=<A>` only.
- [File / storage] OUT OF SCOPE — encryption at rest: the kept file is plaintext in app-private storage, the same posture as the conversation cache; a rooted-device reader is not addressed by either. No eviction: retained files grow with what the user opens (each ≤ 23,040,000 bytes); per-host removal on unpair is the sibling ticket split from #671.
- [Android surface] OUT OF SCOPE — nothing exported is added. Handing the file to another app (FileProvider grant, MIME chosen from a sniffed hint) is #672's; it must grant a single URI read-only and never render a sniffed `text/html` as markup.
- [Crypto] No findings — `MessageDigest.getInstance("SHA-256")`, exact lowercase-hex equality. The digest is not a secret, so constant-time comparison is not needed; integrity is not authenticity, and the plan never treats a match as "safe content".
- [Network & I/O] No findings — no claim sizes anything before it is range-checked: `size` ≤ 23,040,000 and `total_chunks == max(1, ceil(size/45000))` (so ≤ 512) are checked on the first chunk before the slot array is allocated; every chunk is ≤ 45000 decoded bytes and charged against `size` by subtraction. One retrieval per connection bounds heap to one buffer per host. The 30 s idle deadline ends a silent stream. Residual (accepted, availability only): a peer dripping one chunk per 29 s holds the connection's retrieval lock for up to 512 × 29 s; the relay can already deny service by dropping the socket.
- [Logs] No findings — logs carry only the validated attachment id, chunk index, total and the outcome class name; never bytes, filename, MIME, digest, path, the daemon's error code text, or an exception message. All through `RelayLog` (debug-gated).
- [Concurrency] No findings — the transfer is registered before `request_attachment` is sent, so a fast reply cannot be missed; registration and `end()` share one lock, so a retrieval started after teardown is refused rather than orphaned. A late chunk for a cancelled or failed request carries an old `in_reply_to` and is never claimed by a newer request. The store's single-flight never holds its lock across a suspension, and a cancelled leader hands leadership on rather than cancelling its followers. No new scope is created.
- [Threat model] No new findings — malicious relay: content-blind, can drop/delay only → idle deadline or teardown, both `Unavailable`, nothing kept. Hostile daemon frame: strict decode, bounded allocation, exact integrity check, text sanitised, nothing trusted as a path. UI-side leakage is #672's.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- **2026-09-24, Phase B.** `AttachmentRetrievalTransfer.activity` also changes once when the transfer settles, not only per accepted chunk. Without it, a retrieval settled by an `error`, a refused send or teardown would sit in the stall wait until the 30 s deadline before returning. The stall contract is unchanged: a deadline with no change still fails `Unavailable`.
- **Open question resolved as planned:** an `error` other than `attachment.not_found` (including a malformed one) is `Unavailable`. The transfer reads only the `code` string rather than decoding the full `ErrorPayload`, so a refusal missing `message` or `retryable` is still classified.
