# #1327 — Show a large upload's progress on its tile

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `attachmentsSending`, `sendWithAttachments`, `upload` — where the send runs and the callback gets passed.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `uploadAttachment(..., onProgress)` — #1326's callback, reports every chunk, no threshold.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt` → `ComposerAttachmentStrip`, `AttachmentItem` — the spinning indicator and the "Sending" state description.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen(attachmentsSending)` — the pass-through.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `attachmentsSending` collection.
- `app/src/test/.../ThreadViewModelAttachmentTest.kt` → `RecordingRepository` — fake to extend so it drives `onProgress`.
- `app/src/sharedTest/.../ComposerAttachmentStripTest.kt` → `Screen`, `whileSending_theStripSaysSo_andOffersNoRemove` — the screen test to sit beside.
- Desktop: `uploadProgressPercent` (`attachmentUploadCopy.ts`), `ATTACHMENT_PROGRESS_MIN_CHUNKS = 8` and its gate in `driveUpload` (`src/main/attachmentUpload.ts`), copy `Uploading… N%`.

In-flight overlap: #1325 restructures `upload`'s result handling, #1311/#1321/#1341/#1359 touch `ThreadScreen`/`MainActivity`. None is a dependency; my edits there are additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=390-7181 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The tile's 20dp primary-coloured 2dp-stroke indicator over the top-trailing corner stays as drawn; only its fill switches from indeterminate spin to a determinate arc at the upload's percentage. (Figma MCP was not authorised in this session; the ticket states the visual contract explicitly and nothing else changes.) The determinate indicator keeps the indeterminate's transparent track so its drawn look is unchanged apart from the fill.

## Context

A long upload shows only a spinner. #1326 added the per-chunk callback; mobile applies desktop's threshold and figure.

## Design

- New file `AttachmentUploadProgress.kt` in `ui/conversations/thread/`:
  - `data class AttachmentUploadProgress(val key: Long, val percent: Int)` — the uploading entry's key and its figure.
  - `const val ATTACHMENT_PROGRESS_MIN_CHUNKS = 8`.
  - `fun uploadProgressPercent(sentChunks: Int, totalChunks: Int): Int` — desktop's: 0 for `totalChunks <= 0`, else `floor(sent * 100 / total)` clamped to 0..100 (Long arithmetic, no overflow).
  - `fun attachmentUploadProgress(key: Long, sentChunks: Int, totalChunks: Int): AttachmentUploadProgress?` — `null` below the threshold.
- `ThreadViewModel`: `private val _attachmentUploadProgress = MutableStateFlow<AttachmentUploadProgress?>(null)`, exposed as `attachmentUploadProgress: StateFlow<AttachmentUploadProgress?>`. `upload` passes `onProgress = { sent, total -> _attachmentUploadProgress.value = attachmentUploadProgress(entry.key, sent, total) }` and sets it to `null` once `uploadAttachment` returns (stored or failed). `sendWithAttachments`' `finally` also clears it, covering a throw or cancellation mid-upload.
- `ComposerAttachmentStrip(..., uploadProgress: AttachmentUploadProgress? = null)`: while `sending`, the tile whose key matches gets `CircularProgressIndicator(progress = { percent / 100f }, ...)` with the same size, stroke, colour and a transparent track; every other tile keeps the indeterminate one. The strip's state description is `Uploading… N%` (new string `thread_attachments_uploading`, `%1$d`) when `sending && uploadProgress != null`, else `Sending`. No live region, so nothing is announced per chunk.
- `ThreadScreen(attachmentUploadProgress: AttachmentUploadProgress? = null)` forwards it to both strip call sites; `MainActivity` collects it beside `attachmentsSending`.

## State + concurrency model

All writes happen on `viewModelScope` (Main.immediate) or from the repository's callback inside the upload coroutine; `MutableStateFlow` is thread-safe either way. The figure lives inside one `uploadAttachment` call: written by its callback, cleared on its return and by the send's `finally`.

## Error handling

No new failure modes. A failed upload or thrown send clears the figure exactly as it clears `attachmentsSending`.

## Testing strategy

- Unit (`AttachmentUploadProgressTest`): threshold (7 → null, 8 → figure), floor (1/3 → 33), clamp (sent > total → 100, negative → 0), non-positive total → 0.
- `ThreadViewModelAttachmentTest`: `RecordingRepository` gains a `progress` hook that drives `onProgress` while the upload is gated; asserts the figure for a 10-chunk upload carries that entry's key, a 4-chunk upload publishes nothing, the next file starts from its own chunks, and the figure is `null` after a stored, failed and thrown send.
- `ComposerAttachmentStripTest` (sharedTest, Robolectric): with `sending = true` and a progress for one key, the strip's state description reads `Uploading… 40%`, that tile's indicator has `ProgressBarRangeInfo(0.4f, 0..1)` and the other's is indeterminate.

## Open questions

None.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md` under "Composer pending attachments" with the progress figure, its 8-chunk threshold and its lifetime.

## Revisions

- 2026-10-01, implementation: `ThreadScreen` mounts `ComposerAttachmentStrip` once, not at two call sites; the other `sending = attachmentsSending` there belongs to `ThreadInputBar` and is unchanged. The screen test drives the figure through `ThreadScreen`, so it also covers that pass-through.
