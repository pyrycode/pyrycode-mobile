# #933 — Pick files and images into the composer's attachment strip

## Files read

- `ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore.addAttachment` / `removeAttachment` — per-pair pending list, refusal outcomes (`TOO_LARGE`, `TOO_MANY`) checked inside the store's `update {}`.
- `ui/conversations/thread/PendingAttachment.kt` → `PendingAttachment`, `AttachmentAddOutcome`, `clampProviderText` — the strip's row type; `key` is the stable list key and remove handle; `toString` is redacted.
- `ui/conversations/thread/AttachmentReader.kt` → `isForeignContentUri` — the content-URI trust boundary #932 applies at send time; reused at pick time and before a thumbnail load.
- `ui/conversations/thread/ThreadViewModel.kt` → `pendingAttachments`, `addAttachment`, `removeAttachment`, `sendMessage`, `sendWithAttachments`, `sessionSettingsErrors` — the data path this ticket renders; the one-shot `Channel` → `receiveAsFlow` idiom the refusal notice follows.
- `ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall` — swallows send failures, so the in-flight flag must clear in a `finally`.
- `ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar` — send-button enablement and the Stop/Send rule.
- `ui/conversations/thread/ThreadComposerFooter.kt` → `ThreadComposerFooter` — the `Input footer` row; KDoc says the attachment segment belongs to another ticket (this one).
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` bottomBar `Column` (status area → input field → footer), its `SnackbarHostState` and the `LaunchedEffect(flow) { collect { showSnackbar } }` idiom.
- `MainActivity.kt` → the `Routes.CONVERSATION_THREAD` destination — collects `vm.draft` and binds `vm::onDraftChange`; the new flows bind the same way.
- `docs/knowledge/features/attachment-upload.md`, `thread-screen.md` § Composer pending attachments — the send contract (#932): retry keeps acknowledged ids, entries added mid-send survive, bytes read only at send time.
- `app/src/test/.../ThreadViewModelAttachmentTest.kt` — `RecordingRepository` / `FakeReader` fakes the new VM tests extend.
- `app/src/sharedTest/.../ThreadScreenRePairTest.kt` — minimal Robolectric `ThreadScreen` harness to mirror.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

`Input area` → `Attachment area` (390:7136) is a row of 45 × 60 `Input attachment` tiles at a 12dp gap between the status area and `Input large`. An image tile is the picture cropped to fill, 6dp corners; the `File` tile is a 45 × 60 outlined page with a folded top-trailing corner (stroke `Schemes/Inverse Primary`) and a centred body-small-emphasized type label ("PDF") in `Schemes/Inverse Primary`. Each tile carries a 20dp circle-x remove icon (disc `Schemes/Primary`, glyph cut-out showing `Schemes/On Primary`) overlapping its top-trailing corner by 5dp. `Input footer` → `Attachment` (115:3654) is an 11 × 12 paperclip in `Schemes/Primary` at the footer's trailing end.

Assets: the three Figma vectors (paperclip, file outline, circle-x) are reproduced as single-path vector drawables tinted with the role tokens above; the circle-x's inner disc is drawn as an `onPrimary` 14dp circle behind the tinted glyph.

Deliberate deviations: (1) the Status-sheet opener (not in the design, #808) keeps the very end of the footer; the paperclip sits immediately before it. (2) Each strip item is laid out 50 × 65 (tile at bottom-start, icon at top-end) with a 7dp gap, so the overlapping remove icon is not clipped by the scrolling row; tile pitch stays 57dp as in the design, the strip is 5dp taller than the design's 60dp.

## Context

#932 landed the data path: `ComposerDraftStore` keeps per-`(serverId, conversationId)` pending attachments, `ThreadViewModel.addAttachment` / `removeAttachment` edit them and `sendMessage` uploads them. Nothing lets the user choose a file or see them. This ticket adds the picker, the strip, send enablement and the in-flight state. No ADR needed.

## Design

### Picker — new `ui/conversations/thread/AttachmentPicker.kt`

- `data class PickedAttachment(uri: String, displayName: String, mimeType: String, size: Long?)` with a redacted `toString` (size only), like `PendingAttachment`.
- `@Composable fun rememberAttachmentPicker(onPicked: (List<PickedAttachment>) -> Unit): () -> Unit` — `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())`, launched with `arrayOf("*/*")` (one system picker covering images and every other file, multi-select, no storage permission). An empty result (cancel) calls nothing. Otherwise it describes each URI on `Dispatchers.IO` in a `rememberCoroutineScope` and calls `onPicked` once, in the order the picker returned them.
- `internal fun describePickedAttachment(resolver: ContentResolver, uri: Uri, ownPackage: String): PickedAttachment?` — `null` for a URI `isForeignContentUri` refuses (dropped, logged by static code only). Otherwise queries `OpenableColumns.DISPLAY_NAME` / `SIZE` (size `null` when absent or negative; any provider exception → name fallback, size `null`), `resolver.getType` (fallback `application/octet-stream`), display-name fallback `uri.lastPathSegment` then a fixed "file". No bytes read.
- No `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*`; no manifest change.

### ViewModel — `ThreadViewModel`

- `fun addPickedAttachments(picked: List<PickedAttachment>)` — calls the existing `addAttachment` for each in order; counts `TOO_LARGE` and `TOO_MANY`; when either is non-zero sends one `AttachmentRefusal(tooLarge: Int, tooMany: Int)` to a new buffered channel.
- `val attachmentRefusals: Flow<AttachmentRefusal>` — `receiveAsFlow()`, same idiom as `sessionSettingsErrors`. Counts only; no name, URI or type.
- `val attachmentsSending: StateFlow<Boolean>` — set `true` synchronously in `sendWithAttachments` before the launch, cleared in a `finally` inside the guarded block (so success, failure and swallowed throws all clear it). `sendMessage` returns early while it is `true`, so a second tap cannot upload the same snapshot twice.

### Strip — new `ui/conversations/thread/ComposerAttachmentStrip.kt`

`@Composable fun ComposerAttachmentStrip(attachments: List<PendingAttachment>, sending: Boolean, onRemove: (Long) -> Unit, modifier: Modifier = Modifier)`

- `LazyRow` keyed by `PendingAttachment.key`, testTag `ATTACHMENT_STRIP_TEST_TAG`. While `sending`, the row's `stateDescription` is the "Sending" string.
- Tile: image MIME (`image/*`) → thumbnail via `ContentResolver.loadThumbnail(uri, tile-size px, null)` on `Dispatchers.IO` in `produceState`, only for a URI `isForeignContentUri` accepts; any failure → file tile. `ContentScale.Crop`, 6dp clip.
- File tile: the outlined page drawable plus a type label derived by `internal fun attachmentTypeLabel(displayName: String): String?` — the extension after the last `.`, 1–4 letters/digits, upper-cased, else `null` (then the fixed "File" string). One line, `TextOverflow.Clip` inside the 44dp label width.
- The tile's merged semantics carry the display name as its content description (text only, never logged); the visible label never shows the raw name, so it cannot overflow the tile.
- Remove control: 20dp circle-x at the item's top-end, `clickable(role = Button)`, content description "Remove <name>", calls `onRemove(key)`. While `sending` the remove control is replaced by a 20dp `CircularProgressIndicator` and tiles draw at the footer's pending alpha 0.55.

### Input bar — `ThreadInputBar`

New params `hasAttachments: Boolean = false`, `sending: Boolean = false`.
- `stopping = isBusy && text.isBlank() && !hasAttachments` — with attachments the button is Send.
- `enabled = stopping || (!sending && (text.isNotBlank() || hasAttachments))`.

### Footer — `ThreadComposerFooter`

New param `onAttach: () -> Unit = {}`. A paperclip `Box` (32dp touch, 12dp glyph tinted `primary`, content description "Attach files") before the Status opener. KDoc line about the attachment segment updated.

### Screen — `ThreadScreen`

New params (defaulted, so existing tests are untouched): `attachments: List<PendingAttachment> = emptyList()`, `attachmentsSending: Boolean = false`, `onAttachmentsPicked: (List<PickedAttachment>) -> Unit = {}`, `onRemoveAttachment: (Long) -> Unit = {}`, `attachmentRefusals: Flow<AttachmentRefusal> = emptyFlow()`.
- `val openPicker = rememberAttachmentPicker(onAttachmentsPicked)`; footer `onAttach = openPicker`.
- Strip mounted between `ThreadStatusArea` and `ThreadInputBar` only when `attachments` is non-empty.
- `ThreadInputBar(hasAttachments = attachments.isNotEmpty(), sending = attachmentsSending)`.
- `LaunchedEffect(attachmentRefusals, snackbarHostState)` collects and shows one snackbar per non-zero count (plurals `thread_attachments_too_large`, `thread_attachments_too_many`), fixed local text with the count only.

### Wiring — `MainActivity`

Collect `vm.pendingAttachments` / `vm.attachmentsSending` with `collectAsStateWithLifecycle`; bind `onAttachmentsPicked = vm::addPickedAttachments`, `onRemoveAttachment = vm::removeAttachment`, `attachmentRefusals = vm.attachmentRefusals`.

## State + concurrency model

- Picker metadata query: `rememberCoroutineScope` + `withContext(Dispatchers.IO)`, cancelled with the screen's composition. A result arriving after the screen left is dropped with it.
- Thumbnail: `produceState(key = uri)` on IO, cancelled when the tile leaves composition.
- `attachmentsSending`: `MutableStateFlow` on the VM, main-thread writes only (`sendMessage` runs on main; the `finally` runs on `viewModelScope`'s `Main.immediate`).
- Chat switching needs no new state: each destination's VM reads its own pair from the app-scoped store, as `draft` does.

## Error handling

- Cancelled picker → no call, draft unchanged.
- Refused add (too large by reported size, past 32) → that item not added, the rest added in order, a snackbar with the count.
- Unresolvable metadata → added with fallback name/type and unknown size (send-time read bounds the bytes).
- Own-app / non-content URI → dropped at pick time.
- Thumbnail failure → file tile.
- Send/upload failure → unchanged #932 behaviour (text and strip stay); `attachmentsSending` returns to `false` so the tiles regain remove controls and Send re-enables.

Logging: `RelayLog.d` static codes only — `event=composer_attachment_pick count=<n>`, `event=composer_attachment_pick outcome=refused_uri`. Never a URI, name, type or thumbnail.

## Testing strategy

Unit (`app/src/test`):
- `ThreadViewModelAttachmentTest` additions: `addPickedAttachments` keeps order and emits one refusal with both counts when one item is too large and the draft is full; no emission when all added; `attachmentsSending` is `true` while the upload is suspended and `false` after success and after a failed upload; a second `sendMessage` while sending uploads nothing more.
- `AttachmentPickerTest`: `attachmentTypeLabel` (`report.pdf` → `PDF`, `archive.tar.gz` → `GZ`, `noext` / `x.toolongext` / `a.p f` → `null`).

Compose (`app/src/sharedTest`, Robolectric) — `ComposerAttachmentStripTest`:
- Strip absent with no attachments; tiles render in the given order (by content description); a non-resolvable image falls back to the file tile's label.
- Tapping one tile's remove calls `onRemove` with only that key.
- While sending: no remove controls, strip state description "Sending".
- `ThreadScreen`: Send enabled with attachments and blank text; disabled while sending.
- Paperclip opens the picker and a picked result reaches `onAttachmentsPicked` in order; a cancel (empty result) calls nothing — driven by a fake `ActivityResultRegistry` via `LocalActivityResultRegistryOwner`.
- A refusal emission shows the snackbar text.
- Chat switching: two `ThreadViewModel`s over one `ComposerDraftStore`, `ThreadScreen` rendered from whichever is current; switching shows each chat's own strip.

No rung-3 scenario here: live attachment exchange between desktop and phone is #674's (named in the ticket).

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-screen.md` § Composer pending attachments still says "no picker, no attachment strip yet"; `attachment-upload.md` § Composer draft names #670 as the unbuilt UI; `thread-composer-footer.md` should record the paperclip; `thread-input-bar.md` the attachment send rule.

## Open questions

- Whether `OpenMultipleDocuments` returns picks in selection order on every provider — the contract returns the `ClipData` order; the strip preserves whatever order the picker hands back.

## Security review

**Verdict:** PASS

The adversary here is another app: whichever documents provider backs the pick controls the URI, the display name, the reported size, the MIME type and the bytes a thumbnail decodes.

**Findings:**

- [Trust boundaries] No findings. One pick-time boundary, `describePickedAttachment`: a URI `isForeignContentUri` refuses (`file://`, `android.resource://`, this app's own provider, including behind a `userId@` prefix) is dropped before the resolver is queried. Name and type are clamped by the store's `clampProviderText` on entry. The reported size only pre-screens; the #932 send-time `readBounded` re-bounds the bytes.
- [Trust boundaries] SHOULD FIX, applied in Phase B: the thumbnail load is a second read of provider data, so it must apply `isForeignContentUri` itself instead of trusting that every entry came through the picker. Otherwise a stray own-provider URI in the store would render this app's private image in the strip.
- [Trust boundaries] No findings. Untrusted names render only as a content description and as `attachmentTypeLabel`'s 1–4 upper-cased letters or digits. The snackbar carries counts only. Nothing untrusted reaches a path, URL, log line or cache key.
- [Tokens] Not applicable: no token, key or credential is created, read or stored.
- [File / storage] No findings. URIs are never turned into file paths. Thumbnails exist only as in-memory bitmaps and are never written. `takePersistableUriPermission` is not called, so no lasting grant is kept. Drafts stay heap-only, per #789 and #932.
- [Android surface] No findings. The only intent is `ACTION_OPEN_DOCUMENT`, launched outward through the Activity Result API. No component is exported and no permission is added: no `READ_EXTERNAL_STORAGE`, no `READ_MEDIA_*`, no manifest change.
- [Crypto] Not applicable.
- [Network & I/O] No findings. The upload path is unchanged: the #829 8 MB local bound, the 32-id limit and the per-connection upload lock.
- [Network & I/O] SHOULD FIX, applied in Phase B: a hostile provider can block `loadThumbnail` or the metadata `query`. Both run on `Dispatchers.IO`, off the main thread. The thumbnail passes a `CancellationSignal` that is cancelled when its tile leaves composition, so a stalled provider does not hold the load forever.
- [Logs] No findings. `RelayLog.d` logs static codes and counts only. `PickedAttachment.toString` is redacted in the same way as `PendingAttachment.toString`.
- [Concurrency] No findings. `attachmentsSending` is written only on the main thread and cleared in a `finally`. The flag gates `sendMessage`, so a double tap cannot upload one snapshot twice. Remove is not offered while a send is in flight. Coroutines in picker and thumbnail work belong to the composition and are cancelled with it.
- [Threat model] OUT OF SCOPE: the thread window is not `FLAG_SECURE`, so a thumbnail is as visible to screenshots and recents as the message text already is. No ticket covers this; the same holds for the thread's existing content.
- [Threat model] OUT OF SCOPE: platform image decoding of a hostile file happens inside `ContentResolver.loadThumbnail`, the platform decoder the ticket names. This ticket adds no decoder library.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
