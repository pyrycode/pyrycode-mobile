# Attachment retrieval — `ConversationRepository.retrieveAttachment`

Fetches a conversation attachment's bytes from **its owning host** and keeps them in app-private
storage, whether the assistant produced the file or the phone uploaded it (#829's id and #898's offered
id are fetched the same way). Data layer only — no UI; [#672](https://github.com/pyrycode/pyrycode-mobile/issues/672)
renders and opens the kept file. Split from [#671](https://github.com/pyrycode/pyrycode-mobile/issues/671);
[#900](../../specs/architecture/900-clear-attachments-on-unpair.md) clears a host's retained files on
unpair — see [§ Removal](#host-store--datacacheattachmentstorekt) below. Wire contract:
`../pyrycode/docs/protocol-mobile.md` § Attachments (`request_attachment`, `attachment_chunk`,
"Reassembly & integrity", "Retrieval, and its two terminal signals", "Trust and content hygiene"). That
section still says nothing answers `request_attachment` — stale; the daemon answers it through
`handleRequestAttachment` (`cmd/pyry/relay.go`). That correction belongs to the pyrycode repo, not here.

`ConversationRepository.readWorkspaceFile` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049))
is a connection-level sibling that reuses this same reassembly to answer a *live* read of one markdown file
from a conversation's workspace, named by path rather than by attachment id. It shares every rule below —
the driver, the stall deadline, the one-retrieval-per-connection lock, the `Failed` outcomes — but touches
neither `AttachmentStore` nor disk: the operator decided on 2026-09-24 that opening a workspace file always
shows it as it is on disk right now, so nothing is cached and two reads of the same path send two requests.
Wire contract: `../pyrycode/docs/protocol-mobile.md` § Attachments → `read_workspace_file`. There is no UI in
the ticket that added it; the link tap that opens a workspace file this way is a sibling ticket.

This is the retrieval-leg sibling of [Attachment upload](attachment-upload.md) (#829): same chunk shape
(`AttachmentChunkPayloadDto`, `ATTACHMENT_CHUNK_BYTES = 45_000`), opposite correlation. Upload correlates
by the chunk's own envelope id, because the daemon's one success reply doesn't need to; retrieval
correlates by **the request's own envelope id**, because every answering frame — each chunk and the
`error` alike — names it in `in_reply_to`.

## Three layers, matching who knows what

1. **Connection** (`RemoteConversationRepository.fetchAttachment`) — knows the socket, not the host.
   Sends one `request_attachment`, reassembles the correlated chunks in memory, verifies, returns the
   verified content. Never touches the filesystem.
2. **Facade** (`StableConversationRepository.fetchAttachment`) — delegates to the repository live at call
   entry; none live is a retryable failure, not an exception.
3. **Host** (`CachingConversationRepository.retrieveAttachment` + `AttachmentStore`) — knows `serverId`.
   The store is an app singleton: it single-flights per `(host, conversation, attachment)`, returns a
   kept file without asking, otherwise calls the connection layer and writes the verified bytes
   temp-then-rename.

## Wire type — `data/network/AttachmentPayloads.kt`

`internal data class RequestAttachmentPayloadDto(conversation_id, attachment_id)` — both keys always
sent, both checked with `isAttachmentIdShape` before one is built. There is no request-id key on the
wire; the answer names the request's own envelope id instead. Chunks decode through the existing
`AttachmentChunkPayloadDto` unchanged; its retrieval-leg `conversation_id` is empty and ignored.

`internal data class ReadWorkspaceFilePayloadDto(conversation_id, path)` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049))
sits beside it. Both keys are always sent too, but neither is shape-checked by the DTO itself:
`conversation_id` is checked by the driver before the DTO is built (§ Per-connection driver below), and
`path` is sent exactly as given — the phone does not interpret it, the daemon owns confinement. `toString`
is overridden to name neither field, since the path and the conversation id must never reach a log.

## Result types — `data/repository/AttachmentRetrieval.kt`

```kotlin
sealed interface AttachmentFetchResult {
    class Fetched(val content: AttachmentContent, val displayName: String, val mimeType: String) : AttachmentFetchResult
}
sealed interface AttachmentRetrievalResult {
    data class Retrieved(val file: File, val displayName: String, val mimeType: String) : AttachmentRetrievalResult
    sealed interface Failed : AttachmentRetrievalResult, AttachmentFetchResult
    data object NotFound : Failed      // attachment.not_found, or an id that fails isAttachmentIdShape
    data object TooLarge : Failed      // claimed size over AttachmentRetrievalLimit.MAX_BYTES; nothing allocated
    data object Invalid : Failed       // the stream broke a reassembly/integrity rule
    data object Unavailable : Failed   // retryable: stream_aborted, other error codes, no connection,
                                        // refused send, dropped connection, stall, local write failure
}
```

`Failed` is shared between the two sealed hierarchies on purpose: a connection-level failure and a
host-level failure read as the same value, so `CachingConversationRepository` can hand a
`Failed` straight back without translating it. `displayName` is `attachmentDisplayName(filename)` (no
ISO control or Unicode format code points, ≤ 255 UTF-8 bytes — #898's sanitiser); `mimeType` is the
daemon's sniffed hint passed through the same sanitiser. Both are hints from attacker-chosen bytes,
never a path and never a privilege, and neither is ever logged. `AttachmentContent` holds the verified
chunks as the list they arrived in and writes them to an `OutputStream` in index order; its `toString()`
is opaque, never bytes or a length breakdown beyond the total size.

### The bound — `AttachmentRetrievalLimit`: 512 chunks, 23,040,000 bytes

Reassembly is in memory and one connection runs one retrieval at a time, so the bound is this
repository's heap budget for a file: `MAX_CHUNKS = 512`, `MAX_BYTES = 512 × 45_000`. It sits above the
daemon's 16 MiB upload bound (so anything any client uploaded fits) and near the ceiling of the daemon's
32 MiB base64 push queue delivered in one stream; it equals desktop's figure, so both clients refuse the
same files (`pyrycode-desktop` `docs/knowledge/features/attachment-retrieval.md`). The daemon publishes
no bound for host-produced files — a larger one is `TooLarge`.

## Reassembly — `AttachmentRetrievalTransfer` (internal)

One request, settled once, `@Synchronized` like [`DebugBundleTransfer`](relay-debug-bundle-transfer.md).
`accept(envelope)` claims only frames whose `inReplyTo` equals the request's own envelope id; everything
else returns `false` and flows on to the ordinary demux.

For `request_attachment` the requested id is fixed at construction, exactly as before. For
`read_workspace_file` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049)) the daemon mints
the answer's id, so the constructor's `attachmentId` is `String?`, `null` until pinned: the first chunk to
arrive pins it, once that id passes `isAttachmentIdShape`, and every later chunk must repeat the pinned
value exactly. The pin happens in `acceptChunk` before `admitFirst`, so "first chunk" means first to
arrive (possibly out of order), matching how the other first-chunk claims (`size`, `total_chunks`,
`sha256`, `filename`, `mime_type`) are fixed. `toString` and the per-chunk log carry the pinned id, never
a path.

