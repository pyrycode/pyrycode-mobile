# #1068 — Open the markdown reader's note in another app

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`, `MarkdownReaderTopBar`, `MarkdownReaderMenu`, `RefreshableMarkdownReader`, `MarkdownDocument`, `linkedMarkdownName` — the menu gains its fifth item here; `document` is the text on screen, refreshed in place by `RefreshableMarkdownReader`; the reader's `snackbarHostState` is where notices show.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt` → `openAttachment`, `attachmentViewIntent`, `attachmentContentUri`, `attachmentProviderAuthority`, `AttachmentNotice` — the hand-on pattern this ticket mirrors, and the read-only-grant intent it reuses.
- `app/src/main/res/xml/attachment_paths.xml` — the provider's one root, `no_backup/attachments/`.
- `app/src/main/AndroidManifest.xml` → the non-exported `FileProvider` with `grantUriPermissions`; no `<queries>` element yet.
- `app/src/main/java/de/pyryco/mobile/data/cache/AttachmentStore.kt` → `AttachmentStore.removeHost` — deletes only `<root>/<sha256hex(serverId)>`; nothing lists or sweeps the root, so a directory named outside the 64-hex shape is never touched by a host removal.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttachmentStore(File(noBackupFilesDir, "attachments"))` wiring the provider root mirrors.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `attachmentDisplayName`, `truncateUtf8`, `ATTACHMENT_TEXT_MAX_BYTES` — strips control/format characters and bounds to 255 UTF-8 bytes, but keeps `/` and `\`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActionsTest.kt` — Robolectric shape for the provider root and the view intent (`sCache` reset, `shadowOf(packageManager)` viewer registration).
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt` → `theOverflow_opensTheFourItems_inOrder`, `choose` — the menu-order test this ticket extends.
- `docs/knowledge/features/markdown-reader-screen.md` § "Copy and refresh menu (since #1067)" — dismiss-then-act menu shape; copies act on the current `document`; logs never carry the name, path or text.

No other in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958 (`Options overlay`); reader https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

The reader's overflow is the existing plain M3 `DropdownMenu` of text-only `DropdownMenuItem`s, built as `ThreadOverflowMenu` builds the thread's (the operator's 2026-09-24 decision). This ticket adds one more text item, **Open in another app**, directly after Refresh, in the same style; no new tokens, icons or decorations. (The `533:1958` node renders as an empty component frame through the MCP screenshot; the existing `MarkdownReaderMenu` is already the verified translation of it, so the new item copies that.)

## Context

The operator wants to edit or share a note shown in the in-app reader with their own tools (chosen 2026-09-24). A linked note (#1050) has no file on the phone, and after a Refresh (#1067) a stored attachment's text may differ from its kept file, so the text on screen is written to a file first and handed on as `text/markdown` through the system chooser, with the same read-only single-URI grant `openAttachment` uses (#985).

## Design

### `SharedNoteFile.kt` (new, `ui/conversations/thread/`, pure JVM)

- `internal const val SHARED_NOTE_DIRECTORY = "shared-note"` — a child of the attachment store root. Not 64 lowercase hex characters, so it can never equal a host directory `AttachmentStore` names by `sha256Hex(serverId)`, and `removeHost` never deletes it; it in turn never touches a host's files.
- `internal fun sharedNoteDirectory(noBackupFilesDir: File): File` — `<noBackupFilesDir>/attachments/shared-note`, inside the provider's one root, so `FileProvider`'s own canonicalisation still applies.
- `internal fun sharedNoteFileName(name: String): String` — the last path component (after the last `/` and the last `\`), through `attachmentDisplayName` (controls and format characters dropped, ≤ 255 UTF-8 bytes, the file-system name limit), trimmed; `""`, `.` and `..` fall back to `note.md`.
- `internal fun writeSharedNote(directory: File, name: String, text: String): File?` — under one process-wide lock: creates `directory`, deletes every entry already in it (each open replaces the previous one's file), resolves `File(directory, sharedNoteFileName(name))`, refuses (returns `null`) unless its canonical parent is `directory`'s canonical file, writes `text` as UTF-8, returns the file. Any exception is `null`, dropped unread (a message can carry the path).

### `AttachmentActions.kt`

- `internal suspend fun openNoteInAnotherApp(context: Context, document: MarkdownDocument, chooserTitle: String, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): AttachmentNotice?` —
  1. `writeSharedNote(sharedNoteDirectory(context.noBackupFilesDir), document.name, document.text)` on `ioDispatcher`; `null` → `OPEN_FAILED`.
  2. `attachmentContentUri(context, AttachmentSource.Kept(file))` — the provider's content URI; `null` → `OPEN_FAILED`.
  3. `attachmentViewIntent(uri, "text/markdown")` — `ACTION_VIEW`, `text/markdown`, flags exactly `FLAG_GRANT_READ_URI_PERMISSION`.
  4. `packageManager.queryIntentActivities(view, MATCH_DEFAULT_ONLY)` empty → `NO_APP` (a chooser with no targets never throws, so this check is what makes the notice reachable).
  5. `startActivity(Intent.createChooser(view, chooserTitle))` (+ `FLAG_ACTIVITY_NEW_TASK` for a non-Activity context). `createChooser` migrates the target's URI into the chooser's `ClipData` with the same read flag only. `ActivityNotFoundException` → `NO_APP`; any other exception → `OPEN_FAILED`; started → `null`.
- `AndroidManifest.xml` gains `<queries>` with `VIEW` + `scheme="content"` + `mimeType="text/markdown"`, so the step-4 query sees installed viewers under Android 11+ package visibility. The scheme is there because an intent with a type but no URI would not match filters that declare the `content` scheme.

### `MarkdownReaderScreen.kt`

- `MarkdownReaderMenu` gains `onOpenInApp: () -> Unit`, one `DropdownMenuItem` after Refresh (`markdown_reader_open_in_app`), dismiss-then-act.
- `MarkdownReaderScreen` owns the action, as it owns the copies: it acts on the `document` it is drawing (so after a Refresh, the refreshed text), launches in `rememberCoroutineScope()`, and shows a non-null notice's string in its `snackbarHostState`. Logs `event=markdown_reader_open_in_app outcome=opened|no_app|failed chars=<text length>`.
- `strings.xml`: `markdown_reader_open_in_app` = "Open in another app" (menu label and chooser title).

## State + concurrency model

One `launch` per tap in the reader's composition scope; leaving the reader cancels it (a cancelled write leaves at most a file the next open deletes). The file write runs on `Dispatchers.IO` (injectable); the intent start runs on Main. `writeSharedNote`'s lock makes clear-then-write atomic between two opens, so two taps never delete each other's file mid-write.

## Error handling

`OPEN_FAILED` for a failed write, a name that resolves outside the directory, or a file the provider refuses; `NO_APP` when nothing resolves the view intent (or the start throws `ActivityNotFoundException`). Both show in the reader's own snackbar with the existing strings. Exceptions are never read or logged.

## Testing strategy

- `SharedNoteFileTest` (`app/src/test`, JVM, `TemporaryFolder`): names — plain kept, `notes/Plan.md` → `Plan.md`, `a\b.md` → `b.md`, control characters dropped, `""`, `.`, `..`, `dir/` → `note.md`; the directory name is not host-digest shaped; write — exact UTF-8 bytes, file's parent is the directory, a second write with another name leaves only the second file; a directory path that is a regular file → `null`.
- `AttachmentActionsTest` (Robolectric): the shared-note file is served by the attachment provider with the note's name as its last path segment; with a `text/markdown` viewer registered, `openNoteInAnotherApp` starts an `ACTION_CHOOSER` whose `EXTRA_INTENT` is `VIEW` + `text/markdown` + a `content` URI of the attachments authority with flags exactly the read grant, and whose own grant flags carry no write, persistable or prefix bit; the file holds the exact text; with no viewer → `NO_APP` and nothing started; an unwritable directory → `OPEN_FAILED` and nothing started.
- `MarkdownReaderScreenTest` (Robolectric, shared): the overflow's five items in order, Open in another app last; choosing it with no viewer shows "No app can open this file".
- No rung-3 scenario: the hand-off ends in another app's chooser, which the harness cannot drive, and the daemon is not involved — the read that produced the text is already proven live by `interactiveTurn_markdownLink_opensLiveNoteInReader`.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/markdown-reader-screen.md` § "Copy and refresh menu (since #1067)" should gain the fifth item, the `shared-note` directory and its non-collision with host directories, and the new tests.

