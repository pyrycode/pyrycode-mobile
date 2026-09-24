# #932 — Pending attachments in the chat draft, uploaded on send

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore` — the per-pair text store this ticket extends; `clearHost` / `clearConversation` (#790) must drop attachments too.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadViewModel.sendMessage`, `onDraftChange`, `draft` — the post-send clear guard and the sibling-`StateFlow` exposure shape the attachment list copies.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall` — how a failed text send is "reported" today: swallowed inertly, draft left in place.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → three-argument `sendMessage`, `uploadAttachment` — the two calls the send path makes. Both default to `error(...)` (an `IllegalStateException`), which the guard already swallows.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt` → `AttachmentUploadLimit`, `AttachmentUploadResult` — the size bound and the sealed result.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `MessageAttachmentIds.MAX` — the 32-id bound.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread`, the `ComposerDraftStore` single — where the reader is bound and passed.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → the unpair hook calling `clearHost` — unchanged; it inherits attachment clearing through the store.
- `app/src/main/java/de/pyryco/mobile/ui/settings/DebugBundleDownload.kt` → `ArchiveDestination` — precedent for a small interface in `ui/` fronting `ContentResolver` work.
- `docs/knowledge/features/attachment-upload.md` — upload contract; its "Logging" and "Testing" sections: tests reaching `RelayLog.d` need a capturing `RelayLog.sink`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelRePairTest.kt` — sibling test-class shape (own setup, `RelayLog` sink) the new ViewModel test follows.

Overlap: `origin/feature/883` edits `AppModule.kt` (removes the literal destination) and `origin/feature/891` edits `ThreadViewModel.kt` (run-config arm). Neither touches the blocks this ticket changes; edits here stay additive.

## Design source