- `error` → `attachment.not_found` settles `NotFound`; any other code (including
  `attachment.stream_aborted`), or a payload without a readable `code`, settles `Unavailable` — the
  transfer reads only the `code` string, not the full `ErrorPayload`, so a refusal missing `message` or
  `retryable` still classifies.
- `attachment_chunk` → one validation chain; any violation settles `Invalid` or `TooLarge`:
  - decode fails → `Invalid`; the id check comes next — with a fixed id (`request_attachment`), an
    `attachment_id` that doesn't equal the requested one → `Invalid` (a chunk that names the right request
    but the wrong attachment fails the retrieval, per the acceptance criteria); with no id pinned yet
    (`read_workspace_file`), a first-arrival id that fails `isAttachmentIdShape` → `Invalid`, and once
    pinned, a later chunk naming a different id → `Invalid`;
  - **first chunk fixes the claims**: `size`, `total_chunks`, `sha256`, `filename`, `mime_type`.
    `size < 0` → `Invalid`; `size > MAX_BYTES` → `TooLarge`, checked **before** the per-index slot array
    is allocated; `total_chunks != max(1, ceil(size / 45000))` → `Invalid`;
  - later chunks: `total_chunks`, `size` or `sha256` differing from the first chunk → `Invalid` (a
    changed `filename`/`mime_type` is silently ignored — the first chunk's hint wins);
  - `index` outside `[0, total_chunks)` or already filled → `Invalid`;
  - `data` must round-trip through canonical standard base64, decode to ≤ 45000 bytes, and keep the
    running total ≤ `size` by subtraction (no overflow) → else `Invalid`;
  - once every slot is filled: `sha256` over the slots in index order, lowercase hex, exactly equal, and
    the assembled length exactly equal to `size` → `Fetched`; otherwise `Invalid`.
- `fail(failure)` settles unless already settled and zero-fills the slots — no verified-length buffer
  or partial content survives a failed transfer, even transiently.

`activity: StateFlow<Int>` changes on every accepted chunk **and once when the transfer settles**
(Phase B revision) — see § Lessons below for why the settle-only case matters.

## Per-connection driver — `AttachmentRetrievals` (internal)

Built by `RemoteConversationRepository` with `nextRequestId`, `send = pump::send`, and
`stallTimeout = 30.seconds` (`DEFAULT_STALL_TIMEOUT` — the same figure desktop's reassembler uses and the
relay's pong timeout). `fetch(conversationId, attachmentId)` and
`readWorkspaceFile(conversationId, path)` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049))
share one private `retrieve(type, payload, attachmentId: String?, requestEvent, outcomeEvent)`, taken
under the same `lock`:

1. `fetch` requires both `conversationId` and `attachmentId` to pass `isAttachmentIdShape`.
   `readWorkspaceFile` requires `conversationId` to pass it and `path` to be non-blank. Either check
   failing returns `NotFound` without sending anything, before `retrieve` is ever entered.
2. `retrieve` takes a `Mutex` — **one retrieval per connection**, so a workspace read and a `fetch` on
   the same connection still run one at a time. That is what bounds memory to one
   `AttachmentRetrievalLimit.MAX_BYTES` buffer per host, mirroring the upload leg's one-upload-per-
   connection `uploadLock`.
3. Registers the transfer **before** sending — a refused registration (inbound already ended) returns
   `Unavailable` with nothing sent; a fast answer can never be missed.
4. Sends one envelope (`request_attachment` or `read_workspace_file`); a thrown exception or a `false`
   return fails the transfer `Unavailable`.
5. Waits: each `stallTimeout` window with no change on `activity` fails the transfer `Unavailable`.
6. Unregisters in `finally`. `fetch` logs `event=attachment_request id=<A>`, per-chunk
   `event=attachment_chunk_in id=<A> index=<i> total=<n>`, and
   `event=attachment_retrieval id=<A> outcome=<Class>` at settle. `readWorkspaceFile` logs
   `event=workspace_file_request` and `event=workspace_file_read outcome=<Class>` — neither carries the
   path or the conversation id, unlike `fetch`'s events, which carry the daemon-minted attachment id (not
   sensitive) rather than anything caller-chosen. Never bytes, filename, digest, or path, on either
   request type.

`readWorkspaceFile` builds its transfer with `attachmentId = null` — the id is unknown until the daemon
answers; see the first-arrival pin in § Reassembly above. Every call is a fresh request, so nothing is
cached: two calls for the same path send two requests.

`route(envelope)` (`@Synchronized`) offers to the active transfer; `end()` (`@Synchronized`, called from
the inbound collector's `finally`, beside `endAttachmentUploads()`) marks inbound ended and fails the
active transfer `Unavailable` — a stream cannot outlive its connection, and a retrieval started after
teardown is refused rather than orphaned. This applies equally to a `readWorkspaceFile` in flight.

`RemoteConversationRepository` builds `attachmentRetrievals` as a `private val`, routes it in
`onInbound` right after `routeAttachmentUpload` (ahead of the general demux), calls `end()` in the
`init` collector's `finally` next to `endAttachmentUploads()`, and
`override suspend fun fetchAttachment(...) = attachmentRetrievals.fetch(...)` and
`override suspend fun readWorkspaceFile(...) = attachmentRetrievals.readWorkspaceFile(...)`.

## Facade — `StableConversationRepository.fetchAttachment` / `readWorkspaceFile`

Snapshots `currentRepository.value` and delegates, exactly like `uploadAttachment`'s [snapshot-or-result
posture](stable-conversation-repository.md#uploads--snapshot-or-result-829): with no live repository it
returns `AttachmentRetrievalResult.Unavailable`, a value in the same `Failed` set the connected path can
also produce, not an `IllegalStateException`. A connection change mid-fetch never moves a fetch already
in flight — it keeps running (or fails) against the connection it started on.

`readWorkspaceFile` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049)) follows the same
posture with its own explicit override. **This facade overrides every `ConversationRepository` verb
explicitly** rather than inheriting the interface's default-throw, so a new interface method needs an
override added here too, or it throws through the facade ViewModels actually hold — `FakeConversationRepository`
and other test doubles staying on the default is invisible until something calls through the live facade.
The plan for #1049 had budgeted only the interface and `RemoteConversationRepository`; the missing
override here was caught during implementation, not planning.

