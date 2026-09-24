# #1049 — read a markdown file live from a conversation's workspace over `read_workspace_file`

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` → `AttachmentRetrievalTransfer`, `AttachmentRetrievals.fetch` — the reassembly and the one-at-a-time driver this request reuses; the transfer's fixed-id rule is the one thing that must change.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `RequestAttachmentPayloadDto`, `isAttachmentIdShape` — where the new DTO goes, and the id-shape check for the conversation id and the minted id.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `fetchAttachment` — the `error(...)` default the new method copies, so `FakeConversationRepository` and inline test doubles need no change.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `attachmentRetrievals`, `fetchAttachment` — the single `AttachmentRetrievals` instance; `onInbound` already routes to it and the collector's `finally` already ends it.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `fetchAttachment` — the facade ViewModels hold. It overrides every verb explicitly, so without an override the new method would hit the interface's `error(...)` default through the facade. Not in the ticket's file estimate; it is the fifth production file.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `CachingConversationRepository` — `by delegate`; unchanged, so the call never reaches `AttachmentStore`.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` → `Envelope` — a data class whose `toString` prints its payload; nothing logs an envelope, and this ticket keeps it that way.
- `app/src/test/java/de/pyryco/mobile/data/repository/AttachmentRetrievalTransferTest.kt`, `RemoteConversationRepositoryAttachmentRetrievalTest.kt`, `StableConversationRepositoryTest.kt` — the fixtures the new tests extend.
- `docs/knowledge/features/attachment-retrieval.md` § "Lessons learned" — the stall wait must wake on settle; `fail` already bumps `activity`, and the shared driver body keeps that.
- `../pyrycode/docs/protocol-mobile.md` § Attachments → `read_workspace_file` — the wire contract (both keys always present, daemon-minted UUIDv4 `attachment_id`, one `attachment.not_found` refusal, `attachment.stream_aborted` after start). Not restated here.

## Design source

N/A — data layer only; no UI in this ticket. The link tap that uses this call is the sibling ticket.

## Context

The in-app markdown reader must show a workspace file as it is on disk now (operator decision 2026-09-24), so this is a live read, never cached: no `AttachmentStore`, no disk write, one request per call.

## Design

**Wire DTO** (`AttachmentPayloads.kt`, beside `RequestAttachmentPayloadDto`):

```kotlin
@Serializable
internal data class ReadWorkspaceFilePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val path: String,
) { override fun toString(): String = "ReadWorkspaceFilePayloadDto" }
```

Both keys always encoded (no defaults, so `MobileJson` cannot omit them). `toString` names neither field.

**Transfer** (`AttachmentRetrievalTransfer`): the constructor's `attachmentId` becomes `String?`, exposed as a read-only property that is `null` until pinned.

- Non-null (the `request_attachment` path): unchanged — every chunk must carry exactly that id, else `Invalid`.
- `null` (the workspace path): the first chunk to arrive pins the id. It must pass `isAttachmentIdShape`, else `Invalid`; every later chunk must repeat it exactly, else `Invalid`. The pin happens in `acceptChunk` before `admitFirst`, so "first chunk" means first to arrive, matching how claims are fixed.
- `toString` and the per-chunk log carry the pinned id (a daemon-minted, shape-checked UUID), never a path.

**Driver** (`AttachmentRetrievals`): the body of `fetch` after the shape check (build envelope, register, send, stall-wait, finish) moves into one private `retrieve(type, payload, attachmentId: String?, requestEvent, outcomeEvent)`, taken under the same `lock`. Two public entry points share it:

- `fetch(conversationId, attachmentId)` — unchanged behaviour and unchanged log lines.
- `suspend fun readWorkspaceFile(conversationId: String, path: String): AttachmentFetchResult` — returns `NotFound` without sending or logging when `conversationId` fails `isAttachmentIdShape` or `path.isBlank()`. Otherwise one `read_workspace_file` envelope with `ReadWorkspaceFilePayloadDto(conversationId, path)` and a transfer with `attachmentId = null`. Logs `event=workspace_file_request` and `event=workspace_file_read outcome=<Class>`; neither names the path nor the conversation id. The path is sent exactly as given — no trimming, normalising or interpretation.

**Repository surface:**

- `ConversationRepository.readWorkspaceFile(conversationId: String, path: String): AttachmentFetchResult = error("readWorkspaceFile is not implemented for this ConversationRepository")`.
- `RemoteConversationRepository` overrides it with `attachmentRetrievals.readWorkspaceFile(...)` — the same instance as `fetchAttachment`, so one retrieval per connection still bounds the heap.
- `StableConversationRepository` overrides it by snapshotting `currentRepository.value`, falling back to `AttachmentRetrievalResult.Unavailable`, exactly like its `fetchAttachment`.
- `CachingConversationRepository` unchanged: `by delegate` forwards it, and `AttachmentStore` is never touched.

## State + concurrency model

