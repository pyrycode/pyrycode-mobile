# #1069 — Save the markdown reader's note to the device

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`, `MarkdownReaderTopBar`, `MarkdownReaderMenu`, `MarkdownDocument` — the menu gains its sixth item; `document` is the text on screen; notices show in the reader's `snackbarHostState` through the `notices` map #1068 added.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `CreateAttachmentDocument`, `copyAttachment`, `rememberAttachmentActions`, `AttachmentNotice` — the thread's save flow this ticket reuses: the create-document contract, the copy that discards the document on any failure, and the pending-save-across-the-picker shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/SharedNoteFile.kt` → `sharedNoteFileName` — the note's last path component, sanitised, with the `note.md` fallback; reused as the suggested document name.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActionsTest.kt` → the `copyAttachment` tests — the pure-JVM shape the new write helper's tests follow.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt` → `theOverflow_opensTheFiveItems_inOrder`, `choose` — the menu-order test this ticket extends.
- `docs/specs/architecture/1068-markdown-reader-open-in-app.md` — the previous item's plan: dismiss-then-act, act on the `document` being drawn, logs carry lengths only.
- `docs/knowledge/features/markdown-reader-screen.md` § "Copy and refresh menu (since #1067)" — the menu's conventions; logs never carry the name, path or text.

Other in-flight branches (#878, #1021, #1044) touch only `strings.xml`, in other entries; this ticket appends one string beside the reader's.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958 (`Options overlay`); reader https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

The reader's overflow is the existing plain M3 `DropdownMenu` of text-only `DropdownMenuItem`s, built as `ThreadOverflowMenu` builds the thread's. This ticket appends one more text item, **Save to device**, after Open in another app, in the same style; no new tokens, icons or decorations. (`533:1958` still renders as an empty 81×144 component frame through the MCP screenshot, as #1068 recorded; `MarkdownReaderMenu` is the verified translation, and the new item copies it.)

## Context

The operator wants to keep a copy of a note outside the app (chosen 2026-09-24). The thread already saves an attachment through the system create-document picker (#985); this ticket reuses that contract and that copy so the reader's save behaves the same way: the document the picker creates is deleted when the write fails, and a cancelled picker is silent.

## Design

### `AttachmentActions.kt`

- `internal fun saveNoteText(text: String?, openOutput: () -> OutputStream, discard: () -> Unit): Boolean` — `null` text (lost with the process while the picker was open) discards the document without opening it and returns `false`; otherwise `copyAttachment` with the text's UTF-8 bytes as the input, so a failed write discards the document exactly as an attachment save does. Blocking; called on `Dispatchers.IO`.
- `@Composable internal fun rememberNoteSaver(onNotice: (AttachmentNotice) -> Unit): (MarkdownDocument) -> Unit` — the reader's save, bound to its composition:
  - One `rememberLauncherForActivityResult(CreateAttachmentDocument())`. `CreateAttachmentDocument` stays private to the file.
  - The returned function records the document's text in a plain `remember`ed holder (never `rememberSaveable`) and launches `Request(suggestedName = sharedNoteFileName(document.name), mimeType = "text/markdown")`.
  - The result callback takes the pending text and clears the holder. A `null` destination (cancelled) writes nothing, clears the text and notifies nothing. A picked destination writes via `saveNoteText` on `Dispatchers.IO` in `rememberCoroutineScope()`, with `openOutput = resolver.openOutputStream(destination, "wt")` and `discard = DocumentsContract.deleteDocument(resolver, destination)`, then notifies `SAVED` or `SAVE_FAILED`.
  - When the activity or process was recreated while the picker was open, the registry still delivers the picked URI to the re-registered launcher, but the holder is empty: `saveNoteText(null, …)` deletes the created document and the reader shows `SAVE_FAILED`. The name and text are never in saved state; the registry saves only its own key.
  - Logs `event=markdown_reader_save outcome=saved|failed|cancelled chars=<length, or -1 when lost>` — never the text, the name or the URI.

### `MarkdownReaderScreen.kt`

- `MarkdownReaderScreen` gets `val saveNote = rememberNoteSaver { notice -> scope.launch { snackbarHostState.showSnackbar(notices.getValue(notice)) } }` and passes `onSaveToDevice = { saveNote(document) }`, so the save acts on the `document` being drawn (a refreshed one after a Refresh).
- `MarkdownReaderTopBar` and `MarkdownReaderMenu` gain `onSaveToDevice: () -> Unit`; the menu appends one `DropdownMenuItem` after Open in another app, dismiss-then-act.
- `strings.xml`: `markdown_reader_save_to_device` = "Save to device".

## State + concurrency model

The pending text lives in one `remember`ed holder in the reader's composition; it is set on the Main thread when the picker launches and read and cleared on the Main thread in the result callback. The write runs in the reader's `rememberCoroutineScope()` on `Dispatchers.IO`. `copyAttachment` is blocking and not interruptible, so once the write has started it either finishes or fails whole, even if the operator leaves the reader. If leaving the reader cancels the job before the write starts, nothing is written and no notice shows. The document is then left empty, the same as the thread's attachment save today.

## Error handling

`SAVE_FAILED` covers a write or open that throws (the created document is deleted), a provider that returns no stream (`checkNotNull` throws inside `copyAttachment`), and lost pending text after recreation (the document is deleted without being written). `SAVED` shows only after the bytes are flushed. A cancelled picker produces no notice. Exceptions are never read or logged. A delete that itself throws is swallowed by `runCatching`, as in the attachment save.

## Testing strategy

- `AttachmentActionsTest` (`app/src/test`): `saveNoteText` writes the exact UTF-8 bytes of text with multi-byte characters and does not discard; a failing output stream returns `false` and discards; `null` text returns `false`, discards and never opens the output.
- `MarkdownReaderScreenTest` (`app/src/sharedTest`, Robolectric): the overflow shows six items in order with Save to device last. With a fake `ActivityResultRegistry` provided through `LocalActivityResultRegistryOwner`:
  - Choosing Save to device launches `ACTION_CREATE_DOCUMENT` of type `text/markdown` with `EXTRA_TITLE` = `Plan.md` for a note named `notes/Plan.md`.
  - Returning a URI served by a test content provider writes exactly the text on screen and shows "File saved".
  - A provider whose write fails receives the delete call and the reader shows "Couldn't save file".
  - Returning `null` writes nothing and shows neither notice.
- The lost-text path is proven by the `saveNoteText(null, …)` unit test. Recreating the process mid-picker is not driven in Robolectric.
- No rung-3 scenario: the save ends in the system's document picker, which the harness cannot drive, and the daemon is not involved (as for #1068).

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/markdown-reader-screen.md` § "Copy and refresh menu (since #1067)" should gain the sixth item, Save to device: its reuse of `CreateAttachmentDocument` and `copyAttachment`, the in-memory-only pending text and the delete-on-recreation behaviour, and the new tests.

