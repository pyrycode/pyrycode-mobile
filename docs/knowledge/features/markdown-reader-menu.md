# Markdown reader menu (since #1067)

The top bar's overflow menu on [the markdown reader](markdown-reader-screen.md) — Copy as markdown/plain text/HTML, Refresh, Open in another app (since #1068) and Save to device (since #1069), plus their logging. Split out of the parent under the docs guard's size cap (#1533).

## Presentation

The top bar's overflow button (`Icons.Filled.MoreVert`, `cd_more_actions`, 48dp touch target)
opens the shared [Options overlay](options-overlay.md) in Actions mode since #1667,
following Juhana's 2026-10-03 decision. Figma `533:1958` replaces the earlier `675:5883`
reference; there is no separate reader-menu frame. Its 1 × 1 screenshot cannot establish
an exact open-menu pixel match, so the existing composer Actions component is the appearance reference.
The reader uses the shared light/dark surface, bodySmall labels and row insets, with no
selected-row indicator or subset caption.

The reader mounts the overlay last in its full-size layer, above content, chrome and snackbar.
Live button window bounds are translated by that layer's window origin. Below placement starts
4dp below the button, aligns at `anchor.left - 12dp`, clamps to 8dp horizontal edges and scrolls
within the space below. Remembered visibility survives ordinary recomposition and follows the
live anchor; configuration changes may close it. `markdown-reader-menu` tags the actual column.
Outside taps and system Back dismiss without navigation or activating underlying controls or links.

Six resource-labelled rows retain this order, each dismissing before invoking its current callback:
**Copy as Markdown**, **Copy as plain text**, **Copy as HTML**, **Refresh**, **Open in another app**
([below](#open-in-another-app-since-1068)), **Save to device**
([below](#save-to-device-since-1069)). Copies, open and save use the currently displayed document,
including after Refresh. Refresh's success, failure and in-flight behavior is unchanged.
The menu is available only after the first read succeeds; a failed first read never opens the reader.

## Copy

All three copy formats act on the `document` currently on screen — after a Refresh, on the refreshed one — and
go out through `LocalClipboardManager.setClip(ClipEntry(clipData))`. None shows a snackbar of its own: every
supported device (min SDK 33) already confirms a copy at the platform level, the same precedent
[`MessageMetaRow`'s `CopyTextControl`](message-bubble.md#meta-row-and-copy-control-messagemetarowkt-since-644)
set. The clip label is the static string `markdown_reader_clip_label`, never the note's name.

- **Copy as markdown** — `ClipData.newPlainText(label, boundClipText(document.text))`: the raw file text,
  bounded.
- **Copy as plain text** — `ClipData.newPlainText(label, boundClipText(markdownPlainText(document.text)))`.
- **Copy as HTML** — one `ClipData.newHtmlText(label, plainTextFallback, html)`: a single clip item carrying
  both the generated HTML and the plain-text form as its fallback, each independently bounded.

`markdownPlainText` and `markdownHtml` are new pure functions in
`ui/conversations/components/MarkdownConversions.kt`, no Compose runtime, no Android types — parsed with the
same `MarkdownFlavour` (GFM) [`MarkdownText`](markdown-text.md) uses, so the copies match what the screen
actually renders rather than a second, potentially-drifting notion of "this note's markdown":

- **Plain text** walks the AST directly for block structure (headings — all six ATX levels, even though the
  renderer shows `####`+ as raw source, because the AC promises no heading markers at any level; lists, one
  line per item with no bullet/number/task box, nested lists indented two spaces per level; block quotes, no
  `>`; fenced/indented code kept verbatim via the renderer's own `fencedCodeText`/`indentedCodeText`; tables,
  header then body rows tab-separated, within the renderer's `MAX_TABLE_ROWS`/`MAX_TABLE_COLUMNS` bounds) but
  defers every inline span — paragraph text, heading content, table cells — to
  [`inlineText`](markdown-text-internals.md#inline-dispatch), the renderer's own inline walk exposed for this purpose.
  That is what makes "the rendered text" true by construction: emphasis/code/strike delimiters and link targets
  drop exactly as the screen hides them. **Lesson from implementation:** a block quote's continuation `>` and
  the space after it sit inside the *paragraph* node, not the quote's own children — a plain-text extractor
  that walks only the paragraph keeps them, rendering a two-line quote as `words  and more` with a double
  space; `blockText`'s `PARAGRAPH` arm filters both out explicitly.
- **HTML** runs the same parse through `org.intellij.markdown`'s `HtmlGenerator` with the flavour's own
  provider map, then overrides the providers that would otherwise put note-authored text somewhere unsafe. This
  is the one new place note text leaves the app (another app renders the pasted HTML), so every override
  matters for the security boundary, not just fidelity:
  - **Raw HTML is text.** The HTML-block and inline-HTML-tag providers are replaced with one that
    HTML-escapes the node's source and writes it as a `<p>` (block) or inline text.
  - **One href allowlist for every link-producing node** — inline links, reference links, `<autolink>` and a
    GFM bare URL all resolve through [`isSafeLinkScheme`](markdown-text-internals.md#link-safety--scheme-allowlist)
    (`http`/`https`/`mailto`); anything else writes the link's text alone, with no `href`. Titles are dropped.
    **Lesson:** `LinkGeneratingProvider.RenderInfo.destination` arrives already entity-decoded by the library,
    so escaping its `&` again turned `?b=1&c=2` into `&amp;amp;`; the href is escaped for `"`/`'`/`<`/`>` only,
    leaving `&` alone, and the scheme check runs on that decoded value so `java&#115;cript:` is judged as
    `javascript:`. The autolink provider reads *raw* source instead, so it escapes `&` as well as the other
    four characters.
  - **Images become alt text only** — no `<img>` ever reaches the clip, so a remote `src` can never fetch
    anything on the note author's behalf.
  - **The code-fence provider is always replaced**, not conditionally — `<pre><code>` with the code escaped, no
    `class` attribute — so a fence info string can never reach an attribute regardless of what the library
    itself would have escaped. A revision during implementation dropped the earlier "replace it only if the
    library doesn't already escape it" plan in favour of always replacing it, since that removes any dependence
    on the library's own behaviour.
  - No other attribute in the generated HTML carries note-authored text.
- **`internal fun boundClipText(text)`** — `text.take(MAX_CLIPBOARD_CHARS)`. **`internal fun
  boundClipHtml(html)`** — unchanged within the bound; otherwise cut after the last `>` inside it, so the cut
  never lands inside a tag or an entity. `MAX_CLIPBOARD_CHARS` moved from `private` in `MessageMetaRow.kt` to
  `internal` (visibility only) so both copy paths share the one number — a parcelled clip has a Binder ceiling
  near 1 MB, and a note at the reader's own `MAX_MARKDOWN_READER_BYTES` (256 KiB) must not crash a copy.

## Refresh

`@Composable fun RefreshableMarkdownReader(initial: MarkdownDocument, reread: suspend () -> MarkdownDocument?,
onBack, modifier)` owns the refresh and is the one real caller of `MarkdownReaderScreen`. `document` is
`remember(initial) { mutableStateOf(initial) }`, so it survives a Refresh but not a new attachment/link
identity. A tap holds a `Job` in `remember`; if it is still active, a second tap does nothing — the read
already in flight is the only one that can complete. A non-null result from `reread()` replaces `document` in
place, keeping the scroll position wherever `MarkdownText` happens to re-lay it out; `null` leaves `document`
untouched and shows `AttachmentNotice.OPEN_FAILED`'s "Couldn't open file" string in the reader's own
`SnackbarHostState` — the same string the thread's own failed-open snackbar uses, but scoped to the reader so
the operator doesn't have to leave it to see the failure. Leaving the reader cancels the scope and any read
still in flight.

**Lesson from the verifier's rework round:** the failure snackbar first ran *inside* the same `Job` as the
read, so `refreshJob?.isActive` stayed `true` for the ~4s the "Couldn't open file" snackbar was showing —
retrying, the natural next action after that notice, silently did nothing. The fix launches the snackbar in
its own `scope.launch { … }`, so the in-flight guard covers only the read; a retry while the notice is still
showing reads again. Pinned by `MarkdownReaderScreenTest.aRetry_whileTheFailureNoticeShows_readsAgain`.

## Open in another app (since #1068)

The menu's last item, `markdown_reader_open_in_app`, hands the `document` currently on screen — after a
Refresh, the refreshed one — to another app as `text/markdown`, through the system chooser, mirroring the
read-only single-URI grant [`openAttachment`](message-bubble-attachment-slot.md#open-and-save-since-985) gives
a stored attachment. A linked note (since #1050) has no file on disk at all, and a stored attachment's kept
file can be stale after a Refresh, so the text on screen is always written out fresh first rather than handing
on whatever file (if any) already exists.

- **`SharedNoteFile.kt`** (new, `ui/conversations/thread/`, pure JVM, no Compose or Android types):
  `sharedNoteDirectory(noBackupFilesDir)` is `<noBackupFilesDir>/attachments/shared-note` — a child of the same
  root [`AttachmentStore`](attachment-retrieval.md#host-store--datacacheattachmentstorekt) and the attachment
  `FileProvider` use, but named `shared-note`, which is not a 64-character hex digest. That is what keeps it
  safe from `AttachmentStore.removeHost`'s recursive per-host delete (`<root>/<sha256hex(serverId)>`), which
  can never collide with or reach a name outside that shape, and it never deletes anything but its own
  contents in return. `sharedNoteFileName(name)` takes the last component after the last `/` and `\` (a
  daemon-authored name — an attachment's `displayName` or a link's path text — can carry either separator),
  runs it through `attachmentDisplayName` (the same control/format-character strip and 255-UTF-8-byte bound
  attachment names already get, see [Attachment retrieval](attachment-retrieval.md)), and falls back to
  `note.md` for an empty, `.` or `..` result. `writeSharedNote(directory, name, text)` runs under one
  process-wide lock: creates the directory, deletes every entry already there (so at most one note's file
  exists at a time — an open never accumulates), resolves the name inside it, and refuses (`null`) unless the
  resolved file's canonical parent is still the directory's own canonical file — the same
  climb-back-out defence `writeSharedNote`'s own test (`aNameThatWouldClimbOut_staysInside`) pins, on top of
  `FileProvider`'s independent canonicalisation against `attachment_paths.xml`'s one root. Any exception is
  `null`, unread — an exception message can carry the path.
- **`openNoteInAnotherApp(context, document, chooserTitle, ioDispatcher)`** (`AttachmentActions.kt`): writes the
  note on `ioDispatcher` (`OPEN_FAILED` on `null`), resolves its content URI through the same
  `attachmentContentUri` an opened attachment uses (`OPEN_FAILED` on `null`), builds an `ACTION_VIEW` /
  `text/markdown` intent carrying exactly `FLAG_GRANT_READ_URI_PERMISSION` — never write, persistable or
  prefix — then checks `packageManager.queryIntentActivities` before starting anything: an empty result is
  `NO_APP`, because handing an unresolvable `ACTION_VIEW` to `Intent.createChooser` would show an empty chooser
  rather than throw. `startActivity(Intent.createChooser(view, chooserTitle))` follows; `createChooser` itself
  migrates only that read grant into the chooser's own `ClipData` for the picked target.
  `ActivityNotFoundException` is also `NO_APP`; any other exception is `OPEN_FAILED`; a started chooser is
  `null`. `AndroidManifest.xml` gained a `<queries>` element (`VIEW` + `content` scheme + `text/markdown` type)
  so the `queryIntentActivities` check sees installed viewers under Android 11+ package visibility — without
  it the query would under-report regardless of what is actually installed.
- **`MarkdownReaderScreen`** owns the action the same way it owns the copies: the tap captures the `document`
  it is currently drawing, launches in `rememberCoroutineScope()`, and a non-null notice shows in the reader's
  own `snackbarHostState` — `AttachmentNotice.NO_APP` ("No app can open this file") or the existing
  `OPEN_FAILED` ("Couldn't open file"), the same strings the thread's own attachment-open failures use.
  Notice strings are resolved with `stringResource` before the coroutine launches, not `Context.getString`
  inside it (lint `LocalContextGetResourceValueCall`).
- **Accepted stale-grant gap** (from the ticket's security review): the app the operator picked loses its read
  grant on the previous file the moment the *next* open deletes it, and it cannot resolve the new file unless
  the note's name is unchanged (a different URI). Recorded as accepted rather than fixed — one note stays on
  disk at a time is the design, and a grant to text the operator already handed the app for the same note is
  not a new exposure.

**Lesson from implementation:** `MarkdownReaderScreenTest` needed the same `FileProvider.sCache` reset
`AttachmentActionsTest` already used (see [Testing](markdown-reader-screen.md#testing)) — without it, a shared-note file served in one
test could leave the provider's authority-to-root cache pointed at a data directory Robolectric had already
torn down for the next test in the same JVM, turning an expected `NO_APP` into `OPEN_FAILED`.

Both destinations wrap `RefreshableMarkdownReader` rather than `MarkdownReaderScreen` directly:
`MarkdownReaderDestination`'s `Loaded` arm passes `reread = { readMarkdownAttachment(repository,
conversationId, attachmentId) }` (see [The reader destination](markdown-reader-screen.md#the-reader-destination));
`LinkedMarkdownReaderDestination` passes `reread = { reread(note.path) }`, where the outer `reread` parameter
is `MainActivity`'s `{ path -> readLinkedMarkdown(destinations.repository(target.serverId), target.conversationId,
path) }` (see [Linked note, live](markdown-reader-screen.md#linked-note-live-since-1050)) — so a linked note's Refresh sends another
`read_workspace_file`, proven live by the extended `interactiveTurn_markdownLink_opensLiveNoteInReader`
scenario (see [Testing](markdown-reader-screen.md#testing) and [Interactive stream e2e](../../e2e-interactive-stream.md)).

## Save to device (since #1069)

The menu's last item writes `document.text`, as it stood when the picker opened, into a document the operator
picks through the same system create-document picker the thread's own attachment save uses, then reports the
outcome with the existing `AttachmentNotice.SAVED` / `SAVE_FAILED` snackbar strings.

- **`saveNoteText(text, openOutput, discard)`** (`AttachmentActions.kt`): `null` `text` — the pending text was
  lost, see below — discards the document without ever calling `openOutput` and returns `false`; otherwise it
  runs `copyAttachment` with `text.toByteArray(Charsets.UTF_8).inputStream()` as the source, so a failed write
  discards the document exactly as an attachment save does. Blocking; the caller runs it on `Dispatchers.IO`.
  This is the one new pure function — everything else reuses `CreateAttachmentDocument` and `copyAttachment`
  unchanged.
- **`rememberNoteSaver(onNotice): (MarkdownDocument) -> Unit`** (`AttachmentActions.kt`) is the reader's save,
  bound to its composition: one `rememberLauncherForActivityResult(CreateAttachmentDocument())`, and a private
  `PendingNote` holder — a plain `var` in a `remember`, never `rememberSaveable` — that carries the note's text
  across the picker. The returned function stores `document.text` in the holder and launches
  `Request(suggestedName = sharedNoteFileName(document.name), mimeType = "text/markdown")`, reusing
  [`sharedNoteFileName`](#open-in-another-app-since-1068) for the same last-path-component-and-sanitise
  treatment Open in another app already gives the note's name. The result callback takes the holder's text and
  clears it immediately, before deciding anything else, so a later result can never see stale text from an
  earlier save (the same discipline `RefreshableMarkdownReader` uses for its own in-flight guard). A `null`
  destination (cancelled) writes nothing and notifies nothing. A picked destination writes via `saveNoteText` on
  `Dispatchers.IO` inside `rememberCoroutineScope()`, then notifies `SAVED` or `SAVE_FAILED`.
- **Text lost across process death.** The pending text lives only in the `remember`ed holder — never in saved
  state — because the ticket's technical note ruled out writing stale or wrong text after a restart. If the
  activity or process is recreated while the picker is open, `ActivityResultRegistry` still redelivers the
  picked URI to the re-registered launcher, but the holder is now empty: `saveNoteText(null, …)` deletes the
  created document and the reader shows `SAVE_FAILED` rather than writing anything else in its place.
- **`MarkdownReaderScreen`** wires `onSaveToDevice = { saveNote(document) }`, so like the copies and Open in
  another app it acts on the `document` currently drawn — the refreshed one after a Refresh — not on whatever
  was on screen when the menu opened.
- Logs (see [Logging](#logging) below) never carry the text, the name or the picked URI, matching every other
  action in this menu.

**Lesson from implementation (screen-test provider access).** `MarkdownReaderScreenTest` lives in
`app/src/sharedTest`, which compiles into both the JVM (Robolectric) and device test sets (see
[Development verification § Where a screen test goes](development-verification-gates.md#where-a-screen-test-goes)),
so it cannot call a Robolectric-only API such as `Robolectric.setupContentProvider` to stand in for the
document the picker returns — that would fail to compile for the device target. The test instead answers the
picker with a `file://` URI inside the app's cache directory: Robolectric 4.17's
`ContentResolver.openOutputStream(uri, "wt")` passes an unregistered URI straight to the real resolver, which
resolves a `file://` URI the same way a device does, so a missing parent directory makes the write fail exactly
as it would on a device. `DocumentsContract.deleteDocument` cannot reach a `file://` URI at all and throws,
which `saveNoteText`'s own `runCatching` swallows — so the discard call is proven only in the `saveNoteText`
unit tests (`AttachmentActionsTest`), and the failed-write screen test instead asserts the "Couldn't save file"
notice and that no file exists at the destination.

## Logging

`RelayLog.d` only, static fields, never the text, name, path or clip contents:
`event=markdown_reader_copy format=markdown|plain|html chars=<source length>`,
`event=markdown_reader_refresh outcome=loaded|failed`,
`event=markdown_reader_open_in_app outcome=opened|no_app|failed chars=<text length>` (since #1068), and (since
\#1069) `event=markdown_reader_save outcome=saved|failed|cancelled chars=<length, or -1 when lost>`.