**Fetch on open, decided above this facade (#1329).** `fetchAttachment` itself does not know or care why
it was called — it still delegates every call unconditionally. What changed is how often `ThreadViewModel`
calls through it: a thread used to call `retrieveAttachment` for every attachment the moment its row was
composed, so scrolling past a thread of PDFs or archives pulled each one over the relay. Since #1329,
`ThreadViewModel.onAttachmentShown` only starts a load when the attachment is image-kind — by MIME type,
or by a name-only reference's extension matching desktop's image list — and defers everything else until
the row is tapped or long-pressed (`onAttachmentRequested`). A reference with neither a MIME type nor a
name — the one case this facade's own caller cannot classify without fetching — still loads on show,
because retrieval is the only way to learn what it is: that is the history-replayed row, which has no
reference metadata to decide from. See [MessageBubble — attachment slot § Fetch on
open](message-bubble-attachment-slot.md#fetch-on-open-since-1329) for the full rule and the claim/settle
machinery that makes a tap open or save exactly once.

## Host store — `data/cache/AttachmentStore.kt`

One Koin `single` over `File(androidContext().noBackupFilesDir, "attachments")` — the same backup
reasoning [`FileConversationCache`](conversation-cache.md) gives: a retrieved file must be exactly as
transferable as the Keystore-wrapped pairing that authorised fetching it, which is not at all.

**Layout:** `<root>/<sha256hex(serverId)>/<conversationId>/<attachmentId>` holds the bytes,
`<attachmentId>.meta.json` beside it the sanitised `display_name` / `mime_type`. Both ids are used as
path components only **after** `isAttachmentIdShape` — lowercase hex and `-` cannot spell anything but
themselves; an id that fails the shape check returns `NotFound` before any path is built and before
`fetch` is called. The server id is hashed, never pasted, mirroring `FileConversationCache`'s host
directories. One host is one directory, so unpair's per-host removal (`removeHost`, #900 below) is one
recursive delete.

**Writes:** metadata is written first, content last, each through a `.part` file and
`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` — content last makes its name the commit point, so a
partial file is never readable under the attachment's id, and a kept pair with unreadable or
version-mismatched metadata (`readKept` checks `KeptAttachment.version`) is treated as absent and fetched
again rather than trusted. A write failure (`IOException` or `SecurityException`) deletes its `.part`,
logs `event=attachment_store_failed id=<A>` with no exception message (the message carries the local
path), and returns `Unavailable`.

**Single-flight:** a `synchronized` `HashMap<Key, CompletableDeferred<AttachmentRetrievalResult?>>`. The
leader (the caller that inserts the entry) first checks for a kept file — a success returns `Retrieved`
with **no fetch call at all**, satisfying "after a success, a later retrieval returns the kept file
without sending anything." Otherwise it calls `fetch`, and on `AttachmentFetchResult.Fetched` writes the
bytes before returning `Retrieved`. Followers `await()` the same deferred. **A cancelled leader completes
the deferred with `null`**, and a follower that reads `null` loops and becomes the next leader — a
follower never inherits another caller's cancellation, and cancelling one of several concurrent callers
never fails the others.

**Removal — `removeHost(serverId): Result<Unit>` (#900):** on `ioDispatcher`, `deleteRecursively`s the
host's whole directory. Failure is decided by the directory's continued existence afterward — the same
rule [`FileConversationCache.removeHost`](conversation-cache.md#removal-on-unpair--forgetremovedhost)
uses — and a `SecurityException` is also caught as failure; an unknown host is a successful no-op. The
failure carries a static message with no id or path, and never throws except on cancellation. The store
itself logs nothing here; the caller,
[`forgetRemovedHost`](conversation-cache.md#removal-on-unpair--forgetremovedhost), logs the static
`event=host_attachments_remove_failed` on failure and does not surface it.

**Served to other apps (#985), never the store as a whole.** A kept file's bytes reach another app only
through a `FileProvider` declared in the manifest as `android:authorities="${applicationId}.attachments"`,
`android:exported="false"`, `android:grantUriPermissions="true"`. Its one root is
`res/xml/attachment_paths.xml`'s single `<files-path name="attachments" path="../no_backup/attachments/" />`
— androidx.core 1.16's `FileProvider` has no `no-backup-path` tag, so the root is reached the only other way
available: `files` and `no_backup` are sibling directories under the app's data directory, and `../no_backup/`
from the `files-path` root lands exactly on `File(noBackupFilesDir, "attachments")`, the directory this store
already is. `FileProvider` canonicalises whatever path it is given, so a wider entry (`../no_backup/`, no
`attachments` suffix) would have served the store's *parent* — `AttachmentActionsTest.filesOutsideTheStoreRoot_areNeverServed`
is the test that catches that mistake, by asserting a file directly under `noBackupFilesDir`, under `filesDir`,
under `filesDir/attachments`, and under `cacheDir` are all refused a URI. `ui/conversations/thread/AttachmentActions.kt`'s
`attachmentContentUri` is the only caller: it builds the URI with `FileProvider.getUriForFile(context, authority, file)`
from the store's own `File`, never from a display name, and returns `null` on the `IllegalArgumentException`
that call throws for anything outside this one root. The intent that carries the resulting URI grants
`FLAG_GRANT_READ_URI_PERMISSION` only — never write, never persistable, never a whole-tree prefix — so a
receiving app can read exactly the one file the user opened or saved, nothing else in the store, and nothing
in `noBackupFilesDir` or `filesDir` at large. See [MessageBubble — attachment slot §
Open and save](message-bubble-attachment-slot.md#open-and-save-since-985) for the tap/long-press wiring above
this provider, and `docs/specs/architecture/985-open-and-save-message-attachment.md` for the intent and save
mechanics `AttachmentActions.kt` owns.

**Lesson: `FileProvider` caches each authority's canonical roots in a static map for the process's life.**
On a device the data directory never moves, so this is invisible; Robolectric gives every test a fresh one,
so a root resolved by an earlier test in the same JVM points at a directory that test run has already
deleted, and every later `getUriForFile` call for that authority silently refuses every file. `AttachmentActionsTest`
clears `FileProvider`'s private `sCache` field with reflection in a `@Before`, and says why in a comment —
the fix belongs on the test, not on the production path, since the cache is exactly what makes `FileProvider`
cheap to call on every open.

## Wiring — `CachingConversationRepository` + `AppModule`

`CachingConversationRepository` takes a fourth constructor param, `attachments: AttachmentStore? = null`:

```kotlin
override suspend fun retrieveAttachment(conversationId: String, attachmentId: String): AttachmentRetrievalResult =
    attachments?.retrieve(serverId, conversationId, attachmentId) { delegate.fetchAttachment(conversationId, attachmentId) }
        ?: delegate.retrieveAttachment(conversationId, attachmentId)
```

With no store it is plain delegation, the same fallback shape the class already uses elsewhere. `AppModule`
binds `single { AttachmentStore(File(androidContext().noBackupFilesDir, "attachments")) }` beside the
`ConversationCache` single, and `ThreadDestinationFactory` gained the matching `attachments: AttachmentStore?
= null` constructor param, threaded into `CachingConversationRepository(stable, cache, serverId, attachments)`
and passed `if (useRelay) get() else null` from `hostConversationModule`, mirroring the existing
`cache = if (useRelay) get() else null` gate.

### `ConversationRepository` interface

Default-throwing members, the same idiom as `requestSystemPrompt`:

- `fetchAttachment(conversationId, attachmentId): AttachmentFetchResult` — connection-level, host-blind.
  Screens don't call this directly; it exists so `StableConversationRepository` and
  `RemoteConversationRepository` have something to override.
- `readWorkspaceFile(conversationId, path): AttachmentFetchResult`
  ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049)) — connection-level, host-blind live
  read of a workspace file, never cached. `RemoteConversationRepository` and `StableConversationRepository`
  override it (see § Facade above); `CachingConversationRepository` forwards it through `by delegate`
  unchanged, so it never reaches `AttachmentStore`; `FakeConversationRepository` and other test doubles
  keep the default throw.
- `retrieveAttachment(conversationId, attachmentId): AttachmentRetrievalResult` — the kept file on this
  repository's host. Only `CachingConversationRepository` actually keeps files; every other repository
  either doesn't override it (default-throws) or, on `StableConversationRepository`, doesn't need to
  since screens read through the host-bound wrapper. `FakeConversationRepository` (demo) doesn't override
  either — retrieval, like upload, is explicitly out of the demo branch's scope.

## Lessons learned

- **The stall wait must wake on settle, not only on a chunk.** `AttachmentRetrievalTransfer.activity`
  originally changed only when a chunk was accepted. A retrieval settled by an `error`, a refused send, or
  a torn-down connection then sat in the `stallTimeout` wait for the full 30 s before `fetch` returned,
  because nothing ever bumped `activity` for those paths. The fix bumps `activity` once inside `fail(...)`
  too. The stall-deadline test alone can't catch this regression — it passes either way — so a test that
  specifically settles the transfer with no chunk and asserts the wait returns immediately is the one that
  matters (see the Phase B revision in `docs/specs/architecture/899-attachment-retrieval.md`).
- **Per-host shared state cannot live in `CachingConversationRepository` itself.**
  `ThreadDestinationFactory.repository` builds a *new* `CachingConversationRepository` instance on every
  call, so any state that must be shared across every call for one host — here, the single-flight
  bookkeeping that makes two concurrent retrievals of the same file share one fetch — has to live one
  level up, in an app singleton keyed by host (`AttachmentStore`, keyed by `serverId`), not in the
  per-call wrapper.
- **A 3-byte base64 test can't prove non-canonical rejection.** 3 bytes encode to base64 with no padding
  (`=`) at all, so a "strip the trailing `=`" mutation on a 3-byte payload changes nothing and any test
  built that way fails for the wrong reason (or doesn't fail at all). Use a chunk length that is not a
  multiple of 3 when testing the canonical-round-trip check in `AttachmentRetrievalTransfer.place`.
- **DI: resolving a per-host store eagerly through `get()` inside a `single { }` block needs the same
  test-container treatment as `ConversationCache`.** See [Dependency injection §
  AttachmentStore](dependency-injection.md#attachmentstore-and-context-free-thread-destination-containers-899).
- **Don't seed an `AttachmentStore` fixture by suspending on a separate `StandardTestDispatcher` from a
  test body running on an `UnconfinedTestDispatcher`.** `HostChannelListViewModelTest` (#900) needed one
  `AttachmentStore` on the same queued dispatcher the production hook uses (so the test can observe the
  unpair confirmation still open while the removal is pending) and a second store over the same root to
  seed and read back files without disturbing that queue. Seeding through the queued store resumes the
  test body inside that dispatcher's task, where the view model's unconfined launches never start, so
  `openHostEditor` silently does nothing and later assertions fail far from the cause. Seed and read back
  through the second, unconfined-dispatcher store instead.

## Logging

`RelayLog.d` only (debug-gated): `event=attachment_request id=<A>`, `event=attachment_chunk_in id=<A>
index=<i> total=<n>`, `event=attachment_retrieval id=<A> outcome=<Class>` from the connection layer, and
`event=attachment_store_failed id=<A>` from the store on a write failure. `readWorkspaceFile`
([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049)) adds `event=workspace_file_request`
and `event=workspace_file_read outcome=<Class>` from the same connection layer — unlike the attachment
events above, neither carries an id, since the path and the conversation id must never appear in a log.
Never the bytes, the filename, the digest, the MIME type, the daemon's error code text, an exception
message, or a local path.

## Testing

- `AttachmentRetrievalTransferTest` — 0, 45000 and 45001-byte files; out-of-order chunks; a chunk naming
  the right request but the wrong attachment id; duplicate index; out-of-range index; `total_chunks` /
  `size` / `sha256` changed mid-stream; `total_chunks` inconsistent with `size`; size over the bound →
  `TooLarge` before allocation; negative size; non-canonical base64; an oversized chunk; a length or
  digest mismatch at completion; an uppercase digest rejected (lowercase-hex only); `not_found` →
  `NotFound`; `stream_aborted` after partial chunks → `Unavailable` with the partial state discarded (a
  chunk arriving after the abort is claimed but changes nothing); another or malformed `error` →
  `Unavailable`; a foreign `in_reply_to` not claimed; first outcome wins once settled. #1049 added: an
  unpinned transfer (`attachmentId = null`) accepts a stream under any well-shaped id and completes
  `Fetched`, with the id pinned from the first chunk to arrive even out of order; an unpinned transfer's
  first chunk with a malformed id → `Invalid`; an unpinned transfer whose second chunk names a different
  id than the one its first chunk pinned → `Invalid`. The existing
  `chunkNamingTheRequestButAnotherAttachment_failsTheRetrieval` keeps proving the fixed-id rule for
  `request_attachment` is unchanged.
- `RemoteConversationRepositoryAttachmentRetrievalTest` (fake pump, the sibling-test-class pattern
  `RemoteConversationRepositoryAttachmentTest` established) — one `request_attachment` naming the right
  conversation and attachment; success end to end; an invalid id shape sends nothing; a refused send; a
  dropped connection (pump closes) → `Unavailable`; a stall after `stallTimeout` of virtual time →
  `Unavailable`; a retry after a failure sends a fresh request; logs carry no filename or digest. #1049
  extended this class for `readWorkspaceFile`: one call sends exactly one `read_workspace_file` whose
  payload has exactly `conversation_id` and `path` with the given values (a path with spaces and `../`
  sent verbatim); two calls for the same path send two requests; a malformed conversation id or a blank
  path → `NotFound` with nothing sent; `attachment.not_found` → `NotFound`; `attachment.stream_aborted`
  and an unknown code → `Unavailable`; a refused send, a dropped connection mid-stream, and a stall each
  → `Unavailable`; a workspace read and a `fetchAttachment` on one connection run one at a time; no
  captured log line, and neither the payload DTO's nor the transfer's `toString`, contains the path or
  the conversation id.
- `AttachmentStoreTest` (`TemporaryFolder`) — success writes exact bytes under the host directory with no
  `.part` left behind; a kept file is returned with no fetch call; concurrent retrievals of the same key
  produce one fetch and one shared outcome; a failure leaves no file under the attachment's id; a later
  retrieval after a failure fetches again; an invalid id shape never calls `fetch`; two hosts keep
  separate trees. `removeHost` (#900): deleting host A's directory removes every file kept for A across
  two conversations and leaves host B's kept file readable with no fetch; an unknown host is a successful
  no-op; a host directory that cannot be deleted (root made read-only) reports failure without throwing.
- `StableConversationRepositoryTest` — `fetchAttachment` with no live repository returns `Unavailable`;
  #1049 added the same case for `readWorkspaceFile`.
- `CachingConversationRepositoryTest` — `retrieveAttachment` goes through the store with this wrapper's
  own `serverId` and the delegate's `fetchAttachment` as the fetch function.
- Under `testDebugUnitTest`, `RelayLog.enabled` is `true` and the default sink calls `android.util.Log`,
  which throws on plain JVM with no Robolectric — the same capturing-sink requirement documented at
  [Relay diagnostic log § Testing](relay-log.md#testing) and [Attachment upload §
  Testing](attachment-upload.md#testing) applies to every test here that reaches a `RelayLog.d` call.
- `readWorkspaceFile` ([#1049](https://github.com/pyrycode/pyrycode-mobile/issues/1049)) has no rung-3
  scenario for the same reason: it is not operator-facing until the sibling UI ticket wires the link tap
  that opens a workspace file this way.
- No Compose surface and no operator-facing flow of its own — the UI is [#984](message-bubble-attachment-slot.md#attachment-slot-since-984), which maps this leg's four failure members onto `AttachmentViewState` (`Retrieved → Ready`, `NotFound → NotFound`, `TooLarge`/`Invalid`/`Unavailable` → `Failed`) — so no rung-3/4 scenario here either; [#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016) (split from #674) proves `request_attachment` live from the peer's side (retrieving the phone's own upload) and from the phone's side (retrieving a file claude offers with `send_file`), each matching the fixture's SHA-256; see `docs/e2e-interactive-stream.md`. The remaining leg — another client's upload, named on a message, retrieved after a history reload — was blocked on the daemon dropping a `message` entry's `attachment_ids` from history entirely, so `request_attachment` being answered said nothing about whether a client could ever learn the id to ask for after a reload; [#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020) closed that gap (see [Remote conversation repository — reads and the thread store — history paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) for the reducer change) and the live scenario proving this leg, `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, is now in `docs/e2e-interactive-stream.md`'s LIVE list.
  [#1017](https://github.com/pyrycode/pyrycode-mobile/issues/1017) adds
  `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, proving that a link cut at
  `event=attachment_request` — after the transfer registers, before the request is sent — settles
  `Unavailable` and shows the failed row with Retry, and that Retry, once the link is restored, reaches
  ready under the correct name with the fixture's exact bytes on open and save. The cut is deterministic,
  fired from the `RelayLog` line above rather than a timer.

## Related

- UI consumer: [MessageBubble — attachment slot § Attachment slot](message-bubble-attachment-slot.md#attachment-slot-since-984) (#984) —
  renders each reference in its bubble and starts a `retrieveAttachment` call when its row is first shown
  on screen, falling back first to a still-readable original of a file this phone sent in the same app
  session (`ComposerDraftStore.sentOriginal`, [Thread screen — composer drafts and
  attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments)). Opening or
  saving the kept file is [#985](message-bubble-attachment-slot.md#open-and-save-since-985), through the
  non-exported `FileProvider` documented in [§ Host store](#host-store--datacacheattachmentstorekt) below.
- Ticket: `docs/specs/architecture/899-attachment-retrieval.md` — design, the bound's reasoning, security
  review, revisions (the settle-wake fix and the `InertAttachmentStore` rework).
- Sibling leg: [Attachment upload](attachment-upload.md) (#829) — the opposite correlation direction, the
  local socket-queue bound, the `MessageCommands`-hosted state shape this leg's per-connection driver
  mirrors.
- Nearest shape: [Host diagnostic archive transfer](relay-debug-bundle-transfer.md) — the other
  connection-bound, settle-once transfer sharing the sole inbound consumer.
- Contract: [Conversation repository](conversation-repository.md) — the default-throwing member idiom,
  `fetchAttachment` / `retrieveAttachment`.
- Implementation: [Remote conversation repository](remote-conversation-repository.md) — where
  `attachmentRetrievals` is wired into `onInbound` and the inbound collector's `finally`.
- Facade: [Stable conversation repository](stable-conversation-repository.md) — the snapshot-or-result
  posture `fetchAttachment` shares with `uploadAttachment`.
- Host wrapper: [Caching conversation repository](caching-conversation-repository.md) — where
  `retrieveAttachment` and the `AttachmentStore` param are wired; conceptually this ticket's home for "the
  host-bound wrapper is where per-host storage belongs," alongside the thread-row cache it already keeps.
- [Conversation cache](conversation-cache.md) — the `noBackupFilesDir` reasoning and the hashed host
  directory layout `AttachmentStore` mirrors.
- DI: [Dependency injection § AttachmentStore and context-free thread destination
  containers](dependency-injection.md#attachmentstore-and-context-free-thread-destination-containers-899).
- [Relay diagnostic log](relay-log.md) — the `RelayLog.d` calls this leg makes, and the JVM
  capturing-sink test requirement.
- Desktop precedent: `pyrycode-desktop` `docs/knowledge/features/attachment-retrieval.md` and
  `attachment-reassembly-and-store.md` — the same failure set, the same stall figure, the same chunk
  bound.