## Open questions

- Whether Robolectric records a chooser start with the target's grant migrated into `ClipData` — resolved in Phase B by the test; if not, the test asserts the target intent's flags only.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the note's name and text are daemon-authored. The name reaches the file system through one function, `sharedNoteFileName`, then a canonical-parent check in `writeSharedNote`; the text is written as bytes and never parsed. The name the receiving app sees is the file name, already sanitised.
- [File / storage — traversal] No findings — `/` and `\` are cut by taking the last component, `.`/`..`/empty fall back, and the canonical parent must equal the directory before anything is written; `FileProvider` canonicalises again and refuses anything outside `no_backup/attachments/`.
- [File / storage — collision] No findings — `shared-note` cannot be a `sha256Hex` host directory, so `AttachmentStore.removeHost`'s recursive delete never reaches it, and `writeSharedNote` deletes only inside `shared-note`.
- [File / storage — scope and accumulation] No findings — under `noBackupFilesDir` (app-private, not backed up), same scope as the kept attachments; each open empties the directory first, so at most one note stays on disk. The kept copy is plaintext, as the kept attachments already are.
- [File / storage — TOCTOU] No findings — the directory is app-private; no other app can swap a path between check and write. The lock serialises this app's own writers.
- [Inter-process] No findings — the provider stays non-exported with per-URI grants; the intent carries only `FLAG_GRANT_READ_URI_PERMISSION` (no write, persistable or prefix), `createChooser` migrates only that flag, the type is the fixed `text/markdown`, never a `file` URI. The `<queries>` element only widens what this app can see, not what can reach it.
- [Inter-process — stale grant] SHOULD FIX (accepted as designed) — an app granted the previous URI loses access when the next open deletes that file; it cannot read the new one, whose URI differs unless the name matches. When the name matches, the old grant does reach the new text of the same note the user just handed that app anyway. Recorded here so the verifier sees the decision.
- [Logs] No findings — one log line with a static outcome and the text length; never the text, the name, the path or an exception message.
- [Concurrency] No findings — the job lives in the reader's composition scope; the file lock bounds concurrent writers; cancellation leaves at most a file the next open deletes.
- [Tokens / crypto / network] Not applicable — no secrets, no randomness, no network: the text is already in memory.
- [Threat model] OUT OF SCOPE — what the chosen app does with the note (upload, sync) is the user's choice through the system chooser.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

- **2026-09-25, implementation.** Open question resolved: Robolectric records the `ACTION_CHOOSER` start, so `AttachmentActionsTest` asserts both the target intent (exactly the read grant) and that the chooser's own flags carry no write, persistable or prefix bit. `MarkdownReaderScreenTest` gained the same `FileProvider.sCache` reset `AttachmentActionsTest` uses: without it the new screen test passed alone and failed after `AttachmentActionsTest` in the same JVM, because the cached provider root pointed at an earlier test's deleted data directory and the open reported `OPEN_FAILED` instead of `NO_APP`. `MarkdownReaderScreen` resolves the notice strings with `stringResource` up front rather than `Context.getString` in the coroutine (lint `LocalContextGetResourceValueCall`).