## Open questions

- Whether Robolectric's `ContentResolver.openOutputStream` reaches a test provider's `openFile`, and whether `DocumentsContract.deleteDocument` reaches its `call`. To be resolved in Phase B. If they do not, the screen test uses Robolectric's registered output stream for the write and keeps the discard assertion in the unit test.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The note's name is daemon-authored. It reaches the picker only as `EXTRA_TITLE` through `sharedNoteFileName`, which takes the last path component, drops control and format characters, bounds it to 255 UTF-8 bytes and falls back to `note.md`. The picker is the system's own UI and chooses the final file name. The text is written as bytes and never parsed.
- [File / storage: path and scope] No findings. The app never builds a path. The destination is the URI the system picker returns after the operator's explicit choice, and the app gets no persistable or tree grant. The app writes only to that one URI (`"wt"`) and deletes only that one URI.
- [File / storage: partial writes] No findings. Any failure in `copyAttachment` deletes the created document, so no truncated file survives a failed write. A process killed mid-write can leave a partial document, the same trade-off #985 accepted. The operator picked the location, and the app cannot recover it after death.
- [File / storage: lost text after recreation] Addressed by design. The text is never put in saved state. With no pending text, the created document is deleted instead of anything else being written, per the ticket's technical note.
- [File / storage: stale pending text] No findings. The holder is cleared in every callback, including a cancel, so a later result can never write text left from an earlier save.
- [Inter-process] No findings. There is no new exported component or provider, no pending intent and no URI grant. `ACTION_CREATE_DOCUMENT` carries only the type, the openable category and the title. The returned URI is used only in-process.
- [Logs] No findings. One line with a static outcome and a length. It never contains the text, the name, the URI or an exception message.
- [Concurrency] No findings. The holder is touched only on Main. The write job belongs to the reader's composition scope, and the write itself is uninterruptible, so it finishes or fails whole.
- [Tokens / crypto / network] Not applicable. There are no secrets, no randomness and no network, and the text is already in memory.
- [Threat model] OUT OF SCOPE. Where the operator saves the note, for example in a synced cloud folder, is their choice in the system picker.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

- **2026-09-25, implementation.** Open question resolved: the screen test compiles into the device test set too, so it cannot use Robolectric's `setupContentProvider`. It writes through a `file://` URI into the app's cache directory instead. Robolectric's `openOutputStream(uri, "wt")` passes an unregistered URI to the real resolver, and a device resolves it the same way. A missing parent directory makes the write fail. `DocumentsContract.deleteDocument` cannot reach a `file://` URI and throws, and `runCatching` drops the exception, so the discard is asserted only in the `saveNoteText` unit tests. The failed-write screen test asserts the notice and that no file exists.
