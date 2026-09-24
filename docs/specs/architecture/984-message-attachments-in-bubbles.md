# #984 — Attachments in message bubbles, and retrieving missing files

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `Message.attachments`, `MessageAttachment` — #983's references: id always set, name and MIME `null` when unknown; the KDoc's inert-text rule governs every render here.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` → `AttachmentRetrievalResult` (`Retrieved`, `NotFound`, `TooLarge`, `Invalid`, `Unavailable`) — what the ViewModel folds into view state.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `retrieveAttachment` — default-throws on repositories that do not keep files (the demo fake), so the ViewModel must catch.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `retrieveAttachment` — the host-bound path; the store single-flights and returns a kept file with no request.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`, `MessageContainer`, `UserMessageBubble`, `AssistantMessage` — where the attachment slot goes, between the body and the meta row.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` → `META_CONTENT_ALPHA` — the in-bubble substitute for Figma's `inverse-primary` role (#644), reused for the file row.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt` → `FileTile`, `rememberThumbnail`, `attachmentTypeLabel`, `isForeignContentUri` use — the nearest shipped file glyph, type label and off-main-thread thumbnail pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentReader.kt` → `AttachmentReader`, `ContentResolverAttachmentReader`, `isForeignContentUri` — already DI-bound; gains the read-access probe.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore`, `clearHost`, `clearConversation` — the app-scoped, host-keyed store that outlives one thread destination.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendWithAttachments`, `upload`, `attachmentsSending` — the send path that knows which URI became which id; sibling flows are exposed beside `state` because its `combine` is at arity.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, the `LazyColumn(reverseLayout = true)` item body calling `MessageBubble`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CONVERSATION_THREAD` destination binding `ThreadViewModel` flows to `ThreadScreen`.
- `docs/knowledge/features/message-bubble.md` § "Fill vs. hug", § "Token mapping" — a `fillMaxWidth` inside the bubble pins it to the lane; `inverse-primary` maps to content colour at `META_CONTENT_ALPHA`.
- `docs/knowledge/features/attachment-retrieval.md` § Result types, § Host store — `displayName` / `mimeType` are sanitised hints; `NotFound` is final, the rest are failures.