No new state or scopes. The call suspends in the caller's scope; the driver's `Mutex` serialises it with `fetch`. Cancellation propagates through the stall wait and the `finally` unregisters the transfer. `end()` (connection teardown) fails whichever transfer is active, workspace or attachment alike.

## Error handling

Same `AttachmentRetrievalResult.Failed` set as `fetchAttachment`:

- `attachment.not_found` → `NotFound` (final).
- `attachment.stream_aborted`, any other or malformed refusal, stall, dropped connection, refused or throwing send, no live repository at the facade → `Unavailable` (retryable).
- A first chunk with a malformed id, an id change mid-stream, or any existing reassembly violation → `Invalid`; claimed size over the bound → `TooLarge`.
- Bad conversation id or blank path → `NotFound` with nothing sent.

## Testing strategy

Unit tests only (`testDebugUnitTest`), no device work.

`AttachmentRetrievalTransferTest`:
- An unpinned transfer accepts a stream under any well-shaped id and completes `Fetched`; the id is pinned from the first chunk to arrive (out of order).
- An unpinned transfer's first chunk with a malformed id → `Invalid`.
- An unpinned transfer whose second chunk names a different id → `Invalid`.
- Existing `chunkNamingTheRequestButAnotherAttachment_failsTheRetrieval` keeps the pinned rule for `request_attachment`.

`RemoteConversationRepositoryAttachmentRetrievalTest` (reusing its `FakeSessionPump` and helpers):
- One call sends exactly one `read_workspace_file` whose payload has exactly `conversation_id` and `path` with the given values (path with spaces and `../` sent verbatim); chunks under a minted id assemble.
- Two calls for the same path send two requests.
- Malformed conversation id and blank path → `NotFound`, nothing sent.
- `attachment.not_found` → `NotFound`; `attachment.stream_aborted` and an unknown code → `Unavailable`; refused send → `Unavailable`; dropped connection mid-stream → `Unavailable`; stall → `Unavailable`.
- A workspace read and a `fetchAttachment` on one connection run one at a time.
- No captured log line contains the path or the conversation id; the payload DTO's and the transfer's `toString` contain neither.

`StableConversationRepositoryTest`: with no live repository → `Unavailable`.

No rung-3 scenario: not operator-facing until the sibling UI ticket wires the link tap.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/attachment-retrieval.md` gains the workspace-read entry point (unpinned transfer, `readWorkspaceFile` on the driver and the facade, its two log events). The ticket names no other documentation requirement.

## Open questions

- None blocking. The chunk's `conversation_id` is not checked on either path today; this ticket keeps that as is.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — inbound frames cross into the process at `AttachmentRetrievalTransfer.accept`, the single boundary for both request types. The new trust decision (accepting a daemon-chosen `attachment_id`) is confined to the pin in `acceptChunk`, which shape-checks the id with `isAttachmentIdShape` before it is logged or compared. The caller-supplied path is outbound only: it is serialised into the sealed envelope and never used locally.
- [Tokens] No findings — no tokens are created, stored or read.
- [File / storage] No findings — nothing is written: the result is `AttachmentContent` in memory, `CachingConversationRepository` forwards by delegation so `AttachmentStore` is never reached, and the minted id never becomes a filename. The phone does no path handling; confinement and the markdown-only rule are the daemon's (`protocol-mobile.md` § `read_workspace_file`). The daemon-authored `filename` and `mime_type` still reach callers only through `attachmentDisplayName`.
- [Android surface] No findings — no manifest, intent, deep link, provider or WebView change.
- [Crypto] No findings — the envelope rides the existing Noise session; the integrity check is the existing `MessageDigest` SHA-256 compare in `AttachmentRetrievalTransfer.complete`.
- [Network & I/O] No findings — memory is bounded by `AttachmentRetrievalLimit` checked before allocation, and by the shared `Mutex` that allows one retrieval per connection; the stall timeout bounds a slow or silent daemon. Every call sends a fresh request, so a hostile relay replaying an old stream cannot satisfy it (correlation is the fresh envelope id in `in_reply_to`).
- [Logs] SHOULD FIX (implemented in Phase B, verified by test) — the path and conversation id must appear in no log and no `toString`. `ReadWorkspaceFilePayloadDto.toString` is overridden; the driver's two new events carry no argument. `Envelope.toString` does print its payload; it is pre-existing and nothing logs an envelope, which this ticket keeps.
- [Concurrency] No findings — no new scope; the shared body keeps register-before-send, unregister in `finally`, and the settle bump on `activity` that ends the stall wait (the #899 lesson).
- [Threat model] Hostile daemon frame: covered by the unchanged reassembly rules plus the pin's shape check. Malicious relay: can drop or delay, which ends in `Unavailable` via stall or teardown. Reading arbitrary markdown in any named conversation's workspace is the accepted residual the protocol doc names; OUT OF SCOPE for the phone.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25