**Figma:** N/A — data path only; the picker and strip that render it land in a follow-up ticket (split from #670).

## Context

The composer can type and send text. The repository can upload a file (#829) and send a message naming uploaded ids (#830). Nothing joins them. This ticket adds the per-chat pending-attachment list beside the draft text, the ViewModel surface to add / remove / expose it, and the upload-then-send path. No UI.

## Design

### Model — `ui/conversations/thread/PendingAttachment.kt` (new)

```kotlin
class PendingAttachment(          // data class with a redacting toString
    val key: Long,                // store-minted, unique per store; the strip's list key and the remove handle
    val uri: String,              // content URI as a string — never a path
    val displayName: String,
    val mimeType: String,
    val size: Long?,              // provider-reported, null when unknown; untrusted
    val attachmentId: String? = null, // set once the daemon acknowledged the upload
)

enum class AttachmentAddOutcome { ADDED, TOO_LARGE, TOO_MANY }
```

`toString()` prints only `key`, `size` and whether an id is held — no URI, name, MIME type or id (the `AttachmentChunkPlan` redaction posture). `String` for the URI keeps the type free of `android.net.Uri`.

`displayName` and `mimeType` are clamped to `PENDING_ATTACHMENT_TEXT_MAX_CHARS = 1024` chars at add time, never splitting a surrogate pair. 1024 chars is always at least 1024 UTF-8 bytes, well past the 255-byte wire cut `AttachmentChunkPlan` applies, so the clamp never changes what is uploaded; it only stops a hostile provider parking megabytes of name text in the heap.

### Store — `ComposerDraftStore` (modified)

A second `MutableStateFlow<Map<String, Map<String, List<PendingAttachment>>>>`, same host → conversation nesting, same "empty entries and buckets are absent" rule, every write through `update {}`.

- `val attachments: StateFlow<Map<String, Map<String, List<PendingAttachment>>>>`
- `fun attachmentsFor(serverId, conversationId): List<PendingAttachment>`
- `fun addAttachment(serverId, conversationId, uri, displayName, mimeType, size: Long?): AttachmentAddOutcome` — `TOO_LARGE` when `size != null && size > AttachmentUploadLimit.MAX_BYTES`; `TOO_MANY` when the pair already holds `MessageAttachmentIds.MAX`; else appends with a freshly minted key. The count check runs inside the `update` lambda so two concurrent adds cannot both pass at 31.
- `fun removeAttachment(serverId, conversationId, key: Long)` — filters one entry out, order preserved.
- `fun markUploaded(serverId, conversationId, key: Long, attachmentId: String)` — records the id on that entry if it is still present; no-op otherwise.
- `fun removeAttachments(serverId, conversationId, keys: Set<Long>)` — the post-send clear of exactly the sent entries.
- `clearHost` drops the host bucket of both maps; `clearConversation` drops the pair from both maps.

Keys are minted from an `AtomicLong` in the store. They are local handles only, never sent.

### Reader — `ui/conversations/thread/AttachmentReader.kt` (new)

```kotlin
fun interface AttachmentReader { suspend fun read(uri: String): AttachmentRead }
sealed interface AttachmentRead {
    class Bytes(val bytes: ByteArray) : AttachmentRead   // redacting toString
    data object TooLarge : AttachmentRead
    data object Unreadable : AttachmentRead
}
internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray?  // null once past maxBytes
internal fun isForeignContentUri(scheme: String?, authority: String?, ownPackage: String): Boolean
class ContentResolverAttachmentReader(resolver: ContentResolver, ownPackage: String, io: CoroutineDispatcher = Dispatchers.IO) : AttachmentReader
```

`readBounded` reads in fixed-size buffers and returns `null` as soon as the running total passes `maxBytes`, whatever size the provider reported. The Android implementation runs on `io`, opens `resolver.openInputStream(uri.toUri())`, closes it with `use`, maps a `null` stream or any `Exception` (FileNotFound, Security, IO, IllegalArgument) to `Unreadable` and `readBounded == null` to `TooLarge`. `CancellationException` is rethrown. No path conversion, no permission request.

Before opening, `isForeignContentUri` must hold, else `Unreadable` without touching the resolver: the scheme is exactly `content`, and the authority is non-empty and is neither `ownPackage` nor starts with `"$ownPackage."`. `ContentResolver.openInputStream` also opens `file://` and `android.resource://` URIs, and our own non-exported providers, with this app's identity. A URI handed back by another app's picker must not be able to make us upload our own private files.

### ViewModel — `ThreadViewModel` (modified)

- Constructor: `private val attachmentReader: AttachmentReader = AttachmentReader { AttachmentRead.Unreadable }` — defaulted, like the other seams, so the ten existing construction sites stay unchanged; production passes the real one.
- `val pendingAttachments: StateFlow<List<PendingAttachment>>` — mapped from `draftStore.attachments`, seeded from `attachmentsFor`, `Eagerly`, same as `draft`.
- `fun addAttachment(uri, displayName, mimeType, size: Long?): AttachmentAddOutcome` — delegates to the store; a refusal is returned synchronously for the UI and logged content-free.
- `fun removeAttachment(key: Long)`.
- `sendMessage(text)`:
  1. Snapshot `attachmentsFor(serverId, conversationId)`. Return if `text.isBlank()` and the snapshot is empty.
  2. Empty snapshot → today's path, unchanged: two-argument `sendMessage`, then the text clear guard.
  3. Otherwise, inside `launchGuardedRepoCall`: for each snapshot entry in order, use its `attachmentId` if held; else read bytes through `attachmentReader`, upload with `uploadAttachment(conversationId, bytes, displayName, mimeType)`, and on `Stored` call `markUploaded` and keep the id. Any read failure or `Failed` result logs a static outcome and returns from the block, leaving text and entries in place. Then three-argument `sendMessage(conversationId, text, ids)`. On success: the existing text guard, then `removeAttachments(snapshot keys)`.

The message is what the user tapped send on: the text parameter and the attachment snapshot. Entries added during the in-flight send are not in the snapshot, so `removeAttachments` leaves them. The bytes of one file are a local inside the loop body, so only one file's bytes are live at a time.

### DI — `AppModule.kt` (modified)

`single<AttachmentReader> { ContentResolverAttachmentReader(androidContext().contentResolver, androidContext().packageName) }`; `ThreadDestinationFactory.thread` takes an `attachmentReader` parameter and passes it to both `ThreadViewModel` constructions; the Koin binding passes `get()`.

## State + concurrency model

- Store writes go through `MutableStateFlow.update`, so adds, removes, marks and clears on any pair cannot lose one another.
- The send runs in `viewModelScope` via `launchGuardedRepoCall`; cancelled with the ViewModel. `uploadAttachment` serialises uploads on its connection itself.
- The reader switches to `Dispatchers.IO` for the blocking stream read; everything else is on `Main.immediate`, which is what keeps the existing text check-then-act safe.
- Retry after failure: acknowledged entries carry their `attachmentId` in the store, so the next send's snapshot skips them.

## Error handling

| Failure | Result | Draft |
|---|---|---|
| Size known and > 8 MB at add | `TOO_LARGE` returned | unchanged |
| 32 already held at add | `TOO_MANY` returned | unchanged |
| Read: null stream / exception | `Unreadable` → send stops | text + entries kept |
| Read: past 8 MB | `TooLarge` → send stops | kept |
| Upload `Refused` / `TooLarge` / `ReconnectRequired` | send stops | kept; earlier `Stored` ids kept |
| Upload or send throws (not connected, unwired, relay error) | swallowed by the guard, as today | kept |

The failure is reported as a failed text send is today: silently, with the draft left to resend. Logs are `RelayLog.d` with static codes only: `event=composer_attachment_add outcome=too_large|too_many`, `event=composer_attachment_send outcome=read_too_large|read_failed|upload_failed`.

## Testing strategy

Unit tests only (no operator-facing flow yet; the strip ticket and #674 prove it live).

- `ComposerDraftStoreTest` (extend): attachments per pair, including the same conversation id under two hosts; add refuses over-size and the 33rd; remove keeps order; `markUploaded`; `removeAttachments` removes only the named keys; `clearHost` and `clearConversation` drop attachments with text; redacting `toString`; name clamp keeps surrogate pairs whole.
- `AttachmentReaderTest` (new): `readBounded` returns exact bytes at 0, at the limit and across buffer boundaries, and `null` one byte past the limit, reading no more than limit + one buffer. `isForeignContentUri` accepts another app's `content` authority and refuses `file`, `android.resource`, a missing scheme or authority, our own package and its dotted sub-authorities, while accepting an authority that only shares a prefix without the dot.
- `ThreadViewModelAttachmentTest` (new, own capturing `RelayLog.sink`): exposes the current pair's list only; add/remove surface; send uploads only entries without ids, in order, and names exactly the snapshot's ids; success clears text and attachments; blank text with attachments sends; an upload failure or read failure keeps everything and keeps earlier ids, and a retry uploads only the rest; a thrown send keeps everything; attachments added during an in-flight send survive; logs carry no URI or name.

Existing `ThreadViewModelTest` send tests cover the unchanged text-only path.

## Open questions

- Should an entry whose read proved it too large be flagged for the strip? Not required by the AC; the send fails and the draft stays. Left to the strip ticket.

## Documentation handoff

Pending for the documentation stage: the ticket body names no documentation requirement. `docs/knowledge/features/attachment-upload.md` and the composer draft overview may gain a "composer consumer" note.

## Security review

**Verdict:** PASS (after one revision: the first draft opened any URI string the store held; the `isForeignContentUri` guard was added before this pass passed.)

**Findings:**

- [Trust boundaries] Two entry points from another app, each explicit. Metadata enters at `ComposerDraftStore.addAttachment` (name and MIME type clamped to 1024 chars, size treated as a hint only). Bytes enter at `ContentResolverAttachmentReader.read`, re-bounded by `readBounded` regardless of the reported size. The only daemon-authored value kept is the `Stored` id, which `AttachmentUploadTransfer` only settles when it equals the phone-minted UUID, so no daemon text is stored or rendered here.
- [Trust boundaries / IPC] MUST FIX, resolved in the design: `openInputStream` would open `file://`, `android.resource://` and this app's own providers with our identity, so a hostile document provider could hand back a URI naming our private files and have them uploaded. `isForeignContentUri` refuses everything but a `content` URI on a foreign authority, before the resolver is touched.
- [Tokens] No findings — no token is created or stored. Store keys are an `AtomicLong` counter, local handles never sent; attachment ids are minted by the repository (#829).
- [File / storage] No findings — nothing is written anywhere. The store is heap-only (the `ComposerDraftStore` posture), holds URIs and metadata only, and the bytes of one file live only inside one loop iteration. No URI is turned into a path.
- [IPC] OUT OF SCOPE — URI grant lifetime. A temporary read grant may lapse before send; the read then fails `Unreadable` and the draft is kept. Taking a persistable grant (or reading earlier) is the picker follow-up's decision.
- [Crypto] No findings — no primitive is used; the upload rides the existing Noise session.
- [Network & I/O] No findings — at most `MAX_BYTES` plus one buffer is read per file, and `uploadAttachment` keeps its own bound and per-connection serialisation.
- [Logs] SHOULD FIX, carried into Phase B: `FileNotFoundException` messages from a provider contain the URI. The reader catches and discards exceptions without reading their message, `launchGuardedRepoCall` never logs, and log lines use static codes only. `PendingAttachment` and `AttachmentRead.Bytes` redact `toString`, so a crash trace cannot print a URI, name or bytes. A ViewModel test asserts no URI or name reaches the captured log.
- [Concurrency] No findings — every store write is `update {}`; the 32-entry check runs inside the CAS lambda; the send snapshot plus key-based `removeAttachments` keeps entries added mid-send. Double-tapping send can upload the same files twice (the text path already double-sends the same way): OUT OF SCOPE, the strip follow-up owns disabling send while one is in flight.
- [Threat model] OUT OF SCOPE — a hostile provider can stall `read` forever; a blocking stream read is not cancellable, so that send hangs on one IO thread with the draft kept. Bounded to a provider the user picked; a read deadline would need an interruptible stream and belongs with the picker follow-up if observed. Hostile relay/daemon: nothing new is decoded or rendered.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

### 2026-09-24 — reader injected through the factory constructor

Driver: the merge of `main` into this branch, not a review finding. `main` added a line in the thread `viewModel` binding that calls `thread(handle, get(), get())`, and the dispatcher's merge check requires every line `main` added to survive. The design above adds an `attachmentReader` parameter to `ThreadDestinationFactory.thread`, which changes that call.

New contract: `ThreadDestinationFactory` takes `attachmentReader: Lazy<AttachmentReader>` as a constructor parameter, which `hostConversationModule` fills with Koin's `inject()`. `thread` keeps its pre-#932 signature and passes `attachmentReader.value` to both `ThreadViewModel` constructions. Because the value is lazy, the reader still resolves only when a thread is built. So containers without a `ContentResolver` can still build the factory for the settings, archive and literal destinations. The inert `AttachmentReader` overrides in `RelayConnectionFactoryTest` are unchanged. The `AttachmentReader` binding in `appModule` is unchanged.

### 2026-09-24 — test containers that build a thread bind an inert reader

Driver: verifier triage on PR #938. The lazy `inject()` keeps a container without `androidContext()` safe only while it never builds a thread. `NotificationTapNavigationTest` and `LiteralScreenNavigationTest` do build one, so `attachmentReader.value` resolved `ContentResolverAttachmentReader` and threw `MissingAndroidContextException`. Both containers now bind `AttachmentReader { AttachmentRead.Unreadable }` beside `InertAttachmentStore`, as `RelayConnectionFactoryTest` already did. No production change; the other `KoinApplication.init()` containers never reach a thread destination.