In-flight overlap check: no other `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inside `Message area` (`533:1956`), each `Message` column (px 20, py 16, gap 12) has a `Slot` between its body text and its meta row. The user message after `Session reset` (`I533:1956;132:4567`) fills it with a 160 × 160 image, 6dp corners, cropped to cover. The assistant message below (`I533:1956;132:4608`) fills it with `File field` (`132:4605`): a row with a 12dp gap holding the 45 × 60 `file-solid-full` page glyph with the type label (`M3/body/small-emphasized`, centred, 44 wide, on the fold) and the file name in `M3/body/small` on one line. Both glyph and text use `Schemes/inverse-primary`, which inside a bubble maps to `LocalContentColor` at `META_CONTENT_ALPHA`, the #644 substitution the meta row already makes.

## Context

`Message.attachments` (#983) carries references into the thread, but `MessageBubble` still draws text only, so an attachment-only message is an empty bubble. This ticket draws each reference in its bubble and fetches missing bytes through `retrieveAttachment` (#899). Opening and saving are #985. The live desktop↔phone exchange is proven by #674, so no rung-3 scenario lands here. No ADR needed.

## Design

### View state (new file `ui/conversations/components/MessageAttachments.kt`)

```kotlin
sealed interface AttachmentSource {           // toString redacted: never a URI or path
    data class Original(val uri: String)      // the phone's own picked file, still readable
    data class Kept(val file: File)           // AttachmentStore's retained copy
}
sealed interface AttachmentViewState {
    data object Loading
    data class Ready(val source: AttachmentSource, val displayName: String?, val mimeType: String?) // toString redacted
    data object NotFound                      // final; no retry
    data object Failed                        // TooLarge, Invalid, Unavailable, or a throw; retryable
}
```

An id missing from the map renders as `Loading`. Shown name: the reference's non-blank `displayName`, otherwise `Ready.displayName` if non-blank, otherwise the generic `thread_attachment_unnamed` label. MIME follows the same order. An attachment is image-kind when that MIME starts with `image/`. The MIME is only a hint that picks a layout. It never picks a handler.

### Composables (same file)

- `MessageAttachments(attachments, states, onShown, onRetry, modifier)` — a `Column` (gap `BubbleContentSpacing`) in reference order, keyed by attachment id.
- One `MessageAttachmentItem` per reference. `LaunchedEffect(id) { onShown(id) }` runs when the item is composed, so it fires only for items on screen plus the `LazyColumn`'s prefetch.
  - **Image-kind:** a fixed 160 × 160 `BubbleShape` box, used while loading, while decoding and once decoded, so a late thumbnail does not change the item's size. `Ready` decodes on `Dispatchers.IO` through `LocalAttachmentThumbnailDecoder` (below). When the decode fails, or the state is `Failed` or `NotFound`, the item falls back to the file row.
  - **File row** (`File field`): the `ic_attachment_file` glyph, 45 × 60, with the `attachmentTypeLabel` label, then a `Column` with the name (`bodySmall`, `maxLines = 1`, `TextOverflow.MiddleEllipsis`, `Modifier.weight(1f, fill = false)` so a long name never widens the bubble) and a status line. The status line is "Loading…" for Loading, "File not found" for NotFound, and "Couldn't load file" plus a `TextButton` "Retry" for Failed. Ready has no status line.
- `fun interface AttachmentThumbnailDecoder { suspend fun decode(source: AttachmentSource, sizePx: Int): ImageBitmap? }`, provided through `internal val LocalAttachmentThumbnailDecoder = staticCompositionLocalOf { PlatformThumbnailDecoder(context-free) }`. The platform implementation uses `ImageDecoder` on `Dispatchers.IO`. `Original` is decoded only when `isForeignContentUri` passes, as the composer's `rememberThumbnail` does. `Kept` is decoded with `ImageDecoder.createSource(file)`. The target size comes from `thumbnailTargetSize(width, height, sizePx)` in the header listener. Any exception, or a non-positive header size, returns `null`. A test or preview can provide a fake decoder.
- `internal fun thumbnailTargetSize(width: Int, height: Int, sizePx: Int): IntSize?` — `null` for non-positive dimensions; otherwise a downscale that never upscales, scaled so the short side covers `sizePx` and the long side is capped at `4 × sizePx`, each side at least 1. The bytes are attacker-shaped, so a 10⁶ × 160 header cannot allocate hundreds of MB.

### `MessageBubble.kt`

`MessageBubble` gains `attachmentStates: Map<String, AttachmentViewState> = emptyMap()`, `onAttachmentShown: (String) -> Unit = {}`, `onRetryAttachment: (String) -> Unit = {}`, and passes them to the two bubble roles. `MessageContainer` draws `MessageAttachments` after `body()` when `message.attachments` is non-empty. When a message has attachments and blank content, the user and finalized-assistant bodies emit nothing, so no empty text block renders. A text-only message renders exactly as before.

### ViewModel (`ThreadViewModel.kt`)

- `val attachmentStates: StateFlow<Map<String, AttachmentViewState>>`, a sibling flow beside `pendingAttachments`.
- `fun onAttachmentShown(attachmentId: String)` starts a load only when the id has no state. `fun onRetryAttachment(attachmentId: String)` starts one only when the state is `Failed`. Both claim the id by compare-and-set inside `update`, so two calls start one load.
- The load runs in `viewModelScope` and follows a fixed order. First, `draftStore.sentOriginal(serverId, conversationId, id)` is checked; if it returns a URI and `attachmentReader.canRead(uri)` is true, the state becomes `Ready(Original(uri), null, null)` and nothing is retrieved. Otherwise the result of `repository.retrieveAttachment(conversationId, id)` is mapped: `Retrieved` becomes `Ready(Kept(file), displayName, mimeType)`, `NotFound` becomes `NotFound`, and `TooLarge`, `Invalid` and `Unavailable` become `Failed`. Any non-cancellation throw also becomes `Failed`.
- `sendWithAttachments` calls `draftStore.recordSentOriginals(serverId, conversationId, id → entry.uri)` once every upload has succeeded and **before** `repository.sendMessage`. The confirmed row can render during the send's suspension, so the originals must be recorded first. An id recorded for a send that then fails is harmless: a retry reuses the same ids.

### `ComposerDraftStore.kt`

- `fun recordSentOriginals(serverId, conversationId, originals: Map<String, String>)` and `fun sentOriginal(serverId, conversationId, attachmentId): String?`, backed by a private host → conversation → id → URI map. `clearHost` and `clearConversation` drop the entries, as they already do for drafts. The store is app-scoped, so "sent in this app session" holds across leaving and reopening the thread; the entries end with the process, like the picker grant.

### `AttachmentReader.kt`

`AttachmentReader` gains `suspend fun canRead(uri: String): Boolean = false`. It is a default method, so the `fun interface` lambdas in tests and the inert default stay valid. `ContentResolverAttachmentReader` overrides it on `io`: it applies the same `isForeignContentUri` refusal, then `openInputStream(uri)?.use { true } ?: false`, bounded by `withTimeoutOrNull(CAN_READ_TIMEOUT = 5.seconds)`, which also gives `false`. Every exception is dropped unread and gives `false`.

### `ThreadScreen.kt` / `MainActivity.kt`

`ThreadScreen` gains the same three parameters, defaulted, and passes them to `MessageBubble` in the `MessageItem` arm. `MainActivity` binds `vm.attachmentStates`, `vm::onAttachmentShown` and `vm::onRetryAttachment`.

### Scroll stability

The list is `reverseLayout = true` and keeps its first visible item (the newest end) anchored by key, so growth above the anchor never moves what the reader is looking at. On top of that, an image-kind item holds the 160 × 160 box from its first frame to its last, so a thumbnail landing does not resize the row. Two cases still change the size, both visible to the reader: a reference with no MIME hint that retrieval reveals as an image, and a decode failure that falls back to the file row. The fallback is the AC's own requirement.

## State + concurrency model

One new `MutableStateFlow` map in the ViewModel. It is written only through `update` on `Dispatchers.Main.immediate`. Each load is a `viewModelScope.launch` and is cancelled with the ViewModel. A cancelled retrieval leaves the `AttachmentStore` single-flight clean: a cancelled leader hands off, per #899. Leaving the screen cancels the load, and the next opening starts from an empty map, which is cheap because the store returns the kept file without a request. The one-retrieval-per-connection `Mutex` in `AttachmentRetrievals` already serialises fetches, and loads start only for composed items. Thumbnail decodes are `produceState` on `Dispatchers.IO`, cancelled when the item leaves composition.

## Error handling

`Failed` has a retry, and `NotFound` is terminal. A failed decode shows the file row and never an error. Logging uses `RelayLog.d` only and records the id and a static outcome: `event=thread_attachment_load id=<A> outcome=original|retrieved|not_found|failed`. Nothing logs a name, a URI, a path, a MIME type or an exception message.

## Testing strategy

- `test/.../thread/ThreadViewModelAttachmentRetrievalTest` (new):
  - shown → Ready(Kept) with retrieved hints, and one retrieval;
  - a repeat `onAttachmentShown` does not retrieve again;
  - NotFound, and retry ignored;
  - Unavailable → Failed, then retry retrieves again and becomes Ready;
  - a throwing repository → Failed;
  - a sent original that is readable → Ready(Original) and `retrieveAttachment` never called;
  - an unreadable original → retrieval;
  - `sendWithAttachments` records originals before the send reaches the repository;
  - the logs carry no URI or name.
- `test/.../thread/ComposerDraftStoreTest`: record and look up; `clearHost` and `clearConversation` drop originals.
- `test/.../components/ThumbnailTargetSizeTest` (new): downscale-only, short side covers, long side capped, non-positive → null, minimum 1.
- `sharedTest/.../components/MessageAttachmentsTest` (new, Robolectric), rendering `MessageBubble` with a fake `LocalAttachmentThumbnailDecoder`:
  - image loaded (the image node with the name as its description);
  - the image slot is 160 × 160 both while loading and once loaded;
  - a decode failure falls back to the file row;
  - a file row labelled with its name;
  - a long name keeps the bubble inside the lane;
  - an unnamed reference shows the generic label;
  - loading;
  - failed with retry, where a tap calls `onRetry(id)`;
  - not found without retry;
  - references in order;
  - an attachment-only message has no empty text node;
  - `onShown` receives the id.

## Documentation handoff

The ticket names none. Pending for the documentation stage: `docs/knowledge/features/message-bubble.md` (the attachment slot, file row and image states; the `MessageBubble` parameters) and `docs/knowledge/features/attachment-retrieval.md` § Related (#984 as the UI consumer).

## Open questions

- Does `TextOverflow.MiddleEllipsis` exist in this Compose BOM, and does it render under Robolectric? If not, use `Ellipsis`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Three untrusted inputs reach this UI. First, the reference hints: sanitised by `attachmentDisplayName` at #983's three entry points. Second, `Retrieved.displayName` and `mimeType`: sanitised by #899 and at most 255 UTF-8 bytes. Third, the image bytes. The hints render only as `Text`, `maxLines = 1`, and as a `contentDescription`. They are never a path, a handler choice, a key or a log field. The MIME hint only picks a layout, and a wrong hint ends in the decode-failure fallback.
- [Trust boundaries] MUST FIX (applied in plan): decoding attacker-shaped image bytes. `thumbnailTargetSize` downscales only and caps the output at `sizePx × 4·sizePx`, and `ImageDecoder`'s target size samples during decode. The file on disk is at most `AttachmentRetrievalLimit.MAX_BYTES`. A header with non-positive dimensions, or any exception, gives `null` and falls back to the file row.
- [Trust boundaries] No findings: `AttachmentSource.Original` holds a URI the user picked from another app's provider. Both the `canRead` probe and the decode refuse anything `isForeignContentUri` rejects, so a hostile provider cannot turn a `file://` or app-own URI into a thumbnail of this app's private files.
- [Tokens] No findings: no credentials are touched.
- [File / storage] No findings: nothing is written. `Kept.file` is the path `AttachmentStore` built from shape-checked ids under `noBackupFilesDir`. The UI reads it only through `ImageDecoder`, and it is never rendered, logged or passed to another app (opening is #985). `AttachmentSource` and `Ready` override `toString`.
- [Inter-process] SHOULD FIX (applied in plan): a hostile or stalled provider could hang `canRead` and hold the item in Loading forever. `canRead` is bounded by a 5 s timeout and then falls through to retrieval. A stalled thumbnail decode of an `Original` blocks one IO thread until the provider returns, because `ImageDecoder` takes no `CancellationSignal`. The user chose that provider, and the composer strip's thumbnail has the same exposure, so this is accepted.
- [Crypto] No findings: none used.
- [Network & I/O] No findings: retrieval starts only for composed items, and retry only on a tap. Each connection serialises fetches, and the #899 bound caps each one. There is no automatic retry loop.
- [Logs] No findings: one `RelayLog.d` line with the id and a static outcome; never a name, URI, path, MIME type or exception message.
- [Concurrency] No findings: the claim is a compare-and-set inside `MutableStateFlow.update`, and loads live in `viewModelScope`. Cancellation mid-retrieval is handled by the store's leader hand-off.
- [Threat model] OUT OF SCOPE: opening or sharing the file with another app, and choosing a viewer, are #985. A thumbnail visible in the recents screenshot has the same exposure as message text today.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24: Phase B departures

- **The image slot is square at up to 160dp; it is no longer a fixed 160 × 160.** The Robolectric screen is 320dp wide. There, the bubble's content width is 140dp, and `Modifier.size(160.dp)` gave a 140 × 160 slot. The slot is now `sizeIn(maxWidth = 160.dp, maxHeight = 160.dp).aspectRatio(1f)`: 160 × 160 at the 412dp reference width, and square at the bubble's width on a narrower screen. It never uses `fillMaxWidth`, which would pin the bubble to its lane (message-bubble.md § "Fill vs. hug"). The size is still fixed from loading to loaded, so a late thumbnail moves nothing, which is the point. `MessageAttachmentsTest` asserts `min(160dp, content width)`, square, and unchanged across the load.
- **`canRead` is bounded by a `CancellationSignal`, not by `withTimeoutOrNull`.** A provider call blocked in Binder never sees coroutine cancellation, so a plain timeout around `withContext(io)` would have waited for the call anyway. `ContentResolverAttachmentReader.canRead` passes a signal to `openAssetFileDescriptor(uri, "r", signal)`, which is the same open `openInputStream` performs. A deadline job cancels the signal after 5 s, or at once when the caller is cancelled. The security review's SHOULD FIX stands, implemented this way.
- **`META_CONTENT_ALPHA` is private to `MessageMetaRow.kt`**, so `MessageAttachments.kt` keeps its own `ATTACHMENT_CONTENT_ALPHA = 0.80f` with the same reasoning comment. `MarkdownText` already does the same, and this avoids an eighth production file.
- **`MessageContainer`'s `attachments` slot is defaulted to `{}`**, so the markdown preview that calls the container directly is unchanged.
- **Open question resolved:** `TextOverflow.MiddleEllipsis` exists in this BOM and renders one line under Robolectric native graphics. `MessageAttachmentsTest` therefore runs with `@GraphicsMode(NATIVE)`, which is also what lets its fake thumbnail be a real bitmap.
